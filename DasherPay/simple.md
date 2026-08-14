SOLID here isn't decoration — the two things that actually change in this problem are **the rate schedule** (peak windows, holidays, market-specific rates) and **the concurrency rule** (multiply / flat / exclude store wait). Everything below exists to make those two vary without editing the sweep.

| Principle | Concrete change |
|---|---|
| **S** | Sweep, concurrency rule, and time-varying rate were one class. Now three. |
| **O** | New peak rule = new `RateSchedule` class. No edit to `PayCalculator`. |
| **L** | `RateSchedule` has a written contract; violating it corrupts pay or hangs the sweep. |
| **I** | Two narrow interfaces instead of one fat `PayPolicy` — one sees only statuses, one only time. |
| **D** | `PayCalculator` takes both as constructor arguments; no static constants, no `new` inside. |

---

## `Status.java`

```java
public enum Status {
    ACCEPTED, ARRIVED, PICKED_UP, FULFILLED, CANCELED;

    /** CANCELED closes an order's window exactly like FULFILLED. Stated once, here. */
    public boolean isTerminal() { return this == FULFILLED || this == CANCELED; }
}
```

## `Event.java`

```java
import java.time.LocalDateTime;

public final class Event {

    private final String orderId;
    private final LocalDateTime time;
    private final Status status;

    public Event(String orderId, LocalDateTime time, Status status) {
        if (orderId == null || time == null || status == null) {
            throw new IllegalArgumentException("event fields must not be null");
        }
        this.orderId = orderId;
        this.time = time;
        this.status = status;
    }

    public String getOrderId()     { return orderId; }
    public LocalDateTime getTime() { return time; }
    public Status getStatus()      { return status; }

    @Override public String toString() { return status + "(" + orderId + " @" + time + ")"; }
}
```

---

## `ConcurrencyPolicy.java` — ISP: sees only statuses

```java
import java.util.Collection;

/**
 * How many orders the dasher is billed for at a given instant.
 *
 * @param openStatuses statuses of orders that are currently open (terminal ones already removed)
 * @return 0 when idle; otherwise the per-minute rate multiplier
 */
public interface ConcurrencyPolicy {
    int multiplier(Collection<Status> openStatuses);
}
```

It takes a `Collection<Status>` and not the order map or the event list — that's the segregation. It cannot accidentally grow a dependency on order ids or timestamps.

## `MultiplyByConcurrency.java`

```java
import java.util.Collection;

/** 3 open orders for a minute = 3 x base rate. */
public final class MultiplyByConcurrency implements ConcurrencyPolicy {
    @Override public int multiplier(Collection<Status> openStatuses) {
        return openStatuses.size();
    }
}
```

## `FlatWhenActive.java`

```java
import java.util.Collection;

/** The non-multiplying variant: base rate whenever at least one order is open. */
public final class FlatWhenActive implements ConcurrencyPolicy {
    @Override public int multiplier(Collection<Status> openStatuses) {
        return openStatuses.isEmpty() ? 0 : 1;
    }
}
```

## `ExcludeStoreWait.java` — OCP as a decorator

```java
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Part 2: an order waiting at the restaurant keeps the dasher on the clock but does not
 * inflate the multiplier, so waiting for order A is never billed against concurrent order B.
 * Wraps any policy rather than replacing it — composition, not a fourth if-branch.
 */
public final class ExcludeStoreWait implements ConcurrencyPolicy {

    private final ConcurrencyPolicy delegate;

    public ExcludeStoreWait(ConcurrencyPolicy delegate) {
        if (delegate == null) throw new IllegalArgumentException("delegate required");
        this.delegate = delegate;
    }

    @Override public int multiplier(Collection<Status> openStatuses) {
        if (openStatuses.isEmpty()) return 0;
        List<Status> billable = new ArrayList<Status>(openStatuses.size());
        for (Status s : openStatuses) {
            if (s != Status.ARRIVED) billable.add(s);
        }
        // Floor of 1: if every open order is waiting at a store, the dasher is still paid.
        return Math.max(delegate.multiplier(billable), 1);
    }
}
```

