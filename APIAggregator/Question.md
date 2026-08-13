Build an API aggregator with concurrency and retries
Build an Aggregation Service with Parallel Calls, Timeouts, Retries, and Observability
Context
You are designing a backend service that exposes a single HTTP endpoint. When called, the endpoint must call three external HTTP APIs in parallel, aggregate their responses, and return a combined JSON result. The service must be robust to timeouts, failures, and include proper retries, observability, and clear code organization.

Assume a typed language with futures/promises support (e.g., Java with CompletableFuture). You may choose reasonable defaults and make minimal assumptions if needed.

Requirements
Endpoint
Expose one endpoint (e.g., GET /aggregate) that returns a combined JSON response from three upstream services: A, B, and C.
Concurrency
Call the three upstream HTTP APIs in parallel using futures/promises.
Timeouts
Per-call timeout for each upstream request.
Overall request timeout (deadline) for the whole aggregation request.
Policy
Configurable policy to determine behavior:
WAIT_ALL: wait for all upstreams, return partial data with defaults if some fail.
FAIL_FAST: fail the overall request as soon as any upstream fails or times out.
Retries
Implement retries with capped exponential backoff and jitter via a reusable RetryTemplate that accepts a Callable.
Partial Failure Handling
When some upstreams fail, return partial data along with default values and error details.
Observability
Structured logging with correlation IDs.
Metrics (latency, success/fail counts, timeouts, retries).
Deliverables
Interface definitions for clients, retry template, and service layer.
Concurrency flow description.
Sample error-handling logic and example responses.
Constraints & Assumptions
Preserve the scope, facts, inputs, and requested outputs from the prompt above.
If the prompt leaves a detail unspecified, state a reasonable assumption before relying on it.
Keep the answer interview-ready: concise enough to present, but concrete enough to implement or evaluate.
Clarifying Questions to AskGuidance
Clarify users, core use cases, read/write patterns, scale, latency, availability, and data retention.
State explicit assumptions before making sizing or architecture decisions.
Prioritize the functional path first, then address reliability, security, observability, and rollout.
What a Strong Answer CoversGuidance
A scoped requirements summary with concrete non-goals and success metrics.
API, data model, architecture, consistency, capacity, and operations.
Reasoned trade-offs among simple and scalable designs, including bottlenecks and failure modes.
A validation, monitoring, migration, and launch plan appropriate for the risk level.
Follow-up QuestionsGuidance
What breaks first at 10x traffic or data volume?
How would you degrade gracefully during dependency failures?
What metrics and alerts would prove the design is healthy after launch?