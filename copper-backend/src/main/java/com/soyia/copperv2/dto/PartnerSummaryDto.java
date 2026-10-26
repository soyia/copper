package com.soyia.copperv2.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record PartnerSummaryDto(
        Long id,
        String businessNo,
        String type,
        Integer typeMask,
        String entityType,
        String name,
        String aliasName,
        String tags,
        Boolean topFixed,
        String status,
        String customerStatus,
        String supplierStatus,
        String primaryContactName,
        String primaryContactRole,
        String primaryContactPhone,
        String primaryContactWechat,
        String primaryAddress,
        BigDecimal customerReceivable,
        BigDecimal customerOverdue,
        BigDecimal customerCreditLimit,
        BigDecimal customerAvailableCredit,
        BigDecimal customerAdvance,
        BigDecimal supplierPayable,
        BigDecimal supplierPrepay,
        LocalDate customerLastBusinessDate,
        LocalDate supplierLastBusinessDate,
        LocalDate customerLastPaymentDate,
        LocalDate supplierLastPaymentDate,
        LocalDateTime lastFollowupAt,
        LocalDate nextFollowDate,
        String suppliedProducts
) {
}
