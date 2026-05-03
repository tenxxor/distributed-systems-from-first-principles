-- V1 — initial schema for challenge 10.
--
-- Ported from the SQLite schema in challenges 5-9.5, with cleanups that
-- Postgres supports natively:
--   - created_at is a real TIMESTAMPTZ (was INTEGER epoch-seconds in SQLite).
--   - vote is a SMALLINT (was INTEGER; we only need -1 or +1).
--   - user_votes.counter_id has a proper foreign key to counters.counter_id
--     with ON DELETE CASCADE. SQLite supports FKs but we didn't wire them in.

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

-- Supports pagination ordering from challenge 7: keyset pagination on
-- (created_at DESC, counter_id DESC) with a composite index.
CREATE INDEX idx_counters_created_at_counter_id
    ON counters (created_at DESC, counter_id DESC);

CREATE INDEX idx_user_votes_counter_id
    ON user_votes (counter_id);

-- Seed data (first-run only — Flyway runs migrations exactly once per DB).
-- Timestamps are spaced so pagination ordering is deterministic for the demo.
INSERT INTO counters (counter_id, created_at) VALUES
    ('video-funny-cats',   now() - INTERVAL '2 seconds'),
    ('video-dev-tutorial', now() - INTERVAL '1 second'),
    ('video-music-mix',    now());

INSERT INTO user_votes (counter_id, user_id, vote) VALUES
    ('video-funny-cats',   'alice',  1),
    ('video-funny-cats',   'bob',    1),
    ('video-dev-tutorial', 'alice',  1),
    ('video-dev-tutorial', 'bob',   -1);

-- Recompute aggregates from the votes just inserted.
UPDATE counters SET
    likes    = (SELECT count(*) FROM user_votes WHERE user_votes.counter_id = counters.counter_id AND vote =  1),
    dislikes = (SELECT count(*) FROM user_votes WHERE user_votes.counter_id = counters.counter_id AND vote = -1);
