package com.soyia.copperv2.service;

import com.soyia.copperv2.dto.PriceCenterDtos;
import com.soyia.copperv2.dto.PurchaseCreateRequest;
import com.soyia.copperv2.security.SecurityContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.transaction.AfterTransaction;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIfEnvironmentVariable(named = "COPPER_ERP09_E2E", matches = "true")
@Transactional
class Erp09PriceCenterMySqlAcceptanceTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PriceCenterService service;
    @Autowired private CopperService copperService;

    private String token;
    private Long partnerId;
    private Long supplierId;
    private Long productId;
    private LocalDate today;

    @BeforeEach
    void setUp() {
        token = "ERP09" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        today = LocalDate.now();
        SecurityContext.set(owner());
        createMasterData();
    }

    @AfterEach
    void clearContext() { SecurityContext.clear(); }

    @Test
    @DisplayName("基准铜价、加价、数量阶梯、客户特价和供应商协议按唯一优先级取价并冻结来源")
    void deterministicPricingKeepsEffectiveVersionsAndManualOverrideAudit() {
        BusinessException zeroBenchmark = assertThrows(BusinessException.class, () ->
                createBenchmark(today.minusDays(3), "0", "零价基准不得进入正式价格版本"));
        assertTrue(zeroBenchmark.getMessage().contains("大于0"));
        createBenchmark(today.minusDays(2), "100.0000", "首日市场铜价版本");
        createBenchmark(today, "110.0000", "当日市场铜价版本");
        createFormulaRule("STANDARD", 0L, "STANDARD", "0", null,
                "10", "2", "1", "3", today.minusDays(5), null, "标准铜价加价规则发布");
        createFixedRule("CUSTOMER", partnerId, "AGREEMENT", "10", "19.999999", "150",
                today.minusDays(1), today.plusDays(30), "客户年度协议价格生效");
        createFixedRule("CUSTOMER", partnerId, "SPECIAL", "20", null, "145",
                today, today.plusDays(2), "客户本批次特价经老板确认");
        createFixedRule("SUPPLIER", supplierId, "AGREEMENT", "5", null, "95",
                today.minusDays(1), today.plusDays(30), "供应商月度协议进价生效");
        createPurchaseStandardFixedRule("99", "采购标准固定价用于来源分类验收");

        BusinessException zeroFixed = assertThrows(BusinessException.class, () -> createFixedRule(
                "CUSTOMER", partnerId, "AGREEMENT", "40", "49", "0",
                today, today.plusDays(1), "零固定价不得冒充赠品业务语义"));
        assertTrue(zeroFixed.getMessage().contains("大于0"));
        BusinessException invalidAgreement = assertThrows(BusinessException.class, () -> createFixedRule(
                "CUSTOMER", partnerId, "STANDARD", "50", "59", "140",
                today, today.plusDays(1), "指定客户不得误建为标准价格类型"));
        assertTrue(invalidAgreement.getMessage().contains("协议价或特价"));

        PriceCenterDtos.Preview historical = preview("SALES", partnerId, "5", today.minusDays(1));
        assertDecimal("116.0000", historical.unitPrice());
        assertEquals("COPPER_FORMULA", historical.sourceCode());
        assertEquals(1, historical.benchmarkVersionNo());

        PriceCenterDtos.Preview standard = preview("SALES", partnerId, "5", today);
        assertDecimal("126.0000", standard.unitPrice());
        assertEquals(List.of("BENCHMARK", "PROCESSING", "SPECIFICATION", "PACKAGING", "FREIGHT"),
                standard.components().stream().map(PriceCenterDtos.Component::code).toList());
        assertEquals(2, standard.benchmarkVersionNo());

        PriceCenterDtos.Preview agreement = preview("SALES", partnerId, "15", today);
        assertDecimal("150.0000", agreement.unitPrice());
        assertEquals("CUSTOMER_AGREEMENT", agreement.sourceCode());

        PriceCenterDtos.Preview special = preview("SALES", partnerId, "25", today);
        assertDecimal("145.0000", special.unitPrice());
        assertEquals("CUSTOMER_SPECIAL", special.sourceCode());

        PriceCenterDtos.Preview supplier = preview("PURCHASE", supplierId, "6", today);
        assertDecimal("95.0000", supplier.unitPrice());
        assertEquals("SUPPLIER_AGREEMENT", supplier.sourceCode());

        PriceCenterDtos.Preview purchaseStandard = preview("PURCHASE", supplierId, "1", today);
        assertDecimal("99.0000", purchaseStandard.unitPrice());
        assertEquals("STANDARD_PRICE", purchaseStandard.sourceCode());
        assertEquals("标准价", purchaseStandard.sourceName());

        PriceCenterDtos.PriceSnapshot frozen = service.freezeEnteredPrice(
                "SALES", partnerId, productId, decimal("25"), "kg", today, decimal("145"), false);
        assertEquals("CUSTOMER_SPECIAL", frozen.sourceCode());
        assertTrue(frozen.snapshotJson().contains("COPPER_PRICE_CENTER_V1"));
        assertTrue(frozen.snapshotJson().contains("benchmarkVersionId"));

        PriceCenterDtos.PriceSnapshot unchanged = service.freezeEnteredPrice(
                "SALES", partnerId, productId, decimal("25"), "kg", today, decimal("145"), false,
                frozen.snapshotJson());
        assertEquals(frozen.snapshotJson(), unchanged.snapshotJson());
        PriceCenterDtos.PriceSnapshot changedContext = service.freezeEnteredPrice(
                "SALES", partnerId, productId, decimal("26"), "kg", today, decimal("145"), false,
                frozen.snapshotJson());
        assertNotEquals(frozen.snapshotJson(), changedContext.snapshotJson());

        PriceCenterDtos.PriceSnapshot override = service.freezeEnteredPrice(
                "SALES", partnerId, productId, decimal("25"), "kg", today, decimal("143"), false);
        assertEquals("MANUAL_OVERRIDE", override.sourceCode());
        assertTrue(override.snapshotJson().contains("suggestedUnitPrice"));

        Long giftPurchaseId = copperService.createPurchaseOrder(new PurchaseCreateRequest(
                supplierId, today, null, null, "MANUAL", "ERP-09赠品采购验收",
                decimal("0"), decimal("0"), "赠品必须显式留痕",
                List.of(new PurchaseCreateRequest.Item(null, productId, decimal("2"), "kg", null,
                        null, null, "kg", "", true, decimal("0")))));
        assertEquals(1L, count("select count(*) from purchase_order_item where purchase_order_id=? "
                + "and no_charge=1 and unit_price=0 and price_source_code='GIFT'", giftPurchaseId));
        BusinessException ordinaryZero = assertThrows(BusinessException.class, () ->
                copperService.createPurchaseOrder(new PurchaseCreateRequest(
                        supplierId, today, null, null, "MANUAL", "ERP-09普通采购零价拦截",
                        decimal("0"), decimal("0"), "普通采购不得使用零价",
                        List.of(new PurchaseCreateRequest.Item(null, productId, decimal("2"), "kg", null,
                                null, null, "kg", "", false, decimal("0"))))));
        assertTrue(ordinaryZero.getMessage().contains("大于0"));

        BusinessException overlap = assertThrows(BusinessException.class, () -> createFixedRule(
                "CUSTOMER", partnerId, "SPECIAL", "22", "30", "140",
                today, today.plusDays(1), "重复范围特价用于冲突验证"));
        assertTrue(overlap.getMessage().contains("重叠"));

        createBenchmark(today.plusDays(1), "130.0000", "次日市场铜价预先登记");
        assertDecimal("126.0000", preview("SALES", partnerId, "5", today).unitPrice());
        assertDecimal("146.0000", preview("SALES", partnerId, "5", today.plusDays(1)).unitPrice());
    }

    private void createMasterData() {
        jdbc.update("insert into partner(business_no,type,type_mask,name,status) values (?,'CUSTOMER',1,?,'ENABLED')",
                String.valueOf(910000 + Math.floorMod(token.hashCode(), 79999)), token + " 客户");
        partnerId = id("select id from partner where name=?", token + " 客户");
        jdbc.update("insert into partner_customer_profile(partner_id,status,customer_level,tax_included,tax_rate) values (?,'ENABLED','CORE',1,13)", partnerId);
        jdbc.update("insert into partner(business_no,type,type_mask,name,status) values (?,'SUPPLIER',2,?,'ENABLED')",
                String.valueOf(820000 + Math.floorMod(token.hashCode(), 79999)), token + " 供应商");
        supplierId = id("select id from partner where name=?", token + " 供应商");
        jdbc.update("insert into partner_supplier_profile(partner_id,status,tax_included,tax_rate) values (?,'ENABLED',1,13)", supplierId);
        jdbc.update("insert into product(product_series_id,sku,name,spec,unit,status) values (2001,?,?,?,'kg','ENABLED')",
                token + "-SKU", token + " 紫铜管", "TP2 Φ22×1.0");
        productId = id("select id from product where sku=?", token + "-SKU");
    }

    private void createBenchmark(LocalDate date, String amount, String reason) {
        service.createBenchmark(new PriceCenterDtos.BenchmarkSave(token + "-CU", "验收铜基准价", "kg",
                decimal(amount), true, decimal("13"), date, "MARKET_REFERENCE", "INTERNAL_REHEARSAL_ONLY", reason));
    }

    private void createFormulaRule(String scope, Long partner, String agreement, String minimum, String maximum,
                                   String processing, String spec, String packaging, String freight,
                                   LocalDate from, LocalDate to, String reason) {
        service.createRule(new PriceCenterDtos.RuleSave(null, "验收标准铜价公式", "SALES", scope,
                partner, "", agreement, productId, "kg", decimal(minimum), maximum == null ? null : decimal(maximum),
                "FORMULA", token + "-CU", decimal("0"), decimal(processing), decimal(spec),
                decimal(packaging), decimal(freight), true, decimal("13"), from, to, reason));
    }

    private void createFixedRule(String scope, Long partner, String agreement, String minimum, String maximum,
                                 String price, LocalDate from, LocalDate to, String reason) {
        String business = "SUPPLIER".equals(scope) ? "PURCHASE" : "SALES";
        service.createRule(new PriceCenterDtos.RuleSave(null, "验收" + agreement + "价格", business, scope,
                partner, "", agreement, productId, "kg", decimal(minimum), maximum == null ? null : decimal(maximum),
                "FIXED", "", decimal(price), decimal("0"), decimal("0"), decimal("0"), decimal("0"),
                true, decimal("13"), from, to, reason));
    }

    private void createPurchaseStandardFixedRule(String price, String reason) {
        service.createRule(new PriceCenterDtos.RuleSave(null, "验收采购标准固定价", "PURCHASE", "STANDARD",
                0L, "", "STANDARD", productId, "kg", decimal("0"), decimal("4.999999"),
                "FIXED", "", decimal(price), decimal("0"), decimal("0"), decimal("0"), decimal("0"),
                true, decimal("13"), today.minusDays(1), today.plusDays(30), reason));
    }

    private PriceCenterDtos.Preview preview(String business, Long partner, String quantity, LocalDate date) {
        return service.preview(new PriceCenterDtos.PreviewRequest(
                business, partner, productId, decimal(quantity), "kg", date));
    }

    private SecurityContext.Principal owner() {
        return new SecurityContext.Principal(1L, 1L, "ERP-09老板验收", "ERP-09-OWNER", 1L, "本企业", token,
                false, List.of("老板"), Set.of("pricing.view", "pricing.manage"));
    }

    private BigDecimal decimal(String value) { return new BigDecimal(value); }
    private Long id(String sql, Object... args) { return Objects.requireNonNull(jdbc.queryForObject(sql, Long.class, args)); }
    private long count(String sql, Object... args) { Long value = jdbc.queryForObject(sql, Long.class, args); return value == null ? 0 : value; }
    private void assertDecimal(String expected, BigDecimal actual) { assertEquals(0, new BigDecimal(expected).compareTo(actual)); }

    @AfterTransaction
    void rollbackRemovesFixture() {
        if (token == null) return;
        assertEquals(0L, count("select count(*) from partner where name in (?,?)", token + " 客户", token + " 供应商"));
        assertEquals(0L, count("select count(*) from product where sku=?", token + "-SKU"));
        assertEquals(0L, count("select count(*) from price_benchmark_version where benchmark_code=?", token + "-CU"));
        assertEquals(0L, count("select count(*) from price_benchmark_sequence where benchmark_code=?", token + "-CU"));
    }
}
