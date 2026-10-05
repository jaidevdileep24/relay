-- Lets Hibernate batch delivery INSERTs during ingest fan-out.
--
-- With IDENTITY ids Hibernate must run each INSERT on its own to read back the
-- generated key, so a message fanning out to N endpoints cost N round trips
-- inside the outbox transaction. A sequence that hands out blocks of 50 lets
-- it assign ids in memory and send all N rows as one batch.
--
-- The column keeps its BIGSERIAL default, so raw SQL inserts still work. They
-- consume a whole block per nextval(); Hibernate's pooled optimizer treats the
-- returned value as a block boundary, so the two never collide - ids just gap.

ALTER SEQUENCE delivery_id_seq INCREMENT BY 50;
