import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class CounterHelper {

    private final CounterStore counters;
    private final UserVoteStore votes;

    // One lock object per counter ID. Acquired for any operation that touches
    // a specific counter, so read-modify-write sequences on the same counter
    // serialize even under concurrent client connections.
    private final Map<String, Object> counterLocks = new ConcurrentHashMap<>();

    public CounterHelper(CounterStore counters, UserVoteStore votes) {
        this.counters = counters;
        this.votes = votes;
    }

    /** Handles one command line and returns the response as a string (may contain \n). */
    public String handle(String line) {
        if (line == null || line.isEmpty()) return "";

        String[] parts = line.split("\\s+");
        String cmd = parts[0].toLowerCase();

        switch (cmd) {
            case "create":
                if (parts.length != 2) return usage("create <counter-id>");
                return create(parts[1]);

            case "delete":
                if (parts.length != 2) return usage("delete <counter-id>");
                return delete(parts[1]);

            case "l":
                if (parts.length != 3) return usage("l <user-id> <counter-id>");
                return doVote(parts[1], parts[2], UserVote.Vote.LIKE);

            case "d":
                if (parts.length != 3) return usage("d <user-id> <counter-id>");
                return doVote(parts[1], parts[2], UserVote.Vote.DISLIKE);

            case "c":
                if (parts.length != 3) return usage("c <user-id> <counter-id>");
                return doClear(parts[1], parts[2]);

            case "s":
                if (parts.length != 2) return usage("s <counter-id>");
                return show(parts[1]);

            case "myvote":
                if (parts.length != 3) return usage("myvote <user-id> <counter-id>");
                return showMyVote(parts[1], parts[2]);

            case "list":
                return listAll();

            default:
                return "Unknown command. Use create, delete, l, d, c, s, myvote, list, or q.";
        }
    }

    private String create(String id) {
        synchronized (lockFor(id)) {
            if (counters.has(id)) return "counter '" + id + "' already exists";
            counters.add(id, new Counter(id));
            return "created '" + id + "' (likes: 0, dislikes: 0, score: 0)";
        }
    }

    private String delete(String id) {
        synchronized (lockFor(id)) {
            if (!counters.has(id)) return noSuchCounter(id);
            counters.remove(id);
            votes.removeByCounter(id);
            return "deleted '" + id + "'";
        }
    }

    private String doVote(String userId, String counterId, UserVote.Vote v) {
        synchronized (lockFor(counterId)) {
            Counter c = counters.get(counterId);
            if (c == null) return noSuchCounter(counterId);
            votes.put(new UserVote(counterId, userId, v));
            recomputeAggregates(c);
            return formatCounter(c);
        }
    }

    private String doClear(String userId, String counterId) {
        synchronized (lockFor(counterId)) {
            Counter c = counters.get(counterId);
            if (c == null) return noSuchCounter(counterId);
            votes.remove(counterId, userId);
            recomputeAggregates(c);
            return formatCounter(c);
        }
    }

    private String show(String counterId) {
        synchronized (lockFor(counterId)) {
            Counter c = counters.get(counterId);
            if (c == null) return noSuchCounter(counterId);
            return formatCounter(c);
        }
    }

    private String showMyVote(String userId, String counterId) {
        synchronized (lockFor(counterId)) {
            if (!counters.has(counterId)) return noSuchCounter(counterId);
            UserVote v = votes.get(counterId, userId);
            return (v == null)
                ? userId + " has no vote on " + counterId
                : userId + "'s vote on " + counterId + ": " + v.getVote();
        }
    }

    /**
     * Listing all counters doesn't take per-counter locks — readers here may see
     * transient inconsistency (a counter's aggregate mid-update). That's a deliberate
     * simplification; consistent snapshots across many counters require either a
     * global lock (slow) or snapshot-isolation (more machinery than this challenge
     * needs).
     */
    private String listAll() {
        if (counters.isEmpty()) return "(no counters)";
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (var e : counters.entries()) {
            if (!first) sb.append("\n");
            sb.append(formatCounter(e.getValue()));
            first = false;
        }
        return sb.toString();
    }

    /** Rebuilds a counter's aggregate counts from the user-vote store. Caller must hold lockFor(counterId). */
    private void recomputeAggregates(Counter c) {
        c.setLikes(votes.countByCounterAndVote(c.getCounterId(), UserVote.Vote.LIKE));
        c.setDislikes(votes.countByCounterAndVote(c.getCounterId(), UserVote.Vote.DISLIKE));
    }

    private String formatCounter(Counter c) {
        return c.getCounterId()
            + " -> likes: " + c.getLikes()
            + ", dislikes: " + c.getDislikes()
            + ", score: " + c.getScore();
    }

    private String noSuchCounter(String id) {
        return "no such counter '" + id + "'. use 'list' to see available counters, or 'create " + id + "' to add it.";
    }

    private String usage(String message) {
        return "Usage: " + message;
    }

    private Object lockFor(String counterId) {
        return counterLocks.computeIfAbsent(counterId, k -> new Object());
    }
}
