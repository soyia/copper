package com.soyia.copperv2.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public final class BusinessPostingDtos {
    private BusinessPostingDtos() {}

    public record RuleView(Long id, String ruleCode, String ruleName, String sourceType,
                           String postingAction, Integer versionNo, LocalDate effectiveFrom,
                           LocalDate effectiveTo, String ruleJson, String status,
                           LocalDateTime publishedAt) {}

    public record BatchRow(Long id, String postingNo, String sourceType, Long sourceId,
                           String sourceNo, String postingAction, Integer sourceRevisionNo,
                           String ruleCode, Integer ruleVersionNo, LocalDate businessDate,
                           Long partnerId, String partnerName, Long originalBatchId,
                           Long reversalBatchId, String status, String entryHash, String reason,
                           String operatorName, Boolean legacyImported, LocalDateTime postedAt,
                           LocalDateTime reversedAt, LocalDateTime createdAt) {}

    public record EntryRow(Long id, Long postingBatchId, Integer lineNo, String factType,
                           String direction, Long partnerId, Long productId, String productName,
                           Long warehouseId, String warehouseName, Long inventoryBatchId,
                           String inventoryBatchNo, String sourceLineType, Long sourceLineId,
                           BigDecimal quantity, String quantityUnit, BigDecimal amount,
                           String currency, String memo) {}

    public record EventRow(Long id, Long postingBatchId, String action, String fromStatus,
                           String toStatus, String reason, Long operatorId, String operatorName,
                           LocalDateTime createdAt) {}

    public record BatchDetail(BatchRow batch, List<EntryRow> entries, List<EventRow> events) {}

    public record ReconciliationRow(String sourceType, Long sourceId, String sourceNo,
                                    String documentStatus, String expectedAction,
                                    String issueCode, String issueMessage) {}

    public record OrderRevisionRow(Long id, String orderType, Long orderId, String orderNo,
                                   Integer revisionNo, String changeNo, String changeType,
                                   String changeReason, String snapshotBeforeJson,
                                   String snapshotAfterJson, Long reversedPostingBatchId,
                                   Long postedBatchId, String operatorName,
                                   LocalDateTime createdAt) {}

    public record PurchaseAmendment(@NotBlank String reason,
                                    @NotNull @Valid PurchaseCreateRequest order) {}

    public record SalesAmendment(@NotBlank String reason, String riskOverrideReason,
                                 @NotNull @Valid AlignedDtos.SalesOrderCreate order) {}

    public record EntryDraft(String factType, String direction, Long partnerId, Long productId,
                             Long warehouseId, Long inventoryBatchId, String sourceLineType,
                             Long sourceLineId, BigDecimal quantity, String quantityUnit,
                             BigDecimal amount, String memo) {
        public EntryDraft {
            partnerId = partnerId == null ? 0L : partnerId;
            productId = productId == null ? 0L : productId;
            warehouseId = warehouseId == null ? 0L : warehouseId;
            inventoryBatchId = inventoryBatchId == null ? 0L : inventoryBatchId;
            sourceLineType = sourceLineType == null ? "" : sourceLineType;
            sourceLineId = sourceLineId == null ? 0L : sourceLineId;
            quantity = quantity == null ? BigDecimal.ZERO : quantity;
            quantityUnit = quantityUnit == null ? "" : quantityUnit;
            amount = amount == null ? BigDecimal.ZERO : amount;
            memo = memo == null ? "" : memo;
        }
    }

    public record PostingResult(Long batchId, String postingNo, boolean alreadyPosted) {}
}
