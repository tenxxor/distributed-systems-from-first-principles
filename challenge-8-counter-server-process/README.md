# Challenge 8 — Observability

## Problem

The system works. But if something goes wrong at 2am, how do we know? If a user complains "the site feels slow," how do we confirm? If the cache hit rate dropped after a deploy, how do we measure it?

Until now, we've had exactly one signal: does the server respond to HTTP requests. Everything else — request rates, latencies, error patterns, slow queries, bad deploys — is invisible.

Challenge 8 makes the system **observable**. We wire up the three signals every production service needs: metrics (numbers over time), logs (structured events), and lightweight request tracing (request IDs that follow one call through the code).


## Product

No user-facing API changes. Observability is operator-facing — it's what keeps the humans running the system sane.

What's new for an operator:

- **`GET /metrics`** (admin port 8081) returns a big JSON blob with per-endpoint latencies, request counts, cache stats, JVM stats, and DB query timings.
- **`GET /healthcheck`** returns the status of every registered health check (DB reachability, etc.).
- **Application logs** are now JSON, one line per event, parseable by any log aggregation system.
- **Every log line from a request carries the request's unique ID**, so you can `grep` by ID to see the full sequence of events for one request.
- **Every HTTP response includes an `X-Request-Id` header** so clients can reference it in bug reports.


## Programming

### Run-time — What's Actually Happening

#### Data

Three new streams flow out of the counter process, each to a different destination:

**1. Metrics** (JSON at `http://localhost:8081/metrics`):

```json
{
  "timers": {
    "org.example.CounterResource.vote": {
      "count": 42,
      "mean": 0.00983,
      "p99": 0.01758
    }
  },
  "gauges": {
    "jvm.memory.heap.used": 13706224
  }
}
```

Aggregated, low-cardinality, good for dashboards and alerts.

**2. Logs** (JSON to stdout, one line per event):

```json
{
  "timestamp": "2026-04-21T00:37:28.515Z",
  "level": "INFO",
  "logger": "org.example.CounterHelper",
  "thread": "dw-22 - PUT /api/v1/counters/video-funny-cats/vote",
  "message": "vote.recorded userId=charlie counterId=video-funny-cats vote=LIKE likes=3 dislikes=0",
  "mdc": { "requestId": "349dd609-edec-48b9-ab43-df0297304caf" }
}
```

Structured, high-cardinality (every event is distinct), good for debugging specific incidents.

