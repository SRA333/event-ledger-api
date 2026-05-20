package com.eventledger.dto;

import com.eventledger.entity.Event;
import com.eventledger.entity.EventType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

public class EventResponse {

    private String eventId;
    private String accountId;
    private EventType type;
    private BigDecimal amount;
    private String currency;
    private Instant eventTimestamp;
    private Map<String, Object> metadata;

    public static EventResponse from(Event event) {
        EventResponse r = new EventResponse();
        r.eventId = event.getEventId();
        r.accountId = event.getAccountId();
        r.type = event.getType();
        r.amount = event.getAmount();
        r.currency = event.getCurrency();
        r.eventTimestamp = event.getEventTimestamp();
        r.metadata = event.getMetadata();
        return r;
    }

    public String getEventId() { return eventId; }
    public String getAccountId() { return accountId; }
    public EventType getType() { return type; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public Instant getEventTimestamp() { return eventTimestamp; }
    public Map<String, Object> getMetadata() { return metadata; }
}
