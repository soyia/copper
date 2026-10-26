package com.soyia.copperv2.service;

import com.soyia.copperv2.dto.TaxComplianceDtos;
import com.soyia.copperv2.security.SecurityContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Date;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

@Service
public class TaxWorkpaperService {
    public static final String RULE_CODE = "CN_VAT_WORKPAPER_V1";
    public static final int RULE_VERSION = 1;
    private static final BigDecimal ZERO = new BigDecimal("0.00");

    private final JdbcTemplate jdbc;

    public TaxWorkpaperService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public TaxComplianceDtos.WorkpaperRow latest() {
        List<TaxComplianceDtos.WorkpaperRow> rows = jdbc.query(workpaperSelect()
                + " where w.entity_id=? order by w.id desc limit 1", workpaperMapper(), entityId());
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<TaxComplianceDtos.WorkpaperRow> workpapers(String period) {
        StringBuilder sql = new StringBuilder(workpaperSelect()).append(" where w.entity_id=?");
        List<Object> args = new ArrayList<>(List.of(entityId()));
        if (StringUtils.hasText(period)) {
            YearMonth month = parsePeriod(period);
            sql.append(" and p.fiscal_year=? and p.period_no=?");
            args.add(month.getYear());
            args.add(month.getMonthValue());
        }
        sql.append(" order by p.start_date desc,w.version_no desc");
        return jdbc.query(sql.toString(), workpaperMapper(), args.toArray());
    }

    public TaxComplianceDtos.WorkpaperDetail workpaper(Long id) {
        TaxComplianceDtos.WorkpaperRow row = requireWorkpaper(id);
        List<TaxComplianceDtos.WorkpaperLineView> lines = jdbc.query("""
                select * from tax_workpaper_line where workpaper_id=? order by line_no
                """, (rs, rowNum) -> new TaxComplianceDtos.WorkpaperLineView(
                rs.getLong("id"), rs.getInt("line_no"), rs.getString("section_code"),
                rs.getString("item_code"), rs.getString("item_name"), rs.getBigDecimal("tax_rate"),
                rs.getBigDecimal("amount_excluding_tax"), rs.getBigDecimal("tax_amount"),
                rs.getBigDecimal("total_amount"), rs.getInt("source_count"),
                rs.getString("severity"), rs.getString("formula"), rs.getString("source_query_snapshot")), id);
        List<TaxComplianceDtos.WorkpaperEventView> events = jdbc.query("""
                select * from tax_workpaper_event where workpaper_id=? order by id desc
                """, (rs, rowNum) -> new TaxComplianceDtos.WorkpaperEventView(
                rs.getLong("id"), rs.getString("action"), rs.getString("from_status"),
                rs.getString("to_status"), rs.getString("reason"), rs.getString("operator_name"),
                rs.getTimestamp("created_at").toLocalDateTime()), id);
        return new TaxComplianceDtos.WorkpaperDetail(row, lines, events);
    }

    @Transactional
    public TaxComplianceDtos.WorkpaperDetail generate(String rawPeriod) {
        YearMonth month = parsePeriod(rawPeriod);
        Period period = requirePeriod(month);
        Integer reviewed = jdbc.queryForObject("""
                select count(*) from tax_workpaper where entity_id=? and period_id=? and status='REVIEWED'
                """, Integer.class, entityId(), period.id());
        if ("CLOSED".equals(period.status()) && reviewed != null && reviewed > 0) {
            throw new BusinessException("该期间已结账且已有复核底稿；如需更正，请先按权限反结账并保留原因");
        }
        jdbc.queryForObject("select id from accounting_period where id=? for update", Long.class, period.id());
        LocalDateTime cutoff = LocalDateTime.now().withNano(0);
        int version = jdbc.queryForObject("""
                select coalesce(max(version_no),0)+1 from tax_workpaper
                where entity_id=? and period_id=? and tax_type='VAT'
                """, Integer.class, entityId(), period.id());
        List<LineDraft> lines = buildLines(period);
        Totals totals = totals(lines);
        int blockers = (int) lines.stream().filter(line -> "BLOCKER".equals(line.severity())).count();
        int warnings = (int) lines.stream().filter(line -> "WARNING".equals(line.severity())).count();
        // The final digest is calculated from values read back from MySQL. Decimal scale and
        // TIMESTAMP conversion can differ between MySQL/JDBC versions, so hashing the pre-insert
        // Java values would make a valid workpaper fail its later integrity check.
        String provisionalHash = "0".repeat(64);
        SecurityContext.Principal actor = SecurityContext.require();
        String no = "ZZSDG-" + month.toString().replace("-", "") + "-V" + String.format("%03d", version);
        List<PreviousWorkpaper> superseded = jdbc.query("""
                select id,status from tax_workpaper
                where entity_id=? and period_id=? and tax_type='VAT' and status in ('DRAFT','REVIEWED')
                for update
                """, (rs, rowNum) -> new PreviousWorkpaper(rs.getLong("id"), rs.getString("status")),
                entityId(), period.id());
        jdbc.update("""
                update tax_workpaper set status='SUPERSEDED',superseded_at=now()
                where entity_id=? and period_id=? and tax_type='VAT' and status in ('DRAFT','REVIEWED')
                """, entityId(), period.id());
        for (PreviousWorkpaper previous : superseded) {
            addEvent(previous.id(), "SUPERSEDE", previous.status(), "SUPERSEDED",
                    "由新版本 " + no + " 替代；原底稿及内容摘要继续保留供审计追溯");
        }
        jdbc.update("""
                insert into tax_workpaper
                (entity_id,period_id,workpaper_no,tax_type,rule_code,rule_version,version_no,data_cutoff_at,
                 status,output_net_amount,output_tax_amount,input_net_amount,input_tax_amount,
                 deductible_input_tax,transferred_out_input_tax,estimated_vat_payable,
                 ledger_output_tax,ledger_input_tax,ledger_transferred_out_tax,
                 output_ledger_difference,input_ledger_difference,blocker_count,warning_count,
                 content_sha256,generated_by,generated_by_name)
                values (?,?,?,?,?,?,?,?, ?,?,?,?,?,?,?,?, ?,?,?,?,?,?,?,?, ?,?)
                """, entityId(), period.id(), no, "VAT", RULE_CODE, RULE_VERSION, version, cutoff,
                "DRAFT", totals.outputNet(), totals.outputTax(), totals.inputNet(), totals.inputTax(),
                totals.deductibleInput(), totals.transferredOut(), totals.estimatedPayable(),
                totals.ledgerOutput(), totals.ledgerInput(), totals.ledgerTransferredOut(),
                totals.outputDifference(), totals.inputDifference(), blockers, warnings, provisionalHash,
                actor.employeeId(), actor.employeeName());
        Long id = jdbc.queryForObject("select last_insert_id()", Long.class);
        int lineNo = 0;
        for (LineDraft line : lines) {
            jdbc.update("""
                    insert into tax_workpaper_line
                    (workpaper_id,line_no,section_code,item_code,item_name,tax_rate,
                     amount_excluding_tax,tax_amount,total_amount,source_count,severity,formula,source_query_snapshot)
                    values (?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, id, ++lineNo, line.section(), line.code(), line.name(), line.taxRate(),
                    line.net(), line.tax(), line.total(), line.sourceCount(), line.severity(),
                    line.formula(), line.sourceSnapshot());
        }
        String contentHash = persistedHash(id, period, version);
        int hashUpdated = jdbc.update("""
                update tax_workpaper set content_sha256=? where id=? and content_sha256=?
                """, contentHash, id, provisionalHash);
        if (hashUpdated != 1) throw new IllegalStateException("税务底稿摘要固化失败");
        addEvent(id, "GENERATE", "", "DRAFT",
                "按 " + RULE_CODE + "/v" + RULE_VERSION + " 生成；截止=" + cutoff
                        + "；本底稿仅供企业会计或税务专业人员复核，不代表已申报");
        return workpaper(id);
    }

    @Transactional
    public Long review(Long id, String reason) {
        TaxComplianceDtos.WorkpaperDetail detail = workpaper(id);
        TaxComplianceDtos.WorkpaperRow row = detail.workpaper();
        if (!"DRAFT".equals(row.status())) throw new BusinessException("只有当前草稿底稿可以复核");
        Integer newer = jdbc.queryForObject("""
                select count(*) from tax_workpaper where entity_id=? and period_id=? and tax_type='VAT'
                  and version_no>? and status<>'SUPERSEDED'
                """, Integer.class, entityId(), row.periodId(), row.versionNo());
        if (newer != null && newer > 0) throw new BusinessException("该底稿不是最新版本，请复核最新底稿");
        String normalizedReason = required(reason, "复核说明");
        if (row.blockerCount() > 0) throw new BusinessException("底稿仍有阻断项，必须先处理后重新生成");
        if (row.outputLedgerDifference().compareTo(ZERO) != 0 || row.inputLedgerDifference().compareTo(ZERO) != 0) {
            throw new BusinessException("票账税差异不为零，不能标记为已复核");
        }
        Period period = requirePeriod(row.periodId());
        String currentHash = hash(period, row.versionNo(), row.dataCutoffAt(), detail.lines().stream()
                .map(this::draft).toList());
        if (!currentHash.equals(row.contentSha256())) throw new BusinessException("底稿内容摘要不一致，禁止复核");
        SecurityContext.Principal actor = SecurityContext.require();
        int changed = jdbc.update("""
                update tax_workpaper set status='REVIEWED',review_note=?,reviewed_by=?,reviewed_by_name=?,reviewed_at=now()
                where id=? and status='DRAFT'
                """, normalizedReason, actor.employeeId(), actor.employeeName(), id);
        if (changed != 1) throw new BusinessException("底稿状态已变化，请刷新后重试");
        addEvent(id, "REVIEW", "DRAFT", "REVIEWED",
                normalizedReason + "；企业仍须在正式申报前由会计或税务专业人员复核税法适用和申报表口径");
        return id;
    }

    public TaxComplianceDtos.FileContent exportCsv(Long id) {
        TaxComplianceDtos.WorkpaperDetail detail = workpaper(id);
        TaxComplianceDtos.WorkpaperRow item = detail.workpaper();
        StringBuilder csv = new StringBuilder("\uFEFF");
        csv.append("Copper 增值税申报工作底稿（复核用，不代表已申报）\n");
        csv.append("底稿编号,").append(cell(item.workpaperNo())).append("\n");
        csv.append("期间,").append(cell(item.periodName())).append("\n");
        csv.append("规则版本,").append(cell(item.ruleCode() + "/v" + item.ruleVersion())).append("\n");
        csv.append("数据截止,").append(cell(String.valueOf(item.dataCutoffAt()))).append("\n");
        csv.append("内容SHA-256,").append(cell(item.contentSha256())).append("\n");
        csv.append("状态,").append(cell(item.status())).append("\n");
        csv.append("重要提示,").append(cell("本文件是系统工作底稿，不是税务机关申报回执；正式申报前须由企业会计或税务专业人员复核")).append("\n\n");
        csv.append("序号,分区,项目编码,项目名称,税率,未税金额,税额,价税合计,来源数,级别,公式,来源口径\n");
        for (TaxComplianceDtos.WorkpaperLineView line : detail.lines()) {
            csv.append(line.lineNo()).append(',').append(cell(line.sectionCode())).append(',')
                    .append(cell(line.itemCode())).append(',').append(cell(line.itemName())).append(',')
                    .append(cell(line.taxRate() == null ? "" : line.taxRate().stripTrailingZeros().toPlainString())).append(',')
                    .append(cell(money(line.amountExcludingTax()))).append(',').append(cell(money(line.taxAmount()))).append(',')
                    .append(cell(money(line.totalAmount()))).append(',').append(line.sourceCount()).append(',')
                    .append(cell(line.severity())).append(',').append(cell(line.formula())).append(',')
                    .append(cell(line.sourceQuerySnapshot())).append('\n');
        }
        return new TaxComplianceDtos.FileContent(item.workpaperNo() + ".csv", "text/csv;charset=UTF-8",
                csv.toString().getBytes(StandardCharsets.UTF_8));
    }

    private List<LineDraft> buildLines(Period period) {
        List<LineDraft> lines = new ArrayList<>();
        lines.addAll(invoiceRateLines(period, "OUTPUT"));
        lines.addAll(invoiceRateLines(period, "INPUT"));

        Aggregate deductible = aggregate("""
                select count(*) source_count,coalesce(sum(x.net),0) net,
                  coalesce(sum(x.tax),0) tax,coalesce(sum(x.total),0) total
                from (
                  select i.amount_excluding_tax net,i.tax_amount tax,i.total_amount total
                  from tax_invoice_use_event u join tax_invoice i on i.id=u.invoice_id
                  where i.entity_id=? and u.action='USAGE_CONFIRM'
                    and u.to_usage_status='DEDUCTION' and u.tax_period=?
                  union all
                  select -i.amount_excluding_tax,-i.tax_amount,-i.total_amount
                  from tax_invoice i join tax_invoice blue on blue.id=i.blue_invoice_id
                  where i.entity_id=? and i.direction='INPUT' and i.invoice_color='RED'
                    and i.status='CONFIRMED' and i.invoice_date between ? and ?
                    and blue.usage_status='DEDUCTION'
                ) x
                """, entityId(), period.yearMonth(), entityId(), period.start(), period.end());
        lines.add(new LineDraft("INPUT", "INPUT_DEDUCTIBLE", "本期确认抵扣进项税额", null,
                deductible.net(), deductible.tax(), deductible.total(), deductible.count(), "INFO",
                "Σ usage_status=DEDUCTION 且 usage_period=本期的进项发票税额",
                "tax_invoice；状态=CONFIRMED；用途期间=" + period.yearMonth()));

        Aggregate transfer = aggregate("""
                select count(*) source_count,0 net,coalesce(sum(u.tax_amount),0) tax,coalesce(sum(u.tax_amount),0) total
                from tax_invoice_use_event u join tax_invoice i on i.id=u.invoice_id
                where i.entity_id=? and u.action='TRANSFER_OUT' and u.tax_period=?
                """, entityId(), period.yearMonth());
        lines.add(new LineDraft("TRANSFER_OUT", "INPUT_TRANSFERRED_OUT", "本期进项税额转出", null,
                ZERO, transfer.tax(), transfer.total(), transfer.count(), "INFO",
                "Σ action=TRANSFER_OUT 且 tax_period=本期的税额",
                "tax_invoice_use_event；税款所属期=" + period.yearMonth()));

        BigDecimal ledgerOutput = ledgerNet(period.id(), "22210102", "CREDIT");
        BigDecimal ledgerInput = ledgerNet(period.id(), "22210101", "DEBIT");
        BigDecimal ledgerTransfer = jdbc.queryForObject("""
                select coalesce(sum(l.credit_amount),0) from accounting_voucher_line l
                join accounting_voucher v on v.id=l.voucher_id
                where v.period_id=? and v.status='POSTED' and v.source_type='TAX_INVOICE_TRANSFER'
                  and l.account_code_snapshot='22210101'
                """, BigDecimal.class, period.id());
        lines.add(new LineDraft("LEDGER", "LEDGER_OUTPUT_TAX", "总账销项税额净额", null,
                ZERO, scale(ledgerOutput), scale(ledgerOutput), 1, "INFO",
                "贷方发生额-借方发生额", "已记账凭证；科目=22210102；期间=" + period.yearMonth()));
        lines.add(new LineDraft("LEDGER", "LEDGER_INPUT_TAX", "总账进项税额净额", null,
                ZERO, scale(ledgerInput), scale(ledgerInput), 1, "INFO",
                "借方发生额-贷方发生额", "已记账凭证；科目=22210101；期间=" + period.yearMonth()));
        lines.add(new LineDraft("LEDGER", "LEDGER_TRANSFER_OUT", "总账进项转出贷方额", null,
                ZERO, scale(ledgerTransfer), scale(ledgerTransfer), 1, "INFO",
                "来源为进项转出的已记账凭证贷方额", "source_type=TAX_INVOICE_TRANSFER；科目=22210101"));

        BigDecimal outputTax = sectionTax(lines, "OUTPUT");
        BigDecimal expectedInputLedger = scale(deductible.tax().subtract(transfer.tax()));
        BigDecimal outputDifference = scale(outputTax.subtract(ledgerOutput));
        BigDecimal inputDifference = scale(expectedInputLedger.subtract(ledgerInput));
        lines.add(new LineDraft("DIFFERENCE", "OUTPUT_LEDGER_DIFFERENCE", "销项票账差异", null,
                ZERO, outputDifference, outputDifference, 1,
                outputDifference.compareTo(ZERO) == 0 ? "INFO" : "BLOCKER",
                "销项发票税额-总账销项税额净额", "差异必须为0方可复核"));
        lines.add(new LineDraft("DIFFERENCE", "INPUT_LEDGER_DIFFERENCE", "可抵扣进项票账差异", null,
                ZERO, inputDifference, inputDifference, 1,
                inputDifference.compareTo(ZERO) == 0 ? "INFO" : "BLOCKER",
                "本期确认抵扣税额-本期转出税额-总账进项税额净额", "差异必须为0方可复核"));

        addException(lines, "UNVERIFIED_INVOICE", "本期存在未验真的确认发票", "BLOCKER",
                count("""
                        select count(*) from tax_invoice where entity_id=? and status='CONFIRMED'
                          and invoice_date between ? and ? and verification_status<>'VERIFIED'
                        """, entityId(), period.start(), period.end()),
                "确认发票必须保留税务平台验真证据");
        addException(lines, "DEDUCTION_WITHOUT_USAGE_PROOF", "本期抵扣进项票缺少用途确认参考", "BLOCKER",
                count("""
                        select count(*) from tax_invoice_use_event u join tax_invoice i on i.id=u.invoice_id
                        where i.entity_id=? and u.action='USAGE_CONFIRM' and u.to_usage_status='DEDUCTION'
                          and u.tax_period=? and trim(u.evidence_reference)=''
                        """, entityId(), period.yearMonth()),
                "抵扣必须对应税务数字账户用途确认或等效证据");
        addException(lines, "UNPOSTED_INVOICE_VOUCHER", "本期确认发票的会计凭证尚未记账", "BLOCKER",
                count("""
                        select count(*) from tax_invoice i left join accounting_voucher v on v.id=i.accounting_voucher_id
                        where i.entity_id=? and i.status='CONFIRMED' and i.invoice_date between ? and ?
                          and (i.accounting_voucher_id=0 or v.status<>'POSTED')
                        """, entityId(), period.start(), period.end()),
                "发票确认和会计记账分层；底稿复核前必须完成凭证审核记账");
        addException(lines, "ELECTRONIC_INVOICE_EXCEPTION", "本期结构化数电票未完成解析、验真或来源关联", "BLOCKER",
                count("""
                        select count(*) from electronic_credential c
                        where c.entity_id=? and c.credential_type='DIGITAL_INVOICE' and c.status='ACTIVE'
                          and c.issue_date between ? and ? and (c.parse_status<>'PARSED' or c.verification_status<>'VERIFIED'
                            or not exists (select 1 from electronic_credential_relation r
                              join tax_invoice i on i.id=r.document_id and i.status='CONFIRMED'
                              where r.credential_id=c.id and r.status='ACTIVE' and r.document_type='TAX_INVOICE'))
                        """, entityId(), period.start(), period.end()),
                "结构化文件、验真证据和系统发票三者必须关联");
        addException(lines, "INPUT_USAGE_PENDING", "本期开票的进项发票用途尚未确认", "WARNING",
                count("""
                        select count(*) from tax_invoice where entity_id=? and status='CONFIRMED'
                          and direction='INPUT' and invoice_date between ? and ? and usage_status='UNCONFIRMED'
                        """, entityId(), period.start(), period.end()),
                "可能留待以后期间抵扣；需会计确认，不自动计入本期可抵扣额");
        return lines;
    }

    private List<LineDraft> invoiceRateLines(Period period, String direction) {
        return jdbc.query("""
                select l.tax_rate,count(distinct i.id) source_count,
                  coalesce(sum(case when i.invoice_color='RED' then -l.amount_excluding_tax else l.amount_excluding_tax end),0) net,
                  coalesce(sum(case when i.invoice_color='RED' then -l.tax_amount else l.tax_amount end),0) tax,
                  coalesce(sum(case when i.invoice_color='RED' then -l.total_amount else l.total_amount end),0) total
                from tax_invoice i join tax_invoice_line l on l.invoice_id=i.id
                where i.entity_id=? and i.direction=? and i.status='CONFIRMED'
                  and i.invoice_date between ? and ?
                group by l.tax_rate order by l.tax_rate desc
                """, (rs, rowNum) -> {
            BigDecimal rate = rs.getBigDecimal("tax_rate");
            String section = "OUTPUT".equals(direction) ? "OUTPUT" : "INPUT";
            return new LineDraft(section, section + "_RATE_" + rate.stripTrailingZeros().toPlainString(),
                    ("OUTPUT".equals(direction) ? "销项" : "进项") + rate.stripTrailingZeros().toPlainString() + "%发票",
                    rate, rs.getBigDecimal("net"), rs.getBigDecimal("tax"), rs.getBigDecimal("total"),
                    rs.getInt("source_count"), "INFO", "按发票明细冻结税率汇总",
                    "tax_invoice + tax_invoice_line；状态=CONFIRMED；发票日期=" + period.yearMonth());
        }, entityId(), direction, period.start(), period.end());
    }

    private Totals totals(List<LineDraft> lines) {
        BigDecimal outputNet = sectionNet(lines, "OUTPUT");
        BigDecimal outputTax = sectionTax(lines, "OUTPUT");
        BigDecimal inputNet = sectionNet(lines, "INPUT").subtract(item(lines, "INPUT_DEDUCTIBLE").net());
        BigDecimal inputTax = sectionTax(lines, "INPUT").subtract(item(lines, "INPUT_DEDUCTIBLE").tax());
        BigDecimal deductible = item(lines, "INPUT_DEDUCTIBLE").tax();
        BigDecimal transferred = item(lines, "INPUT_TRANSFERRED_OUT").tax();
        BigDecimal ledgerOutput = item(lines, "LEDGER_OUTPUT_TAX").tax();
        BigDecimal ledgerInput = item(lines, "LEDGER_INPUT_TAX").tax();
        BigDecimal ledgerTransfer = item(lines, "LEDGER_TRANSFER_OUT").tax();
        BigDecimal estimated = scale(outputTax.subtract(deductible).add(transferred));
        return new Totals(scale(outputNet), scale(outputTax), scale(inputNet), scale(inputTax), scale(deductible),
                scale(transferred), estimated, scale(ledgerOutput), scale(ledgerInput), scale(ledgerTransfer),
                item(lines, "OUTPUT_LEDGER_DIFFERENCE").tax(), item(lines, "INPUT_LEDGER_DIFFERENCE").tax());
    }

    private void addException(List<LineDraft> lines, String code, String name, String severity,
                              int count, String explanation) {
        if (count <= 0) return;
        lines.add(new LineDraft("EXCEPTION", code, name, null, ZERO, ZERO, ZERO, count, severity,
                explanation, "数量=" + count + "；生成底稿后不得静默忽略"));
    }

    private Aggregate aggregate(String sql, Object... args) {
        return jdbc.queryForObject(sql, (rs, rowNum) -> new Aggregate(rs.getInt("source_count"),
                scale(rs.getBigDecimal("net")), scale(rs.getBigDecimal("tax")), scale(rs.getBigDecimal("total"))), args);
    }

    private BigDecimal ledgerNet(Long periodId, String accountCode, String normalDirection) {
        Map<String, Object> row = jdbc.queryForMap("""
                select coalesce(sum(l.debit_amount),0) debit,coalesce(sum(l.credit_amount),0) credit
                from accounting_voucher_line l join accounting_voucher v on v.id=l.voucher_id
                where v.period_id=? and v.status='POSTED' and l.account_code_snapshot=?
                """, periodId, accountCode);
        BigDecimal debit = (BigDecimal) row.get("debit");
        BigDecimal credit = (BigDecimal) row.get("credit");
        return scale("DEBIT".equals(normalDirection) ? debit.subtract(credit) : credit.subtract(debit));
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private Period requirePeriod(YearMonth month) {
        List<Period> rows = jdbc.query("""
                select id,period_name,start_date,end_date,status from accounting_period
                where entity_id=? and fiscal_year=? and period_no=?
                """, (rs, rowNum) -> new Period(rs.getLong("id"), rs.getString("period_name"),
                rs.getDate("start_date").toLocalDate(), rs.getDate("end_date").toLocalDate(),
                rs.getString("status")), entityId(), month.getYear(), month.getMonthValue());
        if (rows.isEmpty()) throw new BusinessException("会计期间不存在，请先在科目与期间中建立该月份");
        return rows.get(0);
    }

    private Period requirePeriod(Long id) {
        List<Period> rows = jdbc.query("""
                select id,period_name,start_date,end_date,status from accounting_period
                where id=? and entity_id=?
                """, (rs, rowNum) -> new Period(rs.getLong("id"), rs.getString("period_name"),
                rs.getDate("start_date").toLocalDate(), rs.getDate("end_date").toLocalDate(),
                rs.getString("status")), id, entityId());
        if (rows.isEmpty()) throw new BusinessException("会计期间不存在");
        return rows.get(0);
    }

    private TaxComplianceDtos.WorkpaperRow requireWorkpaper(Long id) {
        List<TaxComplianceDtos.WorkpaperRow> rows = jdbc.query(workpaperSelect()
                + " where w.id=? and w.entity_id=?", workpaperMapper(), id, entityId());
        if (rows.isEmpty()) throw new BusinessException("税务工作底稿不存在");
        return rows.get(0);
    }

    private String workpaperSelect() {
        return """
                select w.*,p.period_name from tax_workpaper w
                join accounting_period p on p.id=w.period_id
                """;
    }

    private org.springframework.jdbc.core.RowMapper<TaxComplianceDtos.WorkpaperRow> workpaperMapper() {
        return (rs, rowNum) -> new TaxComplianceDtos.WorkpaperRow(
                rs.getLong("id"), rs.getLong("period_id"), rs.getString("period_name"),
                rs.getString("workpaper_no"), rs.getString("tax_type"), rs.getString("rule_code"),
                rs.getInt("rule_version"), rs.getInt("version_no"),
                rs.getTimestamp("data_cutoff_at").toLocalDateTime(), rs.getString("status"),
                rs.getBigDecimal("output_net_amount"), rs.getBigDecimal("output_tax_amount"),
                rs.getBigDecimal("input_net_amount"), rs.getBigDecimal("input_tax_amount"),
                rs.getBigDecimal("deductible_input_tax"), rs.getBigDecimal("transferred_out_input_tax"),
                rs.getBigDecimal("estimated_vat_payable"), rs.getBigDecimal("ledger_output_tax"),
                rs.getBigDecimal("ledger_input_tax"), rs.getBigDecimal("ledger_transferred_out_tax"),
                rs.getBigDecimal("output_ledger_difference"), rs.getBigDecimal("input_ledger_difference"),
                rs.getInt("blocker_count"), rs.getInt("warning_count"), rs.getString("content_sha256"),
                rs.getString("review_note"), rs.getString("generated_by_name"),
                rs.getTimestamp("generated_at").toLocalDateTime(), rs.getString("reviewed_by_name"),
                rs.getTimestamp("reviewed_at") == null ? null : rs.getTimestamp("reviewed_at").toLocalDateTime());
    }

    private void addEvent(Long id, String action, String from, String to, String reason) {
        SecurityContext.Principal actor = SecurityContext.require();
        jdbc.update("""
                insert into tax_workpaper_event
                (workpaper_id,action,from_status,to_status,reason,operator_id,operator_name)
                values (?,?,?,?,?,?,?)
                """, id, action, from, to, reason, actor.employeeId(), actor.employeeName());
    }

    private String hash(Period period, int version, LocalDateTime cutoff, List<LineDraft> lines) {
        StringBuilder value = new StringBuilder(RULE_CODE).append('|').append(RULE_VERSION).append('|')
                .append(period.id()).append('|').append(version).append('|').append(cutoff);
        for (LineDraft line : lines) value.append('\n').append(line.section()).append('|').append(line.code())
                .append('|').append(line.name()).append('|').append(line.taxRate()).append('|').append(line.net())
                .append('|').append(line.tax()).append('|').append(line.total()).append('|').append(line.sourceCount())
                .append('|').append(line.severity()).append('|').append(line.formula()).append('|').append(line.sourceSnapshot());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("无法计算税务底稿摘要", ex);
        }
    }

    private String persistedHash(Long id, Period period, int version) {
        TaxComplianceDtos.WorkpaperDetail persisted = workpaper(id);
        return hash(period, version, persisted.workpaper().dataCutoffAt(), persisted.lines().stream()
                .map(this::draft).toList());
    }

    private LineDraft draft(TaxComplianceDtos.WorkpaperLineView line) {
        return new LineDraft(line.sectionCode(), line.itemCode(), line.itemName(), line.taxRate(),
                line.amountExcludingTax(), line.taxAmount(), line.totalAmount(), line.sourceCount(),
                line.severity(), line.formula(), line.sourceQuerySnapshot());
    }

    private LineDraft item(List<LineDraft> lines, String code) {
        return lines.stream().filter(line -> code.equals(line.code())).findFirst()
                .orElseThrow(() -> new IllegalStateException("底稿项目缺失：" + code));
    }

    private BigDecimal sectionNet(List<LineDraft> lines, String section) {
        return scale(lines.stream().filter(line -> section.equals(line.section()))
                .map(LineDraft::net).reduce(ZERO, BigDecimal::add));
    }

    private BigDecimal sectionTax(List<LineDraft> lines, String section) {
        return scale(lines.stream().filter(line -> section.equals(line.section()))
                .map(LineDraft::tax).reduce(ZERO, BigDecimal::add));
    }

    private YearMonth parsePeriod(String value) {
        try { return YearMonth.parse(required(value, "税款所属期")); }
        catch (DateTimeParseException ex) { throw new BusinessException("税款所属期必须为 YYYY-MM"); }
    }

    private Long entityId() {
        List<Long> rows = jdbc.queryForList("""
                select id from accounting_entity where entity_code='DEFAULT' and status='ENABLED' limit 1
                """, Long.class);
        if (rows.isEmpty()) throw new BusinessException("默认会计主体不存在或已停用");
        return rows.get(0);
    }

    private BigDecimal scale(BigDecimal value) { return (value == null ? ZERO : value).setScale(2, RoundingMode.HALF_UP); }
    private String money(BigDecimal value) { return scale(value).toPlainString(); }
    private String required(String value, String label) {
        if (!StringUtils.hasText(value)) throw new BusinessException(label + "不能为空");
        return value.trim();
    }
    private String cell(String value) { return "\"" + (value == null ? "" : value.replace("\"", "\"\"")) + "\""; }

    private record Aggregate(int count, BigDecimal net, BigDecimal tax, BigDecimal total) {}
    private record LineDraft(String section, String code, String name, BigDecimal taxRate,
                             BigDecimal net, BigDecimal tax, BigDecimal total, int sourceCount,
                             String severity, String formula, String sourceSnapshot) {}
    private record Totals(BigDecimal outputNet, BigDecimal outputTax,
                          BigDecimal inputNet, BigDecimal inputTax,
                          BigDecimal deductibleInput, BigDecimal transferredOut,
                          BigDecimal estimatedPayable, BigDecimal ledgerOutput,
                          BigDecimal ledgerInput, BigDecimal ledgerTransferredOut,
                          BigDecimal outputDifference, BigDecimal inputDifference) {}
    private record Period(Long id, String name, LocalDate start, LocalDate end, String status) {
        String yearMonth() { return YearMonth.from(start).toString(); }
    }
    private record PreviousWorkpaper(Long id, String status) {}
}
