package com.soyia.copperv2.service;

import com.soyia.copperv2.dto.AccountingDtos;
import com.soyia.copperv2.dto.AlignedDtos;
import com.soyia.copperv2.dto.EvidenceDtos;
import com.soyia.copperv2.dto.FinancialCloseDtos;
import com.soyia.copperv2.dto.InboundCreateRequest;
import com.soyia.copperv2.dto.InventoryDtos;
import com.soyia.copperv2.dto.OutboundActionRequests;
import com.soyia.copperv2.dto.OutboundCreateRequest;
import com.soyia.copperv2.dto.PaymentAllocationDtos;
import com.soyia.copperv2.dto.PaymentCreateRequest;
import com.soyia.copperv2.dto.PurchaseCreateRequest;
import com.soyia.copperv2.dto.ReceivableSettlementDtos;
import com.soyia.copperv2.dto.RiskExceptionDtos;
import com.soyia.copperv2.dto.SalesOrderPrepaymentRequests;
import com.soyia.copperv2.dto.SimpleCreateRequests;
import com.soyia.copperv2.dto.SupplierPaymentCreateRequest;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.transaction.AfterTransaction;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIfEnvironmentVariable(named = "COPPER_ERP_MONTH_END_E2E", matches = "true")
@Transactional
class ErpMonthEndMySqlAcceptanceTest {
    private static final LocalDate PERIOD_START = LocalDate.of(2026, 1, 1);
    private static final LocalDate PERIOD_END = LocalDate.of(2026, 1, 31);
    private static final BigDecimal ZERO = new BigDecimal("0.00");

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private CopperService copperService;

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private AccountingService accountingService;

    @Autowired
    private TaxInvoiceService taxInvoiceService;

    @Autowired
    private EvidenceAttachmentService evidenceAttachmentService;

    @Autowired
    private FinancialCloseService financialCloseService;

    private String token;
    private Long entityId;
    private Long periodId;
    private Long bankAccountId;
    private Long partnerId;
    private Long addressId;
    private Long sourceWarehouseId;
    private Long targetWarehouseId;
    private Long productId;

    @BeforeEach
    void createIsolatedMonthEndFixture() {
        assertTrue(TransactionSynchronizationManager.isActualTransactionActive(),
                "月结端到端验收必须在可回滚事务中运行");
        token = "E2E" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
        SecurityContext.set(new SecurityContext.Principal(
                1L, 1L, "月结端到端验收", "ERP-E2E", 0L, "验收", "erp-month-end-e2e",
                false, List.of("系统管理员"), Set.of(
                "inventory.cost.view", "inventory.view", "inventory.transfer", "inventory.stocktake",
                "inventory.cost.adjust", "purchase.view", "purchase.manage", "purchase.return",
                "sales.view", "sales.manage", "inbound.view", "inbound.manage",
                "outbound.view", "outbound.manage", "finance.view", "finance.manage",
                "invoice.view", "invoice.manage", "accounting.view", "accounting.manage",
                "accounting.review", "accounting.post", "accounting.period.manage",
                "financial.report.view", "financial.bank.view", "financial.bank.manage",
                "financial.close.manage", "risk.exception.approve")));

        createIsolatedAccountingEntity();
        createBusinessMasterData();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContext.clear();
    }

