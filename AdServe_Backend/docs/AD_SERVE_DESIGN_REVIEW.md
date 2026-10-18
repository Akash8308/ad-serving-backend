# AdServe OOAD v1 — Independent Design Review

**Reviewed:** `AD_SERVE_OOAD_v1_draft.md` against `AdServe_Project_Problem_Statement.md`.

**Caveat on independence.** The reviewer is the same model that wrote v1, so shared blind spots are possible. To reduce that risk, every finding below is argued from a concrete failure scenario that can be checked by hand or turned into a test. Treat the "failure scenario" lines as test cases to try, not as assertions to trust.

**Verdict.** v1 gets the shape right: behavior methods instead of setters, a domain free of Spring, CPC reserve/settle, and an idempotent consumer in the same transaction as its side effect. But it has four Critical defects. The most serious is that the serving path can serve a paused campaign (R-02), which breaks business rule 3 directly. Money handling is also unsafe. Do not implement v1 as written.

---

## A. Answers to the 12 review questions

| # | Question | Verdict | Findings |
|---|---|---|---|
| 1 | Every requirement → use case → design → test? | **No.** The §2 table has no test column. `activate`, security, health/metrics, fallback behavior, and the attribution window have no use case. | R-13, R-14 |
| 2 | Aggregate boundaries and invariants | **Wrong.** Budget and reservations inside `Campaign` create a hot, unbounded aggregate. | R-01, R-05 |
| 3 | Anemic models, god classes, misplaced responsibilities | `Campaign` is a god aggregate. `Advertiser` and `AdEvent` are anemic. | R-01, R-16 |
| 4 | Patterns justified? | Mostly no. Five of ten patterns are unjustified. | R-15 |
| 5 | SOLID / Clean Architecture | Layering is fine. Serving reads another context's aggregate, and the ACL claim contradicts §7. | R-09 |
| 6 | Serving path fast, minimal DB work | **No.** About 5 writes per serve, N Redis reads, and full aggregates cached. | R-08, R-12 |
| 7 | Concurrent overspend | Optimistic locking prevents overspend on reserve. **Settlement and daily reset can still overspend or double-charge.** | R-03, R-06 |
| 8 | Stale cache serving ineligible ads | **Yes, it can.** | R-02, R-10 |
| 9 | Kafka duplicate / lost / out-of-order | All three are possible. | R-03, R-11, R-12 |
| 10 | Outbox and idempotency necessary and correct? | Outbox is needed for one event only. Idempotency is keyed on a client-supplied ID. | R-04, R-12 |
| 11 | API, errors, mappings, UML consistent? | No. | R-19, R-20, R-21 |
| 12 | TDD tests behavior, not implementation? | Phases 2, 3, and 5 test implementation. | R-23, R-24 |

---

## B. Findings

### Critical

**R-01 — Budget and reservations live inside the `Campaign` aggregate**
- **Where:** §3 (`Budget`, `Reservation`), §4, §7 step 6, §8.
- **Problem:** Every serve loads the full `Campaign` tree (ads, budget, all reservations) and saves it under one `@Version`. Config edits and money movements share a single concurrency token. The `reservations` list is unbounded, because settled and released entries stay in the aggregate.
- **Failure scenario:** A campaign takes 200 serves/s. Each one bumps `version`, so about 199 of every 200 concurrent attempts hit `OptimisticLockException`. After 3 retries those requests become NO_FILL while budget remains. An advertiser pressing "Pause" during this load gets a `409` because the version moved under them.
- **Correction:**
  - Split money out of `Campaign`. The `Campaign` aggregate keeps config and lifecycle only.
  - Create a separate budget ledger with counters plus a `reservations` table.
  - Mutate it only through atomic conditional SQL, never by loading it as an object graph.
  - Keep a pure `BudgetAccount` class as the executable specification. An in-memory implementation uses it, and contract tests run against both the in-memory and Postgres implementations.
- **Trade-offs:** `Campaign` no longer owns its budget, which departs from the problem statement's suggested model. Creating a campaign now writes two tables in one transaction. The rule logic exists twice (Java spec and SQL), and contract tests are what keep them aligned.

