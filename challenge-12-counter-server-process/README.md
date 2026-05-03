# Challenge 12 — Read Replicas

## Problem

In challenges 10 and 11, every read and every write went to one Postgres primary. That works at our scale, but real systems hit a wall:

1. **Reads dominate.** Most workloads are 90–99% reads. Even with Redis caching, cache misses, list queries, and admin queries all funnel through one Postgres backend per connection. Read throughput is capped by the primary's CPU/IO.
2. **Primary is a SPOF for reads too.** From challenge 10: kill Postgres, the cluster is down. Even reads stop, despite the data being safely on disk and not changing.

The architectural fix is **horizontal scaling for reads** via streaming replication. One **primary** handles writes; N **replicas** each maintain a near-real-time copy of the data and serve reads. The application chooses where to send each query based on whether it's a read or a write.

**Replicas** are one of two architectural moves you can make to scale a stateful service. The other is **sharding**, which we'll cover in challenge 13. They solve *different* problems: replicas scale **reads** (and provide HA failover candidates), sharding scales **writes** and **total data volume**. Both Postgres and Redis can use both moves, with different defaults based on their concurrency models — see the "Replicas vs sharding — two different scaling moves" notes entry below for the precise table.

## Product

Eight containers in compose:

- **3 counter containers** — same as challenge 11 in shape, except they now use **two connection pools**. Reads go to the replica pool; writes go to the primary pool.
- **1 nginx container** — unchanged.
- **1 primary Postgres container** — runs `postgres:16` with WAL streaming enabled. Migrations run here. All writes land here.
- **2 replica Postgres containers** — boot empty, do `pg_basebackup` from primary on first start, then run in standby mode replaying primary's WAL. Read-only by design (Postgres rejects write SQL on a standby).
- **1 Redis container** — unchanged from challenge 11.

Replication is **asynchronous**: primary doesn't wait for replicas to acknowledge before committing. This is fast but introduces **replication lag** — replicas can be milliseconds behind. We accept this; for a like/dislike counter, brief staleness on a read is invisible.

## Programming

### Run-time — What's Actually Happening

