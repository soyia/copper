package com.soyia.copperv2.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

@Component
public class AttachmentBlobMigrationRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(AttachmentBlobMigrationRunner.class);
    private static final int BATCH_SIZE = 5;

    private final JdbcTemplate jdbcTemplate;
    private final LocalFileStorageService fileStorageService;

    public AttachmentBlobMigrationRunner(JdbcTemplate jdbcTemplate,
                                         LocalFileStorageService fileStorageService) {
        this.jdbcTemplate = jdbcTemplate;
        this.fileStorageService = fileStorageService;
    }

    @Override
    public void run(ApplicationArguments args) {
        int attachmentCount = migrate("inbound_attachment", "inbound_id", "inbounds");
        int snapshotCount = migrate("inbound_review_snapshot_attachment", "snapshot_id",
                "inbound-review-snapshots");
        if (attachmentCount + snapshotCount > 0) {
            log.info("旧附件 BLOB 已迁移到本地文件存储: 入库附件 {} 个, 审核快照 {} 个",
                    attachmentCount, snapshotCount);
        }
    }

    private int migrate(String tableName, String parentColumn, String directoryPrefix) {
        int migrated = 0;
        while (true) {
            List<LegacyAttachment> rows = jdbcTemplate.query("""
                    select id,%s parent_id,content_type,storage_path,file_content
                    from %s
                    where file_content is not null
                    order by id
                    limit ?
                    """.formatted(parentColumn, tableName), (rs, rowNum) -> new LegacyAttachment(
                    rs.getLong("id"), rs.getLong("parent_id"), rs.getString("content_type"),
                    rs.getString("storage_path"), rs.getBytes("file_content")), BATCH_SIZE);
            if (rows.isEmpty()) return migrated;
            for (LegacyAttachment row : rows) {
                if (row.content() == null) continue;
                String storagePath = row.storagePath();
                if (!StringUtils.hasText(storagePath) || !fileStorageService.exists(storagePath)) {
                    String extension = fileStorageService.extensionForContentType(row.contentType());
                    storagePath = fileStorageService.storeNamed(row.content(),
                            directoryPrefix + "/" + row.parentId(), "legacy-" + row.id() + extension);
                }
                int changed = jdbcTemplate.update("update " + tableName
                                + " set storage_path=?,file_content=null where id=? and file_content is not null",
                        storagePath, row.id());
                migrated += changed;
            }
        }
    }

    private record LegacyAttachment(long id, long parentId, String contentType, String storagePath,
                                    byte[] content) {
    }
}
