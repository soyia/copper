package com.soyia.copperv2.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.soyia.copperv2.dto.AccountingDtos;
import com.soyia.copperv2.security.SecurityContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
public class AccountingArchiveService {
    public static final String FORMAT_VERSION = "copper-accounting-archive/v1";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");
    private static final AtomicInteger COUNTER = new AtomicInteger();
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final LocalFileStorageService storage;
    private final StorageCapacityService capacity;

    public AccountingArchiveService(JdbcTemplate jdbc, ObjectMapper objectMapper,
                                    LocalFileStorageService storage, StorageCapacityService capacity) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.storage = storage;
        this.capacity = capacity;
    }

    public List<AccountingDtos.ArchiveView> archives() {
        return jdbc.query("""
                select a.*,p.period_name from accounting_archive_batch a
                join accounting_period p on p.id=a.period_id
                where a.entity_id=? order by a.id desc limit 200
                """, (rs, rowNum) -> new AccountingDtos.ArchiveView(
                rs.getLong("id"), rs.getLong("entity_id"), rs.getLong("period_id"),
                rs.getString("period_name"), rs.getString("archive_no"), rs.getString("archive_mode"),
                rs.getString("status"), rs.getString("format_version"), rs.getString("file_name"),
                rs.getLong("archive_bytes"), rs.getString("archive_sha256"), rs.getInt("voucher_count"),
                rs.getInt("source_attachment_count"), rs.getString("generated_by_name"),
                rs.getTimestamp("generated_at").toLocalDateTime(), timestamp(rs.getTimestamp("completed_at")),
                rs.getString("status").equals("SUPERSEDED") ? rs.getString("superseded_reason") : rs.getString("error_message"),
                Set.of("COMPLETED", "SUPERSEDED").contains(rs.getString("status"))), entityId());
    }

    public AccountingDtos.ArchiveView create(AccountingDtos.ArchiveCreate request) {
        capacity.assertLargeWriteAllowed("会计电子凭证归档");
        Period period = period(request.periodId());
        boolean finalArchive = Boolean.TRUE.equals(request.finalArchive());
        if (finalArchive && !"CLOSED".equals(period.status())) {
            throw new BusinessException("正式电子归档只能在期间结账后生成；当前可先生成预归档包检查附件完整性");
        }
        String mode = finalArchive ? "FINAL" : "PREVIEW";
        List<Voucher> vouchers = vouchers(period);
        if (vouchers.isEmpty()) throw new BusinessException("该期间没有已记账或已冲销凭证，不能生成归档包");
        String archiveNo = "ACC-ARC-" + LocalDateTime.now().format(TIME)
                + String.format("%03d", Math.floorMod(COUNTER.incrementAndGet(), 1000));
        SecurityContext.Principal actor = SecurityContext.require();
        jdbc.update("""
                insert into accounting_archive_batch
                (entity_id,period_id,archive_no,archive_mode,status,format_version,generated_by,generated_by_name)
                values (?,?,?,?,'GENERATING',?,?,?)
                """, period.entityId(), period.id(), archiveNo, mode, FORMAT_VERSION,
                actor.employeeId(), actor.employeeName());
        Long id = jdbc.queryForObject("select last_insert_id()", Long.class);
        Path temporary = null;
        String storedArchivePath = null;
        try {
            temporary = Files.createTempFile("copper-accounting-archive-", ".zip");
            ArchiveStats stats = writeArchive(temporary, period, mode, archiveNo, vouchers, actor);
            String physicalName = "accounting_archive_" + archiveNo.toLowerCase(Locale.ROOT) + ".zip";
            storedArchivePath = storage.storeGeneratedFile(temporary, "accounting-archives", physicalName);
            long archiveBytes = Files.size(temporary);
            String sha256 = sha256(temporary);
            if (finalArchive && !"CLOSED".equals(period(period.id()).status())) {
                throw new BusinessException("归档生成期间会计期间已被反结账，本次正式归档已中止");
            }
            String logicalName = "Copper_会计电子凭证_" + period.periodName().replace("年", "-").replace("月", "")
                    + "_" + (finalArchive ? "正式归档" : "预归档") + "_" + archiveNo + ".zip";
            jdbc.update("""
                    update accounting_archive_batch set status='COMPLETED',file_name=?,storage_path=?,
                      archive_bytes=?,archive_sha256=?,voucher_count=?,source_attachment_count=?,completed_at=now()
                    where id=?
                    """, logicalName, storedArchivePath, archiveBytes, sha256,
                    stats.voucherCount(), stats.attachmentCount(), id);
            return archive(id);
        } catch (Exception ex) {
            if (storedArchivePath != null) storage.deleteNow(storedArchivePath);
            jdbc.update("update accounting_archive_batch set status='FAILED',error_message=?,completed_at=now() where id=?",
                    truncate(ex.getMessage(), 1000), id);
            if (ex instanceof BusinessException business) throw business;
            throw new BusinessException("会计电子凭证归档生成失败：" + safeMessage(ex));
        } finally {
            if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
        }
    }

    public ArchiveDownload download(Long archiveId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select file_name,storage_path,archive_bytes,status from accounting_archive_batch where id=? and entity_id=?",
                archiveId, entityId());
        if (rows.isEmpty()) throw new BusinessException("电子凭证归档不存在");
        Map<String, Object> row = rows.get(0);
        if (!Set.of("COMPLETED", "SUPERSEDED").contains(String.valueOf(row.get("status"))))
            throw new BusinessException("电子凭证归档尚未完成");
        String path = String.valueOf(row.get("storage_path"));
        return new ArchiveDownload(String.valueOf(row.get("file_name")), "application/zip",
                storage.resource(path), ((Number) row.get("archive_bytes")).longValue());
    }

    private ArchiveStats writeArchive(Path target, Period period, String mode, String archiveNo,
                                      List<Voucher> vouchers, SecurityContext.Principal actor) throws IOException {
        List<Checksum> checksums = new ArrayList<>();
        List<Map<String, Object>> voucherManifest = new ArrayList<>();
        int attachmentCount = 0;
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(target)),
                StandardCharsets.UTF_8)) {
            for (Voucher voucher : vouchers) {
                String folder = period.periodName() + "/" + safePath(voucher.voucherNo() + "_"
                        + voucher.voucherDate() + "_" + voucher.summary(), 120) + "/";
                List<Map<String, Object>> lines = jdbc.queryForList("""
                        select line_no,account_code_snapshot,account_name_snapshot,summary,
                               debit_amount,credit_amount,partner_id,product_id,warehouse_id
                        from accounting_voucher_line where voucher_id=? order by line_no
                        """, voucher.id());
                Map<String, Object> voucherData = new LinkedHashMap<>();
                voucherData.put("formatVersion", FORMAT_VERSION);
                voucherData.put("voucher", voucher.asMap());
                voucherData.put("lines", lines);
                addEntry(zip, folder + "记账凭证.json",
                        objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(voucherData), checksums);
                addEntry(zip, folder + "记账凭证.html", voucherHtml(voucher, lines).getBytes(StandardCharsets.UTF_8), checksums);
                addEntry(zip, folder + "分录.csv", linesCsv(lines).getBytes(StandardCharsets.UTF_8), checksums);

                List<SourceAttachment> sources = sourceAttachments(voucher);
                for (SourceAttachment source : sources) {
                    byte[] bytes = storage.read(source.storagePath());
                    if (bytes.length != source.fileSize() || !sha256(bytes).equalsIgnoreCase(source.sha256())) {
                        throw new BusinessException("原始附件大小或SHA-256不一致：" + source.originalFileName());
                    }
                    String statusFolder = "VOIDED".equals(source.status()) ? "已作废留痕/" : "有效原始凭证/";
                    String entry = folder + statusFolder + safePath(source.archiveFileName(), 180);
                    addEntry(zip, entry, bytes, checksums);
                    attachmentCount++;
                }
                voucherManifest.add(Map.of(
                        "voucherId", voucher.id(), "voucherNo", voucher.voucherNo(),
                        "voucherDate", voucher.voucherDate().toString(), "status", voucher.status(),
                        "entryHash", voucher.entryHash(), "folder", folder,
                        "sourceAttachmentCount", sources.size()));
            }
            ComplianceArchiveStats compliance = addPeriodComplianceFiles(zip, period, checksums);
            attachmentCount += compliance.sourceFileCount();
            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("formatVersion", FORMAT_VERSION);
            manifest.put("archiveNo", archiveNo);
            manifest.put("archiveMode", mode);
            manifest.put("periodClosedArchive", "FINAL".equals(mode));
            manifest.put("entityId", period.entityId());
            manifest.put("period", Map.of("id", period.id(), "name", period.periodName(),
                    "startDate", period.startDate().toString(), "endDate", period.endDate().toString(),
                    "status", period.status()));
            manifest.put("generatedAt", LocalDateTime.now().toString());
            manifest.put("generatedBy", Map.of("employeeId", actor.employeeId(), "name", actor.employeeName()));
            manifest.put("checksumAlgorithm", "SHA-256");
            manifest.put("voucherCount", vouchers.size());
            manifest.put("sourceAttachmentCount", attachmentCount);
            manifest.put("electronicCredentialCount", compliance.credentialCount());
            manifest.put("electronicCredentials", compliance.credentials());
            manifest.put("taxWorkpaper", compliance.taxWorkpaper());
            manifest.put("vouchers", voucherManifest);
            manifest.put("notice", "FINAL仅表示来源期间已经结账；电子会计档案的合规性仍要求企业制度、来源查验、审签、重复入账控制、防篡改、备份、长期可读性及档案移交管理共同满足现行规定。");
            addEntry(zip, "manifest.json", objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest), checksums);
            StringBuilder checksumText = new StringBuilder();
            for (Checksum checksum : checksums) checksumText.append(checksum.sha256()).append("  ")
                    .append(checksum.path()).append('\n');
            addRawEntry(zip, "SHA256SUMS", checksumText.toString().getBytes(StandardCharsets.UTF_8));
        }
        return new ArchiveStats(vouchers.size(), attachmentCount);
    }

    private ComplianceArchiveStats addPeriodComplianceFiles(ZipOutputStream zip, Period period,
                                                             List<Checksum> checksums) throws IOException {
        List<Map<String, Object>> credentials = jdbc.queryForList("""
                select c.* from electronic_credential c
                where c.entity_id=? and c.status='ACTIVE' and (
                  c.issue_date between ? and ?
                  or (c.period_from is not null and c.period_to is not null
                    and c.period_from<=? and c.period_to>=?)
                  or exists (
                    select 1 from electronic_credential_relation r join tax_invoice i
                      on r.document_type='TAX_INVOICE' and i.id=r.document_id
                    where r.credential_id=c.id and r.status='ACTIVE' and i.invoice_date between ? and ?
                  )
                  or exists (
                    select 1 from electronic_credential_relation r join accounting_voucher v
                      on r.document_type='ACCOUNTING_VOUCHER' and v.id=r.document_id
                    where r.credential_id=c.id and r.status='ACTIVE' and v.period_id=?
                  )
                  or exists (
                    select 1 from electronic_credential_relation r join bank_reconciliation_session b
                      on r.document_type='BANK_RECONCILIATION' and b.id=r.document_id
                    where r.credential_id=c.id and r.status='ACTIVE' and b.period_id=?
                  )
                ) order by c.id
                """, period.entityId(), period.startDate(), period.endDate(), period.endDate(), period.startDate(),
                period.startDate(), period.endDate(), period.id(), period.id());
        List<Map<String, Object>> credentialManifest = new ArrayList<>();
        int sourceFileCount = 0;
        for (Map<String, Object> row : credentials) {
            long id = ((Number) row.get("id")).longValue();
            String originalName = String.valueOf(row.get("original_file_name"));
            byte[] original = storage.read(String.valueOf(row.get("storage_path")));
            assertStoredFile(original, ((Number) row.get("file_size")).longValue(),
                    String.valueOf(row.get("source_file_sha256")), originalName);
            String folder = period.periodName() + "/期间电子凭证/"
                    + safePath(String.valueOf(row.get("credential_no")), 80) + "/";
            addEntry(zip, folder + "原件_" + safePath(originalName, 160), original, checksums);
            sourceFileCount++;
            List<Map<String, Object>> proofs = jdbc.queryForList("""
                    select * from electronic_credential_verification where credential_id=? order by id
                    """, id);
            for (Map<String, Object> proof : proofs) {
                String proofName = String.valueOf(proof.get("proof_file_name"));
                byte[] proofBytes = storage.read(String.valueOf(proof.get("proof_storage_path")));
                assertStoredFile(proofBytes, ((Number) proof.get("proof_file_size")).longValue(),
                        String.valueOf(proof.get("proof_sha256")), proofName);
                addEntry(zip, folder + "验证证据_" + proof.get("id") + "_" + safePath(proofName, 140),
                        proofBytes, checksums);
                sourceFileCount++;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", id);
            item.put("credentialNo", row.get("credential_no"));
            item.put("credentialType", row.get("credential_type"));
            item.put("standardCode", row.get("standard_code"));
            item.put("standardVersion", row.get("standard_version"));
            item.put("externalNo", row.get("external_no"));
            item.put("parseStatus", row.get("parse_status"));
            item.put("verificationStatus", row.get("verification_status"));
            item.put("signatureStatus", row.get("signature_status"));
            item.put("sourceFileSha256", row.get("source_file_sha256"));
            item.put("verificationCount", proofs.size());
            credentialManifest.add(item);
        }

        Map<String, Object> workpaperManifest = new LinkedHashMap<>();
        List<Map<String, Object>> workpapers = jdbc.queryForList("""
                select * from tax_workpaper where entity_id=? and period_id=? and status in ('DRAFT','REVIEWED')
                order by version_no desc limit 1
                """, period.entityId(), period.id());
        if (!workpapers.isEmpty()) {
            Map<String, Object> workpaper = workpapers.get(0);
            long workpaperId = ((Number) workpaper.get("id")).longValue();
            List<Map<String, Object>> lines = jdbc.queryForList("""
                    select line_no,section_code,item_code,item_name,tax_rate,amount_excluding_tax,tax_amount,
                      total_amount,source_count,severity,formula,source_query_snapshot
                    from tax_workpaper_line where workpaper_id=? order by line_no
                    """, workpaperId);
            Map<String, Object> document = new LinkedHashMap<>();
            document.put("notice", "本文件是申报工作底稿，不是税务机关申报回执；正式申报前须由企业会计或税务专业人员复核");
            document.put("workpaper", workpaper);
            document.put("lines", lines);
            addEntry(zip, period.periodName() + "/税务工作底稿/"
                    + safePath(String.valueOf(workpaper.get("workpaper_no")), 100) + ".json",
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(document), checksums);
            workpaperManifest.put("id", workpaperId);
            workpaperManifest.put("workpaperNo", workpaper.get("workpaper_no"));
            workpaperManifest.put("status", workpaper.get("status"));
            workpaperManifest.put("ruleCode", workpaper.get("rule_code"));
            workpaperManifest.put("ruleVersion", workpaper.get("rule_version"));
            workpaperManifest.put("contentSha256", workpaper.get("content_sha256"));
        }
        return new ComplianceArchiveStats(sourceFileCount, credentials.size(), credentialManifest, workpaperManifest);
    }

    private void assertStoredFile(byte[] bytes, long expectedSize, String expectedSha, String fileName) {
        if (bytes.length != expectedSize || !sha256(bytes).equalsIgnoreCase(expectedSha)) {
            throw new BusinessException("电子凭证原件或验证证据大小/SHA-256不一致：" + fileName);
        }
    }

    private List<SourceAttachment> sourceAttachments(Voucher voucher) {
        LinkedHashMap<String, SourceAttachment> found = new LinkedHashMap<>();
        addEvidence(found, "ACCOUNTING_VOUCHER", voucher.id());
        String documentType = evidenceDocumentType(voucher.sourceType());
        if (StringUtils.hasText(documentType) && voucher.sourceId() > 0) addEvidence(found, documentType, voucher.sourceId());
        if ("INBOUND".equals(documentType)) {
            jdbc.query("""
                    select id,file_name,content_type,file_size,sha256,storage_path
                    from inbound_attachment where inbound_id=? order by id
                    """, rs -> {
                while (rs.next()) {
                    long syntheticId = -rs.getLong("id");
                    found.putIfAbsent("INBOUND_LEGACY:" + rs.getLong("id"), new SourceAttachment(syntheticId, "ACTIVE",
                            rs.getString("file_name"), "inbound_" + voucher.sourceId() + "_"
                            + rs.getLong("id") + extension(rs.getString("file_name")), rs.getString("content_type"),
                            rs.getLong("file_size"), rs.getString("sha256"), rs.getString("storage_path")));
                }
                return null;
            }, voucher.sourceId());
        }
        if ("TAX_INVOICE".equals(documentType)) addTaxInvoiceImportSource(found, voucher.sourceId());
        return List.copyOf(found.values());
    }

    private void addTaxInvoiceImportSource(Map<String, SourceAttachment> target, long invoiceId) {
        jdbc.query("""
                select b.id,b.source_file_name,b.source_file_size,b.source_file_sha256,b.source_storage_path
                from tax_invoice i join tax_invoice_import_batch b on b.id=i.import_batch_id
                where i.id=? and b.status='COMPLETED'
                """, rs -> {
            while (rs.next()) {
                long batchId = rs.getLong("id");
                String originalName = rs.getString("source_file_name");
                target.putIfAbsent("TAX_INVOICE_IMPORT:" + batchId, new SourceAttachment(-batchId, "ACTIVE",
                        originalName, "发票导入来源_" + batchId + extension(originalName), "text/csv",
                        rs.getLong("source_file_size"), rs.getString("source_file_sha256"),
                        rs.getString("source_storage_path")));
            }
            return null;
        }, invoiceId);
    }

    private void addEvidence(Map<String, SourceAttachment> target, String documentType, long documentId) {
        jdbc.query("""
                select id,status,original_file_name,archive_file_name,content_type,file_size,sha256,storage_path
                from business_evidence_attachment where document_type=? and document_id=? order by id
                """, rs -> {
            while (rs.next()) {
                long id = rs.getLong("id");
                target.putIfAbsent(documentType + ":" + id, new SourceAttachment(id, rs.getString("status"),
                        rs.getString("original_file_name"), rs.getString("archive_file_name"),
                        rs.getString("content_type"), rs.getLong("file_size"), rs.getString("sha256"),
                        rs.getString("storage_path")));
            }
            return null;
        }, documentType, documentId);
    }

    private String evidenceDocumentType(String sourceType) {
        if (!StringUtils.hasText(sourceType)) return "";
        if (sourceType.startsWith("TAX_INVOICE")) return "TAX_INVOICE";
        return switch (sourceType) {
            case "INBOUND", "OUTBOUND", "PURCHASE_ORDER", "SALES_ORDER", "SALES_RETURN",
                    "PAYMENT", "SUPPLIER_PAYMENT", "INVENTORY_TRANSFER", "INVENTORY_STOCKTAKE",
                    "PURCHASE_RETURN", "INVENTORY_COST_ADJUSTMENT", "INVENTORY_NON_TRADE",
                    "LANDED_COST_ALLOCATION", "TREASURY_DOCUMENT" -> sourceType;
            case "TREASURY_FUND" -> "";
            default -> "";
        };
    }

    private Period period(Long periodId) {
        List<Period> rows = jdbc.query("""
                select id,entity_id,period_name,start_date,end_date,status from accounting_period where id=? and entity_id=?
                """, (rs, rowNum) -> new Period(rs.getLong("id"), rs.getLong("entity_id"),
                rs.getString("period_name"), rs.getDate("start_date").toLocalDate(),
                rs.getDate("end_date").toLocalDate(), rs.getString("status")), periodId, entityId());
        if (rows.isEmpty()) throw new BusinessException("会计期间不存在");
        return rows.get(0);
    }

    private List<Voucher> vouchers(Period period) {
        return jdbc.query("""
                select id,voucher_no,voucher_type,voucher_date,summary,source_type,source_id,source_no,
                       status,debit_total,credit_total,entry_hash,maker_name,reviewer_name,poster_name,
                       created_at,reviewed_at,posted_at,reversed_at
                from accounting_voucher where entity_id=? and period_id=? and status in ('POSTED','REVERSED')
                order by voucher_date,voucher_no,id
                """, (rs, rowNum) -> new Voucher(rs.getLong("id"), rs.getString("voucher_no"),
                rs.getString("voucher_type"), rs.getDate("voucher_date").toLocalDate(), rs.getString("summary"),
                rs.getString("source_type"), rs.getLong("source_id"), rs.getString("source_no"),
                rs.getString("status"), rs.getBigDecimal("debit_total"), rs.getBigDecimal("credit_total"),
                rs.getString("entry_hash"), rs.getString("maker_name"), rs.getString("reviewer_name"),
                rs.getString("poster_name"), rs.getTimestamp("created_at").toLocalDateTime(),
                timestamp(rs.getTimestamp("reviewed_at")), timestamp(rs.getTimestamp("posted_at")),
                timestamp(rs.getTimestamp("reversed_at"))), period.entityId(), period.id());
    }

    private AccountingDtos.ArchiveView archive(Long id) {
        return archives().stream().filter(item -> item.id().equals(id)).findFirst()
                .orElseThrow(() -> new BusinessException("电子凭证归档生成后读取失败"));
    }

    private long entityId() {
        Long id = jdbc.queryForObject("select id from accounting_entity where status='ENABLED' order by id limit 1", Long.class);
        if (id == null || id <= 0) throw new BusinessException("请先启用会计主体");
        return id;
    }

    private String voucherHtml(Voucher voucher, List<Map<String, Object>> lines) {
        StringBuilder rows = new StringBuilder();
        for (Map<String, Object> line : lines) {
            rows.append("<tr><td>").append(line.get("line_no")).append("</td><td>")
                    .append(html(line.get("account_code_snapshot"))).append(" ")
                    .append(html(line.get("account_name_snapshot"))).append("</td><td>")
                    .append(html(line.get("summary"))).append("</td><td class=money>")
                    .append(html(line.get("debit_amount"))).append("</td><td class=money>")
                    .append(html(line.get("credit_amount"))).append("</td></tr>");
        }
        return """
                <!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><title>%s</title>
                <style>body{font:14px/1.5 system-ui,sans-serif;color:#111;margin:28px}h1{text-align:center}
                .meta{display:grid;grid-template-columns:repeat(3,1fr);gap:8px;margin:18px 0}table{width:100%%;border-collapse:collapse}
                th,td{border:1px solid #555;padding:7px}.money{text-align:right}.sign{display:flex;justify-content:space-between;margin-top:22px}</style>
                </head><body><h1>记账凭证</h1><div class=meta><span>凭证号：%s</span><span>日期：%s</span><span>状态：%s</span>
                <span>来源：%s</span><span>来源单号：%s</span><span>摘要：%s</span></div>
                <table><thead><tr><th>行</th><th>科目</th><th>摘要</th><th>借方</th><th>贷方</th></tr></thead><tbody>%s</tbody>
                <tfoot><tr><th colspan=3>合计</th><th class=money>%s</th><th class=money>%s</th></tr></tfoot></table>
                <div class=sign><span>制单：%s</span><span>审核：%s</span><span>记账：%s</span></div></body></html>
                """.formatted(html(voucher.voucherNo()), html(voucher.voucherNo()), voucher.voucherDate(),
                html(voucher.status()), html(voucher.sourceType()), html(voucher.sourceNo()), html(voucher.summary()),
                rows, voucher.debitTotal().toPlainString(), voucher.creditTotal().toPlainString(),
                html(voucher.makerName()), html(voucher.reviewerName()), html(voucher.posterName()));
    }

    private String linesCsv(List<Map<String, Object>> lines) {
        StringBuilder csv = new StringBuilder("\uFEFF行号,科目编码,科目名称,摘要,借方金额,贷方金额,往来单位ID,商品ID,仓库ID\n");
        for (Map<String, Object> line : lines) csv.append(csv(line.get("line_no"))).append(',')
                .append(csv(line.get("account_code_snapshot"))).append(',').append(csv(line.get("account_name_snapshot"))).append(',')
                .append(csv(line.get("summary"))).append(',').append(csv(line.get("debit_amount"))).append(',')
                .append(csv(line.get("credit_amount"))).append(',').append(csv(line.get("partner_id"))).append(',')
                .append(csv(line.get("product_id"))).append(',').append(csv(line.get("warehouse_id"))).append('\n');
        return csv.toString();
    }

    private void addEntry(ZipOutputStream zip, String path, byte[] bytes, List<Checksum> checksums) throws IOException {
        addRawEntry(zip, path, bytes);
        checksums.add(new Checksum(path, sha256(bytes)));
    }
    private void addRawEntry(ZipOutputStream zip, String path, byte[] bytes) throws IOException {
        ZipEntry entry = new ZipEntry(path);
        entry.setTime(0L);
        zip.putNextEntry(entry);
        zip.write(bytes);
        zip.closeEntry();
    }
    private String sha256(byte[] content) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA-256不可用", ex); }
    }
    private String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                for (int read; (read = input.read(buffer)) >= 0; ) if (read > 0) digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256不可用", ex);
        }
    }
    private String safePath(String value, int max) {
        String safe = StringUtils.hasText(value) ? value.trim() : "未命名";
        safe = safe.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").replaceAll("\\s+", " ");
        return safe.length() > max ? safe.substring(0, max) : safe;
    }
    private String extension(String name) {
        if (!StringUtils.hasText(name)) return "";
        int dot = name.lastIndexOf('.');
        return dot >= 0 && name.substring(dot).matches("\\.[A-Za-z0-9]{1,10}") ? name.substring(dot).toLowerCase(Locale.ROOT) : "";
    }
    private String html(Object value) { return String.valueOf(value == null ? "" : value).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;"); }
    private String csv(Object value) { String text = String.valueOf(value == null ? "" : value); return "\"" + text.replace("\"", "\"\"") + "\""; }
    private String truncate(String value, int max) { String text = StringUtils.hasText(value) ? value : "未知错误"; return text.length() > max ? text.substring(0, max) : text; }
    private String safeMessage(Exception ex) { return StringUtils.hasText(ex.getMessage()) ? ex.getMessage() : ex.getClass().getSimpleName(); }
    private LocalDateTime timestamp(Timestamp value) { return value == null ? null : value.toLocalDateTime(); }

    private record Period(Long id, Long entityId, String periodName, LocalDate startDate, LocalDate endDate, String status) {}
    private record Voucher(Long id, String voucherNo, String voucherType, LocalDate voucherDate,
                           String summary, String sourceType, Long sourceId, String sourceNo, String status,
                           BigDecimal debitTotal, BigDecimal creditTotal, String entryHash,
                           String makerName, String reviewerName, String posterName,
                           LocalDateTime createdAt, LocalDateTime reviewedAt, LocalDateTime postedAt,
                           LocalDateTime reversedAt) {
        Map<String, Object> asMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", id); map.put("voucherNo", voucherNo); map.put("voucherType", voucherType);
            map.put("voucherDate", voucherDate); map.put("summary", summary); map.put("sourceType", sourceType);
            map.put("sourceId", sourceId); map.put("sourceNo", sourceNo); map.put("status", status);
            map.put("debitTotal", debitTotal); map.put("creditTotal", creditTotal); map.put("entryHash", entryHash);
            map.put("makerName", makerName); map.put("reviewerName", reviewerName); map.put("posterName", posterName);
            map.put("createdAt", createdAt); map.put("reviewedAt", reviewedAt); map.put("postedAt", postedAt);
            map.put("reversedAt", reversedAt); return map;
        }
    }
    private record SourceAttachment(Long id, String status, String originalFileName, String archiveFileName,
                                    String contentType, Long fileSize, String sha256, String storagePath) {}
    private record Checksum(String path, String sha256) {}
    private record ArchiveStats(int voucherCount, int attachmentCount) {}
    private record ComplianceArchiveStats(int sourceFileCount, int credentialCount,
                                          List<Map<String, Object>> credentials,
                                          Map<String, Object> taxWorkpaper) {}
    public record ArchiveDownload(String fileName, String contentType, Resource resource, long contentLength) {}
}
