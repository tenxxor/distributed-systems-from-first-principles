# Challenge 11 — Distributed Cache with Redis

## Problem

Challenges 9, 9.5, and 10 all carry the same unfixed bug: **per-instance caches diverge.** Every counter container has its own in-memory Caffeine cache. When counter-1 processes a vote, it invalidates *its* cache entry. counter-2 and counter-3 still hold the stale value for up to TTL seconds. Three users hitting the same counter through nginx's round-robin can see three different answers.

The DB is consistent (all three counters write to one Postgres). The cache is not. Fixing this is what challenge 11 is for.

The architectural fix is the same move we made for storage in challenge 10, applied to caching: **per-process state → shared service.** Move the cache out of each JVM's heap and into a separate process — Redis — that all counters reach over the network. One source of truth for the cache, just like Postgres is the one source of truth for persistent data.

## Product

Five containers in compose:

- **3 counter containers** — same as challenge 10, except they no longer hold Caffeine caches. They open a connection to Redis at startup and use it as their cache backend.
- **1 nginx container** — unchanged.
- **1 Postgres container** — unchanged.
- **1 Redis container** (new) — runs `redis:7-alpine`, no persistent volume (cache is regenerable from Postgres).

The cache layer uses **cache-aside with graceful degradation**:
- On read: try Redis → on hit, return cached value; on miss or Redis failure, fall back to Postgres and write the result back.
- On write: invalidate the Redis key. Every other counter sees the miss on its next read and re-fetches from Postgres.
- If Redis is unreachable, requests succeed slightly slower (Redis call times out fast, fallback reads DB) — they don't fail.

## Programming

### Run-time — What's Actually Happening

![Three client processes outside the cluster reach the LB container on localhost:9000. Inside the cluster private network (bridge): the LB container, three counter containers (with admin ports :8081 published as host :18081/:28081/:38081 for external Prometheus scraping, plus per-container metric models — but no cache models, since caching moved out of process), a Redis container (no persistent volume; the cache is regenerable), and a Postgres container. Counter containers connect to BOTH Redis and Postgres over the cluster network. Only Postgres has file handles into the persistent storage volume mounted at /var/lib/postgresql/data, backed by host storage outside the Docker network.](./diagram.png)

```
┌─ host machine ────────────────────────────────────────────────────────┐
│                                                                       │
│   client ── http://<host>:9000                                        │
│                            │                                          │
│   ┌────────────────────────┼─────────── cluster private network ─┐    │
│   │                        ▼                                     │    │
│   │                  ┌──────────┐                                │    │
│   │                  │ nginx-lb │                                │    │
│   │                  └─────┬────┘                                │    │
│   │              ┌─────────┼─────────┐                           │    │
│   │              ▼         ▼         ▼                           │    │
│   │        ┌──────────┐┌──────────┐┌──────────┐                  │    │
│   │        │counter-1 ││counter-2 ││counter-3 │                  │    │
│   │        │  no      ││  no      ││  no      │                  │    │
│   │        │  in-proc ││  in-proc ││  in-proc │                  │    │
│   │        │  cache   ││  cache   ││  cache   │                  │    │
│   │        └────┬─────┘└────┬─────┘└────┬─────┘                  │    │
│   │             │           │           │                        │    │
│   │             │  cache calls (Redis protocol :6379)            │    │
│   │             │ ┌─────────┴───────────┘                        │    │
│   │             │ │                                              │    │
│   │             ▼ ▼                                              │    │
│   │        ┌─────────┐    SQL (JDBC :5432)                       │    │
│   │        │  redis  │      ┌───────────────┐                    │    │
│   │        │ (cache) │      │   postgres    │                    │    │
│   │        │ no vol. │      │  (truth)      │                    │    │
│   │        └─────────┘      └───────┬───────┘                    │    │
│   │                                 │                            │    │
│   └─────────────────────────────────┼────────────────────────────┘    │
│                                     ▼                                 │
│         ┌─────────────────────────────────────┐                       │
│         │  pg-data (named volume)             │                       │
│         │  Postgres's on-disk data directory  │                       │
│         └─────────────────────────────────────┘                       │
│                                                                       │
└───────────────────────────────────────────────────────────────────────┘
```

