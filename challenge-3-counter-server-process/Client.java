import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.Scanner;

public class Client {

    private static final String HOST = "localhost";
    private static final int PORT = 9000;

    public static void main(String[] args) {
        System.out.println("Connecting to " + HOST + ":" + PORT + "...");

        try (Socket socket = new Socket(HOST, PORT);
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
             Scanner stdin = new Scanner(System.in)) {

            // Read the server's greeting (lines terminated by a blank line).
            printResponse(in);

            printHelp();

            while (true) {
                System.out.print("> ");
                if (!stdin.hasNextLine()) break;
                String cmd = stdin.nextLine().trim();
                if (cmd.isEmpty()) continue;
                if (cmd.equalsIgnoreCase("q")) break;

                out.println(cmd);
                if (!printResponse(in)) {
                    System.out.println("(server closed the connection)");
                    break;
                }
            }
        } catch (IOException e) {
            System.err.println("Could not connect to server at " + HOST + ":" + PORT + ": " + e.getMessage());
            System.err.println("(Is the server running? Start it with: java Server)");
            System.exit(1);
        }
    }

    /** Reads one logical response from the server — lines until a blank line. Returns false on EOF. */
    private static boolean printResponse(BufferedReader in) throws IOException {
        String line;
        boolean gotAnything = false;
        while ((line = in.readLine()) != null) {
            if (line.isEmpty()) return true;
            System.out.println(line);
            gotAnything = true;
        }
        return gotAnything;
    }

    private static void printHelp() {
        System.out.println("Commands:");
        System.out.println("  create <id>               create a new counter");
        System.out.println("  delete <id>               delete counter <id>");
        System.out.println("  l <user-id> <id>          user likes counter");
        System.out.println("  d <user-id> <id>          user dislikes counter");
        System.out.println("  c <user-id> <id>          user clears their vote on counter");
        System.out.println("  s <id>                    show counter aggregates");
        System.out.println("  myvote <user-id> <id>     show a user's vote on the counter");
        System.out.println("  list                      show all counters");
        System.out.println("  q                         disconnect");
    }
}
