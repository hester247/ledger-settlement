import java.time.LocalDate;

public record Payment(
        String id,
        String merchantId,
        long amountMinor,
        String currency,
        PaymentStatus status,
        LocalDate date
) {

    public Payment {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Invalid field: id");
        }

        if (merchantId == null) {
            throw new IllegalArgumentException("Invalid field: merchantId");
        }

        if (amountMinor <= 0) {
            throw new IllegalArgumentException("Invalid field: amountMinor");
        }

        if (currency == null || currency.length() != 3) {
            throw new IllegalArgumentException("Invalid field: currency");
        }

        if (status == null) {
            throw new IllegalArgumentException("Invalid field: status");
        }

        if (date == null) {
            throw new IllegalArgumentException("Invalid field: date");
        }
    }
}