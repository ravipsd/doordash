Same treatment as before — grouping self-evident lines, spelling out the ones that encode a decision.

---

# `Status`

```java
public enum ACCEPTED, ARRIVED, PICKED_UP, FULFILLED, CANCELED;
```
An enum rather than `String` status codes. A typo'd `"FULLFILLED"` in a string comparison silently becomes "no matching branch" — with an enum it doesn't compile, and `switch` gets exhaustiveness checking.

```java
public boolean isTerminal() { return this == FULFILLED || this == CANCELED; }
```
The spec's "CANCELED is treated identically to FULFILLED for closing the window" expressed once, in one place. Every downstream site asks `isTerminal()` instead of repeating the two-way comparison. When the interviewer adds `REJECTED` or `TIMED_OUT`, one line changes rather than four.

```java
int rank() { return isTerminal() ? 3 : ordinal(); }
```
The tie-break for same-timestamp events, and it exists to prevent one specific bug: an order whose ACCEPTED and FULFILLED share a timestamp arriving in the wrong order would hit the state machine as `null --FULFILLED-->`, which is an invalid transition. Ranking openers before closers makes that impossible.

Package-private, not public — it's a sorting detail, not part of the domain vocabulary. Note it leans on `ordinal()`, which couples the rank to declaration order; that's a mild smell, and if the enum grew I'd make it an explicit field.

---

# `OrderState`

```java
TO_STORE, AT_STORE, IN_TRANSIT, DONE
```
Four states, not a boolean "active/inactive," because Part 2 needs to distinguish *why* an order is open. The dasher driving to the restaurant, waiting at the counter, and carrying food are three different economic situations, and the pay model has to tell them apart.

The naming is deliberately about **where the dasher is**, not which event last fired. `AT_STORE` says something about the world; `ARRIVED_RECEIVED` would just restate the event log. States you can reason about physically are the ones that survive spec changes.

---

# `Event`

```java
private final String orderId;
private final String dasherId;
private final LocalDateTime timestamp;
private final Status status;
```
All `final`. An event is a record of something that already happened — mutating one after the fact would let a replay produce a different total, which destroys the "pay is a pure function of the event set" property the whole production story rests on.

```java
this.orderId = Objects.requireNonNull(orderId, "orderId");
```
Fail at construction, with the field name in the message. A null `orderId` reaching the accumulator would become a legitimate map key and silently create a phantom order that never closes. Rejecting at the boundary means the bad event is identified where it entered.

```java
private final LocalDateTime timestamp;
```
**`LocalDateTime`, not `Instant`** — and this is the choice most worth defending. Peak hours are a wall-clock concept: "17:00–19:00 in the dasher's market." Comparing a `LocalTime` against an `Instant` requires a timezone at every comparison, which would push zone-handling into the hot loop.

The cost is that `LocalDateTime` has no timezone, so DST is invisible to it — which is why I flagged it as a production caveat rather than pretending it's free. The right production shape is `Instant` on the wire, converted once at the service boundary to the market's local time, with the engine unchanged.

```java
@Override public String toString() {
    return status + "(" + orderId + " @" + timestamp + ")";
}
```
Present because test failures and log lines otherwise show `Event@6d06d69c`. No `equals`/`hashCode` — deliberate. Dedup uses an explicit `(orderId, status)` key rather than whole-event equality, because two genuinely distinct events could share all four fields and I don't want equality semantics deciding a money question implicitly.

---

# `PayModel`

```java
private final BigDecimal baseRatePerMinute;
```
**`BigDecimal`, never `double`.** `0.30` isn't representable in binary floating point; accumulate it across a few thousand intervals and the total drifts. For money that's not a rounding curiosity, it's a reconciliation failure — the ledger won't balance and someone has to explain the difference.

```java
if (baseRatePerMinute.signum() < 0) throw new IllegalArgumentException("rate must be >= 0");
```
`signum()` rather than `compareTo(BigDecimal.ZERO)` — same result, and it sidesteps the `equals` vs `compareTo` trap that `BigDecimal` is famous for (`new BigDecimal("0.0").equals(BigDecimal.ZERO)` is `false`, because `equals` compares scale). Zero is allowed; negative is a config error that would generate negative pay.

```java
public static PayModel standard() { ... }
public static PayModel withStoreWaitExcluded() { ... }
```
Named factories over a three-argument constructor of `(BigDecimal, boolean, boolean)`. Two adjacent booleans at a call site are unreadable and trivially swappable — `new PayModel(rate, true, false)` tells you nothing. The factory names encode which *part of the problem* each configuration answers, so switching from Part 1 to Part 2 is one identifier.

