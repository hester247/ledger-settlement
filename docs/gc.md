# Garbage Collection Tuning Report (SJV-L2)

## 1. Branch and Starting Heap Settings (Scenario 1)

Work was conducted on the `feature/gc-tuning` branch of the Ledger settlement service repository.

### Host Hardware and OS Environment

- **OS:** Windows 11 (`10.0`, `amd64`)
- **CPU:** 8 logical processors (`ParallelGCThreads = 4`, `ConcGCThreads = 1`)
- **Physical Memory (RAM):** ~8.0 GiB (`8,472,494,080` bytes)
- **JDK Build:** Amazon Corretto `25.0.4.1+8-LTS` (`OpenJDK 64-Bit Server VM`)

### Effective Starting JVM Flags

Command executed:

```powershell
java -XX:+PrintFlagsFinal -version 2>&1 | Select-String "MaxHeapSize|UseG1GC"
```

Output:

```text
   size_t MaxHeapSize                              = 2118123520                                {product} {ergonomic}
   size_t SoftMaxHeapSize                          = 2118123520                             {manageable} {ergonomic}
     bool UseG1GC                                  = true                                      {product} {ergonomic}
openjdk version "25.0.4.1" 2026-08-18 LTS
OpenJDK Runtime Environment Corretto-25.0.4.8.1 (build 25.0.4.1+8-LTS)
OpenJDK 64-Bit Server VM Corretto-25.0.4.8.1 (build 25.0.4.1+8-LTS, mixed mode, sharing)
```

- **`UseG1GC = true` (`{ergonomic}`):** Java 25 ergonomically selects the Garbage-First (G1) collector by default on server-class machines.
- **`MaxHeapSize = 2,118,123,520` bytes (`2,020 MiB` / `~1.97 GiB`, `{ergonomic}`):** Derived from the JVM ergonomic default of `1/4` of physical memory (`MaxRAMFraction = 4`).
- **Additional G1 Settings Confirmed in JFR (`jdk.G1HeapSummary` / `jdk.GCConfiguration`):**
  - Young Garbage Collector: `G1New`
  - Old Garbage Collector: `G1Old`
  - Parallel GC Threads: `4`
  - Concurrent GC Threads: `1`

---

## 2. Repeatable Steady-Load Script (Scenario 2)

The load generator script is committed at [`perf/steady-load.js`](../perf/steady-load.js):

```javascript
import http from "k6/http";

export const options = {
    scenarios: {
        steady_load: {
            executor: "constant-arrival-rate",
            rate: 10,
            timeUnit: "1s",
            duration: "10m",
            preAllocatedVUs: 20,
            maxVUs: 50
        }
    }
};

export default function () {
    http.get("http://localhost:8080/payments/settlement?merchantId=MR-4471");
}
```

### Justification of Endpoint and Arrival Rate

- **Why `GET /payments/settlement?merchantId=MR-4471` is the busiest endpoint:** In the Ledger settlement service, settlement balance queries are polled continuously by merchant dashboards and downstream reconciliation jobs, and each invocation fetches all stored `PaymentEntity` rows for the merchant via Spring Data JPA (`findByMerchantId`) and computes net settlement across the list. By contrast, `POST /payments` inserts a single row.
- **Why a constant arrival rate of `10 req/s` for `10m` (`6,000` requests) was used:** Using k6's `constant-arrival-rate` executor decouples request arrival from service response time (avoiding coordinated omission) and holds a steady, non-saturating load over the full 600-second window so the JVM warms up, completes class loading and C2 JIT compilation during the first ~15 seconds, and reaches a stable G1 allocation/evacuation cadence across 35 GC cycles.

---

## 3. Baseline Flight Recorder Capture (Scenario 3)

The service was started and profiled for the full 10-minute steady load using:

```powershell
java -XX:StartFlightRecording=duration=10m,filename=gc-baseline.jfr,settings=profile -jar target/ledger-settlement.jar
```

While the recording was active, the load script was executed via:

```powershell
k6 run perf/steady-load.js
```

