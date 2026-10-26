package com.soyia.copperv2.service;

import com.soyia.copperv2.dto.AlignedDtos;
import com.soyia.copperv2.dto.InboundCreateRequest;
import com.soyia.copperv2.dto.InventoryBatchDto;
import com.soyia.copperv2.dto.InventoryDtos;
import com.soyia.copperv2.dto.OutboundActionRequests;
import com.soyia.copperv2.dto.OutboundCreateRequest;
import com.soyia.copperv2.dto.PackagingDtos;
import com.soyia.copperv2.dto.PurchaseCreateRequest;
import com.soyia.copperv2.dto.PaymentCreateRequest;
import com.soyia.copperv2.dto.SupplierPaymentCreateRequest;
import com.soyia.copperv2.dto.SimpleCreateRequests;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIfEnvironmentVariable(named = "COPPER_15_CATEGORY_ACCEPTANCE", matches = "true")
@Transactional
class FifteenProductCategoryMySqlAcceptanceTest {
    private static final BigDecimal PURCHASE_QUANTITY = new BigDecimal("10.000");
    private static final BigDecimal SALES_QUANTITY = new BigDecimal("2.000");
    private static final BigDecimal RETURN_QUANTITY = new BigDecimal("1.000");
    private static final BigDecimal EXPECTED_BALANCE = new BigDecimal("8.000");

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private CopperService copperService;

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private PackagingService packagingService;

    private String token;
    private Long partnerId;
    private Long addressId;
    private Long warehouseId;

    @BeforeEach
    void createBusinessFixtureInsideRollbackTransaction() {
        assertTrue(TransactionSynchronizationManager.isActualTransactionActive(),
                "15 类商品验收必须在可回滚事务中运行");
        token = "P15" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
        SecurityContext.set(new SecurityContext.Principal(
                1L, 1L, "15类商品验收", "P15", 0L, "验收", "p15-acceptance",
                false, List.of("系统管理员"), Set.of("inventory.cost.view")));

        String businessNo = String.valueOf(800000 + Math.floorMod(token.hashCode(), 199999));
        jdbc.update("""
                insert into partner
                (business_no,type,type_mask,name,contact_person,phone,status,memo)
                values (?,'CUSTOMER',3,?,'验收人员','00000000000','ENABLED',?)
                """, businessNo, token + "-往来单位", "INTERNAL_REHEARSAL_ONLY " + token);
        partnerId = requiredLong("select id from partner where name=?", token + "-往来单位");
        jdbc.update("insert into partner_customer_profile(partner_id,status) values (?,'ENABLED')", partnerId);
        jdbc.update("insert into partner_supplier_profile(partner_id,status) values (?,'ENABLED')", partnerId);
        jdbc.update("""
                insert into partner_address
                (partner_id,name,address_type,detail,contact_person,phone,first_choice,status,memo)
                values (?,?,'RECEIVING',?,'验收人员','00000000000',1,'ENABLED',?)
                """, partnerId, token + "-收货地址", "青岛市验收隔离地址", token);
        addressId = requiredLong("select id from partner_address where partner_id=? and name=?",
                partnerId, token + "-收货地址");
        jdbc.update("""
                insert into warehouse(name,location,status) values (?,?,'ENABLED')
                """, token + "-验收仓", "INTERNAL_REHEARSAL_ONLY");
        warehouseId = requiredLong("select id from warehouse where name=?", token + "-验收仓");
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContext.clear();
    }

