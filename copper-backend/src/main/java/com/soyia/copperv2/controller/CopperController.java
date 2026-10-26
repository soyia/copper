package com.soyia.copperv2.controller;

import com.soyia.copperv2.dto.ApiResponse;
import com.soyia.copperv2.dto.DashboardDto;
import com.soyia.copperv2.dto.InboundCreateRequest;
import com.soyia.copperv2.dto.InventoryBatchDto;
import com.soyia.copperv2.dto.AlignedDtos;
import com.soyia.copperv2.dto.MasterDataDtos;
import com.soyia.copperv2.dto.OrderDtos;
import com.soyia.copperv2.dto.OutboundCreateRequest;
import com.soyia.copperv2.dto.OutboundActionRequests;
import com.soyia.copperv2.dto.PackagingDtos;
import com.soyia.copperv2.dto.PartnerDto;
import com.soyia.copperv2.dto.PartnerSummaryDto;
import com.soyia.copperv2.dto.PaymentAllocationDtos;
import com.soyia.copperv2.dto.PaymentCreateRequest;
import com.soyia.copperv2.dto.ProductDto;
import com.soyia.copperv2.dto.ProductPurchaseOptionsDto;
import com.soyia.copperv2.dto.PurchaseCreateRequest;
import com.soyia.copperv2.dto.ReceivableSettlementDtos;
import com.soyia.copperv2.dto.PurchaseCancelRequest;
import com.soyia.copperv2.dto.SalesOrderCancelRequest;
import com.soyia.copperv2.dto.SalesOrderPrepaymentRequests;
import com.soyia.copperv2.dto.SimpleCreateRequests;
import com.soyia.copperv2.dto.SupplierPaymentCreateRequest;
import com.soyia.copperv2.dto.WarehouseDto;
import com.soyia.copperv2.dto.BusinessPostingDtos;
import com.soyia.copperv2.dto.SalesRiskDtos;
import com.soyia.copperv2.security.SecurityContext;
import com.soyia.copperv2.service.CopperService;
import com.soyia.copperv2.service.InboundAttachmentService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.math.BigDecimal;
import java.time.LocalDate;

@RestController
public class CopperController {
    private final CopperService copperService;
    private final InboundAttachmentService inboundAttachmentService;

    public CopperController(CopperService copperService, InboundAttachmentService inboundAttachmentService) {
        this.copperService = copperService;
        this.inboundAttachmentService = inboundAttachmentService;
    }

    @GetMapping("/dashboard")
    public ApiResponse<DashboardDto> dashboard() {
        return ApiResponse.ok(copperService.dashboard());
    }

    @GetMapping("/partners")
    public ApiResponse<List<PartnerDto>> partners(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Long id) {
        return ApiResponse.ok(copperService.partners(type, keyword, status, limit, id));
    }

    @GetMapping("/partners/summaries")
    public ApiResponse<List<PartnerSummaryDto>> partnerSummaries(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status) {
        return ApiResponse.ok(copperService.partnerSummaries(type, keyword, status));
    }

    @GetMapping("/partners/{id}")
    public ApiResponse<PartnerDto> partner(@PathVariable Long id) {
        return ApiResponse.ok(copperService.partner(id));
    }

    @PostMapping("/partners")
    public ApiResponse<Map<String, Long>> createPartner(@Valid @RequestBody SimpleCreateRequests.PartnerCreate req) {
        return ApiResponse.ok(Map.of("id", copperService.createPartner(req)));
    }

    @PutMapping("/partners/{id}")
    public ApiResponse<Map<String, Long>> updatePartner(@PathVariable Long id, @Valid @RequestBody SimpleCreateRequests.PartnerCreate req) {
        return ApiResponse.ok(Map.of("id", copperService.updatePartner(id, req)));
    }

    @GetMapping("/partners/{partnerId}/contacts")
    public ApiResponse<List<AlignedDtos.ContactRow>> contacts(@PathVariable Long partnerId) {
        return ApiResponse.ok(copperService.contacts(partnerId));
    }

    @PostMapping("/contacts")
    public ApiResponse<Map<String, Long>> createContact(@Valid @RequestBody AlignedDtos.ContactCreate req) {
        return ApiResponse.ok(Map.of("id", copperService.createContact(req)));
    }

    @PutMapping("/contacts/{id}")
    public ApiResponse<Map<String, Long>> updateContact(@PathVariable Long id, @Valid @RequestBody AlignedDtos.ContactCreate req) {
        return ApiResponse.ok(Map.of("id", copperService.updateContact(id, req)));
    }

    @GetMapping("/partners/{partnerId}/addresses")
    public ApiResponse<List<AlignedDtos.AddressRow>> addresses(@PathVariable Long partnerId) {
        return ApiResponse.ok(copperService.addresses(partnerId));
    }

    @PostMapping("/addresses")
    public ApiResponse<Map<String, Long>> createAddress(@Valid @RequestBody AlignedDtos.AddressCreate req) {
        return ApiResponse.ok(Map.of("id", copperService.createAddress(req)));
    }

    @PutMapping("/addresses/{id}")
    public ApiResponse<Map<String, Long>> updateAddress(@PathVariable Long id, @Valid @RequestBody AlignedDtos.AddressCreate req) {
        return ApiResponse.ok(Map.of("id", copperService.updateAddress(id, req)));
    }

    @GetMapping("/partners/{partnerId}/bank-accounts")
    public ApiResponse<List<AlignedDtos.BankAccountRow>> bankAccounts(@PathVariable Long partnerId) {
        return ApiResponse.ok(copperService.bankAccounts(partnerId));
    }

