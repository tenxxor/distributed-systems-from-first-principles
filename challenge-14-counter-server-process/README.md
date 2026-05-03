# Challenge 14 — Task Queues

## Problem

Through challenges 0–13, every operation has been **synchronous**: the client sends a request, the counter does the work (DB write, cache invalidation, response), and the client gets the response when all of it is done. Latency = sum of every step.

This works when:
- The work is fast (a few SQL statements).
- The work has to finish before responding.
- The result of the work is what the client wanted.

But real systems have lots of work that doesn't fit this shape:

1. **Slow side effects the user shouldn't wait for.** Imagine voting triggers a notification — email, push, Slack. Sending an email takes 100–500ms. If we do it in the request handler, every vote becomes that slow. Email failures (SMTP timeout) bubble up as user-visible 5xx.
2. **Work that doesn't matter to the response.** Recompute trending counters once an hour. Index newly-created counters into search. Generate analytics rollups.
3. **Work that should retry on failure.** A flaky downstream shouldn't roll back the user's vote.

The architectural fix is **task queues**: when the request handler needs to do work that fits the above pattern, it **enqueues a message** describing the work, returns immediately, and a separate **worker** process pulls the message off the queue and does the actual work.

```
WITHOUT queue:
  client → POST /vote → counter → DB → cache invalidate → email send → response (slow)

WITH queue:
  client → POST /vote → counter → DB → cache invalidate → enqueue → response (fast)
                                                              │
                                                              ▼
                                                          task queue
                                                              │
                                                              ▼
                                                          worker → email send (retries on failure)
```

Same eventual behavior; **different latency profile and failure semantics**.

## Product

10 containers in compose. New since challenge 13:

- **1 `redis-queue` container** — second Redis, with AOF persistence enabled, used exclusively for the task queue. Separate from `redis-cache` so cache and queue have independent failure domains and persistence settings.
- **1 `worker` container** — runs the same JAR as the counters but with `command: ["worker"]`, dispatching to `WorkerApplication.runWorker()`. Long-running loop: BLPOP, process, requeue-on-failure, repeat.

The async work we demonstrate is **vote notifications** — when a vote succeeds, the counter enqueues a `vote-notification` task. The worker picks it up later and "delivers" it (in the demo, a log line with a synthetic 500ms sleep representing the cost of a real downstream like email or Slack).

Semantics:
- **At-least-once delivery.** A task succeeds → it's gone from the queue. A task fails → it gets requeued with `attempts + 1`. After 3 attempts, it goes to a dead-letter list for manual inspection.
- **Worker can be restarted, scaled, or moved.** Tasks survive worker outages because they live in Redis (with AOF persistence).
- **Single image, multi-role pattern.** Same Docker image powers counters and worker; compose decides via `command:`.

## Programming

### Run-time — What's Actually Happening

![Three client processes outside the cluster reach the LB container on localhost:9000. Inside the cluster private network (bridge): the LB container, three counter containers (admin ports :8081 published as host :18081/:28081/:38081, plus per-container metric models), TWO separate Redis containers, three independent Postgres SHARD containers, and a new worker container. redis-cache holds the cache models with no persistent volume. redis-queue holds task queue models and dead-letter models, with a Block Storage Infra outside the cluster network (mounted at /data via the redis-queue-data volume) that holds the AOF log of all push/pull operations from the queue — accessed via file handles. Counters bidirectionally interact with both redis-cache (cache GET/SET/DEL with replies) and redis-queue (RPUSH enqueue with queue-length replies). The worker bidirectionally interacts with redis-queue only — BLPOP to dequeue tasks, RPUSH to retry/dead-letter. The worker has no inbound network surface. Each Postgres shard has its own block storage volume mounted at /var/lib/postgresql/data, accessed via file handles.](./diagram.png)

