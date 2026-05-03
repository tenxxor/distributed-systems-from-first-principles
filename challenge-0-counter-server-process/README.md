# Challenge 0 — One User, One Counter

## Problem

You want to be able to like/dislike a youtube video. Since we're keeping things simple for the first challenge, lets remove any frontend noise and instead translate this problem to incrementing/decrementing a simple counter in terminal, which effectively means the same thing as liking/disliking a video.  

Nothing else. No users, no network, no database. 


## Product
A simple terminal-based like/dislike counter for a single user. The user can press `l` to like, `d` to dislike, or `c` to clear their vote, and `q` to quit. And in return, the counter will show the current state of the counter. 

## Programming

Before jumping into code, it helps to think in terms of **runtime first**: what's the data, what's the process, and what's the infrastructure? Only then do you ask what models and libraries you need to implement it.

Why this order? Because models and libraries are *answers*. If you pick a HashMap or a framework before you've thought about what data is flowing and what has to happen to it, you're choosing tools before you know what you're building.


### Run-time — What's Actually Happening

![User and Counter process data flow](./diagram.png)

#### Data

The data in this system is what flows in and out of the Counter (server) process:

- User -> Counter process: the characters `l`, `d`, `c`, `q` (commands typed at the keyboard - data requests)
- Counter process -> User: a status string like `"score: 1"` / `"score: -1"` / `"score: 0"` (data responses)

#### Process

As seen from the picture above, there's only one process in this system (for now) - the Counter (server) process.

The Counter process here is a **server process**: a program that runs in an infinite loop, waiting for input, handling it, returning something and looping back for more. That's all a server really is — at any scale, from this terminal program to a Kubernetes pod handling a million requests a second.

Each iteration of the loop does the same four things:

1. Read input (`l`, `d`, `c`, `q` from stdin - data requests)
2. Do some internal processing
3. Write output (`"score: 1"` / `"score: -1"` / `"score: 0"` to stdout - data responses)
4. Loop back (or exit on `q`)

No network, no threads, no concurrency. One process, sequential execution. 


#### Infrastructure

A single machine running a single process, attached to your terminal. The OS owns the terminal device and provides stdin/stdout streams that the process reads from and writes to.

```
                  ┌──────────────────────────────────────────────┐
                  │              Your Machine + OS               │
                  │                                              │
    User          │   stdin    ┌──────────────────────────────┐  │
     O   ───────► │  ────────► │                              │  │
    /|\           │            │    Counter Server Process    │  │
    / \           │            │                              │  │
          ◄────── │  ◄──────── │                              │  │
                  │   stdout   └──────────────────────────────┘  │
                  │                                              │
                  └──────────────────────────────────────────────┘
```


### Compile-Time — How to Implement It

Now that the runtime picture is clear, we can work out how to translate it into code. The runtime tells us three things:

1. The server process needs to *hold state* — the current vote — across iterations of the loop (for the lifetime of the process).
2. The server process needs to *do work on that state* — read input, turn it into state changes, derive outputs.
3. The server process needs to *keep running in a loop* — tie it all together and iterate forever.

These map to three pieces of code — but only two kinds of things in our taxonomy:

- A **model** (`Counter`) — pure data, holds the state. Portable. Could be serialized, stored in a database, sent over the network.
- A **library** (`CounterHelper`) — behavior that operates on the model, plus references to the things it needs (the model, the I/O source). Not portable — bound to the process that runs it.
- Another **library** (`Main`) — the main one. Runs the infinite loop that keeps the server alive, and wires the model and `CounterHelper` together. Still a library (processing logic, not data), just one whose specific job is orchestration.

#### The model: `Counter`

A pure data class. One field, `myVote`, of type `Vote` (an `enum` with three values: `LIKE`, `DISLIKE`, `NONE`). A getter and a setter. No logic.

```java
public class Counter {
    public enum Vote { LIKE, DISLIKE, NONE }
    private Vote myVote = Vote.NONE;

    public Vote getMyVote() { return myVote; }
    public void setMyVote(Vote vote) { this.myVote = vote; }
}
```

- **An `enum` for the vote** — the vote can only be one of three values, and `enum` lets the compiler enforce that. No invalid states like `"yes"` or `42`.
- **No behavior** — no `like()`, no `score()`. Those belong to the library. The model's only job is to hold state.

#### The library: `CounterHelper`

