package com.soyia.copperv2.service;

import com.soyia.copperv2.dto.AccountingDtos;
import com.soyia.copperv2.security.SecurityContext;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class AccountingService {
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    private static final DateTimeFormatter NO_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");
    private static final AtomicInteger NO_COUNTER = new AtomicInteger();
    private final JdbcTemplate jdbc;
    private final AccountingCoverageService coverageService;

    public AccountingService(JdbcTemplate jdbc, AccountingCoverageService coverageService) {
        this.jdbc = jdbc;
        this.coverageService = coverageService;
    }

    public AccountingDtos.EntityView entity() {
        return jdbc.queryForObject("""
                select id,entity_code,entity_name,base_currency,fiscal_year_start_month,status
                from accounting_entity where status='ENABLED' order by id limit 1
                """, (rs, rowNum) -> new AccountingDtos.EntityView(
                rs.getLong("id"), rs.getString("entity_code"), rs.getString("entity_name"),
                rs.getString("base_currency"), rs.getInt("fiscal_year_start_month"), rs.getString("status")));
    }

    public AccountingDtos.DashboardView dashboard() {
        long entityId = entityId();
        AccountingDtos.PeriodView current = periodByDate(entityId, LocalDate.now(), false);
        long draft = scalarLong("select count(*) from accounting_voucher where entity_id=? and status='DRAFT'", entityId);
        long reviewed = scalarLong("select count(*) from accounting_voucher where entity_id=? and status='REVIEWED'", entityId);
        long posted = scalarLong("select count(*) from accounting_voucher where entity_id=? and status in ('POSTED','REVERSED')", entityId);
        long issues = scalarLong("select count(*) from accounting_generation_issue where entity_id=? and status='OPEN'", entityId);
        long missing = ungeneratedPostingCount(entityId);
        LocalDate from = current == null ? LocalDate.now().withDayOfMonth(1) : current.startDate();
        LocalDate to = current == null ? LocalDate.now() : current.endDate();
        return new AccountingDtos.DashboardView(entity(), current, draft, reviewed, posted, issues, missing,
                trialBalance(from, to).difference());
    }

    public List<AccountingDtos.AccountView> accounts(boolean includeDisabled) {
        long entityId = entityId();
        String status = includeDisabled ? "" : " and a.status='ENABLED'";
        return jdbc.query("""
                select a.*,exists(select 1 from accounting_voucher_line l where l.account_id=a.id) used_flag
                from accounting_account a where a.entity_id=?
                """ + status + " order by a.account_code", (rs, rowNum) -> new AccountingDtos.AccountView(
                rs.getLong("id"), rs.getLong("entity_id"), rs.getString("account_code"),
                rs.getString("account_name"), rs.getString("category"), rs.getString("normal_direction"),
                rs.getLong("parent_id"), rs.getInt("account_level"), rs.getInt("leaf") == 1,
                rs.getInt("allow_manual") == 1, rs.getString("partner_auxiliary"),
                rs.getString("product_auxiliary"), rs.getString("warehouse_auxiliary"),
                rs.getDate("effective_from").toLocalDate(),
                rs.getDate("effective_to") == null ? null : rs.getDate("effective_to").toLocalDate(),
                rs.getString("status"), rs.getInt("used_flag") == 1), entityId);
    }

    @Transactional
    public Long saveAccount(Long accountId, AccountingDtos.AccountSave request) {
        long entityId = entityId();
        String code = required(request.accountCode(), "科目编码");
        String name = required(request.accountName(), "科目名称");
        String category = enumValue(request.category(), List.of("ASSET", "LIABILITY", "EQUITY", "REVENUE", "EXPENSE", "COST"), "科目类别");
        String direction = enumValue(request.normalDirection(), List.of("DEBIT", "CREDIT"), "余额方向");
        String partnerAux = auxiliary(request.partnerAuxiliary());
        String productAux = auxiliary(request.productAuxiliary());
        String warehouseAux = auxiliary(request.warehouseAuxiliary());
        long parentId = id(request.parentId());
        int level = 1;
        if (accountId != null && accountId > 0 && parentId == accountId)
            throw new BusinessException("会计科目不能把自己设为上级科目");
        if (parentId > 0) {
            Map<String, Object> parent = row("""
                    select account_level,account_code,category,normal_direction
                    from accounting_account where id=? and entity_id=? and status='ENABLED'
                    """, parentId, entityId);
            if (parent == null) throw new BusinessException("上级科目不存在或已停用");
            if (!code.startsWith(String.valueOf(parent.get("account_code")))) throw new BusinessException("下级科目编码必须以上级科目编码开头");
            if (!category.equals(parent.get("category")) || !direction.equals(parent.get("normal_direction")))
                throw new BusinessException("下级科目的类别和余额方向必须与上级科目一致");
            level = ((Number) parent.get("account_level")).intValue() + 1;
        }
        String status = "DISABLED".equals(request.status()) ? "DISABLED" : "ENABLED";
        if (accountId == null || accountId <= 0) {
            Long id = insert("""
                    insert into accounting_account
                    (entity_id,account_code,account_name,category,normal_direction,parent_id,account_level,
                     leaf,allow_manual,partner_auxiliary,product_auxiliary,warehouse_auxiliary,
                     effective_from,effective_to,status)
                    values (?,?,?,?,?,?,?,1,?,?,?,?,?,?,?)
                    """, entityId, code, name, category, direction, parentId, level,
                    bool(request.allowManual()), partnerAux, productAux, warehouseAux,
                    Date.valueOf(request.effectiveFrom()), sqlDate(request.effectiveTo()), status);
            if (parentId > 0) jdbc.update("update accounting_account set leaf=0 where id=?", parentId);
            accountEvent(id, "CREATE", "新增科目 " + code + " " + name, actor());
            return id;
        }
        AccountLock existing = lockAccount(accountId, entityId);
        Map<String, Object> existingRow = row("select parent_id from accounting_account where id=?", accountId);
        long existingParentId = existingRow == null ? 0L : ((Number) existingRow.get("parent_id")).longValue();
        if (parentId > 0 && scalarLong("""
                with recursive descendants as (
                  select id from accounting_account where parent_id=?
                  union all
                  select a.id from accounting_account a join descendants d on a.parent_id=d.id
                ) select count(*) from descendants where id=?
                """, accountId, parentId) > 0)
            throw new BusinessException("上级科目不能选择当前科目的下级科目");
        boolean used = scalarLong("select count(*) from accounting_voucher_line where account_id=?", accountId) > 0;
        if (used && (!existing.code().equals(code) || !existing.category().equals(category)
                || !existing.direction().equals(direction))) {
            throw new BusinessException("已使用科目的编码、类别和余额方向不能修改；请停用后新增科目");
        }
        if ("DISABLED".equals(status)) {
            if (scalarLong("select count(*) from accounting_account where parent_id=? and status='ENABLED'", accountId) > 0)
                throw new BusinessException("仍有启用的下级科目，不能停用当前科目");
            if (scalarLong("""
                    select count(*) from accounting_rule_version where status='ENABLED'
                      and (debit_account_id=? or credit_account_id=?)
                    """, accountId, accountId) > 0)
                throw new BusinessException("科目仍被已发布自动制证规则使用，请先发布替代规则");
        }
        jdbc.update("""
                update accounting_account set account_code=?,account_name=?,category=?,normal_direction=?,
                  parent_id=?,account_level=?,allow_manual=?,partner_auxiliary=?,product_auxiliary=?,
                  warehouse_auxiliary=?,effective_from=?,effective_to=?,status=? where id=? and entity_id=?
                """, code, name, category, direction, parentId, level, bool(request.allowManual()),
                partnerAux, productAux, warehouseAux, Date.valueOf(request.effectiveFrom()),
                sqlDate(request.effectiveTo()), status, accountId, entityId);
        if (parentId > 0) jdbc.update("update accounting_account set leaf=0 where id=?", parentId);
        if (existingParentId > 0 && existingParentId != parentId) jdbc.update("""
                update accounting_account p set p.leaf=not exists(
                  select 1 from accounting_account c where c.parent_id=p.id)
                where p.id=?
                """, existingParentId);
        accountEvent(accountId, "UPDATE", "更新科目 " + code + " " + name + "；状态=" + status, actor());
        return accountId;
    }

    public List<AccountingDtos.PeriodView> periods(Integer fiscalYear) {
        long entityId = entityId();
        String filter = fiscalYear == null ? "" : " and fiscal_year=?";
        Object[] args = fiscalYear == null ? new Object[]{entityId} : new Object[]{entityId, fiscalYear};
        return jdbc.query("select * from accounting_period where entity_id=?" + filter + " order by start_date desc",
                periodMapper(), args);
    }

    @Transactional
    public int initializeYear(int fiscalYear) {
        if (fiscalYear < 2000 || fiscalYear > 2200) throw new BusinessException("会计年度无效");
        long entityId = entityId();
        int inserted = 0;
        for (int month = 1; month <= 12; month++) {
            YearMonth ym = YearMonth.of(fiscalYear, month);
            inserted += jdbc.update("""
                    insert ignore into accounting_period
                    (entity_id,fiscal_year,period_no,period_name,start_date,end_date,status)
                    values (?,?,?,?,?,?,'OPEN')
                    """, entityId, fiscalYear, month, fiscalYear + "年" + month + "月",
                    Date.valueOf(ym.atDay(1)), Date.valueOf(ym.atEndOfMonth()));
        }
        return inserted;
    }

    @Transactional
    public Long closePeriod(Long periodId, String reason) {
        PeriodLock period = lockPeriod(periodId);
        if ("CLOSED".equals(period.status())) return periodId;
        long pending = scalarLong("""
                select count(*) from accounting_voucher
                where entity_id=? and voucher_date between ? and ? and status in ('DRAFT','REVIEWED')
                """, period.entityId(), period.startDate(), period.endDate());
        if (pending > 0) throw new BusinessException("期间仍有暂存或已审核未记账凭证，不能锁定");
        if (ungeneratedPostingCount(period.entityId(), period.startDate(), period.endDate()) > 0)
            throw new BusinessException("期间仍有未生成会计凭证的业务过账，不能锁定");
        long pendingTaxInvoices = scalarLong("""
                select count(*) from tax_invoice
                where entity_id=? and invoice_date between ? and ?
                  and (status='DRAFT' or (invoice_color='RED' and red_confirmation_status='PENDING'))
                """, period.entityId(), period.startDate(), period.endDate());
        if (pendingTaxInvoices > 0)
            throw new BusinessException("期间仍有暂存发票或待确认红票，不能锁定");
        AccountingDtos.TrialBalanceReport trial = trialBalance(period.startDate(), period.endDate());
        if (trial.difference().compareTo(ZERO) != 0) throw new BusinessException("试算不平衡，不能锁定期间");
        Actor actor = actor();
        jdbc.update("""
                update accounting_period set status='CLOSED',close_reason=?,closed_by=?,closed_by_name=?,
                  closed_at=now() where id=? and status='OPEN'
                """, required(reason, "锁定原因"), actor.id(), actor.name(), periodId);
        periodEvent(periodId, "CLOSE", "OPEN", "CLOSED", reason, actor);
        return periodId;
    }

    @Transactional
    public Long reopenPeriod(Long periodId, String reason) {
        PeriodLock period = lockPeriod(periodId);
        if ("OPEN".equals(period.status())) return periodId;
        Actor actor = actor();
        jdbc.update("""
                update accounting_period set status='OPEN',close_reason='',reopened_at=now()
                where id=? and status='CLOSED'
                """, periodId);
        periodEvent(periodId, "REOPEN", "CLOSED", "OPEN", required(reason, "重新开放原因"), actor);
        return periodId;
    }

    public List<AccountingDtos.RuleView> rules() {
        long entityId = entityId();
        return jdbc.query("""
                select r.*,da.account_code debit_code,da.account_name debit_name,
                       ca.account_code credit_code,ca.account_name credit_name
                from accounting_rule_version r
                join accounting_account da on da.id=r.debit_account_id
                join accounting_account ca on ca.id=r.credit_account_id
                where r.entity_id=? order by r.rule_code,r.version_no desc
                """, ruleMapper(), entityId);
    }

    @Transactional
    public Long saveRule(AccountingDtos.RuleSave request) {
        long entityId = entityId();
        AccountLock debit = lockAccount(request.debitAccountId(), entityId);
        AccountLock credit = lockAccount(request.creditAccountId(), entityId);
        if (!"ENABLED".equals(debit.status()) || !"ENABLED".equals(credit.status()))
            throw new BusinessException("会计规则只能使用已启用科目");
        if (!debit.leaf() || !credit.leaf()) throw new BusinessException("会计规则只能使用末级科目");
        if (debit.id().equals(credit.id())) throw new BusinessException("借方和贷方科目不能相同");
        String ruleCode = required(request.ruleCode(), "规则编码");
        Integer version = jdbc.queryForObject("""
                select coalesce(max(version_no),0)+1 from accounting_rule_version
                where entity_id=? and rule_code=?
                """, Integer.class, entityId, ruleCode);
        return insert("""
                insert into accounting_rule_version
                (entity_id,rule_code,rule_name,source_type,posting_action,fact_type,fact_direction,
                 debit_account_id,credit_account_id,version_no,effective_from,status,change_reason)
                values (?,?,?,?,?,?,?,?,?,?,?,'DRAFT',?)
                """, entityId, ruleCode, required(request.ruleName(), "规则名称"),
                required(request.sourceType(), "来源类型"), required(request.postingAction(), "过账动作"),
                required(request.factType(), "业务事实"), enumValue(request.factDirection(),
                        List.of("INCREASE", "DECREASE"), "事实方向"),
                debit.id(), credit.id(), version, Date.valueOf(request.effectiveFrom()),
                required(request.changeReason(), "变更原因"));
    }

    @Transactional
    public Long publishRule(Long ruleId) {
        long entityId = entityId();
        Map<String, Object> rule = row("""
                select * from accounting_rule_version where id=? and entity_id=? for update
                """, ruleId, entityId);
        if (rule == null) throw new BusinessException("会计规则不存在");
        if ("ENABLED".equals(rule.get("status"))) return ruleId;
        if (!"DRAFT".equals(rule.get("status"))) throw new BusinessException("只有草稿规则可以发布");
        String ruleCode = String.valueOf(rule.get("rule_code"));
        LocalDate effectiveFrom = ((Date) rule.get("effective_from")).toLocalDate();
        jdbc.update("""
                update accounting_rule_version set effective_to=?,status='DISABLED'
                where entity_id=? and rule_code=? and status='ENABLED' and effective_from<?
                """, Date.valueOf(effectiveFrom.minusDays(1)), entityId, ruleCode, Date.valueOf(effectiveFrom));
        long conflicts = scalarLong("""
                select count(*) from accounting_rule_version
                where entity_id=? and status='ENABLED' and id<>?
                  and source_type=? and posting_action=? and fact_type=? and fact_direction=?
                  and (effective_to is null or effective_to>=?)
                """, entityId, ruleId, rule.get("source_type"), rule.get("posting_action"),
                rule.get("fact_type"), rule.get("fact_direction"), effectiveFrom);
        if (conflicts > 0) throw new BusinessException("同一业务事实在该生效日期已有其他已发布会计规则");
        Actor actor = actor();
        jdbc.update("""
                update accounting_rule_version set status='ENABLED',published_by=?,published_by_name=?,published_at=now()
                where id=? and status='DRAFT'
                """, actor.id(), actor.name(), ruleId);
        return ruleId;
    }

    public List<AccountingDtos.VoucherRow> vouchers(String status, LocalDate from, LocalDate to) {
        long entityId = entityId();
        StringBuilder sql = new StringBuilder("select * from accounting_voucher where entity_id=?");
        List<Object> args = new ArrayList<>();
        args.add(entityId);
        if (StringUtils.hasText(status)) { sql.append(" and status=?"); args.add(status.trim()); }
        if (from != null) { sql.append(" and voucher_date>=?"); args.add(Date.valueOf(from)); }
        if (to != null) { sql.append(" and voucher_date<=?"); args.add(Date.valueOf(to)); }
        sql.append(" order by voucher_date desc,id desc limit 1000");
        return jdbc.query(sql.toString(), voucherMapper(), args.toArray());
    }

    public AccountingDtos.VoucherDetail voucher(Long voucherId) {
        List<AccountingDtos.VoucherRow> vouchers = jdbc.query("select * from accounting_voucher where id=?", voucherMapper(), voucherId);
        if (vouchers.isEmpty()) throw new BusinessException("会计凭证不存在");
        List<AccountingDtos.VoucherLineView> lines = jdbc.query("""
                select l.*,coalesce(p.name,'') partner_name,coalesce(pr.name,'') product_name,
                       coalesce(w.name,'') warehouse_name
                from accounting_voucher_line l
                left join partner p on p.id=l.partner_id
                left join product pr on pr.id=l.product_id
                left join warehouse w on w.id=l.warehouse_id
                where l.voucher_id=? order by l.line_no,l.id
                """, voucherLineMapper(), voucherId);
        List<AccountingDtos.VoucherEventView> events = jdbc.query("""
                select * from accounting_voucher_event where voucher_id=? order by id
                """, (rs, rowNum) -> new AccountingDtos.VoucherEventView(
                rs.getLong("id"), rs.getLong("voucher_id"), rs.getString("action"),
                rs.getString("from_status"), rs.getString("to_status"), rs.getString("reason"),
                rs.getLong("operator_id"), rs.getString("operator_name"),
                rs.getTimestamp("created_at").toLocalDateTime()), voucherId);
        List<AccountingDtos.LinkedTaxInvoiceView> linkedTaxInvoices = jdbc.query("""
                select i.id,i.internal_no,i.direction,i.invoice_color,i.invoice_number,
                       i.tax_amount,i.status,r.matching_basis
                from tax_invoice_relation r join tax_invoice i on i.id=r.invoice_id
                where r.source_type='ACCOUNTING_VOUCHER' and r.source_id=? and r.status='ACTIVE'
                order by i.invoice_date,i.id
                """, (rs, rowNum) -> new AccountingDtos.LinkedTaxInvoiceView(
                rs.getLong("id"), rs.getString("internal_no"), rs.getString("direction"),
                rs.getString("invoice_color"), rs.getString("invoice_number"),
                rs.getBigDecimal("tax_amount"), rs.getString("status"),
                rs.getString("matching_basis")), voucherId);
        return new AccountingDtos.VoucherDetail(vouchers.get(0), lines, events, linkedTaxInvoices);
    }

    @Transactional
    public Long createManualVoucher(AccountingDtos.VoucherSave request) {
        return saveManualVoucher(null, request);
    }

    @Transactional
    public Long updateManualVoucher(Long voucherId, AccountingDtos.VoucherSave request) {
        return saveManualVoucher(voucherId, request);
    }

    private Long saveManualVoucher(Long voucherId, AccountingDtos.VoucherSave request) {
        long entityId = entityId();
        PeriodLock period = requiredOpenPeriod(entityId, request.voucherDate());
        PreparedVoucher prepared = prepareManualLines(entityId, request.voucherDate(), request.lines());
        Actor actor = actor();
        if (voucherId == null || voucherId <= 0) {
            voucherId = insertVoucher(entityId, period.id(), "MANUAL", request.voucherDate(),
                    required(request.summary(), "凭证摘要"), "", 0L, "", null, 0L,
                    prepared.debitTotal(), prepared.creditTotal(), actor);
            insertManualLines(voucherId, prepared.lines());
            updateVoucherHash(voucherId);
            voucherEvent(voucherId, "CREATE", "", "DRAFT", "手工新增", actor);
            return voucherId;
        }
        VoucherLock existing = lockVoucher(voucherId);
        if (!"MANUAL".equals(existing.type()) || !"DRAFT".equals(existing.status()))
            throw new BusinessException("只有暂存的手工凭证可以修改");
        jdbc.update("""
                update accounting_voucher set period_id=?,voucher_date=?,summary=?,debit_total=?,credit_total=?
                where id=? and status='DRAFT'
                """, period.id(), Date.valueOf(request.voucherDate()), required(request.summary(), "凭证摘要"),
                prepared.debitTotal(), prepared.creditTotal(), voucherId);
        jdbc.update("delete from accounting_voucher_line where voucher_id=?", voucherId);
        insertManualLines(voucherId, prepared.lines());
        updateVoucherHash(voucherId);
        voucherEvent(voucherId, "UPDATE", "DRAFT", "DRAFT", "修改暂存凭证", actor);
        return voucherId;
    }

    @Transactional
    public Long reviewVoucher(Long voucherId, String reason) {
        VoucherLock voucher = lockVoucher(voucherId);
        if ("REVIEWED".equals(voucher.status())) return voucherId;
        if (!"DRAFT".equals(voucher.status())) throw new BusinessException("只有暂存凭证可以审核");
        requiredOpenPeriod(voucher.entityId(), voucher.date());
        validateStoredVoucher(voucherId);
        Actor actor = actor();
        jdbc.update("""
                update accounting_voucher set status='REVIEWED',reviewer_id=?,reviewer_name=?,reviewed_at=now()
                where id=? and status='DRAFT'
                """, actor.id(), actor.name(), voucherId);
        voucherEvent(voucherId, "REVIEW", "DRAFT", "REVIEWED", text(reason), actor);
        return voucherId;
    }

    @Transactional
    public Long rejectVoucher(Long voucherId, String reason) {
        VoucherLock voucher = lockVoucher(voucherId);
        if (!"REVIEWED".equals(voucher.status())) throw new BusinessException("只有已审核未记账凭证可以退回");
        Actor actor = actor();
        jdbc.update("""
                update accounting_voucher set status='DRAFT',reviewer_id=0,reviewer_name='',reviewed_at=null
                where id=? and status='REVIEWED'
                """, voucherId);
        voucherEvent(voucherId, "REJECT", "REVIEWED", "DRAFT", required(reason, "退回原因"), actor);
        return voucherId;
    }

    @Transactional
    public Long postVoucher(Long voucherId) {
        VoucherLock voucher = lockVoucher(voucherId);
        if (List.of("POSTED", "REVERSED").contains(voucher.status())) return voucherId;
        if (!"REVIEWED".equals(voucher.status())) throw new BusinessException("凭证必须先审核再记账");
        requiredOpenPeriod(voucher.entityId(), voucher.date());
        validateStoredVoucher(voucherId);
        Actor actor = actor();
        jdbc.update("""
                insert into accounting_ledger_entry
                (entity_id,period_id,voucher_id,voucher_line_id,voucher_no,voucher_date,
                 account_id,account_code,account_name,summary,debit_amount,credit_amount,
                 partner_id,product_id,warehouse_id)
                select v.entity_id,v.period_id,v.id,l.id,v.voucher_no,v.voucher_date,
                       l.account_id,l.account_code_snapshot,l.account_name_snapshot,l.summary,
                       l.debit_amount,l.credit_amount,l.partner_id,l.product_id,l.warehouse_id
                from accounting_voucher v join accounting_voucher_line l on l.voucher_id=v.id
                where v.id=?
                """, voucherId);
        jdbc.update("""
                update accounting_voucher set status='POSTED',poster_id=?,poster_name=?,posted_at=now()
                where id=? and status='REVIEWED'
                """, actor.id(), actor.name(), voucherId);
        voucherEvent(voucherId, "POST", "REVIEWED", "POSTED", "记账", actor);
        if (voucher.originalVoucherId() > 0) {
            jdbc.update("""
                    update accounting_voucher set status='REVERSED',reversal_voucher_id=?,reversed_at=now()
                    where id=? and status='POSTED'
                    """, voucherId, voucher.originalVoucherId());
            voucherEvent(voucher.originalVoucherId(), "REVERSE", "POSTED", "REVERSED",
                    "冲销凭证 " + voucher.no(), actor);
        }
        return voucherId;
    }

    @Transactional
    public Long cancelVoucher(Long voucherId, String reason) {
        VoucherLock voucher = lockVoucher(voucherId);
        if ("CANCELLED".equals(voucher.status())) return voucherId;
        if (!List.of("DRAFT", "REVIEWED").contains(voucher.status()))
            throw new BusinessException("只有未记账凭证可以作废");
        Actor actor = actor();
        jdbc.update("update accounting_voucher set status='CANCELLED',cancel_reason=? where id=?",
                required(reason, "作废原因"), voucherId);
        voucherEvent(voucherId, "CANCEL", voucher.status(), "CANCELLED", reason, actor);
        return voucherId;
    }

    /**
     * Creates an auditable draft voucher for a non-business-posting source such as a tax invoice.
     * The caller owns the source state machine; accounting still owns review, posting and reversal.
     */
    @Transactional
    public Long createSourceVoucher(String sourceType, long sourceId, String sourceNo,
                                    LocalDate voucherDate, String summary,
                                    List<SystemVoucherLine> requestedLines) {
        long entityId = entityId();
        List<Long> existing = jdbc.queryForList("""
                select id from accounting_voucher
                where entity_id=? and source_type=? and source_id=? and source_no=?
                  and original_voucher_id=0 and status<>'CANCELLED'
                order by id desc limit 1
                """, Long.class, entityId, required(sourceType, "凭证来源类型"), sourceId,
                required(sourceNo, "来源单号"));
        if (!existing.isEmpty()) return existing.get(0);
        if (requestedLines == null || requestedLines.size() < 2)
            throw new BusinessException("系统来源凭证至少需要两条分录");
        PeriodLock period = requiredOpenPeriod(entityId, voucherDate);
        List<PreparedSystemLine> lines = new ArrayList<>();
        BigDecimal debitTotal = ZERO;
        BigDecimal creditTotal = ZERO;
        for (SystemVoucherLine requested : requestedLines) {
            List<Long> ids = jdbc.queryForList("""
                    select id from accounting_account
                    where entity_id=? and account_code=? and status='ENABLED' and leaf=1
                      and effective_from<=? and (effective_to is null or effective_to>=?)
                    """, Long.class, entityId, required(requested.accountCode(), "科目编码"),
                    Date.valueOf(voucherDate), Date.valueOf(voucherDate));
            if (ids.isEmpty()) throw new BusinessException("会计科目 " + requested.accountCode() + " 不存在、未生效或已停用");
            AccountLock account = lockAccount(ids.get(0), entityId);
            BigDecimal debit = money(requested.debitAmount());
            BigDecimal credit = money(requested.creditAmount());
            if ((debit.compareTo(ZERO) > 0) == (credit.compareTo(ZERO) > 0))
                throw new BusinessException("系统来源凭证每条分录必须且只能填写借方或贷方金额");
            long partnerId = id(requested.partnerId());
            long productId = id(requested.productId());
            long warehouseId = id(requested.warehouseId());
            validateAuxiliary(account.lineAccount(), partnerId, productId, warehouseId);
            lines.add(new PreparedSystemLine(account, required(requested.summary(), "分录摘要"),
                    debit, credit, partnerId, productId, warehouseId));
            debitTotal = debitTotal.add(debit);
            creditTotal = creditTotal.add(credit);
        }
        debitTotal = debitTotal.setScale(2);
        creditTotal = creditTotal.setScale(2);
        if (debitTotal.compareTo(ZERO) <= 0 || debitTotal.compareTo(creditTotal) != 0)
            throw new BusinessException("系统来源凭证借贷合计必须相等且大于0");
        Actor actor = actor();
        Long voucherId = insertVoucher(entityId, period.id(), "AUTO", voucherDate,
                required(summary, "凭证摘要"), sourceType, sourceId, sourceNo,
                null, 0L, debitTotal, creditTotal, actor);
        int lineNo = 1;
        for (PreparedSystemLine line : lines) {
            jdbc.update("""
                    insert into accounting_voucher_line
                    (voucher_id,line_no,account_id,account_code_snapshot,account_name_snapshot,summary,
                     debit_amount,credit_amount,partner_id,product_id,warehouse_id,
                     source_line_type,source_line_id)
                    values (?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, voucherId, lineNo++, line.account().id(), line.account().code(), line.account().name(),
                    line.summary(), line.debit(), line.credit(), line.partnerId(), line.productId(),
                    line.warehouseId(), sourceType, sourceId);
        }
        updateVoucherHash(voucherId);
        voucherEvent(voucherId, "SOURCE_GENERATE", "", "DRAFT", "来源 " + sourceType + " " + sourceNo, actor);
        return voucherId;
    }

    @Transactional
    public Long disposeSourceVoucher(Long voucherId, LocalDate reversalDate, String reason) {
        if (voucherId == null || voucherId <= 0) return 0L;
        VoucherLock voucher = lockVoucher(voucherId);
        if ("CANCELLED".equals(voucher.status())) return voucherId;
        if ("REVERSED".equals(voucher.status())) return voucher.reversalVoucherId();
        if (List.of("DRAFT", "REVIEWED").contains(voucher.status())) return cancelVoucher(voucherId, reason);
        if ("POSTED".equals(voucher.status())) {
            return reverseVoucher(voucherId, new AccountingDtos.VoucherAction(reason, reversalDate));
        }
        throw new BusinessException("来源凭证当前状态不能作废或冲销");
    }

    @Transactional
    public Long reverseVoucher(Long voucherId, AccountingDtos.VoucherAction request) {
        VoucherLock original = lockVoucher(voucherId);
        if ("REVERSED".equals(original.status())) return original.reversalVoucherId();
        if (!"POSTED".equals(original.status())) throw new BusinessException("只有已记账凭证可以冲销");
        if (original.reversalVoucherId() > 0) {
            List<Long> pending = jdbc.queryForList("""
                    select id from accounting_voucher where id=? and original_voucher_id=? and status<>'CANCELLED'
                    """, Long.class, original.reversalVoucherId(), original.id());
            if (!pending.isEmpty()) return pending.get(0);
        }
        LocalDate date = request.voucherDate() == null ? LocalDate.now() : request.voucherDate();
        return createReversalVoucher(original, null, date, required(request.reason(), "冲销原因"));
    }

    public List<Long> missingBusinessPostingBatchIds() {
        long entityId = entityId();
        return jdbc.queryForList("""
                select b.id from business_posting_batch b
                where ((b.status='POSTED' and b.posting_action not in ('COMMITMENT_POST','COMMITMENT_REVERSE'))
                    or (b.status='REVERSED' and b.reversal_batch_id>0
                        and b.posting_action not in ('COMMITMENT_POST','COMMITMENT_REVERSE')))
                  and not exists(select 1 from accounting_generation_result g
                    where g.entity_id=? and g.business_posting_batch_id=b.id)
                order by case when b.status='REVERSED' then 0 when b.posting_action='REVERSE' then 2 else 1 end,b.id
                """, Long.class, entityId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordGenerationFailure(Long batchId, RuntimeException exception) {
        recordGenerationIssue(entityId(), batchId, issueCode(exception), safeMessage(exception));
    }

    @Transactional
    public GenerationOutcome generateForBusinessPosting(Long batchId) {
        long entityId = entityId();
        BusinessBatch batch = lockBusinessBatch(batchId);
        boolean linkedReversedOriginal = "REVERSED".equals(batch.status()) && batch.reversalBatchId() > 0
                && !"REVERSE".equals(batch.action());
        if (!"POSTED".equals(batch.status()) && !linkedReversedOriginal)
            return new GenerationOutcome(List.of(), 0);
        if (List.of("COMMITMENT_POST", "COMMITMENT_REVERSE").contains(batch.action()))
            return new GenerationOutcome(List.of(), 0);
        if (batch.originalBatchId() > 0 || "REVERSE".equals(batch.action())) {
            return generateBusinessReversal(entityId, batch);
        }
        List<Long> existing = jdbc.queryForList("""
                select id from accounting_voucher where entity_id=? and business_posting_batch_id=?
                  and original_voucher_id=0 and status<>'CANCELLED'
                """, Long.class, entityId, batch.id());
        if (!existing.isEmpty()) {
            markGenerationResult(entityId, batch.id(), "VOUCHER_CREATED", existing.size(), "已存在来源凭证");
            return new GenerationOutcome(existing, 0);
        }
        PeriodLock period = requiredOpenPeriod(entityId, batch.businessDate());
        int expectedMappedFacts = coverageService.validateBusinessBatch(entityId, batch.id(),
                batch.sourceType(), batch.action(), batch.businessDate());
        List<MappedFact> facts = mappedFacts(entityId, batch);
        if (facts.size() != expectedMappedFacts) {
            throw new BusinessException("会计规则覆盖校验失败，已阻止生成不完整凭证：应制证事实 "
                    + expectedMappedFacts + " 条，实际匹配 " + facts.size() + " 条");
        }
        if (facts.isEmpty()) throw new BusinessException("未找到适用于该业务过账的会计规则");
        Actor actor = actor();
        BigDecimal total = facts.stream().map(MappedFact::amount).reduce(ZERO, BigDecimal::add);
        Long voucherId = insertVoucher(entityId, period.id(), "AUTO", batch.businessDate(),
                batch.sourceNo() + " 自动制证", batch.sourceType(), batch.sourceId(), batch.sourceNo(),
                batch.id(), 0L, total, total, actor);
        int lineNo = 1;
        for (MappedFact fact : facts) {
            insertVoucherLine(voucherId, lineNo++, fact.debit(), fact.entry(), fact.amount(), ZERO,
                    fact.entry().memo());
            insertVoucherLine(voucherId, lineNo++, fact.credit(), fact.entry(), ZERO, fact.amount(),
                    fact.entry().memo());
        }
        updateVoucherHash(voucherId);
        voucherEvent(voucherId, "AUTO_GENERATE", "", "DRAFT", "来源业务过账 " + batch.postingNo(), actor);
        markGenerationResult(entityId, batch.id(), "VOUCHER_CREATED", 1, "自动生成凭证 " + voucherId);
        resolveGenerationIssue(entityId, batch.id(), voucherId);
        return new GenerationOutcome(List.of(voucherId), 0);
    }

    private GenerationOutcome generateBusinessReversal(long entityId, BusinessBatch batch) {
        List<VoucherLock> originals = jdbc.query("""
                select v.* from accounting_voucher v
                join business_posting_batch b on b.id=v.business_posting_batch_id
                where v.entity_id=? and b.reversal_batch_id=? and v.original_voucher_id=0
                  and v.status in ('DRAFT','REVIEWED','POSTED') order by v.id for update
                """, voucherLockMapper(), entityId, batch.id());
        if (originals.isEmpty()) throw new BusinessException("被冲回业务尚无可处理的会计凭证");
        List<Long> generated = new ArrayList<>();
        int cancelled = 0;
        Actor actor = actor();
        for (VoucherLock original : originals) {
            if (List.of("DRAFT", "REVIEWED").contains(original.status())) {
                jdbc.update("update accounting_voucher set status='CANCELLED',cancel_reason=? where id=?",
                        "来源业务已冲回 " + batch.postingNo(), original.id());
                voucherEvent(original.id(), "SOURCE_REVERSE_CANCEL", original.status(), "CANCELLED",
                        "来源业务已冲回", actor);
                cancelled++;
                continue;
            }
            List<Long> existing = jdbc.queryForList("""
                    select id from accounting_voucher where business_posting_batch_id=?
                      and original_voucher_id=? and status<>'CANCELLED'
                    """, Long.class, batch.id(), original.id());
            if (!existing.isEmpty()) { generated.add(existing.get(0)); continue; }
            generated.add(createReversalVoucher(original, batch.id(), batch.businessDate(),
                    "来源业务冲回 " + batch.sourceNo()));
        }
        markGenerationResult(entityId, batch.id(), generated.isEmpty() ? "SOURCE_VOUCHER_CANCELLED" : "VOUCHER_CREATED",
                generated.size(), generated.isEmpty() ? "来源未记账凭证已作废，无需冲销总账" : "已生成来源冲销凭证");
        resolveGenerationIssue(entityId, batch.id(), generated.isEmpty() ? 0L : generated.get(0));
        return new GenerationOutcome(generated, cancelled);
    }

    private Long createReversalVoucher(VoucherLock original, Long businessBatchId,
                                        LocalDate date, String reason) {
        PeriodLock period = requiredOpenPeriod(original.entityId(), date);
        Actor actor = actor();
        Long reversalId = insertVoucher(original.entityId(), period.id(), "REVERSAL", date,
                "冲销 " + original.no() + "：" + reason, original.sourceType(), original.sourceId(),
                original.sourceNo(), businessBatchId, original.id(), original.creditTotal(),
                original.debitTotal(), actor);
        jdbc.update("""
                insert into accounting_voucher_line
                (voucher_id,line_no,account_id,account_code_snapshot,account_name_snapshot,summary,
                 debit_amount,credit_amount,partner_id,product_id,warehouse_id,
                 source_posting_entry_id,source_line_type,source_line_id)
                select ?,line_no,account_id,account_code_snapshot,account_name_snapshot,concat('冲销：',summary),
                       credit_amount,debit_amount,partner_id,product_id,warehouse_id,
                       source_posting_entry_id,source_line_type,source_line_id
                from accounting_voucher_line where voucher_id=? order by line_no
                """, reversalId, original.id());
        updateVoucherHash(reversalId);
        jdbc.update("update accounting_voucher set reversal_voucher_id=? where id=?", reversalId, original.id());
        voucherEvent(reversalId, "CREATE_REVERSAL", "", "DRAFT", reason, actor);
        return reversalId;
    }

    public List<AccountingDtos.GenerationIssueView> generationIssues(String status) {
        long entityId = entityId();
        String filter = StringUtils.hasText(status) ? " and i.status=?" : "";
        Object[] args = StringUtils.hasText(status) ? new Object[]{entityId, status.trim()} : new Object[]{entityId};
        return jdbc.query("""
                select i.*,b.posting_no,b.source_type,b.source_id,b.source_no
                from accounting_generation_issue i join business_posting_batch b on b.id=i.business_posting_batch_id
                where i.entity_id=?
                """ + filter + " order by i.id desc", (rs, rowNum) -> new AccountingDtos.GenerationIssueView(
                rs.getLong("id"), rs.getLong("business_posting_batch_id"), rs.getString("posting_no"),
                rs.getString("source_type"), rs.getLong("source_id"), rs.getString("source_no"),
                rs.getString("issue_code"), rs.getString("issue_message"), rs.getString("status"),
                rs.getInt("retry_count"), rs.getLong("resolved_voucher_id"),
                rs.getTimestamp("last_attempt_at").toLocalDateTime(),
                rs.getTimestamp("resolved_at") == null ? null : rs.getTimestamp("resolved_at").toLocalDateTime(),
                rs.getTimestamp("created_at").toLocalDateTime()), args);
    }

    @Transactional
    public Long retryGeneration(Long issueId) {
        Map<String, Object> issue = row("""
                select * from accounting_generation_issue where id=? and entity_id=? for update
                """, issueId, entityId());
        if (issue == null) throw new BusinessException("制证问题不存在");
        Long batchId = ((Number) issue.get("business_posting_batch_id")).longValue();
        GenerationOutcome outcome = generateForBusinessPosting(batchId);
        return outcome.voucherIds().isEmpty() ? 0L : outcome.voucherIds().get(0);
    }

    public AccountingDtos.TrialBalanceReport trialBalance(LocalDate from, LocalDate to) {
        long entityId = entityId();
        LocalDate start = from == null ? LocalDate.now().withDayOfMonth(1) : from;
        LocalDate end = to == null ? LocalDate.now() : to;
        if (start.isAfter(end)) throw new BusinessException("账簿开始日期不能晚于结束日期");
        List<AccountingDtos.GeneralLedgerRow> rows = jdbc.query("""
                select a.id,a.account_code,a.account_name,a.category,a.normal_direction,
                       coalesce(sum(case when l.voucher_date<? then l.debit_amount else 0 end),0) opening_debit_raw,
                       coalesce(sum(case when l.voucher_date<? then l.credit_amount else 0 end),0) opening_credit_raw,
                       coalesce(sum(case when l.voucher_date between ? and ? then l.debit_amount else 0 end),0) period_debit,
                       coalesce(sum(case when l.voucher_date between ? and ? then l.credit_amount else 0 end),0) period_credit
                from accounting_account a left join accounting_ledger_entry l
                  on l.account_id=a.id and l.entity_id=a.entity_id and l.voucher_date<=?
                where a.entity_id=? group by a.id,a.account_code,a.account_name,a.category,a.normal_direction
                order by a.account_code
                """, (rs, rowNum) -> ledgerRow(rs.getLong("id"), rs.getString("account_code"),
                rs.getString("account_name"), rs.getString("category"), rs.getString("normal_direction"),
                rs.getBigDecimal("opening_debit_raw"), rs.getBigDecimal("opening_credit_raw"),
                rs.getBigDecimal("period_debit"), rs.getBigDecimal("period_credit")),
                start, start, start, end, start, end, end, entityId);
        BigDecimal openingDebit = sum(rows, AccountingDtos.GeneralLedgerRow::openingDebit);
        BigDecimal openingCredit = sum(rows, AccountingDtos.GeneralLedgerRow::openingCredit);
        BigDecimal periodDebit = sum(rows, AccountingDtos.GeneralLedgerRow::periodDebit);
        BigDecimal periodCredit = sum(rows, AccountingDtos.GeneralLedgerRow::periodCredit);
        BigDecimal closingDebit = sum(rows, AccountingDtos.GeneralLedgerRow::closingDebit);
        BigDecimal closingCredit = sum(rows, AccountingDtos.GeneralLedgerRow::closingCredit);
        return new AccountingDtos.TrialBalanceReport(rows, openingDebit, openingCredit, periodDebit,
                periodCredit, closingDebit, closingCredit, closingDebit.subtract(closingCredit).setScale(2));
    }

    public List<AccountingDtos.LedgerEntryView> ledgerEntries(Long accountId, LocalDate from, LocalDate to,
                                                               Long partnerId, Long productId, Long warehouseId) {
        long entityId = entityId();
        StringBuilder inner = new StringBuilder("""
                select l.*,
                       sum(l.debit_amount-l.credit_amount) over(
                         partition by l.account_id order by l.voucher_date,l.id) running_balance
                from accounting_ledger_entry l where l.entity_id=?
                """);
        List<Object> args = new ArrayList<>(); args.add(entityId);
        if (accountId != null && accountId > 0) { inner.append(" and l.account_id=?"); args.add(accountId); }
        if (to != null) { inner.append(" and l.voucher_date<=?"); args.add(Date.valueOf(to)); }
        if (partnerId != null && partnerId > 0) { inner.append(" and l.partner_id=?"); args.add(partnerId); }
        if (productId != null && productId > 0) { inner.append(" and l.product_id=?"); args.add(productId); }
        if (warehouseId != null && warehouseId > 0) { inner.append(" and l.warehouse_id=?"); args.add(warehouseId); }
        StringBuilder sql = new StringBuilder("""
                select q.*,coalesce(p.name,'') partner_name,coalesce(pr.name,'') product_name,
                       coalesce(w.name,'') warehouse_name
                from (
                """).append(inner).append("""
                ) q
                left join partner p on p.id=q.partner_id
                left join product pr on pr.id=q.product_id
                left join warehouse w on w.id=q.warehouse_id
                where 1=1
                """);
        if (from != null) { sql.append(" and q.voucher_date>=?"); args.add(Date.valueOf(from)); }
        sql.append(" order by q.voucher_date,q.id limit 5000");
        return jdbc.query(sql.toString(), (rs, rowNum) -> new AccountingDtos.LedgerEntryView(
                rs.getLong("id"), rs.getLong("voucher_id"), rs.getString("voucher_no"),
                rs.getDate("voucher_date").toLocalDate(), rs.getLong("account_id"),
                rs.getString("account_code"), rs.getString("account_name"), rs.getString("summary"),
                rs.getBigDecimal("debit_amount"), rs.getBigDecimal("credit_amount"),
                rs.getBigDecimal("running_balance"), rs.getLong("partner_id"), rs.getString("partner_name"),
                rs.getLong("product_id"), rs.getString("product_name"), rs.getLong("warehouse_id"),
                rs.getString("warehouse_name")), args.toArray());
    }

    private List<MappedFact> mappedFacts(long entityId, BusinessBatch batch) {
        AccountForLine settlementAccount = settlementAccount(entityId, batch);
        return jdbc.query("""
                select e.id entry_id,e.fact_type,e.direction,e.partner_id,e.product_id,e.warehouse_id,
                       e.inventory_batch_id,e.source_line_type,e.source_line_id,e.amount,e.memo,
                       da.id debit_id,da.account_code debit_code,da.account_name debit_name,
                       da.partner_auxiliary debit_partner_aux,da.product_auxiliary debit_product_aux,
                       da.warehouse_auxiliary debit_warehouse_aux,da.status debit_status,
                       ca.id credit_id,ca.account_code credit_code,ca.account_name credit_name,
                       ca.partner_auxiliary credit_partner_aux,ca.product_auxiliary credit_product_aux,
                       ca.warehouse_auxiliary credit_warehouse_aux,ca.status credit_status
                from business_posting_entry e
                join accounting_rule_version r on r.entity_id=? and r.source_type=? and r.posting_action=?
                  and r.fact_type=e.fact_type and r.fact_direction=e.direction and r.status='ENABLED'
                  and r.effective_from<=? and (r.effective_to is null or r.effective_to>=?)
                join accounting_account da on da.id=r.debit_account_id and (da.leaf=1 or da.account_code='1002')
                  and da.effective_from<=? and (da.effective_to is null or da.effective_to>=?)
                join accounting_account ca on ca.id=r.credit_account_id and (ca.leaf=1 or ca.account_code='1002')
                  and ca.effective_from<=? and (ca.effective_to is null or ca.effective_to>=?)
                where e.posting_batch_id=? and e.amount<>0 order by e.line_no,r.id desc
                """, (rs, rowNum) -> {
            BusinessEntry entry = new BusinessEntry(rs.getLong("entry_id"), rs.getString("fact_type"),
                    rs.getString("direction"), rs.getLong("partner_id"), rs.getLong("product_id"),
                    rs.getLong("warehouse_id"), rs.getLong("inventory_batch_id"),
                    rs.getString("source_line_type"), rs.getLong("source_line_id"),
                    rs.getBigDecimal("amount").abs().setScale(2, RoundingMode.HALF_UP), rs.getString("memo"));
            AccountForLine debit = new AccountForLine(rs.getLong("debit_id"), rs.getString("debit_code"),
                    rs.getString("debit_name"), rs.getString("debit_partner_aux"),
                    rs.getString("debit_product_aux"), rs.getString("debit_warehouse_aux"), rs.getString("debit_status"));
            AccountForLine credit = new AccountForLine(rs.getLong("credit_id"), rs.getString("credit_code"),
                    rs.getString("credit_name"), rs.getString("credit_partner_aux"),
                    rs.getString("credit_product_aux"), rs.getString("credit_warehouse_aux"), rs.getString("credit_status"));
            if (settlementAccount != null && "1002".equals(debit.code())) debit = settlementAccount;
            if (settlementAccount != null && "1002".equals(credit.code())) credit = settlementAccount;
            validateAuxiliary(debit, entry.partnerId(), entry.productId(), entry.warehouseId());
            validateAuxiliary(credit, entry.partnerId(), entry.productId(), entry.warehouseId());
            return new MappedFact(entry, debit, credit, entry.amount());
        }, entityId, batch.sourceType(), batch.action(), batch.businessDate(), batch.businessDate(),
                batch.businessDate(), batch.businessDate(), batch.businessDate(), batch.businessDate(), batch.id());
    }

    private AccountForLine settlementAccount(long entityId, BusinessBatch batch) {
        if (!Set.of("PAYMENT", "SUPPLIER_PAYMENT", "CUSTOMER_ADVANCE_REFUND", "ACCEPTANCE_EVENT")
                .contains(batch.sourceType())) return null;
        Map<String, Object> source = switch (batch.sourceType()) {
            case "PAYMENT" -> row("select payment_method method,business_bank_account_id bank_id from payment where id=?",
                    batch.sourceId());
            case "SUPPLIER_PAYMENT" -> row("select payment_method method,business_bank_account_id bank_id from supplier_payment where id=?",
                    batch.sourceId());
            case "CUSTOMER_ADVANCE_REFUND" -> row("select refund_method method,business_bank_account_id bank_id from customer_advance_refund where id=?",
                    batch.sourceId());
            case "ACCEPTANCE_EVENT" -> row("select 'BANK' method,business_bank_account_id bank_id from acceptance_bill_event where id=? and cash_amount<>0",
                    batch.sourceId());
            default -> null;
        };
        if (source == null) return null;
        String method = String.valueOf(source.get("method"));
        if ("CASH".equals(method)) return accountForSettlement(entityId, "1001", batch.businessDate());
        if (!"BANK".equals(method)) return null;
        Object bankId = source.get("bank_id");
        if (!(bankId instanceof Number)) throw new BusinessException("银行收付未指定企业银行账户");
        List<AccountForLine> accounts = jdbc.query("""
                select a.id,a.account_code,a.account_name,a.partner_auxiliary,a.product_auxiliary,
                       a.warehouse_auxiliary,a.status
                from business_bank_account b join accounting_account a on a.id=b.ledger_account_id
                where b.id=? and b.entity_id=? and a.entity_id=? and a.leaf=1
                  and a.effective_from<=? and (a.effective_to is null or a.effective_to>=?)
                """, accountForLineMapper(), ((Number) bankId).longValue(), entityId, entityId,
                Date.valueOf(batch.businessDate()), Date.valueOf(batch.businessDate()));
        if (accounts.isEmpty()) throw new BusinessException("企业银行账户未关联业务日期有效的末级银行存款科目");
        return accounts.get(0);
    }

    private AccountForLine accountForSettlement(long entityId, String accountCode, LocalDate date) {
        List<AccountForLine> accounts = jdbc.query("""
                select id,account_code,account_name,partner_auxiliary,product_auxiliary,warehouse_auxiliary,status
                from accounting_account where entity_id=? and account_code=? and leaf=1 and status='ENABLED'
                  and effective_from<=? and (effective_to is null or effective_to>=?)
                """, accountForLineMapper(), entityId, accountCode, Date.valueOf(date), Date.valueOf(date));
        if (accounts.isEmpty()) throw new BusinessException("结算科目 " + accountCode + " 不存在或在业务日期不可用");
        return accounts.get(0);
    }

    private org.springframework.jdbc.core.RowMapper<AccountForLine> accountForLineMapper() {
        return (rs, rowNum) -> new AccountForLine(rs.getLong("id"), rs.getString("account_code"),
                rs.getString("account_name"), rs.getString("partner_auxiliary"),
                rs.getString("product_auxiliary"), rs.getString("warehouse_auxiliary"), rs.getString("status"));
    }

    private PreparedVoucher prepareManualLines(long entityId, LocalDate voucherDate,
                                                List<AccountingDtos.VoucherLineSave> lines) {
        if (lines == null || lines.size() < 2) throw new BusinessException("会计凭证至少需要两条分录");
        List<PreparedManualLine> prepared = new ArrayList<>();
        BigDecimal debitTotal = ZERO;
        BigDecimal creditTotal = ZERO;
        for (AccountingDtos.VoucherLineSave line : lines) {
            AccountLock account = lockAccount(line.accountId(), entityId);
            if (!"ENABLED".equals(account.status()) || !account.leaf()) throw new BusinessException("凭证只能使用已启用的末级科目");
            if (!account.allowManual()) throw new BusinessException("科目 " + account.code() + " 不允许手工录入");
            if (scalarLong("""
                    select count(*) from accounting_account where id=? and effective_from<=?
                      and (effective_to is null or effective_to>=?)
                    """, account.id(), voucherDate, voucherDate) == 0)
                throw new BusinessException("科目 " + account.code() + " 在凭证日期尚未生效或已经失效");
            BigDecimal debit = money(line.debitAmount());
            BigDecimal credit = money(line.creditAmount());
            if ((debit.compareTo(ZERO) > 0) == (credit.compareTo(ZERO) > 0))
                throw new BusinessException("每条分录必须且只能填写借方或贷方金额");
            validateAuxiliary(account.lineAccount(), id(line.partnerId()), id(line.productId()), id(line.warehouseId()));
            prepared.add(new PreparedManualLine(account, required(line.summary(), "分录摘要"), debit, credit,
                    id(line.partnerId()), id(line.productId()), id(line.warehouseId())));
            debitTotal = debitTotal.add(debit); creditTotal = creditTotal.add(credit);
        }
        debitTotal = debitTotal.setScale(2); creditTotal = creditTotal.setScale(2);
        if (debitTotal.compareTo(ZERO) <= 0 || debitTotal.compareTo(creditTotal) != 0)
            throw new BusinessException("凭证借贷合计必须相等且大于0");
        return new PreparedVoucher(prepared, debitTotal, creditTotal);
    }

    private void insertManualLines(Long voucherId, List<PreparedManualLine> lines) {
        int lineNo = 1;
        for (PreparedManualLine line : lines) {
            jdbc.update("""
                    insert into accounting_voucher_line
                    (voucher_id,line_no,account_id,account_code_snapshot,account_name_snapshot,summary,
                     debit_amount,credit_amount,partner_id,product_id,warehouse_id)
                    values (?,?,?,?,?,?,?,?,?,?,?)
                    """, voucherId, lineNo++, line.account().id(), line.account().code(), line.account().name(),
                    line.summary(), line.debit(), line.credit(), line.partnerId(), line.productId(), line.warehouseId());
        }
    }

    private void insertVoucherLine(Long voucherId, int lineNo, AccountForLine account,
                                   BusinessEntry entry, BigDecimal debit, BigDecimal credit, String summary) {
        jdbc.update("""
                insert into accounting_voucher_line
                (voucher_id,line_no,account_id,account_code_snapshot,account_name_snapshot,summary,
                 debit_amount,credit_amount,partner_id,product_id,warehouse_id,
                 source_posting_entry_id,source_line_type,source_line_id)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, voucherId, lineNo, account.id(), account.code(), account.name(), text(summary),
                debit, credit, entry.partnerId(), entry.productId(), entry.warehouseId(), entry.id(),
                entry.sourceLineType(), entry.sourceLineId());
    }

    private Long insertVoucher(long entityId, long periodId, String type, LocalDate date, String summary,
                               String sourceType, long sourceId, String sourceNo, Long businessBatchId,
                               long originalVoucherId, BigDecimal debit, BigDecimal credit, Actor actor) {
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return insert("""
                        insert into accounting_voucher
                        (entity_id,period_id,voucher_no,voucher_type,voucher_date,summary,source_type,source_id,
                         source_no,business_posting_batch_id,original_voucher_id,status,debit_total,credit_total,
                         maker_id,maker_name)
                        values (?,?,?,?,?,?,?,?,?,?,?,'DRAFT',?,?,?,?)
                        """, entityId, periodId, nextVoucherNo(), type, Date.valueOf(date), summary,
                        text(sourceType), sourceId, text(sourceNo), businessBatchId, originalVoucherId,
                        debit, credit, actor.id(), actor.name());
            } catch (DuplicateKeyException exception) {
                if (attempt == 2) throw exception;
            }
        }
        throw new BusinessException("生成凭证编号失败");
    }

    private void validateStoredVoucher(Long voucherId) {
        String storedHash = jdbc.queryForObject("select entry_hash from accounting_voucher where id=?", String.class, voucherId);
        if (!StringUtils.hasText(storedHash) || !storedHash.equals(calculateVoucherHash(voucherId)))
            throw new BusinessException("凭证分录完整性校验失败，不能审核记账");
        Map<String, Object> total = jdbc.queryForMap("""
                select count(*) line_count,coalesce(sum(debit_amount),0) debit_total,
                       coalesce(sum(credit_amount),0) credit_total
                from accounting_voucher_line where voucher_id=?
                """, voucherId);
        BigDecimal debit = money((BigDecimal) total.get("debit_total"));
        BigDecimal credit = money((BigDecimal) total.get("credit_total"));
        if (((Number) total.get("line_count")).longValue() < 2 || debit.compareTo(ZERO) <= 0 || debit.compareTo(credit) != 0)
            throw new BusinessException("凭证借贷不平衡或分录不足，不能审核记账");
        List<Map<String, Object>> invalid = jdbc.queryForList("""
                select l.line_no,a.account_code,a.status,a.leaf,a.partner_auxiliary,a.product_auxiliary,
                       a.warehouse_auxiliary,a.effective_from,a.effective_to,v.voucher_date,
                       l.partner_id,l.product_id,l.warehouse_id
                from accounting_voucher_line l join accounting_account a on a.id=l.account_id
                join accounting_voucher v on v.id=l.voucher_id
                where l.voucher_id=?
                """, voucherId);
        for (Map<String, Object> row : invalid) {
            if (!"ENABLED".equals(row.get("status")) || ((Number) row.get("leaf")).intValue() != 1)
                throw new BusinessException("第" + row.get("line_no") + "行科目已停用或不是末级科目");
            LocalDate voucherDate = ((Date) row.get("voucher_date")).toLocalDate();
            LocalDate effectiveFrom = ((Date) row.get("effective_from")).toLocalDate();
            LocalDate effectiveTo = row.get("effective_to") == null ? null : ((Date) row.get("effective_to")).toLocalDate();
            if (voucherDate.isBefore(effectiveFrom) || effectiveTo != null && voucherDate.isAfter(effectiveTo))
                throw new BusinessException("第" + row.get("line_no") + "行科目在凭证日期无效");
            AccountForLine account = new AccountForLine(0L, String.valueOf(row.get("account_code")), "",
                    String.valueOf(row.get("partner_auxiliary")), String.valueOf(row.get("product_auxiliary")),
                    String.valueOf(row.get("warehouse_auxiliary")), String.valueOf(row.get("status")));
            validateAuxiliary(account, ((Number) row.get("partner_id")).longValue(),
                    ((Number) row.get("product_id")).longValue(), ((Number) row.get("warehouse_id")).longValue());
        }
        jdbc.update("update accounting_voucher set debit_total=?,credit_total=? where id=?", debit, credit, voucherId);
    }

    private void validateAuxiliary(AccountForLine account, long partnerId, long productId, long warehouseId) {
        if (!"ENABLED".equals(account.status())) throw new BusinessException("会计科目 " + account.code() + " 已停用");
        if ("REQUIRED".equals(account.partnerAux()) && partnerId <= 0)
            throw new BusinessException("科目 " + account.code() + " 必须填写往来单位辅助核算");
        if ("REQUIRED".equals(account.productAux()) && productId <= 0)
            throw new BusinessException("科目 " + account.code() + " 必须填写商品辅助核算");
        if ("REQUIRED".equals(account.warehouseAux()) && warehouseId <= 0)
            throw new BusinessException("科目 " + account.code() + " 必须填写仓库辅助核算");
    }

    private AccountingDtos.GeneralLedgerRow ledgerRow(long id, String code, String name, String category,
                                                        String direction, BigDecimal openingDebitRaw,
                                                        BigDecimal openingCreditRaw, BigDecimal periodDebit,
                                                        BigDecimal periodCredit) {
        BigDecimal opening = money(openingDebitRaw).subtract(money(openingCreditRaw));
        BigDecimal closing = opening.add(money(periodDebit)).subtract(money(periodCredit));
        return new AccountingDtos.GeneralLedgerRow(id, code, name, category, direction,
                opening.max(ZERO), opening.negate().max(ZERO), money(periodDebit), money(periodCredit),
                closing.max(ZERO), closing.negate().max(ZERO));
    }

    private long ungeneratedPostingCount(long entityId) {
        return ungeneratedPostingCount(entityId, LocalDate.of(1900, 1, 1), LocalDate.of(2999, 12, 31));
    }

    private long ungeneratedPostingCount(long entityId, LocalDate from, LocalDate to) {
        return scalarLong("""
                select count(*) from business_posting_batch b
                where b.business_date between ? and ?
                  and ((b.status='POSTED' and b.posting_action not in ('COMMITMENT_POST','COMMITMENT_REVERSE'))
                    or (b.status='REVERSED' and b.reversal_batch_id>0
                        and b.posting_action not in ('COMMITMENT_POST','COMMITMENT_REVERSE')))
                  and not exists(select 1 from accounting_generation_result g
                    where g.entity_id=? and g.business_posting_batch_id=b.id)
                """, from, to, entityId);
    }

    private void markGenerationResult(long entityId, long batchId, String outcome, int voucherCount, String detail) {
        jdbc.update("""
                insert into accounting_generation_result
                (entity_id,business_posting_batch_id,outcome,voucher_count,detail,processed_at)
                values (?,?,?,?,?,now())
                on duplicate key update outcome=values(outcome),voucher_count=values(voucher_count),
                  detail=values(detail),processed_at=now()
                """, entityId, batchId, outcome, voucherCount, text(detail));
    }

    private void recordGenerationIssue(long entityId, long batchId, String code, String message) {
        jdbc.update("""
                insert into accounting_generation_issue
                (entity_id,business_posting_batch_id,issue_code,issue_message,status,retry_count,last_attempt_at)
                values (?,?,?,?,'OPEN',1,now())
                on duplicate key update issue_code=values(issue_code),issue_message=values(issue_message),
                  status='OPEN',retry_count=retry_count+1,last_attempt_at=now(),resolved_voucher_id=0,resolved_at=null
                """, entityId, batchId, code, message);
    }

    private void resolveGenerationIssue(long entityId, long batchId, long voucherId) {
        jdbc.update("""
                update accounting_generation_issue set status='RESOLVED',resolved_voucher_id=?,resolved_at=now()
                where entity_id=? and business_posting_batch_id=? and status='OPEN'
                """, voucherId, entityId, batchId);
    }

    private void updateVoucherHash(Long voucherId) {
        jdbc.update("update accounting_voucher set entry_hash=? where id=?", calculateVoucherHash(voucherId), voucherId);
    }

    private String calculateVoucherHash(Long voucherId) {
        List<String> rows = jdbc.query("""
                select concat(line_no,'|',account_id,'|',debit_amount,'|',credit_amount,'|',partner_id,'|',product_id,'|',warehouse_id)
                from accounting_voucher_line where voucher_id=? order by line_no
                """, (rs, rowNum) -> rs.getString(1), voucherId);
        return sha256(String.join("\n", rows));
    }

    private PeriodLock requiredOpenPeriod(long entityId, LocalDate date) {
        AccountingDtos.PeriodView view = periodByDate(entityId, date, true);
        if (view == null) throw new BusinessException("凭证日期没有对应会计期间，请先初始化年度");
        if (!"OPEN".equals(view.status())) throw new BusinessException("凭证日期所属会计期间已锁定");
        return new PeriodLock(view.id(), view.entityId(), view.startDate(), view.endDate(), view.status());
    }

    private AccountingDtos.PeriodView periodByDate(long entityId, LocalDate date, boolean exact) {
        List<AccountingDtos.PeriodView> rows = jdbc.query("""
                select * from accounting_period where entity_id=? and ? between start_date and end_date limit 1
                """, periodMapper(), entityId, Date.valueOf(date));
        if (!rows.isEmpty()) return rows.get(0);
        if (exact) return null;
        List<AccountingDtos.PeriodView> latest = jdbc.query("""
                select * from accounting_period where entity_id=? order by start_date desc limit 1
                """, periodMapper(), entityId);
        return latest.isEmpty() ? null : latest.get(0);
    }

    private BusinessBatch lockBusinessBatch(Long batchId) {
        List<BusinessBatch> rows = jdbc.query("""
                select id,posting_no,source_type,source_id,source_no,posting_action,business_date,
                       original_batch_id,reversal_batch_id,status from business_posting_batch where id=? for update
                """, (rs, rowNum) -> new BusinessBatch(rs.getLong("id"), rs.getString("posting_no"),
                rs.getString("source_type"), rs.getLong("source_id"), rs.getString("source_no"),
                rs.getString("posting_action"), rs.getDate("business_date").toLocalDate(),
                rs.getLong("original_batch_id"), rs.getLong("reversal_batch_id"), rs.getString("status")), batchId);
        if (rows.isEmpty()) throw new BusinessException("业务过账批次不存在");
        return rows.get(0);
    }

    private VoucherLock lockVoucher(Long voucherId) {
        List<VoucherLock> rows = jdbc.query("select * from accounting_voucher where id=? for update",
                voucherLockMapper(), voucherId);
        if (rows.isEmpty()) throw new BusinessException("会计凭证不存在");
        return rows.get(0);
    }

    private AccountLock lockAccount(Long accountId, long entityId) {
        List<AccountLock> rows = jdbc.query("""
                select * from accounting_account where id=? and entity_id=? for update
                """, (rs, rowNum) -> new AccountLock(rs.getLong("id"), rs.getString("account_code"),
                rs.getString("account_name"), rs.getString("category"), rs.getString("normal_direction"),
                rs.getInt("leaf") == 1, rs.getInt("allow_manual") == 1,
                rs.getString("partner_auxiliary"), rs.getString("product_auxiliary"),
                rs.getString("warehouse_auxiliary"), rs.getString("status")), accountId, entityId);
        if (rows.isEmpty()) throw new BusinessException("会计科目不存在");
        return rows.get(0);
    }

    private PeriodLock lockPeriod(Long periodId) {
        List<PeriodLock> rows = jdbc.query("""
                select id,entity_id,start_date,end_date,status from accounting_period where id=? for update
                """, (rs, rowNum) -> new PeriodLock(rs.getLong("id"), rs.getLong("entity_id"),
                rs.getDate("start_date").toLocalDate(), rs.getDate("end_date").toLocalDate(),
                rs.getString("status")), periodId);
        if (rows.isEmpty()) throw new BusinessException("会计期间不存在");
        return rows.get(0);
    }

    private org.springframework.jdbc.core.RowMapper<AccountingDtos.PeriodView> periodMapper() {
        return (rs, rowNum) -> new AccountingDtos.PeriodView(rs.getLong("id"), rs.getLong("entity_id"),
                rs.getInt("fiscal_year"), rs.getInt("period_no"), rs.getString("period_name"),
                rs.getDate("start_date").toLocalDate(), rs.getDate("end_date").toLocalDate(),
                rs.getString("status"), rs.getString("close_reason"), rs.getString("closed_by_name"),
                rs.getTimestamp("closed_at") == null ? null : rs.getTimestamp("closed_at").toLocalDateTime(),
                rs.getTimestamp("reopened_at") == null ? null : rs.getTimestamp("reopened_at").toLocalDateTime());
    }

    private org.springframework.jdbc.core.RowMapper<AccountingDtos.RuleView> ruleMapper() {
        return (rs, rowNum) -> new AccountingDtos.RuleView(rs.getLong("id"), rs.getLong("entity_id"),
                rs.getString("rule_code"), rs.getString("rule_name"), rs.getString("source_type"),
                rs.getString("posting_action"), rs.getString("fact_type"), rs.getString("fact_direction"),
                rs.getLong("debit_account_id"), rs.getString("debit_code"), rs.getString("debit_name"),
                rs.getLong("credit_account_id"), rs.getString("credit_code"), rs.getString("credit_name"),
                rs.getInt("version_no"), rs.getDate("effective_from").toLocalDate(),
                rs.getDate("effective_to") == null ? null : rs.getDate("effective_to").toLocalDate(),
                rs.getString("status"), rs.getString("change_reason"), rs.getString("published_by_name"),
                rs.getTimestamp("published_at") == null ? null : rs.getTimestamp("published_at").toLocalDateTime());
    }

    private org.springframework.jdbc.core.RowMapper<AccountingDtos.VoucherRow> voucherMapper() {
        return (rs, rowNum) -> new AccountingDtos.VoucherRow(rs.getLong("id"), rs.getLong("entity_id"),
                rs.getLong("period_id"), rs.getString("voucher_no"), rs.getString("voucher_type"),
                rs.getDate("voucher_date").toLocalDate(), rs.getString("summary"), rs.getString("source_type"),
                rs.getLong("source_id"), rs.getString("source_no"),
                rs.getObject("business_posting_batch_id") == null ? null : rs.getLong("business_posting_batch_id"),
                rs.getLong("original_voucher_id"), rs.getLong("reversal_voucher_id"), rs.getString("status"),
                rs.getBigDecimal("debit_total"), rs.getBigDecimal("credit_total"), rs.getString("maker_name"),
                rs.getString("reviewer_name"), rs.getString("poster_name"),
                rs.getTimestamp("reviewed_at") == null ? null : rs.getTimestamp("reviewed_at").toLocalDateTime(),
                rs.getTimestamp("posted_at") == null ? null : rs.getTimestamp("posted_at").toLocalDateTime(),
                rs.getTimestamp("reversed_at") == null ? null : rs.getTimestamp("reversed_at").toLocalDateTime(),
                rs.getTimestamp("created_at").toLocalDateTime());
    }

    private org.springframework.jdbc.core.RowMapper<AccountingDtos.VoucherLineView> voucherLineMapper() {
        return (rs, rowNum) -> new AccountingDtos.VoucherLineView(rs.getLong("id"), rs.getLong("voucher_id"),
                rs.getInt("line_no"), rs.getLong("account_id"), rs.getString("account_code_snapshot"),
                rs.getString("account_name_snapshot"), rs.getString("summary"), rs.getBigDecimal("debit_amount"),
                rs.getBigDecimal("credit_amount"), rs.getLong("partner_id"), rs.getString("partner_name"),
                rs.getLong("product_id"), rs.getString("product_name"), rs.getLong("warehouse_id"),
                rs.getString("warehouse_name"), rs.getLong("source_posting_entry_id"),
                rs.getString("source_line_type"), rs.getLong("source_line_id"));
    }

    private org.springframework.jdbc.core.RowMapper<VoucherLock> voucherLockMapper() {
        return (rs, rowNum) -> new VoucherLock(rs.getLong("id"), rs.getLong("entity_id"),
                rs.getString("voucher_no"), rs.getString("voucher_type"), rs.getDate("voucher_date").toLocalDate(),
                rs.getString("source_type"), rs.getLong("source_id"), rs.getString("source_no"),
                rs.getLong("original_voucher_id"), rs.getLong("reversal_voucher_id"), rs.getString("status"),
                rs.getBigDecimal("debit_total"), rs.getBigDecimal("credit_total"));
    }

    private long entityId() {
        Long id = jdbc.queryForObject("select id from accounting_entity where status='ENABLED' order by id limit 1", Long.class);
        if (id == null) throw new BusinessException("尚未建立会计主体");
        return id;
    }

    private Actor actor() {
        SecurityContext.Principal principal = SecurityContext.get();
        if (principal == null) return new Actor(0L, "系统自动");
        return new Actor(id(principal.employeeId()), StringUtils.hasText(principal.employeeName())
                ? principal.employeeName() : principal.username());
    }

    private void voucherEvent(Long voucherId, String action, String from, String to, String reason, Actor actor) {
        jdbc.update("""
                insert into accounting_voucher_event
                (voucher_id,action,from_status,to_status,reason,operator_id,operator_name)
                values (?,?,?,?,?,?,?)
                """, voucherId, action, from, to, text(reason), actor.id(), actor.name());
    }

    private void periodEvent(Long periodId, String action, String from, String to, String reason, Actor actor) {
        jdbc.update("""
                insert into accounting_period_event
                (period_id,action,from_status,to_status,reason,operator_id,operator_name)
                values (?,?,?,?,?,?,?)
                """, periodId, action, from, to, text(reason), actor.id(), actor.name());
    }

    private void accountEvent(Long accountId, String action, String summary, Actor actor) {
        jdbc.update("""
                insert into accounting_account_event
                (account_id,action,change_summary,operator_id,operator_name) values (?,?,?,?,?)
                """, accountId, action, text(summary), actor.id(), actor.name());
    }

    private Long insert(String sql, Object... args) {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
            return statement;
        }, holder);
        if (holder.getKey() == null) throw new BusinessException("保存会计数据失败");
        return holder.getKey().longValue();
    }

    private Map<String, Object> row(String sql, Object... args) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, args);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private long scalarLong(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0L : value;
    }

    private String required(String value, String label) {
        if (!StringUtils.hasText(value)) throw new BusinessException("请填写" + label);
        return value.trim();
    }

    private String enumValue(String value, List<String> allowed, String label) {
        String normalized = required(value, label).toUpperCase();
        if (!allowed.contains(normalized)) throw new BusinessException(label + "无效");
        return normalized;
    }

    private String auxiliary(String value) {
        if (!StringUtils.hasText(value)) return "NONE";
        return enumValue(value, List.of("NONE", "OPTIONAL", "REQUIRED"), "辅助核算要求");
    }

    private BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal sum(List<AccountingDtos.GeneralLedgerRow> rows,
                           java.util.function.Function<AccountingDtos.GeneralLedgerRow, BigDecimal> getter) {
        return rows.stream().map(getter).reduce(ZERO, BigDecimal::add).setScale(2);
    }

    private int bool(Boolean value) { return Boolean.TRUE.equals(value) ? 1 : 0; }
    private long id(Long value) { return value == null ? 0L : value; }
    private String text(String value) { return value == null ? "" : value.trim(); }
    private Date sqlDate(LocalDate value) { return value == null ? null : Date.valueOf(value); }
    private String nextVoucherNo() { return "PZ" + NO_TIME.format(LocalDateTime.now()) + String.format("%03d", Math.floorMod(NO_COUNTER.incrementAndGet(), 1000)); }

    private String sha256(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception exception) { throw new IllegalStateException("无法计算会计凭证摘要", exception); }
    }

    private String issueCode(RuntimeException exception) {
        String message = safeMessage(exception);
        if (message.contains("期间")) return "PERIOD_NOT_OPEN";
        if (message.contains("规则")) return "RULE_MISSING";
        if (message.contains("辅助核算")) return "AUXILIARY_MISSING";
        return "GENERATION_FAILED";
    }

    private String safeMessage(RuntimeException exception) {
        String value = exception.getMessage();
        if (!StringUtils.hasText(value)) value = exception.getClass().getSimpleName();
        return value.length() > 900 ? value.substring(0, 900) : value;
    }

    private record Actor(long id, String name) {}
    private record PeriodLock(long id, long entityId, LocalDate startDate, LocalDate endDate, String status) {}
    private record BusinessBatch(long id, String postingNo, String sourceType, long sourceId, String sourceNo,
                                 String action, LocalDate businessDate, long originalBatchId,
                                 long reversalBatchId, String status) {}
    private record BusinessEntry(long id, String factType, String direction, long partnerId, long productId,
                                 long warehouseId, long inventoryBatchId, String sourceLineType,
                                 long sourceLineId, BigDecimal amount, String memo) {}
    private record AccountForLine(long id, String code, String name, String partnerAux,
                                  String productAux, String warehouseAux, String status) {}
    private record MappedFact(BusinessEntry entry, AccountForLine debit, AccountForLine credit, BigDecimal amount) {}
    private record AccountLock(Long id, String code, String name, String category, String direction,
                               boolean leaf, boolean allowManual, String partnerAux, String productAux,
                               String warehouseAux, String status) {
        AccountForLine lineAccount() { return new AccountForLine(id, code, name, partnerAux, productAux, warehouseAux, status); }
    }
    private record VoucherLock(Long id, long entityId, String no, String type, LocalDate date,
                               String sourceType, long sourceId, String sourceNo, long originalVoucherId,
                               long reversalVoucherId, String status, BigDecimal debitTotal, BigDecimal creditTotal) {}
    private record PreparedManualLine(AccountLock account, String summary, BigDecimal debit, BigDecimal credit,
                                      long partnerId, long productId, long warehouseId) {}
    private record PreparedSystemLine(AccountLock account, String summary, BigDecimal debit, BigDecimal credit,
                                      long partnerId, long productId, long warehouseId) {}
    private record PreparedVoucher(List<PreparedManualLine> lines, BigDecimal debitTotal, BigDecimal creditTotal) {}
    public record SystemVoucherLine(String accountCode, String summary,
                                    BigDecimal debitAmount, BigDecimal creditAmount,
                                    Long partnerId, Long productId, Long warehouseId) {}
    public record GenerationOutcome(List<Long> voucherIds, int cancelledCount) {}
}
