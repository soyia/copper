package com.soyia.copperv2.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.soyia.copperv2.dto.ProductMasterDataDtos;
import com.soyia.copperv2.dto.SimpleCreateRequests;
import com.soyia.copperv2.security.ForbiddenException;
import com.soyia.copperv2.security.BusinessDataScopeService;
import com.soyia.copperv2.security.SecurityContext;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class ProductMasterDataService {
    private static final String VIEW_PERMISSION = "product.master.complex.view";
    private static final String MANAGE_PERMISSION = "product.master.complex.manage";
    private static final Set<String> REFERENCE_TYPES = Set.of("GRADE", "TEMPER", "STANDARD");
    private static final Set<String> IDENTITY_SOURCE_TYPES =
            Set.of("LEGACY_COLUMN", "SERIES_FIELD", "STRUCTURED");
    private static final Set<String> STRUCTURED_IDENTITY_DIMENSIONS =
            Set.of("PORTS", "CONDUCTOR");
    private static final Set<String> UNIT_PURPOSES =
            Set.of("PURCHASE", "SALES", "INVENTORY", "PACKAGING", "PRICE");

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final BusinessDataScopeService dataScopeService;

    public ProductMasterDataService(
            JdbcTemplate jdbc, ObjectMapper objectMapper,
            BusinessDataScopeService dataScopeService) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.dataScopeService = dataScopeService;
    }

    public List<ProductMasterDataDtos.ReferenceValue> referenceValues(
            String referenceType, String keyword, String status) {
        requireReferenceViewPermission();
        return referenceValuesInternal(referenceType, keyword, status);
    }

    /**
     * Compatibility projection for the legacy product.view endpoints. The
     * caller is the already-authorized CopperService path; governance writes
     * remain available only through the complex master-data API.
     */
    List<ProductMasterDataDtos.ReferenceValue> referenceValuesForProductRead(
            String referenceType, String keyword, String status) {
        return referenceValuesInternal(referenceType, keyword, status);
    }

    private List<ProductMasterDataDtos.ReferenceValue> referenceValuesInternal(
            String referenceType, String keyword, String status) {
        StringBuilder sql = new StringBuilder("""
                select id,reference_type,code,name,english_name,issuing_body,edition,
                       effective_from,effective_to,cast(alias_json as char) alias_json,
                       status,version_no,change_reason
                from product_reference_value where 1=1
                """);
        List<Object> args = new ArrayList<>();
        if (StringUtils.hasText(referenceType)) {
            sql.append(" and reference_type=?");
            args.add(referenceType(referenceType));
        }
        if (StringUtils.hasText(keyword)) {
            sql.append("""
                     and (code like concat('%',?,'%') or name like concat('%',?,'%')
                          or english_name like concat('%',?,'%'))
                    """);
            args.add(keyword.trim());
            args.add(keyword.trim());
            args.add(keyword.trim());
        }
        if (StringUtils.hasText(status)) {
            sql.append(" and status=?");
            args.add(status(status));
        }
        sql.append(" order by reference_type,code,id limit 2000");
        return jdbc.query(sql.toString(), (rs, rowNum) -> new ProductMasterDataDtos.ReferenceValue(
                rs.getLong("id"), rs.getString("reference_type"), rs.getString("code"),
                rs.getString("name"), rs.getString("english_name"), rs.getString("issuing_body"),
                rs.getString("edition"), date(rs.getDate("effective_from")), date(rs.getDate("effective_to")),
                stringList(rs.getString("alias_json")), rs.getString("status"), rs.getInt("version_no"),
                rs.getString("change_reason")
        ), args.toArray());
    }

    @Transactional
    public ProductMasterDataDtos.ReferenceValue createReferenceValue(
            ProductMasterDataDtos.ReferenceValueSave request) {
        requireManagePermission();
        String type = referenceType(request.referenceType());
        String code = requiredText(request.code(), "规范编码").toUpperCase(Locale.ROOT);
        ensureReferenceCodeAvailable(type, code, null);
        validateDates(request.effectiveFrom(), request.effectiveTo(), "规范值");
        long id = insert("""
                insert into product_reference_value
                (reference_type,code,name,english_name,issuing_body,edition,effective_from,effective_to,
                 alias_json,status,version_no,change_reason)
                values(?,?,?,?,?,?,?,?,?,?,1,?)
                """, type, code, requiredText(request.name(), "规范名称"), text(request.englishName()),
                text(request.issuingBody()), text(request.edition()), sqlDate(request.effectiveFrom()),
                sqlDate(request.effectiveTo()), json(normalizedAliases(code, request.name(), request.aliases())),
                status(request.status()), requiredText(request.changeReason(), "变更原因"));
        replaceAliases(id, type, code, request.name(), request.aliases());
        return referenceValue(id);
    }

    @Transactional
    public ProductMasterDataDtos.ReferenceValue updateReferenceValue(
            Long id, ProductMasterDataDtos.ReferenceValueSave request) {
        requireManagePermission();
        ProductMasterDataDtos.ReferenceValue current = referenceValue(id);
        String type = referenceType(request.referenceType());
        if (!Objects.equals(type, current.referenceType())) {
            throw new BusinessException("规范值类型建立后不能修改");
        }
        if (request.expectedVersionNo() == null
                || !Objects.equals(request.expectedVersionNo(), current.versionNo())) {
            throw new BusinessException("规范值已被其他人更新，请刷新后重试");
        }
        String code = requiredText(request.code(), "规范编码").toUpperCase(Locale.ROOT);
        ensureReferenceCodeAvailable(type, code, id);
        boolean codeChanged = !Objects.equals(code, current.code());
        boolean disabling = "DISABLED".equals(status(request.status()))
                && !"DISABLED".equals(current.status());
        boolean criticalDefinitionChanged =
                !Objects.equals(text(request.issuingBody()), text(current.issuingBody()))
                || !Objects.equals(text(request.edition()), text(current.edition()))
                || !Objects.equals(request.effectiveFrom(), current.effectiveFrom())
                || !Objects.equals(request.effectiveTo(), current.effectiveTo());
        if ((codeChanged || disabling || criticalDefinitionChanged)
                && referenceValueInUse(id, type, current.code())) {
            if (codeChanged) {
                throw new BusinessException(
                        "规范编码已被商品身份引用，不能原地修改；请新建规范值并走受控商品迁移");
            }
            if (criticalDefinitionChanged) {
                throw new BusinessException(
                        "已引用规范值的发布机构、版本和生效期间不可原地修改；请新建规范值版本并受控迁移");
            }
            throw new BusinessException(
                    "规范值已被商品身份引用，不能直接停用；请先完成受控替换和商品迁移");
        }
        validateDates(request.effectiveFrom(), request.effectiveTo(), "规范值");
        int updated = jdbc.update("""
                update product_reference_value
                set code=?,name=?,english_name=?,issuing_body=?,edition=?,effective_from=?,effective_to=?,
                    alias_json=?,status=?,version_no=version_no+1,change_reason=?
                where id=? and version_no=?
                """, code, requiredText(request.name(), "规范名称"), text(request.englishName()),
                text(request.issuingBody()), text(request.edition()), sqlDate(request.effectiveFrom()),
                sqlDate(request.effectiveTo()), json(normalizedAliases(code, request.name(), request.aliases())),
                status(request.status()), requiredText(request.changeReason(), "变更原因"), id,
                request.expectedVersionNo());
        if (updated != 1) throw new BusinessException("规范值已被其他人更新，请刷新后重试");
        replaceAliases(id, type, code, request.name(), request.aliases());
        return referenceValue(id);
    }

    private boolean referenceValueInUse(Long referenceValueId, String referenceType, String currentCode) {
        Integer structuredUses = jdbc.queryForObject("""
                select
                  (select count(*) from product_reference_binding
                    where reference_value_id=? and status='ENABLED')
                  +(select count(*) from product_field_value where reference_value_id=?)
                """, Integer.class, referenceValueId, referenceValueId);
        if (structuredUses != null && structuredUses > 0) return true;
        String legacyColumn = switch (referenceType) {
            case "GRADE" -> "grade";
            case "TEMPER" -> "temper";
            case "STANDARD" -> "standard";
            default -> throw new BusinessException("不支持的规范值类型");
        };
        Integer legacyUses = jdbc.queryForObject(
                "select count(*) from product where lower(trim(" + legacyColumn + "))=lower(trim(?))",
                Integer.class, currentCode);
        return legacyUses != null && legacyUses > 0;
    }

    public ProductMasterDataDtos.ReferenceResolution resolveReference(
            String referenceType, String sourceValue, boolean allowUnresolved) {
        requireViewPermission();
        return resolveReferenceInternal(referenceType, sourceValue, allowUnresolved);
    }

    ProductMasterDataDtos.ReferenceResolution resolveReferenceForProductWrite(
            String referenceType, String sourceValue, boolean allowUnresolved) {
        return resolveReferenceInternal(referenceType, sourceValue, allowUnresolved);
    }

    private ProductMasterDataDtos.ReferenceResolution resolveReferenceInternal(
            String referenceType, String sourceValue, boolean allowUnresolved) {
        String type = referenceType(referenceType);
        String source = requiredText(sourceValue, "待解析值");
        String key = aliasKey(source);
        List<ProductMasterDataDtos.ReferenceResolution> matches = jdbc.query("""
                select v.id,v.reference_type,v.code,v.name
                from product_reference_alias a
                join product_reference_value v on v.id=a.reference_value_id
                where a.reference_type=? and a.alias_key=?
                  and a.status='ENABLED' and v.status='ENABLED'
                order by v.id
                """, (rs, rowNum) -> new ProductMasterDataDtos.ReferenceResolution(
                rs.getLong("id"), rs.getString("reference_type"), source,
                rs.getString("code"), rs.getString("name"), true, List.of()
        ), type, key);
        if (matches.size() == 1) return matches.get(0);
        if (matches.size() > 1) {
            throw new BusinessException("规范别名“" + source + "”对应多个" + referenceTypeLabel(type) + "，请先修复主数据");
        }
        if (!allowUnresolved) {
            throw new BusinessException(referenceTypeLabel(type) + "“" + source + "”尚未建立规范编码或别名");
        }
        return new ProductMasterDataDtos.ReferenceResolution(
                null, type, source, "", "", false,
                List.of(referenceTypeLabel(type) + "未匹配规范库，已按录入文本保留和区分")
        );
    }

    public ProductMasterDataDtos.ReferenceResolution resolveReference(
            ProductMasterDataDtos.ReferenceResolveRequest request) {
        return resolveReference(request.referenceType(), request.sourceValue(),
                Boolean.TRUE.equals(request.allowUnresolved()));
    }

    public ProductMasterDataDtos.IdentitySchema identitySchema(Long seriesId) {
        requireViewPermission();
        SeriesIdentityHead head = requireSeriesIdentityHead(seriesId, false);
        List<ProductMasterDataDtos.IdentityRule> rules = identityRules(seriesId, head.version());
        List<SchemaRevisionRow> revisions = jdbc.query("""
                select content_hash,change_reason,published_at
                from product_identity_schema_revision
                where product_series_id=? and version_no=?
                """, (rs, rowNum) -> new SchemaRevisionRow(
                rs.getString("content_hash"), rs.getString("change_reason"),
                rs.getTimestamp("published_at").toLocalDateTime()
        ), seriesId, head.version());
        SchemaRevisionRow revision = revisions.isEmpty()
                ? new SchemaRevisionRow("", "", null) : revisions.get(0);
        return new ProductMasterDataDtos.IdentitySchema(
                seriesId, head.name(), head.version(), rules, revision.contentHash(),
                revision.changeReason(), revision.publishedAt()
        );
    }

    @Transactional
    void initializeIdentitySchemaForSeries(Long seriesId) {
        SeriesIdentityHead head = requireSeriesIdentityHead(seriesId, true);
        Integer count = jdbc.queryForObject("""
                select count(*) from product_identity_schema_revision
                where product_series_id=? and version_no=?
                """, Integer.class, seriesId, head.version());
        if (count != null && count > 0) return;
        SecurityContext.Principal actor = SecurityContext.get();
        jdbc.update("""
                insert into product_identity_schema_revision
                (product_series_id,version_no,change_reason,content_hash,published_by,published_by_name)
                values(?,?,?,?,?,?)
                """, seriesId, head.version(), "建立系列时自动初始化商品唯一性规则",
                identitySchemaHash(List.of()),
                actor == null ? 0L : actor.userId(),
                actor == null ? "系统" : actor.employeeName());
    }

    /**
     * Keeps SKU duplicate rules in sync with the simple “用于区分商品” checkbox.
     * Operators no longer publish a second identity-rule document after configuring
     * series fields. Existing products are preflighted before a new rule version is
     * made current, so convenience does not weaken duplicate protection.
     */
    @Transactional
    void syncIdentitySchemaFromSeriesFields(Long seriesId, String changeReason) {
        initializeIdentitySchemaForSeries(seriesId);
        SeriesIdentityHead head = requireSeriesIdentityHead(seriesId, true);
        List<NormalizedIdentityRule> desired = jdbc.query("""
                select sf.id,sf.field_name,sf.display_name,sf.required,sf.sort_no
                from product_series_field sf
                where sf.product_series_id=? and sf.sku_dimension=1 and sf.status='ENABLED'
                order by sf.sort_no,sf.id
                """, (rs, rowNum) -> {
            long fieldId = rs.getLong("id");
            String referenceType = referenceTypeForSeriesField(
                    rs.getString("field_name"), rs.getString("display_name"));
            return new NormalizedIdentityRule(
                    "SF:" + fieldId, "SERIES_FIELD", fieldId, referenceType,
                    rs.getBoolean("required"), rs.getInt("sort_no"), "ENABLED");
        }, seriesId);
        List<NormalizedIdentityRule> current = normalizedRules(seriesId, head.version());
        if (current.equals(desired)) return;

        int proposedVersion = head.version() + 1;
        ProductMasterDataDtos.IdentitySchemaPreflightResult preview =
                preflight(seriesId, head.version(), proposedVersion, desired);
        if (preview.collisionCount() > 0) {
            throw new BusinessException(
                    "该字段配置会使已有商品发生" + preview.collisionCount()
                            + "组重复，请先增加可区分规格");
        }
        if (preview.incompleteProductCount() > 0) {
            throw new BusinessException(
                    "该字段配置要求" + preview.incompleteProductCount()
                            + "个已有商品补齐规格；请先补资料，或取消“用于区分商品”");
        }

        SecurityContext.Principal actor = SecurityContext.get();
        long revisionId = insert("""
                insert into product_identity_schema_revision
                (product_series_id,version_no,change_reason,content_hash,published_by,published_by_name)
                values(?,?,?,?,?,?)
                """, seriesId, proposedVersion,
                StringUtils.hasText(changeReason) ? changeReason.trim() : "系列规格自动更新商品唯一性规则",
                identitySchemaHash(desired),
                actor == null ? 0L : actor.userId(),
                actor == null ? "系统" : actor.employeeName());
        for (NormalizedIdentityRule rule : desired) {
            jdbc.update("""
                    insert into product_identity_rule
                    (schema_revision_id,product_series_id,schema_version,dimension_key,source_type,
                     series_field_id,reference_type,required,sort_no,status)
                    values(?,?,?,?,?,?,?,?,?,?)
                    """, revisionId, seriesId, proposedVersion, rule.dimensionKey(),
                    rule.sourceType(), rule.seriesFieldId(), rule.referenceType(),
                    bool(rule.required()), rule.sortNo(), rule.status());
        }
        jdbc.update(
                "update product_series set identity_schema_version=? where id=?",
                proposedVersion, seriesId);
    }

    private String referenceTypeForSeriesField(String fieldName, String displayName) {
        for (String value : List.of(text(fieldName), text(displayName))) {
            String normalized = value.replaceAll("\\s+", "");
            if (Set.of("牌号", "材质牌号", "牌号/材质", "磁材牌号").contains(normalized)) {
                return "GRADE";
            }
            if (Set.of("材料状态", "状态", "软硬状态").contains(normalized)) {
                return "TEMPER";
            }
            if (Set.of("执行标准", "标准").contains(normalized)) {
                return "STANDARD";
            }
        }
        return "";
    }

    public ProductMasterDataDtos.IdentitySchemaPreflightResult preflightIdentitySchema(
            Long seriesId, ProductMasterDataDtos.IdentitySchemaPreflight request) {
        requireViewPermission();
        dataScopeService.assertFullMasterScope("PRODUCT");
        SeriesIdentityHead head = requireSeriesIdentityHead(seriesId, false);
        if (!Objects.equals(request.expectedVersion(), head.version())) {
            throw new BusinessException("商品身份规则已被其他人更新，请刷新后重试");
        }
        List<NormalizedIdentityRule> rules = normalizeIdentityRules(seriesId, request.rules());
        return preflight(seriesId, head.version(), head.version() + 1, rules);
    }

    @Transactional
    public ProductMasterDataDtos.IdentitySchema publishIdentitySchema(
            Long seriesId, ProductMasterDataDtos.IdentitySchemaSave request) {
        requireManagePermission();
        dataScopeService.assertFullMasterScope("PRODUCT");
        SeriesIdentityHead head = requireSeriesIdentityHead(seriesId, true);
        if (!Objects.equals(request.expectedVersion(), head.version())) {
            throw new BusinessException("商品身份规则已被其他人更新，请刷新后重试");
        }
        List<NormalizedIdentityRule> rules = normalizeIdentityRules(seriesId, request.rules());
        ProductMasterDataDtos.IdentitySchemaPreflightResult preview =
                preflight(seriesId, head.version(), head.version() + 1, rules);
        if (preview.collisionCount() > 0) {
            throw new BusinessException("新身份规则会使已有商品发生" + preview.collisionCount() + "组碰撞，禁止发布");
        }
        if (preview.incompleteProductCount() > 0) {
            throw new BusinessException("新身份规则会使" + preview.incompleteProductCount() + "个已有商品缺少必填身份值，禁止发布");
        }
        int version = head.version() + 1;
        String hash = identitySchemaHash(rules);
        SecurityContext.Principal actor = SecurityContext.require();
        long revisionId = insert("""
                insert into product_identity_schema_revision
                (product_series_id,version_no,change_reason,content_hash,published_by,published_by_name)
                values(?,?,?,?,?,?)
                """, seriesId, version, requiredText(request.changeReason(), "变更原因"),
                hash, actor.userId(), actor.employeeName());
        for (NormalizedIdentityRule rule : rules) {
            jdbc.update("""
                    insert into product_identity_rule
                    (schema_revision_id,product_series_id,schema_version,dimension_key,source_type,
                     series_field_id,reference_type,required,sort_no,status)
                    values(?,?,?,?,?,?,?,?,?,?)
                    """, revisionId, seriesId, version, rule.dimensionKey(), rule.sourceType(),
                    id(rule.seriesFieldId()), rule.referenceType(), bool(rule.required()),
                    rule.sortNo(), rule.status());
        }
        jdbc.update("update product_series set identity_schema_version=? where id=?", version, seriesId);
        return identitySchema(seriesId);
    }

    public ProductMasterDataDtos.ProductDuplicateCheck duplicateCheck(
            SimpleCreateRequests.ProductCreate request) {
        requireViewPermission();
        return duplicateCheck(request, null);
    }

    public ProductMasterDataDtos.ProductDuplicateCheck evaluateStoredCurrentIdentity(Long productId) {
        requireViewPermission();
        dataScopeService.assertMasterAllowed("PRODUCT", productId);
        List<ProductIdentityBase> products = jdbc.query("""
                select p.id,p.product_series_id,p.sku,p.grade,p.temper,p.standard,
                       s.identity_schema_version
                from product p join product_series s on s.id=p.product_series_id
                where p.id=?
                """, (rs, rowNum) -> new ProductIdentityBase(
                rs.getLong("id"), rs.getLong("product_series_id"), rs.getString("sku"),
                rs.getString("grade"), rs.getString("temper"), rs.getString("standard"),
                rs.getInt("identity_schema_version")
        ), productId);
        if (products.isEmpty()) throw new BusinessException("商品不存在");
        ProductIdentityBase product = products.get(0);
        IdentityEvaluation evaluation = evaluateStoredProduct(
                product, product.schemaVersion(),
                normalizedRules(product.seriesId(), product.schemaVersion()));
        return new ProductMasterDataDtos.ProductDuplicateCheck(
                evaluation.complete() ? "EVALUATED" : "INCOMPLETE",
                product.id(), product.sku(), "", evaluation.signature(),
                product.schemaVersion(), evaluation.complete(), evaluation.warnings());
    }

    public ProductMasterDataDtos.ProductDuplicateCheck evaluateCandidateIdentity(
            SimpleCreateRequests.ProductCreate request) {
        return evaluateCandidateIdentity(request, null);
    }

    public ProductMasterDataDtos.ProductDuplicateCheck evaluateCandidateIdentity(
            SimpleCreateRequests.ProductCreate request, Integer schemaVersion) {
        requireViewPermission();
        SeriesIdentityHead head = requireSeriesIdentityHead(request.productSeriesId(), false);
        int version = schemaVersion == null ? head.version() : schemaVersion;
        Integer published = jdbc.queryForObject("""
                select count(*) from product_identity_schema_revision
                where product_series_id=? and version_no=?
                """, Integer.class, request.productSeriesId(), version);
        if (published == null || published != 1) {
            throw new BusinessException("指定的商品身份规则版本不存在");
        }
        CandidateIdentity candidate = candidateIdentity(request, version);
        return new ProductMasterDataDtos.ProductDuplicateCheck(
                candidate.complete() ? "EVALUATED" : "INCOMPLETE",
                null, "", "", candidate.signature(), version,
                candidate.complete(), candidate.warnings()
        );
    }

    public ProductMasterDataDtos.ProductDuplicateCheck duplicateCheck(
            SimpleCreateRequests.ProductCreate request, Long excludeProductId) {
        requireViewPermission();
        if (excludeProductId != null && excludeProductId > 0) {
            dataScopeService.assertMasterAllowed("PRODUCT", excludeProductId);
        }
        Long seriesId = request.productSeriesId();
        SeriesIdentityHead head = requireSeriesIdentityHead(seriesId, false);
        CandidateIdentity candidate = candidateIdentity(request, head.version());
        List<Map<String, Object>> skuMatches = StringUtils.hasText(request.sku())
                ? jdbc.queryForList("""
                        select id,sku,name from product
                        where sku=? and id<>? limit 1
                        """, request.sku().trim(), id(excludeProductId))
                : List.of();
        if (!skuMatches.isEmpty()) {
            Map<String, Object> match = skuMatches.get(0);
            return visibleDuplicateOrRedacted(new ProductMasterDataDtos.ProductDuplicateCheck(
                    "SKU_CONFLICT", number(match.get("id")), String.valueOf(match.get("sku")),
                    String.valueOf(match.get("name")), candidate.signature(), head.version(),
                    candidate.complete(), concat(candidate.warnings(), List.of("商品编码已存在"))
            ));
        }
        if (!candidate.complete()) {
            return new ProductMasterDataDtos.ProductDuplicateCheck(
                    "INCOMPLETE", null, "", "", candidate.signature(), head.version(),
                    false, candidate.warnings()
            );
        }
        ProductIdentityBase semanticMatch = semanticDuplicate(
                seriesId, excludeProductId, head.version(), candidate.signature());
        if (semanticMatch != null) {
            return visibleDuplicateOrRedacted(new ProductMasterDataDtos.ProductDuplicateCheck(
                    "EXACT", semanticMatch.id(), semanticMatch.sku(), "",
                    candidate.signature(), head.version(), true,
                    List.of("同产品系列和身份规格的商品已存在（已跨身份规则版本复核）")
            ));
        }
        List<Map<String, Object>> matches = jdbc.queryForList("""
                select id,sku,name from product
                where product_series_id=? and identity_signature=? and id<>? limit 1
                """, seriesId, candidate.signature(), id(excludeProductId));
        if (!matches.isEmpty()) {
            Map<String, Object> match = matches.get(0);
            return visibleDuplicateOrRedacted(new ProductMasterDataDtos.ProductDuplicateCheck(
                    "EXACT", number(match.get("id")), String.valueOf(match.get("sku")),
                    String.valueOf(match.get("name")), candidate.signature(), head.version(),
                    true, List.of("同产品系列和身份规格的商品已存在")
            ));
        }
        return new ProductMasterDataDtos.ProductDuplicateCheck(
                "NONE", null, "", "", candidate.signature(), head.version(),
                true, candidate.warnings()
        );
    }

    private ProductMasterDataDtos.ProductDuplicateCheck visibleDuplicateOrRedacted(
            ProductMasterDataDtos.ProductDuplicateCheck duplicate) {
        try {
            dataScopeService.assertMasterAllowed("PRODUCT", duplicate.existingProductId());
            return duplicate;
        } catch (ForbiddenException ignored) {
            return new ProductMasterDataDtos.ProductDuplicateCheck(
                    duplicate.result(), null, "", "",
                    duplicate.identitySignature(), duplicate.schemaVersion(),
                    duplicate.complete(), concat(
                    duplicate.warnings() == null ? List.of() : duplicate.warnings(),
                    List.of("存在冲突商品，但当前商品数据范围无权查看其详情"))
            );
        }
    }

    /**
     * Called from the single transactional product-creation/update path after field values are stored.
     */
    @Transactional
    public String finalizeProductIdentity(Long productId, String requestedStatus) {
        requireManagePermission();
        return finalizeProductIdentityInternal(productId, requestedStatus, true);
    }

    /**
     * @param adoptCurrentSchema true only for a new product or an explicit series/schema migration;
     *                           ordinary edits keep the product's frozen identity schema version.
     */
    @Transactional
    public String finalizeProductIdentity(
            Long productId, String requestedStatus, boolean adoptCurrentSchema) {
        requireManagePermission();
        return finalizeProductIdentityInternal(productId, requestedStatus, adoptCurrentSchema);
    }

    /**
     * Trusted continuation of the already-authorized product write transaction.
     * Package-private by design: controllers must use the permission-checked public methods.
     */
    String finalizeProductIdentityForProductWrite(
            Long productId, String requestedStatus, boolean adoptCurrentSchema) {
        return finalizeProductIdentityInternal(productId, requestedStatus, adoptCurrentSchema);
    }

    private String finalizeProductIdentityInternal(
            Long productId, String requestedStatus, boolean adoptCurrentSchema) {
        Long seriesId = jdbc.queryForObject(
                "select product_series_id from product where id=?", Long.class, productId);
        if (seriesId == null || seriesId <= 0) throw new BusinessException("商品不存在");
        jdbc.queryForList("select id from product_series where id=? for update", seriesId);
        List<ProductIdentityBase> rows = jdbc.query("""
                select p.id,p.product_series_id,p.sku,p.grade,p.temper,p.standard,
                       case when ?=1 then s.identity_schema_version
                            else p.identity_schema_version_snapshot end identity_schema_version
                from product p join product_series s on s.id=p.product_series_id
                where p.id=? for update
                """, (rs, rowNum) -> new ProductIdentityBase(
                rs.getLong("id"), rs.getLong("product_series_id"), rs.getString("sku"),
                rs.getString("grade"), rs.getString("temper"), rs.getString("standard"),
                rs.getInt("identity_schema_version")
        ), bool(adoptCurrentSchema), productId);
        if (rows.isEmpty()) throw new BusinessException("商品不存在");
        ProductIdentityBase product = rows.get(0);
        IdentityEvaluation evaluation = evaluateStoredProduct(product, product.schemaVersion(),
                normalizedRules(product.seriesId(), product.schemaVersion()));
        String identityStatus = identityStatus(requestedStatus);
        if ("PUBLISHED".equals(identityStatus) && !evaluation.complete()) {
            throw new BusinessException("商品必填规格不完整，不能用于经营：" + String.join("；", evaluation.warnings()));
        }
        if (evaluation.complete()) {
            ProductIdentityBase semanticMatch = semanticDuplicate(
                    product.seriesId(), productId, product.schemaVersion(), evaluation.signature());
            if (semanticMatch != null) {
                throw new BusinessException(
                        "相同经营规格的商品已存在（商品 #" + semanticMatch.id() + "），请维护已有商品");
            }
            List<Long> duplicateProductIds = jdbc.query("""
                    select id from product
                    where product_series_id=? and identity_signature=? and id<>?
                    limit 1
                    """, (rs, rowNum) -> rs.getLong(1),
                    product.seriesId(), evaluation.signature(), productId);
            if (!duplicateProductIds.isEmpty()) {
                throw new BusinessException(
                        "相同经营规格的商品已存在（商品 #" + duplicateProductIds.get(0) + "），请维护已有商品");
            }
        }
        String persistedSignature = "PENDING_REVIEW".equals(identityStatus)
                ? null : (evaluation.complete() ? evaluation.signature() : null);
        try {
            jdbc.update("""
                    update product
                    set identity_schema_version_snapshot=?,identity_status=?,identity_signature=?
                    where id=?
                    """, product.schemaVersion(), identityStatus,
                    persistedSignature, productId);
        } catch (DuplicateKeyException ex) {
            throw new BusinessException("相同产品系列和身份规格的商品已被其他操作创建，请刷新后核对");
        }
        synchronizeReferenceBindings(productId, product.schemaVersion());
        return persistedSignature == null ? "" : persistedSignature;
    }

    @Transactional
    public ProductMasterDataDtos.ProductIdentityPublication publishProductIdentity(Long productId) {
        requireManagePermission();
        String signature = finalizeProductIdentityInternal(productId, "PUBLISHED", false);
        List<ProductMasterDataDtos.ProductIdentityPublication> rows = jdbc.query("""
                select id,identity_schema_version_snapshot,identity_status,identity_signature
                from product where id=?
                """, (rs, rowNum) -> new ProductMasterDataDtos.ProductIdentityPublication(
                rs.getLong("id"), rs.getInt("identity_schema_version_snapshot"),
                rs.getString("identity_status"), rs.getString("identity_signature")
        ), productId);
        if (rows.isEmpty()) throw new BusinessException("商品不存在");
        if (!Objects.equals(signature, rows.get(0).identitySignature())) {
            throw new BusinessException("商品身份发布结果校验失败");
        }
        return rows.get(0);
    }

    public List<ProductMasterDataDtos.AllowedUnit> allowedUnits(Long seriesId, String purpose) {
        requireProductEntryViewPermission();
        requireSeriesIdentityHead(seriesId, false);
        StringBuilder sql = new StringBuilder("""
                select a.id,a.product_series_id,a.purpose,a.unit_id,a.unit_name_snapshot,
                       u.name unit_name,u.abbreviation,a.is_default,a.sort_no,a.status
                from product_series_allowed_unit a
                join unit u on u.id=a.unit_id
                where a.product_series_id=?
                """);
        List<Object> args = new ArrayList<>();
        args.add(seriesId);
        if (StringUtils.hasText(purpose)) {
            sql.append(" and a.purpose=?");
            args.add(unitPurpose(purpose));
        }
        sql.append(" order by a.purpose,a.is_default desc,a.sort_no,a.id");
        return jdbc.query(sql.toString(), (rs, rowNum) -> new ProductMasterDataDtos.AllowedUnit(
                rs.getLong("id"), rs.getLong("product_series_id"), rs.getString("purpose"),
                rs.getLong("unit_id"), rs.getString("unit_name"), rs.getString("abbreviation"),
                rs.getBoolean("is_default"), rs.getInt("sort_no"), rs.getString("status")
        ), args.toArray());
    }

    @Transactional
    public ProductMasterDataDtos.AllowedUnit saveAllowedUnit(
            Long seriesId, ProductMasterDataDtos.AllowedUnitSave request) {
        requireManagePermission();
        requireSeriesIdentityHead(seriesId, true);
        String purpose = unitPurpose(request.purpose());
        UnitRow unit = requireUnit(request.unitId());
        String requestedStatus = status(request.status());
        List<Map<String, Object>> existing = jdbc.queryForList("""
                select id,status from product_series_allowed_unit
                where product_series_id=? and purpose=? and unit_id=? for update
                """, seriesId, purpose, unit.id());
        if ("DISABLED".equals(requestedStatus)) {
            if (existing.isEmpty()) throw new BusinessException("系列可用单位不存在，无需停用");
            deactivateAllowedUnit(seriesId, number(existing.get(0).get("id")));
            return allowedUnits(seriesId, purpose).stream()
                    .filter(row -> Objects.equals(row.unitId(), unit.id())).findFirst().orElseThrow();
        }
        Integer enabledCount = jdbc.queryForObject("""
                select count(*) from product_series_allowed_unit
                where product_series_id=? and purpose=? and status='ENABLED'
                """, Integer.class, seriesId, purpose);
        boolean makeDefault = Boolean.TRUE.equals(request.defaultUnit())
                || enabledCount == null || enabledCount == 0;
        if (makeDefault) {
            jdbc.update("""
                    update product_series_allowed_unit set is_default=0
                    where product_series_id=? and purpose=?
                    """, seriesId, purpose);
        }
        jdbc.update("""
                insert into product_series_allowed_unit
                (product_series_id,purpose,unit_id,unit_name_snapshot,is_default,sort_no,status)
                values(?,?,?,?,?,?,?)
                on duplicate key update unit_name_snapshot=values(unit_name_snapshot),
                  is_default=values(is_default),sort_no=values(sort_no),status=values(status)
                """, seriesId, purpose, unit.id(), unit.displayName(), bool(makeDefault),
                request.sortNo() == null ? 0 : request.sortNo(), "ENABLED");
        return allowedUnits(seriesId, purpose).stream()
                .filter(row -> Objects.equals(row.unitId(), unit.id())).findFirst().orElseThrow();
    }

    @Transactional
    public void deleteAllowedUnit(Long seriesId, Long allowedUnitId) {
        requireManagePermission();
        requireSeriesIdentityHead(seriesId, true);
        deactivateAllowedUnit(seriesId, allowedUnitId);
    }

    public List<ProductMasterDataDtos.SourceDocument> sourceDocuments(String status) {
        requireViewPermission();
        String condition = StringUtils.hasText(status) ? " where d.status=? " : " ";
        Object[] args = StringUtils.hasText(status) ? new Object[]{status(status)} : new Object[0];
        return jdbc.query("""
                select d.id,d.document_code,d.title,d.source_type,d.current_revision_id,
                       d.current_revision_no,d.status,r.source_url,r.file_name,r.file_sha256,
                       r.mime_type,r.file_size,r.publication_date,r.internal_version,r.downloaded_at,
                       cast(r.content_snapshot_json as char) content_snapshot_json,
                       r.change_reason,r.published_at
                from product_source_document d
                left join product_source_document_revision r on r.id=d.current_revision_id
                """ + condition + " order by d.updated_at desc,d.id desc limit 1000",
                (rs, rowNum) -> sourceDocumentRow(rs), args);
    }

    public List<ProductMasterDataDtos.SourceDocumentRevision> sourceDocumentRevisions(
            Long documentId) {
        requireViewPermission();
        requireCount("select count(*) from product_source_document where id=?",
                documentId, "来源资料不存在");
        return jdbc.query("""
                select r.id,r.document_id,r.document_code_snapshot document_code,
                       r.revision_no,r.title_snapshot,
                       r.source_type_snapshot,r.source_url,r.file_name,r.file_sha256,r.mime_type,
                       r.file_size,r.publication_date,r.internal_version,r.downloaded_at,
                       cast(r.content_snapshot_json as char) content_snapshot_json,
                       r.head_status_snapshot,
                       (r.document_code_snapshot is not null
                        and r.head_status_snapshot is not null) head_snapshot_available,
                       r.status revision_status,r.change_reason,
                       r.published_by,r.published_by_name,r.published_at,
                       r.id=d.current_revision_id current_revision
                from product_source_document_revision r
                join product_source_document d on d.id=r.document_id
                where r.document_id=?
                order by r.revision_no desc,r.id desc
                """, (rs, rowNum) -> new ProductMasterDataDtos.SourceDocumentRevision(
                rs.getLong("id"), rs.getLong("document_id"), rs.getString("document_code"),
                rs.getInt("revision_no"), rs.getString("title_snapshot"),
                rs.getString("source_type_snapshot"), rs.getString("source_url"),
                rs.getString("file_name"), rs.getString("file_sha256"),
                rs.getString("mime_type"), rs.getLong("file_size"),
                date(rs.getDate("publication_date")), rs.getString("internal_version"),
                rs.getTimestamp("downloaded_at") == null
                        ? null : rs.getTimestamp("downloaded_at").toLocalDateTime(),
                rs.getString("content_snapshot_json"), rs.getString("head_status_snapshot"),
                rs.getBoolean("head_snapshot_available"), rs.getString("revision_status"),
                rs.getString("change_reason"), zeroToNull(rs.getLong("published_by")),
                rs.getString("published_by_name"),
                rs.getTimestamp("published_at") == null
                        ? null : rs.getTimestamp("published_at").toLocalDateTime(),
                rs.getBoolean("current_revision")
        ), documentId);
    }

    @Transactional
    public ProductMasterDataDtos.SourceDocument createSourceDocument(
            ProductMasterDataDtos.SourceDocumentSave request) {
        requireManagePermission();
        String code = StringUtils.hasText(request.documentCode())
                ? request.documentCode().trim().toUpperCase(Locale.ROOT)
                : "SRC-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT);
        long id = insert("""
                insert into product_source_document(document_code,title,source_type,status)
                values(?,?,?,?)
                """, code, requiredText(request.title(), "资料标题"),
                sourceType(request.sourceType()), status(request.status()));
        publishSourceRevision(id, 0, request);
        return sourceDocument(id);
    }

    @Transactional
    public ProductMasterDataDtos.SourceDocument updateSourceDocument(
            Long documentId, ProductMasterDataDtos.SourceDocumentSave request) {
        requireManagePermission();
        List<Map<String, Object>> heads = jdbc.queryForList("""
                select current_revision_no,lock_version from product_source_document
                where id=? for update
                """, documentId);
        if (heads.isEmpty()) throw new BusinessException("来源资料不存在");
        int currentRevision = ((Number) heads.get(0).get("current_revision_no")).intValue();
        if (request.expectedRevisionNo() == null
                || request.expectedRevisionNo() != currentRevision) {
            throw new BusinessException("来源资料已被其他人更新，请刷新后重试");
        }
        jdbc.update("""
                update product_source_document set title=?,source_type=?,status=? where id=?
                """, requiredText(request.title(), "资料标题"), sourceType(request.sourceType()),
                status(request.status()), documentId);
        publishSourceRevision(documentId, currentRevision, request);
        return sourceDocument(documentId);
    }

    public List<ProductMasterDataDtos.SourceLink> sourceLinks(String targetType, Long targetId) {
        requireViewPermission();
        String normalizedTargetType = validateSourceTarget(targetType, targetId, false);
        return jdbc.query("""
                select l.id,l.document_revision_id,r.document_id,r.title_snapshot document_title,
                       r.revision_no document_revision_no,l.target_type,l.target_id,l.page_no,
                       l.source_locator,l.raw_value,l.normalized_value,l.confidence,l.review_status,
                       l.review_comment,l.reviewed_by,l.reviewed_by_name,l.reviewed_at
                from product_source_link l
                join product_source_document_revision r on r.id=l.document_revision_id
                where l.target_type=? and l.target_id=? order by l.id
                """, (rs, rowNum) -> sourceLinkRow(rs)
        , normalizedTargetType, targetId);
    }

    @Transactional
    public ProductMasterDataDtos.SourceLink createSourceLink(ProductMasterDataDtos.SourceLinkSave request) {
        requireManagePermission();
        requireCount("select count(*) from product_source_document_revision where id=?",
                request.documentRevisionId(), "来源资料版本不存在");
        String targetType = validateSourceTarget(request.targetType(), request.targetId(), true);
        long id = insert("""
                insert into product_source_link
                (document_revision_id,target_type,target_id,page_no,source_locator,raw_value,
                 normalized_value,confidence,review_status)
                values(?,?,?,?,?,?,?,?,'PENDING')
                """, request.documentRevisionId(), targetType,
                request.targetId(), request.pageNo() == null ? 0 : request.pageNo(),
                text(request.sourceLocator()), text(request.rawValue()), text(request.normalizedValue()),
                request.confidence());
        return sourceLink(id);
    }

    @Transactional
    public ProductMasterDataDtos.SourceLink reviewSourceLink(
            Long sourceLinkId, ProductMasterDataDtos.SourceLinkReview request) {
        SecurityContext.Principal actor = requireManagePermission();
        String reviewStatus = requiredText(request.reviewStatus(), "复核结果")
                .toUpperCase(Locale.ROOT);
        if (!Set.of("APPROVED", "REJECTED").contains(reviewStatus)) {
            throw new BusinessException("来源证据复核结果只支持APPROVED或REJECTED");
        }
        String comment = text(request.reviewComment());
        if ("REJECTED".equals(reviewStatus) && !StringUtils.hasText(comment)) {
            throw new BusinessException("驳回来源证据必须填写原因");
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select review_status,target_type,target_id
                from product_source_link where id=? for update
                """, sourceLinkId);
        if (rows.isEmpty()) throw new BusinessException("来源证据不存在");
        validateSourceTarget(String.valueOf(rows.get(0).get("target_type")),
                number(rows.get(0).get("target_id")), true);
        String previousStatus = String.valueOf(rows.get(0).get("review_status"));
        jdbc.update("""
                insert into product_source_link_review
                (source_link_id,previous_status,review_status,review_comment,
                 reviewed_by,reviewed_by_name)
                values(?,?,?,?,?,?)
                """, sourceLinkId, previousStatus, reviewStatus, comment,
                actor.userId(), actor.employeeName());
        jdbc.update("""
                update product_source_link
                set review_status=?,review_comment=?,reviewed_by=?,reviewed_by_name=?,
                    reviewed_at=current_timestamp(6)
                where id=?
                """, reviewStatus, comment, actor.userId(), actor.employeeName(), sourceLinkId);
        return sourceLink(sourceLinkId);
    }

    public List<ProductMasterDataDtos.SourceLinkReviewHistory> sourceLinkReviewHistory(
            Long sourceLinkId) {
        requireViewPermission();
        List<Map<String, Object>> links = jdbc.queryForList("""
                select target_type,target_id from product_source_link where id=?
                """, sourceLinkId);
        if (links.isEmpty()) throw new BusinessException("来源证据不存在");
        validateSourceTarget(String.valueOf(links.get(0).get("target_type")),
                number(links.get(0).get("target_id")), false);
        return jdbc.query("""
                select id,source_link_id,previous_status,review_status,review_comment,
                       reviewed_by,reviewed_by_name,reviewed_at
                from product_source_link_review
                where source_link_id=?
                order by reviewed_at desc,id desc
                """, (rs, rowNum) -> new ProductMasterDataDtos.SourceLinkReviewHistory(
                rs.getLong("id"), rs.getLong("source_link_id"),
                rs.getString("previous_status"), rs.getString("review_status"),
                rs.getString("review_comment"), zeroToNull(rs.getLong("reviewed_by")),
                rs.getString("reviewed_by_name"),
                rs.getTimestamp("reviewed_at").toLocalDateTime()
        ), sourceLinkId);
    }

    private ProductMasterDataDtos.IdentitySchemaPreflightResult preflight(
            Long seriesId, int currentVersion, int proposedVersion, List<NormalizedIdentityRule> rules) {
        List<ProductIdentityBase> products = storedProducts(seriesId);
        Map<String, List<ProductIdentityBase>> bySignature = new LinkedHashMap<>();
        int incomplete = 0;
        for (ProductIdentityBase product : products) {
            IdentityEvaluation evaluation = evaluateStoredProduct(product, proposedVersion, rules);
            if (!evaluation.complete()) {
                incomplete++;
                continue;
            }
            bySignature.computeIfAbsent(evaluation.signature(), ignored -> new ArrayList<>()).add(product);
        }
        List<ProductMasterDataDtos.IdentityCollision> collisions = bySignature.entrySet().stream()
                .filter(entry -> entry.getValue().size() > 1)
                .map(entry -> new ProductMasterDataDtos.IdentityCollision(
                        entry.getKey(), entry.getValue().stream().map(ProductIdentityBase::id).toList(),
                        entry.getValue().stream().map(ProductIdentityBase::sku).toList()
                )).toList();
        List<String> warnings = new ArrayList<>();
        if (!collisions.isEmpty()) warnings.add("新规则会使已有商品身份碰撞，必须先补充区分字段");
        if (incomplete > 0) warnings.add("已有商品缺少新规则要求的必填身份值");
        warnings.add("发布只影响后续建档；存量商品必须通过受控补资料/重发布流程迁移，历史单据不改写");
        return new ProductMasterDataDtos.IdentitySchemaPreflightResult(
                seriesId, currentVersion, proposedVersion, collisions.size(), incomplete, collisions, warnings
        );
    }

    private CandidateIdentity candidateIdentity(SimpleCreateRequests.ProductCreate request, int schemaVersion) {
        List<NormalizedIdentityRule> rules = normalizedRules(request.productSeriesId(), schemaVersion);
        Map<Long, String> fieldValues = new HashMap<>();
        if (request.fieldValues() != null) {
            for (SimpleCreateRequests.ProductFieldValueCreate value : request.fieldValues()) {
                if (value != null && value.seriesFieldId() != null) {
                    fieldValues.put(value.seriesFieldId(), value.value() == null ? "" : String.valueOf(value.value()).trim());
                }
            }
        }
        Map<Long, FieldTypeRow> fieldTypes = fieldTypes(rules);
        List<ProductIdentitySignature.Part> parts = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (rules.isEmpty()) {
            parts.add(new ProductIdentitySignature.Part(
                    "SKU", text(request.sku()).toUpperCase(Locale.ROOT)));
        }
        for (NormalizedIdentityRule rule : rules) {
            String value = switch (rule.sourceType()) {
                case "LEGACY_COLUMN" -> switch (rule.dimensionKey()) {
                    case "GRADE" -> text(request.grade());
                    case "TEMPER" -> text(request.temper());
                    case "STANDARD" -> text(request.standard());
                    default -> "";
                };
                case "SERIES_FIELD" -> canonicalFieldValue(
                        fieldValues.get(rule.seriesFieldId()), fieldTypes.get(rule.seriesFieldId()));
                default -> "";
            };
            if (StringUtils.hasText(rule.referenceType()) && StringUtils.hasText(value)) {
                ProductMasterDataDtos.ReferenceResolution resolution =
                        resolveReferenceInternal(rule.referenceType(), value, true);
                if (resolution.resolved()) {
                    value = resolution.normalizedCode();
                } else {
                    warnings.addAll(resolution.warnings());
                }
            }
            if (rule.required() && !StringUtils.hasText(value)) {
                warnings.add("缺少必填规格“" + identityRuleLabel(rule, fieldTypes) + "”");
            }
            parts.add(new ProductIdentitySignature.Part(
                    rule.dimensionKey(), identityValue(rule, value)));
        }
        boolean complete = warnings.stream().noneMatch(item ->
                item.startsWith("缺少必填规格"));
        String signature = complete
                ? ProductIdentitySignature.calculate(schemaVersion, parts) : "";
        return new CandidateIdentity(signature, complete, warnings);
    }

    private IdentityEvaluation evaluateStoredProduct(
            ProductIdentityBase product, int schemaVersion, List<NormalizedIdentityRule> rules) {
        Map<Long, StoredFieldValue> values = storedFieldValues(product.id());
        Map<String, StoredReferenceBinding> legacyBindings =
                storedReferenceBindings(product.id());
        Map<Long, FieldTypeRow> fieldTypes = fieldTypes(rules);
        List<ProductIdentitySignature.Part> parts = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (rules.isEmpty()) {
            parts.add(new ProductIdentitySignature.Part(
                    "SKU", text(product.sku()).toUpperCase(Locale.ROOT)));
        }
        for (NormalizedIdentityRule rule : rules) {
            String value;
            if ("LEGACY_COLUMN".equals(rule.sourceType())) {
                value = switch (rule.dimensionKey()) {
                    case "GRADE" -> product.grade();
                    case "TEMPER" -> product.temper();
                    case "STANDARD" -> product.standard();
                    default -> "";
                };
            } else if ("SERIES_FIELD".equals(rule.sourceType())) {
                StoredFieldValue stored = values.get(rule.seriesFieldId());
                value = stored == null ? "" : stored.canonicalValue();
            } else {
                value = structuredValue(product.id(), rule.dimensionKey());
            }
            if (StringUtils.hasText(rule.referenceType()) && StringUtils.hasText(value)) {
                StoredReferenceBinding binding =
                        "LEGACY_COLUMN".equals(rule.sourceType())
                                ? legacyBindings.get(rule.referenceType()) : null;
                if (binding != null
                        && (sameReferenceText(binding.sourceValue(), value)
                            || sameReferenceText(binding.normalizedCode(), value))) {
                    value = binding.normalizedCode();
                } else {
                    ProductMasterDataDtos.ReferenceResolution resolution =
                            resolveReferenceInternal(rule.referenceType(), value, true);
                    if (resolution.resolved()) {
                        value = resolution.normalizedCode();
                    } else {
                        warnings.addAll(resolution.warnings());
                    }
                }
            }
            if (rule.required() && !StringUtils.hasText(value)) {
                warnings.add("缺少必填规格“" + identityRuleLabel(rule, fieldTypes) + "”");
            }
            parts.add(new ProductIdentitySignature.Part(
                    rule.dimensionKey(), identityValue(rule, value)));
        }
        boolean complete = warnings.stream().noneMatch(item ->
                item.startsWith("缺少必填规格"));
        return new IdentityEvaluation(
                complete ? ProductIdentitySignature.calculate(schemaVersion, parts) : "",
                complete, warnings
        );
    }

    private ProductIdentityBase semanticDuplicate(
            Long seriesId, Long excludeProductId, int schemaVersion, String signature) {
        if (!StringUtils.hasText(signature)) return null;
        List<NormalizedIdentityRule> rules = normalizedRules(seriesId, schemaVersion);
        for (ProductIdentityBase existing : storedProducts(seriesId)) {
            if (Objects.equals(existing.id(), excludeProductId)) continue;
            IdentityEvaluation evaluation =
                    evaluateStoredProduct(existing, schemaVersion, rules);
            if (evaluation.complete() && Objects.equals(evaluation.signature(), signature)) {
                return existing;
            }
        }
        return null;
    }

    private String structuredValue(Long productId, String dimensionKey) {
        if ("PORTS".equalsIgnoreCase(dimensionKey)) {
            List<Map<String, Object>> portRows = jdbc.queryForList("""
                    select port_no,port_role,connection_type,gender,nominal_size,
                           nominal_size_mm,pressure_class,angle_degree,standard_code,
                           material_code
                    from product_port
                    where product_id=? and status='ENABLED'
                    order by port_no,sort_no,id
                    """, productId);
            int expectedCount = expectedPortCount(productId);
            if (portRows.size() != expectedCount) return "";
            List<String> ports = new ArrayList<>();
            for (int index = 0; index < portRows.size(); index++) {
                Map<String, Object> port = portRows.get(index);
                if (number(port.get("port_no")) != index + 1
                        || !StringUtils.hasText(text(port.get("connection_type")))
                        || !StringUtils.hasText(text(port.get("nominal_size")))) {
                    return "";
                }
                ports.add(String.join(":",
                        String.valueOf(number(port.get("port_no"))),
                        canonicalStructuredText(text(port.get("port_role"))),
                        canonicalStructuredText(text(port.get("connection_type"))),
                        canonicalStructuredText(text(port.get("gender"))),
                        canonicalStructuredText(text(port.get("nominal_size"))),
                        canonicalStructuredNumber(decimal(port.get("nominal_size_mm"))),
                        canonicalStructuredText(text(port.get("pressure_class"))),
                        canonicalStructuredNumber(decimal(port.get("angle_degree"))),
                        canonicalStructuredText(text(port.get("standard_code"))),
                        canonicalStructuredText(text(port.get("material_code")))));
            }
            return String.join("|", ports);
        }
        if ("CONDUCTOR".equalsIgnoreCase(dimensionKey)) {
            List<Map<String, Object>> constructions = jdbc.queryForList("""
                    select id,construction_type,strand_count,construction_signature
                    from conductor_construction
                    where product_id=? and status='ENABLED'
                    """, productId);
            if (constructions.size() != 1) return "";
            Map<String, Object> construction = constructions.get(0);
            int strandCount = number(construction.get("strand_count")).intValue();
            if (!StringUtils.hasText(text(construction.get("construction_type")))
                    || strandCount <= 0
                    || !StringUtils.hasText(text(construction.get("construction_signature")))) {
                return "";
            }
            List<Map<String, Object>> components = jdbc.queryForList("""
                    select layer_no,strand_count,diameter_mm,thickness_mm
                    from conductor_construction_component
                    where conductor_construction_id=? and status='ENABLED'
                    order by layer_no,sort_no,id
                    """, number(construction.get("id")));
            if (components.isEmpty()) return "";
            Set<Integer> layers = new LinkedHashSet<>();
            int componentStrands = 0;
            for (Map<String, Object> component : components) {
                int layer = number(component.get("layer_no")).intValue();
                int strands = number(component.get("strand_count")).intValue();
                BigDecimal diameter = decimal(component.get("diameter_mm"));
                BigDecimal thickness = decimal(component.get("thickness_mm"));
                if (layer <= 0 || strands <= 0
                        || ((diameter == null || diameter.compareTo(BigDecimal.ZERO) <= 0)
                            && (thickness == null || thickness.compareTo(BigDecimal.ZERO) <= 0))) {
                    return "";
                }
                layers.add(layer);
                componentStrands += strands;
            }
            int layerNo = 1;
            for (Integer layer : layers) {
                if (layer == null || layer != layerNo++) return "";
            }
            if (componentStrands != strandCount) return "";
            return text(construction.get("construction_signature"));
        }
        return "";
    }

    private int expectedPortCount(Long productId) {
        List<String> types = jdbc.query("""
                select coalesce(nullif(pfv.normalized_code,''),nullif(pfv.value_text,''),'')
                from product_field_value pfv
                join product_series_field sf on sf.id=pfv.series_field_id
                where pfv.product_id=? and sf.status='ENABLED'
                  and sf.field_name in ('管件类型','阀门类型')
                order by sf.sort_no,sf.id
                """, (rs, rowNum) -> rs.getString(1), productId);
        String type = types.isEmpty() ? "" : canonicalStructuredText(types.get(0));
        if (type.contains("四通") || type.contains("cross")) return 4;
        if (type.contains("三通") || type.contains("tee")) return 3;
        return 2;
    }

    private String canonicalStructuredText(String value) {
        return StringUtils.hasText(value)
                ? value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT) : "";
    }

    private String canonicalStructuredNumber(BigDecimal value) {
        return value == null ? "" : value.setScale(10).toPlainString();
    }

    private String identityValue(NormalizedIdentityRule rule, String value) {
        String normalized = text(value).trim();
        if ("LEGACY_COLUMN".equals(rule.sourceType()) || StringUtils.hasText(rule.referenceType())) {
            return normalized.toLowerCase(Locale.ROOT);
        }
        return normalized;
    }

    private List<NormalizedIdentityRule> normalizeIdentityRules(
            Long seriesId, List<ProductMasterDataDtos.IdentityRuleSave> input) {
        if (input == null || input.isEmpty()) throw new BusinessException("商品身份规则不能为空");
        Set<String> keys = new HashSet<>();
        List<NormalizedIdentityRule> result = new ArrayList<>();
        int index = 0;
        for (ProductMasterDataDtos.IdentityRuleSave item : input) {
            String key = requiredText(item.dimensionKey(), "身份维度编码").trim().toUpperCase(Locale.ROOT);
            if (!keys.add(key)) throw new BusinessException("身份维度“" + key + "”重复");
            String sourceType = requiredText(item.sourceType(), "身份维度来源").toUpperCase(Locale.ROOT);
            if (!IDENTITY_SOURCE_TYPES.contains(sourceType)) {
                throw new BusinessException("不支持的身份维度来源：" + sourceType);
            }
            if ("STRUCTURED".equals(sourceType) && !STRUCTURED_IDENTITY_DIMENSIONS.contains(key)) {
                throw new BusinessException(
                        "结构化身份维度“" + key + "”尚未实现；当前仅支持PORTS和CONDUCTOR");
            }
            long seriesFieldId = id(item.seriesFieldId());
            if ("SERIES_FIELD".equals(sourceType)) {
                Integer count = jdbc.queryForObject("""
                        select count(*) from product_series_field
                        where id=? and product_series_id=? and status='ENABLED'
                        """, Integer.class, seriesFieldId, seriesId);
                if (count == null || count != 1) throw new BusinessException("身份规则引用的系列字段不存在或已停用");
            } else if (seriesFieldId > 0) {
                throw new BusinessException("只有系列字段来源可以填写seriesFieldId");
            }
            String refType = StringUtils.hasText(item.referenceType())
                    ? referenceType(item.referenceType()) : "";
            int sortNo = item.sortNo() == null ? (index + 1) * 10 : item.sortNo();
            result.add(new NormalizedIdentityRule(
                    key, sourceType, seriesFieldId, refType,
                    Boolean.TRUE.equals(item.required()), sortNo, status(item.status())
            ));
            index++;
        }
        result.sort(Comparator.comparingInt(NormalizedIdentityRule::sortNo)
                .thenComparing(NormalizedIdentityRule::dimensionKey));
        return result;
    }

    private List<NormalizedIdentityRule> normalizedRules(Long seriesId, int version) {
        return jdbc.query("""
                select dimension_key,source_type,series_field_id,reference_type,required,sort_no,status
                from product_identity_rule
                where product_series_id=? and schema_version=? and status='ENABLED'
                order by sort_no,id
                """, (rs, rowNum) -> new NormalizedIdentityRule(
                rs.getString("dimension_key"), rs.getString("source_type"),
                rs.getLong("series_field_id"), rs.getString("reference_type"),
                rs.getBoolean("required"), rs.getInt("sort_no"), rs.getString("status")
        ), seriesId, version);
    }

    private List<ProductMasterDataDtos.IdentityRule> identityRules(Long seriesId, int version) {
        return jdbc.query("""
                select id,dimension_key,source_type,series_field_id,reference_type,required,sort_no,status
                from product_identity_rule
                where product_series_id=? and schema_version=?
                order by sort_no,id
                """, (rs, rowNum) -> new ProductMasterDataDtos.IdentityRule(
                rs.getLong("id"), rs.getString("dimension_key"), rs.getString("source_type"),
                zeroToNull(rs.getLong("series_field_id")), rs.getString("reference_type"),
                rs.getBoolean("required"), rs.getInt("sort_no"), rs.getString("status")
        ), seriesId, version);
    }

    private Map<Long, FieldTypeRow> fieldTypes(List<NormalizedIdentityRule> rules) {
        List<Long> ids = rules.stream().filter(item -> item.seriesFieldId() > 0)
                .map(NormalizedIdentityRule::seriesFieldId).distinct().toList();
        if (ids.isEmpty()) return Map.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        Map<Long, FieldTypeRow> result = new HashMap<>();
        for (Map<String, Object> row : jdbc.queryForList("""
                select sf.id,cf.field_type,
                       coalesce(nullif(sf.display_name,''),sf.field_name) display_name
                from product_series_field sf
                join product_custom_field cf on cf.id=sf.field_id
                where sf.id in (%s)
                """.formatted(placeholders), ids.toArray())) {
            result.put(number(row.get("id")), new FieldTypeRow(
                    text(row.get("field_type")), text(row.get("display_name"))));
        }
        return result;
    }

    private String identityRuleLabel(
            NormalizedIdentityRule rule, Map<Long, FieldTypeRow> fieldTypes) {
        FieldTypeRow field = fieldTypes.get(rule.seriesFieldId());
        if (field != null && StringUtils.hasText(field.displayName())) {
            return field.displayName();
        }
        return switch (rule.dimensionKey()) {
            case "GRADE" -> "牌号";
            case "TEMPER" -> "材料状态";
            case "STANDARD" -> "执行标准";
            default -> rule.dimensionKey();
        };
    }

    private String canonicalFieldValue(String value, FieldTypeRow fieldType) {
        if (!StringUtils.hasText(value)) return "";
        if (fieldType != null && "NUMBER".equals(fieldType.fieldType())) {
            try {
                return new BigDecimal(value.trim()).setScale(10).toPlainString();
            } catch (NumberFormatException ex) {
                return value.trim();
            }
        }
        return value.trim();
    }

    private Map<Long, StoredFieldValue> storedFieldValues(Long productId) {
        Map<Long, StoredFieldValue> result = new HashMap<>();
        jdbc.query("""
                select pfv.series_field_id,cf.field_type,pfv.value_text,pfv.value_decimal,
                       pfv.normalized_code
                from product_field_value pfv
                join product_series_field sf on sf.id=pfv.series_field_id
                join product_custom_field cf on cf.id=sf.field_id
                where pfv.product_id=?
                """, rs -> {
            String canonical;
            if (StringUtils.hasText(rs.getString("normalized_code"))) {
                canonical = rs.getString("normalized_code");
            } else if ("NUMBER".equals(rs.getString("field_type"))
                    && rs.getBigDecimal("value_decimal") != null) {
                canonical = rs.getBigDecimal("value_decimal").setScale(10).toPlainString();
            } else {
                canonical = text(rs.getString("value_text")).trim();
            }
            result.put(rs.getLong("series_field_id"), new StoredFieldValue(canonical));
        }, productId);
        return result;
    }

    private List<ProductIdentityBase> storedProducts(Long seriesId) {
        return jdbc.query("""
                select id,product_series_id,sku,grade,temper,standard,identity_schema_version_snapshot
                from product where product_series_id=? order by id
                """, (rs, rowNum) -> new ProductIdentityBase(
                rs.getLong("id"), rs.getLong("product_series_id"), rs.getString("sku"),
                rs.getString("grade"), rs.getString("temper"), rs.getString("standard"),
                rs.getInt("identity_schema_version_snapshot")
        ), seriesId);
    }

    private void synchronizeReferenceBindings(Long productId, int schemaVersion) {
        Map<String, StoredReferenceBinding> existing = storedReferenceBindings(productId);
        jdbc.update("""
                update product_reference_binding set status='DISABLED'
                where product_id=? and status='ENABLED'
                """, productId);
        Map<String, Object> legacy = jdbc.queryForMap("""
                select grade,temper,standard from product where id=?
                """, productId);
        for (Map.Entry<String, String> item : Map.of(
                "GRADE", text(legacy.get("grade")),
                "TEMPER", text(legacy.get("temper")),
                "STANDARD", text(legacy.get("standard"))).entrySet()) {
            if (!StringUtils.hasText(item.getValue())) continue;
            StoredReferenceBinding previous = existing.get(item.getKey());
            if (previous != null
                    && (sameReferenceText(previous.sourceValue(), item.getValue())
                        || sameReferenceText(previous.normalizedCode(), item.getValue()))) {
                upsertReferenceBinding(
                        productId, item.getKey(), previous.referenceValueId(),
                        item.getValue(), previous.normalizedCode(), schemaVersion);
                continue;
            }
            ProductMasterDataDtos.ReferenceResolution resolution =
                    resolveReferenceInternal(item.getKey(), item.getValue(), true);
            if (resolution.resolved()) {
                upsertReferenceBinding(
                        productId, item.getKey(), resolution.referenceValueId(),
                        item.getValue(), resolution.normalizedCode(), schemaVersion);
            }
        }
        List<Map<String, Object>> fields = jdbc.queryForList("""
                select reference_value_id,source_value_text,normalized_code
                from product_field_value where product_id=? and reference_value_id>0
                """, productId);
        Map<Long, Map<String, Object>> values = new LinkedHashMap<>();
        for (Map<String, Object> field : fields) {
            values.put(number(field.get("reference_value_id")), field);
        }
        for (Map.Entry<Long, Map<String, Object>> entry : values.entrySet()) {
            Long referenceId = entry.getKey();
            String type = jdbc.queryForObject(
                    "select reference_type from product_reference_value where id=?", String.class, referenceId);
            Map<String, Object> field = entry.getValue();
            upsertReferenceBinding(
                    productId, type, referenceId, text(field.get("source_value_text")),
                    text(field.get("normalized_code")), schemaVersion);
        }
    }

    private Map<String, StoredReferenceBinding> storedReferenceBindings(Long productId) {
        Map<String, StoredReferenceBinding> result = new HashMap<>();
        jdbc.query("""
                select reference_type,reference_value_id,source_value_snapshot,
                       normalized_code_snapshot
                from product_reference_binding
                where product_id=? and status='ENABLED'
                """, rs -> {
            while (rs.next()) {
                result.put(rs.getString("reference_type"), new StoredReferenceBinding(
                        rs.getLong("reference_value_id"),
                        rs.getString("source_value_snapshot"),
                        rs.getString("normalized_code_snapshot")));
            }
        }, productId);
        return result;
    }

    private void upsertReferenceBinding(
            Long productId, String type, Long referenceId, String sourceValue,
            String normalizedCode, int schemaVersion) {
        jdbc.update("""
                insert into product_reference_binding
                (product_id,reference_type,reference_value_id,source_value_snapshot,
                 normalized_code_snapshot,identity_schema_version_snapshot,status)
                values(?,?,?,?,?,?,'ENABLED')
                on duplicate key update reference_value_id=values(reference_value_id),
                  source_value_snapshot=values(source_value_snapshot),
                  normalized_code_snapshot=values(normalized_code_snapshot),
                  identity_schema_version_snapshot=values(identity_schema_version_snapshot),
                  status='ENABLED'
                """, productId, type, referenceId, text(sourceValue),
                text(normalizedCode), schemaVersion);
    }

    private boolean sameReferenceText(String left, String right) {
        if (!StringUtils.hasText(left) || !StringUtils.hasText(right)) return false;
        return aliasKey(left).equals(aliasKey(right));
    }

    private ProductMasterDataDtos.ReferenceValue referenceValue(Long id) {
        return referenceValues(null, null, null).stream()
                .filter(row -> Objects.equals(row.id(), id)).findFirst()
                .orElseThrow(() -> new BusinessException("规范值不存在"));
    }

    private void ensureReferenceCodeAvailable(String type, String code, Long excludedId) {
        Integer count = jdbc.queryForObject("""
                select count(*) from product_reference_value
                where reference_type=? and code=? and id<>?
                """, Integer.class, type, code, excludedId == null ? 0 : excludedId);
        if (count != null && count > 0) throw new BusinessException("同类型规范编码已存在");
    }

    private void replaceAliases(Long referenceId, String type, String code, String name, List<String> aliases) {
        List<String> values = normalizedAliases(code, name, aliases);
        for (String alias : values) {
            Integer conflict = jdbc.queryForObject("""
                    select count(*) from product_reference_alias
                    where reference_type=? and alias_key=? and reference_value_id<>?
                    """, Integer.class, type, aliasKey(alias), referenceId);
            if (conflict != null && conflict > 0) {
                throw new BusinessException("别名“" + alias + "”已指向其他" + referenceTypeLabel(type));
            }
        }
        jdbc.update("delete from product_reference_alias where reference_value_id=?", referenceId);
        for (String alias : values) {
            try {
                jdbc.update("""
                        insert into product_reference_alias
                        (reference_type,alias_text,alias_key,reference_value_id,status)
                        values(?,?,?,?, 'ENABLED')
                        """, type, alias, aliasKey(alias), referenceId);
            } catch (DuplicateKeyException ex) {
                throw new BusinessException(
                        "别名“" + alias + "”已指向其他" + referenceTypeLabel(type));
            }
        }
    }

    private List<String> normalizedAliases(String code, String name, List<String> aliases) {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        for (String value : concat(List.of(code, name), aliases == null ? List.of() : aliases)) {
            if (StringUtils.hasText(value)) values.putIfAbsent(aliasKey(value), value.trim());
        }
        return new ArrayList<>(values.values());
    }

    private List<String> concat(List<String> first, List<String> second) {
        List<String> values = new ArrayList<>(first);
        values.addAll(second);
        return values;
    }

    private SeriesIdentityHead requireSeriesIdentityHead(Long seriesId, boolean lock) {
        String sql = """
                select id,name,identity_schema_version from product_series where id=?
                """ + (lock ? " for update" : "");
        List<SeriesIdentityHead> rows = jdbc.query(sql, (rs, rowNum) -> new SeriesIdentityHead(
                rs.getLong("id"), rs.getString("name"), rs.getInt("identity_schema_version")
        ), seriesId);
        if (rows.isEmpty()) throw new BusinessException("产品系列不存在");
        return rows.get(0);
    }

    private ProductMasterDataDtos.SourceDocument sourceDocument(Long id) {
        return sourceDocuments(null).stream().filter(row -> Objects.equals(row.id(), id)).findFirst()
                .orElseThrow(() -> new BusinessException("来源资料不存在"));
    }

    private ProductMasterDataDtos.SourceLink sourceLink(Long id) {
        List<ProductMasterDataDtos.SourceLink> rows = jdbc.query("""
                select l.id,l.document_revision_id,r.document_id,r.title_snapshot document_title,
                       r.revision_no document_revision_no,l.target_type,l.target_id,l.page_no,
                       l.source_locator,l.raw_value,l.normalized_value,l.confidence,l.review_status,
                       l.review_comment,l.reviewed_by,l.reviewed_by_name,l.reviewed_at
                from product_source_link l
                join product_source_document_revision r on r.id=l.document_revision_id
                where l.id=?
                """, (rs, rowNum) -> sourceLinkRow(rs), id);
        if (rows.isEmpty()) throw new BusinessException("来源证据不存在");
        return rows.get(0);
    }

    private ProductMasterDataDtos.SourceLink sourceLinkRow(java.sql.ResultSet rs)
            throws java.sql.SQLException {
        return new ProductMasterDataDtos.SourceLink(
                rs.getLong("id"), rs.getLong("document_revision_id"),
                rs.getLong("document_id"), rs.getString("document_title"),
                rs.getInt("document_revision_no"), rs.getString("target_type"),
                rs.getLong("target_id"), rs.getInt("page_no"), rs.getString("source_locator"),
                rs.getString("raw_value"), rs.getString("normalized_value"),
                rs.getBigDecimal("confidence"), rs.getString("review_status"),
                rs.getString("review_comment"), zeroToNull(rs.getLong("reviewed_by")),
                rs.getString("reviewed_by_name"),
                rs.getTimestamp("reviewed_at") == null
                        ? null : rs.getTimestamp("reviewed_at").toLocalDateTime()
        );
    }

    private ProductMasterDataDtos.SourceDocument sourceDocumentRow(java.sql.ResultSet rs)
            throws java.sql.SQLException {
        return new ProductMasterDataDtos.SourceDocument(
                rs.getLong("id"), rs.getString("document_code"), rs.getString("title"),
                rs.getString("source_type"), zeroToNull(rs.getLong("current_revision_id")),
                rs.getInt("current_revision_no"), rs.getString("status"), rs.getString("source_url"),
                rs.getString("file_name"), rs.getString("file_sha256"), rs.getString("mime_type"),
                rs.getLong("file_size"), date(rs.getDate("publication_date")),
                rs.getString("internal_version"),
                rs.getTimestamp("downloaded_at") == null ? null : rs.getTimestamp("downloaded_at").toLocalDateTime(),
                rs.getString("content_snapshot_json"), rs.getString("change_reason"),
                rs.getTimestamp("published_at") == null ? null : rs.getTimestamp("published_at").toLocalDateTime()
        );
    }

    private void publishSourceRevision(
            Long documentId, int currentRevision, ProductMasterDataDtos.SourceDocumentSave request) {
        SecurityContext.Principal actor = SecurityContext.require();
        int revision = currentRevision + 1;
        String snapshotJson = validJson(request.contentSnapshotJson(), "{}");
        Map<String, Object> head = jdbc.queryForMap("""
                select document_code,status from product_source_document where id=?
                """, documentId);
        long revisionId = insert("""
                insert into product_source_document_revision
                (document_id,document_code_snapshot,revision_no,title_snapshot,source_type_snapshot,
                 head_status_snapshot,source_url,file_name,file_sha256,mime_type,file_size,
                 publication_date,internal_version,downloaded_at,content_snapshot_json,
                 change_reason,status,published_by,published_by_name)
                values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, documentId, String.valueOf(head.get("document_code")), revision,
                requiredText(request.title(), "资料标题"),
                sourceType(request.sourceType()), String.valueOf(head.get("status")),
                sourceUrl(request.sourceUrl()), text(request.fileName()),
                sha256OrBlank(request.fileSha256()), text(request.mimeType()),
                request.fileSize() == null ? 0 : request.fileSize(), sqlDate(request.publicationDate()),
                text(request.internalVersion()),
                request.downloadedAt() == null ? null : Timestamp.valueOf(request.downloadedAt()),
                snapshotJson, requiredText(request.changeReason(), "变更原因"), "PUBLISHED",
                actor.userId(), actor.employeeName());
        jdbc.update("""
                update product_source_document
                set current_revision_id=?,current_revision_no=?,lock_version=lock_version+1
                where id=?
                """, revisionId, revision, documentId);
    }

    private UnitRow requireUnit(Long unitId) {
        List<UnitRow> rows = jdbc.query("""
                select id,name,abbreviation,status from unit where id=?
                """, (rs, rowNum) -> new UnitRow(
                rs.getLong("id"), rs.getString("name"), rs.getString("abbreviation"),
                rs.getString("status")
        ), unitId);
        if (rows.isEmpty()) throw new BusinessException("计量单位不存在");
        if (!"ENABLED".equals(rows.get(0).status())) throw new BusinessException("计量单位已停用");
        return rows.get(0);
    }

    private void deactivateAllowedUnit(Long seriesId, Long allowedUnitId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id,purpose,is_default,status
                from product_series_allowed_unit
                where id=? and product_series_id=? for update
                """, allowedUnitId, seriesId);
        if (rows.isEmpty()) throw new BusinessException("系列可用单位不存在");
        Map<String, Object> target = rows.get(0);
        if ("DISABLED".equals(String.valueOf(target.get("status")))) return;
        String purpose = String.valueOf(target.get("purpose"));
        Integer enabledCount = jdbc.queryForObject("""
                select count(*) from product_series_allowed_unit
                where product_series_id=? and purpose=? and status='ENABLED'
                """, Integer.class, seriesId, purpose);
        if (enabledCount == null || enabledCount <= 1) {
            throw new BusinessException("不能停用该用途最后一个可用单位，请先启用替代单位");
        }
        if (((Number) target.get("is_default")).intValue() == 1) {
            Integer replacementDefaults = jdbc.queryForObject("""
                    select count(*) from product_series_allowed_unit
                    where product_series_id=? and purpose=? and status='ENABLED'
                      and is_default=1 and id<>?
                    """, Integer.class, seriesId, purpose, allowedUnitId);
            if (replacementDefaults == null || replacementDefaults == 0) {
                throw new BusinessException("不能停用当前默认单位，请先指定新的默认单位");
            }
        }
        jdbc.update("""
                update product_series_allowed_unit
                set status='DISABLED',is_default=0
                where id=? and product_series_id=?
                """, allowedUnitId, seriesId);
    }

    private String identitySchemaHash(List<NormalizedIdentityRule> rules) {
        String value = rules.stream().map(rule ->
                rule.dimensionKey() + ":" + rule.sourceType() + ":" + rule.seriesFieldId()
                        + ":" + rule.referenceType() + ":" + rule.required() + ":" + rule.sortNo()
                        + ":" + rule.status()).reduce((a, b) -> a + "|" + b).orElse("");
        return sha256(value);
    }

    private String validJson(String value, String fallback) {
        String candidate = StringUtils.hasText(value) ? value.trim() : fallback;
        try {
            objectMapper.readTree(candidate);
            return candidate;
        } catch (JsonProcessingException ex) {
            throw new BusinessException("JSON格式不正确");
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new BusinessException("主数据JSON无法生成");
        }
    }

    private List<String> stringList(String json) {
        if (!StringUtils.hasText(json)) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException ex) {
            return List.of();
        }
    }

    private String referenceType(String value) {
        String normalized = requiredText(value, "规范值类型").toUpperCase(Locale.ROOT);
        if (!REFERENCE_TYPES.contains(normalized)) {
            throw new BusinessException("规范值类型只支持GRADE、TEMPER、STANDARD");
        }
        return normalized;
    }

    private String referenceTypeLabel(String type) {
        return switch (type) {
            case "GRADE" -> "牌号";
            case "TEMPER" -> "材料状态";
            default -> "执行标准";
        };
    }

    private String sourceType(String value) {
        String normalized = requiredText(value, "资料来源类型").toUpperCase(Locale.ROOT);
        if (!Set.of("UPLOAD", "URL", "MANUAL", "SUPPLIER", "SUPPLIER_CATALOG",
                "TECHNICAL_STANDARD", "QUALITY_RULE", "OTHER").contains(normalized)) {
            throw new BusinessException("不支持的资料来源类型");
        }
        return normalized;
    }

    private String validateSourceTarget(
            String inputType, Long targetId, boolean writeOperation) {
        String type = requiredText(inputType, "来源对象类型").toUpperCase(Locale.ROOT);
        if (targetId == null || targetId <= 0) throw new BusinessException("来源对象ID不正确");
        switch (type) {
            case "PRODUCT" -> {
                requireCount("select count(*) from product where id=?", targetId, "来源商品不存在");
                dataScopeService.assertMasterAllowed("PRODUCT", targetId);
            }
            case "PRODUCT_SERIES" ->
                    requireCount("select count(*) from product_series where id=?", targetId, "来源产品系列不存在");
            case "PRODUCT_REFERENCE_VALUE" ->
                    requireCount("select count(*) from product_reference_value where id=?", targetId, "来源规范值不存在");
            case "PACKAGING_VARIANT" -> assertProductOwnedTarget(
                    "select product_id from packaging_variant where id=?", targetId, "来源包装方案不存在");
            case "SUPPLIER_OFFER" -> {
                if (writeOperation) {
                    requireSupplierOfferManagePermission();
                } else {
                    requireSupplierOfferCostPermission();
                }
                assertSupplierOfferTarget(targetId);
            }
            case "TECHNICAL_SPEC" -> assertProductOwnedTarget(
                    "select product_id from product_technical_spec_revision where id=?", targetId,
                    "来源技术规格不存在");
            case "BOM" -> assertProductOwnedTarget(
                    "select parent_product_id from product_bom where id=?", targetId, "来源BOM不存在");
            case "QUALITY_CERTIFICATE" -> {
                if (writeOperation) {
                    requireQualityCertificateManagePermission();
                } else {
                    requireQualityCertificateViewPermission();
                }
                List<Map<String, Object>> rows = jdbc.queryForList("""
                        select b.product_id,b.warehouse_id
                        from inventory_batch_quality_certificate q
                        join inventory_batch b on b.id=q.inventory_batch_id
                        where q.id=?
                        """, targetId);
                if (rows.isEmpty()) throw new BusinessException("来源质保书不存在");
                dataScopeService.assertMasterAllowed("PRODUCT", number(rows.get(0).get("product_id")));
                dataScopeService.assertMasterAllowed("WAREHOUSE", number(rows.get(0).get("warehouse_id")));
            }
            default -> throw new BusinessException("不支持的来源对象类型：" + type);
        }
        return type;
    }

    private void assertProductOwnedTarget(String sql, Long targetId, String missingMessage) {
        List<Long> productIds = jdbc.query(sql, (rs, rowNum) -> rs.getLong(1), targetId);
        if (productIds.isEmpty()) throw new BusinessException(missingMessage);
        dataScopeService.assertMasterAllowed("PRODUCT", productIds.get(0));
    }

    private void assertSupplierOfferTarget(Long targetId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select product_id,supplier_id from supplier_product_offer where id=?
                """, targetId);
        if (rows.isEmpty()) throw new BusinessException("来源供货档案不存在");
        dataScopeService.assertMasterAllowed(
                "PRODUCT", number(rows.get(0).get("product_id")));
        dataScopeService.assertMasterAllowed(
                "PARTNER", number(rows.get(0).get("supplier_id")));
    }

    private String unitPurpose(String value) {
        String normalized = requiredText(value, "单位用途").toUpperCase(Locale.ROOT);
        if (!UNIT_PURPOSES.contains(normalized)) throw new BusinessException("不支持的单位用途");
        return normalized;
    }

    private String identityStatus(String value) {
        if (!StringUtils.hasText(value)) return "PUBLISHED";
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("PENDING_REVIEW", "PUBLISHED", "DISABLED").contains(normalized)) {
            throw new BusinessException("商品身份状态不正确");
        }
        return normalized;
    }

    private String status(String value) {
        String normalized = requiredText(value, "状态").toUpperCase(Locale.ROOT);
        if (!Set.of("ENABLED", "DISABLED").contains(normalized)) {
            throw new BusinessException("状态只支持ENABLED或DISABLED");
        }
        return normalized;
    }

    private String sourceUrl(String value) {
        if (!StringUtils.hasText(value)) return "";
        String normalized = value.trim();
        try {
            URI uri = URI.create(normalized);
            String scheme = uri.getScheme();
            if (scheme == null
                    || (!"http".equalsIgnoreCase(scheme)
                        && !"https".equalsIgnoreCase(scheme))
                    || !StringUtils.hasText(uri.getHost())) {
                throw new BusinessException("来源URL只支持有效的http或https地址");
            }
            return normalized;
        } catch (IllegalArgumentException ex) {
            throw new BusinessException("来源URL只支持有效的http或https地址");
        }
    }

    private String aliasKey(String value) {
        return requiredText(value, "别名").trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private String requiredText(String value, String label) {
        if (!StringUtils.hasText(value)) throw new BusinessException("请填写" + label);
        return value.trim();
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private BigDecimal decimal(Object value) {
        if (value == null) return null;
        if (value instanceof BigDecimal decimal) return decimal;
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private void validateDates(java.time.LocalDate from, java.time.LocalDate to, String label) {
        if (from != null && to != null && to.isBefore(from)) {
            throw new BusinessException(label + "失效日期不能早于生效日期");
        }
    }

    private Date sqlDate(java.time.LocalDate value) {
        return value == null ? null : Date.valueOf(value);
    }

    private java.time.LocalDate date(Date value) {
        return value == null ? null : value.toLocalDate();
    }

    private String sha256OrBlank(String value) {
        if (!StringUtils.hasText(value)) return "";
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[0-9a-f]{64}")) throw new BusinessException("文件SHA-256格式不正确");
        return normalized;
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("无法生成主数据签名", ex);
        }
    }

    private SecurityContext.Principal requireViewPermission() {
        SecurityContext.Principal actor = SecurityContext.require();
        if (!actor.hasPermission(VIEW_PERMISSION) && !actor.hasPermission(MANAGE_PERMISSION)) {
            throw new ForbiddenException("当前账号无权查看复杂商品主数据");
        }
        return actor;
    }

    private SecurityContext.Principal requireReferenceViewPermission() {
        return requireProductEntryViewPermission();
    }

    private SecurityContext.Principal requireProductEntryViewPermission() {
        SecurityContext.Principal actor = SecurityContext.require();
        if (!actor.hasPermission(VIEW_PERMISSION)
                && !actor.hasPermission(MANAGE_PERMISSION)
                && !actor.hasPermission("product.view")
                && !actor.hasPermission("product.manage")) {
            throw new ForbiddenException("当前账号无权查看商品录入选项");
        }
        return actor;
    }

    private void requireSupplierOfferCostPermission() {
        SecurityContext.Principal actor = SecurityContext.require();
        if (!actor.hasPermission("product.cost.view")
                && !actor.hasPermission("product.supplier.offer.manage")) {
            throw new ForbiddenException("当前账号无权查看供应商供货档案来源证据");
        }
    }

    private void requireSupplierOfferManagePermission() {
        if (!SecurityContext.require().hasPermission("product.supplier.offer.manage")) {
            throw new ForbiddenException("当前账号无权维护供应商供货档案来源证据");
        }
    }

    private void requireQualityCertificateViewPermission() {
        SecurityContext.Principal actor = SecurityContext.require();
        if (!actor.hasPermission("inventory.quality.view")
                && !actor.hasPermission("inventory.quality.manage")) {
            throw new ForbiddenException("当前账号无权查看质保书来源证据");
        }
    }

    private void requireQualityCertificateManagePermission() {
        if (!SecurityContext.require().hasPermission("inventory.quality.manage")) {
            throw new ForbiddenException("当前账号无权维护质保书来源证据");
        }
    }

    private SecurityContext.Principal requireManagePermission() {
        SecurityContext.Principal actor = SecurityContext.require();
        if (!actor.hasPermission(MANAGE_PERMISSION)) {
            throw new ForbiddenException("当前账号无权维护或发布复杂商品主数据");
        }
        return actor;
    }

    private void requireCount(String sql, Object arg, String message) {
        Integer count = jdbc.queryForObject(sql, Integer.class, arg);
        if (count == null || count == 0) throw new BusinessException(message);
    }

    private long insert(String sql, Object... args) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int index = 0; index < args.length; index++) {
                statement.setObject(index + 1, args[index]);
            }
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) throw new BusinessException("新增商品主数据失败");
        return key.longValue();
    }

    private int bool(Boolean value) {
        return Boolean.TRUE.equals(value) ? 1 : 0;
    }

    private long id(Long value) {
        return value == null || value <= 0 ? 0 : value;
    }

    private Long zeroToNull(long value) {
        return value <= 0 ? null : value;
    }

    private Long number(Object value) {
        return value instanceof Number number ? number.longValue() : Long.valueOf(String.valueOf(value));
    }

    private record SeriesIdentityHead(Long id, String name, int version) {
    }

    private record SchemaRevisionRow(String contentHash, String changeReason, LocalDateTime publishedAt) {
    }

    private record NormalizedIdentityRule(
            String dimensionKey, String sourceType, long seriesFieldId,
            String referenceType, boolean required, int sortNo, String status
    ) {
    }

    private record ProductIdentityBase(
            Long id, Long seriesId, String sku, String grade, String temper,
            String standard, int schemaVersion
    ) {
    }

    private record StoredFieldValue(String canonicalValue) {
    }

    private record StoredReferenceBinding(
            Long referenceValueId, String sourceValue, String normalizedCode) {
    }

    private record FieldTypeRow(String fieldType, String displayName) {
    }

    private record CandidateIdentity(String signature, boolean complete, List<String> warnings) {
    }

    private record IdentityEvaluation(String signature, boolean complete, List<String> warnings) {
    }

    private record UnitRow(Long id, String name, String abbreviation, String status) {
        private String displayName() {
            return StringUtils.hasText(abbreviation) ? abbreviation : name;
        }
    }
}
