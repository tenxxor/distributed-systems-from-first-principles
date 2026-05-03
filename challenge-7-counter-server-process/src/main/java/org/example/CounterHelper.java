package org.example;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.example.db.CounterEntity;
import org.example.db.CountersDAO;
import org.example.db.UserVotesDAO;
import org.jdbi.v3.core.Jdbi;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Library that coordinates counter and vote operations against SQLite,
 * with in-memory caches in front of reads and keyset pagination for list.
 */
public class CounterHelper {

    private final Jdbi jdbi;

    private final Cache<String, Optional<CounterEntity>> counterCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(5))
            .maximumSize(10_000)
            .build();

    private final Cache<String, OptionalInt> voteCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(3))
            .maximumSize(50_000)
            .build();

    public CounterHelper(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    // ── Reads (cache-first) ──────────────────────────────────────────────

    public Optional<Counter> get(String counterId) {
        Optional<CounterEntity> cached = counterCache.get(counterId, id ->
                jdbi.withHandle(h -> h.attach(CountersDAO.class).get(id)));
        return cached.map(this::toModel);
    }

    public Optional<Optional<UserVote.Vote>> getMyVote(String userId, String counterId) {
        Optional<CounterEntity> counter = counterCache.get(counterId, id ->
                jdbi.withHandle(h -> h.attach(CountersDAO.class).get(id)));
        if (counter.isEmpty()) return Optional.empty();

        String voteKey = voteKey(counterId, userId);
        OptionalInt cachedVote = voteCache.get(voteKey, k ->
                jdbi.withHandle(h -> h.attach(UserVotesDAO.class).getVote(counterId, userId)));
        return Optional.of(
                cachedVote.isEmpty() ? Optional.empty() : Optional.of(intToVote(cachedVote.getAsInt())));
    }

    /**
     * One page of counters via keyset pagination.
     *
     * Strategy: fetch limit+1 rows from the DB. If we got more than limit, there's
     * another page — the (limit+1)th row tells us the next page exists, and the limit-th
     * row gives us the cursor for the next page. Trim the result to limit before returning.
     *
     * The cache is not consulted for list — we always go to the DB. See the README's
     * discussion of why list is the rare operation that skips caching.
     */
    public PageResult list(Optional<String> cursorString, int limit) {
        List<CounterEntity> page = jdbi.withHandle(h -> {
            CountersDAO dao = h.attach(CountersDAO.class);
            if (cursorString.isEmpty()) {
                return dao.listFirstPage(limit + 1);
            }
            PageCursor c = PageCursor.decode(cursorString.get());
            return dao.listAfterCursor(c.createdAt(), c.counterId(), limit + 1);
        });

        boolean hasMore = page.size() > limit;
        List<CounterEntity> items = hasMore ? page.subList(0, limit) : page;
        List<Counter> counters = items.stream().map(this::toModel).toList();

        Optional<String> nextCursor = Optional.empty();
        if (hasMore) {
            CounterEntity last = items.get(items.size() - 1);
            nextCursor = Optional.of(new PageCursor(last.createdAt(), last.counterId()).encode());
        }
        return new PageResult(counters, nextCursor);
    }

    // ── Writes (DB first, then invalidate cache) ─────────────────────────

    public boolean create(String counterId) {
        long now = Instant.now().getEpochSecond();
        boolean created = jdbi.inTransaction(h -> {
            CountersDAO dao = h.attach(CountersDAO.class);
            if (dao.has(counterId)) return false;
            dao.insert(counterId, now);
            return true;
        });
        if (created) {
            counterCache.invalidate(counterId);
        }
        return created;
    }

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
        }
        return deleted;
    }

    public Optional<Counter> vote(String userId, String counterId, UserVote.Vote v) {
        Optional<Counter> result = jdbi.inTransaction(h -> {
            CountersDAO dao = h.attach(CountersDAO.class);
            if (!dao.has(counterId)) return Optional.<Counter>empty();
            h.attach(UserVotesDAO.class).upsert(counterId, userId, voteToInt(v));
            dao.recomputeAggregates(counterId);
            return dao.get(counterId).map(this::toModel);
        });
        if (result.isPresent()) {
            counterCache.invalidate(counterId);
            voteCache.invalidate(voteKey(counterId, userId));
        }
        return result;
    }

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
        return new Counter(e.counterId(), e.likes(), e.dislikes(), e.createdAt());
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

    /** Result of a paginated list query — the current page plus an optional cursor for the next page. */
    public record PageResult(List<Counter> counters, Optional<String> nextCursor) {}
}
