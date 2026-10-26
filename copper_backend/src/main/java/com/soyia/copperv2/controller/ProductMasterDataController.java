package com.soyia.copperv2.controller;

import com.soyia.copperv2.dto.ApiResponse;
import com.soyia.copperv2.dto.ProductMasterDataDtos;
import com.soyia.copperv2.dto.SimpleCreateRequests;
import com.soyia.copperv2.service.ProductMasterDataService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class ProductMasterDataController {
    private final ProductMasterDataService productMasterDataService;

    public ProductMasterDataController(ProductMasterDataService productMasterDataService) {
        this.productMasterDataService = productMasterDataService;
    }

    @GetMapping("/product-reference-values")
    public ApiResponse<List<ProductMasterDataDtos.ReferenceValue>> referenceValues(
            @RequestParam(required = false) String referenceType,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status) {
        return ApiResponse.ok(productMasterDataService.referenceValues(referenceType, keyword, status));
    }

    @PostMapping("/product-reference-values")
    public ApiResponse<ProductMasterDataDtos.ReferenceValue> createReferenceValue(
            @Valid @RequestBody ProductMasterDataDtos.ReferenceValueSave request) {
        return ApiResponse.ok(productMasterDataService.createReferenceValue(request));
    }

    @PutMapping("/product-reference-values/{referenceValueId}")
    public ApiResponse<ProductMasterDataDtos.ReferenceValue> updateReferenceValue(
            @PathVariable Long referenceValueId,
            @Valid @RequestBody ProductMasterDataDtos.ReferenceValueSave request) {
        return ApiResponse.ok(productMasterDataService.updateReferenceValue(referenceValueId, request));
    }

    @PostMapping("/product-reference-values/resolve")
    public ApiResponse<ProductMasterDataDtos.ReferenceResolution> resolveReference(
            @Valid @RequestBody ProductMasterDataDtos.ReferenceResolveRequest request) {
        return ApiResponse.ok(productMasterDataService.resolveReference(request));
    }

    @PostMapping("/products/duplicate-check")
    public ApiResponse<ProductMasterDataDtos.ProductDuplicateCheck> duplicateCheck(
            @RequestParam(required = false) Long excludeProductId,
            @Valid @RequestBody SimpleCreateRequests.ProductCreate request) {
        return ApiResponse.ok(productMasterDataService.duplicateCheck(request, excludeProductId));
    }

    @GetMapping("/product-series/{seriesId}/allowed-units")
    public ApiResponse<List<ProductMasterDataDtos.AllowedUnit>> allowedUnits(
            @PathVariable Long seriesId,
            @RequestParam(required = false) String purpose) {
        return ApiResponse.ok(productMasterDataService.allowedUnits(seriesId, purpose));
    }

    @PostMapping("/product-series/{seriesId}/allowed-units")
    public ApiResponse<ProductMasterDataDtos.AllowedUnit> saveAllowedUnit(
            @PathVariable Long seriesId,
            @Valid @RequestBody ProductMasterDataDtos.AllowedUnitSave request) {
        return ApiResponse.ok(productMasterDataService.saveAllowedUnit(seriesId, request));
    }

    @DeleteMapping("/product-series/{seriesId}/allowed-units/{allowedUnitId}")
    public ApiResponse<Void> deleteAllowedUnit(
            @PathVariable Long seriesId, @PathVariable Long allowedUnitId) {
        productMasterDataService.deleteAllowedUnit(seriesId, allowedUnitId);
        return ApiResponse.ok(null);
    }

}
