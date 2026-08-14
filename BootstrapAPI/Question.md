Here it is rendered:

---

# Design a Resilient Bootstrap API

> When a client app loads, it needs to fetch everything required to render the first screen in a **single call**. That data lives behind three separate internal services, so you will build an aggregator (a "bootstrap" endpoint) that fans out to them and composes one unified response.

---

## Downstream services

You are given three internal services (internal APIs):

| Service | Endpoint | Returns |
| --- | --- | --- |
| **User Service** | `GET /user-to-consumer?user_id=...` | `{ consumer_id, user_profile... }` |
| **Payments Service** | `GET /payment-info?consumer_id=...` | `{ payment_methods... }` |
| **Address Service** | `GET /address-info?consumer_id=...` | `{ addresses... }` |

> **Note the dependency chain.** Payments and Address are keyed on `consumer_id`, which **only the User Service can produce** from a `user_id`.

```
                        ┌─► Payments Service (consumer_id)
user_id ─► User Service ─┤
             (forced)   └─► Address Service  (consumer_id)
```

---

## What to build

Design and implement a Bootstrap API.

**Endpoint**

```
GET /bootstrap?user_id=...
```

**Behavior**

Take the input `user_id`, fetch the corresponding data from the downstream services, and return a single response that aggregates:

- user / profile information
- payment information
- address information

### Core requirement

> The endpoint must be **as resilient to failures as possible**. Downstream services may be slow, timing out, erroring, or intermittently / partially unavailable, and the bootstrap response should **degrade gracefully rather than fail outright**.

---

## Constraints & assumptions

Anchor your design with the following working assumptions — confirm or adjust them with the interviewer:

- The endpoint is on the client's **first-paint critical path**, so it is latency-sensitive. Assume a target such as **p99 ≤ ~600 ms** end-to-end.
- The operation is a read-only `GET` (**inherently idempotent**).
- Typical microservice constraints apply: bounded thread/connection pools, shared infrastructure, **no distributed transactions**.
- Treat exact SLO numbers, retry counts, and TTLs as **tunable** — state the figures you choose rather than leaving them implicit.

State any further assumptions you make.

---

## Frame before you build

### Part 1 — The API contract

Define the response shape and, crucially, **what happens on partial failures** — when some downstream data is available and some is not. Specify the HTTP-level and body-level semantics a client can program against.

Cover:

- What the contract must express
- HTTP status vs. body status

### Part 2 — Orchestration: ordering & concurrency

Describe how you sequence and parallelize the three calls, and how you bound total latency.

Cover:

- Where the ordering is forced
- Bounding latency

### Part 3 — Reliability strategies

Cover timeouts, retries, circuit breakers, fallbacks, and caching — **and how they compose**.

Cover:

- Start with the cheapest control
- Retry discipline
- Stop cascades and define the fallback ladder

### Part 4 — Observability & operational considerations

Describe what you measure, alert on, and can tune at runtime.

Cover:

- What to instrument

---

## Clarifying questions to ask

A strong candidate scopes the problem before designing. Reasonable questions include:

1. **Dependency roles** — How does each downstream affect what the screen can render? Can the response still be useful if one or two sections are missing, or does the client treat all three as mandatory? Which call, if any, blocks every other piece of the response?
2. **Auth** — Who calls this and how is it authenticated? Is `user_id` trusted from the query string, or must it be derived from the authenticated principal?
3. **Latency** — What is the latency SLO for first paint, and what is the per-call budget for each downstream?
4. **Partial responses** — Are they acceptable to the client, or must all three sections be present atomically?
5. **Staleness tolerance per section** — Can addresses / profile / payment methods be served from cache, and for how long?
6. **Scale** — What is the read volume / fan-out scale, and are there per-user rate limits to respect?
7. **Payments correctness** — Are there correctness constraints specifically on payments (e.g. must we never display a removed or expired payment method)?

---

## What a strong answer covers

Signals an interviewer is looking for. These are **dimensions to evaluate**, not the answers themselves:

- **Dependency reasoning** — How the candidate reasons about each dependency's role in the response, and whether the design's structure follows from that reasoning rather than treating all three calls symmetrically.
- **Contract under partial failure** — Whether a client can *programmatically* tell apart the distinct outcomes a section can have, and how the HTTP-level semantics are chosen and justified.
- **Orchestration quality** — Handling of the forced ordering imposed by the dependency chain, the concurrency model, and how total latency is bounded under a time budget.
- **Reliability-stack coherence** — Timeouts, retries, circuit breakers, bulkheads, and fallbacks — and whether the candidate explains *why* each one protects the caller and *how* they compose.
- **Cache governance** — The policy for what may and may not enter the cache, how TTLs relate to data volatility, and the correctness risks the candidate anticipates.
- **Failure-mode reasoning** — Distinguishing transient from deterministic failures, distinguishing a genuine empty result from an error, and surfacing the security / correctness edge cases.
- **Observability and operational levers** — What is measured *given that failures may not appear as `5xx`*, and which knobs are tunable at runtime.
- **Explicit tradeoffs** articulated for each lever.

---

## Follow-up questions

1. **100x read volume** — How does your design change? What breaks first, and where do you add caching or capacity?
2. **Thundering herd** — When a circuit breaker closes after an outage, what prevents a thundering herd from re-overwhelming the recovered dependency?
3. **Authorization** — The `GET /bootstrap?user_id=...` signature lets a caller name *any* `user_id`. What is the authorization risk, and how do you close it?
4. **Stale payments** — For payment methods, is serving a cached (possibly stale) list ever *worse* than serving nothing? How do you decide?
5. **Budget propagation** — How do you propagate the remaining time budget to downstream services so they can self-cancel work they can no longer deliver in time?

---

Changes beyond formatting, so you can undo them if you'd rather stay verbatim: the downstream services became a table; the bare sub-headings under each Part (which had no body text in the original) became "Cover:" bullet lists; the clarifying questions and follow-ups got bolded lead-ins so they're scannable; the stray "state any further assumptions you make" line moved from the very bottom up into Constraints where it belongs. I also added the small ASCII dependency diagram — that's derived straight from the "note the dependency chain" paragraph, but it is new content rather than reformatting.