package com.soyia.copperv2.service;

import com.soyia.copperv2.dto.FinancialCloseDtos;
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
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIfEnvironmentVariable(named = "COPPER_DIRTY_CLOSE_ACCEPTANCE", matches = "true")
@Transactional
class FinancialCloseDirtyDataMySqlAcceptanceTest {
    private static final BigDecimal ZERO = new BigDecimal("0.00");
    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100.00");

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private FinancialCloseService closeService;

    private long entityId;
    private long periodId;
    private long fixtureProductId;
    private LocalDate periodStart;
    private LocalDate periodEnd;
    private String token;
    private String compactToken;

    @BeforeEach
    void createIsolatedPastPeriodInsideRollbackTransaction() {
        assertTrue(TransactionSynchronizationManager.isActualTransactionActive(),
                "脏数据验收必须在可回滚事务中运行");
        token = "DCA-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        compactToken = token.replace("-", "");
        fixtureProductId = 9_900_000_000L + Integer.toUnsignedLong(token.hashCode());
        entityId = requiredLong("select id from accounting_entity where status='ENABLED' order by id limit 1");

        int fiscalYear = 1999;
        while (count("select count(*) from accounting_period where entity_id=? and fiscal_year=?", entityId, fiscalYear) > 0) {
            fiscalYear--;
        }
        periodStart = LocalDate.of(fiscalYear, 1, 1);
        periodEnd = periodStart.withDayOfMonth(periodStart.lengthOfMonth());
        jdbc.update("""
                insert into accounting_period
                (entity_id,fiscal_year,period_no,period_name,start_date,end_date,status)
                values (?,?,1,?,?,?,'OPEN')
                """, entityId, fiscalYear, token + "-PERIOD",
                Date.valueOf(periodStart), Date.valueOf(periodEnd));
        periodId = requiredLong("select id from accounting_period where entity_id=? and fiscal_year=? and period_no=1",
                entityId, fiscalYear);
    }

    @Test
    @DisplayName("未记账凭证必须阻断关账")
    void unpostedVoucherBlocksClose() {
        assertDirtyFixtureBlocks("VOUCHER_STATUS", () -> jdbc.update("""
                insert into accounting_voucher
                (entity_id,period_id,voucher_no,voucher_type,voucher_date,summary,source_type,source_id,
                 source_no,status,debit_total,credit_total,maker_name)
                values (?,?,?,'MANUAL',?,?,'DIRTY_CLOSE_FIXTURE',0,?,'DRAFT',100,100,'验收脚本')
                """, entityId, periodId, token + "-VOUCHER", Date.valueOf(periodStart.plusDays(1)),
                "未记账凭证脏数据", token));
    }

    @Test
    @DisplayName("未制证业务过账必须阻断关账")
    void businessPostingWithoutVoucherBlocksClose() {
        assertDirtyFixtureBlocks("BUSINESS_POSTING", () -> jdbc.update("""
                insert into business_posting_batch
                (posting_no,source_type,source_id,source_no,posting_action,rule_version_id,rule_code,
                 business_date,idempotency_key,status,reason,operator_name)
                values (?,'DIRTY_CLOSE_FIXTURE',1,?,'POST',0,'DIRTY_CLOSE',?,?, 'POSTED',?,'验收脚本')
                """, token + "-POSTING", token, Date.valueOf(periodStart.plusDays(2)),
                token + "-POSTING-IDEMPOTENCY", "未生成会计凭证"));
    }

    @Test
    @DisplayName("未完成库存单据必须阻断关账")
    void unfinishedInventoryDocumentBlocksClose() {
        assertDirtyFixtureBlocks("INVENTORY_DOCUMENTS", () -> jdbc.update("""
                insert into inventory_cost_adjustment
                (adjustment_no,business_date,status,total_difference_amount,reason,memo,created_by)
                values (?,?,'DRAFT',10,?,'INTERNAL_REHEARSAL_ONLY',0)
                """, token + "-COST-ADJ", Date.valueOf(periodStart.plusDays(3)), "未完成库存成本调整"));
    }

    @Test
    @DisplayName("库存批次与余额差异必须阻断关账")
    void inventoryMismatchBlocksClose() {
        // The mismatch must be business-level dirty stock, not an invalid database reference.
        jdbc.update("insert into product(id,product_series_id,sku,name,unit,status) values (?,2001,?,?,'kg','ENABLED')",
                fixtureProductId, token + "-SKU", token + "-PRODUCT");
        jdbc.update("insert into warehouse(id,name,location,status) values (?,?,'INTERNAL_REHEARSAL_ONLY','ENABLED')",
                fixtureProductId, token + "-WAREHOUSE");
        assertDirtyFixtureBlocks("INVENTORY_RECONCILIATION", () -> jdbc.update("""
                insert into stock_balance(product_id,warehouse_id,quantity_available,amount_cost)
                values (?,?,5.000,500.00)
                """, fixtureProductId, fixtureProductId));
    }

