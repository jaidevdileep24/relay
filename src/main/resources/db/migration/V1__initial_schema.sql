-- =====================================================================
-- V1: core webhook delivery schema
--
-- ID strategy is deliberately mixed:
--   * application / endpoint / message use UUID  - they are exposed in
--     public API paths, so sequential integers would leak volume.
--   * delivery / delivery_attempt use BIGSERIAL  - internal, very high
--     volume, and monotonic ids keep the queue index tight.
-- =====================================================================

CREATE TABLE application (
    id          UUID PRIMARY KEY,
    name        TEXT        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE endpoint (
    id             UUID PRIMARY KEY,
    application_id UUID        NOT NULL REFERENCES application(id) ON DELETE CASCADE,
    url            TEXT        NOT NULL,
    secret         TEXT        NOT NULL,          -- HMAC signing secret
    description    TEXT,
    enabled        BOOLEAN     NOT NULL DEFAULT TRUE,
    disabled_reason TEXT,                          -- set when auto-disabled
    version        BIGINT      NOT NULL DEFAULT 0, -- optimistic locking
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_endpoint_application ON endpoint(application_id);

-- Which event types each endpoint subscribes to. Empty set = all events.
CREATE TABLE endpoint_event_type (
    endpoint_id UUID NOT NULL REFERENCES endpoint(id) ON DELETE CASCADE,
    event_type  TEXT NOT NULL,
    PRIMARY KEY (endpoint_id, event_type)
);

CREATE TABLE message (
    id              UUID PRIMARY KEY,
    application_id  UUID        NOT NULL REFERENCES application(id) ON DELETE CASCADE,
    event_type      TEXT        NOT NULL,
    payload         JSONB       NOT NULL,
    idempotency_key TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Idempotency: the same key from the same application must never create
-- two messages. This unique index IS the enforcement - not app-level checks,
-- which race under concurrency.
CREATE UNIQUE INDEX uq_message_idempotency
    ON message(application_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

CREATE INDEX idx_message_payload ON message USING GIN (payload);

CREATE TABLE delivery (
    id              BIGSERIAL PRIMARY KEY,
    message_id      UUID        NOT NULL REFERENCES message(id) ON DELETE CASCADE,
    endpoint_id     UUID        NOT NULL REFERENCES endpoint(id) ON DELETE CASCADE,
    state           TEXT        NOT NULL,   -- PENDING | SUCCEEDED | FAILED | DISABLED
    attempt_count   INT         NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_error      TEXT,
    version         BIGINT      NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_delivery_message_endpoint UNIQUE (message_id, endpoint_id)
);

-- THE queue index. The dispatcher's hot query is:
--   WHERE state='PENDING' AND next_attempt_at <= now() ORDER BY next_attempt_at
-- A partial index keeps it small: succeeded/failed rows are excluded entirely,
-- so the index stays the size of the backlog, not the size of history.
CREATE INDEX idx_delivery_queue
    ON delivery(next_attempt_at)
    WHERE state = 'PENDING';

CREATE INDEX idx_delivery_endpoint ON delivery(endpoint_id);

CREATE TABLE delivery_attempt (
    id             BIGSERIAL PRIMARY KEY,
    delivery_id    BIGINT      NOT NULL REFERENCES delivery(id) ON DELETE CASCADE,
    attempt_number INT         NOT NULL,
    http_status    INT,
    response_body  TEXT,
    error_message  TEXT,
    duration_ms    INT         NOT NULL,
    attempted_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_attempt_delivery ON delivery_attempt(delivery_id, attempt_number);
