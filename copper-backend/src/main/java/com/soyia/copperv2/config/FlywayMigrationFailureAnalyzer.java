package com.soyia.copperv2.config;

import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.diagnostics.FailureAnalyzer;

public class FlywayMigrationFailureAnalyzer implements FailureAnalyzer {
    @Override
    public FailureAnalysis analyze(Throwable failure) {
        Throwable flywayFailure = findFlywayFailure(failure);
        if (flywayFailure == null) return null;
        String detail = flywayFailure.getMessage() == null ? flywayFailure.getClass().getSimpleName() : flywayFailure.getMessage();
        if (detail.length() > 800) detail = detail.substring(0, 800);
        return new FailureAnalysis(
                "数据库迁移失败，Copper 已中止启动且不会进入就绪状态。失败信息：" + detail,
                "保持业务服务停止；不要修改已发布迁移。核对失败版本和 flyway_schema_history，先备份数据库，修复未发布迁移或恢复备份后再重新启动。",
                flywayFailure
        );
    }

    private Throwable findFlywayFailure(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            Package typePackage = current.getClass().getPackage();
            String packageName = typePackage == null ? "" : typePackage.getName();
            if (packageName.startsWith("org.flywaydb.")) return current;
            current = current.getCause();
        }
        return null;
    }
}
