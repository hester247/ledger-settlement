# Virtual Thread Migration and Pinning Hunt

## Baseline

The application was tested using the main read endpoint with 200 concurrent
virtual users for 60 seconds.

### Baseline Results

- **Iterations:** 102,923
- **Throughput:** 1,718.886 req/s
- **Average latency:** 115.5 ms
- **p95 latency:** 169.49 ms
- **p99 latency:** 236.12 ms
- **Failed requests:** 0.00%

## Virtual Thread Migration

Virtual threads were enabled with:

```properties
spring.threads.virtual.enabled=true
```

Paste everything below:

```markdown
The application web workload was migrated to virtual threads.

The same load profile was then executed again using 200 concurrent users
for 60 seconds.

### Virtual Thread Load Results

- **Iterations:** 93,797
- **Throughput:** 1,561.441 req/s
- **Average latency:** 126.75 ms
- **p95 latency:** 275.59 ms
- **p99 latency:** 703.81 ms
- **Failed requests:** 0.00%

## Pinned JFR Evidence

A realistic virtual-thread pinning defect was introduced by performing the
lookup-table initialization during request processing.

A `jdk.VirtualThreadPinned` event was captured in `pinned.jfr`.

### Observed Event

- **Duration:** 213.780 ms
- **Blocking operation:** Contended monitor enter
- **Pinned reason:** Freeze or preempt failed (2)
- **Event thread:** tomcat-handler-89
- **Carrier thread:** ForkJoinPool-1-worker-4
- **Frame that could not unmount:**
  `org.example.ledgersettlement.SettlementService.calculateSettlement(String)`

### Stack Trace

The captured pinned-event stack included:

```text
org.springframework.transaction.interceptor.TransactionInterceptor.invoke(...)
org.springframework.aop.framework.ReflectiveMethodInvocation.proceed(...)
org.springframework.dao.support.PersistenceExceptionTranslationInterceptor.invoke(...)
org.springframework.aop.framework.ReflectiveMethodInvocation.proceed(...)
org.springframework.aop.framework.JdkDynamicAopProxy.invoke(...)
jdk.proxy2.$Proxy123.findByMerchantId(String)
org.example.ledgersettlement.SettlementService.calculateSettlement(String)