# Challenge 15 — Event-Driven Architecture (Kafka, alongside the task queue)

## Problem

Challenge 14 introduced task queues (Redis lists). They solve "do this work later." A vote happens → counter pushes a `vote-notification` task → exactly one worker pops it and sends the notification. Per-task retries, dead-letter, scheduled work — task queues are great at that.

But many real systems have a different shape too: **one event happens, many independent things need to react to it.**

When a vote is cast, real systems often want to:
- Send a notification (the challenge 14 use case — point-to-point, retryable)
- Update real-time analytics
- Append to an immutable audit log for compliance
- Maintain a "trending" leaderboard
- Stream to a data warehouse, recompute recommendations, recalculate ad pricing, etc.

With task queues alone, every new subscriber means a new queue and a new push from the producer — **the producer has to know about every consumer**. Add a fourth subscriber? Modify the producer.

The architectural alternative for this shape: **events**. Vote happens → producer publishes a `vote-cast` event to a single stream. Any number of consumers subscribe to that stream and each react independently. **The producer doesn't know who's listening.** Adding a new subscriber means deploying a new consumer, no producer change. And the events stay on disk — replayable.

The key insight this challenge bakes in: **task queues and event logs are complementary, not substitutes.** Each is best at a different shape of work. So we keep challenge 14's task queue + worker for notifications (where per-task retries matter), and we *add* Kafka with three consumers for the fan-out use cases (where broadcast and replay matter). Both infrastructures coexist and each does what it's good at.

## Product

15 containers in compose:

- 3 Postgres shards (unchanged)
- 1 redis-cache (unchanged)
- 1 redis-queue (challenge 14, unchanged — AOF on, durable)
- **1 Kafka broker** (KRaft mode — no ZooKeeper, modern setup) (new)
- **1 kafka-init** (one-shot job that creates the `vote-cast` topic with 3 partitions) (new)
- 3 counters (now publish to BOTH the Redis task queue AND the Kafka topic on every vote)
- 1 worker (challenge 14, unchanged — pops Redis queue, sends notifications)
- **1 analytics-consumer** (new — Kafka, in-memory event counts)
- **1 audit-consumer** (new — Kafka, writes every event to a Postgres `audit.audit_log` table)
- **1 trending-consumer** (new — Kafka, maintains a Redis sorted set of top counters)
- 1 nginx

The split:

| Use case | Infrastructure | Why it fits |
|---|---|---|
| Notifications | Redis task queue + worker | Per-task retries, dead-letter, scheduled future delivery — task-queue strengths |
| Real-time analytics | Kafka + analytics-consumer | Broadcast; in-memory counts; cheap to recompute |
| Audit log (compliance) | Kafka + audit-consumer + Postgres `audit.audit_log` | Durable, queryable, replayable from Kafka if corrupted |
| Trending leaderboard | Kafka + trending-consumer + Redis sorted set | Fast derived view; ZINCRBY on each event; one-line read for top-N |

The `vote-cast` Kafka topic has 3 partitions, keyed on `counterId`. Events for the same counter always land in the same partition (preserves per-counter ordering); events for different counters can spread across partitions for parallelism.

## Programming

### Run-time — What's Actually Happening

![Three client processes outside the cluster reach the LB container on localhost:9000. Inside the cluster private network (bridge): the LB container, three counter containers (admin ports :8081 published as host :18081/:28081/:38081), redis-cache, redis-queue, three Postgres shard containers, the notification worker container, a Kafka broker container, a kafka-init one-shot container that exits after creating the vote-cast topic, and three Kafka consumer containers (analytics-consumer, audit-consumer, trending-consumer). On every successful vote, each counter runs the operational write path against the Postgres shards (route by counterId hash) and reads/writes redis-cache, then performs TWO additional publishes: (a) RPUSH a vote-notification task to redis-queue (the worker BLPOPs and delivers notifications), and (b) producer.send a vote-cast event to the Kafka broker on port 9092. Inside Kafka, the vote-cast topic has 3 partitions keyed on counterId. The three Kafka consumers each subscribe in their own consumer group, so each independently sees every event: analytics-consumer counts events in memory, audit-consumer writes every event to the audit.audit_log table on postgres-shard-0 (manual offset commit, idempotent insert via ON CONFLICT DO NOTHING), trending-consumer maintains a Redis sorted set trending:counters in redis-cache via ZINCRBY. Volumes outside the cluster network: pg-shard-{0,1,2}-data mounted at /var/lib/postgresql/data on each shard, redis-queue-data mounted at /data on redis-queue (AOF persistence), kafka-data mounted at /var/lib/kafka/data on the Kafka broker (commit log: vote-cast partitions, __consumer_offsets, __cluster_metadata).](./diagram.png)

