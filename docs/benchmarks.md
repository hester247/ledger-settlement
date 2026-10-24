# JMH Microbenchmark Report (SJV-L3)

## 1. Benchmark Module Architecture (Scenario 1)

The JMH microbenchmarks are isolated in a dedicated Maven submodule [`benchmarks`](../benchmarks/pom.xml) declared in the root [`pom.xml`](../pom.xml) (`<modules><module>benchmarks</module></modules>`).

- **Dependencies pinned in `benchmarks/pom.xml`:**
  - `org.openjdk.jmh:jmh-core:1.37`
  - `org.openjdk.jmh:jmh-generator-annprocess:1.37`
- **Packaging:** `maven-shade-plugin:3.6.0` packages the compiled benchmark classes, JMH runtime, and generated JMH harness classes (`org.example.benchmarks.jmh_generated.*`) into an executable uber-JAR (`target/benchmarks.jar` and `benchmarks/target/benchmarks.jar`) with `Main-Class: org.openjdk.jmh.Main`.
- **Clean Separation:** The production service JAR (`target/ledger-settlement.jar`) carries zero JMH dependencies.

---

## 2. Three Real Service Methods Selected and Justification (Scenario 2)

All benchmarked methods are real methods from the Ledger settlement service (`org.example.ledgersettlement`), exercised directly without reflection inside [`SettlementBenchmark.java`](../benchmarks/src/main/java/org/example/benchmarks/SettlementBenchmark.java):

1. **`SettlementService.calculateSettlement(String merchantId)` — Core Settlement Calculation (`calculateSettlement` & `calculateSettlementPerRequestBigDecimal`):**
   - **Why chosen:** This is the primary business calculation on the `GET /payments/settlement` hot path. It stays strictly on `long` minor units (`amountMinor`) and `BigDecimal` fee-rate conversion (`BigDecimal.valueOf(feeRate).multiply(BigDecimal.valueOf(10000)).longValue()`) rather than binary floating point (`double`) because IEEE 754 binary floating point cannot represent base-10 monetary fractions (such as `0.031` or `0.01`) exactly without rounding drift. Measuring both the `@PostConstruct`-precomputed basis-point path (`calculateSettlement`) and the per-request `BigDecimal` conversion variant (`calculateSettlementPerRequestBigDecimal`) quantifies the exact per-operation latency and allocation cost of `BigDecimal` conversion on the request path.
2. **`SettlementService.groupByMerchant(List<PaymentEntity> payments)` — Collection-Heavy Grouping Operation (`groupByMerchant`):**
   - **Why chosen:** Grouping a batch of `100` `PaymentEntity` records by `merchantId` via `Collectors.groupingBy(PaymentEntity::getMerchantId)` exercises `HashMap` table allocation, `HashMap$Node` creation, and dynamic `ArrayList` resizing per merchant key. In batch reconciliation and multi-merchant reporting paths, collection allocation and pointer chasing dominate CPU and young-generation GC pressure.
3. **`PaymentController.toPaymentResponse(PaymentEntity savedPayment)` — Serialisation / DTO Mapping Method (`mapPayment`):**
   - **Why chosen:** Every `POST /payments` request constructs a `PaymentEntity` and maps it into an immutable `PaymentController.PaymentResponse` record DTO before HTTP response serialisation. Benchmarking this method isolates object-construction and field-copying overhead on the write path and verifies whether scalar replacement (escape analysis) eliminates intermediate allocations.

---

## 3. Dead-Code Elimination (DCE): Broken vs. Fixed Benchmark (Scenarios 3 & 4)

To demonstrate JIT dead-code elimination (DCE), two benchmarks executing the exact same `1,000`-iteration settlement fee calculation loop (`long fee = amount * rate / 10000L; totalOwed += (amount - fee);`) were run:

1. **`wrongDeadCodeBenchmark` (Deliberately Broken — Scenario 3):**
   ```java
   @Benchmark
   public void wrongDeadCodeBenchmark(BenchmarkState state) {
       long totalOwed = 0L;
       long rate = state.feeRateBasisPoints;
       long base = state.baseAmountMinor;
       int n = state.dceBatchSize; // 1,000
       for (int i = 0; i < n; i++) {
           long amount = base + i;
           long fee = amount * rate / 10000L;
           totalOwed += (amount - fee);
       }
       // Result intentionally discarded
   }
   ```
   - **Measured Result:** **`0.001 ± 0.001 µs/op`** (exact from `results.json`: **`0.000708 ± 0.000060 µs/op`**, i.e. **`0.708 ± 0.060 ns/op`**, 99.9% CI `[0.000647, 0.000768] µs/op`).
   - **Why it is suspiciously fast:** Executing `1,000` 64-bit integer multiplications, divisions (`idiv`/`imul` magic multiply), and additions in `0.708 ns` (~2–3 CPU clock cycles total) is physically impossible. Because `totalOwed` is never returned or consumed and the loop body has no observable side effects, the HotSpot C2 JIT compiler proves the entire `1,000`-iteration loop is dead code and removes it completely, measuring an empty method return.

