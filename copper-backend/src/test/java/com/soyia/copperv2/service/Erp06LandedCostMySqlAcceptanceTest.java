package com.soyia.copperv2.service;

import com.soyia.copperv2.dto.AlignedDtos;
import com.soyia.copperv2.dto.InboundCreateRequest;
import com.soyia.copperv2.dto.InventoryDtos;
import com.soyia.copperv2.dto.LandedCostDtos;
import com.soyia.copperv2.dto.OutboundCreateRequest;
import com.soyia.copperv2.dto.PurchaseCreateRequest;
import com.soyia.copperv2.dto.SimpleCreateRequests;
import com.soyia.copperv2.dto.TaxInvoiceDtos;
import com.soyia.copperv2.dto.TreasuryDtos;
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
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIfEnvironmentVariable(named = "COPPER_ERP06_E2E", matches = "true")
@Transactional
class Erp06LandedCostMySqlAcceptanceTest {
    private static final BigDecimal ZERO = new BigDecimal("0.00");

    @Autowired private JdbcTemplate jdbc;
    @Autowired private CopperService copperService;
    @Autowired private InventoryService inventoryService;
    @Autowired private TreasuryService treasuryService;
    @Autowired private LandedCostService landedCostService;
    @Autowired private AccountingService accountingService;
    @Autowired private TaxInvoiceService taxInvoiceService;

    private String token;
    private Long originalEntityId;
    private Long entityId;
    private Long partnerId;
    private Long addressId;
    private Long warehouseId;
    private Long productId;
    private Long expenseAccountId;
    private LocalDate businessDate;

    @BeforeEach
    void setUp() {
        assertTrue(TransactionSynchronizationManager.isActualTransactionActive(),
                "ERP-06 端到端验收必须在可回滚事务中运行");
        token = "ERP06" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        businessDate = LocalDate.now();
        SecurityContext.set(new SecurityContext.Principal(
                1L, 1L, "ERP-06验收", "ERP-06", 0L, "验收", token,
                false, List.of("系统管理员"), Set.of(
                "purchase.view", "purchase.manage", "purchase.return",
                "sales.view", "sales.manage", "inbound.view", "inbound.manage",
                "outbound.view", "outbound.manage", "inventory.view", "inventory.cost.view",
                "landed.cost.view", "landed.cost.manage", "landed.cost.confirm", "landed.cost.reverse",
                "treasury.view", "treasury.document.manage", "treasury.document.confirm", "treasury.reverse",
                "invoice.view", "invoice.manage", "invoice.confirm",
                "accounting.view", "accounting.manage")));
        createIsolatedAccountingEntity();
        createBusinessMasterData();
    }

    @AfterEach
    void clearContext() {
        SecurityContext.clear();
    }