**R-02 — `reserve` checks only budget, and eligibility runs on a cached copy**
- **Where:** §7 steps 3, 5, 6; §3 `reserve(...)`.
- **Problem:** The eligibility chain runs on the Redis copy, which can be 60 s old (§9). `findById` then loads the fresh campaign, but `reserve` checks budget only and never re-checks status or schedule.
- **Failure scenario:**
  1. Candidate cache loads at 10:00:00.
  2. The advertiser pauses the campaign at 10:00:05.
  3. A request at 10:00:30 passes the chain on the stale copy and reserves successfully, because `reserve` ignores status.
  4. A paused campaign is served and billed. Rule 3 and the "never serve paused" requirement are violated.
- **Correction:** Make the authoritative gate part of the reservation itself. The reservation statement must require `campaigns.status='ACTIVE' AND start_at <= :now AND end_at > :now` in the same atomic update that checks funds. Treat caches as candidate hints only.
- **Trade-offs:** The ledger's SQL reads the `campaigns` table, a deliberate cross-context read inside one database. A pause committing within milliseconds of a reservation can lose the race by one ad. That is acceptable and should be documented as such.

**R-03 — Click settlement is asynchronous via Kafka and its failure paths are unspecified**
- **Where:** §8 (`BudgetSettlementConsumer`, `settle`), §10 (retries and DLT).
- **Problem:**
  - `settle(reservationId)` has no stated behavior for a reservation that was already released.
  - A click is billed after Kafka lag and retries, so it can arrive after the 1-minute sweeper has released the reservation.
  - The dead-letter path silently drops settlements.
- **Failure scenarios:**
  - The sweeper releases a reservation, then the click event arrives. If `settle` is permissive, `spent` grows with no reserved capacity behind it, and the campaign overspends by the amount of every late click. If `settle` is strict, a legitimate click is lost.
  - Three consumer retries fail, the message goes to the DLT, and the advertiser is never charged for a real click.
- **Correction:** Settle synchronously in the click request, in one local transaction.
  - A conditional `UPDATE reservations SET status='SETTLED' WHERE id=? AND status='RESERVED' AND expires_at > now`.
  - The charge equals the reserved amount, so settlement can never exceed what was reserved.
  - A second click matches zero rows and is reported as a duplicate, charging nothing.
  - Late clicks are rejected explicitly with a defined error.
- **Trade-offs:** The click endpoint now takes a DB write. Click volume is about 1–2% of serve volume, and it is off the serving path, so this is cheap.

**R-04 — Event identity and attribution are client-supplied, with Redis as the source of truth**
- **Where:** §11 (`{eventId, requestId, adId, campaignId}`), §7 step 7 (`served:{requestId}`), §10 (`processed_events`).
- **Problem:**
  - Idempotency is keyed on a client-chosen `eventId`.
  - Attribution (rule 5) depends on a Redis key that is lost on flush or failover.
  - `campaignId` and `adId` are trusted from the client. The problem statement explicitly warns against this.
- **Failure scenarios:**
  - A script replays one valid `requestId` with fresh `eventId`s. Each passes dedupe, so a single serve is billed N times, and a competitor's budget can be drained.
  - Redis restarts. Every click in the next 30 minutes is rejected or unverifiable.
- **Correction:**
  - Issue a signed tracking token (HMAC) at serve time, carrying the reservation id, campaign id, ad id, publisher id, and expiry.
  - Derive event ids deterministically, as `reservationId + ":IMP"` and `reservationId + ":CLK"`.
  - Make the `reservations` row in Postgres the durable "ad was served" record.
- **Trade-offs:** A signing key must be managed and rotated. Tokens are bearer credentials, so a leaked token can be replayed, but only once per serve.

### High

**R-05 — `EXHAUSTED` is a persisted lifecycle status**
- **Where:** §3 statuses and invariant 4, §8, §13 (state diagram omits it).
- **Problem:**
  - Exhaustion is derivable from the counters.
  - Daily exhaustion is temporary, and the status cannot represent that.
  - There are two sources of truth, and nothing specifies how EXHAUSTED returns to ACTIVE.
- **Failure scenario:** The campaign hits its daily cap at 14:00 and flips to EXHAUSTED. At midnight it should resume serving, but nothing flips it back, so it stays dark. Alternatively the flip races with a concurrent settle.
- **Correction:**
  - Persist only DRAFT, ACTIVE, PAUSED, and COMPLETED.
  - Treat exhaustion as a derived `servingState` computed from the ledger snapshot and the schedule.
  - The resume use case reads the ledger snapshot and passes it into `campaign.resume(now, budgetView)`.
