package com.soyia.copperv2.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.soyia.copperv2.dto.*;
import com.soyia.copperv2.security.SecurityContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.transaction.AfterTransaction;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIfEnvironmentVariable(named = "COPPER_PARTNER_SAFETY_E2E", matches = "true")
@Transactional
class PartnerPostingSafetyMySqlAcceptanceTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired CopperService copper;
    @Autowired PartnerFundAdjustmentService adjustments;
    @Autowired DirectTradeService direct;
    @Autowired TreasuryService treasury;
    String token;
    Long partnerId, supplierId, productId, addressId, warehouseId;
    final LocalDate date = LocalDate.of(2026, 7, 22);

    @BeforeEach void fixture() {
        token = "PSAFE" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        SecurityContext.set(owner());
        partnerId = partner("BOTH", 3, 11);
        supplierId = partner("SUPPLIER", 2, 29);
        jdbc.update("insert into partner_address(partner_id,name,address_type,detail,status) values (?,'仓库','RECEIVING','隔离测试','ENABLED')", partnerId);
        addressId = id("select id from partner_address where partner_id=?", partnerId);
        jdbc.update("insert into warehouse(name,location,status) values (?,'INTERNAL_REHEARSAL_ONLY','ENABLED')", token);
        warehouseId = id("select id from warehouse where name=?", token);
        jdbc.update("insert into product(product_series_id,sku,name,spec,unit,default_price,status) values (2001,?,?,'TP2 Φ22×1.0','kg',10,'ENABLED')", token, token);
        productId = id("select id from product where sku=?", token);
    }

    @AfterEach void clear() { SecurityContext.clear(); }

    @Test void pendingInboundCannotStockAfterSupplierRoleDisable() {
        Long inbound = inbound();
        copper.submitInbound(inbound, "验收");
        disable("ENABLED", "ENABLED", "DISABLED");
        assertThrows(BusinessException.class, () -> copper.auditInbound(inbound, true, "", "验收"));
        assertEquals("PENDING", text("select status from inbound_order where id=?", inbound));
        assertEquals(0L, count("select count(*) from inventory_batch where product_id=?", productId));
        // Rejection is a safe correction of the draft and remains usable after disable.
        copper.auditInbound(inbound, false, "供应商停用后拒绝本次入库", "验收");
        assertEquals("REJECTED", text("select status from inbound_order where id=?", inbound));
    }

    @Test void outboundDraftCannotShipAfterCustomerDisableAndSupplierRoleStillWorks() {
        Long inbound = inbound();
        copper.submitInbound(inbound, "验收"); copper.auditInbound(inbound, true, "", "验收");
        Long batch = id("select id from inventory_batch where product_id=?", productId);
        Long outbound = copper.createOutbound(new OutboundCreateRequest(
                null, partnerId, addressId, warehouseId, date, LocalDateTime.of(2026, 7, 22, 10, 0),
                "NO_ORDER", "NORMAL", "COMPLETE", token, false, "NONE", BigDecimal.ZERO, false, false,
                "", token, BigDecimal.ZERO, BigDecimal.ZERO, token,
                List.of(new OutboundCreateRequest.Item(null, batch, new BigDecimal("2"), "kg", null,
                        null, null, null, "", "kg", new BigDecimal("20"), List.of()))));
        disable("ENABLED", "DISABLED", "ENABLED");
        assertThrows(BusinessException.class, () -> copper.confirmOutbound(outbound));
        assertEquals("DRAFT", text("select status from outbound_order where id=?", outbound));
        assertEquals(0, new BigDecimal("10").compareTo(jdbc.queryForObject(
                "select quantity_available from inventory_batch where id=?", BigDecimal.class, batch)));
        copper.submitInbound(inbound(), "供应商身份仍可用");
        copper.cancelOutboundDraft(outbound, "客户身份停用，作废草稿");
    }

    @Test void balanceDraftRechecksMasterAndRoleMaskButConfirmedReplayAndReverseRemainUsable() {
        Long draft = adjustment();
        disable("DISABLED", "ENABLED", "ENABLED");
        assertThrows(BusinessException.class, () -> adjustments.confirm(draft));
        assertEquals(0L, count("select count(*) from partner_account_log where partner_id=?", partnerId));
        disable("ENABLED", "ENABLED", "ENABLED");
        // An enabled stale profile cannot override the master role set.
        jdbc.update("update partner set type_mask=2,type='SUPPLIER' where id=?", partnerId);
        assertThrows(BusinessException.class, () -> adjustments.confirm(draft));
        jdbc.update("update partner set type_mask=3,type='BOTH' where id=?", partnerId);
        adjustments.confirm(draft, new RiskExceptionDtos.InlineAuthorization("负责人核对原始依据后确认余额调整", null));
        disable("DISABLED", "ENABLED", "ENABLED");
        assertEquals(draft, adjustments.confirm(draft));
        adjustments.reverse(draft, new PartnerFundAdjustmentDtos.Action("停用后纠正历史余额", date));
        assertEquals("REVERSED", text("select status from partner_fund_adjustment where id=?", draft));
    }

    @Test void disabledMasterNeedsReasonAtBackendBoundary() {
        var request = request("DISABLED", "ENABLED", "ENABLED", "");
        assertThrows(BusinessException.class, () -> copper.updatePartner(partnerId, request));
        assertEquals("ENABLED", text("select status from partner where id=?", partnerId));
        assertThrows(BusinessException.class, () -> copper.createPartner(request));
    }

    @Test void directDeliveryRechecksBothPartnersBeforeDispatchAndAcceptance() {
        Long trade = direct.create(new DirectTradeDtos.Create(partnerId, supplierId, addressId,
                date, date.plusDays(1), token,
                List.of(new DirectTradeDtos.ItemCreate(productId, new BigDecimal("2"), "kg", null,
                        null, null, "kg", null, new BigDecimal("10"), new BigDecimal("20")))));
        direct.confirm(trade, new DirectTradeDtos.Confirm(null, null));
        disable("ENABLED", "DISABLED", "ENABLED");
        assertThrows(BusinessException.class, () -> direct.dispatch(trade,
                new DirectTradeDtos.Dispatch(date, "", "", "", List.of())));
        disable("ENABLED", "ENABLED", "ENABLED");
        Long delivery = direct.dispatch(trade, new DirectTradeDtos.Dispatch(date, "", "", "", List.of()));
        jdbc.update("update partner_supplier_profile set status='DISABLED',disabled_reason='停止合作' where partner_id=?", supplierId);
        assertThrows(BusinessException.class, () -> direct.accept(trade, delivery,
                new DirectTradeDtos.Accept(date, "仓管", "", List.of())));
        assertEquals(0L, count("select count(*) from receivable_movement where customer_id=?", partnerId));
        assertEquals("DISPATCHED", text("select status from direct_trade_delivery where id=?", delivery));
    }

    @Test void treasuryDraftAndActualSettlementRejectDisabledSupplier() {
        Long expense = id("select id from accounting_account where account_code='5602' order by id limit 1");
        Long document = treasury.createDocument(new TreasuryDtos.DocumentCreate("CORPORATE_EXPENSE", partnerId,
                date, date.plusDays(1), token, "隔离费用", expense, false, BigDecimal.ZERO,
                new BigDecimal("10"), token));
        disable("ENABLED", "ENABLED", "DISABLED");
        assertThrows(BusinessException.class, () -> treasury.confirmDocument(document));
        disable("ENABLED", "ENABLED", "ENABLED");
        treasury.confirmDocument(document);
        disable("ENABLED", "ENABLED", "DISABLED");
        assertEquals(document, treasury.confirmDocument(document));
        assertThrows(BusinessException.class, () -> treasury.settleDocument(document,
                new TreasuryDtos.SettlementCreate(null, BigDecimal.ONE, date, "CASH", null,
                        "OTHER_OPERATING_OUT", "隔离付款")));
        assertEquals(0L, count("select count(*) from treasury_fund_transaction where document_id=?", document));
    }

    private Long inbound() {
        return copper.createInbound(new InboundCreateRequest(null, partnerId, warehouseId, "NO_ORDER", token,
                date, date, token + UUID.randomUUID(), BigDecimal.ZERO, BigDecimal.ZERO, token,
                List.of(new InboundCreateRequest.Item(productId, null, token + UUID.randomUUID(), "",
                        new BigDecimal("10"), "kg", null, null, null, null, "kg", "", "",
                        new BigDecimal("10"), List.of(), date))));
    }
    private Long adjustment() {
        return adjustments.create(new PartnerFundAdjustmentDtos.Create(partnerId, "CUSTOMER_ADVANCE",
                "BALANCE_CORRECTION", "INCREASE", new BigDecimal("10"), date, token,
                "NOT_APPLICABLE", "隔离余额修正"));
    }
    private Long partner(String type, int mask, int salt) {
        String name = token + salt;
        jdbc.update("insert into partner(business_no,type,type_mask,entity_type,name,status) values (?,?,?,'INDIVIDUAL',?,'ENABLED')",
                String.format("%06d", 100000 + Math.floorMod(token.hashCode() + salt, 899999)), type, mask, name);
        Long id = id("select id from partner where name=?", name);
        if ((mask & 1) != 0) jdbc.update("insert into partner_customer_profile(partner_id,status) values (?,'ENABLED')", id);
        if ((mask & 2) != 0) jdbc.update("insert into partner_supplier_profile(partner_id,status) values (?,'ENABLED')", id);
        return id;
    }
    private void disable(String master, String customer, String supplier) {
        copper.updatePartner(partnerId, request(master, customer, supplier, "停止合作"));
    }
    private SimpleCreateRequests.PartnerCreate request(String master, String customer, String supplier, String reason) {
        Map<String, Object> data = new HashMap<>();
        data.put("typeMask", 3); data.put("type", "BOTH"); data.put("entityType", "INDIVIDUAL");
        data.put("name", token + 11); data.put("creditLimit", BigDecimal.ZERO); data.put("status", master);
        data.put("disabledReason", reason); data.put("customerStatus", customer); data.put("supplierStatus", supplier);
        data.put("customerDisabledReason", reason); data.put("supplierDisabledReason", reason);
        return mapper.convertValue(data, SimpleCreateRequests.PartnerCreate.class);
    }
    private SecurityContext.Principal owner() {
        return new SecurityContext.Principal(1L, 1L, "安全验收", "PSAFE", 0L, "验收", token,
                false, List.of("系统管理员"), Set.of("inventory.cost.view", "partner.fund.adjust.manage",
                "partner.fund.adjust.approve", "partner.fund.adjust.reverse", "risk.exception.approve", "accounting.manage"));
    }
    private Long id(String sql, Object... args) { return Objects.requireNonNull(jdbc.queryForObject(sql, Long.class, args)); }
    private long count(String sql, Object... args) { return Objects.requireNonNull(jdbc.queryForObject(sql, Long.class, args)); }
    private String text(String sql, Object... args) { return jdbc.queryForObject(sql, String.class, args); }
    @AfterTransaction void rollbackVerified() {
        if (token == null) return;
        assertEquals(0L, count("select count(*) from partner where name like ?", token + "%"));
        assertEquals(0L, count("select count(*) from product where sku=?", token));
        assertEquals(0L, count("select count(*) from warehouse where name=?", token));
    }
}
