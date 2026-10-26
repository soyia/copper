package com.soyia.copperv2.service;

import com.soyia.copperv2.dto.DirectTradeDtos;
import com.soyia.copperv2.dto.PaymentCreateRequest;
import com.soyia.copperv2.dto.SupplierPaymentCreateRequest;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIfEnvironmentVariable(named = "COPPER_DIRECT_TRADE_E2E", matches = "true")
@Transactional
class DirectTradeMySqlAcceptanceTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DirectTradeService directTradeService;
    @Autowired private CopperService copperService;

    private String token;
    private Long customerId;
    private Long supplierId;
    private Long addressId;
    private Long productId;

    @BeforeEach
    void setUp() {
        token = "ZY" + UUID.randomUUID().toString().replace("-", "")
                .substring(0, 10).toUpperCase();
        SecurityContext.set(owner());
        createFixture();
    }

    @AfterEach
    void clearContext() {
        SecurityContext.clear();
    }

    @Test
    @DisplayName("直运购销从承诺到实收结算退货冲正全程不改变自有库存")
    void directTradeClosesPurchaseSaleSettlementAndReturnWithoutWarehouseStock() {
        long inventoryBatchBefore = count(
                "select count(*) from inventory_batch where product_id=?", productId);
        long inventoryMovementBefore = count(
                "select count(*) from inventory_movement where product_id=?", productId);

        Long tradeId = directTradeService.create(new DirectTradeDtos.Create(
                customerId, supplierId, addressId, LocalDate.now(), LocalDate.now().plusDays(2),
                token + " 供应商直接送客户",
                List.of(new DirectTradeDtos.ItemCreate(
                        productId, quantity("10"), "kg", null, null, null,
                        "kg", null, price("12"), price("20")))));
        DirectTradeDtos.Detail detail = directTradeService.detail(tradeId);
        Long tradeItemId = detail.items().get(0).id();
        assertEquals("DRAFT", detail.header().status());
        assertEquals("DIRECT_TRADE", text(
                "select source_type from sales_order where id=?", detail.salesOrderId()));
        assertEquals("DIRECT_TRADE", text(
                "select source_type from purchase_order where id=?", detail.purchaseOrderId()));

        directTradeService.confirm(tradeId, new DirectTradeDtos.Confirm(null, null));
        Long deliveryId = directTradeService.dispatch(tradeId, new DirectTradeDtos.Dispatch(
                LocalDate.now(), "隔离物流", token + "-TRACK", "供应商业务员",
                List.of(new DirectTradeDtos.QuantityInput(tradeItemId, quantity("6")))));
        detail = directTradeService.detail(tradeId);
        Long deliveryItemId = detail.deliveries().get(0).items().get(0).id();
        directTradeService.accept(tradeId, deliveryId, new DirectTradeDtos.Accept(
                LocalDate.now(), "客户仓管", "供应商少发1kg，余量后续补发",
                List.of(new DirectTradeDtos.AcceptInput(deliveryItemId, quantity("5")))));

        detail = directTradeService.detail(tradeId);
        assertEquals("PARTIAL_DELIVERED", detail.header().status());
        assertMoney("100", detail.header().saleReceivableAmount());
        assertMoney("60", detail.header().purchasePayableAmount());
        assertMoney("40", detail.header().grossProfitAmount());
        assertMoney("100", decimal(
                "select receivable_amount from sales_order where id=?", detail.salesOrderId()));
        assertEquals("PARTIAL_SHIPPED", text(
                "select status from sales_order where id=?", detail.salesOrderId()));
        assertEquals("PARTIAL_RECEIVED", text(
                "select status from purchase_order where id=?", detail.purchaseOrderId()));

        copperService.createPayment(new PaymentCreateRequest(
                customerId, detail.salesOrderId(), null, null, money("80"), "CASH",
                null, LocalDate.now(), token + " 客户部分收款"));
        copperService.createSupplierPayment(new SupplierPaymentCreateRequest(
                supplierId, detail.purchaseOrderId(), null, money("48"), "CASH",
                null, LocalDate.now(), token + " 供应商部分付款"));

        Long returnId = directTradeService.createReturn(tradeId, new DirectTradeDtos.ReturnCreate(
                LocalDate.now(), "客户退回1kg并由供应商直接收回",
                List.of(new DirectTradeDtos.QuantityInput(tradeItemId, quantity("1")))));
        detail = directTradeService.detail(tradeId);
        assertMoney("80", detail.header().saleReceivableAmount());
        assertMoney("48", detail.header().purchasePayableAmount());
        assertThrows(BusinessException.class, () -> directTradeService.createReturn(
                tradeId, new DirectTradeDtos.ReturnCreate(
                        LocalDate.now(), "继续退回将低于已经收付款金额",
                        List.of(new DirectTradeDtos.QuantityInput(
                                tradeItemId, quantity("1"))))));

        directTradeService.reverseReturn(tradeId, returnId, "客户撤销退货并重新接受货物");
        detail = directTradeService.detail(tradeId);
        assertMoney("100", detail.header().saleReceivableAmount());
        assertMoney("60", detail.header().purchasePayableAmount());
        assertEquals("REVERSED", detail.returns().get(0).status());

        assertEquals(inventoryBatchBefore, count(
                "select count(*) from inventory_batch where product_id=?", productId));
        assertEquals(inventoryMovementBefore, count(
                "select count(*) from inventory_movement where product_id=?", productId));
        assertEquals(0L, count(
                "select count(*) from stock_balance where product_id=?", productId));
        assertEquals(1L, count("""
                select count(*) from business_posting_batch
                where source_type='DIRECT_TRADE' and source_id=? and posting_action='DELIVERY_POST'
                """, tradeId));
        assertEquals(1L, count("""
                select count(*) from business_posting_batch
                where source_type='DIRECT_TRADE' and source_id=? and posting_action='RETURN_POST'
                """, tradeId));
        assertEquals(1L, count("""
                select count(*) from business_posting_batch
                where source_type='DIRECT_TRADE' and source_id=? and posting_action='RETURN_REVERSE'
                """, tradeId));
        assertTrue(count(
                "select count(*) from direct_trade_event where direct_trade_id=?", tradeId) >= 6);
    }

    private void createFixture() {
        jdbc.update("""
                insert into partner(business_no,type,type_mask,name,status)
                values (?,'CUSTOMER',1,?,'ENABLED')
                """, numericToken(17), token + " 客户");
        customerId = id("select id from partner where name=?", token + " 客户");
        jdbc.update("insert into partner_customer_profile(partner_id,status) values (?,'ENABLED')",
                customerId);
        jdbc.update("""
                insert into partner_address
                (partner_id,name,address_type,detail,contact_person,phone,first_choice,status)
                values (?,'客户仓库','RECEIVING','隔离验收地址','客户仓管','',1,'ENABLED')
                """, customerId);
        addressId = id("select id from partner_address where partner_id=?", customerId);

        jdbc.update("""
                insert into partner(business_no,type,type_mask,name,status)
                values (?,'SUPPLIER',2,?,'ENABLED')
                """, numericToken(41), token + " 供应商");
        supplierId = id("select id from partner where name=?", token + " 供应商");
        jdbc.update("insert into partner_supplier_profile(partner_id,status) values (?,'ENABLED')",
                supplierId);

        jdbc.update("""
                insert into product(product_series_id,sku,name,spec,unit,default_price,status)
                values (2001,?,?,?,'kg',20,'ENABLED')
                """, token + "-SKU", token + " 紫铜管", "TP2 Φ22×1.0");
        productId = id("select id from product where sku=?", token + "-SKU");
    }

    private SecurityContext.Principal owner() {
        return new SecurityContext.Principal(
                1L, 1L, "直运验收", "DIRECT-TRADE", 1L, "本企业", token,
                false, List.of("系统管理员"), Set.of(
                "direct.trade.view", "direct.trade.manage", "direct.trade.confirm",
                "purchase.view", "purchase.manage", "sales.view", "sales.manage",
                "payment.view", "payment.manage", "supplier.payment.view",
                "supplier.payment.manage", "inventory.cost.view"));
    }

    private String numericToken(int salt) {
        return String.format("%06d", 100000 + Math.floorMod(token.hashCode() + salt, 899999));
    }
    private Long id(String sql, Object... args) {
        return Objects.requireNonNull(jdbc.queryForObject(sql, Long.class, args));
    }
    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0L : value;
    }
    private String text(String sql, Object... args) {
        return jdbc.queryForObject(sql, String.class, args);
    }
    private BigDecimal decimal(String sql, Object... args) {
        return jdbc.queryForObject(sql, BigDecimal.class, args);
    }
    private BigDecimal money(String value) { return new BigDecimal(value).setScale(2); }
    private BigDecimal price(String value) { return new BigDecimal(value).setScale(8); }
    private BigDecimal quantity(String value) { return new BigDecimal(value).setScale(6); }
    private void assertMoney(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }

    @AfterTransaction
    void rollbackRemovesFixture() {
        if (token == null) return;
        assertEquals(0L, count("select count(*) from partner where name like ?", token + "%"));
        assertEquals(0L, count("select count(*) from product where sku=?", token + "-SKU"));
        assertEquals(0L, count("select count(*) from direct_trade_order where memo like ?", token + "%"));
    }
}
