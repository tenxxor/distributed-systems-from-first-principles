package org.example.db;

import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.util.List;
import java.util.Optional;

/**
 * DAO (Data Access Object) for the counters table.
 *
 * Each method is a tiny wrapper over one SQL statement. JDBI generates the
 * implementation from these annotations at runtime — we don't write execution
 * code ourselves.
 */
@RegisterConstructorMapper(CounterEntity.class)
public interface CountersDAO {

    @SqlUpdate("""
            INSERT INTO counters(counter_id, likes, dislikes)
            VALUES (:counterId, 0, 0)
            """)
    void insert(String counterId);

    @SqlQuery("""
            SELECT counter_id AS counterId, likes, dislikes
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
            SELECT counter_id AS counterId, likes, dislikes
            FROM counters
            """)
    List<CounterEntity> listAll();

    /**
     * Recompute the aggregate counts for a counter from the user_votes table.
     * Runs inside whatever transaction the caller has open (safe against
     * concurrent vote changes because writers serialize on the counter's row).
     */
    @SqlUpdate("""
            UPDATE counters
               SET likes    = (SELECT COUNT(*) FROM user_votes WHERE counter_id = :counterId AND vote =  1),
                   dislikes = (SELECT COUNT(*) FROM user_votes WHERE counter_id = :counterId AND vote = -1)
             WHERE counter_id = :counterId
            """)
    int recomputeAggregates(String counterId);
}