The notable shape: **counters now talk to two backends** — Postgres for persistent data, Redis for cache. Both are addressed by service name on the cluster network; both are internal-only (not published to the host).

#### Data

Same data shapes as challenge 10. What changed about *where* it lives:

| Data | Where it lives | Shared across instances? | Is this a problem? |
|---|---|---|---|
| Counter rows, user votes | Postgres (pg-data volume) | **Yes** — one source of truth | — |
| Cache entries (counter, vote) | **Redis (no volume)** | **Yes — fixed in this challenge** | No, was a bug, now resolved |
| Metric samples, log lines | Per-container | No | No — per-instance by design |

The `Caffeine cache entries` row from challenge 10's table is gone. Cache state moved out of the JVM and into Redis, where it's shared across all three counter instances.

The cache is **deliberately not persisted** (no Redis volume). The point: cache is an optimization, the DB is the truth. Losing the cache on a Redis restart is fine — the next read repopulates it from Postgres. This is in contrast to Postgres, where losing pg-data means losing user data.

#### Process

Six processes now (3 counters + nginx + Postgres + Redis), up from five in challenge 10. The shapes of the requests counters send out:

- **Counter → Postgres**: SQL over JDBC (unchanged).
- **Counter → Redis**: Redis wire protocol (RESP) over a TCP connection.

The Redis call shape, simplified:

```
counter → redis:  GET counter:video-funny-cats
redis   → counter: "{\"counterId\":\"video-funny-cats\",\"likes\":2,\"dislikes\":0,\"createdAt\":1776...}"
                   (or "(nil)" for a miss)
```

Or for invalidation on writes:

```
counter → redis:  DEL counter:video-funny-cats
redis   → counter: 1   (number of keys deleted)
```

Each call is a network round trip — much slower than the in-process Caffeine lookups we used to do (microseconds → ~0.5ms on local Docker), but still much faster than going to Postgres. The trade-off favors correctness (no divergence) over raw latency.

#### Infrastructure

- **Six containers** total: 1 nginx, 3 counters, 1 Postgres, 1 Redis.
- **Four published host ports**, unchanged from 10: 9000, 18081, 28081, 38081.
- **Redis is internal**, not published. Counters reach it via `redis:6379` on `cluster-net`.
- **No persistent volume for Redis.** A `docker compose restart redis` wipes the cache; subsequent reads repopulate it.
- **`pg-data` volume unchanged.**

### Compile-Time — How to Implement It

Two new things: a Lettuce-based Redis client and a thin cache wrapper.

#### The model: `config.yml` (updated)

```yaml
redis:
  url: redis://${REDIS_HOST:-redis}:6379
```

One block added. Hostname comes from an env var so the same image works in dev, CI, and any environment where Redis lives somewhere else.

#### The model: `docker-compose.yml` (updated)

- **New `redis` service** with image `redis:7-alpine` and a healthcheck (`redis-cli ping`).
- **Counters' `depends_on` now also waits for Redis to be healthy** in addition to Postgres.
- **No volume on Redis** — cache is regenerable.

#### The library: `RedisCache` (new)

A thin wrapper around Lettuce that implements cache-aside:

```java
public <T> T get(String key, TypeReference<T> type, Duration ttl, Supplier<T> loader) {
    String cached;
    try {
        cached = conn.sync().get(key);
    } catch (Exception e) {
        log.warn("redis.get.error key={} err={} — falling back to source", key, e.getMessage());
        return loader.get();   // Redis is down? Go straight to DB.
    }

    if (cached != null) {
        return mapper.readValue(cached, type);
    }

    T fresh = loader.get();   // Cache miss — load from DB.
    if (fresh != null) {
        conn.sync().set(key, mapper.writeValueAsString(fresh), SetArgs.Builder.px(ttl.toMillis()));
    }
    return fresh;
}
```