2. **`fixedDeadCodeBenchmark` (Fixed with `Blackhole` — Scenario 4):**
   ```java
   @Benchmark
   public void fixedDeadCodeBenchmark(BenchmarkState state, Blackhole blackhole) {
       long totalOwed = 0L;
       long rate = state.feeRateBasisPoints;
       long base = state.baseAmountMinor;
       int n = state.dceBatchSize; // 1,000
       for (int i = 0; i < n; i++) {
           long amount = base + i;
           long fee = amount * rate / 10000L;
           totalOwed += (amount - fee);
       }
       blackhole.consume(totalOwed);
   }
   ```
   - **Measured Result:** **`1.708 ± 0.166 µs/op`** (exact from `results.json`: **`1.707741 ± 0.165818 µs/op`**, i.e. **`1,707.741 ± 165.818 ns/op`**, 99.9% CI `[1.541923, 1.873559] µs/op`).
   - **Size of the Dead-Code Elimination Error:**
     $$\frac{1.707741\text{ }\mu\text{s/op}}{0.000708\text{ }\mu\text{s/op}} = \mathbf{2,412.06\times}\text{ difference } (+1.707033\text{ }\mu\text{s/op})$$
     Consuming the result via `Blackhole.consume(totalOwed)` (or returning `long` from the `@Benchmark` method, which JMH implicitly feeds into a compiler blackhole) forces C2 to retain all `1,000` loop iterations (`~1.71 ns` per iteration), increasing measured execution time by a factor of **`2,412x`**.

---

## 4. Preventing Constant Folding with `@State` (Scenario 5)

All benchmark inputs (`targetMerchantId`, `configuredFeeRate`, `feeRateBasisPoints`, `dceBatchSize`, `baseAmountMinor`, `allPayments`, `merchantPayments`, `sampleEntity`, `settlementService`, and raw payment fields) are stored in `SettlementBenchmark.BenchmarkState`, annotated with `@State(Scope.Benchmark)` and populated inside `@Setup(Level.Trial)`. None of the measured `@Benchmark` methods contain compile-time literals for their inputs; JMH loads `@State` fields through non-final references across benchmark invocations so the C2 compiler cannot constant-fold the calculation at compile time.

---

## 5. Run Configuration and Why Forks Matter (Scenario 6)

The benchmark class is configured with:

```java
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(3)
```

- **Why forks matter (one-line summary):** Each fork runs in a fresh, independent JVM process so that non-deterministic JIT compilation decisions, branch-prediction profiles, type-profile pollution, and heap layout from one run cannot bias or skew the measurements of other runs.
- **Concrete evidence of fork variance in our run:** In `results.json` for `calculateSettlement`, Forks 1 and 3 eliminated the 24-byte `Object[1]` varargs array in `Proxy.invoke` via C2 escape analysis (`0.007 MB/s` / `~0.000 B/op`), whereas Fork 2 did not scalar-replace that allocation (`84–90 MB/s` / `24.002 B/op`), yielding a cross-fork mean of `8.001 ± 7.688 B/op`. Running `@Fork(1)` would have hidden this bimodal JIT behaviour completely.
- **Verification that 5 warmup iterations settled:** Across all three forks in `results.json`, the 10 measurement iterations settled into a tight band after the 5 warmup iterations (for example, `calculateSettlement` Fork 1 measurement iterations ranged between `0.129 µs/op` and `0.155 µs/op`, and `mapPayment` measurement iterations ranged between `0.007 µs/op` and `0.011 µs/op`).

---

## 6. Execution Command and Allocation Profiling (`-prof gc`) (Scenarios 7 & 8)

The benchmark suite was executed from the repository root with both JSON output and the JMH GC allocation profiler enabled:

```bash
java -jar target/benchmarks.jar -prof gc -rf json -rff results.json
```

