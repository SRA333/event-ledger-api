# Event Ledger API

A financial transaction ledger that accepts events from multiple upstream systems. Designed to handle **duplicate submissions** (idempotency) and **out-of-order event arrival** correctly.

---

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Java 17 |
| Framework | Spring Boot 3.2.5 |
| Build | Maven 3.9 |
| Database | H2 (in-memory) |
| ORM | Spring Data JPA / Hibernate |
| Validation | Jakarta Bean Validation |
| API Docs | springdoc-openapi (Swagger UI) |
| Testing | JUnit 5, MockMvc, Mockito, JaCoCo |

---

## Design Decisions

### Idempotency

`eventId` is the unique idempotency key. The strategy is:

1. On every `POST /events`, the service queries `findByEventId` first.
2. If the event already exists, it returns the stored event immediately — no write is attempted.
3. If it does not exist, the event is saved. A `UNIQUE` database constraint on `event_id` acts as a safety net for concurrent duplicate submissions that slip past the application-layer check. The `DataIntegrityViolationException` is caught and the stored event is returned instead of propagating an error.

This means the first write wins and all subsequent identical submissions are harmless.

### Out-of-Order Event Handling

Events carry their own `eventTimestamp` (when the event actually occurred) which is stored separately from `createdAt` (when the API received it). The listing endpoint always queries with `ORDER BY event_timestamp ASC`, so the return order is always chronological regardless of arrival order. The balance calculation iterates over all events for the account, so it is likewise unaffected by arrival order.

### Balance Calculation

```
balance = Σ(CREDIT amounts) − Σ(DEBIT amounts)
```

Computed in the service layer by streaming all events for the account. An account with no events returns a balance of `0`. There is no separate `Account` entity — the balance is derived on-demand from the event log.

### Validation and Error Handling

All input validation is handled by Jakarta Bean Validation annotations on `EventRequest`:

| Field | Constraint |
|---|---|
| `eventId` | `@NotBlank` |
| `accountId` | `@NotBlank` |
| `type` | `@NotBlank` + `@Pattern(CREDIT\|DEBIT)` |
| `amount` | `@NotNull` + `@DecimalMin(0, exclusive)` |
| `currency` | `@NotBlank` |
| `eventTimestamp` | `@NotNull` (ISO 8601 via Jackson `Instant`) |

A `@RestControllerAdvice` (`GlobalExceptionHandler`) translates all exceptions to structured JSON responses:

- `MethodArgumentNotValidException` → `400` with a list of field error messages
- `HttpMessageNotReadableException` → `400` (malformed body or invalid timestamp format)
- `MissingServletRequestParameterException` → `400` (missing `?account=`)
- `EventNotFoundException` → `404`
- Uncaught exceptions → `500`

---

## Prerequisites

- **Java 17+** (`java -version`)
- **Maven 3.8+** (`mvn -version`)  
  *Alternatively, use the system Maven if available; no wrapper is bundled.*

---

## Running the Application

```bash
mvn spring-boot:run
```

The API starts at `http://localhost:8080`.

Swagger UI is available at: `http://localhost:8080/swagger-ui.html`

H2 console is available at: `http://localhost:8080/h2-console`  
JDBC URL: `jdbc:h2:mem:eventledger` · User: `sa` · Password: *(empty)*

---

## Running the Tests

```bash
mvn test
```

Runs all 26 tests and generates a JaCoCo coverage report at `target/site/jacoco/index.html`.

| Test class | Type | Count |
|---|---|---|
| `EventServiceTest` | Unit — Mockito, no Spring context | 7 |
| `EventLedgerIntegrationTest` | Integration — `@SpringBootTest` + MockMvc + H2 | 19 |

The integration tests clear the database with `deleteAll()` before every test.

---

## API Endpoints

### POST /events — Submit a transaction event

```bash
curl -s -X POST http://localhost:8080/events \
  -H "Content-Type: application/json" \
  -d '{
    "eventId": "evt-001",
    "accountId": "acct-123",
    "type": "CREDIT",
    "amount": 150.00,
    "currency": "USD",
    "eventTimestamp": "2026-05-15T14:02:11Z",
    "metadata": {"source": "mainframe-batch", "batchId": "B-9042"}
  }'
```

