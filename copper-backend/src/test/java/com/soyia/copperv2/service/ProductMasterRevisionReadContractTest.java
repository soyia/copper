package com.soyia.copperv2.service;

import com.soyia.copperv2.controller.ComplexProductMasterController;
import com.soyia.copperv2.dto.ProductMasterDataDtos;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ProductMasterRevisionReadContractTest {
    @Test
    void snapshotMigrationBackfillsOnlyProvableCurrentRevisions() throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/V51__master_revision_head_snapshots.sql"));

        assertTrue(sql.contains("document_code_snapshot varchar(64) DEFAULT NULL"));
        assertTrue(sql.contains("supplier_name_snapshot varchar(255) DEFAULT NULL"));
        assertTrue(sql.contains("SET r.supplier_name_snapshot=NULL"),
                "迁移前没有可证明的历史供应商名称快照时必须保留NULL");
        assertFalse(sql.contains("JOIN partner p ON p.id=o.supplier_id"),
                "不能把迁移时的供应商现名伪造成历史发布快照");
        assertTrue(sql.contains("bom_code_snapshot varchar(64) DEFAULT NULL"));
        assertTrue(sql.contains("name_snapshot varchar(128) DEFAULT NULL"));
        assertTrue(sql.contains(
                "ON d.current_revision_id=r.id AND r.document_id=d.id"));
        assertTrue(sql.contains(
                "ON v.current_revision_id=r.id AND r.packaging_variant_id=v.id"));
        assertTrue(sql.contains(
                "ON o.current_revision_id=r.id AND r.supplier_product_offer_id=o.id"));
        assertTrue(sql.contains(
                "ON b.current_revision_id=r.id AND r.bom_id=b.id"));
        assertFalse(sql.contains(
                "JOIN product_source_document d ON d.id=r.document_id"));
        assertFalse(sql.contains(
                "JOIN product_bom b ON b.id=r.bom_id"));
    }

    @Test
    void supplierOfferPackagingValidationChecksThePublishedRevisionAndEnabledHead()
            throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/service/ComplexProductMasterService.java"));

        assertTrue(source.contains(
                "join packaging_variant v on v.id=r.packaging_variant_id"));
        assertTrue(source.contains("r.status='PUBLISHED'"));
        assertTrue(source.contains("v.status='ENABLED'"));
        assertTrue(source.contains(
                "停用包装不能发布或启用供应商供货档案"));
    }

    @Test
    void operationalHistoryRoutesExposePackagingAndSupplierSnapshots() throws Exception {
        ComplexProductMasterService complexProductMasterService =
                mock(ComplexProductMasterService.class);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
                new ComplexProductMasterController(complexProductMasterService)
        ).build();

        ProductMasterDataDtos.PackagingVariantRevision packagingRevision =
                new ProductMasterDataDtos.PackagingVariantRevision(
                        91L, 9L, 8L, "PKG-9", "包装第三版", "ENABLED", true,
                        3, "箱", "FIXED",
                        new BigDecimal("12.000000"), null, "12件/箱",
                        new BigDecimal("10.000000"), new BigDecimal("10.500000"),
                        new BigDecimal("0.500000"), new BigDecimal("300.000"),
                        new BigDecimal("200.000"), new BigDecimal("100.000"),
                        "6900000000000", null, 7L, 71L, "b".repeat(64),
                        "PUBLISHED", "修订装箱数", 11L, "资料管理员",
                        LocalDateTime.of(2026, 7, 4, 5, 6), true);
        when(complexProductMasterService.packagingVariantRevisions(8L, 9L))
                .thenReturn(List.of(packagingRevision));
        when(complexProductMasterService.supplierOfferRevisions(8L, 10L))
                .thenReturn(List.of());

        mockMvc.perform(get("/products/8/packaging-variants/9/revisions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].id").value(91))
                .andExpect(jsonPath("$.data[0].packagingVariantId").value(9))
                .andExpect(jsonPath("$.data[0].productId").value(8))
                .andExpect(jsonPath("$.data[0].revisionNo").value(3))
                .andExpect(jsonPath("$.data[0].variantCodeSnapshot").value("PKG-9"))
                .andExpect(jsonPath("$.data[0].nameSnapshot").value("包装第三版"))
                .andExpect(jsonPath("$.data[0].headStatusSnapshot").value("ENABLED"))
                .andExpect(jsonPath("$.data[0].fixedBaseQuantity").value(12.0))
                .andExpect(jsonPath("$.data[0].contentHash").value("b".repeat(64)))
                .andExpect(jsonPath("$.data[0].currentRevision").value(true));
        verify(complexProductMasterService).packagingVariantRevisions(8L, 9L);

        mockMvc.perform(get("/products/8/supplier-offers/10/revisions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
        verify(complexProductMasterService).supplierOfferRevisions(8L, 10L);
    }
}
