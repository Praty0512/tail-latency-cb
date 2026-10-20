# Tail-latency-aware adaptive circuit breaking for Java microservices - experimental testbed

Testbed, adaptive breaker, sweep harness and analysis for the project *Tail-Latency-Aware Adaptive Circuit
Breaking for Java Microservices*.

**Base paper:** Ahmed, Ren, Kim, Lee (2026), *Efficient service mesh traffic management for cloud-native
applications*, PLOS ONE 21(3): e0344516. It reports up to 15 % lower **average** response time under high network
latency (290 concurrent users, injected 3 s delays and HTTP 500 errors, Istio circuit breaking: eject after 5
consecutive errors). Its own threats-to-validity section (4.2.3) states that tail latency, communication overhead
and CPU efficiency were not measured and that all results come from **one** middleware configuration. This project
addresses exactly those two gaps; it does **not** reproduce the paper's graph-partitioning algorithm.

> **No results are included.** The full experiment is about 38 hours *per scenario* on one machine (section 6),
> so `results/` is empty and the analysis scripts report "no data" until the experiments are run. Every number the
> analysis prints comes from run files; none is typed in anywhere.

Contents: [1 Architecture](#1-architecture) - [2 Build and test](#2-build-and-test) - [3 Phase by phase](#3-phase-by-phase) -
[4 Decisions that deviate from or interpret the brief](#4-decisions-that-deviate-from-or-interpret-the-brief) -
[5 Reading the output](#5-reading-the-output) - [6 Reproducing the results](#6-reproducing-the-results) -
[7 Limitations](#7-limitations) - [8 Repository layout](#8-repository-layout)

Further documents: [`docs/adaptive-breaker.md`](docs/adaptive-breaker.md) (the contribution, its rule and the
design trade-off), [`docs/resilience4j-vs-istio.md`](docs/resilience4j-vs-istio.md) (what the static baseline does
not replicate).

---

## 1. Architecture

```
                          load generator (Python, 290 users, seeded)          admin API (harness)
                                        |  POST /api/orders                        |
                                        v                                          v
 +-------------------------------- order-service :8080 ---------------------------------------------+
 |  OrderController --> OutboundCall (inventory)  --> Retry( Breaker( TimeLimiter( HTTP ))) --+       |
 |        |         \-> OutboundCall (payment)    --> Retry( Breaker( TimeLimiter( HTTP ))) --+--+    |
 |        |                 breaker = none | static (Resilience4j) | adaptive (tail-latency)    |  |    |
 +--------|---------------------------------------------------------------------------------|--|----+
          |  every service: fault-injection filter, inbound/outbound latency histograms,    |  |
          |  CPU sampler, run export (CSV/JSON), /actuator/prometheus, /health, /admin/*    |  |
          v                                                                                 v  v
   (metrics export)                                            inventory-service :8081   payment-service :8082
```

* **Maven multi-module** (Java 17 target, Spring Boot 3.5): `common` (fault injection, instrumentation, export,
  admin API; auto-configured into every service), `resilience-core` (framework-free: breakers, retry/timeout
  composition, adaptation; this is what the unit tests exercise), and the three services.
* **Breaker lives in the order service**, around each outbound call, like a sidecar on the caller side.
* The system under test (none / static / adaptive) and every sweep cell are switched **at runtime** with
  `POST /admin/config`; no restart is needed between runs.

Admin endpoints (unauthenticated by design; testbed only, never expose them):

| Endpoint | Services | Purpose |
|---|---|---|
| `GET /health` | all | liveness |
| `GET /actuator/prometheus` | all | Prometheus metrics (histogram buckets, `tlcb_latency_quantile_seconds`, `tlcb_cpu_process_pct`, `tlcb_cb_state`, `tlcb_cb_threshold`) |
| `POST/GET/DELETE /admin/fault` | all | install / inspect / clear fault injection |
| `POST /admin/run/start`, `POST /admin/run/stop`, `GET /admin/run/status`, `GET /admin/run/export?format=csv\|json` | all | delimit a measured run and fetch its metrics |
| `POST/GET /admin/config` | order | select the system and its parameters |

---

## 2. Build and test

Requirements: JDK 17+ (built and tested here with JDK 21 `--release 17`), Maven 3.9, Python 3.9+, and (optionally)
Docker.

```bash
mvn clean install                 # 69 Java tests (fault engine, instrumentation, breakers, adaptation traces)
pip install -r requirements.txt
python tests/run_tests.py         # 22 Python tests (sweep planning, load statistics, analysis on synthetic data)
```

Always build with **`clean`**. Without it Spring Boot can keep a stale repackaged fat jar when only a dependency
module (`common`) changed - this happened during development and silently ran old code.

`tests/run_tests.py` exists because a stray `py.py` in some Python installation roots shadows pytest's `py` shim;
on a clean environment `python -m pytest tests` works too.

---

## 3. Phase by phase

### Phase 1 - Testbed

Three services (`/health`, own port, Dockerfile) wired in `docker-compose.yml`:

* `inventory-service :8081` `GET /api/inventory/{sku}` - deterministic stock lookup.
* `payment-service :8082` `POST /api/payments/charge` - simulated, idempotent charge (so retrying it is safe).
* `order-service :8080` `POST /api/orders` - checks stock, then charges, both synchronously. If a dependency is
  unusable the order fails fast with **HTTP 503** (no fallback), the analogue of Envoy's "no healthy upstream".

Business logic burns a configurable, deterministic amount of CPU per request (`WORK_ITERATIONS`) so per-service
CPU numbers are non-trivial.

**Fault injection** (`POST /admin/fault`, servlet filter): fixed delay, random delay (UNIFORM / EXPONENTIAL /
LOGNORMAL), HTTP error at probability p, and fault windows (start/end offsets). Example:

```bash
curl -X POST localhost:8082/admin/fault -H 'Content-Type: application/json' -d '{
  "seed": 42,
  "rules": [
    {"id":"slow","type":"DELAY","delayMs":3000,"probability":0.5,"startOffsetMs":60000,"endOffsetMs":120000},
    {"id":"err","type":"ERROR","status":500,"probability":0.3}
  ]}'
```

*Determinism.* A shared `Random` would make the *sequence* of draws reproducible but not *which request* receives
which draw (thread scheduling). Here every decision is a pure function of `(seed, X-Request-Key, attempt, rule)`
(SplitMix64). The load generator gives each request a seeded key, so **the set of faulted requests is identical
across runs and across systems**; retries carry an incremented `X-Attempt` and get independent draws. System
dynamics (timing, breaker state) are of course not bit-reproducible, which is why runs are repeated.

Run it: `docker compose up --build -d` (see the Docker caveat in section 7) or start the jars (section 6).

### Phase 2 - Instrumentation

* **Latency:** every inbound business endpoint and every outbound call is recorded in a Micrometer `Timer`
  with percentile histograms *and* an HdrHistogram recorder. Micrometer's own percentiles are over a decaying
  window and cannot be cut into exact per-interval rows, so the export uses the recorder; percentiles are never
  averaged across intervals. Three series per service: `inbound`, `outbound` (logical call incl. retries and
  rejections) and `attempt` (each HTTP attempt).
* **CPU:** `CpuSampler`, once per second, exposed as the gauges `tlcb_cpu_process_pct` (100 = one full core),
  `tlcb_cpu_process_host_fraction` and `tlcb_cpu_process_seconds`. OSHI is the primary source and is validated
  against the JVM's own counter at start-up; if they disagree the JVM counter is used and the choice is recorded
  (`cpuSource`) - see section 4, item 10.
* **Breaker state transitions** with timestamps and reasons (`cbTransitions` in the JSON, `CB-TRANSITION` in the log).
* **Export per run:** `GET /admin/run/export` as CSV and JSON. CSV columns (the ten required ones first):

  `timestamp, service, p50, p95, p99, avg, error_rate, cpu_pct, cb_state, config_id` followed by
  `direction, endpoint, count, errors, rejected, t_s, cb_threshold`. Latencies are in ms, `cpu_pct` in percent of
  one core, one row per series per export interval (default 5 s) plus one `process` row per service so CPU is always
  present. The harness merges the three services and the load generator (`service=loadgen`, `direction=e2e`) into
  one file per run.
* **Prometheus:** `/actuator/prometheus` on every service.

### Phase 3 - Static baseline

Resilience4j configured to replicate Istio's consecutive-error ejection (open after N consecutive failures, 10 s
open wait, 3 half-open probes), combined with configurable `Retry` and `TimeLimiter`:
`Retry( Breaker( TimeLimiter( HTTP attempt ) ) )`. A call rejected by an open breaker is not retried. The "no
breaker" system uses the same retry/timeout composition with a pass-through breaker, so only the breaker differs.
See [`docs/resilience4j-vs-istio.md`](docs/resilience4j-vs-istio.md) for the semantic differences.

### Phase 4 - Adaptive breaker

`TailLatencyAdaptiveBreaker`: a rolling 30 s window of latencies, p95/p99, an EWMA and an OLS slope over the last
6 evaluations; a clean `ThresholdStrategy` interface; tighten fast (x0.5) when the p99 rises faster than a slope
threshold, relax slowly (+1) with hysteresis, clamp to `[min, max]`; every change is logged with its reason. The
choice of a custom state machine over replacing the Resilience4j breaker, with its trade-off, is in
[`docs/adaptive-breaker.md`](docs/adaptive-breaker.md). Unit tests replay synthetic latency traces on a simulated
clock and assert the threshold trajectory (stable, creeping, sudden rise, plateau, recovery, repeated episodes,
clamps, no-data).

### Phase 5 - Experiment harness

`python -m harness ...` (wrappers: `run_experiments.sh`, `run_experiments.ps1`).

* **Load:** 290 users, ramp 100 users/s, think time 1-3 s (about 145 req/s, close to the 90-150 req/s the base
  paper shows), closed loop, all configurable (`--users --ramp-rate --think-min --think-max`). Custom asyncio
  client instead of Locust: seeded request keys, in-process phase control and one record per request.
* **Scenarios** (`harness/scenarios.yaml`): `baseline` (no faults), `delay` (3 s on 50 % of payment requests,
  parameters from the paper's 4.2.1), `errors` (HTTP 500 on 30 %), `mixed` (delay on payment plus 500s on
  inventory), `coldstart` (payment-service killed and restarted at 40 % of the run). Fault windows are fractions of
  the run: with 300 s, t = 60-180 s.
* **Systems:** `none`, `static`, `adaptive`.
* **Grid:** timeout {500, 1000, 2000, 3000} ms x retries {0, 1, 3} x threshold {3, 5, 10}; `none` has no threshold
  dimension (12 cells), `static` and `adaptive` 36 cells each, so 84 cells per scenario. Each cell is run
  `--repeats` times (default 5, seeds `base+k`).
* **Output:** `results/<config_id>/run_k.{csv,json,requests.csv}` with
  `config_id = <scenario>__<system>__t<timeout>_r<retries>[_f<threshold>]`.
* **Run order** is repeat-major with a seeded shuffle, so machine drift is spread over configurations; all cells of
  one repeat share a seed (paired design). Runs are resumable: finished runs are skipped.

```bash
./run_experiments.sh --scenario mixed --repeats 5          # the full grid for one scenario (~38 h)
./run_experiments.sh --scenario mixed --quick              # reduced grid, 45 s runs, 1 repeat: smoke test
./run_experiments.sh --scenario all --dry-run              # print the plan and the time estimate
./run_experiments.sh --help
```

`--quick` = timeouts {1000, 2000} x retries {0, 1} x threshold {5}, 45 s runs, 10 s warm-up, 1 repeat (any flag
overrides). Modes: `--mode local` (default; jars), `--mode docker`, `--mode attach` (services already running).

### Phase 6 - Analysis

```bash
python -m analysis.run_analysis --results results --out analysis_out
```

Produces `analysis_out/summary.md`, `tables/*.csv` and `figures/*.png`:

1. avg vs p95 vs p99 for none / static / adaptive per scenario, at the base paper's own configuration
   (timeout 2000 ms, 3 retries, threshold 5), whole run and fault window, with p99 of successful requests and the
   error rate alongside;
2. the **15 % claim check**: gain of each breaker system over the same configuration without breaker at avg, p95
   and p99, with bootstrap CIs, Mann-Whitney U (Holm-corrected) and a verdict from the CI;
3. **configuration-sensitivity maps**: p99 heatmaps over timeout x retries per threshold per system, matching
   gain maps, and a per-cell gain strip;
4. adaptive vs static head to head, breaker activity, time series with the adaptive threshold trajectory;
5. CPU per service and CPU-seconds per 1000 successful requests, with 95 % CIs.

---

## 4. Decisions that deviate from or interpret the brief

Where the specification was ambiguous or technically problematic, this is what was decided and why.

1. **Static baseline needs no custom counter.** A Resilience4j window of N with a 100 % failure-rate threshold is
   exactly "N consecutive failures" (verified by a trace-equivalence test). Real differences are listed in
   `docs/resilience4j-vs-istio.md`.
2. **Adaptive breaker = custom state machine**, not replace-in-registry (rationale and trade-off in
   `docs/adaptive-breaker.md`). Its equivalence to the baseline is tested.
3. **Relative slope.** The slope is normalised by the recent p99 level so the same threshold works for 500 ms and
   3 s timeouts; an absolute ms/s threshold would have confounded the timeout sweep.
4. **"Stable or recovering" is refined:** relaxation also requires the p99 to have fallen to at most half its
   peak, otherwise a plateau of high latency would loosen the breaker.
5. **`config_id` includes the scenario**, because results are stored at `results/<config_id>/run_k.csv` and the
   same grid cell is run under every scenario.
6. **Open wait 10 s**, although the base paper's Fig 5 YAML says `baseEjectionTime: 30s` (its text says 10 s).
   Configurable.
7. **Retries apply to every failure** by default (the Istio `retryOn` set excludes plain 500); `--retry-policy
   ISTIO_GATEWAY_ERROR` selects the Istio-style subset. Otherwise the retry dimension would be empty in the
   HTTP-500 scenarios.
8. **The 15 % claim.** In the base paper the 15 % is the gain of graph-partitioned placement + Istio over default
   Istio. This project does not implement placement, so it cannot test that number. The analysis tests the
   closest measurable question: does a breaker system reduce latency by >= 15 % relative to the same
   timeout/retry configuration without a breaker, at the average and at p95/p99? The summary says so explicitly.
9. **Latency gains are reported next to error rates and successful-only percentiles.** A breaker converts slow
   failures into fast failures; a latency "gain" that is paid for with a much higher error rate is not a
   resilience gain.
10. **CPU source.** OSHI is the primary source (as specified). On the development machine (Windows) OSHI reported
    0 ms CPU time for every process because the OS performance-counter registry is damaged ("Unable to locate
    English counter names"). The sampler therefore validates OSHI against the JVM counter at start-up and falls back
    to the JVM counter, recording `cpuSource` in every export; the analysis warns if sources are mixed. On Linux
    (the Docker images) OSHI reads `/proc` and is used.
11. **Fault targets are our choice:** payment-service is "the one downstream"; the base paper maps faults to a
    benchmark, not to named services.
12. **Python 3.9** compatibility (no 3.10+ syntax), because that is what was available.

---

## 5. Reading the output

* Latency statistics are measured **end to end at the load generator** and include fast failures; successful-only
  variants are provided. Run-level percentiles use raw per-request latencies of each run.
* The unit of replication is the **run**: confidence intervals are Student-t over repeats, the gain CI is a
  percentile bootstrap over runs (needs n >= 3 per group), the test is two-sided Mann-Whitney U (exact for small
  n; with n = 5 vs 5 the smallest attainable p is 0.0079), Holm-corrected within each table.
* The warm-up (default 15 s plus 20 s extra before the first run) is recorded in `run_k.requests.csv` with
  `phase=warmup` and excluded from all statistics.
* `analysis_out/summary.md` begins with automatic **warnings** (fewer than 5 repeats, runs shorter than 300 s,
  partial grid, reference cell missing, mixed CPU sources). Read them before any table.

---

## 6. Reproducing the results

```bash
# 1. build
mvn clean install -DskipTests
pip install -r requirements.txt

# 2. smoke test (about 15 minutes): proves the whole pipeline on your machine
./run_experiments.sh --scenario mixed --quick --results-dir results_smoke
python -m analysis.run_analysis --results results_smoke --out analysis_out_smoke --label "SMOKE DATA"

# 3. the real experiment. Estimate first:
./run_experiments.sh --scenario all --repeats 5 --dry-run
#    one scenario  = 84 cells x 5 repeats = 420 runs x ~327 s  = about 38 hours
#    all 5 scenarios                       = 2100 runs         = about 190 hours (8 days)
./run_experiments.sh --scenario delay --repeats 5          # resumable: re-run the same command after an interruption
./run_experiments.sh --scenario errors --repeats 5
./run_experiments.sh --scenario mixed --repeats 5
./run_experiments.sh --scenario baseline --repeats 5
./run_experiments.sh --scenario coldstart --repeats 5

# 4. analysis
python -m analysis.run_analysis --results results --out analysis_out
```

Time-saving options that keep the design intact: fewer repeats (`--repeats 3`), shorter runs
(`--duration 120`; fault windows scale), a subset of the grid (`--timeouts 1000,2000 --retries 0,3
--thresholds 3,10`), or one scenario at a time. State what was reduced when reporting. Run on a quiet machine:
the load generator and the three JVMs share it, and results are only comparable within one machine and session.

---

## 7. Limitations

* **Docker was not executed** during development (the daemon was not running). `docker-compose.yml` passes
  `docker compose config`, the Dockerfiles follow the standard multi-stage pattern, but images were not built or
  run here; the experiments in this repository's smoke test ran in `--mode local`.
* **Not Istio.** Single instance per service, no sidecar, no Kubernetes, no load balancing across replicas; see
  `docs/resilience4j-vs-istio.md`.
* **Closed-loop load** under-samples stalls (coordinated omission); an open-loop generator would show worse tails
  during outages.
* **One host** runs generator and services; CPU numbers include that contention. CPU is process CPU time, not
  energy, and not memory.
* The adaptation parameters are defaults chosen from synthetic traces, not tuned.
* Statistical power: with 5 repeats per cell, CIs are wide; the grid-wide view (many cells) is the main protection
  against over-reading a single cell.

---

## 8. Repository layout

```
tail-latency-cb/
  pom.xml                       parent POM (Spring Boot 3.5, Resilience4j 2.3, OSHI, HdrHistogram)
  common/                       fault injection, instrumentation, CPU sampler, run export, admin API
  resilience-core/              breakers, retry/timeout composition, adaptation (framework-free) + tests
  inventory-service/ payment-service/ order-service/    the three services (+ Dockerfile each)
  docker-compose.yml
  harness/                      scenarios.yaml, sweep planning, service control, load generator, runner, CLI
  analysis/                     loading, statistics, tables, figures, summary generator
  tests/                        Python tests (harness + analysis on synthetic data)
  docs/                         adaptive-breaker.md, resilience4j-vs-istio.md
  run_experiments.sh / .ps1     one-command entry points
  results/                      experiment output (empty until the experiments are run)
```
