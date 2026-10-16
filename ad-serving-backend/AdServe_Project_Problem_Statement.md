# AdServe — Intelligent Advertisement Serving API

**Project type:** Backend engineering project  
**Stack:** Java 21, Spring Boot, PostgreSQL, Redis, Kafka  
**Focus:** Object-Oriented Analysis and Design (OOAD), Domain-Driven Design (DDD), Test-Driven Development (TDD)

## Project problem statement

Build a production-style advertisement-serving platform that helps digital publishers—such as blogs, news websites, and mobile applications—show relevant advertisements to their users. Advertisers should be able to create campaigns, define targeting rules and budgets, and measure campaign performance.

Whenever a publisher requests an advertisement, the platform must find eligible campaigns, rank their ads, and return the most suitable result quickly.

The platform must address these challenges:

- **Relevance:** Select ads based on keywords, page context, audience targeting, and campaign configuration.
- **Campaign eligibility:** Never serve paused, expired, exhausted, or budget-depleted campaigns.
- **Budget management:** Prevent concurrent requests from overspending a campaign's budget.
- **Low latency:** Return ads quickly, even under high traffic.
- **Analytics:** Track impressions and clicks asynchronously without slowing down ad serving.
- **Reliability:** Handle duplicate events, cache failures, and message retries without corrupting campaign metrics.

The goal is to solve these problems through clean object-oriented design, clear domain boundaries, and automated tests.

---

## 1. Functional requirements

### Module A — Advertiser and Campaign Management

Advertisers can create campaigns, configure targeting rules, set budgets, define schedules, and manage campaign status.

- Create, update, pause, resume, and end campaigns.
- Set daily and total campaign budgets.
- Configure campaign start and end dates.
- Configure keywords, allowed regions, and audience criteria.
- Reject invalid state transitions and configurations.

### Module B — Ad Selection and Serving

Publishers request ads using contextual information. The platform finds eligible campaigns and ranks their advertisements.

- Match keywords and contextual categories.
- Filter campaigns by status, schedule, targeting, and budget.
- Rank eligible ads using relevance, bid, and a defined scoring formula.
- Return the best ad or a meaningful no-fill response.
- Support fallback behavior when optional dependencies fail.

### Module C — Impression and Click Tracking

Record user interactions with served advertisements.

- Record an impression when an ad is actually displayed.
- Record clicks against valid served ads.
- Prevent duplicate event processing.
- Calculate impressions, clicks, and click-through rate (CTR).
- Process analytics asynchronously using Kafka.

### Module D — Performance and Reliability

Make ad selection fast and resilient.

- Cache frequently requested ad candidates in Redis.
- Invalidate or refresh cached data when campaign settings change.
- Handle concurrent requests and budget reservations safely.
- Implement timeouts, retries where appropriate, and idempotent event consumers.
- Expose health checks and performance metrics.

---

## 2. Non-functional requirements

Treat these as initial engineering targets, not claims about measured performance.

| Requirement | Initial target |
|---|---|
| Ad-serving latency | p95 under 100 ms in a defined test environment |
| Availability behavior | Return a controlled no-fill response if no eligible ad exists |
| Consistency | Never overspend a budget because of concurrent reservations |
| Event processing | At-least-once delivery with idempotent processing |
| Testability | Domain rules testable without Spring, database, or Kafka |
| Maintainability | Business rules isolated from infrastructure details |
| Security | Authenticated campaign management with advertiser-level access control |

---

## 3. Core business rules

These rules should drive the domain model and tests.

1. A campaign must have a valid name, positive bid, positive budget, and valid schedule.
2. A campaign cannot be served before its start time or after its end time.
3. A paused or completed campaign cannot serve ads.
4. A campaign is ineligible when its available budget is insufficient for the chosen reservation or charge.
5. A click must reference an ad that was previously served, subject to the defined attribution window.
6. The same event ID must not increment analytics twice.
7. A campaign cannot be resumed if it has expired or exhausted its budget.
8. Ranking must be deterministic when all inputs and tie-breakers are the same.
9. A failed analytics operation must not make a successful ad response fail.
10. Concurrent ad requests must not reserve more budget than is available.

### Decide on the billing model

Define whether budget is charged per impression, per click, or by another billing model. For the first version, use a **cost-per-click (CPC)** model:

- Reserve an estimated amount when serving an ad.
- Settle the actual charge when a valid click occurs.
- Release or expire unused reservations according to a defined policy.

This introduces useful concurrency and consistency problems to solve. Later, compare this approach with other billing models.

---

## 4. Apply OOAD: identify objects and responsibilities

Start with the domain concepts, not the Spring annotations.

### Suggested domain model

- **Advertiser:** Owns and manages campaigns.
- **Campaign (Aggregate Root):** Owns campaign status, budget, schedule, targeting, and bid configuration.
- **Ad:** Contains creative content, destination URL, and format.
- **Budget:** Represents available funds, reservations, and spending rules.
- **TargetingRules:** Defines context and audience matching criteria.
- **AdDecision:** Represents the selected ad and optionally explains the ranking result.
- **AdEvent:** Represents an impression or click.