    @Test
    @DisplayName("往来流水累计与末次余额差异必须阻断关账")
    void partnerBalanceMismatchBlocksClose() {
        assertDirtyFixtureBlocks("PARTNER_BALANCE", () -> jdbc.update("""
                insert into receivable_movement
                (movement_no,customer_id,ref_type,ref_id,direction,amount,balance_after,occurred_at,memo)
                values (?,?,'DIRTY_CLOSE_FIXTURE',1,'DEBIT',100,90,?,?)
                """, token + "-AR", fixtureProductId,
                Timestamp.valueOf(periodStart.plusDays(4).atTime(12, 0)), "应收累计100但末次余额90"));
    }

    @Test
    @DisplayName("票货款账未勾稽税票必须阻断关账")
    void taxInvoiceExceptionBlocksClose() {
        assertDirtyFixtureBlocks("TAX_INVOICE", () -> jdbc.update("""
                insert into tax_invoice
                (entity_id,internal_no,direction,invoice_color,invoice_type,invoice_number,partner_id,
                 partner_name_snapshot,issuer_name,recipient_name,invoice_date,received_or_issued_date,
                 amount_excluding_tax,tax_amount,total_amount,status,verification_status,booking_status,usage_status,
                 source_channel,memo)
                values (?,?,'OUTPUT','BLUE','DIGITAL_NORMAL',?,?,?,'验收销方','验收购方',?,?,
                        100,13,113,'CONFIRMED','UNVERIFIED','UNCONFIRMED','NOT_APPLICABLE','MANUAL',?)
                """, entityId, token + "-TAX", compactToken, fixtureProductId, "脏数据验收往来单位",
                Date.valueOf(periodStart.plusDays(5)), Date.valueOf(periodStart.plusDays(5)),
                "INTERNAL_REHEARSAL_ONLY：未查验、未勾稽"));
    }

    @Test
    @DisplayName("启用银行账户未完成逐户对账必须阻断关账")
    void unreconciledBankAccountBlocksClose() {
        assertDirtyFixtureBlocks("BANK_RECONCILIATION", () -> {
            long accountId = insertAccount("1002B" + compactToken, "脏数据验收银行存款", "ASSET", "DEBIT");
            jdbc.update("""
                    insert into business_bank_account
                    (entity_id,account_code,account_name,bank_name,bank_account_no,currency,ledger_account_id,
                     opening_date,opening_balance,status,created_by_name)
                    values (?,?,?,'验收银行',?,'CNY',?,?,0,'ENABLED','验收脚本')
                    """, entityId, "BANK-" + compactToken, "脏数据验收账户", compactToken,
                    accountId, Date.valueOf(periodStart));
        });
    }

    @Test
    @DisplayName("现金流推算与货币资金总账不平必须阻断关账")
    void cashFlowMismatchBlocksClose() {
        assertDirtyFixtureBlocks("CASH_FLOW_RECONCILIATION", () -> {
            long accountId = insertAccount("1002C" + compactToken, "脏数据验收未分类银行存款", "ASSET", "DEBIT");
            insertPostedLedger(accountId, "1002C" + compactToken, "脏数据验收未分类银行存款",
                    "ASSET", new BigDecimal("123.45"), ZERO, "CASH");
        });
    }

    @Test
    @DisplayName("有余额或发生额但未映射报表的科目必须阻断关账")
    void unmappedReportAccountBlocksClose() {
        assertDirtyFixtureBlocks("REPORT_MAPPING", () -> {
            long accountId = insertAccount("1199R" + compactToken, "脏数据验收未映射资产", "ASSET", "DEBIT");
            insertPostedLedger(accountId, "1199R" + compactToken, "脏数据验收未映射资产",
                    "ASSET", new BigDecimal("88.00"), ZERO, "REPORT");
        });
    }

    @Test
    @DisplayName("存在损益发生但未生成并记账结转凭证必须阻断关账")
    void unclosedProfitBlocksClose() {
        assertDirtyFixtureBlocks("PROFIT_CARRY_FORWARD", () -> {
            long accountId = insertAccount("6099P" + compactToken, "脏数据验收未结转收入", "REVENUE", "CREDIT");
            insertPostedLedger(accountId, "6099P" + compactToken, "脏数据验收未结转收入",
                    "REVENUE", ZERO, new BigDecimal("200.00"), "PROFIT");
        });
    }

