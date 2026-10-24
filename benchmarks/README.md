# Ledger Settlement JMH Benchmarks

## Purpose

This Maven module (`benchmarks`) contains JMH microbenchmarks for real operations in the Ledger Settlement service (`org.example.ledgersettlement`).

The benchmarks are isolated in a separate Maven module so that `jmh-core` and `jmh-generator-annprocess` do not pollute the production service JAR (`target/ledger-settlement.jar`).

## Benchmarks

1. **`calculateSettlement`** — Benchmarks `SettlementService.calculateSettlement(String)` in `org.example.ledgersettlement.SettlementService`, keeping all monetary arithmetic on `long` minor units and `BigDecimal` fee-rate conversion rather than binary floating point (`double`).
2. **`groupByMerchant`** — Benchmarks the collection-heavy grouping method `SettlementService.groupByMerchant(List<PaymentEntity>)`, which allocates a `HashMap` and per-merchant `ArrayList` buckets via `Collectors.groupingBy`.
3. **`mapPayment`** — Benchmarks entity-to-DTO response mapping via `PaymentController.toPaymentResponse(PaymentEntity)`, which runs on the `POST /payments` path.
4. **`wrongDeadCodeBenchmark`** — Deliberately broken benchmark that computes the merchant settlement loop and discards the result without returning or consuming it, demonstrating JIT dead-code elimination (DCE).
5. **`fixedDeadCodeBenchmark`** — Identical computation to `wrongDeadCodeBenchmark`, fixed by consuming the computed total via JMH `Blackhole.consume(long)`.
6. **`calculateSettlementPerRequestBigDecimal`** — Baseline comparison variant performing `BigDecimal.valueOf(feeRate).multiply(BigDecimal.valueOf(10000)).longValue()` on every call to evaluate confidence-interval overlap against `calculateSettlement`.

## JMH Configuration

- `@BenchmarkMode(Mode.AverageTime)`
- `@OutputTimeUnit(TimeUnit.MICROSECONDS)`
- `@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)`
- `@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)`
- `@Fork(3)`

Forks matter because each fork launches a fresh JVM process, preventing profile pollution, inlining bias, or escape-analysis decisions made in one run from skewing subsequent runs.

## Build and Run (Single Command from Repository Root)

```bash
mvn clean package -DskipTests && java -jar target/benchmarks.jar -prof gc -rf json -rff results.json
```

A full run takes approximately **4 minutes 45 seconds** and writes the raw JMH output (including `-prof gc` normalized allocation rates) to `results.json`. See [`../docs/benchmarks.md`](../docs/benchmarks.md) for the full report and confidence-interval analysis.
