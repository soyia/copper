package com.soyia.copperv2.service;

import com.soyia.copperv2.dto.BusinessExportDtos.TaskList;
import com.soyia.copperv2.dto.BusinessExportDtos.TaskView;
import com.soyia.copperv2.security.BusinessDataScopeService;
import com.soyia.copperv2.security.SecurityContext;
import com.soyia.copperv2.security.SecurityAuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskExecutor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

@Service
public class BusinessExportService {
    private static final Logger log = LoggerFactory.getLogger(BusinessExportService.class);
    private static final List<String> ACTIVE_STATUSES = List.of("PENDING", "RUNNING");

    private final JdbcTemplate jdbc;
    private final BusinessDataScopeService dataScopeService;
    private final BusinessExportArchiveWriter archiveWriter;
    private final TaskExecutor executor;
    private final ZoneId zoneId;
    private final StorageCapacityService storageCapacityService;
    private final SecurityAuditService auditService;

    public BusinessExportService(
            JdbcTemplate jdbc,
            BusinessDataScopeService dataScopeService,
            BusinessExportArchiveWriter archiveWriter,
            @Qualifier("businessExportExecutor") TaskExecutor executor,
            @Value("${spring.jackson.time-zone}") String timeZone,
            StorageCapacityService storageCapacityService,
            SecurityAuditService auditService
    ) {
        this.jdbc = jdbc;
        this.dataScopeService = dataScopeService;
        this.archiveWriter = archiveWriter;
        this.executor = executor;
        this.zoneId = ZoneId.of(timeZone);
        this.storageCapacityService = storageCapacityService;
        this.auditService = auditService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterruptedTasks() {
        List<String> interrupted = jdbc.queryForList(
                "SELECT id FROM business_export_task WHERE status IN ('PENDING','RUNNING')", String.class);
        if (!interrupted.isEmpty()) {
            jdbc.update("""
                    UPDATE business_export_task
                    SET status='FAILED', progress_percent=0, current_phase='INTERRUPTED', active_guard=NULL,
                        error_code='APPLICATION_RESTARTED', error_message='应用重启导致导出中断，请重新生成', completed_at=NOW()
                    WHERE status IN ('PENDING','RUNNING')
                    """);
            for (String id : interrupted) audit("BUSINESS_EXPORT_INTERRUPTED", id, "应用重启，导出任务已安全标记为失败");
        }
        archiveWriter.cleanupInterruptedWork();
        archiveWriter.cleanupInterruptedArtifacts(interrupted);
    }

    @Transactional
    public TaskView createFullExport(String ipAddress) {
        storageCapacityService.assertLargeWriteAllowed("完整业务导出");
        dataScopeService.assertFullExportAllowed();
        SecurityContext.Principal principal = SecurityContext.require();
        String id = UUID.randomUUID().toString();
        try {
            jdbc.update("""
                    INSERT INTO business_export_task
                    (id, format_version, scope_type, filters_json, status, progress_percent, current_phase,
                     requested_by_user_id, requested_by_name, requested_from_ip, active_guard)
                    VALUES (?, 'copper-export/v1', 'FULL', JSON_OBJECT('modules','ALL','businessDateFrom','','businessDateTo','','dataScope','ALL'),
                            'PENDING', 0, 'WAITING', ?, ?, ?, 1)
                    """, id, principal.userId(), safe(principal.employeeName(), 100), safe(ipAddress, 64));
        } catch (DuplicateKeyException ex) {
            return activeTask();
        }
        audit("BUSINESS_EXPORT_REQUESTED", id, "已申请生成完整业务导出");
        submitAfterCommit(id);
        return task(id);
    }

    @Transactional
    public TaskView retry(String originalId, String ipAddress) {
        storageCapacityService.assertLargeWriteAllowed("完整业务导出重新生成");
        dataScopeService.assertFullExportAllowed();
        TaskView original = task(originalId);
        if (!"FAILED".equals(original.status())) throw new BusinessException("只有失败的导出任务可以重新生成");
        SecurityContext.Principal principal = SecurityContext.require();
        String id = UUID.randomUUID().toString();
        try {
            jdbc.update("""
                    INSERT INTO business_export_task
                    (id, format_version, scope_type, filters_json, status, progress_percent, current_phase,
                     requested_by_user_id, requested_by_name, requested_from_ip, retry_of, active_guard)
                    VALUES (?, 'copper-export/v1', 'FULL', JSON_OBJECT('modules','ALL','businessDateFrom','','businessDateTo','','dataScope','ALL'),
                            'PENDING', 0, 'WAITING', ?, ?, ?, ?, 1)
                    """, id, principal.userId(), safe(principal.employeeName(), 100), safe(ipAddress, 64), originalId);
        } catch (DuplicateKeyException ex) {
            return activeTask();
        }
        audit("BUSINESS_EXPORT_RETRIED", id, "重新生成失败任务 " + originalId);
        submitAfterCommit(id);
        return task(id);
    }

    public TaskList list() {
        dataScopeService.assertFullExportAllowed();
        List<TaskView> items = jdbc.query("""
                SELECT * FROM business_export_task ORDER BY requested_at DESC, id DESC LIMIT 100
                """, this::mapTask);
        boolean active = items.stream().anyMatch(item -> ACTIVE_STATUSES.contains(item.status()));
        return new TaskList(items, active);
    }

    public TaskView task(String id) {
        dataScopeService.assertFullExportAllowed();
        requireUuid(id);
        List<TaskView> rows = jdbc.query("SELECT * FROM business_export_task WHERE id=?", this::mapTask, id);
        if (rows.isEmpty()) throw new BusinessException("导出任务不存在");
        return rows.get(0);
    }

    @Transactional
    public DownloadFile download(String id) {
        dataScopeService.assertFullExportAllowed();
        TaskView view = task(id);
        if (!"COMPLETED".equals(view.status()) || !view.downloadable()) {
            throw new BusinessException("导出任务尚未成功完成，不能下载");
        }
        String relativePath = jdbc.queryForObject(
                "SELECT relative_path FROM business_export_task WHERE id=?", String.class, id);
        Path path = archiveWriter.resolveCompletedArchive(relativePath);
        jdbc.update("UPDATE business_export_task SET downloaded_at=NOW() WHERE id=?", id);
        audit("BUSINESS_EXPORT_DOWNLOADED", id, "已下载完整业务导出");
        return new DownloadFile(view.fileName(), path, view.archiveSha256());
    }

    private void submitAfterCommit(String id) {
        Runnable submit = () -> {
            try {
                executor.execute(() -> runTask(id));
            } catch (RuntimeException ex) {
                markFailed(id, "EXECUTOR_REJECTED", "导出执行队列不可用，请稍后重试");
            }
        };
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            submit.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                submit.run();
            }
        });
    }

    private void runTask(String id) {
        String relativePath = "";
        try {
            int changed = jdbc.update("""
                    UPDATE business_export_task
                    SET status='RUNNING', progress_percent=5, current_phase='CONSISTENT_SNAPSHOT', started_at=NOW(),
                        error_code='', error_message=''
                    WHERE id=? AND status='PENDING' AND active_guard=1
                    """, id);
            if (changed != 1) return;
            audit("BUSINESS_EXPORT_STARTED", id, "开始生成完整业务导出");
            Requester requester = jdbc.queryForObject("""
                    SELECT requested_by_user_id, requested_by_name FROM business_export_task WHERE id=?
                    """, (rs, rowNum) -> new Requester(rs.getLong(1), rs.getString(2)), id);
            if (requester == null) throw new IllegalStateException("导出任务申请人不存在");
            BusinessExportArchiveWriter.ArchiveResult result = archiveWriter.writeFullExport(
                    id, requester.userId(), requester.name());
            relativePath = result.relativePath();
            jdbc.update("UPDATE business_export_task SET progress_percent=95, current_phase='VERIFYING_ARCHIVE' WHERE id=?", id);
            int completed = jdbc.update("""
                    UPDATE business_export_task
                    SET status='COMPLETED', progress_percent=100, current_phase='COMPLETED', active_guard=NULL,
                        file_name=?, relative_path=?, archive_bytes=?, archive_sha256=?, exported_table_count=?,
                        exported_record_count=?, exported_attachment_count=?, completed_at=NOW()
                    WHERE id=? AND status='RUNNING'
                    """, result.fileName(), result.relativePath(), result.archiveBytes(), result.archiveSha256(),
                    result.tableCount(), result.recordCount(), result.attachmentCount(), id);
            if (completed != 1) {
                archiveWriter.deleteArchive(relativePath);
                throw new IllegalStateException("导出任务状态在完成前发生变化");
            }
            audit("BUSINESS_EXPORT_COMPLETED", id,
                    "完整业务导出已完成：" + result.tableCount() + " 张表，" + result.recordCount() + " 条记录，"
                            + result.attachmentCount() + " 个附件");
        } catch (RuntimeException ex) {
            if (!relativePath.isBlank()) archiveWriter.deleteArchive(relativePath);
            log.error("完整业务导出失败 exportId={} type={}", id, ex.getClass().getSimpleName());
            log.debug("完整业务导出失败调试栈 exportId={}", id, ex);
            String code = ex instanceof BusinessException ? "BUSINESS_RULE" : ex.getClass().getSimpleName().toUpperCase();
            markFailed(id, safe(code, 64), safeError(ex));
        }
    }

    private void markFailed(String id, String code, String message) {
        jdbc.update("""
                UPDATE business_export_task
                SET status='FAILED', progress_percent=0, current_phase='FAILED', active_guard=NULL,
                    error_code=?, error_message=?, completed_at=NOW()
                WHERE id=? AND status IN ('PENDING','RUNNING')
                """, safe(code, 64), safe(message, 500), id);
        audit("BUSINESS_EXPORT_FAILED", id, "完整业务导出失败：" + safe(message, 300));
    }

    private TaskView activeTask() {
        List<TaskView> rows = jdbc.query("""
                SELECT * FROM business_export_task
                WHERE active_guard=1 AND status IN ('PENDING','RUNNING')
                ORDER BY requested_at DESC LIMIT 1 FOR UPDATE
                """, this::mapTask);
        if (rows.isEmpty()) throw new BusinessException("导出任务状态冲突，请刷新后重试");
        return rows.get(0);
    }

    private TaskView mapTask(ResultSet rs, int rowNum) throws SQLException {
        String status = rs.getString("status");
        String fileName = rs.getString("file_name");
        return new TaskView(
                rs.getString("id"), rs.getString("format_version"), rs.getString("scope_type"), status,
                rs.getInt("progress_percent"), rs.getString("current_phase"), rs.getLong("requested_by_user_id"),
                rs.getString("requested_by_name"), rs.getString("retry_of"), fileName,
                rs.getLong("archive_bytes"), rs.getString("archive_sha256"), rs.getLong("exported_table_count"),
                rs.getLong("exported_record_count"), rs.getLong("exported_attachment_count"),
                rs.getString("error_code"), rs.getString("error_message"), format(rs.getTimestamp("requested_at")),
                format(rs.getTimestamp("started_at")), format(rs.getTimestamp("completed_at")),
                format(rs.getTimestamp("downloaded_at")), "COMPLETED".equals(status) && fileName != null && !fileName.isBlank()
        );
    }

    private void audit(String action, String exportId, String summary) {
        ExportRequester requester = jdbc.query("""
                SELECT requested_by_user_id, requested_by_name, requested_from_ip
                FROM business_export_task WHERE id=?
                """, rs -> rs.next() ? new ExportRequester(rs.getLong(1), rs.getString(2), rs.getString(3)) : null, exportId);
        if (requester == null) return;
        boolean failed = action.endsWith("_FAILED") || action.endsWith("_INTERRUPTED");
        auditService.recordForActor(requester.userId(), requester.name(), null, requester.ipAddress(), action,
                failed ? SecurityAuditService.FAILED : SecurityAuditService.SUCCESS,
                failed ? action : "", "BUSINESS_EXPORT", 0L, exportId,
                safe(summary + " [exportId=" + exportId + "]", 500), null, null, "");
    }

    private String format(Timestamp timestamp) {
        return timestamp == null ? "" : timestamp.toLocalDateTime().atZone(zoneId).toOffsetDateTime().toString();
    }

    private String safeError(RuntimeException ex) {
        Throwable current = ex;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        String message = current.getMessage();
        if (message == null || message.isBlank()) message = "生成失败，请检查服务器日志并重新生成";
        message = message.replace('\r', ' ').replace('\n', ' ');
        return safe(message, 500);
    }

    private String safe(String value, int max) {
        String text = value == null ? "" : value.trim();
        return text.length() <= max ? text : text.substring(0, max);
    }

    private void requireUuid(String id) {
        try {
            UUID.fromString(id);
        } catch (RuntimeException ex) {
            throw new BusinessException("导出任务编号格式不正确");
        }
    }

    public record DownloadFile(String fileName, Path path, String sha256) {}
    private record Requester(Long userId, String name) {}
    private record ExportRequester(Long userId, String name, String ipAddress) {}
}