![Three client processes outside the cluster reach the LB container on localhost:9000. Inside the cluster private network (bridge): the LB container, three counter containers (admin ports :8081 published as host :18081/:28081/:38081 for external Prometheus, plus per-container metric models), a Redis container (cache, no persistent volume), one Postgres primary container, and two Postgres replica containers. Counter containers send "write data requests" (transactions) to the primary and "read data requests" to the two replicas. Primary streams its WAL log to each replica continuously, keeping them in sync. Each Postgres process — primary and both replicas — has its own independent block storage volume mounted at /var/lib/postgresql/data, accessed via file handles. The cache and the three Postgres data dirs are all separate persistence layers; only the Postgres ones store source-of-truth state.](./diagram.png)

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
│   │     │ │         │ │         │ │                                  │     │
│   │     │ └─reads───┘ └─reads───┘ │                                  │     │
│   │     │                writes─┐ │                                  │     │
│   │     │                       │ │                                  │     │
│   │     │   ┌─────────┐         │ │      ┌────────────┐              │     │
│   │     │   │  redis  │         │ ▼      │            │              │     │
│   │     │   │ (cache) │   postgres-primary◀──streams──┐              │     │
│   │     │   └─────────┘         │  WAL ──────────────┐│              │     │
│   │     │                       │                    ││              │     │
│   │     │     reads round-robin │                    ▼▼              │     │
│   │     └─────────────────┐     │       postgres-replica-1            │     │
│   │                       ▼     │       postgres-replica-2            │     │
│   │                                                                   │     │
│   └───────────────────────────────────────────────────────────────────┘     │
│                                                                            │
│       pg-primary-data       pg-replica-1-data       pg-replica-2-data      │
│       (named volume)        (named volume)          (named volume)          │
│                                                                            │
└────────────────────────────────────────────────────────────────────────────┘
```

Three Postgres data directories now — one per Postgres container. Each replica's data dir is bootstrapped from primary's via `pg_basebackup` on first run, then kept in sync via streaming WAL.

#### Data

Same data shapes as challenge 11. What changed about *where* it lives:

| Data | Where it lives | Notes |
|---|---|---|
| Counter rows, user votes, `flyway_schema_history` | Primary's pg-data volume + each replica's pg-data volume | Replicas are bytewise copies of primary, kept current via streaming WAL |
| WAL records | Primary's pg-data → streamed over TCP → replicas' pg-data | New runtime data: WAL is no longer just a crash-recovery log; it's now load-bearing for replication |
| Replication slots / replication lag (bytes) | Primary's `pg_stat_replication` view | Observable per-replica state for "how far behind primary is this replica?" |
| Redis cache | Redis (no volume) | Unchanged |
| Metrics, logs | Per-container | Unchanged |

The interesting new *observable* data is **replication lag**, queryable from primary:

```sql
SELECT client_addr, state, pg_wal_lsn_diff(sent_lsn, replay_lsn) AS lag_bytes
FROM pg_stat_replication;
```

Lag is normally near-zero on a healthy local cluster. It grows under heavy write load, network latency, or when a replica is briefly unreachable.

#### Process

**Two more architectural processes** than challenge 11: the two replica Postgres servers, each running in its own container. (Postgres also spawns *internal* child processes for WAL senders and WAL receivers to handle the streaming, but those are below our framework's level — they live inside the existing Postgres processes. See "WAL senders/receivers are Postgres-internal, not architectural processes" in the notes.)

The runtime communication graph:

- **Counter → primary**: writes (transactions). Same as before.
- **Counter → replica pool**: reads. Connections distributed across replica-1 and replica-2 by the JDBC driver.
- **Replica → primary**: continuous TCP connection. Replica sends "I want WAL from position X"; primary streams new records as they're committed; replica replays them locally.

Replicas don't talk to each other. Each one has an independent connection to primary.

#### Infrastructure

- **Eight containers**: 1 nginx, 3 counters, 1 primary Postgres, 2 replica Postgres, 1 Redis.
- **Three Postgres volumes**: `pg-primary-data`, `pg-replica-1-data`, `pg-replica-2-data`. Each is fully populated; replicas aren't smaller than primary.
- **Four published host ports**, unchanged: 9000, 18081, 28081, 38081.
- **All Postgres ports are internal** — none published to the host.

### Compile-Time — How to Implement It

Three things changed: configuration adds a second DataSource, `CounterApplication` builds two JDBI instances, `CounterHelper` explicitly routes reads vs writes.

#### The model: `config.yml` (updated)

Two database blocks now:

```yaml
primaryDb:
  url: jdbc:postgresql://postgres-primary:5432/counters
  maxSize: 10
  ...

replicaDb:
  url: jdbc:postgresql://postgres-replica-1:5432,postgres-replica-2:5432/counters?targetServerType=preferSecondary&loadBalanceHosts=true
  maxSize: 20
  ...
