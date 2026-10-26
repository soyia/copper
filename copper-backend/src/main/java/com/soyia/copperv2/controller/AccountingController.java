package com.soyia.copperv2.controller;

import com.soyia.copperv2.dto.AccountingDtos;
import com.soyia.copperv2.dto.ApiResponse;
import com.soyia.copperv2.service.AccountingGenerationCoordinator;
import com.soyia.copperv2.service.AccountingArchiveService;
import com.soyia.copperv2.service.AccountingCoverageService;
import com.soyia.copperv2.service.AccountingService;
import com.soyia.copperv2.service.FinancialCloseService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.core.io.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/accounting")
public class AccountingController {
    private final AccountingService accountingService;
    private final AccountingGenerationCoordinator generationCoordinator;
    private final FinancialCloseService financialCloseService;
    private final AccountingArchiveService archiveService;
    private final AccountingCoverageService coverageService;

    public AccountingController(AccountingService accountingService,
                                AccountingGenerationCoordinator generationCoordinator,
                                FinancialCloseService financialCloseService,
                                AccountingArchiveService archiveService,
                                AccountingCoverageService coverageService) {
        this.accountingService = accountingService;
        this.generationCoordinator = generationCoordinator;
        this.financialCloseService = financialCloseService;
        this.archiveService = archiveService;
        this.coverageService = coverageService;
    }

    @GetMapping("/entity")
    public ApiResponse<AccountingDtos.EntityView> entity() {
        return ApiResponse.ok(accountingService.entity());
    }

    @GetMapping("/dashboard")
    public ApiResponse<AccountingDtos.DashboardView> dashboard() {
        return ApiResponse.ok(accountingService.dashboard());
    }

    @GetMapping("/archives")
    public ApiResponse<List<AccountingDtos.ArchiveView>> archives() {
        return ApiResponse.ok(archiveService.archives());
    }

    @PostMapping("/archives")
    public ApiResponse<AccountingDtos.ArchiveView> createArchive(
            @Valid @RequestBody AccountingDtos.ArchiveCreate request) {
        return ApiResponse.ok(archiveService.create(request));
    }

    @GetMapping("/archives/{id}/download")
    public ResponseEntity<Resource> downloadArchive(@PathVariable Long id) {
        AccountingArchiveService.ArchiveDownload content = archiveService.download(id);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(content.fileName(), StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.parseMediaType(content.contentType()))
                .contentLength(content.contentLength())
                .body(content.resource());
    }

    @GetMapping("/accounts")
    public ApiResponse<List<AccountingDtos.AccountView>> accounts(
            @RequestParam(defaultValue = "false") boolean includeDisabled) {
        return ApiResponse.ok(accountingService.accounts(includeDisabled));
    }

    @PostMapping("/accounts")
    public ApiResponse<Long> createAccount(@Valid @RequestBody AccountingDtos.AccountSave request) {
        return ApiResponse.ok(accountingService.saveAccount(null, request));
    }

    @PutMapping("/accounts/{id}")
    public ApiResponse<Long> updateAccount(@PathVariable Long id,
                                            @Valid @RequestBody AccountingDtos.AccountSave request) {
        return ApiResponse.ok(accountingService.saveAccount(id, request));
    }

    @GetMapping("/periods")
    public ApiResponse<List<AccountingDtos.PeriodView>> periods(
            @RequestParam(required = false) Integer fiscalYear) {
        return ApiResponse.ok(accountingService.periods(fiscalYear));
    }

    @PostMapping("/periods/initialize")
    public ApiResponse<Integer> initializeYear(@Valid @RequestBody AccountingDtos.InitializeYear request) {
        return ApiResponse.ok(accountingService.initializeYear(request.fiscalYear()));
    }

    @PostMapping("/periods/{id}/close")
    public ApiResponse<Long> closePeriod(@PathVariable Long id,
                                          @Valid @RequestBody AccountingDtos.PeriodAction request) {
        return ApiResponse.ok(financialCloseService.closePeriod(id, request.reason()).periodId());
    }

    @PostMapping("/periods/{id}/reopen")
    public ApiResponse<Long> reopenPeriod(@PathVariable Long id,
                                           @Valid @RequestBody AccountingDtos.PeriodAction request) {
        return ApiResponse.ok(financialCloseService.reopenPeriod(id, request.reason()).periodId());
    }

