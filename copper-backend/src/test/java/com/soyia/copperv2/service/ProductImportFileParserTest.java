package com.soyia.copperv2.service;

import org.junit.jupiter.api.Test;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductImportFileParserTest {
    private final ProductImportFileParser parser = new ProductImportFileParser();

    @Test
    void parsesUtf8CsvAndRetainsQuotedSourceCells() {
        String csv = "\uFEFF产品系列*,库存单位*,属性:外径,备注\n"
                + "紫铜直管,kg,12.7,\"含,逗号\"\n";
        MockMultipartFile file = new MockMultipartFile(
                "file", "products.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));

        ProductImportFileParser.ParsedFile parsed = parser.parse(file);

        assertEquals("CSV", parsed.format());
        assertEquals(1, parsed.rows().size());
        assertEquals(2, parsed.rows().get(0).sourceRowNo());
        assertEquals("12.7", parsed.rows().get(0).values().get("属性:外径"));
        assertEquals("含,逗号", parsed.rows().get(0).values().get("备注"));
    }

    @Test
    void rejectsDuplicateHeadersInsteadOfSilentlyChoosingOne() {
        String csv = "产品系列,库存单位,库存单位\n紫铜直管,kg,t\n";
        MockMultipartFile file = new MockMultipartFile(
                "file", "products.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));

        BusinessException error = assertThrows(BusinessException.class, () -> parser.parse(file));

        assertTrue(error.getMessage().contains("重复列名"));
    }

    @Test
    void producesBothCsvAndXlsxTemplates() {
        ProductImportFileParser.TemplateFile csv = parser.template("csv");
        ProductImportFileParser.TemplateFile xlsx = parser.template("xlsx");

        String csvContent = new String(csv.content(), StandardCharsets.UTF_8);
        assertTrue(csvContent.contains("产品系列"));
        assertTrue(csvContent.contains(ProductImportFileParser.EXAMPLE_SKU_PREFIX));
        assertFalse(csvContent.contains("来源URL"));
        assertFalse(csvContent.contains("默认单价"));
        assertFalse(csvContent.contains("启用状态"));
        assertFalse(csvContent.contains("供应商原始编号"));
        assertTrue(xlsx.content().length > 1000);
        assertEquals(ProductImportFileParser.TEMPLATE_VERSION, "copper-product-import/v2");
    }

    @Test
    void xlsxSeparatesUploadSheetFromExamplesAndKeepsSeriesFields() throws Exception {
        List<String> headers = List.of(
                "产品系列*", "SKU", "商品名称", "库存单位*", "属性:外径", "属性:壁厚");
        List<String> example = List.of(
                "通用圆形铜管", ProductImportFileParser.EXAMPLE_SKU_PREFIX + "1",
                "示例商品（请删除）", "kg", "12.7", "0.8");
        ProductImportFileParser.TemplateFile xlsx = parser.template("xlsx",
                new ProductImportFileParser.TemplateDefinition(
                        "铜管材", "通用圆形铜管", headers, List.of(example)));

        try (XSSFWorkbook workbook = new XSSFWorkbook(
                new ByteArrayInputStream(xlsx.content()))) {
            assertEquals(3, workbook.getNumberOfSheets());
            assertEquals("商品数据（上传此表）", workbook.getSheetAt(0).getSheetName());
            assertEquals(0, workbook.getSheetAt(0).getLastRowNum());
            assertEquals("填写示例（请勿上传）", workbook.getSheetAt(1).getSheetName());
            assertEquals(ProductImportFileParser.EXAMPLE_SKU_PREFIX + "1",
                    workbook.getSheetAt(1).getRow(1).getCell(1).getStringCellValue());
            assertEquals("属性:外径",
                    workbook.getSheetAt(0).getRow(0).getCell(4).getStringCellValue());
        }
    }

    @Test
    void rejectsSpreadsheetFormulaInjectionButAllowsNegativeNumericValues() {
        String safeCsv = "产品系列,库存单位,温度下限\n工业阀门,件,-40.5\n";
        ProductImportFileParser.ParsedFile safe = parser.parse(new MockMultipartFile(
                "file", "safe.csv", "text/csv", safeCsv.getBytes(StandardCharsets.UTF_8)));
        assertEquals("-40.5", safe.rows().get(0).values().get("温度下限"));

        String unsafeCsv = "产品系列,库存单位,备注\n工业阀门,件,\"=HYPERLINK(\"\"https://example.invalid\"\")\"\n";
        BusinessException csvError = assertThrows(BusinessException.class, () -> parser.parse(
                new MockMultipartFile("file", "unsafe.csv", "text/csv",
                        unsafeCsv.getBytes(StandardCharsets.UTF_8))));
        assertTrue(csvError.getMessage().contains("疑似电子表格公式"));
    }

    @Test
    void rejectsNativeExcelFormulaCells() throws Exception {
        byte[] content;
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("商品导入");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("产品系列");
            header.createCell(1).setCellValue("库存单位");
            var row = sheet.createRow(1);
            row.createCell(0).setCellValue("工业阀门");
            row.createCell(1).setCellFormula("HYPERLINK(\"https://example.invalid\")");
            workbook.write(output);
            content = output.toByteArray();
        }

        BusinessException error = assertThrows(BusinessException.class, () -> parser.parse(
                new MockMultipartFile("file", "unsafe.xlsx",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        content)));
        assertTrue(error.getMessage().contains("包含公式"));
    }

    @Test
    void rejectsCsvBeyondColumnLimitBeforeBuildingAnUnboundedRow() {
        String header = IntStream.rangeClosed(1, ProductImportFileParser.MAX_COLUMNS + 1)
                .mapToObj(index -> "列" + index)
                .collect(Collectors.joining(","));
        String values = IntStream.rangeClosed(1, ProductImportFileParser.MAX_COLUMNS + 1)
                .mapToObj(index -> "值" + index)
                .collect(Collectors.joining(","));

        BusinessException error = assertThrows(BusinessException.class, () -> parser.parse(
                new MockMultipartFile("file", "wide.csv", "text/csv",
                        (header + "\n" + values + "\n").getBytes(StandardCharsets.UTF_8))));

        assertTrue(error.getMessage().contains("最多支持"
                + ProductImportFileParser.MAX_COLUMNS + "列"));
    }

    @Test
    void rejectsCsvCellBeyondLengthLimitWhileScanning() {
        String oversized = "X".repeat(ProductImportFileParser.MAX_CELL_LENGTH + 1);
        String csv = "产品系列,库存单位\n工业阀门,\"" + oversized + "\"\n";

        BusinessException error = assertThrows(BusinessException.class, () -> parser.parse(
                new MockMultipartFile("file", "long-cell.csv", "text/csv",
                        csv.getBytes(StandardCharsets.UTF_8))));

        assertTrue(error.getMessage().contains("内容超过"
                + ProductImportFileParser.MAX_CELL_LENGTH + "个字符"));
    }

    @Test
    void rejectsCsvBeyondDataRowLimit() {
        StringBuilder csv = new StringBuilder("产品系列,库存单位\n");
        for (int i = 0; i <= ProductImportFileParser.MAX_ROWS; i++) {
            csv.append("工业阀门,件\n");
        }

        BusinessException error = assertThrows(BusinessException.class, () -> parser.parse(
                new MockMultipartFile("file", "too-many.csv", "text/csv",
                        csv.toString().getBytes(StandardCharsets.UTF_8))));

        assertTrue(error.getMessage().contains("单次最多导入"
                + ProductImportFileParser.MAX_ROWS + "条商品"));
    }

    @Test
    void rejectsSparseXlsxWhoseLastRowExceedsLimit() throws Exception {
        byte[] content;
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("商品导入");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("产品系列");
            header.createCell(1).setCellValue("库存单位");
            var sparse = sheet.createRow(ProductImportFileParser.MAX_ROWS + 1);
            sparse.createCell(0).setCellValue("工业阀门");
            sparse.createCell(1).setCellValue("件");
            workbook.write(output);
            content = output.toByteArray();
        }

        BusinessException error = assertThrows(BusinessException.class, () -> parser.parse(
                new MockMultipartFile("file", "sparse.xlsx",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        content)));

        assertTrue(error.getMessage().contains("单次最多导入"
                + ProductImportFileParser.MAX_ROWS + "条商品"));
    }
}
