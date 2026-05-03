# Challenge 9.5 — Containerize the Cluster

> **This is a tooling detour, not a challenge.** It doesn't introduce a distributed-systems concept — it replaces challenge 9's shell scripts with declarative container orchestration. The distributed-systems story picks back up in challenge 10. If you already know Docker and Docker Compose, you can skim this or skip to challenge 10.

## Problem

Challenge 9 left us with a working cluster but no reasonable way to manage it:

- **Four terminals open**, or a shell script redirecting stdout to files.
- **PID files** tracked by hand in `.pids/`, stale when a process dies.
- **No restart-on-crash** — if a JVM OOMs, it stays dead.
- **Machine-specific** — the setup works only on a machine that has Java 17 + Maven + nginx installed with the versions we expect. Hand this repo to a colleague and they'll spend an hour fighting Homebrew before they see anything.
- **No declarative description** of what the cluster *is*. The shell script is an imperative recipe: "start this, then that, then nginx." There's no single artifact that says "the system consists of these components, with these relationships."

None of these are *distributed-systems* problems. They're **tooling** problems — the gap between "we built it" and "we can run it reliably and hand it to someone else." This is what Docker and Docker Compose solve.

## Product

**Same code. Same topology. Different substrate.** Three identical containers running the counter JAR, one nginx container in front, one shared Docker volume holding the SQLite file, one private Docker network connecting them. The whole cluster is described in one file (`docker-compose.yml`).

Operator experience:

| What you used to do | What you do now |
|---|---|
| Open 4 terminals, run `java -jar` three times + `nginx` | `docker compose up` |
| `tail -f logs/*.log` | `docker compose logs -f` |
| `kill $(cat .pids/counter-8082.pid)` + manually rerun | `docker compose restart counter-2` (or it auto-restarts on crash) |
| Edit port numbers in 3 config files to add a 4th instance | `docker compose up --scale counter=4` (with minor compose tweaks) |
| Clean up `logs/`, `shared-data/`, `.pids/` | `docker compose down -v` |

## Programming

### Run-time — What's Actually Happening

