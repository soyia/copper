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

public final class FinancialCloseDtos {
    private FinancialCloseDtos() {}

    public record ReportLine(String lineCode, String lineName, Integer lineOrder,
                             BigDecimal currentAmount, BigDecimal comparativeAmount,
                             String calculationType, List<String> accountCodes) {}

    public record FinancialReport(String reportType, String reportTitle, LocalDate fromDate,
                                  LocalDate toDate, String baseCurrency, List<ReportLine> rows,
                                  BigDecimal primaryTotal, BigDecimal secondaryTotal,
                                  BigDecimal difference, List<String> unmappedAccounts,
                                  LocalDateTime generatedAt) {}

    public record CashFlowLine(String category, String categoryName, String activityType,
                               String direction, BigDecimal amount) {}

    public record CashFlowReport(LocalDate fromDate, LocalDate toDate, String baseCurrency,
                                 List<CashFlowLine> rows, BigDecimal openingCash,
                                 BigDecimal operatingNet, BigDecimal investingNet,
                                 BigDecimal financingNet, BigDecimal netIncrease,
                                 BigDecimal closingCash, BigDecimal ledgerClosingCash,
                                 BigDecimal closingDifference, Long confirmedSessionCount,
                                 Long unclassifiedMatchCount, LocalDateTime generatedAt) {}

    public record BankAccountView(Long id, String accountCode, String accountName,
                                  String bankName, String bankAccountNo, String maskedAccountNo,
                                  String currency, Long ledgerAccountId, String ledgerAccountCode,
                                  String ledgerAccountName, LocalDate openingDate,
                                  BigDecimal openingBalance, String status,
                                  String disabledReason) {}

    public record BankAccountSave(@NotBlank String accountCode, @NotBlank String accountName,
                                  @NotBlank String bankName, @NotBlank String bankAccountNo,
                                  String currency, @NotNull Long ledgerAccountId,
                                  @NotNull LocalDate openingDate,
                                  @NotNull BigDecimal openingBalance, String status,
                                  String disabledReason) {}

    public record ReconciliationSessionRow(Long id, Long bankAccountId, String bankAccountName,
                                           String maskedAccountNo, Long periodId, String periodName,
                                           String reconciliationNo, LocalDate statementFrom,
                                           LocalDate statementTo, BigDecimal openingBalance,
                                           BigDecimal statementClosingBalance,
                                           BigDecimal ledgerClosingBalance,
                                           BigDecimal adjustedStatementBalance,
                                           BigDecimal adjustedLedgerBalance,
                                           BigDecimal difference, String status,
                                           String confirmedByName, LocalDateTime confirmedAt) {}

    public record ReconciliationCreate(@NotNull Long bankAccountId, @NotNull Long periodId,
                                       @NotNull BigDecimal openingBalance,
                                       @NotNull BigDecimal statementClosingBalance) {}

    public record StatementLineInput(@NotNull LocalDate transactionDate, LocalDate valueDate,
                                     String bankSerialNo, @NotBlank String direction,
                                     @NotNull @DecimalMin("0.01") BigDecimal amount,
                                     @NotNull BigDecimal balance, String counterpartyName,
                                     String counterpartyAccount, String purpose) {}

    public record StatementLinesSave(@NotEmpty @Valid List<StatementLineInput> lines) {}

    public record StatementLineView(Long id, Integer lineNo, LocalDate transactionDate,
                                    LocalDate valueDate, String bankSerialNo, String direction,
                                    BigDecimal amount, BigDecimal balance,
                                    String counterpartyName, String counterpartyAccount,
                                    String purpose, BigDecimal matchedAmount,
                                    BigDecimal explainedAmount, BigDecimal remainingAmount,
                                    String status, String voidReason,
                                    String voidedByName, LocalDateTime voidedAt,
                                    Long importBatchId, String importBatchNo,
                                    String unmatchedReasonCode, String unmatchedReason,
                                    String suggestedSourceType, Long suggestedSourceId,
                                    String suggestedSourceNo, Integer suggestedScore,
                                    String suggestedReason) {}

    public record FundCandidate(String sourceType, Long sourceId, String sourceNo,
                                LocalDate sourceDate, String direction, BigDecimal amount,
                                BigDecimal matchedAmount, BigDecimal remainingAmount,
                                Long partnerId, String partnerName, String paymentMethod,
                                String memo) {}

    public record MatchSave(@NotNull Long statementLineId, @NotBlank String sourceType,
                            @NotNull Long sourceId,
                            @NotNull @DecimalMin("0.01") BigDecimal matchedAmount,
                            @NotBlank String cashFlowCategory, String reason) {}

    public record MatchView(Long id, Long statementLineId, String sourceType, Long sourceId,
                            String sourceNo, LocalDate sourceDate, String sourceDirection,
                            BigDecimal matchedAmount, String cashFlowCategory,
                            String matchingMethod, Integer matchScore, String matchRuleVersion,
                            Long importBatchId, String status, String reason,
                            String matchedByName, LocalDateTime matchedAt) {}