- **JDK & VM recorded in [`results.json`](../results.json):** OpenJDK 64-Bit Server VM `25.0.4.1+8-LTS` (Amazon Corretto 25), JMH `1.37`, `30` measurement samples per benchmark (`3` forks × `10` measurement iterations).

---

## 7. Results Table with Error Bars (99.9% Confidence Intervals) (Scenarios 8 & 9)

All scores and errors below are taken directly from [`results.json`](../results.json). JMH reports `score ± scoreError` at a **99.9% confidence level** (assuming a normal distribution across the $N = 30$ measurement iterations).

| Benchmark Method | Average Time (`score ± error`) | 99.9% Time CI (`µs/op`) | Allocation Rate (`gc.alloc.rate.norm`) | 99.9% Allocation CI (`B/op`) | Source of Allocation in Code |
|---|---:|---:|---:|---:|---|
| `calculateSettlement` | `0.146 ± 0.015 µs/op` | `[0.130, 0.161]` | `8.001 ± 7.688 B/op` | `[0.313, 15.689]` | `Proxy.invoke` `Object[1]` varargs array (`24 B` in Fork 2; scalar-replaced to `0 B` in Forks 1 & 3); zero `BigDecimal` allocation |
| `calculateSettlementPerRequestBigDecimal` | `0.230 ± 0.020 µs/op` | `[0.211, 0.250]` | `120.002 ± 0.001 B/op` | `[120.001, 120.002]` | `BigDecimal.valueOf(double)` -> `Double.toString` (`FloatingDecimal` char/byte buffer + `String` + `BigDecimal` objects = `120 B/op`) |
| `groupByMerchant` | `3.801 ± 0.327 µs/op` | `[3.474, 4.128]` | `3840.026 ± 0.002 B/op` | `[3840.024, 3840.029]` | `Collectors.groupingBy` allocating `HashMap`, `HashMap$Node[]` table, and two `ArrayList` instances resizing to hold 50 elements each (`3,840 B/op`) |
| `mapPayment` | `0.008 ± 0.001 µs/op` | `[0.007, 0.009]` | `40.000 ± 0.001 B/op` | `[40.000, 40.000]` | `PaymentController.toPaymentResponse` allocating the 40-byte `PaymentResponse` record instance (`PaymentEntity` is scalar-replaced by C2 escape analysis) |
| `wrongDeadCodeBenchmark` | `0.001 ± 0.001 µs/op` (`0.000708 ± 0.000060`) | `[0.0006, 0.0008]` | `≈ 10⁻⁵ B/op` (`0.000 ± 0.000`) | `[0.000, 0.000]` | Zero allocations; entire loop eliminated by C2 dead-code elimination |
| `fixedDeadCodeBenchmark` | `1.708 ± 0.166 µs/op` | `[1.542, 1.874]` | `0.012 ± 0.001 B/op` | `[0.011, 0.013]` | Zero application heap allocations inside primitive `long` loop |

---

## 8. Pairwise Statistical Comparisons and Overlap Analysis (Scenario 9)

Two benchmark results differ with statistical significance at the 99.9% confidence level only if their confidence intervals $[L_A, U_A]$ and $[L_B, U_B]$ do **not** overlap ($U_A < L_B$ or $U_B < L_A$). Any comparison where $[L_A, U_A] \cap [L_B, U_B] \neq \emptyset$ is marked **Inconclusive**.

