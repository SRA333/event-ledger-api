package com.eventledger;

import com.eventledger.repository.EventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class EventLedgerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EventRepository eventRepository;

    @BeforeEach
    void setUp() {
        eventRepository.deleteAll();
    }

    // --- Helpers ---

    private void submitEvent(String body) throws Exception {
        mockMvc.perform(post("/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    // --- Successful event creation ---

    @Test
    void createEvent_newEvent_returns201WithBody() throws Exception {
        String body = """
                {
                  "eventId": "evt-001",
                  "accountId": "acct-123",
                  "type": "CREDIT",
                  "amount": 150.00,
                  "currency": "USD",
                  "eventTimestamp": "2026-05-15T14:02:11Z",
                  "metadata": {"source": "mainframe-batch", "batchId": "B-9042"}
                }
                """;

        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.eventId").value("evt-001"))
                .andExpect(jsonPath("$.accountId").value("acct-123"))
                .andExpect(jsonPath("$.type").value("CREDIT"))
                .andExpect(jsonPath("$.amount").value(150.00))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.metadata.source").value("mainframe-batch"))
                .andExpect(jsonPath("$.metadata.batchId").value("B-9042"));
    }

    // --- Get event by id ---

    @Test
    void getEvent_existingId_returns200() throws Exception {
        submitEvent("""
                {"eventId":"evt-002","accountId":"acct-123","type":"DEBIT","amount":50.00,
                 "currency":"USD","eventTimestamp":"2026-05-15T10:00:00Z"}
                """);

        mockMvc.perform(get("/events/evt-002"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value("evt-002"))
                .andExpect(jsonPath("$.type").value("DEBIT"))
                .andExpect(jsonPath("$.amount").value(50.00));
    }

    @Test
    void getEvent_unknownId_returns404() throws Exception {
        mockMvc.perform(get("/events/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(containsString("does-not-exist")));
    }

    // --- Idempotency ---

    @Test
    void createEvent_duplicate_returns200WithOriginalEvent() throws Exception {
        String body = """
                {"eventId":"evt-dup","accountId":"acct-123","type":"CREDIT","amount":100.00,
                 "currency":"USD","eventTimestamp":"2026-05-15T10:00:00Z"}
                """;

        mockMvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value("evt-dup"));

        assertEquals(1, eventRepository.count(), "Duplicate submission must not create a second event");
    }

    @Test
    void createEvent_duplicateDoesNotAlterBalance() throws Exception {
        String credit = """
                {"eventId":"evt-idem","accountId":"acct-idem","type":"CREDIT","amount":200.00,
                 "currency":"USD","eventTimestamp":"2026-05-15T10:00:00Z"}
                """;

        submitEvent(credit);
        submitEvent(credit); // duplicate

        mockMvc.perform(get("/accounts/acct-idem/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(200.00));
    }

    // --- Out-of-order event handling ---

    @Test
    void getEventsByAccount_outOfOrderArrival_returnsChronologicalOrder() throws Exception {
        // Submit later-timestamp event first
        submitEvent("""
                {"eventId":"evt-later","accountId":"acct-order","type":"CREDIT","amount":10.00,
                 "currency":"USD","eventTimestamp":"2026-05-15T12:00:00Z"}
                """);
        // Submit earlier-timestamp event second
        submitEvent("""
                {"eventId":"evt-earlier","accountId":"acct-order","type":"CREDIT","amount":20.00,
                 "currency":"USD","eventTimestamp":"2026-05-15T09:00:00Z"}
                """);
        // Submit middle-timestamp event third
        submitEvent("""
                {"eventId":"evt-middle","accountId":"acct-order","type":"CREDIT","amount":15.00,
                 "currency":"USD","eventTimestamp":"2026-05-15T10:30:00Z"}
                """);

        mockMvc.perform(get("/events").param("account", "acct-order"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].eventId").value("evt-earlier"))
                .andExpect(jsonPath("$[1].eventId").value("evt-middle"))
                .andExpect(jsonPath("$[2].eventId").value("evt-later"));
    }

    // --- Balance computation ---

    @Test
    void getBalance_multipleCreditsAndDebits_returnsCorrectNet() throws Exception {
        submitEvent("""
                {"eventId":"evt-c1","accountId":"acct-bal","type":"CREDIT","amount":200.00,
                 "currency":"USD","eventTimestamp":"2026-05-15T10:00:00Z"}
                """);
        submitEvent("""
                {"eventId":"evt-c2","accountId":"acct-bal","type":"CREDIT","amount":100.00,
                 "currency":"USD","eventTimestamp":"2026-05-15T11:00:00Z"}
                """);
        submitEvent("""
                {"eventId":"evt-d1","accountId":"acct-bal","type":"DEBIT","amount":75.00,
                 "currency":"USD","eventTimestamp":"2026-05-15T12:00:00Z"}
                """);

        // balance = 200 + 100 - 75 = 225
        mockMvc.perform(get("/accounts/acct-bal/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value("acct-bal"))
                .andExpect(jsonPath("$.balance").value(225.00));
    }

    @Test
    void getBalance_noEvents_returnsZero() throws Exception {
        mockMvc.perform(get("/accounts/acct-empty/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(0));
    }

    @Test
    void getBalance_outOfOrderArrival_stillCorrect() throws Exception {
        // Debit arrives before its corresponding credits
        submitEvent("""
                {"eventId":"evt-d","accountId":"acct-oo-bal","type":"DEBIT","amount":50.00,
                 "currency":"USD","eventTimestamp":"2026-05-14T08:00:00Z"}
                """);
        submitEvent("""
                {"eventId":"evt-c","accountId":"acct-oo-bal","type":"CREDIT","amount":300.00,
                 "currency":"USD","eventTimestamp":"2026-05-13T08:00:00Z"}
                """);

        // balance = 300 - 50 = 250, regardless of arrival order
        mockMvc.perform(get("/accounts/acct-oo-bal/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(250.00));
    }

    // --- Validation: missing required fields ---

    @Test
    void createEvent_missingEventId_returns400() throws Exception {
        String body = """
                {"accountId":"acct-123","type":"CREDIT","amount":100.00,
                 "currency":"USD","eventTimestamp":"2026-05-15T10:00:00Z"}
                """;
        mockMvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*]", hasItem(containsString("eventId"))));
    }

    @Test
    void createEvent_missingAccountId_returns400() throws Exception {
        String body = """
                {"eventId":"evt-x","type":"CREDIT","amount":100.00,
                 "currency":"USD","eventTimestamp":"2026-05-15T10:00:00Z"}
                """;
        mockMvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*]", hasItem(containsString("accountId"))));
    }

    @Test
    void createEvent_missingCurrency_returns400() throws Exception {
        String body = """
                {"eventId":"evt-x","accountId":"acct-123","type":"CREDIT","amount":100.00,
                 "eventTimestamp":"2026-05-15T10:00:00Z"}
                """;
        mockMvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*]", hasItem(containsString("currency"))));
    }

    @Test
    void createEvent_missingTimestamp_returns400() throws Exception {
        String body = """
                {"eventId":"evt-x","accountId":"acct-123","type":"CREDIT","amount":100.00,"currency":"USD"}
                """;
        mockMvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*]", hasItem(containsString("eventTimestamp"))));
    }

    // --- Validation: amount ---

    @Test
    void createEvent_amountZero_returns400() throws Exception {
        String body = """
                {"eventId":"evt-x","accountId":"acct-123","type":"CREDIT","amount":0,
                 "currency":"USD","eventTimestamp":"2026-05-15T10:00:00Z"}
                """;
        mockMvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*]", hasItem(containsString("amount"))));
    }

    @Test
    void createEvent_negativeAmount_returns400() throws Exception {
        String body = """
                {"eventId":"evt-x","accountId":"acct-123","type":"CREDIT","amount":-10.00,
                 "currency":"USD","eventTimestamp":"2026-05-15T10:00:00Z"}
                """;
        mockMvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*]", hasItem(containsString("amount"))));
    }

    // --- Validation: type ---

    @Test
    void createEvent_invalidType_returns400() throws Exception {
        String body = """
                {"eventId":"evt-x","accountId":"acct-123","type":"TRANSFER","amount":100.00,
                 "currency":"USD","eventTimestamp":"2026-05-15T10:00:00Z"}
                """;
        mockMvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*]", hasItem(containsString("CREDIT"))));
    }

    @Test
    void createEvent_missingType_returns400() throws Exception {
        String body = """
                {"eventId":"evt-x","accountId":"acct-123","amount":100.00,
                 "currency":"USD","eventTimestamp":"2026-05-15T10:00:00Z"}
                """;
        mockMvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*]", hasItem(containsString("type"))));
    }

    // --- Validation: missing account query param ---

    @Test
    void getEvents_missingAccountParam_returns400() throws Exception {
        mockMvc.perform(get("/events"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("account")));
    }

    // --- Event listing for unknown account returns empty list ---

    @Test
    void getEventsByAccount_noEvents_returnsEmptyList() throws Exception {
        mockMvc.perform(get("/events").param("account", "acct-unknown"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