    @Test
    @DisplayName("15类商品逐类完成建档、SKU、采购、入库、销售、出库、销退、采退和库存查询")
    void allFifteenFamiliesCompleteIndependentBusinessChains() {
        assertEquals(15L, count("""
                select count(*)
                from product_series s
                join product_category c on c.id=s.category_id
                where s.id between 2001 and 2015
                  and s.status='ENABLED' and c.status='ENABLED'
                """), "15 类系列及所属分类必须全部启用");

        for (Family family : families()) {
            try {
                acceptFamily(family);
            } catch (Throwable ex) {
                fail(family.name() + "（系列 " + family.seriesId() + "）业务链验收失败：" + ex.getMessage(), ex);
            }
        }

        assertEquals(15L, count("select count(*) from product where sku like ?", token + "-%"));
        assertEquals(15L, count("select count(*) from purchase_order where memo like ?", token + "%"));
        assertEquals(15L, count("select count(*) from inbound_order where memo like ? and status='STOCKED'", token + "%"));
        assertEquals(15L, count("select count(*) from sales_order where memo like ?", token + "%"));
        assertEquals(15L, count("select count(*) from outbound_order where memo like ? and status='SHIPPED'", token + "%"));
        assertEquals(15L, count("select count(*) from sales_return_order where memo like ? and status='CONFIRMED'", token + "%"));
        assertEquals(15L, count("select count(*) from purchase_return_order where memo like ? and status='CONFIRMED'", token + "%"));
    }

    @Test
    @DisplayName("盘管直管铜排保持实重整包，分歧管和焊条支持固定数量拆包")
    void representativePackagingDifferencesAreExecutable() {
        assertActualWeightPackage(2001, "紫铜大盘管", "盘", new BigDecimal("20.345"),
                number(300001, "77.001"), number(300002, "0.801"));
        assertActualWeightPackage(2004, "紫铜直管", "根", new BigDecimal("12.680"),
                number(300041, "77.004"), number(300042, "0.804"));
        assertActualWeightPackage(2014, "铜排", "根", new BigDecimal("18.760"),
                text(300141, token + "-TP-PACK"), number(300142, "77.014"), number(300143, "7.014"));

        assertFixedBreakablePackage(2007, "空调分歧管/分支器", "件", "箱", new BigDecimal("20"),
                text(300071, "格力空调系列"), text(300072, token + "-PACK-FQ"));
        assertFixedBreakablePackage(2015, "铜焊条", "支", "包", new BigDecimal("20"),
                text(300151, "扁焊条"), text(300152, token + "-PACK-HT"));
    }

    private void assertActualWeightPackage(long seriesId, String name, String parentUnit, BigDecimal actualWeight,
                                           SimpleCreateRequests.ProductFieldValueCreate... fields) {
        Long productId = createPackagingProduct(seriesId, name, "kg", fields);
        PackagingDtos.Profile inherited = packagingService.profile(productId);
        PackagingDtos.Profile profile = packagingService.saveProfile(productId, new PackagingDtos.ProfileSave(
                inherited.versionNo(), "kg", "kg", "kg", "kg", "kg", "REJECT",
                List.of(new PackagingDtos.RuleSave(
                        parentUnit, "kg", "ACTUAL_WEIGHT", null, actualWeight,
                        false, false, true, true, true, true, 10, "ENABLED")),
                List.of(), "15类商品实重原包装验收"));

        assertEquals("PRODUCT_OVERRIDE", profile.sourceLevel());
        assertEquals(1, profile.rules().size());
        assertFalse(profile.rules().get(0).breakable(), name + " 实重包装不得拆分");
        assertFalse(profile.rules().get(0).repackable(), name + " 实重包装不得自动复包");
        assertEquals("kg", profile.defaultPurchaseUnit(), name + " 默认采购必须按kg");
        assertEquals("kg", profile.defaultSalesUnit(), name + " 默认销售必须按kg");

        PackagingDtos.Plan purchase = packagingService.plan(productId, new PackagingDtos.PlanRequest(
                BigDecimal.ONE, parentUnit, null, "kg", "PURCHASE"));
        assertTrue(purchase.estimated(), name + " 下单阶段应按参考重量估算");
        assertTrue(purchase.actualWeightRequired(), name + " 必须在实物流转时录入实重");
        assertEquals(0, actualWeight.compareTo(purchase.baseQuantity()));

        BusinessException missingActual = assertThrows(BusinessException.class, () -> packagingService.plan(
                productId, new PackagingDtos.PlanRequest(BigDecimal.ONE, parentUnit, null, "kg", "INBOUND")));
        assertTrue(missingActual.getMessage().contains("必须填写实际"), name + " 入库不得沿用估算重量");

        PackagingDtos.Plan inbound = packagingService.plan(productId, new PackagingDtos.PlanRequest(
                BigDecimal.ONE, parentUnit, actualWeight, "kg", "INBOUND"));
        PackagingDtos.Plan outbound = packagingService.plan(productId, new PackagingDtos.PlanRequest(
                BigDecimal.ONE, parentUnit, actualWeight, "kg", "OUTBOUND"));
        assertFalse(inbound.estimated());
        assertEquals("ACTUAL", inbound.quantityBasis());
        assertEquals(0, actualWeight.compareTo(inbound.baseQuantity()));
        assertEquals(0, actualWeight.compareTo(outbound.baseQuantity()));
        assertKgActualWeightBusinessFlow(productId, name, parentUnit);
    }

