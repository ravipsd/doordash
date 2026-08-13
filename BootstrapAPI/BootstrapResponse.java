import java.util.Objects;

/**
 * Aggregated bootstrap payload. Immutable — this is what makes the fan-out lock-free.
 *
 * customerId  — always present (no customerId => no BootstrapResponse at all)
 * defaultCard — null when PaymentService could not be resolved
 * address     — "" when AddressService could not be resolved
 */
public final class BootstrapResponse {
    private final String customerId;
    private final DefaultCard defaultCard;   // nullable by contract
    private final String address;            // never null; "" when unknown

    public BootstrapResponse(String customerId, DefaultCard defaultCard, String address) {
        this.customerId = Objects.requireNonNull(customerId, "customerId");
        this.defaultCard = defaultCard;
        this.address = (address == null) ? "" : address;
    }

    public String getCustomerId()       { return customerId; }
    public DefaultCard getDefaultCard() { return defaultCard; }
    public String getAddress()          { return address; }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BootstrapResponse)) return false;
        BootstrapResponse that = (BootstrapResponse) o;
        return Objects.equals(customerId, that.customerId)
            && Objects.equals(defaultCard, that.defaultCard)
            && Objects.equals(address, that.address);
    }
    @Override public int hashCode() { return Objects.hash(customerId, defaultCard, address); }
    @Override public String toString() {
        return "BootstrapResponse[customerId=" + customerId
             + ", defaultCard=" + defaultCard
             + ", address='" + address + "']";
    }
}