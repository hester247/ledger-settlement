package org.example.ledgersettlement;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

@Service
public class SettlementService {

    private static final BigDecimal FEE_RATE = new BigDecimal("0.03");
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final PaymentRepository paymentRepository;
    private final Supplier<Map<String, BigDecimal>> tableLoader;
    private final ExecutorService lookupPlatformExecutor;
    private final AtomicReference<CompletableFuture<Map<String, BigDecimal>>> lookupTableFuture =
            new AtomicReference<>();

    @Autowired
    public SettlementService(PaymentRepository paymentRepository) {
        this(paymentRepository, SettlementService::defaultFetchTable);
    }

    public SettlementService(
            PaymentRepository paymentRepository,
            Supplier<Map<String, BigDecimal>> tableLoader
    ) {
        this.paymentRepository = paymentRepository;
        this.tableLoader = Objects.requireNonNull(tableLoader, "tableLoader must not be null");
        this.lookupPlatformExecutor = Executors.newSingleThreadExecutor(
                Thread.ofPlatform().name("settlement-lookup-loader").daemon(true).factory()
        );
    }

    @PostConstruct
    public void init() {
        getLookupTable();
    }

    @PreDestroy
    public void shutdown() {
        lookupPlatformExecutor.shutdownNow();
    }

    private static Map<String, BigDecimal> defaultFetchTable() {
        return Map.of(
                "USD", new BigDecimal("1.0000"),
                "EUR", new BigDecimal("1.0850"),
                "GBP", new BigDecimal("1.2650")
        );
    }

    public Map<String, BigDecimal> getLookupTable() {
        CompletableFuture<Map<String, BigDecimal>> existing = lookupTableFuture.get();
        if (existing == null) {
            CompletableFuture<Map<String, BigDecimal>> created = new CompletableFuture<>();
            if (lookupTableFuture.compareAndSet(null, created)) {
                CompletableFuture.supplyAsync(tableLoader, lookupPlatformExecutor)
                        .whenComplete((result, ex) -> {
                            if (ex != null) {
                                created.completeExceptionally(ex);
                            } else {
                                created.complete(Map.copyOf(result));
                            }
                        });
                existing = created;
            } else {
                existing = lookupTableFuture.get();
            }
        }
        return existing.join();
    }

    public BigDecimal calculateSettlement(String merchantId) {
        Map<String, BigDecimal> rates = getLookupTable();

        List<PaymentEntity> payments =
                paymentRepository.findByMerchantId(merchantId);

        if (payments.isEmpty()) {
            return null;
        }

        BigDecimal totalGross = BigDecimal.ZERO;

        for (PaymentEntity payment : payments) {
            BigDecimal rate = rates.getOrDefault(payment.getCurrency(), BigDecimal.ONE);
            totalGross = totalGross.add(
                    BigDecimal.valueOf(payment.getAmountMinor()).multiply(rate)
            );
        }

        BigDecimal fee = totalGross.multiply(FEE_RATE);
        BigDecimal net = totalGross.subtract(fee);

        return net.divide(HUNDRED, 2, RoundingMode.HALF_UP);
    }

    public Map<String, List<PaymentEntity>> groupByMerchant(List<PaymentEntity> payments) {
        Map<String, List<PaymentEntity>> grouped = new HashMap<>();
        for (PaymentEntity payment : payments) {
            grouped.computeIfAbsent(payment.getMerchantId(), _ -> new ArrayList<>())
                    .add(payment);
        }
        return grouped;
    }
}
