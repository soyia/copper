package com.soyia.copperv2.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.soyia.copperv2.dto.ProductImportDtos;
import com.soyia.copperv2.dto.ProductMasterDataDtos;
import com.soyia.copperv2.dto.SimpleCreateRequests;
import com.soyia.copperv2.security.BusinessDataScopeService;
import com.soyia.copperv2.security.ForbiddenException;
import com.soyia.copperv2.security.SecurityContext;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
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
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ProductImportService {
    private static final DateTimeFormatter JOB_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");
    private static final AtomicInteger JOB_COUNTER = new AtomicInteger();
    private static final Set<String> ACTIONS =
            Set.of("CREATE", "CREATE_PENDING", "SAFE_UPDATE", "SKIP", "CONFLICT");
    private static final Set<String> FIXED_TARGETS = Set.of(
            "series", "sku", "name", "unit", "grade", "temper", "standard", "spec", "barcode",
            "originalNumber", "defaultPrice", "status", "sourceUrl", "sourceDocumentVersion",
            "sourcePage", "memo");
    private static final List<String> TEMPLATE_COMMON_HEADERS = List.of(
            "产品系列*", "SKU", "商品名称", "库存单位*", "牌号", "材料状态", "执行标准",
            "规格备注", "条码");
    private static final Set<String> TEMPLATE_EXCLUDED_FIELD_NAMES = Set.of(
            "来源url", "来源网址", "来源链接", "来源文件版本", "来源页码",
            "pdf原件", "产品pdf", "试验条件", "技术试验条件", "检测报告",
            "质量证书", "质量指标", "试验指标");
    private static final Map<String, String> FIXED_HEADER_ALIASES = fixedHeaderAliases();
    private static final String VIEW_PERMISSION = "product.master.complex.view";
    private static final String MANAGE_PERMISSION = "product.master.complex.manage";
    private static final String PRODUCT_MANAGE_PERMISSION = "product.manage";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final ProductImportFileParser parser;
    private final CopperService copperService;
    private final ProductMasterDataService productMasterDataService;
    private final BusinessDataScopeService dataScopeService;
    private final LocalFileStorageService storage;
    private final StorageCapacityService capacity;
    private final TransactionTemplate requiresNew;

    public ProductImportService(JdbcTemplate jdbc,
                                ObjectMapper objectMapper,
                                ProductImportFileParser parser,
                                CopperService copperService,
                                ProductMasterDataService productMasterDataService,
                                BusinessDataScopeService dataScopeService,
                                LocalFileStorageService storage,
                                StorageCapacityService capacity,
                                PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.parser = parser;
        this.copperService = copperService;
        this.productMasterDataService = productMasterDataService;
        this.dataScopeService = dataScopeService;
        this.storage = storage;
        this.capacity = capacity;
        DefaultTransactionDefinition definition = new DefaultTransactionDefinition();
        definition.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.requiresNew = new TransactionTemplate(transactionManager, definition);
    }

    public ProductImportFileParser.TemplateFile template(String format, Long seriesId) {
        requireViewPermission();
        if (seriesId == null || seriesId <= 0) throw new BusinessException("请先选择产品系列");
        ProductImportDtos.SeriesOption selected = templateInfo().series().stream()
                .filter(item -> Objects.equals(item.id(), seriesId))
                .findFirst()
                .orElseThrow(() -> new BusinessException("产品系列不存在"));
        if (!"ENABLED".equals(selected.status())) {
            throw new BusinessException("产品系列已停用，不能下载新商品模板");
        }
        List<ProductImportDtos.SeriesFieldOption> fields = selected.fields().stream()
                .filter(ProductImportService::includeInDailyTemplate)
                .filter(field -> !isCommonReferenceField(field))
                .toList();
        List<String> headers = TEMPLATE_COMMON_HEADERS.stream()
                .map(header -> requiredCommonHeader(selected, header) ? header + "*" : header)
                .collect(Collectors.toCollection(ArrayList::new));
        for (ProductImportDtos.SeriesFieldOption field : fields) {
            String header = templateFieldHeader(field);
            if (!headers.contains(header)) headers.add(header);
        }
        String baseUnit = templateBaseUnit(seriesId);
        List<List<String>> exampleRows = List.of(
                templateExampleRow(selected, fields, headers, baseUnit, 1),
                templateExampleRow(selected, fields, headers, baseUnit, 2));
        return parser.template(format, new ProductImportFileParser.TemplateDefinition(
                selected.categoryName(), selected.name(), headers, exampleRows));
    }

    public ProductImportDtos.TemplateInfo templateInfo() {
        requireViewPermission();
        Map<Long, SeriesBuilder> series = new LinkedHashMap<>();
        jdbc.query("""
                select s.id series_id,s.name series_name,s.alias_names,s.status,
                       c.name category_name,sf.id series_field_id,sf.field_name,
                       coalesce(nullif(sf.display_name,''),sf.field_name) display_name,
                       cf.field_type,coalesce(nullif(sf.option_json,''),cf.option_json) option_json,
                       coalesce((
                         select ir.reference_type
                         from product_identity_rule ir
                         where ir.product_series_id=s.id
                           and ir.schema_version=s.identity_schema_version
                           and ir.series_field_id=sf.id
                           and ir.source_type='SERIES_FIELD'
                           and ir.status='ENABLED'
                         order by ir.sort_no,ir.id limit 1
                       ),'') identity_reference_type,
                       sf.unit_name,sf.required,sf.sku_dimension,sf.include_in_spec,
                       sf.min_value,sf.max_value,sf.decimal_scale
                from product_series s
                left join product_category c on c.id=s.category_id
                left join product_series_field sf on sf.product_series_id=s.id and sf.status='ENABLED'
                left join product_custom_field cf on cf.id=sf.field_id
                order by s.name,sf.sort_no,sf.id
                """, rs -> {
            long seriesId = rs.getLong("series_id");
            String seriesName = rs.getString("series_name");
            String categoryName = text(rs.getString("category_name"));
            List<String> aliases = splitAliases(rs.getString("alias_names"));
            String seriesStatus = rs.getString("status");
            SeriesBuilder builder = series.computeIfAbsent(seriesId, ignored -> new SeriesBuilder(
                    seriesId, seriesName, categoryName, aliases, seriesStatus, new ArrayList<>()));
            long fieldId = rs.getLong("series_field_id");
            if (fieldId > 0) {
                builder.fields().add(new ProductImportDtos.SeriesFieldOption(
                        fieldId, "field:" + fieldId, rs.getString("field_name"),
                        rs.getString("display_name"), rs.getString("field_type"),
                        text(rs.getString("identity_reference_type")),
                        text(rs.getString("unit_name")), rs.getInt("required") == 1,
                        rs.getInt("sku_dimension") == 1, rs.getInt("include_in_spec") == 1,
                        rs.getBigDecimal("min_value"), rs.getBigDecimal("max_value"),
                        rs.getInt("decimal_scale"), parseOptions(rs.getString("option_json"))));
            }
        });
        List<ProductImportDtos.SeriesOption> seriesOptions = series.values().stream()
                .map(builder -> new ProductImportDtos.SeriesOption(
                        builder.id(), builder.name(), builder.categoryName(), builder.aliases(),
                        builder.status(), List.copyOf(builder.fields())))
                .toList();
        List<ProductImportDtos.UnitOption> units = jdbc.query("""
                select u.id,u.name,u.abbreviation,uc.name category_name
                from unit u join unit_category uc on uc.id=u.unit_category_id
                where u.status='ENABLED' and uc.status='ENABLED'
                order by uc.id,u.id
                """, (rs, rowNum) -> new ProductImportDtos.UnitOption(
                rs.getLong("id"), rs.getString("name"), text(rs.getString("abbreviation")),
                canonicalUnit(rs.getString("name"), rs.getString("abbreviation")),
                rs.getString("category_name")));
        return new ProductImportDtos.TemplateInfo(
                ProductImportFileParser.TEMPLATE_VERSION,
                ProductImportFileParser.MAX_ROWS,
                ProductImportFileParser.MAX_FILE_SIZE,
                List.of(
                        new ProductImportDtos.TemplateColumn("series", "产品系列", true,
                                "填写已启用系列名称、别名或ID；不会自动创建系列"),
                        new ProductImportDtos.TemplateColumn("sku", "SKU", false,
                                "留空时按系列ID和规格签名生成稳定SKU"),
                        new ProductImportDtos.TemplateColumn("name", "商品名称", false,
                                "留空时按系列和规格显示字段生成"),
                        new ProductImportDtos.TemplateColumn("unit", "库存单位", true,
                                "必须是已启用单位名称、缩写或单位别名，并与系列包装模板主单位一致"),
                        new ProductImportDtos.TemplateColumn("grade", "牌号", false,
                                "使用已配置规范牌号或别名"),
                        new ProductImportDtos.TemplateColumn("temper", "材料状态", false,
                                "使用已配置状态名称、代码或英文名"),
                        new ProductImportDtos.TemplateColumn("standard", "执行标准", false,
                                "使用已启用标准代码、名称或英文名"),
                        new ProductImportDtos.TemplateColumn("field:<seriesFieldId>", "动态规格字段", false,
                                "也可直接使用“属性:字段显示名”作为源文件列名")
                ),
                seriesOptions,
                units);
    }

    private String templateBaseUnit(Long seriesId) {
        SeriesConfig config = loadSeriesCatalog().byId().get(seriesId);
        if (config != null && StringUtils.hasText(config.expectedBaseUnit())) {
            return config.expectedBaseUnit();
        }
        List<String> allowed = jdbc.query("""
                select coalesce(nullif(u.abbreviation,''),u.name)
                from product_series_allowed_unit a
                join unit u on u.id=a.unit_id
                where a.product_series_id=? and a.purpose='INVENTORY'
                  and a.status='ENABLED' and u.status='ENABLED'
                order by a.is_default desc,a.sort_no,a.id
                limit 1
                """, (rs, rowNum) -> text(rs.getString(1)), seriesId);
        if (!allowed.isEmpty() && StringUtils.hasText(allowed.get(0))) return allowed.get(0);
        List<String> existing = jdbc.query("""
                select unit from product
                where product_series_id=? and status='ENABLED' and unit<>''
                order by id limit 1
                """, (rs, rowNum) -> text(rs.getString(1)), seriesId);
        return existing.isEmpty() ? "kg" : existing.get(0);
    }

    private List<String> templateExampleRow(
            ProductImportDtos.SeriesOption series,
            List<ProductImportDtos.SeriesFieldOption> fields,
            List<String> headers,
            String baseUnit,
            int index) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("产品系列*", series.name());
        values.put("SKU", ProductImportFileParser.EXAMPLE_SKU_PREFIX
                + series.id() + "-" + String.format("%02d", index));
        values.put("商品名称", ProductImportFileParser.EXAMPLE_ROW_MARKER
                + series.name() + "示例" + index);
        values.put("库存单位*", baseUnit);
        if (isCopperOrAluminumFamily(series)) {
            boolean aluminum = (text(series.categoryName()) + series.name()).contains("铝");
            values.put("牌号", aluminum ? "1060" : "T2");
            values.put("材料状态", aluminum ? "O" : "O60");
            values.put("执行标准", aluminum ? "GB/T 3880.2" : "GB/T 17791");
        }
        values.put("规格备注", ProductImportFileParser.EXAMPLE_ROW_MARKER
                + "仅用于说明填写格式");
        values.put("条码", index == 1 ? "6900000000001" : "");
        for (ProductImportDtos.SeriesFieldOption field : fields) {
            values.put(templateFieldHeader(field), templateExampleValue(field, index));
        }
        return headers.stream().map(header -> values.getOrDefault(
                header, values.getOrDefault(header.replaceFirst("[*＊]+$", ""), ""))).toList();
    }

    private String templateExampleValue(ProductImportDtos.SeriesFieldOption field, int index) {
        if (field.options() != null && !field.options().isEmpty()) {
            return field.options().get(Math.min(index - 1, field.options().size() - 1));
        }
        if ("NUMBER".equals(field.fieldType())) {
            BigDecimal minimum = field.minValue();
            BigDecimal maximum = field.maxValue();
            BigDecimal value = minimum == null ? BigDecimal.valueOf(index) : minimum;
            if (value.compareTo(BigDecimal.ZERO) <= 0) value = BigDecimal.valueOf(index);
            if (index > 1) value = value.add(BigDecimal.ONE);
            if (maximum != null && value.compareTo(maximum) > 0) value = maximum;
            int scale = Math.max(0, Math.min(6,
                    field.decimalScale() == null ? 0 : field.decimalScale()));
            return value.setScale(scale, RoundingMode.DOWN).stripTrailingZeros().toPlainString();
        }
        String name = text(field.displayName());
        if (name.contains("图纸编号")) return "DWG-" + String.format("%03d", index);
        if (name.contains("图纸版本")) return index == 1 ? "A" : "B";
        if (name.contains("客户图号")) return "CUSTOM-" + String.format("%03d", index);
        if (name.contains("交货形态")) return index == 1 ? "直条" : "盘卷";
        if (name.contains("材质")) return index == 1 ? "紫铜" : "黄铜";
        if (name.contains("颜色")) return index == 1 ? "本色" : "红色";
        return name + "示例" + index;
    }

    private static boolean includeInDailyTemplate(ProductImportDtos.SeriesFieldOption field) {
        if (!(Boolean.TRUE.equals(field.required())
                || Boolean.TRUE.equals(field.skuDimension())
                || Boolean.TRUE.equals(field.includeInSpec()))) {
            return false;
        }
        String name = normalizedFieldName(text(field.name()));
        String displayName = normalizedFieldName(text(field.displayName()));
        return !TEMPLATE_EXCLUDED_FIELD_NAMES.contains(name)
                && !TEMPLATE_EXCLUDED_FIELD_NAMES.contains(displayName);
    }

    private static boolean isCommonReferenceField(ProductImportDtos.SeriesFieldOption field) {
        return StringUtils.hasText(commonReferenceType(field));
    }

    private static boolean requiredCommonHeader(
            ProductImportDtos.SeriesOption series, String header) {
        String expectedType = switch (header) {
            case "牌号" -> "GRADE";
            case "材料状态" -> "TEMPER";
            case "执行标准" -> "STANDARD";
            default -> "";
        };
        if (!StringUtils.hasText(expectedType)) return false;
        return series.fields().stream().anyMatch(field ->
                Boolean.TRUE.equals(field.required())
                        && expectedType.equals(commonReferenceType(field)));
    }

    private static String commonReferenceType(ProductImportDtos.SeriesFieldOption field) {
        String referenceType = upper(field.referenceType());
        if (Set.of("GRADE", "TEMPER", "STANDARD").contains(referenceType)) return referenceType;
        String name = normalizedFieldName(text(field.name()));
        String displayName = normalizedFieldName(text(field.displayName()));
        if (Set.of("牌号", "材质牌号", "牌号材质", "磁材牌号")
                .contains(name) || Set.of("牌号", "材质牌号", "牌号材质", "磁材牌号")
                .contains(displayName)) return "GRADE";
        if (Set.of("材料状态", "状态").contains(name)
                || Set.of("材料状态", "状态").contains(displayName)) return "TEMPER";
        if (Set.of("执行标准", "标准").contains(name)
                || Set.of("执行标准", "标准").contains(displayName)) return "STANDARD";
        return "";
    }

    private static String templateFieldHeader(ProductImportDtos.SeriesFieldOption field) {
        return "属性:" + field.displayName()
                + (Boolean.TRUE.equals(field.required()) ? "*" : "");
    }

    private static boolean isCopperOrAluminumFamily(ProductImportDtos.SeriesOption series) {
        String value = text(series.categoryName()) + text(series.name());
        return value.contains("铜") || value.contains("铝");
    }

    @Transactional
    public ProductImportDtos.Preview preview(MultipartFile file, String mappingJson) {
        requireManagePermission();
        SecurityContext.Principal actor = SecurityContext.require();
        long actorId = id(actor.userId());
        capacity.assertLargeWriteAllowed("商品主数据导入");
        ProductImportFileParser.ParsedFile parsed = parser.parse(file);
        Map<String, String> mapping = parseMapping(mappingJson, parsed.headers());
        String canonicalMappingJson = json(new TreeMap<>(mapping));
        String mappingSha = sha256(canonicalMappingJson);
        String fileSha = sha256(parsed.bytes());
        SeriesCatalog seriesCatalog = loadSeriesCatalog();
        UnitCatalog unitCatalog = loadUnitCatalog();
        List<ProductMasterDataDtos.ReferenceValue> referenceValues =
                productMasterDataService.referenceValues(null, null, "ENABLED");
        List<IdentityRuleSnapshot> identityRules = loadIdentityRules();
        ProductReferenceResolver referenceResolver =
                new MasterDataReferenceResolver(productMasterDataService);
        ExistingCatalog existingCatalog = loadExistingCatalog();
        boolean productCreationAllowed =
                dataScopeService.hasFullMasterScope("PRODUCT");
        String configurationSha = configurationFingerprint(
                seriesCatalog, unitCatalog, referenceValues, identityRules, existingCatalog);
        List<Long> existingJobs = jdbc.query("""
                select id from product_import_job
                where source_file_sha256=? and mapping_sha256=? and template_version=?
                  and configuration_sha256=? and imported_by=?
                """, (rs, rowNum) -> rs.getLong("id"), fileSha, mappingSha,
                ProductImportFileParser.TEMPLATE_VERSION, configurationSha, actorId);
        if (!existingJobs.isEmpty()) return detailInternal(existingJobs.get(0), true);

        String actorName = actorName(actor);
        String jobNo = "SP-IMP-" + LocalDateTime.now().format(JOB_TIME)
                + String.format("%03d", Math.floorMod(JOB_COUNTER.incrementAndGet(), 1000));
        Long jobId;
        try {
            jobId = insert("""
                    insert into product_import_job
                    (job_no,template_version,source_file_name,source_file_format,source_file_sha256,
                     source_file_size,source_storage_path,mapping_json,mapping_sha256,configuration_sha256,status,
                     imported_by,imported_by_name)
                    values (?,?,?,?,?,?,'',?,?,?,'PREVIEWED',?,?)
                    """, jobNo, ProductImportFileParser.TEMPLATE_VERSION, parsed.fileName(), parsed.format(),
                    fileSha, parsed.bytes().length, canonicalMappingJson, mappingSha, configurationSha,
                    actorId, actorName);
        } catch (DuplicateKeyException ex) {
            List<Long> concurrentJob = jdbc.query("""
                    select id from product_import_job
                    where source_file_sha256=? and mapping_sha256=? and template_version=?
                      and configuration_sha256=? and imported_by=?
                    for update
                    """, (rs, rowNum) -> rs.getLong("id"), fileSha, mappingSha,
                    ProductImportFileParser.TEMPLATE_VERSION, configurationSha, actorId);
            if (!concurrentJob.isEmpty()) return detailInternal(concurrentJob.get(0), true);
            throw ex;
        }
        String extension = "CSV".equals(parsed.format()) ? ".csv" : ".xlsx";
        String storagePath = storage.store(parsed.bytes(), "product-imports", extension);
        int storageUpdated = jdbc.update("""
                update product_import_job set source_storage_path=?
                where id=? and imported_by=?
                """, storagePath, jobId, actorId);
        if (storageUpdated != 1) {
            throw new ForbiddenException("商品导入作业不存在或不属于当前操作人");
        }

        Map<String, Integer> sourceHashRows = new HashMap<>();
        Map<String, FileIdentity> fileSignatures = new HashMap<>();
        Map<String, FileIdentity> fileSkus = new HashMap<>();
        Counters counters = new Counters();

        for (ProductImportFileParser.RawRow sourceRow : parsed.rows()) {
            NormalizationResult normalized;
            try {
                normalized = normalize(sourceRow, mapping, seriesCatalog, unitCatalog,
                        referenceResolver);
            } catch (RuntimeException ex) {
                String message = ex instanceof BusinessException && StringUtils.hasText(ex.getMessage())
                        ? ex.getMessage() : "该行发生无法恢复的格式校验错误";
                normalized = NormalizationResult.failed(message);
            }
            String sourceHash = sha256(json(new TreeMap<>(sourceRow.values())));
            Integer duplicateRow = sourceHashRows.putIfAbsent(sourceHash, sourceRow.sourceRowNo());
            if (duplicateRow != null && !normalized.hasBlockingConflict()) {
                normalized = normalized.asSkip(new ConflictDraft(
                        "FILE_EXACT_DUPLICATE", "", "", "", "WARNING",
                        "文件内与第" + duplicateRow + "行内容完全相同，本行跳过"));
            }
            if (!normalized.hasBlockingConflict() && !"SKIP".equals(normalized.action())) {
                normalized = evaluateExisting(normalized, existingCatalog);
            }
            normalized = evaluateFileIdentity(sourceRow.sourceRowNo(), normalized, fileSignatures, fileSkus);
            if (!productCreationAllowed
                    && Set.of("CREATE", "CREATE_PENDING").contains(normalized.action())) {
                normalized = normalized.withConflict(ConflictDraft.blocking(
                        "PRODUCT_SCOPE_CREATE_DENIED", "product", "", "",
                        "当前角色的商品数据范围受限，不能通过导入新建商品"));
            }

            String validationStatus = normalized.hasBlockingConflict() ? "INVALID" : "VALID";
            String validationMessage = normalized.validationMessage();
            String action = normalized.hasBlockingConflict() ? "CONFLICT" : normalized.action();
            NormalizedProduct product = normalized.product();
            Long targetId = product == null ? 0L : product.targetProductId();
            String targetSku = product == null ? "" : product.targetProductSku();
            Long rowId = insert("""
                    insert into product_import_row
                    (job_id,source_row_no,raw_data_json,normalized_data_json,source_hash,product_signature,
                     validation_status,validation_message,proposed_action,decided_action,decision_reason,
                     target_product_id,target_product_sku)
                    values (?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, jobId, sourceRow.sourceRowNo(), json(sourceRow.values()),
                    product == null ? "{}" : json(product), sourceHash,
                    product == null ? "" : text(product.productSignature()),
                    validationStatus, limited(validationMessage, 1000), action, action,
                    normalized.decisionReason(), targetId, targetSku);
            for (ConflictDraft conflict : normalized.conflicts()) {
                jdbc.update("""
                        insert into product_import_conflict
                        (job_id,row_id,conflict_type,field_key,source_value,target_value,severity,message)
                        values (?,?,?,?,?,?,?,?)
                        """, jobId, rowId, conflict.type(), conflict.fieldKey(),
                        limited(conflict.sourceValue(), 1000), limited(conflict.targetValue(), 1000),
                        conflict.severity(), limited(conflict.message(), 1000));
            }
            insertLineage(jobId, rowId, fileSha, sourceRow, normalized.lineage());
            counters.add(action, "INVALID".equals(validationStatus));
        }
        jdbc.update("""
                update product_import_job
                set total_rows=?,create_rows=?,safe_update_rows=?,skip_rows=?,conflict_rows=?,invalid_rows=?
                where id=?
                """, parsed.rows().size(), counters.creates, counters.updates, counters.skips,
                counters.conflicts, counters.invalid, jobId);
        event(jobId, 0L, "PREVIEW_CREATED", "SUCCESS", Map.of(
                "fileSha256", fileSha, "mappingSha256", mappingSha,
                "configurationSha256", configurationSha,
                "totalRows", parsed.rows().size(), "counts", counters.asMap()));
        return detailInternal(jobId, false);
    }

    public List<ProductImportDtos.JobSummary> jobs() {
        requireViewPermission();
        return jdbc.query(jobSelect() + " where imported_by=? order by id desc",
                jobMapper(), currentActorId());
    }

    public ProductImportDtos.Preview detail(Long jobId) {
        requireViewPermission();
        return detailInternal(jobId, false);
    }

    public SourceFileContent sourceFile(Long jobId) {
        requireViewPermission();
        List<Map<String, Object>> jobs = jdbc.queryForList("""
                select source_file_name,source_file_format,source_file_sha256,
                       source_file_size,source_storage_path
                from product_import_job where id=? and imported_by=?
                """, jobId, currentActorId());
        if (jobs.isEmpty()) {
            throw new ForbiddenException("商品导入作业不存在或不属于当前操作人");
        }
        Map<String, Object> job = jobs.get(0);
        byte[] content = storage.read(String.valueOf(job.get("source_storage_path")));
        String expectedSha = String.valueOf(job.get("source_file_sha256"));
        long expectedSize = ((Number) job.get("source_file_size")).longValue();
        if (content.length != expectedSize || !Objects.equals(sha256(content), expectedSha)) {
            throw new BusinessException("商品导入原文件校验失败，审计证据可能已损坏");
        }
        String format = String.valueOf(job.get("source_file_format"));
        String contentType = "CSV".equals(format)
                ? "text/csv;charset=UTF-8"
                : "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        return new SourceFileContent(
                String.valueOf(job.get("source_file_name")), contentType, content);
    }

    @Transactional
    public ProductImportDtos.Preview decide(Long jobId, Long rowId,
                                            ProductImportDtos.RowDecision request) {
        requireManagePermission();
        String action = upper(request.action());
        if (!ACTIONS.contains(action)) throw new BusinessException("行处理动作不合法");
        Map<String, Object> row = rowForUpdate(jobId, rowId);
        String proposed = String.valueOf(row.get("proposed_action"));
        String validation = String.valueOf(row.get("validation_status"));
        String commitStatus = String.valueOf(row.get("commit_status"));
        if ("COMPLETED".equals(jobStatus(jobId)) || "COMMITTING".equals(jobStatus(jobId))) {
            throw new BusinessException("导入作业已提交或正在提交，不能修改行处理动作");
        }
        if (Set.of("SUCCEEDED", "SKIPPED").contains(commitStatus)) {
            throw new BusinessException("该行已完成提交或跳过，不能再修改处理动作");
        }
        if (((Number) row.get("row_version")).intValue() != request.expectedRowVersion()) {
            throw new BusinessException("该行已被其他操作更新，请刷新后重试");
        }
        if ("SKIP".equals(action)) {
            // Any staged row may be explicitly skipped; source evidence and original conflict remain.
            if (!"VALID".equals(validation)
                    && text(request.reason()).trim().length() < 4) {
                throw new BusinessException("跳过冲突或无效行必须填写至少4个字符的原因");
            }
        } else if (!"VALID".equals(validation) || !Objects.equals(proposed, action)) {
            throw new BusinessException("冲突或无效行不能被强制写入；请修正源文件或主数据后重新预检");
        }
        long expectedTarget = ((Number) row.get("target_product_id")).longValue();
        if ("SAFE_UPDATE".equals(action) && request.targetProductId() != null
                && request.targetProductId() != expectedTarget) {
            throw new BusinessException("不能把安全更新改指向未经预检的其他商品");
        }
        if ("SAFE_UPDATE".equals(action)) {
            dataScopeService.assertMasterAllowed("PRODUCT", expectedTarget);
        }
        int updated = jdbc.update("""
                update product_import_row
                set decided_action=?,decision_reason=?,row_version=row_version+1
                where id=? and job_id=? and row_version=?
                """, action, limited(text(request.reason()), 500), rowId, jobId, request.expectedRowVersion());
        if (updated != 1) throw new BusinessException("该行已被其他操作更新，请刷新后重试");
        refreshJobActionCounts(jobId);
        int jobUpdated = jdbc.update("""
                update product_import_job set lock_version=lock_version+1
                where id=? and imported_by=?
                """, jobId, currentActorId());
        if (jobUpdated != 1) {
            throw new ForbiddenException("商品导入作业不存在或不属于当前操作人");
        }
        event(jobId, rowId, "ROW_DECISION_CHANGED", "SUCCESS", Map.of(
                "from", proposed, "to", action, "reason", text(request.reason())));
        return detailInternal(jobId, false);
    }

    private void refreshJobActionCounts(Long jobId) {
        assertOwnedJob(jobId);
        Map<String, Object> counts = jdbc.queryForMap("""
                select
                  sum(case when decided_action in ('CREATE','CREATE_PENDING') then 1 else 0 end) create_rows,
                  sum(case when decided_action='SAFE_UPDATE' then 1 else 0 end) safe_update_rows,
                  sum(case when decided_action='SKIP' then 1 else 0 end) skip_rows,
                  sum(case when decided_action='CONFLICT' then 1 else 0 end) conflict_rows,
                  sum(case when validation_status='INVALID' then 1 else 0 end) invalid_rows
                from product_import_row where job_id=?
                """, jobId);
        jdbc.update("""
                update product_import_job
                set create_rows=?,safe_update_rows=?,skip_rows=?,conflict_rows=?,invalid_rows=?
                where id=? and imported_by=?
                """, number(counts.get("create_rows")), number(counts.get("safe_update_rows")),
                number(counts.get("skip_rows")), number(counts.get("conflict_rows")),
                number(counts.get("invalid_rows")), jobId, currentActorId());
    }

    public ProductImportDtos.CommitResult commit(Long jobId, ProductImportDtos.CommitRequest request) {
        requireManagePermission();
        String claimToken = UUID.randomUUID().toString();
        CommitClaim claim = Objects.requireNonNull(
                requiresNew.execute(status -> claim(jobId, request, claimToken)));
        if (claim.completed()) return commitResult(jobId);

        List<CommitRow> rows = jdbc.query("""
                select r.id,r.normalized_data_json,r.validation_status,r.decided_action,
                       r.target_product_id,r.commit_status
                from product_import_row r
                join product_import_job j on j.id=r.job_id
                where r.job_id=? and j.imported_by=?
                order by r.source_row_no
                """, (rs, rowNum) -> new CommitRow(
                rs.getLong("id"), rs.getString("normalized_data_json"),
                rs.getString("validation_status"), rs.getString("decided_action"),
                rs.getLong("target_product_id"), rs.getString("commit_status")),
                jobId, currentActorId());
        List<Long> importedProductIds = new ArrayList<>();
        for (CommitRow row : rows) {
            if ("SUCCEEDED".equals(row.commitStatus()) || "SKIPPED".equals(row.commitStatus())) continue;
            try {
                Long productId = requiresNew.execute(
                        status -> processCommitRow(jobId, row.id(), claimToken));
                if (productId != null && productId > 0) importedProductIds.add(productId);
            } catch (RuntimeException ex) {
                requiresNew.executeWithoutResult(
                        status -> markFailed(jobId, row.id(), claimToken, safeError(ex)));
            }
        }
        requiresNew.executeWithoutResult(status -> finalizeJob(jobId, claimToken));
        ProductImportDtos.CommitResult result = commitResult(jobId);
        if (!importedProductIds.isEmpty()) {
            return new ProductImportDtos.CommitResult(
                    result.jobId(), result.status(), result.createdRows(), result.updatedRows(),
                    result.skippedRows(), result.failedRows(), result.lockVersion(),
                    List.copyOf(importedProductIds));
        }
        return result;
    }

    private CommitClaim claim(
            Long jobId, ProductImportDtos.CommitRequest request, String claimToken) {
        Map<String, Object> job = jobForUpdate(jobId);
        String status = String.valueOf(job.get("status"));
        int lockVersion = ((Number) job.get("lock_version")).intValue();
        if ("COMPLETED".equals(status)) return new CommitClaim(true);
        boolean recovery = "COMMITTING".equals(status);
        if (recovery) {
            Integer leaseAlive = jdbc.queryForObject("""
                    select case when heartbeat_at is not null
                                      and heartbeat_at>=date_sub(current_timestamp,interval 5 minute)
                                then 1 else 0 end
                    from product_import_job where id=?
                      and imported_by=?
                    """, Integer.class, jobId, currentActorId());
            if (leaseAlive != null && leaseAlive == 1) {
                throw new BusinessException("导入作业正在提交，请勿重复操作");
            }
        } else if (!Set.of("PREVIEWED", "COMPLETED_WITH_ERRORS").contains(status)) {
            throw new BusinessException("当前导入作业状态不能提交");
        }
        if (request == null || request.expectedLockVersion() == null
                || request.expectedLockVersion() != lockVersion) {
            throw new BusinessException("导入作业已变化，请刷新预览后重试");
        }
        Integer productWrites = jdbc.queryForObject("""
                select count(*) from product_import_row
                where job_id=? and decided_action in ('CREATE','CREATE_PENDING','SAFE_UPDATE')
                  and commit_status not in ('SUCCEEDED','SKIPPED')
                """, Integer.class, jobId);
        if (productWrites != null && productWrites > 0) {
            requireProductManagePermission();
        }
        List<Map<String, Object>> pendingWrites = jdbc.queryForList("""
                select decided_action,target_product_id
                from product_import_row
                where job_id=? and validation_status='VALID'
                  and decided_action in ('CREATE','CREATE_PENDING','SAFE_UPDATE')
                  and commit_status not in ('SUCCEEDED','SKIPPED')
                """, jobId);
        boolean creates = pendingWrites.stream().anyMatch(row ->
                Set.of("CREATE", "CREATE_PENDING").contains(
                        String.valueOf(row.get("decided_action"))));
        if (creates) {
            dataScopeService.assertMasterCreationAllowed("PRODUCT");
        }
        for (Map<String, Object> pendingWrite : pendingWrites) {
            if ("SAFE_UPDATE".equals(String.valueOf(pendingWrite.get("decided_action")))) {
                dataScopeService.assertMasterAllowed(
                        "PRODUCT", ((Number) pendingWrite.get("target_product_id")).longValue());
            }
        }
        Integer safeUpdates = jdbc.queryForObject("""
                select count(*) from product_import_row
                where job_id=? and validation_status='VALID' and decided_action='SAFE_UPDATE'
                  and commit_status not in ('SUCCEEDED','SKIPPED')
                """, Integer.class, jobId);
        if (safeUpdates != null && safeUpdates > 0
                && !Boolean.TRUE.equals(request.allowSafeUpdates())) {
            throw new BusinessException("本作业包含安全更新；请明确确认允许更新已有商品");
        }
        int updated = jdbc.update("""
                update product_import_job
                set status='COMMITTING',claim_token=?,heartbeat_at=current_timestamp,
                    lock_version=lock_version+1
                where id=? and imported_by=? and lock_version=? and status=?
                """, claimToken, jobId, currentActorId(), lockVersion, status);
        if (updated != 1) throw new BusinessException("导入作业已被其他操作更新，请刷新后重试");
        event(jobId, 0L, recovery ? "COMMIT_RECOVERED" : "COMMIT_STARTED", "SUCCESS", Map.of(
                "allowSafeUpdates", Boolean.TRUE.equals(request.allowSafeUpdates()),
                "fromStatus", status));
        return new CommitClaim(false);
    }

    private Long processCommitRow(Long jobId, Long rowId, String claimToken) {
        assertCommitOwner(jobId, claimToken);
        Map<String, Object> current = rowForUpdate(jobId, rowId);
        String commitStatus = String.valueOf(current.get("commit_status"));
        if (Set.of("SUCCEEDED", "SKIPPED").contains(commitStatus)) return null;
        CommitRow row = new CommitRow(
                ((Number) current.get("id")).longValue(),
                String.valueOf(current.get("normalized_data_json")),
                String.valueOf(current.get("validation_status")),
                String.valueOf(current.get("decided_action")),
                ((Number) current.get("target_product_id")).longValue(),
                commitStatus);
        if ("SKIP".equals(row.action())) {
            markSkipped(jobId, row.id());
            return null;
        }
        requireProductManagePermission();
        if (!"VALID".equals(row.validationStatus()) || "CONFLICT".equals(row.action())) {
            markBlocked(jobId, row.id(), "该行仍有未解决冲突，未写入正式商品");
            return null;
        }
        return commitRow(jobId, row);
    }

    private Long commitRow(Long jobId, CommitRow row) {
        NormalizedProduct product = read(row.normalizedJson(), NormalizedProduct.class);
        if (product == null || product.seriesId() == null) throw new BusinessException("规范化商品快照缺失");
        boolean pendingCreate = "CREATE_PENDING".equals(row.action());
        SimpleCreateRequests.ProductCreate request = productRequest(product, pendingCreate);
        Long productId;
        if ("CREATE".equals(row.action()) || pendingCreate) {
            dataScopeService.assertMasterCreationAllowed("PRODUCT");
            assertIdentityStillCurrent(product, request, true, pendingCreate);
            assertStillCreate(product);
            productId = copperService.createProduct(request);
        } else if ("SAFE_UPDATE".equals(row.action())) {
            if (row.targetProductId() <= 0 || product.targetProductId() == null
                    || row.targetProductId() != product.targetProductId()) {
                throw new BusinessException("安全更新目标商品缺失");
            }
            dataScopeService.assertMasterAllowed("PRODUCT", row.targetProductId());
            ExistingProduct current = loadExistingProduct(row.targetProductId());
            if (!Objects.equals(product.targetFingerprint(), fingerprint(current))) {
                throw new BusinessException("目标商品在预检后已发生变化，请重新预检");
            }
            assertIdentityStillCurrent(product, request, false, false);
            productId = copperService.updateProduct(row.targetProductId(), request);
        } else {
            throw new BusinessException("当前行处理动作不能写入商品");
        }
        jdbc.update("""
                update product_import_row
                set commit_status='SUCCEEDED',commit_error='',imported_product_id=?,committed_at=current_timestamp
                where id=? and job_id=?
                """, productId, row.id(), jobId);
        jdbc.update("""
                update product_import_source_lineage
                set target_entity_type='PRODUCT',target_entity_id=?
                where row_id=? and job_id=?
                """, productId, row.id(), jobId);
        event(jobId, row.id(), "ROW_COMMITTED", "SUCCESS", Map.of(
                "action", row.action(), "productId", productId, "sku", product.sku()));
        return productId;
    }

    private void assertStillCreate(NormalizedProduct product) {
        Integer skuCount = jdbc.queryForObject("select count(*) from product where sku=?",
                Integer.class, product.sku());
        Integer signatureCount = StringUtils.hasText(product.productSignature())
                ? jdbc.queryForObject("""
                  select count(*) from product where product_series_id=? and identity_signature=?
                  """, Integer.class, product.seriesId(), product.productSignature())
                : 0;
        if ((skuCount != null && skuCount > 0) || (signatureCount != null && signatureCount > 0)) {
            throw new BusinessException("预检后已存在相同SKU或规格身份的商品，请重新预检");
        }
    }

    private void assertIdentityStillCurrent(
            NormalizedProduct product,
            SimpleCreateRequests.ProductCreate request,
            boolean requireCurrentSchema,
            boolean allowPendingStructuredIdentity) {
        ProductMasterDataDtos.ProductDuplicateCheck identity =
                productMasterDataService.evaluateCandidateIdentity(
                        request, requireCurrentSchema ? null : product.identitySchemaVersion());
        boolean acceptablePending = allowPendingStructuredIdentity
                && !Boolean.TRUE.equals(identity.complete())
                && pendingOnlyStructured(identity, product.seriesId(), identity.schemaVersion());
        if ((!Boolean.TRUE.equals(identity.complete()) && !acceptablePending)
                || (Boolean.TRUE.equals(identity.complete())
                    && !Objects.equals(identity.identitySignature(), product.productSignature()))
                || identity.schemaVersion() == null
                || identity.schemaVersion() != product.identitySchemaVersion()) {
            throw new BusinessException("商品规格或重复检查规则在预检后已变化，请重新预检");
        }
    }

    private SimpleCreateRequests.ProductCreate productRequest(
            NormalizedProduct product, boolean pendingIdentity) {
        List<SimpleCreateRequests.ProductFieldValueCreate> fields = product.fieldValues().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new SimpleCreateRequests.ProductFieldValueCreate(entry.getKey(), entry.getValue()))
                .toList();
        return new SimpleCreateRequests.ProductCreate(
                null, product.seriesId(), null, product.sku(), product.name(),
                product.grade(), product.temper(), product.standard(), product.spec(), "{}",
                product.barcode(), product.originalNumber(), product.unit(), product.defaultPrice(),
                product.status(), fields, pendingIdentity ? "PENDING_REVIEW" : "PUBLISHED");
    }

    private ProductMasterDataDtos.ProductDuplicateCheck evaluateIdentity(
            Long seriesId,
            String sku,
            String name,
            String grade,
            String temper,
            String standard,
            String spec,
            String barcode,
            String originalNumber,
            String unit,
            BigDecimal defaultPrice,
            String status,
            Map<Long, String> fieldValues,
            Integer schemaVersion,
            boolean reportFailure,
            List<ConflictDraft> conflicts) {
        List<SimpleCreateRequests.ProductFieldValueCreate> fields = fieldValues.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new SimpleCreateRequests.ProductFieldValueCreate(entry.getKey(), entry.getValue()))
                .toList();
        SimpleCreateRequests.ProductCreate candidate = new SimpleCreateRequests.ProductCreate(
                null, seriesId, null,
                StringUtils.hasText(sku) ? sku : "IMPORT-PREVIEW",
                StringUtils.hasText(name) ? name : "商品导入预检",
                grade, temper, standard, spec, "{}", barcode, originalNumber, unit,
                defaultPrice, status, fields, "PUBLISHED");
        try {
            ProductMasterDataDtos.ProductDuplicateCheck identity =
                    productMasterDataService.evaluateCandidateIdentity(candidate, schemaVersion);
            if (reportFailure && (!Boolean.TRUE.equals(identity.complete())
                    || !StringUtils.hasText(identity.identitySignature()))) {
                String detail = identity.warnings() == null || identity.warnings().isEmpty()
                        ? "当前系列用于区分商品的必填规格不完整"
                        : String.join("；", identity.warnings());
                conflicts.add(ConflictDraft.blocking(
                        "INCOMPLETE_PRODUCT_IDENTITY", "identitySignature", "",
                        identity.schemaVersion() == null ? "" : String.valueOf(identity.schemaVersion()),
                        detail));
            }
            return identity;
        } catch (BusinessException ex) {
            if (reportFailure) {
                conflicts.add(ConflictDraft.blocking(
                        "IDENTITY_SCHEMA_EVALUATION_FAILED", "identitySchemaVersion", "",
                        schemaVersion == null ? "" : String.valueOf(schemaVersion),
                        StringUtils.hasText(ex.getMessage()) ? ex.getMessage() : "商品重复规则无法检查"));
            }
            return null;
        }
    }

    private boolean pendingOnlyStructured(
            ProductMasterDataDtos.ProductDuplicateCheck identity,
            Long seriesId,
            Integer schemaVersion) {
        if (identity == null || schemaVersion == null || identity.warnings() == null
                || identity.warnings().isEmpty()) return false;
        List<String> requiredDimensions = jdbc.query("""
                select dimension_key from product_identity_rule
                where product_series_id=? and schema_version=?
                  and source_type='STRUCTURED' and required=1 and status='ENABLED'
                order by sort_no,id
                """, (rs, rowNum) -> rs.getString(1), seriesId, schemaVersion);
        if (requiredDimensions.isEmpty()) return false;
        return identity.warnings().stream().allMatch(warning ->
                warning.startsWith("缺少必填规格")
                        && requiredDimensions.stream().anyMatch(warning::contains));
    }

    private void markBlocked(Long jobId, Long rowId, String reason) {
        jdbc.update("""
                update product_import_row
                set commit_status='BLOCKED',commit_error=?,committed_at=current_timestamp
                where id=? and job_id=?
                """, limited(reason, 1000), rowId, jobId);
        event(jobId, rowId, "ROW_BLOCKED", "FAILED", Map.of("reason", reason));
    }

    private void markSkipped(Long jobId, Long rowId) {
        jdbc.update("""
                update product_import_row
                set commit_status='SKIPPED',commit_error='',committed_at=current_timestamp
                where id=? and job_id=?
                """, rowId, jobId);
        event(jobId, rowId, "ROW_SKIPPED", "INFO", Map.of());
    }

    private void markFailed(
            Long jobId, Long rowId, String claimToken, String reason) {
        assertCommitOwner(jobId, claimToken);
        Map<String, Object> row = rowForUpdate(jobId, rowId);
        if (Set.of("SUCCEEDED", "SKIPPED").contains(
                String.valueOf(row.get("commit_status")))) {
            return;
        }
        jdbc.update("""
                update product_import_row
                set commit_status='FAILED',commit_error=?,committed_at=current_timestamp
                where id=? and job_id=?
                """, limited(reason, 1000), rowId, jobId);
        event(jobId, rowId, "ROW_COMMIT_FAILED", "FAILED", Map.of("reason", reason));
    }

    private void finalizeJob(Long jobId, String claimToken) {
        assertCommitOwner(jobId, claimToken);
        Map<String, Object> counts = jdbc.queryForMap("""
                select
                  sum(case when commit_status='SUCCEEDED' then 1 else 0 end) committed_rows,
                  sum(case when commit_status in ('FAILED','BLOCKED') then 1 else 0 end) failed_rows
                from product_import_row where job_id=?
                """, jobId);
        int committed = number(counts.get("committed_rows"));
        int failed = number(counts.get("failed_rows"));
        String status = failed > 0 ? "COMPLETED_WITH_ERRORS" : "COMPLETED";
        jdbc.update("""
                update product_import_job
                set status=?,committed_rows=?,failed_rows=?,lock_version=lock_version+1,
                    completed_at=current_timestamp,claim_token='',heartbeat_at=null
                where id=? and imported_by=? and status='COMMITTING' and claim_token=?
                """, status, committed, failed, jobId, currentActorId(), claimToken);
        event(jobId, 0L, "COMMIT_COMPLETED", failed > 0 ? "FAILED" : "SUCCESS",
                Map.of("status", status, "committedRows", committed, "failedRows", failed));
    }

    private void assertCommitOwner(Long jobId, String claimToken) {
        Map<String, Object> job = jobForUpdate(jobId);
        if (!"COMMITTING".equals(String.valueOf(job.get("status")))
                || !Objects.equals(claimToken, String.valueOf(job.get("claim_token")))) {
            throw new BusinessException("导入作业提交租约已失效，请刷新后重试");
        }
        int updated = jdbc.update("""
                update product_import_job
                set heartbeat_at=current_timestamp
                where id=? and imported_by=? and status='COMMITTING' and claim_token=?
                """, jobId, currentActorId(), claimToken);
        if (updated != 1) throw new BusinessException("导入作业提交租约已失效，请刷新后重试");
    }

    private ProductImportDtos.CommitResult commitResult(Long jobId) {
        ProductImportDtos.Preview preview = detailInternal(jobId, false);
        Map<String, Object> counts = jdbc.queryForMap("""
                select
                  sum(case when commit_status='SUCCEEDED'
                            and proposed_action in ('CREATE','CREATE_PENDING') then 1 else 0 end) created_rows,
                  sum(case when commit_status='SUCCEEDED' and proposed_action='SAFE_UPDATE' then 1 else 0 end) updated_rows,
                  sum(case when commit_status='SKIPPED' then 1 else 0 end) skipped_rows,
                  sum(case when commit_status in ('FAILED','BLOCKED') then 1 else 0 end) failed_rows
                from product_import_row where job_id=?
                """, jobId);
        List<Long> productIds = jdbc.query("""
                select imported_product_id from product_import_row
                where job_id=? and commit_status='SUCCEEDED' and imported_product_id>0
                order by source_row_no
                """, (rs, rowNum) -> rs.getLong(1), jobId);
        return new ProductImportDtos.CommitResult(
                jobId, preview.job().status(), number(counts.get("created_rows")),
                number(counts.get("updated_rows")), number(counts.get("skipped_rows")),
                number(counts.get("failed_rows")), preview.job().lockVersion(), productIds);
    }

    private NormalizationResult normalize(ProductImportFileParser.RawRow sourceRow,
                                          Map<String, String> mapping,
                                          SeriesCatalog seriesCatalog,
                                          UnitCatalog unitCatalog,
                                          ProductReferenceResolver referenceResolver) {
        List<ConflictDraft> conflicts = new ArrayList<>();
        Map<String, ValueSource> targets = new LinkedHashMap<>();
        Map<String, LineageDraft> lineage = new LinkedHashMap<>();
        Map<String, String> headerLookup = sourceRow.values().keySet().stream().collect(Collectors.toMap(
                ProductImportService::normalizedLookup, Function.identity(), (left, right) -> left,
                LinkedHashMap::new));
        Set<String> usedHeaders = new LinkedHashSet<>();

        for (Map.Entry<String, String> entry : mapping.entrySet()) {
            String sourceHeader = headerLookup.get(normalizedLookup(entry.getValue()));
            if (sourceHeader == null) throw new BusinessException("映射的源列不存在：" + entry.getValue());
            putTarget(targets, entry.getKey(), sourceHeader, sourceRow.values().get(sourceHeader),
                    "EXPLICIT_MAPPING", conflicts);
            usedHeaders.add(sourceHeader);
            lineage.put(sourceHeader, new LineageDraft(entry.getKey(),
                    text(sourceRow.values().get(sourceHeader)), "EXPLICIT_MAPPING"));
        }
        for (Map.Entry<String, String> entry : sourceRow.values().entrySet()) {
            if (usedHeaders.contains(entry.getKey())) continue;
            String fixedTarget = fixedTargetForHeader(entry.getKey());
            if (fixedTarget != null) {
                putTarget(targets, fixedTarget, entry.getKey(), entry.getValue(),
                        "HEADER_ALIAS", conflicts);
                usedHeaders.add(entry.getKey());
                lineage.put(entry.getKey(), new LineageDraft(
                        fixedTarget, text(entry.getValue()), "HEADER_ALIAS"));
            }
        }

        String templateExampleSku = value(targets, "sku").trim();
        boolean templateExampleRow = templateExampleSku.startsWith(
                ProductImportFileParser.EXAMPLE_SKU_PREFIX)
                || value(targets, "name").contains(ProductImportFileParser.EXAMPLE_ROW_MARKER)
                || value(targets, "spec").contains(ProductImportFileParser.EXAMPLE_ROW_MARKER);
        if (templateExampleRow) {
            conflicts.add(ConflictDraft.blocking(
                    "TEMPLATE_EXAMPLE_ROW", "sku", templateExampleSku, "",
                    "这是模板示例行，不能导入；请删除示例行后再上传"));
            return new NormalizationResult(null, conflicts, lineage, "CONFLICT", "");
        }

        ValueSource seriesValue = targets.get("series");
        if (seriesValue == null || !StringUtils.hasText(seriesValue.value())) {
            conflicts.add(ConflictDraft.blocking("REQUIRED_VALUE_MISSING", "series",
                    "", "", "产品系列不能为空"));
            return new NormalizationResult(null, conflicts, lineage, "CONFLICT", "");
        }
        Resolution<SeriesConfig> resolvedSeries = seriesCatalog.resolve(seriesValue.value());
        if (resolvedSeries.ambiguous()) {
            conflicts.add(ConflictDraft.blocking("AMBIGUOUS_SERIES", "series",
                    seriesValue.value(), "", "产品系列名称或别名同时匹配多个系列"));
            return new NormalizationResult(null, conflicts, lineage, "CONFLICT", "");
        }
        SeriesConfig series = resolvedSeries.value();
        if (series == null) {
            conflicts.add(ConflictDraft.blocking("UNKNOWN_SERIES", "series",
                    seriesValue.value(), "", "产品系列未配置；导入不会静默创建系列"));
            return new NormalizationResult(null, conflicts, lineage, "CONFLICT", "");
        }
        if (!"ENABLED".equals(series.status())) {
            conflicts.add(ConflictDraft.blocking("DISABLED_SERIES", "series",
                    seriesValue.value(), series.name(), "产品系列已停用，不能导入新商品"));
        }
        lineage.computeIfPresent(seriesValue.header(), (key, value) ->
                new LineageDraft("series", String.valueOf(series.id()), "SERIES_ALIAS_TO_ID"));

        for (Map.Entry<String, String> entry : sourceRow.values().entrySet()) {
            if (usedHeaders.contains(entry.getKey())) continue;
            FieldConfig field = series.resolveField(entry.getKey());
            if (field != null) {
                String target = "field:" + field.id();
                putTarget(targets, target, entry.getKey(), entry.getValue(),
                        "SERIES_FIELD_ALIAS", conflicts);
                usedHeaders.add(entry.getKey());
                lineage.put(entry.getKey(), new LineageDraft(
                        target, text(entry.getValue()), "SERIES_FIELD_ALIAS"));
            }
        }
        for (String target : new ArrayList<>(targets.keySet())) {
            if (!target.startsWith("field:")) continue;
            long fieldId = parseFieldId(target);
            if (series.fieldsById().get(fieldId) == null) {
                ValueSource source = targets.get(target);
                conflicts.add(ConflictDraft.blocking("FIELD_NOT_IN_SERIES", target,
                        source == null ? "" : source.value(), "",
                        "映射的规格字段不属于当前产品系列或已停用"));
            }
        }
        for (FieldConfig field : series.fields()) {
            String referenceKey = referenceKey(field);
            ValueSource referenceSource = referenceKey == null ? null : targets.get(referenceKey);
            if (referenceSource != null && !targets.containsKey("field:" + field.id())) {
                targets.put("field:" + field.id(), referenceSource);
            }
        }
        for (Map.Entry<String, String> entry : sourceRow.values().entrySet()) {
            if (!usedHeaders.contains(entry.getKey()) && StringUtils.hasText(entry.getValue())) {
                conflicts.add(ConflictDraft.blocking("UNMAPPED_SOURCE_COLUMN", entry.getKey(),
                        entry.getValue(), "", "源列未映射到固定字段或当前系列规格字段"));
                lineage.put(entry.getKey(), new LineageDraft("", "", "UNMAPPED"));
            }
        }

        Map<Long, String> fieldValues = new LinkedHashMap<>();
        Set<String> provided = new LinkedHashSet<>();
        for (FieldConfig field : series.fields()) {
            ValueSource source = targets.get("field:" + field.id());
            String raw = source == null ? "" : source.value();
            if (StringUtils.hasText(raw)) {
                String normalized = normalizeFieldValue(field, raw, conflicts);
                if (normalized != null && StringUtils.hasText(field.referenceType())) {
                    normalized = normalizeReference(
                            "field:" + field.id(), field.referenceType(), normalized,
                            referenceResolver, conflicts);
                }
                if (normalized != null) {
                    String normalizedValue = normalized;
                    fieldValues.put(field.id(), normalizedValue);
                    provided.add("field:" + field.id());
                    String normalizationRule = StringUtils.hasText(field.referenceType())
                            ? field.referenceType() + "_REFERENCE_ALIAS"
                            : field.fieldType() + "_NORMALIZATION";
                    lineage.computeIfPresent(source.header(), (key, value) ->
                            new LineageDraft("field:" + field.id(), normalizedValue,
                                    normalizationRule));
                }
            } else if (field.required()) {
                conflicts.add(ConflictDraft.blocking("REQUIRED_VALUE_MISSING",
                        "field:" + field.id(), "", "", "请填写必填规格字段“" + field.displayName() + "”"));
            }
        }

        String unit = normalizeUnit(value(targets, "unit"), unitCatalog, conflicts);
        provided.add("unit");
        if (StringUtils.hasText(unit) && StringUtils.hasText(series.expectedBaseUnit())
                && !Objects.equals(unit, series.expectedBaseUnit())) {
            conflicts.add(ConflictDraft.blocking("PACKAGING_BASE_UNIT_MISMATCH", "unit",
                    unit, series.expectedBaseUnit(),
                    "库存单位必须与系列当前发布包装模板的主单位一致"));
        }
        normalizeLineage(targets.get("unit"), lineage, "unit", unit, "UNIT_ALIAS");

        String standard = normalizeReference("standard", "STANDARD",
                value(targets, "standard"), referenceResolver, conflicts);
        String temper = normalizeReference("temper", "TEMPER",
                value(targets, "temper"), referenceResolver, conflicts);
        String grade = normalizeReference("grade", "GRADE",
                value(targets, "grade"), referenceResolver, conflicts);
        normalizeLineage(targets.get("standard"), lineage, "standard", standard, "STANDARD_ALIAS");
        normalizeLineage(targets.get("temper"), lineage, "temper", temper, "TEMPER_ALIAS");
        normalizeLineage(targets.get("grade"), lineage, "grade", grade,
                "GRADE_ALIAS");
        applyReferenceField(series, fieldValues, "grade", grade, conflicts);
        applyReferenceField(series, fieldValues, "temper", temper, conflicts);
        applyReferenceField(series, fieldValues, "standard", standard, conflicts);

        String skuInput = limitedValue("sku", value(targets, "sku"), 64, conflicts);
        String nameInput = limitedValue("name", value(targets, "name"), 128, conflicts);
        String spec = limitedValue("spec", value(targets, "spec"), 1000, conflicts);
        String barcode = limitedValue("barcode", value(targets, "barcode"), 64, conflicts);
        String originalNumber = limitedValue("originalNumber", value(targets, "originalNumber"),
                128, conflicts);
        String status = normalizeStatus(value(targets, "status"), conflicts);
        BigDecimal defaultPrice = normalizePrice(value(targets, "defaultPrice"), conflicts);
        for (String key : List.of("sku", "name", "grade", "temper", "standard", "spec", "barcode",
                "originalNumber", "defaultPrice", "status", "sourceUrl",
                "sourceDocumentVersion", "sourcePage", "memo")) {
            if (StringUtils.hasText(value(targets, key))) provided.add(key);
        }

        String generatedName = StringUtils.hasText(nameInput) ? nameInput
                : generatedName(series, fieldValues);
        if (!StringUtils.hasText(generatedName)) {
            conflicts.add(ConflictDraft.blocking("PRODUCT_NAME_UNAVAILABLE", "name",
                    "", "", "商品名称为空且无法由系列规格生成"));
        }
        ProductMasterDataDtos.ProductDuplicateCheck identity = evaluateIdentity(
                series.id(), skuInput, generatedName, grade, temper, standard, spec,
                barcode, originalNumber, unit, defaultPrice, status, fieldValues,
                null, false, conflicts);
        String productSignature = identity == null ? "" : text(identity.identitySignature());
        int identitySchemaVersion = identity == null || identity.schemaVersion() == null
                ? 0 : identity.schemaVersion();
        boolean createPending = identity != null && !Boolean.TRUE.equals(identity.complete())
                && pendingOnlyStructured(identity, series.id(), identity.schemaVersion());
        if (createPending && !StringUtils.hasText(skuInput)) {
            conflicts.add(ConflictDraft.blocking(
                    "PENDING_IDENTITY_REQUIRES_SKU", "sku", "", "",
                    "结构身份尚待维护的商品必须先填写唯一SKU，导入后再补结构并专门发布"));
        }
        String generatedSku = StringUtils.hasText(skuInput) ? skuInput
                : generatedSku(series.id(), productSignature);
        NormalizedProduct product = new NormalizedProduct(
                series.id(), series.name(), generatedSku, generatedName, unit,
                grade, temper, standard, spec, barcode, originalNumber,
                defaultPrice, status, fieldValues, provided,
                text(value(targets, "sourceUrl")), text(value(targets, "sourceDocumentVersion")),
                text(value(targets, "sourcePage")), text(value(targets, "memo")),
                productSignature, identitySchemaVersion, 0L, "", "");
        return new NormalizationResult(product, conflicts, lineage,
                conflicts.stream().anyMatch(ConflictDraft::blocking) ? "CONFLICT"
                        : createPending ? "CREATE_PENDING" : "CREATE",
                createPending ? "结构化身份待复核；导入后不可用于业务，须补齐结构并专门发布" : "");
    }

    private NormalizationResult evaluateFileIdentity(int rowNo,
                                                     NormalizationResult result,
                                                     Map<String, FileIdentity> signatures,
                                                     Map<String, FileIdentity> skus) {
        if (result.product() == null || result.hasBlockingConflict() || "SKIP".equals(result.action())) {
            return result;
        }
        NormalizedProduct product = result.product();
        FileIdentity signature = null;
        if (!"CREATE_PENDING".equals(result.action())) {
            String signatureKey = product.seriesId() + ":" + product.productSignature();
            signature = signatures.putIfAbsent(signatureKey, new FileIdentity(rowNo, product));
        }
        FileIdentity sku = skus.putIfAbsent(normalizedLookup(product.sku()), new FileIdentity(rowNo, product));
        if (signature != null) {
            if (equivalentInput(signature.product(), product)) {
                return result.asSkip(new ConflictDraft(
                        "FILE_SPEC_DUPLICATE", "productSignature", product.productSignature(), "",
                        "WARNING", "文件内与第" + signature.rowNo() + "行规格身份相同，本行跳过"));
            }
            return result.withConflict(ConflictDraft.blocking(
                    "FILE_SPEC_SIGNATURE_COLLISION", "productSignature",
                    product.productSignature(), signature.product().sku(),
                    "文件内第" + signature.rowNo() + "行已使用相同规格身份但业务字段不同"));
        }
        if (sku != null) {
            return result.withConflict(ConflictDraft.blocking(
                    "FILE_SKU_COLLISION", "sku", product.sku(), sku.product().productSignature(),
                    "文件内第" + sku.rowNo() + "行已使用相同SKU但规格身份不同"));
        }
        return result;
    }

    private NormalizationResult evaluateExisting(NormalizationResult result,
                                                 ExistingCatalog existingCatalog) {
        NormalizedProduct source = result.product();
        ExistingProduct bySku = existingCatalog.bySku().get(normalizedLookup(source.sku()));
        boolean skuProvided = source.provided().contains("sku");
        if (bySku != null && !Objects.equals(bySku.seriesId(), source.seriesId())) {
            return result.withConflict(ConflictDraft.blocking(
                    "SKU_EXISTS_WITH_DIFFERENT_IDENTITY", "sku", source.sku(),
                    bySku.seriesName() + " / " + bySku.spec(),
                    "SKU已存在但属于其他产品系列"));
        }
        if ("CREATE_PENDING".equals(result.action())) {
            if (bySku != null) {
                return result.withConflict(ConflictDraft.blocking(
                        "PENDING_SKU_ALREADY_EXISTS", "sku", source.sku(), bySku.sku(),
                        "待复核商品SKU已存在，不能再创建"));
            }
            return result;
        }
        if (bySku != null) {
            if (bySku.identitySchemaVersion() <= 0
                    || !StringUtils.hasText(bySku.productSignature())) {
                return result.withConflict(ConflictDraft.blocking(
                        "TARGET_IDENTITY_NOT_PUBLISHED", "sku", source.sku(), bySku.sku(),
                        "目标商品尚无已发布身份签名，不能通过批量导入安全更新"));
            }
            List<ConflictDraft> identityConflicts = new ArrayList<>(result.conflicts());
            ProductMasterDataDtos.ProductDuplicateCheck frozenIdentity = evaluateIdentity(
                    source.seriesId(), source.sku(), source.name(), source.grade(), source.temper(),
                    source.standard(), source.spec(), source.barcode(), source.originalNumber(),
                    source.unit(), source.defaultPrice(), source.status(), source.fieldValues(),
                    bySku.identitySchemaVersion(), true, identityConflicts);
            if (frozenIdentity == null || !Boolean.TRUE.equals(frozenIdentity.complete())
                    || !StringUtils.hasText(frozenIdentity.identitySignature())) {
                return new NormalizationResult(source, identityConflicts, result.lineage(),
                        "CONFLICT", "");
            }
            source = source.withIdentity(
                    frozenIdentity.identitySignature(), frozenIdentity.schemaVersion());
            result = new NormalizationResult(source, result.conflicts(), result.lineage(),
                    result.action(), result.decisionReason());
        } else if (source.identitySchemaVersion() <= 0
                || !StringUtils.hasText(source.productSignature())) {
            return result.withConflict(ConflictDraft.blocking(
                    "INCOMPLETE_PRODUCT_IDENTITY", "identitySignature", "",
                    source.identitySchemaVersion() <= 0
                            ? "" : String.valueOf(source.identitySchemaVersion()),
                    "当前系列用于区分商品的必填规格不完整，不能创建经营商品"));
        }

        String signatureKey = source.seriesId() + ":" + source.productSignature();
        if (existingCatalog.ambiguousSignatures().contains(signatureKey)) {
            return result.withConflict(ConflictDraft.blocking(
                    "EXISTING_SIGNATURE_NOT_UNIQUE", "productSignature",
                    source.productSignature(), "",
                    "已有多个经营商品的区分规格相同，请先整理商品资料"));
        }
        ExistingProduct bySignature = existingCatalog.bySignature().get(signatureKey);
        if (bySku != null && bySignature != null && !Objects.equals(bySku.id(), bySignature.id())) {
            return result.withConflict(ConflictDraft.blocking(
                    "SKU_AND_SIGNATURE_MATCH_DIFFERENT_PRODUCTS", "sku",
                    source.sku(), bySignature.sku(),
                    "SKU与规格签名分别命中不同商品，必须先治理商品主数据"));
        }
        if (bySku != null
                && !Objects.equals(fingerprintSignature(bySku), source.productSignature())) {
            return result.withConflict(ConflictDraft.blocking(
                    "SKU_EXISTS_WITH_DIFFERENT_IDENTITY", "sku", source.sku(),
                    bySku.seriesName() + " / " + bySku.spec(),
                    "SKU已存在，但录入规格与该商品现有规格不同"));
        }
        ExistingProduct target = bySku != null ? bySku : bySignature;
        if (target == null) return result.withAction("CREATE", "");
        if (bySignature != null && skuProvided && !Objects.equals(source.sku(), bySignature.sku())) {
            return result.withConflict(ConflictDraft.blocking(
                    "SPEC_SIGNATURE_EXISTS_WITH_DIFFERENT_SKU", "sku",
                    source.sku(), bySignature.sku(),
                    "相同系列和规格身份已存在，不能再创建另一SKU"));
        }
        if (!Objects.equals(source.unit(), target.unit())) {
            return result.withConflict(ConflictDraft.blocking(
                    "EXISTING_BASE_UNIT_MISMATCH", "unit", source.unit(), target.unit(),
                    "现有商品库存主单位不同，不能通过批量导入修改"));
        }
        ConflictDraft unsafeOverwrite = firstUnsafeOverwrite(source, target);
        if (unsafeOverwrite != null) return result.withConflict(unsafeOverwrite);
        NormalizedProduct merged = mergeExisting(source, target);
        String fingerprint = fingerprint(target);
        merged = merged.withTarget(target.id(), target.sku(), fingerprint);
        if (equivalentExisting(merged, target)) {
            return new NormalizationResult(merged, result.conflicts(), result.lineage(),
                    "SKIP", "正式商品已存在且本行没有可应用的变化");
        }
        return new NormalizationResult(merged, result.conflicts(), result.lineage(),
                "SAFE_UPDATE", "相同SKU和规格身份已存在，仅更新非身份主数据");
    }

    private SeriesCatalog loadSeriesCatalog() {
        Map<Long, SeriesConfigBuilder> builders = new LinkedHashMap<>();
        jdbc.query("""
                select s.id series_id,s.name series_name,s.alias_names,s.status,
                       coalesce((
                         select ptr.base_unit
                         from product_series_packaging_binding psb
                         join packaging_template pt on pt.id=psb.template_id
                         join packaging_template_revision ptr on ptr.id=pt.current_revision_id
                         where psb.product_series_id=s.id and psb.status='ENABLED'
                           and pt.status='ENABLED' and ptr.revision_status='PUBLISHED'
                         limit 1
                       ),'') expected_base_unit,
                       sf.id series_field_id,sf.field_name,
                       coalesce(nullif(sf.display_name,''),sf.field_name) display_name,
                       cf.field_type,coalesce(nullif(sf.option_json,''),cf.option_json) option_json,
                       coalesce((
                         select ir.reference_type
                         from product_identity_rule ir
                         where ir.product_series_id=s.id
                           and ir.schema_version=s.identity_schema_version
                           and ir.series_field_id=sf.id
                           and ir.source_type='SERIES_FIELD'
                           and ir.status='ENABLED'
                         order by ir.sort_no,ir.id limit 1
                       ),'') identity_reference_type,
                       sf.unit_name,sf.required,sf.sku_dimension,sf.include_in_spec,
                       sf.min_value,sf.max_value,sf.decimal_scale
                from product_series s
                left join product_series_field sf on sf.product_series_id=s.id and sf.status='ENABLED'
                left join product_custom_field cf on cf.id=sf.field_id
                order by s.id,sf.sort_no,sf.id
                """, rs -> {
            long seriesId = rs.getLong("series_id");
            String seriesName = rs.getString("series_name");
            List<String> aliases = splitAliases(rs.getString("alias_names"));
            String seriesStatus = rs.getString("status");
            String expectedBaseUnit = text(rs.getString("expected_base_unit"));
            SeriesConfigBuilder builder = builders.computeIfAbsent(seriesId, ignored ->
                    new SeriesConfigBuilder(seriesId, seriesName, aliases, seriesStatus,
                            expectedBaseUnit, new ArrayList<>()));
            long fieldId = rs.getLong("series_field_id");
            if (fieldId > 0) {
                builder.fields().add(new FieldConfig(
                        fieldId, rs.getString("field_name"), rs.getString("display_name"),
                        rs.getString("field_type"), text(rs.getString("identity_reference_type")),
                        text(rs.getString("unit_name")),
                        rs.getInt("required") == 1, rs.getInt("sku_dimension") == 1,
                        rs.getInt("include_in_spec") == 1, rs.getBigDecimal("min_value"),
                        rs.getBigDecimal("max_value"), rs.getInt("decimal_scale"),
                        parseOptions(rs.getString("option_json"))));
            }
        });
        Map<Long, SeriesConfig> byId = new LinkedHashMap<>();
        Map<String, List<SeriesConfig>> byAlias = new HashMap<>();
        for (SeriesConfigBuilder builder : builders.values()) {
            List<FieldConfig> fields = List.copyOf(builder.fields());
            Map<Long, FieldConfig> fieldsById = fields.stream().collect(Collectors.toMap(
                    FieldConfig::id, Function.identity(), (left, right) -> left, LinkedHashMap::new));
            Map<String, FieldConfig> fieldsByAlias = new HashMap<>();
            for (FieldConfig field : fields) {
                fieldsByAlias.put(normalizedFieldName(field.name()), field);
                fieldsByAlias.put(normalizedFieldName(field.displayName()), field);
                fieldsByAlias.put("field:" + field.id(), field);
            }
            SeriesConfig config = new SeriesConfig(
                    builder.id(), builder.name(), builder.aliases(), builder.status(),
                    builder.expectedBaseUnit(), fields, fieldsById, fieldsByAlias);
            byId.put(config.id(), config);
            addSeriesAlias(byAlias, String.valueOf(config.id()), config);
            addSeriesAlias(byAlias, config.name(), config);
            for (String alias : config.aliases()) addSeriesAlias(byAlias, alias, config);
        }
        return new SeriesCatalog(byId, byAlias);
    }

    private UnitCatalog loadUnitCatalog() {
        Map<String, Set<String>> values = new HashMap<>();
        if (tableExists("unit_lookup_token")) {
            jdbc.query("""
                    select t.normalized_token,u.name,u.abbreviation
                    from unit_lookup_token t
                    join unit u on u.id=t.unit_id
                    where u.status='ENABLED'
                    """, (org.springframework.jdbc.core.RowCallbackHandler) rs ->
                    values.computeIfAbsent(
                            rs.getString("normalized_token"), ignored -> new LinkedHashSet<>())
                            .add(canonicalUnit(
                                    rs.getString("name"), rs.getString("abbreviation"))));
            return new UnitCatalog(values);
        }
        jdbc.query("""
                select id,name,abbreviation from unit where status='ENABLED'
                """, rs -> {
            String canonical = canonicalUnit(rs.getString("name"), rs.getString("abbreviation"));
            addAlias(values, rs.getString("name"), canonical);
            addAlias(values, rs.getString("abbreviation"), canonical);
            if ("kg".equalsIgnoreCase(canonical)) {
                addAlias(values, "公斤", canonical);
                addAlias(values, "千克", canonical);
                addAlias(values, "KG", canonical);
            } else if ("t".equalsIgnoreCase(canonical)) {
                addAlias(values, "吨", canonical);
                addAlias(values, "公吨", canonical);
            }
        });
        if (tableExists("unit_alias")) {
            jdbc.query("""
                    select ua.alias_name,u.name,u.abbreviation
                    from unit_alias ua join unit u on u.id=ua.unit_id
                    where ua.status='ENABLED' and u.status='ENABLED'
                    """, rs -> {
                addAlias(values, rs.getString("alias_name"),
                        canonicalUnit(rs.getString("name"), rs.getString("abbreviation")));
            });
        }
        return new UnitCatalog(values);
    }

    private ExistingCatalog loadExistingCatalog() {
        Map<Long, ExistingProductBuilder> builders = new LinkedHashMap<>();
        jdbc.query("""
                select p.id,p.product_series_id,s.name series_name,p.sku,p.name,p.grade,p.temper,
                       p.standard,p.spec,p.barcode,p.original_number,p.unit,p.default_price,p.status,
                       p.identity_schema_version_snapshot,p.identity_signature,
                       s.identity_schema_version current_identity_schema_version,
                       sf.id series_field_id,
                       coalesce(nullif(pfv.normalized_code,''),
                                nullif(pfv.value_text,''),
                                cast(pfv.value_decimal as char),'') field_value
                from product p
                join product_series s on s.id=p.product_series_id
                left join product_field_value pfv on pfv.product_id=p.id
                left join product_series_field sf on sf.id=pfv.series_field_id and sf.status='ENABLED'
                order by p.id,pfv.series_field_id
                """, rs -> {
            long productId = rs.getLong("id");
            long seriesId = rs.getLong("product_series_id");
            String seriesName = rs.getString("series_name");
            String sku = rs.getString("sku");
            String name = rs.getString("name");
            String grade = text(rs.getString("grade"));
            String temper = text(rs.getString("temper"));
            String standard = text(rs.getString("standard"));
            String spec = text(rs.getString("spec"));
            String barcode = text(rs.getString("barcode"));
            String originalNumber = text(rs.getString("original_number"));
            String unit = rs.getString("unit");
            BigDecimal defaultPrice = rs.getBigDecimal("default_price");
            String productStatus = rs.getString("status");
            int identitySchemaVersion = rs.getInt("identity_schema_version_snapshot");
            String identitySignature = text(rs.getString("identity_signature"));
            int currentIdentitySchemaVersion = rs.getInt("current_identity_schema_version");
            ExistingProductBuilder builder = builders.computeIfAbsent(productId, ignored ->
                    new ExistingProductBuilder(
                            productId, seriesId, seriesName, sku, name, grade, temper, standard,
                            spec, barcode, originalNumber, unit, defaultPrice, productStatus,
                            identitySchemaVersion, currentIdentitySchemaVersion,
                            identitySignature, new LinkedHashMap<>()));
            long fieldId = rs.getLong("series_field_id");
            if (fieldId > 0) builder.fieldValues().put(fieldId, text(rs.getString("field_value")));
        });
        Map<String, ExistingProduct> bySku = new HashMap<>();
        Map<String, ExistingProduct> bySignature = new HashMap<>();
        Set<String> ambiguousSignatures = new HashSet<>();
        Set<Long> visibleProductIds =
                dataScopeService.filterMasterIds("PRODUCT", builders.keySet());
        for (ExistingProductBuilder builder : builders.values()) {
            if (!visibleProductIds.contains(builder.id())) continue;
            ExistingProduct product = builder.build();
            bySku.put(normalizedLookup(product.sku()), product);
            String currentSignature = "";
            if (product.identitySchemaVersion() == product.currentIdentitySchemaVersion()) {
                currentSignature = product.productSignature();
            } else {
                ProductMasterDataDtos.ProductDuplicateCheck currentIdentity =
                        productMasterDataService.evaluateStoredCurrentIdentity(product.id());
                if (Boolean.TRUE.equals(currentIdentity.complete())) {
                    currentSignature = text(currentIdentity.identitySignature());
                }
            }
            if (StringUtils.hasText(currentSignature)) {
                String key = product.seriesId() + ":" + currentSignature;
                if (bySignature.putIfAbsent(key, product) != null) ambiguousSignatures.add(key);
            }
        }
        return new ExistingCatalog(bySku, bySignature, ambiguousSignatures);
    }

    private ExistingProduct loadExistingProduct(Long productId) {
        dataScopeService.assertMasterAllowed("PRODUCT", productId);
        ExistingCatalog catalog = loadExistingCatalog();
        return catalog.bySku().values().stream()
                .filter(product -> Objects.equals(product.id(), productId))
                .findFirst().orElseThrow(() -> new BusinessException("目标商品不存在"));
    }

    private ProductImportDtos.Preview detailInternal(Long jobId, boolean idempotentReplay) {
        List<ProductImportDtos.JobSummary> jobs = jdbc.query(
                jobSelect() + " where id=? and imported_by=?",
                jobMapper(), jobId, currentActorId());
        if (jobs.isEmpty()) {
            throw new ForbiddenException("商品导入作业不存在或不属于当前操作人");
        }
        List<ProductImportDtos.RowView> rows = jdbc.query("""
                select id,job_id,source_row_no,raw_data_json,normalized_data_json,source_hash,
                       product_signature,validation_status,validation_message,proposed_action,
                       decided_action,decision_reason,target_product_id,target_product_sku,
                       commit_status,commit_error,imported_product_id,row_version
                from product_import_row where job_id=? order by source_row_no
                """, (rs, rowNum) -> new ProductImportDtos.RowView(
                rs.getLong("id"), rs.getLong("job_id"), rs.getInt("source_row_no"),
                readStringMap(rs.getString("raw_data_json")),
                readObjectMap(rs.getString("normalized_data_json")),
                rs.getString("source_hash"), rs.getString("product_signature"),
                rs.getString("validation_status"), rs.getString("validation_message"),
                rs.getString("proposed_action"), rs.getString("decided_action"),
                rs.getString("decision_reason"), rs.getLong("target_product_id"),
                rs.getString("target_product_sku"), rs.getString("commit_status"),
                rs.getString("commit_error"), rs.getLong("imported_product_id"),
                rs.getInt("row_version")), jobId);
        List<ProductImportDtos.ConflictView> conflicts = jdbc.query("""
                select id,job_id,row_id,conflict_type,field_key,source_value,target_value,
                       severity,resolution_status,resolution_note,message
                from product_import_conflict where job_id=? order by row_id,id
                """, (rs, rowNum) -> new ProductImportDtos.ConflictView(
                rs.getLong("id"), rs.getLong("job_id"), rs.getLong("row_id"),
                rs.getString("conflict_type"), rs.getString("field_key"),
                rs.getString("source_value"), rs.getString("target_value"),
                rs.getString("severity"), rs.getString("resolution_status"),
                rs.getString("resolution_note"), rs.getString("message")), jobId);
        return new ProductImportDtos.Preview(jobs.get(0), rows, conflicts, idempotentReplay);
    }

    private String jobSelect() {
        return """
                select id,job_no,template_version,source_file_name,source_file_format,
                       source_file_sha256,source_file_size,mapping_json,status,
                       case when status='COMMITTING'
                                  and (heartbeat_at is null
                                       or heartbeat_at<date_sub(current_timestamp,interval 5 minute))
                            then 1 else 0 end recoverable,
                       case when status='COMMITTING' and heartbeat_at is not null
                            then date_add(heartbeat_at,interval 5 minute)
                            else null end lease_expires_at,
                       total_rows,
                       create_rows,safe_update_rows,skip_rows,conflict_rows,invalid_rows,
                       committed_rows,failed_rows,lock_version,imported_by_name,created_at,completed_at
                from product_import_job
                """;
    }

    private org.springframework.jdbc.core.RowMapper<ProductImportDtos.JobSummary> jobMapper() {
        return (rs, rowNum) -> new ProductImportDtos.JobSummary(
                rs.getLong("id"), rs.getString("job_no"), rs.getString("template_version"),
                rs.getString("source_file_name"), rs.getString("source_file_format"),
                rs.getString("source_file_sha256"), rs.getLong("source_file_size"),
                readStringMap(rs.getString("mapping_json")), rs.getString("status"),
                rs.getBoolean("recoverable"),
                rs.getTimestamp("lease_expires_at") == null
                        ? null : rs.getTimestamp("lease_expires_at").toLocalDateTime(),
                rs.getInt("total_rows"), rs.getInt("create_rows"), rs.getInt("safe_update_rows"),
                rs.getInt("skip_rows"), rs.getInt("conflict_rows"), rs.getInt("invalid_rows"),
                rs.getInt("committed_rows"), rs.getInt("failed_rows"), rs.getInt("lock_version"),
                rs.getString("imported_by_name"), rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getTimestamp("completed_at") == null
                        ? null : rs.getTimestamp("completed_at").toLocalDateTime());
    }

    private Map<String, String> parseMapping(String mappingJson, List<String> headers) {
        if (!StringUtils.hasText(mappingJson)) return Map.of();
        Map<String, String> raw;
        try {
            raw = objectMapper.readValue(mappingJson, new TypeReference<>() {
            });
        } catch (Exception ex) {
            throw new BusinessException("字段映射必须是“目标字段:源列名”的JSON对象");
        }
        Map<String, String> result = new LinkedHashMap<>();
        Set<String> sources = new HashSet<>();
        Set<String> normalizedHeaders = headers.stream().map(ProductImportService::normalizedLookup)
                .collect(Collectors.toSet());
        for (Map.Entry<String, String> entry : raw.entrySet()) {
            String target = canonicalTarget(entry.getKey());
            if (target == null) throw new BusinessException("不支持的目标字段：" + entry.getKey());
            String source = text(entry.getValue()).trim();
            if (!StringUtils.hasText(source)) throw new BusinessException("字段映射的源列名不能为空");
            if (!normalizedHeaders.contains(normalizedLookup(source))) {
                throw new BusinessException("映射的源列不存在：" + source);
            }
            if (result.putIfAbsent(target, source) != null) {
                throw new BusinessException("同一目标字段不能重复映射：" + target);
            }
            if (!sources.add(normalizedLookup(source))) {
                throw new BusinessException("同一源列不能映射到多个目标字段：" + source);
            }
        }
        return Map.copyOf(result);
    }

    private String canonicalTarget(String value) {
        if (!StringUtils.hasText(value)) return null;
        String trimmed = value.trim();
        if (trimmed.matches("(?i)field:\\d+")) {
            return "field:" + parseFieldId(trimmed);
        }
        if (FIXED_TARGETS.contains(trimmed)) return trimmed;
        return FIXED_HEADER_ALIASES.get(normalizedFieldName(trimmed));
    }

    private String normalizeFieldValue(FieldConfig field, String raw,
                                       List<ConflictDraft> conflicts) {
        String value = raw.trim();
        if ("NUMBER".equals(field.fieldType())) {
            try {
                BigDecimal number = new BigDecimal(value.replace(",", ""));
                if (field.minValue() != null && number.compareTo(field.minValue()) < 0) {
                    conflicts.add(ConflictDraft.blocking("NUMBER_BELOW_MINIMUM",
                            "field:" + field.id(), raw, field.minValue().toPlainString(),
                            "“" + field.displayName() + "”小于允许最小值"));
                    return null;
                }
                if (field.maxValue() != null && number.compareTo(field.maxValue()) > 0) {
                    conflicts.add(ConflictDraft.blocking("NUMBER_ABOVE_MAXIMUM",
                            "field:" + field.id(), raw, field.maxValue().toPlainString(),
                            "“" + field.displayName() + "”大于允许最大值"));
                    return null;
                }
                int scale = Math.max(0, field.decimalScale());
                if (number.stripTrailingZeros().scale() > scale) {
                    conflicts.add(ConflictDraft.blocking("NUMBER_SCALE_EXCEEDED",
                            "field:" + field.id(), raw, String.valueOf(scale),
                            "“" + field.displayName() + "”最多允许" + scale + "位小数"));
                    return null;
                }
                return number.setScale(scale, RoundingMode.UNNECESSARY).stripTrailingZeros().toPlainString();
            } catch (NumberFormatException | ArithmeticException ex) {
                conflicts.add(ConflictDraft.blocking("INVALID_NUMBER", "field:" + field.id(),
                        raw, "", "“" + field.displayName() + "”不是有效数字"));
                return null;
            }
        }
        if ("SELECT".equals(field.fieldType())) {
            List<String> matches = field.options().stream()
                    .filter(option -> normalizedLookup(option).equals(normalizedLookup(value))).toList();
            if (matches.size() != 1) {
                conflicts.add(ConflictDraft.blocking("UNKNOWN_SELECT_OPTION",
                        "field:" + field.id(), raw, String.join(",", field.options()),
                        "“" + field.displayName() + "”不是已配置选项；导入不会静默新增枚举"));
                return null;
            }
            return matches.get(0);
        }
        if (value.length() > 1000) {
            conflicts.add(ConflictDraft.blocking("TEXT_TOO_LONG", "field:" + field.id(),
                    value, "1000", "“" + field.displayName() + "”超过1000字符"));
            return null;
        }
        return value;
    }

    private String normalizeUnit(String raw, UnitCatalog catalog,
                                 List<ConflictDraft> conflicts) {
        if (!StringUtils.hasText(raw)) {
            conflicts.add(ConflictDraft.blocking("REQUIRED_VALUE_MISSING",
                    "unit", "", "", "库存单位不能为空"));
            return "";
        }
        Resolution<String> resolution = catalog.resolve(raw);
        if (resolution.ambiguous()) {
            conflicts.add(ConflictDraft.blocking("AMBIGUOUS_UNIT_ALIAS",
                    "unit", raw, "", "单位别名同时命中多个已启用单位"));
            return "";
        }
        if (resolution.value() == null) {
            conflicts.add(ConflictDraft.blocking("UNKNOWN_UNIT",
                    "unit", raw, "", "单位未配置；导入不会静默创建单位"));
            return "";
        }
        return resolution.value();
    }

    private String normalizeReference(String field, String referenceType, String raw,
                                      ProductReferenceResolver resolver,
                                      List<ConflictDraft> conflicts) {
        ProductImportReferenceNormalizer.Result result =
                ProductImportReferenceNormalizer.normalize(referenceType, raw, resolver::resolve);
        if (!result.resolved()) {
            conflicts.add(ConflictDraft.blocking(
                    result.conflictType(), field, raw, "", result.message()));
        }
        return result.normalizedValue();
    }

    private String normalizeStatus(String raw, List<ConflictDraft> conflicts) {
        if (!StringUtils.hasText(raw)) return "ENABLED";
        String key = normalizedLookup(raw);
        if (Set.of("enabled", "启用", "正常", "是", "1").contains(key)) return "ENABLED";
        if (Set.of("disabled", "停用", "禁用", "否", "0").contains(key)) return "DISABLED";
        conflicts.add(ConflictDraft.blocking("UNKNOWN_STATUS",
                "status", raw, "ENABLED,DISABLED", "启用状态不合法"));
        return "ENABLED";
    }

    private BigDecimal normalizePrice(String raw, List<ConflictDraft> conflicts) {
        if (!StringUtils.hasText(raw)) return BigDecimal.ZERO.setScale(4);
        try {
            BigDecimal value = new BigDecimal(raw.replace(",", ""));
            if (value.compareTo(BigDecimal.ZERO) < 0) throw new NumberFormatException();
            if (value.stripTrailingZeros().scale() > 4) {
                conflicts.add(ConflictDraft.blocking("PRICE_SCALE_EXCEEDED",
                        "defaultPrice", raw, "4", "默认单价最多允许4位小数"));
                return BigDecimal.ZERO.setScale(4);
            }
            return value.setScale(4, RoundingMode.UNNECESSARY);
        } catch (NumberFormatException | ArithmeticException ex) {
            conflicts.add(ConflictDraft.blocking("INVALID_PRICE",
                    "defaultPrice", raw, "", "默认单价必须是大于等于0且最多4位小数的数字"));
            return BigDecimal.ZERO.setScale(4);
        }
    }

    private String limitedValue(String key, String value, int max,
                                List<ConflictDraft> conflicts) {
        String result = text(value).trim();
        if (result.length() > max) {
            conflicts.add(ConflictDraft.blocking("TEXT_TOO_LONG", key,
                    result, String.valueOf(max), key + "超过" + max + "字符"));
            return result.substring(0, max);
        }
        return result;
    }

    private NormalizedProduct mergeExisting(NormalizedProduct source, ExistingProduct target) {
        Set<String> provided = source.provided();
        Map<Long, String> fields = new LinkedHashMap<>(target.fieldValues());
        source.fieldValues().forEach((fieldId, value) -> {
            if (!StringUtils.hasText(fields.get(fieldId))) fields.put(fieldId, value);
        });
        return new NormalizedProduct(
                source.seriesId(), source.seriesName(),
                target.sku(),
                fillBlank(target.name(), source.name(), provided.contains("name")),
                target.unit(),
                fillBlank(target.grade(), source.grade(), provided.contains("grade")),
                fillBlank(target.temper(), source.temper(), provided.contains("temper")),
                fillBlank(target.standard(), source.standard(), provided.contains("standard")),
                fillBlank(target.spec(), source.spec(), provided.contains("spec")),
                fillBlank(target.barcode(), source.barcode(), provided.contains("barcode")),
                fillBlank(target.originalNumber(), source.originalNumber(), provided.contains("originalNumber")),
                target.defaultPrice().compareTo(BigDecimal.ZERO) == 0 && provided.contains("defaultPrice")
                        ? source.defaultPrice() : target.defaultPrice(),
                target.status(),
                fields, source.provided(), source.sourceUrl(), source.sourceDocumentVersion(),
                source.sourcePage(), source.memo(), source.productSignature(),
                source.identitySchemaVersion(), target.id(), target.sku(), fingerprint(target));
    }

    private ConflictDraft firstUnsafeOverwrite(NormalizedProduct source, ExistingProduct target) {
        List<String[]> scalarValues = List.of(
                new String[]{"name", source.name(), target.name()},
                new String[]{"grade", source.grade(), target.grade()},
                new String[]{"temper", source.temper(), target.temper()},
                new String[]{"standard", source.standard(), target.standard()},
                new String[]{"spec", source.spec(), target.spec()},
                new String[]{"barcode", source.barcode(), target.barcode()},
                new String[]{"originalNumber", source.originalNumber(), target.originalNumber()},
                new String[]{"status", source.status(), target.status()}
        );
        for (String[] field : scalarValues) {
            if (source.provided().contains(field[0]) && StringUtils.hasText(field[2])
                    && !Objects.equals(field[1], field[2])) {
                return ConflictDraft.blocking(
                        "SAFE_UPDATE_NON_EMPTY_CONFLICT", field[0], field[1], field[2],
                        "安全更新不能覆盖现有非空字段“" + field[0] + "”");
            }
        }
        if (source.provided().contains("defaultPrice")
                && target.defaultPrice().compareTo(BigDecimal.ZERO) != 0
                && source.defaultPrice().compareTo(target.defaultPrice()) != 0) {
            return ConflictDraft.blocking(
                    "SAFE_UPDATE_NON_EMPTY_CONFLICT", "defaultPrice",
                    source.defaultPrice().toPlainString(), target.defaultPrice().toPlainString(),
                    "安全更新不能覆盖现有非零默认单价");
        }
        for (Map.Entry<Long, String> entry : source.fieldValues().entrySet()) {
            String current = target.fieldValues().get(entry.getKey());
            if (StringUtils.hasText(current) && !Objects.equals(entry.getValue(), current)) {
                return ConflictDraft.blocking(
                        "SAFE_UPDATE_NON_EMPTY_CONFLICT", "field:" + entry.getKey(),
                        entry.getValue(), current, "安全更新不能覆盖现有非空规格字段");
            }
        }
        return null;
    }

    private String fillBlank(String current, String proposed, boolean provided) {
        return !StringUtils.hasText(current) && provided ? proposed : current;
    }

    private boolean equivalentExisting(NormalizedProduct product, ExistingProduct target) {
        return Objects.equals(product.sku(), target.sku())
                && Objects.equals(product.name(), target.name())
                && Objects.equals(product.grade(), target.grade())
                && Objects.equals(product.temper(), target.temper())
                && Objects.equals(product.standard(), target.standard())
                && Objects.equals(product.spec(), target.spec())
                && Objects.equals(product.barcode(), target.barcode())
                && Objects.equals(product.originalNumber(), target.originalNumber())
                && Objects.equals(product.unit(), target.unit())
                && product.defaultPrice().compareTo(target.defaultPrice()) == 0
                && Objects.equals(product.status(), target.status())
                && Objects.equals(new TreeMap<>(product.fieldValues()),
                new TreeMap<>(target.fieldValues()));
    }

    private boolean equivalentInput(NormalizedProduct left, NormalizedProduct right) {
        return Objects.equals(left.seriesId(), right.seriesId())
                && Objects.equals(left.sku(), right.sku())
                && Objects.equals(left.name(), right.name())
                && Objects.equals(left.unit(), right.unit())
                && Objects.equals(left.grade(), right.grade())
                && Objects.equals(left.temper(), right.temper())
                && Objects.equals(left.standard(), right.standard())
                && Objects.equals(left.spec(), right.spec())
                && Objects.equals(left.barcode(), right.barcode())
                && Objects.equals(left.originalNumber(), right.originalNumber())
                && left.defaultPrice().compareTo(right.defaultPrice()) == 0
                && Objects.equals(left.status(), right.status())
                && Objects.equals(new TreeMap<>(left.fieldValues()), new TreeMap<>(right.fieldValues()));
    }

    private String fingerprint(ExistingProduct product) {
        Map<String, Object> values = new TreeMap<>();
        values.put("id", product.id());
        values.put("seriesId", product.seriesId());
        values.put("sku", product.sku());
        values.put("name", product.name());
        values.put("grade", product.grade());
        values.put("temper", product.temper());
        values.put("standard", product.standard());
        values.put("spec", product.spec());
        values.put("barcode", product.barcode());
        values.put("originalNumber", product.originalNumber());
        values.put("unit", product.unit());
        values.put("defaultPrice", product.defaultPrice().setScale(4).toPlainString());
        values.put("status", product.status());
        values.put("fieldValues", new TreeMap<>(product.fieldValues()));
        values.put("identitySchemaVersion", product.identitySchemaVersion());
        values.put("identitySignature", product.productSignature());
        return sha256(json(values));
    }

    private String configurationFingerprint(SeriesCatalog seriesCatalog,
                                            UnitCatalog unitCatalog,
                                            List<ProductMasterDataDtos.ReferenceValue> referenceValues,
                                            List<IdentityRuleSnapshot> identityRules,
                                            ExistingCatalog existingCatalog) {
        Map<String, Object> snapshot = new TreeMap<>();
        snapshot.put("series", new TreeMap<>(seriesCatalog.byId()));
        snapshot.put("units", new TreeMap<>(unitCatalog.aliases()));
        snapshot.put("references", referenceValues);
        snapshot.put("identityRules", identityRules);
        snapshot.put("products", new TreeMap<>(existingCatalog.bySku()));
        return sha256(json(snapshot));
    }

    private List<IdentityRuleSnapshot> loadIdentityRules() {
        return jdbc.query("""
                select s.id product_series_id,s.identity_schema_version,
                       coalesce(ir.dimension_key,'') dimension_key,
                       coalesce(ir.source_type,'') source_type,
                       coalesce(ir.series_field_id,0) series_field_id,
                       coalesce(ir.reference_type,'') reference_type,
                       coalesce(ir.required,0) required,
                       coalesce(ir.sort_no,0) sort_no,
                       coalesce(ir.status,'') status
                from product_series s
                left join product_identity_rule ir
                  on ir.product_series_id=s.id
                 and ir.schema_version=s.identity_schema_version
                order by s.id,ir.sort_no,ir.id
                """, (rs, rowNum) -> new IdentityRuleSnapshot(
                rs.getLong("product_series_id"), rs.getInt("identity_schema_version"),
                rs.getString("dimension_key"), rs.getString("source_type"),
                rs.getLong("series_field_id"), rs.getString("reference_type"),
                rs.getInt("required") == 1, rs.getInt("sort_no"), rs.getString("status")));
    }

    private String fingerprintSignature(ExistingProduct product) {
        return product.productSignature();
    }

    private String generatedSku(Long seriesId, String signature) {
        if (!StringUtils.hasText(signature)) return "";
        return "S" + seriesId + "-" + signature.substring(0, 16).toUpperCase(Locale.ROOT);
    }

    private String generatedName(SeriesConfig series, Map<Long, String> fieldValues) {
        List<String> parts = new ArrayList<>();
        for (FieldConfig field : series.fields()) {
            if (!field.includeInSpec()) continue;
            String value = fieldValues.get(field.id());
            if (StringUtils.hasText(value)) {
                parts.add(field.displayName() + value + text(field.unitName()));
            }
        }
        String value = series.name() + (parts.isEmpty() ? "" : " " + String.join("×", parts));
        return value.length() <= 128 ? value : value.substring(0, 128);
    }

    private void insertLineage(Long jobId, Long rowId, String fileSha,
                               ProductImportFileParser.RawRow sourceRow,
                               Map<String, LineageDraft> lineage) {
        for (Map.Entry<String, String> entry : sourceRow.values().entrySet()) {
            if (!StringUtils.hasText(entry.getValue())) continue;
            LineageDraft normalized = lineage.getOrDefault(entry.getKey(),
                    new LineageDraft("", "", "UNMAPPED"));
            jdbc.update("""
                    insert into product_import_source_lineage
                    (job_id,row_id,source_file_sha256,source_row_no,source_column,source_value,
                     normalized_field,normalized_value,normalization_rule)
                    values (?,?,?,?,?,?,?,?,?)
                    """, jobId, rowId, fileSha, sourceRow.sourceRowNo(),
                    limited(entry.getKey(), 255), limited(entry.getValue(), 2000),
                    limited(normalized.field(), 128), limited(normalized.value(), 2000),
                    limited(normalized.rule(), 128));
        }
    }

    private void normalizeLineage(ValueSource source, Map<String, LineageDraft> lineage,
                                  String field, String value, String rule) {
        if (source != null && StringUtils.hasText(source.value())) {
            lineage.put(source.header(), new LineageDraft(field, text(value), rule));
        }
    }

    private void putTarget(Map<String, ValueSource> targets, String target,
                           String header, String value, String rule,
                           List<ConflictDraft> conflicts) {
        ValueSource existing = targets.get(target);
        ValueSource candidate = new ValueSource(header, text(value).trim(), rule);
        if (existing != null && StringUtils.hasText(existing.value())
                && StringUtils.hasText(candidate.value())
                && !Objects.equals(existing.value(), candidate.value())) {
            conflicts.add(ConflictDraft.blocking("DUPLICATE_FIELD_MAPPING", target,
                    candidate.value(), existing.value(),
                    "多个源列映射到同一目标字段且值不一致"));
            return;
        }
        if (existing == null || !StringUtils.hasText(existing.value())) targets.put(target, candidate);
    }

    private void event(Long jobId, Long rowId, String eventType,
                       String status, Object detail) {
        SecurityContext.Principal actor = SecurityContext.get();
        jdbc.update("""
                insert into product_import_event
                (job_id,row_id,event_type,event_status,detail_json,actor_id,actor_name)
                values (?,?,?,?,?,?,?)
                """, jobId, rowId, eventType, status, json(detail),
                actor == null ? 0L : id(actor.userId()),
                actor == null ? "系统" : actorName(actor));
    }

    private Map<String, Object> rowForUpdate(Long jobId, Long rowId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select r.*
                from product_import_row r
                join product_import_job j on j.id=r.job_id
                where r.id=? and r.job_id=? and j.imported_by=?
                for update
                """, rowId, jobId, currentActorId());
        if (rows.isEmpty()) {
            throw new ForbiddenException("商品导入行不存在或不属于当前操作人");
        }
        return rows.get(0);
    }

    private Map<String, Object> jobForUpdate(Long jobId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select * from product_import_job where id=? and imported_by=? for update",
                jobId, currentActorId());
        if (rows.isEmpty()) {
            throw new ForbiddenException("商品导入作业不存在或不属于当前操作人");
        }
        return rows.get(0);
    }

    private String jobStatus(Long jobId) {
        List<String> rows = jdbc.query("""
                select status from product_import_job
                where id=? and imported_by=?
                """, (rs, rowNum) -> rs.getString(1), jobId, currentActorId());
        if (rows.isEmpty()) {
            throw new ForbiddenException("商品导入作业不存在或不属于当前操作人");
        }
        return rows.get(0);
    }

    private void assertOwnedJob(Long jobId) {
        Integer count = jdbc.queryForObject("""
                select count(*) from product_import_job
                where id=? and imported_by=?
                """, Integer.class, jobId, currentActorId());
        if (count == null || count != 1) {
            throw new ForbiddenException("商品导入作业不存在或不属于当前操作人");
        }
    }

    private long currentActorId() {
        return id(SecurityContext.require().userId());
    }

    private boolean tableExists(String table) {
        Integer count = jdbc.queryForObject("""
                select count(*) from information_schema.tables
                where table_schema=database() and table_name=?
                """, Integer.class, table);
        return count != null && count > 0;
    }

    private Long insert(String sql, Object... args) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
            return statement;
        }, key);
        Number value = key.getKey();
        if (value == null) throw new IllegalStateException("未取得商品导入数据编号");
        return value.longValue();
    }

    private String safeError(RuntimeException ex) {
        if (ex instanceof BusinessException && StringUtils.hasText(ex.getMessage())) {
            return limited(ex.getMessage(), 1000);
        }
        return "商品写入失败；系统已回滚本行且保留失败审计，请修正主数据后重新预检";
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("商品导入审计快照序列化失败", ex);
        }
    }

    private <T> T read(String value, Class<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (Exception ex) {
            throw new BusinessException("商品导入规范化快照损坏");
        }
    }

    private Map<String, String> readStringMap(String value) {
        try {
            if (!StringUtils.hasText(value)) return Map.of();
            return objectMapper.readValue(value, new TypeReference<>() {
            });
        } catch (Exception ex) {
            throw new IllegalStateException("商品导入字段映射快照损坏", ex);
        }
    }

    private Map<String, Object> readObjectMap(String value) {
        try {
            if (!StringUtils.hasText(value)) return Map.of();
            return objectMapper.readValue(value, new TypeReference<>() {
            });
        } catch (Exception ex) {
            throw new IllegalStateException("商品导入行快照损坏", ex);
        }
    }

    private String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception ex) {
            throw new IllegalStateException("无法生成商品导入文件摘要", ex);
        }
    }

    private String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String value(Map<String, ValueSource> targets, String target) {
        ValueSource source = targets.get(target);
        return source == null ? "" : text(source.value());
    }

    private static String fixedTargetForHeader(String header) {
        if (normalizedLookup(header).matches("^属性[:：].*")) return null;
        return FIXED_HEADER_ALIASES.get(normalizedFieldName(header));
    }

    private static Map<String, String> fixedHeaderAliases() {
        Map<String, String> result = new HashMap<>();
        addFixedAliases(result, "series", "产品系列", "系列", "商品系列", "product series", "series");
        addFixedAliases(result, "sku", "SKU", "商品SKU", "商品编码", "内部编码");
        addFixedAliases(result, "name", "商品名称", "品名", "产品名称", "name");
        addFixedAliases(result, "unit", "库存单位", "主单位", "基本单位", "unit");
        addFixedAliases(result, "grade", "牌号", "材质牌号", "grade");
        addFixedAliases(result, "temper", "材料状态", "软硬状态", "temper");
        addFixedAliases(result, "standard", "执行标准", "标准", "standard");
        addFixedAliases(result, "spec", "规格备注", "规格描述", "spec");
        addFixedAliases(result, "barcode", "条码", "商品条码", "barcode");
        addFixedAliases(result, "originalNumber", "供应商原始编号", "原厂编号", "外部编号",
                "original number");
        addFixedAliases(result, "defaultPrice", "默认单价", "参考单价", "default price");
        addFixedAliases(result, "status", "启用状态", "商品状态", "status");
        addFixedAliases(result, "sourceUrl", "来源URL", "来源网址", "source url");
        addFixedAliases(result, "sourceDocumentVersion", "来源文件版本", "资料版本",
                "source document version");
        addFixedAliases(result, "sourcePage", "来源页码", "页码", "source page");
        addFixedAliases(result, "memo", "备注", "memo");
        return Map.copyOf(result);
    }

    private static void addFixedAliases(Map<String, String> target, String canonical,
                                        String... aliases) {
        target.put(normalizedFieldName(canonical), canonical);
        for (String alias : aliases) target.put(normalizedFieldName(alias), canonical);
    }

    private static String referenceKey(FieldConfig field) {
        String configured = upper(field.referenceType());
        if ("GRADE".equals(configured)) return "grade";
        if ("TEMPER".equals(configured)) return "temper";
        if ("STANDARD".equals(configured)) return "standard";
        String name = normalizedFieldName(field.name());
        String display = normalizedFieldName(field.displayName());
        if (Set.of("牌号", "材质牌号", "牌号材质", "磁材牌号").contains(name)
                || Set.of("牌号", "材质牌号", "牌号材质", "磁材牌号").contains(display)) return "grade";
        if (Set.of("材料状态", "软硬状态", "状态").contains(name)
                || Set.of("材料状态", "软硬状态", "状态").contains(display)) return "temper";
        if (Set.of("执行标准", "标准").contains(name)
                || Set.of("执行标准", "标准").contains(display)) return "standard";
        return null;
    }

    private void applyReferenceField(SeriesConfig series, Map<Long, String> values,
                                     String referenceKey, String normalizedValue,
                                     List<ConflictDraft> conflicts) {
        if (!StringUtils.hasText(normalizedValue)) return;
        for (FieldConfig field : series.fields()) {
            if (Objects.equals(referenceKey(field), referenceKey)
                    && values.containsKey(field.id())) {
                String fieldValue = values.get(field.id());
                if (StringUtils.hasText(fieldValue)
                        && !Objects.equals(fieldValue, normalizedValue)) {
                    conflicts.add(ConflictDraft.blocking(
                            "REFERENCE_VALUE_MISMATCH", "field:" + field.id(),
                            fieldValue, normalizedValue,
                            "固定主数据列与规格字段“" + field.displayName() + "”的规范值不一致"));
                    continue;
                }
                values.put(field.id(), normalizedValue);
            }
        }
    }

    private static String normalizedFieldName(String value) {
        String normalized = normalizedLookup(value)
                .replaceFirst("^属性[:：]?", "")
                .replaceFirst("[*＊]+$", "");
        return normalized.replaceAll("[\\s_\\-/（）()：:*＊]+", "");
    }

    private static String normalizedLookup(String value) {
        if (value == null) return "";
        return java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKC)
                .trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private static long parseFieldId(String value) {
        try {
            return Long.parseLong(value.substring(value.indexOf(':') + 1).trim());
        } catch (RuntimeException ex) {
            throw new BusinessException("动态字段键必须使用 field:<系列字段ID>");
        }
    }

    private List<String> parseOptions(String optionJson) {
        if (!StringUtils.hasText(optionJson)) return List.of();
        try {
            JsonNode root = objectMapper.readTree(optionJson);
            if (root == null || !root.isArray()) return List.of();
            List<String> values = new ArrayList<>();
            root.forEach(node -> {
                if (node.isValueNode() && StringUtils.hasText(node.asText())) values.add(node.asText().trim());
            });
            return List.copyOf(values);
        } catch (Exception ex) {
            return List.of();
        }
    }

    private static List<String> splitAliases(String value) {
        if (!StringUtils.hasText(value)) return List.of();
        return java.util.Arrays.stream(value.split("[,，;；\\n]+"))
                .map(String::trim).filter(StringUtils::hasText).distinct().toList();
    }

    private static String canonicalUnit(String name, String abbreviation) {
        return StringUtils.hasText(abbreviation) ? abbreviation.trim() : text(name).trim();
    }

    private static void addAlias(Map<String, Set<String>> aliases, String alias, String canonical) {
        if (!StringUtils.hasText(alias) || !StringUtils.hasText(canonical)) return;
        aliases.computeIfAbsent(UnitLookupNormalizer.normalize(alias),
                ignored -> new LinkedHashSet<>()).add(canonical);
    }

    private static void addSeriesAlias(Map<String, List<SeriesConfig>> aliases,
                                       String alias, SeriesConfig series) {
        if (!StringUtils.hasText(alias)) return;
        aliases.computeIfAbsent(normalizedLookup(alias), ignored -> new ArrayList<>()).add(series);
    }

    private static String actorName(SecurityContext.Principal actor) {
        return StringUtils.hasText(actor.employeeName()) ? actor.employeeName() : actor.username();
    }

    private SecurityContext.Principal requireViewPermission() {
        SecurityContext.Principal actor = SecurityContext.require();
        if (!actor.hasPermission(VIEW_PERMISSION) && !actor.hasPermission(MANAGE_PERMISSION)) {
            throw new ForbiddenException("当前账号无权查看复杂商品主数据导入");
        }
        return actor;
    }

    private SecurityContext.Principal requireManagePermission() {
        SecurityContext.Principal actor = SecurityContext.require();
        if (!actor.hasPermission(MANAGE_PERMISSION)) {
            throw new ForbiddenException("当前账号无权预检、决策或提交复杂商品主数据导入");
        }
        return actor;
    }

    private SecurityContext.Principal requireProductManagePermission() {
        SecurityContext.Principal actor = SecurityContext.require();
        if (!actor.hasPermission(PRODUCT_MANAGE_PERMISSION)) {
            throw new ForbiddenException("当前账号无权将导入结果写入正式商品主数据");
        }
        return actor;
    }

    private static long id(Long value) {
        return value == null ? 0L : value;
    }

    private static int number(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static String upper(String value) {
        return text(value).trim().toUpperCase(Locale.ROOT);
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private static String limited(String value, int max) {
        String result = text(value);
        return result.length() <= max ? result : result.substring(0, max);
    }

    private record SeriesBuilder(
            Long id,
            String name,
            String categoryName,
            List<String> aliases,
            String status,
            List<ProductImportDtos.SeriesFieldOption> fields
    ) {
    }

    private record SeriesConfigBuilder(
            Long id,
            String name,
            List<String> aliases,
            String status,
            String expectedBaseUnit,
            List<FieldConfig> fields
    ) {
    }

    private record FieldConfig(
            Long id,
            String name,
            String displayName,
            String fieldType,
            String referenceType,
            String unitName,
            boolean required,
            boolean skuDimension,
            boolean includeInSpec,
            BigDecimal minValue,
            BigDecimal maxValue,
            int decimalScale,
            List<String> options
    ) {
    }

    private record SeriesConfig(
            Long id,
            String name,
            List<String> aliases,
            String status,
            String expectedBaseUnit,
            List<FieldConfig> fields,
            Map<Long, FieldConfig> fieldsById,
            Map<String, FieldConfig> fieldsByAlias
    ) {
        FieldConfig resolveField(String header) {
            String direct = normalizedLookup(header);
            if (direct.matches("field:\\d+")) return fieldsByAlias.get(direct);
            return fieldsByAlias.get(normalizedFieldName(header));
        }
    }

    private record SeriesCatalog(
            Map<Long, SeriesConfig> byId,
            Map<String, List<SeriesConfig>> byAlias
    ) {
        Resolution<SeriesConfig> resolve(String value) {
            List<SeriesConfig> matches = byAlias.getOrDefault(normalizedLookup(value), List.of());
            if (matches.size() > 1) return new Resolution<>(null, true);
            return new Resolution<>(matches.isEmpty() ? null : matches.get(0), false);
        }
    }

    private record UnitCatalog(Map<String, Set<String>> aliases) {
        Resolution<String> resolve(String value) {
            Set<String> matches = aliases.getOrDefault(
                    UnitLookupNormalizer.normalize(value), Set.of());
            if (matches.size() > 1) return new Resolution<>(null, true);
            return new Resolution<>(matches.isEmpty() ? null : matches.iterator().next(), false);
        }
    }

    @FunctionalInterface
    private interface ProductReferenceResolver {
        ProductMasterDataDtos.ReferenceResolution resolve(String referenceType, String sourceValue);
    }

    private static final class MasterDataReferenceResolver implements ProductReferenceResolver {
        private final ProductMasterDataService service;
        private final Map<String, ProductMasterDataDtos.ReferenceResolution> cache = new HashMap<>();

        private MasterDataReferenceResolver(ProductMasterDataService service) {
            this.service = service;
        }

        @Override
        public ProductMasterDataDtos.ReferenceResolution resolve(
                String referenceType, String sourceValue) {
            String key = referenceType + ":" + normalizedLookup(sourceValue);
            return cache.computeIfAbsent(key,
                    ignored -> service.resolveReference(referenceType, sourceValue, false));
        }
    }

    private record Resolution<T>(T value, boolean ambiguous) {
    }

    private record IdentityRuleSnapshot(
            Long productSeriesId,
            int identitySchemaVersion,
            String dimensionKey,
            String sourceType,
            Long seriesFieldId,
            String referenceType,
            boolean required,
            int sortNo,
            String status
    ) {
    }

    private record ValueSource(String header, String value, String rule) {
    }

    private record LineageDraft(String field, String value, String rule) {
    }

    private record ConflictDraft(
            String type,
            String fieldKey,
            String sourceValue,
            String targetValue,
            String severity,
            String message
    ) {
        static ConflictDraft blocking(String type, String fieldKey, String sourceValue,
                                      String targetValue, String message) {
            return new ConflictDraft(type, fieldKey, text(sourceValue), text(targetValue),
                    "BLOCKING", message);
        }

        boolean blocking() {
            return "BLOCKING".equals(severity);
        }
    }

    private record NormalizationResult(
            NormalizedProduct product,
            List<ConflictDraft> conflicts,
            Map<String, LineageDraft> lineage,
            String action,
            String decisionReason
    ) {
        NormalizationResult {
            conflicts = List.copyOf(conflicts);
            lineage = Map.copyOf(lineage);
        }

        static NormalizationResult failed(String message) {
            return new NormalizationResult(null,
                    List.of(ConflictDraft.blocking("ROW_PARSE_ERROR", "", "", "", message)),
                    Map.of(), "CONFLICT", "");
        }

        boolean hasBlockingConflict() {
            return conflicts.stream().anyMatch(ConflictDraft::blocking);
        }

        String validationMessage() {
            return conflicts.stream().filter(ConflictDraft::blocking)
                    .map(ConflictDraft::message).distinct().collect(Collectors.joining("；"));
        }

        NormalizationResult withConflict(ConflictDraft conflict) {
            List<ConflictDraft> values = new ArrayList<>(conflicts);
            values.add(conflict);
            return new NormalizationResult(product, values, lineage,
                    conflict.blocking() ? "CONFLICT" : action, decisionReason);
        }

        NormalizationResult asSkip(ConflictDraft warning) {
            List<ConflictDraft> values = new ArrayList<>(conflicts);
            values.add(warning);
            return new NormalizationResult(product, values, lineage, "SKIP", warning.message());
        }

        NormalizationResult withAction(String nextAction, String reason) {
            return new NormalizationResult(product, conflicts, lineage, nextAction, reason);
        }
    }

    private record NormalizedProduct(
            Long seriesId,
            String seriesName,
            String sku,
            String name,
            String unit,
            String grade,
            String temper,
            String standard,
            String spec,
            String barcode,
            String originalNumber,
            BigDecimal defaultPrice,
            String status,
            Map<Long, String> fieldValues,
            Set<String> provided,
            String sourceUrl,
            String sourceDocumentVersion,
            String sourcePage,
            String memo,
            String productSignature,
            int identitySchemaVersion,
            Long targetProductId,
            String targetProductSku,
            String targetFingerprint
    ) {
        NormalizedProduct {
            fieldValues = Map.copyOf(fieldValues);
            provided = Set.copyOf(provided);
        }

        NormalizedProduct withTarget(Long productId, String productSku, String fingerprint) {
            return new NormalizedProduct(
                    seriesId, seriesName, sku, name, unit, grade, temper, standard, spec,
                    barcode, originalNumber, defaultPrice, status, fieldValues, provided,
                    sourceUrl, sourceDocumentVersion, sourcePage, memo, productSignature,
                    identitySchemaVersion, productId, productSku, fingerprint);
        }

        NormalizedProduct withIdentity(String signature, Integer schemaVersion) {
            return new NormalizedProduct(
                    seriesId, seriesName, sku, name, unit, grade, temper, standard, spec,
                    barcode, originalNumber, defaultPrice, status, fieldValues, provided,
                    sourceUrl, sourceDocumentVersion, sourcePage, memo, signature,
                    schemaVersion == null ? 0 : schemaVersion, targetProductId,
                    targetProductSku, targetFingerprint);
        }
    }

    private record ExistingProductBuilder(
            Long id,
            Long seriesId,
            String seriesName,
            String sku,
            String name,
            String grade,
            String temper,
            String standard,
            String spec,
            String barcode,
            String originalNumber,
            String unit,
            BigDecimal defaultPrice,
            String status,
            int identitySchemaVersion,
            int currentIdentitySchemaVersion,
            String productSignature,
            Map<Long, String> fieldValues
    ) {
        ExistingProduct build() {
            return new ExistingProduct(id, seriesId, seriesName, sku, name, grade, temper,
                    standard, spec, barcode, originalNumber, unit, defaultPrice, status,
                    Map.copyOf(fieldValues), identitySchemaVersion,
                    currentIdentitySchemaVersion, productSignature);
        }
    }

    private record ExistingProduct(
            Long id,
            Long seriesId,
            String seriesName,
            String sku,
            String name,
            String grade,
            String temper,
            String standard,
            String spec,
            String barcode,
            String originalNumber,
            String unit,
            BigDecimal defaultPrice,
            String status,
            Map<Long, String> fieldValues,
            int identitySchemaVersion,
            int currentIdentitySchemaVersion,
            String productSignature
    ) {
    }

    private record ExistingCatalog(
            Map<String, ExistingProduct> bySku,
            Map<String, ExistingProduct> bySignature,
            Set<String> ambiguousSignatures
    ) {
    }

    private record FileIdentity(int rowNo, NormalizedProduct product) {
    }

    private record CommitClaim(boolean completed) {
    }

    public record SourceFileContent(String fileName, String contentType, byte[] content) {
    }

    private record CommitRow(
            Long id,
            String normalizedJson,
            String validationStatus,
            String action,
            Long targetProductId,
            String commitStatus
    ) {
    }

    private static final class Counters {
        int creates;
        int updates;
        int skips;
        int conflicts;
        int invalid;

        void add(String action, boolean invalidRow) {
            switch (action) {
                case "CREATE", "CREATE_PENDING" -> creates++;
                case "SAFE_UPDATE" -> updates++;
                case "SKIP" -> skips++;
                default -> conflicts++;
            }
            if (invalidRow) invalid++;
        }

        Map<String, Integer> asMap() {
            return Map.of(
                    "create", creates,
                    "safeUpdate", updates,
                    "skip", skips,
                    "conflict", conflicts,
                    "invalid", invalid);
        }
    }
}
