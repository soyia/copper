package com.soyia.copperv2.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class M703BusinessSafetyContractTest {
    @Test
    void outboundDraftLifecycleIsIdempotentCapacityGuardedAndCancellable() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/soyia/copperv2/service/CopperService.java"));
        String migration = Files.readString(Path.of("src/main/resources/db/migration/V7__outbound_draft_safety.sql"));

        assertTrue(source.contains("where idempotency_key = ? for update"));
        assertTrue(source.contains("validateOutboundDraftCapacity"));
        assertTrue(source.contains("其他出库草稿"));
        assertTrue(source.contains("status='CANCELLED',cancel_reason=?,cancelled_at=now()"));
        assertTrue(source.contains("outbound.over-shipment"));
        assertTrue(migration.contains("ADD COLUMN cancel_reason"));
        assertTrue(migration.contains("'outbound.over-shipment'"));
        String request = Files.readString(Path.of("src/main/java/com/soyia/copperv2/dto/OutboundCreateRequest.java"));
        assertTrue(request.contains("@NotBlank @Size(max = 64) String idempotencyKey"));
    }

    @Test
    void inventoryAndAddressQueriesExposeTraceabilityWithoutMixingRegistrationAddresses() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/soyia/copperv2/service/CopperService.java"));
        assertTrue(source.contains("io.id source_inbound_id, io.order_no source_inbound_no"));
        assertTrue(source.contains("left join inbound_item ii on b.source_type = 'INBOUND' and ii.id = b.source_id"));
        assertTrue(source.contains("address_type = 'RECEIVING' and status = 'ENABLED'"));
    }

    @Test
    void settlementMethodCannotSilentlyDefaultAtAnyWriteBoundary() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/soyia/copperv2/service/CopperService.java"));
        assertTrue(source.contains("throw new BusinessException(\"请选择结算方式\")"));
        assertTrue(source.contains("default -> throw new BusinessException(\"不支持的结算方式\")"));
        String receipt = Files.readString(Path.of("src/main/java/com/soyia/copperv2/dto/PaymentCreateRequest.java"));
        String payment = Files.readString(Path.of("src/main/java/com/soyia/copperv2/dto/SupplierPaymentCreateRequest.java"));
        String prepayment = Files.readString(Path.of("src/main/java/com/soyia/copperv2/dto/SalesOrderPrepaymentRequests.java"));
        assertTrue(receipt.contains("@NotBlank String paymentMethod"));
        assertTrue(payment.contains("@NotBlank String paymentMethod"));
        assertTrue(prepayment.contains("@NotBlank String paymentMethod"));
        assertTrue(prepayment.contains("@NotBlank String refundMethod"));
    }

    @Test
    void dashboardReceivablesExcludeDraftsAndUseTheOwningBusinessDocument() throws Exception {
        String service = Files.readString(Path.of("src/main/java/com/soyia/copperv2/service/CopperService.java"));
        String scope = Files.readString(Path.of("src/main/java/com/soyia/copperv2/security/BusinessDataScopeService.java"));
        String dto = Files.readString(Path.of("src/main/java/com/soyia/copperv2/dto/DashboardDto.java"));

        assertTrue(service.contains("'SALES_ORDER' source_type"));
        assertTrue(service.contains("o.status not in ('DRAFT', 'CANCELLED')"));
        assertTrue(service.contains("o.sales_order_id = 0"));
        assertTrue(service.contains("o.status = 'SHIPPED'"));
        assertTrue(service.contains("greatest(o.receivable_amount - o.received_amount - o.adjusted_amount, 0) > 0"));
        assertTrue(service.contains("(select count(*) from payment_allocation a\n                                where a.payment_id=p.id and a.status='ACTIVE') allocation_count"));
        assertTrue(scope.contains("filterDerivedDocumentList(source.recentReceivables())"));
        assertTrue(scope.contains("BigDecimal receivable = scopedReceivable()"));
        assertTrue(dto.contains("List<OrderDtos.ReceivableRow> recentReceivables"));
    }
}