```

The replica URL uses two Postgres-specific JDBC features:

- **Multi-host URL** — comma-separated list of hosts. The driver tries them in order on connection acquisition.
- **`loadBalanceHosts=true`** — randomize the order, so connections spread evenly across replicas instead of always preferring the first.
- **`targetServerType=preferSecondary`** — prefer hosts running as standbys. Falls back to primary if all standbys are down (so reads still work in worst case).

Bigger pool on replica side (20 vs 10) because reads are typically more numerous than writes. Three counters × 20 replicas-pool connections = up to 60 connections distributed across two replicas, well under Postgres's default `max_connections=100` per server.

#### The model: `docker-compose.yml` (updated)

Three big additions:

1. **`postgres-primary` service** — replaces the old `postgres` service. Adds `wal_level=replica` and `max_wal_senders=10` to startup args. Mounts a `primary-init.sh` script into `/docker-entrypoint-initdb.d/` that creates a `replicator` role on first boot and adds a replication-allowed line to `pg_hba.conf`.
2. **`postgres-replica-1` and `postgres-replica-2` services** — same `postgres:16` image but with a custom `entrypoint`: `replica-entrypoint.sh`. The script runs `pg_basebackup` from primary on first boot (when the data dir is empty), then chains to the standard postgres entrypoint. Postgres sees the `standby.signal` file written by `pg_basebackup -R` and starts in standby mode.
3. **Counter env vars** — counters get `POSTGRES_PRIMARY_HOST` and `POSTGRES_REPLICA_HOSTS` instead of one `POSTGRES_HOST`.

#### The library: `primary-init.sh` (new)

Two small jobs that have to happen on the primary's first boot:

1. Create a `replicator` role with the `REPLICATION` privilege.
2. Add `host replication replicator 0.0.0.0/0 trust` to `pg_hba.conf` and `pg_reload_conf()`.

The second is needed because the default `pg_hba.conf` doesn't include any `replication` line; without one, `pg_basebackup` from a replica fails with "no pg_hba.conf entry for replication connection." `trust` here is fine — the network is private, and the role is replication-only.

#### The library: `replica-entrypoint.sh` (new)

Runs instead of the default postgres entrypoint:

```bash
if [ ! -s "$PGDATA/PG_VERSION" ]; then
    # First boot — bootstrap from primary
    until psql -h "$PRIMARY_HOST" -U replicator -d postgres -c '\q'; do sleep 2; done
    pg_basebackup -h "$PRIMARY_HOST" -U replicator -D "$PGDATA" -Fp -Xs -P -R
    chown -R postgres:postgres "$PGDATA"
fi
exec docker-entrypoint.sh postgres
```

`pg_basebackup` clones the entire primary's data directory. The `-R` flag writes a `standby.signal` file plus connection settings, which tells Postgres to start in standby mode. After the first boot, the data directory is populated; subsequent restarts skip the bootstrap and just resume streaming.

This is roughly what tools like Patroni and StackGres do during replica bootstrap, minus the orchestration logic for promoting a replica to primary on failure. We don't implement that — see "What's Missing."

#### The library: `CounterApplication` (updated)

Builds **two JDBI instances**:

```java
Jdbi primaryJdbi = new JdbiFactory().build(env, config.getPrimaryDb(), "postgresql-primary");
Jdbi replicaJdbi = new JdbiFactory().build(env, config.getReplicaDb(), "postgresql-replica");

CounterHelper helper = new CounterHelper(primaryJdbi, replicaJdbi, cache);
```

Migrations still run against the primary only:

```java
runMigrations(configuration.getPrimaryDb());
```

Replicas inherit the schema via WAL streaming. The first `CREATE TABLE` on primary becomes a WAL record; replicas replay it. By the time counters start serving traffic, every replica has the same schema as primary.

#### The library: `CounterHelper` (updated)

Same DAO interfaces, **explicitly routed**:

```java
// Reads — replicaJdbi
public Optional<Counter> get(String counterId) {
    CounterEntity entity = cache.get(..., () -> replicaJdbi.withHandle(...));
    ...
}

// Writes — primaryJdbi
public Optional<Counter> vote(...) {
    Optional<Counter> result = primaryJdbi.inTransaction(h -> ...);
    ...
}
```

Every method is unambiguously a read (uses `replicaJdbi`) or a write (uses `primaryJdbi`). Anyone reading the helper can immediately tell which queries go where.

This is the **simplest, clearest** way to do read/write routing. Production systems often hide this behind a smarter abstraction (annotation-driven, query-inspected, proxy-based), but they all reduce to the same primitive: pick the right pool per query.

## Run It

```bash
cd challenge-12-counter-server-process
mvn -q -DskipTests package
docker compose up --build -d
```

First boot is slower (~20–30s) because replicas have to do `pg_basebackup` from primary before they become healthy. Subsequent boots are fast.

### Verify replication is streaming

```bash
docker compose exec postgres-primary psql -U counter_app -d counters \
    -c "SELECT client_addr, state, sync_state FROM pg_stat_replication;"
