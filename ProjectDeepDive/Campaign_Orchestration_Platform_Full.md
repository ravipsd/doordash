# Campaign Orchestration Platform
**End-to-End Architecture - Components, Mechanics & Integration Points**
*Amazon Confidential - Do Not Distribute*

## Executive Summary
This document is the design and architecture reference for migrating Amazon High-Volume Hiring (HVH) outbound customer-messaging journeys — welcome, nurture, and recurring job-alert flows delivered by email and SMS — from Amazon Pinpoint onto the Amazon Connect outbound-campaign platform. It is written as a clean-sheet design: it states the business problem, the solution alternatives weighed, the technical trade-offs that shaped the chosen approach, how success is measured, and then documents the resulting platform end-to-end — every component, how it works, and how the pieces integrate.

The headline: HVH runs a very large, long-lived, multi-region journey estate — on the order of 9.19M active in-journey profiles in `us-east-1` alone, with individual journeys that keep a subscriber engaged for up to a year. Moving that workload onto Amazon Connect consolidates high-volume outbound messaging onto a single strategic platform and allows Pinpoint to be retired for this use case. Because Connect's flow-orchestration model has different scaling characteristics than Pinpoint's campaign model, the migration is validated empirically — an escalating, region-by-region load-test campaign measured against an explicit Pinpoint baseline and five end-to-end performance targets, with full-scale cutover gated on the evidence.

**At a glance:**
* **Customer:** Internal HVH business (email + SMS hiring journeys); ultimate audience is job seekers across 8 countries.
* **Platform:** Amazon Connect outbound campaigns — LFOS (flow orchestration/ingestion) + LFAES (wait/journey execution), with the action-relay/delegation/execution tiers carrying sends out.
* **Core mechanic:** "freeze/thaw" — at each outside-world step the flow checkpoints its state to DynamoDB and releases the thread, resuming on an async result, not a held thread.
* **Decision:** Connect chosen over staying on Pinpoint, a bespoke orchestrator, or a third-party SaaS — on scale, long-lived state, expressiveness, isolation, reuse, and strategic consolidation.
* **Bar for success:** meet/beat the 2025 Pinpoint baseline (~5M emails in ~2h; ~410K SMS in ~4h) with zero message loss and no noisy-neighbor degradation, region by region.

---

## How to Read This Document
The document moves from why to what to how. Sections 1-4 make the business and design case. Sections 5-11 are the technical reference for the platform going forward.

| Section | What it covers | Who it is for |
| :--- | :--- | :--- |
| **1. Problem Statement** | The migration: who it serves, why it matters, and intended outcome | All readers |
| **2. Solutions Explored** | Alternatives, evaluation framework, decision matrix, contentions | Leadership, architects |
| **3. Technical Considerations** | Design inputs and the key engineering trade-offs | Architects, engineers |
| **4. Measuring Success** | Value, metrics (baseline->target->result), data-driven iteration | Leadership, PM, engineers |
| **5. Platform Overview** | How the platform works end to end, at a glance | All technical readers |
| **6. Architecture Diagram** <br> **7. Components** | The end-to-end flow, visualized. Deep dive per service: what, how, integration points, rationale | All readers <br> Engineers, on-call |
| **8. Integration Points** | Every cross-system transport, and the rationale | Engineers, architects |
| **9. State Machine** | Per-contact lifecycle for diagnosis | On-call engineers |

---

## 1. Problem Statement - Migrating HVH Outbound Journeys from Amazon Pinpoint to Amazon Connect
Amazon High-Volume Hiring (HVH) sends automated email and SMS communications — welcome sequences, monthly nurture touches, and recurring job alerts — to people who have signed up for job alerts. These journeys run today on Amazon Pinpoint, driven off marketing segments that span millions of customer profiles per region. This initiative moves that production journey estate onto the Amazon Connect outbound-campaign platform (LFOS flow orchestration for ingestion, LFAES for wait/journey execution), consolidating high-volume outbound messaging onto Connect so Pinpoint can be retired for this use case. The business question is whether Connect can carry HVH's full production journeys — at scale, on time, across regions — as the go-forward platform.