    @Test
    @DisplayName("真实采购销售库存资金税票银行与期末结账形成可复算闭环")
    void completeRealTransactionFlowClosesThePeriod() {
        LocalDate purchaseDate = LocalDate.of(2026, 1, 2);
        LocalDate inboundDate = LocalDate.of(2026, 1, 3);
        LocalDate salesDate = LocalDate.of(2026, 1, 5);
        LocalDate outboundDate = LocalDate.of(2026, 1, 6);

        Long purchaseId = copperService.createPurchaseOrder(new PurchaseCreateRequest(
                partnerId, purchaseDate, inboundDate, sourceWarehouseId, "PHONE", token,
                ZERO, ZERO, token + " 真实月结采购",
                List.of(new PurchaseCreateRequest.Item(
                        null, productId, decimal("100.000"), "kg", null,
                        null, null, "kg", "", decimal("11.3000")))));
        copperService.confirmPurchaseOrder(purchaseId);
        Long purchaseItemId = requiredLong(
                "select id from purchase_order_item where purchase_order_id=? and product_id=?",
                purchaseId, productId);

        Long inboundId = copperService.createInbound(new InboundCreateRequest(
                purchaseId, partnerId, sourceWarehouseId, "PURCHASE_ORDER", token,
                inboundDate, inboundDate, token + "-INBOUND",
                ZERO, ZERO, token + " 真实月结入库",
                List.of(new InboundCreateRequest.Item(
                        productId, purchaseItemId, token + "-BATCH", "",
                        decimal("100.000"), "kg", null, null, null,
                        null, "kg", "", "", decimal("11.3000"), List.of(), inboundDate))));
        copperService.submitInbound(inboundId, "真实月结验收");
        copperService.auditInbound(inboundId, true, "", "真实月结验收");
        Long inboundItemId = requiredLong(
                "select id from inbound_item where inbound_id=? and product_id=?", inboundId, productId);
        Long sourceBatchId = requiredLong("select inventory_batch_id from inbound_item where id=?", inboundItemId);

        assertMoney("1130.00", "select total_amount from purchase_order where id=?", purchaseId);
        assertMoney("1130.00", "select total_amount from inbound_order where id=?", inboundId);
        assertQuantity("100.000", "select quantity_available from inventory_batch where id=?", sourceBatchId);
        assertMoney("1130.00", "select amount_cost from inventory_batch where id=?", sourceBatchId);

        Long salesOrderId = copperService.createSalesOrder(new AlignedDtos.SalesOrderCreate(
                token + " 月结销售", partnerId, addressId, salesDate, outboundDate, sourceWarehouseId,
                ZERO, ZERO, "POSTPAID", "NONE", "AMOUNT",
                ZERO, ZERO, token + " 真实月结销售",
                List.of(new AlignedDtos.SalesOrderCreate.Item(
                        null, productId, decimal("60.000"), "kg", null,
                        null, null, "kg", "", false, decimal("22.6000")))));
        copperService.confirmSalesOrder(salesOrderId);
        Long salesItemId = requiredLong(
                "select id from sales_order_item where sales_order_id=? and product_id=?", salesOrderId, productId);

        Long outboundId = copperService.createOutbound(new OutboundCreateRequest(
                salesOrderId, partnerId, addressId, sourceWarehouseId, outboundDate,
                LocalDateTime.of(2026, 1, 6, 10, 0), "SALES_ORDER", "NORMAL", "COMPLETE", "",
                false, "NONE", ZERO, true, false, "", token + "-OUTBOUND", ZERO, ZERO,
                token + " 真实月结出库",
                List.of(new OutboundCreateRequest.Item(
                        salesItemId, sourceBatchId, decimal("60.000"), "kg", null,
                        null, null, null, "", "kg", decimal("22.6000"), List.of()))));
        Long outboundItemId = requiredLong(
                "select id from outbound_item where outbound_id=? and product_id=?", outboundId, productId);
        assertEquals("SHIPPED", requiredString("select status from outbound_order where id=?", outboundId));
        assertMoney("1356.00", "select total_amount from outbound_order where id=?", outboundId);

        Long salesReturnId = copperService.createSalesReturn(new OutboundActionRequests.SalesReturnCreate(
                outboundId, LocalDate.of(2026, 1, 7), "客户退回10kg",
                token + " 真实月结销售退货",
                List.of(new OutboundActionRequests.SalesReturnCreate.Item(outboundItemId, decimal("10.000")))));
        Long purchaseReturnId = inventoryService.createPurchaseReturn(new InventoryDtos.PurchaseReturnCreate(
                inboundId, LocalDate.of(2026, 1, 8), "向供应商退回5kg",
                token + " 真实月结采购退货",
                List.of(new InventoryDtos.PurchaseReturnItemCreate(
                        inboundItemId, sourceBatchId, decimal("5.000"), List.of()))));
        inventoryService.confirmPurchaseReturn(purchaseReturnId);

        assertMoney("226.00", "select total_amount from sales_return_order where id=?", salesReturnId);
        assertMoney("56.50", "select total_amount from purchase_return_order where id=?", purchaseReturnId);
        assertQuantity("45.000", "select quantity_available from inventory_batch where id=?", sourceBatchId);

        Long transferId = inventoryService.createTransfer(new InventoryDtos.TransferCreate(
                sourceWarehouseId, targetWarehouseId, LocalDate.of(2026, 1, 9),
                LocalDate.of(2026, 1, 10),
                token + " 真实月结调拨",
                List.of(new InventoryDtos.TransferItemCreate(sourceBatchId, decimal("20.000"), List.of()))));
        inventoryService.dispatchTransfer(transferId);
        assertEquals("IN_TRANSIT", requiredString(
                "select status from inventory_transfer_order where id=?", transferId));
        assertMoney("226.00", "select total_cost from inventory_transfer_order where id=?", transferId);
        inventoryService.receiveTransfer(transferId);
        Long targetBatchId = requiredLong(
                "select target_batch_id from inventory_transfer_item where transfer_id=?", transferId);

        Long stocktakeId = inventoryService.createStocktake(new InventoryDtos.StocktakeCreate(
                targetWarehouseId, LocalDate.of(2026, 1, 10), token + " 真实月结盘点",
                List.of(new InventoryDtos.StocktakeItemCreate(
                        targetBatchId, decimal("19.000"), "实盘短少1kg"))));
        inventoryService.confirmStocktake(stocktakeId, new RiskExceptionDtos.InlineAuthorization(
                token + " 负责人确认本次真实盘亏原因与数量，批准按盘点事实调整库存", null));
        assertEquals("CONSUMED", requiredString("""
                select status from risk_exception_request
                where source_type='INVENTORY_STOCKTAKE' and source_id=? and action_code='CONFIRM'
                """, stocktakeId));

        Long costAdjustmentId = inventoryService.createCostAdjustment(new InventoryDtos.CostAdjustmentCreate(
                LocalDate.of(2026, 1, 11), "采购运费归入剩余库存成本",
                token + " 真实月结成本调整",
                List.of(new InventoryDtos.CostAdjustmentItemCreate(sourceBatchId, decimal("12.0000")))));
        inventoryService.confirmCostAdjustment(costAdjustmentId);

        assertQuantity("25.000", "select quantity_available from inventory_batch where id=?", sourceBatchId);
        assertMoney("300.00", "select amount_cost from inventory_batch where id=?", sourceBatchId);
        assertQuantity("19.000", "select quantity_available from inventory_batch where id=?", targetBatchId);
        assertMoney("214.70", "select amount_cost from inventory_batch where id=?", targetBatchId);
        assertQuantity("44.000", "select sum(quantity_available) from stock_balance where product_id=?", productId);
        assertMoney("514.70", "select sum(amount_cost) from stock_balance where product_id=?", productId);

        Long supplierPaymentId = copperService.createSupplierPayment(new SupplierPaymentCreateRequest(
                partnerId, null, inboundId, decimal("1073.50"), "BANK", bankAccountId,
                LocalDate.of(2026, 1, 12), token + " 采购净额付款"));
        Long customerPaymentId = copperService.createPayment(new PaymentCreateRequest(
                partnerId, null, outboundId, null, decimal("800.00"), "BANK", bankAccountId,
                LocalDate.of(2026, 1, 13), token + " 销售部分银行收款"));
        assertEquals("INBOUND", requiredString(
                "select target_type from supplier_payment_allocation where supplier_payment_id=?",
                supplierPaymentId));
        assertEquals(inboundId, requiredLong(
                "select target_id from supplier_payment_allocation where supplier_payment_id=?",
                supplierPaymentId));
        assertMoney("1073.50", """
                select allocated_amount from supplier_payment_allocation
                where supplier_payment_id=? and status='ACTIVE' and capture_source='CREATION'
                """, supplierPaymentId);
        assertEquals("OUTBOUND", requiredString(
                "select target_type from payment_allocation where payment_id=?", customerPaymentId));
        assertEquals(outboundId, requiredLong(
                "select target_id from payment_allocation where payment_id=?", customerPaymentId));
        assertMoney("800.00", """
                select allocated_amount from payment_allocation
                where payment_id=? and status='ACTIVE' and capture_source='CREATION'
                """, customerPaymentId);

        Long acceptanceBillId = copperService.createAcceptanceBill(new ReceivableSettlementDtos.AcceptanceCreate(
                salesOrderId, token + "-ACCEPTANCE", "BANK_ACCEPTANCE", decimal("330.00"),
                LocalDate.of(2026, 1, 14), LocalDate.of(2026, 1, 18), LocalDate.of(2026, 1, 14),
                token + " 客户", "验收承兑银行", token + " 承兑收票"));
        Long acceptancePaymentId = requiredLong(
                "select settlement_payment_id from acceptance_bill where id=?", acceptanceBillId);
        assertEquals("SALES_ORDER", requiredString(
                "select target_type from payment_allocation where payment_id=?", acceptancePaymentId));
        assertEquals(salesOrderId, requiredLong(
                "select target_id from payment_allocation where payment_id=?", acceptancePaymentId));
        copperService.matureAcceptanceBill(acceptanceBillId, new ReceivableSettlementDtos.AcceptanceAction(
                LocalDate.of(2026, 1, 18), null, null, null, bankAccountId, token + " 承兑到期收款"));
        Long acceptanceEventId = requiredLong(
                "select id from acceptance_bill_event where bill_id=? and event_type='MATURITY'", acceptanceBillId);

        Long advancePaymentId = copperService.createPayment(new PaymentCreateRequest(
                partnerId, null, null, null, decimal("200.00"), "BANK", bankAccountId,
                LocalDate.of(2026, 1, 20), token + " 客户无单预收"));
        assertEquals(0L, count("select count(*) from payment_allocation where payment_id=?", advancePaymentId),
                "无单预收保持为未分配资金，不得伪造核销分配行");
        Long advanceRefundId = copperService.refundCustomerAdvance(new SalesOrderPrepaymentRequests.Refund(
                partnerId, decimal("50.00"), "BANK", bankAccountId,
                LocalDate.of(2026, 1, 21), token + " 退回部分预收"));

        assertMoney("0.00", """
                select coalesce(sum(case direction when 'DEBIT' then amount else -amount end),0)
                from receivable_movement where customer_id=?
                """, partnerId);
        assertMoney("0.00", """
                select coalesce(sum(case direction when 'INCREASE' then amount else -amount end),0)
                from payable_movement where supplier_id=?
                """, partnerId);
        assertMoney("150.00", """
                select balance from partner_account where partner_id=? and account_type='CUSTOMER_ADVANCE'
                """, partnerId);

        Long inputInvoiceId = createInputInvoice(inboundId, supplierPaymentId);
        Long outputInvoiceId = createOutputInvoice(outboundId, customerPaymentId, acceptancePaymentId);
        assertEquals("COMPLETE", taxInvoiceService.invoice(inputInvoiceId).invoice().reconciliationStatus());
        assertEquals("COMPLETE", taxInvoiceService.invoice(outputInvoiceId).invoice().reconciliationStatus());
        assertMoney("1073.50", """
                select coalesce(sum(total_amount),0) from tax_invoice_relation
                where invoice_id=? and relation_layer='FUNDS' and status='ACTIVE'
                """, inputInvoiceId);
        assertMoney("1130.00", """
                select coalesce(sum(total_amount),0) from tax_invoice_relation
                where invoice_id=? and relation_layer='FUNDS' and status='ACTIVE'
                """, outputInvoiceId);
        assertTrue(taxInvoiceService.closeExceptions(PERIOD_START, PERIOD_END).isEmpty(),
                "完整票货款账勾稽不应形成税票关账异常");

        List<Long> postingBatchIds = jdbc.queryForList("""
                select id from business_posting_batch
                where business_date between ? and ?
                  and status='POSTED'
                  and posting_action not in ('COMMITMENT_POST','COMMITMENT_REVERSE')
                order by id
                """, Long.class, PERIOD_START, PERIOD_END);
        assertEquals(15, postingBatchIds.size(), "本月15个实际业务过账批次必须进入会计生成");
        for (Long postingBatchId : postingBatchIds) {
            AccountingService.GenerationOutcome outcome =
                    accountingService.generateForBusinessPosting(postingBatchId);
            assertFalse(outcome.voucherIds().isEmpty(), "业务过账必须产生真实会计凭证：" + postingBatchId);
        }
        postAllDraftVouchers();

        assertEquals(15L, count("""
                select count(*) from accounting_generation_result
                where entity_id=? and outcome='VOUCHER_CREATED'
                """, entityId));
        assertEquals(0L, count("""
                select count(*) from accounting_generation_issue
                where entity_id=? and status='OPEN'
                """, entityId));
        assertEquals(0L, count("""
                select count(*) from accounting_voucher
                where entity_id=? and voucher_date between ? and ? and status<>'POSTED'
                """, entityId, PERIOD_START, PERIOD_END));
        assertMoney("0.00", """
                select coalesce(sum(debit_total-credit_total),0) from accounting_voucher
                where entity_id=? and voucher_date between ? and ?
                """, entityId, PERIOD_START, PERIOD_END);
        assertMoney("0.00", """
                select coalesce(sum(debit_amount-credit_amount),0) from accounting_ledger_entry
                where entity_id=? and voucher_date between ? and ?
                """, entityId, PERIOD_START, PERIOD_END);

        Long reconciliationId = reconcileBank(
                supplierPaymentId, customerPaymentId, acceptanceEventId, advancePaymentId, advanceRefundId);
        FinancialCloseDtos.ReconciliationDetail reconciliation =
                financialCloseService.reconciliation(reconciliationId);
        assertEquals("CONFIRMED", reconciliation.session().status());
        assertMoneyEquals("206.50", reconciliation.session().statementClosingBalance());
        assertMoneyEquals("206.50", reconciliation.session().ledgerClosingBalance());
        assertMoneyEquals("0.00", reconciliation.session().difference());
        assertTrue(reconciliation.fundCandidates().isEmpty(), "系统银行收付必须全部逐笔匹配");

        FinancialCloseDtos.PeriodClosePreview beforeProfit = financialCloseService.closePreview(periodId);
        assertEquals("BLOCKED", check(beforeProfit, "PROFIT_CARRY_FORWARD").status(),
                "有损益发生时必须先形成已记账损益结转凭证");
        assertTrue(beforeProfit.checks().stream()
                        .filter(item -> !"PROFIT_CARRY_FORWARD".equals(item.checkCode()))
                        .allMatch(item -> "PASS".equals(item.status())),
                () -> "损益结转前不应存在其他月结阻断：" + beforeProfit.checks());

        Long profitVoucherId = financialCloseService.generateProfitVoucher(periodId);
        assertNotEquals(0L, profitVoucherId);
        accountingService.reviewVoucher(profitVoucherId, "月结损益结转审核");
        accountingService.postVoucher(profitVoucherId);

        FinancialCloseDtos.PeriodClosePreview ready = financialCloseService.closePreview(periodId);
        assertTrue(ready.closable(), () -> "完整真实业务流必须可以关账：" + ready.checks());
        assertTrue(ready.checks().stream().allMatch(item -> "PASS".equals(item.status())),
                () -> "全部月结检查必须通过：" + ready.checks());
        assertMoneyEquals("0.00", ready.trialDifference());
        assertMoneyEquals(ready.balanceSheetAssets().toPlainString(), ready.balanceSheetLiabilitiesEquity());

        FinancialCloseDtos.CloseResult closeResult =
                financialCloseService.closePeriod(periodId, token + " 真实业务月结验收");
        assertEquals("CLOSED", closeResult.status());
        assertFalse(closeResult.snapshotHash().isBlank());
        assertEquals("CLOSED", requiredString("select status from accounting_period where id=?", periodId));
        assertEquals(0L, count("""
                select count(*) from accounting_period_close_check
                where close_run_id=? and status<>'PASS'
                """, closeResult.closeRunId()));
        assertTrue(count("select count(*) from accounting_period_close_check where close_run_id=?",
                closeResult.closeRunId()) >= 15, "正式关账必须留存完整检查快照");
    }

