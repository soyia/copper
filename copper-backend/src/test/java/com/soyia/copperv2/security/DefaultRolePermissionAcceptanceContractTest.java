package com.soyia.copperv2.security;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultRolePermissionAcceptanceContractTest {
    @Test
    void highRiskEndpointsUseIndependentPermissions() throws Exception {
        SecurityInterceptor interceptor = new SecurityInterceptor(null, null, null, null, null);
        Method required = SecurityInterceptor.class.getDeclaredMethod("requiredPermission", String.class, String.class);
        required.setAccessible(true);

        assertEquals("invoice.confirm", required.invoke(interceptor, "/tax-invoices/1/confirm", "POST"));
        assertEquals("invoice.void", required.invoke(interceptor, "/tax-invoices/1/void", "POST"));
        assertEquals("financial.bank.manage", required.invoke(interceptor, "/financial/bank-reconciliations", "POST"));
        assertEquals("financial.bank.account.manage", required.invoke(interceptor, "/financial/bank-accounts", "POST"));
        assertEquals("financial.close.manage", required.invoke(interceptor, "/financial/period-close/1/close", "POST"));
        assertEquals("financial.close.reopen", required.invoke(interceptor, "/financial/period-close/1/reopen", "POST"));
        assertEquals("financial.close.manage", required.invoke(interceptor, "/accounting/periods/1/close", "POST"));
        assertEquals("financial.close.reopen", required.invoke(interceptor, "/accounting/periods/1/reopen", "POST"));
        assertEquals("product.master.complex.view",
                required.invoke(interceptor, "/product-reference-values/resolve", "POST"));
        assertEquals("product.master.complex.view",
                required.invoke(interceptor, "/product-series/3001/identity-schema/preflight", "POST"));
        assertEquals("product.master.complex.view",
                required.invoke(interceptor, "/products/duplicate-check", "POST"));
        assertEquals("product.view",
                required.invoke(interceptor, "/product-reference-values", "GET"));
        assertEquals("product.view",
                required.invoke(interceptor, "/product-series/3001/allowed-units", "GET"));
        assertEquals("product.view", required.invoke(interceptor, "/product-standards", "GET"));
        assertEquals("product.view", required.invoke(interceptor, "/product-tempers", "GET"));
        assertEquals("product.master.complex.view", required.invoke(
                interceptor, "/product-source-links/30/review-history", "GET"));
        assertEquals("inventory.quality.view", required.invoke(
                interceptor,
                "/inventory/batches/81/quality-certificates/82/review-history", "GET"));
        assertEquals("product.cost.view", required.invoke(
                interceptor, "/products/8/supplier-offers/10/revisions", "GET"));
        assertEquals("product.master.complex.view", required.invoke(
                interceptor, "/products/8/bom/revisions", "GET"));
    }

    @Test
    void governedManagePermissionsExplicitlyIncludeTheirOwnReadAccess() throws Exception {
        SecurityInterceptor interceptor = new SecurityInterceptor(null, null, null, null, null);
        Method allowed = SecurityInterceptor.class.getDeclaredMethod(
                "hasRequiredPermission", SecurityContext.Principal.class, String.class);
        allowed.setAccessible(true);

        SecurityContext.Principal principal = new SecurityContext.Principal(
                1L, 1L, "测试用户", "E001", 1L, "测试部门", "tester", false,
                List.of("TEST"), Set.of(
                    "product.manage",
                    "product.master.complex.manage",
                    "inventory.quality.manage",
                    "product.supplier.offer.manage"));
        assertTrue((Boolean) allowed.invoke(
                interceptor, principal, "product.view"));
        assertTrue((Boolean) allowed.invoke(
                interceptor, principal, "product.master.complex.view"));
        assertTrue((Boolean) allowed.invoke(
                interceptor, principal, "inventory.quality.view"));
        assertTrue((Boolean) allowed.invoke(
                interceptor, principal, "product.cost.view"));
    }

    @Test
    void migrationGrantsDailyDutiesWithoutGrantingFinanceReopen() throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/V29__default_role_permission_acceptance.sql"));
        for (String permission : new String[]{"invoice.void", "financial.bank.account.manage", "financial.close.reopen"}) {
            assertTrue(sql.contains("'" + permission + "'"), permission);
        }
        assertTrue(sql.contains("r.code IN ('ADMIN','OWNER')"));
        assertTrue(sql.contains("r.code='FINANCE'"));
        assertTrue(sql.contains("r.code IN ('FINANCE','MANAGER','WAREHOUSE','SALES','PURCHASE')"));
        assertTrue(sql.contains("r.code IN ('FINANCE','WAREHOUSE')"));
    }

    @Test
    void liveAcceptancePolicyCoversEverySmallBusinessDefaultRoleAndRisk() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/service/OrganizationSecurityService.java"));
        for (String role : new String[]{"OWNER", "FINANCE", "WAREHOUSE", "SALES", "PURCHASE"}) {
            assertTrue(source.contains("policy(\"" + role + "\""), role);
        }
        for (String risk : new String[]{"inventory.cost.view", "accounting.manage", "invoice.void",
                "financial.bank.account.manage", "financial.close.reopen"}) {
            assertTrue(source.contains("\"" + risk + "\""), risk);
        }
        assertTrue(source.contains("defaultRoleAcceptance()"));
    }
}
