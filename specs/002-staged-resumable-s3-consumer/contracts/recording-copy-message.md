# Contract: Recording Copy Message

**Topic**: `${copy.topic}` (default `recording-copy`)
**Key**: `recordingFileId` — co-partitions all attempts for one file
**Value**: JSON, `RecordingCopyMessage`
**Producer**: notification ingress only ([notification-ingress.openapi.yaml](./notification-ingress.openapi.yaml))
**Consumer**: staged batch consumer (`copy.consumer.strategy=staged`)

One message per recording **file**, never per notification (FR-077). A session that produced video, audio, a
transcript, and a chat log yields four messages, staged, copied, checkpointed, and released independently.

## Value schema

```json
{
  "recordingFileId": "a1b2c3d4-0000-4000-8000-000000000001",
  "sessionId": "s-8f2c19",
  "providerAccountId": "acct-4471",
  "fileType": "MP4",
  "downloadUrl": "https://recordings.provider.example/f/a1b2c3d4",
  "declaredSizeBytes": 8589934592,
  "contentType": "video/mp4",
  "recordingEndedAt": "2026-08-24T09:14:02Z"
}
```

| Field | Type | Required | Notes |
|---|---|---|---|
| `recordingFileId` | string | yes | Stable provider identifier. Message key, staged-item natural key, and the basis of the destination name |
| `sessionId` | string | yes | Grouping only — **never** sufficient as a destination key (FR-052) |
| `providerAccountId` | string | yes | Provider tenant |
| `fileType` | string | yes | Descriptive; does not select behaviour |
| `downloadUrl` | string | yes | Host-allowlisted before any fetch (FR-062). Treated as untrusted input |
| `declaredSizeBytes` | integer\|null | no | Hint. Absent, negative, non-numeric, or implausible values trigger the metadata-lookup fallback (FR-048) |
| `contentType` | string\|null | no | Passed through to the stored object |
| `recordingEndedAt` | string (ISO-8601) | yes | Provider-supplied |

**Not present, deliberately**: any download credential. One captured at notification time would be expired by the
time a multi-day retry span elapsed, so credentials are minted fresh per attempt from `recordingFileId`
(FR-059, FR-060, SC-020).

## Headers

| Header | Type | Required | Purpose |
|---|---|---|---|
| `x-recording-size` | string (decimal int64) | no | Declared size, duplicated from the body so the delivery worker can select its upload path without deserialising or calling the provider (FR-075, SC-010) |
| `x-provider-event-id` | string | yes | Notification identifier; makes provider-retry duplicates detectable downstream (FR-076, SC-023) |
| `x-provider-account-id` | string | yes | Tenant, for routing and metrics |

`x-recording-size` is validated, not trusted: absent, non-numeric, negative, or above
`copy.size.max-plausible-bytes` falls back to a single metadata lookup whose result is persisted so it is paid at most
once per item across all retries (FR-048, FR-049).

## Producer guarantees

| Property | Mechanism |
|---|---|
| All files of a notification, or none (FR-078) | Kafka transaction over the whole fan-out (`executeInTransaction`) |
| Durable before the provider is acknowledged (FR-079) | `acks=all`, transaction committed before the HTTP response is written |
| No duplicate on producer retry | `enable.idempotence=true` |

## Consumer expectations

| Property | Mechanism |
|---|---|
| Batched intake bounded by size **and** time (FR-006) | `max.poll.records` + `fetch.max.wait.ms` on a batch listener |
| No message acknowledged before its row is committed (FR-008) | Acknowledgement strictly after the staging transaction commits |
| Whole batch staged or none (FR-009) | One transaction per batch |
| No read of an aborted transaction | `isolation.level=read_committed` |

## Duplicate handling

At-least-once is the target; duplicates are made harmless rather than prevented (SC-023).

1. Provider retries a notification → same `recordingFileId` values republished.
2. Staging inserts with `ON CONFLICT (recording_file_id) DO NOTHING` → one row.
3. Destination name derives from `recordingFileId` → one object.
4. Release state lives on that single row → one release signal.

## Compatibility

Additive fields only. A consumer must ignore unknown fields. Removing a field or changing the meaning of
`recordingFileId` is breaking and requires a new topic.
