package com.soyia.copperv2.service;

import com.soyia.copperv2.dto.AlignedDtos;
import com.soyia.copperv2.dto.CrmDtos;
import com.soyia.copperv2.dto.InboundCreateRequest;
import com.soyia.copperv2.dto.InventoryDtos;
import com.soyia.copperv2.dto.OutboundActionRequests;
import com.soyia.copperv2.dto.OutboundCreateRequest;
import com.soyia.copperv2.dto.PurchaseCreateRequest;
import com.soyia.copperv2.dto.SimpleCreateRequests;
import com.soyia.copperv2.dto.TaxInvoiceDtos;
import com.soyia.copperv2.security.SecurityContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.transaction.AfterTransaction;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIfEnvironmentVariable(named = "COPPER_ERP04_E2E", matches = "true")
@Transactional
class Erp04TaxPriceMySqlAcceptanceTest {
    private static final BigDecimal ZERO = new BigDecimal("0.00");

    @Autowired private JdbcTemplate jdbc;
    @Autowired private CopperService copperService;
    @Autowired private CrmService crmService;
    @Autowired private InventoryService inventoryService;
    @Autowired private TaxInvoiceService taxInvoiceService;

    private String token;
    private Long originalEntityId;
    private Long partnerId;
    private Long addressId;
    private Long warehouseId;
    private Long productId;
    private LocalDate businessDate;

    @BeforeEach
    void setUp() {
        assertTrue(TransactionSynchronizationManager.isActualTransactionActive(),
                "ERP-04 端到端验收必须在可回滚事务中运行");
        token = "ERP04" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        SecurityContext.set(new SecurityContext.Principal(
                1L, 1L, "ERP-04验收", "ERP-04", 0L, "验收", token,
                false, List.of("系统管理员"), Set.of(
                "crm.view", "crm.manage", "sales.view", "sales.manage",
                "purchase.view", "purchase.manage", "purchase.return",
                "inbound.view", "inbound.manage", "outbound.view", "outbound.manage",
                "inventory.view", "inventory.cost.view", "invoice.view", "invoice.manage",
                "accounting.view", "accounting.manage")));
        businessDate = LocalDate.now();
        createIsolatedAccountingEntity();
        createBusinessMasterData();
    }

    @AfterEach
    void clearContext() {
        SecurityContext.clear();
    }

