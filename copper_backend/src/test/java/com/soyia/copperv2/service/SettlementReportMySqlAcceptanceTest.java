package com.soyia.copperv2.service;

import com.soyia.copperv2.dto.SettlementReportDtos;
import com.soyia.copperv2.security.SecurityContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.transaction.AfterTransaction;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIfEnvironmentVariable(named = "COPPER_ERP03_E2E", matches = "true")
@Transactional
class SettlementReportMySqlAcceptanceTest {
    private static final int ROW_COUNT = 1001;
    private static final BigDecimal GROSS = new BigDecimal("100.00");
    private static final BigDecimal REDUCTION = new BigDecimal("10.00");

    @Autowired private JdbcTemplate jdbc;
    @Autowired private SettlementReportService service;

    private String token;
    private Long partnerId;
    private LocalDate asOfDate;

    @BeforeEach
    void setUp() {
        token = "ERP03" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        SecurityContext.set(new SecurityContext.Principal(
                1L, 1L, "ERP-03验收", "ERP-03", 0L, "验收", token,
                false, List.of("系统管理员"), Set.of("finance.view")));
        asOfDate = jdbc.queryForObject("select current_date", (rs, rowNum) -> rs.getDate(1).toLocalDate());
        String businessNo = String.valueOf(400000 + Math.floorMod(token.hashCode(), 500000));
        jdbc.update("""
                insert into partner
                (business_no,type,type_mask,name,contact_person,phone,credit_code,status,memo)
                values (?,'CUSTOMER',3,?,'验收人员','00000000000',?,'ENABLED',?)
                """, businessNo, token + " 往来单位", token,
                "INTERNAL_REHEARSAL_ONLY ERP-03 超千条全量汇总验收 " + token);
        partnerId = jdbc.queryForObject("select id from partner where name=?", Long.class, token + " 往来单位");
        jdbc.update("insert into partner_customer_profile(partner_id,status,settlement_days) values (?,'ENABLED',30)", partnerId);
        jdbc.update("insert into partner_supplier_profile(partner_id,status,settlement_days) values (?,'ENABLED',30)", partnerId);
        insertReceivables();
        insertPayables();
        insertPurchaseReturns();
        jdbc.update("update partner_customer_profile set settlement_days=365 where partner_id=?", partnerId);
        jdbc.update("update partner_supplier_profile set settlement_days=365 where partner_id=?", partnerId);
    }

    @AfterEach
    void clearContext() { SecurityContext.clear(); }

    @Test
    void moreThanOneThousandMixedRowsKeepSummaryAgingAndPaginationExact() {
        Expected expected = expected();

        SettlementReportDtos.Page receivableFirst = service.query("RECEIVABLE", partnerId, token, null,
                "all", null, null, 1, 50);
        assertPageAndSummary(receivableFirst, expected, "RECEIVABLE", 50);
        SettlementReportDtos.Page receivableLast = service.query("RECEIVABLE", partnerId, token, null,
                "all", null, null, 21, 50);
        assertEquals(1, receivableLast.items().size());
        assertMoney(expected.unpaid(), agingTotal(receivableLast));

        SettlementReportDtos.Page payableFirst = service.query("PAYABLE", partnerId, token, null,
                "all", null, null, 1, 100);
        assertPageAndSummary(payableFirst, expected, "PAYABLE", 100);
        SettlementReportDtos.Page payableLast = service.query("PAYABLE", partnerId, token, null,
                "all", null, null, 6, 200);
        assertEquals(1, payableLast.items().size());
        assertMoney(expected.unpaid(), agingTotal(payableLast));

        SettlementReportDtos.Page overdue = service.query("RECEIVABLE", partnerId, null, null,
                "overdue", null, null, 1, 20);
        assertEquals(expected.overdueCount(), overdue.totalRows());
        assertMoney(expected.overdueAmount(), overdue.summary().unpaidAmount());
        assertMoney(expected.overdueAmount(), overdue.summary().overdueAmount());
        assertTrue(overdue.items().stream().allMatch(row -> row.overdueDays() > 0 && row.unpaidAmount().signum() > 0));

        SettlementReportDtos.Page dueToday = service.query("PAYABLE", partnerId, null, null,
                "due-today", asOfDate, asOfDate, 1, 200);
        assertEquals(expected.dueTodayCount(), dueToday.totalRows());
        assertMoney(expected.dueTodayAmount(), dueToday.summary().unpaidAmount());
        assertTrue(dueToday.items().stream().allMatch(row -> row.dueDate().equals(asOfDate)));
    }

