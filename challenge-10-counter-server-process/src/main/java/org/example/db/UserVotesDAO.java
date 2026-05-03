package org.example.db;

import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.util.OptionalInt;

/**
 * DAO for the user_votes table.
 *
 * vote column: 1 = LIKE, -1 = DISLIKE. No NONE — a cleared vote is a deleted row.
 */
@RegisterConstructorMapper(UserVoteEntity.class)
public interface UserVotesDAO {

    /** Insert-or-update: if the (counter, user) row exists, replace its vote. */
    @SqlUpdate("""
            INSERT INTO user_votes(counter_id, user_id, vote)
            VALUES (:counterId, :userId, :vote)
            ON CONFLICT(counter_id, user_id) DO UPDATE SET vote = excluded.vote
            """)
    int upsert(String counterId, String userId, int vote);

    @SqlQuery("""
            SELECT vote FROM user_votes
            WHERE counter_id = :counterId AND user_id = :userId
            """)
    OptionalInt getVote(String counterId, String userId);

    @SqlUpdate("""
            DELETE FROM user_votes
            WHERE counter_id = :counterId AND user_id = :userId
            """)
    int delete(String counterId, String userId);

    @SqlUpdate("DELETE FROM user_votes WHERE counter_id = :counterId")
    int deleteByCounter(String counterId);
}