---

## `RateSchedule.java` — LSP with teeth

```java
import java.time.LocalDateTime;

/**
 * A time-varying rate multiplier (peak hours, holidays, market boosts).
 *
 * <p><b>Substitutability contract</b> — every implementation must satisfy all three, or the
 * sweep silently mis-bills or fails to terminate:
 * <ol>
 *   <li>{@code multiplierAt(t) >= 1} for all t.</li>
 *   <li>{@code multiplierAt} is CONSTANT across {@code [t, nextChangeAfter(t, limit))}.
 *       Break this and pay is wrong with no error.</li>
 *   <li>{@code nextChangeAfter(t, limit)} is strictly after {@code t} and at most
 *       {@code limit}. Break this and the sweep loops forever.</li>
 * </ol>
 */
public interface RateSchedule {

    int multiplierAt(LocalDateTime t);

    /** The next instant the multiplier could change, clamped to {@code limit}. */
    LocalDateTime nextChangeAfter(LocalDateTime t, LocalDateTime limit);
}
```

Writing the contract down is the point of LSP here. A subclass that returns a boundary *before* `t` doesn't throw — it hangs the calculator. That's exactly the kind of failure an unstated contract produces.

## `FlatRateSchedule.java`

```java
import java.time.LocalDateTime;

/** No peak pricing. Always 1x, never changes. */
public final class FlatRateSchedule implements RateSchedule {
    @Override public int multiplierAt(LocalDateTime t) { return 1; }

    @Override public LocalDateTime nextChangeAfter(LocalDateTime t, LocalDateTime limit) {
        return limit;   // constant to the horizon; satisfies clauses 2 and 3
    }
}
```

## `DailyWindowSchedule.java`

```java
import java.time.LocalDateTime;
import java.time.LocalTime;

/** One recurring daily window, e.g. 17:00-19:00 at 2x. Start inclusive, end exclusive. */
public final class DailyWindowSchedule implements RateSchedule {

    private final LocalTime start;
    private final LocalTime end;
    private final int multiplier;

    public DailyWindowSchedule(LocalTime start, LocalTime end, int multiplier) {
        if (start == null || end == null || !start.isBefore(end)) {
            throw new IllegalArgumentException("window must be non-empty and must not cross midnight");
        }
        if (multiplier < 1) throw new IllegalArgumentException("multiplier must be >= 1");
        this.start = start;
        this.end = end;
        this.multiplier = multiplier;
    }

    @Override public int multiplierAt(LocalDateTime t) {
        LocalTime lt = t.toLocalTime();
        return (!lt.isBefore(start) && lt.isBefore(end)) ? multiplier : 1;
    }

    @Override public LocalDateTime nextChangeAfter(LocalDateTime t, LocalDateTime limit) {
        LocalDateTime[] candidates = {
            LocalDateTime.of(t.toLocalDate(), start),
            LocalDateTime.of(t.toLocalDate(), end),
            t.toLocalDate().plusDays(1).atStartOfDay()   // re-anchors the window on the next day
        };
        LocalDateTime best = limit;
        for (LocalDateTime c : candidates) {
            if (c.isAfter(t) && c.isBefore(best)) best = c;
        }
        return best;
    }
}
```

## `CompositeRateSchedule.java`