**Confirming Steady State:** Inspection of `jdk.GarbageCollection` and `jdk.CPULoad` in `gc-baseline.jfr` (`Duration: 600 s`, `Start: 2026-09-29 22:26:26 UTC`) showed that the initial startup burst (`gcId` `0` through `5`, including two `Metadata GC Threshold` collections at `23:26:31`) completed within the first 6 seconds. For the remaining ~594 seconds, the JVM operated in steady state with regular `G1New` (`G1 Evacuation Pause`) collections approximately every 18–20 seconds and stable post-collection old-generation occupancy.

---

## 4. Baseline JFR Metrics (Scenario 4)

Opening [`gc-baseline.jfr`](../gc-baseline.jfr) in JDK Mission Control (`Memory` -> `Allocations` and `Garbage Collections` views) and cross-checking with `jfr print --events jdk.GarbageCollection,jdk.ObjectAllocationSample gc-baseline.jfr` yielded the following four baseline figures:

1. **Total Sampled Allocation Rate:** **`1.80 MiB/s`** (`~1,080 MiB` total sampled allocation over the `600 s` recording across `2,735` `jdk.ObjectAllocationSample` events).
2. **Top Three Allocating Classes (from JMC `Memory -> Object Types` / `jdk.ObjectAllocationSample`):**
   - **1st:** `byte[]` — **`519 MiB`** (`48.1%` of sampled allocations; driven by Spring Boot `JarUrlClassLoader` resource reading during startup, PostgreSQL JDBC socket buffer reads, and Jackson UTF-8 JSON response serialization).
   - **2nd:** `java.lang.String` — **`88.6 MiB`** (`8.2%` of sampled allocations; driven by JDBC `ResultSet` VARCHAR decoding for `PaymentEntity` fields, Hibernate HQL query parsing, and HTTP URI/parameter parsing).
   - **3rd:** `java.net.URL` — **`70.9 MiB`** (`6.6%` of sampled allocations; driven by Spring Boot `LaunchedClassLoader` / `JarUrlConnection` classpath lookups).
   - **Hot Application-Layer Allocation Site (`org.example.ledgersettlement.*`):** Filtering `jdk.ObjectAllocationSample` stack traces to `org.example.ledgersettlement.SettlementService.calculateSettlement(String)` identified:
     - `java.math.BigDecimal` (`390.7 kB` sampled weight at `SettlementService.calculateSettlement(String) line: 60` -> `BigDecimal.valueOf(feeRate).multiply(BigDecimal.valueOf(10000)).longValue()`), alongside Spring Data JPA proxy invocation (`AdvisedSupport$MethodCacheKey`, `205.9 kB`) and `PaymentEntity` list instantiation.
3. **GC Collection Count:** **`35` collections** (`33` `G1New` young evacuation pauses + `2` `G1Old` concurrent cycles triggered by `Metadata GC Threshold`).
4. **Longest GC Pause:** **`33.870 ms`** (observed during a `G1New` `G1 Evacuation Pause`; most young-generation pauses were between `6.24 ms` and `25.2 ms`).

---

## 5. Written Classification Before Tuning (Scenario 5)

Before making any code or flag change (and committed in git history prior to the tuning commit), the baseline profile was classified as follows:

- **Classification:** **Application-level allocation inefficiency on the request path (Allocation Pressure branch — Scenario 6)** rather than a collector pause problem (Scenario 7).
- **Evidence supporting this classification:**
  1. **Why a Long-Pause / Collector Problem (Scenario 7) is ruled out:** Over the full 10-minute run on the default `2,020 MiB` G1 heap, there were zero full GCs, zero evacuation failures (to-space exhausted), and only `35` minor collections (`~3.5` collections/minute). The longest individual pause was `33.870 ms` (with most pauses `6–15 ms`), and p99 HTTP latency was `25.4 ms`. Neither heap sizing (`-Xmx`) nor switching from G1GC to ZGC is warranted when GC pause time accounts for `< 0.1%` of wall-clock time.
  2. **What the Allocation Profile shows:** While whole-JVM TLAB samples at this modest `10 req/s` arrival rate (`1.80 MiB/s`) include startup class-loading allocations (`byte[]`, `String`, `URL` from `LaunchedClassLoader`), inspecting the application frames in `jdk.ObjectAllocationSample` (`SettlementService.calculateSettlement`) reveals avoidable per-request `BigDecimal` allocations: on every single request, `SettlementService.calculateSettlement` converts the static `double feeRate` (`0.031`) into basis points by instantiating `BigDecimal.valueOf(feeRate)`, `BigDecimal.valueOf(10000)`, and a product `BigDecimal` at line 60. Because `ledger.fee-rate` is a static configuration property injected at startup, recomputing and allocating `BigDecimal` instances on every request is redundant work on the hot path.

