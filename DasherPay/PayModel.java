import java.math.BigDecimal;
import java.util.Objects;

public final class PayModel {

    private final BigDecimal baseRatePerMinute;
    private final boolean multiplyByConcurrency;
    private final boolean excludeWaitAtStore;

    public PayModel(BigDecimal baseRatePerMinute, boolean multiplyByConcurrency, boolean excludeWaitAtStore) {
        this.baseRatePerMinute = Objects.requireNonNull(baseRatePerMinute);
        if (baseRatePerMinute.signum() < 0) throw new IllegalArgumentException("rate must be >= 0");
        this.multiplyByConcurrency = multiplyByConcurrency;
        this.excludeWaitAtStore = excludeWaitAtStore;
    }

    /** Part 1: flat $0.30/min whenever >=1 order is open, multiplied by concurrency. */
    public static PayModel standard() {
        return new PayModel(new BigDecimal("0.30"), true, false);
    }

    /** Part 2: an order waiting at the store doesn't inflate the concurrency multiplier. */
    public static PayModel withStoreWaitExcluded() {
        return new PayModel(new BigDecimal("0.30"), true, true);
    }

    public BigDecimal getBaseRatePerMinute() { return baseRatePerMinute; }

    /**
     * Weight applied to a time slice given the current mix of open orders.
     * <p>When {@code excludeWaitAtStore} is set, an AT_STORE order keeps the dasher on the
     * clock but does not add to the multiplier — so waiting for order A never bills against
     * concurrent order B. If *every* open order is waiting, the floor of 1 keeps the dasher paid.
     */
    BigDecimal concurrencyMultiplier(int toStore, int atStore, int inTransit) {
        int active = toStore + atStore + inTransit;
        if (active == 0) return BigDecimal.ZERO;
        if (!multiplyByConcurrency) return BigDecimal.ONE;
        int billable = excludeWaitAtStore ? (toStore + inTransit) : active;
        return BigDecimal.valueOf(Math.max(billable, 1));
    }
}