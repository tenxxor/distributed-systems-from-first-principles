package org.example;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.example.db.CounterEntity;
import org.example.db.CountersDAO;
import org.example.db.UserVotesDAO;
import org.jdbi.v3.core.Jdbi;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Library that coordinates counter and vote operations against SQLite,
 * with an in-memory Caffeine cache in front of reads.
 *
 * What changed from challenge 5:
 * - Read methods check the cache first; on miss, load from DB and populate cache.
 * - Write methods do the DB write inside a transaction (same as challenge 5),
 *   then invalidate the relevant cache entries so the next read gets fresh data.
 * - TTL-based expiry acts as a safety net: even if invalidation is somehow missed,
 *   stale entries expire on their own after a few seconds.
 *
 * The cache is library-internal state — same classification as the lock map from
 * challenge 3. It's process-bound, can't be serialized, doesn't survive restarts,
 * and exists only to speed up one process's reads.
 */
public class CounterHelper {

    private final Jdbi jdbi;

    // Cache: counterId → CounterEntity (the aggregate row).
    // TTL 5 seconds: a cached counter becomes stale after 5s and is refreshed on next read.
    private final Cache<String, Optional<CounterEntity>> counterCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(5))
            .maximumSize(10_000)
            .build();

    // Cache: "counterId::userId" → Optional<Integer> (the vote value, or empty = no vote).
    // TTL 3 seconds: slightly shorter than counter cache because votes change more often
    // and users expect to see their own vote reflected immediately after casting.
    private final Cache<String, OptionalInt> voteCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(3))
            .maximumSize(50_000)
            .build();

    public CounterHelper(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    // ── Reads (cache-first) ──────────────────────────────────────────────

    /** Returns the counter, or empty if no such counter. Cache-first. */
    public Optional<Counter> get(String counterId) {
        Optional<CounterEntity> cached = counterCache.get(counterId, id ->
                jdbi.withHandle(h -> h.attach(CountersDAO.class).get(id)));
        return cached.map(this::toModel);
    }

    /**
     * Look up a user's vote on a counter. Cache-first for the vote lookup;
     * counter-existence check also uses cache.
     */
    public Optional<Optional<UserVote.Vote>> getMyVote(String userId, String counterId) {
        // First check counter exists (via cache).
        Optional<CounterEntity> counter = counterCache.get(counterId, id ->
                jdbi.withHandle(h -> h.attach(CountersDAO.class).get(id)));
        if (counter.isEmpty()) return Optional.empty();

        // Then check the vote (via cache).
        String voteKey = voteKey(counterId, userId);
        OptionalInt cachedVote = voteCache.get(voteKey, k ->
                jdbi.withHandle(h -> h.attach(UserVotesDAO.class).getVote(counterId, userId)));
        return Optional.of(
                cachedVote.isEmpty() ? Optional.empty() : Optional.of(intToVote(cachedVote.getAsInt())));
    }

    /**
     * Returns a snapshot of all counters. Always hits the DB — caching the full
     * list is tricky (any single counter change invalidates the whole list) and
     * the common read path is GET /counters/{id}, not GET /counters.
     */
    public List<Counter> list() {
        return jdbi.withHandle(h ->
                h.attach(CountersDAO.class).listAll().stream().map(this::toModel).toList());
    }

    // ── Writes (DB first, then invalidate cache) ─────────────────────────

    /** Creates a counter. Returns true on success, false if one already exists. */
    public boolean create(String counterId) {
        boolean created = jdbi.inTransaction(h -> {
            CountersDAO dao = h.attach(CountersDAO.class);
            if (dao.has(counterId)) return false;
            dao.insert(counterId);
            return true;
        });
        if (created) {
            counterCache.invalidate(counterId);
        }
        return created;
    }

    /** Deletes a counter and all of its user votes. Returns true on success, false if no such counter. */
    public boolean delete(String counterId) {
        boolean deleted = jdbi.inTransaction(h -> {
            CountersDAO dao = h.attach(CountersDAO.class);
            if (!dao.has(counterId)) return false;
            h.attach(UserVotesDAO.class).deleteByCounter(counterId);
            dao.delete(counterId);
            return true;
        });
        if (deleted) {
            counterCache.invalidate(counterId);
            // Vote cache entries for this counter are keyed "counterId::userId".
            // We can't enumerate all users here, but TTL will clean them up within seconds.
            // For a more thorough approach, we'd maintain a reverse index or use a different
            // cache key scheme. TTL-as-safety-net is fine for our scale.
        }
        return deleted;
    }

    /** Records a user's vote. Returns the updated counter, or empty if no such counter. */
    public Optional<Counter> vote(String userId, String counterId, UserVote.Vote v) {
        Optional<Counter> result = jdbi.inTransaction(h -> {
            CountersDAO dao = h.attach(CountersDAO.class);
            if (!dao.has(counterId)) return Optional.<Counter>empty();
            h.attach(UserVotesDAO.class).upsert(counterId, userId, voteToInt(v));
            dao.recomputeAggregates(counterId);
            return dao.get(counterId).map(this::toModel);
        });
        if (result.isPresent()) {
            // Invalidate both caches after the transaction commits.
            counterCache.invalidate(counterId);
            voteCache.invalidate(voteKey(counterId, userId));
        }
        return result;
    }

    /** Clears a user's vote (if any). Returns the updated counter, or empty if no such counter. */
    public Optional<Counter> clearVote(String userId, String counterId) {
        Optional<Counter> result = jdbi.inTransaction(h -> {
            CountersDAO dao = h.attach(CountersDAO.class);
            if (!dao.has(counterId)) return Optional.<Counter>empty();
            h.attach(UserVotesDAO.class).delete(counterId, userId);
            dao.recomputeAggregates(counterId);
            return dao.get(counterId).map(this::toModel);
        });
        if (result.isPresent()) {
            counterCache.invalidate(counterId);
            voteCache.invalidate(voteKey(counterId, userId));
        }
        return result;
    }

    // ── Internal ─────────────────────────────────────────────────────────

    private Counter toModel(CounterEntity e) {
        return new Counter(e.counterId(), e.likes(), e.dislikes());
    }

    private static String voteKey(String counterId, String userId) {
        return counterId + "::" + userId;
    }

    private static int voteToInt(UserVote.Vote v) {
        return switch (v) {
            case LIKE -> 1;
            case DISLIKE -> -1;
        };
    }

    private static UserVote.Vote intToVote(int i) {
        return switch (i) {
            case 1 -> UserVote.Vote.LIKE;
            case -1 -> UserVote.Vote.DISLIKE;
            default -> throw new IllegalStateException("invalid vote integer: " + i);
        };
    }
}