Key design point: **graceful degradation.** Any Redis failure (timeout, network error, deserialization error) falls back to the supplier (a DB read). The user-facing request still succeeds; it's just slower.

#### The library: `CounterApplication` (updated)

Wires up Lettuce's `RedisClient`, sets a 500ms command timeout (critical — otherwise Redis-down requests would hang for 60 seconds and trip the LB's request timeout), creates the `RedisCache`, and passes it into `CounterHelper`.

```java
RedisURI redisUri = RedisURI.create(configuration.getRedis().getUrl());
redisUri.setTimeout(Duration.ofMillis(500));
RedisClient redisClient = RedisClient.create(redisUri);
StatefulRedisConnection<String, String> redisConnection = redisClient.connect();
RedisCache cache = new RedisCache(redisConnection, environment.getObjectMapper());
CounterHelper helper = new CounterHelper(jdbi, cache);
```

#### The library: `CounterHelper` (updated)

- Caffeine cache fields removed; `RedisCache` injected via constructor.
- Cache lookup keys are now namespaced with prefixes (`counter:`, `vote:`) so the single Redis instance can hold multiple kinds of entries cleanly.
- Cache invalidation calls `cache.invalidate(key)` instead of `cache.invalidate(key)` on a Caffeine cache. Same shape, different backend — and now the invalidation reaches all three instances because they all read from the same Redis.

#### New dependencies: `pom.xml`

- Removed: `caffeine` (no more in-process cache).
- Added: `lettuce-core` (modern, async-capable Redis client for Java).

## Run It

```bash
cd challenge-11-counter-server-process
mvn -q -DskipTests package
docker compose up --build -d
```

### Round-robin still works

```bash
for i in 1 2 3 4 5 6; do curl -s http://localhost:9000/api/v1/counters > /dev/null; done
for c in counter-1 counter-2 counter-3; do
    echo -n "$c: "; docker compose logs $c | grep -c '"method":"GET"'
done
```

### Inspect the cache directly

```bash
docker compose exec redis redis-cli KEYS '*'
docker compose exec redis redis-cli GET counter:video-funny-cats
docker compose exec redis redis-cli TTL counter:video-funny-cats
```

You'll see JSON-encoded counter entries, with their TTLs ticking down (5 seconds for counters, 3 seconds for votes).

### The cache-divergence demo (now fixed)

This is the demo that made challenges 9 and 10 painful. It should now show **convergence**.

```bash
NET="challenge-11-counter-server-process_cluster-net"

# Warm each counter's cache (which all read from the SAME Redis)
for c in counter-1 counter-2 counter-3; do
    docker run --rm --network $NET curlimages/curl:latest -s \
        http://$c:8080/api/v1/counters/video-music-mix > /dev/null
done

# Vote on counter-1 only
docker run --rm --network $NET curlimages/curl:latest -s \
    -H "X-User-Id: zoe" -X PUT \
    http://counter-1:8080/api/v1/counters/video-music-mix/vote \
    -H "Content-Type: application/json" -d '{"vote":"LIKE"}'
echo

# Read from each
for c in counter-1 counter-2 counter-3; do
    echo -n "$c: "
    docker run --rm --network $NET curlimages/curl:latest -s \
        http://$c:8080/api/v1/counters/video-music-mix
    echo
done
```

All three return `likes=1`. The vote on counter-1 invalidated `counter:video-music-mix` in Redis, so counter-2 and counter-3 see the miss on their next read and refetch from Postgres. **The bug is gone.**

### Redis-down failure mode (graceful degradation)

```bash
docker stop redis
for i in 1 2 3; do
    curl -s -o /dev/null -w "HTTP %{http_code} time=%{time_total}s\n" \
        http://localhost:9000/api/v1/counters/video-funny-cats
done
docker start redis
```

