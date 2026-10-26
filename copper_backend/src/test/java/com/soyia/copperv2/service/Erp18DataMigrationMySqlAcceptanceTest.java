package com.soyia.copperv2.service;

import com.soyia.copperv2.dto.DataMigrationDtos;
import com.soyia.copperv2.security.SecurityContext;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
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
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIfEnvironmentVariable(named = "COPPER_ERP18_E2E", matches = "true")
@Transactional
class Erp18DataMigrationMySqlAcceptanceTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataMigrationService service;
    @Autowired private OpeningMigrationWorkbook workbook;

    private String token;
    private LocalDate openingDate;

    @BeforeEach
    void setUp() {
        token = "ERP18" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        openingDate = LocalDate.of(2026, 7, 1);
        SecurityContext.set(owner());
        long categoryId = insert("insert into product_category(parent_id,name,sort_no,status) values (0,?,999,'ENABLED')",
                token + "品类");
        insert("""
                insert into product_series(category_id,name,alias_names,package_unit_category_id,price_unit_category_id,status)
                values (?,?,'',2,2,'ENABLED')
                """, categoryId, token + "系列");
        String partnerNoBase = String.format("%09d", Math.floorMod(token.hashCode(), 1_000_000_000));
        long customerId = insert("""
                insert into partner(business_no,type,type_mask,name,status,memo)
                values (?,'CUSTOMER',1,?,'ENABLED','ERP-18复用既有客户')
                """, partnerNoBase + "1", token + "客户");
        long supplierId = insert("""
                insert into partner(business_no,type,type_mask,name,status,memo)
                values (?,'SUPPLIER',2,?,'ENABLED','ERP-18复用既有供应商')
                """, partnerNoBase + "2", token + "供应商");
        jdbc.update("insert into partner_account(partner_id,account_type,balance,credit_limit) values (?,'CUSTOMER_ADVANCE',0,321)", customerId);
        jdbc.update("insert into partner_account(partner_id,account_type,balance,credit_limit) values (?,'SUPPLIER_PREPAY',0,654)", supplierId);
    }

    @AfterEach
    void clearContext() {
        SecurityContext.clear();
    }

    @Test
    @DisplayName("预检逐行阻断借贷和业务勾稽差异且不形成任何业务事实")
    void preflightFindsReconciliationErrorWithoutBusinessWrites() throws Exception {
        DataMigrationDtos.Detail detail = service.upload(openingDate, file(false));
        assertEquals("PREFLIGHT_FAILED", detail.header().status());
        assertTrue(detail.header().errorRows() > 0);
        assertTrue(detail.rowErrors().stream().anyMatch(row -> row.errorCode().contains("TRIAL_BALANCE_FAILED")
                || row.errorCode().contains("INVENTORY_GL_MISMATCH")));
        assertEquals(0L, count("select count(*) from inventory_batch where source_type='DATA_MIGRATION'"));
        assertEquals(0L, count("select count(*) from accounting_voucher where source_type='DATA_MIGRATION'"));
    }

    @Test
    @DisplayName("同一文件幂等预检、应用、完整勾稽和切换前整批撤销形成闭环")
    void applyCheckAndRollbackAreIdempotentAndAuditable() throws Exception {
        MockMultipartFile file = file(true);
        DataMigrationDtos.Detail preview = service.upload(openingDate, file);
        assertEquals("PREFLIGHT_PASSED", preview.header().status(), preview.rowErrors().toString());
        assertEquals(preview.header().id(), service.upload(openingDate, file).header().id());

        assertEquals("PREFLIGHT_PASSED", preview.header().status(), preview.rowErrors().toString());
        DataMigrationDtos.Detail applied = service.apply(preview.header().id(),
                new DataMigrationDtos.Action("已核对旧系统冻结清单并申请内部切换演练"));
        assertEquals("APPLIED", applied.header().status());
        assertEquals(applied.header().validRows(), applied.header().appliedRows());
        assertEquals("DRAFT", applied.header().openingVoucherStatus());
        assertEquals("PASSED", applied.latestCheck().status());
        assertEquals(1L, count("select count(*) from inventory_batch where source_type='DATA_MIGRATION'"));
        assertEquals(4L, count("select count(*) from data_migration_row where batch_id=? and domain_code='PARTNER_OPENING'",
                preview.header().id()));

        DataMigrationDtos.Detail rolledBack = service.rollback(preview.header().id(),
                new DataMigrationDtos.Action("切换前演练完成，按计划撤销整批期初事实"));
        assertEquals("ROLLED_BACK", rolledBack.header().status());
        assertEquals("CANCELLED", rolledBack.header().openingVoucherStatus());
        assertEquals(0L, count("select count(*) from inventory_batch where source_type='DATA_MIGRATION'"));
        assertEquals(0L, count("select count(*) from treasury_document where document_type like 'OPENING_%'"));
        assertEquals(0L, count("select count(*) from business_bank_account where data_migration_row_id is not null"));
        assertEquals(1L, count("select count(*) from partner_account a join partner p on p.id=a.partner_id where p.name=? and a.account_type='CUSTOMER_ADVANCE' and a.balance=0 and a.credit_limit=321", token + "客户"));
        assertEquals(1L, count("select count(*) from partner_account a join partner p on p.id=a.partner_id where p.name=? and a.account_type='SUPPLIER_PREPAY' and a.balance=0 and a.credit_limit=654", token + "供应商"));
        assertTrue(count("select count(*) from data_migration_event where batch_id=?", preview.header().id()) >= 3);
    }

    @Test
    @DisplayName("确认切换记账期初凭证并锁定整批无痕回滚")
    void cutoverPostsLedgerAndRejectsBatchRollback() throws Exception {
        DataMigrationDtos.Detail preview = service.upload(openingDate, file(true));
        DataMigrationDtos.Detail applied = service.apply(preview.header().id(),
                new DataMigrationDtos.Action("旧系统已经冻结并完成库存往来资金复核"));
        DataMigrationDtos.Detail cutover = service.cutover(applied.header().id(),
                new DataMigrationDtos.Action("负责人确认内部切换且接受期初口径"));
        assertEquals("CUTOVER", cutover.header().status());
        assertEquals("POSTED", cutover.header().openingVoucherStatus());
        assertEquals("PASSED", cutover.latestCheck().status());
        assertEquals(count("select count(*) from accounting_voucher_line where voucher_id=?", cutover.header().openingVoucherId()),
                count("select count(*) from accounting_ledger_entry where voucher_id=?", cutover.header().openingVoucherId()));
        assertThrows(BusinessException.class, () -> service.rollback(cutover.header().id(),
                new DataMigrationDtos.Action("切换后错误尝试整批撤销应被拒绝")));
    }

    private MockMultipartFile file(boolean balanced) throws Exception {
        OpeningMigrationWorkbook.TemplateFile template = workbook.template();
        try (XSSFWorkbook book = new XSSFWorkbook(new ByteArrayInputStream(template.content()));
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            add(book, "往来单位", map("来源键*", "CUST", "名称*", token + "客户", "角色*", "CUSTOMER",
                    "联系人", "张三", "手机", "ERP18-CUSTOMER-PHONE", "授信额度", "10000", "备注", token));
            add(book, "往来单位", map("来源键*", "SUP", "名称*", token + "供应商", "角色*", "SUPPLIER",
                    "联系人", "李四", "手机", "ERP18-SUPPLIER-PHONE", "授信额度", "0", "备注", token));
            add(book, "仓库", map("来源键*", "WH", "名称*", token + "仓库", "位置", "青岛", "收货地址", "内部演练"));
            add(book, "商品", map("来源键*", "PROD", "SKU*", token + "-SKU", "名称*", token + "铜材",
                    "商品系列*", token + "系列", "规格", "验收规格", "主库存单位*", "kg", "默认售价", "60",
                    "规格属性JSON", "{}"));
            add(book, "库存期初", map("来源键*", "INV", "商品来源键*", "PROD", "仓库来源键*", "WH",
                    "批次号*", token + "-B1", "数量*", "10", "单位成本*", "50", "生产日期", "2026-06-30", "备注", token));
            add(book, "往来期初", opening("AR", "CUST", "RECEIVABLE", "300", "2026-07-31", "OLD-AR"));
            add(book, "往来期初", opening("AP", "SUP", "PAYABLE", "200", "2026-07-31", "OLD-AP"));
            add(book, "往来期初", opening("ADV", "CUST", "CUSTOMER_ADVANCE", "100", "2026-07-31", "OLD-ADV"));
            add(book, "往来期初", opening("PRE", "SUP", "SUPPLIER_PREPAY", "50", "2026-07-31", "OLD-PRE"));
            add(book, "资金期初", map("来源键*", "BANK", "类型*", "BANK", "账户编码*", token + "-BANK",
                    "账户名称*", "基本户", "开户行", "演练银行", "银行账号", token + "001", "科目编码*", "1002", "余额*", "1000", "币种", "CNY"));
            add(book, "资金期初", map("来源键*", "CASH", "类型*", "CASH", "账户编码*", token + "-CASH",
                    "账户名称*", "现金", "科目编码*", "1001", "余额*", "100", "币种", "CNY"));
            add(book, "总账期初", ledger("GL-1001", "1001", "100", "", "", "", "", "现金期初"));
            add(book, "总账期初", ledger("GL-1002", "1002", "1000", "", "", "", "", "银行期初"));
            add(book, "总账期初", ledger("GL-1122", "1122", "300", "", "CUST", "", "", "应收期初"));
            add(book, "总账期初", ledger("GL-1123", "1123", "50", "", "SUP", "", "", "预付期初"));
            add(book, "总账期初", ledger("GL-1405", "1405", "500", "", "", "PROD", "WH", "库存期初"));
            add(book, "总账期初", ledger("GL-2202", "2202", "", "200", "SUP", "", "", "应付期初"));
            add(book, "总账期初", ledger("GL-2203", "2203", "", "100", "CUST", "", "", "预收期初"));
            add(book, "总账期初", ledger("GL-3001", "3001", "", balanced ? "1650" : "1649", "", "", "", "期初权益"));
            book.write(output);
            return new MockMultipartFile("file", token + ".xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", output.toByteArray());
        }
    }

    private Map<String, String> opening(String key, String partner, String type, String amount, String due, String basis) {
        return map("来源键*", key, "往来单位来源键*", partner, "类型*", type, "金额*", amount,
                "到期日*", due, "原单号*", basis, "备注", token);
    }

    private Map<String, String> ledger(String key, String code, String debit, String credit,
                                       String partner, String product, String warehouse, String summary) {
        return map("来源键*", key, "科目编码*", code, "借方", debit, "贷方", credit,
                "往来单位来源键", partner, "商品来源键", product, "仓库来源键", warehouse, "摘要*", summary);
    }

    private void add(XSSFWorkbook book, String sheetName, Map<String, String> values) {
        Sheet sheet = book.getSheet(sheetName);
        Row header = sheet.getRow(0);
        Map<String, Integer> columns = new HashMap<>();
        for (int i = 0; i < header.getLastCellNum(); i++) columns.put(header.getCell(i).getStringCellValue(), i);
        Row row = sheet.createRow(sheet.getLastRowNum() + 1);
        values.forEach((name, value) -> row.createCell(columns.get(name)).setCellValue(value));
    }

    private Map<String, String> map(String... values) {
        Map<String, String> result = new HashMap<>();
        for (int i = 0; i < values.length; i += 2) result.put(values[i], values[i + 1]);
        return result;
    }

    private SecurityContext.Principal owner() {
        return new SecurityContext.Principal(918L, 918L, "ERP-18老板", "ERP18-OWNER", 1L, "本企业", token + "-owner",
                false, List.of("老板"), Set.of("data.migration.view", "data.migration.manage", "data.migration.apply",
                "data.migration.rollback", "data.migration.cutover", "accounting.review", "accounting.post"));
    }

    private long insert(String sql, Object... args) {
        jdbc.update(sql, args);
        return Objects.requireNonNull(jdbc.queryForObject("select last_insert_id()", Long.class));
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0L : value;
    }

    @AfterTransaction
    void rollbackHasNoAcceptanceResidue() {
        assertEquals(0L, count("select count(*) from data_migration_batch where source_file_name like ?", token + "%"));
        assertEquals(0L, count("select count(*) from product where sku like ?", token + "%"));
        assertEquals(0L, count("select count(*) from partner where name like ?", token + "%"));
    }
}
