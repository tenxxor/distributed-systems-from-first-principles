# Challenge 13 — Sharding

## Problem

Read replicas (challenge 12) scaled **read throughput** by adding more places where reads could be served. They didn't scale:

1. **Write throughput.** Every write still funnels through one primary. When write load exceeds what one Postgres can handle (CPU, IO, lock contention on hot rows), adding more replicas does nothing — they all stream from the same overloaded primary.

2. **Total data volume.** Each replica is a *full copy* of primary's data. If the `counters` table is 10TB, you need 10TB on every replica. Replicas multiply storage; they don't divide it.

3. **Single-row hot spots.** If `video-funny-cats` gets a million votes per second, no amount of replication helps — every vote serializes through one row's lock on one primary. Replicas just observe the bottleneck.

The architectural fix is **sharding** (also called horizontal partitioning): split the data across N independent Postgres clusters. Each shard owns a slice of the keyspace. Writes for a counter go to its shard; reads for a counter come from its shard. Different counters can be written in parallel because they're physically on different Postgres servers.

This is the **sharding** move from challenge 12's "Replicas vs sharding — two different scaling moves" notes entry. Where replicas scale reads on a process-per-connection DB, sharding scales writes and total data volume by partitioning the keyspace across more independent stores. The same pattern applies to other stateful services (Redis Cluster, Cassandra, DynamoDB) — they all use sharding to escape per-instance limits.

## Product

Eight containers in compose:

- **3 counter containers** — same as before, except they now hold **N connection pools** (one per shard). Each operation routes per-counter to the owning shard.
- **1 nginx container** — unchanged.
- **3 Postgres shards** — `postgres-shard-0`, `postgres-shard-1`, `postgres-shard-2`. Each is its own independent Postgres cluster with its own data directory. **No replication between them.**
- **1 Redis container** — unchanged. Still one Redis; the cache key already includes counter ID, so the same key always hits the same Redis entry regardless of which Postgres shard owns the counter.

The sharding key is the **counter ID**. We compute `Math.abs(CRC32(counterId)) % N` to pick the shard. Every operation on a counter — get, vote, create, delete — routes to that one shard. Cross-shard operations (`LIST`) require **scatter-gather**: query every shard, merge results in memory, return.

We deliberately don't add replicas to each shard for this challenge. Pure sharding only. Production setups combine the two (each shard has a primary + replicas) — see "What's Missing."

## Programming

### Run-time — What's Actually Happening

