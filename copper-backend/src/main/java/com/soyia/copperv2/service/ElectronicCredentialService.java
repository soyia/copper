package com.soyia.copperv2.service;

import com.soyia.copperv2.dto.TaxComplianceDtos;
import com.soyia.copperv2.security.BusinessDataScopeService;
import com.soyia.copperv2.security.SecurityContext;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class ElectronicCredentialService {
    private static final long MAX_FILE_SIZE = 30L * 1024 * 1024;
    private static final long MAX_PROOF_SIZE = 10L * 1024 * 1024;
    private static final AtomicInteger COUNTER = new AtomicInteger();
    private static final DateTimeFormatter NUMBER_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");
    private static final Set<String> VERIFY_KINDS = Set.of("AUTHENTICITY", "SIGNATURE");
    private static final Set<String> VERIFY_SOURCES = Set.of(
            "MOF_TOOLKIT", "TAX_DIGITAL_ACCOUNT", "NATIONAL_PLATFORM",
            "BANK_VALIDATION_TOOL", "MANUAL_REVIEW");
    private static final Set<String> VERIFY_RESULTS = Set.of("VERIFIED", "FAILED");
    private static final Set<String> RELATION_TYPES = Set.of("SOURCE", "BOOKING", "RECONCILIATION");
    private static final Map<String, TargetDefinition> TARGETS = Map.of(
            "TAX_INVOICE", new TargetDefinition("tax_invoice", "internal_no", "invoice_date", "total_amount"),
            "ACCOUNTING_VOUCHER", new TargetDefinition("accounting_voucher", "voucher_no", "voucher_date", "debit_total"),
            "BANK_RECONCILIATION", new TargetDefinition("bank_reconciliation_session", "reconciliation_no", "statement_to", "statement_closing_balance"),
            "TREASURY_DOCUMENT", new TargetDefinition("treasury_document", "document_no", "business_date", "total_amount"),
            "PAYMENT", new TargetDefinition("payment", "payment_no", "received_date", "amount"),
            "SUPPLIER_PAYMENT", new TargetDefinition("supplier_payment", "payment_no", "paid_date", "amount")
    );

    private final JdbcTemplate jdbc;
    private final ElectronicCredentialParser parser;
    private final LocalFileStorageService storage;
    private final StorageCapacityService capacity;
    private final BusinessDataScopeService dataScope;

    public ElectronicCredentialService(JdbcTemplate jdbc, ElectronicCredentialParser parser,
                                       LocalFileStorageService storage, StorageCapacityService capacity,
                                       BusinessDataScopeService dataScope) {
        this.jdbc = jdbc;
        this.parser = parser;
        this.storage = storage;
        this.capacity = capacity;
        this.dataScope = dataScope;
    }

    public TaxComplianceDtos.DashboardView dashboard(TaxComplianceDtos.WorkpaperRow latestWorkpaper) {
        Long entityId = entityId();
        Map<String, Object> row = jdbc.queryForMap("""
                select count(*) credential_count,
                  coalesce(sum(case when verification_status='UNVERIFIED' and status='ACTIVE' then 1 else 0 end),0) pending_verification_count,
                  coalesce(sum(case when parse_status in ('FAILED','PENDING_EXTERNAL') and status='ACTIVE' then 1 else 0 end),0) parse_exception_count,
                  coalesce(sum(case when status='ACTIVE' and not exists (
                    select 1 from electronic_credential_relation r
                    where r.credential_id=electronic_credential.id and r.status='ACTIVE'
                  ) then 1 else 0 end),0) unlinked_count,
                  coalesce(sum(case when verification_status='FAILED' and status='ACTIVE' then 1 else 0 end),0) failed_verification_count
                from electronic_credential where entity_id=?
                """, entityId);
        return new TaxComplianceDtos.DashboardView(number(row, "credential_count"),
                number(row, "pending_verification_count"), number(row, "parse_exception_count"),
                number(row, "unlinked_count"), number(row, "failed_verification_count"), latestWorkpaper);
    }

    public List<TaxComplianceDtos.CredentialRow> credentials(String type, String status, boolean exceptionOnly) {
        StringBuilder sql = new StringBuilder(credentialSelect()).append(" where c.entity_id=?");
        List<Object> args = new ArrayList<>(List.of(entityId()));
        if (StringUtils.hasText(type)) { sql.append(" and c.credential_type=?"); args.add(type.trim().toUpperCase(Locale.ROOT)); }
        if (StringUtils.hasText(status)) { sql.append(" and c.status=?"); args.add(status.trim().toUpperCase(Locale.ROOT)); }
        if (exceptionOnly) sql.append(" and (c.parse_status<>'PARSED' or c.verification_status<>'VERIFIED' or rc.relation_count=0)");
        sql.append(" order by c.id desc limit 500");
        return jdbc.query(sql.toString(), credentialMapper(), args.toArray());
    }

    public TaxComplianceDtos.CredentialDetail credential(Long id) {
        TaxComplianceDtos.CredentialRow row = requireCredential(id);
        List<TaxComplianceDtos.VerificationView> verifications = jdbc.query("""
                select * from electronic_credential_verification where credential_id=? order by id desc
                """, (rs, rowNum) -> new TaxComplianceDtos.VerificationView(
                rs.getLong("id"), rs.getString("verification_kind"), rs.getString("verification_source"),
                rs.getString("verification_result"), rs.getString("verification_reference"),
                rs.getTimestamp("checked_at").toLocalDateTime(), rs.getString("result_snapshot"),
                rs.getString("proof_file_name"), rs.getString("proof_content_type"),
                rs.getLong("proof_file_size"), rs.getString("proof_sha256"),
                rs.getString("operator_name"), rs.getTimestamp("created_at").toLocalDateTime()), id);
        List<TaxComplianceDtos.RelationView> relations = jdbc.query("""
                select * from electronic_credential_relation where credential_id=? order by id
                """, (rs, rowNum) -> new TaxComplianceDtos.RelationView(
                rs.getLong("id"), rs.getString("relation_type"), rs.getString("document_type"),
                rs.getLong("document_id"), rs.getString("document_no"),
                rs.getLong("evidence_attachment_id"), rs.getString("status"), rs.getString("note"),
                rs.getString("created_by_name"), rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getString("reversed_by_name"), rs.getString("reversed_reason"),
                timestamp(rs.getTimestamp("reversed_at"))), id);
        List<TaxComplianceDtos.EventView> events = jdbc.query("""
                select * from electronic_credential_event where credential_id=? order by id desc
                """, (rs, rowNum) -> new TaxComplianceDtos.EventView(
                rs.getLong("id"), rs.getString("action"), rs.getString("from_status"),
                rs.getString("to_status"), rs.getString("summary"), rs.getString("operator_name"),
                rs.getTimestamp("created_at").toLocalDateTime()), id);
        return new TaxComplianceDtos.CredentialDetail(row, verifications, relations, events);
    }

    @Transactional
    public TaxComplianceDtos.CredentialRow upload(String declaredType, MultipartFile file) {
        capacity.assertLargeWriteAllowed("电子凭证接收");
        if (file == null || file.isEmpty()) throw new BusinessException("请选择电子凭证原始文件");
        if (file.getSize() > MAX_FILE_SIZE) throw new BusinessException("单个电子凭证原件不能超过30MB");
        try {
            byte[] bytes = file.getBytes();
            String originalName = safeFileName(file.getOriginalFilename(), "electronic-credential");
            ElectronicCredentialParser.ParseResult parsed = parser.parse(bytes, originalName, declaredType);
            String sha = sha256(bytes);
            Long entityId = entityId();
            Integer duplicate = jdbc.queryForObject("""
                    select count(*) from electronic_credential
                    where entity_id=? and source_file_sha256=? and status='ACTIVE'
                    """, Integer.class, entityId, sha);
            if (duplicate != null && duplicate > 0) throw new BusinessException("该电子凭证原始文件已经接收，不能重复导入");
            String storagePath = storage.store(bytes, "electronic-credentials/" + LocalDate.now(),
                    "." + parsed.fileFormat().toLowerCase(Locale.ROOT));
            SecurityContext.Principal actor = SecurityContext.require();
            String credentialNo = nextNumber();
            jdbc.update("""
                    insert into electronic_credential
                    (entity_id,credential_no,credential_type,file_format,standard_code,standard_version,
                     original_file_name,content_type,file_size,source_file_sha256,storage_path,
                     parse_status,parse_message,external_no,direction_hint,issue_date,period_from,period_to,
                     accounting_entity_code,accounting_entity_name,counterparty_code,counterparty_name,
                     bank_account_no,currency,amount_excluding_tax,tax_amount,total_amount,detail_count,
                     created_by,created_by_name)
                    values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, entityId, credentialNo, parsed.credentialType(), parsed.fileFormat(),
                    parsed.standardCode(), parsed.standardVersion(), originalName,
                    contentType(originalName, file.getContentType(), parsed.fileFormat()), bytes.length, sha, storagePath,
                    parsed.parseStatus(), parsed.parseMessage(), text(parsed.externalNo()), text(parsed.directionHint()),
                    parsed.issueDate(), parsed.periodFrom(), parsed.periodTo(), text(parsed.accountingEntityCode()),
                    text(parsed.accountingEntityName()), text(parsed.counterpartyCode()), text(parsed.counterpartyName()),
                    text(parsed.bankAccountNo()), textOr(parsed.currency(), "CNY"), parsed.amountExcludingTax(),
                    parsed.taxAmount(), parsed.totalAmount(), parsed.detailCount(), actor.employeeId(), actor.employeeName());
            Long id = jdbc.queryForObject("select last_insert_id()", Long.class);
            addEvent(id, "RECEIVE", "", parsed.parseStatus(), "接收原始文件 " + originalName + "；SHA-256=" + sha);
            addEvent(id, "PARSE", "RECEIVED", parsed.parseStatus(), parsed.parseMessage());
            return requireCredential(id);
        } catch (DuplicateKeyException ex) {
            throw new BusinessException("该电子凭证原始文件已经接收，不能重复导入");
        } catch (IOException ex) {
            throw new BusinessException("电子凭证原始文件读取失败");
        }
    }

    @Transactional
    public Long verify(Long id, String kind, String source, String result, String reference,
                       LocalDateTime checkedAt, String snapshot, MultipartFile proof) {
        TaxComplianceDtos.CredentialRow credential = requireActive(id);
        String normalizedKind = enumValue(kind, VERIFY_KINDS, "验证类型");
        String normalizedSource = enumValue(source, VERIFY_SOURCES, "验证来源");
        String normalizedResult = enumValue(result, VERIFY_RESULTS, "验证结果");
        String normalizedReference = required(reference, "验证参考号");
        if (proof == null || proof.isEmpty()) throw new BusinessException("必须上传验签或验真结果凭证");
        if (proof.getSize() > MAX_PROOF_SIZE) throw new BusinessException("验证凭证不能超过10MB");
        if ("DIGITAL_INVOICE".equals(credential.credentialType()) && !"AUTHENTICITY".equals(normalizedKind)) {
            throw new BusinessException("数电发票必须登记验真结果，不能登记为银行验签");
        }
        if (!"DIGITAL_INVOICE".equals(credential.credentialType()) && !"SIGNATURE".equals(normalizedKind)) {
            throw new BusinessException("银行电子凭证必须登记验签结果，不能登记为发票验真");
        }
        if ("DIGITAL_INVOICE".equals(credential.credentialType())
                && "BANK_VALIDATION_TOOL".equals(normalizedSource)) {
            throw new BusinessException("银行验证工具不能作为数电发票验真来源");
        }
        if (!"DIGITAL_INVOICE".equals(credential.credentialType())
                && Set.of("TAX_DIGITAL_ACCOUNT", "NATIONAL_PLATFORM").contains(normalizedSource)) {
            throw new BusinessException("税务查验平台不能作为银行电子凭证验签来源");
        }
        try {
            byte[] bytes = proof.getBytes();
            String proofName = safeFileName(proof.getOriginalFilename(), "verification-proof.pdf");
            String proofType = proofContentType(proofName, proof.getContentType(), bytes);
            String proofSha = sha256(bytes);
            String path = storage.store(bytes, "electronic-credential-verifications/" + id,
                    extension(proofName, proofType));
            SecurityContext.Principal actor = SecurityContext.require();
            jdbc.update("""
                    insert into electronic_credential_verification
                    (credential_id,verification_kind,verification_source,verification_result,
                     verification_reference,checked_at,result_snapshot,proof_file_name,proof_content_type,
                     proof_file_size,proof_sha256,proof_storage_path,operator_id,operator_name)
                    values (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, id, normalizedKind, normalizedSource, normalizedResult, normalizedReference,
                    checkedAt == null ? LocalDateTime.now() : checkedAt, text(snapshot), proofName, proofType,
                    bytes.length, proofSha, path, actor.employeeId(), actor.employeeName());
            Long verificationId = jdbc.queryForObject("select last_insert_id()", Long.class);
            if ("SIGNATURE".equals(normalizedKind)) {
                jdbc.update("update electronic_credential set signature_status=? where id=?",
                        normalizedResult, id);
            }
            String requiredKind = "DIGITAL_INVOICE".equals(credential.credentialType()) ? "AUTHENTICITY" : "SIGNATURE";
            String overall = normalizedKind.equals(requiredKind) ? normalizedResult : credential.verificationStatus();
            jdbc.update("update electronic_credential set verification_status=? where id=?", overall, id);
            if ("DIGITAL_INVOICE".equals(credential.credentialType())
                    && "AUTHENTICITY".equals(normalizedKind) && "VERIFIED".equals(normalizedResult)) {
                for (Long invoiceId : jdbc.queryForList("""
                        select document_id from electronic_credential_relation
                        where credential_id=? and document_type='TAX_INVOICE' and status='ACTIVE'
                        """, Long.class, id)) {
                    applyInvoiceVerification(id, verificationId, invoiceId);
                }
            }
            addEvent(id, "VERIFY_" + normalizedKind, credential.verificationStatus(), overall,
                    normalizedSource + "；结果=" + normalizedResult + "；参考号=" + normalizedReference
                            + "；证据SHA-256=" + proofSha);
            return id;
        } catch (IOException ex) {
            throw new BusinessException("验证凭证读取失败");
        }
    }

    public List<TaxComplianceDtos.LinkCandidate> linkCandidates(Long id) {
        TaxComplianceDtos.CredentialRow credential = requireActive(id);
        return switch (credential.credentialType()) {
            case "DIGITAL_INVOICE" -> invoiceCandidates(credential);
            case "BANK_STATEMENT" -> statementCandidates(credential);
            case "BANK_RECEIPT" -> receiptCandidates(credential);
            default -> List.of();
        };
    }

    @Transactional
    public Long link(Long id, TaxComplianceDtos.LinkRequest request) {
        TaxComplianceDtos.CredentialRow credential = requireActive(id);
        String relationType = enumValue(request.relationType(), RELATION_TYPES, "关联层次");
        String documentType = required(request.documentType(), "业务对象类型").toUpperCase(Locale.ROOT);
        TargetDefinition definition = TARGETS.get(documentType);
        if (definition == null) throw new BusinessException("该业务对象不支持电子凭证关联");
        assertCompatible(credential.credentialType(), relationType, documentType);
        if (request.documentId() == null || request.documentId() <= 0) throw new BusinessException("业务对象ID无效");
        dataScope.assertDocumentAllowed(documentType, request.documentId());
        TargetIdentity target = target(definition, request.documentId());
        if ("TAX_INVOICE".equals(documentType)) assertInvoiceMatch(credential, request.documentId());
        if ("BANK_RECONCILIATION".equals(documentType)) assertStatementMatch(credential, request.documentId());
        Long evidenceId = attachOriginal(credential, documentType, request.documentId());
        SecurityContext.Principal actor = SecurityContext.require();
        try {
            jdbc.update("""
                    insert into electronic_credential_relation
                    (credential_id,relation_type,document_type,document_id,document_no,
                     evidence_attachment_id,note,created_by,created_by_name)
                    values (?,?,?,?,?,?,?,?,?)
                    """, id, relationType, documentType, request.documentId(), target.documentNo(), evidenceId,
                    text(request.note()), actor.employeeId(), actor.employeeName());
        } catch (DuplicateKeyException ex) {
            throw new BusinessException("该电子凭证已经关联此业务对象，无需重复操作");
        }
        Long relationId = jdbc.queryForObject("select last_insert_id()", Long.class);
        if ("TAX_INVOICE".equals(documentType)) {
            jdbc.update("""
                    update tax_invoice set source_file_name=?,source_file_sha256=?
                    where id=? and (source_file_sha256='' or source_file_sha256=?)
                    """, credential.originalFileName(), credential.sourceFileSha256(), request.documentId(),
                    credential.sourceFileSha256());
            if ("VERIFIED".equals(credential.verificationStatus())) {
                List<Long> verificationIds = jdbc.queryForList("""
                        select id from electronic_credential_verification
                        where credential_id=? and verification_kind='AUTHENTICITY' and verification_result='VERIFIED'
                        order by id desc limit 1
                        """, Long.class, id);
                if (!verificationIds.isEmpty()) applyInvoiceVerification(id, verificationIds.get(0), request.documentId());
            }
        }
        addEvent(id, "LINK", "ACTIVE", "ACTIVE",
                relationType + "关联" + documentType + " " + target.documentNo());
        return relationId;
    }

    @Transactional
    public Long reverseRelation(Long id, Long relationId, String reason) {
        requireActive(id);
        String normalizedReason = required(reason, "解除原因");
        SecurityContext.Principal actor = SecurityContext.require();
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select document_type,document_id,evidence_attachment_id from electronic_credential_relation
                where id=? and credential_id=? and status='ACTIVE'
                """, relationId, id);
        if (rows.isEmpty()) throw new BusinessException("有效关联不存在或已经解除");
        Map<String, Object> row = rows.get(0);
        String documentType = String.valueOf(row.get("document_type"));
        Long documentId = ((Number) row.get("document_id")).longValue();
        dataScope.assertDocumentAllowed(documentType, documentId);
        int changed = jdbc.update("""
                update electronic_credential_relation set status='REVERSED',reversed_by=?,reversed_by_name=?,
                  reversed_reason=?,reversed_at=now() where id=? and credential_id=? and status='ACTIVE'
                """, actor.employeeId(), actor.employeeName(), normalizedReason, relationId, id);
        if (changed != 1) throw new BusinessException("有效关联不存在或已经解除");
        Long evidenceId = ((Number) row.get("evidence_attachment_id")).longValue();
        if (evidenceId > 0) {
            jdbc.update("""
                    update business_evidence_attachment set status='VOIDED',voided_by=?,voided_by_name=?,
                      void_reason=?,voided_at=now()
                    where id=? and status='ACTIVE'
                    """, actor.employeeId(), actor.employeeName(), "电子凭证关联解除：" + normalizedReason, evidenceId);
        }
        addEvent(id, "RELATION_REVERSE", "ACTIVE", "ACTIVE", normalizedReason);
        return relationId;
    }

    @Transactional
    public Long voidCredential(Long id, String reason) {
        TaxComplianceDtos.CredentialRow credential = requireActive(id);
        Integer linked = jdbc.queryForObject("""
                select count(*) from electronic_credential_relation where credential_id=? and status='ACTIVE'
                """, Integer.class, id);
        if (linked != null && linked > 0) throw new BusinessException("电子凭证仍有关联业务对象，请先逐项解除关联后再作废");
        SecurityContext.Principal actor = SecurityContext.require();
        String normalizedReason = required(reason, "作废原因");
        int changed = jdbc.update("""
                update electronic_credential set status='VOIDED',void_reason=?,voided_by=?,voided_by_name=?,voided_at=now()
                where id=? and status='ACTIVE'
                """, normalizedReason, actor.employeeId(), actor.employeeName(), id);
        if (changed != 1) throw new BusinessException("电子凭证不存在或已经作废");
        addEvent(id, "VOID", credential.status(), "VOIDED", normalizedReason);
        return id;
    }

    public TaxComplianceDtos.FileContent original(Long id) {
        TaxComplianceDtos.CredentialRow credential = requireCredential(id);
        String path = jdbc.queryForObject("select storage_path from electronic_credential where id=?", String.class, id);
        return new TaxComplianceDtos.FileContent(credential.originalFileName(),
                contentType(credential.originalFileName(), "", credential.fileFormat()), storage.read(path));
    }

    public TaxComplianceDtos.FileContent proof(Long id, Long verificationId) {
        requireCredential(id);
        List<TaxComplianceDtos.FileContent> rows = jdbc.query("""
                select proof_file_name,proof_content_type,proof_storage_path
                from electronic_credential_verification where id=? and credential_id=?
                """, (rs, rowNum) -> new TaxComplianceDtos.FileContent(rs.getString("proof_file_name"),
                rs.getString("proof_content_type"), storage.read(rs.getString("proof_storage_path"))),
                verificationId, id);
        if (rows.isEmpty()) throw new BusinessException("验证凭证不存在");
        return rows.get(0);
    }

    private List<TaxComplianceDtos.LinkCandidate> invoiceCandidates(TaxComplianceDtos.CredentialRow item) {
        return jdbc.query("""
                select id,internal_no,invoice_date,total_amount,status,invoice_number,
                  case when invoice_number=? then 'EXACT' when total_amount=? then 'POSSIBLE' else 'REVIEW' end match_level
                from tax_invoice
                where entity_id=? and status<>'VOIDED'
                  and (invoice_number=? or (total_amount=? and abs(datediff(invoice_date,?))<=31))
                order by (invoice_number=?) desc,abs(datediff(invoice_date,?)),id desc limit 20
                """, (rs, rowNum) -> new TaxComplianceDtos.LinkCandidate("SOURCE", "TAX_INVOICE",
                rs.getLong("id"), rs.getString("internal_no"), rs.getDate("invoice_date").toLocalDate(),
                rs.getBigDecimal("total_amount"), rs.getString("status"), rs.getString("match_level"),
                rs.getString("invoice_number").equals(item.externalNo()) ? "发票号码一致" : "金额及日期接近，需人工核对"),
                item.externalNo(), item.totalAmount(), entityId(), item.externalNo(), item.totalAmount(),
                item.issueDate() == null ? LocalDate.now() : item.issueDate(), item.externalNo(),
                item.issueDate() == null ? LocalDate.now() : item.issueDate());
    }

    private List<TaxComplianceDtos.LinkCandidate> statementCandidates(TaxComplianceDtos.CredentialRow item) {
        if (item.periodFrom() == null || item.periodTo() == null) return List.of();
        return jdbc.query("""
                select s.id,s.reconciliation_no,s.statement_to,s.statement_closing_balance,s.status,
                  a.bank_account_no
                from bank_reconciliation_session s
                join business_bank_account a on a.id=s.bank_account_id
                where s.entity_id=? and s.statement_from<=? and s.statement_to>=?
                order by (a.bank_account_no=?) desc,s.id desc limit 20
                """, (rs, rowNum) -> new TaxComplianceDtos.LinkCandidate("RECONCILIATION", "BANK_RECONCILIATION",
                rs.getLong("id"), rs.getString("reconciliation_no"), rs.getDate("statement_to").toLocalDate(),
                rs.getBigDecimal("statement_closing_balance"), rs.getString("status"),
                rs.getString("bank_account_no").equals(item.bankAccountNo()) ? "EXACT" : "REVIEW",
                rs.getString("bank_account_no").equals(item.bankAccountNo()) ? "账户和期间一致" : "期间一致，银行账号需人工核对"),
                entityId(), item.periodTo(), item.periodFrom(), item.bankAccountNo());
    }

    private List<TaxComplianceDtos.LinkCandidate> receiptCandidates(TaxComplianceDtos.CredentialRow item) {
        LocalDate date = item.issueDate() == null ? LocalDate.now() : item.issueDate();
        List<TaxComplianceDtos.LinkCandidate> result = new ArrayList<>();
        result.addAll(jdbc.query("""
                select id,payment_no,received_date,amount,verification_status from payment
                where verification_status<>'REVERSED' and amount=? and abs(datediff(received_date,?))<=7
                order by abs(datediff(received_date,?)),id desc limit 10
                """, (rs, rowNum) -> new TaxComplianceDtos.LinkCandidate("SOURCE", "PAYMENT", rs.getLong("id"),
                rs.getString("payment_no"), rs.getDate("received_date").toLocalDate(), rs.getBigDecimal("amount"),
                rs.getString("verification_status"), "POSSIBLE", "金额一致且日期相差不超过7天"),
                item.totalAmount(), date, date));
        result.addAll(jdbc.query("""
                select id,payment_no,paid_date,amount,verification_status from supplier_payment
                where verification_status<>'REVERSED' and amount=? and abs(datediff(paid_date,?))<=7
                order by abs(datediff(paid_date,?)),id desc limit 10
                """, (rs, rowNum) -> new TaxComplianceDtos.LinkCandidate("SOURCE", "SUPPLIER_PAYMENT", rs.getLong("id"),
                rs.getString("payment_no"), rs.getDate("paid_date").toLocalDate(), rs.getBigDecimal("amount"),
                rs.getString("verification_status"), "POSSIBLE", "金额一致且日期相差不超过7天"),
                item.totalAmount(), date, date));
        return result;
    }

    private void assertCompatible(String credentialType, String relationType, String documentType) {
        boolean valid = switch (credentialType) {
            case "DIGITAL_INVOICE" -> ("SOURCE".equals(relationType) && "TAX_INVOICE".equals(documentType))
                    || ("BOOKING".equals(relationType) && "ACCOUNTING_VOUCHER".equals(documentType));
            case "BANK_STATEMENT" -> ("RECONCILIATION".equals(relationType) && "BANK_RECONCILIATION".equals(documentType))
                    || ("BOOKING".equals(relationType) && "ACCOUNTING_VOUCHER".equals(documentType));
            case "BANK_RECEIPT" -> ("SOURCE".equals(relationType)
                    && Set.of("PAYMENT", "SUPPLIER_PAYMENT", "TREASURY_DOCUMENT").contains(documentType))
                    || ("BOOKING".equals(relationType) && "ACCOUNTING_VOUCHER".equals(documentType));
            default -> false;
        };
        if (!valid) throw new BusinessException("凭证类型、关联层次与业务对象不匹配");
    }

    private void assertInvoiceMatch(TaxComplianceDtos.CredentialRow credential, Long invoiceId) {
        Map<String, Object> invoice = jdbc.queryForMap("""
                select invoice_number,total_amount from tax_invoice where id=?
                """, invoiceId);
        if (StringUtils.hasText(credential.externalNo())
                && !credential.externalNo().equals(String.valueOf(invoice.get("invoice_number")))) {
            throw new BusinessException("结构化数电票号码与目标发票号码不一致，不能关联");
        }
        BigDecimal amount = (BigDecimal) invoice.get("total_amount");
        if (credential.totalAmount().compareTo(BigDecimal.ZERO) != 0
                && credential.totalAmount().compareTo(amount) != 0) {
            throw new BusinessException("结构化数电票价税合计与目标发票不一致，不能关联");
        }
    }

    private void assertStatementMatch(TaxComplianceDtos.CredentialRow credential, Long sessionId) {
        Map<String, Object> row = jdbc.queryForMap("""
                select statement_from,statement_to from bank_reconciliation_session where id=?
                """, sessionId);
        if (credential.periodFrom() != null && credential.periodTo() != null) {
            LocalDate from = ((java.sql.Date) row.get("statement_from")).toLocalDate();
            LocalDate to = ((java.sql.Date) row.get("statement_to")).toLocalDate();
            if (from.isAfter(credential.periodFrom()) || to.isBefore(credential.periodTo())) {
                throw new BusinessException("电子对账单期间不在目标银行对账期间内，不能关联");
            }
        }
    }

    private TargetIdentity target(TargetDefinition definition, Long id) {
        List<TargetIdentity> rows = jdbc.query("select " + definition.numberColumn() + " from "
                        + definition.table() + " where id=?",
                (rs, rowNum) -> new TargetIdentity(rs.getString(1)), id);
        if (rows.isEmpty()) throw new BusinessException("关联业务对象不存在");
        return rows.get(0);
    }

    private Long attachOriginal(TaxComplianceDtos.CredentialRow credential, String documentType, Long documentId) {
        List<Long> existing = jdbc.queryForList("""
                select id from business_evidence_attachment
                where document_type=? and document_id=? and sha256=? and status='ACTIVE'
                order by id limit 1
                """, Long.class, documentType, documentId, credential.sourceFileSha256());
        if (!existing.isEmpty()) return existing.get(0);
        String storagePath = jdbc.queryForObject("select storage_path from electronic_credential where id=?",
                String.class, credential.id());
        SecurityContext.Principal actor = SecurityContext.require();
        jdbc.update("""
                insert into business_evidence_attachment
                (document_type,document_id,evidence_type,source_channel,original_file_name,
                 archive_file_name,content_type,file_size,sha256,storage_path,note,uploaded_by,uploaded_by_name)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, documentType, documentId, "ORIGINAL_DOCUMENT", "IMPORT", credential.originalFileName(),
                "electronic_credential_" + credential.id() + extension(credential.originalFileName(),
                        contentType(credential.originalFileName(), "", credential.fileFormat())),
                contentType(credential.originalFileName(), "", credential.fileFormat()), credential.fileSize(),
                credential.sourceFileSha256(), storagePath, "ERP-12 电子凭证原件 " + credential.credentialNo(),
                actor.employeeId(), actor.employeeName());
        return jdbc.queryForObject("select last_insert_id()", Long.class);
    }

    private void applyInvoiceVerification(Long credentialId, Long verificationId, Long invoiceId) {
        Map<String, Object> proof = jdbc.queryForMap("""
                select * from electronic_credential_verification
                where id=? and credential_id=? and verification_kind='AUTHENTICITY' and verification_result='VERIFIED'
                """, verificationId, credentialId);
        String proofSha = String.valueOf(proof.get("proof_sha256"));
        List<Long> evidenceIds = jdbc.queryForList("""
                select id from business_evidence_attachment
                where document_type='TAX_INVOICE' and document_id=? and evidence_type='VERIFICATION_PROOF'
                  and sha256=? and status='ACTIVE' order by id limit 1
                """, Long.class, invoiceId, proofSha);
        Long evidenceId;
        if (evidenceIds.isEmpty()) {
            SecurityContext.Principal actor = SecurityContext.require();
            String proofName = String.valueOf(proof.get("proof_file_name"));
            jdbc.update("""
                    insert into business_evidence_attachment
                    (document_type,document_id,evidence_type,source_channel,original_file_name,
                     archive_file_name,content_type,file_size,sha256,storage_path,note,uploaded_by,uploaded_by_name)
                    values ('TAX_INVOICE',?,'VERIFICATION_PROOF','IMPORT',?,?,?,?,?,?,?, ?,?)
                    """, invoiceId, proofName, "credential_verification_" + verificationId
                            + extension(proofName, String.valueOf(proof.get("proof_content_type"))),
                    proof.get("proof_content_type"), proof.get("proof_file_size"), proofSha,
                    proof.get("proof_storage_path"), "ERP-12 电子凭证验真证据", actor.employeeId(), actor.employeeName());
            evidenceId = jdbc.queryForObject("select last_insert_id()", Long.class);
        } else {
            evidenceId = evidenceIds.get(0);
        }
        SecurityContext.Principal actor = SecurityContext.require();
        jdbc.update("""
                insert into tax_invoice_verification_record
                (invoice_id,verification_source,verification_result,verification_reference,
                 proof_attachment_id,proof_sha256,checked_at,query_snapshot,note,operator_id,operator_name)
                values (?,?,?,?,?,?,?,?,?,?,?)
                """, invoiceId, proof.get("verification_source"), proof.get("verification_result"),
                proof.get("verification_reference"), evidenceId, proofSha, proof.get("checked_at"),
                proof.get("result_snapshot"), "由 ERP-12 结构化电子凭证验证证据同步；credential=" + credentialId,
                actor.employeeId(), actor.employeeName());
        jdbc.update("""
                update tax_invoice set verification_status='VERIFIED',verification_reference=?,verified_at=?
                where id=? and status<>'VOIDED'
                """, proof.get("verification_reference"), proof.get("checked_at"), invoiceId);
        jdbc.update("""
                insert into tax_invoice_event
                (invoice_id,action,from_status,to_status,reason,operator_id,operator_name)
                values (?,'VERIFY','UNVERIFIED','VERIFIED',?,?,?)
                """, invoiceId, "ERP-12 电子凭证验真同步；credential=" + credentialId,
                actor.employeeId(), actor.employeeName());
    }

    private TaxComplianceDtos.CredentialRow requireCredential(Long id) {
        List<TaxComplianceDtos.CredentialRow> rows = jdbc.query(credentialSelect()
                + " where c.id=? and c.entity_id=?", credentialMapper(), id, entityId());
        if (rows.isEmpty()) throw new BusinessException("电子凭证不存在");
        return rows.get(0);
    }

    private TaxComplianceDtos.CredentialRow requireActive(Long id) {
        TaxComplianceDtos.CredentialRow row = requireCredential(id);
        if (!"ACTIVE".equals(row.status())) throw new BusinessException("电子凭证已作废，不能继续处理");
        return row;
    }

    private String credentialSelect() {
        return """
                select c.*,coalesce(rc.relation_count,0) active_relation_count
                from electronic_credential c
                left join (
                  select credential_id,count(*) relation_count from electronic_credential_relation
                  where status='ACTIVE' group by credential_id
                ) rc on rc.credential_id=c.id
                """;
    }

    private org.springframework.jdbc.core.RowMapper<TaxComplianceDtos.CredentialRow> credentialMapper() {
        return (rs, rowNum) -> new TaxComplianceDtos.CredentialRow(
                rs.getLong("id"), rs.getString("credential_no"), rs.getString("credential_type"),
                rs.getString("file_format"), rs.getString("standard_code"), rs.getString("standard_version"),
                rs.getString("original_file_name"), rs.getLong("file_size"), rs.getString("source_file_sha256"),
                rs.getString("parse_status"), rs.getString("parse_message"),
                rs.getString("verification_status"), rs.getString("signature_status"),
                rs.getString("external_no"), rs.getString("direction_hint"), date(rs.getDate("issue_date")),
                date(rs.getDate("period_from")), date(rs.getDate("period_to")),
                rs.getString("accounting_entity_code"), rs.getString("accounting_entity_name"),
                rs.getString("counterparty_code"), rs.getString("counterparty_name"),
                rs.getString("bank_account_no"), rs.getString("currency"),
                rs.getBigDecimal("amount_excluding_tax"), rs.getBigDecimal("tax_amount"),
                rs.getBigDecimal("total_amount"), rs.getInt("detail_count"), rs.getString("status"),
                rs.getInt("active_relation_count"), rs.getString("created_by_name"),
                rs.getTimestamp("created_at").toLocalDateTime());
    }

    private void addEvent(Long id, String action, String from, String to, String summary) {
        SecurityContext.Principal actor = SecurityContext.require();
        jdbc.update("""
                insert into electronic_credential_event
                (credential_id,action,from_status,to_status,summary,operator_id,operator_name)
                values (?,?,?,?,?,?,?)
                """, id, action, text(from), text(to), text(summary), actor.employeeId(), actor.employeeName());
    }

    private Long entityId() {
        List<Long> rows = jdbc.queryForList("""
                select id from accounting_entity where entity_code='DEFAULT' and status='ENABLED' limit 1
                """, Long.class);
        if (rows.isEmpty()) throw new BusinessException("默认会计主体不存在或已停用");
        return rows.get(0);
    }

    private String nextNumber() {
        return "DZPZ-" + LocalDateTime.now().format(NUMBER_TIME)
                + String.format("%03d", Math.floorMod(COUNTER.incrementAndGet(), 1000));
    }

    private String proofContentType(String name, String raw, byte[] bytes) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (startsWithAscii(bytes, "%PDF-")) return MediaType.APPLICATION_PDF_VALUE;
        if (startsWith(bytes, 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)) return MediaType.IMAGE_PNG_VALUE;
        if (startsWith(bytes, 0xff, 0xd8, 0xff)) return MediaType.IMAGE_JPEG_VALUE;
        int index = startsWith(bytes, 0xef, 0xbb, 0xbf) ? 3 : 0;
        while (index < bytes.length && Character.isWhitespace((char) (bytes[index] & 0xff))) index++;
        if (index < bytes.length && bytes[index] == '<') return MediaType.APPLICATION_XML_VALUE;
        if (startsWith(bytes, 0x50, 0x4b) && lower.endsWith(".ofd")) return "application/ofd";
        throw new BusinessException("验证凭证仅支持内容真实匹配的 PDF、PNG、JPG、XML 或 OFD 文件");
    }

    private String contentType(String name, String raw, String format) {
        return switch (format) {
            case "XML" -> MediaType.APPLICATION_XML_VALUE;
            case "PDF" -> MediaType.APPLICATION_PDF_VALUE;
            case "OFD" -> "application/ofd";
            default -> StringUtils.hasText(raw) ? raw : MediaType.APPLICATION_OCTET_STREAM_VALUE;
        };
    }

    private String extension(String name, String contentType) {
        int dot = name.lastIndexOf('.');
        if (dot >= 0) {
            String ext = name.substring(dot).toLowerCase(Locale.ROOT);
            if (ext.matches("\\.[a-z0-9]{1,10}")) return ext;
        }
        return switch (contentType) {
            case MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE -> ".xml";
            case MediaType.APPLICATION_PDF_VALUE -> ".pdf";
            case MediaType.IMAGE_PNG_VALUE -> ".png";
            case MediaType.IMAGE_JPEG_VALUE -> ".jpg";
            case "application/ofd" -> ".ofd";
            default -> ".bin";
        };
    }

    private String safeFileName(String value, String fallback) {
        String name = StringUtils.hasText(value) ? value.trim() : fallback;
        name = name.replace('\\', '_').replace('/', '_').replace('\0', '_');
        return name.length() > 255 ? name.substring(name.length() - 255) : name;
    }

    private String enumValue(String value, Set<String> values, String label) {
        String normalized = required(value, label).toUpperCase(Locale.ROOT);
        if (!values.contains(normalized)) throw new BusinessException(label + "不支持：" + normalized);
        return normalized;
    }

    private String required(String value, String label) {
        if (!StringUtils.hasText(value)) throw new BusinessException(label + "不能为空");
        return value.trim();
    }

    private String text(String value) { return StringUtils.hasText(value) ? value.trim() : ""; }
    private String textOr(String value, String fallback) { return StringUtils.hasText(value) ? value.trim() : fallback; }
    private long number(Map<String, Object> row, String key) { return ((Number) row.get(key)).longValue(); }
    private LocalDate date(java.sql.Date value) { return value == null ? null : value.toLocalDate(); }
    private LocalDateTime timestamp(java.sql.Timestamp value) { return value == null ? null : value.toLocalDateTime(); }
    private String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception ex) { throw new IllegalStateException("SHA-256不可用", ex); }
    }
    private boolean startsWith(byte[] bytes, int... expected) {
        if (bytes.length < expected.length) return false;
        for (int i = 0; i < expected.length; i++) if ((bytes[i] & 0xff) != expected[i]) return false;
        return true;
    }
    private boolean startsWithAscii(byte[] bytes, String value) { return startsWith(bytes, value.chars().toArray()); }

    private record TargetDefinition(String table, String numberColumn, String dateColumn, String amountColumn) {}
    private record TargetIdentity(String documentNo) {}
}
