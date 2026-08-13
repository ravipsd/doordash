import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

public final class PayAccumulator {

    private static final BigDecimal SIXTY = BigDecimal.valueOf(60);

    private final String dasherId;
    private final PayModel model;
    private final PeakSchedule schedule;
    private final boolean strict;

    private final Map<String, OrderState> orders = new HashMap<>();
    private final Set<String> seen = new HashSet<>();   // (orderId, status) — at-least-once delivery
    private int toStore, atStore, inTransit;

    private LocalDateTime lastT;
    /** Exact sum of (seconds x concurrency x peak). No division until payout, so no per-segment rounding. */
    private BigDecimal weightedSeconds = BigDecimal.ZERO;

    private final List<String> violations = new ArrayList<>();

    public PayAccumulator(String dasherId, PayModel model, PeakSchedule schedule, boolean strict) {
        this.dasherId = Objects.requireNonNull(dasherId);
        this.model = Objects.requireNonNull(model);
        this.schedule = Objects.requireNonNull(schedule);
        this.strict = strict;
    }

    public void apply(Event e) {
        if (!dasherId.equals(e.getDasherId())) {
            throw new IllegalArgumentException("event for dasher " + e.getDasherId() + " on accumulator " + dasherId);
        }
        if (lastT != null && e.getTimestamp().isBefore(lastT)) {
            // The sweep is single-pass and cannot un-accrue. Late events belong in the
            // reorder buffer upstream, not here — see the production notes.
            throw new IllegalArgumentException("out-of-order event: " + e + " after " + lastT);
        }
        if (!seen.add(e.getOrderId() + "|" + e.getStatus())) {
            return;   // duplicate delivery — silently idempotent
        }

        accrueUntil(e.getTimestamp());   // bill the interval BEFORE the state change applies
        transition(e);
        lastT = e.getTimestamp();
    }

    /** Accrue up to a watermark and force-close anything still open (missing FULFILLED). */
    public void close(LocalDateTime watermark) {
        if (watermark != null && lastT != null && watermark.isAfter(lastT)) {
            accrueUntil(watermark);
            lastT = watermark;
        }
        for (Map.Entry<String, OrderState> en : orders.entrySet()) {
            if (en.getValue() != OrderState.DONE) {
                violations.add("order " + en.getKey() + " never closed; treated as CANCELED at watermark");
                en.setValue(OrderState.DONE);
            }
        }
        toStore = atStore = inTransit = 0;
    }

    public BigDecimal getTotalPay() {
        // One rounding, at payout, HALF_UP. Rounding each segment instead would drift by
        // fractions of a cent across a busy day — and pay has to reconcile exactly.
        return weightedSeconds
                .multiply(model.getBaseRatePerMinute())
                .divide(SIXTY, 2, RoundingMode.HALF_UP);
    }

    public List<String> getViolations() { return Collections.unmodifiableList(violations); }

    private void accrueUntil(LocalDateTime t) {
        if (lastT == null || !t.isAfter(lastT)) return;
        BigDecimal concurrency = model.concurrencyMultiplier(toStore, atStore, inTransit);
        if (concurrency.signum() == 0) return;   // idle stretch — nothing open

        schedule.forEachSegment(lastT, t, (segStart, segEnd, peak) -> {
            BigDecimal seconds = exactSeconds(segStart, segEnd);
            weightedSeconds = weightedSeconds.add(seconds.multiply(concurrency).multiply(peak));
        });
    }

    private static BigDecimal exactSeconds(LocalDateTime a, LocalDateTime b) {
        Duration d = Duration.between(a, b);
        return BigDecimal.valueOf(d.getSeconds()).add(BigDecimal.valueOf(d.getNano(), 9));
    }

    /**
     * Explicit state machine. Every count change goes through here, which is why
     * active_count can never go negative — an impossible transition is rejected,
     * not silently decremented.
     */
    private void transition(Event e) {
        String id = e.getOrderId();
        OrderState from = orders.get(id);
        OrderState to = next(from, e.getStatus());

        if (to == null) {
            String msg = "invalid transition " + from + " --" + e.getStatus() + "--> for order " + id;
            if (strict) throw new IllegalStateException(msg);
            violations.add(msg);
            return;
        }
        adjust(from, -1);
        adjust(to, +1);
        orders.put(id, to);
    }

    private static OrderState next(OrderState from, Status s) {
        if (from == null)  return s == Status.ACCEPTED ? OrderState.TO_STORE : null;
        if (from == OrderState.DONE) return null;
        if (s.isTerminal()) return OrderState.DONE;          // CANCELED closes exactly like FULFILLED
        switch (from) {
            case TO_STORE:   // ARRIVED is optional — Part 1 streams jump straight to PICKED_UP
                return (s == Status.ARRIVED) ? OrderState.AT_STORE
                     : (s == Status.PICKED_UP) ? OrderState.IN_TRANSIT : null;
            case AT_STORE:
                return (s == Status.PICKED_UP) ? OrderState.IN_TRANSIT : null;
            default:
                return null;   // IN_TRANSIT only leaves via a terminal status
        }
    }

    private void adjust(OrderState state, int delta) {
        if (state == null) return;
        switch (state) {
            case TO_STORE:   toStore   += delta; break;
            case AT_STORE:   atStore   += delta; break;
            case IN_TRANSIT: inTransit += delta; break;
            default: /* DONE contributes nothing */ break;
        }
    }
}