    private void assertKgActualWeightBusinessFlow(Long productId, String name, String packageUnit) {
        LocalDate businessDate = LocalDate.now();
        BigDecimal totalWeight = new BigDecimal("63");
        BigDecimal shippedWeight = new BigDecimal("41");
        BigDecimal packageCount = new BigDecimal("3");

        Long purchaseId = copperService.createPurchaseOrder(new PurchaseCreateRequest(
                partnerId, businessDate, businessDate, warehouseId, "PHONE", token,
                BigDecimal.ZERO, BigDecimal.ZERO, token + " kg采购包装展示 " + name,
                List.of(new PurchaseCreateRequest.Item(
                        null, productId, totalWeight, "kg", null,
                        null, null, "kg", packageCount, packageUnit,
                        null, null, null, false, new BigDecimal("50.0000")))));
        copperService.confirmPurchaseOrder(purchaseId);
        Long purchaseItemId = requiredLong(
                "select id from purchase_order_item where purchase_order_id=? and product_id=?", purchaseId, productId);
        assertEquals(0, packageCount.compareTo(requiredDecimal(
                "select package_quantity from purchase_order_item where id=?", purchaseItemId)));

        Long inboundId = copperService.createInbound(new InboundCreateRequest(
                purchaseId, partnerId, warehouseId, "PURCHASE_ORDER", token,
                businessDate, businessDate, token + "-KG-PACK-SEND-" + productId,
                BigDecimal.ZERO, BigDecimal.ZERO, token + " kg入库包装展示 " + name,
                List.of(new InboundCreateRequest.Item(
                        productId, purchaseItemId, token + "-KG-PACK-BATCH-" + productId, "",
                        totalWeight, "kg", totalWeight, null, null,
                        packageCount, "kg", packageUnit, "", new BigDecimal("50.0000"),
                        List.of(
                                new PackagingDtos.PackageInstanceInput(token + "-" + productId + "-01", new BigDecimal("21")),
                                new PackagingDtos.PackageInstanceInput(token + "-" + productId + "-02", new BigDecimal("22")),
                                new PackagingDtos.PackageInstanceInput(token + "-" + productId + "-03", new BigDecimal("20"))
                        ), businessDate))));
        copperService.submitInbound(inboundId, "kg数量与包装数分离验收");
        copperService.auditInbound(inboundId, true, "", "kg数量与包装数分离验收");
        Long batchId = requiredLong(
                "select inventory_batch_id from inbound_item where inbound_id=? and product_id=?", inboundId, productId);
        List<PackagingDtos.PackageInstance> instances = copperService.inventoryPackageInstances(batchId);
        assertEquals(3, instances.size());
        assertTrue(instances.stream().allMatch(item -> packageUnit.equals(item.unitName())));

        Long salesOrderId = copperService.createSalesOrder(new AlignedDtos.SalesOrderCreate(
                name + " kg销售包装展示", partnerId, addressId, businessDate, businessDate, warehouseId,
                BigDecimal.ZERO, BigDecimal.ZERO, "POSTPAID", "NONE", "AMOUNT",
                BigDecimal.ZERO, BigDecimal.ZERO, token + " kg销售包装展示 " + name,
                List.of(new AlignedDtos.SalesOrderCreate.Item(
                        null, productId, shippedWeight, "kg", null,
                        null, null, "kg", new BigDecimal("2"), packageUnit,
                        false, new BigDecimal("80.0000")))));
        copperService.confirmSalesOrder(salesOrderId);
        Long salesItemId = requiredLong(
                "select id from sales_order_item where sales_order_id=? and product_id=?", salesOrderId, productId);
        assertEquals(0, new BigDecimal("2").compareTo(requiredDecimal(
                "select package_quantity from sales_order_item where id=?", salesItemId)));

        List<Long> selectedIds = List.of(instances.get(0).id(), instances.get(2).id());
        Long outboundId = copperService.createOutbound(new OutboundCreateRequest(
                salesOrderId, partnerId, addressId, warehouseId, businessDate, LocalDateTime.now(),
                "SALES_ORDER", "NORMAL", "COMPLETE", "", false,
                "NONE", BigDecimal.ZERO, true, false, "",
                token + "-KG-PACK-OUT-" + productId, BigDecimal.ZERO, BigDecimal.ZERO,
                token + " kg出库包装展示 " + name,
                List.of(new OutboundCreateRequest.Item(
                        salesItemId, batchId, shippedWeight, "kg", shippedWeight,
                        null, null, new BigDecimal("2"), packageUnit,
                        "kg", new BigDecimal("80.0000"), selectedIds))));

        assertEquals("SHIPPED", requiredString("select status from outbound_order where id=?", outboundId));
        assertEquals(0, new BigDecimal("22").compareTo(requiredDecimal(
                "select quantity_available from inventory_batch where id=?", batchId)));
        assertEquals(1L, count("""
                select count(*) from inventory_package_instance
                where batch_id=? and status='AVAILABLE'
                """, batchId));
    }

