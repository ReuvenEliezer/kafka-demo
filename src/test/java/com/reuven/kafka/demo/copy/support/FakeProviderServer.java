package com.reuven.kafka.demo.copy.support;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Purpose-built provider test double (research.md R20). Serves deterministic synthetic bytes from a
 * seeded, position-addressable pattern via {@link #byteAt} — never materialised in full, so it can
 * serve arbitrary sizes at effectively zero memory cost and makes byte-identity assertions exact
 * (SC-007). Genuine {@code Range} support, plus switchable faults: ignore-range,
 * fail-after-N-bytes, expire-credential-after-N-bytes, delete-recording.
 *
 * <p>Plain HTTP, not HTTPS, per R20's explicit choice. {@code ProviderHostAllowlist} exempts loopback
 * addresses from the https-only rule specifically so this fixture works without weakening the
 * production posture — see the comment there.
 *
 * <p>Wire format (this repository's own invention — the spec does not fix a real provider's API):
 * {@code POST /recordings/{id}/credentials}, {@code GET /recordings/{id}/metadata?token=},
 * {@code GET /recordings/{id}/content?token=} (with optional {@code Range}), and
 * {@code POST /recordings/{id}/release?token=}.
 */
public class FakeProviderServer implements Closeable {

    private static final Pattern PATH_PATTERN =
            Pattern.compile("^/recordings/([^/]+)/(credentials|metadata|content|release)$");

    private final HttpServer httpServer;
    private final ExecutorService executor;
    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private final Clock clock;
    private final Map<String, RecordingState> recordings = new ConcurrentHashMap<>();

    public FakeProviderServer() throws IOException {
        this(Clock.systemUTC());
    }

    public FakeProviderServer(Clock clock) throws IOException {
        this.clock = clock;
        this.httpServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        this.executor = Executors.newCachedThreadPool();
        httpServer.createContext("/recordings/", this::handle);
        httpServer.setExecutor(executor);
        httpServer.start();
    }

    public String baseUrl() {
        return "http://localhost:" + httpServer.getAddress().getPort();
    }

    @Override
    public void close() {
        httpServer.stop(0);
        executor.shutdownNow();
    }

    // ---- Test configuration API ------------------------------------------------

    public void registerRecording(String recordingFileId, long sizeBytes) {
        recordings.put(recordingFileId, new RecordingState(sizeBytes));
    }

    public void setIgnoreRange(String recordingFileId, boolean ignoreRange) {
        state(recordingFileId).ignoreRange = ignoreRange;
    }

    /** The next response for this recording fails after N bytes of that response; cleared once it fires. */
    public void setFailAfterBytes(String recordingFileId, long bytes) {
        state(recordingFileId).failAfterBytes = bytes;
    }

    public void setExpireCredentialAfterBytes(String recordingFileId, long bytes) {
        state(recordingFileId).expireCredentialAfterBytes = bytes;
    }

    public void setDeleted(String recordingFileId, boolean deleted) {
        state(recordingFileId).deleted = deleted;
    }

    /** Sleeps this long before writing each 8 KiB chunk — a slow provider, for checkpoint-sliding-TTL tests. */
    public void setThrottle(String recordingFileId, java.time.Duration delayPerChunk) {
        state(recordingFileId).throttleDelay = delayPerChunk;
    }

    /** Lifetime of credentials minted from now on for this recording; default one hour. */
    public void setCredentialLifetime(String recordingFileId, java.time.Duration lifetime) {
        state(recordingFileId).credentialLifetime = lifetime;
    }

    public int credentialMintCount(String recordingFileId) {
        return state(recordingFileId).credentialMintCount.get();
    }

    public int metadataCallCount(String recordingFileId) {
        return state(recordingFileId).metadataCallCount.get();
    }

    public long bytesServed(String recordingFileId) {
        return state(recordingFileId).bytesServed.get();
    }

    public int releaseSignalsReceived(String recordingFileId) {
        RecordingState state = recordings.get(recordingFileId);
        return state == null ? 0 : state.releaseSignalsReceived.get();
    }

    private RecordingState state(String recordingFileId) {
        RecordingState state = recordings.get(recordingFileId);
        if (state == null) {
            throw new IllegalArgumentException("Unknown recording: " + recordingFileId);
        }
        return state;
    }

    /** Deterministic, position-addressable synthetic byte, usable by tests to verify uploaded content. */
    public static byte byteAt(String recordingFileId, long offset) {
        long x = recordingFileId.hashCode() ^ offset;
        x ^= (x << 13);
        x ^= (x >>> 7);
        x ^= (x << 17);
        return (byte) x;
    }

    // ---- Request handling --------------------------------------------------------

    private void handle(HttpExchange exchange) {
        try (exchange) {
            // Defensive: a fault-injected response closes mid-body without honouring its declared
            // Content-Length, so avoid letting java.net.http.HttpClient pool and reuse a connection
            // that might be in that state.
            exchange.getResponseHeaders().add("Connection", "close");
            Matcher matcher = PATH_PATTERN.matcher(exchange.getRequestURI().getPath());
            if (!matcher.matches()) {
                sendError(exchange, 404, "Unknown path");
                return;
            }
            String recordingFileId = matcher.group(1);
            String operation = matcher.group(2);
            RecordingState state = recordings.get(recordingFileId);
            if (state == null || state.deleted) {
                sendError(exchange, 404, "Recording not found");
                return;
            }

            switch (operation) {
                case "credentials" -> handleCredentials(exchange, state);
                case "metadata" -> handleMetadata(exchange, state);
                case "content" -> handleContent(exchange, recordingFileId, state);
                case "release" -> handleRelease(exchange, state);
                default -> sendError(exchange, 404, "Unknown operation");
            }
        } catch (IOException e) {
            // Connection likely already broken (e.g. a deliberately simulated failure); nothing to send.
        }
    }

    private void handleCredentials(HttpExchange exchange, RecordingState state) throws IOException {
        String token = java.util.UUID.randomUUID().toString();
        Instant expiresAt = Instant.now(clock).plus(state.credentialLifetime);
        state.issuedCredentials.put(token, new CredentialState(expiresAt, state.bytesServed.get()));
        state.credentialMintCount.incrementAndGet();

        writeJson(exchange, 200, Map.of("token", token, "expiresAt", expiresAt.toString()));
    }

    private void handleMetadata(HttpExchange exchange, RecordingState state) throws IOException {
        if (!isCredentialValid(state, queryParam(exchange, "token"))) {
            sendError(exchange, 401, "Invalid or expired credential");
            return;
        }
        state.metadataCallCount.incrementAndGet();
        writeJson(exchange, 200, Map.of("sizeBytes", state.sizeBytes, "contentType", "application/octet-stream"));
    }

    private void handleContent(HttpExchange exchange, String recordingFileId, RecordingState state) throws IOException {
        if (!isCredentialValid(state, queryParam(exchange, "token"))) {
            sendError(exchange, 401, "Invalid or expired credential");
            return;
        }

        String rangeHeader = exchange.getRequestHeaders().getFirst("Range");
        boolean rangeRequested = rangeHeader != null;
        long fromByte = rangeRequested ? parseRangeStart(rangeHeader) : 0;
        boolean honourRange = rangeRequested && !state.ignoreRange;
        long servedFrom = honourRange ? fromByte : 0;
        long remaining = state.sizeBytes - servedFrom;

        // Chunked transfer encoding (length 0, per HttpExchange#sendResponseHeaders), not a fixed
        // Content-Length: com.sun.net.httpserver's fixed-length framing does not reliably signal an
        // error to the client when a response is abandoned mid-write (as a fault-injected one
        // deliberately is) — the client can end up parked waiting for bytes that were promised but
        // will never arrive. An incomplete chunked stream (no terminating zero-length chunk) is an
        // unambiguous protocol violation the client detects immediately instead.
        if (honourRange) {
            exchange.getResponseHeaders().add("Content-Range",
                    "bytes %d-%d/%d".formatted(servedFrom, state.sizeBytes - 1, state.sizeBytes));
            exchange.sendResponseHeaders(206, 0);
        } else {
            exchange.sendResponseHeaders(200, 0);
        }

        Long failAfter = state.failAfterBytes;
        java.time.Duration throttle = state.throttleDelay;
        try (OutputStream out = exchange.getResponseBody()) {
            long written = 0;
            byte[] buffer = new byte[8192];
            while (written < remaining) {
                if (throttle != null) {
                    sleepQuietly(throttle);
                }
                int chunk = (int) Math.min(buffer.length, remaining - written);
                if (failAfter != null && written + chunk > failAfter) {
                    chunk = (int) Math.max(0, failAfter - written);
                    fill(buffer, recordingFileId, servedFrom + written, chunk);
                    out.write(buffer, 0, chunk);
                    state.bytesServed.addAndGet(chunk);
                    state.failAfterBytes = null;
                    throw new IOException("Simulated provider connection failure after " + (written + chunk) + " bytes");
                }
                fill(buffer, recordingFileId, servedFrom + written, chunk);
                out.write(buffer, 0, chunk);
                written += chunk;
                state.bytesServed.addAndGet(chunk);
            }
        }
    }

    private void handleRelease(HttpExchange exchange, RecordingState state) throws IOException {
        state.releaseSignalsReceived.incrementAndGet();
        writeJson(exchange, 200, Map.of("outcome", "RELEASED"));
    }

    private static void fill(byte[] buffer, String recordingFileId, long baseOffset, int length) {
        for (int i = 0; i < length; i++) {
            buffer[i] = byteAt(recordingFileId, baseOffset + i);
        }
    }

    private boolean isCredentialValid(RecordingState state, String token) {
        if (token == null) {
            return false;
        }
        CredentialState credential = state.issuedCredentials.get(token);
        if (credential == null || credential.expiresAt.isBefore(Instant.now(clock))) {
            return false;
        }
        Long expireAfter = state.expireCredentialAfterBytes;
        return expireAfter == null
                || credential.mintedAtByteCount >= expireAfter
                || state.bytesServed.get() < expireAfter;
    }

    private static long parseRangeStart(String rangeHeader) {
        String spec = rangeHeader.replaceFirst("(?i)bytes=", "");
        String start = spec.split("-", 2)[0];
        return start.isBlank() ? 0 : Long.parseLong(start);
    }

    private static String queryParam(HttpExchange exchange, String name) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null) {
            return null;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name)) {
                return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private void writeJson(HttpExchange exchange, int status, Map<String, ?> body) throws IOException {
        byte[] json = objectMapper.writeValueAsBytes(body);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, json.length);
        exchange.getResponseBody().write(json);
    }

    private static void sendError(HttpExchange exchange, int status, String message) throws IOException {
        byte[] body = message.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }

    private static void sleepQuietly(java.time.Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class RecordingState {
        final long sizeBytes;
        volatile boolean ignoreRange;
        volatile Long failAfterBytes;
        volatile Long expireCredentialAfterBytes;
        volatile boolean deleted;
        volatile java.time.Duration throttleDelay;
        volatile java.time.Duration credentialLifetime = java.time.Duration.ofHours(1);
        final AtomicInteger credentialMintCount = new AtomicInteger();
        final AtomicInteger metadataCallCount = new AtomicInteger();
        final AtomicLong bytesServed = new AtomicLong();
        final AtomicInteger releaseSignalsReceived = new AtomicInteger();
        final Map<String, CredentialState> issuedCredentials = new ConcurrentHashMap<>();

        RecordingState(long sizeBytes) {
            this.sizeBytes = sizeBytes;
        }
    }

    private record CredentialState(Instant expiresAt, long mintedAtByteCount) {
    }
}
