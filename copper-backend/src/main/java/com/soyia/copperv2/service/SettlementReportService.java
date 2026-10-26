package com.soyia.copperv2.service;

import com.soyia.copperv2.dto.SettlementReportDtos;
import com.soyia.copperv2.security.BusinessDataScopeService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class SettlementReportService {
    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 200;
    private static final List<String> AGING_ORDER = List.of(
            "NOT_DUE", "DUE_TODAY", "OVERDUE_1_30", "OVERDUE_31_60", "OVERDUE_61_90", "OVERDUE_91_PLUS");
    private static final Map<String, String> AGING_LABELS = Map.of(
            "NOT_DUE", "未到期",
            "DUE_TODAY", "今日到期",
            "OVERDUE_1_30", "逾期 1-30 天",
            "OVERDUE_31_60", "逾期 31-60 天",
            "OVERDUE_61_90", "逾期 61-90 天",
            "OVERDUE_91_PLUS", "逾期 91 天以上");

    private final JdbcTemplate jdbc;
    private final BusinessDataScopeService dataScopeService;

    public SettlementReportService(JdbcTemplate jdbc, BusinessDataScopeService dataScopeService) {
        this.jdbc = jdbc;
        this.dataScopeService = dataScopeService;
    }

    /**
     * List rows, summary and aging are read under one repeatable-read transaction and one database
     * clock value. The cutoff is deliberately the current trusted database snapshot; this endpoint
     * does not claim arbitrary historical reconstruction from mutable order balance columns.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public SettlementReportDtos.Page query(String direction, Long partnerId, String keyword, String sourceType,
                                           String scope, LocalDate dueDateFrom, LocalDate dueDateTo,
                                           Integer requestedPageNo, Integer requestedPageSize) {
        String normalizedDirection = normalizeDirection(direction);
        int pageNo = requestedPageNo == null ? 1 : requestedPageNo;
        int pageSize = requestedPageSize == null ? DEFAULT_PAGE_SIZE : requestedPageSize;
        if (pageNo < 1) throw new BusinessException("页码必须大于等于 1");
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) throw new BusinessException("每页条数必须在 1 到 " + MAX_PAGE_SIZE + " 之间");
        if (dueDateFrom != null && dueDateTo != null && dueDateFrom.isAfter(dueDateTo)) {
            throw new BusinessException("到期开始日期不能晚于到期结束日期");
        }

        Snapshot snapshot = databaseSnapshot();
        BusinessDataScopeService.SettlementReportScope dataScope = dataScopeService.settlementReportScope(partnerId);
        QuerySpec spec = querySpec(normalizedDirection, snapshot.asOfDate(), partnerId, dataScope.completeScope(), dataScope.partnerIds(),
                keyword, sourceType, scope, dueDateFrom, dueDateTo);

        SettlementReportDtos.Summary summary = summary(spec);
        long totalRows = summary.documentCount();
        int totalPages = totalRows == 0 ? 0 : (int) ((totalRows + pageSize - 1) / pageSize);
        List<SettlementReportDtos.Row> items = page(spec, pageNo, pageSize);
        List<SettlementReportDtos.AgingBucket> aging = aging(spec);
        return new SettlementReportDtos.Page(normalizedDirection, snapshot.asOfDate(), snapshot.snapshotAt(),
                dataScope.completeScope(), pageNo, pageSize, totalRows, totalPages, summary, aging, items);
    }

    private QuerySpec querySpec(String direction, LocalDate asOfDate, Long requestedPartnerId,
                                boolean completeScope, Set<Long> scopedPartnerIds,
                                String keyword, String sourceType, String scope,
                                LocalDate dueDateFrom, LocalDate dueDateTo) {
        String baseSql = "RECEIVABLE".equals(direction) ? receivableBaseSql() : payableBaseSql();
        List<Object> args = new ArrayList<>(List.of(asOfDate, asOfDate, asOfDate, asOfDate));
        StringBuilder where = new StringBuilder(" where 1=1");

        if (requestedPartnerId != null && requestedPartnerId > 0) {
            where.append(" and x.partner_id=?");
            args.add(requestedPartnerId);
        } else if (!scopedPartnerIds.isEmpty()) {
            appendIn(where, args, "x.partner_id", scopedPartnerIds);
        } else if (!completeScope) {
            where.append(" and 1=0");
        }

        String cleanKeyword = clean(keyword, 100);
        if (!cleanKeyword.isBlank()) {
            where.append(" and (x.source_no like ? or x.partner_name like ?)");
            args.add("%" + cleanKeyword + "%");
            args.add("%" + cleanKeyword + "%");
        }

        String cleanSourceType = clean(sourceType, 30).toUpperCase(Locale.ROOT);
        Set<String> allowedSources = "RECEIVABLE".equals(direction)
                ? Set.of("SALES_ORDER", "OUTBOUND", "OPENING_RECEIVABLE")
                : Set.of("PURCHASE_ORDER", "INBOUND", "OPENING_PAYABLE");
        if (!cleanSourceType.isBlank()) {
            if (!allowedSources.contains(cleanSourceType)) throw new BusinessException("不支持的应收应付来源类型");
            where.append(" and x.source_type=?");
            args.add(cleanSourceType);
        }

        String normalizedScope = clean(scope, 30).toLowerCase(Locale.ROOT);
        if (normalizedScope.isBlank()) normalizedScope = "open";
        switch (normalizedScope) {
            case "all" -> { }
            case "open" -> where.append(" and x.unpaid_amount>0");
            case "due-today" -> where.append(" and x.unpaid_amount>0 and x.due_date=x.as_of_date");
            case "overdue" -> where.append(" and x.unpaid_amount>0 and x.due_date<x.as_of_date");
            case "settled" -> where.append(" and x.unpaid_amount=0");
            default -> throw new BusinessException("不支持的到期范围");
        }
        if (dueDateFrom != null) {
            where.append(" and x.due_date>=?");
            args.add(dueDateFrom);
        }
        if (dueDateTo != null) {
            where.append(" and x.due_date<=?");
            args.add(dueDateTo);
        }
        return new QuerySpec(baseSql, where.toString(), List.copyOf(args));
    }

    private SettlementReportDtos.Summary summary(QuerySpec spec) {
        String sql = """
                select count(*) document_count, count(distinct x.partner_id) partner_count,
                       coalesce(sum(x.gross_amount),0) gross_amount,
                       coalesce(sum(x.reduction_amount),0) reduction_amount,
                       coalesce(sum(x.total_amount),0) total_amount,
                       coalesce(sum(x.paid_amount),0) paid_amount,
                       coalesce(sum(x.unpaid_amount),0) unpaid_amount,
                       coalesce(sum(case when x.unpaid_amount>0 and x.due_date=x.as_of_date then 1 else 0 end),0) due_today_count,
                       coalesce(sum(case when x.unpaid_amount>0 and x.due_date=x.as_of_date then x.unpaid_amount else 0 end),0) due_today_amount,
                       coalesce(sum(case when x.unpaid_amount>0 and x.due_date<x.as_of_date then 1 else 0 end),0) overdue_count,
                       coalesce(sum(case when x.unpaid_amount>0 and x.due_date<x.as_of_date then x.unpaid_amount else 0 end),0) overdue_amount
                from (
                """ + spec.baseSql() + ") x" + spec.whereSql();
        return jdbc.queryForObject(sql, (rs, rowNum) -> new SettlementReportDtos.Summary(
                rs.getLong("document_count"), rs.getLong("partner_count"),
                amount(rs.getBigDecimal("gross_amount")), amount(rs.getBigDecimal("reduction_amount")),
                amount(rs.getBigDecimal("total_amount")), amount(rs.getBigDecimal("paid_amount")),
                amount(rs.getBigDecimal("unpaid_amount")), rs.getLong("due_today_count"),
                amount(rs.getBigDecimal("due_today_amount")), rs.getLong("overdue_count"),
                amount(rs.getBigDecimal("overdue_amount"))), spec.args().toArray());
    }

    private List<SettlementReportDtos.Row> page(QuerySpec spec, int pageNo, int pageSize) {
        String sql = "select x.* from (" + spec.baseSql() + ") x" + spec.whereSql() + """
                 order by case
                            when x.unpaid_amount>0 and x.due_date<x.as_of_date then 0
                            when x.unpaid_amount>0 and x.due_date=x.as_of_date then 1
                            when x.unpaid_amount>0 then 2 else 3 end,
                          x.due_date asc, x.business_date desc, x.source_id desc
                 limit ? offset ?
                """;
        List<Object> args = new ArrayList<>(spec.args());
        args.add(pageSize);
        args.add((long) (pageNo - 1) * pageSize);
        return jdbc.query(sql, (rs, rowNum) -> new SettlementReportDtos.Row(
                rs.getString("source_type"), rs.getLong("source_id"), rs.getString("source_no"),
                rs.getLong("partner_id"), rs.getString("partner_name"), rs.getDate("business_date").toLocalDate(),
                amount(rs.getBigDecimal("gross_amount")), amount(rs.getBigDecimal("reduction_amount")),
                amount(rs.getBigDecimal("total_amount")), amount(rs.getBigDecimal("paid_amount")),
                amount(rs.getBigDecimal("unpaid_amount")), rs.getInt("settlement_days"),
                rs.getDate("due_date").toLocalDate(), rs.getInt("overdue_days"),
                rs.getString("aging_bucket"), rs.getString("payment_status"), rs.getString("source_status")),
                args.toArray());
    }

    private List<SettlementReportDtos.AgingBucket> aging(QuerySpec spec) {
        String sql = """
                select x.aging_bucket, count(*) document_count,
                       coalesce(sum(x.unpaid_amount),0) outstanding_amount
                from (
                """ + spec.baseSql() + ") x" + spec.whereSql() + """
                 and x.unpaid_amount>0
                 group by x.aging_bucket
                """;
        Map<String, SettlementReportDtos.AgingBucket> rows = new LinkedHashMap<>();
        jdbc.query(sql, rs -> {
            String code = rs.getString("aging_bucket");
            rows.put(code, new SettlementReportDtos.AgingBucket(code, AGING_LABELS.getOrDefault(code, code),
                    rs.getLong("document_count"), amount(rs.getBigDecimal("outstanding_amount"))));
        }, spec.args().toArray());
        return AGING_ORDER.stream().map(code -> rows.getOrDefault(code,
                new SettlementReportDtos.AgingBucket(code, AGING_LABELS.get(code), 0, BigDecimal.ZERO))).toList();
    }

    private String receivableBaseSql() {
        return """
                select due_rows.*, cutoff.as_of_date,
                       greatest(datediff(cutoff.as_of_date,due_rows.due_date),0) overdue_days,
                       case when due_rows.unpaid_amount=0 then 'SETTLED'
                            when due_rows.due_date>cutoff.as_of_date then 'NOT_DUE'
                            when due_rows.due_date=cutoff.as_of_date then 'DUE_TODAY'
                            when datediff(cutoff.as_of_date,due_rows.due_date)<=30 then 'OVERDUE_1_30'
                            when datediff(cutoff.as_of_date,due_rows.due_date)<=60 then 'OVERDUE_31_60'
                            when datediff(cutoff.as_of_date,due_rows.due_date)<=90 then 'OVERDUE_61_90'
                            else 'OVERDUE_91_PLUS' end aging_bucket,
                       case when due_rows.unpaid_amount=0 then 'PAID'
                            when due_rows.paid_amount>0 then 'PARTIAL' else 'UNPAID' end payment_status
                from (
                  select raw_rows.*
                  from (
                    select 'SALES_ORDER' source_type, o.id source_id, o.order_no source_no,
                           o.customer_id partner_id, p.name partner_name, o.business_date,
                           greatest(o.receivable_amount,0) gross_amount,
                           greatest(o.adjusted_amount,0) reduction_amount,
                           greatest(o.receivable_amount-o.adjusted_amount,0) total_amount,
                           least(greatest(o.received_amount,0),greatest(o.receivable_amount-o.adjusted_amount,0)) paid_amount,
                           greatest(o.receivable_amount-o.adjusted_amount-o.received_amount,0) unpaid_amount,
                           o.settlement_days_snapshot settlement_days, o.due_date, o.status source_status
                    from sales_order o
                    join partner p on p.id=o.customer_id
                    where o.status not in ('DRAFT','CANCELLED') and o.receivable_amount>0 and o.business_date<=?
                    union all
                    select 'OUTBOUND' source_type, o.id source_id, o.order_no source_no,
                           o.customer_id partner_id, p.name partner_name, o.business_date,
                           greatest(o.receivable_amount,0) gross_amount, 0 reduction_amount,
                           greatest(o.receivable_amount,0) total_amount,
                           least(greatest(o.paid_amount,0),greatest(o.receivable_amount,0)) paid_amount,
                           greatest(o.receivable_amount-o.paid_amount,0) unpaid_amount,
                           o.settlement_days_snapshot settlement_days, o.due_date, o.status source_status
                    from outbound_order o
                    join partner p on p.id=o.customer_id
                    where o.sales_order_id=0 and o.status='SHIPPED' and o.receivable_amount>0 and o.business_date<=?
                    union all
                    select 'OPENING_RECEIVABLE' source_type,d.id source_id,d.document_no source_no,
                           d.partner_id,p.name partner_name,d.business_date,
                           d.total_amount gross_amount,0 reduction_amount,d.total_amount,
                           d.settled_amount paid_amount,d.total_amount-d.settled_amount unpaid_amount,
                           greatest(datediff(d.due_date,d.business_date),0) settlement_days,d.due_date,d.status source_status
                    from treasury_document d join partner p on p.id=d.partner_id
                    where d.document_type='OPENING_RECEIVABLE'
                      and d.status in ('CONFIRMED','PARTIALLY_SETTLED','SETTLED') and d.business_date<=?
                  ) raw_rows
                ) due_rows
                cross join (select cast(? as date) as as_of_date) cutoff
                """;
    }

    private String payableBaseSql() {
        return """
                select due_rows.*, cutoff.as_of_date,
                       greatest(datediff(cutoff.as_of_date,due_rows.due_date),0) overdue_days,
                       case when due_rows.unpaid_amount=0 then 'SETTLED'
                            when due_rows.due_date>cutoff.as_of_date then 'NOT_DUE'
                            when due_rows.due_date=cutoff.as_of_date then 'DUE_TODAY'
                            when datediff(cutoff.as_of_date,due_rows.due_date)<=30 then 'OVERDUE_1_30'
                            when datediff(cutoff.as_of_date,due_rows.due_date)<=60 then 'OVERDUE_31_60'
                            when datediff(cutoff.as_of_date,due_rows.due_date)<=90 then 'OVERDUE_61_90'
                            else 'OVERDUE_91_PLUS' end aging_bucket,
                       case when due_rows.unpaid_amount=0 then 'PAID'
                            when due_rows.paid_amount>0 then 'PARTIAL' else 'UNPAID' end payment_status
                from (
                  select raw_rows.*
                  from (
                    select 'PURCHASE_ORDER' source_type, o.id source_id, o.order_no source_no,
                           o.supplier_id partner_id, p.name partner_name, o.business_date,
                           greatest(case when o.source_type='DIRECT_TRADE'
                                           then coalesce(d.purchase_payable_amount,0)
                                         else o.total_amount end,0) gross_amount,
                           least(greatest(coalesce(r.returned_amount,0),0),
                             greatest(case when o.source_type='DIRECT_TRADE'
                                             then coalesce(d.purchase_payable_amount,0)
                                           else o.total_amount end,0)) reduction_amount,
                           greatest((case when o.source_type='DIRECT_TRADE'
                                            then coalesce(d.purchase_payable_amount,0)
                                          else o.total_amount end)-coalesce(r.returned_amount,0),0) total_amount,
                           least(greatest(o.paid_amount,0),
                             greatest((case when o.source_type='DIRECT_TRADE'
                                              then coalesce(d.purchase_payable_amount,0)
                                            else o.total_amount end)-coalesce(r.returned_amount,0),0)) paid_amount,
                           greatest((case when o.source_type='DIRECT_TRADE'
                                            then coalesce(d.purchase_payable_amount,0)
                                          else o.total_amount end)-coalesce(r.returned_amount,0)-o.paid_amount,0) unpaid_amount,
                           o.settlement_days_snapshot settlement_days, o.due_date, o.status source_status
                    from purchase_order o
                    join partner p on p.id=o.supplier_id
                    left join direct_trade_order d on d.purchase_order_id=o.id
                    left join (
                      select purchase_order_id,sum(payable_reduction_amount) returned_amount from purchase_return_order
                      where status='CONFIRMED' group by purchase_order_id
                    ) r on r.purchase_order_id=o.id
                    where o.status not in ('DRAFT','CANCELLED') and o.business_date<=?
                    union all
                    select 'INBOUND' source_type, o.id source_id, o.order_no source_no,
                           o.supplier_id partner_id, p.name partner_name, o.business_date,
                           greatest(o.payable_amount,0) gross_amount,
                           least(greatest(coalesce(r.returned_amount,0),0),greatest(o.payable_amount,0)) reduction_amount,
                           greatest(o.payable_amount-coalesce(r.returned_amount,0),0) total_amount,
                           least(greatest(o.paid_amount,0),greatest(o.payable_amount-coalesce(r.returned_amount,0),0)) paid_amount,
                           greatest(o.payable_amount-coalesce(r.returned_amount,0)-o.paid_amount,0) unpaid_amount,
                           o.settlement_days_snapshot settlement_days, o.due_date, o.status source_status
                    from inbound_order o
                    join partner p on p.id=o.supplier_id
                    left join (
                      select inbound_id,sum(payable_reduction_amount) returned_amount from purchase_return_order
                      where status='CONFIRMED' group by inbound_id
                    ) r on r.inbound_id=o.id
                    where o.purchase_order_id=0 and o.status='STOCKED' and o.payable_amount>0 and o.business_date<=?
                    union all
                    select 'OPENING_PAYABLE' source_type,d.id source_id,d.document_no source_no,
                           d.partner_id,p.name partner_name,d.business_date,
                           d.total_amount gross_amount,0 reduction_amount,d.total_amount,
                           d.settled_amount paid_amount,d.total_amount-d.settled_amount unpaid_amount,
                           greatest(datediff(d.due_date,d.business_date),0) settlement_days,d.due_date,d.status source_status
                    from treasury_document d join partner p on p.id=d.partner_id
                    where d.document_type='OPENING_PAYABLE'
                      and d.status in ('CONFIRMED','PARTIALLY_SETTLED','SETTLED') and d.business_date<=?
                  ) raw_rows
                ) due_rows
                cross join (select cast(? as date) as as_of_date) cutoff
                """;
    }

    private Snapshot databaseSnapshot() {
        return jdbc.queryForObject("select current_date as as_of_date, current_timestamp(6) as snapshot_at",
                (rs, rowNum) -> new Snapshot(rs.getDate("as_of_date").toLocalDate(),
                        timestamp(rs.getTimestamp("snapshot_at"))));
    }

    private String normalizeDirection(String direction) {
        String value = clean(direction, 20).toUpperCase(Locale.ROOT);
        if (!Set.of("RECEIVABLE", "PAYABLE").contains(value)) throw new BusinessException("不支持的应收应付方向");
        return value;
    }

    private void appendIn(StringBuilder sql, List<Object> args, String column, Set<Long> ids) {
        if (ids.isEmpty()) {
            sql.append(" and 1=0");
            return;
        }
        sql.append(" and ").append(column).append(" in (").append("?,".repeat(ids.size()));
        sql.setLength(sql.length() - 1);
        sql.append(')');
        args.addAll(ids);
    }

    private String clean(String value, int maxLength) {
        if (value == null) return "";
        String clean = value.trim();
        return clean.length() <= maxLength ? clean : clean.substring(0, maxLength);
    }

    private BigDecimal amount(BigDecimal value) { return value == null ? BigDecimal.ZERO : value; }
    private LocalDateTime timestamp(Timestamp value) { return value == null ? LocalDateTime.now() : value.toLocalDateTime(); }

    private record Snapshot(LocalDate asOfDate, LocalDateTime snapshotAt) { }
    private record QuerySpec(String baseSql, String whereSql, List<Object> args) { }
}
