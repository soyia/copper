package com.soyia.copperv2.service;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class ProductImportFileParser {
    public static final String TEMPLATE_VERSION = "copper-product-import/v2";
    public static final String EXAMPLE_SKU_PREFIX = "COPPER-EXAMPLE-DO-NOT-IMPORT-";
    public static final String EXAMPLE_ROW_MARKER = "【模板示例-请删除】";
    public static final int MAX_ROWS = 5000;
    public static final int MAX_COLUMNS = 256;
    public static final int MAX_CELL_LENGTH = 10_000;
    public static final long MAX_FILE_SIZE = 20L * 1024 * 1024;

    private static final List<String> DEFAULT_TEMPLATE_HEADERS = List.of(
            "产品系列*", "SKU", "商品名称", "库存单位*", "牌号", "材料状态", "执行标准",
            "规格备注", "条码");

    public TemplateFile template(String requestedFormat) {
        return template(requestedFormat, new TemplateDefinition(
                "通用商品", "通用系列", DEFAULT_TEMPLATE_HEADERS,
                List.of(defaultExample(DEFAULT_TEMPLATE_HEADERS, 1),
                        defaultExample(DEFAULT_TEMPLATE_HEADERS, 2))));
    }

    public TemplateFile template(String requestedFormat, TemplateDefinition definition) {
        if (definition == null || definition.headers().isEmpty()) {
            throw new BusinessException("商品导入模板没有可用字段");
        }
        if (definition.headers().size() > MAX_COLUMNS) {
            throw new BusinessException("商品导入模板最多支持" + MAX_COLUMNS + "列");
        }
        String format = StringUtils.hasText(requestedFormat)
                ? requestedFormat.trim().toLowerCase(Locale.ROOT) : "xlsx";
        String fileStem = "商品导入模板-" + safeTemplateName(definition.categoryName())
                + "-" + safeTemplateName(definition.seriesName());
        if ("csv".equals(format)) {
            StringBuilder content = new StringBuilder("\uFEFF")
                    .append(csvLine(definition.headers())).append("\r\n");
            for (List<String> example : definition.exampleRows()) {
                content.append(csvLine(paddedRow(example, definition.headers().size())))
                        .append("\r\n");
            }
            return new TemplateFile(fileStem + ".csv", "text/csv;charset=UTF-8",
                    content.toString().getBytes(StandardCharsets.UTF_8));
        }
        if (!"xlsx".equals(format)) throw new BusinessException("商品导入模板仅支持 CSV 或 Excel(xlsx)");
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            CellStyle headerStyle = workbook.createCellStyle();
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.LIGHT_CORNFLOWER_BLUE.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            CellStyle exampleStyle = workbook.createCellStyle();
            exampleStyle.setFillForegroundColor(IndexedColors.LIGHT_YELLOW.getIndex());
            exampleStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            Sheet dataSheet = workbook.createSheet("商品数据（上传此表）");
            writeHeader(dataSheet, definition.headers(), headerStyle);

            Sheet exampleSheet = workbook.createSheet("填写示例（请勿上传）");
            writeHeader(exampleSheet, definition.headers(), headerStyle);
            for (int rowIndex = 0; rowIndex < definition.exampleRows().size(); rowIndex++) {
                Row row = exampleSheet.createRow(rowIndex + 1);
                List<String> example = paddedRow(
                        definition.exampleRows().get(rowIndex), definition.headers().size());
                for (int columnIndex = 0; columnIndex < example.size(); columnIndex++) {
                    Cell cell = row.createCell(columnIndex);
                    cell.setCellValue(example.get(columnIndex));
                    cell.setCellStyle(exampleStyle);
                }
            }
            workbook.setActiveSheet(1);
            workbook.setSelectedTab(1);

            Sheet instructions = workbook.createSheet("填写说明");
            List<String> notes = List.of(
                    "Copper 商品主数据系列模板 " + TEMPLATE_VERSION,
                    "适用分类：" + definition.categoryName() + "；产品系列：" + definition.seriesName(),
                    "1. Excel 请在“商品数据（上传此表）”填写并上传；“填写示例（请勿上传）”只用于参照。",
                    "2. CSV 自带两行示例，正式上传前必须删除；系统会拦截带 "
                            + EXAMPLE_SKU_PREFIX + " 前缀的示例行。",
                    "3. 产品系列和库存单位必填；SKU和商品名称可留空，由系统依据系列规格生成。",
                    "4. “属性:”开头的是本系列经营规格。用于区分商品的必填规格必须填写完整。",
                    "5. 牌号、材料状态、执行标准、单位和下拉值应使用系统已有配置；导入不会静默新增配置。",
                    "6. 模板只保留日常商品建档字段；价格、采购条件和内部管理资料请在对应业务中维护。",
                    "7. 预检只形成暂存及建议动作；确认提交前不会新增或修改正式商品。",
                    "8. CSV/Excel 单元格不得包含公式或以 =、+、@ 开头；负数只接受普通十进制数值。"
            );
            for (int i = 0; i < notes.size(); i++) {
                instructions.createRow(i).createCell(0).setCellValue(notes.get(i));
            }
            instructions.setColumnWidth(0, 120 * 256);
            workbook.write(output);
            return new TemplateFile(fileStem + ".xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", output.toByteArray());
        } catch (IOException ex) {
            throw new BusinessException("Excel 商品导入模板生成失败");
        }
    }

    private void writeHeader(Sheet sheet, List<String> headers, CellStyle headerStyle) {
        Row header = sheet.createRow(0);
        for (int i = 0; i < headers.size(); i++) {
            Cell cell = header.createCell(i);
            cell.setCellValue(headers.get(i));
            cell.setCellStyle(headerStyle);
            sheet.setColumnWidth(i, columnWidth(headers.get(i)));
        }
        sheet.createFreezePane(0, 1);
        sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(
                0, 0, 0, headers.size() - 1));
    }

    private int columnWidth(String header) {
        if ("产品系列*".equals(header) || "商品名称".equals(header)) return 24 * 256;
        if ("SKU".equals(header) || "条码".equals(header)) return 22 * 256;
        if ("执行标准".equals(header) || "规格备注".equals(header)) return 30 * 256;
        return header.startsWith("属性:") ? 20 * 256 : 16 * 256;
    }

    private static List<String> defaultExample(List<String> headers, int index) {
        Map<String, String> values = Map.of(
                "产品系列*", "通用系列",
                "SKU", EXAMPLE_SKU_PREFIX + String.format("%02d", index),
                "商品名称", EXAMPLE_ROW_MARKER + "示例商品" + index,
                "库存单位*", "kg",
                "规格备注", EXAMPLE_ROW_MARKER + "仅用于说明填写格式");
        return headers.stream().map(header -> values.getOrDefault(header, "")).toList();
    }

    private List<String> paddedRow(List<String> row, int width) {
        List<String> result = new ArrayList<>(width);
        for (int index = 0; index < width; index++) {
            result.add(index < row.size() ? text(row.get(index)) : "");
        }
        return result;
    }

    private String safeTemplateName(String value) {
        String safe = text(value).replaceAll("[\\\\/:*?\"<>|]", "-").trim();
        return StringUtils.hasText(safe) ? safe : "未命名";
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    public ParsedFile parse(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new BusinessException("请选择商品 CSV 或 Excel 文件");
        if (file.getSize() > MAX_FILE_SIZE) throw new BusinessException("商品导入文件不能超过20MB");
        String fileName = safeFileName(file.getOriginalFilename());
        String lower = fileName.toLowerCase(Locale.ROOT);
        String format = lower.endsWith(".csv") ? "CSV" : lower.endsWith(".xlsx") ? "XLSX" : "";
        if (format.isEmpty()) throw new BusinessException("仅支持 CSV 或 Excel(xlsx) 商品文件");
        try {
            byte[] bytes = file.getBytes();
            List<List<String>> values = "CSV".equals(format) ? csvRows(bytes) : xlsxRows(bytes);
            if (values.size() < 2) throw new BusinessException("导入文件没有可处理的商品");
            rejectSpreadsheetFormulas(values);
            List<String> headers = normalizeHeaders(values.get(0));
            List<RawRow> rows = new ArrayList<>();
            for (int i = 1; i < values.size(); i++) {
                List<String> valuesRow = values.get(i);
                if (valuesRow.stream().noneMatch(StringUtils::hasText)) continue;
                if (valuesRow.size() > headers.size()
                        && valuesRow.subList(headers.size(), valuesRow.size()).stream()
                        .anyMatch(StringUtils::hasText)) {
                    throw new BusinessException("第" + (i + 1) + "行存在没有列名的额外单元格");
                }
                Map<String, String> row = new LinkedHashMap<>();
                for (int c = 0; c < headers.size(); c++) {
                    String value = c < valuesRow.size() ? valuesRow.get(c).trim() : "";
                    row.put(headers.get(c), value);
                }
                rows.add(new RawRow(i + 1, row));
                if (rows.size() > MAX_ROWS) throw new BusinessException("单次最多导入" + MAX_ROWS + "条商品");
            }
            if (rows.isEmpty()) throw new BusinessException("导入文件没有可处理的商品");
            return new ParsedFile(fileName, format, bytes, headers, rows);
        } catch (IOException ex) {
            throw new BusinessException("商品导入文件读取失败");
        }
    }

    private List<String> normalizeHeaders(List<String> source) {
        List<String> result = new ArrayList<>();
        Map<String, Integer> seen = new LinkedHashMap<>();
        for (int i = 0; i < source.size(); i++) {
            String header = source.get(i) == null ? "" : source.get(i).replace("\uFEFF", "").trim();
            if (!StringUtils.hasText(header)) header = "__EMPTY_COLUMN_" + (i + 1);
            String key = header.toLowerCase(Locale.ROOT);
            if (seen.putIfAbsent(key, i) != null) throw new BusinessException("导入文件存在重复列名：" + header);
            result.add(header);
        }
        return result;
    }

    private List<List<String>> xlsxRows(byte[] bytes) throws IOException {
        List<List<String>> rows = new ArrayList<>();
        DataFormatter formatter = new DataFormatter(Locale.CHINA);
        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            if (workbook.getNumberOfSheets() == 0) throw new BusinessException("Excel 文件没有工作表");
            Sheet sheet = workbook.getSheetAt(0);
            if (sheet.getLastRowNum() > MAX_ROWS) {
                throw new BusinessException("单次最多导入" + MAX_ROWS + "条商品");
            }
            int width = 0;
            for (Row row : sheet) {
                int rowWidth = Math.max(0, row.getLastCellNum());
                if (rowWidth > MAX_COLUMNS) {
                    throw new BusinessException("商品导入文件最多支持" + MAX_COLUMNS + "列");
                }
                width = Math.max(width, rowWidth);
                if (width == 0) continue;
                List<String> values = new ArrayList<>();
                for (int i = 0; i < width; i++) {
                    Cell cell = row.getCell(i, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                    if (cell != null && cell.getCellType() == CellType.FORMULA) {
                        throw new BusinessException(
                                "Excel 第" + (row.getRowNum() + 1) + "行第" + (i + 1)
                                        + "列包含公式；商品导入只接受静态值");
                    }
                    String value = cell == null ? "" : formatter.formatCellValue(cell).trim();
                    assertCellLength(value, row.getRowNum() + 1, i + 1);
                    values.add(value);
                }
                if (!rows.isEmpty() || values.stream().anyMatch(StringUtils::hasText)) {
                    if (rows.size() >= MAX_ROWS + 1) {
                        throw new BusinessException("单次最多导入" + MAX_ROWS + "条商品");
                    }
                    rows.add(values);
                }
            }
        } catch (IllegalArgumentException ex) {
            throw new BusinessException("Excel 文件格式损坏或不是有效的 xlsx 文件");
        }
        return rows;
    }

    private void rejectSpreadsheetFormulas(List<List<String>> rows) {
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            List<String> row = rows.get(rowIndex);
            for (int columnIndex = 0; columnIndex < row.size(); columnIndex++) {
                String raw = row.get(columnIndex);
                if (raw == null) continue;
                String value = raw.stripLeading();
                if (value.isEmpty()) continue;
                char prefix = value.charAt(0);
                boolean safeNegativeDecimal = prefix == '-'
                        && value.matches("-\\d+(?:\\.\\d+)?");
                if (prefix == '=' || prefix == '+' || prefix == '@'
                        || prefix == '-' && !safeNegativeDecimal) {
                    throw new BusinessException(
                            "导入文件第" + (rowIndex + 1) + "行第" + (columnIndex + 1)
                                    + "列疑似电子表格公式；请改为静态文本或普通数值");
                }
            }
        }
    }

    private List<List<String>> csvRows(byte[] bytes) {
        String content = decode(bytes).replace("\uFEFF", "");
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder value = new StringBuilder();
        boolean quoted = false;
        int sourceRow = 1;
        for (int i = 0; i < content.length(); i++) {
            char ch = content.charAt(i);
            if (quoted) {
                if (ch == '"' && i + 1 < content.length() && content.charAt(i + 1) == '"') {
                    value.append('"');
                    assertCellBufferLength(value, sourceRow, row.size() + 1);
                    i++;
                } else if (ch == '"') {
                    quoted = false;
                } else {
                    value.append(ch);
                    assertCellBufferLength(value, sourceRow, row.size() + 1);
                }
            } else if (ch == '"') {
                quoted = true;
            } else if (ch == ',') {
                assertColumnCount(row.size() + 1);
                row.add(value.toString());
                value.setLength(0);
            } else if (ch == '\n' || ch == '\r') {
                if (ch == '\r' && i + 1 < content.length() && content.charAt(i + 1) == '\n') i++;
                assertColumnCount(row.size() + 1);
                row.add(value.toString());
                value.setLength(0);
                if (!rows.isEmpty() || row.stream().anyMatch(StringUtils::hasText)) {
                    assertParsedRowCount(rows.size() + 1);
                    rows.add(new ArrayList<>(row));
                }
                row.clear();
                sourceRow++;
            } else {
                value.append(ch);
                assertCellBufferLength(value, sourceRow, row.size() + 1);
            }
        }
        if (quoted) throw new BusinessException("CSV 文件存在未闭合的双引号");
        if (value.length() > 0 || !row.isEmpty()) {
            assertColumnCount(row.size() + 1);
            row.add(value.toString());
            if (row.stream().anyMatch(StringUtils::hasText)) {
                assertParsedRowCount(rows.size() + 1);
                rows.add(row);
            }
        }
        return rows;
    }

    private void assertColumnCount(int columns) {
        if (columns > MAX_COLUMNS) {
            throw new BusinessException("商品导入文件最多支持" + MAX_COLUMNS + "列");
        }
    }

    private void assertParsedRowCount(int rowsIncludingHeader) {
        if (rowsIncludingHeader > MAX_ROWS + 1) {
            throw new BusinessException("单次最多导入" + MAX_ROWS + "条商品");
        }
    }

    private void assertCellBufferLength(StringBuilder value, int row, int column) {
        if (value.length() > MAX_CELL_LENGTH) {
            throw new BusinessException("导入文件第" + row + "行第" + column
                    + "列内容超过" + MAX_CELL_LENGTH + "个字符");
        }
    }

    private void assertCellLength(String value, int row, int column) {
        if (value != null && value.length() > MAX_CELL_LENGTH) {
            throw new BusinessException("导入文件第" + row + "行第" + column
                    + "列内容超过" + MAX_CELL_LENGTH + "个字符");
        }
    }

    private String decode(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException ex) {
            try {
                return java.nio.charset.Charset.forName("GB18030").newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes)).toString();
            } catch (CharacterCodingException ignored) {
                throw new BusinessException("CSV 编码无法识别，请另存为 UTF-8 或 GB18030");
            }
        }
    }

    private String safeFileName(String original) {
        String value = StringUtils.hasText(original) ? original.replace('\\', '/') : "product-import";
        value = value.substring(value.lastIndexOf('/') + 1).trim();
        if (!StringUtils.hasText(value)) value = "product-import";
        return value.length() <= 255 ? value : value.substring(value.length() - 255);
    }

    private String csvLine(List<String> values) {
        return values.stream().map(value -> "\"" + value.replace("\"", "\"\"") + "\"")
                .reduce((left, right) -> left + "," + right).orElse("");
    }

    public record TemplateDefinition(
            String categoryName,
            String seriesName,
            List<String> headers,
            List<List<String>> exampleRows
    ) {
        public TemplateDefinition {
            categoryName = text(categoryName);
            seriesName = text(seriesName);
            headers = headers == null ? List.of() : List.copyOf(headers);
            exampleRows = exampleRows == null ? List.of()
                    : exampleRows.stream().map(List::copyOf).toList();
        }
    }

    public record TemplateFile(String fileName, String contentType, byte[] content) {
    }

    public record ParsedFile(
            String fileName,
            String format,
            byte[] bytes,
            List<String> headers,
            List<RawRow> rows
    ) {
    }

    public record RawRow(int sourceRowNo, Map<String, String> values) {
    }
}