| Comparison Pair | Metric | Benchmark A (99.9% CI) | Benchmark B (99.9% CI) | Intervals Overlap? | Verdict |
|---|---|---|---|---|---|
| `wrongDeadCodeBenchmark` vs. `fixedDeadCodeBenchmark` | Time (`µs/op`) | `[0.000647, 0.000768]` | `[1.541923, 1.873559]` | **No** (`0.000768 < 1.541923`) | **Statistically Significant** (`2,412.1x` slower when DCE is prevented) |
| `calculateSettlement` vs. `calculateSettlementPerRequestBigDecimal` (Final Run) | Time (`µs/op`) | `[0.130301, 0.161156]` | `[0.210613, 0.250062]` | **No** (`0.161156 < 0.210613`) | **Statistically Significant** (`-36.7%` faster when `BigDecimal` conversion is hoisted) |
| `calculateSettlement` vs. `calculateSettlementPerRequestBigDecimal` (Final Run) | Allocation (`B/op`) | `[0.313, 15.689]` | `[120.001, 120.002]` | **No** (`15.689 < 120.001`) | **Statistically Significant** (`-112.0 B/op` allocation reduction) |
| `calculateSettlement` vs. `calculateSettlementPerRequestBigDecimal` (Earlier Run with background OS noise) | Time (`µs/op`) | `0.341 ± 0.304` (`[0.036, 0.645]`) | `0.279 ± 0.072` (`[0.207, 0.351]`) | **Yes** (`[0.207, 0.351] \subset [0.036, 0.645]`) | **Inconclusive** — In our earlier run before quieting background tasks, Fork 2 experienced OS scheduling spikes (`2.670 µs/op`), widening `calculateSettlement`'s error bar to `± 0.304 µs/op` so its 99.9% CI completely overlapped `[0.207, 0.351]`. Treating that noisy `0.341` vs. `0.279 µs/op` difference as real would have been a false conclusion. |
| `wrongDeadCodeBenchmark` vs. `calculateSettlement` | Allocation (`MB/sec`) | `0.007 ± 0.001` (`[0.006, 0.008]`) | `46.230 ± 45.024` (`[1.206, 91.254]`) | **No** (`0.008 < 1.206`), but Fork 1 & 3 of `calculateSettlement` were `0.007 ± 0.001` (`[0.006, 0.008]`) | **Inconclusive within Forks 1 & 3** (overlapping `[0.006, 0.008] MB/sec` background JMH harness allocation rate when escape analysis succeeds) |
| `mapPayment` vs. `calculateSettlement` vs. `groupByMerchant` | Time (`µs/op`) | `[0.007, 0.009]` | `[0.130, 0.161]` vs. `[3.474, 4.128]` | **No** | **Statistically Significant** (`mapPayment < calculateSettlement < groupByMerchant`) |

**Statement on Final Suite Primary Metrics:** In the final quiet-machine run (`results.json`), none of the five distinct benchmark operations have overlapping 99.9% time confidence intervals (`[0.0006, 0.0008]`, `[0.007, 0.009]`, `[0.130, 0.161]`, `[0.211, 0.250]`, `[1.542, 1.874]`, `[3.474, 4.128] µs/op`), whereas in the earlier noisy run (`0.341 ± 0.304 µs/op` vs. `0.279 ± 0.072 µs/op`) the comparison between `calculateSettlement` and the per-request `BigDecimal` loop was **inconclusive** due to overlapping 99.9% confidence intervals.

---

## 9. Limits of This Microbenchmark Suite (Scenario 10)

This JMH suite measures isolated, single-threaded, in-memory execution and **does not** tell us at least the following four critical aspects of production behaviour:

1. **Behaviour Under Concurrency and Virtual Thread Scheduling:** Every benchmark in this suite runs on a single platform thread (`@Threads(1)`). It does not capture virtual-thread carrier mounting/unmounting, database connection-pool contention (`HikariCP`), lock contention, or CPU scheduler interference under `200` concurrent requests.
2. **CPU Cache Misses and GC Pressure at Production Data Sizes:** The `@State` fixture holds a fixed list of `100` `PaymentEntity` objects (`50` for `MR-4471`), which fits entirely inside the CPU's L1/L2 cache (~4–8 KB) and is allocated contiguously during `@Setup`. In production, a high-volume merchant may have tens of thousands of payment rows scattered across heap pages, causing LLC (L3) cache misses, TLB thrashing, and young-generation promotion pressure that this microbenchmark cannot reproduce. To measure this, we would need a `@Param({"50", "1000", "50000"})` JMH parameter sweep paired with `-prof perfnorm` hardware performance counters.
3. **End-to-End Live Request Path Costs (JDBC, Network I/O, and Spring AOP):** The JMH harness calls `SettlementService.calculateSettlement` against an in-memory stub repository and calls `PaymentController.toPaymentResponse` directly without Tomcat socket reads, Spring MVC `DispatcherServlet` routing, `@Valid` Bean Validation, Jackson JSON serialisation, or PostgreSQL TCP round-trips. In our live service (`SJV-L1` and `SJV-L2`), a full HTTP request takes `~15–120 ms` — four to five orders of magnitude larger than the `0.146 µs` in-memory calculation.
4. **Type-Profile Pollution in a Full Application JVM:** In an isolated JMH fork, `PaymentRepository.findByMerchantId` and `List.iterator()` have monomorphic call sites, allowing C2 to inline them aggressively. In a live Spring Boot JVM with dozens of repositories, AOP interceptors (`TransactionInterceptor`, `PersistenceExceptionTranslationInterceptor`), and `List` implementations, call sites can become megamorphic, preventing inlining and invalidating escape analysis.
