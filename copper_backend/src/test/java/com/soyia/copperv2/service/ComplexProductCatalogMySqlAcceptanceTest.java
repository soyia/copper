package com.soyia.copperv2.service;

import com.soyia.copperv2.dto.PackagingDtos;
import com.soyia.copperv2.security.SecurityContext;
import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIfEnvironmentVariable(named = "COPPER_COMPLEX_PRODUCT_ACCEPTANCE", matches = "true")
@Transactional
class ComplexProductCatalogMySqlAcceptanceTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PackagingService packagingService;
    @Autowired private Validator validator;

    private String sku;
    private Long productId;

    @BeforeEach
    void setUp() {
        sku = "CPM-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        SecurityContext.set(new SecurityContext.Principal(
                1L, 1L, "复杂商品验收", "CPM", 1L, "内部验收", sku,
                false, List.of("系统管理员"),
                Set.of("product.master.complex.view", "product.master.complex.manage")));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContext.clear();
    }

    @Test
    void migrationsCreateEveryStructuralFamilyWithVersionedIdentityAndAllowedUnits() {
        assertEquals(37L, count("""
                select count(*) from product_series s
                where s.name in (
                  '通用圆形铜管','高强合金铜管','无氧铜管','盘卷铜管','覆层铜管',
                  '保温管材','内螺纹换热管','多端口铜管件','铜带卷材','铜板材',
                  '圆形铜合金棒','方形铜合金棒','六角铜合金棒','扁形铜合金棒',
                  '铜合金锭','异形截面棒材','铜合金圆线','铜合金扁线','电工圆铜杆',
                  '电工圆铜单丝','镀锡铜线','并线与绞线','直条矩形铜排',
                  '卷制矩形铜排','盘圆铜材','异形铜排','漆包圆线','漆包扁线',
                  '特种绝缘导线','工业阀门','矩形钕铁硼磁体',
                  '圆柱环形钕铁硼磁体','异形钕铁硼磁体','磁性组件',
                  '铜及铜合金焊丝','铜及铜合金焊条','再生铜阴极'
                )
                """));
        assertEquals(37L, count("""
                select count(distinct r.product_series_id)
                from product_identity_schema_revision r
                join product_series s on s.id=r.product_series_id
                where r.version_no=1 and s.id>2015
                """));
        assertEquals(0L, count("""
                select count(*) from product_series s
                where s.id>2015 and not exists (
                  select 1 from product_series_allowed_unit u
                  where u.product_series_id=s.id and u.status='ENABLED'
                )
                """));
        assertTrue(count("select count(*) from product_reference_value where reference_type='GRADE'") >= 25);
        assertTrue(count("select count(*) from product_reference_value where reference_type='TEMPER'") >= 10);
    }

    @Test
    void insulationStockAndOperatingConditionIdentityRemainAuditable() {
        assertEquals("支", jdbc.queryForObject("""
                select r.base_unit
                from product_series s
                join product_series_packaging_binding b on b.product_series_id=s.id
                join packaging_template t on t.id=b.template_id
                join packaging_template_revision r on r.id=t.current_revision_id
                where s.name='保温管材'
                """, String.class));
        assertEquals(4L, count("""
                select count(*)
                from product_series_allowed_unit a
                join product_series s on s.id=a.product_series_id
                join unit u on u.id=a.unit_id
                where s.name='保温管材'
                  and u.abbreviation='箱'
                  and a.purpose in ('PURCHASE','SALES','PACKAGING','PRICE')
                  and a.is_default=0
                  and a.status='ENABLED'
                """));
        assertEquals(1L, count("""
                select count(*)
                from product_series_field f
                join product_series s on s.id=f.product_series_id
                where s.name='工业阀门' and f.field_name='适用介质'
                  and f.required=1 and f.sku_dimension=1
                """));
        assertEquals(2L, count("""
                select count(*)
                from product_series_field f
                join product_series s on s.id=f.product_series_id
                where s.name='工业阀门'
                  and f.field_name in ('温度下限','温度上限')
                  and f.sku_dimension=1
                """));
        assertEquals(3L, count("""
                select count(*)
                from product_series_field f
                join product_series s on s.id=f.product_series_id
                where s.name in (
                    '矩形钕铁硼磁体','圆柱环形钕铁硼磁体','异形钕铁硼磁体'
                  )
                  and f.field_name='最高工作温度'
                  and f.sku_dimension=1
                """));
    }

    @Test
    void tradePackagingUsesFixedTonneConversionAndActualPhysicalPackageWeight() {
        Long seriesId = id("select id from product_series where name='铜带卷材'");
        Long categoryId = id("select category_id from product_series where id=?", seriesId);
        jdbc.update("""
                insert into product(category_id,product_series_id,sku,name,unit,status)
                values (?,?,?,?,?,'ENABLED')
                """, categoryId, seriesId, sku, "复杂商品验收铜带", "kg");
        productId = id("select id from product where sku=?", sku);

        PackagingDtos.Profile profile = packagingService.initializeProductProfile(
                productId, "INTERNAL_REHEARSAL_ONLY 复杂商品包装验收");
        assertEquals("TEMPLATE", profile.sourceLevel());
        assertEquals("kg", profile.baseUnit());
        assertTrue(profile.rules().stream().anyMatch(rule ->
                "t".equals(rule.parentUnit()) && "FIXED".equals(rule.conversionMode())));
        assertTrue(profile.rules().stream().anyMatch(rule ->
                "卷".equals(rule.parentUnit()) && "ACTUAL_WEIGHT".equals(rule.conversionMode())));

        PackagingDtos.Plan tonne = packagingService.plan(productId,
                new PackagingDtos.PlanRequest(BigDecimal.ONE, "t", null, "kg", "PURCHASE_ORDER"));
        assertEquals(0, new BigDecimal("1000.000000").compareTo(tonne.baseQuantity()));
        assertFalse(tonne.actualWeightRequired());

        BusinessException missingActual = assertThrows(BusinessException.class, () ->
                packagingService.plan(productId,
                        new PackagingDtos.PlanRequest(BigDecimal.ONE, "卷", null, "kg", "INBOUND")));
        assertTrue(missingActual.getMessage().contains("实际"));

        PackagingDtos.Plan measured = packagingService.plan(productId,
                new PackagingDtos.PlanRequest(
                        BigDecimal.ONE, "卷", new BigDecimal("25.123456"), "kg", "INBOUND"));
        assertEquals(0, new BigDecimal("25.123456").compareTo(measured.baseQuantity()));
        assertFalse(measured.estimated());
    }

    @Test
    void operationalQuantityColumnsKeepSixDecimalFixedPrecision() {
        assertEquals(61L, count("""
                select count(*) from information_schema.columns
                where table_schema=database()
                  and (table_name,column_name) in (
                    ('inbound_item','quantity'),
                    ('inbound_item','package_quantity'),
                    ('inbound_order','total_quantity'),
                    ('inbound_review_snapshot','total_quantity'),
                    ('inbound_review_snapshot_item','quantity'),
                    ('inbound_review_snapshot_item','package_quantity'),
                    ('inventory_batch','quantity_in'),
                    ('inventory_batch','quantity_available'),
                    ('inventory_batch','package_quantity_in'),
                    ('inventory_batch','package_quantity_available'),
                    ('inventory_movement','quantity'),
                    ('outbound_allocation','quantity'),
                    ('outbound_item','quantity'),
                    ('outbound_item','package_quantity'),
                    ('outbound_order','total_quantity'),
                    ('outbound_order','package_quantity'),
                    ('platform_listing','quantity'),
                    ('purchase_order','total_quantity'),
                    ('purchase_order','received_quantity'),
                    ('purchase_order_item','quantity'),
                    ('purchase_order_item','received_quantity'),
                    ('sales_order_item','quantity'),
                    ('stock_balance','quantity_available'),
                    ('inventory_transfer_order','total_quantity'),
                    ('inventory_transfer_item','quantity'),
                    ('inventory_stocktake_order','total_gain_quantity'),
                    ('inventory_stocktake_order','total_loss_quantity'),
                    ('inventory_stocktake_item','system_quantity'),
                    ('inventory_stocktake_item','counted_quantity'),
                    ('inventory_stocktake_item','difference_quantity'),
                    ('purchase_return_order','total_quantity'),
                    ('purchase_return_item','quantity'),
                    ('inventory_cost_adjustment_item','quantity_snapshot'),
                    ('inventory_opening_cost_alignment','quantity_snapshot'),
                    ('business_posting_entry','quantity'),
                    ('landed_cost_allocation_item','original_quantity'),
                    ('landed_cost_allocation_item','remaining_quantity_snapshot'),
                    ('landed_cost_allocation_batch','quantity_snapshot'),
                    ('product_series_package_unit','net_weight'),
                    ('product_series_package_unit','gross_weight'),
                    ('inbound_item','requested_quantity'),
                    ('inbound_item','pricing_quantity'),
                    ('inbound_review_snapshot_item','requested_quantity'),
                    ('inbound_review_snapshot_item','pricing_quantity'),
                    ('outbound_item','requested_quantity'),
                    ('outbound_item','pricing_quantity'),
                    ('purchase_order_item','requested_quantity'),
                    ('purchase_order_item','pricing_quantity'),
                    ('sales_order_item','requested_quantity'),
                    ('sales_order_item','pricing_quantity'),
                    ('inbound_package_instance','actual_base_quantity'),
                    ('inventory_package_balance','quantity'),
                    ('inventory_package_instance','actual_base_quantity'),
                    ('inventory_package_movement','from_quantity'),
                    ('inventory_package_movement','to_quantity'),
                    ('landed_cost_allocation_item','basis_value'),
                    ('product_package_rule','fixed_child_quantity'),
                    ('product_package_rule','reference_base_quantity'),
                    ('product_series_package_unit','exchange_rate'),
                    ('product_series_package_unit','volume'),
                    ('product_series_price_unit','exchange_rate')
                  )
                  and numeric_precision=24 and numeric_scale=6
                """));
    }

    @Test
    void smallestOperationalQuantityRoundTripsAndSeventhDecimalIsRejectedBeforePersistence() {
        PackagingDtos.PlanRequest accepted = new PackagingDtos.PlanRequest(
                new BigDecimal("0.000001"), "kg", null, "kg", "PURCHASE_ORDER");
        PackagingDtos.PlanRequest rejected = new PackagingDtos.PlanRequest(
                new BigDecimal("0.0000001"), "kg", null, "kg", "PURCHASE_ORDER");
        assertTrue(validator.validate(accepted).isEmpty());
        assertTrue(validator.validate(rejected).stream()
                .anyMatch(violation -> "quantity".equals(violation.getPropertyPath().toString())));

        Long seriesId = id("select id from product_series where name='通用圆形铜管'");
        Long categoryId = id("select category_id from product_series where id=?", seriesId);
        jdbc.update("""
                insert into product(category_id,product_series_id,sku,name,unit,status)
                values (?,?,?,?,?,'ENABLED')
                """, categoryId, seriesId, sku, "六位数量精度验收商品", "kg");
        productId = id("select id from product where sku=?", sku);
        String warehouseName = "六位精度验收仓-" + sku;
        jdbc.update("insert into warehouse(name,location,status) values (?,'','ENABLED')",
                warehouseName);
        Long warehouseId = id("select id from warehouse where name=?", warehouseName);
        jdbc.update("""
                insert into stock_balance(product_id,warehouse_id,quantity_available,amount_cost)
                values (?,?,?,0.00)
                """, productId, warehouseId, new BigDecimal("0.000001"));
        BigDecimal stored = jdbc.queryForObject("""
                select quantity_available from stock_balance
                where product_id=? and warehouse_id=?
                """, BigDecimal.class, productId, warehouseId);
        assertEquals(0, new BigDecimal("0.000001").compareTo(stored));
    }

    @Test
    void fullEighteenIntegerDigitQuantityRoundTripsAndNineteenthDigitOverflows() {
        Long existingProductId = id("select id from product order by id limit 1");
        long batchId = Long.MAX_VALUE;
        BigDecimal maximum = new BigDecimal("999999999999999999.999999");
        PackagingDtos.PlanRequest accepted = new PackagingDtos.PlanRequest(
                maximum, "kg", null, "kg", "PURCHASE_ORDER");
        assertTrue(validator.validate(accepted).isEmpty());
        jdbc.update("""
                insert into inventory_package_balance(batch_id,product_id,unit_name,quantity)
                values (?,?,?,?)
                """, batchId, existingProductId, "V50-MAX", maximum);

        BigDecimal stored = jdbc.queryForObject("""
                select quantity from inventory_package_balance
                where batch_id=? and unit_name='V50-MAX'
                """, BigDecimal.class, batchId);
        assertEquals(0, maximum.compareTo(stored));

        BigDecimal overflow = new BigDecimal("1000000000000000000.000000");
        PackagingDtos.PlanRequest rejected = new PackagingDtos.PlanRequest(
                overflow, "kg", null, "kg", "PURCHASE_ORDER");
        assertTrue(validator.validate(rejected).stream()
                .anyMatch(violation -> "quantity".equals(violation.getPropertyPath().toString())));
        assertThrows(DataAccessException.class, () -> jdbc.update("""
                update inventory_package_balance set quantity=?
                where batch_id=? and unit_name='V50-MAX'
                """, overflow, batchId));
    }

    private Long id(String sql, Object... args) {
        return Objects.requireNonNull(jdbc.queryForObject(sql, Long.class, args));
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0L : value;
    }
}