    @Test
    @DisplayName("普通收付款建立独立分配行，冲正保留核销审计链，无单资金保持未分配")
    void ordinaryPaymentsKeepAllocationAndReversalAuditTrail() {
        LocalDate purchaseDate = LocalDate.of(2026, 1, 2);
        Long purchaseId = copperService.createPurchaseOrder(new PurchaseCreateRequest(
                partnerId, purchaseDate, purchaseDate.plusDays(1), sourceWarehouseId, "PHONE", token,
                ZERO, ZERO, token + " 分配行采购验收",
                List.of(new PurchaseCreateRequest.Item(
                        null, productId, decimal("10.000"), "kg", null,
                        null, null, "kg", "", decimal("10.0000")))));
        copperService.confirmPurchaseOrder(purchaseId);

        Long supplierPaymentId = copperService.createSupplierPayment(new SupplierPaymentCreateRequest(
                partnerId, purchaseId, null, decimal("40.00"), "BANK", bankAccountId,
                purchaseDate.plusDays(2), token + " 分配行付款验收"));
        assertEquals("PURCHASE_ORDER", requiredString(
                "select target_type from supplier_payment_allocation where supplier_payment_id=?",
                supplierPaymentId));
        assertEquals(purchaseId, requiredLong(
                "select target_id from supplier_payment_allocation where supplier_payment_id=?",
                supplierPaymentId));
        assertMoney("40.00", "select allocated_amount from supplier_payment_allocation where supplier_payment_id=?",
                supplierPaymentId);

        String supplierReversalReason = token + " 供应商付款冲正验收";
        copperService.reverseSupplierPayment(supplierPaymentId,
                new OutboundActionRequests.Reason(supplierReversalReason, purchaseDate.plusDays(3)));
        assertEquals("REVERSED", requiredString(
                "select status from supplier_payment_allocation where supplier_payment_id=?", supplierPaymentId));
        assertEquals(supplierReversalReason, requiredString(
                "select reversal_reason from supplier_payment_allocation where supplier_payment_id=?",
                supplierPaymentId));
        assertEquals(1L, count("""
                select count(*) from supplier_payment_allocation
                where supplier_payment_id=? and reversed_at is not null
                """, supplierPaymentId));
        assertEquals("REVERSED", requiredString(
                "select verification_status from supplier_payment where id=?", supplierPaymentId));
        assertMoney("0.00", "select paid_amount from purchase_order where id=?", purchaseId);
        assertEquals("UNPAID", requiredString("select payment_status from purchase_order where id=?", purchaseId));

        Long purchaseItemId = requiredLong(
                "select id from purchase_order_item where purchase_order_id=? and product_id=?",
                purchaseId, productId);
        LocalDate inboundDate = LocalDate.of(2026, 1, 6);
        Long inboundId = copperService.createInbound(new InboundCreateRequest(
                purchaseId, partnerId, sourceWarehouseId, "PURCHASE_ORDER", token,
                inboundDate, inboundDate, token + "-ALLOCATION-INBOUND",
                ZERO, ZERO, token + " 分配行入库验收",
                List.of(new InboundCreateRequest.Item(
                        productId, purchaseItemId, token + "-ALLOCATION-BATCH", "",
                        decimal("10.000"), "kg", null, null, null,
                        null, "kg", "", "", decimal("10.0000"), List.of(), inboundDate))));
        copperService.submitInbound(inboundId, "分配行真实验收");
        copperService.auditInbound(inboundId, true, "", "分配行真实验收");
        Long inventoryBatchId = requiredLong(
                "select inventory_batch_id from inbound_item where inbound_id=? and product_id=?",
                inboundId, productId);

        LocalDate salesDate = LocalDate.of(2026, 1, 7);
        Long salesOrderId = copperService.createSalesOrder(new AlignedDtos.SalesOrderCreate(
                token + " 分配行销售验收", partnerId, addressId, salesDate, salesDate.plusDays(1), sourceWarehouseId,
                ZERO, ZERO, "POSTPAID", "NONE", "AMOUNT",
                ZERO, ZERO, token + " 分配行销售验收",
                List.of(new AlignedDtos.SalesOrderCreate.Item(
                        null, productId, decimal("5.000"), "kg", null,
                        null, null, "kg", "", false, decimal("20.0000")))));
        copperService.confirmSalesOrder(salesOrderId);
        Long salesItemId = requiredLong(
                "select id from sales_order_item where sales_order_id=? and product_id=?",
                salesOrderId, productId);
        Long outboundId = copperService.createOutbound(new OutboundCreateRequest(
                salesOrderId, partnerId, addressId, sourceWarehouseId, salesDate.plusDays(1),
                LocalDateTime.of(2026, 1, 8, 10, 0), "SALES_ORDER", "NORMAL", "COMPLETE", "",
                false, "NONE", ZERO, true, false, "", token + "-ALLOCATION-OUTBOUND", ZERO, ZERO,
                token + " 分配行出库验收",
                List.of(new OutboundCreateRequest.Item(
                        salesItemId, inventoryBatchId, decimal("5.000"), "kg", null,
                        null, null, null, "", "kg", decimal("20.0000"), List.of()))));

        Long customerPaymentId = copperService.createPayment(new PaymentCreateRequest(
                partnerId, null, outboundId, null, decimal("80.00"), "BANK", bankAccountId,
                salesDate.plusDays(2), token + " 分配行收款验收"));
        assertEquals("OUTBOUND", requiredString(
                "select target_type from payment_allocation where payment_id=?", customerPaymentId));
        assertEquals(outboundId, requiredLong(
                "select target_id from payment_allocation where payment_id=?", customerPaymentId));
        assertMoney("80.00", "select allocated_amount from payment_allocation where payment_id=?",
                customerPaymentId);

        String customerReversalReason = token + " 客户收款冲正验收";
        copperService.reversePayment(customerPaymentId,
                new OutboundActionRequests.Reason(customerReversalReason, salesDate.plusDays(3)));
        assertEquals("REVERSED", requiredString(
                "select status from payment_allocation where payment_id=?", customerPaymentId));
        assertEquals(customerReversalReason, requiredString(
                "select reversal_reason from payment_allocation where payment_id=?", customerPaymentId));
        assertEquals(1L, count("""
                select count(*) from payment_allocation
                where payment_id=? and reversed_at is not null
                """, customerPaymentId));
        assertEquals("REVERSED", requiredString(
                "select verification_status from payment where id=?", customerPaymentId));
        assertMoney("0.00", "select received_amount from sales_order where id=?", salesOrderId);
        assertEquals("UNPAID", requiredString("select payment_status from sales_order where id=?", salesOrderId));
        assertMoney("0.00", "select paid_amount from outbound_order where id=?", outboundId);
        assertEquals("UNPAID", requiredString("select payment_status from outbound_order where id=?", outboundId));

        Long customerAdvanceId = copperService.createPayment(new PaymentCreateRequest(
                partnerId, null, null, null, decimal("25.00"), "BANK", bankAccountId,
                salesDate.plusDays(4), token + " 无单预收验收"));
        Long supplierPrepaymentId = copperService.createSupplierPayment(new SupplierPaymentCreateRequest(
                partnerId, null, null, decimal("35.00"), "BANK", bankAccountId,
                salesDate.plusDays(4), token + " 无单预付验收"));
        assertEquals("UNAPPLIED", requiredString(
                "select verification_status from payment where id=?", customerAdvanceId));
        assertEquals("UNAPPLIED", requiredString(
                "select verification_status from supplier_payment where id=?", supplierPrepaymentId));
        assertEquals(0L, count("select count(*) from payment_allocation where payment_id=?", customerAdvanceId));
        assertEquals(0L, count("""
                select count(*) from supplier_payment_allocation where supplier_payment_id=?
                """, supplierPrepaymentId));

        Long customerAdvanceApplicationId = copperService.createPayment(new PaymentCreateRequest(
                partnerId, null, outboundId, null, decimal("25.00"), "ADVANCE", null,
                salesDate.plusDays(5), token + " 预收款核销验收"));
        Long customerAdvanceAllocationId = requiredLong(
                "select id from payment_allocation where payment_id=? and status='ACTIVE'",
                customerAdvanceApplicationId);
        assertThrows(BusinessException.class, () -> copperService.changePaymentAllocations(
                customerAdvanceApplicationId, new PaymentAllocationDtos.AllocationChange(
                        token + " 预收款不能释放为悬空余额", salesDate.plusDays(6),
                        List.of(customerAdvanceAllocationId), List.of())));
        copperService.changePaymentAllocations(customerAdvanceApplicationId,
                new PaymentAllocationDtos.AllocationChange(token + " 预收款更换核销目标留痕",
                        salesDate.plusDays(6), List.of(customerAdvanceAllocationId), List.of(
                        new PaymentAllocationDtos.AllocationInput("OUTBOUND", outboundId, decimal("25.00")))));
        assertMoney("25.00", "select allocated_amount from payment where id=?", customerAdvanceApplicationId);
        assertMoney("0.00", "select unallocated_amount from payment where id=?", customerAdvanceApplicationId);
        assertEquals(1L, count("select count(*) from payment_allocation where payment_id=? and status='ACTIVE'",
                customerAdvanceApplicationId));

        Long supplierPrepaymentApplicationId = copperService.createSupplierPayment(new SupplierPaymentCreateRequest(
                partnerId, purchaseId, null, decimal("35.00"), "ADVANCE", null,
                salesDate.plusDays(5), token + " 预付款核销验收"));
        Long supplierPrepaymentAllocationId = requiredLong(
                "select id from supplier_payment_allocation where supplier_payment_id=? and status='ACTIVE'",
                supplierPrepaymentApplicationId);
        assertThrows(BusinessException.class, () -> copperService.changeSupplierPaymentAllocations(
                supplierPrepaymentApplicationId, new PaymentAllocationDtos.AllocationChange(
                        token + " 预付款不能释放为悬空余额", salesDate.plusDays(6),
                        List.of(supplierPrepaymentAllocationId), List.of())));
        assertMoney("35.00", "select allocated_amount from supplier_payment where id=?",
                supplierPrepaymentApplicationId);
        assertMoney("0.00", "select unallocated_amount from supplier_payment where id=?",
                supplierPrepaymentApplicationId);
    }