You should see `HTTP 200` on all three, but with latency around **500ms** — that's the Lettuce command timeout. Each request waits up to 500ms for Redis, times out, falls back to Postgres, succeeds. The cache is an optimization; when it's broken, the system slows down but doesn't fail.

### Stop everything

```bash
docker compose down       # keep pg-data
docker compose down -v    # also wipe pg-data
```

## What's Missing

Three things this challenge does not address:

1. **Redis is now a new SPOF.** If Redis is down, every read pays the 500ms timeout penalty. We mitigate by falling back to the DB, but production setups run Redis in HA (sentinel, cluster mode, or managed service like Memorystore / ElastiCache).
2. **Cache stampede / thundering herd.** When a popular cache entry expires, every counter that wants it fetches from the DB simultaneously. With many instances and high traffic this can overload Postgres. Real solutions: single-flight (one instance fetches, others wait), probabilistic early refresh, or staggered TTLs.
3. **No circuit breaker.** Each Redis-down request still pays the 500ms timeout. A circuit breaker would short-circuit consecutive failures, returning DB-only after the first few timeouts, then probe Redis later to see if it's recovered.

Also still unaddressed from earlier challenges:

- **Postgres is a SPOF.** Same as challenge 10.
- **nginx is a SPOF.** Same as challenge 9.
- **Secrets in plaintext.** The Postgres password is still hardcoded.

## Notes

### Every distributed system is the same shape

There's a useful framing for systems work that crystallizes after going through challenges 10 and 11:

> **Every distributed system is a small number of stateless services talking to a few stateful services over the network.**

Once you internalize this, modern architecture is much less mysterious.

In our cluster, the **stateless services** are the three counter containers — they hold no durable state of their own; killing one and starting another loses nothing user-facing. The **stateful services** are Postgres (the source of truth) and Redis (the cache). The counters reason about their own work using whatever's in heap for that one request, then read/write the stateful services for anything that has to outlive the request.

This pattern repeats at every scale and in every stack:

| Stateless | Stateful |
|---|---|
| Web servers (Rails, Django, Express) | Postgres / MySQL, Redis |
| Microservices (Java, Go, Node) | DBs, Kafka, S3 |
| Serverless functions (Lambda, Cloud Run) | DynamoDB, SQS, S3 |
| Mobile app backends | The same DBs and caches |

Different deployment substrates, same architectural shape. The interesting design choices are about **which stateful services you pick** (relational vs document vs key-value vs streaming) and **how the stateless services interact with them** (cache patterns, transaction boundaries, retry policies). **The architecture itself is almost always "stateless compute, stateful storage, kept separate.**"

This is also why challenges 10 and 11 build on each other so cleanly. Each is the same architectural move — externalize state into a service — applied to a different concern. Once you've seen it twice, you'll see it everywhere.

### Cache as a service, the same pattern as DB as a service

We just made the same architectural move twice in a row:

| Challenge | Before | After | Pattern |
|---|---|---|---|
| 10 | Embedded SQLite (per-process file I/O) | Postgres server (network service) | Storage as service |
| 11 | In-process Caffeine (per-process map) | Redis server (network service) | Cache as service |

The pattern is general:

- **What was per-process** becomes a network service.
- **Coordination that used to happen via shared file or shared memory** moves into a process designed to be a shared service.
- **All app instances now route through one logical thing** (the service), so they automatically agree on state.

This generalizes further:

- **Search indexes as a service** — Elasticsearch.
- **Object storage as a service** — S3, MinIO.
- **Message queues as a service** — Kafka, RabbitMQ, SQS.
- **Configuration as a service** — Consul, etcd.

Each is a stateful network service that solves a coordination problem that used to be either impossible (across machines) or local-only (one machine, multiple processes).

### Cache-aside vs write-through vs read-through

We use **cache-aside** (also called "lazy loading"):
- App reads cache → on miss, reads DB → writes result back to cache.
- App writes DB → invalidates the cache key.

Two alternatives exist:

