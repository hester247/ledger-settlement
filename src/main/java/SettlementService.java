import java.util.Map;
import java.util.stream.Collectors;
import java.util.List;

public class SettlementService {

    public long settle(List<Payment> payments, String merchantId) {
        return payments.stream()
                .filter(payment -> payment.merchantId().equals(merchantId))
                .mapToLong(payment -> {
                    long fee = payment.amountMinor() * 31 / 1000;
                    return payment.amountMinor() - fee;
                })
                .sum();
    }

    public Map<String, List<Payment>> groupByMerchant(List<Payment> payments) {
        return payments.stream()
                .collect(Collectors.groupingBy(Payment::merchantId));
    }

    public void apply(SettlementEvent event) {
        switch (event) {
            case PaymentReceived received -> {
                // Handle a received payment.
            }
            case PaymentReversed reversed -> {
                // Handle a reversed payment.
            }
            case FeeApplied fee -> {
                // Handle an applied fee.
            }
        }
    }
}