```

You should see two rows, both with `state=streaming`. `sync_state=async` is expected — we configured async replication.

### Verify writes go to primary, reads to replicas

```bash
# Vote (write)
curl -s -H "X-User-Id: zoe" -X PUT \
    http://localhost:9000/api/v1/counters/video-funny-cats/vote \
    -H "Content-Type: application/json" -d '{"vote":"LIKE"}'

# Inspect — vote is in primary
docker compose exec postgres-primary psql -U counter_app -d counters \
    -c "SELECT * FROM user_votes WHERE counter_id='video-funny-cats';"

# Inspect — vote propagated to replicas
docker compose exec postgres-replica-1 psql -U counter_app -d counters \
    -c "SELECT * FROM user_votes WHERE counter_id='video-funny-cats';"
```

Same rows in all three. Replicas caught up to primary.

### Verify reads actually hit replicas

```bash
# Clear the cache so reads bypass Redis
docker compose exec redis redis-cli FLUSHALL

# Read several times
for i in 1 2 3 4 5; do
    curl -s http://localhost:9000/api/v1/counters/video-funny-cats > /dev/null
done

# Connections per replica (you'll see active client backends)
docker compose exec postgres-replica-1 psql -U counter_app -d counters \
    -c "SELECT count(*) FROM pg_stat_activity WHERE backend_type='client backend';"
docker compose exec postgres-replica-2 psql -U counter_app -d counters \
    -c "SELECT count(*) FROM pg_stat_activity WHERE backend_type='client backend';"

# Confirm the replicas are actually in standby mode
docker compose exec postgres-replica-1 psql -U counter_app -d counters \
    -c "SELECT pg_is_in_recovery();"   # → t
```

### Replica failover

```bash
docker stop postgres-replica-1
docker compose exec redis redis-cli FLUSHALL

# Reads still work — load balancer routes to replica-2
for i in 1 2 3 4 5; do
    curl -s -w "HTTP %{http_code}\n" -o /dev/null \
        http://localhost:9000/api/v1/counters/video-funny-cats
done

docker start postgres-replica-1
sleep 5  # let it catch up
```

The first request after the kill may return a 500 (a connection in the JDBC pool was pointing at the now-dead replica; the driver detects and removes it). Subsequent requests succeed via replica-2. After replica-1 comes back, the driver starts using both again.

### Primary failover (we don't have one)

```bash
docker stop postgres-primary
docker compose exec redis redis-cli FLUSHALL

# Reads still work — replicas are independent
for i in 1 2 3; do
    curl -s -w "HTTP %{http_code}\n" -o /dev/null \
        http://localhost:9000/api/v1/counters/video-funny-cats
done

# Writes fail — no primary to accept them
for i in 1 2 3; do
    curl -s -w "HTTP %{http_code}\n" -o /dev/null \
        -H "X-User-Id: alice" -X PUT \
        http://localhost:9000/api/v1/counters/video-funny-cats/vote \
        -H "Content-Type: application/json" -d '{"vote":"LIKE"}'
done

docker start postgres-primary
sleep 15  # JDBC pool needs to validate and replace stale connections
# Writes resume
```

This is the primary-as-SPOF story. **Read replicas don't fix availability for writes.** That's HA territory — automatic promotion of a replica to primary on failure — which we don't implement. See "What's Missing."

### Replication lag (mostly invisible at our scale)

```bash
# Show current lag in bytes
docker compose exec postgres-primary psql -U counter_app -d counters \
    -c "SELECT client_addr, state, pg_wal_lsn_diff(sent_lsn, replay_lsn) AS lag_bytes
        FROM pg_stat_replication;"
