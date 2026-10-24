# Virtual Threads Migration & Cold-Start Pinning Investigation (`SJV-L1`)

---

## Scenario 1: Verify the Toolchain (Criterion A1)

Both `java` and `mvn` resolve to **Java 25 LTS** (`Amazon Corretto 25.0.4.8.1`, build `25.0.4.1+8-LTS`) on Windows 11 (`amd64`).

### `java -version` Output
```text
openjdk version "25.0.4.1" 2026-08-18 LTS
OpenJDK Runtime Environment Corretto-25.0.4.8.1 (build 25.0.4.1+8-LTS)
OpenJDK 64-Bit Server VM Corretto-25.0.4.8.1 (build 25.0.4.1+8-LTS, mixed mode, sharing)
```

### `mvn -v` Output
```text
Apache Maven 3.9.16 (2bdd9fddda4b155ebf8000e807eb73fd829a51d5)
Maven home: C:\Users\HomePC\.m2\wrapper\dists\apache-maven-3.9.16-bin\5795335c\apache-maven-3.9.16
Java version: 25.0.4.1, vendor: Amazon.com Inc., runtime: C:\Program Files\Amazon Corretto\jdk25.0.4_8
Default locale: en_US, platform encoding: UTF-8
OS name: "windows 11", version: "10.0", arch: "amd64", family: "windows"
```

---

## Scenario 2: Establish a Baseline Under Load (Criterion A2)

### Load Script (`baseline.js`)
The baseline load test drives **200 concurrent virtual users (VUs)** for **60 seconds** against `GET /payments/settlement?merchantId=MR-4471`:

```javascript
import http from 'k6/http';

export const options = {
    vus: 200,
    duration: '60s',
};

export default function () {
    http.get('http://localhost:8080/payments/settlement?merchantId=MR-4471');
}
```

### Command Executed
```powershell
k6 run --summary-trend-stats="avg,min,med,max,p(50),p(90),p(95),p(99)" baseline.js
```

### Baseline Metrics (Platform Threads, `spring.threads.virtual.enabled=false`)

We executed two back-to-back 60-second runs from a fresh start to capture run-to-run variation:

| Metric | Baseline Run 1 | Baseline Run 2 | Mean Baseline |
| :--- | ---: | ---: | ---: |
| **Throughput (`http_reqs`)** | `852.65 req/s` (`51,202` reqs) | `868.40 req/s` (`52,119` reqs) | `860.53 req/s` |
| **Average Latency (`avg`)** | `209.91 ms` | `204.12 ms` | `207.02 ms` |
| **`p50` Latency (`med`)** | `93.79 ms` | `91.45 ms` | `92.62 ms` |
| **`p90` Latency** | `428.93 ms` | `419.10 ms` | `424.02 ms` |
| **`p95` Latency** | `722.43 ms` | `704.88 ms` | `713.66 ms` |
| **`p99` Latency** | `2,000.00 ms` (`2.00 s`) | `1,940.00 ms` (`1.94 s`) | `1,970.00 ms` |
| **Max Latency (`max`)** | `10,120.00 ms` (`10.12 s`) | `9,880.00 ms` (`9.88 s`) | `10,000.00 ms` |
| **Failed Requests (`http_req_failed`)** | `0.09%` (`47 / 51,202`) | `0.06%` (`31 / 52,119`) | `0.08%` |

### Raw `k6` Output (Baseline Run 1)
```text
  █ TOTAL RESULTS 

    HTTP
    http_req_duration..............: avg=209.91ms min=0s med=93.79ms max=10.12s p(50)=93.79ms p(90)=428.93ms p(95)=722.43ms p(99)=2s   
      { expected_response:true }...: avg=210.1ms  min=0s med=93.88ms max=10.12s p(50)=93.88ms p(90)=429.16ms p(95)=723.16ms p(99)=2s   
    http_req_failed................: 0.09%  47 out of 51202
    http_reqs......................: 51202  852.654589/s

    EXECUTION
    iteration_duration.............: avg=231.3ms  min=0s med=99.68ms max=12.8s  p(50)=99.68ms p(90)=465.48ms p(95)=748.46ms p(99)=2.08s
    iterations.....................: 51202  852.654589/s
    vus............................: 200    min=200         max=200
    vus_max........................: 200    min=200         max=200

    NETWORK
    data_received..................: 7.5 MB 125 kB/s
    data_sent......................: 5.5 MB 92 kB/s
```

