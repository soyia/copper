package com.soyia.copperv2.controller;

import com.soyia.copperv2.dto.ApiResponse;
import com.soyia.copperv2.dto.SettlementReportDtos;
import com.soyia.copperv2.service.SettlementReportService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
public class SettlementReportController {
    private final SettlementReportService settlementReportService;

    public SettlementReportController(SettlementReportService settlementReportService) {
        this.settlementReportService = settlementReportService;
    }

    @GetMapping("/settlement-reports/receivables")
    public ApiResponse<SettlementReportDtos.Page> receivables(
            @RequestParam(required = false) Long partnerId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String sourceType,
            @RequestParam(defaultValue = "open") String scope,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueDateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueDateTo,
            @RequestParam(defaultValue = "1") Integer pageNo,
            @RequestParam(defaultValue = "50") Integer pageSize) {
        return ApiResponse.ok(settlementReportService.query("RECEIVABLE", partnerId, keyword, sourceType, scope,
                dueDateFrom, dueDateTo, pageNo, pageSize));
    }

    @GetMapping("/settlement-reports/payables")
    public ApiResponse<SettlementReportDtos.Page> payables(
            @RequestParam(required = false) Long partnerId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String sourceType,
            @RequestParam(defaultValue = "open") String scope,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueDateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueDateTo,
            @RequestParam(defaultValue = "1") Integer pageNo,
            @RequestParam(defaultValue = "50") Integer pageSize) {
        return ApiResponse.ok(settlementReportService.query("PAYABLE", partnerId, keyword, sourceType, scope,
                dueDateFrom, dueDateTo, pageNo, pageSize));
    }
}
