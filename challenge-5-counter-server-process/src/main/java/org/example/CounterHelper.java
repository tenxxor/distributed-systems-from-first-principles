package org.example;

import org.example.db.CounterEntity;
import org.example.db.CountersDAO;
import org.example.db.UserVotesDAO;
import org.jdbi.v3.core.Jdbi;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Library that coordinates counter and vote operations against SQLite.
 *
 * What changed from challenge 4:
 * - No more CounterStore / UserVoteStore in-memory maps. The helper talks to DAOs.
 * - No more per-counter lock map. Multi-step writes are wrapped in DB transactions;
 *   SQLite's WAL-mode concurrency control serializes conflicting writes at the row level.
 */
public class CounterHelper {

    private final Jdbi jdbi;

    public CounterHelper(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    /** Creates a counter. Returns true on success, false if one already exists with that ID. */
    public boolean create(String counterId) {
        return jdbi.inTransaction(h -> {
            CountersDAO dao = h.attach(CountersDAO.class);
            if (dao.has(counterId)) return false;
            dao.insert(counterId);
            return true;
        });
    }

    /** Deletes a counter and all of its user votes. Returns true on success, false if no such counter. */
    public boolean delete(String counterId) {
        return jdbi.inTransaction(h -> {
            CountersDAO dao = h.attach(CountersDAO.class);
            if (!dao.has(counterId)) return false;
            h.attach(UserVotesDAO.class).deleteByCounter(counterId);
            dao.delete(counterId);
            return true;
        });
    }

    /** Records a user's vote. Returns the updated counter, or empty if no such counter. */
    public Optional<Counter> vote(String userId, String counterId, UserVote.Vote v) {
        return jdbi.inTransaction(h -> {
            CountersDAO dao = h.attach(CountersDAO.class);
            if (!dao.has(counterId)) return Optional.<Counter>empty();
            h.attach(UserVotesDAO.class).upsert(counterId, userId, voteToInt(v));
            dao.recomputeAggregates(counterId);
            return dao.get(counterId).map(this::toModel);
        });
    }

    /** Clears a user's vote (if any). Returns the updated counter, or empty if no such counter. */
    public Optional<Counter> clearVote(String userId, String counterId) {
        return jdbi.inTransaction(h -> {
            CountersDAO dao = h.attach(CountersDAO.class);
            if (!dao.has(counterId)) return Optional.<Counter>empty();
            h.attach(UserVotesDAO.class).delete(counterId, userId);
            dao.recomputeAggregates(counterId);
            return dao.get(counterId).map(this::toModel);
        });
    }

    /** Returns the counter, or empty if no such counter. */
    public Optional<Counter> get(String counterId) {
        return jdbi.withHandle(h -> h.attach(CountersDAO.class).get(counterId).map(this::toModel));
    }

    /**
     * Look up a user's vote on a counter.
     * Result semantics (same as challenge 4):
     *   - Optional.empty()               → no such counter (resource returns 404)
     *   - Optional.of(Optional.empty())  → counter exists, no vote (resource returns { vote: null })
     *   - Optional.of(Optional.of(v))    → counter exists, user voted v
     */
    public Optional<Optional<UserVote.Vote>> getMyVote(String userId, String counterId) {
        return jdbi.withHandle(h -> {
            CountersDAO cDao = h.attach(CountersDAO.class);
            if (!cDao.has(counterId)) return Optional.<Optional<UserVote.Vote>>empty();
            OptionalInt v = h.attach(UserVotesDAO.class).getVote(counterId, userId);
            return Optional.of(v.isEmpty() ? Optional.<UserVote.Vote>empty() : Optional.of(intToVote(v.getAsInt())));
        });
    }

    /** Returns a snapshot of all counters. */
    public List<Counter> list() {
        return jdbi.withHandle(h ->
            h.attach(CountersDAO.class).listAll().stream().map(this::toModel).toList());
    }

    private Counter toModel(CounterEntity e) {
        return new Counter(e.counterId(), e.likes(), e.dislikes());
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
