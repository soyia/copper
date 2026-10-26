package com.soyia.copperv2.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.soyia.copperv2.dto.DataMigrationDtos;
import com.soyia.copperv2.dto.SimpleCreateRequests;
import com.soyia.copperv2.security.SecurityContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
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
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class DataMigrationService {
    private static final List<String> DOMAIN_ORDER = List.of(
            "PARTNER", "WAREHOUSE", "PRODUCT", "INVENTORY", "PARTNER_OPENING", "FUND_OPENING", "GENERAL_LEDGER");
    private static final Map<String, String> DOMAIN_NAMES = Map.of(
            "PARTNER", "往来单位", "WAREHOUSE", "仓库", "PRODUCT", "商品", "INVENTORY", "库存期初",
            "PARTNER_OPENING", "往来期初", "FUND_OPENING", "资金期初", "GENERAL_LEDGER", "总账期初");
    private static final Set<String> PARTNER_ROLES = Set.of("CUSTOMER", "SUPPLIER", "BOTH");
    private static final Set<String> PARTNER_OPENING_TYPES = Set.of(
            "RECEIVABLE", "PAYABLE", "CUSTOMER_ADVANCE", "SUPPLIER_PREPAY");
    private static final Set<String> FUND_TYPES = Set.of("BANK", "CASH");
    private static final BigDecimal ZERO2 = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    private static final DateTimeFormatter NO_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");
    private static final AtomicInteger NO_COUNTER = new AtomicInteger();

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final OpeningMigrationWorkbook workbook;
    private final CopperService copperService;
    private final AccountingService accountingService;

    public DataMigrationService(JdbcTemplate jdbc, ObjectMapper objectMapper,
                                OpeningMigrationWorkbook workbook, CopperService copperService,
                                AccountingService accountingService) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.workbook = workbook;
        this.copperService = copperService;
        this.accountingService = accountingService;
    }

    public OpeningMigrationWorkbook.TemplateFile template() {
        return workbook.template();
    }

    public List<DataMigrationDtos.BatchRow> list() {
        return jdbc.query(batchSelect() + " order by b.id desc limit 100", batchMapper());
    }

    public DataMigrationDtos.Detail detail(Long batchId) {
        DataMigrationDtos.BatchRow header = batch(batchId);
        List<DataMigrationDtos.DomainSummary> domains = domainSummaries(batchId);
        List<DataMigrationDtos.RowResult> errors = jdbc.query("""
                select id,domain_code,source_sheet,source_row_no,source_key,validation_status,error_code,error_message,
                       target_type,target_id,target_created,applied_at,rolled_back_at
                from data_migration_row where batch_id=? and validation_status='ERROR'
                order by field(domain_code,'PARTNER','WAREHOUSE','PRODUCT','INVENTORY','PARTNER_OPENING','FUND_OPENING','GENERAL_LEDGER'),source_row_no
                limit 1000
                """, rowMapper(), batchId);
        List<DataMigrationDtos.EventRow> events = jdbc.query("""
                select id,action,from_status,to_status,reason,operator_name,created_at
                from data_migration_event where batch_id=? order by id
                """, (rs, rowNum) -> new DataMigrationDtos.EventRow(
                rs.getLong("id"), rs.getString("action"), rs.getString("from_status"), rs.getString("to_status"),
                rs.getString("reason"), rs.getString("operator_name"), rs.getTimestamp("created_at").toLocalDateTime()), batchId);
        return new DataMigrationDtos.Detail(header, domains, errors, events, latestCheck(batchId), List.of(
                "上传和预检不形成库存、往来、资金或总账事实。",
                "应用后仅在没有后续业务且尚未确认切换时允许整批撤销。",
                "确认切换会审核并记账期初凭证；此后只能通过业务调整、冲正或越期调整修正。",
                "系统检查是数据安全门槛，不能替代企业会计对科目、税务和法定期初口径的复核。"));
    }

    @Transactional
    public DataMigrationDtos.Detail upload(LocalDate openingDate, MultipartFile file) {
        if (openingDate == null) throw new BusinessException("请选择期初日期");
        long entityId = entityId();
        assertOpenPeriod(entityId, openingDate);
        OpeningMigrationWorkbook.ParsedWorkbook parsed = workbook.parse(file);
        String fileHash = sha256(parsed.bytes());
        List<Long> existing = jdbc.queryForList("""
                select id from data_migration_batch where entity_id=? and opening_date=? and source_file_sha256=?
                order by id desc limit 1
                """, Long.class, entityId, Date.valueOf(openingDate), fileHash);
        if (!existing.isEmpty()) return detail(existing.get(0));

        Validation validation = validate(parsed.rows(), openingDate, entityId);
        Actor actor = actor();
        String status = validation.errorCount() == 0 ? "PREFLIGHT_PASSED" : "PREFLIGHT_FAILED";
        String batchNo = nextNo("QC");
        Long batchId = insert("""
                insert into data_migration_batch
                (entity_id,batch_no,opening_date,source_file_name,source_file_sha256,source_file_size,status,
                 total_rows,valid_rows,error_rows,imported_by,imported_by_name)
                values (?,?,?,?,?,?,?,?,?,?,?,?)
                """, entityId, batchNo, Date.valueOf(openingDate), safeFileName(parsed.fileName()), fileHash,
                parsed.bytes().length, status, validation.rows().size(), validation.validCount(), validation.errorCount(),
                actor.id(), actor.name());
        for (ValidatedRow row : validation.rows()) insertRow(batchId, row);
        event(batchId, "PREFLIGHT", "", status,
                status.equals("PREFLIGHT_PASSED") ? "七张工作表预检通过；尚未形成业务事实"
                        : "预检发现 " + validation.errorCount() + " 行错误；请修正原文件后重新上传",
                actor);
        return detail(batchId);
    }

    @Transactional
    public DataMigrationDtos.Detail apply(Long batchId, DataMigrationDtos.Action action) {
        BatchLock batch = lockBatch(batchId);
        if (List.of("APPLIED", "CUTOVER").contains(batch.status())) return detail(batchId);
        if (!"PREFLIGHT_PASSED".equals(batch.status())) throw new BusinessException("只有预检通过的批次可以应用");
        requiredReason(action);
        assertNoOtherActiveBatch(batchId, batch.entityId());
        assertEmptyOperationalFacts();
        Actor actor = actor();
        try {
            Map<String, Long> partnerTargets = applyPartners(batchId);
            Map<String, Long> warehouseTargets = applyWarehouses(batchId);
            Map<String, Long> productTargets = applyProducts(batchId);
            applyInventory(batch, productTargets, warehouseTargets);
            applyPartnerOpenings(batch, partnerTargets);
            applyFundOpenings(batch);
            long voucherId = applyOpeningVoucher(batch, partnerTargets, productTargets, warehouseTargets);
            jdbc.update("update treasury_document set accounting_voucher_id=? where data_migration_row_id in (select id from data_migration_row where batch_id=?)",
                    voucherId, batchId);
            int appliedRows = Objects.requireNonNull(jdbc.queryForObject(
                    "select count(*) from data_migration_row where batch_id=? and applied_at is not null", Integer.class, batchId));
            jdbc.update("""
                    update data_migration_batch set status='APPLIED',applied_rows=?,opening_voucher_id=?,
                      applied_by=?,applied_by_name=?,applied_at=now(),failure_message=''
                    where id=? and status='PREFLIGHT_PASSED'
                    """, appliedRows, voucherId, actor.id(), actor.name(), batchId);
            event(batchId, "APPLY", "PREFLIGHT_PASSED", "APPLIED", action.reason().trim(), actor);
            DataMigrationDtos.CheckRun check = runCheck(batchId, "APPLY", actor);
            if (!"PASSED".equals(check.status())) throw new BusinessException("应用后勾稽未通过，整批应用已回滚");
            return detail(batchId);
        } catch (RuntimeException ex) {
            throw ex;
        }
    }

    @Transactional
    public DataMigrationDtos.Detail rollback(Long batchId, DataMigrationDtos.Action action) {
        BatchLock batch = lockBatch(batchId);
        if ("ROLLED_BACK".equals(batch.status())) return detail(batchId);
        if ("CUTOVER".equals(batch.status())) throw new BusinessException("已确认切换的期初批次不能整批撤销；请使用调整或冲正保留历史");
        if (!Set.of("PREFLIGHT_FAILED", "PREFLIGHT_PASSED", "APPLIED").contains(batch.status()))
            throw new BusinessException("当前迁移状态不能撤销");
        requiredReason(action);
        Actor actor = actor();
        if ("APPLIED".equals(batch.status())) rollbackApplied(batch);
        String from = batch.status();
        jdbc.update("""
                update data_migration_batch set status='ROLLED_BACK',rolled_back_by=?,rolled_back_by_name=?,rolled_back_at=now()
                where id=? and status=?
                """, actor.id(), actor.name(), batchId, from);
        event(batchId, "ROLLBACK", from, "ROLLED_BACK", action.reason().trim(), actor);
        return detail(batchId);
    }

    @Transactional
    public DataMigrationDtos.Detail cutover(Long batchId, DataMigrationDtos.Action action) {
        BatchLock batch = lockBatch(batchId);
        if ("CUTOVER".equals(batch.status())) return detail(batchId);
        if (!"APPLIED".equals(batch.status())) throw new BusinessException("只有已应用且尚未切换的批次可以确认切换");
        requiredReason(action);
        assertNoDownstreamUsage(batch);
        Actor actor = actor();
        DataMigrationDtos.CheckRun before = runCheck(batchId, "CUTOVER", actor);
        if (!"PASSED".equals(before.status())) throw new BusinessException("切换勾稽未通过，请先处理全部阻断差异");
        accountingService.reviewVoucher(batch.openingVoucherId(), "期初迁移切换复核：" + action.reason().trim());
        accountingService.postVoucher(batch.openingVoucherId());
        jdbc.update("""
                update data_migration_batch set status='CUTOVER',cutover_by=?,cutover_by_name=?,cutover_at=now()
                where id=? and status='APPLIED'
                """, actor.id(), actor.name(), batchId);
        event(batchId, "CUTOVER", "APPLIED", "CUTOVER",
                action.reason().trim() + "；期初凭证已记账，后续修正必须走调整或冲正", actor);
        DataMigrationDtos.CheckRun after = runCheck(batchId, "CUTOVER", actor);
        if (!"PASSED".equals(after.status())) throw new BusinessException("切换记账后勾稽未通过，切换事务已回滚");
        return detail(batchId);
    }

    @Transactional
    public DataMigrationDtos.CheckRun check(Long batchId) {
        BatchLock batch = lockBatch(batchId);
        if (!Set.of("APPLIED", "CUTOVER").contains(batch.status()))
            throw new BusinessException("仅已应用或已切换批次可以执行一致性检查");
        return runCheck(batchId, "MANUAL", actor());
    }

    @Scheduled(cron = "${copper.data-migration.consistency-cron:0 30 2 * * *}")
    @Transactional
    public void scheduledConsistencyCheck() {
        List<Long> ids = jdbc.queryForList("select id from data_migration_batch where status='CUTOVER' order by id", Long.class);
        for (Long id : ids) {
            try {
                runCheck(id, "SCHEDULED", new Actor(0L, "系统一致性检查"));
            } catch (RuntimeException ignored) {
                // 检查本身的失败由下一轮和人工检查继续暴露；不得影响其他已切换批次。
            }
        }
    }

    private Validation validate(List<OpeningMigrationWorkbook.ParsedRow> sourceRows,
                                LocalDate openingDate, long entityId) {
        List<ValidatedRow> rows = sourceRows.stream().map(ValidatedRow::new).toList();
        Map<String, Map<String, ValidatedRow>> byDomain = new LinkedHashMap<>();
        for (String domain : DOMAIN_ORDER) byDomain.put(domain, new LinkedHashMap<>());
        for (ValidatedRow row : rows) {
            String key = requiredValue(row, "来源键*");
            row.sourceKey = key;
            if (!key.isBlank()) {
                ValidatedRow prior = byDomain.get(row.domain()).putIfAbsent(key, row);
                if (prior != null) {
                    row.error("DUPLICATE_SOURCE_KEY", "同一工作表来源键重复");
                    prior.error("DUPLICATE_SOURCE_KEY", "同一工作表来源键重复");
                }
            }
        }

        for (ValidatedRow row : rows) switch (row.domain()) {
            case "PARTNER" -> validatePartner(row);
            case "WAREHOUSE" -> validateWarehouse(row);
            case "PRODUCT" -> validateProduct(row, entityId);
            case "INVENTORY" -> validateInventory(row, byDomain);
            case "PARTNER_OPENING" -> validatePartnerOpening(row, byDomain, openingDate);
            case "FUND_OPENING" -> validateFundOpening(row, entityId);
            case "GENERAL_LEDGER" -> validateGeneralLedger(row, byDomain, entityId);
            default -> row.error("UNKNOWN_DOMAIN", "未知迁移工作表");
        };
        if (byDomain.get("GENERAL_LEDGER").isEmpty())
            throw new BusinessException("总账期初工作表至少需要一行借贷分录");
        validateDuplicates(rows);
        validateTrialAndReconciliation(rows);
        int errors = (int) rows.stream().filter(ValidatedRow::hasError).count();
        return new Validation(rows, rows.size() - errors, errors);
    }

    private void validatePartner(ValidatedRow row) {
        String name = requiredValue(row, "名称*");
        String role = enumValue(row, "角色*", PARTNER_ROLES);
        optionalMoney(row, "授信额度", true);
        if (role.isBlank()) row.error("PARTNER_ROLE_REQUIRED", "角色必须是 CUSTOMER、SUPPLIER 或 BOTH");
        String creditCode = text(row, "统一信用代码");
        if (name.isBlank()) return;
        List<Map<String, Object>> existing = StringUtils.hasText(creditCode)
                ? jdbc.queryForList("select id,type_mask,status from partner where normalized_credit_code=lower(trim(?))", creditCode)
                : jdbc.queryForList("select id,type_mask,status from partner where normalized_name=lower(trim(?))", name);
        if (!StringUtils.hasText(creditCode) && existing.isEmpty())
            row.error("PARTNER_CREDIT_CODE_REQUIRED", "新建企业往来单位必须填写统一社会信用代码");
        if (existing.size() > 1) row.error("PARTNER_MASTER_AMBIGUOUS", "系统中存在多个匹配往来单位，请先整理主数据");
        if (existing.size() == 1) {
            Map<String, Object> current = existing.get(0);
            if (!"ENABLED".equals(String.valueOf(current.get("status"))))
                row.error("PARTNER_MASTER_DISABLED", "匹配的往来单位已停用，不能用于期初迁移");
            if (!role.isBlank() && (number(current.get("type_mask")) & partnerRoleMask(role)) != partnerRoleMask(role))
                row.error("PARTNER_ROLE_MISMATCH", "匹配的往来单位不具备迁移文件要求的客户或供应商身份");
        }
    }

    private void validateWarehouse(ValidatedRow row) {
        String name = requiredValue(row, "名称*");
        if (name.isBlank()) return;
        List<String> statuses = jdbc.queryForList("select status from warehouse where name=?", String.class, name);
        if (statuses.size() > 1) row.error("WAREHOUSE_MASTER_AMBIGUOUS", "系统中存在多个同名仓库，请先整理主数据");
        if (statuses.size() == 1 && !"ENABLED".equals(statuses.get(0)))
            row.error("WAREHOUSE_MASTER_DISABLED", "匹配的仓库已停用，不能用于期初库存");
    }

    private void validateProduct(ValidatedRow row, long entityId) {
        requiredValue(row, "SKU*");
        requiredValue(row, "名称*");
        String series = requiredValue(row, "商品系列*");
        requiredValue(row, "主库存单位*");
        optionalMoney(row, "默认售价", true);
        List<Long> seriesIds = jdbc.queryForList(
                "select id from product_series where name=? and status='ENABLED'", Long.class, series);
        if (!series.isBlank() && seriesIds.isEmpty()) row.error("PRODUCT_SERIES_NOT_FOUND", "商品系列不存在或已停用");
        if (seriesIds.size() > 1) row.error("PRODUCT_SERIES_AMBIGUOUS", "商品系列名称不唯一，请先整理主数据");
        if (!seriesIds.isEmpty()) validateProductAttributes(row, seriesIds.get(0));
        String sku = text(row, "SKU*");
        if (!sku.isBlank()) {
            List<Map<String, Object>> existing = jdbc.queryForList("""
                    select p.name,p.unit,p.status,s.name series_name from product p
                    join product_series s on s.id=p.product_series_id where p.sku=?
                    """, sku);
            if (existing.size() > 1) row.error("PRODUCT_MASTER_AMBIGUOUS", "系统中存在多个相同 SKU，请先整理商品主数据");
            if (existing.size() == 1) {
                Map<String, Object> current = existing.get(0);
                if (!"ENABLED".equals(String.valueOf(current.get("status"))))
                    row.error("PRODUCT_MASTER_DISABLED", "匹配的商品已停用，不能用于期初库存");
                if (!Objects.equals(String.valueOf(current.get("name")), text(row, "名称*"))
                        || !Objects.equals(String.valueOf(current.get("series_name")), series)
                        || !Objects.equals(String.valueOf(current.get("unit")), text(row, "主库存单位*")))
                    row.error("PRODUCT_MASTER_CONFLICT", "已有 SKU 的名称、系列或主库存单位与迁移文件不一致");
            }
        }
    }

    private void validateProductAttributes(ValidatedRow row, Long seriesId) {
        Map<String, Object> attributes;
        try {
            String json = text(row, "规格属性JSON");
            attributes = json.isBlank() ? Map.of() : objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception ex) {
            row.error("PRODUCT_ATTRIBUTE_JSON_INVALID", "规格属性JSON无法解析");
            return;
        }
        List<Map<String, Object>> fields = jdbc.queryForList("""
                select field_name,coalesce(nullif(display_name,''),field_name) display_name,required
                from product_series_field where product_series_id=? and status='ENABLED'
                """, seriesId);
        for (Map<String, Object> field : fields) {
            if (number(field.get("required")) != 1) continue;
            String internal = String.valueOf(field.get("field_name"));
            String display = String.valueOf(field.get("display_name"));
            Object value = attributes.containsKey(internal) ? attributes.get(internal) : attributes.get(display);
            if (value == null || String.valueOf(value).isBlank())
                row.error("PRODUCT_REQUIRED_ATTRIBUTE", "缺少商品系列必填规格：“" + display + "”");
        }
    }

    private void validateInventory(ValidatedRow row, Map<String, Map<String, ValidatedRow>> byDomain) {
        reference(row, "商品来源键*", "PRODUCT", byDomain);
        reference(row, "仓库来源键*", "WAREHOUSE", byDomain);
        requiredValue(row, "批次号*");
        positive(row, "数量*", 3);
        positive(row, "单位成本*", 4);
        optionalDate(row, "生产日期");
    }

    private void validatePartnerOpening(ValidatedRow row, Map<String, Map<String, ValidatedRow>> byDomain,
                                        LocalDate openingDate) {
        String partnerKey = reference(row, "往来单位来源键*", "PARTNER", byDomain);
        String type = enumValue(row, "类型*", PARTNER_OPENING_TYPES);
        positive(row, "金额*", 2);
        LocalDate due = requiredDate(row, "到期日*");
        requiredValue(row, "原单号*");
        if (due != null && due.isBefore(openingDate.minusYears(20)))
            row.error("DUE_DATE_IMPLAUSIBLE", "到期日早于期初日期二十年以上，请核对");
        ValidatedRow partner = byDomain.get("PARTNER").get(partnerKey);
        if (partner != null && !type.isBlank()) {
            String role = text(partner, "角色*").toUpperCase(Locale.ROOT);
            boolean customer = type.startsWith("RECEIVABLE") || type.startsWith("CUSTOMER");
            if (customer && !Set.of("CUSTOMER", "BOTH").contains(role))
                row.error("PARTNER_ROLE_MISMATCH", "客户期初必须引用客户或双角色往来单位");
            if (!customer && !Set.of("SUPPLIER", "BOTH").contains(role))
                row.error("PARTNER_ROLE_MISMATCH", "供应商期初必须引用供应商或双角色往来单位");
        }
    }

    private void validateFundOpening(ValidatedRow row, long entityId) {
        String type = enumValue(row, "类型*", FUND_TYPES);
        String fundAccountCode = requiredValue(row, "账户编码*");
        requiredValue(row, "账户名称*");
        String accountCode = requiredValue(row, "科目编码*");
        nonNegative(row, "余额*", 2);
        String currency = text(row, "币种").isBlank() ? "CNY" : text(row, "币种").toUpperCase(Locale.ROOT);
        if (!"CNY".equals(currency)) row.error("CURRENCY_NOT_SUPPORTED", "当前期初迁移仅支持人民币 CNY");
        if ("BANK".equals(type) && (text(row, "开户行").isBlank() || text(row, "银行账号").isBlank()))
            row.error("BANK_DETAILS_REQUIRED", "银行账户必须填写开户行和银行账号");
        if ("BANK".equals(type) && !"1002".equals(accountCode))
            row.error("BANK_LEDGER_ACCOUNT_INVALID", "银行期初必须使用 1002 银行存款科目");
        if ("CASH".equals(type) && !"1001".equals(accountCode))
            row.error("CASH_LEDGER_ACCOUNT_INVALID", "现金期初必须使用 1001 库存现金科目");
        long ledgerId = accountCode.isBlank() ? 0 : accountId(entityId, accountCode);
        if (!accountCode.isBlank() && ledgerId == 0)
            row.error("LEDGER_ACCOUNT_NOT_FOUND", "科目编码不存在、未生效或不是末级科目");
        if ("BANK".equals(type) && ledgerId > 0) {
            String bankAccountNo = text(row, "银行账号");
            List<Map<String, Object>> mapped = jdbc.queryForList(
                    "select * from business_bank_account where entity_id=? and ledger_account_id=?", entityId, ledgerId);
            if (mapped.size() > 1) row.error("BANK_LEDGER_MAPPING_CONFLICT", "银行存款科目存在多个账户映射，请先整理主数据");
            if (mapped.size() == 1) {
                Map<String, Object> existing = mapped.get(0);
                long bankId = number(existing.get("id"));
                if (existing.get("data_migration_row_id") != null)
                    row.error("BANK_ACCOUNT_ALREADY_MIGRATED", "银行存款科目已经由另一迁移批次占用");
                if (bankAccountUsageCount(bankId) > 0)
                    row.error("BANK_ACCOUNT_IN_USE", "银行存款账户已经发生资金或对账业务，不能由期初迁移覆盖");
            }
            if (!fundAccountCode.isBlank() && count("select count(*) from business_bank_account where entity_id=? and account_code=? and ledger_account_id<>?",
                    entityId, fundAccountCode, ledgerId) > 0)
                row.error("BANK_ACCOUNT_CODE_CONFLICT", "账户编码已被其他银行存款科目使用");
            if (!bankAccountNo.isBlank() && count("select count(*) from business_bank_account where entity_id=? and bank_account_no=? and ledger_account_id<>?",
                    entityId, bankAccountNo, ledgerId) > 0)
                row.error("BANK_ACCOUNT_NO_CONFLICT", "银行账号已被其他银行存款科目使用");
        }
    }

    private void validateGeneralLedger(ValidatedRow row, Map<String, Map<String, ValidatedRow>> byDomain, long entityId) {
        String accountCode = requiredValue(row, "科目编码*");
        BigDecimal debit = nonNegative(row, "借方", 2);
        BigDecimal credit = nonNegative(row, "贷方", 2);
        requiredValue(row, "摘要*");
        if ((debit.signum() > 0) == (credit.signum() > 0))
            row.error("DEBIT_CREDIT_EXCLUSIVE", "借方和贷方必须且只能填写一方");
        if (!accountCode.isBlank() && accountId(entityId, accountCode) == 0)
            row.error("LEDGER_ACCOUNT_NOT_FOUND", "科目编码不存在、未生效或不是末级科目");
        optionalReference(row, "往来单位来源键", "PARTNER", byDomain);
        optionalReference(row, "商品来源键", "PRODUCT", byDomain);
        optionalReference(row, "仓库来源键", "WAREHOUSE", byDomain);
    }

    private void validateDuplicates(List<ValidatedRow> rows) {
        duplicateValue(rows, "PARTNER", "名称*", "DUPLICATE_PARTNER_NAME", "往来单位名称重复");
        duplicateValue(rows, "WAREHOUSE", "名称*", "DUPLICATE_WAREHOUSE_NAME", "仓库名称重复");
        duplicateValue(rows, "PRODUCT", "SKU*", "DUPLICATE_PRODUCT_SKU", "商品 SKU 重复");
        duplicateValue(rows, "FUND_OPENING", "账户编码*", "DUPLICATE_FUND_ACCOUNT", "资金账户编码重复");
        duplicateValue(rows, "FUND_OPENING", "银行账号", "DUPLICATE_BANK_ACCOUNT", "银行账号重复");
        duplicateValue(rows, "FUND_OPENING", "科目编码*", "DUPLICATE_FUND_LEDGER", "同一资金科目只能有一条期初账户记录");
        Map<String, ValidatedRow> inventoryKeys = new HashMap<>();
        for (ValidatedRow row : rows.stream().filter(r -> "INVENTORY".equals(r.domain())).toList()) {
            String key = text(row, "商品来源键*") + "|" + text(row, "仓库来源键*") + "|" + text(row, "批次号*");
            markDuplicate(inventoryKeys, key, row, "DUPLICATE_INVENTORY_BATCH", "同一商品、仓库和批次号重复");
        }
    }

    private void validateTrialAndReconciliation(List<ValidatedRow> rows) {
        BigDecimal debit = sum(rows, "GENERAL_LEDGER", "借方");
        BigDecimal credit = sum(rows, "GENERAL_LEDGER", "贷方");
        if (debit.compareTo(credit) != 0 || debit.signum() <= 0) {
            generalLedgerError(rows, "TRIAL_BALANCE_FAILED", "总账期初借贷不平衡：借方 " + debit + "，贷方 " + credit);
        }
        BigDecimal inventory = rows.stream().filter(r -> "INVENTORY".equals(r.domain()))
                .map(r -> decimal(text(r, "数量*")).multiply(decimal(text(r, "单位成本*"))).setScale(2, RoundingMode.HALF_UP))
                .reduce(ZERO2, BigDecimal::add);
        BigDecimal receivable = sumByType(rows, "RECEIVABLE");
        BigDecimal payable = sumByType(rows, "PAYABLE");
        BigDecimal funds = sum(rows, "FUND_OPENING", "余额*");
        compareLedger(rows, "1405", inventory, "INVENTORY_GL_MISMATCH", "库存期初金额与 1405 库存商品不一致");
        compareLedger(rows, "1122", receivable, "RECEIVABLE_GL_MISMATCH", "应收期初与 1122 应收账款不一致");
        BigDecimal payableLedger = ledgerDebitNet(rows, Set.of("2202")).negate().setScale(2);
        if (payableLedger.compareTo(payable) != 0)
            generalLedgerError(rows, "PAYABLE_GL_MISMATCH",
                    "应付期初与 2202 应付账款不一致：业务 " + payable + "，总账 " + payableLedger);
        compareLedger(rows, "1123", sumByType(rows, "SUPPLIER_PREPAY"),
                "SUPPLIER_PREPAY_GL_MISMATCH", "供应商预付期初与 1123 预付账款不一致");
        BigDecimal customerAdvanceLedger = ledgerDebitNet(rows, Set.of("2203")).negate().setScale(2);
        BigDecimal customerAdvance = sumByType(rows, "CUSTOMER_ADVANCE");
        if (customerAdvanceLedger.compareTo(customerAdvance) != 0)
            generalLedgerError(rows, "CUSTOMER_ADVANCE_GL_MISMATCH",
                    "客户预收期初与 2203 预收账款不一致：业务 " + customerAdvance + "，总账 " + customerAdvanceLedger);
        BigDecimal fundLedger = ledgerDebitNet(rows, Set.of("1001", "1002"));
        if (fundLedger.compareTo(funds) != 0)
            generalLedgerError(rows, "FUND_GL_MISMATCH", "资金期初与 1001/1002 现金银行科目不一致：资金 " + funds + "，总账 " + fundLedger);
    }

    private Map<String, Long> applyPartners(Long batchId) {
        Map<String, Long> targets = new LinkedHashMap<>();
        for (StoredRow row : storedRows(batchId, "PARTNER")) {
            Map<String, String> value = row.payload();
            String name = value.get("名称*");
            String creditCode = value.get("统一信用代码");
            List<Long> existing = StringUtils.hasText(creditCode)
                    ? jdbc.queryForList("select id from partner where normalized_credit_code=lower(trim(?))", Long.class, creditCode)
                    : jdbc.queryForList("select id from partner where normalized_name=lower(trim(?))", Long.class, name);
            Long id;
            boolean created = false;
            if (!existing.isEmpty()) {
                if (existing.size() != 1) throw new BusinessException("匹配的往来单位不唯一，不能应用期初迁移");
                id = existing.get(0);
                assertExistingPartnerRole(id, value.get("角色*"));
            } else {
                int mask = switch (value.get("角色*").toUpperCase(Locale.ROOT)) {
                    case "SUPPLIER" -> 2;
                    case "BOTH" -> 3;
                    default -> 1;
                };
                id = copperService.createPartner(new SimpleCreateRequests.PartnerCreate(
                        value.get("角色*"), mask, "COMPANY", name, "", creditCode,
                        value.get("联系人"), "", value.get("手机"), "", "", "", money(value.get("授信额度")),
                        false, null, null, "期初迁移", "", "ERP-18 期初迁移：" + value.get("备注"), "ENABLED", "",
                        "ENABLED", "", "NORMAL", "PER_ORDER", 0, true, "NONE", ZERO2, "",
                        "ENABLED", "", "PER_ORDER", 0, 0, ZERO2, true, "NONE", ZERO2, ""));
                created = true;
            }
            markApplied(row, "PARTNER", id, created);
            targets.put(row.sourceKey(), id);
        }
        return targets;
    }

    private Map<String, Long> applyWarehouses(Long batchId) {
        Map<String, Long> targets = new LinkedHashMap<>();
        for (StoredRow row : storedRows(batchId, "WAREHOUSE")) {
            Map<String, String> value = row.payload();
            List<Long> existing = jdbc.queryForList("select id from warehouse where name=?", Long.class, value.get("名称*"));
            if (existing.size() > 1) throw new BusinessException("匹配的仓库不唯一，不能应用期初迁移");
            boolean created = existing.isEmpty();
            Long id = created ? copperService.createWarehouse(new SimpleCreateRequests.WarehouseCreate(
                    value.get("名称*"), value.get("位置"), value.get("收货地址"), "ENABLED")) : existing.get(0);
            markApplied(row, "WAREHOUSE", id, created);
            targets.put(row.sourceKey(), id);
        }
        return targets;
    }

    private Map<String, Long> applyProducts(Long batchId) {
        Map<String, Long> targets = new LinkedHashMap<>();
        for (StoredRow row : storedRows(batchId, "PRODUCT")) {
            Map<String, String> value = row.payload();
            List<Long> existing = jdbc.queryForList("select id from product where sku=?", Long.class, value.get("SKU*"));
            if (existing.size() > 1) throw new BusinessException("匹配的商品 SKU 不唯一，不能应用期初迁移");
            boolean created = existing.isEmpty();
            Long id;
            if (created) {
                Long seriesId = requiredLong("select id from product_series where name=? and status='ENABLED'", value.get("商品系列*"));
                String attributeJson = normalizedProductAttributes(seriesId, value.get("规格属性JSON"));
                id = copperService.createProduct(new SimpleCreateRequests.ProductCreate(
                        null, seriesId, null, value.get("SKU*"), value.get("名称*"), value.get("材质牌号"),
                        value.get("软硬状态"), value.get("执行标准"), value.get("规格"), attributeJson,
                        value.get("条码"), value.get("原编号"), value.get("主库存单位*"),
                        money(value.get("默认售价")), "ENABLED", null));
            } else {
                id = existing.get(0);
                Map<String, Object> current = requiredMap("""
                        select p.name,s.name series_name,p.unit from product p join product_series s on s.id=p.product_series_id
                        where p.id=? and p.status='ENABLED'
                        """, id);
                if (!Objects.equals(String.valueOf(current.get("name")), value.get("名称*"))
                        || !Objects.equals(String.valueOf(current.get("series_name")), value.get("商品系列*"))
                        || !Objects.equals(String.valueOf(current.get("unit")), value.get("主库存单位*")))
                    throw new BusinessException("已有商品 SKU 与迁移名称、系列或主库存单位不一致：" + value.get("SKU*"));
            }
            markApplied(row, "PRODUCT", id, created);
            targets.put(row.sourceKey(), id);
        }
        return targets;
    }

    private void applyInventory(BatchLock batch, Map<String, Long> products, Map<String, Long> warehouses) {
        for (StoredRow row : storedRows(batch.id(), "INVENTORY")) {
            Map<String, String> value = row.payload();
            long productId = target(products, value.get("商品来源键*"), "商品");
            long warehouseId = target(warehouses, value.get("仓库来源键*"), "仓库");
            BigDecimal quantity = quantity(value.get("数量*"));
            BigDecimal unitCost = cost(value.get("单位成本*"));
            BigDecimal amount = quantity.multiply(unitCost).setScale(2, RoundingMode.HALF_UP);
            String unit = requiredText("select unit from product where id=?", productId);
            LocalDate productionDate = dateOrNull(value.get("生产日期"));
            Long batchId = insert("""
                    insert into inventory_batch
                    (product_id,warehouse_id,batch_no,source_type,source_id,data_migration_row_id,base_unit,
                     quantity_in,quantity_available,unit_cost,amount_cost,status,received_at,production_date)
                    values (?,?,?,'DATA_MIGRATION',?,?,?,?,?,?,?,'AVAILABLE',?,?)
                    """, productId, warehouseId, value.get("批次号*"), row.id(), row.id(), unit,
                    quantity, quantity, unitCost, amount, Date.valueOf(batch.openingDate()),
                    productionDate == null ? null : Date.valueOf(productionDate));
            jdbc.update("""
                    insert into stock_balance(product_id,warehouse_id,quantity_available,amount_cost) values (?,?,?,?)
                    on duplicate key update quantity_available=quantity_available+values(quantity_available),
                      amount_cost=amount_cost+values(amount_cost)
                    """, productId, warehouseId, quantity, amount);
            jdbc.update("""
                    insert into inventory_movement
                    (movement_no,movement_type,product_id,warehouse_id,batch_id,quantity,unit_price,amount,ref_type,ref_id,occurred_at,memo)
                    values (?,'INBOUND',?,?,?,?,?,?,'DATA_MIGRATION',?,?,?)
                    """, nextNo("QK"), productId, warehouseId, batchId, quantity, unitCost, amount,
                    row.id(), Timestamp.valueOf(batch.openingDate().atStartOfDay()), "期初迁移 " + value.get("备注"));
            markApplied(row, "INVENTORY_BATCH", batchId, true);
        }
    }

    private void applyPartnerOpenings(BatchLock batch, Map<String, Long> partners) {
        for (StoredRow row : storedRows(batch.id(), "PARTNER_OPENING")) {
            Map<String, String> value = row.payload();
            long partnerId = target(partners, value.get("往来单位来源键*"), "往来单位");
            String type = value.get("类型*").toUpperCase(Locale.ROOT);
            BigDecimal amount = money(value.get("金额*"));
            if (Set.of("RECEIVABLE", "PAYABLE").contains(type)) {
                String documentType = "RECEIVABLE".equals(type) ? "OPENING_RECEIVABLE" : "OPENING_PAYABLE";
                String offsetCode = "RECEIVABLE".equals(type) ? "1122" : "2202";
                long accountId = accountId(batch.entityId(), offsetCode);
                String accountName = requiredText("select account_name from accounting_account where id=?", accountId);
                String partnerName = requiredText("select name from partner where id=?", partnerId);
                Long documentId = insert("""
                        insert into treasury_document
                        (document_no,document_type,partner_id,data_migration_row_id,partner_name_snapshot,business_date,due_date,
                         basis_no,summary,offset_account_id,offset_account_code_snapshot,offset_account_name_snapshot,
                         price_includes_tax,tax_rate,amount_excluding_tax,tax_amount,total_amount,tax_rounding_difference,
                         tax_rule_snapshot,input_tax_treatment,settled_amount,status,memo,created_by,created_by_name,
                         confirmed_by,confirmed_by_name,confirmed_at)
                        values (?,?,?,?,?,?,?,?,?,?,?,?,0,0,?,0,?,0,'ERP18_OPENING_NO_TAX','DEDUCTIBLE',0,'CONFIRMED',?,?,?,?,?,now())
                        """, nextNo("QW"), documentType, partnerId, row.id(), partnerName,
                        Date.valueOf(batch.openingDate()), Date.valueOf(requiredDate(value.get("到期日*"))),
                        value.get("原单号*"), "期初往来 " + value.get("原单号*"), accountId, offsetCode, accountName,
                        amount, amount, "期初迁移 " + value.get("备注"), actor().id(), actor().name(), actor().id(), actor().name());
                jdbc.update("""
                        insert into treasury_document_event(document_id,action,from_status,to_status,reason,operator_id,operator_name)
                        values (?,'MIGRATION_APPLY','','CONFIRMED','期初迁移应用；尚未确认切换',?,?)
                        """, documentId, actor().id(), actor().name());
                markApplied(row, "TREASURY_DOCUMENT", documentId, true);
            } else {
                String accountType = "CUSTOMER_ADVANCE".equals(type) ? "CUSTOMER_ADVANCE" : "SUPPLIER_PREPAY";
                List<Map<String, Object>> accounts = jdbc.queryForList(
                        "select id,balance from partner_account where partner_id=? and account_type=? for update",
                        partnerId, accountType);
                if (accounts.size() > 1) throw new BusinessException("期初预收预付账户不唯一，不能应用迁移");
                boolean accountCreated = accounts.isEmpty();
                BigDecimal before = accountCreated ? ZERO2 : money(accounts.get(0).get("balance"));
                if (before.signum() != 0) throw new BusinessException("期初预收预付要求对应账户尚无余额");
                if (accountCreated) jdbc.update(
                        "insert into partner_account(partner_id,account_type,balance) values (?,?,0)", partnerId, accountType);
                jdbc.update("update partner_account set balance=?,updated_at=now() where partner_id=? and account_type=?",
                        amount, partnerId, accountType);
                Long logId = insert("""
                        insert into partner_account_log(partner_id,account_type,source_type,source_id,amount,amount_before,amount_after)
                        values (?,?,'DATA_MIGRATION',?,?,0,?)
                        """, partnerId, accountType, row.id(), amount, amount);
                markApplied(row, "PARTNER_ACCOUNT_LOG", logId, true, Map.of(
                        "partnerAccountCreated", accountCreated,
                        "balanceBefore", before.toPlainString()));
            }
        }
    }

    private void applyFundOpenings(BatchLock batch) {
        for (StoredRow row : storedRows(batch.id(), "FUND_OPENING")) {
            Map<String, String> value = row.payload();
            String type = value.get("类型*").toUpperCase(Locale.ROOT);
            String ledgerCode = value.get("科目编码*");
            long ledgerId = accountId(batch.entityId(), ledgerCode);
            BigDecimal balance = money(value.get("余额*"));
            long bankId = 0;
            boolean bankCreated = false;
            String bankBeforeJson = null;
            if ("BANK".equals(type)) {
                List<Map<String, Object>> mapped = jdbc.queryForList(
                        "select * from business_bank_account where entity_id=? and ledger_account_id=? for update",
                        batch.entityId(), ledgerId);
                if (mapped.isEmpty()) {
                    bankId = insert("""
                            insert into business_bank_account
                            (entity_id,data_migration_row_id,account_code,account_name,bank_name,bank_account_no,currency,
                             ledger_account_id,opening_date,opening_balance,status,created_by,created_by_name)
                            values (?,?,?,?,?,?,?,?,?,?,'ENABLED',?,?)
                            """, batch.entityId(), row.id(), value.get("账户编码*"), value.get("账户名称*"), value.get("开户行"),
                            value.get("银行账号"), currency(value.get("币种")), ledgerId, Date.valueOf(batch.openingDate()), balance,
                            actor().id(), actor().name());
                    bankCreated = true;
                } else {
                    if (mapped.size() != 1) throw new BusinessException("银行存款科目账户映射不唯一，不能应用期初迁移");
                    Map<String, Object> existing = mapped.get(0);
                    bankId = number(existing.get("id"));
                    if (existing.get("data_migration_row_id") != null || bankAccountUsageCount(bankId) > 0)
                        throw new BusinessException("银行存款账户已经被迁移或发生后续业务，不能应用期初迁移");
                    bankBeforeJson = json(bankAccountSnapshot(existing));
                    jdbc.update("""
                            update business_bank_account set data_migration_row_id=?,account_code=?,account_name=?,bank_name=?,
                              bank_account_no=?,currency=?,opening_date=?,opening_balance=?,status='ENABLED',disabled_reason=''
                            where id=? and data_migration_row_id is null
                            """, row.id(), value.get("账户编码*"), value.get("账户名称*"), value.get("开户行"),
                            value.get("银行账号"), currency(value.get("币种")), Date.valueOf(batch.openingDate()), balance, bankId);
                }
            }
            Long factId = insert("""
                    insert into data_migration_fund_opening
                    (migration_row_id,batch_id,fund_type,account_code,account_name,bank_name,bank_account_no,currency,
                     ledger_account_id,opening_date,opening_balance,business_bank_account_id,
                     business_bank_account_created,business_bank_account_before_json)
                    values (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, row.id(), batch.id(), type, value.get("账户编码*"), value.get("账户名称*"), value.get("开户行"),
                    value.get("银行账号"), currency(value.get("币种")), ledgerId, Date.valueOf(batch.openingDate()), balance, bankId,
                    bankCreated ? 1 : 0, bankBeforeJson);
            markApplied(row, "FUND_OPENING", factId, true);
        }
    }

    private long applyOpeningVoucher(BatchLock batch, Map<String, Long> partners,
                                     Map<String, Long> products, Map<String, Long> warehouses) {
        List<AccountingService.SystemVoucherLine> lines = new ArrayList<>();
        for (StoredRow row : storedRows(batch.id(), "GENERAL_LEDGER")) {
            Map<String, String> value = row.payload();
            lines.add(new AccountingService.SystemVoucherLine(
                    value.get("科目编码*"), value.get("摘要*"), money(value.get("借方")), money(value.get("贷方")),
                    optionalTarget(partners, value.get("往来单位来源键")), optionalTarget(products, value.get("商品来源键")),
                    optionalTarget(warehouses, value.get("仓库来源键"))));
        }
        long voucherId = accountingService.createSourceVoucher("DATA_MIGRATION", batch.id(), batch.batchNo(),
                batch.openingDate(), "期初迁移 " + batch.batchNo(), lines);
        jdbc.update("update accounting_voucher set data_migration_batch_id=? where id=?", batch.id(), voucherId);
        for (StoredRow row : storedRows(batch.id(), "GENERAL_LEDGER")) markApplied(row, "ACCOUNTING_VOUCHER", voucherId, false);
        return voucherId;
    }

    private void rollbackApplied(BatchLock batch) {
        assertNoDownstreamUsage(batch);
        accountingService.cancelVoucher(batch.openingVoucherId(), "期初迁移批次撤销");
        rollbackPartnerOpenings(batch.id());
        rollbackInventory(batch.id());
        rollbackBusinessBankAccounts(batch.id());
        jdbc.update("update data_migration_fund_opening set status='ROLLED_BACK',rolled_back_at=now() where batch_id=? and status='ACTIVE'", batch.id());
        rollbackCreatedMasters(batch.id(), "PRODUCT");
        rollbackCreatedMasters(batch.id(), "WAREHOUSE");
        rollbackCreatedMasters(batch.id(), "PARTNER");
        jdbc.update("update data_migration_row set rolled_back_at=now() where batch_id=? and applied_at is not null", batch.id());
    }

    private void rollbackPartnerOpenings(Long batchId) {
        List<Map<String, Object>> docs = jdbc.queryForList("""
                select d.id from treasury_document d join data_migration_row r on r.id=d.data_migration_row_id
                where r.batch_id=?
                """, batchId);
        for (Map<String, Object> doc : docs) {
            long id = number(doc.get("id"));
            jdbc.update("delete from treasury_document_event where document_id=?", id);
            jdbc.update("delete from treasury_document where id=?", id);
        }
        for (StoredRow row : storedRows(batchId, "PARTNER_OPENING")) {
            if (!"PARTNER_ACCOUNT_LOG".equals(row.targetType())) continue;
            Map<String, Object> log = requiredMap("select * from partner_account_log where id=?", row.targetId());
            long partnerId = number(log.get("partner_id"));
            String accountType = String.valueOf(log.get("account_type"));
            BigDecimal amount = money(log.get("amount"));
            BigDecimal balance = requiredDecimalForUpdate(
                    "select balance from partner_account where partner_id=? and account_type=? for update", partnerId, accountType);
            if (balance.compareTo(amount) != 0 || count("select count(*) from partner_account_log where partner_id=? and account_type=? and id<>?",
                    partnerId, accountType, row.targetId()) > 0)
                throw new BusinessException("期初预收预付已发生后续业务，不能整批撤销");
            Map<String, Object> rollback = row.rollbackContext();
            boolean accountCreated = Boolean.TRUE.equals(rollback.get("partnerAccountCreated"));
            BigDecimal balanceBefore = money(rollback.getOrDefault("balanceBefore", ZERO2));
            jdbc.update("delete from partner_account_log where id=?", row.targetId());
            if (accountCreated) {
                jdbc.update("delete from partner_account where partner_id=? and account_type=?", partnerId, accountType);
            } else {
                jdbc.update("update partner_account set balance=?,updated_at=now() where partner_id=? and account_type=?",
                        balanceBefore, partnerId, accountType);
            }
        }
    }

    private void rollbackInventory(Long batchId) {
        List<Map<String, Object>> batches = jdbc.queryForList("""
                select b.* from inventory_batch b join data_migration_row r on r.id=b.data_migration_row_id where r.batch_id=? for update
                """, batchId);
        for (Map<String, Object> batch : batches) {
            long id = number(batch.get("id"));
            BigDecimal in = quantity(batch.get("quantity_in"));
            BigDecimal available = quantity(batch.get("quantity_available"));
            if (in.compareTo(available) != 0 || count("select count(*) from inventory_movement where batch_id=? and not (ref_type='DATA_MIGRATION' and ref_id=?)",
                    id, number(batch.get("data_migration_row_id"))) > 0)
                throw new BusinessException("期初库存批次已发生后续出入库或成本变化，不能整批撤销");
            long productId = number(batch.get("product_id"));
            long warehouseId = number(batch.get("warehouse_id"));
            BigDecimal amount = money(batch.get("amount_cost"));
            jdbc.update("delete from inventory_movement where batch_id=? and ref_type='DATA_MIGRATION'", id);
            jdbc.update("""
                    update stock_balance set quantity_available=quantity_available-?,amount_cost=amount_cost-?
                    where product_id=? and warehouse_id=?
                    """, available, amount, productId, warehouseId);
            jdbc.update("delete from stock_balance where product_id=? and warehouse_id=? and quantity_available=0 and amount_cost=0",
                    productId, warehouseId);
            jdbc.update("delete from inventory_batch where id=?", id);
        }
    }

    private void rollbackBusinessBankAccounts(Long batchId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select f.*,r.id migration_row_id from data_migration_fund_opening f
                join data_migration_row r on r.id=f.migration_row_id
                where f.batch_id=? and f.fund_type='BANK' and f.status='ACTIVE' for update
                """, batchId);
        for (Map<String, Object> row : rows) {
            long bankId = number(row.get("business_bank_account_id"));
            long migrationRowId = number(row.get("migration_row_id"));
            if (number(row.get("business_bank_account_created")) == 1) {
                jdbc.update("delete from business_bank_account where id=? and data_migration_row_id=?", bankId, migrationRowId);
                continue;
            }
            Map<String, Object> before = parseSummary(String.valueOf(row.get("business_bank_account_before_json")));
            jdbc.update("""
                    update business_bank_account set data_migration_row_id=null,account_code=?,account_name=?,bank_name=?,
                      bank_account_no=?,currency=?,opening_date=?,opening_balance=?,status=?,disabled_reason=?
                    where id=? and data_migration_row_id=?
                    """, before.get("accountCode"), before.get("accountName"), before.get("bankName"),
                    before.get("bankAccountNo"), before.get("currency"), Date.valueOf(String.valueOf(before.get("openingDate"))),
                    money(before.get("openingBalance")), before.get("status"), before.get("disabledReason"), bankId, migrationRowId);
        }
    }

    private void rollbackCreatedMasters(Long batchId, String domain) {
        List<StoredRow> rows = storedRows(batchId, domain).stream().filter(StoredRow::targetCreated)
                .sorted(Comparator.comparing(StoredRow::id).reversed()).toList();
        for (StoredRow row : rows) switch (domain) {
            case "PRODUCT" -> {
                jdbc.update("delete from product_field_value where product_id=?", row.targetId());
                jdbc.update("delete from product where id=?", row.targetId());
            }
            case "WAREHOUSE" -> jdbc.update("delete from warehouse where id=?", row.targetId());
            case "PARTNER" -> {
                jdbc.update("delete from partner_contact where partner_id=?", row.targetId());
                jdbc.update("delete from partner_address where partner_id=?", row.targetId());
                jdbc.update("delete from partner_customer_profile where partner_id=?", row.targetId());
                jdbc.update("delete from partner_supplier_profile where partner_id=?", row.targetId());
                jdbc.update("delete from partner_account where partner_id=?", row.targetId());
                jdbc.update("delete from partner where id=?", row.targetId());
            }
            default -> throw new IllegalStateException(domain);
        };
    }

    private DataMigrationDtos.CheckRun runCheck(Long batchId, String trigger, Actor actor) {
        BatchLock batch = lockBatch(batchId);
        List<CheckResult> checks = new ArrayList<>();
        int validRows = (int) count("select count(*) from data_migration_row where batch_id=? and validation_status='VALID'", batchId);
        int appliedRows = (int) count("select count(*) from data_migration_row where batch_id=? and applied_at is not null", batchId);
        check(checks, "ROW_APPLICATION_COMPLETE", "ALL", validRows, appliedRows,
                "全部预检通过行都必须只应用一次");
        check(checks, "MASTER_TARGET_LINK", "MASTER_DATA",
                count("select count(*) from data_migration_row where batch_id=? and domain_code in ('PARTNER','WAREHOUSE','PRODUCT')", batchId),
                count("""
                        select count(*) from data_migration_row r where r.batch_id=? and
                          ((r.domain_code='PARTNER' and r.target_type='PARTNER' and exists(select 1 from partner p where p.id=r.target_id and p.status='ENABLED'))
                           or (r.domain_code='WAREHOUSE' and r.target_type='WAREHOUSE' and exists(select 1 from warehouse w where w.id=r.target_id and w.status='ENABLED'))
                           or (r.domain_code='PRODUCT' and r.target_type='PRODUCT' and exists(select 1 from product p where p.id=r.target_id and p.status='ENABLED')))
                        """, batchId), "往来单位、仓库和商品必须唯一关联仍然有效的主数据");
        check(checks, "INVENTORY_TARGET_LINK", "INVENTORY",
                count("select count(*) from data_migration_row where batch_id=? and domain_code='INVENTORY'", batchId),
                count("""
                        select count(*) from inventory_batch b join data_migration_row r on r.id=b.data_migration_row_id
                        where r.batch_id=? and b.source_type='DATA_MIGRATION' and b.source_id=r.id
                        """, batchId), "库存期初行必须唯一关联库存批次");
        check(checks, "INVENTORY_OPENING_MOVEMENT", "INVENTORY",
                count("select count(*) from data_migration_row where batch_id=? and domain_code='INVENTORY'", batchId),
                count("""
                        select count(*) from inventory_movement m join data_migration_row r on r.id=m.ref_id
                        where r.batch_id=? and m.ref_type='DATA_MIGRATION'
                        """, batchId), "库存期初必须保留不可覆盖的来源流水");
        checkDecimal(checks, "INVENTORY_OPENING_AMOUNT", "INVENTORY",
                expectedInventoryAmount(batchId), decimalQuery("""
                        select coalesce(sum(m.amount),0) from inventory_movement m join data_migration_row r on r.id=m.ref_id
                        where r.batch_id=? and m.ref_type='DATA_MIGRATION'
                        """, batchId), "库存期初金额必须与来源文件一致");
        checkDecimal(checks, "STOCK_BALANCE_QUANTITY", "INVENTORY",
                decimalQuery("select coalesce(sum(quantity_available),0) from inventory_batch"),
                decimalQuery("select coalesce(sum(quantity_available),0) from stock_balance"),
                "库存余额数量必须等于全部批次当前结存");
        checkDecimal(checks, "STOCK_BALANCE_AMOUNT", "INVENTORY",
                decimalQuery("select coalesce(sum(amount_cost),0) from inventory_batch"),
                decimalQuery("select coalesce(sum(amount_cost),0) from stock_balance"),
                "库存金额账必须等于全部批次当前成本金额");
        check(checks, "PARTNER_OPENING_TARGET", "PARTNER_OPENING",
                count("select count(*) from data_migration_row where batch_id=? and domain_code='PARTNER_OPENING'", batchId),
                count("""
                        select count(*) from data_migration_row r where r.batch_id=? and r.domain_code='PARTNER_OPENING'
                          and ((r.target_type='TREASURY_DOCUMENT' and exists(select 1 from treasury_document d where d.id=r.target_id and d.data_migration_row_id=r.id))
                            or (r.target_type='PARTNER_ACCOUNT_LOG' and exists(select 1 from partner_account_log l where l.id=r.target_id and l.source_type='DATA_MIGRATION' and l.source_id=r.id)))
                        """, batchId), "往来期初必须唯一关联可结算源单或预收预付流水");
        checkDecimal(checks, "RECEIVABLE_OPENING_FACT", "PARTNER_OPENING",
                expectedPartnerOpening(batchId, "RECEIVABLE"), decimalQuery("""
                        select coalesce(sum(d.total_amount),0) from treasury_document d
                        join data_migration_row r on r.id=d.data_migration_row_id
                        where r.batch_id=? and d.document_type='OPENING_RECEIVABLE'
                        """, batchId), "期初应收源单金额必须与迁移来源逐行一致");
        checkDecimal(checks, "PAYABLE_OPENING_FACT", "PARTNER_OPENING",
                expectedPartnerOpening(batchId, "PAYABLE"), decimalQuery("""
                        select coalesce(sum(d.total_amount),0) from treasury_document d
                        join data_migration_row r on r.id=d.data_migration_row_id
                        where r.batch_id=? and d.document_type='OPENING_PAYABLE'
                        """, batchId), "期初应付源单金额必须与迁移来源逐行一致");
        checkDecimal(checks, "CUSTOMER_ADVANCE_OPENING_FACT", "PARTNER_OPENING",
                expectedPartnerOpening(batchId, "CUSTOMER_ADVANCE"), decimalQuery("""
                        select coalesce(sum(l.amount),0) from partner_account_log l
                        join data_migration_row r on r.id=l.source_id
                        where r.batch_id=? and l.source_type='DATA_MIGRATION' and l.account_type='CUSTOMER_ADVANCE'
                        """, batchId), "客户预收期初流水必须与迁移来源逐行一致");
        checkDecimal(checks, "SUPPLIER_PREPAY_OPENING_FACT", "PARTNER_OPENING",
                expectedPartnerOpening(batchId, "SUPPLIER_PREPAY"), decimalQuery("""
                        select coalesce(sum(l.amount),0) from partner_account_log l
                        join data_migration_row r on r.id=l.source_id
                        where r.batch_id=? and l.source_type='DATA_MIGRATION' and l.account_type='SUPPLIER_PREPAY'
                        """, batchId), "供应商预付期初流水必须与迁移来源逐行一致");
        check(checks, "PARTNER_ACCOUNT_LOG_BALANCE", "PARTNER_OPENING", 0,
                count("""
                        select count(*) from partner_account a
                        where exists(select 1 from partner_account_log l
                            where l.partner_id=a.partner_id and l.account_type=a.account_type)
                          and a.balance<>(select l2.amount_after from partner_account_log l2
                            where l2.partner_id=a.partner_id and l2.account_type=a.account_type
                            order by l2.id desc limit 1)
                        """), "往来余额必须等于最后一笔可追溯账户流水余额");
        check(checks, "FUND_OPENING_TARGET", "FUND_OPENING",
                count("select count(*) from data_migration_row where batch_id=? and domain_code='FUND_OPENING'", batchId),
                count("select count(*) from data_migration_fund_opening where batch_id=? and status='ACTIVE'", batchId),
                "每个资金期初行必须形成独立资金事实");
        checkDecimal(checks, "FUND_OPENING_AMOUNT", "FUND_OPENING", expectedFundAmountFromRows(batchId),
                expectedFundAmount(batchId), "银行和现金期初事实金额必须与迁移来源逐行一致");
        check(checks, "FUND_BANK_ACCOUNT_LINK", "FUND_OPENING",
                count("select count(*) from data_migration_fund_opening where batch_id=? and fund_type='BANK' and status='ACTIVE'", batchId),
                count("""
                        select count(*) from data_migration_fund_opening f
                        join business_bank_account b on b.id=f.business_bank_account_id
                        where f.batch_id=? and f.fund_type='BANK' and f.status='ACTIVE'
                          and b.data_migration_row_id=f.migration_row_id and b.account_code=f.account_code
                          and b.bank_account_no=f.bank_account_no and b.opening_balance=f.opening_balance
                        """, batchId), "银行期初必须唯一关联同账号、同余额的企业银行账户");
        check(checks, "OPENING_VOUCHER_LINK", "GENERAL_LEDGER", 1,
                count("select count(*) from accounting_voucher where id=? and data_migration_batch_id=? and source_type='DATA_MIGRATION'",
                        batch.openingVoucherId(), batchId), "期初总账必须唯一关联迁移批次凭证");
        checkDecimal(checks, "TRIAL_BALANCE", "GENERAL_LEDGER",
                decimalQuery("select debit_total from accounting_voucher where id=?", batch.openingVoucherId()),
                decimalQuery("select credit_total from accounting_voucher where id=?", batch.openingVoucherId()),
                "期初凭证借贷必须平衡");
        checkDecimal(checks, "INVENTORY_GL", "RECONCILIATION", expectedInventoryAmount(batchId),
                voucherDebitNet(batch.openingVoucherId(), Set.of("1405")), "库存期初与 1405 科目必须勾稽");
        checkDecimal(checks, "RECEIVABLE_GL", "RECONCILIATION", expectedPartnerOpening(batchId, "RECEIVABLE"),
                voucherDebitNet(batch.openingVoucherId(), Set.of("1122")), "应收期初与 1122 科目必须勾稽");
        checkDecimal(checks, "PAYABLE_GL", "RECONCILIATION", expectedPartnerOpening(batchId, "PAYABLE"),
                voucherCreditNet(batch.openingVoucherId(), Set.of("2202")), "应付期初与 2202 科目必须勾稽");
        checkDecimal(checks, "SUPPLIER_PREPAY_GL", "RECONCILIATION", expectedPartnerOpening(batchId, "SUPPLIER_PREPAY"),
                voucherDebitNet(batch.openingVoucherId(), Set.of("1123")), "供应商预付期初与 1123 科目必须勾稽");
        checkDecimal(checks, "CUSTOMER_ADVANCE_GL", "RECONCILIATION", expectedPartnerOpening(batchId, "CUSTOMER_ADVANCE"),
                voucherCreditNet(batch.openingVoucherId(), Set.of("2203")), "客户预收期初与 2203 科目必须勾稽");
        checkDecimal(checks, "FUND_GL", "RECONCILIATION", expectedFundAmount(batchId),
                voucherDebitNet(batch.openingVoucherId(), Set.of("1001", "1002")), "现金银行期初与资金事实必须勾稽");
        String voucherStatus = requiredText("select status from accounting_voucher where id=?", batch.openingVoucherId());
        if ("POSTED".equals(voucherStatus) || "CUTOVER".equals(batch.status())) {
            check(checks, "LEDGER_POSTING_COMPLETE", "GENERAL_LEDGER",
                    count("select count(*) from accounting_voucher_line where voucher_id=?", batch.openingVoucherId()),
                    count("select count(*) from accounting_ledger_entry where voucher_id=?", batch.openingVoucherId()),
                    "切换后每条期初凭证分录必须进入不可变总账");
        }
        long issueCount = checks.stream().filter(result -> !result.passed()).count();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("batchNo", batch.batchNo());
        summary.put("status", batch.status());
        summary.put("voucherStatus", voucherStatus);
        summary.put("checkedAt", LocalDateTime.now().toString());
        summary.put("checkedCount", checks.size());
        summary.put("issueCount", issueCount);
        Long runId = insert("""
                insert into data_migration_check_run
                (batch_id,trigger_type,status,checked_count,issue_count,summary_json,checked_by,checked_by_name)
                values (?,?,?,?,?,?,?,?)
                """, batchId, trigger, issueCount == 0 ? "PASSED" : "FAILED", checks.size(), issueCount,
                json(summary), actor.id(), actor.name());
        for (CheckResult result : checks) if (!result.passed()) insert("""
                insert into data_migration_check_issue
                (check_run_id,check_code,severity,domain_code,expected_value,actual_value,message)
                values (?,?,'BLOCKING',?,?,?,?)
                """, runId, result.code(), result.domain(), result.expected(), result.actual(), result.message());
        jdbc.update("update data_migration_batch set last_checked_at=now() where id=?", batchId);
        return checkRun(runId);
    }

    private void assertNoOtherActiveBatch(long batchId, long entityId) {
        if (count("""
                select count(*) from data_migration_batch where entity_id=? and id<>? and status in ('PREFLIGHT_PASSED','APPLIED')
                """, entityId, batchId) > 0) throw new BusinessException("存在另一批待处理或已应用迁移，请先完成或撤销该批次");
    }

    private void assertEmptyOperationalFacts() {
        Map<String, Long> facts = new LinkedHashMap<>();
        facts.put("库存流水", count("select count(*) from inventory_movement"));
        facts.put("库存批次", count("select count(*) from inventory_batch"));
        facts.put("库存余额", count("select count(*) from stock_balance where quantity_available<>0 or amount_cost<>0"));
        facts.put("采购订单", count("select count(*) from purchase_order where status<>'DRAFT'"));
        facts.put("销售订单", count("select count(*) from sales_order where status<>'DRAFT'"));
        facts.put("往来源单", count("select count(*) from treasury_document where status<>'DRAFT'"));
        facts.put("客户回款", count("select count(*) from payment"));
        facts.put("供应商付款", count("select count(*) from supplier_payment"));
        facts.put("预收退款", count("select count(*) from customer_advance_refund"));
        facts.put("承兑票据", count("select count(*) from acceptance_bill"));
        facts.put("往来余额", count("select count(*) from partner_account where balance<>0 or frozen_amount<>0 or opposite_balance<>0"));
        facts.put("资金流水", count("select count(*) from treasury_cash_journal where status='ACTIVE'"));
        facts.put("银行对账", count("select count(*) from bank_reconciliation_session"));
        facts.put("发票", count("select count(*) from tax_invoice"));
        facts.put("会计凭证", count("select count(*) from accounting_voucher where status not in ('CANCELLED','REVERSED')"));
        facts.put("已记账总账", count("select count(*) from accounting_ledger_entry"));
        List<String> nonEmpty = facts.entrySet().stream().filter(entry -> entry.getValue() > 0)
                .map(entry -> entry.getKey() + " " + entry.getValue() + " 条").toList();
        if (!nonEmpty.isEmpty()) throw new BusinessException("期初迁移只允许用于尚未发生正式业务的账套；当前已有：" + String.join("、", nonEmpty));
    }

    private void assertNoDownstreamUsage(BatchLock batch) {
        if (count("""
                select count(*) from inventory_movement m join inventory_batch b on b.id=m.batch_id
                join data_migration_row r on r.id=b.data_migration_row_id
                where r.batch_id=? and not (m.ref_type='DATA_MIGRATION' and m.ref_id=r.id)
                """, batch.id()) > 0) throw new BusinessException("期初库存已经发生后续业务，不能撤销或直接切换");
        if (count("""
                select count(*) from treasury_fund_transaction f join treasury_document d on d.id=f.document_id
                join data_migration_row r on r.id=d.data_migration_row_id where r.batch_id=? and f.status='ACTIVE'
                """, batch.id()) > 0) throw new BusinessException("期初往来已经发生后续收付，不能整批撤销或直接切换");
        if (count("select count(*) from accounting_ledger_entry where voucher_id<>?", batch.openingVoucherId()) > 0)
            throw new BusinessException("期初迁移期间已出现其他记账凭证，请先核对并恢复空白切换窗口");
    }

    private List<StoredRow> storedRows(Long batchId, String domain) {
        return jdbc.query("""
                select id,source_key,payload_json,target_type,target_id,target_created,rollback_context_json
                from data_migration_row where batch_id=? and domain_code=? and validation_status='VALID'
                order by source_row_no
                """, (rs, rowNum) -> new StoredRow(rs.getLong("id"), rs.getString("source_key"),
                parsePayload(rs.getString("payload_json")), rs.getString("target_type"), rs.getLong("target_id"),
                rs.getInt("target_created") == 1, parseNullableSummary(rs.getString("rollback_context_json"))), batchId, domain);
    }

    private void insertRow(Long batchId, ValidatedRow row) {
        insert("""
                insert into data_migration_row
                (batch_id,domain_code,source_sheet,source_row_no,source_key,payload_json,row_hash,
                 validation_status,error_code,error_message)
                values (?,?,?,?,?,?,?,?,?,?)
                """, batchId, row.domain(), row.sheet(), row.rowNo(), row.sourceKey(), json(row.values()),
                sha256(json(row.values()).getBytes(StandardCharsets.UTF_8)), row.hasError() ? "ERROR" : "VALID",
                row.errorCodes(), row.errorMessage());
    }

    private void markApplied(StoredRow row, String targetType, long targetId, boolean created) {
        markApplied(row, targetType, targetId, created, null);
    }

    private void markApplied(StoredRow row, String targetType, long targetId, boolean created,
                             Map<String, Object> rollbackContext) {
        int updated = jdbc.update("""
                update data_migration_row set target_type=?,target_id=?,target_created=?,rollback_context_json=?,applied_at=now()
                where id=? and applied_at is null
                """, targetType, targetId, created ? 1 : 0,
                rollbackContext == null ? null : json(rollbackContext), row.id());
        if (updated != 1) throw new BusinessException("迁移行已经应用，幂等保护拒绝重复写入：" + row.sourceKey());
    }

    private List<DataMigrationDtos.DomainSummary> domainSummaries(Long batchId) {
        Map<String, DataMigrationDtos.DomainSummary> values = new LinkedHashMap<>();
        List<Map<String, Object>> counts = jdbc.queryForList("""
                select domain_code,count(*) total_rows,sum(validation_status='VALID') valid_rows,
                       sum(validation_status='ERROR') error_rows,sum(applied_at is not null) applied_rows
                from data_migration_row where batch_id=? group by domain_code
                """, batchId);
        for (Map<String, Object> count : counts) {
            String domain = String.valueOf(count.get("domain_code"));
            values.put(domain, new DataMigrationDtos.DomainSummary(domain, DOMAIN_NAMES.get(domain),
                    (int) number(count.get("total_rows")), (int) number(count.get("valid_rows")),
                    (int) number(count.get("error_rows")), (int) number(count.get("applied_rows")),
                    ZERO2.setScale(3), ZERO2));
        }
        List<DataMigrationDtos.DomainSummary> result = new ArrayList<>();
        for (String domain : DOMAIN_ORDER) {
            DataMigrationDtos.DomainSummary base = values.getOrDefault(domain,
                    new DataMigrationDtos.DomainSummary(domain, DOMAIN_NAMES.get(domain), 0, 0, 0, 0, ZERO2.setScale(3), ZERO2));
            BigDecimal quantity = "INVENTORY".equals(domain) ? expectedInventoryQuantity(batchId) : ZERO2.setScale(3);
            BigDecimal amount = switch (domain) {
                case "INVENTORY" -> expectedInventoryAmount(batchId);
                case "PARTNER_OPENING" -> expectedPartnerOpeningTotal(batchId);
                case "FUND_OPENING" -> expectedFundAmountFromRows(batchId);
                case "GENERAL_LEDGER" -> expectedLedgerDebit(batchId);
                default -> ZERO2;
            };
            result.add(new DataMigrationDtos.DomainSummary(base.domainCode(), base.domainName(), base.totalRows(),
                    base.validRows(), base.errorRows(), base.appliedRows(), quantity, amount));
        }
        return result;
    }

    private DataMigrationDtos.CheckRun latestCheck(Long batchId) {
        List<Long> ids = jdbc.queryForList("select id from data_migration_check_run where batch_id=? order by id desc limit 1",
                Long.class, batchId);
        return ids.isEmpty() ? null : checkRun(ids.get(0));
    }

    private DataMigrationDtos.CheckRun checkRun(Long runId) {
        Map<String, Object> row = requiredMap("select * from data_migration_check_run where id=?", runId);
        List<DataMigrationDtos.CheckIssue> issues = jdbc.query("""
                select id,check_code,severity,domain_code,expected_value,actual_value,message
                from data_migration_check_issue where check_run_id=? order by id
                """, (rs, rowNum) -> new DataMigrationDtos.CheckIssue(rs.getLong("id"), rs.getString("check_code"),
                rs.getString("severity"), rs.getString("domain_code"), rs.getString("expected_value"),
                rs.getString("actual_value"), rs.getString("message")), runId);
        return new DataMigrationDtos.CheckRun(runId, String.valueOf(row.get("trigger_type")), String.valueOf(row.get("status")),
                (int) number(row.get("checked_count")), (int) number(row.get("issue_count")),
                parseSummary(String.valueOf(row.get("summary_json"))), String.valueOf(row.get("checked_by_name")),
                timestamp(row.get("created_at")), issues);
    }

    private DataMigrationDtos.BatchRow batch(Long batchId) {
        List<DataMigrationDtos.BatchRow> rows = jdbc.query(batchSelect() + " where b.id=?", batchMapper(), batchId);
        if (rows.isEmpty()) throw new BusinessException("期初迁移批次不存在");
        return rows.get(0);
    }

    private String batchSelect() {
        return """
                select b.*,coalesce(v.voucher_no,'') opening_voucher_no,coalesce(v.status,'') opening_voucher_status
                from data_migration_batch b left join accounting_voucher v on v.id=b.opening_voucher_id
                """;
    }

    private org.springframework.jdbc.core.RowMapper<DataMigrationDtos.BatchRow> batchMapper() {
        return (rs, rowNum) -> new DataMigrationDtos.BatchRow(rs.getLong("id"), rs.getString("batch_no"),
                rs.getDate("opening_date").toLocalDate(), rs.getString("source_file_name"), rs.getString("source_file_sha256"),
                rs.getLong("source_file_size"), rs.getString("status"), rs.getInt("total_rows"), rs.getInt("valid_rows"),
                rs.getInt("error_rows"), rs.getInt("applied_rows"), rs.getLong("opening_voucher_id"),
                rs.getString("opening_voucher_no"), rs.getString("opening_voucher_status"), rs.getString("failure_message"),
                rs.getString("imported_by_name"), rs.getString("applied_by_name"), rs.getString("cutover_by_name"),
                rs.getString("rolled_back_by_name"), timestamp(rs.getTimestamp("preflight_at")),
                timestamp(rs.getTimestamp("applied_at")), timestamp(rs.getTimestamp("cutover_at")),
                timestamp(rs.getTimestamp("rolled_back_at")), timestamp(rs.getTimestamp("last_checked_at")),
                timestamp(rs.getTimestamp("created_at")));
    }

    private org.springframework.jdbc.core.RowMapper<DataMigrationDtos.RowResult> rowMapper() {
        return (rs, rowNum) -> new DataMigrationDtos.RowResult(rs.getLong("id"), rs.getString("domain_code"),
                rs.getString("source_sheet"), rs.getInt("source_row_no"), rs.getString("source_key"),
                rs.getString("validation_status"), rs.getString("error_code"), rs.getString("error_message"),
                rs.getString("target_type"), rs.getLong("target_id"), rs.getInt("target_created") == 1,
                timestamp(rs.getTimestamp("applied_at")), timestamp(rs.getTimestamp("rolled_back_at")));
    }

    private BatchLock lockBatch(Long batchId) {
        List<BatchLock> rows = jdbc.query("""
                select id,entity_id,batch_no,opening_date,status,opening_voucher_id
                from data_migration_batch where id=? for update
                """, (rs, rowNum) -> new BatchLock(rs.getLong("id"), rs.getLong("entity_id"), rs.getString("batch_no"),
                rs.getDate("opening_date").toLocalDate(), rs.getString("status"), rs.getLong("opening_voucher_id")), batchId);
        if (rows.isEmpty()) throw new BusinessException("期初迁移批次不存在");
        return rows.get(0);
    }

    private void event(Long batchId, String action, String from, String to, String reason, Actor actor) {
        jdbc.update("""
                insert into data_migration_event(batch_id,action,from_status,to_status,reason,operator_id,operator_name)
                values (?,?,?,?,?,?,?)
                """, batchId, action, from, to, reason, actor.id(), actor.name());
    }

    private void assertOpenPeriod(long entityId, LocalDate date) {
        if (count("""
                select count(*) from accounting_period where entity_id=? and status='OPEN' and ? between start_date and end_date
                """, entityId, Date.valueOf(date)) != 1) throw new BusinessException("期初日期必须位于唯一开放的会计期间");
    }

    private long entityId() {
        return requiredLong("select id from accounting_entity where entity_code='DEFAULT' and status='ENABLED'");
    }

    private long accountId(long entityId, String code) {
        List<Long> ids = jdbc.queryForList("""
                select id from accounting_account where entity_id=? and account_code=? and status='ENABLED' and leaf=1
                """, Long.class, entityId, code);
        return ids.size() == 1 ? ids.get(0) : 0L;
    }

    private long bankAccountUsageCount(long bankAccountId) {
        return count("select count(*) from payment where business_bank_account_id=?", bankAccountId)
                + count("select count(*) from supplier_payment where business_bank_account_id=?", bankAccountId)
                + count("select count(*) from customer_advance_refund where business_bank_account_id=?", bankAccountId)
                + count("select count(*) from acceptance_bill_event where business_bank_account_id=?", bankAccountId)
                + count("select count(*) from treasury_cash_journal where business_bank_account_id=?", bankAccountId)
                + count("select count(*) from bank_reconciliation_session where bank_account_id=?", bankAccountId);
    }

    private Map<String, Object> bankAccountSnapshot(Map<String, Object> row) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("accountCode", String.valueOf(row.get("account_code")));
        snapshot.put("accountName", String.valueOf(row.get("account_name")));
        snapshot.put("bankName", String.valueOf(row.get("bank_name")));
        snapshot.put("bankAccountNo", String.valueOf(row.get("bank_account_no")));
        snapshot.put("currency", String.valueOf(row.get("currency")));
        snapshot.put("openingDate", String.valueOf(row.get("opening_date")));
        snapshot.put("openingBalance", String.valueOf(row.get("opening_balance")));
        snapshot.put("status", String.valueOf(row.get("status")));
        snapshot.put("disabledReason", String.valueOf(row.get("disabled_reason")));
        return snapshot;
    }

    private String normalizedProductAttributes(long seriesId, String json) {
        try {
            Map<String, Object> source = StringUtils.hasText(json) ? objectMapper.readValue(json, new TypeReference<>() {}) : Map.of();
            Map<String, Object> normalized = new LinkedHashMap<>();
            for (Map<String, Object> field : jdbc.queryForList("""
                    select field_name,coalesce(nullif(display_name,''),field_name) display_name
                    from product_series_field where product_series_id=? and status='ENABLED' order by sort_no,id
                    """, seriesId)) {
                String internal = String.valueOf(field.get("field_name"));
                String display = String.valueOf(field.get("display_name"));
                Object value = source.containsKey(internal) ? source.get(internal) : source.get(display);
                if (value != null && !String.valueOf(value).isBlank()) normalized.put(internal, value);
            }
            return json(normalized);
        } catch (Exception ex) {
            throw new BusinessException("商品规格属性无法应用");
        }
    }

    private void assertExistingPartnerRole(long partnerId, String role) {
        Integer mask = jdbc.queryForObject("select type_mask from partner where id=? and status='ENABLED'", Integer.class, partnerId);
        int expected = partnerRoleMask(role);
        if (mask == null || (mask & expected) != expected) throw new BusinessException("已有往来单位角色与迁移文件不一致");
    }

    private int partnerRoleMask(String role) {
        return switch (role.toUpperCase(Locale.ROOT)) { case "SUPPLIER" -> 2; case "BOTH" -> 3; default -> 1; };
    }

    private BigDecimal expectedInventoryQuantity(long batchId) {
        return storedRows(batchId, "INVENTORY").stream().map(row -> quantity(row.payload().get("数量*")))
                .reduce(BigDecimal.ZERO.setScale(3), BigDecimal::add);
    }

    private BigDecimal expectedInventoryAmount(long batchId) {
        return storedRows(batchId, "INVENTORY").stream()
                .map(row -> quantity(row.payload().get("数量*")).multiply(cost(row.payload().get("单位成本*"))).setScale(2, RoundingMode.HALF_UP))
                .reduce(ZERO2, BigDecimal::add);
    }

    private BigDecimal expectedPartnerOpening(long batchId, String type) {
        return storedRows(batchId, "PARTNER_OPENING").stream().filter(row -> type.equals(row.payload().get("类型*")))
                .map(row -> money(row.payload().get("金额*"))).reduce(ZERO2, BigDecimal::add);
    }

    private BigDecimal expectedPartnerOpeningTotal(long batchId) {
        return storedRows(batchId, "PARTNER_OPENING").stream().map(row -> money(row.payload().get("金额*")))
                .reduce(ZERO2, BigDecimal::add);
    }

    private BigDecimal expectedFundAmount(long batchId) {
        return decimalQuery("select coalesce(sum(opening_balance),0) from data_migration_fund_opening where batch_id=? and status='ACTIVE'", batchId);
    }

    private BigDecimal expectedFundAmountFromRows(long batchId) {
        return storedRows(batchId, "FUND_OPENING").stream().map(row -> money(row.payload().get("余额*")))
                .reduce(ZERO2, BigDecimal::add);
    }

    private BigDecimal expectedLedgerDebit(long batchId) {
        return storedRows(batchId, "GENERAL_LEDGER").stream().map(row -> money(row.payload().get("借方")))
                .reduce(ZERO2, BigDecimal::add);
    }

    private BigDecimal voucherDebitNet(long voucherId, Set<String> codes) {
        String placeholders = String.join(",", codes.stream().map(value -> "?").toList());
        List<Object> args = new ArrayList<>();
        args.add(voucherId); args.addAll(codes);
        return decimalQuery("select coalesce(sum(debit_amount-credit_amount),0) from accounting_voucher_line where voucher_id=? and account_code_snapshot in (" + placeholders + ")", args.toArray());
    }

    private BigDecimal voucherCreditNet(long voucherId, Set<String> codes) {
        return voucherDebitNet(voucherId, codes).negate().setScale(2);
    }

    private void check(List<CheckResult> checks, String code, String domain, long expected, long actual, String message) {
        checks.add(new CheckResult(code, domain, String.valueOf(expected), String.valueOf(actual), expected == actual, message));
    }

    private void checkDecimal(List<CheckResult> checks, String code, String domain,
                              BigDecimal expected, BigDecimal actual, String message) {
        BigDecimal left = money(expected); BigDecimal right = money(actual);
        checks.add(new CheckResult(code, domain, left.toPlainString(), right.toPlainString(), left.compareTo(right) == 0, message));
    }

    private String requiredValue(ValidatedRow row, String key) {
        String value = text(row, key);
        if (value.isBlank()) row.error("REQUIRED", key.replace("*", "") + "不能为空");
        return value;
    }

    private String enumValue(ValidatedRow row, String key, Set<String> values) {
        String value = requiredValue(row, key).toUpperCase(Locale.ROOT);
        if (!value.isBlank() && !values.contains(value)) row.error("ENUM_INVALID", key.replace("*", "") + "业务值无效");
        return value;
    }

    private BigDecimal positive(ValidatedRow row, String key, int scale) {
        BigDecimal value = decimalValue(row, key, scale, true);
        if (value.signum() <= 0) row.error("AMOUNT_MUST_BE_POSITIVE", key.replace("*", "") + "必须大于0");
        return value;
    }

    private BigDecimal nonNegative(ValidatedRow row, String key, int scale) {
        BigDecimal value = decimalValue(row, key, scale, false);
        if (value.signum() < 0) row.error("AMOUNT_MUST_BE_NON_NEGATIVE", key + "不能小于0");
        return value;
    }

    private BigDecimal optionalMoney(ValidatedRow row, String key, boolean nonNegative) {
        if (text(row, key).isBlank()) return ZERO2;
        BigDecimal value = decimalValue(row, key, 2, false);
        if (nonNegative && value.signum() < 0) row.error("AMOUNT_MUST_BE_NON_NEGATIVE", key + "不能小于0");
        return value;
    }

    private BigDecimal decimalValue(ValidatedRow row, String key, int scale, boolean required) {
        String raw = text(row, key).replace(",", "");
        if (raw.isBlank()) {
            if (required) row.error("REQUIRED", key.replace("*", "") + "不能为空");
            return BigDecimal.ZERO.setScale(scale);
        }
        try {
            BigDecimal value = new BigDecimal(raw);
            if (value.scale() > scale) row.error("PRECISION_EXCEEDED", key.replace("*", "") + "最多保留" + scale + "位小数");
            return value.setScale(scale, RoundingMode.HALF_UP);
        } catch (NumberFormatException ex) {
            row.error("NUMBER_INVALID", key.replace("*", "") + "不是有效数字");
            return BigDecimal.ZERO.setScale(scale);
        }
    }

    private String reference(ValidatedRow row, String key, String domain, Map<String, Map<String, ValidatedRow>> byDomain) {
        String value = requiredValue(row, key);
        if (!value.isBlank() && !byDomain.get(domain).containsKey(value)) row.error("REFERENCE_NOT_FOUND", key.replace("*", "") + "未在对应主数据工作表找到");
        return value;
    }

    private void optionalReference(ValidatedRow row, String key, String domain, Map<String, Map<String, ValidatedRow>> byDomain) {
        String value = text(row, key);
        if (!value.isBlank() && !byDomain.get(domain).containsKey(value)) row.error("REFERENCE_NOT_FOUND", key + "未在对应主数据工作表找到");
    }

    private LocalDate requiredDate(ValidatedRow row, String key) {
        String value = requiredValue(row, key);
        if (value.isBlank()) return null;
        try { return requiredDate(value); }
        catch (BusinessException ex) { row.error("DATE_INVALID", key.replace("*", "") + "必须使用 yyyy-MM-dd"); return null; }
    }

    private void optionalDate(ValidatedRow row, String key) {
        if (text(row, key).isBlank()) return;
        try { requiredDate(text(row, key)); }
        catch (BusinessException ex) { row.error("DATE_INVALID", key + "必须使用 yyyy-MM-dd"); }
    }

    private void duplicateValue(List<ValidatedRow> rows, String domain, String field, String code, String message) {
        Map<String, ValidatedRow> values = new HashMap<>();
        for (ValidatedRow row : rows.stream().filter(r -> domain.equals(r.domain())).toList()) {
            String value = text(row, field).toLowerCase(Locale.ROOT);
            if (!value.isBlank()) markDuplicate(values, value, row, code, message);
        }
    }

    private void markDuplicate(Map<String, ValidatedRow> values, String key, ValidatedRow row, String code, String message) {
        ValidatedRow prior = values.putIfAbsent(key, row);
        if (prior != null) { row.error(code, message); prior.error(code, message); }
    }

    private void compareLedger(List<ValidatedRow> rows, String code, BigDecimal expected, String errorCode, String message) {
        BigDecimal actual = ledgerDebitNet(rows, Set.of(code));
        if (actual.compareTo(expected) != 0) generalLedgerError(rows, errorCode, message + "：业务 " + expected + "，总账 " + actual);
    }

    private BigDecimal ledgerDebitNet(List<ValidatedRow> rows, Set<String> codes) {
        return rows.stream().filter(row -> "GENERAL_LEDGER".equals(row.domain()) && codes.contains(text(row, "科目编码*")))
                .map(row -> money(text(row, "借方")).subtract(money(text(row, "贷方"))))
                .reduce(ZERO2, BigDecimal::add).setScale(2);
    }

    private BigDecimal sum(List<ValidatedRow> rows, String domain, String field) {
        return rows.stream().filter(row -> domain.equals(row.domain())).map(row -> money(text(row, field)))
                .reduce(ZERO2, BigDecimal::add).setScale(2);
    }

    private BigDecimal sumByType(List<ValidatedRow> rows, String type) {
        return rows.stream().filter(row -> "PARTNER_OPENING".equals(row.domain()) && type.equals(text(row, "类型*")))
                .map(row -> money(text(row, "金额*"))).reduce(ZERO2, BigDecimal::add);
    }

    private void generalLedgerError(List<ValidatedRow> rows, String code, String message) {
        List<ValidatedRow> ledger = rows.stream().filter(row -> "GENERAL_LEDGER".equals(row.domain())).toList();
        if (ledger.isEmpty()) return;
        ledger.get(0).error(code, message);
    }

    private String text(ValidatedRow row, String key) { return row.values().getOrDefault(key, "").trim(); }
    private BigDecimal money(Object value) { return decimal(value == null ? "" : String.valueOf(value)).setScale(2, RoundingMode.HALF_UP); }
    private BigDecimal quantity(Object value) { return decimal(value == null ? "" : String.valueOf(value)).setScale(3, RoundingMode.HALF_UP); }
    private BigDecimal cost(Object value) { return decimal(value == null ? "" : String.valueOf(value)).setScale(4, RoundingMode.HALF_UP); }
    private BigDecimal decimal(String value) { try { return StringUtils.hasText(value) ? new BigDecimal(value.replace(",", "")) : BigDecimal.ZERO; } catch (Exception ex) { return BigDecimal.ZERO; } }
    private String currency(String value) { return StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : "CNY"; }
    private LocalDate dateOrNull(String value) { return StringUtils.hasText(value) ? requiredDate(value) : null; }
    private LocalDate requiredDate(String value) {
        for (String pattern : List.of("yyyy-MM-dd", "yyyy/M/d", "yyyy-M-d")) {
            try { return LocalDate.parse(value.trim(), DateTimeFormatter.ofPattern(pattern)); }
            catch (DateTimeParseException ignored) {}
        }
        throw new BusinessException("日期格式无效");
    }

    private long target(Map<String, Long> targets, String key, String name) {
        Long id = targets.get(key); if (id == null || id <= 0) throw new BusinessException(name + "来源键未映射：" + key); return id;
    }
    private long optionalTarget(Map<String, Long> targets, String key) { return StringUtils.hasText(key) ? target(targets, key, "辅助核算") : 0L; }
    private String safeFileName(String name) { String safe = name.replace('\\', '/'); safe = safe.substring(safe.lastIndexOf('/') + 1); return safe.length() > 255 ? safe.substring(safe.length() - 255) : safe; }
    private void requiredReason(DataMigrationDtos.Action action) { if (action == null || !StringUtils.hasText(action.reason()) || action.reason().trim().length() < 4) throw new BusinessException("请填写至少4个字的业务原因"); }

    private Actor actor() {
        SecurityContext.Principal principal = SecurityContext.get();
        return principal == null ? new Actor(0L, "系统") : new Actor(principal.employeeId(), principal.employeeName());
    }

    private String nextNo(String prefix) { return prefix + LocalDateTime.now().format(NO_TIME) + String.format("%03d", Math.floorMod(NO_COUNTER.incrementAndGet(), 1000)); }
    private String sha256(byte[] bytes) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); } catch (Exception ex) { throw new IllegalStateException(ex); } }
    private String json(Object value) { try { return objectMapper.writeValueAsString(value); } catch (Exception ex) { throw new BusinessException("迁移快照无法序列化"); } }
    private Map<String, String> parsePayload(String value) { try { return objectMapper.readValue(value, new TypeReference<>() {}); } catch (Exception ex) { throw new BusinessException("迁移逐行快照无法读取"); } }
    private Map<String, Object> parseSummary(String value) { try { return objectMapper.readValue(value, new TypeReference<>() {}); } catch (Exception ex) { return Map.of("raw", value); } }
    private Map<String, Object> parseNullableSummary(String value) { return StringUtils.hasText(value) ? parseSummary(value) : Map.of(); }

    private Long insert(String sql, Object... args) {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
            return statement;
        }, holder);
        if (holder.getKey() == null) throw new BusinessException("迁移数据写入失败");
        return holder.getKey().longValue();
    }

    private long count(String sql, Object... args) { Long value = jdbc.queryForObject(sql, Long.class, args); return value == null ? 0L : value; }
    private long requiredLong(String sql, Object... args) { List<Long> values = jdbc.queryForList(sql, Long.class, args); if (values.size() != 1) throw new BusinessException("迁移依赖的唯一主数据不存在或不唯一"); return values.get(0); }
    private String requiredText(String sql, Object... args) { String value = jdbc.queryForObject(sql, String.class, args); if (value == null) throw new BusinessException("迁移依赖数据不存在"); return value; }
    private BigDecimal decimalQuery(String sql, Object... args) { BigDecimal value = jdbc.queryForObject(sql, BigDecimal.class, args); return value == null ? ZERO2 : value; }
    private BigDecimal requiredDecimalForUpdate(String sql, Object... args) { BigDecimal value = jdbc.queryForObject(sql, BigDecimal.class, args); return value == null ? ZERO2 : value; }
    private Map<String, Object> requiredMap(String sql, Object... args) { List<Map<String, Object>> rows = jdbc.queryForList(sql, args); if (rows.size() != 1) throw new BusinessException("迁移关联数据不存在或不唯一"); return rows.get(0); }
    private long number(Object value) { return value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value)); }
    private LocalDateTime timestamp(Object value) { return value == null ? null : value instanceof Timestamp ts ? ts.toLocalDateTime() : LocalDateTime.parse(String.valueOf(value)); }

    private record Actor(long id, String name) {}
    private record BatchLock(long id, long entityId, String batchNo, LocalDate openingDate, String status, long openingVoucherId) {}
    private record StoredRow(long id, String sourceKey, Map<String, String> payload,
                             String targetType, long targetId, boolean targetCreated,
                             Map<String, Object> rollbackContext) {}
    private record CheckResult(String code, String domain, String expected, String actual, boolean passed, String message) {}
    private record Validation(List<ValidatedRow> rows, int validCount, int errorCount) {}

    private static final class ValidatedRow {
        private final String domain;
        private final String sheet;
        private final int rowNo;
        private final Map<String, String> values;
        private final Map<String, String> errors = new LinkedHashMap<>();
        private String sourceKey = "";

        private ValidatedRow(OpeningMigrationWorkbook.ParsedRow row) {
            domain = row.domainCode(); sheet = row.sheetName(); rowNo = row.rowNo(); values = new LinkedHashMap<>(row.values());
        }
        String domain() { return domain; }
        String sheet() { return sheet; }
        int rowNo() { return rowNo; }
        Map<String, String> values() { return values; }
        String sourceKey() { return sourceKey; }
        boolean hasError() { return !errors.isEmpty(); }
        void error(String code, String message) { errors.putIfAbsent(code + ":" + message, message); }
        String errorCodes() { return errors.keySet().stream().map(value -> value.substring(0, value.indexOf(':'))).distinct().reduce((a, b) -> a + "," + b).orElse(""); }
        String errorMessage() { return String.join("；", new LinkedHashSet<>(errors.values())); }
    }
}
