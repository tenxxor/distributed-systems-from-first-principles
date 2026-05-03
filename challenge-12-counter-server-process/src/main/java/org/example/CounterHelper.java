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
import java.util.List;
import java.util.Optional;

/**
 * Library that coordinates counter and vote operations across:
 *   - primaryJdbi  → Postgres primary (writes only, plus exists() probes inside
 *                    write transactions so they see the freshest state)
 *   - replicaJdbi  → Postgres replica pool (reads — load-balanced across replicas)
 *   - cache        → Redis (cache-aside in front of all reads)
 *
 * The split is explicit: every method either writes (uses primaryJdbi) or reads
 * (uses replicaJdbi). This is the cleanest way to surface the read/write
 * routing — anyone reviewing CounterHelper can immediately see which queries go
 * where.
 *
 * Trade-off accepted: replication lag means a vote written to primary may not
 * be visible from a replica for some milliseconds. Cache invalidation also
 * doesn't wait for the replica to catch up. Within those few ms, a read can
 * see the OLD value — that's the price of read scaling without read-your-writes
 * routing. For a like/dislike counter, brief staleness is invisible to users.
 */
public class CounterHelper {

    private static final Logger log = LoggerFactory.getLogger(CounterHelper.class);

    private static final Duration COUNTER_TTL = Duration.ofSeconds(5);
    private static final Duration VOTE_TTL    = Duration.ofSeconds(3);

    private static final TypeReference<CounterEntity> COUNTER_TYPE = new TypeReference<>() {};

    private final Jdbi primaryJdbi;
    private final Jdbi replicaJdbi;
    private final RedisCache cache;

    public CounterHelper(Jdbi primaryJdbi, Jdbi replicaJdbi, RedisCache cache) {
        this.primaryJdbi = primaryJdbi;
        this.replicaJdbi = replicaJdbi;
        this.cache = cache;
    }

    // ── Reads (replica pool, with Redis cache in front) ────────────────────

    public Optional<Counter> get(String counterId) {
        CounterEntity entity = cache.get(
                counterKey(counterId),
                COUNTER_TYPE,
                COUNTER_TTL,
                () -> replicaJdbi.withHandle(h -> h.attach(CountersDAO.class).get(counterId).orElse(null))
        );
        return Optional.ofNullable(entity).map(this::toModel);
    }

    public Optional<Optional<UserVote.Vote>> getMyVote(String userId, String counterId) {
        CounterEntity entity = cache.get(
                counterKey(counterId),
                COUNTER_TYPE,
                COUNTER_TTL,
                () -> replicaJdbi.withHandle(h -> h.attach(CountersDAO.class).get(counterId).orElse(null))
        );
        if (entity == null) return Optional.empty();

        Optional<Integer> cachedVote = cache.getOptionalInt(
                voteKey(counterId, userId),
                VOTE_TTL,
                () -> {
                    var dbVote = replicaJdbi.withHandle(h -> h.attach(UserVotesDAO.class).getVote(counterId, userId));
                    return dbVote.isPresent() ? Optional.of(dbVote.getAsInt()) : Optional.empty();
                }
        );
        return Optional.of(cachedVote.map(this::intToVote));
    }

    public PageResult list(Optional<String> cursorString, int limit) {
        // List queries are read-only — go to the replica pool.
        List<CounterEntity> page = replicaJdbi.withHandle(h -> {
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

    // ── Writes (primary, then invalidate cache) ────────────────────────────

    public boolean create(String counterId) {
        long now = Instant.now().getEpochSecond();
        boolean created = primaryJdbi.inTransaction(h -> {
            CountersDAO dao = h.attach(CountersDAO.class);
            if (dao.has(counterId)) return false;
            dao.insert(counterId, now);
            return true;
        });
        if (created) {
            cache.invalidate(counterKey(counterId));
            log.info("counter.created counterId={} createdAt={}", counterId, now);
        } else {
            log.info("counter.create.conflict counterId={}", counterId);
        }
        return created;
    }

    public boolean delete(String counterId) {
        boolean deleted = primaryJdbi.inTransaction(h -> {
            CountersDAO dao = h.attach(CountersDAO.class);
            if (!dao.has(counterId)) return false;
            h.attach(UserVotesDAO.class).deleteByCounter(counterId);
            dao.delete(counterId);
            return true;
        });
        if (deleted) {
            cache.invalidate(counterKey(counterId));
            log.info("counter.deleted counterId={}", counterId);
        } else {
            log.info("counter.delete.notFound counterId={}", counterId);
        }
        return deleted;
    }

    public Optional<Counter> vote(String userId, String counterId, UserVote.Vote v) {
        Optional<Counter> result = primaryJdbi.inTransaction(h -> {
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
            log.info("vote.recorded userId={} counterId={} vote={} likes={} dislikes={}",
                    userId, counterId, v, c.getLikes(), c.getDislikes());
        } else {
            log.info("vote.notFound userId={} counterId={}", userId, counterId);
        }
        return result;
    }

    public Optional<Counter> clearVote(String userId, String counterId) {
        Optional<Counter> result = primaryJdbi.inTransaction(h -> {
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
            log.info("vote.cleared userId={} counterId={} likes={} dislikes={}",
                    userId, counterId, c.getLikes(), c.getDislikes());
        } else {
            log.info("clearVote.notFound userId={} counterId={}", userId, counterId);
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
}
