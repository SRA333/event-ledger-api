package com.eventledger.dto;

import java.math.BigDecimal;

public class BalanceResponse {

    private final String accountId;
    private final BigDecimal balance;

    public BalanceResponse(String accountId, BigDecimal balance) {
        this.accountId = accountId;
        this.balance = balance;
    }

    public String getAccountId() { return accountId; }
    public BigDecimal getBalance() { return balance; }
}
