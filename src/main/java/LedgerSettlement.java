import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;
import java.util.Vector;

public class LedgerSettlement {

    public static class Payment {
        private String id;
        private String merchantId;
        private long amountMinor;
        private String currency;
        private String status;
        // Tell 3: SimpleDateFormat / java.util.Date instead of java.time.LocalDate
        private Date date;

        public Payment(String id, String merchantId, long amountMinor, String currency, String status, String dateStr) {
            this.id = id;
            this.merchantId = merchantId;
            this.amountMinor = amountMinor;
            this.currency = currency;
            this.status = status;
            try {
                // Tell 3: SimpleDateFormat usage
                this.date = new SimpleDateFormat("yyyy-MM-dd").parse(dateStr);
            } catch (ParseException e) {
                this.date = null;
            }
        }

        public String getId() { return id; }
        public String getMerchantId() { return merchantId; }
        public long getAmountMinor() { return amountMinor; }
        public String getCurrency() { return currency; }
        public String getStatus() { return status; }
        public Date getDate() { return date; }
    }

    // Functional interface for filtering
    public interface PaymentFilter {
        boolean matches(Payment p);
    }

    // Tell 1: Raw type `List` with no type argument
    public static long calculateMerchantSettlement(List payments, String merchantId) {
        // Tell 4: Manual null check where Optional belongs
        if (payments == null || merchantId == null) {
            return 0L;
        }

        // Tell 2: Vector used in place of ArrayList
        Vector merchantPayments = new Vector();

        // Tell 5: Anonymous inner class where a lambda works
        PaymentFilter filter = new PaymentFilter() {
            @Override
            public boolean matches(Payment p) {
                // Tell 4: Another manual null check where Optional could be used
                if (p.getMerchantId() == null) {
                    return false;
                }
                return p.getMerchantId().equals(merchantId);
            }
        };

        // Populating the Vector using raw type iteration
        for (int i = 0; i < payments.size(); i++) {
            Payment p = (Payment) payments.get(i); // Tell 1: Raw type requires manual cast
            if (filter.matches(p)) {
                merchantPayments.add(p);
            }
        }

        long totalNet = 0L;
        Enumeration elements = merchantPayments.elements();
        while (elements.hasMoreElements()) {
            Payment p = (Payment) elements.nextElement();
            long fee = p.getAmountMinor() * 31 / 1000;
            long net = p.getAmountMinor() - fee;
            totalNet += net;
        }

        return totalNet;
    }
}