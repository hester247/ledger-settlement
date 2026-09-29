# Virtual Thread Migration and Pinning Hunt

## Baseline

The application was teste# Virtual Thread Migration and Pinning Hunt

## Baseline

The application was tested using the main read endpoint with 200 concurrent
virtual users for 60 seconds.

### Baseline Results

- **Iterations:** 102,923
- **Throughput:** 1,718.886 req/s
- **Average latency:** 115.5 ms
- **p95 latency:** 169.49 msGet-ChildItem src/main/java/org -Recurse -File | Select-Object FullName
- **p99 latency:** 236.12 ms
- **Failed requests:** 0.00%

## Virtual Thread Migration

Virtual threads were enabled with:

```properties
spring.threads.virtual.enabled=trued using the main read endpoint with 200 concurrent
virtual users for 60 seconds.

### Baseline Results

- **Iterations:** 102,923
- **Throughput:** 1,718.886 req/s
- **Average latency:** 115.5 ms
- **p95 latency:** 169.49 ms
- **p99 latency:** 236.12 ms
- **Failed requests:** 0.00%
git diff --cached --checkgit log --oneline --all --decorate -15git switch -c spring-app-baseline backup-before-pr-fix
## Virtual Thread Migration

Virtual threads were enabled with:

```properties
spring.threads.virtual.enabled=true