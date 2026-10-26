package com.soyia.copperv2.service;

import com.soyia.copperv2.dto.LightweightOperationsDtos;
import com.soyia.copperv2.security.BusinessDataScopeService;
import com.soyia.copperv2.security.ForbiddenException;
import com.soyia.copperv2.security.SecurityContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class LightweightOperationsService {
    private static final BigDecimal ZERO = new BigDecimal("0.00");
    private static final DateTimeFormatter NO_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final AtomicLong NO_COUNTER = new AtomicLong();
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Set<String> SOURCE_TYPES = Set.of("SALES_ORDER", "OUTBOUND", "DIRECT_TRADE");
    private final JdbcTemplate jdbc;
    private final BusinessDataScopeService dataScopeService;

    public LightweightOperationsService(JdbcTemplate jdbc, BusinessDataScopeService dataScopeService) {
        this.jdbc = jdbc;
        this.dataScopeService = dataScopeService;
    }

    public List<LightweightOperationsDtos.ReplenishmentRow> replenishment() {
        requireCompleteOperationalScope();
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select sb.product_id,p.sku,p.name product_name,sb.warehouse_id,w.name warehouse_name,
                       p.unit base_unit,sb.quantity_available on_hand,sb.amount_cost stock_amount,
                       coalesce((select sum(a.quantity) from outbound_allocation a
                         join outbound_order o on o.id=a.outbound_id
                         where a.product_id=sb.product_id and o.warehouse_id=sb.warehouse_id
                           and a.status='ACTIVE' and o.status='DRAFT'),0) reserved_quantity,
                       coalesce((select sum(greatest(i.quantity-i.received_quantity,0))
                         from purchase_order_item i join purchase_order o on o.id=i.purchase_order_id
                         where i.product_id=sb.product_id and o.expected_warehouse_id=sb.warehouse_id
                           and i.status='ENABLED' and o.status not in ('DRAFT','CANCELLED','COMPLETED')),0) incoming_quantity,
                       coalesce((select sum(i.quantity) from outbound_item i
                         join outbound_order o on o.id=i.outbound_id
                         where i.product_id=sb.product_id and o.warehouse_id=sb.warehouse_id
                           and o.status='SHIPPED' and o.business_date>=date_sub(curdate(),interval 29 day)),0) sales_30,
                       coalesce((select max(r.lead_time_days)
                         from supplier_product_offer x join supplier_product_offer_revision r
                           on r.id=x.current_revision_id
                         where x.product_id=sb.product_id and x.status='ENABLED'
                           and r.status='PUBLISHED' and r.effective_from<=curdate()
                           and (r.effective_to is null or r.effective_to>=curdate())),7) lead_days,
                       (select min(b.received_at) from inventory_batch b
                         where b.product_id=sb.product_id and b.warehouse_id=sb.warehouse_id
                           and b.quantity_available>0 and b.status='AVAILABLE') oldest_batch_date
                from stock_balance sb join product p on p.id=sb.product_id
                join warehouse w on w.id=sb.warehouse_id
                where p.status='ENABLED' and w.status='ENABLED'
                order by p.name,w.name
                """);
        LocalDate today = LocalDate.now();
        List<LightweightOperationsDtos.ReplenishmentRow> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            BigDecimal onHand = quantity(row.get("on_hand"));
            BigDecimal reserved = quantity(row.get("reserved_quantity"));
            BigDecimal free = onHand.subtract(reserved).max(BigDecimal.ZERO);
            BigDecimal incoming = quantity(row.get("incoming_quantity"));
            BigDecimal sales30 = quantity(row.get("sales_30"));
            BigDecimal daily = sales30.divide(BigDecimal.valueOf(30), 6, RoundingMode.HALF_UP);
            int lead = Math.max(0, integer(row.get("lead_days")));
            int targetDays = lead + 14;
            BigDecimal suggested = daily.multiply(BigDecimal.valueOf(targetDays))
                    .subtract(free).subtract(incoming).max(BigDecimal.ZERO).setScale(6, RoundingMode.HALF_UP);
            BigDecimal coverage = daily.signum() == 0 ? null
                    : free.divide(daily, 1, RoundingMode.HALF_UP);
            LocalDate oldest = localDate(row.get("oldest_batch_date"));
            int age = oldest == null ? 0 : (int) Math.max(0, ChronoUnit.DAYS.between(oldest, today));
            String level = suggested.signum() > 0 ? "REPLENISH"
                    : age >= 180 && onHand.signum() > 0 ? "AGED" : "NORMAL";
            String explanation = daily.signum() == 0
                    ? (age >= 180 ? "近30天无出库且最老批次已超过180天，建议先消化库存" : "近30天无出库，不建议自动补货")
                    : "建议量=日均出库×(交期" + lead + "天+安全期14天)-可用库存-在途采购";
            result.add(new LightweightOperationsDtos.ReplenishmentRow(
                    number(row, "product_id"), text(row.get("sku")), text(row.get("product_name")),
                    number(row, "warehouse_id"), text(row.get("warehouse_name")), text(row.get("base_unit")),
                    onHand, reserved, free, incoming, sales30, daily, lead, targetDays, coverage,
                    suggested, oldest, age, money(row.get("stock_amount")), level, explanation));
        }
        return result;
    }

    public List<LightweightOperationsDtos.ContributionRow> contributions(
            LocalDate from, LocalDate to, String sourceType, Long sourceId) {
        requireCompleteOperationalScope();
        LocalDate start = from == null ? LocalDate.now().withDayOfMonth(1) : from;
        LocalDate end = to == null ? LocalDate.now() : to;
        if (start.isAfter(end)) throw new BusinessException("开始日期不能晚于结束日期");
        String type = StringUtils.hasText(sourceType) ? sourceType.trim().toUpperCase(Locale.ROOT) : "";
        if (!type.isEmpty() && !SOURCE_TYPES.contains(type)) throw new BusinessException("销售贡献来源类型无效");
        List<LightweightOperationsDtos.ContributionRow> rows = new ArrayList<>();
        if (type.isEmpty() || "SALES_ORDER".equals(type)) {
            rows.addAll(jdbc.query("""
                    select 'SALES_ORDER' source_type,o.id source_id,o.order_no source_no,
                           o.customer_id partner_id,p.name partner_name,o.business_date,o.status,
                           coalesce((select sum(x.total_amount) from outbound_order x
                             where x.sales_order_id=o.id and x.status='SHIPPED'),0) revenue,
                           coalesce((select sum(i.inventory_amount_cost)
                             from outbound_order x join outbound_item i on i.outbound_id=x.id
                             where x.sales_order_id=o.id and x.status='SHIPPED'),0) inventory_cost,
                           coalesce((select sum(a.amount) from sales_cost_allocation a
                             where a.source_type='SALES_ORDER' and a.source_id=o.id and a.status='ACTIVE'),0) allocated_expense,
                           greatest(o.receivable_amount-o.received_amount-o.adjusted_amount,0) outstanding_amount
                    from sales_order o join partner p on p.id=o.customer_id
                    where o.source_type<>'DIRECT_TRADE' and o.business_date between ? and ?
                      and o.status not in ('DRAFT','CANCELLED')
                      and (?=0 or o.id=?)
                    order by o.business_date desc,o.id desc
                    """, (rs, rowNum) -> contributionRow(
                    rs.getString("source_type"), rs.getLong("source_id"), rs.getString("source_no"),
                    rs.getLong("partner_id"), rs.getString("partner_name"), rs.getDate("business_date").toLocalDate(),
                    rs.getString("status"), rs.getBigDecimal("revenue"), rs.getBigDecimal("inventory_cost"),
                    rs.getBigDecimal("allocated_expense"), rs.getBigDecimal("outstanding_amount")),
                    Date.valueOf(start), Date.valueOf(end), id(sourceId), id(sourceId)));
        }
        if (type.isEmpty() || "OUTBOUND".equals(type)) {
            rows.addAll(jdbc.query("""
                    select 'OUTBOUND' source_type,o.id source_id,o.order_no source_no,
                           o.customer_id partner_id,p.name partner_name,o.business_date,o.status,
                           o.total_amount revenue,
                           coalesce((select sum(i.inventory_amount_cost) from outbound_item i
                             where i.outbound_id=o.id),0) inventory_cost,
                           coalesce((select sum(a.amount) from sales_cost_allocation a
                             where a.source_type='OUTBOUND' and a.source_id=o.id and a.status='ACTIVE'),0) allocated_expense,
                           greatest(o.receivable_amount-o.paid_amount,0) outstanding_amount
                    from outbound_order o join partner p on p.id=o.customer_id
                    where o.sales_order_id=0 and o.business_date between ? and ? and o.status='SHIPPED'
                      and (?=0 or o.id=?)
                    order by o.business_date desc,o.id desc
                    """, (rs, rowNum) -> contributionRow(
                    rs.getString("source_type"), rs.getLong("source_id"), rs.getString("source_no"),
                    rs.getLong("partner_id"), rs.getString("partner_name"), rs.getDate("business_date").toLocalDate(),
                    rs.getString("status"), rs.getBigDecimal("revenue"), rs.getBigDecimal("inventory_cost"),
                    rs.getBigDecimal("allocated_expense"), rs.getBigDecimal("outstanding_amount")),
                    Date.valueOf(start), Date.valueOf(end), id(sourceId), id(sourceId)));
        }
        if (type.isEmpty() || "DIRECT_TRADE".equals(type)) {
            rows.addAll(jdbc.query("""
                    select 'DIRECT_TRADE' source_type,d.id source_id,d.trade_no source_no,
                           d.customer_id partner_id,p.name partner_name,d.business_date,d.status,
                           d.sale_receivable_amount revenue,d.purchase_payable_amount inventory_cost,
                           coalesce((select sum(a.amount) from sales_cost_allocation a
                             where a.source_type='SALES_ORDER' and a.source_id=d.sales_order_id
                               and a.status='ACTIVE'),0) allocated_expense,
                           greatest(d.sale_receivable_amount-so.received_amount-so.adjusted_amount,0) outstanding_amount
                    from direct_trade_order d
                    join partner p on p.id=d.customer_id
                    join sales_order so on so.id=d.sales_order_id
                    where d.business_date between ? and ? and d.status not in ('DRAFT','CANCELLED')
                      and (?=0 or d.id=?)
                    order by d.business_date desc,d.id desc
                    """, (rs, rowNum) -> contributionRow(
                    rs.getString("source_type"), rs.getLong("source_id"), rs.getString("source_no"),
                    rs.getLong("partner_id"), rs.getString("partner_name"),
                    rs.getDate("business_date").toLocalDate(), rs.getString("status"),
                    rs.getBigDecimal("revenue"), rs.getBigDecimal("inventory_cost"),
                    rs.getBigDecimal("allocated_expense"), rs.getBigDecimal("outstanding_amount")),
                    Date.valueOf(start), Date.valueOf(end), id(sourceId), id(sourceId)));
        }
        return rows;
    }

    public LightweightOperationsDtos.OwnerSummary ownerSummary() {
        requireCompleteOperationalScope();
        LocalDate today = LocalDate.now();
        List<LightweightOperationsDtos.ContributionRow> month = contributions(today.withDayOfMonth(1), today, "", null);
        BigDecimal monthSales = month.stream().map(LightweightOperationsDtos.ContributionRow::revenue)
                .reduce(ZERO, BigDecimal::add);
        BigDecimal monthContribution = month.stream().map(LightweightOperationsDtos.ContributionRow::contributionMargin)
                .reduce(ZERO, BigDecimal::add);
        BigDecimal todaySales = month.stream().filter(row -> today.equals(row.businessDate()))
                .map(LightweightOperationsDtos.ContributionRow::revenue).reduce(ZERO, BigDecimal::add);
        List<LightweightOperationsDtos.ReplenishmentRow> replenishment = replenishment();
        return new LightweightOperationsDtos.OwnerSummary(
                today, money(todaySales), money(monthSales), money(monthContribution),
                scalarMoney("""
                        select coalesce(sum(case when a.normal_direction='DEBIT'
                          then l.debit_amount-l.credit_amount else l.credit_amount-l.debit_amount end),0)
                        from accounting_ledger_entry l join accounting_account a on a.id=l.account_id
                        where a.account_code in ('1001','1002')
                        """),
                scalarMoney("""
                        select coalesce(sum(greatest(receivable_amount-received_amount-adjusted_amount,0)),0)
                        from sales_order where status not in ('DRAFT','CANCELLED') and due_date<curdate()
                        """).add(scalarMoney("""
                        select coalesce(sum(greatest(receivable_amount-paid_amount,0)),0)
                        from outbound_order where sales_order_id=0 and status='SHIPPED' and due_date<curdate()
                        """)),
                scalarMoney("""
                        select coalesce(sum(greatest(
                          case when o.source_type='DIRECT_TRADE' then coalesce(d.purchase_payable_amount,0)
                               else o.total_amount end-o.paid_amount,0)),0)
                        from purchase_order o
                        left join direct_trade_order d on d.purchase_order_id=o.id
                        where o.status not in ('DRAFT','CANCELLED','COMPLETED')
                          and o.due_date between curdate() and date_add(curdate(),interval 7 day)
                        """),
                scalarMoney("""
                        select coalesce(sum(b.quantity_available*b.unit_cost),0)
                        from inventory_batch b where b.quantity_available>0
                          and b.received_at<=date_sub(curdate(),interval 180 day)
                        """),
                scalarLong("""
                        select
                          (select count(*) from outbound_order o left join outbound_delivery d on d.outbound_id=o.id
                           where o.status='SHIPPED' and (d.id is null or d.status not in ('SIGNED')))+
                          (select count(*) from direct_trade_order
                           where status in ('CONFIRMED','PARTIAL_DELIVERED'))
                        """),
                scalarLong("""
                        select count(*) from inventory_batch_quality_certificate
                        where status='ENABLED' and verification_status='PENDING'
                        """),
                replenishment.stream().filter(row -> !"NORMAL".equals(row.attentionLevel())).count());
    }

    public List<LightweightOperationsDtos.QuoteOption> quotes(Long purchaseOrderId) {
        dataScopeService.assertDocumentAllowed("PURCHASE_ORDER", purchaseOrderId);
        List<LightweightOperationsDtos.QuoteOption> headers = jdbc.query("""
                select * from purchase_quote_option where purchase_order_id=?
                order by case status when 'SELECTED' then 0 when 'ACTIVE' then 1 else 2 end,
                         total_amount,lead_time_days,id desc
                """, (rs, rowNum) -> new LightweightOperationsDtos.QuoteOption(
                rs.getLong("id"), rs.getLong("purchase_order_id"), rs.getLong("supplier_id"),
                rs.getString("supplier_name_snapshot"), rs.getDate("quote_date").toLocalDate(),
                rs.getBoolean("price_includes_tax"), rs.getBigDecimal("goods_amount"),
                rs.getBigDecimal("freight_amount"), rs.getBigDecimal("total_amount"),
                rs.getInt("lead_time_days"), rs.getString("settlement_terms"),
                rs.getString("source_note"), rs.getString("status"), rs.getString("selected_by_name"),
                dateTime(rs.getTimestamp("selected_at")), rs.getString("void_reason"), List.of()
        ), purchaseOrderId);
        return headers.stream().map(header -> new LightweightOperationsDtos.QuoteOption(
                header.id(), header.purchaseOrderId(), header.supplierId(), header.supplierName(),
                header.quoteDate(), header.priceIncludesTax(), header.goodsAmount(), header.freightAmount(),
                header.totalAmount(), header.leadTimeDays(), header.settlementTerms(), header.sourceNote(),
                header.status(), header.selectedByName(), header.selectedAt(), header.voidReason(),
                quoteItems(header.id()))).toList();
    }

    @Transactional
    public Long createQuote(Long purchaseOrderId, LightweightOperationsDtos.QuoteSave request) {
        dataScopeService.assertDocumentAllowed("PURCHASE_ORDER", purchaseOrderId);
        Map<String, Object> order = requiredRow("select id,status from purchase_order where id=? for update", purchaseOrderId);
        if (!"DRAFT".equals(text(order.get("status")))) throw new BusinessException("只有采购草稿可以登记报价比较");
        dataScopeService.assertMasterAllowed("PARTNER", request.supplierId());
        Map<String, Object> supplier = requiredRow("""
                select id,name,status,type_mask from partner where id=?
                """, request.supplierId());
        if (!"ENABLED".equals(text(supplier.get("status"))) || (integer(supplier.get("type_mask")) & 2) == 0)
            throw new BusinessException("请选择启用的供应商");
        if (request.leadTimeDays() == null || request.leadTimeDays() < 0 || request.leadTimeDays() > 3650)
            throw new BusinessException("交期天数必须在0到3650之间");
        BigDecimal goods = ZERO;
        for (LightweightOperationsDtos.QuoteItemSave item : request.items()) {
            dataScopeService.assertMasterAllowed("PRODUCT", item.productId());
            requireOrderProduct(purchaseOrderId, item.productId());
            goods = goods.add(quantity(item.requestedQuantity())
                    .multiply(unitPrice(item.quotedUnitPrice())).setScale(2, RoundingMode.HALF_UP));
        }
        BigDecimal freight = money(request.freightAmount());
        Actor actor = actor();
        long quoteId = insert("""
                insert into purchase_quote_option
                (purchase_order_id,supplier_id,supplier_name_snapshot,quote_date,price_includes_tax,
                 goods_amount,freight_amount,total_amount,lead_time_days,settlement_terms,source_note,
                 status,created_by,created_by_name)
                values(?,?,?,?,?,?,?,?,?,?,?,'ACTIVE',?,?)
                """, purchaseOrderId, request.supplierId(), text(supplier.get("name")),
                Date.valueOf(request.quoteDate()), Boolean.TRUE.equals(request.priceIncludesTax()),
                goods, freight, goods.add(freight), request.leadTimeDays(),
                text(request.settlementTerms()), text(request.sourceNote()), actor.id(), actor.name());
        for (LightweightOperationsDtos.QuoteItemSave item : request.items()) {
            Map<String, Object> product = requiredRow("select sku,name from product where id=?", item.productId());
            BigDecimal amount = quantity(item.requestedQuantity()).multiply(unitPrice(item.quotedUnitPrice()))
                    .setScale(2, RoundingMode.HALF_UP);
            jdbc.update("""
                    insert into purchase_quote_option_item
                    (quote_option_id,product_id,product_sku_snapshot,product_name_snapshot,
                     requested_quantity,requested_unit,quoted_unit_price,amount)
                    values(?,?,?,?,?,?,?,?)
                    """, quoteId, item.productId(), text(product.get("sku")), text(product.get("name")),
                    quantity(item.requestedQuantity()), required(item.requestedUnit(), "报价单位"),
                    unitPrice(item.quotedUnitPrice()), amount);
        }
        return quoteId;
    }

    @Transactional
    public Long selectQuote(Long purchaseOrderId, Long quoteId) {
        dataScopeService.assertDocumentAllowed("PURCHASE_ORDER", purchaseOrderId);
        requiredDraftPurchaseOrder(purchaseOrderId);
        Map<String, Object> row = requiredRow("""
                select id,status from purchase_quote_option where id=? and purchase_order_id=? for update
                """, quoteId, purchaseOrderId);
        if ("VOID".equals(text(row.get("status")))) throw new BusinessException("已作废报价不能选择");
        Actor actor = actor();
        jdbc.update("""
                update purchase_quote_option set status='ACTIVE',selected_by=0,selected_by_name='',selected_at=null
                where purchase_order_id=? and status='SELECTED'
                """, purchaseOrderId);
        jdbc.update("""
                update purchase_quote_option set status='SELECTED',selected_by=?,selected_by_name=?,selected_at=now()
                where id=? and purchase_order_id=?
                """, actor.id(), actor.name(), quoteId, purchaseOrderId);
        return quoteId;
    }

    @Transactional
    public Long voidQuote(Long purchaseOrderId, Long quoteId, String reason) {
        dataScopeService.assertDocumentAllowed("PURCHASE_ORDER", purchaseOrderId);
        requiredDraftPurchaseOrder(purchaseOrderId);
        requireReason(reason);
        int updated = jdbc.update("""
                update purchase_quote_option set status='VOID',void_reason=?
                where id=? and purchase_order_id=? and status in ('ACTIVE','SELECTED')
                """, reason.trim(), quoteId, purchaseOrderId);
        if (updated != 1) throw new BusinessException("报价不存在或已作废");
        return quoteId;
    }

    public List<LightweightOperationsDtos.ExpenseSource> expenseSources() {
        requireCompleteOperationalScope();
        return jdbc.query("""
                select d.id,d.document_no,d.document_type,d.partner_id,d.partner_name_snapshot,
                       d.business_date,d.summary,d.total_amount,d.status,
                       coalesce((select sum(a.amount) from sales_cost_allocation a
                         where a.treasury_document_id=d.id and a.status='ACTIVE'),0) allocated_amount
                from treasury_document d
                where d.document_type in ('CORPORATE_EXPENSE','OTHER_EXPENSE')
                  and d.status in ('CONFIRMED','PARTIALLY_SETTLED','SETTLED')
                order by d.business_date desc,d.id desc limit 500
                """, (rs, rowNum) -> {
            BigDecimal total = rs.getBigDecimal("total_amount");
            BigDecimal allocated = rs.getBigDecimal("allocated_amount");
            return new LightweightOperationsDtos.ExpenseSource(
                    rs.getLong("id"), rs.getString("document_no"), rs.getString("document_type"),
                    rs.getLong("partner_id"), rs.getString("partner_name_snapshot"),
                    rs.getDate("business_date").toLocalDate(), rs.getString("summary"),
                    total, allocated, total.subtract(allocated), rs.getString("status"));
        });
    }

    public List<LightweightOperationsDtos.SalesCostAllocation> salesCostAllocations(
            String sourceType, Long sourceId) {
        String type = salesSourceType(sourceType);
        assertSalesSource(type, sourceId);
        return jdbc.query("""
                select a.*,d.document_no,d.document_type,d.summary,d.total_amount,
                       coalesce((select sum(x.amount) from sales_cost_allocation x
                         where x.treasury_document_id=d.id and x.status='ACTIVE'),0) document_allocated_amount
                from sales_cost_allocation a join treasury_document d on d.id=a.treasury_document_id
                where a.source_type=? and a.source_id=? order by a.id desc
                """, (rs, rowNum) -> new LightweightOperationsDtos.SalesCostAllocation(
                rs.getLong("id"), rs.getLong("treasury_document_id"), rs.getString("document_no"),
                rs.getString("document_type"), rs.getString("summary"), rs.getBigDecimal("total_amount"),
                rs.getBigDecimal("document_allocated_amount"), rs.getString("source_type"),
                rs.getLong("source_id"), rs.getBigDecimal("amount"), rs.getString("reason"),
                rs.getString("status"), rs.getString("created_by_name"), rs.getString("reversal_reason"),
                rs.getString("reversed_by_name"), dateTime(rs.getTimestamp("reversed_at")),
                rs.getTimestamp("created_at").toLocalDateTime()), type, sourceId);
    }

    @Transactional
    public Long allocateSalesCost(String sourceType, Long sourceId,
                                  LightweightOperationsDtos.SalesCostSave request) {
        String type = salesSourceType(sourceType);
        assertSalesSource(type, sourceId);
        BigDecimal amount = positiveMoney(request.amount(), "分配金额");
        requireReason(request.reason());
        Map<String, Object> document = requiredRow("""
                select id,document_type,total_amount,status from treasury_document where id=? for update
                """, request.treasuryDocumentId());
        if (!Set.of("CORPORATE_EXPENSE", "OTHER_EXPENSE").contains(text(document.get("document_type"))))
            throw new BusinessException("只有已存在的企业费用或其他费用可以归集到销售");
        if (!Set.of("CONFIRMED", "PARTIALLY_SETTLED", "SETTLED").contains(text(document.get("status"))))
            throw new BusinessException("费用源单尚未确认或已经失效");
        long duplicate = scalarLong("""
                select count(*) from sales_cost_allocation
                where treasury_document_id=? and source_type=? and source_id=? and status='ACTIVE'
                """, request.treasuryDocumentId(), type, sourceId);
        if (duplicate > 0) throw new BusinessException("该费用已分配到当前销售来源；请先撤销原分配");
        BigDecimal allocated = scalarMoney("""
                select coalesce(sum(amount),0) from sales_cost_allocation
                where treasury_document_id=? and status='ACTIVE'
                """, request.treasuryDocumentId());
        BigDecimal total = money(document.get("total_amount"));
        if (allocated.add(amount).compareTo(total) > 0) throw new BusinessException("分配金额超过费用源单可分配余额");
        Actor actor = actor();
        return insert("""
                insert into sales_cost_allocation
                (treasury_document_id,source_type,source_id,amount,reason,status,created_by,created_by_name)
                values(?,?,?,?,?,'ACTIVE',?,?)
                """, request.treasuryDocumentId(), type, sourceId, amount, request.reason().trim(),
                actor.id(), actor.name());
    }

    @Transactional
    public Long reverseSalesCost(String sourceType, Long sourceId, Long allocationId, String reason) {
        String type = salesSourceType(sourceType);
        assertSalesSource(type, sourceId);
        requireReason(reason);
        Actor actor = actor();
        int updated = jdbc.update("""
                update sales_cost_allocation set status='REVERSED',reversal_reason=?,
                    reversed_by=?,reversed_by_name=?,reversed_at=now()
                where id=? and source_type=? and source_id=? and status='ACTIVE'
                """, reason.trim(), actor.id(), actor.name(), allocationId, type, sourceId);
        if (updated != 1) throw new BusinessException("费用分配不存在或已撤销");
        return allocationId;
    }

    public LightweightOperationsDtos.Delivery delivery(Long outboundId) {
        dataScopeService.assertDocumentAllowed("OUTBOUND", outboundId);
        List<LightweightOperationsDtos.Delivery> rows = jdbc.query("""
                select d.*,o.order_no outbound_no from outbound_delivery d
                join outbound_order o on o.id=d.outbound_id where d.outbound_id=?
                """, (rs, rowNum) -> deliveryRow(rs, List.of()), outboundId);
        return rows.isEmpty() ? null : withDeliveryEvents(rows.get(0));
    }

    @Transactional
    public LightweightOperationsDtos.Delivery saveDelivery(
            Long outboundId, LightweightOperationsDtos.DeliverySave request) {
        dataScopeService.assertDocumentAllowed("OUTBOUND", outboundId);
        Map<String, Object> outbound = requiredRow("""
                select id,order_no,status from outbound_order where id=? for update
                """, outboundId);
        if (!"SHIPPED".equals(text(outbound.get("status"))))
            throw new BusinessException("只有已出库单可以维护交付信息");
        String mode = enumValue(request.deliveryMode(), Set.of("SELF_PICKUP", "OWN_DELIVERY", "LOGISTICS"), "交付方式");
        if ("LOGISTICS".equals(mode) && !StringUtils.hasText(request.carrierName()))
            throw new BusinessException("物流交付请填写承运方");
        Actor actor = actor();
        long existing = scalarLong("select coalesce(max(id),0) from outbound_delivery where outbound_id=?", outboundId);
        if (existing == 0) {
            existing = insert("""
                    insert into outbound_delivery
                    (outbound_id,delivery_mode,carrier_name,tracking_no,handler_name,status,memo,
                     created_by,created_by_name,updated_by,updated_by_name)
                    values(?,?,?,?,?,'PENDING',?,?,?,?,?)
                    """, outboundId, mode, text(request.carrierName()), text(request.trackingNo()),
                    text(request.handlerName()), text(request.memo()), actor.id(), actor.name(),
                    actor.id(), actor.name());
            deliveryEvent(existing, "CREATE", "", "PENDING", "建立交付记录", actor);
        } else {
            Map<String, Object> current = requiredRow("select status from outbound_delivery where id=? for update", existing);
            if ("SIGNED".equals(text(current.get("status"))))
                throw new BusinessException("已签收交付不能原地修改；如有差错请先重开");
            jdbc.update("""
                    update outbound_delivery set delivery_mode=?,carrier_name=?,tracking_no=?,handler_name=?,
                      memo=?,updated_by=?,updated_by_name=?,version_no=version_no+1 where id=?
                    """, mode, text(request.carrierName()), text(request.trackingNo()),
                    text(request.handlerName()), text(request.memo()), actor.id(), actor.name(), existing);
            deliveryEvent(existing, "UPDATE", text(current.get("status")), text(current.get("status")),
                    "更新交付信息", actor);
        }
        return delivery(outboundId);
    }

    @Transactional
    public LightweightOperationsDtos.Delivery actDelivery(
            Long outboundId, LightweightOperationsDtos.DeliveryAction request) {
        dataScopeService.assertDocumentAllowed("OUTBOUND", outboundId);
        Map<String, Object> row = requiredRow("""
                select d.* from outbound_delivery d where d.outbound_id=? for update
                """, outboundId);
        String action = enumValue(request.action(), Set.of("DISPATCH", "SIGN", "EXCEPTION", "REOPEN"), "交付动作");
        String from = text(row.get("status"));
        String to;
        switch (action) {
            case "DISPATCH" -> {
                if (!Set.of("PENDING", "EXCEPTION").contains(from)) throw new BusinessException("当前状态不能确认发出");
                to = "DISPATCHED";
            }
            case "SIGN" -> {
                if (!"DISPATCHED".equals(from)) throw new BusinessException("只有已发出交付可以签收");
                if (!StringUtils.hasText(request.recipientName())) throw new BusinessException("签收请填写收货人");
                to = "SIGNED";
            }
            case "EXCEPTION" -> {
                if (!Set.of("PENDING", "DISPATCHED").contains(from)) throw new BusinessException("当前状态不能登记交付异常");
                requireReason(request.reason());
                to = "EXCEPTION";
            }
            default -> {
                if (!Set.of("SIGNED", "EXCEPTION").contains(from)) throw new BusinessException("只有已签收或异常交付可以重开");
                requireReason(request.reason());
                to = "PENDING";
            }
        }
        Actor actor = actor();
        jdbc.update("""
                update outbound_delivery set status=?,
                  dispatched_at=case when ?='DISPATCH' then now() when ?='REOPEN' then null else dispatched_at end,
                  signed_at=case when ?='SIGN' then now() when ?='REOPEN' then null else signed_at end,
                  recipient_name=case when ?='SIGN' then ? when ?='REOPEN' then '' else recipient_name end,
                  exception_note=case when ?='EXCEPTION' then ? when ? in ('DISPATCH','REOPEN') then '' else exception_note end,
                  updated_by=?,updated_by_name=?,version_no=version_no+1 where id=?
                """, to, action, action, action, action, action, text(request.recipientName()), action,
                action, text(request.reason()), action, actor.id(), actor.name(), number(row, "id"));
        deliveryEvent(number(row, "id"), action, from, to, text(request.reason()), actor);
        return delivery(outboundId);
    }

    public List<LightweightOperationsDtos.ShareRow> shares(String sourceType, Long sourceId) {
        String type = shareSourceType(sourceType);
        assertShareSource(type, sourceId);
        return jdbc.query("""
                select s.*,p.name partner_name,
                  case when s.source_type='SALES_ORDER'
                    then (select order_no from sales_order where id=s.source_id)
                    else (select order_no from purchase_order where id=s.source_id) end source_no
                from external_share_link s join partner p on p.id=s.partner_id
                where s.source_type=? and s.source_id=? order by s.id desc
                """, (rs, rowNum) -> shareRow(rs), type, sourceId);
    }

    @Transactional
    public LightweightOperationsDtos.ShareCreated createShare(
            LightweightOperationsDtos.ShareCreate request) {
        String type = shareSourceType(request.sourceType());
        SourceHead head = assertShareSource(type, request.sourceId());
        LocalDateTime expiry = expiry(request.expiresAt(), 30, "分享链接");
        String token = newToken();
        Actor actor = actor();
        long id = insert("""
                insert into external_share_link
                (share_no,token_hash,source_type,source_id,partner_id,expires_at,status,created_by,created_by_name)
                values(?,?,?,?,?,?,'ACTIVE',?,?)
                """, nextNo("SH"), sha256(token), type, request.sourceId(), head.partnerId(),
                Timestamp.valueOf(expiry), actor.id(), actor.name());
        LightweightOperationsDtos.ShareRow row = jdbc.query("""
                select s.*,p.name partner_name,? source_no
                from external_share_link s join partner p on p.id=s.partner_id where s.id=?
                """, rs -> rs.next() ? shareRow(rs) : null, head.sourceNo(), id);
        return new LightweightOperationsDtos.ShareCreated(Objects.requireNonNull(row), token);
    }

    @Transactional
    public Long revokeShare(Long id, String reason) {
        requireReason(reason);
        Map<String, Object> share = requiredRow("select * from external_share_link where id=? for update", id);
        assertShareSource(text(share.get("source_type")), number(share, "source_id"));
        Actor actor = actor();
        int updated = jdbc.update("""
                update external_share_link set status='REVOKED',revoke_reason=?,
                  revoked_by=?,revoked_by_name=?,revoked_at=now()
                where id=? and status='ACTIVE'
                """, reason.trim(), actor.id(), actor.name(), id);
        if (updated != 1) throw new BusinessException("分享链接已撤销或失效");
        return id;
    }

    @Transactional
    public LightweightOperationsDtos.PublicOrder publicSharedOrder(String token) {
        String hash = sha256(required(token, "分享令牌"));
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select * from external_share_link where token_hash=? for update
                """, hash);
        if (rows.isEmpty()) throw new BusinessException("分享链接不存在");
        Map<String, Object> row = rows.get(0);
        if (!"ACTIVE".equals(text(row.get("status")))) throw new BusinessException("分享链接已失效");
        LocalDateTime expiresAt = dateTime(row.get("expires_at"));
        if (expiresAt == null || !expiresAt.isAfter(LocalDateTime.now())) {
            jdbc.update("update external_share_link set status='EXPIRED' where id=?", number(row, "id"));
            throw new BusinessException("分享链接已到期");
        }
        jdbc.update("""
                update external_share_link set view_count=view_count+1,last_viewed_at=now() where id=?
                """, number(row, "id"));
        return publicOrder(text(row.get("source_type")), number(row, "source_id"), number(row, "partner_id"));
    }

    public List<LightweightOperationsDtos.ApiKeyRow> apiKeys() {
        requireCompleteOperationalScope();
        return jdbc.query("""
                select k.*,p.name partner_name from integration_access_key k
                left join partner p on p.id=k.partner_id order by k.id desc
                """, (rs, rowNum) -> apiKeyRow(rs));
    }

    @Transactional
    public LightweightOperationsDtos.ApiKeyCreated createApiKey(
            LightweightOperationsDtos.ApiKeyCreate request) {
        requireCompleteOperationalScope();
        dataScopeService.assertMasterAllowed("PARTNER", request.partnerId());
        Map<String, Object> partner = requiredRow("select name,status from partner where id=?", request.partnerId());
        if (!"ENABLED".equals(text(partner.get("status")))) throw new BusinessException("往来单位已停用");
        LocalDateTime expiry = expiry(request.expiresAt(), 365, "开放接口密钥");
        String token = newToken();
        Actor actor = actor();
        long id = insert("""
                insert into integration_access_key
                (key_no,key_name,token_hash,partner_id,scopes,expires_at,status,created_by,created_by_name)
                values(?,?,?,?, 'order.read',?,'ACTIVE',?,?)
                """, nextNo("AK"), required(request.keyName(), "密钥名称"), sha256(token),
                request.partnerId(), Timestamp.valueOf(expiry), actor.id(), actor.name());
        LightweightOperationsDtos.ApiKeyRow row = jdbc.query("""
                select k.*,p.name partner_name from integration_access_key k
                join partner p on p.id=k.partner_id where k.id=?
                """, rs -> rs.next() ? apiKeyRow(rs) : null, id);
        return new LightweightOperationsDtos.ApiKeyCreated(Objects.requireNonNull(row), token);
    }

    @Transactional
    public Long revokeApiKey(Long id, String reason) {
        requireCompleteOperationalScope();
        requireReason(reason);
        Actor actor = actor();
        int updated = jdbc.update("""
                update integration_access_key set status='REVOKED',revoke_reason=?,
                  revoked_by=?,revoked_by_name=?,revoked_at=now()
                where id=? and status='ACTIVE'
                """, reason.trim(), actor.id(), actor.name(), id);
        if (updated != 1) throw new BusinessException("开放接口密钥已撤销或失效");
        return id;
    }

    @Transactional
    public LightweightOperationsDtos.PublicOrder openApiOrder(
            String token, String sourceType, String sourceNo) {
        String hash = sha256(required(token, "接口密钥"));
        List<Map<String, Object>> keys = jdbc.queryForList("""
                select * from integration_access_key where token_hash=? for update
                """, hash);
        if (keys.isEmpty()) throw new ForbiddenException("接口密钥无效");
        Map<String, Object> key = keys.get(0);
        if (!"ACTIVE".equals(text(key.get("status"))) || !text(key.get("scopes")).contains("order.read"))
            throw new ForbiddenException("接口密钥已失效或无订单读取范围");
        LocalDateTime expiresAt = dateTime(key.get("expires_at"));
        if (expiresAt == null || !expiresAt.isAfter(LocalDateTime.now())) {
            jdbc.update("update integration_access_key set status='EXPIRED' where id=?", number(key, "id"));
            throw new ForbiddenException("接口密钥已到期");
        }
        String type = shareSourceType(sourceType);
        String table = "SALES_ORDER".equals(type) ? "sales_order" : "purchase_order";
        String partnerColumn = "SALES_ORDER".equals(type) ? "customer_id" : "supplier_id";
        List<Map<String, Object>> sources = jdbc.queryForList(
                "select id," + partnerColumn + " partner_id from " + table + " where order_no=?",
                required(sourceNo, "订单号"));
        if (sources.isEmpty()) throw new BusinessException("订单不存在");
        long partnerId = number(sources.get(0), "partner_id");
        if (partnerId != number(key, "partner_id")) throw new ForbiddenException("接口密钥无权查看该订单");
        jdbc.update("""
                update integration_access_key set use_count=use_count+1,last_used_at=now() where id=?
                """, number(key, "id"));
        return publicOrder(type, number(sources.get(0), "id"), partnerId);
    }

    private List<LightweightOperationsDtos.QuoteItem> quoteItems(Long quoteId) {
        return jdbc.query("""
                select * from purchase_quote_option_item where quote_option_id=? order by id
                """, (rs, rowNum) -> new LightweightOperationsDtos.QuoteItem(
                rs.getLong("id"), rs.getLong("product_id"), rs.getString("product_sku_snapshot"),
                rs.getString("product_name_snapshot"), rs.getBigDecimal("requested_quantity"),
                rs.getString("requested_unit"), rs.getBigDecimal("quoted_unit_price"),
                rs.getBigDecimal("amount")), quoteId);
    }

    private LightweightOperationsDtos.ContributionRow contributionRow(
            String sourceType, Long sourceId, String sourceNo, Long partnerId, String partnerName,
            LocalDate businessDate, String status, BigDecimal revenue, BigDecimal inventoryCost,
            BigDecimal allocatedExpense, BigDecimal outstandingAmount) {
        BigDecimal safeRevenue = money(revenue);
        BigDecimal safeCost = money(inventoryCost);
        BigDecimal safeExpense = money(allocatedExpense);
        BigDecimal gross = safeRevenue.subtract(safeCost);
        return new LightweightOperationsDtos.ContributionRow(sourceType, sourceId, sourceNo,
                partnerId, partnerName, businessDate, status, safeRevenue, safeCost, safeExpense,
                gross, gross.subtract(safeExpense), money(outstandingAmount));
    }

    private LightweightOperationsDtos.Delivery deliveryRow(java.sql.ResultSet rs,
                                                            List<LightweightOperationsDtos.DeliveryEvent> events)
            throws java.sql.SQLException {
        return new LightweightOperationsDtos.Delivery(
                rs.getLong("id"), rs.getLong("outbound_id"), rs.getString("outbound_no"),
                rs.getString("delivery_mode"), rs.getString("carrier_name"), rs.getString("tracking_no"),
                rs.getString("handler_name"), rs.getString("status"),
                dateTime(rs.getTimestamp("dispatched_at")), dateTime(rs.getTimestamp("signed_at")),
                rs.getString("recipient_name"), rs.getString("exception_note"), rs.getString("memo"),
                rs.getInt("version_no"), rs.getString("created_by_name"), rs.getString("updated_by_name"),
                rs.getTimestamp("created_at").toLocalDateTime(), rs.getTimestamp("updated_at").toLocalDateTime(),
                events);
    }

    private LightweightOperationsDtos.Delivery withDeliveryEvents(LightweightOperationsDtos.Delivery delivery) {
        List<LightweightOperationsDtos.DeliveryEvent> events = jdbc.query("""
                select * from outbound_delivery_event where delivery_id=? order by id
                """, (rs, rowNum) -> new LightweightOperationsDtos.DeliveryEvent(
                rs.getLong("id"), rs.getString("action"), rs.getString("from_status"),
                rs.getString("to_status"), rs.getString("reason"), rs.getString("operator_name"),
                rs.getTimestamp("created_at").toLocalDateTime()), delivery.id());
        return new LightweightOperationsDtos.Delivery(
                delivery.id(), delivery.outboundId(), delivery.outboundNo(), delivery.deliveryMode(),
                delivery.carrierName(), delivery.trackingNo(), delivery.handlerName(), delivery.status(),
                delivery.dispatchedAt(), delivery.signedAt(), delivery.recipientName(),
                delivery.exceptionNote(), delivery.memo(), delivery.versionNo(), delivery.createdByName(),
                delivery.updatedByName(), delivery.createdAt(), delivery.updatedAt(), events);
    }

    private void deliveryEvent(Long id, String action, String from, String to, String reason, Actor actor) {
        jdbc.update("""
                insert into outbound_delivery_event
                (delivery_id,action,from_status,to_status,reason,operator_id,operator_name)
                values(?,?,?,?,?,?,?)
                """, id, action, text(from), text(to), text(reason), actor.id(), actor.name());
    }

    private LightweightOperationsDtos.ShareRow shareRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new LightweightOperationsDtos.ShareRow(
                rs.getLong("id"), rs.getString("share_no"), rs.getString("source_type"),
                rs.getLong("source_id"), rs.getString("source_no"), rs.getLong("partner_id"),
                rs.getString("partner_name"), dateTime(rs.getTimestamp("expires_at")),
                expiredStatus(rs.getString("status"), rs.getTimestamp("expires_at")),
                rs.getString("created_by_name"), rs.getLong("view_count"),
                dateTime(rs.getTimestamp("last_viewed_at")), rs.getString("revoke_reason"),
                rs.getTimestamp("created_at").toLocalDateTime());
    }

    private LightweightOperationsDtos.ApiKeyRow apiKeyRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new LightweightOperationsDtos.ApiKeyRow(
                rs.getLong("id"), rs.getString("key_no"), rs.getString("key_name"),
                rs.getLong("partner_id"), rs.getString("partner_name"), rs.getString("scopes"),
                dateTime(rs.getTimestamp("expires_at")),
                expiredStatus(rs.getString("status"), rs.getTimestamp("expires_at")),
                rs.getString("created_by_name"), rs.getLong("use_count"),
                dateTime(rs.getTimestamp("last_used_at")), rs.getString("revoke_reason"),
                rs.getTimestamp("created_at").toLocalDateTime());
    }

    private LightweightOperationsDtos.PublicOrder publicOrder(String sourceType, Long sourceId, Long expectedPartnerId) {
        String type = shareSourceType(sourceType);
        if ("SALES_ORDER".equals(type)) {
            Map<String, Object> head = requiredRow("""
                    select o.id,o.order_no,o.customer_id partner_id,p.name partner_name,o.business_date,
                           o.status,o.price_includes_tax,o.total_amount,o.memo
                    from sales_order o join partner p on p.id=o.customer_id where o.id=?
                    """, sourceId);
            assertExpectedPartner(head, expectedPartnerId);
            List<LightweightOperationsDtos.PublicOrderLine> items = jdbc.query("""
                    select product_sku_snapshot,product_name_snapshot,product_spec_snapshot,
                           requested_quantity quantity,requested_unit unit,unit_price,amount
                    from sales_order_item where sales_order_id=? and status='ENABLED' order by id
                    """, (rs, rowNum) -> publicLine(rs), sourceId);
            return publicHead(type, head, items);
        }
        Map<String, Object> head = requiredRow("""
                select o.id,o.order_no,o.supplier_id partner_id,p.name partner_name,o.business_date,
                       o.status,o.price_includes_tax,o.total_amount,o.memo
                from purchase_order o join partner p on p.id=o.supplier_id where o.id=?
                """, sourceId);
        assertExpectedPartner(head, expectedPartnerId);
        List<LightweightOperationsDtos.PublicOrderLine> items = jdbc.query("""
                select product_sku_snapshot,product_name_snapshot,product_spec_snapshot,
                       requested_quantity quantity,requested_unit unit,unit_price,amount
                from purchase_order_item where purchase_order_id=? and status='ENABLED' order by id
                """, (rs, rowNum) -> publicLine(rs), sourceId);
        return publicHead(type, head, items);
    }

    private LightweightOperationsDtos.PublicOrderLine publicLine(java.sql.ResultSet rs)
            throws java.sql.SQLException {
        return new LightweightOperationsDtos.PublicOrderLine(
                rs.getString("product_sku_snapshot"), rs.getString("product_name_snapshot"),
                rs.getString("product_spec_snapshot"), rs.getBigDecimal("quantity"),
                rs.getString("unit"), rs.getBigDecimal("unit_price"), rs.getBigDecimal("amount"));
    }

    private LightweightOperationsDtos.PublicOrder publicHead(
            String type, Map<String, Object> head, List<LightweightOperationsDtos.PublicOrderLine> items) {
        return new LightweightOperationsDtos.PublicOrder(
                type, text(head.get("order_no")), text(head.get("partner_name")),
                localDate(head.get("business_date")), text(head.get("status")),
                booleanValue(head.get("price_includes_tax")), money(head.get("total_amount")),
                "", items);
    }

    private SourceHead assertShareSource(String type, Long sourceId) {
        if ("SALES_ORDER".equals(type)) {
            dataScopeService.assertDocumentAllowed("SALES_ORDER", sourceId);
            Map<String, Object> row = requiredRow("select order_no,customer_id partner_id from sales_order where id=?", sourceId);
            return new SourceHead(number(row, "partner_id"), text(row.get("order_no")));
        }
        dataScopeService.assertDocumentAllowed("PURCHASE_ORDER", sourceId);
        Map<String, Object> row = requiredRow("select order_no,supplier_id partner_id from purchase_order where id=?", sourceId);
        return new SourceHead(number(row, "partner_id"), text(row.get("order_no")));
    }

    private void assertSalesSource(String type, Long sourceId) {
        if (sourceId == null || sourceId <= 0) throw new BusinessException("销售来源不能为空");
        if ("SALES_ORDER".equals(type)) {
            dataScopeService.assertDocumentAllowed("SALES_ORDER", sourceId);
            requiredRow("select id from sales_order where id=?", sourceId);
        } else {
            dataScopeService.assertDocumentAllowed("OUTBOUND", sourceId);
            requiredRow("select id from outbound_order where id=?", sourceId);
        }
    }

    private void requireOrderProduct(Long purchaseOrderId, Long productId) {
        if (scalarLong("""
                select count(*) from purchase_order_item
                where purchase_order_id=? and product_id=? and status='ENABLED'
                """, purchaseOrderId, productId) == 0)
            throw new BusinessException("报价商品不在当前采购草稿中");
    }

    private void requiredDraftPurchaseOrder(Long purchaseOrderId) {
        Map<String, Object> row = requiredRow("select status from purchase_order where id=? for update", purchaseOrderId);
        if (!"DRAFT".equals(text(row.get("status")))) throw new BusinessException("只有采购草稿可以调整报价选择");
    }

    private void assertExpectedPartner(Map<String, Object> head, Long expectedPartnerId) {
        if (expectedPartnerId != null && expectedPartnerId > 0
                && number(head, "partner_id") != expectedPartnerId)
            throw new ForbiddenException("无权查看该往来单位订单");
    }

    private void requireCompleteOperationalScope() {
        if (!dataScopeService.hasFullDocumentScope())
            throw new ForbiddenException("经营汇总要求业务单据数据范围为全部，避免显示不完整的经营结论");
        dataScopeService.assertFullMasterScope("PARTNER");
        dataScopeService.assertFullMasterScope("PRODUCT");
        dataScopeService.assertFullMasterScope("WAREHOUSE");
    }

    private String shareSourceType(String value) {
        return enumValue(value, Set.of("SALES_ORDER", "PURCHASE_ORDER"), "订单类型");
    }

    private String salesSourceType(String value) {
        return enumValue(value, SOURCE_TYPES, "销售来源类型");
    }

    private String enumValue(String value, Set<String> allowed, String label) {
        String result = required(value, label).toUpperCase(Locale.ROOT);
        if (!allowed.contains(result)) throw new BusinessException(label + "无效");
        return result;
    }

    private LocalDateTime expiry(LocalDateTime value, int maxDays, String label) {
        if (value == null || !value.isAfter(LocalDateTime.now().plusMinutes(5)))
            throw new BusinessException(label + "到期时间至少晚于当前时间5分钟");
        if (value.isAfter(LocalDateTime.now().plusDays(maxDays)))
            throw new BusinessException(label + "有效期不能超过" + maxDays + "天");
        return value;
    }

    private String newToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte b : digest) result.append(String.format("%02x", b));
            return result.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256不可用", ex);
        }
    }

    private String expiredStatus(String status, Timestamp expiresAt) {
        if ("ACTIVE".equals(status) && expiresAt != null
                && !expiresAt.toLocalDateTime().isAfter(LocalDateTime.now())) return "EXPIRED";
        return status;
    }

    private Actor actor() {
        SecurityContext.Principal principal = SecurityContext.require();
        String name = StringUtils.hasText(principal.employeeName()) ? principal.employeeName() : principal.username();
        return new Actor(principal.userId() == null ? 0L : principal.userId(), name);
    }

    private long insert(String sql, Object... args) {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
            return statement;
        }, holder);
        if (holder.getKey() == null) throw new BusinessException("保存经营数据失败");
        return holder.getKey().longValue();
    }

    private Map<String, Object> requiredRow(String sql, Object... args) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, args);
        if (rows.isEmpty()) throw new BusinessException("业务记录不存在");
        return rows.get(0);
    }

    private long scalarLong(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0L : value;
    }

    private BigDecimal scalarMoney(String sql, Object... args) {
        return money(jdbc.queryForObject(sql, BigDecimal.class, args));
    }

    private String nextNo(String prefix) {
        return prefix + NO_TIME.format(LocalDateTime.now())
                + String.format("%03d", Math.floorMod(NO_COUNTER.incrementAndGet(), 1000));
    }

    private String required(String value, String label) {
        if (!StringUtils.hasText(value)) throw new BusinessException("请填写" + label);
        return value.trim();
    }

    private void requireReason(String value) {
        if (!StringUtils.hasText(value) || value.trim().length() < 2)
            throw new BusinessException("请填写至少2个字的原因");
        if (value.trim().length() > 500) throw new BusinessException("原因不能超过500字");
    }

    private BigDecimal positiveMoney(BigDecimal value, String label) {
        BigDecimal result = money(value);
        if (result.signum() <= 0) throw new BusinessException(label + "必须大于0");
        return result;
    }

    private BigDecimal money(Object value) {
        BigDecimal result = decimal(value);
        return result.setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal quantity(Object value) {
        return decimal(value).setScale(6, RoundingMode.HALF_UP);
    }

    private BigDecimal unitPrice(Object value) {
        BigDecimal result = decimal(value).setScale(8, RoundingMode.HALF_UP);
        if (result.signum() < 0) throw new BusinessException("报价单价不能小于0");
        return result;
    }

    private BigDecimal decimal(Object value) {
        if (value instanceof BigDecimal decimal) return decimal;
        if (value instanceof Number number) return BigDecimal.valueOf(number.doubleValue());
        if (value == null) return BigDecimal.ZERO;
        return new BigDecimal(String.valueOf(value));
    }

    private long number(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private long id(Long value) { return value == null ? 0L : value; }
    private int integer(Object value) { return value instanceof Number number ? number.intValue() : 0; }
    private boolean booleanValue(Object value) {
        return value instanceof Boolean bool ? bool
                : value instanceof Number number && number.intValue() != 0;
    }
    private String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    private LocalDate localDate(Object value) {
        if (value instanceof Date date) return date.toLocalDate();
        if (value instanceof LocalDate localDate) return localDate;
        return null;
    }
    private LocalDateTime dateTime(Timestamp value) { return value == null ? null : value.toLocalDateTime(); }
    private LocalDateTime dateTime(Object value) {
        if (value == null) return null;
        if (value instanceof LocalDateTime dateTime) return dateTime;
        if (value instanceof Timestamp timestamp) return timestamp.toLocalDateTime();
        throw new IllegalArgumentException("不支持的日期时间类型：" + value.getClass().getName());
    }

    private record Actor(long id, String name) {}
    private record SourceHead(long partnerId, String sourceNo) {}
}
