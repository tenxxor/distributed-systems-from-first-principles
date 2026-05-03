package org.example;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Library that coordinates counter/vote operations across the two stores and
 * keeps their aggregates consistent under concurrent access.
 *
 * Exposes typed methods (instead of the string-handle from challenge 3) because
 * its callers are now HTTP resource methods that already carry structured input
 * and expect structured output — no need to funnel through a text-command layer.
 *
 * Errors: missing and already-existing counters are signalled by returning Optional
 * values or booleans. The resource class is responsible for translating those into
 * HTTP status codes (404, 409, etc.).
 */
public class CounterHelper {

    private final CounterStore counters;
    private final UserVoteStore votes;

    // One lock per counter ID (see the Deep Dive section of challenge 3's README
    // for why each operation holds this lock while it runs).
    private final Map<String, Object> counterLocks = new ConcurrentHashMap<>();

    public CounterHelper(CounterStore counters, UserVoteStore votes) {
        this.counters = counters;
        this.votes = votes;
    }

    /** Creates a counter. Returns true on success, false if one already exists with that ID. */
    public boolean create(String counterId) {
        synchronized (lockFor(counterId)) {
            if (counters.has(counterId)) return false;
            counters.add(counterId, new Counter(counterId));
            return true;
        }
    }

    /** Deletes a counter (and all its user votes). Returns true on success, false if no such counter. */
    public boolean delete(String counterId) {
        synchronized (lockFor(counterId)) {
            if (!counters.has(counterId)) return false;
            counters.remove(counterId);
            votes.removeByCounter(counterId);
            return true;
        }
    }

    /** Records a user's vote. Returns the updated counter, or empty if no such counter. */
    public Optional<Counter> vote(String userId, String counterId, UserVote.Vote v) {
        synchronized (lockFor(counterId)) {
            Counter c = counters.get(counterId);
            if (c == null) return Optional.empty();
            votes.put(new UserVote(counterId, userId, v));
            recomputeAggregates(c);
            return Optional.of(c);
        }
    }

    /** Clears a user's vote (if any). Returns the updated counter, or empty if no such counter. */
    public Optional<Counter> clearVote(String userId, String counterId) {
        synchronized (lockFor(counterId)) {
            Counter c = counters.get(counterId);
            if (c == null) return Optional.empty();
            votes.remove(counterId, userId);
            recomputeAggregates(c);
            return Optional.of(c);
        }
    }

    /** Returns the counter, or empty if no such counter. */
    public Optional<Counter> get(String counterId) {
        synchronized (lockFor(counterId)) {
            return Optional.ofNullable(counters.get(counterId));
        }
    }

    /**
     * Look up a user's vote on a counter.
     * Result semantics:
     *   - Optional.empty()             → no such counter (resource should return 404)
     *   - Optional.of(Optional.empty()) → counter exists but the user has no vote (resource returns { vote: null })
     *   - Optional.of(Optional.of(v))   → counter exists and user's vote is v
     */
    public Optional<Optional<UserVote.Vote>> getMyVote(String userId, String counterId) {
        synchronized (lockFor(counterId)) {
            if (!counters.has(counterId)) return Optional.empty();
            UserVote v = votes.get(counterId, userId);
            return Optional.of(v == null ? Optional.empty() : Optional.of(v.getVote()));
        }
    }

    /**
     * Returns a snapshot of all counters. Deliberately does NOT take per-counter locks —
     * see the "list deliberately skips locking" discussion in challenge 3.
     */
    public List<Counter> list() {
        List<Counter> result = new ArrayList<>();
        for (var e : counters.entries()) {
            result.add(e.getValue());
        }
        return result;
    }

    /** Caller must hold lockFor(c.getCounterId()). */
    private void recomputeAggregates(Counter c) {
        c.setLikes(votes.countByCounterAndVote(c.getCounterId(), UserVote.Vote.LIKE));
        c.setDislikes(votes.countByCounterAndVote(c.getCounterId(), UserVote.Vote.DISLIKE));
    }

    private Object lockFor(String counterId) {
        return counterLocks.computeIfAbsent(counterId, k -> new Object());
    }
}
