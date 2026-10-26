package com.soyia.copperv2.controller;

import com.soyia.copperv2.dto.ApiResponse;
import com.soyia.copperv2.dto.LandedCostDtos;
import com.soyia.copperv2.service.LandedCostService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
public class LandedCostController {
    private final LandedCostService landedCostService;

    public LandedCostController(LandedCostService landedCostService) {
        this.landedCostService = landedCostService;
    }

    @GetMapping("/inventory/landed-cost-allocations/options")
    public ApiResponse<LandedCostDtos.Options> options() {
        return ApiResponse.ok(landedCostService.options());
    }

    @GetMapping("/inventory/landed-cost-allocations")
    public ApiResponse<List<LandedCostDtos.AllocationRow>> allocations(@RequestParam(required = false) String status) {
        return ApiResponse.ok(landedCostService.allocations(status));
    }

    @GetMapping("/inventory/landed-cost-allocations/{id}")
    public ApiResponse<LandedCostDtos.AllocationDetail> allocation(@PathVariable Long id) {
        return ApiResponse.ok(landedCostService.allocation(id));
    }

    @PostMapping("/inventory/landed-cost-allocations")
    public ApiResponse<Map<String, Long>> create(@Valid @RequestBody LandedCostDtos.AllocationCreate request) {
        return ApiResponse.ok(Map.of("id", landedCostService.create(request)));
    }

    @PutMapping("/inventory/landed-cost-allocations/{id}")
    public ApiResponse<Map<String, Long>> update(@PathVariable Long id,
                                                 @Valid @RequestBody LandedCostDtos.AllocationCreate request) {
        return ApiResponse.ok(Map.of("id", landedCostService.update(id, request)));
    }

    @PostMapping("/inventory/landed-cost-allocations/{id}/confirm")
    public ApiResponse<Map<String, Long>> confirm(@PathVariable Long id) {
        return ApiResponse.ok(Map.of("id", landedCostService.confirm(id)));
    }

    @PostMapping("/inventory/landed-cost-allocations/{id}/cancel")
    public ApiResponse<Map<String, Long>> cancel(@PathVariable Long id, @Valid @RequestBody LandedCostDtos.Action action) {
        return ApiResponse.ok(Map.of("id", landedCostService.cancel(id, action.reason())));
    }

    @PostMapping("/inventory/landed-cost-allocations/{id}/reverse")
    public ApiResponse<Map<String, Long>> reverse(@PathVariable Long id, @Valid @RequestBody LandedCostDtos.Action action) {
        return ApiResponse.ok(Map.of("id", landedCostService.reverse(id, action)));
    }
}
