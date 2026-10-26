package com.soyia.copperv2.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComplexProductCatalogContractTest {
    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V49__complex_product_catalog_units_and_precision.sql");

    @Test
    void catalogDefinesReusableStructuresForAllMaterialAndComponentShapes() throws Exception {
        String sql = Files.readString(MIGRATION);

        for (String family : List.of(
                "通用圆形铜管", "内螺纹换热管", "铜带卷材", "铜板材",
                "圆形铜合金棒", "异形截面棒材", "铜合金扁线", "并线与绞线",
                "直条矩形铜排", "异形铜排", "漆包圆线", "特种绝缘导线",
                "工业阀门", "矩形钕铁硼磁体", "磁性组件",
                "铜及铜合金焊丝", "再生铜阴极")) {
            assertTrue(sql.contains("'" + family + "'"), family + " 必须有可配置商品系列");
        }
        for (String structure : List.of(
                "product_series_field", "标称截面积", "股数", "绞距",
                "公称通径", "公称压力", "连接形式", "磁化方向",
                "图纸编号", "图纸版本", "质量等级")) {
            assertTrue(sql.contains(structure), structure + " 必须进入结构化商品配置");
        }
    }

    @Test
    void unitAliasesAndPackagingKeepMeasuredWeightSeparateFromPackageCounts() throws Exception {
        String sql = Files.readString(MIGRATION);

        for (String token : List.of(
                "CREATE TABLE unit_alias", "uk_unit_alias_normalized",
                "TRADE-WEIGHT-TUBE", "TRADE-WEIGHT-COIL", "TRADE-WEIGHT-LONG",
                "TRADE-WEIGHT-SHEET", "TRADE-WEIGHT-BULK", "TRADE-WEIGHT-WELD",
                "'ACTUAL_WEIGHT'", "1000.000000", "product_series_allowed_unit")) {
            assertTrue(sql.contains(token), token + " 必须受控配置");
        }
        assertTrue(sql.contains("case when d.conversion_mode='ACTUAL_WEIGHT' then 1 else 0 end"),
                "实重包装必须整包计数并在实物流转时录入实际重量");
        assertTrue(sql.contains(
                        "('保温材料', '空调保温材料', '保温管材', '橡塑保温管,管状保温材料', '包装', '数量', 'BASE-STICK')"),
                "保温管必须按支保有库存事实，箱只能作为受控包装");
        assertTrue(sql.contains("WHERE s.name='保温管材'"),
                "保温管必须单独开放箱包装录入，不能把箱设为基础库存单位");
        assertFalse(sql.contains(
                        "('保温材料', '空调保温材料', '保温管材', '橡塑保温管,管状保温材料', '包装', '数量', 'BASE-BOX')"),
                "装箱数变化不得改写保温管库存与成本事实");
    }

    @Test
    void operatingConditionsThatAffectSubstitutionBelongToSkuIdentity() throws Exception {
        String sql = Files.readString(MIGRATION);

        assertTrue(sql.contains("('工业阀门','适用介质',1,1,1,1,1,90,'')"),
                "阀门适用介质必须必填并参与 SKU 身份");
        assertTrue(sql.contains("('工业阀门','温度下限',0,1,1,1,1,100,'℃')"));
        assertTrue(sql.contains("('工业阀门','温度上限',0,1,1,1,1,110,'℃')"));
        assertTrue(sql.contains("('矩形钕铁硼磁体','最高工作温度',0,1,1,1,1,70,'℃')"));
        assertTrue(sql.contains("('圆柱环形钕铁硼磁体','最高工作温度',0,1,1,1,1,80,'℃')"));
        assertTrue(sql.contains("('异形钕铁硼磁体','最高工作温度',0,1,1,1,1,60,'℃')"));
    }

    @Test
    void operationalQuantityFactsUseSixDecimalFixedPrecision() throws Exception {
        String sql = Files.readString(MIGRATION);

        for (String table : List.of(
                "inbound_item", "inventory_batch", "inventory_movement",
                "inbound_review_snapshot_item",
                "outbound_item", "purchase_order_item", "sales_order_item",
                "stock_balance", "inventory_transfer_item", "inventory_stocktake_item",
                "purchase_return_item", "business_posting_entry",
                "landed_cost_allocation_item", "product_series_package_unit")) {
            assertTrue(sql.contains("ALTER TABLE " + table), table + " 数量精度必须迁移");
        }
        assertFalse(sql.contains("float"), "数量和换算不得使用浮点类型");
        assertTrue(sql.contains("decimal(24,6)"), "经营数量事实必须使用六位定点精度");
    }

    @Test
    void repositoryMigrationRemainsVendorNeutralAndContainsNoExternalCatalogLinks() throws Exception {
        String sql = Files.readString(MIGRATION);

        assertFalse(sql.contains("https://"));
        assertFalse(sql.contains("http://"));
        assertTrue(sql.contains("vendor-neutral"));
    }
}
