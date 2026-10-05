-- The failure triage verdict, surfaced where people actually look: the
-- delivery row. It also lives on every delivery_attempt, but the deliveries
-- list would need a lookup per row to show it from there.
--
-- retries_saved: attempts NOT made because the classifier called a failure
-- permanent (401, signature rejected, ...) while retry budget remained. It is
-- the measurable answer to "what does triage buy us". Cumulative across
-- replays - savings already made stay made.

ALTER TABLE delivery ADD COLUMN last_failure_category  TEXT;
ALTER TABLE delivery ADD COLUMN last_failure_reasoning TEXT;
ALTER TABLE delivery ADD COLUMN retries_saved          INT NOT NULL DEFAULT 0;