```java
BigDecimal concurrencyMultiplier(int toStore, int atStore, int inTransit) {
```
The entire pay policy lives in this one method. That's the point: when the interviewer says "actually, concurrency caps at 3×" or "waiting pays half," you change four lines in one place, not the sweep. It takes three `int`s rather than the order map because it must be cheap — it's called once per event.

```java
int active = toStore + atStore + inTransit;
if (active == 0) return BigDecimal.ZERO;
```
The idle case, handled first. Returning `ZERO` rather than having the caller check "are there open orders" keeps the accrual loop free of business logic — it just multiplies by whatever comes back.

```java
if (!multiplyByConcurrency) return BigDecimal.ONE;
```
The flat-rate variant the prompt mentions ("some variants multiply, some don't"). One flag rather than two classes, because the difference genuinely is one line.

```java
int billable = excludeWaitAtStore ? (toStore + inTransit) : active;
```
**Part 2, in one expression.** An order sitting at the store is excluded from the multiplier, so waiting for order A never inflates the rate that order B is earning at — which is exactly what "not billed against the other concurrent orders" asks for.

```java
return BigDecimal.valueOf(Math.max(billable, 1));
```
The `max(_, 1)` floor is a genuine policy decision and the one I'd say out loud. If every open order is waiting at a store, `billable` is 0 — and without the floor, a dasher standing in a restaurant with their only order earns nothing. That's almost certainly not intended, but it *is* an assumption, and it's a one-character change if the interviewer disagrees.

`BigDecimal.valueOf(long)` rather than `new BigDecimal(int)` — consistent with the rest of the money arithmetic and it uses the cached small values.

---

# `PeakSchedule`

## `Window`

```java
if (!start.isBefore(end)) {
    throw new IllegalArgumentException("window must not be empty or cross midnight: " + start + "-" + end);
}
```
One check covering two errors. An empty window (`17:00–17:00`) is a config mistake. A midnight-crossing window (`22:00–02:00`) would break the partition-array representation below, which assumes each day's segments are ordered within the day. Rejecting it is honest — the alternative is silently computing something wrong. If it's needed, the caller splits it into `22:00–24:00` and `00:00–02:00`, which is also clearer about what it means.

## The representation

```java
private final LocalTime[] times;
private final BigDecimal[] mults;
```
This is the core idea of the class: rather than storing windows and asking "which window contains `t`?" for every lookup, **precompute a partition of the day** into contiguous segments, each with its multiplier. `times` is strictly increasing and always starts at `00:00`, so every instant of the day maps to exactly one index, and the lookup becomes a binary search.

Parallel arrays instead of a `List<Segment>`: no per-segment object, and `Arrays.binarySearch` works directly. That's a readability sacrifice I'd normally resist, but here the two arrays are private, built once, and never mutated — the coupling can't leak.

## Construction

```java
List<Window> ws = new ArrayList<>(windows);
ws.sort(Comparator.comparing(w -> w.start));
```
Defensive copy first — sorting the caller's list would be a surprising side effect, and the caller may have passed an immutable list.

```java
for (int i = 1; i < ws.size(); i++) {
    if (ws.get(i).start.isBefore(ws.get(i - 1).end)) {
        throw new IllegalArgumentException("overlapping peak windows");
    }
}
```
Overlap has **no defined answer** — do a 2× and a 3× window stack to 6×, or does one shadow the other? Rather than pick silently, reject. Validating after sorting means one adjacent-pair scan instead of a quadratic all-pairs check.

```java
LocalTime cursor = LocalTime.MIN;
for (Window w : ws) {
    if (w.start.isAfter(cursor)) { t.add(cursor); m.add(BigDecimal.ONE); }
    t.add(w.start); m.add(w.multiplier);
    cursor = w.end;
}
t.add(cursor); m.add(BigDecimal.ONE);
```
The `cursor` walks the day, filling gaps with 1× and windows with their multiplier. Three edge cases fall out without special-casing:

- **No windows:** the loop never runs, the tail appends `(00:00, 1×)`, and the whole day is a single 1× segment.
- **A window starting at midnight:** `w.start.isAfter(cursor)` is false, so no zero-width filler is emitted.
- **Adjacent windows** (`17–19` then `19–21`): the filler is again skipped, and `19:00` gets exactly one entry carrying the second window's multiplier.