    @PostMapping("/bank-accounts")
    public ApiResponse<Map<String, Long>> createBankAccount(@Valid @RequestBody AlignedDtos.BankAccountCreate req) {
        return ApiResponse.ok(Map.of("id", copperService.createBankAccount(req)));
    }

    @PutMapping("/bank-accounts/{id}")
    public ApiResponse<Map<String, Long>> updateBankAccount(@PathVariable Long id, @Valid @RequestBody AlignedDtos.BankAccountCreate req) {
        return ApiResponse.ok(Map.of("id", copperService.updateBankAccount(id, req)));
    }

    @GetMapping("/partners/{partnerId}/followups")
    public ApiResponse<List<AlignedDtos.FollowupRow>> followups(@PathVariable Long partnerId) {
        return ApiResponse.ok(copperService.followups(partnerId));
    }

    @PostMapping("/followups")
    public ApiResponse<Map<String, Long>> createFollowup(@Valid @RequestBody AlignedDtos.FollowupCreate req) {
        return ApiResponse.ok(Map.of("id", copperService.createFollowup(req)));
    }

    @PutMapping("/followups/{id}")
    public ApiResponse<Map<String, Long>> updateFollowup(@PathVariable Long id, @Valid @RequestBody AlignedDtos.FollowupCreate req) {
        return ApiResponse.ok(Map.of("id", copperService.updateFollowup(id, req)));
    }

    @GetMapping("/partners/{partnerId}/special-dates")
    public ApiResponse<List<AlignedDtos.SpecialDateRow>> specialDates(@PathVariable Long partnerId) {
        return ApiResponse.ok(copperService.specialDates(partnerId));
    }

    @PostMapping("/special-dates")
    public ApiResponse<Map<String, Long>> createSpecialDate(@Valid @RequestBody AlignedDtos.SpecialDateCreate req) {
        return ApiResponse.ok(Map.of("id", copperService.createSpecialDate(req)));
    }

    @PutMapping("/special-dates/{id}")
    public ApiResponse<Map<String, Long>> updateSpecialDate(@PathVariable Long id, @Valid @RequestBody AlignedDtos.SpecialDateCreate req) {
        return ApiResponse.ok(Map.of("id", copperService.updateSpecialDate(id, req)));
    }

    @GetMapping("/products")
    public ApiResponse<List<ProductDto>> products() {
        return ApiResponse.ok(copperService.products());
    }