- **Trade-offs:** Resume now spans two aggregates, coordinated in the application layer. A tiny race with a concurrent settle is harmless, because the reservation gate still enforces funds.

**R-06 — Midnight `dailySpent` reset job**
- **Where:** §3 invariant 5, §8.
- **Problem:** The time zone is undefined. A job that resets every campaign is a bulk write that races with live reservations.
- **Failure scenario:**
  - The job resets `dailySpent` while a reserve is in flight, so the daily-cap check reads stale data.
  - A settlement of a reservation made yesterday is counted against today's cap.
  - The job is down for an hour, so the campaign serves on yesterday's totals.
- **Correction:**
  - Define the day as the UTC calendar date.
  - Store `daily_day` on the budget row and roll over lazily inside the reservation statement using `CASE WHEN daily_day = :today`.
  - Each reservation records its own `day`, and settle and release adjust daily counters only when `daily_day` matches.
- **Trade-offs:** The SQL is more intricate, which makes contract tests essential. Per-campaign time zones are deferred.

**R-07 — Pessimistic reservation with a 30-minute TTL starves campaigns**
- **Where:** §1, §8.
- **Problem:** The full bid is reserved per serve and held for 30 minutes, but only about 1–2% of serves produce a click.
- **Failure scenario:** Total budget is ₹1,000 and the bid is ₹10. After 100 serves the campaign appears exhausted, with about 1–2 real clicks and nearly ₹1,000 locked for 30 minutes. The campaign delivers about 100 impressions per half hour regardless of demand.
- **Correction:** Keep strict reservation, because the problem statement requires "never overspend" under CPC. Make the TTL a configuration value (`adserve.reservation.ttl`), default **10 minutes**, and document the delivery impact as a known trade-off. Rejected alternative: reserve the expected cost and tolerate bounded overspend, which violates the non-functional requirement.
- **Trade-offs:** A shorter window excludes late clicks. Product must confirm the attribution window.

**R-08 — The serving path is heavier than needed**
- **Where:** §7 steps 2, 6–8.
- **Problem:**
  - Redis lookup is per keyword (N round trips), and the values are full `Campaign` aggregates.
  - Writes per serve: the campaign row (version bump), budget, reservation, outbox row (`AdServed`), and a Redis `SET`. Each is an extra failure point on the hot path.
  - A cache miss falls through to a keyword-join query inside the request.
- **Failure scenario:** At TTL expiry, 500 concurrent requests all miss and all run the DB query (stampede). p95 breaks 100 ms and the DB takes the full load.
- **Correction:**
  - Cache a small read model per `(adFormat, region)` as one Redis value, with a single-flight loader on miss.
  - Do keyword matching in memory.
  - The only per-serve DB work is one reservation transaction (gate update plus reservation insert), with no outbox row, no Redis write, and no campaign write.
- **Trade-offs:** A (format, region) list may be large. Measure first, then add a short-TTL in-process L1 cache if deserialization shows up in profiles.

**R-09 — Serving consumes the `Campaign` aggregate directly**
- **Where:** §7 (cache stores `Campaign` JSON), §5 (ACL claim), §6.
- **Problem:** §5 claims an anti-corruption layer between Campaign and Serving, but §7 serializes the write-side aggregate into Redis. The domain model then needs serialization concerns, and any aggregate change breaks the cached schema.
- **Failure scenario:** Adding a field to `Campaign` during a rolling deploy makes old cache entries fail to deserialize, so serving errors until the TTL passes.
- **Correction:**
  - Give Serving its own immutable `ServableCampaign` read model (id, bid, schedule, targeting, ads).
  - Build it by a SQL projection, with a versioned cache DTO in infrastructure.
  - Share only the value objects (`CampaignSchedule`, `TargetingRules`, `Money`), so rules are not duplicated.
- **Trade-offs:** One more class, in exchange for isolating the contexts. The eligibility rule has to be placed once and reused.

**R-10 — Cache invalidation is slow, racy, and incomplete**
- **Where:** §9.
- **Problem:**
  - Invalidation travels through outbox → relay (500 ms) → Kafka → consumer, so latency stacks up on a safety-critical path.
  - Keys are keyword-scoped, so one change touches many keys.
  - Deleting keys races with in-flight refills.
  - Budget exhaustion is cached for 60 s.