    @Test
    @DisplayName("一笔跨多单、多笔核同单、未分配后补、释放重分配及整笔冲正形成完整资金核销闭环")
    void completeMultiDocumentAllocationLifecycle() {
        LocalDate date = LocalDate.of(2026, 1, 12);
        Long stockPurchaseId = copperService.createPurchaseOrder(new PurchaseCreateRequest(
                partnerId, date.minusDays(3), date.minusDays(2), sourceWarehouseId, "PHONE", token + "-STOCK-PO",
                ZERO, ZERO, token + " 多单收款库存准备",
                List.of(new PurchaseCreateRequest.Item(null, productId, decimal("30.000"), "kg",
                        null, null, null, "kg", "", decimal("5.0000")))));
        copperService.confirmPurchaseOrder(stockPurchaseId);
        Long stockPurchaseItemId = requiredLong(
                "select id from purchase_order_item where purchase_order_id=?", stockPurchaseId);
        Long stockInboundId = copperService.createInbound(new InboundCreateRequest(
                stockPurchaseId, partnerId, sourceWarehouseId, "PURCHASE_ORDER", token,
                date.minusDays(2), date.minusDays(2), token + "-MULTI-IN", ZERO, ZERO,
                token + " 多单收款库存准备", List.of(new InboundCreateRequest.Item(
                productId, stockPurchaseItemId, token + "-MULTI-BATCH", "", decimal("30.000"), "kg",
                null, null, null, null, "kg", "", "", decimal("5.0000"), List.of(), date.minusDays(2)))));
        copperService.submitInbound(stockInboundId, "多单收款库存准备");
        copperService.auditInbound(stockInboundId, true, "", "多单收款库存准备");
        Long stockBatchId = requiredLong("select inventory_batch_id from inbound_item where inbound_id=?", stockInboundId);
        List<Long> salesIds = new java.util.ArrayList<>();
        List<Long> outboundIds = new java.util.ArrayList<>();
        for (int index = 1; index <= 3; index++) {
            Long id = copperService.createSalesOrder(new AlignedDtos.SalesOrderCreate(
                    token + " 多单销售" + index, partnerId, addressId, date, date.plusDays(1), sourceWarehouseId, ZERO, ZERO,
                    "POSTPAID", "NONE", "AMOUNT", ZERO, ZERO, token + " 多单收款验收",
                    List.of(new AlignedDtos.SalesOrderCreate.Item(null, productId, decimal("10.000"), "kg",
                            null, null, null, "kg", "", false, decimal("10.0000")))));
            copperService.confirmSalesOrder(id);
            salesIds.add(id);
            Long salesItemId = requiredLong("select id from sales_order_item where sales_order_id=?", id);
            Long outboundId = copperService.createOutbound(new OutboundCreateRequest(
                    id, partnerId, addressId, sourceWarehouseId, date, date.atTime(10 + index, 0),
                    "SALES_ORDER", "NORMAL", "COMPLETE", "", false, "NONE", ZERO, true, false, "",
                    token + "-MULTI-OUT-" + index, ZERO, ZERO, token + " 多单收款出库",
                    List.of(new OutboundCreateRequest.Item(salesItemId, stockBatchId, decimal("10.000"), "kg",
                            null, null, null, null, "", "kg", decimal("10.0000"), List.of()))));
            outboundIds.add(outboundId);
        }

        Long paymentId = copperService.createPayment(new PaymentCreateRequest(
                partnerId, null, null, null, decimal("180.00"), "BANK", bankAccountId, date,
                token + " 一笔收款跨三单", List.of(
                new PaymentAllocationDtos.AllocationInput("OUTBOUND", outboundIds.get(0), decimal("50.00")),
                new PaymentAllocationDtos.AllocationInput("OUTBOUND", outboundIds.get(1), decimal("60.00")))));
        assertMoney("110.00", "select allocated_amount from payment where id=?", paymentId);
        assertMoney("70.00", "select unallocated_amount from payment where id=?", paymentId);
        assertEquals("PARTIAL", requiredString("select verification_status from payment where id=?", paymentId));
        assertMoney("110.00", "select amount from receivable_movement where ref_type='PAYMENT' and ref_id=?", paymentId);
        assertMoney("110.00", """
                select amount from business_posting_entry e join business_posting_batch b on b.id=e.posting_batch_id
                where b.source_type='PAYMENT' and b.source_id=? and b.posting_action='POST'
                  and e.fact_type='RECEIVABLE_SETTLEMENT'
                """, paymentId);
        assertMoney("70.00", """
                select amount from business_posting_entry e join business_posting_batch b on b.id=e.posting_batch_id
                where b.source_type='PAYMENT' and b.source_id=? and b.posting_action='POST'
                  and e.fact_type='UNALLOCATED_CUSTOMER_RECEIPT'
                """, paymentId);

        copperService.changePaymentAllocations(paymentId, new PaymentAllocationDtos.AllocationChange(
                token + " 未分配余额后补第三单", date.plusDays(1), List.of(), List.of(
                new PaymentAllocationDtos.AllocationInput("OUTBOUND", outboundIds.get(2), decimal("40.00")))));
        assertMoney("150.00", "select allocated_amount from payment where id=?", paymentId);
        assertMoney("30.00", "select unallocated_amount from payment where id=?", paymentId);
        assertMoney("40.00", """
                select amount from business_posting_entry e join business_posting_batch b on b.id=e.posting_batch_id
                where b.source_type='PAYMENT' and b.source_id=? and b.posting_action='ALLOCATION_CHANGE'
                  and e.fact_type='CUSTOMER_RECEIPT_ALLOCATION' and e.direction='INCREASE'
                """, paymentId);
        assertEquals(3L, count("select count(*) from payment_allocation where payment_id=? and status='ACTIVE'", paymentId));

        Long firstAllocationId = requiredLong("""
                select id from payment_allocation where payment_id=? and target_type='OUTBOUND'
                  and target_id=? and status='ACTIVE'
                """, paymentId, outboundIds.get(0));
        copperService.changePaymentAllocations(paymentId, new PaymentAllocationDtos.AllocationChange(
                token + " 释放并重分配第一单", date.plusDays(2), List.of(firstAllocationId), List.of(
                new PaymentAllocationDtos.AllocationInput("OUTBOUND", outboundIds.get(0), decimal("45.00")))));
        assertEquals("RELEASED", requiredString("select status from payment_allocation where id=?", firstAllocationId));
        assertMoney("145.00", "select allocated_amount from payment where id=?", paymentId);
        assertMoney("35.00", "select unallocated_amount from payment where id=?", paymentId);
        assertMoney("5.00", """
                select amount from business_posting_entry e join business_posting_batch b on b.id=e.posting_batch_id
                where b.source_type='PAYMENT' and b.source_id=? and b.posting_action='ALLOCATION_CHANGE'
                  and e.fact_type='CUSTOMER_RECEIPT_ALLOCATION' and e.direction='DECREASE'
                """, paymentId);
        assertTrue(count("select count(*) from payment_allocation_event where payment_id=? and action='RELEASED'", paymentId) >= 1);

        Long secondReceipt = copperService.createPayment(new PaymentCreateRequest(
                partnerId, null, null, null, decimal("20.00"), "CASH", null, date.plusDays(2),
                token + " 同单第二笔收款", List.of(
                new PaymentAllocationDtos.AllocationInput("OUTBOUND", outboundIds.get(1), decimal("20.00")))));
        Long thirdReceipt = copperService.createPayment(new PaymentCreateRequest(
                partnerId, null, null, null, decimal("20.00"), "CASH", null, date.plusDays(2),
                token + " 同单第三笔收款", List.of(
                new PaymentAllocationDtos.AllocationInput("OUTBOUND", outboundIds.get(1), decimal("20.00")))));
        assertMoney("100.00", "select received_amount from sales_order where id=?", salesIds.get(1));
        assertEquals(3L, count("""
                select count(distinct a.payment_id) from payment_allocation a
                where a.target_type='OUTBOUND' and a.target_id=? and a.status='ACTIVE'
                """, outboundIds.get(1)));
        assertThrows(BusinessException.class, () -> copperService.createPayment(new PaymentCreateRequest(
                partnerId, null, null, null, decimal("1.00"), "CASH", null, date.plusDays(2),
                token + " 超额核销必须失败", List.of(
                new PaymentAllocationDtos.AllocationInput("OUTBOUND", outboundIds.get(1), decimal("1.00"))))));

        PaymentAllocationDtos.Detail detail = copperService.paymentAllocationDetail(paymentId);
        assertEquals(4, detail.allocations().size());
        assertTrue(detail.events().size() >= 5);
        copperService.reversePayment(paymentId,
                new OutboundActionRequests.Reason(token + " 多单收款整笔冲正", date.plusDays(3)));
        assertMoney("0.00", "select received_amount from sales_order where id=?", salesIds.get(0));
        assertMoney("40.00", "select received_amount from sales_order where id=?", salesIds.get(1));
        assertMoney("0.00", "select received_amount from sales_order where id=?", salesIds.get(2));
        assertEquals(3L, count("select count(*) from payment_allocation where payment_id=? and status='REVERSED'", paymentId));
        assertEquals(3L, count("select count(*) from payment_allocation_event where payment_id=? and action='HEAD_REVERSED'", paymentId));
        assertEquals("VERIFIED", requiredString("select verification_status from payment where id=?", secondReceipt));
        assertEquals("VERIFIED", requiredString("select verification_status from payment where id=?", thirdReceipt));

        List<Long> purchaseIds = new java.util.ArrayList<>();
        for (int index = 1; index <= 3; index++) {
            Long id = copperService.createPurchaseOrder(new PurchaseCreateRequest(
                    partnerId, date, date.plusDays(1), sourceWarehouseId, "PHONE", token + "-PO-" + index,
                    ZERO, ZERO, token + " 多单付款验收",
                    List.of(new PurchaseCreateRequest.Item(null, productId, decimal("10.000"), "kg",
                            null, null, null, "kg", "", decimal("10.0000")))));
            copperService.confirmPurchaseOrder(id);
            purchaseIds.add(id);
        }
        Long supplierPaymentId = copperService.createSupplierPayment(new SupplierPaymentCreateRequest(
                partnerId, null, null, decimal("170.00"), "BANK", bankAccountId, date.plusDays(4),
                token + " 一笔付款跨三单", List.of(
                new PaymentAllocationDtos.AllocationInput("PURCHASE_ORDER", purchaseIds.get(0), decimal("50.00")),
                new PaymentAllocationDtos.AllocationInput("PURCHASE_ORDER", purchaseIds.get(1), decimal("60.00")))));
        copperService.changeSupplierPaymentAllocations(supplierPaymentId,
                new PaymentAllocationDtos.AllocationChange(token + " 付款未分配后补", date.plusDays(5),
                        List.of(), List.of(new PaymentAllocationDtos.AllocationInput(
                        "PURCHASE_ORDER", purchaseIds.get(2), decimal("40.00")))));
        assertMoney("150.00", "select allocated_amount from supplier_payment where id=?", supplierPaymentId);
        assertMoney("20.00", "select unallocated_amount from supplier_payment where id=?", supplierPaymentId);
        assertMoney("60.00", """
                select amount from business_posting_entry e join business_posting_batch b on b.id=e.posting_batch_id
                where b.source_type='SUPPLIER_PAYMENT' and b.source_id=? and b.posting_action='POST'
                  and e.fact_type='UNALLOCATED_SUPPLIER_PAYMENT'
                """, supplierPaymentId);
        assertMoney("40.00", """
                select amount from business_posting_entry e join business_posting_batch b on b.id=e.posting_batch_id
                where b.source_type='SUPPLIER_PAYMENT' and b.source_id=? and b.posting_action='ALLOCATION_CHANGE'
                  and e.fact_type='SUPPLIER_PAYMENT_ALLOCATION' and e.direction='INCREASE'
                """, supplierPaymentId);
        assertEquals(3L, count("select count(*) from supplier_payment_allocation where supplier_payment_id=? and status='ACTIVE'", supplierPaymentId));
        assertEquals(3, copperService.supplierPaymentAllocationDetail(supplierPaymentId).allocations().size());
        copperService.reverseSupplierPayment(supplierPaymentId,
                new OutboundActionRequests.Reason(token + " 多单付款整笔冲正", date.plusDays(6)));
        for (Long purchaseId : purchaseIds) assertMoney("0.00", "select paid_amount from purchase_order where id=?", purchaseId);
        assertEquals(3L, count("select count(*) from supplier_payment_allocation_event where supplier_payment_id=? and action='HEAD_REVERSED'", supplierPaymentId));

        assertEquals("PASS", check(financialCloseService.closePreview(periodId), "PAYMENT_ALLOCATION").status());
    }