![Three client processes outside the cluster reach the LB container on localhost:9000. The LB container and three counter containers (each with cache models, metric models, and admin port :8081) all sit inside the cluster private network (bridge). Each counter's admin port is published on a distinct host port (18081, 28081, 38081) so an external Prometheus process can scrape metrics from each instance. The shared-data named volume is mounted at /app/shared-data into each counter container, backed by block storage on the host filesystem at ./shared-data/counters.db — outside the Docker network.](./diagram.png)

Same four processes as challenge 9, plus the Docker daemon supervising them. Same shared-state story: DB is one, caches are three.

#### Data

Same data shapes as challenge 9. Two new **configuration models** replace the three per-port YAML configs:

- **`Dockerfile`** — how to package a counter into an image. Serialized instructions: "base image, copy JAR, copy config, set entrypoint."
- **`docker-compose.yml`** — declarative description of the whole cluster: which images run, how many replicas, which env vars per container, which volumes, which network.

Both pass the "could be sent over the network as data" test — they're pure state, no runtime behavior.

The application config collapses from **three files** (`config-8080/8082/8084.yml`) to **one** (`config.yml`). Each container gets the same file; per-container differences come through environment variables (`INSTANCE_NAME=counter-2`) interpolated at startup via `${INSTANCE_NAME:-counter}` syntax. This is the first container-enabled simplification: **port namespaces are per-container**, so the app can always bind `:8080` inside, and each container has its own `:8080` that doesn't collide with siblings.

#### Process

Still four application processes (3 JVMs + nginx), unchanged from challenge 9 in behavior. What's new:

- Each process runs **inside its own container** — a Linux namespace + cgroup isolating its filesystem, network, PID space, and process view. From inside `counter-1`, the only process it sees is its own `java`.
- The **Docker daemon** is a new supervising process. It starts containers, restarts them per policy, mediates the virtual network, manages volumes.
- nginx now addresses upstreams by **service name** (`counter-1`, `counter-2`, `counter-3`) instead of localhost + port. Docker's embedded DNS resolves names to container IPs.

#### Infrastructure

```
             ┌── external Prometheus (on another machine) ──┐
             │  scrapes <host>:18081, :28081, :38081        │
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
│   │                 ┌─────────────┐ │        │        │           │   │
│   │                 │  nginx-lb   │ │        │        │           │   │
│   │                 │  (:80)      │ │        │        │           │   │
│   │                 └──────┬──────┘ │        │        │           │   │
│   │                        │  round-robin    │        │           │   │
│   │              ┌─────────┼─────────┐       │        │           │   │
│   │              ▼         ▼         ▼       │        │           │   │
│   │        ┌──────────┐┌──────────┐┌──────────┐                   │   │
│   │        │counter-1 ││counter-2 ││counter-3 │                   │   │
│   │        │ app :8080││ app :8080││ app :8080│                   │   │
│   │        │admin:8081││admin:8081││admin:8081│                   │   │
│   │        │   ▲      ││   ▲      ││   ▲      │                   │   │
│   │        │   └──────┼┼───┼──────┼┼───┼──────┘ ◀── admin traffic │   │
│   │        │ cache    ││ cache    ││ cache    │     from host     │   │
│   │        │ metrics  ││ metrics  ││ metrics  │     ports         │   │
│   │        └─────┬────┘└─────┬────┘└─────┬────┘                   │   │
│   │              │           │           │                        │   │
│   └──────────────┼───────────┼───────────┼────────────────────────┘   │
│                  │           │           │                            │
│                  │  /app/shared-data (volume mount)                   │
│                  ▼           ▼           ▼                            │
│   ┌──────────────────────────────────────────────┐                    │
│   │  shared-data (named volume)                  │                    │
│   │  block storage on host filesystem            │                    │
│   │  counters.db + -wal + -shm                   │                    │
│   └──────────────────────────────────────────────┘                    │
│                                                                       │
└───────────────────────────────────────────────────────────────────────┘

Published host ports:
  :9000   → nginx-lb :80        (public client traffic)
  :18081  → counter-1 :8081     (admin — Prometheus scrape target)
  :28081  → counter-2 :8081     (admin — same)
  :38081  → counter-3 :8081     (admin — same)
```

- **Host exposes four ports**:
  - `:9000` → nginx's `:80` — public, client traffic.
  - `:18081` → counter-1's admin `:8081`
  - `:28081` → counter-2's admin `:8081`
  - `:38081` → counter-3's admin `:8081`
  Client traffic stays behind nginx (one public entry point). Admin traffic is published per-container so an external Prometheus on another machine can scrape each instance directly. The choice of "publish admin ports" is a design trade-off — see the notes entry below.
- **Private bridge network `cluster-net`** — all four containers share it, addressed by name. Anything *not* published to the host (inter-container traffic, client-to-counter routing, DNS) stays invisible from outside.
- **Volume mount `shared-data`** — Docker-managed storage, mounted at `/app/shared-data` inside each counter container. Survives `docker compose down`; destroyed only by `docker compose down -v`.
- **Docker daemon** — the new process supervisor, owns the image cache, manages lifecycle, handles restarts.

### Compile-Time — How to Implement It

One small Java change plus three new artifacts.

#### The library: `CounterApplication` (updated)

Two-line change: enable environment-variable substitution in config files so `${INSTANCE_NAME:-counter}` works.

```java
bootstrap.setConfigurationSourceProvider(
        new SubstitutingSourceProvider(
                bootstrap.getConfigurationSourceProvider(),
                new EnvironmentVariableSubstitutor(false)));
```

Without this, Dropwizard reads the config YAML literally. With it, `${VAR:-default}` expands from the process's environment. `INSTANCE_NAME` comes from `docker-compose.yml`, one per container.

#### The model: `Dockerfile` (new)

```dockerfile
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY target/challenge-9-5-counter-1.0-SNAPSHOT.jar app.jar
COPY config.yml config.yml
EXPOSE 8080 8081
RUN mkdir -p /app/shared-data
ENTRYPOINT ["java", "-jar", "app.jar"]
CMD ["server", "config.yml"]
```

An image is a **frozen snapshot**: the JAR + JRE + config + directory structure, baked together. Every container spawned from it is identical at startup. This is immutability as a deployment primitive — you don't "update a running server," you build a new image and replace containers.

`EXPOSE 8080 8081` is documentation only; it tells readers (and tooling) which ports the container binds. Actually publishing them to the host happens in `docker-compose.yml`.

#### The model: `docker-compose.yml` (new)

Declarative. Describes **desired state**; Docker figures out how to achieve and maintain it:

```yaml
services:
  counter-1:
    build: .
    image: counter:9.5
    environment:
      INSTANCE_NAME: counter-1
    volumes:
      - shared-data:/app/shared-data
    networks: [cluster-net]
    restart: unless-stopped
  # counter-2, counter-3 follow the same shape

  nginx:
    image: nginx:1.25-alpine
    ports: ["9000:80"]
    volumes:
      - ./nginx.conf:/etc/nginx/nginx.conf:ro

volumes:
  shared-data:

networks:
  cluster-net:
```

Four things to notice:

1. **`restart: unless-stopped`** — if the container dies (OOM, segfault, uncaught exception), Docker restarts it. No more "JVM crashed, cluster quietly degrades until someone re-runs the script."
2. **`depends_on:`** (used on counter-2 and nginx) — declares startup order, not health. Compose starts counter-1 before counter-2 and all counters before nginx, but doesn't wait for them to be *ready*. Nginx's `proxy_next_upstream` failover covers the brief window where it might try to reach a counter that hasn't opened port 8080 yet.
3. **Named volume** (`shared-data:`) — no host path baked in. The compose file is portable across machines; Docker creates the volume in its own managed storage.
4. **Private network** (`cluster-net`) — isolation by default. Only nginx publishes a port to the host; counters are invisible from outside the Docker network.

#### The model: `nginx.conf` (updated)

Three line-level changes from challenge 9:

```nginx
upstream counters {
    server counter-1:8080 max_fails=3 fail_timeout=10s;
    server counter-2:8080 max_fails=3 fail_timeout=10s;
    server counter-3:8080 max_fails=3 fail_timeout=10s;
}

resolver 127.0.0.11 valid=10s;  # Docker's embedded DNS
```

Upstreams by **service name**, not localhost+port. Same port (`:8080`) inside every container. nginx uses Docker's embedded DNS resolver at `127.0.0.11` to turn names into container IPs, re-resolving every 10s so a restarted container's new IP is picked up quickly.

#### The model: `config.yml` (collapsed from three files)

One config, same inside every container. Two env-var interpolations:
- `INSTANCE_NAME` (log tagging) — set per-container by compose.
- Everything else is shared.

The per-port configs are gone because ports are now per-container, not per-host.

## Run It

Prereqs: Docker Desktop (or equivalent). Java 17 + Maven to build the JAR.

```bash
cd challenge-9.5-counter-containerized
mvn -q -DskipTests package           # build the JAR on the host
docker compose up --build -d         # build image + start cluster
```

### Basic round-robin check

```bash
for i in 1 2 3 4 5 6 7 8 9; do
    curl -s http://localhost:9000/api/v1/counters > /dev/null
done

for c in counter-1 counter-2 counter-3; do
    echo -n "$c: "
    docker compose logs $c | grep -c '"method":"GET"'
done
```

You should see ~3/3/3.

### Trace a request across LB and upstream

```bash
REQ_ID=$(curl -s -v -H "X-User-Id: demo" -X PUT \
    http://localhost:9000/api/v1/counters/video-funny-cats/vote \
    -H "Content-Type: application/json" -d '{"vote":"LIKE"}' 2>&1 \
    | grep -i "< x-request-id" | awk '{print $NF}' | tr -d '\r\n')
echo "ID: $REQ_ID"
docker compose logs | grep "$REQ_ID"
```

One line in exactly one counter's log, tagged with `"instance":"counter-N"` (coming from `INSTANCE_NAME` in compose).

### Cache-divergence demo

Counter containers aren't reachable from the host (that's the isolation feature), so direct reads go through a curl container attached to the same Docker network:

