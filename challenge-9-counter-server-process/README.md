# Challenge 9 — Multiple Server Instances + Load Balancer

## Problem

One counter server is a **single point of failure** and a **single throughput ceiling**. If the JVM crashes, gets OOM-killed, or goes down for a deploy, every user sees errors. If traffic grows past what one box can handle, vertical scaling runs out eventually. You need more than one copy of the server.

But the moment you run two copies of a stateful service, a new problem appears: **their state diverges.** The caches each instance builds up are private; writes on one instance don't invalidate caches on the others. This challenge runs three instances. All three share one SQLite file, so **the database agrees** — a vote written by one instance is immediately visible to the others. But each instance keeps its **own in-memory cache**, and those caches don't know about writes that happened on a sibling instance. The result: you can read the same counter from two instances seconds apart and get two different answers, even though the DB holds one correct value.

We leave that bug in on purpose. Challenge 11 fixes it by moving the cache out of process (Redis). Challenge 10 separately replaces SQLite with Postgres — not because SQLite is broken here, but because sharing a SQLite file locks us to a single machine, which is the next wall we'll hit.

## Product

Three identical counter servers running as three separate JVM processes, on ports 8080, 8082, 8084. One nginx instance in front on port 9000, doing round-robin load balancing. All three servers share one SQLite file at `./shared-data/counters.db` (WAL mode lets multiple processes coexist as long as they're on the same local filesystem).

Clients always hit `http://localhost:9000`. They never know or care which upstream served the request. The LB injects an `X-Request-Id` that the counter's `RequestIdFilter` honors, so a single request can be traced from the LB log to exactly one upstream's log.

## Programming

### Run-time — What's Actually Happening

![Three client processes hit one nginx LB on :9000. nginx round-robins to three counter processes on :8080, :8082, :8084, each with its own admin port (:8081, :8083, :8085), per-instance Caffeine cache models, and per-instance metric models. All three processes hold file handles on one shared SQLite file containing the counters table, user_votes table, and the created_at/counter_id composite index.](./diagram.png)

#### Data

Same data shapes as challenge 8 — counters, user votes, log records, metric samples, request IDs. Three additions worth naming:

- **Routing decisions** (inside nginx). Each incoming request triggers a choice: "which upstream gets this?" nginx answers via round-robin over the `upstream counters` pool. The decision itself isn't persisted; it lives in nginx's request-processing state for one request.
- **Per-instance cache state**. Each counter process now has its own Caffeine cache. These caches are private and **diverge over time** — that's the whole point of the demo.
- **Per-instance metric state**. Each counter process has its own `MetricRegistry` holding timers, counters, and reservoirs. Hitting `http://localhost:8081/metrics` vs `:8083/metrics` vs `:8085/metrics` returns three different snapshots — each instance only knows about the requests *it* served. Unlike the cache, this isn't a bug — it's how metrics are supposed to work per-instance. The aggregation happens in the scraper (Prometheus pulls from all three `/metrics` endpoints and sums/averages them into cluster-wide views).

Data that used to be process-local now has two meanings:

| Data | Where it lives | Shared across instances? | Is this a problem? |
|---|---|---|---|
| Counter rows, user votes | `./shared-data/counters.db` (one file) | **Yes** — all three instances read/write the same SQLite file | — |
| Caffeine cache entries | JVM heap | **No** — each instance has its own | **Yes, bug.** A write on instance A doesn't invalidate B's cache. Fixed in challenge 11. |
| Metric samples (registry) | JVM heap | **No** — each instance has its own | No — this is how metrics are *supposed* to work per-instance. The aggregation happens externally in the scraper (Prometheus pulls all three `/metrics` endpoints). |
| Log lines | stdout / log files | **No** — each instance writes its own stream | No — aggregation happens externally in the log shipper (which fans in to one searchable store). Request-ID MDC makes cross-instance traces possible. |

That split is what creates the visible bug: *DB state agrees, cache state doesn't.* Metrics and logs are also per-instance, but they're designed to be — scraping and log-shipping are the intended aggregation points.

#### Process

```
client
  │
  ▼
nginx :9000      ← picks one upstream round-robin, sets X-Request-Id
  │
  ├─────→ counter-8080   (JVM)  →  Caffeine cache  ─┐
  ├─────→ counter-8082   (JVM)  →  Caffeine cache  ─┤──→  shared SQLite file
  └─────→ counter-8084   (JVM)  →  Caffeine cache  ─┘
```

Each counter process internally still has the same pipeline as challenge 8: RequestIdFilter → Resource → @Timed → CounterHelper → DAO → SQLite. What's new is that **there are three of them**, and a new process (nginx) decides which one each request lands on.

#### Infrastructure

- **Four OS processes**: one nginx, three JVMs.
- **Four application ports**: 9000 (LB, public), 8080/8082/8084 (counter instances, upstreams).
- **Four admin ports**: 8081/8083/8085 (one per counter instance). Clients never hit these; they're for metrics scraping and health checks.
- **One shared SQLite file** at `./shared-data/counters.db`, plus its WAL sidecar (`-wal`) and shared memory file (`-shm`). All three counter processes hold file handles on these.
- **Four log streams** to `logs/counter-{8080,8082,8084}.log` and `logs/nginx-error.log`.

### Compile-Time — How to Implement It

**Zero Java code changes** from challenge 8. This challenge is entirely about:

1. **Running the same JAR three times** with three config files that differ only in ports.
2. **Adding an nginx config** that points at the three upstreams.
3. **Adding shell scripts** to start and stop the cluster.

The fact that no application code needed to change is a statement about challenge 8's design: we didn't do anything that assumed "only one instance exists." SQLite file-sharing, per-instance caches, per-instance metrics — all of it composes into a multi-instance setup by doing nothing special. **The shared-state problems that emerge are consequences of running multiple instances of any stateful service; they're not bugs we introduced.**

No new Java models or libraries — every existing class from challenge 8 is reused unchanged. What's new lives outside Java: three YAML config files, one nginx config, two shell scripts. By the quick test from the root README (*"if it could be written to a database or sent over the network as JSON, it's a model"*), the config files pass — they're pure state. The shell scripts don't — they do work. See the "Configuration is a model, but a distinctive kind" note below for why these configs live in their own sub-category of models.

#### The model: `config-8080.yml` / `config-8082.yml` / `config-8084.yml` (new)

Three almost-identical Dropwizard configs — per-instance application config: app port, admin port, DB URL, log fields. The deltas across the three files:

- Application port: `8080` / `8082` / `8084`
- Admin port: `8081` / `8083` / `8085`
- `additionalFields: { instance: "counter-NNNN" }` in the JSON log layout — tags every log line with which instance produced it, which matters when tailing all three streams together.

All three point to **the same SQLite file**: `jdbc:sqlite:./shared-data/counters.db`. `busy_timeout` bumped from 3s to 5s to absorb inter-process contention on the write lock.

#### The model: `nginx.conf` (new)

LB config: upstream pool, routing rules, headers to inject. Three important directives:

```nginx
upstream counters {
    server 127.0.0.1:8080 max_fails=3 fail_timeout=10s;
    server 127.0.0.1:8082 max_fails=3 fail_timeout=10s;
    server 127.0.0.1:8084 max_fails=3 fail_timeout=10s;
}

location / {
    proxy_pass http://counters;
    proxy_set_header X-Request-Id $request_id;
    proxy_next_upstream error timeout http_502 http_503 http_504;
}
```

- **`upstream counters`** — the pool. Default algorithm is round-robin. Other options are `least_conn`, `ip_hash` (sticky by client IP), `hash $some_variable consistent;` (consistent hashing).
- **`max_fails` / `fail_timeout`** — passive health check. If an upstream fails 3 requests within 10s, nginx stops sending it traffic for 10s. Real setups add active health checks (periodic `GET /healthcheck`).
- **`proxy_next_upstream`** — failover. If upstream A is down when nginx tries to connect, it automatically retries on upstream B. This is what turns "many processes" into actual high availability.
- **`X-Request-Id $request_id`** — nginx generates a UUID per request. The counter's `RequestIdFilter` (from challenge 8) honors it, so the same ID appears in nginx's access log and the upstream's app log. Cross-correlation for free.

#### The library: `start-cluster.sh` / `stop-cluster.sh` (new)

Shell scripts — libraries by the framework (they do work: start processes, track PIDs, send signals), just not Java libraries. A rigorous read of the model/library split stretches past one language.

Primitive process supervision:
- `start-cluster.sh` starts each JVM in the background, saves PIDs to `.pids/`, redirects stdout to `logs/`.
- `stop-cluster.sh` reads PIDs and sends SIGTERM.
- No restart-on-crash. No health-aware routing beyond nginx's `max_fails`. No graceful shutdown.

This is **deliberately crude.** In production, nobody runs services this way — you'd use Docker Compose, Kubernetes, or a process supervisor (systemd, supervisord). Those tools exist because managing a fleet of long-running processes is a real problem. Challenge 9.5 introduces Docker Compose to replace the shell scripts. Challenge 9's point is architectural (load balancing, shared state), not deployment (process management).

## Run It

Prereqs: Java 17, Maven, nginx (`brew install nginx` on macOS).

```bash
cd challenge-9-counter-server-process
mvn -q -DskipTests package
./start-cluster.sh
```

### Basic round-robin check

```bash
# Hit the LB 6 times
for i in 1 2 3 4 5 6; do curl -s http://localhost:9000/api/v1/counters > /dev/null; done

# Count how many requests each instance served
for p in 8080 8082 8084; do
    echo -n "counter-$p: "
    grep -c '"method":"GET"' logs/counter-$p.log
done
```

You should see roughly 2/2/2 split — nginx's round-robin in action.

### Trace a single request across LB and upstream logs

```bash
curl -v http://localhost:9000/api/v1/counters/video-funny-cats 2>&1 | grep -i "x-request-id"
# Grab the ID nginx assigned (e.g. abc123...), then:
grep abc123 logs/nginx-access.log logs/counter-*.log
```

One line in nginx's access log, one line in exactly one counter instance's log, same UUID on both.

### Demonstrate the cache divergence problem

This is the money demo — the reason challenge 11 needs to exist.

```bash
# Step 1: warm all three caches by reading once on each instance directly
for p in 8080 8082 8084; do
    curl -s http://localhost:$p/api/v1/counters/video-dev-tutorial | head -c 200
    echo " ← from $p"
done

# Step 2: vote on one instance (invalidates ONLY that instance's cache)
curl -s -H "X-User-Id: zoe" -X PUT http://localhost:8080/api/v1/counters/video-dev-tutorial/vote \
     -H "Content-Type: application/json" -d '{"vote":"LIKE"}'
echo

# Step 3: read from all three — within the 5-second cache TTL
for p in 8080 8082 8084; do
    curl -s http://localhost:$p/api/v1/counters/video-dev-tutorial | head -c 200
    echo " ← from $p"
done
```

Instance 8080 shows the new vote. Instances 8082 and 8084 return their stale cached view for up to 5 more seconds. **The DB agrees. The caches don't.** Depending on which upstream nginx routes the next read request to, the user gets a different answer to "what are the likes on this counter?"

### Kill an instance, watch the LB route around it

```bash
kill $(cat .pids/counter-8082.pid)

# Fire a bunch of requests — none should fail
for i in $(seq 1 20); do curl -s -w "%{http_code}\n" -o /dev/null http://localhost:9000/api/v1/counters; done
```

All 200s. nginx's `proxy_next_upstream` retries on a live instance. After 3 failures in 10s, it stops sending traffic to the dead instance entirely.

### Stop everything

```bash
./stop-cluster.sh
```

## What's Missing

Running multiple instances exposes three shared-state problems. Challenge 9 deliberately solves **none of them** — each problem motivates a later challenge.

1. **Cache divergence.** Per-instance Caffeine caches disagree for up to TTL seconds after a write that routes to one instance. For a 5-second TTL, two users clicking at the same moment can see inconsistent counts. **Fixed in challenge 11** (distributed cache / Redis).

2. **SQLite is a stepping stone, not a destination.** Sharing a SQLite file across processes works at demo scale (WAL mode + `busy_timeout`), but it caps write throughput at "one writer at a time" and breaks outright on network filesystems. **Fixed in challenge 10** (central Postgres).

3. **The LB is a new single point of failure.** We removed "the server" as SPOF by running three of them. But now "the LB" is the SPOF — if nginx dies, the whole system is unreachable. Real setups run LB pairs (keepalived + VRRP) or use a cloud-managed LB that's itself HA. Out of scope for this repo.

Also missing:

- **Active health checks.** nginx only notices a dead instance when requests to it fail. A real LB (HAProxy, AWS ALB) probes `/healthcheck` on a timer.
- **Graceful shutdown.** `stop-cluster.sh` sends SIGTERM, but Dropwizard's shutdown handlers aren't wired to drain in-flight requests cleanly.
- **Restart on crash.** If a JVM dies, it stays dead until someone runs the start script again.
- **Declarative process management.** Shell scripts that track PIDs in files are primitive. **Fixed in challenge 9.5** (Docker Compose).

## Notes

### Round-robin is the simplest algorithm, not always the best

nginx's default round-robin is stateless — request N goes to instance N mod 3, regardless of load. That's fine when instances are identical and requests are uniform. Alternatives:

- **`least_conn`** — pick the instance with the fewest active connections. Better for variable request durations.
- **`ip_hash`** / **`hash`** — send all requests from a given client (or with a given key) to the same instance. Enables stateful behavior like per-instance session caches. The cost is that failover is messier: if an instance dies, its "sticky" clients have to be re-hashed, blowing cache warmness.

Our counter service is stateless-ish (state lives in DB + per-instance cache), so round-robin is fine. If we cached user sessions in-process, we'd need sticky sessions and the cache divergence problem would be *worse*, not better.

### Horizontal scaling only works if the service is stateless (or state is externalized)

The word "stateless" in LB context means "this instance doesn't hold anything the other instances need." Our counter server *is* stateless in that sense after challenge 5 moved writes to SQLite — nothing user-facing lives in memory that's not in the DB. The Caffeine cache holds *derived* state (computable from the DB); losing it costs a DB roundtrip, not user data.

This is why the cache divergence bug is subtle but not catastrophic: the DB is the source of truth, and every cache entry has a 5-second TTL that eventually resolves the inconsistency. A system where primary state lived in memory (session tokens, shopping carts, game state) *cannot* be horizontally scaled this way. You'd either externalize the state (Redis for sessions, DB for carts) or pin users to specific instances with sticky sessions.

### The SPOF shifts, it doesn't disappear

Going from one server to three didn't remove the single point of failure — it moved it. Before: the server was the SPOF. After: nginx is the SPOF. Before: the JVM process was the SPOF. After: the box they all run on is the SPOF (all three JVMs + nginx are on localhost — one power outage takes everything out). **Horizontal scaling on one machine is a real improvement for software-crash resilience but does nothing for hardware-failure resilience.** That takes multi-machine deployment, which is deferred to later challenges.

### Why 3 instances, not 2 or 5?

- **2** — works, but doesn't demonstrate "round-robin distributes across N instances" viscerally. It's just alternating.
- **3** — smallest number where round-robin is visibly not-alternating, and where you can kill one and still have *plural* instances left (important for the "kill one, LB routes around it" demo).
- **5+** — noisier to tail logs, adds no teaching value.

### SQLite in WAL mode is the reason multi-process SQLite is even possible

Without WAL (`PRAGMA journal_mode=WAL`), every write takes an exclusive lock on the whole DB file. With WAL, writes go into a sidecar `.db-wal` file while readers continue reading from the main file. This makes concurrent readers + one writer feasible across processes. It's still "one writer at a time" — there's no path to true concurrent writes in SQLite — but it's enough to make this challenge run without constant `SQLITE_BUSY` errors.

If you see `SQLITE_BUSY` under hand-testing, it means two instances tried to write at exactly the same moment and one waited longer than `busy_timeout: 5000ms`. At demo scale this shouldn't happen. At production scale it would — which is another reason to move to Postgres in challenge 10.

### Configuration is a model, but a distinctive kind

By the quick test from the root README (*"if it could be written to a database or sent over the network as JSON, it's a model"*), config files pass cleanly — they're serialized, they represent pure state, they have no behavior. So YAML configs and `nginx.conf` are models, same category as the rows in our counters table.

But their **lifecycle** is different, and that difference matters:

| | Source-of-truth models (e.g. `counters` rows) | Config models (e.g. `config-8080.yml`) |
|---|---|---|
| Who authors them | The running system, in response to user actions | A human operator, before the system starts |
| When | Runtime | Pre-start |
| Where they live | Database | Version control + disk + (eventually) config services |
| Change requires | A write through the API | Usually a restart |
| Drift across instances is | A bug (solved by shared DB in this challenge) | Expected (each instance *should* have a different port) |

Naming this distinction matters because the same architectural moves happen to config data that happen to source-of-truth data. In challenge 10, we move source-of-truth models from local SQLite to a shared DB. In real-world distributed systems, config often makes the same move — from per-host files to a shared config service like Consul or etcd. **Same move, same reasons (single point of truth, dynamic updates without redeploy, consistency across instances), different category of data.**

### Why configs, not CLI args?

We could have parameterized the JAR to take `--port 8080 --admin-port 8081 --instance-name counter-8080` on the command line. We used three config files instead because Dropwizard's config YAML is already how configuration is expressed, and adding CLI flag parsing for a demo is scope creep. In production you'd have *one* config template and inject environment-specific values (via Consul, env vars, or templating). For three instances that differ only in two integers, three files is fine.

### What the LB log looks like

```
127.0.0.1 - - [21/Apr/2026:11:30:15 -0700] "GET /api/v1/counters HTTP/1.1" 200 234 "-" "curl/8.7.1"
```

And the corresponding upstream log line:

```json
{"timestamp":"2026-04-21T18:30:15.123Z","level":"INFO","thread":"dw-22 - GET ...",
 "logger":"...","message":"...","mdc":{"requestId":"ac5f..."},"instance":"counter-8082"}
```

The `X-Request-Id` nginx generated becomes the `mdc.requestId` on the upstream side. That single ID is what makes "which instance served my request, and how long did each hop take?" answerable — worth the effort of wiring request IDs through in challenge 8.
