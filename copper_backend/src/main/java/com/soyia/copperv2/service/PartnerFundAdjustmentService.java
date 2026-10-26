package com.soyia.copperv2.service;

import com.soyia.copperv2.dto.BusinessPostingDtos;
import com.soyia.copperv2.dto.PartnerFundAdjustmentDtos;
import com.soyia.copperv2.dto.RiskExceptionDtos;
import com.soyia.copperv2.security.BusinessDataScopeService;
import com.soyia.copperv2.security.SecurityContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class PartnerFundAdjustmentService {
    private static final Set<String> ACCOUNT_TYPES = Set.of(
            "CUSTOMER_ADVANCE", "CUSTOMER_REWARD", "SUPPLIER_PREPAY", "SUPPLIER_REWARD");
    private static final Set<String> ADJUSTMENT_TYPES = Set.of(
            "OPENING_BALANCE", "CUSTOMER_REWARD_GRANT", "SUPPLIER_REBATE_GRANT", "BALANCE_CORRECTION");
    private static final Set<String> TAX_TREATMENTS = Set.of(
            "NOT_APPLICABLE", "PENDING_PROFESSIONAL_REVIEW", "SEPARATE_INVOICE_HANDLING");
    private static final DateTimeFormatter NO_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");
    private static final AtomicInteger NO_COUNTER = new AtomicInteger();

    private final JdbcTemplate jdbc;
    private final BusinessPostingService postingService;
    private final BusinessDataScopeService dataScopeService;
    private final RiskExceptionService riskExceptionService;

    public PartnerFundAdjustmentService(JdbcTemplate jdbc, BusinessPostingService postingService,
                                        BusinessDataScopeService dataScopeService,
                                        RiskExceptionService riskExceptionService) {
        this.jdbc = jdbc;
        this.postingService = postingService;
        this.dataScopeService = dataScopeService;
        this.riskExceptionService = riskExceptionService;
    }

    public List<PartnerFundAdjustmentDtos.Row> list(String status, Long partnerId) {
        StringBuilder sql = new StringBuilder(baseSelect()).append(" where 1=1");
        java.util.ArrayList<Object> args = new java.util.ArrayList<>();
        if (status != null && !status.isBlank()) {
            sql.append(" and a.status=?");
            args.add(status.trim().toUpperCase());
        }
        if (partnerId != null && partnerId > 0) {
            sql.append(" and a.partner_id=?");
            args.add(partnerId);
        }
        sql.append(" order by a.business_date desc,a.id desc limit 500");
        return dataScopeService.filterDocumentList("PARTNER_FUND_ADJUSTMENT",
                jdbc.query(sql.toString(), rowMapper(), args.toArray()));
    }

    public PartnerFundAdjustmentDtos.Detail detail(Long id) {
        dataScopeService.assertDocumentAllowed("PARTNER_FUND_ADJUSTMENT", id);
        List<PartnerFundAdjustmentDtos.Row> rows = jdbc.query(baseSelect() + " where a.id=?", rowMapper(), id);
        if (rows.isEmpty()) throw new BusinessException("余额调整单不存在");
        List<PartnerFundAdjustmentDtos.EventRow> events = jdbc.query("""
                select id,action,from_status,to_status,reason,operator_name,created_at
                from partner_fund_adjustment_event where adjustment_id=? order by id
                """, (rs, rowNum) -> new PartnerFundAdjustmentDtos.EventRow(
                rs.getLong("id"), rs.getString("action"), rs.getString("from_status"),
                rs.getString("to_status"), rs.getString("reason"), rs.getString("operator_name"),
                rs.getTimestamp("created_at").toLocalDateTime()), id);
        return new PartnerFundAdjustmentDtos.Detail(rows.get(0), events);
    }

    @Transactional
    public Long create(PartnerFundAdjustmentDtos.Create req) {
        String accountType = normalized(req.accountType());
        String adjustmentType = normalized(req.adjustmentType());
        String direction = normalized(req.direction());
        String taxTreatment = normalized(req.taxTreatment());
        if (!ACCOUNT_TYPES.contains(accountType)) throw new BusinessException("往来余额账户类型无效");
        if (!ADJUSTMENT_TYPES.contains(adjustmentType)) throw new BusinessException("余额调整业务类型无效");
        if (!Set.of("INCREASE", "DECREASE").contains(direction)) throw new BusinessException("调整方向无效");
        if (!TAX_TREATMENTS.contains(taxTreatment)) throw new BusinessException("税票处理状态无效");
        BigDecimal amount = money(req.amount());
        if (amount.compareTo(BigDecimal.ZERO) <= 0) throw new BusinessException("调整金额必须大于0");
        requirePartnerRole(req.partnerId(), accountType);
        dataScopeService.assertMasterAllowed("PARTNER", req.partnerId());
        dataScopeService.assertPartnerAccountAllowed(req.partnerId(), accountType);
        validateSemantic(accountType, adjustmentType, direction, taxTreatment);
        ensureOpenPeriod(req.businessDate());

        Actor actor = actor();
        String no = nextNo("YE");
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    insert into partner_fund_adjustment
                    (adjustment_no,partner_id,account_type,adjustment_type,direction,amount,business_date,
                     basis_no,tax_treatment,memo,status,created_by,created_by_name)
                    values (?,?,?,?,?,?,?, ?,?,?,'DRAFT',?,?)
                    """, Statement.RETURN_GENERATED_KEYS);
            int i = 1;
            ps.setString(i++, no);
            ps.setLong(i++, req.partnerId());
            ps.setString(i++, accountType);
            ps.setString(i++, adjustmentType);
            ps.setString(i++, direction);
            ps.setBigDecimal(i++, amount);
            ps.setDate(i++, Date.valueOf(req.businessDate()));
            ps.setString(i++, req.basisNo().trim());
            ps.setString(i++, taxTreatment);
            ps.setString(i++, req.memo().trim());
            ps.setLong(i++, actor.id());
            ps.setString(i, actor.name());
            return ps;
        }, keyHolder);
        Long id = keyHolder.getKey().longValue();
        event(id, "CREATE", "", "DRAFT", req.memo().trim(), actor);
        dataScopeService.stampDocumentOwner("PARTNER_FUND_ADJUSTMENT", id);
        return id;
    }

    @Transactional
    public Long confirm(Long id) {
        return confirm(id, null);
    }

    @Transactional
    public Long confirm(Long id, RiskExceptionDtos.InlineAuthorization authorization) {
        AdjustmentLock row = lock(id);
        if ("CONFIRMED".equals(row.status())) return id;
        if (!"DRAFT".equals(row.status())) throw new BusinessException("只有草稿余额调整单可以确认");
        dataScopeService.assertDocumentAllowed("PARTNER_FUND_ADJUSTMENT", id);
        requirePartnerRole(row.partnerId(), row.accountType());
        riskExceptionService.authorizeOrConsume(
                riskExceptionService.resolveTrigger("PARTNER_FUND_ADJUSTMENT", id, "CONFIRM"), authorization);
        ensureOpenPeriod(row.businessDate());
        if ("OPENING_BALANCE".equals(row.adjustmentType())) ensureOpeningIsFirst(row);

        BigDecimal diff = "INCREASE".equals(row.direction()) ? row.amount() : row.amount().negate();
        changeBalance(row, diff, "PARTNER_FUND_ADJUSTMENT");
        Actor actor = actor();
        int updated = jdbc.update("""
                update partner_fund_adjustment
                set status='CONFIRMED',confirmed_by=?,confirmed_by_name=?,confirmed_at=now()
                where id=? and status='DRAFT'
                """, actor.id(), actor.name(), id);
        if (updated != 1) throw new BusinessException("余额调整单状态已变化，请刷新后重试");
        event(id, "CONFIRM", "DRAFT", "CONFIRMED", "负责人确认并过账", actor);
        postingService.post("PARTNER_FUND_ADJUSTMENT", id, row.adjustmentNo(), "POST", row.versionNo(),
                row.businessDate(), row.partnerId(), 0L, "余额调整确认：" + row.memo(),
                List.of(postingEntry(row)));
        return id;
    }

    @Transactional
    public Long cancel(Long id, PartnerFundAdjustmentDtos.Action req) {
        AdjustmentLock row = lock(id);
        if ("CANCELLED".equals(row.status())) return id;
        if (!"DRAFT".equals(row.status())) throw new BusinessException("只有草稿余额调整单可以作废");
        dataScopeService.assertDocumentAllowed("PARTNER_FUND_ADJUSTMENT", id);
        Actor actor = actor();
        jdbc.update("""
                update partner_fund_adjustment set status='CANCELLED',cancel_reason=?,cancelled_by=?,
                  cancelled_by_name=?,cancelled_at=now() where id=? and status='DRAFT'
                """, req.reason().trim(), actor.id(), actor.name(), id);
        event(id, "CANCEL", "DRAFT", "CANCELLED", req.reason().trim(), actor);
        return id;
    }

    @Transactional
    public Long reverse(Long id, PartnerFundAdjustmentDtos.Action req) {
        AdjustmentLock row = lock(id);
        if ("REVERSED".equals(row.status())) return id;
        if (!"CONFIRMED".equals(row.status())) throw new BusinessException("只有已确认余额调整单可以冲正");
        dataScopeService.assertDocumentAllowed("PARTNER_FUND_ADJUSTMENT", id);
        riskExceptionService.authorizeOrConsume(
                riskExceptionService.resolveTrigger("PARTNER_FUND_ADJUSTMENT", id, "REVERSE"),
                new RiskExceptionDtos.InlineAuthorization(req.reason(), req.approvalId()));
        LocalDate reversalDate = req.businessDate() == null ? LocalDate.now() : req.businessDate();
        ensureOpenPeriod(reversalDate);
        BigDecimal diff = "INCREASE".equals(row.direction()) ? row.amount().negate() : row.amount();
        changeBalance(row, diff, "PARTNER_FUND_ADJUSTMENT_REVERSAL");
        Actor actor = actor();
        String reversalNo = nextNo("YC");
        int updated = jdbc.update("""
                update partner_fund_adjustment set status='REVERSED',reversal_no=?,reversal_reason=?,
                  reversed_by=?,reversed_by_name=?,reversed_at=now() where id=? and status='CONFIRMED'
                """, reversalNo, req.reason().trim(), actor.id(), actor.name(), id);
        if (updated != 1) throw new BusinessException("余额调整单状态已变化，请刷新后重试");
        event(id, "REVERSE", "CONFIRMED", "REVERSED", req.reason().trim(), actor);
        postingService.reverseSource("PARTNER_FUND_ADJUSTMENT", id, row.adjustmentNo(), "REVERSE",
                row.versionNo(), reversalDate, row.partnerId(), List.of("POST"), req.reason().trim());
        return id;
    }

    private BusinessPostingDtos.EntryDraft postingEntry(AdjustmentLock row) {
        return new BusinessPostingDtos.EntryDraft(
                factType(row), row.direction(), row.partnerId(), 0L, 0L, 0L,
                "PARTNER_FUND_ADJUSTMENT", row.id(), BigDecimal.ZERO, "", row.amount(),
                accountName(row.accountType()) + " " + adjustmentName(row.adjustmentType()) + " " + row.adjustmentNo());
    }

    private String factType(AdjustmentLock row) {
        if ("OPENING_BALANCE".equals(row.adjustmentType())) {
            return "CUSTOMER_ADVANCE".equals(row.accountType())
                    ? "CUSTOMER_ADVANCE_OPENING" : "SUPPLIER_PREPAY_OPENING";
        }
        if ("CUSTOMER_REWARD_GRANT".equals(row.adjustmentType())) return "CUSTOMER_REWARD_GRANT";
        if ("SUPPLIER_REBATE_GRANT".equals(row.adjustmentType())) return "SUPPLIER_REBATE_GRANT";
        return switch (row.accountType()) {
            case "CUSTOMER_ADVANCE" -> "CUSTOMER_ADVANCE_CORRECTION";
            case "CUSTOMER_REWARD" -> "CUSTOMER_REWARD_CORRECTION";
            case "SUPPLIER_PREPAY" -> "SUPPLIER_PREPAY_CORRECTION";
            default -> "SUPPLIER_REWARD_CORRECTION";
        };
    }

    private void changeBalance(AdjustmentLock row, BigDecimal diff, String sourceType) {
        jdbc.update("""
                insert into partner_account(partner_id,account_type,balance) values (?,?,0)
                on duplicate key update updated_at=updated_at
                """, row.partnerId(), row.accountType());
        BigDecimal before = jdbc.queryForObject("""
                select balance from partner_account where partner_id=? and account_type=? for update
                """, BigDecimal.class, row.partnerId(), row.accountType());
        if (before == null) before = BigDecimal.ZERO;
        if (diff.signum() < 0 && "CUSTOMER_ADVANCE".equals(row.accountType())) {
            BigDecimal reserved = jdbc.queryForObject("""
                    select coalesce(sum(amount),0) from sales_order_advance_allocation
                    where customer_id=? and status in ('RESERVED','APPLIED')
                    """, BigDecimal.class, row.partnerId());
            BigDecimal available = before.subtract(reserved == null ? BigDecimal.ZERO : reserved);
            if (available.compareTo(diff.abs()) < 0) {
                throw new BusinessException("客户预收款已有订单占用，本次最多可冲减"
                        + available.max(BigDecimal.ZERO).setScale(2) + "元");
            }
        }
        BigDecimal after = money(before.add(diff));
        if (after.signum() < 0) throw new BusinessException(accountName(row.accountType()) + "余额不足，不能确认或冲正");
        jdbc.update("update partner_account set balance=?,updated_at=now() where partner_id=? and account_type=?",
                after, row.partnerId(), row.accountType());
        jdbc.update("""
                insert into partner_account_log
                (partner_id,account_type,source_type,source_id,amount,amount_before,amount_after)
                values (?,?,?,?,?,?,?)
                """, row.partnerId(), row.accountType(), sourceType, row.id(), diff, before, after);
    }

    private void ensureOpeningIsFirst(AdjustmentLock row) {
        Long activeOpening = jdbc.queryForObject("""
                select count(*) from partner_fund_adjustment
                where id<>? and partner_id=? and account_type=? and adjustment_type='OPENING_BALANCE'
                  and status='CONFIRMED'
                """, Long.class, row.id(), row.partnerId(), row.accountType());
        Long priorLogs = jdbc.queryForObject("""
                select count(*) from partner_account_log where partner_id=? and account_type=?
                """, Long.class, row.partnerId(), row.accountType());
        BigDecimal balance = jdbc.queryForObject("""
                select coalesce(max(balance),0) from partner_account where partner_id=? and account_type=?
                """, BigDecimal.class, row.partnerId(), row.accountType());
        if ((activeOpening != null && activeOpening > 0) || (priorLogs != null && priorLogs > 0)
                || (balance != null && balance.signum() != 0)) {
            throw new BusinessException("期初余额只能用于尚无余额和流水的账户；已有历史请使用差错调整或冲正原单");
        }
    }

    private void validateSemantic(String accountType, String adjustmentType, String direction, String taxTreatment) {
        if ("OPENING_BALANCE".equals(adjustmentType)) {
            if (!Set.of("CUSTOMER_ADVANCE", "SUPPLIER_PREPAY").contains(accountType) || !"INCREASE".equals(direction))
                throw new BusinessException("期初余额仅用于客户预收或供应商预付的调增");
        }
        if ("CUSTOMER_REWARD_GRANT".equals(adjustmentType)
                && (!"CUSTOMER_REWARD".equals(accountType) || !"INCREASE".equals(direction)))
            throw new BusinessException("客户奖励确认只能增加客户奖励金账户");
        if ("SUPPLIER_REBATE_GRANT".equals(adjustmentType)
                && (!"SUPPLIER_REWARD".equals(accountType) || !"INCREASE".equals(direction)))
            throw new BusinessException("供应商返利确认只能增加供应商返利金账户");
        if (Set.of("CUSTOMER_REWARD_GRANT", "SUPPLIER_REBATE_GRANT").contains(adjustmentType)
                && "NOT_APPLICABLE".equals(taxTreatment))
            throw new BusinessException("奖励或返利必须标记待专业复核或单独发票处理，不能直接认定税票不适用");
    }

    private void requirePartnerRole(Long partnerId, String accountType) {
        PartnerBusinessGuard.requireEnabledRole(jdbc, partnerId,
                accountType.startsWith("CUSTOMER") ? "CUSTOMER" : "SUPPLIER");
    }

    private void ensureOpenPeriod(LocalDate date) {
        Long count = jdbc.queryForObject("""
                select count(*) from accounting_entity e join accounting_period p on p.entity_id=e.id
                where e.entity_code='DEFAULT' and e.status='ENABLED' and p.status='OPEN'
                  and ? between p.start_date and p.end_date
                """, Long.class, Date.valueOf(date));
        if (count == null || count == 0) throw new BusinessException("业务日期不在开放会计期间，不能编制或过账余额调整单");
    }

    private AdjustmentLock lock(Long id) {
        List<AdjustmentLock> rows = jdbc.query("""
                select id,adjustment_no,partner_id,account_type,adjustment_type,direction,amount,
                       business_date,status,version_no,memo
                from partner_fund_adjustment where id=? for update
                """, (rs, rowNum) -> new AdjustmentLock(
                rs.getLong("id"), rs.getString("adjustment_no"), rs.getLong("partner_id"),
                rs.getString("account_type"), rs.getString("adjustment_type"), rs.getString("direction"),
                rs.getBigDecimal("amount"), rs.getDate("business_date").toLocalDate(),
                rs.getString("status"), rs.getInt("version_no"), rs.getString("memo")), id);
        if (rows.isEmpty()) throw new BusinessException("余额调整单不存在");
        return rows.get(0);
    }

    private String baseSelect() {
        return """
                select a.*,p.name partner_name,
                  coalesce(b.id,0) posting_batch_id,coalesce(b.posting_no,'') posting_no,
                  coalesce(b.status,'') posting_status,coalesce(v.id,0) accounting_voucher_id,
                  coalesce(v.voucher_no,'') accounting_voucher_no,coalesce(v.status,'') accounting_voucher_status
                from partner_fund_adjustment a join partner p on p.id=a.partner_id
                left join business_posting_batch b on b.source_type='PARTNER_FUND_ADJUSTMENT'
                  and b.source_id=a.id and b.posting_action='POST'
                left join accounting_voucher v on v.business_posting_batch_id=b.id
                """;
    }

    private org.springframework.jdbc.core.RowMapper<PartnerFundAdjustmentDtos.Row> rowMapper() {
        return (rs, rowNum) -> new PartnerFundAdjustmentDtos.Row(
                rs.getLong("id"), rs.getString("adjustment_no"), rs.getLong("partner_id"),
                rs.getString("partner_name"), rs.getString("account_type"), rs.getString("adjustment_type"),
                rs.getString("direction"), rs.getBigDecimal("amount"), rs.getDate("business_date").toLocalDate(),
                rs.getString("basis_no"), rs.getString("tax_treatment"), rs.getString("memo"),
                rs.getString("status"), rs.getString("created_by_name"), rs.getString("confirmed_by_name"),
                timestamp(rs.getTimestamp("confirmed_at")), rs.getString("cancel_reason"),
                rs.getString("cancelled_by_name"), timestamp(rs.getTimestamp("cancelled_at")),
                rs.getString("reversal_no"), rs.getString("reversal_reason"), rs.getString("reversed_by_name"),
                timestamp(rs.getTimestamp("reversed_at")), rs.getLong("posting_batch_id"),
                rs.getString("posting_no"), rs.getString("posting_status"), rs.getLong("accounting_voucher_id"),
                rs.getString("accounting_voucher_no"), rs.getString("accounting_voucher_status"),
                rs.getTimestamp("created_at").toLocalDateTime());
    }

    private void event(Long id, String action, String from, String to, String reason, Actor actor) {
        jdbc.update("""
                insert into partner_fund_adjustment_event
                (adjustment_id,action,from_status,to_status,reason,operator_id,operator_name)
                values (?,?,?,?,?,?,?)
                """, id, action, from, to, reason, actor.id(), actor.name());
    }

    private Actor actor() {
        SecurityContext.Principal principal = SecurityContext.get();
        return principal == null ? new Actor(0L, "系统") : new Actor(principal.userId(), principal.employeeName());
    }

    private String nextNo(String prefix) {
        return prefix + LocalDateTime.now().format(NO_TIME)
                + String.format("%03d", Math.floorMod(NO_COUNTER.incrementAndGet(), 1000));
    }

    private BigDecimal money(BigDecimal value) {
        return value == null ? BigDecimal.ZERO.setScale(2) : value.setScale(2, RoundingMode.HALF_UP);
    }

    private String normalized(String value) { return value == null ? "" : value.trim().toUpperCase(); }
    private LocalDateTime timestamp(java.sql.Timestamp value) { return value == null ? null : value.toLocalDateTime(); }
    private String accountName(String type) {
        return switch (type) {
            case "CUSTOMER_REWARD" -> "客户奖励金";
            case "SUPPLIER_PREPAY" -> "供应商预付款";
            case "SUPPLIER_REWARD" -> "供应商返利金";
            default -> "客户预收款";
        };
    }
    private String adjustmentName(String type) {
        return switch (type) {
            case "OPENING_BALANCE" -> "期初余额";
            case "CUSTOMER_REWARD_GRANT" -> "客户奖励确认";
            case "SUPPLIER_REBATE_GRANT" -> "供应商返利确认";
            default -> "余额差错调整";
        };
    }

    private record Actor(Long id, String name) {}
    private record AdjustmentLock(Long id, String adjustmentNo, Long partnerId, String accountType,
                                  String adjustmentType, String direction, BigDecimal amount,
                                  LocalDate businessDate, String status, int versionNo, String memo) {}
}
