package com.soyia.copperv2.service;

import com.soyia.copperv2.dto.SalesRiskDtos;
import com.soyia.copperv2.security.ForbiddenException;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIfEnvironmentVariable(named = "COPPER_ERP07_E2E", matches = "true")
@Transactional
class Erp07SalesRiskMySqlAcceptanceTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private SalesRiskService service;

    private String token;
    private Long partnerId;
    private Long productId;
    private Long warehouseId;
    private Long batchId;
    private Long salesOrderId;
    private Long outboundId;

    @BeforeEach
    void setUp() {
        token = "ERP07" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        SecurityContext.set(owner());
        createFixture();
    }

    @AfterEach
    void clearContext() {
        SecurityContext.clear();
    }

    @Test
    @DisplayName("信用敞口不重复抵减承兑，逾期和最低毛利必须由老板逐单授权并保存快照")
    void ownerOverrideFreezesExposureMarginAndReason() {
        SalesRiskDtos.Policy policy = service.updatePolicy(partnerId, new SalesRiskDtos.PolicySave(
                true, true, money("500"), true, 3, true, rate("60"),
                "老板设定首轮信用与最低毛利控制"));
        assertEquals(1, policy.versionNo());

        SalesRiskDtos.Evaluation preview = service.previewSalesOrder(salesOrderId);
        assertTrue(preview.blocked());
        assertEquals(Set.of("CREDIT_LIMIT_EXCEEDED", "OVERDUE_RECEIVABLE", "BELOW_MINIMUM_MARGIN"),
                preview.issues().stream().map(SalesRiskDtos.Issue::code).collect(java.util.stream.Collectors.toSet()));
        assertMoney("100.00", preview.exposure().receivableAmount());
        assertMoney("300.00", preview.exposure().confirmedUnshippedAmount());
        assertMoney("200.00", preview.exposure().currentDocumentAmount());
        assertMoney("50.00", preview.exposure().advanceCreditAmount());
        assertMoney("40.00", preview.exposure().acceptanceSecuredAmount());
        assertMoney("550.00", preview.exposure().netExposureAmount());
        assertMoney("50.00", preview.exposure().overLimitAmount());
        assertEquals(10, preview.exposure().maximumOverdueDays());
        assertMoney("200.00", preview.margin().revenueExcludingTax());
        assertMoney("100.00", preview.margin().estimatedCostAmount());
        assertEquals(0, rate("50").compareTo(preview.margin().estimatedMarginRate()));

        BusinessException noReason = assertThrows(BusinessException.class,
                () -> service.enforceSalesOrder(salesOrderId, "CONFIRM", 1, null));
        assertTrue(noReason.getMessage().contains("销售风险控制未通过"));
        assertEquals(0L, count("select count(*) from sales_risk_snapshot where document_id=?", salesOrderId));

        SecurityContext.set(salesperson());
        assertThrows(ForbiddenException.class,
                () -> service.enforceSalesOrder(salesOrderId, "CONFIRM", 1, "销售申请先发货后收款特批"));
        assertEquals(0L, count("select count(*) from sales_risk_snapshot where document_id=?", salesOrderId));

        SecurityContext.set(owner());
        SalesRiskDtos.Evaluation authorized = service.enforceSalesOrder(
                salesOrderId, "CONFIRM", 1, "客户已提供当日银行回单，老板同意本单先确认");
        assertEquals("OVERRIDDEN", authorized.decision());
        assertEquals("DRAFT", text("select status from sales_order where id=?", salesOrderId));
        List<SalesRiskDtos.Snapshot> history = service.salesOrderHistory(salesOrderId);
        assertEquals(1, history.size());
        assertEquals("OVERRIDDEN", history.get(0).decision());
        assertEquals("客户已提供当日银行回单，老板同意本单先确认", history.get(0).overrideReason());
        assertMoney("550.00", history.get(0).exposure().netExposureAmount());
        assertEquals(0, rate("50").compareTo(history.get(0).margin().estimatedMarginRate()));

        SalesRiskDtos.Evaluation outboundPreview = service.previewOutbound(outboundId);
        assertTrue(outboundPreview.blocked());
        assertTrue(outboundPreview.issues().stream().anyMatch(item -> "BELOW_MINIMUM_MARGIN".equals(item.code())));
        assertMoney("100.00", outboundPreview.margin().estimatedCostAmount());
        assertMoney("-20.00", outboundPreview.margin().estimatedGrossProfit());
        service.enforceOutbound(outboundId, "老板同意清理尾货，本次亏损发货已核实");
        assertEquals(1L, count("select count(*) from sales_risk_snapshot where document_type='OUTBOUND' and document_id=? and decision='OVERRIDDEN'", outboundId));
    }

    @Test
    @DisplayName("未启用策略的历史客户只监测不阻断且不产生授权快照")
    void disabledPolicyDoesNotRetroactivelyBlockLegacyCustomer() {
        SalesRiskDtos.Evaluation preview = service.previewSalesOrder(salesOrderId);
        assertFalse(preview.policyEnabled());
        assertEquals("NOT_CONTROLLED", preview.decision());
        service.enforceSalesOrder(salesOrderId, "CONFIRM", 1, null);
        assertEquals(0L, count("select count(*) from sales_risk_snapshot where document_id=?", salesOrderId));
    }

    private void createFixture() {
        String businessNo = String.valueOf(700000 + Math.floorMod(token.hashCode(), 199999));
        jdbc.update("""
                insert into partner(business_no,type,type_mask,name,status,memo)
                values (?,'CUSTOMER',1,?,'ENABLED','INTERNAL_REHEARSAL_ONLY')
                """, businessNo, token + " 客户");
        partnerId = id("select id from partner where name=?", token + " 客户");
        jdbc.update("""
                insert into partner_customer_profile
                (partner_id,status,settlement_method,settlement_days,credit_limit)
                values (?,'ENABLED','CREDIT_TERM',30,0)
                """, partnerId);
        jdbc.update("insert into partner_account(partner_id,account_type,balance) values (?,'CUSTOMER_ADVANCE',50)", partnerId);

        jdbc.update("insert into product(sku,name,unit,status) values (?,?,'kg','ENABLED')",
                token + "-SKU", token + " 紫铜管");
        productId = id("select id from product where sku=?", token + "-SKU");
        jdbc.update("insert into warehouse(name,location,status) values (?,?,'ENABLED')",
                token + " 仓库", "INTERNAL_REHEARSAL_ONLY");
        warehouseId = id("select id from warehouse where name=?", token + " 仓库");
        jdbc.update("insert into stock_balance(product_id,warehouse_id,quantity_available,amount_cost) values (?,?,10,1000)", productId, warehouseId);
        jdbc.update("""
                insert into inventory_batch
                (product_id,warehouse_id,batch_no,source_type,source_id,base_unit,quantity_in,quantity_available,
                 unit_cost,amount_cost,status,received_at)
                values (?,?,?,'OPENING',0,'kg',10,10,100,1000,'AVAILABLE',current_date)
                """, productId, warehouseId, token + "-BATCH");
        batchId = id("select id from inventory_batch where batch_no=?", token + "-BATCH");

        jdbc.update("""
                insert into sales_order
                (order_no,title,customer_id,business_date,settlement_days_snapshot,due_date,
                 amount_excluding_tax,total_amount,receivable_amount,status,current_revision_no)
                values (?,?,?,current_date-interval 10 day,0,current_date-interval 10 day,400,400,100,'OPEN',1)
                """, token + "-OLD", token + " 历史订单", partnerId);
        Long oldOrderId = id("select id from sales_order where order_no=?", token + "-OLD");
        jdbc.update("""
                insert into acceptance_bill
                (bill_no,instrument_no,bill_type,customer_id,sales_order_id,face_amount,issue_date,due_date,
                 received_date,acceptor,settlement_payment_id,status)
                values (?,?,'BANK_ACCEPTANCE',?,?,40,current_date,current_date+interval 30 day,current_date,?,1,'HELD')
                """, token + "-BILL", token + "-INSTRUMENT", partnerId, oldOrderId, token + " 银行");

        jdbc.update("""
                insert into sales_order
                (order_no,title,customer_id,business_date,settlement_days_snapshot,due_date,
                 amount_excluding_tax,total_amount,status,current_revision_no)
                values (?,?,?,current_date,30,current_date+interval 30 day,200,200,'DRAFT',1)
                """, token + "-SO", token + " 目标订单", partnerId);
        salesOrderId = id("select id from sales_order where order_no=?", token + "-SO");
        jdbc.update("""
                insert into sales_order_item
                (sales_order_id,product_id,product_sku_snapshot,product_name_snapshot,requested_quantity,requested_unit,
                 quantity,base_unit,price_unit,pricing_quantity,packaging_plan_json,no_charge,unit_price,amount,
                 amount_excluding_tax,settlement_total_amount,status)
                values (?,?,?,?,1,'kg',1,'kg','kg',1,'{}',0,200,200,200,200,'ENABLED')
                """, salesOrderId, productId, token + "-SKU", token + " 紫铜管");

        jdbc.update("""
                insert into outbound_order
                (order_no,sales_order_id,source_type,source_channel,entry_mode,completeness_status,settlement_status,
                 customer_id,warehouse_id,business_date,settlement_days_snapshot,due_date,status,total_quantity,
                 original_amount,amount_excluding_tax,total_amount,shipment_control_amount)
                values (?,0,'DIRECT','PHONE','NORMAL','COMPLETE','READY',?,?,current_date,30,current_date+interval 30 day,
                        'DRAFT',1,80,80,80,80)
                """, token + "-OUT", partnerId, warehouseId);
        outboundId = id("select id from outbound_order where order_no=?", token + "-OUT");
        jdbc.update("""
                insert into outbound_item
                (outbound_id,product_id,product_sku_snapshot,product_name_snapshot,batch_id,quantity,base_unit,
                 packaging_plan_json,price_unit,pricing_quantity,unit_price,amount,amount_excluding_tax,settlement_total_amount)
                values (?,?,?,?,?,1,'kg','{}','kg',1,80,80,80,80)
                """, outboundId, productId, token + "-SKU", token + " 紫铜管", batchId);
    }

    private SecurityContext.Principal owner() {
        return new SecurityContext.Principal(1L, 1L, "ERP-07老板验收", "ERP-07-OWNER", 1L, "本企业", token,
                false, List.of("老板"), Set.of("sales.view", "outbound.view", "sales.gross-profit.view",
                "sales.risk.policy.manage", "sales.risk.override"));
    }

    private SecurityContext.Principal salesperson() {
        return new SecurityContext.Principal(2L, 2L, "ERP-07销售验收", "ERP-07-SALES", 1L, "销售", token + "-sales",
                false, List.of("销售"), Set.of("sales.view", "sales.confirm", "outbound.view", "outbound.manage"));
    }

    private BigDecimal money(String value) { return new BigDecimal(value).setScale(2); }
    private BigDecimal rate(String value) { return new BigDecimal(value).setScale(4); }
    private Long id(String sql, Object... args) { return Objects.requireNonNull(jdbc.queryForObject(sql, Long.class, args)); }
    private String text(String sql, Object... args) { return Objects.requireNonNull(jdbc.queryForObject(sql, String.class, args)); }
    private long count(String sql, Object... args) { Long value = jdbc.queryForObject(sql, Long.class, args); return value == null ? 0 : value; }
    private void assertMoney(String expected, BigDecimal actual) { assertEquals(0, new BigDecimal(expected).compareTo(actual)); }

    @AfterTransaction
    void rollbackRemovesFixture() {
        if (token == null) return;
        assertEquals(0L, count("select count(*) from partner where name=?", token + " 客户"));
        assertEquals(0L, count("select count(*) from product where sku=?", token + "-SKU"));
        assertEquals(0L, count("select count(*) from warehouse where name=?", token + " 仓库"));
    }
}
