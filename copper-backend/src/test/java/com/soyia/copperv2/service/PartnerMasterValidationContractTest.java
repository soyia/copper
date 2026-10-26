package com.soyia.copperv2.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PartnerMasterValidationContractTest {
    @Test
    void createAndUpdateRequireCreditCodeExceptForIndividuals() throws Exception {
        String service = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/service/CopperService.java"));

        assertEquals(2, occurrences(service, "validatePartnerCreditCode(req);"));
        assertTrue(service.contains("!\"INDIVIDUAL\".equals(normalizedEntityType)"));
        assertTrue(service.contains("企业和个体工商户必须填写统一社会信用代码"));
    }

    @Test
    void openingMigrationReportsMissingCompanyCreditCodeDuringPreflight() throws Exception {
        String migrationService = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/service/DataMigrationService.java"));

        assertTrue(migrationService.contains("PARTNER_CREDIT_CODE_REQUIRED"));
        assertTrue(migrationService.contains("新建企业往来单位必须填写统一社会信用代码"));
    }

    private int occurrences(String value, String token) {
        return (value.length() - value.replace(token, "").length()) / token.length();
    }
}