    private void assertPageAndSummary(SettlementReportDtos.Page page, Expected expected, String direction, int pageSize) {
        assertEquals(direction, page.direction());
        assertEquals(asOfDate, page.asOfDate());
        assertNotNull(page.snapshotAt());
        assertTrue(page.completeScope());
        assertEquals(ROW_COUNT, page.totalRows());
        assertEquals((ROW_COUNT + pageSize - 1) / pageSize, page.totalPages());
        assertEquals(pageSize, page.items().size());
        assertEquals(ROW_COUNT, page.summary().documentCount());
        assertEquals(1, page.summary().partnerCount());
        assertMoney(expected.gross(), page.summary().grossAmount());
        assertMoney(expected.reduction(), page.summary().reductionAmount());
        assertMoney(expected.net(), page.summary().totalAmount());
        assertMoney(expected.paid(), page.summary().paidAmount());
        assertMoney(expected.unpaid(), page.summary().unpaidAmount());
        assertEquals(expected.dueTodayCount(), page.summary().dueTodayCount());
        assertMoney(expected.dueTodayAmount(), page.summary().dueTodayAmount());
        assertEquals(expected.overdueCount(), page.summary().overdueCount());
        assertMoney(expected.overdueAmount(), page.summary().overdueAmount());
        assertEquals(6, page.aging().size());
        assertMoney(expected.unpaid(), agingTotal(page));
    }

    private void insertReceivables() {
        jdbc.batchUpdate("""
                insert into sales_order
                (order_no,title,customer_id,business_date,settlement_days_snapshot,due_date,total_amount,receivable_amount,received_amount,
                 adjusted_amount,payment_status,status,memo)
                values (?,?,?,?,30,?,?,?,?,?,?,?,?)
                """, new BatchPreparedStatementSetter() {
            @Override public void setValues(PreparedStatement ps, int i) throws SQLException {
                BigDecimal reduction = reduction(i);
                BigDecimal net = GROSS.subtract(reduction);
                BigDecimal paid = paid(i).min(net);
                ps.setString(1, token + "-SO-" + String.format("%04d", i));
                ps.setString(2, "ERP-03 超千条应收验收");
                ps.setLong(3, partnerId);
                ps.setDate(4, Date.valueOf(businessDate(i)));
                ps.setDate(5, Date.valueOf(businessDate(i).plusDays(30)));
                ps.setBigDecimal(6, GROSS);
                ps.setBigDecimal(7, GROSS);
                ps.setBigDecimal(8, paid);
                ps.setBigDecimal(9, reduction);
                ps.setString(10, paymentStatus(paid, net));
                ps.setString(11, "CONFIRMED");
                ps.setString(12, "INTERNAL_REHEARSAL_ONLY " + token);
            }
            @Override public int getBatchSize() { return ROW_COUNT; }
        });
    }

    private void insertPayables() {
        jdbc.batchUpdate("""
                insert into purchase_order
                (order_no,supplier_id,business_date,settlement_days_snapshot,due_date,total_amount,paid_amount,payment_status,status,memo)
                values (?,?,?,30,?,?,?,?,?,?)
                """, new BatchPreparedStatementSetter() {
            @Override public void setValues(PreparedStatement ps, int i) throws SQLException {
                BigDecimal net = GROSS.subtract(reduction(i));
                BigDecimal paid = paid(i).min(net);
                ps.setString(1, token + "-PO-" + String.format("%04d", i));
                ps.setLong(2, partnerId);
                ps.setDate(3, Date.valueOf(businessDate(i)));
                ps.setDate(4, Date.valueOf(businessDate(i).plusDays(30)));
                ps.setBigDecimal(5, GROSS);
                ps.setBigDecimal(6, paid);
                ps.setString(7, paymentStatus(paid, net));
                ps.setString(8, "ORDERED");
                ps.setString(9, "INTERNAL_REHEARSAL_ONLY " + token);
            }
            @Override public int getBatchSize() { return ROW_COUNT; }
        });
    }

