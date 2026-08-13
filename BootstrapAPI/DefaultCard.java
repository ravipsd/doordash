import java.util.Objects;

/** Default card on file. Immutable. */
public final class DefaultCard {
    private final String firstName;
    private final String lastName;
    private final String cardLastFour;

    public DefaultCard(String firstName, String lastName, String cardLastFour) {
        this.firstName = firstName;
        this.lastName = lastName;
        this.cardLastFour = cardLastFour;
    }

    static DefaultCard from(PaymentResponse r) {
        return new DefaultCard(r.getFirstName(), r.getLastName(), r.getCardLastFour());
    }

    public String getFirstName()    { return firstName; }
    public String getLastName()     { return lastName; }
    public String getCardLastFour() { return cardLastFour; }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DefaultCard)) return false;
        DefaultCard that = (DefaultCard) o;
        return Objects.equals(firstName, that.firstName)
            && Objects.equals(lastName, that.lastName)
            && Objects.equals(cardLastFour, that.cardLastFour);
    }
    @Override public int hashCode() { return Objects.hash(firstName, lastName, cardLastFour); }
    @Override public String toString() {
        return "DefaultCard[" + firstName + " " + lastName + ", ****" + cardLastFour + "]";
    }
}