```bash
CURL="docker run --rm --network challenge-95-counter-containerized_cluster-net curlimages/curl:latest"

# Warm each cache
for c in counter-1 counter-2 counter-3; do
    $CURL -s http://$c:8080/api/v1/counters/video-music-mix > /dev/null
done

# Vote on counter-1 only
$CURL -s -H "X-User-Id: zoe" -X PUT \
    http://counter-1:8080/api/v1/counters/video-music-mix/vote \
    -H "Content-Type: application/json" -d '{"vote":"LIKE"}'
echo

# Read from each within TTL
for c in counter-1 counter-2 counter-3; do
    echo -n "$c: "
    $CURL -s http://$c:8080/api/v1/counters/video-music-mix
    echo
done
```

counter-1 returns `likes=1`. counter-2 and counter-3 return stale `likes=0`. **Same bug as challenge 9, same reason — caches are per-process (now per-container), the DB is shared.**

### Crash and restart

```bash
docker compose restart counter-2     # simulated redeploy
docker kill counter-2                # abrupt kill — auto-restart kicks in
                                     #   (because `restart: unless-stopped` in compose)
```

`docker ps` will show counter-2 as "restarting" then back up. No shell-script management needed.

### Stop everything

```bash
docker compose down      # keeps the volume (DB persists across runs)
docker compose down -v   # also deletes the volume (fresh start)
```