### OOAD exercises

Before implementation, answer these questions:

- Which objects have identity, and which should be immutable value objects?
- Which object owns campaign state transitions?
- Should `Campaign` expose `setStatus()` or methods such as `pause()`, `resume()`, and `complete()`?
- Should `Budget` be a value object that enforces budget invariants?
- Where should the ranking algorithm live?
- How can the selection policy be replaced without changing the serving workflow?
- Which dependencies should be interfaces rather than concrete classes?

For example, prefer behavior-oriented methods:

```java
campaign.pause();
campaign.resume(currentTime);
campaign.isEligibleFor(request, currentTime);
```

over allowing arbitrary external code to modify campaign status and budget fields.

The first design protects invariants inside the domain. The second spreads business rules across controllers and services.

---

## 5. Apply DDD: organize around business capabilities

Start with a **modular monolith**, not independent microservices. This lets you learn domain boundaries and transactional consistency before adding distributed-system complexity.

### Suggested bounded contexts

#### Campaign Context
Responsible for campaign configuration, lifecycle, schedules, targeting configuration, and budget rules.

#### Ad Serving Context
Responsible for candidate retrieval, eligibility evaluation, ad selection, and ranking.

#### Tracking Context
Responsible for receiving and validating impression and click events.

#### Analytics Context
Responsible for aggregating event data and generating campaign reports.

### DDD rules to follow

- **Bounded contexts:** Give campaign management, serving, tracking, and analytics clearly defined responsibilities.
- **Aggregates:** Use the `Campaign` aggregate to protect campaign invariants. Avoid loading and modifying an entire campaign tree for every ad request.
- **Repositories:** Define domain-facing interfaces and implement them using Spring Data or custom persistence adapters.
- **Domain services:** Use them for business operations that do not naturally belong to one entity, such as selecting among eligible campaigns.
- **Value objects:** Consider `Money`, `Budget`, `CampaignSchedule`, `TargetingRules`, and `Bid`.
- **Domain events:** Raise events such as `CampaignPaused`, `CampaignBudgetExhausted`, and `AdClicked`.
- **Application services:** Orchestrate use cases without becoming a dumping ground for business rules.

**Important:** Redis caching is not the authority for budget correctness. PostgreSQL transactions, atomic conditional updates, or another explicitly designed reservation mechanism should protect the budget invariant. Cache invalidation and distributed consistency can be tackled after the core business rules are correct.

---

## 6. Apply TDD: red-green-refactor

Your first goal is not to create all the controllers. It is to prove the business rules through tests.

### Step 1 — RED: Write a failing test

Example: a paused campaign must never be eligible for serving.

```java
@Test
void pausedCampaignIsNotEligible() {
    Campaign campaign = activeCampaign();
    campaign.pause();

    assertFalse(
        campaign.isEligibleFor(
            validRequest(), fixedTime()
        )
    );
}
```

Use test fixtures and types that you define as you develop the domain model.

### Step 2 — GREEN: Implement the minimum code

Add the smallest implementation that makes the test pass. Avoid building unnecessary abstractions before the need appears.

### Step 3 — REFACTOR: Improve the design

Remove duplication, improve naming, extract value objects, and preserve passing tests. Do not change business behavior during refactoring.

### Essential test scenarios

| Feature | Tests to write |
|---|---|
| Campaign lifecycle | Draft → active → paused → resumed; reject invalid transitions |
| Schedule | Before start, at start, at end, after end |
| Budget | Zero budget, insufficient funds, exact remaining budget |
| Targeting | Keyword match, no match, partial match, region mismatch |
| Ranking | Higher score wins; deterministic tie-breaker |
| Serving | No eligible ads, one candidate, multiple candidates |
| Concurrency | Parallel reservations cannot overspend budget |
| Event tracking | Duplicate event ID is processed only once |
| Analytics | CTR calculation and zero-impression behavior |
| Cache | Stale campaign data after pause or budget exhaustion |

Use JUnit 5 for unit tests, Mockito when useful for isolation, and Testcontainers for integration tests involving PostgreSQL, Redis, or Kafka. Keep domain unit tests fast and independent of infrastructure.

---

## 7. API contract to implement

These are suggested endpoints. Final request and response DTOs should follow your domain decisions.

| Method | Endpoint | Purpose |
|---|---|---|
| `POST` | `/api/v1/campaigns` | Create a campaign |
| `GET` | `/api/v1/campaigns/{id}` | Retrieve campaign details |
| `POST` | `/api/v1/campaigns/{id}/activate` | Activate a campaign |
| `POST` | `/api/v1/campaigns/{id}/pause` | Pause a campaign |
| `POST` | `/api/v1/campaigns/{id}/resume` | Resume a campaign |
| `POST` | `/api/v1/ads/serve` | Select and return an eligible ad |
| `POST` | `/api/v1/events/impressions` | Record an impression |
| `POST` | `/api/v1/events/clicks` | Record a click |
| `GET` | `/api/v1/campaigns/{id}/analytics` | Retrieve campaign metrics |

