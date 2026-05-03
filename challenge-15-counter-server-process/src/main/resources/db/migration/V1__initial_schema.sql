-- V1 — initial schema (sharded edition).
--
-- Same schema as challenge 10/11/12. The big difference in challenge 13 is
-- that this migration runs *on every shard* — each shard ends up with the
-- same tables, indexes, and Flyway history. Each shard owns a different
-- subset of counters (decided by the application's hash-based router).
--
-- We deliberately do NOT seed data here, because seed counter IDs would have
-- to be pre-routed to the right shards, and embedding the routing logic in
-- a SQL file is fragile. The cluster boots up empty; populate it via the
-- API after `docker compose up` (see the README's Run It section).

CREATE TABLE counters (
    counter_id TEXT PRIMARY KEY,
    likes      INTEGER     NOT NULL DEFAULT 0,
    dislikes   INTEGER     NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE user_votes (
    counter_id TEXT     NOT NULL REFERENCES counters(counter_id) ON DELETE CASCADE,
    user_id    TEXT     NOT NULL,
    vote       SMALLINT NOT NULL CHECK (vote IN (-1, 1)),
    PRIMARY KEY (counter_id, user_id)
);

CREATE INDEX idx_counters_created_at_counter_id
    ON counters (created_at DESC, counter_id DESC);

CREATE INDEX idx_user_votes_counter_id
    ON user_votes (counter_id);
