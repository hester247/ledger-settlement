# Ledger Settlement

Pinned dependencies:
- JUnit Jupiter: 5.14.3
- AssertJ: 3.27.7

Versions pinned on 2026-09-20.

Tests run: 6, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS

## JMH Microbenchmarks (`benchmarks` module — SJV-L3)

The `benchmarks` Maven module builds an executable shaded JAR (`target/benchmarks.jar` and `benchmarks/target/benchmarks.jar`) containing the JMH microbenchmark suite for the Ledger settlement service, while keeping JMH dependencies out of the production service artifact (`target/ledger-settlement.jar`).

### Single Command to Build and Run (from a Clean Clone)

Run the following single command from the repository root (`ledger-settlement`):

```bash
mvn clean package -DskipTests && java -jar target/benchmarks.jar -prof gc -rf json -rff results.json
```

*(In PowerShell 5.1, if `&&` is not enabled, use `; if ($?) { ... }`: `mvn clean package -DskipTests; if ($?) { java -jar target/benchmarks.jar -prof gc -rf json -rff results.json }`)*

### Expected Run Duration

A full suite execution (6 benchmarks × 3 forks × [5 warmup iterations + 10 measurement iterations at 1 s each] with `-prof gc`) takes approximately **4 minutes 45 seconds** (`~00:04:45`). Raw JSON output is written to `results.json` and analysed in [`docs/benchmarks.md`](docs/benchmarks.md).
