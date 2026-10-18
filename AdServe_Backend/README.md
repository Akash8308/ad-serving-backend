# AdServe

Contextual ad-serving backend. Design: [`docs/AD_SERVE_OOAD.md`](docs/AD_SERVE_OOAD.md); review that shaped it:
[`docs/AD_SERVE_DESIGN_REVIEW.md`](docs/AD_SERVE_DESIGN_REVIEW.md).

## Status: phases 1-2 plus the serve use case

| Area | State |
|---|---|
| Shared kernel (money, ids, schedule, targeting) | done |
| Campaign aggregate, lifecycle, create/manage use cases | done |
| Budget ledger port, `BudgetAccount` rules, in-memory ledger | done |
| Ranking policy, eligibility, `ServeAdUseCase` | done |
| HMAC tracking token codec | done |
| PostgreSQL ledger, JPA mapping, Flyway DDL | **next** (design section 7, 12) |
| Redis candidate cache, REST controllers, security | next |
| Impression/click use cases, outbox relay, Kafka consumer, analytics | next |

## Configuration

`src/main/resources/application.properties` holds the base settings. Hosts and secrets come from environment
variables (`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `REDIS_HOST`, `KAFKA_BOOTSTRAP_SERVERS`, `ADSERVE_TOKEN_KEY_K1`).
`application-dev.properties` gives local defaults; activate it with `-Dspring.profiles.active=dev`. The `adserve.*`
keys (reservation TTL, timeouts, outbox and consumer settings) are not read by any code yet; they are bound when the
infrastructure layer is added.

## Run the tests

```bash
mvn test
```

Unit and contract tests need no Docker. Testcontainers-based tests arrive with the PostgreSQL ledger.

## Layout

```
com.adserve.shared      value objects shared by every context
com.adserve.campaign    domain / application      (config + lifecycle; no money counters)
com.adserve.budget      domain                    (BudgetLedger port, BudgetAccount rules)
com.adserve.serving     domain / application      (read model, ranking, ServeAdUseCase)
com.adserve.tracking    domain / infrastructure   (signed tracking token)
```

`src/test/.../testsupport` holds the in-memory fakes. `BudgetLedgerContract` defines the behavior every ledger must
have; the PostgreSQL ledger will extend that same class.

## Known difference from the design document

The document lists BUD-11 and BUD-12 (parallel reservation, settle-versus-sweeper race) as PostgreSQL-only. They are
implemented in the shared contract so they also run against the in-memory ledger; the PostgreSQL run is the one
that proves the SQL.