- **Read-through** — the cache itself fetches from the DB on miss. The app only knows about the cache. Less common in app code; common in cache libraries that wrap a DB driver.
- **Write-through** — every write goes to the cache first, which then writes to the DB. The app only writes to the cache; the cache handles persistence. Used in some specialized systems.

Cache-aside is the default for app-managed caching because it's simple, the cache stays a pure optimization layer, and failure modes are easy to reason about (cache down ⇒ go to DB).

### The four communication flows between counter, Redis, and Postgres

Cache-aside boils down to four distinct flows. Knowing each one cold makes the rest of the architecture click.

**Mental model in three rules:**

1. **The counter (application) is the orchestrator.** Redis and Postgres don't know about each other. The counter knows about both and decides who to call when.
2. **Redis is the fast path; Postgres is the truth.** Reads check Redis first. Writes go to Postgres first. The cache is *derived state* — allowed to be missing or stale; not allowed to be the only copy.
3. **Writes invalidate, don't update.** A delete in Redis is cheap and unambiguous. The next reader pays a miss but gets a fresh value from the truth.

#### Flow 1 — Read with cache HIT (the happy path)

```
client                                                      Postgres
  │ GET /counters/video-funny-cats                              │
  ▼                                                             │
┌───────────┐                                                   │
│  nginx    │                                                   │
└─────┬─────┘                                                   │
      │ proxy_pass to counter-N                                 │
      ▼                                                         │
┌───────────┐                ┌─────────┐                        │
│ counter-2 │  GET counter:..│  redis  │                        │
│           │ ──────────────▶│         │                        │
│           │  ◀─────────────│  hit    │                        │
└─────┬─────┘   JSON value   └─────────┘                        │
      │ deserialize, return                                     │
      ▼                                                         │
   200 OK                              (Postgres NOT touched)
```

Five steps: client → nginx → counter → Redis (hit) → counter → client. Postgres is not involved at all. Latency ~1-3ms.

#### Flow 2 — Read with cache MISS

```
client                                                      Postgres
  │ GET /counters/video-funny-cats                              │
  ▼                                                             │
┌───────────┐                                                   │
│  nginx    │                                                   │
└─────┬─────┘                                                   │
      ▼                                                         │
┌───────────┐                ┌─────────┐                        │
│ counter-2 │  GET counter:..│  redis  │                        │
│           │ ──────────────▶│         │                        │
│           │  ◀─────────────│  nil    │                        │
│           │   (miss)       └─────────┘                        │
│           │                                                   │
│           │  SELECT * FROM counters WHERE counter_id = '...'  │
│           │ ─────────────────────────────────────────────────▶│
│           │  ◀───────────────────────────────────────────────  │
│           │            row data                               │
│           │                                                   │
│           │  SET counter:.. "{...}" PX 5000                   │
│           │ ──────────────▶┌─────────┐                        │
│           │  ◀─────────────│   OK    │                        │
└─────┬─────┘                └─────────┘                        │
      ▼                                                         │
   200 OK
```

Six steps: counter asks Redis (miss), counter asks Postgres, Postgres returns the row, counter writes back to Redis with TTL, counter returns to client. **Three round trips.** Latency ~10-50ms.

The counter is the orchestrator — Redis doesn't call Postgres on its behalf. The application coordinates between cache and DB.

#### Flow 3 — Write (vote, create, delete)

```
client                                                      Postgres
  │ PUT /counters/video-funny-cats/vote                         │
  ▼                                                             │
┌───────────┐                                                   │
│  nginx    │                                                   │
└─────┬─────┘                                                   │
      ▼                                                         │
┌───────────┐                                                   │
│ counter-2 │  BEGIN; SELECT...; INSERT...; UPDATE...; COMMIT;  │
│           │ ─────────────────────────────────────────────────▶│
│           │  ◀───────────────────────────────────────────────  │
│           │              new row state                        │
│           │                                                   │
│           │  DEL counter:video-funny-cats                     │
│           │ ──────────────▶┌─────────┐                        │
│           │  ◀─────────────│  redis  │                        │
│           │                │   1     │                        │
│           │  DEL vote:..   │         │                        │
│           │ ──────────────▶│         │                        │
│           │  ◀─────────────│   1     │                        │
└─────┬─────┘                └─────────┘                        │
      ▼                                                         │
   200 OK                                                       │
```

