package org.example.db;

import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.util.List;
import java.util.Optional;

/**
 * Counter table DAO, challenge 10 (Postgres edition).
 *
 * The schema stores created_at as TIMESTAMPTZ, but the Java layer still speaks
 * epoch seconds (long) everywhere — we convert at the DAO boundary so nothing
 * downstream has to change. `to_timestamp(:createdAt)` on writes,
 * `EXTRACT(EPOCH FROM created_at)::BIGINT` on reads.
 */
@RegisterConstructorMapper(CounterEntity.class)
public interface CountersDAO {

    @SqlUpdate("""
            INSERT INTO counters(counter_id, likes, dislikes, created_at)
            VALUES (:counterId, 0, 0, to_timestamp(:createdAt))
            """)
    void insert(String counterId, long createdAt);

    @SqlQuery("""
            SELECT counter_id                               AS counterId,
                   likes,
                   dislikes,
                   EXTRACT(EPOCH FROM created_at)::BIGINT   AS createdAt
            FROM counters
            WHERE counter_id = :counterId
            """)
    Optional<CounterEntity> get(String counterId);

    @SqlQuery("SELECT 1 FROM counters WHERE counter_id = :counterId LIMIT 1")
    Optional<Integer> existsProbe(String counterId);

    default boolean has(String counterId) {
        return existsProbe(counterId).isPresent();
    }

    @SqlUpdate("DELETE FROM counters WHERE counter_id = :counterId")
    int delete(String counterId);

    @SqlQuery("""
            SELECT counter_id                               AS counterId,
                   likes,
                   dislikes,
                   EXTRACT(EPOCH FROM created_at)::BIGINT   AS createdAt
            FROM counters
            ORDER BY created_at DESC, counter_id DESC
            LIMIT :limit
            """)
    List<CounterEntity> listFirstPage(int limit);

    @SqlQuery("""
            SELECT counter_id                               AS counterId,
                   likes,
                   dislikes,
                   EXTRACT(EPOCH FROM created_at)::BIGINT   AS createdAt
            FROM counters
            WHERE created_at < to_timestamp(:cursorCreatedAt)
               OR (created_at = to_timestamp(:cursorCreatedAt) AND counter_id < :cursorCounterId)
            ORDER BY created_at DESC, counter_id DESC
            LIMIT :limit
            """)
    List<CounterEntity> listAfterCursor(long cursorCreatedAt, String cursorCounterId, int limit);

    @SqlQuery("""
            SELECT counter_id                               AS counterId,
                   likes,
                   dislikes,
                   EXTRACT(EPOCH FROM created_at)::BIGINT   AS createdAt
            FROM counters
            """)
    List<CounterEntity> listAll();

    @SqlUpdate("""
            UPDATE counters
               SET likes    = (SELECT COUNT(*) FROM user_votes WHERE counter_id = :counterId AND vote =  1),
                   dislikes = (SELECT COUNT(*) FROM user_votes WHERE counter_id = :counterId AND vote = -1)
             WHERE counter_id = :counterId
            """)
    int recomputeAggregates(String counterId);
}
