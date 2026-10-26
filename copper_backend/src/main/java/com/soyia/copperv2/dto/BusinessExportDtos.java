package com.soyia.copperv2.dto;

import jakarta.validation.constraints.AssertTrue;

import java.util.List;

public final class BusinessExportDtos {
    private BusinessExportDtos() {}

    public record CreateRequest(
            @AssertTrue(message = "必须确认生成完整业务导出") boolean confirmFullExport
    ) {}

    public record RetryRequest(
            @AssertTrue(message = "必须确认重新生成完整业务导出") boolean confirmRetry
    ) {}

    public record TaskView(
            String id,
            String formatVersion,
            String scopeType,
            String status,
            int progressPercent,
            String currentPhase,
            Long requestedByUserId,
            String requestedByName,
            String retryOf,
            String fileName,
            long archiveBytes,
            String archiveSha256,
            long exportedTableCount,
            long exportedRecordCount,
            long exportedAttachmentCount,
            String errorCode,
            String errorMessage,
            String requestedAt,
            String startedAt,
            String completedAt,
            String downloadedAt,
            boolean downloadable
    ) {}

    public record TaskList(List<TaskView> items, boolean hasActiveTask) {}
}
