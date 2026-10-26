package com.soyia.copperv2.service;

import com.soyia.copperv2.dto.AccountingDtos;
import com.soyia.copperv2.dto.TreasuryDtos;
import com.soyia.copperv2.dto.RiskExceptionDtos;
import com.soyia.copperv2.security.BusinessDataScopeService;
import com.soyia.copperv2.security.SecurityContext;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class TreasuryService {
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    private static final Set<String> DOCUMENT_TYPES = Set.of(
            "CORPORATE_EXPENSE", "OTHER_EXPENSE", "OTHER_INCOME", "OTHER_RECEIVABLE", "OTHER_PAYABLE",
            "PURCHASE_LANDED_COST");
    private static final Set<String> OUTGOING_TYPES = Set.of(
            "CORPORATE_EXPENSE", "OTHER_EXPENSE", "OTHER_PAYABLE", "PURCHASE_LANDED_COST", "OPENING_PAYABLE");
    private static final Set<String> INCOMING_TYPES = Set.of("OTHER_INCOME", "OTHER_RECEIVABLE", "OPENING_RECEIVABLE");
    private static final Set<String> INPUT_TAX_TREATMENTS = Set.of("DEDUCTIBLE", "INCLUDED_IN_COST");
    private static final Set<String> CASH_FLOW_CATEGORIES = Set.of(
            "SALES_COLLECTION", "OTHER_OPERATING_IN", "PURCHASE_PAYMENT", "EMPLOYEE_PAYMENT",
            "TAX_PAYMENT", "BANK_FEE", "OTHER_OPERATING_OUT", "INVESTING_IN", "INVESTING_OUT",
            "FINANCING_IN", "FINANCING_OUT", "INTERNAL_TRANSFER");
    private static final DateTimeFormatter NO_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");
    private static final AtomicInteger NO_COUNTER = new AtomicInteger();

    private final JdbcTemplate jdbc;
    private final AccountingService accountingService;
    private final BusinessDataScopeService dataScopeService;
    private final RiskExceptionService riskExceptionService;

    public TreasuryService(JdbcTemplate jdbc, AccountingService accountingService,
                           BusinessDataScopeService dataScopeService,
                           RiskExceptionService riskExceptionService) {
        this.jdbc = jdbc;
        this.accountingService = accountingService;
        this.dataScopeService = dataScopeService;
        this.riskExceptionService = riskExceptionService;
    }

    public TreasuryDtos.Dashboard dashboard() {
        if (!dataScopeService.hasFullDocumentScope()) {
            return new TreasuryDtos.Dashboard(0L, ZERO, 0L, ZERO,
                    0L, ZERO, 0L, ZERO, true);
        }
        Map<String, Object> row = requiredRow("""
                select
                  (select count(*) from treasury_document where status='DRAFT') draft_count,
                  (select coalesce(sum(total_amount),0) from treasury_document where status='DRAFT') draft_amount,
                  (select count(*) from treasury_document where status in ('CONFIRMED','PARTIALLY_SETTLED')) outstanding_count,
                  (select coalesce(sum(total_amount-settled_amount),0) from treasury_document where status in ('CONFIRMED','PARTIALLY_SETTLED')) outstanding_amount,
                  (select count(*) from treasury_payment_request where status in ('DRAFT','APPROVED','PARTIALLY_PAID')) request_count,
                  (select coalesce(sum(requested_amount-paid_amount),0) from treasury_payment_request where status in ('DRAFT','APPROVED','PARTIALLY_PAID')) request_amount,
                  (select count(*) from treasury_cash_journal j where j.settlement_method='BANK' and j.status='ACTIVE'
                    and coalesce((select sum(m.matched_amount) from bank_reconciliation_match m
                      where m.source_type='TREASURY_JOURNAL' and m.source_id=j.id and m.status='ACTIVE'),0)+
                        coalesce((select sum(p.amount) from bank_reconciliation_pending_system p
                      where p.source_type='TREASURY_JOURNAL' and p.source_id=j.id and p.status='OPEN'),0)<j.amount) unreconciled_count,
                  (select coalesce(sum(j.amount-coalesce((select sum(m.matched_amount) from bank_reconciliation_match m
                      where m.source_type='TREASURY_JOURNAL' and m.source_id=j.id and m.status='ACTIVE'),0)-
                        coalesce((select sum(p.amount) from bank_reconciliation_pending_system p
                      where p.source_type='TREASURY_JOURNAL' and p.source_id=j.id and p.status='OPEN'),0)),0)
                    from treasury_cash_journal j where j.settlement_method='BANK' and j.status='ACTIVE') unreconciled_amount
                """);
        return new TreasuryDtos.Dashboard(number(row, "draft_count"), money(row.get("draft_amount")),
                number(row, "outstanding_count"), money(row.get("outstanding_amount")),
                number(row, "request_count"), money(row.get("request_amount")),
                number(row, "unreconciled_count"), money(row.get("unreconciled_amount")), false);
    }

    public List<TreasuryDtos.DocumentRow> documents(String status, String documentType, Long partnerId) {
        StringBuilder sql = new StringBuilder(documentSelect()).append(" where 1=1");
        List<Object> args = new ArrayList<>();
        if (StringUtils.hasText(status)) { sql.append(" and d.status=?"); args.add(status.trim().toUpperCase()); }
        if (StringUtils.hasText(documentType)) { sql.append(" and d.document_type=?"); args.add(documentType.trim().toUpperCase()); }
        if (partnerId != null && partnerId > 0) { sql.append(" and d.partner_id=?"); args.add(partnerId); }
        sql.append(" order by d.business_date desc,d.id desc limit 1000");
        return dataScopeService.filterDocumentList("TREASURY_DOCUMENT",
                jdbc.query(sql.toString(), documentMapper(), args.toArray()));
    }

    public TreasuryDtos.DocumentDetail document(Long id) {
        dataScopeService.assertDocumentAllowed("TREASURY_DOCUMENT", id);
        List<TreasuryDtos.DocumentRow> rows = jdbc.query(documentSelect() + " where d.id=?", documentMapper(), id);
        if (rows.isEmpty()) throw new BusinessException("费用或其他往来源单不存在");
        List<TreasuryDtos.EventRow> events = jdbc.query("""
                select id,action,from_status,to_status,reason,operator_name,created_at
                from treasury_document_event where document_id=? order by id
                """, eventMapper(), id);
        return new TreasuryDtos.DocumentDetail(rows.get(0), events, funds(id), paymentRequests(null, id));
    }

    @Transactional
    public Long createDocument(TreasuryDtos.DocumentCreate request) {
        String type = enumValue(request.documentType(), DOCUMENT_TYPES, "业务类型");
        if (request.partnerId() == null || request.partnerId() <= 0) throw new BusinessException("请选择往来单位");
        dataScopeService.assertMasterAllowed("PARTNER", request.partnerId());
        PartnerSnapshot partner = partner(request.partnerId());
        requirePartnerRole(request.partnerId(), OUTGOING_TYPES.contains(type) ? "SUPPLIER" : "CUSTOMER");
        AccountSnapshot offset = offsetAccount(request.offsetAccountId(), type, request.businessDate());
        if (request.dueDate().isBefore(request.businessDate())) throw new BusinessException("到期日不能早于业务日期");
        ensureOpenPeriod(request.businessDate());
        BigDecimal rate = rate(request.taxRate());
        String inputTaxTreatment = "DEDUCTIBLE";
        if ("PURCHASE_LANDED_COST".equals(type)) {
            if (!StringUtils.hasText(request.inputTaxTreatment()))
                throw new BusinessException("请选择采购附加费用的进项税处理方式");
            inputTaxTreatment = enumValue(request.inputTaxTreatment(), INPUT_TAX_TREATMENTS, "进项税处理方式");
        }
        TaxPriceContract.Document tax = TaxPriceContract.calculate(Boolean.TRUE.equals(request.priceIncludesTax()),
                List.of(new TaxPriceContract.Input(BigDecimal.ONE, positive(request.amount(), "金额"), rate)),
                BigDecimal.ZERO, BigDecimal.ZERO, "ERP05_TREASURY_SOURCE");
        Actor actor = actor();
        String no = nextNo("FY");
        Long id = insert("""
                insert into treasury_document
                (document_no,document_type,partner_id,partner_name_snapshot,business_date,due_date,
                 basis_no,summary,offset_account_id,offset_account_code_snapshot,offset_account_name_snapshot,
                 price_includes_tax,tax_rate,amount_excluding_tax,tax_amount,total_amount,
                 tax_rounding_difference,tax_rule_snapshot,input_tax_treatment,memo,created_by,created_by_name)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, no, type, request.partnerId(), partner.name(), Date.valueOf(request.businessDate()),
                Date.valueOf(request.dueDate()), required(request.basisNo(), "业务依据"),
                required(request.summary(), "业务摘要"), offset.id(), offset.code(), offset.name(),
                Boolean.TRUE.equals(request.priceIncludesTax()) ? 1 : 0, rate, tax.amountExcludingTax(),
                tax.taxAmount(), tax.totalAmount(), tax.roundingDifference(), tax.ruleSnapshot(), inputTaxTreatment,
                text(request.memo()), actor.id(), actor.name());
        documentEvent(id, "CREATE", "", "DRAFT", request.summary(), actor);
        dataScopeService.stampDocumentOwner("TREASURY_DOCUMENT", id);
        return id;
    }

    @Transactional
    public Long confirmDocument(Long id) {
        DocumentLock document = lockDocument(id);
        if (List.of("CONFIRMED", "PARTIALLY_SETTLED", "SETTLED").contains(document.status())) return id;
        if (!"DRAFT".equals(document.status())) throw new BusinessException("只有草稿源单可以确认");
        requirePartnerRole(document.partnerId(), OUTGOING_TYPES.contains(document.documentType()) ? "SUPPLIER" : "CUSTOMER");
        dataScopeService.assertDocumentAllowed("TREASURY_DOCUMENT", id);
        ensureOpenPeriod(document.businessDate());
        String summary = document.documentNo() + " " + document.summary();
        List<AccountingService.SystemVoucherLine> lines = new ArrayList<>();
        if (OUTGOING_TYPES.contains(document.documentType())) {
            lines.add(systemLine(document.offsetAccountCode(), summary, document.totalAmount(), ZERO, document.partnerId()));
            lines.add(systemLine("2241", summary, ZERO, document.totalAmount(), document.partnerId()));
        } else {
            lines.add(systemLine("1231", summary, document.totalAmount(), ZERO, document.partnerId()));
            lines.add(systemLine(document.offsetAccountCode(), summary, ZERO, document.totalAmount(), document.partnerId()));
        }
        Long voucherId = accountingService.createSourceVoucher("TREASURY_DOCUMENT", id, document.documentNo(),
                document.businessDate(), summary, lines);
        Actor actor = actor();
        int updated = jdbc.update("""
                update treasury_document set status='CONFIRMED',accounting_voucher_id=?,confirmed_by=?,
                  confirmed_by_name=?,confirmed_at=now(),version_no=version_no+1 where id=? and status='DRAFT'
                """, voucherId, actor.id(), actor.name(), id);
        if (updated != 1) throw new BusinessException("源单状态已变化，请刷新后重试");
        documentEvent(id, "CONFIRM", "DRAFT", "CONFIRMED", "负责人确认；资金、发票和凭证保持分层", actor);
        return id;
    }

    @Transactional
    public Long cancelDocument(Long id, TreasuryDtos.Action request) {
        DocumentLock document = lockDocument(id);
        if ("CANCELLED".equals(document.status())) return id;
        if (!"DRAFT".equals(document.status())) throw new BusinessException("只有草稿源单可以作废");
        dataScopeService.assertDocumentAllowed("TREASURY_DOCUMENT", id);
        Actor actor = actor();
        jdbc.update("""
                update treasury_document set status='CANCELLED',cancel_reason=?,cancelled_by=?,
                  cancelled_by_name=?,cancelled_at=now(),version_no=version_no+1 where id=? and status='DRAFT'
                """, required(request.reason(), "作废原因"), actor.id(), actor.name(), id);
        documentEvent(id, "CANCEL", "DRAFT", "CANCELLED", request.reason(), actor);
        return id;
    }

    @Transactional
    public Long reverseDocument(Long id, TreasuryDtos.Action request) {
        DocumentLock document = lockDocument(id);
        if (Set.of("OPENING_RECEIVABLE", "OPENING_PAYABLE").contains(document.documentType()))
            throw new BusinessException("期初往来源单不能单独冲正；切换前请撤销整批迁移，切换后请使用调整单保留勾稽");
        if ("REVERSED".equals(document.status())) return id;
        if (!List.of("CONFIRMED", "PARTIALLY_SETTLED", "SETTLED").contains(document.status()))
            throw new BusinessException("只有已确认源单可以冲正");
        dataScopeService.assertDocumentAllowed("TREASURY_DOCUMENT", id);
        riskExceptionService.authorizeOrConsume(
                riskExceptionService.resolveTrigger("TREASURY_DOCUMENT", id, "REVERSE"),
                new RiskExceptionDtos.InlineAuthorization(request.reason(), request.approvalId()));
        if (document.settledAmount().signum() > 0)
            throw new BusinessException("源单仍有有效收付款，请先逐笔冲正资金后再冲正源单");
        if (scalarLong("select count(*) from treasury_payment_request where document_id=? and status in ('APPROVED','PARTIALLY_PAID','PAID')", id) > 0)
            throw new BusinessException("源单存在已批准或已支付付款申请，不能直接冲正");
        if (scalarLong("select count(*) from landed_cost_allocation where treasury_document_id=? and status='CONFIRMED'", id) > 0)
            throw new BusinessException("采购附加费用已经计入批次成本，请先冲正费用分摊单");
        LocalDate date = request.businessDate() == null ? LocalDate.now() : request.businessDate();
        ensureOpenPeriod(date);
        accountingService.disposeSourceVoucher(document.accountingVoucherId(), date,
                required(request.reason(), "冲正原因"));
        Actor actor = actor();
        jdbc.update("""
                update treasury_document set status='REVERSED',reversal_reason=?,reversed_by=?,
                  reversed_by_name=?,reversed_at=now(),version_no=version_no+1 where id=?
                """, request.reason().trim(), actor.id(), actor.name(), id);
        documentEvent(id, "REVERSE", document.status(), "REVERSED", request.reason(), actor);
        return id;
    }

    public List<TreasuryDtos.PaymentRequestRow> paymentRequests(String status, Long documentId) {
        StringBuilder sql = new StringBuilder(requestSelect()).append(" where 1=1");
        List<Object> args = new ArrayList<>();
        if (StringUtils.hasText(status)) { sql.append(" and r.status=?"); args.add(status.trim().toUpperCase()); }
        if (documentId != null && documentId > 0) { sql.append(" and r.document_id=?"); args.add(documentId); }
        sql.append(" order by r.request_date desc,r.id desc limit 1000");
        List<TreasuryDtos.PaymentRequestRow> rows = jdbc.query(sql.toString(), requestMapper(), args.toArray());
        if (dataScopeService.hasFullDocumentScope()) return rows;
        return dataScopeService.filterDerivedDocumentList(rows.stream()
                        .map(row -> new ScopedPaymentRequest("TREASURY_DOCUMENT", row.documentId(), row)).toList())
                .stream().map(ScopedPaymentRequest::row).toList();
    }

    @Transactional
    public Long createPaymentRequest(TreasuryDtos.PaymentRequestCreate request) {
        DocumentLock document = lockDocument(request.documentId());
        dataScopeService.assertDocumentAllowed("TREASURY_DOCUMENT", request.documentId());
        if (!OUTGOING_TYPES.contains(document.documentType())) throw new BusinessException("只有费用或其他应付源单可以申请付款");
        if (!List.of("CONFIRMED", "PARTIALLY_SETTLED").contains(document.status()))
            throw new BusinessException("只有已确认且尚未结清的源单可以申请付款");
        BigDecimal amount = positive(request.requestedAmount(), "申请金额");
        BigDecimal outstanding = money(document.totalAmount().subtract(document.settledAmount()));
        BigDecimal reserved = money(jdbc.queryForObject("""
                select coalesce(sum(requested_amount-paid_amount),0) from treasury_payment_request
                where document_id=? and status in ('DRAFT','APPROVED','PARTIALLY_PAID')
                """, BigDecimal.class, document.id()));
        if (reserved.add(amount).compareTo(outstanding) > 0)
            throw new BusinessException("申请金额加已有未支付申请超过源单未结金额");
        if (request.expectedPaymentDate().isBefore(request.requestDate())) throw new BusinessException("预计付款日不能早于申请日");
        Actor actor = actor();
        Long id = insert("""
                insert into treasury_payment_request
                (request_no,document_id,requested_amount,request_date,expected_payment_date,purpose,created_by,created_by_name)
                values (?,?,?,?,?,?,?,?)
                """, nextNo("FKSQ"), document.id(), amount, Date.valueOf(request.requestDate()),
                Date.valueOf(request.expectedPaymentDate()), required(request.purpose(), "付款用途"),
                actor.id(), actor.name());
        requestEvent(id, "CREATE", "", "DRAFT", request.purpose(), actor);
        return id;
    }

    @Transactional
    public Long approvePaymentRequest(Long id) {
        return approvePaymentRequest(id, null);
    }

    @Transactional
    public Long approvePaymentRequest(Long id, RiskExceptionDtos.InlineAuthorization authorization) {
        PaymentRequestLock request = lockPaymentRequest(id);
        if ("APPROVED".equals(request.status())) return id;
        if (!"DRAFT".equals(request.status())) throw new BusinessException("只有草稿付款申请可以批准");
        DocumentLock document = lockDocument(request.documentId());
        dataScopeService.assertDocumentAllowed("TREASURY_DOCUMENT", document.id());
        riskExceptionService.authorizeOrConsume(
                riskExceptionService.resolveTrigger("TREASURY_PAYMENT_REQUEST", id, "APPROVE"), authorization);
        if (!List.of("CONFIRMED", "PARTIALLY_SETTLED").contains(document.status()))
            throw new BusinessException("源单已结清、作废或冲正，不能批准付款申请");
        Actor actor = actor();
        jdbc.update("""
                update treasury_payment_request set status='APPROVED',approved_by=?,approved_by_name=?,
                  approved_at=now(),version_no=version_no+1 where id=? and status='DRAFT'
                """, actor.id(), actor.name(), id);
        requestEvent(id, "APPROVE", "DRAFT", "APPROVED", "负责人批准；尚未发生资金支付", actor);
        return id;
    }

    @Transactional
    public Long rejectPaymentRequest(Long id, TreasuryDtos.Action action) {
        PaymentRequestLock request = lockPaymentRequest(id);
        dataScopeService.assertDocumentAllowed("TREASURY_DOCUMENT", request.documentId());
        if ("REJECTED".equals(request.status())) return id;
        if (!List.of("DRAFT", "APPROVED").contains(request.status()) || request.paidAmount().signum() > 0)
            throw new BusinessException("只有未支付的草稿或已批准付款申请可以退回");
        Actor actor = actor();
        jdbc.update("""
                update treasury_payment_request set status='REJECTED',rejected_by=?,rejected_by_name=?,
                  rejected_reason=?,rejected_at=now(),version_no=version_no+1 where id=?
                """, actor.id(), actor.name(), required(action.reason(), "退回原因"), id);
        requestEvent(id, "REJECT", request.status(), "REJECTED", action.reason(), actor);
        return id;
    }

    @Transactional
    public Long cancelPaymentRequest(Long id, TreasuryDtos.Action action) {
        PaymentRequestLock request = lockPaymentRequest(id);
        dataScopeService.assertDocumentAllowed("TREASURY_DOCUMENT", request.documentId());
        if ("CANCELLED".equals(request.status())) return id;
        if (!List.of("DRAFT", "APPROVED").contains(request.status()) || request.paidAmount().signum() > 0)
            throw new BusinessException("只有未支付的草稿或已批准付款申请可以作废");
        Actor actor = actor();
        jdbc.update("""
                update treasury_payment_request set status='CANCELLED',cancelled_by=?,cancelled_by_name=?,
                  cancel_reason=?,cancelled_at=now(),version_no=version_no+1 where id=?
                """, actor.id(), actor.name(), required(action.reason(), "作废原因"), id);
        requestEvent(id, "CANCEL", request.status(), "CANCELLED", action.reason(), actor);
        return id;
    }

    @Transactional
    public Long settleDocument(Long documentId, TreasuryDtos.SettlementCreate request) {
        DocumentLock document = lockDocument(documentId);
        dataScopeService.assertDocumentAllowed("TREASURY_DOCUMENT", documentId);
        if (!List.of("CONFIRMED", "PARTIALLY_SETTLED").contains(document.status()))
            throw new BusinessException("只有已确认且未结清的源单可以登记收付款");
        requirePartnerRole(document.partnerId(), OUTGOING_TYPES.contains(document.documentType()) ? "SUPPLIER" : "CUSTOMER");
        BigDecimal amount = positive(request.amount(), "收付款金额");
        BigDecimal outstanding = money(document.totalAmount().subtract(document.settledAmount()));
        if (amount.compareTo(outstanding) > 0) throw new BusinessException("收付款金额超过源单未结金额");
        String method = enumValue(request.settlementMethod(), Set.of("CASH", "BANK"), "结算方式");
        String category = enumValue(request.cashFlowCategory(), CASH_FLOW_CATEGORIES, "现金流量分类");
        if ("INTERNAL_TRANSFER".equals(category)) throw new BusinessException("源单收付不能使用内部转账分类");
        ensureOpenPeriod(request.transactionDate());
        BankLedger bank = settlementAccount(method, request.businessBankAccountId(), request.transactionDate());
        PaymentRequestLock paymentRequest = null;
        if (request.paymentRequestId() != null && request.paymentRequestId() > 0) {
            paymentRequest = lockPaymentRequest(request.paymentRequestId());
            if (paymentRequest.documentId() != documentId) throw new BusinessException("付款申请不属于当前源单");
            if (!List.of("APPROVED", "PARTIALLY_PAID").contains(paymentRequest.status()))
                throw new BusinessException("付款申请尚未批准或已经结束");
            if (!OUTGOING_TYPES.contains(document.documentType())) throw new BusinessException("收款不能引用付款申请");
            if (paymentRequest.paidAmount().add(amount).compareTo(paymentRequest.requestedAmount()) > 0)
                throw new BusinessException("本次付款超过付款申请未支付金额");
        }
        String direction = OUTGOING_TYPES.contains(document.documentType()) ? "DEBIT" : "CREDIT";
        Actor actor = actor();
        String fundNo = nextNo(direction.equals("DEBIT") ? "ZC" : "SR");
        Long fundId = insert("""
                insert into treasury_fund_transaction
                (fund_no,fund_type,document_id,payment_request_id,direction,settlement_method,
                 business_bank_account_id,amount,transaction_date,cash_flow_category,
                 counterparty_name_snapshot,purpose,created_by,created_by_name)
                values (?,'DOCUMENT_SETTLEMENT',?,?,?,?,?,?,?,?,?,?,?,?)
                """, fundNo, documentId, paymentRequest == null ? 0L : paymentRequest.id(), direction, method,
                bank.bankAccountId(), amount, Date.valueOf(request.transactionDate()), category,
                document.partnerName(), required(request.purpose(), "收付款用途"), actor.id(), actor.name());
        Long journalId = insert("""
                insert into treasury_cash_journal
                (journal_no,fund_transaction_id,line_no,source_id,source_no,transaction_date,
                 business_bank_account_id,settlement_method,direction,amount,cash_flow_category,
                 partner_id,partner_name_snapshot,purpose)
                values (?,?,1,?,?,?,?,?,?,?,?,?,?,?)
                """, nextNo("CN"), fundId, fundId, fundNo, Date.valueOf(request.transactionDate()),
                bank.bankAccountId(), method, direction, amount, category, document.partnerId(),
                document.partnerName(), request.purpose().trim());
        String summary = fundNo + " " + request.purpose().trim();
        List<AccountingService.SystemVoucherLine> lines = "DEBIT".equals(direction)
                ? List.of(systemLine("2241", summary, amount, ZERO, document.partnerId()),
                    systemLine(bank.accountCode(), summary, ZERO, amount, 0L))
                : List.of(systemLine(bank.accountCode(), summary, amount, ZERO, 0L),
                    systemLine("1231", summary, ZERO, amount, document.partnerId()));
        Long voucherId = accountingService.createSourceVoucher("TREASURY_FUND", fundId, fundNo,
                request.transactionDate(), summary, lines);
        jdbc.update("update treasury_fund_transaction set accounting_voucher_id=? where id=?", voucherId, fundId);
        BigDecimal settled = money(document.settledAmount().add(amount));
        String nextStatus = settled.compareTo(document.totalAmount()) == 0 ? "SETTLED" : "PARTIALLY_SETTLED";
        jdbc.update("update treasury_document set settled_amount=?,status=?,version_no=version_no+1 where id=?",
                settled, nextStatus, documentId);
        documentEvent(documentId, "SETTLE", document.status(), nextStatus,
                fundNo + "；金额=" + amount + "；出纳行=" + journalId, actor);
        if (paymentRequest != null) updateRequestPaid(paymentRequest, amount, actor, fundNo);
        fundEvent(fundId, "CREATE", "", "ACTIVE", request.purpose(), actor);
        return fundId;
    }

    @Transactional
    public Long createTransfer(TreasuryDtos.TransferCreate request) {
        if (request.sourceBankAccountId().equals(request.destinationBankAccountId()))
            throw new BusinessException("转出和转入账户不能相同");
        BigDecimal amount = positive(request.amount(), "转账金额");
        ensureOpenPeriod(request.transactionDate());
        BankLedger source = bankAccount(request.sourceBankAccountId(), request.transactionDate());
        BankLedger destination = bankAccount(request.destinationBankAccountId(), request.transactionDate());
        Actor actor = actor();
        String fundNo = nextNo("ZZ");
        Long fundId = insert("""
                insert into treasury_fund_transaction
                (fund_no,fund_type,direction,settlement_method,business_bank_account_id,counter_bank_account_id,
                 amount,transaction_date,cash_flow_category,purpose,created_by,created_by_name)
                values (?,'INTERNAL_TRANSFER','TRANSFER','BANK',?,?,?,?,'INTERNAL_TRANSFER',?,?,?)
                """, fundNo, source.bankAccountId(), destination.bankAccountId(), amount,
                Date.valueOf(request.transactionDate()), required(request.purpose(), "转账用途"), actor.id(), actor.name());
        insert("""
                insert into treasury_cash_journal
                (journal_no,fund_transaction_id,line_no,source_id,source_no,transaction_date,
                 business_bank_account_id,settlement_method,direction,amount,cash_flow_category,purpose)
                values (?,?,1,?,?,?,?,'BANK','DEBIT',?,'INTERNAL_TRANSFER',?)
                """, nextNo("CN"), fundId, fundId, fundNo, Date.valueOf(request.transactionDate()),
                source.bankAccountId(), amount, request.purpose().trim());
        insert("""
                insert into treasury_cash_journal
                (journal_no,fund_transaction_id,line_no,source_id,source_no,transaction_date,
                 business_bank_account_id,settlement_method,direction,amount,cash_flow_category,purpose)
                values (?,?,2,?,?,?,?,'BANK','CREDIT',?,'INTERNAL_TRANSFER',?)
                """, nextNo("CN"), fundId, fundId, fundNo, Date.valueOf(request.transactionDate()),
                destination.bankAccountId(), amount, request.purpose().trim());
        String summary = fundNo + " " + request.purpose().trim();
        Long voucherId = accountingService.createSourceVoucher("TREASURY_FUND", fundId, fundNo,
                request.transactionDate(), summary, List.of(
                        systemLine(destination.accountCode(), summary, amount, ZERO, 0L),
                        systemLine(source.accountCode(), summary, ZERO, amount, 0L)));
        jdbc.update("update treasury_fund_transaction set accounting_voucher_id=? where id=?", voucherId, fundId);
        fundEvent(fundId, "TRANSFER", "", "ACTIVE", request.purpose(), actor);
        return fundId;
    }

    @Transactional
    public Long reverseFund(Long fundId, TreasuryDtos.Action action) {
        FundLock fund = lockFund(fundId);
        if (fund.documentId() > 0) {
            dataScopeService.assertDocumentAllowed("TREASURY_DOCUMENT", fund.documentId());
        } else if (!dataScopeService.hasFullDocumentScope()) {
            throw new com.soyia.copperv2.security.ForbiddenException("当前数据范围无权冲正内部账户转账");
        }
        if ("REVERSED".equals(fund.status())) return fundId;
        riskExceptionService.authorizeOrConsume(
                riskExceptionService.resolveTrigger("TREASURY_FUND", fundId, "REVERSE"),
                new RiskExceptionDtos.InlineAuthorization(action.reason(), action.approvalId()));
        List<Long> journalIds = jdbc.queryForList(
                "select id from treasury_cash_journal where fund_transaction_id=? and status='ACTIVE' for update",
                Long.class, fundId);
        if (journalIds.isEmpty()) throw new BusinessException("资金事项缺少有效出纳日记账行");
        for (Long journalId : journalIds) {
            if (scalarLong("select count(*) from bank_reconciliation_match where source_type='TREASURY_JOURNAL' and source_id=? and status='ACTIVE'", journalId) > 0
                    || scalarLong("select count(*) from bank_reconciliation_pending_system where source_type='TREASURY_JOURNAL' and source_id=? and status='OPEN'", journalId) > 0)
                throw new BusinessException("资金已经进入银行对账，请先撤销匹配或银行未达项");
        }
        LocalDate date = action.businessDate() == null ? LocalDate.now() : action.businessDate();
        ensureOpenPeriod(date);
        Long reversalVoucherId = accountingService.disposeSourceVoucher(fund.accountingVoucherId(), date,
                required(action.reason(), "冲正原因"));
        Actor actor = actor();
        jdbc.update("""
                update treasury_fund_transaction set status='REVERSED',reversal_voucher_id=?,reversal_reason=?,
                  reversed_by=?,reversed_by_name=?,reversed_at=now() where id=? and status='ACTIVE'
                """, reversalVoucherId, action.reason().trim(), actor.id(), actor.name(), fundId);
        jdbc.update("update treasury_cash_journal set status='REVERSED',reversed_at=now() where fund_transaction_id=? and status='ACTIVE'", fundId);
        if (fund.documentId() > 0) reverseDocumentSettlement(fund, actor);
        fundEvent(fundId, "REVERSE", "ACTIVE", "REVERSED", action.reason(), actor);
        return fundId;
    }

    public List<TreasuryDtos.JournalRow> journal(LocalDate from, LocalDate to,
                                                  Long bankAccountId, String direction) {
        LocalDate end = to == null ? LocalDate.now() : to;
        LocalDate start = from == null ? end.withDayOfMonth(1) : from;
        if (start.isAfter(end)) throw new BusinessException("开始日期不能晚于结束日期");
        StringBuilder sql = new StringBuilder(journalUnion()).append("""
                select x.*,
                  case when x.settlement_method='BANK' then
                    coalesce((select sum(m.matched_amount) from bank_reconciliation_match m
                      where m.source_type=x.source_type and m.source_id=x.source_id and m.status='ACTIVE'),0)+
                    coalesce((select sum(p.amount) from bank_reconciliation_pending_system p
                      where p.source_type=x.source_type and p.source_id=x.source_id and p.status='OPEN'),0)
                    else x.amount end matched_amount
                from cash_rows x where x.transaction_date between ? and ?
                """);
        List<Object> args = new ArrayList<>(List.of(Date.valueOf(start), Date.valueOf(end)));
        if (bankAccountId != null && bankAccountId > 0) { sql.append(" and x.business_bank_account_id=?"); args.add(bankAccountId); }
        if (StringUtils.hasText(direction)) { sql.append(" and x.direction=?"); args.add(direction.trim().toUpperCase()); }
        sql.append(" order by x.transaction_date desc,x.created_at desc,x.source_type,x.source_id limit 3000");
        List<ScopedJournalRow> scopedRows = jdbc.query(sql.toString(), (rs, rowNum) -> {
            BigDecimal amount = money(rs.getBigDecimal("amount"));
            BigDecimal matched = money(rs.getBigDecimal("matched_amount")).min(amount);
            TreasuryDtos.JournalRow row = new TreasuryDtos.JournalRow(rs.getString("row_key"), rs.getString("source_type"),
                    rs.getLong("source_id"), rs.getString("source_no"), rs.getLong("fund_transaction_id"),
                    rs.getDate("transaction_date").toLocalDate(), rs.getString("settlement_method"),
                    rs.getLong("business_bank_account_id"), rs.getString("bank_account_name"),
                    rs.getString("direction"), amount, rs.getString("cash_flow_category"),
                    rs.getLong("partner_id"), rs.getString("partner_name"), rs.getString("purpose"),
                    rs.getString("status"), matched, money(amount.subtract(matched)),
                    rs.getTimestamp("created_at").toLocalDateTime());
            return new ScopedJournalRow(rs.getString("access_source_type"),
                    rs.getLong("access_source_id"), row);
        }, args.toArray());
        if (dataScopeService.hasFullDocumentScope()) return scopedRows.stream().map(ScopedJournalRow::row).toList();
        return dataScopeService.filterDerivedDocumentList(scopedRows).stream().map(ScopedJournalRow::row).toList();
    }

    private void reverseDocumentSettlement(FundLock fund, Actor actor) {
        DocumentLock document = lockDocument(fund.documentId());
        BigDecimal settled = money(document.settledAmount().subtract(fund.amount())).max(ZERO);
        String nextStatus = settled.signum() == 0 ? "CONFIRMED" : "PARTIALLY_SETTLED";
        jdbc.update("update treasury_document set settled_amount=?,status=?,version_no=version_no+1 where id=?",
                settled, nextStatus, document.id());
        documentEvent(document.id(), "SETTLEMENT_REVERSE", document.status(), nextStatus,
                fund.fundNo() + " 已冲正", actor);
        if (fund.paymentRequestId() > 0) {
            PaymentRequestLock request = lockPaymentRequest(fund.paymentRequestId());
            BigDecimal paid = money(request.paidAmount().subtract(fund.amount())).max(ZERO);
            String requestStatus = paid.signum() == 0 ? "APPROVED" : "PARTIALLY_PAID";
            jdbc.update("update treasury_payment_request set paid_amount=?,status=?,version_no=version_no+1 where id=?",
                    paid, requestStatus, request.id());
            requestEvent(request.id(), "PAYMENT_REVERSE", request.status(), requestStatus,
                    fund.fundNo() + " 已冲正", actor);
        }
    }

    private void updateRequestPaid(PaymentRequestLock request, BigDecimal amount, Actor actor, String fundNo) {
        BigDecimal paid = money(request.paidAmount().add(amount));
        String status = paid.compareTo(request.requestedAmount()) == 0 ? "PAID" : "PARTIALLY_PAID";
        jdbc.update("update treasury_payment_request set paid_amount=?,status=?,version_no=version_no+1 where id=?",
                paid, status, request.id());
        requestEvent(request.id(), "PAY", request.status(), status, fundNo + "；金额=" + amount, actor);
    }

    private List<TreasuryDtos.FundRow> funds(Long documentId) {
        return jdbc.query("""
                select f.*,coalesce(b.account_name,'') bank_name,coalesce(cb.account_name,'') counter_bank_name,
                       coalesce(v.voucher_no,'') voucher_no,coalesce(v.status,'') voucher_status,
                       coalesce(rv.voucher_no,'') reversal_voucher_no
                from treasury_fund_transaction f
                left join business_bank_account b on b.id=f.business_bank_account_id
                left join business_bank_account cb on cb.id=f.counter_bank_account_id
                left join accounting_voucher v on v.id=f.accounting_voucher_id
                left join accounting_voucher rv on rv.id=f.reversal_voucher_id
                where f.document_id=? order by f.transaction_date,f.id
                """, (rs, rowNum) -> new TreasuryDtos.FundRow(rs.getLong("id"), rs.getString("fund_no"),
                rs.getString("fund_type"), rs.getLong("document_id"), rs.getLong("payment_request_id"),
                rs.getString("direction"), rs.getString("settlement_method"),
                rs.getLong("business_bank_account_id"), rs.getString("bank_name"),
                rs.getLong("counter_bank_account_id"), rs.getString("counter_bank_name"),
                rs.getBigDecimal("amount"), rs.getDate("transaction_date").toLocalDate(),
                rs.getString("cash_flow_category"), rs.getString("counterparty_name_snapshot"),
                rs.getString("purpose"), rs.getString("status"), rs.getLong("accounting_voucher_id"),
                rs.getString("voucher_no"), rs.getString("voucher_status"), rs.getLong("reversal_voucher_id"),
                rs.getString("reversal_voucher_no"), rs.getString("created_by_name"),
                rs.getString("reversal_reason"), rs.getString("reversed_by_name"),
                dateTime(rs.getTimestamp("reversed_at")), rs.getTimestamp("created_at").toLocalDateTime()), documentId);
    }

    private String documentSelect() {
        return """
                select d.*,coalesce(v.voucher_no,'') accounting_voucher_no,
                       coalesce(v.status,'') accounting_voucher_status
                from treasury_document d left join accounting_voucher v on v.id=d.accounting_voucher_id
                """;
    }

    private org.springframework.jdbc.core.RowMapper<TreasuryDtos.DocumentRow> documentMapper() {
        return (rs, rowNum) -> new TreasuryDtos.DocumentRow(rs.getLong("id"), rs.getString("document_no"),
                rs.getString("document_type"), rs.getLong("partner_id"), rs.getString("partner_name_snapshot"),
                rs.getDate("business_date").toLocalDate(), rs.getDate("due_date").toLocalDate(),
                rs.getString("basis_no"), rs.getString("summary"), rs.getLong("offset_account_id"),
                rs.getString("offset_account_code_snapshot"), rs.getString("offset_account_name_snapshot"),
                rs.getBoolean("price_includes_tax"), rs.getBigDecimal("tax_rate"),
                rs.getBigDecimal("amount_excluding_tax"), rs.getBigDecimal("tax_amount"),
                rs.getBigDecimal("total_amount"), rs.getBigDecimal("tax_rounding_difference"),
                rs.getString("tax_rule_snapshot"), rs.getString("input_tax_treatment"),
                rs.getBigDecimal("settled_amount"),
                money(rs.getBigDecimal("total_amount").subtract(rs.getBigDecimal("settled_amount"))),
                OUTGOING_TYPES.contains(rs.getString("document_type")) ? "DEBIT" : "CREDIT",
                rs.getString("status"), rs.getString("memo"), rs.getInt("version_no"),
                rs.getString("created_by_name"), rs.getString("confirmed_by_name"),
                dateTime(rs.getTimestamp("confirmed_at")), rs.getString("cancel_reason"),
                rs.getString("cancelled_by_name"), dateTime(rs.getTimestamp("cancelled_at")),
                rs.getString("reversal_reason"), rs.getString("reversed_by_name"),
                dateTime(rs.getTimestamp("reversed_at")), rs.getLong("accounting_voucher_id"),
                rs.getString("accounting_voucher_no"), rs.getString("accounting_voucher_status"),
                rs.getTimestamp("created_at").toLocalDateTime());
    }

    private String requestSelect() {
        return """
                select r.*,d.document_no,d.document_type,d.partner_id,d.partner_name_snapshot,
                       d.total_amount-d.settled_amount document_outstanding
                from treasury_payment_request r join treasury_document d on d.id=r.document_id
                """;
    }

    private org.springframework.jdbc.core.RowMapper<TreasuryDtos.PaymentRequestRow> requestMapper() {
        return (rs, rowNum) -> new TreasuryDtos.PaymentRequestRow(rs.getLong("id"), rs.getString("request_no"),
                rs.getLong("document_id"), rs.getString("document_no"), rs.getString("document_type"),
                rs.getLong("partner_id"), rs.getString("partner_name_snapshot"),
                rs.getBigDecimal("document_outstanding"), rs.getBigDecimal("requested_amount"),
                rs.getBigDecimal("paid_amount"), money(rs.getBigDecimal("requested_amount").subtract(rs.getBigDecimal("paid_amount"))),
                rs.getDate("request_date").toLocalDate(), rs.getDate("expected_payment_date").toLocalDate(),
                rs.getString("purpose"), rs.getString("status"), rs.getInt("version_no"),
                rs.getString("created_by_name"), rs.getString("approved_by_name"),
                dateTime(rs.getTimestamp("approved_at")), rs.getString("rejected_by_name"),
                rs.getString("rejected_reason"), dateTime(rs.getTimestamp("rejected_at")),
                rs.getString("cancelled_by_name"), rs.getString("cancel_reason"),
                dateTime(rs.getTimestamp("cancelled_at")), rs.getTimestamp("created_at").toLocalDateTime());
    }

    private org.springframework.jdbc.core.RowMapper<TreasuryDtos.EventRow> eventMapper() {
        return (rs, rowNum) -> new TreasuryDtos.EventRow(rs.getLong("id"), rs.getString("action"),
                rs.getString("from_status"), rs.getString("to_status"), rs.getString("reason"),
                rs.getString("operator_name"), rs.getTimestamp("created_at").toLocalDateTime());
    }

    private String journalUnion() {
        return """
                with cash_rows as (
                  select concat('PAYMENT:',p.id) row_key,'PAYMENT' source_type,p.id source_id,p.payment_no source_no,
                         'PAYMENT' access_source_type,p.id access_source_id,
                         0 fund_transaction_id,p.received_date transaction_date,p.payment_method settlement_method,
                         p.business_bank_account_id,coalesce(b.account_name,case when p.payment_method='CASH' then '库存现金' else '' end) bank_account_name,
                         'CREDIT' direction,p.amount,'SALES_COLLECTION' cash_flow_category,p.customer_id partner_id,
                         x.name partner_name,p.memo purpose,
                         case when p.verification_status='REVERSED' then 'REVERSED' else 'ACTIVE' end status,p.created_at
                  from payment p join partner x on x.id=p.customer_id
                  left join business_bank_account b on b.id=p.business_bank_account_id
                  where p.payment_method in ('CASH','BANK')
                  union all
                  select concat('SUPPLIER_PAYMENT:',p.id),'SUPPLIER_PAYMENT',p.id,p.payment_no,
                         'SUPPLIER_PAYMENT',p.id,0,p.paid_date,
                         p.payment_method,p.business_bank_account_id,
                         coalesce(b.account_name,case when p.payment_method='CASH' then '库存现金' else '' end),
                         'DEBIT',p.amount,'PURCHASE_PAYMENT',p.supplier_id,x.name,p.memo,
                         case when p.verification_status='REVERSED' then 'REVERSED' else 'ACTIVE' end,p.created_at
                  from supplier_payment p join partner x on x.id=p.supplier_id
                  left join business_bank_account b on b.id=p.business_bank_account_id
                  where p.payment_method in ('CASH','BANK')
                  union all
                  select concat('CUSTOMER_ADVANCE_REFUND:',r.id),'CUSTOMER_ADVANCE_REFUND',r.id,r.refund_no,
                         'CUSTOMER_ADVANCE_REFUND',r.id,0,
                         r.refunded_date,r.refund_method,r.business_bank_account_id,
                         coalesce(b.account_name,case when r.refund_method='CASH' then '库存现金' else '' end),
                         'DEBIT',r.amount,'OTHER_OPERATING_OUT',r.customer_id,x.name,r.memo,
                         case when r.status='ACTIVE' then 'ACTIVE' else 'REVERSED' end,r.created_at
                  from customer_advance_refund r join partner x on x.id=r.customer_id
                  left join business_bank_account b on b.id=r.business_bank_account_id
                  where r.refund_method in ('CASH','BANK')
                  union all
                  select concat('ACCEPTANCE_EVENT:',e.id),'ACCEPTANCE_EVENT',e.id,e.event_no,
                         'ACCEPTANCE_EVENT',e.id,0,e.occurred_date,
                         'BANK',e.business_bank_account_id,coalesce(ba.account_name,''),
                         case when e.cash_amount<0 then 'DEBIT' else 'CREDIT' end,abs(e.cash_amount),
                         case when e.cash_amount<0 then 'OTHER_OPERATING_OUT' else 'OTHER_OPERATING_IN' end,
                         a.customer_id,x.name,concat(e.event_type,' ',e.memo),'ACTIVE',e.created_at
                  from acceptance_bill_event e join acceptance_bill a on a.id=e.bill_id
                  join partner x on x.id=a.customer_id left join business_bank_account ba on ba.id=e.business_bank_account_id
                  where e.cash_amount<>0
                  union all
                  select concat('TREASURY_JOURNAL:',j.id),'TREASURY_JOURNAL',j.id,j.journal_no,
                         'TREASURY_DOCUMENT',f.document_id,
                         j.fund_transaction_id,j.transaction_date,j.settlement_method,j.business_bank_account_id,
                         coalesce(b.account_name,case when j.settlement_method='CASH' then '库存现金' else '' end),
                         j.direction,j.amount,j.cash_flow_category,j.partner_id,j.partner_name_snapshot,
                         j.purpose,j.status,j.created_at
                  from treasury_cash_journal j
                  join treasury_fund_transaction f on f.id=j.fund_transaction_id
                  left join business_bank_account b on b.id=j.business_bank_account_id
                )
                """;
    }

    private DocumentLock lockDocument(Long id) {
        List<DocumentLock> rows = jdbc.query("""
                select id,document_no,document_type,partner_id,partner_name_snapshot,business_date,
                       offset_account_code_snapshot,amount_excluding_tax,tax_amount,total_amount,
                       settled_amount,status,accounting_voucher_id,version_no,summary
                from treasury_document where id=? for update
                """, (rs, rowNum) -> new DocumentLock(rs.getLong("id"), rs.getString("document_no"),
                rs.getString("document_type"), rs.getLong("partner_id"), rs.getString("partner_name_snapshot"),
                rs.getDate("business_date").toLocalDate(), rs.getString("offset_account_code_snapshot"),
                rs.getBigDecimal("amount_excluding_tax"), rs.getBigDecimal("tax_amount"),
                rs.getBigDecimal("total_amount"), rs.getBigDecimal("settled_amount"), rs.getString("status"),
                rs.getLong("accounting_voucher_id"), rs.getInt("version_no"), rs.getString("summary")), id);
        if (rows.isEmpty()) throw new BusinessException("费用或其他往来源单不存在");
        return rows.get(0);
    }

    private PaymentRequestLock lockPaymentRequest(Long id) {
        List<PaymentRequestLock> rows = jdbc.query("""
                select id,document_id,requested_amount,paid_amount,status from treasury_payment_request where id=? for update
                """, (rs, rowNum) -> new PaymentRequestLock(rs.getLong("id"), rs.getLong("document_id"),
                rs.getBigDecimal("requested_amount"), rs.getBigDecimal("paid_amount"), rs.getString("status")), id);
        if (rows.isEmpty()) throw new BusinessException("付款申请不存在");
        return rows.get(0);
    }

    private FundLock lockFund(Long id) {
        List<FundLock> rows = jdbc.query("""
                select id,fund_no,document_id,payment_request_id,amount,status,accounting_voucher_id
                from treasury_fund_transaction where id=? for update
                """, (rs, rowNum) -> new FundLock(rs.getLong("id"), rs.getString("fund_no"),
                rs.getLong("document_id"), rs.getLong("payment_request_id"), rs.getBigDecimal("amount"),
                rs.getString("status"), rs.getLong("accounting_voucher_id")), id);
        if (rows.isEmpty()) throw new BusinessException("资金事项不存在");
        return rows.get(0);
    }

    private PartnerSnapshot partner(Long id) {
        List<PartnerSnapshot> rows = jdbc.query("select id,name,status from partner where id=?",
                (rs, rowNum) -> new PartnerSnapshot(rs.getLong("id"), rs.getString("name"), rs.getString("status")), id);
        if (rows.isEmpty()) throw new BusinessException("往来单位不存在");
        if (!"ENABLED".equals(rows.get(0).status())) throw new BusinessException("往来单位已停用，不能新增业务");
        return rows.get(0);
    }

    private void requirePartnerRole(Long partnerId, String role) {
        PartnerBusinessGuard.requireEnabledRole(jdbc, partnerId, role);
    }

    private AccountSnapshot offsetAccount(Long id, String documentType, LocalDate date) {
        if (id == null || id <= 0) throw new BusinessException("请选择费用或收入科目");
        List<AccountSnapshot> rows = jdbc.query("""
                select id,account_code,account_name,category,status from accounting_account
                where id=? and entity_id=(select min(id) from accounting_entity where status='ENABLED')
                  and leaf=1 and effective_from<=? and (effective_to is null or effective_to>=?)
                """, (rs, rowNum) -> new AccountSnapshot(rs.getLong("id"), rs.getString("account_code"),
                rs.getString("account_name"), rs.getString("category"), rs.getString("status")),
                id, Date.valueOf(date), Date.valueOf(date));
        if (rows.isEmpty() || !"ENABLED".equals(rows.get(0).status())) throw new BusinessException("所选会计科目未生效或已停用");
        if ("PURCHASE_LANDED_COST".equals(documentType) && !"5401".equals(rows.get(0).code()))
            throw new BusinessException("采购附加费用必须选择主营业务成本科目 5401");
        Set<String> allowed = OUTGOING_TYPES.contains(documentType) ? Set.of("EXPENSE", "COST") : Set.of("REVENUE");
        if (!allowed.contains(rows.get(0).category()))
            throw new BusinessException(OUTGOING_TYPES.contains(documentType) ? "费用及其他应付必须选择费用或成本科目" : "其他收入及其他应收必须选择收入科目");
        return rows.get(0);
    }

    private BankLedger settlementAccount(String method, Long bankAccountId, LocalDate date) {
        if ("CASH".equals(method)) return new BankLedger(0L, "库存现金", "1001");
        if (bankAccountId == null || bankAccountId <= 0) throw new BusinessException("银行收付必须选择企业银行账户");
        return bankAccount(bankAccountId, date);
    }

    private BankLedger bankAccount(Long id, LocalDate date) {
        List<BankLedger> rows = jdbc.query("""
                select b.id,b.account_name,a.account_code
                from business_bank_account b join accounting_account a on a.id=b.ledger_account_id
                where b.id=? and b.entity_id=(select min(id) from accounting_entity where status='ENABLED')
                  and a.entity_id=b.entity_id and b.status='ENABLED' and b.opening_date<=?
                  and a.status='ENABLED' and a.leaf=1
                  and a.effective_from<=? and (a.effective_to is null or a.effective_to>=?)
                """, (rs, rowNum) -> new BankLedger(rs.getLong(1), rs.getString(2), rs.getString(3)),
                id, Date.valueOf(date), Date.valueOf(date), Date.valueOf(date));
        if (rows.isEmpty()) throw new BusinessException("企业银行账户不存在、已停用或在业务日期尚未启用");
        return rows.get(0);
    }

    private void ensureOpenPeriod(LocalDate date) {
        long count = scalarLong("""
                select count(*) from accounting_period p join accounting_entity e on e.id=p.entity_id
                where e.id=(select min(id) from accounting_entity where status='ENABLED')
                  and ? between p.start_date and p.end_date and p.status='OPEN'
                """, Date.valueOf(date));
        if (count == 0) throw new BusinessException("业务日期没有开放的会计期间");
    }

    private void documentEvent(Long id, String action, String from, String to, String reason, Actor actor) {
        jdbc.update("""
                insert into treasury_document_event(document_id,action,from_status,to_status,reason,operator_id,operator_name)
                values (?,?,?,?,?,?,?)
                """, id, action, text(from), text(to), text(reason), actor.id(), actor.name());
    }

    private void requestEvent(Long id, String action, String from, String to, String reason, Actor actor) {
        jdbc.update("""
                insert into treasury_payment_request_event(request_id,action,from_status,to_status,reason,operator_id,operator_name)
                values (?,?,?,?,?,?,?)
                """, id, action, text(from), text(to), text(reason), actor.id(), actor.name());
    }

    private void fundEvent(Long id, String action, String from, String to, String reason, Actor actor) {
        jdbc.update("""
                insert into treasury_fund_event(fund_transaction_id,action,from_status,to_status,reason,operator_id,operator_name)
                values (?,?,?,?,?,?,?)
                """, id, action, text(from), text(to), text(reason), actor.id(), actor.name());
    }

    private AccountingService.SystemVoucherLine systemLine(String accountCode, String summary,
                                                            BigDecimal debit, BigDecimal credit, Long partnerId) {
        return new AccountingService.SystemVoucherLine(accountCode, summary, money(debit), money(credit),
                partnerId == null ? 0L : partnerId, 0L, 0L);
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
        if (holder.getKey() == null) throw new BusinessException("保存资金数据失败");
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

    private long number(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private BigDecimal money(Object value) {
        BigDecimal decimal = value instanceof BigDecimal number ? number
                : value instanceof Number number ? BigDecimal.valueOf(number.doubleValue()) : BigDecimal.ZERO;
        return decimal.setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal positive(BigDecimal value, String label) {
        BigDecimal result = money(value);
        if (result.signum() <= 0) throw new BusinessException(label + "必须大于0");
        return result;
    }

    private BigDecimal rate(BigDecimal value) {
        BigDecimal result = value == null ? BigDecimal.ZERO : value.setScale(4, RoundingMode.HALF_UP);
        if (result.signum() < 0 || result.compareTo(BigDecimal.valueOf(100)) > 0)
            throw new BusinessException("税率必须在0%到100%之间");
        return result;
    }

    private String enumValue(String value, Set<String> allowed, String label) {
        String result = required(value, label).toUpperCase();
        if (!allowed.contains(result)) throw new BusinessException(label + "无效");
        return result;
    }

    private String required(String value, String label) {
        if (!StringUtils.hasText(value)) throw new BusinessException("请填写" + label);
        return value.trim();
    }

    private String text(String value) { return value == null ? "" : value.trim(); }
    private LocalDateTime dateTime(java.sql.Timestamp value) { return value == null ? null : value.toLocalDateTime(); }
    private String nextNo(String prefix) {
        return prefix + NO_TIME.format(LocalDateTime.now()) + String.format("%03d", Math.floorMod(NO_COUNTER.incrementAndGet(), 1000));
    }

    private record Actor(long id, String name) {}
    private record PartnerSnapshot(long id, String name, String status) {}
    private record AccountSnapshot(long id, String code, String name, String category, String status) {}
    private record BankLedger(long bankAccountId, String name, String accountCode) {}
    private record DocumentLock(long id, String documentNo, String documentType, long partnerId,
                                String partnerName, LocalDate businessDate, String offsetAccountCode,
                                BigDecimal netAmount, BigDecimal taxAmount, BigDecimal totalAmount,
                                BigDecimal settledAmount, String status, long accountingVoucherId,
                                int versionNo, String summary) {}
    private record PaymentRequestLock(long id, long documentId, BigDecimal requestedAmount,
                                      BigDecimal paidAmount, String status) {}
    private record FundLock(long id, String fundNo, long documentId, long paymentRequestId,
                            BigDecimal amount, String status, long accountingVoucherId) {}
    private record ScopedPaymentRequest(String sourceType, long sourceId,
                                        TreasuryDtos.PaymentRequestRow row) {}
    private record ScopedJournalRow(String sourceType, long sourceId, TreasuryDtos.JournalRow row) {}
}
