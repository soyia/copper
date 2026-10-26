package com.soyia.copperv2.controller;

import com.soyia.copperv2.dto.ApiResponse;
import com.soyia.copperv2.dto.ProductImportDtos;
import com.soyia.copperv2.service.ProductImportFileParser;
import com.soyia.copperv2.service.ProductImportService;
import jakarta.validation.Valid;
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
import java.util.List;

@RestController
@RequestMapping("/product-imports")
public class ProductImportController {
    private final ProductImportService service;

    public ProductImportController(ProductImportService service) {
        this.service = service;
    }

    @GetMapping("/template-info")
    public ApiResponse<ProductImportDtos.TemplateInfo> templateInfo() {
        return ApiResponse.ok(service.templateInfo());
    }

    @GetMapping("/template")
    public ResponseEntity<byte[]> template(
            @RequestParam(defaultValue = "xlsx") String format,
            @RequestParam Long seriesId) {
        ProductImportFileParser.TemplateFile template = service.template(format, seriesId);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(template.fileName(), StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.parseMediaType(template.contentType()))
                .body(template.content());
    }

    @PostMapping(value = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<ProductImportDtos.Preview> preview(
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) String mapping) {
        return ApiResponse.ok(service.preview(file, mapping));
    }

    @GetMapping
    public ApiResponse<List<ProductImportDtos.JobSummary>> jobs() {
        return ApiResponse.ok(service.jobs());
    }

    @GetMapping("/{jobId}")
    public ApiResponse<ProductImportDtos.Preview> detail(@PathVariable Long jobId) {
        return ApiResponse.ok(service.detail(jobId));
    }

    @GetMapping("/{jobId}/source-file")
    public ResponseEntity<byte[]> sourceFile(@PathVariable Long jobId) {
        ProductImportService.SourceFileContent content = service.sourceFile(jobId);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(content.fileName(), StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.parseMediaType(content.contentType()))
                .body(content.content());
    }

    @PutMapping("/{jobId}/rows/{rowId}/decision")
    public ApiResponse<ProductImportDtos.Preview> decide(
            @PathVariable Long jobId,
            @PathVariable Long rowId,
            @Valid @RequestBody ProductImportDtos.RowDecision request) {
        return ApiResponse.ok(service.decide(jobId, rowId, request));
    }

    @PostMapping("/{jobId}/commit")
    public ApiResponse<ProductImportDtos.CommitResult> commit(
            @PathVariable Long jobId,
            @Valid @RequestBody ProductImportDtos.CommitRequest request) {
        return ApiResponse.ok(service.commit(jobId, request));
    }
}
