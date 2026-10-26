package com.soyia.copperv2.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record SupplierPaymentCreateRequest(
        @NotNull Long supplierId,
        Long purchaseOrderId,
        Long inboundId,
        @NotNull @DecimalMin("0.01") BigDecimal amount,
        @NotBlank String paymentMethod,
        Long businessBankAccountId,
        @NotNull LocalDate paidDate,
        String memo,
        @Valid List<PaymentAllocationDtos.AllocationInput> allocations,
        String exceptionReason,
        Long riskApprovalId
) {
    public SupplierPaymentCreateRequest(Long supplierId, Long purchaseOrderId, Long inboundId,
                                        BigDecimal amount, String paymentMethod, Long businessBankAccountId,
                                        LocalDate paidDate, String memo) {
        this(supplierId, purchaseOrderId, inboundId, amount, paymentMethod,
                businessBankAccountId, paidDate, memo, null, null, null);
    }

    public SupplierPaymentCreateRequest(Long supplierId, Long purchaseOrderId, Long inboundId,
                                        BigDecimal amount, String paymentMethod, Long businessBankAccountId,
                                        LocalDate paidDate, String memo,
                                        List<PaymentAllocationDtos.AllocationInput> allocations) {
        this(supplierId, purchaseOrderId, inboundId, amount, paymentMethod,
                businessBankAccountId, paidDate, memo, allocations, null, null);
    }
}
