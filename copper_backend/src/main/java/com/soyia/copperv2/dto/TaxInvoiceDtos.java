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

public final class TaxInvoiceDtos {
    private TaxInvoiceDtos() {}

    public record TaxRateView(Long id, String ruleCode, String ruleName, BigDecimal taxRate,
                              String calculationMethod, String invoiceScope, Integer versionNo,
                              LocalDate effectiveFrom, LocalDate effectiveTo, String legalBasis,
                              String status) {}

    public record DashboardView(long inputConfirmedCount, BigDecimal inputTotalAmount,
                                BigDecimal inputTaxAmount, long outputConfirmedCount,
                                BigDecimal outputTotalAmount, BigDecimal outputTaxAmount,
                                long reconciliationExceptionCount, long verificationPendingCount,
                                long deductionPendingCount, long redConfirmationPendingCount) {}

    public record InvoiceRow(Long id, String internalNo, String direction, String invoiceColor,
                             String invoiceType, String invoiceCode, String invoiceNumber,
                             Long blueInvoiceId, Long partnerId, String partnerName,
                             LocalDate invoiceDate, LocalDate receivedOrIssuedDate,
                             BigDecimal amountExcludingTax, BigDecimal taxAmount,
                             BigDecimal totalAmount, String status, String verificationStatus,
                             String bookingStatus, String usageStatus, String usagePeriod,
                             String redConfirmationStatus, Long accountingVoucherId,
                             Long usageVoucherId, Long transferOutVoucherId,
                             BigDecimal redTotalAmount, BigDecimal remainingRedAmount,
                             BigDecimal goodsMatchedAmount, BigDecimal settlementMatchedAmount,
                             BigDecimal fundsMatchedAmount, BigDecimal accountingMatchedTax,
                             String reconciliationStatus, Integer versionNo,
                             LocalDateTime confirmedAt, LocalDateTime createdAt) {}

    public record InvoiceLineView(Long id, Long invoiceId, Integer lineNo, Long productId,
                                  String productSku, String productName, String productSpec,
                                  String unit, BigDecimal quantity,
                                  BigDecimal unitPriceExcludingTax,
                                  BigDecimal amountExcludingTax, Long taxRateRuleId,
                                  String taxRuleCode, String taxRuleName, BigDecimal taxRate,
                                  String calculationMethod, String taxClassificationCode,
                                  BigDecimal taxAmount, BigDecimal totalAmount,
                                  String legalBasis) {}

    public record RelationView(Long id, Long invoiceId, String relationLayer,
                               String sourceType, Long sourceId, String sourceNo,
                               LocalDate sourceBusinessDate, Long partnerId,
                               BigDecimal amountExcludingTax, BigDecimal taxAmount,
                               BigDecimal totalAmount, String status, String matchingBasis,
                               String reversedReason, String createdByName,
                               String reversedByName, LocalDateTime reversedAt,
                               LocalDateTime createdAt) {}

    public record InvoiceEventView(Long id, Long invoiceId, String action,
                                   String fromStatus, String toStatus, String reason,
                                   Long operatorId, String operatorName,
                                   LocalDateTime createdAt) {}

    public record UseEventView(Long id, Long invoiceId, String action,
                               String fromUsageStatus, String toUsageStatus,
                               String taxPeriod, BigDecimal taxAmount,
                               Long accountingVoucherId, String evidenceReference,
                               String reason, String operatorName,
                               LocalDateTime createdAt) {}

    public record VerificationRecordView(Long id, Long invoiceId, String verificationSource,
                                         String verificationResult, String verificationReference,
                                         Long proofAttachmentId, String proofSha256,
                                         LocalDateTime checkedAt, String querySnapshot,
                                         String note, String operatorName,
                                         LocalDateTime createdAt) {}