    @GetMapping("/products/{id}/purchase-options")
    public ApiResponse<ProductPurchaseOptionsDto> productPurchaseOptions(
            @PathVariable Long id,
            @RequestParam(required = false) Long supplierId,
            @RequestParam(required = false) String priceUnit,
            @RequestParam(required = false) BigDecimal quantity,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {
        return ApiResponse.ok(copperService.productPurchaseOptions(id, supplierId, priceUnit, quantity, businessDate));
    }

    @GetMapping("/products/{id}/unit-options")
    public ApiResponse<ProductPurchaseOptionsDto> productUnitOptions(
            @PathVariable Long id,
            @RequestParam(required = false) Long partnerId,
            @RequestParam(required = false) String partnerType,
            @RequestParam(required = false) String priceUnit,
            @RequestParam(required = false) BigDecimal quantity,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {
        return ApiResponse.ok(copperService.productUnitOptions(id, partnerId, partnerType, priceUnit, quantity, businessDate));
    }

    @PostMapping("/products")
    public ApiResponse<Map<String, Long>> createProduct(@Valid @RequestBody SimpleCreateRequests.ProductCreate req) {
        return ApiResponse.ok(Map.of("id", copperService.createProduct(req)));
    }

    @PutMapping("/products/{id}")
    public ApiResponse<Map<String, Long>> updateProduct(@PathVariable Long id, @Valid @RequestBody SimpleCreateRequests.ProductCreate req) {
        return ApiResponse.ok(Map.of("id", copperService.updateProduct(id, req)));
    }

    @GetMapping("/product-categories")
    public ApiResponse<List<MasterDataDtos.ProductCategoryRow>> productCategories() {
        return ApiResponse.ok(copperService.productCategories());
    }

    @PostMapping("/product-categories")
    public ApiResponse<Map<String, Long>> createProductCategory(@Valid @RequestBody MasterDataDtos.ProductCategoryCreate req) {
        return ApiResponse.ok(Map.of("id", copperService.createProductCategory(req)));
    }

    @PutMapping("/product-categories/{id}")
    public ApiResponse<Map<String, Long>> updateProductCategory(@PathVariable Long id, @Valid @RequestBody MasterDataDtos.ProductCategoryCreate req) {
        return ApiResponse.ok(Map.of("id", copperService.updateProductCategory(id, req)));
    }

    @GetMapping("/product-standards")
    public ApiResponse<List<MasterDataDtos.ProductStandardRow>> productStandards() {
        return ApiResponse.ok(copperService.productStandards());
    }

    @PostMapping("/product-standards")
    public ApiResponse<Map<String, Long>> createProductStandard(@Valid @RequestBody MasterDataDtos.ProductStandardCreate req) {
        return ApiResponse.ok(Map.of("id", copperService.createProductStandard(req)));
    }

    @PutMapping("/product-standards/{id}")
    public ApiResponse<Map<String, Long>> updateProductStandard(@PathVariable Long id, @Valid @RequestBody MasterDataDtos.ProductStandardCreate req) {
        return ApiResponse.ok(Map.of("id", copperService.updateProductStandard(id, req)));
    }

    @GetMapping("/product-tempers")
    public ApiResponse<List<MasterDataDtos.ProductTemperRow>> productTempers() {
        return ApiResponse.ok(copperService.productTempers());
    }

    @PostMapping("/product-tempers")
    public ApiResponse<Map<String, Long>> createProductTemper(@Valid @RequestBody MasterDataDtos.ProductTemperCreate req) {
        return ApiResponse.ok(Map.of("id", copperService.createProductTemper(req)));
    }

    @PutMapping("/product-tempers/{id}")
    public ApiResponse<Map<String, Long>> updateProductTemper(@PathVariable Long id, @Valid @RequestBody MasterDataDtos.ProductTemperCreate req) {
        return ApiResponse.ok(Map.of("id", copperService.updateProductTemper(id, req)));
    }

    @GetMapping("/custom-fields")
    public ApiResponse<List<MasterDataDtos.CustomFieldRow>> customFields() {
        return ApiResponse.ok(copperService.customFields());
    }

    @GetMapping("/custom-fields/{id}/usages")
    public ApiResponse<List<MasterDataDtos.CustomFieldUsageRow>> customFieldUsages(@PathVariable Long id) {
        return ApiResponse.ok(copperService.customFieldUsages(id));
    }

    @PostMapping("/custom-fields")
    public ApiResponse<Map<String, Long>> createCustomField(@Valid @RequestBody MasterDataDtos.CustomFieldCreate req) {
        return ApiResponse.ok(Map.of("id", copperService.createCustomField(req)));
    }

    @PutMapping("/custom-fields/{id}")
    public ApiResponse<Map<String, Long>> updateCustomField(@PathVariable Long id,
                                                            @Valid @RequestBody MasterDataDtos.CustomFieldCreate req) {
        return ApiResponse.ok(Map.of("id", copperService.updateCustomField(id, req)));
    }

    @GetMapping("/product-series/{seriesId}/fields")
    public ApiResponse<List<MasterDataDtos.SeriesFieldRow>> seriesFields(@PathVariable Long seriesId) {
        return ApiResponse.ok(copperService.seriesFields(seriesId));
    }

    @PostMapping("/product-series-fields")
    public ApiResponse<Map<String, Long>> createSeriesField(@Valid @RequestBody MasterDataDtos.SeriesFieldCreate req) {
        return ApiResponse.ok(Map.of("id", copperService.createSeriesField(req)));
    }

    @PutMapping("/product-series-fields/{id}")
    public ApiResponse<Map<String, Long>> updateSeriesField(@PathVariable Long id, @Valid @RequestBody MasterDataDtos.SeriesFieldCreate req) {
        return ApiResponse.ok(Map.of("id", copperService.updateSeriesField(id, req)));
    }

    @GetMapping("/product-spec-presets")
    public ApiResponse<List<MasterDataDtos.SpecPresetRow>> specPresets(@RequestParam(required = false) Long seriesId) {
        return ApiResponse.ok(copperService.specPresets(seriesId));
    }

    @PostMapping("/product-spec-presets")
    public ApiResponse<Map<String, Long>> createSpecPreset(@Valid @RequestBody MasterDataDtos.SpecPresetCreate req) {
        return ApiResponse.ok(Map.of("id", copperService.createSpecPreset(req)));
    }

    @PutMapping("/product-spec-presets/{id}")
    public ApiResponse<Map<String, Long>> updateSpecPreset(@PathVariable Long id, @Valid @RequestBody MasterDataDtos.SpecPresetCreate req) {
        return ApiResponse.ok(Map.of("id", copperService.updateSpecPreset(id, req)));
    }

    @GetMapping("/product-series")
    public ApiResponse<List<AlignedDtos.ProductSeriesRow>> productSeries() {
        return ApiResponse.ok(copperService.productSeries());
    }

    @PostMapping("/product-series")
    public ApiResponse<Map<String, Long>> createProductSeries(@Valid @RequestBody AlignedDtos.ProductSeriesCreate req) {
        return ApiResponse.ok(Map.of("id", copperService.createProductSeries(req)));
    }

    @PutMapping("/product-series/{id}")
    public ApiResponse<Map<String, Long>> updateProductSeries(@PathVariable Long id, @Valid @RequestBody AlignedDtos.ProductSeriesCreate req) {
        return ApiResponse.ok(Map.of("id", copperService.updateProductSeries(id, req)));
    }

    @GetMapping("/unit-categories")
    public ApiResponse<List<AlignedDtos.UnitCategoryRow>> unitCategories() {
        return ApiResponse.ok(copperService.unitCategories());
    }

    @PostMapping("/unit-categories")
    public ApiResponse<Map<String, Long>> createUnitCategory(@Valid @RequestBody AlignedDtos.UnitCategoryCreate req) {
        return ApiResponse.ok(Map.of("id", copperService.createUnitCategory(req)));
    }

    @PutMapping("/unit-categories/{id}")
    public ApiResponse<Map<String, Long>> updateUnitCategory(@PathVariable Long id, @Valid @RequestBody AlignedDtos.UnitCategoryCreate req) {
        return ApiResponse.ok(Map.of("id", copperService.updateUnitCategory(id, req)));
    }

    @GetMapping("/units")
    public ApiResponse<List<AlignedDtos.UnitRow>> units() {
        return ApiResponse.ok(copperService.units());
    }

    @PostMapping("/units")
    public ApiResponse<Map<String, Long>> createUnit(@Valid @RequestBody AlignedDtos.UnitCreate req) {
        return ApiResponse.ok(Map.of("id", copperService.createUnit(req)));
    }

    @PutMapping("/units/{id}")
    public ApiResponse<Map<String, Long>> updateUnit(@PathVariable Long id, @Valid @RequestBody AlignedDtos.UnitCreate req) {
        return ApiResponse.ok(Map.of("id", copperService.updateUnit(id, req)));
    }

    @GetMapping("/warehouses")
    public ApiResponse<List<WarehouseDto>> warehouses() {
        return ApiResponse.ok(copperService.warehouses());
    }

    @PostMapping("/warehouses")
    public ApiResponse<Map<String, Long>> createWarehouse(@Valid @RequestBody SimpleCreateRequests.WarehouseCreate req) {
        return ApiResponse.ok(Map.of("id", copperService.createWarehouse(req)));
    }

    @PutMapping("/warehouses/{id}")
    public ApiResponse<Map<String, Long>> updateWarehouse(@PathVariable Long id, @Valid @RequestBody SimpleCreateRequests.WarehouseCreate req) {
        return ApiResponse.ok(Map.of("id", copperService.updateWarehouse(id, req)));
    }

    @GetMapping("/inventory/batches")
    public ApiResponse<List<InventoryBatchDto>> inventoryBatches(
            @RequestParam(required = false) Long productId,
            @RequestParam(required = false) Long warehouseId,
            @RequestParam(required = false, defaultValue = "false") Boolean availableOnly
    ) {
        return ApiResponse.ok(copperService.inventoryBatches(productId, warehouseId, availableOnly));
    }

    @GetMapping("/inventory/batches/{batchId}/package-instances")
    public ApiResponse<List<PackagingDtos.PackageInstance>> inventoryPackageInstances(@PathVariable Long batchId) {
        return ApiResponse.ok(copperService.inventoryPackageInstances(batchId));
    }

    @GetMapping("/inbounds")
    public ApiResponse<List<OrderDtos.InboundRow>> inbounds(@RequestParam(required = false) Long supplierId) {
        return ApiResponse.ok(copperService.inbounds(supplierId));
    }

    @GetMapping("/inbounds/{id}")
    public ApiResponse<OrderDtos.InboundDetail> inboundDetail(@PathVariable Long id) {
        return ApiResponse.ok(copperService.inboundDetail(id));
    }

    @PostMapping("/inbounds")
    public ApiResponse<Map<String, Long>> createInbound(@Valid @RequestBody InboundCreateRequest req) {
        return ApiResponse.ok(Map.of("id", copperService.createInbound(req)));
    }

    @PutMapping("/inbounds/{id}")
    public ApiResponse<Map<String, Long>> updateInboundDraft(@PathVariable Long id,
                                                              @Valid @RequestBody InboundCreateRequest req) {
        return ApiResponse.ok(Map.of("id", copperService.updateInboundDraft(id, req)));
    }

    @PostMapping("/inbounds/{id}/submit")
    public ApiResponse<Map<String, Long>> submitInbound(@PathVariable Long id,
                                                         @RequestBody(required = false) Map<String, Object> body) {
        return ApiResponse.ok(Map.of("id", copperService.submitInbound(id, operatorName(body))));
    }

    @PostMapping("/inbounds/{id}/withdraw")
    public ApiResponse<Map<String, Long>> withdrawInbound(@PathVariable Long id,
                                                           @RequestBody(required = false) Map<String, Object> body) {
        return ApiResponse.ok(Map.of("id", copperService.withdrawInbound(id, operatorName(body))));
    }

    @GetMapping("/inbounds/{id}/attachments")
    public ApiResponse<List<OrderDtos.InboundAttachment>> inboundAttachments(@PathVariable Long id) {
        return ApiResponse.ok(inboundAttachmentService.list(id));
    }

    @PostMapping(value = "/inbounds/{id}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<OrderDtos.InboundAttachment> uploadInboundAttachment(
            @PathVariable Long id, @RequestParam("file") MultipartFile file) {
        return ApiResponse.ok(inboundAttachmentService.upload(id, file));
    }

    @GetMapping("/inbounds/{id}/attachments/{attachmentId}/content")
    public ResponseEntity<byte[]> inboundAttachmentContent(
            @PathVariable Long id, @PathVariable Long attachmentId) {
        InboundAttachmentService.AttachmentContent content = inboundAttachmentService.content(id, attachmentId);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(content.contentType())).body(content.bytes());
    }

    @DeleteMapping("/inbounds/{id}/attachments/{attachmentId}")
    public ApiResponse<Map<String, Long>> deleteInboundAttachment(
            @PathVariable Long id, @PathVariable Long attachmentId) {
        inboundAttachmentService.delete(id, attachmentId);
        return ApiResponse.ok(Map.of("id", attachmentId));
    }

    @PostMapping("/inbounds/{id}/audit")
    public ApiResponse<Map<String, Long>> auditInbound(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        boolean approved = !Boolean.FALSE.equals(body.get("approved"));
        String reason = body.get("reason") == null ? "" : String.valueOf(body.get("reason"));
        return ApiResponse.ok(Map.of("id", copperService.auditInbound(id, approved, reason, operatorName(body))));
    }

    @PostMapping("/inbounds/{id}/reverse")
    public ApiResponse<Map<String, Long>> reverseInbound(
            @PathVariable Long id, @Valid @RequestBody OutboundActionRequests.Reason req) {
        return ApiResponse.ok(Map.of("id", copperService.reverseInbound(id, req, SecurityContext.require().employeeName())));
    }

    @GetMapping("/inbounds/{id}/audit-logs")
    public ApiResponse<List<OrderDtos.InboundAuditLog>> inboundAuditLogs(@PathVariable Long id) {
        return ApiResponse.ok(copperService.inboundAuditLogs(id));
    }

    @GetMapping("/inbounds/{id}/audit-logs/{auditLogId}")
    public ApiResponse<OrderDtos.InboundAuditLogDetail> inboundAuditLogDetail(
            @PathVariable Long id, @PathVariable Long auditLogId) {
        return ApiResponse.ok(copperService.inboundAuditLogDetail(id, auditLogId));
    }

    @GetMapping("/inbounds/{id}/review-snapshots/{snapshotId}/attachments/{attachmentId}/content")
    public ResponseEntity<byte[]> inboundReviewSnapshotAttachmentContent(
            @PathVariable Long id, @PathVariable Long snapshotId, @PathVariable Long attachmentId) {
        InboundAttachmentService.AttachmentContent content =
                copperService.inboundReviewSnapshotAttachmentContent(id, snapshotId, attachmentId);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(content.contentType())).body(content.bytes());
    }

