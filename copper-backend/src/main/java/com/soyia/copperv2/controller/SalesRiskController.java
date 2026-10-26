package com.soyia.copperv2.controller;

import com.soyia.copperv2.dto.ApiResponse;
import com.soyia.copperv2.dto.SalesRiskDtos;
import com.soyia.copperv2.service.SalesRiskService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class SalesRiskController {
    private final SalesRiskService service;

    public SalesRiskController(SalesRiskService service) {
        this.service = service;
    }

    @GetMapping("/sales-risk/partners/{partnerId}/policy")
    public ApiResponse<SalesRiskDtos.Policy> policy(@PathVariable Long partnerId) {
        return ApiResponse.ok(service.policy(partnerId));
    }

    @PutMapping("/sales-risk/partners/{partnerId}/policy")
    public ApiResponse<SalesRiskDtos.Policy> updatePolicy(
            @PathVariable Long partnerId, @Valid @RequestBody SalesRiskDtos.PolicySave request) {
        return ApiResponse.ok(service.updatePolicy(partnerId, request));
    }

    @GetMapping("/sales-orders/{id}/risk")
    public ApiResponse<SalesRiskDtos.Evaluation> salesOrderRisk(@PathVariable Long id) {
        return ApiResponse.ok(service.previewSalesOrder(id));
    }

    @GetMapping("/sales-orders/{id}/risk-history")
    public ApiResponse<List<SalesRiskDtos.Snapshot>> salesOrderRiskHistory(@PathVariable Long id) {
        return ApiResponse.ok(service.salesOrderHistory(id));
    }

    @GetMapping("/outbounds/{id}/risk")
    public ApiResponse<SalesRiskDtos.Evaluation> outboundRisk(@PathVariable Long id) {
        return ApiResponse.ok(service.previewOutbound(id));
    }

    @GetMapping("/outbounds/{id}/risk-history")
    public ApiResponse<List<SalesRiskDtos.Snapshot>> outboundRiskHistory(@PathVariable Long id) {
        return ApiResponse.ok(service.outboundHistory(id));
    }
}
