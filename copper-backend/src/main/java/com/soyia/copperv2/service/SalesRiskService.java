package com.soyia.copperv2.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.soyia.copperv2.dto.RiskExceptionDtos;
import com.soyia.copperv2.dto.SalesRiskDtos;
import com.soyia.copperv2.security.BusinessDataScopeService;
import com.soyia.copperv2.security.ForbiddenException;
import com.soyia.copperv2.security.SecurityContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class SalesRiskService {
    private static final BigDecimal ZERO = new BigDecimal("0.00");
    private static final String SALES_ORDER = "SALES_ORDER";
    private static final String OUTBOUND = "OUTBOUND";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final BusinessDataScopeService dataScopeService;
    private final RiskExceptionService riskExceptionService;

    public SalesRiskService(JdbcTemplate jdbc, ObjectMapper objectMapper,
                            BusinessDataScopeService dataScopeService,
                            RiskExceptionService riskExceptionService) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.dataScopeService = dataScopeService;
        this.riskExceptionService = riskExceptionService;
    }

    public SalesRiskDtos.Policy policy(Long partnerId) {
        dataScopeService.assertMasterAllowed("PARTNER", partnerId);
        return policyRow(partnerId, false).toDto();
    }

    @Transactional
    public SalesRiskDtos.Policy updatePolicy(Long partnerId, SalesRiskDtos.PolicySave request) {
        SecurityContext.Principal actor = SecurityContext.require();
        if (!actor.hasPermission("sales.risk.policy.manage")) {
            throw new ForbiddenException("只有老板或系统管理员可以维护客户销售风控策略");
        }
        dataScopeService.assertMasterAllowed("PARTNER", partnerId);
        // Credit fields live on the partner/profile. Acquire their exclusive master lock
        // before the shared current-read policy query can be followed by an update.
        PartnerBusinessGuard.lockMaster(jdbc, partnerId);
        PolicyRow before = policyRow(partnerId, true);
        String reason = requiredReason(request.changeReason(), "风控策略调整");
        if (reason.length() < 4) throw new BusinessException("风控策略调整原因至少填写4个字");
        BigDecimal creditLimit = money(request.creditLimit(), "授信额度");
        BigDecimal minimumMarginRate = rate(request.minimumMarginRate(), "最低毛利率");
        int maxOverdueDays = request.maxOverdueDays();
        if (Boolean.TRUE.equals(request.enabled())
                && !Boolean.TRUE.equals(request.creditControlEnabled())
                && !Boolean.TRUE.equals(request.overdueControlEnabled())
                && !Boolean.TRUE.equals(request.marginControlEnabled())) {
            throw new BusinessException("启用销售风控时至少选择信用额度、逾期或最低毛利中的一项");
        }
        int nextVersion = before.versionNo() + 1;
        jdbc.update("""
                insert into sales_risk_policy
                (partner_id,enabled,credit_control_enabled,overdue_control_enabled,max_overdue_days,
                 margin_control_enabled,minimum_margin_rate,version_no,change_reason,updated_by,updated_by_name)
                values (?,?,?,?,?,?,?,?,?,?,?)
                on duplicate key update enabled=values(enabled),credit_control_enabled=values(credit_control_enabled),
                  overdue_control_enabled=values(overdue_control_enabled),max_overdue_days=values(max_overdue_days),
                  margin_control_enabled=values(margin_control_enabled),minimum_margin_rate=values(minimum_margin_rate),
                  version_no=values(version_no),change_reason=values(change_reason),updated_by=values(updated_by),
                  updated_by_name=values(updated_by_name),updated_at=now(6)
                """, partnerId, bool(request.enabled()), bool(request.creditControlEnabled()),
                bool(request.overdueControlEnabled()), maxOverdueDays, bool(request.marginControlEnabled()),
                minimumMarginRate, nextVersion, reason, actor.userId(), actor.employeeName());
        jdbc.update("update partner_customer_profile set credit_limit=? where partner_id=?", creditLimit, partnerId);
        jdbc.update("update partner set credit_limit=? where id=?", creditLimit, partnerId);
        jdbc.update("""
                insert into sales_risk_policy_version
                (partner_id,version_no,enabled,credit_control_enabled,credit_limit_snapshot,
                 overdue_control_enabled,max_overdue_days,margin_control_enabled,minimum_margin_rate,
                 change_reason,operator_id,operator_name)
                values (?,?,?,?,?,?,?,?,?,?,?,?)
                """, partnerId, nextVersion, bool(request.enabled()), bool(request.creditControlEnabled()),
                creditLimit, bool(request.overdueControlEnabled()), maxOverdueDays,
                bool(request.marginControlEnabled()), minimumMarginRate, reason,
                actor.userId(), actor.employeeName());
        return policyRow(partnerId, false).toDto();
    }

    public void assertGeneralPartnerCreditLimitUnchanged(Long partnerId, BigDecimal requestedCreditLimit) {
        Long customerProfiles = jdbc.queryForObject(
                "select count(*) from partner_customer_profile where partner_id=?", Long.class, partnerId);
        if (customerProfiles == null || customerProfiles == 0) return;
        PolicyRow policy = policyRow(partnerId, false);
        BigDecimal requested = requestedCreditLimit == null ? ZERO : requestedCreditLimit;
        if (policy.enabled() && policy.creditLimit().compareTo(money(requested, "授信额度")) != 0) {
            throw new BusinessException("已启用销售风控的客户，请在客户详情的“销售风控策略”中调整授信额度并填写原因");
        }
    }

    public SalesRiskDtos.Evaluation previewSalesOrder(Long salesOrderId) {
        dataScopeService.assertDocumentAllowed(SALES_ORDER, salesOrderId);
        DocumentMeta document = salesOrder(salesOrderId);
        return visible(evaluate(document, false));
    }

    public SalesRiskDtos.Evaluation previewOutbound(Long outboundId) {
        dataScopeService.assertDocumentAllowed(OUTBOUND, outboundId);
        DocumentMeta document = outbound(outboundId);
        return visible(evaluate(document, false));
    }

    @Transactional
    public SalesRiskDtos.Evaluation enforceSalesOrder(Long salesOrderId, String action,
                                                       int sourceRevisionNo, String overrideReason) {
        return enforceSalesOrder(salesOrderId, action, sourceRevisionNo, overrideReason, null);
    }

    @Transactional
    public SalesRiskDtos.Evaluation enforceSalesOrder(Long salesOrderId, String action,
                                                       int sourceRevisionNo, String overrideReason,
                                                       Long approvalId) {
        DocumentMeta document = salesOrder(salesOrderId).withAction(action, sourceRevisionNo);
        return enforce(document, overrideReason, approvalId);
    }

    @Transactional
    public void lockCustomerScope(Long customerId) {
        policyRow(customerId, true);
    }

    @Transactional
    public SalesRiskDtos.Evaluation enforceOutbound(Long outboundId, String overrideReason) {
        return enforceOutbound(outboundId, overrideReason, null);
    }

    @Transactional
    public SalesRiskDtos.Evaluation enforceOutbound(Long outboundId, String overrideReason, Long approvalId) {
        return enforce(outbound(outboundId).withAction("SHIP", 1), overrideReason, approvalId);
    }

    public List<SalesRiskDtos.Snapshot> salesOrderHistory(Long salesOrderId) {
        dataScopeService.assertDocumentAllowed(SALES_ORDER, salesOrderId);
        return history(SALES_ORDER, salesOrderId);
    }

    public List<SalesRiskDtos.Snapshot> outboundHistory(Long outboundId) {
        dataScopeService.assertDocumentAllowed(OUTBOUND, outboundId);
        return history(OUTBOUND, outboundId);
    }

    public RiskExceptionService.Trigger exceptionTrigger(String documentType, Long documentId, String action) {
        DocumentMeta document = SALES_ORDER.equals(documentType)
                ? salesOrder(documentId).withAction(action, salesOrder(documentId).sourceRevisionNo())
                : OUTBOUND.equals(documentType)
                    ? outbound(documentId).withAction(action, 1)
                    : null;
        if (document == null) throw new BusinessException("当前销售单据类型不支持例外审批");
        return exceptionTrigger(evaluate(document, false));
    }

    private SalesRiskDtos.Evaluation enforce(DocumentMeta document, String overrideReason, Long approvalId) {
        EvaluationFull evaluation = evaluate(document, true);
        if (!evaluation.policy().enabled() && evaluation.issues().isEmpty()) return visible(evaluation);
        String decision = "PASS";
        String reason = "";
        if (!evaluation.issues().isEmpty()) {
            if (!StringUtils.hasText(overrideReason) && approvalId == null) {
                throw new BusinessException("销售风险控制未通过：" + issueTitles(evaluation.issues())
                        + "。请调整订单或先取得负责人例外批准");
            }
            RiskExceptionService.Authorization authorization = riskExceptionService.authorizeOrConsume(
                    exceptionTrigger(evaluation), new RiskExceptionDtos.InlineAuthorization(overrideReason, approvalId));
            reason = authorization.reason();
            decision = "OVERRIDDEN";
        }
        recordSnapshot(evaluation, decision, reason);
        return visible(evaluation.withDecision(decision));
    }

    private EvaluationFull evaluate(DocumentMeta document, boolean lockCustomer) {
        PolicyRow policy = policyRow(document.customerId(), lockCustomer);
        ExposureFull exposure = exposure(document, policy.creditLimit());
        MarginFull margin = SALES_ORDER.equals(document.documentType())
                ? salesOrderMargin(document, policy.minimumMarginRate())
                : outboundMargin(document, policy.minimumMarginRate());
        List<SalesRiskDtos.Issue> issues = new ArrayList<>();
        if (policy.enabled() && policy.creditControlEnabled()
                && exposure.netExposure().compareTo(policy.creditLimit()) > 0) {
            issues.add(new SalesRiskDtos.Issue("CREDIT_LIMIT_EXCEEDED", "超过客户授信额度",
                    "本次后信用敞口超过额度 " + moneyText(exposure.netExposure().subtract(policy.creditLimit())) + " 元"));
        }
        if (policy.enabled() && policy.overdueControlEnabled() && exposure.overdue().signum() > 0
                && exposure.maximumOverdueDays() > policy.maxOverdueDays()) {
            issues.add(new SalesRiskDtos.Issue("OVERDUE_RECEIVABLE", "客户存在超期欠款",
                    "逾期 " + moneyText(exposure.overdue()) + " 元，最长 " + exposure.maximumOverdueDays()
                            + " 天，超过策略允许的 " + policy.maxOverdueDays() + " 天"));
        }
        if (policy.enabled() && policy.marginControlEnabled()) {
            if (!margin.available()) {
                issues.add(new SalesRiskDtos.Issue("MARGIN_UNAVAILABLE", "毛利无法可靠计算",
                        OUTBOUND.equals(document.documentType()) && document.pricePending()
                                ? "出库价格尚未确定，发货前无法验证最低毛利"
                                : "部分商品没有可用库存成本，当前无法验证最低毛利"));
            } else if (margin.marginRate().compareTo(policy.minimumMarginRate()) < 0) {
                issues.add(new SalesRiskDtos.Issue("BELOW_MINIMUM_MARGIN", "低于最低毛利率",
                        "预计毛利率低于客户策略最低要求"));
            }
        }
        issues.addAll(manualSpecialPriceIssues(document));
        String decision = !policy.enabled() ? "NOT_CONTROLLED" : issues.isEmpty() ? "PASS" : "BLOCK";
        return new EvaluationFull(document, policy, exposure, margin, List.copyOf(issues), decision,
                LocalDateTime.now());
    }

    private List<SalesRiskDtos.Issue> manualSpecialPriceIssues(DocumentMeta document) {
        List<SalesRiskDtos.Issue> issues = new ArrayList<>();
        List<Map<String, Object>> lines = SALES_ORDER.equals(document.documentType())
                ? jdbc.queryForList("""
                        select unit_price,price_snapshot_json from sales_order_item
                        where sales_order_id=? and price_source_code='MANUAL_OVERRIDE'
                        """, document.documentId())
                : jdbc.queryForList("""
                        select oi.unit_price,soi.price_snapshot_json
                        from outbound_item oi
                        join sales_order_item soi on soi.id=oi.sales_order_item_id
                        where oi.outbound_id=? and soi.price_source_code='MANUAL_OVERRIDE'
                        """, document.documentId());
        for (Map<String, Object> line : lines) {
            try {
                JsonNode snapshot = objectMapper.readTree(String.valueOf(line.get("price_snapshot_json")));
                JsonNode suggestion = snapshot == null ? null : snapshot.get("suggestedUnitPrice");
                if (suggestion != null && suggestion.isNumber()
                        && money((BigDecimal) line.get("unit_price")).compareTo(suggestion.decimalValue()) < 0) {
                    issues.add(new SalesRiskDtos.Issue("MANUAL_PRICE_BELOW_SUGGESTED", "手工特价低于建议价",
                            "存在手工输入且低于定价中心建议价的商品，需要负责人确认价格例外"));
                    break;
                }
            } catch (Exception ignored) {
                // 旧单据无有效建议价快照时不臆断为特价，仍由既有最低毛利规则控制。
            }
        }
        return issues;
    }

    private RiskExceptionService.Trigger exceptionTrigger(EvaluationFull evaluation) {
        DocumentMeta document = evaluation.document();
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("documentType", document.documentType());
        snapshot.put("documentId", document.documentId());
        snapshot.put("documentNo", document.documentNo());
        snapshot.put("action", document.action());
        snapshot.put("sourceRevisionNo", document.sourceRevisionNo());
        snapshot.put("customerId", document.customerId());
        snapshot.put("status", document.status());
        snapshot.put("controlAmount", document.controlAmount());
        snapshot.put("policyVersion", evaluation.policy().versionNo());
        snapshot.put("creditLimit", evaluation.exposure().creditLimit());
        snapshot.put("netExposure", evaluation.exposure().netExposure());
        snapshot.put("overdueAmount", evaluation.exposure().overdue());
        snapshot.put("maximumOverdueDays", evaluation.exposure().maximumOverdueDays());
        snapshot.put("marginAvailable", evaluation.margin().available());
        snapshot.put("estimatedMarginRate", evaluation.margin().marginRate());
        snapshot.put("minimumMarginRate", evaluation.margin().minimumMarginRate());
        List<String> issueCodes = evaluation.issues().stream().map(SalesRiskDtos.Issue::code).sorted().toList();
        snapshot.put("issueCodes", issueCodes);
        return riskExceptionService.salesTrigger(document.documentType(), document.documentId(),
                document.documentNo(), document.action(), document.sourceRevisionNo(), document.controlAmount(),
                snapshot, issueCodes, issueTitles(evaluation.issues()));
    }

    private ExposureFull exposure(DocumentMeta document, BigDecimal creditLimit) {
        BigDecimal receivable = value("""
                select coalesce(sum(unpaid_amount),0) from (
                  select greatest(receivable_amount-received_amount-adjusted_amount,0) unpaid_amount
                  from sales_order where customer_id=? and status not in ('DRAFT','CANCELLED')
                  union all
                  select greatest(receivable_amount-paid_amount,0) unpaid_amount
                  from outbound_order where customer_id=? and sales_order_id=0 and status='SHIPPED'
                ) debt
                """, document.customerId(), document.customerId());
        BigDecimal commitments = value("""
                select coalesce(sum(greatest(total_amount-receivable_amount,0)),0)
                from sales_order where customer_id=? and status in ('OPEN','PARTIAL_SHIPPED')
                """, document.customerId());
        BigDecimal current = ZERO;
        if (SALES_ORDER.equals(document.documentType()) && "DRAFT".equals(document.status())) {
            current = document.controlAmount();
        } else if (OUTBOUND.equals(document.documentType()) && document.salesOrderId() == 0
                && "DRAFT".equals(document.status())) {
            current = document.controlAmount();
        }
        BigDecimal advance = value("""
                select coalesce(sum(balance),0) from partner_account
                where partner_id=? and account_type='CUSTOMER_ADVANCE'
                """, document.customerId());
        BigDecimal acceptance = value("""
                select coalesce(sum(face_amount),0) from acceptance_bill
                where customer_id=? and status='HELD'
                """, document.customerId());
        List<OverdueRow> overdueRows = jdbc.query("""
                select coalesce(sum(unpaid_amount),0) overdue_amount,
                       coalesce(max(datediff(current_date,due_date)),0) maximum_overdue_days
                from (
                  select due_date,greatest(receivable_amount-received_amount-adjusted_amount,0) unpaid_amount
                  from sales_order where customer_id=? and status not in ('DRAFT','CANCELLED')
                  union all
                  select due_date,greatest(receivable_amount-paid_amount,0) unpaid_amount
                  from outbound_order where customer_id=? and sales_order_id=0 and status='SHIPPED'
                ) debt where unpaid_amount>0 and due_date<current_date
                """, (rs, rowNum) -> new OverdueRow(rs.getBigDecimal("overdue_amount"),
                rs.getInt("maximum_overdue_days")), document.customerId(), document.customerId());
        OverdueRow overdue = overdueRows.isEmpty() ? new OverdueRow(ZERO, 0) : overdueRows.get(0);
        BigDecimal gross = receivable.add(commitments).add(current);
        BigDecimal net = gross.subtract(advance).max(ZERO);
        return new ExposureFull(money(receivable), money(commitments), money(current), money(gross), money(advance),
                money(acceptance), money(net), money(creditLimit), money(creditLimit.subtract(net).max(ZERO)),
                money(net.subtract(creditLimit).max(ZERO)), money(overdue.amount()), overdue.maximumDays());
    }

    private MarginFull salesOrderMargin(DocumentMeta document, BigDecimal minimumMarginRate) {
        String sourceType = jdbc.queryForObject(
                "select source_type from sales_order where id=?", String.class, document.documentId());
        if ("DIRECT_TRADE".equals(sourceType)) {
            Map<String, Object> amounts = jdbc.queryForMap("""
                    select sale_order_amount,purchase_order_amount
                    from direct_trade_order where sales_order_id=?
                    """, document.documentId());
            BigDecimal directRevenue = money((BigDecimal) amounts.get("sale_order_amount"));
            BigDecimal directCost = money((BigDecimal) amounts.get("purchase_order_amount"));
            boolean available = directRevenue.signum() > 0;
            return margin(available, directRevenue, directCost, minimumMarginRate,
                    "DIRECT_TRADE_PURCHASE_SNAPSHOT_V1");
        }
        List<CostLine> lines = jdbc.query("""
                select i.quantity,
                       (select sum(s.amount_cost)/nullif(sum(s.quantity_available),0)
                        from stock_balance s where s.product_id=i.product_id and s.quantity_available>0) unit_cost
                from sales_order_item i where i.sales_order_id=? and i.status='ENABLED'
                """, (rs, rowNum) -> new CostLine(rs.getBigDecimal("quantity"), rs.getBigDecimal("unit_cost")),
                document.documentId());
        boolean available = document.revenueExcludingTax().signum() > 0 && !lines.isEmpty()
                && lines.stream().allMatch(line -> line.unitCost() != null && line.unitCost().signum() >= 0);
        BigDecimal cost = available ? lines.stream().map(line -> line.quantity().multiply(line.unitCost()))
                .reduce(BigDecimal.ZERO, BigDecimal::add) : ZERO;
        return margin(available, document.revenueExcludingTax(), cost, minimumMarginRate,
                "CURRENT_STOCK_WEIGHTED_AVERAGE_V1");
    }

    private MarginFull outboundMargin(DocumentMeta document, BigDecimal minimumMarginRate) {
        List<CostLine> lines = jdbc.query("""
                select i.quantity,b.unit_cost
                from outbound_item i join inventory_batch b on b.id=i.batch_id
                where i.outbound_id=?
                """, (rs, rowNum) -> new CostLine(rs.getBigDecimal("quantity"), rs.getBigDecimal("unit_cost")),
                document.documentId());
        boolean available = !document.pricePending() && document.revenueExcludingTax().signum() > 0
                && !lines.isEmpty() && lines.stream().allMatch(line -> line.unitCost() != null && line.unitCost().signum() >= 0);
        BigDecimal cost = lines.stream().filter(line -> line.unitCost() != null)
                .map(line -> line.quantity().multiply(line.unitCost()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return margin(available, document.revenueExcludingTax(), cost, minimumMarginRate,
                document.pricePending() ? "BATCH_ACTUAL_COST_PRICE_PENDING_V1" : "BATCH_ACTUAL_COST_V1");
    }

    private MarginFull margin(boolean available, BigDecimal revenue, BigDecimal cost,
                              BigDecimal minimumMarginRate, String costBasis) {
        BigDecimal normalizedRevenue = money(revenue);
        BigDecimal normalizedCost = money(cost);
        BigDecimal profit = money(normalizedRevenue.subtract(normalizedCost));
        BigDecimal marginRate = available && normalizedRevenue.signum() > 0
                ? profit.multiply(new BigDecimal("100")).divide(normalizedRevenue, 4, RoundingMode.HALF_UP)
                : BigDecimal.ZERO.setScale(4);
        return new MarginFull(available, normalizedRevenue, normalizedCost, profit, marginRate,
                rate(minimumMarginRate), costBasis);
    }

    private void recordSnapshot(EvaluationFull evaluation, String decision, String overrideReason) {
        DocumentMeta document = evaluation.document();
        ExposureFull exposure = evaluation.exposure();
        MarginFull margin = evaluation.margin();
        SecurityContext.Principal actor = SecurityContext.get();
        long actorId = actor == null || actor.userId() == null ? 0L : actor.userId();
        String actorName = actor == null ? "系统" : actor.employeeName();
        String issueJson;
        try {
            issueJson = objectMapper.writeValueAsString(evaluation.issues().stream()
                    .map(SalesRiskDtos.Issue::code).toList());
        } catch (Exception ex) {
            throw new BusinessException("销售风险快照无法保存");
        }
        jdbc.update("""
                insert into sales_risk_snapshot
                (document_type,document_id,document_no,action,source_revision_no,customer_id,policy_version,
                 receivable_amount,confirmed_unshipped_amount,current_document_amount,advance_credit_amount,
                 acceptance_secured_amount,net_exposure_amount,credit_limit_amount,available_credit_amount,
                 overdue_amount,maximum_overdue_days,revenue_excluding_tax,margin_available,estimated_cost_amount,
                 estimated_gross_profit,estimated_margin_rate,minimum_margin_rate,cost_basis,issue_codes_json,
                 decision,override_reason,operator_id,operator_name)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                on duplicate key update id=id
                """, document.documentType(), document.documentId(), document.documentNo(), document.action(),
                document.sourceRevisionNo(), document.customerId(), evaluation.policy().versionNo(),
                exposure.receivable(), exposure.commitments(), exposure.current(), exposure.advance(),
                exposure.acceptance(), exposure.netExposure(), exposure.creditLimit(), exposure.availableCredit(),
                exposure.overdue(), exposure.maximumOverdueDays(), margin.revenue(), bool(margin.available()), margin.cost(), margin.profit(),
                margin.marginRate(), margin.minimumMarginRate(), margin.costBasis(), issueJson, decision,
                overrideReason, actorId, actorName);
    }

    private List<SalesRiskDtos.Snapshot> history(String documentType, Long documentId) {
        boolean marginVisible = canViewMargin();
        return jdbc.query("""
                select * from sales_risk_snapshot
                where document_type=? and document_id=? order by id desc
                """, (rs, rowNum) -> {
            ExposureFull exposure = new ExposureFull(rs.getBigDecimal("receivable_amount"),
                    rs.getBigDecimal("confirmed_unshipped_amount"), rs.getBigDecimal("current_document_amount"),
                    rs.getBigDecimal("receivable_amount").add(rs.getBigDecimal("confirmed_unshipped_amount"))
                            .add(rs.getBigDecimal("current_document_amount")),
                    rs.getBigDecimal("advance_credit_amount"), rs.getBigDecimal("acceptance_secured_amount"),
                    rs.getBigDecimal("net_exposure_amount"), rs.getBigDecimal("credit_limit_amount"),
                    rs.getBigDecimal("available_credit_amount"),
                    rs.getBigDecimal("net_exposure_amount").subtract(rs.getBigDecimal("credit_limit_amount")).max(ZERO),
                    rs.getBigDecimal("overdue_amount"), rs.getInt("maximum_overdue_days"));
            MarginFull margin = new MarginFull(rs.getBoolean("margin_available"), rs.getBigDecimal("revenue_excluding_tax"),
                    rs.getBigDecimal("estimated_cost_amount"), rs.getBigDecimal("estimated_gross_profit"),
                    rs.getBigDecimal("estimated_margin_rate"), rs.getBigDecimal("minimum_margin_rate"),
                    rs.getString("cost_basis"));
            List<String> issueCodes;
            try {
                issueCodes = objectMapper.readValue(rs.getString("issue_codes_json"), new TypeReference<>() {});
            } catch (Exception ex) {
                issueCodes = List.of("SNAPSHOT_JSON_INVALID");
            }
            return new SalesRiskDtos.Snapshot(rs.getLong("id"), rs.getString("document_type"),
                    rs.getLong("document_id"), rs.getString("document_no"), rs.getString("action"),
                    rs.getInt("source_revision_no"), rs.getLong("customer_id"), rs.getInt("policy_version"),
                    exposure.toDto(), margin.toDto(marginVisible), issueCodes, rs.getString("decision"),
                    rs.getString("override_reason"), rs.getLong("operator_id"), rs.getString("operator_name"),
                    rs.getTimestamp("created_at").toLocalDateTime());
        }, documentType, documentId);
    }

    private SalesRiskDtos.Evaluation visible(EvaluationFull evaluation) {
        boolean marginVisible = canViewMargin();
        return new SalesRiskDtos.Evaluation(evaluation.document().documentType(), evaluation.document().documentId(),
                evaluation.document().documentNo(), evaluation.document().action(), evaluation.policy().enabled(),
                evaluation.policy().versionNo(), evaluation.decision(), !evaluation.issues().isEmpty(),
                canOverride(), marginVisible, evaluation.exposure().toDto(),
                evaluation.margin().toDto(marginVisible), evaluation.issues(), evaluation.evaluatedAt());
    }

    private PolicyRow policyRow(Long partnerId, boolean lock) {
        if (lock) {
            PartnerBusinessGuard.requireEnabledRole(jdbc, partnerId, "CUSTOMER");
            // Business paths already hold shared partner/profile locks. Serialize exposure
            // on its own PK rather than upgrading those shared locks to exclusive ones.
            // A no-op upsert takes X directly, including the initial absent-row case;
            // INSERT IGNORE followed by FOR UPDATE would introduce an S-to-X conversion race.
            jdbc.update("""
                    insert into sales_risk_policy(partner_id) values (?)
                    on duplicate key update partner_id=values(partner_id)
                    """, partnerId);
        }
        List<PolicyRow> rows = jdbc.query("""
                select p.id,p.name,cp.credit_limit,
                       coalesce(r.enabled,0) enabled,coalesce(r.credit_control_enabled,1) credit_control_enabled,
                       coalesce(r.overdue_control_enabled,1) overdue_control_enabled,
                       coalesce(r.max_overdue_days,0) max_overdue_days,
                       coalesce(r.margin_control_enabled,0) margin_control_enabled,
                       coalesce(r.minimum_margin_rate,0) minimum_margin_rate,
                       coalesce(r.version_no,0) version_no,coalesce(r.change_reason,'') change_reason,
                       coalesce(r.updated_by,0) updated_by,coalesce(r.updated_by_name,'') updated_by_name,
                       r.updated_at
                from partner p join partner_customer_profile cp on cp.partner_id=p.id
                left join sales_risk_policy r on r.partner_id=p.id
                where p.id=?
                """ + (lock ? " for share" : ""), (rs, rowNum) -> new PolicyRow(
                rs.getLong("id"), rs.getString("name"), rs.getBoolean("enabled"),
                rs.getBoolean("credit_control_enabled"), rs.getBigDecimal("credit_limit"),
                rs.getBoolean("overdue_control_enabled"), rs.getInt("max_overdue_days"),
                rs.getBoolean("margin_control_enabled"), rs.getBigDecimal("minimum_margin_rate"),
                rs.getInt("version_no"), rs.getString("change_reason"), rs.getLong("updated_by"),
                rs.getString("updated_by_name"), rs.getTimestamp("updated_at") == null ? null
                : rs.getTimestamp("updated_at").toLocalDateTime()), partnerId);
        if (rows.isEmpty()) throw new BusinessException("客户不存在或未启用客户身份");
        return rows.get(0);
    }

    private DocumentMeta salesOrder(Long id) {
        List<DocumentMeta> rows = jdbc.query("""
                select id,order_no,customer_id,status,total_amount,amount_excluding_tax,current_revision_no
                from sales_order where id=?
                """, (rs, rowNum) -> new DocumentMeta(SALES_ORDER, rs.getLong("id"), rs.getString("order_no"),
                rs.getLong("customer_id"), rs.getString("status"), 0L, rs.getBigDecimal("total_amount"),
                rs.getBigDecimal("amount_excluding_tax"), false,
                "DRAFT".equals(rs.getString("status")) ? "CONFIRM" : "REVIEW",
                rs.getInt("current_revision_no")), id);
        if (rows.isEmpty()) throw new BusinessException("销售订单不存在");
        return rows.get(0);
    }

    private DocumentMeta outbound(Long id) {
        List<DocumentMeta> rows = jdbc.query("""
                select id,order_no,customer_id,status,sales_order_id,total_amount,amount_excluding_tax,
                       settlement_status,shipment_control_amount
                from outbound_order where id=?
                """, (rs, rowNum) -> {
            boolean pending = "PENDING_PRICING".equals(rs.getString("settlement_status"));
            BigDecimal total = rs.getBigDecimal("total_amount");
            BigDecimal control = pending ? rs.getBigDecimal("shipment_control_amount") : total;
            return new DocumentMeta(OUTBOUND, rs.getLong("id"), rs.getString("order_no"),
                    rs.getLong("customer_id"), rs.getString("status"), rs.getLong("sales_order_id"),
                    control, rs.getBigDecimal("amount_excluding_tax"), pending, "SHIP", 1);
        }, id);
        if (rows.isEmpty()) throw new BusinessException("出库单不存在");
        return rows.get(0);
    }

    private BigDecimal value(String sql, Object... args) {
        BigDecimal value = jdbc.queryForObject(sql, BigDecimal.class, args);
        return value == null ? ZERO : value;
    }

    private boolean canViewMargin() {
        SecurityContext.Principal actor = SecurityContext.get();
        return actor != null && (actor.hasPermission("sales.gross-profit.view")
                || actor.hasPermission("sales.risk.policy.manage"));
    }

    private boolean canOverride() {
        SecurityContext.Principal actor = SecurityContext.get();
        return actor != null && actor.hasPermission("sales.risk.override");
    }

    private String issueTitles(List<SalesRiskDtos.Issue> issues) {
        return issues.stream().map(SalesRiskDtos.Issue::title).reduce((left, right) -> left + "、" + right).orElse("");
    }

    private String requiredReason(String value, String action) {
        if (!StringUtils.hasText(value)) throw new BusinessException(action + "必须填写原因");
        String reason = value.trim();
        if (reason.length() > 500) throw new BusinessException(action + "原因不能超过500字");
        return reason;
    }

    private BigDecimal money(BigDecimal value, String label) {
        if (value == null || value.signum() < 0) throw new BusinessException(label + "不能小于0");
        try {
            return value.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException ex) {
            throw new BusinessException(label + "最多保留2位小数");
        }
    }

    private BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal rate(BigDecimal value, String label) {
        if (value == null || value.signum() < 0 || value.compareTo(new BigDecimal("100")) > 0) {
            throw new BusinessException(label + "必须在0到100之间");
        }
        try {
            return value.setScale(4, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException ex) {
            throw new BusinessException(label + "最多保留4位小数");
        }
    }

    private BigDecimal rate(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(4, RoundingMode.HALF_UP);
    }

    private String moneyText(BigDecimal value) {
        return money(value).toPlainString();
    }

    private int bool(Boolean value) { return Boolean.TRUE.equals(value) ? 1 : 0; }

    private record PolicyRow(Long partnerId, String partnerName, boolean enabled,
                             boolean creditControlEnabled, BigDecimal creditLimit,
                             boolean overdueControlEnabled, int maxOverdueDays,
                             boolean marginControlEnabled, BigDecimal minimumMarginRate,
                             int versionNo, String changeReason, long updatedBy,
                             String updatedByName, LocalDateTime updatedAt) {
        SalesRiskDtos.Policy toDto() {
            return new SalesRiskDtos.Policy(partnerId, partnerName, enabled, creditControlEnabled,
                    creditLimit, overdueControlEnabled, maxOverdueDays, marginControlEnabled,
                    minimumMarginRate, versionNo, changeReason, updatedBy, updatedByName, updatedAt);
        }
    }

    private record DocumentMeta(String documentType, Long documentId, String documentNo,
                                Long customerId, String status, Long salesOrderId,
                                BigDecimal controlAmount, BigDecimal revenueExcludingTax,
                                boolean pricePending, String action, int sourceRevisionNo) {
        DocumentMeta withAction(String nextAction, int revisionNo) {
            return new DocumentMeta(documentType, documentId, documentNo, customerId, status, salesOrderId,
                    controlAmount, revenueExcludingTax, pricePending, nextAction, revisionNo);
        }
    }

    private record ExposureFull(BigDecimal receivable, BigDecimal commitments, BigDecimal current,
                                BigDecimal gross, BigDecimal advance, BigDecimal acceptance,
                                BigDecimal netExposure, BigDecimal creditLimit, BigDecimal availableCredit,
                                BigDecimal overLimit, BigDecimal overdue, int maximumOverdueDays) {
        SalesRiskDtos.Exposure toDto() {
            return new SalesRiskDtos.Exposure(receivable, commitments, current, gross, advance, acceptance,
                    netExposure, creditLimit, availableCredit, overLimit, overdue, maximumOverdueDays);
        }
    }

    private record MarginFull(boolean available, BigDecimal revenue, BigDecimal cost, BigDecimal profit,
                              BigDecimal marginRate, BigDecimal minimumMarginRate, String costBasis) {
        SalesRiskDtos.Margin toDto(boolean visible) {
            return new SalesRiskDtos.Margin(available, visible ? revenue : null, visible ? cost : null,
                    visible ? profit : null, visible ? marginRate : null,
                    visible ? minimumMarginRate : null, costBasis);
        }
    }

    private record EvaluationFull(DocumentMeta document, PolicyRow policy, ExposureFull exposure,
                                  MarginFull margin, List<SalesRiskDtos.Issue> issues,
                                  String decision, LocalDateTime evaluatedAt) {
        EvaluationFull withDecision(String nextDecision) {
            return new EvaluationFull(document, policy, exposure, margin, issues, nextDecision, evaluatedAt);
        }
    }

    private record CostLine(BigDecimal quantity, BigDecimal unitCost) {}
    private record OverdueRow(BigDecimal amount, int maximumDays) {}
}
