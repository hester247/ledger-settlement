
// Merchant is a class because its bank account reference can change.
public class Merchant {

    private final String id;
    private final String displayName;
    private String bankAccountRef;

    public Merchant(String id, String displayName, String bankAccountRef) {
        this.id = id;
        this.displayName = displayName;
        this.bankAccountRef = bankAccountRef;
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getBankAccountRef() {
        return bankAccountRef;
    }

    public void updateBankAccountRef(String bankAccountRef) {
        this.bankAccountRef = bankAccountRef;
    }
}