package com.soyia.copperv2.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.soyia.copperv2.dto.PriceCenterDtos;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class PriceCenterService {
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal ONE = BigDecimal.ONE;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public PriceCenterService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public List<PriceCenterDtos.BenchmarkRow> benchmarks(String benchmarkCode) {
        String sql = """
                select id,benchmark_code,benchmark_name,version_no,price_unit,unit_price,
                       price_includes_tax,tax_rate,effective_date,source_type,source_note,
                       change_reason,operator_id,operator_name,created_at
                from price_benchmark_version
                """ + (StringUtils.hasText(benchmarkCode) ? " where benchmark_code=? " : " ")
                + "order by effective_date desc,id desc limit 500";
        Object[] args = StringUtils.hasText(benchmarkCode)
                ? new Object[]{benchmarkCode.trim().toUpperCase()} : new Object[0];
        return jdbc.query(sql, (rs, rowNum) -> new PriceCenterDtos.BenchmarkRow(
                rs.getLong("id"), rs.getString("benchmark_code"), rs.getString("benchmark_name"),
                rs.getInt("version_no"), rs.getString("price_unit"), rs.getBigDecimal("unit_price"),
                rs.getBoolean("price_includes_tax"), rs.getBigDecimal("tax_rate"),
                rs.getDate("effective_date").toLocalDate(), rs.getString("source_type"),
                rs.getString("source_note"), rs.getString("change_reason"),
                rs.getLong("operator_id"), rs.getString("operator_name"),
                rs.getTimestamp("created_at").toLocalDateTime()), args);
    }

    @Transactional
    public PriceCenterDtos.BenchmarkRow createBenchmark(PriceCenterDtos.BenchmarkSave request) {
        SecurityContext.Principal actor = SecurityContext.require();
        String code = code(request.benchmarkCode(), "CU-SPOT");
        String sourceType = enumValue(request.sourceType(), List.of("MANUAL", "MARKET_REFERENCE", "SUPPLIER_NOTICE"), "基准价来源");
        String reason = reason(request.changeReason(), "基准价变更原因");
        BigDecimal taxRate = rate(request.taxRate());
        BigDecimal unitPrice = positivePrice(request.unitPrice(), "基准铜价");
        int version = nextBenchmarkVersion(code);
        Integer exists = jdbc.queryForObject("select count(*) from price_benchmark_version where benchmark_code=? and effective_date=?",
                Integer.class, code, Date.valueOf(request.effectiveDate()));
        if (exists != null && exists > 0) throw new BusinessException("同一基准价代码和生效日期只能有一个版本");
        long id = insert("""
                insert into price_benchmark_version
                (benchmark_code,benchmark_name,version_no,price_unit,unit_price,price_includes_tax,
                 tax_rate,effective_date,source_type,source_note,change_reason,operator_id,operator_name)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, code, request.benchmarkName().trim(), version, request.priceUnit().trim(), unitPrice,
                bool(request.priceIncludesTax()), taxRate, Date.valueOf(request.effectiveDate()), sourceType,
                text(request.sourceNote()), reason, actor.userId(), actor.employeeName());
        return benchmarks(code).stream().filter(row -> Objects.equals(row.id(), id)).findFirst().orElseThrow();
    }

    public List<PriceCenterDtos.RuleRow> rules(String businessType, Long productId, Long partnerId) {
        StringBuilder sql = new StringBuilder("""
                select r.*,p.sku product_sku,p.name product_name,p.spec product_spec,
                       coalesce(bp.name,'') partner_name
                from price_rule_version r
                join product p on p.id=r.product_id
                left join partner bp on bp.id=r.partner_id
                where 1=1
                """);
        List<Object> args = new ArrayList<>();
        if (StringUtils.hasText(businessType)) {
            sql.append(" and r.business_type=?");
            args.add(businessType.trim().toUpperCase());
        }
        if (productId != null && productId > 0) {
            sql.append(" and r.product_id=?");
            args.add(productId);
        }
        if (partnerId != null && partnerId > 0) {
            sql.append(" and r.partner_id=?");
            args.add(partnerId);
        }
        sql.append(" order by r.effective_from desc,r.id desc limit 1000");
        return jdbc.query(sql.toString(), (rs, rowNum) -> new PriceCenterDtos.RuleRow(
                rs.getLong("id"), rs.getString("rule_code"), rs.getString("rule_name"), rs.getInt("version_no"),
                rs.getString("business_type"), rs.getString("scope_type"), rs.getLong("partner_id"),
                rs.getString("partner_name"), rs.getString("customer_level"), rs.getString("agreement_type"),
                rs.getLong("product_id"), rs.getString("product_sku"), rs.getString("product_name"),
                rs.getString("product_spec"), rs.getString("price_unit"), rs.getBigDecimal("minimum_quantity"),
                rs.getBigDecimal("maximum_quantity"), rs.getString("pricing_mode"), rs.getString("benchmark_code"),
                rs.getBigDecimal("fixed_unit_price"), rs.getBigDecimal("processing_fee"),
                rs.getBigDecimal("specification_fee"), rs.getBigDecimal("packaging_fee"),
                rs.getBigDecimal("freight_fee"), rs.getBoolean("price_includes_tax"), rs.getBigDecimal("tax_rate"),
                rs.getDate("effective_from").toLocalDate(), date(rs.getDate("effective_to")),
                rs.getString("change_reason"), rs.getString("status"), rs.getLong("operator_id"),
                rs.getString("operator_name"), rs.getTimestamp("created_at").toLocalDateTime()), args.toArray());
    }

    @Transactional
    public PriceCenterDtos.RuleRow createRule(PriceCenterDtos.RuleSave request) {
        SecurityContext.Principal actor = SecurityContext.require();
        String businessType = enumValue(request.businessType(), List.of("SALES", "PURCHASE"), "业务类型");
        String scopeType = enumValue(request.scopeType(), List.of("STANDARD", "CUSTOMER_LEVEL", "CUSTOMER", "SUPPLIER"), "适用范围");
        String agreementType = enumValue(request.agreementType(), List.of("STANDARD", "AGREEMENT", "SPECIAL"), "价格类型");
        String pricingMode = enumValue(request.pricingMode(), List.of("FORMULA", "FIXED"), "计价方式");
        validateScope(businessType, scopeType, request.partnerId(), request.customerLevel(), agreementType);
        requireEnabledProduct(request.productId());
        if (request.effectiveTo() != null && request.effectiveTo().isBefore(request.effectiveFrom())) {
            throw new BusinessException("失效日期不能早于生效日期");
        }
        BigDecimal minimum = quantity(request.minimumQuantity());
        BigDecimal maximum = request.maximumQuantity() == null ? null : quantity(request.maximumQuantity());
        if (maximum != null && maximum.compareTo(minimum) < 0) throw new BusinessException("数量上限不能小于数量下限");
        String benchmarkCode = text(request.benchmarkCode()).toUpperCase();
        if ("FORMULA".equals(pricingMode) && !StringUtils.hasText(benchmarkCode)) {
            throw new BusinessException("公式价必须选择铜基准价代码");
        }
        if ("FORMULA".equals(pricingMode)) {
            Integer benchmarkCount = jdbc.queryForObject("select count(*) from price_benchmark_version where benchmark_code=?",
                    Integer.class, benchmarkCode);
            if (benchmarkCount == null || benchmarkCount == 0) throw new BusinessException("铜基准价代码不存在");
        }
        BigDecimal fixedUnitPrice = "FIXED".equals(pricingMode)
                ? positivePrice(request.fixedUnitPrice(), "固定单价")
                : price(request.fixedUnitPrice(), "固定单价");
        String code = code(request.ruleCode(), "PR-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase());
        validateRuleIdentity(code, request, businessType, scopeType, agreementType, pricingMode);
        assertNoAmbiguousOverlap(request, businessType, scopeType, agreementType, minimum, maximum);
        int version = nextVersion("price_rule_version", "rule_code", code);
        String reason = reason(request.changeReason(), "价格规则变更原因");
        long id = insert("""
                insert into price_rule_version
                (rule_code,rule_name,version_no,business_type,scope_type,partner_id,customer_level,
                 agreement_type,product_id,price_unit,minimum_quantity,maximum_quantity,pricing_mode,
                 benchmark_code,fixed_unit_price,processing_fee,specification_fee,packaging_fee,freight_fee,
                 price_includes_tax,tax_rate,effective_from,effective_to,change_reason,status,operator_id,operator_name)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'PUBLISHED',?,?)
                """, code, request.ruleName().trim(), version, businessType, scopeType, id(request.partnerId()),
                text(request.customerLevel()).toUpperCase(), agreementType, request.productId(), request.priceUnit().trim(),
                minimum, maximum, pricingMode, benchmarkCode, fixedUnitPrice,
                price(request.processingFee(), "加工费"), price(request.specificationFee(), "规格加价"),
                price(request.packagingFee(), "包装费"), price(request.freightFee(), "运费"),
                bool(request.priceIncludesTax()), rate(request.taxRate()), Date.valueOf(request.effectiveFrom()),
                request.effectiveTo() == null ? null : Date.valueOf(request.effectiveTo()), reason,
                actor.userId(), actor.employeeName());
        return rules(null, null, null).stream().filter(row -> Objects.equals(row.id(), id)).findFirst().orElseThrow();
    }

    public PriceCenterDtos.Preview preview(PriceCenterDtos.PreviewRequest request) {
        return previewOrEmpty(request).orElseThrow(() -> new BusinessException("当前客户、商品、数量和日期没有可用价格规则"));
    }

    public Optional<PriceCenterDtos.Preview> previewOrEmpty(PriceCenterDtos.PreviewRequest request) {
        String businessType = enumValue(request.businessType(), List.of("SALES", "PURCHASE"), "业务类型");
        BigDecimal requestedQuantity = quantity(request.quantity());
        PartnerContract partner = partnerContract(businessType, request.partnerId());
        List<RuleMatch> candidates = matchingRules(businessType, request.partnerId(), partner.customerLevel(),
                request.productId(), request.priceUnit().trim(), requestedQuantity, request.businessDate());
        if (candidates.isEmpty()) return Optional.empty();
        RuleMatch rule = candidates.get(0);
        Contract target = partner.exists()
                ? new Contract(partner.priceIncludesTax(), partner.taxRate())
                : new Contract(rule.priceIncludesTax(), rule.taxRate());
        Contract source = new Contract(rule.priceIncludesTax(), rule.taxRate());
        List<PriceCenterDtos.Component> components = new ArrayList<>();
        Long benchmarkId = 0L;
        String benchmarkCode = "";
        int benchmarkVersion = 0;
        BigDecimal unitPriceExcludingTax;
        if ("FIXED".equals(rule.pricingMode())) {
            unitPriceExcludingTax = excludingTax(rule.fixedUnitPrice(), source);
            components.add(component("FIXED_PRICE", "固定单价", rule.fixedUnitPrice(), unitPriceExcludingTax, target));
        } else {
            Benchmark benchmark = benchmark(rule.benchmarkCode(), request.businessDate());
            if (!sameText(benchmark.priceUnit(), rule.priceUnit())) {
                throw new BusinessException("基准价单位与价格规则计价单位不一致，请新建正确版本");
            }
            benchmarkId = benchmark.id();
            benchmarkCode = benchmark.code();
            benchmarkVersion = benchmark.versionNo();
            BigDecimal benchmarkExcludingTax = excludingTax(benchmark.unitPrice(),
                    new Contract(benchmark.priceIncludesTax(), benchmark.taxRate()));
            unitPriceExcludingTax = benchmarkExcludingTax;
            components.add(component("BENCHMARK", benchmark.name() + " V" + benchmark.versionNo(),
                    benchmark.unitPrice(), benchmarkExcludingTax, target));
            for (Fee fee : List.of(
                    new Fee("PROCESSING", "加工费", rule.processingFee()),
                    new Fee("SPECIFICATION", "规格加价", rule.specificationFee()),
                    new Fee("PACKAGING", "包装费", rule.packagingFee()),
                    new Fee("FREIGHT", "运费", rule.freightFee()))) {
                BigDecimal excludingTax = excludingTax(fee.amount(), source);
                unitPriceExcludingTax = unitPriceExcludingTax.add(excludingTax);
                components.add(component(fee.code(), fee.name(), fee.amount(), excludingTax, target));
            }
        }
        BigDecimal unitPrice = enteredPrice(unitPriceExcludingTax, target);
        String sourceCode = sourceCode(rule);
        String sourceName = sourceName(rule);
        List<String> warnings = new ArrayList<>();
        if (partner.exists() && (partner.priceIncludesTax() != rule.priceIncludesTax()
                || partner.taxRate().compareTo(rule.taxRate()) != 0)) {
            warnings.add("价格规则价税口径已自动换算为往来单位当前订单口径");
        }
        String explanation = explain(rule, benchmarkCode, benchmarkVersion, target);
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("version", "COPPER_PRICE_CENTER_V1");
        snapshot.put("businessType", businessType);
        snapshot.put("businessDate", request.businessDate());
        snapshot.put("partnerId", id(request.partnerId()));
        snapshot.put("productId", request.productId());
        snapshot.put("quantity", requestedQuantity);
        snapshot.put("priceUnit", rule.priceUnit());
        snapshot.put("unitPrice", unitPrice);
        snapshot.put("priceIncludesTax", target.priceIncludesTax());
        snapshot.put("taxRate", target.taxRate());
        snapshot.put("sourceCode", sourceCode);
        snapshot.put("sourceName", sourceName);
        snapshot.put("ruleVersionId", rule.id());
        snapshot.put("ruleCode", rule.ruleCode());
        snapshot.put("ruleVersionNo", rule.versionNo());
        snapshot.put("benchmarkVersionId", benchmarkId);
        snapshot.put("benchmarkCode", benchmarkCode);
        snapshot.put("benchmarkVersionNo", benchmarkVersion);
        snapshot.put("agreementType", rule.agreementType());
        snapshot.put("components", components);
        snapshot.put("explanation", explanation);
        String snapshotJson = json(snapshot);
        return Optional.of(new PriceCenterDtos.Preview(unitPrice, rule.priceUnit(), sourceCode, sourceName,
                explanation, rule.id(), rule.ruleCode(), rule.versionNo(), benchmarkId,
                benchmarkCode, benchmarkVersion, target.priceIncludesTax(), target.taxRate(),
                rule.agreementType(), components, warnings, snapshotJson));
    }

    public PriceCenterDtos.PriceSnapshot freezeEnteredPrice(
            String businessType, Long partnerId, Long productId, BigDecimal quantity,
            String priceUnit, LocalDate businessDate, BigDecimal enteredUnitPrice, boolean noCharge) {
        return freezeEnteredPrice(businessType, partnerId, productId, quantity, priceUnit,
                businessDate, enteredUnitPrice, noCharge, null);
    }

    public PriceCenterDtos.PriceSnapshot freezeEnteredPrice(
            String businessType, Long partnerId, Long productId, BigDecimal quantity,
            String priceUnit, LocalDate businessDate, BigDecimal enteredUnitPrice, boolean noCharge,
            String historicalSnapshotJson) {
        if (noCharge) {
            return snapshot("GIFT", "赠品零价", 0L, Map.of(
                    "version", "COPPER_PRICE_CENTER_V1", "sourceCode", "GIFT",
                    "sourceName", "赠品零价", "enteredUnitPrice", BigDecimal.ZERO));
        }
        BigDecimal entered = positivePrice(enteredUnitPrice, "单价");
        if (matchesHistoricalSnapshot(historicalSnapshotJson, businessType, partnerId, productId,
                quantity, priceUnit, businessDate, entered)) {
            return snapshotFromJson(historicalSnapshotJson);
        }
        PriceCenterDtos.PreviewRequest request = new PriceCenterDtos.PreviewRequest(
                businessType, partnerId, productId, quantity, priceUnit, businessDate);
        Optional<PriceCenterDtos.Preview> suggested = previewOrEmpty(request);
        if (suggested.isPresent() && suggested.get().unitPrice().compareTo(entered) == 0) {
            PriceCenterDtos.Preview preview = suggested.get();
            return new PriceCenterDtos.PriceSnapshot(preview.sourceCode(), preview.sourceName(),
                    preview.ruleVersionId(), preview.snapshotJson());
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("version", "COPPER_PRICE_CENTER_V1");
        payload.put("sourceCode", "MANUAL_OVERRIDE");
        payload.put("sourceName", "人工改价");
        payload.put("businessType", businessType);
        payload.put("businessDate", businessDate);
        payload.put("partnerId", id(partnerId));
        payload.put("productId", productId);
        payload.put("quantity", quantity);
        payload.put("priceUnit", priceUnit);
        payload.put("enteredUnitPrice", entered);
        suggested.ifPresent(value -> {
            payload.put("suggestedUnitPrice", value.unitPrice());
            payload.put("suggestedSourceCode", value.sourceCode());
            payload.put("suggestedRuleVersionId", value.ruleVersionId());
        });
        return snapshot("MANUAL_OVERRIDE", "人工改价", 0L, payload);
    }

    private boolean matchesHistoricalSnapshot(String snapshotJson, String businessType, Long partnerId,
                                              Long productId, BigDecimal quantity, String priceUnit,
                                              LocalDate businessDate, BigDecimal enteredUnitPrice) {
        if (!StringUtils.hasText(snapshotJson)) return false;
        try {
            JsonNode node = objectMapper.readTree(snapshotJson);
            if (!"COPPER_PRICE_CENTER_V1".equals(node.path("version").asText())) return false;
            JsonNode priceNode = node.hasNonNull("unitPrice") ? node.get("unitPrice") : node.get("enteredUnitPrice");
            return priceNode != null
                    && enteredUnitPrice.compareTo(priceNode.decimalValue().setScale(4, RoundingMode.HALF_UP)) == 0
                    && text(businessType).equalsIgnoreCase(node.path("businessType").asText())
                    && id(partnerId) == node.path("partnerId").asLong()
                    && Objects.equals(productId, node.path("productId").asLong())
                    && quantity.setScale(6, RoundingMode.HALF_UP)
                    .compareTo(node.path("quantity").decimalValue().setScale(6, RoundingMode.HALF_UP)) == 0
                    && sameText(priceUnit, node.path("priceUnit").asText())
                    && Objects.equals(businessDate.toString(), node.path("businessDate").asText());
        } catch (RuntimeException | JsonProcessingException exception) {
            throw new BusinessException("历史价格快照格式不正确");
        }
    }

    public PriceCenterDtos.PriceSnapshot snapshotFromJson(String snapshotJson) {
        if (!StringUtils.hasText(snapshotJson)) throw new BusinessException("历史价格快照缺失");
        try {
            JsonNode node = objectMapper.readTree(snapshotJson);
            if (!"COPPER_PRICE_CENTER_V1".equals(node.path("version").asText())) {
                throw new BusinessException("历史价格快照版本不受支持");
            }
            return new PriceCenterDtos.PriceSnapshot(node.path("sourceCode").asText("HISTORICAL"),
                    node.path("sourceName").asText("历史价格快照"),
                    node.path("ruleVersionId").asLong(0), snapshotJson);
        } catch (JsonProcessingException exception) {
            throw new BusinessException("历史价格快照格式不正确");
        }
    }

    private List<RuleMatch> matchingRules(String businessType, Long partnerId, String customerLevel,
                                          Long productId, String priceUnit, BigDecimal quantity,
                                          LocalDate businessDate) {
        return jdbc.query("""
                select r.* from price_rule_version r
                where r.business_type=? and r.product_id=? and r.price_unit=? and r.status='PUBLISHED'
                  and r.effective_from<=? and (r.effective_to is null or r.effective_to>=?)
                  and r.minimum_quantity<=? and (r.maximum_quantity is null or r.maximum_quantity>=?)
                  and (
                    r.scope_type='STANDARD'
                    or (r.scope_type='CUSTOMER_LEVEL' and ?='SALES' and r.customer_level=?)
                    or (r.scope_type='CUSTOMER' and ?='SALES' and r.partner_id=?)
                    or (r.scope_type='SUPPLIER' and ?='PURCHASE' and r.partner_id=?)
                  )
                order by
                  case
                    when r.scope_type='CUSTOMER' and r.agreement_type='SPECIAL' then 500
                    when r.scope_type='CUSTOMER' then 400
                    when r.scope_type='SUPPLIER' then 400
                    when r.scope_type='CUSTOMER_LEVEL' then 300
                    else 100 end desc,
                  r.minimum_quantity desc,r.effective_from desc,r.version_no desc,r.id desc
                """, (rs, rowNum) -> new RuleMatch(
                rs.getLong("id"), rs.getString("rule_code"), rs.getString("rule_name"), rs.getInt("version_no"),
                rs.getString("business_type"), rs.getString("scope_type"), rs.getString("agreement_type"),
                rs.getLong("product_id"), rs.getString("price_unit"), rs.getString("pricing_mode"),
                rs.getString("benchmark_code"), rs.getBigDecimal("fixed_unit_price"),
                rs.getBigDecimal("processing_fee"), rs.getBigDecimal("specification_fee"),
                rs.getBigDecimal("packaging_fee"), rs.getBigDecimal("freight_fee"),
                rs.getBoolean("price_includes_tax"), rs.getBigDecimal("tax_rate")),
                businessType, productId, priceUnit, Date.valueOf(businessDate), Date.valueOf(businessDate),
                quantity, quantity, businessType, text(customerLevel).toUpperCase(), businessType, id(partnerId),
                businessType, id(partnerId));
    }

    private Benchmark benchmark(String benchmarkCode, LocalDate businessDate) {
        List<Benchmark> rows = jdbc.query("""
                select id,benchmark_code,benchmark_name,version_no,price_unit,unit_price,
                       price_includes_tax,tax_rate
                from price_benchmark_version
                where benchmark_code=? and effective_date<=?
                order by effective_date desc,version_no desc,id desc limit 1
                """, (rs, rowNum) -> new Benchmark(rs.getLong("id"), rs.getString("benchmark_code"),
                rs.getString("benchmark_name"), rs.getInt("version_no"), rs.getString("price_unit"),
                rs.getBigDecimal("unit_price"), rs.getBoolean("price_includes_tax"), rs.getBigDecimal("tax_rate")),
                benchmarkCode, Date.valueOf(businessDate));
        if (rows.isEmpty()) throw new BusinessException("业务日期没有已生效的铜基准价版本");
        return rows.get(0);
    }

    private PartnerContract partnerContract(String businessType, Long partnerId) {
        if (partnerId == null || partnerId <= 0) return new PartnerContract(false, false, BigDecimal.ZERO, "");
        String sql = "SALES".equals(businessType) ? """
                select p.id,cp.tax_included,cp.tax_rate,cp.customer_level
                from partner p join partner_customer_profile cp on cp.partner_id=p.id
                where p.id=? and p.status='ENABLED' and cp.status='ENABLED'
                """ : """
                select p.id,sp.tax_included,sp.tax_rate,'' customer_level
                from partner p join partner_supplier_profile sp on sp.partner_id=p.id
                where p.id=? and p.status='ENABLED' and sp.status='ENABLED'
                """;
        List<PartnerContract> rows = jdbc.query(sql, (rs, rowNum) -> new PartnerContract(true,
                rs.getBoolean("tax_included"), rate(rs.getBigDecimal("tax_rate")), rs.getString("customer_level")), partnerId);
        if (rows.isEmpty()) throw new BusinessException("往来单位不存在、已停用或缺少对应业务身份");
        return rows.get(0);
    }

    private void validateScope(String businessType, String scopeType, Long partnerId,
                               String customerLevel, String agreementType) {
        if ("SALES".equals(businessType) && "SUPPLIER".equals(scopeType)
                || "PURCHASE".equals(businessType) && List.of("CUSTOMER", "CUSTOMER_LEVEL").contains(scopeType)) {
            throw new BusinessException("业务类型与价格适用范围不匹配");
        }
        if (List.of("CUSTOMER", "SUPPLIER").contains(scopeType) && (partnerId == null || partnerId <= 0)) {
            throw new BusinessException("客户价或供应商价必须选择往来单位");
        }
        if ("CUSTOMER_LEVEL".equals(scopeType) && !StringUtils.hasText(customerLevel)) {
            throw new BusinessException("客户等级价必须填写客户等级");
        }
        if ("SPECIAL".equals(agreementType) && !"CUSTOMER".equals(scopeType)) {
            throw new BusinessException("特价只能针对具体客户建立");
        }
        if (List.of("STANDARD", "CUSTOMER_LEVEL").contains(scopeType) && !"STANDARD".equals(agreementType)) {
            throw new BusinessException("标准价和客户等级价必须使用标准价格类型");
        }
        if ("CUSTOMER".equals(scopeType) && !List.of("AGREEMENT", "SPECIAL").contains(agreementType)) {
            throw new BusinessException("指定客户只能建立协议价或特价");
        }
        if ("SUPPLIER".equals(scopeType) && !"AGREEMENT".equals(agreementType)) {
            throw new BusinessException("指定供应商只能建立协议价");
        }
    }

    private void validateRuleIdentity(String code, PriceCenterDtos.RuleSave request, String businessType,
                                      String scopeType, String agreementType, String pricingMode) {
        List<String> identities = jdbc.query("""
                select concat_ws('|',business_type,scope_type,partner_id,customer_level,agreement_type,
                                 product_id,price_unit,pricing_mode)
                from price_rule_version where rule_code=? order by version_no desc limit 1
                """, (rs, rowNum) -> rs.getString(1), code);
        if (identities.isEmpty()) return;
        String requested = String.join("|", businessType, scopeType, String.valueOf(id(request.partnerId())),
                text(request.customerLevel()).toUpperCase(), agreementType, String.valueOf(request.productId()),
                request.priceUnit().trim(), pricingMode);
        if (!identities.get(0).equals(requested)) {
            throw new BusinessException("同一规则代码的新版本不能更换业务范围、商品、单位或计价方式；请创建新规则代码");
        }
    }

    private void assertNoAmbiguousOverlap(PriceCenterDtos.RuleSave request, String businessType,
                                          String scopeType, String agreementType,
                                          BigDecimal minimum, BigDecimal maximum) {
        LocalDate farFuture = LocalDate.of(9999, 12, 31);
        Integer count = jdbc.queryForObject("""
                select count(*) from price_rule_version
                where business_type=? and scope_type=? and partner_id=? and customer_level=?
                  and agreement_type=? and product_id=? and price_unit=? and status='PUBLISHED'
                  and not (coalesce(effective_to,?) < ? or effective_from > ?)
                  and not (coalesce(maximum_quantity,999999999999999999.999999) < ?
                           or minimum_quantity > coalesce(?,999999999999999999.999999))
                """, Integer.class, businessType, scopeType, id(request.partnerId()),
                text(request.customerLevel()).toUpperCase(), agreementType, request.productId(), request.priceUnit().trim(),
                Date.valueOf(farFuture), Date.valueOf(request.effectiveFrom()),
                Date.valueOf(request.effectiveTo() == null ? farFuture : request.effectiveTo()), minimum, maximum);
        if (count != null && count > 0) {
            throw new BusinessException("同一适用范围存在日期和数量都重叠的已发布规则，请调整生效期或数量阶梯");
        }
    }

    private void requireEnabledProduct(Long productId) {
        List<Long> ids = jdbc.queryForList(
                "select id from product where id=? and status='ENABLED' for update", Long.class, productId);
        if (ids.isEmpty()) throw new BusinessException("商品不存在或已停用");
    }

    private PriceCenterDtos.Component component(String code, String name, BigDecimal sourceAmount,
                                                BigDecimal excludingTax, Contract target) {
        return new PriceCenterDtos.Component(code, name, sourceAmount.setScale(4, RoundingMode.HALF_UP),
                enteredPrice(excludingTax, target));
    }

    private String explain(RuleMatch rule, String benchmarkCode, int benchmarkVersion, Contract target) {
        String formula = "FIXED".equals(rule.pricingMode()) ? "固定单价"
                : benchmarkCode + " V" + benchmarkVersion + " + 加工费 + 规格加价 + 包装费 + 运费";
        return sourceName(rule) + "；" + formula + "；按"
                + (target.priceIncludesTax() ? "含税" : "未税") + "、税率"
                + target.taxRate().stripTrailingZeros().toPlainString() + "%输出";
    }

    private String sourceCode(RuleMatch rule) {
        if ("CUSTOMER".equals(rule.scopeType()) && "SPECIAL".equals(rule.agreementType())) return "CUSTOMER_SPECIAL";
        if ("CUSTOMER".equals(rule.scopeType())) return "CUSTOMER_AGREEMENT";
        if ("CUSTOMER_LEVEL".equals(rule.scopeType())) return "CUSTOMER_LEVEL";
        if ("SUPPLIER".equals(rule.scopeType())) return "SUPPLIER_AGREEMENT";
        return "FORMULA".equals(rule.pricingMode()) ? "COPPER_FORMULA" : "STANDARD_PRICE";
    }

    private String sourceName(RuleMatch rule) {
        return switch (sourceCode(rule)) {
            case "CUSTOMER_SPECIAL" -> "客户特价";
            case "CUSTOMER_AGREEMENT" -> "客户协议价";
            case "CUSTOMER_LEVEL" -> "客户等级价";
            case "SUPPLIER_AGREEMENT" -> "供应商协议价";
            case "STANDARD_PRICE" -> "标准价";
            default -> "标准铜价公式";
        };
    }

    private BigDecimal excludingTax(BigDecimal entered, Contract contract) {
        BigDecimal value = price(entered, "价格组成");
        if (!contract.priceIncludesTax() || contract.taxRate().compareTo(BigDecimal.ZERO) == 0) return value;
        return value.divide(ONE.add(contract.taxRate().divide(HUNDRED, 10, RoundingMode.HALF_UP)), 10, RoundingMode.HALF_UP);
    }

    private BigDecimal enteredPrice(BigDecimal excludingTax, Contract contract) {
        BigDecimal value = excludingTax;
        if (contract.priceIncludesTax()) {
            value = value.multiply(ONE.add(contract.taxRate().divide(HUNDRED, 10, RoundingMode.HALF_UP)));
        }
        return value.setScale(4, RoundingMode.HALF_UP);
    }

    private PriceCenterDtos.PriceSnapshot snapshot(String code, String name, Long ruleId, Map<String, Object> payload) {
        return new PriceCenterDtos.PriceSnapshot(code, name, ruleId, json(payload));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new BusinessException("价格快照生成失败");
        }
    }

    private int nextVersion(String table, String keyColumn, String key) {
        Integer value = jdbc.queryForObject("select coalesce(max(version_no),0)+1 from " + table + " where " + keyColumn + "=?",
                Integer.class, key);
        return value == null ? 1 : value;
    }

    private int nextBenchmarkVersion(String benchmarkCode) {
        jdbc.update("insert into price_benchmark_sequence(benchmark_code,last_version) values (?,0) "
                + "on duplicate key update benchmark_code=values(benchmark_code)", benchmarkCode);
        Integer current = jdbc.queryForObject(
                "select last_version from price_benchmark_sequence where benchmark_code=? for update",
                Integer.class, benchmarkCode);
        int next = (current == null ? 0 : current) + 1;
        jdbc.update("update price_benchmark_sequence set last_version=? where benchmark_code=?", next, benchmarkCode);
        return next;
    }

    private long insert(String sql, Object... args) {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int index = 0; index < args.length; index++) statement.setObject(index + 1, args[index]);
            return statement;
        }, holder);
        if (holder.getKey() == null) throw new BusinessException("新增价格版本失败");
        return holder.getKey().longValue();
    }

    private BigDecimal price(BigDecimal value, String label) {
        BigDecimal safe = value == null ? BigDecimal.ZERO : value;
        if (safe.compareTo(BigDecimal.ZERO) < 0) throw new BusinessException(label + "不能小于0");
        return safe.setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal positivePrice(BigDecimal value, String label) {
        BigDecimal safe = value == null ? BigDecimal.ZERO : value;
        if (safe.compareTo(BigDecimal.ZERO) <= 0) throw new BusinessException(label + "必须大于0；赠品请使用赠品业务语义");
        return safe.setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal quantity(BigDecimal value) {
        if (value == null || value.compareTo(BigDecimal.ZERO) < 0) throw new BusinessException("数量不能小于0");
        return value.setScale(6, RoundingMode.HALF_UP);
    }

    private BigDecimal rate(BigDecimal value) {
        BigDecimal safe = value == null ? BigDecimal.ZERO : value;
        if (safe.compareTo(BigDecimal.ZERO) < 0 || safe.compareTo(HUNDRED) > 0) throw new BusinessException("税率必须在0至100之间");
        return safe.setScale(4, RoundingMode.HALF_UP);
    }

    private String reason(String value, String label) {
        String safe = text(value);
        if (safe.length() < 4) throw new BusinessException(label + "至少填写4个字");
        return safe;
    }

    private String enumValue(String value, List<String> allowed, String label) {
        String normalized = text(value).toUpperCase();
        if (!allowed.contains(normalized)) throw new BusinessException(label + "不正确");
        return normalized;
    }

    private String code(String value, String fallback) {
        String normalized = StringUtils.hasText(value) ? value.trim().toUpperCase() : fallback;
        if (!normalized.matches("[A-Z0-9_-]{2,64}")) throw new BusinessException("版本代码只能使用字母、数字、下划线和横线");
        return normalized;
    }

    private String text(String value) { return value == null ? "" : value.trim(); }
    private long id(Long value) { return value == null || value < 0 ? 0L : value; }
    private int bool(boolean value) { return value ? 1 : 0; }
    private LocalDate date(Date value) { return value == null ? null : value.toLocalDate(); }
    private boolean sameText(String left, String right) { return text(left).equalsIgnoreCase(text(right)); }

    private record Contract(boolean priceIncludesTax, BigDecimal taxRate) {}
    private record PartnerContract(boolean exists, boolean priceIncludesTax, BigDecimal taxRate, String customerLevel) {}
    private record Fee(String code, String name, BigDecimal amount) {}
    private record Benchmark(Long id, String code, String name, int versionNo, String priceUnit,
                             BigDecimal unitPrice, boolean priceIncludesTax, BigDecimal taxRate) {}
    private record RuleMatch(Long id, String ruleCode, String ruleName, int versionNo,
                             String businessType, String scopeType, String agreementType,
                             Long productId, String priceUnit, String pricingMode, String benchmarkCode,
                             BigDecimal fixedUnitPrice, BigDecimal processingFee, BigDecimal specificationFee,
                             BigDecimal packagingFee, BigDecimal freightFee,
                             boolean priceIncludesTax, BigDecimal taxRate) {}
}