Four steps: receive write, run transaction against Postgres, **invalidate** (DEL) cache keys in Redis, return to client.

**Invalidation, not update.** We delete the old cache entry rather than writing the new value. Why?

| Invalidate (what we do) | Update (alternative) |
|---|---|
| Next reader does a fresh DB read → guaranteed correct | Risk of writing stale data if writes race |
| One operation per cache key | Must serialize writes carefully |
| One slower read after each write | Always-fast reads, but bigger bug surface |
| Standard cache-aside practice | Sometimes called "write-through" |

**Critical for distributed correctness:** because all three counter instances share the same Redis, when counter-2 deletes the cache key, counter-1 and counter-3 see the deletion immediately. Their next reads pay the miss but get fresh data. **This is what fixes the divergence bug from challenge 10.**

#### Flow 4 — Read with Redis DOWN (graceful degradation)

```
client                                                      Postgres
  │ GET /counters/video-funny-cats                              │
  ▼                                                             │
┌───────────┐                                                   │
│  nginx    │                                                   │
└─────┬─────┘                                                   │
      ▼                                                         │
┌───────────┐                ┌─────────┐                        │
│ counter-2 │  GET counter:..│  redis  │  ✗ unreachable          │
│           │ ─────────────X │   ✗     │                        │
│           │  (timeout 500ms)                                  │
│           │                                                   │
│           │  log.warn("redis.get.error ... falling back")     │
│           │                                                   │
│           │  SELECT * FROM counters WHERE ...                 │
│           │ ─────────────────────────────────────────────────▶│
│           │  ◀───────────────────────────────────────────────  │
│           │              row data                             │
│           │                                                   │
│           │  (no SET back — Redis still down)                 │
└─────┬─────┘                                                   │
      ▼                                                         │
   200 OK (slow — 500ms timeout + DB query ≈ 510ms total)
```

Five steps: cache call times out at 500ms, exception caught, fall back to DB, return result. **Request still succeeds**, just slower. The application is *degraded, not broken*.

This is what cache-aside buys you — Redis is an optimization, not a dependency. With a circuit breaker (see "What's Missing"), prolonged outages would skip the 500ms timeout entirely after the first few failures.

#### Summary table

| Operation | Counter → Redis | Counter → Postgres | Order |
|---|---|---|---|
| Read (hit) | GET (returns JSON) | not called | — |
| Read (miss) | GET (nil), then SET | SELECT | Redis GET → PG SELECT → Redis SET |
| Write | DEL (invalidate) | full transaction | PG COMMIT → Redis DEL |
| Read (Redis down) | GET (times out) | SELECT (fallback) | Redis timeout → PG SELECT |

#### What's NOT happening in our setup

A few things you might *expect* but don't happen:

- **Redis doesn't call Postgres.** Read-through caching (where the cache itself fetches the underlying data on miss) is a different pattern; some libraries support it. Ours doesn't.
- **Postgres doesn't notify Redis on writes.** No DB triggers, no logical replication into Redis. The counter does all the coordination.
- **Writes don't go to Redis first.** Write-through caching (cache → cache writes to DB) is yet another pattern; we don't use it.
- **There's no two-phase commit between DB and cache.** If the DB write succeeds but the Redis invalidation fails, we have temporarily stale cache data — but only until TTL expiry. Acceptable trade; atomic DB+cache writes are much harder.

### Two race classes the cache layer can hit

Cache-aside has two distinct race conditions it has to deal with. They're not "race or no race" — both happen — they differ in size, recoverability, and what it takes to fix them. Comparing them side by side:

