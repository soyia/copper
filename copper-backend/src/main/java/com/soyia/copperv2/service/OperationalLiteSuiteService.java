package com.soyia.copperv2.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.soyia.copperv2.dto.OperationalLiteDtos;
import com.soyia.copperv2.security.BusinessDataScopeService;
import com.soyia.copperv2.security.ForbiddenException;
import com.soyia.copperv2.security.SecurityContext;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class OperationalLiteSuiteService {
    private static final BigDecimal ZERO = new BigDecimal("0.00");
    private static final Set<String> FOLLOWUP_TYPES = Set.of(
            "SUPPLIER_DISPATCHED", "ETA_CHANGED", "DELAYED", "EXCEPTION", "RESOLVED");
    private static final Set<String> FOLLOWUP_EXCEPTION_TYPES = Set.of(
            "SHORT_SHIPMENT", "DAMAGED", "WRONG_SPEC", "LOGISTICS", "OTHER");
    private static final Set<String> PACKET_TYPES = Set.of(
            "CUSTOMER_QUOTE", "CUSTOMER_STATEMENT", "DELIVERY_SUMMARY");
    private static final Set<String> INTAKE_TYPES = Set.of(
            "SUPPLIER_QUOTE", "WEIGH_TICKET", "SIGNED_RECEIPT", "PACKAGE_LABEL");
    private static final Set<String> PUBLIC_DECISIONS = Set.of("CONFIRMED", "DISPUTED");
    private static final DateTimeFormatter NO_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final AtomicLong NO_COUNTER = new AtomicLong();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final long MAX_INTAKE_BYTES = 20L * 1024 * 1024;

    private final JdbcTemplate jdbc;
    private final BusinessDataScopeService dataScope;
    private final LocalFileStorageService storage;
    private final StorageCapacityService capacity;
    private final ObjectMapper objectMapper;
    private final PaddleOcrClient paddleOcrClient;
    private final HongtaiPackageLabelParser hongtaiPackageLabelParser;

    public OperationalLiteSuiteService(JdbcTemplate jdbc, BusinessDataScopeService dataScope,
                                       LocalFileStorageService storage, StorageCapacityService capacity,
                                       ObjectMapper objectMapper, PaddleOcrClient paddleOcrClient,
                                       HongtaiPackageLabelParser hongtaiPackageLabelParser) {
        this.jdbc = jdbc;
        this.dataScope = dataScope;
        this.storage = storage;
        this.capacity = capacity;
        this.objectMapper = objectMapper;
        this.paddleOcrClient = paddleOcrClient;
        this.hongtaiPackageLabelParser = hongtaiPackageLabelParser;
    }

    public OperationalLiteDtos.FollowupSummary purchaseFollowup(Long purchaseOrderId) {
        dataScope.assertDocumentAllowed("PURCHASE_ORDER", purchaseOrderId);
        Map<String, Object> order = requiredRow("""
                select o.id,o.order_no,o.status,o.source_type,o.expected_date,p.name supplier_name
                from purchase_order o join partner p on p.id=o.supplier_id where o.id=?
                """, purchaseOrderId);
        if ("DIRECT_TRADE".equals(text(order.get("source_type"))))
            throw new BusinessException("直运采购请在直运购销单中登记供应商发出，避免形成两套履约事实");
        List<OperationalLiteDtos.FollowupItemSummary> items = jdbc.query("""
                select i.id,i.product_sku_snapshot sku,p.name product_name,i.base_unit unit,
                       i.quantity ordered_quantity,i.received_quantity,
                       coalesce((select sum(fi.dispatched_quantity)
                         from purchase_followup_event_item fi
                         join purchase_followup_event fe on fe.id=fi.event_id
                         where fi.purchase_order_item_id=i.id and fe.event_type='SUPPLIER_DISPATCHED'),0) dispatched_quantity
                from purchase_order_item i join product p on p.id=i.product_id
                where i.purchase_order_id=? and i.status='ENABLED' order by i.id
                """, (rs, rowNum) -> {
            BigDecimal ordered = quantity(rs.getBigDecimal("ordered_quantity"));
            BigDecimal dispatched = quantity(rs.getBigDecimal("dispatched_quantity")).min(ordered);
            BigDecimal received = quantity(rs.getBigDecimal("received_quantity")).min(ordered);
            return new OperationalLiteDtos.FollowupItemSummary(
                    rs.getLong("id"), rs.getString("sku"), rs.getString("product_name"),
                    rs.getString("unit"), ordered, dispatched, received,
                    dispatched.subtract(received).max(BigDecimal.ZERO),
                    ordered.subtract(dispatched).max(BigDecimal.ZERO));
        }, purchaseOrderId);
        List<OperationalLiteDtos.FollowupEvent> events = followupEvents(purchaseOrderId);
        LocalDate latestEta = events.stream().filter(e -> e.expectedArrivalDate() != null)
                .map(OperationalLiteDtos.FollowupEvent::expectedArrivalDate).reduce((a, b) -> b)
                .orElse(localDate(order.get("expected_date")));
        boolean openException = false;
        String latestException = "";
        for (OperationalLiteDtos.FollowupEvent event : events) {
            if ("EXCEPTION".equals(event.eventType())) {
                openException = true;
                latestException = event.reason();
            } else if ("RESOLVED".equals(event.eventType())) {
                openException = false;
                latestException = "";
            }
        }
        BigDecimal ordered = sum(items, OperationalLiteDtos.FollowupItemSummary::orderedQuantity);
        BigDecimal dispatched = sum(items, OperationalLiteDtos.FollowupItemSummary::dispatchedQuantity);
        BigDecimal received = sum(items, OperationalLiteDtos.FollowupItemSummary::receivedQuantity);
        BigDecimal inTransit = sum(items, OperationalLiteDtos.FollowupItemSummary::inTransitQuantity);
        BigDecimal remaining = sum(items, OperationalLiteDtos.FollowupItemSummary::remainingToDispatch);
        int delayDays = latestEta == null || !LocalDate.now().isAfter(latestEta) || received.compareTo(ordered) >= 0
                ? 0 : (int) ChronoUnit.DAYS.between(latestEta, LocalDate.now());
        String fulfillment = received.compareTo(ordered) >= 0 ? "RECEIVED"
                : openException ? "EXCEPTION"
                : delayDays > 0 ? "DELAYED"
                : inTransit.signum() > 0 ? "IN_TRANSIT"
                : dispatched.signum() > 0 ? "PARTIAL_DISPATCHED" : "WAITING_DISPATCH";
        return new OperationalLiteDtos.FollowupSummary(
                purchaseOrderId, text(order.get("order_no")), text(order.get("supplier_name")),
                text(order.get("status")), fulfillment, latestEta, delayDays, openException, latestException,
                ordered, dispatched, received, inTransit, remaining, items, events);
    }

    @Transactional
    public OperationalLiteDtos.FollowupSummary addPurchaseFollowup(
            Long purchaseOrderId, OperationalLiteDtos.FollowupEventSave request) {
        dataScope.assertDocumentAllowed("PURCHASE_ORDER", purchaseOrderId);
        Map<String, Object> order = requiredRow("""
                select id,status,source_type from purchase_order where id=? for update
                """, purchaseOrderId);
        if (Set.of("DRAFT", "CANCELLED", "COMPLETED").contains(text(order.get("status"))))
            throw new BusinessException("只有履行中的采购订单可以登记跟催");
        if ("DIRECT_TRADE".equals(text(order.get("source_type"))))
            throw new BusinessException("直运采购必须回到直运购销单登记供应商发出");
        String eventType = enumValue(request.eventType(), FOLLOWUP_TYPES, "跟催事件");
        if (request.occurredOn().isAfter(LocalDate.now()))
            throw new BusinessException("事件日期不能晚于今天");
        LocalDate latestOccurredOn = jdbc.queryForObject("""
                select max(occurred_on) from purchase_followup_event where purchase_order_id=?
                """, LocalDate.class, purchaseOrderId);
        if (latestOccurredOn != null && request.occurredOn().isBefore(latestOccurredOn))
            throw new BusinessException("跟催事件日期不能早于已经登记的最新事件");
        if (Set.of("ETA_CHANGED", "DELAYED", "SUPPLIER_DISPATCHED").contains(eventType)
                && request.expectedArrivalDate() == null)
            throw new BusinessException("请填写最新预计到货日期");
        String reason = text(request.reason());
        if (Set.of("DELAYED", "EXCEPTION", "RESOLVED").contains(eventType) && reason.length() < 2)
            throw new BusinessException("请填写真实原因或处理结果");
        String exceptionType = "EXCEPTION".equals(eventType)
                ? enumValue(request.exceptionType(), FOLLOWUP_EXCEPTION_TYPES, "异常类型") : "";
        if ("RESOLVED".equals(eventType)) {
            Long openExceptions = jdbc.queryForObject("""
                    select count(*) from purchase_followup_event e
                    where e.purchase_order_id=? and e.event_type='EXCEPTION'
                      and e.id>coalesce((select max(r.id) from purchase_followup_event r
                        where r.purchase_order_id=e.purchase_order_id and r.event_type='RESOLVED'),0)
                    """, Long.class, purchaseOrderId);
            if (openExceptions == null || openExceptions == 0)
                throw new BusinessException("当前没有待解决的采购异常");
        }
        List<OperationalLiteDtos.FollowupItemSave> requestItems =
                request.items() == null ? List.of() : request.items();
        if ("SUPPLIER_DISPATCHED".equals(eventType) && requestItems.isEmpty())
            throw new BusinessException("供应商已发必须填写逐行发出数量");
        Actor actor = actor();
        long eventId = insert("""
                insert into purchase_followup_event
                (purchase_order_id,event_type,occurred_on,expected_arrival_date,carrier_name,
                 tracking_no,exception_type,reason,operator_id,operator_name)
                values (?,?,?,?,?,?,?,?,?,?)
                """, purchaseOrderId, eventType, Date.valueOf(request.occurredOn()),
                date(request.expectedArrivalDate()), requiredMax(request.carrierName(), 128),
                requiredMax(request.trackingNo(), 128), exceptionType,
                reason, actor.id(), actor.name());
        if ("SUPPLIER_DISPATCHED".equals(eventType)) {
            for (OperationalLiteDtos.FollowupItemSave item : requestItems) {
                Map<String, Object> row = requiredRow("""
                        select i.id,i.quantity,i.base_unit unit,
                          coalesce((select sum(fi.dispatched_quantity)
                            from purchase_followup_event_item fi join purchase_followup_event fe on fe.id=fi.event_id
                            where fi.purchase_order_item_id=i.id and fe.event_type='SUPPLIER_DISPATCHED'),0) dispatched_quantity
                        from purchase_order_item i
                        where i.id=? and i.purchase_order_id=? and i.status='ENABLED' for update
                        """, item.purchaseOrderItemId(), purchaseOrderId);
                BigDecimal quantity = quantity(item.dispatchedQuantity());
                BigDecimal remaining = quantity(row.get("quantity"))
                        .subtract(quantity(row.get("dispatched_quantity"))).max(BigDecimal.ZERO);
                if (quantity.signum() <= 0 || quantity.compareTo(remaining) > 0)
                    throw new BusinessException("供应商发出数量不能超过尚未发出的采购数量");
                jdbc.update("""
                        insert into purchase_followup_event_item
                        (event_id,purchase_order_item_id,dispatched_quantity,unit) values (?,?,?,?)
                        """, eventId, item.purchaseOrderItemId(), quantity, text(row.get("unit")));
            }
        }
        return purchaseFollowup(purchaseOrderId);
    }

    public OperationalLiteDtos.CashWindow cashWindow() {
        if (!dataScope.hasFullDocumentScope())
            throw new ForbiddenException("现金窗口要求业务单据数据范围为全部，避免把局部数据显示成企业资金缺口");
        dataScope.assertFullMasterScope("PARTNER");
        dataScope.assertFullMasterScope("ACCOUNT");
        LocalDate start = LocalDate.now();
        LocalDate end = start.plusDays(29);
        BigDecimal opening = scalarMoney("""
                select coalesce(sum(case when a.normal_direction='DEBIT'
                  then l.debit_amount-l.credit_amount else l.credit_amount-l.debit_amount end),0)
                from accounting_ledger_entry l join accounting_account a on a.id=l.account_id
                where a.account_code in ('1001','1002')
                """);
        List<OperationalLiteDtos.CashWindowSource> sources = new ArrayList<>();
        addCashSources(sources, """
                select 'SALES_ORDER' source_type,o.id source_id,o.order_no source_no,p.name partner_name,
                       o.due_date,'INFLOW' direction,'RECEIVABLE' fact_level,
                       greatest(o.receivable_amount-o.received_amount-o.adjusted_amount,0) amount,
                       '已形成应收，按账期预计回款' explanation
                from sales_order o join partner p on p.id=o.customer_id
                where o.status not in ('DRAFT','CANCELLED') and o.due_date<=?
                """, end);
        addCashSources(sources, """
                select 'OUTBOUND' source_type,o.id source_id,o.order_no source_no,p.name partner_name,
                       o.due_date,'INFLOW' direction,'RECEIVABLE' fact_level,
                       greatest(o.receivable_amount-o.paid_amount,0) amount,
                       '无订单出库已形成应收' explanation
                from outbound_order o join partner p on p.id=o.customer_id
                where o.sales_order_id=0 and o.status='SHIPPED' and o.due_date<=?
                """, end);
        addCashSources(sources, """
                select 'PURCHASE_ORDER' source_type,o.id source_id,o.order_no source_no,p.name partner_name,
                       o.due_date,'OUTFLOW' direction,'PAYABLE' fact_level,
                       greatest((case when o.source_type='DIRECT_TRADE' then coalesce(d.purchase_payable_amount,0)
                         else o.total_amount-coalesce(r.returned_amount,0) end)-o.paid_amount,0) amount,
                       case when o.source_type='DIRECT_TRADE' then '直运客户实收后形成的应付'
                            else '确认采购的到期付款安排' end explanation
                from purchase_order o join partner p on p.id=o.supplier_id
                left join direct_trade_order d on d.purchase_order_id=o.id
                left join (select purchase_order_id,sum(payable_reduction_amount) returned_amount
                           from purchase_return_order where status='CONFIRMED' group by purchase_order_id) r
                  on r.purchase_order_id=o.id
                where o.status not in ('DRAFT','CANCELLED') and o.due_date<=?
                """, end);
        addCashSources(sources, """
                select 'INBOUND' source_type,o.id source_id,o.order_no source_no,p.name partner_name,
                       o.due_date,'OUTFLOW' direction,'PAYABLE' fact_level,
                       greatest(o.payable_amount-coalesce(r.returned_amount,0)-o.paid_amount,0) amount,
                       '无采购订单入库形成的应付' explanation
                from inbound_order o join partner p on p.id=o.supplier_id
                left join (select inbound_id,sum(payable_reduction_amount) returned_amount
                           from purchase_return_order where status='CONFIRMED' group by inbound_id) r on r.inbound_id=o.id
                where o.purchase_order_id=0 and o.status='STOCKED' and o.due_date<=?
                """, end);
        addCashSources(sources, """
                select 'TREASURY_DOCUMENT' source_type,t.id source_id,t.document_no source_no,
                       t.partner_name_snapshot partner_name,t.due_date,
                       case when t.document_type in ('OTHER_INCOME','OTHER_RECEIVABLE') then 'INFLOW' else 'OUTFLOW' end direction,
                       'TREASURY' fact_level,greatest(t.total_amount-t.settled_amount,0) amount,
                       '已确认的其他收支或往来源单' explanation
                from treasury_document t
                where t.status in ('CONFIRMED','PARTIALLY_SETTLED') and t.due_date<=?
                """, end);
        sources = sources.stream().filter(row -> row.amount().signum() > 0)
                .sorted((a, b) -> a.dueDate().compareTo(b.dueDate())).toList();
        List<OperationalLiteDtos.CashWindowDay> days = new ArrayList<>();
        BigDecimal conservative = opening;
        BigDecimal scheduled = opening;
        LocalDate firstConservativeGap = null;
        LocalDate firstScheduledGap = null;
        BigDecimal gap7Conservative = ZERO, gap7Scheduled = ZERO, gap30Conservative = ZERO, gap30Scheduled = ZERO;
        for (int offset = 0; offset < 30; offset++) {
            LocalDate day = start.plusDays(offset);
            BigDecimal inflow = amountForDay(sources, day, "INFLOW");
            BigDecimal outflow = amountForDay(sources, day, "OUTFLOW");
            conservative = conservative.subtract(outflow);
            scheduled = scheduled.add(inflow).subtract(outflow);
            BigDecimal conservativeGap = conservative.min(BigDecimal.ZERO).abs();
            BigDecimal scheduledGap = scheduled.min(BigDecimal.ZERO).abs();
            if (firstConservativeGap == null && conservativeGap.signum() > 0) firstConservativeGap = day;
            if (firstScheduledGap == null && scheduledGap.signum() > 0) firstScheduledGap = day;
            if (offset < 7) {
                gap7Conservative = gap7Conservative.max(conservativeGap);
                gap7Scheduled = gap7Scheduled.max(scheduledGap);
            }
            gap30Conservative = gap30Conservative.max(conservativeGap);
            gap30Scheduled = gap30Scheduled.max(scheduledGap);
            days.add(new OperationalLiteDtos.CashWindowDay(day, money(inflow), money(outflow),
                    money(conservative), money(scheduled), money(conservativeGap), money(scheduledGap)));
        }
        BigDecimal receivable = sources.stream().filter(s -> "INFLOW".equals(s.direction()))
                .map(OperationalLiteDtos.CashWindowSource::amount).reduce(ZERO, BigDecimal::add);
        BigDecimal payable = sources.stream().filter(s -> "OUTFLOW".equals(s.direction()))
                .map(OperationalLiteDtos.CashWindowSource::amount).reduce(ZERO, BigDecimal::add);
        return new OperationalLiteDtos.CashWindow(
                LocalDateTime.now(), start, end, money(opening), money(receivable), money(payable),
                money(gap7Conservative), money(gap7Scheduled), money(gap30Conservative), money(gap30Scheduled),
                firstConservativeGap, firstScheduledGap, days, sources,
                "不等回款余额=当前现金及银行账面余额-累计到期付款；按账期余额=当前余额+累计到期应收-累计到期付款。应收只是预计流入，不代表已经收款。");
    }

    public List<OperationalLiteDtos.ConfirmationPacketRow> confirmationPackets(
            String packetType, Long sourceId) {
        String type = enumValue(packetType, PACKET_TYPES, "确认类型");
        assertConfirmationSource(type, sourceId);
        return jdbc.query("""
                select x.*,r.decision,r.confirmer_name,r.comment,r.confirmed_at
                from external_confirmation_packet x
                left join external_confirmation_record r on r.packet_id=x.id
                where x.packet_type=? and x.source_id=? order by x.id desc
                """, (rs, rowNum) -> packetRow(rs), type, sourceId);
    }

    @Transactional
    public OperationalLiteDtos.ConfirmationPacketCreated createConfirmationPacket(
            OperationalLiteDtos.ConfirmationPacketCreate request) {
        String type = enumValue(request.packetType(), PACKET_TYPES, "确认类型");
        if (request.asOfDate().isAfter(LocalDate.now()))
            throw new BusinessException("冻结截止日期不能晚于今天");
        if (request.expiresAt().isBefore(LocalDateTime.now().plusMinutes(5))
                || request.expiresAt().isAfter(LocalDateTime.now().plusDays(30)))
            throw new BusinessException("确认链接有效期必须在5分钟到30天之间");
        Snapshot snapshot = buildSnapshot(type, request.sourceId(), request.asOfDate());
        dataScope.assertMasterAllowed("PARTNER", snapshot.partnerId());
        String json = json(snapshot.payload());
        String hash = sha256(json);
        String token = newToken();
        Actor actor = actor();
        long id = insert("""
                insert into external_confirmation_packet
                (packet_no,token_hash,packet_type,source_type,source_id,source_no,partner_id,
                 partner_name_snapshot,as_of_date,snapshot_json,snapshot_hash,expires_at,
                 created_by,created_by_name)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, nextNo("EC"), sha256(token), type, snapshot.sourceType(), request.sourceId(),
                snapshot.sourceNo(), snapshot.partnerId(), snapshot.partnerName(), Date.valueOf(request.asOfDate()),
                json, hash, Timestamp.valueOf(request.expiresAt()), actor.id(), actor.name());
        OperationalLiteDtos.ConfirmationPacketRow row = packetById(id);
        return new OperationalLiteDtos.ConfirmationPacketCreated(row, token);
    }

    @Transactional
    public Long revokeConfirmationPacket(Long id, String reason) {
        Map<String, Object> row = requiredRow("select * from external_confirmation_packet where id=? for update", id);
        dataScope.assertMasterAllowed("PARTNER", number(row, "partner_id"));
        if (!"ACTIVE".equals(text(row.get("status"))))
            throw new BusinessException("只有待确认的链接可以撤销");
        if (text(reason).length() < 2) throw new BusinessException("请填写撤销原因");
        Actor actor = actor();
        jdbc.update("""
                update external_confirmation_packet set status='REVOKED',revoke_reason=?,
                revoked_by=?,revoked_by_name=?,revoked_at=now() where id=?
                """, text(reason), actor.id(), actor.name(), id);
        return id;
    }

    @Transactional
    public OperationalLiteDtos.PublicConfirmationPacket publicConfirmation(String token) {
        Map<String, Object> row = publicPacketRow(token, true);
        return publicPacket(row);
    }

    @Transactional
    public OperationalLiteDtos.PublicConfirmationPacket confirmPublic(
            String token, OperationalLiteDtos.PublicConfirmationSave request) {
        String decision = enumValue(request.decision(), PUBLIC_DECISIONS, "确认结果");
        Map<String, Object> row = publicPacketRow(token, false);
        String status = text(row.get("status"));
        if (Set.of("CONFIRMED", "DISPUTED").contains(status)) {
            OperationalLiteDtos.PublicConfirmationPacket existing = publicPacket(row);
            if (existing.confirmation() != null
                    && decision.equals(existing.confirmation().decision())
                    && request.confirmerName().trim().equals(existing.confirmation().confirmerName()))
                return existing;
            throw new BusinessException("该确认包已经提交，不能覆盖原确认记录");
        }
        if (!"ACTIVE".equals(status)) throw new BusinessException("确认链接已失效");
        String comment = text(request.comment());
        if ("DISPUTED".equals(decision) && comment.length() < 2)
            throw new BusinessException("存在差异时必须填写具体原因");
        jdbc.update("""
                insert into external_confirmation_record
                (packet_id,decision,confirmer_name,contact_tail,comment,snapshot_hash)
                values (?,?,?,?,?,?)
                """, number(row, "id"), decision, request.confirmerName().trim(),
                requiredMax(request.contactTail(), 16), comment, text(row.get("snapshot_hash")));
        jdbc.update("update external_confirmation_packet set status=? where id=?",
                decision, number(row, "id"));
        return publicPacket(requiredRow("select * from external_confirmation_packet where id=?",
                number(row, "id")));
    }

    public List<OperationalLiteDtos.IntakeBatch> intakeBatches(String status) {
        String normalizedStatus = StringUtils.hasText(status)
                ? enumValue(status, Set.of("REVIEW_REQUIRED", "CONFIRMED", "REJECTED", "FAILED"), "候选状态")
                : "";
        String where = StringUtils.hasText(normalizedStatus) ? " where b.status=?" : "";
        Object[] args = StringUtils.hasText(normalizedStatus) ? new Object[]{normalizedStatus} : new Object[]{};
        return jdbc.query("""
                select b.* from document_intake_batch b
                """ + where + " order by b.id desc limit 200",
                (rs, rowNum) -> intakeBatch(rs), args);
    }

    public OperationalLiteDtos.IntakeBatch intakeBatch(Long id) {
        return jdbc.query("""
                select * from document_intake_batch where id=?
                """, rs -> {
            if (!rs.next()) throw new BusinessException("单据识别批次不存在");
            return intakeBatch(rs);
        }, id);
    }

    @Transactional
    public OperationalLiteDtos.IntakeBatch createIntake(
            String documentType, String providedText, MultipartFile file) {
        return createIntake(documentType, providedText, null, file);
    }

    @Transactional
    public OperationalLiteDtos.IntakeBatch createIntake(
            String documentType, String providedText, Long expectedProductId, MultipartFile file) {
        String type = enumValue(documentType, INTAKE_TYPES, "单据类型");
        if (expectedProductId != null) {
            dataScope.assertMasterAllowed("PRODUCT", expectedProductId);
        }
        if (file == null || file.isEmpty()) throw new BusinessException("请选择需要识别的原始文件");
        if (file.getSize() > MAX_INTAKE_BYTES) throw new BusinessException("单个文件不能超过20MB");
        try {
            byte[] bytes = file.getBytes();
            String contentType = normalizedContentType(file);
            String originalName = safeFileName(file.getOriginalFilename());
            String digest = sha256(bytes);
            OperationalLiteDtos.IntakeBatch existing = existingIntake(type, digest);
            if (existing != null) {
                return refreshExistingPackageLabelMatch(existing, expectedProductId);
            }
            capacity.assertLargeWriteAllowed("单据候选上传");
            Extracted extracted = extract(type, bytes, contentType, providedText);
            String path = storage.store(bytes, "document-intake/" + LocalDate.now(), extension(originalName, contentType));
            Actor actor = actor();
            long id;
            try {
                id = insert("""
                        insert into document_intake_batch
                        (intake_no,document_type,original_file_name,content_type,file_size,sha256,
                         storage_path,extraction_mode,extracted_text,ocr_engine,ocr_model,
                         ocr_response_sha256,status,uploaded_by,uploaded_by_name)
                        values (?,?,?,?,?,?,?,?,?,?,?,?,'REVIEW_REQUIRED',?,?)
                        """, nextNo("DI"), type, originalName, contentType, bytes.length, digest,
                        path, extracted.mode(), extracted.text(), extracted.engine(), extracted.model(),
                        extracted.responseSha256(), actor.id(), actor.name());
            } catch (DuplicateKeyException ex) {
                storage.deleteAfterTransactionCommit(path);
                OperationalLiteDtos.IntakeBatch raced = existingIntake(type, digest);
                if (raced != null) {
                    return refreshExistingPackageLabelMatch(raced, expectedProductId);
                }
                throw new BusinessException("原始文件去重检查发生并发冲突，请重试");
            }
            List<CandidateDraft> candidates = recognize(
                    type, extracted.text(), extracted.confidence(), expectedProductId);
            int sort = 10;
            for (CandidateDraft candidate : candidates) {
                jdbc.update("""
                        insert into document_intake_candidate
                        (batch_id,field_code,field_label,candidate_value,confidence,source_excerpt,sort_no)
                        values (?,?,?,?,?,?,?)
                        """, id, candidate.code(), candidate.label(), candidate.value(),
                        candidate.confidence(), candidate.excerpt(), sort);
                sort += 10;
            }
            return Objects.requireNonNull(intakeBatch(id));
        } catch (IOException ex) {
            throw new BusinessException("原始文件读取失败");
        }
    }

    private OperationalLiteDtos.IntakeBatch existingIntake(String documentType, String digest) {
        List<Long> ids = jdbc.query("""
                select id from document_intake_batch
                where document_type=? and sha256=?
                order by id desc limit 1
                """, (rs, rowNum) -> rs.getLong("id"), documentType, digest);
        return ids.isEmpty() ? null : intakeBatch(ids.get(0));
    }

    private OperationalLiteDtos.IntakeBatch refreshExistingPackageLabelMatch(
            OperationalLiteDtos.IntakeBatch existing, Long expectedProductId) {
        if (!"PACKAGE_LABEL".equals(existing.documentType())
                || !"REVIEW_REQUIRED".equals(existing.status())) {
            return existing;
        }
        List<CandidateDraft> candidates = new ArrayList<>();
        for (String code : List.of("product_name", "specification")) {
            String value = existing.candidates().stream()
                    .filter(row -> code.equals(row.fieldCode()))
                    .map(OperationalLiteDtos.IntakeCandidate::candidateValue)
                    .findFirst().orElse("");
            candidates.add(new CandidateDraft(code, code, value,
                    BigDecimal.ONE, value));
        }
        appendProductMatchCandidates(candidates, expectedProductId);
        int sort = 80;
        for (CandidateDraft candidate : candidates.subList(2, candidates.size())) {
            jdbc.update("""
                    insert into document_intake_candidate
                    (batch_id,field_code,field_label,candidate_value,confidence,source_excerpt,sort_no)
                    values (?,?,?,?,?,?,?)
                    on duplicate key update
                    field_label=values(field_label),candidate_value=values(candidate_value),
                    confidence=values(confidence),source_excerpt=values(source_excerpt),
                    sort_no=values(sort_no)
                    """, existing.id(), candidate.code(), candidate.label(), candidate.value(),
                    candidate.confidence(), candidate.excerpt(), sort);
            sort += 10;
        }
        return Objects.requireNonNull(intakeBatch(existing.id()));
    }

    @Transactional
    public OperationalLiteDtos.IntakeBatch reviewIntake(
            Long id, OperationalLiteDtos.IntakeReview request) {
        Map<String, Object> batch = requiredRow("select * from document_intake_batch where id=? for update", id);
        if (!"REVIEW_REQUIRED".equals(text(batch.get("status"))))
            throw new BusinessException("只有待确认候选可以审核");
        String decision = enumValue(request.decision(), Set.of("CONFIRMED", "REJECTED"), "审核结果");
        if ("REJECTED".equals(decision) && text(request.note()).length() < 2)
            throw new BusinessException("驳回候选必须填写原因");
        for (OperationalLiteDtos.IntakeCandidateReview candidate : request.candidates()) {
            int updated = jdbc.update("""
                    update document_intake_candidate set confirmed_value=?
                    where batch_id=? and field_code=?
                    """, text(candidate.confirmedValue()), id, candidate.fieldCode());
            if (updated != 1) throw new BusinessException("识别候选字段不存在：" + candidate.fieldCode());
        }
        Actor actor = actor();
        jdbc.update("""
                update document_intake_batch set status=?,reviewed_by=?,reviewed_by_name=?,
                reviewed_at=now(),review_note=? where id=?
                """, decision, actor.id(), actor.name(), text(request.note()), id);
        return Objects.requireNonNull(intakeBatch(id));
    }

    public byte[] intakeContent(Long id) {
        Map<String, Object> row = requiredRow("select storage_path from document_intake_batch where id=?", id);
        return storage.read(text(row.get("storage_path")));
    }

    private List<OperationalLiteDtos.FollowupEvent> followupEvents(Long purchaseOrderId) {
        List<OperationalLiteDtos.FollowupEvent> events = jdbc.query("""
                select * from purchase_followup_event where purchase_order_id=? order by id
                """, (rs, rowNum) -> new OperationalLiteDtos.FollowupEvent(
                rs.getLong("id"), rs.getString("event_type"), rs.getDate("occurred_on").toLocalDate(),
                rs.getDate("expected_arrival_date") == null ? null : rs.getDate("expected_arrival_date").toLocalDate(),
                rs.getString("carrier_name"), rs.getString("tracking_no"), rs.getString("exception_type"),
                rs.getString("reason"), rs.getString("operator_name"),
                rs.getTimestamp("created_at").toLocalDateTime(), List.of()), purchaseOrderId);
        return events.stream().map(event -> new OperationalLiteDtos.FollowupEvent(
                event.id(), event.eventType(), event.occurredOn(), event.expectedArrivalDate(),
                event.carrierName(), event.trackingNo(), event.exceptionType(), event.reason(),
                event.operatorName(), event.createdAt(), jdbc.query("""
                        select i.id,i.purchase_order_item_id,poi.product_sku_snapshot sku,p.name product_name,
                               i.dispatched_quantity,i.unit
                        from purchase_followup_event_item i
                        join purchase_order_item poi on poi.id=i.purchase_order_item_id
                        join product p on p.id=poi.product_id where i.event_id=? order by i.id
                        """, (rs, rowNum) -> new OperationalLiteDtos.FollowupEventItem(
                        rs.getLong("id"), rs.getLong("purchase_order_item_id"), rs.getString("sku"),
                        rs.getString("product_name"), rs.getBigDecimal("dispatched_quantity"),
                        rs.getString("unit")), event.id()))).toList();
    }

    private void addCashSources(List<OperationalLiteDtos.CashWindowSource> target, String sql, LocalDate end) {
        target.addAll(jdbc.query(sql, (rs, rowNum) -> new OperationalLiteDtos.CashWindowSource(
                rs.getString("source_type"), rs.getLong("source_id"), rs.getString("source_no"),
                rs.getString("partner_name"), rs.getDate("due_date").toLocalDate(),
                rs.getString("direction"), rs.getString("fact_level"), money(rs.getBigDecimal("amount")),
                rs.getString("explanation")), Date.valueOf(end)));
    }

    private BigDecimal amountForDay(List<OperationalLiteDtos.CashWindowSource> sources,
                                    LocalDate date, String direction) {
        return sources.stream().filter(s -> direction.equals(s.direction()))
                .filter(s -> !s.dueDate().isAfter(date)
                        && (date.equals(LocalDate.now()) || date.equals(s.dueDate())))
                .filter(s -> date.equals(LocalDate.now()) ? !s.dueDate().isAfter(date) : s.dueDate().equals(date))
                .map(OperationalLiteDtos.CashWindowSource::amount).reduce(ZERO, BigDecimal::add);
    }

    private Snapshot buildSnapshot(String type, Long sourceId, LocalDate asOfDate) {
        return switch (type) {
            case "CUSTOMER_QUOTE" -> quoteSnapshot(sourceId);
            case "CUSTOMER_STATEMENT" -> statementSnapshot(sourceId, asOfDate);
            case "DELIVERY_SUMMARY" -> deliverySnapshot(sourceId);
            default -> throw new BusinessException("确认类型无效");
        };
    }

    private Snapshot quoteSnapshot(Long quoteId) {
        Map<String, Object> quote = requiredRow("""
                select q.*,p.name partner_name from crm_quote q join partner p on p.id=q.partner_id
                where q.id=?
                """, quoteId);
        if (!Set.of("ACTIVE", "ACCEPTED").contains(text(quote.get("status"))))
            throw new BusinessException("只有生效或已接受的报价可以发送外部确认");
        List<Map<String, Object>> items = jdbc.queryForList("""
                select product_sku_snapshot sku,product_name_snapshot product_name,
                       product_spec_snapshot product_spec,requested_quantity,requested_unit,
                       unit_price,total_amount from crm_quote_item where quote_id=? order by sort_no,id
                """, quoteId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", "客户报价确认");
        payload.put("quoteNo", text(quote.get("quote_no")) + "-V" + integer(quote.get("revision_no")));
        payload.put("quoteDate", localDate(quote.get("quote_date")));
        payload.put("validUntil", localDate(quote.get("valid_until")));
        payload.put("priceIncludesTax", bool(quote.get("price_includes_tax")));
        payload.put("totalAmount", money(quote.get("total_amount")));
        payload.put("deliveryPlanDate", localDate(quote.get("delivery_plan_date")));
        payload.put("paymentTerm", text(quote.get("payment_term")));
        payload.put("items", items);
        return new Snapshot("CRM_QUOTE", text(payload.get("quoteNo")), number(quote, "partner_id"),
                text(quote.get("partner_name")), payload);
    }

    private Snapshot statementSnapshot(Long partnerId, LocalDate asOfDate) {
        dataScope.settlementReportScope(partnerId);
        Map<String, Object> partner = requiredRow("select id,name from partner where id=?", partnerId);
        List<Map<String, Object>> lines = jdbc.queryForList("""
                select 'SALES_ORDER' source_type,o.id source_id,o.order_no source_no,o.business_date,o.due_date,
                       o.receivable_amount total_amount,o.received_amount+o.adjusted_amount settled_amount,
                       greatest(o.receivable_amount-o.received_amount-o.adjusted_amount,0) outstanding_amount
                from sales_order o where o.customer_id=? and o.status not in ('DRAFT','CANCELLED')
                  and o.business_date<=?
                union all
                select 'OUTBOUND',o.id,o.order_no,o.business_date,o.due_date,o.receivable_amount,o.paid_amount,
                       greatest(o.receivable_amount-o.paid_amount,0)
                from outbound_order o where o.customer_id=? and o.sales_order_id=0 and o.status='SHIPPED'
                  and o.business_date<=?
                order by business_date,source_id
                """, partnerId, Date.valueOf(asOfDate), partnerId, Date.valueOf(asOfDate));
        BigDecimal total = lines.stream().map(r -> money(r.get("total_amount"))).reduce(ZERO, BigDecimal::add);
        BigDecimal settled = lines.stream().map(r -> money(r.get("settled_amount"))).reduce(ZERO, BigDecimal::add);
        BigDecimal outstanding = lines.stream().map(r -> money(r.get("outstanding_amount"))).reduce(ZERO, BigDecimal::add);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", "客户对账单确认");
        payload.put("asOfDate", asOfDate);
        payload.put("totalAmount", money(total));
        payload.put("settledAmount", money(settled));
        payload.put("outstandingAmount", money(outstanding));
        payload.put("lines", lines);
        return new Snapshot("PARTNER", "客户对账单-" + asOfDate, partnerId,
                text(partner.get("name")), payload);
    }

    private Snapshot deliverySnapshot(Long salesOrderId) {
        Map<String, Object> order = requiredRow("""
                select o.id,o.order_no,o.customer_id,o.business_date,o.total_amount,p.name partner_name
                from sales_order o join partner p on p.id=o.customer_id where o.id=?
                """, salesOrderId);
        dataScope.assertDocumentAllowed("SALES_ORDER", salesOrderId);
        List<Map<String, Object>> deliveries = jdbc.queryForList("""
                select o.id outbound_id,o.order_no outbound_no,o.business_date,o.total_quantity,
                       coalesce(d.delivery_mode,'') delivery_mode,coalesce(d.carrier_name,'') carrier_name,
                       coalesce(d.tracking_no,'') tracking_no,coalesce(d.status,'PENDING') delivery_status,
                       d.dispatched_at,d.signed_at,coalesce(d.recipient_name,'') recipient_name,
                       coalesce(d.exception_note,'') exception_note
                from outbound_order o left join outbound_delivery d on d.outbound_id=o.id
                where o.sales_order_id=? and o.status='SHIPPED' order by o.business_date,o.id
                """, salesOrderId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", "交付摘要确认");
        payload.put("salesOrderNo", text(order.get("order_no")));
        payload.put("businessDate", localDate(order.get("business_date")));
        payload.put("totalAmount", money(order.get("total_amount")));
        payload.put("deliveries", deliveries);
        return new Snapshot("SALES_ORDER", text(order.get("order_no")), number(order, "customer_id"),
                text(order.get("partner_name")), payload);
    }

    private void assertConfirmationSource(String type, Long sourceId) {
        Snapshot snapshot = buildSnapshot(type, sourceId, LocalDate.now());
        dataScope.assertMasterAllowed("PARTNER", snapshot.partnerId());
    }

    private Map<String, Object> publicPacketRow(String token, boolean incrementView) {
        if (!StringUtils.hasText(token)) throw new BusinessException("确认链接无效");
        Map<String, Object> row = requiredRow("""
                select * from external_confirmation_packet where token_hash=? for update
                """, sha256(token.trim()));
        if ("ACTIVE".equals(text(row.get("status")))
                && localDateTime(row.get("expires_at")).isBefore(LocalDateTime.now())) {
            jdbc.update("update external_confirmation_packet set status='EXPIRED' where id=?",
                    number(row, "id"));
            row.put("status", "EXPIRED");
        }
        if (Set.of("REVOKED", "EXPIRED").contains(text(row.get("status"))))
            throw new BusinessException("确认链接不存在、已到期或已撤销");
        if (incrementView) {
            jdbc.update("""
                    update external_confirmation_packet
                    set view_count=view_count+1,last_viewed_at=now() where id=?
                    """, number(row, "id"));
            row.put("view_count", integer(row.get("view_count")) + 1L);
        }
        return row;
    }

    private OperationalLiteDtos.PublicConfirmationPacket publicPacket(Map<String, Object> row) {
        Long id = number(row, "id");
        OperationalLiteDtos.ConfirmationResult result = jdbc.query("""
                select * from external_confirmation_record where packet_id=?
                """, rs -> rs.next() ? new OperationalLiteDtos.ConfirmationResult(
                rs.getString("decision"), rs.getString("confirmer_name"), rs.getString("contact_tail"),
                rs.getString("comment"), rs.getString("snapshot_hash"),
                rs.getTimestamp("confirmed_at").toLocalDateTime()) : null, id);
        try {
            Map<String, Object> snapshot = objectMapper.readValue(
                    text(row.get("snapshot_json")), new TypeReference<>() {});
            return new OperationalLiteDtos.PublicConfirmationPacket(
                    text(row.get("packet_no")), text(row.get("packet_type")), text(row.get("source_no")),
                    text(row.get("partner_name_snapshot")), localDate(row.get("as_of_date")),
                    text(row.get("snapshot_hash")), localDateTime(row.get("expires_at")),
                    text(row.get("status")), snapshot, result);
        } catch (IOException ex) {
            throw new BusinessException("外部确认快照无法读取");
        }
    }

    private OperationalLiteDtos.ConfirmationPacketRow packetById(Long id) {
        return jdbc.query("""
                select x.*,r.decision,r.confirmer_name,r.comment,r.confirmed_at
                from external_confirmation_packet x
                left join external_confirmation_record r on r.packet_id=x.id where x.id=?
                """, rs -> rs.next() ? packetRow(rs) : null, id);
    }

    private OperationalLiteDtos.ConfirmationPacketRow packetRow(java.sql.ResultSet rs)
            throws java.sql.SQLException {
        return new OperationalLiteDtos.ConfirmationPacketRow(
                rs.getLong("id"), rs.getString("packet_no"), rs.getString("packet_type"),
                rs.getString("source_type"), rs.getLong("source_id"), rs.getString("source_no"),
                rs.getLong("partner_id"), rs.getString("partner_name_snapshot"),
                rs.getDate("as_of_date").toLocalDate(), rs.getString("snapshot_hash"),
                rs.getTimestamp("expires_at").toLocalDateTime(), rs.getString("status"),
                rs.getString("created_by_name"), rs.getLong("view_count"),
                rs.getTimestamp("last_viewed_at") == null ? null : rs.getTimestamp("last_viewed_at").toLocalDateTime(),
                rs.getString("revoke_reason"), rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getString("decision"), rs.getString("confirmer_name"), rs.getString("comment"),
                rs.getTimestamp("confirmed_at") == null ? null : rs.getTimestamp("confirmed_at").toLocalDateTime());
    }

    private OperationalLiteDtos.IntakeBatch intakeBatch(java.sql.ResultSet rs)
            throws java.sql.SQLException {
        Long id = rs.getLong("id");
        List<OperationalLiteDtos.IntakeCandidate> candidates = jdbc.query("""
                select * from document_intake_candidate where batch_id=? order by sort_no,id
                """, (crs, rowNum) -> new OperationalLiteDtos.IntakeCandidate(
                crs.getLong("id"), crs.getString("field_code"), crs.getString("field_label"),
                crs.getString("candidate_value"), crs.getString("confirmed_value"),
                crs.getBigDecimal("confidence"), crs.getString("source_excerpt"), crs.getInt("sort_no")), id);
        return new OperationalLiteDtos.IntakeBatch(
                id, rs.getString("intake_no"), rs.getString("document_type"),
                rs.getString("original_file_name"), rs.getString("content_type"),
                rs.getLong("file_size"), rs.getString("sha256"), rs.getString("extraction_mode"),
                rs.getString("ocr_engine"), rs.getString("ocr_model"),
                rs.getString("ocr_response_sha256"),
                rs.getString("status"), rs.getString("error_message"), rs.getString("uploaded_by_name"),
                rs.getString("reviewed_by_name"),
                rs.getTimestamp("reviewed_at") == null ? null : rs.getTimestamp("reviewed_at").toLocalDateTime(),
                rs.getString("review_note"), rs.getTimestamp("created_at").toLocalDateTime(), candidates);
    }

    private Extracted extract(
            String documentType, byte[] bytes, String contentType, String providedText) {
        String provided = text(providedText).trim();
        if (provided.length() > 12000) throw new BusinessException("补充识别文字不能超过12000字");
        if (!provided.isEmpty()) {
            return new Extracted("PROVIDED_TEXT", normalizeText(provided),
                    "", "", "", new BigDecimal("0.8500"));
        }
        if ("PACKAGE_LABEL".equals(documentType)) {
            if (!contentType.startsWith("image/")) {
                throw new BusinessException("宏泰标签识别 MVP 仅支持拍照或图片文件");
            }
            PaddleOcrClient.OcrResult result = paddleOcrClient.recognize(bytes);
            return new Extracted("OCR", limitedExtractedText(result.text()),
                    result.engine(), result.model(), result.responseSha256(), result.confidence());
        }
        if (contentType.startsWith("text/"))
            return new Extracted("TEXT",
                    limitedExtractedText(new String(bytes, StandardCharsets.UTF_8)),
                    "", "", "", new BigDecimal("0.8500"));
        if ("application/pdf".equals(contentType)) {
            try (PDDocument document = Loader.loadPDF(bytes)) {
                if (document.getNumberOfPages() > 50)
                    throw new BusinessException("轻量单据识别只支持50页以内的PDF");
                String text = limitedExtractedText(new PDFTextStripper().getText(document));
                return new Extracted(text.isEmpty() ? "FILE_ONLY" : "PDF_TEXT", text,
                        "", "", "", text.isEmpty()
                        ? new BigDecimal("0.0000") : new BigDecimal("0.8500"));
            } catch (IOException ex) {
                return new Extracted("FILE_ONLY", "",
                        "", "", "", new BigDecimal("0.0000"));
            }
        }
        return new Extracted("FILE_ONLY", "",
                "", "", "", new BigDecimal("0.0000"));
    }

    private List<CandidateDraft> recognize(
            String type, String text, BigDecimal extractionConfidence, Long expectedProductId) {
        if ("PACKAGE_LABEL".equals(type)) {
            List<CandidateDraft> result = new ArrayList<>(
                    hongtaiPackageLabelParser.parse(text, extractionConfidence).stream()
                            .map(field -> new CandidateDraft(
                                    field.code(), field.label(), field.value(),
                                    field.confidence(), field.excerpt()))
                            .toList());
            appendProductMatchCandidates(result, expectedProductId);
            return result;
        }
        List<CandidateSpec> specs = switch (type) {
            case "SUPPLIER_QUOTE" -> List.of(
                    spec("supplier_name", "供应商", "供应商|报价单位|供方", "[\\u4e00-\\u9fa5A-Za-z0-9（）()·\\-]{2,60}"),
                    spec("quote_no", "报价单号", "报价单号|报价编号|单号", "[A-Za-z0-9\\-_]{3,40}"),
                    spec("document_date", "报价日期", "报价日期|日期", "\\d{4}[-/.年]\\d{1,2}[-/.月]\\d{1,2}日?"),
                    spec("total_amount", "价税合计", "价税合计|合计金额|总金额", "[￥¥]?\\s*[0-9,]+(?:\\.\\d{1,2})?"),
                    spec("freight_amount", "运费", "运费", "[￥¥]?\\s*[0-9,]+(?:\\.\\d{1,2})?"),
                    spec("lead_time", "交期", "交期|预计到货", "[^\\n]{1,40}"),
                    rawSpec());
            case "WEIGH_TICKET" -> List.of(
                    spec("ticket_no", "磅单号", "磅单号|过磅单号|单号", "[A-Za-z0-9\\-_]{3,40}"),
                    spec("document_date", "过磅日期", "过磅日期|日期|时间", "\\d{4}[-/.年]\\d{1,2}[-/.月]\\d{1,2}日?"),
                    spec("vehicle_no", "车号", "车号|车牌", "[\\u4e00-\\u9fa5A-Z][A-Z0-9]{5,7}"),
                    spec("gross_weight", "毛重", "毛重", "[0-9,.]+\\s*(?:kg|KG|吨|千克)?"),
                    spec("tare_weight", "皮重", "皮重", "[0-9,.]+\\s*(?:kg|KG|吨|千克)?"),
                    spec("net_weight", "净重", "净重", "[0-9,.]+\\s*(?:kg|KG|吨|千克)?"),
                    rawSpec());
            case "SIGNED_RECEIPT" -> List.of(
                    spec("source_no", "销售/出库单号", "销售单号|出库单号|订单号|单号", "[A-Za-z0-9\\-_]{3,40}"),
                    spec("signed_date", "签收日期", "签收日期|签收时间|日期", "\\d{4}[-/.年]\\d{1,2}[-/.月]\\d{1,2}日?"),
                    spec("recipient_name", "签收人", "签收人|收货人", "[\\u4e00-\\u9fa5A-Za-z·]{2,30}"),
                    spec("received_quantity", "签收数量", "签收数量|实收数量|数量", "[0-9,.]+\\s*[\\u4e00-\\u9fa5A-Za-z]*"),
                    spec("exception_note", "异常说明", "异常|破损|少货|备注", "[^\\n]{1,100}"),
                    rawSpec());
            default -> List.of(rawSpec());
        };
        List<CandidateDraft> result = new ArrayList<>();
        for (CandidateSpec spec : specs) {
            if ("raw_summary".equals(spec.code())) {
                String summary = text.length() > 500 ? text.substring(0, 500) : text;
                result.add(new CandidateDraft(spec.code(), spec.label(), summary,
                        summary.isEmpty() ? new BigDecimal("0.0000")
                                : extractionConfidence.min(new BigDecimal("0.5000")), summary));
                continue;
            }
            Pattern pattern = Pattern.compile("(?im)(?:" + spec.labelPattern() + ")\\s*[:：]?\\s*(" + spec.valuePattern() + ")");
            Matcher matcher = pattern.matcher(text);
            String value = matcher.find() ? matcher.group(1).trim() : "";
            result.add(new CandidateDraft(spec.code(), spec.label(), value,
                    value.isEmpty() ? new BigDecimal("0.0000")
                            : extractionConfidence.min(new BigDecimal("0.8500")),
                    value.isEmpty() ? "" : matcher.group(0).trim()));
        }
        return result;
    }

    private CandidateSpec spec(String code, String label, String labelPattern, String valuePattern) {
        return new CandidateSpec(code, label, labelPattern, valuePattern);
    }

    private CandidateSpec rawSpec() {
        return new CandidateSpec("raw_summary", "识别原文摘要", "", "");
    }

    private void appendProductMatchCandidates(
            List<CandidateDraft> candidates, Long expectedProductId) {
        String labelName = candidateValue(candidates, "product_name");
        String labelSpec = candidateValue(candidates, "specification");
        List<ProductMatchRow> visibleProducts;
        if (expectedProductId != null) {
            visibleProducts = jdbc.query("""
                    select id,sku,name,spec from product where id=? and status='ENABLED'
                    """, (rs, rowNum) -> new ProductMatchRow(
                    rs.getLong("id"), rs.getString("sku"), rs.getString("name"), rs.getString("spec")),
                    expectedProductId);
        } else {
            List<ProductMatchRow> allProducts = jdbc.query("""
                    select id,sku,name,spec from product where status='ENABLED' order by id
                    """, (rs, rowNum) -> new ProductMatchRow(
                    rs.getLong("id"), rs.getString("sku"), rs.getString("name"), rs.getString("spec")));
            visibleProducts = dataScope.filterMasterList("PRODUCT", allProducts, "id");
        }
        List<ProductMatchRow> matches = visibleProducts.stream()
                .filter(row -> PackageLabelProductMatcher.matches(
                        labelName, labelSpec, row.name(), row.spec()))
                .toList();
        ProductMatchRow match = matches.size() == 1 ? matches.get(0) : null;
        String status;
        String reason;
        if (!StringUtils.hasText(labelName) || !StringUtils.hasText(labelSpec)) {
            status = "INCOMPLETE";
            reason = "标签产品名称或规格未识别完整，请人工选择商品";
        } else if (match != null) {
            status = "MATCHED";
            reason = expectedProductId == null
                    ? "产品名称和规格唯一匹配"
                    : "标签产品名称和规格与当前商品一致";
        } else if (expectedProductId != null) {
            status = "MISMATCH";
            reason = "标签产品名称或规格与当前商品不一致";
        } else if (matches.size() > 1) {
            status = "AMBIGUOUS";
            reason = "产品名称和规格匹配到多个商品，请人工选择";
        } else {
            status = "NOT_FOUND";
            reason = "未找到名称和规格同时匹配的商品，请人工选择";
        }
        addMatchCandidate(candidates, "product_match_status", "商品匹配状态", status);
        addMatchCandidate(candidates, "product_match_reason", "商品匹配说明", reason);
        addMatchCandidate(candidates, "matched_product_id", "匹配商品ID",
                match == null ? "" : String.valueOf(match.id()));
        addMatchCandidate(candidates, "matched_product_sku", "匹配商品SKU",
                match == null ? "" : match.sku());
        addMatchCandidate(candidates, "matched_product_name", "匹配商品名称",
                match == null ? "" : match.name());
        addMatchCandidate(candidates, "matched_product_spec", "匹配商品规格",
                match == null ? "" : match.spec());
    }

    private String candidateValue(List<CandidateDraft> candidates, String code) {
        return candidates.stream().filter(row -> code.equals(row.code()))
                .map(CandidateDraft::value).findFirst().orElse("");
    }

    private void addMatchCandidate(
            List<CandidateDraft> candidates, String code, String label, String value) {
        candidates.add(new CandidateDraft(code, label, text(value),
                StringUtils.hasText(value) ? new BigDecimal("1.0000") : new BigDecimal("0.0000"),
                text(value)));
    }

    private String normalizedContentType(MultipartFile file) {
        String type = text(file.getContentType()).toLowerCase(Locale.ROOT);
        if (type.equals("application/pdf") || type.startsWith("image/") || type.startsWith("text/")) return type;
        throw new BusinessException("仅支持 PDF、图片或文本文件");
    }

    private String extension(String fileName, String contentType) {
        int dot = fileName.lastIndexOf('.');
        if (dot >= 0 && dot < fileName.length() - 1) {
            String ext = fileName.substring(dot).toLowerCase(Locale.ROOT);
            if (ext.matches("\\.[a-z0-9]{1,8}")) return ext;
        }
        String ext = storage.extensionForContentType(contentType);
        return ext.isEmpty() ? ".bin" : ext;
    }

    private String safeFileName(String value) {
        String name = StringUtils.hasText(value) ? value.trim() : "document";
        name = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
        return name.length() <= 255 ? name : name.substring(name.length() - 255);
    }

    private String normalizeText(String value) {
        return text(value).replace('\u0000', ' ').replace("\r\n", "\n").replace('\r', '\n').trim();
    }

    private String limitedExtractedText(String value) {
        String normalized = normalizeText(value);
        return normalized.length() <= 100000 ? normalized : normalized.substring(0, 100000);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (IOException ex) {
            throw new BusinessException("冻结快照生成失败");
        }
    }

    private String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String nextNo(String prefix) {
        long suffix = Math.floorMod(NO_COUNTER.incrementAndGet(), 1000);
        return prefix + LocalDateTime.now().format(NO_TIME) + String.format("%03d", suffix);
    }

    private long insert(String sql, Object... args) {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
            return statement;
        }, holder);
        Number key = holder.getKey();
        if (key == null) throw new BusinessException("保存后未取得业务主键");
        return key.longValue();
    }

    private Map<String, Object> requiredRow(String sql, Object... args) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, args);
        if (rows.isEmpty()) throw new BusinessException("业务对象不存在或已不可用");
        return new LinkedHashMap<>(rows.get(0));
    }

    private BigDecimal scalarMoney(String sql) {
        BigDecimal value = jdbc.queryForObject(sql, BigDecimal.class);
        return money(value);
    }

    private BigDecimal sum(List<OperationalLiteDtos.FollowupItemSummary> rows,
                           java.util.function.Function<OperationalLiteDtos.FollowupItemSummary, BigDecimal> mapper) {
        return rows.stream().map(mapper).reduce(BigDecimal.ZERO, BigDecimal::add).setScale(6, RoundingMode.HALF_UP);
    }

    private String enumValue(String value, Set<String> allowed, String label) {
        String normalized = text(value).trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) throw new BusinessException(label + "无效");
        return normalized;
    }

    private String requiredMax(String value, int max) {
        String normalized = text(value).trim();
        if (normalized.length() > max) throw new BusinessException("输入内容过长");
        return normalized;
    }

    private Actor actor() {
        SecurityContext.Principal principal = SecurityContext.require();
        return new Actor(principal.employeeId() == null ? 0L : principal.employeeId(),
                text(principal.employeeName()));
    }

    private String sha256(byte[] value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    private String text(Object value) { return value == null ? "" : String.valueOf(value); }
    private Long number(Map<String, Object> row, String key) { return ((Number) row.get(key)).longValue(); }
    private int integer(Object value) { return value == null ? 0 : ((Number) value).intValue(); }
    private boolean bool(Object value) { return value instanceof Boolean b ? b : value instanceof Number n && n.intValue() != 0; }
    private BigDecimal quantity(Object value) {
        return value == null ? BigDecimal.ZERO.setScale(6) : new BigDecimal(String.valueOf(value)).setScale(6, RoundingMode.HALF_UP);
    }
    private BigDecimal money(Object value) {
        return value == null ? ZERO : new BigDecimal(String.valueOf(value)).setScale(2, RoundingMode.HALF_UP);
    }
    private LocalDate localDate(Object value) {
        if (value == null) return null;
        if (value instanceof Date date) return date.toLocalDate();
        if (value instanceof LocalDate date) return date;
        return LocalDate.parse(String.valueOf(value));
    }
    private LocalDateTime localDateTime(Object value) {
        if (value instanceof Timestamp timestamp) return timestamp.toLocalDateTime();
        if (value instanceof LocalDateTime dateTime) return dateTime;
        return LocalDateTime.parse(String.valueOf(value).replace(' ', 'T'));
    }
    private Date date(LocalDate value) { return value == null ? null : Date.valueOf(value); }

    private record Actor(Long id, String name) {}
    private record Snapshot(String sourceType, String sourceNo, Long partnerId,
                            String partnerName, Map<String, Object> payload) {}
    private record Extracted(
            String mode, String text, String engine, String model,
            String responseSha256, BigDecimal confidence) {}
    private record CandidateSpec(
            String code, String label, String labelPattern, String valuePattern) {}
    private record CandidateDraft(String code, String label, String value,
                                  BigDecimal confidence, String excerpt) {}
    private record ProductMatchRow(Long id, String sku, String name, String spec) {}
}
