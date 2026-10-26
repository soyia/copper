package com.soyia.copperv2.service;

import org.junit.jupiter.api.Test;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BankStatementFileParserTest {
    private final BankStatementFileParser parser = new BankStatementFileParser();

    @Test
    void parsesStandardCsvAndNormalizesChineseDirection() {
        String csv = "交易日期,记账日期,收支方向,金额,余额,银行流水号,对方户名,对方账号,用途/摘要\n" +
                "2026-07-01,2026-07-02,收入,1,234.50,5234.50,LS001,青岛示例铜业有限公司,62220001,货款 YS001\n";
        // The amount contains a comma, so keep it quoted like a real CSV export.
        csv = csv.replace("收入,1,234.50", "收入,\"1,234.50\"");
        BankStatementFileParser.ParsedFile parsed = parser.parse(new MockMultipartFile(
                "file", "statement.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)));
        assertEquals("CSV", parsed.format());
        assertEquals(1, parsed.rows().size());
        assertTrue(parsed.rows().get(0).valid());
        assertEquals("CREDIT", parsed.rows().get(0).input().direction());
        assertEquals("1234.50", parsed.rows().get(0).input().amount().toPlainString());
    }

    @Test
    void acceptsBankExportWithSeparateCreditAndDebitColumnsAndGb18030() {
        String csv = "交易日期,收入金额,支出金额,余额,交易流水号,交易对方,交易摘要\n" +
                "2026/07/03,,88.20,4911.80,LS002,某供应商,采购付款\n";
        BankStatementFileParser.ParsedFile parsed = parser.parse(new MockMultipartFile(
                "file", "bank.csv", "text/csv", csv.getBytes(Charset.forName("GB18030"))));
        assertTrue(parsed.rows().get(0).valid());
        assertEquals("DEBIT", parsed.rows().get(0).input().direction());
        assertEquals("88.20", parsed.rows().get(0).input().amount().toPlainString());
    }

    @Test
    void generatesAndReadsXlsxTemplate() throws Exception {
        BankStatementFileParser.TemplateFile template = parser.template("xlsx");
        assertTrue(template.fileName().endsWith(".xlsx"));
        assertTrue(template.content().length > 1000);
        byte[] populated;
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(template.content()));
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var row = workbook.getSheetAt(0).createRow(1);
            String[] values = {"2026-07-05", "2026-07-05", "支出", "18.80", "981.20",
                    "XLSX-001", "示例供应商", "62220002", "采购付款 FK001"};
            for (int i = 0; i < values.length; i++) row.createCell(i).setCellValue(values[i]);
            workbook.write(output);
            populated = output.toByteArray();
        }
        BankStatementFileParser.ParsedFile parsed = parser.parse(new MockMultipartFile(
                "file", template.fileName(), template.contentType(), populated));
        assertEquals(1, parsed.rows().size());
        assertTrue(parsed.rows().get(0).valid());
        assertEquals("DEBIT", parsed.rows().get(0).input().direction());
        assertEquals("18.80", parsed.rows().get(0).input().amount().toPlainString());
    }
}