```java
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Combines schedules: the highest applicable multiplier wins, and the rate can change at
 * any member's boundary. Max rather than product, so two overlapping 2x windows give 2x,
 * not a surprising 4x.
 */
public final class CompositeRateSchedule implements RateSchedule {

    private final List<RateSchedule> schedules;

    public CompositeRateSchedule(List<RateSchedule> schedules) {
        if (schedules == null || schedules.isEmpty()) {
            throw new IllegalArgumentException("at least one schedule required");
        }
        this.schedules = new ArrayList<RateSchedule>(schedules);   // defensive copy
    }

    @Override public int multiplierAt(LocalDateTime t) {
        int max = 1;
        for (RateSchedule s : schedules) max = Math.max(max, s.multiplierAt(t));
        return max;
    }

    @Override public LocalDateTime nextChangeAfter(LocalDateTime t, LocalDateTime limit) {
        LocalDateTime best = limit;
        for (RateSchedule s : schedules) {
            LocalDateTime c = s.nextChangeAfter(t, limit);
            if (c.isAfter(t) && c.isBefore(best)) best = c;
        }
        return best;
    }
}
```

`CompositeRateSchedule` **is** a `RateSchedule` and honors the same contract, so it can be passed anywhere one is expected — including into another composite. That's LSP paying off rather than being asserted.

---

## `PayCalculator.java` — DIP: depends on abstractions only

```java
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Sweeps events chronologically and accumulates pay. Knows nothing about peak hours or
 * store waits — those arrive as collaborators.
 */
public final class PayCalculator {

    private final double ratePerMinute;
    private final ConcurrencyPolicy concurrency;
    private final RateSchedule schedule;

    public PayCalculator(double ratePerMinute, ConcurrencyPolicy concurrency, RateSchedule schedule) {
        if (ratePerMinute < 0) throw new IllegalArgumentException("rate must be >= 0");
        if (concurrency == null || schedule == null) {
            throw new IllegalArgumentException("concurrency and schedule are required");
        }
        this.ratePerMinute = ratePerMinute;
        this.concurrency = concurrency;
        this.schedule = schedule;
    }

    public double totalPay(List<Event> events) {
        List<Event> sorted = new ArrayList<Event>(events);
        sorted.sort(Comparator.comparing(Event::getTime)
                              .thenComparingInt(e -> e.getStatus().ordinal()));   // open before close

        Map<String, Status> open = new HashMap<String, Status>();   // only non-terminal orders
        long weightedSeconds = 0;                                   // integer: no float drift
        LocalDateTime previous = null;

        for (Event e : sorted) {
            // Bill the elapsed interval BEFORE applying the event — it was lived under the old state.
            if (previous != null) {
                weightedSeconds += weigh(previous, e.getTime(), concurrency.multiplier(open.values()));
            }
            if (e.getStatus().isTerminal()) {
                open.remove(e.getOrderId());        // keeps open.values() == the open set
            } else {
                open.put(e.getOrderId(), e.getStatus());
            }
            previous = e.getTime();
        }
        return round(weightedSeconds * ratePerMinute / 60);
    }

    /** Splits [from, to) wherever the schedule changes, weighting each piece. */
    private long weigh(LocalDateTime from, LocalDateTime to, int concurrencyMultiplier) {
        if (concurrencyMultiplier == 0) return 0;   // idle
        long total = 0;
        LocalDateTime cursor = from;
        while (cursor.isBefore(to)) {
            LocalDateTime next = schedule.nextChangeAfter(cursor, to);
            long seconds = Duration.between(cursor, next).getSeconds();
            total += seconds * concurrencyMultiplier * schedule.multiplierAt(cursor);
            cursor = next;                          // contract clause 3 guarantees progress
        }
        return total;
    }

    /** Single rounding, at the end. Production with real payouts would use BigDecimal. */
    private static double round(double value) {
        return Math.round(value * 100) / 100.0;
    }
}
```

---

## `DasherPayTest.java`

