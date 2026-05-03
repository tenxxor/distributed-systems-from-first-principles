# Challenge 16 — Timeouts and Circuit Breakers

## Problem

Up through challenge 15, our system has assumed dependencies are reachable. They aren't always, and the consequences are worse than they look.

Three failure modes are real and untested in our setup:

- **A Postgres shard goes down.** With default JDBC config, calls to that shard hang on TCP reads — potentially forever. Each hung call holds a Jersey request thread *and* a Jdbi connection from the per-shard pool. At any non-trivial traffic rate to the dead shard, this exhausts the connection pool first (10 slots) and then — once requests start piling up waiting on `maxWaitForConnection` — the Jersey thread pool too. New requests queue, **including those that would succeed against healthy shards**. Sharding gave us independent failure at the data layer; without per-shard fault handling, that independence doesn't extend to the request layer. The failure is gradual and load-proportional: low-traffic systems may never saturate; high-traffic ones can saturate in seconds.

- **A dependency is slow, not dead.** redis-cache stalls during a GC pause. Without a tight client timeout, every cache call blocks 5+ seconds. The counter's request latency goes from 50ms to 5s+ for every vote — the cache is now actively *hurting* us instead of helping.

- **A dependency comes back.** When a paused shard resumes, we want the system to start using it again — automatically, not after some operator flips a switch.

The pattern that addresses all three is **timeouts + circuit breakers**, applied per-dependency:

- Timeouts bound the damage when a single call is slow.
- Circuit breakers stop hammering a known-broken dependency, fail fast, and probe for recovery.
- When applied **per shard** (rather than globally per dependency type), they preserve the independence that sharding gave us.

This challenge teaches: **use the structure your system already has.** Shards are independent failure domains, so the breakers are independent. The Postgres failure of one shard becomes invisible to traffic on the others.

## Product

Same 15 containers as challenge 15. The work happens entirely inside the counter and audit-consumer code — config changes, a library addition, and ~150 lines of new Java.

What appears at runtime that wasn't there before:

- 3 Postgres shards (unchanged)
- 1 redis-cache (unchanged)
- 1 redis-queue (unchanged)
- 1 kafka, 1 kafka-init (unchanged)
- 3 counters (now wrap every outbound call in a per-dependency circuit breaker)
- 1 worker (unchanged)
- 1 analytics-consumer (unchanged)
- 1 audit-consumer (now wraps its Postgres INSERT in a circuit breaker; stops committing offsets while the breaker is open)
- 1 trending-consumer (unchanged)
- 1 nginx

The only externally-visible surface change is a new endpoint on the **admin port**:

- `GET /breakers` (port 8081, reachable per-counter at `localhost:18081/breakers`, `:28081/breakers`, `:38081/breakers`) — JSON dump of every breaker's state on this counter instance.

The application port (8080) is **unchanged** — it stays internal-only, only reachable through the LB on `localhost:9000`. Breaker state belongs on the admin port for the same reason `/healthcheck` and `/metrics` do: it's operational diagnostic data for operators, not a customer-facing API. In production the admin port is firewalled to internal networks.

Driving load against a *specific* counter (which the chaos demo needs, to trip *its* breaker) uses `docker exec counter-N curl http://localhost:8080/...` — same pattern as `kubectl exec` for poking a single pod in production.

## Programming

### Run-time — What's Actually Happening

Same topology as challenge 15. The behavioral difference is in **how failures propagate**.

Pre-challenge-16: a paused shard stalls request threads cluster-wide. Three counter pods × N stuck requests each → request capacity vanishes for everyone.

Post-challenge-16: paused-shard impact stays inside the affected shard's blast radius. Counters whose `counterId` hashes elsewhere are unaffected. The breaker absorbs the failure within the first ~5 calls and then fails fast.

```
┌─ counter-1 process ──────────────────────────────────────┐
│                                                           │
│  request: PUT /counters/golf/vote                         │
│       │                                                   │
│       ▼                                                   │
│  ShardRouter.shardIndexFor("golf") → 1                    │
│       │                                                   │
│       ▼                                                   │
│  ┌─ shard-1 breaker ──────────────────────────────────┐  │
│  │                                                     │  │
│  │  state == CLOSED  →  forward call to Postgres       │  │
│  │  state == OPEN    →  throw CallNotPermittedException│  │
│  │  state == HALF_OPEN → try a few; close or re-open   │  │
│  │                                                     │  │
│  └─────────────────────────────────────────────────────┘  │
│       │                                                   │
│       ▼                                                   │
│  postgres-shard-1                                         │
│                                                           │
│  Other breakers in this counter (independent state):      │
│    shard-0, shard-2, cache, kafka-publish                 │
│                                                           │
└───────────────────────────────────────────────────────────┘
```