    private void assertFixedBreakablePackage(long seriesId, String name, String baseUnit,
                                             String parentUnit, BigDecimal childQuantity,
                                             SimpleCreateRequests.ProductFieldValueCreate... fields) {
        Long productId = createPackagingProduct(seriesId, name, baseUnit, fields);
        PackagingDtos.Profile inherited = packagingService.profile(productId);
        PackagingDtos.Profile profile = packagingService.saveProfile(productId, new PackagingDtos.ProfileSave(
                inherited.versionNo(), baseUnit, baseUnit, baseUnit, parentUnit, baseUnit, "REJECT",
                List.of(new PackagingDtos.RuleSave(
                        parentUnit, baseUnit, "FIXED", childQuantity, null,
                        true, true, true, true, true, true, 10, "ENABLED")),
                List.of(), "15类商品固定包装拆分验收"));

        assertTrue(profile.rules().get(0).breakable(), name + " 固定数量包装应允许拆分");
        assertTrue(profile.rules().get(0).repackable(), name + " 固定数量包装应允许复包");
        PackagingDtos.Plan purchase = packagingService.plan(productId, new PackagingDtos.PlanRequest(
                BigDecimal.ONE, parentUnit, null, baseUnit, "PURCHASE"));
        assertEquals(0, childQuantity.compareTo(purchase.baseQuantity()), name + " 整包换算错误");
        assertFalse(purchase.estimated());

        PackagingDtos.Plan splitSale = packagingService.plan(productId, new PackagingDtos.PlanRequest(
                new BigDecimal("5"), baseUnit, null, baseUnit, "SALES"));
        assertEquals(0, new BigDecimal("5").compareTo(splitSale.baseQuantity()), name + " 拆包销售换算错误");
        assertFalse(splitSale.actualWeightRequired());
        assertPackagingBusinessFlow(productId, name, parentUnit, BigDecimal.ONE, null,
                BigDecimal.ONE, parentUnit, false, new BigDecimal("5"), baseUnit, null,
                childQuantity.subtract(new BigDecimal("5")));
    }