```java
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class DasherPayTest {

    private static final double RATE = 0.30;

    private static LocalDateTime at(int h, int m) { return LocalDateTime.of(2025, 12, 3, h, m); }
    private static Event ev(String id, int h, int m, Status s) { return new Event(id, at(h, m), s); }

    private static void check(String name, double actual, double expected) {
        boolean ok = Math.abs(actual - expected) < 1e-9;
        System.out.printf("%-16s $%.2f  %s%n", name, actual, ok ? "PASS" : "FAIL exp " + expected);
    }

    public static void main(String[] args) {

        // Wiring is explicit — every variant is a different composition, not a different branch.
        PayCalculator basic = new PayCalculator(
                RATE, new MultiplyByConcurrency(), new FlatRateSchedule());

        PayCalculator withWait = new PayCalculator(
                RATE, new ExcludeStoreWait(new MultiplyByConcurrency()), new FlatRateSchedule());

        PayCalculator withPeak = new PayCalculator(
                RATE, new MultiplyByConcurrency(),
                new DailyWindowSchedule(LocalTime.of(17, 0), LocalTime.of(19, 0), 2));

        check("single", basic.totalPay(Arrays.asList(
            ev("A", 10, 0,  Status.ACCEPTED),
            ev("A", 10, 30, Status.FULFILLED))), 9.00);

        check("concurrent", basic.totalPay(Arrays.asList(
            ev("A", 10, 0,  Status.ACCEPTED),
            ev("B", 10, 10, Status.ACCEPTED),
            ev("B", 10, 20, Status.FULFILLED),
            ev("A", 10, 30, Status.FULFILLED))), 12.00);

        check("canceled", basic.totalPay(Arrays.asList(
            ev("A", 10, 0, Status.ACCEPTED),
            ev("A", 10, 5, Status.CANCELED))), 1.50);

        check("idle gap", basic.totalPay(Arrays.asList(
            ev("A", 10, 0,  Status.ACCEPTED),
            ev("A", 10, 10, Status.FULFILLED),
            ev("B", 11, 0,  Status.ACCEPTED),
            ev("B", 11, 10, Status.FULFILLED))), 6.00);

        // 16:30-17:00 at 1x ($9) + 17:00-17:30 at 2x ($18)
        check("peak straddle", withPeak.totalPay(Arrays.asList(
            ev("A", 16, 30, Status.ACCEPTED),
            ev("A", 17, 30, Status.FULFILLED))), 27.00);

        // Same events, two policies — the difference is the wiring, not the calculator.
        List<Event> waitEvents = Arrays.asList(
            ev("A", 10, 0,  Status.ACCEPTED),
            ev("A", 10, 5,  Status.ARRIVED),
            ev("B", 10, 5,  Status.ACCEPTED),
            ev("A", 10, 15, Status.PICKED_UP),
            ev("B", 10, 25, Status.FULFILLED),
            ev("A", 10, 30, Status.FULFILLED));

        check("wait excluded", withWait.totalPay(waitEvents), 12.00);
        check("wait counted",  basic.totalPay(waitEvents),    15.00);

        check("empty", basic.totalPay(Collections.<Event>emptyList()), 0.00);
    }
}
```

---

## What this bought, concretely

- **Adding a second peak window** is `new CompositeRateSchedule(Arrays.asList(lunch, dinner))`. `PayCalculator` is untouched.
- **Switching to flat pay** is swapping one constructor argument.
- **Testing the sweep in isolation** is now possible — inject a stub `RateSchedule` returning a fixed multiplier, and you're testing the accrual logic without any date arithmetic.
- **The `weigh` loop can no longer hang**, because clause 3 of the contract is what guarantees `cursor` advances — and now that's written down where an implementer will read it.

The organizing test I applied: **a class earns its existence by isolating one reason to change.** If two things in a file change for different reasons and on different schedules, they belong apart; if they always change together, splitting them adds indirection for nothing. Every justification below is an answer to "what varies, and who would have to edit what."

```
DasherPayTest  (composition root — the only place that names concrete classes)
      │ wires
      ▼
PayCalculator ──depends on──► ConcurrencyPolicy      RateSchedule
                                    ▲                      ▲
                     ┌──────────────┼───────────┐    ┌─────┼──────────────┐
            MultiplyByConcurrency  FlatWhenActive │  Flat  Daily      Composite
                     ▲                            │
                ExcludeStoreWait (decorator) ─────┘
```

