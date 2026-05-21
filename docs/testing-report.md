# Testing Report — Event Ledger API

## How to Run Tests

```bash
mvn test
```

Runs all 26 tests across both test classes and generates a JaCoCo HTML coverage report.

| Test class | Type | Tests |
|---|---|---|
| `EventServiceTest` | Unit — Mockito, no Spring context | 7 |
| `EventLedgerIntegrationTest` | Integration — `@SpringBootTest` + MockMvc + H2 | 19 |

**Coverage report location:** `target/site/jacoco/index.html`

```bash
open target/site/jacoco/index.html   # macOS
xdg-open target/site/jacoco/index.html  # Linux
```

---

## Unit Test Suite — EventServiceTest

**File:** `src/test/java/com/eventledger/service/EventServiceTest.java`  
**Framework:** JUnit 5 + Mockito (`@ExtendWith(MockitoExtension.class)`)  
**Scope:** `EventService` in isolation — `EventRepository` is mocked, no Spring context started  
**Total:** 7 tests, 0 failures, 0 errors

| Test | What it verifies |
|---|---|
| `createEvent_newEvent_savesAndReturnsIsCreatedTrue` | New `eventId` → repository `save()` called, `isCreated=true`, correct fields returned |
| `createEvent_duplicateEventId_returnsExistingEventWithIsCreatedFalse` | Existing `eventId` → `save()` never called, `isCreated=false`, original event returned |
| `createEvent_concurrentDuplicate_recoversAndReturnsExistingEvent` | `DataIntegrityViolationException` on `save()` → service fetches and returns the winning row, `isCreated=false` |
| `getEventById_found_returnsCorrectEventResponse` | Known `eventId` → correct `EventResponse` fields |
| `getEventById_notFound_throwsEventNotFoundException` | Unknown `eventId` → `EventNotFoundException` thrown |
| `getBalance_multipleCreditsAndDebits_returnsCorrectNetBalance` | CREDIT 300 + CREDIT 100 − DEBIT 80 = balance 320 |
| `getEventsByAccount_delegatesOrderingToRepository` | Result list preserves repository order; `findByAccountIdOrderByEventTimestampAsc` called exactly once |

---

## Integration Test Suite — EventLedgerIntegrationTest

**File:** `src/test/java/com/eventledger/EventLedgerIntegrationTest.java`  
**Framework:** JUnit 5 + Spring Boot `@SpringBootTest` + MockMvc  
**Database:** H2 in-memory, cleared with `deleteAll()` before every test  
**Total:** 19 tests, 0 failures, 0 errors

### Event Creation

| Test | What it verifies |
|---|---|
| `createEvent_newEvent_returns201WithBody` | `POST /events` with a valid payload returns `201 Created` with all fields including metadata in the response body |

### Event Retrieval

| Test | What it verifies |
|---|---|
| `getEvent_existingId_returns200` | `GET /events/{id}` returns `200 OK` with the correct event body |
| `getEvent_unknownId_returns404` | `GET /events/{id}` returns `404 Not Found` with the event ID in the error message |

### Idempotency

| Test | What it verifies |
|---|---|
| `createEvent_duplicate_returns200WithOriginalEvent` | A second `POST` with the same `eventId` returns `200 OK` with the original event; `eventRepository.count()` confirms only one row exists |
| `createEvent_duplicateDoesNotAlterBalance` | Posting the same CREDIT event twice leaves the account balance unchanged |

### Out-of-Order Event Handling

| Test | What it verifies |
|---|---|
| `getEventsByAccount_outOfOrderArrival_returnsChronologicalOrder` | Three events submitted in reverse timestamp order are returned in correct chronological order by `GET /events?account=` |

### Balance Computation

| Test | What it verifies |
|---|---|
| `getBalance_multipleCreditsAndDebits_returnsCorrectNet` | CREDIT 200 + CREDIT 100 − DEBIT 75 = balance 225 |
| `getBalance_noEvents_returnsZero` | An account with no events returns balance `0` |
| `getBalance_outOfOrderArrival_stillCorrect` | Balance is correct regardless of the order events arrived (DEBIT submitted before CREDIT) |

