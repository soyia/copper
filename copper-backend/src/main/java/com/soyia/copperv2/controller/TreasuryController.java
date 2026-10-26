package com.soyia.copperv2.controller;

import com.soyia.copperv2.dto.ApiResponse;
import com.soyia.copperv2.dto.TreasuryDtos;
import com.soyia.copperv2.dto.RiskExceptionDtos;
import com.soyia.copperv2.service.TreasuryService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/treasury")
public class TreasuryController {
    private final TreasuryService service;

    public TreasuryController(TreasuryService service) {
        this.service = service;
    }

    @GetMapping("/dashboard")
    public ApiResponse<TreasuryDtos.Dashboard> dashboard() {
        return ApiResponse.ok(service.dashboard());
    }

    @GetMapping("/documents")
    public ApiResponse<List<TreasuryDtos.DocumentRow>> documents(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String documentType,
            @RequestParam(required = false) Long partnerId) {
        return ApiResponse.ok(service.documents(status, documentType, partnerId));
    }

    @GetMapping("/documents/{id}")
    public ApiResponse<TreasuryDtos.DocumentDetail> document(@PathVariable Long id) {
        return ApiResponse.ok(service.document(id));
    }

    @PostMapping("/documents")
    public ApiResponse<Map<String, Long>> createDocument(
            @Valid @RequestBody TreasuryDtos.DocumentCreate request) {
        return ApiResponse.ok(Map.of("id", service.createDocument(request)));
    }

    @PostMapping("/documents/{id}/confirm")
    public ApiResponse<Map<String, Long>> confirmDocument(@PathVariable Long id) {
        return ApiResponse.ok(Map.of("id", service.confirmDocument(id)));
    }

    @PostMapping("/documents/{id}/cancel")
    public ApiResponse<Map<String, Long>> cancelDocument(@PathVariable Long id,
            @Valid @RequestBody TreasuryDtos.Action request) {
        return ApiResponse.ok(Map.of("id", service.cancelDocument(id, request)));
    }

    @PostMapping("/documents/{id}/reverse")
    public ApiResponse<Map<String, Long>> reverseDocument(@PathVariable Long id,
            @Valid @RequestBody TreasuryDtos.Action request) {
        return ApiResponse.ok(Map.of("id", service.reverseDocument(id, request)));
    }

    @PostMapping("/documents/{id}/settlements")
    public ApiResponse<Map<String, Long>> settleDocument(@PathVariable Long id,
            @Valid @RequestBody TreasuryDtos.SettlementCreate request) {
        return ApiResponse.ok(Map.of("id", service.settleDocument(id, request)));
    }

    @GetMapping("/payment-requests")
    public ApiResponse<List<TreasuryDtos.PaymentRequestRow>> paymentRequests(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long documentId) {
        return ApiResponse.ok(service.paymentRequests(status, documentId));
    }

    @PostMapping("/payment-requests")
    public ApiResponse<Map<String, Long>> createPaymentRequest(
            @Valid @RequestBody TreasuryDtos.PaymentRequestCreate request) {
        return ApiResponse.ok(Map.of("id", service.createPaymentRequest(request)));
    }

    @PostMapping("/payment-requests/{id}/approve")
    public ApiResponse<Map<String, Long>> approvePaymentRequest(@PathVariable Long id,
            @RequestBody(required = false) RiskExceptionDtos.InlineAuthorization authorization) {
        return ApiResponse.ok(Map.of("id", service.approvePaymentRequest(id, authorization)));
    }

    @PostMapping("/payment-requests/{id}/reject")
    public ApiResponse<Map<String, Long>> rejectPaymentRequest(@PathVariable Long id,
            @Valid @RequestBody TreasuryDtos.Action request) {
        return ApiResponse.ok(Map.of("id", service.rejectPaymentRequest(id, request)));
    }

    @PostMapping("/payment-requests/{id}/cancel")
    public ApiResponse<Map<String, Long>> cancelPaymentRequest(@PathVariable Long id,
            @Valid @RequestBody TreasuryDtos.Action request) {
        return ApiResponse.ok(Map.of("id", service.cancelPaymentRequest(id, request)));
    }

    @PostMapping("/transfers")
    public ApiResponse<Map<String, Long>> createTransfer(
            @Valid @RequestBody TreasuryDtos.TransferCreate request) {
        return ApiResponse.ok(Map.of("id", service.createTransfer(request)));
    }

    @PostMapping("/funds/{id}/reverse")
    public ApiResponse<Map<String, Long>> reverseFund(@PathVariable Long id,
            @Valid @RequestBody TreasuryDtos.Action request) {
        return ApiResponse.ok(Map.of("id", service.reverseFund(id, request)));
    }

    @GetMapping("/journal")
    public ApiResponse<List<TreasuryDtos.JournalRow>> journal(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long bankAccountId,
            @RequestParam(required = false) String direction) {
        return ApiResponse.ok(service.journal(from, to, bankAccountId, direction));
    }
}