    private String operatorName(Map<String, Object> body) {
        if (body == null || body.get("operatorName") == null) return "系统用户";
        return String.valueOf(body.get("operatorName"));
    }

    @GetMapping("/purchase-orders")
    public ApiResponse<List<OrderDtos.PurchaseOrderRow>> purchaseOrders() {
        return ApiResponse.ok(copperService.purchaseOrders());
    }

    @GetMapping("/purchase-orders/{id}")
    public ApiResponse<OrderDtos.PurchaseOrderDetail> purchaseOrderDetail(@PathVariable Long id) {
        return ApiResponse.ok(copperService.purchaseOrderDetail(id));
    }

    @GetMapping("/purchase-orders/{id}/revisions")
    public ApiResponse<List<BusinessPostingDtos.OrderRevisionRow>> purchaseOrderRevisions(@PathVariable Long id) {
        return ApiResponse.ok(copperService.orderRevisions("PURCHASE_ORDER", id));
    }

    @PostMapping("/purchase-orders")
    public ApiResponse<Map<String, Long>> createPurchaseOrder(@Valid @RequestBody PurchaseCreateRequest req) {
        return ApiResponse.ok(Map.of("id", copperService.createPurchaseOrder(req)));
    }

    @PutMapping("/purchase-orders/{id}")
    public ApiResponse<Map<String, Long>> updatePurchaseOrder(@PathVariable Long id,
                                                              @Valid @RequestBody PurchaseCreateRequest req) {
        return ApiResponse.ok(Map.of("id", copperService.updatePurchaseOrder(id, req)));
    }

