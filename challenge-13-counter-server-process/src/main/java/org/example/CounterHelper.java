package org.example;

import com.fasterxml.jackson.core.type.TypeReference;
import org.example.cache.RedisCache;
import org.example.db.CounterEntity;
import org.example.db.CountersDAO;
import org.example.db.UserVotesDAO;
import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Library coordinating counter and vote operations across SHARDED Postgres.
 *
 * Two routing patterns:
 *
 *   1. Single-counter ops (get, vote, create, delete) — pick the shard that
 *      owns this counterId via ShardRouter.jdbiFor(id), do work there.
 *
 *   2. Cross-shard ops (list) — SCATTER-GATHER: query every shard, merge in
 *      memory, return a unified result. The pagination cursor is per-shard
 *      (ShardedPageCursor), so each shard advances independently.
 */
public class CounterHelper {

    private static final Logger log = LoggerFactory.getLogger(CounterHelper.class);

    private static final Duration COUNTER_TTL = Duration.ofSeconds(5);
    private static final Duration VOTE_TTL    = Duration.ofSeconds(3);

    private static final TypeReference<CounterEntity> COUNTER_TYPE = new TypeReference<>() {};

    private final ShardRouter router;
    private final RedisCache cache;

    public CounterHelper(ShardRouter router, RedisCache cache) {
        this.router = router;
        this.cache = cache;
    }

    // ── Reads (cache-first, then route to the owning shard) ───────────────

    public Optional<Counter> get(String counterId) {
        Jdbi jdbi = router.jdbiFor(counterId);
        CounterEntity entity = cache.get(
                counterKey(counterId),
                COUNTER_TYPE,
                COUNTER_TTL,
                () -> jdbi.withHandle(h -> h.attach(CountersDAO.class).get(counterId).orElse(null))
        );
        return Optional.ofNullable(entity).map(this::toModel);
    }

    public Optional<Optional<UserVote.Vote>> getMyVote(String userId, String counterId) {
        Jdbi jdbi = router.jdbiFor(counterId);
        CounterEntity entity = cache.get(
                counterKey(counterId),
                COUNTER_TYPE,
                COUNTER_TTL,
                () -> jdbi.withHandle(h -> h.attach(CountersDAO.class).get(counterId).orElse(null))
        );
        if (entity == null) return Optional.empty();

        Optional<Integer> cachedVote = cache.getOptionalInt(
                voteKey(counterId, userId),
                VOTE_TTL,
                () -> {
                    var dbVote = jdbi.withHandle(h -> h.attach(UserVotesDAO.class).getVote(counterId, userId));
                    return dbVote.isPresent() ? Optional.of(dbVote.getAsInt()) : Optional.empty();
                }
        );
        return Optional.of(cachedVote.map(this::intToVote));
    }

    /**
     * Cross-shard list. Scatter-gather:
     *   1. Fetch (limit + 1) candidates from each shard.
     *   2. Merge into one sorted stream by (created_at DESC, counter_id DESC).
     *   3. Take the first `limit` items as this page.
     *   4. For each shard, advance its cursor PAST the last item we took FROM
     *      THAT SHARD. If we took nothing from a shard but it had results,
     *      its cursor is unchanged. If a shard returned fewer than (limit+1)
     *      rows, it's exhausted.
     *
     * Note the worst-case work: we fetch N * (limit+1) rows from the DB to
     * return `limit` to the client. That's the inherent cost of scatter-gather
     * — you can't tell which shard's row should come next without looking at
     * candidates from each shard. This is one reason offset-style pagination
     * is even worse on sharded DBs than on single-DB systems.
     */
    public PageResult list(Optional<String> cursorString, int limit) {
        int shardCount = router.shardCount();
        ShardedPageCursor cursor = cursorString.map(ShardedPageCursor::decode)
                .orElseGet(() -> ShardedPageCursor.firstPage(shardCount));

        // Fetch from each shard, tagging rows with their shard index so we
        // can update the right cursor slot when we're done.
        List<TaggedEntity> all = new ArrayList<>();
        List<Jdbi> shards = router.allShards();
        for (int i = 0; i < shards.size(); i++) {
            List<CounterEntity> shardPage = fetchFromShard(
                    shards.get(i), cursor.shardCursors().get(i), limit + 1);
            for (CounterEntity e : shardPage) {
                all.add(new TaggedEntity(i, e));
            }
        }

        // Sort all candidates by the same (created_at DESC, counter_id DESC) order
        // each shard already sorted by. Stable merge across shards.
        all.sort(Comparator
                .<TaggedEntity, Long>comparing(t -> t.entity.createdAt(), Comparator.reverseOrder())
                .thenComparing(t -> t.entity.counterId(), Comparator.reverseOrder()));

        boolean hasMore = all.size() > limit;
        List<TaggedEntity> page = hasMore ? all.subList(0, limit) : all;
        List<Counter> counters = page.stream().map(t -> toModel(t.entity)).toList();

        Optional<String> nextCursor = Optional.empty();
        if (hasMore) {
            // For each shard, the new cursor is "the last row I took from this
            // shard." If I took nothing from a shard, its cursor is unchanged
            // (it still has rows past where it was). If I took rows but the
            // shard returned fewer than limit+1 (shard is exhausted), set its
            // cursor to empty.
            List<Optional<PageCursor>> nextShardCursors = new ArrayList<>();
            for (int i = 0; i < shardCount; i++) {
                final int shardIdx = i;
                Optional<TaggedEntity> lastTaken = page.stream()
                        .filter(t -> t.shardIdx == shardIdx)
                        .reduce((a, b) -> b);  // last
                if (lastTaken.isPresent()) {
                    CounterEntity le = lastTaken.get().entity;
                    nextShardCursors.add(Optional.of(new PageCursor(le.createdAt(), le.counterId())));
                } else {
                    // Didn't take anything from this shard — its cursor
                    // doesn't move (unless it was already empty in the input).
                    nextShardCursors.add(cursor.shardCursors().get(i));
                }
            }
            ShardedPageCursor next = new ShardedPageCursor(nextShardCursors);
            if (!next.isExhausted()) {
                nextCursor = Optional.of(next.encode());
            }
        }
        return new PageResult(counters, nextCursor);
    }