Every arrow points *toward* an abstraction. Nothing below the composition root knows which implementation it's talking to.

---

# `Status`

**What it does.** Names the five lifecycle events, and answers one question: `isTerminal()`.

**Why it exists as an enum rather than `String` constants.** A typo'd `"FULLFILLED"` in a string comparison silently becomes "no branch matched" — the order never closes, and the dasher is paid until end of day. As an enum it doesn't compile. Enums also give exhaustiveness checking in `switch` and a natural `ordinal()` for the sort tie-break.

**Why `isTerminal()` lives here.** The spec says "CANCELED is treated identically to FULFILLED for the purpose of closing the order's active window." That rule is stated **once**, on the type that owns it. Without the method, `s == FULFILLED || s == CANCELED` gets repeated in `PayCalculator`, in `ExcludeStoreWait`, and in every future policy — and when a sixth status like `REJECTED` arrives, you must find all of them. Here you edit one line.

**What would break if inlined.** Nothing immediately — it's the maintenance cost that bites. This is the cheapest possible piece of encapsulation and it prevents the most common class of drift.

---

# `Event`

**What it does.** An immutable record of one thing that happened: order, time, status.

**Why a class instead of a tuple, a `Map<String,Object>`, or three parallel lists.** Three reasons, in order of weight:

1. **Validation at the boundary.** The constructor rejects nulls. A null `orderId` reaching `PayCalculator` would become a legitimate map key and silently create a phantom order that never closes — a wrong paycheck with no error anywhere. Rejecting at construction names the bad input where it entered.
2. **Immutability.** Fields are `final`, so replaying the same event list twice always yields the same pay. That property is what makes the whole thing safe to retry, recompute, and reconcile — the entire production story in the prompt rests on it.
3. **Type safety.** `ev.getTime()` versus `map.get("timestamp")` cast to `LocalDateTime`.

**Why getters and not public final fields** (which would be less code). Because `Comparator.comparing(Event::getTime)` needs a method reference. Written as `Comparator.comparing(e -> e.time)` — an implicitly-typed lambda — the chained `.thenComparingInt(...)` **fails to compile**, because the receiver has no target type to infer from. The getters are load-bearing, not ceremony.

**Why no `equals`/`hashCode`.** Nothing puts `Event` in a `Set` or uses it as a map key. Generating them would be speculative, and equality semantics on a money-adjacent type are worth defining only when something actually needs them.

---

# `ConcurrencyPolicy` (interface)

**What it does.** Given the statuses of currently-open orders, returns the rate multiplier. `0` means idle.

**Why this is an interface and not an `if` chain.** The prompt itself names three variants: multiply by count, flat-when-active, and exclude-store-wait. Those are three *rules*, and interview follow-ups add more (cap at 3×, half rate while waiting). With an `if` chain inside the sweep, every new rule edits the class that computes money — the single riskiest file in the codebase. With an interface, a new rule is a new file and the sweep is never touched.

**Why the parameter is `Collection<Status>` and not the `Map<String,Status>` or the event list.** This is interface segregation with a concrete payoff: the policy **cannot** develop a dependency on order ids, timestamps, or dasher ids, because it can't see them. That keeps every implementation trivially testable — you pass a list of statuses and assert an int, with no clock and no fixtures.

**Why `0` for idle rather than a separate `isIdle()` method.** It keeps the caller branch-free: `weigh(...)` multiplies by whatever comes back, and zero naturally contributes nothing. A second method would be a second thing every implementation must get right and keep consistent with the first.

---

# `MultiplyByConcurrency`

**What it does.** Returns `openStatuses.size()`.

**Why a whole class for one line.** Because it's the *default policy*, and naming it makes the wiring self-documenting: `new PayCalculator(RATE, new MultiplyByConcurrency(), ...)` states the rule at the composition root where a reader is looking for it. The alternative — a lambda inline at the wiring site — works, but it can't be unit-tested by name, can't be decorated, and can't appear in a config-driven policy lookup later.

