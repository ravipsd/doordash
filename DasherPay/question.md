Here it is rendered:

---

# Code Craft: Dasher Pay

> The canonical DoorDash Code Craft prompt. Given a chronological event stream for a dasher's day, compute the dasher's total pay. Production-style follow-ups dominate the second half of the round.

---

## Problem

Given a chronological event stream for a dasher's day (orders `ACCEPTED` / `FULFILLED` / `CANCELED` with timestamps), compute the dasher's total pay.

Pay accrues **per minute that the dasher has at least one ongoing delivery**. Multiple concurrent orders typically multiply the per-minute rate.

---

## Requirements

### Input

A chronological list of events for a **single dasher**. Each event is roughly:

```
{ order_id, dasher_id, timestamp, status }
```

where `status` is one of `ACCEPTED`, `FULFILLED`, `CANCELED`.

> **Deliberately unspecified:** the exact input shape — JSON object, list of tuples, timestamp as `int` vs ISO-8601 string. Clarifying this with the interviewer **is part of the signal**. Use a clean dataclass / struct.

### Output

The dasher's total pay for the day. Two flavors:

| Flavor | Rule |
| --- | --- |
| **Basic** | `pay = minutes with ≥1 ongoing delivery × base_rate` (often `$0.30/min`) |
| **Concurrency variant** | Multiply by the count of ongoing deliveries — 3 active orders for 1 minute = `3 × base_rate` |
| **Peak-hour variant** | A peak window (e.g. `17:00–19:00`) doubles the per-minute rate |

> **Peak-hour caveat:** intervals that straddle the boundary must be **cut at the boundary**, not double-counted.

### Edge cases

- A single order can be `ACCEPTED` then `CANCELED` — no `FULFILLED`, no pay.
- `CANCELED` is treated **identically to** `FULFILLED` for the purpose of closing the order's active window.

---

## Approach

### The standard sweep

Maintain `active_count` (number of ongoing deliveries) while sweeping events in chronological order.

1. Between consecutive events at `t_prev` and `t_now`, add:
   ```
   (t_now − t_prev) × rate × multiplier(active_count)
   ```
2. **Apply the event after the addition** — an `ACCEPTED` increments `active_count`; a `FULFILLED` / `CANCELED` decrements it.

### Complexity

- `O(n log n)` for the sort, plus `O(n)` for the sweep.
- Events typically arrive **already sorted**, so the sort can sometimes be skipped — after clarifying.

### Peak-hour implementation

The cleanest approach: split each `[t_prev, t_now]` interval at every boundary timestamp inside that interval and apply the per-segment multiplier. Pre-sort the boundary list and use `bisect` to find the relevant segment.

### Common subtle bug

> Applying the multiplier change at the same instant as a status event **without ordering them**. Break ties by processing event status changes **after** the time-based accounting for that timestamp.

### Testability

Implementing with a **state machine per order** (`PENDING → IN_PROGRESS → DONE`) makes the cancellation logic and the per-order pay reconciliation easier to test.

---

## Multi-part progression

Some loops run this as a three-part build:

| Part | Adds |
| --- | --- |
| **Part 1** | Basic per-minute accrual |
| **Part 2** | `ARRIVED` and `PICKED_UP` states, so time spent waiting at the restaurant is **not billed against the other concurrent orders** |
| **Part 3** | Peak-hour double pay |

Model each order's lifecycle explicitly so the wait-at-store window is attributed to the right order.

---

## Practical tips

- **String timestamps** (`"2025-12-03 13:15:00"`) come up in some variants. Have a `datetime.strptime` / `dateutil.parser.isoparse` snippet ready, or be willing to Google it in the editor — the interviewer typically allows this.
- **Java runs long** because of `Map<String, Order>` boilerplate. Python is faster to finish bug-free.
- **Aggressive clarification legitimately shrinks the problem.** One phone screen collapsed Part 1 to a simple counter-based solution the interviewer accepted, and the saved time turned into a system-design-flavored conversation. **Ask your scoping questions before writing code.**

---

## Production follow-ups

> The follow-up the interviewer almost always asks: **how do you turn this from a batch script into a service called by an upstream system?**

| Scenario | Expected discussion |
| --- | --- |
| **Upstream API failure** | Exponential backoff with jitter; circuit breaker on the upstream; deadline budget per request; fallback to stale cached data. Surface the partial-result vs hard-fail decision. |
| **Latency SLA when upstream is slow** | Hedged requests; per-request timeout less than the SLA; async + webhook delivery so the dasher pay endpoint returns immediately. |
| **Payment safety** | Idempotency key derived from `(dasher_id, day)`; transactional outbox; reconciliation job for orphaned payment intents; audit log with monotonic `event_id`. |
| **Bad / out-of-order input** | Accept events arriving out of order and re-derive the timeline; treat missing `FULFILLED` beyond a watermark as `CANCELED` for accounting; alert if `active_count` goes negative. |
| **Memory blow-up on full-day data** | Stream events instead of buffering; aggregate per-minute totals to a rolling sum and discard processed events. |
| **Reading all dashers' records** | Partition by `dasher_id`; batch by date; use an aggregation table updated by a daily ETL. |

---

Changes I made beyond formatting: split the flat "Notes" list into **Approach**, **Multi-part progression**, and **Practical tips**, since it was mixing algorithm, structure, and tactics; pulled the two output flavors and the six production follow-ups into tables; and moved the "how do you turn this into a service" line from Requirements to the top of the follow-ups section, where it actually introduces them. All content preserved.