    @Test
    @DisplayName("报价至凭证保持价税快照，主档变化不改历史，退货按原单比例吸收尾差")
    void quoteOrdersFlowsReturnsInvoiceAndVoucherKeepOneTaxContract() {
        Long purchaseOrderId = copperService.createPurchaseOrder(new PurchaseCreateRequest(
                partnerId, businessDate, businessDate.plusDays(1), warehouseId, "PHONE", token + "-PO",
                decimal("10.00"), decimal("0.01"), token + " 含税采购",
                List.of(new PurchaseCreateRequest.Item(null, productId, decimal("10.000"), "kg",
                        null, null, null, "kg", "", decimal("113.0000")))));
        assertTax("purchase_order", purchaseOrderId, true, "900.01", "117.00", "1017.01", "13.0000%");
        Long originalPurchaseItemId = id("select id from purchase_order_item where purchase_order_id=?", purchaseOrderId);

        jdbc.update("update partner_supplier_profile set tax_included=0,tax_rate=6 where partner_id=?", partnerId);
        copperService.updatePurchaseOrder(purchaseOrderId, new PurchaseCreateRequest(
                partnerId, businessDate, businessDate.plusDays(1), warehouseId, "PHONE", token + "-PO",
                decimal("10.00"), decimal("0.01"), token + " 主档变化后仍保留原合同",
                List.of(new PurchaseCreateRequest.Item(originalPurchaseItemId, productId, decimal("10.000"), "kg",
                        null, null, null, "kg", "", decimal("113.0000")))));
        assertTax("purchase_order", purchaseOrderId, true, "900.01", "117.00", "1017.01", "13.0000%");
        copperService.confirmPurchaseOrder(purchaseOrderId);
        Long purchaseItemId = id("select id from purchase_order_item where purchase_order_id=?", purchaseOrderId);

        Long inboundId = copperService.createInbound(new InboundCreateRequest(
                purchaseOrderId, partnerId, warehouseId, "PURCHASE_ORDER", token,
                businessDate.plusDays(1), businessDate.plusDays(1), token + "-IN",
                decimal("10.00"), decimal("0.01"), token + " 入库继承采购快照",
                List.of(new InboundCreateRequest.Item(productId, purchaseItemId, token + "-BATCH", "",
                        decimal("10.000"), "kg", null, null, null, null, "kg", "", "",
                        decimal("113.0000"), List.of(), businessDate.plusDays(1)))));
        copperService.submitInbound(inboundId, token + " 提交入库");
        copperService.auditInbound(inboundId, true, "", token + " 审核入库");
        assertTax("inbound_order", inboundId, true, "900.01", "117.00", "1017.01", "13.0000%");
        Long inboundItemId = id("select id from inbound_item where inbound_id=?", inboundId);
        Long batchId = id("select inventory_batch_id from inbound_item where id=?", inboundItemId);

        Long quoteId = crmService.saveQuote(null, new CrmDtos.QuoteSave(
                null, partnerId, null, addressId, token + " 未税报价", businessDate,
                businessDate.plusDays(30), false, decimal("13.00"), "ERP04_QUOTE_RULE_V1",
                decimal("10.00"), decimal("0.01"), "POSTPAID", "NONE", "AMOUNT",
                ZERO, ZERO, businessDate.plusDays(2), token + " 报价快照",
                List.of(new CrmDtos.QuoteItemSave(null, productId, decimal("3.000"), "kg", null,
                        "kg", "", false, decimal("100.0000")))));
        crmService.activateQuote(quoteId);
        crmService.decideQuote(quoteId, "ACCEPTED", new CrmDtos.ReasonAction("OTHER", token + " 客户接受报价"));

        jdbc.update("update partner_customer_profile set tax_included=1,tax_rate=6 where partner_id=?", partnerId);
        Long salesOrderId = crmService.convertQuote(quoteId).salesOrderId();
        assertTax("sales_order", salesOrderId, false, "270.01", "35.10", "305.11", "13.0000%");
        assertTrue(text("select tax_rule_snapshot from sales_order where id=?", salesOrderId).contains("ERP04_QUOTE_RULE_V1"));
        // CRM conversion intentionally produces a draft; choose fulfillment coordinates before confirming.
        Long convertedItemId = id("select id from sales_order_item where sales_order_id=?", salesOrderId);
        copperService.updateSalesOrder(salesOrderId, new AlignedDtos.SalesOrderCreate(
                token + " 未税报价", partnerId, addressId, businessDate, businessDate.plusDays(2), warehouseId,
                decimal("10.00"), decimal("0.01"), "POSTPAID", "NONE", "AMOUNT", ZERO, ZERO,
                token + " 补齐履约仓库", List.of(new AlignedDtos.SalesOrderCreate.Item(
                        convertedItemId, productId, decimal("3.000"), "kg", null, null, null,
                        "kg", "", false, decimal("100.0000")))));
        assertTax("sales_order", salesOrderId, false, "270.01", "35.10", "305.11", "13.0000%");
        copperService.confirmSalesOrder(salesOrderId);
        Long salesItemId = id("select id from sales_order_item where sales_order_id=?", salesOrderId);

        Long outboundId = copperService.createOutbound(new OutboundCreateRequest(
                salesOrderId, partnerId, addressId, warehouseId, businessDate.plusDays(2), null,
                "SALES_ORDER", "NORMAL", "COMPLETE", "", false, "NONE", ZERO, true, false, "",
                token + "-OUT", decimal("10.00"), decimal("0.01"), token + " 出库继承报价转单快照",
                List.of(new OutboundCreateRequest.Item(salesItemId, batchId, decimal("3.000"), "kg",
                        null, null, null, null, "", "kg", decimal("100.0000"), List.of()))));
        assertTax("outbound_order", outboundId, false, "270.01", "35.10", "305.11", "13.0000%");
        Long outboundItemId = id("select id from outbound_item where outbound_id=?", outboundId);

        Long purchaseReturnId = inventoryService.createPurchaseReturn(new InventoryDtos.PurchaseReturnCreate(
                inboundId, businessDate.plusDays(3), token + " 采购部分退货", token,
                List.of(new InventoryDtos.PurchaseReturnItemCreate(
                        inboundItemId, batchId, decimal("1.000"), List.of()))));
        inventoryService.confirmPurchaseReturn(purchaseReturnId);
        assertTax("purchase_return_order", purchaseReturnId, true, "90.00", "11.70", "101.70", "13.0000%");

        Long salesReturnId = copperService.createSalesReturn(new OutboundActionRequests.SalesReturnCreate(
                outboundId, businessDate.plusDays(3), token + " 销售部分退货", token,
                List.of(new OutboundActionRequests.SalesReturnCreate.Item(outboundItemId, decimal("1.000")))));
        assertTax("sales_return_order", salesReturnId, false, "90.00", "11.70", "101.70", "13.0000%");

        TaxInvoiceDtos.SourceCandidate outboundSource = taxInvoiceService.sourceCandidates("OUTPUT", partnerId, "BLUE")
                .stream().filter(row -> row.sourceType().equals("OUTBOUND") && row.sourceId().equals(outboundId))
                .findFirst().orElseThrow();
        assertMoney("270.01", outboundSource.sourceAmountExcludingTax());
        assertMoney("35.10", outboundSource.sourceTaxAmount());
        assertMoney("305.11", outboundSource.availableAmount());

        Long taxRateId = id("select id from tax_rate_rule where rule_code='VAT_GOODS_13' and status='ENABLED'");
        Long invoiceId = taxInvoiceService.saveInvoice(null, new TaxInvoiceDtos.InvoiceSave(
                "OUTPUT", "BLUE", "DIGITAL_SPECIAL", "", "20260722000000000004", null,
                partnerId, "ERP-04 验收企业", "91370200ERP040001", token + " 往来单位",
                "91370200ERP040002", businessDate, businessDate, "TAX_DIGITAL_ACCOUNT", "CNY",
                token + ".pdf", "", token + " 销项发票", null,
                List.of(new TaxInvoiceDtos.InvoiceLineSave(productId, token + " 铜材", token + "-SKU", "",
                        "kg", decimal("3.000000"), decimal("90.003333"), decimal("270.01"), taxRateId,
                        "107060101", decimal("35.10"), decimal("305.11")))));
        taxInvoiceService.addRelation(invoiceId, new TaxInvoiceDtos.RelationSave(
                "OUTBOUND", outboundId, outboundSource.sourceAmountExcludingTax(), outboundSource.sourceTaxAmount(),
                outboundSource.sourceAmount(), token + " 来源冻结价税快照一致"));
        taxInvoiceService.confirmInvoice(invoiceId);

        Long voucherId = id("select accounting_voucher_id from tax_invoice where id=?", invoiceId);
        assertMoney("35.10", requiredDecimal("select debit_total from accounting_voucher where id=?", voucherId));
        assertMoney("35.10", requiredDecimal("select credit_total from accounting_voucher where id=?", voucherId));
        assertEquals(1L, count("""
                select count(*) from tax_invoice_relation
                where invoice_id=? and relation_layer='ACCOUNTING' and source_id=? and tax_amount=35.10
                """, invoiceId, voucherId));
        assertEquals(1L, count("""
                select count(*) from tax_invoice_relation
                where invoice_id=? and relation_layer='GOODS' and source_type='OUTBOUND' and source_id=?
                  and amount_excluding_tax=270.01 and tax_amount=35.10 and total_amount=305.11
                """, invoiceId, outboundId));
    }