- **Failure scenario:** A cache refill reads the old DB row, the invalidation `DEL` runs, and then the refill writes the stale value back. The stale value now lives for a full TTL.
- **Correction:**
  - Invalidate in-process, after commit, with `@TransactionalEventListener(AFTER_COMMIT)` and a coarse delete of all candidate keys.
  - Cap the TTL at 30 s.
  - Design explicitly so that **correctness never depends on invalidation**. Stale candidates only waste an attempt, because the reservation gate (R-02) rejects them.
- **Trade-offs:** More refills after each change. Those refills are lazy and single-flight, and changes are rare relative to serves.

**R-12 — Outbox is applied to every event**
- **Where:** §5, §10.
- **Problem:** `AdServed` adds a DB write to every serve for an event nobody needs, because the reservation row already records it. Impressions have no DB state change to make atomic with an event. The relay is unspecified for multiple instances, so two relays can double-publish or contend.
- **Failure scenario:** Two app instances poll the outbox simultaneously. Both publish the same rows, and the consumer shrugs it off only thanks to dedupe, but they also deadlock on row updates.
- **Correction:**
  - Use the outbox only for the **click** event, because a billing state change and its analytics event must commit atomically.
  - Publish impressions directly to Kafka with an idempotent producer, and return 503 if publishing fails so the client retries.
  - The relay uses `SELECT … FOR UPDATE SKIP LOCKED` in batches.
- **Trade-offs:** Two publish paths (outbox and direct). Both are small, and each is justified by whether a DB state change must be atomic with the event.

**R-13 — Requirements traceability has gaps**
- **Where:** §2, §14.
- **Gaps:**
  - No use case for `activate`, even though it exists in the API and in the DRAFT → ACTIVE transition.
  - No design or tests for authentication or advertiser-level access (NFR).
  - No design for health checks or metrics (FR-D5).
  - No design for fallback behavior when dependencies fail (FR-B5).
  - No attribution window (rule 5).
  - Rules 8 and 9 have no test.
  - The §2 table has no test column.
- **Correction:** Full matrix of requirement → use case → design element → test ID, with every requirement and business rule covered.
- **Trade-offs:** More documentation, but it is the only way to prove coverage.

**R-14 — No advertiser-level authorization**
- **Where:** §11, §6.
- **Problem:** `GET /campaigns/{id}` and the pause/resume endpoints take only an id.
- **Failure scenario:** Advertiser A guesses or enumerates campaign UUIDs, then reads or pauses advertiser B's campaigns.
- **Correction:** Load campaigns via `findByIdAndAdvertiserId`, or call `campaign.assertOwnedBy(advertiserId)`, taking `advertiserId` from the authenticated principal. Return 404 on mismatch so existence is not leaked.
- **Trade-offs:** None of significance.

### Medium

**R-11 — Kafka key and ordering**
- **Where:** §10.
- **Problem:** The key is `eventId`, which is unique per event, so it gives no ordering at all. Three unrelated consumers share one topic.
- **Failure scenario:** `CampaignPaused` and `CampaignResumed` are consumed in the wrong order, leaving the cache handler with the opposite state.
- **Correction:**
  - Remove campaign events from Kafka entirely (R-10).
  - Use the tracking topic only for impressions and clicks, keyed by `reservationId`.
  - Analytics counters are commutative, so ordering does not affect totals.
- **Trade-offs:** None.

**R-15 — Pattern inventory is over-built**
- **Where:** §5.

| Pattern in v1 | Verdict | Reason |
|---|---|---|
| Strategy for ranking | **Keep** | The problem statement asks for a replaceable selection policy |
| Chain of Responsibility for filters | Remove | Four predicates; duplicates `isEligibleFor` |
| Specification for targeting | Remove | `TargetingRules.matches(request)` is enough |
| `CampaignFactory` | Remove | A static factory method is enough |
| Decorator `CachingCampaignRepository` | Replace | Wrong type to cache (R-09) |
| Domain events + Observer | **Keep, narrowed** | In-process only, for cache eviction |
| Outbox | **Keep, narrowed** | Click event only (R-12) |
| CQRS | Remove the label | It is a counters table and a query |
| Anti-corruption layer | Remove | Replaced by a real read model (R-09) |
| Circuit breaker | **Keep** | Redis and DB timeouts on the serving path |

