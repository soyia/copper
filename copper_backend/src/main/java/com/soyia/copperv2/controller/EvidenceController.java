package com.soyia.copperv2.controller;

import com.soyia.copperv2.dto.ApiResponse;
import com.soyia.copperv2.dto.EvidenceDtos;
import com.soyia.copperv2.service.EvidenceAttachmentService;
import jakarta.validation.Valid;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/evidence")
public class EvidenceController {
    private final EvidenceAttachmentService service;

    public EvidenceController(EvidenceAttachmentService service) {
        this.service = service;
    }

    @GetMapping("/{documentType}/{documentId}/attachments")
    public ApiResponse<List<EvidenceDtos.AttachmentView>> attachments(
            @PathVariable String documentType, @PathVariable Long documentId) {
        return ApiResponse.ok(service.list(documentType, documentId));
    }

    @GetMapping("/{documentType}/{documentId}/outputs")
    public ApiResponse<List<EvidenceDtos.OutputRecordView>> outputs(
            @PathVariable String documentType, @PathVariable Long documentId) {
        return ApiResponse.ok(service.outputRecords(documentType, documentId));
    }

    @PostMapping("/{documentType}/{documentId}/outputs")
    public ApiResponse<EvidenceDtos.OutputRecordView> recordOutput(
            @PathVariable String documentType, @PathVariable Long documentId,
            @Valid @RequestBody EvidenceDtos.OutputRecordRequest request) {
        return ApiResponse.ok(service.recordOutput(documentType, documentId, request));
    }

    @PostMapping(value = "/{documentType}/{documentId}/attachments",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<EvidenceDtos.AttachmentView> upload(
            @PathVariable String documentType, @PathVariable Long documentId,
            @RequestParam(defaultValue = "OTHER") String evidenceType,
            @RequestParam(defaultValue = "UPLOAD") String sourceChannel,
            @RequestParam(defaultValue = "") String note,
            @RequestParam("file") MultipartFile file) {
        return ApiResponse.ok(service.upload(documentType, documentId, evidenceType, sourceChannel, note, file));
    }

    @GetMapping("/{documentType}/{documentId}/attachments/{attachmentId}/content")
    public ResponseEntity<byte[]> content(
            @PathVariable String documentType, @PathVariable Long documentId,
            @PathVariable Long attachmentId,
            @RequestParam(defaultValue = "false") boolean inline) {
        EvidenceDtos.AttachmentContent content = service.content(documentType, documentId, attachmentId);
        ContentDisposition disposition = (inline ? ContentDisposition.inline() : ContentDisposition.attachment())
                .filename(content.fileName(), StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.parseMediaType(content.contentType()))
                .body(content.bytes());
    }

    @DeleteMapping("/{documentType}/{documentId}/attachments/{attachmentId}")
    public ApiResponse<Map<String, Long>> delete(
            @PathVariable String documentType, @PathVariable Long documentId,
            @PathVariable Long attachmentId) {
        return ApiResponse.ok(Map.of("id",
                service.removeDraftAttachment(documentType, documentId, attachmentId)));
    }

    @PostMapping("/{documentType}/{documentId}/attachments/{attachmentId}/void")
    public ApiResponse<Map<String, Long>> voidAttachment(
            @PathVariable String documentType, @PathVariable Long documentId,
            @PathVariable Long attachmentId,
            @Valid @RequestBody EvidenceDtos.VoidRequest request) {
        return ApiResponse.ok(Map.of("id",
                service.voidAttachment(documentType, documentId, attachmentId, request.reason())));
    }
}
