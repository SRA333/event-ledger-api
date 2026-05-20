package com.eventledger.service;

import com.eventledger.dto.BalanceResponse;
import com.eventledger.dto.CreateEventResult;
import com.eventledger.dto.EventRequest;
import com.eventledger.dto.EventResponse;
import com.eventledger.entity.Event;
import com.eventledger.entity.EventType;
import com.eventledger.exception.EventNotFoundException;
import com.eventledger.repository.EventRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
public class EventService {

    private final EventRepository eventRepository;

    public EventService(EventRepository eventRepository) {
        this.eventRepository = eventRepository;
    }

    public CreateEventResult createEvent(EventRequest request) {
        Optional<Event> existing = eventRepository.findByEventId(request.getEventId());
        if (existing.isPresent()) {
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
            return new CreateEventResult(EventResponse.from(saved), true);
        } catch (DataIntegrityViolationException e) {
            // A concurrent request with the same eventId won the race — return that event.
            Event concurrent = eventRepository.findByEventId(request.getEventId())
                    .orElseThrow(() -> new RuntimeException("Unexpected state after duplicate key violation", e));
            return new CreateEventResult(EventResponse.from(concurrent), false);
        }
    }

    public EventResponse getEventById(String eventId) {
        return eventRepository.findByEventId(eventId)
                .map(EventResponse::from)
                .orElseThrow(() -> new EventNotFoundException(eventId));
    }

    public List<EventResponse> getEventsByAccount(String accountId) {
        return eventRepository.findByAccountIdOrderByEventTimestampAsc(accountId).stream()
                .map(EventResponse::from)
                .toList();
    }

    public BalanceResponse getBalance(String accountId) {
        List<Event> events = eventRepository.findByAccountIdOrderByEventTimestampAsc(accountId);
        BigDecimal balance = events.stream()
                .map(e -> e.getType() == EventType.CREDIT
                        ? e.getAmount()
                        : e.getAmount().negate())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new BalanceResponse(accountId, balance);
    }
}