**3. Access logs** (Jetty's per-request line, also JSON):

```json
{
  "timestamp": 1776731848516,
  "method": "PUT",
  "uri": "/api/v1/counters/video-funny-cats/vote",
  "status": 200,
  "requestTime": 19
}
```

One line per HTTP request/response. Useful for traffic patterns and latency analysis that doesn't require application-level context.

#### Process

Each request now goes through one extra step — the `RequestIdFilter`:

```
HTTP request arrives
        │
        ▼
   RequestIdFilter.filter(request)
     → generates UUID (or honors X-Request-Id if client sent one)
     → MDC.put("requestId", id)
        │
        ▼
   ...resource method runs, helper runs, DAOs run...
   (every log line during this thread carries requestId automatically)
        │
        ▼
   RequestIdFilter.filter(request, response)
     → response.headers.put("X-Request-Id", id)
     → MDC.remove("requestId")   ← important, or next request on same thread inherits it
```

MDC (Mapped Diagnostic Context) is SLF4J's thread-local map. Whatever you put in MDC shows up as fields on every log line from that thread until you remove it.

Dropwizard's `@Timed` annotations wire into the `MetricRegistry` — a global registry that collects timing samples per method. The `/metrics` endpoint on the admin port renders that registry as JSON.

Everything else in the processing pipeline (threads per request, cache lookups, DB transactions, pagination, etc.) is unchanged from challenge 7.

#### Infrastructure

Unchanged from challenge 7 — one process, SQLite file, Dropwizard on :8080, admin on :8081. The observability signals go out through existing channels:

- **Metrics and health checks**: the admin HTTP port (8081) — already there, we just expose more data.
- **Logs**: stdout (the terminal) — in production these get captured by a log-shipping agent (Fluent Bit, Datadog Agent, CloudWatch Agent) and pushed to a centralized store.
- **Access logs**: also stdout, separate JSON format.

In a full production setup, you'd also have:

- A **metrics scraper** (Prometheus) polling `/metrics` every 15 seconds and storing the samples in a time-series DB.
- A **log aggregator** (Loki, Elasticsearch, Splunk) collecting logs and letting you grep/filter them.
- A **dashboard tool** (Grafana) visualizing metrics and alerting on thresholds.
- A **tracing backend** (Jaeger, Zipkin, Honeycomb) for cross-service traces.

Challenge 8 stops at "emit the signals." Wiring them to actual observability platforms is a deployment concern that varies by organization and is out of scope for this repo.

![Counter process with two observability planes: metrics scraped by Prometheus on :8081, logs written to stdout and shipped by an agent to a log aggregator. Request-handling path on the left, telemetry side-paths on the top and bottom. Dashed external components are not built in this challenge.](./diagram.png)


### Compile-Time — How to Implement It

#### The library: `RequestIdFilter` (new)

A Jersey filter (a request/response interceptor) that tags every request with a unique ID:

```java
@Provider
public class RequestIdFilter implements ContainerRequestFilter, ContainerResponseFilter {

    @Override
    public void filter(ContainerRequestContext request) {
        String id = request.getHeaderString("X-Request-Id");
        if (id == null || id.isBlank()) id = UUID.randomUUID().toString();
        MDC.put("requestId", id);
        request.setProperty("requestId", id);
    }

    @Override
    public void filter(ContainerRequestContext request, ContainerResponseContext response) {
        response.getHeaders().putSingle("X-Request-Id", (String) request.getProperty("requestId"));
        MDC.remove("requestId");
    }
}
```

Two things worth noticing:

1. **It honors an incoming `X-Request-Id`.** If an upstream proxy (load balancer, API gateway) already tagged the request, we keep that ID. That's how distributed tracing stays consistent across services — the ID flows end-to-end. If no incoming header, we generate one.
2. **It cleans up MDC on the way out.** Thread-pool threads get reused. If we forgot to clear, the next request on that thread would inherit the previous request's ID. Subtle bug, easy to avoid with a cleanup filter.

#### The library: `CounterResource` (updated)

Every endpoint gained a `@Timed` annotation:

```java
@GET
@Timed(name = "get")
@Path("/{id}")
public CounterResponse get(@PathParam("id") String id) { ... }
```

`@Timed` tells Dropwizard's metrics library: "every time this method runs, record how long it took." That produces per-endpoint timers with count/mean/p50/p95/p99/p999 percentiles. Zero extra code in the method body.

#### The library: `CounterHelper` (updated)

Added informational log statements at the significant state-change points:

```java
log.info("counter.created counterId={} createdAt={}", counterId, now);
log.info("vote.recorded userId={} counterId={} vote={} likes={} dislikes={}", ...);
log.info("vote.notFound userId={} counterId={}", userId, counterId);
```

These fire at INFO level on every write operation (success or failure). The log format uses structured key-value pairs inside the message (`counterId=video-funny-cats`) which log processors can parse.

In a richer production system, you'd use SLF4J's **structured arguments** to put these as proper JSON fields instead of message text:

```java
// Richer version (not done here, worth knowing):
log.info("vote.recorded",
    kv("userId", userId),
    kv("counterId", counterId),
    kv("vote", v));
```

Same idea; nicer downstream querying. We're doing the simpler version that works everywhere SLF4J does.

#### Configuration changes: `config.yml`

Two new sections. First, structured JSON for application logs:

```yaml
logging:
  level: INFO
  appenders:
    - type: console
      layout:
        type: json
        includes: [timestamp, level, loggerName, threadName, message, mdc, exception]
```

The `mdc` include is important — that's what makes the `requestId` field appear on every log line.

Second, JSON for Jetty's per-request access logs:

```yaml
requestLog:
  appenders:
    - type: console
      layout:
        type: access-json
```

Both streams go to stdout. In production they'd be separated and routed differently, but for local development, one stream is easier to read.

#### New dependencies

- **`io.dropwizard.metrics:metrics-annotation`** — provides the `@Timed` annotation (the underlying MetricRegistry is already bundled with Dropwizard).
- **`net.logstash.logback:logstash-logback-encoder`** — the JSON encoder for Logback (Dropwizard's logging library).
- **`io.dropwizard:dropwizard-json-logging`** — registers the `type: json` and `type: access-json` layout factories that `config.yml` references.

None of these change the existing code shape — they just activate JSON output where plain text was before.


## Run It

```bash
cd challenge-8-counter-server-process
mvn clean package
java -jar target/challenge-8-counter-1.0-SNAPSHOT.jar server config.yml
```

You'll see startup logs as JSON — timestamp, level, logger name, thread name, message, all as fields.

### Trace a single request

```bash
# Cast a vote. The server generates a request ID and echoes it in the response header.
curl -i -X PUT \
    -H "X-User-Id: charlie" \
    -H "Content-Type: application/json" \
    -d '{"vote":"like"}' \
    http://localhost:8080/api/v1/counters/video-funny-cats/vote

# Response includes:  X-Request-Id: <UUID>
# Grab that ID from the response.
```

Now grep the server's log output for that request ID. You'll see:

- The application log from `CounterHelper` (`vote.recorded userId=charlie ...`) tagged with this request ID.
- The access log line for this request's HTTP response.

Both share the same ID. This is how you reconstruct what happened during one request in a high-traffic system — grep by ID.

### See per-endpoint metrics

```bash
curl -s http://localhost:8081/metrics | python3 -m json.tool | grep -A 6 "CounterResource.vote"
```

Shows request count, mean latency, p50/p95/p99 percentiles for the `vote` endpoint. Production systems scrape `/metrics` every 15 seconds and graph these over time.

### See health status

```bash
curl -s http://localhost:8081/healthcheck | python3 -m json.tool
```

The DB health check does a real `SELECT 1` against SQLite. If the DB were unreachable or slow, this would fail — and load balancers would route around the unhealthy instance.

### Pass your own request ID

If a client library or upstream service already tagged a request, we honor that ID instead of generating a new one:

```bash
curl -i -H "X-Request-Id: my-test-id-123" http://localhost:8080/api/v1/counters/video-funny-cats

# Response:  X-Request-Id: my-test-id-123   (not a new UUID)
```

This is the distributed-tracing convention: one request ID flows end-to-end across every service that touches the request, so you can reconstruct cross-service journeys.


## What's Missing

- **Metrics scraping and dashboards** — we emit the `/metrics` JSON; we don't pull it into Prometheus, don't visualize it in Grafana, don't alert on it. The signals are exposed; downstream tooling is out of scope for this challenge.
- **Log aggregation** — logs go to stdout. Production systems capture them with Fluent Bit / Datadog Agent / a logging sidecar and ship them to a centralized store (Loki, Elasticsearch, Splunk, CloudWatch, etc.) where you can search them across many instances.
- **Distributed tracing** — our request ID is a poor-person's trace. Real tracing (OpenTelemetry, Jaeger, Zipkin) automatically instruments every call boundary (HTTP, DB, cache) and builds a tree showing where time was spent. For a single-service app it's overkill; for multi-service, it's essential. Challenge 9 onward is where it'd start paying off.
- **Log sampling and redaction** — at high throughput, logging every request is expensive and floods the log store. Production systems sample logs and redact PII (user IDs, emails, etc.). We're logging everything at full fidelity with no PII concerns — fine for teaching, not for prod.
- **Alerting** — metrics without alerts are a dashboard nobody looks at. Real systems set thresholds ("alert if p99 latency > 500ms for 5 minutes") and wake someone up when they fire. Out of scope here.


## Notes

A few things worth noticing about this design:

- **The three pillars answer different questions.**
  - **Metrics** answer "is the system healthy at scale?" — aggregated numbers, small storage footprint, fast to query. Use case: "are requests 10x slower than last week?"
  - **Logs** answer "what exactly happened for this specific thing?" — per-event records, big storage footprint, slow to query without indexing. Use case: "what sequence of events led to that 500?"
  - **Traces** answer "where was time spent inside a single request?" — structured per-operation breakdowns. Use case: "which DB query made request X slow?"
  You need all three because each is bad at the other two's job. Metrics can't tell you *which* request was slow; logs can't tell you if the whole system is slow; traces can't tell you about aggregate trends.
- **Cardinality is the observability word you have to know.** Metrics are *low-cardinality* — you can't tag a metric with user IDs because you'd have one metric per user and the metrics system would explode. Logs are *high-cardinality* — every event can carry any fields you want. If you ever think "I should tag this metric with the user ID", don't. Log it instead. This distinction is why there are separate systems for each.
- **The `@Timed` annotation is deceptively powerful.** That one line on each endpoint gives you: count of calls, rate (per second, minute, 5-minute, 15-minute), and full latency histogram (mean, p50, p75, p95, p99, p999, min, max). In production that's the single most valuable piece of data for understanding endpoint behavior.
- **MDC + request IDs are the cheapest tracing you can buy.** Full distributed tracing (OpenTelemetry, spans, trace context propagation) is a real commitment — instrumentation libraries, collector infrastructure, storage, visualization. MDC with a request ID gives you 80% of the value for 1% of the effort, in a single-service app. When you go multi-service (challenge 9+), the ID propagation already works across process boundaries (it's an HTTP header), so you get a head start on real tracing.
- **JSON logs are for machines, not humans.** Reading JSON log lines in a terminal is painful. Production systems ship them to an aggregator (Loki, Elasticsearch) that renders them nicely and lets you filter by field. Locally, `jq` is your friend: `tail -f log.json | jq .` — gives you pretty-printed, color-coded output with filtering.
- **The observability tax.** Every log line, every metric sample, every trace span costs CPU and memory. At low scale it's imperceptible; at high scale it can be 5-20% of your service's resource use. Real systems tune log levels, sample traces, aggregate metrics at the source, and drop low-value signals. Observability isn't free — it's an engineering discipline of balancing insight against cost.


## Trade-off: What to log, and at what level

Log levels exist for a reason — they let you control the signal-to-noise ratio.

| Level | What it's for | Example |
|-------|---------------|---------|
| `TRACE` | Noisy debug detail, usually off in prod | "entered recomputeAggregates(video-x)" |
| `DEBUG` | Useful during development, usually off in prod | "cache hit for video-x" |
| `INFO` | Significant state changes worth seeing always | "vote.recorded userId=charlie counterId=..." |
| `WARN` | Something recoverable but unusual | "cache stampede on video-x, 10 threads waiting" |
| `ERROR` | Something failed; operator attention needed | "DB query failed, counter=video-x, error=timeout" |

Our helper logs at INFO for state changes. We don't log at TRACE or DEBUG because:
- TRACE/DEBUG volume is huge and mostly useless in production.
- If we need them for a specific investigation, Dropwizard has a live log-level endpoint (`POST /tasks/log-level`) that lets you flip a logger's level at runtime without restarting — you turn on DEBUG just for the investigation, then turn it off.

The design principle: **log at the level that makes sense for production** (INFO for state changes, WARN/ERROR for problems), and accept that DEBUG-level insight requires turning up log levels temporarily. Logging everything at INFO would drown the signal in noise; logging nothing would leave you blind when something goes wrong.
