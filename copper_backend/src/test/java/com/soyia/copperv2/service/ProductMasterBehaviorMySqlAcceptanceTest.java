package com.soyia.copperv2.service;

import com.soyia.copperv2.controller.ComplexProductMasterController;
import com.soyia.copperv2.dto.InboundCreateRequest;
import com.soyia.copperv2.dto.AlignedDtos;
import com.soyia.copperv2.dto.MasterDataDtos;
import com.soyia.copperv2.dto.PackagingDtos;
import com.soyia.copperv2.dto.ProductImportDtos;
import com.soyia.copperv2.dto.ProductMasterDataDtos;
import com.soyia.copperv2.dto.ProductPurchaseOptionsDto;
import com.soyia.copperv2.dto.PurchaseCreateRequest;
import com.soyia.copperv2.dto.SimpleCreateRequests;
import com.soyia.copperv2.security.ForbiddenException;
import com.soyia.copperv2.security.SecurityContext;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIfEnvironmentVariable(named = "COPPER_COMPLEX_PRODUCT_ACCEPTANCE", matches = "true")
class ProductMasterBehaviorMySqlAcceptanceTest {
    private static final Set<String> MASTER_PERMISSIONS = Set.of(
            "product.master.complex.view", "product.master.complex.manage",
            "product.view", "product.manage",
            "product.cost.view", "product.supplier.offer.manage",
            "inventory.quality.view", "inventory.quality.manage");

    @Autowired private JdbcTemplate jdbc;
    @Autowired private Validator validator;
    @Autowired private ProductImportFileParser fileParser;
    @Autowired private ProductImportService importService;
    @Autowired private ProductMasterDataService productMasterDataService;
    @Autowired private ComplexProductMasterService complexProductMasterService;
    @Autowired private CopperService copperService;
    @Autowired private PackagingService packagingService;

    private String runKey;

    @BeforeEach
    void setUp() {
        runKey = UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        usePrincipal(1L, "系统管理员", MASTER_PERMISSIONS);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContext.clear();
    }