    @PostMapping("/purchase-orders/{id}/confirm")
    public ApiResponse<Map<String, Long>> confirmPurchaseOrder(@PathVariable Long id) {
        return ApiResponse.ok(Map.of("id", copperService.confirmPurchaseOrder(id)));
    }

    @PostMapping("/purchase-orders/{id}/amendments")
    public ApiResponse<Map<String, Long>> amendPurchaseOrder(
            @PathVariable Long id, @Valid @RequestBody BusinessPostingDtos.PurchaseAmendment req) {
        return ApiResponse.ok(Map.of("id", copperService.amendPurchaseOrder(id, req)));
    }

    @PostMapping("/purchase-orders/{id}/amendments/items/{itemId}/packaging-plan")
    public ApiResponse<PackagingDtos.Plan> purchaseAmendmentPackagingPlan(
            @PathVariable Long id, @PathVariable Long itemId,
            @Valid @RequestBody PackagingDtos.PlanRequest req) {
        return ApiResponse.ok(copperService.purchaseAmendmentPackagingPlan(id, itemId, req));
    }

    @PostMapping("/purchase-orders/{id}/cancel")
    public ApiResponse<Map<String, Long>> cancelPurchaseOrder(@PathVariable Long id,
                                                              @Valid @RequestBody PurchaseCancelRequest req) {
        return ApiResponse.ok(Map.of("id", copperService.cancelPurchaseOrder(id, req.reason())));
    }

    @GetMapping("/sales-orders")
    public ApiResponse<List<AlignedDtos.SalesOrderRow>> salesOrders() {
        return ApiResponse.ok(copperService.salesOrders());
    }

    @GetMapping("/sales-orders/{id}")
    public ApiResponse<OrderDtos.SalesOrderDetail> salesOrderDetail(@PathVariable Long id) {
        return ApiResponse.ok(copperService.salesOrderDetail(id));
    }

    @GetMapping("/sales-orders/{id}/revisions")
    public ApiResponse<List<BusinessPostingDtos.OrderRevisionRow>> salesOrderRevisions(@PathVariable Long id) {
        return ApiResponse.ok(copperService.orderRevisions("SALES_ORDER", id));
    }

    @PostMapping("/sales-orders")
    public ApiResponse<Map<String, Long>> createSalesOrder(@Valid @RequestBody AlignedDtos.SalesOrderCreate req) {
        return ApiResponse.ok(Map.of("id", copperService.createSalesOrder(req)));
    }

    @PutMapping("/sales-orders/{id}")
    public ApiResponse<Map<String, Long>> updateSalesOrder(@PathVariable Long id,
                                                           @Valid @RequestBody AlignedDtos.SalesOrderCreate req) {
        return ApiResponse.ok(Map.of("id", copperService.updateSalesOrder(id, req)));
    }

