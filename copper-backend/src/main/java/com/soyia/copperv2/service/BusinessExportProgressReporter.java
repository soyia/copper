package com.soyia.copperv2.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BusinessExportProgressReporter {
    private final JdbcTemplate jdbc;

    public BusinessExportProgressReporter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void report(String exportId, int progressPercent, String phase) {
        int progress = Math.max(0, Math.min(progressPercent, 99));
        jdbc.update("""
                UPDATE business_export_task
                SET progress_percent=GREATEST(progress_percent, ?), current_phase=?
                WHERE id=? AND status='RUNNING' AND active_guard=1
                """, progress, phase, exportId);
    }
}