    private void assertDirtyFixtureBlocks(String checkCode, Runnable createDirtyFixture) {
        FinancialCloseDtos.CloseCheck before = check(closeService.closePreview(periodId), checkCode);
        createDirtyFixture.run();

        FinancialCloseDtos.PeriodClosePreview preview = closeService.closePreview(periodId);
        FinancialCloseDtos.CloseCheck after = check(preview, checkCode);
        assertEquals(before.issueCount() + 1, after.issueCount(), checkCode + " 必须准确识别新增的一条脏数据");
        assertEquals("BLOCKED", after.status(), checkCode + " 必须进入阻断状态");
        assertFalse(Boolean.TRUE.equals(preview.closable()), checkCode + " 存在时不得允许关账");
        assertNotNull(after.detail());
        assertFalse(after.detail().isBlank());

        BusinessException exception = assertThrows(BusinessException.class,
                () -> closeService.closePeriod(periodId, "INTERNAL_REHEARSAL_ONLY 脏数据阻断验收"));
        assertTrue(exception.getMessage().contains("期末结账仍有阻断项"));
    }

    private FinancialCloseDtos.CloseCheck check(FinancialCloseDtos.PeriodClosePreview preview, String code) {
        return preview.checks().stream().filter(item -> code.equals(item.checkCode())).findFirst()
                .orElseThrow(() -> new AssertionError("缺少关账检查项：" + code));
    }

    private long insertAccount(String code, String name, String category, String direction) {
        jdbc.update("""
                insert into accounting_account
                (entity_id,account_code,account_name,category,normal_direction,allow_manual,
                 partner_auxiliary,product_auxiliary,warehouse_auxiliary,effective_from,status)
                values (?,?,?,?,?,1,'NONE','NONE','NONE',?,'ENABLED')
                """, entityId, code, name, category, direction, Date.valueOf(periodStart.minusYears(1)));
        return requiredLong("select id from accounting_account where entity_id=? and account_code=?", entityId, code);
    }

    private void insertPostedLedger(long accountId, String accountCode, String accountName,
                                    String category, BigDecimal debit, BigDecimal credit, String label) {
        String voucherNo = token + "-" + label;
        jdbc.update("""
                insert into accounting_voucher
                (entity_id,period_id,voucher_no,voucher_type,voucher_date,summary,source_type,source_id,
                 source_no,status,debit_total,credit_total,maker_name,reviewer_name,poster_name,reviewed_at,posted_at)
                values (?,?,?,'MANUAL',?,?,'DIRTY_CLOSE_FIXTURE',0,?,'POSTED',?,?,
                        '验收脚本','验收脚本','验收脚本',now(),now())
                """, entityId, periodId, voucherNo, Date.valueOf(periodStart.plusDays(10)),
                "脏数据验收 " + category, token, debit, credit);
        long voucherId = requiredLong("select id from accounting_voucher where entity_id=? and voucher_no=?",
                entityId, voucherNo);
        jdbc.update("""
                insert into accounting_voucher_line
                (voucher_id,line_no,account_id,account_code_snapshot,account_name_snapshot,summary,debit_amount,credit_amount)
                values (?,1,?,?,?,?,?,?)
                """, voucherId, accountId, accountCode, accountName, "脏数据验收", debit, credit);
        long lineId = requiredLong("select id from accounting_voucher_line where voucher_id=? and line_no=1", voucherId);
        jdbc.update("""
                insert into accounting_ledger_entry
                (entity_id,period_id,voucher_id,voucher_line_id,voucher_no,voucher_date,account_id,account_code,
                 account_name,summary,debit_amount,credit_amount)
                values (?,?,?,?,?,?,?,?,?,?,?,?)
                """, entityId, periodId, voucherId, lineId, voucherNo, Date.valueOf(periodStart.plusDays(10)),
                accountId, accountCode, accountName, "脏数据验收", debit, credit);
    }

    @AfterTransaction
    void assertRollbackRemovedEveryDirtyFixture() {
        if (token == null) return;
        assertEquals(0, count("select count(*) from accounting_period where period_name like ?", token + "%"));
        assertEquals(0, count("select count(*) from accounting_voucher where voucher_no like ?", token + "%"));
        assertEquals(0, count("select count(*) from business_posting_batch where posting_no like ?", token + "%"));
        assertEquals(0, count("select count(*) from inventory_cost_adjustment where adjustment_no like ?", token + "%"));
        assertEquals(0, count("select count(*) from receivable_movement where movement_no like ?", token + "%"));
        assertEquals(0, count("select count(*) from tax_invoice where internal_no like ?", token + "%"));
        assertEquals(0, count("select count(*) from accounting_account where account_code like ?", "%" + compactToken));
        assertEquals(0, count("select count(*) from business_bank_account where account_code=?", "BANK-" + compactToken));
        assertEquals(0, count("select count(*) from stock_balance where product_id=? and warehouse_id=?",
                fixtureProductId, fixtureProductId));
    }

    private long requiredLong(String sql, Object... args) {
        return Objects.requireNonNull(jdbc.queryForObject(sql, Long.class, args));
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0L : value;
    }
}
