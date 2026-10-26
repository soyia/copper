package com.soyia.copperv2.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.soyia.copperv2.dto.PackagingDtos;
import com.soyia.copperv2.security.BusinessDataScopeService;
import com.soyia.copperv2.security.SecurityAuditService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PackagingServiceTest {

    private final PackagingService service = new PackagingService(
            mock(JdbcTemplate.class), new ObjectMapper(), mock(BusinessDataScopeService.class),
            mock(SecurityAuditService.class));

    @Test
    void templateCodeRejectsCharactersOutsideLettersDigitsDashAndUnderscore() {
        PackagingDtos.TemplateSave request = new PackagingDtos.TemplateSave(
                "中文 编码", "测试模板", "", "ENABLED", 0, null, "测试非法编码");

        BusinessException error = assertThrows(
                BusinessException.class, () -> service.createTemplate(request));

        assertEquals("模板编码只能使用数字、英文字母、短横线和下划线", error.getMessage());
    }

    @Test
    @SuppressWarnings("unchecked")
    void batchProfileUsesImmutableSnapshotWhenStructuredBatchHasNoPersistedVersion() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        BusinessDataScopeService dataScopeService = mock(BusinessDataScopeService.class);
        PackagingService batchService = new PackagingService(
                jdbcTemplate, new ObjectMapper(), dataScopeService, mock(SecurityAuditService.class));
        String snapshot = systemDefaultSnapshot();
        when(jdbcTemplate.query(contains("from inventory_batch"), any(RowMapper.class), eq(99L)))
                .thenAnswer(invocation -> {
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getLong("product_id")).thenReturn(1L);
                    when(rs.getLong("warehouse_id")).thenReturn(7L);
                    when(rs.getInt("packaging_version_no")).thenReturn(0);
                    when(rs.getLong("packaging_effective_revision_id")).thenReturn(0L);
                    when(rs.getString("packaging_profile_snapshot_json")).thenReturn(snapshot);
                    when(rs.getString("packaging_tracking_mode")).thenReturn("STRUCTURED");
                    RowMapper<Object> mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(rs, 0));
                });
        when(jdbcTemplate.query(contains("from product p"), any(RowMapper.class), eq(1L)))
                .thenAnswer(invocation -> {
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getLong("id")).thenReturn(1L);
                    when(rs.getString("name")).thenReturn("M1验收商品");
                    when(rs.getString("unit")).thenReturn("千克");
                    when(rs.getLong("product_series_id")).thenReturn(2016L);
                    when(rs.getString("series_name")).thenReturn("M1验收系列");
                    RowMapper<Object> mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(rs, 0));
                });

        PackagingDtos.Profile profile = batchService.batchProfile(99L);

        assertSystemDefaultProfile(profile);
        verify(dataScopeService, atLeastOnce()).assertMasterAllowed("PRODUCT", 1L);
        verify(dataScopeService).assertMasterAllowed("WAREHOUSE", 7L);
    }

    @Test
    void reconstructsSystemDefaultBatchProfileFromImmutableSnapshot() {
        PackagingDtos.Profile profile = service.immutableBatchSnapshotProfile(
                systemDefaultSnapshot(), 1L, "M1验收商品");

        assertSystemDefaultProfile(profile);
    }

    @Test
    @SuppressWarnings("unchecked")
    void packageBarcodeDoesNotReturnProductOutsideCurrentScope() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        BusinessDataScopeService dataScopeService = mock(BusinessDataScopeService.class);
        PackagingService scopedService = new PackagingService(
                jdbcTemplate, new ObjectMapper(), dataScopeService, mock(SecurityAuditService.class));
        PackagingDtos.PackageBarcodeMatch unauthorized = new PackagingDtos.PackageBarcodeMatch(
                9L, "SECRET-SKU", "无权商品", "箱", "SECRET-BARCODE",
                "NONE", null, "");
        when(jdbcTemplate.query(
                contains("from product_package_definition d"), any(RowMapper.class), eq("SECRET-BARCODE")))
                .thenReturn(List.of(unauthorized));
        when(dataScopeService.filterMasterIds("PRODUCT", List.of(9L))).thenReturn(Set.of());

        BusinessException error = assertThrows(
                BusinessException.class, () -> scopedService.packageBarcode("SECRET-BARCODE"));

        assertEquals("未找到此包装条码对应的商品", error.getMessage());
        verify(dataScopeService).filterMasterIds("PRODUCT", List.of(9L));
    }

    @Test
    void kgBusinessQuantityAcceptsSeparateActualWeightPackageCount() {
        String snapshot = """
                {
                  "policy": {
                    "baseUnit": "kg",
                    "minimumSalesUnit": "kg",
                    "minimumOutboundUnit": "kg",
                    "defaultPurchaseUnit": "kg",
                    "defaultSalesUnit": "kg",
                    "roundingPolicy": "REJECT"
                  },
                  "rules": [{
                    "parentUnit": "盘",
                    "childUnit": "kg",
                    "conversionMode": "ACTUAL_WEIGHT",
                    "integerOnly": true,
                    "status": "ENABLED"
                  }]
                }
                """;
        PackagingDtos.Plan plan = new PackagingDtos.Plan(
                new BigDecimal("63"), "kg", new BigDecimal("63"), "kg",
                new BigDecimal("63"), "kg", false, false, "REJECT", "",
                List.of(new PackagingDtos.Component("kg", new BigDecimal("63"), new BigDecimal("63"), "BASE")),
                snapshot, false, 1L, "snapshot", "ACTUAL");
        List<PackagingDtos.PackageInstanceInput> instances = List.of(
                new PackagingDtos.PackageInstanceInput("P-01", new BigDecimal("21")),
                new PackagingDtos.PackageInstanceInput("P-02", new BigDecimal("22")),
                new PackagingDtos.PackageInstanceInput("P-03", new BigDecimal("20"))
        );

        assertDoesNotThrow(() -> service.validatePackagingQuantity(
                plan, new BigDecimal("3"), "盘"));
        assertDoesNotThrow(() -> service.validateInboundPackageInstances(
                plan, new BigDecimal("3"), "盘", instances));
    }

    @Test
    void actualWeightDraftAllowsMissingWeightsButSubmissionRequiresAllRows() {
        String snapshot = """
                {
                  "policy": {
                    "baseUnit": "kg",
                    "minimumSalesUnit": "kg",
                    "minimumOutboundUnit": "kg",
                    "defaultPurchaseUnit": "kg",
                    "defaultSalesUnit": "kg",
                    "roundingPolicy": "REJECT"
                  },
                  "rules": [{
                    "parentUnit": "盘",
                    "childUnit": "kg",
                    "conversionMode": "ACTUAL_WEIGHT",
                    "integerOnly": true,
                    "status": "ENABLED"
                  }]
                }
                """;
        PackagingDtos.Plan plan = new PackagingDtos.Plan(
                new BigDecimal("63"), "kg", new BigDecimal("63"), "kg",
                new BigDecimal("63"), "kg", false, false, "REJECT", "",
                List.of(new PackagingDtos.Component("kg", new BigDecimal("63"), new BigDecimal("63"), "BASE")),
                snapshot, false, 1L, "snapshot", "ACTUAL");
        List<PackagingDtos.PackageInstanceInput> partial = List.of(
                new PackagingDtos.PackageInstanceInput("P-01", new BigDecimal("21")),
                new PackagingDtos.PackageInstanceInput("P-02", null),
                new PackagingDtos.PackageInstanceInput("", null)
        );

        assertDoesNotThrow(() -> service.validateInboundPackageInstancesDraft(
                plan, new BigDecimal("3"), "盘", partial));
        assertThrows(BusinessException.class, () -> service.validateInboundPackageInstances(
                plan, new BigDecimal("3"), "盘", partial));
    }

    @Test
    void fixedCountPackagingSupportsBreakableAndWholePackageRules() {
        PackagingDtos.Plan breakablePlan = fixedPackagePlan(new BigDecimal("25"), true);
        assertDoesNotThrow(() -> service.validatePackagingQuantity(
                breakablePlan, BigDecimal.ONE, "箱"));
        BusinessException tooManyBoxes = assertThrows(
                BusinessException.class,
                () -> service.validatePackagingQuantity(
                        breakablePlan, new BigDecimal("2"), "箱"));
        assertTrue(tooManyBoxes.getMessage().contains("超过填写数量25件"));

        PackagingDtos.Plan wholePackagePlan = fixedPackagePlan(new BigDecimal("40"), false);
        assertDoesNotThrow(() -> service.validatePackagingQuantity(
                wholePackagePlan, new BigDecimal("2"), "箱"));
        BusinessException notWholePackage = assertThrows(
                BusinessException.class,
                () -> service.validatePackagingQuantity(
                        fixedPackagePlan(new BigDecimal("25"), false), BigDecimal.ONE, "箱"));
        assertTrue(notWholePackage.getMessage().contains("与填写数量25件不一致"));
    }

    private PackagingDtos.Plan fixedPackagePlan(BigDecimal baseQuantity, boolean breakable) {
        String snapshot = """
                {
                  "policy": {
                    "baseUnit": "件",
                    "minimumSalesUnit": "件",
                    "minimumOutboundUnit": "件",
                    "defaultPurchaseUnit": "件",
                    "defaultSalesUnit": "件",
                    "roundingPolicy": "REJECT"
                  },
                  "rules": [{
                    "parentUnit": "箱",
                    "childUnit": "件",
                    "conversionMode": "FIXED",
                    "fixedChildQuantity": 20,
                    "breakable": %s,
                    "integerOnly": true,
                    "status": "ENABLED"
                  }]
                }
                """.formatted(breakable);
        return new PackagingDtos.Plan(
                baseQuantity, "件", baseQuantity, "件",
                baseQuantity, "件", false, false, "REJECT", "",
                List.of(new PackagingDtos.Component("件", baseQuantity, baseQuantity, "BASE")),
                snapshot, false, 1L, "snapshot", "ACTUAL");
    }

    private void assertSystemDefaultProfile(PackagingDtos.Profile profile) {
        assertEquals(1L, profile.productId());
        assertEquals("M1验收商品", profile.productName());
        assertEquals("千克", profile.baseUnit());
        assertEquals(0, profile.versionNo());
        assertNull(profile.effectiveRevisionId());
        assertEquals("SYSTEM_DEFAULT", profile.sourceLevel());
        assertEquals("snapshot-hash", profile.contentHash());
        assertTrue(profile.rules().isEmpty());
        assertEquals("按库存批次不可变快照履约", profile.warnings().get(0));
    }

    private String systemDefaultSnapshot() {
        return """
                {
                  "schemaVersion": 2,
                  "versionNo": 0,
                  "effectiveRevisionId": null,
                  "contentHash": "snapshot-hash",
                  "source": {
                    "sourceLevel": "SYSTEM_DEFAULT",
                    "templateId": 0,
                    "templateRevisionId": 0,
                    "seriesId": 2016,
                    "overrideRevisionId": 0
                  },
                  "policy": {
                    "baseUnit": "千克",
                    "minimumSalesUnit": "千克",
                    "minimumOutboundUnit": "千克",
                    "defaultPurchaseUnit": "千克",
                    "defaultSalesUnit": "千克",
                    "roundingPolicy": "REJECT"
                  },
                  "rules": []
                }
                """;
    }
}