| Race | Where it happens | Window size | Self-heals on next write? | Extra complexity to fix? |
|---|---|---|---|---|
| **SET races SET** | Two concurrent writes both writing the new value to cache | Whole duration of both transactions | **No** — next write can re-stale (writes can keep losing to each other) | **Yes** — needs CAS, distributed locking, or single-writer |
| **Read-then-stale-write** | A concurrent read and write — reader misses the cache, fetches from DB, writes back its (now-stale) snapshot | Few ms (between cache miss and SET-back) | **Yes** — the next write invalidates and a fresh read populates the cache correctly | Versioning helps but is optional |

Both races exist. The second is smaller in window, less catastrophic in pattern, and self-recovers. That's why "**invalidate, accept the rare stale-write**" is the standard pattern, and "SET on every write" usually isn't.

### How single-writer architectures eliminate the SET-vs-SET race

If you have to update the cache on every write (instead of invalidating), one solution is to make sure **only one process is allowed to write to a given cache key** at a time. Let me unpack what that actually means.

In our current setup, **all three counter containers can write to Redis directly**. When two writes race, neither knows about the other. The cache write that arrives at Redis last wins, even if it's the stale value.

Single-writer architectures break this by introducing a coordination layer that ensures only one writer is active at a time per key. There are a few common shapes:

**Shape 1: Distributed lock around the write**

Before writing, the writer must acquire a Redis-side lock:

```
1. SET lock:counter:foo my-uuid NX EX 5     ← only succeeds if no one holds it
2. (run DB transaction)
3. SET counter:foo "new value"              ← only this writer is here right now
4. DEL lock:counter:foo
```

Other writers see the `NX` (not-exists) check fail and either wait or retry. Effectively serializes writes per-key. Works but **slow under contention** (everyone queues for the lock) and **buggy if the lock-holder crashes** mid-write (you need TTL on the lock so it auto-releases, but then you have to handle "the lock expired but I wasn't done yet" cases).

**Shape 2: Route writes for a given key to a designated owner**

Use **consistent hashing** on the key to pick which counter instance owns writes for that key. All vote requests for `video-funny-cats` get routed (by some routing layer) to, say, counter-2. Other counters can read but not write. Within counter-2, writes are serialized in order (single thread, or a per-key queue). Now the race can't even occur — there's literally one writer.

This is how systems like **Kafka** (single leader per partition) and **RocksDB block cache** work internally. Trade-offs:
- ✅ No locks needed; writes never race.
- ❌ Adds a routing layer (the LB has to know which counter owns which key).
- ❌ Failure of the owner means no writes for those keys until a new owner is elected.
- ❌ Requires consistent hashing or some equivalent partitioning scheme.

**Shape 3: Send writes through a single-threaded process**

