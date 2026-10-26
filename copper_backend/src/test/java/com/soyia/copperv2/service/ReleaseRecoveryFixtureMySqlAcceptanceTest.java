package com.soyia.copperv2.service;

import com.soyia.copperv2.dto.*;
import com.soyia.copperv2.security.SecurityContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/** Deliberately committed fictional facts for an isolated whole-database backup/recovery rehearsal. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIfEnvironmentVariable(named = "COPPER_RELEASE_RECOVERY_FIXTURE", matches = "true")
class ReleaseRecoveryFixtureMySqlAcceptanceTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired CopperService copper;
    @Autowired InboundAttachmentService attachments;

    @Test void commitFictionalTradingFactsForTheExternalEncryptedRecoveryRehearsal() {
        String database = jdbc.queryForObject("select database()", String.class);
        assertNotNull(database);
        assertTrue(database.startsWith("copper_release_acceptance_recovery_"),
                "Persistent recovery fixtures require a separately owned recovery database");
        assertEquals(0L, count("select count(*) from purchase_order"), "Recovery fixture requires a blank business database");
        String fixtureMarker = "RECOVERY" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        LocalDate date = LocalDate.of(2026, 10, 16);
        SecurityContext.set(new SecurityContext.Principal(1L, 1L, "虚构恢复验收", "RECOVERY", 0L,
                "隔离演练", fixtureMarker, false, List.of("系统管理员"), Set.of("inventory.cost.view")));
        try {
            jdbc.update("""
                    insert into partner(business_no,type,type_mask,entity_type,name,status,memo)
                    values ('990001','BOTH',3,'INDIVIDUAL',?,'ENABLED','SYNTHETIC_RECOVERY_ONLY')
                    """, fixtureMarker + "虚构往来单位");
            Long partner = id("select id from partner where name=?", fixtureMarker + "虚构往来单位");
            jdbc.update("insert into partner_customer_profile(partner_id,status,tax_rate) values (?,'ENABLED',0)", partner);
            jdbc.update("insert into partner_supplier_profile(partner_id,status,tax_rate) values (?,'ENABLED',0)", partner);
            jdbc.update("insert into partner_address(partner_id,name,address_type,detail,status) values (?,'虚构仓库','RECEIVING','SYNTHETIC_RECOVERY_ONLY','ENABLED')", partner);
            Long address = id("select id from partner_address where partner_id=?", partner);
            jdbc.update("insert into warehouse(name,location,status) values (?,'SYNTHETIC_RECOVERY_ONLY','ENABLED')", fixtureMarker);
            Long warehouse = id("select id from warehouse where name=?", fixtureMarker);
            jdbc.update("insert into product(product_series_id,sku,name,spec,unit,default_price,status) values (2001,?,?,'TP2 Φ22×1.0','kg',10,'ENABLED')", fixtureMarker, fixtureMarker + "虚构铜管");
            Long product = id("select id from product where sku=?", fixtureMarker);
            BigDecimal purchased = new BigDecimal("10.123456"), sold = new BigDecimal("2.000001");
            BigDecimal cost = new BigDecimal("50.1234"), price = new BigDecimal("80.1234");
            Long purchase = copper.createPurchaseOrder(new PurchaseCreateRequest(partner, date, date, warehouse,
                    "PHONE", fixtureMarker, BigDecimal.ZERO, BigDecimal.ZERO, fixtureMarker,
                    List.of(new PurchaseCreateRequest.Item(null, product, purchased, "kg", null,
                            null, null, "kg", "", cost))));
            copper.confirmPurchaseOrder(purchase);
            Long purchaseItem = id("select id from purchase_order_item where purchase_order_id=?", purchase);
            Long inbound = copper.createInbound(new InboundCreateRequest(purchase, partner, warehouse,
                    "PURCHASE_ORDER", fixtureMarker, date, date, fixtureMarker, BigDecimal.ZERO, BigDecimal.ZERO, fixtureMarker,
                    List.of(new InboundCreateRequest.Item(product, purchaseItem, fixtureMarker, "", purchased, "kg",
                            null, null, null, null, "kg", "", "", cost, List.of(), date))));
            byte[] placeholder = Base64.getDecoder().decode(
                    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=");
            var attachment = attachments.upload(inbound, new MockMultipartFile("file",
                    "synthetic-recovery-placeholder.png", "image/png", placeholder));
            assertArrayEquals(placeholder, attachments.content(inbound, attachment.id()).bytes());
            copper.submitInbound(inbound, "虚构恢复验收");
            copper.auditInbound(inbound, true, "", "虚构恢复验收");
            Long batch = id("select inventory_batch_id from inbound_item where inbound_id=?", inbound);
            Long sales = copper.createSalesOrder(new AlignedDtos.SalesOrderCreate(fixtureMarker, partner, address,
                    date, date, warehouse, BigDecimal.ZERO, BigDecimal.ZERO, "POSTPAID", "NONE", "AMOUNT",
                    BigDecimal.ZERO, BigDecimal.ZERO, fixtureMarker,
                    List.of(new AlignedDtos.SalesOrderCreate.Item(null, product, sold, "kg", null,
                            null, null, "kg", "", false, price))));
            copper.confirmSalesOrder(sales);
            Long salesItem = id("select id from sales_order_item where sales_order_id=?", sales);
            Long outbound = copper.createOutbound(new OutboundCreateRequest(sales, partner, address, warehouse,
                    date, LocalDateTime.of(2026, 10, 16, 10, 0), "SALES_ORDER", "NORMAL", "COMPLETE", "", false,
                    "NONE", BigDecimal.ZERO, true, false, "", fixtureMarker, BigDecimal.ZERO, BigDecimal.ZERO, fixtureMarker,
                    List.of(new OutboundCreateRequest.Item(salesItem, batch, sold, "kg", null,
                            null, null, null, "", "kg", price, List.of()))));
            copper.createPayment(new PaymentCreateRequest(partner, sales, null, null,
                    new BigDecimal("100.00"), "CASH", null, date, fixtureMarker));
            copper.createSupplierPayment(new SupplierPaymentCreateRequest(partner, purchase, null,
                    new BigDecimal("200.00"), "CASH", null, date, fixtureMarker));
            BigDecimal expectedPurchase = purchased.multiply(cost).setScale(2, RoundingMode.HALF_UP);
            BigDecimal expectedSales = sold.multiply(price).setScale(2, RoundingMode.HALF_UP);
            equal(purchased.subtract(sold), decimal("select quantity_available from inventory_batch where id=?", batch));
            equal(purchased.subtract(sold), decimal("select quantity_available from stock_balance where product_id=? and warehouse_id=?", product, warehouse));
            equal(expectedPurchase.subtract(decimal("select sum(inventory_amount_cost) from outbound_item where outbound_id=?", outbound)),
                    decimal("select amount_cost from inventory_batch where id=?", batch));
            equal(expectedSales.subtract(new BigDecimal("100.00")), decimal("select receivable_amount-received_amount-adjusted_amount from sales_order where id=?", sales));
            equal(expectedPurchase.subtract(new BigDecimal("200.00")), decimal("select total_amount-paid_amount from purchase_order where id=?", purchase));
            assertEquals(0L, count("select count(*) from accounting_generation_issue where status='OPEN'"));
            long postings = count("select count(*) from business_posting_batch where partner_id=?", partner);
            long vouchers = count("select count(*) from accounting_voucher where source_type<>'MANUAL'");
            assertTrue(postings >= 7, "Committed business source postings must exist");
            assertTrue(vouchers >= 5, "Real after-commit voucher generation must complete before backup");
            System.out.println("RELEASE_RECOVERY_FIXTURE=PASS synthetic=true committed=true quantity=8.123455"
                    + " receivable=" + expectedSales.subtract(new BigDecimal("100.00"))
                    + " payable=" + expectedPurchase.subtract(new BigDecimal("200.00"))
                    + " postings=" + postings + " vouchers=" + vouchers + " attachments=1");
        } finally { SecurityContext.clear(); }
    }
    private Long id(String sql, Object... args) { return Objects.requireNonNull(jdbc.queryForObject(sql, Long.class, args)); }
    private long count(String sql, Object... args) { return Objects.requireNonNull(jdbc.queryForObject(sql, Long.class, args)); }
    private BigDecimal decimal(String sql, Object... args) { return Objects.requireNonNull(jdbc.queryForObject(sql, BigDecimal.class, args)); }
    private void equal(BigDecimal expected, BigDecimal actual) { assertEquals(0, expected.compareTo(actual)); }
}
