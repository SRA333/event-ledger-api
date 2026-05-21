package com.eventledger.service;

import com.eventledger.dto.BalanceResponse;
import com.eventledger.dto.CreateEventResult;
import com.eventledger.dto.EventRequest;
import com.eventledger.dto.EventResponse;
import com.eventledger.entity.Event;
import com.eventledger.entity.EventType;
import com.eventledger.exception.EventNotFoundException;
import com.eventledger.repository.EventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
public class EventService {

    private static final Logger log = LoggerFactory.getLogger(EventService.class);

    private final EventRepository eventRepository;

    public EventService(EventRepository eventRepository) {
        this.eventRepository = eventRepository;
    }

    public CreateEventResult createEvent(EventRequest request) {
        Optional<Event> existing = eventRepository.findByEventId(request.getEventId());
        if (existing.isPresent()) {
            log.info("[AUDIT] Idempotent duplicate received — eventId={}, accountId={}, returning existing event",
                    request.getEventId(), request.getAccountId());
            return new CreateEventResult(EventResponse.from(existing.get()), false);
        }

        Event event = new Event();
        event.setEventId(request.getEventId());
        event.setAccountId(request.getAccountId());
        event.setType(EventType.valueOf(request.getType()));
        event.setAmount(request.getAmount());
        event.setCurrency(request.getCurrency());
        event.setEventTimestamp(request.getEventTimestamp());
        event.setMetadata(request.getMetadata());
        event.setCreatedAt(Instant.now());

        try {
            Event saved = eventRepository.save(event);
            log.info("[AUDIT] Event created — eventId={}, accountId={}, type={}, amount={}, currency={}",
                    saved.getEventId(), saved.getAccountId(), saved.getType(), saved.getAmount(), saved.getCurrency());
            return new CreateEventResult(EventResponse.from(saved), true);
        } catch (DataIntegrityViolationException e) {
            // A concurrent request with the same eventId won the race — return that event.
            log.warn("[AUDIT] Concurrent duplicate detected for eventId={} — returning persisted event",
                    request.getEventId());
            Event concurrent = eventRepository.findByEventId(request.getEventId())
                    .orElseThrow(() -> new RuntimeException("Unexpected state after duplicate key violation", e));
            return new CreateEventResult(EventResponse.from(concurrent), false);
        }
    }

    public EventResponse getEventById(String eventId) {
        EventResponse response = eventRepository.findByEventId(eventId)
                .map(EventResponse::from)
                .orElseThrow(() -> new EventNotFoundException(eventId));
        log.debug("[AUDIT] Event retrieved — eventId={}, accountId={}", response.getEventId(), response.getAccountId());
        return response;
    }

    public List<EventResponse> getEventsByAccount(String accountId) {
        List<EventResponse> events = eventRepository.findByAccountIdOrderByEventTimestampAsc(accountId).stream()
                .map(EventResponse::from)
                .toList();
        log.debug("[AUDIT] Event listing retrieved — accountId={}, count={}", accountId, events.size());
        return events;
    }

    public BalanceResponse getBalance(String accountId) {
        List<Event> events = eventRepository.findByAccountIdOrderByEventTimestampAsc(accountId);
        BigDecimal balance = events.stream()
                .map(e -> e.getType() == EventType.CREDIT
                        ? e.getAmount()
                        : e.getAmount().negate())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        log.info("[AUDIT] Balance computed — accountId={}, balance={}, eventCount={}", accountId, balance, events.size());
        return new BalanceResponse(accountId, balance);
    }
}
