package com.soyia.copperv2.controller;

import com.soyia.copperv2.dto.ApiResponse;
import com.soyia.copperv2.dto.TaxInvoiceDtos;
import com.soyia.copperv2.service.TaxInvoiceImportService;
import com.soyia.copperv2.service.TaxInvoiceService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/tax-invoices")
public class TaxInvoiceController {
    private final TaxInvoiceService service;
    private final TaxInvoiceImportService importService;

    public TaxInvoiceController(TaxInvoiceService service, TaxInvoiceImportService importService) {
        this.service = service;
        this.importService = importService;
    }

    @GetMapping("/dashboard")
    public ApiResponse<TaxInvoiceDtos.DashboardView> dashboard() {
        return ApiResponse.ok(service.dashboard());
    }

    @GetMapping("/rates")
    public ApiResponse<List<TaxInvoiceDtos.TaxRateView>> rates(
            @RequestParam(required = false) String direction,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResponse.ok(service.taxRates(direction, date));
    }

    @GetMapping("/import-template")
    public ResponseEntity<byte[]> importTemplate() {
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename("copper-tax-invoice-template-v1.csv", StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .body(importService.template());
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<TaxInvoiceDtos.ImportResult> importCsv(@RequestParam("file") MultipartFile file) {
        return ApiResponse.ok(importService.importCsv(file));
    }

    @GetMapping
    public ApiResponse<List<TaxInvoiceDtos.InvoiceRow>> invoices(
            @RequestParam(required = false) String direction,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long partnerId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "false") boolean exceptionOnly) {
        return ApiResponse.ok(service.invoices(direction, status, partnerId, from, to, exceptionOnly));
    }

    @GetMapping("/{id}")
    public ApiResponse<TaxInvoiceDtos.InvoiceDetail> invoice(@PathVariable Long id) {
        return ApiResponse.ok(service.invoice(id));
    }

    @PostMapping
    public ApiResponse<Long> create(@Valid @RequestBody TaxInvoiceDtos.InvoiceSave request) {
        return ApiResponse.ok(service.saveInvoice(null, request));
    }

    @PutMapping("/{id}")
    public ApiResponse<Long> update(@PathVariable Long id,
                                    @Valid @RequestBody TaxInvoiceDtos.InvoiceSave request) {
        return ApiResponse.ok(service.saveInvoice(id, request));
    }

    @PostMapping("/{id}/confirm")
    public ApiResponse<Long> confirm(@PathVariable Long id) {
        return ApiResponse.ok(service.confirmInvoice(id));
    }

    @PostMapping("/{id}/void")
    public ApiResponse<Long> voidInvoice(@PathVariable Long id,
                                         @Valid @RequestBody TaxInvoiceDtos.ActionRequest request) {
        return ApiResponse.ok(service.voidInvoice(id, request.reason()));
    }

    @PostMapping("/{id}/verify")
    public ApiResponse<Long> verify(@PathVariable Long id,
                                    @Valid @RequestBody TaxInvoiceDtos.VerifyRequest request) {
        return ApiResponse.ok(service.verifyInvoice(id, request));
    }

    @PostMapping("/{id}/booking-confirm")
    public ApiResponse<Long> confirmBooking(@PathVariable Long id,
                                            @Valid @RequestBody TaxInvoiceDtos.BookingRequest request) {
        return ApiResponse.ok(service.confirmBooking(id, request));
    }

    @PostMapping("/{id}/usage-confirm")
    public ApiResponse<Long> confirmUsage(@PathVariable Long id,
                                          @Valid @RequestBody TaxInvoiceDtos.UsageRequest request) {
        return ApiResponse.ok(service.confirmUsage(id, request));
    }

    @PostMapping("/{id}/usage-transfer-out")
    public ApiResponse<Long> transferOut(@PathVariable Long id,
                                         @Valid @RequestBody TaxInvoiceDtos.TransferOutRequest request) {
        return ApiResponse.ok(service.transferOut(id, request));
    }

    @PostMapping("/{id}/red-confirmation")
    public ApiResponse<Long> redConfirmation(@PathVariable Long id,
                                             @Valid @RequestBody TaxInvoiceDtos.RedConfirmationRequest request) {
        return ApiResponse.ok(service.updateRedConfirmation(id, request));
    }

    @GetMapping("/source-candidates")
    public ApiResponse<List<TaxInvoiceDtos.SourceCandidate>> sourceCandidates(
            @RequestParam String direction, @RequestParam Long partnerId,
            @RequestParam(required = false) String invoiceColor) {
        return ApiResponse.ok(service.sourceCandidates(direction, partnerId, invoiceColor));
    }

    @PostMapping("/{id}/relations")
    public ApiResponse<Long> addRelation(@PathVariable Long id,
                                         @Valid @RequestBody TaxInvoiceDtos.RelationSave request) {
        return ApiResponse.ok(service.addRelation(id, request));
    }

    @PostMapping("/{id}/relations/{relationId}/reverse")
    public ApiResponse<Long> reverseRelation(@PathVariable Long id, @PathVariable Long relationId,
                                             @Valid @RequestBody TaxInvoiceDtos.ActionRequest request) {
        return ApiResponse.ok(service.reverseRelation(id, relationId, request.reason()));
    }
}
