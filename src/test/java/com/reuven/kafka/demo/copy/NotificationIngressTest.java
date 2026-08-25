package com.reuven.kafka.demo.copy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.reuven.kafka.demo.copy.staging.StagedItemRepository;
import com.reuven.kafka.demo.copy.support.CopyIntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * quickstart.md S13 — SC-021, SC-023 (SC-022's sustained-load p99 is a performance test, not covered
 * here). Drives the real HTTP endpoint end to end: ingress -> topic -> {@code StagedBatchConsumer} ->
 * staged rows, so "published" is verified by what actually lands in the staging store, not a topic
 * probe.
 *
 * <p>The signature is always computed over the exact {@code String} sent as the request body, never
 * over a re-serialisation of the source {@code Map} — the whole point of raw-body signing (R10) is
 * that those two are not guaranteed to be byte-identical (key ordering, whitespace).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class NotificationIngressTest extends CopyIntegrationTestBase {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private StagedItemRepository stagedItemRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void validNotificationWithThreeFilesPublishesExactlyThree() {
        String fileA = "file-a-" + UUID.randomUUID();
        String fileB = "file-b-" + UUID.randomUUID();
        String fileC = "file-c-" + UUID.randomUUID();
        String rawBody = recordingCompletedJson("evt-" + UUID.randomUUID(), "session-" + UUID.randomUUID(), fileA, fileB, fileC);

        ResponseEntity<Map> response = post(rawBody, validSignatureHeaders(rawBody));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("published", 3);

        await().untilAsserted(() -> {
            List<String> ids = stagedItemRepository.findAll().stream().map(i -> i.getRecordingFileId()).toList();
            assertThat(ids).contains(fileA, fileB, fileC);
        });
    }

    @Test
    void missingSignatureIsRejectedAndNothingPublished() {
        String recordingFileId = "file-" + UUID.randomUUID();
        String rawBody = recordingCompletedJson("evt-" + UUID.randomUUID(), "session-x", recordingFileId);

        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Provider-Request-Timestamp", String.valueOf(Instant.now().getEpochSecond()));
        ResponseEntity<Map> response = post(rawBody, headers);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).containsEntry("code", "SIGNATURE_INVALID");
    }

    @Test
    void wrongSignatureIsRejected() {
        String rawBody = recordingCompletedJson("evt-" + UUID.randomUUID(), "session-y", "file-" + UUID.randomUUID());
        String timestamp = String.valueOf(Instant.now().getEpochSecond());

        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Provider-Request-Timestamp", timestamp);
        headers.add("X-Provider-Signature", "v0=" + "0".repeat(64));
        ResponseEntity<Map> response = post(rawBody, headers);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void staleTimestampIsRejected() {
        String rawBody = recordingCompletedJson("evt-" + UUID.randomUUID(), "session-z", "file-" + UUID.randomUUID());
        String staleTimestamp = String.valueOf(Instant.now().minusSeconds(600).getEpochSecond());

        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Provider-Request-Timestamp", staleTimestamp);
        headers.add("X-Provider-Signature", "v0=" + hmacHex(staleTimestamp, rawBody));
        ResponseEntity<Map> response = post(rawBody, headers);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.REQUEST_TIMEOUT);
        assertThat(response.getBody()).containsEntry("code", "TIMESTAMP_STALE");
    }

    @Test
    void urlValidationChallengeIsAnsweredCorrectly() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("event", "endpoint.url_validation");
        body.put("payload", Map.of("plainToken", "the-plain-token"));
        String rawBody = objectMapper.writeValueAsString(body);

        ResponseEntity<Map> response = post(rawBody, validSignatureHeaders(rawBody));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("plainToken", "the-plain-token");
        assertThat(response.getBody().get("encryptedToken")).isEqualTo(hmac("the-plain-token"));
    }

    @Test
    void duplicateNotificationResultsInExactlyOneStagedRow() {
        String recordingFileId = "dup-file-" + UUID.randomUUID();
        String rawBody = recordingCompletedJson("evt-" + UUID.randomUUID(), "session-dup", recordingFileId);

        post(rawBody, validSignatureHeaders(rawBody));
        post(rawBody, validSignatureHeaders(rawBody));

        await().untilAsserted(() ->
                assertThat(stagedItemRepository.findAll().stream()
                        .filter(item -> item.getRecordingFileId().equals(recordingFileId))
                        .count())
                        .isEqualTo(1));
    }

    private ResponseEntity<Map> post(String rawBody, HttpHeaders headers) {
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.postForEntity("/provider/notifications", new HttpEntity<>(rawBody, headers), Map.class);
    }

    private HttpHeaders validSignatureHeaders(String rawBody) {
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Provider-Request-Timestamp", timestamp);
        headers.add("X-Provider-Signature", "v0=" + hmacHex(timestamp, rawBody));
        return headers;
    }

    private String recordingCompletedJson(String eventId, String uuid, String... fileIds) {
        List<Map<String, Object>> files = java.util.Arrays.stream(fileIds)
                .map(id -> (Map<String, Object>) Map.<String, Object>of(
                        "id", id,
                        "file_type", "MP4",
                        "download_url", "https://localhost:1/f/" + id,
                        "file_size", 1024))
                .toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("event", "recording.completed");
        body.put("event_id", eventId);
        body.put("event_ts", Instant.now().getEpochSecond());
        Map<String, Object> object = new LinkedHashMap<>();
        object.put("uuid", uuid);
        object.put("recording_files", files);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("account_id", "acct-1");
        payload.put("object", object);
        body.put("payload", payload);
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String hmacHex(String timestamp, String rawBody) {
        return hmac("v0:" + timestamp + ":" + rawBody);
    }

    private String hmac(String material) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(TEST_NOTIFICATION_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(material.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