    private void assertPackagingBusinessFlow(Long productId, String name,
                                             String purchaseUnit, BigDecimal purchaseQuantity,
                                             BigDecimal inboundActualBaseQuantity,
                                             BigDecimal inboundPackageQuantity, String inboundPackageUnit,
                                             boolean actualInstance,
                                             BigDecimal salesQuantity, String salesUnit,
                                             BigDecimal outboundActualBaseQuantity,
                                             BigDecimal expectedBalance) {
        String baseUnit = requiredString("select unit from product where id=?", productId);
        LocalDate businessDate = LocalDate.now();
        Long purchaseId = copperService.createPurchaseOrder(new PurchaseCreateRequest(
                partnerId, businessDate, businessDate, warehouseId, "PHONE", token,
                BigDecimal.ZERO, BigDecimal.ZERO, token + " 包装采购 " + name,
                List.of(new PurchaseCreateRequest.Item(
                        null, productId, purchaseQuantity, purchaseUnit, null,
                        null, null, baseUnit, inboundPackageUnit, new BigDecimal("50.0000")))));
        copperService.confirmPurchaseOrder(purchaseId);
        Long purchaseItemId = requiredLong(
                "select id from purchase_order_item where purchase_order_id=? and product_id=?", purchaseId, productId);

        List<PackagingDtos.PackageInstanceInput> instances = actualInstance
                ? List.of(new PackagingDtos.PackageInstanceInput(
                        token + "-PACKAGE-" + productId, inboundActualBaseQuantity))
                : List.of();
        Long inboundId = copperService.createInbound(new InboundCreateRequest(
                purchaseId, partnerId, warehouseId, "PURCHASE_ORDER", token,
                businessDate, businessDate, token + "-PACK-SEND-" + productId,
                BigDecimal.ZERO, BigDecimal.ZERO, token + " 包装入库 " + name,
                List.of(new InboundCreateRequest.Item(
                        productId, purchaseItemId, token + "-PACK-BATCH-" + productId, "",
                        purchaseQuantity, purchaseUnit, inboundActualBaseQuantity,
                        null, null, inboundPackageQuantity, baseUnit, inboundPackageUnit, "",
                        new BigDecimal("50.0000"), instances, businessDate))));
        copperService.submitInbound(inboundId, "15类包装验收");
        copperService.auditInbound(inboundId, true, "", "15类包装验收");
        Long batchId = requiredLong("""
                select inventory_batch_id from inbound_item where inbound_id=? and product_id=?
                """, inboundId, productId);
        Long packageInstanceId = actualInstance
                ? copperService.inventoryPackageInstances(batchId).get(0).id()
                : null;

        Long salesOrderId = copperService.createSalesOrder(new AlignedDtos.SalesOrderCreate(
                name + "包装销售", partnerId, addressId, businessDate, businessDate, warehouseId,
                BigDecimal.ZERO, BigDecimal.ZERO, "POSTPAID", "NONE", "AMOUNT",
                BigDecimal.ZERO, BigDecimal.ZERO, token + " 包装销售 " + name,
                List.of(new AlignedDtos.SalesOrderCreate.Item(
                        null, productId, salesQuantity, salesUnit, null,
                        null, null, baseUnit, "", false, new BigDecimal("80.0000")))));
        copperService.confirmSalesOrder(salesOrderId);
        Long salesItemId = requiredLong(
                "select id from sales_order_item where sales_order_id=? and product_id=?", salesOrderId, productId);
        Long outboundId = copperService.createOutbound(new OutboundCreateRequest(
                salesOrderId, partnerId, addressId, warehouseId, businessDate, LocalDateTime.now(),
                "SALES_ORDER", "NORMAL", "COMPLETE", "", false,
                "NONE", BigDecimal.ZERO, true, false, "",
                token + "-PACK-OUT-" + productId, BigDecimal.ZERO, BigDecimal.ZERO,
                token + " 包装出库 " + name,
                List.of(new OutboundCreateRequest.Item(
                        salesItemId, batchId, salesQuantity, salesUnit, outboundActualBaseQuantity,
                        null, null, actualInstance ? BigDecimal.ONE : null,
                        actualInstance ? purchaseUnit : "", baseUnit, new BigDecimal("80.0000"),
                        actualInstance ? List.of(packageInstanceId) : List.of()))));
        assertEquals("SHIPPED", requiredString("select status from outbound_order where id=?", outboundId));
        assertEquals(0, expectedBalance.compareTo(requiredDecimal(
                "select quantity_available from inventory_batch where id=?", batchId)), name + " 包装业务流库存错误");
        if (actualInstance) {
            assertEquals(0L, count("""
                    select count(*) from inventory_package_instance
                    where id=? and status='AVAILABLE'
                    """, packageInstanceId), name + " 原包装出库后不得残留为可用");
        }
    }

