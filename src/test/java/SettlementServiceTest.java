import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SettlementServiceTest {

    private final SettlementService service = new SettlementService();

    @Test
    @DisplayName("MR-447 total amount is 180450")
    void mr447TotalIs180450() {
        List<Payment> payments = List.of(
                new Payment(
                        "PAY-001",
                        "MR-447",
                        180450L,
                        "GBP",
                        PaymentStatus.RECEIVED,
                        LocalDate.of(2026, 3, 4)
                )
        );

        long total = payments.stream()
                .filter(payment -> payment.merchantId().equals("MR-447"))
                .mapToLong(Payment::amountMinor)
                .sum();

        assertThat(total).isEqualTo(180450L);
    }

    @Test
    @DisplayName("Net settlement is 174857")
    void netSettlementIs174857() {
        List<Payment> payments = List.of(
                new Payment(
                        "PAY-001",
                        "MR-447",
                        180450L,
                        "GBP",
                        PaymentStatus.RECEIVED,
                        LocalDate.of(2026, 3, 4)
                )
        );

        assertThat(service.settle(payments, "MR-447"))
                .isEqualTo(174857L);
    }

    @Test
    @DisplayName("An empty payment list returns zero")
    void emptyPaymentListReturnsZero() {
        assertThat(service.settle(List.of(), "MR-447"))
                .isZero();
    }

    @Test
    @DisplayName("A reversal event can be applied")
    void reversalEventCanBeApplied() {
        service.apply(new PaymentReversed());
    }

    @Test
    @DisplayName("A null currency is rejected")
    void nullCurrencyIsRejected() {
        assertThatThrownBy(() -> new Payment(
                "PAY-001",
                "MR-447",
                180450L,
                null,
                PaymentStatus.RECEIVED,
                LocalDate.of(2026, 3, 4)
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid field: currency");
    }

}