package com.soyia.copperv2.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaymentLinkageContractTest {

    @Test
    void outboundPaymentDerivesAndUpdatesItsSalesOrder() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/soyia/copperv2/service/CopperService.java"));

        assertTrue(source.contains("select id, order_no, customer_id, sales_order_id, receivable_amount, paid_amount"));
        assertFalse(source.contains("(Long) rs.getObject(\"sales_order_id\")"),
                "MySQL BIGINT UNSIGNED may be returned as BigInteger and must not be cast to Long");
        assertTrue(source.contains("salesOrderId = outbound.salesOrderId();"));
        assertTrue(source.contains("出库单关联的销售订单与本次回款销售订单不一致"));
        assertTrue(source.contains("update sales_order\n                    set payment_status"));
        assertTrue(source.contains("update outbound_order\n                    set payment_status"));
    }
}
