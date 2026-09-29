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

    @Value("${ledger.fee-rate}")
    private double feeRate;

    private static class LookupTable {
        static final Map<String, String> TABLE = loadTable();

        private static Map<String, String> loadTable() {
            synchronized (LookupTable.class) {
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
        }
    }

    public SettlementService(PaymentRepository paymentRepository) {
        this.paymentRepository = paymentRepository;
    }

    @Transactional
    public PaymentEntity recordPayment(PaymentEntity payment) {
        return paymentRepository.save(payment);
    }

    public long calculateSettlement(String merchantId) {

        LookupTable.TABLE.get(merchantId);

        List<PaymentEntity> payments =
                paymentRepository.findByMerchantId(merchantId);

        if (payments.isEmpty()) {
            throw new IllegalArgumentException(
                    "Unknown merchant: " + merchantId
            );
        }

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
