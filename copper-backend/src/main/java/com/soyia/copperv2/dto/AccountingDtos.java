package com.soyia.copperv2.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public final class AccountingDtos {
    private AccountingDtos() {}

    public record EntityView(Long id, String entityCode, String entityName, String baseCurrency,
                             Integer fiscalYearStartMonth, String status) {}

    public record AccountView(Long id, Long entityId, String accountCode, String accountName,
                              String category, String normalDirection, Long parentId,
                              Integer accountLevel, Boolean leaf, Boolean allowManual,
                              String partnerAuxiliary, String productAuxiliary,
                              String warehouseAuxiliary, LocalDate effectiveFrom,
                              LocalDate effectiveTo, String status, Boolean used) {}

    public record AccountSave(@NotBlank String accountCode, @NotBlank String accountName,
                              @NotBlank String category, @NotBlank String normalDirection,
                              Long parentId, Boolean allowManual, String partnerAuxiliary,
                              String productAuxiliary, String warehouseAuxiliary,
                              @NotNull LocalDate effectiveFrom, LocalDate effectiveTo,
                              String status) {}

    public record PeriodView(Long id, Long entityId, Integer fiscalYear, Integer periodNo,
                             String periodName, LocalDate startDate, LocalDate endDate,
                             String status, String closeReason, String closedByName,
                             LocalDateTime closedAt, LocalDateTime reopenedAt) {}

    public record InitializeYear(@NotNull Integer fiscalYear) {}

    public record PeriodAction(@NotBlank String reason) {}

    public record RuleView(Long id, Long entityId, String ruleCode, String ruleName,
                           String sourceType, String postingAction, String factType,
                           String factDirection, Long debitAccountId, String debitAccountCode,
                           String debitAccountName, Long creditAccountId, String creditAccountCode,
                           String creditAccountName, Integer versionNo, LocalDate effectiveFrom,
                           LocalDate effectiveTo, String status, String changeReason,
                           String publishedByName, LocalDateTime publishedAt) {}

    public record RuleSave(@NotBlank String ruleCode, @NotBlank String ruleName,
                           @NotBlank String sourceType, @NotBlank String postingAction,
                           @NotBlank String factType, @NotBlank String factDirection,
                           @NotNull Long debitAccountId, @NotNull Long creditAccountId,
                           @NotNull LocalDate effectiveFrom, @NotBlank String changeReason) {}

    public record RuleCoverageSummary(LocalDate asOf, Integer totalCount, Integer coveredCount,
                                      Integer warningCount, Integer blockingCount,
                                      Integer accountingRuleCount, Integer noVoucherCount) {}

    public record RuleCoverageRow(String scenarioCode, String category, String scenarioName,
                                  String sourceType, String postingAction, String factType,
                                  String factDirection, String settlementMethod,
                                  String generationMode, Boolean voucherRequired,
                                  String baselineDebitCode, String baselineCreditCode,
                                  String actualRuleCode, String actualDebitCode,
                                  String actualCreditCode, Integer activeRuleCount,
                                  String coverageStatus, String resultDescription,
                                  String evidence) {}

    public record RuleCoverageMatrix(RuleCoverageSummary summary,
                                     List<RuleCoverageRow> rows) {}

    public record VoucherRow(Long id, Long entityId, Long periodId, String voucherNo,
                             String voucherType, LocalDate voucherDate, String summary,
                             String sourceType, Long sourceId, String sourceNo,
                             Long businessPostingBatchId, Long originalVoucherId,
                             Long reversalVoucherId, String status, BigDecimal debitTotal,
                             BigDecimal creditTotal, String makerName, String reviewerName,
                             String posterName, LocalDateTime reviewedAt, LocalDateTime postedAt,
                             LocalDateTime reversedAt, LocalDateTime createdAt) {}

    public record VoucherLineView(Long id, Long voucherId, Integer lineNo, Long accountId,
                                  String accountCode, String accountName, String summary,
                                  BigDecimal debitAmount, BigDecimal creditAmount, Long partnerId,
                                  String partnerName, Long productId, String productName,
                                  Long warehouseId, String warehouseName,
                                  Long sourcePostingEntryId, String sourceLineType,
                                  Long sourceLineId) {}

    public record VoucherEventView(Long id, Long voucherId, String action, String fromStatus,
                                   String toStatus, String reason, Long operatorId,
                                   String operatorName, LocalDateTime createdAt) {}

    public record LinkedTaxInvoiceView(Long id, String internalNo, String direction,
                                       String invoiceColor, String invoiceNumber,
                                       BigDecimal taxAmount, String status,
                                       String matchingBasis) {}

    public record VoucherDetail(VoucherRow voucher, List<VoucherLineView> lines,
                                List<VoucherEventView> events,
                                List<LinkedTaxInvoiceView> linkedTaxInvoices) {}

    public record VoucherLineSave(@NotNull Long accountId, @NotBlank String summary,
                                  @NotNull @DecimalMin("0.00") BigDecimal debitAmount,
                                  @NotNull @DecimalMin("0.00") BigDecimal creditAmount,
                                  Long partnerId, Long productId, Long warehouseId) {}

    public record VoucherSave(@NotNull LocalDate voucherDate, @NotBlank String summary,
                              @NotEmpty @Valid List<VoucherLineSave> lines) {}

    public record VoucherAction(@NotBlank String reason, LocalDate voucherDate) {}

    public record GenerateResult(Integer generatedCount, Integer cancelledCount,
                                 Integer issueCount, List<Long> voucherIds) {}

    public record ArchiveCreate(@NotNull Long periodId, @NotNull Boolean finalArchive) {}

    public record ArchiveView(Long id, Long entityId, Long periodId, String periodName,
                              String archiveNo, String archiveMode, String status,
                              String formatVersion, String fileName, Long archiveBytes,
                              String archiveSha256, Integer voucherCount,
                              Integer sourceAttachmentCount, String generatedByName,
                              LocalDateTime generatedAt, LocalDateTime completedAt,
                              String errorMessage, Boolean downloadable) {}

    public record GenerationIssueView(Long id, Long businessPostingBatchId, String postingNo,
                                      String sourceType, Long sourceId, String sourceNo,
                                      String issueCode, String issueMessage, String status,
                                      Integer retryCount, Long resolvedVoucherId,
                                      LocalDateTime lastAttemptAt, LocalDateTime resolvedAt,
                                      LocalDateTime createdAt) {}

    public record DashboardView(EntityView entity, PeriodView currentPeriod,
                                Long draftVoucherCount, Long reviewedVoucherCount,
                                Long postedVoucherCount, Long openIssueCount,
                                Long ungeneratedPostingCount, BigDecimal trialDifference) {}

    public record GeneralLedgerRow(Long accountId, String accountCode, String accountName,
                                   String category, String normalDirection,
                                   BigDecimal openingDebit, BigDecimal openingCredit,
                                   BigDecimal periodDebit, BigDecimal periodCredit,
                                   BigDecimal closingDebit, BigDecimal closingCredit) {}

    public record LedgerEntryView(Long id, Long voucherId, String voucherNo,
                                  LocalDate voucherDate, Long accountId, String accountCode,
                                  String accountName, String summary, BigDecimal debitAmount,
                                  BigDecimal creditAmount, BigDecimal runningBalance,
                                  Long partnerId, String partnerName, Long productId,
                                  String productName, Long warehouseId, String warehouseName) {}

    public record TrialBalanceReport(List<GeneralLedgerRow> rows,
                                     BigDecimal openingDebitTotal, BigDecimal openingCreditTotal,
                                     BigDecimal periodDebitTotal, BigDecimal periodCreditTotal,
                                     BigDecimal closingDebitTotal, BigDecimal closingCreditTotal,
                                     BigDecimal difference) {}
}
