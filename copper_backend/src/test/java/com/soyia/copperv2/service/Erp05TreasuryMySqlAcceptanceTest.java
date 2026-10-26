package com.soyia.copperv2.service;

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
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIfEnvironmentVariable(named = "COPPER_ERP05_E2E", matches = "true")
@Transactional
class Erp05TreasuryMySqlAcceptanceTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TreasuryService treasuryService;
    @Autowired private TaxInvoiceService taxInvoiceService;

    private String token;
    private Long originalEntityId;
    private Long entityId;
    private Long partnerId;
    private Long expenseAccountId;
    private Long sourceBankId;
    private Long destinationBankId;
    private LocalDate businessDate;

    @BeforeEach
    void setUp() {
        assertTrue(TransactionSynchronizationManager.isActualTransactionActive(),
                "ERP-05 端到端验收必须在可回滚事务中运行");
        token = "ERP05" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        businessDate = LocalDate.now();
        SecurityContext.set(new SecurityContext.Principal(
                1L, 1L, "ERP-05验收", "ERP-05", 0L, "验收", token,
                false, List.of("系统管理员"), Set.of(
                "treasury.view", "treasury.document.manage", "treasury.document.confirm",
                "treasury.payment.request", "treasury.fund.manage", "treasury.reverse",
                "invoice.view", "invoice.manage", "invoice.confirm", "accounting.view", "accounting.manage")));
        createIsolatedAccountingEntity();
        createPartnerAndBankAccounts();
    }

    @AfterEach
    void clearContext() {
        SecurityContext.clear();
    }

    @Test
    @DisplayName("费用、付款申请、实际付款、税票重分类、统一日记账和内部转账完整闭环")
    void expensePaymentInvoiceJournalAndTransferCloseWithoutDoubleTax() {
        Long documentId = treasuryService.createDocument(new TreasuryDtos.DocumentCreate(
                "CORPORATE_EXPENSE", partnerId, businessDate, businessDate.plusDays(10),
                token + "-RENT", "仓库房租", expenseAccountId, true,
                decimal("13.0000"), decimal("113.00"), "INTERNAL_REHEARSAL_ONLY"));
        treasuryService.confirmDocument(documentId);
        Long sourceVoucherId = id("select accounting_voucher_id from treasury_document where id=?", documentId);
        assertLine(sourceVoucherId, "5602", "113.00", "0.00");
        assertLine(sourceVoucherId, "2241", "0.00", "113.00");
        assertEquals(0L, count("select count(*) from accounting_voucher_line where voucher_id=? and account_code_snapshot='22210103'", sourceVoucherId),
                "费用源单不得在发票确认前重复拆出进项税");

        Long requestId = treasuryService.createPaymentRequest(new TreasuryDtos.PaymentRequestCreate(
                documentId, decimal("50.00"), businessDate, businessDate.plusDays(2), "支付部分房租"));
        treasuryService.approvePaymentRequest(requestId);
        assertEquals("APPROVED", text("select status from treasury_payment_request where id=?", requestId));
        assertEquals(0L, count("select count(*) from treasury_fund_transaction where payment_request_id=?", requestId),
                "批准付款申请不能伪造实际付款");

        Long fundId = treasuryService.settleDocument(documentId, new TreasuryDtos.SettlementCreate(
                requestId, decimal("50.00"), businessDate, "BANK", sourceBankId,
                "OTHER_OPERATING_OUT", "网银支付部分房租"));
        assertEquals("PARTIALLY_SETTLED", text("select status from treasury_document where id=?", documentId));
        assertMoney("50.00", decimalValue("select settled_amount from treasury_document where id=?", documentId));
        assertEquals(1L, count("select count(*) from treasury_cash_journal where fund_transaction_id=? and status='ACTIVE'", fundId));

        Long taxRateId = id("select id from tax_rate_rule where rule_code='VAT_GOODS_13' and status='ENABLED'");
        Long invoiceId = taxInvoiceService.saveInvoice(null, new TaxInvoiceDtos.InvoiceSave(
                "INPUT", "BLUE", "DIGITAL_SPECIAL", "", "20260722999999999999", null,
                partnerId, token + " 出租方", token, "ERP-05 验收企业", "91370200ERP050001",
                businessDate, businessDate, "TAX_DIGITAL_ACCOUNT", "CNY", token + ".pdf", "",
                "仓库房租进项发票", null,
                List.of(new TaxInvoiceDtos.InvoiceLineSave(null, "仓库房租", "", "", "月",
                        decimal("1.000000"), decimal("100.000000"), decimal("100.00"), taxRateId,
                        "304050202", decimal("13.00"), decimal("113.00")))));
        taxInvoiceService.addRelation(invoiceId, new TaxInvoiceDtos.RelationSave(
                "TREASURY_DOCUMENT", documentId, decimal("100.00"), decimal("13.00"),
                decimal("113.00"), "费用事实勾稽"));
        taxInvoiceService.addRelation(invoiceId, new TaxInvoiceDtos.RelationSave(
                "TREASURY_ACCRUAL", documentId, decimal("100.00"), decimal("13.00"),
                decimal("113.00"), "其他应付勾稽"));
        taxInvoiceService.confirmInvoice(invoiceId);
        Long invoiceVoucherId = id("select accounting_voucher_id from tax_invoice where id=?", invoiceId);
        assertLine(invoiceVoucherId, "22210103", "13.00", "0.00");
        assertLine(invoiceVoucherId, "5602", "0.00", "13.00");
        assertEquals("COMPLETE", taxInvoiceService.invoice(invoiceId).invoice().reconciliationStatus());

        Long transferId = treasuryService.createTransfer(new TreasuryDtos.TransferCreate(
                sourceBankId, destinationBankId, decimal("20.00"), businessDate, "银行账户内部调拨"));
        assertEquals(2L, count("select count(*) from treasury_cash_journal where fund_transaction_id=? and status='ACTIVE'", transferId));
        assertEquals(2L, treasuryService.journal(businessDate, businessDate, null, null).stream()
                .filter(row -> row.fundTransactionId().equals(transferId)).count());
        treasuryService.reverseFund(transferId, new TreasuryDtos.Action("内部转账录入错误，冲正重办", businessDate));
        assertEquals(2L, count("select count(*) from treasury_cash_journal where fund_transaction_id=? and status='REVERSED'", transferId));
    }

    private void createIsolatedAccountingEntity() {
        originalEntityId = id("select id from accounting_entity where status='ENABLED' order by id limit 1");
        jdbc.update("update accounting_entity set status='DISABLED' where status='ENABLED'");
        jdbc.update("insert into accounting_entity(entity_code,entity_name,base_currency,fiscal_year_start_month,status) values (?,?,'CNY',1,'ENABLED')",
                token, token + " ERP-05隔离会计主体");
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
        expenseAccountId = id("select id from accounting_account where entity_id=? and account_code='5602'", entityId);
    }

    private void createPartnerAndBankAccounts() {
        jdbc.update("""
                insert into partner(business_no,type,type_mask,name,contact_person,phone,credit_code,status,memo)
                values (?,'SUPPLIER',3,?,'验收人员','00000000000',?,'ENABLED','INTERNAL_REHEARSAL_ONLY')
                """, String.valueOf(600000 + Math.floorMod(token.hashCode(), 300000)), token + " 双重角色往来单位", token);
        partnerId = id("select id from partner where name=?", token + " 双重角色往来单位");
        jdbc.update("insert into partner_customer_profile(partner_id,status,tax_included,invoice_type,tax_rate) values (?,'ENABLED',1,'DIGITAL_SPECIAL',13)", partnerId);
        jdbc.update("insert into partner_supplier_profile(partner_id,status,tax_included,invoice_type,tax_rate) values (?,'ENABLED',1,'DIGITAL_SPECIAL',13)", partnerId);
        sourceBankId = createBank("100201" + token.substring(5), "基本户");
        destinationBankId = createBank("100202" + token.substring(5), "一般户");
    }

    private Long createBank(String ledgerCode, String name) {
        jdbc.update("""
                insert into accounting_account
                (entity_id,account_code,account_name,category,normal_direction,allow_manual,
                 partner_auxiliary,product_auxiliary,warehouse_auxiliary,effective_from,status)
                values (?, ?,?,'ASSET','DEBIT',1,'NONE','NONE','NONE',?,'ENABLED')
                """, entityId, ledgerCode, token + name, businessDate.minusYears(1));
        Long ledgerId = id("select id from accounting_account where entity_id=? and account_code=?", entityId, ledgerCode);
        jdbc.update("""
                insert into business_bank_account
                (entity_id,account_code,account_name,bank_name,bank_account_no,currency,ledger_account_id,
                 opening_date,opening_balance,status,created_by_name)
                values (?,?,?,'验收银行',?,'CNY',?,?,0,'ENABLED','ERP-05验收')
                """, entityId, token + name, token + name, token + name + "0000", ledgerId, businessDate.minusDays(1));
        return id("select id from business_bank_account where entity_id=? and ledger_account_id=?", entityId, ledgerId);
    }

    private void assertLine(Long voucherId, String accountCode, String debit, String credit) {
        assertEquals(1L, count("""
                select count(*) from accounting_voucher_line
                where voucher_id=? and account_code_snapshot=? and debit_amount=? and credit_amount=?
                """, voucherId, accountCode, decimal(debit), decimal(credit)), accountCode);
    }

    private BigDecimal decimal(String value) { return new BigDecimal(value); }
    private Long id(String sql, Object... args) { return Objects.requireNonNull(jdbc.queryForObject(sql, Long.class, args)); }
    private String text(String sql, Object... args) { return Objects.requireNonNull(jdbc.queryForObject(sql, String.class, args)); }
    private BigDecimal decimalValue(String sql, Object... args) { return Objects.requireNonNull(jdbc.queryForObject(sql, BigDecimal.class, args)); }
    private long count(String sql, Object... args) { Long value = jdbc.queryForObject(sql, Long.class, args); return value == null ? 0L : value; }
    private void assertMoney(String expected, BigDecimal actual) { assertEquals(0, decimal(expected).setScale(2).compareTo(actual.setScale(2))); }

    @AfterTransaction
    void rollbackRemovesFixture() {
        if (token == null) return;
        assertEquals(0L, count("select count(*) from accounting_entity where entity_code=?", token));
        assertEquals(1L, count("select count(*) from accounting_entity where id=? and status='ENABLED'", originalEntityId));
        assertEquals(0L, count("select count(*) from partner where name=?", token + " 双重角色往来单位"));
    }
}
