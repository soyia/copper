package com.soyia.copperv2.service;

import com.soyia.copperv2.dto.EvidenceDtos;
import com.soyia.copperv2.security.BusinessDataScopeService;
import com.soyia.copperv2.security.ForbiddenException;
import com.soyia.copperv2.security.SecurityContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class EvidenceAttachmentService {
    private static final long MAX_FILE_SIZE = 30L * 1024 * 1024;
    private static final int MAX_ACTIVE_FILES = 30;
    private static final Set<String> EVIDENCE_TYPES = Set.of(
            "ORIGINAL_DOCUMENT", "SIGNED_RECEIPT", "DELIVERY_PROOF", "PAYMENT_PROOF",
            "VERIFICATION_PROOF", "SIGNED_STAMPED_COPY", "VISUAL_COPY", "OTHER");
    private static final Set<String> OUTPUT_TYPES = Set.of("PRINT_OR_PDF");
    private static final Set<String> SOURCE_CHANNELS = Set.of(
            "UPLOAD", "TAX_DIGITAL_ACCOUNT", "IMPORT", "SCAN", "SYSTEM");
    private static final Set<String> ALLOWED_TYPES = Set.of(
            MediaType.IMAGE_JPEG_VALUE, MediaType.IMAGE_PNG_VALUE, "image/webp",
            MediaType.APPLICATION_PDF_VALUE, MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE,
            "application/ofd");

    private static final Map<String, DocumentDefinition> DOCUMENTS = Map.ofEntries(
            Map.entry("INBOUND", new DocumentDefinition("inbound_order", "status", "order_no", "inbound.view", "inbound.manage", true)),
            Map.entry("OUTBOUND", new DocumentDefinition("outbound_order", "status", "order_no", "outbound.view", "outbound.manage", true)),
            Map.entry("PURCHASE_ORDER", new DocumentDefinition("purchase_order", "status", "order_no", "purchase.view", "purchase.manage", true)),
            Map.entry("SALES_ORDER", new DocumentDefinition("sales_order", "status", "order_no", "sales.view", "sales.manage", true)),
            Map.entry("SALES_RETURN", new DocumentDefinition("sales_return_order", "status", "return_no", "outbound.view", "outbound.manage", true)),
            Map.entry("PAYMENT", new DocumentDefinition("payment", "verification_status", "payment_no", "finance.view", "finance.manage", true)),
            Map.entry("SUPPLIER_PAYMENT", new DocumentDefinition("supplier_payment", "verification_status", "payment_no", "finance.view", "finance.manage", true)),
            Map.entry("INVENTORY_TRANSFER", new DocumentDefinition("inventory_transfer_order", "status", "order_no", "inventory.view", "inventory.transfer", true)),
            Map.entry("INVENTORY_STOCKTAKE", new DocumentDefinition("inventory_stocktake_order", "status", "order_no", "inventory.view", "inventory.stocktake", true)),
            Map.entry("PURCHASE_RETURN", new DocumentDefinition("purchase_return_order", "status", "order_no", "purchase.view", "purchase.return", true)),
            Map.entry("INVENTORY_COST_ADJUSTMENT", new DocumentDefinition("inventory_cost_adjustment", "status", "adjustment_no", "inventory.cost.view", "inventory.cost.adjust", true)),
            Map.entry("INVENTORY_NON_TRADE", new DocumentDefinition("inventory_non_trade_document", "status", "document_no", "inventory.nontrade.view", "inventory.nontrade.manage", true)),
            Map.entry("LANDED_COST_ALLOCATION", new DocumentDefinition("landed_cost_allocation", "status", "allocation_no", "landed.cost.view", "landed.cost.manage", true)),
            Map.entry("PARTNER_FUND_ADJUSTMENT", new DocumentDefinition("partner_fund_adjustment", "status", "adjustment_no", "partner.fund.adjust.view", "partner.fund.adjust.manage", true)),
            Map.entry("TREASURY_DOCUMENT", new DocumentDefinition("treasury_document", "status", "document_no", "treasury.view", "treasury.document.manage", true)),
            Map.entry("TAX_INVOICE", new DocumentDefinition("tax_invoice", "status", "internal_no", "invoice.view", "invoice.manage", true)),
            Map.entry("ELECTRONIC_CREDENTIAL", new DocumentDefinition("electronic_credential", "status", "credential_no", "tax.compliance.view", "tax.compliance.manage", false)),
            Map.entry("TAX_WORKPAPER", new DocumentDefinition("tax_workpaper", "status", "workpaper_no", "tax.compliance.view", "tax.compliance.review", false)),
            Map.entry("ACCOUNTING_VOUCHER", new DocumentDefinition("accounting_voucher", "status", "voucher_no", "accounting.view", "accounting.manage", false)),
            Map.entry("BANK_RECONCILIATION", new DocumentDefinition("bank_reconciliation_session", "status", "reconciliation_no", "financial.bank.view", "financial.bank.manage", false)),
            Map.entry("PERIOD_CLOSE", new DocumentDefinition("accounting_period", "status", "period_name", "financial.report.view", "financial.close.manage", false))
    );

    private final JdbcTemplate jdbc;
    private final LocalFileStorageService storage;
    private final StorageCapacityService capacity;
    private final BusinessDataScopeService dataScope;

    public EvidenceAttachmentService(JdbcTemplate jdbc, LocalFileStorageService storage,
                                     StorageCapacityService capacity, BusinessDataScopeService dataScope) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.capacity = capacity;
        this.dataScope = dataScope;
    }

    public List<EvidenceDtos.AttachmentView> list(String documentType, Long documentId) {
        DocumentContext context = requireDocument(documentType, documentId, false);
        return jdbc.query("""
                select * from business_evidence_attachment
                where document_type=? and document_id=? order by id
                """, (rs, rowNum) -> new EvidenceDtos.AttachmentView(
                rs.getLong("id"), rs.getString("document_type"), rs.getLong("document_id"),
                rs.getString("evidence_type"), rs.getString("source_channel"),
                rs.getString("original_file_name"), rs.getString("archive_file_name"),
                rs.getString("content_type"), rs.getLong("file_size"), rs.getString("sha256"),
                rs.getString("status"), rs.getString("note"), rs.getString("uploaded_by_name"),
                rs.getTimestamp("uploaded_at").toLocalDateTime(), rs.getString("voided_by_name"),
                rs.getString("void_reason"), timestamp(rs.getTimestamp("voided_at")),
                context.draft() && "ACTIVE".equals(rs.getString("status"))
        ), context.type(), documentId);
    }

    public List<EvidenceDtos.OutputRecordView> outputRecords(String documentType, Long documentId) {
        DocumentContext context = requireDocument(documentType, documentId, false);
        return jdbc.query("""
                select * from business_document_output_log
                where document_type=? and document_id=? order by id desc
                """, (rs, rowNum) -> new EvidenceDtos.OutputRecordView(
                rs.getLong("id"), rs.getString("document_type"), rs.getLong("document_id"),
                rs.getString("document_no_snapshot"), rs.getString("status_snapshot"),
                rs.getString("output_type"), rs.getString("template_version"),
                rs.getString("output_file_name"), rs.getString("snapshot_hash"),
                rs.getString("operator_name"), rs.getTimestamp("created_at").toLocalDateTime()
        ), context.type(), documentId);
    }

    @Transactional
    public EvidenceDtos.OutputRecordView recordOutput(String documentType, Long documentId,
                                                       EvidenceDtos.OutputRecordRequest request) {
        DocumentContext context = requireDocument(documentType, documentId, false);
        String outputType = enumValue(request.outputType(), OUTPUT_TYPES, "输出类型", "PRINT_OR_PDF");
        String templateVersion = required(request.templateVersion(), "打印模板版本");
        String outputFileName = safeFileName(required(request.outputFileName(), "输出文件名"), "text/html");
        SecurityContext.Principal actor = SecurityContext.require();
        jdbc.update("""
                insert into business_document_output_log
                (document_type,document_id,document_no_snapshot,status_snapshot,output_type,
                 template_version,output_file_name,snapshot_hash,operator_id,operator_name)
                values (?,?,?,?,?,?,?,?,?,?)
                """, context.type(), documentId, context.documentNo(), context.status(), outputType,
                templateVersion, outputFileName, request.snapshotHash(), actor.employeeId(), actor.employeeName());
        Long id = jdbc.queryForObject("select last_insert_id()", Long.class);
        return outputRecords(context.type(), documentId).stream().filter(item -> item.id().equals(id)).findFirst()
                .orElseThrow(() -> new BusinessException("打印留痕保存后读取失败"));
    }

    @Transactional
    public EvidenceDtos.AttachmentView upload(String documentType, Long documentId, String evidenceType,
                                               String sourceChannel, String note, MultipartFile file) {
        capacity.assertLargeWriteAllowed("电子凭据上传");
        DocumentContext context = requireDocument(documentType, documentId, true);
        String normalizedEvidence = enumValue(evidenceType, EVIDENCE_TYPES, "凭据类型", "OTHER");
        String normalizedSource = enumValue(sourceChannel, SOURCE_CHANNELS, "来源渠道", "UPLOAD");
        if (file == null || file.isEmpty()) throw new BusinessException("请选择需要留存的原始文件");
        if (file.getSize() > MAX_FILE_SIZE) throw new BusinessException("单个附件不能超过30MB");
        String contentType = normalizedContentType(file);
        if (!ALLOWED_TYPES.contains(contentType)) {
            throw new BusinessException("仅支持 JPG、PNG、WebP、PDF、OFD 和 XML 原始文件");
        }
        Integer count = jdbc.queryForObject("""
                select count(*) from business_evidence_attachment
                where document_type=? and document_id=? and status='ACTIVE'
                """, Integer.class, context.type(), documentId);
        if (count != null && count >= MAX_ACTIVE_FILES) throw new BusinessException("每张业务单据最多保留30个有效附件");
        try {
            byte[] bytes = file.getBytes();
            validateFileSignature(bytes, contentType);
            String sha256 = sha256(bytes);
            Integer duplicate = jdbc.queryForObject("""
                    select count(*) from business_evidence_attachment
                    where document_type=? and document_id=? and sha256=? and status='ACTIVE'
                    """, Integer.class, context.type(), documentId, sha256);
            if (duplicate != null && duplicate > 0) throw new BusinessException("同一原始文件已经上传，无需重复添加");
            String originalName = safeFileName(file.getOriginalFilename(), contentType);
            String storagePath = storage.store(bytes,
                    "evidence/" + context.type().toLowerCase(Locale.ROOT) + "/" + documentId,
                    extension(originalName, contentType));
            SecurityContext.Principal actor = SecurityContext.require();
            jdbc.update("""
                    insert into business_evidence_attachment
                    (document_type,document_id,evidence_type,source_channel,original_file_name,
                     content_type,file_size,sha256,storage_path,note,uploaded_by,uploaded_by_name)
                    values (?,?,?,?,?,?,?,?,?,?,?,?)
                    """, context.type(), documentId, normalizedEvidence, normalizedSource, originalName,
                    contentType, bytes.length, sha256, storagePath, text(note), actor.employeeId(), actor.employeeName());
            Long id = jdbc.queryForObject("select last_insert_id()", Long.class);
            String archiveName = context.type().toLowerCase(Locale.ROOT) + "_" + documentId + "_"
                    + String.format("%06d", id) + extension(originalName, contentType);
            jdbc.update("update business_evidence_attachment set archive_file_name=? where id=?", archiveName, id);
            return list(context.type(), documentId).stream().filter(item -> item.id().equals(id)).findFirst()
                    .orElseThrow(() -> new BusinessException("附件保存后读取失败"));
        } catch (DuplicateKeyException ex) {
            throw new BusinessException("同一原始文件已经上传，无需重复添加");
        } catch (IOException ex) {
            throw new BusinessException("附件读取失败");
        }
    }

    public EvidenceDtos.AttachmentContent content(String documentType, Long documentId, Long attachmentId) {
        DocumentContext context = requireDocument(documentType, documentId, false);
        List<StoredAttachment> rows = jdbc.query("""
                select original_file_name,content_type,storage_path from business_evidence_attachment
                where id=? and document_type=? and document_id=?
                """, (rs, rowNum) -> new StoredAttachment(rs.getString("original_file_name"),
                rs.getString("content_type"), rs.getString("storage_path")),
                attachmentId, context.type(), documentId);
        if (rows.isEmpty()) throw new BusinessException("附件不存在");
        StoredAttachment item = rows.get(0);
        return new EvidenceDtos.AttachmentContent(item.fileName(), item.contentType(), storage.read(item.storagePath()));
    }

    @Transactional
    public Long removeDraftAttachment(String documentType, Long documentId, Long attachmentId) {
        DocumentContext context = requireDocument(documentType, documentId, true);
        if (!context.draft()) {
            throw new BusinessException("单据已经确认、审核或无需审核，附件不能删除；如凭据有误请留痕作废后补传正确文件");
        }
        List<String> paths = jdbc.query("""
                select storage_path from business_evidence_attachment
                where id=? and document_type=? and document_id=? and status='ACTIVE'
                """, (rs, rowNum) -> rs.getString(1), attachmentId, context.type(), documentId);
        int changed = jdbc.update("""
                delete from business_evidence_attachment
                where id=? and document_type=? and document_id=? and status='ACTIVE'
                """, attachmentId, context.type(), documentId);
        if (changed != 1) throw new BusinessException("可删除附件不存在");
        if (!paths.isEmpty()) storage.deleteAfterTransactionCommit(paths.get(0));
        return attachmentId;
    }

    @Transactional
    public Long voidAttachment(String documentType, Long documentId, Long attachmentId, String reason) {
        DocumentContext context = requireDocument(documentType, documentId, true);
        if (context.draft()) throw new BusinessException("草稿附件请直接删除；确认后的凭据才使用留痕作废");
        String normalizedReason = required(reason, "附件作废原因");
        SecurityContext.Principal actor = SecurityContext.require();
        int changed = jdbc.update("""
                update business_evidence_attachment
                set status='VOIDED',voided_by=?,voided_by_name=?,void_reason=?,voided_at=now()
                where id=? and document_type=? and document_id=? and status='ACTIVE'
                """, actor.employeeId(), actor.employeeName(), normalizedReason,
                attachmentId, context.type(), documentId);
        if (changed != 1) throw new BusinessException("有效附件不存在或已经作废");
        return attachmentId;
    }

    public AttachmentRecord requiredActive(Long attachmentId, String documentType, Long documentId,
                                           String requiredEvidenceType) {
        List<AttachmentRecord> rows = jdbc.query("""
                select id,evidence_type,sha256,storage_path from business_evidence_attachment
                where id=? and document_type=? and document_id=? and status='ACTIVE'
                for update
                """, (rs, rowNum) -> new AttachmentRecord(rs.getLong("id"), rs.getString("evidence_type"),
                rs.getString("sha256"), rs.getString("storage_path")),
                attachmentId, documentType, documentId);
        if (rows.isEmpty()) throw new BusinessException("查验凭证附件不存在或已经作废");
        AttachmentRecord record = rows.get(0);
        if (!requiredEvidenceType.equals(record.evidenceType())) {
            throw new BusinessException("请选择“查验凭证”类型的附件");
        }
        if (!storage.exists(record.storagePath())) throw new BusinessException("查验凭证原始文件缺失");
        return record;
    }

    public AttachmentRecord requiredActiveOneOf(Long attachmentId, String documentType, Long documentId,
                                                Set<String> allowedEvidenceTypes, String evidenceLabel) {
        if (attachmentId == null || attachmentId <= 0) {
            throw new BusinessException("请上传并选择" + evidenceLabel);
        }
        List<AttachmentRecord> rows = jdbc.query("""
                select id,evidence_type,sha256,storage_path from business_evidence_attachment
                where id=? and document_type=? and document_id=? and status='ACTIVE'
                for update
                """, (rs, rowNum) -> new AttachmentRecord(rs.getLong("id"), rs.getString("evidence_type"),
                rs.getString("sha256"), rs.getString("storage_path")),
                attachmentId, documentType, documentId);
        if (rows.isEmpty()) throw new BusinessException(evidenceLabel + "不存在或已经作废");
        AttachmentRecord record = rows.get(0);
        if (!allowedEvidenceTypes.contains(record.evidenceType())) {
            throw new BusinessException("请选择“签字 / 签收单”或“签字盖章归档件”类型的附件");
        }
        if (!storage.exists(record.storagePath())) throw new BusinessException(evidenceLabel + "原始文件缺失");
        return record;
    }

    private DocumentContext requireDocument(String rawType, Long documentId, boolean write) {
        String type = StringUtils.hasText(rawType) ? rawType.trim().toUpperCase(Locale.ROOT) : "";
        DocumentDefinition definition = DOCUMENTS.get(type);
        if (definition == null) throw new BusinessException("不支持该业务单据的附件留存");
        if (documentId == null || documentId <= 0) throw new BusinessException("业务单据ID无效");
        String permission = write ? definition.managePermission() : definition.viewPermission();
        if (!SecurityContext.require().hasPermission(permission)) throw new ForbiddenException("无权查看或维护该业务单据附件");
        if (definition.scoped()) dataScope.assertDocumentAllowed(type, documentId);
        List<DocumentIdentity> identities = jdbc.query("select " + definition.statusColumn() + ","
                        + definition.numberColumn() + " from " + definition.table() + " where id=?",
                (rs, rowNum) -> new DocumentIdentity(rs.getString(1), rs.getString(2)), documentId);
        if (identities.isEmpty()) throw new BusinessException("业务单据不存在");
        DocumentIdentity identity = identities.get(0);
        return new DocumentContext(type, identity.status(), identity.documentNo(), "DRAFT".equals(identity.status()));
    }

    private String normalizedContentType(MultipartFile file) {
        String value = StringUtils.hasText(file.getContentType()) ? file.getContentType().toLowerCase(Locale.ROOT) : "";
        if (value.contains(";")) value = value.substring(0, value.indexOf(';')).trim();
        String name = StringUtils.hasText(file.getOriginalFilename()) ? file.getOriginalFilename().toLowerCase(Locale.ROOT) : "";
        if (value.isBlank() || MediaType.APPLICATION_OCTET_STREAM_VALUE.equals(value)) {
            if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return MediaType.IMAGE_JPEG_VALUE;
            if (name.endsWith(".png")) return MediaType.IMAGE_PNG_VALUE;
            if (name.endsWith(".webp")) return "image/webp";
            if (name.endsWith(".pdf")) return MediaType.APPLICATION_PDF_VALUE;
            if (name.endsWith(".xml")) return MediaType.APPLICATION_XML_VALUE;
            if (name.endsWith(".ofd")) return "application/ofd";
        }
        return value;
    }

    private void validateFileSignature(byte[] bytes, String contentType) {
        boolean valid = switch (contentType) {
            case MediaType.IMAGE_JPEG_VALUE -> startsWith(bytes, 0xff, 0xd8, 0xff);
            case MediaType.IMAGE_PNG_VALUE -> startsWith(bytes, 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a);
            case "image/webp" -> startsWithAscii(bytes, "RIFF") && bytes.length >= 12
                    && new String(bytes, 8, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("WEBP");
            case MediaType.APPLICATION_PDF_VALUE -> startsWithAscii(bytes, "%PDF-");
            case MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE -> looksLikeXml(bytes);
            case "application/ofd" -> startsWith(bytes, 0x50, 0x4b);
            default -> false;
        };
        if (!valid) throw new BusinessException("文件内容与 JPG、PNG、WebP、PDF、OFD 或 XML 类型不一致");
    }

    private boolean startsWith(byte[] bytes, int... expected) {
        if (bytes.length < expected.length) return false;
        for (int i = 0; i < expected.length; i++) if ((bytes[i] & 0xff) != expected[i]) return false;
        return true;
    }

    private boolean startsWithAscii(byte[] bytes, String expected) {
        return startsWith(bytes, expected.chars().toArray());
    }

    private boolean looksLikeXml(byte[] bytes) {
        int index = 0;
        if (startsWith(bytes, 0xef, 0xbb, 0xbf)) index = 3;
        while (index < bytes.length && Character.isWhitespace((char) (bytes[index] & 0xff))) index++;
        return index < bytes.length && bytes[index] == '<';
    }

    private String safeFileName(String value, String contentType) {
        String name = StringUtils.hasText(value) ? value.trim() : "evidence" + extension("", contentType);
        name = name.replace('\\', '_').replace('/', '_').replace('\0', '_');
        return name.length() > 255 ? name.substring(name.length() - 255) : name;
    }

    private String extension(String fileName, String contentType) {
        int dot = fileName.lastIndexOf('.');
        if (dot >= 0 && dot < fileName.length() - 1) {
            String extension = fileName.substring(dot).toLowerCase(Locale.ROOT);
            if (extension.matches("\\.[a-z0-9]{1,10}")) return extension;
        }
        return switch (contentType) {
            case MediaType.IMAGE_JPEG_VALUE -> ".jpg";
            case MediaType.IMAGE_PNG_VALUE -> ".png";
            case "image/webp" -> ".webp";
            case MediaType.APPLICATION_PDF_VALUE -> ".pdf";
            case MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE -> ".xml";
            case "application/ofd" -> ".ofd";
            default -> ".bin";
        };
    }

    private String enumValue(String value, Set<String> allowed, String label, String fallback) {
        String normalized = StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : fallback;
        if (!allowed.contains(normalized)) throw new BusinessException(label + "不支持：" + normalized);
        return normalized;
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256不可用", ex);
        }
    }

    private String text(String value) { return StringUtils.hasText(value) ? value.trim() : ""; }
    private String required(String value, String label) {
        if (!StringUtils.hasText(value)) throw new BusinessException(label + "不能为空");
        return value.trim();
    }
    private LocalDateTime timestamp(java.sql.Timestamp value) { return value == null ? null : value.toLocalDateTime(); }

    private record DocumentDefinition(String table, String statusColumn, String numberColumn,
                                      String viewPermission, String managePermission, boolean scoped) {}
    private record DocumentIdentity(String status, String documentNo) {}
    private record DocumentContext(String type, String status, String documentNo, boolean draft) {}
    private record StoredAttachment(String fileName, String contentType, String storagePath) {}
    public record AttachmentRecord(Long id, String evidenceType, String sha256, String storagePath) {}
}
