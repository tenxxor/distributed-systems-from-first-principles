# Challenge 10 — Central Postgres + Flyway Migrations

## Problem

Challenges 9 and 9.5 made three counter instances share one SQLite file. Worked at demo scale, caps us badly:

1. **SQLite is single-writer.** Three JVMs lining up behind one file lock means write throughput is whatever one SQLite writer can do. Under real concurrent voters, we'd hit `SQLITE_BUSY` errors fast.
2. **SQLite is local.** All three instances must be on the same machine with access to the same filesystem. Multi-host deployment is impossible — SQLite over NFS corrupts.
3. **Schema evolution is hand-rolled.** `CREATE TABLE IF NOT EXISTS` in application startup code. Works when the schema never changes; falls apart the first time you need to add a column, rename one, backfill data, or roll back.

The architectural fix is the move the industry settled on 30 years ago: **stop treating the database as a file and start treating it as a service**. Switch from embedded SQLite (library inside the app process, file I/O) to external Postgres (separate process, separate container, TCP-based client/server protocol).

This is the single biggest architectural shift in the repo so far. It's the transition from **shared storage** (all instances touch the same bytes) to **shared service** (all instances send requests to a process that owns the bytes). That shift is why distributed systems became possible — coordination moves into a DB server designed for concurrent clients (MVCC, row-level locks, transaction isolation) instead of being jury-rigged through OS file locks.

## Product

Four services in compose:

- **3 counter containers** — unchanged client-facing behavior. Now connect to Postgres over TCP instead of mounting a SQLite file.
- **1 nginx container** — LB, unchanged from 9.5.
- **1 Postgres container** — new. Runs `postgres:16`, stores data in a named volume (`pg-data`), reachable only inside `cluster-net` (not published to the host).

Schema changes are now **versioned Flyway migrations** in `src/main/resources/db/migration/`. Every counter server process runs `flyway.migrate()` at startup. Flyway takes a DB-level lock so three instances starting in parallel race safely — one runs the migration, the others see it already applied.

## Programming

### Run-time — What's Actually Happening

