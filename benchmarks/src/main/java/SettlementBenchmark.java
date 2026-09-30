package org.example.benchmarks;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5)
@Measurement(iterations = 10)
@Fork(3)
public class SettlementBenchmark {

    @State(Scope.Benchmark)
    public static class BenchmarkState {

        List<Object> payments;
        Object settlementService;

        Method calculateMerchantSettlement;
        Method groupByMerchant;

        Constructor<?> paymentConstructor;

        @Setup
        public void setup() throws Exception {

            Class<?> paymentClass =
                    Class.forName("Payment");

            Class<?> paymentStatusClass =
                    Class.forName("PaymentStatus");

            paymentConstructor =
                    paymentClass.getConstructor(
                            String.class,
                            String.class,
                            long.class,
                            String.class,
                            paymentStatusClass,
                            LocalDate.class
                    );

            Object status =
                    paymentStatusClass.getEnumConstants()[0];

            payments = new ArrayList<>();

            for (int i = 0; i < 100; i++) {
                payments.add(
                        paymentConstructor.newInstance(
                                "P-" + i,
                                i % 2 == 0 ? "MR-4471" : "MR-9000",
                                128450L,
                                "GBP",
                                status,
                                LocalDate.of(2026, 1, 1)
                        )
                );
            }

            Class<?> ledgerSettlementClass =
                    Class.forName("LedgerSettlement");

            calculateMerchantSettlement =
                    ledgerSettlementClass.getMethod(
                            "calculateMerchantSettlement",
                            List.class,
                            String.class
                    );

            Class<?> serviceClass =
                    Class.forName("SettlementService");

            settlementService =
                    serviceClass.getConstructor().newInstance();

            groupByMerchant =
                    serviceClass.getMethod(
                            "groupByMerchant",
                            List.class
                    );
        }
    }

    /*
     * REAL METHOD 1:
     * Settlement calculation
     */
    @Benchmark
    public long calculateMerchantSettlement(
            BenchmarkState state) throws Exception {

        return (long) state.calculateMerchantSettlement.invoke(
                null,
                state.payments,
                "MR-4471"
        );
    }

    /*
     * REAL METHOD 2:
     * String/collection-heavy operation
     */
    @Benchmark
    public Map<String, List<Object>> groupByMerchant(
            BenchmarkState state) throws Exception {

        @SuppressWarnings("unchecked")
        Map<String, List<Object>> result =
                (Map<String, List<Object>>)
                        state.groupByMerchant.invoke(
                                state.settlementService,
                                state.payments
                        );

        return result;
    }

    /*
     * REAL METHOD 3:
     * Mapping/parsing operation
     */
    @Benchmark
    public Object mapPayment(
            BenchmarkState state) throws Exception {

        Class<?> paymentStatusClass =
                Class.forName("PaymentStatus");

        Object status =
                paymentStatusClass.getEnumConstants()[0];

        return state.paymentConstructor.newInstance(
                "P-999",
                "MR-4471",
                128450L,
                "GBP",
                status,
                LocalDate.of(2026, 1, 1)
        );
    }

    /*
     * DELIBERATELY WRONG BENCHMARK:
     * The calculated result is never used.
     * The compiler may eliminate the work.
     */
    @Benchmark
    public void wrongDeadCodeBenchmark() {

        long result = 0;

        for (int i = 0; i < 1_000_000; i++) {
            result += (long) i * i;
        }
    }

    /*
     * FIXED BENCHMARK:
     * Blackhole makes sure the result is actually consumed.
     */
    @Benchmark
    public void fixedDeadCodeBenchmark(
            Blackhole blackhole) {

        long result = 0;

        for (int i = 0; i < 1_000_000; i++) {
            result += (long) i * i;
        }

        blackhole.consume(result);
    }
}