    private Long createPackagingProduct(long seriesId, String name, String baseUnit,
                                        SimpleCreateRequests.ProductFieldValueCreate... fields) {
        return copperService.createProduct(new SimpleCreateRequests.ProductCreate(
                null, seriesId, null, token + "-PACK-" + seriesId,
                token + "-包装-" + name, "", "", "", "", "{}", "", "",
                baseUnit, new BigDecimal("100.0000"), "ENABLED", List.of(fields)));
    }

    private void acceptFamily(Family family) {
        Long productId = copperService.createProduct(new SimpleCreateRequests.ProductCreate(
                null, family.seriesId(), null, token + "-" + family.seriesId(),
                token + "-" + family.name(), "", "", "", "", "{}", "", "",
                family.baseUnit(), new BigDecimal("100.0000"), "ENABLED", family.fields()));

        assertNotNull(requiredString("select spec_signature from product where id=?", productId),
                family.name() + " 必须形成稳定 SKU 规格签名");
        assertEquals(0L, count("""
                select count(*)
                from product_series_field sf
                left join product_field_value pfv
                  on pfv.series_field_id=sf.id and pfv.product_id=?
                where sf.product_series_id=? and sf.status='ENABLED' and sf.required=1
                  and pfv.id is null
                """, productId, family.seriesId()), family.name() + " 不得缺少必填规格字段");

        LocalDate businessDate = LocalDate.now();
        Long purchaseId = copperService.createPurchaseOrder(new PurchaseCreateRequest(
                partnerId, businessDate, businessDate, warehouseId, "PHONE", token,
                BigDecimal.ZERO, BigDecimal.ZERO, token + " " + family.name(),
                List.of(new PurchaseCreateRequest.Item(
                        null, productId, PURCHASE_QUANTITY, family.baseUnit(), null,
                        null, null, family.baseUnit(), "", new BigDecimal("50.0000")))));
        copperService.confirmPurchaseOrder(purchaseId);
        Long purchaseItemId = requiredLong(
                "select id from purchase_order_item where purchase_order_id=? and product_id=?", purchaseId, productId);

        Long inboundId = copperService.createInbound(new InboundCreateRequest(
                purchaseId, partnerId, warehouseId, "PURCHASE_ORDER", token,
                businessDate, businessDate, token + "-SEND-" + family.seriesId(),
                BigDecimal.ZERO, BigDecimal.ZERO, token + " " + family.name(),
                List.of(new InboundCreateRequest.Item(
                        productId, purchaseItemId, token + "-BATCH-" + family.seriesId(), "",
                        PURCHASE_QUANTITY, family.baseUnit(), null, null, null,
                        null, family.baseUnit(), "", "", new BigDecimal("50.0000"),
                        List.of(), businessDate))));
        copperService.submitInbound(inboundId, "15类商品验收");
        copperService.auditInbound(inboundId, true, "", "15类商品验收");
        Long inboundItemId = requiredLong("select id from inbound_item where inbound_id=? and product_id=?",
                inboundId, productId);
        Long batchId = requiredLong("select inventory_batch_id from inbound_item where id=?", inboundItemId);

        Long salesOrderId = copperService.createSalesOrder(new AlignedDtos.SalesOrderCreate(
                family.name() + "验收销售", partnerId, addressId, businessDate, businessDate, warehouseId,
                BigDecimal.ZERO, BigDecimal.ZERO, "POSTPAID", "NONE", "AMOUNT",
                BigDecimal.ZERO, BigDecimal.ZERO, token + " " + family.name(),
                List.of(new AlignedDtos.SalesOrderCreate.Item(
                        null, productId, SALES_QUANTITY, family.baseUnit(), null,
                        null, null, family.baseUnit(), "", false, new BigDecimal("80.0000")))));
        copperService.confirmSalesOrder(salesOrderId);
        Long salesItemId = requiredLong(
                "select id from sales_order_item where sales_order_id=? and product_id=?", salesOrderId, productId);

        Long outboundId = copperService.createOutbound(new OutboundCreateRequest(
                salesOrderId, partnerId, addressId, warehouseId, businessDate, LocalDateTime.now(),
                "SALES_ORDER", "NORMAL", "COMPLETE", "", false,
                "NONE", BigDecimal.ZERO, true, false, "",
                token + "-OUT-" + family.seriesId(), BigDecimal.ZERO, BigDecimal.ZERO,
                token + " " + family.name(),
                List.of(new OutboundCreateRequest.Item(
                        salesItemId, batchId, SALES_QUANTITY, family.baseUnit(), null,
                        null, null, null, "", family.baseUnit(), new BigDecimal("80.0000"), List.of()))));
        Long outboundItemId = requiredLong(
                "select id from outbound_item where outbound_id=? and product_id=?", outboundId, productId);

        Long salesReturnId = copperService.createSalesReturn(new OutboundActionRequests.SalesReturnCreate(
                outboundId, businessDate, "15类商品验收销退", token + " " + family.name(),
                List.of(new OutboundActionRequests.SalesReturnCreate.Item(outboundItemId, RETURN_QUANTITY))));
        assertEquals("CONFIRMED", requiredString("select status from sales_return_order where id=?", salesReturnId));

        Long purchaseReturnId = inventoryService.createPurchaseReturn(new InventoryDtos.PurchaseReturnCreate(
                inboundId, businessDate, "15类商品验收采退", token + " " + family.name(),
                List.of(new InventoryDtos.PurchaseReturnItemCreate(
                        inboundItemId, batchId, RETURN_QUANTITY, List.of()))));
        inventoryService.confirmPurchaseReturn(purchaseReturnId);

        // Every category must independently close both its receivable and payable.
        BigDecimal receivable = SALES_QUANTITY.subtract(RETURN_QUANTITY).multiply(new BigDecimal("80"));
        BigDecimal payable = PURCHASE_QUANTITY.subtract(RETURN_QUANTITY).multiply(new BigDecimal("50"));
        Long receiptId = copperService.createPayment(new PaymentCreateRequest(partnerId, salesOrderId,
                null, null, receivable, "CASH", null, businessDate, token + " " + family.name()));
        Long paymentId = copperService.createSupplierPayment(new SupplierPaymentCreateRequest(partnerId,
                purchaseId, null, payable, "CASH", null, businessDate, token + " " + family.name()));
        assertEquals(0, requiredDecimal("select receivable_amount-received_amount-adjusted_amount from sales_order where id=?",
                salesOrderId).compareTo(BigDecimal.ZERO), family.name() + " 应收必须结清");
        assertEquals(0, requiredDecimal("select o.total_amount-o.paid_amount-coalesce((select sum(r.payable_reduction_amount) from purchase_return_order r where r.purchase_order_id=o.id and r.status='CONFIRMED'),0) from purchase_order o where o.id=?",
                purchaseId).compareTo(BigDecimal.ZERO), family.name() + " 应付必须结清");
        assertEquals(1L, count("select count(*) from payment_allocation where payment_id=? and target_id=? and status='ACTIVE'",
                receiptId, salesOrderId));
        assertEquals(1L, count("select count(*) from supplier_payment_allocation where supplier_payment_id=? and target_id=? and status='ACTIVE'",
                paymentId, purchaseId));

        InventoryBatchDto batch = copperService.inventoryBatches(productId, warehouseId, false).stream()
                .filter(row -> Objects.equals(row.id(), batchId))
                .findFirst()
                .orElseThrow(() -> new AssertionError(family.name() + " 库存批次查询不到"));
        assertEquals(0, EXPECTED_BALANCE.compareTo(batch.quantityAvailable()), family.name() + " 期末批次结存错误");
        assertEquals(family.baseUnit(), batch.baseUnit(), family.name() + " 主库存单位错误");
        assertEquals(0, EXPECTED_BALANCE.compareTo(requiredDecimal(
                "select quantity_available from stock_balance where product_id=? and warehouse_id=?",
                productId, warehouseId)), family.name() + " 库存汇总结存错误");
        assertFalse(batch.packagingProfileSnapshotJson().isBlank(), family.name() + " 入库批次必须留存计量快照");

        assertTrue(count("""
                select count(*) from business_posting_batch
                where status='POSTED' and (
                  (source_type='PURCHASE_ORDER' and source_id=?) or
                  (source_type='INBOUND' and source_id=?) or
                  (source_type='SALES_ORDER' and source_id=?) or
                  (source_type='OUTBOUND' and source_id=?) or
                  (source_type='SALES_RETURN' and source_id=?) or
                  (source_type='PURCHASE_RETURN' and source_id=?))
                """, purchaseId, inboundId, salesOrderId, outboundId, salesReturnId, purchaseReturnId) >= 6,
                family.name() + " 六类业务事实必须全部过账");
    }

