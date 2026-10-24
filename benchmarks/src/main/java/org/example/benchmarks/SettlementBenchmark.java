package org.example.benchmarks;

import org.example.ledgersettlement.PaymentController;
import org.example.ledgersettlement.PaymentEntity;
import org.example.ledgersettlement.PaymentRepository;
import org.example.ledgersettlement.SettlementService;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(3)
public class SettlementBenchmark {

    @State(Scope.Benchmark)
    public static class BenchmarkState {

        String targetMerchantId;
        double configuredFeeRate;
        long feeRateBasisPoints;
        int dceBatchSize;
        long baseAmountMinor;
        List<PaymentEntity> allPayments;
        List<PaymentEntity> merchantPayments;
        PaymentEntity sampleEntity;
        SettlementService settlementService;

        String rawId;
        String rawMerchantId;
        long rawAmountMinor;
        String rawCurrency;
        LocalDateTime rawRecordedAt;

        @Setup(Level.Trial)
        public void setup() {
            targetMerchantId = "MR-4471";
            configuredFeeRate = 0.031;
            feeRateBasisPoints = BigDecimal.valueOf(configuredFeeRate)
                    .multiply(BigDecimal.valueOf(10000))
                    .longValue();
            dceBatchSize = 1_000;
            baseAmountMinor = 128450L;
            rawId = "PAY-BENCH-001";
            rawMerchantId = "MR-4471";
            rawAmountMinor = 128450L;
            rawCurrency = "GBP";
            rawRecordedAt = LocalDateTime.of(2026, 9, 29, 12, 0);

            allPayments = new ArrayList<>(100);
            merchantPayments = new ArrayList<>(50);

            for (int i = 0; i < 100; i++) {
                PaymentEntity entity = new PaymentEntity();
                entity.setId("P-" + i);
                String merchant = (i % 2 == 0) ? "MR-4471" : "MR-9000";
                entity.setMerchantId(merchant);
                entity.setAmountMinor(128450L + (i * 100L));
                entity.setCurrency("GBP");
                entity.setRecordedAt(rawRecordedAt.plusMinutes(i));
                allPayments.add(entity);
                if ("MR-4471".equals(merchant)) {
                    merchantPayments.add(entity);
                }
            }

            sampleEntity = allPayments.getFirst();

            InvocationHandler handler = (proxy, method, args) -> {
                if ("findByMerchantId".equals(method.getName())) {
                    return merchantPayments;
                }
                throw new UnsupportedOperationException(method.getName());
            };

            PaymentRepository stubRepository = (PaymentRepository) Proxy.newProxyInstance(
                    PaymentRepository.class.getClassLoader(),
                    new Class<?>[]{PaymentRepository.class},
                    handler
            );

            settlementService = new SettlementService(
                    stubRepository,
                    () -> Map.of("MR-4471", "GBP", "MR-9000", "GBP")
            );
            settlementService.initializeService();
        }
    }

    /**
     * 1. Settlement calculation in SettlementService (long minor units + BigDecimal fee rate).
     */
    @Benchmark
    public long calculateSettlement(BenchmarkState state) {
        return state.settlementService.calculateSettlement(state.targetMerchantId);
    }

    /**
     * 2. Collection-heavy method in SettlementService: grouping PaymentEntity records by merchantId.
     */
    @Benchmark
    public Map<String, List<PaymentEntity>> groupByMerchant(BenchmarkState state) {
        return state.settlementService.groupByMerchant(state.allPayments);
    }

    /**
     * 3. Serialisation / mapping method: mapping entity fields into PaymentController.PaymentResponse DTO.
     */
    @Benchmark
    public PaymentController.PaymentResponse mapPayment(BenchmarkState state) {
        PaymentEntity entity = new PaymentEntity();
        entity.setId(state.rawId);
        entity.setMerchantId(state.rawMerchantId);
        entity.setAmountMinor(state.rawAmountMinor);
        entity.setCurrency(state.rawCurrency);
        entity.setRecordedAt(state.rawRecordedAt);
        return PaymentController.toPaymentResponse(entity);
    }

    /**
     * 4. Deliberately broken benchmark: computes 1,000 minor-unit settlement fee calculations
     * and discards the result without returning or consuming it, allowing JIT dead-code elimination.
     */
    @Benchmark
    public void wrongDeadCodeBenchmark(BenchmarkState state) {
        long totalOwed = 0L;
        long rate = state.feeRateBasisPoints;
        long base = state.baseAmountMinor;
        int n = state.dceBatchSize;
        for (int i = 0; i < n; i++) {
            long amount = base + i;
            long fee = amount * rate / 10000L;
            totalOwed += (amount - fee);
        }
        // Result intentionally discarded to demonstrate dead-code elimination (DCE)
    }

    /**
     * 5. Fixed benchmark: identical 1,000-payment settlement loop to wrongDeadCodeBenchmark,
     * but consumes the result via JMH Blackhole so the JIT compiler cannot eliminate the work.
     */
    @Benchmark
    public void fixedDeadCodeBenchmark(BenchmarkState state, Blackhole blackhole) {
        long totalOwed = 0L;
        long rate = state.feeRateBasisPoints;
        long base = state.baseAmountMinor;
        int n = state.dceBatchSize;
        for (int i = 0; i < n; i++) {
            long amount = base + i;
            long fee = amount * rate / 10000L;
            totalOwed += (amount - fee);
        }
        blackhole.consume(totalOwed);
    }

    /**
     * 6. Baseline settlement calculation variant with per-request BigDecimal fee-rate conversion,
     * used for statistical interval comparison against calculateSettlement.
     */
    @Benchmark
    public long calculateSettlementPerRequestBigDecimal(BenchmarkState state) {
        long feeRateBasisPoints = BigDecimal.valueOf(state.configuredFeeRate)
                .multiply(BigDecimal.valueOf(10000))
                .longValue();
        long totalOwed = 0L;
        for (PaymentEntity payment : state.merchantPayments) {
            long fee = payment.getAmountMinor() * feeRateBasisPoints / 10000;
            totalOwed += (payment.getAmountMinor() - fee);
        }
        return totalOwed;
    }
}
