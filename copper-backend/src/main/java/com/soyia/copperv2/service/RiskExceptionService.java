package com.soyia.copperv2.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.soyia.copperv2.dto.PaymentAllocationDtos;
import com.soyia.copperv2.dto.RiskExceptionDtos;
import com.soyia.copperv2.dto.SupplierPaymentCreateRequest;
import com.soyia.copperv2.security.BusinessDataScopeService;
import com.soyia.copperv2.security.ForbiddenException;
import com.soyia.copperv2.security.SecurityContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class RiskExceptionService {
    public static final String APPROVE_PERMISSION = "risk.exception.approve";
    private static final String REQUEST_PERMISSION = "risk.exception.request";
    private static final DateTimeFormatter NO_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");
    private static final AtomicInteger NO_COUNTER = new AtomicInteger();
    private static final Set<String> TERMINAL_STATUSES = Set.of(
            "REJECTED", "WITHDRAWN", "EXPIRED", "CONSUMED", "INVALIDATED");

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final BusinessDataScopeService dataScopeService;

    public RiskExceptionService(JdbcTemplate jdbc, ObjectMapper objectMapper,
                                BusinessDataScopeService dataScopeService) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.dataScopeService = dataScopeService;
    }

    public RiskExceptionDtos.Policy policy() {
        PolicyRow row = policyRow(false);
        return row.toDto();
    }

    @Transactional
    public RiskExceptionDtos.Policy updatePolicy(RiskExceptionDtos.PolicySave request) {
        SecurityContext.Principal actor = requirePermission("risk.exception.policy.manage", "只有老板或系统管理员可以维护例外审批阈值");
        PolicyRow current = policyRow(true);
        BigDecimal largePayment = positiveMoney(request.largePaymentThreshold(), "大额付款阈值");
        BigDecimal inventory = positiveMoney(request.inventoryAdjustmentThreshold(), "库存调整阈值");
        BigDecimal reversal = positiveMoney(request.highRiskReversalThreshold(), "高风险冲正阈值");
        String reason = requiredReason(request.changeReason(), "策略调整原因", 4);
        int nextVersion = current.versionNo() + 1;
        jdbc.update("""
                update risk_exception_policy set large_payment_threshold=?,inventory_adjustment_threshold=?,
                  high_risk_reversal_threshold=?,approval_valid_hours=?,version_no=?,change_reason=?,
                  updated_by=?,updated_by_name=?,updated_at=now(6) where entity_id=?
                """, largePayment, inventory, reversal, request.approvalValidHours(), nextVersion, reason,
                id(actor.userId()), actorName(actor), current.entityId());
        jdbc.update("""
                insert into risk_exception_policy_version
                (entity_id,version_no,large_payment_threshold,inventory_adjustment_threshold,
                 high_risk_reversal_threshold,approval_valid_hours,change_reason,operator_id,operator_name)
                values (?,?,?,?,?,?,?,?,?)
                """, current.entityId(), nextVersion, largePayment, inventory, reversal,
                request.approvalValidHours(), reason, id(actor.userId()), actorName(actor));
        return policyRow(false).toDto();
    }

    @Transactional
    public List<RiskExceptionDtos.RequestRow> list(String status, String exceptionType, Boolean mineOnly) {
        expireOverdue();
        SecurityContext.Principal actor = SecurityContext.require();
        StringBuilder sql = new StringBuilder(requestSelect()).append(" where 1=1");
        List<Object> args = new ArrayList<>();
        if (StringUtils.hasText(status)) {
            sql.append(" and r.status=?");
            args.add(status.trim().toUpperCase());
        }
        if (StringUtils.hasText(exceptionType)) {
            sql.append(" and r.exception_type=?");
            args.add(exceptionType.trim().toUpperCase());
        }
        boolean restrictToMine = Boolean.TRUE.equals(mineOnly) || !actor.hasPermission(APPROVE_PERMISSION);
        if (restrictToMine) {
            sql.append(" and (r.requested_employee_id=? or r.assigned_employee_id=?)");
            args.add(id(actor.employeeId()));
            args.add(id(actor.employeeId()));
        }
        sql.append(" order by case r.status when 'PENDING' then 0 when 'APPROVED' then 1 else 2 end,r.expires_at,r.id desc limit 1000");
        return jdbc.query(sql.toString(), requestMapper(), args.toArray());
    }

    @Transactional
    public RiskExceptionDtos.RequestDetail detail(Long id) {
        expireOverdue();
        RequestLock row = requiredRequest(id, false);
        assertVisible(row);
        List<RiskExceptionDtos.EventRow> events = jdbc.query("""
                select id,action,from_status,to_status,reason,operator_employee_id,operator_name,created_at
                from risk_exception_event where request_id=? order by id
                """, (rs, rowNum) -> new RiskExceptionDtos.EventRow(
                rs.getLong("id"), rs.getString("action"), rs.getString("from_status"),
                rs.getString("to_status"), rs.getString("reason"), rs.getLong("operator_employee_id"),
                rs.getString("operator_name"), dateTime(rs.getTimestamp("created_at"))), id);
        return new RiskExceptionDtos.RequestDetail(row.toDto(), row.snapshotJson(), events);
    }

    @Transactional
    public RiskExceptionDtos.Dashboard dashboard() {
        expireOverdue();
        SecurityContext.Principal actor = SecurityContext.require();
        boolean approver = actor.hasPermission(APPROVE_PERMISSION);
        long employeeId = id(actor.employeeId());
        String scope = approver ? "1=1" : "(requested_employee_id=" + employeeId + " or assigned_employee_id=" + employeeId + ")";
        return new RiskExceptionDtos.Dashboard(
                count("select count(*) from risk_exception_request where status='PENDING' and " + scope),
                count("select count(*) from risk_exception_request where status='APPROVED' and " + scope),
                count("select count(*) from risk_exception_request where status in ('PENDING','APPROVED') and expires_at<=date_add(now(6),interval 24 hour) and " + scope),
                count("select count(*) from risk_exception_request where status in ('PENDING','APPROVED') and requested_employee_id=?", employeeId));
    }

    public List<RiskExceptionDtos.Approver> approvers() {
        return jdbc.query("""
                select distinct e.id,e.employee_no,e.name,d.name department_name,e.job_title
                from organization_employee e
                join organization_department d on d.id=e.department_id
                join system_user u on u.employee_id=e.id and u.status='ENABLED' and u.login_enabled=1
                join system_user_role ur on ur.user_id=u.id
                join security_role_permission rp on rp.role_id=ur.role_id
                join security_permission p on p.id=rp.permission_id and p.code='risk.exception.approve'
                where e.employment_status='ACTIVE'
                order by department_name,e.employee_no,e.id
                """, (rs, rowNum) -> new RiskExceptionDtos.Approver(
                rs.getLong("id"), rs.getString("employee_no"), rs.getString("name"),
                rs.getString("department_name"), rs.getString("job_title")));
    }

    @Transactional
    public RiskExceptionDtos.RequestRow submit(Trigger trigger, String reason) {
        requirePermission(REQUEST_PERMISSION, "当前账号无权提交例外审批");
        if (trigger == null) throw new BusinessException("当前业务动作未达到例外审批条件，无需提交审批");
        String requestReason = requiredReason(reason, "例外申请原因", 8);
        RequestLock existing = activeRequest(trigger, false);
        if (existing != null) {
            assertVisible(existing);
            return existing.toDto();
        }
        PolicyRow policy = policyRow(false);
        SecurityContext.Principal actor = SecurityContext.require();
        String snapshotJson = json(trigger.snapshot());
        String snapshotHash = sha256(snapshotJson);
        LocalDateTime expiresAt = LocalDateTime.now().plusHours(policy.approvalValidHours());
        Long requestId = insert("""
                insert into risk_exception_request
                (request_no,entity_id,policy_version,exception_type,risk_level,source_type,source_id,source_no,
                 action_code,source_version,source_amount,currency,title,request_reason,source_snapshot_json,
                 source_snapshot_hash,status,requested_by,requested_employee_id,requested_by_name,expires_at)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?, 'PENDING',?,?,?,?)
                """, nextNo(), policy.entityId(), policy.versionNo(), trigger.exceptionType(), "HIGH",
                trigger.sourceType(), trigger.sourceId(), trigger.sourceNo(), trigger.actionCode(),
                trigger.sourceVersion(), money(trigger.sourceAmount()), "CNY", trigger.title(), requestReason,
                snapshotJson, snapshotHash, id(actor.userId()), id(actor.employeeId()), actorName(actor),
                Timestamp.valueOf(expiresAt));
        event(requestId, "SUBMIT", "", "PENDING", requestReason, actor);
        return requiredRequest(requestId, false).toDto();
    }

    @Transactional
    public RiskExceptionDtos.RequestRow submitSupplierPaymentIntent(SupplierPaymentCreateRequest payment,
                                                                     String reason) {
        return submit(supplierPaymentTrigger(payment), reason);
    }

    @Transactional
    public RiskExceptionDtos.RequestRow approve(Long id, RiskExceptionDtos.Decision decision) {
        SecurityContext.Principal actor = requirePermission(APPROVE_PERMISSION, "当前账号无权批准例外申请");
        expireOverdue();
        RequestLock row = requiredRequest(id, true);
        if ("APPROVED".equals(row.status())) return row.toDto();
        if (!"PENDING".equals(row.status())) throw new BusinessException("只有待审批申请可以批准");
        assertAssignment(row, actor);
        String reason = requiredReason(decision.reason(), "批准意见", 4);
        int updated = jdbc.update("""
                update risk_exception_request set status='APPROVED',approved_by=?,approved_employee_id=?,
                  approved_by_name=?,approved_at=now(6),decision_reason=?,version_no=version_no+1
                where id=? and status='PENDING'
                """, id(actor.userId()), id(actor.employeeId()), actorName(actor), reason, id);
        if (updated != 1) throw new BusinessException("审批状态已变化，请刷新后重试");
        event(id, "APPROVE", "PENDING", "APPROVED", reason, actor);
        return requiredRequest(id, false).toDto();
    }

    @Transactional
    public RiskExceptionDtos.RequestRow reject(Long id, RiskExceptionDtos.Decision decision) {
        SecurityContext.Principal actor = requirePermission(APPROVE_PERMISSION, "当前账号无权退回例外申请");
        expireOverdue();
        RequestLock row = requiredRequest(id, true);
        if ("REJECTED".equals(row.status())) return row.toDto();
        if (!"PENDING".equals(row.status())) throw new BusinessException("只有待审批申请可以退回");
        assertAssignment(row, actor);
        String reason = requiredReason(decision.reason(), "退回原因", 4);
        jdbc.update("""
                update risk_exception_request set status='REJECTED',rejected_by=?,rejected_by_name=?,
                  rejected_at=now(6),decision_reason=?,version_no=version_no+1 where id=? and status='PENDING'
                """, id(actor.userId()), actorName(actor), reason, id);
        event(id, "REJECT", "PENDING", "REJECTED", reason, actor);
        return requiredRequest(id, false).toDto();
    }

    @Transactional
    public RiskExceptionDtos.RequestRow withdraw(Long id, RiskExceptionDtos.Decision decision) {
        SecurityContext.Principal actor = SecurityContext.require();
        expireOverdue();
        RequestLock row = requiredRequest(id, true);
        if ("WITHDRAWN".equals(row.status())) return row.toDto();
        if (!Set.of("PENDING", "APPROVED").contains(row.status()))
            throw new BusinessException("只有待审批或已批准未执行的申请可以撤回");
        if (row.requestedEmployeeId() != id(actor.employeeId()) && !actor.hasPermission(APPROVE_PERMISSION))
            throw new ForbiddenException("只有申请人或负责人可以撤回例外申请");
        String reason = requiredReason(decision.reason(), "撤回原因", 4);
        jdbc.update("""
                update risk_exception_request set status='WITHDRAWN',withdrawn_by=?,withdrawn_by_name=?,
                  withdrawn_at=now(6),decision_reason=?,version_no=version_no+1
                where id=? and status in ('PENDING','APPROVED')
                """, id(actor.userId()), actorName(actor), reason, id);
        event(id, "WITHDRAW", row.status(), "WITHDRAWN", reason, actor);
        return requiredRequest(id, false).toDto();
    }

    @Transactional
    public RiskExceptionDtos.RequestRow transfer(Long id, RiskExceptionDtos.Transfer transfer) {
        SecurityContext.Principal actor = requirePermission(APPROVE_PERMISSION, "当前账号无权转交例外申请");
        expireOverdue();
        RequestLock row = requiredRequest(id, true);
        if (!"PENDING".equals(row.status())) throw new BusinessException("只有待审批申请可以转交");
        RiskExceptionDtos.Approver target = approvers().stream()
                .filter(item -> Objects.equals(item.employeeId(), transfer.targetEmployeeId()))
                .findFirst().orElseThrow(() -> new BusinessException("转交对象没有例外审批权限或账号未启用"));
        String reason = requiredReason(transfer.reason(), "转交原因", 4);
        jdbc.update("""
                update risk_exception_request set assigned_employee_id=?,assigned_employee_name=?,
                  version_no=version_no+1 where id=? and status='PENDING'
                """, target.employeeId(), target.employeeName(), id);
        event(id, "TRANSFER", "PENDING", "PENDING",
                reason + "；转交给=" + target.employeeName(), actor);
        return requiredRequest(id, false).toDto();
    }

    @Transactional
    public Authorization authorizeOrConsume(Trigger trigger, RiskExceptionDtos.InlineAuthorization inline) {
        if (trigger == null) return new Authorization(0L, "无需例外审批", false);
        expireOverdue();
        SecurityContext.Principal actor = SecurityContext.require();
        String inlineReason = inline == null ? "" : text(inline.reason());
        boolean legacySalesApprover = Set.of("SALES_RISK", "SPECIAL_PRICE").contains(trigger.exceptionType())
                && actor.hasPermission("sales.risk.override");
        if (actor.hasPermission(APPROVE_PERMISSION) || legacySalesApprover) {
            String reason = requiredReason(inlineReason, "负责人例外授权原因", 8);
            return directAuthorization(trigger, reason, actor);
        }
        RequestLock row = inline != null && inline.approvalId() != null && inline.approvalId() > 0
                ? requiredRequest(inline.approvalId(), true) : activeRequest(trigger, true);
        if (row == null && StringUtils.hasText(inlineReason))
            throw new ForbiddenException("当前账号无权直接授权该销售风险，请先提交例外审批");
        if (row == null) throw new BusinessException("该高风险动作尚未取得负责人批准，请先提交例外审批");
        if (!"APPROVED".equals(row.status())) {
            if ("PENDING".equals(row.status())) throw new BusinessException("例外申请正在等待负责人审批");
            throw new BusinessException("例外申请当前状态不能执行，请重新提交");
        }
        assertTriggerMatches(row, trigger);
        int updated = jdbc.update("""
                update risk_exception_request set status='CONSUMED',consumed_by=?,consumed_by_name=?,
                  consumed_at=now(6),result_source_type=?,result_source_id=?,result_source_no=?,
                  version_no=version_no+1 where id=? and status='APPROVED'
                """, id(actor.userId()), actorName(actor), trigger.sourceType(), trigger.sourceId(),
                trigger.sourceNo(), row.id());
        if (updated != 1) throw new BusinessException("例外批准已被使用或状态已变化，请刷新后重试");
        event(row.id(), "CONSUME", "APPROVED", "CONSUMED", "执行经批准的业务动作", actor);
        String reason = StringUtils.hasText(row.decisionReason()) ? row.decisionReason() : row.requestReason();
        return new Authorization(row.id(), reason, false);
    }

    @Transactional
    public void bindResult(Long approvalId, String resultType, Long resultId, String resultNo) {
        if (approvalId == null || approvalId <= 0) return;
        jdbc.update("""
                update risk_exception_request set result_source_type=?,result_source_id=?,result_source_no=?
                where id=? and status='CONSUMED'
                """, text(resultType), id(resultId), text(resultNo), approvalId);
    }

    public RiskExceptionDtos.SourceStatus sourceStatus(Trigger trigger) {
        SecurityContext.Principal actor = SecurityContext.require();
        if (trigger == null) return new RiskExceptionDtos.SourceStatus(false,
                "当前金额和风险未达到例外审批条件", actor.hasPermission(APPROVE_PERMISSION), null);
        RequestLock active = activeRequest(trigger, false);
        if (active != null) assertVisible(active);
        return new RiskExceptionDtos.SourceStatus(true, trigger.requirementReason(),
                actor.hasPermission(APPROVE_PERMISSION), active == null ? null : active.toDto());
    }

    public Trigger resolveTrigger(String sourceType, Long sourceId, String actionCode) {
        String type = normalize(sourceType);
        String action = normalize(actionCode);
        if (sourceId == null || sourceId <= 0) throw new BusinessException("业务单据标识无效");
        return switch (type + ":" + action) {
            case "TREASURY_PAYMENT_REQUEST:APPROVE" -> treasuryPaymentRequestTrigger(sourceId);
            case "PARTNER_FUND_ADJUSTMENT:CONFIRM", "PARTNER_FUND_ADJUSTMENT:REVERSE" ->
                    partnerFundAdjustmentTrigger(sourceId, action);
            case "INVENTORY_STOCKTAKE:CONFIRM", "INVENTORY_STOCKTAKE:REVERSE" ->
                    stocktakeTrigger(sourceId, action);
            case "INVENTORY_COST_ADJUSTMENT:CONFIRM", "INVENTORY_COST_ADJUSTMENT:REVERSE" ->
                    costAdjustmentTrigger(sourceId, action);
            case "TREASURY_DOCUMENT:REVERSE" -> treasuryDocumentReversalTrigger(sourceId);
            case "TREASURY_FUND:REVERSE" -> treasuryFundReversalTrigger(sourceId);
            case "PAYMENT:REVERSE" -> paymentReversalTrigger(sourceId, false);
            case "SUPPLIER_PAYMENT:REVERSE" -> paymentReversalTrigger(sourceId, true);
            case "CUSTOMER_ADVANCE_REFUND:REVERSE" -> customerAdvanceRefundReversalTrigger(sourceId);
            default -> throw new BusinessException("当前业务动作尚未接入例外审批目录");
        };
    }

    public Trigger salesTrigger(String documentType, Long documentId, String documentNo, String action,
                                int sourceVersion, BigDecimal amount, Map<String, Object> snapshot,
                                List<String> issueCodes, String issueSummary) {
        if (issueCodes == null || issueCodes.isEmpty()) return null;
        String exceptionType = issueCodes.stream().allMatch(code -> code.startsWith("MANUAL_PRICE"))
                ? "SPECIAL_PRICE" : "SALES_RISK";
        return trigger(exceptionType, normalize(documentType), documentId, documentNo, normalize(action),
                sourceVersion, amount, "销售风险例外：" + documentNo, snapshot,
                StringUtils.hasText(issueSummary) ? issueSummary : "销售动作存在特价、超信用、逾期或低毛利风险");
    }

    public Trigger supplierPaymentTrigger(SupplierPaymentCreateRequest request) {
        PolicyRow policy = policyRow(false);
        BigDecimal amount = positiveMoney(request.amount(), "付款金额");
        if (amount.compareTo(policy.largePaymentThreshold()) < 0) return null;
        Map<String, Object> snapshot = supplierPaymentSnapshot(request);
        String sourceNo = "待付款-供应商" + id(request.supplierId());
        return trigger("LARGE_PAYMENT", "SUPPLIER_PAYMENT_INTENT", 0L, sourceNo, "CREATE", 1,
                amount, "大额供应商付款 " + sourceNo, snapshot,
                "付款金额达到大额付款阈值 " + policy.largePaymentThreshold().toPlainString() + " 元");
    }

    public RequestLock executableRequest(Long id) {
        expireOverdue();
        RequestLock row = requiredRequest(id, false);
        assertVisible(row);
        if (!"APPROVED".equals(row.status())) throw new BusinessException("只有已批准且未执行的例外申请可以执行业务动作");
        return row;
    }

    public SupplierPaymentCreateRequest supplierPaymentFromSnapshot(RequestLock row) {
        if (!"SUPPLIER_PAYMENT_INTENT".equals(row.sourceType())) throw new BusinessException("审批申请不是供应商付款意向");
        try {
            Map<String, Object> envelope = objectMapper.readValue(row.snapshotJson(), new TypeReference<>() {});
            Map<String, Object> snapshot = envelope.get("facts") instanceof Map<?, ?> facts
                    ? objectMapper.convertValue(facts, new TypeReference<>() {}) : envelope;
            List<PaymentAllocationDtos.AllocationInput> allocations = objectMapper.convertValue(
                    snapshot.getOrDefault("allocations", List.of()), new TypeReference<>() {});
            return new SupplierPaymentCreateRequest(
                    longValue(snapshot.get("supplierId")), nullableLong(snapshot.get("purchaseOrderId")),
                    nullableLong(snapshot.get("inboundId")), decimal(snapshot.get("amount")),
                    text(snapshot.get("paymentMethod")), nullableLong(snapshot.get("businessBankAccountId")),
                    java.time.LocalDate.parse(text(snapshot.get("paidDate"))), text(snapshot.get("memo")),
                    allocations, null, row.id());
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new BusinessException("大额付款审批快照无法还原，请重新提交审批", ex);
        }
    }

    private Trigger treasuryPaymentRequestTrigger(Long id) {
        Map<String, Object> row = requiredRow("""
                select r.id,r.request_no,r.document_id,r.requested_amount,r.paid_amount,r.request_date,
                       r.expected_payment_date,r.purpose,r.status,r.version_no,d.document_no,d.partner_id
                from treasury_payment_request r join treasury_document d on d.id=r.document_id where r.id=?
                """, id);
        dataScopeService.assertDocumentAllowed("TREASURY_DOCUMENT", longValue(row.get("document_id")));
        PolicyRow policy = policyRow(false);
        BigDecimal amount = decimal(row.get("requested_amount"));
        if (amount.compareTo(policy.largePaymentThreshold()) < 0) return null;
        return trigger("LARGE_PAYMENT", "TREASURY_PAYMENT_REQUEST", id, text(row.get("request_no")),
                "APPROVE", intValue(row.get("version_no")), amount,
                "大额付款申请 " + text(row.get("request_no")), row,
                "申请金额达到大额付款阈值 " + policy.largePaymentThreshold().toPlainString() + " 元");
    }

    private Trigger partnerFundAdjustmentTrigger(Long id, String action) {
        Map<String, Object> row = requiredRow("""
                select id,adjustment_no,partner_id,account_type,adjustment_type,direction,amount,business_date,
                       basis_no,tax_treatment,memo,status,version_no from partner_fund_adjustment where id=?
                """, id);
        dataScopeService.assertDocumentAllowed("PARTNER_FUND_ADJUSTMENT", id);
        String expected = "CONFIRM".equals(action) ? "DRAFT" : "CONFIRMED";
        if (!expected.equals(text(row.get("status")))) return null;
        String type = "CONFIRM".equals(action) ? "FUND_ADJUSTMENT" : "HIGH_RISK_REVERSAL";
        String title = ("CONFIRM".equals(action) ? "确认余额调整 " : "冲正余额调整 ") + text(row.get("adjustment_no"));
        return trigger(type, "PARTNER_FUND_ADJUSTMENT", id, text(row.get("adjustment_no")), action,
                intValue(row.get("version_no")), decimal(row.get("amount")), title, row,
                "往来余额调整会直接改变预收、预付、奖励或返利余额，必须由负责人单级授权");
    }

    private Trigger stocktakeTrigger(Long id, String action) {
        Map<String, Object> row = new LinkedHashMap<>(requiredRow("""
                select id,order_no,warehouse_id,business_date,status,total_gain_quantity,total_loss_quantity,
                       total_cost_difference,memo from inventory_stocktake_order where id=?
                """, id));
        dataScopeService.assertDocumentAllowed("INVENTORY_STOCKTAKE", id);
        row.put("items", jdbc.queryForList("""
                select id,product_id,batch_id,system_quantity,counted_quantity,difference_quantity,
                       unit_cost,system_amount_cost,counted_amount_cost,cost_difference,difference_reason
                from inventory_stocktake_item where stocktake_id=? order by id
                """, id));
        String expected = "CONFIRM".equals(action) ? "DRAFT" : "CONFIRMED";
        if (!expected.equals(text(row.get("status")))) return null;
        PolicyRow policy = policyRow(false);
        BigDecimal amount = decimal(row.get("total_cost_difference")).abs();
        boolean loss = decimal(row.get("total_loss_quantity")).signum() > 0;
        BigDecimal threshold = "CONFIRM".equals(action)
                ? policy.inventoryAdjustmentThreshold() : policy.highRiskReversalThreshold();
        if (!loss && amount.compareTo(threshold) < 0) return null;
        String type = "CONFIRM".equals(action) ? "INVENTORY_ADJUSTMENT" : "HIGH_RISK_REVERSAL";
        return trigger(type, "INVENTORY_STOCKTAKE", id, text(row.get("order_no")), action, 1, amount,
                ("CONFIRM".equals(action) ? "确认库存盘点 " : "冲正库存盘点 ") + text(row.get("order_no")), row,
                loss ? "盘点包含盘亏，确认后将减少实际结存" : "库存金额影响达到例外审批阈值 " + threshold.toPlainString() + " 元");
    }

    private Trigger costAdjustmentTrigger(Long id, String action) {
        Map<String, Object> row = new LinkedHashMap<>(requiredRow("""
                select id,adjustment_no,business_date,status,total_difference_amount,reason,memo
                from inventory_cost_adjustment where id=?
                """, id));
        dataScopeService.assertDocumentAllowed("INVENTORY_COST_ADJUSTMENT", id);
        row.put("items", jdbc.queryForList("""
                select id,product_id,warehouse_id,batch_id,quantity_snapshot,old_unit_cost,new_unit_cost,
                       old_amount_cost,new_amount_cost,difference_amount
                from inventory_cost_adjustment_item where adjustment_id=? order by id
                """, id));
        String expected = "CONFIRM".equals(action) ? "DRAFT" : "CONFIRMED";
        if (!expected.equals(text(row.get("status")))) return null;
        PolicyRow policy = policyRow(false);
        BigDecimal amount = decimal(row.get("total_difference_amount")).abs();
        BigDecimal threshold = "CONFIRM".equals(action)
                ? policy.inventoryAdjustmentThreshold() : policy.highRiskReversalThreshold();
        if (amount.compareTo(threshold) < 0) return null;
        String type = "CONFIRM".equals(action) ? "INVENTORY_ADJUSTMENT" : "HIGH_RISK_REVERSAL";
        return trigger(type, "INVENTORY_COST_ADJUSTMENT", id, text(row.get("adjustment_no")), action, 1, amount,
                ("CONFIRM".equals(action) ? "确认成本调整 " : "冲正成本调整 ") + text(row.get("adjustment_no")), row,
                "库存金额影响达到例外审批阈值 " + threshold.toPlainString() + " 元");
    }

    private Trigger treasuryDocumentReversalTrigger(Long id) {
        Map<String, Object> row = requiredRow("""
                select id,document_no,document_type,partner_id,business_date,total_amount,settled_amount,status,version_no
                from treasury_document where id=?
                """, id);
        dataScopeService.assertDocumentAllowed("TREASURY_DOCUMENT", id);
        return reversalTrigger("TREASURY_DOCUMENT", id, text(row.get("document_no")),
                intValue(row.get("version_no")), decimal(row.get("total_amount")), row);
    }

    private Trigger treasuryFundReversalTrigger(Long id) {
        Map<String, Object> row = requiredRow("""
                select id,fund_no,fund_type,document_id,payment_request_id,direction,settlement_method,
                       business_bank_account_id,counter_bank_account_id,amount,transaction_date,status
                from treasury_fund_transaction where id=?
                """, id);
        long documentId = longValue(row.get("document_id"));
        if (documentId > 0) dataScopeService.assertDocumentAllowed("TREASURY_DOCUMENT", documentId);
        else if (!dataScopeService.hasFullDocumentScope()) throw new ForbiddenException("当前数据范围无权处理内部账户转账");
        return reversalTrigger("TREASURY_FUND", id, text(row.get("fund_no")), 1,
                decimal(row.get("amount")), row);
    }

    private Trigger paymentReversalTrigger(Long id, boolean supplier) {
        String table = supplier ? "supplier_payment" : "payment";
        String partnerColumn = supplier ? "supplier_id" : "customer_id";
        String businessDateColumn = supplier ? "paid_date" : "received_date";
        Map<String, Object> row = requiredRow("select id,payment_no," + partnerColumn
                + ",amount,payment_method," + businessDateColumn
                + " as business_date,verification_status from " + table + " where id=?", id);
        dataScopeService.assertDocumentAllowed(supplier ? "SUPPLIER_PAYMENT" : "PAYMENT", id);
        return reversalTrigger(supplier ? "SUPPLIER_PAYMENT" : "PAYMENT", id,
                text(row.get("payment_no")), 1, decimal(row.get("amount")), row);
    }

    private Trigger customerAdvanceRefundReversalTrigger(Long id) {
        Map<String, Object> row = requiredRow("""
                select id,refund_no,customer_id,amount,refund_method,business_bank_account_id,refunded_date,status
                from customer_advance_refund where id=?
                """, id);
        dataScopeService.assertDocumentAllowed("CUSTOMER_ADVANCE_REFUND", id);
        return reversalTrigger("CUSTOMER_ADVANCE_REFUND", id, text(row.get("refund_no")), 1,
                decimal(row.get("amount")), row);
    }

    private Trigger reversalTrigger(String sourceType, Long sourceId, String sourceNo, int sourceVersion,
                                    BigDecimal amount, Map<String, Object> snapshot) {
        PolicyRow policy = policyRow(false);
        BigDecimal normalized = money(amount).abs();
        if (normalized.compareTo(policy.highRiskReversalThreshold()) < 0) return null;
        return trigger("HIGH_RISK_REVERSAL", sourceType, sourceId, sourceNo, "REVERSE", sourceVersion,
                normalized, "高风险冲正 " + sourceNo, snapshot,
                "冲正金额达到高风险阈值 " + policy.highRiskReversalThreshold().toPlainString() + " 元");
    }

    private Authorization directAuthorization(Trigger trigger, String reason, SecurityContext.Principal actor) {
        RequestLock existing = activeRequest(trigger, true);
        if (existing != null) {
            if ("PENDING".equals(existing.status())) {
                int updated = jdbc.update("""
                        update risk_exception_request set status='CONSUMED',approved_by=?,approved_employee_id=?,
                          approved_by_name=?,approved_at=now(6),decision_reason=?,consumed_by=?,consumed_by_name=?,
                          consumed_at=now(6),result_source_type=?,result_source_id=?,result_source_no=?,
                          version_no=version_no+1 where id=? and status='PENDING'
                        """, id(actor.userId()), id(actor.employeeId()), actorName(actor), reason,
                        id(actor.userId()), actorName(actor), trigger.sourceType(), trigger.sourceId(),
                        trigger.sourceNo(), existing.id());
                if (updated != 1) throw new BusinessException("例外审批状态已变化，请刷新后重试");
                event(existing.id(), "DIRECT_APPROVE", "PENDING", "APPROVED", reason, actor);
                event(existing.id(), "CONSUME", "APPROVED", "CONSUMED", "负责人在原业务动作中批准并执行", actor);
                return new Authorization(existing.id(), reason, true);
            }
            if ("APPROVED".equals(existing.status())) {
                int updated = jdbc.update("""
                        update risk_exception_request set status='CONSUMED',consumed_by=?,consumed_by_name=?,
                          consumed_at=now(6),result_source_type=?,result_source_id=?,result_source_no=?,
                          version_no=version_no+1 where id=? and status='APPROVED'
                        """, id(actor.userId()), actorName(actor), trigger.sourceType(), trigger.sourceId(),
                        trigger.sourceNo(), existing.id());
                if (updated != 1) throw new BusinessException("例外审批状态已变化，请刷新后重试");
                event(existing.id(), "CONSUME", "APPROVED", "CONSUMED", reason, actor);
                return new Authorization(existing.id(), reason, true);
            }
        }
        PolicyRow policy = policyRow(false);
        String snapshotJson = json(trigger.snapshot());
        String snapshotHash = sha256(snapshotJson);
        Long requestId = insert("""
                insert into risk_exception_request
                (request_no,entity_id,policy_version,exception_type,risk_level,source_type,source_id,source_no,
                 action_code,source_version,source_amount,currency,title,request_reason,source_snapshot_json,
                 source_snapshot_hash,status,requested_by,requested_employee_id,requested_by_name,expires_at,
                 approved_by,approved_employee_id,approved_by_name,approved_at,decision_reason,
                 consumed_by,consumed_by_name,consumed_at,result_source_type,result_source_id,result_source_no)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?, 'CONSUMED',
                        ?,?,?,?, ?,?,?,now(6),?, ?,?,now(6),?,?,?)
                """, nextNo(), policy.entityId(), policy.versionNo(), trigger.exceptionType(), "HIGH",
                trigger.sourceType(), trigger.sourceId(), trigger.sourceNo(), trigger.actionCode(),
                trigger.sourceVersion(), money(trigger.sourceAmount()), "CNY", trigger.title(), reason,
                snapshotJson, snapshotHash, id(actor.userId()), id(actor.employeeId()), actorName(actor),
                Timestamp.valueOf(LocalDateTime.now().plusHours(policy.approvalValidHours())),
                id(actor.userId()), id(actor.employeeId()), actorName(actor), reason,
                id(actor.userId()), actorName(actor), trigger.sourceType(), trigger.sourceId(), trigger.sourceNo());
        event(requestId, "DIRECT_APPROVE", "", "APPROVED", reason, actor);
        event(requestId, "CONSUME", "APPROVED", "CONSUMED", "负责人在原业务动作中一次授权并执行", actor);
        return new Authorization(requestId, reason, true);
    }

    private RequestLock activeRequest(Trigger trigger, boolean lock) {
        String hash = sha256(json(trigger.snapshot()));
        List<RequestLock> rows = jdbc.query(requestSelect() + " where r.entity_id=? and r.source_type=? and r.source_id=?"
                        + " and r.action_code=? and r.source_snapshot_hash=? and r.status in ('PENDING','APPROVED')"
                        + " order by r.id desc limit 1" + (lock ? " for update" : ""), requestLockMapper(),
                policyRow(false).entityId(), trigger.sourceType(), trigger.sourceId(), trigger.actionCode(), hash);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void assertTriggerMatches(RequestLock row, Trigger trigger) {
        String hash = sha256(json(trigger.snapshot()));
        if (!Objects.equals(row.sourceType(), trigger.sourceType())
                || row.sourceId() != id(trigger.sourceId())
                || !Objects.equals(row.actionCode(), trigger.actionCode())
                || !Objects.equals(row.snapshotHash(), hash)) {
            throw new BusinessException("业务单据版本、金额或风险已经变化，原批准不能继续使用，请重新提交审批");
        }
    }

    private Map<String, Object> supplierPaymentSnapshot(SupplierPaymentCreateRequest request) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("supplierId", id(request.supplierId()));
        snapshot.put("purchaseOrderId", id(request.purchaseOrderId()));
        snapshot.put("inboundId", id(request.inboundId()));
        snapshot.put("amount", money(request.amount()));
        snapshot.put("paymentMethod", normalize(request.paymentMethod()));
        snapshot.put("businessBankAccountId", id(request.businessBankAccountId()));
        snapshot.put("paidDate", request.paidDate());
        snapshot.put("memo", text(request.memo()));
        List<Map<String, Object>> allocations = new ArrayList<>();
        if (request.allocations() != null) {
            request.allocations().stream()
                    .sorted(Comparator.comparing(PaymentAllocationDtos.AllocationInput::targetType)
                            .thenComparing(PaymentAllocationDtos.AllocationInput::targetId))
                    .forEach(item -> allocations.add(new LinkedHashMap<>(Map.of(
                            "targetType", normalize(item.targetType()),
                            "targetId", id(item.targetId()),
                            "amount", money(item.amount())))));
        }
        snapshot.put("allocations", allocations);
        return snapshot;
    }

    private Trigger trigger(String exceptionType, String sourceType, Long sourceId, String sourceNo,
                            String actionCode, int sourceVersion, BigDecimal sourceAmount, String title,
                            Map<String, Object> snapshot, String requirementReason) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("exceptionType", exceptionType);
        canonical.put("sourceType", sourceType);
        canonical.put("sourceId", id(sourceId));
        canonical.put("sourceNo", text(sourceNo));
        canonical.put("actionCode", actionCode);
        canonical.put("sourceVersion", sourceVersion);
        canonical.put("sourceAmount", money(sourceAmount));
        canonical.put("facts", snapshot);
        return new Trigger(exceptionType, sourceType, id(sourceId), text(sourceNo), actionCode,
                sourceVersion, money(sourceAmount), title, canonical, requirementReason);
    }

    private PolicyRow policyRow(boolean lock) {
        List<Long> entities = jdbc.queryForList("""
                select id from accounting_entity where status='ENABLED' order by id limit 1
                """, Long.class);
        if (entities.isEmpty()) throw new BusinessException("当前没有启用的会计主体，不能使用例外审批");
        long entityId = entities.get(0);
        List<PolicyRow> rows = queryPolicy(entityId, lock);
        if (rows.isEmpty()) {
            jdbc.update("""
                    insert ignore into risk_exception_policy
                    (entity_id,large_payment_threshold,inventory_adjustment_threshold,
                     high_risk_reversal_threshold,approval_valid_hours,version_no,change_reason)
                    values (?,50000.00,5000.00,50000.00,72,1,
                            '新会计主体默认轻量策略；负责人单级授权，普通业务不增加审批层级')
                    """, entityId);
            jdbc.update("""
                    insert ignore into risk_exception_policy_version
                    (entity_id,version_no,large_payment_threshold,inventory_adjustment_threshold,
                     high_risk_reversal_threshold,approval_valid_hours,change_reason)
                    select entity_id,version_no,large_payment_threshold,inventory_adjustment_threshold,
                           high_risk_reversal_threshold,approval_valid_hours,change_reason
                    from risk_exception_policy where entity_id=?
                    """, entityId);
            rows = queryPolicy(entityId, lock);
        }
        if (rows.isEmpty()) throw new BusinessException("例外审批策略尚未初始化");
        return rows.get(0);
    }

    private List<PolicyRow> queryPolicy(long entityId, boolean lock) {
        return jdbc.query("""
                select entity_id,large_payment_threshold,inventory_adjustment_threshold,
                       high_risk_reversal_threshold,approval_valid_hours,version_no,change_reason,
                       updated_by_name,updated_at from risk_exception_policy
                where entity_id=?
                """ + (lock ? " for update" : ""), (rs, rowNum) -> new PolicyRow(
                rs.getLong("entity_id"), rs.getBigDecimal("large_payment_threshold"),
                rs.getBigDecimal("inventory_adjustment_threshold"),
                rs.getBigDecimal("high_risk_reversal_threshold"), rs.getInt("approval_valid_hours"),
                rs.getInt("version_no"), rs.getString("change_reason"), rs.getString("updated_by_name"),
                dateTime(rs.getTimestamp("updated_at"))), entityId);
    }

    private RequestLock requiredRequest(Long id, boolean lock) {
        List<RequestLock> rows = jdbc.query(requestSelect() + " where r.id=?" + (lock ? " for update" : ""),
                requestLockMapper(), id);
        if (rows.isEmpty()) throw new BusinessException("例外审批申请不存在");
        return rows.get(0);
    }

    private void assertVisible(RequestLock row) {
        SecurityContext.Principal actor = SecurityContext.require();
        if (actor.hasPermission(APPROVE_PERMISSION)) return;
        long employeeId = id(actor.employeeId());
        if (row.requestedEmployeeId() != employeeId && row.assignedEmployeeId() != employeeId)
            throw new ForbiddenException("当前账号无权查看该例外审批申请");
    }

    private void assertAssignment(RequestLock row, SecurityContext.Principal actor) {
        if (row.assignedEmployeeId() > 0 && row.assignedEmployeeId() != id(actor.employeeId())
                && !actor.hasPermission("risk.exception.policy.manage")) {
            throw new ForbiddenException("该申请已经转交给" + row.assignedEmployeeName() + "处理");
        }
    }

    private void expireOverdue() {
        List<Long> ids = jdbc.queryForList("""
                select id from risk_exception_request
                where status in ('PENDING','APPROVED') and expires_at<=now(6) for update
                """, Long.class);
        for (Long id : ids) {
            String before = jdbc.queryForObject("select status from risk_exception_request where id=?", String.class, id);
            if (before == null || TERMINAL_STATUSES.contains(before)) continue;
            jdbc.update("update risk_exception_request set status='EXPIRED',version_no=version_no+1 where id=?", id);
            event(id, "EXPIRE", before, "EXPIRED", "审批有效期届满，系统自动失效", null);
        }
    }

    private void event(Long requestId, String action, String from, String to, String reason,
                       SecurityContext.Principal actor) {
        jdbc.update("""
                insert into risk_exception_event
                (request_id,action,from_status,to_status,reason,operator_id,operator_employee_id,operator_name)
                values (?,?,?,?,?,?,?,?)
                """, requestId, action, text(from), text(to), text(reason),
                actor == null ? 0L : id(actor.userId()), actor == null ? 0L : id(actor.employeeId()),
                actor == null ? "系统超时处理" : actorName(actor));
    }

    private SecurityContext.Principal requirePermission(String permission, String message) {
        SecurityContext.Principal actor = SecurityContext.require();
        if (!actor.hasPermission(permission)) throw new ForbiddenException(message);
        return actor;
    }

    private String requestSelect() {
        return """
                select r.id,r.request_no,r.policy_version,r.exception_type,r.risk_level,r.source_type,
                       r.source_id,r.source_no,r.action_code,r.source_version,r.source_amount,r.currency,
                       r.title,r.request_reason,r.source_snapshot_json,r.source_snapshot_hash,r.status,
                       r.assigned_employee_id,r.assigned_employee_name,r.requested_by,r.requested_employee_id,
                       r.requested_by_name,r.requested_at,r.expires_at,r.approved_by,r.approved_employee_id,
                       r.approved_by_name,r.approved_at,r.decision_reason,r.rejected_by_name,r.rejected_at,
                       r.withdrawn_by_name,r.withdrawn_at,r.consumed_by_name,r.consumed_at,r.result_source_type,
                       r.result_source_id,r.result_source_no,r.version_no,r.created_at,r.updated_at
                from risk_exception_request r
                """;
    }

    private org.springframework.jdbc.core.RowMapper<RiskExceptionDtos.RequestRow> requestMapper() {
        return (rs, rowNum) -> requestLock(rs).toDto();
    }

    private org.springframework.jdbc.core.RowMapper<RequestLock> requestLockMapper() {
        return (rs, rowNum) -> requestLock(rs);
    }

    private RequestLock requestLock(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new RequestLock(rs.getLong("id"), rs.getString("request_no"), rs.getInt("policy_version"),
                rs.getString("exception_type"), rs.getString("risk_level"), rs.getString("source_type"),
                rs.getLong("source_id"), rs.getString("source_no"), rs.getString("action_code"),
                rs.getInt("source_version"), rs.getBigDecimal("source_amount"), rs.getString("currency"),
                rs.getString("title"), rs.getString("request_reason"), rs.getString("source_snapshot_json"),
                rs.getString("source_snapshot_hash"), rs.getString("status"),
                rs.getLong("assigned_employee_id"), rs.getString("assigned_employee_name"),
                rs.getLong("requested_by"), rs.getLong("requested_employee_id"),
                rs.getString("requested_by_name"), dateTime(rs.getTimestamp("requested_at")),
                dateTime(rs.getTimestamp("expires_at")), rs.getLong("approved_by"),
                rs.getLong("approved_employee_id"), rs.getString("approved_by_name"),
                dateTime(rs.getTimestamp("approved_at")), rs.getString("decision_reason"),
                rs.getString("rejected_by_name"), dateTime(rs.getTimestamp("rejected_at")),
                rs.getString("withdrawn_by_name"), dateTime(rs.getTimestamp("withdrawn_at")),
                rs.getString("consumed_by_name"), dateTime(rs.getTimestamp("consumed_at")),
                rs.getString("result_source_type"), rs.getLong("result_source_id"),
                rs.getString("result_source_no"), rs.getInt("version_no"),
                dateTime(rs.getTimestamp("created_at")), dateTime(rs.getTimestamp("updated_at")));
    }

    private Long insert(String sql, Object... args) {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
            return statement;
        }, holder);
        if (holder.getKey() == null) throw new BusinessException("例外审批申请保存失败");
        return holder.getKey().longValue();
    }

    private Map<String, Object> requiredRow(String sql, Object... args) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, args);
        if (rows.isEmpty()) throw new BusinessException("业务单据不存在");
        return rows.get(0);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new BusinessException("例外审批快照生成失败");
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new BusinessException("例外审批快照摘要生成失败");
        }
    }

    private String nextNo() {
        return "SP" + LocalDateTime.now().format(NO_TIME)
                + String.format("%03d", Math.floorMod(NO_COUNTER.incrementAndGet(), 1000));
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0L : value;
    }

    private BigDecimal positiveMoney(BigDecimal value, String label) {
        BigDecimal result = money(value);
        if (result.signum() <= 0) throw new BusinessException(label + "必须大于0");
        return result;
    }

    private BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal decimal(Object value) {
        if (value instanceof BigDecimal decimal) return money(decimal);
        if (value instanceof Number number) return money(BigDecimal.valueOf(number.doubleValue()));
        if (value == null || text(value).isEmpty()) return BigDecimal.ZERO.setScale(2);
        return money(new BigDecimal(text(value)));
    }

    private int intValue(Object value) { return value instanceof Number number ? number.intValue() : 0; }
    private long longValue(Object value) { return value instanceof Number number ? number.longValue() : Long.parseLong(text(value)); }
    private Long nullableLong(Object value) { long result = value == null ? 0L : longValue(value); return result <= 0 ? null : result; }
    private long id(Long value) { return value == null ? 0L : value; }
    private String normalize(String value) { return text(value).toUpperCase(); }
    private String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    private LocalDateTime dateTime(Timestamp value) { return value == null ? null : value.toLocalDateTime(); }
    private String actorName(SecurityContext.Principal actor) {
        return StringUtils.hasText(actor.employeeName()) ? actor.employeeName() : actor.username();
    }
    private String requiredReason(String value, String label, int min) {
        String reason = text(value);
        if (reason.length() < min) throw new BusinessException(label + "至少填写" + min + "个字");
        if (reason.length() > 500) throw new BusinessException(label + "不能超过500字");
        return reason;
    }

    public record Trigger(
            String exceptionType,
            String sourceType,
            Long sourceId,
            String sourceNo,
            String actionCode,
            Integer sourceVersion,
            BigDecimal sourceAmount,
            String title,
            Map<String, Object> snapshot,
            String requirementReason
    ) {}

    public record Authorization(Long requestId, String reason, boolean direct) {}

    private record PolicyRow(
            long entityId,
            BigDecimal largePaymentThreshold,
            BigDecimal inventoryAdjustmentThreshold,
            BigDecimal highRiskReversalThreshold,
            int approvalValidHours,
            int versionNo,
            String changeReason,
            String updatedByName,
            LocalDateTime updatedAt
    ) {
        RiskExceptionDtos.Policy toDto() {
            return new RiskExceptionDtos.Policy(entityId, largePaymentThreshold, inventoryAdjustmentThreshold,
                    highRiskReversalThreshold, approvalValidHours, versionNo, changeReason,
                    updatedByName, updatedAt);
        }
    }

    public record RequestLock(
            long id,
            String requestNo,
            int policyVersion,
            String exceptionType,
            String riskLevel,
            String sourceType,
            long sourceId,
            String sourceNo,
            String actionCode,
            int sourceVersion,
            BigDecimal sourceAmount,
            String currency,
            String title,
            String requestReason,
            String snapshotJson,
            String snapshotHash,
            String status,
            long assignedEmployeeId,
            String assignedEmployeeName,
            long requestedBy,
            long requestedEmployeeId,
            String requestedByName,
            LocalDateTime requestedAt,
            LocalDateTime expiresAt,
            long approvedBy,
            long approvedEmployeeId,
            String approvedByName,
            LocalDateTime approvedAt,
            String decisionReason,
            String rejectedByName,
            LocalDateTime rejectedAt,
            String withdrawnByName,
            LocalDateTime withdrawnAt,
            String consumedByName,
            LocalDateTime consumedAt,
            String resultSourceType,
            long resultSourceId,
            String resultSourceNo,
            int versionNo,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
        RiskExceptionDtos.RequestRow toDto() {
            return new RiskExceptionDtos.RequestRow(id, requestNo, policyVersion, exceptionType, riskLevel,
                    sourceType, sourceId, sourceNo, actionCode, sourceVersion, sourceAmount, currency, title,
                    requestReason, snapshotHash, status, assignedEmployeeId, assignedEmployeeName,
                    requestedEmployeeId, requestedByName, requestedAt, expiresAt, approvedEmployeeId,
                    approvedByName, approvedAt, decisionReason, rejectedByName, rejectedAt, withdrawnByName,
                    withdrawnAt, consumedByName, consumedAt, resultSourceType, resultSourceId, resultSourceNo,
                    versionNo, createdAt, updatedAt);
        }
    }
}
