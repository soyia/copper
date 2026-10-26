package com.soyia.copperv2.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public final class OutboundActionRequests {
    private OutboundActionRequests() {
    }

    public record Reason(
            @NotBlank String reason,
            LocalDate businessDate,
            Long approvalId
    ) {
        public Reason(String reason, LocalDate businessDate) {
            this(reason, businessDate, null);
        }
    }

    public record Settlement(
            @NotBlank String reason,
            BigDecimal discountRate,
            BigDecimal adjustmentAmount,
            @Valid @NotEmpty List<ItemPrice> items
    ) {
        public record ItemPrice(
                @NotNull Long outboundItemId,
                @NotNull @DecimalMin("0.0001") BigDecimal unitPrice
        ) {
        }
    }

    public record SalesReturnCreate(
            @NotNull Long outboundId,
            @NotNull LocalDate businessDate,
            @NotBlank String reason,
            String memo,
            @Valid @NotEmpty List<Item> items
    ) {
        public record Item(
                @NotNull Long outboundItemId,
                @NotNull @DecimalMin("0.000001") @Digits(integer = 18, fraction = 6) BigDecimal quantity
        ) {
        }
    }

    public record SalesReturnRow(
            Long id,
            String returnNo,
            Long outboundId,
            String outboundNo,
            Long salesOrderId,
            String salesOrderNo,
            Long customerId,
            String customerName,
            LocalDate businessDate,
            String status,
            BigDecimal totalQuantity,
            BigDecimal totalAmount,
            Boolean priceIncludesTax,
            String taxRateSummary,
            String taxRuleSnapshot,
            BigDecimal amountExcludingTax,
            BigDecimal taxAmount,
            BigDecimal taxRoundingDifference,
            BigDecimal receivableReduction,
            BigDecimal advanceCreditAmount,
            String reason,
            String memo,
            LocalDateTime confirmedAt,
            String reversalNo,
            String reversalReason,
            LocalDateTime reversedAt
    ) {
    }
}