    @PostMapping("/sales-orders/{id}/confirm")
    public ApiResponse<Map<String, Long>> confirmSalesOrder(
            @PathVariable Long id, @RequestBody(required = false) SalesRiskDtos.OverrideRequest request) {
        return ApiResponse.ok(Map.of("id", copperService.confirmSalesOrder(
                id, request == null ? null : request.reason(), request == null ? null : request.approvalId())));
    }

    @PostMapping("/sales-orders/{id}/amendments")
    public ApiResponse<Map<String, Long>> amendSalesOrder(
            @PathVariable Long id, @Valid @RequestBody BusinessPostingDtos.SalesAmendment req) {
        return ApiResponse.ok(Map.of("id", copperService.amendSalesOrder(id, req)));
    }

    @PostMapping("/sales-orders/{id}/amendments/items/{itemId}/packaging-plan")
    public ApiResponse<PackagingDtos.Plan> salesAmendmentPackagingPlan(
            @PathVariable Long id, @PathVariable Long itemId,
            @Valid @RequestBody PackagingDtos.PlanRequest req) {
        return ApiResponse.ok(copperService.salesAmendmentPackagingPlan(id, itemId, req));
    }

    @PostMapping("/sales-orders/{id}/cancel")
    public ApiResponse<Map<String, Long>> cancelSalesOrder(@PathVariable Long id,
                                                           @Valid @RequestBody SalesOrderCancelRequest req) {
        return ApiResponse.ok(Map.of("id", copperService.cancelSalesOrder(id, req.reason())));
    }

    @GetMapping("/sales-orders/{id}/prepayments")
    public ApiResponse<OrderDtos.SalesOrderPrepaymentSummary> salesOrderPrepayments(@PathVariable Long id) {
        return ApiResponse.ok(copperService.salesOrderPrepayments(id));
    }

    @PostMapping("/sales-orders/{id}/prepayments/receive")
    public ApiResponse<Map<String, Long>> receiveSalesOrderPrepayment(
            @PathVariable Long id,
            @Valid @RequestBody SalesOrderPrepaymentRequests.Receive req) {
        return ApiResponse.ok(Map.of("id", copperService.receiveSalesOrderPrepayment(id, req)));
    }

    @PostMapping("/sales-orders/{id}/prepayments/reserve")
    public ApiResponse<Map<String, Long>> reserveSalesOrderPrepayment(
            @PathVariable Long id,
            @Valid @RequestBody SalesOrderPrepaymentRequests.Reserve req) {
        return ApiResponse.ok(Map.of("id", copperService.reserveSalesOrderPrepayment(id, req)));
    }

    @PostMapping("/sales-orders/{id}/prepayments/{allocationId}/release")
    public ApiResponse<Map<String, Long>> releaseSalesOrderPrepayment(
            @PathVariable Long id,
            @PathVariable Long allocationId) {
        copperService.releaseSalesOrderPrepayment(id, allocationId);
        return ApiResponse.ok(Map.of("id", allocationId));
    }

    @GetMapping("/outbounds")
    public ApiResponse<List<OrderDtos.OutboundRow>> outbounds(
            @RequestParam(required = false) Long customerId,
            @RequestParam(required = false) Long salesOrderId) {
        return ApiResponse.ok(copperService.outbounds(customerId, salesOrderId));
    }

    @GetMapping("/outbounds/{id}")
    public ApiResponse<OrderDtos.OutboundDetail> outboundDetail(@PathVariable Long id) {
        return ApiResponse.ok(copperService.outboundDetail(id));
    }

    @PostMapping("/outbounds")
    public ApiResponse<Map<String, Long>> createOutbound(@Valid @RequestBody OutboundCreateRequest req) {
        return ApiResponse.ok(Map.of("id", copperService.createOutbound(req)));
    }

    @PutMapping("/outbounds/{id}")
    public ApiResponse<Map<String, Long>> updateOutboundDraft(
            @PathVariable Long id, @Valid @RequestBody OutboundCreateRequest req) {
        return ApiResponse.ok(Map.of("id", copperService.updateOutboundDraft(id, req)));
    }

    @PostMapping("/outbounds/{id}/cancel")
    public ApiResponse<Map<String, Long>> cancelOutboundDraft(
            @PathVariable Long id, @Valid @RequestBody OutboundActionRequests.Reason req) {
        return ApiResponse.ok(Map.of("id", copperService.cancelOutboundDraft(id, req.reason())));
    }

    @PostMapping("/outbounds/{id}/complete-information")
    public ApiResponse<Map<String, Long>> completeOutboundInformation(
            @PathVariable Long id, @RequestBody Map<String, String> req) {
        return ApiResponse.ok(Map.of("id", copperService.completeOutboundInformation(id, req.get("memo"))));
    }

    @PostMapping("/outbounds/{id}/confirm")
    public ApiResponse<Map<String, Long>> confirmOutbound(
            @PathVariable Long id, @RequestBody(required = false) SalesRiskDtos.OverrideRequest request) {
        return ApiResponse.ok(Map.of("id", copperService.confirmOutbound(
                id, request == null ? null : request.reason(), request == null ? null : request.approvalId())));
    }

    @PostMapping("/outbounds/{id}/settle")
    public ApiResponse<Map<String, Long>> settleOutbound(
            @PathVariable Long id, @Valid @RequestBody OutboundActionRequests.Settlement req) {
        return ApiResponse.ok(Map.of("id", copperService.settleOutbound(id, req)));
    }

