import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;

public class Server {

    private static final int PORT = 9000;

    public static void main(String[] args) throws IOException {
        CounterStore counters = new CounterStore();
        UserVoteStore votes = new UserVoteStore();

        // Seed counters and initial votes (same as challenge 2).
        counters.add("video-funny-cats", new Counter("video-funny-cats"));
        counters.add("video-dev-tutorial", new Counter("video-dev-tutorial"));
        counters.add("video-music-mix", new Counter("video-music-mix"));

        votes.put(new UserVote("video-funny-cats", "alice", UserVote.Vote.LIKE));
        votes.put(new UserVote("video-funny-cats", "bob", UserVote.Vote.LIKE));
        votes.put(new UserVote("video-dev-tutorial", "alice", UserVote.Vote.LIKE));
        votes.put(new UserVote("video-dev-tutorial", "bob", UserVote.Vote.DISLIKE));

        for (var e : counters.entries()) {
            Counter c = e.getValue();
            c.setLikes(votes.countByCounterAndVote(c.getCounterId(), UserVote.Vote.LIKE));
            c.setDislikes(votes.countByCounterAndVote(c.getCounterId(), UserVote.Vote.DISLIKE));
        }

        // One helper shared across all connections. It's thread-safe via per-counter locks.
        CounterHelper helper = new CounterHelper(counters, votes);

        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            System.out.println("Counter server listening on localhost:" + PORT);
            System.out.println("(three counters seeded; 'video-funny-cats' and 'video-dev-tutorial' already have votes from alice and bob)");
            System.out.println("Start a client with: java Client");

            while (true) {
                Socket client = serverSocket.accept();
                Thread t = new Thread(() -> handleClient(client, helper));
                t.setDaemon(false);
                t.start();
            }
        }
    }

    private static void handleClient(Socket socket, CounterHelper helper) {
        String who = socket.getRemoteSocketAddress().toString();
        System.out.println("[connect]    " + who);

        try (socket;
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {

            // Greet the client.
            out.println("Welcome to the Counter server. Type 'q' to disconnect.");
            out.println();  // blank line = end-of-response marker

            String line;
            while ((line = in.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                if (line.equalsIgnoreCase("q")) break;

                String response = helper.handle(line);
                out.println(response);
                out.println();  // blank line = end-of-response marker
            }
        } catch (IOException e) {
            System.out.println("[error]      " + who + ": " + e.getMessage());
        } finally {
            System.out.println("[disconnect] " + who);
        }
    }
}
