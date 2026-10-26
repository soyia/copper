package com.soyia.copperv2.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;

public final class SalesOrderPrepaymentRequests {
    private SalesOrderPrepaymentRequests() {
    }

    public record Receive(
            @NotNull @DecimalMin("0.01") BigDecimal amount,
            @NotBlank String paymentMethod,
            Long businessBankAccountId,
            @NotNull LocalDate receivedDate,
            String memo
    ) {
    }

    public record Reserve(
            @NotNull @DecimalMin("0.01") BigDecimal amount,
            String memo
    ) {
    }

    public record Refund(
            @NotNull Long customerId,
            @NotNull @DecimalMin("0.01") BigDecimal amount,
            @NotBlank String refundMethod,
            Long businessBankAccountId,
            @NotNull LocalDate refundedDate,
            String memo
    ) {
    }
}
