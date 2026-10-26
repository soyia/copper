package com.soyia.copperv2.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public final class TreasuryDtos {
    private TreasuryDtos() {}

    public record DocumentCreate(
            @NotBlank String documentType,
            @NotNull Long partnerId,
            @NotNull LocalDate businessDate,
            @NotNull LocalDate dueDate,
            @NotBlank @Size(max = 128) String basisNo,
            @NotBlank @Size(max = 255) String summary,
            @NotNull Long offsetAccountId,
            @NotNull Boolean priceIncludesTax,
            @NotNull @DecimalMin("0.0000") BigDecimal taxRate,
            @NotNull @DecimalMin("0.01") BigDecimal amount,
            @Size(max = 500) String memo,
            String inputTaxTreatment
    ) {
        public DocumentCreate(String documentType, Long partnerId, LocalDate businessDate, LocalDate dueDate,
                              String basisNo, String summary, Long offsetAccountId, Boolean priceIncludesTax,
                              BigDecimal taxRate, BigDecimal amount, String memo) {
            this(documentType, partnerId, businessDate, dueDate, basisNo, summary, offsetAccountId,
                    priceIncludesTax, taxRate, amount, memo, "DEDUCTIBLE");
        }
    }

    public record DocumentRow(
            Long id, String documentNo, String documentType, Long partnerId, String partnerName,
            LocalDate businessDate, LocalDate dueDate, String basisNo, String summary,
            Long offsetAccountId, String offsetAccountCode, String offsetAccountName,
            Boolean priceIncludesTax, BigDecimal taxRate, BigDecimal amountExcludingTax,
            BigDecimal taxAmount, BigDecimal totalAmount, BigDecimal taxRoundingDifference,
            String taxRuleSnapshot, String inputTaxTreatment,
            BigDecimal settledAmount, BigDecimal outstandingAmount,
            String settlementDirection, String status, String memo, Integer versionNo,
            String createdByName, String confirmedByName, LocalDateTime confirmedAt,
            String cancelReason, String cancelledByName, LocalDateTime cancelledAt,
            String reversalReason, String reversedByName, LocalDateTime reversedAt,
            Long accountingVoucherId, String accountingVoucherNo, String accountingVoucherStatus,
            LocalDateTime createdAt
    ) {}

    public record EventRow(Long id, String action, String fromStatus, String toStatus,
                           String reason, String operatorName, LocalDateTime createdAt) {}

    public record FundRow(
            Long id, String fundNo, String fundType, Long documentId, Long paymentRequestId,
            String direction, String settlementMethod, Long businessBankAccountId,
            String bankAccountName, Long counterBankAccountId, String counterBankAccountName,
            BigDecimal amount, LocalDate transactionDate, String cashFlowCategory,
            String counterpartyName, String purpose, String status,
            Long accountingVoucherId, String accountingVoucherNo, String accountingVoucherStatus,
            Long reversalVoucherId, String reversalVoucherNo,
            String createdByName, String reversalReason, String reversedByName,
            LocalDateTime reversedAt, LocalDateTime createdAt
    ) {}

    public record PaymentRequestRow(
            Long id, String requestNo, Long documentId, String documentNo, String documentType,
            Long partnerId, String partnerName, BigDecimal documentOutstandingAmount,
            BigDecimal requestedAmount, BigDecimal paidAmount, BigDecimal unpaidAmount,
            LocalDate requestDate, LocalDate expectedPaymentDate, String purpose, String status,
            Integer versionNo, String createdByName, String approvedByName,
            LocalDateTime approvedAt, String rejectedByName, String rejectedReason,
            LocalDateTime rejectedAt, String cancelledByName, String cancelReason,
            LocalDateTime cancelledAt, LocalDateTime createdAt
    ) {}

    public record DocumentDetail(DocumentRow header, List<EventRow> events,
                                 List<FundRow> funds, List<PaymentRequestRow> paymentRequests) {}

    public record PaymentRequestCreate(
            @NotNull Long documentId,
            @NotNull @DecimalMin("0.01") BigDecimal requestedAmount,
            @NotNull LocalDate requestDate,
            @NotNull LocalDate expectedPaymentDate,
            @NotBlank @Size(max = 500) String purpose
    ) {}

    public record SettlementCreate(
            Long paymentRequestId,
            @NotNull @DecimalMin("0.01") BigDecimal amount,
            @NotNull LocalDate transactionDate,
            @NotBlank String settlementMethod,
            Long businessBankAccountId,
            @NotBlank String cashFlowCategory,
            @NotBlank @Size(max = 500) String purpose
    ) {}

    public record TransferCreate(
            @NotNull Long sourceBankAccountId,
            @NotNull Long destinationBankAccountId,
            @NotNull @DecimalMin("0.01") BigDecimal amount,
            @NotNull LocalDate transactionDate,
            @NotBlank @Size(max = 500) String purpose
    ) {}

    public record Action(@NotBlank @Size(min = 4, max = 500) String reason,
                         LocalDate businessDate,
                         Long approvalId) {
        public Action(String reason, LocalDate businessDate) {
            this(reason, businessDate, null);
        }
    }

    public record JournalRow(
            String rowKey, String sourceType, Long sourceId, String sourceNo,
            Long fundTransactionId, LocalDate transactionDate, String settlementMethod,
            Long businessBankAccountId, String bankAccountName, String direction,
            BigDecimal amount, String cashFlowCategory, Long partnerId, String partnerName,
            String purpose, String status, BigDecimal matchedAmount,
            BigDecimal unmatchedAmount, LocalDateTime createdAt
    ) {}

    public record Dashboard(
            Long draftDocumentCount, BigDecimal draftDocumentAmount,
            Long outstandingDocumentCount, BigDecimal outstandingDocumentAmount,
            Long pendingRequestCount, BigDecimal pendingRequestAmount,
            Long unreconciledBankCount, BigDecimal unreconciledBankAmount,
            Boolean restricted
    ) {}
}
