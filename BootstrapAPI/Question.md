Design a Resilient Bootstrap API
When a client app loads, it needs to fetch everything required to render the first screen in a single call. That data lives behind three separate internal services, so you will build an aggregator (a "bootstrap" endpoint) that fans out to them and composes one unified response.

Downstream services
You are given three internal services (internal APIs):

User Service — GET /user-to-consumer?user_id=... → returns { consumer_id, user_profile... }
Payments Service — GET /payment-info?consumer_id=... → returns { payment_methods... }
Address Service — GET /address-info?consumer_id=... → returns { addresses... }
Note the dependency chain: the Payments and Address services are keyed on consumer_id, which only the User Service can produce from a user_id.

What to build
Design and implement a Bootstrap API:

Endpoint: GET /bootstrap?user_id=...
Behavior: take the input user_id , fetch the corresponding data from the downstream services, and return a single response that aggregates:
user / profile information
payment information
address information
Core requirement
The endpoint must be as resilient to failures as possible. Downstream services may be slow, timing out, erroring, or intermittently / partially unavailable, and the bootstrap response should degrade gracefully rather than fail outright.

Constraints & Assumptions
Anchor your design with the following working assumptions (confirm or adjust them with the interviewer):

The endpoint is on the client's first-paint critical path , so it is latency-sensitive — assume a target such as p99 ≤ ~600 ms end-to-end.
The operation is a read-only GET (inherently idempotent).
Typical microservice constraints apply: bounded thread/connection pools, shared infrastructure, no distributed transactions.
Treat exact SLO numbers, retry counts, and TTLs as tunable — state the figures you choose rather than leaving them implicit.

Frame before you build
Part 1 — The API Contract
Define the response shape and, crucially, what happens on partial failures — when some downstream data is available and some is not. Specify the HTTP-level and body-level semantics a client can program against.


What the contract must express

HTTP status vs. body status
Part 2 — Orchestration: Ordering & Concurrency
Describe how you sequence and parallelize the three calls, and how you bound total latency.


Where the ordering is forced

Bounding latency
Part 3 — Reliability Strategies
Cover timeouts, retries, circuit breakers, fallbacks, and caching, and how they compose.


Start with the cheapest control

Retry discipline

Stop cascades and define the fallback ladder
Part 4 — Observability & Operational Considerations
Describe what you measure, alert on, and can tune at runtime.


What to instrument
Clarifying Questions to AskGuidance
A strong candidate scopes the problem before designing. Reasonable questions include:

How does each downstream affect what the screen can render? Can the response still be useful if one or two sections are missing, or does the client treat all three as mandatory? Which call, if any, blocks every other piece of the response?
Who calls this and how is it authenticated? Is user_id trusted from the query string, or must it be derived from the authenticated principal?
What is the latency SLO for first paint, and what is the per-call budget for each downstream?
Are partial responses acceptable to the client , or must all three sections be present atomically?
What is the staleness tolerance per section — can addresses / profile / payment methods be served from cache, and for how long?
What is the read volume / fan-out scale , and are there per-user rate limits to respect?
Are there correctness constraints on payments specifically (e.g. must we never display a removed or expired payment method)?
What a Strong Answer CoversGuidance
Signals an interviewer is looking for (these are dimensions to evaluate, not the answers themselves):

How the candidate reasons about each dependency's role in the response, and whether the design's structure follows from that reasoning rather than treating all three calls symmetrically.
How the API contract behaves under partial failure — whether a client can programmatically tell apart the distinct outcomes a section can have, and how the HTTP-level semantics are chosen and justified.
The quality of the orchestration — handling of the forced ordering imposed by the dependency chain, the concurrency model, and how total latency is bounded under a time budget.
The coherence of the reliability stack — timeouts, retries, circuit breakers, bulkheads, and fallbacks — and whether the candidate explains why each one protects the caller and how they compose.
How caching is governed — the policy for what may and may not enter the cache, how TTLs relate to data volatility, and the correctness risks the candidate anticipates.
Failure-mode reasoning — distinguishing transient from deterministic failures, distinguishing a genuine empty result from an error, and surfacing the security / correctness edge cases.
Observability and operational levers — what is measured given that failures may not appear as 5xx , and which knobs are tunable at runtime.
Explicit tradeoffs articulated for each lever.
Follow-up QuestionsGuidance
How does your design change at 100x read volume ? What breaks first, and where do you add caching or capacity?
When a circuit breaker closes after an outage , what prevents a thundering herd from re-overwhelming the recovered dependency?
The GET /bootstrap?user_id=... signature lets a caller name any user_id . What is the authorization risk , and how do you close it?
For payment methods , is serving a cached (possibly stale) list ever worse than serving nothing? How do you decide?
How do you propagate the remaining time budget to downstream services so they can self-cancel work they can no longer deliver in time?
Assume typical microservice constraints throughout, and state any further assumptions you make.