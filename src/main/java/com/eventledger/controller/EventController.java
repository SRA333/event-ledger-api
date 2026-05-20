package com.eventledger.controller;

import com.eventledger.dto.CreateEventResult;
import com.eventledger.dto.EventRequest;
import com.eventledger.dto.EventResponse;
import com.eventledger.service.EventService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/events")
public class EventController {

    private final EventService eventService;

    public EventController(EventService eventService) {
        this.eventService = eventService;
    }

    @PostMapping
    public ResponseEntity<EventResponse> createEvent(@Valid @RequestBody EventRequest request) {
        CreateEventResult result = eventService.createEvent(request);
        HttpStatus status = result.isCreated() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(result.getEvent());
    }

    @GetMapping("/{id}")
    public EventResponse getEvent(@PathVariable("id") String eventId) {
        return eventService.getEventById(eventId);
    }

    @GetMapping
    public List<EventResponse> getEventsByAccount(@RequestParam("account") String accountId) {
        return eventService.getEventsByAccount(accountId);
    }
}
