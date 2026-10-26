package com.soyia.copperv2.controller;

import com.soyia.copperv2.dto.ApiResponse;
import com.soyia.copperv2.dto.RiskExceptionDtos;
import com.soyia.copperv2.service.RiskExceptionExecutionService;
import com.soyia.copperv2.service.RiskExceptionService;
import com.soyia.copperv2.service.SalesRiskService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/risk-exceptions")
public class RiskExceptionController {
    private final RiskExceptionService service;
    private final SalesRiskService salesRiskService;
    private final RiskExceptionExecutionService executionService;

    public RiskExceptionController(RiskExceptionService service, SalesRiskService salesRiskService,
                                   RiskExceptionExecutionService executionService) {
        this.service = service;
        this.salesRiskService = salesRiskService;
        this.executionService = executionService;
    }

    @GetMapping("/policy")
    public ApiResponse<RiskExceptionDtos.Policy> policy() {
        return ApiResponse.ok(service.policy());
    }

    @PutMapping("/policy")
    public ApiResponse<RiskExceptionDtos.Policy> updatePolicy(
            @Valid @RequestBody RiskExceptionDtos.PolicySave request) {
        return ApiResponse.ok(service.updatePolicy(request));
    }

    @GetMapping("/dashboard")
    public ApiResponse<RiskExceptionDtos.Dashboard> dashboard() {
        return ApiResponse.ok(service.dashboard());
    }

    @GetMapping
    public ApiResponse<List<RiskExceptionDtos.RequestRow>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String exceptionType,
            @RequestParam(required = false) Boolean mineOnly) {
        return ApiResponse.ok(service.list(status, exceptionType, mineOnly));
    }

    @GetMapping("/approvers")
    public ApiResponse<List<RiskExceptionDtos.Approver>> approvers() {
        return ApiResponse.ok(service.approvers());
    }

    @GetMapping("/source")
    public ApiResponse<RiskExceptionDtos.SourceStatus> sourceStatus(
            @RequestParam String sourceType, @RequestParam Long sourceId, @RequestParam String actionCode) {
        RiskExceptionService.Trigger trigger = switch (sourceType.trim().toUpperCase()) {
            case "SALES_ORDER", "OUTBOUND" -> salesRiskService.exceptionTrigger(
                    sourceType.trim().toUpperCase(), sourceId, actionCode.trim().toUpperCase());
            default -> service.resolveTrigger(sourceType, sourceId, actionCode);
        };
        return ApiResponse.ok(service.sourceStatus(trigger));
    }

    @GetMapping("/{id}")
    public ApiResponse<RiskExceptionDtos.RequestDetail> detail(@PathVariable Long id) {
        return ApiResponse.ok(service.detail(id));
    }

    @PostMapping("/requests")
    public ApiResponse<RiskExceptionDtos.RequestRow> submit(@Valid @RequestBody RiskExceptionDtos.SourceSubmit request) {
        RiskExceptionService.Trigger trigger = switch (request.sourceType().trim().toUpperCase()) {
            case "SALES_ORDER", "OUTBOUND" -> salesRiskService.exceptionTrigger(
                    request.sourceType().trim().toUpperCase(), request.sourceId(), request.actionCode().trim().toUpperCase());
            default -> service.resolveTrigger(request.sourceType(), request.sourceId(), request.actionCode());
        };
        return ApiResponse.ok(service.submit(trigger, request.reason()));
    }

    @PostMapping("/supplier-payment-intents")
    public ApiResponse<RiskExceptionDtos.RequestRow> submitSupplierPayment(
            @Valid @RequestBody RiskExceptionDtos.SupplierPaymentIntentSubmit request) {
        return ApiResponse.ok(service.submitSupplierPaymentIntent(request.payment(), request.reason()));
    }

    @PostMapping("/{id}/approve")
    public ApiResponse<RiskExceptionDtos.RequestRow> approve(@PathVariable Long id,
            @Valid @RequestBody RiskExceptionDtos.Decision request) {
        return ApiResponse.ok(service.approve(id, request));
    }

    @PostMapping("/{id}/reject")
    public ApiResponse<RiskExceptionDtos.RequestRow> reject(@PathVariable Long id,
            @Valid @RequestBody RiskExceptionDtos.Decision request) {
        return ApiResponse.ok(service.reject(id, request));
    }

    @PostMapping("/{id}/withdraw")
    public ApiResponse<RiskExceptionDtos.RequestRow> withdraw(@PathVariable Long id,
            @Valid @RequestBody RiskExceptionDtos.Decision request) {
        return ApiResponse.ok(service.withdraw(id, request));
    }

    @PostMapping("/{id}/transfer")
    public ApiResponse<RiskExceptionDtos.RequestRow> transfer(@PathVariable Long id,
            @Valid @RequestBody RiskExceptionDtos.Transfer request) {
        return ApiResponse.ok(service.transfer(id, request));
    }

    @PostMapping("/{id}/execute")
    public ApiResponse<Map<String, Long>> execute(@PathVariable Long id) {
        return ApiResponse.ok(Map.of("id", executionService.execute(id)));
    }
}
