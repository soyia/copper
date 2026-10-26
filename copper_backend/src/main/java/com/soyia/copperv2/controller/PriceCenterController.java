package com.soyia.copperv2.controller;

import com.soyia.copperv2.dto.ApiResponse;
import com.soyia.copperv2.dto.PriceCenterDtos;
import com.soyia.copperv2.service.PriceCenterService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class PriceCenterController {
    private final PriceCenterService priceCenterService;

    public PriceCenterController(PriceCenterService priceCenterService) {
        this.priceCenterService = priceCenterService;
    }

    @GetMapping("/price-center/benchmarks")
    public ApiResponse<List<PriceCenterDtos.BenchmarkRow>> benchmarks(
            @RequestParam(required = false) String benchmarkCode) {
        return ApiResponse.ok(priceCenterService.benchmarks(benchmarkCode));
    }

    @PostMapping("/price-center/benchmarks")
    public ApiResponse<PriceCenterDtos.BenchmarkRow> createBenchmark(
            @Valid @RequestBody PriceCenterDtos.BenchmarkSave request) {
        return ApiResponse.ok(priceCenterService.createBenchmark(request));
    }

    @GetMapping("/price-center/rules")
    public ApiResponse<List<PriceCenterDtos.RuleRow>> rules(
            @RequestParam(required = false) String businessType,
            @RequestParam(required = false) Long productId,
            @RequestParam(required = false) Long partnerId) {
        return ApiResponse.ok(priceCenterService.rules(businessType, productId, partnerId));
    }

    @PostMapping("/price-center/rules")
    public ApiResponse<PriceCenterDtos.RuleRow> createRule(
            @Valid @RequestBody PriceCenterDtos.RuleSave request) {
        return ApiResponse.ok(priceCenterService.createRule(request));
    }

    @PostMapping("/price-center/preview")
    public ApiResponse<PriceCenterDtos.Preview> preview(
            @Valid @RequestBody PriceCenterDtos.PreviewRequest request) {
        return ApiResponse.ok(priceCenterService.preview(request));
    }
}