## What's Missing

This challenge deliberately doesn't introduce new *architectural* problems. The distributed-systems issues from challenge 9 are unchanged:

1. **Cache divergence** — still there, same reason. Fixed in challenge 11 (Redis).
2. **SQLite single-writer ceiling** — still there. Fixed in challenge 10 (Postgres).
3. **nginx is still the SPOF** — containerizing it doesn't make it redundant. Real setups run LB pairs or use cloud-managed LBs.

Containerization-specific gaps we're leaving on the table:

- **Multi-stage Dockerfile** — building Maven inside the image instead of relying on the host. Cleaner, slower to iterate, not worth it for a demo.
- **Image registry** — we build locally and never push. In a team setting, images live in a registry (Docker Hub, ECR, GHCR).
- **Active health checks** — compose supports `healthcheck:` that probe the container on a schedule. We didn't wire them in; nginx's passive checks cover the basics.
- **Resource limits** — no `mem_limit`, no `cpus:`. Under load, a runaway JVM can starve its siblings because they share host resources.
- **Secrets** — nothing here has credentials yet. Starting in challenge 10, we'll need to handle DB passwords cleanly (Docker secrets, env var files, external config).
- **Orchestration beyond one host** — Compose is single-host. Multi-host clusters need Kubernetes or Nomad. Out of scope.

## Notes

### Containers aren't VMs

A container isn't a small VM — it's a normal Linux process with restricted **namespaces** (what it sees: own filesystem, own PID view, own network) and **cgroups** (how much it can use: memory, CPU). There's no second kernel, no virtualization, no separate boot. On macOS and Windows, Docker Desktop runs a single Linux VM, and all "containers" are processes inside *that* VM — which is why file I/O across the host boundary is slower than native on those platforms.

### Named volume vs bind mount — why we chose named

We mount the SQLite file into the containers via a **named volume** (`shared-data:`), Docker-managed storage. The alternative is a **bind mount** — `./shared-data:/app/shared-data` — pinning a host directory into the container, the way challenge 9 had it on disk. Both work. The tradeoffs:

| | Named volume | Bind mount |
|---|---|---|
| Portability across machines | ✓ No host paths in compose file | ✗ Baked-in path, moves awkwardly |
| Permissions | ✓ Docker sets up UID/GID correctly | ✗ Inherits host UID; on macOS with default UID 501 and container UID 1000, you'll hit permission mismatches |
| Performance (macOS/Windows) | ✓ Lives in Docker's VM filesystem | ✗ Crosses the VM boundary via a translation layer; slower |
| Host visibility | ✗ File is under `/var/lib/docker/volumes/...`, owned by Docker | ✓ `sqlite3 ./shared-data/counters.db` works directly |
| Backup/inspect | Requires `docker cp` or a helper container | Just copy the directory |
| Production use | Preferred for databases, stateful services | Preferred for dev workflows mounting source code for hot-reload |

For a DB in production, **named volume almost always wins.** For development where a human is poking at files from outside the container, bind mounts are more convenient.

Here we picked named volume because (a) it's what production does, (b) it sets up the mental model for challenge 10's move to "DB lives in its own managed process you talk to, not a file you can touch," and (c) inspecting the DB is still easy via `docker compose exec counter-1 sqlite3 /app/shared-data/counters.db "SELECT * FROM counters;"`.

### The image is immutable; the container is ephemeral

Two ideas worth separating:
- **Image** — the frozen artifact built by `docker build`. It's a tarball of a filesystem + startup metadata. Two containers started from the same image have identical starting state.
- **Container** — a running instance of an image, plus whatever state accumulated at runtime (logs in stdout, writes to ephemeral layers, mounted-in files). When the container is removed, that state goes away — *unless* it was in a volume.

This is why the named volume matters: the containers are disposable, but the DB has to outlive any one container. By putting `/app/shared-data` on a volume, the DB survives container restarts, image rebuilds, and replacements. It's the first appearance of a principle that becomes central in phase 3+: **stateless compute, stateful storage, kept separate.**

### Declarative vs imperative

Challenge 9's `start-cluster.sh` is imperative: "do these things in this order." Challenge 9.5's `docker-compose.yml` is declarative: "the system consists of these parts." Docker handles the mechanics — *how* to make reality match.

