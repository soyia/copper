package com.soyia.copperv2.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record PaymentCreateRequest(
        @NotNull Long customerId,
        Long salesOrderId,
        Long outboundId,
        String outboundNo,
        @NotNull @DecimalMin("0.01") BigDecimal amount,
        @NotBlank String paymentMethod,
        Long businessBankAccountId,
        @NotNull LocalDate receivedDate,
        String memo,
        @Valid List<PaymentAllocationDtos.AllocationInput> allocations
) {
    public PaymentCreateRequest(Long customerId, Long salesOrderId, Long outboundId, String outboundNo,
                                BigDecimal amount, String paymentMethod, Long businessBankAccountId,
                                LocalDate receivedDate, String memo) {
        this(customerId, salesOrderId, outboundId, outboundNo, amount, paymentMethod,
                businessBankAccountId, receivedDate, memo, null);
    }
}
