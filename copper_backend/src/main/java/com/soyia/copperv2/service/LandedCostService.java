package com.soyia.copperv2.service;

import com.soyia.copperv2.dto.BusinessPostingDtos;
import com.soyia.copperv2.dto.LandedCostDtos;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class LandedCostService {
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final Set<String> METHODS = Set.of("QUANTITY", "WEIGHT", "AMOUNT", "MANUAL");
    private static final DateTimeFormatter NO_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");
    private static final AtomicInteger NO_COUNTER = new AtomicInteger();

    private final JdbcTemplate jdbc;
    private final BusinessDataScopeService dataScopeService;
    private final BusinessPostingService postingService;

    public LandedCostService(JdbcTemplate jdbc, BusinessDataScopeService dataScopeService,
                             BusinessPostingService postingService) {
        this.jdbc = jdbc;
        this.dataScopeService = dataScopeService;
        this.postingService = postingService;
    }

    public LandedCostDtos.Options options() {
        List<LandedCostDtos.SourceOption> sources = jdbc.query("""
                select d.id,d.document_no,d.partner_id,d.partner_name_snapshot,d.business_date,d.summary,
                       d.amount_excluding_tax,d.tax_amount,d.total_amount,d.input_tax_treatment,
                       case when d.input_tax_treatment='INCLUDED_IN_COST' then d.total_amount
                            else d.amount_excluding_tax end capitalizable_amount,d.status,
                       exists(select 1 from landed_cost_allocation a where a.treasury_document_id=d.id
                         and a.status in ('DRAFT','CONFIRMED')) allocated
                from treasury_document d
                where d.document_type='PURCHASE_LANDED_COST'
                  and d.status in ('CONFIRMED','PARTIALLY_SETTLED','SETTLED')
                order by d.business_date desc,d.id desc limit 500
                """, (rs, rowNum) -> new LandedCostDtos.SourceOption(
                rs.getLong("id"), rs.getString("document_no"), rs.getLong("partner_id"),
                rs.getString("partner_name_snapshot"), rs.getDate("business_date").toLocalDate(),
                rs.getString("summary"), rs.getBigDecimal("amount_excluding_tax"), rs.getBigDecimal("tax_amount"),
                rs.getBigDecimal("total_amount"), rs.getBigDecimal("capitalizable_amount"),
                rs.getString("input_tax_treatment"), rs.getString("status"), rs.getBoolean("allocated")));
        sources = dataScopeService.filterDocumentList("TREASURY_DOCUMENT", sources);

        List<LandedCostDtos.InboundOption> inbound = jdbc.query("""
                select o.id inbound_id,o.order_no,o.business_date,o.supplier_id,coalesce(s.name,'') supplier_name,
                       i.id inbound_item_id,i.inventory_batch_id,b.batch_no,i.product_id,p.sku,p.name product_name,
                       p.spec,o.warehouse_id,w.name warehouse_name,i.quantity original_quantity,i.base_unit,
                       i.amount_excluding_tax
                from inbound_item i join inbound_order o on o.id=i.inbound_id
                join inventory_batch b on b.id=i.inventory_batch_id
                join product p on p.id=i.product_id join warehouse w on w.id=o.warehouse_id
                left join partner s on s.id=o.supplier_id
                where o.status='STOCKED' and i.inventory_batch_id>0 and i.quantity>0
                order by o.business_date desc,o.id desc,i.id limit 1000
                """, (rs, rowNum) -> {
            BigDecimal original = rs.getBigDecimal("original_quantity");
            String unit = rs.getString("base_unit");
            return new LandedCostDtos.InboundOption(
                    rs.getLong("inbound_id"), rs.getString("order_no"), rs.getDate("business_date").toLocalDate(),
                    rs.getLong("supplier_id"), rs.getString("supplier_name"), rs.getLong("inbound_item_id"),
                    rs.getLong("inventory_batch_id"), rs.getString("batch_no"), rs.getLong("product_id"),
                    rs.getString("sku"), rs.getString("product_name"), rs.getString("spec"),
                    rs.getLong("warehouse_id"), rs.getString("warehouse_name"), original,
                    lineageBatches(rs.getLong("inventory_batch_id")).stream()
                            .map(BatchRow::quantity).reduce(ZERO, BigDecimal::add), unit,
                    rs.getBigDecimal("amount_excluding_tax"), suggestedWeight(original, unit));
        });
        List<ScopedInbound> scoped = inbound.stream().map(row -> new ScopedInbound("INBOUND", row.inboundId(), row)).toList();
        inbound = dataScopeService.filterDerivedDocumentList(scoped).stream().map(ScopedInbound::row).toList();
        inbound = dataScopeService.filterMasterList("PRODUCT", inbound, "productId");
        inbound = dataScopeService.filterMasterList("WAREHOUSE", inbound, "warehouseId");
        return new LandedCostDtos.Options(sources, inbound);
    }

    public List<LandedCostDtos.AllocationRow> allocations(String status) {
        String normalized = text(status).toUpperCase();
        List<LandedCostDtos.AllocationRow> rows = jdbc.query(headerSql() + """
                where (?='' or a.status=?) order by a.business_date desc,a.id desc limit 500
                """, allocationRowMapper(), normalized, normalized);
        return dataScopeService.filterDocumentList("LANDED_COST_ALLOCATION", rows);
    }

    public LandedCostDtos.AllocationDetail allocation(Long id) {
        dataScopeService.assertDocumentAllowed("LANDED_COST_ALLOCATION", id);
        List<LandedCostDtos.AllocationRow> headers = jdbc.query(headerSql() + " where a.id=?", allocationRowMapper(), id);
        if (headers.isEmpty()) throw new BusinessException("采购费用分摊单不存在");
        Map<String, Object> links = jdbc.queryForMap("""
                select a.cancel_reason,a.reversal_reason,
                       coalesce((select b.id from business_posting_batch b where b.source_type='LANDED_COST_ALLOCATION'
                         and b.source_id=a.id and b.posting_action='POST' order by b.id desc limit 1),0) posting_batch_id,
                       coalesce((select v.id from accounting_voucher v join business_posting_batch b
                         on b.id=v.business_posting_batch_id where b.source_type='LANDED_COST_ALLOCATION'
                         and b.source_id=a.id and b.posting_action='POST' and v.status<>'CANCELLED'
                         order by v.id desc limit 1),0) accounting_voucher_id,
                       coalesce((select v.voucher_no from accounting_voucher v join business_posting_batch b
                         on b.id=v.business_posting_batch_id where b.source_type='LANDED_COST_ALLOCATION'
                         and b.source_id=a.id and b.posting_action='POST' and v.status<>'CANCELLED'
                         order by v.id desc limit 1),'') accounting_voucher_no
                from landed_cost_allocation a where a.id=?
                """, id);
        List<LandedCostDtos.AllocationItem> items = jdbc.query("""
                select i.*,o.order_no inbound_no,b.batch_no,p.sku,p.name product_name,p.spec,w.name warehouse_name
                from landed_cost_allocation_item i join inbound_order o on o.id=i.inbound_id
                join inventory_batch b on b.id=i.original_batch_id join product p on p.id=i.product_id
                join warehouse w on w.id=i.warehouse_id where i.allocation_id=? order by i.id
                """, (rs, rowNum) -> new LandedCostDtos.AllocationItem(
                rs.getLong("id"), rs.getLong("inbound_id"), rs.getString("inbound_no"),
                rs.getLong("inbound_item_id"), rs.getLong("original_batch_id"), rs.getString("batch_no"),
                rs.getLong("product_id"), rs.getString("sku"), rs.getString("product_name"), rs.getString("spec"),
                rs.getLong("warehouse_id"), rs.getString("warehouse_name"), rs.getBigDecimal("original_quantity"),
                rs.getBigDecimal("remaining_quantity_snapshot"), rs.getString("base_unit"),
                rs.getBigDecimal("basis_value"), rs.getBigDecimal("allocated_amount"),
                rs.getBigDecimal("landed_unit_amount"), rs.getBigDecimal("capitalized_amount"),
                rs.getBigDecimal("expensed_amount")), id);
        List<LandedCostDtos.BatchAllocation> batches = jdbc.query("""
                select x.*,b.batch_no,w.name warehouse_name from landed_cost_allocation_batch x
                join inventory_batch b on b.id=x.batch_id join warehouse w on w.id=x.warehouse_id
                where x.allocation_id=? order by x.id
                """, (rs, rowNum) -> new LandedCostDtos.BatchAllocation(
                rs.getLong("id"), rs.getLong("allocation_item_id"), rs.getLong("batch_id"),
                rs.getString("batch_no"), rs.getString("warehouse_name"), rs.getBigDecimal("quantity_snapshot"),
                rs.getBigDecimal("old_unit_cost"), rs.getBigDecimal("allocated_amount"),
                rs.getBigDecimal("new_unit_cost"), rs.getBigDecimal("old_amount_cost"),
                rs.getBigDecimal("new_amount_cost")), id);
        List<LandedCostDtos.Event> events = jdbc.query("""
                select id,action,from_status,to_status,reason,operator_name,created_at
                from landed_cost_allocation_event where allocation_id=? order by id
                """, (rs, rowNum) -> new LandedCostDtos.Event(
                rs.getLong("id"), rs.getString("action"), rs.getString("from_status"),
                rs.getString("to_status"), rs.getString("reason"), rs.getString("operator_name"),
                rs.getTimestamp("created_at").toLocalDateTime()), id);
        return new LandedCostDtos.AllocationDetail(headers.get(0), text(links.get("cancel_reason")),
                text(links.get("reversal_reason")), number(links.get("posting_batch_id")),
                number(links.get("accounting_voucher_id")), text(links.get("accounting_voucher_no")),
                items, batches, events);
    }

    @Transactional
    public Long create(LandedCostDtos.AllocationCreate request) {
        return saveDraft(null, request);
    }

    @Transactional
    public Long update(Long id, LandedCostDtos.AllocationCreate request) {
        AllocationLock allocation = lockAllocation(id);
        if (!"DRAFT".equals(allocation.status())) throw new BusinessException("只有草稿采购费用分摊单可以修改");
        return saveDraft(id, request);
    }

    private Long saveDraft(Long existingId, LandedCostDtos.AllocationCreate request) {
        String method = enumValue(request.allocationMethod(), METHODS, "分摊方式");
        SourceLock source = lockSource(request.treasuryDocumentId());
        validateSource(source, existingId);
        ensureOpenPeriod(request.businessDate());
        if (request.items() == null || request.items().isEmpty()) throw new BusinessException("请至少选择一条采购入库明细");
        HashSet<Long> seen = new HashSet<>();
        List<PreparedItem> prepared = new ArrayList<>();
        BigDecimal basisTotal = ZERO;
        BigDecimal manualTotal = ZERO;
        for (LandedCostDtos.ItemCreate item : request.items()) {
            if (!seen.add(item.inboundItemId())) throw new BusinessException("同一采购入库明细不能重复分摊");
            InboundItem inbound = lockInboundItem(item.inboundItemId());
            dataScopeService.assertDocumentAllowed("INBOUND", inbound.inboundId());
            dataScopeService.assertMasterAllowed("PRODUCT", inbound.productId());
            dataScopeService.assertMasterAllowed("WAREHOUSE", inbound.warehouseId());
            BigDecimal basis = switch (method) {
                case "QUANTITY" -> inbound.originalQuantity();
                case "AMOUNT" -> inbound.amountExcludingTax();
                case "WEIGHT" -> positive(item.basisValue(), "重量分摊依据");
                case "MANUAL" -> positive(item.allocatedAmount(), "手工分摊金额");
                default -> throw new BusinessException("分摊方式无效");
            };
            if (basis.compareTo(ZERO) <= 0) throw new BusinessException("所选明细的分摊依据必须大于0");
            BigDecimal manual = "MANUAL".equals(method) ? money(item.allocatedAmount()) : ZERO;
            prepared.add(new PreparedItem(inbound, basis, manual));
            basisTotal = basisTotal.add(basis);
            manualTotal = manualTotal.add(manual);
        }
        BigDecimal fee = money(source.capitalizableAmount());
        if (fee.compareTo(ZERO) <= 0) throw new BusinessException("采购附加费用计入成本金额必须大于0");
        if ("MANUAL".equals(method) && manualTotal.compareTo(fee) != 0)
            throw new BusinessException("手工分摊金额合计必须等于费用计入成本金额 " + fee);
        if (!"MANUAL".equals(method)) {
            BigDecimal used = ZERO;
            for (int index = 0; index < prepared.size(); index++) {
                PreparedItem row = prepared.get(index);
                BigDecimal amount = index == prepared.size() - 1
                        ? fee.subtract(used)
                        : money(fee.multiply(row.basis()).divide(basisTotal, 10, RoundingMode.HALF_UP));
                prepared.set(index, new PreparedItem(row.inbound(), row.basis(), amount));
                used = used.add(amount);
            }
        }
        Actor actor = actor();
        Long id = existingId;
        if (id == null) {
            id = insert("""
                    insert into landed_cost_allocation
                    (allocation_no,treasury_document_id,business_date,allocation_method,fee_amount,allocated_amount,
                     memo,created_by,created_by_name) values (?,?,?,?,?,?,?, ?,?)
                    """, nextNo(), source.id(), Date.valueOf(request.businessDate()), method, fee, fee,
                    text(request.memo()), actor.id(), actor.name());
            event(id, "CREATE", "", "DRAFT", "建立采购附加费用分摊草稿", actor);
            dataScopeService.stampDocumentOwner("LANDED_COST_ALLOCATION", id);
        } else {
            jdbc.update("""
                    update landed_cost_allocation set treasury_document_id=?,business_date=?,allocation_method=?,
                      fee_amount=?,allocated_amount=?,memo=? where id=? and status='DRAFT'
                    """, source.id(), Date.valueOf(request.businessDate()), method, fee, fee,
                    text(request.memo()), id);
            jdbc.update("delete from landed_cost_allocation_item where allocation_id=?", id);
            event(id, "UPDATE", "DRAFT", "DRAFT", "修改分摊依据并重新计算", actor);
        }
        for (PreparedItem row : prepared) {
            InboundItem inbound = row.inbound();
            insert("""
                    insert into landed_cost_allocation_item
                    (allocation_id,inbound_id,inbound_item_id,original_batch_id,product_id,warehouse_id,
                     original_quantity,base_unit,basis_value,allocated_amount,landed_unit_amount)
                    values (?,?,?,?,?,?,?,?,?,?,?)
                    """, id, inbound.inboundId(), inbound.id(), inbound.batchId(), inbound.productId(),
                    inbound.warehouseId(), inbound.originalQuantity(), inbound.baseUnit(), row.basis(), row.amount(),
                    row.amount().divide(inbound.originalQuantity(), 6, RoundingMode.HALF_UP));
        }
        return id;
    }

    @Transactional
    public Long confirm(Long id) {
        AllocationLock allocation = lockAllocation(id);
        if ("CONFIRMED".equals(allocation.status())) return id;
        if (!"DRAFT".equals(allocation.status())) throw new BusinessException("只有草稿采购费用分摊单可以确认");
        dataScopeService.assertDocumentAllowed("LANDED_COST_ALLOCATION", id);
        ensureOpenPeriod(allocation.businessDate());
        SourceLock source = lockSource(allocation.treasuryDocumentId());
        validateSource(source, id);
        List<BusinessPostingDtos.EntryDraft> entries = new ArrayList<>();
        BigDecimal totalCapitalized = ZERO;
        BigDecimal totalExpensed = ZERO;
        for (ItemLock item : lockItems(id)) {
            List<BatchRow> batches = lineageBatches(item.originalBatchId());
            assertNoInTransit(batches, "存在调拨在途数量，必须先完成收货或冲正再确认费用分摊");
            BigDecimal remaining = batches.stream().map(BatchRow::quantity).reduce(ZERO, BigDecimal::add);
            BigDecimal eligible = remaining.min(item.originalQuantity()).max(ZERO);
            BigDecimal capitalized = eligible.compareTo(ZERO) == 0 ? ZERO
                    : money(item.allocatedAmount().multiply(eligible).divide(item.originalQuantity(), 10, RoundingMode.HALF_UP));
            BigDecimal expensed = item.allocatedAmount().subtract(capitalized);
            List<BatchRow> positiveBatches = batches.stream().filter(batch -> batch.quantity().compareTo(ZERO) > 0).toList();
            BigDecimal used = ZERO;
            for (int index = 0; index < positiveBatches.size(); index++) {
                BatchRow batch = lockBatch(positiveBatches.get(index).id());
                dataScopeService.assertMasterAllowed("PRODUCT", batch.productId());
                dataScopeService.assertMasterAllowed("WAREHOUSE", batch.warehouseId());
                BigDecimal share = index == positiveBatches.size() - 1
                        ? capitalized.subtract(used)
                        : money(capitalized.multiply(batch.quantity()).divide(remaining, 10, RoundingMode.HALF_UP));
                if (share.compareTo(ZERO) < 0) share = ZERO;
                BigDecimal newAmount = money(batch.amount().add(share));
                BigDecimal newUnit = newAmount.divide(batch.quantity(), 4, RoundingMode.HALF_UP);
                jdbc.update("update inventory_batch set unit_cost=?,amount_cost=? where id=?", newUnit, newAmount, batch.id());
                changeStock(batch.productId(), batch.warehouseId(), share);
                movement("LAND_COST", batch, ZERO, newUnit, share, "LANDED_COST_ALLOCATION_ITEM", item.id(),
                        "采购附加费用资本化 " + allocation.allocationNo());
                insert("""
                        insert into landed_cost_allocation_batch
                        (allocation_id,allocation_item_id,batch_id,product_id,warehouse_id,quantity_snapshot,
                         old_unit_cost,old_amount_cost,allocated_amount,new_unit_cost,new_amount_cost)
                        values (?,?,?,?,?,?,?,?,?,?,?)
                        """, id, item.id(), batch.id(), batch.productId(), batch.warehouseId(), batch.quantity(),
                        batch.unitCost(), batch.amount(), share, newUnit, newAmount);
                BigDecimal componentUnit = share.compareTo(ZERO) == 0 ? ZERO
                        : share.divide(batch.quantity(), 6, RoundingMode.HALF_UP);
                insert("""
                        insert into inventory_batch_landed_cost_component
                        (allocation_id,allocation_item_id,batch_id,landed_unit_amount,status)
                        values (?,?,?,?,'ACTIVE')
                        """, id, item.id(), batch.id(), componentUnit);
                if (share.compareTo(ZERO) > 0) entries.add(new BusinessPostingDtos.EntryDraft(
                        "LANDED_COST_CAPITALIZED", "INCREASE", source.partnerId(), batch.productId(),
                        batch.warehouseId(), batch.id(), "LANDED_COST_ALLOCATION_ITEM", item.id(), ZERO, "",
                        share, "采购附加费用计入当前批次成本"));
                used = used.add(share);
            }
            jdbc.update("""
                    update landed_cost_allocation_item set remaining_quantity_snapshot=?,capitalized_amount=?,expensed_amount=?
                    where id=?
                    """, remaining, capitalized, expensed, item.id());
            totalCapitalized = totalCapitalized.add(capitalized);
            totalExpensed = totalExpensed.add(expensed);
        }
        Actor actor = actor();
        jdbc.update("""
                update landed_cost_allocation set status='CONFIRMED',capitalized_amount=?,expensed_amount=?,
                  confirmed_by=?,confirmed_by_name=?,confirmed_at=now(6) where id=? and status='DRAFT'
                """, totalCapitalized, totalExpensed, actor.id(), actor.name(), id);
        event(id, "CONFIRM", "DRAFT", "CONFIRMED",
                "计入成本金额=" + allocation.feeAmount() + "；资本化=" + totalCapitalized + "；已耗用留存成本=" + totalExpensed, actor);
        if (!entries.isEmpty()) postingService.post("LANDED_COST_ALLOCATION", id, allocation.allocationNo(), "POST", 1,
                allocation.businessDate(), source.partnerId(), 0L, "采购附加费用按当前结存资本化", entries);
        return id;
    }

    @Transactional
    public Long cancel(Long id, String reason) {
        AllocationLock allocation = lockAllocation(id);
        if ("CANCELLED".equals(allocation.status())) return id;
        if (!"DRAFT".equals(allocation.status())) throw new BusinessException("只有草稿采购费用分摊单可以作废");
        String required = requiredReason(reason);
        Actor actor = actor();
        jdbc.update("update landed_cost_allocation set status='CANCELLED',cancel_reason=?,cancelled_at=now() where id=?",
                required, id);
        event(id, "CANCEL", "DRAFT", "CANCELLED", required, actor);
        return id;
    }

    @Transactional
    public Long reverse(Long id, LandedCostDtos.Action action) {
        AllocationLock allocation = lockAllocation(id);
        if ("REVERSED".equals(allocation.status())) return id;
        if (!"CONFIRMED".equals(allocation.status())) throw new BusinessException("只有已确认采购费用分摊单可以冲正");
        String reason = requiredReason(action.reason());
        LocalDate businessDate = action.businessDate() == null ? LocalDate.now() : action.businessDate();
        ensureOpenPeriod(businessDate);
        assertNoLaterDependentDocuments(id, allocation.confirmedAt());
        List<ComponentRow> components = lockComponents(id);
        List<BatchRow> componentBatches = new ArrayList<>();
        for (ComponentRow component : components) componentBatches.add(lockBatch(component.batchId()));
        assertNoInTransit(componentBatches, "费用组件所在批次存在调拨在途，必须先完成收货或冲正");
        SourceLock source = lockSource(allocation.treasuryDocumentId());
        List<BusinessPostingDtos.EntryDraft> entries = new ArrayList<>();
        BigDecimal removedTotal = ZERO;
        for (int index = 0; index < components.size(); index++) {
            ComponentRow component = components.get(index);
            BatchRow batch = lockBatch(component.batchId());
            dataScopeService.assertMasterAllowed("PRODUCT", batch.productId());
            dataScopeService.assertMasterAllowed("WAREHOUSE", batch.warehouseId());
            BigDecimal removable = rollbackAmount(component, batch);
            if (removable.compareTo(batch.amount()) > 0) throw new BusinessException("批次成本不足以回退采购附加费用，请先检查后续成本调整");
            if (removable.compareTo(ZERO) > 0) {
                BigDecimal newAmount = money(batch.amount().subtract(removable));
                BigDecimal newUnit = batch.quantity().compareTo(ZERO) > 0
                        ? newAmount.divide(batch.quantity(), 4, RoundingMode.HALF_UP) : batch.unitCost();
                jdbc.update("update inventory_batch set unit_cost=?,amount_cost=? where id=?", newUnit, newAmount, batch.id());
                changeStock(batch.productId(), batch.warehouseId(), removable.negate());
                movement("LAND_COST_ROLLBACK", batch, ZERO, newUnit, removable.negate(),
                        "LANDED_COST_ALLOCATION_REVERSAL", id, "采购附加费用冲正 " + allocation.allocationNo());
                entries.add(new BusinessPostingDtos.EntryDraft(
                        "LANDED_COST_CAPITALIZED", "DECREASE", source.partnerId(), batch.productId(),
                        batch.warehouseId(), batch.id(), "LANDED_COST_ALLOCATION_COMPONENT", component.id(), ZERO, "",
                        removable, "按冲正时当前结存回退采购附加费用"));
                removedTotal = removedTotal.add(removable);
            }
            jdbc.update("update inventory_batch_landed_cost_component set status='REVERSED' where id=? and status='ACTIVE'",
                    component.id());
        }
        Actor actor = actor();
        jdbc.update("""
                update landed_cost_allocation set status='REVERSED',reversal_reason=?,reversed_at=now()
                where id=? and status='CONFIRMED'
                """, reason, id);
        event(id, "REVERSE", "CONFIRMED", "REVERSED",
                reason + "；按当前结存回退=" + removedTotal + "；历史已耗用成本不改写", actor);
        if (!entries.isEmpty()) postingService.post("LANDED_COST_ALLOCATION", id, allocation.allocationNo(), "ROLLBACK", 1,
                businessDate, source.partnerId(), 0L, reason, entries);
        return id;
    }

    private void assertNoLaterDependentDocuments(Long allocationId, LocalDateTime confirmedAt) {
        long laterReturns = scalar("""
                select count(*) from purchase_return_order r join purchase_return_item ri on ri.return_id=r.id
                join landed_cost_allocation_item li on li.inbound_item_id=ri.inbound_item_id
                where li.allocation_id=? and r.status='CONFIRMED' and r.confirmed_at>?
                """, allocationId, confirmedAt);
        if (laterReturns > 0) throw new BusinessException("费用分摊后存在已确认采购退货，请先按时间倒序冲正采购退货");
        long laterTransfers = scalar("""
                select count(*) from inventory_transfer_order t join inventory_transfer_item ti on ti.transfer_id=t.id
                join inventory_batch_landed_cost_component c on c.batch_id in (ti.source_batch_id,ti.target_batch_id)
                where c.allocation_id=? and t.status in ('IN_TRANSIT','COMPLETED') and t.dispatched_at>?
                """, allocationId, confirmedAt);
        if (laterTransfers > 0) throw new BusinessException("费用分摊后存在未冲正的库存调拨，请先按时间倒序冲正调拨");
        long laterCosts = scalar("""
                select count(*) from inventory_cost_adjustment a join inventory_cost_adjustment_item ai on ai.adjustment_id=a.id
                join inventory_batch_landed_cost_component c on c.batch_id=ai.batch_id
                where c.allocation_id=? and a.status='CONFIRMED' and a.confirmed_at>?
                """, allocationId, confirmedAt);
        if (laterCosts > 0) throw new BusinessException("费用分摊后存在已确认成本调整，请先按时间倒序冲正成本调整");
    }

    private BigDecimal rollbackAmount(ComponentRow component, BatchRow batch) {
        if (batch.quantity().compareTo(ZERO) <= 0) return ZERO;
        List<Map<String, Object>> snapshot = jdbc.queryForList("""
                select quantity_snapshot,allocated_amount from landed_cost_allocation_batch
                where allocation_item_id=? and batch_id=? limit 1
                """, component.allocationItemId(), batch.id());
        if (!snapshot.isEmpty() && decimal(snapshot.get(0).get("quantity_snapshot")).compareTo(batch.quantity()) == 0)
            return money(decimal(snapshot.get(0).get("allocated_amount")));
        return money(batch.quantity().multiply(component.unitAmount()));
    }

    private List<BatchRow> lineageBatches(Long rootBatchId) {
        return jdbc.query("""
                with recursive lineage as (
                  select b.id,b.product_id,b.warehouse_id,b.batch_no,b.quantity_available,b.unit_cost,b.amount_cost
                  from inventory_batch b where b.id=?
                  union all
                  select target.id,target.product_id,target.warehouse_id,target.batch_no,target.quantity_available,
                         target.unit_cost,target.amount_cost
                  from lineage l join inventory_transfer_item ti on ti.source_batch_id=l.id
                  join inventory_transfer_order t on t.id=ti.transfer_id and t.status='COMPLETED'
                  join inventory_batch target on target.id=ti.target_batch_id
                ) select * from lineage order by id
                """, batchMapper(), rootBatchId);
    }

    private void assertNoInTransit(List<BatchRow> batches, String message) {
        if (batches.isEmpty()) return;
        String ids = placeholders(batches.size());
        Object[] args = batches.stream().map(BatchRow::id).toArray();
        if (scalar("select count(*) from inventory_transfer_item i join inventory_transfer_order o on o.id=i.transfer_id " +
                "where o.status='IN_TRANSIT' and i.source_batch_id in (" + ids + ")", args) > 0) throw new BusinessException(message);
    }

    private List<ComponentRow> lockComponents(Long allocationId) {
        return jdbc.query("""
                select id,allocation_item_id,batch_id,landed_unit_amount
                from inventory_batch_landed_cost_component where allocation_id=? and status='ACTIVE'
                order by id for update
                """, (rs, rowNum) -> new ComponentRow(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getBigDecimal(4)), allocationId);
    }

    private SourceLock lockSource(Long id) {
        List<SourceLock> rows = jdbc.query("""
                select id,document_no,partner_id,business_date,
                       case when input_tax_treatment='INCLUDED_IN_COST' then total_amount else amount_excluding_tax end,
                       input_tax_treatment,status,document_type,
                       offset_account_code_snapshot from treasury_document where id=? for update
                """, (rs, rowNum) -> new SourceLock(rs.getLong(1), rs.getString(2), rs.getLong(3),
                rs.getDate(4).toLocalDate(), rs.getBigDecimal(5), rs.getString(6), rs.getString(7),
                rs.getString(8), rs.getString(9)), id);
        if (rows.isEmpty()) throw new BusinessException("采购附加费用源单不存在");
        return rows.get(0);
    }

    private void validateSource(SourceLock source, Long existingAllocationId) {
        dataScopeService.assertDocumentAllowed("TREASURY_DOCUMENT", source.id());
        if (!"PURCHASE_LANDED_COST".equals(source.documentType())) throw new BusinessException("所选源单不是采购附加费用");
        if (!List.of("CONFIRMED", "PARTIALLY_SETTLED", "SETTLED").contains(source.status()))
            throw new BusinessException("采购附加费用必须先确认，才能分摊到库存成本");
        if (!"5401".equals(source.offsetAccountCode())) throw new BusinessException("采购附加费用必须使用主营业务成本科目 5401");
        long occupied = scalar("""
                select count(*) from landed_cost_allocation where treasury_document_id=?
                  and status in ('DRAFT','CONFIRMED') and id<>?
                """, source.id(), existingAllocationId == null ? 0L : existingAllocationId);
        if (occupied > 0) throw new BusinessException("该费用源单已经存在有效分摊单，不能重复分摊");
    }

    private InboundItem lockInboundItem(Long id) {
        List<InboundItem> rows = jdbc.query("""
                select i.id,i.inbound_id,i.inventory_batch_id,i.product_id,o.warehouse_id,i.quantity,i.base_unit,
                       i.amount_excluding_tax,o.status from inbound_item i join inbound_order o on o.id=i.inbound_id
                where i.id=? for update
                """, (rs, rowNum) -> new InboundItem(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4),
                rs.getLong(5), rs.getBigDecimal(6), rs.getString(7), rs.getBigDecimal(8), rs.getString(9)), id);
        if (rows.isEmpty() || !"STOCKED".equals(rows.get(0).status()) || rows.get(0).batchId() <= 0)
            throw new BusinessException("采购入库明细不存在、尚未入库或已经冲正");
        return rows.get(0);
    }

    private AllocationLock lockAllocation(Long id) {
        List<AllocationLock> rows = jdbc.query("""
                select id,allocation_no,treasury_document_id,business_date,fee_amount,status,confirmed_at
                from landed_cost_allocation where id=? for update
                """, (rs, rowNum) -> new AllocationLock(rs.getLong(1), rs.getString(2), rs.getLong(3),
                rs.getDate(4).toLocalDate(), rs.getBigDecimal(5), rs.getString(6),
                rs.getTimestamp(7) == null ? null : rs.getTimestamp(7).toLocalDateTime()), id);
        if (rows.isEmpty()) throw new BusinessException("采购费用分摊单不存在");
        return rows.get(0);
    }

    private List<ItemLock> lockItems(Long id) {
        return jdbc.query("""
                select id,inbound_item_id,original_batch_id,product_id,warehouse_id,original_quantity,base_unit,
                       allocated_amount from landed_cost_allocation_item where allocation_id=? order by id for update
                """, (rs, rowNum) -> new ItemLock(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4),
                rs.getLong(5), rs.getBigDecimal(6), rs.getString(7), rs.getBigDecimal(8)), id);
    }

    private BatchRow lockBatch(Long id) {
        List<BatchRow> rows = jdbc.query("""
                select id,product_id,warehouse_id,batch_no,quantity_available,unit_cost,amount_cost
                from inventory_batch where id=? for update
                """, batchMapper(), id);
        if (rows.isEmpty()) throw new BusinessException("库存批次不存在");
        return rows.get(0);
    }

    private org.springframework.jdbc.core.RowMapper<BatchRow> batchMapper() {
        return (rs, rowNum) -> new BatchRow(rs.getLong("id"), rs.getLong("product_id"), rs.getLong("warehouse_id"),
                rs.getString("batch_no"), rs.getBigDecimal("quantity_available"), rs.getBigDecimal("unit_cost"),
                rs.getBigDecimal("amount_cost"));
    }

    private String headerSql() {
        return """
                select a.*,d.document_no treasury_document_no,d.partner_name_snapshot fee_partner_name
                from landed_cost_allocation a join treasury_document d on d.id=a.treasury_document_id
                """;
    }

    private org.springframework.jdbc.core.RowMapper<LandedCostDtos.AllocationRow> allocationRowMapper() {
        return (rs, rowNum) -> new LandedCostDtos.AllocationRow(
                rs.getLong("id"), rs.getString("allocation_no"), rs.getLong("treasury_document_id"),
                rs.getString("treasury_document_no"), rs.getString("fee_partner_name"),
                rs.getDate("business_date").toLocalDate(), rs.getString("allocation_method"), rs.getString("status"),
                rs.getBigDecimal("fee_amount"), rs.getBigDecimal("allocated_amount"),
                rs.getBigDecimal("capitalized_amount"), rs.getBigDecimal("expensed_amount"), rs.getString("memo"),
                time(rs.getTimestamp("confirmed_at")), time(rs.getTimestamp("created_at")));
    }

    private void ensureOpenPeriod(LocalDate date) {
        if (scalar("""
                select count(*) from accounting_period p join accounting_entity e on e.id=p.entity_id
                where e.id=(select min(id) from accounting_entity where status='ENABLED')
                  and ? between p.start_date and p.end_date and p.status='OPEN'
                """, Date.valueOf(date)) == 0) throw new BusinessException("业务日期没有开放的会计期间");
    }

    private void changeStock(Long productId, Long warehouseId, BigDecimal amountDiff) {
        int updated = jdbc.update("update stock_balance set amount_cost=amount_cost+? where product_id=? and warehouse_id=?",
                amountDiff, productId, warehouseId);
        if (updated != 1) throw new BusinessException("库存汇总不存在，不能调整批次成本");
        BigDecimal balance = jdbc.queryForObject("select amount_cost from stock_balance where product_id=? and warehouse_id=? for update",
                BigDecimal.class, productId, warehouseId);
        if (balance != null && balance.compareTo(new BigDecimal("-0.01")) < 0)
            throw new BusinessException("库存汇总成本将出现负数，费用分摊已拒绝");
    }

    private void movement(String type, BatchRow batch, BigDecimal quantity, BigDecimal unitCost, BigDecimal amount,
                          String refType, Long refId, String memo) {
        insert("""
                insert into inventory_movement
                (movement_no,movement_type,product_id,warehouse_id,batch_id,quantity,unit_price,amount,ref_type,ref_id,memo)
                values (?,?,?,?,?,?,?,?,?,?,?)
                """, nextNo(), type, batch.productId(), batch.warehouseId(), batch.id(), quantity, unitCost, amount,
                refType, refId, memo);
    }

    private void event(Long id, String action, String from, String to, String reason, Actor actor) {
        jdbc.update("""
                insert into landed_cost_allocation_event
                (allocation_id,action,from_status,to_status,reason,operator_id,operator_name)
                values (?,?,?,?,?,?,?)
                """, id, action, from, to, text(reason), actor.id(), actor.name());
    }

    private Long insert(String sql, Object... args) {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int index = 0; index < args.length; index++) ps.setObject(index + 1, args[index]);
            return ps;
        }, holder);
        if (holder.getKey() == null) throw new IllegalStateException("数据库未返回新增记录主键");
        return holder.getKey().longValue();
    }

    private long scalar(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0L : value;
    }

    private String enumValue(String value, Set<String> allowed, String label) {
        String normalized = text(value).toUpperCase();
        if (!allowed.contains(normalized)) throw new BusinessException(label + "无效");
        return normalized;
    }

    private BigDecimal suggestedWeight(BigDecimal quantity, String unit) {
        String normalized = text(unit).toUpperCase();
        if (Set.of("KG", "公斤", "千克").contains(normalized)) return quantity;
        if (Set.of("T", "吨").contains(normalized)) return quantity.multiply(BigDecimal.valueOf(1000));
        return ZERO;
    }

    private BigDecimal positive(BigDecimal value, String label) {
        if (value == null || value.compareTo(ZERO) <= 0) throw new BusinessException(label + "必须大于0");
        return value;
    }

    private String requiredReason(String value) {
        if (!StringUtils.hasText(value) || value.trim().length() < 4) throw new BusinessException("原因至少填写4个字");
        if (value.trim().length() > 500) throw new BusinessException("原因不能超过500字");
        return value.trim();
    }

    private Actor actor() {
        SecurityContext.Principal principal = SecurityContext.get();
        return principal == null ? new Actor(0L, "系统") : new Actor(principal.userId(), principal.employeeName());
    }

    private String nextNo() {
        return "FYCB" + LocalDateTime.now().format(NO_TIME)
                + String.format("%03d", Math.floorMod(NO_COUNTER.incrementAndGet(), 1000));
    }

    private BigDecimal money(BigDecimal value) { return value.setScale(2, RoundingMode.HALF_UP); }
    private BigDecimal decimal(Object value) { return value == null ? ZERO : new BigDecimal(String.valueOf(value)); }
    private String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    private Long number(Object value) { return value instanceof Number number ? number.longValue() : 0L; }
    private LocalDateTime time(java.sql.Timestamp value) { return value == null ? null : value.toLocalDateTime(); }
    private String placeholders(int count) { return String.join(",", java.util.Collections.nCopies(count, "?")); }

    private record Actor(Long id, String name) {}
    private record SourceLock(Long id, String documentNo, Long partnerId, LocalDate businessDate,
                              BigDecimal capitalizableAmount, String inputTaxTreatment,
                              String status, String documentType, String offsetAccountCode) {}
    private record InboundItem(Long id, Long inboundId, Long batchId, Long productId, Long warehouseId,
                               BigDecimal originalQuantity, String baseUnit, BigDecimal amountExcludingTax, String status) {}
    private record PreparedItem(InboundItem inbound, BigDecimal basis, BigDecimal amount) {}
    private record AllocationLock(Long id, String allocationNo, Long treasuryDocumentId, LocalDate businessDate,
                                  BigDecimal feeAmount, String status, LocalDateTime confirmedAt) {}
    private record ItemLock(Long id, Long inboundItemId, Long originalBatchId, Long productId, Long warehouseId,
                            BigDecimal originalQuantity, String baseUnit, BigDecimal allocatedAmount) {}
    private record BatchRow(Long id, Long productId, Long warehouseId, String batchNo,
                            BigDecimal quantity, BigDecimal unitCost, BigDecimal amount) {}
    private record ComponentRow(Long id, Long allocationItemId, Long batchId, BigDecimal unitAmount) {}
    private record ScopedInbound(String sourceType, Long sourceId, LandedCostDtos.InboundOption row) {}
}