    @GetMapping("/rules")
    public ApiResponse<List<AccountingDtos.RuleView>> rules() {
        return ApiResponse.ok(accountingService.rules());
    }

    @GetMapping("/rule-coverage")
    public ApiResponse<AccountingDtos.RuleCoverageMatrix> ruleCoverage(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return ApiResponse.ok(coverageService.matrix(asOf));
    }

    @PostMapping("/rules")
    public ApiResponse<Long> createRuleVersion(@Valid @RequestBody AccountingDtos.RuleSave request) {
        return ApiResponse.ok(accountingService.saveRule(request));
    }

    @PostMapping("/rules/{id}/publish")
    public ApiResponse<Long> publishRule(@PathVariable Long id) {
        return ApiResponse.ok(accountingService.publishRule(id));
    }

    @GetMapping("/vouchers")
    public ApiResponse<List<AccountingDtos.VoucherRow>> vouchers(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.ok(accountingService.vouchers(status, from, to));
    }

    @GetMapping("/vouchers/{id}")
    public ApiResponse<AccountingDtos.VoucherDetail> voucher(@PathVariable Long id) {
        return ApiResponse.ok(accountingService.voucher(id));
    }

    @PostMapping("/vouchers")
    public ApiResponse<Long> createVoucher(@Valid @RequestBody AccountingDtos.VoucherSave request) {
        return ApiResponse.ok(accountingService.createManualVoucher(request));
    }

    @PutMapping("/vouchers/{id}")
    public ApiResponse<Long> updateVoucher(@PathVariable Long id,
                                            @Valid @RequestBody AccountingDtos.VoucherSave request) {
        return ApiResponse.ok(accountingService.updateManualVoucher(id, request));
    }

    @PostMapping("/vouchers/{id}/review")
    public ApiResponse<Long> reviewVoucher(@PathVariable Long id,
                                            @RequestBody(required = false) AccountingDtos.VoucherAction request) {
        return ApiResponse.ok(accountingService.reviewVoucher(id, request == null ? "" : request.reason()));
    }

    @PostMapping("/vouchers/{id}/reject")
    public ApiResponse<Long> rejectVoucher(@PathVariable Long id,
                                            @Valid @RequestBody AccountingDtos.VoucherAction request) {
        return ApiResponse.ok(accountingService.rejectVoucher(id, request.reason()));
    }

    @PostMapping("/vouchers/{id}/post")
    public ApiResponse<Long> postVoucher(@PathVariable Long id) {
        return ApiResponse.ok(accountingService.postVoucher(id));
    }

    @PostMapping("/vouchers/{id}/cancel")
    public ApiResponse<Long> cancelVoucher(@PathVariable Long id,
                                            @Valid @RequestBody AccountingDtos.VoucherAction request) {
        return ApiResponse.ok(accountingService.cancelVoucher(id, request.reason()));
    }

    @PostMapping("/vouchers/{id}/reverse")
    public ApiResponse<Long> reverseVoucher(@PathVariable Long id,
                                             @Valid @RequestBody AccountingDtos.VoucherAction request) {
        return ApiResponse.ok(accountingService.reverseVoucher(id, request));
    }

    @PostMapping("/vouchers/generate-missing")
    public ApiResponse<AccountingDtos.GenerateResult> generateMissing() {
        return ApiResponse.ok(generationCoordinator.generateMissingSafely());
    }

    @GetMapping("/generation-issues")
    public ApiResponse<List<AccountingDtos.GenerationIssueView>> generationIssues(
            @RequestParam(required = false) String status) {
        return ApiResponse.ok(accountingService.generationIssues(status));
    }

    @PostMapping("/generation-issues/{id}/retry")
    public ApiResponse<Long> retryGeneration(@PathVariable Long id) {
        return ApiResponse.ok(accountingService.retryGeneration(id));
    }

    @GetMapping("/trial-balance")
    public ApiResponse<AccountingDtos.TrialBalanceReport> trialBalance(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.ok(accountingService.trialBalance(from, to));
    }

    @GetMapping("/ledger")
    public ApiResponse<List<AccountingDtos.LedgerEntryView>> ledger(
            @RequestParam Long accountId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long partnerId,
            @RequestParam(required = false) Long productId,
            @RequestParam(required = false) Long warehouseId) {
        return ApiResponse.ok(accountingService.ledgerEntries(accountId, from, to, partnerId, productId, warehouseId));
    }
}