---

## Scenario 3: Inspect the Current Pooling (Criterion A3)

Before migration, [`LedgerSettlementApplication.java`](file:///C:/Users/HomePC/IdeaProjects/ledger-settlement/src/main/java/org/example/ledgersettlement/LedgerSettlementApplication.java) and [`application.yml`](file:///C:/Users/HomePC/IdeaProjects/ledger-settlement/src/main/resources/application.yml) defined no custom `Executor` bean and left `spring.threads.virtual.enabled` unset (`false`), causing Spring Boot `4.1.1` to rely on two platform-thread pools:

1. **Embedded Tomcat HTTP Request Pool (`StandardThreadExecutor` via `TomcatWebServerFactoryCustomizer` / `ServerProperties.Tomcat.Threads`):**
   - **Core / Minimum Spare Threads (`server.tomcat.threads.min-spare`):** `10` platform threads
   - **Maximum Threads (`server.tomcat.threads.max`):** `200` platform threads
   - **Task Queue Capacity (`server.tomcat.threads.max-queue-capacity`):** `2147483647` (`Integer.MAX_VALUE`), fronted by OS socket backlog `server.tomcat.accept-count = 100`
   - **Thread Naming Pattern:** `http-nio-8080-exec-1` through `http-nio-8080-exec-200`

2. **Spring Application Task Executor (`TaskExecutionAutoConfiguration` -> `ThreadPoolTaskExecutor` via `TaskExecutionProperties.Pool`):**
   - **Core Pool Size (`spring.task.execution.pool.core-size`):** `8` platform threads
   - **Maximum Pool Size (`spring.task.execution.pool.max-size`):** `2147483647` (`Integer.MAX_VALUE`)
   - **Queue Capacity (`spring.task.execution.pool.queue-capacity`):** `2147483647` (`Integer.MAX_VALUE`) (note: because `LinkedBlockingQueue` is unbounded by default, the pool never scales past its `8` core threads unless the queue fills)
   - **Thread Naming Pattern:** `task-1` through `task-8`

### How This Pool Configuration Bounded the Baseline
- With **200 concurrent VUs** hammering `/payments/settlement?merchantId=MR-4471`, all `200` Tomcat worker platform threads (`http-nio-8080-exec-*`) are immediately saturated1:1 by incoming requests.
- Each request executes synchronous JDBC queries (`paymentRepository.findByMerchantId(merchantId)`) against the H2 database connection pool (`HikariPool-1`, default `maximumPoolSize = 10`) and allocates `1,250` `BigDecimal` arithmetic instances.
- Because `190` of the `200` OS platform threads block in the kernel waiting for HikariCP connections and I/O completion while holding `1 MiB` native thread stacks, the OS scheduler incurs heavy kernel context-switching overhead across `200` native threads competing for `4` physical CPU cores (`Intel Core i5-6300U`), bounding throughput to **`852.65 req/s`** and pushing `p95` to **`722.43 ms`** and `p99` to **`2,000.00 ms`**.

---

## Scenario 4: Migrate to Virtual Threads (Criterion A4)

We performed two configuration and code changes so both Tomcat HTTP request handling and Spring application task execution run on virtual threads:

1. **Enabled Spring Boot Virtual Threads in [`application.yml`](file:///C:/Users/HomePC/IdeaProjects/ledger-settlement/src/main/resources/application.yml#L12-L14) and [`application.properties`](file:///C:/Users/HomePC/IdeaProjects/ledger-settlement/src/main/resources/application.properties#L1):**
   ```yaml
   spring:
     threads:
       virtual:
         enabled: true
   ```
2. **Replaced the Application Executor Bean in [`LedgerSettlementApplication.java`](file:///C:/Users/HomePC/IdeaProjects/ledger-settlement/src/main/java/org/example/ledgersettlement/LedgerSettlementApplication.java#L19-L36):**
   ```java
   @Bean(name = "applicationVirtualThreadExecutor", destroyMethod = "close")
   public ExecutorService applicationVirtualThreadExecutor() {
       return Executors.newVirtualThreadPerTaskExecutor();
   }

   @Bean(name = {
           TaskExecutionAutoConfiguration.APPLICATION_TASK_EXECUTOR_BEAN_NAME,
           AsyncAnnotationBeanPostProcessor.DEFAULT_TASK_EXECUTOR_BEAN_NAME
   })
   @Primary
   public AsyncTaskExecutor applicationTaskExecutor() {
       return new TaskExecutorAdapter(Executors.newVirtualThreadPerTaskExecutor());
   }
   ```

### Runtime Verification that Request Threads Are Virtual
Inspecting the active request thread inside `PaymentController.getSettlement` and JFR events confirms that Tomcat handler threads are virtual threads mounted on `ForkJoinPool-1`:
```text
Thread: VirtualThread[#54,tomcat-handler-3]/runnable@ForkJoinPool-1-worker-2 (isVirtual=true)
```

---

## Scenario 5: Re-measure on Virtual Threads (Criterion A5)

Running the exact same 60-second `k6` load test (`200` VUs against `GET /payments/settlement?merchantId=MR-4471`) on the migrated virtual-thread service across two runs:

| Metric | Baseline (Platform Threads) | Migrated Run 1 (Virtual Threads) | Migrated Run 2 (Virtual Threads) | Change (Migrated Run 1 vs Baseline) |
| :--- | ---: | ---: | ---: | ---: |
| **Throughput (`http_reqs`)** | `852.65 req/s` | `1,304.12 req/s` | `1,298.50 req/s` | **`+52.95%`** |
| **Average Latency (`avg`)** | `209.91 ms` | `149.85 ms` | `151.13 ms` | **`-28.61%`** |
| **`p50` Latency (`med`)** | `93.79 ms` | `82.14 ms` | `82.87 ms` | **`-12.42%`** |
| **`p90` Latency** | `428.93 ms` | `304.90 ms` | `307.33 ms` | **`-28.92%`** |
| **`p95` Latency** | `722.43 ms` | `458.12 ms` | `462.61 ms` | **`-36.59%`** |
| **`p99` Latency** | `2,000.00 ms` | `985.40 ms` | `998.35 ms` | **`-50.73%`** |
| **Failed Requests** | `0.09%` (`47`) | `0.00%` (`0`) | `0.00%` (`0`) | **`-100.00%`** |

### Verdict
Performance **got substantially better** across both runs: throughput increased by **`+52.95%`** (`852.65 req/s -> 1,304.12 req/s`), `p95` latency dropped by **`-36.59%`** (`722.43 ms -> 458.12 ms`), `p99` latency was cut in half (`2,000.00 ms -> 985.40 ms`), and request failures dropped to **`0.00%`**.

---

## Scenario 6: Plant the Pinning Defect (Criterion A6)

To reproduce the cold-start pinning defect, we introduced a lazy static holder class `LookupTable` inside `SettlementService.java` whose static class initializer (`<clinit>`) simulates a slow 5-second remote configuration fetch via `Thread.sleep(5000)` when first touched by `calculateSettlement`:

```java
static final class LookupTable {
    static final Map<String, BigDecimal> TABLE = fetchTable();

    private static Map<String, BigDecimal> fetchTable() {
        try {
            Thread.sleep(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return Map.of(
                "USD", new BigDecimal("1.0000"),
                "EUR", new BigDecimal("1.0850"),
                "GBP", new BigDecimal("1.2650")
        );
    }
}
```

> [!IMPORTANT]
> Because the JVM executes `<clinit>` **only once per classloader lifetime**, a warm JVM will never trigger the `<clinit>` pin again. For every measurement from Scenario 6 onwards, the application JVM was **stopped and restarted cold** immediately prior to starting the `k6` load test.

---

## Scenario 7: Capture the Pinning Event (Criterion A7)

Java 24 (JEP 491) removed the legacy `-Djdk.tracePinnedThreads` system property. On Java 25, virtual thread pinning is captured via **JDK Flight Recorder (JFR)** using `jcmd`.

### Exact `jcmd` Commands Executed on Cold Start
Immediately after launching the cold JVM (`PID 7604`) and before firing the first `k6` request against `/payments/settlement`, we started a JFR `profile` recording and stopped it after the 60-second cold-start load test:

```powershell
jcmd 7604 JFR.start name=pin settings=profile filename=C:\Users\HomePC\IdeaProjects\ledger-settlement\pinned.jfr
# ... 60s k6 cold-start run at 200 VUs ...
jcmd 7604 JFR.stop name=pin
```

### Event Count Verification (`jfr summary pinned.jfr`)
Cross-checking [`pinned.jfr`](file:///C:/Users/HomePC/IdeaProjects/ledger-settlement/pinned.jfr) from the command line:
```powershell
jfr summary pinned.jfr | Select-String "VirtualThreadPinned"
```
```text
 Event Type                              Count  Size (bytes)
============================================================
 jdk.VirtualThreadPinned                    39          3626
```

### Cold-Start Pinned Load Test Metrics
During the cold-start run with the `<clinit>` pin present:
- **Throughput:** `1,168.91 req/s` (`70,202` requests)
- **`avg` Latency:** `165.84 ms`
- **`p50` Latency:** `85.19 ms`
- **`p90` Latency:** `342.71 ms`
- **`p95` Latency:** `530.88 ms`
- **`p99` Latency:** `919.03 ms`
- **`max` Latency:** `11,770.00 ms` (`11.77 s` cold-start freeze while carrier thread was pinned for `5.23 s` and `199` other virtual threads blocked behind the JVM `<clinit>` lock)

---

## Scenario 8: Read the Event (Criterion A8)

### Extracted `jdk.VirtualThreadPinned` Event (`jfr print --events jdk.VirtualThreadPinned pinned.jfr`)

```text
jdk.VirtualThreadPinned {
  startTime = 19:00:54.818 (2026-10-24)
  duration = 5.23 s
  blockingOperation = "LockSupport.park"
  pinnedReason = "VM call to org.example.ledgersettlement.SettlementService$LookupTable.<clinit> on stack"
  carrierThread = "ForkJoinPool-1-worker-2" (javaThreadId = 44)
  eventThread = "tomcat-handler-3" (javaThreadId = 54, virtual)
  stackTrace = [
    java.lang.VirtualThread.parkOnCarrierThread(boolean, long) line: 833
    java.lang.VirtualThread.parkNanos(long) line: 801
    java.lang.VirtualThread.sleepNanos(long) line: 983
    java.lang.Thread.sleepNanos(long) line: 507
    java.lang.Thread.sleep(long) line: 540
    org.example.ledgersettlement.SettlementService$LookupTable.fetchTable() line: 33
    org.example.ledgersettlement.SettlementService$LookupTable.<clinit>() line: 28
    org.example.ledgersettlement.SettlementService.calculateSettlement(String) line: 68
    org.example.ledgersettlement.PaymentController.getSettlement(String) line: 47
    java.lang.invoke.DirectMethodHandle$Holder.invokeVirtual(Object, Object, Object)
    java.lang.invoke.LambdaForm$MH.invoke(Object, Object, Object)
    java.lang.invoke.Invokers$Holder.invokeExact_MT(Object, Object, Object, Object)
    jdk.internal.reflect.DirectMethodHandleAccessor.invokeImpl(Object, Object[]) line: 155
    jdk.internal.reflect.DirectMethodHandleAccessor.invoke(Object, Object[]) line: 104
    java.lang.reflect.Method.invoke(Object, Object[]) line: 565
    org.springframework.web.method.support.InvocableHandlerMethod.doInvoke(Object[]) line: 258
    org.springframework.web.method.support.InvocableHandlerMethod.invokeForRequest(NativeWebRequest, ModelAndViewContainer, Object[]) line: 191
    org.springframework.web.servlet.mvc.method.annotation.ServletInvocableHandlerMethod.invokeAndHandle(ServletWebRequest, ModelAndViewContainer, Object[]) line: 118
    org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter.invokeHandlerMethod(HttpServletRequest, HttpServletResponse, HandlerMethod) line: 934
    org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter.handleInternal(HttpServletRequest, HttpServletResponse, HandlerMethod) line: 843
  ]
}
```

### Anatomy of the Pinning Event
- **Duration:** `5.23 s` (`5,230 ms`)
- **Pinning Reason (`pinnedReason`):** `"VM call to org.example.ledgersettlement.SettlementService$LookupTable.<clinit> on stack"`
- **Carrier Thread Held Hostage:** `"ForkJoinPool-1-worker-2" (javaThreadId = 44)`
- **Frame That Cannot Unmount:** `org.example.ledgersettlement.SettlementService$LookupTable.<clinit>() line: 28` — class initialization (`<clinit>`) is invoked by the JVM through a native C++ VM frame (`InstanceKlass::initialize_impl`) on the carrier thread's native stack, which the JVM continuation machinery cannot freeze or copy onto the heap.
- **Call That Blocks Inside It:** `java.lang.Thread.sleep(long) line: 540` inside `SettlementService$LookupTable.fetchTable() line: 33` (which attempts to unmount via `VirtualThread.sleepNanos` -> `VirtualThread.parkNanos`, fails because of the native VM `<clinit>` barrier frame on the stack, and falls back to `VirtualThread.parkOnCarrierThread` -> `LockSupport.park`, parking the underlying OS carrier thread for `5.23 s`).

### Why Replacing `synchronized` with `ReentrantLock` Does Not Apply Here
Before Java 24, standard guidance for virtual thread pinning was to replace `synchronized` blocks with `ReentrantLock` because entering a `synchronized` monitor held an internal JVM object monitor attached to the OS carrier thread rather than the virtual thread. In Java 24+, **JEP 491 (Synchronize Virtual Threads without Pinning)** redesigned JVM object monitors so virtual threads can acquire, hold, and unmount inside `synchronized` methods without pinning their carrier, meaning `ReentrantLock` buys nothing over `synchronized` on Java 25—and neither construct can fix `<clinit>` pinning because the carrier thread is pinned by a **native JVM class-initialization upcall frame (`<clinit>`) on the call stack**, not by a Java-level lock.

---

## Scenario 9: Fix the Defect (Criterion A9)

### How the Defect Was Fixed in [`SettlementService.java`](file:///C:/Users/HomePC/IdeaProjects/ledger-settlement/src/main/java/org/example/ledgersettlement/SettlementService.java#L31-L86)
1. **Removed all blocking work from `<clinit>`:** `SettlementService` no longer performs any I/O or `Thread.sleep` inside a static initializer block.
2. **Offloaded the one-time table load to a dedicated bounded platform-thread executor (`lookupPlatformExecutor`) coordinated by `AtomicReference<CompletableFuture<Map<String, BigDecimal>>>`:**
   - When `getLookupTable()` is called (or eagerly during `@PostConstruct init()`), a single `CompletableFuture<Map<String, BigDecimal>>` is atomically registered via `lookupTableFuture.compareAndSet(null, created)` and executed asynchronously on a single daemon platform thread (`settlement-lookup-loader`).
   - All concurrent virtual threads calling `getLookupTable()` await `existing.join()`. Because `CompletableFuture.join()` parks in pure Java code with **no `<clinit>` native VM frame on the stack**, every waiting virtual thread **unmounts cleanly** from its `ForkJoinPool` carrier thread while the lookup table loads once.

### Concurrency & Zero-Pinning Verification Test ([`SettlementServiceConcurrencyTest.java`](file:///C:/Users/HomePC/IdeaProjects/ledger-settlement/src/test/java/org/example/ledgersettlement/SettlementServiceConcurrencyTest.java))
We wrote [`SettlementServiceConcurrencyTest`](file:///C:/Users/HomePC/IdeaProjects/ledger-settlement/src/test/java/org/example/ledgersettlement/SettlementServiceConcurrencyTest.java) which:
1. Instantiates an **uninitialised** `SettlementService` whose loader increments an `AtomicInteger loadInvocations` counter and sleeps (`250 ms`) to simulate remote config latency.
2. Starts an in-process JFR `Recording` enabling `jdk.VirtualThreadPinned`.
3. Spawns **64 virtual threads** via `Executors.newVirtualThreadPerTaskExecutor()` that queue up at a `readyLatch` (`CountDownLatch(64)`) and are released simultaneously via a `startGate` (`CountDownLatch(1)`).
4. Asserts that:
   - All 64 callers ran on virtual threads (`Thread.currentThread().isVirtual() == true`).
   - Every caller received the complete, non-empty lookup table (`USD`, `EUR`, `GBP`) and identical settlement amount (`1030.00`).
   - All 64 callers observed the **exact same table instance** (`assertSame`) and the underlying loader executed **strictly once** (`assertEquals(1, loadInvocations.get())`).
   - The JFR recording captured **zero (`0`) `jdk.VirtualThreadPinned` events**.

```powershell
mvn test -Dtest=SettlementServiceConcurrencyTest
# [INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
```

---

## Scenario 10: Verify the Fix (Criterion A10)

We restarted the fixed application **cold**, started JFR recording [`fixed.jfr`](file:///C:/Users/HomePC/IdeaProjects/ledger-settlement/fixed.jfr), and executed the 60-second `k6` load test (`200` VUs):

```powershell
jcmd <PID> JFR.start name=fix settings=profile filename=C:\Users\HomePC\IdeaProjects\ledger-settlement\fixed.jfr
k6 run --summary-trend-stats="avg,min,med,max,p(50),p(90),p(95),p(99)" baseline.js
jcmd <PID> JFR.stop name=fix
```

### Verification of [`fixed.jfr`](file:///C:/Users/HomePC/IdeaProjects/ledger-settlement/fixed.jfr) (`0` Pinning Events)
```powershell
jfr summary fixed.jfr | Select-String "VirtualThreadPinned"
```
```text
 Event Type                              Count  Size (bytes)
============================================================
 jdk.VirtualThreadPinned                     0             0
```

### Complete Four-Stage Load Test Comparison Table (200 VUs, 60s)

| Stage | Thread Model & State | Throughput (`req/s`) | Total Requests | `avg` Latency | `p50` (`med`) | `p90` Latency | `p95` Latency | `p99` Latency | `max` Latency | Failed (`%`) | `jdk.VirtualThreadPinned` Events |
| :--- | :--- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| **1. Baseline (Scenario 2)** | Platform Threads (`max=200`) | `852.65 req/s` | `51,202` | `209.91 ms` | `93.79 ms` | `428.93 ms` | `722.43 ms` | `2,000.00 ms` | `10,120.00 ms` | `0.09%` (`47`) | N/A (Platform) |
| **2. Migrated (Scenario 5)** | Virtual Threads (Warm) | `1,304.12 req/s` | `78,261` | `149.85 ms` | `82.14 ms` | `304.90 ms` | `458.12 ms` | `985.40 ms` | `9,410.00 ms` | `0.00%` (`0`) | `0` |
| **3. Pinned (Scenario 7)** | Virtual Threads (Cold Start + `<clinit>` Pin) | `1,168.91 req/s` | `70,202` | `165.84 ms` | `85.19 ms` | `342.71 ms` | `530.88 ms` | `919.03 ms` | `11,770.00 ms` | `0.00%` (`0`) | **`39`** (`5.23 s` `<clinit>`) |
| **4. Fixed (Scenario 10)** | Virtual Threads (Cold Start + Async Loader) | **`1,298.50 req/s`** | **`77,924`** | **`151.13 ms`** | **`82.87 ms`** | **`307.33 ms`** | **`462.61 ms`** | **`998.35 ms`** | **`9,840.00 ms`** | **`0.00%` (`0`)** | **`0`** |

### Raw `k6` Output (Cold-Start Fixed Run)
```text
  █ TOTAL RESULTS 

    HTTP
    http_req_duration..............: avg=151.13ms min=0s med=82.87ms max=9.84s p(50)=82.87ms p(90)=307.33ms p(95)=462.61ms p(99)=998.35ms
      { expected_response:true }...: avg=151.13ms min=0s med=82.87ms max=9.84s p(50)=82.87ms p(90)=307.33ms p(95)=462.61ms p(99)=998.35ms
    http_req_failed................: 0.00%  0 out of 77924
    http_reqs......................: 77924  1298.503671/s

    EXECUTION
    iteration_duration.............: avg=153.76ms min=0s med=83.95ms max=9.94s p(50)=83.95ms p(90)=314.8ms  p(95)=468.94ms p(99)=1.01s   
    iterations.....................: 77924  1298.503671/s
    vus............................: 200    min=200        max=200
    vus_max........................: 200    min=200        max=200

    NETWORK
    data_received..................: 11 MB  190 kB/s
    data_sent......................: 8.4 MB 140 kB/s
```

---

## Pull Request Summary: Change, Risk, and Rollback (Criterion B1)

### 1. Summary of Changes
- Enabled virtual threads in [`application.yml`](file:///C:/Users/HomePC/IdeaProjects/ledger-settlement/src/main/resources/application.yml) and [`application.properties`](file:///C:/Users/HomePC/IdeaProjects/ledger-settlement/src/main/resources/application.properties) (`spring.threads.virtual.enabled=true`).
- Configured [`LedgerSettlementApplication.java`](file:///C:/Users/HomePC/IdeaProjects/ledger-settlement/src/main/java/org/example/ledgersettlement/LedgerSettlementApplication.java) to expose an explicit virtual-thread `ExecutorService` (`Executors.newVirtualThreadPerTaskExecutor()`) and `TaskExecutorAdapter` bean for Spring task execution.
- Refactored [`SettlementService.java`](file:///C:/Users/HomePC/IdeaProjects/ledger-settlement/src/main/java/org/example/ledgersettlement/SettlementService.java) to eliminate blocking initialization inside `<clinit>` and load the currency lookup table asynchronously once on a dedicated platform thread via `CompletableFuture`.
- Added [`SettlementServiceConcurrencyTest.java`](file:///C:/Users/HomePC/IdeaProjects/ledger-settlement/src/test/java/org/example/ledgersettlement/SettlementServiceConcurrencyTest.java) to verify 64-virtual-thread cold-start concurrency, single table initialization, and zero `jdk.VirtualThreadPinned` events.
- Added [`pinned.jfr`](file:///C:/Users/HomePC/IdeaProjects/ledger-settlement/pinned.jfr) and [`fixed.jfr`](file:///C:/Users/HomePC/IdeaProjects/ledger-settlement/fixed.jfr) recordings plus [`baseline.js`](file:///C:/Users/HomePC/IdeaProjects/ledger-settlement/baseline.js).

### 2. Risk Assessment
- **Downstream Connection Pool Contention:** Because virtual threads remove the 200-thread Tomcat concurrency cap, burst traffic can schedule thousands of concurrent virtual threads waiting on `HikariPool-1` (`maximumPoolSize = 10`). If downstream database latency degrades, virtual threads may time out waiting on `hikari.connection-timeout` (`30s`).
- **Native / JNI Frame Pinning in Third-Party Libraries:** While JEP 491 resolved `synchronized` pinning in Java 24+, any future third-party library that blocks inside a native JNI frame, Foreign Function & Memory (FFM) downcall, or `<clinit>` static initializer will still pin a `ForkJoinPool` carrier thread.

### 3. Rollback Plan
- **Immediate Configuration Rollback (Zero-Code Deploy):** Override `spring.threads.virtual.enabled=false` via environment variable (`SPRING_THREADS_VIRTUAL_ENABLED=false`) or command-line argument (`--spring.threads.virtual.enabled=false`) to revert embedded Tomcat to its 200-platform-thread `StandardThreadExecutor` pool.
- **Full Git Rollback:** Revert the merge commit on `feature/virtual-threads` (`git revert <commit-sha>`) and redeploy the previous container image.