    private void createIsolatedAccountingEntity() {
        originalEntityId = id("select id from accounting_entity where status='ENABLED' order by id limit 1");
        jdbc.update("update accounting_entity set status='DISABLED' where status='ENABLED'");
        jdbc.update("""
                insert into accounting_entity
                (entity_code,entity_name,base_currency,fiscal_year_start_month,status)
                values (?,?,'CNY',1,'ENABLED')
                """, token, token + " ERP-04隔离会计主体");
        Long entityId = id("select id from accounting_entity where entity_code=?", token);
        jdbc.update("""
                insert into accounting_period
                (entity_id,fiscal_year,period_no,period_name,start_date,end_date,status)
                values (?,?,?,?,?,?,'OPEN')
                """, entityId, businessDate.getYear(), businessDate.getMonthValue(), token + " 当前期间",
                businessDate.withDayOfMonth(1), businessDate.withDayOfMonth(businessDate.lengthOfMonth()));
        jdbc.update("""
                insert into accounting_account
                (entity_id,account_code,account_name,category,normal_direction,parent_id,account_level,leaf,
                 allow_manual,partner_auxiliary,product_auxiliary,warehouse_auxiliary,effective_from,effective_to,status)
                select ?,account_code,account_name,category,normal_direction,0,account_level,leaf,
                       allow_manual,partner_auxiliary,product_auxiliary,warehouse_auxiliary,effective_from,effective_to,status
                from accounting_account where entity_id=?
                """, entityId, originalEntityId);
    }