    @PostMapping("/outbounds/{id}/reverse")
    public ApiResponse<Map<String, Long>> reverseOutbound(
            @PathVariable Long id, @Valid @RequestBody OutboundActionRequests.Reason req) {
        return ApiResponse.ok(Map.of("id", copperService.reverseOutbound(id, req)));
    }

    @GetMapping("/sales-returns")
    public ApiResponse<List<OutboundActionRequests.SalesReturnRow>> salesReturns(
            @RequestParam(required = false) Long outboundId) {
        return ApiResponse.ok(copperService.salesReturns(outboundId));
    }

    @PostMapping("/sales-returns")
    public ApiResponse<Map<String, Long>> createSalesReturn(
            @Valid @RequestBody OutboundActionRequests.SalesReturnCreate req) {
        return ApiResponse.ok(Map.of("id", copperService.createSalesReturn(req)));
    }

    @PostMapping("/sales-returns/{id}/reverse")
    public ApiResponse<Map<String, Long>> reverseSalesReturn(
            @PathVariable Long id, @Valid @RequestBody OutboundActionRequests.Reason req) {
        return ApiResponse.ok(Map.of("id", copperService.reverseSalesReturn(id, req)));
    }

    @GetMapping("/payments")
    public ApiResponse<List<OrderDtos.PaymentRow>> payments(
            @RequestParam(required = false) Long customerId,
            @RequestParam(required = false) Long salesOrderId,
            @RequestParam(required = false) Long outboundId
    ) {
        return ApiResponse.ok(copperService.payments(customerId, salesOrderId, outboundId));
    }

    @GetMapping("/receivables")
    public ApiResponse<List<OrderDtos.ReceivableRow>> receivables(@RequestParam(required = false) Long customerId) {
        return ApiResponse.ok(copperService.receivables(customerId));
    }

    @PostMapping("/payments")
    public ApiResponse<Map<String, Long>> createPayment(@Valid @RequestBody PaymentCreateRequest req) {
        return ApiResponse.ok(Map.of("id", copperService.createPayment(req)));
    }

    @GetMapping("/payments/{id}/allocation")
    public ApiResponse<PaymentAllocationDtos.Detail> paymentAllocationDetail(@PathVariable Long id) {
        return ApiResponse.ok(copperService.paymentAllocationDetail(id));
    }

    @PostMapping("/payments/{id}/allocation")
    public ApiResponse<PaymentAllocationDtos.AllocationSummary> changePaymentAllocation(
            @PathVariable Long id, @Valid @RequestBody PaymentAllocationDtos.AllocationChange req) {
        return ApiResponse.ok(copperService.changePaymentAllocations(id, req));
    }

    @PostMapping("/payments/{id}/reverse")
    public ApiResponse<Map<String, Long>> reversePayment(
            @PathVariable Long id, @Valid @RequestBody OutboundActionRequests.Reason req) {
        return ApiResponse.ok(Map.of("id", copperService.reversePayment(id, req)));
    }

    @GetMapping("/receivable-adjustments")
    public ApiResponse<List<ReceivableSettlementDtos.AdjustmentRow>> receivableAdjustments(
            @RequestParam(required = false) Long customerId,
            @RequestParam(required = false) Long salesOrderId
    ) {
        return ApiResponse.ok(copperService.receivableAdjustments(customerId, salesOrderId));
    }

    @PostMapping("/receivable-adjustments")
    public ApiResponse<Map<String, Long>> createReceivableAdjustment(
            @Valid @RequestBody ReceivableSettlementDtos.AdjustmentCreate req
    ) {
        return ApiResponse.ok(Map.of("id", copperService.createReceivableAdjustment(req)));
    }

    @PostMapping("/receivable-adjustments/{id}/confirm")
    public ApiResponse<Map<String, Long>> confirmReceivableAdjustment(@PathVariable Long id) {
        return ApiResponse.ok(Map.of("id", copperService.confirmReceivableAdjustment(id)));
    }

    @PostMapping("/receivable-adjustments/{id}/cancel")
    public ApiResponse<Map<String, Long>> cancelReceivableAdjustment(
            @PathVariable Long id,
            @Valid @RequestBody ReceivableSettlementDtos.AdjustmentAction req
    ) {
        return ApiResponse.ok(Map.of("id", copperService.cancelReceivableAdjustment(id, req)));
    }

    @PostMapping("/receivable-adjustments/{id}/reverse")
    public ApiResponse<Map<String, Long>> reverseReceivableAdjustment(
            @PathVariable Long id,
            @Valid @RequestBody ReceivableSettlementDtos.AdjustmentAction req
    ) {
        return ApiResponse.ok(Map.of("id", copperService.reverseReceivableAdjustment(id, req)));
    }

    @GetMapping("/acceptance-bills")
    public ApiResponse<List<ReceivableSettlementDtos.AcceptanceBillRow>> acceptanceBills(
            @RequestParam(required = false) Long customerId,
            @RequestParam(required = false) Long salesOrderId
    ) {
        return ApiResponse.ok(copperService.acceptanceBills(customerId, salesOrderId));
    }

    @PostMapping("/acceptance-bills")
    public ApiResponse<Map<String, Long>> createAcceptanceBill(
            @Valid @RequestBody ReceivableSettlementDtos.AcceptanceCreate req
    ) {
        return ApiResponse.ok(Map.of("id", copperService.createAcceptanceBill(req)));
    }