```
┌─ host machine ──────────────────────────────────────────────────────────────┐
│                                                                              │
│   client ── http://<host>:9000                                               │
│                                                                              │
│   ┌────────────── cluster private network (bridge) ───────────────────────┐  │
│   │             ┌──────────┐                                              │  │
│   │             │ nginx-lb │                                              │  │
│   │             └─────┬────┘                                              │  │
│   │       ┌───────────┼───────────┐                                       │  │
│   │       ▼           ▼           ▼                                       │  │
│   │   counter-1   counter-2   counter-3                                   │  │
│   │     │ │ │       │ │ │       │ │ │                                     │  │
│   │     │ │ │ ┌─────┴─┼─┴───────┴─┴─┴─▶ redis-cache (no AOF)              │  │
│   │     │ │ │ │                                                           │  │
│   │     │ │ │ │ ┌─────┴────────────┐                                      │  │
│   │     │ │ │ └─┴─▶ redis-queue (AOF on, persistent)                      │  │
│   │     │ │ │       ▲                                                     │  │
│   │     │ │ │       │ BLPOP                                                │  │
│   │     │ │ │     ┌─┴────────┐                                            │  │
│   │     │ │ │     │  worker  │                                            │  │
│   │     │ │ │     │ (no API) │                                            │  │
│   │     │ │ │     └──────────┘                                            │  │
│   │     │ │ └─ shard route via CRC32 % 3 ───┐                              │  │
│   │     │ │                                  │                              │  │
│   │     │ │   ┌──────────────┬───────────────┘                              │  │
│   │     │ │   ▼              ▼                                              │  │
│   │     │ │  postgres-shard-0  postgres-shard-1  postgres-shard-2           │  │
│   │     │ │   ▲              ▲              ▲                               │  │
│   │     └─┴───┼──────────────┼──────────────┘                               │  │
│   │           │              │                                              │  │
│   │     pg-shard-0-data  pg-shard-1-data  pg-shard-2-data                   │  │
│   │     (volume)         (volume)         (volume)                          │  │
│   │                                                                         │  │
│   │     redis-queue-data (volume — AOF file)                                │  │
│   │                                                                         │  │
│   └─────────────────────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────────────────┘
```

The new shapes worth seeing in this diagram:

- **Counters write to BOTH Redises**: `redis-cache` for cache reads/invalidations, `redis-queue` for enqueueing tasks.
- **Worker only talks to `redis-queue`** — pulls tasks via BLPOP, processes, optionally requeues on failure.
- **Worker has no inbound network surface** — it's a pure consumer. No port published, no LB connection.
- **`redis-queue` has its own volume**; `redis-cache` does not.

#### Data

| Data | Where it lives | Notes |
|---|---|---|
| Counter rows, user votes | Postgres shards | Unchanged from challenge 13 |
| Cache entries | `redis-cache` (no volume) | Unchanged from earlier challenges |
| **Task messages** (new) | `redis-queue` list `tasks:notifications` | JSON-serialized, ordered, FIFO |
| **Dead-letter tasks** (new) | `redis-queue` list `tasks:notifications:dead-letter` | Tasks that failed 3 times; require manual handling |
| **AOF log** (new) | `redis-queue-data` volume | Redis's append-only log; replayed on restart to restore queue state |

The new pieces of data are **task messages** — pure-data records describing work to do. By the framework's quick test ("could be sent over the network as JSON?"), they're unambiguously **models**.

#### Process

Two new architectural processes vs challenge 13:

- **`redis-queue`** — second Redis instance with AOF on.
- **`worker`** — same JAR as counters, different role. No HTTP endpoint; long-running consumer loop.

The runtime communication graph:

- **Counter → redis-queue**: RPUSH on the way out (enqueue), nothing else.
- **Counter → redis-cache**: GET/SET/DEL, unchanged.
- **Worker → redis-queue**: BLPOP (blocking dequeue), RPUSH on retry, RPUSH on dead-letter.
- **Worker doesn't touch Postgres**. The notification handler just logs (in our demo). Real handlers might call external APIs, hit secondary DBs, push to other queues — but they wouldn't typically write to the same primary DB the counter uses.

#### Infrastructure

