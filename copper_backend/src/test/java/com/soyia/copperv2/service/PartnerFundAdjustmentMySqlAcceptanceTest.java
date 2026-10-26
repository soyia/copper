package com.soyia.copperv2.service;

import com.soyia.copperv2.dto.PartnerFundAdjustmentDtos;
import com.soyia.copperv2.dto.RiskExceptionDtos;
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
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIfEnvironmentVariable(named = "COPPER_ERP02_E2E", matches = "true")
@Transactional
class PartnerFundAdjustmentMySqlAcceptanceTest {
    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 7, 22);

    @Autowired private JdbcTemplate jdbc;
    @Autowired private PartnerFundAdjustmentService service;
    @Autowired private AccountingService accountingService;

    private String token;
    private Long partnerId;

    @BeforeEach
    void setUp() {
        token = "ERP02" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        SecurityContext.set(new SecurityContext.Principal(
                1L, 1L, "ERP-02验收", "ERP-02", 0L, "验收", token,
                false, List.of("系统管理员"), Set.of(
                "partner.fund.adjust.view", "partner.fund.adjust.manage",
                "partner.fund.adjust.approve", "partner.fund.adjust.reverse",
                "accounting.view", "accounting.manage", "risk.exception.approve")));
        String businessNo = String.valueOf(700000 + Math.floorMod(token.hashCode(), 299999));
        jdbc.update("""
                insert into partner
                (business_no,type,type_mask,name,contact_person,phone,credit_code,status,memo)
                values (?,'CUSTOMER',3,?,'验收人员','00000000000',?,'ENABLED',?)
                """, businessNo, token + " 往来单位", token, "INTERNAL_REHEARSAL_ONLY " + token);
        partnerId = jdbc.queryForObject("select id from partner where name=?", Long.class, token + " 往来单位");
        jdbc.update("insert into partner_customer_profile(partner_id,status) values (?,'ENABLED')", partnerId);
        jdbc.update("insert into partner_supplier_profile(partner_id,status) values (?,'ENABLED')", partnerId);
    }

    @AfterEach
    void clearContext() {
        SecurityContext.clear();
    }

    @Test
    void rewardRebateOpeningCorrectionApprovalVoucherAndReversalStayTraceable() {
        Long rewardId = create("CUSTOMER_REWARD", "CUSTOMER_REWARD_GRANT", "INCREASE", "125.00",
                "PENDING_PROFESSIONAL_REVIEW", "客户年度采购奖励协议");
        assertMoney("0.00", balance("CUSTOMER_REWARD"));
        service.confirm(rewardId, new RiskExceptionDtos.InlineAuthorization("负责人复核原始业务依据并确认本次调整", null));
        assertMoney("125.00", balance("CUSTOMER_REWARD"));
        assertVoucher(rewardId, "5601", "2203", "125.00");

        Long rebateId = create("SUPPLIER_REWARD", "SUPPLIER_REBATE_GRANT", "INCREASE", "80.00",
                "SEPARATE_INVOICE_HANDLING", "供应商季度返利对账单");
        service.confirm(rebateId, new RiskExceptionDtos.InlineAuthorization("负责人复核原始业务依据并确认本次调整", null));
        assertMoney("80.00", balance("SUPPLIER_REWARD"));
        assertVoucher(rebateId, "1123", "5051", "80.00");

        Long openingId = create("CUSTOMER_ADVANCE", "OPENING_BALANCE", "INCREASE", "300.00",
                "NOT_APPLICABLE", "上线迁移批次 ERP02-OPENING");
        service.confirm(openingId, new RiskExceptionDtos.InlineAuthorization("负责人复核原始业务依据并确认本次调整", null));
        assertMoney("300.00", balance("CUSTOMER_ADVANCE"));
        assertVoucher(openingId, "3104", "2203", "300.00");
        Long duplicateOpening = create("CUSTOMER_ADVANCE", "OPENING_BALANCE", "INCREASE", "10.00",
                "NOT_APPLICABLE", "重复期初必须阻断");
        assertThrows(BusinessException.class, () -> service.confirm(duplicateOpening, new RiskExceptionDtos.InlineAuthorization("负责人复核原始业务依据并确认本次调整", null)));

        Long correctionId = create("CUSTOMER_ADVANCE", "BALANCE_CORRECTION", "DECREASE", "20.00",
                "NOT_APPLICABLE", "对账发现历史重复余额");
        service.confirm(correctionId, new RiskExceptionDtos.InlineAuthorization("负责人复核原始业务依据并确认本次调整", null));
        assertMoney("280.00", balance("CUSTOMER_ADVANCE"));
        assertVoucher(correctionId, "2203", "5051", "20.00");
        service.reverse(correctionId, new PartnerFundAdjustmentDtos.Action("复核后确认原余额正确，冲正差错单", BUSINESS_DATE));
        assertMoney("300.00", balance("CUSTOMER_ADVANCE"));
        assertEquals("REVERSED", text("select status from partner_fund_adjustment where id=?", correctionId));
        assertEquals(3L, count("select count(*) from partner_fund_adjustment_event where adjustment_id=?", correctionId));

        Long consumeReward = create("CUSTOMER_REWARD", "BALANCE_CORRECTION", "DECREASE", "125.00",
                "NOT_APPLICABLE", "模拟余额已经被后续业务耗用");
        service.confirm(consumeReward, new RiskExceptionDtos.InlineAuthorization("负责人复核原始业务依据并确认本次调整", null));
        BusinessException blocked = assertThrows(BusinessException.class, () -> service.reverse(rewardId,
                new PartnerFundAdjustmentDtos.Action("奖励余额已经使用后不得把原义务冲成负数", BUSINESS_DATE)));
        assertTrue(blocked.getMessage().contains("余额不足"));
        service.reverse(consumeReward, new PartnerFundAdjustmentDtos.Action("恢复奖励余额以完成验收", BUSINESS_DATE));
        service.reverse(rewardId, new PartnerFundAdjustmentDtos.Action("奖励协议撤销，完整冲正原确认", BUSINESS_DATE));
        assertMoney("0.00", balance("CUSTOMER_REWARD"));

        assertEquals(0L, count("select count(*) from partner_account_action where memo like ?", "%" + token + "%"));
        assertTrue(count("select count(*) from partner_account_log where partner_id=? and source_type like 'PARTNER_FUND_ADJUSTMENT%'", partnerId) >= 7);
    }

    private Long create(String accountType, String adjustmentType, String direction, String amount,
                        String taxTreatment, String memo) {
        return service.create(new PartnerFundAdjustmentDtos.Create(
                partnerId, accountType, adjustmentType, direction, new BigDecimal(amount), BUSINESS_DATE,
                token + "-BASIS", taxTreatment, memo));
    }

    private void assertVoucher(Long adjustmentId, String debitCode, String creditCode, String amount) {
        Long batchId = jdbc.queryForObject("""
                select id from business_posting_batch
                where source_type='PARTNER_FUND_ADJUSTMENT' and source_id=? and posting_action='POST'
                """, Long.class, adjustmentId);
        accountingService.generateForBusinessPosting(batchId);
        assertEquals(1L, count("select count(*) from accounting_voucher where business_posting_batch_id=?", batchId));
        assertEquals(1L, count("""
                select count(*) from accounting_voucher_line l join accounting_account a on a.id=l.account_id
                where l.voucher_id=(select id from accounting_voucher where business_posting_batch_id=?)
                  and a.account_code=? and l.debit_amount=?
                """, batchId, debitCode, new BigDecimal(amount)));
        assertEquals(1L, count("""
                select count(*) from accounting_voucher_line l join accounting_account a on a.id=l.account_id
                where l.voucher_id=(select id from accounting_voucher where business_posting_batch_id=?)
                  and a.account_code=? and l.credit_amount=?
                """, batchId, creditCode, new BigDecimal(amount)));
    }

    private BigDecimal balance(String type) {
        return jdbc.queryForObject("""
                select coalesce(max(balance),0) from partner_account where partner_id=? and account_type=?
                """, BigDecimal.class, partnerId, type);
    }

    private void assertMoney(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual.setScale(2)));
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0L : value;
    }

    private String text(String sql, Object... args) {
        return jdbc.queryForObject(sql, String.class, args);
    }

    @AfterTransaction
    void rollbackRemovesFixture() {
        if (token == null) return;
        assertEquals(0L, count("select count(*) from partner where name=?", token + " 往来单位"));
    }
}
