import java.util.Scanner;

public class Main {

    public static void main(String[] args) {
        Counter counter = new Counter();
        Scanner scanner = new Scanner(System.in);
        CounterHelper helper = new CounterHelper(counter, scanner);

        System.out.println("score: " + helper.score());
        System.out.println("Commands: l (like), d (dislike), c (clear vote), q (quit)");

        while (true) {
            System.out.print("> ");
            String cmd = helper.readCommand();
            boolean keepGoing = helper.handle(cmd);
            if (!keepGoing) {
                System.out.println("Final score: " + helper.score());
                return;
            }
            System.out.println("score: " + helper.score());
        }
    }
}
