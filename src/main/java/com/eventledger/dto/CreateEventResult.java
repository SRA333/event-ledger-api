package com.eventledger.dto;

public class CreateEventResult {

    private final EventResponse event;
    private final boolean created;

    public CreateEventResult(EventResponse event, boolean created) {
        this.event = event;
        this.created = created;
    }

    public EventResponse getEvent() { return event; }
    public boolean isCreated() { return created; }
}
