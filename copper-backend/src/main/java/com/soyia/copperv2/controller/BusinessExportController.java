package com.soyia.copperv2.controller;

import com.soyia.copperv2.dto.ApiResponse;
import com.soyia.copperv2.dto.BusinessExportDtos.CreateRequest;
import com.soyia.copperv2.dto.BusinessExportDtos.RetryRequest;
import com.soyia.copperv2.dto.BusinessExportDtos.TaskList;
import com.soyia.copperv2.dto.BusinessExportDtos.TaskView;
import com.soyia.copperv2.service.BusinessExportService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.io.IOException;

@RestController
public class BusinessExportController {
    private final BusinessExportService exportService;

    public BusinessExportController(BusinessExportService exportService) {
        this.exportService = exportService;
    }

    @PostMapping("/business-exports")
    public ApiResponse<TaskView> create(@Valid @RequestBody CreateRequest request, HttpServletRequest servletRequest) {
        return ApiResponse.ok(exportService.createFullExport(clientIp(servletRequest)));
    }

    @GetMapping("/business-exports")
    public ApiResponse<TaskList> list() {
        return ApiResponse.ok(exportService.list());
    }

    @GetMapping("/business-exports/{id}")
    public ApiResponse<TaskView> task(@PathVariable String id) {
        return ApiResponse.ok(exportService.task(id));
    }

    @PostMapping("/business-exports/{id}/retry")
    public ApiResponse<TaskView> retry(@PathVariable String id, @Valid @RequestBody RetryRequest request,
                                       HttpServletRequest servletRequest) {
        return ApiResponse.ok(exportService.retry(id, clientIp(servletRequest)));
    }

    @GetMapping("/business-exports/{id}/download")
    public ResponseEntity<FileSystemResource> download(@PathVariable String id) throws IOException {
        BusinessExportService.DownloadFile file = exportService.download(id);
        FileSystemResource resource = new FileSystemResource(file.path());
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(resource.contentLength())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-SHA256", file.sha256())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(resource);
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) return forwarded.split(",")[0].trim();
        return request.getRemoteAddr();
    }
}
