package com.soyia.copperv2.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DirectTradeContractTest {
    @Test
    void migrationKeepsDirectTradeSeparateFromOwnedInventory() throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/V57__direct_trade_fulfillment.sql"));
        for (String marker : new String[]{
                "CREATE TABLE direct_trade_order (",
                "CREATE TABLE direct_trade_item (",
                "CREATE TABLE direct_trade_delivery (",
                "CREATE TABLE direct_trade_delivery_item (",
                "CREATE TABLE direct_trade_return (",
                "CREATE TABLE direct_trade_event (",
                "'DIRECT_TRADE','DELIVERY_POST'",
                "'DIRECT_TRADE','RETURN_POST'",
                "'DIRECT_TRADE','RETURN_REVERSE'",
                "\"inventoryImpact\":false",
                "direct.trade.view",
                "direct.trade.manage",
                "direct.trade.confirm"
        }) assertTrue(sql.contains(marker), marker);
        assertFalse(sql.contains("CREATE TABLE direct_trade_inventory"),
                "直运不得另造影子库存台账");
    }

    @Test
    void customerAcceptanceIsTheOnlyDeliverySettlementTrigger() throws Exception {
        String service = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/service/DirectTradeService.java"));
        String dispatch = service.substring(
                service.indexOf("public Long dispatch("),
                service.indexOf("public Long accept("));
        assertFalse(dispatch.contains("receivable_amount"));
        assertFalse(dispatch.contains("purchase_payable_amount"));
        assertFalse(dispatch.contains("postingService.post"));

        String acceptance = service.substring(
                service.indexOf("public Long accept("),
                service.indexOf("public Long createReturn("));
        for (String marker : new String[]{
                "客户实收与供应商发出有差异",
                "sale_receivable_amount=sale_receivable_amount+?",
                "purchase_payable_amount=purchase_payable_amount+?",
                "postingService.post(\"DIRECT_TRADE\"",
                "\"DELIVERY_POST\""
        }) assertTrue(acceptance.contains(marker), marker);
    }

    @Test
    void directTradeNeverMutatesWarehouseStock() throws Exception {
        String service = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/service/DirectTradeService.java"));
        for (String forbidden : new String[]{
                "update inventory_batch",
                "insert into inventory_movement",
                "update stock_balance",
                "insert into inbound_order",
                "insert into outbound_order"
        }) assertFalse(service.toLowerCase().contains(forbidden), forbidden);
        assertTrue(service.contains("source_type='DIRECT_TRADE'"));
        assertTrue(service.contains("createPurchaseOrder"));
        assertTrue(service.contains("createSalesOrder"));
    }

    @Test
    void returnsRemainTraceableAndCannotCutBelowSettledAmounts() throws Exception {
        String service = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/service/DirectTradeService.java"));
        for (String marker : new String[]{
                "ensureSettlementNotExceeded",
                "退货后的应收将小于已收款或应收调整",
                "退货后的应付将小于已付款",
                "DIRECT_TRADE_RETURN_REVERSAL",
                "returned_quantity=returned_quantity-?",
                "status='REVERSED'"
        }) assertTrue(service.contains(marker), marker);
    }
}