    private void insertPurchaseReturns() {
        List<Long> orderIds = jdbc.queryForList("select id from purchase_order where order_no like ? order by order_no", Long.class, token + "-PO-%");
        List<Long> reducedOrderIds = new java.util.ArrayList<>();
        for (int i = 0; i < orderIds.size(); i++) if (i % 10 == 0) reducedOrderIds.add(orderIds.get(i));
        jdbc.batchUpdate("""
                insert into purchase_return_order
                (order_no,inbound_id,purchase_order_id,supplier_id,warehouse_id,business_date,status,
                 total_quantity,total_amount,payable_reduction_amount,reason,memo)
                values (?,0,?,?,0,?,'CONFIRMED',1,10,10,?,?)
                """, new BatchPreparedStatementSetter() {
            @Override public void setValues(PreparedStatement ps, int i) throws SQLException {
                ps.setString(1, token + "-PR-" + String.format("%04d", i));
                ps.setLong(2, reducedOrderIds.get(i));
                ps.setLong(3, partnerId);
                ps.setDate(4, Date.valueOf(asOfDate));
                ps.setString(5, "ERP-03 退货冲减验收");
                ps.setString(6, "INTERNAL_REHEARSAL_ONLY " + token);
            }
            @Override public int getBatchSize() { return reducedOrderIds.size(); }
        });
    }

    private Expected expected() {
        BigDecimal gross = BigDecimal.ZERO;
        BigDecimal reduction = BigDecimal.ZERO;
        BigDecimal net = BigDecimal.ZERO;
        BigDecimal paid = BigDecimal.ZERO;
        BigDecimal unpaid = BigDecimal.ZERO;
        long dueTodayCount = 0;
        BigDecimal dueTodayAmount = BigDecimal.ZERO;
        long overdueCount = 0;
        BigDecimal overdueAmount = BigDecimal.ZERO;
        for (int i = 0; i < ROW_COUNT; i++) {
            BigDecimal rowReduction = reduction(i);
            BigDecimal rowNet = GROSS.subtract(rowReduction);
            BigDecimal rowPaid = paid(i).min(rowNet);
            BigDecimal rowUnpaid = rowNet.subtract(rowPaid).max(BigDecimal.ZERO);
            gross = gross.add(GROSS);
            reduction = reduction.add(rowReduction);
            net = net.add(rowNet);
            paid = paid.add(rowPaid);
            unpaid = unpaid.add(rowUnpaid);
            LocalDate dueDate = businessDate(i).plusDays(30);
            if (rowUnpaid.signum() > 0 && dueDate.equals(asOfDate)) {
                dueTodayCount++;
                dueTodayAmount = dueTodayAmount.add(rowUnpaid);
            }
            if (rowUnpaid.signum() > 0 && dueDate.isBefore(asOfDate)) {
                overdueCount++;
                overdueAmount = overdueAmount.add(rowUnpaid);
            }
        }
        return new Expected(gross, reduction, net, paid, unpaid,
                dueTodayCount, dueTodayAmount, overdueCount, overdueAmount);
    }

    private LocalDate businessDate(int index) {
        return switch (index % 6) {
            case 0 -> asOfDate;
            case 1 -> asOfDate.minusDays(30);
            case 2 -> asOfDate.minusDays(40);
            case 3 -> asOfDate.minusDays(75);
            case 4 -> asOfDate.minusDays(105);
            default -> asOfDate.minusDays(150);
        };
    }

    private BigDecimal reduction(int index) { return index % 10 == 0 ? REDUCTION : BigDecimal.ZERO; }
    private BigDecimal paid(int index) {
        if (index % 4 == 0) return new BigDecimal("20.00");
        if (index % 4 == 1) return GROSS;
        return BigDecimal.ZERO;
    }
    private String paymentStatus(BigDecimal paid, BigDecimal net) {
        if (paid.compareTo(net) >= 0) return "PAID";
        return paid.signum() > 0 ? "PARTIAL" : "UNPAID";
    }
    private BigDecimal agingTotal(SettlementReportDtos.Page page) {
        return page.aging().stream().map(SettlementReportDtos.AgingBucket::outstandingAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
    private void assertMoney(BigDecimal expected, BigDecimal actual) { assertEquals(0, expected.compareTo(actual)); }

    private record Expected(BigDecimal gross, BigDecimal reduction, BigDecimal net, BigDecimal paid,
                            BigDecimal unpaid, long dueTodayCount, BigDecimal dueTodayAmount,
                            long overdueCount, BigDecimal overdueAmount) { }

    @AfterTransaction
    void rollbackRemovesFixture() {
        if (token == null) return;
        Long count = jdbc.queryForObject("select count(*) from partner where name=?", Long.class, token + " 往来单位");
        assertEquals(0L, count == null ? 0L : count);
    }
}
