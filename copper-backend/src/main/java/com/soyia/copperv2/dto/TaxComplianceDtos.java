package com.soyia.copperv2.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public final class TaxComplianceDtos {
    private TaxComplianceDtos() {}

    public record DashboardView(long credentialCount, long pendingVerificationCount,
                                long parseExceptionCount, long unlinkedCount,
                                long failedVerificationCount,
                                WorkpaperRow latestWorkpaper) {}

    public record CredentialRow(Long id, String credentialNo, String credentialType,
                                String fileFormat, String standardCode, String standardVersion,
                                String originalFileName, Long fileSize, String sourceFileSha256,
                                String parseStatus, String parseMessage,
                                String verificationStatus, String signatureStatus,
                                String externalNo, String directionHint, LocalDate issueDate,
                                LocalDate periodFrom, LocalDate periodTo,
                                String accountingEntityCode, String accountingEntityName,
                                String counterpartyCode, String counterpartyName,
                                String bankAccountNo, String currency,
                                BigDecimal amountExcludingTax, BigDecimal taxAmount,
                                BigDecimal totalAmount, Integer detailCount,
                                String status, Integer activeRelationCount,
                                String createdByName, LocalDateTime createdAt) {}

    public record VerificationView(Long id, String verificationKind,
                                   String verificationSource, String verificationResult,
                                   String verificationReference, LocalDateTime checkedAt,
                                   String resultSnapshot, String proofFileName,
                                   String proofContentType, Long proofFileSize,
                                   String proofSha256, String operatorName,
                                   LocalDateTime createdAt) {}

    public record RelationView(Long id, String relationType, String documentType,
                               Long documentId, String documentNo,
                               Long evidenceAttachmentId, String status, String note,
                               String createdByName, LocalDateTime createdAt,
                               String reversedByName, String reversedReason,
                               LocalDateTime reversedAt) {}

    public record EventView(Long id, String action, String fromStatus, String toStatus,
                            String summary, String operatorName, LocalDateTime createdAt) {}

    public record CredentialDetail(CredentialRow credential,
                                   List<VerificationView> verifications,
                                   List<RelationView> relations,
                                   List<EventView> events) {}

    public record LinkRequest(@NotBlank String relationType,
                              @NotBlank String documentType,
                              @NotNull Long documentId,
                              String note) {}

    public record LinkCandidate(String relationType, String documentType, Long documentId,
                                String documentNo, LocalDate businessDate,
                                BigDecimal amount, String status,
                                String matchLevel, String matchReason) {}

    public record ActionRequest(@NotBlank String reason) {}

    public record GenerateWorkpaperRequest(@NotBlank String period) {}

    public record ReviewWorkpaperRequest(@NotBlank String reason) {}

    public record WorkpaperRow(Long id, Long periodId, String periodName, String workpaperNo,
                               String taxType, String ruleCode, Integer ruleVersion,
                               Integer versionNo, LocalDateTime dataCutoffAt, String status,
                               BigDecimal outputNetAmount, BigDecimal outputTaxAmount,
                               BigDecimal inputNetAmount, BigDecimal inputTaxAmount,
                               BigDecimal deductibleInputTax, BigDecimal transferredOutInputTax,
                               BigDecimal estimatedVatPayable,
                               BigDecimal ledgerOutputTax, BigDecimal ledgerInputTax,
                               BigDecimal ledgerTransferredOutTax,
                               BigDecimal outputLedgerDifference,
                               BigDecimal inputLedgerDifference,
                               Integer blockerCount, Integer warningCount,
                               String contentSha256, String reviewNote,
                               String generatedByName, LocalDateTime generatedAt,
                               String reviewedByName, LocalDateTime reviewedAt) {}

    public record WorkpaperLineView(Long id, Integer lineNo, String sectionCode,
                                    String itemCode, String itemName, BigDecimal taxRate,
                                    BigDecimal amountExcludingTax, BigDecimal taxAmount,
                                    BigDecimal totalAmount, Integer sourceCount,
                                    String severity, String formula,
                                    String sourceQuerySnapshot) {}

    public record WorkpaperEventView(Long id, String action, String fromStatus,
                                     String toStatus, String reason,
                                     String operatorName, LocalDateTime createdAt) {}

    public record WorkpaperDetail(WorkpaperRow workpaper,
                                  List<WorkpaperLineView> lines,
                                  List<WorkpaperEventView> events) {}

    public record FileContent(String fileName, String contentType, byte[] content) {}
}
