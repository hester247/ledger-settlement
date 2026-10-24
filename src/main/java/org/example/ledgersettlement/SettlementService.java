package org.example.ledgersettlement;

import jakarta.annotation.PreDestroy;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Service
public class SettlementService {

    private final PaymentRepository paymentRepository;
    private final Supplier<Map<String, String>> tableLoader;
    private final ExecutorService lookupPlatformExecutor;
    private final AtomicReference<CompletableFuture<Map<String, String>>> lookupTableFuture =
            new AtomicReference<>();

    @Value("${ledger.fee-rate:0.031}")
    private double feeRate = 0.031;

    private long feeRateInteger = 310L;

    @Autowired
    public SettlementService(PaymentRepository paymentRepository) {
        this(paymentRepository, SettlementService::defaultFetchTable);
    }

    public SettlementService(
            PaymentRepository paymentRepository,
            Supplier<Map<String, String>> tableLoader) {
        this.paymentRepository = paymentRepository;
        this.tableLoader = tableLoader;
        this.lookupPlatformExecutor = Executors.newFixedThreadPool(
                2,
                Thread.ofPlatform().name("lookup-loader-", 0).daemon(true).factory()
        );
    }

    /**
     * Converts the configured percentage fee to integer basis points once
     * during service initialization rather than for every settlement request,
     * and eagerly triggers lookup table loading off the virtual-thread request path.
     *
     * 0.031 * 10000 = 310
     */
    @PostConstruct
    public void initializeService() {
        this.feeRateInteger = BigDecimal.valueOf(feeRate)
                .multiply(BigDecimal.valueOf(10000))
                .longValue();
        getLookupTable();
    }

    @PreDestroy
    public void shutdownExecutor() {
        lookupPlatformExecutor.close();
    }

    /**
     * Loads the merchant lookup table outside any class initializer (<clinit>),
     * computing it once on a bounded platform-thread executor so virtual threads
     * waiting on the result unmount cleanly and never pin their carrier threads.
     */
    public Map<String, String> getLookupTable() {
        CompletableFuture<Map<String, String>> future = lookupTableFuture.get();
        if (future == null) {
            CompletableFuture<Map<String, String>> created = new CompletableFuture<>();
            if (lookupTableFuture.compareAndSet(null, created)) {
                CompletableFuture.supplyAsync(tableLoader, lookupPlatformExecutor)
                        .whenComplete((result, ex) -> {
                            if (ex != null) {
                                created.completeExceptionally(ex);
                            } else {
                                created.complete(Collections.unmodifiableMap(new HashMap<>(result)));
                            }
                        });
                future = created;
            } else {
                future = lookupTableFuture.get();
            }
        }
        return future.join();
    }

    private static Map<String, String> defaultFetchTable() {
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }

        Map<String, String> table = new HashMap<>();
        table.put("MR-4471", "GBP");
        return Collections.unmodifiableMap(table);
    }

    @Transactional
    public PaymentEntity recordPayment(PaymentEntity payment) {
        return paymentRepository.save(payment);
    }

    /**
     * Groups payment entities by merchant identifier.
     */
    public Map<String, List<PaymentEntity>> groupByMerchant(List<PaymentEntity> payments) {
        return payments.stream()
                .collect(Collectors.groupingBy(PaymentEntity::getMerchantId));
    }

    /**
     * Calculates the settlement amount in minor units for a merchant.
     *
     * Stays on long minor units and BigDecimal fee-rate conversion rather than binary
     * floating-point arithmetic.
     */
    public long calculateSettlement(String merchantId) {

        getLookupTable().get(merchantId);

        List<PaymentEntity> payments =
                paymentRepository.findByMerchantId(merchantId);

        if (payments.isEmpty()) {
            throw new IllegalArgumentException(
                    "Unknown merchant: " + merchantId
            );
        }

        long totalOwed = 0L;

        for (PaymentEntity payment : payments) {

            long fee =
                    payment.getAmountMinor()
                            * feeRateInteger
                            / 10000;

            long netAmount =
                    payment.getAmountMinor() - fee;

            totalOwed += netAmount;
        }

        return totalOwed;
    }
}
