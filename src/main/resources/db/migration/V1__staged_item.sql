CREATE TABLE staged_item (
    id                     BIGSERIAL PRIMARY KEY,
    recording_file_id      TEXT        NOT NULL,
    session_id             TEXT        NOT NULL,
    provider_account_id    TEXT        NOT NULL,
    provider_event_id      TEXT        NOT NULL,
    destination_bucket     TEXT        NOT NULL,
    destination_key        TEXT        NOT NULL,
    declared_size_bytes    BIGINT,
    resolved_size_bytes    BIGINT,
    content_type           TEXT,
    delivery_state         TEXT        NOT NULL DEFAULT 'AWAITING_DELIVERY',
    attempt_count          INT         NOT NULL DEFAULT 0,
    next_attempt_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_failure_reason    TEXT,
    last_failure_at        TIMESTAMPTZ,
    claim_owner            TEXT,
    claim_expires_at       TIMESTAMPTZ,
    release_state          TEXT        NOT NULL DEFAULT 'NOT_APPLICABLE',
    release_attempt_count  INT         NOT NULL DEFAULT 0,
    release_last_error     TEXT,
    verified_checksum      TEXT,
    verified_size_bytes    BIGINT,
    delivered_at           TIMESTAMPTZ,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_staged_item_file UNIQUE (recording_file_id),
    CONSTRAINT uq_staged_item_destination UNIQUE (destination_bucket, destination_key)
);

CREATE INDEX ix_staged_item_claimable
    ON staged_item (next_attempt_at)
    WHERE delivery_state = 'AWAITING_DELIVERY';

CREATE INDEX ix_staged_item_stale_claim
    ON staged_item (claim_expires_at)
    WHERE delivery_state = 'DELIVERY_IN_PROGRESS';

CREATE INDEX ix_staged_item_release_pending
    ON staged_item (delivered_at)
    WHERE release_state = 'PENDING';

CREATE INDEX ix_staged_item_backlog
    ON staged_item (delivery_state);
