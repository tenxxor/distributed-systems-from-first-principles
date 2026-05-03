package org.example;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.example.cache.RedisCache;
import org.example.db.CounterEntity;
import org.example.db.CountersDAO;
import org.example.db.UserVotesDAO;
import org.example.events.EventPublisher;
import org.example.resilience.BreakerRegistry;
import org.example.tasks.TaskMessage;
import org.example.tasks.TaskQueue;
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
 * Routing:
 *   - Single-counter ops (get, vote, create, delete) — pick the shard that
 *     owns this counterId, do work there. Each shard call is wrapped in
 *     that shard's CircuitBreaker (challenge 16).
 *   - Cross-shard ops (list) — SCATTER-GATHER: query every shard with each
 *     shard's own breaker. A dead shard's breaker fails fast and we skip
 *     its contribution to the merged page.
 *
 * Cache and Kafka publish each have their own (single) breaker. When the
 * cache breaker is open, callers transparently bypass the cache and read
 * directly from Postgres (graceful degradation). When the Kafka publish
 * breaker is open, the publish is dropped — the vote already succeeded;
 * downstream events are best-effort.
 */
public class CounterHelper {

    private static final Logger log = LoggerFactory.getLogger(CounterHelper.class);

    private static final Duration COUNTER_TTL = Duration.ofSeconds(5);
    private static final Duration VOTE_TTL    = Duration.ofSeconds(3);

    private static final TypeReference<CounterEntity> COUNTER_TYPE = new TypeReference<>() {};

    private final ShardRouter router;
    private final RedisCache cache;
    private final TaskQueue queue;
    private final EventPublisher publisher;
    private final BreakerRegistry breakers;

    public CounterHelper(ShardRouter router, RedisCache cache, TaskQueue queue,
                         EventPublisher publisher, BreakerRegistry breakers) {
        this.router = router;
        this.cache = cache;
        this.queue = queue;
        this.publisher = publisher;
        this.breakers = breakers;
    }

    // ── Reads (cache-first when cache breaker is closed; falls through on open) ───

    public Optional<Counter> get(String counterId) {
        CounterEntity entity = readCounterEntity(counterId);
        return Optional.ofNullable(entity).map(this::toModel);
    }

    public Optional<Optional<UserVote.Vote>> getMyVote(String userId, String counterId) {
        CounterEntity entity = readCounterEntity(counterId);
        if (entity == null) return Optional.empty();

        Optional<Integer> dbResolved = readVote(counterId, userId);
        return Optional.of(dbResolved.map(this::intToVote));
    }

    /** Cache-aware counter fetch. Falls through to direct DB if cache breaker is open. */
    private CounterEntity readCounterEntity(String counterId) {
        return router.onCounterShard(counterId, jdbi -> {
            try {
                return breakers.forCache().executeSupplier(() ->
                        cache.get(
                                counterKey(counterId),
                                COUNTER_TYPE,
                                COUNTER_TTL,
                                () -> jdbi.withHandle(h -> h.attach(CountersDAO.class).get(counterId).orElse(null))
                        ));
            } catch (CallNotPermittedException e) {
                log.info("cache.breaker.open path=counter counterId={}", counterId);
                return jdbi.withHandle(h -> h.attach(CountersDAO.class).get(counterId).orElse(null));
            }
        });
    }

    /** Cache-aware vote fetch. Falls through to direct DB if cache breaker is open. */
    private Optional<Integer> readVote(String counterId, String userId) {
        return router.onCounterShard(counterId, jdbi -> {
            try {
                return breakers.forCache().executeSupplier(() ->
                        cache.getOptionalInt(
                                voteKey(counterId, userId),
                                VOTE_TTL,
                                () -> {
                                    var dbVote = jdbi.withHandle(h ->
                                            h.attach(UserVotesDAO.class).getVote(counterId, userId));
                                    return dbVote.isPresent()
                                            ? Optional.of(dbVote.getAsInt())
                                            : Optional.empty();
                                }
                        ));
            } catch (CallNotPermittedException e) {
                log.info("cache.breaker.open path=vote counterId={} userId={}", counterId, userId);
                var dbVote = jdbi.withHandle(h ->
                        h.attach(UserVotesDAO.class).getVote(counterId, userId));
                return dbVote.isPresent() ? Optional.of(dbVote.getAsInt()) : Optional.empty();
            }
        });
    }

