# Garbage Collection Under Load

## Experiment

The Ledger settlement service was tested with the same steady load for 10 minutes for both the baseline and tuned versions.

The tuned change moved the fee-rate calculation out of the request path so that the `BigDecimal` calculation is performed once during service initialization instead of on every settlement request.

## Baseline vs Tuned

| Metric | Baseline | Tuned |
|---|---:|---:|
| Allocation – `byte[]` | 519 MiB total / 48 MiB/s | 524 MiB total / 48.1 MiB/s |
| Top allocating class | `byte[]` | `byte[]` |
| 2nd allocating class | `String` – 88.6 MiB | `String` – 77.6 MiB |
| 3rd allocating class | `URL` – 70.9 MiB | `URL` – 57.8 MiB |
| GC collections | 35 | 35 |
| Longest GC pause | 33.870 ms | 27.171 ms |
| p99 request latency | 25.4 ms | 19.0 ms |
| Throughput | 9.999793 req/s | 10.001523 req/s |
| Failed requests | 0% | 0% |

## Baseline Findings

The baseline recording showed `byte[]`, `String`, and `URL` as the top allocating classes. The allocation stack was primarily associated with JVM, Spring Boot class loading, and URL/JAR handling rather than a clear application-specific allocation site.

The settlement request path also recalculated the configured fee rate using `BigDecimal` on every request.

## Tuning Change

The fee-rate calculation was moved from `calculateSettlement()` into service initialization.

Previously, every settlement request performed:

```java
BigDecimal.valueOf(feeRate)
        .multiply(BigDecimal.valueOf(10000))
        .longValue();