package com.soyia.copperv2.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

public final class EvidenceDtos {
    private EvidenceDtos() {}

    public record AttachmentView(Long id, String documentType, Long documentId,
                                 String evidenceType, String sourceChannel,
                                 String originalFileName, String archiveFileName,
                                 String contentType, Long fileSize, String sha256,
                                 String status, String note, String uploadedByName,
                                 LocalDateTime uploadedAt, String voidedByName,
                                 String voidReason, LocalDateTime voidedAt,
                                 Boolean canDelete) {}

    public record VoidRequest(@NotBlank String reason) {}

    public record OutputRecordRequest(
            @NotBlank String outputType,
            @NotBlank @Size(max = 64) String templateVersion,
            @NotBlank @Size(max = 255) String outputFileName,
            @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String snapshotHash) {}

    public record OutputRecordView(Long id, String documentType, Long documentId,
                                   String documentNoSnapshot, String statusSnapshot,
                                   String outputType, String templateVersion,
                                   String outputFileName, String snapshotHash,
                                   String operatorName, LocalDateTime createdAt) {}

    public record AttachmentContent(String fileName, String contentType, byte[] bytes) {}
}