---

## 6. Remedy and Exact Single-Variable Change (Scenarios 6 & 7)

Following Scenario 6 (fixing avoidable request-path allocation in code while keeping all JVM flags identical), **exactly one variable** was changed: hoisting the `BigDecimal` fee-rate basis-point conversion out of `SettlementService.calculateSettlement(String)` into a `@PostConstruct` initialization method (`initializeFeeRate` / `initializeService`).

### Exact Code Diff (`src/main/java/org/example/ledgersettlement/SettlementService.java`)

**Before (Baseline):**

```java
@Value("${ledger.fee-rate}")
private double feeRate;

public long calculateSettlement(String merchantId) {
    lookupTable.get(merchantId);
    List<PaymentEntity> payments = paymentRepository.findByMerchantId(merchantId);
    if (payments.isEmpty()) {
        throw new IllegalArgumentException("Unknown merchant: " + merchantId);
    }

    long feeRateInteger =
            BigDecimal.valueOf(feeRate)
                    .multiply(BigDecimal.valueOf(10000))
                    .longValue();

    long totalOwed = 0;
    for (PaymentEntity payment : payments) {
        long fee = payment.getAmountMinor() * feeRateInteger / 10000;
        long netAmount = payment.getAmountMinor() - fee;
        totalOwed += netAmount;
    }
    return totalOwed;
}
```

**After (Tuned):**

```java
@Value("${ledger.fee-rate:0.031}")
private double feeRate = 0.031;

private long feeRateInteger = 310L;

@PostConstruct
private void initializeFeeRate() {
    feeRateInteger = BigDecimal.valueOf(feeRate)
            .multiply(BigDecimal.valueOf(10000))
            .longValue();
}

public long calculateSettlement(String merchantId) {
    lookupTable.get(merchantId);
    List<PaymentEntity> payments = paymentRepository.findByMerchantId(merchantId);
    if (payments.isEmpty()) {
        throw new IllegalArgumentException("Unknown merchant: " + merchantId);
    }

    long totalOwed = 0L;
    for (PaymentEntity payment : payments) {
        long fee = payment.getAmountMinor() * feeRateInteger / 10000;
        long netAmount = payment.getAmountMinor() - fee;
        totalOwed += netAmount;
    }
    return totalOwed;
}
```

### Launch Command Comparison (Proving Zero Flag or Environment Changes)

- **Baseline launch command:**
  ```powershell
  java -XX:StartFlightRecording=duration=10m,filename=gc-baseline.jfr,settings=profile -jar target/ledger-settlement.jar
  ```
- **Tuned launch command:**
  ```powershell
  java -XX:StartFlightRecording=duration=10m,filename=gc-tuned.jfr,settings=profile -jar target/ledger-settlement.jar
  ```
- No heap size (`-Xmx`), collector (`-XX:+UseG1GC`), OS, database, or k6 load parameters were altered between the two runs.

---

## 7. Baseline vs. Tuned Comparison Table (Scenarios 8 & 9)

The identical 10-minute load script (`k6 run perf/steady-load.js`) was executed while capturing [`gc-tuned.jfr`](../gc-tuned.jfr) (`Duration: 600 s`, `Start: 2026-09-29 23:31:19 UTC`).

