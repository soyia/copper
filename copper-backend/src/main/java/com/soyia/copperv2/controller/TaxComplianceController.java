package com.soyia.copperv2.controller;

import com.soyia.copperv2.dto.ApiResponse;
import com.soyia.copperv2.dto.TaxComplianceDtos;
import com.soyia.copperv2.service.ElectronicCredentialService;
import com.soyia.copperv2.service.TaxWorkpaperService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/tax-compliance")
public class TaxComplianceController {
    private final ElectronicCredentialService credentialService;
    private final TaxWorkpaperService workpaperService;

    public TaxComplianceController(ElectronicCredentialService credentialService,
                                   TaxWorkpaperService workpaperService) {
        this.credentialService = credentialService;
        this.workpaperService = workpaperService;
    }

    @GetMapping("/dashboard")
    public ApiResponse<TaxComplianceDtos.DashboardView> dashboard() {
        return ApiResponse.ok(credentialService.dashboard(workpaperService.latest()));
    }

    @GetMapping("/credentials")
    public ApiResponse<List<TaxComplianceDtos.CredentialRow>> credentials(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "false") boolean exceptionOnly) {
        return ApiResponse.ok(credentialService.credentials(type, status, exceptionOnly));
    }

    @GetMapping("/credentials/{id}")
    public ApiResponse<TaxComplianceDtos.CredentialDetail> credential(@PathVariable Long id) {
        return ApiResponse.ok(credentialService.credential(id));
    }

    @PostMapping(value = "/credentials/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<TaxComplianceDtos.CredentialRow> importCredential(
            @RequestParam(defaultValue = "AUTO") String declaredType,
            @RequestParam("file") MultipartFile file) {
        return ApiResponse.ok(credentialService.upload(declaredType, file));
    }

    @GetMapping("/credentials/{id}/original")
    public ResponseEntity<byte[]> original(@PathVariable Long id) {
        return file(credentialService.original(id), false);
    }

    @PostMapping(value = "/credentials/{id}/verifications", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Long> verify(
            @PathVariable Long id,
            @RequestParam String verificationKind,
            @RequestParam String verificationSource,
            @RequestParam String verificationResult,
            @RequestParam String verificationReference,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime checkedAt,
            @RequestParam(required = false) String resultSnapshot,
            @RequestParam("proof") MultipartFile proof) {
        return ApiResponse.ok(credentialService.verify(id, verificationKind, verificationSource,
                verificationResult, verificationReference, checkedAt, resultSnapshot, proof));
    }

    @GetMapping("/credentials/{id}/verifications/{verificationId}/proof")
    public ResponseEntity<byte[]> proof(@PathVariable Long id, @PathVariable Long verificationId) {
        return file(credentialService.proof(id, verificationId), false);
    }

    @GetMapping("/credentials/{id}/link-candidates")
    public ApiResponse<List<TaxComplianceDtos.LinkCandidate>> linkCandidates(@PathVariable Long id) {
        return ApiResponse.ok(credentialService.linkCandidates(id));
    }

    @PostMapping("/credentials/{id}/relations")
    public ApiResponse<Long> link(@PathVariable Long id,
                                  @Valid @RequestBody TaxComplianceDtos.LinkRequest request) {
        return ApiResponse.ok(credentialService.link(id, request));
    }

    @PostMapping("/credentials/{id}/relations/{relationId}/reverse")
    public ApiResponse<Long> reverseRelation(@PathVariable Long id, @PathVariable Long relationId,
                                             @Valid @RequestBody TaxComplianceDtos.ActionRequest request) {
        return ApiResponse.ok(credentialService.reverseRelation(id, relationId, request.reason()));
    }

    @PostMapping("/credentials/{id}/void")
    public ApiResponse<Long> voidCredential(@PathVariable Long id,
                                            @Valid @RequestBody TaxComplianceDtos.ActionRequest request) {
        return ApiResponse.ok(credentialService.voidCredential(id, request.reason()));
    }

    @GetMapping("/workpapers")
    public ApiResponse<List<TaxComplianceDtos.WorkpaperRow>> workpapers(
            @RequestParam(required = false) String period) {
        return ApiResponse.ok(workpaperService.workpapers(period));
    }

    @GetMapping("/workpapers/{id}")
    public ApiResponse<TaxComplianceDtos.WorkpaperDetail> workpaper(@PathVariable Long id) {
        return ApiResponse.ok(workpaperService.workpaper(id));
    }

    @PostMapping("/workpapers/generate")
    public ApiResponse<TaxComplianceDtos.WorkpaperDetail> generateWorkpaper(
            @Valid @RequestBody TaxComplianceDtos.GenerateWorkpaperRequest request) {
        return ApiResponse.ok(workpaperService.generate(request.period()));
    }

    @PostMapping("/workpapers/{id}/review")
    public ApiResponse<Long> reviewWorkpaper(@PathVariable Long id,
                                             @Valid @RequestBody TaxComplianceDtos.ReviewWorkpaperRequest request) {
        return ApiResponse.ok(workpaperService.review(id, request.reason()));
    }

    @GetMapping("/workpapers/{id}/export")
    public ResponseEntity<byte[]> exportWorkpaper(@PathVariable Long id) {
        return file(workpaperService.exportCsv(id), true);
    }

    private ResponseEntity<byte[]> file(TaxComplianceDtos.FileContent file, boolean attachment) {
        ContentDisposition disposition = (attachment ? ContentDisposition.attachment() : ContentDisposition.inline())
                .filename(file.fileName(), StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.parseMediaType(file.contentType()))
                .body(file.content());
    }
}
