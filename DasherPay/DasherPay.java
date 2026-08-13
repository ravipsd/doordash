import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

public final class DasherPay {

    private DasherPay() {}

    public static BigDecimal totalPay(String dasherId, List<Event> events,
                                      PayModel model, PeakSchedule schedule,
                                      boolean alreadySorted, LocalDateTime watermark) {
        List<Event> ordered = events;
        if (!alreadySorted) {
            ordered = new ArrayList<>(events);
            // Stable sort; ties broken so an order's opening status precedes its terminal one.
            ordered.sort(Comparator.comparing(Event::getTimestamp)
                                   .thenComparingInt(e -> e.getStatus().rank()));
        }
        PayAccumulator acc = new PayAccumulator(dasherId, model, schedule, false);
        for (Event e : ordered) acc.apply(e);
        acc.close(watermark);
        return acc.getTotalPay();
    }

    public static BigDecimal totalPay(String dasherId, List<Event> events) {
        return totalPay(dasherId, events, PayModel.standard(), PeakSchedule.none(), false, null);
    }
}