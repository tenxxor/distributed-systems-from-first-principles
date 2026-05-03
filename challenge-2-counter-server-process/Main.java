import java.util.Scanner;

public class Main {

    public static void main(String[] args) {
        CounterStore counters = new CounterStore();
        UserVoteStore votes = new UserVoteStore();

        // Seed counters
        counters.add("video-funny-cats", new Counter("video-funny-cats"));
        counters.add("video-dev-tutorial", new Counter("video-dev-tutorial"));
        counters.add("video-music-mix", new Counter("video-music-mix"));

        // Seed some user votes so aggregation is visible immediately
        votes.put(new UserVote("video-funny-cats", "alice", UserVote.Vote.LIKE));
        votes.put(new UserVote("video-funny-cats", "bob", UserVote.Vote.LIKE));
        votes.put(new UserVote("video-dev-tutorial", "alice", UserVote.Vote.LIKE));
        votes.put(new UserVote("video-dev-tutorial", "bob", UserVote.Vote.DISLIKE));

        // Recompute aggregates after seeding so counters reflect the votes
        for (var e : counters.entries()) {
            Counter c = e.getValue();
            c.setLikes(votes.countByCounterAndVote(c.getCounterId(), UserVote.Vote.LIKE));
            c.setDislikes(votes.countByCounterAndVote(c.getCounterId(), UserVote.Vote.DISLIKE));
        }

        Scanner scanner = new Scanner(System.in);
        CounterHelper helper = new CounterHelper(counters, votes, scanner);

        System.out.println("Counter server — many counters, many users");
        System.out.println("Commands:");
        System.out.println("  create <id>               create a new counter");
        System.out.println("  delete <id>               delete counter <id>");
        System.out.println("  l <user-id> <id>          user likes counter");
        System.out.println("  d <user-id> <id>          user dislikes counter");
        System.out.println("  c <user-id> <id>          user clears their vote on counter");
        System.out.println("  s <id>                    show counter aggregates");
        System.out.println("  myvote <user-id> <id>     show a user's vote on the counter");
        System.out.println("  list                      show all counters");
        System.out.println("  q                         quit");
        System.out.println("(three counters seeded; 'video-funny-cats' and 'video-dev-tutorial' already have votes from alice and bob)");

        while (true) {
            System.out.print("> ");
            String line = helper.readCommand();
            if (!helper.handle(line)) return;
        }
    }
}
