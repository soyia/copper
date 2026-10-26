package com.soyia.copperv2.controller;

import com.soyia.copperv2.dto.ApiResponse;
import com.soyia.copperv2.dto.AtpDtos;
import com.soyia.copperv2.service.AtpService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
public class AtpController {
    private final AtpService atpService;

    public AtpController(AtpService atpService) {
        this.atpService = atpService;
    }

    @GetMapping("/inventory/atp")
    public ApiResponse<List<AtpDtos.Summary>> summaries(
            @RequestParam(required = false) Long productId,
            @RequestParam(required = false) Long warehouseId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOfDate) {
        return ApiResponse.ok(atpService.summaries(productId, warehouseId, asOfDate));
    }

    @GetMapping("/inventory/atp/timeline")
    public ApiResponse<List<AtpDtos.TimelineRow>> timeline(
            @RequestParam Long productId,
            @RequestParam Long warehouseId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate) {
        return ApiResponse.ok(atpService.timeline(productId, warehouseId, fromDate, toDate));
    }

    @GetMapping("/sales-orders/{id}/atp")
    public ApiResponse<AtpDtos.OrderEvaluation> salesOrderAtp(@PathVariable Long id) {
        return ApiResponse.ok(atpService.previewSalesOrder(id));
    }

    @GetMapping("/sales-orders/{id}/atp-history")
    public ApiResponse<List<AtpDtos.Snapshot>> salesOrderAtpHistory(@PathVariable Long id) {
        return ApiResponse.ok(atpService.salesOrderHistory(id));
    }
}
