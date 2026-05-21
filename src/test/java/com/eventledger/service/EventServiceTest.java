package com.eventledger.service;

import com.eventledger.dto.BalanceResponse;
import com.eventledger.dto.CreateEventResult;
import com.eventledger.dto.EventRequest;
import com.eventledger.dto.EventResponse;
import com.eventledger.entity.Event;
import com.eventledger.entity.EventType;
import com.eventledger.exception.EventNotFoundException;
import com.eventledger.repository.EventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EventServiceTest {

    @Mock
    private EventRepository eventRepository;

    @InjectMocks
    private EventService eventService;

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private EventRequest buildRequest(String eventId, String accountId, String type, double amount) {
        EventRequest req = new EventRequest();
        req.setEventId(eventId);
        req.setAccountId(accountId);
        req.setType(type);
        req.setAmount(BigDecimal.valueOf(amount));
        req.setCurrency("USD");
        req.setEventTimestamp(Instant.parse("2026-05-15T10:00:00Z"));
        return req;
    }

    private Event buildEntity(String eventId, String accountId, EventType type, double amount) {
        Event e = new Event();
        e.setEventId(eventId);
        e.setAccountId(accountId);
        e.setType(type);
        e.setAmount(BigDecimal.valueOf(amount));
        e.setCurrency("USD");
        e.setEventTimestamp(Instant.parse("2026-05-15T10:00:00Z"));
        e.setCreatedAt(Instant.now());
        return e;
    }

    // ---------------------------------------------------------------------------
    // createEvent — new event
    // ---------------------------------------------------------------------------

    @Test
    void createEvent_newEvent_savesAndReturnsIsCreatedTrue() {
        EventRequest req = buildRequest("evt-new", "acct-1", "CREDIT", 150.0);
        Event saved = buildEntity("evt-new", "acct-1", EventType.CREDIT, 150.0);

        when(eventRepository.findByEventId("evt-new")).thenReturn(Optional.empty());
        when(eventRepository.save(any(Event.class))).thenReturn(saved);

        CreateEventResult result = eventService.createEvent(req);

        assertTrue(result.isCreated());
        assertEquals("evt-new", result.getEvent().getEventId());
        assertEquals(0, BigDecimal.valueOf(150.0).compareTo(result.getEvent().getAmount()));
        verify(eventRepository).save(any(Event.class));
    }

    // ---------------------------------------------------------------------------
    // createEvent — duplicate (sequential)
    // ---------------------------------------------------------------------------

    @Test
    void createEvent_duplicateEventId_returnsExistingEventWithIsCreatedFalse() {
        EventRequest req = buildRequest("evt-dup", "acct-1", "CREDIT", 200.0);
        Event existing = buildEntity("evt-dup", "acct-1", EventType.CREDIT, 200.0);

        when(eventRepository.findByEventId("evt-dup")).thenReturn(Optional.of(existing));

        CreateEventResult result = eventService.createEvent(req);

        assertFalse(result.isCreated());
        assertEquals("evt-dup", result.getEvent().getEventId());
        verify(eventRepository, never()).save(any());
    }

    // ---------------------------------------------------------------------------
    // createEvent — concurrent duplicate (DataIntegrityViolationException path)
    // ---------------------------------------------------------------------------

    @Test
    void createEvent_concurrentDuplicate_recoversAndReturnsExistingEvent() {
        EventRequest req = buildRequest("evt-race", "acct-1", "CREDIT", 50.0);
        Event existing = buildEntity("evt-race", "acct-1", EventType.CREDIT, 50.0);

        // First findByEventId (before save) returns empty; second (after exception) returns the winner's row
        when(eventRepository.findByEventId("evt-race"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(existing));
        when(eventRepository.save(any(Event.class)))
                .thenThrow(new DataIntegrityViolationException("unique constraint violation"));

        CreateEventResult result = eventService.createEvent(req);

        assertFalse(result.isCreated());
        assertEquals("evt-race", result.getEvent().getEventId());
    }

    // ---------------------------------------------------------------------------
    // getEventById
    // ---------------------------------------------------------------------------

    @Test
    void getEventById_found_returnsCorrectEventResponse() {
        Event event = buildEntity("evt-get", "acct-2", EventType.DEBIT, 75.0);
        when(eventRepository.findByEventId("evt-get")).thenReturn(Optional.of(event));

        EventResponse response = eventService.getEventById("evt-get");

        assertEquals("evt-get", response.getEventId());
        assertEquals(EventType.DEBIT, response.getType());
        assertEquals(0, BigDecimal.valueOf(75.0).compareTo(response.getAmount()));
    }

    @Test
    void getEventById_notFound_throwsEventNotFoundException() {
        when(eventRepository.findByEventId("missing")).thenReturn(Optional.empty());

        assertThrows(EventNotFoundException.class, () -> eventService.getEventById("missing"));
    }

    // ---------------------------------------------------------------------------
    // getBalance
    // ---------------------------------------------------------------------------

    @Test
    void getBalance_multipleCreditsAndDebits_returnsCorrectNetBalance() {
        List<Event> events = List.of(
                buildEntity("e1", "acct-3", EventType.CREDIT, 300.0),
                buildEntity("e2", "acct-3", EventType.CREDIT, 100.0),
                buildEntity("e3", "acct-3", EventType.DEBIT,   80.0)
        );
        when(eventRepository.findByAccountIdOrderByEventTimestampAsc("acct-3")).thenReturn(events);

        BalanceResponse response = eventService.getBalance("acct-3");

        assertEquals("acct-3", response.getAccountId());
        assertEquals(0, BigDecimal.valueOf(320.0).compareTo(response.getBalance())); // 300 + 100 - 80
    }

    // ---------------------------------------------------------------------------
    // getEventsByAccount
    // ---------------------------------------------------------------------------

    @Test
    void getEventsByAccount_delegatesOrderingToRepository() {
        List<Event> ordered = List.of(
                buildEntity("evt-early", "acct-4", EventType.CREDIT, 10.0),
                buildEntity("evt-late",  "acct-4", EventType.DEBIT,  20.0)
        );
        when(eventRepository.findByAccountIdOrderByEventTimestampAsc("acct-4")).thenReturn(ordered);

        List<EventResponse> result = eventService.getEventsByAccount("acct-4");

        assertEquals(2, result.size());
        assertEquals("evt-early", result.get(0).getEventId());
        assertEquals("evt-late",  result.get(1).getEventId());
        verify(eventRepository).findByAccountIdOrderByEventTimestampAsc("acct-4");
    }
}
