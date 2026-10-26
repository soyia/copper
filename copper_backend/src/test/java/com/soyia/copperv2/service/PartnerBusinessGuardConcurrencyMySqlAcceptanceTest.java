package com.soyia.copperv2.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.soyia.copperv2.dto.SimpleCreateRequests;
import com.soyia.copperv2.security.SecurityContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import com.soyia.copperv2.dto.PurchaseCreateRequest;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIfEnvironmentVariable(named = "COPPER_PARTNER_SAFETY_E2E", matches = "true")
class PartnerBusinessGuardConcurrencyMySqlAcceptanceTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired CopperService copper;
    @Autowired ObjectMapper mapper;
    @Autowired SalesRiskService salesRisk;
    @Autowired PlatformTransactionManager transactions;

    @Test void postingWaitsForDisableCommitThenUsesTheCurrentStatus() throws Exception {
        String token = "PLOCK" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String no = String.format("%06d", 100000 + Math.floorMod(token.hashCode(), 899999));
        jdbc.update("insert into partner(business_no,type,type_mask,entity_type,name,status) values (?,'CUSTOMER',1,'INDIVIDUAL',?,'ENABLED')", no, token);
        Long partner = jdbc.queryForObject("select id from partner where name=?", Long.class, token);
        jdbc.update("insert into partner_customer_profile(partner_id,status) values (?,'ENABLED')", partner);
        CountDownLatch disabledUncommitted = new CountDownLatch(1);
        CountDownLatch mayCommit = new CountDownLatch(1);
        CountDownLatch postingStarted = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> disabling = executor.submit(() -> {
                SecurityContext.set(owner(token));
                try {
                    new TransactionTemplate(transactions).executeWithoutResult(status -> {
                        copper.updatePartner(partner, mapper.convertValue(Map.of(
                                "typeMask", 1, "entityType", "INDIVIDUAL", "name", token,
                                "creditLimit", BigDecimal.ZERO, "status", "DISABLED",
                                "disabledReason", "并发停用验收", "customerStatus", "ENABLED"),
                                SimpleCreateRequests.PartnerCreate.class));
                        disabledUncommitted.countDown();
                        try {
                            if (!mayCommit.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("commit signal missing");
                        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                    });
                } finally { SecurityContext.clear(); }
            });
            assertTrue(disabledUncommitted.await(10, TimeUnit.SECONDS));
            Future<String> posting = executor.submit(() -> {
                postingStarted.countDown();
                try {
                    new TransactionTemplate(transactions).executeWithoutResult(status ->
                            PartnerBusinessGuard.requireEnabledRole(jdbc, partner, "CUSTOMER"));
                    return "ALLOWED";
                } catch (BusinessException blocked) { return blocked.getMessage(); }
            });
            assertTrue(postingStarted.await(10, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> posting.get(200, TimeUnit.MILLISECONDS),
                    "New posting must wait for the partner lock held by the disable transaction");
            mayCommit.countDown();
            disabling.get(10, TimeUnit.SECONDS);
            assertTrue(posting.get(10, TimeUnit.SECONDS).contains("往来单位已停用"));
        } finally {
            mayCommit.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
            jdbc.update("delete from partner_customer_profile where partner_id=?", partner);
            jdbc.update("delete from partner_contact where partner_id=?", partner);
            jdbc.update("delete from partner_address where partner_id=?", partner);
            jdbc.update("delete from partner where id=?", partner);
            assertEquals(0L, jdbc.queryForObject("select count(*) from partner where name=?", Long.class, token));
        }
    }

    @Test void editingAndConfirmationDoNotInvertPartnerAndOrderLocks() throws Exception {
        String token = "PEDIT" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String no = String.format("%06d", 100000 + Math.floorMod(token.hashCode(), 899999));
        jdbc.update("insert into partner(business_no,type,type_mask,entity_type,name,status) values (?,'SUPPLIER',2,'INDIVIDUAL',?,'ENABLED')", no, token);
        Long partner = jdbc.queryForObject("select id from partner where name=?", Long.class, token);
        jdbc.update("insert into partner_supplier_profile(partner_id,status) values (?,'ENABLED')", partner);
        jdbc.update("insert into product(product_series_id,sku,name,spec,unit,default_price,status) values (2001,?,?,'TP2 Φ22×1.0','kg',10,'ENABLED')", token, token);
        Long product = jdbc.queryForObject("select id from product where sku=?", Long.class, token);
        jdbc.update("insert into warehouse(name,location,status) values (?,'INTERNAL_REHEARSAL_ONLY','ENABLED')", token);
        Long warehouse = jdbc.queryForObject("select id from warehouse where name=?", Long.class, token);
        PurchaseCreateRequest request = new PurchaseCreateRequest(partner, LocalDate.now(), LocalDate.now(),
                warehouse, "PHONE", token, BigDecimal.ZERO, BigDecimal.ZERO, token,
                List.of(new PurchaseCreateRequest.Item(null, product, BigDecimal.ONE, "kg", null,
                        null, null, "kg", "", new BigDecimal("10"))));
        SecurityContext.set(owner(token));
        Long order;
        try { order = copper.createPurchaseOrder(request); }
        finally { SecurityContext.clear(); }
        CountDownLatch documentLocked = new CountDownLatch(1);
        CountDownLatch partnerShared = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Long> confirming = executor.submit(() -> {
                SecurityContext.set(owner(token));
                try {
                    return new TransactionTemplate(transactions).execute(status -> {
                        jdbc.queryForObject("select id from purchase_order where id=? for update", Long.class, order);
                        documentLocked.countDown();
                        await(partnerShared);
                        Long result = copper.confirmPurchaseOrder(order);
                        status.setRollbackOnly();
                        return result;
                    });
                } finally { SecurityContext.clear(); }
            });
            assertTrue(documentLocked.await(10, TimeUnit.SECONDS));
            Future<Long> editing = executor.submit(() -> {
                SecurityContext.set(owner(token));
                try {
                    return new TransactionTemplate(transactions).execute(status -> {
                        PartnerBusinessGuard.requireEnabledRole(jdbc, partner, "SUPPLIER");
                        partnerShared.countDown();
                        Long result = copper.updatePurchaseOrder(order, request);
                        status.setRollbackOnly();
                        return result;
                    });
                } finally { SecurityContext.clear(); }
            });
            assertEquals(order, confirming.get(10, TimeUnit.SECONDS));
            assertEquals(order, editing.get(10, TimeUnit.SECONDS));
            assertEquals("DRAFT", jdbc.queryForObject("select status from purchase_order where id=?", String.class, order));
        } finally {
            partnerShared.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
            jdbc.update("delete from business_order_revision where order_type='PURCHASE_ORDER' and order_id=?", order);
            jdbc.update("delete from security_business_record_owner where resource_type='PURCHASE_ORDER' and record_id=?", order);
            jdbc.update("delete from purchase_order_item where purchase_order_id=?", order);
            jdbc.update("delete from purchase_order where id=?", order);
            jdbc.update("delete from product where id=?", product);
            jdbc.update("delete from warehouse where id=?", warehouse);
            jdbc.update("delete from partner_supplier_profile where partner_id=?", partner);
            jdbc.update("delete from partner where id=?", partner);
        }
    }

    @Test void concurrentCreditChecksNeverUpgradeSharedPartnerOrProfileLocks() throws Exception {
        String token = "PRISK" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String no = String.format("%06d", 100000 + Math.floorMod(token.hashCode(), 899999));
        jdbc.update("insert into partner(business_no,type,type_mask,entity_type,name,status) values (?,'CUSTOMER',1,'INDIVIDUAL',?,'ENABLED')", no, token);
        Long partner = jdbc.queryForObject("select id from partner where name=?", Long.class, token);
        jdbc.update("insert into partner_customer_profile(partner_id,status) values (?,'ENABLED')", partner);
        CountDownLatch bothShared = new CountDownLatch(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            java.util.ArrayList<Future<String>> checks = new java.util.ArrayList<>();
            for (int i = 0; i < 2; i++) checks.add(executor.submit(() -> {
                SecurityContext.set(owner(token));
                try {
                    return new TransactionTemplate(transactions).execute(status -> {
                        PartnerBusinessGuard.requireEnabledRole(jdbc, partner, "CUSTOMER");
                        bothShared.countDown();
                        await(bothShared);
                        salesRisk.lockCustomerScope(partner);
                        status.setRollbackOnly();
                        return "CHECKED";
                    });
                } finally { SecurityContext.clear(); }
            }));
            for (Future<String> check : checks) assertEquals("CHECKED", check.get(10, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
            jdbc.update("delete from sales_risk_policy where partner_id=?", partner);
            jdbc.update("delete from partner_customer_profile where partner_id=?", partner);
            jdbc.update("delete from partner where id=?", partner);
        }
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Concurrent test timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private SecurityContext.Principal owner(String token) {
        return new SecurityContext.Principal(1L, 1L, "并发验收", "PLOCK", 0L, "验收", token,
                false, List.of("系统管理员"), Set.of());
    }
}
