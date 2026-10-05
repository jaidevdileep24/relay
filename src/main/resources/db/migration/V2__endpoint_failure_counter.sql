-- Circuit breaker state. Counts deliveries that exhausted every retry in a
-- row; any success resets it to zero. When it crosses the configured
-- threshold the endpoint is auto-disabled.

ALTER TABLE endpoint
    ADD COLUMN consecutive_failures INT NOT NULL DEFAULT 0;