The state machine for one breaker:

```
              ┌──────────────────────┐
              │       CLOSED         │
              │  (normal operation)  │
              └──────────┬───────────┘
                         │ failure rate >50%
                         │ over last 20 calls
                         ▼
              ┌──────────────────────┐
              │        OPEN          │
              │ (fail fast, no calls)│
              └──────────┬───────────┘
                         │ wait 10s
                         ▼
              ┌──────────────────────┐
              │     HALF_OPEN        │
              │ (probe with 2 calls) │
              └────┬─────────────┬───┘
              all probes      any probe
              succeed ↓        fails ↓
        (back to CLOSED)   (back to OPEN)
```

#### Data

| Data | Where it lives | Notes |
|---|---|---|
| Counter rows, user votes | Postgres shards | Unchanged |
| Cache entries | redis-cache | Unchanged |
| Notification tasks | redis-queue + AOF | Unchanged |
| Vote-cast events | Kafka topic + `kafka-data` volume | Unchanged |
| Audit log | `audit.audit_log` on shard-0 | Unchanged |
| Trending sorted set | redis-cache `trending:counters` | Unchanged |
| **Breaker state** (new) | In-memory inside each counter process | One CircuitBreaker object per dependency. Per-instance — no coordination across counters. Lost on restart, rebuilt as the breakers observe traffic again. |

A new architecturally interesting piece of state: **the breaker's sliding window**. Each breaker keeps a count-based ring of the last 20 call outcomes (success / failure / slow-success). When the failure-or-slow rate over that window exceeds 50% with at least 5 calls observed, the breaker flips to OPEN. The window resets on state transitions.

This window is *per breaker, per counter instance*. counter-1's view of shard-1 health is independent of counter-2's view. With three counters and five breakers each, there are 15 sliding windows in flight across the cluster.

#### Process

No new processes. The counter and audit-consumer processes from challenge 15 gain in-process libraries (Resilience4j) — same JVM, same threads, same containers.

What *is* new is the failure-path behavior:

- **Counter request thread** on a healthy path → still goes JDBC → Postgres → response, ~50ms.
- **Counter request thread** when shard-N is paused, breaker CLOSED → goes JDBC → blocks 2s (socketTimeout) → SQLException → 500 to client, breaker counts a failure.
- **Counter request thread** when shard-N is paused, breaker OPEN → throws `CallNotPermittedException` immediately (<10ms), Jersey mapper converts to 503.
- **Audit consumer thread** when shard-0 is paused, breaker OPEN → skips the batch, **does not commit Kafka offset**, polls again on next iteration. Lag grows in `__consumer_offsets`; drains when shard-0 recovers.

#### Infrastructure

- **15 containers**: same as challenge 15. No new services.
- **5 volumes**: same as challenge 15.
- **4 published host ports** (unchanged from ch15): 9000 (LB) + 18081/28081/38081 (counter admin ports). Application ports stay internal; per-counter targeting in the demo uses `docker exec`.
- **Internal-only**: Kafka, both Redises, all 3 Postgres shards.

### Compile-Time — How to Implement It

Two new files, one new dependency, and small surgical edits to existing files. The architecture is unchanged — we add fault-handling around the seams that were already there.

#### Failure policy: what each breaker does when it opens

The same primitive (CircuitBreaker) wraps every outbound call, but **what we do when a breaker opens differs per dependency**. The decision rule:

> **Does this dependency's failure block correctness?**
> - **Yes** → *fail fast.* Return an error to the caller.
> - **No** → *degrade.* Skip the dependency and produce a usable (if diminished) response.

Concretely, every breaker we add falls into one of these policies:

| Breaker | Policy on OPEN | Why | What the user/system sees |
|---|---|---|---|
| **shard-0 / shard-1 / shard-2** (CounterHelper writes & reads) | **Fail fast.** Throw `CallNotPermittedException` → mapper → 503 with `Retry-After: 10`. | Postgres is the source of truth. There's no fallback that gives a correct answer; making one up would corrupt data when the shard returns. | Client gets a clear 503 in <10ms. Other shards keep serving 200s. |
| **cache** (CounterHelper reads) | **Skip-degrade.** Catch `CallNotPermittedException` locally; fall through to read directly from Postgres. | The cache is a *feature* (latency), not correctness. Skipping it gives the right answer, just slower. | Client gets 200, ~10-30ms slower than a cache hit until the breaker recovers. No errors. |
| **cache** (CounterHelper invalidations / write-side) | **Drop-degrade.** Catch the exception and log; never fail a write because the cache is sad. | Stale cache entries on a re-fill is recoverable; failing the user's write is not. | Client gets 200 even with cache down. Cache may serve briefly stale data for the next TTL. |
| **kafka-publish** (CounterHelper, after-vote fan-out) | **Drop-degrade.** Log the dropped event; continue. | The vote already succeeded in Postgres. Kafka events drive fan-out (analytics, audit, trending) — they're best-effort downstream views, not source of truth. | Client gets 200. Three downstream consumers miss this event for the duration of the outage. |
| **audit-db** (AuditConsumer's Postgres INSERT) | **Pause-and-retry.** Don't commit the Kafka offset; re-poll. The consumer loops without advancing. | Audit is durable — we'd rather wait than drop. Lag grows in `__consumer_offsets`; it drains when shard-0 returns. `ON CONFLICT (event_id) DO NOTHING` keeps replay idempotent. | Audit table is briefly behind Kafka. No events lost; lag visible in `kafka-consumer-groups`. |

Three different shapes of "what to do on OPEN" — all valid, all from the same primitive:

- **Fail fast** = "the answer would be wrong; refuse explicitly." For correctness-path dependencies.
- **Skip-degrade** = "compute the answer without this dependency's contribution." For features (cache).
- **Drop-degrade** = "fire-and-forget the part that uses this dependency." For best-effort fan-out (Kafka publish).
- **Pause-and-retry** = "stop progressing my consumer's offset; come back when the dependency is healthy." For durable consumers where lag is preferable to loss (audit).

The choice for each breaker isn't about the breaker itself; it's about **what the dependency contributes to the request's correctness**. The breaker just provides the OPEN signal; the surrounding code decides the response.

#### The model: `BreakerRegistry` (new)

Holds one CircuitBreaker per dependency, with three different config profiles tuned to that dependency's expected latency:

```java
private static final CircuitBreakerConfig SHARD_CONFIG = CircuitBreakerConfig.custom()
        .failureRateThreshold(50.0f)
        .slowCallRateThreshold(50.0f)
        .slowCallDurationThreshold(Duration.ofSeconds(2))   // matches socketTimeout
        .waitDurationInOpenState(Duration.ofSeconds(10))
        .permittedNumberOfCallsInHalfOpenState(2)
        .minimumNumberOfCalls(5)
        .slidingWindowSize(20)
        .recordExceptions(Exception.class)
        .build();
```

Three accessors: `forShard(int)`, `forCache()`, `forKafkaPublish()`. Plus `snapshot()` returning a `Map<String, String>` of breaker name → state for the admin endpoint. One registry per counter container, constructed at startup with `new BreakerRegistry(shardCount)`.

#### The model: `config.yml` (updated)

Each shard's JDBC URL gets `socketTimeout=2&connectTimeout=2`:

```yaml
shardDbs:
  - url: jdbc:postgresql://${POSTGRES_SHARD_0_HOST}:5432/counters?socketTimeout=2&connectTimeout=2
    # ... rest unchanged ...
```

`socketTimeout=2` (seconds) bounds how long any I/O on a connection can wait. Without this, a paused shard's TCP connection silently hangs. With it, the call surfaces a SQLException within 2s — which the breaker can observe.

#### The model: `docker-compose.yml` (updated)

Two small changes. No new services. No new published ports.

1. **Image tag bump**: `counter:15` → `counter:16`.
2. **AUDIT_DB_URL gains the same timeouts**: `?socketTimeout=2&connectTimeout=2`.

#### The library: `BreakerResource` (new)

Tiny new servlet registered on the Dropwizard **admin connector** at `/breakers`:

```java
environment.admin()
    .addServlet("breakers", new BreakerResource(breakers, mapper))
    .addMapping("/breakers");
```

Returns the registry's snapshot:

```json
{
  "shard-0": "CLOSED",
  "shard-1": "OPEN",
  "shard-2": "CLOSED",
  "cache": "CLOSED",
  "kafka-publish": "CLOSED"
}
```

Reachable per-counter at `localhost:18081/breakers`, `:28081/breakers`, `:38081/breakers`. The admin port is the right home — same as `/healthcheck` and `/metrics`. It's a `HttpServlet` rather than a Jersey `@Path` resource because admin-port endpoints don't go through Jersey; they're plain servlets registered on the admin Jetty connector.

In production this would feed Prometheus → Grafana → alerts. Here it's an inspection endpoint for the demo.

#### The library: `CircuitBreakerExceptionMapper` (new)

A Jersey `ExceptionMapper<CallNotPermittedException>`. Translates breaker-open into a structured 503:

```java
return Response.status(503)
        .header("Retry-After", "10")
        .entity(Map.of(
                "error", "service_temporarily_unavailable",
                "breaker", breakerName))
        .build();
```

`Retry-After: 10` matches the breaker's `waitDurationInOpenState`. Clients (or LBs) can use both fields to back off intelligently.

#### The library: `ShardRouter` (updated)

Gains a `BreakerRegistry` constructor parameter and two new helpers that wrap each shard's JDBI access in its breaker:

```java
public <T> T onCounterShard(String counterId, Function<Jdbi, T> work) {
    int idx = shardIndexFor(counterId);
    return onShardIndex(idx, work);
}

public <T> T onShardIndex(int shardIndex, Function<Jdbi, T> work) {
    CircuitBreaker breaker = breakers.forShard(shardIndex);
    Jdbi jdbi = shardJdbis.get(shardIndex);
    return breaker.executeSupplier(() -> work.apply(jdbi));
}
```

Every JDBI access in `CounterHelper` is now routed through one of these. The breaker is implicit at the call site — callers don't think about it. If the breaker is OPEN, `executeSupplier` throws `CallNotPermittedException` (which the exception mapper turns into 503).

#### The library: `CounterHelper` (updated)

Wires up all three counter-side policies from the *Failure policy* table above. Each call site picks the right one for its dependency:

- **Postgres shards** — every read/write goes through `router.onCounterShard(...)`, so a breaker-open trip bubbles up unchanged → exception mapper → 503. No local catch; correctness can't be faked.
- **Cache reads** — wrapped in `breakers.forCache().executeSupplier(...)` with a `try/catch (CallNotPermittedException)` that falls through to a direct DB read inside the same shard call. The fallback path *is* the degraded mode:

```java
private CounterEntity readCounterEntity(String counterId) {
    return router.onCounterShard(counterId, jdbi -> {
        try {
            return breakers.forCache().executeSupplier(() ->
                    cache.get(counterKey(counterId), COUNTER_TYPE, COUNTER_TTL,
                            () -> jdbi.withHandle(h -> h.attach(CountersDAO.class).get(counterId).orElse(null))));
        } catch (CallNotPermittedException e) {
            log.info("cache.breaker.open path=counter counterId={}", counterId);
            return jdbi.withHandle(h -> h.attach(CountersDAO.class).get(counterId).orElse(null));
        }
    });
}
```

- **Cache invalidations** — same breaker, but the catch branch just logs and returns. A dead cache must never fail a write.
- **Kafka publish** — wrapped in `breakers.forKafkaPublish().executeRunnable(...)` with a catch that logs and returns. The vote already succeeded; the fan-out is best-effort.

#### The library: `EventPublisher` (updated)

Two changes: tight timeouts, and **block on the future** so the breaker can observe outcome.

```java
props.put("delivery.timeout.ms", "5000");   // total time for a publish
props.put("request.timeout.ms", "3000");
props.put("max.block.ms", "3000");          // bound .send() metadata block

// publishVoteCast() now blocks up to ~6s on the future
RecordMetadata metadata = producer.send(record).get(6, TimeUnit.SECONDS);
```

A circuit breaker can only count successes/failures it sees. If `send()` returns instantly without observing the outcome, the breaker has nothing to measure. We bound the wait at 6s so a dead Kafka still doesn't tie up the request thread indefinitely; but we *do* wait long enough to know whether the publish succeeded.

#### The library: `AuditConsumer` (updated)

Adds a local CircuitBreaker around the INSERT batch. The semantics differ from the counter side: instead of returning 503 to a caller, the consumer **stops committing offsets** when its breaker is open.

```java
boolean batchOk = true;
for (ConsumerRecord<String, String> record : records) {
    try {
        auditDbBreaker.executeRunnable(() -> /* INSERT */);
    } catch (CallNotPermittedException cnpe) {
        log.info("audit-db.breaker.open ... — will retry on next poll");
        batchOk = false;
        break;
    }
}
if (batchOk) {
    consumer.commitSync();
}
```

When shard-0 dies, the audit consumer's offset stays where it was. Lag grows in Kafka; events accumulate. When shard-0 recovers, the breaker closes, the consumer drains the backlog, the offset advances. **No events lost** — Kafka retention covers the gap. Same Kafka primitive as challenge 15's manual-commit policy, now with explicit fault handling.

#### The library: `CounterApplication` (updated)

Two added lines: construct the `BreakerRegistry`, register the new resource and exception mapper.

```java
BreakerRegistry breakers = new BreakerRegistry(shardJdbis.size());
ShardRouter shardRouter = new ShardRouter(shardJdbis, breakers);
CounterHelper helper = new CounterHelper(shardRouter, cache, queue, publisher, breakers);

environment.jersey().register(new CircuitBreakerExceptionMapper());
environment.jersey().register(new BreakerResource(breakers));
```

#### New dependencies: `pom.xml`

One addition:

```xml
<dependency>
    <groupId>io.github.resilience4j</groupId>
    <artifactId>resilience4j-circuitbreaker</artifactId>
    <version>2.2.0</version>
</dependency>
```

Resilience4j is mature, pure-Java, no required external state, no Spring dependency. Used directly via the `CircuitBreaker` API; no annotations, no proxies.

## Run It

```bash
cd challenge-16-counter-server-process
mvn -q -DskipTests package
docker compose up --build -d
sleep 15
```

15 containers come up. Counter app ports stay internal-only (production-correct). Each counter's admin port is published to the host (18081/28081/38081) so we can read per-counter breaker state with curl. To send load to a *specific* counter (which the demo needs to reliably trip *one* counter's breaker), we use `docker exec` to reach inside that counter's container.