```
┌─ host machine ─────────────────────────────────────────────────────────────────┐
│                                                                                 │
│   client ── http://<host>:9000                                                  │
│                                                                                 │
│   ┌────────────── cluster private network (bridge) ───────────────────────┐    │
│   │                                                                         │    │
│   │              ┌──────────┐                                               │    │
│   │              │ nginx-lb │                                               │    │
│   │              └─────┬────┘                                               │    │
│   │       ┌────────────┼────────────┐                                       │    │
│   │       ▼            ▼            ▼                                       │    │
│   │   counter-1   counter-2   counter-3                                     │    │
│   │       │            │            │                                       │    │
│   │       │  on every successful vote: TWO publishes                        │    │
│   │       │     (a) RPUSH to Redis task queue   ┐                           │    │
│   │       │     (b) producer.send to Kafka      ┘                           │    │
│   │       │                                                                  │    │
│   │       ├──── reads/writes ──▶ postgres-shard-{0,1,2}                     │    │
│   │       ├──── cache ─────────▶ redis-cache                                │    │
│   │       │                                                                  │    │
│   │       │  (a) task-queue path (challenge 14, unchanged):                  │    │
│   │       └─▶ redis-queue ──BLPOP──▶ worker ──▶ notification.delivered      │    │
│   │                                                                          │    │
│   │          (b) event-stream path (new):                                    │    │
│   │              kafka (vote-cast topic, 3 partitions)                       │    │
│   │                ▲                                                         │    │
│   │                │ subscribe (each group sees all events independently)    │    │
│   │              ┌─┴────────────────────┐                                    │    │
│   │              │ analytics-consumer   │  group A → in-memory counts       │    │
│   │              │ audit-consumer       │  group B → Postgres audit.audit_log│   │
│   │              │ trending-consumer    │  group C → Redis sorted set        │    │
│   │              └──────────────────────┘                                    │    │
│   │                                                                          │    │
│   └──────────────────────────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────────────────────────┘
```

The notable shape change vs. challenge 14:

- **Counters publish to BOTH the task queue AND Kafka on every vote.** Same point in code, two side effects, two transports, two semantics.
- **The task-queue path is unchanged** — same redis-queue, same worker, same notification delivery.
- **The event-stream path is new** — three Kafka consumers, each in its own group, each writing to a different output (memory, Postgres, Redis).

#### Data

| Data | Where it lives | Notes |
|---|---|---|
| Counter rows, user votes | Postgres shards | Unchanged |
| Cache entries | redis-cache (no volume) | Unchanged |
| Notification tasks (in-flight) | redis-queue + AOF volume | Unchanged from challenge 14 |
| **Vote-cast events** (new) | Kafka topic `vote-cast`, 3 partitions, `kafka-data` volume | Retained on disk; replayable from any offset |
| **Per-group consumer offsets** (new) | Kafka's internal `__consumer_offsets` topic | Each consumer group tracks its own position |
| **Audit log** (new) | `audit.audit_log` table on postgres-shard-0 | Append-only; idempotent insert via `ON CONFLICT (event_id) DO NOTHING` |
| **Trending leaderboard** (new) | Redis sorted set `trending:counters` in redis-cache | ZINCRBY on every LIKE; ZREVRANGE for top-N |

A new piece of architecturally interesting data: **consumer offsets**. Each consumer group's "where am I" position is stored *inside Kafka*. This is what lets a consumer disconnect and resume, lets different groups see the same events at different rates, and lets you reset an offset to 0 to replay every event.

Compare with the Redis task queue, where the moment a task is BLPOP'd it's gone. Both models are correct — they're answering different questions. The task queue answers "did this specific unit of work get done"; Kafka answers "what happened, and who's caught up."

#### Process

New architectural processes vs challenge 14:

