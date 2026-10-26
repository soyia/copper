package com.soyia.copperv2.service;

import com.soyia.copperv2.dto.AtpDtos;
import com.soyia.copperv2.security.BusinessDataScopeService;
import com.soyia.copperv2.security.SecurityContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class AtpService {
    public static final String CALCULATION_VERSION = "TIME_PHASED_ATP_V1";
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final DateTimeFormatter NO_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");
    private static final AtomicInteger NO_COUNTER = new AtomicInteger();

    private final JdbcTemplate jdbc;
    private final BusinessDataScopeService dataScopeService;

    public AtpService(JdbcTemplate jdbc, BusinessDataScopeService dataScopeService) {
        this.jdbc = jdbc;
        this.dataScopeService = dataScopeService;
    }

    public List<AtpDtos.Summary> summaries(Long productId, Long warehouseId, LocalDate requestedDate) {
        LocalDate asOfDate = normalizedDate(requestedDate);
        List<AtpDtos.Summary> rows = jdbc.query("""
                with
                on_hand as (
                  select product_id,warehouse_id,sum(quantity_available) quantity
                  from inventory_batch group by product_id,warehouse_id
                ),
                draft_by_item as (
                  select oi.sales_order_item_id,a.product_id,o.warehouse_id,sum(a.quantity) quantity
                  from outbound_allocation a
                  join outbound_order o on o.id=a.outbound_id and o.status='DRAFT'
                  join outbound_item oi on oi.id=a.outbound_item_id
                  where a.status='DRAFT'
                  group by oi.sales_order_item_id,a.product_id,o.warehouse_id
                ),
                draft as (
                  select product_id,warehouse_id,sum(quantity) quantity
                  from draft_by_item group by product_id,warehouse_id
                ),
                sales_base as (
                  select i.id sales_order_item_id,i.product_id,o.fulfillment_warehouse_id warehouse_id,
                         greatest(i.quantity-i.shipped_quantity,0) quantity,
                         least(greatest(i.quantity-i.shipped_quantity,0),coalesce(d.quantity,0)) covered_quantity
                  from sales_order_item i
                  join sales_order o on o.id=i.sales_order_id
                  left join draft_by_item d on d.sales_order_item_id=i.id
                    and d.warehouse_id=o.fulfillment_warehouse_id
                  where i.status='ENABLED' and o.status in ('OPEN','PARTIAL_SHIPPED')
                    and o.fulfillment_warehouse_id>0
                    and coalesce(o.delivery_plan_date,o.business_date)<=?
                ),
                sales as (
                  select product_id,warehouse_id,sum(quantity) quantity,sum(covered_quantity) covered_quantity
                  from sales_base group by product_id,warehouse_id
                ),
                purchase_incoming as (
                  select i.product_id,o.expected_warehouse_id warehouse_id,
                         sum(greatest(i.quantity-i.received_quantity,0)) quantity
                  from purchase_order_item i join purchase_order o on o.id=i.purchase_order_id
                  where i.status='ENABLED' and o.status in ('ORDERED','PARTIAL_RECEIVED')
                    and o.expected_warehouse_id>0 and o.expected_date<=?
                  group by i.product_id,o.expected_warehouse_id
                ),
                transfer_incoming as (
                  select i.product_id,o.to_warehouse_id warehouse_id,sum(i.quantity) quantity
                  from inventory_transfer_item i join inventory_transfer_order o on o.id=i.transfer_id
                  where o.status='IN_TRANSIT' and o.expected_arrival_date<=?
                  group by i.product_id,o.to_warehouse_id
                ),
                unallocated_sales as (
                  select i.product_id,sum(greatest(i.quantity-i.shipped_quantity,0)) quantity
                  from sales_order_item i join sales_order o on o.id=i.sales_order_id
                  where i.status='ENABLED' and o.status in ('OPEN','PARTIAL_SHIPPED')
                    and o.fulfillment_warehouse_id=0 group by i.product_id
                ),
                unallocated_purchase as (
                  select i.product_id,sum(greatest(i.quantity-i.received_quantity,0)) quantity
                  from purchase_order_item i join purchase_order o on o.id=i.purchase_order_id
                  where i.status='ENABLED' and o.status in ('ORDERED','PARTIAL_RECEIVED')
                    and (o.expected_warehouse_id=0 or o.expected_date is null) group by i.product_id
                ),
                keys_scope as (
                  select product_id,warehouse_id from on_hand
                  union select product_id,warehouse_id from draft
                  union select product_id,warehouse_id from sales
                  union select product_id,warehouse_id from purchase_incoming
                  union select product_id,warehouse_id from transfer_incoming
                  union select u.product_id,w.id from unallocated_sales u join warehouse w on w.status='ENABLED'
                  union select u.product_id,w.id from unallocated_purchase u join warehouse w on w.status='ENABLED'
                )
                select k.product_id,p.sku,p.name product_name,p.unit base_unit,
                       k.warehouse_id,w.name warehouse_name,
                       coalesce(h.quantity,0) on_hand_quantity,
                       coalesce(d.quantity,0) outbound_draft_quantity,
                       coalesce(s.quantity,0) sales_commitment_quantity,
                       coalesce(s.covered_quantity,0) linked_draft_covered_quantity,
                       coalesce(pi.quantity,0) purchase_incoming_quantity,
                       coalesce(ti.quantity,0) transfer_incoming_quantity,
                       coalesce(h.quantity,0)-coalesce(d.quantity,0)-
                         greatest(coalesce(s.quantity,0)-coalesce(s.covered_quantity,0),0)+
                         coalesce(pi.quantity,0)+coalesce(ti.quantity,0) atp_quantity,
                       coalesce(us.quantity,0) unallocated_sales_quantity,
                       coalesce(up.quantity,0) unallocated_purchase_quantity
                from keys_scope k join product p on p.id=k.product_id join warehouse w on w.id=k.warehouse_id
                left join on_hand h on h.product_id=k.product_id and h.warehouse_id=k.warehouse_id
                left join draft d on d.product_id=k.product_id and d.warehouse_id=k.warehouse_id
                left join sales s on s.product_id=k.product_id and s.warehouse_id=k.warehouse_id
                left join purchase_incoming pi on pi.product_id=k.product_id and pi.warehouse_id=k.warehouse_id
                left join transfer_incoming ti on ti.product_id=k.product_id and ti.warehouse_id=k.warehouse_id
                left join unallocated_sales us on us.product_id=k.product_id
                left join unallocated_purchase up on up.product_id=k.product_id
                where (?=0 or k.product_id=?) and (?=0 or k.warehouse_id=?)
                order by p.name,w.name
                """, (rs, rowNum) -> {
            BigDecimal atp = rs.getBigDecimal("atp_quantity");
            BigDecimal unallocated = rs.getBigDecimal("unallocated_sales_quantity");
            BigDecimal unscheduledSupply = rs.getBigDecimal("unallocated_purchase_quantity");
            String status = unallocated.compareTo(ZERO) > 0 ? "UNALLOCATED_DEMAND"
                    : atp.compareTo(ZERO) < 0 ? "SHORTAGE"
                    : unscheduledSupply.compareTo(ZERO) > 0 ? "UNSCHEDULED_SUPPLY" : "AVAILABLE";
            return new AtpDtos.Summary(
                    rs.getLong("product_id"), rs.getString("sku"), rs.getString("product_name"),
                    rs.getString("base_unit"), rs.getLong("warehouse_id"), rs.getString("warehouse_name"),
                    asOfDate, rs.getBigDecimal("on_hand_quantity"), rs.getBigDecimal("outbound_draft_quantity"),
                    rs.getBigDecimal("sales_commitment_quantity"), rs.getBigDecimal("linked_draft_covered_quantity"),
                    rs.getBigDecimal("purchase_incoming_quantity"), rs.getBigDecimal("transfer_incoming_quantity"),
                    atp, unallocated, unscheduledSupply, status,
                    LocalDateTime.now());
        }, Date.valueOf(asOfDate), Date.valueOf(asOfDate), Date.valueOf(asOfDate),
                productId == null ? 0L : productId, productId == null ? 0L : productId,
                warehouseId == null ? 0L : warehouseId, warehouseId == null ? 0L : warehouseId);
        rows = dataScopeService.filterMasterList("PRODUCT", rows, "productId");
        return dataScopeService.filterMasterList("WAREHOUSE", rows, "warehouseId");
    }

    public List<AtpDtos.TimelineRow> timeline(Long productId, Long warehouseId,
                                               LocalDate requestedFrom, LocalDate requestedTo) {
        dataScopeService.assertMasterAllowed("PRODUCT", productId);
        dataScopeService.assertMasterAllowed("WAREHOUSE", warehouseId);
        LocalDate from = normalizedDate(requestedFrom);
        LocalDate to = requestedTo == null || requestedTo.isBefore(from) ? from.plusMonths(6) : requestedTo;
        return jdbc.query("""
                select x.* from (
                  select greatest(coalesce(o.delivery_plan_date,o.business_date),current_date) event_date,
                         'SALES_COMMITMENT' event_type,'SALES_ORDER' source_type,o.id source_id,o.order_no source_no,
                         i.product_id,o.fulfillment_warehouse_id warehouse_id,'DEMAND' direction,
                         greatest(i.quantity-i.shipped_quantity,0) quantity,
                         least(greatest(i.quantity-i.shipped_quantity,0),coalesce(d.quantity,0)) covered_quantity,
                         greatest(greatest(i.quantity-i.shipped_quantity,0)-coalesce(d.quantity,0),0) net_quantity,
                         o.status
                  from sales_order_item i join sales_order o on o.id=i.sales_order_id
                  left join (
                    select oi.sales_order_item_id,oo.warehouse_id,sum(a.quantity) quantity
                    from outbound_allocation a
                    join outbound_item oi on oi.id=a.outbound_item_id
                    join outbound_order oo on oo.id=a.outbound_id
                    where a.status='DRAFT' and oo.status='DRAFT'
                    group by oi.sales_order_item_id,oo.warehouse_id
                  ) d on d.sales_order_item_id=i.id and d.warehouse_id=o.fulfillment_warehouse_id
                  where i.product_id=? and o.fulfillment_warehouse_id=? and i.status='ENABLED'
                    and o.status in ('OPEN','PARTIAL_SHIPPED')
                  union all
                  select greatest(o.expected_date,current_date),'PURCHASE_INCOMING','PURCHASE_ORDER',o.id,o.order_no,
                         i.product_id,o.expected_warehouse_id,'SUPPLY',greatest(i.quantity-i.received_quantity,0),0,
                         greatest(i.quantity-i.received_quantity,0),o.status
                  from purchase_order_item i join purchase_order o on o.id=i.purchase_order_id
                  where i.product_id=? and o.expected_warehouse_id=? and i.status='ENABLED'
                    and o.status in ('ORDERED','PARTIAL_RECEIVED')
                  union all
                  select greatest(o.expected_arrival_date,current_date),'TRANSFER_INCOMING','INVENTORY_TRANSFER',o.id,o.order_no,
                         i.product_id,o.to_warehouse_id,'SUPPLY',i.quantity,0,i.quantity,o.status
                  from inventory_transfer_item i join inventory_transfer_order o on o.id=i.transfer_id
                  where i.product_id=? and o.to_warehouse_id=? and o.status='IN_TRANSIT'
                  union all
                  select current_date,'OUTBOUND_DRAFT','OUTBOUND',o.id,o.order_no,a.product_id,o.warehouse_id,
                         'DEMAND',sum(a.quantity),0,sum(a.quantity),o.status
                  from outbound_allocation a join outbound_order o on o.id=a.outbound_id
                  where a.product_id=? and o.warehouse_id=? and a.status='DRAFT' and o.status='DRAFT'
                  group by o.id,o.order_no,a.product_id,o.warehouse_id,o.status
                ) x where x.event_date between ? and ?
                order by x.event_date,x.direction desc,x.source_type,x.source_id
                """, (rs, rowNum) -> new AtpDtos.TimelineRow(
                rs.getDate("event_date").toLocalDate(), rs.getString("event_type"), rs.getString("source_type"),
                rs.getLong("source_id"), rs.getString("source_no"), rs.getLong("product_id"),
                rs.getLong("warehouse_id"), rs.getString("direction"), rs.getBigDecimal("quantity"),
                rs.getBigDecimal("covered_quantity"), rs.getBigDecimal("net_quantity"), rs.getString("status")),
                productId, warehouseId, productId, warehouseId, productId, warehouseId, productId, warehouseId,
                Date.valueOf(from), Date.valueOf(to));
    }

    public AtpDtos.OrderEvaluation previewSalesOrder(Long salesOrderId) {
        dataScopeService.assertDocumentAllowed("SALES_ORDER", salesOrderId);
        return evaluateSalesOrder(salesOrderId);
    }

    // The scope row serializes writers; READ_COMMITTED makes a waiter see the winner's committed promise.
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AtpDtos.OrderEvaluation enforceSalesOrder(Long salesOrderId, String action, int revisionNo) {
        OrderHeader header = orderHeader(salesOrderId);
        List<OrderDemand> demands = orderDemands(salesOrderId);
        requirePromiseCoordinates(header, demands);
        lockScopes(demands.stream().map(item -> new Scope(item.productId(), header.warehouseId())).toList());
        AtpDtos.OrderEvaluation evaluation = evaluate(header, demands, salesOrderId);
        if (!evaluation.canPromise()) {
            String detail = evaluation.issues().stream().map(AtpDtos.Issue::message)
                    .reduce((left, right) -> left + "；" + right).orElse("可承诺量不足");
            throw new BusinessException("ATP可承诺量不足：" + detail);
        }
        recordSnapshots(evaluation, demands, action, "PROMISED", revisionNo);
        return evaluation;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void lockSalesOrderAmendment(Long salesOrderId, Long newWarehouseId, Collection<Long> newProductIds) {
        OrderHeader current = orderHeader(salesOrderId);
        List<Scope> scopes = new ArrayList<>();
        if (current.warehouseId() != null && current.warehouseId() > 0) {
            for (OrderDemand item : orderDemands(salesOrderId)) scopes.add(new Scope(item.productId(), current.warehouseId()));
        }
        if (newWarehouseId != null && newWarehouseId > 0) {
            for (Long productId : newProductIds) if (productId != null && productId > 0)
                scopes.add(new Scope(productId, newWarehouseId));
        }
        lockScopes(scopes);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void recordSalesOrderRelease(Long salesOrderId, int revisionNo) {
        OrderHeader header = orderHeader(salesOrderId);
        List<OrderDemand> demands = orderDemands(salesOrderId);
        if (header.warehouseId() == null || header.warehouseId() <= 0 || header.promiseDate() == null || demands.isEmpty()) return;
        lockScopes(demands.stream().map(item -> new Scope(item.productId(), header.warehouseId())).toList());
        List<AtpDtos.OrderItemEvaluation> items = new ArrayList<>();
        for (OrderDemand demand : aggregate(demands)) {
            Components before = components(demand.productId(), header.warehouseId(), header.promiseDate(), 0L);
            Components after = components(demand.productId(), header.warehouseId(), header.promiseDate(), salesOrderId);
            items.add(itemEvaluation(demand, before, after.atp(), null, ZERO));
        }
        AtpDtos.OrderEvaluation evaluation = new AtpDtos.OrderEvaluation(
                salesOrderId, header.orderNo(), header.warehouseId(), header.warehouseName(), header.promiseDate(),
                CALCULATION_VERSION, true, List.of(), items, LocalDateTime.now());
        recordSnapshots(evaluation, demands, "CANCEL", "RELEASED", revisionNo);
    }

    public List<AtpDtos.Snapshot> salesOrderHistory(Long salesOrderId) {
        dataScopeService.assertDocumentAllowed("SALES_ORDER", salesOrderId);
        return jdbc.query("""
                select s.*,p.sku,p.name product_name,w.name warehouse_name
                from inventory_atp_snapshot s join product p on p.id=s.product_id
                join warehouse w on w.id=s.warehouse_id
                where s.sales_order_id=? order by s.id desc
                """, (rs, rowNum) -> new AtpDtos.Snapshot(
                rs.getLong("id"), rs.getString("snapshot_no"), rs.getString("action"), rs.getString("decision"),
                rs.getLong("sales_order_id"), rs.getLong("sales_order_item_id"), rs.getString("sales_order_no"),
                rs.getInt("revision_no"), rs.getLong("product_id"), rs.getString("sku"),
                rs.getString("product_name"), rs.getLong("warehouse_id"), rs.getString("warehouse_name"),
                rs.getDate("promise_date").toLocalDate(), rs.getBigDecimal("commitment_quantity"),
                rs.getBigDecimal("on_hand_quantity"), rs.getBigDecimal("outbound_draft_quantity"),
                rs.getBigDecimal("sales_commitment_quantity"), rs.getBigDecimal("linked_draft_covered_quantity"),
                rs.getBigDecimal("purchase_incoming_quantity"), rs.getBigDecimal("transfer_incoming_quantity"),
                rs.getBigDecimal("atp_before_quantity"), rs.getBigDecimal("atp_after_quantity"),
                rs.getDate("earliest_shortage_date") == null ? null : rs.getDate("earliest_shortage_date").toLocalDate(),
                rs.getString("calculation_version"), rs.getLong("operator_id"), rs.getString("operator_name"),
                rs.getTimestamp("created_at").toLocalDateTime()), salesOrderId);
    }

    private AtpDtos.OrderEvaluation evaluateSalesOrder(Long salesOrderId) {
        OrderHeader header = orderHeader(salesOrderId);
        List<OrderDemand> demands = orderDemands(salesOrderId);
        if (header.warehouseId() == null || header.warehouseId() <= 0 || header.promiseDate() == null || demands.isEmpty()) {
            List<AtpDtos.Issue> issues = new ArrayList<>();
            if (header.warehouseId() == null || header.warehouseId() <= 0)
                issues.add(new AtpDtos.Issue("WAREHOUSE_REQUIRED", "请选择履约仓库后再确认销售", null, ZERO));
            if (header.promiseDate() == null)
                issues.add(new AtpDtos.Issue("PROMISE_DATE_REQUIRED", "请选择计划交付日期后再确认销售", null, ZERO));
            if (demands.isEmpty()) issues.add(new AtpDtos.Issue("ITEM_REQUIRED", "销售订单没有待承诺商品", null, ZERO));
            return new AtpDtos.OrderEvaluation(salesOrderId, header.orderNo(), header.warehouseId(), header.warehouseName(),
                    header.promiseDate(), CALCULATION_VERSION, false, issues, List.of(), LocalDateTime.now());
        }
        return evaluate(header, demands, salesOrderId);
    }

    private AtpDtos.OrderEvaluation evaluate(OrderHeader header, List<OrderDemand> rawDemands, Long excludedSalesOrderId) {
        List<AtpDtos.Issue> issues = new ArrayList<>();
        List<AtpDtos.OrderItemEvaluation> items = new ArrayList<>();
        for (OrderDemand demand : aggregate(rawDemands)) {
            Components values = components(demand.productId(), header.warehouseId(), header.promiseDate(), excludedSalesOrderId);
            if (values.unallocatedSales().compareTo(ZERO) > 0) {
                issues.add(new AtpDtos.Issue("UNALLOCATED_LEGACY_COMMITMENT",
                        demand.productName() + "尚有" + number(values.unallocatedSales()) + demand.baseUnit()
                                + "历史销售承诺未分配履约仓库，请先补全旧订单",
                        null, values.unallocatedSales()));
            }
            TimePhaseCheck check = timePhaseCheck(demand.productId(), header.warehouseId(), header.promiseDate(),
                    demand.quantity(), excludedSalesOrderId);
            if (check.earliestShortageDate() != null) {
                issues.add(new AtpDtos.Issue("ATP_SHORTAGE",
                        demand.productName() + "在" + check.earliestShortageDate() + "预计短缺"
                                + number(check.shortageQuantity()) + demand.baseUnit(),
                        check.earliestShortageDate(), check.shortageQuantity()));
            }
            items.add(itemEvaluation(demand, values, values.atp().subtract(demand.quantity()),
                    check.earliestShortageDate(), check.shortageQuantity()));
        }
        return new AtpDtos.OrderEvaluation(header.id(), header.orderNo(), header.warehouseId(), header.warehouseName(),
                header.promiseDate(), CALCULATION_VERSION, issues.isEmpty(), List.copyOf(issues), List.copyOf(items),
                LocalDateTime.now());
    }

    private AtpDtos.OrderItemEvaluation itemEvaluation(OrderDemand demand, Components values,
                                                        BigDecimal atpAfter, LocalDate shortageDate,
                                                        BigDecimal shortageQuantity) {
        return new AtpDtos.OrderItemEvaluation(demand.itemId(), demand.productId(), demand.sku(),
                demand.productName(), demand.baseUnit(), demand.quantity(), values.onHand(), values.outboundDraft(),
                values.salesCommitment(), values.linkedDraftCovered(), values.purchaseIncoming(),
                values.transferIncoming(), values.atp(), atpAfter, shortageDate, shortageQuantity);
    }

    private Components components(Long productId, Long warehouseId, LocalDate date, Long excludedSalesOrderId) {
        return jdbc.query("""
                select
                  (select coalesce(sum(quantity_available),0) from inventory_batch
                   where product_id=? and warehouse_id=?) on_hand_quantity,
                  (select coalesce(sum(a.quantity),0) from outbound_allocation a
                   join outbound_order o on o.id=a.outbound_id
                   where a.product_id=? and o.warehouse_id=? and a.status='DRAFT' and o.status='DRAFT') outbound_draft_quantity,
                  (select coalesce(sum(greatest(i.quantity-i.shipped_quantity,0)),0)
                   from sales_order_item i join sales_order o on o.id=i.sales_order_id
                   where i.product_id=? and o.fulfillment_warehouse_id=? and i.status='ENABLED'
                     and o.status in ('OPEN','PARTIAL_SHIPPED') and o.id<>?
                     and coalesce(o.delivery_plan_date,o.business_date)<=?) sales_commitment_quantity,
                  (select coalesce(sum(least(b.remaining_quantity,coalesce(d.quantity,0))),0)
                   from (
                     select i.id,greatest(i.quantity-i.shipped_quantity,0) remaining_quantity
                     from sales_order_item i join sales_order o on o.id=i.sales_order_id
                     where i.product_id=? and o.fulfillment_warehouse_id=? and i.status='ENABLED'
                       and o.status in ('OPEN','PARTIAL_SHIPPED') and o.id<>?
                       and coalesce(o.delivery_plan_date,o.business_date)<=?
                   ) b left join (
                     select oi.sales_order_item_id,sum(a.quantity) quantity
                     from outbound_allocation a
                     join outbound_item oi on oi.id=a.outbound_item_id
                     join outbound_order oo on oo.id=a.outbound_id
                     where a.status='DRAFT' and oo.status='DRAFT' and oo.warehouse_id=?
                     group by oi.sales_order_item_id
                   ) d on d.sales_order_item_id=b.id) linked_draft_covered_quantity,
                  (select coalesce(sum(greatest(i.quantity-i.received_quantity,0)),0)
                   from purchase_order_item i join purchase_order o on o.id=i.purchase_order_id
                   where i.product_id=? and o.expected_warehouse_id=? and i.status='ENABLED'
                     and o.status in ('ORDERED','PARTIAL_RECEIVED') and o.expected_date<=?) purchase_incoming_quantity,
                  (select coalesce(sum(i.quantity),0)
                   from inventory_transfer_item i join inventory_transfer_order o on o.id=i.transfer_id
                   where i.product_id=? and o.to_warehouse_id=? and o.status='IN_TRANSIT'
                     and o.expected_arrival_date<=?) transfer_incoming_quantity,
                  (select coalesce(sum(greatest(i.quantity-i.shipped_quantity,0)),0)
                   from sales_order_item i join sales_order o on o.id=i.sales_order_id
                   where i.product_id=? and i.status='ENABLED' and o.status in ('OPEN','PARTIAL_SHIPPED')
                     and o.fulfillment_warehouse_id=0 and o.id<>?) unallocated_sales_quantity
                """, rs -> {
            if (!rs.next()) return new Components(ZERO, ZERO, ZERO, ZERO, ZERO, ZERO, ZERO, ZERO);
            BigDecimal onHand = rs.getBigDecimal("on_hand_quantity");
            BigDecimal draft = rs.getBigDecimal("outbound_draft_quantity");
            BigDecimal sales = rs.getBigDecimal("sales_commitment_quantity");
            BigDecimal covered = rs.getBigDecimal("linked_draft_covered_quantity");
            BigDecimal purchase = rs.getBigDecimal("purchase_incoming_quantity");
            BigDecimal transfer = rs.getBigDecimal("transfer_incoming_quantity");
            BigDecimal atp = onHand.subtract(draft).subtract(sales.subtract(covered).max(ZERO))
                    .add(purchase).add(transfer);
            return new Components(onHand, draft, sales, covered, purchase, transfer, atp,
                    rs.getBigDecimal("unallocated_sales_quantity"));
        }, productId, warehouseId, productId, warehouseId,
                productId, warehouseId, excludedSalesOrderId, Date.valueOf(date),
                productId, warehouseId, excludedSalesOrderId, Date.valueOf(date), warehouseId,
                productId, warehouseId, Date.valueOf(date),
                productId, warehouseId, Date.valueOf(date), productId, excludedSalesOrderId);
    }

    private TimePhaseCheck timePhaseCheck(Long productId, Long warehouseId, LocalDate promiseDate,
                                           BigDecimal candidateQuantity, Long excludedSalesOrderId) {
        LocalDate today = LocalDate.now();
        BigDecimal onHand = decimal("select coalesce(sum(quantity_available),0) from inventory_batch where product_id=? and warehouse_id=?",
                productId, warehouseId);
        BigDecimal draft = decimal("""
                select coalesce(sum(a.quantity),0) from outbound_allocation a
                join outbound_order o on o.id=a.outbound_id
                where a.product_id=? and o.warehouse_id=? and a.status='DRAFT' and o.status='DRAFT'
                """, productId, warehouseId);
        TreeMap<LocalDate, BigDecimal> movements = new TreeMap<>();
        jdbc.query("""
                select greatest(coalesce(o.delivery_plan_date,o.business_date),current_date) event_date,
                       sum(greatest(greatest(i.quantity-i.shipped_quantity,0)-coalesce(d.quantity,0),0)) quantity
                from sales_order_item i join sales_order o on o.id=i.sales_order_id
                left join (
                  select oi.sales_order_item_id,sum(a.quantity) quantity
                  from outbound_allocation a
                  join outbound_item oi on oi.id=a.outbound_item_id
                  join outbound_order oo on oo.id=a.outbound_id
                  where a.status='DRAFT' and oo.status='DRAFT' and oo.warehouse_id=?
                  group by oi.sales_order_item_id
                ) d on d.sales_order_item_id=i.id
                where i.product_id=? and o.fulfillment_warehouse_id=? and i.status='ENABLED'
                  and o.status in ('OPEN','PARTIAL_SHIPPED') and o.id<>?
                group by greatest(coalesce(o.delivery_plan_date,o.business_date),current_date)
                """, rs -> {
            movements.merge(rs.getDate(1).toLocalDate(), rs.getBigDecimal(2).negate(), BigDecimal::add);
        }, warehouseId, productId, warehouseId, excludedSalesOrderId);
        jdbc.query("""
                select greatest(o.expected_date,current_date) event_date,
                       sum(greatest(i.quantity-i.received_quantity,0)) quantity
                from purchase_order_item i join purchase_order o on o.id=i.purchase_order_id
                where i.product_id=? and o.expected_warehouse_id=? and i.status='ENABLED'
                  and o.status in ('ORDERED','PARTIAL_RECEIVED') and o.expected_date is not null
                group by greatest(o.expected_date,current_date)
                """, rs -> {
            movements.merge(rs.getDate(1).toLocalDate(), rs.getBigDecimal(2), BigDecimal::add);
        }, productId, warehouseId);
        jdbc.query("""
                select greatest(o.expected_arrival_date,current_date) event_date,sum(i.quantity) quantity
                from inventory_transfer_item i join inventory_transfer_order o on o.id=i.transfer_id
                where i.product_id=? and o.to_warehouse_id=? and o.status='IN_TRANSIT'
                group by greatest(o.expected_arrival_date,current_date)
                """, rs -> {
            movements.merge(rs.getDate(1).toLocalDate(), rs.getBigDecimal(2), BigDecimal::add);
        }, productId, warehouseId);
        movements.merge(promiseDate.isBefore(today) ? today : promiseDate, candidateQuantity.negate(), BigDecimal::add);
        BigDecimal balance = onHand.subtract(draft);
        for (Map.Entry<LocalDate, BigDecimal> movement : movements.entrySet()) {
            balance = balance.add(movement.getValue());
            if (balance.compareTo(ZERO) < 0) return new TimePhaseCheck(movement.getKey(), balance.abs());
        }
        return new TimePhaseCheck(null, ZERO);
    }

    private void lockScopes(Collection<Scope> rawScopes) {
        List<Scope> scopes = rawScopes.stream().filter(scope -> scope.productId() != null && scope.productId() > 0
                        && scope.warehouseId() != null && scope.warehouseId() > 0)
                .distinct().sorted(Comparator.comparing(Scope::productId).thenComparing(Scope::warehouseId)).toList();
        for (Scope scope : scopes) {
            jdbc.update("insert ignore into inventory_atp_lock(product_id,warehouse_id) values (?,?)",
                    scope.productId(), scope.warehouseId());
            jdbc.queryForObject("select product_id from inventory_atp_lock where product_id=? and warehouse_id=? for update",
                    Long.class, scope.productId(), scope.warehouseId());
        }
    }

    private void recordSnapshots(AtpDtos.OrderEvaluation evaluation, List<OrderDemand> rawDemands,
                                 String action, String decision, int revisionNo) {
        SecurityContext.Principal actor = SecurityContext.require();
        Map<Long, List<OrderDemand>> byProduct = rawDemands.stream().collect(java.util.stream.Collectors.groupingBy(
                OrderDemand::productId, LinkedHashMap::new, java.util.stream.Collectors.toList()));
        Map<Long, AtpDtos.OrderItemEvaluation> evaluations = evaluation.items().stream().collect(
                java.util.stream.Collectors.toMap(AtpDtos.OrderItemEvaluation::productId, item -> item));
        for (Map.Entry<Long, List<OrderDemand>> entry : byProduct.entrySet()) {
            AtpDtos.OrderItemEvaluation itemEvaluation = evaluations.get(entry.getKey());
            if (itemEvaluation == null) continue;
            for (OrderDemand item : entry.getValue()) {
                jdbc.update("""
                        insert into inventory_atp_snapshot
                        (snapshot_no,action,decision,sales_order_id,sales_order_item_id,sales_order_no,revision_no,
                         product_id,warehouse_id,promise_date,commitment_quantity,on_hand_quantity,outbound_draft_quantity,
                         sales_commitment_quantity,linked_draft_covered_quantity,purchase_incoming_quantity,
                         transfer_incoming_quantity,atp_before_quantity,atp_after_quantity,earliest_shortage_date,
                         calculation_version,operator_id,operator_name)
                        values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                        """, nextSnapshotNo(), action, decision, evaluation.salesOrderId(), item.itemId(),
                        evaluation.salesOrderNo(), revisionNo, item.productId(), evaluation.warehouseId(),
                        Date.valueOf(evaluation.promiseDate()), item.quantity(), itemEvaluation.onHandQuantity(),
                        itemEvaluation.outboundDraftQuantity(), itemEvaluation.salesCommitmentQuantity(),
                        itemEvaluation.linkedDraftCoveredQuantity(), itemEvaluation.purchaseIncomingQuantity(),
                        itemEvaluation.transferIncomingQuantity(), itemEvaluation.atpBeforeQuantity(),
                        itemEvaluation.atpAfterQuantity(), itemEvaluation.earliestShortageDate() == null ? null
                                : Date.valueOf(itemEvaluation.earliestShortageDate()),
                        CALCULATION_VERSION, actor.userId(), actor.employeeName());
            }
        }
    }

    private OrderHeader orderHeader(Long salesOrderId) {
        List<OrderHeader> rows = jdbc.query("""
                select o.id,o.order_no,o.fulfillment_warehouse_id,w.name warehouse_name,
                       o.delivery_plan_date,o.business_date,o.status
                from sales_order o left join warehouse w on w.id=o.fulfillment_warehouse_id
                where o.id=?
                """, (rs, rowNum) -> new OrderHeader(rs.getLong("id"), rs.getString("order_no"),
                rs.getLong("fulfillment_warehouse_id"), rs.getString("warehouse_name"),
                rs.getDate("delivery_plan_date") == null ? null : rs.getDate("delivery_plan_date").toLocalDate(),
                rs.getDate("business_date").toLocalDate(), rs.getString("status")), salesOrderId);
        if (rows.isEmpty()) throw new BusinessException("销售订单不存在");
        return rows.get(0);
    }

    private List<OrderDemand> orderDemands(Long salesOrderId) {
        return jdbc.query("""
                select i.id,i.product_id,p.sku,p.name product_name,coalesce(nullif(i.base_unit,''),p.unit) base_unit,
                       greatest(i.quantity-i.shipped_quantity,0) quantity
                from sales_order_item i join product p on p.id=i.product_id
                where i.sales_order_id=? and i.status='ENABLED' and i.quantity>i.shipped_quantity
                order by i.id
                """, (rs, rowNum) -> new OrderDemand(rs.getLong("id"), rs.getLong("product_id"),
                rs.getString("sku"), rs.getString("product_name"), rs.getString("base_unit"),
                rs.getBigDecimal("quantity")), salesOrderId);
    }

    private List<OrderDemand> aggregate(List<OrderDemand> raw) {
        Map<Long, OrderDemand> values = new LinkedHashMap<>();
        for (OrderDemand row : raw) values.merge(row.productId(), row, (left, right) -> new OrderDemand(
                left.itemId(), left.productId(), left.sku(), left.productName(), left.baseUnit(),
                left.quantity().add(right.quantity())));
        return List.copyOf(values.values());
    }

    private void requirePromiseCoordinates(OrderHeader header, List<OrderDemand> demands) {
        if (header.warehouseId() == null || header.warehouseId() <= 0) throw new BusinessException("请先选择销售订单履约仓库");
        if (header.promiseDate() == null) throw new BusinessException("请先填写销售订单计划交付日期");
        if (header.promiseDate().isBefore(header.businessDate())) throw new BusinessException("计划交付日期不能早于订单日期");
        Integer warehouse = jdbc.queryForObject("select count(*) from warehouse where id=? and status='ENABLED'",
                Integer.class, header.warehouseId());
        if (warehouse == null || warehouse != 1) throw new BusinessException("销售订单履约仓库不存在或已停用");
        if (demands.isEmpty()) throw new BusinessException("销售订单没有待承诺商品");
    }

    private BigDecimal decimal(String sql, Object... args) {
        BigDecimal value = jdbc.queryForObject(sql, BigDecimal.class, args);
        return value == null ? ZERO : value;
    }

    private LocalDate normalizedDate(LocalDate value) {
        LocalDate today = LocalDate.now();
        return value == null || value.isBefore(today) ? today : value;
    }

    private String nextSnapshotNo() {
        return "ATP" + LocalDateTime.now().format(NO_TIME)
                + String.format("%03d", Math.floorMod(NO_COUNTER.incrementAndGet(), 1000));
    }

    private String number(BigDecimal value) {
        return value == null ? "0" : value.stripTrailingZeros().toPlainString();
    }

    private record Scope(Long productId, Long warehouseId) {}
    private record OrderHeader(Long id, String orderNo, Long warehouseId, String warehouseName,
                               LocalDate promiseDate, LocalDate businessDate, String status) {}
    private record OrderDemand(Long itemId, Long productId, String sku, String productName,
                               String baseUnit, BigDecimal quantity) {}
    private record Components(BigDecimal onHand, BigDecimal outboundDraft, BigDecimal salesCommitment,
                              BigDecimal linkedDraftCovered, BigDecimal purchaseIncoming,
                              BigDecimal transferIncoming, BigDecimal atp, BigDecimal unallocatedSales) {}
    private record TimePhaseCheck(LocalDate earliestShortageDate, BigDecimal shortageQuantity) {}
}
