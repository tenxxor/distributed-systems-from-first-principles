import java.util.Scanner;

public class CounterHelper {

    private final CounterStore counters;
    private final UserVoteStore votes;
    private final Scanner scanner;

    public CounterHelper(CounterStore counters, UserVoteStore votes, Scanner scanner) {
        this.counters = counters;
        this.votes = votes;
        this.scanner = scanner;
    }

    public String readCommand() {
        return scanner.nextLine().trim();
    }

    /** Applies the command. Returns false if the user asked to quit. */
    public boolean handle(String line) {
        if (line.isEmpty()) {
            return true;
        }

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
                printAll();
                return true;

            case "q":
                return false;

            default:
                System.out.println("Unknown command. Use create, delete, l, d, c, s, myvote, list, or q.");
                return true;
        }
    }

    private boolean create(String id) {
        if (counters.has(id)) {
            System.out.println("counter '" + id + "' already exists");
            return true;
        }
        counters.add(id, new Counter(id));
        System.out.println("created '" + id + "' (likes: 0, dislikes: 0, score: 0)");
        return true;
    }

    private boolean delete(String id) {
        if (!counters.has(id)) return noSuchCounter(id);
        counters.remove(id);
        votes.removeByCounter(id);
        System.out.println("deleted '" + id + "'");
        return true;
    }

    private boolean doVote(String userId, String counterId, UserVote.Vote v) {
        Counter c = counters.get(counterId);
        if (c == null) return noSuchCounter(counterId);

        votes.put(new UserVote(counterId, userId, v));

        recomputeAggregates(c);
        printOne(c);
        return true;
    }

    private boolean doClear(String userId, String counterId) {
        Counter c = counters.get(counterId);
        if (c == null) return noSuchCounter(counterId);

        votes.remove(counterId, userId);

        recomputeAggregates(c);
        printOne(c);
        return true;
    }

    private boolean show(String counterId) {
        Counter c = counters.get(counterId);
        if (c == null) return noSuchCounter(counterId);
        printOne(c);
        return true;
    }

    private boolean showMyVote(String userId, String counterId) {
        if (!counters.has(counterId)) return noSuchCounter(counterId);
        UserVote v = votes.get(counterId, userId);
        if (v == null) {
            System.out.println(userId + " has no vote on " + counterId);
        } else {
            System.out.println(userId + "'s vote on " + counterId + ": " + v.getVote());
        }
        return true;
    }

    /** Rebuilds a counter's aggregate counts from the user-vote store. */
    private void recomputeAggregates(Counter c) {
        c.setLikes(votes.countByCounterAndVote(c.getCounterId(), UserVote.Vote.LIKE));
        c.setDislikes(votes.countByCounterAndVote(c.getCounterId(), UserVote.Vote.DISLIKE));
    }

    private void printOne(Counter c) {
        System.out.println(c.getCounterId()
            + " -> likes: " + c.getLikes()
            + ", dislikes: " + c.getDislikes()
            + ", score: " + c.getScore());
    }

    private void printAll() {
        if (counters.isEmpty()) {
            System.out.println("(no counters)");
            return;
        }
        for (var e : counters.entries()) {
            printOne(e.getValue());
        }
    }

    private boolean noSuchCounter(String id) {
        System.out.println("no such counter '" + id + "'. use 'list' to see available counters, or 'create " + id + "' to add it.");
        return true;
    }

    private boolean usage(String message) {
        System.out.println("Usage: " + message);
        return true;
    }
}
