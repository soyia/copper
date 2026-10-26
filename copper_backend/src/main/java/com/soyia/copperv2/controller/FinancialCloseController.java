package com.soyia.copperv2.controller;

import com.soyia.copperv2.dto.ApiResponse;
import com.soyia.copperv2.dto.FinancialCloseDtos;
import com.soyia.copperv2.service.BankStatementFileParser;
import com.soyia.copperv2.service.BankStatementImportService;
import com.soyia.copperv2.service.FinancialCloseService;
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
@RequestMapping("/financial")
public class FinancialCloseController {
    private final FinancialCloseService service;
    private final BankStatementImportService statementImportService;

    public FinancialCloseController(FinancialCloseService service,
                                    BankStatementImportService statementImportService) {
        this.service = service;
        this.statementImportService = statementImportService;
    }

    @GetMapping("/reports/balance-sheet")
    public ApiResponse<FinancialCloseDtos.FinancialReport> balanceSheet(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return ApiResponse.ok(service.balanceSheet(asOf));
    }

    @GetMapping("/reports/income-statement")
    public ApiResponse<FinancialCloseDtos.FinancialReport> incomeStatement(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.ok(service.incomeStatement(from, to));
    }

    @GetMapping("/reports/cash-flow")
    public ApiResponse<FinancialCloseDtos.CashFlowReport> cashFlow(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.ok(service.cashFlow(from, to));
    }

    @GetMapping("/bank-accounts")
    public ApiResponse<List<FinancialCloseDtos.BankAccountView>> bankAccounts(
            @RequestParam(defaultValue = "false") boolean includeDisabled) {
        return ApiResponse.ok(service.bankAccounts(includeDisabled));
    }

    @PostMapping("/bank-accounts")
    public ApiResponse<Long> createBankAccount(@Valid @RequestBody FinancialCloseDtos.BankAccountSave request) {
        return ApiResponse.ok(service.saveBankAccount(null, request));
    }

    @PutMapping("/bank-accounts/{id}")
    public ApiResponse<Long> updateBankAccount(@PathVariable Long id,
                                               @Valid @RequestBody FinancialCloseDtos.BankAccountSave request) {
        return ApiResponse.ok(service.saveBankAccount(id, request));
    }

    @GetMapping("/bank-reconciliations")
    public ApiResponse<List<FinancialCloseDtos.ReconciliationSessionRow>> reconciliations(
            @RequestParam(required = false) Long periodId) {
        return ApiResponse.ok(service.reconciliationSessions(periodId));
    }

    @PostMapping("/bank-reconciliations")
    public ApiResponse<Long> createReconciliation(
            @Valid @RequestBody FinancialCloseDtos.ReconciliationCreate request) {
        return ApiResponse.ok(service.createReconciliation(request));
    }

    @GetMapping("/bank-reconciliations/{id}")
    public ApiResponse<FinancialCloseDtos.ReconciliationDetail> reconciliation(@PathVariable Long id) {
        return ApiResponse.ok(service.reconciliation(id));
    }

    @PostMapping("/bank-reconciliations/{id}/statement-lines")
    public ApiResponse<Integer> statementLines(@PathVariable Long id,
                                               @Valid @RequestBody FinancialCloseDtos.StatementLinesSave request) {
        return ApiResponse.ok(service.saveStatementLines(id, request));
    }

    @GetMapping("/bank-reconciliations/import-template")
    public ResponseEntity<byte[]> statementImportTemplate(
            @RequestParam(defaultValue = "xlsx") String format) {
        BankStatementFileParser.TemplateFile template = statementImportService.template(format);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(template.fileName(), StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.parseMediaType(template.contentType()))
                .body(template.content());
    }

