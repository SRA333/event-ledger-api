package com.eventledger.controller;

import com.eventledger.dto.BalanceResponse;
import com.eventledger.service.EventService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/accounts")
public class AccountController {

    private final EventService eventService;

    public AccountController(EventService eventService) {
        this.eventService = eventService;
    }

    @GetMapping("/{accountId}/balance")
    public BalanceResponse getBalance(@PathVariable String accountId) {
        return eventService.getBalance(accountId);
    }
}