    @GetMapping("/acceptance-bills/{id}/events")
    public ApiResponse<List<ReceivableSettlementDtos.AcceptanceEventRow>> acceptanceBillEvents(@PathVariable Long id) {
        return ApiResponse.ok(copperService.acceptanceBillEvents(id));
    }

    @PostMapping("/acceptance-bills/{id}/discount")
    public ApiResponse<Map<String, Long>> discountAcceptanceBill(
            @PathVariable Long id,
            @Valid @RequestBody ReceivableSettlementDtos.AcceptanceAction req
    ) {
        return ApiResponse.ok(Map.of("id", copperService.discountAcceptanceBill(id, req)));
    }

    @PostMapping("/acceptance-bills/{id}/mature")
    public ApiResponse<Map<String, Long>> matureAcceptanceBill(
            @PathVariable Long id,
            @Valid @RequestBody ReceivableSettlementDtos.AcceptanceAction req
    ) {
        return ApiResponse.ok(Map.of("id", copperService.matureAcceptanceBill(id, req)));
    }

    @PostMapping("/acceptance-bills/{id}/endorse")
    public ApiResponse<Map<String, Long>> endorseAcceptanceBill(
            @PathVariable Long id,
            @Valid @RequestBody ReceivableSettlementDtos.AcceptanceAction req
    ) {
        return ApiResponse.ok(Map.of("id", copperService.endorseAcceptanceBill(id, req)));
    }

    @PostMapping("/acceptance-bills/{id}/bounce")
    public ApiResponse<Map<String, Long>> bounceAcceptanceBill(
            @PathVariable Long id,
            @Valid @RequestBody ReceivableSettlementDtos.AcceptanceAction req
    ) {
        return ApiResponse.ok(Map.of("id", copperService.bounceAcceptanceBill(id, req)));
    }

    @GetMapping("/supplier-payments")
    public ApiResponse<List<OrderDtos.SupplierPaymentRow>> supplierPayments(
            @RequestParam(required = false) Long supplierId,
            @RequestParam(required = false) Long purchaseOrderId,
            @RequestParam(required = false) Long inboundId
    ) {
        return ApiResponse.ok(copperService.supplierPayments(supplierId, purchaseOrderId, inboundId));
    }

    @PostMapping("/supplier-payments")
    public ApiResponse<Map<String, Long>> createSupplierPayment(@Valid @RequestBody SupplierPaymentCreateRequest req) {
        return ApiResponse.ok(Map.of("id", copperService.createSupplierPayment(req)));
    }

    @GetMapping("/supplier-payments/{id}/allocation")
    public ApiResponse<PaymentAllocationDtos.Detail> supplierPaymentAllocationDetail(@PathVariable Long id) {
        return ApiResponse.ok(copperService.supplierPaymentAllocationDetail(id));
    }

    @PostMapping("/supplier-payments/{id}/allocation")
    public ApiResponse<PaymentAllocationDtos.AllocationSummary> changeSupplierPaymentAllocation(
            @PathVariable Long id, @Valid @RequestBody PaymentAllocationDtos.AllocationChange req) {
        return ApiResponse.ok(copperService.changeSupplierPaymentAllocations(id, req));
    }

    @PostMapping("/supplier-payments/{id}/reverse")
    public ApiResponse<Map<String, Long>> reverseSupplierPayment(
            @PathVariable Long id, @Valid @RequestBody OutboundActionRequests.Reason req) {
        return ApiResponse.ok(Map.of("id", copperService.reverseSupplierPayment(id, req)));
    }

    @GetMapping("/payables")
    public ApiResponse<List<OrderDtos.PayableRow>> payables(@RequestParam(required = false) Long supplierId) {
        return ApiResponse.ok(copperService.payables(supplierId));
    }

    @GetMapping("/partner-accounts")
    public ApiResponse<List<OrderDtos.PartnerAccountRow>> partnerAccounts(@RequestParam(required = false) Long partnerId) {
        return ApiResponse.ok(copperService.partnerAccounts(partnerId));
    }

    @GetMapping("/partner-account-logs")
    public ApiResponse<List<OrderDtos.PartnerAccountLogRow>> partnerAccountLogs(@RequestParam(required = false) Long partnerId) {
        return ApiResponse.ok(copperService.partnerAccountLogs(partnerId));
    }

    @GetMapping("/customer-advances")
    public ApiResponse<List<OrderDtos.CustomerAdvanceRow>> customerAdvances() {
        return ApiResponse.ok(copperService.customerAdvances());
    }

    @GetMapping("/customer-advance-refunds")
    public ApiResponse<List<OrderDtos.CustomerAdvanceRefundRow>> customerAdvanceRefunds(
            @RequestParam(required = false) Long customerId) {
        return ApiResponse.ok(copperService.customerAdvanceRefunds(customerId));
    }

    @PostMapping("/customer-advance-refunds")
    public ApiResponse<Map<String, Long>> refundCustomerAdvance(
            @Valid @RequestBody SalesOrderPrepaymentRequests.Refund req) {
        return ApiResponse.ok(Map.of("id", copperService.refundCustomerAdvance(req)));
    }

    @PostMapping("/customer-advance-refunds/{id}/reverse")
    public ApiResponse<Map<String, Long>> reverseCustomerAdvanceRefund(
            @PathVariable Long id, @Valid @RequestBody OutboundActionRequests.Reason req) {
        return ApiResponse.ok(Map.of("id", copperService.reverseCustomerAdvanceRefund(id, req)));
    }
}
