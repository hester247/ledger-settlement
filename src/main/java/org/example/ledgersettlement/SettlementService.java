package org.example.ledgersettlement;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class SettlementService {

    private final PaymentRepository paymentRepository;
    private final Map<String, String> lookupTable;

    @Value("${ledger.fee-rate}")
    private double feeRate;

    public SettlementService(PaymentRepository paymentRepository) {
        this.paymentRepository = paymentRepository;
        this.lookupTable = loadTable();
    }

    private Map<String, String> loadTable() {
        try {
            Thread.sleep(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }

        Map<String, String> table = new HashMap<>();
        table.put("MR-4471", "GBP");
        return table;
    }

    @Transactional
    public PaymentEntity recordPayment(PaymentEntity payment) {
        return paymentRepository.save(payment);
    }

    public long calculateSettlement(String merchantId) {

        lookupTable.get(merchantId);

        List<PaymentEntity> payments =
                paymentRepository.findByMerchantId(merchantId);

        if (payments.isEmpty()) {
            throw new IllegalArgumentException(
                    "Unknown merchant: " + merchantId
            );
        }

        // Convert the configured fee rate to an integer rate.
        long feeRateInteger =
                BigDecimal.valueOf(feeRate)
                        .multiply(BigDecimal.valueOf(10000))
                        .longValue();

        long totalOwed = 0;

        for (PaymentEntity payment : payments) {

            long fee =
                    payment.getAmountMinor() * feeRateInteger / 10000;

            long netAmount =
                    payment.getAmountMinor() - fee;

            totalOwed += netAmount;
        }

        return totalOwed;
    }
}