- **10 containers**: 1 nginx (LB), 3 counters, 3 Postgres shards, 2 Redis (cache + queue), 1 worker.
- **4 volumes**: 3 Postgres shards + redis-queue-data (new). `redis-cache` deliberately has no volume.
- **Same 4 published host ports** as before: 9000 + 18081/28081/38081.
- **Worker publishes nothing** — internal-only.

### Compile-Time — How to Implement It

Three new things: a task message model, a queue library, and a worker entry point.

#### The model: `TaskMessage` (new)

A JSON-serializable record describing one queued task. Fields: `taskId` (UUID), `type`, `payload`, `attempts`, `enqueuedAt`. The `attempts` counter is incremented on retry; when it hits `MAX_ATTEMPTS`, the task moves to dead-letter.

```java
public record TaskMessage(
        String taskId,
        String type,
        VoteNotificationPayload payload,
        int attempts,
        long enqueuedAt
) { ... }
```

#### The model: `config.yml` (updated)

The single `redis:` block becomes two:

```yaml
cacheRedis:
  url: redis://${REDIS_CACHE_HOST:-redis-cache}:6379
queueRedis:
  url: redis://${REDIS_QUEUE_HOST:-redis-queue}:6379
```

Each has its own client connection inside the counter and (for the queue) inside the worker.

#### The model: `docker-compose.yml` (updated)

- New `redis-queue` service with AOF: `--appendonly yes --appendfsync everysec`. Mounts `redis-queue-data:/data` so the AOF log persists.
- New `worker` service. Same image as counters; `command: ["worker"]` triggers the `main()` dispatcher to call `WorkerApplication.runWorker()`.
- Counters now `depends_on` both `redis-cache` and `redis-queue`.

#### The library: `TaskQueue` (new)

Thin wrapper around Redis lists. Three operations:

- **`enqueue(type, payload)`** — RPUSH a fresh task onto `tasks:notifications`. Generates a UUID, sets `attempts=0`, stamps `enqueuedAt`.
- **`blockingDequeue(timeout)`** — BLPOP wait up to `timeout` seconds for a task. Returns `Optional<TaskMessage>` (empty on timeout).
- **`requeueWithRetry(msg)`** — RPUSH a copy with `attempts + 1`. If the new attempts ≥ MAX_ATTEMPTS, dead-letter instead.

#### The library: `NotificationHandler` (new)

Stand-in for whatever the real downstream side effect would be. For the demo:

- Sleeps 500ms (synthetic latency, representing email/SMTP/Slack/etc).
- Throws `RuntimeException` if the counter ID starts with `trigger-failure-` (synthetic failure for demoing retries).
- Otherwise logs `notification.delivered`.

#### The library: `WorkerApplication` (new)

Long-running loop:

```java
while (true) {
    Optional<TaskMessage> taskOpt = queue.blockingDequeue(BLPOP_TIMEOUT);
    if (taskOpt.isEmpty()) continue;
    TaskMessage task = taskOpt.get();
    try {
        handler.handle(task);
    } catch (Exception e) {
        queue.requeueWithRetry(task);
    }
}
```

Same JAR as the counters. The `main()` dispatcher in `CounterApplication` checks `args[0]`:

```java
public static void main(String[] args) throws Exception {
    if (args.length > 0 && args[0].equals("worker")) {
        WorkerApplication.runWorker();
        return;
    }
    new CounterApplication().run(args);
}
```

`docker run app.jar server config.yml` → counter mode. `docker run app.jar worker` → worker mode. Same image, same code, two roles.

#### The library: `CounterHelper` (updated)

After a successful vote, the helper enqueues a notification task:

```java
queue.enqueue("vote-notification", new TaskMessage.VoteNotificationPayload(
        counterId, userId, v.name(), c.getLikes(), c.getDislikes()));
```

That's the entire producer side. Two lines.

## Run It

```bash
cd challenge-14-counter-server-process
mvn -q -DskipTests package
docker compose up --build -d
```

10 containers come up. Wait ~10s for everything healthy.

### Vote and watch the queue