    private Long createInputInvoice(Long inboundId, Long supplierPaymentId) {
        Long taxRateId = requiredLong("select id from tax_rate_rule where rule_code='VAT_GOODS_13' and status='ENABLED'");
        LocalDate invoiceDate = LocalDate.of(2026, 1, 22);
        Long invoiceId = taxInvoiceService.saveInvoice(null, new TaxInvoiceDtos.InvoiceSave(
                "INPUT", "BLUE", "DIGITAL_SPECIAL", "", "20260100000000000001", null,
                partnerId, token + " 往来单位", "91370200E2E000001", "月结验收企业",
                "91370200E2E000002", invoiceDate, invoiceDate, "TAX_DIGITAL_ACCOUNT", "CNY",
                "input-invoice.pdf", "", token + " 进项发票", null,
                List.of(new TaxInvoiceDtos.InvoiceLineSave(
                        productId, token + " 紫铜大盘管", token + "-SKU", "19.05x1.0", "kg",
                        decimal("95.000000"), decimal("10.000000"), decimal("950.00"), taxRateId,
                        "107060101", decimal("123.50"), decimal("1073.50")))));
        taxInvoiceService.confirmInvoice(invoiceId);

        Long payableMovementId = requiredLong("""
                select id from payable_movement
                where supplier_id=? and ref_type='INBOUND' and ref_id=? and direction='INCREASE'
                """, partnerId, inboundId);
        addInvoiceRelation(invoiceId, "INBOUND", inboundId, "950.00", "123.50", "1073.50");
        addInvoiceRelation(invoiceId, "PAYABLE", payableMovementId, "950.00", "123.50", "1073.50");
        addInvoiceRelation(invoiceId, "SUPPLIER_PAYMENT", supplierPaymentId,
                "950.00", "123.50", "1073.50");

        Long proofId = uploadVerificationProof(invoiceId, "input-verification.png");
        taxInvoiceService.verifyInvoice(invoiceId, new TaxInvoiceDtos.VerifyRequest(
                "VERIFIED", token + "-INPUT-VERIFY", "TAX_DIGITAL_ACCOUNT", proofId,
                LocalDateTime.now(), "查验结果一致", "进项发票真实查验凭据"));
        taxInvoiceService.confirmBooking(invoiceId,
                new TaxInvoiceDtos.BookingRequest(token + "-INPUT-BOOKING", "税务数字账户已入账"));
        taxInvoiceService.confirmUsage(invoiceId, new TaxInvoiceDtos.UsageRequest(
                "DEDUCTION", "2026-01", token + "-INPUT-DEDUCTION", "本期抵扣"));
        return invoiceId;
    }

