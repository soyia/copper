package com.soyia.copperv2.service;

import com.soyia.copperv2.dto.BusinessPostingDtos;
import com.soyia.copperv2.security.BusinessDataScopeService;
import com.soyia.copperv2.security.SecurityContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class BusinessPostingService {
    private static final DateTimeFormatter NO_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");
    private static final AtomicInteger NO_COUNTER = new AtomicInteger();

    private final JdbcTemplate jdbc;
    private final BusinessDataScopeService dataScopeService;
    private final AccountingGenerationCoordinator accountingGenerationCoordinator;

    public BusinessPostingService(JdbcTemplate jdbc, BusinessDataScopeService dataScopeService,
                                  AccountingGenerationCoordinator accountingGenerationCoordinator) {
        this.jdbc = jdbc;
        this.dataScopeService = dataScopeService;
        this.accountingGenerationCoordinator = accountingGenerationCoordinator;
    }

    public List<BusinessPostingDtos.RuleView> rules() {
        return jdbc.query("""
                select id,rule_code,rule_name,source_type,posting_action,version_no,effective_from,effective_to,
                       rule_json,status,published_at
                from business_posting_rule_version order by source_type,posting_action,version_no desc
                """, (rs, rowNum) -> new BusinessPostingDtos.RuleView(
                rs.getLong("id"), rs.getString("rule_code"), rs.getString("rule_name"),
                rs.getString("source_type"), rs.getString("posting_action"), rs.getInt("version_no"),
                rs.getDate("effective_from").toLocalDate(),
                rs.getDate("effective_to") == null ? null : rs.getDate("effective_to").toLocalDate(),
                rs.getString("rule_json"), rs.getString("status"),
                rs.getTimestamp("published_at") == null ? null : rs.getTimestamp("published_at").toLocalDateTime()));
    }

    public List<BusinessPostingDtos.BatchRow> batches(String sourceType, String status) {
        StringBuilder sql = new StringBuilder("""
                select b.*,r.version_no rule_version_no,coalesce(p.name,'') partner_name
                from business_posting_batch b
                join business_posting_rule_version r on r.id=b.rule_version_id
                left join partner p on p.id=b.partner_id
                where 1=1
                """);
        List<Object> args = new ArrayList<>();
        if (StringUtils.hasText(sourceType)) { sql.append(" and b.source_type=?"); args.add(sourceType.trim()); }
        if (StringUtils.hasText(status)) { sql.append(" and b.status=?"); args.add(status.trim()); }
        sql.append(" order by b.id desc limit 500");
        return dataScopeService.filterDerivedDocumentList(jdbc.query(sql.toString(), batchMapper(), args.toArray()));
    }

    public BusinessPostingDtos.BatchDetail detail(Long id) {
        List<BusinessPostingDtos.BatchRow> rows = jdbc.query("""
                select b.*,r.version_no rule_version_no,coalesce(p.name,'') partner_name
                from business_posting_batch b
                join business_posting_rule_version r on r.id=b.rule_version_id
                left join partner p on p.id=b.partner_id where b.id=?
                """, batchMapper(), id);
        if (rows.isEmpty()) throw new BusinessException("过账批次不存在");
        BusinessPostingDtos.BatchRow batch = rows.get(0);
        dataScopeService.assertDocumentAllowed(normalizeDocumentType(batch.sourceType()), batch.sourceId());
        List<BusinessPostingDtos.EntryRow> entries = jdbc.query("""
                select e.*,coalesce(p.name,'') product_name,coalesce(w.name,'') warehouse_name,
                       coalesce(b.batch_no,'') inventory_batch_no
                from business_posting_entry e
                left join product p on p.id=e.product_id
                left join warehouse w on w.id=e.warehouse_id
                left join inventory_batch b on b.id=e.inventory_batch_id
                where e.posting_batch_id=? order by e.line_no,e.id
                """, (rs, rowNum) -> new BusinessPostingDtos.EntryRow(
                rs.getLong("id"), rs.getLong("posting_batch_id"), rs.getInt("line_no"),
                rs.getString("fact_type"), rs.getString("direction"), rs.getLong("partner_id"),
                rs.getLong("product_id"), rs.getString("product_name"), rs.getLong("warehouse_id"),
                rs.getString("warehouse_name"), rs.getLong("inventory_batch_id"),
                rs.getString("inventory_batch_no"), rs.getString("source_line_type"),
                rs.getLong("source_line_id"), rs.getBigDecimal("quantity"),
                rs.getString("quantity_unit"), rs.getBigDecimal("amount"),
                rs.getString("currency"), rs.getString("memo")), id);
        List<BusinessPostingDtos.EventRow> events = jdbc.query("""
                select * from business_posting_event where posting_batch_id=? order by id
                """, (rs, rowNum) -> new BusinessPostingDtos.EventRow(
                rs.getLong("id"), rs.getLong("posting_batch_id"), rs.getString("action"),
                rs.getString("from_status"), rs.getString("to_status"), rs.getString("reason"),
                rs.getLong("operator_id"), rs.getString("operator_name"),
                rs.getTimestamp("created_at").toLocalDateTime()), id);
        return new BusinessPostingDtos.BatchDetail(batch, entries, events);
    }

    public List<BusinessPostingDtos.OrderRevisionRow> orderRevisions(String orderType, Long orderId) {
        dataScopeService.assertDocumentAllowed(normalizeDocumentType(orderType), orderId);
        return jdbc.query("""
                select * from business_order_revision where order_type=? and order_id=? order by revision_no desc
                """, (rs, rowNum) -> new BusinessPostingDtos.OrderRevisionRow(
                rs.getLong("id"), rs.getString("order_type"), rs.getLong("order_id"),
                rs.getString("order_no"), rs.getInt("revision_no"), rs.getString("change_no"),
                rs.getString("change_type"), rs.getString("change_reason"),
                rs.getString("snapshot_before_json"), rs.getString("snapshot_after_json"),
                rs.getLong("reversed_posting_batch_id"), rs.getLong("posted_batch_id"),
                rs.getString("operator_name"), rs.getTimestamp("created_at").toLocalDateTime()),
                orderType, orderId);
    }

    public BusinessPostingDtos.PostingResult post(String sourceType, Long sourceId, String sourceNo,
                                                   String action, int sourceRevisionNo, LocalDate businessDate,
                                                   Long partnerId, Long originalBatchId, String reason,
                                                   List<BusinessPostingDtos.EntryDraft> entries) {
        String idempotencyKey = sourceType + ":" + sourceId + ":" + action + ":" + sourceRevisionNo;
        List<ExistingBatch> existing = jdbc.query("""
                select id,posting_no,status from business_posting_batch where idempotency_key=? for update
                """, (rs, rowNum) -> new ExistingBatch(rs.getLong(1), rs.getString(2), rs.getString(3)), idempotencyKey);
        if (!existing.isEmpty()) {
            ExistingBatch row = existing.get(0);
            if (List.of("POSTED", "REVERSED").contains(row.status())) {
                if (!"REVERSE".equals(action)) scheduleAccountingGeneration(row.id());
                return new BusinessPostingDtos.PostingResult(row.id(), row.postingNo(), true);
            }
            throw new BusinessException("该业务动作正在过账或上次过账未完成，请检查过账工作台");
        }
        RuleRow rule = activeRule(sourceType, action, businessDate);
        Actor actor = actor();
        String postingNo = nextPostingNo();
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    insert into business_posting_batch
                    (posting_no,source_type,source_id,source_no,posting_action,source_revision_no,
                     rule_version_id,rule_code,business_date,partner_id,original_batch_id,idempotency_key,
                     status,reason,operator_id,operator_name)
                    values (?,?,?,?,?,?,?,?,?,?,?,?, 'POSTING',?,?,?)
                    """, Statement.RETURN_GENERATED_KEYS);
            int i = 1;
            ps.setString(i++, postingNo); ps.setString(i++, sourceType); ps.setLong(i++, sourceId);
            ps.setString(i++, text(sourceNo)); ps.setString(i++, action); ps.setInt(i++, sourceRevisionNo);
            ps.setLong(i++, rule.id()); ps.setString(i++, rule.ruleCode()); ps.setDate(i++, Date.valueOf(businessDate));
            ps.setLong(i++, id(partnerId)); ps.setLong(i++, id(originalBatchId)); ps.setString(i++, idempotencyKey);
            ps.setString(i++, text(reason)); ps.setLong(i++, actor.id()); ps.setString(i, actor.name());
            return ps;
        }, keyHolder);
        Long batchId = keyHolder.getKey().longValue();
        event(batchId, "BEGIN", "", "POSTING", reason, actor);
        int lineNo = 1;
        for (BusinessPostingDtos.EntryDraft entry : entries) {
            jdbc.update("""
                    insert into business_posting_entry
                    (posting_batch_id,line_no,fact_type,direction,partner_id,product_id,warehouse_id,
                     inventory_batch_id,source_line_type,source_line_id,quantity,quantity_unit,amount,memo)
                    values (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, batchId, lineNo++, entry.factType(), entry.direction(), entry.partnerId(),
                    entry.productId(), entry.warehouseId(), entry.inventoryBatchId(), entry.sourceLineType(),
                    entry.sourceLineId(), entry.quantity(), entry.quantityUnit(), entry.amount(), entry.memo());
        }
        String hash = entryHash(entries);
        jdbc.update("update business_posting_batch set status='POSTED',entry_hash=?,posted_at=now() where id=? and status='POSTING'",
                hash, batchId);
        event(batchId, "COMPLETE", "POSTING", "POSTED", reason, actor);
        if (!"REVERSE".equals(action)) scheduleAccountingGeneration(batchId);
        return new BusinessPostingDtos.PostingResult(batchId, postingNo, false);
    }

    public BusinessPostingDtos.PostingResult reverseSource(String sourceType, Long sourceId, String sourceNo,
                                                            String reversalAction, int sourceRevisionNo,
                                                            LocalDate businessDate, Long partnerId,
                                                            List<String> originalActions, String reason) {
        List<Long> originalIds = jdbc.queryForList("""
                select id from business_posting_batch
                where source_type=? and source_id=? and posting_action in (%s) and status='POSTED'
                order by id for update
                """.formatted(placeholders(originalActions.size())), Long.class,
                joinArgs(sourceType, sourceId, originalActions));
        if (originalIds.isEmpty()) throw new BusinessException("没有可冲回的已过账业务事实");
        List<BusinessPostingDtos.EntryDraft> reversed = new ArrayList<>();
        for (Long originalId : originalIds) {
            reversed.addAll(jdbc.query("""
                    select fact_type,direction,partner_id,product_id,warehouse_id,inventory_batch_id,
                           source_line_type,source_line_id,quantity,quantity_unit,amount,memo
                    from business_posting_entry where posting_batch_id=? order by line_no
                    """, (rs, rowNum) -> new BusinessPostingDtos.EntryDraft(
                    rs.getString("fact_type"), opposite(rs.getString("direction")), rs.getLong("partner_id"),
                    rs.getLong("product_id"), rs.getLong("warehouse_id"), rs.getLong("inventory_batch_id"),
                    rs.getString("source_line_type"), rs.getLong("source_line_id"), rs.getBigDecimal("quantity"),
                    rs.getString("quantity_unit"), rs.getBigDecimal("amount"),
                    "冲回：" + rs.getString("memo")), originalId));
        }
        BusinessPostingDtos.PostingResult result = post(sourceType, sourceId, sourceNo, reversalAction,
                sourceRevisionNo, businessDate, partnerId, originalIds.get(0), reason, reversed);
        if (!result.alreadyPosted()) {
            jdbc.update("""
                    update business_posting_batch set status='REVERSED',reversal_batch_id=?,reversed_at=now()
                    where id in (%s) and status='POSTED'
                    """.formatted(placeholders(originalIds.size())), joinArgs(result.batchId(), originalIds));
            Actor actor = actor();
            for (Long originalId : originalIds) event(originalId, "REVERSE", "POSTED", "REVERSED", reason, actor);
        }
        scheduleAccountingGeneration(result.batchId());
        return result;
    }

    private void scheduleAccountingGeneration(Long batchId) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    accountingGenerationCoordinator.generateSafely(batchId);
                }
            });
            return;
        }
        accountingGenerationCoordinator.generateSafely(batchId);
    }

    public List<BusinessPostingDtos.ReconciliationRow> reconciliation() {
        List<BusinessPostingDtos.ReconciliationRow> rows = new ArrayList<>();
        rows.addAll(issueQuery("""
                select 'PURCHASE_ORDER' source_type,o.id,o.order_no,o.status,'COMMITMENT_POST' expected_action,
                       'MISSING_COMMITMENT' issue_code,'已确认采购订单缺少有效承诺过账' issue_message
                from purchase_order o where o.status not in ('DRAFT','CANCELLED') and not exists(
                  select 1 from business_posting_batch b where b.source_type='PURCHASE_ORDER' and b.source_id=o.id
                    and b.posting_action='COMMITMENT_POST' and b.source_revision_no=o.current_revision_no and b.status='POSTED')
                """));
        rows.addAll(issueQuery("""
                select 'SALES_ORDER',o.id,o.order_no,o.status,'COMMITMENT_POST',
                       'MISSING_COMMITMENT','已确认销售订单缺少有效承诺过账'
                from sales_order o where o.status not in ('DRAFT','CANCELLED') and not exists(
                  select 1 from business_posting_batch b where b.source_type='SALES_ORDER' and b.source_id=o.id
                    and b.posting_action='COMMITMENT_POST' and b.source_revision_no=o.current_revision_no and b.status='POSTED')
                """));
        rows.addAll(issueQuery("""
                select 'INBOUND',o.id,o.order_no,o.status,'POST','MISSING_POSTING','已入库单缺少库存及应付过账'
                from inbound_order o where o.status in ('STOCKED','REVERSED') and not exists(
                  select 1 from business_posting_batch b where b.source_type='INBOUND' and b.source_id=o.id
                    and b.posting_action='POST' and b.status in ('POSTED','REVERSED'))
                """));
        rows.addAll(issueQuery("""
                select 'OUTBOUND',o.id,o.order_no,o.status,'SETTLE','MISSING_SETTLEMENT','已定价出库单缺少应收过账'
                from outbound_order o where o.status in ('SHIPPED','REVERSED') and o.settlement_status<>'PENDING_PRICING' and not exists(
                  select 1 from business_posting_batch b where b.source_type='OUTBOUND' and b.source_id=o.id
                    and b.posting_action='SETTLE' and b.status in ('POSTED','REVERSED'))
                """));
        rows.addAll(issueQuery("""
                select 'DIRECT_TRADE',d.direct_trade_id,o.trade_no,d.status,'DELIVERY_POST',
                       'MISSING_DIRECT_TRADE_DELIVERY',
                       '已确认客户实收的直运批次缺少应收应付过账'
                from direct_trade_delivery d
                join direct_trade_order o on o.id=d.direct_trade_id
                where d.status in ('ACCEPTED','PARTIAL_ACCEPTED') and not exists(
                  select 1 from business_posting_batch b
                  join business_posting_entry e on e.posting_batch_id=b.id
                  where b.source_type='DIRECT_TRADE' and b.source_id=d.direct_trade_id
                    and b.posting_action='DELIVERY_POST' and b.status in ('POSTED','REVERSED')
                    and e.source_line_type='DIRECT_TRADE_DELIVERY' and e.source_line_id=d.id)
                """));
        rows.addAll(issueQuery("""
                select 'DIRECT_TRADE',r.direct_trade_id,o.trade_no,r.status,'RETURN_POST',
                       'MISSING_DIRECT_TRADE_RETURN','直运退货缺少应收应付冲减过账'
                from direct_trade_return r
                join direct_trade_order o on o.id=r.direct_trade_id
                where not exists(
                  select 1 from business_posting_batch b
                  join business_posting_entry e on e.posting_batch_id=b.id
                  where b.source_type='DIRECT_TRADE' and b.source_id=r.direct_trade_id
                    and b.posting_action=case when r.status='REVERSED' then 'RETURN_REVERSE' else 'RETURN_POST' end
                    and b.status='POSTED' and e.source_line_id=r.id)
                """));
        rows.addAll(issueQuery("""
                select 'SALES_RETURN',o.id,o.return_no,o.status,'POST','MISSING_POSTING','销售退货缺少库存及应收过账'
                from sales_return_order o where o.status in ('CONFIRMED','REVERSED') and not exists(
                  select 1 from business_posting_batch b where b.source_type='SALES_RETURN' and b.source_id=o.id
                    and b.posting_action='POST' and b.status in ('POSTED','REVERSED'))
                """));
        rows.addAll(issueQuery("""
                select 'PURCHASE_RETURN',o.id,o.order_no,o.status,'POST','MISSING_POSTING','采购退货缺少库存及应付过账'
                from purchase_return_order o where o.status in ('CONFIRMED','REVERSED') and not exists(
                  select 1 from business_posting_batch b where b.source_type='PURCHASE_RETURN' and b.source_id=o.id
                    and b.posting_action='POST' and b.status in ('POSTED','REVERSED'))
                """));
        rows.addAll(issueQuery("""
                select 'INVENTORY_TRANSFER',o.id,o.order_no,o.status,'DISPATCH','MISSING_DISPATCH','已发出调拨缺少出库及在途过账'
                from inventory_transfer_order o where o.status in ('IN_TRANSIT','COMPLETED','REVERSED') and not exists(
                  select 1 from business_posting_batch b where b.source_type='INVENTORY_TRANSFER' and b.source_id=o.id
                    and b.posting_action='DISPATCH' and b.status in ('POSTED','REVERSED'))
                """));
        rows.addAll(issueQuery("""
                select 'INVENTORY_TRANSFER',o.id,o.order_no,o.status,'RECEIVE','MISSING_RECEIVE','已收货调拨缺少在途冲减及入库过账'
                from inventory_transfer_order o where o.received_at is not null and o.status in ('COMPLETED','REVERSED') and not exists(
                  select 1 from business_posting_batch b where b.source_type='INVENTORY_TRANSFER' and b.source_id=o.id
                    and b.posting_action='RECEIVE' and b.status in ('POSTED','REVERSED'))
                """));
        rows.addAll(issueQuery("""
                select 'INVENTORY_STOCKTAKE',o.id,o.order_no,o.status,'POST','MISSING_POSTING','已确认盘点缺少库存过账'
                from inventory_stocktake_order o where o.status in ('CONFIRMED','REVERSED') and not exists(
                  select 1 from business_posting_batch b where b.source_type='INVENTORY_STOCKTAKE' and b.source_id=o.id
                    and b.posting_action='POST' and b.status in ('POSTED','REVERSED'))
                """));
        rows.addAll(issueQuery("""
                select 'INVENTORY_COST_ADJUSTMENT',o.id,o.adjustment_no,o.status,'POST','MISSING_POSTING','已确认成本调整缺少库存成本过账'
                from inventory_cost_adjustment o where o.status in ('CONFIRMED','REVERSED') and not exists(
                  select 1 from business_posting_batch b where b.source_type='INVENTORY_COST_ADJUSTMENT' and b.source_id=o.id
                    and b.posting_action='POST' and b.status in ('POSTED','REVERSED'))
                """));
        rows.addAll(issueQuery("""
                select 'INVENTORY_NON_TRADE',o.id,o.document_no,o.status,'POST','MISSING_POSTING','已发放或已归档的其他出入库缺少库存及成本过账'
                from inventory_non_trade_document o where o.status in ('ISSUED_PENDING_SIGNATURE','CONFIRMED','REVERSED') and not exists(
                  select 1 from business_posting_batch b where b.source_type='INVENTORY_NON_TRADE' and b.source_id=o.id
                    and b.posting_action='POST' and b.status in ('POSTED','REVERSED'))
                """));
        rows.addAll(issueQuery("""
                select 'LANDED_COST_ALLOCATION',o.id,o.allocation_no,o.status,'POST','MISSING_POSTING','已确认采购费用分摊缺少库存成本过账'
                from landed_cost_allocation o where o.status in ('CONFIRMED','REVERSED') and o.capitalized_amount>0 and not exists(
                  select 1 from business_posting_batch b where b.source_type='LANDED_COST_ALLOCATION' and b.source_id=o.id
                    and b.posting_action='POST' and b.status in ('POSTED','REVERSED'))
                """));
        rows.addAll(issueQuery("""
                select 'PAYMENT',o.id,o.payment_no,o.verification_status,'POST','MISSING_POSTING','客户收款缺少应收核销或预收过账'
                from payment o where not exists(
                  select 1 from business_posting_batch b where b.source_type='PAYMENT' and b.source_id=o.id
                    and b.posting_action='POST' and b.status in ('POSTED','REVERSED'))
                """));
        rows.addAll(issueQuery("""
                select 'SUPPLIER_PAYMENT',o.id,o.payment_no,o.verification_status,'POST','MISSING_POSTING','供应商付款缺少应付核销或预付过账'
                from supplier_payment o where not exists(
                  select 1 from business_posting_batch b where b.source_type='SUPPLIER_PAYMENT' and b.source_id=o.id
                    and b.posting_action='POST' and b.status in ('POSTED','REVERSED'))
                """));
        rows.addAll(issueQuery("""
                select 'RECEIVABLE_ADJUSTMENT',o.id,o.adjustment_no,o.status,'POST','MISSING_POSTING','已确认应收调整缺少业务过账'
                from receivable_adjustment o where o.status in ('CONFIRMED','REVERSED') and not exists(
                  select 1 from business_posting_batch b where b.source_type='RECEIVABLE_ADJUSTMENT' and b.source_id=o.id
                    and b.posting_action='POST' and b.status in ('POSTED','REVERSED'))
                """));
        rows.addAll(issueQuery("""
                select 'PARTNER_FUND_ADJUSTMENT',o.id,o.adjustment_no,o.status,'POST','MISSING_POSTING','已确认余额调整缺少业务过账'
                from partner_fund_adjustment o where o.status in ('CONFIRMED','REVERSED') and not exists(
                  select 1 from business_posting_batch b where b.source_type='PARTNER_FUND_ADJUSTMENT' and b.source_id=o.id
                    and b.posting_action='POST' and b.status in ('POSTED','REVERSED'))
                """));
        rows.addAll(issueQuery("""
                select 'OUTBOUND',o.id,o.order_no,o.status,'SHIP','MISSING_POSTING','已发货出库单缺少库存过账'
                from outbound_order o where o.status in ('SHIPPED','REVERSED') and not exists(
                  select 1 from business_posting_batch b where b.source_type='OUTBOUND' and b.source_id=o.id
                    and b.posting_action='SHIP' and b.status in ('POSTED','REVERSED'))
                """));
        rows.addAll(issueQuery("""
                select b.source_type,b.source_id,b.source_no,b.status,b.posting_action,'INCOMPLETE_BATCH',
                       concat('过账批次 ',b.posting_no,' 状态为 ',b.status)
                from business_posting_batch b where b.status in ('POSTING','FAILED')
                """));
        return dataScopeService.filterDerivedDocumentList(rows);
    }

    private List<BusinessPostingDtos.ReconciliationRow> issueQuery(String sql) {
        return jdbc.query(sql, (rs, rowNum) -> new BusinessPostingDtos.ReconciliationRow(
                rs.getString(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getString(7)));
    }

    private RuleRow activeRule(String sourceType, String action, LocalDate businessDate) {
        List<RuleRow> rows = jdbc.query("""
                select id,rule_code from business_posting_rule_version
                where source_type=? and posting_action=? and status='ENABLED'
                  and effective_from<=? and (effective_to is null or effective_to>=?)
                order by effective_from desc,version_no desc limit 1
                """, (rs, rowNum) -> new RuleRow(rs.getLong(1), rs.getString(2)),
                sourceType, action, Date.valueOf(businessDate), Date.valueOf(businessDate));
        if (rows.isEmpty()) throw new BusinessException("业务日期没有有效过账规则：" + sourceType + "/" + action);
        return rows.get(0);
    }

    private org.springframework.jdbc.core.RowMapper<BusinessPostingDtos.BatchRow> batchMapper() {
        return (rs, rowNum) -> new BusinessPostingDtos.BatchRow(
                rs.getLong("id"), rs.getString("posting_no"), rs.getString("source_type"),
                rs.getLong("source_id"), rs.getString("source_no"), rs.getString("posting_action"),
                rs.getInt("source_revision_no"), rs.getString("rule_code"), rs.getInt("rule_version_no"),
                rs.getDate("business_date").toLocalDate(), rs.getLong("partner_id"),
                rs.getString("partner_name"), rs.getLong("original_batch_id"),
                rs.getLong("reversal_batch_id"), rs.getString("status"), rs.getString("entry_hash"),
                rs.getString("reason"), rs.getString("operator_name"), rs.getInt("legacy_imported") == 1,
                rs.getTimestamp("posted_at") == null ? null : rs.getTimestamp("posted_at").toLocalDateTime(),
                rs.getTimestamp("reversed_at") == null ? null : rs.getTimestamp("reversed_at").toLocalDateTime(),
                rs.getTimestamp("created_at").toLocalDateTime());
    }

    private void event(Long batchId, String action, String from, String to, String reason, Actor actor) {
        jdbc.update("""
                insert into business_posting_event
                (posting_batch_id,action,from_status,to_status,reason,operator_id,operator_name)
                values (?,?,?,?,?,?,?)
                """, batchId, action, from, to, text(reason), actor.id(), actor.name());
    }

    private String entryHash(List<BusinessPostingDtos.EntryDraft> entries) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (BusinessPostingDtos.EntryDraft entry : entries) {
                String line = String.join("|", entry.factType(), entry.direction(), String.valueOf(entry.partnerId()),
                        String.valueOf(entry.productId()), String.valueOf(entry.warehouseId()),
                        String.valueOf(entry.inventoryBatchId()), entry.sourceLineType(), String.valueOf(entry.sourceLineId()),
                        entry.quantity().toPlainString(), entry.quantityUnit(), entry.amount().toPlainString(), entry.memo());
                digest.update(line.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '\n');
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception ex) {
            throw new IllegalStateException("无法计算过账分录摘要", ex);
        }
    }

    private String normalizeDocumentType(String sourceType) {
        return switch (sourceType) {
            case "INBOUND" -> "INBOUND";
            case "OUTBOUND" -> "OUTBOUND";
            default -> sourceType;
        };
    }

    private Actor actor() {
        SecurityContext.Principal principal = SecurityContext.get();
        return principal == null ? new Actor(0L, "系统") : new Actor(principal.userId(), principal.employeeName());
    }

    private String nextPostingNo() {
        return "GZ" + LocalDateTime.now().format(NO_TIME) + String.format("%03d", Math.floorMod(NO_COUNTER.incrementAndGet(), 1000));
    }

    private String opposite(String direction) { return "INCREASE".equals(direction) ? "DECREASE" : "INCREASE"; }
    private long id(Long value) { return value == null ? 0L : value; }
    private String text(String value) { return value == null ? "" : value; }
    private String placeholders(int size) { return String.join(",", java.util.Collections.nCopies(size, "?")); }
    private Object[] joinArgs(Object first, Object second, List<?> rest) {
        List<Object> args = new ArrayList<>(); args.add(first); args.add(second); args.addAll(rest); return args.toArray();
    }
    private Object[] joinArgs(Object first, List<?> rest) {
        List<Object> args = new ArrayList<>(); args.add(first); args.addAll(rest); return args.toArray();
    }

    private record RuleRow(Long id, String ruleCode) {}
    private record ExistingBatch(Long id, String postingNo, String status) {}
    private record Actor(Long id, String name) {}
}