Where behavior lives. It holds references to its dependencies — the `Counter` model it operates on and the `Scanner` library it reads input from — and exposes methods that are called from the main loop.

```java
public class CounterHelper {
    private final Counter counter;
    private final Scanner scanner;

    public CounterHelper(Counter counter, Scanner scanner) { ... }

    public String readCommand() { ... }      // wraps I/O
    public boolean handle(String cmd) { ... }  // mutates the model
    public int score() { ... }                 // derives a value from the model
}
```

Two things worth noticing:

1. **It's our own library, sitting next to an external one.** `CounterHelper` is library code we wrote; `java.util.Scanner` is library code the JDK provides. Both play the same role — processing logic while possibly bundling some state. The distinction between "library I wrote" and "library I imported" isn't structural; it's just about who packaged it.
2. **The model is still separate.** `CounterHelper` library holds a *reference* to a `Counter` model — it doesn't become one. You could swap the counter model out, hand it to a different helper library, or serialize it away. The model is portable; the helper library isn't.

#### Another library: `Main`

`Main` is a library too — it's code with processing logic, not a model. Its specific job is **orchestration**: it creates the pieces (Counter, Scanner, CounterHelper), wires them together, and runs the infinite `while` loop that keeps the server process alive.

```java
public class Main {
    public static void main(String[] args) {
        Counter counter = new Counter();
        Scanner scanner = new Scanner(System.in);
        CounterHelper helper = new CounterHelper(counter, scanner);

        while (true) {
            String cmd = helper.readCommand();
            if (!helper.handle(cmd)) return;
            System.out.println("score: " + helper.score());
        }
    }
}
```

It's the JVM's required entry point — every Java program needs a `main` method somewhere. But nothing says the `main` method has to live in the same class as the rest of the logic. Splitting it into its own `Main` class keeps `CounterHelper` focused on its one job (operating on the Counter) and puts the orchestration concern in its own file.

Why call it a library and not something fancier like "orchestrator"? Because splitting the compile-time world into just *model* and *library* is simpler, and it's accurate — orchestration *is* processing logic. Two libraries doing different things (one operating on the model, one running the loop) is still two libraries.

## Run It

```bash
cd challenge-0-counter-server-process
javac Counter.java CounterHelper.java Main.java
java Main
```



## What's Missing


- **Multiple counters** — there's only one counter in the whole system. A real platform like YouTube has millions.
- **Multiple users** — only one user, so the counter can only be -1, 0, or +1. A real life system needs many users, each with their own vote, aggregated into a total.
- **Network access** — no way to reach it from a browser or another machine.
- **Persistence** — close the terminal, lose the vote.
- **Concurrency** — not even a concern yet, because there's only one thread running inside one process.

Challenge 1 tackles the first one: one user voting on many different counters.


## Notes


A few more things worth noticing about the design:

- The server holds a single vote state that persists across iterations for the lifetime of the process.
- The vote is **idempotent**: pressing `l` five times has the same effect as pressing it once. That's different from a raw counter that just goes up. This is an important property of the like/dislike model — one user, one vote.
- `score` in the response is **derived** from the vote state, not stored separately. The server keeps the minimal state (the vote) and computes the display value from it.
- In challenge 1, we'll introduce an actual separate process (a browser) that talks to this server over HTTP. The server's loop stays the same shape; only the input/output mechanism changes.

Keeping model and library strictly separate is deliberate. In challenge 2, the `Counter` model will physically move out of this process and into a database. The library (behavior) can't move — you can't put a Java method in a Postgres column. So we're setting the split up now, while the move is still hypothetical.

### A note on language-specificity

The *names* here are Java-flavored — "class", "enum", `Scanner`. But the *shapes* aren't. In Python you'd have a dataclass for the model and two separate classes (or modules) for the helper and the entry point. In Go you'd have a struct for the model and another struct with methods for the helper, plus a `main` function in a package. In every case the compile-time structure mirrors the same runtime picture: a portable data model and process-bound libraries that operate on it and run the loop.

The fact that the Counter process happens to be a JVM process is an implementation detail — we picked Java, so we get a JVM. The same program could be written in Python, Go, or C, and the story would be identical: one process, one machine, one OS, a pair of I/O streams. What matters at this level is the *shape* (process running on an OS with stdin/stdout, taking data requests from a user and returning data responses), not which language runtime we're using. 