    @Test
    void generatedCsvAndXlsxTemplatesRoundTripThroughTheRealParser() throws Exception {
        List<String> headers = List.of(
                "产品系列*", "SKU", "商品名称", "库存单位*", "牌号", "材料状态",
                "执行标准", "规格备注", "条码", "供应商原始编号",
                "属性:外径", "属性:壁厚", "属性:长度");
        List<String> values = List.of(
                "通用圆形铜管", "PMB-" + runKey, "模板往返验收", "kg",
                "T2", "O", "GB/T 17791", "说明,保留逗号",
                "", "", "12.700000", "0.800000", "6000.000000");
        ProductImportFileParser.TemplateDefinition definition =
                new ProductImportFileParser.TemplateDefinition(
                        "铜管材", "通用圆形铜管", headers, List.of());

        ProductImportFileParser.TemplateFile csvTemplate = fileParser.template("csv", definition);
        byte[] csvContent = (new String(csvTemplate.content(), StandardCharsets.UTF_8)
                + csvLine(values) + "\n").getBytes(StandardCharsets.UTF_8);
        ProductImportFileParser.ParsedFile csv = fileParser.parse(new MockMultipartFile(
                "file", csvTemplate.fileName(), csvTemplate.contentType(), csvContent));

        ProductImportFileParser.TemplateFile xlsxTemplate = fileParser.template("xlsx", definition);
        byte[] xlsxContent;
        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(xlsxTemplate.content()));
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.getSheetAt(0);
            Row row = sheet.createRow(1);
            for (int i = 0; i < values.size(); i++) {
                row.createCell(i).setCellValue(values.get(i));
            }
            workbook.write(output);
            xlsxContent = output.toByteArray();
        }
        ProductImportFileParser.ParsedFile xlsx = fileParser.parse(new MockMultipartFile(
                "file", xlsxTemplate.fileName(), xlsxTemplate.contentType(), xlsxContent));

        assertEquals("CSV", csv.format());
        assertEquals("XLSX", xlsx.format());
        assertEquals(csv.headers(), xlsx.headers());
        assertEquals(csv.rows().get(0).values(), xlsx.rows().get(0).values());
        assertEquals("说明,保留逗号", csv.rows().get(0).values().get("规格备注"));
        assertEquals("12.700000", xlsx.rows().get(0).values().get("属性:外径"));
    }

    @Test
    void everyEnabledSeriesProvidesLeanCsvAndXlsxTemplatesWithExamples() throws Exception {
        List<ProductImportDtos.SeriesOption> enabledSeries = importService.templateInfo().series()
                .stream().filter(series -> "ENABLED".equals(series.status())).toList();
        assertFalse(enabledSeries.isEmpty());

        for (ProductImportDtos.SeriesOption series : enabledSeries) {
            ProductImportFileParser.TemplateFile csv = importService.template("csv", series.id());
            String csvContent = new String(csv.content(), StandardCharsets.UTF_8);
            assertTrue(csv.fileName().endsWith(".csv"), series.name());
            assertTrue(csvContent.contains(ProductImportFileParser.EXAMPLE_SKU_PREFIX),
                    series.name());
            for (String removed : List.of(
                    "默认单价", "启用状态", "来源URL", "来源文件版本", "来源页码",
                    "供应商原始编号")) {
                assertFalse(csvContent.contains(removed), series.name() + " / " + removed);
            }

            ProductImportFileParser.TemplateFile xlsx = importService.template("xlsx", series.id());
            assertTrue(xlsx.fileName().endsWith(".xlsx"), series.name());
            try (XSSFWorkbook workbook = new XSSFWorkbook(
                    new ByteArrayInputStream(xlsx.content()))) {
                assertEquals(3, workbook.getNumberOfSheets(), series.name());
                assertEquals("商品数据（上传此表）",
                        workbook.getSheetAt(0).getSheetName(), series.name());
                assertEquals("填写示例（请勿上传）",
                        workbook.getSheetAt(1).getSheetName(), series.name());
                assertEquals(ProductImportFileParser.EXAMPLE_SKU_PREFIX + series.id() + "-01",
                        workbook.getSheetAt(1).getRow(1).getCell(1).getStringCellValue(),
                        series.name());
                Row header = workbook.getSheetAt(0).getRow(0);
                List<String> headerValues = new ArrayList<>();
                for (int index = 0; index < header.getLastCellNum(); index++) {
                    headerValues.add(header.getCell(index).getStringCellValue());
                }
                for (String removed : List.of(
                        "默认单价", "启用状态", "来源URL", "来源文件版本", "来源页码",
                        "供应商原始编号")) {
                    assertFalse(headerValues.contains(removed), series.name() + " / " + removed);
                }
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sixDecimalQuantityCases")
    void quantityDtosAcceptOneMillionthAndRejectASeventhDecimal(
            String label, Object accepted, Object rejected, String property) {
        Set<ConstraintViolation<Object>> acceptedViolations = validator.validate(accepted);
        Set<ConstraintViolation<Object>> rejectedViolations = validator.validate(rejected);

        assertTrue(acceptedViolations.isEmpty(), label + " should accept 0.000001");
        assertTrue(rejectedViolations.stream()
                        .anyMatch(item -> property.equals(item.getPropertyPath().toString())),
                label + " should reject 0.0000001");
    }

    @Test
    void retiredBomHttpEndpointIsNotExposed() throws Exception {
        ComplexProductMasterService service = mock(ComplexProductMasterService.class);
        LocalValidatorFactoryBean mvcValidator = new LocalValidatorFactoryBean();
        mvcValidator.afterPropertiesSet();
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new ComplexProductMasterController(service))
                .setValidator(mvcValidator)
                .build();

        mockMvc.perform(post("/products/1/bom/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bomJson("0.000001")))
                .andExpect(status().isNotFound());
        verifyNoInteractions(service);
    }

    @Test
    void importRejectsALiveLeaseRecoversAnExpiredLeaseAndNeverRepeatsASuccessfulRow() {
        String sku = "PMB-IMP-" + runKey;
        String baseUnit = seriesBaseUnit("保温管材");
        String csv = "\uFEFF产品系列*,SKU,商品名称,库存单位*,属性:材质,属性:内径,属性:厚度,属性:长度\n"
                + "保温管材," + sku + ",租约恢复验收," + baseUnit
                + ",橡塑-" + runKey + ",12.000000,10.000000,2000.000000\n";
        ProductImportDtos.Preview preview = importService.preview(new MockMultipartFile(
                "file", "lease-" + runKey + ".csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8)), null);

        assertEquals("PREVIEWED", preview.job().status());
        assertEquals(1, preview.rows().size());
        assertEquals("VALID", preview.rows().get(0).validationStatus());
        assertEquals("CREATE", preview.rows().get(0).proposedAction());

        Long jobId = preview.job().id();
        jdbc.update("""
                update product_import_job
                set status='COMMITTING',claim_token=?,heartbeat_at=current_timestamp,
                    lock_version=lock_version+1
                where id=?
                """, UUID.randomUUID().toString(), jobId);
        int activeVersion = intValue(
                "select lock_version from product_import_job where id=?", jobId);

        BusinessException activeLease = assertThrows(BusinessException.class, () ->
                importService.commit(jobId,
                        new ProductImportDtos.CommitRequest(activeVersion, false)));
        assertTrue(activeLease.getMessage().contains("正在提交"));
        assertEquals(0, count("select count(*) from product where sku=?", sku));

        jdbc.update("""
                update product_import_job
                set heartbeat_at=date_sub(current_timestamp,interval 6 minute)
                where id=?
                """, jobId);
        ProductImportDtos.CommitResult recovered = importService.commit(jobId,
                new ProductImportDtos.CommitRequest(activeVersion, false));

        assertEquals("COMPLETED", recovered.status());
        assertEquals(1, recovered.createdRows());
        assertEquals(0, recovered.failedRows());
        assertEquals(1, count("select count(*) from product where sku=?", sku));
        Long importedProductId = id("select imported_product_id from product_import_row where job_id=?", jobId);
        assertTrue(importedProductId > 0);
        assertEquals("SUCCEEDED", jdbc.queryForObject(
                "select commit_status from product_import_row where job_id=?", String.class, jobId));
        assertEquals(1, count("""
                select count(*) from product_import_event
                where job_id=? and event_type='COMMIT_RECOVERED'
                """, jobId));

        ProductImportDtos.CommitResult replay = importService.commit(jobId,
                new ProductImportDtos.CommitRequest(recovered.lockVersion(), false));
        assertEquals("COMPLETED", replay.status());
        assertEquals(1, replay.createdRows());
        assertEquals(1, count("select count(*) from product where sku=?", sku));
        assertEquals(importedProductId,
                id("select imported_product_id from product_import_row where job_id=?", jobId));
        assertEquals(1, count("""
                select count(*) from product_import_event
                where job_id=? and event_type='ROW_COMMITTED' and event_status='SUCCESS'
                """, jobId));
    }

    @Test
    void importWithOnlySkippedRowsCanCompleteWithoutProductWritePermission() {
        String sku = "PMB-SKIP-" + runKey;
        String baseUnit = seriesBaseUnit("保温管材");
        String csv = "\uFEFF产品系列*,SKU,商品名称,库存单位*,属性:材质,属性:内径,属性:厚度,属性:长度\n"
                + "保温管材," + sku + ",全跳过审计验收," + baseUnit
                + ",橡塑-" + runKey + ",18.000000,12.000000,2000.000000\n";
        ProductImportDtos.Preview preview = importService.preview(new MockMultipartFile(
                "file", "skip-" + runKey + ".csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8)), null);
        ProductImportDtos.RowView row = preview.rows().get(0);
        assertEquals("CREATE", row.proposedAction());

        ProductImportDtos.Preview decided = importService.decide(
                preview.job().id(), row.id(),
                new ProductImportDtos.RowDecision(
                        "SKIP", null, "本批次仅保留原文件与逐行审计", row.rowVersion()));
        SecurityContext.set(new SecurityContext.Principal(
                1L, 1L, "导入审计验收", "PMB", 1L, "内部验收", "PMB-" + runKey,
                false, List.of("商品资料管理员"),
                Set.of("product.master.complex.view", "product.master.complex.manage")));

        ProductImportDtos.CommitResult result = importService.commit(
                decided.job().id(),
                new ProductImportDtos.CommitRequest(decided.job().lockVersion(), false));

        assertEquals("COMPLETED", result.status());
        assertEquals(0, result.createdRows());
        assertEquals(1, result.skippedRows());
        assertEquals(0, result.failedRows());
        assertEquals(0, count("select count(*) from product where sku=?", sku));
        assertEquals("SKIPPED", jdbc.queryForObject("""
                select commit_status from product_import_row where job_id=?
                """, String.class, decided.job().id()));
    }

    @Test
    void importJobsAndIdempotencyKeysAreIsolatedByOperator() {
        String sku = "PMB-OWNER-" + runKey;
        String baseUnit = seriesBaseUnit("保温管材");
        String csv = "\uFEFF产品系列*,SKU,商品名称,库存单位*,属性:材质,属性:内径,属性:厚度,属性:长度\n"
                + "保温管材," + sku + ",导入归属验收," + baseUnit
                + ",橡塑-" + runKey + ",19.000000,13.000000,2000.000000\n";
        MockMultipartFile file = new MockMultipartFile(
                "file", "owner-" + runKey + ".csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));
        ProductImportDtos.Preview first = importService.preview(file, null);

        long secondUserId = 8_100_000_000L + Math.floorMod(runKey.hashCode(), 999_999);
        usePrincipal(secondUserId, "第二导入操作人", MASTER_PERMISSIONS);
        ProductImportDtos.Preview second = importService.preview(file, null);

        assertNotEquals(first.job().id(), second.job().id(),
                "相同文件的幂等键必须按操作人隔离");
        assertTrue(importService.jobs().stream()
                .anyMatch(job -> Objects.equals(job.id(), second.job().id())));
        assertFalse(importService.jobs().stream()
                .anyMatch(job -> Objects.equals(job.id(), first.job().id())));
        assertThrows(ForbiddenException.class,
                () -> importService.detail(first.job().id()));
        assertThrows(ForbiddenException.class,
                () -> importService.sourceFile(first.job().id()));
        assertThrows(ForbiddenException.class, () -> importService.decide(
                first.job().id(), first.rows().get(0).id(),
                new ProductImportDtos.RowDecision(
                        "SKIP", null, "跨操作人不得修改",
                        first.rows().get(0).rowVersion())));
        assertThrows(ForbiddenException.class, () -> importService.commit(
                first.job().id(),
                new ProductImportDtos.CommitRequest(first.job().lockVersion(), false)));
    }

    @Test
    @Transactional
    void productScopeFailsClosedForCommonWritesAndImportCreateOrUpdate() {
        SimpleCreateRequests.ProductCreate allowedRequest = productRequestForSeries(
                "保温管材", "PMB-SCOPE-ALLOW-" + runKey, "允许商品", 51,
                seriesBaseUnit("保温管材"), "PUBLISHED");
        SimpleCreateRequests.ProductCreate blockedRequest = productRequestForSeries(
                "保温管材", "PMB-SCOPE-BLOCK-" + runKey, "受限商品", 52,
                seriesBaseUnit("保温管材"), "PUBLISHED");
        Long allowedProductId = copperService.createProduct(allowedRequest);
        Long blockedProductId = copperService.createProduct(blockedRequest);

        restrictProductScopeTo(allowedProductId);

        assertThrows(ForbiddenException.class, () ->
                copperService.updateProduct(blockedProductId, blockedRequest));
        assertThrows(ForbiddenException.class, () ->
                copperService.createProduct(productRequestForSeries(
                        "保温管材", "PMB-SCOPE-NEW-" + runKey, "越权新建", 53,
                        seriesBaseUnit("保温管材"), "PUBLISHED")));

        ProductImportDtos.Preview allowedUpdate = importService.preview(
                importFileForExistingProduct(allowedProductId, "允许范围更新"), null);
        assertEquals("SAFE_UPDATE", allowedUpdate.rows().get(0).proposedAction(),
                () -> allowedUpdate.rows().get(0).validationMessage()
                        + " / " + allowedUpdate.conflicts());
        assertEquals(allowedProductId, allowedUpdate.rows().get(0).targetProductId());

        ProductImportDtos.Preview blockedUpdate = importService.preview(
                importFileForExistingProduct(blockedProductId, "越权范围更新"), null);
        assertNotEquals("SAFE_UPDATE", blockedUpdate.rows().get(0).proposedAction());
        assertTrue(blockedUpdate.rows().get(0).targetProductId() == null
                || blockedUpdate.rows().get(0).targetProductId() == 0);

        String baseUnit = seriesBaseUnit("保温管材");
        String createCsv = "\uFEFF产品系列*,SKU,商品名称,库存单位*,属性:材质,属性:内径,属性:厚度,属性:长度\n"
                + "保温管材,PMB-SCOPE-IMPORT-" + runKey + ",越权导入新建,"
                + baseUnit + ",橡塑-" + runKey
                + ",25.000000,15.000000,2000.000000\n";
        ProductImportDtos.Preview deniedCreate = importService.preview(
                new MockMultipartFile("file", "scope-create-" + runKey + ".csv",
                        "text/csv", createCsv.getBytes(StandardCharsets.UTF_8)), null);
        assertEquals("INVALID", deniedCreate.rows().get(0).validationStatus());
        assertEquals("CONFLICT", deniedCreate.rows().get(0).proposedAction());
        assertTrue(deniedCreate.rows().get(0).validationMessage()
                .contains("数据范围受限"));

        ProductMasterDataDtos.ProductDuplicateCheck redacted =
                productMasterDataService.duplicateCheck(blockedRequest);
        assertNotEquals("NONE", redacted.result());
        assertNull(redacted.existingProductId());
        assertEquals("", redacted.existingSku());
        assertEquals("", redacted.existingName());
        assertThrows(ForbiddenException.class, () ->
                productMasterDataService.duplicateCheck(
                        allowedRequest, blockedProductId));
    }

    @Test
    @Transactional
    void unitOptionsRequireCostPermissionAndOnlyExposeTradeableProducts() {
        Long productId = createInsulationProduct("UNIT-OPTIONS", 54);
        usePrincipal(8_200_000_000L + Math.floorMod(runKey.hashCode(), 999_999),
                "无成本权限操作人",
                Set.of("product.master.complex.view", "product.view"));

        assertThrows(ForbiddenException.class, () ->
                copperService.productPurchaseOptions(
                        productId, null, seriesBaseUnit("保温管材")));

        usePrincipal(1L, "系统管理员", MASTER_PERMISSIONS);
        jdbc.update("update product set status='DISABLED' where id=?", productId);
        BusinessException disabled = assertThrows(BusinessException.class, () ->
                copperService.productUnitOptions(
                        productId, null, "CUSTOMER",
                        seriesBaseUnit("保温管材")));
        assertTrue(disabled.getMessage().contains("已停用"));

        jdbc.update("""
                update product set status='ENABLED',identity_status='PENDING_REVIEW'
                where id=?
                """, productId);
        BusinessException pending = assertThrows(BusinessException.class, () ->
                copperService.productUnitOptions(
                        productId, null, "CUSTOMER",
                        seriesBaseUnit("保温管材")));
        assertTrue(pending.getMessage().contains("商品必填规格尚未补齐"));
    }

    @Test
    @Transactional
    void validPackagingTemplateCreatesItsInitialRevision() {
        String code = "PMB-CREATE-" + runKey;
        PackagingDtos.ProfileSave profile = new PackagingDtos.ProfileSave(
                0, "kg", "kg", "kg", "kg", "kg", "REJECT",
                List.of(), List.of(), "创建模板验收");

        PackagingDtos.TemplateRow created = packagingService.createTemplate(
                new PackagingDtos.TemplateSave(
                        code, "可创建模板-" + runKey,
                        "验证模板与首个版本原子创建", "ENABLED", 0,
                        profile, "创建模板验收"));

        assertEquals(code, created.code());
        assertEquals(1, created.currentRevisionNo());
        assertEquals(1, count("""
                select count(*) from packaging_template_revision
                where template_id=? and revision_no=1
                """, created.id()));

        PackagingDtos.TemplateRow renamed = packagingService.updateTemplateMetadata(
                created.id(), new PackagingDtos.TemplateMetadataSave(
                        "已改名模板-" + runKey, "只修改基本信息", created.lockVersion()));
        assertEquals("已改名模板-" + runKey, renamed.name());
        assertEquals(1, renamed.currentRevisionNo());

        PackagingDtos.TemplateRow disabled = packagingService.updateTemplateStatus(
                created.id(), new PackagingDtos.TemplateStatusSave(
                        "DISABLED", renamed.lockVersion(), "暂不用于新系列"));
        assertEquals("DISABLED", disabled.status());
        assertEquals(1, disabled.currentRevisionNo());
        assertEquals(1, count("""
                select count(*) from packaging_template_revision
                where template_id=?
                """, created.id()));
    }

    @Test
    @Transactional
    void packagingTemplateAndRuleStatusesUseAStrictWhitelist() {
        PackagingDtos.ProfileSave emptyProfile = new PackagingDtos.ProfileSave(
                0, "kg", "kg", "kg", "kg", "kg", "REJECT",
                List.of(), List.of(), "状态白名单验收");
        BusinessException templateStatus = assertThrows(BusinessException.class,
                () -> packagingService.createTemplate(new PackagingDtos.TemplateSave(
                        "PMB-STRICT-" + runKey, "严格状态模板-" + runKey,
                        "", "ACTIVE", 0, emptyProfile, "状态白名单验收")));
        assertTrue(templateStatus.getMessage().contains("ENABLED或DISABLED"));
        assertEquals(0, count(
                "select count(*) from packaging_template where code=?",
                "PMB-STRICT-" + runKey));

        Long productId = createInsulationProduct("STRICT-RULE", 55);
        PackagingDtos.Profile current = packagingService.profile(productId);
        PackagingDtos.ProfileSave invalidRule = new PackagingDtos.ProfileSave(
                current.versionNo(), current.baseUnit(), current.baseUnit(),
                current.baseUnit(), current.baseUnit(), current.baseUnit(),
                "REJECT", List.of(new PackagingDtos.RuleSave(
                        "箱", current.baseUnit(), "FIXED", BigDecimal.TEN, null,
                        true, true, true, true, true, true, 10, "ACTIVE")),
                List.of(), "未知规则状态");
        BusinessException ruleStatus = assertThrows(BusinessException.class,
                () -> packagingService.saveProfile(productId, invalidRule));
        assertTrue(ruleStatus.getMessage().contains("ENABLED或DISABLED"));
    }

    @Test
    void productCreationRejectsAUnitThatConflictsWithTheSeriesPackagingTemplate() {
        String sku = "PMB-UNIT-REJECT-" + runKey;
        SimpleCreateRequests.ProductCreate request = productRequestForSeries(
                "保温管材", sku, "错误单位验收", 16,
                "kg", "PENDING_REVIEW");

        BusinessException error = assertThrows(
                BusinessException.class, () -> copperService.createProduct(request));

        assertTrue(error.getMessage().contains("不能提交"));
        assertTrue(error.getMessage().contains("静默改写"));
        assertEquals(0, count("select count(*) from product where sku=?", sku));
    }

    @Test
    @Transactional
    void administratorConfigurationMakesOrdinaryAndSpecialProductsDirectlyTradeable() {
        Long categoryId = id("select id from product_category order by id limit 1");

        Long simpleSeriesId = copperService.createProductSeries(
                new AlignedDtos.ProductSeriesCreate(
                        categoryId, "MANUAL-" + runKey, "手工经营品-" + runKey, "", null, null, "ENABLED"));
        assertEquals(1, intValue("""
                select count(*) from product_identity_schema_revision
                where product_series_id=? and version_no=1
                """, simpleSeriesId));

        Long simpleA = copperService.createProduct(new SimpleCreateRequests.ProductCreate(
                categoryId, simpleSeriesId, null,
                "PMB-SIMPLE-A-" + runKey, "手工经营品A",
                "厂家临时牌号-" + runKey, "厂家状态-" + runKey,
                "厂家标准-" + runKey, "", "{}", "", "", "kg",
                BigDecimal.ZERO, "ENABLED", List.of(), "PUBLISHED"));
        Long simpleB = copperService.createProduct(new SimpleCreateRequests.ProductCreate(
                categoryId, simpleSeriesId, null,
                "PMB-SIMPLE-B-" + runKey, "手工经营品B",
                "另一厂家牌号-" + runKey, "", "",
                "", "{}", "", "", "kg",
                BigDecimal.ZERO, "ENABLED", List.of(), "PUBLISHED"));
        assertNotEquals(simpleA, simpleB,
                "未配置区分规格时应按SKU建档，不能把整个系列误判为同一商品");
        assertEquals(2, count("""
                select count(*) from product
                where product_series_id=? and identity_status='PUBLISHED'
                """, simpleSeriesId));

        Long configuredSeriesId = copperService.createProductSeries(
                new AlignedDtos.ProductSeriesCreate(
                        categoryId, "CONFIG-" + runKey, "管理员配置品-" + runKey, "", null, null, "ENABLED"));
        Long customFieldId = copperService.createCustomField(
                new MasterDataDtos.CustomFieldCreate(
                        "经营规格-" + runKey, "TEXT", "TEXT", null, "[]", "ENABLED"));
        Long seriesFieldId = copperService.createSeriesField(
                new MasterDataDtos.SeriesFieldCreate(
                        configuredSeriesId, customFieldId, "经营规格", "[]",
                        null, null, "", true, true, true, true, true,
                        null, null, null, 10, "ENABLED"));
        assertEquals(2, intValue("""
                select identity_schema_version from product_series where id=?
                """, configuredSeriesId));
        assertEquals(1, count("""
                select count(*) from product_identity_rule
                where product_series_id=? and schema_version=2
                  and source_type='SERIES_FIELD' and series_field_id=?
                """, configuredSeriesId, seriesFieldId));

        SimpleCreateRequests.ProductCreate configured =
                new SimpleCreateRequests.ProductCreate(
                        categoryId, configuredSeriesId, null,
                        "PMB-CONFIGURED-A-" + runKey, "管理员配置特殊产品",
                        "镀银导体自定义牌号-" + runKey, "", "",
                        "", "{}", "", "", "kg", BigDecimal.ZERO, "ENABLED",
                        List.of(new SimpleCreateRequests.ProductFieldValueCreate(
                                seriesFieldId, "7×0.25 镀银")),
                        "PUBLISHED");
        Long configuredProductId = copperService.createProduct(configured);
        assertEquals("PUBLISHED", jdbc.queryForObject(
                "select identity_status from product where id=?",
                String.class, configuredProductId));
        assertEquals("镀银导体自定义牌号-" + runKey, jdbc.queryForObject(
                "select grade from product where id=?",
                String.class, configuredProductId));

        // Simulate a same-schema legacy row created before identity_signature was backfilled.
        // Duplicate preflight must still evaluate its stored field values instead of
        // discovering the collision only during the final spec_signature write.
        jdbc.update("update product set identity_signature=null where id=?", configuredProductId);
        ProductMasterDataDtos.ProductDuplicateCheck duplicate =
                productMasterDataService.duplicateCheck(
                        new SimpleCreateRequests.ProductCreate(
                                categoryId, configuredSeriesId, null,
                                "PMB-CONFIGURED-B-" + runKey, "重复规格",
                                "任意未维护牌号-" + runKey, "", "",
                                "", "{}", "", "", "kg", BigDecimal.ZERO, "ENABLED",
                                List.of(new SimpleCreateRequests.ProductFieldValueCreate(
                                        seriesFieldId, "7×0.25 镀银")),
                                "PUBLISHED"),
                        null);
        assertEquals("EXACT", duplicate.result());
    }

    @Test
    void pendingProductSeriesChangePublishesANewAlignedPackagingVersion() {
        String sku = "PMB-UNIT-REBASE-" + runKey;
        Long productId = copperService.createProduct(productRequestForSeries(
                "保温管材", sku, "系列换绑前", 17,
                seriesBaseUnit("保温管材"), "PENDING_REVIEW"));
        int beforeRevision = intValue("""
                select version_no from product_measure_policy where product_id=?
                """, productId);

        copperService.updateProduct(productId, productRequestForSeries(
                "通用圆形铜管", sku, "系列换绑后", 18,
                seriesBaseUnit("通用圆形铜管"), "PENDING_REVIEW"));

        Map<String, Object> aligned = jdbc.queryForMap("""
                select p.product_series_id,p.unit,m.base_unit,m.version_no
                from product p
                join product_measure_policy m on m.product_id=p.id
                where p.id=?
                """, productId);
        assertEquals(id("select id from product_series where name='通用圆形铜管'"),
                ((Number) aligned.get("product_series_id")).longValue());
        assertEquals(seriesBaseUnit("通用圆形铜管"), aligned.get("unit"));
        assertEquals(aligned.get("unit"), aligned.get("base_unit"));
        assertTrue(((Number) aligned.get("version_no")).intValue() > beforeRevision);
    }

    @Test
    @Transactional
    void bomRejectsBothDirectAndThreeNodeTransitiveCycles() {
        Long productA = createInsulationProduct("A", 11);
        Long productB = createInsulationProduct("B", 12);
        Long productC = createInsulationProduct("C", 13);
        String unit = seriesBaseUnit("保温管材");

        BusinessException direct = assertThrows(BusinessException.class, () ->
                complexProductMasterService.publishBom(productA,
                        bomSave("SELF", productA, unit, 0)));
        assertTrue(direct.getMessage().contains("自身"));

        ProductMasterDataDtos.Bom aToB = complexProductMasterService.publishBom(
                productA, bomSave("A-B", productB, unit, 0));
        ProductMasterDataDtos.Bom aToBTemporary = complexProductMasterService.publishBom(
                productA, bomSave("A-B-TEMP", productB, unit, 1,
                        LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31)));
        ProductMasterDataDtos.Bom bToC = complexProductMasterService.publishBom(
                productB, bomSave("B-C", productC, unit, 0));
        assertEquals(productB, aToB.items().get(0).componentProductId());
        assertEquals(2, aToBTemporary.revisionNo());
        assertEquals(productC, bToC.items().get(0).componentProductId());
        List<ProductMasterDataDtos.BomRevision> bomHistory =
                complexProductMasterService.bomRevisions(productA);
        assertEquals(2, bomHistory.size());
        assertEquals(List.of(2, 1),
                bomHistory.stream().map(ProductMasterDataDtos.BomRevision::revisionNo).toList());
        assertEquals("PMB-A-B-" + runKey, bomHistory.get(0).bomCodeSnapshot());
        assertEquals("BOM行为验收-A-B-TEMP", bomHistory.get(0).nameSnapshot());
        assertEquals("ENABLED", bomHistory.get(0).headStatusSnapshot());
        assertTrue(bomHistory.get(0).headSnapshotAvailable());
        assertTrue(bomHistory.get(0).currentRevision());
        assertEquals("BOM行为验收-A-B", bomHistory.get(1).nameSnapshot());
        assertFalse(bomHistory.get(1).currentRevision());
        assertEquals(1, complexProductMasterService
                .effectiveBom(productA, LocalDate.of(2026, 7, 25)).revisionNo());
        assertEquals(2, complexProductMasterService
                .effectiveBom(productA, LocalDate.of(2026, 8, 15)).revisionNo());
        assertNull(complexProductMasterService
                .effectiveBom(productA, LocalDate.of(2026, 9, 1)),
                "较新的限期版本过期后，旧BOM不得重新复活");

        BusinessException transitive = assertThrows(BusinessException.class, () ->
                complexProductMasterService.publishBom(productC,
                        bomSave("C-A", productA, unit, 0)));
        assertTrue(transitive.getMessage().contains("传递循环"));
        assertEquals(0, count("select count(*) from product_bom where parent_product_id=?", productC));
    }

    @Test
    @Transactional
    void bomRejectsCurrentCycleHiddenByAFutureHeadRevision() {
        Long productA = createInsulationProduct("TEMPORAL-A", 14);
        Long productB = createInsulationProduct("TEMPORAL-B", 15);
        Long productC = createInsulationProduct("TEMPORAL-C", 16);
        String unit = seriesBaseUnit("保温管材");

        complexProductMasterService.publishBom(productA,
                bomSave("TEMPORAL-A-C", productC, unit, 0,
                        LocalDate.of(2026, 7, 1), null));
        complexProductMasterService.publishBom(productA,
                bomSave("TEMPORAL-A-B-FUTURE", productB, unit, 1,
                        LocalDate.of(2026, 9, 1), null));

        BusinessException temporal = assertThrows(BusinessException.class, () ->
                complexProductMasterService.publishBom(productC,
                        bomSave("TEMPORAL-C-A", productA, unit, 0,
                                LocalDate.of(2026, 7, 23), null)));
        assertTrue(temporal.getMessage().contains("2026-07-23"));
        assertTrue(temporal.getMessage().contains("传递循环"));
        assertEquals(0, count(
                "select count(*) from product_bom where parent_product_id=?", productC));
    }

    @Test
    @Transactional
    void supplierOfferHistoryUsesRevisionSnapshotsAndNeverRevivesAnOlderVersion() {
        Long productId = createInsulationProduct("OFFER", 21);
        Long supplierId = createSupplier();
        String unit = seriesBaseUnit("保温管材");
        ProductMasterDataDtos.SupplierOffer first =
                complexProductMasterService.createSupplierOffer(productId,
                        supplierOfferSave(supplierId, "OLD-" + runKey, "M1",
                                unit, LocalDate.of(2026, 1, 1), null, 0));
        ProductMasterDataDtos.SupplierOffer second =
                complexProductMasterService.updateSupplierOffer(productId, first.id(),
                        supplierOfferSave(supplierId, "NEW-" + runKey, "M2",
                                unit, LocalDate.of(2026, 7, 1),
                                LocalDate.of(2026, 9, 30), 1));
        jdbc.update("update partner set name=? where id=?",
                "后续更名供应商-" + runKey, supplierId);

        List<ProductMasterDataDtos.SupplierOffer> historical =
                complexProductMasterService.supplierOffers(
                        productId, supplierId, "ENABLED", LocalDate.of(2026, 5, 1));
        assertEquals(1, historical.size());
        assertEquals("OLD-" + runKey, historical.get(0).supplierProductCode());
        assertEquals("M1", historical.get(0).supplierModel());
        assertEquals("PMB供应商-" + runKey, historical.get(0).supplierName());
        assertEquals(first.currentRevisionId(), historical.get(0).currentRevisionId());
        assertEquals(first.revisionNo(), historical.get(0).revisionNo());

        List<ProductMasterDataDtos.SupplierOffer> temporary =
                complexProductMasterService.supplierOffers(
                        productId, supplierId, "ENABLED", LocalDate.of(2026, 8, 1));
        assertEquals(1, temporary.size());
        assertEquals(second.currentRevisionId(), temporary.get(0).currentRevisionId());
        assertEquals("NEW-" + runKey, temporary.get(0).supplierProductCode());

        assertTrue(complexProductMasterService.supplierOffers(
                productId, supplierId, "ENABLED", LocalDate.of(2026, 10, 1)).isEmpty(),
                "新版到期后不得回退使用已被替代的旧版供货条款");
        List<ProductMasterDataDtos.SupplierOfferRevision> offerHistory =
                complexProductMasterService.supplierOfferRevisions(productId, first.id());
        assertEquals(List.of("NEW-" + runKey, "OLD-" + runKey),
                offerHistory.stream()
                        .map(ProductMasterDataDtos.SupplierOfferRevision::supplierProductCodeSnapshot)
                        .toList());
        assertEquals("PMB供应商-" + runKey, offerHistory.get(0).supplierNameSnapshot());
        assertEquals("ENABLED", offerHistory.get(0).headStatusSnapshot());
        assertTrue(offerHistory.get(0).headSnapshotAvailable());
        assertTrue(offerHistory.get(0).currentRevision());
        assertFalse(offerHistory.get(1).currentRevision());
    }

    @Test
    @Transactional
    void supplierOfferCannotReferenceARevisionWhosePackagingHeadIsDisabled() {
        Long productId = createInsulationProduct("DISABLED-PACKAGING-OFFER", 23);
        Long supplierId = createSupplier();
        String unit = seriesBaseUnit("保温管材");
        String variantCode = "PMB-DISABLED-PKG-" + runKey;
        ProductMasterDataDtos.PackagingVariant enabled =
                complexProductMasterService.createPackagingVariant(
                        productId, new ProductMasterDataDtos.PackagingVariantSave(
                                variantCode, "待停用包装", unit, "FIXED",
                                BigDecimal.ONE, null, "1件/包",
                                null, null, null, null, null, null,
                                "", null, null, null,
                                "ENABLED", "建立包装", 0));
        complexProductMasterService.updatePackagingVariant(
                productId, enabled.id(), new ProductMasterDataDtos.PackagingVariantSave(
                        variantCode, "已停用包装", unit, "FIXED",
                        BigDecimal.ONE, null, "1件/包",
                        null, null, null, null, null, null,
                        "", null, null, null,
                        "DISABLED", "停用包装", 1));

        BusinessException disabled = assertThrows(BusinessException.class, () ->
                complexProductMasterService.createSupplierOffer(
                        productId, supplierOfferSave(
                                supplierId, "DISABLED-" + runKey, "M-DISABLED", unit,
                                LocalDate.of(2026, 7, 23), null, 0,
                                enabled.currentRevisionId())));

        assertTrue(disabled.getMessage().contains("包装方案已停用"));
        assertEquals(0, count("""
                select count(*) from supplier_product_offer
                where product_id=? and supplier_product_code=?
                """, productId, "DISABLED-" + runKey));
    }

    @Test
    @Transactional
    void disablingPackagingHeadRemovesCurrentOfferSelectionButKeepsHistory() {
        Long productId = createInsulationProduct("PACKAGE-SELECT", 57);
        Long supplierId = createSupplier("PACKAGE-SELECT");
        String unit = seriesBaseUnit("保温管材");
        String variantCode = "PMB-PKG-SELECT-" + runKey;
        ProductMasterDataDtos.PackagingVariant variant =
                complexProductMasterService.createPackagingVariant(
                        productId, new ProductMasterDataDtos.PackagingVariantSave(
                                variantCode, "供货包装", "箱", "FIXED",
                                new BigDecimal("12"), null, "12支/箱",
                                null, null, null, null, null, null,
                                "", supplierId, null, null,
                                "ENABLED", "建立包装", 0));
        ProductMasterDataDtos.SupplierOffer offer =
                complexProductMasterService.createSupplierOffer(
                        productId, supplierOfferSave(
                                supplierId, "PKG-" + runKey, "PKG-MODEL", unit,
                                LocalDate.of(2026, 1, 1), null, 0,
                                variant.currentRevisionId()));
        assertNotNull(copperService.productPurchaseOptions(
                productId, supplierId, unit).supplierOffer());

        complexProductMasterService.updatePackagingVariant(
                productId, variant.id(), new ProductMasterDataDtos.PackagingVariantSave(
                        variantCode, "停用供货包装", "箱", "FIXED",
                        new BigDecimal("12"), null, "12支/箱",
                        null, null, null, null, null, null,
                        "", supplierId, null, null,
                        "DISABLED", "停用包装", variant.revisionNo()));

        assertNull(copperService.productPurchaseOptions(
                productId, supplierId, unit).supplierOffer());
        assertEquals(1, complexProductMasterService.supplierOfferRevisions(
                productId, offer.id()).size());
    }

    @Test
    @Transactional
    void baseUnitChangeIsBlockedByPackagingOfferAndBomDependencies() {
        String originalUnit = seriesBaseUnit("保温管材");

        Long packagingProduct = createInsulationProduct("BASE-PACKAGING", 58);
        complexProductMasterService.createPackagingVariant(
                packagingProduct, new ProductMasterDataDtos.PackagingVariantSave(
                        "PMB-BASE-PKG-" + runKey, "基础单位包装依赖",
                        "箱", "FIXED", new BigDecimal("12"), null, "",
                        null, null, null, null, null, null,
                        "", null, null, null, "ENABLED", "包装依赖", 0));
        assertBaseUnitChangeBlocked(packagingProduct, "包装");

        Long offerProduct = createInsulationProduct("BASE-OFFER", 59);
        Long supplierId = createSupplier("BASE-OFFER");
        complexProductMasterService.createSupplierOffer(
                offerProduct, supplierOfferSave(
                        supplierId, "BASE-OFFER-" + runKey, "BASE-OFFER",
                        originalUnit, LocalDate.of(2026, 1, 1), null, 0));
        assertBaseUnitChangeBlocked(offerProduct, "供应商供货");

        Long bomParent = createInsulationProduct("BASE-BOM-PARENT", 60);
        Long bomComponent = createInsulationProduct("BASE-BOM-COMPONENT", 61);
        complexProductMasterService.publishBom(
                bomParent, bomSave("BASE-UNIT", bomComponent, originalUnit, 0));
        assertBaseUnitChangeBlocked(bomParent, "BOM父件");
        assertBaseUnitChangeBlocked(bomComponent, "BOM子件");
    }

    @Test
    @Transactional
    void supplierOfferAndSourceReadsEnforcePartnerDataScope() {
        Long productId = createInsulationProduct("PARTNER-SCOPE", 24);
        Long allowedSupplierId = createSupplier();
        Long blockedSupplierId = createSupplier("BLOCKED");
        String unit = seriesBaseUnit("保温管材");
        ProductMasterDataDtos.SupplierOffer allowedOffer =
                complexProductMasterService.createSupplierOffer(
                        productId, supplierOfferSave(
                                allowedSupplierId, "ALLOWED-" + runKey, "M-ALLOWED", unit,
                                LocalDate.of(2026, 1, 1), null, 0));
        ProductMasterDataDtos.SupplierOffer blockedOffer =
                complexProductMasterService.createSupplierOffer(
                        productId, supplierOfferSave(
                                blockedSupplierId, "BLOCKED-" + runKey, "M-BLOCKED", unit,
                                LocalDate.of(2026, 1, 1), null, 0));
        ProductMasterDataDtos.SourceDocument source =
                productMasterDataService.createSourceDocument(
                        new ProductMasterDataDtos.SourceDocumentSave(
                                "PMB-SCOPE-SRC-" + runKey, "供应商范围来源", "MANUAL",
                                "", "", "", "", 0L, null, "", null, "{}",
                                "ENABLED", "建立范围测试来源", 0));
        ProductMasterDataDtos.SourceLink allowedLink =
                productMasterDataService.createSourceLink(
                        new ProductMasterDataDtos.SourceLinkSave(
                                source.currentRevisionId(), "SUPPLIER_OFFER",
                                allowedOffer.id(), 0, "允许报价", "", "", null));
        ProductMasterDataDtos.SourceLink blockedLink =
                productMasterDataService.createSourceLink(
                        new ProductMasterDataDtos.SourceLinkSave(
                                source.currentRevisionId(), "SUPPLIER_OFFER",
                                blockedOffer.id(), 0, "越权报价", "", "", null));

        restrictPartnerScopeTo(allowedSupplierId);

        List<ProductMasterDataDtos.SupplierOffer> scopedOffers =
                complexProductMasterService.supplierOffers(
                        productId, null, "ENABLED", LocalDate.of(2026, 7, 23));
        assertEquals(List.of(allowedSupplierId),
                scopedOffers.stream().map(ProductMasterDataDtos.SupplierOffer::supplierId)
                        .toList());
        assertEquals(1,
                complexProductMasterService.supplierOfferRevisions(
                        productId, allowedOffer.id()).size());
        assertThrows(ForbiddenException.class, () ->
                complexProductMasterService.supplierOffers(
                        productId, blockedSupplierId, "ENABLED",
                        LocalDate.of(2026, 7, 23)));
        assertThrows(ForbiddenException.class, () ->
                complexProductMasterService.supplierOfferRevisions(
                        productId, blockedOffer.id()));

        assertEquals(1,
                productMasterDataService.sourceLinks(
                        "SUPPLIER_OFFER", allowedOffer.id()).size());
        assertThrows(ForbiddenException.class, () ->
                productMasterDataService.sourceLinks(
                        "SUPPLIER_OFFER", blockedOffer.id()));
        productMasterDataService.reviewSourceLink(
                allowedLink.id(), new ProductMasterDataDtos.SourceLinkReview(
                        "APPROVED", "允许范围内复核"));
        assertEquals(1,
                productMasterDataService.sourceLinkReviewHistory(allowedLink.id()).size());
        assertThrows(ForbiddenException.class, () ->
                productMasterDataService.sourceLinkReviewHistory(blockedLink.id()));
        assertThrows(ForbiddenException.class, () ->
                productMasterDataService.reviewSourceLink(
                        blockedLink.id(), new ProductMasterDataDtos.SourceLinkReview(
                                "APPROVED", "不得执行越权复核")));
    }

    @Test
    @Transactional
    void supplierOfferTermsFreezeIntoPurchaseAndSurviveMysqlJsonRoundTrip() {
        Long productId = createInsulationProduct("PURCHASE-OFFER", 22);
        Long supplierId = createSupplier();
        Long warehouseId = createWarehouse();
        jdbc.update("""
                update partner_supplier_profile
                set tax_included=1,tax_rate=13.00
                where partner_id=?
                """, supplierId);
        String unit = seriesBaseUnit("保温管材");
        LocalDate businessDate = LocalDate.of(2026, 7, 23);
        ProductMasterDataDtos.SupplierOffer first =
                complexProductMasterService.createSupplierOffer(productId,
                        new ProductMasterDataDtos.SupplierOfferSave(
                                supplierId, "PO-OLD-" + runKey, "PO-M1",
                                unit, unit, new BigDecimal("88.12345678"),
                                "CNY", true, new BigDecimal("13.000000"),
                                new BigDecimal("5.000000"),
                                new BigDecimal("2.000000"), 7,
                                businessDate, null, null, null, null,
                                "{\"delivery\":\"test\"}", "ENABLED",
                                "采购供货快照行为验收", 0));
        ProductPurchaseOptionsDto.SupplierOfferContext context =
                Objects.requireNonNull(copperService.productPurchaseOptions(
                        productId, supplierId, unit,
                        new BigDecimal("6.000000"), businessDate).supplierOffer());

        BusinessException belowMinimum = assertThrows(BusinessException.class, () ->
                copperService.createPurchaseOrder(purchaseRequest(
                        supplierId, warehouseId, businessDate,
                        purchaseItem(productId, null, new BigDecimal("4.000000"),
                                unit, context, null))));
        assertTrue(belowMinimum.getMessage().contains("最小起订量"));

        BusinessException wrongMultiple = assertThrows(BusinessException.class, () ->
                copperService.createPurchaseOrder(purchaseRequest(
                        supplierId, warehouseId, businessDate,
                        purchaseItem(productId, null, new BigDecimal("5.000000"),
                                unit, context, null))));
        assertTrue(wrongMultiple.getMessage().contains("下单倍数"));

        BigDecimal accepted = new BigDecimal("6.000000");
        PackagingDtos.Plan plan = packagingService.plan(productId,
                new PackagingDtos.PlanRequest(
                        accepted, unit, null, unit, "PURCHASE",
                        null, null, context.supplierOfferRevisionId(),
                        context.packagingVariantRevisionId()));
        Long purchaseId = copperService.createPurchaseOrder(purchaseRequest(
                supplierId, warehouseId, businessDate,
                purchaseItem(productId, null, accepted, unit, context, plan)));
        Long itemId = id(
                "select id from purchase_order_item where purchase_order_id=?", purchaseId);
        assertEquals(context.supplierOfferRevisionId(), id("""
                select cast(json_unquote(json_extract(price_snapshot_json,
                       '$.supplierOfferRevisionId')) as unsigned)
                from purchase_order_item where id=?
                """, itemId));

        ProductMasterDataDtos.SupplierOffer second =
                complexProductMasterService.updateSupplierOffer(productId, first.id(),
                        supplierOfferSave(supplierId, "PO-NEW-" + runKey, "PO-M2",
                                unit, businessDate, null, 1));
        assertNotEquals(first.currentRevisionId(), second.currentRevisionId());

        assertEquals(purchaseId, copperService.updatePurchaseOrder(
                purchaseId, purchaseRequest(
                        supplierId, warehouseId, businessDate,
                        purchaseItem(productId, itemId, accepted, unit, context, plan))));
        assertEquals(first.currentRevisionId(), id("""
                select cast(json_unquote(json_extract(price_snapshot_json,
                       '$.supplierOfferRevisionId')) as unsigned)
                from purchase_order_item where purchase_order_id=?
                """, purchaseId));

        ProductPurchaseOptionsDto.SupplierOfferContext refreshed =
                Objects.requireNonNull(copperService.productPurchaseOptions(
                        productId, supplierId, unit, accepted, businessDate).supplierOffer());
        assertEquals(second.currentRevisionId(), refreshed.supplierOfferRevisionId());
        BusinessException staleNewOrder = assertThrows(BusinessException.class, () ->
                copperService.createPurchaseOrder(purchaseRequest(
                        supplierId, warehouseId, businessDate,
                        purchaseItem(productId, null, accepted, unit, context, plan))));
        assertTrue(staleNewOrder.getMessage().contains("已更新"));
    }

    @Test
    @Transactional
    void expiredNewerTechnicalSpecDoesNotReviveAnOlderQualityTarget() {
        Long productId = createInsulationProduct("QUALITY-AS-OF", 30);
        Long warehouseId = createWarehouse();
        Long batchId = createInventoryBatch(productId, warehouseId);
        ProductMasterDataDtos.TechnicalCharacteristicSave v1Characteristic =
                new ProductMasterDataDtos.TechnicalCharacteristicSave(
                        "DIM", "关键尺寸", "NUMBER", "",
                        new BigDecimal("14.9000000000"),
                        new BigDecimal("15.0000000000"),
                        new BigDecimal("15.1000000000"), "mm",
                        "卡尺", "常温", true, 10);
        ProductMasterDataDtos.TechnicalSpec v1 =
                complexProductMasterService.publishTechnicalSpec(
                        productId, new ProductMasterDataDtos.TechnicalSpecSave(
                                "STD-V1", LocalDate.of(2026, 1, 1), null,
                                null, null, "质量目标第一版",
                                List.of(v1Characteristic), 0));
        complexProductMasterService.publishTechnicalSpec(
                productId, new ProductMasterDataDtos.TechnicalSpecSave(
                        "STD-V2", LocalDate.of(2026, 6, 1),
                        LocalDate.of(2026, 6, 30),
                        null, null, "质量目标限期第二版",
                        List.of(v1Characteristic), 1));

        ProductMasterDataDtos.QualityResultSave oldTargetResult =
                new ProductMasterDataDtos.QualityResultSave(
                        v1.characteristics().get(0).id(), "DIM", "",
                        new BigDecimal("15.0000000000"), "mm",
                        "PASS", "卡尺", LocalDateTime.of(2026, 7, 23, 10, 0));
        ProductMasterDataDtos.QualityCertificateSave certificate =
                new ProductMasterDataDtos.QualityCertificateSave(
                        "PMB-QC-ASOF-" + runKey, "MATERIAL", null,
                        "行为验收生产厂", "LOT-" + runKey, "HEAT-" + runKey,
                        "COIL-" + runKey, LocalDate.of(2026, 7, 23),
                        null, null, null, "ENABLED",
                        List.of(oldTargetResult), 0);
        BusinessException expired = assertThrows(BusinessException.class, () ->
                complexProductMasterService.saveQualityCertificate(
                        batchId, null, certificate));
        assertTrue(expired.getMessage().contains("没有适用的已发布技术规格"));
    }

    @Test
    @Transactional
    void qualityCertificateLifecycleKeepsOneUnambiguousVoidStateAndAuditTrail() {
        Long productId = createInsulationProduct("QUALITY", 31);
        Long warehouseId = createWarehouse();
        Long batchId = createInventoryBatch(productId, warehouseId);

        BusinessException manualDisable = assertThrows(BusinessException.class, () ->
                complexProductMasterService.saveQualityCertificate(
                        batchId, null, qualityCertificateSave("DISABLED")));
        assertTrue(manualDisable.getMessage().contains("不能手工停用"));

        ProductMasterDataDtos.QualityCertificate pending =
                complexProductMasterService.saveQualityCertificate(
                        batchId, null, qualityCertificateSave("ENABLED"));
        assertEquals("PENDING", pending.verificationStatus());
        assertEquals("ENABLED", pending.status());

        ProductMasterDataDtos.QualityCertificate verified =
                complexProductMasterService.reviewQualityCertificate(
                        batchId, pending.id(),
                        new ProductMasterDataDtos.QualityCertificateReviewSave(
                                "VERIFIED", "复核数据与来源一致", pending.lockVersion()));
        assertEquals("VERIFIED", verified.verificationStatus());
        assertEquals("ENABLED", verified.status());

        BusinessException stale = assertThrows(BusinessException.class, () ->
                complexProductMasterService.reviewQualityCertificate(
                        batchId, pending.id(),
                        new ProductMasterDataDtos.QualityCertificateReviewSave(
                                "VOID", "使用旧版本尝试作废", pending.lockVersion())));
        assertTrue(stale.getMessage().contains("已被其他人更新"));

        ProductMasterDataDtos.QualityCertificate voided =
                complexProductMasterService.reviewQualityCertificate(
                        batchId, pending.id(),
                        new ProductMasterDataDtos.QualityCertificateReviewSave(
                                "VOID", "发现来源错误，保留历史后作废", verified.lockVersion()));
        assertEquals("VOID", voided.verificationStatus());
        assertEquals("DISABLED", voided.status());
        assertEquals(2, count("""
                select count(*) from inventory_batch_quality_certificate_review
                where certificate_id=?
                """, pending.id()));
        List<ProductMasterDataDtos.QualityCertificateReviewHistory> reviewHistory =
                complexProductMasterService.qualityCertificateReviewHistory(
                        batchId, pending.id());
        assertEquals(List.of("VOID", "VERIFIED"),
                reviewHistory.stream()
                        .map(ProductMasterDataDtos.QualityCertificateReviewHistory::reviewStatus)
                        .toList());
        assertEquals("VERIFIED", reviewHistory.get(0).previousStatus());
        assertEquals("商品行为验收", reviewHistory.get(0).reviewedByName());

        BusinessException disabled = assertThrows(BusinessException.class, () ->
                complexProductMasterService.reviewQualityCertificate(
                        batchId, pending.id(),
                        new ProductMasterDataDtos.QualityCertificateReviewSave(
                                "VERIFIED", "不得恢复已作废记录", voided.lockVersion())));
        assertTrue(disabled.getMessage().contains("不能再复核"));
    }

    @Test
    @Transactional
    void qualityCertificatePartnerIdentityIsRedactedAndWritesFailClosedByScope() {
        Long productId = createInsulationProduct("QUALITY-PARTNER-SCOPE", 56);
        Long warehouseId = createWarehouse();
        Long batchId = createInventoryBatch(productId, warehouseId);
        Long allowedSupplierId = createSupplier("QUALITY-ALLOWED");
        Long blockedSupplierId = createSupplier("QUALITY-BLOCKED");
        ProductMasterDataDtos.QualityCertificate created =
                complexProductMasterService.saveQualityCertificate(
                        batchId, null, new ProductMasterDataDtos.QualityCertificateSave(
                                "PMB-QC-SCOPE-" + runKey, "MATERIAL",
                                blockedSupplierId, "受限生产厂-" + runKey,
                                "LOT-" + runKey, "HEAT-" + runKey, "COIL-" + runKey,
                                LocalDate.of(2026, 7, 23), null, null, null,
                                "ENABLED", List.of(), 0));

        restrictPartnerScopeTo(allowedSupplierId);

        ProductMasterDataDtos.QualityCertificate redacted =
                complexProductMasterService.qualityCertificates(batchId).stream()
                        .filter(item -> Objects.equals(item.id(), created.id()))
                        .findFirst().orElseThrow();
        assertNull(redacted.supplierId());
        assertNull(redacted.manufacturerName());
        assertThrows(ForbiddenException.class, () ->
                complexProductMasterService.reviewQualityCertificate(
                        batchId, created.id(),
                        new ProductMasterDataDtos.QualityCertificateReviewSave(
                                "VERIFIED", "越权复核", created.lockVersion())));
        assertThrows(ForbiddenException.class, () ->
                complexProductMasterService.qualityCertificateReviewHistory(
                        batchId, created.id()));
        assertThrows(ForbiddenException.class, () ->
                complexProductMasterService.saveQualityCertificate(
                        batchId, created.id(),
                        new ProductMasterDataDtos.QualityCertificateSave(
                                created.certificateNo(), created.certificateType(),
                                allowedSupplierId, "尝试改绑到允许供应商",
                                created.manufacturerBatchNo(), created.heatNo(), created.coilNo(),
                                created.issueDate(), created.sourceDocumentId(),
                                created.sourceDocumentRevisionId(), null,
                                "ENABLED", List.of(), created.lockVersion())));
    }

    @Test
    @Transactional
    void sourceDocumentPublishesImmutableRevisionsAndRejectsAStaleWriter() {
        String code = "PMB-SRC-" + runKey;
        ProductMasterDataDtos.SourceDocument first =
                productMasterDataService.createSourceDocument(new ProductMasterDataDtos.SourceDocumentSave(
                        code, "来源修订一", "MANUAL", "https://example.invalid/v1",
                        "source-v1.pdf", "a".repeat(64), "application/pdf", 101L,
                        LocalDate.of(2026, 1, 1), "v1", LocalDateTime.of(2026, 1, 2, 3, 4),
                        "{\"revision\":1}", "ENABLED", "初始发布", 0));
        ProductMasterDataDtos.SourceDocument second =
                productMasterDataService.updateSourceDocument(first.id(),
                        new ProductMasterDataDtos.SourceDocumentSave(
                                code, "来源修订二", "TECHNICAL_STANDARD",
                                "https://example.invalid/v2", "source-v2.pdf",
                                "b".repeat(64), "application/pdf", 202L,
                                LocalDate.of(2026, 2, 1), "v2",
                                LocalDateTime.of(2026, 2, 2, 3, 4),
                                "{\"revision\":2}", "ENABLED", "资料换版", 1));

        assertEquals(2, second.currentRevisionNo());
        assertNotEquals(first.currentRevisionId(), second.currentRevisionId());
        List<Map<String, Object>> revisions = jdbc.queryForList("""
                select revision_no,title_snapshot,source_type_snapshot,source_url,
                       file_name,file_sha256,internal_version,
                       cast(content_snapshot_json as char) content_snapshot_json
                from product_source_document_revision
                where document_id=? order by revision_no
                """, first.id());
        assertEquals(2, revisions.size());
        assertEquals("来源修订一", revisions.get(0).get("title_snapshot"));
        assertEquals("MANUAL", revisions.get(0).get("source_type_snapshot"));
        assertEquals("https://example.invalid/v1", revisions.get(0).get("source_url"));
        assertEquals("source-v1.pdf", revisions.get(0).get("file_name"));
        assertEquals("a".repeat(64), revisions.get(0).get("file_sha256"));
        assertEquals("v1", revisions.get(0).get("internal_version"));
        assertTrue(String.valueOf(revisions.get(0).get("content_snapshot_json"))
                .contains("\"revision\": 1")
                || String.valueOf(revisions.get(0).get("content_snapshot_json"))
                .contains("\"revision\":1"));
        assertEquals("来源修订二", revisions.get(1).get("title_snapshot"));
        assertEquals("TECHNICAL_STANDARD", revisions.get(1).get("source_type_snapshot"));

        List<ProductMasterDataDtos.SourceDocumentRevision> history =
                productMasterDataService.sourceDocumentRevisions(first.id());
        assertEquals(List.of(2, 1),
                history.stream().map(ProductMasterDataDtos.SourceDocumentRevision::revisionNo)
                        .toList());
        assertEquals(second.currentRevisionId(), history.get(0).id());
        assertEquals("来源修订二", history.get(0).titleSnapshot());
        assertEquals("TECHNICAL_STANDARD", history.get(0).sourceTypeSnapshot());
        assertEquals(code, history.get(0).documentCode());
        assertEquals("ENABLED", history.get(0).headStatusSnapshot());
        assertTrue(history.get(0).headSnapshotAvailable());
        assertTrue(history.get(0).currentRevision());
        assertEquals(first.currentRevisionId(), history.get(1).id());
        assertEquals("来源修订一", history.get(1).titleSnapshot());
        assertEquals("MANUAL", history.get(1).sourceTypeSnapshot());
        assertFalse(history.get(1).currentRevision());
        assertEquals("商品行为验收", history.get(1).publishedByName());

        Long sourceTargetProductId = createInsulationProduct("SOURCE-LINK", 42);
        ProductMasterDataDtos.SourceLink link = productMasterDataService.createSourceLink(
                new ProductMasterDataDtos.SourceLinkSave(
                        second.currentRevisionId(), "PRODUCT", sourceTargetProductId,
                        2, "表2第3行", "原始规格", "规范规格",
                        new BigDecimal("0.980000")));
        productMasterDataService.reviewSourceLink(link.id(),
                new ProductMasterDataDtos.SourceLinkReview(
                        "APPROVED", "来源内容与商品一致"));
        productMasterDataService.reviewSourceLink(link.id(),
                new ProductMasterDataDtos.SourceLinkReview(
                        "REJECTED", "后续发现版本引用错误"));
        List<ProductMasterDataDtos.SourceLinkReviewHistory> sourceReviewHistory =
                productMasterDataService.sourceLinkReviewHistory(link.id());
        assertEquals(List.of("REJECTED", "APPROVED"),
                sourceReviewHistory.stream()
                        .map(ProductMasterDataDtos.SourceLinkReviewHistory::reviewStatus)
                        .toList());
        assertEquals("APPROVED", sourceReviewHistory.get(0).previousStatus());
        assertEquals("商品行为验收", sourceReviewHistory.get(0).reviewedByName());

        BusinessException stale = assertThrows(BusinessException.class, () ->
                productMasterDataService.updateSourceDocument(first.id(),
                        new ProductMasterDataDtos.SourceDocumentSave(
                                code, "过期写入", "MANUAL", "", "", "",
                                "", 0L, null, "", null, "{}", "ENABLED",
                                "使用旧版本号更新", 1)));
        assertTrue(stale.getMessage().contains("已被其他人更新"));
        assertEquals(2, intValue("""
                select count(*) from product_source_document_revision where document_id=?
                """, first.id()));
    }

    @Test
    @Transactional
    void packagingVariantRevisionHistoryReturnsImmutableSnapshotsNewestFirst() {
        Long productId = createInsulationProduct("PACKAGING-HISTORY", 41);
        String unit = seriesBaseUnit("保温管材");
        String code = "PMB-PKG-" + runKey;
        ProductMasterDataDtos.PackagingVariant first =
                complexProductMasterService.createPackagingVariant(
                        productId, new ProductMasterDataDtos.PackagingVariantSave(
                                code, "验收包装第一版", unit, "FIXED",
                                new BigDecimal("12.000000"), null, "12件/箱",
                                new BigDecimal("10.000000"), new BigDecimal("10.500000"),
                                new BigDecimal("0.500000"), new BigDecimal("300.000"),
                                new BigDecimal("200.000"), new BigDecimal("100.000"),
                                "6900000000001", null, null, null,
                                "ENABLED", "初始包装发布", 0));
        ProductMasterDataDtos.PackagingVariant second =
                complexProductMasterService.updatePackagingVariant(
                        productId, first.id(), new ProductMasterDataDtos.PackagingVariantSave(
                                code, "验收包装第二版", unit, "FIXED",
                                new BigDecimal("24.000000"), null, "24件/箱",
                                new BigDecimal("20.000000"), new BigDecimal("21.000000"),
                                new BigDecimal("1.000000"), new BigDecimal("400.000"),
                                new BigDecimal("300.000"), new BigDecimal("200.000"),
                                "6900000000002", null, null, null,
                                "ENABLED", "调整装箱数量", 1));

        List<ProductMasterDataDtos.PackagingVariantRevision> history =
                complexProductMasterService.packagingVariantRevisions(productId, first.id());

        assertEquals(List.of(2, 1),
                history.stream().map(ProductMasterDataDtos.PackagingVariantRevision::revisionNo)
                        .toList());
        assertEquals(second.currentRevisionId(), history.get(0).id());
        assertEquals(new BigDecimal("24.000000"), history.get(0).fixedBaseQuantity());
        assertEquals("24件/箱", history.get(0).innerSpec());
        assertEquals("调整装箱数量", history.get(0).changeReason());
        assertEquals(code, history.get(0).variantCodeSnapshot());
        assertEquals("验收包装第二版", history.get(0).nameSnapshot());
        assertEquals("ENABLED", history.get(0).headStatusSnapshot());
        assertTrue(history.get(0).headSnapshotAvailable());
        assertEquals("PUBLISHED", history.get(0).revisionStatus());
        assertTrue(history.get(0).currentRevision());
        assertEquals(first.currentRevisionId(), history.get(1).id());
        assertEquals(new BigDecimal("12.000000"), history.get(1).fixedBaseQuantity());
        assertEquals("12件/箱", history.get(1).innerSpec());
        assertEquals("初始包装发布", history.get(1).changeReason());
        assertFalse(history.get(1).currentRevision());
        assertNotEquals(history.get(0).contentHash(), history.get(1).contentHash());
        assertEquals("商品行为验收", history.get(1).publishedByName());
    }

    private static Stream<Arguments> sixDecimalQuantityCases() {
        BigDecimal accepted = new BigDecimal("0.000001");
        BigDecimal rejected = new BigDecimal("0.0000001");
        return Stream.of(
                Arguments.of("包装计划数量",
                        new PackagingDtos.PlanRequest(accepted, "kg", null, "kg", "PURCHASE_ORDER"),
                        new PackagingDtos.PlanRequest(rejected, "kg", null, "kg", "PURCHASE_ORDER"),
                        "quantity"),
                Arguments.of("采购订单数量",
                        new PurchaseCreateRequest.Item(null, 1L, accepted, "kg",
                                null, null, null, "kg", "", BigDecimal.ONE),
                        new PurchaseCreateRequest.Item(null, 1L, rejected, "kg",
                                null, null, null, "kg", "", BigDecimal.ONE),
                        "quantity"),
                Arguments.of("采购入库数量",
                        new InboundCreateRequest.Item(
                                1L, null, "BATCH-A", "", accepted, "kg",
                                null, null, null, null, "kg", "", "",
                                BigDecimal.ONE, List.of(), null),
                        new InboundCreateRequest.Item(
                                1L, null, "BATCH-B", "", rejected, "kg",
                                null, null, null, null, "kg", "", "",
                                BigDecimal.ONE, List.of(), null),
                        "quantity"),
                Arguments.of("BOM组件数量",
                        new ProductMasterDataDtos.BomItemSave(
                                1L, accepted, "kg", BigDecimal.ZERO, "MANUAL", 10),
                        new ProductMasterDataDtos.BomItemSave(
                                1L, rejected, "kg", BigDecimal.ZERO, "MANUAL", 10),
                        "quantity")
        );
    }

    private Long createInsulationProduct(String suffix, int dimension) {
        String sku = "PMB-BOM-" + suffix + "-" + runKey;
        return copperService.createProduct(productRequestForSeries(
                "保温管材", sku, "BOM行为验收-" + suffix, dimension,
                seriesBaseUnit("保温管材"), "PUBLISHED"));
    }

    private SimpleCreateRequests.ProductCreate productRequestForSeries(
            String seriesName, String sku, String productName, int dimension,
            String unit, String identityStatus) {
        Long seriesId = id("select id from product_series where name=?", seriesName);
        List<SimpleCreateRequests.ProductFieldValueCreate> fields = new ArrayList<>();
        List<Map<String, Object>> definitions = jdbc.queryForList("""
                select sf.id,sf.field_name,cf.field_type,
                       json_unquote(json_extract(
                         coalesce(nullif(sf.option_json,''),cf.option_json),'$[0]'
                       )) first_option
                from product_series_field sf
                join product_custom_field cf on cf.id=sf.field_id
                where sf.product_series_id=? and sf.required=1 and sf.status='ENABLED'
                order by sf.sort_no,sf.id
                """, seriesId);
        for (Map<String, Object> definition : definitions) {
                String fieldName = String.valueOf(definition.get("field_name"));
                String fieldType = String.valueOf(definition.get("field_type"));
                Object value;
                if ("NUMBER".equals(fieldType)) {
                    value = new BigDecimal(dimension + "." + String.format("%06d",
                            Math.floorMod(fieldName.hashCode(), 1_000_000)));
                } else if ("SELECT".equals(fieldType)) {
                    String firstOption = Objects.toString(
                            definition.get("first_option"), "");
                    assertFalse(firstOption.isBlank(),
                            fieldName + " 必须至少配置一个可选值");
                    value = firstOption;
                } else {
                    value = "验收资料-" + seriesName + "-" + runKey;
                }
                fields.add(new SimpleCreateRequests.ProductFieldValueCreate(
                        ((Number) definition.get("id")).longValue(), value));
        }
        assertFalse(fields.isEmpty());
        return new SimpleCreateRequests.ProductCreate(
                null, seriesId, null, sku, productName,
                "", "", "", "", "{}", "", "", unit,
                BigDecimal.ZERO, "ENABLED", fields, identityStatus);
    }

    private ProductMasterDataDtos.BomSave bomSave(
            String suffix, Long componentProductId, String unit, int expectedRevision) {
        return bomSave(suffix, componentProductId, unit, expectedRevision,
                LocalDate.of(2026, 7, 23), null);
    }

    private ProductMasterDataDtos.BomSave bomSave(
            String suffix, Long componentProductId, String unit, int expectedRevision,
            LocalDate effectiveFrom, LocalDate effectiveTo) {
        return new ProductMasterDataDtos.BomSave(
                "PMB-" + suffix + "-" + runKey,
                "BOM行为验收-" + suffix, BigDecimal.ONE, unit, BigDecimal.ZERO,
                effectiveFrom, effectiveTo, "ENABLED", "内部行为验收",
                List.of(new ProductMasterDataDtos.BomItemSave(
                        componentProductId, BigDecimal.ONE, unit,
                        BigDecimal.ZERO, "MANUAL", 10)),
                expectedRevision);
    }

    private ProductMasterDataDtos.SupplierOfferSave supplierOfferSave(
            Long supplierId, String supplierCode, String supplierModel, String unit,
            LocalDate effectiveFrom, LocalDate effectiveTo, int expectedRevision) {
        return supplierOfferSave(supplierId, supplierCode, supplierModel, unit,
                effectiveFrom, effectiveTo, expectedRevision, null);
    }

    private ProductMasterDataDtos.SupplierOfferSave supplierOfferSave(
            Long supplierId, String supplierCode, String supplierModel, String unit,
            LocalDate effectiveFrom, LocalDate effectiveTo, int expectedRevision,
            Long packagingVariantRevisionId) {
        return new ProductMasterDataDtos.SupplierOfferSave(
                supplierId, supplierCode, supplierModel, unit, unit,
                new BigDecimal("88.12345678"), "CNY", true,
                new BigDecimal("13.000000"), BigDecimal.ONE, BigDecimal.ONE,
                7, effectiveFrom, effectiveTo, packagingVariantRevisionId, null, null,
                "{\"delivery\":\"test\"}", "ENABLED", "内部行为验收",
                expectedRevision);
    }

    private PurchaseCreateRequest purchaseRequest(
            Long supplierId, Long warehouseId, LocalDate businessDate,
            PurchaseCreateRequest.Item item) {
        return new PurchaseCreateRequest(
                supplierId, businessDate, businessDate.plusDays(7), warehouseId,
                "OTHER", "INTERNAL_REHEARSAL_ONLY-" + runKey,
                BigDecimal.ZERO, BigDecimal.ZERO,
                "供应商供货快照行为验收", List.of(item));
    }

    private PurchaseCreateRequest.Item purchaseItem(
            Long productId, Long itemId, BigDecimal quantity, String unit,
            ProductPurchaseOptionsDto.SupplierOfferContext offer,
            PackagingDtos.Plan plan) {
        return new PurchaseCreateRequest.Item(
                itemId, productId, quantity, unit, null,
                plan == null ? null : plan.effectiveRevisionId(),
                plan == null ? null : plan.snapshotHash(),
                unit, "",
                offer.supplierOfferRevisionId(), offer.snapshotHash(),
                offer.packagingVariantRevisionId(), false,
                offer.quotedUnitPrice());
    }

    private ProductMasterDataDtos.QualityCertificateSave qualityCertificateSave(
            String status) {
        return new ProductMasterDataDtos.QualityCertificateSave(
                "PMB-QC-" + runKey, "MATERIAL", null,
                "行为验收生产厂", "LOT-" + runKey, "HEAT-" + runKey,
                "COIL-" + runKey, LocalDate.of(2026, 7, 23),
                null, null, null, status, List.of(), 0);
    }

    private MockMultipartFile importFileForExistingProduct(
            Long productId, String nextName) {
        Map<String, Object> product = jdbc.queryForMap("""
                select s.name series_name,p.sku,p.name,p.unit,p.grade,p.temper,p.standard,
                       p.spec,p.barcode,p.status
                from product p
                join product_series s on s.id=p.product_series_id
                where p.id=?
                """, productId);
        List<String> headers = new ArrayList<>(List.of(
                "产品系列*", "SKU", "商品名称", "库存单位*",
                "牌号", "材料状态", "执行标准", "规格备注", "条码", "启用状态"));
        List<String> values = new ArrayList<>(List.of(
                Objects.toString(product.get("series_name"), ""),
                Objects.toString(product.get("sku"), ""),
                Objects.toString(product.get("name"), ""),
                Objects.toString(product.get("unit"), ""),
                Objects.toString(product.get("grade"), ""),
                Objects.toString(product.get("temper"), ""),
                Objects.toString(product.get("standard"), ""),
                Objects.toString(product.get("spec"), ""),
                nextName,
                Objects.toString(product.get("status"), "")));
        for (Map<String, Object> field : jdbc.queryForList("""
                select coalesce(nullif(sf.display_name,''),sf.field_name) field_name,
                       coalesce(nullif(pfv.normalized_code,''),
                                nullif(pfv.value_text,''),
                                cast(pfv.value_decimal as char),'') field_value
                from product_field_value pfv
                join product_series_field sf on sf.id=pfv.series_field_id
                where pfv.product_id=? and sf.status='ENABLED'
                order by sf.sort_no,sf.id
                """, productId)) {
            headers.add("属性:" + field.get("field_name"));
            values.add(Objects.toString(field.get("field_value"), ""));
        }
        String csv = "\uFEFF" + csvLine(headers) + "\n" + csvLine(values) + "\n";
        return new MockMultipartFile(
                "file", "existing-" + productId + "-" + runKey + ".csv",
                "text/csv", csv.getBytes(StandardCharsets.UTF_8));
    }

    private void assertBaseUnitChangeBlocked(Long productId, String label) {
        PackagingDtos.Profile current = packagingService.profile(productId);
        BusinessException error = assertThrows(BusinessException.class, () ->
                packagingService.saveProfile(productId, new PackagingDtos.ProfileSave(
                        current.versionNo(), "g", "g", "g", "g", "g",
                        "REJECT", List.of(), List.of(),
                        label + "基础单位不可重释验收")));
        assertTrue(error.getMessage().contains("停用旧商品并新建SKU"),
                label + " / " + error.getMessage());
        assertEquals(current.baseUnit(), jdbc.queryForObject(
                "select unit from product where id=?", String.class, productId));
    }

    private Long createSupplier() {
        return createSupplier("");
    }

    private Long createSupplier(String suffix) {
        String businessNo = String.valueOf(
                700000 + Math.floorMod((runKey + "-" + suffix).hashCode(), 299999));
        String supplierName = "PMB供应商-" + runKey
                + (suffix.isBlank() ? "" : "-" + suffix);
        jdbc.update("""
                insert into partner
                (business_no,type,type_mask,name,contact_person,phone,status,memo)
                values (?,'SUPPLIER',2,?,'验收人员','00000000000','ENABLED',?)
                """, businessNo, supplierName,
                "INTERNAL_REHEARSAL_ONLY " + runKey);
        Long supplierId = id("select id from partner where name=?", supplierName);
        jdbc.update("""
                insert into partner_supplier_profile(partner_id,status)
                values (?,'ENABLED')
                """, supplierId);
        return supplierId;
    }

    private void restrictPartnerScopeTo(Long allowedSupplierId) {
        String roleCode = "PMB_SCOPE_" + runKey;
        String roleName = "商品行为范围验收-" + runKey;
        jdbc.update("""
                insert into security_role
                (code,name,description,system_preset,status)
                values(?,?,?,0,'ENABLED')
                """, roleCode, roleName, "INTERNAL_REHEARSAL_ONLY");
        Long roleId = id("select id from security_role where code=?", roleCode);
        jdbc.update("""
                insert into security_role_data_scope
                (role_id,dimension,scope_mode,selected_ids_json)
                values (?,'PARTNER','CUSTOM',json_array(?))
                """, roleId, allowedSupplierId);
        long scopedUserId = 8_000_000_000L
                + Math.floorMod(runKey.hashCode(), 999_999);
        jdbc.update("""
                insert into system_user_role(user_id,role_id,assigned_by)
                values (?,?,1)
                """, scopedUserId, roleId);
        SecurityContext.set(new SecurityContext.Principal(
                scopedUserId, 1L, "商品行为验收", "PMB", 1L, "内部验收",
                "PMB-SCOPE-" + runKey, false, List.of("范围验收"),
                Set.of("product.master.complex.view", "product.master.complex.manage",
                        "product.view", "product.manage",
                        "product.cost.view", "product.supplier.offer.manage",
                        "inventory.quality.view", "inventory.quality.manage")));
    }

    private void restrictProductScopeTo(Long allowedProductId) {
        String roleCode = "PMB_PRODUCT_SCOPE_" + runKey;
        String roleName = "商品范围验收-" + runKey;
        jdbc.update("""
                insert into security_role
                (code,name,description,system_preset,status)
                values(?,?,?,0,'ENABLED')
                """, roleCode, roleName, "INTERNAL_REHEARSAL_ONLY");
        Long roleId = id("select id from security_role where code=?", roleCode);
        jdbc.update("""
                insert into security_role_data_scope
                (role_id,dimension,scope_mode,selected_ids_json)
                values (?,'PRODUCT','CUSTOM',json_array(?))
                """, roleId, allowedProductId);
        long scopedUserId = 8_300_000_000L
                + Math.floorMod(runKey.hashCode(), 999_999);
        jdbc.update("""
                insert into system_user_role(user_id,role_id,assigned_by)
                values (?,?,1)
                """, scopedUserId, roleId);
        usePrincipal(scopedUserId, "商品范围验收", MASTER_PERMISSIONS);
    }

    private void usePrincipal(
            long userId, String roleName, Set<String> permissions) {
        SecurityContext.set(new SecurityContext.Principal(
                userId, 1L, "商品行为验收", "PMB", 1L, "内部验收",
                "PMB-" + runKey, false, List.of(roleName), permissions));
    }

    private Long createWarehouse() {
        String name = "PMB验收仓-" + runKey;
        jdbc.update("""
                insert into warehouse(name,location,status)
                values (?,'INTERNAL_REHEARSAL_ONLY','ENABLED')
                """, name);
        return id("select id from warehouse where name=?", name);
    }

    private Long createInventoryBatch(Long productId, Long warehouseId) {
        String unit = Objects.requireNonNull(
                jdbc.queryForObject("select unit from product where id=?",
                        String.class, productId));
        String batchNo = "PMB-BATCH-" + runKey;
        jdbc.update("""
                insert into inventory_batch
                (product_id,warehouse_id,batch_no,source_type,source_id,base_unit,
                 packaging_profile_snapshot_json,quantity_in,quantity_available,
                 unit_cost,amount_cost,status,received_at)
                values (?,?,?,'OPENING',0,?,'{}',1.000000,1.000000,
                        1.0000,1.00,'AVAILABLE',?)
                """, productId, warehouseId, batchNo, unit,
                java.sql.Date.valueOf(LocalDate.of(2026, 7, 23)));
        return id("""
                select id from inventory_batch
                where product_id=? and warehouse_id=? and batch_no=?
                """, productId, warehouseId, batchNo);
    }

    private String seriesBaseUnit(String seriesName) {
        return Objects.requireNonNull(jdbc.queryForObject("""
                select r.base_unit
                from product_series s
                join product_series_packaging_binding b
                  on b.product_series_id=s.id and b.status='ENABLED'
                join packaging_template t on t.id=b.template_id and t.status='ENABLED'
                join packaging_template_revision r on r.id=t.current_revision_id
                where s.name=?
                """, String.class, seriesName));
    }

    private static String csvLine(List<String> values) {
        return values.stream()
                .map(value -> "\"" + value.replace("\"", "\"\"") + "\"")
                .reduce((left, right) -> left + "," + right)
                .orElse("");
    }

    private static String bomJson(String quantity) {
        return """
                {
                  "name":"数量HTTP验收",
                  "outputQuantity":%s,
                  "outputUnit":"kg",
                  "scrapRate":0,
                  "effectiveFrom":"2026-07-23",
                  "status":"ENABLED",
                  "changeReason":"验证六位小数边界",
                  "items":[{
                    "componentProductId":2,
                    "quantity":%s,
                    "unit":"kg",
                    "scrapRate":0,
                    "issueMethod":"MANUAL",
                    "sortNo":10
                  }],
                  "expectedRevisionNo":0
                }
                """.formatted(quantity, quantity);
    }

    private Long id(String sql, Object... args) {
        return Objects.requireNonNull(jdbc.queryForObject(sql, Long.class, args));
    }

    private int intValue(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        assertNotNull(value);
        return value;
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0L : value;
    }
}
