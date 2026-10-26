package com.soyia.copperv2.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.soyia.copperv2.dto.BusinessPostingDtos;
import com.soyia.copperv2.dto.InventoryDtos;
import com.soyia.copperv2.dto.PackagingDtos;
import com.soyia.copperv2.dto.RiskExceptionDtos;
import com.soyia.copperv2.security.BusinessDataScopeService;
import com.soyia.copperv2.security.SecurityContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class InventoryService {
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final DateTimeFormatter NO_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");
    private static final AtomicInteger NO_COUNTER = new AtomicInteger();

    private final JdbcTemplate jdbc;
    private final PackagingService packagingService;
    private final BusinessDataScopeService dataScopeService;
    private final ObjectMapper objectMapper;
    private final BusinessPostingService postingService;
    private final RiskExceptionService riskExceptionService;
    private final EvidenceAttachmentService evidenceAttachmentService;

    public InventoryService(JdbcTemplate jdbc, PackagingService packagingService,
                            BusinessDataScopeService dataScopeService, ObjectMapper objectMapper,
                            BusinessPostingService postingService,
                            RiskExceptionService riskExceptionService,
                            EvidenceAttachmentService evidenceAttachmentService) {
        this.jdbc = jdbc;
        this.packagingService = packagingService;
        this.dataScopeService = dataScopeService;
        this.objectMapper = objectMapper;
        this.postingService = postingService;
        this.riskExceptionService = riskExceptionService;
        this.evidenceAttachmentService = evidenceAttachmentService;
    }

    public InventoryDtos.CostPolicy costPolicy() {
        return jdbc.query("""
                select policy_code,costing_method,quantity_scale,unit_cost_scale,amount_scale,
                       rounding_mode,retroactive_recalculation,effective_from
                from inventory_cost_policy where status='ENABLED'
                order by effective_from desc,id desc limit 1
                """, rs -> {
            if (!rs.next()) throw new BusinessException("库存成本策略尚未配置");
            return new InventoryDtos.CostPolicy(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getInt(4),
                    rs.getInt(5), rs.getString(6), rs.getString(7), rs.getDate(8).toLocalDate());
        });
    }

    public List<InventoryDtos.OpeningCostAlignment> openingCostAlignments() {
        List<InventoryDtos.OpeningCostAlignment> rows = jdbc.query("""
                select a.*,b.batch_no,p.sku,p.name product_name,w.name warehouse_name
                from inventory_opening_cost_alignment a
                join inventory_batch b on b.id=a.batch_id
                join product p on p.id=a.product_id
                join warehouse w on w.id=a.warehouse_id
                order by a.id desc
                """, (rs, rowNum) -> new InventoryDtos.OpeningCostAlignment(
                rs.getLong("id"), rs.getLong("batch_id"), rs.getString("batch_no"),
                rs.getLong("product_id"), rs.getString("sku"), rs.getString("product_name"),
                rs.getLong("warehouse_id"), rs.getString("warehouse_name"),
                rs.getBigDecimal("quantity_snapshot"), rs.getBigDecimal("unit_cost_snapshot"),
                rs.getBigDecimal("batch_amount_before"), rs.getBigDecimal("batch_amount_after"),
                rs.getBigDecimal("difference_amount"), rs.getBigDecimal("stock_balance_amount_snapshot"),
                rs.getString("alignment_reason"), rs.getTimestamp("aligned_at").toLocalDateTime()));
        rows = dataScopeService.filterMasterList("PRODUCT", rows, "productId");
        return dataScopeService.filterMasterList("WAREHOUSE", rows, "warehouseId");
    }

    public List<InventoryDtos.Movement> movements(Long productId, Long warehouseId, Long batchId) {
        StringBuilder sql = new StringBuilder("""
                select m.*,p.name product_name,w.name warehouse_name,b.batch_no,
                       case
                         when m.ref_type='INBOUND_ITEM' then 'INBOUND'
                         when m.ref_type in ('OUTBOUND_ITEM','OUTBOUND_REVERSAL') then 'OUTBOUND'
                         when m.ref_type in ('SALES_RETURN','SALES_RETURN_REVERSAL') then 'OUTBOUND'
                         when m.ref_type in ('TRANSFER_ITEM','TRANSFER_REVERSAL') then 'INVENTORY_TRANSFER'
                         when m.ref_type in ('STOCKTAKE_ITEM','STOCKTAKE_REVERSAL') then 'INVENTORY_STOCKTAKE'
                         when m.ref_type in ('PURCHASE_RETURN_ITEM','PURCHASE_RETURN_REVERSAL') then 'PURCHASE_RETURN'
                         when m.ref_type in ('COST_ADJUSTMENT_ITEM','COST_ADJUSTMENT_REVERSAL') then 'INVENTORY_COST_ADJUSTMENT'
                         when m.ref_type in ('LANDED_COST_ALLOCATION_ITEM','LANDED_COST_ALLOCATION_REVERSAL') then 'LANDED_COST_ALLOCATION'
                         when m.ref_type in ('INVENTORY_NON_TRADE','INVENTORY_NON_TRADE_REVERSAL') then 'INVENTORY_NON_TRADE'
                         else m.ref_type
                       end source_document_type,
                       coalesce(src_in.id,src_out.id,src_sales_return.outbound_id,src_transfer.id,src_stocktake.id,
                                src_purchase_return.id,src_cost.id,src_landed.id,src_non_trade.id,m.ref_id) source_document_id,
                       coalesce(src_in.order_no,src_out.order_no,src_sales_return.return_no,src_transfer.order_no,
                                src_stocktake.order_no,src_purchase_return.order_no,src_cost.adjustment_no,src_landed.allocation_no,
                                src_non_trade.document_no,
                                concat(m.ref_type,' #',m.ref_id)) source_document_no
                from inventory_movement m
                join product p on p.id=m.product_id
                join warehouse w on w.id=m.warehouse_id
                join inventory_batch b on b.id=m.batch_id
                left join inbound_item src_in_item on m.ref_type='INBOUND_ITEM' and src_in_item.id=m.ref_id
                left join inbound_order src_in on src_in.id=src_in_item.inbound_id
                left join outbound_item src_out_item on m.ref_type='OUTBOUND_ITEM' and src_out_item.id=m.ref_id
                left join outbound_order src_out on src_out.id=case when m.ref_type='OUTBOUND_ITEM' then src_out_item.outbound_id
                                                                   when m.ref_type='OUTBOUND_REVERSAL' then m.ref_id end
                left join sales_return_order src_sales_return on m.ref_type in ('SALES_RETURN','SALES_RETURN_REVERSAL')
                                                              and src_sales_return.id=m.ref_id
                left join inventory_transfer_item src_transfer_item on m.ref_type='TRANSFER_ITEM' and src_transfer_item.id=m.ref_id
                left join inventory_transfer_order src_transfer on src_transfer.id=case when m.ref_type='TRANSFER_ITEM' then src_transfer_item.transfer_id
                                                                                        when m.ref_type='TRANSFER_REVERSAL' then m.ref_id end
                left join inventory_stocktake_item src_stocktake_item on m.ref_type='STOCKTAKE_ITEM' and src_stocktake_item.id=m.ref_id
                left join inventory_stocktake_order src_stocktake on src_stocktake.id=case when m.ref_type='STOCKTAKE_ITEM' then src_stocktake_item.stocktake_id
                                                                                           when m.ref_type='STOCKTAKE_REVERSAL' then m.ref_id end
                left join purchase_return_item src_purchase_return_item on m.ref_type='PURCHASE_RETURN_ITEM' and src_purchase_return_item.id=m.ref_id
                left join purchase_return_order src_purchase_return on src_purchase_return.id=case when m.ref_type='PURCHASE_RETURN_ITEM' then src_purchase_return_item.return_id
                                                                                                    when m.ref_type='PURCHASE_RETURN_REVERSAL' then m.ref_id end
                left join inventory_cost_adjustment_item src_cost_item on m.ref_type='COST_ADJUSTMENT_ITEM' and src_cost_item.id=m.ref_id
                left join inventory_cost_adjustment src_cost on src_cost.id=case when m.ref_type='COST_ADJUSTMENT_ITEM' then src_cost_item.adjustment_id
                                                                                 when m.ref_type='COST_ADJUSTMENT_REVERSAL' then m.ref_id end
                left join landed_cost_allocation_item src_landed_item on m.ref_type='LANDED_COST_ALLOCATION_ITEM' and src_landed_item.id=m.ref_id
                left join landed_cost_allocation src_landed on src_landed.id=case when m.ref_type='LANDED_COST_ALLOCATION_ITEM' then src_landed_item.allocation_id
                                                                                 when m.ref_type='LANDED_COST_ALLOCATION_REVERSAL' then m.ref_id end
                left join inventory_non_trade_document src_non_trade
                  on m.ref_type in ('INVENTORY_NON_TRADE','INVENTORY_NON_TRADE_REVERSAL')
                 and src_non_trade.id=m.ref_id
                where 1=1
                """);
        List<Object> args = new ArrayList<>();
        if (productId != null && productId > 0) { sql.append(" and m.product_id=?"); args.add(productId); }
        if (warehouseId != null && warehouseId > 0) { sql.append(" and m.warehouse_id=?"); args.add(warehouseId); }
        if (batchId != null && batchId > 0) { sql.append(" and m.batch_id=?"); args.add(batchId); }
        sql.append(" order by m.id desc limit 500");
        List<InventoryDtos.Movement> rows = jdbc.query(sql.toString(), (rs, rowNum) -> new InventoryDtos.Movement(
                rs.getLong("id"), rs.getString("movement_no"), rs.getString("movement_type"),
                rs.getLong("product_id"), rs.getString("product_name"), rs.getLong("warehouse_id"),
                rs.getString("warehouse_name"), rs.getLong("batch_id"), rs.getString("batch_no"),
                rs.getBigDecimal("quantity"), rs.getBigDecimal("unit_price"), rs.getBigDecimal("amount"),
                rs.getString("ref_type"), rs.getLong("ref_id"), rs.getString("source_document_type"),
                rs.getLong("source_document_id"), rs.getString("source_document_no"), rs.getString("memo"),
                rs.getTimestamp("occurred_at").toLocalDateTime()), args.toArray());
        rows = dataScopeService.filterMasterList("PRODUCT", rows, "productId");
        rows = dataScopeService.filterMasterList("WAREHOUSE", rows, "warehouseId");
        if (SecurityContext.require().hasPermission("inventory.cost.view")) return rows;
        return rows.stream().map(row -> new InventoryDtos.Movement(row.id(), row.movementNo(), row.movementType(),
                row.productId(), row.productName(), row.warehouseId(), row.warehouseName(), row.batchId(), row.batchNo(),
                row.quantity(), null, null, row.refType(), row.refId(), row.sourceDocumentType(), row.sourceDocumentId(),
                row.sourceDocumentNo(), row.memo(), row.occurredAt())).toList();
    }

    public List<InventoryDtos.ReconciliationRow> reconciliation(Long productId, Long warehouseId, boolean mismatchesOnly) {
        List<InventoryDtos.ReconciliationRow> rows = jdbc.query("""
                select k.product_id,p.sku,p.name product_name,k.warehouse_id,w.name warehouse_name,
                       coalesce(b.batch_quantity,0) batch_quantity,
                       coalesce(s.quantity_available,0) balance_quantity,
                       coalesce(b.batch_quantity,0)-coalesce(s.quantity_available,0) quantity_difference,
                       coalesce(r.reserved_quantity,0) reserved_quantity,
                       coalesce(b.batch_quantity,0)-coalesce(r.reserved_quantity,0) free_quantity,
                       coalesce(t.in_transit_quantity,0) in_transit_quantity,
                       coalesce(b.tracked_batch_quantity,0) tracked_batch_quantity,
                       coalesce(b.tracked_package_quantity,0) tracked_package_quantity,
                       coalesce(b.tracked_batch_quantity,0)-coalesce(b.tracked_package_quantity,0) package_difference,
                       coalesce(b.batch_amount_cost,0) batch_amount_cost,
                       coalesce(s.amount_cost,0) balance_amount_cost,
                       coalesce(b.batch_amount_cost,0)-coalesce(s.amount_cost,0) cost_difference,
                       coalesce(b.negative_batch_count,0)+
                         case when coalesce(s.quantity_available,0)<0 or coalesce(s.amount_cost,0)<0 then 1 else 0 end
                         negative_source_count,
                       coalesce(u.unavailable_batch_count,0) unavailable_batch_count
                from (
                  select product_id,warehouse_id from inventory_batch
                  union
                  select product_id,warehouse_id from stock_balance
                ) k
                join product p on p.id=k.product_id
                join warehouse w on w.id=k.warehouse_id
                left join (
                  select ib.product_id,ib.warehouse_id,
                         sum(ib.quantity_available) batch_quantity,
                         sum(ib.amount_cost) batch_amount_cost,
                         sum(case when ib.quantity_available<0 or ib.amount_cost<0 then 1 else 0 end) negative_batch_count,
                         sum(case when ib.packaging_tracking_mode='ACTUAL_INSTANCE' then ib.quantity_available else 0 end) tracked_batch_quantity,
                         sum(case when ib.packaging_tracking_mode='ACTUAL_INSTANCE' then coalesce(pi.available_quantity,0) else 0 end) tracked_package_quantity
                  from inventory_batch ib
                  left join (
                    select batch_id,sum(actual_base_quantity) available_quantity
                    from inventory_package_instance where status='AVAILABLE' group by batch_id
                  ) pi on pi.batch_id=ib.id
                  group by ib.product_id,ib.warehouse_id
                ) b on b.product_id=k.product_id and b.warehouse_id=k.warehouse_id
                left join stock_balance s on s.product_id=k.product_id and s.warehouse_id=k.warehouse_id
                left join (
                  select a.product_id,ib.warehouse_id,sum(a.quantity) reserved_quantity
                  from outbound_allocation a join inventory_batch ib on ib.id=a.inventory_batch_id
                  where a.status='DRAFT' group by a.product_id,ib.warehouse_id
                ) r on r.product_id=k.product_id and r.warehouse_id=k.warehouse_id
                left join (
                  select ib.product_id,ib.warehouse_id,count(*) unavailable_batch_count
                  from inventory_batch ib
                  join (
                    select inventory_batch_id,sum(quantity) reserved_quantity
                    from outbound_allocation where status='DRAFT' group by inventory_batch_id
                  ) rb on rb.inventory_batch_id=ib.id
                  where ib.quantity_available>0 and rb.reserved_quantity>=ib.quantity_available
                  group by ib.product_id,ib.warehouse_id
                ) u on u.product_id=k.product_id and u.warehouse_id=k.warehouse_id
                left join (
                  select i.product_id,o.from_warehouse_id warehouse_id,sum(i.quantity) in_transit_quantity
                  from inventory_transfer_item i join inventory_transfer_order o on o.id=i.transfer_id
                  where o.status='IN_TRANSIT' group by i.product_id,o.from_warehouse_id
                ) t on t.product_id=k.product_id and t.warehouse_id=k.warehouse_id
                where (?=0 or k.product_id=?) and (?=0 or k.warehouse_id=?)
                order by p.name,w.name
                """, (rs, rowNum) -> {
            BigDecimal batchQuantity = rs.getBigDecimal("batch_quantity");
            BigDecimal balanceQuantity = rs.getBigDecimal("balance_quantity");
            BigDecimal reservedQuantity = rs.getBigDecimal("reserved_quantity");
            BigDecimal trackedBatchQuantity = rs.getBigDecimal("tracked_batch_quantity");
            BigDecimal trackedPackageQuantity = rs.getBigDecimal("tracked_package_quantity");
            BigDecimal batchAmountCost = rs.getBigDecimal("batch_amount_cost");
            BigDecimal balanceAmountCost = rs.getBigDecimal("balance_amount_cost");
            long negativeSourceCount = rs.getLong("negative_source_count");
            long unavailableBatchCount = rs.getLong("unavailable_batch_count");
            boolean mismatch = batchQuantity.compareTo(balanceQuantity) != 0
                    || reservedQuantity.compareTo(batchQuantity) > 0
                    || trackedBatchQuantity.compareTo(trackedPackageQuantity) != 0
                    || batchAmountCost.compareTo(balanceAmountCost) != 0;
            String status = mismatch ? "MISMATCH"
                    : negativeSourceCount > 0 || unavailableBatchCount > 0 ? "RISK" : "MATCH";
            return new InventoryDtos.ReconciliationRow(rs.getLong("product_id"), rs.getString("sku"),
                    rs.getString("product_name"), rs.getLong("warehouse_id"), rs.getString("warehouse_name"),
                    batchQuantity, balanceQuantity, rs.getBigDecimal("quantity_difference"), reservedQuantity,
                    rs.getBigDecimal("free_quantity"), rs.getBigDecimal("in_transit_quantity"), trackedBatchQuantity,
                    trackedPackageQuantity, rs.getBigDecimal("package_difference"), batchAmountCost, balanceAmountCost,
                    rs.getBigDecimal("cost_difference"), negativeSourceCount, unavailableBatchCount, status);
        }, productId == null ? 0L : productId, productId == null ? 0L : productId,
                warehouseId == null ? 0L : warehouseId, warehouseId == null ? 0L : warehouseId);
        rows = dataScopeService.filterMasterList("PRODUCT", rows, "productId");
        rows = dataScopeService.filterMasterList("WAREHOUSE", rows, "warehouseId");
        if (mismatchesOnly) rows = rows.stream().filter(row -> !"MATCH".equals(row.status())).toList();
        if (SecurityContext.require().hasPermission("inventory.cost.view")) return rows;
        return rows.stream().map(row -> new InventoryDtos.ReconciliationRow(row.productId(), row.sku(), row.productName(),
                row.warehouseId(), row.warehouseName(), row.batchQuantity(), row.balanceQuantity(),
                row.quantityDifference(), row.reservedQuantity(), row.freeQuantity(), row.inTransitQuantity(),
                row.trackedBatchQuantity(), row.trackedPackageQuantity(), row.packageDifference(), null, null, null,
                row.negativeSourceCount(), row.unavailableBatchCount(), row.status())).toList();
    }

    public List<InventoryDtos.NonTradeRow> nonTradeDocuments(String status, Long batchId) {
        StringBuilder sql = new StringBuilder("""
                select d.*,p.sku,p.name product_name,p.spec,w.name warehouse_name,b.batch_no
                from inventory_non_trade_document d
                join product p on p.id=d.product_id
                join warehouse w on w.id=d.warehouse_id
                join inventory_batch b on b.id=d.batch_id
                where 1=1
                """);
        List<Object> args = new ArrayList<>();
        if (StringUtils.hasText(status)) {
            sql.append(" and d.status=?");
            args.add(status.trim().toUpperCase());
        }
        if (batchId != null && batchId > 0) {
            sql.append("""
                     and exists (
                       select 1 from inventory_non_trade_item ni
                       where ni.document_id=d.id and ni.batch_id=?
                     )
                    """);
            args.add(batchId);
        }
        sql.append(" order by d.id desc limit 300");
        List<InventoryDtos.NonTradeRow> rows = jdbc.query(sql.toString(), this::nonTradeRow, args.toArray());
        rows = dataScopeService.filterMasterList("PRODUCT", rows, "productId");
        rows = dataScopeService.filterMasterList("WAREHOUSE", rows, "warehouseId");
        return sanitizeNonTradeCost(rows);
    }

    public InventoryDtos.NonTradeDetail nonTradeDocument(Long id) {
        InventoryDtos.NonTradeRow header = jdbc.query("""
                select d.*,p.sku,p.name product_name,p.spec,w.name warehouse_name,b.batch_no
                from inventory_non_trade_document d
                join product p on p.id=d.product_id
                join warehouse w on w.id=d.warehouse_id
                join inventory_batch b on b.id=d.batch_id
                where d.id=?
                """, rs -> rs.next() ? nonTradeRow(rs, 0) : null, id);
        if (header == null) throw new BusinessException("其他出入库记录不存在");
        List<InventoryDtos.NonTradeItem> items = nonTradeItems(id, header.direction(), false);
        for (InventoryDtos.NonTradeItem item : items) {
            dataScopeService.assertMasterAllowed("PRODUCT", item.productId());
            dataScopeService.assertMasterAllowed("WAREHOUSE", item.warehouseId());
        }
        List<InventoryDtos.NonTradeRow> sanitized = sanitizeNonTradeCost(List.of(header));
        if (!SecurityContext.require().hasPermission("inventory.cost.view")) {
            items = items.stream().map(item -> new InventoryDtos.NonTradeItem(
                    item.id(), item.lineNo(), item.productId(), item.sku(), item.productName(), item.spec(),
                    item.warehouseId(), item.warehouseName(), item.batchId(), item.batchNo(),
                    item.quantity(), item.baseUnit(), null, null, item.quantityBefore(), item.quantityAfter(), null,
                    item.packageInstanceIds(), item.packageAllocations())).toList();
        }
        return new InventoryDtos.NonTradeDetail(
                sanitized.get(0),
                items,
                events("INVENTORY_NON_TRADE", id));
    }

    @Transactional
    public Long createNonTradeDraft(InventoryDtos.NonTradeCreate req) {
        String businessType = nonTradeType(req.businessType());
        String documentDirection = "OTHER_IN".equals(businessType) ? "IN" : "OUT";
        requireReason(req.reason());
        if ("OUT".equals(documentDirection) && !StringUtils.hasText(req.recipient()))
            throw new BusinessException("请填写实际领用人；签字单据必须由该领用人签字");
        if (req.items() == null || req.items().isEmpty()) throw new BusinessException("至少添加一个商品明细");
        Set<Long> uniqueBatchIds = new HashSet<>();
        for (InventoryDtos.NonTradeItemCreate item : req.items()) {
            if (!uniqueBatchIds.add(item.batchId())) throw new BusinessException("同一库存批次不能重复添加，请合并扣取明细");
        }
        Map<Long, Batch> batchesById = new LinkedHashMap<>();
        uniqueBatchIds.stream().sorted().forEach(batchId -> batchesById.put(batchId, lockBatch(batchId)));
        List<PreparedNonTradeItem> preparedItems = new ArrayList<>();
        BigDecimal totalAmountCost = ZERO;
        for (InventoryDtos.NonTradeItemCreate item : req.items()) {
            Batch batch = batchesById.get(item.batchId());
            requirePositive(item.quantity(), "商品明细数量");
            if ("OUT".equals(documentDirection)) ensureFreeStock(batch, item.quantity(), 0L);
            BigDecimal amount = removalCost(batch, item.quantity());
            PackagingDtos.Plan plan = "OUT".equals(documentDirection)
                    ? packagingService.planNonTradeForBatch(batch.id(), new PackagingDtos.PlanRequest(
                    item.quantity(), batch.baseUnit(), item.quantity(), batch.baseUnit(),
                    "OUTBOUND", null, null), item.packageInstanceIds(), item.packageAllocations())
                    : null;
            preparedItems.add(new PreparedNonTradeItem(batch, item, plan, amount,
                    packageIds(item.packageInstanceIds(), item.packageAllocations())));
            totalAmountCost = totalAmountCost.add(amount);
        }
        SecurityContext.Principal actor = SecurityContext.require();
        PreparedNonTradeItem first = preparedItems.get(0);
        String handlerName = StringUtils.hasText(req.handlerName()) ? req.handlerName().trim() : actor.employeeName();
        Long id = insert("""
                insert into inventory_non_trade_document
                (document_no,business_type,direction,business_date,product_id,warehouse_id,batch_id,
                 quantity,base_unit,unit_cost,amount_cost,quantity_before,amount_before,
                 recipient,handler_name,item_count,total_amount_cost,reason,memo,
                 selected_package_instance_ids_json,selected_package_allocations_json,
                 packaging_plan_json,status,created_by,created_by_name)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'DRAFT',?,?)
                """, nextNo("QT"), businessType, documentDirection, Date.valueOf(req.businessDate()),
                first.batch().productId(), first.batch().warehouseId(), first.batch().id(),
                first.request().quantity(), first.batch().baseUnit(), first.batch().unitCost(),
                first.amount(), first.batch().quantityAvailable(), first.batch().amountCost(),
                blank(req.recipient()), handlerName, preparedItems.size(), money(totalAmountCost),
                req.reason().trim(), blank(req.memo()), jsonIds(first.selectedPackageIds()),
                jsonNonTradeAllocations(first.request().packageAllocations()),
                first.plan() == null ? "" : first.plan().snapshotJson(), actor.userId(), actor.employeeName());
        int lineNo = 1;
        for (PreparedNonTradeItem item : preparedItems) {
            Long itemId = insert("""
                    insert into inventory_non_trade_item
                    (document_id,line_no,product_id,warehouse_id,batch_id,quantity,base_unit,
                     unit_cost,amount_cost,quantity_before,amount_before,packaging_ref_id,
                     selected_package_instance_ids_json,selected_package_allocations_json,packaging_plan_json)
                    values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, id, lineNo++, item.batch().productId(), item.batch().warehouseId(), item.batch().id(),
                    item.request().quantity(), item.batch().baseUnit(), item.batch().unitCost(), item.amount(),
                    item.batch().quantityAvailable(), item.batch().amountCost(), 0L, jsonIds(item.selectedPackageIds()),
                    jsonNonTradeAllocations(item.request().packageAllocations()),
                    item.plan() == null ? "" : item.plan().snapshotJson());
            jdbc.update("update inventory_non_trade_item set packaging_ref_id=? where id=?", itemId, itemId);
        }
        event("INVENTORY_NON_TRADE", id, "CREATE", req.reason() + "；商品明细" + preparedItems.size() + "项");
        dataScopeService.stampDocumentOwner("INVENTORY_NON_TRADE", id);
        return id;
    }

    @Transactional
    public Long quickConfirmNonTrade(InventoryDtos.NonTradeCreate req) {
        if (!"OTHER_IN".equals(nonTradeType(req.businessType()))) {
            throw new BusinessException("送样、领用、损耗和其他出库必须先保存草稿，再按实际发放确认扣减库存");
        }
        Long id = createNonTradeDraft(req);
        return issueNonTrade(id);
    }

    @Transactional
    public Long reverseNonTrade(Long id, String reason) {
        requireReason(reason);
        NonTradeLock document = lockNonTrade(id);
        if ("REVERSED".equals(document.status())) return id;
        if (!Set.of("ISSUED_PENDING_SIGNATURE", "CONFIRMED").contains(document.status())) {
            throw new BusinessException("只有已发放或已签字归档的其他出入库记录可以冲正");
        }
        List<NonTradeItemLock> items = lockNonTradeItems(id);
        Map<Long, Batch> batchesById = lockBatches(items.stream().map(NonTradeItemLock::batchId).toList());
        for (NonTradeItemLock item : items) {
            Batch batch = batchesById.get(item.batchId());
            if ("OUT".equals(document.direction())) {
                packagingService.restoreNonTradePackaging(batch.id(), batch.productId(),
                        parseIds(item.selectedPackageIdsJson()), item.packagingRefId());
                increaseBatch(batch, item.quantity(), item.amountCost());
                changeStock(batch.productId(), batch.warehouseId(), item.quantity(), item.amountCost());
                movement("QH", "NON_TRADE_OUT_REVERSAL", batch, batch.warehouseId(), item.quantity(),
                        item.unitCost(), item.amountCost(), "INVENTORY_NON_TRADE_REVERSAL", id,
                        "其他出库冲正 " + document.documentNo() + " 第" + item.lineNo() + "行");
            } else {
                ensureFreeStock(batch, item.quantity(), 0L);
                packagingService.reverseNonTradeBaseQuantity(batch.id(), batch.productId(), item.quantity(),
                        item.baseUnit(), item.packagingRefId());
                decreaseBatch(batch, item.quantity(), item.amountCost());
                changeStock(batch.productId(), batch.warehouseId(), item.quantity().negate(),
                        item.amountCost().negate());
                movement("QH", "NON_TRADE_IN_REVERSAL", batch, batch.warehouseId(), item.quantity().negate(),
                        item.unitCost(), item.amountCost().negate(), "INVENTORY_NON_TRADE_REVERSAL", id,
                        "其他入库冲正 " + document.documentNo() + " 第" + item.lineNo() + "行");
            }
        }
        SecurityContext.Principal actor = SecurityContext.require();
        jdbc.update("""
                update inventory_non_trade_document
                set status='REVERSED',reversal_reason=?,reversed_by=?,reversed_by_name=?,reversed_at=now()
                where id=? and status in ('ISSUED_PENDING_SIGNATURE','CONFIRMED')
                """, reason.trim(), actor.userId(), actor.employeeName(), id);
        event("INVENTORY_NON_TRADE", id, "REVERSE", reason);
        postingService.reverseSource("INVENTORY_NON_TRADE", id, document.documentNo(), "REVERSE", 1,
                LocalDate.now(), 0L, List.of("POST"), reason.trim());
        return id;
    }

    @Transactional
    public Long issueNonTrade(Long id) {
        NonTradeLock document = lockNonTrade(id);
        if (Set.of("ISSUED_PENDING_SIGNATURE", "CONFIRMED").contains(document.status())) return id;
        if (!"DRAFT".equals(document.status())) throw new BusinessException("其他出入库记录状态不允许确认发放");
        if ("OUT".equals(document.direction())) {
            if (!StringUtils.hasText(document.recipient()))
                throw new BusinessException("实际领用人不能为空，不能确认发放");
        }
        List<NonTradeItemLock> items = lockNonTradeItems(id);
        if (items.isEmpty()) throw new BusinessException("单据没有商品明细，不能确认发放");
        Map<Long, Batch> batchesById = lockBatches(items.stream().map(NonTradeItemLock::batchId).toList());
        for (NonTradeItemLock item : items) {
            Batch batch = batchesById.get(item.batchId());
            if (batch.quantityAvailable().compareTo(item.quantityBefore()) != 0
                    || batch.amountCost().compareTo(item.amountBefore()) != 0
                    || batch.unitCost().compareTo(item.unitCost()) != 0) {
                throw new BusinessException("第" + item.lineNo() + "行批次库存或成本已变化，请重新办理");
            }
        }
        for (NonTradeItemLock item : items) {
            Batch batch = batchesById.get(item.batchId());
            if ("OUT".equals(document.direction())) {
                ensureFreeStock(batch, item.quantity(), 0L);
                PackagingDtos.Plan plan = batchPlanFromSnapshot(batch, item.quantity(), item.packagingPlanJson());
                packagingService.consumeNonTradePackaging(batch.id(), batch.productId(), plan,
                        parseIds(item.selectedPackageIdsJson()),
                        parseNonTradeAllocations(item.selectedPackageAllocationsJson()), item.packagingRefId());
                decreaseBatch(batch, item.quantity(), item.amountCost());
                changeStock(batch.productId(), batch.warehouseId(), item.quantity().negate(),
                        item.amountCost().negate());
                movement("QT", "NON_TRADE_OUT", batch, batch.warehouseId(), item.quantity().negate(),
                        item.unitCost(), item.amountCost().negate(), "INVENTORY_NON_TRADE", id,
                        nonTradeLabel(document.businessType()) + " " + document.documentNo()
                                + " 第" + item.lineNo() + "行");
            } else {
                packagingService.receiveNonTradeBaseQuantity(batch.id(), batch.productId(), item.quantity(),
                        item.baseUnit(), item.packagingRefId());
                increaseBatch(batch, item.quantity(), item.amountCost());
                changeStock(batch.productId(), batch.warehouseId(), item.quantity(), item.amountCost());
                movement("QT", "NON_TRADE_IN", batch, batch.warehouseId(), item.quantity(),
                        item.unitCost(), item.amountCost(), "INVENTORY_NON_TRADE", id,
                        "其他入库 " + document.documentNo() + " 第" + item.lineNo() + "行");
            }
        }
        SecurityContext.Principal actor = SecurityContext.require();
        if ("OUT".equals(document.direction())) {
            jdbc.update("""
                    update inventory_non_trade_document
                    set status='ISSUED_PENDING_SIGNATURE',issued_by=?,issued_by_name=?,issued_at=now()
                    where id=? and status='DRAFT'
                    """, actor.userId(), actor.employeeName(), id);
        } else {
            jdbc.update("""
                    update inventory_non_trade_document
                    set status='CONFIRMED',issued_by=?,issued_by_name=?,issued_at=now(),
                        confirmed_by=?,confirmed_by_name=?,confirmed_at=now()
                    where id=? and status='DRAFT'
                    """, actor.userId(), actor.employeeName(), actor.userId(), actor.employeeName(), id);
        }
        event("INVENTORY_NON_TRADE", id, "OUT".equals(document.direction()) ? "ISSUE" : "RECEIVE",
                "OUT".equals(document.direction()) ? "实物已发放，库存已扣减，待实际领用人签字"
                        : "其他入库已确认并增加库存");
        postingService.post("INVENTORY_NON_TRADE", id, document.documentNo(), "POST", 1,
                document.businessDate(), 0L, 0L, nonTradeLabel(document.businessType()),
                nonTradePostingEntries(document, items));
        return id;
    }

    @Transactional
    public Long confirmNonTrade(Long id, InventoryDtos.NonTradeConfirm request) {
        NonTradeLock document = lockNonTrade(id);
        if ("CONFIRMED".equals(document.status())) return id;
        if (!"ISSUED_PENDING_SIGNATURE".equals(document.status())) {
            throw new BusinessException("只有已发放待签字的单据可以确认签字归档");
        }
        EvidenceAttachmentService.AttachmentRecord signedReceipt = evidenceAttachmentService.requiredActiveOneOf(
                request == null ? null : request.signedAttachmentId(),
                "INVENTORY_NON_TRADE", id,
                Set.of("SIGNED_RECEIPT", "SIGNED_STAMPED_COPY"), "实际领用人签字单据");
        SecurityContext.Principal actor = SecurityContext.require();
        jdbc.update("""
                update inventory_non_trade_document
                set status='CONFIRMED',signed_attachment_id=?,signed_attachment_sha256=?,
                    confirmed_by=?,confirmed_by_name=?,confirmed_at=now()
                where id=? and status='ISSUED_PENDING_SIGNATURE'
                """, signedReceipt.id(), signedReceipt.sha256(),
                actor.userId(), actor.employeeName(), id);
        event("INVENTORY_NON_TRADE", id, "SIGN_ARCHIVE",
                "实际领用人签字单据附件#" + signedReceipt.id());
        return id;
    }

    public List<InventoryDtos.TransferRow> transfers(String status) {
        String sql = """
                select o.*,fw.name from_warehouse_name,tw.name to_warehouse_name
                from inventory_transfer_order o
                join warehouse fw on fw.id=o.from_warehouse_id
                join warehouse tw on tw.id=o.to_warehouse_id
                where (?='' or o.status=?) order by o.id desc limit 300
                """;
        List<InventoryDtos.TransferRow> rows = dataScopeService.filterDocumentList("INVENTORY_TRANSFER",
                jdbc.query(sql, transferRowMapper(), blank(status), blank(status)));
        if (SecurityContext.require().hasPermission("inventory.cost.view")) return rows;
        return rows.stream().map(row -> new InventoryDtos.TransferRow(row.id(), row.orderNo(), row.fromWarehouseId(),
                row.fromWarehouseName(), row.toWarehouseId(), row.toWarehouseName(), row.businessDate(), row.status(),
                row.expectedArrivalDate(), row.totalQuantity(), null, row.memo(), row.dispatchedAt(), row.receivedAt(), row.createdAt())).toList();
    }

    public InventoryDtos.TransferDetail transfer(Long id) {
        InventoryDtos.TransferRow header = jdbc.query("""
                select o.*,fw.name from_warehouse_name,tw.name to_warehouse_name
                from inventory_transfer_order o join warehouse fw on fw.id=o.from_warehouse_id
                join warehouse tw on tw.id=o.to_warehouse_id where o.id=?
                """, rs -> rs.next() ? transferRow(rs) : null, id);
        if (header == null) throw new BusinessException("调拨单不存在");
        Map<String, Object> reasons = jdbc.queryForMap(
                "select cancel_reason,reversal_reason from inventory_transfer_order where id=?", id);
        List<InventoryDtos.TransferItem> items = jdbc.query("""
                select i.*,p.sku,p.name product_name,p.spec,sb.batch_no source_batch_no,
                       coalesce(tb.batch_no,'') target_batch_no
                from inventory_transfer_item i join product p on p.id=i.product_id
                join inventory_batch sb on sb.id=i.source_batch_id
                left join inventory_batch tb on tb.id=i.target_batch_id
                where i.transfer_id=? order by i.id
                """, (rs, rowNum) -> new InventoryDtos.TransferItem(
                rs.getLong("id"), rs.getLong("product_id"), rs.getString("sku"), rs.getString("product_name"),
                rs.getString("spec"), rs.getLong("source_batch_id"), rs.getString("source_batch_no"),
                rs.getLong("target_batch_id"), rs.getString("target_batch_no"), rs.getBigDecimal("quantity"),
                rs.getString("base_unit"), rs.getBigDecimal("unit_cost"), rs.getBigDecimal("amount_cost"),
                parseIds(rs.getString("selected_package_instance_ids_json"))), id);
        if (!SecurityContext.require().hasPermission("inventory.cost.view")) {
            header = new InventoryDtos.TransferRow(header.id(), header.orderNo(), header.fromWarehouseId(), header.fromWarehouseName(),
                    header.toWarehouseId(), header.toWarehouseName(), header.businessDate(), header.status(), header.expectedArrivalDate(), header.totalQuantity(),
                    null, header.memo(), header.dispatchedAt(), header.receivedAt(), header.createdAt());
            items = items.stream().map(item -> new InventoryDtos.TransferItem(item.id(), item.productId(), item.sku(),
                    item.productName(), item.spec(), item.sourceBatchId(), item.sourceBatchNo(), item.targetBatchId(),
                    item.targetBatchNo(), item.quantity(), item.baseUnit(), null, null, item.packageInstanceIds())).toList();
        }
        return new InventoryDtos.TransferDetail(header, text(reasons.get("cancel_reason")), text(reasons.get("reversal_reason")),
                items, events("INVENTORY_TRANSFER", id));
    }

    @Transactional
    public Long createTransfer(InventoryDtos.TransferCreate req) {
        if (Objects.equals(req.fromWarehouseId(), req.toWarehouseId())) throw new BusinessException("调出仓与调入仓不能相同");
        requireWarehouse(req.fromWarehouseId());
        requireWarehouse(req.toWarehouseId());
        dataScopeService.assertMasterAllowed("WAREHOUSE", req.fromWarehouseId());
        dataScopeService.assertMasterAllowed("WAREHOUSE", req.toWarehouseId());
        BigDecimal totalQuantity = ZERO;
        BigDecimal totalCost = ZERO;
        List<PreparedTransferItem> prepared = new ArrayList<>();
        HashSet<Long> seenBatches = new HashSet<>();
        for (InventoryDtos.TransferItemCreate item : req.items()) {
            if (!seenBatches.add(item.batchId())) throw new BusinessException("同一批次不能重复出现在一张调拨单中");
            Batch batch = lockBatch(item.batchId());
            if (!Objects.equals(batch.warehouseId(), req.fromWarehouseId())) throw new BusinessException("调拨批次不属于调出仓");
            requirePositive(item.quantity(), "调拨数量");
            ensureFreeStock(batch, item.quantity(), 0L);
            PackagingDtos.Plan plan = batchPlan(batch, item.quantity());
            BigDecimal amount = removalCost(batch, item.quantity());
            prepared.add(new PreparedTransferItem(batch, item, plan, amount));
            totalQuantity = totalQuantity.add(item.quantity());
            totalCost = totalCost.add(amount);
        }
        Long id = insert("""
                insert into inventory_transfer_order
                (order_no,from_warehouse_id,to_warehouse_id,business_date,expected_arrival_date,status,total_quantity,total_cost,memo,created_by)
                values (?,?,?,?,?,'DRAFT',?,?,?,?)
                """, nextNo("DB"), req.fromWarehouseId(), req.toWarehouseId(), Date.valueOf(req.businessDate()),
                req.expectedArrivalDate() == null ? null : Date.valueOf(req.expectedArrivalDate()),
                totalQuantity, totalCost, blank(req.memo()), SecurityContext.require().userId());
        for (PreparedTransferItem row : prepared) {
            insert("""
                    insert into inventory_transfer_item
                    (transfer_id,product_id,source_batch_id,quantity,base_unit,unit_cost,amount_cost,
                     packaging_plan_json,selected_package_instance_ids_json)
                    values (?,?,?,?,?,?,?,?,?)
                    """, id, row.batch().productId(), row.batch().id(), row.request().quantity(), row.batch().baseUnit(),
                    row.batch().unitCost(), row.amount(), row.plan().snapshotJson(), jsonIds(row.request().packageInstanceIds()));
        }
        event("INVENTORY_TRANSFER", id, "CREATE", "");
        dataScopeService.stampDocumentOwner("INVENTORY_TRANSFER", id);
        return id;
    }

    @Transactional
    public Long updateTransfer(Long id, InventoryDtos.TransferCreate req) {
        TransferLock existing = lockTransfer(id);
        if (!"DRAFT".equals(existing.status())) throw new BusinessException("只有草稿调拨单可以修改");
        if (Objects.equals(req.fromWarehouseId(), req.toWarehouseId())) throw new BusinessException("调出仓与调入仓不能相同");
        requireWarehouse(req.fromWarehouseId()); requireWarehouse(req.toWarehouseId());
        dataScopeService.assertMasterAllowed("WAREHOUSE", req.fromWarehouseId());
        dataScopeService.assertMasterAllowed("WAREHOUSE", req.toWarehouseId());
        BigDecimal totalQuantity = ZERO, totalCost = ZERO;
        List<PreparedTransferItem> prepared = new ArrayList<>();
        HashSet<Long> seenBatches = new HashSet<>();
        for (InventoryDtos.TransferItemCreate item : req.items()) {
            if (!seenBatches.add(item.batchId())) throw new BusinessException("同一批次不能重复出现在一张调拨单中");
            Batch batch = lockBatch(item.batchId());
            if (!Objects.equals(batch.warehouseId(), req.fromWarehouseId())) throw new BusinessException("调拨批次不属于调出仓");
            requirePositive(item.quantity(), "调拨数量"); ensureFreeStock(batch, item.quantity(), 0L);
            PackagingDtos.Plan plan = batchPlan(batch, item.quantity());
            BigDecimal amount = removalCost(batch, item.quantity());
            prepared.add(new PreparedTransferItem(batch, item, plan, amount));
            totalQuantity = totalQuantity.add(item.quantity()); totalCost = totalCost.add(amount);
        }
        jdbc.update("""
                update inventory_transfer_order set from_warehouse_id=?,to_warehouse_id=?,business_date=?,expected_arrival_date=?,
                  total_quantity=?,total_cost=?,memo=? where id=? and status='DRAFT'
                """, req.fromWarehouseId(), req.toWarehouseId(), Date.valueOf(req.businessDate()),
                req.expectedArrivalDate() == null ? null : Date.valueOf(req.expectedArrivalDate()),
                totalQuantity, totalCost, blank(req.memo()), id);
        jdbc.update("delete from inventory_transfer_item where transfer_id=?", id);
        for (PreparedTransferItem row : prepared) insert("""
                insert into inventory_transfer_item
                (transfer_id,product_id,source_batch_id,quantity,base_unit,unit_cost,amount_cost,packaging_plan_json,selected_package_instance_ids_json)
                values (?,?,?,?,?,?,?,?,?)
                """, id, row.batch().productId(), row.batch().id(), row.request().quantity(), row.batch().baseUnit(),
                row.batch().unitCost(), row.amount(), row.plan().snapshotJson(), jsonIds(row.request().packageInstanceIds()));
        event("INVENTORY_TRANSFER", id, "UPDATE", "");
        return id;
    }

    @Transactional
    public Long dispatchTransfer(Long id) {
        TransferLock order = lockTransfer(id);
        if ("IN_TRANSIT".equals(order.status())) return id;
        if (!"DRAFT".equals(order.status())) throw new BusinessException("只有草稿调拨单可以发出");
        if (order.expectedArrivalDate() == null) throw new BusinessException("请先填写预计到仓日期");
        if (order.expectedArrivalDate().isBefore(order.businessDate())) throw new BusinessException("预计到仓日期不能早于业务日期");
        BigDecimal totalCost = ZERO;
        for (TransferItemLock item : transferItems(id)) {
            Batch batch = lockBatch(item.sourceBatchId());
            ensureFreeStock(batch, item.quantity(), 0L);
            BigDecimal dispatchCost = removalCost(batch, item.quantity());
            jdbc.update("update inventory_transfer_item set unit_cost=?,amount_cost=? where id=?",
                    batch.unitCost(), dispatchCost, item.id());
            totalCost = totalCost.add(dispatchCost);
            PackagingDtos.Plan plan = batchPlanFromSnapshot(batch, item.quantity(), item.packagingPlanJson());
            packagingService.dispatchTransferPackaging(batch.id(), batch.productId(), plan,
                    parseIds(item.selectedIdsJson()), item.id());
            decreaseBatch(batch, item.quantity(), dispatchCost);
            changeStock(batch.productId(), order.fromWarehouseId(), item.quantity().negate(), dispatchCost.negate());
            movement("DB", "TRANSFER_OUT", batch, order.fromWarehouseId(), item.quantity().negate(),
                    batch.unitCost(), dispatchCost.negate(), "TRANSFER_ITEM", item.id(), "调拨发出 " + order.orderNo());
        }
        jdbc.update("update inventory_transfer_order set status='IN_TRANSIT',total_cost=?,dispatched_at=now(6) where id=? and status='DRAFT'", totalCost, id);
        event("INVENTORY_TRANSFER", id, "DISPATCH", "");
        postingService.post("INVENTORY_TRANSFER", id, order.orderNo(), "DISPATCH", 1,
                businessDate("inventory_transfer_order", id), 0L, 0L, "调拨发出",
                transferDispatchEntries(id));
        return id;
    }

    @Transactional
    public Long receiveTransfer(Long id) {
        TransferLock order = lockTransfer(id);
        if ("COMPLETED".equals(order.status())) return id;
        if (!"IN_TRANSIT".equals(order.status())) throw new BusinessException("只有在途调拨单可以确认入库");
        for (TransferItemLock item : transferItems(id)) {
            Batch source = lockBatch(item.sourceBatchId());
            String targetNo = targetBatchNo(source.batchNo(), id, item.id());
            Long targetId = insertTargetBatch(source, order.toWarehouseId(), targetNo, item);
            jdbc.update("""
                    insert ignore into inventory_batch_landed_cost_component
                    (allocation_id,allocation_item_id,batch_id,landed_unit_amount,status)
                    select allocation_id,allocation_item_id,?,landed_unit_amount,'ACTIVE'
                    from inventory_batch_landed_cost_component where batch_id=? and status='ACTIVE'
                    """, targetId, source.id());
            PackagingDtos.Plan plan = batchPlanFromSnapshot(source, item.quantity(), item.packagingPlanJson());
            packagingService.receiveTransferPackaging(source.id(), targetId, source.productId(), plan,
                    parseIds(item.selectedIdsJson()), item.id());
            changeStock(source.productId(), order.toWarehouseId(), item.quantity(), item.amountCost());
            Batch target = lockBatch(targetId);
            movement("DR", "TRANSFER_IN", target, order.toWarehouseId(), item.quantity(), target.unitCost(),
                    item.amountCost(), "TRANSFER_ITEM", item.id(), "调拨入库 " + order.orderNo());
            jdbc.update("update inventory_transfer_item set target_batch_id=? where id=?", targetId, item.id());
        }
        jdbc.update("update inventory_transfer_order set status='COMPLETED',received_at=now(6) where id=? and status='IN_TRANSIT'", id);
        event("INVENTORY_TRANSFER", id, "RECEIVE", "");
        postingService.post("INVENTORY_TRANSFER", id, order.orderNo(), "RECEIVE", 1,
                businessDate("inventory_transfer_order", id), 0L, 0L, "调拨收货",
                transferReceiveEntries(id));
        return id;
    }

    @Transactional
    public Long cancelTransfer(Long id, String reason) {
        TransferLock order = lockTransfer(id);
        if ("CANCELLED".equals(order.status())) return id;
        if (!"DRAFT".equals(order.status())) throw new BusinessException("只有草稿调拨单可以作废；已发出请办理冲正");
        requireReason(reason);
        jdbc.update("update inventory_transfer_order set status='CANCELLED',cancel_reason=?,cancelled_at=now() where id=?", reason.trim(), id);
        event("INVENTORY_TRANSFER", id, "CANCEL", reason);
        return id;
    }

    @Transactional
    public Long reverseTransfer(Long id, String reason) {
        TransferLock order = lockTransfer(id);
        if ("REVERSED".equals(order.status())) return id;
        if (!List.of("IN_TRANSIT", "COMPLETED").contains(order.status())) throw new BusinessException("只有在途或已完成调拨可以冲正");
        requireReason(reason);
        boolean completed = "COMPLETED".equals(order.status());
        if (completed && jdbc.queryForObject("""
                select count(*) from inventory_transfer_item ti
                join inventory_batch_landed_cost_component c on c.batch_id in (ti.source_batch_id,ti.target_batch_id)
                join landed_cost_allocation a on a.id=c.allocation_id and a.status='CONFIRMED'
                where ti.transfer_id=? and a.confirmed_at>?
                """, Long.class, id, order.receivedAt()) > 0)
            throw new BusinessException("调拨完成后确认了采购费用分摊，请先冲正后到的费用分摊再冲正调拨");
        for (TransferItemLock item : transferItems(id)) {
            Batch source = lockBatch(item.sourceBatchId());
            Batch target = completed ? lockBatch(item.targetBatchId()) : null;
            if (source.unitCost().compareTo(item.unitCost()) != 0 || completed && target.unitCost().compareTo(item.unitCost()) != 0)
                throw new BusinessException("调拨批次成本已发生变化，不能直接冲正；请先处理后续成本调整");
            if (completed) ensureFreeStock(target, item.quantity(), 0L);
            PackagingDtos.Plan plan = batchPlanFromSnapshot(source, item.quantity(), item.packagingPlanJson());
            packagingService.reverseTransferPackaging(source.id(), completed ? target.id() : 0L, source.productId(), plan,
                    parseIds(item.selectedIdsJson()), item.id(), completed);
            if (completed) {
                decreaseBatch(target, item.quantity(), item.amountCost());
                changeStock(target.productId(), order.toWarehouseId(), item.quantity().negate(), item.amountCost().negate());
                movement("DC", "TRANSFER_REVERSE_OUT", target, order.toWarehouseId(), item.quantity().negate(),
                        target.unitCost(), item.amountCost().negate(), "TRANSFER_REVERSAL", id, "调拨冲正扣回 " + order.orderNo());
            }
            increaseBatch(source, item.quantity(), item.amountCost());
            changeStock(source.productId(), order.fromWarehouseId(), item.quantity(), item.amountCost());
            movement("DC", "TRANSFER_REVERSE_IN", source, order.fromWarehouseId(), item.quantity(), source.unitCost(),
                    item.amountCost(), "TRANSFER_REVERSAL", id, "调拨冲正恢复 " + order.orderNo());
        }
        jdbc.update("update inventory_transfer_order set status='REVERSED',reversal_reason=?,reversed_at=now() where id=?", reason.trim(), id);
        event("INVENTORY_TRANSFER", id, "REVERSE", reason);
        postingService.reverseSource("INVENTORY_TRANSFER", id, order.orderNo(), "REVERSE", 1,
                LocalDate.now(), 0L, List.of("DISPATCH", "RECEIVE"), reason.trim());
        return id;
    }

    public List<InventoryDtos.StocktakeRow> stocktakes(String status) {
        List<InventoryDtos.StocktakeRow> rows = jdbc.query("""
                select o.*,w.name warehouse_name from inventory_stocktake_order o
                join warehouse w on w.id=o.warehouse_id where (?='' or o.status=?) order by o.id desc limit 300
                """, stocktakeRowMapper(), blank(status), blank(status));
        rows = dataScopeService.filterDocumentList("INVENTORY_STOCKTAKE", rows);
        if (SecurityContext.require().hasPermission("inventory.cost.view")) return rows;
        return rows.stream().map(row -> new InventoryDtos.StocktakeRow(row.id(), row.orderNo(), row.warehouseId(),
                row.warehouseName(), row.businessDate(), row.status(), row.totalGainQuantity(), row.totalLossQuantity(),
                null, row.memo(), row.confirmedAt(), row.createdAt())).toList();
    }

    public InventoryDtos.StocktakeDetail stocktake(Long id) {
        InventoryDtos.StocktakeRow header = jdbc.query("""
                select o.*,w.name warehouse_name from inventory_stocktake_order o
                join warehouse w on w.id=o.warehouse_id where o.id=?
                """, rs -> rs.next() ? stocktakeRow(rs) : null, id);
        if (header == null) throw new BusinessException("盘点单不存在");
        Map<String, Object> reasons = jdbc.queryForMap("select cancel_reason,reversal_reason from inventory_stocktake_order where id=?", id);
        List<InventoryDtos.StocktakeItem> items = jdbc.query("""
                select i.*,p.sku,p.name product_name,p.spec,b.batch_no
                from inventory_stocktake_item i join product p on p.id=i.product_id
                join inventory_batch b on b.id=i.batch_id where i.stocktake_id=? order by i.id
                """, (rs, rowNum) -> new InventoryDtos.StocktakeItem(
                rs.getLong("id"), rs.getLong("product_id"), rs.getString("sku"), rs.getString("product_name"),
                rs.getString("spec"), rs.getLong("batch_id"), rs.getString("batch_no"),
                rs.getBigDecimal("system_quantity"), rs.getBigDecimal("counted_quantity"),
                rs.getBigDecimal("difference_quantity"), rs.getString("base_unit"), rs.getBigDecimal("unit_cost"),
                rs.getBigDecimal("cost_difference"), rs.getString("difference_reason")), id);
        if (!SecurityContext.require().hasPermission("inventory.cost.view")) {
            header = new InventoryDtos.StocktakeRow(header.id(), header.orderNo(), header.warehouseId(),
                    header.warehouseName(), header.businessDate(), header.status(), header.totalGainQuantity(),
                    header.totalLossQuantity(), null, header.memo(), header.confirmedAt(), header.createdAt());
            items = items.stream().map(item -> new InventoryDtos.StocktakeItem(item.id(), item.productId(), item.sku(),
                    item.productName(), item.spec(), item.batchId(), item.batchNo(), item.systemQuantity(),
                    item.countedQuantity(), item.differenceQuantity(), item.baseUnit(), null, null,
                    item.differenceReason())).toList();
        }
        return new InventoryDtos.StocktakeDetail(header, text(reasons.get("cancel_reason")), text(reasons.get("reversal_reason")),
                items, events("INVENTORY_STOCKTAKE", id));
    }

    @Transactional
    public Long createStocktake(InventoryDtos.StocktakeCreate req) {
        requireWarehouse(req.warehouseId());
        dataScopeService.assertMasterAllowed("WAREHOUSE", req.warehouseId());
        BigDecimal gain = ZERO, loss = ZERO, costDiff = ZERO;
        List<PreparedStocktakeItem> prepared = new ArrayList<>();
        HashSet<Long> seenBatches = new HashSet<>();
        for (InventoryDtos.StocktakeItemCreate item : req.items()) {
            if (!seenBatches.add(item.batchId())) throw new BusinessException("同一批次不能重复出现在一张盘点单中");
            Batch batch = lockBatch(item.batchId());
            if (!Objects.equals(batch.warehouseId(), req.warehouseId())) throw new BusinessException("盘点批次不属于所选仓库");
            if (item.countedQuantity().compareTo(ZERO) < 0) throw new BusinessException("实盘数量不能为负数");
            BigDecimal diff = item.countedQuantity().subtract(batch.quantityAvailable());
            if (diff.compareTo(ZERO) != 0 && !StringUtils.hasText(item.differenceReason())) throw new BusinessException("盘盈盘亏必须填写差异原因");
            BigDecimal countedAmount = stocktakeAmount(batch, item.countedQuantity(), diff);
            BigDecimal amount = countedAmount.subtract(batch.amountCost());
            prepared.add(new PreparedStocktakeItem(batch, item, diff, batch.amountCost(), countedAmount, amount));
            if (diff.compareTo(ZERO) > 0) gain = gain.add(diff); else loss = loss.add(diff.abs());
            costDiff = costDiff.add(amount);
        }
        Long id = insert("""
                insert into inventory_stocktake_order
                (order_no,warehouse_id,business_date,status,total_gain_quantity,total_loss_quantity,total_cost_difference,memo,created_by)
                values (?,?,?,'DRAFT',?,?,?,?,?)
                """, nextNo("PD"), req.warehouseId(), Date.valueOf(req.businessDate()), gain, loss, costDiff,
                blank(req.memo()), SecurityContext.require().userId());
        for (PreparedStocktakeItem row : prepared) insert("""
                insert into inventory_stocktake_item
                (stocktake_id,product_id,batch_id,system_quantity,counted_quantity,difference_quantity,base_unit,unit_cost,
                 system_amount_cost,counted_amount_cost,cost_difference,difference_reason)
                values (?,?,?,?,?,?,?,?,?,?,?,?)
                """, id, row.batch().productId(), row.batch().id(), row.batch().quantityAvailable(),
                row.request().countedQuantity(), row.difference(), row.batch().baseUnit(), row.batch().unitCost(),
                row.systemAmount(), row.countedAmount(), row.costDifference(), blank(row.request().differenceReason()));
        event("INVENTORY_STOCKTAKE", id, "CREATE", "");
        dataScopeService.stampDocumentOwner("INVENTORY_STOCKTAKE", id);
        return id;
    }

    @Transactional
    public Long updateStocktake(Long id, InventoryDtos.StocktakeCreate req) {
        StocktakeLock existing = lockStocktake(id);
        if (!"DRAFT".equals(existing.status())) throw new BusinessException("只有草稿盘点单可以修改");
        requireWarehouse(req.warehouseId()); dataScopeService.assertMasterAllowed("WAREHOUSE", req.warehouseId());
        BigDecimal gain = ZERO, loss = ZERO, costDiff = ZERO;
        List<PreparedStocktakeItem> prepared = new ArrayList<>();
        HashSet<Long> seen = new HashSet<>();
        for (InventoryDtos.StocktakeItemCreate item : req.items()) {
            if (!seen.add(item.batchId())) throw new BusinessException("同一批次不能重复出现在一张盘点单中");
            Batch batch = lockBatch(item.batchId());
            if (!Objects.equals(batch.warehouseId(), req.warehouseId())) throw new BusinessException("盘点批次不属于所选仓库");
            if (item.countedQuantity().compareTo(ZERO) < 0) throw new BusinessException("实盘数量不能为负数");
            BigDecimal diff = item.countedQuantity().subtract(batch.quantityAvailable());
            if (diff.compareTo(ZERO) != 0 && !StringUtils.hasText(item.differenceReason())) throw new BusinessException("盘盈盘亏必须填写差异原因");
            BigDecimal countedAmount = stocktakeAmount(batch, item.countedQuantity(), diff);
            BigDecimal amount = countedAmount.subtract(batch.amountCost());
            prepared.add(new PreparedStocktakeItem(batch, item, diff, batch.amountCost(), countedAmount, amount));
            if (diff.compareTo(ZERO) > 0) gain = gain.add(diff); else loss = loss.add(diff.abs());
            costDiff = costDiff.add(amount);
        }
        jdbc.update("""
                update inventory_stocktake_order set warehouse_id=?,business_date=?,total_gain_quantity=?,
                  total_loss_quantity=?,total_cost_difference=?,memo=? where id=? and status='DRAFT'
                """, req.warehouseId(), Date.valueOf(req.businessDate()), gain, loss, costDiff, blank(req.memo()), id);
        jdbc.update("delete from inventory_stocktake_item where stocktake_id=?", id);
        for (PreparedStocktakeItem row : prepared) insert("""
                insert into inventory_stocktake_item
                (stocktake_id,product_id,batch_id,system_quantity,counted_quantity,difference_quantity,base_unit,unit_cost,
                 system_amount_cost,counted_amount_cost,cost_difference,difference_reason)
                values (?,?,?,?,?,?,?,?,?,?,?,?)
                """, id, row.batch().productId(), row.batch().id(), row.batch().quantityAvailable(),
                row.request().countedQuantity(), row.difference(), row.batch().baseUnit(), row.batch().unitCost(),
                row.systemAmount(), row.countedAmount(), row.costDifference(), blank(row.request().differenceReason()));
        event("INVENTORY_STOCKTAKE", id, "UPDATE", "");
        return id;
    }

    @Transactional
    public Long confirmStocktake(Long id) {
        return confirmStocktake(id, null);
    }

    @Transactional
    public Long confirmStocktake(Long id, RiskExceptionDtos.InlineAuthorization authorization) {
        StocktakeLock order = lockStocktake(id);
        if ("CONFIRMED".equals(order.status())) return id;
        if (!"DRAFT".equals(order.status())) throw new BusinessException("只有草稿盘点单可以确认");
        riskExceptionService.authorizeOrConsume(
                riskExceptionService.resolveTrigger("INVENTORY_STOCKTAKE", id, "CONFIRM"), authorization);
        for (StocktakeItemLock item : stocktakeItems(id)) {
            Batch batch = lockBatch(item.batchId());
            if (batch.quantityAvailable().compareTo(item.systemQuantity()) != 0
                    || batch.unitCost().compareTo(item.unitCost()) != 0
                    || batch.amountCost().compareTo(item.systemAmount()) != 0)
                throw new BusinessException("批次 " + batch.batchNo() + " 在盘点后发生了库存或成本变化，请重新建盘点单");
            if (item.differenceQuantity().compareTo(ZERO) < 0 && item.countedQuantity().compareTo(reserved(batch.id(), 0L)) < 0)
                throw new BusinessException("批次 " + batch.batchNo() + " 的实盘数低于已占用数，请先处理出库草稿");
            packagingService.adjustStocktakeBaseQuantity(batch.id(), batch.productId(), item.differenceQuantity(),
                    batch.baseUnit(), item.id());
            jdbc.update("update inventory_batch set quantity_available=?,amount_cost=?,status=if(?<=0,'OUT','AVAILABLE') where id=?",
                    item.countedQuantity(), item.countedAmount(), item.countedQuantity(), batch.id());
            changeStock(batch.productId(), order.warehouseId(), item.differenceQuantity(), item.costDifference());
            movement("PD", item.differenceQuantity().compareTo(ZERO) >= 0 ? "STOCKTAKE_GAIN" : "STOCKTAKE_LOSS",
                    batch, order.warehouseId(), item.differenceQuantity(), batch.unitCost(), item.costDifference(),
                    "STOCKTAKE_ITEM", item.id(), "库存盘点 " + order.orderNo());
        }
        jdbc.update("update inventory_stocktake_order set status='CONFIRMED',confirmed_at=now() where id=?", id);
        event("INVENTORY_STOCKTAKE", id, "CONFIRM", "");
        postingService.post("INVENTORY_STOCKTAKE", id, order.orderNo(), "POST", 1,
                businessDate("inventory_stocktake_order", id), 0L, 0L, "库存盘点确认",
                stocktakePostingEntries(id));
        return id;
    }

    @Transactional
    public Long cancelStocktake(Long id, String reason) {
        StocktakeLock order = lockStocktake(id);
        if ("CANCELLED".equals(order.status())) return id;
        if (!"DRAFT".equals(order.status())) throw new BusinessException("只有草稿盘点单可以作废");
        requireReason(reason);
        jdbc.update("update inventory_stocktake_order set status='CANCELLED',cancel_reason=?,cancelled_at=now() where id=?", reason.trim(), id);
        event("INVENTORY_STOCKTAKE", id, "CANCEL", reason);
        return id;
    }

    @Transactional
    public Long reverseStocktake(Long id, String reason) {
        return reverseStocktake(id, reason, null);
    }

    @Transactional
    public Long reverseStocktake(Long id, String reason, Long approvalId) {
        StocktakeLock order = lockStocktake(id);
        if ("REVERSED".equals(order.status())) return id;
        if (!"CONFIRMED".equals(order.status())) throw new BusinessException("只有已确认盘点单可以冲正");
        requireReason(reason);
        riskExceptionService.authorizeOrConsume(
                riskExceptionService.resolveTrigger("INVENTORY_STOCKTAKE", id, "REVERSE"),
                new RiskExceptionDtos.InlineAuthorization(reason, approvalId));
        for (StocktakeItemLock item : stocktakeItems(id)) {
            Batch batch = lockBatch(item.batchId());
            if (batch.quantityAvailable().compareTo(item.countedQuantity()) != 0
                    || batch.unitCost().compareTo(item.unitCost()) != 0
                    || batch.amountCost().compareTo(item.countedAmount()) != 0)
                throw new BusinessException("批次 " + batch.batchNo() + " 在盘点后已有变动，不能直接冲正；请新建调整单");
            if (item.systemQuantity().compareTo(reserved(batch.id(), 0L)) < 0)
                throw new BusinessException("冲正后的数量将低于已占用库存，请先处理出库草稿");
            BigDecimal reverseDiff = item.differenceQuantity().negate();
            packagingService.reverseStocktakePackaging(batch.id(), batch.productId(), item.id());
            jdbc.update("update inventory_batch set quantity_available=?,amount_cost=?,status=if(?<=0,'OUT','AVAILABLE') where id=?",
                    item.systemQuantity(), item.systemAmount(), item.systemQuantity(), batch.id());
            changeStock(batch.productId(), order.warehouseId(), reverseDiff, item.costDifference().negate());
            movement("PC", "STOCKTAKE_REVERSAL", batch, order.warehouseId(), reverseDiff, batch.unitCost(),
                    item.costDifference().negate(), "STOCKTAKE_REVERSAL", id, "盘点冲正 " + order.orderNo());
        }
        jdbc.update("update inventory_stocktake_order set status='REVERSED',reversal_reason=?,reversed_at=now() where id=?", reason.trim(), id);
        event("INVENTORY_STOCKTAKE", id, "REVERSE", reason);
        postingService.reverseSource("INVENTORY_STOCKTAKE", id, order.orderNo(), "REVERSE", 1,
                LocalDate.now(), 0L, List.of("POST"), reason.trim());
        return id;
    }

    public List<InventoryDtos.PurchaseReturnRow> purchaseReturns(String status, Long supplierId) {
        String sql = """
                select r.*,i.order_no inbound_no,coalesce(p.order_no,'') purchase_order_no,s.name supplier_name,w.name warehouse_name
                from purchase_return_order r join inbound_order i on i.id=r.inbound_id
                left join purchase_order p on p.id=r.purchase_order_id join partner s on s.id=r.supplier_id
                join warehouse w on w.id=r.warehouse_id
                where (?='' or r.status=?) and (?=0 or r.supplier_id=?) order by r.id desc limit 300
                """;
        return dataScopeService.filterDocumentList("PURCHASE_RETURN", jdbc.query(sql, purchaseReturnRowMapper(),
                blank(status), blank(status), supplierId == null ? 0L : supplierId, supplierId == null ? 0L : supplierId));
    }

    public InventoryDtos.PurchaseReturnDetail purchaseReturn(Long id) {
        InventoryDtos.PurchaseReturnRow header = jdbc.query("""
                select r.*,i.order_no inbound_no,coalesce(p.order_no,'') purchase_order_no,s.name supplier_name,w.name warehouse_name
                from purchase_return_order r join inbound_order i on i.id=r.inbound_id
                left join purchase_order p on p.id=r.purchase_order_id join partner s on s.id=r.supplier_id
                join warehouse w on w.id=r.warehouse_id where r.id=?
                """, rs -> rs.next() ? purchaseReturnRow(rs) : null, id);
        if (header == null) throw new BusinessException("采购退货单不存在");
        Map<String, Object> reasons = jdbc.queryForMap("select cancel_reason,reversal_reason from purchase_return_order where id=?", id);
        List<InventoryDtos.PurchaseReturnItem> items = jdbc.query("""
                select i.*,p.sku,p.name product_name,p.spec,b.batch_no from purchase_return_item i
                join product p on p.id=i.product_id join inventory_batch b on b.id=i.batch_id
                where i.return_id=? order by i.id
                """, (rs, rowNum) -> new InventoryDtos.PurchaseReturnItem(
                rs.getLong("id"), rs.getLong("inbound_item_id"), rs.getLong("product_id"), rs.getString("sku"),
                rs.getString("product_name"), rs.getString("spec"), rs.getLong("batch_id"), rs.getString("batch_no"),
                rs.getBigDecimal("quantity"), rs.getString("base_unit"), rs.getBigDecimal("inventory_unit_cost"),
                rs.getBigDecimal("inventory_amount_cost"), rs.getBigDecimal("payable_unit_cost"),
                rs.getBigDecimal("amount"), rs.getInt("price_includes_tax") == 1, rs.getBigDecimal("tax_rate"),
                rs.getBigDecimal("amount_excluding_tax"), rs.getBigDecimal("tax_amount"),
                rs.getBigDecimal("tax_rounding_difference"), parseIds(rs.getString("selected_package_instance_ids_json"))), id);
        if (!SecurityContext.require().hasPermission("inventory.cost.view")) {
            items = items.stream().map(item -> new InventoryDtos.PurchaseReturnItem(item.id(), item.inboundItemId(),
                    item.productId(), item.sku(), item.productName(), item.spec(), item.batchId(), item.batchNo(),
                    item.quantity(), item.baseUnit(), null, null, item.payableUnitCost(), item.amount(),
                    item.priceIncludesTax(), item.taxRate(), item.amountExcludingTax(), item.taxAmount(),
                    item.taxRoundingDifference(),
                    item.packageInstanceIds())).toList();
        }
        return new InventoryDtos.PurchaseReturnDetail(header, text(reasons.get("cancel_reason")), text(reasons.get("reversal_reason")),
                items, events("PURCHASE_RETURN", id));
    }

    @Transactional
    public Long createPurchaseReturn(InventoryDtos.PurchaseReturnCreate req) {
        InboundSource inbound = lockInbound(req.inboundId());
        PartnerBusinessGuard.requireEnabledRole(jdbc, inbound.supplierId(), "SUPPLIER");
        if (!"STOCKED".equals(inbound.status())) throw new BusinessException("只能对已入库单办理采购退货");
        requireReason(req.reason());
        dataScopeService.assertMasterAllowed("PARTNER", inbound.supplierId());
        dataScopeService.assertMasterAllowed("WAREHOUSE", inbound.warehouseId());
        BigDecimal totalQty = ZERO, totalAmount = ZERO;
        List<PreparedPurchaseReturnItem> prepared = new ArrayList<>();
        HashSet<Long> seenInboundItems = new HashSet<>();
        for (InventoryDtos.PurchaseReturnItemCreate item : req.items()) {
            if (!seenInboundItems.add(item.inboundItemId())) throw new BusinessException("同一原入库明细不能在退货单中重复选择");
            InboundItemSource source = lockInboundItem(item.inboundItemId(), inbound.id(), item.batchId());
            Batch batch = lockBatch(item.batchId());
            requirePositive(item.quantity(), "退货数量");
            if (!Objects.equals(batch.warehouseId(), inbound.warehouseId()) || !Objects.equals(batch.productId(), source.productId()))
                throw new BusinessException("退货批次与原入库明细不一致");
            ensureFreeStock(batch, item.quantity(), 0L);
            BigDecimal returned = confirmedReturnedQuantity(source.id(), 0L);
            if (returned.add(item.quantity()).compareTo(source.quantity()) > 0) throw new BusinessException("累计采购退货数量不能超过原入库数量");
            PackagingDtos.Plan plan = batchPlan(batch, item.quantity());
            BigDecimal amount = source.amount().multiply(item.quantity()).divide(source.quantity(), 2, RoundingMode.HALF_UP);
            BigDecimal net = source.amountExcludingTax().multiply(item.quantity()).divide(source.quantity(), 2, RoundingMode.HALF_UP);
            BigDecimal tax = amount.subtract(net).setScale(2, RoundingMode.HALF_UP);
            prepared.add(new PreparedPurchaseReturnItem(source, batch, item, plan, amount, net, tax,
                    tax.subtract(net.multiply(source.taxRate()).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP))));
            totalQty = totalQty.add(item.quantity()); totalAmount = totalAmount.add(amount);
        }
        Long id = insert("""
                insert into purchase_return_order
                (order_no,inbound_id,purchase_order_id,supplier_id,warehouse_id,business_date,status,total_quantity,total_amount,reason,memo,created_by)
                values (?,?,?,?,?,?,'DRAFT',?,?,?,?,?)
                """, nextNo("CT"), inbound.id(), inbound.purchaseOrderId(), inbound.supplierId(), inbound.warehouseId(),
                Date.valueOf(req.businessDate()), totalQty, totalAmount, req.reason().trim(), blank(req.memo()), SecurityContext.require().userId());
        for (PreparedPurchaseReturnItem row : prepared) insert("""
                insert into purchase_return_item
                (return_id,inbound_item_id,product_id,batch_id,quantity,base_unit,inventory_unit_cost,inventory_amount_cost,
                 payable_unit_cost,price_includes_tax,tax_rate,amount,amount_excluding_tax,tax_amount,tax_rounding_difference,
                 packaging_plan_json,selected_package_instance_ids_json)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, id, row.source().id(), row.batch().productId(), row.batch().id(), row.request().quantity(),
                row.batch().baseUnit(), row.batch().unitCost(), removalCost(row.batch(), row.request().quantity()),
                row.source().amount().divide(row.source().quantity(), 4, RoundingMode.HALF_UP), row.source().priceIncludesTax(),
                row.source().taxRate(), row.amount(), row.amountExcludingTax(), row.taxAmount(), row.roundingDifference(),
                row.plan().snapshotJson(), jsonIds(row.request().packageInstanceIds()));
        refreshPurchaseReturnTaxHeader(id);
        event("PURCHASE_RETURN", id, "CREATE", req.reason());
        dataScopeService.stampDocumentOwner("PURCHASE_RETURN", id);
        return id;
    }

    @Transactional
    public Long updatePurchaseReturn(Long id, InventoryDtos.PurchaseReturnCreate req) {
        PurchaseReturnLock existing = lockPurchaseReturn(id);
        if (!"DRAFT".equals(existing.status())) throw new BusinessException("只有草稿采购退货单可以修改");
        InboundSource inbound = lockInbound(req.inboundId());
        if (!"STOCKED".equals(inbound.status())) throw new BusinessException("只能对已入库单办理采购退货");
        requireReason(req.reason());
        dataScopeService.assertMasterAllowed("PARTNER", inbound.supplierId());
        dataScopeService.assertMasterAllowed("WAREHOUSE", inbound.warehouseId());
        BigDecimal totalQty = ZERO, totalAmount = ZERO;
        List<PreparedPurchaseReturnItem> prepared = new ArrayList<>();
        HashSet<Long> seen = new HashSet<>();
        for (InventoryDtos.PurchaseReturnItemCreate item : req.items()) {
            if (!seen.add(item.inboundItemId())) throw new BusinessException("同一原入库明细不能在退货单中重复选择");
            InboundItemSource source = lockInboundItem(item.inboundItemId(), inbound.id(), item.batchId());
            Batch batch = lockBatch(item.batchId()); requirePositive(item.quantity(), "退货数量");
            ensureFreeStock(batch, item.quantity(), 0L);
            if (confirmedReturnedQuantity(source.id(), id).add(item.quantity()).compareTo(source.quantity()) > 0)
                throw new BusinessException("累计采购退货数量不能超过原入库数量");
            PackagingDtos.Plan plan = batchPlan(batch, item.quantity());
            BigDecimal amount = source.amount().multiply(item.quantity()).divide(source.quantity(), 2, RoundingMode.HALF_UP);
            BigDecimal net = source.amountExcludingTax().multiply(item.quantity()).divide(source.quantity(), 2, RoundingMode.HALF_UP);
            BigDecimal tax = amount.subtract(net).setScale(2, RoundingMode.HALF_UP);
            prepared.add(new PreparedPurchaseReturnItem(source, batch, item, plan, amount, net, tax,
                    tax.subtract(net.multiply(source.taxRate()).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP))));
            totalQty = totalQty.add(item.quantity()); totalAmount = totalAmount.add(amount);
        }
        jdbc.update("""
                update purchase_return_order set inbound_id=?,purchase_order_id=?,supplier_id=?,warehouse_id=?,business_date=?,
                  total_quantity=?,total_amount=?,reason=?,memo=? where id=? and status='DRAFT'
                """, inbound.id(), inbound.purchaseOrderId(), inbound.supplierId(), inbound.warehouseId(), Date.valueOf(req.businessDate()),
                totalQty, totalAmount, req.reason().trim(), blank(req.memo()), id);
        jdbc.update("delete from purchase_return_item where return_id=?", id);
        for (PreparedPurchaseReturnItem row : prepared) insert("""
                insert into purchase_return_item
                (return_id,inbound_item_id,product_id,batch_id,quantity,base_unit,inventory_unit_cost,inventory_amount_cost,
                 payable_unit_cost,price_includes_tax,tax_rate,amount,amount_excluding_tax,tax_amount,tax_rounding_difference,
                 packaging_plan_json,selected_package_instance_ids_json) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, id, row.source().id(), row.batch().productId(), row.batch().id(), row.request().quantity(),
                row.batch().baseUnit(), row.batch().unitCost(), removalCost(row.batch(), row.request().quantity()),
                row.source().amount().divide(row.source().quantity(), 4, RoundingMode.HALF_UP), row.source().priceIncludesTax(),
                row.source().taxRate(), row.amount(), row.amountExcludingTax(), row.taxAmount(), row.roundingDifference(),
                row.plan().snapshotJson(), jsonIds(row.request().packageInstanceIds()));
        refreshPurchaseReturnTaxHeader(id);
        event("PURCHASE_RETURN", id, "UPDATE", req.reason());
        return id;
    }

    private void refreshPurchaseReturnTaxHeader(Long id) {
        jdbc.update("""
                update purchase_return_order r set
                  price_includes_tax=coalesce((select min(i.price_includes_tax) from purchase_return_item i where i.return_id=r.id),1),
                  tax_rate_summary=coalesce((select group_concat(distinct concat(format(i.tax_rate,4),'%') order by i.tax_rate separator ';')
                    from purchase_return_item i where i.return_id=r.id),'0.0000%'),
                  tax_rule_snapshot='SOURCE_INBOUND_PROPORTION_HALF_UP_V1',
                  total_amount=coalesce((select sum(i.amount) from purchase_return_item i where i.return_id=r.id),0),
                  amount_excluding_tax=coalesce((select sum(i.amount_excluding_tax) from purchase_return_item i where i.return_id=r.id),0),
                  tax_amount=coalesce((select sum(i.tax_amount) from purchase_return_item i where i.return_id=r.id),0),
                  tax_rounding_difference=coalesce((select sum(i.tax_rounding_difference) from purchase_return_item i where i.return_id=r.id),0)
                where r.id=?
                """, id);
    }

    @Transactional
    public Long confirmPurchaseReturn(Long id) {
        PurchaseReturnLock order = lockPurchaseReturn(id);
        if ("CONFIRMED".equals(order.status())) return id;
        if (!"DRAFT".equals(order.status())) throw new BusinessException("只有草稿采购退货单可以确认");
        PartnerBusinessGuard.requireEnabledRole(jdbc, order.supplierId(), "SUPPLIER");
        BigDecimal confirmedReturnAmount = ZERO;
        for (PurchaseReturnItemLock item : purchaseReturnItems(id)) {
            Batch batch = lockBatch(item.batchId());
            ensureFreeStock(batch, item.quantity(), 0L);
            BigDecimal already = confirmedReturnedQuantity(item.inboundItemId(), id);
            InboundItemSource source = lockInboundItem(item.inboundItemId(), order.inboundId(), item.batchId());
            if (already.add(item.quantity()).compareTo(source.quantity()) > 0) throw new BusinessException("累计采购退货数量超过原入库数量");
            BigDecimal priorAmount = confirmedReturnedAmount(item.inboundItemId(), id);
            BigDecimal payableAmount = already.add(item.quantity()).compareTo(source.quantity()) == 0
                    ? source.amount().subtract(priorAmount)
                    : source.amount().multiply(item.quantity()).divide(source.quantity(), 2, RoundingMode.HALF_UP);
            BigDecimal priorNet = confirmedReturnedValue(item.inboundItemId(), id, "amount_excluding_tax");
            BigDecimal payableNet = already.add(item.quantity()).compareTo(source.quantity()) == 0
                    ? source.amountExcludingTax().subtract(priorNet)
                    : source.amountExcludingTax().multiply(item.quantity()).divide(source.quantity(), 2, RoundingMode.HALF_UP);
            BigDecimal payableTax = payableAmount.subtract(payableNet).setScale(2, RoundingMode.HALF_UP);
            BigDecimal roundingDifference = payableTax.subtract(payableNet.multiply(source.taxRate())
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP));
            confirmedReturnAmount = confirmedReturnAmount.add(payableAmount);
            jdbc.update("""
                    update purchase_return_item set payable_unit_cost=?,amount=?,amount_excluding_tax=?,tax_amount=?,
                      tax_rounding_difference=? where id=?
                    """, source.amount().divide(source.quantity(), 4, RoundingMode.HALF_UP), payableAmount,
                    payableNet, payableTax, roundingDifference, item.id());
            PackagingDtos.Plan plan = batchPlanFromSnapshot(batch, item.quantity(), item.packagingPlanJson());
            packagingService.consumePurchaseReturnPackaging(batch.id(), batch.productId(), plan,
                    parseIds(item.selectedIdsJson()), item.id());
            BigDecimal inventoryAmount = removalCost(batch, item.quantity());
            BigDecimal landedCost = activeLandedUnitAmount(item.batchId(), item.inboundItemId())
                    .multiply(item.quantity()).setScale(2, RoundingMode.HALF_UP).min(inventoryAmount);
            jdbc.update("update purchase_return_item set inventory_unit_cost=?,inventory_amount_cost=?,landed_cost_amount=? where id=?",
                    batch.unitCost(), inventoryAmount, landedCost, item.id());
            decreaseBatch(batch, item.quantity(), inventoryAmount);
            changeStock(batch.productId(), order.warehouseId(), item.quantity().negate(), inventoryAmount.negate());
            movement("CT", "PURCHASE_RETURN", batch, order.warehouseId(), item.quantity().negate(), batch.unitCost(),
                    inventoryAmount.negate(), "PURCHASE_RETURN_ITEM", item.id(), "采购退货 " + order.orderNo());
        }
        refreshPurchaseReturnTaxHeader(id);
        BigDecimal outstanding = sourcePayableOutstanding(order.purchaseOrderId(), order.inboundId(), id);
        BigDecimal payableReduction = confirmedReturnAmount.min(outstanding.max(ZERO));
        BigDecimal supplierCredit = confirmedReturnAmount.subtract(payableReduction);
        jdbc.update("""
                update purchase_return_order set status='CONFIRMED',total_amount=?,payable_reduction_amount=?,supplier_credit_amount=?,confirmed_at=now(6)
                where id=?
                """, confirmedReturnAmount, payableReduction, supplierCredit, id);
        if (supplierCredit.compareTo(ZERO) > 0)
            changePartnerAccount(order.supplierId(), "SUPPLIER_PREPAY", supplierCredit, "PURCHASE_RETURN", id);
        if (payableReduction.compareTo(ZERO) > 0)
            payableMovement("PR", order.supplierId(), "PURCHASE_RETURN", id, "DECREASE", payableReduction,
                    "采购退货冲减应付 " + order.orderNo());
        event("PURCHASE_RETURN", id, "CONFIRM", "");
        postingService.post("PURCHASE_RETURN", id, order.orderNo(), "POST", 1,
                businessDate("purchase_return_order", id), order.supplierId(), 0L, "采购退货确认",
                purchaseReturnPostingEntries(id));
        return id;
    }

    @Transactional
    public Long cancelPurchaseReturn(Long id, String reason) {
        PurchaseReturnLock order = lockPurchaseReturn(id);
        if ("CANCELLED".equals(order.status())) return id;
        if (!"DRAFT".equals(order.status())) throw new BusinessException("只有草稿采购退货单可以作废");
        requireReason(reason);
        jdbc.update("update purchase_return_order set status='CANCELLED',cancel_reason=?,cancelled_at=now() where id=?", reason.trim(), id);
        event("PURCHASE_RETURN", id, "CANCEL", reason);
        return id;
    }

    @Transactional
    public Long reversePurchaseReturn(Long id, String reason) {
        PurchaseReturnLock order = lockPurchaseReturn(id);
        if ("REVERSED".equals(order.status())) return id;
        if (!"CONFIRMED".equals(order.status())) throw new BusinessException("只有已确认采购退货单可以冲正");
        requireReason(reason);
        Long laterAllocation = jdbc.queryForObject("""
                select count(*) from purchase_return_item ri
                join landed_cost_allocation_item li on li.inbound_item_id=ri.inbound_item_id
                join landed_cost_allocation a on a.id=li.allocation_id
                join purchase_return_order r on r.id=ri.return_id
                where ri.return_id=? and a.status='CONFIRMED' and a.confirmed_at>r.confirmed_at
                """, Long.class, id);
        if (laterAllocation != null && laterAllocation > 0)
            throw new BusinessException("采购退货确认后存在采购费用分摊，请先冲正后到的费用分摊");
        if (order.supplierCreditAmount().compareTo(ZERO) > 0)
            changePartnerAccount(order.supplierId(), "SUPPLIER_PREPAY", order.supplierCreditAmount().negate(), "PURCHASE_RETURN_REVERSAL", id);
        for (PurchaseReturnItemLock item : purchaseReturnItems(id)) {
            Batch batch = lockBatch(item.batchId());
            if (batch.unitCost().compareTo(item.inventoryUnitCost()) != 0)
                throw new BusinessException("退货批次成本已发生变化，不能直接冲正；请先处理后续成本调整");
            packagingService.restorePurchaseReturnPackaging(batch.id(), batch.productId(), parseIds(item.selectedIdsJson()), item.id());
            BigDecimal inventoryAmount = item.inventoryAmountCost();
            increaseBatch(batch, item.quantity(), inventoryAmount);
            changeStock(batch.productId(), order.warehouseId(), item.quantity(), inventoryAmount);
            movement("TC", "PURCHASE_RETURN_REVERSAL", batch, order.warehouseId(), item.quantity(), batch.unitCost(),
                    inventoryAmount, "PURCHASE_RETURN_REVERSAL", id, "采购退货冲正 " + order.orderNo());
        }
        jdbc.update("update purchase_return_order set status='REVERSED',reversal_reason=?,reversed_at=now() where id=?", reason.trim(), id);
        if (order.payableReductionAmount().compareTo(ZERO) > 0)
            payableMovement("PR", order.supplierId(), "PURCHASE_RETURN_REVERSAL", id, "INCREASE", order.payableReductionAmount(),
                    "采购退货冲正恢复应付 " + order.orderNo());
        event("PURCHASE_RETURN", id, "REVERSE", reason);
        postingService.reverseSource("PURCHASE_RETURN", id, order.orderNo(), "REVERSE", 1,
                LocalDate.now(), order.supplierId(), List.of("POST"), reason.trim());
        return id;
    }

    public List<InventoryDtos.CostAdjustmentRow> costAdjustments(String status) {
        return dataScopeService.filterDocumentList("INVENTORY_COST_ADJUSTMENT", jdbc.query("""
                select * from inventory_cost_adjustment where (?='' or status=?) order by id desc limit 300
                """, costAdjustmentRowMapper(), blank(status), blank(status)));
    }

    public InventoryDtos.CostAdjustmentDetail costAdjustment(Long id) {
        InventoryDtos.CostAdjustmentRow header = jdbc.query("select * from inventory_cost_adjustment where id=?",
                rs -> rs.next() ? costAdjustmentRow(rs) : null, id);
        if (header == null) throw new BusinessException("成本调整单不存在");
        Map<String, Object> reasons = jdbc.queryForMap("select cancel_reason,reversal_reason from inventory_cost_adjustment where id=?", id);
        List<InventoryDtos.CostAdjustmentItem> items = jdbc.query("""
                select i.*,p.sku,p.name product_name,p.spec,w.name warehouse_name,b.batch_no
                from inventory_cost_adjustment_item i join product p on p.id=i.product_id
                join warehouse w on w.id=i.warehouse_id join inventory_batch b on b.id=i.batch_id
                where i.adjustment_id=? order by i.id
                """, (rs, rowNum) -> new InventoryDtos.CostAdjustmentItem(
                rs.getLong("id"), rs.getLong("product_id"), rs.getString("sku"), rs.getString("product_name"),
                rs.getString("spec"), rs.getLong("warehouse_id"), rs.getString("warehouse_name"),
                rs.getLong("batch_id"), rs.getString("batch_no"), rs.getBigDecimal("quantity_snapshot"),
                rs.getBigDecimal("old_unit_cost"), rs.getBigDecimal("new_unit_cost"),
                rs.getBigDecimal("old_amount_cost"), rs.getBigDecimal("new_amount_cost"), rs.getBigDecimal("difference_amount")), id);
        return new InventoryDtos.CostAdjustmentDetail(header, text(reasons.get("cancel_reason")), text(reasons.get("reversal_reason")),
                items, events("INVENTORY_COST_ADJUSTMENT", id));
    }

    @Transactional
    public Long createCostAdjustment(InventoryDtos.CostAdjustmentCreate req) {
        requireReason(req.reason());
        BigDecimal total = ZERO;
        List<PreparedCostItem> prepared = new ArrayList<>();
        HashSet<Long> seenBatches = new HashSet<>();
        for (InventoryDtos.CostAdjustmentItemCreate item : req.items()) {
            if (!seenBatches.add(item.batchId())) throw new BusinessException("同一批次不能重复出现在成本调整单中");
            Batch batch = lockBatch(item.batchId());
            dataScopeService.assertMasterAllowed("PRODUCT", batch.productId());
            dataScopeService.assertMasterAllowed("WAREHOUSE", batch.warehouseId());
            if (batch.quantityAvailable().compareTo(ZERO) <= 0)
                throw new BusinessException("零结存批次无需调整成本");
            if (inTransitQuantity(batch.id()).compareTo(ZERO) > 0)
                throw new BusinessException("批次 " + batch.batchNo() + " 存在调拨在途数量，收货或冲正完成前不能调整成本");
            BigDecimal newCost = item.newUnitCost().setScale(4, RoundingMode.HALF_UP);
            BigDecimal oldAmount = batch.amountCost();
            BigDecimal newAmount = money(batch.quantityAvailable().multiply(newCost));
            BigDecimal diff = newAmount.subtract(oldAmount);
            prepared.add(new PreparedCostItem(batch, newCost, oldAmount, newAmount, diff));
            total = total.add(diff);
        }
        Long id = insert("""
                insert into inventory_cost_adjustment
                (adjustment_no,business_date,status,total_difference_amount,reason,memo,created_by)
                values (?,?,'DRAFT',?,?,?,?)
                """, nextNo("CB"), Date.valueOf(req.businessDate()), total, req.reason().trim(), blank(req.memo()),
                SecurityContext.require().userId());
        for (PreparedCostItem row : prepared) insert("""
                insert into inventory_cost_adjustment_item
                (adjustment_id,product_id,warehouse_id,batch_id,quantity_snapshot,old_unit_cost,new_unit_cost,
                 old_amount_cost,new_amount_cost,difference_amount)
                values (?,?,?,?,?,?,?,?,?,?)
                """, id, row.batch().productId(), row.batch().warehouseId(), row.batch().id(), row.batch().quantityAvailable(),
                row.batch().unitCost(), row.newCost(), row.oldAmount(), row.newAmount(), row.difference());
        event("INVENTORY_COST_ADJUSTMENT", id, "CREATE", req.reason());
        dataScopeService.stampDocumentOwner("INVENTORY_COST_ADJUSTMENT", id);
        return id;
    }

    @Transactional
    public Long updateCostAdjustment(Long id, InventoryDtos.CostAdjustmentCreate req) {
        CostAdjustmentLock existing = lockCostAdjustment(id);
        if (!"DRAFT".equals(existing.status())) throw new BusinessException("只有草稿成本调整单可以修改");
        requireReason(req.reason());
        BigDecimal total = ZERO;
        List<PreparedCostItem> prepared = new ArrayList<>();
        HashSet<Long> seen = new HashSet<>();
        for (InventoryDtos.CostAdjustmentItemCreate item : req.items()) {
            if (!seen.add(item.batchId())) throw new BusinessException("同一批次不能重复出现在成本调整单中");
            Batch batch = lockBatch(item.batchId());
            if (batch.quantityAvailable().compareTo(ZERO) <= 0) throw new BusinessException("零结存批次无需调整成本");
            if (inTransitQuantity(batch.id()).compareTo(ZERO) > 0) throw new BusinessException("批次存在调拨在途数量，不能调整成本");
            BigDecimal newCost = item.newUnitCost().setScale(4, RoundingMode.HALF_UP);
            BigDecimal oldAmount = batch.amountCost();
            BigDecimal newAmount = money(batch.quantityAvailable().multiply(newCost));
            BigDecimal diff = newAmount.subtract(oldAmount);
            prepared.add(new PreparedCostItem(batch, newCost, oldAmount, newAmount, diff)); total = total.add(diff);
        }
        jdbc.update("""
                update inventory_cost_adjustment set business_date=?,total_difference_amount=?,reason=?,memo=?
                where id=? and status='DRAFT'
                """, Date.valueOf(req.businessDate()), total, req.reason().trim(), blank(req.memo()), id);
        jdbc.update("delete from inventory_cost_adjustment_item where adjustment_id=?", id);
        for (PreparedCostItem row : prepared) insert("""
                insert into inventory_cost_adjustment_item
                (adjustment_id,product_id,warehouse_id,batch_id,quantity_snapshot,old_unit_cost,new_unit_cost,
                 old_amount_cost,new_amount_cost,difference_amount) values (?,?,?,?,?,?,?,?,?,?)
                """, id, row.batch().productId(), row.batch().warehouseId(), row.batch().id(), row.batch().quantityAvailable(),
                row.batch().unitCost(), row.newCost(), row.oldAmount(), row.newAmount(), row.difference());
        event("INVENTORY_COST_ADJUSTMENT", id, "UPDATE", req.reason());
        return id;
    }

    @Transactional
    public Long confirmCostAdjustment(Long id) {
        return confirmCostAdjustment(id, null);
    }

    @Transactional
    public Long confirmCostAdjustment(Long id, RiskExceptionDtos.InlineAuthorization authorization) {
        CostAdjustmentLock order = lockCostAdjustment(id);
        if ("CONFIRMED".equals(order.status())) return id;
        if (!"DRAFT".equals(order.status())) throw new BusinessException("只有草稿成本调整单可以确认");
        riskExceptionService.authorizeOrConsume(
                riskExceptionService.resolveTrigger("INVENTORY_COST_ADJUSTMENT", id, "CONFIRM"), authorization);
        for (CostItemLock item : costItems(id)) {
            Batch batch = lockBatch(item.batchId());
            if (batch.quantityAvailable().compareTo(item.quantitySnapshot()) != 0
                    || batch.unitCost().compareTo(item.oldUnitCost()) != 0
                    || batch.amountCost().compareTo(item.oldAmountCost()) != 0)
                throw new BusinessException("批次 " + batch.batchNo() + " 的数量或成本已变化，请重新建成本调整单");
            jdbc.update("update inventory_batch set unit_cost=?,amount_cost=? where id=?", item.newUnitCost(), item.newAmountCost(), batch.id());
            changeStock(batch.productId(), batch.warehouseId(), ZERO, item.differenceAmount());
            movement("CB", "COST_ADJUSTMENT", batch, batch.warehouseId(), ZERO, item.newUnitCost(),
                    item.differenceAmount(), "COST_ADJUSTMENT_ITEM", item.id(), "批次成本调整 " + order.adjustmentNo());
        }
        jdbc.update("update inventory_cost_adjustment set status='CONFIRMED',confirmed_at=now(6) where id=?", id);
        event("INVENTORY_COST_ADJUSTMENT", id, "CONFIRM", "");
        postingService.post("INVENTORY_COST_ADJUSTMENT", id, order.adjustmentNo(), "POST", 1,
                businessDate("inventory_cost_adjustment", id), 0L, 0L, "库存成本调整确认",
                costAdjustmentPostingEntries(id));
        return id;
    }

    @Transactional
    public Long cancelCostAdjustment(Long id, String reason) {
        CostAdjustmentLock order = lockCostAdjustment(id);
        if ("CANCELLED".equals(order.status())) return id;
        if (!"DRAFT".equals(order.status())) throw new BusinessException("只有草稿成本调整单可以作废");
        requireReason(reason);
        jdbc.update("update inventory_cost_adjustment set status='CANCELLED',cancel_reason=?,cancelled_at=now() where id=?", reason.trim(), id);
        event("INVENTORY_COST_ADJUSTMENT", id, "CANCEL", reason);
        return id;
    }

    @Transactional
    public Long reverseCostAdjustment(Long id, String reason) {
        return reverseCostAdjustment(id, reason, null);
    }

    @Transactional
    public Long reverseCostAdjustment(Long id, String reason, Long approvalId) {
        CostAdjustmentLock order = lockCostAdjustment(id);
        if ("REVERSED".equals(order.status())) return id;
        if (!"CONFIRMED".equals(order.status())) throw new BusinessException("只有已确认成本调整单可以冲正");
        requireReason(reason);
        riskExceptionService.authorizeOrConsume(
                riskExceptionService.resolveTrigger("INVENTORY_COST_ADJUSTMENT", id, "REVERSE"),
                new RiskExceptionDtos.InlineAuthorization(reason, approvalId));
        for (CostItemLock item : costItems(id)) {
            Batch batch = lockBatch(item.batchId());
            if (batch.quantityAvailable().compareTo(item.quantitySnapshot()) != 0
                    || batch.unitCost().compareTo(item.newUnitCost()) != 0
                    || batch.amountCost().compareTo(item.newAmountCost()) != 0)
                throw new BusinessException("批次 " + batch.batchNo() + " 调整后已有数量或成本变化，不能追溯冲正；请新建调整单");
            jdbc.update("update inventory_batch set unit_cost=?,amount_cost=? where id=?", item.oldUnitCost(), item.oldAmountCost(), batch.id());
            changeStock(batch.productId(), batch.warehouseId(), ZERO, item.differenceAmount().negate());
            movement("CC", "COST_ADJUSTMENT_REVERSAL", batch, batch.warehouseId(), ZERO, item.oldUnitCost(),
                    item.differenceAmount().negate(), "COST_ADJUSTMENT_REVERSAL", id, "批次成本调整冲正 " + order.adjustmentNo());
        }
        jdbc.update("update inventory_cost_adjustment set status='REVERSED',reversal_reason=?,reversed_at=now() where id=?", reason.trim(), id);
        event("INVENTORY_COST_ADJUSTMENT", id, "REVERSE", reason);
        postingService.reverseSource("INVENTORY_COST_ADJUSTMENT", id, order.adjustmentNo(), "REVERSE", 1,
                LocalDate.now(), 0L, List.of("POST"), reason.trim());
        return id;
    }

    private List<BusinessPostingDtos.EntryDraft> transferDispatchEntries(Long transferId) {
        List<BusinessPostingDtos.EntryDraft> entries = new ArrayList<>();
        jdbc.query("""
                select i.id,i.product_id,i.source_batch_id,i.quantity,i.base_unit,i.amount_cost,o.from_warehouse_id
                from inventory_transfer_item i join inventory_transfer_order o on o.id=i.transfer_id
                where i.transfer_id=? order by i.id
                """, (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
            entries.add(new BusinessPostingDtos.EntryDraft(
                    "INVENTORY_ON_HAND", "DECREASE", 0L, rs.getLong("product_id"),
                    rs.getLong("from_warehouse_id"), rs.getLong("source_batch_id"), "TRANSFER_ITEM", rs.getLong("id"),
                    rs.getBigDecimal("quantity"), rs.getString("base_unit"), rs.getBigDecimal("amount_cost"),
                    "调拨发出减少调出仓结存"));
            entries.add(new BusinessPostingDtos.EntryDraft(
                    "INVENTORY_IN_TRANSIT", "INCREASE", 0L, rs.getLong("product_id"),
                    0L, rs.getLong("source_batch_id"), "TRANSFER_ITEM", rs.getLong("id"),
                    rs.getBigDecimal("quantity"), rs.getString("base_unit"), rs.getBigDecimal("amount_cost"),
                    "调拨发出形成在途库存"));
        }, transferId);
        return entries;
    }

    private List<BusinessPostingDtos.EntryDraft> transferReceiveEntries(Long transferId) {
        List<BusinessPostingDtos.EntryDraft> entries = new ArrayList<>();
        jdbc.query("""
                select i.id,i.product_id,i.source_batch_id,i.target_batch_id,i.quantity,i.base_unit,i.amount_cost,
                       o.to_warehouse_id
                from inventory_transfer_item i join inventory_transfer_order o on o.id=i.transfer_id
                where i.transfer_id=? order by i.id
                """, (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
            entries.add(new BusinessPostingDtos.EntryDraft(
                    "INVENTORY_IN_TRANSIT", "DECREASE", 0L, rs.getLong("product_id"),
                    0L, rs.getLong("source_batch_id"), "TRANSFER_ITEM", rs.getLong("id"),
                    rs.getBigDecimal("quantity"), rs.getString("base_unit"), rs.getBigDecimal("amount_cost"),
                    "调拨收货冲减在途库存"));
            entries.add(new BusinessPostingDtos.EntryDraft(
                    "INVENTORY_ON_HAND", "INCREASE", 0L, rs.getLong("product_id"),
                    rs.getLong("to_warehouse_id"), rs.getLong("target_batch_id"), "TRANSFER_ITEM", rs.getLong("id"),
                    rs.getBigDecimal("quantity"), rs.getString("base_unit"), rs.getBigDecimal("amount_cost"),
                    "调拨收货增加调入仓结存"));
        }, transferId);
        return entries;
    }

    private List<BusinessPostingDtos.EntryDraft> stocktakePostingEntries(Long stocktakeId) {
        List<BusinessPostingDtos.EntryDraft> entries = new ArrayList<>();
        jdbc.query("""
                select i.id,i.product_id,i.batch_id,i.difference_quantity,i.base_unit,i.cost_difference,o.warehouse_id
                from inventory_stocktake_item i join inventory_stocktake_order o on o.id=i.stocktake_id
                where i.stocktake_id=? order by i.id
                """, (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
            BigDecimal quantity = rs.getBigDecimal("difference_quantity");
            BigDecimal amount = rs.getBigDecimal("cost_difference");
            if (quantity.compareTo(ZERO) != 0) entries.add(new BusinessPostingDtos.EntryDraft(
                    "INVENTORY_ON_HAND", direction(quantity), 0L, rs.getLong("product_id"),
                    rs.getLong("warehouse_id"), rs.getLong("batch_id"), "STOCKTAKE_ITEM", rs.getLong("id"),
                    quantity.abs(), rs.getString("base_unit"), BigDecimal.ZERO, "盘盈盘亏调整实际结存"));
            if (amount.compareTo(ZERO) != 0) entries.add(new BusinessPostingDtos.EntryDraft(
                    "INVENTORY_COST", direction(amount), 0L, rs.getLong("product_id"),
                    rs.getLong("warehouse_id"), rs.getLong("batch_id"), "STOCKTAKE_ITEM", rs.getLong("id"),
                    BigDecimal.ZERO, "", amount.abs(), "盘盈盘亏调整库存成本"));
        }, stocktakeId);
        return entries;
    }

    private List<BusinessPostingDtos.EntryDraft> purchaseReturnPostingEntries(Long returnId) {
        List<BusinessPostingDtos.EntryDraft> entries = new ArrayList<>();
        jdbc.query("""
                select i.id,i.product_id,i.batch_id,i.quantity,i.base_unit,i.inventory_amount_cost,i.landed_cost_amount,
                       r.warehouse_id,r.supplier_id
                from purchase_return_item i join purchase_return_order r on r.id=i.return_id
                where i.return_id=? order by i.id
                """, (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
            entries.add(new BusinessPostingDtos.EntryDraft(
                    "INVENTORY_ON_HAND", "DECREASE", rs.getLong("supplier_id"), rs.getLong("product_id"),
                    rs.getLong("warehouse_id"), rs.getLong("batch_id"), "PURCHASE_RETURN_ITEM", rs.getLong("id"),
                    rs.getBigDecimal("quantity"), rs.getString("base_unit"), BigDecimal.ZERO, "采购退货减少库存"));
            BigDecimal inventoryAmount = rs.getBigDecimal("inventory_amount_cost");
            BigDecimal landedCost = rs.getBigDecimal("landed_cost_amount");
            BigDecimal baseCost = inventoryAmount.subtract(landedCost);
            if (baseCost.compareTo(ZERO) > 0) entries.add(new BusinessPostingDtos.EntryDraft(
                    "INVENTORY_COST", "DECREASE", rs.getLong("supplier_id"), rs.getLong("product_id"),
                    rs.getLong("warehouse_id"), rs.getLong("batch_id"), "PURCHASE_RETURN_ITEM", rs.getLong("id"),
                    BigDecimal.ZERO, "", baseCost, "采购退货结转原采购库存成本"));
            if (landedCost.compareTo(ZERO) > 0) entries.add(new BusinessPostingDtos.EntryDraft(
                    "LANDED_COST_RETURN_EXPENSE", "INCREASE", rs.getLong("supplier_id"), rs.getLong("product_id"),
                    rs.getLong("warehouse_id"), rs.getLong("batch_id"), "PURCHASE_RETURN_ITEM", rs.getLong("id"),
                    BigDecimal.ZERO, "", landedCost, "采购退货将附加费用转回期间成本"));
        }, returnId);
        Map<String, Object> header = jdbc.queryForMap("""
                select supplier_id,payable_reduction_amount,supplier_credit_amount
                from purchase_return_order where id=?
                """, returnId);
        long supplierId = ((Number) header.get("supplier_id")).longValue();
        BigDecimal payable = (BigDecimal) header.get("payable_reduction_amount");
        BigDecimal credit = (BigDecimal) header.get("supplier_credit_amount");
        if (payable.compareTo(ZERO) > 0) entries.add(new BusinessPostingDtos.EntryDraft(
                "PAYABLE", "DECREASE", supplierId, 0L, 0L, 0L, "PURCHASE_RETURN", returnId,
                ZERO, "", payable, "采购退货冲减应付"));
        if (credit.compareTo(ZERO) > 0) entries.add(new BusinessPostingDtos.EntryDraft(
                "SUPPLIER_PREPAY", "INCREASE", supplierId, 0L, 0L, 0L, "PURCHASE_RETURN", returnId,
                ZERO, "", credit, "采购退货形成供应商预付款余额"));
        return entries;
    }

    private List<BusinessPostingDtos.EntryDraft> costAdjustmentPostingEntries(Long adjustmentId) {
        List<BusinessPostingDtos.EntryDraft> entries = new ArrayList<>();
        jdbc.query("""
                select id,product_id,warehouse_id,batch_id,difference_amount
                from inventory_cost_adjustment_item where adjustment_id=? order by id
                """, (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
            BigDecimal amount = rs.getBigDecimal("difference_amount");
            if (amount.compareTo(ZERO) != 0) entries.add(new BusinessPostingDtos.EntryDraft(
                    "INVENTORY_COST", direction(amount), 0L, rs.getLong("product_id"),
                    rs.getLong("warehouse_id"), rs.getLong("batch_id"), "COST_ADJUSTMENT_ITEM", rs.getLong("id"),
                    ZERO, "", amount.abs(), "库存成本调整"));
        }, adjustmentId);
        return entries;
    }

    private List<BusinessPostingDtos.EntryDraft> nonTradePostingEntries(
            NonTradeLock document, List<NonTradeItemLock> items) {
        String factDirection = "IN".equals(document.direction()) ? "INCREASE" : "DECREASE";
        String memo = nonTradeLabel(document.businessType());
        List<BusinessPostingDtos.EntryDraft> entries = new ArrayList<>();
        for (NonTradeItemLock item : items) {
            entries.add(new BusinessPostingDtos.EntryDraft(
                        "INVENTORY_ON_HAND", factDirection, 0L, item.productId(),
                        item.warehouseId(), item.batchId(), "INVENTORY_NON_TRADE_ITEM", item.id(),
                        item.quantity(), item.baseUnit(), ZERO, memo + "调整实际结存"));
            entries.add(new BusinessPostingDtos.EntryDraft(
                        nonTradeFact(document.businessType()), factDirection, 0L, item.productId(),
                        item.warehouseId(), item.batchId(), "INVENTORY_NON_TRADE_ITEM", item.id(),
                        ZERO, "", item.amountCost(), memo + "结转库存成本"));
        }
        return entries;
    }

    private LocalDate businessDate(String table, Long id) {
        Date value = jdbc.queryForObject("select business_date from " + table + " where id=?", Date.class, id);
        return value == null ? LocalDate.now() : value.toLocalDate();
    }

    private String direction(BigDecimal value) {
        return value.compareTo(ZERO) >= 0 ? "INCREASE" : "DECREASE";
    }

    private void decreaseBatch(Batch batch, BigDecimal quantity, BigDecimal amount) {
        int updated = jdbc.update("""
                update inventory_batch set quantity_available=quantity_available-?,amount_cost=amount_cost-?,
                  status=if(quantity_available<=0,'OUT','AVAILABLE')
                where id=? and quantity_available>=? and amount_cost>=?
                """, quantity, amount, batch.id(), quantity, amount);
        if (updated != 1) throw new BusinessException("批次库存或成本不足，禁止负库存");
    }

    private void increaseBatch(Batch batch, BigDecimal quantity, BigDecimal amount) {
        jdbc.update("update inventory_batch set quantity_available=quantity_available+?,amount_cost=amount_cost+?,status='AVAILABLE' where id=?",
                quantity, amount, batch.id());
    }

    private void changeStock(Long productId, Long warehouseId, BigDecimal quantityDiff, BigDecimal amountDiff) {
        jdbc.update("""
                insert into stock_balance (product_id,warehouse_id,quantity_available,amount_cost) values (?,?,?,?)
                on duplicate key update quantity_available=quantity_available+values(quantity_available),
                  amount_cost=amount_cost+values(amount_cost)
                """, productId, warehouseId, quantityDiff, amountDiff);
        Map<String, Object> balance = jdbc.queryForMap("select quantity_available,amount_cost from stock_balance where product_id=? and warehouse_id=? for update",
                productId, warehouseId);
        if (decimal(balance.get("quantity_available")).compareTo(ZERO) < 0 || decimal(balance.get("amount_cost")).compareTo(new BigDecimal("-0.01")) < 0)
            throw new BusinessException("库存汇总将出现负数，过账已拒绝");
    }

    private void movement(String prefix, String type, Batch batch, Long warehouseId, BigDecimal quantity,
                          BigDecimal unitCost, BigDecimal amount, String refType, Long refId, String memo) {
        insert("""
                insert into inventory_movement
                (movement_no,movement_type,product_id,warehouse_id,batch_id,quantity,unit_price,amount,ref_type,ref_id,memo)
                values (?,?,?,?,?,?,?,?,?,?,?)
                """, nextNo(prefix), type, batch.productId(), warehouseId, batch.id(), quantity, unitCost, amount, refType, refId, memo);
    }

    private void payableMovement(String prefix, Long supplierId, String refType, Long refId,
                                 String direction, BigDecimal amount, String memo) {
        BigDecimal current = supplierPayableBalance(supplierId);
        insert("""
                insert into payable_movement
                (movement_no,supplier_id,ref_type,ref_id,direction,amount,balance_after,memo)
                values (?,?,?,?,?,?,?,?)
                """, nextNo(prefix), supplierId, refType, refId, direction, amount, current, memo);
    }

    private BigDecimal supplierPayableBalance(Long supplierId) {
        BigDecimal value = jdbc.queryForObject("""
                select coalesce(sum(unpaid),0) from (
                  select greatest(o.total_amount-o.paid_amount-coalesce(r.returned,0),0) unpaid
                  from purchase_order o left join (
                    select purchase_order_id,sum(total_amount) returned from purchase_return_order
                    where status='CONFIRMED' group by purchase_order_id
                  ) r on r.purchase_order_id=o.id
                  where o.supplier_id=? and o.status not in ('DRAFT','CANCELLED')
                  union all
                  select greatest(i.payable_amount-i.paid_amount-coalesce(r.returned,0),0) unpaid
                  from inbound_order i left join (
                    select inbound_id,sum(total_amount) returned from purchase_return_order
                    where status='CONFIRMED' group by inbound_id
                  ) r on r.inbound_id=i.id
                  where i.supplier_id=? and i.purchase_order_id=0 and i.status='STOCKED'
                ) x
                """, BigDecimal.class, supplierId, supplierId);
        return value == null ? ZERO : value;
    }

    private BigDecimal sourcePayableOutstanding(Long purchaseOrderId, Long inboundId, Long excludedReturnId) {
        if (purchaseOrderId != null && purchaseOrderId > 0) {
            BigDecimal value = jdbc.queryForObject("""
                    select greatest(o.total_amount-o.paid_amount-coalesce((select sum(total_amount) from purchase_return_order
                      where purchase_order_id=o.id and status='CONFIRMED' and id<>?),0),0)
                    from purchase_order o where o.id=? for update
                    """, BigDecimal.class, excludedReturnId, purchaseOrderId);
            return value == null ? ZERO : value;
        }
        BigDecimal value = jdbc.queryForObject("""
                select greatest(i.payable_amount-i.paid_amount-coalesce((select sum(total_amount) from purchase_return_order
                  where inbound_id=i.id and status='CONFIRMED' and id<>?),0),0)
                from inbound_order i where i.id=? for update
                """, BigDecimal.class, excludedReturnId, inboundId);
        return value == null ? ZERO : value;
    }

    private void changePartnerAccount(Long partnerId, String accountType, BigDecimal diff, String sourceType, Long sourceId) {
        jdbc.update("insert into partner_account (partner_id,account_type,balance) values (?,?,0) on duplicate key update updated_at=updated_at",
                partnerId, accountType);
        BigDecimal before = jdbc.queryForObject("select balance from partner_account where partner_id=? and account_type=? for update",
                BigDecimal.class, partnerId, accountType);
        BigDecimal after = Objects.requireNonNullElse(before, ZERO).add(diff);
        if (after.compareTo(ZERO) < 0) throw new BusinessException("供应商预付余额不足，不能冲正采购退货");
        jdbc.update("update partner_account set balance=? where partner_id=? and account_type=?", after, partnerId, accountType);
        insert("""
                insert into partner_account_log
                (partner_id,account_type,source_type,source_id,amount,amount_before,amount_after)
                values (?,?,?,?,?,?,?)
                """, partnerId, accountType, sourceType, sourceId, diff, before, after);
    }

    private List<InventoryDtos.Event> events(String type, Long id) {
        return jdbc.query("""
                select * from inventory_document_event where document_type=? and document_id=? order by id desc
                """, (rs, rowNum) -> new InventoryDtos.Event(rs.getLong("id"), rs.getString("document_type"),
                rs.getLong("document_id"), rs.getString("action"), rs.getString("reason"), rs.getLong("operator_id"),
                rs.getString("operator_name"), rs.getTimestamp("created_at").toLocalDateTime()), type, id);
    }

    private void event(String type, Long id, String action, String reason) {
        SecurityContext.Principal actor = SecurityContext.require();
        insert("""
                insert into inventory_document_event
                (document_type,document_id,action,reason,operator_id,operator_name) values (?,?,?,?,?,?)
                """, type, id, action, blank(reason), actor.userId(), actor.employeeName());
    }

    private Batch lockBatch(Long id) {
        List<Batch> rows = jdbc.query("""
                select id,product_id,warehouse_id,batch_no,base_unit,quantity_available,unit_cost,amount_cost,
                       source_type,source_id,packaging_version_no,packaging_effective_revision_id,
                       packaging_profile_snapshot_json,packaging_snapshot_hash,packaging_tracking_mode,package_barcode,
                       received_at,production_date,shelf_life_mode,shelf_life_value,shelf_life_unit,expiration_date
                from inventory_batch where id=? for update
                """, (rs, rowNum) -> new Batch(rs.getLong("id"), rs.getLong("product_id"), rs.getLong("warehouse_id"),
                rs.getString("batch_no"), rs.getString("base_unit"), rs.getBigDecimal("quantity_available"),
                rs.getBigDecimal("unit_cost"), rs.getBigDecimal("amount_cost"), rs.getString("source_type"), rs.getLong("source_id"),
                rs.getInt("packaging_version_no"), rs.getLong("packaging_effective_revision_id"),
                rs.getString("packaging_profile_snapshot_json"), rs.getString("packaging_snapshot_hash"),
                rs.getString("packaging_tracking_mode"), rs.getString("package_barcode"), rs.getDate("received_at").toLocalDate(),
                rs.getDate("production_date") == null ? null : rs.getDate("production_date").toLocalDate(),
                rs.getString("shelf_life_mode"), rs.getObject("shelf_life_value") == null ? null : rs.getInt("shelf_life_value"),
                rs.getString("shelf_life_unit"), rs.getDate("expiration_date") == null ? null : rs.getDate("expiration_date").toLocalDate()), id);
        if (rows.isEmpty()) throw new BusinessException("库存批次不存在");
        Batch batch = rows.get(0);
        dataScopeService.assertMasterAllowed("PRODUCT", batch.productId());
        dataScopeService.assertMasterAllowed("WAREHOUSE", batch.warehouseId());
        return batch;
    }

    private BigDecimal reserved(Long batchId, Long excludedOutboundId) {
        BigDecimal value = jdbc.queryForObject("""
                select coalesce(sum(quantity),0) from outbound_allocation
                where inventory_batch_id=? and status='DRAFT' and outbound_id<>?
                """, BigDecimal.class, batchId, excludedOutboundId == null ? 0L : excludedOutboundId);
        return value == null ? ZERO : value;
    }

    private BigDecimal inTransitQuantity(Long batchId) {
        BigDecimal value = jdbc.queryForObject("""
                select coalesce(sum(i.quantity),0) from inventory_transfer_item i
                join inventory_transfer_order o on o.id=i.transfer_id
                where i.source_batch_id=? and o.status='IN_TRANSIT'
                """, BigDecimal.class, batchId);
        return value == null ? ZERO : value;
    }

    private void ensureFreeStock(Batch batch, BigDecimal quantity, Long excludedOutboundId) {
        BigDecimal free = batch.quantityAvailable().subtract(reserved(batch.id(), excludedOutboundId));
        if (free.compareTo(quantity) < 0)
            throw new BusinessException("批次 " + batch.batchNo() + " 可用库存不足；账面 " + batch.quantityAvailable()
                    + "，已占用 " + reserved(batch.id(), excludedOutboundId) + "，本次需要 " + quantity);
    }

    private PackagingDtos.Plan batchPlan(Batch batch, BigDecimal quantity) {
        return packagingService.planForBatch(batch.id(), new PackagingDtos.PlanRequest(
                quantity, batch.baseUnit(), quantity, batch.baseUnit(), "OUTBOUND", null, null));
    }

    private PackagingDtos.Plan batchPlanFromSnapshot(Batch batch, BigDecimal quantity, String snapshot) {
        return packagingService.planFromSnapshot(snapshot, new PackagingDtos.PlanRequest(
                quantity, batch.baseUnit(), quantity, batch.baseUnit(), "OUTBOUND", null, null));
    }

    private Long insertTargetBatch(Batch source, Long warehouseId, String batchNo, TransferItemLock item) {
        return insert("""
                insert into inventory_batch
                (product_id,warehouse_id,batch_no,source_type,source_id,base_unit,packaging_version_no,
                 packaging_effective_revision_id,packaging_profile_snapshot_json,packaging_snapshot_hash,
                 packaging_tracking_mode,package_barcode,quantity_in,quantity_available,unit_cost,amount_cost,status,
                 received_at,production_date,shelf_life_mode,shelf_life_value,shelf_life_unit,expiration_date)
                values (?,?,?,'TRANSFER',?,?,?,?,?,?,?,?,?,?,?,?, 'AVAILABLE',?,?,?,?,?,?)
                """, source.productId(), warehouseId, batchNo, item.id(), source.baseUnit(), source.packagingVersionNo(),
                source.packagingEffectiveRevisionId(), source.packagingSnapshot(), source.packagingSnapshotHash(),
                source.packagingTrackingMode(), source.packageBarcode(), item.quantity(), item.quantity(), item.unitCost(),
                item.amountCost(), Date.valueOf(java.time.LocalDate.now()),
                source.productionDate() == null ? null : Date.valueOf(source.productionDate()), source.shelfLifeMode(),
                source.shelfLifeValue(), source.shelfLifeUnit(), source.expirationDate() == null ? null : Date.valueOf(source.expirationDate()));
    }

    private String targetBatchNo(String sourceNo, Long transferId, Long itemId) {
        String suffix = "-D" + transferId + "-" + itemId;
        String base = blank(sourceNo);
        return (base.length() + suffix.length() <= 64 ? base : base.substring(0, 64 - suffix.length())) + suffix;
    }

    private void requireWarehouse(Long id) {
        Integer count = jdbc.queryForObject("select count(*) from warehouse where id=? and status='ENABLED'", Integer.class, id);
        if (count == null || count == 0) throw new BusinessException("仓库不存在或已停用");
    }

    private InboundSource lockInbound(Long id) {
        List<InboundSource> rows = jdbc.query("""
                select id,purchase_order_id,supplier_id,warehouse_id,status from inbound_order where id=? for update
                """, (rs, rowNum) -> new InboundSource(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getString(5)), id);
        if (rows.isEmpty()) throw new BusinessException("原入库单不存在");
        return rows.get(0);
    }

    private InboundItemSource lockInboundItem(Long id, Long inboundId, Long batchId) {
        List<InboundItemSource> rows = jdbc.query("""
                select id,product_id,quantity,coalesce(inventory_unit_cost,unit_cost),
                       coalesce(nullif(settlement_total_amount,0),coalesce(inventory_amount_cost,amount)),
                       price_includes_tax,tax_rate,amount_excluding_tax,tax_amount,tax_rounding_difference from inbound_item
                where id=? and inbound_id=? and inventory_batch_id=? for update
                """, (rs, rowNum) -> new InboundItemSource(rs.getLong(1), rs.getLong(2), rs.getBigDecimal(3),
                rs.getBigDecimal(4), rs.getBigDecimal(5), rs.getInt(6) == 1, rs.getBigDecimal(7),
                rs.getBigDecimal(8), rs.getBigDecimal(9), rs.getBigDecimal(10)),
                id, inboundId, batchId);
        if (rows.isEmpty()) throw new BusinessException("采购退货明细与原入库批次不一致");
        return rows.get(0);
    }

    private BigDecimal confirmedReturnedQuantity(Long inboundItemId, Long excludedReturnId) {
        BigDecimal value = jdbc.queryForObject("""
                select coalesce(sum(i.quantity),0) from purchase_return_item i
                join purchase_return_order r on r.id=i.return_id
                where i.inbound_item_id=? and r.status='CONFIRMED' and r.id<>?
                """, BigDecimal.class, inboundItemId, excludedReturnId == null ? 0L : excludedReturnId);
        return value == null ? ZERO : value;
    }

    private BigDecimal confirmedReturnedAmount(Long inboundItemId, Long excludedReturnId) {
        return confirmedReturnedValue(inboundItemId, excludedReturnId, "amount");
    }

    private BigDecimal activeLandedUnitAmount(Long batchId, Long inboundItemId) {
        BigDecimal value = jdbc.queryForObject("""
                select coalesce(sum(c.landed_unit_amount),0)
                from inventory_batch_landed_cost_component c
                join landed_cost_allocation_item i on i.id=c.allocation_item_id
                join landed_cost_allocation a on a.id=c.allocation_id
                where c.batch_id=? and i.inbound_item_id=? and c.status='ACTIVE' and a.status='CONFIRMED'
                """, BigDecimal.class, batchId, inboundItemId);
        return value == null ? ZERO : value;
    }

    private BigDecimal confirmedReturnedValue(Long inboundItemId, Long excludedReturnId, String column) {
        BigDecimal value = jdbc.queryForObject("""
                select coalesce(sum(i.%s),0) from purchase_return_item i
                join purchase_return_order r on r.id=i.return_id
                where i.inbound_item_id=? and r.status='CONFIRMED' and r.id<>?
                """.formatted(column), BigDecimal.class, inboundItemId, excludedReturnId == null ? 0L : excludedReturnId);
        return value == null ? ZERO : value;
    }

    private TransferLock lockTransfer(Long id) {
        List<TransferLock> rows = jdbc.query("""
                select id,order_no,from_warehouse_id,to_warehouse_id,status,business_date,expected_arrival_date,received_at
                from inventory_transfer_order where id=? for update
                """, (rs, rowNum) -> new TransferLock(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getLong(4), rs.getString(5),
                rs.getDate(6).toLocalDate(), rs.getDate(7) == null ? null : rs.getDate(7).toLocalDate(),
                rs.getTimestamp(8) == null ? null : rs.getTimestamp(8).toLocalDateTime()), id);
        if (rows.isEmpty()) throw new BusinessException("调拨单不存在");
        return rows.get(0);
    }

    private List<TransferItemLock> transferItems(Long id) {
        return jdbc.query("""
                select id,source_batch_id,target_batch_id,quantity,unit_cost,amount_cost,packaging_plan_json,selected_package_instance_ids_json
                from inventory_transfer_item where transfer_id=? order by id
                """, (rs, rowNum) -> new TransferItemLock(rs.getLong(1), rs.getLong(2), rs.getLong(3),
                rs.getBigDecimal(4), rs.getBigDecimal(5), rs.getBigDecimal(6), rs.getString(7), rs.getString(8)), id);
    }

    private StocktakeLock lockStocktake(Long id) {
        List<StocktakeLock> rows = jdbc.query("select id,order_no,warehouse_id,status from inventory_stocktake_order where id=? for update",
                (rs, rowNum) -> new StocktakeLock(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getString(4)), id);
        if (rows.isEmpty()) throw new BusinessException("盘点单不存在");
        return rows.get(0);
    }

    private List<StocktakeItemLock> stocktakeItems(Long id) {
        return jdbc.query("""
                select id,batch_id,system_quantity,counted_quantity,difference_quantity,unit_cost,
                       system_amount_cost,counted_amount_cost,cost_difference
                from inventory_stocktake_item where stocktake_id=? order by id
                """, (rs, rowNum) -> new StocktakeItemLock(rs.getLong(1), rs.getLong(2), rs.getBigDecimal(3),
                rs.getBigDecimal(4), rs.getBigDecimal(5), rs.getBigDecimal(6), rs.getBigDecimal(7),
                rs.getBigDecimal(8), rs.getBigDecimal(9)), id);
    }

    private PurchaseReturnLock lockPurchaseReturn(Long id) {
        List<PurchaseReturnLock> rows = jdbc.query("""
                select id,order_no,inbound_id,purchase_order_id,supplier_id,warehouse_id,status,total_amount,
                       payable_reduction_amount,supplier_credit_amount from purchase_return_order where id=? for update
                """, (rs, rowNum) -> new PurchaseReturnLock(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getLong(4),
                rs.getLong(5), rs.getLong(6), rs.getString(7), rs.getBigDecimal(8), rs.getBigDecimal(9), rs.getBigDecimal(10)), id);
        if (rows.isEmpty()) throw new BusinessException("采购退货单不存在");
        return rows.get(0);
    }

    private List<PurchaseReturnItemLock> purchaseReturnItems(Long id) {
        return jdbc.query("""
                select id,inbound_item_id,batch_id,quantity,inventory_unit_cost,inventory_amount_cost,landed_cost_amount,
                       packaging_plan_json,selected_package_instance_ids_json
                from purchase_return_item where return_id=? order by id
                """, (rs, rowNum) -> new PurchaseReturnItemLock(rs.getLong(1), rs.getLong(2), rs.getLong(3),
                rs.getBigDecimal(4), rs.getBigDecimal(5), rs.getBigDecimal(6), rs.getBigDecimal(7),
                rs.getString(8), rs.getString(9)), id);
    }

    private CostAdjustmentLock lockCostAdjustment(Long id) {
        List<CostAdjustmentLock> rows = jdbc.query("select id,adjustment_no,status from inventory_cost_adjustment where id=? for update",
                (rs, rowNum) -> new CostAdjustmentLock(rs.getLong(1), rs.getString(2), rs.getString(3)), id);
        if (rows.isEmpty()) throw new BusinessException("成本调整单不存在");
        return rows.get(0);
    }

    private NonTradeLock lockNonTrade(Long id) {
        List<NonTradeLock> rows = jdbc.query("""
                select id,document_no,business_type,direction,business_date,status,recipient,handler_name
                from inventory_non_trade_document where id=? for update
                """, (rs, rowNum) -> new NonTradeLock(
                rs.getLong("id"), rs.getString("document_no"), rs.getString("business_type"),
                rs.getString("direction"), rs.getDate("business_date").toLocalDate(),
                rs.getString("status"), rs.getString("recipient"), rs.getString("handler_name")), id);
        if (rows.isEmpty()) throw new BusinessException("其他出入库记录不存在");
        return rows.get(0);
    }

    private List<NonTradeItemLock> lockNonTradeItems(Long documentId) {
        List<NonTradeItemLock> rows = jdbc.query("""
                select id,line_no,product_id,warehouse_id,batch_id,quantity,base_unit,unit_cost,amount_cost,
                       quantity_before,amount_before,packaging_ref_id,selected_package_instance_ids_json,
                       selected_package_allocations_json,packaging_plan_json
                from inventory_non_trade_item where document_id=? order by line_no,id for update
                """, (rs, rowNum) -> new NonTradeItemLock(
                rs.getLong("id"), rs.getInt("line_no"), rs.getLong("product_id"),
                rs.getLong("warehouse_id"), rs.getLong("batch_id"), rs.getBigDecimal("quantity"),
                rs.getString("base_unit"), rs.getBigDecimal("unit_cost"), rs.getBigDecimal("amount_cost"),
                rs.getBigDecimal("quantity_before"), rs.getBigDecimal("amount_before"),
                rs.getLong("packaging_ref_id"),
                rs.getString("selected_package_instance_ids_json"),
                rs.getString("selected_package_allocations_json"), rs.getString("packaging_plan_json")), documentId);
        for (NonTradeItemLock item : rows) {
            dataScopeService.assertMasterAllowed("PRODUCT", item.productId());
            dataScopeService.assertMasterAllowed("WAREHOUSE", item.warehouseId());
        }
        return rows;
    }

    private Map<Long, Batch> lockBatches(List<Long> batchIds) {
        Map<Long, Batch> result = new LinkedHashMap<>();
        batchIds.stream().filter(Objects::nonNull).distinct().sorted()
                .forEach(batchId -> result.put(batchId, lockBatch(batchId)));
        return result;
    }

    private List<CostItemLock> costItems(Long id) {
        return jdbc.query("""
                select id,batch_id,quantity_snapshot,old_unit_cost,new_unit_cost,old_amount_cost,new_amount_cost,difference_amount
                from inventory_cost_adjustment_item where adjustment_id=? order by id
                """, (rs, rowNum) -> new CostItemLock(rs.getLong(1), rs.getLong(2), rs.getBigDecimal(3), rs.getBigDecimal(4),
                rs.getBigDecimal(5), rs.getBigDecimal(6), rs.getBigDecimal(7), rs.getBigDecimal(8)), id);
    }

    private org.springframework.jdbc.core.RowMapper<InventoryDtos.TransferRow> transferRowMapper() {
        return (rs, rowNum) -> transferRow(rs);
    }

    private InventoryDtos.TransferRow transferRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new InventoryDtos.TransferRow(rs.getLong("id"), rs.getString("order_no"), rs.getLong("from_warehouse_id"),
                rs.getString("from_warehouse_name"), rs.getLong("to_warehouse_id"), rs.getString("to_warehouse_name"),
                rs.getDate("business_date").toLocalDate(), rs.getString("status"),
                rs.getDate("expected_arrival_date") == null ? null : rs.getDate("expected_arrival_date").toLocalDate(),
                rs.getBigDecimal("total_quantity"),
                rs.getBigDecimal("total_cost"), rs.getString("memo"), time(rs, "dispatched_at"), time(rs, "received_at"),
                time(rs, "created_at"));
    }

    private org.springframework.jdbc.core.RowMapper<InventoryDtos.StocktakeRow> stocktakeRowMapper() {
        return (rs, rowNum) -> stocktakeRow(rs);
    }

    private InventoryDtos.StocktakeRow stocktakeRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new InventoryDtos.StocktakeRow(rs.getLong("id"), rs.getString("order_no"), rs.getLong("warehouse_id"),
                rs.getString("warehouse_name"), rs.getDate("business_date").toLocalDate(), rs.getString("status"),
                rs.getBigDecimal("total_gain_quantity"), rs.getBigDecimal("total_loss_quantity"),
                rs.getBigDecimal("total_cost_difference"), rs.getString("memo"), time(rs, "confirmed_at"), time(rs, "created_at"));
    }

    private org.springframework.jdbc.core.RowMapper<InventoryDtos.PurchaseReturnRow> purchaseReturnRowMapper() {
        return (rs, rowNum) -> purchaseReturnRow(rs);
    }

    private InventoryDtos.PurchaseReturnRow purchaseReturnRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new InventoryDtos.PurchaseReturnRow(rs.getLong("id"), rs.getString("order_no"), rs.getLong("inbound_id"),
                rs.getString("inbound_no"), rs.getLong("purchase_order_id"), rs.getString("purchase_order_no"),
                rs.getLong("supplier_id"), rs.getString("supplier_name"), rs.getLong("warehouse_id"),
                rs.getString("warehouse_name"), rs.getDate("business_date").toLocalDate(), rs.getString("status"),
                rs.getBigDecimal("total_quantity"), rs.getBigDecimal("total_amount"), rs.getInt("price_includes_tax") == 1,
                rs.getString("tax_rate_summary"), rs.getString("tax_rule_snapshot"), rs.getBigDecimal("amount_excluding_tax"),
                rs.getBigDecimal("tax_amount"), rs.getBigDecimal("tax_rounding_difference"), rs.getBigDecimal("payable_reduction_amount"),
                rs.getBigDecimal("supplier_credit_amount"), rs.getString("reason"), rs.getString("memo"),
                time(rs, "confirmed_at"), time(rs, "created_at"));
    }

    private org.springframework.jdbc.core.RowMapper<InventoryDtos.CostAdjustmentRow> costAdjustmentRowMapper() {
        return (rs, rowNum) -> costAdjustmentRow(rs);
    }

    private InventoryDtos.CostAdjustmentRow costAdjustmentRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new InventoryDtos.CostAdjustmentRow(rs.getLong("id"), rs.getString("adjustment_no"),
                rs.getDate("business_date").toLocalDate(), rs.getString("status"), rs.getBigDecimal("total_difference_amount"),
                rs.getString("reason"), rs.getString("memo"), time(rs, "confirmed_at"), time(rs, "created_at"));
    }

    private InventoryDtos.NonTradeRow nonTradeRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new InventoryDtos.NonTradeRow(
                rs.getLong("id"), rs.getString("document_no"), rs.getString("business_type"),
                rs.getString("direction"), rs.getDate("business_date").toLocalDate(),
                rs.getLong("product_id"), rs.getString("sku"), rs.getString("product_name"), rs.getString("spec"),
                rs.getLong("warehouse_id"), rs.getString("warehouse_name"), rs.getLong("batch_id"),
                rs.getString("batch_no"), rs.getBigDecimal("quantity"), rs.getString("base_unit"),
                rs.getBigDecimal("unit_cost"), rs.getBigDecimal("amount_cost"),
                rs.getBigDecimal("quantity_before"), rs.getBigDecimal("amount_before"),
                rs.getString("recipient"), rs.getString("reason"), rs.getString("memo"), rs.getString("status"),
                rs.getString("cancel_reason"), rs.getString("reversal_reason"),
                rs.getObject("signed_attachment_id", Long.class), rs.getString("signed_attachment_sha256"),
                rs.getString("handler_name"), rs.getInt("item_count"), rs.getBigDecimal("total_amount_cost"),
                rs.getString("issued_by_name"), time(rs, "issued_at"),
                rs.getString("created_by_name"),
                rs.getString("confirmed_by_name"), time(rs, "confirmed_at"), rs.getString("reversed_by_name"),
                time(rs, "reversed_at"), time(rs, "created_at"));
    }

    private List<InventoryDtos.NonTradeItem> nonTradeItems(
            Long documentId, String direction, boolean forUpdate) {
        String sql = """
                select ni.*,p.sku,p.name product_name,p.spec,w.name warehouse_name,b.batch_no
                from inventory_non_trade_item ni
                join product p on p.id=ni.product_id
                join warehouse w on w.id=ni.warehouse_id
                join inventory_batch b on b.id=ni.batch_id
                where ni.document_id=?
                order by ni.line_no,ni.id
                """ + (forUpdate ? " for update" : "");
        List<NonTradeItemDetailSource> sources = jdbc.query(sql, (rs, rowNum) -> {
            BigDecimal quantity = rs.getBigDecimal("quantity");
            BigDecimal quantityBefore = rs.getBigDecimal("quantity_before");
            BigDecimal quantityAfter = "IN".equals(direction)
                    ? quantityBefore.add(quantity)
                    : quantityBefore.subtract(quantity);
            List<InventoryDtos.NonTradePackageAllocation> allocations =
                    parseNonTradeAllocations(rs.getString("selected_package_allocations_json"));
            return new NonTradeItemDetailSource(
                    new InventoryDtos.NonTradeItem(
                            rs.getLong("id"), rs.getInt("line_no"), rs.getLong("product_id"),
                            rs.getString("sku"), rs.getString("product_name"), rs.getString("spec"),
                            rs.getLong("warehouse_id"), rs.getString("warehouse_name"), rs.getLong("batch_id"),
                            rs.getString("batch_no"), quantity, rs.getString("base_unit"),
                            rs.getBigDecimal("unit_cost"), rs.getBigDecimal("amount_cost"),
                            quantityBefore, quantityAfter, rs.getBigDecimal("amount_before"),
                            parseIds(rs.getString("selected_package_instance_ids_json")),
                            List.of()),
                    rs.getLong("packaging_ref_id"), allocations);
        }, documentId);
        return sources.stream().map(source -> {
            InventoryDtos.NonTradeItem item = source.item();
            return new InventoryDtos.NonTradeItem(
                    item.id(), item.lineNo(), item.productId(), item.sku(), item.productName(), item.spec(),
                    item.warehouseId(), item.warehouseName(), item.batchId(), item.batchNo(),
                    item.quantity(), item.baseUnit(), item.unitCost(), item.amountCost(),
                    item.quantityBefore(), item.quantityAfter(), item.amountBefore(),
                    item.packageInstanceIds(),
                    nonTradeAllocationDetails(source.packagingRefId(), source.allocations()));
        }).toList();
    }

    private List<InventoryDtos.NonTradePackageAllocationDetail> nonTradeAllocationDetails(
            Long packagingRefId, List<InventoryDtos.NonTradePackageAllocation> allocations) {
        if (allocations == null || allocations.isEmpty()) return List.of();
        List<Long> packageIds = allocations.stream()
                .map(InventoryDtos.NonTradePackageAllocation::packageInstanceId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (packageIds.isEmpty()) return allocations.stream()
                .map(allocation -> new InventoryDtos.NonTradePackageAllocationDetail(
                        allocation.packageInstanceId(), allocation.quantity(),
                        null, null, null, null))
                .toList();
        String packagePlaceholders = String.join(",", java.util.Collections.nCopies(packageIds.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(packagingRefId);
        args.addAll(packageIds);
        Map<Long, PackageAllocationAudit> audits = jdbc.query("""
                select i.id,i.package_code,i.unit_name,i.remaining_base_quantity,
                       u.quantity_before,u.quantity_after
                from inventory_package_instance i
                left join inventory_package_instance_usage u
                  on u.package_instance_id=i.id
                 and u.ref_type='INVENTORY_NON_TRADE'
                 and u.ref_id=?
                 and u.movement_type='PARTIAL_ACTUAL_OUT'
                where i.id in (%s)
                """.formatted(packagePlaceholders), rs -> {
            Map<Long, PackageAllocationAudit> rows = new LinkedHashMap<>();
            while (rs.next()) {
                rows.put(rs.getLong("id"), new PackageAllocationAudit(
                        rs.getString("package_code"),
                        rs.getString("unit_name"),
                        rs.getBigDecimal("remaining_base_quantity"),
                        rs.getBigDecimal("quantity_before"),
                        rs.getBigDecimal("quantity_after")));
            }
            return rows;
        }, args.toArray());
        return allocations.stream().map(allocation -> {
            PackageAllocationAudit audit = audits.get(allocation.packageInstanceId());
            if (audit == null) return new InventoryDtos.NonTradePackageAllocationDetail(
                    allocation.packageInstanceId(), allocation.quantity(),
                    null, null, null, null);
            BigDecimal before = audit.quantityBefore() == null
                    ? audit.currentQuantity()
                    : audit.quantityBefore();
            BigDecimal after = audit.quantityAfter() == null
                    ? before.subtract(allocation.quantity())
                    : audit.quantityAfter();
            return new InventoryDtos.NonTradePackageAllocationDetail(
                    allocation.packageInstanceId(), allocation.quantity(),
                    audit.packageCode(), audit.unitName(), before, after);
        }).toList();
    }

    private List<InventoryDtos.NonTradeRow> sanitizeNonTradeCost(List<InventoryDtos.NonTradeRow> rows) {
        if (SecurityContext.require().hasPermission("inventory.cost.view")) return rows;
        return rows.stream().map(row -> new InventoryDtos.NonTradeRow(
                row.id(), row.documentNo(), row.businessType(), row.direction(), row.businessDate(),
                row.productId(), row.sku(), row.productName(), row.spec(), row.warehouseId(), row.warehouseName(),
                row.batchId(), row.batchNo(), row.quantity(), row.baseUnit(), null, null,
                row.quantityBefore(), null, row.recipient(), row.reason(), row.memo(), row.status(),
                row.cancelReason(), row.reversalReason(), row.signedAttachmentId(), row.signedAttachmentSha256(),
                row.handlerName(), row.itemCount(), null,
                row.issuedByName(), row.issuedAt(),
                row.createdByName(), row.confirmedByName(),
                row.confirmedAt(), row.reversedByName(), row.reversedAt(), row.createdAt())).toList();
    }

    private LocalDateTime time(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        return rs.getTimestamp(column) == null ? null : rs.getTimestamp(column).toLocalDateTime();
    }

    private Long insert(String sql, Object... args) {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            return ps;
        }, holder);
        Number key = holder.getKey();
        return key == null ? 0L : key.longValue();
    }

    private String nextNo(String prefix) {
        return prefix + LocalDateTime.now().format(NO_TIME) + (NO_COUNTER.incrementAndGet() % 1000);
    }

    private String jsonIds(List<Long> ids) {
        try { return objectMapper.writeValueAsString(ids == null ? List.of() : ids.stream().filter(Objects::nonNull).distinct().toList()); }
        catch (Exception ex) { throw new BusinessException("包装实物选择无法保存"); }
    }

    private List<Long> parseIds(String json) {
        if (!StringUtils.hasText(json)) return List.of();
        try { return objectMapper.readValue(json, new TypeReference<>() {}); }
        catch (Exception ex) { throw new BusinessException("包装实物记录无法解析"); }
    }

    private String jsonNonTradeAllocations(List<InventoryDtos.NonTradePackageAllocation> allocations) {
        try { return objectMapper.writeValueAsString(allocations == null ? List.of() : allocations); }
        catch (Exception ex) { throw new BusinessException("来源包装分配无法保存"); }
    }

    private List<InventoryDtos.NonTradePackageAllocation> parseNonTradeAllocations(String json) {
        if (!StringUtils.hasText(json)) return List.of();
        try { return objectMapper.readValue(json, new TypeReference<>() {}); }
        catch (Exception ex) { throw new BusinessException("来源包装分配无法解析"); }
    }

    private List<Long> packageIds(
            List<Long> packageInstanceIds,
            List<InventoryDtos.NonTradePackageAllocation> allocations) {
        if (allocations != null && !allocations.isEmpty()) {
            return allocations.stream()
                    .filter(Objects::nonNull)
                    .map(InventoryDtos.NonTradePackageAllocation::packageInstanceId)
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();
        }
        return packageInstanceIds == null ? List.of() : packageInstanceIds;
    }

    private BigDecimal money(BigDecimal value) { return value.setScale(2, RoundingMode.HALF_UP); }
    private BigDecimal removalCost(Batch batch, BigDecimal quantity) {
        return quantity.compareTo(batch.quantityAvailable()) == 0
                ? batch.amountCost()
                : money(quantity.multiply(batch.unitCost()));
    }
    private BigDecimal stocktakeAmount(Batch batch, BigDecimal countedQuantity, BigDecimal difference) {
        if (difference.compareTo(ZERO) == 0) return batch.amountCost();
        if (difference.compareTo(ZERO) > 0)
            return batch.amountCost().add(money(difference.multiply(batch.unitCost())));
        return batch.amountCost().subtract(removalCost(batch, difference.abs()));
    }
    private BigDecimal decimal(Object value) { return value == null ? ZERO : new BigDecimal(String.valueOf(value)); }
    private String blank(String value) { return value == null ? "" : value; }
    private String text(Object value) { return value == null ? "" : String.valueOf(value); }
    private String nonTradeType(String value) {
        String normalized = blank(value).trim().toUpperCase();
        if (!List.of("SAMPLE_OUT", "INTERNAL_USE", "NORMAL_LOSS", "OTHER_OUT", "OTHER_IN").contains(normalized))
            throw new BusinessException("其他出入库类型不支持");
        return normalized;
    }
    private String nonTradeLabel(String value) {
        return switch (nonTradeType(value)) {
            case "SAMPLE_OUT" -> "送样出库";
            case "INTERNAL_USE" -> "内部领用";
            case "NORMAL_LOSS" -> "合理损耗";
            case "OTHER_OUT" -> "其他出库";
            default -> "其他入库";
        };
    }
    private String nonTradeFact(String value) {
        return switch (nonTradeType(value)) {
            case "SAMPLE_OUT" -> "NON_TRADE_SAMPLE";
            case "INTERNAL_USE" -> "NON_TRADE_INTERNAL_USE";
            case "NORMAL_LOSS" -> "NON_TRADE_NORMAL_LOSS";
            case "OTHER_OUT" -> "NON_TRADE_OTHER_OUT";
            default -> "NON_TRADE_OTHER_IN";
        };
    }
    private void requirePositive(BigDecimal value, String name) {
        if (value == null || value.compareTo(ZERO) <= 0) throw new BusinessException(name + "必须大于0");
    }
    private void requireReason(String reason) {
        if (!StringUtils.hasText(reason)) throw new BusinessException("必须填写原因");
        if (reason.trim().length() > 500) throw new BusinessException("原因不能超过500字");
    }

    private record Batch(Long id, Long productId, Long warehouseId, String batchNo, String baseUnit,
                         BigDecimal quantityAvailable, BigDecimal unitCost, BigDecimal amountCost,
                         String sourceType, Long sourceId, Integer packagingVersionNo, Long packagingEffectiveRevisionId,
                         String packagingSnapshot, String packagingSnapshotHash, String packagingTrackingMode,
                         String packageBarcode, java.time.LocalDate receivedAt, java.time.LocalDate productionDate,
                         String shelfLifeMode, Integer shelfLifeValue, String shelfLifeUnit, java.time.LocalDate expirationDate) {}
    private record PreparedTransferItem(Batch batch, InventoryDtos.TransferItemCreate request, PackagingDtos.Plan plan, BigDecimal amount) {}
    private record PreparedStocktakeItem(Batch batch, InventoryDtos.StocktakeItemCreate request, BigDecimal difference,
                                         BigDecimal systemAmount, BigDecimal countedAmount, BigDecimal costDifference) {}
    private record PreparedPurchaseReturnItem(InboundItemSource source, Batch batch, InventoryDtos.PurchaseReturnItemCreate request,
                                              PackagingDtos.Plan plan, BigDecimal amount,
                                              BigDecimal amountExcludingTax, BigDecimal taxAmount,
                                              BigDecimal roundingDifference) {}
    private record PreparedCostItem(Batch batch, BigDecimal newCost, BigDecimal oldAmount, BigDecimal newAmount, BigDecimal difference) {}
    private record PreparedNonTradeItem(
            Batch batch, InventoryDtos.NonTradeItemCreate request, PackagingDtos.Plan plan,
            BigDecimal amount, List<Long> selectedPackageIds) {}
    private record TransferLock(Long id, String orderNo, Long fromWarehouseId, Long toWarehouseId, String status,
                                LocalDate businessDate, LocalDate expectedArrivalDate, LocalDateTime receivedAt) {}
    private record TransferItemLock(Long id, Long sourceBatchId, Long targetBatchId, BigDecimal quantity,
                                    BigDecimal unitCost, BigDecimal amountCost, String packagingPlanJson, String selectedIdsJson) {}
    private record StocktakeLock(Long id, String orderNo, Long warehouseId, String status) {}
    private record StocktakeItemLock(Long id, Long batchId, BigDecimal systemQuantity, BigDecimal countedQuantity,
                                     BigDecimal differenceQuantity, BigDecimal unitCost, BigDecimal systemAmount,
                                     BigDecimal countedAmount, BigDecimal costDifference) {}
    private record InboundSource(Long id, Long purchaseOrderId, Long supplierId, Long warehouseId, String status) {}
    private record InboundItemSource(Long id, Long productId, BigDecimal quantity, BigDecimal unitCost, BigDecimal amount,
                                     boolean priceIncludesTax, BigDecimal taxRate, BigDecimal amountExcludingTax,
                                     BigDecimal taxAmount, BigDecimal roundingDifference) {}
    private record PurchaseReturnLock(Long id, String orderNo, Long inboundId, Long purchaseOrderId, Long supplierId,
                                      Long warehouseId, String status, BigDecimal totalAmount,
                                      BigDecimal payableReductionAmount, BigDecimal supplierCreditAmount) {}
    private record PurchaseReturnItemLock(Long id, Long inboundItemId, Long batchId, BigDecimal quantity,
                                          BigDecimal inventoryUnitCost, BigDecimal inventoryAmountCost, BigDecimal landedCostAmount,
                                          String packagingPlanJson, String selectedIdsJson) {}
    private record CostAdjustmentLock(Long id, String adjustmentNo, String status) {}
    private record NonTradeLock(
            Long id, String documentNo, String businessType, String direction,
            LocalDate businessDate, String status, String recipient, String handlerName) {}
    private record NonTradeItemLock(
            Long id, Integer lineNo, Long productId, Long warehouseId, Long batchId,
            BigDecimal quantity, String baseUnit, BigDecimal unitCost, BigDecimal amountCost,
            BigDecimal quantityBefore, BigDecimal amountBefore, Long packagingRefId, String selectedPackageIdsJson,
            String selectedPackageAllocationsJson, String packagingPlanJson) {}
    private record NonTradeItemDetailSource(
            InventoryDtos.NonTradeItem item, Long packagingRefId,
            List<InventoryDtos.NonTradePackageAllocation> allocations) {}
    private record PackageAllocationAudit(
            String packageCode, String unitName, BigDecimal currentQuantity,
            BigDecimal quantityBefore, BigDecimal quantityAfter) {}
    private record CostItemLock(Long id, Long batchId, BigDecimal quantitySnapshot, BigDecimal oldUnitCost,
                                BigDecimal newUnitCost, BigDecimal oldAmountCost, BigDecimal newAmountCost,
                                BigDecimal differenceAmount) {}
}