**R-16 — God aggregate and anemic objects**
- **Where:** §3.
- **Problem:**
  - `Campaign` owns configuration, lifecycle, money, reservations, and eligibility.
  - `Advertiser` has no invariants, and the `campaignIds` list duplicates `Campaign.advertiserId`.
  - `AdEvent` as one mutable entity with a type flag has no behavior.
- **Correction:**
  - Drop the `Advertiser` aggregate in v1 and use an `AdvertiserId` value object from the authenticated principal.
  - Make events immutable records (`ImpressionRecorded`, `ClickRecorded`).
  - Move money into the ledger (R-01).
- **Trade-offs:** No advertiser profile management, which is out of the stated scope.

**R-17 — Ranking uses `double`, and `relevance` penalizes broad targeting**
- **Where:** §7 step 4.
- **Problem:**
  - `relevance = matched / campaign.keywords.size` means an advertiser with 20 keywords matching 3 scores 0.15, while one with 1 keyword matching 1 scores 1.0.
  - Floating-point scores plus a tie-break only on campaignId leave ad selection within one campaign unspecified.
- **Failure scenario:** The same inputs rank differently across JVMs or refactors, which breaks rule 8. Broad advertisers are systematically penalized.
- **Correction:**
  - Define relevance as request-side coverage, in basis points: `10_000 × matchedTerms / requestTerms`.
  - Use `score = bidMinor × relevanceBp` as a `long`.
  - Tie-break chain: score, relevance, bid, campaignId, adId.
- **Trade-offs:** The formula is simple and has no tuning knob. Add one only when product asks.

**R-18 — Duplicate `requestId` is not handled on serve**
- **Where:** §7.
- **Problem:** `reserve` accepts a `requestId` but nothing de-duplicates it.
- **Failure scenario:** A publisher times out and retries the same request, so two reservations are created for one page view. Funds are locked twice and two different ads may come back.
- **Correction:** Add a unique constraint on `(publisher_id, request_id)`. A conflict returns the original reservation and its ad snapshot.
- **Trade-offs:** A small ad snapshot is stored on the reservation row.

**R-19 — API contract gaps**
- **Where:** §11.
- **Problems:**
  - `activate` is missing from the endpoint table.
  - The serve response has no tracking token.
  - The no-fill reason is unspecified.
  - Error shape is ad hoc, and there is no status for a late click or for an unavailable event bus.
  - `/analytics` has no date range or spend.
  - `POST /campaigns` does not say whether budget fields exist, even though budget is required (rule 1).
- **Correction:** Specify every endpoint with problem-details errors and a code catalog (see the revised §10).
- **Trade-offs:** None.

**R-20 — UML and model inconsistencies**
- **Where:** §3, §13.
- **Problems:**
  - The state diagram omits EXHAUSTED.
  - The class diagram has `Bid`, `AdDecision`, and `AdEvent` listed in §3 but missing.
  - `Advertiser` appears as owner through `campaignIds`, while `Campaign` also holds the advertiser reference.
  - `reserve` appears on `Campaign` and on `Budget`.
- **Correction:** Regenerate all diagrams from the revised model.

**R-21 — Persistence design issues**
- **Where:** §12.
- **Problems:**
  - Keywords are stored twice (`campaign_keywords` and JSONB).
  - The `reservations` table is unbounded, with no index for the sweeper.
  - There are no `CHECK` constraints as a last line of defense for the money invariants.
  - `campaign_stats` has no day dimension, so reports can't have date ranges.
  - `processed_events` grows forever.
  - MapStruct on a deep aggregate graph adds mapping cost for little benefit.
- **Correction:**
  - Store targeting as arrays on the campaign row.
  - Add a partial index on `reservations(expires_at) WHERE status='RESERVED'`, and plan day partitions.
  - Add `CHECK (spent + reserved <= limit)`.
  - Use `campaign_daily_stats(campaign_id, day)`.
  - Purge `processed_events` after 7 days.
  - Use JPA only for `Campaign` and `JdbcTemplate` elsewhere.
- **Trade-offs:** Day partitions need operational care, so defer until volume demands them.

**R-23 — TDD roadmap tests implementation**
- **Where:** §14 phases 2, 3, 5.
- **Problem:**
  - `CampaignRepositoryAdapterTest` verifies SQL text.
  - `ServeAdUseCaseTest` asserts the order of `verify(...)` calls and that `outbox.append` was called.
  - `OutboxRelayTest` verifies that `kafkaTemplate.send` was called.
