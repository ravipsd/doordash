Code Craft: Dasher Pay
The canonical DoorDash Code Craft prompt. Given a chronological event stream for a dasher's day (orders ACCEPTED / FULFILLED / CANCELED with timestamps), compute the dasher's total pay. Pay accrues per minute that the dasher has at least one ongoing delivery; multiple concurrent orders typically multiply the per-minute rate. Production-style follow-ups dominate the second half of the round.

SWE
EM
simulation
interval
state-machine
json
medium
Frequency
Very high
Last asked
2026-08-03
Stage
phone-screen · onsite-coding
Requirements
Input: a chronological list of events for a single dasher. Each event is roughly {order_id, dasher_id, timestamp, status} where status is one of ACCEPTED, FULFILLED, CANCELED. Exact input shape (JSON object, list of tuples, timestamp as int vs ISO-8601 string) is deliberately left for the candidate to clarify with the interviewer — this is part of the signal. Use a clean dataclass / struct.
Output: the dasher's total pay for the day. Two flavors:
Basic: pay = number of minutes the dasher has ≥1 ongoing delivery × base_rate (often $0.30 / min). Some variants multiply by the count of ongoing deliveries (so 3 active orders for 1 minute = 3 × base_rate).
Peak-hour variant: a peak window (e.g. 17:00–19:00) doubles the per-minute rate. Intervals that straddle the boundary must be cut at the boundary, not double-counted.
Edge cases: a single order can be ACCEPTED then CANCELED (no FULFILLED, no pay); CANCELED is treated identically to FULFILLED for the purpose of closing the order's active window.
The follow-up the interviewer almost always asks: how do you turn this from a batch script into a service called by an upstream system? This is where the production discussion below comes from.
Notes
Standard approach: maintain active_count (number of ongoing deliveries) as you sweep events in chronological order. Between consecutive events at times t_prev and t_now, add (t_now − t_prev) × rate × multiplier(active_count) to the total. Apply the event after the addition (an ACCEPTED increments active_count, a FULFILLED / CANCELED decrements it).
Time complexity O(n log n) for the sort plus O(n) for the sweep; events typically arrive already sorted in the input, so the sort can sometimes be skipped after clarifying.
For the peak-hour follow-up, the cleanest implementation is to split each [t_prev, t_now] interval at every boundary timestamp inside that interval and apply the per-segment multiplier. Pre-sort the boundary list and use bisect to find the relevant segment.
Common subtle bug: applying the multiplier change at the same instant as a status event without ordering them — break ties by processing event status changes after the time-based accounting for that timestamp.
Implementing with a state-machine per order (PENDING → IN_PROGRESS → DONE) makes the cancellation logic and the per-order pay reconciliation easier to test.
A multi-part progression shows up in some loops: Part 1 is the basic per-minute accrual; Part 2 adds ARRIVED and PICKED_UP states so that time the dasher spends waiting at the restaurant is not billed against the other concurrent orders; Part 3 layers on peak-hour double pay. Model each order's lifecycle explicitly so the wait-at-store window is attributed to the right order.
Timestamps as strings ("2025-12-03 13:15:00") come up in some variants; have a datetime.strptime / dateutil.parser.isoparse snippet ready or be willing to Google it in the editor — the interviewer typically allows this.
Java solutions run long because of Map<String, Order> boilerplate; Python is faster to finish bug-free.
Aggressive clarification can legitimately shrink the problem: one phone screen collapsed Part 1 to a simple counter-based solution the interviewer accepted, and the saved time turned into a system-design-flavored conversation. Ask your scoping questions before writing code.
Production follow-ups the interviewer expects you to discuss
Upstream API failure — exponential backoff with jitter, circuit breaker on the upstream, deadline budget per request, fallback to stale cached data; surface the partial-result vs hard-fail decision.
Latency SLA when upstream is slow — hedged requests, per-request timeout less than the SLA, async + webhook delivery so the dasher pay endpoint returns immediately.
Payment safety — idempotency key derived from (dasher_id, day), transactional outbox, reconciliation job for orphaned payment intents, audit log with monotonic event_id.
Bad / out-of-order input — accept events arriving out of order and re-derive the timeline; treat missing FULFILLED beyond a watermark as CANCELED for accounting; alert if active_count goes negative.
Memory blow-up on full-day data — stream events instead of buffering; aggregate per-minute totals to a rolling sum and discard processed events.
Reading all dashers' records — partition by dasher_id, batch by date, use an aggregation table updated by a daily ETL.