All writes go through one queue or one process. This is what **Redis itself** does internally — Redis is single-threaded, so two `SET` commands to the same key always happen in arrival order at Redis, never simultaneously. (The race we're worried about is at the *application* layer — two clients computing different "new values" and racing to write them. Redis processes them serially, but the *content* of the writes is already wrong.)

If you put a single-threaded relay between your app and Redis, you can have it serialize the *computation* of the new value, not just the storage. That's what some systems do — but at that point you've reinvented a database.

**Shape 4: Atomic compare-and-set (CAS) at the cache layer**

Each cache entry has a version number. When writing, you say "set this value, but only if the version is still 5":

```lua
-- runs atomically inside Redis
local current_version = redis.call("HGET", KEYS[1], "version")
if current_version ~= ARGV[2] then
    return "stale"
end
redis.call("HSET", KEYS[1], "value", ARGV[1], "version", ARGV[3])
```

Writers who lose the race get "stale" back and either retry or give up. Doesn't make it impossible for writes to lose — it just makes losing **detectable**. This is what Redis's `WATCH` / `MULTI` / `EXEC` and Memcached's `gets` / `cas` give you.

**Why we don't bother**

For our challenge:
- Writes are rare (it's a like/dislike counter, not a high-throughput feed).
- Invalidation already avoids this race entirely — `DEL counter:foo` from two writers is just two no-ops in some order, both correct.
- Adding any of the above mechanisms would add complexity without showing a meaningful new architectural concept.

If we *had* to update on every write — say, for a write-through cache where misses are unacceptable — single-writer or CAS would be the standard fix. The pattern is real and shows up in production caches; we just don't need it because invalidation gives us correctness for free.

### Why JSON and not a binary format?

Cache entries are serialized as JSON via Jackson. This costs a little CPU and bandwidth per cache hit/miss vs a binary format like Protobuf or MessagePack.

Reasons we picked JSON:
- **Debuggable.** `redis-cli GET counter:foo` returns readable JSON, not opaque bytes. For a teaching repo this is huge — you can see exactly what's cached.
- **Schema-free.** Add a field to `CounterEntity`, deserialize old cached entries with `@JsonIgnoreProperties(ignoreUnknown = true)` already on Jackson's default. With Protobuf you'd need a versioning story.
- **Already a dependency.** Jackson ships with Dropwizard.

For a high-throughput cache where every byte matters, binary serialization wins. For a demo and most app-level caches, JSON is fine.

### Cache stampede — the problem we're not solving

Imagine 10,000 simultaneous reads for `counter:video-trending`. The cache entry expires. **All 10,000 requests** see a miss, all 10,000 hit Postgres, Postgres struggles, latency spikes, sometimes the DB falls over.

This is "cache stampede" or "thundering herd," and it's a real production failure mode at scale.

Solutions:
- **Single-flight** — only one request actually fetches; others wait for its result. Go's `singleflight` package does this; Java has `Caffeine`'s `loadingCache` which built it in.
- **Probabilistic early refresh** — refresh the cache *before* it expires, with probability that increases as expiry approaches. Spreads the refresh load.
- **Stale-while-revalidate** — serve stale data on miss while async-refreshing. Some latency tolerance for some staleness.

We don't implement any of these. At our scale (3 counters, demo traffic), it's not a real problem. At Twitter / TikTok / Instagram scale, you can't go to production without it.

### Connection model: one Redis connection per counter

Lettuce uses a single multiplexed connection by default. All threads on a counter share one TCP connection to Redis; Lettuce pipelines commands over it. This is very different from JDBC's connection pool model.

Why the difference?
- **Postgres** is a backend per connection — each connection forks a Postgres backend process. Connections are expensive; we pool them, ration them, share them across requests.
- **Redis** is single-threaded, with a binary protocol that supports pipelining. One connection can sustain very high throughput because the server processes commands sequentially anyway. Multiple connections gain you nothing.

So our config has `maxSize: 10` for the DB pool but no equivalent for Redis — there's a single connection, and it's enough.

### Why no persistence on Redis?

We use Redis purely as a cache. The DB is the source of truth; the cache is regenerable. Two arguments for not persisting:

1. **Pedagogical clarity.** "Cache is regenerable" is a clean story. If we persisted Redis, readers would wonder if there's data they need to back up.
2. **Operational simplicity.** No volume to manage, no backup story, no concern about restoring "stale" cache data after a restart.

Real production setups sometimes do persist Redis (with AOF for durability), especially when Redis is used for things beyond caching — sessions, rate limit counters, queue state. The right answer depends on what Redis is being used for. Pure cache: don't persist. Stateful queue or session store: do persist. We're firmly in the first bucket.

### What didn't change

The application logic is almost entirely identical to challenge 10. The shape of `vote()`, the resource methods, the request flow, the metric annotations, the request-ID tracing — all unchanged. Only the cache backend swapped from Caffeine to Redis. The user-visible API didn't move.

This is the same payoff we saw in challenge 10: the cache abstraction in `CounterHelper` was already sound (cache → DB on miss, invalidate-on-write), so swapping the implementation underneath that abstraction was a small change. **Investing in clean layering early pays back later when you swap layers.**
