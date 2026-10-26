package com.soyia.copperv2.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.soyia.copperv2.dto.InventoryDtos;
import com.soyia.copperv2.dto.PackagingDtos;
import com.soyia.copperv2.dto.ProductPurchaseOptionsDto;
import com.soyia.copperv2.security.BusinessDataScopeService;
import com.soyia.copperv2.security.SecurityAuditService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class PackagingService {
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal ONE = BigDecimal.ONE;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final BusinessDataScopeService dataScopeService;
    private final SecurityAuditService auditService;

    public PackagingService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
                            BusinessDataScopeService dataScopeService,
                            SecurityAuditService auditService) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.dataScopeService = dataScopeService;
        this.auditService = auditService;
    }

    public PackagingDtos.Profile profile(Long productId) {
        return profile(productId, null);
    }

    public List<PackagingDtos.PackageDefinition> packageDefinitions(Long productId) {
        requireProduct(productId);
        return jdbcTemplate.query("""
                select product_id,package_unit,barcode,shelf_life_mode,shelf_life_value,
                       shelf_life_unit,revision_id,updated_at
                from product_package_definition
                where product_id=?
                order by package_unit
                """, (rs, rowNum) -> new PackagingDtos.PackageDefinition(
                rs.getLong("product_id"), rs.getString("package_unit"), rs.getString("barcode"),
                rs.getString("shelf_life_mode"),
                rs.getObject("shelf_life_value") == null ? null : rs.getInt("shelf_life_value"),
                rs.getString("shelf_life_unit"), rs.getLong("revision_id"),
                rs.getTimestamp("updated_at").toLocalDateTime()
        ), productId);
    }

    @Transactional
    public List<PackagingDtos.PackageDefinition> savePackageDefinitions(
            Long productId, PackagingDtos.PackageDefinitionsSave request) {
        ProductBase product = requireProduct(productId);
        jdbcTemplate.queryForObject("select id from product where id=? for update", Long.class, productId);
        Set<String> allowedUnits = new HashSet<>();
        for (PackagingDtos.Rule rule : profile(productId).rules()) {
            if ("ENABLED".equalsIgnoreCase(rule.status())) allowedUnits.add(key(rule.parentUnit()));
        }
        Map<String, PackagingDtos.PackageDefinitionSave> definitions = new LinkedHashMap<>();
        Set<String> barcodes = new HashSet<>();
        for (PackagingDtos.PackageDefinitionSave item : request.definitions()) {
            String packageUnit = item.packageUnit().trim();
            if (!allowedUnits.contains(key(packageUnit))) {
                throw new BusinessException("包装单位“" + packageUnit + "”不在商品当前有效包装层级中");
            }
            if (definitions.putIfAbsent(key(packageUnit), item) != null) {
                throw new BusinessException("包装单位“" + packageUnit + "”重复");
            }
            String barcode = blank(item.barcode()).trim();
            if (barcode.length() > 128) throw new BusinessException("包装条码最多128个字符");
            if (StringUtils.hasText(barcode) && !barcodes.add(key(barcode))) {
                throw new BusinessException("同一商品的包装条码不能重复");
            }
            validateShelfLife(item);
        }
        for (PackagingDtos.PackageDefinitionSave item : definitions.values()) {
            String barcode = blank(item.barcode()).trim();
            if (!StringUtils.hasText(barcode)) continue;
            Integer duplicate = jdbcTemplate.queryForObject("""
                    select count(*) from product_package_definition
                    where barcode=? and product_id<>?
                    """, Integer.class, barcode, productId);
            if (duplicate != null && duplicate > 0) {
                throw new BusinessException("包装条码“" + barcode + "”已被其他商品使用");
            }
        }
        Integer revisionNo = jdbcTemplate.queryForObject("""
                select coalesce(max(revision_no),0)+1
                from product_package_definition_revision where product_id=?
                """, Integer.class, productId);
        long revisionId = insertId("""
                insert into product_package_definition_revision
                (product_id,revision_no,change_reason,created_at) values(?,?,?,now())
                """, productId, revisionNo == null ? 1 : revisionNo, request.changeReason().trim());
        jdbcTemplate.update("delete from product_package_definition where product_id=?", productId);
        for (PackagingDtos.PackageDefinitionSave item : definitions.values()) {
            String packageUnit = item.packageUnit().trim();
            String mode = shelfLifeMode(item.shelfLifeMode());
            Integer value = "PERIOD".equals(mode) ? item.shelfLifeValue() : null;
            String unit = "PERIOD".equals(mode) ? shelfLifeUnit(item.shelfLifeUnit()) : "";
            String barcode = blank(item.barcode()).trim();
            jdbcTemplate.update("""
                    insert into product_package_definition
                    (product_id,package_unit,barcode,shelf_life_mode,shelf_life_value,shelf_life_unit,
                     revision_id,change_reason,updated_at)
                    values(?,?,?,?,?,?,?,?,now())
                    """, productId, packageUnit, StringUtils.hasText(barcode) ? barcode : null,
                    mode, value, unit, revisionId, request.changeReason().trim());
            jdbcTemplate.update("""
                    insert into product_package_definition_revision_item
                    (revision_id,product_id,package_unit,barcode,shelf_life_mode,shelf_life_value,shelf_life_unit)
                    values(?,?,?,?,?,?,?)
                    """, revisionId, productId, packageUnit, barcode, mode, value, unit);
        }
        return packageDefinitions(product.id());
    }

    public PackagingDtos.PackageBarcodeMatch packageBarcode(String barcode) {
        String value = blank(barcode).trim();
        if (!StringUtils.hasText(value)) throw new BusinessException("请输入包装条码");
        List<PackagingDtos.PackageBarcodeMatch> rows = jdbcTemplate.query("""
                select d.product_id,p.sku,p.name product_name,d.package_unit,d.barcode,
                       d.shelf_life_mode,d.shelf_life_value,d.shelf_life_unit
                from product_package_definition d
                join product p on p.id=d.product_id and p.status='ENABLED'
                where d.barcode=?
                """, (rs, rowNum) -> new PackagingDtos.PackageBarcodeMatch(
                rs.getLong("product_id"), rs.getString("sku"), rs.getString("product_name"),
                rs.getString("package_unit"), rs.getString("barcode"), rs.getString("shelf_life_mode"),
                rs.getObject("shelf_life_value") == null ? null : rs.getInt("shelf_life_value"),
                rs.getString("shelf_life_unit")
        ), value);
        if (rows.isEmpty()) throw new BusinessException("未找到此包装条码对应的商品");
        Set<Long> allowedProductIds = dataScopeService.filterMasterIds(
                "PRODUCT", rows.stream().map(PackagingDtos.PackageBarcodeMatch::productId).toList());
        rows = rows.stream().filter(item -> allowedProductIds.contains(item.productId())).toList();
        if (rows.isEmpty()) throw new BusinessException("未找到此包装条码对应的商品");
        if (rows.size() > 1) throw new BusinessException("包装条码对应多个商品，请先修复商品资料");
        PackagingDtos.PackageBarcodeMatch match = rows.get(0);
        boolean activePackage = profile(match.productId()).rules().stream()
                .anyMatch(rule -> "ENABLED".equalsIgnoreCase(rule.status()) && same(rule.parentUnit(), match.packageUnit()));
        if (!activePackage) throw new BusinessException("此条码对应的包装已不在商品当前有效包装层级中");
        return match;
    }

    public PackagingDtos.PackageDefinition packageDefinition(Long productId, String packageUnit) {
        if (!StringUtils.hasText(packageUnit)) return null;
        return packageDefinitions(productId).stream()
                .filter(item -> same(item.packageUnit(), packageUnit))
                .findFirst().orElse(null);
    }

    public PackagingDtos.Profile batchProfile(Long batchId) {
        List<BatchPackagingVersion> batches = jdbcTemplate.query("""
                select product_id, warehouse_id, packaging_version_no, packaging_effective_revision_id,
                       packaging_profile_snapshot_json, packaging_tracking_mode
                from inventory_batch where id = ?
                """, (rs, rowNum) -> new BatchPackagingVersion(
                rs.getLong("product_id"), rs.getLong("warehouse_id"),
                rs.getInt("packaging_version_no"),
                rs.getLong("packaging_effective_revision_id"),
                rs.getString("packaging_profile_snapshot_json"), rs.getString("packaging_tracking_mode")
        ), batchId);
        if (batches.isEmpty()) {
            throw new BusinessException("库存批次不存在");
        }
        BatchPackagingVersion batch = batches.get(0);
        dataScopeService.assertMasterAllowed("PRODUCT", batch.productId());
        dataScopeService.assertMasterAllowed("WAREHOUSE", batch.warehouseId());
        if ("QUANTITY_ONLY".equalsIgnoreCase(batch.trackingMode()) && StringUtils.hasText(batch.snapshotJson())) {
            return legacyBatchProfile(batch);
        }
        if (batch.effectiveRevisionId() > 0) {
            List<PolicyRow> rows = queryPolicyByEffectiveRevision(batch.productId(), batch.effectiveRevisionId());
            if (!rows.isEmpty()) {
                return assembleProfile(requireProduct(batch.productId()), rows.get(0));
            }
        }
        if (batch.versionNo() > 0) {
            List<PolicyRow> rows = queryVersionPolicy(batch.productId(), batch.versionNo());
            if (!rows.isEmpty()) {
                return assembleProfile(requireProduct(batch.productId()), rows.get(0));
            }
        }
        if (StringUtils.hasText(batch.snapshotJson())) {
            ProductBase product = requireProduct(batch.productId());
            return immutableBatchSnapshotProfile(batch.snapshotJson(), product.id(), product.name());
        }
        throw new BusinessException("库存批次包装历史版本缺失，请先进行数据修复；系统不会回退当前商品配置");
    }

    private PackagingDtos.Profile profile(Long productId, Integer requestedVersionNo) {
        ProductBase product = requireProduct(productId);
        List<PolicyRow> policies = requestedVersionNo == null
                ? queryCurrentPolicy(productId)
                : queryVersionPolicy(productId, requestedVersionNo);
        if (policies.isEmpty() && requestedVersionNo != null) {
            throw new BusinessException("商品计量包装历史版本不存在，禁止回退当前配置");
        }

        PolicyRow policy = policies.isEmpty()
                ? defaultPolicy(product)
                : policies.get(0);
        return assembleProfile(product, policy);
    }

    private PackagingDtos.Profile assembleProfile(ProductBase product, PolicyRow policy) {
        String baseUnit = unitName(policy.baseUnit(), product.unit());
        int versionNo = policy.versionNo() == null ? 0 : policy.versionNo();

        List<PackagingDtos.Rule> rules = versionNo <= 0 ? List.of() : jdbcTemplate.query("""
                select *
                from product_package_rule
                where product_id = ? and version_no = ? and status = 'ENABLED'
                order by sort_no, id
                """, (rs, rowNum) -> new PackagingDtos.Rule(
                rs.getLong("id"),
                rs.getLong("product_id"),
                rs.getInt("version_no"),
                rs.getString("parent_unit"),
                rs.getString("child_unit"),
                rs.getString("conversion_mode"),
                rs.getBigDecimal("fixed_child_quantity"),
                rs.getBigDecimal("reference_base_quantity"),
                rs.getInt("breakable") == 1,
                rs.getInt("repackable") == 1,
                rs.getInt("purchase_allowed") == 1,
                rs.getInt("sales_allowed") == 1,
                rs.getInt("outbound_allowed") == 1,
                rs.getInt("integer_only") == 1,
                rs.getInt("sort_no"),
                rs.getString("status"),
                rs.getString("rule_key"),
                rs.getString("source_level"),
                rs.getInt("overridden") == 1
        ), product.id(), versionNo);

        String templateName = templateName(policy.sourceTemplateId());
        List<String> overrideFields = overrideFields(policy.sourceOverrideRevisionId());
        boolean inherited = policy.sourceOverrideRevisionId() == 0;

        return new PackagingDtos.Profile(
                product.id(), product.name(), baseUnit,
                unitName(policy.minimumSalesUnit(), baseUnit),
                unitName(policy.minimumOutboundUnit(), unitName(policy.minimumSalesUnit(), baseUnit)),
                unitName(policy.defaultPurchaseUnit(), baseUnit),
                unitName(policy.defaultSalesUnit(), unitName(policy.minimumSalesUnit(), baseUnit)),
                roundingPolicy(policy.roundingPolicy()), versionNo, rules,
                unitName(policy.sourceLevel(), policy.sourceTemplateRevisionId() > 0 ? "TEMPLATE" : "SYSTEM_DEFAULT"),
                inherited, zeroToNull(policy.sourceTemplateId()), templateName,
                zeroToNull(policy.sourceTemplateRevisionId()), policy.templateRevisionNo(),
                zeroToNull(product.seriesId()), product.seriesName(), zeroToNull(policy.sourceOverrideRevisionId()),
                zeroToNull(policy.effectiveRevisionId()), policy.contentHash(), overrideFields, List.of()
        );
    }

    private List<PolicyRow> queryCurrentPolicy(Long productId) {
        return queryPolicy("""
                select base_unit, minimum_sales_unit, minimum_outbound_unit,
                       default_purchase_unit, default_sales_unit, rounding_policy, version_no,
                       effective_revision_id, source_level, source_template_id,
                       source_template_revision_id, source_override_revision_id, content_hash,
                       (select revision_no from packaging_template_revision where id=source_template_revision_id) template_revision_no
                from product_measure_policy where product_id = ?
                """, productId);
    }

    private List<PolicyRow> queryVersionPolicy(Long productId, Integer versionNo) {
        return queryPolicy("""
                select base_unit, minimum_sales_unit, minimum_outbound_unit,
                       default_purchase_unit, default_sales_unit, rounding_policy, version_no,
                       effective_revision_id, source_level, source_template_id,
                       source_template_revision_id, source_override_revision_id, content_hash,
                       (select revision_no from packaging_template_revision where id=source_template_revision_id) template_revision_no
                from product_measure_policy_version where product_id = ? and version_no = ?
                """, productId, versionNo);
    }

    private List<PolicyRow> queryPolicyByEffectiveRevision(Long productId, Long revisionId) {
        return queryPolicy("""
                select base_unit, minimum_sales_unit, minimum_outbound_unit,
                       default_purchase_unit, default_sales_unit, rounding_policy, version_no,
                       effective_revision_id, source_level, source_template_id,
                       source_template_revision_id, source_override_revision_id, content_hash,
                       (select revision_no from packaging_template_revision where id=source_template_revision_id) template_revision_no
                from product_measure_policy_version
                where product_id = ? and effective_revision_id = ?
                """, productId, revisionId);
    }

    private List<PolicyRow> queryPolicy(String sql, Object... args) {
        return jdbcTemplate.query(sql, (rs, rowNum) -> new PolicyRow(
                rs.getString("base_unit"), rs.getString("minimum_sales_unit"),
                rs.getString("minimum_outbound_unit"), rs.getString("default_purchase_unit"),
                rs.getString("default_sales_unit"), rs.getString("rounding_policy"),
                rs.getInt("version_no"), rs.getLong("effective_revision_id"),
                rs.getString("source_level"), rs.getLong("source_template_id"),
                rs.getLong("source_template_revision_id"), rs.getLong("source_override_revision_id"),
                rs.getString("content_hash"), rs.getInt("template_revision_no")
        ), args);
    }

    @Transactional
    public PackagingDtos.Profile saveProfile(Long productId, PackagingDtos.ProfileSave request) {
        ProductBase product = requireProduct(productId);
        ResolvedProfile desired = resolvedFromRequest(product, request);
        int currentVersion = lockCurrentProductVersion(productId);
        if (request.expectedVersionNo() != null && request.expectedVersionNo() != currentVersion) {
            throw new BusinessException("计量包装规则已被其他人更新，请关闭窗口后重新打开再修改");
        }
        if (!same(desired.baseUnit(), product.unit())) {
            ensureBaseUnitChangeAllowed(product, desired.baseUnit());
            jdbcTemplate.update("update product set unit = ? where id = ?", desired.baseUnit(), productId);
        }
        ResolvedProfile inherited = resolveInherited(product);
        OverridePatch patch = difference(inherited, desired);
        long overrideRevisionId = 0;
        String sourceLevel = inherited.templateRevisionId() > 0 ? "TEMPLATE" : "SYSTEM_DEFAULT";
        if (!patch.empty()) {
            overrideRevisionId = insertOverrideRevision(productId, inherited.templateRevisionId(), patch,
                    unitName(request.changeReason(), "商品特殊计量包装配置"));
            sourceLevel = "PRODUCT_OVERRIDE";
        } else {
            jdbcTemplate.update("update product_packaging_override set status='DISABLED', lock_version=lock_version+1 where product_id=?", productId);
        }
        publishEffective(product, desired, inherited.templateId(), inherited.templateRevisionId(),
                overrideRevisionId, sourceLevel, unitName(request.changeReason(), "商品计量包装配置变更"), currentVersion);
        if (request.packageDefinitions() != null) {
            savePackageDefinitions(productId, new PackagingDtos.PackageDefinitionsSave(
                    request.packageDefinitions(), unitName(request.changeReason(), "商品包装条码和保质期变更")));
        }
        return profile(productId);
    }

    private boolean hasProductBusiness(Long productId) {
        // Quantity/cost facts, external commitments, prices and complex product
        // revisions can all retain or later re-use the original base-unit
        // meaning. A base-unit change must never reinterpret any of them.
        List<ProductDependency> dependencies = List.of(
                new ProductDependency("purchase_order_item", "product_id"),
                new ProductDependency("sales_order_item", "product_id"),
                new ProductDependency("inbound_item", "product_id"),
                new ProductDependency("outbound_item", "product_id"),
                new ProductDependency("sales_return_item", "product_id"),
                new ProductDependency("inventory_batch", "product_id"),
                new ProductDependency("inventory_movement", "product_id"),
                new ProductDependency("inventory_package_balance", "product_id"),
                new ProductDependency("inventory_package_instance", "product_id"),
                new ProductDependency("inventory_package_instance_usage", "product_id"),
                new ProductDependency("inventory_package_movement", "product_id"),
                new ProductDependency("stock_balance", "product_id"),
                new ProductDependency("inventory_transfer_item", "product_id"),
                new ProductDependency("inventory_stocktake_item", "product_id"),
                new ProductDependency("purchase_return_item", "product_id"),
                new ProductDependency("inventory_cost_adjustment_item", "product_id"),
                new ProductDependency("platform_listing", "product_id"),
                new ProductDependency("crm_quote_item", "product_id"),
                new ProductDependency("price_rule_version", "product_id"),
                new ProductDependency("supplier_product_price", "product_id"),
                new ProductDependency("packaging_variant", "product_id"),
                new ProductDependency("packaging_variant_revision", "product_id"),
                new ProductDependency("supplier_product_offer", "product_id"),
                new ProductDependency("supplier_product_offer_revision", "product_id"),
                new ProductDependency("product_bom", "parent_product_id"),
                new ProductDependency("product_bom_revision", "parent_product_id"),
                new ProductDependency("product_bom_item", "component_product_id"),
                new ProductDependency("product_technical_spec_revision", "product_id")
        );
        for (ProductDependency dependency : dependencies) {
            Integer used = jdbcTemplate.queryForObject(
                    "select exists(select 1 from " + dependency.table()
                            + " where " + dependency.productColumn() + "=?)",
                    Integer.class, productId);
            if (used != null && used == 1) return true;
        }
        return false;
    }

    public List<PackagingDtos.TemplateRow> templates() {
        return jdbcTemplate.query("""
                select t.*,
                       (select count(*) from product_series_packaging_binding b where b.template_id=t.id and b.status='ENABLED') series_count,
                       (select count(*) from product p join product_series_packaging_binding b
                          on b.product_series_id=p.product_series_id and b.status='ENABLED' where b.template_id=t.id) product_count,
                       coalesce(r.base_unit,'') base_unit,
                       (select count(*) from packaging_template_rule pr where pr.template_revision_id=t.current_revision_id and pr.status='ENABLED') rule_count,
                       (select count(*) from product_packaging_override o join product p on p.id=o.product_id
                         join product_series_packaging_binding b on b.product_series_id=p.product_series_id
                         where b.template_id=t.id and o.status='ENABLED') override_product_count
                from packaging_template t
                left join packaging_template_revision r on r.id=t.current_revision_id
                order by t.name,t.id
                """, (rs, rowNum) -> new PackagingDtos.TemplateRow(
                rs.getLong("id"), rs.getString("code"), rs.getString("name"), rs.getString("description"),
                rs.getString("status"), zeroToNull(rs.getLong("current_revision_id")),
                rs.getInt("current_revision_no"), rs.getInt("lock_version"),
                rs.getInt("series_count"), rs.getInt("product_count"), rs.getString("base_unit"),
                rs.getInt("rule_count"), rs.getInt("override_product_count")
        ));
    }

    public PackagingDtos.TemplateDetail templateDetail(Long templateId) {
        PackagingDtos.TemplateRow row = template(templateId);
        TemplateResolved resolved = currentTemplate(templateId);
        ProductBase virtual = new ProductBase(0L, row.name(), resolved.profile().baseUnit(), 0L, "");
        return new PackagingDtos.TemplateDetail(row, transientProfile(virtual, resolved.profile(), true, 0));
    }

    public List<PackagingDtos.TemplateRevisionRow> templateHistory(Long templateId) {
        template(templateId);
        return jdbcTemplate.query("""
                select id,revision_no,revision_status,base_unit,content_hash,change_reason,
                       coalesce(published_at,created_at) published_at
                from packaging_template_revision where template_id=? order by revision_no desc
                """, (rs, rowNum) -> new PackagingDtos.TemplateRevisionRow(
                rs.getLong("id"), rs.getInt("revision_no"), rs.getString("revision_status"),
                rs.getString("base_unit"), rs.getString("content_hash"), rs.getString("change_reason"),
                rs.getTimestamp("published_at").toLocalDateTime()), templateId);
    }

    public PackagingDtos.Profile templateRevision(Long templateId, Long revisionId) {
        List<ResolvedProfile> rows = jdbcTemplate.query("""
                select * from packaging_template_revision where id=? and template_id=?
                """, (rs, rowNum) -> new ResolvedProfile(rs.getString("base_unit"), rs.getString("minimum_sales_unit"),
                rs.getString("minimum_outbound_unit"), rs.getString("default_purchase_unit"),
                rs.getString("default_sales_unit"), rs.getString("rounding_policy"), templateRules(revisionId),
                templateId, revisionId), revisionId, templateId);
        if (rows.isEmpty()) throw new BusinessException("包装模板历史版本不存在");
        ProductBase virtual = new ProductBase(0L, template(templateId).name(), rows.get(0).baseUnit(), 0L, "");
        return transientProfile(virtual, rows.get(0), true, 0);
    }

    @Transactional
    public PackagingDtos.TemplateRow createTemplate(PackagingDtos.TemplateSave request) {
        String templateCode = request.code();
        if (templateCode == null || !templateCode.matches("^[A-Za-z0-9_-]+$")) {
            throw new BusinessException("模板编码只能使用数字、英文字母、短横线和下划线");
        }
        String templateStatus = enabledStatus(request.status(), "包装模板状态");
        Integer duplicate = jdbcTemplate.queryForObject("select count(*) from packaging_template where code=? or name=?",
                Integer.class, templateCode, request.name().trim());
        if (duplicate != null && duplicate > 0) {
            throw new BusinessException("包装模板编码或名称已存在");
        }
        long id = insertId("""
                insert into packaging_template(code,name,description,status)
                values(?,?,?,?)
                """, templateCode, request.name().trim(), unitName(request.description(), ""),
                templateStatus);
        publishTemplate(id, 0, request.profile(), request.changeReason());
        return template(id);
    }

    @Transactional
    public PackagingDtos.TemplateRow updateTemplateMetadata(
            Long templateId, PackagingDtos.TemplateMetadataSave request) {
        PackagingDtos.TemplateRow before = template(templateId);
        TemplateHead head = lockTemplate(templateId);
        if (!Objects.equals(request.expectedLockVersion(), head.lockVersion())) {
            throw new BusinessException("包装模板基本信息已被其他人更新，请刷新后重试");
        }
        String name = request.name().trim();
        Integer duplicate = jdbcTemplate.queryForObject(
                "select count(*) from packaging_template where name=? and id<>?",
                Integer.class, name, templateId);
        if (duplicate != null && duplicate > 0) {
            throw new BusinessException("包装模板名称已存在");
        }
        int updated = jdbcTemplate.update("""
                update packaging_template
                set name=?,description=?,lock_version=lock_version+1,updated_at=now()
                where id=? and lock_version=?
                """, name, unitName(request.description(), ""), templateId, request.expectedLockVersion());
        if (updated != 1) {
            throw new BusinessException("包装模板基本信息已被其他人更新，请刷新后重试");
        }
        PackagingDtos.TemplateRow after = template(templateId);
        auditService.successChange("UPDATE_PACKAGING_TEMPLATE_METADATA", "PACKAGING_TEMPLATE",
                templateId, after.code(), "修改包装模板基本信息", before, after);
        return after;
    }

    @Transactional
    public PackagingDtos.TemplateRow updateTemplateStatus(
            Long templateId, PackagingDtos.TemplateStatusSave request) {
        PackagingDtos.TemplateRow before = template(templateId);
        TemplateHead head = lockTemplate(templateId);
        if (!Objects.equals(request.expectedLockVersion(), head.lockVersion())) {
            throw new BusinessException("包装模板状态已被其他人更新，请刷新后重试");
        }
        String status = enabledStatus(request.status(), "包装模板状态");
        if (Objects.equals(status, before.status())) {
            throw new BusinessException("包装模板已经是" + ("ENABLED".equals(status) ? "启用" : "停用") + "状态");
        }
        int updated = jdbcTemplate.update("""
                update packaging_template
                set status=?,lock_version=lock_version+1,updated_at=now()
                where id=? and lock_version=?
                """, status, templateId, request.expectedLockVersion());
        if (updated != 1) {
            throw new BusinessException("包装模板状态已被其他人更新，请刷新后重试");
        }
        PackagingDtos.TemplateRow after = template(templateId);
        auditService.successChange(
                "ENABLED".equals(status) ? "ENABLE_PACKAGING_TEMPLATE" : "DISABLE_PACKAGING_TEMPLATE",
                "PACKAGING_TEMPLATE", templateId, after.code(),
                ("ENABLED".equals(status) ? "启用" : "停用") + "包装模板；原因："
                        + request.changeReason().trim(),
                before, after);
        return after;
    }

    @Transactional
    public PackagingDtos.TemplateRow publishTemplate(Long templateId, PackagingDtos.TemplatePublish request) {
        publishTemplate(templateId, request.expectedRevisionNo(), request.profile(), request.changeReason());
        return template(templateId);
    }

    private void publishTemplate(Long templateId, int expectedRevisionNo, PackagingDtos.ProfileSave request, String reason) {
        TemplateHead head = lockTemplate(templateId);
        if (head.revisionNo() != expectedRevisionNo) {
            throw new BusinessException("包装模板已被其他人更新，请刷新后重试");
        }
        if (expectedRevisionNo > 0 && !"ENABLED".equals(head.status())) {
            throw new BusinessException("包装模板已停用，不能发布规则新版本；请先启用模板");
        }
        ResolvedProfile templateProfile = resolvedFromTemplateRequest(request);
        int revisionNo = head.revisionNo() + 1;
        String hash = profileHash(templateProfile);
        long revisionId = insertId("""
                insert into packaging_template_revision
                (template_id,revision_no,revision_status,base_unit_id,base_unit,
                 minimum_sales_unit_id,minimum_sales_unit,minimum_outbound_unit_id,minimum_outbound_unit,
                 default_purchase_unit_id,default_purchase_unit,default_sales_unit_id,default_sales_unit,
                 rounding_policy,content_hash,change_reason,published_at)
                values(?,?,'PUBLISHED',?,?,?,?,?,?,?,?,?,?,?,?,?,now())
                """, templateId, revisionNo, unitId(templateProfile.baseUnit()), templateProfile.baseUnit(),
                unitId(templateProfile.minimumSalesUnit()), templateProfile.minimumSalesUnit(),
                unitId(templateProfile.minimumOutboundUnit()), templateProfile.minimumOutboundUnit(),
                unitId(templateProfile.defaultPurchaseUnit()), templateProfile.defaultPurchaseUnit(),
                unitId(templateProfile.defaultSalesUnit()), templateProfile.defaultSalesUnit(),
                templateProfile.roundingPolicy(), hash, unitName(reason, "发布包装模板版本"));
        insertTemplateRules(revisionId, templateProfile.rules());

        List<ProductBase> products = productsForTemplate(templateId);
        for (ProductBase product : products) {
            ResolvedProfile inherited = templateProfile.withLineage(templateId, revisionId);
            ResolvedProfile effective = applyCurrentOverride(product.id(), inherited);
            ensureBaseUnitChangeAllowed(product, effective.baseUnit());
        }
        for (ProductBase product : products) {
            ResolvedProfile inherited = templateProfile.withLineage(templateId, revisionId);
            ResolvedProfile effective = applyCurrentOverride(product.id(), inherited);
            long overrideId = currentOverrideRevisionId(product.id());
            int current = lockCurrentProductVersion(product.id());
            publishEffective(product, effective, templateId, revisionId, overrideId,
                    overrideId > 0 ? "PRODUCT_OVERRIDE" : "TEMPLATE",
                    unitName(reason, "包装模板发布"), current);
        }
        jdbcTemplate.update("""
                update packaging_template set current_revision_id=?,current_revision_no=?,
                       lock_version=lock_version+1,updated_at=now() where id=?
                """, revisionId, revisionNo, templateId);
    }

    public List<PackagingDtos.SeriesBinding> seriesBindings() {
        return jdbcTemplate.query("""
                select s.id series_id,s.name series_name,coalesce(c.name,'') category_name,coalesce(b.template_id,0) template_id,
                       coalesce(t.name,'') template_name,coalesce(t.current_revision_id,0) revision_id,
                       coalesce(t.current_revision_no,0) revision_no,coalesce(b.lock_version,0) lock_version,
                       coalesce(b.status,'UNBOUND') status,coalesce(b.change_reason,'') change_reason,
                       (select count(*) from product p where p.product_series_id=s.id) product_count,
                       (select count(*) from product_packaging_override o join product p on p.id=o.product_id
                         where p.product_series_id=s.id and o.status='ENABLED') override_product_count
                from product_series s
                left join product_category c on c.id=s.category_id
                left join product_series_packaging_binding b on b.product_series_id=s.id
                left join packaging_template t on t.id=b.template_id
                order by s.name,s.id
                """, (rs, rowNum) -> new PackagingDtos.SeriesBinding(
                rs.getLong("series_id"), rs.getString("series_name"), rs.getString("category_name"), zeroToNull(rs.getLong("template_id")),
                rs.getString("template_name"), zeroToNull(rs.getLong("revision_id")), rs.getInt("revision_no"),
                rs.getInt("lock_version"), rs.getString("status"), rs.getInt("product_count"),
                rs.getInt("override_product_count"), rs.getString("change_reason"),
                "UNBOUND".equals(rs.getString("status")) ? List.of("尚未绑定包装模板") : List.of()
        ));
    }

    public PackagingDtos.SeriesBinding seriesBinding(Long seriesId) {
        return seriesBindings().stream().filter(row -> Objects.equals(row.productSeriesId(), seriesId)).findFirst()
                .orElseThrow(() -> new BusinessException("产品系列不存在"));
    }

    @Transactional
    public PackagingDtos.SeriesBinding saveSeriesBinding(Long seriesId, PackagingDtos.SeriesBindingSave request) {
        SeriesBindingHead current = lockSeriesBinding(seriesId);
        if (!Objects.equals(request.expectedLockVersion(), current.lockVersion())) {
            throw new BusinessException("系列包装模板绑定已被其他人更新，请刷新后重试");
        }
        TemplateHead templateHead = lockTemplate(request.templateId());
        if (!"ENABLED".equals(templateHead.status())) {
            throw new BusinessException("包装模板已停用，不能应用到产品系列；请先启用模板");
        }
        TemplateResolved template = currentTemplate(request.templateId());
        List<ProductBase> products = productsForSeries(seriesId);
        for (ProductBase product : products) {
            ResolvedProfile effective = applyCurrentOverride(product.id(), template.profile());
            ensureBaseUnitChangeAllowed(product, effective.baseUnit());
        }
        jdbcTemplate.update("""
                insert into product_series_packaging_binding(product_series_id,template_id,lock_version,status,change_reason)
                values(?,?,1,'ENABLED',?)
                on duplicate key update template_id=values(template_id),lock_version=lock_version+1,
                  status='ENABLED',change_reason=values(change_reason),updated_at=now()
                """, seriesId, request.templateId(), unitName(request.changeReason(), "系列绑定包装模板"));
        for (ProductBase product : products) {
            ResolvedProfile effective = applyCurrentOverride(product.id(), template.profile());
            long overrideId = currentOverrideRevisionId(product.id());
            int version = lockCurrentProductVersion(product.id());
            publishEffective(product, effective, request.templateId(), template.revisionId(), overrideId,
                    overrideId > 0 ? "PRODUCT_OVERRIDE" : "TEMPLATE",
                    unitName(request.changeReason(), "系列绑定包装模板"), version);
        }
        return seriesBinding(seriesId);
    }

    public PackagingDtos.Profile inheritedProfile(Long productId) {
        ProductBase product = requireProduct(productId);
        ResolvedProfile inherited = resolveInherited(product);
        return transientProfile(product, inherited, true, 0);
    }

    /**
     * Materializes an immutable effective packaging version for a newly-created product.
     * Product creation and packaging publication participate in the caller transaction, so
     * a missing/invalid series template rolls the entire product creation back.
     */
    @Transactional
    public PackagingDtos.Profile initializeProductProfile(Long productId, String changeReason) {
        ProductBase product = requireProduct(productId);
        int current = lockCurrentProductVersion(productId);
        if (current > 0) {
            return profile(productId);
        }
        ResolvedProfile inherited = resolveInherited(product);
        ResolvedProfile effective = applyCurrentOverride(productId, inherited);
        if (!same(product.unit(), effective.baseUnit())) {
            throw new BusinessException(
                    "主库存单位必须使用系列计量模板的基础单位“" + effective.baseUnit()
                            + "”，不能提交“" + product.unit() + "”后由系统静默改写");
        }
        ensureBaseUnitChangeAllowed(product, effective.baseUnit());
        long overrideId = currentOverrideRevisionId(productId);
        String sourceLevel = overrideId > 0
                ? "PRODUCT_OVERRIDE"
                : inherited.templateRevisionId() > 0 ? "TEMPLATE" : "SYSTEM_DEFAULT";
        publishEffective(product, effective, inherited.templateId(), inherited.templateRevisionId(),
                overrideId, sourceLevel, unitName(changeReason, "商品建档初始化计量包装版本"), current);
        return profile(productId);
    }

    /**
     * A pending-review product may be corrected to another series before it
     * becomes tradeable. Rebase its effective packaging profile in the same
     * transaction; never leave product.unit and the versioned base unit apart.
     */
    @Transactional
    public PackagingDtos.Profile rebaseProductProfileForSeriesChange(
            Long productId, String changeReason) {
        ProductBase product = requireProduct(productId);
        int current = lockCurrentProductVersion(productId);
        if (currentOverrideRevisionId(productId) > 0) {
            throw new BusinessException(
                    "商品已有独立包装特例，不能直接更换产品系列；请先停用旧商品并新建SKU");
        }
        ResolvedProfile inherited = resolveInherited(product);
        if (!same(product.unit(), inherited.baseUnit())) {
            throw new BusinessException(
                    "更换系列后的主库存单位必须使用新系列计量模板基础单位“"
                            + inherited.baseUnit() + "”");
        }
        ensureBaseUnitChangeAllowed(product, inherited.baseUnit());
        publishEffective(product, inherited, inherited.templateId(),
                inherited.templateRevisionId(), 0,
                inherited.templateRevisionId() > 0 ? "TEMPLATE" : "SYSTEM_DEFAULT",
                unitName(changeReason, "待复核商品更换系列，重发计量包装版本"),
                current);
        return profile(productId);
    }

    @Transactional
    public PackagingDtos.Profile restoreOverride(Long productId, Integer expectedVersionNo, String reason) {
        ProductBase product = requireProduct(productId);
        int current = lockCurrentProductVersion(productId);
        if (expectedVersionNo != null && expectedVersionNo != current) {
            throw new BusinessException("计量包装规则已被其他人更新，请刷新后重试");
        }
        jdbcTemplate.update("update product_packaging_override set status='DISABLED',lock_version=lock_version+1 where product_id=?", productId);
        ResolvedProfile inherited = resolveInherited(product);
        ensureBaseUnitChangeAllowed(product, inherited.baseUnit());
        publishEffective(product, inherited, inherited.templateId(), inherited.templateRevisionId(), 0,
                inherited.templateRevisionId() > 0 ? "TEMPLATE" : "SYSTEM_DEFAULT",
                unitName(reason, "恢复系列包装规则"), current);
        return profile(productId);
    }

    public List<PackagingDtos.HistoryRow> productHistory(Long productId) {
        requireProduct(productId);
        return jdbcTemplate.query("""
                select v.effective_revision_id,v.version_no,v.source_level,v.source_template_id,
                       v.source_template_revision_id,v.source_override_revision_id,v.content_hash,v.change_reason,
                       coalesce(v.published_at,v.created_at) published_at,coalesce(t.name,'') template_name,
                       coalesce(tr.revision_no,0) template_revision_no,v.base_unit,v.minimum_sales_unit,
                       v.minimum_outbound_unit,v.profile_snapshot_json
                from product_measure_policy_version v
                left join packaging_template t on t.id=v.source_template_id
                left join packaging_template_revision tr on tr.id=v.source_template_revision_id
                where v.product_id=? order by v.version_no desc
                """, (rs, rowNum) -> new PackagingDtos.HistoryRow(
                rs.getLong("effective_revision_id"), rs.getInt("version_no"), rs.getString("source_level"),
                zeroToNull(rs.getLong("source_template_id")), zeroToNull(rs.getLong("source_template_revision_id")),
                zeroToNull(rs.getLong("source_override_revision_id")), rs.getString("content_hash"),
                rs.getString("change_reason"), rs.getTimestamp("published_at").toLocalDateTime(),
                rs.getString("template_name"), rs.getInt("template_revision_no"), rs.getString("base_unit"),
                "库存:" + rs.getString("base_unit") + " / 最小销售:" + rs.getString("minimum_sales_unit")
                        + " / 最小发货:" + rs.getString("minimum_outbound_unit"),
                rs.getString("profile_snapshot_json")
        ), productId);
    }

    public List<PackagingDtos.ProductSummary> productSummaries() {
        List<PackagingDtos.ProductSummary> rows = jdbcTemplate.query("""
                select p.id product_id,m.version_no,m.source_level,coalesce(t.name,'') template_name,
                       coalesce(tr.revision_no,0) template_revision_no,
                       case when m.source_override_revision_id>0 then 1 else 0 end override_count,
                       m.content_hash,m.base_unit,m.minimum_sales_unit,m.minimum_outbound_unit
                from product p left join product_measure_policy m on m.product_id=p.id
                left join packaging_template t on t.id=m.source_template_id
                left join packaging_template_revision tr on tr.id=m.source_template_revision_id
                order by p.id
                """, (rs, rowNum) -> new PackagingDtos.ProductSummary(
                rs.getLong("product_id"), rs.getInt("version_no"), unitName(rs.getString("source_level"), "SYSTEM_DEFAULT"),
                rs.getString("template_name"), rs.getInt("template_revision_no"), rs.getInt("override_count"),
                rs.getString("content_hash"),
                "库存:" + rs.getString("base_unit") + " / 最小销售:" + rs.getString("minimum_sales_unit")
                        + " / 最小发货:" + rs.getString("minimum_outbound_unit"), List.of()
        ));
        return dataScopeService.filterMasterList("PRODUCT", rows, "productId");
    }

    public List<ProductPurchaseOptionsDto.UnitOption> unitOptions(Long productId, String requestedPurpose) {
        PackagingDtos.Profile profile = profile(productId);
        String purpose = purpose(requestedPurpose);
        List<ProductPurchaseOptionsDto.UnitOption> result = new ArrayList<>();
        result.add(new ProductPurchaseOptionsDto.UnitOption(profile.baseUnit(), zeroToNull(unitId(profile.baseUnit())),
                profile.baseUnit(), BigDecimal.ONE, true));
        Map<String, PackagingDtos.Rule> rules = activeRuleMap(profile.rules());
        for (PackagingDtos.Rule rule : profile.rules()) {
            if (!allowed(rule, purpose)) continue;
            BigDecimal factor = factorToBase(rule.parentUnit(), profile.baseUnit(), rules, new HashSet<>());
            result.add(new ProductPurchaseOptionsDto.UnitOption(rule.parentUnit(), zeroToNull(unitId(rule.parentUnit())),
                    rule.parentUnit(), factor, same(rule.parentUnit(), defaultUnitForPurpose(profile, purpose))));
        }
        return result.stream().collect(java.util.stream.Collectors.toMap(
                option -> key(option.name()), option -> option, (left, right) -> left, LinkedHashMap::new
        )).values().stream().toList();
    }

    private ResolvedProfile resolvedFromRequest(ProductBase product, PackagingDtos.ProfileSave request) {
        String baseUnit = unitName(request.baseUnit(), product.unit());
        return validatedResolved(baseUnit, request.minimumSalesUnit(), request.minimumOutboundUnit(),
                request.defaultPurchaseUnit(), request.defaultSalesUnit(), request.roundingPolicy(), request.rules(), 0, 0);
    }

    private ResolvedProfile resolvedFromTemplateRequest(PackagingDtos.ProfileSave request) {
        if (!StringUtils.hasText(request.baseUnit())) {
            throw new BusinessException("包装模板必须选择主库存单位");
        }
        return validatedResolved(request.baseUnit().trim(), request.minimumSalesUnit(), request.minimumOutboundUnit(),
                request.defaultPurchaseUnit(), request.defaultSalesUnit(), request.roundingPolicy(), request.rules(), 0, 0);
    }

    private ResolvedProfile validatedResolved(String baseUnit, String minimumSalesUnit, String minimumOutboundUnit,
                                               String defaultPurchaseUnit, String defaultSalesUnit, String rounding,
                                               List<PackagingDtos.RuleSave> inputRules,
                                               long templateId, long templateRevisionId) {
        String minimumSales = unitName(minimumSalesUnit, baseUnit);
        String minimumOutbound = unitName(minimumOutboundUnit, minimumSales);
        String defaultPurchase = unitName(defaultPurchaseUnit, baseUnit);
        String defaultSales = unitName(defaultSalesUnit, minimumSales);
        List<PackagingDtos.RuleSave> rules = normalizeRules(inputRules);
        validateRules(baseUnit, rules);
        validatePolicyUnits(baseUnit, minimumSales, minimumOutbound, defaultPurchase, defaultSales, rules);
        return new ResolvedProfile(baseUnit, minimumSales, minimumOutbound, defaultPurchase, defaultSales,
                roundingPolicy(rounding), rules, templateId, templateRevisionId);
    }

    private ResolvedProfile resolveInherited(ProductBase product) {
        if (product.seriesId() != null && product.seriesId() > 0) {
            List<TemplateResolved> templates = jdbcTemplate.query("""
                    select t.id template_id,t.current_revision_id revision_id
                    from product_series_packaging_binding b
                    join packaging_template t on t.id=b.template_id
                    where b.product_series_id=? and b.status='ENABLED' and t.current_revision_id>0
                    """, (rs, rowNum) -> currentTemplate(rs.getLong("template_id")), product.seriesId());
            if (!templates.isEmpty()) return templates.get(0).profile();
        }
        return new ResolvedProfile(product.unit(), product.unit(), product.unit(), product.unit(), product.unit(),
                "REJECT", List.of(), 0, 0);
    }

    private TemplateResolved currentTemplate(Long templateId) {
        List<TemplateResolved> rows = jdbcTemplate.query("""
                select t.id template_id,t.current_revision_id revision_id,r.base_unit,r.minimum_sales_unit,
                       r.minimum_outbound_unit,r.default_purchase_unit,r.default_sales_unit,r.rounding_policy
                from packaging_template t join packaging_template_revision r on r.id=t.current_revision_id
                where t.id=? and r.revision_status='PUBLISHED'
                """, (rs, rowNum) -> {
            long revisionId = rs.getLong("revision_id");
            List<PackagingDtos.RuleSave> rules = templateRules(revisionId);
            return new TemplateResolved(rs.getLong("template_id"), revisionId,
                    new ResolvedProfile(rs.getString("base_unit"), rs.getString("minimum_sales_unit"),
                            rs.getString("minimum_outbound_unit"), rs.getString("default_purchase_unit"),
                            rs.getString("default_sales_unit"), rs.getString("rounding_policy"), rules,
                            rs.getLong("template_id"), revisionId));
        }, templateId);
        if (rows.isEmpty()) throw new BusinessException("包装模板不存在或没有已发布版本");
        return rows.get(0);
    }

    private List<PackagingDtos.RuleSave> templateRules(long revisionId) {
        return jdbcTemplate.query("""
                select * from packaging_template_rule where template_revision_id=? order by sort_no,id
                """, (rs, rowNum) -> new PackagingDtos.RuleSave(
                rs.getString("parent_unit"), rs.getString("child_unit"), rs.getString("conversion_mode"),
                rs.getBigDecimal("fixed_child_quantity"), rs.getBigDecimal("reference_base_quantity"),
                rs.getInt("breakable") == 1, rs.getInt("repackable") == 1,
                rs.getInt("purchase_allowed") == 1, rs.getInt("sales_allowed") == 1,
                rs.getInt("outbound_allowed") == 1, rs.getInt("integer_only") == 1,
                rs.getInt("sort_no"), rs.getString("status"), rs.getString("rule_key")
        ), revisionId);
    }

    private ResolvedProfile applyCurrentOverride(Long productId, ResolvedProfile inherited) {
        long revisionId = currentOverrideRevisionId(productId);
        if (revisionId == 0) return inherited;
        List<OverrideJson> rows = jdbcTemplate.query("""
                select policy_patch_json,rule_patch_json from product_packaging_override_revision
                where id=? and status='PUBLISHED'
                """, (rs, rowNum) -> new OverrideJson(rs.getString(1), rs.getString(2)), revisionId);
        if (rows.isEmpty()) return inherited;
        try {
            JsonNode policy = objectMapper.readTree(rows.get(0).policyJson());
            String base = textOr(policy, "baseUnit", inherited.baseUnit());
            String minSales = textOr(policy, "minimumSalesUnit", inherited.minimumSalesUnit());
            String minOutbound = textOr(policy, "minimumOutboundUnit", inherited.minimumOutboundUnit());
            String defaultPurchase = textOr(policy, "defaultPurchaseUnit", inherited.defaultPurchaseUnit());
            String defaultSales = textOr(policy, "defaultSalesUnit", inherited.defaultSalesUnit());
            String rounding = textOr(policy, "roundingPolicy", inherited.roundingPolicy());
            Map<String, PackagingDtos.RuleSave> rules = new LinkedHashMap<>();
            for (PackagingDtos.RuleSave rule : inherited.rules()) rules.put(stableRuleKey(rule), rule);
            for (JsonNode patch : objectMapper.readTree(rows.get(0).ruleJson())) {
                String ruleKey = patch.path("ruleKey").asText();
                if ("DISABLE".equals(patch.path("action").asText())) {
                    rules.remove(key(ruleKey));
                } else {
                    JsonNode r = patch.path("rule");
                    PackagingDtos.RuleSave rule = ruleFromJson(r);
                    rules.put(stableRuleKey(rule), rule);
                }
            }
            return validatedResolved(base, minSales, minOutbound, defaultPurchase, defaultSales, rounding,
                    new ArrayList<>(rules.values()), inherited.templateId(), inherited.templateRevisionId());
        } catch (JsonProcessingException ex) {
            throw new BusinessException("商品包装特例数据无法解析");
        }
    }

    private OverridePatch difference(ResolvedProfile inherited, ResolvedProfile desired) {
        Map<String, Object> policy = new LinkedHashMap<>();
        putDifference(policy, "baseUnit", inherited.baseUnit(), desired.baseUnit());
        putDifference(policy, "minimumSalesUnit", inherited.minimumSalesUnit(), desired.minimumSalesUnit());
        putDifference(policy, "minimumOutboundUnit", inherited.minimumOutboundUnit(), desired.minimumOutboundUnit());
        putDifference(policy, "defaultPurchaseUnit", inherited.defaultPurchaseUnit(), desired.defaultPurchaseUnit());
        putDifference(policy, "defaultSalesUnit", inherited.defaultSalesUnit(), desired.defaultSalesUnit());
        putDifference(policy, "roundingPolicy", inherited.roundingPolicy(), desired.roundingPolicy());
        Map<String, PackagingDtos.RuleSave> inheritedRules = ruleMap(inherited.rules());
        Map<String, PackagingDtos.RuleSave> desiredRules = ruleMap(desired.rules());
        List<RulePatch> rules = new ArrayList<>();
        for (Map.Entry<String, PackagingDtos.RuleSave> entry : desiredRules.entrySet()) {
            if (!Objects.equals(ruleSignature(entry.getValue()), ruleSignature(inheritedRules.get(entry.getKey())))) {
                rules.add(new RulePatch(entry.getKey(), "UPSERT", entry.getValue()));
            }
        }
        for (String inheritedKey : inheritedRules.keySet()) {
            if (!desiredRules.containsKey(inheritedKey)) rules.add(new RulePatch(inheritedKey, "DISABLE", null));
        }
        return new OverridePatch(policy, rules);
    }

    private long insertOverrideRevision(Long productId, long templateRevisionId, OverridePatch patch, String reason) {
        int revisionNo = jdbcTemplate.query("""
                select current_revision_no from product_packaging_override where product_id=? for update
                """, rs -> rs.next() ? rs.getInt(1) + 1 : 1, productId);
        String policyJson = toJson(patch.policy());
        String ruleJson = toJson(patch.rules());
        long id = insertId("""
                insert into product_packaging_override_revision
                (product_id,revision_no,based_on_template_revision_id,policy_patch_json,rule_patch_json,
                 content_hash,change_reason,status,published_at)
                values(?,?,?,?,?,?,?,'PUBLISHED',now())
                """, productId, revisionNo, templateRevisionId, policyJson, ruleJson,
                sha256(policyJson + "|" + ruleJson), reason);
        jdbcTemplate.update("""
                insert into product_packaging_override(product_id,current_revision_id,current_revision_no,lock_version,status)
                values(?,?,?,1,'ENABLED')
                on duplicate key update current_revision_id=values(current_revision_id),
                  current_revision_no=values(current_revision_no),lock_version=lock_version+1,status='ENABLED',updated_at=now()
                """, productId, id, revisionNo);
        return id;
    }

    private void publishEffective(ProductBase product, ResolvedProfile effective, long templateId,
                                  long templateRevisionId, long overrideRevisionId, String sourceLevel,
                                  String reason, int currentVersion) {
        ensureBaseUnitChangeAllowed(product, effective.baseUnit());
        int versionNo = currentVersion + 1;
        long effectiveRevisionId = product.id() * 1_000_000L + versionNo;
        String hash = profileHash(effective);
        String snapshot = effectiveProfileJson(product, effective, effectiveRevisionId, versionNo,
                sourceLevel, templateId, templateRevisionId, overrideRevisionId, hash);
        Set<String> overriddenRuleKeys = overriddenRuleKeys(overrideRevisionId);
        jdbcTemplate.update("""
                insert into product_measure_policy_version
                (product_id,version_no,effective_revision_id,base_unit,minimum_sales_unit,minimum_outbound_unit,
                 default_purchase_unit,default_sales_unit,rounding_policy,source_level,source_template_id,
                 source_template_revision_id,source_override_revision_id,product_series_id_snapshot,content_hash,
                 profile_snapshot_json,change_reason,published_at)
                values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,now())
                """, product.id(), versionNo, effectiveRevisionId, effective.baseUnit(), effective.minimumSalesUnit(),
                effective.minimumOutboundUnit(), effective.defaultPurchaseUnit(), effective.defaultSalesUnit(),
                effective.roundingPolicy(), sourceLevel, templateId, templateRevisionId, overrideRevisionId,
                product.seriesId() == null ? 0 : product.seriesId(), hash, snapshot, reason);
        int sort = 10;
        for (PackagingDtos.RuleSave rule : effective.rules()) {
            String status = enabledStatus(rule.status(), "包装规则状态");
            String ruleKey = stableRuleKey(rule);
            boolean overridden = overriddenRuleKeys.contains(ruleKey);
            jdbcTemplate.update("""
                    insert into product_package_rule
                    (product_id,version_no,effective_revision_id,rule_key,parent_unit,parent_unit_id,
                     child_unit,child_unit_id,conversion_mode,fixed_child_quantity,reference_base_quantity,
                     breakable,repackable,purchase_allowed,sales_allowed,outbound_allowed,integer_only,
                     sort_no,status,source_level,overridden)
                    values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, product.id(), versionNo, effectiveRevisionId, ruleKey, rule.parentUnit(), unitId(rule.parentUnit()),
                    rule.childUnit(), unitId(rule.childUnit()), conversionMode(rule.conversionMode()),
                    positiveOrNull(rule.fixedChildQuantity()), positiveOrNull(rule.referenceBaseQuantity()),
                    bool(rule.breakable()), bool(rule.repackable()), bool(rule.purchaseAllowed()),
                    bool(rule.salesAllowed()), bool(rule.outboundAllowed()), bool(rule.integerOnly() == null || rule.integerOnly()),
                    rule.sortNo() == null ? sort : rule.sortNo(), status,
                    overridden ? "PRODUCT_OVERRIDE" : sourceLevel, bool(overridden));
            sort += 10;
        }
        jdbcTemplate.update("""
                insert into product_measure_policy
                (product_id,base_unit,minimum_sales_unit,minimum_outbound_unit,default_purchase_unit,default_sales_unit,
                 rounding_policy,version_no,effective_revision_id,source_level,source_template_id,
                 source_template_revision_id,source_override_revision_id,content_hash,lock_version)
                values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,1)
                on duplicate key update base_unit=values(base_unit),minimum_sales_unit=values(minimum_sales_unit),
                  minimum_outbound_unit=values(minimum_outbound_unit),default_purchase_unit=values(default_purchase_unit),
                  default_sales_unit=values(default_sales_unit),rounding_policy=values(rounding_policy),
                  version_no=values(version_no),effective_revision_id=values(effective_revision_id),
                  source_level=values(source_level),source_template_id=values(source_template_id),
                  source_template_revision_id=values(source_template_revision_id),
                  source_override_revision_id=values(source_override_revision_id),content_hash=values(content_hash),
                  lock_version=lock_version+1,updated_at=now()
                """, product.id(), effective.baseUnit(), effective.minimumSalesUnit(), effective.minimumOutboundUnit(),
                effective.defaultPurchaseUnit(), effective.defaultSalesUnit(), effective.roundingPolicy(), versionNo,
                effectiveRevisionId, sourceLevel, templateId, templateRevisionId, overrideRevisionId, hash);
        if (!same(product.unit(), effective.baseUnit())) {
            jdbcTemplate.update("update product set unit=? where id=?", effective.baseUnit(), product.id());
        }
    }

    private PackagingDtos.Profile transientProfile(ProductBase product, ResolvedProfile resolved,
                                                    boolean inherited, long overrideRevisionId) {
        List<PackagingDtos.Rule> rules = new ArrayList<>();
        long id = -1;
        String inheritedSource = resolved.templateRevisionId() > 0 ? "TEMPLATE" : "SYSTEM_DEFAULT";
        for (PackagingDtos.RuleSave r : resolved.rules()) {
            rules.add(new PackagingDtos.Rule(id--, product.id(), 0, r.parentUnit(), r.childUnit(),
                    conversionMode(r.conversionMode()), r.fixedChildQuantity(), r.referenceBaseQuantity(),
                    r.breakable(), r.repackable(), r.purchaseAllowed(), r.salesAllowed(), r.outboundAllowed(),
                    r.integerOnly(), r.sortNo(), enabledStatus(r.status(), "包装规则状态"), stableRuleKey(r),
                    overrideRevisionId > 0 ? "PRODUCT_OVERRIDE" : inheritedSource, overrideRevisionId > 0));
        }
        return new PackagingDtos.Profile(product.id(), product.name(), resolved.baseUnit(), resolved.minimumSalesUnit(),
                resolved.minimumOutboundUnit(), resolved.defaultPurchaseUnit(), resolved.defaultSalesUnit(),
                resolved.roundingPolicy(), 0, rules,
                resolved.templateRevisionId() > 0 ? "TEMPLATE" : "SYSTEM_DEFAULT", inherited,
                zeroToNull(resolved.templateId()), templateName(resolved.templateId()),
                zeroToNull(resolved.templateRevisionId()), templateRevisionNo(resolved.templateRevisionId()),
                zeroToNull(product.seriesId()), product.seriesName(), zeroToNull(overrideRevisionId), null,
                profileHash(resolved), List.of(), List.of());
    }

    private PackagingDtos.Profile legacyBatchProfile(BatchPackagingVersion batch) {
        ProductBase product = requireProduct(batch.productId());
        String base = product.unit();
        try {
            JsonNode root = objectMapper.readTree(batch.snapshotJson());
            base = textOr(root, "baseUnit", base);
        } catch (JsonProcessingException ignored) {
            // Explicit legacy quantity-only snapshot remains usable by its stored product unit.
        }
        ResolvedProfile legacy = new ResolvedProfile(base, base, base, base, base, "REJECT", List.of(), 0, 0);
        PackagingDtos.Profile profile = transientProfile(product, legacy, false, 0);
        return new PackagingDtos.Profile(profile.productId(), profile.productName(), profile.baseUnit(),
                profile.minimumSalesUnit(), profile.minimumOutboundUnit(), profile.defaultPurchaseUnit(),
                profile.defaultSalesUnit(), profile.roundingPolicy(), batch.versionNo(), profile.rules(),
                "LEGACY", false, null, "", null, 0, zeroToNull(product.seriesId()), product.seriesName(),
                null, zeroToNull(batch.effectiveRevisionId()), sha256(batch.snapshotJson()), List.of(),
                List.of("历史批次仅按库存数量口径管理，不推算原包装形态"));
    }

    private void ensureBaseUnitChangeAllowed(ProductBase product, String desiredBaseUnit) {
        String currentBaseUnit = jdbcTemplate.query("""
                select base_unit from product_measure_policy where product_id=?
                """, rs -> rs.next() ? rs.getString(1) : product.unit(), product.id());
        if (!same(currentBaseUnit, desiredBaseUnit) && hasProductBusiness(product.id())) {
            throw new BusinessException(
                    "商品“" + product.name()
                            + "”已有业务或主数据依赖，主库存单位不能改变；"
                            + "请停用旧商品并新建SKU");
        }
    }

    private int lockCurrentProductVersion(Long productId) {
        return jdbcTemplate.query("select version_no from product_measure_policy where product_id=? for update",
                rs -> rs.next() ? rs.getInt(1) : 0, productId);
    }

    private long currentOverrideRevisionId(Long productId) {
        return jdbcTemplate.query("""
                select current_revision_id from product_packaging_override
                where product_id=? and status='ENABLED'
                """, rs -> rs.next() ? rs.getLong(1) : 0L, productId);
    }

    private List<ProductBase> productsForTemplate(Long templateId) {
        return jdbcTemplate.query("""
                select p.id,p.name,p.unit,p.product_series_id,s.name series_name
                from product p join product_series_packaging_binding b on b.product_series_id=p.product_series_id
                left join product_series s on s.id=p.product_series_id
                where b.template_id=? and b.status='ENABLED' order by p.id
                """, productBaseMapper(), templateId);
    }

    private List<ProductBase> productsForSeries(Long seriesId) {
        return jdbcTemplate.query("""
                select p.id,p.name,p.unit,p.product_series_id,s.name series_name
                from product p left join product_series s on s.id=p.product_series_id
                where p.product_series_id=? order by p.id
                """, productBaseMapper(), seriesId);
    }

    private org.springframework.jdbc.core.RowMapper<ProductBase> productBaseMapper() {
        return (rs, rowNum) -> new ProductBase(rs.getLong("id"), rs.getString("name"), rs.getString("unit"),
                rs.getLong("product_series_id"), rs.getString("series_name"));
    }

    private TemplateHead lockTemplate(Long templateId) {
        List<TemplateHead> rows = jdbcTemplate.query("""
                select id,current_revision_no,lock_version,status from packaging_template where id=? for update
                """, (rs, rowNum) -> new TemplateHead(
                rs.getLong(1), rs.getInt(2), rs.getInt(3), rs.getString(4)), templateId);
        if (rows.isEmpty()) throw new BusinessException("包装模板不存在");
        return rows.get(0);
    }

    private SeriesBindingHead lockSeriesBinding(Long seriesId) {
        Integer series = jdbcTemplate.queryForObject("select count(*) from product_series where id=?", Integer.class, seriesId);
        if (series == null || series == 0) throw new BusinessException("产品系列不存在");
        return jdbcTemplate.query("""
                select lock_version from product_series_packaging_binding where product_series_id=? for update
                """, rs -> rs.next() ? new SeriesBindingHead(rs.getInt(1)) : new SeriesBindingHead(0), seriesId);
    }

    private PackagingDtos.TemplateRow template(Long templateId) {
        return templates().stream().filter(row -> Objects.equals(row.id(), templateId)).findFirst()
                .orElseThrow(() -> new BusinessException("包装模板不存在"));
    }

    private void insertTemplateRules(long revisionId, List<PackagingDtos.RuleSave> rules) {
        int sort = 10;
        for (PackagingDtos.RuleSave rule : rules) {
            jdbcTemplate.update("""
                    insert into packaging_template_rule
                    (template_revision_id,rule_key,parent_unit_id,parent_unit,child_unit_id,child_unit,
                     conversion_mode,fixed_child_quantity,reference_base_quantity,breakable,repackable,
                     purchase_allowed,sales_allowed,outbound_allowed,integer_only,sort_no,status)
                    values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, revisionId, stableRuleKey(rule), unitId(rule.parentUnit()), rule.parentUnit(),
                    unitId(rule.childUnit()), rule.childUnit(), conversionMode(rule.conversionMode()),
                    positiveOrNull(rule.fixedChildQuantity()), positiveOrNull(rule.referenceBaseQuantity()),
                    bool(rule.breakable()), bool(rule.repackable()), bool(rule.purchaseAllowed()),
                    bool(rule.salesAllowed()), bool(rule.outboundAllowed()), bool(rule.integerOnly() == null || rule.integerOnly()),
                    rule.sortNo() == null ? sort : rule.sortNo(),
                    enabledStatus(rule.status(), "包装规则状态"));
            sort += 10;
        }
    }

    public PackagingDtos.Plan plan(Long productId, PackagingDtos.PlanRequest request) {
        PackagingDtos.Profile profile = profile(productId);
        verifyExpectedRevision(profile, request);
        PackagingVariantRule variant = request.packagingVariantRevisionId() == null
                ? null : requirePackagingVariant(productId, request.packagingVariantRevisionId());
        return verifyExpectedSnapshot(
                plan(variant == null ? profile : profileWithVariant(profile, variant), request, variant),
                request);
    }

    public PackagingDtos.Plan planFromSnapshot(String snapshotJson, PackagingDtos.PlanRequest request) {
        PackagingDtos.Profile snapshotProfile = snapshotProfile(snapshotJson, 0L, "来源订单快照",
                "来源订单缺少计量包装快照，不能按当前配置替代历史规则",
                "来源订单计量包装快照不完整，请先修复历史数据",
                "来源订单计量包装快照无法解析",
                "按来源订单不可变快照履约");
        verifyExpectedRevision(snapshotProfile, request);
        return verifyExpectedSnapshot(plan(snapshotProfile, request), request);
    }

    PackagingDtos.Profile immutableBatchSnapshotProfile(String snapshotJson, Long productId, String productName) {
        return snapshotProfile(snapshotJson, productId, productName,
                "库存批次缺少计量包装快照，请先进行数据修复",
                "库存批次计量包装快照不完整，请先进行数据修复",
                "库存批次计量包装快照无法解析，请先进行数据修复",
                "按库存批次不可变快照履约");
    }

    private PackagingDtos.Profile snapshotProfile(String snapshotJson, Long productId, String productName,
                                                   String missingMessage, String incompleteMessage,
                                                   String parseMessage, String warning) {
        if (!StringUtils.hasText(snapshotJson) || "{}".equals(snapshotJson.trim())) {
            throw new BusinessException(missingMessage);
        }
        try {
            JsonNode root = objectMapper.readTree(snapshotJson);
            JsonNode policy = root.path("policy");
            if (!policy.isObject() || !StringUtils.hasText(policy.path("baseUnit").asText())) {
                throw new BusinessException(incompleteMessage);
            }
            List<PackagingDtos.Rule> rules = new ArrayList<>();
            for (JsonNode node : root.path("rules")) {
                rules.add(new PackagingDtos.Rule(node.path("id").asLong(0), node.path("productId").asLong(productId),
                        node.path("versionNo").asInt(root.path("versionNo").asInt(0)),
                        node.path("parentUnit").asText(), node.path("childUnit").asText(),
                        node.path("conversionMode").asText("FIXED"), decimalOrNull(node.get("fixedChildQuantity")),
                        decimalOrNull(node.get("referenceBaseQuantity")), node.path("breakable").asBoolean(false),
                        node.path("repackable").asBoolean(false), node.path("purchaseAllowed").asBoolean(true),
                        node.path("salesAllowed").asBoolean(true), node.path("outboundAllowed").asBoolean(true),
                        node.path("integerOnly").asBoolean(true), node.path("sortNo").asInt(0),
                        node.path("status").asText("ENABLED"), node.path("ruleKey").asText(key(node.path("parentUnit").asText())),
                        node.path("sourceLevel").asText(root.path("source").path("sourceLevel").asText("LEGACY")),
                        node.path("overridden").asBoolean(false)));
            }
            JsonNode source = root.path("source");
            return new PackagingDtos.Profile(
                    productId, productName, policy.path("baseUnit").asText(),
                    policy.path("minimumSalesUnit").asText(policy.path("baseUnit").asText()),
                    policy.path("minimumOutboundUnit").asText(policy.path("baseUnit").asText()),
                    policy.path("defaultPurchaseUnit").asText(policy.path("baseUnit").asText()),
                    policy.path("defaultSalesUnit").asText(policy.path("baseUnit").asText()),
                    policy.path("roundingPolicy").asText("REJECT"), root.path("versionNo").asInt(0), rules,
                    source.path("sourceLevel").asText("LEGACY"), false,
                    zeroToNull(source.path("templateId").asLong(0)), "",
                    zeroToNull(source.path("templateRevisionId").asLong(0)), 0,
                    zeroToNull(source.path("seriesId").asLong(0)), "",
                    zeroToNull(source.path("overrideRevisionId").asLong(0)),
                    zeroToNull(root.path("effectiveRevisionId").asLong(0)), root.path("contentHash").asText(""),
                    List.of(), List.of(warning));
        } catch (JsonProcessingException ex) {
            throw new BusinessException(parseMessage);
        }
    }

    public PackagingDtos.Plan planForBatch(Long batchId, PackagingDtos.PlanRequest request) {
        PackagingDtos.Profile profile = batchProfile(batchId);
        verifyExpectedRevision(profile, request);
        return verifyExpectedSnapshot(planAgainstBatchBalance(batchId, profile, plan(profile, request)), request);
    }

    public PackagingDtos.Plan planNonTradeForBatch(Long batchId, PackagingDtos.PlanRequest request,
                                                   List<Long> packageInstanceIds,
                                                   List<InventoryDtos.NonTradePackageAllocation> packageAllocations) {
        PackagingDtos.Profile profile = batchProfile(batchId);
        verifyExpectedRevision(profile, request);
        PackagingDtos.Plan idealPlan = plan(profile, request);
        String trackingMode = jdbcTemplate.query("select packaging_tracking_mode from inventory_batch where id=?",
                rs -> rs.next() ? rs.getString(1) : "QUANTITY_ONLY", batchId);
        if ("ACTUAL_INSTANCE".equals(trackingMode)) {
            List<InventoryDtos.NonTradePackageAllocation> allocations =
                    normalizedNonTradeAllocations(packageAllocations);
            validatePartialActualPackages(idealPlan,
                    lockInventoryPackages(batchId, allocations.stream()
                            .map(InventoryDtos.NonTradePackageAllocation::packageInstanceId).toList()),
                    allocations);
            return verifyExpectedSnapshot(idealPlan, request);
        }
        return verifyExpectedSnapshot(planAgainstBatchBalance(batchId, profile, idealPlan), request);
    }

    private void verifyExpectedRevision(PackagingDtos.Profile profile, PackagingDtos.PlanRequest request) {
        if (request.expectedEffectiveRevisionId() != null && request.expectedEffectiveRevisionId() > 0
                && !Objects.equals(request.expectedEffectiveRevisionId(), profile.effectiveRevisionId())) {
            throw new BusinessException("计量包装规则已更新，请重新计算后再提交");
        }
    }

    private PackagingDtos.Plan verifyExpectedSnapshot(PackagingDtos.Plan plan, PackagingDtos.PlanRequest request) {
        if (StringUtils.hasText(request.expectedSnapshotHash())
                && !request.expectedSnapshotHash().trim().equalsIgnoreCase(plan.snapshotHash())) {
            throw new BusinessException("计量包装计算结果已变化，请重新计算后再提交");
        }
        return plan;
    }

    private PackagingDtos.Plan plan(PackagingDtos.Profile profile, PackagingDtos.PlanRequest request) {
        return plan(profile, request, null);
    }

    private PackagingDtos.Plan plan(
            PackagingDtos.Profile profile, PackagingDtos.PlanRequest request,
            PackagingVariantRule packagingVariant) {
        BigDecimal requestedQuantity = request.quantity();
        if (requestedQuantity == null || requestedQuantity.compareTo(ZERO) <= 0) {
            throw new BusinessException("数量必须大于0");
        }
        String requestedUnit = unitName(request.quantityUnit(), defaultUnitForPurpose(profile, request.purpose()));
        String purpose = purpose(request.purpose());
        Map<String, PackagingDtos.Rule> ruleByParent = activeRuleMap(profile.rules());
        PackagingDtos.Rule requestedRule = ruleByParent.get(key(requestedUnit));
        if (requestedRule != null && Boolean.TRUE.equals(requestedRule.integerOnly())
                && requestedQuantity.stripTrailingZeros().scale() > 0) {
            throw new BusinessException("包装单位“" + requestedUnit + "”必须填写整数");
        }
        BigDecimal requestedFactor = factorToBase(requestedUnit, profile.baseUnit(), ruleByParent, new HashSet<>());
        boolean actualWeightRequired = requestedFactor == null;
        boolean estimated = false;
        BigDecimal baseQuantity;
        if (requestedFactor != null) {
            baseQuantity = requestedQuantity.multiply(requestedFactor);
        } else {
            PackagingDtos.Rule rule = ruleByParent.get(key(requestedUnit));
            if (rule == null || !"ACTUAL_WEIGHT".equals(rule.conversionMode())) {
                throw new BusinessException("单位“" + requestedUnit + "”无法换算为库存单位“" + profile.baseUnit() + "”");
            }
            boolean orderEstimate = "PURCHASE".equals(purpose) || "SALES".equals(purpose);
            if (orderEstimate) {
                BigDecimal reference = positiveOrNull(rule.referenceBaseQuantity());
                if (request.actualBaseQuantity() != null && request.actualBaseQuantity().compareTo(ZERO) > 0) {
                    baseQuantity = request.actualBaseQuantity();
                } else if (reference != null) {
                    baseQuantity = requestedQuantity.multiply(reference);
                } else {
                    throw new BusinessException("“" + requestedUnit + "”为实重包装，订单必须填写预计"
                            + profile.baseUnit() + "数量或维护参考重量");
                }
                estimated = true;
            } else if (request.actualBaseQuantity() == null || request.actualBaseQuantity().compareTo(ZERO) <= 0) {
                throw new BusinessException("“" + requestedUnit + "”为实重包装，入库或出库必须填写实际"
                        + profile.baseUnit() + "数量");
            } else {
                baseQuantity = request.actualBaseQuantity();
            }
        }

        String minimumUnit = switch (purpose) {
            case "SALES" -> profile.minimumSalesUnit();
            case "OUTBOUND" -> profile.minimumOutboundUnit();
            default -> profile.baseUnit();
        };
        RoundingResult rounded = applyMinimum(baseQuantity, minimumUnit, profile.baseUnit(), ruleByParent, profile.roundingPolicy());
        baseQuantity = rounded.baseQuantity();

        String priceUnit = unitName(request.priceUnit(), profile.baseUnit());
        BigDecimal pricingQuantity = pricingQuantity(baseQuantity, requestedQuantity, requestedUnit, priceUnit,
                profile.baseUnit(), ruleByParent, rounded.rounded());

        List<PackagingDtos.Component> components;
        if (actualWeightRequired) {
            components = List.of(new PackagingDtos.Component(requestedUnit, requestedQuantity, baseQuantity,
                    estimated ? "ESTIMATED_ACTUAL_WEIGHT" : "ACTUAL_WEIGHT"));
        } else {
            components = decompose(baseQuantity, profile, purpose, ruleByParent, minimumUnit);
        }
        String suggestion = suggestion(components, baseQuantity, profile.baseUnit(), actualWeightRequired);
        if (estimated) suggestion += "（参考重量估算，入库/出库必须录实重）";
        String snapshot = snapshotJson(profile, requestedQuantity, requestedUnit, baseQuantity,
                pricingQuantity, priceUnit, components, estimated, request);
        return new PackagingDtos.Plan(
                requestedQuantity, requestedUnit, strip(baseQuantity), profile.baseUnit(), strip(pricingQuantity), priceUnit,
                actualWeightRequired, rounded.rounded(), profile.roundingPolicy(), suggestion, components,
                snapshot, estimated, profile.effectiveRevisionId(), sha256(snapshot),
                estimated ? "ESTIMATED" : "ACTUAL"
        );
    }

    private PackagingVariantRule requirePackagingVariant(Long productId, Long revisionId) {
        List<PackagingVariantRule> rows = jdbcTemplate.query("""
                select r.id,r.packaging_variant_id,r.product_id,r.revision_no,r.package_unit,
                       r.conversion_mode,r.fixed_base_quantity,r.reference_base_quantity,
                       r.content_hash
                from packaging_variant_revision r
                join packaging_variant v on v.id=r.packaging_variant_id
                where r.id=? and r.product_id=? and r.status='PUBLISHED' and v.status='ENABLED'
                """, (rs, rowNum) -> new PackagingVariantRule(
                rs.getLong("id"), rs.getLong("packaging_variant_id"),
                rs.getLong("product_id"), rs.getInt("revision_no"),
                rs.getString("package_unit"), rs.getString("conversion_mode"),
                rs.getBigDecimal("fixed_base_quantity"),
                rs.getBigDecimal("reference_base_quantity"), rs.getString("content_hash")
        ), revisionId, productId);
        if (rows.isEmpty()) {
            throw new BusinessException("供应商包装方案版本不存在、已停用或不属于当前商品");
        }
        return rows.get(0);
    }

    private PackagingDtos.Profile profileWithVariant(
            PackagingDtos.Profile profile, PackagingVariantRule variant) {
        List<PackagingDtos.Rule> rules = new ArrayList<>();
        boolean replaced = false;
        for (PackagingDtos.Rule rule : profile.rules()) {
            if (!same(rule.parentUnit(), variant.packageUnit())) {
                rules.add(rule);
                continue;
            }
            rules.add(new PackagingDtos.Rule(
                    rule.id(), rule.productId(), rule.versionNo(), variant.packageUnit(),
                    profile.baseUnit(), variant.conversionMode(), variant.fixedBaseQuantity(),
                    variant.referenceBaseQuantity(), false, false, true,
                    rule.salesAllowed(), rule.outboundAllowed(), true, rule.sortNo(), "ENABLED",
                    "PACKAGING_VARIANT_REVISION_" + variant.revisionId(),
                    "SUPPLIER_VARIANT", true
            ));
            replaced = true;
        }
        if (!replaced) {
            rules.add(new PackagingDtos.Rule(
                    -variant.revisionId(), profile.productId(), profile.versionNo(),
                    variant.packageUnit(), profile.baseUnit(), variant.conversionMode(),
                    variant.fixedBaseQuantity(), variant.referenceBaseQuantity(),
                    false, false, true, false, false, true, 1, "ENABLED",
                    "PACKAGING_VARIANT_REVISION_" + variant.revisionId(),
                    "SUPPLIER_VARIANT", true
            ));
        }
        return new PackagingDtos.Profile(
                profile.productId(), profile.productName(), profile.baseUnit(),
                profile.minimumSalesUnit(), profile.minimumOutboundUnit(),
                variant.packageUnit(), profile.defaultSalesUnit(), profile.roundingPolicy(),
                profile.versionNo(), List.copyOf(rules), "SUPPLIER_VARIANT", false,
                profile.templateId(), profile.templateName(), profile.templateRevisionId(),
                profile.templateRevisionNo(), profile.productSeriesId(), profile.productSeriesName(),
                profile.overrideRevisionId(), profile.effectiveRevisionId(),
                sha256(profile.contentHash() + "|" + variant.contentHash()),
                profile.overrideFields(),
                concat(profile.warnings(), List.of(
                        "本次采购采用供应商包装方案第" + variant.revisionNo() + "版"))
        );
    }

    public void validatePackagingQuantity(Long productId, BigDecimal baseQuantity,
                                          BigDecimal packageQuantity, String packageUnit) {
        validatePackagingQuantity(profile(productId), baseQuantity, packageQuantity, packageUnit);
    }

    public void validatePackagingQuantity(PackagingDtos.Plan plan,
                                          BigDecimal packageQuantity, String packageUnit) {
        PackagingDtos.Profile frozenProfile = snapshotProfile(
                plan.snapshotJson(), 0L, "业务单据",
                "业务单据缺少计量包装快照", "业务单据计量包装快照不完整",
                "业务单据计量包装快照无法解析", "按业务单据不可变快照校验包装数量");
        validatePackagingQuantity(frozenProfile, plan.baseQuantity(), packageQuantity, packageUnit);
    }

    public void validateBatchPackagingQuantity(Long batchId, BigDecimal baseQuantity,
                                               BigDecimal packageQuantity, String packageUnit) {
        validatePackagingQuantity(batchProfile(batchId), baseQuantity, packageQuantity, packageUnit);
    }

    private void validatePackagingQuantity(PackagingDtos.Profile profile, BigDecimal baseQuantity,
                                           BigDecimal packageQuantity, String packageUnit) {
        if (!StringUtils.hasText(packageUnit) || packageQuantity == null || packageQuantity.compareTo(ZERO) <= 0) {
            return;
        }
        Map<String, PackagingDtos.Rule> rules = activeRuleMap(profile.rules());
        PackagingDtos.Rule rule = rules.get(key(packageUnit));
        if (rule == null) {
            throw new BusinessException("包装单位“" + packageUnit + "”未配置到当前商品");
        }
        if (Boolean.TRUE.equals(rule.integerOnly()) && packageQuantity.stripTrailingZeros().scale() > 0) {
            throw new BusinessException("包装单位“" + packageUnit + "”必须填写整数");
        }
        BigDecimal factor = factorToBase(packageUnit, profile.baseUnit(), rules, new HashSet<>());
        if (factor != null) {
            BigDecimal expected = packageQuantity.multiply(factor);
            if (Boolean.TRUE.equals(rule.breakable()) && expected.compareTo(baseQuantity) > 0) {
                throw new BusinessException(packageQuantity.stripTrailingZeros().toPlainString() + packageUnit
                        + "包含" + expected.stripTrailingZeros().toPlainString() + profile.baseUnit()
                        + "，超过填写数量" + baseQuantity.stripTrailingZeros().toPlainString() + profile.baseUnit());
            }
            if (!Boolean.TRUE.equals(rule.breakable()) && expected.compareTo(baseQuantity) != 0) {
                throw new BusinessException(packageQuantity.stripTrailingZeros().toPlainString() + packageUnit
                        + "应换算为" + expected.stripTrailingZeros().toPlainString() + profile.baseUnit()
                        + "，与填写数量" + baseQuantity.stripTrailingZeros().toPlainString() + profile.baseUnit() + "不一致");
            }
        }
    }

    public void validateInboundPackageInstances(PackagingDtos.Plan plan,
                                                BigDecimal packageQuantity,
                                                String packageUnit,
                                                List<PackagingDtos.PackageInstanceInput> instances) {
        validateInboundPackageInstances(
                plan.snapshotJson(), plan.baseQuantity(), plan.baseUnit(),
                packageQuantity, packageUnit, instances);
    }

    public void validateInboundPackageInstances(String snapshotJson,
                                                BigDecimal baseQuantity,
                                                String baseUnit,
                                                BigDecimal packageQuantity,
                                                String packageUnit,
                                                List<PackagingDtos.PackageInstanceInput> instances) {
        List<PackagingDtos.PackageInstanceInput> safeInstances = instances == null ? List.of() : instances;
        boolean actualWeightPackage = snapshotHasActualWeightRule(snapshotJson, packageUnit);
        if (!actualWeightPackage) {
            if (!safeInstances.isEmpty()) {
                throw new BusinessException("只有实重包装才能录入逐件重量");
            }
            return;
        }
        if (safeInstances.isEmpty()) {
            throw new BusinessException("实重包装必须逐件录入包装码和实际" + baseUnit + "数量");
        }
        if (packageQuantity == null || packageQuantity.compareTo(ZERO) <= 0
                || packageQuantity.stripTrailingZeros().scale() > 0
                || packageQuantity.compareTo(BigDecimal.valueOf(safeInstances.size())) != 0) {
            throw new BusinessException("包装数必须为正整数，并与逐件实重记录条数一致");
        }
        Set<String> codes = new HashSet<>();
        BigDecimal actualTotal = ZERO;
        for (PackagingDtos.PackageInstanceInput instance : safeInstances) {
            if (instance.actualBaseQuantity() == null || instance.actualBaseQuantity().compareTo(ZERO) <= 0) {
                throw new BusinessException("每件实重必须大于0");
            }
            if (StringUtils.hasText(instance.packageCode()) && !codes.add(key(instance.packageCode()))) {
                throw new BusinessException("实重包装码不能重复：" + instance.packageCode());
            }
            actualTotal = actualTotal.add(instance.actualBaseQuantity());
        }
        if (actualTotal.compareTo(baseQuantity) != 0) {
            throw new BusinessException("逐件实重合计" + strip(actualTotal).toPlainString() + baseUnit
                    + "，与实际库存数量" + strip(baseQuantity).toPlainString() + baseUnit + "不一致");
        }
    }

    public void validateInboundPackageInstancesDraft(PackagingDtos.Plan plan,
                                                     BigDecimal packageQuantity,
                                                     String packageUnit,
                                                     List<PackagingDtos.PackageInstanceInput> instances) {
        List<PackagingDtos.PackageInstanceInput> safeInstances = instances == null ? List.of() : instances;
        boolean actualWeightPackage = snapshotHasActualWeightRule(plan.snapshotJson(), packageUnit);
        if (!actualWeightPackage) {
            if (!safeInstances.isEmpty()) {
                throw new BusinessException("只有实重包装才能录入逐件重量");
            }
            return;
        }
        if (packageQuantity != null && packageQuantity.compareTo(ZERO) > 0) {
            if (packageQuantity.stripTrailingZeros().scale() > 0) {
                throw new BusinessException("实重包装数必须为整数");
            }
            if (BigDecimal.valueOf(safeInstances.size()).compareTo(packageQuantity) > 0) {
                throw new BusinessException("逐件实重记录不能超过包装数");
            }
        }
        Set<String> codes = new HashSet<>();
        for (PackagingDtos.PackageInstanceInput instance : safeInstances) {
            if (instance.actualBaseQuantity() != null && instance.actualBaseQuantity().compareTo(ZERO) < 0) {
                throw new BusinessException("逐件实重不能为负数");
            }
            if (StringUtils.hasText(instance.packageCode()) && !codes.add(key(instance.packageCode()))) {
                throw new BusinessException("实重包装码不能重复：" + instance.packageCode());
            }
        }
    }

    public List<PackagingDtos.PackageInstance> inventoryPackageInstances(Long batchId) {
        return jdbcTemplate.query("""
                select id, batch_id, product_id, package_code, unit_name,
                       actual_base_quantity, remaining_base_quantity, base_unit, status
                from inventory_package_instance
                where batch_id = ? and status = 'AVAILABLE'
                order by id
                """, (rs, rowNum) -> new PackagingDtos.PackageInstance(
                rs.getLong("id"), rs.getLong("batch_id"), rs.getLong("product_id"),
                rs.getString("package_code"), rs.getString("unit_name"),
                rs.getBigDecimal("actual_base_quantity"), rs.getBigDecimal("remaining_base_quantity"),
                rs.getString("base_unit"), rs.getString("status")
        ), batchId);
    }

    public void initializeInventoryPackaging(Long batchId, Long productId, String snapshotJson,
                                             BigDecimal packageQuantity, String packageUnit,
                                             boolean hasActualPackageInstances, String refType, Long refId) {
        if (!StringUtils.hasText(snapshotJson) || "{}".equals(snapshotJson.trim())) {
            return;
        }
        try {
            JsonNode root = objectMapper.readTree(snapshotJson);
            int versionNo = root.path("versionNo").asInt(0);
            long effectiveRevisionId = root.path("effectiveRevisionId").asLong(0);
            String snapshotHash = sha256(snapshotJson);
            String trackingMode = hasActualPackageInstances
                    || root.path("calculation").path("actualWeightRequired").asBoolean(false)
                    ? "ACTUAL_INSTANCE" : "STRUCTURED";
            jdbcTemplate.update("""
                    update inventory_batch set packaging_version_no=?,packaging_effective_revision_id=?,
                      packaging_profile_snapshot_json=?,packaging_snapshot_hash=?,packaging_tracking_mode=? where id=?
                    """, versionNo, effectiveRevisionId, snapshotJson, snapshotHash, trackingMode, batchId);

            boolean initializedFromFixedPackage = false;
            if (packageQuantity != null && packageQuantity.compareTo(ZERO) > 0 && StringUtils.hasText(packageUnit)) {
                PackagingDtos.Profile frozenProfile = snapshotProfile(
                        snapshotJson, productId, "库存批次",
                        "入库包装快照缺失", "入库包装快照不完整", "入库包装快照无法解析",
                        "按入库不可变快照建立包装库存");
                Map<String, PackagingDtos.Rule> rules = activeRuleMap(frozenProfile.rules());
                BigDecimal factor = factorToBase(packageUnit, frozenProfile.baseUnit(), rules, new HashSet<>());
                if (factor != null) {
                    BigDecimal coveredBase = packageQuantity.multiply(factor);
                    BigDecimal baseQuantity = root.path("calculation").path("baseQuantity").decimalValue();
                    addBalance(batchId, productId, packageUnit, packageQuantity);
                    insertPackageMovement(batchId, productId, "INBOUND", "", ZERO,
                            packageUnit, packageQuantity, refType, refId, "入库形成固定包装库存");
                    BigDecimal looseBase = baseQuantity.subtract(coveredBase);
                    if (looseBase.compareTo(ZERO) > 0) {
                        addBalance(batchId, productId, frozenProfile.baseUnit(), looseBase);
                        insertPackageMovement(batchId, productId, "INBOUND", "", ZERO,
                                frozenProfile.baseUnit(), looseBase, refType, refId, "入库形成拆包散件库存");
                    }
                    initializedFromFixedPackage = true;
                }
            }
            if (!initializedFromFixedPackage) {
                for (JsonNode component : root.path("components")) {
                    String unit = component.path("unit").asText("");
                    BigDecimal quantity = component.path("quantity").decimalValue();
                    if (!StringUtils.hasText(unit) || quantity.compareTo(ZERO) <= 0) {
                        continue;
                    }
                    addBalance(batchId, productId, unit, quantity);
                    insertPackageMovement(batchId, productId, "INBOUND", "", ZERO, unit, quantity,
                            refType, refId, "入库形成包装库存");
                }
            }
        } catch (JsonProcessingException | ArithmeticException ex) {
            throw new BusinessException("入库包装快照无法解析");
        }
    }

    public void reverseInboundPackaging(Long batchId, Long productId, Long inboundItemId) {
        Integer unavailableInstances = jdbcTemplate.queryForObject("""
                select count(*) from inventory_package_instance
                where batch_id=? and (status<>'AVAILABLE' or remaining_base_quantity<>actual_base_quantity)
                """, Integer.class, batchId);
        if (unavailableInstances != null && unavailableInstances > 0) {
            throw new BusinessException("入库包装实物已被出库、拆包、调拨或退货使用，不能冲正入库");
        }
        List<PackageBalanceRow> balances = jdbcTemplate.query("""
                select unit_name,quantity from inventory_package_balance
                where batch_id=? and quantity<>0 for update
                """, (rs, rowNum) -> new PackageBalanceRow(rs.getString(1), rs.getBigDecimal(2)), batchId);
        for (PackageBalanceRow balance : balances) {
            addBalance(batchId, productId, balance.unit(), balance.quantity().negate());
            insertPackageMovement(batchId, productId, "INBOUND_REVERSE", balance.unit(), balance.quantity(),
                    "", ZERO, "INBOUND_ITEM", inboundItemId, "入库冲正撤销包装库存");
        }
        jdbcTemplate.update("""
                update inventory_package_instance set status='REVERSED'
                where batch_id=? and status='AVAILABLE'
                """, batchId);
    }

    public void consumeInventoryPackaging(Long batchId, Long productId, PackagingDtos.Plan plan,
                                          BigDecimal packageQuantity, String packageUnit,
                                          List<Long> packageInstanceIds, String refType, Long refId) {
        String trackingMode = jdbcTemplate.query("select packaging_tracking_mode from inventory_batch where id=?",
                rs -> rs.next() ? rs.getString(1) : "QUANTITY_ONLY", batchId);
        Integer balanceRows = jdbcTemplate.queryForObject(
                "select count(*) from inventory_package_balance where batch_id = ? and quantity > 0", Integer.class, batchId);
        List<Long> selectedIds = packageInstanceIds == null
                ? List.of()
                : packageInstanceIds.stream().filter(id -> id != null && id > 0).distinct().toList();
        boolean actualInstanceTracking = "ACTUAL_INSTANCE".equals(trackingMode)
                || Boolean.TRUE.equals(plan.actualWeightRequired());
        if (("QUANTITY_ONLY".equals(trackingMode) || balanceRows == null || balanceRows == 0) && selectedIds.isEmpty()) {
            if (actualInstanceTracking) {
                throw new BusinessException("该批次没有可选的逐件实重包装");
            }
            return; // 历史批次没有包装形态余额，继续按原库存数量口径出库。
        }

        int versionNo = jdbcTemplate.query("""
                select packaging_version_no from inventory_batch where id = ? for update
                """, rs -> rs.next() ? rs.getInt(1) : 0, batchId);
        Map<String, PackagingDtos.Rule> rules = activeRuleMap(rulesForVersion(productId, versionNo));
        List<InventoryPackageRow> selected = lockInventoryPackages(batchId, selectedIds);

        if (actualInstanceTracking) {
            validateWholeActualPackages(plan, selected);
            if (packageQuantity == null
                    || packageQuantity.compareTo(BigDecimal.valueOf(selected.size())) != 0
                    || !same(packageUnit, selected.get(0).unitName())) {
                throw new BusinessException("包装数、包装单位必须与所选具体实重包装一致");
            }
            for (InventoryPackageRow item : selected) {
                jdbcTemplate.update("""
                        update inventory_package_instance
                        set status = 'OUTBOUND', outbound_item_id = ?, changed_at = now()
                        where id = ? and status = 'AVAILABLE'
                        """, refId, item.id());
            }
        } else if (!selected.isEmpty()) {
            throw new BusinessException("逐件实重包装必须按原包装整包出库，不能拆成散重；固定数量包装由系统按已发布的可拆规则自动换算");
        }

        for (PackagingDtos.Component component : plan.components()) {
            BigDecimal required = component.quantity();
            ensureBalance(batchId, productId, component.unit(), required, rules,
                    refType, refId, new HashSet<>());
            addBalance(batchId, productId, component.unit(), required.negate());
            insertPackageMovement(batchId, productId, "OUTBOUND", component.unit(), required,
                    "", ZERO, refType, refId, "按拣货方案出库");
        }
    }

    public void restoreInventoryPackaging(Long batchId, Long productId, Long outboundItemId) {
        List<Map<String, Object>> movements = jdbcTemplate.queryForList("""
                select movement_type,from_unit,from_quantity,to_unit,to_quantity
                from inventory_package_movement
                where ref_type='OUTBOUND_ITEM' and ref_id=?
                order by id desc
                """, outboundItemId);
        for (Map<String, Object> movement : movements) {
            String type = String.valueOf(movement.get("movement_type"));
            String fromUnit = String.valueOf(movement.get("from_unit"));
            String toUnit = String.valueOf(movement.get("to_unit"));
            BigDecimal fromQuantity = decimal(movement.get("from_quantity"));
            BigDecimal toQuantity = decimal(movement.get("to_quantity"));
            if ("OUTBOUND".equals(type) && StringUtils.hasText(fromUnit)) {
                addBalance(batchId, productId, fromUnit, fromQuantity);
            } else if ("BREAK".equals(type)) {
                if (StringUtils.hasText(toUnit) && toQuantity.compareTo(ZERO) > 0) {
                    addBalance(batchId, productId, toUnit, toQuantity.negate());
                }
                if (StringUtils.hasText(fromUnit) && fromQuantity.compareTo(ZERO) > 0) {
                    addBalance(batchId, productId, fromUnit, fromQuantity);
                }
            }
        }
        jdbcTemplate.update("""
                update inventory_package_instance
                set status='AVAILABLE',outbound_item_id=0,changed_at=now()
                where outbound_item_id=? and status in ('OUTBOUND','BROKEN')
                """, outboundItemId);
        insertPackageMovement(batchId, productId, "REVERSAL", "", ZERO, "", ZERO,
                "OUTBOUND_REVERSAL", outboundItemId, "反向出库恢复包装库存");
    }

    public void receiveReturnedBaseQuantity(Long batchId, Long productId, BigDecimal quantity,
                                            String baseUnit, Long salesReturnId) {
        String trackingMode = jdbcTemplate.query("select packaging_tracking_mode from inventory_batch where id=?",
                rs -> rs.next() ? rs.getString(1) : "QUANTITY_ONLY", batchId);
        if ("QUANTITY_ONLY".equals(trackingMode)) return;
        addBalance(batchId, productId, baseUnit, quantity);
        insertPackageMovement(batchId, productId, "RETURN", "", ZERO, baseUnit, quantity,
                "SALES_RETURN", salesReturnId, "销售退货按散装基础单位入库");
    }

    public void reverseReturnedBaseQuantity(Long batchId, Long productId, BigDecimal quantity,
                                            String baseUnit, Long salesReturnId) {
        String trackingMode = jdbcTemplate.query("select packaging_tracking_mode from inventory_batch where id=?",
                rs -> rs.next() ? rs.getString(1) : "QUANTITY_ONLY", batchId);
        if ("QUANTITY_ONLY".equals(trackingMode)) return;
        addBalance(batchId, productId, baseUnit, quantity.negate());
        insertPackageMovement(batchId, productId, "RETURN_REVERSAL", baseUnit, quantity, "", ZERO,
                "SALES_RETURN_REVERSAL", salesReturnId, "销售退货冲正扣回库存");
    }

    public void dispatchTransferPackaging(Long batchId, Long productId, PackagingDtos.Plan plan,
                                          List<Long> packageInstanceIds, Long transferItemId) {
        consumeOperationPackaging(batchId, productId, plan, packageInstanceIds,
                "TRANSFER_ITEM", transferItemId, "IN_TRANSIT", "调拨发出");
    }

    public void receiveTransferPackaging(Long sourceBatchId, Long targetBatchId, Long productId,
                                         PackagingDtos.Plan plan, List<Long> packageInstanceIds,
                                         Long transferItemId) {
        addPlanBalances(targetBatchId, productId, plan, "TRANSFER_IN", transferItemId, "调拨入库");
        List<Long> ids = normalizedIds(packageInstanceIds);
        if (!ids.isEmpty()) {
            int updated = jdbcTemplate.update("""
                    update inventory_package_instance
                    set batch_id=?,status='AVAILABLE',operation_ref_type='',operation_ref_id=0,changed_at=now()
                    where batch_id=? and operation_ref_type='TRANSFER_ITEM' and operation_ref_id=?
                      and status='IN_TRANSIT' and id in (%s)
                    """.formatted(placeholders(ids.size())), joinArgs(targetBatchId, sourceBatchId, transferItemId, ids));
            if (updated != ids.size()) throw new BusinessException("调拨包装实物状态已变化，请刷新后重试");
        }
    }

    public void reverseTransferPackaging(Long sourceBatchId, Long targetBatchId, Long productId,
                                         PackagingDtos.Plan plan, List<Long> packageInstanceIds,
                                         Long transferItemId, boolean completed) {
        List<Long> ids = normalizedIds(packageInstanceIds);
        if (completed) {
            subtractPlanBalances(targetBatchId, productId, plan, "TRANSFER_REVERSAL", transferItemId, "调拨冲正扣回目标仓包装");
            addPlanBalances(sourceBatchId, productId, plan, "TRANSFER_REVERSAL", transferItemId, "调拨冲正恢复来源仓包装");
            if (!ids.isEmpty()) {
                int updated = jdbcTemplate.update("""
                        update inventory_package_instance
                        set batch_id=?,status='AVAILABLE',operation_ref_type='',operation_ref_id=0,changed_at=now()
                        where batch_id=? and status='AVAILABLE' and id in (%s)
                        """.formatted(placeholders(ids.size())), joinArgs(sourceBatchId, targetBatchId, ids));
                if (updated != ids.size()) throw new BusinessException("目标仓包装实物已被使用，不能冲正调拨");
            }
        } else {
            restoreOperationPackaging(sourceBatchId, productId, "TRANSFER_ITEM", transferItemId,
                    packageInstanceIds, "调拨在途冲正");
        }
    }

    public void consumePurchaseReturnPackaging(Long batchId, Long productId, PackagingDtos.Plan plan,
                                               List<Long> packageInstanceIds, Long returnItemId) {
        consumeOperationPackaging(batchId, productId, plan, packageInstanceIds,
                "PURCHASE_RETURN_ITEM", returnItemId, "PURCHASE_RETURNED", "采购退货出库");
    }

    public void restorePurchaseReturnPackaging(Long batchId, Long productId,
                                               List<Long> packageInstanceIds, Long returnItemId) {
        restoreOperationPackaging(batchId, productId, "PURCHASE_RETURN_ITEM", returnItemId,
                packageInstanceIds, "采购退货冲正");
    }

    public void consumeNonTradePackaging(Long batchId, Long productId, PackagingDtos.Plan plan,
                                         List<Long> packageInstanceIds,
                                         List<InventoryDtos.NonTradePackageAllocation> packageAllocations,
                                         Long documentId) {
        String trackingMode = jdbcTemplate.query("select packaging_tracking_mode from inventory_batch where id=?",
                rs -> rs.next() ? rs.getString(1) : "QUANTITY_ONLY", batchId);
        if ("ACTUAL_INSTANCE".equals(trackingMode)) {
            consumePartialActualPackages(batchId, productId, plan, packageAllocations, documentId);
            return;
        }
        consumeOperationPackaging(batchId, productId, plan, packageInstanceIds,
                "INVENTORY_NON_TRADE", documentId, "NON_TRADE_OUT", "其他出库");
    }

    public void restoreNonTradePackaging(Long batchId, Long productId,
                                         List<Long> packageInstanceIds, Long documentId) {
        Integer usages = jdbcTemplate.queryForObject("""
                select count(*) from inventory_package_instance_usage
                where ref_type='INVENTORY_NON_TRADE' and ref_id=? and status='CONFIRMED'
                  and batch_id=? and product_id=?
                """, Integer.class, documentId, batchId, productId);
        if (usages != null && usages > 0) {
            restorePartialActualPackages(batchId, productId, documentId);
            return;
        }
        restoreOperationPackaging(batchId, productId, "INVENTORY_NON_TRADE", documentId,
                packageInstanceIds, "其他出库冲正");
    }

    public void receiveNonTradeBaseQuantity(Long batchId, Long productId, BigDecimal quantity,
                                            String baseUnit, Long documentId) {
        String trackingMode = jdbcTemplate.query("select packaging_tracking_mode from inventory_batch where id=?",
                rs -> rs.next() ? rs.getString(1) : "QUANTITY_ONLY", batchId);
        if ("QUANTITY_ONLY".equals(trackingMode)) return;
        addBalance(batchId, productId, baseUnit, quantity);
        insertPackageMovement(batchId, productId, "NON_TRADE_IN", "", ZERO, baseUnit, quantity,
                "INVENTORY_NON_TRADE", documentId, "其他入库按基础单位增加包装余额");
    }

    public void reverseNonTradeBaseQuantity(Long batchId, Long productId, BigDecimal quantity,
                                            String baseUnit, Long documentId) {
        String trackingMode = jdbcTemplate.query("select packaging_tracking_mode from inventory_batch where id=?",
                rs -> rs.next() ? rs.getString(1) : "QUANTITY_ONLY", batchId);
        if ("QUANTITY_ONLY".equals(trackingMode)) return;
        addBalance(batchId, productId, baseUnit, quantity.negate());
        insertPackageMovement(batchId, productId, "NON_TRADE_IN_REVERSAL", baseUnit, quantity, "", ZERO,
                "INVENTORY_NON_TRADE_REVERSAL", documentId, "其他入库冲正扣回包装余额");
    }

    private void consumePartialActualPackages(Long batchId, Long productId, PackagingDtos.Plan plan,
                                              List<InventoryDtos.NonTradePackageAllocation> packageAllocations,
                                              Long documentId) {
        List<InventoryDtos.NonTradePackageAllocation> allocations =
                normalizedNonTradeAllocations(packageAllocations);
        List<InventoryPackageRow> selected = lockInventoryPackages(batchId, allocations.stream()
                .map(InventoryDtos.NonTradePackageAllocation::packageInstanceId).toList());
        validatePartialActualPackages(plan, selected, allocations);
        Map<Long, InventoryPackageRow> packagesById = new LinkedHashMap<>();
        for (InventoryPackageRow item : selected) packagesById.put(item.id(), item);
        BigDecimal baseBalance = balanceForUpdate(batchId, plan.baseUnit());
        if (baseBalance.compareTo(ZERO) > 0) {
            if (baseBalance.compareTo(plan.baseQuantity()) < 0) {
                throw new BusinessException("批次包装重量余额不足，请先校正包装账后再办理");
            }
            addBalance(batchId, productId, plan.baseUnit(), plan.baseQuantity().negate());
            insertPackageMovement(batchId, productId, "NONTRADE_BASE",
                    plan.baseUnit(), plan.baseQuantity(), "", ZERO,
                    "INVENTORY_NON_TRADE", documentId, "其他出库扣减实重包装重量余额");
        }
        for (InventoryDtos.NonTradePackageAllocation allocation : allocations) {
            InventoryPackageRow item = packagesById.get(allocation.packageInstanceId());
            BigDecimal before = item.remainingBaseQuantity();
            BigDecimal consumed = allocation.quantity();
            BigDecimal after = before.subtract(consumed);
            boolean depleted = after.compareTo(ZERO) == 0;
            int updated = jdbcTemplate.update("""
                    update inventory_package_instance
                    set remaining_base_quantity=?,status=?,operation_ref_type=?,operation_ref_id=?,changed_at=now()
                    where id=? and batch_id=? and status='AVAILABLE' and remaining_base_quantity=?
                    """, after, depleted ? "NON_TRADE_OUT" : "AVAILABLE",
                    depleted ? "INVENTORY_NON_TRADE" : "", depleted ? documentId : 0L,
                    item.id(), batchId, before);
            if (updated != 1) {
                throw new BusinessException("实重包装“" + item.packageCode() + "”的剩余重量已变化，请刷新后重试");
            }
            jdbcTemplate.update("""
                    insert into inventory_package_instance_usage
                    (package_instance_id,batch_id,product_id,ref_type,ref_id,movement_type,
                     base_quantity,base_unit,quantity_before,quantity_after,status)
                    values(?,?,?,'INVENTORY_NON_TRADE',?,'PARTIAL_ACTUAL_OUT',?,?,?,?,'CONFIRMED')
                    """, item.id(), batchId, productId, documentId,
                    consumed, plan.baseUnit(), before, after);
            if (depleted) {
                BigDecimal packageBalance = balanceForUpdate(batchId, item.unitName());
                if (packageBalance.compareTo(ZERO) > 0) {
                    if (packageBalance.compareTo(ONE) < 0) {
                        throw new BusinessException("批次包装个数余额异常，请先校正包装账后再办理");
                    }
                    addBalance(batchId, productId, item.unitName(), ONE.negate());
                    insertPackageMovement(batchId, productId, "NONTRADE_FULL",
                            item.unitName(), ONE, "", ZERO, "INVENTORY_NON_TRADE", documentId,
                            "其他出库用尽实重包装“" + item.packageCode() + "”");
                }
            }
        }
    }

    private void restorePartialActualPackages(Long batchId, Long productId, Long documentId) {
        List<ActualPackageUsageRow> usages = jdbcTemplate.query("""
                select u.id,u.package_instance_id,u.base_quantity,u.base_unit,
                       u.quantity_before,u.quantity_after,i.package_code,i.unit_name,
                       i.remaining_base_quantity,i.status,i.operation_ref_type,i.operation_ref_id
                from inventory_package_instance_usage u
                join inventory_package_instance i on i.id=u.package_instance_id
                where u.ref_type='INVENTORY_NON_TRADE' and u.ref_id=? and u.status='CONFIRMED'
                  and u.batch_id=? and u.product_id=?
                  and i.batch_id=? and i.product_id=?
                order by u.id desc
                for update
                """, (rs, rowNum) -> new ActualPackageUsageRow(
                rs.getLong("id"), rs.getLong("package_instance_id"),
                rs.getBigDecimal("base_quantity"), rs.getString("base_unit"),
                rs.getBigDecimal("quantity_before"), rs.getBigDecimal("quantity_after"),
                rs.getString("package_code"), rs.getString("unit_name"),
                rs.getBigDecimal("remaining_base_quantity"), rs.getString("status"),
                rs.getString("operation_ref_type"), rs.getLong("operation_ref_id")
        ), documentId, batchId, productId, batchId, productId);
        if (usages.isEmpty()) {
            throw new BusinessException("未找到可冲正的实重包装扣取记录");
        }
        List<PackageBalanceRow> consumedBalances = jdbcTemplate.query("""
                select from_unit,from_quantity
                from inventory_package_movement
                where ref_type='INVENTORY_NON_TRADE' and ref_id=?
                  and batch_id=? and product_id=?
                  and movement_type in ('NONTRADE_BASE','NONTRADE_FULL')
                order by id desc
                """, (rs, rowNum) -> new PackageBalanceRow(
                rs.getString("from_unit"), rs.getBigDecimal("from_quantity")
        ), documentId, batchId, productId);
        for (ActualPackageUsageRow usage : usages) {
            Integer laterUsages = jdbcTemplate.queryForObject("""
                    select count(*) from inventory_package_instance_usage
                    where package_instance_id=? and status='CONFIRMED' and id>?
                    """, Integer.class, usage.packageInstanceId(), usage.id());
            boolean depletedByThisDocument = usage.quantityAfter().compareTo(ZERO) == 0;
            boolean currentStateMatches = usage.remainingBaseQuantity().compareTo(usage.quantityAfter()) == 0
                    && (depletedByThisDocument
                    ? "NON_TRADE_OUT".equals(usage.instanceStatus())
                        && "INVENTORY_NON_TRADE".equals(usage.operationRefType())
                        && Objects.equals(documentId, usage.operationRefId())
                    : "AVAILABLE".equals(usage.instanceStatus()));
            if ((laterUsages != null && laterUsages > 0) || !currentStateMatches) {
                throw new BusinessException("实重包装“" + usage.packageCode()
                        + "”在本次扣取后已有后续业务，不能直接冲正；请先冲正后续业务");
            }
            int updated = jdbcTemplate.update("""
                    update inventory_package_instance
                    set remaining_base_quantity=?,status='AVAILABLE',
                        operation_ref_type='',operation_ref_id=0,changed_at=now()
                    where id=? and batch_id=? and remaining_base_quantity=?
                    """, usage.quantityBefore(), usage.packageInstanceId(), batchId, usage.quantityAfter());
            if (updated != 1) {
                throw new BusinessException("实重包装“" + usage.packageCode() + "”状态已变化，不能冲正");
            }
            jdbcTemplate.update("""
                    update inventory_package_instance_usage
                    set status='REVERSED',reversed_at=now()
                    where id=? and status='CONFIRMED'
                    """, usage.id());
        }
        for (PackageBalanceRow balance : consumedBalances) {
            if (StringUtils.hasText(balance.unit()) && balance.quantity().compareTo(ZERO) > 0) {
                addBalance(batchId, productId, balance.unit(), balance.quantity());
                insertPackageMovement(batchId, productId, "NONTRADE_BAL_REV",
                        "", ZERO, balance.unit(), balance.quantity(),
                        "INVENTORY_NON_TRADE_REVERSAL", documentId, "冲正恢复实重包装余额");
            }
        }
        insertPackageMovement(batchId, productId, "REVERSAL", "", ZERO, "", ZERO,
                "INVENTORY_NON_TRADE_REVERSAL", documentId, "其他出库冲正恢复实重包装");
    }

    public void adjustStocktakeBaseQuantity(Long batchId, Long productId, BigDecimal difference,
                                            String baseUnit, Long stocktakeItemId) {
        if (difference.compareTo(ZERO) == 0) return;
        if (difference.compareTo(ZERO) > 0) {
            addBalance(batchId, productId, baseUnit, difference);
            insertPackageMovement(batchId, productId, "STOCKTAKE_GAIN", "", ZERO,
                    baseUnit, difference, "STOCKTAKE_ITEM", stocktakeItemId, "盘盈形成散装基础单位库存");
            return;
        }
        int versionNo = jdbcTemplate.query("select packaging_version_no from inventory_batch where id=? for update",
                rs -> rs.next() ? rs.getInt(1) : 0, batchId);
        Map<String, PackagingDtos.Rule> rules = activeRuleMap(rulesForVersion(productId, versionNo));
        BigDecimal loss = difference.abs();
        ensureBalance(batchId, productId, baseUnit, loss, rules,
                "STOCKTAKE_ITEM", stocktakeItemId, new HashSet<>());
        addBalance(batchId, productId, baseUnit, loss.negate());
        insertPackageMovement(batchId, productId, "STOCKTAKE_LOSS", baseUnit, loss,
                "", ZERO, "STOCKTAKE_ITEM", stocktakeItemId, "盘亏扣减基础单位库存");
    }

    public void reverseStocktakePackaging(Long batchId, Long productId, Long stocktakeItemId) {
        List<Map<String, Object>> movements = jdbcTemplate.queryForList("""
                select movement_type,from_unit,from_quantity,to_unit,to_quantity
                from inventory_package_movement where ref_type='STOCKTAKE_ITEM' and ref_id=? order by id desc
                """, stocktakeItemId);
        for (Map<String, Object> movement : movements) {
            String type = String.valueOf(movement.get("movement_type"));
            String fromUnit = String.valueOf(movement.get("from_unit"));
            String toUnit = String.valueOf(movement.get("to_unit"));
            BigDecimal fromQuantity = decimal(movement.get("from_quantity"));
            BigDecimal toQuantity = decimal(movement.get("to_quantity"));
            if ("STOCKTAKE_GAIN".equals(type) && StringUtils.hasText(toUnit)) {
                addBalance(batchId, productId, toUnit, toQuantity.negate());
            } else if ("STOCKTAKE_LOSS".equals(type) && StringUtils.hasText(fromUnit)) {
                addBalance(batchId, productId, fromUnit, fromQuantity);
            } else if ("BREAK".equals(type)) {
                if (StringUtils.hasText(toUnit)) addBalance(batchId, productId, toUnit, toQuantity.negate());
                if (StringUtils.hasText(fromUnit)) addBalance(batchId, productId, fromUnit, fromQuantity);
            }
        }
        insertPackageMovement(batchId, productId, "STOCKTAKE_REVERSAL", "", ZERO, "", ZERO,
                "STOCKTAKE_REVERSAL", stocktakeItemId, "盘点冲正恢复包装结构");
    }

    private void consumeOperationPackaging(Long batchId, Long productId, PackagingDtos.Plan plan,
                                           List<Long> packageInstanceIds, String refType, Long refId,
                                           String instanceStatus, String memo) {
        String trackingMode = jdbcTemplate.query("select packaging_tracking_mode from inventory_batch where id=?",
                rs -> rs.next() ? rs.getString(1) : "QUANTITY_ONLY", batchId);
        Integer balanceRows = jdbcTemplate.queryForObject(
                "select count(*) from inventory_package_balance where batch_id=? and quantity>0", Integer.class, batchId);
        List<Long> ids = normalizedIds(packageInstanceIds);
        if (("QUANTITY_ONLY".equals(trackingMode) || balanceRows == null || balanceRows == 0) && ids.isEmpty()) return;

        int versionNo = jdbcTemplate.query("select packaging_version_no from inventory_batch where id=? for update",
                rs -> rs.next() ? rs.getInt(1) : 0, batchId);
        Map<String, PackagingDtos.Rule> rules = activeRuleMap(rulesForVersion(productId, versionNo));
        List<InventoryPackageRow> selected = lockInventoryPackages(batchId, ids);
        boolean actualInstanceTracking = "ACTUAL_INSTANCE".equals(trackingMode)
                || Boolean.TRUE.equals(plan.actualWeightRequired());
        if (actualInstanceTracking) {
            validateWholeActualPackages(plan, selected);
            for (InventoryPackageRow item : selected) {
                int updated = jdbcTemplate.update("""
                        update inventory_package_instance
                        set status=?,operation_ref_type=?,operation_ref_id=?,changed_at=now()
                        where id=? and batch_id=? and status='AVAILABLE'
                        """, instanceStatus, refType, refId, item.id(), batchId);
                if (updated != 1) throw new BusinessException("包装实物已被其他业务占用");
            }
        } else if (!selected.isEmpty()) {
            throw new BusinessException("非逐件实重库存无需选择包装实物");
        }
        for (PackagingDtos.Component component : plan.components()) {
            ensureBalance(batchId, productId, component.unit(), component.quantity(), rules,
                    refType, refId, new HashSet<>());
            addBalance(batchId, productId, component.unit(), component.quantity().negate());
            String movementType = switch (refType) {
                case "TRANSFER_ITEM" -> "TRANSFER_OUT";
                case "INVENTORY_NON_TRADE" -> "NON_TRADE_OUT";
                default -> "PURCHASE_RETURN";
            };
            insertPackageMovement(batchId, productId, movementType, component.unit(), component.quantity(),
                    "", ZERO, refType, refId, memo);
        }
    }

    private void restoreOperationPackaging(Long batchId, Long productId, String refType, Long refId,
                                           List<Long> packageInstanceIds, String memo) {
        List<Map<String, Object>> movements = jdbcTemplate.queryForList("""
                select movement_type,from_unit,from_quantity,to_unit,to_quantity from inventory_package_movement
                where ref_type=? and ref_id=? and batch_id=? and product_id=? order by id desc
                """, refType, refId, batchId, productId);
        for (Map<String, Object> movement : movements) {
            String type = String.valueOf(movement.get("movement_type"));
            String fromUnit = String.valueOf(movement.get("from_unit"));
            String toUnit = String.valueOf(movement.get("to_unit"));
            BigDecimal fromQuantity = decimal(movement.get("from_quantity"));
            BigDecimal toQuantity = decimal(movement.get("to_quantity"));
            if (("TRANSFER_OUT".equals(type) || "PURCHASE_RETURN".equals(type) || "NON_TRADE_OUT".equals(type))
                    && StringUtils.hasText(fromUnit)) {
                addBalance(batchId, productId, fromUnit, fromQuantity);
            } else if ("BREAK".equals(type)) {
                if (StringUtils.hasText(toUnit) && toQuantity.compareTo(ZERO) > 0) addBalance(batchId, productId, toUnit, toQuantity.negate());
                if (StringUtils.hasText(fromUnit) && fromQuantity.compareTo(ZERO) > 0) addBalance(batchId, productId, fromUnit, fromQuantity);
            }
        }
        List<Long> ids = normalizedIds(packageInstanceIds);
        if (!ids.isEmpty()) {
            int updated = jdbcTemplate.update("""
                    update inventory_package_instance
                    set status='AVAILABLE',operation_ref_type='',operation_ref_id=0,changed_at=now()
                    where batch_id=? and operation_ref_type=? and operation_ref_id=? and id in (%s)
                    """.formatted(placeholders(ids.size())), joinArgs(batchId, refType, refId, ids));
            if (updated != ids.size()) throw new BusinessException("包装实物状态已变化，不能冲正");
        }
        insertPackageMovement(batchId, productId, "REVERSAL", "", ZERO, "", ZERO,
                refType + "_REVERSAL", refId, memo);
    }

    private void addPlanBalances(Long batchId, Long productId, PackagingDtos.Plan plan,
                                 String refType, Long refId, String memo) {
        for (PackagingDtos.Component component : plan.components()) {
            addBalance(batchId, productId, component.unit(), component.quantity());
            insertPackageMovement(batchId, productId, "TRANSFER_IN", "", ZERO,
                    component.unit(), component.quantity(), refType, refId, memo);
        }
    }

    private void subtractPlanBalances(Long batchId, Long productId, PackagingDtos.Plan plan,
                                      String refType, Long refId, String memo) {
        int versionNo = jdbcTemplate.query("select packaging_version_no from inventory_batch where id=? for update",
                rs -> rs.next() ? rs.getInt(1) : 0, batchId);
        Map<String, PackagingDtos.Rule> rules = activeRuleMap(rulesForVersion(productId, versionNo));
        for (PackagingDtos.Component component : plan.components()) {
            ensureBalance(batchId, productId, component.unit(), component.quantity(), rules,
                    refType, refId, new HashSet<>());
            addBalance(batchId, productId, component.unit(), component.quantity().negate());
            insertPackageMovement(batchId, productId, "TRANSFER_REVERSAL", component.unit(), component.quantity(),
                    "", ZERO, refType, refId, memo);
        }
    }

    private List<Long> normalizedIds(List<Long> ids) {
        return ids == null ? List.of() : ids.stream().filter(id -> id != null && id > 0).distinct().toList();
    }

    private String placeholders(int size) {
        return String.join(",", java.util.Collections.nCopies(size, "?"));
    }

    private Object[] joinArgs(Object first, Object second, Object third, List<Long> ids) {
        List<Object> args = new ArrayList<>(List.of(first, second, third));
        args.addAll(ids);
        return args.toArray();
    }

    private Object[] joinArgs(Object first, Object second, List<Long> ids) {
        List<Object> args = new ArrayList<>(List.of(first, second));
        args.addAll(ids);
        return args.toArray();
    }

    private List<PackagingDtos.Component> decompose(BigDecimal baseQuantity, PackagingDtos.Profile profile,
                                                    String purpose, Map<String, PackagingDtos.Rule> rules,
                                                    String minimumUnit) {
        BigDecimal minimumFactor = factorToBase(minimumUnit, profile.baseUnit(), rules, new HashSet<>());
        if (minimumFactor == null) {
            minimumFactor = ONE;
        }
        List<UnitFactor> candidates = new ArrayList<>();
        for (PackagingDtos.Rule rule : rules.values()) {
            if (!allowed(rule, purpose)) {
                continue;
            }
            BigDecimal factor = factorToBase(rule.parentUnit(), profile.baseUnit(), rules, new HashSet<>());
            if (factor != null && factor.compareTo(minimumFactor) >= 0) {
                candidates.add(new UnitFactor(rule.parentUnit(), factor, rule.conversionMode()));
            }
        }
        if (minimumFactor.compareTo(ONE) <= 0) {
            candidates.add(new UnitFactor(profile.baseUnit(), ONE, "BASE"));
        }
        candidates.sort(Comparator.comparing(UnitFactor::factor).reversed());

        BigDecimal remaining = baseQuantity;
        List<PackagingDtos.Component> components = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (UnitFactor candidate : candidates) {
            if (!seen.add(key(candidate.unit()))) {
                continue;
            }
            BigDecimal count = same(candidate.unit(), profile.baseUnit()) && candidate.factor().compareTo(ONE) == 0
                    ? remaining : remaining.divideToIntegralValue(candidate.factor());
            if (count.compareTo(ZERO) <= 0) {
                continue;
            }
            BigDecimal componentBase = count.multiply(candidate.factor());
            components.add(new PackagingDtos.Component(candidate.unit(), strip(count), strip(componentBase), candidate.mode()));
            remaining = remaining.subtract(componentBase);
        }
        if (remaining.compareTo(ZERO) != 0) {
            throw new BusinessException("数量无法按最小单位“" + minimumUnit + "”完整配货");
        }
        return components;
    }

    private PackagingDtos.Plan planAgainstBatchBalance(Long batchId, PackagingDtos.Profile profile,
                                                       PackagingDtos.Plan idealPlan) {
        if (Boolean.TRUE.equals(idealPlan.actualWeightRequired())) {
            return idealPlan;
        }
        List<PackageBalanceRow> storedBalances = jdbcTemplate.query("""
                select unit_name, quantity from inventory_package_balance
                where batch_id = ? and quantity > 0
                """, (rs, rowNum) -> new PackageBalanceRow(rs.getString(1), rs.getBigDecimal(2)), batchId);
        if (storedBalances.isEmpty()) {
            return idealPlan;
        }
        Map<String, BigDecimal> balances = new HashMap<>();
        for (PackageBalanceRow balance : storedBalances) {
            balances.put(key(balance.unit()), balance.quantity());
        }
        Map<String, PackagingDtos.Rule> rules = activeRuleMap(profile.rules());
        BigDecimal minimumFactor = factorToBase(profile.minimumOutboundUnit(), profile.baseUnit(), rules, new HashSet<>());
        if (minimumFactor == null) {
            minimumFactor = ONE;
        }
        List<UnitFactor> candidates = new ArrayList<>();
        for (PackagingDtos.Rule rule : rules.values()) {
            if (!Boolean.TRUE.equals(rule.outboundAllowed())) {
                continue;
            }
            BigDecimal factor = factorToBase(rule.parentUnit(), profile.baseUnit(), rules, new HashSet<>());
            if (factor != null && factor.compareTo(minimumFactor) >= 0) {
                candidates.add(new UnitFactor(rule.parentUnit(), factor, rule.conversionMode()));
            }
        }
        if (minimumFactor.compareTo(ONE) <= 0) {
            candidates.add(new UnitFactor(profile.baseUnit(), ONE, "BASE"));
        }
        candidates.sort(Comparator.comparing(UnitFactor::factor).reversed());

        BigDecimal remaining = idealPlan.baseQuantity();
        List<PackagingDtos.Component> components = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (UnitFactor candidate : candidates) {
            if (!seen.add(key(candidate.unit()))) {
                continue;
            }
            BigDecimal desired = remaining.divideToIntegralValue(candidate.factor());
            if (desired.compareTo(ZERO) <= 0) {
                continue;
            }
            makeSimulatedBalanceAvailable(candidate.unit(), desired, balances, rules, new HashSet<>());
            BigDecimal available = balances.getOrDefault(key(candidate.unit()), ZERO);
            BigDecimal take = available.min(desired).divideToIntegralValue(ONE);
            if (take.compareTo(ZERO) <= 0) {
                continue;
            }
            balances.put(key(candidate.unit()), available.subtract(take));
            BigDecimal componentBase = take.multiply(candidate.factor());
            components.add(new PackagingDtos.Component(candidate.unit(), strip(take), strip(componentBase), candidate.mode()));
            remaining = remaining.subtract(componentBase);
        }
        if (remaining.compareTo(ZERO) != 0) {
            throw new BusinessException("当前批次的包装形态无法配出" + strip(idealPlan.baseQuantity()).toPlainString()
                    + profile.baseUnit() + "。逐件实重包装必须整包流转；固定数量包装请检查可拆规则和包装余额");
        }
        String suggestion = suggestion(components, idealPlan.baseQuantity(), profile.baseUnit(), false);
        String snapshot = snapshotJson(profile, idealPlan.requestedQuantity(), idealPlan.requestedUnit(),
                idealPlan.baseQuantity(), idealPlan.pricingQuantity(), idealPlan.priceUnit(), components, false);
        return new PackagingDtos.Plan(
                idealPlan.requestedQuantity(), idealPlan.requestedUnit(), idealPlan.baseQuantity(),
                idealPlan.baseUnit(), idealPlan.pricingQuantity(), idealPlan.priceUnit(),
                false, idealPlan.rounded(), idealPlan.roundingPolicy(), suggestion, components,
                snapshot, false, profile.effectiveRevisionId(), sha256(snapshot), "ACTUAL");
    }

    private void makeSimulatedBalanceAvailable(String unit, BigDecimal required,
                                               Map<String, BigDecimal> balances,
                                               Map<String, PackagingDtos.Rule> rules,
                                               Set<String> visiting) {
        BigDecimal available = balances.getOrDefault(key(unit), ZERO);
        if (available.compareTo(required) >= 0) {
            return;
        }
        String unitKey = key(unit);
        if (!visiting.add(unitKey)) {
            return;
        }
        PackagingDtos.Rule parentRule = rules.values().stream()
                .filter(rule -> same(rule.childUnit(), unit))
                .filter(rule -> "FIXED".equals(rule.conversionMode()))
                .filter(rule -> Boolean.TRUE.equals(rule.breakable()))
                .min(Comparator.comparing(rule -> rule.sortNo() == null ? Integer.MAX_VALUE : rule.sortNo()))
                .orElse(null);
        if (parentRule == null) {
            return;
        }
        BigDecimal childPerParent = positiveOrNull(parentRule.fixedChildQuantity());
        BigDecimal parentRequired = required.subtract(available).divide(childPerParent, 0, RoundingMode.CEILING);
        makeSimulatedBalanceAvailable(parentRule.parentUnit(), parentRequired, balances, rules, visiting);
        BigDecimal parentAvailable = balances.getOrDefault(key(parentRule.parentUnit()), ZERO);
        BigDecimal parentToBreak = parentAvailable.min(parentRequired).divideToIntegralValue(ONE);
        if (parentToBreak.compareTo(ZERO) > 0) {
            balances.put(key(parentRule.parentUnit()), parentAvailable.subtract(parentToBreak));
            balances.put(unitKey, available.add(parentToBreak.multiply(childPerParent)));
        }
        visiting.remove(unitKey);
    }

    private RoundingResult applyMinimum(BigDecimal baseQuantity, String minimumUnit, String baseUnit,
                                        Map<String, PackagingDtos.Rule> rules, String roundingPolicy) {
        if (same(minimumUnit, baseUnit)) {
            return new RoundingResult(baseQuantity, false);
        }
        BigDecimal factor = factorToBase(minimumUnit, baseUnit, rules, new HashSet<>());
        if (factor == null) {
            if (!same(minimumUnit, baseUnit)) {
                throw new BusinessException("最小业务单位“" + minimumUnit + "”没有固定换算关系");
            }
            return new RoundingResult(baseQuantity, false);
        }
        BigDecimal[] division = baseQuantity.divideAndRemainder(factor);
        if (division[1].compareTo(ZERO) == 0) {
            return new RoundingResult(baseQuantity, false);
        }
        return switch (roundingPolicy) {
            case "ROUND_UP" -> new RoundingResult(division[0].add(ONE).multiply(factor), true);
            case "ROUND_DOWN" -> {
                BigDecimal rounded = division[0].multiply(factor);
                if (rounded.compareTo(ZERO) <= 0) {
                    throw new BusinessException("数量小于最小业务单位“" + minimumUnit + "”");
                }
                yield new RoundingResult(rounded, true);
            }
            default -> throw new BusinessException("数量必须是最小业务单位“" + minimumUnit + "”的整数倍");
        };
    }

    private BigDecimal pricingQuantity(BigDecimal baseQuantity, BigDecimal requestedQuantity, String requestedUnit,
                                       String priceUnit, String baseUnit, Map<String, PackagingDtos.Rule> rules,
                                       boolean rounded) {
        if (same(priceUnit, baseUnit)) {
            return baseQuantity;
        }
        if (!rounded && same(priceUnit, requestedUnit)) {
            return requestedQuantity;
        }
        BigDecimal factor = factorToBase(priceUnit, baseUnit, rules, new HashSet<>());
        if (factor == null || factor.compareTo(ZERO) <= 0) {
            throw new BusinessException("计价单位“" + priceUnit + "”无法换算为库存单位“" + baseUnit + "”");
        }
        return baseQuantity.divide(factor, 6, RoundingMode.HALF_UP);
    }

    private BigDecimal factorToBase(String unit, String baseUnit, Map<String, PackagingDtos.Rule> rules, Set<String> visiting) {
        if (same(unit, baseUnit)) {
            return ONE;
        }
        String key = key(unit);
        if (!visiting.add(key)) {
            throw new BusinessException("包装换算关系存在循环");
        }
        PackagingDtos.Rule rule = rules.get(key);
        if (rule == null) {
            return null;
        }
        if ("THEORETICAL".equals(rule.conversionMode())) {
            return positiveOrNull(rule.referenceBaseQuantity());
        }
        if (!"FIXED".equals(rule.conversionMode())) {
            return null;
        }
        BigDecimal childFactor = factorToBase(rule.childUnit(), baseUnit, rules, visiting);
        BigDecimal childQuantity = positiveOrNull(rule.fixedChildQuantity());
        if (childFactor == null || childQuantity == null) {
            return null;
        }
        return childQuantity.multiply(childFactor);
    }

    private void validateRules(String baseUnit, List<PackagingDtos.RuleSave> rules) {
        Map<String, PackagingDtos.RuleSave> parents = new HashMap<>();
        for (PackagingDtos.RuleSave rule : rules) {
            String parent = unitName(rule.parentUnit(), "");
            String child = unitName(rule.childUnit(), "");
            if (!StringUtils.hasText(parent) || !StringUtils.hasText(child)) {
                throw new BusinessException("包装上级单位和下级单位不能为空");
            }
            if (same(parent, child)) {
                throw new BusinessException("包装上级单位和下级单位不能相同");
            }
            String mode = conversionMode(rule.conversionMode());
            if ("FIXED".equals(mode) && positiveOrNull(rule.fixedChildQuantity()) == null) {
                throw new BusinessException(parent + "到" + child + "的固定换算数量必须大于0");
            }
            if ("THEORETICAL".equals(mode) && positiveOrNull(rule.referenceBaseQuantity()) == null) {
                throw new BusinessException(parent + "的理论换算必须填写参考" + baseUnit + "数量");
            }
            if (("ACTUAL_WEIGHT".equals(mode) || "THEORETICAL".equals(mode)) && !same(child, baseUnit)) {
                throw new BusinessException(parent + "的实重/理论换算下级单位必须是主库存单位“" + baseUnit + "”");
            }
            if ("ACTUAL_WEIGHT".equals(mode) && Boolean.TRUE.equals(rule.repackable())) {
                throw new BusinessException("实重包装不能按固定数量自动复包，请关闭“可复包”");
            }
            if (!"FIXED".equals(mode) && Boolean.TRUE.equals(rule.breakable())) {
                throw new BusinessException(parent + "只有固定数量包装才能配置可拆；实重包装必须保持原包装整包流转");
            }
            if (parents.putIfAbsent(key(parent), rule) != null) {
                throw new BusinessException("包装单位“" + parent + "”只能配置一个下级关系");
            }
        }
        for (String parent : parents.keySet()) {
            Set<String> path = new HashSet<>();
            String current = parent;
            while (parents.containsKey(current)) {
                if (!path.add(current)) {
                    throw new BusinessException("包装换算关系存在循环");
                }
                current = key(parents.get(current).childUnit());
            }
        }
    }

    private void validatePolicyUnits(String baseUnit, String minimumSalesUnit, String minimumOutboundUnit,
                                     String defaultPurchaseUnit, String defaultSalesUnit,
                                     List<PackagingDtos.RuleSave> rules) {
        Map<String, PackagingDtos.RuleSave> active = new HashMap<>();
        for (PackagingDtos.RuleSave rule : rules) {
            if ("ENABLED".equals(enabledStatus(rule.status(), "包装规则状态"))) {
                active.put(key(rule.parentUnit()), rule);
            }
        }
        for (PackagingDtos.RuleSave rule : active.values()) {
            if ("FIXED".equals(conversionMode(rule.conversionMode()))
                    && fixedFactorToBase(rule.parentUnit(), baseUnit, active, new HashSet<>()) == null) {
                throw new BusinessException("包装单位“" + rule.parentUnit() + "”没有完整换算到主库存单位“" + baseUnit + "”");
            }
        }
        requireFixedMinimum(minimumSalesUnit, "最小销售单位", baseUnit, active);
        requireFixedMinimum(minimumOutboundUnit, "最小发货单位", baseUnit, active);
        requireAllowedUnit(defaultPurchaseUnit, "默认采购单位", baseUnit, active, "PURCHASE");
        requireAllowedUnit(defaultSalesUnit, "默认销售单位", baseUnit, active, "SALES");
        requireAllowedUnit(minimumSalesUnit, "最小销售单位", baseUnit, active, "SALES");
        requireAllowedUnit(minimumOutboundUnit, "最小发货单位", baseUnit, active, "OUTBOUND");
    }

    private void requireFixedMinimum(String unit, String label, String baseUnit,
                                     Map<String, PackagingDtos.RuleSave> rules) {
        if (!same(unit, baseUnit) && fixedFactorToBase(unit, baseUnit, rules, new HashSet<>()) == null) {
            throw new BusinessException(label + "“" + unit + "”必须有固定换算关系，实重包装不能作为最小数量步长");
        }
    }

    private void requireAllowedUnit(String unit, String label, String baseUnit,
                                    Map<String, PackagingDtos.RuleSave> rules, String purpose) {
        if (same(unit, baseUnit)) {
            return;
        }
        PackagingDtos.RuleSave rule = rules.get(key(unit));
        if (rule == null) {
            throw new BusinessException(label + "“" + unit + "”未配置为可用包装单位");
        }
        boolean allowed = switch (purpose) {
            case "PURCHASE" -> Boolean.TRUE.equals(rule.purchaseAllowed());
            case "OUTBOUND" -> Boolean.TRUE.equals(rule.outboundAllowed());
            default -> Boolean.TRUE.equals(rule.salesAllowed());
        };
        if (!allowed) {
            throw new BusinessException(label + "“" + unit + "”未开启对应业务用途");
        }
    }

    private BigDecimal fixedFactorToBase(String unit, String baseUnit,
                                         Map<String, PackagingDtos.RuleSave> rules, Set<String> visiting) {
        if (same(unit, baseUnit)) {
            return ONE;
        }
        String unitKey = key(unit);
        if (!visiting.add(unitKey)) {
            throw new BusinessException("包装换算关系存在循环");
        }
        PackagingDtos.RuleSave rule = rules.get(unitKey);
        if (rule == null) {
            return null;
        }
        String mode = conversionMode(rule.conversionMode());
        if ("THEORETICAL".equals(mode)) {
            return positiveOrNull(rule.referenceBaseQuantity());
        }
        if (!"FIXED".equals(mode)) {
            return null;
        }
        BigDecimal childFactor = fixedFactorToBase(rule.childUnit(), baseUnit, rules, visiting);
        BigDecimal childQuantity = positiveOrNull(rule.fixedChildQuantity());
        return childFactor == null || childQuantity == null ? null : childQuantity.multiply(childFactor);
    }

    private Map<String, PackagingDtos.Rule> activeRuleMap(List<PackagingDtos.Rule> rules) {
        Map<String, PackagingDtos.Rule> result = new HashMap<>();
        for (PackagingDtos.Rule rule : rules) {
            if ("ENABLED".equals(rule.status())) {
                result.put(key(rule.parentUnit()), rule);
            }
        }
        return result;
    }

    private List<PackagingDtos.Rule> rulesForVersion(Long productId, int versionNo) {
        if (versionNo <= 0) {
            return List.of();
        }
        return jdbcTemplate.query("""
                select *
                from product_package_rule
                where product_id = ? and version_no = ? and status = 'ENABLED'
                order by sort_no, id
                """, (rs, rowNum) -> new PackagingDtos.Rule(
                rs.getLong("id"), rs.getLong("product_id"), rs.getInt("version_no"),
                rs.getString("parent_unit"), rs.getString("child_unit"), rs.getString("conversion_mode"),
                rs.getBigDecimal("fixed_child_quantity"), rs.getBigDecimal("reference_base_quantity"),
                rs.getInt("breakable") == 1, rs.getInt("repackable") == 1,
                rs.getInt("purchase_allowed") == 1, rs.getInt("sales_allowed") == 1,
                rs.getInt("outbound_allowed") == 1, rs.getInt("integer_only") == 1,
                rs.getInt("sort_no"), rs.getString("status"), rs.getString("rule_key"),
                rs.getString("source_level"), rs.getInt("overridden") == 1
        ), productId, versionNo);
    }

    private List<InventoryPackageRow> lockInventoryPackages(Long batchId, List<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(batchId);
        args.addAll(ids);
        List<InventoryPackageRow> rows = jdbcTemplate.query("""
                        select id, package_code, unit_name, actual_base_quantity,
                               remaining_base_quantity, base_unit, status
                        from inventory_package_instance
                        where batch_id = ? and id in (%s)
                        for update
                        """.formatted(placeholders),
                (rs, rowNum) -> new InventoryPackageRow(
                        rs.getLong("id"), rs.getString("package_code"), rs.getString("unit_name"),
                        rs.getBigDecimal("actual_base_quantity"), rs.getBigDecimal("remaining_base_quantity"),
                        rs.getString("base_unit"), rs.getString("status")
                ), args.toArray());
        if (rows.size() != ids.size()) {
            throw new BusinessException("选择的实重包装不属于当前库存批次");
        }
        for (InventoryPackageRow row : rows) {
            if (!"AVAILABLE".equals(row.status())) {
                throw new BusinessException("实重包装“" + row.packageCode() + "”已出库或已拆包");
            }
        }
        Map<Long, InventoryPackageRow> byId = new HashMap<>();
        for (InventoryPackageRow row : rows) {
            byId.put(row.id(), row);
        }
        return ids.stream().map(byId::get).toList();
    }

    private void validateWholeActualPackages(PackagingDtos.Plan plan, List<InventoryPackageRow> rows) {
        if (rows.isEmpty()) {
            throw new BusinessException("按实重包装出库时，必须选择具体包装码");
        }
        BigDecimal total = ZERO;
        String packageUnit = rows.get(0).unitName();
        for (InventoryPackageRow row : rows) {
            if (!same(row.unitName(), packageUnit) || !same(row.baseUnit(), plan.baseUnit())) {
                throw new BusinessException("一次出库只能选择同一种实重包装，且库存核算单位必须一致");
            }
            total = total.add(row.remainingBaseQuantity());
        }
        if (total.compareTo(plan.baseQuantity()) != 0) {
            throw new BusinessException("所选包装实重合计为" + strip(total).toPlainString() + plan.baseUnit()
                    + "，与出库实际数量" + strip(plan.baseQuantity()).toPlainString() + plan.baseUnit() + "不一致");
        }
    }

    private List<InventoryDtos.NonTradePackageAllocation> normalizedNonTradeAllocations(
            List<InventoryDtos.NonTradePackageAllocation> packageAllocations) {
        if (packageAllocations == null || packageAllocations.isEmpty()) {
            throw new BusinessException("送样、领用或损耗从逐件实重库存扣取时，必须逐包装填写来源和扣取量");
        }
        Map<Long, InventoryDtos.NonTradePackageAllocation> byPackageId = new LinkedHashMap<>();
        for (InventoryDtos.NonTradePackageAllocation allocation : packageAllocations) {
            if (allocation == null || allocation.packageInstanceId() == null
                    || allocation.packageInstanceId() <= 0) {
                throw new BusinessException("来源实重包装不能为空");
            }
            if (allocation.quantity() == null || allocation.quantity().compareTo(ZERO) <= 0) {
                throw new BusinessException("每个来源包装的扣取量必须大于0");
            }
            if (byPackageId.putIfAbsent(allocation.packageInstanceId(), allocation) != null) {
                throw new BusinessException("同一个来源实重包装不能重复分配");
            }
        }
        return List.copyOf(byPackageId.values());
    }

    private void validatePartialActualPackages(
            PackagingDtos.Plan plan,
            List<InventoryPackageRow> rows,
            List<InventoryDtos.NonTradePackageAllocation> allocations) {
        if (rows.isEmpty()) {
            throw new BusinessException("送样、领用或损耗从逐件实重库存扣取时，必须逐包装填写来源和扣取量");
        }
        Map<Long, InventoryPackageRow> packagesById = new LinkedHashMap<>();
        for (InventoryPackageRow row : rows) packagesById.put(row.id(), row);
        BigDecimal allocatedTotal = ZERO;
        String packageUnit = rows.get(0).unitName();
        for (InventoryDtos.NonTradePackageAllocation allocation : allocations) {
            InventoryPackageRow row = packagesById.get(allocation.packageInstanceId());
            if (!same(row.unitName(), packageUnit) || !same(row.baseUnit(), plan.baseUnit())) {
                throw new BusinessException("一次扣取只能选择同一种实重包装，且库存核算单位必须一致");
            }
            if (row.remainingBaseQuantity() == null || row.remainingBaseQuantity().compareTo(ZERO) <= 0) {
                throw new BusinessException("实重包装“" + row.packageCode() + "”已经没有剩余重量");
            }
            if (allocation.quantity().compareTo(row.remainingBaseQuantity()) > 0) {
                throw new BusinessException("实重包装“" + row.packageCode() + "”本次扣取"
                        + strip(allocation.quantity()).toPlainString() + plan.baseUnit()
                        + "，超过当前剩余" + strip(row.remainingBaseQuantity()).toPlainString()
                        + plan.baseUnit());
            }
            allocatedTotal = allocatedTotal.add(allocation.quantity());
        }
        if (allocatedTotal.compareTo(plan.baseQuantity()) != 0) {
            throw new BusinessException("各来源包装扣取量合计为"
                    + strip(allocatedTotal).toPlainString() + plan.baseUnit()
                    + "，必须与本次数量" + strip(plan.baseQuantity()).toPlainString()
                    + plan.baseUnit() + "完全一致");
        }
    }

    private boolean snapshotHasActualWeightRule(String snapshotJson, String packageUnit) {
        if (!StringUtils.hasText(snapshotJson) || !StringUtils.hasText(packageUnit)) return false;
        try {
            JsonNode root = objectMapper.readTree(snapshotJson);
            for (JsonNode rule : root.path("rules")) {
                if (same(rule.path("parentUnit").asText(""), packageUnit)
                        && "ACTUAL_WEIGHT".equals(rule.path("conversionMode").asText(""))
                        && !"DISABLED".equals(rule.path("status").asText("ENABLED"))) {
                    return true;
                }
            }
            return false;
        } catch (JsonProcessingException ex) {
            throw new BusinessException("包装规则快照无法解析");
        }
    }

    private void ensureBalance(Long batchId, Long productId, String unit, BigDecimal required,
                               Map<String, PackagingDtos.Rule> rules, String refType, Long refId,
                               Set<String> visiting) {
        BigDecimal available = balanceForUpdate(batchId, unit);
        if (available.compareTo(required) >= 0) {
            return;
        }
        String unitKey = key(unit);
        if (!visiting.add(unitKey)) {
            throw new BusinessException("库存包装拆分关系存在循环");
        }
        PackagingDtos.Rule parentRule = rules.values().stream()
                .filter(rule -> same(rule.childUnit(), unit))
                .filter(rule -> "FIXED".equals(rule.conversionMode()))
                .filter(rule -> Boolean.TRUE.equals(rule.breakable()))
                .min(Comparator.comparing(rule -> rule.sortNo() == null ? Integer.MAX_VALUE : rule.sortNo()))
                .orElse(null);
        if (parentRule == null) {
            boolean hasActualPackages = rules.values().stream().anyMatch(rule ->
                    same(rule.childUnit(), unit) && "ACTUAL_WEIGHT".equals(rule.conversionMode()));
            if (hasActualPackages) {
                throw new BusinessException("散装" + unit + "库存不足；逐件实重包装不允许拆分，请改按完整包装出库");
            }
            throw new BusinessException("批次包装库存不足：缺少" + strip(required.subtract(available)).toPlainString() + unit);
        }
        BigDecimal childPerParent = positiveOrNull(parentRule.fixedChildQuantity());
        BigDecimal shortage = required.subtract(available);
        BigDecimal parentRequired = shortage.divide(childPerParent, 0, RoundingMode.CEILING);
        ensureBalance(batchId, productId, parentRule.parentUnit(), parentRequired,
                rules, refType, refId, visiting);
        addBalance(batchId, productId, parentRule.parentUnit(), parentRequired.negate());
        BigDecimal childAdded = parentRequired.multiply(childPerParent);
        addBalance(batchId, productId, unit, childAdded);
        insertPackageMovement(batchId, productId, "BREAK", parentRule.parentUnit(), parentRequired,
                unit, childAdded, refType, refId, "自动拆包配货");
        visiting.remove(unitKey);
    }

    private BigDecimal balanceForUpdate(Long batchId, String unit) {
        List<BigDecimal> balances = jdbcTemplate.query("""
                select quantity from inventory_package_balance
                where batch_id = ? and unit_name = ? for update
                """, (rs, rowNum) -> rs.getBigDecimal(1), batchId, unit);
        return balances.isEmpty() ? ZERO : balances.get(0);
    }

    private void addBalance(Long batchId, Long productId, String unit, BigDecimal delta) {
        jdbcTemplate.update("""
                insert into inventory_package_balance (batch_id, product_id, unit_name, quantity)
                values (?, ?, ?, ?)
                on duplicate key update quantity = quantity + values(quantity), updated_at = current_timestamp
                """, batchId, productId, unit, delta);
        BigDecimal balance = balanceForUpdate(batchId, unit);
        if (balance.compareTo(ZERO) < 0) {
            throw new BusinessException("批次包装库存出现负数：" + unit);
        }
    }

    private void insertPackageMovement(Long batchId, Long productId, String movementType,
                                       String fromUnit, BigDecimal fromQuantity,
                                       String toUnit, BigDecimal toQuantity,
                                       String refType, Long refId, String memo) {
        jdbcTemplate.update("""
                insert into inventory_package_movement
                (batch_id, product_id, movement_type, from_unit, from_quantity,
                 to_unit, to_quantity, ref_type, ref_id, memo)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, batchId, productId, movementType, unitName(fromUnit, ""), fromQuantity,
                unitName(toUnit, ""), toQuantity, unitName(refType, ""), refId == null ? 0 : refId, memo);
    }

    private ProductBase requireProduct(Long productId) {
        dataScopeService.assertMasterAllowed("PRODUCT", productId);
        List<ProductBase> products = jdbcTemplate.query("""
                select p.id,p.name,p.unit,p.product_series_id,coalesce(s.name,'') series_name
                from product p left join product_series s on s.id=p.product_series_id where p.id=?
                """, productBaseMapper(), productId);
        if (products.isEmpty()) {
            throw new BusinessException("商品不存在");
        }
        return products.get(0);
    }

    private String snapshotJson(PackagingDtos.Profile profile, BigDecimal requestedQuantity, String requestedUnit,
                                BigDecimal baseQuantity, BigDecimal pricingQuantity,
                                String priceUnit, List<PackagingDtos.Component> components, boolean estimated) {
        return snapshotJson(profile, requestedQuantity, requestedUnit, baseQuantity,
                pricingQuantity, priceUnit, components, estimated, null);
    }

    private String snapshotJson(PackagingDtos.Profile profile, BigDecimal requestedQuantity, String requestedUnit,
                                BigDecimal baseQuantity, BigDecimal pricingQuantity,
                                String priceUnit, List<PackagingDtos.Component> components, boolean estimated,
                                PackagingDtos.PlanRequest request) {
        try {
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("schemaVersion", 2);
            root.put("versionNo", profile.versionNo());
            root.put("effectiveRevisionId", profile.effectiveRevisionId());
            root.put("contentHash", profile.contentHash());
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("sourceLevel", unitName(profile.sourceLevel(), "SYSTEM_DEFAULT"));
            source.put("templateId", profile.templateId() == null ? 0 : profile.templateId());
            source.put("templateRevisionId",
                    profile.templateRevisionId() == null ? 0 : profile.templateRevisionId());
            source.put("seriesId", profile.productSeriesId() == null ? 0 : profile.productSeriesId());
            source.put("overrideRevisionId",
                    profile.overrideRevisionId() == null ? 0 : profile.overrideRevisionId());
            source.put("supplierOfferRevisionId",
                    request == null || request.supplierOfferRevisionId() == null
                            ? 0 : request.supplierOfferRevisionId());
            source.put("packagingVariantRevisionId",
                    request == null || request.packagingVariantRevisionId() == null
                            ? 0 : request.packagingVariantRevisionId());
            root.put("source", source);
            root.put("policy", Map.of(
                    "baseUnit", profile.baseUnit(), "minimumSalesUnit", profile.minimumSalesUnit(),
                    "minimumOutboundUnit", profile.minimumOutboundUnit(),
                    "defaultPurchaseUnit", profile.defaultPurchaseUnit(),
                    "defaultSalesUnit", profile.defaultSalesUnit(), "roundingPolicy", profile.roundingPolicy()
            ));
            root.put("rules", profile.rules());
            root.put("calculation", Map.of(
                    "requestedQuantity", requestedQuantity, "requestedUnit", requestedUnit,
                    "baseQuantity", baseQuantity, "baseUnit", profile.baseUnit(),
                    "pricingQuantity", pricingQuantity, "priceUnit", priceUnit,
                    "estimated", estimated, "quantityBasis", estimated ? "ESTIMATED" : "ACTUAL",
                    "actualWeightRequired",
                    profile.rules().stream().anyMatch(rule -> same(rule.parentUnit(), requestedUnit)
                            && "ACTUAL_WEIGHT".equals(rule.conversionMode())),
                    "components", components
            ));
            // Keep top-level calculation fields for old clients and migration readers.
            root.put("requestedQuantity", requestedQuantity);
            root.put("requestedUnit", requestedUnit);
            root.put("baseQuantity", baseQuantity);
            root.put("baseUnit", profile.baseUnit());
            root.put("pricingQuantity", pricingQuantity);
            root.put("priceUnit", priceUnit);
            root.put("components", components);
            return objectMapper.writeValueAsString(root);
        } catch (JsonProcessingException ex) {
            throw new BusinessException("包装换算快照生成失败");
        }
    }

    private String suggestion(List<PackagingDtos.Component> components, BigDecimal baseQuantity,
                              String baseUnit, boolean actualWeight) {
        StringBuilder text = new StringBuilder();
        for (PackagingDtos.Component component : components) {
            if (!text.isEmpty()) {
                text.append(" + ");
            }
            text.append(component.quantity().stripTrailingZeros().toPlainString()).append(component.unit());
        }
        if (!components.isEmpty() && !(components.size() == 1 && same(components.get(0).unit(), baseUnit))) {
            text.append(" = ");
        }
        if (components.isEmpty() || !same(components.get(0).unit(), baseUnit) || components.size() > 1) {
            text.append(baseQuantity.stripTrailingZeros().toPlainString()).append(baseUnit);
        }
        if (actualWeight) {
            text.append("（按实际重量）");
        }
        return text.toString();
    }

    private boolean allowed(PackagingDtos.Rule rule, String purpose) {
        return switch (purpose) {
            case "PURCHASE", "INBOUND" -> Boolean.TRUE.equals(rule.purchaseAllowed());
            case "OUTBOUND" -> Boolean.TRUE.equals(rule.outboundAllowed());
            default -> Boolean.TRUE.equals(rule.salesAllowed());
        };
    }

    private String defaultUnitForPurpose(PackagingDtos.Profile profile, String purpose) {
        return switch (purpose(purpose)) {
            case "PURCHASE", "INBOUND" -> profile.defaultPurchaseUnit();
            case "OUTBOUND" -> profile.minimumOutboundUnit();
            default -> profile.defaultSalesUnit();
        };
    }

    private String purpose(String value) {
        if (!StringUtils.hasText(value)) {
            return "SALES";
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "PURCHASE", "PURCHASE_ORDER" -> "PURCHASE";
            case "PURCHASE_INBOUND", "INBOUND" -> "INBOUND";
            case "SALES", "SALES_ORDER", "QUOTE", "SALES_RETURN" -> "SALES";
            case "OUTBOUND", "SALES_OUTBOUND", "TRANSFER_OUT", "PURCHASE_RETURN",
                 "STOCKTAKE_LOSS" -> "OUTBOUND";
            default -> throw new BusinessException("不支持的计量包装用途：" + normalized);
        };
    }

    private String conversionMode(String value) {
        if (!StringUtils.hasText(value)) {
            return "FIXED";
        }
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "ACTUAL_WEIGHT" -> "ACTUAL_WEIGHT";
            case "THEORETICAL" -> "THEORETICAL";
            default -> "FIXED";
        };
    }

    private String roundingPolicy(String value) {
        if (!StringUtils.hasText(value)) {
            return "REJECT";
        }
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "ROUND_UP" -> "ROUND_UP";
            case "ROUND_DOWN" -> "ROUND_DOWN";
            default -> "REJECT";
        };
    }

    private String unitName(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    private String enabledStatus(String value, String label) {
        String normalized = StringUtils.hasText(value)
                ? value.trim().toUpperCase(Locale.ROOT) : "";
        if (!Set.of("ENABLED", "DISABLED").contains(normalized)) {
            throw new BusinessException(label + "只支持ENABLED或DISABLED");
        }
        return normalized;
    }

    private List<PackagingDtos.RuleSave> normalizeRules(
            List<PackagingDtos.RuleSave> inputRules) {
        if (inputRules == null) return List.of();
        List<PackagingDtos.RuleSave> result = new ArrayList<>();
        for (PackagingDtos.RuleSave rule : inputRules) {
            if (rule == null) throw new BusinessException("包装规则不能为空");
            result.add(new PackagingDtos.RuleSave(
                    rule.parentUnit(), rule.childUnit(), rule.conversionMode(),
                    rule.fixedChildQuantity(), rule.referenceBaseQuantity(),
                    rule.breakable(), rule.repackable(), rule.purchaseAllowed(),
                    rule.salesAllowed(), rule.outboundAllowed(), rule.integerOnly(),
                    rule.sortNo(), enabledStatus(rule.status(), "包装规则状态"),
                    rule.ruleKey()));
        }
        return List.copyOf(result);
    }

    private String key(String value) {
        return unitName(value, "").toLowerCase(Locale.ROOT);
    }

    private boolean same(String left, String right) {
        return key(left).equals(key(right));
    }

    private int bool(Boolean value) {
        return Boolean.TRUE.equals(value) ? 1 : 0;
    }

    private BigDecimal positiveOrNull(BigDecimal value) {
        return value != null && value.compareTo(ZERO) > 0 ? value : null;
    }

    private BigDecimal strip(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros();
    }

    private BigDecimal decimal(Object value) {
        if (value instanceof BigDecimal decimal) return decimal;
        if (value instanceof Number number) return BigDecimal.valueOf(number.doubleValue());
        return value == null ? ZERO : new BigDecimal(String.valueOf(value));
    }

    private PolicyRow defaultPolicy(ProductBase product) {
        return new PolicyRow(product.unit(), product.unit(), product.unit(), product.unit(), product.unit(),
                "REJECT", 0, 0L, "SYSTEM_DEFAULT", 0L, 0L, 0L, "", 0);
    }

    private String templateName(long templateId) {
        if (templateId <= 0) return "";
        return jdbcTemplate.query("select name from packaging_template where id=?",
                rs -> rs.next() ? rs.getString(1) : "", templateId);
    }

    private int templateRevisionNo(long revisionId) {
        if (revisionId <= 0) return 0;
        return jdbcTemplate.query("select revision_no from packaging_template_revision where id=?",
                rs -> rs.next() ? rs.getInt(1) : 0, revisionId);
    }

    private List<String> overrideFields(long overrideRevisionId) {
        if (overrideRevisionId <= 0) return List.of();
        List<OverrideJson> rows = jdbcTemplate.query("""
                select policy_patch_json,rule_patch_json from product_packaging_override_revision where id=?
                """, (rs, rowNum) -> new OverrideJson(rs.getString(1), rs.getString(2)), overrideRevisionId);
        if (rows.isEmpty()) return List.of();
        try {
            List<String> fields = new ArrayList<>();
            objectMapper.readTree(rows.get(0).policyJson()).fieldNames().forEachRemaining(fields::add);
            if (objectMapper.readTree(rows.get(0).ruleJson()).size() > 0) fields.add("rules");
            return fields;
        } catch (JsonProcessingException ex) {
            return List.of("INVALID_OVERRIDE");
        }
    }

    private Set<String> overriddenRuleKeys(long overrideRevisionId) {
        if (overrideRevisionId <= 0) return Set.of();
        List<String> json = jdbcTemplate.query("""
                select rule_patch_json from product_packaging_override_revision where id=?
                """, (rs, rowNum) -> rs.getString(1), overrideRevisionId);
        if (json.isEmpty()) return Set.of();
        try {
            Set<String> result = new HashSet<>();
            for (JsonNode patch : objectMapper.readTree(json.get(0))) {
                if ("UPSERT".equals(patch.path("action").asText())) result.add(key(patch.path("ruleKey").asText()));
            }
            return result;
        } catch (JsonProcessingException ex) {
            throw new BusinessException("商品包装特例规则无法解析");
        }
    }

    private void putDifference(Map<String, Object> target, String field, String inherited, String desired) {
        if (!same(inherited, desired)) target.put(field, desired);
    }

    private Map<String, PackagingDtos.RuleSave> ruleMap(List<PackagingDtos.RuleSave> rules) {
        Map<String, PackagingDtos.RuleSave> result = new LinkedHashMap<>();
        for (PackagingDtos.RuleSave rule : rules) result.put(stableRuleKey(rule), rule);
        return result;
    }

    private String stableRuleKey(PackagingDtos.RuleSave rule) {
        return StringUtils.hasText(rule.ruleKey()) ? key(rule.ruleKey()) : key(rule.parentUnit());
    }

    private String ruleSignature(PackagingDtos.RuleSave rule) {
        if (rule == null) return null;
        return String.join("|", key(rule.parentUnit()), key(rule.childUnit()), conversionMode(rule.conversionMode()),
                decimalText(rule.fixedChildQuantity()), decimalText(rule.referenceBaseQuantity()),
                String.valueOf(Boolean.TRUE.equals(rule.breakable())), String.valueOf(Boolean.TRUE.equals(rule.repackable())),
                String.valueOf(Boolean.TRUE.equals(rule.purchaseAllowed())), String.valueOf(Boolean.TRUE.equals(rule.salesAllowed())),
                String.valueOf(Boolean.TRUE.equals(rule.outboundAllowed())),
                String.valueOf(rule.integerOnly() == null || rule.integerOnly()),
                String.valueOf(rule.sortNo() == null ? 0 : rule.sortNo()),
                enabledStatus(rule.status(), "包装规则状态"));
    }

    private PackagingDtos.RuleSave ruleFromJson(JsonNode node) {
        return new PackagingDtos.RuleSave(node.path("parentUnit").asText(), node.path("childUnit").asText(),
                node.path("conversionMode").asText("FIXED"), decimalOrNull(node.get("fixedChildQuantity")),
                decimalOrNull(node.get("referenceBaseQuantity")), node.path("breakable").asBoolean(false),
                node.path("repackable").asBoolean(false), node.path("purchaseAllowed").asBoolean(true),
                node.path("salesAllowed").asBoolean(true), node.path("outboundAllowed").asBoolean(true),
                node.path("integerOnly").asBoolean(true), node.path("sortNo").asInt(0),
                enabledStatus(node.path("status").asText("ENABLED"), "包装规则状态"),
                node.path("ruleKey").asText(null));
    }

    private BigDecimal decimalOrNull(JsonNode node) {
        return node == null || node.isNull() || !node.isNumber() ? null : node.decimalValue();
    }

    private String textOr(JsonNode node, String field, String fallback) {
        JsonNode value = node.path(field);
        return value.isTextual() && StringUtils.hasText(value.asText()) ? value.asText().trim() : fallback;
    }

    private void validateShelfLife(PackagingDtos.PackageDefinitionSave item) {
        String mode = shelfLifeMode(item.shelfLifeMode());
        if ("PERIOD".equals(mode)) {
            if (item.shelfLifeValue() == null || item.shelfLifeValue() <= 0) {
                throw new BusinessException("保质期必须填写大于0的期限");
            }
            shelfLifeUnit(item.shelfLifeUnit());
        }
    }

    private String shelfLifeMode(String value) {
        String mode = StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : "NONE";
        if (!Set.of("NONE", "PERIOD").contains(mode)) throw new BusinessException("保质期类型不正确");
        return mode;
    }

    private String shelfLifeUnit(String value) {
        String unit = StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : "";
        if (!Set.of("DAY", "MONTH", "YEAR").contains(unit)) throw new BusinessException("保质期单位不正确");
        return unit;
    }

    private String blank(String value) {
        return value == null ? "" : value;
    }

    private String profileHash(ResolvedProfile profile) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("baseUnit", key(profile.baseUnit()));
        canonical.put("minimumSalesUnit", key(profile.minimumSalesUnit()));
        canonical.put("minimumOutboundUnit", key(profile.minimumOutboundUnit()));
        canonical.put("defaultPurchaseUnit", key(profile.defaultPurchaseUnit()));
        canonical.put("defaultSalesUnit", key(profile.defaultSalesUnit()));
        canonical.put("roundingPolicy", profile.roundingPolicy());
        canonical.put("rules", profile.rules().stream()
                .sorted(Comparator.comparing(rule -> key(rule.parentUnit())))
                .map(this::ruleSignature).toList());
        return sha256(toJson(canonical));
    }

    private String effectiveProfileJson(ProductBase product, ResolvedProfile profile, long effectiveRevisionId,
                                        int versionNo, String sourceLevel, long templateId,
                                        long templateRevisionId, long overrideRevisionId, String hash) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("schemaVersion", 2);
        value.put("productId", product.id());
        value.put("productSeriesId", product.seriesId() == null ? 0 : product.seriesId());
        value.put("versionNo", versionNo);
        value.put("effectiveRevisionId", effectiveRevisionId);
        value.put("sourceLevel", sourceLevel);
        value.put("templateId", templateId);
        value.put("templateRevisionId", templateRevisionId);
        value.put("overrideRevisionId", overrideRevisionId);
        value.put("contentHash", hash);
        value.put("baseUnit", profile.baseUnit());
        value.put("minimumSalesUnit", profile.minimumSalesUnit());
        value.put("minimumOutboundUnit", profile.minimumOutboundUnit());
        value.put("defaultPurchaseUnit", profile.defaultPurchaseUnit());
        value.put("defaultSalesUnit", profile.defaultSalesUnit());
        value.put("roundingPolicy", profile.roundingPolicy());
        value.put("rules", profile.rules());
        return toJson(value);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new BusinessException("计量包装配置序列化失败");
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(unitName(value, "").getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256不可用", ex);
        }
    }

    private String decimalText(BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }

    private long unitId(String unitName) {
        if (!StringUtils.hasText(unitName)) return 0;
        return jdbcTemplate.query("""
                select id from unit where name=? or abbreviation=? order by status='ENABLED' desc,id limit 1
                """, rs -> rs.next() ? rs.getLong(1) : 0L, unitName.trim(), unitName.trim());
    }

    private Long zeroToNull(long value) {
        return value > 0 ? value : null;
    }

    private <T> List<T> concat(List<T> first, List<T> second) {
        List<T> result = new ArrayList<>(first == null ? List.of() : first);
        result.addAll(second == null ? List.of() : second);
        return List.copyOf(result);
    }

    private long insertId(String sql, Object... args) {
        jdbcTemplate.update(sql, args);
        Long id = jdbcTemplate.queryForObject("select last_insert_id()", Long.class);
        if (id == null || id <= 0) throw new BusinessException("新增计量包装数据失败");
        return id;
    }

    private record ProductBase(Long id, String name, String unit, Long seriesId, String seriesName) {
    }

    private record PolicyRow(String baseUnit, String minimumSalesUnit, String minimumOutboundUnit,
                             String defaultPurchaseUnit, String defaultSalesUnit,
                             String roundingPolicy, Integer versionNo, Long effectiveRevisionId,
                             String sourceLevel, Long sourceTemplateId, Long sourceTemplateRevisionId,
                             Long sourceOverrideRevisionId, String contentHash, Integer templateRevisionNo) {
    }

    private record UnitFactor(String unit, BigDecimal factor, String mode) {
    }

    private record PackagingVariantRule(
            Long revisionId, Long packagingVariantId, Long productId, Integer revisionNo,
            String packageUnit, String conversionMode, BigDecimal fixedBaseQuantity,
            BigDecimal referenceBaseQuantity, String contentHash
    ) {
    }

    private record ProductDependency(String table, String productColumn) {
    }

    private record RoundingResult(BigDecimal baseQuantity, boolean rounded) {
    }

    private record BatchPackagingVersion(Long productId, Long warehouseId, Integer versionNo, Long effectiveRevisionId,
                                         String snapshotJson, String trackingMode) {
    }

    private record PackageBalanceRow(String unit, BigDecimal quantity) {
    }

    private record InventoryPackageRow(Long id, String packageCode, String unitName,
                                       BigDecimal actualBaseQuantity, BigDecimal remainingBaseQuantity,
                                       String baseUnit, String status) {
    }

    private record ActualPackageUsageRow(
            Long id, Long packageInstanceId, BigDecimal baseQuantity, String baseUnit,
            BigDecimal quantityBefore, BigDecimal quantityAfter, String packageCode, String unitName,
            BigDecimal remainingBaseQuantity, String instanceStatus,
            String operationRefType, Long operationRefId
    ) {
    }

    private record ResolvedProfile(String baseUnit, String minimumSalesUnit, String minimumOutboundUnit,
                                   String defaultPurchaseUnit, String defaultSalesUnit, String roundingPolicy,
                                   List<PackagingDtos.RuleSave> rules, long templateId, long templateRevisionId) {
        private ResolvedProfile withLineage(long newTemplateId, long newRevisionId) {
            return new ResolvedProfile(baseUnit, minimumSalesUnit, minimumOutboundUnit, defaultPurchaseUnit,
                    defaultSalesUnit, roundingPolicy, rules, newTemplateId, newRevisionId);
        }
    }

    private record TemplateResolved(long templateId, long revisionId, ResolvedProfile profile) {
    }

    private record TemplateHead(long id, int revisionNo, int lockVersion, String status) {
    }

    private record SeriesBindingHead(int lockVersion) {
    }

    private record OverrideJson(String policyJson, String ruleJson) {
    }

    private record RulePatch(String ruleKey, String action, PackagingDtos.RuleSave rule) {
    }

    private record OverridePatch(Map<String, Object> policy, List<RulePatch> rules) {
        private boolean empty() {
            return policy.isEmpty() && rules.isEmpty();
        }
    }
}
