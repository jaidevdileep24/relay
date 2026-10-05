-- P4: replay + the dead-letter queue read path.

-- Replay puts a terminal delivery back to PENDING so the dispatcher claims it
-- again. It cannot insert a fresh delivery row: uq_delivery_message_endpoint
-- allows exactly one delivery per (message, endpoint), which is what makes
-- ingest fan-out idempotent. So the same row is reused, and attempt_count goes
-- back to zero to restore the retry budget.
--
-- That alone would corrupt the audit trail. A replayed delivery's first attempt
-- would be "attempt 1" again, indistinguishable from the original attempt 1.
-- replay_count is the generation counter: (replay_count, attempt_number)
-- identifies an attempt uniquely, and the history of every earlier cycle stays
-- readable.
ALTER TABLE delivery         ADD COLUMN replay_count INT NOT NULL DEFAULT 0;
ALTER TABLE delivery_attempt ADD COLUMN replay_count INT NOT NULL DEFAULT 0;

DROP INDEX idx_attempt_delivery;
CREATE INDEX idx_attempt_delivery
    ON delivery_attempt(delivery_id, replay_count DESC, attempt_number DESC);

-- The DLQ lists deliveries for an application, but delivery has no
-- application_id - it reaches one through message. uq_message_idempotency is
-- partial (WHERE idempotency_key IS NOT NULL) so it cannot serve this lookup,
-- which left message(application_id) with no usable index at all: both this
-- query and the ingest fan-out were seq-scanning.
CREATE INDEX idx_message_application ON message(application_id);

-- Listing one endpoint's failures, and the bulk replay that follows an outage,
-- both filter endpoint + state. The existing idx_delivery_endpoint only covers
-- the first half.
CREATE INDEX idx_delivery_endpoint_state ON delivery(endpoint_id, state);
