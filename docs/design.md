# Event Ledger API — Design Document

## 1. Problem Summary

Upstream financial systems publish transaction events to this API. Because those systems are not perfectly synchronized, two reliability challenges must be handled correctly:

1. **Duplicate delivery** — the same event may arrive more than once.
2. **Out-of-order delivery** — an event with an earlier `eventTimestamp` may arrive after a later one.

The API must store events reliably, expose them in chronological order, compute an accurate account balance at any time, and reject malformed input with clear error messages.

---

## 2. Requirements Summary

### Functional Requirements

| # | Requirement |
|---|---|
| F1 | Accept `POST /events` to record a new transaction event |
| F2 | Return an existing event unchanged when the same `eventId` is submitted again (idempotency) |
| F3 | Expose `GET /events/{id}` to retrieve a single event by its ID |
| F4 | Expose `GET /events?account={accountId}` to list all events for an account, ordered by `eventTimestamp` ascending |
| F5 | Expose `GET /accounts/{accountId}/balance` returning `Σ CREDIT − Σ DEBIT` |
| F6 | Validate all required fields; reject invalid input with a structured `400` response |

### Non-Functional Requirements

| # | Requirement |
|---|---|
| N1 | Runnable locally with a single command — no external database required |
| N2 | Automated tests runnable with `mvn test` |
| N3 | Meaningful commit history that reflects the development process |
| N4 | Clean, maintainable layered architecture |

---

## 3. API Design

### POST /events

Accepts a JSON event payload. Returns `201 Created` for a new event, `200 OK` for a duplicate (the original stored event is returned unchanged).

**Request body:**

```json
{
  "eventId": "evt-001",
  "accountId": "acct-123",
  "type": "CREDIT",
  "amount": 150.00,
  "currency": "USD",
  "eventTimestamp": "2026-05-15T14:02:11Z",
  "metadata": { "source": "mainframe-batch", "batchId": "B-9042" }
}
```

**Field rules:**

| Field | Type | Required | Validation |
|---|---|---|---|
| `eventId` | string | Yes | Non-blank |
| `accountId` | string | Yes | Non-blank |
| `type` | string | Yes | Must be `CREDIT` or `DEBIT` |
| `amount` | number | Yes | Must be > 0 |
| `currency` | string | Yes | Non-blank |
| `eventTimestamp` | ISO 8601 string | Yes | Valid instant |
| `metadata` | object | No | Any key-value pairs |

### GET /events/{id}

Returns the event with the given `eventId`. Returns `404` if not found.

### GET /events?account={accountId}

Returns all events for the account, sorted by `eventTimestamp` ascending. Returns an empty array if the account has no events. Returns `400` if the `account` parameter is missing.

### GET /accounts/{accountId}/balance

Returns the net balance: `Σ CREDIT amounts − Σ DEBIT amounts`. Returns `{ "accountId": "...", "balance": 0 }` for accounts with no events.

---

## 4. Data Model

### Event Entity

| Column | Type | Notes |
|---|---|---|
| `id` | BIGINT (PK) | Auto-generated surrogate key |
| `event_id` | VARCHAR(255) | Unique; the external idempotency key |
| `account_id` | VARCHAR(255) | Not null |
| `type` | VARCHAR(10) | `CREDIT` or `DEBIT` |
| `amount` | DECIMAL(19,4) | Must be > 0 |
| `currency` | VARCHAR(10) | e.g. `USD` |
| `event_timestamp` | TIMESTAMP | When the event occurred (provided by caller) |
| `metadata` | TEXT | JSON blob; optional |
| `created_at` | TIMESTAMP | When this API received the event |

**Key design point:** `event_timestamp` (business time) and `created_at` (arrival time) are stored independently. All sorting and ordering uses `event_timestamp`, so arrival order is irrelevant.

### Uniqueness Constraint

A `UNIQUE` constraint is declared on `event_id` at the database level (in addition to the application-layer check), ensuring no duplicates can persist even under concurrent submissions.

---

## 5. Idempotency Approach

The idempotency strategy has two layers:

**Layer 1 — Application check:**
Before every write, the service calls `findByEventId`. If a record exists, it is returned immediately with no write attempted. This handles the common case of sequential duplicate submissions.