```bash
# Create a counter and vote three times
curl -s -X POST http://localhost:9000/api/v1/counters \
    -H "Content-Type: application/json" -d '{"counterId":"video-async"}'
echo

for i in 1 2 3; do
    curl -s -o /dev/null -w "vote $i: time=%{time_total}s\n" \
        -H "X-User-Id: user-$i" -X PUT \
        http://localhost:9000/api/v1/counters/video-async/vote \
        -H "Content-Type: application/json" -d '{"vote":"LIKE"}'
done
```

You should see vote times in **tens of milliseconds**, NOT half a second. The 500ms synthetic latency in the worker doesn't block the response path.

### Watch worker process the queue

```bash
docker compose logs worker | grep -E "notification\."
```

You'll see the worker pick up tasks ~500ms apart, each one taking 500ms to "deliver." The `ageInQueueMs` field grows for tasks that wait behind earlier ones — that's the queueing-up effect.

### Verify queue persistence

```bash
# Stop the worker, vote 5 times — votes are still fast
docker stop worker
for i in 4 5 6 7 8; do
    curl -s -o /dev/null -w "vote $i: time=%{time_total}s\n" \
        -H "X-User-Id: user-$i" -X PUT \
        http://localhost:9000/api/v1/counters/video-async/vote \
        -H "Content-Type: application/json" -d '{"vote":"LIKE"}'
done

# Backlog grew
docker compose exec redis-queue redis-cli LLEN tasks:notifications
# → 5

# Restart worker; backlog drains
docker start worker
sleep 6
docker compose exec redis-queue redis-cli LLEN tasks:notifications
# → 0
```

Even with the worker fully down, votes keep returning in tens of ms. The queue absorbs the load. Worker comes back, drains the backlog at 500ms/task.

### Demo retry + dead-letter

A counter ID starting with `trigger-failure-` makes the handler always throw:

```bash
curl -s -X POST http://localhost:9000/api/v1/counters \
    -H "Content-Type: application/json" -d '{"counterId":"trigger-failure-1"}'
echo

curl -s -o /dev/null -w "vote: time=%{time_total}s\n" \
    -H "X-User-Id: alice" -X PUT \
    http://localhost:9000/api/v1/counters/trigger-failure-1/vote \
    -H "Content-Type: application/json" -d '{"vote":"LIKE"}'

sleep 3
docker compose logs worker | grep -E "task\.requeued|task\.dead-letter"
docker compose exec redis-queue redis-cli LLEN tasks:notifications:dead-letter
# → 1
docker compose exec redis-queue redis-cli LRANGE tasks:notifications:dead-letter 0 -1
```

Three retries (attempts 1, 2, 3) → fourth attempt would exceed MAX_ATTEMPTS so the task moves to the dead-letter list with the full payload preserved for manual inspection.

### Scale workers

The compose has one worker, but you can run multiple:

```bash
docker compose up -d --scale worker=3
```

Three workers compete for tasks via BLPOP — Redis's atomic dequeue guarantees each task goes to exactly one worker. Throughput triples (in our synthetic case, that means 1.5 notifications/sec instead of 0.5).

### Stop everything

```bash
docker compose down       # keeps queue + shard data
docker compose down -v    # wipes everything
```

## What's Missing

1. **No idempotency guard.** Tasks are at-least-once — a worker that crashes mid-processing causes the task to be re-tried and possibly re-delivered. Real notification systems handle this with a dedup table keyed on `taskId`. We don't, because our synthetic notification is "log a line" and double-logs are harmless.

2. **BLPOP loses tasks on worker crash mid-processing.** When the worker pops a task off the queue, it's removed immediately. If the worker crashes between popping and finishing, the task is gone. The fix: use `BRPOPLPUSH` to atomically move tasks to a "processing" list that the worker drains on success — surviving worker crashes mid-task. Real queue libraries (Sidekiq, Resque) do this.

