package com.soyia.copperv2.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public class ReceivableSettlementDtos {
    public record AdjustmentRow(
            Long id,
            String adjustmentNo,
            Long customerId,
            String customerName,
            Long salesOrderId,
            String salesOrderNo,
            String adjustmentType,
            BigDecimal amount,
            LocalDate occurredDate,
            String status,
            String reason,
            String evidenceRef,
            LocalDateTime confirmedAt,
            String reversalNo,
            String reversalReason,
            LocalDateTime reversedAt,
            LocalDateTime createdAt
    ) {
    }

    public record AdjustmentCreate(
            @NotNull Long salesOrderId,
            @NotBlank String adjustmentType,
            @NotNull @DecimalMin("0.01") BigDecimal amount,
            @NotNull LocalDate occurredDate,
            @NotBlank String reason,
            String evidenceRef
    ) {
    }

    public record AdjustmentAction(
            LocalDate occurredDate,
            @NotBlank String reason
    ) {
    }

    public record AcceptanceBillRow(
            Long id,
            String billNo,
            String instrumentNo,
            String billType,
            Long customerId,
            String customerName,
            Long salesOrderId,
            String salesOrderNo,
            BigDecimal faceAmount,
            LocalDate issueDate,
            LocalDate dueDate,
            LocalDate receivedDate,
            String drawer,
            String acceptor,
            Long settlementPaymentId,
            String status,
            LocalDate discountDate,
            String discountBank,
            BigDecimal discountFee,
            BigDecimal netProceeds,
            BigDecimal realizedCashAmount,
            String memo,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
    }

    public record AcceptanceCreate(
            @NotNull Long salesOrderId,
            @NotBlank String instrumentNo,
            @NotBlank String billType,
            @NotNull @DecimalMin("0.01") BigDecimal faceAmount,
            @NotNull LocalDate issueDate,
            @NotNull LocalDate dueDate,
            @NotNull LocalDate receivedDate,
            String drawer,
            @NotBlank String acceptor,
            String memo
    ) {
    }

    public record AcceptanceAction(
            @NotNull LocalDate occurredDate,
            BigDecimal netProceeds,
            String bank,
            String counterparty,
            Long businessBankAccountId,
            String memo
    ) {
    }

    public record AcceptanceEventRow(
            Long id,
            String eventNo,
            Long billId,
            String eventType,
            LocalDate occurredDate,
            BigDecimal cashAmount,
            BigDecimal feeAmount,
            Long businessBankAccountId,
            String businessBankAccountName,
            String counterparty,
            String memo,
            LocalDateTime createdAt
    ) {
    }
}