![Three client processes outside the cluster reach the LB container on localhost:9000. The LB container, three counter containers (each with cache models, metric models, and admin port :8081), and the Postgres container all sit inside the cluster private network (bridge). Each counter's admin port is published on a distinct host port (18081, 28081, 38081) for external Prometheus scraping. Counters connect to the Postgres process over TCP — they no longer touch persistent storage directly. Only Postgres has file handles into the pg-data named volume, mounted at /var/lib/postgresql/data inside the Postgres container, backed by Docker-managed host storage at /var/lib/docker/volumes/project_pg-data/_data outside the Docker network.](./diagram.png)

```
          ┌── external Prometheus (on another machine) ──┐
          │   scrapes <host>:18081, :28081, :38081       │
          └─────────────────────┬────────────────────────┘
                                │
┌─ host machine ────────────────────────────────────────────────────────┐
│                                                                       │
│   client                                                              │
│     │ http://<host>:9000                                              │
│     ▼                                                                 │
│   published host ports:  9000    18081    28081    38081              │
│                            │       │        │        │                │
│   ┌────────────────────────┼───────┼────────┼────────┼────────────┐   │
│   │                        │       │        │        │            │   │
│   │  cluster private network (bridge)                             │   │
│   │                        ▼       │        │        │            │   │
│   │                 ┌─────────────┐                                │   │
│   │                 │  nginx-lb   │                                │   │
│   │                 │  (:80)      │                                │   │
│   │                 └──────┬──────┘                                │   │
│   │                        │  round-robin                          │   │
│   │              ┌─────────┼─────────┐                             │   │
│   │              ▼         ▼         ▼                             │   │
│   │        ┌──────────┐┌──────────┐┌──────────┐                    │   │
│   │        │counter-1 ││counter-2 ││counter-3 │                    │   │
│   │        │ app :8080││ app :8080││ app :8080│                    │   │
│   │        │admin:8081││admin:8081││admin:8081│                    │   │
│   │        │ cache    ││ cache    ││ cache    │                    │   │
│   │        │ metrics  ││ metrics  ││ metrics  │                    │   │
│   │        └─────┬────┘└─────┬────┘└─────┬────┘                    │   │
│   │              │           │           │                         │   │
│   │              └───────────┼───────────┘                         │   │
│   │                          ▼  JDBC over TCP (:5432)              │   │
│   │                 ┌──────────────────────────────────┐           │   │
│   │                 │  postgres container              │           │   │
│   │                 │  (not published to host)         │           │   │
│   │                 │                                  │           │   │
│   │                 │  Postgres process :5432          │           │   │
│   │                 │  reads/writes                    │           │   │
│   │                 │  /var/lib/postgresql/data ◀──┐   │           │   │
│   │                 └──────────────────────────────┼───┘           │   │
│   │                                                │               │   │
│   └────────────────────────────────────────────────┼───────────────┘   │
│                                                    │                   │
│              volume mount at /var/lib/postgresql/data                  │
│                                                    │                   │
│         ┌──────────────────────────────────────────┴───────┐           │
│         │  pg-data (named volume)                          │           │
│         │  Docker-managed, lives on host filesystem        │           │
│         │  contains Postgres's tables, indexes, WAL, etc.  │           │
│         │  survives container restart; deleted by `down -v`│           │
│         └──────────────────────────────────────────────────┘           │
│                                                                        │
└────────────────────────────────────────────────────────────────────────┘
```

Key visual differences from 9.5:

- **Counter containers no longer mount a volume mount for storage.** They talk to Postgres process over TCP on `cluster-net` network.
- **Only Postgres process touches the pg-data volume mount.** Storage ownership is consolidated in one process.
- **Postgres process is not published to the host.** The DB is internal infrastructure; only the LB's `:9000` and the admin ports remain externally reachable.

#### Data

| Data | Where it lives | Shared across instances? | Is this a problem? |
|---|---|---|---|
| Counter rows, user votes, `flyway_schema_history` | Postgres (pg-data volume) | **Yes** — one source of truth, reached over TCP | — |
| Caffeine cache entries | JVM heap (per-container) | **No** | **Yes, still a bug.** A write on one instance doesn't invalidate the others' caches. Fixed in challenge 11. |
| Metric samples, log lines | Per-container | **No** | No — per-instance by design; aggregation happens externally. |

New piece of data in Postgres itself: the **`flyway_schema_history`** table. Flyway creates and maintains it; it records which migrations have been applied, their checksums, and install timestamps. This is how Flyway detects "already applied, skip" on subsequent boots.

One thing that *went away*: the `shared-data` named volume from 9.5. Counter containers no longer need a filesystem connection to the DB — they have a network connection instead.

#### Process

Five application processes now (3 JVMs + nginx + Postgres), up from four in 9.5. The shapes of requests they handle:

- **Client → nginx**: HTTP
- **nginx → counter**: HTTP (unchanged)
- **counter → Postgres**: JDBC/Postgres wire protocol over TCP (new) — no more file handles to a SQLite file

A new process step appears at counter startup: **Flyway migration**. Before JDBI starts, before the resource is registered, before any request can land, Flyway connects to Postgres, inspects `flyway_schema_history`, and applies whatever migrations haven't run yet. Three counters starting in parallel can all try this; Flyway's DB-level lock prevents duplicate application.

#### Infrastructure

- **Five containers**: 1 nginx, 3 counters, 1 Postgres.
- **Four published host ports**, unchanged from 9.5: 9000 (client), 18081/28081/38081 (admin).
- **Postgres is internal** — not published. Clients never reach it directly; only the counters do, via `postgres:5432` on `cluster-net`.
- **`pg-data` named volume** replaces `shared-data`. Owned by the Postgres container; no other container mounts it.
- **No filesystem mounts on counter containers** for the DB. Counter containers are now closer to truly stateless — nothing on their disk persists across restarts.

### Compile-Time — How to Implement It

Three new things to understand: Flyway migrations, DAO SQL updates, and a much smaller `CounterApplication`.

#### The model: `V1__initial_schema.sql` (new)

Flyway convention: filenames start with `V<number>__<description>.sql`. Every migration is a file in `src/main/resources/db/migration/`. Flyway sorts them by version number and applies each exactly once.

Our V1 does three things the hand-rolled `initializeDatabase()` from challenges 5-9.5 used to do:

1. **Create the tables** — `counters` and `user_votes`, with cleanups the Postgres version supports natively:
   - `created_at` is now `TIMESTAMPTZ` (was `INTEGER` epoch-seconds in SQLite).
   - `vote` is `SMALLINT` (was `INTEGER`; we only need -1 or +1).
   - `user_votes.counter_id` has a real foreign key to `counters.counter_id` with `ON DELETE CASCADE`. SQLite supports FKs but we didn't use them.
2. **Create the indexes** — same pagination index on `(created_at DESC, counter_id DESC)` from challenge 7.
3. **Seed the initial counters** — three sample videos with deterministic timestamps.

Why in a migration file, not in code?
- **Versioned.** We can see exactly what the schema was at each point in history.
- **Tracked in the DB.** The `flyway_schema_history` table records which version the DB is at.
- **Diff-able.** Every schema change is a file that gets code-reviewed like any other change.
- **Rollback-able** (in principle; we're not using Flyway undo migrations here). You can see the exact SQL that put the DB in this state.

#### The model: `config.yml` (updated)

```yaml
database:
  driverClass: org.postgresql.Driver
  url: jdbc:postgresql://${POSTGRES_HOST:-postgres}:5432/${POSTGRES_DB:-counters}
  user: ${POSTGRES_USER:-counter_app}
  password: ${POSTGRES_PASSWORD:-change_me_in_prod}
  maxSize: 10
  minSize: 2
  initialSize: 2
  validationQuery: "SELECT 1"
  validationQueryTimeout: 2s
  maxWaitForConnection: 3s
```

Five changes from the SQLite config:
1. **Driver class** — `org.postgresql.Driver` replaces `org.sqlite.JDBC`.
2. **URL is a network address**, not a file path. `postgres` is a DNS name inside the Docker network.
3. **Credentials** — Postgres requires auth. We pass them via env vars from compose.
4. **Pool size 10/2** — Postgres handles real concurrency, so the pool is meaningfully larger than SQLite's single connection.
5. **Validation query** — `SELECT 1` on checkout catches dead connections (e.g., Postgres restarted). Not strictly needed but cheap insurance.

#### The model: `docker-compose.yml` (updated)

- **New `postgres` service** with image `postgres:16`, env vars for DB name / user / password, a named volume for data, and a healthcheck.
- **`depends_on: postgres: condition: service_healthy`** on each counter — counters don't start until Postgres's `pg_isready` check passes. Without this, counters would try to connect before Postgres finished initializing its data directory.
- **`shared-data` volume removed** — no longer needed.
- **No port published for Postgres** — internal-only.

#### The library: `CounterApplication` (updated)

Previously did ~50 lines of `CREATE TABLE IF NOT EXISTS` + seed logic. Now delegates everything to Flyway:

```java
private void runMigrations(DataSourceFactory db) {
    Flyway flyway = Flyway.configure()
            .dataSource(db.getUrl(), db.getUser(), db.getPassword())
            .locations("classpath:db/migration")
            .load();
    flyway.migrate();
}
```

Four lines, does more than the 50 it replaces — and it handles concurrent instance startup, idempotency across restarts, and schema version tracking for free.

#### The library: `CountersDAO` (updated)

Most SQL unchanged — JDBI and plain SQL are portable between Postgres and SQLite. Two changes worth naming:

- **Type conversion at the DAO boundary.** The schema uses `TIMESTAMPTZ` but the Java layer still speaks epoch-seconds (`long createdAt`). We convert at the SQL level: `to_timestamp(:createdAt)` on insert, `EXTRACT(EPOCH FROM created_at)::BIGINT` on select. Nothing downstream has to change.
- **No `INSERT OR IGNORE` / SQLite quirks.** Our upsert uses `ON CONFLICT (cols) DO UPDATE` which Postgres and SQLite both support identically, so UserVotesDAO is unchanged.

#### New dependencies: `pom.xml`

```xml
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
    <version>42.7.3</version>
</dependency>
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-core</artifactId>
    <version>10.15.0</version>
</dependency>
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-database-postgresql</artifactId>
    <version>10.15.0</version>
</dependency>
```

`sqlite-jdbc` removed. `flyway-database-postgresql` is required separately in Flyway 10+ (the core library doesn't ship Postgres support by default anymore).

## Run It

Prereqs: Docker Desktop, Java 17, Maven.

```bash
cd challenge-10-counter-server-process
mvn -q -DskipTests package
docker compose up --build -d
```

Postgres takes a few seconds to initialize on first run; counters wait via the healthcheck. You should see "all containers up" after ~15s.

### Basic round-robin check

```bash
for i in 1 2 3 4 5 6; do
    curl -s http://localhost:9000/api/v1/counters > /dev/null
done

for c in counter-1 counter-2 counter-3; do
    echo -n "$c: "
    docker compose logs $c | grep -c '"method":"GET"'
done
```

~2/2/2 distribution.

### Verify Flyway ran

```bash
docker compose exec postgres psql -U counter_app -d counters \
    -c "SELECT version, description, success FROM flyway_schema_history;"
```

Should show V1 with `success = t`.

### Trace a request across LB and upstream

```bash
REQ_ID=$(curl -s -v -H "X-User-Id: demo" -X PUT \
    http://localhost:9000/api/v1/counters/video-funny-cats/vote \
    -H "Content-Type: application/json" -d '{"vote":"LIKE"}' 2>&1 \
    | grep -i "< x-request-id" | awk '{print $NF}' | tr -d '\r\n')
docker compose logs | grep "$REQ_ID" | grep vote.recorded
```

One line in exactly one counter's log, tagged with its `instance` field.

### Cache divergence demo (still broken — same as 9.5)

```bash
NET="challenge-10-counter-server-process_cluster-net"

# Warm each cache
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

# Read from each within TTL
for c in counter-1 counter-2 counter-3; do
    echo -n "$c: "
    docker run --rm --network $NET curlimages/curl:latest -s \
        http://$c:8080/api/v1/counters/video-music-mix
    echo
done
```

counter-1 returns `likes=1`. counter-2 and counter-3 return stale `likes=0`.

**The DB now agrees** (Postgres is the single source of truth) — but per-container caches still don't. That's what challenge 11 (Redis) fixes.

### Kill Postgres (the new failure mode)

```bash
docker stop postgres
for i in 1 2 3; do
    curl -s -w "HTTP %{http_code}\n" -o /dev/null http://localhost:9000/api/v1/counters
done
```

All three counters return 500s simultaneously — Postgres is the shared dependency. When it's down, none of the counters can serve reads or writes. **The LB can't route around this** (no healthy upstream exists). Postgres is now our SPOF; challenges later in phase 3 and 4 address this (read replicas, HA setups).

```bash
docker start postgres
sleep 12
for i in 1 2 3; do
    curl -s -w "HTTP %{http_code}\n" -o /dev/null http://localhost:9000/api/v1/counters
done
```

Recovery takes ~10-12s (connection pool validates and refreshes connections on next use).

### Schema migration demo

Create a new migration file:

```bash
cat > src/main/resources/db/migration/V2__add_description_column.sql <<EOF
ALTER TABLE counters ADD COLUMN description TEXT;
EOF
```

Rebuild and restart:

```bash
mvn -q -DskipTests package
docker compose up --build -d
```

Check the schema history:

```bash
docker compose exec postgres psql -U counter_app -d counters \
    -c "SELECT version, description, success FROM flyway_schema_history;"
```

```
 version |      description       | success
---------+------------------------+---------
 1       | initial schema         | t
 2       | add description column | t
```

Flyway applied V2 on startup and recorded it. On subsequent restarts, it sees V2 already installed and skips it. This is how you evolve schemas in production: write a new migration, deploy, each new instance runs whatever migrations it's missing on startup.

### Stop everything

```bash
docker compose down      # keep data volume
docker compose down -v   # wipe data volume (fresh DB next time)
```

## What's Missing

What's still broken:

1. **Cache divergence** — unfixed. Per-container Caffeine caches still disagree for up to TTL seconds after a write. **Fixed in challenge 11** (distributed cache / Redis).
2. **Postgres is a new single point of failure.** Kill the `postgres` container and the whole system is read-only-at-best. Real setups run Postgres in HA (primary + standby, automatic failover). Out of scope for challenge 10 — it shows up in phase 4.
3. **nginx is still a SPOF.** Same as 9.5.

What we're deliberately not doing yet:

- **Separate migration container.** Flyway currently runs inside each counter's startup path. In production you'd usually run migrations as a one-shot step in your deploy pipeline — keeps slow migrations from blocking every instance's boot. For our scale, bundled migrations are fine.
- **Proper secrets management.** `change_me_in_prod` is hardcoded in `docker-compose.yml`. Real production uses Docker secrets, env-var files, or external secret stores (Vault, AWS Secrets Manager, GCP Secret Manager).
- **Connection pool metrics / tuning.** Dropwizard-jdbi exposes pool stats to the metric registry (you can see them at `/metrics`) but we didn't build any dashboards. Real production watches pool utilization closely.
- **Prepared statement caching / query plan stability.** JDBC handles prepared statements automatically; Postgres caches plans; we didn't tune any of this.
- **Read replicas.** All three counters currently hit the primary for everything. Challenge 12 introduces read replicas.

## Notes

### Shared storage vs shared service — the architectural shift

This is the important conceptual move in this challenge.

**Shared storage** (challenges 5-9.5): every instance has direct access to the same bytes on disk. Coordination relies on OS-level file locks, which are primitive, local to a single filesystem, and scale poorly.

**Shared service** (challenge 10+): instances send requests to a separate process that owns the bytes. That process handles concurrency internally using proper DB primitives — MVCC, row-level locks, transaction isolation levels. Clients see a clean request/response interface instead of file-level coordination.

| | Shared storage | Shared service |
|---|---|---|
| Coordination | OS file locks | DB's internal concurrency control |
| Communication | File I/O syscalls | TCP (or Unix sockets) |
| Geographic reach | One machine's filesystem | Anywhere on the network |
| Concurrent writes | Serialized (SQLite single writer) | Proper concurrency (Postgres MVCC) |
| Client count | Limited by OS file handle behavior | Limited by DB's connection pool / tuning |
| Failure mode | File corruption possible if the writer crashes mid-write | DB maintains ACID even across crashes (WAL + fsync) |

This is the pattern for basically every piece of shared state in distributed systems. Databases, caches, message queues, object stores — all of them are "shared services" you reach over the network, not "shared storage" you read bytes from directly. Once you understand this, a huge amount of distributed-system architecture clicks into place.

### Where the file handle lives — library vs. server

The shift from SQLite to Postgres is, at the syscall level, a shift in **which process holds the file descriptor for the data files.**

With SQLite, the counter server process holds the fd directly:

```
Your server process
  ├── SQLite code (linked in as a library)
  │     └── holds fd for counter.db
  └── calls pwrite/fsync directly on that fd
```

The SQLite library is linked into the JVM. When it opens `counter.db`, the fd lands in *the counter process's* file descriptor table. Storage logic (parser, planner, executor, transaction manager, lock manager, buffer cache, WAL, page layout) all runs as in-process function calls. The kernel sees one process doing the I/O.

With Postgres, the fd moves to a different process:

```
Your server process              Postgres process
  └── libpq (client library)       └── holds fd for table files
        │                          └── calls pwrite/fsync
        │  TCP / Unix socket       │
        └──────────────────────────┘
```

The counter process links `libpq` (a thin client library) — but `libpq` doesn't open data files. It opens a **socket** to the Postgres process. Postgres holds the fds for the actual table files in *its own* fd table, and Postgres is the only process that calls `pwrite`/`fsync` on them. The counter process talks to Postgres over the wire protocol; Postgres talks to the kernel.

Same POSIX syscalls underneath in both cases. What changed is which process's fd table holds the entry, and therefore which process is responsible for concurrency control, caching, and durability. That move — fd ownership crossing the process boundary — is the architectural shift that makes "shared service" possible.

### Why the JDBC URL uses a hostname, not an IP

`jdbc:postgresql://postgres:5432/counters` — `postgres` is a DNS name, resolved by Docker's embedded DNS (on `127.0.0.11`) inside `cluster-net`. If the Postgres container restarts and gets a new IP, the name still resolves. The counters don't need to know anything about IPs or restart semantics; they just talk to "postgres" and Docker handles the rest.

This is the container-era equivalent of service discovery. In a multi-host deployment (Kubernetes, Nomad) the name would be resolved by a different DNS infrastructure but the principle is identical: **address services by stable name, not by ephemeral IP**.

### Flyway in each app instance vs a separate migration step

We bundle Flyway into the counter JAR and run it on startup. Three counter instances starting in parallel all call `flyway.migrate()`. This is safe because Flyway takes a DB-level lock (`pg_advisory_lock` on Postgres) — one instance wins, runs the migration, releases the lock. The other two see the migration already applied and proceed.

**Pros of bundled:**
- Simple. No extra service, no extra compose step.
- Any instance that starts knows it's running against a compatible schema (it just migrated itself).
- Works for simple migrations.

**Cons:**
- **Long migrations block startup.** If V5 takes 10 minutes to run, every instance that starts has to wait for that lock, fetch the result, and move on.
- **Schema changes tied to deploy.** You can't run a migration ahead of time, then separately deploy the new app version.
- **Accidental concurrent migration attempts.** Flyway handles it correctly, but the locking-and-waiting behavior can surprise you in tight restart loops.

In production you'd typically have a **separate migration container** that runs once in the deploy pipeline (e.g., in a Kubernetes Job), completes, and the app instances just start against the already-migrated schema. This decouples migration timing from instance startup.

For our scale and tooling, bundled is fine. Worth knowing the tradeoff.

### What "10 connections" actually means at runtime

`maxSize: 10` doesn't mean every counter opens 10 connections at startup and keeps them all busy. It's an **upper bound** on how many concurrent TCP connections the pool can hold. The actual count fluctuates with load:

| Layer | What happens |
|---|---|
| **App startup** | Hikari (Dropwizard's pool) opens `minSize: 2` connections. So 2 TCP connections from this counter process to `postgres:5432`. |
| **First request** | Resource handler asks JDBI for a connection. JDBI asks the pool. Pool hands out one of the 2 idle connections. After the request, it goes back to the pool — **the connection isn't closed**, it's reused. |
| **Burst of traffic** | 5 concurrent requests arrive. Pool is asked for 5 connections. It gives out the 2 idle, then opens 3 more (up to `maxSize: 10`). Now 5 connections are active, 5 still available. |
| **Pool exhaustion** | If 11 requests arrive concurrently, the 11th request waits up to `maxWaitForConnection: 3s` for a connection to become free. If none does in 3s, the request fails with a timeout. |
| **Idle period** | If traffic dies down, the pool keeps `minSize: 2` connections open and lets the rest go idle. Idle ones may eventually be closed (Hikari has its own timeouts). |

So "10 connections" means the pool can hold **up to 10 concurrent TCP connections, reused across requests**. It's not "10 connections per request" — most of the time, fewer than 10 exist. Think of it as a parking lot with 10 spaces: cars come and go, the lot is rarely full, but you can't have more than 10 cars in it at once.

**TL;DR:**

- `maxSize: 10` = up to 10 concurrent TCP connections per counter process to Postgres.
- They're not 10 ports — they're 10 connections, each using one ephemeral source port (kernel-assigned) and the same destination port 5432 on Postgres.
- They're reused across requests; the pool keeps them open between requests so we don't pay the connection-setup cost every time.
- Across 3 counters, the cluster opens at most 30 concurrent connections to Postgres. Postgres's default `max_connections` is 100, so we're well under.

### The connection pool is now meaningful

SQLite in challenges 5-9.5 had `maxSize: 1` because SQLite itself is single-writer — any pool bigger than 1 was wasted. Postgres supports real concurrency, so the pool can hold multiple live connections that different request threads check out independently.

We set `maxSize: 10, minSize: 2`. Per instance. Three instances means up to **30 connections total** against one Postgres server. Postgres's default `max_connections` is 100, so we're well under, but:

- If you scaled to 15 counter instances, you'd be at 150 connections — above the default. You'd either increase Postgres's `max_connections` (costs Postgres memory per connection) or introduce a connection pooler (PgBouncer). This is why connection pooling matters at scale.
- Idle connections consume Postgres memory. `minSize: 2` means each counter holds 2 connections open even at zero traffic; with 3 counters, that's 6 idle connections on Postgres. Rounding error here, but in large fleets it matters.

### Postgres as a new SPOF, shifting the failure story

In challenge 9, killing one counter instance was fine — the LB routed around it. In challenge 10, killing one counter instance is still fine. But killing **Postgres** takes down the whole system — every request that needs the DB fails.

This is the trade we made by moving to a shared service. Before, each SQLite write had to coordinate with whoever else was touching the file; now, the writes all go through one DB server. The failure footprint consolidated.

Solutions for this show up in phase 4:
- **Read replicas** (challenge 12) — spread read load across N Postgres instances so one failure isn't fatal for reads.
- **Failover / HA setups** — a standby Postgres that takes over when the primary fails. Tools: patroni, pg_auto_failover, cloud-managed Postgres (Cloud SQL, RDS) do this automatically.

Challenge 10 deliberately leaves this as a single Postgres. The pedagogical arc is: "add the shared service → see it become a SPOF → fix the SPOF in later challenges." Trying to introduce HA here would obscure the core move.

### Secrets in plaintext — a known gap

`docker-compose.yml` has `change_me_in_prod` sitting in plaintext. Terrible for production, fine for a demo. Real secret management options:

- **Docker secrets** — for Swarm-mode clusters. Secrets are encrypted at rest, mounted as files in the container.
- **Environment variables from a `.env` file** — not checked into git, read by compose at startup. Simple improvement over hardcoding.
- **Vault / AWS Secrets Manager / GCP Secret Manager** — dedicated secret stores, apps fetch secrets at startup (or use a sidecar).
- **Kubernetes Secrets** — base64-encoded, stored in etcd. Mountable as env vars or files.

We're skipping all of this for now. The deliberate flag in the code (`change_me_in_prod`) is a note to future readers: *I see this is a problem. It's not the problem we're teaching today.*

### The schema migration mental model

Versioned migrations replace "ad-hoc SQL commands sent to the DB." The change in perspective is worth naming:

- **Before (ad-hoc):** The DB's schema is whatever state it's in. You make changes by running `ALTER TABLE` statements in some order. History is whatever's in your shell's history file, or gone.
- **After (versioned):** The DB's schema is **whatever migrations V1, V2, V3, ... produce when applied in order.** The schema isn't a state; it's a function of the migration list. Apply the same list to any Postgres, you get the same schema.

This is the same mental shift as "code under version control" vs "ad-hoc script edits." You get:
- Reproducibility — any dev machine, CI, staging, prod all converge to the same schema.
- Reversibility (with effort) — you can roll back by running undo migrations.
- Auditability — schema changes are code-reviewed files, not random SQL run in a console.
- Confidence — you know exactly what state the DB is in by looking at `flyway_schema_history`.

Every production system with a DB uses something like this (Flyway, Liquibase, Rails migrations, Django migrations, Prisma migrate, Atlas, golang-migrate...). The tool differs; the principle is universal.

### What didn't change

Worth naming explicitly: the **application code is almost entirely unchanged.** `CounterHelper`, `CounterResource`, `RequestIdFilter`, the cache, the metrics, the logging, the request flow — all identical to 9.5. The only changes are:

- `CounterApplication.runMigrations()` replaces `initializeDatabase()`.
- The two DAOs have minor SQL tweaks at the boundary.
- Config points to a different DB.

**The service's architecture from the outside is identical.** That's because the abstraction we'd been using in challenge 8+ was already good — talk to a DB through a DAO, invalidate caches on write, pagination via opaque cursors. Changing the DB underneath that abstraction didn't ripple up into the business logic. This is the reward for paying attention to layering.
