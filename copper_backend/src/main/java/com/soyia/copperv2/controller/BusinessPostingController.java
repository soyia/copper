package com.soyia.copperv2.controller;

import com.soyia.copperv2.dto.ApiResponse;
import com.soyia.copperv2.dto.BusinessPostingDtos;
import com.soyia.copperv2.service.BusinessPostingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/business-postings")
public class BusinessPostingController {
    private final BusinessPostingService postingService;

    public BusinessPostingController(BusinessPostingService postingService) {
        this.postingService = postingService;
    }

    @GetMapping("/rules")
    public ApiResponse<List<BusinessPostingDtos.RuleView>> rules() {
        return ApiResponse.ok(postingService.rules());
    }

    @GetMapping("/batches")
    public ApiResponse<List<BusinessPostingDtos.BatchRow>> batches(
            @RequestParam(required = false) String sourceType,
            @RequestParam(required = false) String status) {
        return ApiResponse.ok(postingService.batches(sourceType, status));
    }

    @GetMapping("/batches/{id}")
    public ApiResponse<BusinessPostingDtos.BatchDetail> detail(@PathVariable Long id) {
        return ApiResponse.ok(postingService.detail(id));
    }

    @GetMapping("/reconciliation")
    public ApiResponse<List<BusinessPostingDtos.ReconciliationRow>> reconciliation() {
        return ApiResponse.ok(postingService.reconciliation());
    }

    @GetMapping("/order-revisions/{orderType}/{orderId}")
    public ApiResponse<List<BusinessPostingDtos.OrderRevisionRow>> orderRevisions(
            @PathVariable String orderType, @PathVariable Long orderId) {
        return ApiResponse.ok(postingService.orderRevisions(orderType, orderId));
    }
}