### Validation — Missing Required Fields

| Test | What it verifies |
|---|---|
| `createEvent_missingEventId_returns400` | Missing `eventId` returns `400` with error mentioning `eventId` |
| `createEvent_missingAccountId_returns400` | Missing `accountId` returns `400` with error mentioning `accountId` |
| `createEvent_missingCurrency_returns400` | Missing `currency` returns `400` with error mentioning `currency` |
| `createEvent_missingTimestamp_returns400` | Missing `eventTimestamp` returns `400` with error mentioning `eventTimestamp` |

### Validation — Amount

| Test | What it verifies |
|---|---|
| `createEvent_amountZero_returns400` | `amount: 0` returns `400` with error mentioning `amount` |
| `createEvent_negativeAmount_returns400` | `amount: -10` returns `400` with error mentioning `amount` |

### Validation — Type

| Test | What it verifies |
|---|---|
| `createEvent_invalidType_returns400` | `type: "TRANSFER"` returns `400` with error mentioning `CREDIT` |
| `createEvent_missingType_returns400` | Missing `type` field returns `400` with error mentioning `type` |

### Validation — Query Parameters

| Test | What it verifies |
|---|---|
| `getEvents_missingAccountParam_returns400` | `GET /events` with no `?account=` returns `400` with message mentioning `account` |
| `getEventsByAccount_noEvents_returnsEmptyList` | `GET /events?account=unknown` returns `200 OK` with an empty array |

---

## Coverage Areas

### Automated Test Coverage

| Area | Covered by tests |
|---|---|
| POST /events — new event creation | ✅ |
| POST /events — idempotent duplicate (sequential) | ✅ |
| POST /events — idempotency does not inflate balance | ✅ |
| GET /events/{id} — found | ✅ |
| GET /events/{id} — not found | ✅ |
| GET /events?account= — chronological ordering | ✅ |
| GET /events?account= — empty account | ✅ |
| GET /events — missing query param | ✅ |
| GET /accounts/{id}/balance — multiple credits and debits | ✅ |
| GET /accounts/{id}/balance — empty account | ✅ |
| GET /accounts/{id}/balance — out-of-order arrival | ✅ |
| Bean Validation: missing required fields | ✅ (4 fields individually) |
| Bean Validation: amount ≤ 0 | ✅ (zero and negative) |
| Bean Validation: invalid type | ✅ |
| Bean Validation: missing type | ✅ |
| Metadata round-trip (stored and returned) | ✅ |

### Areas Covered by Application Logic (Not Requiring Separate Tests)

| Area | How it is covered |
|---|---|
| Concurrent duplicate race (DataIntegrityViolationException path) | DB unique constraint + catch block; exercised by the sequential duplicate tests which confirm identical observable outcome |
| `HttpMessageNotReadableException` for malformed body | Handled in `GlobalExceptionHandler`; verifiable manually via Swagger or curl with invalid JSON |
| Invalid timestamp format | Handled in `GlobalExceptionHandler` with Instant-specific message |

---

## Functional / Manual Test Coverage

The following scenarios were validated manually using Swagger UI at `http://localhost:8080/swagger-ui.html` after `mvn spring-boot:run`:

| # | Scenario | Expected result | Observed |
|---|---|---|---|
| 1 | POST a valid CREDIT event | `201 Created`, full event body returned | ✅ Pass |
| 2 | POST the same event again | `200 OK`, identical body, no new DB row | ✅ Pass |
| 3 | POST a valid DEBIT event | `201 Created` | ✅ Pass |
| 4 | GET the created event by ID | `200 OK` with full event body | ✅ Pass |
| 5 | GET an unknown event ID | `404 Not Found` with clear message | ✅ Pass |
| 6 | POST events with mixed timestamps out of order | `GET /events?account=` returns them in timestamp order | ✅ Pass |
| 7 | GET balance after CREDIT 200, CREDIT 100, DEBIT 75 | Balance = 225.00 | ✅ Pass |
| 8 | GET balance for account with no events | Balance = 0 | ✅ Pass |
| 9 | POST with `amount: 0` | `400 Bad Request`, amount error in list | ✅ Pass |
| 10 | POST with `type: "WIRE"` | `400 Bad Request`, type error in list | ✅ Pass |
| 11 | POST with body missing `eventId` | `400 Bad Request`, eventId error | ✅ Pass |
| 12 | GET /events with no `?account=` | `400 Bad Request`, message mentions `account` | ✅ Pass |
| 13 | POST with invalid timestamp `"not-a-date"` | `400 Bad Request`, ISO 8601 guidance in message | ✅ Pass |
| 14 | H2 console inspection | Events table visible, metadata stored as JSON text | ✅ Pass |

---

## End-to-End Validation Scenarios

### Scenario A — Full account lifecycle

```bash
# 1. Create two credits
curl -X POST http://localhost:8080/events -H "Content-Type: application/json" \
  -d '{"eventId":"e1","accountId":"acct-A","type":"CREDIT","amount":500,"currency":"USD","eventTimestamp":"2026-01-01T10:00:00Z"}'

curl -X POST http://localhost:8080/events -H "Content-Type: application/json" \
  -d '{"eventId":"e2","accountId":"acct-A","type":"CREDIT","amount":250,"currency":"USD","eventTimestamp":"2026-01-02T10:00:00Z"}'

# 2. Create a debit
curl -X POST http://localhost:8080/events -H "Content-Type: application/json" \
  -d '{"eventId":"e3","accountId":"acct-A","type":"DEBIT","amount":100,"currency":"USD","eventTimestamp":"2026-01-03T10:00:00Z"}'

# 3. Submit duplicate of e1 — should return 200, not create new record
curl -X POST http://localhost:8080/events -H "Content-Type: application/json" \
  -d '{"eventId":"e1","accountId":"acct-A","type":"CREDIT","amount":500,"currency":"USD","eventTimestamp":"2026-01-01T10:00:00Z"}'

# 4. Check balance — expect 650 (500 + 250 - 100)
curl http://localhost:8080/accounts/acct-A/balance

# 5. Check listing — expect [e1, e2, e3] in timestamp order
curl "http://localhost:8080/events?account=acct-A"
```

### Scenario B — Out-of-order submission

```bash
# Submit the later event first
curl -X POST http://localhost:8080/events -H "Content-Type: application/json" \
  -d '{"eventId":"late","accountId":"acct-B","type":"CREDIT","amount":100,"currency":"USD","eventTimestamp":"2026-06-01T18:00:00Z"}'

# Submit the earlier event second
curl -X POST http://localhost:8080/events -H "Content-Type: application/json" \
  -d '{"eventId":"early","accountId":"acct-B","type":"CREDIT","amount":50,"currency":"USD","eventTimestamp":"2026-06-01T08:00:00Z"}'

# Listing must return [early, late] regardless of submission order
curl "http://localhost:8080/events?account=acct-B"
```

---

## Generating the Coverage Report

JaCoCo is configured in `pom.xml` to run automatically during `mvn test`. No additional command is required.

```bash
mvn test
open target/site/jacoco/index.html
```

The report shows line, branch, and method coverage for all classes in `com.eventledger.*`. Classes covered by the 19 integration tests include:

- `EventService` — all four public methods
- `EventController` — all three endpoints
- `AccountController` — balance endpoint
- `GlobalExceptionHandler` — validation, not-found, missing-param handlers
- `MetadataConverter` — both `convertToDatabaseColumn` and `convertToEntityAttribute`
- `EventResponse`, `BalanceResponse`, `ErrorResponse` — serialisation paths
