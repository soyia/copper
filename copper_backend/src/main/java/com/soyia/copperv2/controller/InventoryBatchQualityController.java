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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class InventoryBatchQualityController {
    private final ComplexProductMasterService qualityService;

    public InventoryBatchQualityController(ComplexProductMasterService qualityService) {
        this.qualityService = qualityService;
    }

    @GetMapping("/inventory/batches/{batchId}/quality-certificates")
    public ApiResponse<List<ProductMasterDataDtos.QualityCertificate>> qualityCertificates(
            @PathVariable Long batchId) {
        return ApiResponse.ok(qualityService.qualityCertificates(batchId));
    }

    @PostMapping("/inventory/batches/{batchId}/quality-certificates")
    public ApiResponse<ProductMasterDataDtos.QualityCertificate> createQualityCertificate(
            @PathVariable Long batchId,
            @Valid @RequestBody ProductMasterDataDtos.QualityCertificateSave request) {
        return ApiResponse.ok(qualityService.saveQualityCertificate(batchId, null, request));
    }

    @PutMapping("/inventory/batches/{batchId}/quality-certificates/{certificateId}")
    public ApiResponse<ProductMasterDataDtos.QualityCertificate> updateQualityCertificate(
            @PathVariable Long batchId, @PathVariable Long certificateId,
            @Valid @RequestBody ProductMasterDataDtos.QualityCertificateSave request) {
        return ApiResponse.ok(qualityService.saveQualityCertificate(batchId, certificateId, request));
    }

    @PostMapping("/inventory/batches/{batchId}/quality-certificates/{certificateId}/review")
    public ApiResponse<ProductMasterDataDtos.QualityCertificate> reviewQualityCertificate(
            @PathVariable Long batchId, @PathVariable Long certificateId,
            @Valid @RequestBody ProductMasterDataDtos.QualityCertificateReviewSave request) {
        return ApiResponse.ok(qualityService.reviewQualityCertificate(batchId, certificateId, request));
    }

    @GetMapping("/inventory/batches/{batchId}/quality-certificates/{certificateId}/reviews")
    public ApiResponse<List<ProductMasterDataDtos.QualityCertificateReviewHistory>>
            qualityCertificateReviews(
            @PathVariable Long batchId, @PathVariable Long certificateId) {
        return ApiResponse.ok(qualityService.qualityCertificateReviewHistory(batchId, certificateId));
    }
}
