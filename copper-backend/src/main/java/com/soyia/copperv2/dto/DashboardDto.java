package com.soyia.copperv2.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record DashboardDto(
        BigDecimal stockQuantity,
        BigDecimal stockCostAmount,
        BigDecimal receivableAmount,
        BigDecimal paidAmount,
        BigDecimal todayInboundQuantity,
        BigDecimal todayOutboundQuantity,
        OwnerAttention ownerAttention,
        List<FulfillmentReminder> fulfillmentReminders,
        List<InventoryBatchDto> inventoryAlerts,
        List<OrderDtos.InboundRow> recentInbounds,
        List<OrderDtos.ReceivableRow> recentReceivables,
        List<OrderDtos.PaymentRow> recentPayments
) {
    public record FulfillmentReminder(
            String sourceType,
            Long sourceId,
            String orderNo,
            String partnerName,
            LocalDate plannedDate,
            String timingBucket,
            long remainingLineCount,
            String warehouseName
    ) {}

    public record OwnerAttention(
            boolean completeScope,
            SettlementAttention receivables,
            SettlementAttention payables,
            InventoryAttention inventory,
            BankAttention bank,
            InvoiceAttention invoices,
            CloseAttention close
    ) {}

    public record SettlementAttention(
            long dueTodayCount,
            BigDecimal dueTodayAmount,
            long overdueCount,
            BigDecimal overdueAmount
    ) {}

    public record InventoryAttention(
            long riskCount,
            long reconciliationMismatchCount,
            long negativeBalanceCount,
            long unavailableBatchCount
    ) {}

    public record BankAttention(
            long unmatchedStatementCount,
            BigDecimal unmatchedStatementAmount,
            long openSessionCount
    ) {}

    public record InvoiceAttention(
            long unreconciledCount,
            BigDecimal unreconciledAmount,
            long verificationPendingCount
    ) {}

    public record CloseAttention(
            Long periodId,
            String periodName,
            LocalDate periodEndDate,
            String periodStatus,
            long blockingCheckCount,
            long blockingIssueCount,
            String blockingSummary
    ) {}
}
