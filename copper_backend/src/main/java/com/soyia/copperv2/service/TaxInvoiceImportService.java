package com.soyia.copperv2.service;

import com.soyia.copperv2.dto.TaxInvoiceDtos;
import com.soyia.copperv2.security.SecurityContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class TaxInvoiceImportService {
    public static final String TEMPLATE_VERSION = "copper-tax-invoice-csv/v1";
    private static final int MAX_ROWS = 2000;
    private static final int MAX_INVOICES = 500;
    private static final AtomicInteger COUNTER = new AtomicInteger();
    private static final List<String> HEADERS = List.of(
            "direction", "invoice_color", "invoice_type", "invoice_code", "invoice_number", "blue_invoice_id",
            "partner_id", "issuer_name", "issuer_tax_no", "recipient_name", "recipient_tax_no",
            "invoice_date", "received_or_issued_date", "product_name", "product_sku", "product_spec",
            "unit", "quantity", "unit_price_excluding_tax", "amount_excluding_tax",
            "tax_rate", "tax_classification_code", "tax_amount", "total_amount", "memo");

    private final JdbcTemplate jdbc;
    private final TaxInvoiceService invoiceService;
    private final LocalFileStorageService storage;
    private final StorageCapacityService capacity;

    public TaxInvoiceImportService(JdbcTemplate jdbc, TaxInvoiceService invoiceService,
                                   LocalFileStorageService storage, StorageCapacityService capacity) {
        this.jdbc = jdbc;
        this.invoiceService = invoiceService;
        this.storage = storage;
        this.capacity = capacity;
    }

    public byte[] template() {
        String description = "# " + TEMPLATE_VERSION
                + "；每行一条发票明细；同一发票的抬头字段必须重复且一致；导入仅生成草稿。\n"
                + "# direction填INPUT或OUTPUT；invoice_type填DIGITAL_SPECIAL、DIGITAL_NORMAL、VAT_SPECIAL、VAT_NORMAL或OTHER；tax_rate直接填13、9、6、3、0等有效税率。\n"
                + "# 红字票必须填写原蓝字发票的blue_invoice_id；蓝字票留空。partner_id可从往来单位详情取得。请从下一行开始录入，勿改列名。\n";
        return ("\uFEFF" + description + String.join(",", HEADERS) + "\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    @Transactional
    public TaxInvoiceDtos.ImportResult importCsv(MultipartFile file) {
        capacity.assertLargeWriteAllowed("发票模板导入");
        if (file == null || file.isEmpty()) throw new BusinessException("请选择标准CSV模板文件");
        if (file.getSize() > 10L * 1024 * 1024) throw new BusinessException("发票导入文件不能超过10MB");
        String fileName = safeFileName(file.getOriginalFilename());
        if (!fileName.toLowerCase(Locale.ROOT).endsWith(".csv")) throw new BusinessException("仅支持标准CSV模板导入");
        try {
            byte[] bytes = file.getBytes();
            String sha = sha256(bytes);
            String content = new String(bytes, StandardCharsets.UTF_8).replace("\uFEFF", "");
            List<List<String>> rows = parseCsv(content).stream()
                    .filter(row -> !row.isEmpty() && !row.get(0).trim().startsWith("#"))
                    .toList();
            if (rows.size() < 2) throw new BusinessException("模板中没有可导入的发票明细");
            Map<String, Integer> columns = headerMap(rows.get(0));
            if (rows.size() - 1 > MAX_ROWS) throw new BusinessException("单次最多导入" + MAX_ROWS + "条发票明细");
            String batchNo = "TI-IMP-" + java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS"))
                    + String.format("%03d", Math.floorMod(COUNTER.incrementAndGet(), 1000));
            String storagePath = storage.store(bytes, "tax-invoice-imports", ".csv");
            SecurityContext.Principal actor = SecurityContext.require();
            jdbc.update("""
                    insert into tax_invoice_import_batch
                    (batch_no,template_version,source_file_name,source_file_sha256,source_file_size,
                     source_storage_path,status,imported_by,imported_by_name)
                    values (?,?,?,?,?,?,'PROCESSING',?,?)
                    """, batchNo, TEMPLATE_VERSION, fileName, sha, bytes.length, storagePath,
                    actor.employeeId(), actor.employeeName());
            Long batchId = jdbc.queryForObject("select last_insert_id()", Long.class);
            LinkedHashMap<String, InvoiceGroup> groups = groupRows(rows.subList(1, rows.size()), columns);
            if (groups.size() > MAX_INVOICES) throw new BusinessException("单次最多导入" + MAX_INVOICES + "张发票");
            List<Long> ids = new ArrayList<>();
            int lineCount = 0;
            for (InvoiceGroup group : groups.values()) {
                TaxInvoiceDtos.InvoiceSave request = group.toRequest(fileName, sha);
                Long invoiceId = invoiceService.saveInvoice(null, request);
                jdbc.update("update tax_invoice set import_batch_id=? where id=?", batchId, invoiceId);
                ids.add(invoiceId);
                lineCount += group.lines().size();
            }
            jdbc.update("""
                    update tax_invoice_import_batch set status='COMPLETED',imported_invoice_count=?,
                      imported_line_count=?,completed_at=now() where id=?
                    """, ids.size(), lineCount, batchId);
            return new TaxInvoiceDtos.ImportResult(batchId, batchNo, ids.size(), lineCount, ids);
        } catch (IOException ex) {
            throw new BusinessException("发票导入文件读取失败");
        }
    }

    private LinkedHashMap<String, InvoiceGroup> groupRows(List<List<String>> rows, Map<String, Integer> columns) {
        LinkedHashMap<String, InvoiceGroup> groups = new LinkedHashMap<>();
        int rowNo = 1;
        for (List<String> row : rows) {
            rowNo++;
            if (row.stream().allMatch(value -> value == null || value.isBlank())) continue;
            String direction = value(row, columns, "direction", rowNo).toUpperCase(Locale.ROOT);
            String color = value(row, columns, "invoice_color", rowNo).toUpperCase(Locale.ROOT);
            String code = optional(row, columns, "invoice_code");
            String number = value(row, columns, "invoice_number", rowNo);
            String key = direction + "|" + code + "|" + number;
            InvoiceHeader header = new InvoiceHeader(direction, color,
                    value(row, columns, "invoice_type", rowNo).toUpperCase(Locale.ROOT), code, number,
                    optionalLong(row, columns, "blue_invoice_id", rowNo),
                    longValue(row, columns, "partner_id", rowNo),
                    value(row, columns, "issuer_name", rowNo), optional(row, columns, "issuer_tax_no"),
                    value(row, columns, "recipient_name", rowNo), optional(row, columns, "recipient_tax_no"),
                    dateValue(row, columns, "invoice_date", rowNo),
                    dateValue(row, columns, "received_or_issued_date", rowNo),
                    optional(row, columns, "memo"));
            TaxInvoiceDtos.InvoiceLineSave line = new TaxInvoiceDtos.InvoiceLineSave(null,
                    value(row, columns, "product_name", rowNo), optional(row, columns, "product_sku"),
                    optional(row, columns, "product_spec"), optional(row, columns, "unit"),
                    decimal(row, columns, "quantity", rowNo),
                    decimal(row, columns, "unit_price_excluding_tax", rowNo),
                    decimal(row, columns, "amount_excluding_tax", rowNo),
                    taxRateRuleId(direction, header.invoiceDate(), decimal(row, columns, "tax_rate", rowNo), rowNo),
                    optional(row, columns, "tax_classification_code"),
                    decimal(row, columns, "tax_amount", rowNo),
                    decimal(row, columns, "total_amount", rowNo));
            InvoiceGroup group = groups.computeIfAbsent(key, ignored -> new InvoiceGroup(header, new ArrayList<>()));
            if (!group.header().equals(header)) throw new BusinessException("第" + rowNo + "行同一发票的抬头字段不一致");
            group.lines().add(line);
        }
        if (groups.isEmpty()) throw new BusinessException("模板中没有可导入的有效发票");
        return groups;
    }

    private Map<String, Integer> headerMap(List<String> row) {
        Map<String, Integer> columns = new LinkedHashMap<>();
        for (int i = 0; i < row.size(); i++) columns.put(row.get(i).trim().toLowerCase(Locale.ROOT), i);
        List<String> missing = HEADERS.stream().filter(header -> !columns.containsKey(header)).toList();
        if (!missing.isEmpty()) throw new BusinessException("模板版本不正确，缺少列：" + String.join(",", missing));
        return columns;
    }

    private List<List<String>> parseCsv(String content) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder value = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < content.length(); i++) {
            char current = content.charAt(i);
            if (quoted) {
                if (current == '"' && i + 1 < content.length() && content.charAt(i + 1) == '"') {
                    value.append('"'); i++;
                } else if (current == '"') quoted = false;
                else value.append(current);
            } else if (current == '"') quoted = true;
            else if (current == ',') { row.add(value.toString().trim()); value.setLength(0); }
            else if (current == '\n' || current == '\r') {
                if (current == '\r' && i + 1 < content.length() && content.charAt(i + 1) == '\n') i++;
                row.add(value.toString().trim()); value.setLength(0); rows.add(row); row = new ArrayList<>();
            } else value.append(current);
        }
        if (quoted) throw new BusinessException("CSV存在未闭合的引号");
        if (value.length() > 0 || !row.isEmpty()) { row.add(value.toString().trim()); rows.add(row); }
        return rows;
    }

    private String value(List<String> row, Map<String, Integer> columns, String name, int rowNo) {
        String value = optional(row, columns, name);
        if (!StringUtils.hasText(value)) throw new BusinessException("第" + rowNo + "行“" + name + "”不能为空");
        return value.trim();
    }
    private String optional(List<String> row, Map<String, Integer> columns, String name) {
        int index = columns.get(name);
        return index < row.size() ? row.get(index).trim() : "";
    }
    private Long longValue(List<String> row, Map<String, Integer> columns, String name, int rowNo) {
        try { return Long.valueOf(value(row, columns, name, rowNo)); }
        catch (NumberFormatException ex) { throw new BusinessException("第" + rowNo + "行“" + name + "”必须为整数ID"); }
    }
    private Long optionalLong(List<String> row, Map<String, Integer> columns, String name, int rowNo) {
        String value = optional(row, columns, name);
        if (!StringUtils.hasText(value)) return null;
        try { return Long.valueOf(value); }
        catch (NumberFormatException ex) { throw new BusinessException("第" + rowNo + "行“" + name + "”必须为整数ID"); }
    }
    private Long taxRateRuleId(String direction, LocalDate invoiceDate, BigDecimal rate, int rowNo) {
        List<Long> ids = jdbc.queryForList("""
                select id from tax_rate_rule where status='ENABLED' and tax_rate=?
                  and effective_from<=? and (effective_to is null or effective_to>=?)
                  and invoice_scope in ('BOTH',?) order by version_no desc,id desc limit 1
                """, Long.class, rate, invoiceDate, invoiceDate, direction);
        if (ids.isEmpty()) throw new BusinessException("第" + rowNo + "行税率" + rate.stripTrailingZeros().toPlainString()
                + "%在开票日期没有有效规则，请先维护税率规则");
        return ids.get(0);
    }
    private BigDecimal decimal(List<String> row, Map<String, Integer> columns, String name, int rowNo) {
        try { return new BigDecimal(value(row, columns, name, rowNo)); }
        catch (NumberFormatException ex) { throw new BusinessException("第" + rowNo + "行“" + name + "”金额或数量格式错误"); }
    }
    private LocalDate dateValue(List<String> row, Map<String, Integer> columns, String name, int rowNo) {
        try { return LocalDate.parse(value(row, columns, name, rowNo)); }
        catch (DateTimeParseException ex) { throw new BusinessException("第" + rowNo + "行“" + name + "”必须为YYYY-MM-DD"); }
    }
    private String safeFileName(String value) {
        String name = StringUtils.hasText(value) ? value.trim() : "tax-invoice-import.csv";
        name = name.replace('\\', '_').replace('/', '_').replace('\0', '_');
        return name.length() > 255 ? name.substring(name.length() - 255) : name;
    }
    private String sha256(byte[] content) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA-256不可用", ex); }
    }

    private record InvoiceHeader(String direction, String color, String type, String code, String number,
                                 Long blueInvoiceId,
                                 Long partnerId, String issuerName, String issuerTaxNo,
                                 String recipientName, String recipientTaxNo, LocalDate invoiceDate,
                                 LocalDate receivedDate, String memo) {}

    private record InvoiceGroup(InvoiceHeader header, List<TaxInvoiceDtos.InvoiceLineSave> lines) {
        TaxInvoiceDtos.InvoiceSave toRequest(String sourceFileName, String sourceFileSha256) {
            return new TaxInvoiceDtos.InvoiceSave(header.direction(), header.color(), header.type(), header.code(),
                    header.number(), header.blueInvoiceId(), header.partnerId(), header.issuerName(), header.issuerTaxNo(),
                    header.recipientName(), header.recipientTaxNo(), header.invoiceDate(), header.receivedDate(),
                    "IMPORT", "CNY", sourceFileName, sourceFileSha256, header.memo(), null, List.copyOf(lines));
        }
    }
}
