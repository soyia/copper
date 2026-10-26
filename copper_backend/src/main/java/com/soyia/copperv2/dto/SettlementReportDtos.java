package com.soyia.copperv2.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public final class SettlementReportDtos {
    private SettlementReportDtos() { }

    public record Row(
            String sourceType,
            Long sourceId,
            String sourceNo,
            Long partnerId,
            String partnerName,
            LocalDate businessDate,
            BigDecimal grossAmount,
            BigDecimal reductionAmount,
            BigDecimal totalAmount,
            BigDecimal paidAmount,
            BigDecimal unpaidAmount,
            Integer settlementDays,
            LocalDate dueDate,
            Integer overdueDays,
            String agingBucket,
            String paymentStatus,
            String status
    ) { }

    public record Summary(
            long documentCount,
            long partnerCount,
            BigDecimal grossAmount,
            BigDecimal reductionAmount,
            BigDecimal totalAmount,
            BigDecimal paidAmount,
            BigDecimal unpaidAmount,
            long dueTodayCount,
            BigDecimal dueTodayAmount,
            long overdueCount,
            BigDecimal overdueAmount
    ) { }

    public record AgingBucket(
            String code,
            String label,
            long documentCount,
            BigDecimal outstandingAmount
    ) { }

    public record Page(
            String direction,
            LocalDate asOfDate,
            LocalDateTime snapshotAt,
            boolean completeScope,
            int pageNo,
            int pageSize,
            long totalRows,
            int totalPages,
            Summary summary,
            List<AgingBucket> aging,
            List<Row> items
    ) { }
}
