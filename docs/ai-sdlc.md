# AI-Assisted SDLC Deliverables

This document describes how AI-assisted engineering practices were applied across the software development lifecycle for the Event Ledger API.

---

## Overview

AI tooling was used as a collaborative engineering partner throughout the project — not as a code generator to be accepted uncritically, but as a tool for accelerating design analysis, implementation, and quality assurance. Every decision in this project was reviewed, validated, and is fully explainable by the engineer.

The three agent roles below map to the three phases of AI-assisted SDLC requested in the evaluation brief.

---

## Design Agent

### Requirement Analysis

The requirement document was analysed to extract:

- The two core reliability challenges: duplicate delivery and out-of-order delivery
- The four required endpoints and their expected behaviours
- The idempotency contract: first write wins, duplicates return the original event
- The balance formula: `Σ CREDIT − Σ DEBIT`
- The validation rules per field

This analysis shaped the sequence of design decisions before any code was written.

### Design Document

A full design document was produced covering the problem summary, requirements, API design, data model, idempotency strategy, out-of-order handling, balance calculation, validation/error mapping, and logging approach.

→ [`docs/design.md`](design.md)

### Architecture Reasoning

The layered architecture (Controller → Service → Repository → H2) was selected for:

- **Simplicity**: appropriate for a take-home scope; no messaging or caching needed
- **Testability**: the service layer can be tested through MockMvc integration tests without mocking individual layers
- **Explainability**: each class has one clear responsibility that is easy to describe in an interview

The decision to use a `UNIQUE` database constraint as a second idempotency layer (in addition to the application-layer `findByEventId` check) was driven by the need to handle concurrent duplicate submissions correctly — a scenario the `DataIntegrityViolationException` catch block addresses.

### Diagrams

A Mermaid flowchart was produced showing all layers, cross-cutting concerns (validation, error handling, audit logging), and the data flow for the four key request scenarios.

→ [`docs/architecture.md`](architecture.md)

---

## Development Agent

### Implementation Support

AI-assisted development was used to:

- Scaffold the Maven project structure and `pom.xml` dependency set
- Draft the entity, repository, service, and controller layers in a consistent style
- Identify and resolve a naming collision in the test class between a local helper method and a static import of `MockMvcRequestBuilders.post`
- Select the correct Bean Validation annotations (`@DecimalMin(value = "0", inclusive = false)` for exclusive lower bound; `@Pattern(regexp = "^(CREDIT|DEBIT)$")` to validate enum values without a custom constraint)

### Error Handling

Structured error handling is centralised in `GlobalExceptionHandler` (`@RestControllerAdvice`). It covers:

| Exception | Status | Response format |
|---|---|---|
| `MethodArgumentNotValidException` | 400 | `{ "message": "Validation failed", "errors": [...] }` |
| `HttpMessageNotReadableException` | 400 | Specific message for bad timestamp format |
| `MissingServletRequestParameterException` | 400 | Names the missing parameter |
| `EventNotFoundException` | 404 | Includes the unknown event ID |
| Uncaught `Exception` | 500 | Generic message; hides internal detail |

`@JsonInclude(NON_NULL)` on `ErrorResponse` ensures the `errors` array is omitted from responses where it is not applicable (e.g. 404, 500).

### Logging and Auditing

`EventService` emits structured `[AUDIT]`-prefixed log entries via SLF4J for every significant operation:

- New event creation (`INFO`)
- Idempotent duplicate received (`INFO`)
- Concurrent duplicate race resolved (`WARN`)
- Event retrieval and listing (`DEBUG`)
- Balance computation (`INFO`)

This provides a full operational trail without requiring a separate audit table, which would have been overengineering for this scope.

### Meaningful Commit History

The commit history was structured to reflect the actual development process, with each commit corresponding to a logical milestone:

| Commit | Description |
|---|---|
| `Initialize Spring Boot project` | pom.xml, main class, application properties |
| `Add event domain model and persistence` | Entity, enum, converter, repository |
| `Implement event APIs, balance endpoint, and validation` | Service, controllers, DTOs, exception handler |
| `Add automated integration tests` | 19 integration tests via MockMvc |
| `Add README, Dockerfile, and final polish` | Documentation, Docker support |
| `Ignore .claude/ directory` | Housekeeping: git tracking cleanup |
| `Address final review cleanup` | Remove dead code, suppress deprecation warning |
| `Add AI-assisted SDLC documentation` | This set of docs, JaCoCo, audit logging |

---

## QA Agent

### Test Scenario Generation

Test scenarios were derived directly from the requirements document. Every requirement has at least one corresponding test:

| Requirement | Test(s) |
|---|---|
| Successful event creation | `createEvent_newEvent_returns201WithBody` |
| Get event by ID | `getEvent_existingId_returns200` |
| Unknown event ID returns 404 | `getEvent_unknownId_returns404` |
| Duplicate returns original, no new record | `createEvent_duplicate_returns200WithOriginalEvent` |
| Duplicate does not alter balance | `createEvent_duplicateDoesNotAlterBalance` |
| Out-of-order listing | `getEventsByAccount_outOfOrderArrival_returnsChronologicalOrder` |
| Balance: CREDIT and DEBIT | `getBalance_multipleCreditsAndDebits_returnsCorrectNet` |
| Balance: no events → 0 | `getBalance_noEvents_returnsZero` |
| Balance: out-of-order arrival | `getBalance_outOfOrderArrival_stillCorrect` |
| Missing each required field | Five individual `missingX_returns400` tests |
| Amount ≤ 0 | `createEvent_amountZero_returns400`, `createEvent_negativeAmount_returns400` |
| Invalid type | `createEvent_invalidType_returns400`, `createEvent_missingType_returns400` |
| Missing `?account=` param | `getEvents_missingAccountParam_returns400` |
| Unknown account → empty list | `getEventsByAccount_noEvents_returnsEmptyList` |

### Automated Tests

19 integration tests in `EventLedgerIntegrationTest` use `@SpringBootTest` + `MockMvc` to exercise the full Spring context against an H2 database. The database is cleared with `deleteAll()` before every test, ensuring full isolation.

→ `src/test/java/com/eventledger/EventLedgerIntegrationTest.java`

### Manual Validation via Swagger UI

The application exposes Swagger UI at `http://localhost:8080/swagger-ui.html` after `mvn spring-boot:run`. Manual functional testing was performed against all four endpoints using the interactive UI, covering the same scenarios as the automated tests.

→ See [`docs/testing-report.md`](testing-report.md) for the full manual test scenario list.

### Coverage Reporting

JaCoCo is configured in `pom.xml` to generate an HTML coverage report automatically when `mvn test` runs.

Report location after `mvn test`: `target/site/jacoco/index.html`

Given that the 19 integration tests exercise the full Spring stack, the report reflects coverage of all service logic, controllers, exception handlers, and the metadata converter.

→ [`docs/testing-report.md`](testing-report.md)
