package com.soyia.copperv2.service;

import com.soyia.copperv2.dto.AccountingDtos;
import com.soyia.copperv2.dto.TaxComplianceDtos;
import com.soyia.copperv2.security.SecurityContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.transaction.AfterTransaction;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIfEnvironmentVariable(named = "COPPER_ERP12_E2E", matches = "true")
@Transactional
class Erp12TaxComplianceMySqlAcceptanceTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ElectronicCredentialService credentialService;
    @Autowired private TaxWorkpaperService workpaperService;
    @Autowired private AccountingArchiveService archiveService;
    @Autowired private LocalFileStorageService storage;

    private String token;
    private Long entityId;
    private Long periodId;
    private Long invoiceId;
    private final List<Long> createdArchiveIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        token = "ERP12" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        SecurityContext.set(owner());
        entityId = id("select id from accounting_entity where entity_code='DEFAULT'");
        periodId = id("select id from accounting_period where entity_id=? and fiscal_year=2026 and period_no=7", entityId);
        createPostedInputInvoice();
    }

    @AfterEach
    void cleanStoredFilesAndContext() {
        if (token != null) {
            List<String> ownedPaths = new ArrayList<>(jdbc.queryForList("""
                    select storage_path from electronic_credential where original_file_name like ?
                    union all
                    select v.proof_storage_path from electronic_credential_verification v
                    join electronic_credential c on c.id=v.credential_id where c.original_file_name like ?
                    """, String.class, token + "%", token + "%"));
            for (Long archiveId : createdArchiveIds) {
                ownedPaths.addAll(jdbc.queryForList(
                        "select storage_path from accounting_archive_batch where id=?",
                        String.class, archiveId));
            }
            for (String path : ownedPaths) {
                if (path != null && !path.isBlank()) storage.deleteNow(path);
            }
        }
        SecurityContext.clear();
    }

    @Test
    @DisplayName("数电票、银行回单/对账单、票账税底稿和会计归档形成真实闭环")
    void electronicInvoiceEvidenceAndVatWorkpaperAreTraceableAndImmutable() throws Exception {
        MockMultipartFile original = new MockMultipartFile("file", token + "-invoice.xml",
                "application/xml", invoiceXml().getBytes(StandardCharsets.UTF_8));
        TaxComplianceDtos.CredentialRow credential = credentialService.upload("AUTO", original);
        assertEquals("PARSED", credential.parseStatus());
        assertEquals("UNVERIFIED", credential.verificationStatus());
        assertEquals("EINV", credential.standardCode());
        assertThrows(BusinessException.class, () -> credentialService.upload("AUTO", original));

        MockMultipartFile proof = new MockMultipartFile("proof", token + "-verify.pdf",
                "application/pdf", "%PDF-1.7\nERP12 verification evidence".getBytes(StandardCharsets.US_ASCII));
        assertThrows(BusinessException.class, () -> credentialService.verify(
                credential.id(), "SIGNATURE", "TAX_DIGITAL_ACCOUNT", "VERIFIED",
                token + "-WRONG-KIND", LocalDateTime.of(2026, 7, 23, 11, 59),
                "不得把数电票验真登记成银行验签", proof));
        credentialService.verify(credential.id(), "AUTHENTICITY", "TAX_DIGITAL_ACCOUNT", "VERIFIED",
                token + "-VERIFY-001", LocalDateTime.of(2026, 7, 23, 12, 0),
                "税务数字账户查验：票面信息一致、状态正常", proof);
        TaxComplianceDtos.LinkCandidate candidate = credentialService.linkCandidates(credential.id()).stream()
                .filter(item -> item.documentId().equals(invoiceId)).findFirst().orElseThrow();
        assertEquals("EXACT", candidate.matchLevel());
        credentialService.link(credential.id(), new TaxComplianceDtos.LinkRequest(
                "SOURCE", "TAX_INVOICE", invoiceId, "结构化数电票原件与已确认进项票关联"));

        assertEquals("VERIFIED", jdbc.queryForObject(
                "select verification_status from tax_invoice where id=?", String.class, invoiceId));
        assertEquals(2L, count("select count(*) from business_evidence_attachment where document_type='TAX_INVOICE' and document_id=? and status='ACTIVE'", invoiceId));
        assertEquals(1L, count("select count(*) from tax_invoice_verification_record where invoice_id=? and proof_sha256<>''", invoiceId));
        exerciseBankCredentials();

        TaxComplianceDtos.WorkpaperDetail workpaper = workpaperService.generate("2026-07");
        assertEquals(0, workpaper.workpaper().blockerCount());
        assertDecimal("130.00", workpaper.workpaper().deductibleInputTax());
        assertDecimal("130.00", workpaper.workpaper().ledgerInputTax());
        assertDecimal("0.00", workpaper.workpaper().inputLedgerDifference());
        workpaperService.review(workpaper.workpaper().id(), "已核对数电票原件、税务数字账户验真、用途确认和总账分录");
        assertEquals("REVIEWED", workpaperService.workpaper(workpaper.workpaper().id()).workpaper().status());

        Long inputAccountId = id("select id from accounting_account where entity_id=? and account_code='22210101'", entityId);
        Long inventoryAccountId = id("select id from accounting_account where entity_id=? and account_code='1405'", entityId);
        Long transferVoucherId = postedVoucher(token + "-VOUCHER-TRANSFER", "TAX_INVOICE_TRANSFER",
                inventoryAccountId, "1405", "库存商品",
                inputAccountId, "22210101", "应交增值税-进项税额");
        jdbc.update("update accounting_voucher set source_id=? where id=?", invoiceId, transferVoucherId);
        jdbc.update("""
                insert into tax_invoice_use_event
                (invoice_id,action,from_usage_status,to_usage_status,tax_period,tax_amount,
                 accounting_voucher_id,evidence_reference,reason,operator_name)
                values (?,'TRANSFER_OUT','DEDUCTION','TRANSFERRED_OUT','2026-07',130,?,
                  'ERP12-TRANSFER','同月抵扣后转出验收','ERP-12验收')
                """, invoiceId, transferVoucherId);
        jdbc.update("""
                update tax_invoice set usage_status='TRANSFERRED_OUT',transfer_out_voucher_id=? where id=?
                """, transferVoucherId, invoiceId);
        TaxComplianceDtos.WorkpaperDetail transferWorkpaper = workpaperService.generate("2026-07");
        assertDecimal("130.00", transferWorkpaper.workpaper().deductibleInputTax());
        assertDecimal("130.00", transferWorkpaper.workpaper().transferredOutInputTax());
        assertDecimal("0.00", transferWorkpaper.workpaper().ledgerInputTax());
        assertDecimal("0.00", transferWorkpaper.workpaper().inputLedgerDifference());
        assertEquals("SUPERSEDED", workpaperService.workpaper(workpaper.workpaper().id()).workpaper().status());
        assertEquals(1L, count("select count(*) from tax_workpaper_event where workpaper_id=? and action='SUPERSEDE'", workpaper.workpaper().id()));
        workpaperService.review(transferWorkpaper.workpaper().id(),
                "已核对同月进项抵扣和转出事件、两张记账凭证及总账净额");

        AccountingDtos.ArchiveView archive = archiveService.create(new AccountingDtos.ArchiveCreate(periodId, false));
        createdArchiveIds.add(archive.id());
        byte[] zip = archiveService.download(archive.id()).resource().getInputStream().readAllBytes();
        List<String> entries = zipEntries(zip);
        assertTrue(entries.stream().filter(name -> name.contains("期间电子凭证") && name.contains("原件_")).count() >= 3);
        assertTrue(entries.stream().filter(name -> name.contains("验证证据_")).count() >= 3);
        assertTrue(entries.stream().anyMatch(name -> name.contains("税务工作底稿") && name.endsWith(".json")));
        assertTrue(entries.contains("manifest.json"));
        assertTrue(entries.contains("SHA256SUMS"));
    }

    private void exerciseBankCredentials() {
        Long partnerId = id("select partner_id from tax_invoice where id=?", invoiceId);
        jdbc.update("""
                insert into payment
                (payment_no,customer_id,sales_order_id,amount,payment_method,account_type,received_date,verification_status,memo)
                values (?,?,0,888,'BANK','TRADE','2026-07-20','VERIFIED','ERP-12 银行回单验收')
                """, token + "-PAYMENT", partnerId);
        Long paymentId = id("select id from payment where payment_no=?", token + "-PAYMENT");

        TaxComplianceDtos.CredentialRow receipt = credentialService.upload("AUTO", new MockMultipartFile(
                "file", token + "-receipt.xml", "application/xml",
                bankReceiptXml().getBytes(StandardCharsets.UTF_8)));
        assertEquals("BANK_RECEIPT", receipt.credentialType());
        assertEquals("BKER", receipt.standardCode());
        MockMultipartFile receiptProof = bankProof("receipt");
        assertThrows(BusinessException.class, () -> credentialService.verify(receipt.id(), "AUTHENTICITY",
                "BANK_VALIDATION_TOOL", "VERIFIED", token + "-RECEIPT-WRONG-KIND",
                LocalDateTime.of(2026, 7, 23, 12, 5), "银行凭证不得登记为发票验真", receiptProof));
        credentialService.verify(receipt.id(), "SIGNATURE", "BANK_VALIDATION_TOOL", "VERIFIED",
                token + "-RECEIPT-SIGN", LocalDateTime.of(2026, 7, 23, 12, 6),
                "银行验证工具：回单签名有效，金额与交易日期一致", bankProof("receipt-valid"));
        assertThrows(BusinessException.class, () -> credentialService.link(receipt.id(),
                new TaxComplianceDtos.LinkRequest("RECONCILIATION", "BANK_RECONCILIATION", 1L,
                        "回单不得冒充银行对账单")));
        TaxComplianceDtos.LinkCandidate paymentCandidate = credentialService.linkCandidates(receipt.id()).stream()
                .filter(item -> item.documentId().equals(paymentId)).findFirst().orElseThrow();
        assertEquals("PAYMENT", paymentCandidate.documentType());
        credentialService.link(receipt.id(), new TaxComplianceDtos.LinkRequest(
                "SOURCE", "PAYMENT", paymentId, "银行电子回单与客户收款关联"));

        Long bankAccountId = id("select id from business_bank_account where entity_id=? and status='ENABLED' order by id limit 1", entityId);
        String bankAccountNo = jdbc.queryForObject(
                "select bank_account_no from business_bank_account where id=?", String.class, bankAccountId);
        jdbc.update("""
                insert into bank_reconciliation_session
                (entity_id,bank_account_id,period_id,reconciliation_no,statement_from,statement_to,
                 opening_balance,statement_closing_balance,ledger_closing_balance,
                 adjusted_statement_balance,adjusted_ledger_balance,difference,status,close_reason)
                values (?,?,?,?, '2026-07-01','2026-07-31',9000,9988,9988,9988,9988,0,'DRAFT','ERP-12 对账单验收')
                """, entityId, bankAccountId, periodId, token + "-BANK-REC");
        Long reconciliationId = id("select id from bank_reconciliation_session where reconciliation_no=?",
                token + "-BANK-REC");

        TaxComplianceDtos.CredentialRow statement = credentialService.upload("AUTO", new MockMultipartFile(
                "file", token + "-statement.xml", "application/xml",
                bankStatementXml(bankAccountNo).getBytes(StandardCharsets.UTF_8)));
        assertEquals("BANK_STATEMENT", statement.credentialType());
        assertEquals("BKRS", statement.standardCode());
        assertEquals(2, statement.detailCount());
        credentialService.verify(statement.id(), "SIGNATURE", "BANK_VALIDATION_TOOL", "VERIFIED",
                token + "-STATEMENT-SIGN", LocalDateTime.of(2026, 7, 23, 12, 10),
                "银行验证工具：对账单签名有效，账户和期间一致", bankProof("statement-valid"));
        assertThrows(BusinessException.class, () -> credentialService.link(statement.id(),
                new TaxComplianceDtos.LinkRequest("SOURCE", "PAYMENT", paymentId,
                        "对账单不得冒充单笔回单")));
        TaxComplianceDtos.LinkCandidate statementCandidate = credentialService.linkCandidates(statement.id()).stream()
                .filter(item -> item.documentId().equals(reconciliationId)).findFirst().orElseThrow();
        assertEquals("EXACT", statementCandidate.matchLevel());
        credentialService.link(statement.id(), new TaxComplianceDtos.LinkRequest(
                "RECONCILIATION", "BANK_RECONCILIATION", reconciliationId, "银行电子对账单与本期对账关联"));

        assertEquals(3L, count("select count(*) from electronic_credential where original_file_name like ?", token + "%"));
        assertEquals(3L, count("""
                select count(*) from electronic_credential_verification v join electronic_credential c on c.id=v.credential_id
                where c.original_file_name like ? and v.verification_result='VERIFIED' and v.proof_sha256<>''
                """, token + "%"));
        assertEquals(1L, count("""
                select count(*) from electronic_credential_relation
                where credential_id=? and document_type='PAYMENT' and document_id=? and status='ACTIVE'
                """, receipt.id(), paymentId));
        assertEquals(1L, count("""
                select count(*) from electronic_credential_relation
                where credential_id=? and document_type='BANK_RECONCILIATION' and document_id=? and status='ACTIVE'
                """, statement.id(), reconciliationId));
    }

    private MockMultipartFile bankProof(String suffix) {
        return new MockMultipartFile("proof", token + "-" + suffix + "-proof.xml", "application/xml",
                ("<bank-validation token=\"" + token + "\" result=\"valid\"/>").getBytes(StandardCharsets.UTF_8));
    }

    private String bankReceiptXml() {
        return """
                <xbrli:xbrl xmlns:xbrli="http://www.xbrl.org/2003/instance"
                  xmlns:bker="http://xbrl.mof.gov.cn/taxonomy/2023-12-31/bker">
                  <bker:IdentifyingCode>%s-RECEIPT</bker:IdentifyingCode>
                  <bker:Currency>CNY</bker:Currency>
                  <bker:TransactionAmountInFigures>888.00</bker:TransactionAmountInFigures>
                  <bker:DateOfIssue>2026-07-20</bker:DateOfIssue>
                </xbrli:xbrl>
                """.formatted(token);
    }

    private String bankStatementXml(String bankAccountNo) {
        return """
                <xbrli:xbrl xmlns:xbrli="http://www.xbrl.org/2003/instance"
                  xmlns:bkrs="http://xbrl.mof.gov.cn/taxonomy/2023-12-31/bkrs">
                  <bkrs:IdentificationCodeOfIssuer>%s-STATEMENT</bkrs:IdentificationCodeOfIssuer>
                  <bkrs:CustomerSettlementBankAccount>%s</bkrs:CustomerSettlementBankAccount>
                  <bkrs:YearOfBankReconciliationStatement>2026</bkrs:YearOfBankReconciliationStatement>
                  <bkrs:MonthOfBankReconciliationStatement>07</bkrs:MonthOfBankReconciliationStatement>
                  <bkrs:InformationOfReconcileDetailsTuple/><bkrs:InformationOfReconcileDetailsTuple/>
                  <bkrs:AccountBalanceAtTheEndOfReconciliationCycleAmount>9988.00</bkrs:AccountBalanceAtTheEndOfReconciliationCycleAmount>
                </xbrli:xbrl>
                """.formatted(token, bankAccountNo);
    }

    private void createPostedInputInvoice() {
        jdbc.update("insert into partner(business_no,type,type_mask,name,credit_code,status) values (?,'SUPPLIER',2,?,?,'ENABLED')",
                String.valueOf(700000 + Math.floorMod(token.hashCode(), 99999)), token + "供应商", token + "TAX");
        Long partnerId = id("select id from partner where name=?", token + "供应商");
        Long pendingAccountId = id("select id from accounting_account where entity_id=? and account_code='22210103'", entityId);
        Long inputAccountId = id("select id from accounting_account where entity_id=? and account_code='22210101'", entityId);
        Long inventoryAccountId = id("select id from accounting_account where entity_id=? and account_code='1405'", entityId);
        Long baseVoucherId = postedVoucher(token + "-VOUCHER-BASE", "TAX_INVOICE_BASE",
                pendingAccountId, "22210103", "待认证进项税额",
                inventoryAccountId, "1405", "库存商品");
        Long usageVoucherId = postedVoucher(token + "-VOUCHER-USE", "TAX_INVOICE_USAGE",
                inputAccountId, "22210101", "应交增值税-进项税额",
                pendingAccountId, "22210103", "待认证进项税额");
        jdbc.update("""
                insert into tax_invoice
                (entity_id,internal_no,direction,invoice_color,invoice_type,invoice_number,partner_id,
                 partner_name_snapshot,partner_tax_no_snapshot,issuer_name,issuer_tax_no,recipient_name,
                 recipient_tax_no,invoice_date,received_or_issued_date,amount_excluding_tax,tax_amount,total_amount,
                 status,verification_status,booking_status,usage_status,usage_period,accounting_voucher_id,
                 usage_voucher_id,confirmed_by_name,confirmed_at)
                values (?,?,'INPUT','BLUE','DIGITAL_SPECIAL',?,?,?,?,?,?,?,?,?,?,1000,130,1130,
                  'CONFIRMED','UNVERIFIED','CONFIRMED','DEDUCTION','2026-07',?,?,'ERP-12验收',now())
                """, entityId, token + "-INV", invoiceNumber(), partnerId, token + "供应商", token + "TAX",
                token + "供应商", token + "TAX", "Copper测试企业", "COPPER001",
                java.time.LocalDate.of(2026, 7, 23), java.time.LocalDate.of(2026, 7, 23), baseVoucherId, usageVoucherId);
        invoiceId = id("select id from tax_invoice where internal_no=?", token + "-INV");
        jdbc.update("update accounting_voucher set source_id=? where id in (?,?)", invoiceId, baseVoucherId, usageVoucherId);
        Long rateId = id("select id from tax_rate_rule where tax_rate=13 order by id limit 1");
        jdbc.update("""
                insert into tax_invoice_line
                (invoice_id,line_no,product_name_snapshot,quantity,unit_price_excluding_tax,
                 amount_excluding_tax,tax_rate_rule_id,tax_rule_code_snapshot,tax_rule_name_snapshot,
                 tax_rate,calculation_method_snapshot,tax_amount,total_amount)
                values (?,1,'紫铜管',10,100,1000,?,'VAT_GOODS_13','货物及铜材一般计税13%',13,'GENERAL',130,1130)
                """, invoiceId, rateId);
        jdbc.update("""
                insert into tax_invoice_use_event
                (invoice_id,action,from_usage_status,to_usage_status,tax_period,tax_amount,
                 accounting_voucher_id,evidence_reference,reason,operator_name)
                values (?,'USAGE_CONFIRM','UNCONFIRMED','DEDUCTION','2026-07',130,?,'ERP12-USAGE','用途确认验收','ERP-12验收')
                """, invoiceId, usageVoucherId);
    }

    private Long postedVoucher(String voucherNo, String sourceType,
                               Long debitAccountId, String debitCode, String debitName,
                               Long creditAccountId, String creditCode, String creditName) {
        jdbc.update("""
                insert into accounting_voucher
                (entity_id,period_id,voucher_no,voucher_type,voucher_date,summary,source_type,source_id,source_no,
                 status,debit_total,credit_total,entry_hash,maker_name,reviewer_name,poster_name,reviewed_at,posted_at)
                values (?,?,?,?,?,?,?,?,?,'POSTED',130,130,?,?,?,?,now(),now())
                """, entityId, periodId, voucherNo, "AUTO", java.time.LocalDate.of(2026, 7, 23),
                "ERP-12进项税验收", sourceType, 0, token + "-INV", token + "-HASH-" + sourceType,
                "制单", "审核", "记账");
        Long voucherId = id("select id from accounting_voucher where voucher_no=?", voucherNo);
        jdbc.update("""
                insert into accounting_voucher_line
                (voucher_id,line_no,account_id,account_code_snapshot,account_name_snapshot,summary,debit_amount,credit_amount)
                values (?,?,?,?,?,?,130,0),(?,?,?,?,?,?,0,130)
                """, voucherId, 1, debitAccountId, debitCode, debitName, "ERP-12进项税验收",
                voucherId, 2, creditAccountId, creditCode, creditName, "ERP-12进项税验收");
        return voucherId;
    }

    private String invoiceXml() {
        return """
                <xbrli:xbrl xmlns:xbrli="http://www.xbrl.org/2003/instance"
                  xmlns:einv="http://xbrl.mof.gov.cn/taxonomy/2023-12-31/einv"
                  xmlns:link="http://www.xbrl.org/2003/linkbase" xmlns:xlink="http://www.w3.org/1999/xlink">
                  <link:schemaRef xlink:href="einv_entry_point_2023-12-31.xsd"/>
                  <einv:InvoiceNumber>%s</einv:InvoiceNumber>
                  <einv:SellerName>%s供应商</einv:SellerName><einv:SellerIdNum>%sTAX</einv:SellerIdNum>
                  <einv:TotalAmWithoutTax>1000.00</einv:TotalAmWithoutTax><einv:TotalTaxAm>130.00</einv:TotalTaxAm>
                  <einv:TotalTax-includedAmount>1130.00</einv:TotalTax-includedAmount>
                  <einv:RequestTime>2026-07-23 10:11:12</einv:RequestTime>
                  <einv:UnifiedSocialCreditCodeOfAccountingEntity>COPPER001</einv:UnifiedSocialCreditCodeOfAccountingEntity>
                  <einv:NameOfAccountingEntity>Copper测试企业</einv:NameOfAccountingEntity><einv:AccountingPeriod>2026-07</einv:AccountingPeriod>
                </xbrli:xbrl>
                """.formatted(invoiceNumber(), token, token);
    }

    private String invoiceNumber() {
        return "20260723" + String.format("%012d", Integer.toUnsignedLong(token.hashCode()));
    }

    private List<String> zipEntries(byte[] zip) throws Exception {
        List<String> names = new ArrayList<>();
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
            for (ZipEntry entry; (entry = input.getNextEntry()) != null; ) names.add(entry.getName());
        }
        return names;
    }

    private SecurityContext.Principal owner() {
        return new SecurityContext.Principal(1L, 1L, "ERP-12老板验收", "ERP-12-OWNER", 1L, "本企业", token,
                false, List.of("老板"), Set.of("tax.compliance.view", "tax.compliance.manage",
                "tax.compliance.review", "invoice.view", "invoice.manage", "accounting.archive"));
    }

    private Long id(String sql, Object... args) { return Objects.requireNonNull(jdbc.queryForObject(sql, Long.class, args)); }
    private long count(String sql, Object... args) { Long value = jdbc.queryForObject(sql, Long.class, args); return value == null ? 0 : value; }
    private void assertDecimal(String expected, BigDecimal actual) { assertEquals(0, new BigDecimal(expected).compareTo(actual)); }

    @AfterTransaction
    void rollbackRemovesFixtures() {
        if (token == null) return;
        assertEquals(0L, count("select count(*) from partner where name=?", token + "供应商"));
        assertEquals(0L, count("select count(*) from tax_invoice where internal_no=?", token + "-INV"));
        assertEquals(0L, count("select count(*) from accounting_voucher where voucher_no like ?", token + "-VOUCHER%"));
        assertEquals(0L, count("select count(*) from electronic_credential where original_file_name like ?", token + "%"));
    }
}
