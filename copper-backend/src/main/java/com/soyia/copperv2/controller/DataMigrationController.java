package com.soyia.copperv2.controller;

import com.soyia.copperv2.dto.ApiResponse;
import com.soyia.copperv2.dto.DataMigrationDtos;
import com.soyia.copperv2.service.DataMigrationService;
import com.soyia.copperv2.service.OpeningMigrationWorkbook;
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
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/data-migrations")
public class DataMigrationController {
    private final DataMigrationService service;

    public DataMigrationController(DataMigrationService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<DataMigrationDtos.BatchRow>> list() {
        return ApiResponse.ok(service.list());
    }

    @GetMapping("/{id}")
    public ApiResponse<DataMigrationDtos.Detail> detail(@PathVariable Long id) {
        return ApiResponse.ok(service.detail(id));
    }

    @GetMapping("/template")
    public ResponseEntity<byte[]> template() {
        OpeningMigrationWorkbook.TemplateFile file = service.template();
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(file.fileName(), StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.parseMediaType(file.contentType()))
                .body(file.content());
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<DataMigrationDtos.Detail> upload(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate openingDate,
            @RequestParam("file") MultipartFile file) {
        return ApiResponse.ok(service.upload(openingDate, file));
    }

    @PostMapping("/{id}/apply")
    public ApiResponse<DataMigrationDtos.Detail> apply(@PathVariable Long id,
                                                        @Valid @RequestBody DataMigrationDtos.Action action) {
        return ApiResponse.ok(service.apply(id, action));
    }

    @PostMapping("/{id}/rollback")
    public ApiResponse<DataMigrationDtos.Detail> rollback(@PathVariable Long id,
                                                           @Valid @RequestBody DataMigrationDtos.Action action) {
        return ApiResponse.ok(service.rollback(id, action));
    }

    @PostMapping("/{id}/cutover")
    public ApiResponse<DataMigrationDtos.Detail> cutover(@PathVariable Long id,
                                                          @Valid @RequestBody DataMigrationDtos.Action action) {
        return ApiResponse.ok(service.cutover(id, action));
    }

    @PostMapping("/{id}/consistency-check")
    public ApiResponse<DataMigrationDtos.CheckRun> consistencyCheck(@PathVariable Long id) {
        return ApiResponse.ok(service.check(id));
    }
}
