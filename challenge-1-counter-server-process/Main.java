import java.util.Scanner;

public class Main {

    public static void main(String[] args) {
        // Seed the store with a few counters so there's something to interact with.
        CounterStore store = new CounterStore();
        store.add("video-funny-cats", new Counter());
        store.add("video-dev-tutorial", new Counter());
        store.add("video-music-mix", new Counter());

        Scanner scanner = new Scanner(System.in);
        CounterHelper helper = new CounterHelper(store, scanner);

        System.out.println("Counter server — many counters, one user");
        System.out.println("Commands:");
        System.out.println("  create <id>   create a new counter");
        System.out.println("  delete <id>   delete counter <id>");
        System.out.println("  l <id>        like counter <id>");
        System.out.println("  d <id>        dislike counter <id>");
        System.out.println("  c <id>        clear vote on counter <id>");
        System.out.println("  s <id>        show score of counter <id>");
        System.out.println("  list          show all counters");
        System.out.println("  q             quit");
        System.out.println("(three counters are seeded at startup; use 'list' to see them)");

        while (true) {
            System.out.print("> ");
            String line = helper.readCommand();
            if (!helper.handle(line)) return;
        }
    }
}
