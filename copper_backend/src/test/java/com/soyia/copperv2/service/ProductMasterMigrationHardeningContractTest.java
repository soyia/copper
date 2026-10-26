package com.soyia.copperv2.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductMasterMigrationHardeningContractTest {
    @Test
    void v49RunsCompatibilityPreflightBeforePermanentChangesAndAssertsCompletion() throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/V49__complex_product_catalog_units_and_precision.sql"));

        int preflight = sql.indexOf("CREATE TEMPORARY TABLE tmp_v49_preflight_guard");
        int preflightClosed = sql.indexOf("DROP TEMPORARY TABLE tmp_v49_preflight_guard");
        int firstPermanentChange = sql.indexOf("ALTER TABLE unit");
        int completion = sql.indexOf("CREATE TEMPORARY TABLE tmp_v49_completion_assertion");
        int lastPermanentChange = sql.lastIndexOf("ALTER TABLE product_series_package_unit");
        assertTrue(preflight >= 0 && preflightClosed > preflight);
        assertTrue(firstPermanentChange > preflightClosed,
                "所有可判定的兼容性预检必须早于首条永久DDL/DML");
        assertTrue(completion > lastPermanentChange,
                "目录、单位、模板、字段与身份规则必须在迁移末尾统一断言");
        for (String marker : new String[]{
                "tmp_v49_required_unit", "packaging_template_code",
                "min(r.revision_status)<>'PUBLISHED'", "count(distinct base_u.id)<>1",
                "count(distinct default_sale_u.id)<>1"
        }) {
            assertTrue(sql.substring(0, firstPermanentChange).contains(marker), marker);
        }
        assertFalse(sql.substring(firstPermanentChange, completion)
                .contains("tmp_product_field_collision_guard"));
        assertFalse(sql.substring(firstPermanentChange, completion)
                .contains("tmp_series_field_binding_guard"));
        assertFalse(sql.substring(firstPermanentChange, completion)
                .contains("tmp_complex_series_collision_guard"));
    }

    @Test
    void unitTokensUseOneWhitespaceFreeLowercaseNamespace() throws Exception {
        String migration = Files.readString(Path.of(
                "src/main/resources/db/migration/V49__complex_product_catalog_units_and_precision.sql"));
        String copper = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/service/CopperService.java"));
        String importer = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/service/ProductImportService.java"));

        assertTrue(migration.contains("CREATE TABLE unit_lookup_token"));
        assertTrue(migration.contains("PRIMARY KEY (normalized_token)"));
        assertTrue(migration.contains(
                "lower(regexp_replace(trim(alias_name),'[[:space:]]+',''))"));
        assertTrue(migration.contains("count(distinct logical_unit_key)<>1"),
                "迁移前必须发现跨名称、缩写、别名的碰撞");
        assertTrue(copper.contains("UnitLookupNormalizer.normalize(value)"));
        assertTrue(copper.contains("replaceUnitLookupTokens"));
        assertTrue(importer.contains("UnitLookupNormalizer.normalize(value)"));
    }

    @Test
    void migrationAndRuntimeShareLengthPrefixedIdentityHashChain() throws Exception {
        String migration = Files.readString(Path.of(
                "src/main/resources/db/migration/V47__complex_product_master_foundation.sql"));
        String master = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/service/ProductMasterDataService.java"));

        assertTrue(migration.contains("WITH RECURSIVE identity_chain"));
        assertTrue(migration.contains("octet_length(p.dimension_key)"));
        assertTrue(migration.contains("octet_length(p.canonical_value)"));
        assertTrue(migration.contains("never depends on group_concat_max_len"));
        assertFalse(migration.contains("SET SESSION group_concat_max_len"));
        assertFalse(migration.contains(
                "group_concat(concat(dimension_key,'=',canonical_value)"));
        assertTrue(master.contains("ProductIdentitySignature.calculate(schemaVersion, parts)"));
    }
}
