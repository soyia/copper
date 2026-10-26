package com.soyia.copperv2.controller;

import com.soyia.copperv2.dto.ApiResponse;
import com.soyia.copperv2.dto.DirectTradeDtos;
import com.soyia.copperv2.service.DirectTradeService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
public class DirectTradeController {
    private final DirectTradeService service;

    public DirectTradeController(DirectTradeService service) {
        this.service = service;
    }

    @GetMapping("/direct-trades")
    public ApiResponse<List<DirectTradeDtos.Row>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status) {
        return ApiResponse.ok(service.list(keyword, status));
    }

    @GetMapping("/direct-trades/{id}")
    public ApiResponse<DirectTradeDtos.Detail> detail(@PathVariable Long id) {
        return ApiResponse.ok(service.detail(id));
    }

    @PostMapping("/direct-trades")
    public ApiResponse<Map<String, Long>> create(@Valid @RequestBody DirectTradeDtos.Create request) {
        return ApiResponse.ok(Map.of("id", service.create(request)));
    }

    @PostMapping("/direct-trades/{id}/confirm")
    public ApiResponse<Map<String, Long>> confirm(
            @PathVariable Long id, @Valid @RequestBody DirectTradeDtos.Confirm request) {
        return ApiResponse.ok(Map.of("id", service.confirm(id, request)));
    }

    @PostMapping("/direct-trades/{id}/cancel")
    public ApiResponse<Map<String, Long>> cancel(
            @PathVariable Long id, @Valid @RequestBody DirectTradeDtos.Action request) {
        return ApiResponse.ok(Map.of("id", service.cancel(id, request.reason())));
    }

    @PostMapping("/direct-trades/{id}/deliveries")
    public ApiResponse<Map<String, Long>> dispatch(
            @PathVariable Long id, @Valid @RequestBody DirectTradeDtos.Dispatch request) {
        return ApiResponse.ok(Map.of("id", service.dispatch(id, request)));
    }

    @PostMapping("/direct-trades/{id}/deliveries/{deliveryId}/accept")
    public ApiResponse<Map<String, Long>> accept(
            @PathVariable Long id, @PathVariable Long deliveryId,
            @Valid @RequestBody DirectTradeDtos.Accept request) {
        return ApiResponse.ok(Map.of("id", service.accept(id, deliveryId, request)));
    }

    @PostMapping("/direct-trades/{id}/returns")
    public ApiResponse<Map<String, Long>> createReturn(
            @PathVariable Long id, @Valid @RequestBody DirectTradeDtos.ReturnCreate request) {
        return ApiResponse.ok(Map.of("id", service.createReturn(id, request)));
    }

    @PostMapping("/direct-trades/{id}/returns/{returnId}/reverse")
    public ApiResponse<Map<String, Long>> reverseReturn(
            @PathVariable Long id, @PathVariable Long returnId,
            @Valid @RequestBody DirectTradeDtos.Action request) {
        return ApiResponse.ok(Map.of("id", service.reverseReturn(id, returnId, request.reason())));
    }
}