All three preserve the invariant the binary search depends on — **strictly increasing `times`** — which is why they're worth walking through rather than assuming.

## Lookup

```java
private int indexAt(LocalTime t) {
    int i = Arrays.binarySearch(times, t);
    return (i >= 0) ? i : -i - 2;
}
```
`Arrays.binarySearch` returns the index when found, and `-(insertionPoint) - 1` when not. So `-i - 2` is `insertionPoint - 1` — the rightmost segment starting at or before `t`.

It can't return `-1`: `times[0]` is always `00:00`, and no `LocalTime` precedes it, so the insertion point is never 0. That's the invariant paying off — no bounds check needed at the call site.

Exact hits return the segment *starting* at `t`, which makes window starts **inclusive**. Combined with the tail entry making ends **exclusive**, back-to-back windows never double-bill the instant they touch.

## Splitting

```java
public void forEachSegment(LocalDateTime start, LocalDateTime end, SegmentVisitor visitor) {
```
A callback rather than returning `List<Segment>`. This runs once per event, and the list form would allocate a list plus a `Segment` object per piece for a loop body that's three lines. The `@FunctionalInterface` means the call site is a lambda and reads like a `for` loop anyway.

```java
LocalDateTime next = (i + 1 < times.length)
        ? LocalDateTime.of(cur.toLocalDate(), times[i + 1])
        : cur.toLocalDate().plusDays(1).atStartOfDay();
```
The daily partition is wall-clock, so it has to be re-anchored to `cur`'s date each iteration. When `cur` is in the final segment of the day, the next boundary is **tomorrow's midnight** — which is what makes a delivery spanning midnight work without any special case.

```java
LocalDateTime segEnd = next.isBefore(end) ? next : end;
visitor.visit(cur, segEnd, mults[i]);
cur = segEnd;
```
Clamp to `end`, emit, advance. This is the answer to "intervals that straddle the boundary must be cut, not double-counted" — a 16:30–17:30 delivery is visited twice, at 1× and 2×, and neither piece is counted in the other's rate.

Worth confirming it terminates: `next > cur` always (either `times[i+1] > cur.toLocalTime()` by the strictly-increasing invariant, or it's tomorrow's midnight), and `end > cur` by the loop condition, so `segEnd > cur` and `cur` strictly advances. A loop that walks a data structure while clamping to a bound is exactly where an off-by-one becomes an infinite loop, so it's worth being able to state why this one can't.

---

# `PayAccumulator` — the engine

## Fields

```java
private final Map<String, OrderState> orders = new HashMap<>();
```
Per-order state, which is what makes cancellation and the store-wait logic testable — the prompt's own note about modeling the lifecycle explicitly. The memory is `O(open orders)`, a handful, not `O(events)`.

```java
private final Set<String> seen = new HashSet<>();   // (orderId, status)
```
Idempotency against at-least-once delivery. Keyed on `(orderId, status)` rather than whole-event equality because that pair is *legitimately* unique — an order can't be ACCEPTED twice — so a repeat is provably a redelivery, not a real event. A string concat with `|` is the cheap version; a small key class avoids the (here impossible) ambiguity if an id contained a pipe.

```java
private int toStore, atStore, inTransit;
```
Denormalized counters alongside the map. Recomputing them by scanning `orders` on every event would be `O(open orders)` per event for a value that changes by exactly ±1. The duplication is a real risk — they can drift from the map — which is precisely why every mutation is funneled through `adjust()` and nothing else touches them.

```java
private BigDecimal weightedSeconds = BigDecimal.ZERO;
```
**The most important field, and the reason it isn't called `total`.** It accumulates `seconds × concurrency × peak` — all exact multiplications, no division anywhere. The rate and the `/60` are applied once, at payout.

The alternative (converting each segment to minutes and multiplying by `$0.30` as you go) requires dividing by 60 per segment, which is non-terminating for most durations — so you'd round hundreds of times and accumulate drift. Deferring the single division means **exactly one rounding operation in the entire system**, which is what lets the ledger reconcile to the cent.

```java
private final List<String> violations = new ArrayList<>();
```
Non-fatal anomalies that operations needs to see: impossible transitions, orders that never closed. This is the concrete answer to the prompt's "alert if active_count goes negative" — the count can't go negative here, and the thing that would have caused it lands in this list instead.

## `apply`

```java
if (!dasherId.equals(e.getDasherId())) {
    throw new IllegalArgumentException("event for dasher " + e.getDasherId() + " on accumulator " + dasherId);
}
```
A cross-dasher event would silently inflate someone's pay with another person's deliveries. Loud failure, because there is no correct way to interpret it — and in a partitioned consumer, this firing means the partitioning is broken, which is worth knowing immediately.