### Example ad-serving request

```json
{
  "publisherId": "pub-101",
  "requestId": "req-9001",
  "pageUrl": "https://example.com/running-shoes",
  "keywords": ["running", "fitness", "shoes"],
  "region": "IN-KA",
  "adFormat": "BANNER"
}
```

### Example response

```json
{
  "requestId": "req-9001",
  "status": "FILLED",
  "ad": {
    "id": "ad-501",
    "campaignId": "cmp-101",
    "headline": "Find Your Next Running Shoes",
    "creativeUrl": "https://cdn.example.com/ad-501.png",
    "clickUrl": "https://example.com/products/shoes"
  }
}
```

The URLs and identifiers are illustrative. In a real system, click tracking should use a controlled redirect or signed tracking token rather than trusting arbitrary client-supplied campaign identifiers.

---

## 8. Implementation roadmap

This is a focused 18-day plan. Adjust it to your available time.

### Phase 1 — Domain modeling (Days 1–2)

- [ ] Write user stories and acceptance criteria.
- [ ] Draw the domain model and state transitions.
- [ ] Define value objects, aggregates, and invariants.

### Phase 2 — Core domain with TDD (Days 3–5)

- [ ] Implement `Campaign`, `Budget`, and `Schedule`.
- [ ] Implement targeting and ranking policies.
- [ ] Build unit tests before production code.

### Phase 3 — Persistence and REST APIs (Days 6–8)

- [ ] Add Spring Boot and PostgreSQL.
- [ ] Implement repositories and application use cases.
- [ ] Add request validation, error handling, and integration tests.

### Phase 4 — Ad-serving engine (Days 9–11)

- [ ] Implement candidate retrieval and ranking.
- [ ] Handle no-fill responses and budget reservations.
- [ ] Add concurrent reservation tests.

### Phase 5 — Redis and Kafka (Days 12–14)

- [ ] Cache campaign and candidate data.
- [ ] Publish tracking events asynchronously.
- [ ] Implement idempotent consumers and retry handling.

### Phase 6 — Production readiness (Days 15–18)

- [ ] Add security, metrics, and health checks.
- [ ] Run load and failure tests.
- [ ] Document architecture and key trade-offs.

Do not introduce Kafka and Redis merely to check off technologies. First make campaign eligibility, ranking, and budget behavior correct without them.

---

## 9. Recommended project structure

Start with package-by-feature and keep domain code independent of Spring.

```text
com.adserve
├── campaign
│   ├── domain
│   │   ├── Campaign.java
│   │   ├── Budget.java
│   │   ├── CampaignSchedule.java
│   │   └── CampaignRepository.java
│   ├── application
│   │   ├── CreateCampaignUseCase.java
│   │   └── ManageCampaignUseCase.java
│   ├── infrastructure
│   │   └── JpaCampaignRepository.java
│   └── api
│       └── CampaignController.java
├── serving
│   ├── domain
│   │   ├── AdSelectionPolicy.java
│   │   ├── TargetingRules.java
│   │   └── AdDecision.java
│   ├── application
│   │   └── ServeAdUseCase.java
│   └── infrastructure
├── tracking
├── analytics
└── shared
```

This is an initial organization, not a rigid template. If budget reservations become a separate transactional capability, give them an explicit model and ownership rather than hiding correctness rules inside a generic service.

---

## 10. Make the project interview-worthy

Prepare to explain these five design decisions:

1. **Why DDD?** Which business invariants justify the `Campaign` aggregate and separate serving and analytics contexts?
2. **Why this ranking strategy?** How do you combine relevance and bid without allowing an irrelevant high-bid ad to win every time?
3. **How do you prevent overspending?** Explain your transaction or atomic reservation strategy under concurrent requests.
4. **How do you handle duplicate Kafka messages?** Explain event IDs, idempotency, database constraints, and retry behavior.
5. **How do you optimize latency?** Explain database query design, Redis caching, cache invalidation, load testing, and p95 latency measurements.

The strongest result is not the number of technologies you use. It is being able to demonstrate that your domain model prevents invalid states, your tests verify real business rules, and your infrastructure choices follow from measurable requirements.

---

## 11. First assignment

Before creating the Spring Boot project:

1. Write the `Campaign` lifecycle rules and `Budget` invariants as tests or acceptance criteria.
2. Design the domain objects needed to make those tests pass.
3. Implement the first domain test using the TDD red-green-refactor cycle.
4. Keep the domain model free of Spring, database, Redis, and Kafka dependencies.

This is the best starting point for practicing OOAD, DDD, and TDD together.
