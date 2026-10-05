-- P5: AI failure triage.
--
-- Why classify at all: the dispatcher currently treats every non-2xx the same -
-- record, back off, retry, give up. A 401 retried eight times over an hour fails
-- eight times. A 429 ignored and retried on our own schedule gets us throttled
-- harder. The categories below are what let retry behaviour differ.

ALTER TABLE delivery_attempt ADD COLUMN failure_category TEXT;
ALTER TABLE delivery_attempt ADD COLUMN failure_reasoning TEXT;

-- Fingerprint cache. A receiver that is down returns the same body thousands of
-- times an hour; without this we would pay a model to read it thousands of
-- times. The key is a hash of the *normalised* body, with digits and hex runs
-- collapsed - request ids and timestamps would otherwise make every occurrence
-- unique and give the cache a 0% hit rate.
--
-- It lives in Postgres rather than in memory so the hit rate survives restarts
-- and is shared across instances. Spend is the thing being protected, and an
-- in-process map protects it N times worse on N instances.
CREATE TABLE failure_classification (
    fingerprint       TEXT PRIMARY KEY,
    category          TEXT        NOT NULL,
    retry_after_secs  BIGINT,
    reasoning         TEXT,
    classifier        TEXT        NOT NULL,   -- which provider/model decided this
    hit_count         BIGINT      NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_used_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Supports both "what is failing most" reporting and eviction of stale rows.
CREATE INDEX idx_failure_classification_last_used ON failure_classification(last_used_at);