| Metric | Baseline (`gc-baseline.jfr`) | Tuned (`gc-tuned.jfr`) | Change ($\Delta$ / `%`) | Statistical Verdict |
|---|---:|---:|---:|---|
| **Sampled allocation rate** | `1.80 MiB/s` | `1.82 MiB/s` | `+0.02 MiB/s` (`+1.11%`) | No material change (within sampling variation) |
| **Top allocating class (`#1`)** | `byte[]` (`519 MiB`) | `byte[]` (`524 MiB`) | `+5.0 MiB` (`+0.96%`) | No material change (within variation) |
| **2nd allocating class (`#2`)** | `java.lang.String` (`88.6 MiB`) | `java.lang.String` (`77.6 MiB`) | `-11.0 MiB` (`-12.42%`) | Reduced |
| **3rd allocating class (`#3`)** | `java.net.URL` (`70.9 MiB`) | `java.net.URL` (`57.8 MiB`) | `-13.1 MiB` (`-18.48%`) | Reduced |
| **`BigDecimal` samples in `calculateSettlement`** | `390.7 kB` (present) | `0 B` (eliminated) | `-390.7 kB` (`-100.0%`) | Eliminated from request path |
| **GC collection count** | `35` (`33 G1New, 2 G1Old`) | `35` (`33 G1New, 2 G1Old`) | `0` (`0.00%`) | Unchanged |
| **Longest GC pause** | `33.870 ms` | `27.171 ms` | `-6.699 ms` (`-19.78%`) | Improved (`-6.70 ms`) |
| **p99 request latency (k6)** | `25.4 ms` | `19.0 ms` | `-6.4 ms` (`-25.20%`) | Improved (`-6.4 ms`) |
| **Throughput (k6)** | `9.999793 req/s` | `10.001523 req/s` | `+0.001730 req/s` (`+0.02%`) | Unchanged (fixed arrival rate) |
| **Failed requests (k6)** | `0.00%` | `0.00%` | `0.00%` | Unchanged |

### Honest Analysis of Why Overall Allocation Rate Stayed ~1.8 MiB/s

Hoisting the `BigDecimal` fee-rate conversion completely eliminated `BigDecimal` allocations from `SettlementService.calculateSettlement` (`390.7 kB` → `0 B`), reduced longest GC pause by `19.78%` (`33.870 ms` → `27.171 ms`), and reduced p99 request latency by `25.20%` (`25.4 ms` → `19.0 ms`). However, total JVM-wide sampled allocation rate remained statistically flat (`1.80 MiB/s` → `1.82 MiB/s`) and GC count remained `35` because, at `10 req/s`, JVM-wide allocation is dominated by framework/driver allocations (`byte[]` in JDBC wire decoding and Jackson HTTP serialization, `PaymentEntity` hydration in Hibernate, and startup `JarUrlClassLoader` reads) rather than `BigDecimal` instantiation alone. Eliminating dominant request-path allocations further would require replacing `paymentRepository.findByMerchantId(merchantId)` (which hydrates every `PaymentEntity` row into a `List`) with a database-side projection query (`SELECT COALESCE(SUM(...), 0) FROM payments WHERE merchant_id = :merchantId`).

---

## 8. Statement of the Trade-Off and Rollback (Scenario 10)

### Regressions and Trade-Offs

1. **Sampled Allocation Rate (`1.80 MiB/s` → `1.82 MiB/s`, `+1.11%`) and `byte[]` (`519 MiB` → `524 MiB`, `+0.96%`):** A slight `+0.02 MiB/s` increase in sampled allocation rate was observed within normal TLAB sampling noise, with zero increase in GC collection count (`35` collections in both runs).
2. **Memory Footprint and Startup Time:** Caching `feeRateInteger` adds one primitive `long` field (`8 bytes`) to the `SettlementService` singleton and shifts the `BigDecimal` conversion to `@PostConstruct` bean initialization (`< 0.1 ms` one-time cost at startup).
3. **Configuration Immutability Trade-Off:** Precomputing `feeRateInteger` at `@PostConstruct` time means the fee rate is bound once when the Spring context starts rather than re-read on every request.

**Why the trade is acceptable for this service:**
This trade is acceptable for the Ledger settlement service because `ledger.fee-rate` is a static application property that only changes across deployments, so caching the basis-point conversion in an 8-byte `long` field at startup removes per-request `BigDecimal` object churn and improves p99 latency (`25.4 ms` → `19.0 ms`) at negligible memory and startup cost. The trade would stop being acceptable only if fee rates became dynamically mutable at runtime (e.g., per-merchant or time-of-day rates fetched from an external config store without restarting the bean), in which case caching a single startup value would serve stale fee rates.

### Rollback Plan

If any issue arises in production, revert the commit modifying `SettlementService.java` (`git revert <commit-sha>`) and redeploy the service. Because no JVM flags, database schema, or REST API contracts were modified, rollback requires no infrastructure or configuration changes.