    public record StatementImportBatchView(Long id, Long sessionId, String batchNo,
                                           String templateVersion, String sourceFileName,
                                           String sourceFileFormat, String sourceFileSha256,
                                           Long sourceFileSize, String status,
                                           Integer totalRows, Integer validRows,
                                           Integer duplicateRows, Integer errorRows,
                                           Integer autoMatchRows, Integer reviewRows,
                                           Integer unmatchedRows, Integer importedRows,
                                           Integer appliedAutoMatchRows,
                                           String importedByName, LocalDateTime createdAt,
                                           LocalDateTime completedAt) {}

    public record StatementImportRowView(Long id, Long batchId, Integer sourceRowNo,
                                         LocalDate transactionDate, LocalDate valueDate,
                                         String bankSerialNo, String direction,
                                         BigDecimal amount, BigDecimal balance,
                                         String counterpartyName, String counterpartyAccount,
                                         String purpose, String validationStatus,
                                         String validationMessage, String duplicateType,
                                         Long duplicateStatementLineId, String matchAction,
                                         String suggestedSourceType, Long suggestedSourceId,
                                         String suggestedSourceNo, Integer suggestedScore,
                                         String suggestedReason, String unmatchedReasonCode,
                                         String unmatchedReason, Long importedStatementLineId) {}

    public record StatementImportPreview(StatementImportBatchView batch,
                                         List<StatementImportRowView> rows) {}

    public record StatementImportCommit(Boolean applyAutoMatches) {}

    public record StatementImportResult(Long batchId, String batchNo, String status,
                                        Integer importedRows, Integer duplicateRows,
                                        Integer errorRows, Integer appliedAutoMatchRows,
                                        Integer reviewRows, Integer unmatchedRows) {}

    public record DifferenceSave(@NotNull Long statementLineId,
                                 @NotBlank String differenceType,
                                 @NotBlank String direction,
                                 @NotNull @DecimalMin("0.01") BigDecimal amount,
                                 @NotBlank String cashFlowCategory,
                                 @NotBlank String reason, Boolean createVoucher) {}

    public record DifferenceView(Long id, Long statementLineId, String differenceType,
                                 String direction, BigDecimal amount,
                                 String cashFlowCategory, String reason,
                                 Long accountingVoucherId, String accountingVoucherNo,
                                 String accountingVoucherStatus, String status,
                                 String createdByName, LocalDateTime createdAt) {}

    public record PendingSystemSave(@NotBlank String sourceType, @NotNull Long sourceId,
                                    @NotBlank String cashFlowCategory,
                                    @NotBlank String reason) {}

    public record PendingSystemView(Long id, String sourceType, Long sourceId,
                                    String sourceNo, LocalDate sourceDate, String direction,
                                    BigDecimal amount, String cashFlowCategory,
                                    String reason, String status, String createdByName,
                                    LocalDateTime createdAt) {}

    public record ReconciliationEventView(Long id, String action, String fromStatus,
                                          String toStatus, String reason,
                                          String operatorName, LocalDateTime createdAt) {}

    public record ReconciliationDetail(ReconciliationSessionRow session,
                                       List<StatementLineView> statementLines,
                                       List<FundCandidate> fundCandidates,
                                       List<MatchView> matches,
                                       List<DifferenceView> differences,
                                       List<PendingSystemView> pendingSystemItems,
                                       List<ReconciliationEventView> events,
                                       BigDecimal unmatchedStatementCredit,
                                       BigDecimal unmatchedStatementDebit,
                                       BigDecimal unmatchedSystemCredit,
                                       BigDecimal unmatchedSystemDebit) {}

    public record ActionRequest(@NotBlank String reason) {}

    public record CloseCheck(String checkCode, String checkName, String status,
                             Long issueCount, BigDecimal amountDifference, String detail) {}

    public record PeriodEventView(Long id, String action, String fromStatus,
                                  String toStatus, String reason,
                                  String operatorName, LocalDateTime createdAt) {}

    public record PeriodClosePreview(Long periodId, String periodName, LocalDate startDate,
                                     LocalDate endDate, String periodStatus,
                                     List<CloseCheck> checks, Boolean closable,
                                     Long profitVoucherId, String profitVoucherNo,
                                     String profitVoucherStatus,
                                     BigDecimal balanceSheetAssets,
                                     BigDecimal balanceSheetLiabilitiesEquity,
                                     BigDecimal incomeNetProfit,
                                     BigDecimal cashNetIncrease,
                                     BigDecimal trialDifference,
                                     Long lastCloseRunId, String lastCloseRunStatus,
                                     String lastSnapshotHash,
                                     List<PeriodEventView> events) {}

    public record CloseResult(Long periodId, Long closeRunId, String status,
                              String snapshotHash) {}
}