![Three client processes outside the cluster reach the LB container on localhost:9000. Inside the cluster private network (bridge): the LB container, three counter containers (admin ports :8081 published as host :18081/:28081/:38081 for external Prometheus, plus per-container metric models — but no cache models, since cache moved to Redis), a Redis container (cache), and three independent Postgres SHARD containers (postgres-shard-0:5432, postgres-shard-1:5432, postgres-shard-2:5432). Each counter has connection pools to all three shards, but routes per-counter operations to the one shard that owns the counter via CRC32(counterId) % 3; cross-shard operations like LIST scatter-gather across all shards. There is NO replication between shards — they're independent. Each Postgres shard has its own block storage volume mounted at /var/lib/postgresql/data, accessed via file handles. Killing one shard makes 1/3 of the keyspace unavailable but other shards keep working.](./diagram.png)

```
┌─ host machine ────────────────────────────────────────────────────────────┐
│                                                                            │
│   client ── http://<host>:9000                                             │
│                                                                            │
│   ┌────────────── cluster private network (bridge) ─────────────────┐      │
│   │                                                                  │     │
│   │             ┌──────────┐                                         │     │
│   │             │ nginx-lb │                                         │     │
│   │             └─────┬────┘                                         │     │
│   │       ┌───────────┼───────────┐                                  │     │
│   │       ▼           ▼           ▼                                  │     │
│   │   counter-1   counter-2   counter-3                              │     │
│   │     │ │ │       │ │ │       │ │ │                                │     │
│   │     │ │ │       │ │ │       │ │ │                                │     │
│   │     │ │ └───────┴─┴─┴───────┴─┴─┴──▶ redis (cache)               │     │
│   │     │ │                                                          │     │
│   │     │ └─ shard route via CRC32(counterId) % 3 ─────┐             │     │
│   │     │                                              │             │     │
│   │     │   ┌──────────────────┬───────────────────────┘             │     │
│   │     │   ▼                  ▼                                     │     │
│   │     │  postgres-shard-0  postgres-shard-1  postgres-shard-2      │     │
│   │     │   ▲                  ▲                  ▲                  │     │
│   │     └───┼──────────────────┼──────────────────┘                  │     │
│   │         │                  │                                     │     │
│   │      pg-shard-0-data    pg-shard-1-data    pg-shard-2-data       │     │
│   │      (volume)           (volume)           (volume)              │     │
│   │                                                                  │     │
│   └──────────────────────────────────────────────────────────────────┘     │
│                                                                            │
└────────────────────────────────────────────────────────────────────────────┘
```

The diagram should make one shape obvious: **each counter process has connections to all three shard db processes, but only sends each request to one of them** — the shard db process that owns the counter model being operated on.

#### Data

Same data shapes as challenge 12, partitioned differently:

| Data | Where it lives | Notes |
|---|---|---|
| Counter rows, user votes | **Partitioned** across 3 Postgres shards by `CRC32(counterId) % 3` | Each row exists on exactly one shard |
| `flyway_schema_history` | One per shard, separate state on each | Migrations run independently against each shard |
| Redis cache entries | Single Redis (no sharding) | Cache key includes counter ID; works regardless of which shard owns the counter |
| Metrics, logs | Per-container | Unchanged |

The interesting new piece of data is the **shard mapping**. Unlike the read/write split in challenge 12 (which was a property of the SQL operation), the shard a counter belongs to is **derived** from its ID — we don't store it anywhere. That's a design choice: a stored shard map is more flexible (lets you rebalance) but introduces a metadata service. Computed mapping is simpler.

#### Process

**Three more architectural processes** than challenge 12 (which had 1 primary + 2 replicas = 3 Postgres). Net change is zero process count, but the **shape** is different:

- Challenge 12: 1 write-capable Postgres + 2 read-only replicas. **Sequential** scaling.
- Challenge 13: 3 write-capable Postgres, each owning ~1/3 of the keyspace. **Parallel** scaling.

The runtime communication graph:

- **Counter → owning shard**: per-counter operations (get/vote/create/delete) go to one shard, decided by hashing the counter ID.
- **Counter → all shards**: cross-shard operations (`LIST`) — scatter-gather across every shard.
- **Counter → Redis**: cache reads/invalidations, unchanged from challenge 11+.

#### Infrastructure

- **8 containers**: 1 nginx, 3 counters, 3 Postgres shards, 1 Redis.
- **3 Postgres volumes**, one per shard.
- **N connection pools** in each counter (one per shard), plus the existing Redis connection. With `maxSize: 10` per pool:
  - **Per counter**: 3 pools × 10 = up to 30 Postgres connections (10 to each shard).
  - **Per shard**: 3 counters × 10 = up to 30 Postgres connections received (one pool from each counter).
  - **Cluster-wide**: 3 counters × 3 shards × 10 = up to 90 connections total.
  - Each shard at 30 connections is well under Postgres's default `max_connections=100`.
- **No new published ports** — all Postgres remains internal.

### Compile-Time — How to Implement It

The new compile-time concept is the **router** — a small library that encapsulates "given a counter ID, pick a shard."

#### The model: `config.yml` (updated)

The single `database` block (or `primaryDb`/`replicaDb` from challenge 12) becomes a **list** under `shardDbs`:

```yaml
shardDbs:
  - url: jdbc:postgresql://postgres-shard-0:5432/counters
    user: counter_app
    password: change_me_in_prod
    maxSize: 10
    ...
  - url: jdbc:postgresql://postgres-shard-1:5432/counters
    ...
  - url: jdbc:postgresql://postgres-shard-2:5432/counters
    ...
```

Three independent DataSourceFactory blocks. Each becomes its own connection pool with its own host. The size of this list **is** the shard count.

#### The model: `docker-compose.yml` (updated)

Three separate `postgres-shard-N` services, each with its own volume. Each counter container gets `POSTGRES_SHARD_0_HOST`, `POSTGRES_SHARD_1_HOST`, `POSTGRES_SHARD_2_HOST` env vars. No replication wiring between them — each shard is independent.

#### The model: `V1__initial_schema.sql` (updated)

Same schema as challenge 12, with one important deletion: **no seed data**. Why?

- Each shard runs V1 independently.
- If V1 inserts the seed counters, *every* shard would have all of them.
- But the application expects each counter to live on only the shard `CRC32(id) % N` says it should.
- Inserting via SQL bypasses the routing logic.

So V1 just creates schema. The cluster boots up empty; populate it via the API after `docker compose up` (POST to `/api/v1/counters` for each counter you want). The router lands each counter on the correct shard automatically.

#### The library: `ShardRouter` (new)

The new compile-time abstraction. Encodes the sharding strategy in one place:

```java
public class ShardRouter {
    private final List<Jdbi> shardJdbis;

    public int shardIndexFor(String counterId) {
        CRC32 crc = new CRC32();
        crc.update(counterId.getBytes(StandardCharsets.UTF_8));
        int hash = (int) (crc.getValue() & 0x7FFFFFFF);
        return hash % shardJdbis.size();
    }

    public Jdbi jdbiFor(String counterId) {
        return shardJdbis.get(shardIndexFor(counterId));
    }

    public List<Jdbi> allShards() {
        return shardJdbis;  // for scatter-gather
    }
}
```

Three responsibilities:
- `shardIndexFor(id)` — pure function from ID to shard index. Deterministic.
- `jdbiFor(id)` — gets the JDBI for the shard that owns this counter.
- `allShards()` — for cross-shard operations.

Localizing the routing logic in one class means changing the strategy (e.g., to consistent hashing) is a single-file edit.

#### The model: `ShardedPageCursor` (new)

The single-shard `PageCursor` (created_at, counter_id) won't work for sharded list, because "the next page" depends on **per-shard positions** — each shard advances independently. So we wrap N optional `PageCursor`s into a sharded version:

```java
public record ShardedPageCursor(List<Optional<PageCursor>> shardCursors) {
    // Each Optional represents one shard's position.
    // Optional.empty() means that shard is exhausted.
}
```

Encoded on the wire as a base64 string of the form `<cursor0>|<cursor1>|<cursor2>`. Each `<cursorI>` is either empty (shard exhausted) or a base64-encoded `PageCursor`.

#### The library: `CounterHelper` (updated)

Two routing patterns now coexist:

```java
// Single-counter ops — route via ShardRouter
public Optional<Counter> get(String counterId) {
    Jdbi jdbi = router.jdbiFor(counterId);
    // ... cache-aside, fall back to jdbi if miss ...
}

// Cross-shard ops — scatter-gather
public PageResult list(Optional<String> cursorString, int limit) {
    // Fetch (limit + 1) candidates from EACH shard
    // Merge into one sorted list
    // Take first `limit` as this page
    // Compute new ShardedPageCursor — each shard advances per what we took from it
}
```

The scatter-gather logic is the most subtle part. For the "give me 10 counters globally sorted by created_at DESC" query:

1. Each shard returns up to 11 of its newest counters.
2. We have up to 33 candidates total (3 shards × 11 each).
3. Sort them by (created_at DESC, counter_id DESC).
4. Take the first 10 as this page.
5. For each shard, the new cursor points to the **last item we took FROM THAT SHARD** — or stays at the previous position if we didn't take anything from that shard, or becomes empty if the shard exhausted.

The key insight: scatter-gather **fetches more rows than it returns**. To return 10 globally-sorted rows, we have to fetch up to (limit+1) from each shard, because we don't know in advance which shard's rows will dominate the top of the merged list.

#### The library: `CounterApplication` (updated)

- Builds a `List<Jdbi>` (one per shard).
- Runs Flyway against each shard sequentially.
- Constructs the `ShardRouter` from the JDBI list.
- Passes the router to the helper.
- Health check probes every shard.

#### The library: `ShardClusterHealthCheck` (new)

Replaces `DatabaseHealthCheck`. Iterates every shard in `router.allShards()` and runs `SELECT 1` against each. If any shard fails, the counter instance reports unhealthy. Rationale: with sharding-without-replication, losing a shard means we can't serve requests for the keys that hash to it — better to be visibly unhealthy than to silently 500 on partial requests.

## Run It

```bash
cd challenge-13-counter-server-process
mvn -q -DskipTests package
docker compose up --build -d
```

The cluster boots up empty — no seed data baked into the schema. Populate via the API.

### Create some counters

```bash
for c in video-funny-cats video-dev-tutorial video-music-mix; do
    curl -s -X POST http://localhost:9000/api/v1/counters \
        -H "Content-Type: application/json" -d "{\"counterId\":\"$c\"}"
    echo
done
```

Three counters created. Each routed to one of three shards via `CRC32(counterId) % 3`.

### Verify shard distribution

```bash
for c in video-funny-cats video-dev-tutorial video-music-mix; do
    for shard in 0 1 2; do
        result=$(docker compose exec -T postgres-shard-$shard psql -U counter_app -d counters \
            -tAc "SELECT counter_id FROM counters WHERE counter_id='$c';" 2>/dev/null)
        if [ -n "$result" ]; then echo "$c → shard-$shard"; fi
    done
done
```

You'll see something like:

```
video-funny-cats → shard-2
video-dev-tutorial → shard-0
video-music-mix → shard-2
```

Different counters live on different shards. Same counter always lives on the same shard (the routing is deterministic).

### Verify writes route to one shard

```bash
curl -s -H "X-User-Id: zoe" -X PUT \
    http://localhost:9000/api/v1/counters/video-dev-tutorial/vote \
    -H "Content-Type: application/json" -d '{"vote":"LIKE"}'

# zoe's vote should only exist on shard-0 (owner of video-dev-tutorial)
for shard in 0 1 2; do
    count=$(docker compose exec -T postgres-shard-$shard psql -U counter_app -d counters \
        -tAc "SELECT count(*) FROM user_votes WHERE counter_id='video-dev-tutorial' AND user_id='zoe';")
    echo "shard-$shard zoe votes: $count"
done
```

Output:

```
shard-0 zoe votes: 1
shard-1 zoe votes: 0
shard-2 zoe votes: 0
```

### Cross-shard scatter-gather (LIST)

```bash
# Add several counters to spread across shards
for c in alpha bravo charlie delta echo foxtrot; do
    curl -s -X POST http://localhost:9000/api/v1/counters \
        -H "Content-Type: application/json" -d "{\"counterId\":\"vid-$c\"}" > /dev/null
done

# Pagination across shards, 3 at a time
curl -s "http://localhost:9000/api/v1/counters?limit=3"
# returns 3 newest, plus a nextCursor
# repeat with the cursor to get the next page
```

The cursor encodes per-shard positions. Each page fetches (limit+1) from each shard, merges in memory, takes `limit`, returns a new cursor that advances each shard by whatever we took from it.

### Partial outage — kill one shard

```bash
docker stop postgres-shard-2

# Counters whose IDs hash to shard-2 fail:
curl -s -w "HTTP %{http_code}\n" -o /dev/null \
    http://localhost:9000/api/v1/counters/video-funny-cats   # → 500

# Counters on other shards still work:
curl -s -w "HTTP %{http_code}\n" -o /dev/null \
    http://localhost:9000/api/v1/counters/video-dev-tutorial  # → 200

# LIST fails because scatter-gather can't complete:
curl -s -w "HTTP %{http_code}\n" -o /dev/null \
    http://localhost:9000/api/v1/counters                     # → 500

docker start postgres-shard-2
```

This is the **defining failure mode of pure sharding**: localized loss of part of the keyspace. Some operations work, some don't, depending on which key they touch.

### Stop everything

```bash
docker compose down       # keep shard data volumes
docker compose down -v    # wipe everything
```

## What's Missing

1. **No replication.** Each shard is a single Postgres. Lose a shard, lose 1/N of your data availability. Real production systems combine sharding + replication: each shard has its own primary + replicas. The two compose like Lego — different routing logic for write/read split inside each shard, on top of the shard routing across shards.

2. **No automatic resharding.** With our `hash % N` formula, changing N (adding a shard) would re-map most keys to different shards. Production systems use **consistent hashing** to limit data movement on resize — only ~1/N of keys move when you add a shard. We use simple modulo because we never resize at runtime; consistent hashing is real and worth knowing about.

3. **No cross-shard transactions.** A transaction on shard A and one on shard B can't be atomic together. For our counter app this isn't a problem (every operation is scoped to one counter), but for apps that need transactional consistency across keys (e.g., transferring money between accounts), you'd need either: (a) two-phase commit (slow, complex), (b) sagas (eventual consistency, application-level rollback), or (c) keeping the related keys on the same shard via key co-location.

4. **No directory service / metadata layer.** A real sharded system often has a separate service that maps tenants/users/keys to shards, with rebalancing logic. We compute the mapping deterministically from the ID — simpler but less flexible.

5. **Hot shard problem.** If your hash isn't uniform (e.g., one user generates 90% of votes), one shard saturates while others idle. Production systems handle this with: better hashing, sub-key partitioning (split a single hot key across "subshards"), or moving hot keys to dedicated shards.

6. **Scatter-gather LIST is O(N) in shard count.** For 3 shards it's fine. For 100 shards, listing 10 counters means querying 100 shards for 11 each = 1100 row reads to return 10. Real systems either limit cross-shard queries, denormalize into a separate index, or use specialized stores (Elasticsearch) for queries that don't fit the shard key.

7. **No Redis Cluster.** One Redis serves all shards. At larger scale, Redis itself can be sharded (Redis Cluster or client-side sharding).

8. **Migrations across all shards in app startup.** For 3 shards × small migrations, fine. For 100 shards or migrations that take minutes, this becomes a real operational problem; production setups separate migration into a deploy step.

Carried forward:
- **Secrets in plaintext.**
- **No circuit breaker on Redis.**
- **Cache stampede possible on hot keys.**

## Notes

### Sharding is the same architectural move as challenge 9's "split work across instances" — just applied to data instead of compute

Challenge 9 split the *compute* across multiple counter processes — same logical service, three independent runners, requests load-balanced across them.

Challenge 13 splits the *data* across multiple Postgres shards — same logical schema, three independent stores, keys partitioned across them.

Both are "horizontal scaling": add more independent instances of the same kind of thing. The difference is what you're scaling:

- **Stateless compute** (counter containers): trivial to scale because instances are interchangeable. Add another counter, the LB routes to it, no coordination needed.
- **Stateful storage** (Postgres shards): hard to scale because instances are *not* interchangeable. Each shard owns specific keys; routing decisions are deterministic and can't be load-balanced.

The asymmetry is fundamental. Stateless services scale by replication (more identical workers). Stateful services scale by sharding (different workers own different data). The pattern carries through every layer of distributed systems — caches, message queues, search indexes, you name it.

### Why we use simple modulo instead of consistent hashing

Our `shardIndexFor` is `hash % N`. The textbook problem with this formula: when N changes (you add or remove a shard), most keys remap to different shards.

Concretely: if N goes from 3 to 4, then a key that was on shard `hash % 3 = 1` is now on `hash % 4 = ?` — almost certainly a different shard, requiring a data move. **About 75% of keys move on a 3 → 4 resize.** That's catastrophic if your DB is terabytes.

**Consistent hashing** solves this. It maps keys to a circular hash space, then maps each shard to multiple positions on that ring. A key belongs to whichever shard's position is "next clockwise." When you add a shard, only the keys that fall in *that shard's range* move — about 1/N of keys, not (N-1)/N.

This is what production sharding systems use:
- **Memcached clients** (libketama) implement consistent hashing.
- **Cassandra** uses it for partition placement.
- **DynamoDB** uses a related technique (consistent hashing variant + virtual nodes).
- **Redis Cluster** uses fixed 16,384 hash slots — a different approach with similar resize-friendliness.

For our demo, we use simple modulo because:
- We never resize the cluster at runtime.
- Consistent hashing is a few hundred lines of code on top of CRC32 — it'd dominate the file count.
- The pedagogical point is "partition by hash"; the resize-friendliness story is a follow-on.

But it's worth knowing that production sharding always uses some form of consistent hashing or hash slots, never naive modulo.

### Scatter-gather is unavoidable for queries that don't include the shard key

If a query targets a single counter ID, we know its shard immediately and route there.

If a query is "give me all counters in some order" — `LIST` — there's no shard hint in the query. We have to check every shard.

This is the **fundamental cost** of sharding: queries that don't include the partition key are expensive. Sharded systems handle this in different ways:

- **DynamoDB** requires you to specify a partition key for every query. Querying *across* partitions costs more (Scan operation, expensive, doesn't paginate naturally).
- **Cassandra** has the same model: queries by partition key are O(1); queries that scan partitions are O(N) and discouraged.
- **MongoDB sharded collections** allow non-shard-key queries but route them to all shards via "scatter-gather."
- **Citus** and **Vitess** (sharding proxies for Postgres/MySQL) implement scatter-gather automatically inside the proxy.

Our implementation is a hand-rolled scatter-gather. Same shape as the proxy-based ones, just done in app code so the reader can see what's actually happening.

### The cost of scatter-gather: fetching more rows than you return

For limit=10 across N=3 shards, we fetch up to 11 rows per shard = 33 candidates, take the top 10, throw 23 away. Worst-case work scales O(N × limit).

For small N this is fine. At larger N it becomes a real problem. Three responses:

1. **Limit cross-shard queries.** Make `LIST` an admin-only endpoint, or limit it to filtered queries that include the shard key (e.g., "list my counters" where user_id is the partition key).
2. **Maintain a separate index.** Replicate the data into Elasticsearch or a non-sharded read-replica that has all counters. Pay the storage cost; gain fast cross-shard queries.
3. **Materialized cross-shard views.** Some sharding systems automatically maintain a non-sharded summary table updated via async replication.

For our scale, scatter-gather with N=3 is fine. The reader walks away knowing the cost.

### Why Redis stays as one instance

Three reasons:

1. **The cache key is already the routing key.** `counter:video-funny-cats` is one key, hits one Redis entry, no ambiguity. Whether Postgres has the row on shard-0 or shard-2 doesn't matter — Redis is a separate dimension.
2. **Cache load is uniform across shards in a way DB load isn't.** Reading from cache is microseconds; one Redis can serve many counters' worth of cache traffic. A single Redis is rarely the bottleneck before Postgres is.
3. **Redis has its own sharding mechanism (Redis Cluster).** When you do need to shard cache, you don't roll your own — you flip to cluster mode. That's a separate concept (server-side sharding via hash slots, vs our app-side sharding via hash modulo). We left it for "What's Missing" rather than building both at once.

### Sharding without replication = partial outages

This is worth being explicit about. Killing one Postgres shard makes 1/N of the keyspace unavailable — for that key range:
- Reads return errors.
- Writes return errors.
- LIST fails entirely (any one shard down breaks scatter-gather).

But the *other* keys keep working. Different counters experience the outage as "I'm down" or "I'm up" depending purely on which shard hashes their ID.

This is fundamentally different from the challenge 12 failure modes:
- Challenge 12: kill primary → everything writes fail, reads still work via replicas.
- Challenge 13: kill a shard → some keys (the ones on that shard) fail entirely; other keys are fine.

Production fixes this by adding replicas to each shard (so each shard's primary failing has a standby ready). Compound complexity, same primitives — see "What's Missing."

### What didn't change

The DAOs. Same SQL, same JDBI annotations, same return types. We attach them to whichever shard's JDBI we want — sharding is a routing decision *above* the DAO.

This is the third time we've reaped this. Challenge 10 (SQLite → Postgres), challenge 12 (read/write split), challenge 13 (shard routing) — each is a different kind of "where does the SQL go" decision, and none of them requires the SQL itself to change. **Clean DAO abstraction is what makes orthogonal scaling possible.**
