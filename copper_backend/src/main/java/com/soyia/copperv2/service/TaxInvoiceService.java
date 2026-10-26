package com.soyia.copperv2.service;

import com.soyia.copperv2.dto.TaxInvoiceDtos;
import com.soyia.copperv2.security.BusinessDataScopeService;
import com.soyia.copperv2.security.SecurityContext;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class TaxInvoiceService {
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    private static final DateTimeFormatter NO_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");
    private static final AtomicInteger NO_COUNTER = new AtomicInteger();
    private static final List<String> DIRECTIONS = List.of("INPUT", "OUTPUT");
    private static final List<String> COLORS = List.of("BLUE", "RED");
    private static final List<String> INVOICE_TYPES = List.of(
            "DIGITAL_SPECIAL", "DIGITAL_NORMAL", "VAT_SPECIAL", "VAT_NORMAL", "OTHER");

    private final JdbcTemplate jdbc;
    private final AccountingService accounting;
    private final BusinessDataScopeService dataScope;
    private final EvidenceAttachmentService evidenceAttachments;

    public TaxInvoiceService(JdbcTemplate jdbc, AccountingService accounting,
                             BusinessDataScopeService dataScope,
                             EvidenceAttachmentService evidenceAttachments) {
        this.jdbc = jdbc;
        this.accounting = accounting;
        this.dataScope = dataScope;
        this.evidenceAttachments = evidenceAttachments;
    }

    public TaxInvoiceDtos.DashboardView dashboard() {
        long entityId = entityId();
        return new TaxInvoiceDtos.DashboardView(
                scalarLong("select count(*) from tax_invoice where entity_id=? and direction='INPUT' and status='CONFIRMED'", entityId),
                signedSum(entityId, "INPUT", "total_amount"), signedSum(entityId, "INPUT", "tax_amount"),
                scalarLong("select count(*) from tax_invoice where entity_id=? and direction='OUTPUT' and status='CONFIRMED'", entityId),
                signedSum(entityId, "OUTPUT", "total_amount"), signedSum(entityId, "OUTPUT", "tax_amount"),
                invoices(null, "CONFIRMED", null, null, null, true).size(),
                scalarLong("select count(*) from tax_invoice where entity_id=? and status='CONFIRMED' and verification_status<>'VERIFIED'", entityId),
                scalarLong("""
                        select count(*) from tax_invoice where entity_id=? and direction='INPUT'
                          and invoice_color='BLUE' and status='CONFIRMED' and usage_status='UNCONFIRMED'
                        """, entityId),
                scalarLong("""
                        select count(*) from tax_invoice where entity_id=? and invoice_color='RED'
                          and status='DRAFT' and red_confirmation_status='PENDING'
                        """, entityId));
    }

    public List<TaxInvoiceDtos.TaxRateView> taxRates(String direction, LocalDate date) {
        String normalizedDirection = StringUtils.hasText(direction)
                ? enumValue(direction, DIRECTIONS, "发票方向") : "";
        LocalDate effectiveDate = date == null ? LocalDate.now() : date;
        String scope = normalizedDirection.isBlank() ? "" : " and invoice_scope in ('BOTH',?)";
        Object[] args = normalizedDirection.isBlank()
                ? new Object[]{Date.valueOf(effectiveDate), Date.valueOf(effectiveDate)}
                : new Object[]{Date.valueOf(effectiveDate), Date.valueOf(effectiveDate), normalizedDirection};
        return jdbc.query("""
                select * from tax_rate_rule where status='ENABLED' and effective_from<=?
                  and (effective_to is null or effective_to>=?)
                """ + scope + " order by tax_rate desc,rule_code", (rs, rowNum) ->
                new TaxInvoiceDtos.TaxRateView(rs.getLong("id"), rs.getString("rule_code"),
                        rs.getString("rule_name"), rs.getBigDecimal("tax_rate"),
                        rs.getString("calculation_method"), rs.getString("invoice_scope"),
                        rs.getInt("version_no"), rs.getDate("effective_from").toLocalDate(),
                        date(rs.getDate("effective_to")), rs.getString("legal_basis"),
                        rs.getString("status")), args);
    }

    public List<TaxInvoiceDtos.InvoiceRow> invoices(String direction, String status, Long partnerId,
                                                     LocalDate from, LocalDate to,
                                                     boolean exceptionOnly) {
        long entityId = entityId();
        StringBuilder sql = new StringBuilder(invoiceRowSelect()).append(" where i.entity_id=?");
        List<Object> args = new ArrayList<>();
        args.add(entityId);
        if (StringUtils.hasText(direction)) {
            sql.append(" and i.direction=?");
            args.add(enumValue(direction, DIRECTIONS, "发票方向"));
        }
        if (StringUtils.hasText(status)) { sql.append(" and i.status=?"); args.add(status.trim().toUpperCase()); }
        if (partnerId != null && partnerId > 0) { sql.append(" and i.partner_id=?"); args.add(partnerId); }
        if (from != null) { sql.append(" and i.invoice_date>=?"); args.add(Date.valueOf(from)); }
        if (to != null) { sql.append(" and i.invoice_date<=?"); args.add(Date.valueOf(to)); }
        if (exceptionOnly) sql.append(" and i.status='CONFIRMED'");
        sql.append(" order by i.invoice_date desc,i.id desc limit 1000");
        List<TaxInvoiceDtos.InvoiceRow> rows = jdbc.query(sql.toString(), invoiceRowMapper(), args.toArray());
        if (exceptionOnly) rows = rows.stream().filter(this::hasException).toList();
        return dataScope.filterDocumentList("TAX_INVOICE", rows);
    }

    /** Entity-wide close control must not be weakened by the operator's document data scope. */
    public List<TaxInvoiceDtos.InvoiceRow> closeExceptions(LocalDate from, LocalDate to) {
        long entityId = entityId();
        List<TaxInvoiceDtos.InvoiceRow> rows = jdbc.query(
                invoiceRowSelect() + " where i.entity_id=? and i.status='CONFIRMED'" +
                        " and i.invoice_date between ? and ? order by i.invoice_date,i.id",
                invoiceRowMapper(), entityId, Date.valueOf(from), Date.valueOf(to));
        return rows.stream().filter(this::hasException).toList();
    }

    public TaxInvoiceDtos.InvoiceDetail invoice(Long invoiceId) {
        List<TaxInvoiceDtos.InvoiceRow> headers = jdbc.query(invoiceRowSelect() + " where i.id=?", invoiceRowMapper(), invoiceId);
        if (headers.isEmpty()) throw new BusinessException("发票不存在");
        Map<String, Object> header = requiredRow("select * from tax_invoice where id=?", invoiceId);
        List<TaxInvoiceDtos.InvoiceLineView> lines = jdbc.query("""
                select * from tax_invoice_line where invoice_id=? order by line_no,id
                """, (rs, rowNum) -> new TaxInvoiceDtos.InvoiceLineView(
                rs.getLong("id"), rs.getLong("invoice_id"), rs.getInt("line_no"),
                rs.getLong("product_id"), rs.getString("product_sku_snapshot"),
                rs.getString("product_name_snapshot"), rs.getString("product_spec_snapshot"),
                rs.getString("unit_snapshot"), rs.getBigDecimal("quantity"),
                rs.getBigDecimal("unit_price_excluding_tax"), rs.getBigDecimal("amount_excluding_tax"),
                rs.getLong("tax_rate_rule_id"), rs.getString("tax_rule_code_snapshot"),
                rs.getString("tax_rule_name_snapshot"), rs.getBigDecimal("tax_rate"),
                rs.getString("calculation_method_snapshot"), rs.getString("tax_classification_code"),
                rs.getBigDecimal("tax_amount"), rs.getBigDecimal("total_amount"),
                rs.getString("legal_basis_snapshot")), invoiceId);
        List<TaxInvoiceDtos.RelationView> relations = jdbc.query("""
                select * from tax_invoice_relation where invoice_id=? order by relation_layer,id
                """, relationMapper(), invoiceId);
        List<TaxInvoiceDtos.InvoiceEventView> events = jdbc.query("""
                select * from tax_invoice_event where invoice_id=? order by id desc
                """, (rs, rowNum) -> new TaxInvoiceDtos.InvoiceEventView(
                rs.getLong("id"), rs.getLong("invoice_id"), rs.getString("action"),
                rs.getString("from_status"), rs.getString("to_status"), rs.getString("reason"),
                rs.getLong("operator_id"), rs.getString("operator_name"),
                rs.getTimestamp("created_at").toLocalDateTime()), invoiceId);
        List<TaxInvoiceDtos.UseEventView> useEvents = jdbc.query("""
                select * from tax_invoice_use_event where invoice_id=? order by id desc
                """, (rs, rowNum) -> new TaxInvoiceDtos.UseEventView(
                rs.getLong("id"), rs.getLong("invoice_id"), rs.getString("action"),
                rs.getString("from_usage_status"), rs.getString("to_usage_status"),
                rs.getString("tax_period"), rs.getBigDecimal("tax_amount"),
                rs.getLong("accounting_voucher_id"), rs.getString("evidence_reference"),
                rs.getString("reason"), rs.getString("operator_name"),
                rs.getTimestamp("created_at").toLocalDateTime()), invoiceId);
        List<TaxInvoiceDtos.VerificationRecordView> verificationRecords = jdbc.query("""
                select * from tax_invoice_verification_record where invoice_id=? order by id desc
                """, (rs, rowNum) -> new TaxInvoiceDtos.VerificationRecordView(
                rs.getLong("id"), rs.getLong("invoice_id"), rs.getString("verification_source"),
                rs.getString("verification_result"), rs.getString("verification_reference"),
                rs.getLong("proof_attachment_id"), rs.getString("proof_sha256"),
                rs.getTimestamp("checked_at").toLocalDateTime(), rs.getString("query_snapshot"),
                rs.getString("note"), rs.getString("operator_name"),
                rs.getTimestamp("created_at").toLocalDateTime()), invoiceId);
        return new TaxInvoiceDtos.InvoiceDetail(headers.get(0), string(header, "partner_business_no_snapshot"),
                string(header, "partner_tax_no_snapshot"), string(header, "issuer_name"),
                string(header, "issuer_tax_no"), string(header, "recipient_name"),
                string(header, "recipient_tax_no"), string(header, "source_channel"),
                string(header, "currency"), money((BigDecimal) header.get("rounding_difference")),
                string(header, "red_confirmation_no"), string(header, "red_confirmation_initiator"),
                dateTime(header.get("red_confirmation_initiated_at")),
                dateTime(header.get("red_confirmation_expires_at")),
                dateTime(header.get("red_confirmation_confirmed_at")),
                string(header, "source_file_name"), string(header, "source_file_sha256"),
                string(header, "memo"), string(header, "void_reason"),
                number(header, "void_voucher_id"), lines, relations, events, useEvents,
                verificationRecords);
    }

    @Transactional
    public Long saveInvoice(Long invoiceId, TaxInvoiceDtos.InvoiceSave request) {
        String direction = enumValue(request.direction(), DIRECTIONS, "发票方向");
        String color = enumValue(request.invoiceColor(), COLORS, "发票颜色");
        String type = enumValue(request.invoiceType(), INVOICE_TYPES, "发票类型");
        String invoiceNumber = required(request.invoiceNumber(), "发票号码");
        String invoiceCode = text(request.invoiceCode());
        validateInvoiceIdentity(type, invoiceCode, invoiceNumber);
        long partnerId = request.partnerId() == null ? 0L : request.partnerId();
        PartnerSnapshot partner = partnerSnapshot(partnerId, direction);
        long blueInvoiceId = request.blueInvoiceId() == null ? 0L : request.blueInvoiceId();
        if ("RED".equals(color)) validateRedDraft(direction, partnerId, blueInvoiceId, request.invoiceDate(), invoiceId);
        else if (blueInvoiceId > 0) throw new BusinessException("蓝字发票不能关联原蓝字发票");
        List<PreparedLine> lines = prepareLines(direction, request.invoiceDate(), request.lines());
        BigDecimal net = sum(lines, PreparedLine::net);
        BigDecimal tax = sum(lines, PreparedLine::tax);
        BigDecimal total = sum(lines, PreparedLine::total);
        if (net.add(tax).compareTo(total) != 0) throw new BusinessException("发票价税合计与金额、税额不一致");
        String currency = StringUtils.hasText(request.currency()) ? request.currency().trim().toUpperCase() : "CNY";
        if (!"CNY".equals(currency)) throw new BusinessException("当前阶段仅支持人民币发票；外币折算规则将在外币核算阶段建立");
        String sourceChannel = StringUtils.hasText(request.sourceChannel())
                ? enumValue(request.sourceChannel(), List.of("TAX_DIGITAL_ACCOUNT", "MANUAL", "IMPORT"), "发票来源") : "MANUAL";
        validateFileHash(request.sourceFileSha256());
        long entityId = entityId();
        Actor actor = actor();
        if (invoiceId == null || invoiceId <= 0) {
            Long id;
            try {
                id = insert("""
                        insert into tax_invoice
                        (entity_id,internal_no,direction,invoice_color,invoice_type,invoice_code,invoice_number,
                         blue_invoice_id,partner_id,partner_business_no_snapshot,partner_name_snapshot,
                         partner_tax_no_snapshot,issuer_name,issuer_tax_no,recipient_name,recipient_tax_no,
                         invoice_date,received_or_issued_date,source_channel,currency,amount_excluding_tax,
                         tax_amount,total_amount,rounding_difference,usage_status,source_file_name,
                         source_file_sha256,memo,created_by,created_by_name)
                        values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0,?,?,?,?,?,?)
                        """, entityId, nextInternalNo(), direction, color, type, invoiceCode,
                        invoiceNumber, blueInvoiceId, partnerId, partner.businessNo(), partner.name(),
                        partner.taxNo(), required(request.issuerName(), "销售方名称"), text(request.issuerTaxNo()),
                        required(request.recipientName(), "购买方名称"), text(request.recipientTaxNo()),
                        Date.valueOf(request.invoiceDate()), Date.valueOf(request.receivedOrIssuedDate()),
                        sourceChannel, currency, net, tax, total,
                        initialUsageStatus(direction, color),
                        text(request.sourceFileName()), text(request.sourceFileSha256()), text(request.memo()),
                        actor.id(), actor.name());
            } catch (DuplicateKeyException exception) {
                throw new BusinessException("该发票代码和号码已经登记，不能重复录入");
            }
            insertLines(id, lines);
            event(id, "CREATE", "", "DRAFT", "新增" + directionName(direction) + colorName(color) + "发票", actor);
            dataScope.stampDocumentOwner("TAX_INVOICE", id);
            return id;
        }
        InvoiceLock locked = lockInvoice(invoiceId);
        if (!"DRAFT".equals(locked.status())) throw new BusinessException("只有草稿发票可以修改");
        int expectedVersion = request.expectedVersionNo() == null ? locked.versionNo() : request.expectedVersionNo();
        if (expectedVersion != locked.versionNo()) throw new BusinessException("发票已被其他用户修改，请刷新后重试");
        try {
            int updated = jdbc.update("""
                    update tax_invoice set direction=?,invoice_color=?,invoice_type=?,invoice_code=?,invoice_number=?,
                      blue_invoice_id=?,partner_id=?,partner_business_no_snapshot=?,partner_name_snapshot=?,
                      partner_tax_no_snapshot=?,issuer_name=?,issuer_tax_no=?,recipient_name=?,recipient_tax_no=?,
                      invoice_date=?,received_or_issued_date=?,source_channel=?,currency=?,amount_excluding_tax=?,
                      tax_amount=?,total_amount=?,rounding_difference=0,source_file_name=?,source_file_sha256=?,memo=?,
                      usage_status=?,version_no=version_no+1
                    where id=? and status='DRAFT' and version_no=?
                    """, direction, color, type, invoiceCode, invoiceNumber, blueInvoiceId,
                    partnerId, partner.businessNo(), partner.name(), partner.taxNo(),
                    required(request.issuerName(), "销售方名称"), text(request.issuerTaxNo()),
                    required(request.recipientName(), "购买方名称"), text(request.recipientTaxNo()),
                    Date.valueOf(request.invoiceDate()), Date.valueOf(request.receivedOrIssuedDate()), sourceChannel,
                    currency, net, tax, total, text(request.sourceFileName()), text(request.sourceFileSha256()),
                    text(request.memo()), initialUsageStatus(direction, color),
                    invoiceId, expectedVersion);
            if (updated != 1) throw new BusinessException("发票已被其他用户修改，请刷新后重试");
        } catch (DuplicateKeyException exception) {
            throw new BusinessException("该发票代码和号码已经登记，不能重复录入");
        }
        jdbc.update("delete from tax_invoice_line where invoice_id=?", invoiceId);
        insertLines(invoiceId, lines);
        event(invoiceId, "UPDATE", "DRAFT", "DRAFT", "修改发票草稿", actor);
        return invoiceId;
    }

    @Transactional
    public Long confirmInvoice(Long invoiceId) {
        InvoiceLock invoice = lockInvoice(invoiceId);
        if ("CONFIRMED".equals(invoice.status())) {
            repairMissingAccountingRelation(invoice);
            return invoiceId;
        }
        if (!"DRAFT".equals(invoice.status())) throw new BusinessException("只有草稿发票可以确认");
        if (scalarLong("select count(*) from tax_invoice_line where invoice_id=?", invoiceId) == 0)
            throw new BusinessException("发票没有明细，不能确认");
        if ("RED".equals(invoice.color())) validateRedConfirmation(invoice);
        long voucherId = createBaseVoucher(invoice);
        Actor actor = actor();
        jdbc.update("""
                update tax_invoice set status='CONFIRMED',accounting_voucher_id=?,confirmed_by=?,
                  confirmed_by_name=?,confirmed_at=now(),version_no=version_no+1 where id=? and status='DRAFT'
                """, voucherId, actor.id(), actor.name(), invoiceId);
        if (voucherId > 0) {
            addAccountingRelation(invoiceId, voucherId, invoice.taxAmount(), "系统自动制证", actor);
        } else {
            long dispositionVoucherId = inputRedDispositionVoucher(invoice);
            if (dispositionVoucherId > 0)
                addAccountingRelation(invoiceId, dispositionVoucherId, invoice.taxAmount(),
                        "原蓝字进项税已转回成本或办理转出，本红票不重复生成分录", actor);
        }
        event(invoiceId, "CONFIRM", "DRAFT", "CONFIRMED",
                "确认发票登记；发票确认不等于收付款或业务确认", actor);
        return invoiceId;
    }

    private void repairMissingAccountingRelation(InvoiceLock invoice) {
        if ("INPUT".equals(invoice.direction()) && "RED".equals(invoice.color())
                && !"NOT_APPLICABLE".equals(invoice.usageStatus())) {
            Actor actor = actor();
            jdbc.update("update tax_invoice set usage_status='NOT_APPLICABLE',usage_period='',version_no=version_no+1 where id=?",
                    invoice.id());
            event(invoice.id(), "RED_USAGE_DERIVE", invoice.usageStatus(), "NOT_APPLICABLE",
                    "红字进项发票沿用原蓝字发票税务用途，不单独选择抵扣用途", actor);
        }
        if (invoice.taxAmount().compareTo(ZERO) == 0 || scalarLong("""
                select count(*) from tax_invoice_relation
                where invoice_id=? and relation_layer='ACCOUNTING' and status='ACTIVE'
                """, invoice.id()) > 0) return;
        long voucherId = invoice.accountingVoucherId() > 0
                ? invoice.accountingVoucherId() : inputRedDispositionVoucher(invoice);
        if (voucherId <= 0) return;
        Actor actor = actor();
        int inserted = addAccountingRelation(invoice.id(), voucherId, invoice.taxAmount(),
                invoice.accountingVoucherId() > 0 ? "系统自动制证"
                        : "原蓝字进项税已转回成本或办理转出，本红票不重复生成分录", actor);
        if (inserted > 0)
            event(invoice.id(), "ACCOUNTING_RELATION_REPAIR", "", "ACCOUNTING",
                    "补齐既有发票与会计处理的派生勾稽关系", actor);
    }

    @Transactional
    public Long voidInvoice(Long invoiceId, String reason) {
        InvoiceLock invoice = lockInvoice(invoiceId);
        if ("VOIDED".equals(invoice.status())) return invoiceId;
        if (!"CONFIRMED".equals(invoice.status())) throw new BusinessException("只有已确认发票可以作废");
        if ("BLUE".equals(invoice.color()) && scalarLong("""
                select count(*) from tax_invoice where blue_invoice_id=? and status='CONFIRMED'
                """, invoiceId) > 0) throw new BusinessException("该蓝字发票已有红字发票，不能作废");
        if ("INPUT".equals(invoice.direction()) && ("CONFIRMED".equals(invoice.bookingStatus())
                || !"UNCONFIRMED".equals(invoice.usageStatus())))
            throw new BusinessException("进项发票已入账或已确认用途，不能直接作废；请按红字发票流程处理");
        String normalizedReason = required(reason, "作废原因");
        long voidVoucherId = accounting.disposeSourceVoucher(invoice.accountingVoucherId(), LocalDate.now(),
                "发票作废 " + invoice.internalNo() + "：" + normalizedReason);
        Actor actor = actor();
        jdbc.update("""
                update tax_invoice set status='VOIDED',void_reason=?,void_voucher_id=?,voided_at=now(),
                  version_no=version_no+1 where id=? and status='CONFIRMED'
                """, normalizedReason, voidVoucherId, invoiceId);
        event(invoiceId, "VOID", "CONFIRMED", "VOIDED", normalizedReason, actor);
        return invoiceId;
    }

    @Transactional
    public Long verifyInvoice(Long invoiceId, TaxInvoiceDtos.VerifyRequest request) {
        InvoiceLock invoice = lockInvoice(invoiceId);
        requireConfirmed(invoice);
        String status = enumValue(request.verificationStatus(), List.of("VERIFIED", "FAILED"), "查验结果");
        String source = enumValue(request.verificationSource(), List.of(
                "TAX_DIGITAL_ACCOUNT", "NATIONAL_PLATFORM", "LOCAL_TAX_AUTHORITY", "MANUAL_REVIEW"), "查验来源");
        EvidenceAttachmentService.AttachmentRecord proof = evidenceAttachments.requiredActive(
                request.proofAttachmentId(), "TAX_INVOICE", invoiceId, "VERIFICATION_PROOF");
        String reference = required(request.verificationReference(), "查验凭据");
        LocalDateTime checkedAt = request.checkedAt() == null ? LocalDateTime.now() : request.checkedAt();
        if (checkedAt.isAfter(LocalDateTime.now().plusMinutes(5))) throw new BusinessException("实际查验时间不能晚于当前时间");
        Actor actor = actor();
        String before = invoice.verificationStatus();
        jdbc.update("""
                update tax_invoice set verification_status=?,verification_reference=?,verified_at=?,
                  version_no=version_no+1 where id=?
                """, status, reference, Timestamp.valueOf(checkedAt), invoiceId);
        jdbc.update("""
                insert into tax_invoice_verification_record
                (invoice_id,verification_source,verification_result,verification_reference,
                 proof_attachment_id,proof_sha256,checked_at,query_snapshot,note,operator_id,operator_name)
                values (?,?,?,?,?,?,?,?,?,?,?)
                """, invoiceId, source, status, reference, proof.id(), proof.sha256(),
                Timestamp.valueOf(checkedAt), text(request.querySnapshot()), text(request.reason()),
                actor.id(), actor.name());
        event(invoiceId, "VERIFY", before, status,
                text(request.reason()) + "；查验来源=" + source + "；查验凭据=" + reference
                        + "；附件SHA-256=" + proof.sha256(), actor);
        return invoiceId;
    }

    private void validateInvoiceIdentity(String type, String invoiceCode, String invoiceNumber) {
        if (type.startsWith("DIGITAL")) {
            if (StringUtils.hasText(invoiceCode)) throw new BusinessException("数电发票不填写传统发票代码");
            if (!invoiceNumber.matches("\\d{20}")) throw new BusinessException("数电发票号码必须为20位数字");
            return;
        }
        if (Set.of("VAT_SPECIAL", "VAT_NORMAL").contains(type)) {
            if (!invoiceCode.matches("\\d{10}|\\d{12}"))
                throw new BusinessException("传统增值税发票代码必须为10位或12位数字");
            if (!invoiceNumber.matches("\\d{8}"))
                throw new BusinessException("传统增值税发票号码必须为8位数字");
            return;
        }
        if (!invoiceNumber.matches("[0-9A-Za-z-]{6,32}"))
            throw new BusinessException("其他发票号码仅允许6至32位数字、字母或短横线");
        if (StringUtils.hasText(invoiceCode) && !invoiceCode.matches("[0-9A-Za-z-]{6,32}"))
            throw new BusinessException("其他发票代码格式不正确");
    }

    @Transactional
    public Long confirmBooking(Long invoiceId, TaxInvoiceDtos.BookingRequest request) {
        InvoiceLock invoice = lockInvoice(invoiceId);
        requireConfirmed(invoice);
        if ("CONFIRMED".equals(invoice.bookingStatus())) return invoiceId;
        jdbc.update("""
                update tax_invoice set booking_status='CONFIRMED',booked_at=now(),version_no=version_no+1 where id=?
                """, invoiceId);
        Actor actor = actor();
        useEvent(invoiceId, "BOOKING_CONFIRM", invoice.usageStatus(), invoice.usageStatus(), "",
                invoice.taxAmount(), 0L, request.evidenceReference(), request.reason(), actor);
        event(invoiceId, "BOOKING_CONFIRM", "UNCONFIRMED", "CONFIRMED",
                "税务数字账户入账标识；凭据=" + request.evidenceReference(), actor);
        return invoiceId;
    }

    @Transactional
    public Long confirmUsage(Long invoiceId, TaxInvoiceDtos.UsageRequest request) {
        InvoiceLock invoice = lockInvoice(invoiceId);
        requireInputBlueConfirmed(invoice);
        if (!"VERIFIED".equals(invoice.verificationStatus())) throw new BusinessException("进项发票查验通过后才能确认用途");
        if (!"UNCONFIRMED".equals(invoice.usageStatus())) throw new BusinessException("进项发票用途已经确认，不能重复确认");
        String usage = enumValue(request.usageStatus(), List.of("DEDUCTION", "NON_DEDUCTION"), "进项用途");
        if ("DEDUCTION".equals(usage) && treasuryTaxRelations(invoiceId).stream()
                .anyMatch(TreasuryTaxRelation::includedInCost))
            throw new BusinessException("发票已关联税额计入存货成本的采购附加费用，不能再确认为可抵扣进项税");
        YearMonth period = taxPeriod(request.taxPeriod());
        long voucherId = createUsageVoucher(invoice, usage, period);
        jdbc.update("""
                update tax_invoice set usage_status=?,usage_period=?,usage_voucher_id=?,version_no=version_no+1
                where id=? and usage_status='UNCONFIRMED'
                """, usage, period.toString(), voucherId, invoiceId);
        Actor actor = actor();
        useEvent(invoiceId, "USAGE_CONFIRM", "UNCONFIRMED", usage, period.toString(),
                invoice.taxAmount(), voucherId, request.evidenceReference(), request.reason(), actor);
        event(invoiceId, "USAGE_CONFIRM", "UNCONFIRMED", usage,
                "进项用途确认；税期=" + period + "；凭据=" + request.evidenceReference(), actor);
        return invoiceId;
    }

    @Transactional
    public Long transferOut(Long invoiceId, TaxInvoiceDtos.TransferOutRequest request) {
        InvoiceLock invoice = lockInvoice(invoiceId);
        requireInputBlueConfirmed(invoice);
        if (!"DEDUCTION".equals(invoice.usageStatus())) throw new BusinessException("只有已抵扣进项税额可以办理转出");
        YearMonth period = taxPeriod(request.taxPeriod());
        long voucherId = createTransferOutVoucher(invoice, period, request.reason());
        jdbc.update("""
                update tax_invoice set usage_status='TRANSFERRED_OUT',usage_period=?,transfer_out_voucher_id=?,
                  version_no=version_no+1 where id=? and usage_status='DEDUCTION'
                """, period.toString(), voucherId, invoiceId);
        Actor actor = actor();
        useEvent(invoiceId, "TRANSFER_OUT", "DEDUCTION", "TRANSFERRED_OUT", period.toString(),
                invoice.taxAmount(), voucherId, request.evidenceReference(), request.reason(), actor);
        event(invoiceId, "TRANSFER_OUT", "DEDUCTION", "TRANSFERRED_OUT",
                request.reason() + "；税期=" + period + "；凭据=" + request.evidenceReference(), actor);
        return invoiceId;
    }

    @Transactional
    public Long updateRedConfirmation(Long invoiceId, TaxInvoiceDtos.RedConfirmationRequest request) {
        InvoiceLock invoice = lockInvoice(invoiceId);
        if (!"DRAFT".equals(invoice.status()) || !"RED".equals(invoice.color()))
            throw new BusinessException("只有红字发票草稿可以维护红字确认单");
        String status = enumValue(request.confirmationStatus(), List.of("PENDING", "CONFIRMED", "EXPIRED"), "确认单状态");
        String initiator = enumValue(request.initiator(), List.of("ISSUER", "RECIPIENT"), "发起方");
        LocalDateTime initiatedAt = request.initiatedAt() == null ? LocalDateTime.now() : request.initiatedAt();
        LocalDateTime confirmedAt = request.confirmedAt();
        if ("CONFIRMED".equals(status)) {
            required(request.confirmationNo(), "红字确认单编号");
            if (confirmedAt == null) confirmedAt = LocalDateTime.now();
            if (confirmedAt.isAfter(initiatedAt.plusHours(72)))
                throw new BusinessException("确认时间已超过确认单发起后72小时，请重新发起确认单");
        }
        if ("EXPIRED".equals(status) && LocalDateTime.now().isBefore(initiatedAt.plusHours(72)))
            throw new BusinessException("确认单发起未满72小时，不能登记为已过期");
        String before = invoice.redConfirmationStatus();
        jdbc.update("""
                update tax_invoice set red_confirmation_status=?,red_confirmation_no=?,
                  red_confirmation_initiator=?,red_confirmation_initiated_at=?,
                  red_confirmation_expires_at=?,red_confirmation_confirmed_at=?,version_no=version_no+1
                where id=? and status='DRAFT'
                """, status, text(request.confirmationNo()), initiator, Timestamp.valueOf(initiatedAt),
                Timestamp.valueOf(initiatedAt.plusHours(72)), confirmedAt == null ? null : Timestamp.valueOf(confirmedAt),
                invoiceId);
        event(invoiceId, "RED_CONFIRMATION", before, status,
                text(request.reason()) + "；发起方=" + initiator, actor());
        return invoiceId;
    }

    public List<TaxInvoiceDtos.SourceCandidate> sourceCandidates(String direction, Long partnerId,
                                                                  String invoiceColor) {
        String normalizedDirection = enumValue(direction, DIRECTIONS, "发票方向");
        String color = StringUtils.hasText(invoiceColor)
                ? enumValue(invoiceColor, COLORS, "发票颜色") : "BLUE";
        partnerSnapshot(partnerId == null ? 0L : partnerId, normalizedDirection);
        List<SourceRow> rows = new ArrayList<>();
        if ("INPUT".equals(normalizedDirection)) {
            if ("BLUE".equals(color)) {
                rows.addAll(sourceRows("PURCHASE_ORDER", """
                        select po.id,po.order_no source_no,po.business_date,po.supplier_id partner_id,
                               p.name partner_name,po.total_amount source_amount,po.status
                        from purchase_order po join partner p on p.id=po.supplier_id
                        where po.supplier_id=? and po.status not in ('DRAFT','CANCELLED')
                        """, partnerId));
                rows.addAll(sourceRows("INBOUND", """
                        select o.id,o.order_no source_no,o.business_date,o.supplier_id partner_id,
                               p.name partner_name,o.total_amount source_amount,o.status
                        from inbound_order o join partner p on p.id=o.supplier_id
                        where o.supplier_id=? and o.status='STOCKED'
                        """, partnerId));
                rows.addAll(sourceRows("PAYABLE", """
                        select m.id,m.movement_no source_no,date(m.occurred_at) business_date,m.supplier_id partner_id,
                               p.name partner_name,m.amount source_amount,concat(m.direction,':',m.ref_type) status
                        from payable_movement m join partner p on p.id=m.supplier_id
                        where m.supplier_id=? and m.direction='INCREASE'
                          and m.ref_type in ('INBOUND','PURCHASE_ORDER')
                        """, partnerId));
                rows.addAll(sourceRows("SUPPLIER_PAYMENT", """
                        select s.id,s.payment_no source_no,s.paid_date business_date,s.supplier_id partner_id,
                               p.name partner_name,s.amount source_amount,s.verification_status status
                        from supplier_payment s join partner p on p.id=s.supplier_id
                        where s.supplier_id=? and s.verification_status<>'REVERSED'
                        """, partnerId));
                rows.addAll(sourceRows("TREASURY_DOCUMENT", """
                        select d.id,d.document_no source_no,d.business_date,d.partner_id,
                               d.partner_name_snapshot partner_name,d.total_amount source_amount,d.status
                        from treasury_document d where d.partner_id=?
                          and d.document_type in ('CORPORATE_EXPENSE','OTHER_EXPENSE','OTHER_PAYABLE','PURCHASE_LANDED_COST')
                          and d.status in ('CONFIRMED','PARTIALLY_SETTLED','SETTLED')
                        """, partnerId));
                rows.addAll(sourceRows("TREASURY_ACCRUAL", """
                        select d.id,d.document_no source_no,d.business_date,d.partner_id,
                               d.partner_name_snapshot partner_name,d.total_amount source_amount,d.status
                        from treasury_document d where d.partner_id=?
                          and d.document_type in ('CORPORATE_EXPENSE','OTHER_EXPENSE','OTHER_PAYABLE','PURCHASE_LANDED_COST')
                          and d.status in ('CONFIRMED','PARTIALLY_SETTLED','SETTLED')
                        """, partnerId));
                rows.addAll(sourceRows("TREASURY_JOURNAL", """
                        select j.id,j.journal_no source_no,j.transaction_date business_date,j.partner_id,
                               j.partner_name_snapshot partner_name,j.amount source_amount,j.status
                        from treasury_cash_journal j where j.partner_id=? and j.direction='DEBIT' and j.status='ACTIVE'
                        """, partnerId));
            } else {
                rows.addAll(sourceRows("PURCHASE_RETURN", """
                        select r.id,r.order_no source_no,r.business_date,r.supplier_id partner_id,
                               p.name partner_name,r.total_amount source_amount,r.status
                        from purchase_return_order r join partner p on p.id=r.supplier_id
                        where r.supplier_id=? and r.status='CONFIRMED'
                        """, partnerId));
                rows.addAll(sourceRows("PAYABLE", """
                        select m.id,m.movement_no source_no,date(m.occurred_at) business_date,m.supplier_id partner_id,
                               p.name partner_name,m.amount source_amount,
                               concat(m.direction,':',m.ref_type,':',r.status) status
                        from payable_movement m join partner p on p.id=m.supplier_id
                        join purchase_return_order r on r.id=m.ref_id and m.ref_type='PURCHASE_RETURN'
                        where m.supplier_id=? and m.direction='DECREASE' and r.status='CONFIRMED'
                        """, partnerId));
            }
        } else if ("BLUE".equals(color)) {
            rows.addAll(sourceRows("SALES_ORDER", """
                    select s.id,s.order_no source_no,s.business_date,s.customer_id partner_id,
                           p.name partner_name,s.total_amount source_amount,s.status
                    from sales_order s join partner p on p.id=s.customer_id
                    where s.customer_id=? and s.status not in ('DRAFT','CANCELLED')
                    """, partnerId));
            rows.addAll(sourceRows("OUTBOUND", """
                    select o.id,o.order_no source_no,o.business_date,o.customer_id partner_id,
                           p.name partner_name,o.total_amount source_amount,o.status
                    from outbound_order o join partner p on p.id=o.customer_id
                    where o.customer_id=? and o.status='SHIPPED'
                    """, partnerId));
            rows.addAll(sourceRows("RECEIVABLE", """
                    select m.id,m.movement_no source_no,date(m.occurred_at) business_date,m.customer_id partner_id,
                           p.name partner_name,m.amount source_amount,concat(m.direction,':',m.ref_type) status
                    from receivable_movement m join partner p on p.id=m.customer_id
                    where m.customer_id=? and m.direction='DEBIT'
                      and m.ref_type in ('OUTBOUND','SALES_ORDER')
                    """, partnerId));
            rows.addAll(sourceRows("PAYMENT", """
                    select m.id,m.payment_no source_no,m.received_date business_date,m.customer_id partner_id,
                           p.name partner_name,m.amount source_amount,m.verification_status status
                    from payment m join partner p on p.id=m.customer_id
                    where m.customer_id=? and m.verification_status<>'REVERSED'
                    """, partnerId));
            rows.addAll(sourceRows("TREASURY_DOCUMENT", """
                    select d.id,d.document_no source_no,d.business_date,d.partner_id,
                           d.partner_name_snapshot partner_name,d.total_amount source_amount,d.status
                    from treasury_document d where d.partner_id=?
                      and d.document_type in ('OTHER_INCOME','OTHER_RECEIVABLE')
                      and d.status in ('CONFIRMED','PARTIALLY_SETTLED','SETTLED')
                    """, partnerId));
            rows.addAll(sourceRows("TREASURY_ACCRUAL", """
                    select d.id,d.document_no source_no,d.business_date,d.partner_id,
                           d.partner_name_snapshot partner_name,d.total_amount source_amount,d.status
                    from treasury_document d where d.partner_id=?
                      and d.document_type in ('OTHER_INCOME','OTHER_RECEIVABLE')
                      and d.status in ('CONFIRMED','PARTIALLY_SETTLED','SETTLED')
                    """, partnerId));
            rows.addAll(sourceRows("TREASURY_JOURNAL", """
                    select j.id,j.journal_no source_no,j.transaction_date business_date,j.partner_id,
                           j.partner_name_snapshot partner_name,j.amount source_amount,j.status
                    from treasury_cash_journal j where j.partner_id=? and j.direction='CREDIT' and j.status='ACTIVE'
                    """, partnerId));
        } else {
            rows.addAll(sourceRows("SALES_RETURN", """
                    select r.id,r.return_no source_no,r.business_date,r.customer_id partner_id,
                           p.name partner_name,r.total_amount source_amount,r.status
                    from sales_return_order r join partner p on p.id=r.customer_id
                    where r.customer_id=? and r.status='CONFIRMED'
                    """, partnerId));
            rows.addAll(sourceRows("RECEIVABLE", """
                    select m.id,m.movement_no source_no,date(m.occurred_at) business_date,m.customer_id partner_id,
                           p.name partner_name,m.amount source_amount,
                           concat(m.direction,':',m.ref_type,':',r.status) status
                    from receivable_movement m join partner p on p.id=m.customer_id
                    join sales_return_order r on r.id=m.ref_id and m.ref_type='SALES_RETURN'
                    where m.customer_id=? and m.direction='CREDIT' and r.status='CONFIRMED'
                    """, partnerId));
        }
        return rows.stream().map(row -> {
            BigDecimal matched = sourceMatched(row.sourceType(), row.id(), normalizedDirection, color);
            BigDecimal available = row.amount().subtract(matched).max(ZERO);
            SourceTaxSnapshot taxSnapshot = sourceTaxSnapshot(row.sourceType(), row.id(), row.amount());
            BigDecimal availableNet = row.amount().signum() == 0 ? ZERO
                    : money(taxSnapshot.netAmount().multiply(available).divide(row.amount(), 8, RoundingMode.HALF_UP));
            BigDecimal availableTax = money(available.subtract(availableNet));
            return new TaxInvoiceDtos.SourceCandidate(relationLayer(row.sourceType()), row.sourceType(), row.id(),
                    row.sourceNo(), row.businessDate(), row.partnerId(), row.partnerName(), row.amount(),
                    matched, available, taxSnapshot.netAmount(), taxSnapshot.taxAmount(), availableNet,
                    availableTax, taxSnapshot.roundingDifference(), taxSnapshot.available(), row.status());
        }).filter(row -> row.availableAmount().compareTo(ZERO) > 0).toList();
    }

    private SourceTaxSnapshot sourceTaxSnapshot(String sourceType, long sourceId, BigDecimal totalAmount) {
        if (Set.of("TREASURY_DOCUMENT", "TREASURY_ACCRUAL").contains(sourceType)) {
            return jdbc.queryForObject("""
                    select amount_excluding_tax,tax_amount,tax_rounding_difference
                    from treasury_document where id=?
                    """, (rs, rowNum) -> new SourceTaxSnapshot(money(rs.getBigDecimal(1)),
                    money(rs.getBigDecimal(2)), money(rs.getBigDecimal(3)), true), sourceId);
        }
        String table = switch (sourceType) {
            case "PURCHASE_ORDER" -> "purchase_order";
            case "SALES_ORDER" -> "sales_order";
            case "INBOUND" -> "inbound_order";
            case "OUTBOUND" -> "outbound_order";
            case "PURCHASE_RETURN" -> "purchase_return_order";
            case "SALES_RETURN" -> "sales_return_order";
            default -> "";
        };
        if (table.isEmpty()) return new SourceTaxSnapshot(money(totalAmount), ZERO, ZERO, false);
        return jdbc.queryForObject("select amount_excluding_tax,tax_amount,tax_rounding_difference from " + table + " where id=?",
                (rs, rowNum) -> new SourceTaxSnapshot(money(rs.getBigDecimal(1)), money(rs.getBigDecimal(2)),
                        money(rs.getBigDecimal(3)), true), sourceId);
    }

    @Transactional
    public Long addRelation(Long invoiceId, TaxInvoiceDtos.RelationSave request) {
        InvoiceLock invoice = lockInvoice(invoiceId);
        if ("VOIDED".equals(invoice.status())) throw new BusinessException("已作废发票不能新增勾稽关系");
        String sourceType = required(request.sourceType(), "来源类型").toUpperCase();
        String layer = relationLayer(sourceType);
        if ("ACCOUNTING".equals(layer)) throw new BusinessException("会计凭证关联由系统生成，不能手工新增");
        SourceInfo source = sourceInfo(sourceType, request.sourceId());
        if (source.partnerId() != invoice.partnerId()) throw new BusinessException("来源单据往来单位与发票不一致");
        validateSourceDirection(invoice.direction(), invoice.color(), sourceType, source.status());
        BigDecimal net = money(request.amountExcludingTax());
        BigDecimal tax = money(request.taxAmount());
        BigDecimal total = money(request.totalAmount());
        if (net.add(tax).compareTo(total) != 0) throw new BusinessException("勾稽金额的价税合计不一致");
        BigDecimal invoiceLayerMatched = layerMatched(invoiceId, layer);
        if (invoiceLayerMatched.add(total).compareTo(invoice.totalAmount()) > 0)
            throw new BusinessException("该勾稽层累计金额不能超过发票价税合计");
        BigDecimal invoiceLayerTaxMatched = layerMatchedTax(invoiceId, layer);
        if (invoiceLayerTaxMatched.add(tax).compareTo(invoice.taxAmount()) > 0)
            throw new BusinessException("该勾稽层累计税额不能超过发票税额");
        BigDecimal sourceMatched = sourceMatched(sourceType, source.id(), invoice.direction(), invoice.color());
        if (sourceMatched.add(total).compareTo(source.amount()) > 0)
            throw new BusinessException("来源单据在同类发票中的累计勾稽金额不能超过来源金额");
        String matchingBasis = required(request.matchingBasis(), "勾稽依据");
        SourceTaxSnapshot sourceTax = sourceTaxSnapshot(sourceType, source.id(), source.amount());
        if (sourceTax.available() && source.amount().signum() > 0) {
            BigDecimal expectedNet = money(sourceTax.netAmount().multiply(total)
                    .divide(source.amount(), 8, RoundingMode.HALF_UP));
            BigDecimal expectedTax = money(total.subtract(expectedNet));
            BigDecimal netDifference = money(net.subtract(expectedNet));
            BigDecimal taxDifference = money(tax.subtract(expectedTax));
            if (netDifference.signum() != 0 || taxDifference.signum() != 0) {
                String difference = "；来源价税差异：未税" + signedMoney(netDifference)
                        + "，税额" + signedMoney(taxDifference);
                matchingBasis = matchingBasis.substring(0, Math.min(matchingBasis.length(), 500 - difference.length()))
                        + difference;
            }
        }
        Actor actor = actor();
        try {
            Long relationId = insert("""
                    insert into tax_invoice_relation
                    (invoice_id,relation_layer,source_type,source_id,source_no,source_business_date,partner_id,
                     amount_excluding_tax,tax_amount,total_amount,matching_basis,created_by,created_by_name)
                    values (?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, invoiceId, layer, sourceType, source.id(), source.no(), sqlDate(source.businessDate()),
                    source.partnerId(), net, tax, total, matchingBasis,
                    actor.id(), actor.name());
            event(invoiceId, "RELATION_ADD", "", layer,
                    sourceType + " " + source.no() + "；价税合计=" + total
                            + (sourceTax.available() ? "；来源快照未税=" + sourceTax.netAmount()
                            + "，税额=" + sourceTax.taxAmount() : "；该层无独立价税快照"), actor);
            if ("CONFIRMED".equals(invoice.status()) && "TREASURY_DOCUMENT".equals(sourceType)) {
                long voucherId = createTreasuryRelationAdjustment(invoice, relationId, source, tax, false);
                if (voucherId > 0) {
                    addAccountingRelation(invoiceId, voucherId, ZERO,
                            "发票确认后补充费用或收入源单勾稽，系统调整税额重分类科目", actor);
                }
            }
            return relationId;
        } catch (DuplicateKeyException exception) {
            throw new BusinessException("该来源单据已经与本发票建立过勾稽关系");
        }
    }

    @Transactional
    public Long reverseRelation(Long invoiceId, Long relationId, String reason) {
        InvoiceLock invoice = lockInvoice(invoiceId);
        if ("VOIDED".equals(invoice.status())) throw new BusinessException("已作废发票不能修改勾稽关系");
        Map<String, Object> relation = requiredRow("""
                select * from tax_invoice_relation where id=? and invoice_id=? for update
                """, relationId, invoiceId);
        if ("REVERSED".equals(relation.get("status"))) return relationId;
        if ("ACCOUNTING".equals(relation.get("relation_layer")))
            throw new BusinessException("系统生成的会计凭证关系不能手工解除");
        Actor actor = actor();
        if ("CONFIRMED".equals(invoice.status())
                && "TREASURY_DOCUMENT".equals(String.valueOf(relation.get("source_type")))) {
            Map<String, Object> sourceDocument = requiredRow(
                    "select input_tax_treatment from treasury_document where id=?", number(relation, "source_id"));
            if ("INCLUDED_IN_COST".equals(String.valueOf(sourceDocument.get("input_tax_treatment")))
                    && !"UNCONFIRMED".equals(invoice.usageStatus()))
                throw new BusinessException("税额计入存货成本的发票用途已确认，不能再解除费用源单勾稽；请由会计按冲销路径处理");
            reverseTreasuryRelationAccounting(invoice, relationId, relation, actor);
        }
        jdbc.update("""
                update tax_invoice_relation set status='REVERSED',reversed_reason=?,reversed_by=?,
                  reversed_by_name=?,reversed_at=now() where id=? and status='ACTIVE'
                """, required(reason, "解除原因"), actor.id(), actor.name(), relationId);
        event(invoiceId, "RELATION_REVERSE", String.valueOf(relation.get("relation_layer")), "REVERSED",
                reason + "；来源=" + relation.get("source_type") + " " + relation.get("source_no"), actor);
        return relationId;
    }

    private long createBaseVoucher(InvoiceLock invoice) {
        if (invoice.taxAmount().compareTo(ZERO) == 0) return 0L;
        List<AccountingService.SystemVoucherLine> lines = new ArrayList<>();
        String summary = invoice.internalNo() + " " + directionName(invoice.direction()) + colorName(invoice.color()) + "税额重分类";
        if ("OUTPUT".equals(invoice.direction()) && "BLUE".equals(invoice.color())) {
            List<TreasuryTaxRelation> treasury = treasuryTaxRelations(invoice.id());
            BigDecimal allocated = treasury.stream().map(TreasuryTaxRelation::taxAmount)
                    .reduce(ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
            if (allocated.compareTo(invoice.taxAmount()) > 0)
                throw new BusinessException("费用或收入源单勾稽税额超过发票税额");
            for (TreasuryTaxRelation relation : treasury) {
                if (relation.taxAmount().signum() > 0)
                    lines.add(systemLine(relation.offsetAccountCode(), summary + " " + relation.documentNo(),
                            relation.taxAmount(), ZERO, invoice.partnerId()));
            }
            BigDecimal remaining = money(invoice.taxAmount().subtract(allocated));
            if (remaining.signum() > 0) lines.add(systemLine("5001", summary, remaining, ZERO, invoice.partnerId()));
            lines.add(systemLine("22210102", summary, ZERO, invoice.taxAmount(), 0L));
        } else if ("OUTPUT".equals(invoice.direction())) {
            lines.add(systemLine("22210102", summary, invoice.taxAmount(), ZERO, 0L));
            lines.add(systemLine("5001", summary, ZERO, invoice.taxAmount(), invoice.partnerId()));
        } else if ("BLUE".equals(invoice.color())) {
            lines.add(systemLine("22210103", summary, invoice.taxAmount(), ZERO, 0L));
            List<TreasuryTaxRelation> treasury = treasuryTaxRelations(invoice.id());
            BigDecimal allocated = treasury.stream().map(TreasuryTaxRelation::taxAmount)
                    .reduce(ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
            if (allocated.compareTo(invoice.taxAmount()) > 0)
                throw new BusinessException("费用或收入源单勾稽税额超过发票税额");
            for (TreasuryTaxRelation relation : treasury) {
                if (relation.taxAmount().signum() > 0)
                    lines.add(systemLine(relation.offsetAccountCode(), summary + " " + relation.documentNo(),
                            ZERO, relation.taxAmount(), invoice.partnerId()));
            }
            BigDecimal remaining = money(invoice.taxAmount().subtract(allocated));
            if (remaining.signum() > 0) lines.add(systemLine("1405", summary, ZERO, remaining, 0L));
        } else {
            InvoiceLock blue = lockInvoice(invoice.blueInvoiceId());
            if (List.of("NON_DEDUCTION", "TRANSFERRED_OUT").contains(blue.usageStatus())) return 0L;
            lines.add(systemLine("1405", summary, invoice.taxAmount(), ZERO, 0L));
            lines.add(systemLine("DEDUCTION".equals(blue.usageStatus()) ? "22210101" : "22210103",
                    summary, ZERO, invoice.taxAmount(), 0L));
        }
        return accounting.createSourceVoucher("TAX_INVOICE_BASE", invoice.id(), invoice.internalNo(),
                invoice.invoiceDate(), summary, lines);
    }

    private List<TreasuryTaxRelation> treasuryTaxRelations(long invoiceId) {
        return jdbc.query("""
                select r.id,r.tax_amount,d.document_no,d.offset_account_code_snapshot,d.input_tax_treatment
                from tax_invoice_relation r join treasury_document d on d.id=r.source_id
                where r.invoice_id=? and r.relation_layer='GOODS'
                  and r.source_type='TREASURY_DOCUMENT' and r.status='ACTIVE'
                order by r.id
        """, (rs, rowNum) -> new TreasuryTaxRelation(rs.getLong("id"),
                money(rs.getBigDecimal("tax_amount")), rs.getString("document_no"),
                rs.getString("offset_account_code_snapshot"),
                "INCLUDED_IN_COST".equals(rs.getString("input_tax_treatment"))), invoiceId);
    }

    private long createTreasuryRelationAdjustment(InvoiceLock invoice, long relationId,
                                                   SourceInfo source, BigDecimal tax,
                                                   boolean unmatched) {
        BigDecimal amount = money(tax);
        if (amount.signum() == 0) return 0L;
        Map<String, Object> document = requiredRow("""
                select document_no,offset_account_code_snapshot,input_tax_treatment from treasury_document where id=?
                """, source.id());
        String offset = String.valueOf(document.get("offset_account_code_snapshot"));
        boolean includedInCost = "INCLUDED_IN_COST".equals(String.valueOf(document.get("input_tax_treatment")));
        if (includedInCost && "DEDUCTION".equals(invoice.usageStatus()))
            throw new BusinessException("该采购附加费用的税额已计入存货成本，不能关联已抵扣进项发票");
        if (includedInCost && List.of("NON_DEDUCTION", "TRANSFERRED_OUT").contains(invoice.usageStatus()))
            return 0L;
        String summary = invoice.internalNo() + (unmatched ? " 解除费用或收入税票勾稽" : " 后补费用或收入税票勾稽")
                + " " + source.no();
        List<AccountingService.SystemVoucherLine> lines;
        if ("INPUT".equals(invoice.direction())) {
            lines = unmatched
                    ? List.of(systemLine(offset, summary, amount, ZERO, invoice.partnerId()),
                        systemLine("1405", summary, ZERO, amount, 0L))
                    : List.of(systemLine("1405", summary, amount, ZERO, 0L),
                        systemLine(offset, summary, ZERO, amount, invoice.partnerId()));
        } else {
            lines = unmatched
                    ? List.of(systemLine("5001", summary, amount, ZERO, invoice.partnerId()),
                        systemLine(offset, summary, ZERO, amount, invoice.partnerId()))
                    : List.of(systemLine(offset, summary, amount, ZERO, invoice.partnerId()),
                        systemLine("5001", summary, ZERO, amount, invoice.partnerId()));
        }
        return accounting.createSourceVoucher(unmatched ? "TAX_INVOICE_TREASURY_UNMATCH" : "TAX_INVOICE_TREASURY_RECLASS",
                relationId, source.no() + (unmatched ? "/UNMATCH" : "/RECLASS"),
                treasuryAdjustmentDate(invoice.invoiceDate()), summary, lines);
    }

    private void reverseTreasuryRelationAccounting(InvoiceLock invoice, long relationId,
                                                    Map<String, Object> relation, Actor actor) {
        List<Long> adjustmentIds = jdbc.queryForList("""
                select id from accounting_voucher
                where source_type='TAX_INVOICE_TREASURY_RECLASS' and source_id=?
                  and original_voucher_id=0 and status<>'CANCELLED'
                order by id desc limit 1
                """, Long.class, relationId);
        if (!adjustmentIds.isEmpty()) {
            accounting.disposeSourceVoucher(adjustmentIds.get(0), treasuryAdjustmentDate(invoice.invoiceDate()),
                    "解除费用或收入源单与发票勾稽");
            return;
        }
        SourceInfo source = sourceInfo("TREASURY_DOCUMENT", number(relation, "source_id"));
        long voucherId = createTreasuryRelationAdjustment(invoice, relationId, source,
                money((BigDecimal) relation.get("tax_amount")), true);
        if (voucherId > 0) {
            addAccountingRelation(invoice.id(), voucherId, ZERO,
                    "解除费用或收入源单勾稽，系统恢复通用税额重分类科目", actor);
        }
    }

    private LocalDate treasuryAdjustmentDate(LocalDate invoiceDate) {
        List<Map<String, Object>> periods = jdbc.queryForList("""
                select p.start_date,p.end_date from accounting_period p
                join accounting_entity e on e.id=p.entity_id
                where e.id=(select min(id) from accounting_entity where status='ENABLED') and p.status='OPEN'
                order by p.start_date
                """);
        if (periods.isEmpty()) throw new BusinessException("没有开放会计期间，不能调整税票勾稽凭证");
        LocalDate today = LocalDate.now();
        for (Map<String, Object> period : periods) {
            LocalDate start = date((Date) period.get("start_date"));
            LocalDate end = date((Date) period.get("end_date"));
            if (!invoiceDate.isBefore(start) && !invoiceDate.isAfter(end)) return invoiceDate;
            if (!today.isBefore(start) && !today.isAfter(end)) return today;
        }
        return date((Date) periods.get(0).get("start_date"));
    }

    private long createUsageVoucher(InvoiceLock invoice, String usage, YearMonth period) {
        if (invoice.taxAmount().compareTo(ZERO) == 0) return 0L;
        String summary = invoice.internalNo() + ("DEDUCTION".equals(usage) ? " 进项税额抵扣" : " 不抵扣进项税额转回成本");
        List<AccountingService.SystemVoucherLine> lines = new ArrayList<>();
        if ("DEDUCTION".equals(usage)) {
            lines.add(systemLine("22210101", summary, invoice.taxAmount(), ZERO, 0L));
        } else {
            List<TreasuryTaxRelation> included = treasuryTaxRelations(invoice.id()).stream()
                    .filter(TreasuryTaxRelation::includedInCost).toList();
            BigDecimal restored = ZERO;
            for (TreasuryTaxRelation relation : included) {
                if (relation.taxAmount().signum() > 0) {
                    lines.add(systemLine(relation.offsetAccountCode(), summary + " " + relation.documentNo(),
                            relation.taxAmount(), ZERO, invoice.partnerId()));
                    restored = restored.add(relation.taxAmount());
                }
            }
            BigDecimal remaining = money(invoice.taxAmount().subtract(restored));
            if (remaining.signum() > 0) lines.add(systemLine("1405", summary, remaining, ZERO, 0L));
        }
        lines.add(systemLine("22210103", summary, ZERO, invoice.taxAmount(), 0L));
        return accounting.createSourceVoucher("TAX_INVOICE_USAGE", invoice.id(), invoice.internalNo() + "/USE",
                voucherDate(period, invoice.invoiceDate()), summary, lines);
    }

    private long createTransferOutVoucher(InvoiceLock invoice, YearMonth period, String reason) {
        if (invoice.taxAmount().compareTo(ZERO) == 0) return 0L;
        String summary = invoice.internalNo() + " 进项税额转出：" + required(reason, "转出原因");
        return accounting.createSourceVoucher("TAX_INVOICE_TRANSFER", invoice.id(), invoice.internalNo() + "/OUT",
                voucherDate(period, invoice.invoiceDate()), summary, List.of(
                        systemLine("1405", summary, invoice.taxAmount(), ZERO, 0L),
                        systemLine("22210101", summary, ZERO, invoice.taxAmount(), 0L)));
    }

    private AccountingService.SystemVoucherLine systemLine(String code, String summary,
                                                            BigDecimal debit, BigDecimal credit,
                                                            long partnerId) {
        return new AccountingService.SystemVoucherLine(code, summary, debit, credit,
                partnerId, 0L, 0L);
    }

    private long inputRedDispositionVoucher(InvoiceLock invoice) {
        if (!"INPUT".equals(invoice.direction()) || !"RED".equals(invoice.color())) return 0L;
        Map<String, Object> blue = requiredRow("""
                select usage_status,usage_voucher_id,transfer_out_voucher_id
                from tax_invoice where id=?
                """, invoice.blueInvoiceId());
        String usage = String.valueOf(blue.get("usage_status"));
        if ("TRANSFERRED_OUT".equals(usage)) return number(blue, "transfer_out_voucher_id");
        if ("NON_DEDUCTION".equals(usage)) return number(blue, "usage_voucher_id");
        return 0L;
    }

    private int addAccountingRelation(Long invoiceId, long voucherId, BigDecimal tax,
                                      String matchingBasis, Actor actor) {
        Map<String, Object> voucher = requiredRow("""
                select voucher_no,voucher_date from accounting_voucher where id=?
                """, voucherId);
        return jdbc.update("""
                insert ignore into tax_invoice_relation
                (invoice_id,relation_layer,source_type,source_id,source_no,source_business_date,
                 tax_amount,total_amount,matching_basis,created_by,created_by_name)
                values (?,'ACCOUNTING','ACCOUNTING_VOUCHER',?,?,?,?,?,?,?,?)
                """, invoiceId, voucherId, voucher.get("voucher_no"), voucher.get("voucher_date"),
                tax, tax, matchingBasis, actor.id(), actor.name());
    }

    private List<PreparedLine> prepareLines(String direction, LocalDate invoiceDate,
                                            List<TaxInvoiceDtos.InvoiceLineSave> requested) {
        if (requested == null || requested.isEmpty()) throw new BusinessException("发票至少需要一条明细");
        List<PreparedLine> result = new ArrayList<>();
        int lineNo = 1;
        for (TaxInvoiceDtos.InvoiceLineSave line : requested) {
            TaxRate rate = taxRate(line.taxRateRuleId(), direction, invoiceDate);
            long productId = line.productId() == null ? 0L : line.productId();
            ProductSnapshot product = productSnapshot(productId, line);
            BigDecimal quantity = quantity(line.quantity());
            BigDecimal unitPrice = unitPrice(line.unitPriceExcludingTax());
            BigDecimal net = money(line.amountExcludingTax());
            BigDecimal tax = money(line.taxAmount());
            BigDecimal total = money(line.totalAmount());
            if (quantity.compareTo(BigDecimal.ZERO) > 0
                    && quantity.multiply(unitPrice).setScale(2, RoundingMode.HALF_UP).subtract(net).abs()
                    .compareTo(new BigDecimal("0.01")) > 0)
                throw new BusinessException("第" + lineNo + "行未税金额与数量、未税单价不一致");
            BigDecimal expectedTax = net.multiply(rate.rate()).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
            if (expectedTax.subtract(tax).abs().compareTo(new BigDecimal("0.01")) > 0)
                throw new BusinessException("第" + lineNo + "行税额与未税金额、税率不一致");
            if (net.add(tax).compareTo(total) != 0)
                throw new BusinessException("第" + lineNo + "行价税合计不一致");
            result.add(new PreparedLine(lineNo++, productId, product.sku(),
                    required(line.productName(), "项目名称"), product.spec(), text(line.unit()), quantity,
                    unitPrice, net, rate, text(line.taxClassificationCode()), tax, total));
        }
        return result;
    }

    private void insertLines(Long invoiceId, List<PreparedLine> lines) {
        for (PreparedLine line : lines) jdbc.update("""
                insert into tax_invoice_line
                (invoice_id,line_no,product_id,product_sku_snapshot,product_name_snapshot,
                 product_spec_snapshot,unit_snapshot,quantity,unit_price_excluding_tax,
                 amount_excluding_tax,tax_rate_rule_id,tax_rule_code_snapshot,tax_rule_name_snapshot,
                 tax_rate,calculation_method_snapshot,tax_classification_code,tax_amount,total_amount,
                 legal_basis_snapshot)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, invoiceId, line.lineNo(), line.productId(), line.sku(), line.name(), line.spec(),
                line.unit(), line.quantity(), line.unitPrice(), line.net(), line.rate().id(),
                line.rate().code(), line.rate().name(), line.rate().rate(), line.rate().method(),
                line.taxClassificationCode(), line.tax(), line.total(), line.rate().legalBasis());
    }

    private TaxRate taxRate(Long id, String direction, LocalDate date) {
        List<TaxRate> rows = jdbc.query("""
                select id,rule_code,rule_name,tax_rate,calculation_method,legal_basis
                from tax_rate_rule where id=? and status='ENABLED' and invoice_scope in ('BOTH',?)
                  and effective_from<=? and (effective_to is null or effective_to>=?)
                """, (rs, rowNum) -> new TaxRate(rs.getLong("id"), rs.getString("rule_code"),
                rs.getString("rule_name"), rs.getBigDecimal("tax_rate"),
                rs.getString("calculation_method"), rs.getString("legal_basis")),
                id, direction, Date.valueOf(date), Date.valueOf(date));
        if (rows.isEmpty()) throw new BusinessException("税率规则不存在、未生效或不适用于当前发票方向");
        return rows.get(0);
    }

    private PartnerSnapshot partnerSnapshot(long partnerId, String direction) {
        List<PartnerSnapshot> rows = jdbc.query("""
                select p.business_no,p.name,p.credit_code,p.type_mask,p.status,
                       coalesce(cp.status,'') customer_status,coalesce(sp.status,'') supplier_status
                from partner p
                left join partner_customer_profile cp on cp.partner_id=p.id
                left join partner_supplier_profile sp on sp.partner_id=p.id where p.id=?
                """, (rs, rowNum) -> {
            int mask = rs.getInt("type_mask");
            String roleStatus = "OUTPUT".equals(direction) ? rs.getString("customer_status") : rs.getString("supplier_status");
            int requiredMask = "OUTPUT".equals(direction) ? 1 : 2;
            if (!"ENABLED".equals(rs.getString("status")) || (mask & requiredMask) == 0 || !"ENABLED".equals(roleStatus))
                throw new BusinessException("往来单位未启用或不具备有效的" + (requiredMask == 1 ? "客户" : "供应商") + "身份");
            return new PartnerSnapshot(rs.getString("business_no"), rs.getString("name"), rs.getString("credit_code"));
        }, partnerId);
        if (rows.isEmpty()) throw new BusinessException("往来单位不存在");
        return rows.get(0);
    }

    private ProductSnapshot productSnapshot(long productId, TaxInvoiceDtos.InvoiceLineSave line) {
        if (productId <= 0) return new ProductSnapshot(text(line.productSku()), text(line.productSpec()));
        List<ProductSnapshot> rows = jdbc.query("""
                select sku,spec from product
                where id=? and status='ENABLED'
                """, (rs, rowNum) -> new ProductSnapshot(rs.getString("sku"), rs.getString("spec")), productId);
        if (rows.isEmpty()) throw new BusinessException("发票明细商品不存在或已停用");
        return rows.get(0);
    }

    private void validateRedDraft(String direction, long partnerId, long blueInvoiceId,
                                  LocalDate redDate, Long currentId) {
        if (blueInvoiceId <= 0) throw new BusinessException("红字发票必须关联原蓝字发票");
        InvoiceLock blue = lockInvoice(blueInvoiceId);
        if (!"BLUE".equals(blue.color()) || !"CONFIRMED".equals(blue.status()))
            throw new BusinessException("原发票必须是已确认蓝字发票");
        if (!direction.equals(blue.direction()) || partnerId != blue.partnerId())
            throw new BusinessException("红字发票方向和往来单位必须与原蓝字发票一致");
        if (redDate.isBefore(blue.invoiceDate())) throw new BusinessException("红字发票日期不能早于原蓝字发票日期");
        if (currentId != null && currentId.equals(blueInvoiceId)) throw new BusinessException("发票不能关联自身作为原蓝字发票");
    }

    private void validateRedConfirmation(InvoiceLock red) {
        InvoiceLock blue = lockInvoice(red.blueInvoiceId());
        BigDecimal redNet = confirmedRedSum(blue.id(), red.id(), "amount_excluding_tax").add(red.netAmount());
        BigDecimal redTax = confirmedRedSum(blue.id(), red.id(), "tax_amount").add(red.taxAmount());
        BigDecimal redTotal = confirmedRedSum(blue.id(), red.id(), "total_amount").add(red.totalAmount());
        if (redNet.compareTo(blue.netAmount()) > 0 || redTax.compareTo(blue.taxAmount()) > 0
                || redTotal.compareTo(blue.totalAmount()) > 0)
            throw new BusinessException("累计红冲金额、税额或价税合计超过原蓝字发票可红冲余额");
        boolean used = "CONFIRMED".equals(blue.bookingStatus())
                || !List.of("UNCONFIRMED", "NOT_APPLICABLE").contains(blue.usageStatus());
        if (used && !"CONFIRMED".equals(red.redConfirmationStatus()))
            throw new BusinessException("原蓝字发票已用途确认或入账确认，必须先完成红字确认单双方确认");
        if ("PENDING".equals(red.redConfirmationStatus())
                && red.redConfirmationExpiresAt() != null
                && !LocalDateTime.now().isBefore(red.redConfirmationExpiresAt()))
            throw new BusinessException("红字确认单已超过72小时，请重新发起");
    }

    private BigDecimal confirmedRedSum(long blueId, long excludingId, String column) {
        return money(jdbc.queryForObject("select coalesce(sum(" + column + "),0) from tax_invoice " +
                "where blue_invoice_id=? and id<>? and status='CONFIRMED'", BigDecimal.class, blueId, excludingId));
    }

    private void requireConfirmed(InvoiceLock invoice) {
        if (!"CONFIRMED".equals(invoice.status())) throw new BusinessException("只有已确认发票可以执行该操作");
    }

    private void requireInputBlueConfirmed(InvoiceLock invoice) {
        requireConfirmed(invoice);
        if (!"INPUT".equals(invoice.direction()) || !"BLUE".equals(invoice.color()))
            throw new BusinessException("该操作只适用于已确认的蓝字进项发票");
    }

    private void validateSourceDirection(String direction, String color, String sourceType, String status) {
        List<String> allowed = "INPUT".equals(direction)
                ? ("RED".equals(color) ? List.of("PURCHASE_RETURN", "PAYABLE")
                : List.of("PURCHASE_ORDER", "INBOUND", "PAYABLE", "SUPPLIER_PAYMENT",
                    "TREASURY_DOCUMENT", "TREASURY_ACCRUAL", "TREASURY_JOURNAL"))
                : ("RED".equals(color) ? List.of("SALES_RETURN", "RECEIVABLE")
                : List.of("SALES_ORDER", "OUTBOUND", "RECEIVABLE", "PAYMENT",
                    "TREASURY_DOCUMENT", "TREASURY_ACCRUAL", "TREASURY_JOURNAL"));
        if (!allowed.contains(sourceType)) throw new BusinessException("来源单据不适用于当前发票方向或蓝红字类型");
        if ("PAYABLE".equals(sourceType)) {
            List<String> expected = "RED".equals(color)
                    ? List.of("DECREASE:PURCHASE_RETURN:CONFIRMED")
                    : List.of("INCREASE:INBOUND", "INCREASE:PURCHASE_ORDER");
            if (!expected.contains(status)) throw new BusinessException("只有采购业务形成的应付增加或采购退货冲减可以勾稽发票");
        }
        if ("RECEIVABLE".equals(sourceType)) {
            List<String> expected = "RED".equals(color)
                    ? List.of("CREDIT:SALES_RETURN:CONFIRMED")
                    : List.of("DEBIT:OUTBOUND", "DEBIT:SALES_ORDER");
            if (!expected.contains(status)) throw new BusinessException("只有销售业务形成的应收增加或销售退货冲减可以勾稽发票");
        }
        if (Set.of("TREASURY_DOCUMENT", "TREASURY_ACCRUAL").contains(sourceType)) {
            Set<String> types = "INPUT".equals(direction)
                    ? Set.of("CORPORATE_EXPENSE", "OTHER_EXPENSE", "OTHER_PAYABLE", "PURCHASE_LANDED_COST")
                    : Set.of("OTHER_INCOME", "OTHER_RECEIVABLE");
            String[] parts = status.split(":", 2);
            if (parts.length != 2 || !types.contains(parts[0])
                    || !Set.of("CONFIRMED", "PARTIALLY_SETTLED", "SETTLED").contains(parts[1]))
                throw new BusinessException("费用或其他往来源单的类型、方向或状态与发票不一致");
            return;
        }
        if ("TREASURY_JOURNAL".equals(sourceType)) {
            String expected = "INPUT".equals(direction) ? "DEBIT:ACTIVE" : "CREDIT:ACTIVE";
            if (!expected.equals(status)) throw new BusinessException("出纳日记账收支方向或状态与发票不一致");
            return;
        }
        if (List.of("CANCELLED", "REVERSED", "DRAFT").contains(status))
            throw new BusinessException("来源单据尚未确认或已经撤销，不能用于发票勾稽");
    }

    private SourceInfo sourceInfo(String type, Long id) {
        if (id == null || id <= 0) throw new BusinessException("来源单据无效");
        String sql = switch (type) {
            case "PURCHASE_ORDER" -> "select id,order_no no,business_date,supplier_id partner_id,total_amount amount,status from purchase_order where id=?";
            case "INBOUND" -> "select id,order_no no,business_date,supplier_id partner_id,total_amount amount,status from inbound_order where id=?";
            case "PURCHASE_RETURN" -> "select id,order_no no,business_date,supplier_id partner_id,total_amount amount,status from purchase_return_order where id=?";
            case "PAYABLE" -> "select id,movement_no no,date(occurred_at) business_date,supplier_id partner_id,amount,case when ref_type='PURCHASE_RETURN' then concat(direction,':',ref_type,':',coalesce((select status from purchase_return_order where id=ref_id),'')) else concat(direction,':',ref_type) end status from payable_movement where id=?";
            case "SUPPLIER_PAYMENT" -> "select id,payment_no no,paid_date business_date,supplier_id partner_id,amount,verification_status status from supplier_payment where id=?";
            case "SALES_ORDER" -> "select id,order_no no,business_date,customer_id partner_id,total_amount amount,status from sales_order where id=?";
            case "OUTBOUND" -> "select id,order_no no,business_date,customer_id partner_id,total_amount amount,status from outbound_order where id=?";
            case "SALES_RETURN" -> "select id,return_no no,business_date,customer_id partner_id,total_amount amount,status from sales_return_order where id=?";
            case "RECEIVABLE" -> "select id,movement_no no,date(occurred_at) business_date,customer_id partner_id,amount,case when ref_type='SALES_RETURN' then concat(direction,':',ref_type,':',coalesce((select status from sales_return_order where id=ref_id),'')) else concat(direction,':',ref_type) end status from receivable_movement where id=?";
            case "PAYMENT" -> "select id,payment_no no,received_date business_date,customer_id partner_id,amount,verification_status status from payment where id=?";
            case "TREASURY_DOCUMENT", "TREASURY_ACCRUAL" -> "select id,document_no no,business_date,partner_id,total_amount amount,concat(document_type,':',status) status from treasury_document where id=?";
            case "TREASURY_JOURNAL" -> "select id,journal_no no,transaction_date business_date,partner_id,amount,concat(direction,':',status) status from treasury_cash_journal where id=?";
            default -> throw new BusinessException("不支持的发票勾稽来源类型");
        };
        List<SourceInfo> rows = jdbc.query(sql, (rs, rowNum) -> new SourceInfo(
                rs.getLong("id"), rs.getString("no"), date(rs.getDate("business_date")),
                rs.getLong("partner_id"), money(rs.getBigDecimal("amount")), rs.getString("status")), id);
        if (rows.isEmpty()) throw new BusinessException("来源单据不存在");
        return rows.get(0);
    }

    private List<SourceRow> sourceRows(String sourceType, String sql, Long partnerId) {
        return jdbc.query(sql, (rs, rowNum) -> new SourceRow(sourceType, rs.getLong("id"),
                rs.getString("source_no"), date(rs.getDate("business_date")), rs.getLong("partner_id"),
                rs.getString("partner_name"), money(rs.getBigDecimal("source_amount")), rs.getString("status")), partnerId);
    }

    private String relationLayer(String sourceType) {
        return switch (sourceType) {
            case "PURCHASE_ORDER", "SALES_ORDER" -> "CONTRACT";
            case "INBOUND", "PURCHASE_RETURN", "OUTBOUND", "SALES_RETURN" -> "GOODS";
            case "TREASURY_DOCUMENT" -> "GOODS";
            case "PAYABLE", "RECEIVABLE", "TREASURY_ACCRUAL" -> "SETTLEMENT";
            case "SUPPLIER_PAYMENT", "PAYMENT", "TREASURY_JOURNAL" -> "FUNDS";
            case "ACCOUNTING_VOUCHER" -> "ACCOUNTING";
            default -> throw new BusinessException("不支持的发票勾稽来源类型");
        };
    }

    private BigDecimal sourceMatched(String sourceType, long sourceId, String direction, String color) {
        return money(jdbc.queryForObject("""
                select coalesce(sum(r.total_amount),0)
                from tax_invoice_relation r join tax_invoice i on i.id=r.invoice_id
                where r.source_type=? and r.source_id=? and r.status='ACTIVE'
                  and i.status<>'VOIDED' and i.direction=? and i.invoice_color=?
                """, BigDecimal.class, sourceType, sourceId, direction, color));
    }

    private BigDecimal layerMatched(long invoiceId, String layer) {
        return money(jdbc.queryForObject("""
                select coalesce(sum(total_amount),0) from tax_invoice_relation
                where invoice_id=? and relation_layer=? and status='ACTIVE'
                """, BigDecimal.class, invoiceId, layer));
    }

    private BigDecimal layerMatchedTax(long invoiceId, String layer) {
        return money(jdbc.queryForObject("""
                select coalesce(sum(tax_amount),0) from tax_invoice_relation
                where invoice_id=? and relation_layer=? and status='ACTIVE'
                """, BigDecimal.class, invoiceId, layer));
    }

    private String invoiceRowSelect() {
        return """
                select i.*,
                  coalesce((select sum(r.total_amount) from tax_invoice r
                    where r.blue_invoice_id=i.id and r.status='CONFIRMED'),0) red_total_amount,
                  greatest(i.total_amount-coalesce((select sum(r.total_amount) from tax_invoice r
                    where r.blue_invoice_id=i.id and r.status='CONFIRMED'),0),0) remaining_red_amount,
                  coalesce((select sum(x.total_amount) from tax_invoice_relation x
                    where x.invoice_id=i.id and x.relation_layer='GOODS' and x.status='ACTIVE'),0) goods_matched_amount,
                  coalesce((select sum(x.total_amount) from tax_invoice_relation x
                    where x.invoice_id=i.id and x.relation_layer='SETTLEMENT' and x.status='ACTIVE'),0) settlement_matched_amount,
                  coalesce((select sum(x.total_amount) from tax_invoice_relation x
                    where x.invoice_id=i.id and x.relation_layer='FUNDS' and x.status='ACTIVE'),0) funds_matched_amount,
                  coalesce((select sum(x.tax_amount) from tax_invoice_relation x
                    where x.invoice_id=i.id and x.relation_layer='ACCOUNTING' and x.status='ACTIVE'),0) accounting_matched_tax
                from tax_invoice i
                """;
    }

    private org.springframework.jdbc.core.RowMapper<TaxInvoiceDtos.InvoiceRow> invoiceRowMapper() {
        return (rs, rowNum) -> {
            BigDecimal goods = money(rs.getBigDecimal("goods_matched_amount"));
            BigDecimal settlement = money(rs.getBigDecimal("settlement_matched_amount"));
            BigDecimal funds = money(rs.getBigDecimal("funds_matched_amount"));
            BigDecimal accountingTax = money(rs.getBigDecimal("accounting_matched_tax"));
            BigDecimal total = money(rs.getBigDecimal("total_amount"));
            BigDecimal tax = money(rs.getBigDecimal("tax_amount"));
            String reconciliation = reconciliation(goods, settlement, accountingTax, total, tax);
            return new TaxInvoiceDtos.InvoiceRow(rs.getLong("id"), rs.getString("internal_no"),
                    rs.getString("direction"), rs.getString("invoice_color"), rs.getString("invoice_type"),
                    rs.getString("invoice_code"), rs.getString("invoice_number"), rs.getLong("blue_invoice_id"),
                    rs.getLong("partner_id"), rs.getString("partner_name_snapshot"),
                    rs.getDate("invoice_date").toLocalDate(), rs.getDate("received_or_issued_date").toLocalDate(),
                    rs.getBigDecimal("amount_excluding_tax"), tax, total, rs.getString("status"),
                    rs.getString("verification_status"), rs.getString("booking_status"),
                    rs.getString("usage_status"), rs.getString("usage_period"),
                    rs.getString("red_confirmation_status"), rs.getLong("accounting_voucher_id"),
                    rs.getLong("usage_voucher_id"), rs.getLong("transfer_out_voucher_id"),
                    money(rs.getBigDecimal("red_total_amount")), money(rs.getBigDecimal("remaining_red_amount")),
                    goods, settlement, funds, accountingTax, reconciliation, rs.getInt("version_no"),
                    dateTime(rs.getTimestamp("confirmed_at")), rs.getTimestamp("created_at").toLocalDateTime());
        };
    }

    private String reconciliation(BigDecimal goods, BigDecimal settlement, BigDecimal accountingTax,
                                  BigDecimal total, BigDecimal tax) {
        boolean goodsOk = goods.compareTo(total) >= 0;
        boolean settlementOk = settlement.compareTo(total) >= 0;
        boolean accountingOk = tax.compareTo(ZERO) == 0 || accountingTax.compareTo(tax) >= 0;
        if (goodsOk && settlementOk && accountingOk) return "COMPLETE";
        if (goods.compareTo(ZERO) > 0 || settlement.compareTo(ZERO) > 0 || accountingTax.compareTo(ZERO) > 0)
            return "PARTIAL";
        return "UNMATCHED";
    }

    private boolean hasException(TaxInvoiceDtos.InvoiceRow row) {
        if (!"COMPLETE".equals(row.reconciliationStatus())) return true;
        if (!"VERIFIED".equals(row.verificationStatus())) return true;
        if ("INPUT".equals(row.direction()) && "BLUE".equals(row.invoiceColor())
                && "UNCONFIRMED".equals(row.usageStatus())) return true;
        return "RED".equals(row.invoiceColor()) && "PENDING".equals(row.redConfirmationStatus());
    }

    private org.springframework.jdbc.core.RowMapper<TaxInvoiceDtos.RelationView> relationMapper() {
        return (rs, rowNum) -> new TaxInvoiceDtos.RelationView(rs.getLong("id"), rs.getLong("invoice_id"),
                rs.getString("relation_layer"), rs.getString("source_type"), rs.getLong("source_id"),
                rs.getString("source_no"), date(rs.getDate("source_business_date")), rs.getLong("partner_id"),
                rs.getBigDecimal("amount_excluding_tax"), rs.getBigDecimal("tax_amount"),
                rs.getBigDecimal("total_amount"), rs.getString("status"), rs.getString("matching_basis"),
                rs.getString("reversed_reason"), rs.getString("created_by_name"),
                rs.getString("reversed_by_name"), dateTime(rs.getTimestamp("reversed_at")),
                rs.getTimestamp("created_at").toLocalDateTime());
    }

    private InvoiceLock lockInvoice(Long invoiceId) {
        List<InvoiceLock> rows = jdbc.query("select * from tax_invoice where id=? for update", (rs, rowNum) ->
                new InvoiceLock(rs.getLong("id"), rs.getString("internal_no"), rs.getString("direction"),
                        rs.getString("invoice_color"), rs.getLong("blue_invoice_id"), rs.getLong("partner_id"),
                        rs.getDate("invoice_date").toLocalDate(), rs.getBigDecimal("amount_excluding_tax"),
                        rs.getBigDecimal("tax_amount"), rs.getBigDecimal("total_amount"), rs.getString("status"),
                        rs.getString("verification_status"), rs.getString("booking_status"),
                        rs.getString("usage_status"), rs.getString("red_confirmation_status"),
                        dateTime(rs.getTimestamp("red_confirmation_expires_at")),
                        rs.getLong("accounting_voucher_id"), rs.getInt("version_no")), invoiceId);
        if (rows.isEmpty()) throw new BusinessException("发票不存在");
        return rows.get(0);
    }

    private void event(Long invoiceId, String action, String from, String to, String reason, Actor actor) {
        jdbc.update("""
                insert into tax_invoice_event
                (invoice_id,action,from_status,to_status,reason,operator_id,operator_name)
                values (?,?,?,?,?,?,?)
                """, invoiceId, action, text(from), text(to), text(reason), actor.id(), actor.name());
    }

    private void useEvent(Long invoiceId, String action, String from, String to, String period,
                          BigDecimal tax, Long voucherId, String evidence, String reason, Actor actor) {
        jdbc.update("""
                insert into tax_invoice_use_event
                (invoice_id,action,from_usage_status,to_usage_status,tax_period,tax_amount,
                 accounting_voucher_id,evidence_reference,reason,operator_id,operator_name)
                values (?,?,?,?,?,?,?,?,?,?,?)
                """, invoiceId, action, text(from), text(to), text(period), money(tax),
                voucherId == null ? 0L : voucherId, required(evidence, "税务凭据"), text(reason),
                actor.id(), actor.name());
    }

    private BigDecimal signedSum(long entityId, String direction, String column) {
        BigDecimal value = jdbc.queryForObject("select coalesce(sum(case when invoice_color='RED' then -" + column +
                " else " + column + " end),0) from tax_invoice where entity_id=? and direction=? and status='CONFIRMED'",
                BigDecimal.class, entityId, direction);
        return money(value);
    }

    private long entityId() {
        Long id = jdbc.queryForObject("select id from accounting_entity where status='ENABLED' order by id limit 1", Long.class);
        if (id == null) throw new BusinessException("尚未建立会计主体");
        return id;
    }

    private Actor actor() {
        SecurityContext.Principal principal = SecurityContext.get();
        if (principal == null) return new Actor(0L, "系统自动");
        String name = StringUtils.hasText(principal.employeeName()) ? principal.employeeName() : principal.username();
        return new Actor(principal.employeeId() == null ? 0L : principal.employeeId(), name);
    }

    private Long insert(String sql, Object... args) {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
            return statement;
        }, holder);
        if (holder.getKey() == null) throw new BusinessException("保存发票数据失败");
        return holder.getKey().longValue();
    }

    private Map<String, Object> requiredRow(String sql, Object... args) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, args);
        if (rows.isEmpty()) throw new BusinessException("业务记录不存在");
        return rows.get(0);
    }

    private long scalarLong(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0L : value;
    }

    private BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }

    private String signedMoney(BigDecimal value) {
        BigDecimal normalized = money(value);
        return (normalized.signum() > 0 ? "+" : "") + normalized.toPlainString();
    }

    private BigDecimal quantity(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(6, RoundingMode.HALF_UP);
    }

    private BigDecimal unitPrice(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(6, RoundingMode.HALF_UP);
    }

    private BigDecimal sum(List<PreparedLine> lines, java.util.function.Function<PreparedLine, BigDecimal> getter) {
        return lines.stream().map(getter).reduce(ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
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

    private YearMonth taxPeriod(String value) {
        try { return YearMonth.parse(required(value, "税款所属期")); }
        catch (Exception exception) { throw new BusinessException("税款所属期必须使用YYYY-MM格式"); }
    }

    private LocalDate voucherDate(YearMonth period, LocalDate invoiceDate) {
        return YearMonth.from(invoiceDate).equals(period) ? invoiceDate : period.atEndOfMonth();
    }

    private void validateFileHash(String hash) {
        if (StringUtils.hasText(hash) && !hash.trim().matches("[0-9a-fA-F]{64}"))
            throw new BusinessException("发票原文件摘要必须为64位SHA-256");
    }

    private String nextInternalNo() {
        return "FP" + NO_TIME.format(LocalDateTime.now())
                + String.format("%03d", Math.floorMod(NO_COUNTER.incrementAndGet(), 1000));
    }

    private String directionName(String direction) { return "INPUT".equals(direction) ? "进项" : "销项"; }
    private String colorName(String color) { return "RED".equals(color) ? "红字" : "蓝字"; }
    private String initialUsageStatus(String direction, String color) {
        return "INPUT".equals(direction) && "BLUE".equals(color) ? "UNCONFIRMED" : "NOT_APPLICABLE";
    }
    private String text(String value) { return value == null ? "" : value.trim(); }
    private Date sqlDate(LocalDate value) { return value == null ? null : Date.valueOf(value); }
    private LocalDate date(Date value) { return value == null ? null : value.toLocalDate(); }
    private LocalDateTime dateTime(Object value) {
        if (value == null) return null;
        if (value instanceof Timestamp timestamp) return timestamp.toLocalDateTime();
        if (value instanceof LocalDateTime dateTime) return dateTime;
        return null;
    }
    private String string(Map<String, Object> row, String key) { return row.get(key) == null ? "" : String.valueOf(row.get(key)); }
    private long number(Map<String, Object> row, String key) { return row.get(key) instanceof Number n ? n.longValue() : 0L; }

    private record Actor(long id, String name) {}
    private record PartnerSnapshot(String businessNo, String name, String taxNo) {}
    private record ProductSnapshot(String sku, String spec) {}
    private record TaxRate(long id, String code, String name, BigDecimal rate, String method, String legalBasis) {}
    private record PreparedLine(int lineNo, long productId, String sku, String name, String spec,
                                String unit, BigDecimal quantity, BigDecimal unitPrice, BigDecimal net,
                                TaxRate rate, String taxClassificationCode, BigDecimal tax, BigDecimal total) {}
    private record SourceInfo(long id, String no, LocalDate businessDate, long partnerId,
                              BigDecimal amount, String status) {}
    private record SourceRow(String sourceType, long id, String sourceNo, LocalDate businessDate,
                             long partnerId, String partnerName, BigDecimal amount, String status) {}
    private record SourceTaxSnapshot(BigDecimal netAmount, BigDecimal taxAmount,
                                     BigDecimal roundingDifference, boolean available) {}
    private record TreasuryTaxRelation(long relationId, BigDecimal taxAmount,
                                       String documentNo, String offsetAccountCode,
                                       boolean includedInCost) {}
    private record InvoiceLock(long id, String internalNo, String direction, String color,
                               long blueInvoiceId, long partnerId, LocalDate invoiceDate,
                               BigDecimal netAmount, BigDecimal taxAmount, BigDecimal totalAmount,
                               String status, String verificationStatus, String bookingStatus,
                               String usageStatus, String redConfirmationStatus,
                               LocalDateTime redConfirmationExpiresAt,
                               long accountingVoucherId, int versionNo) {}
}