3. **No retry backoff.** Failed tasks go right back to the queue tail. With persistent failure (e.g., Slack outage), the worker spins on the failed task at full speed. Production systems use **exponential backoff** — wait 1s, 2s, 4s, ... between retries, often with jitter.

4. **Dead-letter queue requires manual handling.** Tasks that fail 3× sit forever until an operator does something. No automatic alerting, no replay-to-main-queue UI, no retention policy.

5. **No circuit breaker.** If the downstream is down for an hour, every task hits the 500ms timeout, fails, retries, fails again. We'd want to detect "downstream is down" and pause processing rather than burn through the queue retrying.

6. **Single point of failure: redis-queue.** If `redis-queue` dies, no new tasks can be enqueued (the producer side fails fast and logs but doesn't fail the user request) AND no tasks can be processed (worker BLPOP fails). HA Redis (Sentinel or Cluster) would fix this.

7. **Producer continues on enqueue failure.** If `queue.enqueue()` fails, the user's vote already succeeded. We log and move on. Some apps want stricter semantics: "if I can't enqueue the side effect, fail the request." Pick based on whether the side effect is critical (e.g., billing event) or best-effort (notification).

Carried forward from earlier challenges: secrets in plaintext, no circuit breaker on Redis from the cache side, scatter-gather LIST inefficiency at scale, primary as SPOF for writes, etc.

## Notes

### Could we have used ONE Redis for cache + queue? Yes — here's the trade-off

We split cache and queue onto two separate Redis instances. Both shapes are real production patterns. Here's the precise comparison.

**What the single-Redis version would look like (changes vs. what we built):**

```yaml
# docker-compose.yml — one Redis container instead of two:
redis:
  image: redis:7-alpine
  command:
    - "redis-server"
    - "--appendonly"
    - "yes"           # Enable AOF for queue durability — affects cache too
    - "--appendfsync"
    - "everysec"
  volumes:
    - redis-data:/data
  networks: [cluster-net]
```

```java
// CounterApplication.java — one connection instead of two:
StatefulRedisConnection<String, String> conn =
    connectRedis(environment, configuration.getRedis().getUrl(), "shared");
RedisCache cache = new RedisCache(conn, environment.getObjectMapper());
TaskQueue queue = new TaskQueue(conn, environment.getObjectMapper());
```

Roughly **30 lines of YAML + Java that disappear**. Same ~30 lines that would *appear* on the other side: split into two distinct Redis services and config blocks.

**Pros of one Redis:**
- **Operational simplicity.** One container, one backup, one connection per process.
- **Resource efficiency at small scale.** One process's idle overhead instead of two.
- **Atomic multi-key operations remain possible.** A Lua script could atomically dequeue a task AND update a cache entry. Edge case, but available.

**Cons of one Redis:**
- **Coupled failure domain.** Redis dies → cache AND queue both unavailable.
- **Performance interference.** Redis is single-threaded; a slow operation on one workload blocks the other.
- **Persistence settings conflict.** Cache wants no AOF (regenerable, fast). Queue wants AOF (durable). With one Redis, you pick one — and the queue's needs dominate, so cache writes pay the AOF cost.
- **Memory pressure interference.** Cache eviction policies might evict queue tasks if you set up `maxmemory-policy` carelessly.

**Pros of two Redises (what we built):**
- **Independent failure domains.** Cache outage doesn't kill the queue.
- **Workload isolation.** Slow cache scan doesn't block queue dequeues.
- **Per-instance config.** Cache without persistence; queue with AOF.

**Cons:**
- **More moving parts.** Two containers, two configs, two client connections per process.
- **Doubled idle resource overhead.**
- **No cross-instance atomic ops.**

**Industry practice:** small-to-medium teams often start with one Redis (simpler). High-scale teams typically split (failure isolation, workload isolation, different SLAs). It's a real trade-off, not a "right answer."

We picked two for challenge 14 because the AOF-on-the-cache cost is real and the failure-domain story is cleaner. If you're optimizing for fewer moving parts in your demo cluster, one Redis is defensible — and the migration from one to two (or back) is mechanical, not architectural.

### "Single image, multi-role" pattern

Same JAR ships two main classes. The Dockerfile uses the default CMD for the counter mode; the worker compose service overrides via `command: ["worker"]`. The `main()` dispatcher in `CounterApplication` checks the first arg and routes to either `CounterApplication.run()` (Dropwizard server) or `WorkerApplication.runWorker()` (loop forever processing tasks).

Why this is the standard pattern:
- **Same dependencies.** Worker uses `TaskMessage`, `TaskQueue`, Lettuce, Jackson — all already in the JAR.
- **Code reuse is automatic.** No "shared library" project, no submodules.
- **One CI build, one image.** Build once, run as either role.

You see this everywhere in production: Sidekiq workers + Rails web in the same Gemfile, Celery workers + Django web, Kubernetes Jobs reusing a Deployment's image, etc.

### At-least-once vs exactly-once: the honest version

We have **at-least-once**. A worker can crash after processing a task but before acknowledging — actually our setup is even weaker, BLPOP removes the task on dequeue, so a crash mid-processing loses the task entirely. (See "What's Missing" — `BRPOPLPUSH` would fix this.)

"Exactly-once" sounds nicer but is famously hard to actually achieve. Most claims of exactly-once are really "at-least-once + idempotent processing." Our setup needs the same — if the handler is idempotent (or the side effect is harmless to repeat), at-least-once with retries gives effectively-exactly-once behavior.

For our notification: re-delivering a notification log line twice is harmless. A real email sender would key emails on `taskId` and skip already-sent IDs.

### Producer-side queue failure handling: keep going

When `queue.enqueue()` fails (Redis down, network blip), our producer **logs and continues**. The user's vote response still says success. The side effect is lost.

This is a deliberate trade-off:
- The user's intent (recording a vote) succeeded — the DB has it.
- The side effect (notification) is best-effort.
- Failing the request because the queue is down would mean a DB outage on the queue side cascades into user-visible 5xx on the request side. That defeats the decoupling.

For business-critical side effects (e.g., billing events, regulatory notifications), you'd want different semantics — typically a transactional outbox pattern: write the event into a DB table in the same transaction as the main work, and a separate process polls the table and forwards to the queue. We don't implement that; it's a real pattern for when the side effect *can't* be lost.

### The synthetic 500ms is a stand-in for real cost

Real notification work takes 100–500ms — establishing a TLS connection, rendering a template, calling an API, waiting for an ack. Our `Thread.sleep(500)` is a representative cost. The point is: **whatever that latency is, the queue means the user doesn't pay it on the request path**.

Replace the sleep with a real SMTP call, a Slack webhook, a Twilio API call — same architectural shape, same latency benefit. The pedagogical cost is bounded; the pattern is exact.

### Why `redis-queue` has a volume but `redis-cache` doesn't

This is the cleanest expression of the "cache is regenerable; queue isn't" distinction.

- **Cache** holds derived state. Lose it → repopulate from Postgres on the next read. No data loss, just a brief cold-cache window.
- **Queue** holds in-flight work. Lose it → tasks are lost. The user thought their vote triggered a notification; the notification never happens.

So `redis-cache` runs without persistence, `redis-queue` runs with AOF. The distinction is **what's the source of truth?** For cache, Postgres is. For queue, the queue itself is.

### How AOF persistence actually works (and why it's not a "task table")

A subtle point worth nailing down: when we say `redis-queue` "persists tasks," the tasks themselves don't live on disk. The mechanism is different from Postgres.

**In RAM (inside the `redis-queue` process):**
- The list `tasks:notifications` with the task JSON entries.
- The list `tasks:notifications:dead-letter`.
- Everything Redis serves to clients comes from here. BLPOP reads from RAM. RPUSH writes to RAM.

**On disk (in the `redis-queue-data` volume, mounted at `/data`):**
- A file called `appendonly.aof` (or `appendonlydir/` in Redis 7+).
- This is **not** the tasks themselves. It's a **log of every write command** Redis received.

When the counter does `RPUSH tasks:notifications {"taskId":"abc",...}`:
1. Redis updates the in-memory list — appends the task to the tail.
2. Redis appends the **command itself** to the AOF file on disk.
3. Redis returns success to the counter.

When the worker does `BLPOP tasks:notifications`:
1. Redis pops the head of the in-memory list and returns it.
2. Redis appends `LPOP tasks:notifications` to the AOF.

So the AOF is a **transcript of every mutation**, not a snapshot of current state.

**On restart:**
1. The `redis-queue` process starts with empty memory.
2. It sees an AOF file in `/data` and replays every command in order: RPUSH, RPUSH, LPOP, RPUSH, ...
3. After replay, in-memory state matches what it was before the crash.
4. Now it starts accepting new client commands.

The persistence story is: **tasks survive restarts, but not because the tasks live on disk — because the recipe to rebuild them lives on disk.**

**Why this design instead of writing tasks into a "table"?** Redis is fundamentally an in-memory database. Its data structures (lists, sets, sorted sets, hashes) are designed for RAM access patterns. Writing them to disk in their native form would be slow and complex. A log-of-commands is:
- **Fast to write** — sequential append, no seeks.
- **Crash-safe** — with `appendfsync everysec`, you lose at most ~1 second of writes.
- **Simple** — just write what the client said.

Postgres makes the opposite trade-off: data lives on disk in a structured format (tables, indexes, B-trees), and RAM is a cache (the buffer pool). When you query Postgres, it reads pages from disk into RAM.

| | Source of truth | RAM is... | Disk is... |
|---|---|---|---|
| Postgres | Disk | A cache (buffer pool) | The actual data (table files) |
| `redis-queue` | RAM | The actual data (lists) | A replay log (AOF) |

This is also why a diagram of the `redis-queue` volume shouldn't show a "tasks table" the way a Postgres volume shows table files. The accurate label for what's inside `redis-queue-data` is `appendonly.aof` — a sequence of write commands. The tasks themselves belong in the process box, in RAM.

### BLPOP is blocking, not polling — the worker waits, it doesn't ask

A natural assumption looking at the worker loop is: "the worker is constantly asking Redis if there's a task." That would be **polling** — and it would be wasteful (CPU spin, network round-trips, mostly empty answers).

BLPOP is different. It's **B**locking **L**eft **POP**.

**Polling (`LPOP` in a loop) would look like:**
- Worker: "Any tasks?" Redis: "No."
- Worker sleeps briefly.
- Worker: "Any tasks?" Redis: "No."
- ...repeat thousands of times per second per worker...

**Blocking (`BLPOP`) actually looks like:**
- Worker opens a TCP connection and says: "Give me the next task. If there isn't one, **don't answer me** until there is — wait up to N seconds."
- Redis parks the connection. No reply. The worker thread is suspended by the OS.
- The instant a counter does `RPUSH`, Redis wakes the parked connection and hands over the task in the same operation.
- Worker processes the task, then loops back into another BLPOP.

Pizza shop analogy:
- Polling = calling the shop every 30 seconds: "is my pizza ready?" "no." *click.* Repeat.
- Blocking = calling once and staying on the line: "tell me when it's ready, I'll wait." Quiet line until they speak up.

Same outcome, very different cost. With BLPOP:
- **No CPU spin.** The worker thread is OS-suspended during the wait.
- **No empty round-trips.** Redis doesn't reply until there's data (or the timeout fires).
- **Sub-millisecond wakeup.** Redis pushes the task the instant `RPUSH` completes.

The `BLPOP_TIMEOUT` exists so the worker periodically returns control (to handle shutdown signals, log heartbeats, etc.) — but during that timeout window the worker is **idle on Redis's side, not hammering it**. Empty result → loop back into another BLPOP.

So when the diagram shows the worker connected to `redis-queue` via BLPOP, the right mental model is **"a patient open phone line"**, not **"a noisy poll loop."**

### Why the worker is single-threaded (and why we scale horizontally instead)

A natural question: why does the worker process tasks one at a time? Couldn't a "listener thread" pull tasks off Redis and hand them to a pool of worker threads, processing many in parallel? That'd give us up to 8x throughput per container with 8 worker threads.

It would. But it adds real complexity that horizontal scaling avoids.

#### What single-threaded gives up

Throughput per container is bounded by `1 / handler-latency`. With 500ms tasks, that's 2 tasks/sec/worker. The listener-and-pool model could push that to 8 × 2 = 16 tasks/sec/worker (in the best case — less if tasks contend for CPU on a small container, less still if downstream services rate-limit).

#### What single-threaded gains us

- **Simple code.** `dequeue → process → loop` is 5 lines. Listener-and-pool is closer to 50 once you handle the gotchas below.
- **No concurrency bugs in the handler.** With a single thread, the handler doesn't need locks, doesn't share state across in-flight tasks, doesn't have race conditions.
- **Crash semantics are clearer.** When the worker dies, exactly one task is in-flight (if any). With a pool, N tasks are in-flight, all in different states.

#### Three real complications a thread pool would force on us

1. **Bounded pool queue.** `Executors.newFixedThreadPool(8)` defaults to an *unbounded* internal queue. Listener pulls faster than threads complete → tasks pile up in JVM memory → eventual OOM. Fix: use a bounded queue (`new LinkedBlockingQueue<>(100)`), then decide what to do when full (block the listener, reject the task, drop it). Each is a real policy choice.

2. **Shutdown drain.** SIGTERM should: (a) stop dequeuing new tasks, (b) wait up to N seconds for in-flight tasks to finish, (c) push unfinished ones back to Redis. With a pool, you have N in-flight states to coordinate. Without this, SIGTERM abandons tasks mid-processing.

3. **In-flight tracking.** Once a task is dequeued from Redis but not yet started by a thread, it's in a window where it's *out of Redis* but *not yet processed*. Worker crash there = task lost. The fix is `BRPOPLPUSH` to atomically move tasks to an "in-flight" list, then remove on success — protocol-level bookkeeping on top of Redis lists. Real, but more code than `BLPOP`.

#### The horizontal-scaling alternative

Run more *worker containers*, each single-threaded. `docker compose up -d --scale worker=5` runs 5 workers; Redis BLPOP atomically delivers each task to one worker, so they compete cleanly without coordinating.

| Approach | Pros | Cons |
|---|---|---|
| **One worker, single thread** (what we have) | Simplest. Crash boundary is one task. | Throughput capped at 1/task-time per container |
| **One worker, thread pool** | 8x throughput per container | Bounded-queue policy, drain logic, in-flight tracking, concurrency bugs in handler |
| **Many workers, single-threaded each** | Linear scaling. OS-level isolation. Trivial code. | More processes to monitor, slightly more memory overhead |

**For our setup, "many single-threaded workers" wins.** Compose makes scaling cheap, and we keep all the complexity at the orchestration layer (where it belongs) instead of inside the handler code (where it'd cause subtle bugs).

When you'd actually reach for the in-process pool: very high task rates with cheap tasks (10K/sec range), wildly uneven task latencies (one slow task shouldn't block dozens of fast ones), or environments where adding more containers is expensive (some serverless setups, memory-constrained edges). None of those describe our cluster.

The principle worth taking away: **prefer "more processes, simpler code" over "fewer processes, smarter code"** — until the scale forces otherwise. Modern container orchestration makes the first option cheap; it didn't always.

### What didn't change

Every Postgres shard, the cache, the LB, the API, the DAOs — all unchanged. The vote endpoint's response shape is unchanged. **Adding a queue is purely additive**: no existing behavior was modified, just a new step (enqueue) was inserted between "DB write" and "return to client."

This is the second time we've added a new stateful service alongside the existing ones (Postgres in 10, Redis cache in 11, second Redis here in 14). Each addition follows the same pattern: a new container, new client connection, new code path. The architecture grows by **adding services**, not by refactoring existing ones. That's the architectural-Lego property — well-layered systems compose.
