package org.example.db;

import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.util.List;
import java.util.Optional;

@RegisterConstructorMapper(CounterEntity.class)
public interface CountersDAO {

    @SqlUpdate("""
            INSERT INTO counters(counter_id, likes, dislikes, created_at)
            VALUES (:counterId, 0, 0, :createdAt)
            """)
    void insert(String counterId, long createdAt);

    @SqlQuery("""
            SELECT counter_id AS counterId, likes, dislikes, created_at AS createdAt
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

    /**
     * First page of a keyset-paginated list. Ordered by (created_at DESC, counter_id DESC)
     * so that newest counters come first, with counter_id breaking ties when two rows
     * share a timestamp. The index on (created_at DESC, counter_id DESC) makes this O(limit).
     */
    @SqlQuery("""
            SELECT counter_id AS counterId, likes, dislikes, created_at AS createdAt
            FROM counters
            ORDER BY created_at DESC, counter_id DESC
            LIMIT :limit
            """)
    List<CounterEntity> listFirstPage(int limit);

    /**
     * Subsequent page of a keyset-paginated list. The cursor represents the last row
     * on the previous page; we return rows that sort *after* that row in our (desc, desc) ordering.
     * "After" in descending order means "smaller than" — hence the < in the WHERE clause.
     *
     * The tuple comparison is expanded (SQLite doesn't support (a,b) < (c,d) tuple syntax reliably):
     *   created_at < cursorCreatedAt
     *   OR (created_at = cursorCreatedAt AND counter_id < cursorCounterId)
     */
    @SqlQuery("""
            SELECT counter_id AS counterId, likes, dislikes, created_at AS createdAt
            FROM counters
            WHERE created_at < :cursorCreatedAt
               OR (created_at = :cursorCreatedAt AND counter_id < :cursorCounterId)
            ORDER BY created_at DESC, counter_id DESC
            LIMIT :limit
            """)
    List<CounterEntity> listAfterCursor(long cursorCreatedAt, String cursorCounterId, int limit);

    @SqlQuery("""
            SELECT counter_id AS counterId, likes, dislikes, created_at AS createdAt
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
