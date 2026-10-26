package com.soyia.copperv2.controller;

import com.soyia.copperv2.dto.ApiResponse;
import com.soyia.copperv2.dto.LightweightOperationsDtos;
import com.soyia.copperv2.service.LightweightOperationsService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
public class LightweightOperationsController {
    private final LightweightOperationsService service;

    public LightweightOperationsController(LightweightOperationsService service) {
        this.service = service;
    }

    @GetMapping("/operations/replenishment")
    public ApiResponse<List<LightweightOperationsDtos.ReplenishmentRow>> replenishment() {
        return ApiResponse.ok(service.replenishment());
    }

    @GetMapping("/operations/contributions")
    public ApiResponse<List<LightweightOperationsDtos.ContributionRow>> contributions(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String sourceType,
            @RequestParam(required = false) Long sourceId) {
        return ApiResponse.ok(service.contributions(from, to, sourceType, sourceId));
    }

    @GetMapping("/operations/owner-summary")
    public ApiResponse<LightweightOperationsDtos.OwnerSummary> ownerSummary() {
        return ApiResponse.ok(service.ownerSummary());
    }

    @GetMapping("/purchase-orders/{purchaseOrderId}/quotes")
    public ApiResponse<List<LightweightOperationsDtos.QuoteOption>> quotes(
            @PathVariable Long purchaseOrderId) {
        return ApiResponse.ok(service.quotes(purchaseOrderId));
    }

    @PostMapping("/purchase-orders/{purchaseOrderId}/quotes")
    public ApiResponse<Map<String, Long>> createQuote(
            @PathVariable Long purchaseOrderId,
            @Valid @RequestBody LightweightOperationsDtos.QuoteSave request) {
        return ApiResponse.ok(Map.of("id", service.createQuote(purchaseOrderId, request)));
    }

    @PostMapping("/purchase-orders/{purchaseOrderId}/quotes/{quoteId}/select")
    public ApiResponse<Map<String, Long>> selectQuote(
            @PathVariable Long purchaseOrderId, @PathVariable Long quoteId) {
        return ApiResponse.ok(Map.of("id", service.selectQuote(purchaseOrderId, quoteId)));
    }

    @PostMapping("/purchase-orders/{purchaseOrderId}/quotes/{quoteId}/void")
    public ApiResponse<Map<String, Long>> voidQuote(
            @PathVariable Long purchaseOrderId, @PathVariable Long quoteId,
            @Valid @RequestBody LightweightOperationsDtos.Action request) {
        return ApiResponse.ok(Map.of("id", service.voidQuote(purchaseOrderId, quoteId, request.reason())));
    }

    @GetMapping("/operations/expense-sources")
    public ApiResponse<List<LightweightOperationsDtos.ExpenseSource>> expenseSources() {
        return ApiResponse.ok(service.expenseSources());
    }

    @GetMapping("/operations/sales-costs/{sourceType}/{sourceId}")
    public ApiResponse<List<LightweightOperationsDtos.SalesCostAllocation>> salesCosts(
            @PathVariable String sourceType, @PathVariable Long sourceId) {
        return ApiResponse.ok(service.salesCostAllocations(sourceType, sourceId));
    }

    @PostMapping("/operations/sales-costs/{sourceType}/{sourceId}")
    public ApiResponse<Map<String, Long>> allocateSalesCost(
            @PathVariable String sourceType, @PathVariable Long sourceId,
            @Valid @RequestBody LightweightOperationsDtos.SalesCostSave request) {
        return ApiResponse.ok(Map.of("id", service.allocateSalesCost(sourceType, sourceId, request)));
    }

    @PostMapping("/operations/sales-costs/{sourceType}/{sourceId}/{allocationId}/reverse")
    public ApiResponse<Map<String, Long>> reverseSalesCost(
            @PathVariable String sourceType, @PathVariable Long sourceId,
            @PathVariable Long allocationId,
            @Valid @RequestBody LightweightOperationsDtos.Action request) {
        return ApiResponse.ok(Map.of("id", service.reverseSalesCost(
                sourceType, sourceId, allocationId, request.reason())));
    }

    @GetMapping("/outbounds/{outboundId}/delivery")
    public ApiResponse<LightweightOperationsDtos.Delivery> delivery(@PathVariable Long outboundId) {
        return ApiResponse.ok(service.delivery(outboundId));
    }

    @PostMapping("/outbounds/{outboundId}/delivery")
    public ApiResponse<LightweightOperationsDtos.Delivery> saveDelivery(
            @PathVariable Long outboundId,
            @Valid @RequestBody LightweightOperationsDtos.DeliverySave request) {
        return ApiResponse.ok(service.saveDelivery(outboundId, request));
    }

    @PostMapping("/outbounds/{outboundId}/delivery/action")
    public ApiResponse<LightweightOperationsDtos.Delivery> actDelivery(
            @PathVariable Long outboundId,
            @Valid @RequestBody LightweightOperationsDtos.DeliveryAction request) {
        return ApiResponse.ok(service.actDelivery(outboundId, request));
    }

    @GetMapping("/external-shares")
    public ApiResponse<List<LightweightOperationsDtos.ShareRow>> shares(
            @RequestParam String sourceType, @RequestParam Long sourceId) {
        return ApiResponse.ok(service.shares(sourceType, sourceId));
    }

    @PostMapping("/external-shares")
    public ApiResponse<LightweightOperationsDtos.ShareCreated> createShare(
            @Valid @RequestBody LightweightOperationsDtos.ShareCreate request) {
        return ApiResponse.ok(service.createShare(request));
    }

    @PostMapping("/external-shares/{id}/revoke")
    public ApiResponse<Map<String, Long>> revokeShare(
            @PathVariable Long id, @Valid @RequestBody LightweightOperationsDtos.Action request) {
        return ApiResponse.ok(Map.of("id", service.revokeShare(id, request.reason())));
    }

    @GetMapping("/public/order-shares/{token}")
    public ApiResponse<LightweightOperationsDtos.PublicOrder> publicShare(@PathVariable String token) {
        return ApiResponse.ok(service.publicSharedOrder(token));
    }

    @GetMapping("/integration-keys")
    public ApiResponse<List<LightweightOperationsDtos.ApiKeyRow>> apiKeys() {
        return ApiResponse.ok(service.apiKeys());
    }

    @PostMapping("/integration-keys")
    public ApiResponse<LightweightOperationsDtos.ApiKeyCreated> createApiKey(
            @Valid @RequestBody LightweightOperationsDtos.ApiKeyCreate request) {
        return ApiResponse.ok(service.createApiKey(request));
    }

    @PostMapping("/integration-keys/{id}/revoke")
    public ApiResponse<Map<String, Long>> revokeApiKey(
            @PathVariable Long id, @Valid @RequestBody LightweightOperationsDtos.Action request) {
        return ApiResponse.ok(Map.of("id", service.revokeApiKey(id, request.reason())));
    }

    @GetMapping("/open-api/v1/orders/{sourceType}/{sourceNo}")
    public ApiResponse<LightweightOperationsDtos.PublicOrder> openApiOrder(
            @RequestHeader("X-Copper-Key") String token,
            @PathVariable String sourceType, @PathVariable String sourceNo) {
        return ApiResponse.ok(service.openApiOrder(token, sourceType, sourceNo));
    }
}
