package com.soyia.copperv2.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductionImageBaselineTest {
    @Test
    void productionImageAndConfigurationKeepSecurityBaseline() throws Exception {
        String dockerfile = Files.readString(Path.of("Dockerfile"));
        String application = Files.readString(Path.of("src/main/resources/application.yml"));
        String example = Files.readString(Path.of(".env.example"));
        String publishWorkflow = Files.readString(Path.of(".github/workflows/publish-m1-image.yml"));
        String transferWorkflow = Files.readString(Path.of(".github/workflows/transfer-m7-internal-image.yml"));

        assertTrue(dockerfile.contains("maven:3.9.9-eclipse-temurin-17@sha256:"));
        assertTrue(dockerfile.contains("eclipse-temurin:17-jre@sha256:"));
        assertTrue(dockerfile.contains("USER copper:copper"));
        assertTrue(dockerfile.contains("VOLUME [\"/app/data\"]"));
        assertTrue(dockerfile.contains("SPRING_PROFILES_ACTIVE=prod"));
        assertTrue(dockerfile.contains("/actuator/health/liveness"));

        assertFalse(application.contains("123456"));
        assertFalse(application.contains("secure(false)"));
        assertFalse(application.contains("StatementCreatorUtils: trace"));
        assertFalse(example.contains("COPPER_DB_PASSWORD="));
        assertTrue(example.contains("COPPER_DB_PASSWORD_FILE="));
        assertTrue(publishWorkflow.contains("docker pull --platform \"$PLATFORM\" \"$exact\""));
        assertTrue(publishWorkflow.contains("docker image inspect --format '{{.Architecture}}' \"$delivery_tag\""));
        assertFalse(publishWorkflow.contains("docker image inspect --platform"));
        assertTrue(publishWorkflow.contains("internal_rehearsal_without_artifacts:"));
        assertTrue(publishWorkflow.contains("description: INTERNAL_REHEARSAL_ONLY"));
        assertTrue(publishWorkflow.contains("github.event_name == 'workflow_dispatch' && inputs.internal_rehearsal_without_artifacts"));
        assertFalse(publishWorkflow.contains("continue-on-error: true"));
        assertTrue(publishWorkflow.contains("fifteen-product-category-acceptance:\n"));
        assertTrue(publishWorkflow.contains("run: bash tests/run-15-product-category-mysql.sh"));
        assertEquals(2L, publishWorkflow.lines()
                .filter(line -> line.trim().equals("- fifteen-product-category-acceptance"))
                .count(), "PR 验证与 main 镜像构建必须同时依赖 15 类商品 MySQL 验收");
        assertTrue(transferWorkflow.contains("workflow_dispatch:"));
        assertFalse(transferWorkflow.contains("\n  push:"));
        assertFalse(transferWorkflow.contains("\n  pull_request:"));
        assertTrue(transferWorkflow.contains("description: INTERNAL_REHEARSAL_ONLY"));
        assertTrue(transferWorkflow.contains("^m7-internal-transfer-[0-9A-Za-z._-]"));
        assertTrue(transferWorkflow.contains("Internal transfer is forbidden for a public repository"));
        assertTrue(transferWorkflow.contains("Transfer target must be an existing private draft release"));
        assertTrue(transferWorkflow.contains("contents: write"));
        assertTrue(transferWorkflow.contains("transfer_draft_id:"));
        assertTrue(transferWorkflow.contains("releases/$TRANSFER_DRAFT_ID"));
        assertTrue(transferWorkflow.contains("https://uploads.github.com/repos/$GITHUB_REPOSITORY"));
        assertTrue(transferWorkflow.contains("--retry 3"));
        assertFalse(transferWorkflow.contains("Authorization: Bearer"));
        assertFalse(transferWorkflow.contains("gh release upload \"$TRANSFER_TAG\""));
        assertFalse(transferWorkflow.contains("actions/upload-artifact"));
        assertFalse(publishWorkflow.contains("internal_rehearsal_transfer_draft_tag"));
    }
}
