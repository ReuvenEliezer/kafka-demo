package com.reuven.kafka.demo.copy.provider;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.reuven.kafka.demo.copy.config.CopyProperties;
import com.reuven.kafka.demo.copy.exception.ProviderUnavailableException;
import com.reuven.kafka.demo.copy.exception.RecordingNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;

/**
 * {@link ProviderClient} over {@code java.net.http.HttpClient} (research.md R11-R13). Redirects are
 * never auto-followed — {@link #openDownload} re-checks the allowlist on every hop itself, which is
 * the only way an allowlisted host that redirects inward is caught (FR-062).
 *
 * <p>Every call is built from {@code copy.provider.base-url} and the recording's stable identifier
 * alone, never from a URL carried on the originating message — a credential or URL captured at
 * notification time would be long expired by the time a multi-day retry span elapsed (FR-059).
 */
@Component
@Slf4j
@ConditionalOnProperty(name = "copy.consumer.strategy", havingValue = "staged")
public class HttpProviderClient implements ProviderClient {

    private static final int MAX_REDIRECTS = 5;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ProviderHostAllowlist allowlist;
    private final CopyProperties properties;

    public HttpProviderClient(ObjectMapper objectMapper, ProviderHostAllowlist allowlist, CopyProperties properties) {
        this.objectMapper = objectMapper;
        this.allowlist = allowlist;
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.provider().connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public ProviderCredential mintDownloadCredential(String recordingFileId) {
        URI uri = recordingUri(recordingFileId, "credentials", null);
        HttpRequest request = requestBuilder(uri).POST(HttpRequest.BodyPublishers.noBody()).build();
        HttpResponse<String> response = sendString(request, recordingFileId);
        ensureSuccess(response.statusCode(), recordingFileId);
        JsonNode node = parseJson(response.body(), recordingFileId);
        return new ProviderCredential(node.get("token").asText(), Instant.parse(node.get("expiresAt").asText()));
    }

    @Override
    public RecordingMetadata fetchMetadata(String recordingFileId, ProviderCredential credential) {
        URI uri = recordingUri(recordingFileId, "metadata", credential.token());
        HttpRequest request = requestBuilder(uri).GET().build();
        HttpResponse<String> response = sendString(request, recordingFileId);
        ensureSuccess(response.statusCode(), recordingFileId);
        JsonNode node = parseJson(response.body(), recordingFileId);
        Instant lastModified = node.hasNonNull("lastModified") ? Instant.parse(node.get("lastModified").asText()) : null;
        return new RecordingMetadata(node.get("sizeBytes").asLong(), node.path("contentType").asText(null), lastModified);
    }

    @Override
    public ProviderDownload openDownload(String recordingFileId, ProviderCredential credential, long fromByte) {
        URI uri = recordingUri(recordingFileId, "content", credential.token());
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            allowlist.checkOrThrow(uri);
            HttpRequest.Builder builder = requestBuilder(uri).GET();
            if (fromByte > 0) {
                builder.header("Range", "bytes=" + fromByte + "-");
            }

            HttpResponse<InputStream> response;
            try {
                response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            } catch (IOException e) {
                throw new ProviderUnavailableException("Provider download request failed for " + recordingFileId, e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ProviderUnavailableException("Interrupted contacting provider for " + recordingFileId, e);
            }

            int status = response.statusCode();
            if (isRedirect(status)) {
                closeQuietly(response.body());
                String location = response.headers().firstValue("Location")
                        .orElseThrow(() -> new ProviderUnavailableException(
                                "Redirect (status %d) with no Location header for %s".formatted(status, recordingFileId)));
                uri = uri.resolve(location);
                continue;
            }
            if (status == 206) {
                return new ProviderDownload(response.body(), fromByte, totalSizeFrom(response), true);
            }
            if (status == 200) {
                return new ProviderDownload(response.body(), 0, totalSizeFrom(response), false);
            }

            closeQuietly(response.body());
            throw classify(status, recordingFileId);
        }
        throw new ProviderUnavailableException("Too many redirects fetching recording " + recordingFileId);
    }

    @Override
    public ReleaseOutcome signalRelease(String recordingFileId) {
        URI uri = recordingUri(recordingFileId, "release", null);
        HttpRequest request = requestBuilder(uri).POST(HttpRequest.BodyPublishers.noBody()).build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("Release signal request failed for {}: {}", recordingFileId, e.toString());
            return ReleaseOutcome.TRANSIENT_FAILURE;
        }

        int status = response.statusCode();
        if (status == 200) {
            JsonNode node = parseJson(response.body(), recordingFileId);
            String outcome = node.path("outcome").asText("RELEASED");
            return "ALREADY_RELEASED".equalsIgnoreCase(outcome) ? ReleaseOutcome.ALREADY_RELEASED : ReleaseOutcome.RELEASED;
        }
        if (status == 404 || status == 410) {
            // The provider no longer has anything to release — the state a release signal wants.
            return ReleaseOutcome.ALREADY_RELEASED;
        }
        if (status == 429 || status >= 500) {
            return ReleaseOutcome.TRANSIENT_FAILURE;
        }
        return ReleaseOutcome.PERMANENT_FAILURE;
    }

    private HttpRequest.Builder requestBuilder(URI uri) {
        return HttpRequest.newBuilder(uri).timeout(properties.provider().readTimeout());
    }

    private URI recordingUri(String recordingFileId, String segment, String token) {
        String path = "%s/recordings/%s/%s".formatted(
                properties.provider().baseUrl(), encode(recordingFileId), segment);
        if (token != null) {
            path = path + "?token=" + encode(token);
        }
        URI uri = URI.create(path);
        allowlist.checkOrThrow(uri);
        return uri;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private HttpResponse<String> sendString(HttpRequest request, String recordingFileId) {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new ProviderUnavailableException("Provider request failed for " + recordingFileId, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderUnavailableException("Interrupted contacting provider for " + recordingFileId, e);
        }
    }

    private void ensureSuccess(int status, String recordingFileId) {
        if (status < 200 || status >= 300) {
            throw classify(status, recordingFileId);
        }
    }

    private static RuntimeException classify(int status, String recordingFileId) {
        if (status == 404 || status == 410) {
            return new RecordingNotFoundException("Recording not found at provider: " + recordingFileId);
        }
        return new ProviderUnavailableException(
                "Provider request for %s failed with status %d".formatted(recordingFileId, status));
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static long totalSizeFrom(HttpResponse<InputStream> response) {
        Optional<String> contentRange = response.headers().firstValue("Content-Range");
        if (contentRange.isPresent()) {
            int slash = contentRange.get().lastIndexOf('/');
            if (slash >= 0) {
                try {
                    return Long.parseLong(contentRange.get().substring(slash + 1));
                } catch (NumberFormatException ignored) {
                    // fall through to Content-Length
                }
            }
        }
        return response.headers().firstValueAsLong("Content-Length").orElse(-1L);
    }

    private JsonNode parseJson(String body, String recordingFileId) {
        try {
            return objectMapper.readTree(body);
        } catch (JacksonException e) {
            throw new ProviderUnavailableException("Malformed provider response for " + recordingFileId, e);
        }
    }

    private static void closeQuietly(InputStream stream) {
        try {
            stream.close();
        } catch (IOException ignored) {
            // best-effort cleanup of a discarded redirect/error body
        }
    }
}