    private Long createOutputInvoice(Long outboundId, Long customerPaymentId, Long acceptancePaymentId) {
        Long taxRateId = requiredLong("select id from tax_rate_rule where rule_code='VAT_GOODS_13' and status='ENABLED'");
        LocalDate invoiceDate = LocalDate.of(2026, 1, 23);
        Long invoiceId = taxInvoiceService.saveInvoice(null, new TaxInvoiceDtos.InvoiceSave(
                "OUTPUT", "BLUE", "DIGITAL_SPECIAL", "", "20260100000000000002", null,
                partnerId, "月结验收企业", "91370200E2E000002", token + " 往来单位",
                "91370200E2E000001", invoiceDate, invoiceDate, "TAX_DIGITAL_ACCOUNT", "CNY",
                "output-invoice.pdf", "", token + " 销项发票", null,
                List.of(new TaxInvoiceDtos.InvoiceLineSave(
                        productId, token + " 紫铜大盘管", token + "-SKU", "19.05x1.0", "kg",
                        decimal("50.000000"), decimal("20.000000"), decimal("1000.00"), taxRateId,
                        "107060101", decimal("130.00"), decimal("1130.00")))));
        taxInvoiceService.confirmInvoice(invoiceId);

        Long receivableMovementId = requiredLong("""
                select id from receivable_movement
                where customer_id=? and ref_type='OUTBOUND' and ref_id=? and direction='DEBIT'
                """, partnerId, outboundId);
        addInvoiceRelation(invoiceId, "OUTBOUND", outboundId, "1000.00", "130.00", "1130.00");
        addInvoiceRelation(invoiceId, "RECEIVABLE", receivableMovementId,
                "1000.00", "130.00", "1130.00");
        addInvoiceRelation(invoiceId, "PAYMENT", customerPaymentId, "700.00", "100.00", "800.00");
        addInvoiceRelation(invoiceId, "PAYMENT", acceptancePaymentId, "300.00", "30.00", "330.00");

        Long proofId = uploadVerificationProof(invoiceId, "output-verification.png");
        taxInvoiceService.verifyInvoice(invoiceId, new TaxInvoiceDtos.VerifyRequest(
                "VERIFIED", token + "-OUTPUT-VERIFY", "TAX_DIGITAL_ACCOUNT", proofId,
                LocalDateTime.now(), "查验结果一致", "销项发票真实查验凭据"));
        return invoiceId;
    }

