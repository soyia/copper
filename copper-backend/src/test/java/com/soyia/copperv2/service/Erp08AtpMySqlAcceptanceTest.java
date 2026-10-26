package com.soyia.copperv2.service;

import com.soyia.copperv2.dto.AtpDtos;
import com.soyia.copperv2.security.SecurityContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIfEnvironmentVariable(named = "COPPER_ERP08_E2E", matches = "true")
class Erp08AtpMySqlAcceptanceTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AtpService atpService;
    @Autowired private PlatformTransactionManager transactionManager;

    private String token;
    private Long partnerId;
    private Long productId;
    private Long warehouseId;
    private Long sourceWarehouseId;
    private Long batchId;
    private LocalDate today;

    @BeforeEach
    void setUp() {
        token = "ERP08" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        today = LocalDate.now();
        SecurityContext.set(owner());
        createMasterData();
    }

    @AfterEach
    void cleanupAndVerifyZeroResidual() {
        SecurityContext.clear();
        cleanup();
        assertEquals(0L, count("select count(*) from sales_order where order_no like ?", token + "%"));
        assertEquals(0L, count("select count(*) from product where sku=?", token + "-SKU"));
        assertEquals(0L, count("select count(*) from warehouse where name like ?", token + "%"));
    }

    @Test
    @DisplayName("现存、草稿占用、销售承诺、采购与调拨在途按日期计算且关联草稿不重复扣减")
    void timePhasedAtpPreventsLaterPromiseBreakAndKeepsReleaseAudit() {
        Long existingOrderId = createSalesOrder("-EXISTING", "OPEN", today.plusDays(4), decimal("6"));
        Long existingItemId = id("select id from sales_order_item where sales_order_id=?", existingOrderId);
        createLinkedOutboundDraft(existingOrderId, existingItemId, decimal("2"));
        createPurchaseIncoming(today.plusDays(2), decimal("6"));
        createTransferIncoming(today.plusDays(4), decimal("3"));

        Long candidateId = createSalesOrder("-CANDIDATE", "DRAFT", today.plusDays(3), decimal("8"));
        AtpDtos.OrderEvaluation blocked = atpService.previewSalesOrder(candidateId);
        assertFalse(blocked.canPromise());
        assertDecimal("8", blocked.items().get(0).atpBeforeQuantity());
        assertDecimal("0", blocked.items().get(0).atpAfterQuantity());
        assertEquals(today.plusDays(4), blocked.items().get(0).earliestShortageDate());
        assertDecimal("1", blocked.items().get(0).shortageQuantity());

        jdbc.update("update sales_order_item set quantity=7,requested_quantity=7,pricing_quantity=7 where sales_order_id=?",
                candidateId);
        transaction().executeWithoutResult(status -> {
            atpService.enforceSalesOrder(candidateId, "CONFIRM", 1);
            jdbc.update("update sales_order set status='OPEN' where id=?", candidateId);
        });

        AtpDtos.Summary summary = only(atpService.summaries(productId, warehouseId, today.plusDays(4)));
        assertDecimal("4", summary.onHandQuantity());
        assertDecimal("2", summary.outboundDraftQuantity());
        assertDecimal("13", summary.salesCommitmentQuantity());
        assertDecimal("2", summary.linkedDraftCoveredQuantity());
        assertDecimal("6", summary.purchaseIncomingQuantity());
        assertDecimal("3", summary.transferIncomingQuantity());
        assertDecimal("0", summary.atpQuantity());
        assertEquals("AVAILABLE", summary.status());

        transaction().executeWithoutResult(status -> {
            atpService.recordSalesOrderRelease(candidateId, 2);
            jdbc.update("update sales_order set status='CANCELLED' where id=?", candidateId);
        });
        List<AtpDtos.Snapshot> history = atpService.salesOrderHistory(candidateId);
        assertEquals(List.of("CANCEL", "CONFIRM"), history.stream().map(AtpDtos.Snapshot::action).toList());
        assertEquals(List.of("RELEASED", "PROMISED"), history.stream().map(AtpDtos.Snapshot::decision).toList());
        assertDecimal("7", only(atpService.summaries(productId, warehouseId, today.plusDays(4))).atpQuantity());
    }

    @Test
    @DisplayName("同一商品仓库并发确认只能成功一单，数据库锁防止重复承诺")
    void concurrentSalesCannotPromiseTheSameStockTwice() throws Exception {
        Long firstId = createSalesOrder("-CONCURRENT-A", "DRAFT", today.plusDays(1), decimal("3"));
        Long secondId = createSalesOrder("-CONCURRENT-B", "DRAFT", today.plusDays(1), decimal("3"));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<Future<String>> futures = new ArrayList<>();
        try {
            for (Long orderId : List.of(firstId, secondId)) {
                futures.add(executor.submit(() -> {
                    SecurityContext.set(owner());
                    ready.countDown();
                    start.await(10, TimeUnit.SECONDS);
                    try {
                        transaction().executeWithoutResult(status -> {
                            assertEquals("READ-COMMITTED", jdbc.queryForObject(
                                    "select @@transaction_isolation", String.class));
                            atpService.enforceSalesOrder(orderId, "CONFIRM", 1);
                            jdbc.update("update sales_order set status='OPEN' where id=?", orderId);
                        });
                        return "PROMISED";
                    } catch (BusinessException exception) {
                        return exception.getMessage();
                    } finally {
                        SecurityContext.clear();
                    }
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            List<String> results = List.of(futures.get(0).get(20, TimeUnit.SECONDS),
                    futures.get(1).get(20, TimeUnit.SECONDS));
            assertEquals(1L, results.stream().filter("PROMISED"::equals).count());
            assertEquals(1L, results.stream().filter(value -> value.contains("ATP可承诺量不足")).count());
            assertEquals(1L, count("select count(*) from sales_order where id in (?,?) and status='OPEN'", firstId, secondId));
            assertEquals(1L, count("select count(*) from inventory_atp_snapshot where sales_order_id in (?,?) and decision='PROMISED'",
                    firstId, secondId));
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    private void createMasterData() {
        String businessNo = String.valueOf(800000 + Math.floorMod(token.hashCode(), 199999));
        jdbc.update("insert into partner(business_no,type,type_mask,name,status,memo) values (?,'BOTH',3,?,'ENABLED','INTERNAL_REHEARSAL_ONLY')",
                businessNo, token + " 往来单位");
        partnerId = id("select id from partner where name=?", token + " 往来单位");
        jdbc.update("insert into partner_customer_profile(partner_id,status,settlement_method,settlement_days,credit_limit) values (?,'ENABLED','CREDIT_TERM',30,0)", partnerId);
        jdbc.update("insert into partner_supplier_profile(partner_id,status,settlement_method,settlement_days) values (?,'ENABLED','CREDIT_TERM',30)", partnerId);
        jdbc.update("insert into product(sku,name,unit,status) values (?,?,'kg','ENABLED')", token + "-SKU", token + " 紫铜管");
        productId = id("select id from product where sku=?", token + "-SKU");
        jdbc.update("insert into warehouse(name,location,status) values (?,?,'ENABLED')", token + " 目标仓", "INTERNAL_REHEARSAL_ONLY");
        warehouseId = id("select id from warehouse where name=?", token + " 目标仓");
        jdbc.update("insert into warehouse(name,location,status) values (?,?,'ENABLED')", token + " 来源仓", "INTERNAL_REHEARSAL_ONLY");
        sourceWarehouseId = id("select id from warehouse where name=?", token + " 来源仓");
        jdbc.update("insert into stock_balance(product_id,warehouse_id,quantity_available,amount_cost) values (?,?,4,400)", productId, warehouseId);
        jdbc.update("""
                insert into inventory_batch
                (product_id,warehouse_id,batch_no,source_type,source_id,base_unit,quantity_in,quantity_available,
                 unit_cost,amount_cost,status,received_at)
                values (?,?,?,'OPENING',0,'kg',4,4,100,400,'AVAILABLE',current_date)
                """, productId, warehouseId, token + "-BATCH");
        batchId = id("select id from inventory_batch where batch_no=?", token + "-BATCH");
    }

    private Long createSalesOrder(String suffix, String status, LocalDate promiseDate, BigDecimal quantity) {
        String no = token + suffix;
        jdbc.update("""
                insert into sales_order
                (order_no,title,customer_id,business_date,delivery_plan_date,fulfillment_warehouse_id,
                 settlement_days_snapshot,due_date,amount_excluding_tax,total_amount,payment_term,
                 shipment_payment_policy,status,current_revision_no,memo)
                values (?,?,?,current_date,?,?,30,current_date+interval 30 day,?,?,
                        'POSTPAID','NONE',?,1,'INTERNAL_REHEARSAL_ONLY')
                """, no, no, partnerId, promiseDate, warehouseId, quantity.multiply(decimal("150")),
                quantity.multiply(decimal("150")), status);
        Long orderId = id("select id from sales_order where order_no=?", no);
        jdbc.update("""
                insert into sales_order_item
                (sales_order_id,product_id,product_sku_snapshot,product_name_snapshot,requested_quantity,
                 requested_unit,quantity,base_unit,price_unit,pricing_quantity,packaging_plan_json,no_charge,
                 unit_price,amount,amount_excluding_tax,settlement_total_amount,status)
                values (?,?,?,?,?,'kg',?,'kg','kg',?,'{}',0,150,?,?,?,'ENABLED')
                """, orderId, productId, token + "-SKU", token + " 紫铜管", quantity, quantity, quantity,
                quantity.multiply(decimal("150")), quantity.multiply(decimal("150")), quantity.multiply(decimal("150")));
        return orderId;
    }

    private void createLinkedOutboundDraft(Long salesOrderId, Long salesOrderItemId, BigDecimal quantity) {
        jdbc.update("""
                insert into outbound_order
                (order_no,sales_order_id,source_type,source_channel,entry_mode,completeness_status,settlement_status,
                 customer_id,warehouse_id,business_date,settlement_days_snapshot,due_date,status,total_quantity,memo)
                values (?,?, 'SALES_ORDER','PHONE','NORMAL','COMPLETE','READY',?,?,current_date,30,
                        current_date+interval 30 day,'DRAFT',?,'INTERNAL_REHEARSAL_ONLY')
                """, token + "-OUT", salesOrderId, partnerId, warehouseId, quantity);
        Long outboundId = id("select id from outbound_order where order_no=?", token + "-OUT");
        jdbc.update("""
                insert into outbound_item
                (outbound_id,sales_order_item_id,product_id,product_sku_snapshot,product_name_snapshot,batch_id,
                 quantity,base_unit,packaging_plan_json,price_unit,pricing_quantity,unit_price,amount)
                values (?,?,?,?,?,?,?,'kg','{}','kg',?,150,?)
                """, outboundId, salesOrderItemId, productId, token + "-SKU", token + " 紫铜管", batchId,
                quantity, quantity, quantity.multiply(decimal("150")));
        Long outboundItemId = id("select id from outbound_item where outbound_id=?", outboundId);
        jdbc.update("""
                insert into outbound_allocation
                (outbound_id,outbound_item_id,sales_order_item_id,product_id,inventory_batch_id,quantity,status)
                values (?,?,?,?,?,?,'DRAFT')
                """, outboundId, outboundItemId, salesOrderItemId, productId, batchId, quantity);
    }

    private void createPurchaseIncoming(LocalDate expectedDate, BigDecimal quantity) {
        jdbc.update("""
                insert into purchase_order
                (order_no,supplier_id,business_date,expected_date,expected_warehouse_id,settlement_days_snapshot,
                 due_date,total_quantity,total_amount,status,memo)
                values (?,?,current_date,?,?,30,current_date+interval 30 day,?,?,'ORDERED','INTERNAL_REHEARSAL_ONLY')
                """, token + "-PO", partnerId, expectedDate, warehouseId, quantity, quantity.multiply(decimal("100")));
        Long purchaseId = id("select id from purchase_order where order_no=?", token + "-PO");
        jdbc.update("""
                insert into purchase_order_item
                (purchase_order_id,product_id,product_sku_snapshot,product_name_snapshot,requested_quantity,
                 requested_unit,quantity,base_unit,price_unit,pricing_quantity,packaging_plan_json,unit_price,amount,status)
                values (?,?,?,?,?,'kg',?,'kg','kg',?,'{}',100,?,'ENABLED')
                """, purchaseId, productId, token + "-SKU", token + " 紫铜管", quantity, quantity, quantity,
                quantity.multiply(decimal("100")));
    }

    private void createTransferIncoming(LocalDate expectedDate, BigDecimal quantity) {
        jdbc.update("""
                insert into inventory_transfer_order
                (order_no,from_warehouse_id,to_warehouse_id,business_date,expected_arrival_date,status,
                 total_quantity,total_cost,memo,dispatched_at)
                values (?,?,?,current_date,?,'IN_TRANSIT',?,?, 'INTERNAL_REHEARSAL_ONLY',now())
                """, token + "-TR", sourceWarehouseId, warehouseId, expectedDate, quantity,
                quantity.multiply(decimal("100")));
        Long transferId = id("select id from inventory_transfer_order where order_no=?", token + "-TR");
        jdbc.update("""
                insert into inventory_transfer_item
                (transfer_id,product_id,source_batch_id,quantity,base_unit,unit_cost,amount_cost,packaging_plan_json)
                values (?,?,?,?, 'kg',100,?,'{}')
                """, transferId, productId, batchId, quantity, quantity.multiply(decimal("100")));
    }

    private void cleanup() {
        jdbc.update("delete from inventory_atp_snapshot where sales_order_no like ?", token + "%");
        jdbc.update("delete from inventory_atp_lock where product_id=?", productId == null ? 0L : productId);
        jdbc.update("delete a from outbound_allocation a join outbound_order o on o.id=a.outbound_id where o.order_no like ?", token + "%");
        jdbc.update("delete i from outbound_item i join outbound_order o on o.id=i.outbound_id where o.order_no like ?", token + "%");
        jdbc.update("delete from outbound_order where order_no like ?", token + "%");
        jdbc.update("delete i from inventory_transfer_item i join inventory_transfer_order o on o.id=i.transfer_id where o.order_no like ?", token + "%");
        jdbc.update("delete from inventory_transfer_order where order_no like ?", token + "%");
        jdbc.update("delete i from purchase_order_item i join purchase_order o on o.id=i.purchase_order_id where o.order_no like ?", token + "%");
        jdbc.update("delete from purchase_order where order_no like ?", token + "%");
        jdbc.update("delete i from sales_order_item i join sales_order o on o.id=i.sales_order_id where o.order_no like ?", token + "%");
        jdbc.update("delete from sales_order where order_no like ?", token + "%");
        jdbc.update("delete from stock_balance where product_id=?", productId == null ? 0L : productId);
        jdbc.update("delete from inventory_batch where product_id=?", productId == null ? 0L : productId);
        jdbc.update("delete from partner_customer_profile where partner_id=?", partnerId == null ? 0L : partnerId);
        jdbc.update("delete from partner_supplier_profile where partner_id=?", partnerId == null ? 0L : partnerId);
        jdbc.update("delete from partner where id=?", partnerId == null ? 0L : partnerId);
        jdbc.update("delete from product where id=?", productId == null ? 0L : productId);
        jdbc.update("delete from warehouse where name like ?", token + "%");
    }

    private SecurityContext.Principal owner() {
        return new SecurityContext.Principal(1L, 1L, "ERP-08验收", "ERP-08", 0L, "验收", token,
                false, List.of("系统管理员"), Set.of("sales.view", "sales.manage", "sales.confirm",
                "inventory.view", "purchase.view", "outbound.view"));
    }

    private TransactionTemplate transaction() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return transaction;
    }
    private BigDecimal decimal(String value) { return new BigDecimal(value); }
    private Long id(String sql, Object... args) { return Objects.requireNonNull(jdbc.queryForObject(sql, Long.class, args)); }
    private long count(String sql, Object... args) { Long value = jdbc.queryForObject(sql, Long.class, args); return value == null ? 0 : value; }
    private void assertDecimal(String expected, BigDecimal actual) { assertEquals(0, decimal(expected).compareTo(actual)); }
    private <T> T only(List<T> rows) { assertEquals(1, rows.size()); return rows.get(0); }
}