### Sanity check

```bash
# counter-1's breaker state — via its admin port.
curl -s http://localhost:18081/breakers
# {"shard-0":"CLOSED","shard-1":"CLOSED","shard-2":"CLOSED","cache":"CLOSED","kafka-publish":"CLOSED"}
```

### Find counters on different shards

The hash routing means we don't choose; we discover. Create a few via the LB and check the log lines:

```bash
for c in alpha bravo charlie delta echo foxtrot golf hotel india juliet; do
  curl -s -X POST http://localhost:9000/api/v1/counters \
    -H "Content-Type: application/json" -d "{\"counterId\":\"$c\"}" -o /dev/null
done

{ docker logs counter-1 2>&1; docker logs counter-2 2>&1; docker logs counter-3 2>&1; } \
  | grep -oE "counterId=[a-z]+ shardIdx=[0-9]" | sort -u
```

In our test run: `alpha=2, charlie=0, golf=1`. Pick one per shard.

### Headline demo: kill a shard, watch the breaker isolate the failure

**Baseline — all shards healthy** (load via counter-1's app port, reached through `docker exec`):

```bash
for c in charlie golf alpha; do
  docker exec counter-1 curl -s -o /dev/null -w "$c: %{http_code} (%{time_total}s)\n" \
    -X PUT http://localhost:8080/api/v1/counters/$c/vote \
    -H "Content-Type: application/json" -H "X-User-Id: u-baseline" \
    -d '{"vote":"LIKE"}'
done
# All 200, ~50-80ms each.
```

**Pause shard-1:**

```bash
docker pause postgres-shard-1
```

**Drive enough requests through counter-1 against `golf` (shard-1) to trip its local breaker:**

```bash
for i in $(seq 1 10); do
  docker exec counter-1 curl -s -o /dev/null -w "vote $i: %{http_code} (%{time_total}s)\n" \
    -X PUT http://localhost:8080/api/v1/counters/golf/vote \
    -H "Content-Type: application/json" -H "X-User-Id: paused-$i" \
    -d '{"vote":"LIKE"}'
done
```

Verified output from a real run:

```
vote 1: 500 (2.045s)   ← JDBC socket timeout, breaker still CLOSED, counts a failure
vote 2: 500 (0.002s)   ← pool failure, fast
vote 3: 500 (2.014s)
vote 4: 503 (0.004s)   ← breaker now OPEN, fast 503
vote 5: 503 (0.002s)
vote 6: 503 (0.002s)
vote 7: 503 (0.002s)
vote 8: 503 (0.002s)
vote 9: 503 (0.002s)
vote 10: 503 (0.002s)
```

The transition from 500-with-timeout to 503-fast-fail is the breaker doing its job.

```bash
curl -s http://localhost:18081/breakers
# {"shard-0":"CLOSED","shard-1":"OPEN","shard-2":"CLOSED","cache":"CLOSED","kafka-publish":"CLOSED"}
```

**The critical part — other shards still serve normally on counter-1:**

```bash
for c in charlie alpha; do
  docker exec counter-1 curl -s -o /dev/null -w "$c: %{http_code} (%{time_total}s)\n" \
    -X PUT http://localhost:8080/api/v1/counters/$c/vote \
    -H "Content-Type: application/json" -H "X-User-Id: u-parallel" \
    -d '{"vote":"LIKE"}'
done
# All 200, ~30-70ms.
```

This is the lesson. shard-1's breaker is OPEN, but counter-1's other breakers (shard-0, shard-2) are unaffected. Independent failure domains, preserved by per-shard breakers.

**Recover:**

```bash
docker unpause postgres-shard-1
sleep 12

for i in 1 2 3; do
  docker exec counter-1 curl -s -o /dev/null -w "vote $i: %{http_code} (%{time_total}s)\n" \
    -X PUT http://localhost:8080/api/v1/counters/golf/vote \
    -H "Content-Type: application/json" -H "X-User-Id: u-recover-$i" \
    -d '{"vote":"LIKE"}'
done
# 200 OK, ~10-30ms. Recovery automatic.

curl -s http://localhost:18081/breakers
# All CLOSED again.
```

The breaker transitions OPEN → HALF_OPEN (after 10s) → CLOSED (after the probe calls succeed). No human intervention.

### Secondary demo: pause the cache, watch graceful degradation

```bash
docker pause redis-cache

for i in $(seq 1 10); do
  docker exec counter-1 curl -s -o /dev/null -w "vote $i: %{http_code} (%{time_total}s)\n" \
    -X PUT http://localhost:8080/api/v1/counters/alpha/vote \
    -H "Content-Type: application/json" -H "X-User-Id: cache-test-$i" \
    -d '{"vote":"LIKE"}'
done
```

Verified output from a real run:

```
vote 1: 200 (1.072s)   ← Lettuce timeout, breaker still CLOSED
vote 2: 200 (1.018s)
vote 3: 200 (1.032s)
vote 4: 200 (1.019s)
vote 5: 200 (1.019s)
vote 6: 200 (0.012s)   ← cache breaker now OPEN; bypass cache, hit Postgres directly
vote 7: 200 (0.010s)
vote 8: 200 (0.009s)
vote 9: 200 (0.010s)
vote 10: 200 (0.009s)
```

```bash
curl -s http://localhost:18081/breakers
# {"shard-0":"CLOSED","shard-1":"CLOSED","shard-2":"CLOSED","cache":"OPEN","kafka-publish":"CLOSED"}
```

**All 10 votes succeed.** The first ~5 are slow because each request makes 2 cache calls × 500ms timeout each ≈ 1s. After enough failures the cache breaker opens, subsequent calls skip the cache entirely and read straight from Postgres → 9-12ms.

**Recover:**

```bash
docker unpause redis-cache
sleep 12

# Need a couple of probe requests to drive HALF_OPEN → CLOSED.
for i in 1 2 3 4 5; do
  docker exec counter-1 curl -s -o /dev/null \
    -X PUT http://localhost:8080/api/v1/counters/alpha/vote \
    -H "Content-Type: application/json" -H "X-User-Id: u-cache-recover-$i" \
    -d '{"vote":"LIKE"}'
done

curl -s http://localhost:18081/breakers
# All CLOSED again.
```

### The contrast across the two demos

The same primitive (circuit breakers) gives two different *responses to breaker-open*:

| Dependency | Breaker open response | Why |
|---|---|---|
| Postgres shard | 503 to client, fast | Postgres is the source of truth; can't fabricate data |
| Cache | Bypass cache, read from Postgres | Cache is a feature; the system is correct without it |
| Kafka publish | Drop the publish, log it | Events are fan-out best-effort; vote already persisted |

The decision rule: **does this dependency's failure block correctness?** If yes, fail fast. If no, degrade.

### Stop everything

```bash
docker compose down       # keep volumes
docker compose down -v    # wipe everything
```

## What's Missing

1. **Single-instance breaker state.** Each counter has its own breaker registry; no coordination across counters. If counter-1 has tripped its shard-1 breaker, counter-2 might still be in CLOSED state and keep hammering shard-1 until enough failures pile up there too. Real production sometimes uses *distributed breakers* with state in Redis or a shared coordination service. We skip that — it adds complexity for marginal benefit at our scale, and is a topic that deserves its own lesson.

2. **No retry layer between the call and the breaker.** A single transient failure (one packet loss) goes straight to "failure," counts toward tripping the breaker. Resilience4j has a `RetryRegistry` that would absorb truly transient failures with exponential backoff + jitter before they trip the breaker. Production-grade systems usually layer both.

3. **No bulkheads (thread-pool isolation).** All outbound calls share Jersey's thread pool. A slow Postgres can starve cache calls and Kafka publishes that share that pool. A thoughtful production setup gives each downstream its own thread pool (a "bulkhead") so one slow dependency can't poison the others. Doubles the code volume; deferred.

4. **No API-level admission control.** When the system is overwhelmed (many breakers open, request queues growing), we don't reject *at the door* — every request still gets in and runs through the breakers. Real systems often add concurrency limiters (e.g., AIMD, Netflix `concurrency-limits`) at the LB or first servlet to shed load before it eats threads.

5. **No DLQ for the audit consumer.** A truly poison-pill event (un-deserializable, or fails INSERT for a non-transient reason like a constraint violation) is currently *skipped* (deserialization) or *retried forever* (DB failure). Real consumer pipelines route persistent per-record failures to a dead-letter topic for inspection.

6. **Fixed timeouts and breaker thresholds.** Production systems tune these by environment and load; some use adaptive thresholds. Our values are picked for a *demoable* trip rate, not real workloads.

7. **No chaos automation.** We use `docker pause` interactively. Real teams run chaos suites (Chaos Mesh, Gremlin, AWS FIS) on a schedule.

Carried forward: secrets in plaintext, scatter-gather LIST inefficiency at scale, primary-shard-as-SPOF for writes per shard, no schema registry for Kafka events, etc.

## Notes

### Why timeouts come *before* breakers

A circuit breaker without a timeout is mostly useless: the breaker can only react to "this call returned slowly" or "this call threw an exception." Without a tight timeout, a hung dependency silently holds the request thread forever — the call never returns, the breaker never observes anything.

Set the timeouts first. *Then* layer breakers on top. The breaker's "slow call threshold" should be set *near* the timeout — close enough that breaker-tripping kicks in around the same time as the timeout fires, so most failed calls count as failures rather than just slow successes.

In our setup: JDBC `socketTimeout=2s` matches `slowCallDurationThreshold=2s`. Lettuce 500ms matches the cache breaker's 500ms. Kafka 5s matches kafka-publish's 5s.

### The state machine, in detail

| State | What happens | Transition |
|---|---|---|
| **CLOSED** | All calls forwarded normally. Breaker counts results in a sliding window. | If failure rate (or slow-call rate) ≥ threshold over `minimumNumberOfCalls`, → OPEN. |
| **OPEN** | All calls fail fast with `CallNotPermittedException`. No actual call to the dependency. | After `waitDurationInOpenState` (10s here), → HALF_OPEN. |
| **HALF_OPEN** | Permits a small number of probe calls (`permittedNumberOfCallsInHalfOpenState`, 2 here). | If probes succeed, → CLOSED. If any probe fails, → OPEN. |

The trade-off knob is `waitDurationInOpenState`:

- Too short → as soon as you flip to HALF_OPEN, the still-broken dependency gets hammered again, the probes fail, you re-OPEN. Thrashing.
- Too long → the dependency recovered ages ago but you're still rejecting calls.

We use 10s for fast demos; production typically uses 30-60s.

### Why per-shard, not per-dependency-type

Imagine one global "Postgres breaker." When shard-1 fails, that breaker's failure counter rises. Past threshold, it trips OPEN. Now **all Postgres calls** — including healthy shard-0 and shard-2 — fast-fail. You've taken down 100% of write capacity because of a 33% failure.

Per-shard breakers preserve the failure domain that sharding established. The system already has independence at the *data* level (each shard is a different Postgres). Per-shard breakers give you that same independence at the *control* level (each shard has its own state machine).

The lesson generalizes: **breaker granularity should match failure-domain granularity.** Per-region. Per-AZ. Per-tenant. Whatever your structure already gives you.

### What "graceful degradation" actually means

Two cases in our demo:

- **Cache failure** → counter still serves; first few calls slow (timeout), then fast (skip cache).
- **Postgres failure** → counter rejects with 503; can't serve.

Both are "the system kept running" in some sense — but only the first is truly degradation. The decision rule:

> **Does this dependency provide a feature, or is it on the path of correctness?**

- Cache is a feature (it makes things fast). You can degrade away features.
- Postgres is correctness (it holds the canonical state). You cannot degrade away correctness.

A common production mistake: trying to "degrade" away correctness — serving stale or fabricated data when the source of truth is unreachable. That's not degradation; it's data corruption with extra steps. The right move when correctness fails is to fail fast and let the operator know.

### The auto-recovery is the second half of the value

Operators love circuit breakers because of the *failure* path: stop hammering a sick dependency. But the *recovery* path is equally important. With manual switches, every outage requires a human to flip the breaker back. At scale, you'd be flipping breakers all day.

The HALF_OPEN probe is what makes recovery automatic. The breaker takes a small risk — sends a few real calls — to discover whether the dependency is back. If yes, full traffic resumes. If no, back to OPEN for another `waitDurationInOpenState`.

This is the same pattern as TCP's slow-start, BGP's exponential dampening, and many other protocols that recover from transient failures: small-probe-then-resume.

### Why the Kafka publish became blocking

Challenge 15's `EventPublisher.publishVoteCast()` was async — fire-and-forget. The breaker would have observed nothing useful, because the call returned in microseconds regardless of whether Kafka was healthy. The breaker can't count successes/failures it doesn't see.

For the breaker to mean anything, the call has to *surface* the outcome. We changed `publishVoteCast()` to block on the future for up to 6s. Trade-off:

- Vote requests now wait up to 6s on a dead Kafka before returning success. Bad for latency.
- The kafka-publish breaker actually observes failures and trips. Good for the breaker.

The mitigation: the *breaker itself* limits how many slow calls happen. After the first ~5 slow ones, the breaker opens and subsequent calls fail fast (<10ms). So the worst case is "the first ~5 publishes after Kafka dies are slow; the rest are instant fast-fails."

A more sophisticated pattern: keep publish async, but use a *separate* mechanism (e.g., periodic health probes against Kafka) to drive the breaker state. Higher complexity; we picked the simpler path.

### Why audit-consumer responds to breaker-open differently than the counter

Both the counter and the audit-consumer write to Postgres. Both wrap the write in a breaker. But their responses to breaker-open are opposites:

- **Counter** → return 503 to the client. Fast fail. The user can retry.
- **Audit-consumer** → don't commit the Kafka offset. Block in the consumer loop. Drain the backlog when shard-0 recovers.

Different priorities driving the same primitive:

- The counter prioritizes **liveness** for the user. A request stuck waiting for a dead DB is worse than a fast 503.
- The audit-consumer prioritizes **completeness** for the audit trail. Skipping audit rows is unacceptable; lag is fine. Kafka retention means events sit on disk waiting to be consumed; we just hold the consumer's offset until shard-0 returns.

The `ON CONFLICT (event_id) DO NOTHING` clause makes this safe: if a partial batch was inserted before the breaker tripped, the re-read on next poll will see those event_ids already present and skip them. At-least-once + idempotent = effectively exactly-once for the audit table.

### Single-instance state (and why it's OK for now)

Each counter has its own breaker registry — no coordination across counters. This means:

- counter-1 trips its shard-1 breaker after 5 failures; counter-2 hasn't seen 5 failures yet, so it keeps hammering shard-1.
- Eventually counter-2 trips its breaker too. But there's a window where the cluster's view of "shard-1 is dead" is inconsistent.

Why this is acceptable here:

- Each counter only sees its own slice of traffic. As long as the breaker trips *before* the slice fills the request thread pool, that counter is protected.
- The total damage is bounded: each counter independently trips within its first ~5-10 failed calls.
- Distributed breaker state (e.g., Redis-backed shared counters) adds operational complexity and a new SPOF — the coordination store itself.

The bigger pattern in production: when you want truly cluster-wide breaker state, the right place for it is **the load balancer or service mesh** (which already has cluster visibility), not the application library. Linkerd/Istio implement breakers at the proxy level for exactly this reason.

### What this looks like with a service mesh

Resilience4j puts breakers, retries, timeouts in your application code. The popular alternative at scale is to put them in a **service mesh** sidecar:

- **Linkerd**, **Istio**, **Cilium** all sit beside your service as a Layer-4/7 proxy.
- They implement timeouts, retries, breakers, mTLS, observability — without your app code knowing.
- Configuration is declarative (CRDs in Kubernetes) and applies cluster-wide.

Trade-off: the mesh adds operational complexity (it's its own infra), and the proxy adds latency (~1ms per hop). In return, your app code stays clean and the platform handles cross-cutting concerns uniformly.

For demo and small services: in-process libraries like Resilience4j are simpler. For platforms running 100+ services in Kubernetes: a mesh almost always wins.

### A cautious word on metrics

We expose `/breakers` on the admin port for the demo. In real systems you'd ship the breaker's metrics (state, failure rate, slow-call rate) to Prometheus and alert on them. The most valuable single alert: **any breaker has been OPEN for more than 5 minutes.** That's a stuck-open situation worth a human eye.

Resilience4j has a Micrometer integration that emits the right metrics out of the box. We don't wire it here — the JSON endpoint is enough for our chaos demo — but in production it's a one-line addition.

### What didn't change

API surface, DAOs, sharding hash function, Kafka topology, all three Kafka consumer roles' core work, the notification worker. The compile-time delta on top of challenge 15:

- One config block (JDBC URL gains `socketTimeout`/`connectTimeout` query params).
- One library imports add (`io.github.resilience4j:resilience4j-circuitbreaker`).
- New `BreakerRegistry`, `BreakerResource`, `CircuitBreakerExceptionMapper` libraries.
- `ShardRouter` gains a registry parameter and `onCounterShard`/`onShardIndex` helpers.
- `CounterHelper` switches DB calls to go through the new helpers, wraps cache and Kafka publish with their own breakers.
- `EventPublisher` gets tighter timeouts and blocks on the future.
- `AuditConsumer` gets a local breaker around its INSERT batch.

Total: ~150 lines of net-new Java, plus config/URL changes. The architecture didn't change at all — we just added fault-handling around the seams that were already there.

This is the fifth time in this repo we've added a cross-cutting concern (durability in 10, caching in 11, sharding in 13, async dispatch in 14, fan-out events in 15) without touching the API or domain code. **Clean abstractions are what allow these to be local changes.**
