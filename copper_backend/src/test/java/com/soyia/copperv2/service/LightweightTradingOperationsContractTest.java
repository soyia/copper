package com.soyia.copperv2.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LightweightTradingOperationsContractTest {
    @Test
    void migrationKeepsLightweightFactsTraceableAndTokensHashed() throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/V56__lightweight_trading_operations.sql"));
        for (String marker : new String[]{
                "CREATE TABLE inventory_non_trade_document (",
                "CREATE TABLE purchase_quote_option (",
                "CREATE TABLE sales_cost_allocation (",
                "CREATE TABLE outbound_delivery (",
                "CREATE TABLE outbound_delivery_event (",
                "CREATE TABLE external_share_link (",
                "CREATE TABLE integration_access_key (",
                "token_hash char(64) NOT NULL",
                "INVENTORY_NON_TRADE_POST",
                "INVENTORY_NON_TRADE_REVERSE",
                "inventory.nontrade.manage",
                "operations.insight.view",
                "purchase.quote.manage",
                "outbound.delivery.manage",
                "external.share.manage",
                "integration.key.manage"
        }) assertTrue(sql.contains(marker), marker);
        assertFalse(sql.contains("token_plain"), "访问令牌不得明文持久化");
        assertTrue(sql.contains("不直接改变应收"));
        assertFalse(sql.contains(
                        "r.code='SALES' AND p.code IN ('operations.insight.view','sales.cost.manage'"),
                "销售岗位不能默认看到全企业经营汇总或修改贡献费用");
        assertFalse(sql.contains(
                        "r.code='PURCHASE' AND p.code IN ('operations.insight.view'"),
                "采购岗位不能默认看到全企业经营汇总");
    }

    @Test
    void partialActualPackageUsageKeepsOriginalWeightRemainderAndReversalAudit() throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/V62__partial_actual_package_non_trade.sql"));
        assertEquals(-1310924946, flywayChecksum(sql),
                "已经执行的 V62 必须保持不可变，新增结构应使用后续迁移");
        for (String marker : new String[]{
                "remaining_base_quantity",
                "CREATE TABLE inventory_package_instance_usage (",
                "quantity_before",
                "quantity_after",
                "reversed_at",
                "uk_inventory_package_usage_ref",
                "remaining_base_quantity decimal(24,6)",
                "base_quantity decimal(24,6)"
        }) assertTrue(sql.contains(marker), marker);
        String allocationSql = Files.readString(Path.of(
                "src/main/resources/db/migration/V63__non_trade_package_allocations.sql"));
        assertTrue(allocationSql.contains("selected_package_allocations_json"));
        String signedReceiptSql = Files.readString(Path.of(
                "src/main/resources/db/migration/V64__non_trade_signed_receipt.sql"));
        assertTrue(signedReceiptSql.contains("signed_attachment_id"));
        assertTrue(signedReceiptSql.contains("signed_attachment_sha256"));
        String multiItemSql = Files.readString(Path.of(
                "src/main/resources/db/migration/V65__non_trade_multi_item_document.sql"));
        for (String marker : new String[]{
                "CREATE TABLE inventory_non_trade_item",
                "handler_name",
                "item_count",
                "INSERT INTO inventory_non_trade_item"
        }) assertTrue(multiItemSql.contains(marker), marker);
        String issueBeforeSignatureSql = Files.readString(Path.of(
                "src/main/resources/db/migration/V66__non_trade_issue_before_signature.sql"));
        for (String marker : new String[]{
                "issued_by_name",
                "issued_at",
                "status varchar(32)",
                "ISSUED_PENDING_SIGNATURE"
        }) assertTrue(issueBeforeSignatureSql.contains(marker), marker);

        String service = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/service/PackagingService.java"));
        for (String marker : new String[]{
                "planNonTradeForBatch",
                "consumePartialActualPackages",
                "restorePartialActualPackages",
                "必须逐包装填写来源和扣取量",
                "各来源包装扣取量合计为",
                "同一个来源实重包装不能重复分配",
                "在本次扣取后已有后续业务"
        }) assertTrue(service.contains(marker), marker);
        String inventoryService = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/service/InventoryService.java"));
        for (String marker : new String[]{
                "createNonTradeDraft", "issueNonTrade", "requiredActiveOneOf",
                "set status='ISSUED_PENDING_SIGNATURE'",
                "where id=? and status='ISSUED_PENDING_SIGNATURE'",
                "实际领用人不能为空", "signed_attachment_sha256",
                "lockNonTradeItems", "同一库存批次不能重复添加",
                "nonTradeAllocationDetails", "quantity_before", "quantity_after"
        }) assertTrue(inventoryService.contains(marker), marker);
        String copperService = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/service/CopperService.java"));
        for (String marker : new String[]{
                "current_book_quantity",
                "current_free_quantity",
                "non_trade_issued_quantity",
                "ISSUED_PENDING_SIGNATURE','CONFIRMED','REVERSED",
                "InboundNonTradeRecord"
        }) assertTrue(copperService.contains(marker), marker);
        String controller = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/controller/InventoryController.java"));
        assertTrue(controller.contains("/inventory/non-trade/{id}/issue"));
    }

    private int flywayChecksum(String sql) {
        CRC32 crc32 = new CRC32();
        sql.lines().forEach(line -> crc32.update(line.getBytes(StandardCharsets.UTF_8)));
        return (int) crc32.getValue();
    }

    @Test
    void recommendationsAndContributionReuseBusinessFactsWithoutAutomaticOrders() throws Exception {
        String service = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/service/LightweightOperationsService.java"));
        for (String marker : new String[]{
                "日均出库×(交期",
                "安全期14天",
                "近30天无出库，不建议自动补货",
                "outbound_allocation",
                "purchase_order_item",
                "inventory_amount_cost",
                "sales_cost_allocation",
                "outstanding_amount"
        }) assertTrue(service.contains(marker), marker);
        String replenishmentBlock = service.substring(
                service.indexOf("public List<LightweightOperationsDtos.ReplenishmentRow> replenishment()"),
                service.indexOf("public List<LightweightOperationsDtos.ContributionRow> contributions("));
        assertFalse(replenishmentBlock.contains("insert into purchase_order"),
                "补货建议只能提示，不能替经营者自动建采购单");
    }

    @Test
    void deliverySharingAndOpenApiStayInsideTheirBusinessBoundaries() throws Exception {
        String service = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/service/LightweightOperationsService.java"));
        String deliveryBlock = service.substring(
                service.indexOf("public LightweightOperationsDtos.Delivery saveDelivery("),
                service.indexOf("public List<LightweightOperationsDtos.ShareRow> shares("));
        assertFalse(deliveryBlock.contains("receivable_amount"));
        assertFalse(deliveryBlock.contains("received_amount"));
        assertFalse(deliveryBlock.contains("paid_amount"));
        for (String marker : new String[]{
                "sha256(token)",
                "share.get(\"source_type\")",
                "request.partnerId()",
                "'order.read'",
                "partnerId != number(key, \"partner_id\")",
                "接口密钥无权查看该订单"
        }) assertTrue(service.contains(marker), marker);
    }

    @Test
    void controllersAndSecurityExposeOnlyTheIntendedLightweightEndpoints() throws Exception {
        String controller = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/controller/LightweightOperationsController.java"));
        for (String path : new String[]{
                "/operations/replenishment",
                "/operations/contributions",
                "/operations/owner-summary",
                "/purchase-orders/{purchaseOrderId}/quotes",
                "/operations/sales-costs/{sourceType}/{sourceId}",
                "/outbounds/{outboundId}/delivery",
                "/external-shares",
                "/public/order-shares/{token}",
                "/integration-keys",
                "/open-api/v1/orders/{sourceType}/{sourceNo}"
        }) assertTrue(controller.contains(path), path);
        String security = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/security/SecurityInterceptor.java"));
        assertTrue(security.contains("path.startsWith(\"/public/order-shares/\")"));
        assertTrue(security.contains("path.startsWith(\"/open-api/v1/orders/\")"));
        assertTrue(security.contains("return \"operations.insight.view\""));
        assertTrue(security.contains("return \"integration.key.manage\""));
    }
}
