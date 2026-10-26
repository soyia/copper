package com.soyia.copperv2.service;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.XSSFDataValidation;
import org.apache.poi.xssf.usermodel.XSSFDataValidationConstraint;
import org.apache.poi.xssf.usermodel.XSSFDataValidationHelper;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class OpeningMigrationWorkbook {
    private static final long MAX_FILE_SIZE = 10L * 1024 * 1024;
    private static final int MAX_TOTAL_ROWS = 30_000;
    private static final int MAX_SHEET_ROWS = 10_000;

    static final Map<String, SheetDefinition> SHEETS = Map.ofEntries(
            Map.entry("往来单位", new SheetDefinition("PARTNER", List.of(
                    "来源键*", "名称*", "角色*", "统一信用代码", "联系人", "手机", "授信额度", "备注"))),
            Map.entry("仓库", new SheetDefinition("WAREHOUSE", List.of(
                    "来源键*", "名称*", "位置", "收货地址"))),
            Map.entry("商品", new SheetDefinition("PRODUCT", List.of(
                    "来源键*", "SKU*", "名称*", "商品系列*", "规格", "主库存单位*", "材质牌号", "软硬状态",
                    "执行标准", "条码", "原编号", "默认售价", "规格属性JSON"))),
            Map.entry("库存期初", new SheetDefinition("INVENTORY", List.of(
                    "来源键*", "商品来源键*", "仓库来源键*", "批次号*", "数量*", "单位成本*", "生产日期", "备注"))),
            Map.entry("往来期初", new SheetDefinition("PARTNER_OPENING", List.of(
                    "来源键*", "往来单位来源键*", "类型*", "金额*", "到期日*", "原单号*", "备注"))),
            Map.entry("资金期初", new SheetDefinition("FUND_OPENING", List.of(
                    "来源键*", "类型*", "账户编码*", "账户名称*", "开户行", "银行账号", "科目编码*", "余额*", "币种"))),
            Map.entry("总账期初", new SheetDefinition("GENERAL_LEDGER", List.of(
                    "来源键*", "科目编码*", "借方", "贷方", "往来单位来源键", "商品来源键", "仓库来源键", "摘要*")))
    );

    public TemplateFile template() {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            CellStyle headerStyle = headerStyle(workbook);
            Sheet instructions = workbook.createSheet("填写说明");
            List<String> notes = List.of(
                    "Copper 主数据与期初迁移模板（仅用于空白账套切换）",
                    "1. 先维护来源键。来源键是旧系统中稳定且唯一的编号；同一文件内被其他工作表引用。",
                    "2. 金额保留两位、数量保留三位、单位成本保留四位；日期使用 yyyy-MM-dd。",
                    "3. 角色填写 CUSTOMER、SUPPLIER 或 BOTH。往来期初类型填写 RECEIVABLE、PAYABLE、CUSTOMER_ADVANCE 或 SUPPLIER_PREPAY。",
                    "4. 资金类型填写 BANK 或 CASH；币种当前仅支持 CNY。银行账户必须填写开户行和银行账号。",
                    "5. 商品系列必须使用系统中已启用的系列名称；规格属性JSON按系列必填属性填写，例如 {\"外径\":\"22\",\"壁厚\":\"1\"}。",
                    "6. 总账期初必须借贷相等；库存、应收、预付、应付、预收、资金分别与 1405、1122、1123、2202、2203、1001/1002 科目勾稽。",
                    "7. 上传只做预检。应用后、正式切换前可整批撤销；确认切换后不得整批无痕回滚，只能走调整或冲正。",
                    "8. 请先在副本或测试环境演练，并由企业会计复核科目、税务和期初口径。"
            );
            for (int i = 0; i < notes.size(); i++) instructions.createRow(i).createCell(0).setCellValue(notes.get(i));
            instructions.setColumnWidth(0, 120 * 256);

            for (Map.Entry<String, SheetDefinition> entry : orderedDefinitions()) {
                XSSFSheet sheet = workbook.createSheet(entry.getKey());
                Row header = sheet.createRow(0);
                List<String> columns = entry.getValue().headers();
                for (int i = 0; i < columns.size(); i++) {
                    Cell cell = header.createCell(i);
                    cell.setCellValue(columns.get(i));
                    cell.setCellStyle(headerStyle);
                    sheet.setColumnWidth(i, Math.min(36, Math.max(14, columns.get(i).length() * 3)) * 256);
                }
                sheet.createFreezePane(0, 1);
                sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0, 0, 0, columns.size() - 1));
                if ("往来单位".equals(entry.getKey())) dropdown(sheet, 2, new String[]{"CUSTOMER", "SUPPLIER", "BOTH"});
                if ("往来期初".equals(entry.getKey())) dropdown(sheet, 2,
                        new String[]{"RECEIVABLE", "PAYABLE", "CUSTOMER_ADVANCE", "SUPPLIER_PREPAY"});
                if ("资金期初".equals(entry.getKey())) dropdown(sheet, 1, new String[]{"BANK", "CASH"});
            }
            workbook.setActiveSheet(0);
            workbook.write(output);
            return new TemplateFile("copper-opening-migration-template-v1.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", output.toByteArray());
        } catch (IOException ex) {
            throw new BusinessException("生成期初迁移模板失败");
        }
    }

    public ParsedWorkbook parse(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new BusinessException("请选择期初迁移 Excel 文件");
        if (file.getSize() > MAX_FILE_SIZE) throw new BusinessException("期初迁移文件不能超过 10MB");
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().trim();
        if (!name.toLowerCase().endsWith(".xlsx")) throw new BusinessException("仅支持模板生成的 .xlsx 文件");
        try {
            byte[] bytes = file.getBytes();
            List<ParsedRow> rows = new ArrayList<>();
            try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
                DataFormatter formatter = new DataFormatter(java.util.Locale.CHINA);
                for (Map.Entry<String, SheetDefinition> entry : orderedDefinitions()) {
                    Sheet sheet = workbook.getSheet(entry.getKey());
                    if (sheet == null) throw new BusinessException("缺少工作表：“" + entry.getKey() + "”");
                    if (sheet.getLastRowNum() > MAX_SHEET_ROWS)
                        throw new BusinessException("工作表“" + entry.getKey() + "”超过 10000 行");
                    Map<Integer, String> headers = headers(sheet, entry.getValue(), formatter);
                    for (int rowNo = 1; rowNo <= sheet.getLastRowNum(); rowNo++) {
                        Row row = sheet.getRow(rowNo);
                        if (row == null || blank(row, headers.keySet(), formatter)) continue;
                        Map<String, String> values = new LinkedHashMap<>();
                        for (Map.Entry<Integer, String> header : headers.entrySet()) {
                            values.put(header.getValue(), value(row.getCell(header.getKey()), formatter));
                        }
                        rows.add(new ParsedRow(entry.getValue().domainCode(), entry.getKey(), rowNo + 1, values));
                        if (rows.size() > MAX_TOTAL_ROWS) throw new BusinessException("期初迁移文件总行数不能超过 30000 行");
                    }
                }
            }
            if (rows.isEmpty()) throw new BusinessException("期初迁移文件没有可预检的数据行");
            return new ParsedWorkbook(name, bytes, rows);
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException("期初迁移文件无法读取，请确认文件未损坏且结构未被修改");
        }
    }

    private List<Map.Entry<String, SheetDefinition>> orderedDefinitions() {
        List<String> order = List.of("往来单位", "仓库", "商品", "库存期初", "往来期初", "资金期初", "总账期初");
        return order.stream().map(name -> Map.entry(name, SHEETS.get(name))).toList();
    }

    private Map<Integer, String> headers(Sheet sheet, SheetDefinition definition, DataFormatter formatter) {
        Row row = sheet.getRow(0);
        if (row == null) throw new BusinessException("工作表“" + sheet.getSheetName() + "”缺少表头");
        Map<Integer, String> actual = new LinkedHashMap<>();
        Set<String> names = new LinkedHashSet<>();
        for (int i = 0; i < row.getLastCellNum(); i++) {
            String name = value(row.getCell(i), formatter);
            if (name.isBlank()) continue;
            if (!names.add(name)) throw new BusinessException("工作表“" + sheet.getSheetName() + "”存在重复表头：“" + name + "”");
            actual.put(i, name);
        }
        if (!names.containsAll(definition.headers())) {
            Set<String> missing = new LinkedHashSet<>(definition.headers());
            missing.removeAll(names);
            throw new BusinessException("工作表“" + sheet.getSheetName() + "”缺少列：" + String.join("、", missing));
        }
        actual.entrySet().removeIf(entry -> !definition.headers().contains(entry.getValue()));
        return actual;
    }

    private boolean blank(Row row, Set<Integer> columns, DataFormatter formatter) {
        for (Integer column : columns) if (!value(row.getCell(column), formatter).isBlank()) return false;
        return true;
    }

    private String value(Cell cell, DataFormatter formatter) {
        return cell == null ? "" : formatter.formatCellValue(cell).trim();
    }

    private CellStyle headerStyle(XSSFWorkbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        Font font = workbook.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.WHITE.getIndex());
        style.setFont(font);
        return style;
    }

    private void dropdown(XSSFSheet sheet, int column, String[] values) {
        XSSFDataValidationHelper helper = new XSSFDataValidationHelper(sheet);
        XSSFDataValidationConstraint constraint = (XSSFDataValidationConstraint) helper.createExplicitListConstraint(values);
        XSSFDataValidation validation = (XSSFDataValidation) helper.createValidation(
                constraint, new CellRangeAddressList(1, MAX_SHEET_ROWS, column, column));
        validation.setSuppressDropDownArrow(true);
        validation.setShowErrorBox(true);
        sheet.addValidationData(validation);
    }

    record SheetDefinition(String domainCode, List<String> headers) {}
    public record TemplateFile(String fileName, String contentType, byte[] content) {}
    public record ParsedWorkbook(String fileName, byte[] bytes, List<ParsedRow> rows) {}
    public record ParsedRow(String domainCode, String sheetName, int rowNo, Map<String, String> values) {}
}