```java
if (lastT != null && e.getTimestamp().isBefore(lastT)) {
    throw new IllegalArgumentException("out-of-order event: " + e + " after " + lastT);
}
```
Deliberate and worth defending, since the prompt explicitly asks about out-of-order input. **A single-pass sweep physically cannot un-accrue** — the interval it would need to revise has already been folded into `weightedSeconds`, unrecoverably.

So rather than silently mis-billing, the accumulator states its precondition and enforces it. Reordering is a *different responsibility* (a watermark buffer upstream), and conflating the two would put a priority queue inside the money loop. `isBefore`, not `!isAfter`, so equal timestamps are fine — same-instant events are normal.

```java
if (!seen.add(e.getOrderId() + "|" + e.getStatus())) {
    return;
}
```
`Set.add` returns `false` if already present, so the check and the insert are one atomic-looking line rather than `contains` followed by `add`. Returning *before* accruing is essential: a duplicate must be a complete no-op, not "skip the transition but still bill the gap."

```java
accrueUntil(e.getTimestamp());   // bill the interval BEFORE the state change applies
transition(e);
lastT = e.getTimestamp();
```
**These three lines in this order are the whole algorithm**, and swapping the first two is the canonical bug in this problem.

The interval `[lastT, now)` was lived under the *old* set of open orders. An ACCEPTED at 10:10 means the dasher had one order from 10:00 to 10:10 and two from 10:10 onward. Transition first and you'd bill 10:00–10:10 at 2×. The comment says "before" because the correct order isn't self-evident from reading it.

`lastT` updates last, after both, so the next interval starts where this one ended.

## `close`

```java
public void close(LocalDateTime watermark) {
    if (watermark != null && lastT != null && watermark.isAfter(lastT)) {
        accrueUntil(watermark);
        lastT = watermark;
    }
```
Answers "the day ended and this order never got a FULFILLED." Pay accrues to the watermark — the dasher was working. `null` watermark means "stop at the last event," so an unclosed order simply stops earning there, which is the conservative choice when there's no defined end of day.

```java
    for (Map.Entry<String, OrderState> en : orders.entrySet()) {
        if (en.getValue() != OrderState.DONE) {
            violations.add("order " + en.getKey() + " never closed; treated as CANCELED at watermark");
            en.setValue(OrderState.DONE);
        }
    }
```
The prompt's "treat missing FULFILLED beyond a watermark as CANCELED." Each one is recorded, because a dasher-day with several of these means an upstream service is dropping terminal events — a data-quality signal that would otherwise vanish into a plausible-looking total.