    public record InvoiceDetail(InvoiceRow invoice, String partnerBusinessNo,
                                String partnerTaxNo, String issuerName, String issuerTaxNo,
                                String recipientName, String recipientTaxNo,
                                String sourceChannel, String currency,
                                BigDecimal roundingDifference,
                                String redConfirmationNo, String redConfirmationInitiator,
                                LocalDateTime redConfirmationInitiatedAt,
                                LocalDateTime redConfirmationExpiresAt,
                                LocalDateTime redConfirmationConfirmedAt,
                                String sourceFileName, String sourceFileSha256,
                                String memo, String voidReason, Long voidVoucherId,
                                List<InvoiceLineView> lines, List<RelationView> relations,
                                List<InvoiceEventView> events, List<UseEventView> useEvents,
                                List<VerificationRecordView> verificationRecords) {}

    public record InvoiceSave(@NotBlank String direction, @NotBlank String invoiceColor,
                              @NotBlank String invoiceType, String invoiceCode,
                              @NotBlank String invoiceNumber, Long blueInvoiceId,
                              @NotNull Long partnerId, @NotBlank String issuerName,
                              String issuerTaxNo, @NotBlank String recipientName,
                              String recipientTaxNo, @NotNull LocalDate invoiceDate,
                              @NotNull LocalDate receivedOrIssuedDate,
                              String sourceChannel, String currency,
                              String sourceFileName, String sourceFileSha256,
                              String memo, Integer expectedVersionNo,
                              @NotEmpty List<@Valid InvoiceLineSave> lines) {}

    public record InvoiceLineSave(Long productId, @NotBlank String productName,
                                  String productSku, String productSpec, String unit,
                                  @NotNull @DecimalMin("0.000000") @Digits(integer = 18, fraction = 6) BigDecimal quantity,
                                  @NotNull @DecimalMin("0.000000") BigDecimal unitPriceExcludingTax,
                                  @NotNull @DecimalMin("0.00") BigDecimal amountExcludingTax,
                                  @NotNull Long taxRateRuleId, String taxClassificationCode,
                                  @NotNull @DecimalMin("0.00") BigDecimal taxAmount,
                                  @NotNull @DecimalMin("0.00") BigDecimal totalAmount) {}

    public record RelationSave(@NotBlank String sourceType, @NotNull Long sourceId,
                               @NotNull @DecimalMin("0.00") BigDecimal amountExcludingTax,
                               @NotNull @DecimalMin("0.00") BigDecimal taxAmount,
                               @NotNull @DecimalMin("0.01") BigDecimal totalAmount,
                               @NotBlank String matchingBasis) {}

    public record ActionRequest(@NotBlank String reason) {}

    public record VerifyRequest(@NotBlank String verificationStatus,
                                @NotBlank String verificationReference,
                                @NotBlank String verificationSource,
                                @NotNull Long proofAttachmentId,
                                LocalDateTime checkedAt,
                                String querySnapshot,
                                String reason) {}

    public record ImportResult(Long batchId, String batchNo, Integer importedInvoiceCount,
                               Integer importedLineCount, List<Long> invoiceIds) {}

    public record BookingRequest(@NotBlank String evidenceReference, String reason) {}

    public record UsageRequest(@NotBlank String usageStatus, @NotBlank String taxPeriod,
                               @NotBlank String evidenceReference, String reason) {}

    public record TransferOutRequest(@NotBlank String taxPeriod,
                                     @NotBlank String evidenceReference,
                                     @NotBlank String reason) {}

    public record RedConfirmationRequest(@NotBlank String confirmationStatus,
                                         String confirmationNo,
                                         @NotBlank String initiator,
                                         LocalDateTime initiatedAt,
                                         LocalDateTime confirmedAt,
                                         String reason) {}

    public record SourceCandidate(String relationLayer, String sourceType, Long sourceId,
                                  String sourceNo, LocalDate businessDate, Long partnerId,
                                  String partnerName, BigDecimal sourceAmount,
                                  BigDecimal alreadyMatchedAmount,
                                  BigDecimal availableAmount, BigDecimal sourceAmountExcludingTax,
                                  BigDecimal sourceTaxAmount, BigDecimal availableAmountExcludingTax,
                                  BigDecimal availableTaxAmount, BigDecimal taxRoundingDifference,
                                  boolean taxSnapshotAvailable, String status) {}
}