This is the same philosophy as Kubernetes manifests, Terraform configs, SQL (declarative: "I want these rows," not "do this index scan"), and CSS. Most modern ops tooling prefers declarative because:
- **Convergence** — if the system drifts from the desired state, the tool can nudge it back. Restart-on-crash is a primitive version of this.
- **Idempotence** — running `docker compose up` twice is safe; running a shell script twice may not be.
- **Diffability** — you can see *what changed* by comparing two versions of the manifest.
- **Tooling** — once the description is structured data, machines can analyze it (linting, graphing, policy checks).

The shell script in challenge 9 couldn't do any of these cleanly.

### What Docker Compose does *not* give you

It's single-host. If the machine dies, the whole cluster dies. The network is a private bridge, not a mesh across machines. There's no consensus, no leader election, no distributed scheduling. All of that is Kubernetes territory.

Compose is the right tool for:
- Local development with multiple services
- Simple single-host production deployments
- Integration testing setups

It's the wrong tool for production clusters that need multi-host HA. We're using it here because **one concept at a time** — challenge 9.5 is "declarative orchestration," not "distributed orchestration."

### Why `counter-1`, `counter-2`, `counter-3` rather than replicas?

Compose supports `deploy: replicas: 3` to spawn N copies of one service. I didn't use it here because:
- Each replica would need a unique `INSTANCE_NAME`, which replicas-mode makes awkward (they share env).
- Named containers are easier to reference in logs, `docker exec`, and the upstream list in nginx.
- The pedagogical goal is "here are three distinct containers that happen to be identical" — naming them makes the story clearer than "N anonymous replicas."

In real deployments with an orchestrator like Kubernetes, you'd use the replicas model plus service discovery (not static upstream lists). That's phase 3+ material.

### How you address a container depends on where you're calling from

Containerization creates **rings of reachability**. What [IP, port] you dial to reach a counter depends on which ring you're calling from:

| Caller's location | How to reach `counter-2`'s admin port | Why |
|---|---|---|
| **Another container on `cluster-net`** | `counter-2:8081` | Docker's embedded DNS resolves the service name to the container's private IP; container-local port |
| **A process on the cluster host itself** | `localhost:28081` | Host port 28081 is published and mapped to counter-2's container-local `:8081` |
| **Another machine** | `<cluster-host-IP>:28081` | Same published-port mapping — packets reach the host, Docker NATs them to counter-2's `:8081` |

**Each ring sees less than the one inside it.** Containers are isolated from the host; the host is isolated from other machines. **Publishing a port is punching a hole through one ring.** Our compose file punches four holes: nginx's `:9000` (public client), plus `:18081/:28081/:38081` (per-counter admin, for external scraping). Everything *not* published — inter-container traffic, the LB's internal routing, Docker's embedded DNS — stays behind the network boundary and is invisible from outside.

### Publishing admin ports: a deliberate trade-off

We publish each counter's admin port to the host on a distinct host port (`18081`, `28081`, `38081`) so that an external Prometheus (running on another machine, not on cluster-net) can scrape each instance directly. The trade-off this makes:

**Gives up:** the port-uniformity win from containerization. Inside each container the admin port is still uniformly `:8081`, but on the host surface we're back to the pre-container "pick unique ports per instance" story for the three admin endpoints. Adding a fourth counter means picking `48081` or similar and wiring it into Prometheus's scrape config.

**Keeps:** client traffic behind one public entry (nginx `:9000`), name-based addressing inside the cluster, containerized packaging.

**Gains:** Prometheus can live anywhere with network access to the cluster host — no requirement to run it as another container on `cluster-net`.

**Alternatives we didn't take:**
- **Prometheus inside cluster-net** — scrapes `counter-1:8081`, `counter-2:8081`, `counter-3:8081`. Clean, keeps port uniformity, but requires Prometheus to be co-deployed with the cluster.
- **Single aggregation endpoint on nginx** — e.g., `:9091/metrics/counter-1` etc., with nginx fanning out to the admins. Cuts external scrape targets to one per host. More config, more coupling between the LB and observability.
- **Service discovery** — Prometheus queries Consul / K8s / DNS SRV for current targets. Scales to many hosts and ephemeral instances. Overkill for a single-host cluster.

This is the **pragmatic middle ground** for small fleets (3–10 hosts, stable instances, external monitoring). Once instances are numerous or dynamic, you'd reach for service discovery.
