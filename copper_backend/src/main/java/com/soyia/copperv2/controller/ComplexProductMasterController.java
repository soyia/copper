package com.soyia.copperv2.controller;

import com.soyia.copperv2.dto.ApiResponse;
import com.soyia.copperv2.dto.ProductMasterDataDtos;
import com.soyia.copperv2.service.ComplexProductMasterService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
public class ComplexProductMasterController {
    private final ComplexProductMasterService complexProductMasterService;

    public ComplexProductMasterController(ComplexProductMasterService complexProductMasterService) {
        this.complexProductMasterService = complexProductMasterService;
    }

    @GetMapping("/products/{productId}/packaging-variants")
    public ApiResponse<List<ProductMasterDataDtos.PackagingVariant>> packagingVariants(
            @PathVariable Long productId) {
        return ApiResponse.ok(complexProductMasterService.packagingVariants(productId));
    }

    @PostMapping("/products/{productId}/packaging-variants")
    public ApiResponse<ProductMasterDataDtos.PackagingVariant> createPackagingVariant(
            @PathVariable Long productId,
            @Valid @RequestBody ProductMasterDataDtos.PackagingVariantSave request) {
        return ApiResponse.ok(complexProductMasterService.createPackagingVariant(productId, request));
    }

    @PutMapping("/products/{productId}/packaging-variants/{variantId}")
    public ApiResponse<ProductMasterDataDtos.PackagingVariant> updatePackagingVariant(
            @PathVariable Long productId, @PathVariable Long variantId,
            @Valid @RequestBody ProductMasterDataDtos.PackagingVariantSave request) {
        return ApiResponse.ok(complexProductMasterService.updatePackagingVariant(productId, variantId, request));
    }

    @GetMapping("/products/{productId}/packaging-variants/{variantId}/revisions")
    public ApiResponse<List<ProductMasterDataDtos.PackagingVariantRevision>> packagingVariantRevisions(
            @PathVariable Long productId, @PathVariable Long variantId) {
        return ApiResponse.ok(complexProductMasterService.packagingVariantRevisions(
                productId, variantId));
    }

    @GetMapping("/products/{productId}/supplier-offers")
    public ApiResponse<List<ProductMasterDataDtos.SupplierOffer>> supplierOffers(
            @PathVariable Long productId,
            @RequestParam(required = false) Long supplierId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) LocalDate businessDate) {
        return ApiResponse.ok(complexProductMasterService.supplierOffers(
                productId, supplierId, status, businessDate));
    }

    @PostMapping("/products/{productId}/supplier-offers")
    public ApiResponse<ProductMasterDataDtos.SupplierOffer> createSupplierOffer(
            @PathVariable Long productId,
            @Valid @RequestBody ProductMasterDataDtos.SupplierOfferSave request) {
        return ApiResponse.ok(complexProductMasterService.createSupplierOffer(productId, request));
    }

    @PutMapping("/products/{productId}/supplier-offers/{offerId}")
    public ApiResponse<ProductMasterDataDtos.SupplierOffer> updateSupplierOffer(
            @PathVariable Long productId, @PathVariable Long offerId,
            @Valid @RequestBody ProductMasterDataDtos.SupplierOfferSave request) {
        return ApiResponse.ok(complexProductMasterService.updateSupplierOffer(productId, offerId, request));
    }

    @GetMapping("/products/{productId}/supplier-offers/{offerId}/revisions")
    public ApiResponse<List<ProductMasterDataDtos.SupplierOfferRevision>> supplierOfferRevisions(
            @PathVariable Long productId, @PathVariable Long offerId) {
        return ApiResponse.ok(complexProductMasterService.supplierOfferRevisions(productId, offerId));
    }

}
