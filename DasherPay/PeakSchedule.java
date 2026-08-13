import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;

public final class PeakSchedule {

    /** A recurring daily window, e.g. 17:00–19:00 at 2x. Must not cross midnight. */
    public static final class Window {
        final LocalTime start, end;
        final BigDecimal multiplier;

        public Window(LocalTime start, LocalTime end, BigDecimal multiplier) {
            if (!start.isBefore(end)) {
                throw new IllegalArgumentException("window must not be empty or cross midnight: " + start + "-" + end);
            }
            this.start = start; this.end = end;
            this.multiplier = Objects.requireNonNull(multiplier);
        }
    }

    @FunctionalInterface
    public interface SegmentVisitor {
        void visit(LocalDateTime segStart, LocalDateTime segEnd, BigDecimal multiplier);
    }

    // Parallel arrays partitioning a day: times[i] is where mults[i] takes effect.
    // times is strictly increasing and always starts at 00:00, so binary search never
    // falls off the front and every instant maps to exactly one segment.
    private final LocalTime[] times;
    private final BigDecimal[] mults;

    public static PeakSchedule none() { return new PeakSchedule(Collections.emptyList()); }

    public PeakSchedule(List<Window> windows) {
        List<Window> ws = new ArrayList<>(windows);
        ws.sort(Comparator.comparing(w -> w.start));
        for (int i = 1; i < ws.size(); i++) {
            if (ws.get(i).start.isBefore(ws.get(i - 1).end)) {
                // Overlapping windows have no defined answer (do multipliers stack or shadow?).
                // Reject at construction rather than silently pick one.
                throw new IllegalArgumentException("overlapping peak windows");
            }
        }
        List<LocalTime> t = new ArrayList<>();
        List<BigDecimal> m = new ArrayList<>();
        LocalTime cursor = LocalTime.MIN;
        for (Window w : ws) {
            if (w.start.isAfter(cursor)) { t.add(cursor); m.add(BigDecimal.ONE); }
            t.add(w.start); m.add(w.multiplier);
            cursor = w.end;
        }
        t.add(cursor); m.add(BigDecimal.ONE);   // tail (and the whole day when ws is empty)

        this.times = t.toArray(new LocalTime[0]);
        this.mults = m.toArray(new BigDecimal[0]);
    }

    /** Rightmost segment whose start is <= t. */
    private int indexAt(LocalTime t) {
        int i = Arrays.binarySearch(times, t);
        return (i >= 0) ? i : -i - 2;
    }

    /**
     * Cuts [start, end) at every peak boundary inside it and visits each piece.
     * This is what stops a 16:30–17:30 delivery from being billed entirely at
     * peak rate or entirely at base rate.
     */
    public void forEachSegment(LocalDateTime start, LocalDateTime end, SegmentVisitor visitor) {
        LocalDateTime cur = start;
        while (cur.isBefore(end)) {
            int i = indexAt(cur.toLocalTime());
            LocalDateTime next = (i + 1 < times.length)
                    ? LocalDateTime.of(cur.toLocalDate(), times[i + 1])
                    : cur.toLocalDate().plusDays(1).atStartOfDay();   // rolls into tomorrow
            LocalDateTime segEnd = next.isBefore(end) ? next : end;
            visitor.visit(cur, segEnd, mults[i]);
            cur = segEnd;   // strictly advances: next > cur and end > cur, so no infinite loop
        }
    }
}