`entry.setValue()` during iteration is safe (it's not a structural modification), unlike `map.put()` in the same loop, which would throw `ConcurrentModificationException`.

```java
    toStore = atStore = inTransit = 0;
```
Keeps the counters consistent with the map after the bulk close. Without it, a second `close()` or a stray `getTotalPay()` would see stale counts — cheap insurance for the denormalization above.

## `getTotalPay`

```java
return weightedSeconds
        .multiply(model.getBaseRatePerMinute())
        .divide(SIXTY, 2, RoundingMode.HALF_UP);
```
Multiply first (exact), divide last (the only rounding). `divide` with an explicit scale and mode — the two-argument `divide` would throw `ArithmeticException` on a non-terminating result, which `weightedSeconds / 60` frequently is.

`HALF_UP` because that's what people expect a payout to do; `HALF_EVEN` is the accounting default and reduces bias across many roundings. Either is defensible — what matters is that it's **stated explicitly** rather than inherited from a default, since this single line determines every cent the company pays out.

Scale 2 hard-codes cents. For a multi-currency system that becomes a currency lookup.

## `accrueUntil`

```java
if (lastT == null || !t.isAfter(lastT)) return;
```
Two guards in one. `lastT == null` is the first event — there's no preceding interval. `!t.isAfter(lastT)` covers same-timestamp events, where the interval is zero-width; returning early skips a pointless trip through the schedule.

```java
BigDecimal concurrency = model.concurrencyMultiplier(toStore, atStore, inTransit);
if (concurrency.signum() == 0) return;
```
Computed **once** for the whole interval, outside the segment loop — the order mix doesn't change between events, by definition. It also has to be a single local for the lambda below to capture it.

The `signum() == 0` early return is the idle case: the dasher has nothing open, so a two-hour gap between shifts costs one comparison instead of walking every peak boundary in between. `signum()`, again, to dodge `BigDecimal.equals` and its scale sensitivity.

```java
schedule.forEachSegment(lastT, t, (segStart, segEnd, peak) -> {
    BigDecimal seconds = exactSeconds(segStart, segEnd);
    weightedSeconds = weightedSeconds.add(seconds.multiply(concurrency).multiply(peak));
});
```
The one place money is computed. `weightedSeconds` is a *field*, so the lambda can assign to it — locals would have to be effectively final. `concurrency` is a local and is only read, which satisfies that rule.

`seconds × concurrency × peak`, all exact. Peak comes from the segment because it's the only one of the three that varies *within* an interval.

```java
private static BigDecimal exactSeconds(LocalDateTime a, LocalDateTime b) {
    Duration d = Duration.between(a, b);
    return BigDecimal.valueOf(d.getSeconds()).add(BigDecimal.valueOf(d.getNano(), 9));
}
```
`Duration.toMinutes()` truncates to whole minutes and `toSeconds()` truncates sub-second precision — either would quietly discard time the dasher worked. Reconstructing from `getSeconds()` + `getNano()` keeps it exact to the nanosecond.

`BigDecimal.valueOf(nanos, 9)` is the `(unscaledValue, scale)` overload: it builds `nanos × 10⁻⁹` directly, with no division and no floating point in the path. This is the "prorated partial minutes" clarification made concrete — if the interviewer wants whole minutes instead, this method is the only thing that changes.

## `transition`

```java
OrderState from = orders.get(id);
OrderState to = next(from, e.getStatus());
```
`from` is `null` for an unseen order, which `next` treats as a real state ("not yet accepted") rather than a special case.

```java
if (to == null) {
    String msg = "invalid transition " + from + " --" + e.getStatus() + "--> for order " + id;
    if (strict) throw new IllegalStateException(msg);
    violations.add(msg);
    return;
}
```
`null` from `next` means "impossible." The `strict` flag exists because the right response differs by context: **tests want the throw** (a bug should fail loudly), while **a production consumer wants the record** (one malformed event shouldn't kill a shift's pay for every dasher on the partition).

The `return` matters — on a rejected transition, the counters are left untouched. That's what makes the state machine, not a comment, the guarantee that `active_count` never goes negative: there is no code path that decrements without a validated `from` state.

```java
adjust(from, -1);
adjust(to, +1);
orders.put(id, to);
```
Decrement the old bucket, increment the new one, record. Both calls tolerate `null` and `DONE`, so entering and leaving the tracked states need no branching here.

## `next` — the state machine

```java
if (from == null)  return s == Status.ACCEPTED ? OrderState.TO_STORE : null;
```
An order's first event must be ACCEPTED. A FULFILLED for an unknown order is rejected rather than being invented as an order — which is what would otherwise decrement a counter that was never incremented.

```java
if (from == OrderState.DONE) return null;
```
Terminal is terminal. This is what makes a duplicate CANCELED (arriving under a different key than the dedup set caught) harmless rather than a second decrement.

```java
if (s.isTerminal()) return OrderState.DONE;
```
**One line implements "CANCELED is treated identically to FULFILLED."** Placed before the switch, so it applies from any live state — an order can be canceled while driving to the store, while waiting, or mid-transit, and all three are legal. Enumerating those six combinations in the switch would be the same behavior with six chances to omit one.

```java
case TO_STORE:
    return (s == Status.ARRIVED) ? OrderState.AT_STORE
         : (s == Status.PICKED_UP) ? OrderState.IN_TRANSIT : null;
```
`ARRIVED` is **optional**. Part 1 streams contain only ACCEPTED/FULFILLED/CANCELED, and this is what lets the same engine run both parts unmodified — a Part 1 order simply lives its whole life in `TO_STORE`. Since `excludeWaitAtStore` only ever subtracts `AT_STORE` orders, which never exist in Part 1 data, that flag is inert there too. One code path, both parts.

```java
default:
    return null;   // IN_TRANSIT only leaves via a terminal status
```
`IN_TRANSIT` and `DONE` fall here. Anything not explicitly permitted is rejected — a whitelist, not a blacklist. With money, unknown transitions should fail rather than be guessed at.

## `adjust`

```java
private void adjust(OrderState state, int delta) {
    if (state == null) return;
    switch (state) {
        case TO_STORE:   toStore   += delta; break;
        ...
        default: /* DONE contributes nothing */ break;
    }
}
```
The single funnel for every counter mutation in the class. That's what keeps the denormalized counters honest: there's exactly one place they change, and it's driven by validated state transitions. The `default` comment documents that `DONE` deliberately has no counter, so a reader doesn't take it for a missing case.

---

# `DasherPay`

```java
private DasherPay() {}
```
Private constructor on a static-only class — stops `new DasherPay()`, which would compile and mean nothing.

```java
if (!alreadySorted) {
    ordered = new ArrayList<>(events);
```
The prompt notes events usually arrive sorted, so the `O(n log n)` is often skippable — but only after confirming it. The flag makes that a caller decision rather than a hidden assumption, and the copy protects the caller's list from being reordered underneath them.

Note the failure mode is safe: claim `alreadySorted` wrongly and the accumulator's out-of-order check throws. A wrong assumption produces an exception, not a wrong paycheck.

```java
ordered.sort(Comparator.comparing(Event::getTimestamp)
                       .thenComparingInt(e -> e.getStatus().rank()));
```
`List.sort` is a stable merge sort, so events sharing both a timestamp and a rank keep their input order — the last tie-break available, and the right one when the source is an append-only log.

A Java inference detail worth knowing: this compiles because `Event::getTimestamp` is an *exact* method reference, which the compiler can use to infer `Comparator<Event>` for the receiver. Written as `comparing(e -> e.getTimestamp())` — an implicitly-typed lambda — the same chain **fails to compile**, because the receiver has no target type to infer from. It's a common enough trap that it's worth using the method reference by reflex.

```java
PayAccumulator acc = new PayAccumulator(dasherId, model, schedule, false);
for (Event e : ordered) acc.apply(e);
acc.close(watermark);
return acc.getTotalPay();
```
Four lines, and that's the point. **The batch API is a thin shell over the streaming engine**, so "turn this into a service" requires no rewrite — a consumer holds the accumulator across a partition instead of a `for` loop holding it across a list. Building the batch version around the streaming core, rather than retrofitting later, is what makes that follow-up a five-second answer.

`strict = false` for batch: a whole day's pay shouldn't be lost to one malformed event; the anomalies land in `violations`.

```java
public static BigDecimal totalPay(String dasherId, List<Event> events) {
    return totalPay(dasherId, events, PayModel.standard(), PeakSchedule.none(), false, null);
}
```
The two-argument overload keeps the Part 1 tests readable. Six-parameter calls in every test would bury the one thing each test is actually varying.

---

# The tests

Each one pins a specific decision rather than just exercising code:

- **`single`** — the base case. 30 min × $0.30 = $9.00.
- **`concurrent`** — that the multiplier changes *mid-order*, and that the sweep bills three differently-rated intervals rather than one averaged one.
- **`canceled`** — the disputed edge case. Its presence and its value ($1.50, not $0.00) are the assumption written down where a reviewer will see it.
- **`idle gap`** — that the `signum() == 0` early return actually skips unpaid time. Without it this would return $18.00 instead of $6.00, and it's the one bug that a naive "last event minus first event" implementation always has.
- **`peak straddle`** — boundary cutting. $27.00 rather than $18.00 (all peak) or $9.00 (no peak) distinguishes correct splitting from both wrong answers.
- **`wait excluded` / `wait counted`** — the same event list under both models, $12.00 vs $15.00. A single test would prove the code runs; the pair proves the flag *does what it says*.
- **`duplicate`** — idempotency, which is the property the whole retry story upstream depends on.
- **`watermark`** — that an unclosed order still pays up to the cutoff.

The arithmetic is in comments on each case because a money test whose expected value you can't re-derive by hand is a test that gets "fixed" to match the code the first time it fails.

---

# What a reviewer would still flag

Three, in order of how much they'd matter in production:

1. **No logging.** After the last exchange it would be inconsistent not to say it: `violations` is a list nobody reads unless the caller checks it. It should be structured log lines plus a counter (`dasher_pay_violations_total{type}`), so "one upstream started dropping FULFILLED events" is a dashboard change rather than a discovery.
2. **`strict` is a boolean where a handler belongs.** `throw` vs `record` is really "what should the caller do about anomalies," and a `ViolationHandler` callback would let a consumer route to a dead-letter queue without a third boolean appearing later.
3. **`weightedSeconds` grows unbounded in a long-lived accumulator.** Fine for a day; if one were held across a week of a busy dasher, `BigDecimal` precision costs grow slowly. Flushing to a daily rollup at midnight — which the partitioning story wants anyway — resolves it.