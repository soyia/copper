package com.soyia.copperv2.service;

import com.soyia.copperv2.dto.FinancialCloseDtos;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.DataValidationConstraint;
import org.apache.poi.ss.usermodel.DataValidationHelper;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class BankStatementFileParser {
    public static final String TEMPLATE_VERSION = "copper-bank-statement/v1";
    public static final int MAX_ROWS = 5000;
    public static final long MAX_FILE_SIZE = 20L * 1024 * 1024;
    private static final List<String> TEMPLATE_HEADERS = List.of(
            "交易日期", "记账日期", "收支方向", "金额", "余额", "银行流水号", "对方户名", "对方账号", "用途/摘要");
    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("yyyy/M/d"),
            DateTimeFormatter.ofPattern("yyyy.M.d"),
            DateTimeFormatter.BASIC_ISO_DATE);
    private static final Map<String, Set<String>> HEADER_ALIASES = aliases();

    public TemplateFile template(String requestedFormat) {
        String format = StringUtils.hasText(requestedFormat) ? requestedFormat.trim().toLowerCase(Locale.ROOT) : "xlsx";
        if ("csv".equals(format)) {
            byte[] content = ("\uFEFF" + String.join(",", TEMPLATE_HEADERS) + "\n").getBytes(StandardCharsets.UTF_8);
            return new TemplateFile("copper-bank-statement-template-v1.csv", "text/csv;charset=UTF-8", content);
        }
        if (!"xlsx".equals(format)) throw new BusinessException("银行流水模板仅支持 CSV 或 Excel(xlsx)");
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("银行流水");
            CellStyle headerStyle = workbook.createCellStyle();
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.LIGHT_CORNFLOWER_BLUE.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            Row header = sheet.createRow(0);
            for (int i = 0; i < TEMPLATE_HEADERS.size(); i++) {
                Cell cell = header.createCell(i);
                cell.setCellValue(TEMPLATE_HEADERS.get(i));
                cell.setCellStyle(headerStyle);
                sheet.setColumnWidth(i, switch (i) {
                    case 0, 1 -> 14 * 256;
                    case 2 -> 12 * 256;
                    case 3, 4 -> 16 * 256;
                    case 5, 7 -> 24 * 256;
                    case 6 -> 26 * 256;
                    default -> 36 * 256;
                });
            }
            sheet.createFreezePane(0, 1);
            sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0, 0, 0, TEMPLATE_HEADERS.size() - 1));
            DataValidationHelper helper = sheet.getDataValidationHelper();
            DataValidationConstraint constraint = helper.createExplicitListConstraint(new String[]{"收入", "支出"});
            DataValidation validation = helper.createValidation(constraint, new CellRangeAddressList(1, MAX_ROWS, 2, 2));
            validation.setShowErrorBox(true);
            validation.setErrorStyle(DataValidation.ErrorStyle.STOP);
            validation.createErrorBox("方向无效", "请选择收入或支出");
            sheet.addValidationData(validation);

            Sheet instructions = workbook.createSheet("填写说明");
            List<String> notes = List.of(
                    "Copper 银行流水标准导入模板 " + TEMPLATE_VERSION,
                    "1. 每行一笔银行流水，交易日期、收支方向、金额和余额必填。",
                    "2. 收支方向填“收入/CREDIT/贷”或“支出/DEBIT/借”。金额统一填正数。",
                    "3. 银行流水号应保留银行原始值；它会与完整行摘要共同用于重复识别。",
                    "4. 对方户名、对方账号、用途/摘要越完整，自动匹配越准确。",
                    "5. 导入先预检；只有金额一致、证据充分且候选唯一的流水才会自动匹配，其他结果需人工确认。",
                    "6. 导入不会创建收付款，也不会修改银行或总账余额；错误流水必须按对账规则作废或撤销匹配。"
            );
            for (int i = 0; i < notes.size(); i++) instructions.createRow(i).createCell(0).setCellValue(notes.get(i));
            instructions.setColumnWidth(0, 110 * 256);
            workbook.write(output);
            return new TemplateFile("copper-bank-statement-template-v1.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", output.toByteArray());
        } catch (IOException ex) {
            throw new BusinessException("Excel 银行流水模板生成失败");
        }
    }

    public ParsedFile parse(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new BusinessException("请选择银行流水 CSV 或 Excel 文件");
        if (file.getSize() > MAX_FILE_SIZE) throw new BusinessException("银行流水导入文件不能超过20MB");
        String fileName = safeFileName(file.getOriginalFilename());
        String lower = fileName.toLowerCase(Locale.ROOT);
        String format = lower.endsWith(".csv") ? "CSV" : lower.endsWith(".xlsx") ? "XLSX" : "";
        if (format.isEmpty()) throw new BusinessException("仅支持 CSV 或 Excel(xlsx) 银行流水文件");
        try {
            byte[] bytes = file.getBytes();
            List<RawRow> rawRows = "CSV".equals(format) ? csvRows(bytes) : xlsxRows(bytes);
            if (rawRows.size() < 2) throw new BusinessException("导入文件没有可处理的银行流水");
            RawRow header = rawRows.get(0);
            Map<String, Integer> columns = headerMap(header.values());
            List<ParsedRow> rows = new ArrayList<>();
            for (RawRow raw : rawRows.subList(1, rawRows.size())) {
                if (raw.values().stream().allMatch(value -> !StringUtils.hasText(value))) continue;
                rows.add(parseRow(raw, columns));
                if (rows.size() > MAX_ROWS) throw new BusinessException("单次最多导入" + MAX_ROWS + "条银行流水");
            }
            if (rows.isEmpty()) throw new BusinessException("导入文件没有可处理的银行流水");
            return new ParsedFile(fileName, format, bytes, rows);
        } catch (IOException ex) {
            throw new BusinessException("银行流水文件读取失败");
        }
    }

    private ParsedRow parseRow(RawRow raw, Map<String, Integer> columns) {
        try {
            LocalDate transactionDate = date(value(raw, columns, "transactionDate"), "交易日期");
            String valueDateText = optional(raw, columns, "valueDate");
            LocalDate valueDate = StringUtils.hasText(valueDateText) ? date(valueDateText, "记账日期") : transactionDate;
            String creditText = optional(raw, columns, "creditAmount");
            String debitText = optional(raw, columns, "debitAmount");
            String directionText = optional(raw, columns, "direction");
            BigDecimal credit = optionalMoney(creditText);
            BigDecimal debit = optionalMoney(debitText);
            String direction = direction(directionText, credit, debit);
            String amountText = optional(raw, columns, "amount");
            BigDecimal amount = StringUtils.hasText(amountText) ? money(amountText, "金额") :
                    "CREDIT".equals(direction) ? credit : debit;
            if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) throw new RowException("金额必须大于0");
            BigDecimal balance = money(value(raw, columns, "balance"), "余额");
            String serial = limited(optional(raw, columns, "bankSerialNo"), 128, "银行流水号");
            String counterpartyName = limited(optional(raw, columns, "counterpartyName"), 255, "对方户名");
            String counterpartyAccount = limited(optional(raw, columns, "counterpartyAccount"), 128, "对方账号");
            String purpose = limited(optional(raw, columns, "purpose"), 500, "用途/摘要");
            FinancialCloseDtos.StatementLineInput input = new FinancialCloseDtos.StatementLineInput(
                    transactionDate, valueDate, serial, direction, amount, balance,
                    counterpartyName, counterpartyAccount, purpose);
            return new ParsedRow(raw.rowNo(), input, "");
        } catch (RowException ex) {
            return new ParsedRow(raw.rowNo(), null, ex.getMessage());
        }
    }

    private List<RawRow> csvRows(byte[] bytes) {
        String content = decode(bytes).replace("\uFEFF", "");
        List<List<String>> values = parseCsv(content);
        List<RawRow> rows = new ArrayList<>();
        for (int i = 0; i < values.size(); i++) rows.add(new RawRow(i + 1, values.get(i)));
        return rows;
    }

    private List<RawRow> xlsxRows(byte[] bytes) throws IOException {
        List<RawRow> rows = new ArrayList<>();
        DataFormatter formatter = new DataFormatter(Locale.CHINA);
        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            if (workbook.getNumberOfSheets() == 0) throw new BusinessException("Excel 文件没有工作表");
            Sheet sheet = workbook.getSheetAt(0);
            int width = 0;
            for (Row row : sheet) {
                if (width == 0) width = Math.max(1, row.getLastCellNum());
                List<String> values = new ArrayList<>();
                for (int i = 0; i < width; i++) {
                    Cell cell = row.getCell(i, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                    if (cell != null && cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
                        values.add(cell.getLocalDateTimeCellValue().toLocalDate().toString());
                    } else {
                        values.add(cell == null ? "" : formatter.formatCellValue(cell).trim());
                    }
                }
                if (!rows.isEmpty() || values.stream().anyMatch(StringUtils::hasText)) {
                    rows.add(new RawRow(row.getRowNum() + 1, values));
                }
            }
        } catch (IllegalArgumentException ex) {
            throw new BusinessException("Excel 文件格式损坏或不是有效的 xlsx 文件");
        }
        return rows;
    }

    private Map<String, Integer> headerMap(List<String> values) {
        Map<String, Integer> result = new HashMap<>();
        for (int i = 0; i < values.size(); i++) {
            String normalized = normalizeHeader(values.get(i));
            for (Map.Entry<String, Set<String>> entry : HEADER_ALIASES.entrySet()) {
                if (entry.getValue().contains(normalized)) result.putIfAbsent(entry.getKey(), i);
            }
        }
        if (!result.containsKey("transactionDate")) throw new BusinessException("模板缺少“交易日期”列");
        if (!result.containsKey("balance")) throw new BusinessException("模板缺少“余额”列");
        if (!result.containsKey("direction") &&
                !(result.containsKey("creditAmount") && result.containsKey("debitAmount"))) {
            throw new BusinessException("模板缺少“收支方向”列，或未同时提供“收入金额/支出金额”列");
        }
        if (!result.containsKey("amount") &&
                !(result.containsKey("creditAmount") && result.containsKey("debitAmount"))) {
            throw new BusinessException("模板缺少“金额”列，或未同时提供“收入金额/支出金额”列");
        }
        return result;
    }

    private static Map<String, Set<String>> aliases() {
        Map<String, Set<String>> map = new LinkedHashMap<>();
        map.put("transactionDate", normalized("交易日期", "交易时间", "日期", "transaction_date", "transactiondate"));
        map.put("valueDate", normalized("记账日期", "入账日期", "起息日", "value_date", "valuedate"));
        map.put("direction", normalized("收支方向", "借贷标志", "交易方向", "收付方向", "方向", "direction"));
        map.put("amount", normalized("金额", "交易金额", "发生额", "amount"));
        map.put("creditAmount", normalized("收入金额", "贷方发生额", "贷方金额", "收入", "credit_amount", "credit"));
        map.put("debitAmount", normalized("支出金额", "借方发生额", "借方金额", "支出", "debit_amount", "debit"));
        map.put("balance", normalized("余额", "账户余额", "交易后余额", "balance"));
        map.put("bankSerialNo", normalized("银行流水号", "流水号", "交易流水号", "交易序号", "凭证号", "bank_serial_no", "serialno"));
        map.put("counterpartyName", normalized("对方户名", "对方名称", "对手方户名", "交易对方", "counterparty_name"));
        map.put("counterpartyAccount", normalized("对方账号", "对方账户", "对手方账号", "counterparty_account"));
        map.put("purpose", normalized("用途/摘要", "用途", "摘要", "附言", "备注", "交易摘要", "purpose", "memo"));
        return map;
    }

    private static Set<String> normalized(String... values) {
        java.util.LinkedHashSet<String> result = new java.util.LinkedHashSet<>();
        for (String value : values) result.add(normalizeHeader(value));
        return Set.copyOf(result);
    }

    private static String normalizeHeader(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[\\s_\\-/（）()]+", "");
    }

    private String value(RawRow row, Map<String, Integer> columns, String field) {
        String value = optional(row, columns, field);
        if (!StringUtils.hasText(value)) throw new RowException(label(field) + "不能为空");
        return value;
    }

    private String optional(RawRow row, Map<String, Integer> columns, String field) {
        Integer index = columns.get(field);
        return index == null || index >= row.values().size() ? "" : row.values().get(index).trim();
    }

    private String direction(String text, BigDecimal credit, BigDecimal debit) {
        if (StringUtils.hasText(text)) {
            String normalized = text.trim().toUpperCase(Locale.ROOT);
            if (Set.of("CREDIT", "收入", "收", "贷", "贷方", "入账", "+").contains(normalized)) return "CREDIT";
            if (Set.of("DEBIT", "支出", "付", "借", "借方", "出账", "-").contains(normalized)) return "DEBIT";
            throw new RowException("收支方向必须为收入/CREDIT/贷或支出/DEBIT/借");
        }
        boolean hasCredit = credit != null && credit.compareTo(BigDecimal.ZERO) > 0;
        boolean hasDebit = debit != null && debit.compareTo(BigDecimal.ZERO) > 0;
        if (hasCredit == hasDebit) throw new RowException("收入金额和支出金额必须且只能填写一项");
        return hasCredit ? "CREDIT" : "DEBIT";
    }

    private LocalDate date(String value, String label) {
        String normalized = value.trim();
        int space = normalized.indexOf(' ');
        if (space > 0) normalized = normalized.substring(0, space);
        for (DateTimeFormatter formatter : DATE_FORMATS) {
            try { return LocalDate.parse(normalized, formatter); } catch (DateTimeParseException ignored) { }
        }
        throw new RowException(label + "格式无效，应为 yyyy-MM-dd");
    }

    private BigDecimal money(String value, String label) {
        try {
            String normalized = value.trim().replace(",", "").replace("￥", "").replace("¥", "");
            if (normalized.startsWith("(") && normalized.endsWith(")")) {
                normalized = "-" + normalized.substring(1, normalized.length() - 1);
            }
            return new BigDecimal(normalized).setScale(2, RoundingMode.HALF_UP);
        } catch (NumberFormatException ex) {
            throw new RowException(label + "不是有效金额");
        }
    }

    private BigDecimal optionalMoney(String value) {
        return StringUtils.hasText(value) ? money(value, "金额") : null;
    }

    private String limited(String value, int max, String label) {
        if (value.length() > max) throw new RowException(label + "不能超过" + max + "个字符");
        return value;
    }

    private String label(String field) {
        return switch (field) {
            case "transactionDate" -> "交易日期";
            case "balance" -> "余额";
            default -> field;
        };
    }

    private String decode(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException ex) {
            try {
                return Charset.forName("GB18030").newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            } catch (CharacterCodingException fallback) {
                throw new BusinessException("CSV 编码无法识别，请另存为 UTF-8 CSV");
            }
        }
    }

    private List<List<String>> parseCsv(String content) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder value = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < content.length(); i++) {
            char current = content.charAt(i);
            if (current == '"') {
                if (quoted && i + 1 < content.length() && content.charAt(i + 1) == '"') {
                    value.append('"');
                    i++;
                } else quoted = !quoted;
            } else if (current == ',' && !quoted) {
                row.add(value.toString().trim());
                value.setLength(0);
            } else if ((current == '\n' || current == '\r') && !quoted) {
                if (current == '\r' && i + 1 < content.length() && content.charAt(i + 1) == '\n') i++;
                row.add(value.toString().trim());
                value.setLength(0);
                if (row.stream().anyMatch(StringUtils::hasText)) rows.add(row);
                row = new ArrayList<>();
            } else value.append(current);
        }
        if (quoted) throw new BusinessException("CSV 存在未闭合的引号");
        row.add(value.toString().trim());
        if (row.stream().anyMatch(StringUtils::hasText)) rows.add(row);
        return rows;
    }

    private String safeFileName(String value) {
        String name = StringUtils.hasText(value) ? value.trim() : "bank-statement";
        name = name.replace('\\', '_').replace('/', '_').replace('\0', '_');
        return name.length() > 255 ? name.substring(name.length() - 255) : name;
    }

    public record TemplateFile(String fileName, String contentType, byte[] content) {}
    public record ParsedFile(String fileName, String format, byte[] bytes, List<ParsedRow> rows) {}
    public record ParsedRow(int sourceRowNo, FinancialCloseDtos.StatementLineInput input, String error) {
        public boolean valid() { return input != null; }
    }
    private record RawRow(int rowNo, List<String> values) {}
    private static final class RowException extends RuntimeException {
        private RowException(String message) { super(message); }
    }
}
