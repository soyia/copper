package com.soyia.copperv2.service;

import com.soyia.copperv2.dto.AccountingDtos;
import com.soyia.copperv2.dto.FinancialCloseDtos;
import com.soyia.copperv2.dto.TaxInvoiceDtos;
import com.soyia.copperv2.security.SecurityContext;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class FinancialCloseService {
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    private static final BigDecimal CENT = new BigDecimal("0.01");
    private static final DateTimeFormatter NO_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");
    private static final AtomicInteger NO_COUNTER = new AtomicInteger();
    private static final Set<String> DIRECTIONS = Set.of("DEBIT", "CREDIT");
    private static final Set<String> CASH_FLOW_CATEGORIES = Set.of(
            "SALES_COLLECTION", "OTHER_OPERATING_IN", "PURCHASE_PAYMENT",
            "EMPLOYEE_PAYMENT", "TAX_PAYMENT", "BANK_FEE", "OTHER_OPERATING_OUT",
            "INVESTING_IN", "INVESTING_OUT", "FINANCING_IN", "FINANCING_OUT", "INTERNAL_TRANSFER");
    private static final List<String> CASH_FLOW_ORDER = List.of(
            "SALES_COLLECTION", "OTHER_OPERATING_IN", "PURCHASE_PAYMENT", "EMPLOYEE_PAYMENT",
            "TAX_PAYMENT", "BANK_FEE", "OTHER_OPERATING_OUT", "INVESTING_IN", "INVESTING_OUT",
            "FINANCING_IN", "FINANCING_OUT");
    private static final Map<String, CategoryMeta> CASH_FLOW_META = Map.ofEntries(
            Map.entry("SALES_COLLECTION", new CategoryMeta("销售商品收到的现金", "OPERATING", "CREDIT")),
            Map.entry("OTHER_OPERATING_IN", new CategoryMeta("收到其他经营活动现金", "OPERATING", "CREDIT")),
            Map.entry("PURCHASE_PAYMENT", new CategoryMeta("购买商品支付的现金", "OPERATING", "DEBIT")),
            Map.entry("EMPLOYEE_PAYMENT", new CategoryMeta("支付给职工的现金", "OPERATING", "DEBIT")),
            Map.entry("TAX_PAYMENT", new CategoryMeta("支付的税费", "OPERATING", "DEBIT")),
            Map.entry("BANK_FEE", new CategoryMeta("支付银行手续费", "OPERATING", "DEBIT")),
            Map.entry("OTHER_OPERATING_OUT", new CategoryMeta("支付其他经营活动现金", "OPERATING", "DEBIT")),
            Map.entry("INVESTING_IN", new CategoryMeta("投资活动现金流入", "INVESTING", "CREDIT")),
            Map.entry("INVESTING_OUT", new CategoryMeta("投资活动现金流出", "INVESTING", "DEBIT")),
            Map.entry("FINANCING_IN", new CategoryMeta("筹资活动现金流入", "FINANCING", "CREDIT")),
            Map.entry("FINANCING_OUT", new CategoryMeta("筹资活动现金流出", "FINANCING", "DEBIT")));

    private final JdbcTemplate jdbc;
    private final AccountingService accountingService;
    private final TaxInvoiceService taxInvoiceService;

    public FinancialCloseService(JdbcTemplate jdbc, AccountingService accountingService,
                                 TaxInvoiceService taxInvoiceService) {
        this.jdbc = jdbc;
        this.accountingService = accountingService;
        this.taxInvoiceService = taxInvoiceService;
    }

    public FinancialCloseDtos.FinancialReport balanceSheet(LocalDate asOf) {
        LocalDate date = asOf == null ? LocalDate.now() : asOf;
        LocalDate comparative = date.minusYears(1);
        long entityId = entityId();
        List<ReportDefinition> definitions = reportDefinitions(entityId, "BALANCE_SHEET", date);
        Map<String, BigDecimal> current = new LinkedHashMap<>();
        Map<String, BigDecimal> prior = new LinkedHashMap<>();
        for (ReportDefinition definition : definitions) {
            // Formula rows may still own direct account mappings. The current-profit row combines
            // the posted 3103 balance with revenue/cost balances that have not yet been carried forward.
            if ("ACCOUNT_SUM".equals(definition.calculationType()) || !definition.accountCodes().isEmpty()) {
                current.put(definition.lineCode(), mappedBalance(entityId, definition.id(), date,
                        definition.normalDirection()));
                prior.put(definition.lineCode(), mappedBalance(entityId, definition.id(), comparative,
                        definition.normalDirection()));
            }
        }
        applyBalanceFormulas(entityId, date, current);
        applyBalanceFormulas(entityId, comparative, prior);
        List<FinancialCloseDtos.ReportLine> rows = definitions.stream().map(line ->
                new FinancialCloseDtos.ReportLine(line.lineCode(), line.lineName(), line.lineOrder(),
                        money(current.get(line.lineCode())), money(prior.get(line.lineCode())),
                        line.calculationType(), line.accountCodes())).toList();
        BigDecimal assets = money(current.get("BS_ASSET_TOTAL"));
        BigDecimal liabilitiesEquity = money(current.get("BS_LIABILITY_EQUITY_TOTAL"));
        return new FinancialCloseDtos.FinancialReport("BALANCE_SHEET", "资产负债表", null,
                date, "CNY", rows, assets, liabilitiesEquity,
                assets.subtract(liabilitiesEquity).setScale(2),
                unmappedAccounts(entityId, "BALANCE_SHEET", null, date), LocalDateTime.now());
    }

    public FinancialCloseDtos.FinancialReport incomeStatement(LocalDate from, LocalDate to) {
        LocalDate end = to == null ? LocalDate.now() : to;
        LocalDate start = from == null ? end.withDayOfMonth(1) : from;
        if (start.isAfter(end)) throw new BusinessException("利润表开始日期不能晚于结束日期");
        long entityId = entityId();
        List<ReportDefinition> definitions = reportDefinitions(entityId, "INCOME_STATEMENT", end);
        Map<String, BigDecimal> current = new LinkedHashMap<>();
        Map<String, BigDecimal> prior = new LinkedHashMap<>();
        for (ReportDefinition definition : definitions) {
            if ("ACCOUNT_SUM".equals(definition.calculationType())) {
                current.put(definition.lineCode(), mappedActivity(entityId, definition.id(), start, end,
                        definition.normalDirection()));
                prior.put(definition.lineCode(), mappedActivity(entityId, definition.id(),
                        start.minusYears(1), end.minusYears(1), definition.normalDirection()));
            }
        }
        applyIncomeFormulas(entityId, start, end, current);
        applyIncomeFormulas(entityId, start.minusYears(1), end.minusYears(1), prior);
        List<FinancialCloseDtos.ReportLine> rows = definitions.stream().map(line ->
                new FinancialCloseDtos.ReportLine(line.lineCode(), line.lineName(), line.lineOrder(),
                        money(current.get(line.lineCode())), money(prior.get(line.lineCode())),
                        line.calculationType(), line.accountCodes())).toList();
        BigDecimal revenue = money(current.get("IS_REVENUE_TOTAL"));
        BigDecimal expense = money(current.get("IS_EXPENSE_TOTAL"));
        return new FinancialCloseDtos.FinancialReport("INCOME_STATEMENT", "利润表", start,
                end, "CNY", rows, revenue, expense,
                money(current.get("IS_NET_PROFIT")),
                unmappedAccounts(entityId, "INCOME_STATEMENT", start, end), LocalDateTime.now());
    }

    public FinancialCloseDtos.CashFlowReport cashFlow(LocalDate from, LocalDate to) {
        LocalDate end = to == null ? LocalDate.now() : to;
        LocalDate start = from == null ? end.withDayOfMonth(1) : from;
        if (start.isAfter(end)) throw new BusinessException("现金流量表开始日期不能晚于结束日期");
        long entityId = entityId();
        Map<String, BigDecimal> amounts = new LinkedHashMap<>();
        jdbc.query("""
                select cash_flow_category,direction,sum(amount) amount from (
                  select m.cash_flow_category,l.direction,m.matched_amount amount
                  from bank_reconciliation_match m
                  join bank_statement_line l on l.id=m.statement_line_id
                  join bank_reconciliation_session s on s.id=m.session_id
                  where s.entity_id=? and s.status='CONFIRMED' and m.status='ACTIVE'
                    and l.transaction_date between ? and ?
                  union all
                  select d.cash_flow_category,d.direction,d.amount
                  from bank_reconciliation_difference d
                  join bank_statement_line l on l.id=d.statement_line_id
                  join bank_reconciliation_session s on s.id=d.session_id
                  where s.entity_id=? and s.status='CONFIRMED' and d.status<>'REVERSED'
                    and l.transaction_date between ? and ?
                  union all
                  select p.cash_flow_category,p.direction,p.amount
                  from bank_reconciliation_pending_system p
                  join bank_reconciliation_session s on s.id=p.session_id
                  where s.entity_id=? and s.status='CONFIRMED' and p.status='OPEN'
                    and p.source_date between ? and ?
                  union all
                  select 'SALES_COLLECTION','CREDIT',p.amount from payment p
                  where p.payment_method='CASH' and p.verification_status<>'REVERSED'
                    and p.received_date between ? and ?
                  union all
                  select 'PURCHASE_PAYMENT','DEBIT',p.amount from supplier_payment p
                  where p.payment_method='CASH' and p.verification_status<>'REVERSED'
                    and p.paid_date between ? and ?
                  union all
                  select 'OTHER_OPERATING_OUT','DEBIT',r.amount from customer_advance_refund r
                  where r.refund_method='CASH' and r.status='ACTIVE' and r.refunded_date between ? and ?
                  union all
                  select j.cash_flow_category,j.direction,j.amount from treasury_cash_journal j
                  where j.settlement_method='CASH' and j.status='ACTIVE'
                    and j.cash_flow_category<>'INTERNAL_TRANSFER' and j.transaction_date between ? and ?
                ) x group by cash_flow_category,direction
                """, (RowCallbackHandler) rs -> amounts.put(rs.getString("cash_flow_category") + ":" + rs.getString("direction"),
                        money(rs.getBigDecimal("amount"))), entityId, Date.valueOf(start), Date.valueOf(end),
                entityId, Date.valueOf(start), Date.valueOf(end),
                entityId, Date.valueOf(start), Date.valueOf(end),
                Date.valueOf(start), Date.valueOf(end), Date.valueOf(start), Date.valueOf(end),
                Date.valueOf(start), Date.valueOf(end), Date.valueOf(start), Date.valueOf(end));
        List<FinancialCloseDtos.CashFlowLine> rows = new ArrayList<>();
        BigDecimal operating = ZERO;
        BigDecimal investing = ZERO;
        BigDecimal financing = ZERO;
        for (String code : CASH_FLOW_ORDER) {
            CategoryMeta meta = CASH_FLOW_META.get(code);
            BigDecimal amount = money(amounts.get(code + ":" + meta.direction()));
            rows.add(new FinancialCloseDtos.CashFlowLine(code, meta.name(), meta.activityType(),
                    meta.direction(), amount));
            BigDecimal signed = "CREDIT".equals(meta.direction()) ? amount : amount.negate();
            if ("OPERATING".equals(meta.activityType())) operating = operating.add(signed);
            if ("INVESTING".equals(meta.activityType())) investing = investing.add(signed);
            if ("FINANCING".equals(meta.activityType())) financing = financing.add(signed);
        }
        operating = money(operating);
        investing = money(investing);
        financing = money(financing);
        BigDecimal net = operating.add(investing).add(financing).setScale(2);
        BigDecimal opening = cashLedgerBalance(entityId, start.minusDays(1));
        BigDecimal expectedClosing = opening.add(net).setScale(2);
        BigDecimal ledgerClosing = cashLedgerBalance(entityId, end);
        long sessions = scalarLong("""
                select count(*) from bank_reconciliation_session
                where entity_id=? and status='CONFIRMED' and statement_from>=? and statement_to<=?
                """, entityId, Date.valueOf(start), Date.valueOf(end));
        long unclassified = scalarLong("""
                select count(*) from bank_reconciliation_match m
                join bank_reconciliation_session s on s.id=m.session_id
                where s.entity_id=? and s.status='CONFIRMED' and m.status='ACTIVE'
                  and (m.cash_flow_category='' or m.cash_flow_category is null)
                  and m.source_date between ? and ?
                """, entityId, Date.valueOf(start), Date.valueOf(end));
        return new FinancialCloseDtos.CashFlowReport(start, end, "CNY", rows, opening,
                operating, investing, financing, net, expectedClosing, ledgerClosing,
                ledgerClosing.subtract(expectedClosing).setScale(2), sessions,
                unclassified, LocalDateTime.now());
    }

    public List<FinancialCloseDtos.BankAccountView> bankAccounts(boolean includeDisabled) {
        long entityId = entityId();
        String filter = includeDisabled ? "" : " and b.status='ENABLED'";
        boolean sensitive = SecurityContext.require().hasPermission("finance.sensitive.view");
        return jdbc.query("""
                select b.*,a.account_code ledger_account_code,a.account_name ledger_account_name
                from business_bank_account b join accounting_account a on a.id=b.ledger_account_id
                where b.entity_id=?
                """ + filter + " order by b.status,b.id", (rs, rowNum) -> {
            String accountNo = rs.getString("bank_account_no");
            return new FinancialCloseDtos.BankAccountView(rs.getLong("id"), rs.getString("account_code"),
                    rs.getString("account_name"), rs.getString("bank_name"), sensitive ? accountNo : "",
                    mask(accountNo), rs.getString("currency"), rs.getLong("ledger_account_id"),
                    rs.getString("ledger_account_code"), rs.getString("ledger_account_name"),
                    rs.getDate("opening_date").toLocalDate(), rs.getBigDecimal("opening_balance"),
                    rs.getString("status"), rs.getString("disabled_reason"));
        }, entityId);
    }

    @Transactional
    public Long saveBankAccount(Long accountId, FinancialCloseDtos.BankAccountSave request) {
        long entityId = entityId();
        Map<String, Object> ledger = row("""
                select id,account_code,account_name,category,normal_direction,status,leaf
                from accounting_account where id=? and entity_id=?
                """, request.ledgerAccountId(), entityId);
        if (ledger == null || !"ASSET".equals(ledger.get("category")) ||
                !"DEBIT".equals(ledger.get("normal_direction")) || !"ENABLED".equals(ledger.get("status")) ||
                number(ledger.get("leaf")) != 1)
            throw new BusinessException("银行账户必须关联启用的资产类借方科目");
        if (!String.valueOf(ledger.get("account_code")).startsWith("1002"))
            throw new BusinessException("企业银行账户必须关联银行存款科目1002或其末级明细科目");
        String status = "DISABLED".equals(request.status()) ? "DISABLED" : "ENABLED";
        String disabledReason = "DISABLED".equals(status) ? required(request.disabledReason(), "停用原因") : "";
        Actor actor = actor();
        try {
            if (accountId == null || accountId <= 0) {
                Long createdId = insert("""
                        insert into business_bank_account
                        (entity_id,account_code,account_name,bank_name,bank_account_no,currency,
                         ledger_account_id,opening_date,opening_balance,status,disabled_reason,
                         created_by,created_by_name)
                        values (?,?,?,?,?,?,?,?,?,?,?,?,?)
                        """, entityId, required(request.accountCode(), "账户编码"),
                        required(request.accountName(), "账户名称"), required(request.bankName(), "开户银行"),
                        required(request.bankAccountNo(), "银行账号"), currency(request.currency()),
                        request.ledgerAccountId(), Date.valueOf(request.openingDate()), money(request.openingBalance()),
                        status, disabledReason, actor.id(), actor.name());
                ensureBankReportMapping(entityId, request.ledgerAccountId(), request.openingDate());
                return createdId;
            }
            Map<String, Object> existing = requiredRow("""
                    select ledger_account_id,bank_account_no,opening_date,opening_balance
                    from business_bank_account where id=? and entity_id=?
                    """, accountId, entityId);
            long reconciliationCount = scalarLong(
                    "select count(*) from bank_reconciliation_session where bank_account_id=?", accountId);
            if (reconciliationCount > 0 &&
                    (number(existing.get("ledger_account_id")) != request.ledgerAccountId() ||
                     !String.valueOf(existing.get("bank_account_no")).equals(request.bankAccountNo().trim()) ||
                     !((Date) existing.get("opening_date")).toLocalDate().equals(request.openingDate()) ||
                     money(existing.get("opening_balance")).compareTo(money(request.openingBalance())) != 0)) {
                throw new BusinessException("已有对账记录后不能更换银行账号、总账科目或期初口径；请停用旧账户并另建新账户");
            }
            if ("DISABLED".equals(status) && scalarLong("""
                    select count(*) from bank_reconciliation_session
                    where bank_account_id=? and status in ('DRAFT','REOPENED')
                    """, accountId) > 0) {
                throw new BusinessException("银行账户仍有未确认或已重新打开的对账单，不能停用");
            }
            jdbc.update("""
                    update business_bank_account set account_code=?,account_name=?,bank_name=?,bank_account_no=?,
                      currency=?,ledger_account_id=?,opening_date=?,opening_balance=?,status=?,disabled_reason=?
                    where id=? and entity_id=?
                    """, required(request.accountCode(), "账户编码"), required(request.accountName(), "账户名称"),
                    required(request.bankName(), "开户银行"), required(request.bankAccountNo(), "银行账号"),
                    currency(request.currency()), request.ledgerAccountId(), Date.valueOf(request.openingDate()),
                    money(request.openingBalance()), status, disabledReason, accountId, entityId);
            ensureBankReportMapping(entityId, request.ledgerAccountId(), request.openingDate());
            return accountId;
        } catch (DuplicateKeyException ex) {
            throw new BusinessException("账户编码、银行账号或关联总账科目已被其他企业账户使用");
        }
    }

    private void ensureBankReportMapping(long entityId, Long ledgerAccountId, LocalDate effectiveFrom) {
        if (scalarLong("""
                select count(*) from financial_report_account_map
                where entity_id=? and report_type='BALANCE_SHEET' and account_id=?
                """, entityId, ledgerAccountId) > 0) return;
        int inserted = jdbc.update("""
                insert into financial_report_account_map
                (entity_id,report_type,line_id,account_id,amount_sign,effective_from,effective_to)
                select m.entity_id,m.report_type,m.line_id,?,m.amount_sign,?,null
                from financial_report_account_map m
                join accounting_account a on a.id=m.account_id
                where m.entity_id=? and m.report_type='BALANCE_SHEET' and a.account_code='1002'
                  and m.effective_from<=? and (m.effective_to is null or m.effective_to>=?)
                order by m.effective_from desc limit 1
                """, ledgerAccountId, Date.valueOf(effectiveFrom), entityId,
                Date.valueOf(effectiveFrom), Date.valueOf(effectiveFrom));
        if (inserted == 0) throw new BusinessException("银行存款科目尚未配置资产负债表项目，不能建立银行账户");
    }

    public List<FinancialCloseDtos.ReconciliationSessionRow> reconciliationSessions(Long periodId) {
        long entityId = entityId();
        String filter = periodId == null || periodId <= 0 ? "" : " and s.period_id=?";
        Object[] args = periodId == null || periodId <= 0 ? new Object[]{entityId} : new Object[]{entityId, periodId};
        return jdbc.query(sessionSelect() + " where s.entity_id=?" + filter + " order by p.start_date desc,b.id",
                sessionMapper(), args);
    }

    @Transactional
    public Long createReconciliation(FinancialCloseDtos.ReconciliationCreate request) {
        long entityId = entityId();
        Period period = period(request.periodId(), true);
        BankAccount bank = bankAccount(request.bankAccountId(), false);
        if (period.entityId() != entityId || bank.entityId() != entityId) throw new BusinessException("会计主体不一致");
        if (bank.openingDate().isAfter(period.endDate())) throw new BusinessException("银行账户启用日期晚于当前对账期间");
        Map<String, Object> prior = row("""
                select statement_closing_balance,statement_to from bank_reconciliation_session
                where bank_account_id=? and statement_to<? and status='CONFIRMED'
                order by statement_to desc,id desc limit 1
                """, bank.id(), Date.valueOf(period.startDate()));
        if (prior != null && money(prior.get("statement_closing_balance")).compareTo(money(request.openingBalance())) != 0) {
            throw new BusinessException("本期对账单期初余额必须等于上一已确认期间的期末余额 " +
                    money(prior.get("statement_closing_balance")));
        }
        try {
            Long id = insert("""
                    insert into bank_reconciliation_session
                    (entity_id,bank_account_id,period_id,reconciliation_no,statement_from,statement_to,
                     opening_balance,statement_closing_balance,status)
                    values (?,?,?,?,?,?,?,?,'DRAFT')
                    """, entityId, bank.id(), period.id(), nextNo("YH"), Date.valueOf(period.startDate()),
                    Date.valueOf(period.endDate()), money(request.openingBalance()),
                    money(request.statementClosingBalance()));
            recalculateSession(id);
            reconciliationEvent(id, "CREATE", "", "DRAFT", "建立银行对账单", actor());
            return id;
        } catch (DuplicateKeyException ex) {
            throw new BusinessException("该银行账户在当前会计期间已经建立对账单");
        }
    }

    @Transactional
    public int saveStatementLines(Long sessionId, FinancialCloseDtos.StatementLinesSave request) {
        SessionLock session = session(sessionId, true);
        requireEditableSession(session);
        int lineNo = (int) scalarLong("select coalesce(max(line_no),0) from bank_statement_line where session_id=?", sessionId);
        int inserted = 0;
        for (FinancialCloseDtos.StatementLineInput line : request.lines()) {
            String direction = enumValue(line.direction(), DIRECTIONS, "收支方向");
            LocalDate valueDate = line.valueDate() == null ? line.transactionDate() : line.valueDate();
            if (line.transactionDate().isBefore(session.from()) || line.transactionDate().isAfter(session.to()))
                throw new BusinessException("银行流水日期必须位于对账期间内");
            FinancialCloseDtos.StatementLineInput normalized = new FinancialCloseDtos.StatementLineInput(
                    line.transactionDate(), valueDate, text(line.bankSerialNo()), direction,
                    money(line.amount()), money(line.balance()), text(line.counterpartyName()),
                    text(line.counterpartyAccount()), text(line.purpose()));
            String hash = statementSourceHash(normalized);
            try {
                jdbc.update("""
                        insert into bank_statement_line
                        (session_id,line_no,transaction_date,value_date,bank_serial_no,direction,amount,balance,
                         counterparty_name,counterparty_account,purpose,source_hash,status)
                        values (?,?,?,?,?,?,?,?,?,?,?,?,'UNMATCHED')
                        """, sessionId, ++lineNo, Date.valueOf(line.transactionDate()), Date.valueOf(valueDate),
                        text(line.bankSerialNo()), direction, money(line.amount()), money(line.balance()),
                        text(line.counterpartyName()), text(line.counterpartyAccount()), text(line.purpose()), hash);
                inserted++;
            } catch (DuplicateKeyException ignored) {
                lineNo--;
            }
        }
        recalculateSession(sessionId);
        if (inserted > 0) reconciliationEvent(sessionId, "IMPORT_STATEMENT", session.status(), session.status(),
                "新增 " + inserted + " 条银行流水", actor());
        return inserted;
    }

    public void assertStatementImportVisible(Long sessionId) {
        session(sessionId, false);
    }

    public void assertStatementImportEditable(Long sessionId) {
        requireEditableSession(session(sessionId, false));
    }

    public List<FinancialCloseDtos.FundCandidate> statementImportCandidates(Long sessionId) {
        SessionLock locked = session(sessionId, false);
        requireEditableSession(locked);
        return fundCandidates(locked);
    }

    public String statementSourceHash(FinancialCloseDtos.StatementLineInput line) {
        LocalDate valueDate = line.valueDate() == null ? line.transactionDate() : line.valueDate();
        return sha256(line.transactionDate() + "|" + valueDate + "|" + text(line.bankSerialNo()) + "|" +
                text(line.direction()).toUpperCase() + "|" + money(line.amount()) + "|" + money(line.balance()) + "|" +
                text(line.counterpartyName()) + "|" + text(line.counterpartyAccount()) + "|" + text(line.purpose()));
    }

    @Transactional
    public FinancialCloseDtos.StatementImportResult commitStatementImport(Long sessionId, Long batchId,
                                                                           boolean applyAutoMatches) {
        SessionLock lockedSession = session(sessionId, true);
        requireEditableSession(lockedSession);
        Map<String, Object> batch = requiredRow("""
                select * from bank_statement_import_batch where id=? and session_id=? for update
                """, batchId, sessionId);
        if ("COMPLETED".equals(batch.get("status"))) return statementImportResult(batchId);
        if (!"PREVIEWED".equals(batch.get("status"))) throw new BusinessException("银行流水导入批次当前状态不能提交");

        List<Map<String, Object>> rows = jdbc.queryForList("""
                select * from bank_statement_import_row
                where batch_id=? and validation_status='VALID' and imported_statement_line_id=0
                order by source_row_no for update
                """, batchId);
        if (rows.isEmpty()) throw new BusinessException("当前导入批次没有可导入的有效流水");
        int lineNo = (int) scalarLong(
                "select coalesce(max(line_no),0) from bank_statement_line where session_id=?", sessionId);
        int imported = 0;
        int autoMatched = 0;
        int commitDuplicates = 0;
        for (Map<String, Object> importRow : rows) {
            long importRowId = number(importRow.get("id"));
            Long lineId;
            try {
                lineId = insert("""
                        insert into bank_statement_line
                        (session_id,import_batch_id,import_row_id,line_no,transaction_date,value_date,
                         bank_serial_no,direction,amount,balance,counterparty_name,counterparty_account,
                         purpose,source_hash,unmatched_reason_code,unmatched_reason,suggested_source_type,
                         suggested_source_id,suggested_source_no,suggested_score,suggested_reason,status)
                        values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'UNMATCHED')
                        """, sessionId, batchId, importRowId, ++lineNo, importRow.get("transaction_date"),
                        importRow.get("value_date"), text(String.valueOf(importRow.get("bank_serial_no"))),
                        importRow.get("direction"), money(importRow.get("amount")), money(importRow.get("balance")),
                        text(String.valueOf(importRow.get("counterparty_name"))),
                        text(String.valueOf(importRow.get("counterparty_account"))),
                        text(String.valueOf(importRow.get("purpose"))), importRow.get("source_hash"),
                        text(String.valueOf(importRow.get("unmatched_reason_code"))),
                        text(String.valueOf(importRow.get("unmatched_reason"))),
                        text(String.valueOf(importRow.get("suggested_source_type"))),
                        number(importRow.get("suggested_source_id")),
                        text(String.valueOf(importRow.get("suggested_source_no"))),
                        number(importRow.get("suggested_score")), text(String.valueOf(importRow.get("suggested_reason"))));
                imported++;
            } catch (DuplicateKeyException ex) {
                lineNo--;
                commitDuplicates++;
                Long duplicateLineId = jdbc.queryForObject("""
                        select id from bank_statement_line where session_id=? and source_hash=? order by id limit 1
                        """, Long.class, sessionId, importRow.get("source_hash"));
                jdbc.update("""
                        update bank_statement_import_row set validation_status='DUPLICATE',
                          validation_message='预检后已有相同流水写入，本次提交已跳过',
                          duplicate_type='EXISTING_EXACT',duplicate_statement_line_id=?,match_action='DUPLICATE'
                        where id=?
                        """, duplicateLineId == null ? 0L : duplicateLineId, importRowId);
                continue;
            }
            jdbc.update("update bank_statement_import_row set imported_statement_line_id=? where id=?",
                    lineId, importRowId);
            if (applyAutoMatches && "AUTO_MATCH".equals(importRow.get("match_action"))) {
                try {
                    String direction = String.valueOf(importRow.get("direction"));
                    String sourceType = String.valueOf(importRow.get("suggested_source_type"));
                    FinancialCloseDtos.MatchSave match = new FinancialCloseDtos.MatchSave(lineId, sourceType,
                            number(importRow.get("suggested_source_id")), money(importRow.get("amount")),
                            importCashFlowCategory(sourceType, number(importRow.get("suggested_source_id")), direction),
                            "导入批次 " + batch.get("batch_no") + " 唯一高置信自动匹配；" +
                                    importRow.get("suggested_reason"));
                    createMatchInternal(sessionId, match, "RULE", (int) number(importRow.get("suggested_score")),
                            BankStatementFileParser.TEMPLATE_VERSION, batchId, false);
                    autoMatched++;
                } catch (BusinessException ex) {
                    jdbc.update("""
                            update bank_statement_line set unmatched_reason_code='AUTO_MATCH_CONFLICT',
                              unmatched_reason=?,suggested_reason=concat(suggested_reason,'；提交时未自动应用')
                            where id=?
                            """, "预检后系统资金状态发生变化，需人工重新确认：" + ex.getMessage(), lineId);
                    jdbc.update("""
                            update bank_statement_import_row set match_action='REVIEW',
                              unmatched_reason_code='AUTO_MATCH_CONFLICT',unmatched_reason=? where id=?
                            """, "预检后系统资金状态发生变化，需人工重新确认", importRowId);
                }
            }
        }
        if (commitDuplicates > 0) {
            jdbc.update("""
                    update bank_statement_import_batch set duplicate_rows=duplicate_rows+?,valid_rows=greatest(valid_rows-?,0)
                    where id=?
                    """, commitDuplicates, commitDuplicates, batchId);
        }
        jdbc.update("""
                update bank_statement_import_batch set status='COMPLETED',imported_rows=?,
                  applied_auto_match_rows=?,completed_at=now() where id=?
                """, imported, autoMatched, batchId);
        recalculateSession(sessionId);
        reconciliationEvent(sessionId, "IMPORT_STATEMENT", lockedSession.status(), lockedSession.status(),
                "批次 " + batch.get("batch_no") + " 导入 " + imported + " 条；自动匹配 " + autoMatched +
                        " 条；重复跳过 " + (number(batch.get("duplicate_rows")) + commitDuplicates) + " 条",
                actor());
        return statementImportResult(batchId);
    }

    private FinancialCloseDtos.StatementImportResult statementImportResult(Long batchId) {
        Map<String, Object> batch = requiredRow("select * from bank_statement_import_batch where id=?", batchId);
        int review = (int) scalarLong("""
                select count(*) from bank_statement_import_row where batch_id=? and validation_status='VALID'
                  and match_action='REVIEW'
                """, batchId);
        int unmatched = (int) scalarLong("""
                select count(*) from bank_statement_import_row where batch_id=? and validation_status='VALID'
                  and match_action='UNMATCHED'
                """, batchId);
        return new FinancialCloseDtos.StatementImportResult(batchId, String.valueOf(batch.get("batch_no")),
                String.valueOf(batch.get("status")), (int) number(batch.get("imported_rows")),
                (int) number(batch.get("duplicate_rows")), (int) number(batch.get("error_rows")),
                (int) number(batch.get("applied_auto_match_rows")), review, unmatched);
    }

    private String importCashFlowCategory(String sourceType, long sourceId, String direction) {
        if ("PAYMENT".equals(sourceType)) return "SALES_COLLECTION";
        if ("SUPPLIER_PAYMENT".equals(sourceType)) return "PURCHASE_PAYMENT";
        if ("TREASURY_JOURNAL".equals(sourceType)) {
            List<String> values = jdbc.queryForList(
                    "select cash_flow_category from treasury_cash_journal where id=?", String.class, sourceId);
            if (!values.isEmpty()) return values.get(0);
        }
        if ("CREDIT".equals(direction)) return "SALES_COLLECTION";
        return "OTHER_OPERATING_OUT";
    }

    public FinancialCloseDtos.ReconciliationDetail reconciliation(Long sessionId) {
        SessionLock session = session(sessionId, false);
        recalculateSession(sessionId);
        FinancialCloseDtos.ReconciliationSessionRow row = jdbc.queryForObject(sessionSelect() + " where s.id=?",
                sessionMapper(), sessionId);
        List<FinancialCloseDtos.StatementLineView> lines = statementLines(sessionId);
        List<FinancialCloseDtos.FundCandidate> candidates = fundCandidates(session);
        List<FinancialCloseDtos.MatchView> matches = matches(sessionId);
        List<FinancialCloseDtos.DifferenceView> differences = differences(sessionId);
        List<FinancialCloseDtos.PendingSystemView> pending = pendingSystemItems(sessionId);
        List<FinancialCloseDtos.ReconciliationEventView> events = reconciliationEvents(sessionId);
        BigDecimal statementCredit = sumRemaining(lines, "CREDIT");
        BigDecimal statementDebit = sumRemaining(lines, "DEBIT");
        BigDecimal systemCredit = pending.stream().filter(x -> "OPEN".equals(x.status()) && "CREDIT".equals(x.direction()))
                .map(FinancialCloseDtos.PendingSystemView::amount).reduce(ZERO, BigDecimal::add).setScale(2);
        BigDecimal systemDebit = pending.stream().filter(x -> "OPEN".equals(x.status()) && "DEBIT".equals(x.direction()))
                .map(FinancialCloseDtos.PendingSystemView::amount).reduce(ZERO, BigDecimal::add).setScale(2);
        return new FinancialCloseDtos.ReconciliationDetail(row, lines, candidates, matches, differences, pending, events,
                statementCredit, statementDebit, systemCredit, systemDebit);
    }

    @Transactional
    public Long createMatch(Long sessionId, FinancialCloseDtos.MatchSave request) {
        return createMatchInternal(sessionId, request, "MANUAL", 0, "", 0L, true);
    }

    private Long createMatchInternal(Long sessionId, FinancialCloseDtos.MatchSave request,
                                     String matchingMethod, int matchScore,
                                     String matchRuleVersion, long importBatchId,
                                     boolean writeEvent) {
        SessionLock session = session(sessionId, true);
        requireEditableSession(session);
        String sourceType = required(request.sourceType(), "系统资金类型").toUpperCase();
        lockFundSource(sourceType, request.sourceId());
        StatementLock statement = statement(request.statementLineId(), sessionId, true);
        FinancialCloseDtos.FundCandidate candidate = fundCandidate(session, sourceType, request.sourceId());
        if (!statement.direction().equals(candidate.direction())) throw new BusinessException("银行流水与系统资金收支方向不一致");
        BigDecimal amount = money(request.matchedAmount());
        if (amount.compareTo(statementRemaining(statement.id())) > 0) throw new BusinessException("匹配金额超过银行流水未匹配金额");
        if (amount.compareTo(candidate.remainingAmount()) > 0) throw new BusinessException("匹配金额超过系统资金未匹配金额");
        String category = cashFlowCategory(request.cashFlowCategory(), statement.direction());
        Actor actor = actor();
        Long id = insert("""
                insert into bank_reconciliation_match
                (session_id,statement_line_id,source_type,source_id,source_no,source_date,
                 source_direction,matched_amount,cash_flow_category,matching_method,match_score,
                 match_rule_version,import_batch_id,status,reason,matched_by,matched_by_name)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?,'ACTIVE',?,?,?)
                """, sessionId, statement.id(), candidate.sourceType(), candidate.sourceId(), candidate.sourceNo(),
                Date.valueOf(candidate.sourceDate()), candidate.direction(), amount, category,
                matchingMethod, matchScore, text(matchRuleVersion), importBatchId,
                text(request.reason()), actor.id(), actor.name());
        refreshStatementStatus(statement.id());
        recalculateSession(sessionId);
        if (writeEvent) reconciliationEvent(sessionId, "MATCH", session.status(), session.status(),
                candidate.sourceNo() + " 匹配 " + amount, actor);
        return id;
    }

    @Transactional
    public Long reverseMatch(Long matchId, String reason) {
        Map<String, Object> source = row(
                "select session_id,source_type,source_id from bank_reconciliation_match where id=?", matchId);
        if (source == null) throw new BusinessException("银行匹配记录不存在");
        long sessionId = number(source.get("session_id"));
        requireEditableSession(session(sessionId, true));
        lockFundSource(String.valueOf(source.get("source_type")), number(source.get("source_id")));
        Map<String, Object> match = requiredRow(
                "select * from bank_reconciliation_match where id=? for update", matchId);
        if ("REVERSED".equals(match.get("status"))) return matchId;
        Actor actor = actor();
        jdbc.update("""
                update bank_reconciliation_match set status='REVERSED',reason=?,reversed_by=?,reversed_by_name=?,
                  reversed_at=now() where id=? and status='ACTIVE'
                """, required(reason, "撤销原因"), actor.id(), actor.name(), matchId);
        refreshStatementStatus(number(match.get("statement_line_id")));
        recalculateSession(sessionId);
        reconciliationEvent(sessionId, "REVERSE_MATCH", "DRAFT", "DRAFT", required(reason, "撤销原因"), actor);
        return matchId;
    }

    @Transactional
    public Long createDifference(Long sessionId, FinancialCloseDtos.DifferenceSave request) {
        SessionLock session = session(sessionId, true);
        requireEditableSession(session);
        StatementLock statement = statement(request.statementLineId(), sessionId, true);
        String direction = enumValue(request.direction(), DIRECTIONS, "差异方向");
        if (!direction.equals(statement.direction())) throw new BusinessException("差异方向必须与银行流水方向一致");
        BigDecimal amount = money(request.amount());
        if (amount.compareTo(statementRemaining(statement.id())) > 0) throw new BusinessException("差异金额超过银行流水未解释金额");
        String type = enumValue(request.differenceType(), Set.of("BANK_NOT_BOOKED", "SYSTEM_NOT_BANK",
                "BANK_FEE", "INTEREST", "OTHER"), "差异类型");
        String category = cashFlowCategory(request.cashFlowCategory(), direction);
        Actor actor = actor();
        Long differenceId = insert("""
                insert into bank_reconciliation_difference
                (session_id,statement_line_id,difference_type,direction,amount,cash_flow_category,reason,
                 status,created_by,created_by_name)
                values (?,?,?,?,?,?,?,'OPEN',?,?)
                """, sessionId, statement.id(), type, direction, amount, category,
                required(request.reason(), "差异说明"), actor.id(), actor.name());
        long voucherId = 0L;
        if (Boolean.TRUE.equals(request.createVoucher())) {
            BankAccount bank = bankAccount(session.bankAccountId(), false);
            if ("BANK_FEE".equals(type)) {
                if (!"DEBIT".equals(direction)) throw new BusinessException("银行手续费必须是银行支出");
                voucherId = accountingService.createSourceVoucher("BANK_DIFFERENCE", differenceId,
                        "YHCF-" + differenceId, statement.transactionDate(), "银行手续费：" + request.reason(),
                        List.of(new AccountingService.SystemVoucherLine("5603", "银行手续费", amount, ZERO, 0L, 0L, 0L),
                                new AccountingService.SystemVoucherLine(bank.ledgerAccountCode(), "银行手续费", ZERO, amount, 0L, 0L, 0L)));
            } else if ("INTEREST".equals(type)) {
                if (!"CREDIT".equals(direction)) throw new BusinessException("利息收入必须是银行收入");
                voucherId = accountingService.createSourceVoucher("BANK_DIFFERENCE", differenceId,
                        "YHCF-" + differenceId, statement.transactionDate(), "银行利息收入：" + request.reason(),
                        List.of(new AccountingService.SystemVoucherLine(bank.ledgerAccountCode(), "银行利息收入", amount, ZERO, 0L, 0L, 0L),
                                new AccountingService.SystemVoucherLine("5603", "银行利息冲减财务费用", ZERO, amount, 0L, 0L, 0L)));
            } else {
                throw new BusinessException("只有银行手续费和利息收入可以自动生成凭证，其他差异必须选择正确科目编制凭证");
            }
            jdbc.update("update bank_reconciliation_difference set accounting_voucher_id=? where id=?", voucherId, differenceId);
        }
        refreshStatementStatus(statement.id());
        recalculateSession(sessionId);
        reconciliationEvent(sessionId, "EXPLAIN_DIFFERENCE", session.status(), session.status(),
                type + " " + amount + "；" + required(request.reason(), "差异说明"), actor);
        return differenceId;
    }

    @Transactional
    public Long reverseDifference(Long differenceId, String reason) {
        Map<String, Object> difference = row(
                "select * from bank_reconciliation_difference where id=? for update", differenceId);
        if (difference == null) throw new BusinessException("银行差异记录不存在");
        long sessionId = number(difference.get("session_id"));
        SessionLock session = session(sessionId, true);
        requireEditableSession(session);
        if ("REVERSED".equals(difference.get("status"))) return differenceId;
        long voucherId = number(difference.get("accounting_voucher_id"));
        if (voucherId > 0) {
            Date transactionDate = jdbc.queryForObject(
                    "select transaction_date from bank_statement_line where id=?", Date.class,
                    number(difference.get("statement_line_id")));
            accountingService.disposeSourceVoucher(voucherId,
                    transactionDate == null ? session.to() : transactionDate.toLocalDate(),
                    "撤销银行差异：" + required(reason, "撤销原因"));
        }
        Actor actor = actor();
        jdbc.update("""
                update bank_reconciliation_difference set status='REVERSED',reversed_by=?,reversed_by_name=?,
                  reversed_at=now(),reason=concat(reason,'；撤销：',?) where id=? and status<>'REVERSED'
                """, actor.id(), actor.name(), required(reason, "撤销原因"), differenceId);
        long statementId = number(difference.get("statement_line_id"));
        refreshStatementStatus(statementId);
        recalculateSession(sessionId);
        reconciliationEvent(sessionId, "REVERSE_DIFFERENCE", session.status(), session.status(), reason, actor);
        return differenceId;
    }

    @Transactional
    public Long voidStatementLine(Long statementLineId, String reason) {
        Map<String, Object> line = row("select * from bank_statement_line where id=? for update", statementLineId);
        if (line == null) throw new BusinessException("银行流水不存在");
        long sessionId = number(line.get("session_id"));
        SessionLock session = session(sessionId, true);
        requireEditableSession(session);
        if ("VOID".equals(line.get("status"))) return statementLineId;
        long links = scalarLong("""
                select (select count(*) from bank_reconciliation_match where statement_line_id=? and status='ACTIVE')+
                       (select count(*) from bank_reconciliation_difference where statement_line_id=? and status<>'REVERSED')
                """, statementLineId, statementLineId);
        if (links > 0) throw new BusinessException("银行流水已有匹配或差异说明，请先逐项撤销");
        Actor actor = actor();
        jdbc.update("""
                update bank_statement_line set status='VOID',void_reason=?,voided_by=?,voided_by_name=?,voided_at=now()
                where id=? and status<>'VOID'
                """, required(reason, "作废原因"), actor.id(), actor.name(), statementLineId);
        recalculateSession(sessionId);
        reconciliationEvent(sessionId, "VOID_STATEMENT_LINE", session.status(), session.status(), reason, actor);
        return statementLineId;
    }

    @Transactional
    public Long createPendingSystem(Long sessionId, FinancialCloseDtos.PendingSystemSave request) {
        SessionLock session = session(sessionId, true);
        requireEditableSession(session);
        String sourceType = required(request.sourceType(), "系统资金类型").toUpperCase();
        lockFundSource(sourceType, request.sourceId());
        FinancialCloseDtos.FundCandidate candidate = fundCandidate(session, sourceType, request.sourceId());
        if (candidate.remainingAmount().compareTo(CENT) < 0) throw new BusinessException("系统资金已经全部匹配或归入其他账户未达项");
        Actor actor = actor();
        try {
            Long id = insert("""
                    insert into bank_reconciliation_pending_system
                    (session_id,source_type,source_id,source_no,source_date,direction,amount,
                     cash_flow_category,reason,status,created_by,created_by_name)
                    values (?,?,?,?,?,?,?,?,?,'OPEN',?,?)
                    """, sessionId, candidate.sourceType(), candidate.sourceId(), candidate.sourceNo(),
                    Date.valueOf(candidate.sourceDate()), candidate.direction(), candidate.remainingAmount(),
                    cashFlowCategory(request.cashFlowCategory(), candidate.direction()),
                    required(request.reason(), "未达原因"), actor.id(), actor.name());
            recalculateSession(sessionId);
            reconciliationEvent(sessionId, "MARK_PENDING_SYSTEM", session.status(), session.status(),
                    candidate.sourceNo() + " " + candidate.remainingAmount(), actor);
            return id;
        } catch (DuplicateKeyException ex) {
            throw new BusinessException("该系统资金已经归入银行匹配或未达项");
        }
    }

    @Transactional
    public Long reversePendingSystem(Long pendingId, String reason) {
        Map<String, Object> source = row(
                "select session_id,source_type,source_id from bank_reconciliation_pending_system where id=?", pendingId);
        if (source == null) throw new BusinessException("系统未达项不存在");
        long sessionId = number(source.get("session_id"));
        requireEditableSession(session(sessionId, true));
        lockFundSource(String.valueOf(source.get("source_type")), number(source.get("source_id")));
        Map<String, Object> pending = requiredRow(
                "select * from bank_reconciliation_pending_system where id=? for update", pendingId);
        if ("REVERSED".equals(pending.get("status"))) return pendingId;
        jdbc.update("""
                update bank_reconciliation_pending_system set status='REVERSED',reason=concat(reason,'；撤销：',?),
                  resolved_at=now() where id=? and status='OPEN'
                """, required(reason, "撤销原因"), pendingId);
        recalculateSession(sessionId);
        reconciliationEvent(sessionId, "REVERSE_PENDING_SYSTEM", "DRAFT", "DRAFT",
                required(reason, "撤销原因"), actor());
        return pendingId;
    }

    @Transactional
    public Long confirmReconciliation(Long sessionId, String reason) {
        SessionLock session = session(sessionId, true);
        if ("CONFIRMED".equals(session.status())) return sessionId;
        requireEditableSession(session);
        recalculateSession(sessionId);
        session = session(sessionId, true);
        BigDecimal statementCalculated = session.openingBalance()
                .add(statementTotal(sessionId, "CREDIT")).subtract(statementTotal(sessionId, "DEBIT")).setScale(2);
        if (statementCalculated.compareTo(session.statementClosing()) != 0)
            throw new BusinessException("银行流水按期初余额计算的期末余额与对账单期末余额不一致");
        validateStatementBalances(session);
        if (scalarLong("""
                select count(*) from bank_statement_line l where l.session_id=? and l.status<>'VOID' and
                  l.amount>(select coalesce(sum(m.matched_amount),0) from bank_reconciliation_match m
                            where m.statement_line_id=l.id and m.status='ACTIVE')+
                           (select coalesce(sum(d.amount),0) from bank_reconciliation_difference d
                            where d.statement_line_id=l.id and d.status<>'REVERSED')+0.005
                """, sessionId) > 0)
            throw new BusinessException("仍有银行流水未匹配或未形成差异说明");
        if (!fundCandidates(session).isEmpty()) throw new BusinessException("仍有系统银行收付未匹配或未登记银行未达项");
        if (session.difference().abs().compareTo(CENT) >= 0)
            throw new BusinessException("银行余额调节表仍不平衡，不能确认对账");
        Actor actor = actor();
        jdbc.update("""
                update bank_reconciliation_session set status='CONFIRMED',close_reason=?,confirmed_by=?,
                  confirmed_by_name=?,confirmed_at=now() where id=? and status in ('DRAFT','REOPENED')
                """, required(reason, "确认说明"), actor.id(), actor.name(), sessionId);
        reconciliationEvent(sessionId, "CONFIRM", session.status(), "CONFIRMED", reason, actor);
        return sessionId;
    }

    @Transactional
    public Long reopenReconciliation(Long sessionId, String reason) {
        SessionLock session = session(sessionId, true);
        if (!"CONFIRMED".equals(session.status())) return sessionId;
        Period period = period(session.periodId(), false);
        if ("CLOSED".equals(period.status())) throw new BusinessException("会计期间已结账，必须先执行规范反结账");
        jdbc.update("""
                update bank_reconciliation_session set status='REOPENED',close_reason=?,reopened_at=now()
                where id=? and status='CONFIRMED'
                """, required(reason, "重新打开原因"), sessionId);
        reconciliationEvent(sessionId, "REOPEN", "CONFIRMED", "REOPENED", reason, actor());
        return sessionId;
    }

    public FinancialCloseDtos.PeriodClosePreview closePreview(Long periodId) {
        Period period = period(periodId, false);
        List<FinancialCloseDtos.CloseCheck> checks = buildCloseChecks(period);
        AccountingDtos.TrialBalanceReport trial = accountingService.trialBalance(period.startDate(), period.endDate());
        FinancialCloseDtos.FinancialReport balance = balanceSheet(period.endDate());
        FinancialCloseDtos.FinancialReport income = incomeStatement(period.startDate(), period.endDate());
        FinancialCloseDtos.CashFlowReport cash = cashFlow(period.startDate(), period.endDate());
        VoucherSummary profit = profitVoucher(period.id());
        Map<String, Object> last = row("""
                select id,status,snapshot_hash from accounting_period_close_run
                where period_id=? order by id desc limit 1
                """, periodId);
        boolean closable = "OPEN".equals(period.status()) &&
                checks.stream().noneMatch(x -> "BLOCKED".equals(x.status()));
        List<FinancialCloseDtos.PeriodEventView> events = jdbc.query("""
                select id,action,from_status,to_status,reason,operator_name,created_at
                from accounting_period_event where period_id=? order by id
                """, (rs, rowNum) -> new FinancialCloseDtos.PeriodEventView(
                rs.getLong("id"), rs.getString("action"), rs.getString("from_status"),
                rs.getString("to_status"), rs.getString("reason"), rs.getString("operator_name"),
                rs.getTimestamp("created_at").toLocalDateTime()), periodId);
        return new FinancialCloseDtos.PeriodClosePreview(period.id(), period.name(), period.startDate(),
                period.endDate(), period.status(), checks, closable, profit.id(), profit.no(), profit.status(),
                balance.primaryTotal(), balance.secondaryTotal(), income.difference(), cash.netIncrease(),
                trial.difference(), last == null ? 0L : number(last.get("id")),
                last == null ? "" : String.valueOf(last.get("status")),
                last == null ? "" : String.valueOf(last.get("snapshot_hash")), events);
    }

    @Transactional
    public Long generateProfitVoucher(Long periodId) {
        Period period = period(periodId, true);
        if (!"OPEN".equals(period.status())) throw new BusinessException("只有开放期间可以生成损益结转凭证");
        if (period.endDate().isAfter(LocalDate.now())) throw new BusinessException("会计期间尚未结束，不能提前结转损益");
        VoucherSummary existing = profitVoucher(periodId);
        if (existing.id() > 0) return existing.id();
        List<ProfitAccount> accounts = jdbc.query("""
                select a.account_code,a.account_name,a.category,
                       coalesce(sum(case when v.source_type<>'PERIOD_PROFIT_CLOSE' then l.debit_amount else 0 end),0) debit_amount,
                       coalesce(sum(case when v.source_type<>'PERIOD_PROFIT_CLOSE' then l.credit_amount else 0 end),0) credit_amount
                from accounting_account a
                left join accounting_ledger_entry l on l.account_id=a.id and l.voucher_date between ? and ?
                left join accounting_voucher v on v.id=l.voucher_id
                where a.entity_id=? and a.category in ('REVENUE','COST','EXPENSE') and a.leaf=1
                group by a.id,a.account_code,a.account_name,a.category order by a.account_code
                """, (rs, rowNum) -> new ProfitAccount(rs.getString("account_code"), rs.getString("account_name"),
                rs.getString("category"), money(rs.getBigDecimal("debit_amount")),
                money(rs.getBigDecimal("credit_amount"))), Date.valueOf(period.startDate()),
                Date.valueOf(period.endDate()), period.entityId());
        List<AccountingService.SystemVoucherLine> lines = new ArrayList<>();
        BigDecimal debit = ZERO;
        BigDecimal credit = ZERO;
        for (ProfitAccount account : accounts) {
            BigDecimal balance = "REVENUE".equals(account.category()) ?
                    account.credit().subtract(account.debit()) : account.debit().subtract(account.credit());
            if (balance.abs().compareTo(CENT) < 0) continue;
            String summary = period.name() + "损益结转 " + account.name();
            if ("REVENUE".equals(account.category())) {
                if (balance.signum() > 0) { lines.add(new AccountingService.SystemVoucherLine(account.code(), summary, balance, ZERO, 0L, 0L, 0L)); debit = debit.add(balance); }
                else { lines.add(new AccountingService.SystemVoucherLine(account.code(), summary, ZERO, balance.abs(), 0L, 0L, 0L)); credit = credit.add(balance.abs()); }
            } else {
                if (balance.signum() > 0) { lines.add(new AccountingService.SystemVoucherLine(account.code(), summary, ZERO, balance, 0L, 0L, 0L)); credit = credit.add(balance); }
                else { lines.add(new AccountingService.SystemVoucherLine(account.code(), summary, balance.abs(), ZERO, 0L, 0L, 0L)); debit = debit.add(balance.abs()); }
            }
        }
        if (lines.isEmpty()) return 0L;
        BigDecimal difference = debit.subtract(credit).setScale(2);
        if (difference.signum() > 0) lines.add(new AccountingService.SystemVoucherLine("3103", period.name() + "结转本年利润", ZERO, difference, 0L, 0L, 0L));
        if (difference.signum() < 0) lines.add(new AccountingService.SystemVoucherLine("3103", period.name() + "结转本年亏损", difference.abs(), ZERO, 0L, 0L, 0L));
        long version = scalarLong("""
                select count(*)+1 from accounting_voucher
                where source_type='PERIOD_PROFIT_CLOSE' and source_id=? and original_voucher_id=0
                """, period.id());
        return accountingService.createSourceVoucher("PERIOD_PROFIT_CLOSE", period.id(),
                "JZ-" + period.fiscalYear() + String.format("%02d", period.periodNo()) + "-" + version, period.endDate(),
                period.name() + "损益结转", lines);
    }

    @Transactional
    public FinancialCloseDtos.CloseResult closePeriod(Long periodId, String reason) {
        Period period = period(periodId, true);
        if ("CLOSED".equals(period.status())) {
            Map<String, Object> run = requiredRow("select * from accounting_period_close_run where period_id=? and status='CLOSED' order by id desc limit 1", periodId);
            return new FinancialCloseDtos.CloseResult(periodId, number(run.get("id")), "CLOSED", String.valueOf(run.get("snapshot_hash")));
        }
        String closeReason = required(reason, "结账原因");
        FinancialCloseDtos.PeriodClosePreview preview = closePreview(periodId);
        List<FinancialCloseDtos.CloseCheck> blocked = preview.checks().stream().filter(x -> "BLOCKED".equals(x.status())).toList();
        if (!blocked.isEmpty()) throw new BusinessException("期末结账仍有阻断项：" + blocked.get(0).checkName() + "（" + blocked.get(0).detail() + "）");
        Actor actor = actor();
        int attempt = (int) scalarLong("select coalesce(max(attempt_no),0)+1 from accounting_period_close_run where period_id=?", periodId);
        String snapshot = sha256(period.id() + "|" + period.endDate() + "|" + preview.balanceSheetAssets() + "|" +
                preview.balanceSheetLiabilitiesEquity() + "|" + preview.incomeNetProfit() + "|" +
                preview.cashNetIncrease() + "|" + preview.trialDifference() + "|" + preview.checks());
        Long runId = insert("""
                insert into accounting_period_close_run
                (entity_id,period_id,attempt_no,status,close_reason,profit_voucher_id,
                 balance_sheet_assets,balance_sheet_liabilities_equity,income_net_profit,
                 cash_net_increase,trial_difference,snapshot_hash,operator_id,operator_name,closed_at)
                values (?,?,?,'CLOSED',?,?,?,?,?,?,?,?,?,?,now())
                """, period.entityId(), period.id(), attempt, closeReason,
                preview.profitVoucherId(), preview.balanceSheetAssets(), preview.balanceSheetLiabilitiesEquity(),
                preview.incomeNetProfit(), preview.cashNetIncrease(), preview.trialDifference(), snapshot,
                actor.id(), actor.name());
        for (FinancialCloseDtos.CloseCheck check : preview.checks()) {
            jdbc.update("""
                    insert into accounting_period_close_check
                    (close_run_id,check_code,check_name,status,issue_count,amount_difference,detail)
                    values (?,?,?,?,?,?,?)
                    """, runId, check.checkCode(), check.checkName(), check.status(), check.issueCount(),
                    check.amountDifference(), check.detail());
        }
        jdbc.update("""
                update accounting_period set status='CLOSED',close_reason=?,closed_by=?,closed_by_name=?,closed_at=now()
                where id=? and status='OPEN'
                """, closeReason, actor.id(), actor.name(), periodId);
        periodEvent(periodId, "FORMAL_CLOSE", "OPEN", "CLOSED", closeReason + "；快照=" + snapshot, actor);
        return new FinancialCloseDtos.CloseResult(periodId, runId, "CLOSED", snapshot);
    }

    @Transactional
    public FinancialCloseDtos.CloseResult reopenPeriod(Long periodId, String reason) {
        Period period = period(periodId, true);
        if ("OPEN".equals(period.status())) return new FinancialCloseDtos.CloseResult(periodId, 0L, "OPEN", "");
        if (scalarLong("""
                select count(*) from accounting_period where entity_id=? and start_date>? and status='CLOSED'
                """, period.entityId(), Date.valueOf(period.endDate())) > 0) {
            throw new BusinessException("存在后续已结账期间，请按时间倒序逐期反结账");
        }
        Map<String, Object> run = requiredRow("""
                select * from accounting_period_close_run where period_id=? and status='CLOSED' order by id desc limit 1 for update
                """, periodId);
        Actor actor = actor();
        String requiredReason = required(reason, "反结账原因");
        jdbc.update("""
                update accounting_period set status='OPEN',close_reason='',reopened_at=now()
                where id=? and status='CLOSED'
                """, periodId);
        jdbc.update("""
                update accounting_archive_batch set status='SUPERSEDED',superseded_reason=?,
                  superseded_at=now(),error_message=?
                where period_id=? and archive_mode='FINAL' and status='COMPLETED'
                """, "期间已反结账：" + requiredReason, "期间已反结账：" + requiredReason, periodId);
        long reversalId = 0L;
        long profitVoucherId = number(run.get("profit_voucher_id"));
        if (profitVoucherId > 0) reversalId = accountingService.disposeSourceVoucher(profitVoucherId,
                period.endDate(), "反结账冲销损益结转：" + requiredReason);
        jdbc.update("""
                update accounting_period_close_run set status='REOPENED',reversal_voucher_id=?,reopened_at=now()
                where id=? and status='CLOSED'
                """, reversalId, number(run.get("id")));
        periodEvent(periodId, "FORMAL_REOPEN", "CLOSED", "OPEN",
                requiredReason + (reversalId > 0 ? "；已生成冲销凭证 #" + reversalId : ""), actor);
        return new FinancialCloseDtos.CloseResult(periodId, number(run.get("id")), "REOPENED",
                String.valueOf(run.get("snapshot_hash")));
    }

    private List<FinancialCloseDtos.CloseCheck> buildCloseChecks(Period period) {
        List<FinancialCloseDtos.CloseCheck> checks = new ArrayList<>();
        long periodDateIssue = period.endDate().isAfter(LocalDate.now()) ? 1 : 0;
        checks.add(check("PERIOD_END", "期间已经结束", periodDateIssue, ZERO,
                periodDateIssue == 0 ? "期间截止日不晚于当前业务日期" : "期间尚未结束，禁止提前结账"));
        long priorOpen = scalarLong("""
                select count(*) from accounting_period where entity_id=? and end_date<? and status='OPEN'
                """, period.entityId(), Date.valueOf(period.startDate()));
        checks.add(check("PRIOR_PERIOD", "以前期间已经结账", priorOpen, ZERO,
                priorOpen == 0 ? "不存在未结账的以前期间" : "仍有 " + priorOpen + " 个以前期间未结账"));
        long pendingVouchers = scalarLong("""
                select count(*) from accounting_voucher where entity_id=? and voucher_date between ? and ?
                  and status in ('DRAFT','REVIEWED')
                """, period.entityId(), Date.valueOf(period.startDate()), Date.valueOf(period.endDate()));
        checks.add(check("VOUCHER_STATUS", "凭证全部记账", pendingVouchers, ZERO,
                pendingVouchers == 0 ? "期间内没有暂存或已审核未记账凭证" : "仍有 " + pendingVouchers + " 张未记账凭证"));
        long posting = scalarLong("""
                select count(*) from business_posting_batch b
                where b.business_date between ? and ? and
                  ((b.status='POSTED' and b.posting_action not in ('COMMITMENT_POST','COMMITMENT_REVERSE')) or
                   (b.status='REVERSED' and b.reversal_batch_id>0 and b.posting_action not in ('COMMITMENT_POST','COMMITMENT_REVERSE')))
                  and not exists(select 1 from accounting_generation_result g
                    where g.entity_id=? and g.business_posting_batch_id=b.id)
                """, Date.valueOf(period.startDate()), Date.valueOf(period.endDate()), period.entityId());
        checks.add(check("BUSINESS_POSTING", "业务过账全部制证", posting, ZERO,
                posting == 0 ? "采购、销售、库存和往来过账均已有会计处理结果" : "仍有 " + posting + " 个业务过账未制证"));
        long inventoryDocuments = scalarLong("""
                select (select count(*) from inventory_transfer_order where business_date between ? and ? and status in ('DRAFT','IN_TRANSIT'))+
                       (select count(*) from inventory_stocktake_order where business_date between ? and ? and status='DRAFT')+
                       (select count(*) from purchase_return_order where business_date between ? and ? and status='DRAFT')+
                       (select count(*) from inventory_cost_adjustment where business_date between ? and ? and status='DRAFT')+
                       (select count(*) from inventory_non_trade_document where business_date between ? and ? and status in ('DRAFT','ISSUED_PENDING_SIGNATURE'))+
                       (select count(*) from landed_cost_allocation where business_date between ? and ? and status='DRAFT')
                """, Date.valueOf(period.startDate()), Date.valueOf(period.endDate()),
                Date.valueOf(period.startDate()), Date.valueOf(period.endDate()),
                Date.valueOf(period.startDate()), Date.valueOf(period.endDate()),
                Date.valueOf(period.startDate()), Date.valueOf(period.endDate()),
                Date.valueOf(period.startDate()), Date.valueOf(period.endDate()),
                Date.valueOf(period.startDate()), Date.valueOf(period.endDate()));
        checks.add(check("INVENTORY_DOCUMENTS", "库存单据完成", inventoryDocuments, ZERO,
                inventoryDocuments == 0 ? "调拨、盘点、采购退货、其他出入库、到岸成本和成本调整均已完成" : "仍有 " + inventoryDocuments + " 张库存单据未完成"));
        long directTradeIssues = scalarLong("""
                select
                  (select count(*) from direct_trade_order
                    where business_date between ? and ? and status='DRAFT')+
                  (select count(*) from direct_trade_delivery
                    where business_date between ? and ? and status='DISPATCHED')
                """, Date.valueOf(period.startDate()), Date.valueOf(period.endDate()),
                Date.valueOf(period.startDate()), Date.valueOf(period.endDate()));
        checks.add(check("DIRECT_TRADE", "直运购销事实完成", directTradeIssues, ZERO,
                directTradeIssues == 0
                        ? "期间内直运草稿与待确认客户实收的供应商发出批次均已处理"
                        : "仍有 " + directTradeIssues + " 张直运草稿或待实收批次未处理"));
        long fundAdjustmentDrafts = scalarLong("""
                select count(*) from partner_fund_adjustment
                where business_date between ? and ? and status='DRAFT'
                """, Date.valueOf(period.startDate()), Date.valueOf(period.endDate()));
        checks.add(check("PARTNER_FUND_ADJUSTMENTS", "往来余额调整完成", fundAdjustmentDrafts, ZERO,
                fundAdjustmentDrafts == 0 ? "奖励、返利、期初和差错余额调整均已确认或作废"
                        : "仍有 " + fundAdjustmentDrafts + " 张余额调整草稿未处理"));
        long treasuryIssues = scalarLong("""
                select
                  (select count(*) from treasury_document where business_date between ? and ? and status='DRAFT')+
                  (select count(*) from treasury_document where business_date between ? and ?
                    and status in ('CONFIRMED','PARTIALLY_SETTLED','SETTLED') and accounting_voucher_id=0)+
                  (select count(*) from treasury_fund_transaction where transaction_date between ? and ?
                    and status='ACTIVE' and accounting_voucher_id=0)+
                  (select count(*) from treasury_document d where d.business_date between ? and ? and
                    d.settled_amount<>coalesce((select sum(f.amount) from treasury_fund_transaction f
                      where f.document_id=d.id and f.status='ACTIVE'),0))+
                  (select count(*) from treasury_document d where d.business_date between ? and ?
                    and d.document_type='PURCHASE_LANDED_COST' and d.status in ('CONFIRMED','PARTIALLY_SETTLED','SETTLED')
                    and not exists(select 1 from landed_cost_allocation a where a.treasury_document_id=d.id
                      and a.status='CONFIRMED'))+
                  (select count(*) from treasury_payment_request r where r.request_date between ? and ? and
                    r.paid_amount<>coalesce((select sum(f.amount) from treasury_fund_transaction f
                      where f.payment_request_id=r.id and f.status='ACTIVE'),0))
                """, Date.valueOf(period.startDate()), Date.valueOf(period.endDate()),
                Date.valueOf(period.startDate()), Date.valueOf(period.endDate()),
                Date.valueOf(period.startDate()), Date.valueOf(period.endDate()),
                Date.valueOf(period.startDate()), Date.valueOf(period.endDate()),
                Date.valueOf(period.startDate()), Date.valueOf(period.endDate()),
                Date.valueOf(period.startDate()), Date.valueOf(period.endDate()));
        checks.add(check("TREASURY_DOCUMENTS", "费用、其他往来与出纳事实完整", treasuryIssues, ZERO,
                treasuryIssues == 0 ? "费用源单、付款申请、实际资金、日记账和凭证可逐笔勾稽"
                        : "存在 " + treasuryIssues + " 条未完成、未制证或金额不一致的费用出纳事项"));
        long inventoryMismatch = inventoryMismatchCount(period.endDate());
        checks.add(check("INVENTORY_RECONCILIATION", "库存账实勾稽", inventoryMismatch, ZERO,
                inventoryMismatch == 0 ? "库存批次、余额及成本一致" : "存在 " + inventoryMismatch + " 个库存数量或成本差异"));
        long partnerMismatch = partnerMovementMismatchCount(period.endDate());
        checks.add(check("PARTNER_BALANCE", "应收应付勾稽", partnerMismatch, ZERO,
                partnerMismatch == 0 ? "应收应付流水累计与末次余额一致" : "存在 " + partnerMismatch + " 个往来余额差异"));
        long allocationIssues = scalarLong("""
                select
                  (select count(*) from payment p where p.received_date between ? and ? and (
                    (p.fund_nature='SETTLEMENT' and (p.allocated_amount+p.unallocated_amount<>p.amount or
                      coalesce((select sum(a.allocated_amount) from payment_allocation a
                                where a.payment_id=p.id and a.status='ACTIVE'),0)<>
                        case when p.verification_status='REVERSED' then 0 else p.allocated_amount end or
                      (p.verification_status<>'REVERSED' and coalesce((select sum(case
                         when e.fact_type in ('RECEIVABLE_SETTLEMENT','RECEIVABLE_ACCEPTANCE') then e.amount
                         when e.fact_type='CUSTOMER_RECEIPT_ALLOCATION' and e.direction='INCREASE' then e.amount
                         when e.fact_type='CUSTOMER_RECEIPT_ALLOCATION' and e.direction='DECREASE' then -e.amount
                         else 0 end) from business_posting_entry e join business_posting_batch b
                           on b.id=e.posting_batch_id where b.source_type='PAYMENT' and b.source_id=p.id
                           and b.status='POSTED'),0)<>p.allocated_amount))) or
                    (p.fund_nature='ADVANCE' and (p.allocated_amount<>0 or p.unallocated_amount<>0 or
                      exists(select 1 from payment_allocation a where a.payment_id=p.id and a.status='ACTIVE'))) or
                    exists(select 1 from payment_allocation a where a.payment_id=p.id and a.status='ACTIVE' and
                      ((a.target_type='SALES_ORDER' and not exists(
                         select 1 from sales_order so where so.id=a.target_id and so.customer_id=p.customer_id)) or
                       (a.target_type='OUTBOUND' and not exists(
                         select 1 from outbound_order o where o.id=a.target_id and o.customer_id=p.customer_id))))))+
                  (select count(*) from supplier_payment p where p.paid_date between ? and ? and (
                    (p.fund_nature='SETTLEMENT' and (p.allocated_amount+p.unallocated_amount<>p.amount or
                      coalesce((select sum(a.allocated_amount) from supplier_payment_allocation a
                                where a.supplier_payment_id=p.id and a.status='ACTIVE'),0)<>
                        case when p.verification_status='REVERSED' then 0 else p.allocated_amount end or
                      (p.verification_status<>'REVERSED' and coalesce((select sum(case
                         when e.fact_type='PAYABLE_SETTLEMENT' then e.amount
                         when e.fact_type='SUPPLIER_PAYMENT_ALLOCATION' and e.direction='INCREASE' then e.amount
                         when e.fact_type='SUPPLIER_PAYMENT_ALLOCATION' and e.direction='DECREASE' then -e.amount
                         else 0 end) from business_posting_entry e join business_posting_batch b
                           on b.id=e.posting_batch_id where b.source_type='SUPPLIER_PAYMENT' and b.source_id=p.id
                           and b.status='POSTED'),0)<>p.allocated_amount))) or
                    (p.fund_nature='ADVANCE' and (p.allocated_amount<>0 or p.unallocated_amount<>0 or
                      exists(select 1 from supplier_payment_allocation a
                             where a.supplier_payment_id=p.id and a.status='ACTIVE'))) or
                    exists(select 1 from supplier_payment_allocation a
                      where a.supplier_payment_id=p.id and a.status='ACTIVE' and
                      ((a.target_type='PURCHASE_ORDER' and not exists(
                         select 1 from purchase_order po where po.id=a.target_id and po.supplier_id=p.supplier_id)) or
                       (a.target_type='INBOUND' and not exists(
                         select 1 from inbound_order i where i.id=a.target_id and i.supplier_id=p.supplier_id))))))
                """, Date.valueOf(period.startDate()), Date.valueOf(period.endDate()),
                Date.valueOf(period.startDate()), Date.valueOf(period.endDate()));
        checks.add(check("PAYMENT_ALLOCATION", "收付款核销分配一致", allocationIssues, ZERO,
                allocationIssues == 0 ? "收付款头、有效分配、未分配余额及往来单位一致" :
                        "存在 " + allocationIssues + " 笔收付款分配金额或目标往来单位不一致"));
        long acceptanceIssues = scalarLong("""
                select
                  (select count(*) from payment p
                   where p.payment_method='ACCEPTANCE' and p.received_date between ? and ?
                     and p.verification_status<>'REVERSED'
                     and not exists(select 1 from business_posting_batch b
                       join business_posting_entry e on e.posting_batch_id=b.id
                       where b.source_type='PAYMENT' and b.source_id=p.id and b.posting_action='POST'
                         and e.fact_type='RECEIVABLE_ACCEPTANCE'))+
                  (select count(*) from acceptance_bill_event ae
                   where ae.occurred_date between ? and ? and (ae.cash_amount<>0 or ae.event_type='ENDORSE')
                     and not exists(select 1 from business_posting_batch b
                       where b.source_type='ACCEPTANCE_EVENT' and b.source_id=ae.id
                         and b.posting_action='POST' and b.status in ('POSTED','REVERSED')))
                """, Date.valueOf(period.startDate()), Date.valueOf(period.endDate()),
                Date.valueOf(period.startDate()), Date.valueOf(period.endDate()));
        checks.add(check("ACCEPTANCE_ACCOUNTING", "承兑票据会计处理完整", acceptanceIssues, ZERO,
                acceptanceIssues == 0 ? "承兑收票、到期、贴现和退票均有独立会计来源" :
                        "存在 " + acceptanceIssues + " 条承兑票据事项未按应收票据语义过账"));
        List<TaxInvoiceDtos.InvoiceRow> taxExceptions = taxInvoiceService.closeExceptions(
                period.startDate(), period.endDate());
        checks.add(check("TAX_INVOICE", "发票税票勾稽", taxExceptions.size(), ZERO,
                taxExceptions.isEmpty() ? "期间发票票货款账无异常" : "仍有 " + taxExceptions.size() + " 张发票存在勾稽异常"));
        List<Long> requiredBankAccounts = requiredBankAccountIds(period);
        long bankRequired = requiredBankAccounts.size();
        long bankConfirmed = requiredBankAccounts.stream().filter(bankAccountId -> scalarLong("""
                select count(*) from bank_reconciliation_session
                where entity_id=? and period_id=? and bank_account_id=?
                  and status='CONFIRMED' and abs(difference)<0.01
                """, period.entityId(), period.id(), bankAccountId) > 0).count();
        long bankIssues = Math.max(0, bankRequired - bankConfirmed);
        checks.add(check("BANK_RECONCILIATION", "银行账户逐户对账", bankIssues, ZERO,
                bankIssues == 0 ? "全部启用银行账户已确认余额调节表" : "仍有 " + bankIssues + " 个启用银行账户未完成对账"));
        FinancialCloseDtos.CashFlowReport cash = cashFlow(period.startDate(), period.endDate());
        long cashFlowIssue = cash.closingDifference().abs().compareTo(CENT) >= 0 ? 1 : 0;
        checks.add(check("CASH_FLOW_RECONCILIATION", "现金流量与货币资金勾稽", cashFlowIssue,
                cash.closingDifference(), cashFlowIssue == 0 ? "现金净增加额可与货币资金期末余额复核" :
                        "现金流量推算期末余额与总账货币资金差额 " + cash.closingDifference()));
        AccountingDtos.TrialBalanceReport trial = accountingService.trialBalance(period.startDate(), period.endDate());
        checks.add(check("TRIAL_BALANCE", "总账试算平衡", trial.difference().abs().compareTo(CENT) >= 0 ? 1 : 0,
                trial.difference(), trial.difference().abs().compareTo(CENT) < 0 ? "借贷试算平衡" : "试算差额 " + trial.difference()));
        FinancialCloseDtos.FinancialReport balance = balanceSheet(period.endDate());
        FinancialCloseDtos.FinancialReport income = incomeStatement(period.startDate(), period.endDate());
        List<String> unmapped = new ArrayList<>(balance.unmappedAccounts());
        income.unmappedAccounts().stream().filter(x -> !unmapped.contains(x)).forEach(unmapped::add);
        checks.add(check("REPORT_MAPPING", "报表科目映射完整", unmapped.size(), ZERO,
                unmapped.isEmpty() ? "余额和发生额科目均有有效报表映射" : "未映射：" + String.join("、", unmapped)));
        checks.add(check("BALANCE_SHEET", "资产负债表平衡", balance.difference().abs().compareTo(CENT) >= 0 ? 1 : 0,
                balance.difference(), balance.difference().abs().compareTo(CENT) < 0 ? "资产等于负债和所有者权益" : "报表差额 " + balance.difference()));
        VoucherSummary profit = profitVoucher(period.id());
        BigDecimal profitActivity = profitActivity(period);
        long profitIssue = profitActivity.abs().compareTo(CENT) < 0 ? 0 :
                (profit.id() > 0 && "POSTED".equals(profit.status()) ? 0 : 1);
        checks.add(check("PROFIT_CARRY_FORWARD", "损益结转已记账", profitIssue, ZERO,
                profitIssue == 0 ? "期间损益已结转或本期无损益发生" : "需生成、审核并记账损益结转凭证"));
        return checks;
    }

    private List<Long> requiredBankAccountIds(Period period) {
        return jdbc.queryForList("""
                select b.id from business_bank_account b
                where b.entity_id=? and b.opening_date<=? and (
                  b.status='ENABLED' or abs(b.opening_balance)>=0.01 or
                  exists(select 1 from bank_reconciliation_session s
                         where s.bank_account_id=b.id and s.period_id=?) or
                  exists(select 1 from payment p
                         where p.business_bank_account_id=b.id and p.payment_method='BANK'
                           and p.verification_status<>'REVERSED' and p.received_date between ? and ?) or
                  exists(select 1 from supplier_payment p
                         where p.business_bank_account_id=b.id and p.payment_method='BANK'
                           and p.verification_status<>'REVERSED' and p.paid_date between ? and ?) or
                  exists(select 1 from customer_advance_refund r
                         where r.business_bank_account_id=b.id and r.refund_method='BANK'
                           and r.status='ACTIVE' and r.refunded_date between ? and ?) or
                  exists(select 1 from acceptance_bill_event e
                         where e.business_bank_account_id=b.id and e.cash_amount<>0
                           and e.occurred_date between ? and ?) or
                  abs(coalesce((select sum(l.debit_amount-l.credit_amount)
                                from accounting_ledger_entry l
                                where l.entity_id=b.entity_id and l.account_id=b.ledger_account_id
                                  and l.voucher_date<=?),0))>=0.01
                ) order by b.id
                """, Long.class, period.entityId(), Date.valueOf(period.endDate()), period.id(),
                Date.valueOf(period.startDate()), Date.valueOf(period.endDate()),
                Date.valueOf(period.startDate()), Date.valueOf(period.endDate()),
                Date.valueOf(period.startDate()), Date.valueOf(period.endDate()),
                Date.valueOf(period.startDate()), Date.valueOf(period.endDate()),
                Date.valueOf(period.endDate()));
    }

    private FinancialCloseDtos.CloseCheck check(String code, String name, long issues,
                                                 BigDecimal difference, String detail) {
        return new FinancialCloseDtos.CloseCheck(code, name, issues == 0 ? "PASS" : "BLOCKED",
                issues, money(difference), detail);
    }

    private long inventoryMismatchCount(LocalDate cutoff) {
        long currentSubledgerMismatch = scalarLong("""
                select count(*) from (
                  select k.product_id,k.warehouse_id,
                         coalesce(b.quantity_available,0)-coalesce(s.quantity_available,0) quantity_diff,
                         coalesce(b.amount_cost,0)-coalesce(s.amount_cost,0) cost_diff
                  from (select product_id,warehouse_id from inventory_batch union select product_id,warehouse_id from stock_balance) k
                  left join (select product_id,warehouse_id,sum(quantity_available) quantity_available,sum(amount_cost) amount_cost
                             from inventory_batch group by product_id,warehouse_id) b
                    on b.product_id=k.product_id and b.warehouse_id=k.warehouse_id
                  left join stock_balance s on s.product_id=k.product_id and s.warehouse_id=k.warehouse_id
                ) x where abs(quantity_diff)>0.0005 or abs(cost_diff)>0.005
                """);
        long historicalNegative = scalarLong("""
                select count(*) from (
                  select e.inventory_batch_id,e.product_id,e.warehouse_id,
                         sum(case e.direction when 'INCREASE' then e.quantity
                                              when 'DECREASE' then -e.quantity else 0 end) quantity_balance,
                         sum(case e.direction when 'INCREASE' then e.amount
                                              when 'DECREASE' then -e.amount else 0 end) amount_balance
                  from business_posting_entry e
                  join business_posting_batch b on b.id=e.posting_batch_id
                  where b.business_date<=? and b.status='POSTED'
                    and e.fact_type in ('INVENTORY_ON_HAND','INVENTORY_COST')
                  group by e.inventory_batch_id,e.product_id,e.warehouse_id
                ) x where quantity_balance < -0.0005 or amount_balance < -0.005
                """, Date.valueOf(cutoff));
        return currentSubledgerMismatch + historicalNegative;
    }

    private long partnerMovementMismatchCount(LocalDate cutoff) {
        return scalarLong("""
                select count(*) from (
                  select customer_id partner_id,
                         sum(case direction when 'DEBIT' then amount else -amount end) calculated,
                         substring_index(group_concat(balance_after order by occurred_at desc,id desc),',',1)+0 recorded
                  from receivable_movement where occurred_at<? group by customer_id
                  union all
                  select supplier_id,
                         sum(case direction when 'INCREASE' then amount else -amount end),
                         substring_index(group_concat(balance_after order by occurred_at desc,id desc),',',1)+0
                  from payable_movement where occurred_at<? group by supplier_id
                  union all
                  select partner_id,
                         sum(amount),
                         substring_index(group_concat(amount_after order by created_at desc,id desc),',',1)+0
                  from partner_account_log where created_at<? group by partner_id,account_type
                ) x where abs(calculated-recorded)>0.005
                """, Timestamp.valueOf(cutoff.plusDays(1).atStartOfDay()),
                Timestamp.valueOf(cutoff.plusDays(1).atStartOfDay()),
                Timestamp.valueOf(cutoff.plusDays(1).atStartOfDay()));
    }

    private List<ReportDefinition> reportDefinitions(long entityId, String type, LocalDate date) {
        return jdbc.query("""
                select l.*,coalesce(group_concat(a.account_code order by a.account_code separator ','),'') account_codes
                from financial_report_line l
                left join financial_report_account_map m on m.line_id=l.id and m.effective_from<=?
                  and (m.effective_to is null or m.effective_to>=?)
                left join accounting_account a on a.id=m.account_id
                where l.entity_id=? and l.report_type=? and l.status='ENABLED' and l.effective_from<=?
                  and (l.effective_to is null or l.effective_to>=?)
                group by l.id order by l.line_order
                """, (rs, rowNum) -> new ReportDefinition(rs.getLong("id"), rs.getString("line_code"),
                rs.getString("line_name"), rs.getInt("line_order"), rs.getString("calculation_type"),
                rs.getString("formula_code"), rs.getString("normal_direction"),
                split(rs.getString("account_codes"))), Date.valueOf(date), Date.valueOf(date), entityId,
                type, Date.valueOf(date), Date.valueOf(date));
    }

    private BigDecimal mappedBalance(long entityId, long lineId, LocalDate date, String direction) {
        BigDecimal raw = scalarMoney("""
                select coalesce(sum((l.debit_amount-l.credit_amount)*m.amount_sign),0)
                from financial_report_account_map m
                join accounting_ledger_entry l on l.account_id=m.account_id and l.entity_id=m.entity_id
                where m.entity_id=? and m.line_id=? and m.effective_from<=?
                  and (m.effective_to is null or m.effective_to>=?) and l.voucher_date<=?
                """, entityId, lineId, Date.valueOf(date), Date.valueOf(date), Date.valueOf(date));
        return "CREDIT".equals(direction) ? raw.negate().setScale(2) : raw.setScale(2);
    }

    private BigDecimal mappedActivity(long entityId, long lineId, LocalDate from, LocalDate to, String direction) {
        BigDecimal raw = scalarMoney("""
                select coalesce(sum((l.debit_amount-l.credit_amount)*m.amount_sign),0)
                from financial_report_account_map m
                join accounting_ledger_entry l on l.account_id=m.account_id and l.entity_id=m.entity_id
                join accounting_voucher v on v.id=l.voucher_id
                where m.entity_id=? and m.line_id=? and m.effective_from<=?
                  and (m.effective_to is null or m.effective_to>=?) and l.voucher_date between ? and ?
                  and v.source_type<>'PERIOD_PROFIT_CLOSE'
                """, entityId, lineId, Date.valueOf(to), Date.valueOf(to), Date.valueOf(from), Date.valueOf(to));
        return "CREDIT".equals(direction) ? raw.negate().setScale(2) : raw.setScale(2);
    }

    private void applyBalanceFormulas(long entityId, LocalDate date, Map<String, BigDecimal> values) {
        BigDecimal assets = categoryBalance(entityId, Set.of("ASSET"), date);
        BigDecimal liabilities = categoryBalance(entityId, Set.of("LIABILITY"), date);
        BigDecimal equity = categoryBalance(entityId, Set.of("EQUITY"), date);
        BigDecimal unclosedProfit = categoryBalance(entityId, Set.of("REVENUE"), date)
                .subtract(categoryBalance(entityId, Set.of("COST", "EXPENSE"), date)).setScale(2);
        values.put("BS_ASSET_TOTAL", assets);
        values.put("BS_LIABILITY_TOTAL", liabilities);
        values.put("BS_CURRENT_PROFIT", money(values.get("BS_CURRENT_PROFIT")).add(unclosedProfit).setScale(2));
        values.put("BS_EQUITY_TOTAL", equity.add(unclosedProfit).setScale(2));
        values.put("BS_LIABILITY_EQUITY_TOTAL", liabilities.add(equity).add(unclosedProfit).setScale(2));
    }

    private void applyIncomeFormulas(long entityId, LocalDate from, LocalDate to, Map<String, BigDecimal> values) {
        BigDecimal revenue = activityByCategory(entityId, Set.of("REVENUE"), from, to);
        BigDecimal expense = activityByCategory(entityId, Set.of("COST", "EXPENSE"), from, to);
        values.put("IS_REVENUE_TOTAL", revenue);
        values.put("IS_EXPENSE_TOTAL", expense);
        values.put("IS_PROFIT_TOTAL", revenue.subtract(expense).setScale(2));
        values.put("IS_NET_PROFIT", revenue.subtract(expense).setScale(2));
    }

    private BigDecimal categoryBalance(long entityId, Set<String> categories, LocalDate date) {
        String placeholders = String.join(",", categories.stream().map(x -> "?").toList());
        List<Object> args = new ArrayList<>();
        args.add(entityId);
        args.addAll(categories);
        args.add(Date.valueOf(date));
        String sql = """
                select coalesce(sum(case when a.category in ('ASSET','COST','EXPENSE')
                                         then l.debit_amount-l.credit_amount
                                         else l.credit_amount-l.debit_amount end),0)
                from accounting_account a join accounting_ledger_entry l on l.account_id=a.id
                where a.entity_id=? and a.category in (%s) and l.voucher_date<=?
                """.formatted(placeholders);
        BigDecimal raw = jdbc.queryForObject(sql, BigDecimal.class, args.toArray());
        return money(raw);
    }

    private BigDecimal activityByCategory(long entityId, Set<String> categories, LocalDate from, LocalDate to) {
        String placeholders = String.join(",", categories.stream().map(x -> "?").toList());
        List<Object> args = new ArrayList<>();
        args.add(entityId);
        args.addAll(categories);
        args.add(Date.valueOf(from));
        args.add(Date.valueOf(to));
        String sql = """
                select coalesce(sum(case when a.normal_direction='DEBIT' then l.debit_amount-l.credit_amount
                                         else l.credit_amount-l.debit_amount end),0)
                from accounting_account a join accounting_ledger_entry l on l.account_id=a.id
                join accounting_voucher v on v.id=l.voucher_id
                where a.entity_id=? and a.category in (%s) and l.voucher_date between ? and ?
                  and v.source_type<>'PERIOD_PROFIT_CLOSE'
                """.formatted(placeholders);
        BigDecimal raw = jdbc.queryForObject(sql, BigDecimal.class, args.toArray());
        return money(raw);
    }

    private List<String> unmappedAccounts(long entityId, String reportType, LocalDate from, LocalDate to) {
        String categories = "BALANCE_SHEET".equals(reportType)
                ? "'ASSET','LIABILITY','EQUITY'"
                : "'REVENUE','COST','EXPENSE'";
        String dateFilter = from == null ? " and l.voucher_date<=?" : " and l.voucher_date between ? and ?";
        List<Object> args = new ArrayList<>();
        args.add(entityId);
        if (from == null) {
            args.add(Date.valueOf(to));
        } else {
            args.add(Date.valueOf(from));
            args.add(Date.valueOf(to));
        }
        args.add(reportType);
        args.add(Date.valueOf(to));
        args.add(Date.valueOf(to));
        String sql = """
                select distinct concat(a.account_code,' ',a.account_name)
                from accounting_account a join accounting_ledger_entry l on l.account_id=a.id
                where a.entity_id=? and a.category in (%s)%s
                  and not exists(select 1 from financial_report_account_map m
                    where m.entity_id=a.entity_id and m.report_type=? and m.account_id=a.id
                      and m.effective_from<=? and (m.effective_to is null or m.effective_to>=?))
                order by 1
                """.formatted(categories, dateFilter);
        return jdbc.queryForList(sql, String.class, args.toArray());
    }

    private BigDecimal cashLedgerBalance(long entityId, LocalDate date) {
        return scalarMoney("""
                select coalesce(sum(l.debit_amount-l.credit_amount),0)
                from accounting_ledger_entry l join accounting_account a on a.id=l.account_id
                where l.entity_id=? and (a.account_code='1001' or a.account_code like '1002%')
                  and l.voucher_date<=?
                """, entityId, Date.valueOf(date));
    }

    private List<FinancialCloseDtos.StatementLineView> statementLines(long sessionId) {
        return jdbc.query("""
                select l.*,coalesce(b.batch_no,'') import_batch_no,
                  (select coalesce(sum(m.matched_amount),0) from bank_reconciliation_match m where m.statement_line_id=l.id and m.status='ACTIVE') matched_amount,
                  (select coalesce(sum(d.amount),0) from bank_reconciliation_difference d where d.statement_line_id=l.id and d.status<>'REVERSED') explained_amount
                from bank_statement_line l
                left join bank_statement_import_batch b on b.id=l.import_batch_id
                where l.session_id=? order by l.line_no
                """, (rs, rowNum) -> {
            BigDecimal matched = money(rs.getBigDecimal("matched_amount"));
            BigDecimal explained = money(rs.getBigDecimal("explained_amount"));
            BigDecimal remaining = "VOID".equals(rs.getString("status")) ? ZERO :
                    rs.getBigDecimal("amount").subtract(matched).subtract(explained).max(ZERO).setScale(2);
            return new FinancialCloseDtos.StatementLineView(rs.getLong("id"), rs.getInt("line_no"),
                    rs.getDate("transaction_date").toLocalDate(), rs.getDate("value_date").toLocalDate(),
                    rs.getString("bank_serial_no"), rs.getString("direction"), rs.getBigDecimal("amount"),
                    rs.getBigDecimal("balance"), rs.getString("counterparty_name"),
                    rs.getString("counterparty_account"), rs.getString("purpose"), matched, explained,
                    remaining, rs.getString("status"), rs.getString("void_reason"),
                    rs.getString("voided_by_name"), rs.getTimestamp("voided_at") == null ? null :
                    rs.getTimestamp("voided_at").toLocalDateTime(), rs.getLong("import_batch_id"),
                    rs.getString("import_batch_no"), rs.getString("unmatched_reason_code"),
                    rs.getString("unmatched_reason"), rs.getString("suggested_source_type"),
                    rs.getLong("suggested_source_id"), rs.getString("suggested_source_no"),
                    rs.getInt("suggested_score"), rs.getString("suggested_reason"));
        }, sessionId);
    }

    private List<FinancialCloseDtos.FundCandidate> fundCandidates(SessionLock session) {
        String sql = fundSourceSql() + """
                select x.*,
                  coalesce((select sum(m.matched_amount) from bank_reconciliation_match m
                            where m.source_type=x.source_type and m.source_id=x.source_id and m.status='ACTIVE'),0) matched_amount,
                  coalesce((select sum(p.amount) from bank_reconciliation_pending_system p
                            where p.source_type=x.source_type and p.source_id=x.source_id and p.status='OPEN'),0) pending_amount
                from fund_source x where x.business_bank_account_id=? and x.source_date between ? and ?
                order by x.source_date,x.source_type,x.source_id
                """;
        return jdbc.query(sql, (rs, rowNum) -> {
            BigDecimal amount = money(rs.getBigDecimal("amount"));
            BigDecimal matched = money(rs.getBigDecimal("matched_amount")).add(money(rs.getBigDecimal("pending_amount"))).setScale(2);
            return new FinancialCloseDtos.FundCandidate(rs.getString("source_type"), rs.getLong("source_id"),
                    rs.getString("source_no"), rs.getDate("source_date").toLocalDate(), rs.getString("direction"),
                    amount, matched, amount.subtract(matched).max(ZERO).setScale(2), rs.getLong("partner_id"),
                    rs.getString("partner_name"), rs.getString("payment_method"), rs.getString("memo"));
        }, session.bankAccountId(), Date.valueOf(session.from()), Date.valueOf(session.to())).stream()
                .filter(x -> x.remainingAmount().compareTo(CENT) >= 0).toList();
    }

    private String fundSourceSql() {
        return """
                with fund_source as (
                  select 'PAYMENT' source_type,pay.id source_id,pay.payment_no source_no,pay.received_date source_date,
                         'CREDIT' direction,pay.amount, pay.customer_id partner_id,p.name partner_name,
                         pay.payment_method, pay.memo,pay.business_bank_account_id
                  from payment pay join partner p on p.id=pay.customer_id
                  where pay.payment_method='BANK' and pay.verification_status<>'REVERSED'
                  union all
                  select 'SUPPLIER_PAYMENT',sp.id,sp.payment_no,sp.paid_date,'DEBIT',sp.amount,
                         sp.supplier_id,p.name,sp.payment_method,sp.memo
                         ,sp.business_bank_account_id
                  from supplier_payment sp join partner p on p.id=sp.supplier_id
                  where sp.payment_method='BANK' and sp.verification_status<>'REVERSED'
                  union all
                  select 'CUSTOMER_ADVANCE_REFUND',r.id,r.refund_no,r.refunded_date,'DEBIT',r.amount,
                         r.customer_id,p.name,r.refund_method,r.memo
                         ,r.business_bank_account_id
                  from customer_advance_refund r join partner p on p.id=r.customer_id
                  where r.refund_method='BANK' and r.status='ACTIVE'
                  union all
                  select 'ACCEPTANCE_EVENT',e.id,e.event_no,e.occurred_date,
                         case when e.cash_amount<0 then 'DEBIT' else 'CREDIT' end,abs(e.cash_amount),
                         b.customer_id,p.name,'BANK',concat(e.event_type,' ',e.memo),e.business_bank_account_id
                  from acceptance_bill_event e join acceptance_bill b on b.id=e.bill_id
                  join partner p on p.id=b.customer_id where e.cash_amount<>0
                  union all
                  select 'TREASURY_JOURNAL',j.id,j.journal_no,j.transaction_date,j.direction,j.amount,
                         j.partner_id,j.partner_name_snapshot,j.settlement_method,j.purpose,j.business_bank_account_id
                  from treasury_cash_journal j
                  where j.settlement_method='BANK' and j.status='ACTIVE'
                )
                """;
    }

    private FinancialCloseDtos.FundCandidate fundCandidate(SessionLock session, String type, Long id) {
        return fundCandidates(session).stream().filter(x -> x.sourceType().equals(type) && x.sourceId().equals(id))
                .findFirst().orElseThrow(() -> new BusinessException("系统资金不存在、已全部匹配或已归入其他账户未达项"));
    }

    private void lockFundSource(String sourceType, Long sourceId) {
        String type = required(sourceType, "系统资金类型").toUpperCase();
        String table = switch (type) {
            case "PAYMENT" -> "payment";
            case "SUPPLIER_PAYMENT" -> "supplier_payment";
            case "CUSTOMER_ADVANCE_REFUND" -> "customer_advance_refund";
            case "ACCEPTANCE_EVENT" -> "acceptance_bill_event";
            case "TREASURY_JOURNAL" -> "treasury_cash_journal";
            default -> throw new BusinessException("系统资金类型无效");
        };
        List<Long> ids = jdbc.queryForList("select id from " + table + " where id=? for update",
                Long.class, sourceId);
        if (ids.isEmpty()) throw new BusinessException("系统资金不存在");
    }

    private List<FinancialCloseDtos.MatchView> matches(long sessionId) {
        return jdbc.query("""
                select * from bank_reconciliation_match where session_id=? order by id desc
                """, (rs, rowNum) -> new FinancialCloseDtos.MatchView(rs.getLong("id"),
                rs.getLong("statement_line_id"), rs.getString("source_type"), rs.getLong("source_id"),
                rs.getString("source_no"), rs.getDate("source_date").toLocalDate(),
                rs.getString("source_direction"), rs.getBigDecimal("matched_amount"),
                rs.getString("cash_flow_category"), rs.getString("matching_method"),
                rs.getInt("match_score"), rs.getString("match_rule_version"),
                rs.getLong("import_batch_id"), rs.getString("status"), rs.getString("reason"), rs.getString("matched_by_name"),
                rs.getTimestamp("matched_at").toLocalDateTime()), sessionId);
    }

    private List<FinancialCloseDtos.DifferenceView> differences(long sessionId) {
        return jdbc.query("""
                select d.*,v.voucher_no,v.status voucher_status
                from bank_reconciliation_difference d left join accounting_voucher v on v.id=d.accounting_voucher_id
                where d.session_id=? order by d.id desc
                """, (rs, rowNum) -> new FinancialCloseDtos.DifferenceView(rs.getLong("id"),
                rs.getLong("statement_line_id"), rs.getString("difference_type"), rs.getString("direction"),
                rs.getBigDecimal("amount"), rs.getString("cash_flow_category"), rs.getString("reason"),
                rs.getLong("accounting_voucher_id"), rs.getString("voucher_no"), rs.getString("voucher_status"),
                rs.getString("status"), rs.getString("created_by_name"),
                rs.getTimestamp("created_at").toLocalDateTime()), sessionId);
    }

    private List<FinancialCloseDtos.PendingSystemView> pendingSystemItems(long sessionId) {
        return jdbc.query("""
                select * from bank_reconciliation_pending_system where session_id=? order by id desc
                """, (rs, rowNum) -> new FinancialCloseDtos.PendingSystemView(rs.getLong("id"),
                rs.getString("source_type"), rs.getLong("source_id"), rs.getString("source_no"),
                rs.getDate("source_date").toLocalDate(), rs.getString("direction"), rs.getBigDecimal("amount"),
                rs.getString("cash_flow_category"), rs.getString("reason"), rs.getString("status"),
                rs.getString("created_by_name"), rs.getTimestamp("created_at").toLocalDateTime()), sessionId);
    }

    private List<FinancialCloseDtos.ReconciliationEventView> reconciliationEvents(long sessionId) {
        return jdbc.query("""
                select * from bank_reconciliation_event where session_id=? order by id desc
                """, (rs, rowNum) -> new FinancialCloseDtos.ReconciliationEventView(rs.getLong("id"),
                rs.getString("action"), rs.getString("from_status"), rs.getString("to_status"),
                rs.getString("reason"), rs.getString("operator_name"),
                rs.getTimestamp("created_at").toLocalDateTime()), sessionId);
    }

    private void recalculateSession(long sessionId) {
        Map<String, Object> source = requiredRow("""
                select s.*,b.ledger_account_id,b.opening_balance bank_opening_balance
                from bank_reconciliation_session s join business_bank_account b on b.id=s.bank_account_id
                where s.id=?
                """, sessionId);
        BigDecimal unmatchedCredit = statementRemainingTotal(sessionId, "CREDIT");
        BigDecimal unmatchedDebit = statementRemainingTotal(sessionId, "DEBIT");
        BigDecimal pendingCredit = scalarMoney("""
                select coalesce(sum(amount),0) from bank_reconciliation_pending_system
                where session_id=? and status='OPEN' and direction='CREDIT'
                """, sessionId);
        BigDecimal pendingDebit = scalarMoney("""
                select coalesce(sum(amount),0) from bank_reconciliation_pending_system
                where session_id=? and status='OPEN' and direction='DEBIT'
                """, sessionId);
        BigDecimal ledger = scalarMoney("""
                select coalesce(sum(l.debit_amount-l.credit_amount),0)
                from accounting_ledger_entry l where l.entity_id=? and l.account_id=? and l.voucher_date<=?
                """, number(source.get("entity_id")), number(source.get("ledger_account_id")), source.get("statement_to"));
        BigDecimal adjustedLedger = ledger.add(unmatchedCredit).subtract(unmatchedDebit).setScale(2);
        BigDecimal adjustedStatement = money((BigDecimal) source.get("statement_closing_balance"))
                .add(pendingCredit).subtract(pendingDebit).setScale(2);
        jdbc.update("""
                update bank_reconciliation_session set ledger_closing_balance=?,adjusted_statement_balance=?,
                  adjusted_ledger_balance=?,difference=? where id=?
                """, ledger, adjustedStatement, adjustedLedger,
                adjustedLedger.subtract(adjustedStatement).setScale(2), sessionId);
    }

    private BigDecimal statementRemainingTotal(long sessionId, String direction) {
        return scalarMoney("""
                select coalesce(sum(greatest(l.amount-
                  (select coalesce(sum(m.matched_amount),0) from bank_reconciliation_match m where m.statement_line_id=l.id and m.status='ACTIVE')-
                  (select coalesce(sum(d.amount),0) from bank_reconciliation_difference d where d.statement_line_id=l.id and d.status<>'REVERSED'),0)),0)
                from bank_statement_line l where l.session_id=? and l.direction=? and l.status<>'VOID'
                """, sessionId, direction);
    }

    private BigDecimal statementRemaining(long statementId) {
        return scalarMoney("""
                select greatest(l.amount-
                  (select coalesce(sum(m.matched_amount),0) from bank_reconciliation_match m where m.statement_line_id=l.id and m.status='ACTIVE')-
                  (select coalesce(sum(d.amount),0) from bank_reconciliation_difference d where d.statement_line_id=l.id and d.status<>'REVERSED'),0)
                from bank_statement_line l where l.id=? and l.status<>'VOID'
                """, statementId);
    }

    private void refreshStatementStatus(long statementId) {
        String currentStatus = jdbc.queryForObject("select status from bank_statement_line where id=?", String.class,
                statementId);
        if ("VOID".equals(currentStatus)) return;
        BigDecimal remaining = statementRemaining(statementId);
        BigDecimal matched = scalarMoney("select coalesce(sum(matched_amount),0) from bank_reconciliation_match where statement_line_id=? and status='ACTIVE'", statementId);
        BigDecimal explained = scalarMoney("select coalesce(sum(amount),0) from bank_reconciliation_difference where statement_line_id=? and status<>'REVERSED'", statementId);
        String status = remaining.compareTo(CENT) < 0 ? (explained.compareTo(CENT) >= 0 ? "EXPLAINED" : "MATCHED") :
                (matched.add(explained).compareTo(CENT) >= 0 ? "PARTIAL" : "UNMATCHED");
        jdbc.update("""
                update bank_statement_line set status=?,
                  unmatched_reason_code=case when ? in ('MATCHED','EXPLAINED') then '' else unmatched_reason_code end,
                  unmatched_reason=case when ? in ('MATCHED','EXPLAINED') then '' else unmatched_reason end
                where id=?
                """, status, status, status, statementId);
    }

    private BigDecimal statementTotal(long sessionId, String direction) {
        return scalarMoney("select coalesce(sum(amount),0) from bank_statement_line where session_id=? and direction=? and status<>'VOID'", sessionId, direction);
    }

    private void validateStatementBalances(SessionLock session) {
        List<Map<String, Object>> lines = jdbc.queryForList("""
                select line_no,direction,amount,balance from bank_statement_line
                where session_id=? and status<>'VOID' order by line_no
                """, session.id());
        BigDecimal running = session.openingBalance();
        for (Map<String, Object> line : lines) {
            BigDecimal amount = money(line.get("amount"));
            running = "CREDIT".equals(line.get("direction")) ? running.add(amount) : running.subtract(amount);
            BigDecimal stated = money(line.get("balance"));
            if (running.setScale(2).compareTo(stated) != 0) {
                throw new BusinessException("银行流水第 " + number(line.get("line_no")) +
                        " 行余额不连续，计算余额为 " + running.setScale(2) + "，导入余额为 " + stated);
            }
        }
    }

    private BigDecimal sumRemaining(List<FinancialCloseDtos.StatementLineView> lines, String direction) {
        return lines.stream().filter(x -> direction.equals(x.direction())).map(FinancialCloseDtos.StatementLineView::remainingAmount)
                .reduce(ZERO, BigDecimal::add).setScale(2);
    }

    private BigDecimal profitActivity(Period period) {
        return scalarMoney("""
                select coalesce(sum(abs(x.balance_amount)),0) from (
                  select a.id,sum(case when a.normal_direction='DEBIT' then l.debit_amount-l.credit_amount
                                       else l.credit_amount-l.debit_amount end) balance_amount
                  from accounting_account a
                  join accounting_ledger_entry l on l.account_id=a.id and l.voucher_date between ? and ?
                  join accounting_voucher v on v.id=l.voucher_id and v.source_type<>'PERIOD_PROFIT_CLOSE'
                  where a.entity_id=? and a.category in ('REVENUE','COST','EXPENSE')
                  group by a.id
                ) x
                """, Date.valueOf(period.startDate()), Date.valueOf(period.endDate()), period.entityId());
    }

    private VoucherSummary profitVoucher(long periodId) {
        Map<String, Object> row = row("""
                select id,voucher_no,status from accounting_voucher
                where source_type='PERIOD_PROFIT_CLOSE' and source_id=? and original_voucher_id=0
                  and status in ('DRAFT','REVIEWED','POSTED')
                order by id desc limit 1
                """, periodId);
        return row == null ? new VoucherSummary(0L, "", "") : new VoucherSummary(number(row.get("id")),
                String.valueOf(row.get("voucher_no")), String.valueOf(row.get("status")));
    }

    private String sessionSelect() {
        return """
                select s.*,b.account_name bank_account_name,b.bank_account_no,p.period_name
                from bank_reconciliation_session s
                join business_bank_account b on b.id=s.bank_account_id
                join accounting_period p on p.id=s.period_id
                """;
    }

    private org.springframework.jdbc.core.RowMapper<FinancialCloseDtos.ReconciliationSessionRow> sessionMapper() {
        return (rs, rowNum) -> new FinancialCloseDtos.ReconciliationSessionRow(rs.getLong("id"),
                rs.getLong("bank_account_id"), rs.getString("bank_account_name"), mask(rs.getString("bank_account_no")),
                rs.getLong("period_id"), rs.getString("period_name"), rs.getString("reconciliation_no"),
                rs.getDate("statement_from").toLocalDate(), rs.getDate("statement_to").toLocalDate(),
                rs.getBigDecimal("opening_balance"), rs.getBigDecimal("statement_closing_balance"),
                rs.getBigDecimal("ledger_closing_balance"), rs.getBigDecimal("adjusted_statement_balance"),
                rs.getBigDecimal("adjusted_ledger_balance"), rs.getBigDecimal("difference"), rs.getString("status"),
                rs.getString("confirmed_by_name"), rs.getTimestamp("confirmed_at") == null ? null :
                rs.getTimestamp("confirmed_at").toLocalDateTime());
    }

    private Period period(Long periodId, boolean lock) {
        List<Period> rows = jdbc.query("""
                select id,entity_id,fiscal_year,period_no,period_name,start_date,end_date,status
                from accounting_period where id=?
                """ + (lock ? " for update" : ""), (rs, rowNum) -> new Period(rs.getLong("id"),
                rs.getLong("entity_id"), rs.getInt("fiscal_year"), rs.getInt("period_no"),
                rs.getString("period_name"), rs.getDate("start_date").toLocalDate(),
                rs.getDate("end_date").toLocalDate(), rs.getString("status")), periodId);
        if (rows.isEmpty()) throw new BusinessException("会计期间不存在");
        return rows.get(0);
    }

    private SessionLock session(Long sessionId, boolean lock) {
        List<SessionLock> rows = jdbc.query("""
                select id,entity_id,bank_account_id,period_id,statement_from,statement_to,opening_balance,
                       statement_closing_balance,difference,status
                from bank_reconciliation_session where id=?
                """ + (lock ? " for update" : ""), (rs, rowNum) -> new SessionLock(rs.getLong("id"),
                rs.getLong("entity_id"), rs.getLong("bank_account_id"), rs.getLong("period_id"),
                rs.getDate("statement_from").toLocalDate(), rs.getDate("statement_to").toLocalDate(),
                rs.getBigDecimal("opening_balance"), rs.getBigDecimal("statement_closing_balance"),
                rs.getBigDecimal("difference"), rs.getString("status")), sessionId);
        if (rows.isEmpty()) throw new BusinessException("银行对账单不存在");
        return rows.get(0);
    }

    private StatementLock statement(Long statementId, Long sessionId, boolean lock) {
        List<StatementLock> rows = jdbc.query("""
                select id,session_id,transaction_date,direction,amount,status from bank_statement_line
                where id=? and session_id=?
                """ + (lock ? " for update" : ""), (rs, rowNum) -> new StatementLock(rs.getLong("id"),
                rs.getLong("session_id"), rs.getDate("transaction_date").toLocalDate(),
                rs.getString("direction"), rs.getBigDecimal("amount"), rs.getString("status")), statementId, sessionId);
        if (rows.isEmpty()) throw new BusinessException("银行流水不存在或不属于当前对账单");
        return rows.get(0);
    }

    private BankAccount bankAccount(Long id, boolean enabled) {
        List<BankAccount> rows = jdbc.query("""
                select b.*,a.account_code ledger_account_code from business_bank_account b
                join accounting_account a on a.id=b.ledger_account_id where b.id=?
                """ + (enabled ? " and b.status='ENABLED'" : ""), (rs, rowNum) -> new BankAccount(rs.getLong("id"),
                rs.getLong("entity_id"), rs.getLong("ledger_account_id"), rs.getString("ledger_account_code"),
                rs.getDate("opening_date").toLocalDate(), rs.getString("status")), id);
        if (rows.isEmpty()) throw new BusinessException("企业银行账户不存在或已停用");
        return rows.get(0);
    }

    private void requireEditableSession(SessionLock session) {
        if (!List.of("DRAFT", "REOPENED").contains(session.status())) throw new BusinessException("已确认银行对账单必须先重新打开才能修改");
        if ("CLOSED".equals(period(session.periodId(), false).status())) throw new BusinessException("会计期间已结账，不能修改银行对账");
    }

    private String cashFlowCategory(String value, String direction) {
        String category = required(value, "现金流量分类").toUpperCase();
        CategoryMeta meta = CASH_FLOW_META.get(category);
        if (meta == null) throw new BusinessException("现金流量分类无效");
        if (!meta.direction().equals(direction)) throw new BusinessException("现金流量分类与银行收支方向不一致");
        return category;
    }

    private String currency(String value) {
        String result = StringUtils.hasText(value) ? value.trim().toUpperCase() : "CNY";
        if (!"CNY".equals(result)) throw new BusinessException("当前会计主体仅支持人民币银行账户");
        return result;
    }

    private long entityId() {
        Long id = jdbc.queryForObject("select id from accounting_entity where status='ENABLED' order by id limit 1", Long.class);
        if (id == null) throw new BusinessException("未初始化会计主体");
        return id;
    }

    private Actor actor() {
        SecurityContext.Principal principal = SecurityContext.require();
        return new Actor(principal.userId() == null ? 0L : principal.userId(),
                StringUtils.hasText(principal.employeeName()) ? principal.employeeName() : principal.username());
    }

    private Long insert(String sql, Object... args) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            return ps;
        }, key);
        Number value = key.getKey();
        if (value == null) throw new IllegalStateException("未取得新增记录编号");
        return value.longValue();
    }

    private long scalarLong(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0L : value;
    }

    private BigDecimal scalarMoney(String sql, Object... args) {
        BigDecimal value = jdbc.queryForObject(sql, BigDecimal.class, args);
        return money(value);
    }

    private Map<String, Object> row(String sql, Object... args) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, args);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Map<String, Object> requiredRow(String sql, Object... args) {
        Map<String, Object> row = row(sql, args);
        if (row == null) throw new BusinessException("所需业务记录不存在");
        return row;
    }

    private BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal money(Object value) {
        return value instanceof BigDecimal amount ? money(amount) : ZERO;
    }

    private long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private String required(String value, String label) {
        if (!StringUtils.hasText(value)) throw new BusinessException(label + "不能为空");
        return value.trim();
    }

    private String text(String value) { return value == null ? "" : value.trim(); }

    private String enumValue(String value, Set<String> allowed, String label) {
        String result = required(value, label).toUpperCase();
        if (!allowed.contains(result)) throw new BusinessException(label + "无效");
        return result;
    }

    private List<String> split(String value) {
        return StringUtils.hasText(value) ? List.of(value.split(",")) : List.of();
    }

    private String mask(String value) {
        if (!StringUtils.hasText(value)) return "";
        String trimmed = value.trim();
        if (trimmed.length() <= 4) return "****" + trimmed;
        return "**** **** **** " + trimmed.substring(trimmed.length() - 4);
    }

    private String nextNo(String prefix) {
        int counter = Math.floorMod(NO_COUNTER.incrementAndGet(), 1000);
        return prefix + LocalDateTime.now().format(NO_TIME) + String.format("%03d", counter);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("无法生成审计摘要", ex);
        }
    }

    private void periodEvent(Long periodId, String action, String from, String to, String reason, Actor actor) {
        jdbc.update("""
                insert into accounting_period_event
                (period_id,action,from_status,to_status,reason,operator_id,operator_name)
                values (?,?,?,?,?,?,?)
                """, periodId, action, from, to, text(reason), actor.id(), actor.name());
    }

    private void reconciliationEvent(Long sessionId, String action, String from, String to,
                                     String reason, Actor actor) {
        jdbc.update("""
                insert into bank_reconciliation_event
                (session_id,action,from_status,to_status,reason,operator_id,operator_name)
                values (?,?,?,?,?,?,?)
                """, sessionId, action, from, to, text(reason), actor.id(), actor.name());
    }

    private record CategoryMeta(String name, String activityType, String direction) {}
    private record ReportDefinition(long id, String lineCode, String lineName, int lineOrder,
                                    String calculationType, String formulaCode,
                                    String normalDirection, List<String> accountCodes) {}
    private record Actor(long id, String name) {}
    private record Period(long id, long entityId, int fiscalYear, int periodNo, String name,
                          LocalDate startDate, LocalDate endDate, String status) {}
    private record SessionLock(long id, long entityId, long bankAccountId, long periodId,
                               LocalDate from, LocalDate to, BigDecimal openingBalance,
                               BigDecimal statementClosing, BigDecimal difference, String status) {}
    private record StatementLock(long id, long sessionId, LocalDate transactionDate,
                                 String direction, BigDecimal amount, String status) {}
    private record BankAccount(long id, long entityId, long ledgerAccountId,
                               String ledgerAccountCode, LocalDate openingDate, String status) {}
    private record VoucherSummary(long id, String no, String status) {}
    private record ProfitAccount(String code, String name, String category,
                                 BigDecimal debit, BigDecimal credit) {}
}