**Who this serves:** The immediate customer is the internal HVH business, whose marketing and lifecycle teams own these hiring journeys (key stakeholders Michael Dong and David Hall). The platform owners who receive the migrated workload are the Amazon Connect Flows / LFOS - LFAES teams (Ravi Prasad, Sivakumar Nadimaran, Joe Ezaki), with migration coordination and review from Caitlin Kaphagmi, Neal Liu, and Prabhat Suman. The ultimate beneficiaries are HVH's end audiences — job seekers across the US, Canada, Mexico, UK, India, Germany, Japan, and (planned) Brazil who rely on timely welcome, nurture, and job-alert messages.

**Why it is critical:** This is a migration of live, high-volume production traffic, not a lab exercise. The in-journey estate is roughly 9.19M profiles in `us-east-1` (dominated by US SMS + email job alerts at ~7.1M), 3.24M in `eu-central-1` (India alone ~2.66M), and ~78K in `ap-northeast-1`, keeping strict delivery windows tweaked for up to a year. At this scale, timeliness is a binding constraint: HVH's 2025 Pinpoint baseline that Connect must beat is on the order of 5M emails delivered within a 2-hour window (700/msg/s) and 410K SMS within 4 hours. Strategically, a successful migration validates Amazon Connect as the standard outbound platform and unlocks Pinpoint decommissioning; without it, HVH stays on Pinpoint and the consolidation — and the planned new-region and Q1 2027 Germany onboarding — cannot proceed on Connect.

### 1.1 The journey estate being migrated
HVH's production journeys reduce to three archetypes, each a distinct load profile:
1. **Welcome flow (two-touch onboarding):** Onboards a new job-alert subscriber with a short, gated two-message sequence separated by ~2-day waits.
2. **Nurture flow (monthly re-engagement):** Re-engages existing subscribers with a staged entry (chained 7-day waits) followed by monthly touches (~30- and ~34-day waits).
3. **Looping job-alerts flow (highest volume):** The dominant archetype: periodically fetches jobs matching a subscriber's preferences via a Lambda, decides whether to send, waits ~1 hour for the profile write-back to land, sends the alert, waits ~X days, re-checks membership, and cycles through a template set before looping back.

### 1.2 Scale by region and journey
The migration is sized against live Pinpoint populations. The table summarizes the in-journey estate and the largest journeys per region (approximate, from current populations and volume analysis).

| Region | Countries | In-journey profiles (approx.) | Largest journeys / notes |
| :--- | :--- | :--- | :--- |
| **us-east-1** | US, CA, MX, BR | ~9.19M | US Job Alerts SMS ~3.6M (-> 4M target) + Email ~1.75M (-> 3M) dominates total volume. |
| **eu-central-1** | UK, IN, DE | ~3.24M | India alone ~2.66M; Germany onboards Q1 2027. |
| **ap-northeast-1** | JP | ~78K | Smallest region; smaller volume, onboarding now. |

### 1.3 Scope, phasing, and timeline
The migration is deliberately phased rather than big-bang. Smaller regions are validated first to build confidence and a repeatable method: the largest workload (US) is sequenced on a later track, and new-region onboarding is gated on accumulated readiness evidence.

---

## 2. Solutions Explored
Approaching HVH's outbound estate as a clean-sheet design, the central question was how to run millions of long-lived, multi-step customer journeys reliably and on schedule across regions. 

### 2.1 Evaluation framework
Every candidate was judged against the same seven weighted criteria:
1. **Scale & throughput:** Sustained sends/sec and millions of concurrent in-journey profiles.
2. **Long-lived state:** Ability to hold a profile paused for hours-to-weeks, then resume exactly.
3. **Timing accuracy:** Send to land on the targeted day/window.
4. **Multi-tenant isolation:** One tenant's bursts don't degrade others on a shared fleet.
5. **Journey expressiveness:** Branching, segment gating, external lookups, looping natively.
6. **Operability & reuse:** Existing service, tooling, on-call, and org ownership.
7. **Multi-region/compliance:** Per-region accounts, quotas, data residency, channel rules.

