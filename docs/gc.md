# Garbage Collection Under Load

## Classification Before Tuning

The baseline recording showed allocation activity primarily associated with
`byte[]`, `String`, and `URL` objects. The baseline was therefore classified
primarily as an allocation-pressure problem rather than a long-pause problem.

The application also performed the configured fee-rate conversion using
`BigDecimal` inside the settlement request path. This calculation was therefore
repeated for every settlement request.

## JVM / Runtime Configuration

The application was run using the JVM's default G1 garbage collector
configuration. The relevant GC configuration observed in the baseline JFR was:

- Young Garbage Collector: `G1New`
- Old Garbage Collector: `G1Old`
- Concurrent GC Threads: `1`
- Parallel GC Threads: `4`
- G1: enabled
- UseG1GC: `true`

The tuning change was application-level rather than a change to the G1
collector flags: the fee-rate conversion was moved out of the per-request
settlement path and calculated once during service initialization.

## Baseline vs Tuned Results

Both tests used the same 10-minute steady-load test at approximately
10 requests per second.

| Metric | Baseline | Tuned |
|---|---:|---:|
| Sampled allocation rate | 1.80 MiB/s | 1.82 MiB/s |
| Top allocating class | `byte[]` – 519 MiB | `byte[]` – 524 MiB |
| 2nd allocating class | `String` – 88.6 MiB | `String` – 77.6 MiB |
| 3rd allocating class | `URL` – 70.9 MiB | `URL` – 57.8 MiB |
| GC collections | 35 | 35 |
| Longest GC pause | 33.870 ms | 27.171 ms |
| p99 request latency | 25.4 ms | 19.0 ms |
| Throughput | 9.999793 req/s | 10.001523 req/s |
| Failed requests | 0% | 0% |

## Tuning Change

The fee-rate conversion was previously performed for every settlement
request:

```java
BigDecimal.valueOf(feeRate)
        .multiply(BigDecimal.valueOf(10000))
        .longValue();