Note it doesn't special-case empty: an empty collection has size 0, which is exactly the idle contract. The one-line implementation is correct by construction rather than by a guard.

---

# `FlatWhenActive`

**What it does.** `1` when any order is open, `0` otherwise.

**Why it exists.** The prompt explicitly says "*some variants* multiply by the count." This is the other variant, available by swapping one constructor argument instead of editing the multiplier logic.

**Honest assessment — this is the weakest class in the set.** No test currently exercises it, and an unused implementation is speculative generality until something needs it. I'd keep it only because the prompt names the variant as a live possibility in the interview; if this were production code with a fixed rule, I'd delete it and add it when asked. Worth saying plainly rather than defending: not every SOLID class is equally earned.

---

# `ExcludeStoreWait`

**What it does.** Filters `ARRIVED` orders out before delegating, then floors the result at 1.

**Why a decorator instead of a fourth implementation.** This is the Open/Closed principle doing real work. "Exclude store waits" is **orthogonal** to "multiply or don't." As separate implementations you'd need four classes for two independent choices, and six for three. As a decorator you compose: `new ExcludeStoreWait(new MultiplyByConcurrency())` or `new ExcludeStoreWait(new FlatWhenActive())`. Two axes, two classes plus one wrapper.

**Why the `Math.max(..., 1)` floor.** It encodes a policy decision that is easy to miss: if *every* open order is waiting at a restaurant, `billable` is empty and the delegate returns 0 — the dasher standing in a store with their only order would earn nothing. The floor says "still on the clock." That's an assumption worth stating out loud with an interviewer, and putting it in a named class means there's exactly one place to change it.

**Evidence the composition is coherent:** wrapping `FlatWhenActive` yields 1 whenever active regardless of waits — which is correct, since a flat rate has no concurrency to dilute. The decorator degrades sensibly over both delegates rather than only making sense over one.

**The improvement I'd make.** It hard-codes `Status.ARRIVED`. Adding a second non-billable state (say `WAITING_FOR_CUSTOMER`) means editing this class — a small OCP violation. The open version takes the exclusion set as a constructor argument:

```java
public ExcludeStates(ConcurrencyPolicy delegate, Set<Status> nonBillable)
```

That's strictly better and costs three lines. I left it concrete because one exclusion exists today, but it's the first thing I'd change on the second requirement.

---

# `RateSchedule` (interface)

**What it does.** Two methods: the multiplier at an instant, and the next instant it could change.

**Why two methods and not just `multiplierAt(t)`.** Because the sweep must *split* intervals at rate boundaries — the prompt is explicit that a 16:30–17:30 delivery must be cut at 17:00, not billed wholly at one rate. With only `multiplierAt`, the calculator would have to sample or would have to know where boundaries are, which is exactly the knowledge this class exists to hide. `nextChangeAfter` lets the calculator walk segments without knowing what a "peak hour" is.

**Why the contract is written into the Javadoc.** This is the Liskov principle with actual consequences rather than a slogan. Two of the three clauses fail *silently or catastrophically*:

- Break clause 2 (multiplier constant across the returned segment) → **pay is wrong, nothing throws.** The calculator samples `multiplierAt(cursor)` once per segment and trusts it for the whole span.
- Break clause 3 (`nextChangeAfter` strictly after `t`) → **the `while` loop in `weigh` never terminates.** The cursor stops advancing and the request hangs.

An implementer who hasn't read that contract can write something that compiles, passes a naive test, and hangs in production. Documenting it is the only enforcement available short of a contract test suite — which is what I'd add next.

**Why it takes `LocalDateTime` and knows nothing about orders.** Segregation again, the mirror of `ConcurrencyPolicy`: this one sees only time, that one sees only statuses. They vary for entirely different reasons — peak windows change when the pricing team changes them; concurrency rules change when the pay model changes — so they change on different schedules and belong in different files.