    private void addInvoiceRelation(Long invoiceId, String sourceType, Long sourceId,
                                    String net, String tax, String total) {
        taxInvoiceService.addRelation(invoiceId, new TaxInvoiceDtos.RelationSave(
                sourceType, sourceId, decimal(net), decimal(tax), decimal(total), token + " 真实来源勾稽"));
    }

    private Long uploadVerificationProof(Long invoiceId, String fileName) {
        byte[] png = Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl2nfsAAAAASUVORK5CYII=");
        EvidenceDtos.AttachmentView attachment = evidenceAttachmentService.upload(
                "TAX_INVOICE", invoiceId, "VERIFICATION_PROOF", "TAX_DIGITAL_ACCOUNT",
                token + " 发票查验凭据",
                new MockMultipartFile("file", fileName, "image/png", png));
        assertTrue(attachment.fileSize() > 0);
        assertFalse(attachment.sha256().isBlank());
        return attachment.id();
    }

    private Long reconcileBank(Long supplierPaymentId, Long customerPaymentId,
                               Long acceptanceEventId, Long advancePaymentId, Long advanceRefundId) {
        Long sessionId = financialCloseService.createReconciliation(
                new FinancialCloseDtos.ReconciliationCreate(bankAccountId, periodId, ZERO, decimal("206.50")));
        financialCloseService.saveStatementLines(sessionId, new FinancialCloseDtos.StatementLinesSave(List.of(
                statement(LocalDate.of(2026, 1, 12), token + "-B001", "DEBIT", "1073.50", "-1073.50",
                        token + " 往来单位", "采购净额付款"),
                statement(LocalDate.of(2026, 1, 13), token + "-B002", "CREDIT", "800.00", "-273.50",
                        token + " 往来单位", "销售银行收款"),
                statement(LocalDate.of(2026, 1, 18), token + "-B003", "CREDIT", "330.00", "56.50",
                        "验收承兑银行", "承兑到期收款"),
                statement(LocalDate.of(2026, 1, 20), token + "-B004", "CREDIT", "200.00", "256.50",
                        token + " 往来单位", "客户无单预收"),
                statement(LocalDate.of(2026, 1, 21), token + "-B005", "DEBIT", "50.00", "206.50",
                        token + " 往来单位", "退回部分预收"))));

        matchStatement(sessionId, token + "-B001", "SUPPLIER_PAYMENT", supplierPaymentId,
                "1073.50", "PURCHASE_PAYMENT");
        matchStatement(sessionId, token + "-B002", "PAYMENT", customerPaymentId,
                "800.00", "SALES_COLLECTION");
        matchStatement(sessionId, token + "-B003", "ACCEPTANCE_EVENT", acceptanceEventId,
                "330.00", "SALES_COLLECTION");
        matchStatement(sessionId, token + "-B004", "PAYMENT", advancePaymentId,
                "200.00", "OTHER_OPERATING_IN");
        matchStatement(sessionId, token + "-B005", "CUSTOMER_ADVANCE_REFUND", advanceRefundId,
                "50.00", "OTHER_OPERATING_OUT");
        financialCloseService.confirmReconciliation(sessionId, token + " 逐笔银行对账完成");
        return sessionId;
    }

    private FinancialCloseDtos.StatementLineInput statement(LocalDate date, String serialNo, String direction,
                                                             String amount, String balance,
                                                             String counterparty, String purpose) {
        return new FinancialCloseDtos.StatementLineInput(
                date, date, serialNo, direction, decimal(amount), decimal(balance),
                counterparty, "", purpose);
    }

    private void matchStatement(Long sessionId, String serialNo, String sourceType, Long sourceId,
                                String amount, String cashFlowCategory) {
        Long statementLineId = requiredLong(
                "select id from bank_statement_line where session_id=? and bank_serial_no=?", sessionId, serialNo);
        financialCloseService.createMatch(sessionId, new FinancialCloseDtos.MatchSave(
                statementLineId, sourceType, sourceId, decimal(amount), cashFlowCategory,
                token + " 金额日期对方用途核对一致"));
    }

    private void postAllDraftVouchers() {
        List<Long> voucherIds = jdbc.queryForList("""
                select id from accounting_voucher
                where entity_id=? and voucher_date between ? and ? and status='DRAFT'
                order by voucher_date,id
                """, Long.class, entityId, PERIOD_START, PERIOD_END);
        assertTrue(voucherIds.size() >= 18, "业务过账和税票必须至少形成18张真实凭证");
        for (Long voucherId : voucherIds) {
            AccountingDtos.VoucherDetail detail = accountingService.voucher(voucherId);
            assertMoneyEquals(detail.voucher().debitTotal().toPlainString(), detail.voucher().creditTotal());
            assertTrue(detail.lines().size() >= 2);
            accountingService.reviewVoucher(voucherId, token + " 月结验收审核");
            accountingService.postVoucher(voucherId);
        }
    }

    private FinancialCloseDtos.CloseCheck check(FinancialCloseDtos.PeriodClosePreview preview, String code) {
        return preview.checks().stream().filter(item -> code.equals(item.checkCode())).findFirst()
                .orElseThrow(() -> new AssertionError("缺少关账检查项 " + code));
    }

