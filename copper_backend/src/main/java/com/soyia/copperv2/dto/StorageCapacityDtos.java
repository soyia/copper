package com.soyia.copperv2.dto;

import java.time.Instant;
import java.util.List;

public final class StorageCapacityDtos {
    private StorageCapacityDtos() {}

    public record Usage(
            long databaseBytes,
            long attachmentBytes,
            long exportBytes,
            long backupBytes,
            long logBytes
    ) {}

    public record Filesystem(
            long capacityBytes,
            long availableBytes,
            String riskLevel
    ) {}

    public record Target(
            String target,
            String type,
            String location,
            long backupCount,
            long backupBytes,
            Long capacityBytes,
            Long availableBytes,
            boolean quotaKnown,
            String quotaMessage,
            String riskLevel,
            boolean available,
            String errorCode
    ) {}

    public record LastSuccessfulBackup(
            String backupId,
            Instant completedAt,
            String archive
    ) {}

    public record Policy(
            String quotaSource,
            boolean artificialCopperQuota,
            String warning,
            String critical,
            String danger
    ) {}

    public record View(
            String formatVersion,
            Instant observedAt,
            boolean snapshotStale,
            String runtimeRiskLevel,
            Usage usage,
            Filesystem dataFilesystem,
            List<Target> targets,
            LastSuccessfulBackup lastSuccessfulBackup,
            Policy policy,
            String statusMessage
    ) {}
}
