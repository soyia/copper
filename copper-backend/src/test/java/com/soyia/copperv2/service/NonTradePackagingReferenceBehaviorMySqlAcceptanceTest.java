package com.soyia.copperv2.service;

import com.soyia.copperv2.dto.InventoryDtos;
import com.soyia.copperv2.security.SecurityContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.transaction.AfterTransaction;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIfEnvironmentVariable(named = "COPPER_PACKAGING_REF_E2E", matches = "true")
@Transactional
class NonTradePackagingReferenceBehaviorMySqlAcceptanceTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired InventoryService inventory;
    @Autowired PackagingService packaging;
    String token;
    Long product, warehouse, batch, firstPackage, secondPackage;

    @BeforeEach void fixture() {
        token = "PKREF" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        SecurityContext.set(new SecurityContext.Principal(1L, 1L, "包装引用验收", "PKREF", 0L, "验收", token,
                false, List.of("系统管理员"), Set.of("inventory.view", "inventory.cost.view", "inventory.nontrade.manage")));
        jdbc.update("insert into warehouse(name,location,status) values (?,'INTERNAL_REHEARSAL_ONLY','ENABLED')", token);
        warehouse = id("select id from warehouse where name=?", token);
        jdbc.update("insert into product(product_series_id,sku,name,spec,unit,status) values (2001,?,?,'TEST','kg','ENABLED')", token, token);
        product = id("select id from product where sku=?", token);
        batch = createBatch("A", "ACTUAL_INSTANCE");
        jdbc.update("insert into stock_balance(product_id,warehouse_id,quantity_available,amount_cost) values (?,?,20,200)", product, warehouse);
        firstPackage = createPackage(batch, "A"); secondPackage = createPackage(batch, "B");
    }
    @AfterEach void clear() { SecurityContext.clear(); }

    @Test void newReferencesAndReversalsKeepAnotherDocumentOnTheSameBatchIntact() {
        long oldDocument = issue(batch, firstPackage, "1");
        long oldReference = ref(oldDocument);
        long document = issue(batch, secondPackage, "2");
        long reference = ref(document);
        assertTrue(reference > oldReference);
        assertEquals(2L, count("select count(distinct packaging_ref_id) from inventory_non_trade_item where document_id in (?,?)", oldDocument, document));
        inventory.reverseNonTrade(document, "只退回第二笔样品");
        equal("19", decimal("select quantity_available from inventory_batch where id=?", batch));
        equal("19", decimal("select quantity from inventory_package_balance where batch_id=? and unit_name='kg'", batch));
        equal("9", decimal("select remaining_base_quantity from inventory_package_instance where id=?", firstPackage));
        equal("10", decimal("select remaining_base_quantity from inventory_package_instance where id=?", secondPackage));
        assertEquals("CONFIRMED", text("select status from inventory_package_instance_usage where ref_type='INVENTORY_NON_TRADE' and ref_id=?", oldReference));
        assertEquals("REVERSED", text("select status from inventory_package_instance_usage where ref_type='INVENTORY_NON_TRADE' and ref_id=?", reference));
        assertEquals("ISSUED_PENDING_SIGNATURE", text("select status from inventory_non_trade_document where id=?", oldDocument));
    }

    @Test void reverseSelectorsNeverApplyAnotherBatchsUsageOrMovements() {
        long document = issue(batch, firstPackage, "1");
        long reference = ref(document);
        Long otherBatch = createBatch("OTHER", "ACTUAL_INSTANCE");
        Long otherPackage = createPackage(otherBatch, "OTHER");
        jdbc.update("update inventory_package_instance set remaining_base_quantity=9 where id=?", otherPackage);
        // Retained/malformed polymorphic facts must not be routed into this batch.
        jdbc.update("""
                insert into inventory_package_instance_usage
                (package_instance_id,batch_id,product_id,ref_type,ref_id,movement_type,base_quantity,base_unit,quantity_before,quantity_after)
                values (?,?,?,'INVENTORY_NON_TRADE',?,'PARTIAL_ACTUAL_OUT',1,'kg',10,9)
                """, otherPackage, otherBatch, product, reference);
        jdbc.update("""
                insert into inventory_package_movement
                (batch_id,product_id,movement_type,from_unit,from_quantity,ref_type,ref_id)
                values (?,?,'NONTRADE_BASE','kg',100,'INVENTORY_NON_TRADE',?)
                """, otherBatch, product, reference);
        inventory.reverseNonTrade(document, "按原批次恢复包装");
        equal("20", decimal("select quantity from inventory_package_balance where batch_id=? and unit_name='kg'", batch));
        equal("9", decimal("select remaining_base_quantity from inventory_package_instance where id=?", otherPackage));
        assertEquals("CONFIRMED", text("select status from inventory_package_instance_usage where package_instance_id=?", otherPackage));

        Long fixedBatch = createBatch("FIXED", "PACKAGE_BALANCE");
        Long foreignBatch = createBatch("FOREIGN", "PACKAGE_BALANCE");
        jdbc.update("insert into inventory_package_movement(batch_id,product_id,movement_type,from_unit,from_quantity,ref_type,ref_id) values (?,?,'NON_TRADE_OUT','kg',2,'INVENTORY_NON_TRADE',?)", fixedBatch, product, reference + 100);
        jdbc.update("insert into inventory_package_movement(batch_id,product_id,movement_type,from_unit,from_quantity,ref_type,ref_id) values (?,?,'NON_TRADE_OUT','kg',7,'INVENTORY_NON_TRADE',?)", foreignBatch, product, reference + 100);
        packaging.restoreNonTradePackaging(fixedBatch, product, List.of(), reference + 100);
        equal("22", decimal("select quantity from inventory_package_balance where batch_id=? and unit_name='kg'", fixedBatch));
        equal("20", decimal("select quantity from inventory_package_balance where batch_id=? and unit_name='kg'", foreignBatch));
    }

    private Long createBatch(String suffix, String tracking) {
        String snapshot = """
                {"versionNo":0,"source":{"sourceLevel":"SYSTEM_DEFAULT"},
                 "policy":{"baseUnit":"kg","minimumSalesUnit":"kg","minimumOutboundUnit":"kg","defaultPurchaseUnit":"kg","defaultSalesUnit":"kg","roundingPolicy":"REJECT"},
                 "rules":[{"parentUnit":"盘","childUnit":"kg","conversionMode":"ACTUAL_WEIGHT","breakable":false,"outboundAllowed":true,"integerOnly":true,"status":"ENABLED"}]}
                """;
        jdbc.update("""
                insert into inventory_batch(product_id,warehouse_id,batch_no,source_type,source_id,base_unit,
                packaging_profile_snapshot_json,packaging_tracking_mode,quantity_in,quantity_available,unit_cost,amount_cost,received_at,status)
                values (?,?,?,'OPENING',0,'kg',?,?,20,20,10,200,?,'AVAILABLE')
                """, product, warehouse, token + suffix, snapshot, tracking, LocalDate.of(2026,7,22));
        Long result = id("select id from inventory_batch where batch_no=?", token + suffix);
        jdbc.update("insert into inventory_package_balance(batch_id,product_id,unit_name,quantity) values (?,?,'kg',20),(?,?,'盘',2)", result, product, result, product);
        return result;
    }
    private Long createPackage(Long sourceBatch, String suffix) {
        jdbc.update("insert into inventory_package_instance(batch_id,product_id,package_code,unit_name,actual_base_quantity,remaining_base_quantity,base_unit,status) values (?,?,?,'盘',10,10,'kg','AVAILABLE')", sourceBatch, product, token + suffix);
        return id("select id from inventory_package_instance where package_code=?", token + suffix);
    }
    private long issue(Long sourceBatch, Long instance, String amount) {
        BigDecimal quantity = new BigDecimal(amount);
        long document = inventory.createNonTradeDraft(new InventoryDtos.NonTradeCreate(sourceBatch, "SAMPLE_OUT", LocalDate.of(2026,7,22), quantity,
                "领用人", "隔离样品", "", List.of(instance), List.of(new InventoryDtos.NonTradePackageAllocation(instance, quantity))));
        inventory.issueNonTrade(document);
        return document;
    }
    private long ref(long document) { return id("select packaging_ref_id from inventory_non_trade_item where document_id=?", document); }
    private Long id(String sql, Object... args) { return Objects.requireNonNull(jdbc.queryForObject(sql, Long.class, args)); }
    private long count(String sql, Object... args) { return id(sql, args); }
    private String text(String sql, Object... args) { return jdbc.queryForObject(sql, String.class, args); }
    private BigDecimal decimal(String sql, Object... args) { return jdbc.queryForObject(sql, BigDecimal.class, args); }
    private void equal(String expected, BigDecimal actual) { assertEquals(0, new BigDecimal(expected).compareTo(actual)); }
    @AfterTransaction void rollbackVerified() {
        assertEquals(0L, count("select count(*) from product where sku=?", token));
        assertEquals(0L, count("select count(*) from warehouse where name=?", token));
    }
}
