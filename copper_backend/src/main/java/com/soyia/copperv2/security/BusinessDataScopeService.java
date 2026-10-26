package com.soyia.copperv2.security;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.soyia.copperv2.dto.DashboardDto;
import com.soyia.copperv2.dto.InventoryBatchDto;
import com.soyia.copperv2.dto.OrderDtos;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class BusinessDataScopeService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public BusinessDataScopeService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public void assertMasterAllowed(String dimension, Long id) {
        if (id == null || id == 0) return;
        Scope scope = effectiveScope(dimension);
        if (!scope.all() && !scope.selectedIds().contains(id)) throw new ForbiddenException("当前角色无权使用该" + dimensionName(dimension));
    }

    /**
     * A newly-created master record has no pre-existing selected id that can be
     * evaluated safely. Restricted master-data roles therefore fail closed
     * instead of creating a record outside their configured scope.
     */
    public void assertMasterCreationAllowed(String dimension) {
        if (!effectiveScope(dimension).all()) {
            throw new ForbiddenException(
                    "当前角色的" + dimensionName(dimension) + "数据范围受限，不能新建该主数据");
        }
    }

    public void assertFullMasterScope(String dimension) {
        if (!effectiveScope(dimension).all()) {
            throw new ForbiddenException(
                    "该操作要求" + dimensionName(dimension) + "数据范围为全部");
        }
    }

    public boolean hasFullMasterScope(String dimension) {
        return effectiveScope(dimension).all();
    }

    public Set<Long> filterMasterIds(String dimension, Iterable<Long> ids) {
        Scope scope = effectiveScope(dimension);
        Set<Long> result = new LinkedHashSet<>();
        for (Long id : ids) {
            if (id != null && (scope.all() || scope.selectedIds().contains(id))) {
                result.add(id);
            }
        }
        return Set.copyOf(result);
    }

    public void assertPartnerAccountAllowed(Long partnerId, String accountType) {
        List<Long> ids = jdbc.queryForList("SELECT id FROM partner_account WHERE partner_id=? AND account_type=?", Long.class, partnerId, accountType);
        if (!ids.isEmpty()) assertMasterAllowed("ACCOUNT", ids.get(0));
    }

    public <T> List<T> filterMasterList(String dimension, List<T> rows, String... idAccessors) {
        Scope scope = effectiveScope(dimension);
        if (scope.all()) return rows;
        return rows.stream().filter(item -> scope.selectedIds().contains(extractId(item, idAccessors))).toList();
    }

    public boolean documentUnrestricted() { return documentScope().all(); }

    /**
     * Aggregated settlement reports cannot be corrected by response-list filtering: doing so would
     * either expose documents outside the operator's scope or return a summary that does not match
     * its page rows. Document-restricted roles therefore fail closed. Partner-restricted roles are
     * represented as an explicit SQL scope so both detail and aggregate queries use the same set.
     */
    public SettlementReportScope settlementReportScope(Long requestedPartnerId) {
        if (!documentScope().all()) {
            throw new ForbiddenException("应收应付汇总要求业务单据数据范围为全部；当前角色仅可查看受限单据，不能生成可能失真的全量汇总");
        }
        Scope partnerScope = effectiveScope("PARTNER");
        if (requestedPartnerId != null && requestedPartnerId > 0) {
            if (!partnerScope.all() && !partnerScope.selectedIds().contains(requestedPartnerId)) {
                throw new ForbiddenException("当前角色无权查看该往来单位的应收应付汇总");
            }
            return new SettlementReportScope(partnerScope.all(), Set.of(requestedPartnerId));
        }
        return new SettlementReportScope(partnerScope.all(), partnerScope.selectedIds());
    }

    public record SettlementReportScope(boolean completeScope, Set<Long> partnerIds) {
        public SettlementReportScope {
            partnerIds = Set.copyOf(partnerIds == null ? Set.of() : partnerIds);
        }
    }

    public void assertFullExportAllowed() {
        if (!documentScope().all()) {
            throw new ForbiddenException("完整业务导出要求业务单据数据范围为全部");
        }
        for (String dimension : List.of("PARTNER", "PRODUCT", "WAREHOUSE", "ACCOUNT")) {
            if (!effectiveScope(dimension).all()) {
                throw new ForbiddenException("完整业务导出要求" + dimensionName(dimension) + "数据范围为全部");
            }
        }
    }

    public void assertDocumentAllowed(String resourceType, Long recordId) {
        if (recordId == null || recordId == 0 || documentScope().all()) return;
        if (!allowedDocumentIds(resourceType).contains(recordId)) throw new ForbiddenException("当前角色无权访问该业务记录");
    }

    public boolean hasFullDocumentScope() {
        return documentScope().all();
    }

    public <T> List<T> filterDocumentList(String resourceType, List<T> rows) {
        if (documentScope().all()) return rows;
        Set<Long> allowed = allowedDocumentIds(resourceType);
        return rows.stream().filter(item -> allowed.contains(extractId(item, "id"))).toList();
    }

    public <T> List<T> filterDerivedDocumentList(List<T> rows) {
        Map<String, Set<Long>> cache = new java.util.HashMap<>();
        boolean unrestricted = documentScope().all();
        return rows.stream().filter(item -> {
            String resourceType = normalizeSourceType(extractString(item, "sourceType"));
            Long sourceId = extractId(item, "sourceId");
            if (resourceType == null || sourceId == null) return false;
            if (unrestricted) return true;
            return cache.computeIfAbsent(resourceType, this::allowedDocumentIds).contains(sourceId);
        }).toList();
    }

    public <T> List<T> filterPartnerAccountRecords(List<T> rows, String partnerAccessor, String accountTypeAccessor, String defaultAccountType) {
        Scope partnerScope = effectiveScope("PARTNER");
        Scope accountScope = effectiveScope("ACCOUNT");
        if (partnerScope.all() && accountScope.all()) return rows;
        return rows.stream().filter(item -> {
            Long partnerId = extractId(item, partnerAccessor);
            if (partnerId == null || !partnerScope.all() && !partnerScope.selectedIds().contains(partnerId)) return false;
            if (accountScope.all()) return true;
            String accountType = extractString(item, accountTypeAccessor);
            if (accountType == null || accountType.isBlank()) accountType = defaultAccountType;
            List<Long> accountIds = jdbc.queryForList("SELECT id FROM partner_account WHERE partner_id=? AND account_type=?", Long.class, partnerId, accountType);
            return accountIds.stream().anyMatch(accountScope.selectedIds()::contains);
        }).toList();
    }

    public void stampDocumentOwner(String resourceType, Long recordId) {
        if (recordId == null || recordId == 0) return;
        SecurityContext.Principal principal = SecurityContext.require();
        jdbc.update("""
                INSERT IGNORE INTO security_business_record_owner
                (resource_type, record_id, owner_employee_id, owner_department_id, created_by_user_id)
                VALUES (?,?,?,?,?)
                """, resourceType, recordId, principal.employeeId(), principal.departmentId(), principal.userId());
    }

    public DashboardDto scopedDashboard(DashboardDto source) {
        DocumentScope document = documentScope();
        List<InventoryBatchDto> inventoryAlerts = sanitizeInventory(filterInventory(source.inventoryAlerts()));
        List<DashboardDto.FulfillmentReminder> fulfillmentReminders =
                filterDerivedDocumentList(source.fulfillmentReminders());
        List<OrderDtos.InboundRow> inbounds = filterDocumentList("INBOUND", source.recentInbounds());
        List<OrderDtos.ReceivableRow> receivables = filterDerivedDocumentList(source.recentReceivables());
        List<OrderDtos.PaymentRow> payments = filterDocumentList("PAYMENT", source.recentPayments());

        BigDecimal stockQuantity = scopedStock("quantity_available");
        BigDecimal stockCost = SecurityContext.require().hasPermission("inventory.cost.view") ? scopedStock("amount_cost") : BigDecimal.ZERO;
        if (document.all()) {
            return new DashboardDto(stockQuantity, stockCost, source.receivableAmount(), source.paidAmount(),
                    source.todayInboundQuantity(), source.todayOutboundQuantity(), source.ownerAttention(),
                    fulfillmentReminders, inventoryAlerts, inbounds, receivables, payments);
        }
        BigDecimal receivable = scopedReceivable();
        BigDecimal paid = aggregateDocuments("PAYMENT", "payment", "amount", "verification_status<>'REVERSED'");
        BigDecimal todayIn = aggregateDocuments("INBOUND", "inbound_order", "total_quantity", "business_date=CURDATE() AND status='STOCKED'");
        BigDecimal todayOut = aggregateDocuments("OUTBOUND", "outbound_order", "total_quantity", "business_date=CURDATE() AND status='SHIPPED'");
        DashboardDto.SettlementAttention emptySettlement = new DashboardDto.SettlementAttention(
                0, BigDecimal.ZERO, 0, BigDecimal.ZERO);
        DashboardDto.OwnerAttention restrictedAttention = new DashboardDto.OwnerAttention(false,
                emptySettlement, emptySettlement,
                new DashboardDto.InventoryAttention(0, 0, 0, 0),
                new DashboardDto.BankAttention(0, BigDecimal.ZERO, 0),
                new DashboardDto.InvoiceAttention(0, BigDecimal.ZERO, 0),
                new DashboardDto.CloseAttention(0L, "数据范围受限", null, "RESTRICTED", 0, 0,
                        "经营待办汇总仅对全量业务数据范围开放"));
        return new DashboardDto(stockQuantity, stockCost, receivable, paid, todayIn, todayOut,
                restrictedAttention, fulfillmentReminders, inventoryAlerts, inbounds, receivables, payments);
    }

    private BigDecimal scopedReceivable() {
        return aggregateDocuments("SALES_ORDER", "sales_order",
                        "GREATEST(receivable_amount-received_amount-adjusted_amount,0)",
                        "status NOT IN ('DRAFT','CANCELLED') AND receivable_amount>0")
                .add(aggregateDocuments("OUTBOUND", "outbound_order",
                        "GREATEST(receivable_amount-paid_amount,0)",
                        "sales_order_id=0 AND status='SHIPPED' AND receivable_amount>0"));
    }

    private List<InventoryBatchDto> filterInventory(List<InventoryBatchDto> rows) {
        Scope warehouse = effectiveScope("WAREHOUSE");
        Scope product = effectiveScope("PRODUCT");
        return rows.stream().filter(item -> (warehouse.all() || warehouse.selectedIds().contains(item.warehouseId()))
                && (product.all() || product.selectedIds().contains(item.productId()))).toList();
    }

    public List<InventoryBatchDto> sanitizeInventory(List<InventoryBatchDto> rows) {
        if (SecurityContext.require().hasPermission("inventory.cost.view")) return rows;
        return rows.stream().map(item -> new InventoryBatchDto(
                item.id(), item.productId(), item.sku(), item.productName(), item.grade(), item.spec(), item.warehouseId(),
                item.warehouseName(), item.batchNo(), item.sourceInboundId(), item.sourceInboundNo(),
                item.quantityIn(), item.quantityAvailable(), item.quantityReserved(), item.quantityFree(),
                item.quantityInTransit(), item.baseUnit(),
                item.packagingEffectiveRevisionId(), item.packagingProfileSnapshotJson(), item.packagingSnapshotHash(),
                item.packagingTrackingMode(), item.packageBarcode(), null, null, item.status(), item.receivedAt(),
                item.productionDate(), item.shelfLifeMode(), item.shelfLifeValue(), item.shelfLifeUnit(), item.expirationDate()
        )).toList();
    }

    private BigDecimal scopedStock(String column) {
        Scope warehouse = effectiveScope("WAREHOUSE");
        Scope product = effectiveScope("PRODUCT");
        StringBuilder sql = new StringBuilder("SELECT COALESCE(SUM(").append(column).append("),0) FROM stock_balance WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (!warehouse.all()) appendIn(sql, args, "warehouse_id", warehouse.selectedIds());
        if (!product.all()) appendIn(sql, args, "product_id", product.selectedIds());
        BigDecimal result = jdbc.queryForObject(sql.toString(), BigDecimal.class, args.toArray());
        return result == null ? BigDecimal.ZERO : result;
    }

    private BigDecimal aggregateDocuments(String resourceType, String table, String expression, String predicate) {
        Set<Long> ids = allowedDocumentIds(resourceType);
        if (ids.isEmpty()) return BigDecimal.ZERO;
        String sql = "SELECT COALESCE(SUM(" + expression + "),0) FROM " + table + " WHERE " + predicate + " AND id IN (" + placeholders(ids.size()) + ")";
        BigDecimal result = jdbc.queryForObject(sql, BigDecimal.class, ids.toArray());
        return result == null ? BigDecimal.ZERO : result;
    }

    private Set<Long> allowedDocumentIds(String resourceType) {
        DocumentScope scope = documentScope();
        if (scope.all()) return new LinkedHashSet<>(jdbc.queryForList(
                "SELECT record_id FROM security_business_record_owner WHERE resource_type=?", Long.class, resourceType));
        StringBuilder sql = new StringBuilder("SELECT record_id FROM security_business_record_owner WHERE resource_type=? AND (");
        List<Object> args = new ArrayList<>();
        args.add(resourceType);
        List<String> conditions = new ArrayList<>();
        if (scope.self()) {
            conditions.add("owner_employee_id=?");
            args.add(SecurityContext.require().employeeId());
        }
        if (!scope.departmentIds().isEmpty()) {
            conditions.add("owner_department_id IN (" + placeholders(scope.departmentIds().size()) + ")");
            args.addAll(scope.departmentIds());
        }
        if (conditions.isEmpty()) return Set.of();
        sql.append(String.join(" OR ", conditions)).append(')');
        return new LinkedHashSet<>(jdbc.queryForList(sql.toString(), Long.class, args.toArray()));
    }

    private DocumentScope documentScope() {
        List<ScopeRow> rows = scopeRows("DOCUMENT");
        if (rows.stream().anyMatch(row -> "ALL".equals(row.mode()))) return new DocumentScope(true, false, Set.of());
        SecurityContext.Principal principal = SecurityContext.require();
        boolean self = false;
        Set<Long> departments = new LinkedHashSet<>();
        for (ScopeRow row : rows) {
            switch (row.mode()) {
                case "SELF" -> self = true;
                case "DEPARTMENT" -> departments.add(principal.departmentId());
                case "DEPARTMENT_AND_CHILDREN" -> departments.addAll(departmentAndDescendants(principal.departmentId()));
                case "CUSTOM" -> departments.addAll(row.selectedIds());
                default -> { }
            }
        }
        return new DocumentScope(false, self, departments);
    }

    private Scope effectiveScope(String dimension) {
        List<ScopeRow> rows = scopeRows(dimension);
        if (rows.isEmpty() || rows.stream().anyMatch(row -> "ALL".equals(row.mode()))) return new Scope(true, Set.of());
        Set<Long> selected = new LinkedHashSet<>();
        for (ScopeRow row : rows) if ("CUSTOM".equals(row.mode())) selected.addAll(row.selectedIds());
        return new Scope(false, selected);
    }

    private List<ScopeRow> scopeRows(String dimension) {
        return jdbc.query("""
                SELECT s.scope_mode, s.selected_ids_json
                FROM system_user_role ur
                JOIN security_role r ON r.id=ur.role_id AND r.status='ENABLED'
                JOIN security_role_data_scope s ON s.role_id=r.id AND s.dimension=?
                WHERE ur.user_id=?
                """, (rs, rowNum) -> new ScopeRow(rs.getString(1), parseIds(rs.getString(2))),
                dimension, SecurityContext.require().userId());
    }

    private Set<Long> departmentAndDescendants(Long departmentId) {
        Set<Long> result = new LinkedHashSet<>();
        result.add(departmentId);
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id,parent_id FROM organization_department WHERE status='ENABLED'");
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Map<String, Object> row : rows) {
                long id = ((Number) row.get("id")).longValue();
                long parent = ((Number) row.get("parent_id")).longValue();
                if (result.contains(parent) && result.add(id)) changed = true;
            }
        }
        return result;
    }

    private List<Long> parseIds(String json) {
        try { return objectMapper.readValue(json, new TypeReference<>() {}); }
        catch (Exception ex) { return List.of(); }
    }

    private Long extractId(Object item, String... accessors) {
        if (item == null) return null;
        for (String accessor : accessors) {
            try {
                Method method = item.getClass().getMethod(accessor);
                Object value = method.invoke(item);
                if (value instanceof Number number) return number.longValue();
            } catch (ReflectiveOperationException ignored) { }
        }
        return null;
    }

    private String extractString(Object item, String accessor) {
        if (item == null) return null;
        try {
            Object value = item.getClass().getMethod(accessor).invoke(item);
            return value == null ? null : String.valueOf(value);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private String normalizeSourceType(String value) {
        if (value == null) return null;
        return switch (value) {
            case "PURCHASE_ORDER" -> "PURCHASE_ORDER";
            case "INBOUND", "INBOUND_ORDER" -> "INBOUND";
            case "SALES_ORDER" -> "SALES_ORDER";
            case "OUTBOUND", "OUTBOUND_ORDER" -> "OUTBOUND";
            case "DIRECT_TRADE" -> "DIRECT_TRADE";
            case "SALES_RETURN" -> "SALES_RETURN";
            case "PAYMENT" -> "PAYMENT";
            case "SUPPLIER_PAYMENT" -> "SUPPLIER_PAYMENT";
            case "RECEIVABLE_ADJUSTMENT" -> "RECEIVABLE_ADJUSTMENT";
            case "PARTNER_FUND_ADJUSTMENT" -> "PARTNER_FUND_ADJUSTMENT";
            case "TREASURY_DOCUMENT" -> "TREASURY_DOCUMENT";
            case "INVENTORY_TRANSFER" -> "INVENTORY_TRANSFER";
            case "INVENTORY_STOCKTAKE" -> "INVENTORY_STOCKTAKE";
            case "PURCHASE_RETURN" -> "PURCHASE_RETURN";
            case "INVENTORY_COST_ADJUSTMENT" -> "INVENTORY_COST_ADJUSTMENT";
            case "INVENTORY_NON_TRADE" -> "INVENTORY_NON_TRADE";
            case "LANDED_COST_ALLOCATION" -> "LANDED_COST_ALLOCATION";
            case "TAX_INVOICE" -> "TAX_INVOICE";
            default -> null;
        };
    }

    private void appendIn(StringBuilder sql, List<Object> args, String column, Set<Long> ids) {
        if (ids.isEmpty()) {
            sql.append(" AND 1=0");
            return;
        }
        sql.append(" AND ").append(column).append(" IN (").append(placeholders(ids.size())).append(')');
        args.addAll(ids);
    }

    private String placeholders(int size) { return String.join(",", java.util.Collections.nCopies(size, "?")); }
    private String dimensionName(String dimension) {
        return switch (dimension) {
            case "WAREHOUSE" -> "仓库";
            case "PARTNER" -> "往来单位";
            case "PRODUCT" -> "商品";
            case "ACCOUNT" -> "账号";
            default -> "数据";
        };
    }

    private record ScopeRow(String mode, List<Long> selectedIds) {}
    private record Scope(boolean all, Set<Long> selectedIds) {}
    private record DocumentScope(boolean all, boolean self, Set<Long> departmentIds) {}
}
