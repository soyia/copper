package com.soyia.copperv2.controller;

import com.soyia.copperv2.dto.ApiResponse;
import com.soyia.copperv2.dto.PartnerFundAdjustmentDtos;
import com.soyia.copperv2.dto.RiskExceptionDtos;
import com.soyia.copperv2.service.PartnerFundAdjustmentService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/partner-fund-adjustments")
public class PartnerFundAdjustmentController {
    private final PartnerFundAdjustmentService service;

    public PartnerFundAdjustmentController(PartnerFundAdjustmentService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<PartnerFundAdjustmentDtos.Row>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long partnerId) {
        return ApiResponse.ok(service.list(status, partnerId));
    }

    @GetMapping("/{id}")
    public ApiResponse<PartnerFundAdjustmentDtos.Detail> detail(@PathVariable Long id) {
        return ApiResponse.ok(service.detail(id));
    }

    @PostMapping
    public ApiResponse<Map<String, Long>> create(@Valid @RequestBody PartnerFundAdjustmentDtos.Create req) {
        return ApiResponse.ok(Map.of("id", service.create(req)));
    }

    @PostMapping("/{id}/confirm")
    public ApiResponse<Map<String, Long>> confirm(@PathVariable Long id,
            @RequestBody(required = false) RiskExceptionDtos.InlineAuthorization authorization) {
        return ApiResponse.ok(Map.of("id", service.confirm(id, authorization)));
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<Map<String, Long>> cancel(@PathVariable Long id,
                                                 @Valid @RequestBody PartnerFundAdjustmentDtos.Action req) {
        return ApiResponse.ok(Map.of("id", service.cancel(id, req)));
    }

    @PostMapping("/{id}/reverse")
    public ApiResponse<Map<String, Long>> reverse(@PathVariable Long id,
                                                  @Valid @RequestBody PartnerFundAdjustmentDtos.Action req) {
        return ApiResponse.ok(Map.of("id", service.reverse(id, req)));
    }
}
