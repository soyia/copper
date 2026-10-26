package com.soyia.copperv2.controller;

import com.soyia.copperv2.dto.ApiResponse;
import com.soyia.copperv2.dto.PackagingDtos;
import com.soyia.copperv2.service.PackagingService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class PackagingController {
    private final PackagingService packagingService;

    public PackagingController(PackagingService packagingService) {
        this.packagingService = packagingService;
    }

    @GetMapping("/products/{productId}/packaging-profile")
    public ApiResponse<PackagingDtos.Profile> profile(@PathVariable Long productId) {
        return ApiResponse.ok(packagingService.profile(productId));
    }

    @GetMapping("/products/{productId}/package-definitions")
    public ApiResponse<List<PackagingDtos.PackageDefinition>> packageDefinitions(@PathVariable Long productId) {
        return ApiResponse.ok(packagingService.packageDefinitions(productId));
    }

    @PutMapping("/products/{productId}/package-definitions")
    public ApiResponse<List<PackagingDtos.PackageDefinition>> savePackageDefinitions(
            @PathVariable Long productId,
            @Valid @RequestBody PackagingDtos.PackageDefinitionsSave request) {
        return ApiResponse.ok(packagingService.savePackageDefinitions(productId, request));
    }

    @GetMapping("/products/package-barcode/{barcode}")
    public ApiResponse<PackagingDtos.PackageBarcodeMatch> packageBarcode(@PathVariable String barcode) {
        return ApiResponse.ok(packagingService.packageBarcode(barcode));
    }

    @PutMapping("/products/{productId}/packaging-profile")
    public ApiResponse<PackagingDtos.Profile> saveProfile(
            @PathVariable Long productId,
            @Valid @RequestBody PackagingDtos.ProfileSave request) {
        return ApiResponse.ok(packagingService.saveProfile(productId, request));
    }

    @PostMapping("/products/{productId}/packaging-plan")
    public ApiResponse<PackagingDtos.Plan> plan(
            @PathVariable Long productId,
            @Valid @RequestBody PackagingDtos.PlanRequest request) {
        return ApiResponse.ok(packagingService.plan(productId, request));
    }

    @GetMapping("/inventory/batches/{batchId}/packaging-profile")
    public ApiResponse<PackagingDtos.Profile> batchProfile(@PathVariable Long batchId) {
        return ApiResponse.ok(packagingService.batchProfile(batchId));
    }

    @PostMapping("/inventory/batches/{batchId}/packaging-plan")
    public ApiResponse<PackagingDtos.Plan> batchPlan(
            @PathVariable Long batchId,
            @Valid @RequestBody PackagingDtos.PlanRequest request) {
        return ApiResponse.ok(packagingService.planForBatch(batchId, request));
    }

    @GetMapping("/packaging-templates")
    public ApiResponse<List<PackagingDtos.TemplateRow>> templates() {
        return ApiResponse.ok(packagingService.templates());
    }

    @PostMapping("/packaging-templates")
    public ApiResponse<PackagingDtos.TemplateRow> createTemplate(@Valid @RequestBody PackagingDtos.TemplateSave request) {
        return ApiResponse.ok(packagingService.createTemplate(request));
    }

    @GetMapping("/packaging-templates/{templateId}")
    public ApiResponse<PackagingDtos.TemplateDetail> templateDetail(@PathVariable Long templateId) {
        return ApiResponse.ok(packagingService.templateDetail(templateId));
    }

    @GetMapping("/packaging-templates/{templateId}/history")
    public ApiResponse<List<PackagingDtos.TemplateRevisionRow>> templateHistory(@PathVariable Long templateId) {
        return ApiResponse.ok(packagingService.templateHistory(templateId));
    }

    @GetMapping("/packaging-templates/{templateId}/revisions/{revisionId}")
    public ApiResponse<PackagingDtos.Profile> templateRevision(@PathVariable Long templateId,
                                                               @PathVariable Long revisionId) {
        return ApiResponse.ok(packagingService.templateRevision(templateId, revisionId));
    }

    @PutMapping("/packaging-templates/{templateId}/metadata")
    public ApiResponse<PackagingDtos.TemplateRow> updateTemplateMetadata(
            @PathVariable Long templateId,
            @Valid @RequestBody PackagingDtos.TemplateMetadataSave request) {
        return ApiResponse.ok(packagingService.updateTemplateMetadata(templateId, request));
    }

    @PutMapping("/packaging-templates/{templateId}/status")
    public ApiResponse<PackagingDtos.TemplateRow> updateTemplateStatus(
            @PathVariable Long templateId,
            @Valid @RequestBody PackagingDtos.TemplateStatusSave request) {
        return ApiResponse.ok(packagingService.updateTemplateStatus(templateId, request));
    }

    @PostMapping("/packaging-templates/{templateId}/publish")
    public ApiResponse<PackagingDtos.TemplateRow> publishTemplate(@PathVariable Long templateId,
                                                                   @Valid @RequestBody PackagingDtos.TemplatePublish request) {
        return ApiResponse.ok(packagingService.publishTemplate(templateId, request));
    }

    @GetMapping("/product-series/packaging-bindings")
    public ApiResponse<List<PackagingDtos.SeriesBinding>> seriesBindings() {
        return ApiResponse.ok(packagingService.seriesBindings());
    }

    @GetMapping("/product-series/{seriesId}/packaging-binding")
    public ApiResponse<PackagingDtos.SeriesBinding> seriesBinding(@PathVariable Long seriesId) {
        return ApiResponse.ok(packagingService.seriesBinding(seriesId));
    }

    @PutMapping("/product-series/{seriesId}/packaging-binding")
    public ApiResponse<PackagingDtos.SeriesBinding> saveSeriesBinding(@PathVariable Long seriesId,
                                                                       @Valid @RequestBody PackagingDtos.SeriesBindingSave request) {
        return ApiResponse.ok(packagingService.saveSeriesBinding(seriesId, request));
    }

    @GetMapping("/products/{productId}/packaging-profile/inherited")
    public ApiResponse<PackagingDtos.Profile> inheritedProfile(@PathVariable Long productId) {
        return ApiResponse.ok(packagingService.inheritedProfile(productId));
    }

    @DeleteMapping("/products/{productId}/packaging-override")
    public ApiResponse<PackagingDtos.Profile> restoreOverride(@PathVariable Long productId,
                                                               @RequestParam(required = false) Integer expectedVersionNo,
                                                               @RequestParam(required = false) String reason) {
        return ApiResponse.ok(packagingService.restoreOverride(productId, expectedVersionNo, reason));
    }

    @GetMapping("/products/{productId}/packaging-history")
    public ApiResponse<List<PackagingDtos.HistoryRow>> productHistory(@PathVariable Long productId) {
        return ApiResponse.ok(packagingService.productHistory(productId));
    }

    @GetMapping("/products/packaging-summaries")
    public ApiResponse<List<PackagingDtos.ProductSummary>> productSummaries() {
        return ApiResponse.ok(packagingService.productSummaries());
    }
}