- **`kafka`** — the broker. KRaft mode: single binary serves both as broker (handles producer/consumer requests) and controller (manages cluster metadata). No ZooKeeper.
- **`kafka-init`** — one-shot container that creates the `vote-cast` topic with 3 partitions, then exits. Counters and consumers `depends_on: kafka-init: service_completed_successfully`.
- **`analytics-consumer`** — same JAR, role `analytics-consumer`. Group `analytics-consumer-group`. Counts events in memory.
- **`audit-consumer`** — same JAR, role `audit-consumer`. Group `audit-consumer-group`. Inserts into `audit.audit_log`. Manual offset commit (commits AFTER the INSERT succeeds, so a crash mid-batch doesn't lose audit rows).
- **`trending-consumer`** — same JAR, role `trending-consumer`. Group `trending-consumer-group`. ZINCRBY on a Redis sorted set.

Unchanged from challenge 14: `worker` (notification worker), redis-queue.

The communication graph at vote time:

- **Counter → redis-queue**: RPUSH `vote-notification` task. Synchronous local Redis call. Unchanged from challenge 14.
- **Counter → Kafka**: `producer.send(record, callback)` with key=counterId. Async; counter's request thread doesn't wait for the broker ack.
- **Kafka → consumer**: each consumer's `poll()` returns batches of records from the partitions assigned to its group.
- **Consumer → Kafka**: offset commits (auto for analytics + trending; manual for audit).

#### Infrastructure

- **15 containers**: 1 nginx, 3 counters, 3 Postgres shards, redis-cache, redis-queue, kafka, kafka-init (exits early), worker, 3 Kafka consumers.
- **5 volumes**: 3 Postgres shards + redis-queue-data + kafka-data.
- **4 published host ports**: 9000 + 18081/28081/38081 (unchanged).
- **Internal-only Kafka and Redis** — neither is exposed to the host.

### Compile-Time — How to Implement It

Two infrastructures means two libraries on the producer side and N consumers on the consumer side. The structure stays the same as challenge 14; we add the Kafka path next to it.

#### The model: `VoteCastEvent` (new)

A JSON-serializable record describing a single vote. Same fields as challenge 14's `TaskMessage` payload, framed differently:

- `TaskMessage` is a *task* — "do this work."
- `VoteCastEvent` is a *fact* — "this thing happened."

```java
public record VoteCastEvent(
        String eventId,
        String counterId,
        String userId,
        String vote,
        int likes,
        int dislikes,
        long occurredAt
) { ... }
```

No `attempts` field. Each consumer is responsible for its own retry/skip semantics via offset advancement.

#### The model: `config.yml` (updated)

Both `queueRedis` (from challenge 14) AND a new `kafka` block:

```yaml
queueRedis:
  url: ${QUEUE_REDIS_URL:-redis://redis-queue:6379}

kafka:
  bootstrapServers: ${KAFKA_BOOTSTRAP_SERVERS:-kafka:9092}
  voteTopic: ${KAFKA_VOTE_TOPIC:-vote-cast}
```

#### The model: `docker-compose.yml` (updated)

New services: `kafka`, `kafka-init`, `analytics-consumer`, `audit-consumer`, `trending-consumer`. Kept from challenge 14: `redis-queue`, `worker`.

The Kafka container runs in **KRaft mode** — modern Kafka (3.x+) doesn't need ZooKeeper. One binary plays both roles (broker + controller). Single-node setup; production runs multiple brokers and a separate quorum of controllers.

`kafka-init` is a one-shot container:

```yaml
kafka-init:
  image: confluentinc/cp-kafka:7.6.0
  depends_on: { kafka: { condition: service_healthy } }
  command:
    - "kafka-topics --bootstrap-server kafka:9092 --create --if-not-exists --topic vote-cast --partitions 3 --replication-factor 1"
```

Counters and Kafka consumers `depends_on: kafka-init: service_completed_successfully` — they wait for the topic to exist before booting.

#### The library: `EventPublisher` (new)

Wraps Kafka's `KafkaProducer<String, String>`. One method:

```java
publishVoteCast(counterId, userId, vote, likes, dislikes)
```

Builds a `VoteCastEvent`, serializes to JSON, sends with `key=counterId` (so all events for the same counter land on the same partition). Async — the producer thread returns immediately; Kafka's network thread handles the actual TCP send and ack.

#### The library: `AnalyticsConsumer` (new)

Auto-commit, in-memory event counts. Group: `analytics-consumer-group`. Demonstrates the cheapest possible consumer — derived state is regeneratable, so we don't care if some events get re-processed on restart.

#### The library: `AuditConsumer` (new)

Manual commit, inserts every event into `audit.audit_log`. Group: `audit-consumer-group`.

Two design choices worth calling out:

1. **Manual offset commit.** `enable.auto.commit=false`. After the batch's INSERTs succeed, the consumer calls `commitSync()`. If the consumer crashes mid-batch, the next start re-reads the un-committed records and re-inserts. The `ON CONFLICT (event_id) DO NOTHING` clause makes the insert idempotent, so duplicates are silently dropped.
2. **Dedicated `audit` schema.** Audit data lives in `audit.audit_log` rather than `public.audit_log`. Reason: Flyway manages the operational `public` schema for the counter app. If audit-consumer (which races ahead and creates `audit_log` before the counter app has run its V1 migration) put the table in `public`, Flyway would refuse to run V1 ("non-empty schema, no schema history table"). Using a separate schema cleanly avoids the collision and matches production reality — audit data usually lives in a dedicated schema or a dedicated database.

#### The library: `TrendingConsumer` (new)

Auto-commit. ZINCRBY on a Redis sorted set keyed by counterId; only LIKE votes count toward trending. Group: `trending-consumer-group`. Periodically logs the top 5 (`ZREVRANGE trending:counters 0 4`) so the demo is observable.

Replay caveat: re-running this consumer from offset 0 would double-count scores unless you reset the sorted set first. Derived-view consumers are not always idempotent on replay — see the notes on replay below.

#### The library: `CounterApplication` (updated)

`main()` dispatcher now has FIVE roles:

```java
public static void main(String[] args) throws Exception {
    if (args.length > 0) {
        switch (args[0]) {
            case "worker"              -> { WorkerApplication.runWorker();  return; }
            case "analytics-consumer"  -> { AnalyticsConsumer.run();        return; }
            case "audit-consumer"      -> { AuditConsumer.run();            return; }
            case "trending-consumer"   -> { TrendingConsumer.run();         return; }
        }
    }
    new CounterApplication().run(args);
}
```

The same JAR, the same image, five different processes — each picked by Compose's `command:` override.

#### The library: `CounterHelper` (updated)

`vote()` now publishes to BOTH paths after a successful DB write:

```java
// 1) Enqueue notification task — challenge 14 path, point-to-point, with retries.
queue.enqueue("vote-notification", new TaskMessage.VoteNotificationPayload(
        counterId, userId, v.name(), c.getLikes(), c.getDislikes()));

// 2) Publish vote-cast event — new fan-out path, broadcast to all subscribed consumer groups.
publisher.publishVoteCast(counterId, userId, v.name(), c.getLikes(), c.getDislikes());
```

Same data, two transports, two semantics. The producer doesn't know who's downstream of either.

#### New dependencies: `pom.xml`

Adds `org.apache.kafka:kafka-clients:3.7.0`. Both producer and consumer code lives in the same library.

## Run It

```bash
cd challenge-15-counter-server-process
mvn -q -DskipTests package
docker compose up --build -d
```

15 containers come up. Wait ~15s for everything healthy + topic created.

### Vote and watch all four downstream paths

```bash
# Create two counters
curl -s -X POST http://localhost:9000/api/v1/counters \
    -H "Content-Type: application/json" -d '{"counterId":"video-cats"}'
curl -s -X POST http://localhost:9000/api/v1/counters \
    -H "Content-Type: application/json" -d '{"counterId":"video-dogs"}'
echo

# 8 likes for cats, 6 likes for dogs
for i in $(seq 1 8); do
    curl -s -o /dev/null -X PUT \
        -H "Content-Type: application/json" -H "X-User-Id: user-cat-$i" \
        http://localhost:9000/api/v1/counters/video-cats/vote \
        -d '{"vote":"LIKE"}'
done
for i in $(seq 1 6); do
    curl -s -o /dev/null -X PUT \
        -H "Content-Type: application/json" -H "X-User-Id: user-dog-$i" \
        http://localhost:9000/api/v1/counters/video-dogs/vote \
        -d '{"vote":"LIKE"}'
done

sleep 6

# (a) Task queue path — worker delivered notifications
docker compose logs worker | grep notification.delivered | wc -l
# Expected: 14

# (b) Kafka path → analytics consumer (in-memory counts)
docker compose logs analytics-consumer | grep totalEvents | tail

# (c) Kafka path → audit consumer (Postgres)
docker compose exec postgres-shard-0 \
    psql -U counter_app -d counters \
    -c "SELECT counter_id, count(*) FROM audit.audit_log GROUP BY counter_id;"

# (d) Kafka path → trending consumer (Redis sorted set)
docker compose exec redis-cache redis-cli ZREVRANGE trending:counters 0 4 WITHSCORES
```

You should see the same 14 vote events delivered to all four destinations independently. The task queue handled exactly-one notification per vote; Kafka fanned out to three consumer groups in parallel.

### Inspect the topic and consumer offsets

```bash
docker compose exec kafka kafka-topics --bootstrap-server localhost:9092 --describe --topic vote-cast

for g in analytics-consumer-group audit-consumer-group trending-consumer-group; do
    echo "── $g ──"
    docker compose exec kafka kafka-consumer-groups \
        --bootstrap-server localhost:9092 --describe --group $g
done
```

Three groups, **independent offsets**, all on the same topic — the defining property of pub/sub.

### Replay events from the start (the Kafka demo)

This is the demo Redis queues couldn't do. We replay on the **audit consumer** because it has durable, observable output (a table you can re-populate).

```bash
# Truncate the audit table — pretend the data got corrupted.
docker compose exec postgres-shard-0 \
    psql -U counter_app -d counters -c "TRUNCATE audit.audit_log;"

# Stop the consumer (must be inactive to reset its offset)
docker stop audit-consumer

# Wait for the group to fully leave (session timeout ~45s)
until ! docker compose exec kafka kafka-consumer-groups \
    --bootstrap-server localhost:9092 \
    --describe --group audit-consumer-group 2>&1 | grep -q "Stable\|CompletingRebalance"; do
    sleep 3
done

# Reset offsets to earliest
docker compose exec kafka kafka-consumer-groups \
    --bootstrap-server localhost:9092 \
    --group audit-consumer-group \
    --reset-offsets --to-earliest --topic vote-cast --execute

# Restart
docker start audit-consumer
sleep 8

# Audit table should be re-populated from Kafka's retention.
docker compose exec postgres-shard-0 \
    psql -U counter_app -d counters \
    -c "SELECT counter_id, count(*) FROM audit.audit_log GROUP BY counter_id;"
```

Audit consumer re-reads every event from offset 0 and re-inserts them. **Analytics and trending consumers are unaffected** — their offsets are in different positions in `__consumer_offsets`, untouched by the audit reset. This independence is what events-vs-tasks gets you.

### Stop everything

```bash
docker compose down       # keeps Postgres + Kafka + redis-queue data
docker compose down -v    # wipes everything including Kafka commit log
```

## What's Missing

1. **Single-broker Kafka.** Production Kafka runs at least 3 brokers for fault tolerance. We use 1 because the lesson is "events," not "broker HA." Production replication factor would be 3 (each partition replicated on 3 brokers); ours is 1.

2. **No schema registry.** We serialize events as plain JSON. Production setups use **Avro** or **Protobuf** with a schema registry (Confluent Schema Registry, AWS Glue) — this catches schema-incompatible producer/consumer pairs at deploy time rather than at runtime.

3. **Mixed delivery semantics across consumers.** Analytics and trending use auto-commit (at-least-once with possible duplicates on crash); audit uses manual commit (still at-least-once, but committed only after the DB insert succeeds). Production sometimes uses Kafka transactions for true exactly-once; we don't.

4. **No dead-letter topic.** A persistently-failing event in any consumer just doesn't advance its offset, so it keeps being re-delivered. Real systems route failing events to a dead-letter topic for manual inspection.

5. **Trending replay double-counts.** If you reset the trending consumer's offset to 0 without first deleting the sorted set, scores accumulate twice. Real systems either (a) make derived-view updates idempotent (e.g., write-through with an idempotency key) or (b) bundle a state reset with the offset reset.

6. **No consumer-side metrics.** No `/metrics` on workers or consumers. Consumer lag (LOG-END-OFFSET − CURRENT-OFFSET) would be a primary alerting signal in production.

7. **No partition rebalancing demo.** With 3 partitions, you could run 3 instances of any consumer in the same group and watch them split partitions. We don't show this — it's a real concept worth knowing.

8. **Audit on shard-0, arbitrarily.** Audit doesn't need sharding (low write rate, queries are full scans), so we put it on shard-0 for simplicity. Production audit data usually lives on its own Postgres or in a dedicated columnar warehouse.

Carried forward: secrets in plaintext, scatter-gather LIST inefficiency at scale, primary-shard-as-SPOF for writes per shard, etc.

## Notes

### Why both task queue AND Kafka? Aren't they redundant?

They aren't — they're best at different shapes of work. The honest framing:

| | Task queue (Redis lists, SQS, Sidekiq) | Event log (Kafka, Pulsar) |
|---|---|---|
| **What it conveys** | "Do this work" — a unit of work delegated to one worker | "This happened" — a fact broadcast to anyone listening |
| **Lifecycle** | Removed on processing | Retained for retention period |
| **Consumers** | Exactly one per task | All subscribed groups see every event |
| **Replay** | No (once popped, gone) | Yes (reset offset, re-read) |
| **Strength** | Per-task tracking, retries, dead-letter, scheduled work | Fan-out, decoupling, audit history, replay |
| **Weakness** | Each new subscriber = producer change | Awkward for per-item ack/scheduling |

Real systems that run both side by side:

- **Slack**: Kafka for events ("a message was sent"), Sidekiq for scheduled tasks ("send this user a digest at 8am").
- **Shopify**: Kafka as the event backbone; Sidekiq for tasks tied to specific user actions (receipts, payment retries).
- **Stripe**: NSQ/SQS for tasks, Kafka for events.

The increasingly common shape is **Kafka as the event backbone with task queues downstream of it.** Events fan out; whichever consumer needs per-task semantics (retries, scheduled execution, ack/nack) hands work off to a task queue.

For this challenge we pick a simpler, parallel layout: counter publishes to BOTH directly. Same data on the wire, two transports, two semantics. The producer doesn't know who's downstream of either path.

### Tasks vs events — same data shape, different semantics

The architectural insight isn't about **what** is in the message — it's about **what the message means**.

| Aspect | Task | Event |
|---|---|---|
| **Mental model** | "Do this work" | "This thing happened" |
| **Producer's intent** | Delegating a unit of work | Announcing a fact |
| **Producer knows consumers?** | Effectively yes (one queue per work type) | No (publishes to topic; anyone can subscribe) |
| **Delivery** | Each message → exactly one consumer | Each message → every subscribed group |
| **Lifecycle** | Removed when processed | Retained for retention period |
| **Adding a new subscriber** | Producer must publish to a new queue | Subscriber just adds itself; producer untouched |

Notification fits the task shape: there's exactly one work output, it can fail and need retries, schedule-future-delivery makes sense. Analytics, audit, and trending all fit the event shape: they're independent reactions to a fact, none of them is "the canonical processor," and replay is meaningful.

### Why we picked Kafka (not Redis Streams, RabbitMQ, NATS, etc.)

All of these support some form of pub/sub. We picked Kafka because:

- **Industry standard.** Kafka concepts (topics, partitions, offsets, consumer groups) are the vocabulary readers will encounter elsewhere.
- **KRaft mode in 2024+ is reasonable to run.** No more ZooKeeper. One container instead of two.
- **Strong retention model.** Events sit on disk for a configurable time, replayable, regardless of consumer state.

Trade-off: Kafka has more operational surface area than Redis Streams or RabbitMQ. For our scale, that's pedagogical noise — but it's the noise readers will hit in real life.

### Partitions and ordering

Kafka topics are split into *partitions*. **Within a partition**, order is preserved. **Across partitions**, no global ordering.

Our topic has 3 partitions. We use `counterId` as the partition key, which means:

- All events for `video-cats` go to (say) partition 1, in arrival order. A consumer reads them in the order they were produced.
- Events for `video-dogs` might go to partition 2.
- Two events for `video-cats` can never be reordered relative to each other.
- An event for `video-cats` and an event for `video-dogs` have no defined relative order.

Why partitions? **Parallelism.** Multiple consumers in the same group can each handle different partitions concurrently. With 3 partitions and 3 consumer instances in a group, each one owns one partition's events — 3x the throughput vs a single consumer.

### Consumer groups vs consumer instances

Two distinct concepts that trip people up:

- **Group** = a set of consumers that *share work* on a topic. Each group has its own offset per partition.
- **Instance** = a running consumer process. Multiple instances in the same group split partitions; instances in different groups each see all events.

Three scenarios:

1. **One group, one instance** (our setup for each of analytics/audit/trending): one consumer reads all 3 partitions.
2. **One group, three instances** (`docker compose up -d --scale audit-consumer=3`): Kafka assigns one partition per instance. 3x throughput. Same group = competitive consumption.
3. **Three groups, one instance each** (our actual setup across the three Kafka consumers): each instance sees ALL events. This is the fan-out we use.

Scaling within a group is bounded by partition count: more than 3 instances in one group leaves the extras idle.

### How consumers actually read — polling, but not busy polling

The consumer code in this challenge is a `while (true) { consumer.poll(...) }` loop. It looks like a tight polling loop — and the naive read of "constantly polling" sounds wasteful. It isn't. Underneath the polling-shaped API is **long-polling**, which behaves much closer to a push channel.

What actually happens on each `poll(Duration.ofSeconds(5))` call:

1. The Kafka client sends a **fetch request** to the broker over an already-open TCP connection.
2. **If records are waiting** at the consumer's offset, the broker returns them immediately. The consumer processes them, loops, calls `poll()` again.
3. **If nothing is waiting**, the broker **holds the request open** (up to a server-side wait time, default ~500ms via `fetch.max.wait.ms`) until either:
   - New data arrives → broker sends it down the open connection, the consumer's `poll()` wakes up.
   - The server-side wait expires → broker returns an empty batch.
4. If the server returned empty before the client's 5-second `poll()` timeout, the client retries the fetch transparently within the same `poll()` call.

So the consumer thread is **blocked inside `poll()` most of the time**, not spinning. No CPU burn on either side; near-instant delivery when events arrive (single-digit-millisecond latency in practice).

It's "polling" in the API shape — your code calls `poll()` in a loop — but mechanically it's much closer to a long-lived push channel. You can verify this empirically: `docker stats analytics-consumer` on an idle cluster shows near-zero CPU.

Why pull-based instead of true push? Two reasons Kafka chose this:

- **Consumers control their pace.** A slow consumer just polls less frequently; the broker doesn't have to buffer indefinitely or drop records.
- **Replay falls out naturally.** "Reset offset and re-poll" works because reads are consumer-driven; with pure push, the broker would need to track and re-send.

One related runtime detail: while a consumer is between or inside `poll()` calls, a **background heartbeat thread** in the Kafka client sends periodic heartbeats to the group coordinator (default every 3 seconds). This is how the broker knows a consumer is still alive. If heartbeats stop — process died, or `poll()` was blocked longer than `max.poll.interval.ms` (default 5 min, e.g., a single record's processing took too long) — the broker concludes the consumer is dead and rebalances its partitions to the surviving members of the group. For our consumers (small per-record work), this is healthy by default.

### Why audit uses manual commit and analytics doesn't

Different durability requirements call for different commit strategies.

- **Analytics** counts events in memory. State is regeneratable from a replay. Auto-commit is fine — if a crash drops a few events, no big deal; on restart we recount from a slightly earlier offset.
- **Audit** writes durable rows to Postgres. We *cannot* lose an event. The consumer commits offsets manually, ONLY after the batch's INSERTs succeed. If the consumer crashes mid-batch, the next start re-reads the un-committed records. `ON CONFLICT (event_id) DO NOTHING` makes the re-insert idempotent.

This is the practical version of the at-least-once + idempotent-handler pattern. Same Kafka primitive, different consumer policies depending on what the downstream sink can tolerate.

### Replay is a Kafka superpower (and a footgun)

The "reset offset to 0" demo above is something Kafka can do that Redis queues cannot. Events live in the topic until retention expires; consumers can replay at any time.

When this is great:

- **Bug fix in a consumer.** Deploy fix, reset offset, replay. No re-runs from the producer.
- **New consumer joins late.** It starts from `auto.offset.reset=earliest` and reads everything ever published.
- **Backfill.** Historical events drive a new system that didn't exist when they were originally produced.

When this is dangerous:

- **Side effects.** Replaying notification events would re-send the notifications. Replaying trending events would double-count scores. Real systems include idempotency keys, bundle a state reset with the offset reset, or replay with side effects disabled.
- **Order changes.** If you reset multiple consumers at different times, their relative ordering of side effects can differ.

In our setup: audit replay is safe (idempotent insert via `ON CONFLICT DO NOTHING`). Trending replay is unsafe (would double-score). Analytics replay is safe in the trivial sense (just a recount, no external effect). The three consumers illustrate the spectrum.

### The three sources of files "inside a container"

Worth being explicit about, since it affects how you reason about persistence (especially for stateful services like Kafka, Postgres, redis-queue).

When the process inside a container reads or writes a file, the bytes can come from one of **three different places** — and they behave differently when the container exits or is removed.

| Path inside container | Where the bytes live | What happens on container exit | What happens on `docker rm` |
|---|---|---|---|
| Files the **image baked in** (e.g., `/usr/bin/java`, `/etc/kafka/server.properties`) | Image layers (read-only, shared across all containers using the image) | Untouched | Untouched (image is a separate resource) |
| Files the process **wrote** that aren't in a volume mount (e.g., `/tmp/scratch.txt`) | Writable layer (per-container, on disk under `/var/lib/docker/overlay2/...`) | **Preserved on disk** | **Deleted** along with the container |
| Files at paths covered by a **volume mount** (e.g., `/var/lib/kafka/data/...`) | The volume (Docker-managed directory, independent of any container) | **Preserved entirely** | **Preserved** (volume is a separate resource); deleted only by `docker volume rm` or `docker compose down -v` |

Mental model shortcut:

- **Image-layer files**: live forever (until the image is removed). Shared across containers; immutable.
- **Writable-layer files**: ephemeral per container instance. Survive process exit and container stop, but die with the container.
- **Volume files**: durable. Survive container removal entirely; outlive any one container's lifecycle.

This is why every stateful service in our compose has a volume mount for its data dir (Postgres → `pg-shard-N-data`, redis-queue → `redis-queue-data`, Kafka → `kafka-data`), while stateless services (counters, nginx, consumer roles, kafka-init, worker, redis-cache) don't.

### Could we consolidate `kafka-init` into the broker?

A reasonable design instinct: instead of a separate `kafka-init` container, just have the Kafka broker create the topic on startup. One fewer service.

It's a real choice. Trade-offs:

**Pros of consolidating**: Fewer services (14 vs 15). One health check covers both broker liveness and topic readiness. Cleaner `docker ps -a` (no exited init container).

**Cons of consolidating**: Couples broker lifecycle to topic-management lifecycle (restart broker → re-run topic creation, idempotent but extra). Single-responsibility violation. Custom startup script you maintain (race conditions, signal forwarding). Adding more topics later means editing this script and restarting the broker. Worse for multi-broker production (you want topic creation to happen once, not N times). Doesn't map to Kubernetes' first-class `initContainers` pattern.

**What real teams do**: Small dev environments sometimes consolidate. Production setups almost always **separate** — Strimzi/Confluent operators on K8s use init containers or Jobs as the standard pattern, and topic management often gets delegated to a separate service (GitOps repo of topic definitions).

**What we use and why**: separate `kafka-init` container. The init pattern is a real production pattern; showing it explicitly makes the concept visible. The Kafka container's job is brokering; the kafka-init container's job is topic management. Maps cleanly to Kubernetes.

For a tiny demo it's a wash. For anything that grows, the separated pattern is the production-correct choice.

### What didn't change

The counter's API is unchanged. DAOs unchanged. Sharding unchanged. Cache layer unchanged. Notification worker unchanged. The compile-time delta on top of challenge 14:

- One config block added (`kafka`).
- One library added on the producer side (`EventPublisher`).
- One method call added in `CounterHelper.vote()` (publish to Kafka).
- Three new consumer libraries.
- Three new cases in the `main()` dispatcher.

This is the fourth time in this repo we've added or swapped a stateful infrastructure component without rippling through the application layer (SQLite → Postgres in 10, in-process cache → Redis in 11, task queue added in 14, Kafka added here). **Clean abstractions are what allow these to be local changes.**