    private List<CounterEntity> fetchFromShard(Jdbi jdbi, Optional<PageCursor> cursorOpt, int limit) {
        return jdbi.withHandle(h -> {
            CountersDAO dao = h.attach(CountersDAO.class);
            return cursorOpt
                    .map(c -> dao.listAfterCursor(c.createdAt(), c.counterId(), limit))
                    .orElseGet(() -> dao.listFirstPage(limit));
        });
    }

    // ── Writes (route to owning shard, then invalidate cache) ─────────────

    public boolean create(String counterId) {
        long now = Instant.now().getEpochSecond();
        Jdbi jdbi = router.jdbiFor(counterId);
        boolean created = jdbi.inTransaction(h -> {
            CountersDAO dao = h.attach(CountersDAO.class);
            if (dao.has(counterId)) return false;
            dao.insert(counterId, now);
            return true;
        });
        if (created) {
            cache.invalidate(counterKey(counterId));
            log.info("counter.created counterId={} shardIdx={} createdAt={}",
                    counterId, router.shardIndexFor(counterId), now);
        } else {
            log.info("counter.create.conflict counterId={} shardIdx={}",
                    counterId, router.shardIndexFor(counterId));
        }
        return created;
    }

    public boolean delete(String counterId) {
        Jdbi jdbi = router.jdbiFor(counterId);
        boolean deleted = jdbi.inTransaction(h -> {
            CountersDAO dao = h.attach(CountersDAO.class);
            if (!dao.has(counterId)) return false;
            h.attach(UserVotesDAO.class).deleteByCounter(counterId);
            dao.delete(counterId);
            return true;
        });
        if (deleted) {
            cache.invalidate(counterKey(counterId));
            log.info("counter.deleted counterId={} shardIdx={}",
                    counterId, router.shardIndexFor(counterId));
        } else {
            log.info("counter.delete.notFound counterId={} shardIdx={}",
                    counterId, router.shardIndexFor(counterId));
        }
        return deleted;
    }

    public Optional<Counter> vote(String userId, String counterId, UserVote.Vote v) {
        Jdbi jdbi = router.jdbiFor(counterId);
        Optional<Counter> result = jdbi.inTransaction(h -> {
            CountersDAO dao = h.attach(CountersDAO.class);
            if (!dao.has(counterId)) return Optional.<Counter>empty();
            h.attach(UserVotesDAO.class).upsert(counterId, userId, voteToInt(v));
            dao.recomputeAggregates(counterId);
            return dao.get(counterId).map(this::toModel);
        });
        if (result.isPresent()) {
            cache.invalidate(counterKey(counterId));
            cache.invalidate(voteKey(counterId, userId));
            Counter c = result.get();
            log.info("vote.recorded userId={} counterId={} shardIdx={} vote={} likes={} dislikes={}",
                    userId, counterId, router.shardIndexFor(counterId), v, c.getLikes(), c.getDislikes());
        } else {
            log.info("vote.notFound userId={} counterId={} shardIdx={}",
                    userId, counterId, router.shardIndexFor(counterId));
        }
        return result;
    }

    public Optional<Counter> clearVote(String userId, String counterId) {
        Jdbi jdbi = router.jdbiFor(counterId);
        Optional<Counter> result = jdbi.inTransaction(h -> {
            CountersDAO dao = h.attach(CountersDAO.class);
            if (!dao.has(counterId)) return Optional.<Counter>empty();
            h.attach(UserVotesDAO.class).delete(counterId, userId);
            dao.recomputeAggregates(counterId);
            return dao.get(counterId).map(this::toModel);
        });
        if (result.isPresent()) {
            cache.invalidate(counterKey(counterId));
            cache.invalidate(voteKey(counterId, userId));
            Counter c = result.get();
            log.info("vote.cleared userId={} counterId={} shardIdx={} likes={} dislikes={}",
                    userId, counterId, router.shardIndexFor(counterId), c.getLikes(), c.getDislikes());
        } else {
            log.info("clearVote.notFound userId={} counterId={} shardIdx={}",
                    userId, counterId, router.shardIndexFor(counterId));
        }
        return result;
    }

    // ── Internal ─────────────────────────────────────────────────────────

    private Counter toModel(CounterEntity e) {
        return new Counter(e.counterId(), e.likes(), e.dislikes(), e.createdAt());
    }

    private static String counterKey(String counterId) {
        return "counter:" + counterId;
    }

    private static String voteKey(String counterId, String userId) {
        return "vote:" + counterId + ":" + userId;
    }

    private static int voteToInt(UserVote.Vote v) {
        return switch (v) {
            case LIKE -> 1;
            case DISLIKE -> -1;
        };
    }

    private UserVote.Vote intToVote(int i) {
        return switch (i) {
            case 1 -> UserVote.Vote.LIKE;
            case -1 -> UserVote.Vote.DISLIKE;
            default -> throw new IllegalStateException("invalid vote integer: " + i);
        };
    }

    public record PageResult(List<Counter> counters, Optional<String> nextCursor) {}

    /** Internal: an entity tagged with which shard returned it. */
    private record TaggedEntity(int shardIdx, CounterEntity entity) {}
}
