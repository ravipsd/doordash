import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;

public class DasherPayTest {

    private static LocalDateTime at(int h, int m) { return LocalDateTime.of(2025, 12, 3, h, m); }
    private static Event ev(String order, int h, int m, Status s) { return new Event(order, "d1", at(h, m), s); }

    private static void check(String name, BigDecimal actual, String expected) {
        if (actual.compareTo(new BigDecimal(expected)) != 0) {
            throw new AssertionError(name + ": expected " + expected + " got " + actual);
        }
        System.out.println("PASS " + name + " = $" + actual);
    }

    public static void main(String[] args) {
        // 1. Single order, 10:00-10:30 => 30 min x $0.30 = $9.00
        check("single", DasherPay.totalPay("d1", Arrays.asList(
                ev("A", 10, 0, Status.ACCEPTED),
                ev("A", 10, 30, Status.FULFILLED))), "9.00");

        // 2. Overlap: A 10:00-10:30, B 10:10-10:20
        //    10:00-10:10 x1 = $3.00 | 10:10-10:20 x2 = $6.00 | 10:20-10:30 x1 = $3.00
        check("concurrent", DasherPay.totalPay("d1", Arrays.asList(
                ev("A", 10, 0, Status.ACCEPTED),
                ev("B", 10, 10, Status.ACCEPTED),
                ev("B", 10, 20, Status.FULFILLED),
                ev("A", 10, 30, Status.FULFILLED))), "12.00");

        // 3. ACCEPTED then CANCELED: window still closes, 5 min x $0.30 = $1.50
        check("canceled", DasherPay.totalPay("d1", Arrays.asList(
                ev("A", 10, 0, Status.ACCEPTED),
                ev("A", 10, 5, Status.CANCELED))), "1.50");

        // 4. Idle gap earns nothing: A 10:00-10:10, B 11:00-11:10 => 20 min x $0.30 = $6.00
        check("idle gap", DasherPay.totalPay("d1", Arrays.asList(
                ev("A", 10, 0, Status.ACCEPTED),
                ev("A", 10, 10, Status.FULFILLED),
                ev("B", 11, 0, Status.ACCEPTED),
                ev("B", 11, 10, Status.FULFILLED))), "6.00");

        // 5. Peak straddle: 16:30-17:30, peak 17:00-19:00 at 2x
        //    16:30-17:00 = 30 x 0.30 x1 = $9.00 | 17:00-17:30 = 30 x 0.30 x2 = $18.00 => $27.00
        PeakSchedule peak = new PeakSchedule(Collections.singletonList(
                new PeakSchedule.Window(LocalTime.of(17, 0), LocalTime.of(19, 0), BigDecimal.valueOf(2))));
        check("peak straddle", DasherPay.totalPay("d1", Arrays.asList(
                ev("A", 16, 30, Status.ACCEPTED),
                ev("A", 17, 30, Status.FULFILLED)),
                PayModel.standard(), peak, false, null), "27.00");

        // 6. Store wait. A: accepted 10:00, arrived 10:05, picked up 10:15, done 10:30.
        //                B: accepted 10:05, done 10:25.
        //    10:00-10:05 billable=1 -> 300s | 10:05-10:15 A waiting so billable=1 -> 600s
        //    10:15-10:25 billable=2 -> 1200s | 10:25-10:30 billable=1 -> 300s
        //    2400 weighted-seconds x 0.30 / 60 = $12.00
        List<Event> wait = Arrays.asList(
                ev("A", 10, 0, Status.ACCEPTED),
                ev("A", 10, 5, Status.ARRIVED),
                ev("B", 10, 5, Status.ACCEPTED),
                ev("A", 10, 15, Status.PICKED_UP),
                ev("B", 10, 25, Status.FULFILLED),
                ev("A", 10, 30, Status.FULFILLED));
        check("wait excluded", DasherPay.totalPay("d1", wait,
                PayModel.withStoreWaitExcluded(), PeakSchedule.none(), false, null), "12.00");
        // Same stream, wait counted: 10:05-10:15 becomes x2 -> 3000 weighted-seconds => $15.00
        check("wait counted", DasherPay.totalPay("d1", wait,
                PayModel.standard(), PeakSchedule.none(), false, null), "15.00");

        // 7. Duplicate delivery is idempotent
        check("duplicate", DasherPay.totalPay("d1", Arrays.asList(
                ev("A", 10, 0, Status.ACCEPTED),
                ev("A", 10, 0, Status.ACCEPTED),
                ev("A", 10, 30, Status.FULFILLED))), "9.00");

        // 8. Unclosed order + watermark: pays to 10:30, then force-closes
        check("watermark", DasherPay.totalPay("d1",
                Collections.singletonList(ev("A", 10, 0, Status.ACCEPTED)),
                PayModel.standard(), PeakSchedule.none(), false, at(10, 30)), "9.00");

        System.out.println("all passed");
    }
}