    @Test
    @DisplayName("采购费用净额按当前结存资本化，退货拆分附加费并可按逆序完整冲回")
    void landedCostCapitalizesCurrentStockAndReversesInStrictOrder() {
        Long purchaseId = copperService.createPurchaseOrder(new PurchaseCreateRequest(
                partnerId, businessDate, businessDate, warehouseId, "PHONE", token + "-PO",
                ZERO, ZERO, token + " 采购十公斤铜材",
                List.of(new PurchaseCreateRequest.Item(null, productId, decimal("10.000"), "kg",
                        null, null, null, "kg", "", decimal("100.0000")))));
        copperService.confirmPurchaseOrder(purchaseId);
        Long purchaseItemId = id("select id from purchase_order_item where purchase_order_id=?", purchaseId);
        Long inboundId = copperService.createInbound(new InboundCreateRequest(
                purchaseId, partnerId, warehouseId, "PURCHASE_ORDER", token + "-PO",
                businessDate, businessDate, token + "-IN", ZERO, ZERO, token + " 采购入库",
                List.of(new InboundCreateRequest.Item(productId, purchaseItemId, token + "-BATCH", "",
                        decimal("10.000"), "kg", null, null, null, null, "kg", "", "",
                        decimal("100.0000"), List.of(), businessDate))));
        copperService.submitInbound(inboundId, token + " 提交入库");
        copperService.auditInbound(inboundId, true, "", token + " 审核入库");
        Long inboundItemId = id("select id from inbound_item where inbound_id=?", inboundId);
        Long batchId = id("select inventory_batch_id from inbound_item where id=?", inboundItemId);

        Long salesId = copperService.createSalesOrder(new AlignedDtos.SalesOrderCreate(
                token + " 已耗用四公斤", partnerId, addressId, businessDate, businessDate, warehouseId,
                ZERO, ZERO, "POSTPAID", "NONE", "AMOUNT", ZERO, ZERO, token + " 销售耗用",
                List.of(new AlignedDtos.SalesOrderCreate.Item(null, productId, decimal("4.000"), "kg",
                        null, null, null, "kg", "", false, decimal("150.0000")))));
        copperService.confirmSalesOrder(salesId);
        Long salesItemId = id("select id from sales_order_item where sales_order_id=?", salesId);
        copperService.createOutbound(new OutboundCreateRequest(
                salesId, partnerId, addressId, warehouseId, businessDate, LocalDateTime.now(),
                "SALES_ORDER", "NORMAL", "COMPLETE", "", false, "NONE", ZERO,
                true, false, "", token + "-OUT", ZERO, ZERO, token + " 销售出库",
                List.of(new OutboundCreateRequest.Item(salesItemId, batchId, decimal("4.000"), "kg",
                        null, null, null, null, "", "kg", decimal("150.0000"), List.of()))));
        assertBatch(batchId, "6.000", "100.0000", "600.00");

        Long sourceId = treasuryService.createDocument(new TreasuryDtos.DocumentCreate(
                "PURCHASE_LANDED_COST", partnerId, businessDate, businessDate,
                token + "-FEE", "采购运输装卸费", expenseAccountId, true,
                decimal("13.0000"), decimal("113.00"), "INTERNAL_REHEARSAL_ONLY", "DEDUCTIBLE"));
        treasuryService.confirmDocument(sourceId);
        Long sourceVoucherId = id("select accounting_voucher_id from treasury_document where id=?", sourceId);
        assertAccount(sourceVoucherId, "5401", "113.00", "0.00");
        assertAccount(sourceVoucherId, "2241", "0.00", "113.00");

        Long allocationId = landedCostService.create(new LandedCostDtos.AllocationCreate(
                sourceId, businessDate, "QUANTITY", token + " 按原始采购数量分摊",
                List.of(new LandedCostDtos.ItemCreate(inboundItemId, ZERO, ZERO))));
        landedCostService.confirm(allocationId);
        assertMoney("100.00", value("select fee_amount from landed_cost_allocation where id=?", allocationId));
        assertMoney("60.00", value("select capitalized_amount from landed_cost_allocation where id=?", allocationId));
        assertMoney("40.00", value("select expensed_amount from landed_cost_allocation where id=?", allocationId));
        assertBatch(batchId, "6.000", "110.0000", "660.00");
        assertMoney("660.00", value("select amount_cost from stock_balance where product_id=? and warehouse_id=?", productId, warehouseId));
        assertMoney("10.000000", value("select landed_unit_amount from inventory_batch_landed_cost_component where allocation_id=? and batch_id=? and status='ACTIVE'", allocationId, batchId));
        assertBusinessFact("LANDED_COST_ALLOCATION", allocationId, "POST", "LANDED_COST_CAPITALIZED", "INCREASE", "60.00");
        Long allocationVoucherId = generateVoucher("LANDED_COST_ALLOCATION", allocationId, "POST");
        assertAccount(allocationVoucherId, "1405", "60.00", "0.00");
        assertAccount(allocationVoucherId, "5401", "0.00", "60.00");

        BusinessException blocked = assertThrows(BusinessException.class, () -> treasuryService.reverseDocument(
                sourceId, new TreasuryDtos.Action("费用已分摊时不得冲正源单", businessDate)));
        assertTrue(blocked.getMessage().contains("先冲正费用分摊单"));

        Long returnId = inventoryService.createPurchaseReturn(new InventoryDtos.PurchaseReturnCreate(
                inboundId, businessDate, token + " 质量问题退回两公斤", token,
                List.of(new InventoryDtos.PurchaseReturnItemCreate(inboundItemId, batchId, decimal("2.000"), List.of()))));
        inventoryService.confirmPurchaseReturn(returnId);
        assertMoney("20.00", value("select landed_cost_amount from purchase_return_item where return_id=?", returnId));
        assertBatch(batchId, "4.000", "110.0000", "440.00");
        assertBusinessFact("PURCHASE_RETURN", returnId, "POST", "INVENTORY_COST", "DECREASE", "200.00");
        assertBusinessFact("PURCHASE_RETURN", returnId, "POST", "LANDED_COST_RETURN_EXPENSE", "INCREASE", "20.00");
        Long returnVoucherId = generateVoucher("PURCHASE_RETURN", returnId, "POST");
        assertAccount(returnVoucherId, "5401", "20.00", "0.00");
        assertAccount(returnVoucherId, "1405", "0.00", "220.00");

        BusinessException reverseBlocked = assertThrows(BusinessException.class, () -> landedCostService.reverse(
                allocationId, new LandedCostDtos.Action("退货未先冲正时禁止倒序冲回", businessDate)));
        assertTrue(reverseBlocked.getMessage().contains("先按时间倒序冲正采购退货"));

        inventoryService.reversePurchaseReturn(returnId, "先冲正后发生的采购退货");
        assertBatch(batchId, "6.000", "110.0000", "660.00");
        assertEquals("REVERSED", text("select status from purchase_return_order where id=?", returnId));

        landedCostService.reverse(allocationId, new LandedCostDtos.Action("费用来源录入错误，按当前结存冲回", businessDate));
        assertEquals("REVERSED", text("select status from landed_cost_allocation where id=?", allocationId));
        assertBatch(batchId, "6.000", "100.0000", "600.00");
        assertMoney("600.00", value("select amount_cost from stock_balance where product_id=? and warehouse_id=?", productId, warehouseId));
        assertBusinessFact("LANDED_COST_ALLOCATION", allocationId, "ROLLBACK", "LANDED_COST_CAPITALIZED", "DECREASE", "60.00");
        Long rollbackVoucherId = generateVoucher("LANDED_COST_ALLOCATION", allocationId, "ROLLBACK");
        assertAccount(rollbackVoucherId, "5401", "60.00", "0.00");
        assertAccount(rollbackVoucherId, "1405", "0.00", "60.00");

        treasuryService.reverseDocument(sourceId, new TreasuryDtos.Action("费用源单确认有误，完成依赖冲正后撤销", businessDate));
        assertEquals("REVERSED", text("select status from treasury_document where id=?", sourceId));

        Long includedSourceId = treasuryService.createDocument(new TreasuryDtos.DocumentCreate(
                "PURCHASE_LANDED_COST", partnerId, businessDate, businessDate,
                token + "-NON-DEDUCTIBLE", "小规模纳税人口径运输费", expenseAccountId, true,
                decimal("13.0000"), decimal("113.00"), "INTERNAL_REHEARSAL_ONLY", "INCLUDED_IN_COST"));
        treasuryService.confirmDocument(includedSourceId);
        Long taxRateId = id("select id from tax_rate_rule where rule_code='VAT_GOODS_13' and status='ENABLED'");
        String invoiceNumber = String.format("20260722%012d", Math.abs((long) token.hashCode()));
        Long includedInvoiceId = taxInvoiceService.saveInvoice(null, new TaxInvoiceDtos.InvoiceSave(
                "INPUT", "BLUE", "DIGITAL_SPECIAL", "", invoiceNumber, null,
                partnerId, token + " 运输商", token, "ERP-06 验收企业", "91370200ERP060001",
                businessDate, businessDate, "TAX_DIGITAL_ACCOUNT", "CNY", token + ".pdf", "",
                "不抵扣采购附加费进项发票", null,
                List.of(new TaxInvoiceDtos.InvoiceLineSave(null, "采购运输装卸费", "", "", "次",
                        decimal("1.000000"), decimal("100.000000"), decimal("100.00"), taxRateId,
                        "304040000", decimal("13.00"), decimal("113.00")))));
        taxInvoiceService.addRelation(includedInvoiceId, new TaxInvoiceDtos.RelationSave(
                "TREASURY_DOCUMENT", includedSourceId, decimal("100.00"), decimal("13.00"),
                decimal("113.00"), "不抵扣税额随采购附加费用计入存货成本"));
        taxInvoiceService.confirmInvoice(includedInvoiceId);
        Long includedInvoiceVoucherId = id("select accounting_voucher_id from tax_invoice where id=?", includedInvoiceId);
        assertAccount(includedInvoiceVoucherId, "22210103", "13.00", "0.00");
        assertAccount(includedInvoiceVoucherId, "5401", "0.00", "13.00");
        jdbc.update("update tax_invoice set verification_status='VERIFIED' where id=?", includedInvoiceId);
        BusinessException deductionBlocked = assertThrows(BusinessException.class,
                () -> taxInvoiceService.confirmUsage(includedInvoiceId, new TaxInvoiceDtos.UsageRequest(
                        "DEDUCTION", businessDate.toString().substring(0, 7), token + " 错误抵扣尝试", "ERP-06 验收")));
        assertTrue(deductionBlocked.getMessage().contains("不能再确认为可抵扣进项税"));
        taxInvoiceService.confirmUsage(includedInvoiceId, new TaxInvoiceDtos.UsageRequest(
                "NON_DEDUCTION", businessDate.toString().substring(0, 7), token + " 小规模纳税人口径复核", "ERP-06 验收"));
        Long usageVoucherId = id("select usage_voucher_id from tax_invoice where id=?", includedInvoiceId);
        assertAccount(usageVoucherId, "5401", "13.00", "0.00");
        assertAccount(usageVoucherId, "22210103", "0.00", "13.00");
        Long includedAllocationId = landedCostService.create(new LandedCostDtos.AllocationCreate(
                includedSourceId, businessDate, "QUANTITY", token + " 税额计入采购成本",
                List.of(new LandedCostDtos.ItemCreate(inboundItemId, ZERO, ZERO))));
        landedCostService.confirm(includedAllocationId);
        assertMoney("113.00", value("select fee_amount from landed_cost_allocation where id=?", includedAllocationId));
        assertMoney("67.80", value("select capitalized_amount from landed_cost_allocation where id=?", includedAllocationId));
        assertMoney("45.20", value("select expensed_amount from landed_cost_allocation where id=?", includedAllocationId));
        assertBatch(batchId, "6.000", "111.3000", "667.80");
        landedCostService.reverse(includedAllocationId,
                new LandedCostDtos.Action("不抵扣税额计入成本场景验收后冲正", businessDate));
        assertBatch(batchId, "6.000", "100.0000", "600.00");
        treasuryService.reverseDocument(includedSourceId,
                new TreasuryDtos.Action("不抵扣税额计入成本场景验收收口", businessDate));
    }