```

On a healthy local cluster, lag is near zero — replicas are caught up within microseconds. Lag becomes visible under sustained heavy write load, network latency between primary and replicas, or when a replica is briefly unreachable.

### Stop everything

```bash
docker compose down       # keep volumes (replicas resume from where they left off on restart)
docker compose down -v    # wipe everything (replicas re-bootstrap on next up)
```

## What's Missing

Read replicas scale **reads**. They don't scale writes, and they don't make the cluster HA on their own.

1. **Primary is still a SPOF for writes.** Kill primary, writes fail until you bring it back. Real HA setups use **automatic failover** — promote a replica to primary on detected primary failure. Tools: Patroni, repmgr, pg_auto_failover; cloud services like RDS Multi-AZ, Cloud SQL HA. All require distributed consensus (etcd, Consul, or similar) to avoid split-brain — that's challenge 19 / 20 territory.
2. **Replication lag → user-visible staleness.** A user voting and then reloading immediately can read from a replica that hasn't caught up yet, briefly seeing their vote not counted. **Read-your-writes consistency** would route a user's reads to primary for a short window after their write. We don't.
3. **Synchronous replication option not used.** Postgres can guarantee a write is on at least one replica before returning success (`synchronous_commit=on` + `synchronous_standby_names`). Higher write latency, but no data loss on primary failure. Trade-off we didn't make.
4. **No write scaling.** All writes still funnel through one primary. When write throughput exceeds one primary's capacity, you reach for **sharding** (challenge 13) — partitioning data across N primaries.

Carried forward from earlier challenges:

- **Cache stampede** (challenge 11).
- **Secrets in plaintext.**
- **No circuit breaker on Redis.**

## Notes

### Replicas vs sharding — two different scaling moves

Read replicas are one of two architectural moves you can make to scale a stateful service. The other is sharding (challenge 13). They solve **different problems** and they're not in competition — production systems often use both, layered.

| Move | What it scales | Postgres uses it for | Redis uses it for |
|---|---|---|---|
| **Replicas** | Reads (and HA) | Yes — primary use case | Mostly HA; rarely for read throughput (single-threaded reads are already cheap) |
| **Sharding** | Writes + data volume | Yes — when writes outgrow one primary | Yes — the standard way to scale total throughput |

**Both services use both moves.** The defaults differ because the concurrency models differ:

- **Postgres** (process-per-connection from challenge 11): reads are the first thing to saturate, so replicas come up first. When writes or data volume outgrow one primary, sharding follows.
- **Redis** (single-threaded event loop from challenge 11): one thread caps total throughput, so sharding is what you reach for to scale read+write together. Replicas exist for HA (failover) more than for read scaling.

A useful rule of thumb when picking which move to make:

- **Bottleneck is read throughput** → replicas (more places that serve reads).
- **Bottleneck is write throughput, single-row hot spots, or data volume** → sharding (split the keyspace across more independent stores).
- **Bottleneck is availability** → replicas (failover candidate). For sharding, every shard needs its own replica or the system can't survive a single shard's primary failing.

This is the framing challenge 13 builds on. We've used the **replicas** move in this challenge to scale reads. Challenge 13 uses the **sharding** move to scale writes and total data volume — and it'll generalize to "any stateful service that hits the same wall."

### Streaming replication is just WAL-over-TCP

We talked about WAL in challenge 10's notes — every write goes to the write-ahead log first, fsync'd before commit. Streaming replication is **the same WAL, sent over TCP to a replica**, where the replica replays it.

#### Step-by-step mechanics

The intuition: *whenever there is a write on primary, it gets streamed to each replica via WAL, and the replica gets caught up to primary.* Precisely:

1. **Every write on primary writes a WAL record locally first.** This is true even without replication — it's how Postgres ensures durability. Each write is described by a WAL record, written to `pg_wal/` and fsync'd before COMMIT returns to the client.
2. **The WAL sender** on primary (a background process Postgres spawns automatically when a replica connects — one per connected replica) streams those records over a TCP connection to its replica.
3. **The WAL receiver** on each replica reads them off the socket and writes them to its own local `pg_wal/`.
4. **The startup process** on each replica replays the WAL records, applying the same byte-level changes primary made — same code path Postgres uses for crash recovery.
5. **The replica's `replay_lsn`** advances as each record is applied. **Lag** is the distance between primary's `sent_lsn` and replica's `replay_lsn` (queryable via `pg_stat_replication`). Healthy local cluster: 0. Network glitch or slow replica: positive and growing.

You can see the actors with `ps`:

```bash
docker compose exec postgres-primary ps aux | grep walsender
# postgres: walsender replicator 172.20.0.4(48592) streaming 0/3000148
# postgres: walsender replicator 172.20.0.5(51100) streaming 0/3000148