- **Failure scenario:** Fixing R-08 by removing the outbox call from serving breaks tests while behavior is unchanged. Meanwhile the real bug in R-02 (stale cache serves a paused campaign) is not caught by any test.
- **Correction:**
  - Test through in-memory fakes of the ports and assert on outcomes: what was served, what was charged, what was counted.
  - Run contract tests against both the fake and the Postgres implementation.
  - Add a regression test for each Critical finding.
- **Trade-offs:** Fakes cost some code and must be kept faithful, which is what the contract tests are for.

**R-24 — The concurrency test cannot prove the real property**
- **Where:** §14 phase 4.
- **Problem:** 50 threads calling `campaign.reserve` on one in-memory object tests Java memory visibility and `synchronized`, not the database guarantee that production relies on.
- **Failure scenario:** The test passes forever while the SQL has an off-by-one in its `<=` and overspends by one reservation.
- **Correction:** Add Testcontainers concurrency tests (200 parallel reservations against a budget that fits 50 asserts exactly 50 succeed) plus a click-versus-expiry race test.
- **Trade-offs:** Slower tests, kept in a separate integration suite.

**R-25 — Dependency failure behavior undefined**
- **Where:** §5 (circuit breaker mention), §7.
- **Problem:** v1 does not say what happens when Redis, Kafka, or Postgres is slow or down, and has no timeouts.
- **Correction:**
  - Redis down: fall back to a DB candidate query behind a circuit breaker.
  - Kafka down: serving is unaffected, since it never touches Kafka; impressions return 503.
  - Postgres down: respond `NO_FILL` with reason `TEMPORARILY_UNAVAILABLE`.
  - Set explicit timeouts for each dependency.
- **Trade-offs:** The DB fallback can overload Postgres during a Redis outage, so the breaker needs a concurrency cap.

### Low

**R-22 — `AdDecision` always carries an explanation**
- **Where:** §3.
- **Problem:** Building the ranking explanation for every request allocates on the hot path.
- **Correction:** Populate it only when a debug flag is set.

**R-26 — `Bid` and `Money` are redundant**
- **Where:** §3.
- **Problem:** `Bid` is just a positive `Money`.
- **Correction:** Drop `Bid` and enforce "positive" in `Campaign`.

**R-27 — No `Clock` port**
- **Where:** §3, §7.
- **Problem:** Time comes from `now` parameters in some places and is unspecified in others, which makes eligibility boundary tests brittle.
- **Correction:** Inject a `Clock` in application services and pass `Instant` into domain methods.

---

## C. Prioritized design changes

| Priority | Change | Fixes |
|---|---|---|
| 1 | Separate the budget ledger from `Campaign`. Atomic conditional SQL for reserve, settle, and expire. | R-01, R-03, R-06, R-07 |
| 2 | Put the eligibility gate (status and schedule) inside the reservation statement. | R-02, R-10 |
| 3 | Signed tracking token, deterministic event ids, and synchronous click settlement. | R-03, R-04, R-18 |
| 4 | Serving read model, a single Redis value per (format, region), and an in-memory ranking pass. One DB transaction per serve. | R-08, R-09 |
| 5 | Outbox for the click event only. Impressions go direct to Kafka. Remove campaign events from Kafka. | R-11, R-12 |
| 6 | Derive the exhausted state. Persist four statuses only. | R-05 |
| 7 | Authorization by advertiser, fully specified API and error catalog. | R-14, R-19 |
| 8 | Remove the pattern bloat. Fix ranking arithmetic. | R-15, R-16, R-17 |
| 9 | Full requirement → test matrix. Behavior-level tests with contract tests and concurrency tests. | R-13, R-23, R-24, R-25 |
| 10 | Regenerate UML and persistence. | R-20, R-21, R-22, R-26, R-27 |

## D. What v1 got right and stays

- Behavior-oriented `Campaign` methods, with no `setStatus`.
- A domain layer free of Spring, JPA, Redis, and Kafka.
- CPC with reserve/settle/release, as the problem statement requires.
- Idempotent consumer with dedupe written in the same transaction as its side effect.
- A modular monolith.
- Testcontainers for integration tests.