---

# `FlatRateSchedule`

**What it does.** Always 1, never changes.

**Why it exists — this is the Null Object pattern, and it's the strongest justification of any single-line class here.** Without it, `PayCalculator` would need a nullable `schedule` field and a null check in the hot loop, and every future collaborator would need the same. The null check would then have to be repeated in `CompositeRateSchedule`, and forgetting it once is an NPE inside the money path.

`nextChangeAfter` returning `limit` is the interesting line: it says "constant to the horizon," which satisfies clauses 2 and 3 exactly, and it makes `weigh` collapse to a single segment with no special case. The Null Object isn't just a placeholder — it participates in the contract properly.

---

# `DailyWindowSchedule`

**What it does.** One recurring daily window (17:00–19:00 at 2×), start inclusive, end exclusive.

**Why a class rather than two constants and a helper method** (which is what the simple version had). Because the window is *configuration*, and configuration that lives in `static final` fields cannot vary per market, per experiment, or per test. As a class you can construct three of them for three cities and pick at wiring time.

**Why the constructor rejects midnight-crossing windows.** A 22:00–02:00 window would break the day-anchored boundary arithmetic in `nextChangeAfter` and produce silently wrong segments. Rejecting it at construction is honest; the caller splits it into 22:00–24:00 and 00:00–02:00 and passes both to a composite — which is also clearer about what it means.

**Why `nextChangeAfter` includes tomorrow's midnight** as a third candidate. This is the non-obvious line. The two window boundaries are computed from `t`'s own date, so if `t` is 20:00 and the segment runs into the next day, both candidates are already in the past and the method would return `limit` — skipping the *next day's* 17:00 boundary entirely. Tomorrow's midnight re-anchors the computation. Without it, any interval spanning midnight mis-bills the following day's peak.

**Why `multiplierAt` uses `!lt.isBefore(start) && lt.isBefore(end)`.** Start inclusive, end exclusive, so two back-to-back windows never double-bill the instant where they touch. Same reasoning as half-open intervals everywhere.

---

# `CompositeRateSchedule`

**What it does.** Combines several schedules: highest multiplier wins, rate can change at any member's boundary.

**Why it exists.** Lunch peak *and* dinner peak is the obvious next requirement, and without a composite you'd either write a `TwoWindowSchedule` (then a three-window one) or teach `PayCalculator` to hold a list. Both are worse. This is the Composite pattern, and its defining property is the one that matters: **`CompositeRateSchedule` is itself a `RateSchedule`**, so it goes anywhere one is expected, including inside another composite. That's Liskov paying off rather than being asserted.

**Why max and not product.** Two overlapping 2× windows under a product rule give 4× — a real payout bug arising from a config mistake nobody would notice. Max is the intuitive reading of "highest applicable rate wins," it's idempotent under duplicate windows, and it keeps the result bounded by the largest configured multiplier. It's a defined rule either way; the point is that it's *documented* rather than emergent.

**Why the defensive copy** of the incoming list. Otherwise a caller mutating their list afterwards silently changes pay calculations already in flight — and this object is shared across every request.

**How it upholds the contract.** `nextChangeAfter` takes the *minimum* of members' boundaries, which guarantees the composite's multiplier is constant across the segment it returns: no member can change inside it, so the max can't change either. That's clause 2 preserved by construction, and it's why the composite is safe to nest.

---

# `PayCalculator`

**What it does.** Sorts, sweeps, accumulates. That's all.

**Why this is the class that must stay thin.** It's the only place money is computed. Every rule that lives here is a rule you cannot change without risking the arithmetic. By pushing both varying dimensions out to collaborators, the parts that can change (rates, concurrency rules) are isolated from the part that must not (the sweep).

