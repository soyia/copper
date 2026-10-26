package com.soyia.copperv2.service;

import com.soyia.copperv2.dto.RiskExceptionDtos;
import com.soyia.copperv2.dto.SupplierPaymentCreateRequest;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIfEnvironmentVariable(named = "COPPER_ERP15_E2E", matches = "true")
@Transactional
class Erp15RiskExceptionMySqlAcceptanceTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private RiskExceptionService service;
    @Autowired private RiskExceptionExecutionService executionService;

    private String token;
    private Long supplierId;

    @BeforeEach
    void setUp() {
        token = "ERP15" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        SecurityContext.set(owner());
        createSupplier();
    }

    @AfterEach
    void clearContext() {
        SecurityContext.clear();
    }

    @Test
    @DisplayName("负责人直接授权、申请审批、快照防篡改和批准一次性消耗形成完整闭环")
    void lifecycleIsSingleStageImmutableAndConsumableOnce() {
        RiskExceptionService.Trigger direct = trigger(101L, "DIRECT", "100.00");
        RiskExceptionService.Authorization directAuthorization = service.authorizeOrConsume(
                direct, new RiskExceptionDtos.InlineAuthorization("老板已核实原始业务依据并同意本次例外", null));
        assertTrue(directAuthorization.direct());
        assertEquals("CONSUMED", text("select status from risk_exception_request where id=?", directAuthorization.requestId()));
        assertEquals(2L, count("select count(*) from risk_exception_event where request_id=?", directAuthorization.requestId()));

        SecurityContext.set(requester());
        RiskExceptionService.Trigger submittedTrigger = trigger(102L, "SUBMITTED", "60000.00");
        RiskExceptionDtos.RequestRow submitted = service.submit(submittedTrigger, "客户现场急需且已提供完整书面业务依据");
        assertEquals("PENDING", submitted.status());
        assertEquals(submitted.id(), service.submit(submittedTrigger, "重复提交应返回原申请而不新增记录").id());

        SecurityContext.set(owner());
        RiskExceptionDtos.RequestRow approved = service.approve(submitted.id(),
                new RiskExceptionDtos.Decision("已复核合同、金额和风险承担，同意本次执行"));
        assertEquals("APPROVED", approved.status());

        SecurityContext.set(requester());
        RiskExceptionService.Trigger changed = trigger(102L, "SUBMITTED", "60001.00");
        BusinessException changedError = assertThrows(BusinessException.class, () -> service.authorizeOrConsume(
                changed, new RiskExceptionDtos.InlineAuthorization(null, submitted.id())));
        assertTrue(changedError.getMessage().contains("已经变化"));
        assertEquals("APPROVED", text("select status from risk_exception_request where id=?", submitted.id()));

        RiskExceptionService.Authorization consumed = service.authorizeOrConsume(
                submittedTrigger, new RiskExceptionDtos.InlineAuthorization(null, submitted.id()));
        assertEquals(submitted.id(), consumed.requestId());
        assertEquals("CONSUMED", text("select status from risk_exception_request where id=?", submitted.id()));
        assertThrows(BusinessException.class, () -> service.authorizeOrConsume(
                submittedTrigger, new RiskExceptionDtos.InlineAuthorization(null, submitted.id())));
    }

    @Test
    @DisplayName("大额供应商付款只冻结付款意向，批准后再校验原权限并建立真实付款")
    void largeSupplierPaymentIntentExecutesAfterApproval() {
        SupplierPaymentCreateRequest payment = new SupplierPaymentCreateRequest(
                supplierId, null, null, new BigDecimal("60000.00"), "CASH", null,
                LocalDate.now(), token + " 大额付款意向", List.of(), null, null);

        SecurityContext.set(requester());
        RiskExceptionDtos.RequestRow request = service.submitSupplierPaymentIntent(payment,
                "采购合同与收货依据齐全，申请支付本次大额货款");
        assertEquals("PENDING", request.status());
        assertEquals(0L, count("select count(*) from supplier_payment where memo=?", token + " 大额付款意向"));

        SecurityContext.set(owner());
        service.approve(request.id(), new RiskExceptionDtos.Decision("已核对供应商、金额、日期与付款依据，同意付款"));

        SecurityContext.set(requester());
        Long paymentId = executionService.execute(request.id());
        assertNotNull(paymentId);
        assertEquals(new BigDecimal("60000.00"), decimal("select amount from supplier_payment where id=?", paymentId));
        assertEquals("CONSUMED", text("select status from risk_exception_request where id=?", request.id()));
        assertEquals(paymentId, number("select result_source_id from risk_exception_request where id=?", request.id()));
        assertTrue(count("select count(*) from business_posting_batch where source_type='SUPPLIER_PAYMENT' and source_id=?", paymentId) >= 1);
    }

    @Test
    @DisplayName("撤回、退回、转交、超时和阈值版本均保留不可变审计轨迹")
    void exceptionalLifecycleAndPolicyVersionsAreAuditable() {
        SecurityContext.set(requester());
        RiskExceptionDtos.RequestRow withdrawn = service.submit(trigger(201L, "WITHDRAW", "50001.00"),
                "申请人发现原业务依据需要修订，先提交后撤回");
        assertEquals("WITHDRAWN", service.withdraw(withdrawn.id(),
                new RiskExceptionDtos.Decision("原业务依据需要修订后重新申请")).status());

        RiskExceptionDtos.RequestRow rejected = service.submit(trigger(202L, "REJECT", "50002.00"),
                "当前业务情况特殊，提交负责人复核处理");
        SecurityContext.set(owner());
        assertEquals("REJECTED", service.reject(rejected.id(),
                new RiskExceptionDtos.Decision("缺少足够的原始业务依据，暂不批准")).status());

        SecurityContext.set(requester());
        RiskExceptionDtos.RequestRow transferred = service.submit(trigger(203L, "TRANSFER", "50003.00"),
                "已准备完整原始业务依据，提交负责人审核");
        SecurityContext.set(owner());
        RiskExceptionDtos.RequestRow assigned = service.transfer(transferred.id(),
                new RiskExceptionDtos.Transfer(1L, "转交系统管理员代为复核本次风险事项"));
        assertEquals(1L, assigned.assignedEmployeeId());
        assertEquals("PENDING", assigned.status());

        SecurityContext.set(requester());
        RiskExceptionDtos.RequestRow expired = service.submit(trigger(204L, "EXPIRE", "50004.00"),
                "用于验证批准有效期届满后不得继续执行业务");
        jdbc.update("update risk_exception_request set expires_at=date_sub(now(6),interval 1 minute) where id=?", expired.id());
        assertEquals("EXPIRED", service.detail(expired.id()).header().status());
        assertEquals(1L, count("select count(*) from risk_exception_event where request_id=? and action='EXPIRE'", expired.id()));

        SecurityContext.set(owner());
        RiskExceptionDtos.Policy before = service.policy();
        RiskExceptionDtos.Policy after = service.updatePolicy(new RiskExceptionDtos.PolicySave(
                new BigDecimal("52000.00"), new BigDecimal("5200.00"),
                new BigDecimal("53000.00"), 48, "依据企业最新风险承受能力调整阈值"));
        assertEquals(before.versionNo() + 1, after.versionNo());
        assertEquals(1L, count("select count(*) from risk_exception_policy_version where entity_id=? and version_no=?",
                after.entityId(), after.versionNo()));
        assertTrue(count("select count(*) from risk_exception_event where request_id in (?,?,?)",
                withdrawn.id(), rejected.id(), transferred.id()) >= 6);
    }

    @Test
    @DisplayName("负责人在原业务页面授权时复用既有待审批申请，不遗留悬空待办")
    void ownerInlineAuthorizationConsumesExistingRequest() {
        RiskExceptionService.Trigger source = trigger(301L, "EXISTING", "51000.00");
        SecurityContext.set(requester());
        RiskExceptionDtos.RequestRow pending = service.submit(source, "业务依据已经准备齐全，请负责人直接复核");

        SecurityContext.set(owner());
        RiskExceptionService.Authorization authorization = service.authorizeOrConsume(source,
                new RiskExceptionDtos.InlineAuthorization("负责人已在原业务页面复核并同意立即执行", null));
        assertEquals(pending.id(), authorization.requestId());
        assertEquals("CONSUMED", text("select status from risk_exception_request where id=?", pending.id()));
        assertEquals(0L, count("select count(*) from risk_exception_request where source_id=? and status='PENDING'", 301L));
    }

    private void createSupplier() {
        String businessNo = String.valueOf(800000 + Math.floorMod(token.hashCode(), 99999));
        jdbc.update("insert into partner(business_no,type,type_mask,name,status,memo) values (?,'SUPPLIER',2,?,'ENABLED',?)",
                businessNo, token + " 供应商", "INTERNAL_REHEARSAL_ONLY " + token);
        supplierId = number("select id from partner where name=?", token + " 供应商");
        jdbc.update("insert into partner_supplier_profile(partner_id,status) values (?,'ENABLED')", supplierId);
    }

    private RiskExceptionService.Trigger trigger(long sourceId, String sourceNo, String amount) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("sourceNo", sourceNo);
        snapshot.put("amount", new BigDecimal(amount));
        snapshot.put("version", 1);
        return new RiskExceptionService.Trigger("HIGH_RISK_REVERSAL", "ERP15_ACCEPTANCE", sourceId,
                sourceNo, "REVERSE", 1, new BigDecimal(amount), "验收例外 " + sourceNo, snapshot,
                "达到高风险冲正阈值");
    }

    private SecurityContext.Principal owner() {
        return new SecurityContext.Principal(901L, 901L, "ERP-15老板", "ERP15-OWNER", 1L, "本企业", token + "-owner",
                false, List.of("老板"), Set.of("risk.exception.view", "risk.exception.request",
                "risk.exception.approve", "risk.exception.policy.manage", "finance.manage"));
    }

    private SecurityContext.Principal requester() {
        return new SecurityContext.Principal(902L, 902L, "ERP-15财务", "ERP15-FINANCE", 1L, "财务", token + "-finance",
                false, List.of("财务"), Set.of("risk.exception.view", "risk.exception.request", "finance.manage"));
    }

    private Long number(String sql, Object... args) { return Objects.requireNonNull(jdbc.queryForObject(sql, Long.class, args)); }
    private String text(String sql, Object... args) { return Objects.requireNonNull(jdbc.queryForObject(sql, String.class, args)); }
    private BigDecimal decimal(String sql, Object... args) { return Objects.requireNonNull(jdbc.queryForObject(sql, BigDecimal.class, args)).setScale(2); }
    private long count(String sql, Object... args) { Long value = jdbc.queryForObject(sql, Long.class, args); return value == null ? 0L : value; }

    @AfterTransaction
    void rollbackHasNoAcceptanceResidue() {
        assertEquals(0L, count("select count(*) from partner where memo like ?", "%" + token + "%"));
        assertEquals(0L, count("select count(*) from risk_exception_request where requested_by_name like 'ERP-15%'"));
    }
}
