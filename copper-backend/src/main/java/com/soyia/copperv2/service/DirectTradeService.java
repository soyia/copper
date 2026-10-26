package com.soyia.copperv2.service;

import com.soyia.copperv2.dto.AlignedDtos;
import com.soyia.copperv2.dto.BusinessPostingDtos;
import com.soyia.copperv2.dto.DirectTradeDtos;
import com.soyia.copperv2.dto.PurchaseCreateRequest;
import com.soyia.copperv2.security.BusinessDataScopeService;
import com.soyia.copperv2.security.SecurityContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class DirectTradeService {
    private static final BigDecimal ZERO = new BigDecimal("0.00");
    private static final DateTimeFormatter MOVEMENT_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");
    private static final AtomicInteger MOVEMENT_COUNTER = new AtomicInteger();

    private final JdbcTemplate jdbc;
    private final CopperService copperService;
    private final BusinessPostingService postingService;
    private final BusinessDataScopeService dataScope;

    public DirectTradeService(JdbcTemplate jdbc, CopperService copperService,
                              BusinessPostingService postingService,
                              BusinessDataScopeService dataScope) {
        this.jdbc = jdbc;
        this.copperService = copperService;
        this.postingService = postingService;
        this.dataScope = dataScope;
    }

    public List<DirectTradeDtos.Row> list(String keyword, String status) {
        StringBuilder sql = new StringBuilder("""
                select d.*,c.name customer_name,s.name supplier_name,
                       so.received_amount,po.paid_amount,so.payment_status,po.payment_status supplier_payment_status
                from direct_trade_order d
                join partner c on c.id=d.customer_id
                join partner s on s.id=d.supplier_id
                join sales_order so on so.id=d.sales_order_id
                join purchase_order po on po.id=d.purchase_order_id
                where 1=1
                """);
        List<Object> args = new ArrayList<>();
        String cleanKeyword = text(keyword).trim();
        if (!cleanKeyword.isBlank()) {
            sql.append(" and (d.trade_no like ? or c.name like ? or s.name like ?)");
            String like = "%" + cleanKeyword + "%";
            args.add(like);
            args.add(like);
            args.add(like);
        }
        String normalizedStatus = text(status).trim().toUpperCase(Locale.ROOT);
        if (!normalizedStatus.isBlank()) {
            if (!Set.of("DRAFT", "CONFIRMED", "PARTIAL_DELIVERED", "DELIVERED", "CANCELLED")
                    .contains(normalizedStatus)) {
                throw new BusinessException("不支持的直运状态");
            }
            sql.append(" and d.status=?");
            args.add(normalizedStatus);
        }
        sql.append(" order by d.business_date desc,d.id desc limit 200");
        return dataScope.filterDocumentList("DIRECT_TRADE",
                jdbc.query(sql.toString(), this::mapRow, args.toArray()));
    }

    public DirectTradeDtos.Detail detail(Long id) {
        dataScope.assertDocumentAllowed("DIRECT_TRADE", id);
        List<DirectTradeDtos.Detail> rows = jdbc.query("""
                select d.*,c.name customer_name,s.name supplier_name,
                       so.order_no sales_order_no,po.order_no purchase_order_no,
                       so.received_amount,po.paid_amount,so.payment_status,po.payment_status supplier_payment_status
                from direct_trade_order d
                join partner c on c.id=d.customer_id
                join partner s on s.id=d.supplier_id
                join sales_order so on so.id=d.sales_order_id
                join purchase_order po on po.id=d.purchase_order_id
                where d.id=?
                """, (rs, rowNum) -> new DirectTradeDtos.Detail(
                mapRow(rs, rowNum), rs.getLong("address_id"), rs.getString("address_snapshot"),
                rs.getLong("sales_order_id"), rs.getString("sales_order_no"),
                rs.getLong("purchase_order_id"), rs.getString("purchase_order_no"),
                rs.getInt("sale_price_includes_tax") == 1, rs.getString("sale_tax_rate_summary"),
                rs.getInt("purchase_price_includes_tax") == 1, rs.getString("purchase_tax_rate_summary"),
                rs.getString("memo"), rs.getString("cancel_reason"), rs.getString("created_by_name"),
                rs.getString("confirmed_by_name"), timestamp(rs.getTimestamp("confirmed_at")),
                List.of(), List.of(), List.of(), List.of()
        ), id);
        if (rows.isEmpty()) throw new BusinessException("直运购销单不存在");
        DirectTradeDtos.Detail header = rows.get(0);
        return new DirectTradeDtos.Detail(
                header.header(), header.addressId(), header.addressSnapshot(),
                header.salesOrderId(), header.salesOrderNo(), header.purchaseOrderId(), header.purchaseOrderNo(),
                header.salePriceIncludesTax(), header.saleTaxRateSummary(),
                header.purchasePriceIncludesTax(), header.purchaseTaxRateSummary(),
                header.memo(), header.cancelReason(), header.createdByName(),
                header.confirmedByName(), header.confirmedAt(),
                items(id), deliveries(id), returns(id), events(id));
    }

    @Transactional
    public Long create(DirectTradeDtos.Create request) {
        if (Objects.equals(request.customerId(), request.supplierId())) {
            throw new BusinessException("直运客户与供应商不能是同一往来单位");
        }
        if (request.expectedDeliveryDate() != null
                && request.expectedDeliveryDate().isBefore(request.businessDate())) {
            throw new BusinessException("预计交付日期不能早于业务日期");
        }
        if (request.items() == null || request.items().isEmpty()) {
            throw new BusinessException("直运购销至少需要一条商品");
        }
        requireEnabledPartners(request.customerId(), request.supplierId());
        String tradeNo = nextTradeNo(request.businessDate());
        List<PurchaseCreateRequest.Item> purchaseItems = request.items().stream().map(item ->
                new PurchaseCreateRequest.Item(null, item.productId(), item.quantity(), item.quantityUnit(),
                        item.actualBaseQuantity(), item.expectedEffectiveRevisionId(),
                        item.expectedPackagingSnapshotHash(), item.priceUnit(), item.packageUnit(),
                        null, null, null, false, item.purchaseUnitPrice())).toList();
        List<AlignedDtos.SalesOrderCreate.Item> saleItems = request.items().stream().map(item ->
                new AlignedDtos.SalesOrderCreate.Item(null, item.productId(), item.quantity(), item.quantityUnit(),
                        item.actualBaseQuantity(), item.expectedEffectiveRevisionId(),
                        item.expectedPackagingSnapshotHash(), item.priceUnit(), item.packageUnit(),
                        false, item.saleUnitPrice())).toList();

        Long purchaseOrderId = copperService.createPurchaseOrder(new PurchaseCreateRequest(
                request.supplierId(), request.businessDate(), request.expectedDeliveryDate(), null,
                "DIRECT_TRADE", tradeNo, ZERO, ZERO, "由直运购销单 " + tradeNo + " 生成", purchaseItems));
        Long salesOrderId = copperService.createSalesOrder(new AlignedDtos.SalesOrderCreate(
                "直运购销 " + tradeNo, request.customerId(), request.addressId(), request.businessDate(),
                request.expectedDeliveryDate(), null, ZERO, ZERO, "POSTPAID", "NONE",
                "AMOUNT", ZERO, ZERO, "由直运购销单 " + tradeNo + " 生成", saleItems));
        jdbc.update("update sales_order set source_type='DIRECT_TRADE' where id=?", salesOrderId);

        SecurityContext.Principal actor = SecurityContext.require();
        Map<String, Object> sale = jdbc.queryForMap("""
                select address_snapshot,total_amount,price_includes_tax,tax_rate_summary,tax_rule_snapshot
                from sales_order where id=?
                """, salesOrderId);
        Map<String, Object> purchase = jdbc.queryForMap("""
                select total_amount,price_includes_tax,tax_rate_summary,tax_rule_snapshot
                from purchase_order where id=?
                """, purchaseOrderId);
        Long id = insert("""
                insert into direct_trade_order
                (trade_no,customer_id,supplier_id,address_id,address_snapshot,business_date,expected_delivery_date,
                 sales_order_id,purchase_order_id,status,sale_order_amount,purchase_order_amount,
                 sale_price_includes_tax,sale_tax_rate_summary,sale_tax_rule_snapshot,
                 purchase_price_includes_tax,purchase_tax_rate_summary,purchase_tax_rule_snapshot,
                 memo,created_by,created_by_name)
                values (?,?,?,?,?,?,?, ?,?,'DRAFT',?,?, ?,?,?, ?,?,?, ?,?,?)
                """, tradeNo, request.customerId(), request.supplierId(), request.addressId(),
                sale.get("address_snapshot"), Date.valueOf(request.businessDate()),
                request.expectedDeliveryDate() == null ? null : Date.valueOf(request.expectedDeliveryDate()),
                salesOrderId, purchaseOrderId, money(sale.get("total_amount")), money(purchase.get("total_amount")),
                bool(sale.get("price_includes_tax")), text(sale.get("tax_rate_summary")),
                text(sale.get("tax_rule_snapshot")), bool(purchase.get("price_includes_tax")),
                text(purchase.get("tax_rate_summary")), text(purchase.get("tax_rule_snapshot")),
                text(request.memo()), actor.userId(), actor.employeeName());

        List<Map<String, Object>> salesRows = jdbc.queryForList(
                "select * from sales_order_item where sales_order_id=? order by id", salesOrderId);
        List<Map<String, Object>> purchaseRows = jdbc.queryForList(
                "select * from purchase_order_item where purchase_order_id=? order by id", purchaseOrderId);
        if (salesRows.size() != request.items().size() || purchaseRows.size() != request.items().size()) {
            throw new BusinessException("直运购销生成的购销明细不一致");
        }
        BigDecimal totalQuantity = BigDecimal.ZERO;
        for (int i = 0; i < request.items().size(); i++) {
            Map<String, Object> s = salesRows.get(i);
            Map<String, Object> p = purchaseRows.get(i);
            if (!Objects.equals(number(s.get("product_id")), number(p.get("product_id")))) {
                throw new BusinessException("直运购销生成的商品顺序不一致");
            }
            BigDecimal quantity = decimal(s.get("quantity"));
            totalQuantity = totalQuantity.add(quantity);
            jdbc.update("""
                    insert into direct_trade_item
                    (direct_trade_id,line_no,product_id,product_sku_snapshot,product_name_snapshot,
                     product_spec_snapshot,sales_order_item_id,purchase_order_item_id,
                     requested_quantity,requested_unit,quantity,base_unit,price_unit,pricing_quantity,
                     package_unit,packaging_plan_json,packaging_effective_revision_id,packaging_snapshot_hash,
                     sale_unit_price,purchase_unit_price,sale_amount,purchase_amount)
                    values (?,?,?,?,?,?,?,?, ?,?,?,?,?,?,?,?, ?,?, ?,?,?,?)
                    """, id, i + 1, number(s.get("product_id")), text(s.get("product_sku_snapshot")),
                    text(s.get("product_name_snapshot")), text(s.get("product_spec_snapshot")),
                    number(s.get("id")), number(p.get("id")), decimal(s.get("requested_quantity")),
                    text(s.get("requested_unit")), quantity, text(s.get("base_unit")),
                    text(s.get("price_unit")), decimal(s.get("pricing_quantity")),
                    text(s.get("package_unit")), text(s.get("packaging_plan_json")),
                    number(s.get("packaging_effective_revision_id")), text(s.get("packaging_snapshot_hash")),
                    decimal(s.get("unit_price")), decimal(p.get("unit_price")),
                    decimal(s.get("amount")), decimal(p.get("amount")));
        }
        jdbc.update("""
                update direct_trade_order d set total_quantity=?,
                  base_unit_summary=(select group_concat(concat(
                    trim(trailing '.' from trim(trailing '0' from cast(i.requested_quantity as char))),
                    coalesce(nullif(i.requested_unit,''),i.base_unit)
                  ) order by i.line_no separator ' / ') from direct_trade_item i where i.direct_trade_id=d.id)
                where d.id=?
                """, totalQuantity, id);
        dataScope.stampDocumentOwner("DIRECT_TRADE", id);
        recordEvent(id, "CREATE", "DIRECT_TRADE", id, "", "DRAFT", "一次录入并生成采购、销售承诺草稿");
        return id;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Long confirm(Long id, DirectTradeDtos.Confirm request) {
        TradeLock trade = lockTrade(id);
        if ("CANCELLED".equals(trade.status())) throw new BusinessException("已作废的直运购销单不能确认");
        if (!"DRAFT".equals(trade.status())) return id;
        requireEnabledPartners(trade);
        copperService.confirmPurchaseOrder(trade.purchaseOrderId());
        copperService.confirmSalesOrder(trade.salesOrderId(), request.riskOverrideReason(), request.riskApprovalId());
        SecurityContext.Principal actor = SecurityContext.require();
        jdbc.update("""
                update direct_trade_order set status='CONFIRMED',confirmed_by=?,confirmed_by_name=?,confirmed_at=now()
                where id=? and status='DRAFT'
                """, actor.userId(), actor.employeeName(), id);
        recordEvent(id, "CONFIRM", "DIRECT_TRADE", id, "DRAFT", "CONFIRMED", "采购与销售承诺已同时确认");
        return id;
    }

    @Transactional
    public Long cancel(Long id, String reason) {
        String cleanReason = requiredReason(reason, "直运购销作废");
        TradeLock trade = lockTrade(id);
        if ("CANCELLED".equals(trade.status())) return id;
        if (!Set.of("DRAFT", "CONFIRMED").contains(trade.status())) {
            throw new BusinessException("已有客户实收的直运购销不能作废，请按直运退货处理");
        }
        Integer deliveries = jdbc.queryForObject(
                "select count(*) from direct_trade_delivery where direct_trade_id=?", Integer.class, id);
        if (deliveries != null && deliveries > 0) {
            throw new BusinessException("已有供应商发出记录，不能直接作废；请先完成差异确认或联系供应商撤回");
        }
        copperService.cancelSalesOrder(trade.salesOrderId(), cleanReason);
        copperService.cancelPurchaseOrder(trade.purchaseOrderId(), cleanReason);
        SecurityContext.Principal actor = SecurityContext.require();
        jdbc.update("""
                update direct_trade_order set status='CANCELLED',cancel_reason=?,cancelled_by=?,
                  cancelled_by_name=?,cancelled_at=now() where id=?
                """, cleanReason, actor.userId(), actor.employeeName(), id);
        recordEvent(id, "CANCEL", "DIRECT_TRADE", id, trade.status(), "CANCELLED", cleanReason);
        return id;
    }

    @Transactional
    public Long dispatch(Long id, DirectTradeDtos.Dispatch request) {
        TradeLock trade = lockTrade(id);
        if (!Set.of("CONFIRMED", "PARTIAL_DELIVERED").contains(trade.status())) {
            throw new BusinessException("只有已确认且尚未全部交付的直运购销可以登记供应商发出");
        }
        requireEnabledPartners(trade);
        List<ItemLock> items = lockItems(id);
        Map<Long, BigDecimal> requested = quantities(request.items());
        BigDecimal total = BigDecimal.ZERO;
        Set<Long> consumed = new HashSet<>();
        Map<Long, BigDecimal> dispatchQuantities = new HashMap<>();
        for (ItemLock item : items) {
            BigDecimal openDispatch = decimal(jdbc.queryForObject("""
                    select coalesce(sum(di.dispatched_quantity-di.accepted_quantity),0)
                    from direct_trade_delivery_item di
                    join direct_trade_delivery d on d.id=di.delivery_id
                    where di.direct_trade_item_id=? and d.status='DISPATCHED'
                    """, BigDecimal.class, item.id()));
            BigDecimal netDelivered = item.deliveredQuantity().subtract(item.returnedQuantity());
            BigDecimal available = item.quantity().subtract(netDelivered).subtract(openDispatch);
            BigDecimal quantity = requested.isEmpty() ? available : requested.getOrDefault(item.id(), BigDecimal.ZERO);
            if (quantity.signum() == 0) continue;
            if (quantity.signum() < 0 || quantity.compareTo(available) > 0) {
                throw new BusinessException(item.productName() + "本次发出超过尚未交付数量");
            }
            dispatchQuantities.put(item.id(), quantity);
            total = total.add(quantity);
            consumed.add(item.id());
        }
        if (!requested.isEmpty() && !consumed.equals(requested.keySet())) {
            throw new BusinessException("发出明细包含不存在或不可发出的直运商品");
        }
        if (total.signum() <= 0) throw new BusinessException("没有可发出的直运商品");
        int sequence = nextDeliverySequence(id);
        String deliveryNo = trade.tradeNo() + "-" + String.format("%02d", sequence);
        SecurityContext.Principal actor = SecurityContext.require();
        Long deliveryId = insert("""
                insert into direct_trade_delivery
                (delivery_no,direct_trade_id,sequence_no,business_date,carrier_name,tracking_no,handler_name,
                 status,dispatched_quantity,dispatched_by,dispatched_by_name)
                values (?,?,?,?,?,?,?,'DISPATCHED',?,?,?)
                """, deliveryNo, id, sequence, Date.valueOf(request.businessDate()), text(request.carrierName()),
                text(request.trackingNo()), text(request.handlerName()), total, actor.userId(), actor.employeeName());
        for (ItemLock item : items) {
            BigDecimal quantity = dispatchQuantities.get(item.id());
            if (quantity == null) continue;
            jdbc.update("""
                    insert into direct_trade_delivery_item
                    (delivery_id,direct_trade_item_id,product_id,dispatched_quantity)
                    values (?,?,?,?)
                    """, deliveryId, item.id(), item.productId(), quantity);
        }
        recordEvent(id, "DISPATCH", "DIRECT_TRADE_DELIVERY", deliveryId, trade.status(), trade.status(),
                "供应商发出 " + deliveryNo + shippingText(request.carrierName(), request.trackingNo()));
        return deliveryId;
    }

    @Transactional
    public Long accept(Long id, Long deliveryId, DirectTradeDtos.Accept request) {
        TradeLock trade = lockTrade(id);
        DeliveryLock delivery = lockDelivery(id, deliveryId);
        if (!"DISPATCHED".equals(delivery.status())) {
            throw new BusinessException("该直运发出批次已经确认客户实收");
        }
        requireEnabledPartners(trade);
        List<DeliveryItemLock> deliveryItems = lockDeliveryItems(deliveryId);
        Map<Long, BigDecimal> requested = acceptQuantities(request.items());
        BigDecimal acceptedTotal = BigDecimal.ZERO;
        BigDecimal saleTotal = ZERO;
        BigDecimal purchaseTotal = ZERO;
        boolean difference = false;
        for (DeliveryItemLock deliveryItem : deliveryItems) {
            BigDecimal accepted = requested.isEmpty()
                    ? deliveryItem.dispatchedQuantity()
                    : requested.getOrDefault(deliveryItem.id(), BigDecimal.ZERO);
            if (accepted.signum() < 0 || accepted.compareTo(deliveryItem.dispatchedQuantity()) > 0) {
                throw new BusinessException(deliveryItem.productName() + "客户实收数量不能超过供应商发出数量");
            }
            if (accepted.compareTo(deliveryItem.dispatchedQuantity()) < 0) difference = true;
            ItemLock item = lockItem(deliveryItem.directTradeItemId(), id);
            BigDecimal saleAmount = recognizedAmount(item.saleAmount(), item.quantity(),
                    item.recognizedSaleAmount(), item.deliveredQuantity().subtract(item.returnedQuantity()), accepted);
            BigDecimal purchaseAmount = recognizedAmount(item.purchaseAmount(), item.quantity(),
                    item.recognizedPurchaseAmount(), item.deliveredQuantity().subtract(item.returnedQuantity()), accepted);
            jdbc.update("""
                    update direct_trade_delivery_item
                    set accepted_quantity=?,sale_amount=?,purchase_amount=? where id=?
                    """, accepted, saleAmount, purchaseAmount, deliveryItem.id());
            if (accepted.signum() > 0) {
                jdbc.update("""
                        update direct_trade_item set delivered_quantity=delivered_quantity+?,
                          recognized_sale_amount=recognized_sale_amount+?,
                          recognized_purchase_amount=recognized_purchase_amount+?
                        where id=?
                        """, accepted, saleAmount, purchaseAmount, item.id());
                applyUnderlyingFulfillment(item, accepted);
            }
            acceptedTotal = acceptedTotal.add(accepted);
            saleTotal = saleTotal.add(saleAmount);
            purchaseTotal = purchaseTotal.add(purchaseAmount);
        }
        if (difference && !StringUtils.hasText(request.differenceReason())) {
            throw new BusinessException("客户实收与供应商发出有差异，请填写少发、拒收或其他差异原因");
        }
        if (acceptedTotal.signum() <= 0) {
            throw new BusinessException("客户实收数量全部为零，请将本批次作为差异处理并重新登记发出");
        }
        String deliveryStatus = difference ? "PARTIAL_ACCEPTED" : "ACCEPTED";
        SecurityContext.Principal actor = SecurityContext.require();
        jdbc.update("""
                update direct_trade_delivery set status=?,accepted_quantity=?,sale_amount=?,purchase_amount=?,
                  recipient_name=?,difference_reason=?,accepted_by=?,accepted_by_name=?,accepted_at=now()
                where id=?
                """, deliveryStatus, acceptedTotal, saleTotal, purchaseTotal, text(request.recipientName()),
                text(request.differenceReason()), actor.userId(), actor.employeeName(), deliveryId);
        jdbc.update("""
                update direct_trade_order set
                  sale_receivable_amount=sale_receivable_amount+?,
                  purchase_payable_amount=purchase_payable_amount+?,
                  gross_profit_amount=gross_profit_amount+?-?
                where id=?
                """, saleTotal, purchaseTotal, saleTotal, purchaseTotal, id);
        jdbc.update("update sales_order set receivable_amount=receivable_amount+? where id=?",
                saleTotal, trade.salesOrderId());
        refreshSalesOrder(trade.salesOrderId());
        refreshPurchaseOrder(trade.purchaseOrderId());
        refreshTradeStatus(id);
        insertReceivableMovement(trade.customerId(), "DIRECT_TRADE_DELIVERY", deliveryId,
                "DEBIT", saleTotal, "直运客户实收 " + delivery.deliveryNo());
        insertPayableMovement(trade.supplierId(), "DIRECT_TRADE_DELIVERY", deliveryId,
                "INCREASE", purchaseTotal, "直运客户实收形成供应商应付 " + delivery.deliveryNo());
        int revision = nextPostingRevision(id, "DELIVERY_POST");
        postingService.post("DIRECT_TRADE", id, trade.tradeNo(), "DELIVERY_POST", revision,
                request.acceptedDate(), trade.customerId(), 0L, "客户确认直运实收 " + delivery.deliveryNo(),
                settlementEntries(trade, deliveryId, "DIRECT_TRADE_DELIVERY",
                        saleTotal, purchaseTotal, "INCREASE"));
        String nextStatus = currentStatus(id);
        recordEvent(id, difference ? "ACCEPT_WITH_DIFFERENCE" : "ACCEPT",
                "DIRECT_TRADE_DELIVERY", deliveryId, trade.status(), nextStatus,
                difference ? request.differenceReason() : "客户确认实收 " + delivery.deliveryNo());
        return deliveryId;
    }

    @Transactional
    public Long createReturn(Long id, DirectTradeDtos.ReturnCreate request) {
        TradeLock trade = lockTrade(id);
        requireEnabledPartners(trade);
        if (!Set.of("PARTIAL_DELIVERED", "DELIVERED").contains(trade.status())) {
            throw new BusinessException("直运购销尚无客户实收，不能办理退货");
        }
        Map<Long, BigDecimal> requested = quantities(request.items());
        if (requested.isEmpty()) throw new BusinessException("请选择退货商品及数量");
        List<ItemLock> items = lockItems(id);
        Map<Long, ReturnAmount> amounts = new HashMap<>();
        BigDecimal totalQuantity = BigDecimal.ZERO;
        BigDecimal saleReduction = ZERO;
        BigDecimal purchaseReduction = ZERO;
        for (ItemLock item : items) {
            BigDecimal quantity = requested.getOrDefault(item.id(), BigDecimal.ZERO);
            if (quantity.signum() == 0) continue;
            BigDecimal returnable = item.deliveredQuantity().subtract(item.returnedQuantity());
            if (quantity.signum() < 0 || quantity.compareTo(returnable) > 0) {
                throw new BusinessException(item.productName() + "退货数量超过客户当前净实收数量");
            }
            BigDecimal saleAmount = reductionAmount(item.recognizedSaleAmount(), item.saleAmount(),
                    item.quantity(), returnable, quantity);
            BigDecimal purchaseAmount = reductionAmount(item.recognizedPurchaseAmount(), item.purchaseAmount(),
                    item.quantity(), returnable, quantity);
            amounts.put(item.id(), new ReturnAmount(quantity, saleAmount, purchaseAmount));
            totalQuantity = totalQuantity.add(quantity);
            saleReduction = saleReduction.add(saleAmount);
            purchaseReduction = purchaseReduction.add(purchaseAmount);
        }
        if (!amounts.keySet().equals(requested.keySet())) {
            throw new BusinessException("退货明细包含不存在或不可退的直运商品");
        }
        BigDecimal nextSale = trade.saleReceivableAmount().subtract(saleReduction);
        BigDecimal nextPurchase = trade.purchasePayableAmount().subtract(purchaseReduction);
        ensureSettlementNotExceeded(trade, nextSale, nextPurchase);
        int sequence = jdbc.queryForObject(
                "select count(*)+1 from direct_trade_return where direct_trade_id=?", Integer.class, id);
        String returnNo = trade.tradeNo() + "-TH" + String.format("%02d", sequence);
        SecurityContext.Principal actor = SecurityContext.require();
        Long returnId = insert("""
                insert into direct_trade_return
                (return_no,direct_trade_id,business_date,total_quantity,sale_reduction_amount,
                 purchase_reduction_amount,reason,status,created_by,created_by_name)
                values (?,?,?,?,?,?,?,'CONFIRMED',?,?)
                """, returnNo, id, Date.valueOf(request.businessDate()), totalQuantity,
                saleReduction, purchaseReduction, request.reason().trim(), actor.userId(), actor.employeeName());
        for (ItemLock item : items) {
            ReturnAmount amount = amounts.get(item.id());
            if (amount == null) continue;
            jdbc.update("""
                    insert into direct_trade_return_item
                    (return_id,direct_trade_item_id,product_id,quantity,sale_reduction_amount,purchase_reduction_amount)
                    values (?,?,?,?,?,?)
                    """, returnId, item.id(), item.productId(), amount.quantity(),
                    amount.saleAmount(), amount.purchaseAmount());
            jdbc.update("""
                    update direct_trade_item set returned_quantity=returned_quantity+?,
                      recognized_sale_amount=recognized_sale_amount-?,
                      recognized_purchase_amount=recognized_purchase_amount-?
                    where id=?
                    """, amount.quantity(), amount.saleAmount(), amount.purchaseAmount(), item.id());
            applyUnderlyingReturn(item, amount.quantity());
        }
        jdbc.update("""
                update direct_trade_order set sale_receivable_amount=sale_receivable_amount-?,
                  purchase_payable_amount=purchase_payable_amount-?,
                  gross_profit_amount=gross_profit_amount-?+? where id=?
                """, saleReduction, purchaseReduction, saleReduction, purchaseReduction, id);
        jdbc.update("update sales_order set receivable_amount=receivable_amount-? where id=?",
                saleReduction, trade.salesOrderId());
        refreshSalesOrder(trade.salesOrderId());
        refreshPurchaseOrder(trade.purchaseOrderId());
        refreshTradeStatus(id);
        insertReceivableMovement(trade.customerId(), "DIRECT_TRADE_RETURN", returnId,
                "CREDIT", saleReduction, "直运退货 " + returnNo);
        insertPayableMovement(trade.supplierId(), "DIRECT_TRADE_RETURN", returnId,
                "DECREASE", purchaseReduction, "直运退货冲减供应商应付 " + returnNo);
        int revision = nextPostingRevision(id, "RETURN_POST");
        postingService.post("DIRECT_TRADE", id, trade.tradeNo(), "RETURN_POST", revision,
                request.businessDate(), trade.customerId(), 0L, request.reason().trim(),
                settlementEntries(trade, returnId, "DIRECT_TRADE_RETURN",
                        saleReduction, purchaseReduction, "DECREASE"));
        recordEvent(id, "RETURN", "DIRECT_TRADE_RETURN", returnId, trade.status(), currentStatus(id),
                request.reason().trim());
        return returnId;
    }

    @Transactional
    public Long reverseReturn(Long id, Long returnId, String reason) {
        String cleanReason = requiredReason(reason, "直运退货冲正");
        TradeLock trade = lockTrade(id);
        ReturnLock returned = lockReturn(id, returnId);
        if ("REVERSED".equals(returned.status())) return returnId;
        List<ReturnItemLock> items = jdbc.query("""
                select ri.direct_trade_item_id,ri.quantity return_quantity,
                       ri.sale_reduction_amount,ri.purchase_reduction_amount,
                       i.product_name_snapshot,i.sales_order_item_id,i.purchase_order_item_id,
                       i.requested_quantity,i.quantity ordered_quantity
                from direct_trade_return_item ri
                join direct_trade_item i on i.id=ri.direct_trade_item_id
                where ri.return_id=? order by ri.id for update
                """, (rs, rowNum) -> new ReturnItemLock(
                rs.getLong("direct_trade_item_id"), rs.getLong("sales_order_item_id"),
                rs.getLong("purchase_order_item_id"), rs.getString("product_name_snapshot"),
                rs.getBigDecimal("requested_quantity"), rs.getBigDecimal("ordered_quantity"),
                rs.getBigDecimal("return_quantity"), rs.getBigDecimal("sale_reduction_amount"),
                rs.getBigDecimal("purchase_reduction_amount")), returnId);
        for (ReturnItemLock item : items) {
            jdbc.update("""
                    update direct_trade_item set returned_quantity=returned_quantity-?,
                      recognized_sale_amount=recognized_sale_amount+?,
                      recognized_purchase_amount=recognized_purchase_amount+?
                    where id=? and returned_quantity>=?
                    """, item.returnQuantity(), item.saleAmount(), item.purchaseAmount(),
                    item.directTradeItemId(), item.returnQuantity());
            restoreUnderlyingFulfillment(item);
        }
        jdbc.update("""
                update direct_trade_order set sale_receivable_amount=sale_receivable_amount+?,
                  purchase_payable_amount=purchase_payable_amount+?,
                  gross_profit_amount=gross_profit_amount+?-? where id=?
                """, returned.saleAmount(), returned.purchaseAmount(),
                returned.saleAmount(), returned.purchaseAmount(), id);
        jdbc.update("update sales_order set receivable_amount=receivable_amount+? where id=?",
                returned.saleAmount(), trade.salesOrderId());
        SecurityContext.Principal actor = SecurityContext.require();
        jdbc.update("""
                update direct_trade_return set status='REVERSED',reversal_reason=?,reversed_by=?,
                  reversed_by_name=?,reversed_at=now() where id=?
                """, cleanReason, actor.userId(), actor.employeeName(), returnId);
        refreshSalesOrder(trade.salesOrderId());
        refreshPurchaseOrder(trade.purchaseOrderId());
        refreshTradeStatus(id);
        insertReceivableMovement(trade.customerId(), "DIRECT_TRADE_RETURN_REVERSAL", returnId,
                "DEBIT", returned.saleAmount(), "直运退货冲正 " + returned.returnNo());
        insertPayableMovement(trade.supplierId(), "DIRECT_TRADE_RETURN_REVERSAL", returnId,
                "INCREASE", returned.purchaseAmount(), "直运退货冲正恢复供应商应付 " + returned.returnNo());
        int revision = nextPostingRevision(id, "RETURN_REVERSE");
        postingService.post("DIRECT_TRADE", id, trade.tradeNo(), "RETURN_REVERSE", revision,
                LocalDate.now(), trade.customerId(), 0L, cleanReason,
                settlementEntries(trade, returnId, "DIRECT_TRADE_RETURN_REVERSAL",
                        returned.saleAmount(), returned.purchaseAmount(), "INCREASE"));
        recordEvent(id, "RETURN_REVERSE", "DIRECT_TRADE_RETURN", returnId,
                trade.status(), currentStatus(id), cleanReason);
        return returnId;
    }

    private List<DirectTradeDtos.Item> items(Long id) {
        return jdbc.query("""
                select i.*,greatest(i.quantity-(i.delivered_quantity-i.returned_quantity),0) remaining_quantity
                from direct_trade_item i where i.direct_trade_id=? order by i.line_no
                """, (rs, rowNum) -> new DirectTradeDtos.Item(
                rs.getLong("id"), rs.getInt("line_no"), rs.getLong("product_id"),
                rs.getString("product_sku_snapshot"), rs.getString("product_name_snapshot"),
                rs.getString("product_spec_snapshot"), rs.getLong("sales_order_item_id"),
                rs.getLong("purchase_order_item_id"), rs.getBigDecimal("requested_quantity"),
                rs.getString("requested_unit"), rs.getBigDecimal("quantity"), rs.getString("base_unit"),
                rs.getString("price_unit"), rs.getBigDecimal("pricing_quantity"), rs.getString("package_unit"),
                rs.getString("packaging_plan_json"), rs.getLong("packaging_effective_revision_id"),
                rs.getString("packaging_snapshot_hash"), rs.getBigDecimal("sale_unit_price"),
                rs.getBigDecimal("purchase_unit_price"), rs.getBigDecimal("sale_amount"),
                rs.getBigDecimal("purchase_amount"), rs.getBigDecimal("delivered_quantity"),
                rs.getBigDecimal("returned_quantity"), rs.getBigDecimal("remaining_quantity"),
                rs.getBigDecimal("recognized_sale_amount"), rs.getBigDecimal("recognized_purchase_amount")
        ), id);
    }

    private List<DirectTradeDtos.Delivery> deliveries(Long id) {
        return jdbc.query("""
                select * from direct_trade_delivery where direct_trade_id=? order by sequence_no desc
                """, (rs, rowNum) -> new DirectTradeDtos.Delivery(
                rs.getLong("id"), rs.getString("delivery_no"), rs.getInt("sequence_no"),
                rs.getDate("business_date").toLocalDate(), rs.getString("carrier_name"),
                rs.getString("tracking_no"), rs.getString("handler_name"), rs.getString("status"),
                rs.getBigDecimal("dispatched_quantity"), rs.getBigDecimal("accepted_quantity"),
                rs.getBigDecimal("sale_amount"), rs.getBigDecimal("purchase_amount"),
                rs.getString("recipient_name"), rs.getString("difference_reason"),
                rs.getString("dispatched_by_name"), rs.getString("accepted_by_name"),
                timestamp(rs.getTimestamp("dispatched_at")), timestamp(rs.getTimestamp("accepted_at")),
                deliveryItems(rs.getLong("id"))
        ), id);
    }

    private List<DirectTradeDtos.DeliveryItem> deliveryItems(Long deliveryId) {
        return jdbc.query("""
                select di.*,i.product_sku_snapshot,i.product_name_snapshot,i.product_spec_snapshot,i.base_unit
                from direct_trade_delivery_item di
                join direct_trade_item i on i.id=di.direct_trade_item_id
                where di.delivery_id=? order by i.line_no
                """, (rs, rowNum) -> new DirectTradeDtos.DeliveryItem(
                rs.getLong("id"), rs.getLong("direct_trade_item_id"), rs.getLong("product_id"),
                rs.getString("product_sku_snapshot"), rs.getString("product_name_snapshot"),
                rs.getString("product_spec_snapshot"), rs.getBigDecimal("dispatched_quantity"),
                rs.getBigDecimal("accepted_quantity"), rs.getString("base_unit"),
                rs.getBigDecimal("sale_amount"), rs.getBigDecimal("purchase_amount")
        ), deliveryId);
    }

    private List<DirectTradeDtos.ReturnRow> returns(Long id) {
        return jdbc.query("""
                select * from direct_trade_return where direct_trade_id=? order by id desc
                """, (rs, rowNum) -> new DirectTradeDtos.ReturnRow(
                rs.getLong("id"), rs.getString("return_no"), rs.getDate("business_date").toLocalDate(),
                rs.getBigDecimal("total_quantity"), rs.getBigDecimal("sale_reduction_amount"),
                rs.getBigDecimal("purchase_reduction_amount"), rs.getString("reason"),
                rs.getString("status"), rs.getString("created_by_name"),
                timestamp(rs.getTimestamp("confirmed_at"))
        ), id);
    }

    private List<DirectTradeDtos.Event> events(Long id) {
        return jdbc.query("""
                select * from direct_trade_event where direct_trade_id=? order by id desc
                """, (rs, rowNum) -> new DirectTradeDtos.Event(
                rs.getLong("id"), rs.getString("event_type"), rs.getString("ref_type"),
                rs.getLong("ref_id"), rs.getString("from_status"), rs.getString("to_status"),
                rs.getString("reason"), rs.getString("operator_name"),
                rs.getTimestamp("created_at").toLocalDateTime()
        ), id);
    }

    private DirectTradeDtos.Row mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new DirectTradeDtos.Row(
                rs.getLong("id"), rs.getString("trade_no"), rs.getLong("customer_id"),
                rs.getString("customer_name"), rs.getLong("supplier_id"), rs.getString("supplier_name"),
                rs.getDate("business_date").toLocalDate(),
                rs.getDate("expected_delivery_date") == null ? null : rs.getDate("expected_delivery_date").toLocalDate(),
                rs.getString("status"), rs.getBigDecimal("total_quantity"), rs.getString("base_unit_summary"),
                rs.getBigDecimal("sale_order_amount"), rs.getBigDecimal("purchase_order_amount"),
                rs.getBigDecimal("sale_receivable_amount"), rs.getBigDecimal("purchase_payable_amount"),
                rs.getBigDecimal("received_amount"), rs.getBigDecimal("paid_amount"),
                rs.getBigDecimal("gross_profit_amount"), rs.getString("payment_status"),
                rs.getString("supplier_payment_status"), rs.getTimestamp("updated_at").toLocalDateTime());
    }

    private void requireEnabledPartners(TradeLock trade) {
        requireEnabledPartners(trade.customerId(), trade.supplierId());
    }

    private void requireEnabledPartners(Long customerId, Long supplierId) {
        if (customerId < supplierId) {
            PartnerBusinessGuard.requireEnabledRole(jdbc, customerId, "CUSTOMER");
            PartnerBusinessGuard.requireEnabledRole(jdbc, supplierId, "SUPPLIER");
        } else {
            PartnerBusinessGuard.requireEnabledRole(jdbc, supplierId, "SUPPLIER");
            PartnerBusinessGuard.requireEnabledRole(jdbc, customerId, "CUSTOMER");
        }
    }

    private void applyUnderlyingFulfillment(ItemLock item, BigDecimal quantity) {
        BigDecimal salesRequested = requestedEquivalent(item.requestedQuantity(), item.quantity(),
                item.salesFulfilledRequested(), item.deliveredQuantity().subtract(item.returnedQuantity()), quantity);
        BigDecimal purchaseRequested = requestedEquivalent(item.requestedQuantity(), item.quantity(),
                item.purchaseFulfilledRequested(), item.deliveredQuantity().subtract(item.returnedQuantity()), quantity);
        jdbc.update("""
                update sales_order_item set shipped_quantity=shipped_quantity+?,
                  shipped_requested_quantity=shipped_requested_quantity+? where id=?
                """, quantity, salesRequested, item.salesOrderItemId());
        jdbc.update("""
                update purchase_order_item set received_quantity=received_quantity+?,
                  received_requested_quantity=received_requested_quantity+? where id=?
                """, quantity, purchaseRequested, item.purchaseOrderItemId());
    }

    private void applyUnderlyingReturn(ItemLock item, BigDecimal quantity) {
        BigDecimal requested = item.requestedQuantity().multiply(quantity)
                .divide(item.quantity(), 6, RoundingMode.HALF_UP);
        jdbc.update("""
                update sales_order_item set shipped_quantity=greatest(shipped_quantity-?,0),
                  shipped_requested_quantity=greatest(shipped_requested_quantity-?,0) where id=?
                """, quantity, requested, item.salesOrderItemId());
        jdbc.update("""
                update purchase_order_item set received_quantity=greatest(received_quantity-?,0),
                  received_requested_quantity=greatest(received_requested_quantity-?,0) where id=?
                """, quantity, requested, item.purchaseOrderItemId());
    }

    private void restoreUnderlyingFulfillment(ReturnItemLock item) {
        BigDecimal requested = item.requestedQuantity().multiply(item.returnQuantity())
                .divide(item.quantity(), 6, RoundingMode.HALF_UP);
        jdbc.update("""
                update sales_order_item set shipped_quantity=shipped_quantity+?,
                  shipped_requested_quantity=shipped_requested_quantity+? where id=?
                """, item.returnQuantity(), requested, item.salesOrderItemId());
        jdbc.update("""
                update purchase_order_item set received_quantity=received_quantity+?,
                  received_requested_quantity=received_requested_quantity+? where id=?
                """, item.returnQuantity(), requested, item.purchaseOrderItemId());
    }

    private BigDecimal requestedEquivalent(BigDecimal requestedTotal, BigDecimal baseTotal,
                                           BigDecimal fulfilledRequested, BigDecimal fulfilledBase,
                                           BigDecimal quantity) {
        if (fulfilledBase.add(quantity).compareTo(baseTotal) == 0) {
            return requestedTotal.subtract(fulfilledRequested);
        }
        return requestedTotal.multiply(quantity).divide(baseTotal, 6, RoundingMode.HALF_UP);
    }

    private BigDecimal recognizedAmount(BigDecimal orderAmount, BigDecimal orderQuantity,
                                        BigDecimal recognizedAmount, BigDecimal netDelivered,
                                        BigDecimal quantity) {
        if (quantity.signum() == 0) return ZERO;
        if (netDelivered.add(quantity).compareTo(orderQuantity) == 0) {
            return orderAmount.subtract(recognizedAmount).setScale(2, RoundingMode.HALF_UP);
        }
        return orderAmount.multiply(quantity).divide(orderQuantity, 2, RoundingMode.HALF_UP);
    }

    private BigDecimal reductionAmount(BigDecimal recognizedAmount, BigDecimal orderAmount,
                                       BigDecimal orderQuantity, BigDecimal returnable,
                                       BigDecimal quantity) {
        if (quantity.compareTo(returnable) == 0) return recognizedAmount;
        return orderAmount.multiply(quantity).divide(orderQuantity, 2, RoundingMode.HALF_UP)
                .min(recognizedAmount);
    }

    private List<BusinessPostingDtos.EntryDraft> settlementEntries(
            TradeLock trade, Long sourceId, String sourceLineType,
            BigDecimal saleAmount, BigDecimal purchaseAmount, String direction) {
        List<BusinessPostingDtos.EntryDraft> entries = new ArrayList<>();
        if (saleAmount.signum() > 0) entries.add(new BusinessPostingDtos.EntryDraft(
                "RECEIVABLE", direction, trade.customerId(), 0L, 0L, 0L,
                sourceLineType, sourceId, BigDecimal.ZERO, "", saleAmount,
                "直运购销客户侧结算 " + trade.tradeNo()));
        if (purchaseAmount.signum() > 0) entries.add(new BusinessPostingDtos.EntryDraft(
                "PAYABLE", direction, trade.supplierId(), 0L, 0L, 0L,
                sourceLineType, sourceId, BigDecimal.ZERO, "", purchaseAmount,
                "直运购销供应商侧结算 " + trade.tradeNo()));
        return List.copyOf(entries);
    }

    private void ensureSettlementNotExceeded(TradeLock trade, BigDecimal saleAmount, BigDecimal purchaseAmount) {
        Map<String, Object> values = jdbc.queryForMap("""
                select so.received_amount,so.adjusted_amount,po.paid_amount
                from sales_order so join purchase_order po on po.id=?
                where so.id=?
                """, trade.purchaseOrderId(), trade.salesOrderId());
        if (decimal(values.get("received_amount")).add(decimal(values.get("adjusted_amount")))
                .compareTo(saleAmount) > 0) {
            throw new BusinessException("退货后的应收将小于已收款或应收调整，请先冲正或重新分配相关收款/调整");
        }
        if (decimal(values.get("paid_amount")).compareTo(purchaseAmount) > 0) {
            throw new BusinessException("退货后的应付将小于已付款，请先冲正或重新分配相关供应商付款");
        }
    }

    private void refreshSalesOrder(Long salesOrderId) {
        jdbc.update("""
                update sales_order o set
                  status=case
                    when not exists(select 1 from sales_order_item i where i.sales_order_id=o.id
                      and i.shipped_requested_quantity<i.requested_quantity) then 'SHIPPED'
                    when exists(select 1 from sales_order_item i where i.sales_order_id=o.id
                      and i.shipped_requested_quantity>0) then 'PARTIAL_SHIPPED'
                    else 'OPEN' end,
                  payment_status=case
                    when received_amount+adjusted_amount>=receivable_amount and receivable_amount>0 then 'PAID'
                    when received_amount+adjusted_amount>0 then 'PARTIAL' else 'UNPAID' end
                where o.id=? and o.status<>'CANCELLED'
                """, salesOrderId);
    }

    private void refreshPurchaseOrder(Long purchaseOrderId) {
        jdbc.update("""
                update purchase_order o set
                  received_quantity=(select coalesce(sum(i.received_quantity),0)
                    from purchase_order_item i where i.purchase_order_id=o.id),
                  status=case
                    when not exists(select 1 from purchase_order_item i where i.purchase_order_id=o.id
                      and i.received_requested_quantity<i.requested_quantity) then 'RECEIVED'
                    when exists(select 1 from purchase_order_item i where i.purchase_order_id=o.id
                      and i.received_requested_quantity>0) then 'PARTIAL_RECEIVED'
                    else 'ORDERED' end,
                  payment_status=case
                    when (select d.purchase_payable_amount from direct_trade_order d where d.purchase_order_id=o.id)>0
                      and paid_amount>=(select d.purchase_payable_amount from direct_trade_order d where d.purchase_order_id=o.id)
                      then 'PAID'
                    when paid_amount>0 then 'PARTIAL' else 'UNPAID' end
                where o.id=? and o.status<>'CANCELLED'
                """, purchaseOrderId);
    }

    private void refreshTradeStatus(Long id) {
        jdbc.update("""
                update direct_trade_order d set status=case
                  when not exists(select 1 from direct_trade_item i where i.direct_trade_id=d.id
                    and (i.delivered_quantity-i.returned_quantity)<i.quantity) then 'DELIVERED'
                  when exists(select 1 from direct_trade_item i where i.direct_trade_id=d.id
                    and (i.delivered_quantity-i.returned_quantity)>0) then 'PARTIAL_DELIVERED'
                  else 'CONFIRMED' end
                where d.id=? and d.status<>'CANCELLED'
                """, id);
    }

    private void insertReceivableMovement(Long partnerId, String refType, Long refId,
                                          String direction, BigDecimal amount, String memo) {
        if (amount.signum() <= 0) return;
        BigDecimal balance = decimal(jdbc.queryForObject("""
                select coalesce(sum(greatest(receivable_amount-received_amount-adjusted_amount,0)),0)
                from sales_order where customer_id=? and status not in ('DRAFT','CANCELLED')
                """, BigDecimal.class, partnerId));
        jdbc.update("""
                insert into receivable_movement
                (movement_no,customer_id,ref_type,ref_id,direction,amount,balance_after,memo)
                values (?,?,?,?,?,?,?,?)
                """, nextMovementNo("ZYAR"), partnerId, refType, refId, direction, amount, balance, memo);
    }

    private void insertPayableMovement(Long partnerId, String refType, Long refId,
                                       String direction, BigDecimal amount, String memo) {
        if (amount.signum() <= 0) return;
        BigDecimal balance = decimal(jdbc.queryForObject("""
                select coalesce(sum(unpaid_amount),0) from (
                  select greatest(
                    case when po.source_type='DIRECT_TRADE'
                      then coalesce(d.purchase_payable_amount,0)
                      else po.total_amount
                    end-po.paid_amount-coalesce(r.returned_amount,0),0
                  ) unpaid_amount
                  from purchase_order po
                  left join direct_trade_order d on d.purchase_order_id=po.id
                  left join (
                    select purchase_order_id,sum(payable_reduction_amount) returned_amount
                    from purchase_return_order where status='CONFIRMED'
                    group by purchase_order_id
                  ) r on r.purchase_order_id=po.id
                  where po.supplier_id=? and po.status not in ('DRAFT','CANCELLED')
                  union all
                  select greatest(i.payable_amount-i.paid_amount-coalesce(r.returned_amount,0),0)
                  from inbound_order i
                  left join (
                    select inbound_id,sum(payable_reduction_amount) returned_amount
                    from purchase_return_order where status='CONFIRMED'
                    group by inbound_id
                  ) r on r.inbound_id=i.id
                  where i.supplier_id=? and i.purchase_order_id=0 and i.status='STOCKED'
                ) payable
                """, BigDecimal.class, partnerId, partnerId));
        jdbc.update("""
                insert into payable_movement
                (movement_no,supplier_id,ref_type,ref_id,direction,amount,balance_after,memo)
                values (?,?,?,?,?,?,?,?)
                """, nextMovementNo("ZYAP"), partnerId, refType, refId, direction, amount, balance, memo);
    }

    private void recordEvent(Long tradeId, String type, String refType, Long refId,
                             String fromStatus, String toStatus, String reason) {
        SecurityContext.Principal actor = SecurityContext.require();
        jdbc.update("""
                insert into direct_trade_event
                (direct_trade_id,event_type,ref_type,ref_id,from_status,to_status,reason,operator_id,operator_name)
                values (?,?,?,?,?,?,?,?,?)
                """, tradeId, type, text(refType), refId == null ? 0L : refId,
                text(fromStatus), text(toStatus), text(reason), actor.userId(), actor.employeeName());
    }

    private TradeLock lockTrade(Long id) {
        dataScope.assertDocumentAllowed("DIRECT_TRADE", id);
        List<TradeLock> rows = jdbc.query("""
                select id,trade_no,customer_id,supplier_id,sales_order_id,purchase_order_id,status,
                       sale_receivable_amount,purchase_payable_amount
                from direct_trade_order where id=? for update
                """, (rs, rowNum) -> new TradeLock(
                rs.getLong("id"), rs.getString("trade_no"), rs.getLong("customer_id"),
                rs.getLong("supplier_id"), rs.getLong("sales_order_id"), rs.getLong("purchase_order_id"),
                rs.getString("status"), rs.getBigDecimal("sale_receivable_amount"),
                rs.getBigDecimal("purchase_payable_amount")), id);
        if (rows.isEmpty()) throw new BusinessException("直运购销单不存在");
        return rows.get(0);
    }

    private List<ItemLock> lockItems(Long id) {
        return jdbc.query("""
                select i.*,soi.shipped_requested_quantity,poi.received_requested_quantity
                from direct_trade_item i
                join sales_order_item soi on soi.id=i.sales_order_item_id
                join purchase_order_item poi on poi.id=i.purchase_order_item_id
                where i.direct_trade_id=? order by i.line_no for update
                """, this::mapItemLock, id);
    }

    private ItemLock lockItem(Long itemId, Long tradeId) {
        List<ItemLock> rows = jdbc.query("""
                select i.*,soi.shipped_requested_quantity,poi.received_requested_quantity
                from direct_trade_item i
                join sales_order_item soi on soi.id=i.sales_order_item_id
                join purchase_order_item poi on poi.id=i.purchase_order_item_id
                where i.id=? and i.direct_trade_id=? for update
                """, this::mapItemLock, itemId, tradeId);
        if (rows.isEmpty()) throw new BusinessException("直运商品不存在");
        return rows.get(0);
    }

    private ItemLock mapItemLock(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new ItemLock(
                rs.getLong("id"), rs.getLong("product_id"), rs.getString("product_name_snapshot"),
                rs.getLong("sales_order_item_id"), rs.getLong("purchase_order_item_id"),
                rs.getBigDecimal("requested_quantity"), rs.getBigDecimal("quantity"),
                rs.getBigDecimal("delivered_quantity"), rs.getBigDecimal("returned_quantity"),
                rs.getBigDecimal("sale_amount"), rs.getBigDecimal("purchase_amount"),
                rs.getBigDecimal("recognized_sale_amount"), rs.getBigDecimal("recognized_purchase_amount"),
                rs.getBigDecimal("shipped_requested_quantity"), rs.getBigDecimal("received_requested_quantity"));
    }

    private DeliveryLock lockDelivery(Long tradeId, Long deliveryId) {
        List<DeliveryLock> rows = jdbc.query("""
                select id,delivery_no,status from direct_trade_delivery
                where id=? and direct_trade_id=? for update
                """, (rs, rowNum) -> new DeliveryLock(
                rs.getLong("id"), rs.getString("delivery_no"), rs.getString("status")), deliveryId, tradeId);
        if (rows.isEmpty()) throw new BusinessException("直运发出批次不存在");
        return rows.get(0);
    }

    private List<DeliveryItemLock> lockDeliveryItems(Long deliveryId) {
        return jdbc.query("""
                select di.*,i.product_name_snapshot
                from direct_trade_delivery_item di
                join direct_trade_item i on i.id=di.direct_trade_item_id
                where di.delivery_id=? order by di.id for update
                """, (rs, rowNum) -> new DeliveryItemLock(
                rs.getLong("id"), rs.getLong("direct_trade_item_id"), rs.getString("product_name_snapshot"),
                rs.getBigDecimal("dispatched_quantity")), deliveryId);
    }

    private ReturnLock lockReturn(Long tradeId, Long returnId) {
        List<ReturnLock> rows = jdbc.query("""
                select id,return_no,status,sale_reduction_amount,purchase_reduction_amount
                from direct_trade_return where id=? and direct_trade_id=? for update
                """, (rs, rowNum) -> new ReturnLock(
                rs.getLong("id"), rs.getString("return_no"), rs.getString("status"),
                rs.getBigDecimal("sale_reduction_amount"), rs.getBigDecimal("purchase_reduction_amount")),
                returnId, tradeId);
        if (rows.isEmpty()) throw new BusinessException("直运退货单不存在");
        return rows.get(0);
    }

    private Map<Long, BigDecimal> quantities(List<DirectTradeDtos.QuantityInput> inputs) {
        Map<Long, BigDecimal> result = new HashMap<>();
        if (inputs == null) return result;
        for (DirectTradeDtos.QuantityInput input : inputs) {
            if (result.putIfAbsent(input.itemId(), input.quantity()) != null) {
                throw new BusinessException("同一商品不能重复填写数量");
            }
        }
        return result;
    }

    private Map<Long, BigDecimal> acceptQuantities(List<DirectTradeDtos.AcceptInput> inputs) {
        Map<Long, BigDecimal> result = new HashMap<>();
        if (inputs == null) return result;
        for (DirectTradeDtos.AcceptInput input : inputs) {
            if (result.putIfAbsent(input.deliveryItemId(), input.acceptedQuantity()) != null) {
                throw new BusinessException("同一发出明细不能重复填写实收数量");
            }
        }
        return result;
    }

    private int nextDeliverySequence(Long id) {
        Integer value = jdbc.queryForObject(
                "select coalesce(max(sequence_no),0)+1 from direct_trade_delivery where direct_trade_id=?",
                Integer.class, id);
        return value == null ? 1 : value;
    }

    private int nextPostingRevision(Long id, String action) {
        Integer value = jdbc.queryForObject("""
                select coalesce(max(source_revision_no),0)+1 from business_posting_batch
                where source_type='DIRECT_TRADE' and source_id=? and posting_action=?
                """, Integer.class, id, action);
        return value == null ? 1 : value;
    }

    private String nextTradeNo(LocalDate date) {
        jdbc.update("insert ignore into direct_trade_number_sequence (business_date,next_value) values (?,1)",
                Date.valueOf(date));
        Long value = jdbc.queryForObject("""
                select next_value from direct_trade_number_sequence where business_date=? for update
                """, Long.class, Date.valueOf(date));
        if (value == null) throw new BusinessException("无法生成直运购销单号");
        jdbc.update("update direct_trade_number_sequence set next_value=next_value+1 where business_date=?",
                Date.valueOf(date));
        return "ZY" + date.format(DateTimeFormatter.BASIC_ISO_DATE) + String.format("%05d", value);
    }

    private String nextMovementNo(String prefix) {
        return prefix + LocalDateTime.now().format(MOVEMENT_TIME)
                + String.format("%03d", Math.floorMod(MOVEMENT_COUNTER.incrementAndGet(), 1000));
    }

    private Long insert(String sql, Object... args) {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            return ps;
        }, holder);
        Number key = holder.getKey();
        if (key == null) throw new BusinessException("保存直运购销数据失败");
        return key.longValue();
    }

    private String currentStatus(Long id) {
        return jdbc.queryForObject("select status from direct_trade_order where id=?", String.class, id);
    }

    private String requiredReason(String value, String label) {
        if (!StringUtils.hasText(value) || value.trim().length() < 2) {
            throw new BusinessException(label + "原因至少填写2个字");
        }
        return value.trim();
    }

    private String shippingText(String carrier, String tracking) {
        String value = String.join(" ", List.of(text(carrier), text(tracking))).trim();
        return value.isBlank() ? "" : "（" + value + "）";
    }

    private BigDecimal decimal(Object value) {
        if (value == null) return BigDecimal.ZERO;
        if (value instanceof BigDecimal decimal) return decimal;
        return new BigDecimal(String.valueOf(value));
    }

    private BigDecimal money(Object value) {
        return decimal(value).setScale(2, RoundingMode.HALF_UP);
    }

    private long number(Object value) {
        return value == null ? 0L : ((Number) value).longValue();
    }

    private int bool(Object value) {
        if (value instanceof Boolean bool) return bool ? 1 : 0;
        if (value instanceof Number number) return number.intValue() == 0 ? 0 : 1;
        return Boolean.parseBoolean(String.valueOf(value)) ? 1 : 0;
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private LocalDateTime timestamp(java.sql.Timestamp value) {
        return value == null ? null : value.toLocalDateTime();
    }

    private record TradeLock(Long id, String tradeNo, Long customerId, Long supplierId,
                             Long salesOrderId, Long purchaseOrderId, String status,
                             BigDecimal saleReceivableAmount, BigDecimal purchasePayableAmount) {}

    private record ItemLock(Long id, Long productId, String productName,
                            Long salesOrderItemId, Long purchaseOrderItemId,
                            BigDecimal requestedQuantity, BigDecimal quantity,
                            BigDecimal deliveredQuantity, BigDecimal returnedQuantity,
                            BigDecimal saleAmount, BigDecimal purchaseAmount,
                            BigDecimal recognizedSaleAmount, BigDecimal recognizedPurchaseAmount,
                            BigDecimal salesFulfilledRequested, BigDecimal purchaseFulfilledRequested) {}

    private record DeliveryLock(Long id, String deliveryNo, String status) {}

    private record DeliveryItemLock(Long id, Long directTradeItemId, String productName,
                                    BigDecimal dispatchedQuantity) {}

    private record ReturnAmount(BigDecimal quantity, BigDecimal saleAmount, BigDecimal purchaseAmount) {}

    private record ReturnLock(Long id, String returnNo, String status,
                              BigDecimal saleAmount, BigDecimal purchaseAmount) {}

    private record ReturnItemLock(Long directTradeItemId, Long salesOrderItemId, Long purchaseOrderItemId,
                                  String productName, BigDecimal requestedQuantity, BigDecimal quantity,
                                  BigDecimal returnQuantity, BigDecimal saleAmount, BigDecimal purchaseAmount) {}
}
