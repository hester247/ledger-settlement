@'
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