docker compose exec postgres-replica-1 ps aux | grep -E 'walreceiver|startup'
# postgres: walreceiver streaming 0/3000148
# postgres: startup recovering 000000010000000000000003
```

Two WAL senders on primary (one per replica), one WAL receiver per replica, plus the startup process that's permanently in "recovery mode" applying WAL.

#### Why this is the clever part

**WAL is reused for two purposes**: crash recovery (already there before replication existed) and replication (new consumer of the same log). Postgres got streaming replication essentially **for free** because the recovery code already knew how to "replay WAL into the database." Replicas don't need a different code path — they're just permanently doing what crash-recovery does, on a stream that never ends.

This is also why physical streaming is so fast and exact: it's the lowest-level possible representation of changes (raw bytes and LSNs), and replicas reuse the same code path that handles crash recovery. No translation, no application-level logic, no triggers. Just bytes flowing from primary's disk to replica's disk.

This is also why replicas come up empty and need `pg_basebackup` for the initial sync — WAL only describes *changes*, not the full DB state. You need a one-time snapshot to establish a baseline, then WAL keeps you in sync from that point.

### WAL senders/receivers are Postgres-internal, not architectural processes

A precision worth being explicit about. When we count "processes" at the framework level, we mean **architectural processes** — the OS-level processes our compose file declares and we'd draw as boxes in the diagram. By that count, challenge 12 adds **two** new processes vs challenge 11: the two replica Postgres servers (each in its own container).

The WAL senders and WAL receivers we discussed above are real OS processes — you can `ps` them — but they're **child processes spawned by Postgres internally**, not things our compose file or our diagram knows about. They live inside Postgres the same way Postgres backends (one per client connection) live inside Postgres.

| Layer | Process | Visible in compose? | Drawn in our diagram? |
|---|---|---|---|
| Architectural | `postgres-primary` container | Yes | Yes (one box) |
| Architectural | `postgres-replica-1` container | Yes | Yes (one box) |
| Architectural | `postgres-replica-2` container | Yes | Yes (one box) |
| Postgres-internal | postmaster (parent process) | No | No |
| Postgres-internal | client backends (one per app connection) | No | No |
| Postgres-internal | WAL writer | No | No |
| Postgres-internal | checkpointer | No | No |
| Postgres-internal | autovacuum | No | No |
| Postgres-internal | WAL sender (one per replication client) | No | No |
| Postgres-internal | WAL receiver (on each replica) | No | No |
| Postgres-internal | startup process (perpetually replaying WAL on each replica) | No | No |

This isn't a flaw in the framework — it's the framework working correctly. Recall from challenge 11's notes that **MVCC, transaction backends, and other Postgres internals don't appear in our diagrams** because they're properties of one of our infrastructure dependencies (Postgres), not pieces of our system. Same logic here: WAL senders and receivers are part of how Postgres provides the "streaming replication" capability, but they're inside the Postgres process boundary.

The framework composes recursively. If you opened up Postgres, you'd find its own data/process/infra structure (shared memory, backends, file handles into the data dir, the WAL log itself). At the level we're operating, Postgres is one box, and "Postgres knows how to stream WAL to other Postgres instances" is a capability we use without modeling its internals.

So: when in doubt, count processes that **we run, configure, and connect to.** Don't count processes that exist *inside* something we run. That keeps the framework's level of abstraction consistent.

### Why three Postgres processes, not "one logical Postgres"

A reasonable instinct: "Postgres is the database; the replicas should appear as part of one DB to the application." Some systems do offer this abstraction — e.g., Aurora, Spanner, Citus. They hide the fact that there are multiple physical instances behind a single client interface.

We don't hide it. Counters explicitly know about primary and replicas, choose pool per query, handle failure of either independently. Reasons:

- **Honest about the architecture.** The reader sees the read/write split is a deliberate choice, not magic.
- **No new dependency** (no PgBouncer, no Aurora-style proxy).
- **Easy to debug.** When you see a query in primary's logs vs replica-1's logs, you know exactly which path the request took.

In production, many setups insert a routing proxy (PgBouncer, Pgpool-II, RDS Proxy, AWS Aurora) between apps and DB cluster, so apps just see "the database." Trade-off: the proxy is another moving part, with its own failure modes and config. For a teaching repo, the explicit version is clearer.

### The `recomputeAggregates` race we already had

Our `vote()` SQL recomputes `likes` and `dislikes` from scratch using a subquery on `user_votes`. Under high concurrency, two transactions can each compute aggregates from a snapshot that doesn't include the other transaction's vote yet. The end state may be temporarily incorrect.

This race exists since challenge 5 (we noted it implicitly when we discussed transaction isolation in challenge 11). With read replicas, the same race exists on writes — replication doesn't change it, since writes still serialize through one primary's row locks.

The fix would be one of the techniques from challenge 11's "single-writer architectures" notes — `SELECT ... FOR UPDATE`, version columns, or higher isolation levels. We accept the race for the same reason: it self-heals on the next vote (the next recompute sees the latest state) and the per-row contention isn't realistic for our demo workload.

### Replication lag and user-visible staleness

The classic read-replica trap:

1. User votes (write → primary, returns success).
2. User immediately reloads the page (read → replica, replica hasn't gotten the WAL record yet).
3. User sees their own vote not counted.

For a counter that's a like/dislike on a video, a few hundred ms of staleness is invisible — users don't reload that fast, and even if they did, "my vote didn't register" is a one-time blip that resolves on the next refresh.

For other workloads (banking, inventory, e-commerce checkout), this would be unacceptable. The standard fix is **read-your-writes consistency**:

- Track per-user "last write timestamp" or LSN.
- Reads from a user route to primary for some window after their last write.
- Or compare replica's `replay_lsn` to the user's "last seen" LSN before serving from replica.

We don't implement this. The pedagogical point is: **eventual consistency between replicas is the cost of read scaling, and you have to design your features around that cost.**

### Why we don't fail over automatically on primary loss

Automatic primary failover requires answering: **"Has the primary really failed, or is it just slow / unreachable to me?"**

Two replicas observing a primary timeout could each conclude "primary is dead, I'll promote myself." Now both think they're primary. Concurrent writes go to two different boxes. Their data diverges. This is **split-brain** — and it's the canonical hard problem in distributed systems.

The right solution requires **distributed consensus**: a quorum of nodes (often via etcd, Consul, or ZooKeeper) agreeing on "who is primary right now." Tools like Patroni delegate this to a consensus store; cloud services like RDS Multi-AZ have proprietary control planes that solve it.

Our roadmap covers consensus in challenge 19 (leader election) and challenge 20 (Raft walkthrough). Once you understand consensus, automatic failover is a small layer on top of it — but the foundational concept deserves its own challenge.

So challenge 12 is **explicit about the limitation**: read replicas without HA. You scale reads; you don't survive primary failure without manual intervention.

### What didn't change

The **DAOs are identical** to challenge 11. Same SQL, same return types, same JDBI annotations. The only thing that changed is which `Jdbi` instance you `attach()` them to.

This is the second time we've seen the payoff of clean DAO design. In challenge 10, swapping SQLite for Postgres barely touched the DAOs. In challenge 12, splitting reads from writes barely touched them either. **DAOs don't know about routing decisions; they just describe SQL operations.** The routing happens one layer up, in `CounterHelper`. That separation lets us scale orthogonally.
