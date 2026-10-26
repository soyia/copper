package com.soyia.copperv2.controller;

import com.soyia.copperv2.dto.ApiResponse;
import com.soyia.copperv2.dto.InventoryDtos;
import com.soyia.copperv2.dto.RiskExceptionDtos;
import com.soyia.copperv2.service.InventoryService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PutMapping;

import java.util.List;
import java.util.Map;

@RestController
public class InventoryController {
    private final InventoryService inventoryService;

    public InventoryController(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @GetMapping("/inventory/cost-policy")
    public ApiResponse<InventoryDtos.CostPolicy> costPolicy() {
        return ApiResponse.ok(inventoryService.costPolicy());
    }

    @GetMapping("/inventory/cost-opening-alignments")
    public ApiResponse<List<InventoryDtos.OpeningCostAlignment>> costOpeningAlignments() {
        return ApiResponse.ok(inventoryService.openingCostAlignments());
    }

    @GetMapping("/inventory/movements")
    public ApiResponse<List<InventoryDtos.Movement>> movements(
            @RequestParam(required = false) Long productId,
            @RequestParam(required = false) Long warehouseId,
            @RequestParam(required = false) Long batchId) {
        return ApiResponse.ok(inventoryService.movements(productId, warehouseId, batchId));
    }

    @GetMapping("/inventory/reconciliation")
    public ApiResponse<List<InventoryDtos.ReconciliationRow>> reconciliation(
            @RequestParam(required = false) Long productId,
            @RequestParam(required = false) Long warehouseId,
            @RequestParam(defaultValue = "false") boolean mismatchesOnly) {
        return ApiResponse.ok(inventoryService.reconciliation(productId, warehouseId, mismatchesOnly));
    }

    @GetMapping("/inventory/non-trade")
    public ApiResponse<List<InventoryDtos.NonTradeRow>> nonTradeDocuments(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long batchId) {
        return ApiResponse.ok(inventoryService.nonTradeDocuments(status, batchId));
    }

    @GetMapping("/inventory/non-trade/{id}")
    public ApiResponse<InventoryDtos.NonTradeDetail> nonTradeDocument(@PathVariable Long id) {
        return ApiResponse.ok(inventoryService.nonTradeDocument(id));
    }

    @PostMapping("/inventory/non-trade")
    public ApiResponse<Map<String, Long>> createNonTradeDraft(
            @Valid @RequestBody InventoryDtos.NonTradeCreate req) {
        return ApiResponse.ok(Map.of("id", inventoryService.createNonTradeDraft(req)));
    }

    @PostMapping("/inventory/non-trade/{id}/issue")
    public ApiResponse<Map<String, Long>> issueNonTrade(@PathVariable Long id) {
        return ApiResponse.ok(Map.of("id", inventoryService.issueNonTrade(id)));
    }

    @PostMapping("/inventory/non-trade/{id}/confirm")
    public ApiResponse<Map<String, Long>> confirmNonTrade(
            @PathVariable Long id, @Valid @RequestBody InventoryDtos.NonTradeConfirm req) {
        return ApiResponse.ok(Map.of("id", inventoryService.confirmNonTrade(id, req)));
    }

    @PostMapping("/inventory/non-trade/quick-confirm")
    public ApiResponse<Map<String, Long>> quickConfirmNonTrade(
            @Valid @RequestBody InventoryDtos.NonTradeCreate req) {
        return ApiResponse.ok(Map.of("id", inventoryService.quickConfirmNonTrade(req)));
    }

    @PostMapping("/inventory/non-trade/{id}/reverse")
    public ApiResponse<Map<String, Long>> reverseNonTrade(
            @PathVariable Long id, @Valid @RequestBody InventoryDtos.Action req) {
        return ApiResponse.ok(Map.of("id", inventoryService.reverseNonTrade(id, req.reason())));
    }

    @GetMapping("/inventory/transfers")
    public ApiResponse<List<InventoryDtos.TransferRow>> transfers(@RequestParam(required = false) String status) {
        return ApiResponse.ok(inventoryService.transfers(status));
    }

    @GetMapping("/inventory/transfers/{id}")
    public ApiResponse<InventoryDtos.TransferDetail> transfer(@PathVariable Long id) {
        return ApiResponse.ok(inventoryService.transfer(id));
    }

    @PostMapping("/inventory/transfers")
    public ApiResponse<Map<String, Long>> createTransfer(@Valid @RequestBody InventoryDtos.TransferCreate req) {
        return ApiResponse.ok(Map.of("id", inventoryService.createTransfer(req)));
    }

    @PutMapping("/inventory/transfers/{id}")
    public ApiResponse<Map<String, Long>> updateTransfer(@PathVariable Long id, @Valid @RequestBody InventoryDtos.TransferCreate req) {
        return ApiResponse.ok(Map.of("id", inventoryService.updateTransfer(id, req)));
    }

    @PostMapping("/inventory/transfers/{id}/dispatch")
    public ApiResponse<Map<String, Long>> dispatchTransfer(@PathVariable Long id) {
        return ApiResponse.ok(Map.of("id", inventoryService.dispatchTransfer(id)));
    }

    @PostMapping("/inventory/transfers/{id}/receive")
    public ApiResponse<Map<String, Long>> receiveTransfer(@PathVariable Long id) {
        return ApiResponse.ok(Map.of("id", inventoryService.receiveTransfer(id)));
    }

    @PostMapping("/inventory/transfers/{id}/cancel")
    public ApiResponse<Map<String, Long>> cancelTransfer(@PathVariable Long id, @Valid @RequestBody InventoryDtos.Action req) {
        return ApiResponse.ok(Map.of("id", inventoryService.cancelTransfer(id, req.reason())));
    }

    @PostMapping("/inventory/transfers/{id}/reverse")
    public ApiResponse<Map<String, Long>> reverseTransfer(@PathVariable Long id, @Valid @RequestBody InventoryDtos.Action req) {
        return ApiResponse.ok(Map.of("id", inventoryService.reverseTransfer(id, req.reason())));
    }

    @GetMapping("/inventory/stocktakes")
    public ApiResponse<List<InventoryDtos.StocktakeRow>> stocktakes(@RequestParam(required = false) String status) {
        return ApiResponse.ok(inventoryService.stocktakes(status));
    }

    @GetMapping("/inventory/stocktakes/{id}")
    public ApiResponse<InventoryDtos.StocktakeDetail> stocktake(@PathVariable Long id) {
        return ApiResponse.ok(inventoryService.stocktake(id));
    }

    @PostMapping("/inventory/stocktakes")
    public ApiResponse<Map<String, Long>> createStocktake(@Valid @RequestBody InventoryDtos.StocktakeCreate req) {
        return ApiResponse.ok(Map.of("id", inventoryService.createStocktake(req)));
    }

    @PutMapping("/inventory/stocktakes/{id}")
    public ApiResponse<Map<String, Long>> updateStocktake(@PathVariable Long id, @Valid @RequestBody InventoryDtos.StocktakeCreate req) {
        return ApiResponse.ok(Map.of("id", inventoryService.updateStocktake(id, req)));
    }

    @PostMapping("/inventory/stocktakes/{id}/confirm")
    public ApiResponse<Map<String, Long>> confirmStocktake(@PathVariable Long id,
            @RequestBody(required = false) RiskExceptionDtos.InlineAuthorization authorization) {
        return ApiResponse.ok(Map.of("id", inventoryService.confirmStocktake(id, authorization)));
    }

    @PostMapping("/inventory/stocktakes/{id}/cancel")
    public ApiResponse<Map<String, Long>> cancelStocktake(@PathVariable Long id, @Valid @RequestBody InventoryDtos.Action req) {
        return ApiResponse.ok(Map.of("id", inventoryService.cancelStocktake(id, req.reason())));
    }

    @PostMapping("/inventory/stocktakes/{id}/reverse")
    public ApiResponse<Map<String, Long>> reverseStocktake(@PathVariable Long id, @Valid @RequestBody InventoryDtos.Action req) {
        return ApiResponse.ok(Map.of("id", inventoryService.reverseStocktake(id, req.reason(), req.approvalId())));
    }

    @GetMapping("/inventory/purchase-returns")
    public ApiResponse<List<InventoryDtos.PurchaseReturnRow>> purchaseReturns(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long supplierId) {
        return ApiResponse.ok(inventoryService.purchaseReturns(status, supplierId));
    }

    @GetMapping("/inventory/purchase-returns/{id}")
    public ApiResponse<InventoryDtos.PurchaseReturnDetail> purchaseReturn(@PathVariable Long id) {
        return ApiResponse.ok(inventoryService.purchaseReturn(id));
    }

    @PostMapping("/inventory/purchase-returns")
    public ApiResponse<Map<String, Long>> createPurchaseReturn(@Valid @RequestBody InventoryDtos.PurchaseReturnCreate req) {
        return ApiResponse.ok(Map.of("id", inventoryService.createPurchaseReturn(req)));
    }

    @PutMapping("/inventory/purchase-returns/{id}")
    public ApiResponse<Map<String, Long>> updatePurchaseReturn(@PathVariable Long id, @Valid @RequestBody InventoryDtos.PurchaseReturnCreate req) {
        return ApiResponse.ok(Map.of("id", inventoryService.updatePurchaseReturn(id, req)));
    }

    @PostMapping("/inventory/purchase-returns/{id}/confirm")
    public ApiResponse<Map<String, Long>> confirmPurchaseReturn(@PathVariable Long id) {
        return ApiResponse.ok(Map.of("id", inventoryService.confirmPurchaseReturn(id)));
    }

    @PostMapping("/inventory/purchase-returns/{id}/cancel")
    public ApiResponse<Map<String, Long>> cancelPurchaseReturn(@PathVariable Long id, @Valid @RequestBody InventoryDtos.Action req) {
        return ApiResponse.ok(Map.of("id", inventoryService.cancelPurchaseReturn(id, req.reason())));
    }

    @PostMapping("/inventory/purchase-returns/{id}/reverse")
    public ApiResponse<Map<String, Long>> reversePurchaseReturn(@PathVariable Long id, @Valid @RequestBody InventoryDtos.Action req) {
        return ApiResponse.ok(Map.of("id", inventoryService.reversePurchaseReturn(id, req.reason())));
    }

    @GetMapping("/inventory/cost-adjustments")
    public ApiResponse<List<InventoryDtos.CostAdjustmentRow>> costAdjustments(@RequestParam(required = false) String status) {
        return ApiResponse.ok(inventoryService.costAdjustments(status));
    }

    @GetMapping("/inventory/cost-adjustments/{id}")
    public ApiResponse<InventoryDtos.CostAdjustmentDetail> costAdjustment(@PathVariable Long id) {
        return ApiResponse.ok(inventoryService.costAdjustment(id));
    }

    @PostMapping("/inventory/cost-adjustments")
    public ApiResponse<Map<String, Long>> createCostAdjustment(@Valid @RequestBody InventoryDtos.CostAdjustmentCreate req) {
        return ApiResponse.ok(Map.of("id", inventoryService.createCostAdjustment(req)));
    }

    @PutMapping("/inventory/cost-adjustments/{id}")
    public ApiResponse<Map<String, Long>> updateCostAdjustment(@PathVariable Long id, @Valid @RequestBody InventoryDtos.CostAdjustmentCreate req) {
        return ApiResponse.ok(Map.of("id", inventoryService.updateCostAdjustment(id, req)));
    }

    @PostMapping("/inventory/cost-adjustments/{id}/confirm")
    public ApiResponse<Map<String, Long>> confirmCostAdjustment(@PathVariable Long id,
            @RequestBody(required = false) RiskExceptionDtos.InlineAuthorization authorization) {
        return ApiResponse.ok(Map.of("id", inventoryService.confirmCostAdjustment(id, authorization)));
    }

    @PostMapping("/inventory/cost-adjustments/{id}/cancel")
    public ApiResponse<Map<String, Long>> cancelCostAdjustment(@PathVariable Long id, @Valid @RequestBody InventoryDtos.Action req) {
        return ApiResponse.ok(Map.of("id", inventoryService.cancelCostAdjustment(id, req.reason())));
    }

    @PostMapping("/inventory/cost-adjustments/{id}/reverse")
    public ApiResponse<Map<String, Long>> reverseCostAdjustment(@PathVariable Long id, @Valid @RequestBody InventoryDtos.Action req) {
        return ApiResponse.ok(Map.of("id", inventoryService.reverseCostAdjustment(id, req.reason(), req.approvalId())));
    }
}