**Layer 2 — Database constraint:**
The `event_id` column has a `UNIQUE` constraint. If two concurrent requests both pass the application-layer check, only one insert succeeds. The losing request catches `DataIntegrityViolationException` and resolves by reading the record the winning request wrote. This makes the operation safe for concurrent callers.

The `POST /events` endpoint returns `201 Created` for genuinely new events and `200 OK` for duplicates, allowing callers to distinguish the two outcomes.

---

## 6. Out-of-Order Event Handling

Events carry an `eventTimestamp` representing when the event occurred in the source system. This value is independent of the time the API receives the event (`created_at`).

All read operations that return ordered or aggregated data use `eventTimestamp`:

- `GET /events?account=` queries with `ORDER BY event_timestamp ASC`
- The balance calculation iterates over all events regardless of their arrival order

No special reprocessing, reordering queues, or streaming infrastructure is needed. The approach is correct by construction because the business value (`eventTimestamp`) is stored and queried independently from the insertion sequence.

---

## 7. Balance Calculation

```
balance = Σ(amount WHERE type = CREDIT) − Σ(amount WHERE type = DEBIT)
```

Implemented in `EventService.getBalance` as an in-memory stream reduction over all events for the account. `BigDecimal` is used throughout to avoid floating-point rounding errors. The balance is computed on demand; there is no cached or materialised balance column.

---

## 8. Validation and Error Handling

### Input Validation

All validation is declared via Jakarta Bean Validation annotations on `EventRequest`:

```
@NotBlank          → eventId, accountId, currency, type
@Pattern(CREDIT|DEBIT) → type
@NotNull           → amount, eventTimestamp
@DecimalMin(0, exclusive) → amount
```

When validation fails, Spring raises `MethodArgumentNotValidException` which the `GlobalExceptionHandler` converts to:

```json
{
  "message": "Validation failed",
  "errors": ["amount must be greater than 0", "type must be CREDIT or DEBIT"]
}
```

### Exception Mapping

| Exception | HTTP Status | Cause |
|---|---|---|
| `MethodArgumentNotValidException` | 400 | Bean Validation failure |
| `HttpMessageNotReadableException` | 400 | Malformed body or unparseable timestamp |
| `MissingServletRequestParameterException` | 400 | Missing `?account=` parameter |
| `EventNotFoundException` | 404 | Unknown `eventId` |
| Uncaught `Exception` | 500 | Unexpected server error |

---

## 9. Logging and Auditing

Structured audit-level logging is emitted by `EventService` via SLF4J / Logback (Spring Boot's default logging framework). No separate audit database table is used, keeping the design simple while still providing a complete operational trail.

| Operation | Log Level | Message |
|---|---|---|
| New event created | `INFO` | `[AUDIT] Event created — eventId, accountId, type, amount, currency` |
| Duplicate received (sequential) | `INFO` | `[AUDIT] Idempotent duplicate received — eventId, accountId` |
| Duplicate received (concurrent race) | `WARN` | `[AUDIT] Concurrent duplicate detected — eventId` |
| Event retrieved by ID | `DEBUG` | `[AUDIT] Event retrieved — eventId, accountId` |
| Event listing retrieved | `DEBUG` | `[AUDIT] Event listing retrieved — accountId, count` |
| Balance computed | `INFO` | `[AUDIT] Balance computed — accountId, balance, eventCount` |

All audit entries are prefixed with `[AUDIT]` for easy log filtering in production.

---

## 10. Tradeoffs and Future Improvements

| Area | Current Approach | Production Alternative |
|---|---|---|
| Database | H2 in-memory (resets on restart) | PostgreSQL with Flyway migrations |
| Idempotency | App-layer check + DB unique constraint | Distributed lock (Redis) for strict multi-replica guarantees |
| Balance | Computed on every request | Materialised running-balance updated transactionally on each write |
| Pagination | Not implemented | Keyset pagination on `(eventTimestamp, eventId)` |
| Audit trail | SLF4J log entries | Dedicated `event_audit` table or event-sourcing log |
| Currency | Free-text string | ISO 4217 enum with forex-aware balance per currency |
| Concurrency | Single-node safe via DB constraint | Optimistic locking or compare-and-swap for multi-replica |
| Observability | Default Spring Boot logging | Structured JSON logs + Micrometer metrics + distributed tracing |