**Why it depends on interfaces injected through the constructor** rather than constructing them or reading statics. Three payoffs: the sweep is testable with a stub schedule and no date arithmetic; the same class serves basic / wait-excluded / peak variants; and there is exactly one place — the composition root — that knows which implementations exist.

**The two lines that carry the algorithm:**

```java
if (previous != null) weightedSeconds += weigh(previous, e.getTime(), concurrency.multiplier(open.values()));
// ...then apply the event
```

Accrue **before** applying. The interval that just ended was lived under the *old* set of orders; swapping these is the canonical bug in this problem, and it's why the comment says "before" rather than describing what the line does.

```java
if (e.getStatus().isTerminal()) open.remove(e.getOrderId()); else open.put(...);
```

Removing terminal orders instead of marking them `DONE` is what makes `open.values()` *be* the open set, with no filtering per event and no state machine. It's the simplification that let the whole design stay small — and it's only safe because `isTerminal()` is defined in one place.

**Why `weightedSeconds` is a `long` and not a running dollar total.** Every multiplication is exact integer arithmetic; the rate and the `/60` are applied once at the end. Accumulating dollars would divide per segment, and `seconds/60` is non-terminating for most durations — hundreds of roundings and accumulating drift. **One floating-point operation in the entire program**, which is also why `round()` can be a one-liner rather than a `BigDecimal` pipeline.

**Why `round` is private and not a `RoundingStrategy` interface.** Nothing varies. If payouts became real, the answer is `BigDecimal` end-to-end, not a strategy — an interface here would be indirection with no second implementation, which is the failure mode SOLID gets blamed for.

---

# `DasherPayTest`

**Why this counts as an architectural class, not just tests.** It's the **composition root** — the single place that names concrete implementations. `PayCalculator` never says `new DailyWindowSchedule`; `ExcludeStoreWait` never says `new MultiplyByConcurrency`. All the wiring is here, at the top, which is what makes dependency inversion actually true rather than aspirational. In a Spring app this file becomes `@Configuration`; the role is identical.

**Why the wait test runs the same events through two calculators:**

```java
check("wait excluded", withWait.totalPay(waitEvents), 12.00);
check("wait counted",  basic.totalPay(waitEvents),    15.00);
```

One test proves the code runs. The pair proves the **policy is what makes the difference** — same input, same calculator class, different wiring, different answer. That's the design claim under test, not just the arithmetic.

---

# Summary: what each class buys

| Class | Isolates | Cost if merged into `PayCalculator` |
|---|---|---|
| `Status` | The FULFILLED/CANCELED equivalence | Rule repeated in 3+ places; drifts on the next status |
| `Event` | Input validation and immutability | Phantom orders from null ids; replay stops being deterministic |
| `ConcurrencyPolicy` | *How many orders bill* | Every pay-rule change edits the money path |
| `MultiplyByConcurrency` | The default rule | Rule invisible at the wiring site |
| `FlatWhenActive` | The non-multiplying variant | **Weakest — currently unused** |
| `ExcludeStoreWait` | Store-wait exclusion, orthogonally | 4 classes for 2 independent choices |
| `RateSchedule` | *What the rate is over time* | Peak hours hard-coded; sweep untestable without a clock |
| `FlatRateSchedule` | Absence of peak pricing | Null checks in the hot loop, repeated per collaborator |
| `DailyWindowSchedule` | One window's configuration | Windows become constants; can't vary by market |
| `CompositeRateSchedule` | Combining rules | Second peak window forces a new class or a list in the calculator |
| `PayCalculator` | The sweep itself | — |
| `DasherPayTest` | All knowledge of concrete types | Every class would `new` its own collaborators |

**Two honest weak points:** `FlatWhenActive` has no test and no current caller, and `ExcludeStoreWait` hard-codes `Status.ARRIVED` where it should take an exclusion set. Both are small; naming them matters more than fixing them, because "which of my abstractions haven't earned their keep yet" is the question that keeps a SOLID design from turning into the thing SOLID gets criticized for.