    private List<Family> families() {
        return List.of(
                family(2001, "紫铜大盘管", "kg", number(300001, "88.001"), number(300002, "1.001")),
                family(2002, "紫铜小盘管", "kg", number(300011, "88.002"), number(300012, "1.002")),
                family(2003, "蚊香盘铜管", "kg", number(300021, "88.003"), number(300022, "1.003")),
                family(2004, "紫铜直管", "kg", number(300041, "88.004"), number(300042, "1.004")),
                family(2005, "承插式直管", "kg", number(300051, "88.005"), number(300052, "1.005")),
                family(2006, "橡塑保温管", "箱", text(300061, "红色管"), text(300062, token + "-16x20x50")),
                family(2007, "空调分歧管/分支器", "件", text(300071, "大金空调系列"), text(300072, token + "-FQ")),
                family(2008, "紫铜管件", "件", text(300081, "紫铜三通"), text(300082, token + "-28.6")),
                family(2009, "覆塑铜管", "kg", text(300091, token + "-FS"), text(300092, "22x1覆塑")),
                family(2010, "内螺纹铜管", "kg", text(300101, token + "-NLW"), number(300102, "9.520")),
                family(2011, "毛细铜管", "kg", text(300111, token + "-MX"), text(300112, "2.0x0.5")),
                family(2012, "异形铜管", "kg", text(300121, "矩形管"), text(300123, token + "-30x20x2")),
                // 牌号已升级为受控主数据维度，验收夹具必须使用可解析的规范编码，
                // 不能再用随机文本绕过主数据唯一性和导入冲突校验。
                family(2013, "电解铜", "kg", text(300131, "T2")),
                family(2014, "铜排", "kg", text(300141, token + "-TP"), number(300142, "88.014"), number(300143, "8.014")),
                family(2015, "铜焊条", "支", text(300151, "圆焊条"), text(300152, token + "-H01"))
        );
    }

