# JMH Benchmark Report

## Benchmarks

Three real application operations were selected:

1. `LedgerSettlement.calculateMerchantSettlement` — settlement calculation.
2. `SettlementService.groupByMerchant` — collection-heavy grouping operation.
3. `LedgerSettlement.Payment` construction — mapping/parsing operation.

## JMH Configuration

- Benchmark mode: AverageTime
- Output unit: Microseconds
- Warmup: 5 iterations
- Measurement: 10 iterations
- Forks: 3

## Results

### Settlement calculation

Time: 426.470 ± 369.840 µs/op

Allocation: 7952.075 ± 50.663 B/op

### groupByMerchant

Time: 3.938 ± 0.598 µs/op

Allocation: 3840.000 ± 0.001 B/op

### mapPayment

Time: 0.573 ± 0.036 µs/op

Allocation: 160.001 ± 0.001 B/op

## Dead-Code Elimination

A deliberately incorrect benchmark was included where a calculated result was never consumed. This demonstrates that the JIT compiler may eliminate unused work.

A corrected version uses JMH `Blackhole.consume()` so the calculated result must be retained.

## Allocation Profiling

GC profiling was performed using JMH `-prof gc`.

The settlement calculation allocated approximately:

7952.075 ± 50.663 B/op

The `groupByMerchant` benchmark allocated:

3840.000 ± 0.001 B/op

The mapping benchmark allocated:

160.001 ± 0.001 B/op

## Interpretation

Results with overlapping error intervals should be treated as inconclusive rather than as evidence of a meaningful performance difference.

The reported score is the average execution time per operation and the error represents the reported JMH uncertainty.

## Limits

1. Results depend on the hardware, operating system, JVM version, and runtime environment.
2. Microbenchmarks do not necessarily represent complete production application performance.
3. The benchmark inputs are fixed and may not represent all production workloads.
4. JVM warmup and JIT compilation can affect measured results.
5. Database and network effects are not represented by these in-process benchmarks.