    private void createIsolatedAccountingEntity() {
        originalEntityId = id("select id from accounting_entity where status='ENABLED' order by id limit 1");
        jdbc.update("update accounting_entity set status='DISABLED' where status='ENABLED'");
        jdbc.update("insert into accounting_entity(entity_code,entity_name,base_currency,fiscal_year_start_month,status) values (?,?,'CNY',1,'ENABLED')",
                token, token + " ERP-06隔离会计主体");
        entityId = id("select id from accounting_entity where entity_code=?", token);
        jdbc.update("""
                insert into accounting_period(entity_id,fiscal_year,period_no,period_name,start_date,end_date,status)
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
        jdbc.update("""
                insert into accounting_rule_version
                (entity_id,rule_code,rule_name,source_type,posting_action,fact_type,fact_direction,
                 debit_account_id,credit_account_id,version_no,effective_from,effective_to,status,
                 change_reason,published_by,published_by_name,published_at)
                select ?,r.rule_code,r.rule_name,r.source_type,r.posting_action,r.fact_type,r.fact_direction,
                       nda.id,nca.id,r.version_no,r.effective_from,r.effective_to,r.status,
                       concat(r.change_reason,'；ERP-06隔离副本'),r.published_by,r.published_by_name,r.published_at
                from accounting_rule_version r
                join accounting_account oda on oda.id=r.debit_account_id
                join accounting_account oca on oca.id=r.credit_account_id
                join accounting_account nda on nda.entity_id=? and nda.account_code=oda.account_code
                join accounting_account nca on nca.entity_id=? and nca.account_code=oca.account_code
                where r.entity_id=?
                """, entityId, entityId, entityId, originalEntityId);
        expenseAccountId = id("select id from accounting_account where entity_id=? and account_code='5401'", entityId);
    }

    private void createBusinessMasterData() {
        String businessNo = String.valueOf(600000 + Math.floorMod(token.hashCode(), 299999));
        jdbc.update("""
                insert into partner(business_no,type,type_mask,name,contact_person,phone,credit_code,status,memo)
                values (?,'CUSTOMER',3,?,'验收人员','00000000000',?,'ENABLED','INTERNAL_REHEARSAL_ONLY')
                """, businessNo, token + " 往来单位", token);
        partnerId = id("select id from partner where name=?", token + " 往来单位");
        jdbc.update("insert into partner_customer_profile(partner_id,status,tax_included,invoice_type,tax_rate) values (?,'ENABLED',1,'DIGITAL_SPECIAL',0)", partnerId);
        jdbc.update("insert into partner_supplier_profile(partner_id,status,tax_included,invoice_type,tax_rate) values (?,'ENABLED',1,'DIGITAL_SPECIAL',0)", partnerId);
        jdbc.update("""
                insert into partner_address(partner_id,name,address_type,detail,contact_person,phone,first_choice,status,memo)
                values (?,?,'RECEIVING',?,'验收人员','00000000000',1,'ENABLED',?)
                """, partnerId, token + " 收货地址", "青岛市 ERP-06 验收地址", token);
        addressId = id("select id from partner_address where partner_id=? and name=?", partnerId, token + " 收货地址");
        jdbc.update("insert into warehouse(name,location,status) values (?,?,'ENABLED')", token + " 验收仓", "INTERNAL_REHEARSAL_ONLY");
        warehouseId = id("select id from warehouse where name=?", token + " 验收仓");
        productId = copperService.createProduct(new SimpleCreateRequests.ProductCreate(
                null, 2001L, null, token + "-SKU", token + " 紫铜大盘管",
                "", "", "", "", "{}", "", "", "kg", decimal("100.0000"), "ENABLED",
                List.of(new SimpleCreateRequests.ProductFieldValueCreate(300001L, decimal("19.050")),
                        new SimpleCreateRequests.ProductFieldValueCreate(300002L, decimal("1.000")))));
    }

    private Long generateVoucher(String sourceType, Long sourceId, String action) {
        Long batchId = id("""
                select id from business_posting_batch
                where source_type=? and source_id=? and posting_action=? and status='POSTED'
                order by id desc limit 1
                """, sourceType, sourceId, action);
        AccountingService.GenerationOutcome outcome = accountingService.generateForBusinessPosting(batchId);
        assertEquals(1, outcome.voucherIds().size(), sourceType + " " + action + " 应生成一张凭证");
        return outcome.voucherIds().get(0);
    }

    private void assertBatch(Long batchId, String quantity, String unitCost, String amount) {
        assertMoney(quantity, value("select quantity_available from inventory_batch where id=?", batchId));
        assertMoney(unitCost, value("select unit_cost from inventory_batch where id=?", batchId));
        assertMoney(amount, value("select amount_cost from inventory_batch where id=?", batchId));
    }

    private void assertBusinessFact(String sourceType, Long sourceId, String action,
                                    String factType, String direction, String amount) {
        assertEquals(1L, count("""
                select count(*) from business_posting_entry e join business_posting_batch b on b.id=e.posting_batch_id
                where b.source_type=? and b.source_id=? and b.posting_action=? and b.status='POSTED'
                  and e.fact_type=? and e.direction=? and e.amount=?
                """, sourceType, sourceId, action, factType, direction, decimal(amount)));
    }

    private void assertAccount(Long voucherId, String accountCode, String debit, String credit) {
        BigDecimal actualDebit = value("select coalesce(sum(debit_amount),0) from accounting_voucher_line where voucher_id=? and account_code_snapshot=?", voucherId, accountCode);
        BigDecimal actualCredit = value("select coalesce(sum(credit_amount),0) from accounting_voucher_line where voucher_id=? and account_code_snapshot=?", voucherId, accountCode);
        assertMoney(debit, actualDebit);
        assertMoney(credit, actualCredit);
    }

    private BigDecimal decimal(String value) { return new BigDecimal(value); }
    private Long id(String sql, Object... args) { return Objects.requireNonNull(jdbc.queryForObject(sql, Long.class, args)); }
    private String text(String sql, Object... args) { return Objects.requireNonNull(jdbc.queryForObject(sql, String.class, args)); }
    private BigDecimal value(String sql, Object... args) { return Objects.requireNonNull(jdbc.queryForObject(sql, BigDecimal.class, args)); }
    private long count(String sql, Object... args) { Long value = jdbc.queryForObject(sql, Long.class, args); return value == null ? 0L : value; }
    private void assertMoney(String expected, BigDecimal actual) { assertEquals(0, decimal(expected).compareTo(actual), expected + " != " + actual); }

    @AfterTransaction
    void rollbackRemovesFixture() {
        if (token == null) return;
        assertEquals(0L, count("select count(*) from accounting_entity where entity_code=?", token));
        assertEquals(1L, count("select count(*) from accounting_entity where id=? and status='ENABLED'", originalEntityId));
        assertEquals(0L, count("select count(*) from product where sku=?", token + "-SKU"));
        assertEquals(0L, count("select count(*) from partner where name=?", token + " 往来单位"));
        assertEquals(0L, count("select count(*) from warehouse where name=?", token + " 验收仓"));
    }
}
