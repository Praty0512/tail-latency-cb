# Resilience4j vs. Istio outlier detection: what the static baseline does and does not replicate

The base paper (Ahmed et al. 2026, Fig 5 and section 4.2.2) configures Istio's `outlierDetection`:
`consecutive5xxErrors: 5` (the OCR of the figure reads "consecutiveSxxErrors"), `interval: 10s`,
`baseEjectionTime: 30s` in the YAML, while the text says an ejected instance "remained out of rotation for 10
seconds". The study's brief says 10 s. **The paper is inconsistent (30 s in the YAML, 10 s in the text)**; the
testbed defaults to 10 s (`openWaitMs`) and the value is configurable (`--open-wait-ms`).

The static baseline is a Resilience4j `CircuitBreaker` configured as: COUNT_BASED sliding window of size N,
`minimumNumberOfCalls = N`, `failureRateThreshold = 100`, `waitDurationInOpenState = 10 s`,
`permittedNumberOfCallsInHalfOpenState = 3`, automatic OPEN to HALF_OPEN transition.

## Why no custom consecutive-error counter was needed

Resilience4j opens on a failure *rate* over a sliding window. A count-based window of N calls with a 100 %
threshold is satisfied exactly when the last N recorded calls all failed, i.e. when there were **N consecutive
failures**; a success inside the window prevents opening, exactly like a success resets Envoy's counter. This was
verified, not assumed: `Resilience4jBreakerEquivalenceTest` runs random call traces through this configuration
and through an independent consecutive-failure state machine and asserts identical behaviour step by step.
(The spec allowed for a custom counter "if needed to be faithful"; for the trip rule it is not needed.)

## Where the two still differ

| Aspect | Istio / Envoy outlier detection | Resilience4j baseline here | Consequence for the study |
|---|---|---|---|
| **Scope of the decision** | per **endpoint** (pod) in a load-balancing pool; traffic shifts to the remaining replicas | per **dependency**; the testbed has one instance per service, so there is nothing to shift to | A rejected call fails fast with HTTP 503 (the analogue of Envoy answering "no healthy upstream"). Multi-replica load balancing is out of scope and is a limitation. |
| **Ejection / open duration** | `baseEjectionTime x number of times the host was ejected` (grows with repeated ejection, capped by `maxEjectionTime`) | constant `waitDurationInOpenState` | A flapping dependency is isolated for longer by Envoy than here. |
| **Re-admission** | the host returns to the pool completely; needs N *new* consecutive errors to be ejected again | HALF_OPEN: only 3 probe calls are admitted, the outcome decides CLOSED or OPEN again | Re-entry is more cautious here. |
| **Half-open decision** | none | with a 100 % failure-rate threshold the breaker re-opens only if *all* probes fail; Resilience4j also evaluates after `min(N, probes)` calls (irrelevant for N >= 3) | documented and unit-tested |
| **Ejection limits** | `maxEjectionPercent` (default 10 %, but at least one host may always be ejected) and a panic threshold (default 50 % healthy hosts) below which health is ignored | none | With one instance per dependency these mechanisms would not trigger in a comparable way anyway. |
| **When the decision is taken** | consecutive-error ejection fires inline on the Nth error; the un-ejection and other analyses run on the `interval` sweep | inline on the Nth failure, re-admission by a timer at exactly the open wait | Re-admission times differ by up to one `interval` in Envoy. |
| **What counts as an error** | 5xx from the upstream, gateway errors, and (by default) local-origin failures such as connect errors and timeouts | 5xx, timeout (TimeLimiter), I/O error | Same classes; HTTP 4xx and business results never count. |
| **Retries** | `VirtualService.retries` with `retryOn: gateway-error,connect-failure,refused-stream` (502/503/504, connect failures, not 500) | Resilience4j `Retry` retrying every failure by default (`retryOn = ALL_FAILURES`); `ISTIO_GATEWAY_ERROR` selects the Istio-style subset | With ALL_FAILURES the retry dimension is meaningful in the HTTP-500 scenarios. Deviation chosen deliberately and documented; configurable. |
| **Timeout structure** | `perTryTimeout` plus an overall `timeout` (5 s in the paper) | per-attempt `TimeLimiter` only (the sweep's *timeout* dimension is per-attempt); no overall deadline | With 3 retries and a 3 s timeout one call can take about 12 s. |
| **Composition order** | retry wraps the per-try timeout; each retry re-selects a non-ejected host | `Retry( Breaker( TimeLimiter( call ) ) )`; every attempt asks the breaker again; a call rejected by an open breaker is not retried | equivalent in spirit |
| **Connection-pool limits** | `maxConnections: 100`, `http1MaxPendingRequests: 100` | Tomcat worker threads (400 by default, so the testbed itself is not the bottleneck at 290 users) | The paper's queueing limits are not replicated. |

## Consequences for how results must be read

* The static baseline replicates the **trip rule** of Istio's consecutive-error detection and its default
  parameters, not the full Envoy mechanism. Statements in the analysis are about "a consecutive-failure breaker
  of the Istio kind implemented in Resilience4j".
* The base paper's gain is reported for a different system (graph-partitioned placement plus Istio on
  Kubernetes). Nothing here reproduces or contradicts that system; the study asks whether the *kind* of gain it
  reports on the average is also present in the tail and across configurations.
