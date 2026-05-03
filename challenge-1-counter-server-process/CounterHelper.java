import java.util.Scanner;

public class CounterHelper {

    private final CounterStore store;
    private final Scanner scanner;

    public CounterHelper(CounterStore store, Scanner scanner) {
        this.store = store;
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

        String[] parts = line.split("\\s+", 2);
        String cmd = parts[0].toLowerCase();
        String id = parts.length > 1 ? parts[1] : null;

        switch (cmd) {
            case "create":
                if (id == null) return usage("create <counter-id>");
                if (store.has(id)) {
                    System.out.println("counter '" + id + "' already exists");
                    return true;
                }
                store.add(id, new Counter());
                System.out.println("created '" + id + "' (score: 0)");
                return true;

            case "delete":
                if (id == null) return usage("delete <counter-id>");
                if (!store.has(id)) return noSuchCounter(id);
                store.remove(id);
                System.out.println("deleted '" + id + "'");
                return true;

            case "l":
                if (id == null) return usage("l <counter-id>");
                return vote(id, Counter.Vote.LIKE);

            case "d":
                if (id == null) return usage("d <counter-id>");
                return vote(id, Counter.Vote.DISLIKE);

            case "c":
                if (id == null) return usage("c <counter-id>");
                return vote(id, Counter.Vote.NONE);

            case "s":
                if (id == null) return usage("s <counter-id>");
                Counter c = store.get(id);
                if (c == null) return noSuchCounter(id);
                printOne(id, c);
                return true;

            case "list":
                printAll();
                return true;

            case "q":
                return false;

            default:
                System.out.println("Unknown command. Use create, delete, l, d, c, s, list, or q.");
                return true;
        }
    }

    private boolean vote(String id, Counter.Vote v) {
        Counter c = store.get(id);
        if (c == null) return noSuchCounter(id);
        c.setMyVote(v);
        printOne(id, c);
        return true;
    }

    private int scoreOf(Counter c) {
        switch (c.getMyVote()) {
            case LIKE:    return 1;
            case DISLIKE: return -1;
            default:      return 0;
        }
    }

    private void printOne(String id, Counter c) {
        System.out.println(id + " -> score: " + scoreOf(c));
    }

    private void printAll() {
        if (store.isEmpty()) {
            System.out.println("(no counters)");
            return;
        }
        for (var entry : store.entries()) {
            System.out.println(entry.getKey() + " -> score: " + scoreOf(entry.getValue()));
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
