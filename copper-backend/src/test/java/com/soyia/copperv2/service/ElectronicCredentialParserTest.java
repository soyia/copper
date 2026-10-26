package com.soyia.copperv2.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ElectronicCredentialParserTest {
    private final ElectronicCredentialParser parser = new ElectronicCredentialParser();

    @Test
    void parsesMofDigitalInvoiceAccountingDataWithoutClaimingVerification() {
        String xml = """
                <xbrli:xbrl xmlns:xbrli="http://www.xbrl.org/2003/instance"
                  xmlns:einv="http://xbrl.mof.gov.cn/taxonomy/2023-12-31/einv"
                  xmlns:link="http://www.xbrl.org/2003/linkbase"
                  xmlns:xlink="http://www.w3.org/1999/xlink">
                  <link:schemaRef xlink:href="einv_entry_point_2023-12-31.xsd"/>
                  <einv:InvoiceNumber>24442000000000000001</einv:InvoiceNumber>
                  <einv:SellerName>铜材供应商</einv:SellerName><einv:SellerIdNum>SUPPLIER001</einv:SellerIdNum>
                  <einv:TotalAmWithoutTax>1000.00</einv:TotalAmWithoutTax>
                  <einv:TotalTaxAm>130.00</einv:TotalTaxAm>
                  <einv:TotalTax-includedAmount>1130.00</einv:TotalTax-includedAmount>
                  <einv:RequestTime>2026-07-23 10:11:12</einv:RequestTime>
                  <einv:UnifiedSocialCreditCodeOfAccountingEntity>COPPER001</einv:UnifiedSocialCreditCodeOfAccountingEntity>
                  <einv:NameOfAccountingEntity>Copper测试企业</einv:NameOfAccountingEntity>
                  <einv:AccountingPeriod>2026-07</einv:AccountingPeriod>
                </xbrli:xbrl>
                """;
        ElectronicCredentialParser.ParseResult result = parser.parse(
                xml.getBytes(StandardCharsets.UTF_8), "invoice.xml", "AUTO");
        assertEquals("DIGITAL_INVOICE", result.credentialType());
        assertEquals("EINV", result.standardCode());
        assertEquals("2023-12-31", result.standardVersion());
        assertEquals("INPUT", result.directionHint());
        assertEquals("1130.00", result.totalAmount().toPlainString());
        assertEquals("PARSED", result.parseStatus());
        assertTrue(result.parseMessage().contains("不等同税务验真"));
    }

    @Test
    void parsesBankReceiptAndStatementFamilies() {
        String receipt = """
                <xbrli:xbrl xmlns:xbrli="http://www.xbrl.org/2003/instance"
                  xmlns:bker="http://xbrl.mof.gov.cn/taxonomy/2023-12-31/bker">
                  <bker:IdentifyingCode>R-001</bker:IdentifyingCode>
                  <bker:Currency>CNY</bker:Currency>
                  <bker:TransactionAmountInFigures>888.00</bker:TransactionAmountInFigures>
                  <bker:DateOfIssue>2026-07-20</bker:DateOfIssue>
                </xbrli:xbrl>
                """;
        String statement = """
                <xbrli:xbrl xmlns:xbrli="http://www.xbrl.org/2003/instance"
                  xmlns:bkrs="http://xbrl.mof.gov.cn/taxonomy/2023-12-31/bkrs">
                  <bkrs:IdentificationCodeOfIssuer>BANK001</bkrs:IdentificationCodeOfIssuer>
                  <bkrs:CustomerSettlementBankAccount>62220001</bkrs:CustomerSettlementBankAccount>
                  <bkrs:YearOfBankReconciliationStatement>2026</bkrs:YearOfBankReconciliationStatement>
                  <bkrs:MonthOfBankReconciliationStatement>07</bkrs:MonthOfBankReconciliationStatement>
                  <bkrs:InformationOfReconcileDetailsTuple/><bkrs:InformationOfReconcileDetailsTuple/>
                  <bkrs:AccountBalanceAtTheEndOfReconciliationCycleAmount>9988.00</bkrs:AccountBalanceAtTheEndOfReconciliationCycleAmount>
                </xbrli:xbrl>
                """;
        assertEquals("BANK_RECEIPT", parser.parse(receipt.getBytes(StandardCharsets.UTF_8), "receipt.xml", "AUTO").credentialType());
        ElectronicCredentialParser.ParseResult result = parser.parse(statement.getBytes(StandardCharsets.UTF_8), "statement.xml", "AUTO");
        assertEquals("BANK_STATEMENT", result.credentialType());
        assertEquals(2, result.detailCount());
        assertEquals("2026-07-01", result.periodFrom().toString());
        assertEquals("2026-07-31", result.periodTo().toString());
    }

    @Test
    void pdfAndOfdNeedDeclaredTypeAndRemainPendingExternalVerification() {
        byte[] pdf = "%PDF-1.7\nmock".getBytes(StandardCharsets.US_ASCII);
        assertThrows(BusinessException.class, () -> parser.parse(pdf, "invoice.pdf", "AUTO"));
        ElectronicCredentialParser.ParseResult result = parser.parse(pdf, "invoice.pdf", "DIGITAL_INVOICE");
        assertEquals("PENDING_EXTERNAL", result.parseStatus());
        assertTrue(result.parseMessage().contains("财政部工具包"));
    }

    @Test
    void rejectsDoctypeAndDeclaredTypeMismatch() {
        String xxe = "<!DOCTYPE x [<!ENTITY y SYSTEM 'file:///etc/passwd'>]><x xmlns:einv=\"http://xbrl.mof.gov.cn/taxonomy/2023-12-31/einv\">&y;</x>";
        assertThrows(BusinessException.class,
                () -> parser.parse(xxe.getBytes(StandardCharsets.UTF_8), "bad.xml", "AUTO"));
        String invoice = "<x xmlns:einv=\"http://xbrl.mof.gov.cn/taxonomy/2023-12-31/einv\"><einv:InvoiceNumber>1</einv:InvoiceNumber></x>";
        assertThrows(BusinessException.class,
                () -> parser.parse(invoice.getBytes(StandardCharsets.UTF_8), "invoice.xml", "BANK_RECEIPT"));
    }
}