    private void createBusinessMasterData() {
        String businessNo = String.valueOf(500000 + Math.floorMod(token.hashCode(), 400000));
        jdbc.update("""
                insert into partner
                (business_no,type,type_mask,name,contact_person,phone,credit_code,status,memo)
                values (?,'CUSTOMER',3,?,'验收人员','00000000000',?,'ENABLED',?)
                """, businessNo, token + " 往来单位", token, "INTERNAL_REHEARSAL_ONLY " + token);
        partnerId = id("select id from partner where name=?", token + " 往来单位");
        jdbc.update("""
                insert into partner_customer_profile(partner_id,status,tax_included,invoice_type,tax_rate)
                values (?,'ENABLED',1,'DIGITAL_SPECIAL',13)
                """, partnerId);
        jdbc.update("""
                insert into partner_supplier_profile(partner_id,status,tax_included,invoice_type,tax_rate)
                values (?,'ENABLED',1,'DIGITAL_SPECIAL',13)
                """, partnerId);
        jdbc.update("""
                insert into partner_address
                (partner_id,name,address_type,detail,contact_person,phone,first_choice,status,memo)
                values (?,?,'RECEIVING',?,'验收人员','00000000000',1,'ENABLED',?)
                """, partnerId, token + " 收货地址", "青岛市 ERP-04 验收地址", token);
        addressId = id("select id from partner_address where partner_id=?", partnerId);
        jdbc.update("insert into warehouse(name,location,status) values (?,?,'ENABLED')",
                token + " 仓库", "INTERNAL_REHEARSAL_ONLY");
        warehouseId = id("select id from warehouse where name=?", token + " 仓库");
        productId = copperService.createProduct(new SimpleCreateRequests.ProductCreate(
                null, 2001L, null, token + "-SKU", token + " 紫铜大盘管",
                "", "", "", "", "{}", "", "", "kg", decimal("100.0000"), "ENABLED",
                List.of(new SimpleCreateRequests.ProductFieldValueCreate(300001L, decimal("19.050")),
                        new SimpleCreateRequests.ProductFieldValueCreate(300002L, decimal("1.000")))));
    }

    private void assertTax(String table, Long id, boolean included, String net, String tax,
                           String total, String rateSummary) {
        assertEquals(included ? 1 : 0, jdbc.queryForObject(
                "select price_includes_tax from " + table + " where id=?", Integer.class, id));
        assertMoney(net, requiredDecimal("select amount_excluding_tax from " + table + " where id=?", id));
        assertMoney(tax, requiredDecimal("select tax_amount from " + table + " where id=?", id));
        assertMoney(total, requiredDecimal("select total_amount from " + table + " where id=?", id));
        assertTrue(text("select tax_rate_summary from " + table + " where id=?", id).contains(rateSummary));
    }

    private void assertMoney(String expected, BigDecimal actual) {
        assertEquals(0, decimal(expected).setScale(2).compareTo(actual.setScale(2)),
                () -> "金额不一致，期望=" + expected + "，实际=" + actual);
    }

    private BigDecimal decimal(String value) { return new BigDecimal(value); }

    private Long id(String sql, Object... args) {
        return Objects.requireNonNull(jdbc.queryForObject(sql, Long.class, args));
    }

    private String text(String sql, Object... args) {
        return Objects.requireNonNull(jdbc.queryForObject(sql, String.class, args));
    }

    private BigDecimal requiredDecimal(String sql, Object... args) {
        return Objects.requireNonNull(jdbc.queryForObject(sql, BigDecimal.class, args));
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0L : value;
    }

    @AfterTransaction
    void rollbackRemovesFixture() {
        if (token == null) return;
        assertEquals(0L, count("select count(*) from accounting_entity where entity_code=?", token));
        assertEquals(1L, count("select count(*) from accounting_entity where id=? and status='ENABLED'", originalEntityId));
        assertEquals(0L, count("select count(*) from product where sku=?", token + "-SKU"));
        assertEquals(0L, count("select count(*) from partner where name=?", token + " 往来单位"));
        assertEquals(0L, count("select count(*) from warehouse where name=?", token + " 仓库"));
    }
}