    private void createIsolatedAccountingEntity() {
        Long sourceEntityId = requiredLong(
                "select id from accounting_entity where status='ENABLED' order by id limit 1");
        jdbc.update("update accounting_entity set status='DISABLED' where status='ENABLED'");
        jdbc.update("""
                insert into accounting_entity
                (entity_code,entity_name,base_currency,fiscal_year_start_month,status)
                values (?,?, 'CNY',1,'ENABLED')
                """, token, token + " 月结隔离会计主体");
        entityId = requiredLong("select id from accounting_entity where entity_code=?", token);
        jdbc.update("""
                insert into accounting_period
                (entity_id,fiscal_year,period_no,period_name,start_date,end_date,status)
                values (?,2026,1,?,?,?,'OPEN')
                """, entityId, token + " 2026年1月", PERIOD_START, PERIOD_END);
        periodId = requiredLong(
                "select id from accounting_period where entity_id=? and fiscal_year=2026 and period_no=1", entityId);

        jdbc.update("""
                insert into accounting_account
                (entity_id,account_code,account_name,category,normal_direction,parent_id,account_level,leaf,
                 allow_manual,partner_auxiliary,product_auxiliary,warehouse_auxiliary,
                 effective_from,effective_to,status)
                select ?,account_code,account_name,category,normal_direction,0,account_level,leaf,
                       allow_manual,partner_auxiliary,product_auxiliary,warehouse_auxiliary,
                       effective_from,effective_to,status
                from accounting_account where entity_id=?
                """, entityId, sourceEntityId);
        jdbc.update("""
                insert into accounting_rule_version
                (entity_id,rule_code,rule_name,source_type,posting_action,fact_type,fact_direction,
                 debit_account_id,credit_account_id,version_no,effective_from,effective_to,status,
                 change_reason,published_by,published_by_name,published_at)
                select ?,r.rule_code,r.rule_name,r.source_type,r.posting_action,r.fact_type,r.fact_direction,
                       nda.id,nca.id,r.version_no,r.effective_from,r.effective_to,r.status,
                       concat(r.change_reason,'；月结端到端隔离副本'),r.published_by,r.published_by_name,r.published_at
                from accounting_rule_version r
                join accounting_account oda on oda.id=r.debit_account_id
                join accounting_account oca on oca.id=r.credit_account_id
                join accounting_account nda on nda.entity_id=? and nda.account_code=oda.account_code
                join accounting_account nca on nca.entity_id=? and nca.account_code=oca.account_code
                where r.entity_id=?
                """, entityId, entityId, entityId, sourceEntityId);
        jdbc.update("""
                insert into financial_report_line
                (entity_id,report_type,line_code,line_name,line_order,calculation_type,formula_code,
                 normal_direction,effective_from,effective_to,status)
                select ?,report_type,line_code,line_name,line_order,calculation_type,formula_code,
                       normal_direction,effective_from,effective_to,status
                from financial_report_line where entity_id=?
                """, entityId, sourceEntityId);
        jdbc.update("""
                insert into financial_report_account_map
                (entity_id,report_type,line_id,account_id,amount_sign,effective_from,effective_to)
                select ?,m.report_type,nl.id,na.id,m.amount_sign,m.effective_from,m.effective_to
                from financial_report_account_map m
                join financial_report_line ol on ol.id=m.line_id
                join accounting_account oa on oa.id=m.account_id
                join financial_report_line nl on nl.entity_id=? and nl.report_type=ol.report_type
                  and nl.line_code=ol.line_code and nl.effective_from=ol.effective_from
                join accounting_account na on na.entity_id=? and na.account_code=oa.account_code
                where m.entity_id=?
                """, entityId, entityId, entityId, sourceEntityId);
        Long bankLedgerAccountId = requiredLong(
                "select id from accounting_account where entity_id=? and account_code='1002'", entityId);
        jdbc.update("""
                insert into business_bank_account
                (entity_id,account_code,account_name,bank_name,bank_account_no,currency,
                 ledger_account_id,opening_date,opening_balance,status,created_by,created_by_name)
                values (?,?,?,? ,?,'CNY',?,?,0,'ENABLED',1,?)
                """, entityId, token + "-BANK", token + " 月结验收账户", "验收银行",
                token + "000001", bankLedgerAccountId, PERIOD_START, "月结端到端验收");
        bankAccountId = requiredLong(
                "select id from business_bank_account where entity_id=? and account_code=?",
                entityId, token + "-BANK");

        assertEquals(26L, count("select count(*) from accounting_account where entity_id=?", entityId));
        assertEquals(count("select count(*) from accounting_rule_version where entity_id=?", sourceEntityId),
                count("select count(*) from accounting_rule_version where entity_id=?", entityId));
        assertEquals(28L, count("select count(*) from financial_report_line where entity_id=?", entityId));
        assertEquals(26L, count("select count(*) from financial_report_account_map where entity_id=?", entityId));
    }

    private void createBusinessMasterData() {
        String businessNo = String.valueOf(600000 + Math.floorMod(token.hashCode(), 299999));
        jdbc.update("""
                insert into partner
                (business_no,type,type_mask,name,contact_person,phone,credit_code,status,memo)
                values (?,'CUSTOMER',3,?,'验收人员','00000000000','91370200E2E000001','ENABLED',?)
                """, businessNo, token + " 往来单位", "INTERNAL_REHEARSAL_ONLY " + token);
        partnerId = requiredLong("select id from partner where name=?", token + " 往来单位");
        jdbc.update("insert into partner_customer_profile(partner_id,status) values (?,'ENABLED')", partnerId);
        jdbc.update("insert into partner_supplier_profile(partner_id,status) values (?,'ENABLED')", partnerId);
        jdbc.update("""
                insert into partner_address
                (partner_id,name,address_type,detail,contact_person,phone,first_choice,status,memo)
                values (?,?,'RECEIVING',?,'验收人员','00000000000',1,'ENABLED',?)
                """, partnerId, token + " 收货地址", "青岛市月结验收隔离地址", token);
        addressId = requiredLong(
                "select id from partner_address where partner_id=? and name=?", partnerId, token + " 收货地址");
        jdbc.update("insert into warehouse(name,location,status) values (?,?,'ENABLED')",
                token + " 源仓", "INTERNAL_REHEARSAL_ONLY");
        jdbc.update("insert into warehouse(name,location,status) values (?,?,'ENABLED')",
                token + " 目标仓", "INTERNAL_REHEARSAL_ONLY");
        sourceWarehouseId = requiredLong("select id from warehouse where name=?", token + " 源仓");
        targetWarehouseId = requiredLong("select id from warehouse where name=?", token + " 目标仓");
        productId = copperService.createProduct(new SimpleCreateRequests.ProductCreate(
                null, 2001L, null, token + "-SKU", token + " 紫铜大盘管",
                "", "", "", "", "{}", "", "", "kg", decimal("100.0000"), "ENABLED",
                List.of(
                        new SimpleCreateRequests.ProductFieldValueCreate(300001L, decimal("19.050")),
                        new SimpleCreateRequests.ProductFieldValueCreate(300002L, decimal("1.000")))));
    }

    @AfterTransaction
    void assertRollbackRemovedEveryAcceptanceFixture() {
        if (token == null) return;
        assertEquals(0L, count("select count(*) from accounting_entity where entity_code=?", token));
        assertEquals(0L, count("select count(*) from product where sku=?", token + "-SKU"));
        assertEquals(0L, count("select count(*) from partner where name=?", token + " 往来单位"));
        assertEquals(0L, count("select count(*) from warehouse where name like ?", token + "%"));
        assertEquals(1L, count("select count(*) from accounting_entity where entity_code='DEFAULT' and status='ENABLED'"));
    }

    private void assertQuantity(String expected, String sql, Object... args) {
        assertEquals(0, decimal(expected).compareTo(requiredDecimal(sql, args)),
                () -> "数量不一致，SQL=" + sql);
    }

    private void assertMoney(String expected, String sql, Object... args) {
        assertMoneyEquals(expected, requiredDecimal(sql, args));
    }

    private void assertMoneyEquals(String expected, BigDecimal actual) {
        assertEquals(0, decimal(expected).setScale(2).compareTo(actual.setScale(2)),
                () -> "金额不一致，期望=" + expected + "，实际=" + actual);
    }

    private BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }

    private Long requiredLong(String sql, Object... args) {
        return Objects.requireNonNull(jdbc.queryForObject(sql, Long.class, args));
    }

    private String requiredString(String sql, Object... args) {
        return Objects.requireNonNull(jdbc.queryForObject(sql, String.class, args));
    }

    private BigDecimal requiredDecimal(String sql, Object... args) {
        return Objects.requireNonNull(jdbc.queryForObject(sql, BigDecimal.class, args));
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0L : value;
    }
}
