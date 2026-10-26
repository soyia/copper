package com.soyia.copperv2.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.soyia.copperv2.security.SecurityContext;
import com.soyia.copperv2.security.ForbiddenException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProductMasterDataPermissionTest {
    private final JdbcTemplate jdbc = new EmptyReferenceJdbcTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ProductMasterDataService master = new ProductMasterDataService(
            jdbc, objectMapper, null);
    private final ComplexProductMasterService complex =
            new ComplexProductMasterService(
                    jdbc, objectMapper, master, null, null);

    @AfterEach
    void clearSecurityContext() {
        SecurityContext.clear();
    }

    @Test
    void complexStructuresAndWritesFailClosedWithoutComplexMasterPermissions() {
        setPermissions(Set.of("product.view", "product.manage"));

        assertDoesNotThrow(() -> master.referenceValues("GRADE", null, null));
        assertThrows(ForbiddenException.class,
                () -> complex.packagingVariants(1L));
        assertThrows(ForbiddenException.class,
                () -> complex.packagingVariantRevisions(1L, 1L));
        assertThrows(ForbiddenException.class,
                () -> master.sourceDocumentRevisions(1L));
        assertThrows(ForbiddenException.class,
                () -> complex.savePorts(1L, List.of()));
    }

    @Test
    void unknownPublishedGradeIsRejectedByTheSharedResolver() {
        setPermissions(Set.of("product.master.complex.view"));

        assertThrows(BusinessException.class,
                () -> master.resolveReference("GRADE", "UNKNOWN-MAGNET-GRADE", false));
    }

    @Test
    void legacyProductReadProjectionDoesNotRequireComplexMasterPermission() {
        setPermissions(Set.of("product.view"));

        assertEquals(List.of(),
                master.referenceValuesForProductRead("STANDARD", null, null));
    }

    @Test
    void qualityManagePermissionIncludesServiceLevelReadback() throws Exception {
        setPermissions(Set.of("inventory.quality.manage"));
        Method required = ComplexProductMasterService.class
                .getDeclaredMethod("requireQualityViewPermission");
        required.setAccessible(true);

        assertDoesNotThrow(() -> required.invoke(complex));
    }

    @Test
    void batchQualityWriteRequiresComplexInventoryWarehouseOrPurchaseAuthority() {
        setPermissions(Set.of("inventory.view"));

        assertThrows(ForbiddenException.class,
                () -> complex.saveQualityCertificate(1L, null, null));
    }

    private void setPermissions(Set<String> permissions) {
        SecurityContext.set(new SecurityContext.Principal(
                1L, 1L, "测试用户", "E001", 1L, "测试部门",
                "tester", false, List.of("TEST"), permissions
        ));
    }

    private static final class EmptyReferenceJdbcTemplate extends JdbcTemplate {
        @Override
        public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
            if (!sql.contains("from product_reference_alias")
                    && !sql.contains("from product_reference_value")) {
                throw new AssertionError("测试不允许访问其他SQL：" + sql);
            }
            return List.of();
        }
    }
}
