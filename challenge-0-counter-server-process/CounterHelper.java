import java.util.Scanner;

public class CounterHelper {

    private final Counter counter;
    private final Scanner scanner;

    public CounterHelper(Counter counter, Scanner scanner) {
        this.counter = counter;
        this.scanner = scanner;
    }

    public String readCommand() {
        return scanner.nextLine().trim().toLowerCase();
    }

    /** Applies the command to the counter. Returns false if the user asked to quit. */
    public boolean handle(String cmd) {
        switch (cmd) {
            case "l":
                counter.setMyVote(Counter.Vote.LIKE);
                return true;
            case "d":
                counter.setMyVote(Counter.Vote.DISLIKE);
                return true;
            case "c":
                counter.setMyVote(Counter.Vote.NONE);
                return true;
            case "q":
                return false;
            default:
                System.out.println("Unknown command. Use l, d, c, or q.");
                return true;
        }
    }

    public int score() {
        switch (counter.getMyVote()) {
            case LIKE:    return 1;
            case DISLIKE: return -1;
            default:      return 0;
        }
    }
}