### 2.2 Alternatives considered
* **Option A — Stay on Amazon Pinpoint (do nothing / scale in place):** Strongest on operability but leaves HVH on a divergent stack and does not benefit from Connect's roadmap. Retained only as the baseline to beat.
* **Option B — Build a bespoke journey orchestrator (Step Functions/DynamoDB):** Maximal control but carries extreme cost and risk of re-creating a mature platform from scratch.
* **Option C — Amazon Connect outbound campaign platform (LFOS + LFAES):** Natively supports the exact primitives HVH uses (segment re-checks, waits, Lambda lookups, looping) with checkpoint/resume for long-lived state, per-cell/per-region deployment, and mature metrics/alarms. Strongest on scale, long-lived state, expressiveness, isolation, and reuse.
* **Option D — Third-party / SaaS marketing-automation platform:** Feature-rich but weak on data residency, multi-region/compliance control, and deep integration with internal HVH systems. Prohibitive cost/lock-in at this volume.

**Decision:** Option C (Amazon Connect) was selected as the strategic consolidation target.

---

## 3. Technical Considerations
The solution was shaped by a set of concrete technical inputs — the shape of HVH's workload, the guarantees the business needs, and the physics of a shared, multi-region fleet.

### 3.1 Technical inputs into the design
Six characteristics of the workload drove the architecture:
*   **Massive fan-out, bursty on wait-exit:** Millions of profiles leave long waits in synchronized cohorts.
*   **Long-lived, resumable state:** A journey may pause for >34 days; thread-holding is impossible so state must be externalized.
*   **Per-touch segment re-checks & external lookups:** Every send is gated on live membership and job matching.

### 3.2 Key trade-offs
*   **Availability vs. Latency:** I prioritized availability over latency by holding state in a durable row and making remote actions asynchronous. This deliberate trade-off exchanged seconds-to-minutes of latency for guaranteed state preservation across month-long user journeys.
*   **Throughput vs. Cost:** Rather than massively over-provisioning compute resources for traffic spikes, I decoupled the system using SQS, SNS, and Kinesis to absorb wait-exit bursts. This allowed downstream consumers to drain at a consistent, sustainable rate.
*   **Isolation vs. Utilization:** I implemented a shared multi-tenant fleet governed by strictly enforced bounds. By relying on per-cell sharding and precise quotas, I ensured that bursty noisy neighbors could not starve other tenants of resources.
*   **Core Execution Mechanic:** To handle the physical constraints of the fleet during extended waits, I utilized a "freeze/thaw" mechanic. Instead of holding an active thread open for months, the flow engine checkpoints its state to DynamoDB and releases the thread, resuming execution only when an asynchronous result triggers it.

### 3.4 Failure model and resilience
The design assumes failure is normal at scale and builds recovery into the flow model rather than bolting it on. Because every outside-world step checkpoints before delegating, a crashed host loses no work. 

---

## 4. Measuring Success
*   **The Baseline:** The incumbent Pinpoint system peaked at delivering approximately 5M emails within a 2-hour window and 410K SMS messages within a 4-hour window.
*   **The Goal:** My primary target was to meet or beat this 2025 Pinpoint baseline with absolute zero message loss and zero noisy-neighbor degradation.
*   **The Outcome:** The migration was highly successful, empirically validating Connect's flow-orchestration model against our target volumes and fully achieving the zero message loss requirement during the cutover.
*   **Data-Driven Iteration:** We utilized live production traffic to tune the design and gate the regional cutovers. Every load test evaluated our core targets, and any missed metric fed directly back into design and capacity adjustments before we permitted scaling to the next region.

---

## 5. Key Learnings
*   **What Surprised Me:** I underestimated how dominant synchronized wait-exits would become as a primary load driver. The infrastructure was heavily impacted by large cohorts of profiles exiting wait states at the exact same moment.
*   **What I Would Change:** Next time, I would treat wait-exit burst modeling as a mandatory, required deliverable during the initial design phase rather than analyzing the burstiness reactively.
*   **How My Approach Evolved:** I learned that baseline data must be derived directly from the quantified incumbent system prior to any new design work. Furthermore, I recognized that cross-system read-after-write operations require explicit SLAs, which reinforced the need to enumerate every cross-system data dependency up front.

---
*Generated directly from document OCR transcriptions.*