**201 Created** for a new event. **200 OK** for a duplicate (returns the original stored event).

---

### GET /events/{id} — Retrieve a single event

```bash
curl -s http://localhost:8080/events/evt-001
```

**200 OK** with the event body. **404 Not Found** if unknown.

---

### GET /events?account={accountId} — List events for an account

```bash
curl -s "http://localhost:8080/events?account=acct-123"
```

Returns all events for the account ordered by `eventTimestamp` ascending (chronological). Returns an empty array if the account has no events. Returns **400** if the `account` parameter is missing.

---

### GET /accounts/{accountId}/balance — Get account balance

```bash
curl -s http://localhost:8080/accounts/acct-123/balance
```

Returns:
```json
{
  "accountId": "acct-123",
  "balance": 150.00
}
```

Returns `0` balance for accounts with no events.

---

### Validation error example

```bash
curl -s -X POST http://localhost:8080/events \
  -H "Content-Type: application/json" \
  -d '{"eventId":"x","accountId":"y","type":"TRANSFER","amount":-5,"currency":"USD","eventTimestamp":"2026-05-15T10:00:00Z"}'
```

```json
{
  "message": "Validation failed",
  "errors": [
    "amount must be greater than 0",
    "type must be CREDIT or DEBIT"
  ]
}
```

---

## Docker

Build the JAR first, then build and run the image:

```bash
mvn package -DskipTests
docker build -t event-ledger-api .
docker run -p 8080:8080 event-ledger-api
```

---

## Tradeoffs and Possible Improvements

| Area | Current Approach | Production Alternative |
|---|---|---|
| Database | H2 in-memory (data lost on restart) | PostgreSQL with Flyway migrations |
| Idempotency | App-layer check + DB unique constraint | Distributed lock (Redis) for strict guarantees across replicas |
| Balance | Computed on every request | Materialised running-balance column, updated transactionally |
| Pagination | Not implemented | Keyset pagination on `eventTimestamp` + `eventId` |
| Currency | Stored as a free-text string | Enum or ISO 4217 validation |
| Concurrency | Single-node safe via DB constraint | Optimistic locking or event-store CAS for multi-replica |
| Observability | SLF4J audit logging | Structured JSON logs, metrics (Micrometer), distributed tracing |

---

## AI-Assisted SDLC Deliverables

This project was built with AI-assisted engineering practices across the full SDLC. The following documents cover each phase:

| Document | Description |
|---|---|
| [`docs/design.md`](docs/design.md) | Problem summary, requirements, API design, data model, idempotency, balance calculation, validation, logging |
| [`docs/architecture.md`](docs/architecture.md) | Layered architecture explanation and Mermaid diagram with request flow traces |
| [`docs/ai-sdlc.md`](docs/ai-sdlc.md) | How AI assistance was applied across Design, Development, and QA phases |
| [`docs/testing-report.md`](docs/testing-report.md) | Full test suite listing, functional test coverage, end-to-end scenarios, JaCoCo coverage report guide |

**Coverage report:** run `mvn test`, then open `target/site/jacoco/index.html`.

---

## Evaluation Checklist

| Requested Item | Where it is covered |
|---|---|
| **Design Agent** | |
| Design document | [`docs/design.md`](docs/design.md) |
| Architecture / design diagram | [`docs/architecture.md`](docs/architecture.md) — Mermaid flowchart |
| **Development Agent** | |
| Error handling | `GlobalExceptionHandler` — 400 / 404 / 500 with structured JSON |
| Logging and auditing | `EventService` — `[AUDIT]`-prefixed SLF4J entries on every operation |
| Meaningful Git commits | Meaningful commit history; see `git log --oneline` |
| **QA Agent** | |
| Unit tests | `EventServiceTest` — 7 Mockito tests, service layer in isolation |
| Integration tests | `EventLedgerIntegrationTest` — 19 MockMvc tests, all passing |
| Test coverage report | JaCoCo — `target/site/jacoco/index.html` after `mvn test` |
| Functional test coverage | [`docs/testing-report.md`](docs/testing-report.md) — 14 manual scenarios, all passing |