    /**
     * Cross-shard list. Scatter-gather across shards, with each shard's
     * call wrapped in its own breaker. A shard with an OPEN breaker
     * contributes zero rows to this page (we don't fail the whole list);
     * the page just reflects the surviving shards.
     */
    public PageResult list(Optional<String> cursorString, int limit) {
        int shardCount = router.shardCount();
        ShardedPageCursor cursor = cursorString.map(ShardedPageCursor::decode)
                .orElseGet(() -> ShardedPageCursor.firstPage(shardCount));

        List<TaggedEntity> all = new ArrayList<>();
        for (int i = 0; i < shardCount; i++) {
            final int shardIdx = i;
            try {
                List<CounterEntity> shardPage = router.onShardIndex(shardIdx, jdbi ->
                        fetchFromShard(jdbi, cursor.shardCursors().get(shardIdx), limit + 1));
                for (CounterEntity e : shardPage) {
                    all.add(new TaggedEntity(shardIdx, e));
                }
            } catch (CallNotPermittedException e) {
                log.info("list.shard.breaker.open shardIdx={}", shardIdx);
                // Skip this shard for this page — surviving shards still serve.
            }
        }

        all.sort(Comparator
                .<TaggedEntity, Long>comparing(t -> t.entity.createdAt(), Comparator.reverseOrder())
                .thenComparing(t -> t.entity.counterId(), Comparator.reverseOrder()));

        boolean hasMore = all.size() > limit;
        List<TaggedEntity> page = hasMore ? all.subList(0, limit) : all;
        List<Counter> counters = page.stream().map(t -> toModel(t.entity)).toList();

        Optional<String> nextCursor = Optional.empty();
        if (hasMore) {
            List<Optional<PageCursor>> nextShardCursors = new ArrayList<>();
            for (int i = 0; i < shardCount; i++) {
                final int shardIdx = i;
                Optional<TaggedEntity> lastTaken = page.stream()
                        .filter(t -> t.shardIdx == shardIdx)
                        .reduce((a, b) -> b);
                if (lastTaken.isPresent()) {
                    CounterEntity le = lastTaken.get().entity;
                    nextShardCursors.add(Optional.of(new PageCursor(le.createdAt(), le.counterId())));
                } else {
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
        boolean created = router.onCounterShard(counterId, jdbi -> jdbi.inTransaction(h -> {
            CountersDAO dao = h.attach(CountersDAO.class);
            if (dao.has(counterId)) return false;
            dao.insert(counterId, now);
            return true;
        }));
        if (created) {
            invalidateCacheBestEffort(counterKey(counterId));
            log.info("counter.created counterId={} shardIdx={} createdAt={}",
                    counterId, router.shardIndexFor(counterId), now);
        } else {
            log.info("counter.create.conflict counterId={} shardIdx={}",
                    counterId, router.shardIndexFor(counterId));
        }
        return created;
    }

    public boolean delete(String counterId) {
        boolean deleted = router.onCounterShard(counterId, jdbi -> jdbi.inTransaction(h -> {
            CountersDAO dao = h.attach(CountersDAO.class);
            if (!dao.has(counterId)) return false;
            h.attach(UserVotesDAO.class).deleteByCounter(counterId);
            dao.delete(counterId);
            return true;
        }));
        if (deleted) {
            invalidateCacheBestEffort(counterKey(counterId));
            log.info("counter.deleted counterId={} shardIdx={}",
                    counterId, router.shardIndexFor(counterId));
        } else {
            log.info("counter.delete.notFound counterId={} shardIdx={}",
                    counterId, router.shardIndexFor(counterId));
        }
        return deleted;
    }

    public Optional<Counter> vote(String userId, String counterId, UserVote.Vote v) {
        Optional<Counter> result = router.onCounterShard(counterId, jdbi -> jdbi.inTransaction(h -> {
            CountersDAO dao = h.attach(CountersDAO.class);
            if (!dao.has(counterId)) return Optional.<Counter>empty();
            h.attach(UserVotesDAO.class).upsert(counterId, userId, voteToInt(v));
            dao.recomputeAggregates(counterId);
            return dao.get(counterId).map(this::toModel);
        }));
        if (result.isPresent()) {
            invalidateCacheBestEffort(counterKey(counterId));
            invalidateCacheBestEffort(voteKey(counterId, userId));
            Counter c = result.get();
            log.info("vote.recorded userId={} counterId={} shardIdx={} vote={} likes={} dislikes={}",
                    userId, counterId, router.shardIndexFor(counterId), v, c.getLikes(), c.getDislikes());

            // Two downstream paths after a successful vote (challenge 15 unchanged):
            // (1) enqueue a notification task, (2) publish a vote-cast event.
            // The publish is wrapped in the kafka-publish breaker; if open, we drop.
            queue.enqueue("vote-notification", new TaskMessage.VoteNotificationPayload(
                    counterId, userId, v.name(), c.getLikes(), c.getDislikes()));
            publishBestEffort(counterId, userId, v.name(), c.getLikes(), c.getDislikes());
        } else {
            log.info("vote.notFound userId={} counterId={} shardIdx={}",
                    userId, counterId, router.shardIndexFor(counterId));
        }
        return result;
    }

    public Optional<Counter> clearVote(String userId, String counterId) {
        Optional<Counter> result = router.onCounterShard(counterId, jdbi -> jdbi.inTransaction(h -> {
            CountersDAO dao = h.attach(CountersDAO.class);
            if (!dao.has(counterId)) return Optional.<Counter>empty();
            h.attach(UserVotesDAO.class).delete(counterId, userId);
            dao.recomputeAggregates(counterId);
            return dao.get(counterId).map(this::toModel);
        }));
        if (result.isPresent()) {
            invalidateCacheBestEffort(counterKey(counterId));
            invalidateCacheBestEffort(voteKey(counterId, userId));
            Counter c = result.get();
            log.info("vote.cleared userId={} counterId={} shardIdx={} likes={} dislikes={}",
                    userId, counterId, router.shardIndexFor(counterId), c.getLikes(), c.getDislikes());
        } else {
            log.info("clearVote.notFound userId={} counterId={} shardIdx={}",
                    userId, counterId, router.shardIndexFor(counterId));
        }
        return result;
    }

    // ── Best-effort wrappers for non-critical-path side effects ──────────

    /** Cache invalidation is best-effort — never fail a write because the cache is sad. */
    private void invalidateCacheBestEffort(String key) {
        CircuitBreaker b = breakers.forCache();
        try {
            b.executeRunnable(() -> cache.invalidate(key));
        } catch (CallNotPermittedException e) {
            log.info("cache.breaker.open path=invalidate key={}", key);
        } catch (Exception e) {
            log.warn("cache.invalidate.error key={} err={}", key, e.getMessage());
        }
    }

    /** Kafka publish is best-effort — vote already succeeded, downstream events are fan-out. */
    private void publishBestEffort(String counterId, String userId, String vote,
                                   int likes, int dislikes) {
        CircuitBreaker b = breakers.forKafkaPublish();
        try {
            b.executeRunnable(() ->
                    publisher.publishVoteCast(counterId, userId, vote, likes, dislikes));
        } catch (CallNotPermittedException e) {
            log.info("kafka.breaker.open counterId={}", counterId);
        } catch (Exception e) {
            log.warn("kafka.publish.error counterId={} err={}", counterId, e.getMessage());
        }
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

    private record TaggedEntity(int shardIdx, CounterEntity entity) {}
}