    @PostMapping(value = "/bank-reconciliations/{id}/statement-imports/preview",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<FinancialCloseDtos.StatementImportPreview> previewStatementImport(
            @PathVariable Long id, @RequestParam("file") MultipartFile file) {
        return ApiResponse.ok(statementImportService.preview(id, file));
    }

    @GetMapping("/bank-reconciliations/{id}/statement-imports")
    public ApiResponse<List<FinancialCloseDtos.StatementImportBatchView>> statementImportBatches(
            @PathVariable Long id) {
        return ApiResponse.ok(statementImportService.batches(id));
    }

    @GetMapping("/bank-reconciliations/{id}/statement-imports/{batchId}")
    public ApiResponse<FinancialCloseDtos.StatementImportPreview> statementImport(
            @PathVariable Long id, @PathVariable Long batchId) {
        return ApiResponse.ok(statementImportService.preview(id, batchId));
    }

    @PostMapping("/bank-reconciliations/{id}/statement-imports/{batchId}/commit")
    public ApiResponse<FinancialCloseDtos.StatementImportResult> commitStatementImport(
            @PathVariable Long id, @PathVariable Long batchId,
            @RequestBody(required = false) FinancialCloseDtos.StatementImportCommit request) {
        return ApiResponse.ok(statementImportService.commit(id, batchId, request));
    }

    @PostMapping("/bank-reconciliations/{id}/matches")
    public ApiResponse<Long> createMatch(@PathVariable Long id,
                                         @Valid @RequestBody FinancialCloseDtos.MatchSave request) {
        return ApiResponse.ok(service.createMatch(id, request));
    }

    @PostMapping("/bank-reconciliations/matches/{matchId}/reverse")
    public ApiResponse<Long> reverseMatch(@PathVariable Long matchId,
                                          @Valid @RequestBody FinancialCloseDtos.ActionRequest request) {
        return ApiResponse.ok(service.reverseMatch(matchId, request.reason()));
    }

    @PostMapping("/bank-reconciliations/statement-lines/{lineId}/void")
    public ApiResponse<Long> voidStatementLine(@PathVariable Long lineId,
                                               @Valid @RequestBody FinancialCloseDtos.ActionRequest request) {
        return ApiResponse.ok(service.voidStatementLine(lineId, request.reason()));
    }

    @PostMapping("/bank-reconciliations/{id}/differences")
    public ApiResponse<Long> createDifference(@PathVariable Long id,
                                              @Valid @RequestBody FinancialCloseDtos.DifferenceSave request) {
        return ApiResponse.ok(service.createDifference(id, request));
    }

    @PostMapping("/bank-reconciliations/differences/{differenceId}/reverse")
    public ApiResponse<Long> reverseDifference(@PathVariable Long differenceId,
                                               @Valid @RequestBody FinancialCloseDtos.ActionRequest request) {
        return ApiResponse.ok(service.reverseDifference(differenceId, request.reason()));
    }

    @PostMapping("/bank-reconciliations/{id}/pending-system")
    public ApiResponse<Long> createPendingSystem(@PathVariable Long id,
                                                 @Valid @RequestBody FinancialCloseDtos.PendingSystemSave request) {
        return ApiResponse.ok(service.createPendingSystem(id, request));
    }

    @PostMapping("/bank-reconciliations/pending-system/{pendingId}/reverse")
    public ApiResponse<Long> reversePendingSystem(@PathVariable Long pendingId,
                                                  @Valid @RequestBody FinancialCloseDtos.ActionRequest request) {
        return ApiResponse.ok(service.reversePendingSystem(pendingId, request.reason()));
    }

    @PostMapping("/bank-reconciliations/{id}/confirm")
    public ApiResponse<Long> confirmReconciliation(@PathVariable Long id,
                                                   @Valid @RequestBody FinancialCloseDtos.ActionRequest request) {
        return ApiResponse.ok(service.confirmReconciliation(id, request.reason()));
    }

    @PostMapping("/bank-reconciliations/{id}/reopen")
    public ApiResponse<Long> reopenReconciliation(@PathVariable Long id,
                                                  @Valid @RequestBody FinancialCloseDtos.ActionRequest request) {
        return ApiResponse.ok(service.reopenReconciliation(id, request.reason()));
    }

    @GetMapping("/period-close/{periodId}/preview")
    public ApiResponse<FinancialCloseDtos.PeriodClosePreview> closePreview(@PathVariable Long periodId) {
        return ApiResponse.ok(service.closePreview(periodId));
    }

    @PostMapping("/period-close/{periodId}/profit-voucher")
    public ApiResponse<Long> generateProfitVoucher(@PathVariable Long periodId) {
        return ApiResponse.ok(service.generateProfitVoucher(periodId));
    }

    @PostMapping("/period-close/{periodId}/close")
    public ApiResponse<FinancialCloseDtos.CloseResult> closePeriod(
            @PathVariable Long periodId,
            @Valid @RequestBody FinancialCloseDtos.ActionRequest request) {
        return ApiResponse.ok(service.closePeriod(periodId, request.reason()));
    }

    @PostMapping("/period-close/{periodId}/reopen")
    public ApiResponse<FinancialCloseDtos.CloseResult> reopenPeriod(
            @PathVariable Long periodId,
            @Valid @RequestBody FinancialCloseDtos.ActionRequest request) {
        return ApiResponse.ok(service.reopenPeriod(periodId, request.reason()));
    }
}