    private Family family(long seriesId, String name, String baseUnit,
                          SimpleCreateRequests.ProductFieldValueCreate... fields) {
        return new Family(seriesId, name, baseUnit, List.of(fields));
    }

    private SimpleCreateRequests.ProductFieldValueCreate number(long fieldId, String value) {
        return new SimpleCreateRequests.ProductFieldValueCreate(fieldId, new BigDecimal(value));
    }

    private SimpleCreateRequests.ProductFieldValueCreate text(long fieldId, String value) {
        return new SimpleCreateRequests.ProductFieldValueCreate(fieldId, value);
    }

    @AfterTransaction
    void assertRollbackRemovedAcceptanceFixture() {
        if (token == null) return;
        assertEquals(0L, count("select count(*) from product where sku like ?", token + "-%"));
        assertEquals(0L, count("select count(*) from partner where name=?", token + "-往来单位"));
        assertEquals(0L, count("select count(*) from warehouse where name=?", token + "-验收仓"));
    }

    private Long requiredLong(String sql, Object... args) {
        return Objects.requireNonNull(jdbc.queryForObject(sql, Long.class, args));
    }

    private String requiredString(String sql, Object... args) {
        return Objects.requireNonNull(jdbc.queryForObject(sql, String.class, args));
    }

    private BigDecimal requiredDecimal(String sql, Object... args) {
        return Objects.requireNonNull(jdbc.queryForObject(sql, BigDecimal.class, args));
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0L : value;
    }

    private record Family(long seriesId, String name, String baseUnit,
                          List<SimpleCreateRequests.ProductFieldValueCreate> fields) {
    }
}
