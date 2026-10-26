package com.soyia.copperv2.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.soyia.copperv2.dto.DashboardDto;
import com.soyia.copperv2.dto.FinancialCloseDtos;
import com.soyia.copperv2.dto.InboundCreateRequest;
import com.soyia.copperv2.dto.InventoryBatchDto;
import com.soyia.copperv2.dto.InventoryDtos;
import com.soyia.copperv2.dto.AlignedDtos;
import com.soyia.copperv2.dto.MasterDataDtos;
import com.soyia.copperv2.dto.OrderDtos;
import com.soyia.copperv2.dto.OutboundCreateRequest;
import com.soyia.copperv2.dto.OutboundActionRequests;
import com.soyia.copperv2.dto.PackagingDtos;
import com.soyia.copperv2.dto.PartnerDto;
import com.soyia.copperv2.dto.PartnerSummaryDto;
import com.soyia.copperv2.dto.PaymentAllocationDtos;
import com.soyia.copperv2.dto.PaymentCreateRequest;
import com.soyia.copperv2.dto.SalesOrderPrepaymentRequests;
import com.soyia.copperv2.dto.ProductDto;
import com.soyia.copperv2.dto.ProductMasterDataDtos;
import com.soyia.copperv2.dto.ProductPurchaseOptionsDto;
import com.soyia.copperv2.dto.PriceCenterDtos;
import com.soyia.copperv2.dto.PurchaseCreateRequest;
import com.soyia.copperv2.dto.ReceivableSettlementDtos;
import com.soyia.copperv2.dto.RiskExceptionDtos;
import com.soyia.copperv2.dto.SimpleCreateRequests;
import com.soyia.copperv2.dto.SupplierPaymentCreateRequest;
import com.soyia.copperv2.dto.WarehouseDto;
import com.soyia.copperv2.dto.BusinessPostingDtos;
import com.soyia.copperv2.security.BusinessDataScopeService;
import com.soyia.copperv2.security.ForbiddenException;
import com.soyia.copperv2.security.SecurityContext;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class CopperService {
    private static final DateTimeFormatter NO_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");
    private static final AtomicLong NO_COUNTER = new AtomicLong(100);

    private final JdbcTemplate jdbcTemplate;
    private final PackagingService packagingService;
    private final ObjectMapper objectMapper;
    private final BusinessDataScopeService dataScopeService;
    private final LocalFileStorageService fileStorageService;
    private final BusinessPostingService postingService;
    private final FinancialCloseService financialCloseService;
    private final InventoryService inventoryService;
    private final SalesRiskService salesRiskService;
    private final AtpService atpService;
    private final PriceCenterService priceCenterService;
    private final RiskExceptionService riskExceptionService;
    private final ProductMasterDataService productMasterDataService;

    public CopperService(JdbcTemplate jdbcTemplate, PackagingService packagingService, ObjectMapper objectMapper,
                         BusinessDataScopeService dataScopeService, LocalFileStorageService fileStorageService,
                         BusinessPostingService postingService, FinancialCloseService financialCloseService,
                         InventoryService inventoryService, SalesRiskService salesRiskService,
                         AtpService atpService, PriceCenterService priceCenterService,
                         RiskExceptionService riskExceptionService,
                         ProductMasterDataService productMasterDataService) {
        this.jdbcTemplate = jdbcTemplate;
        this.packagingService = packagingService;
        this.objectMapper = objectMapper;
        this.dataScopeService = dataScopeService;
        this.fileStorageService = fileStorageService;
        this.postingService = postingService;
        this.financialCloseService = financialCloseService;
        this.inventoryService = inventoryService;
        this.salesRiskService = salesRiskService;
        this.atpService = atpService;
        this.priceCenterService = priceCenterService;
        this.riskExceptionService = riskExceptionService;
        this.productMasterDataService = productMasterDataService;
    }

    public List<PartnerDto> partners(String type, String keyword, String status, Integer limit, Long id) {
        StringBuilder sql = new StringBuilder(partnerBaseSelect()).append(" where 1 = 1");
        List<Object> args = new ArrayList<>();

        if (StringUtils.hasText(type)) {
            int roleMask = partnerRoleMask(type);
            sql.append(" and (p.type_mask & ?) = ?");
            args.add(roleMask);
            args.add(roleMask);
            if (id == null && roleMask == 1) {
                sql.append(" and cp.status = 'ENABLED'");
            } else if (id == null && roleMask == 2) {
                sql.append(" and sp.status = 'ENABLED'");
            }
        }
        if (id != null) {
            sql.append(" and p.id = ?");
            args.add(id);
        }
        if (StringUtils.hasText(status)) {
            sql.append(" and p.status = ?");
            args.add(baseStatus(status));
        }
        if (StringUtils.hasText(keyword)) {
            String like = likeKeyword(keyword);
            String phoneDigits = keyword.replaceAll("\\D", "");
            String phoneLike = StringUtils.hasText(phoneDigits) ? likeKeyword(phoneDigits) : like;
            sql.append("""
                    and (
                      p.business_no like ? escape '!'
                      or p.name like ? escape '!'
                      or p.alias_name like ? escape '!'
                      or p.credit_code like ? escape '!'
                      or coalesce(pc.name, '') like ? escape '!'
                      or coalesce(pc.phone, '') like ? escape '!'
                      or replace(replace(replace(replace(coalesce(pc.phone, ''), ' ', ''), '-', ''), '(', ''), ')', '') like ? escape '!'
                      or coalesce(pa.detail, p.address) like ? escape '!'
                      or coalesce(pc.wechat, '') like ? escape '!'
                    )
                    """);
            args.add(like);
            args.add(like);
            args.add(like);
            args.add(like);
            args.add(like);
            args.add(like);
            args.add(phoneLike);
            args.add(like);
            args.add(like);
        }
        sql.append(" order by p.top_fixed desc, p.id desc");
        int safeLimit = safePartnerLimit(limit);
        if (safeLimit > 0) {
            sql.append(" limit ").append(safeLimit);
        }
        return jdbcTemplate.query(sql.toString(), partnerMapper(), args.toArray());
    }

    public PartnerDto partner(Long id) {
        List<PartnerDto> rows = partners(null, null, null, 1, id);
        if (rows.isEmpty()) {
            throw new BusinessException("往来单位不存在");
        }
        return rows.get(0);
    }

    public List<PartnerSummaryDto> partnerSummaries(String type, String keyword, String status) {
        StringBuilder sql = new StringBuilder(partnerSummarySelect()).append(" where 1 = 1");
        List<Object> args = new ArrayList<>();
        if (StringUtils.hasText(type)) {
            int roleMask = partnerRoleMask(type);
            sql.append(" and (p.type_mask & ?) = ?");
            args.add(roleMask);
            args.add(roleMask);
        }
        if (StringUtils.hasText(status)) {
            sql.append(" and p.status = ?");
            args.add(baseStatus(status));
        }
        if (StringUtils.hasText(keyword)) {
            String like = likeKeyword(keyword);
            String phoneDigits = keyword.replaceAll("\\D", "");
            String phoneLike = StringUtils.hasText(phoneDigits) ? likeKeyword(phoneDigits) : like;
            sql.append("""
                    and (
                      p.business_no like ? escape '!'
                      or p.name like ? escape '!'
                      or p.alias_name like ? escape '!'
                      or p.credit_code like ? escape '!'
                      or p.tags like ? escape '!'
                      or coalesce(pc.name, '') like ? escape '!'
                      or coalesce(pc.phone, '') like ? escape '!'
                      or replace(replace(replace(replace(coalesce(pc.phone, ''), ' ', ''), '-', ''), '(', ''), ')', '') like ? escape '!'
                      or coalesce(pa.detail, p.address) like ? escape '!'
                    )
                    """);
            args.add(like);
            args.add(like);
            args.add(like);
            args.add(like);
            args.add(like);
            args.add(like);
            args.add(like);
            args.add(phoneLike);
            args.add(like);
        }
        sql.append(" order by p.top_fixed desc, coalesce(ar.overdue_amount, 0) desc, (coalesce(ar.receivable_amount, 0) + coalesce(ap.payable_amount, 0)) desc, p.id desc limit 500");
        return jdbcTemplate.query(sql.toString(), partnerSummaryMapper(), args.toArray());
    }

    @Transactional
    public Long createPartner(SimpleCreateRequests.PartnerCreate req) {
        int typeMask = validPartnerRoleMask(req.typeMask() == null ? partnerRoleMask(req.type()) : req.typeMask());
        String type = typeFromMask(typeMask);
        validateRoleStatuses(typeMask, req);
        validatePartnerCreditCode(req);
        String name = req.name().trim();
        assertPartnerUnique(null, name, req.creditCode());
        String status = baseStatus(req.status());
        requirePartnerDisableReason(status, req.disabledReason());
        String businessNo = nextPartnerBusinessNo();
        Long id = insert("""
                insert into partner
                (business_no, type, type_mask, entity_type, name, alias_name, credit_code,
                 contact_person, phone, address, credit_limit, top_fixed, cooperation_start_date,
                 establishment_date, source, tags, status, disabled_reason, disabled_at, memo)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                businessNo, type, typeMask, entityType(req.entityType()), name, blank(req.aliasName()), blank(req.creditCode()),
                blank(req.contactPerson()), blank(req.phone()), blank(req.address()),
                nvl(req.creditLimit()), bool(req.topFixed()), req.cooperationStartDate() == null ? null : Date.valueOf(req.cooperationStartDate()),
                req.establishmentDate() == null ? null : Date.valueOf(req.establishmentDate()),
                blank(req.source()), blank(req.tags()), status, "DISABLED".equals(status) ? blank(req.disabledReason()) : "",
                "DISABLED".equals(status) ? Timestamp.valueOf(LocalDateTime.now()) : null, blank(req.memo()));
        syncPartnerProfiles(id, typeMask, req);
        upsertPrimaryContact(id, req);
        upsertPrimaryAddress(id, req);
        return id;
    }

    @Transactional
    public Long updatePartner(Long id, SimpleCreateRequests.PartnerCreate req) {
        requirePartner(id);
        PartnerBusinessGuard.lockMaster(jdbcTemplate, id);
        salesRiskService.assertGeneralPartnerCreditLimitUnchanged(id, req.creditLimit());
        int typeMask = validPartnerRoleMask(req.typeMask() == null ? partnerRoleMask(req.type()) : req.typeMask());
        String type = typeFromMask(typeMask);
        Integer existingTypeMask = jdbcTemplate.queryForObject(
                "select type_mask from partner where id = ?", Integer.class, id);
        validateRoleRemoval(id, existingTypeMask == null ? 0 : existingTypeMask, typeMask);
        validateRoleStatuses(typeMask, req);
        validatePartnerCreditCode(req);
        String name = req.name().trim();
        assertPartnerUnique(id, name, req.creditCode());
        String status = baseStatus(req.status());
        requirePartnerDisableReason(status, req.disabledReason());
        updateRequired("""
                update partner
                set type = ?, type_mask = ?, entity_type = ?, name = ?, alias_name = ?, credit_code = ?,
                    contact_person = ?, phone = ?, address = ?, credit_limit = ?,
                    top_fixed = ?, cooperation_start_date = ?, establishment_date = ?, source = ?, tags = ?,
                    memo = ?, status = ?, disabled_reason = ?,
                    disabled_at = case when ? = 'DISABLED' then coalesce(disabled_at, current_timestamp) else null end
                where id = ?
                """, "往来单位不存在",
                type, typeMask, entityType(req.entityType()), name, blank(req.aliasName()), blank(req.creditCode()),
                blank(req.contactPerson()), blank(req.phone()), blank(req.address()),
                nvl(req.creditLimit()), bool(req.topFixed()), req.cooperationStartDate() == null ? null : Date.valueOf(req.cooperationStartDate()),
                req.establishmentDate() == null ? null : Date.valueOf(req.establishmentDate()),
                blank(req.source()), blank(req.tags()), blank(req.memo()), status,
                "DISABLED".equals(status) ? blank(req.disabledReason()) : "", status, id);
        syncPartnerProfiles(id, typeMask, req);
        upsertPrimaryContact(id, req);
        upsertPrimaryAddress(id, req);
        return id;
    }

    public List<AlignedDtos.ContactRow> contacts(Long partnerId) {
        return jdbcTemplate.query("""
                select * from partner_contact
                where partner_id = ?
                order by first_choice desc, status = 'ENABLED' desc, id desc
                """, contactMapper(), partnerId);
    }

    @Transactional
    public Long createContact(AlignedDtos.ContactCreate req) {
        requirePartner(req.partnerId());
        if (Boolean.TRUE.equals(req.firstChoice())) {
            clearFirstChoiceContact(req.partnerId());
        }
        Long id = insert("""
                insert into partner_contact
                (partner_id, name, job_title, department, gender, phone, wechat, email,
                 preferred_method, first_choice, birthday, memo, status)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, req.partnerId(), req.name(), blank(req.jobTitle()), blank(req.department()),
                req.gender() == null ? 0 : req.gender(), blank(req.phone()), blank(req.wechat()), blank(req.email()),
                preferredContactMethod(req.preferredMethod()), bool(req.firstChoice()),
                req.birthday() == null ? null : Date.valueOf(req.birthday()), blank(req.memo()), baseStatus(req.status()));
        syncPartnerPrimaryContactFromContacts(req.partnerId());
        return id;
    }

    @Transactional
    public Long updateContact(Long id, AlignedDtos.ContactCreate req) {
        requirePartner(req.partnerId());
        if (Boolean.TRUE.equals(req.firstChoice())) {
            clearFirstChoiceContact(req.partnerId());
        }
        updateRequired("""
                update partner_contact
                set partner_id = ?, name = ?, job_title = ?, department = ?, gender = ?, phone = ?,
                    wechat = ?, email = ?, preferred_method = ?, first_choice = ?, birthday = ?, memo = ?, status = ?
                where id = ?
                """, "联系人不存在",
                req.partnerId(), req.name(), blank(req.jobTitle()), blank(req.department()),
                req.gender() == null ? 0 : req.gender(), blank(req.phone()), blank(req.wechat()), blank(req.email()),
                preferredContactMethod(req.preferredMethod()), bool(req.firstChoice()),
                req.birthday() == null ? null : Date.valueOf(req.birthday()), blank(req.memo()), baseStatus(req.status()), id);
        syncPartnerPrimaryContactFromContacts(req.partnerId());
        return id;
    }

    public List<AlignedDtos.AddressRow> addresses(Long partnerId) {
        return jdbcTemplate.query("""
                select * from partner_address
                where partner_id = ?
                order by first_choice desc, id desc
                """, addressMapper(), partnerId);
    }

    @Transactional
    public Long createAddress(AlignedDtos.AddressCreate req) {
        requirePartner(req.partnerId());
        String addressType = addressType(req.addressType());
        if (Boolean.TRUE.equals(req.firstChoice())) {
            clearFirstChoiceAddress(req.partnerId(), addressType);
        }
        Long id = insert("""
                insert into partner_address
                (partner_id, name, address_type, province_code, city_code, district_code, detail, contact_person, phone,
                 first_choice, memo, status)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, req.partnerId(), blank(req.name()), addressType, blank(req.provinceCode()), blank(req.cityCode()),
                blank(req.districtCode()), blank(req.detail()), blank(req.contactPerson()), blank(req.phone()),
                bool(req.firstChoice()), blank(req.memo()), baseStatus(req.status()));
        if (Boolean.TRUE.equals(req.firstChoice())) {
            syncPartnerPrimaryAddress(req.partnerId(), req.detail());
        }
        return id;
    }

    @Transactional
    public Long updateAddress(Long id, AlignedDtos.AddressCreate req) {
        requirePartner(req.partnerId());
        String addressType = addressType(req.addressType());
        if (Boolean.TRUE.equals(req.firstChoice())) {
            clearFirstChoiceAddress(req.partnerId(), addressType);
        }
        updateRequired("""
                update partner_address
                set partner_id = ?, name = ?, address_type = ?, province_code = ?, city_code = ?, district_code = ?,
                    detail = ?, contact_person = ?, phone = ?, first_choice = ?, memo = ?, status = ?
                where id = ?
                """, "收货地址不存在",
                req.partnerId(), blank(req.name()), addressType, blank(req.provinceCode()), blank(req.cityCode()),
                blank(req.districtCode()), blank(req.detail()), blank(req.contactPerson()), blank(req.phone()),
                bool(req.firstChoice()), blank(req.memo()), baseStatus(req.status()), id);
        if (Boolean.TRUE.equals(req.firstChoice())) {
            syncPartnerPrimaryAddress(req.partnerId(), req.detail());
        }
        return id;
    }

    public List<AlignedDtos.BankAccountRow> bankAccounts(Long partnerId) {
        return jdbcTemplate.query("""
                select * from partner_bank_account
                where partner_id = ?
                order by first_choice desc, status = 'ENABLED' desc, id desc
                """, bankAccountMapper(), partnerId);
    }

    @Transactional
    public Long createBankAccount(AlignedDtos.BankAccountCreate req) {
        requirePartner(req.partnerId());
        if (Boolean.TRUE.equals(req.firstChoice())) {
            clearFirstChoiceBankAccount(req.partnerId());
        }
        return insert("""
                insert into partner_bank_account
                (partner_id, account_name, bank_name, bank_account, first_choice, status, memo)
                values (?, ?, ?, ?, ?, ?, ?)
                """, req.partnerId(), req.accountName(), blank(req.bankName()), req.bankAccount(),
                bool(req.firstChoice()), baseStatus(req.status()), blank(req.memo()));
    }

    @Transactional
    public Long updateBankAccount(Long id, AlignedDtos.BankAccountCreate req) {
        requirePartner(req.partnerId());
        if (Boolean.TRUE.equals(req.firstChoice())) {
            clearFirstChoiceBankAccount(req.partnerId());
        }
        updateRequired("""
                update partner_bank_account
                set partner_id = ?, account_name = ?, bank_name = ?, bank_account = ?,
                    first_choice = ?, status = ?, memo = ?
                where id = ?
                """, "银行账户不存在", req.partnerId(), req.accountName(), blank(req.bankName()), req.bankAccount(),
                bool(req.firstChoice()), baseStatus(req.status()), blank(req.memo()), id);
        return id;
    }

    public List<AlignedDtos.FollowupRow> followups(Long partnerId) {
        return jdbcTemplate.query("""
                select f.*, c.name contact_name, c.phone contact_phone
                from partner_followup f
                left join partner_contact c on c.id = f.contact_id
                where f.partner_id = ?
                order by f.occurred_at desc, f.id desc
                """, followupMapper(), partnerId);
    }

    public Long createFollowup(AlignedDtos.FollowupCreate req) {
        requirePartner(req.partnerId());
        if (req.contactId() != null && req.contactId() > 0) {
            requireContactForPartner(req.partnerId(), req.contactId());
        }
        String status = followupStatus(req.status());
        return insert("""
                insert into partner_followup
                (partner_id, contact_id, followup_type, method_type, occurred_at, next_follow_date, status, completed_at, memo)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, req.partnerId(), req.contactId() == null ? null : req.contactId(),
                followupType(req.followupType()), blank(req.methodType()),
                Timestamp.valueOf(req.occurredAt()), req.nextFollowDate() == null ? null : Date.valueOf(req.nextFollowDate()),
                status, "COMPLETED".equals(status) ? Timestamp.valueOf(LocalDateTime.now()) : null, blank(req.memo()));
    }

    public Long updateFollowup(Long id, AlignedDtos.FollowupCreate req) {
        requirePartner(req.partnerId());
        if (req.contactId() != null && req.contactId() > 0) {
            requireContactForPartner(req.partnerId(), req.contactId());
        }
        String status = followupStatus(req.status());
        updateRequired("""
                update partner_followup
                set partner_id = ?, contact_id = ?, followup_type = ?, method_type = ?,
                    occurred_at = ?, next_follow_date = ?, status = ?,
                    completed_at = case when ? = 'COMPLETED' then coalesce(completed_at, current_timestamp) else null end,
                    memo = ?
                where id = ?
                """, "沟通记录不存在",
                req.partnerId(), req.contactId() == null ? null : req.contactId(),
                followupType(req.followupType()), blank(req.methodType()),
                Timestamp.valueOf(req.occurredAt()), req.nextFollowDate() == null ? null : Date.valueOf(req.nextFollowDate()),
                status, status, blank(req.memo()), id);
        return id;
    }

    public List<AlignedDtos.SpecialDateRow> specialDates(Long partnerId) {
        return jdbcTemplate.query("""
                select d.*, c.name contact_name
                from partner_special_date d
                left join partner_contact c on c.id = d.contact_id
                where d.partner_id = ?
                order by d.special_date asc, d.id desc
                """, specialDateMapper(), partnerId);
    }

    public Long createSpecialDate(AlignedDtos.SpecialDateCreate req) {
        requirePartner(req.partnerId());
        if (req.contactId() != null && req.contactId() > 0) {
            requireContactForPartner(req.partnerId(), req.contactId());
        }
        return insert("""
                insert into partner_special_date
                (partner_id, contact_id, date_type, title, special_date, repeat_yearly, remind_days_before, memo, status)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, req.partnerId(), req.contactId() == null ? null : req.contactId(),
                specialDateType(req.dateType()), req.title(), Date.valueOf(req.specialDate()),
                bool(req.repeatYearly() == null || req.repeatYearly()), req.remindDaysBefore() == null ? 7 : req.remindDaysBefore(),
                blank(req.memo()), baseStatus(req.status()));
    }

    public Long updateSpecialDate(Long id, AlignedDtos.SpecialDateCreate req) {
        requirePartner(req.partnerId());
        if (req.contactId() != null && req.contactId() > 0) {
            requireContactForPartner(req.partnerId(), req.contactId());
        }
        updateRequired("""
                update partner_special_date
                set partner_id = ?, contact_id = ?, date_type = ?, title = ?, special_date = ?,
                    repeat_yearly = ?, remind_days_before = ?, memo = ?, status = ?
                where id = ?
                """, "特殊日期不存在",
                req.partnerId(), req.contactId() == null ? null : req.contactId(),
                specialDateType(req.dateType()), req.title(), Date.valueOf(req.specialDate()),
                bool(req.repeatYearly() == null || req.repeatYearly()), req.remindDaysBefore() == null ? 7 : req.remindDaysBefore(),
                blank(req.memo()), baseStatus(req.status()), id);
        return id;
    }

    public List<ProductDto> products() {
        List<ProductDto> products = jdbcTemplate.query("""
                select p.*, s.name product_series_name, c.name category_name
                from product p
                left join product_series s on s.id = p.product_series_id
                left join product_category c on c.id = p.category_id
                order by p.id desc
                """, productMapper());
        products = dataScopeService.filterMasterList("PRODUCT", products, "id");
        return attachProductFieldValues(products);
    }

    @Transactional
    public Long createProduct(SimpleCreateRequests.ProductCreate req) {
        dataScopeService.assertMasterCreationAllowed("PRODUCT");
        Long productSeriesId = req.productSeriesId();
        Long categoryId = requiredProductSeriesCategory(productSeriesId, false);
        List<ProductFieldChange> fieldChanges = normalizeProductFieldChanges(req, null);
        validateProductRequiredFields(productSeriesId, null, fieldChanges);
        String attributeJson = mergeProductAttributeJson(req.attributeJson(), fieldChanges);
        String grade = normalizeLegacyProductReference("GRADE", req.grade(), true);
        String temper = normalizeLegacyProductReference("TEMPER", req.temper(), true);
        String standard = normalizeLegacyProductReference("STANDARD", req.standard(), true);
        String systemProductCode = StringUtils.hasText(req.sku()) ? req.sku().trim() : nextNo("P");
        Long id = insert("""
                insert into product
                (category_id, product_series_id, spec_preset_id, sku, name, grade, temper, standard, spec,
                 attribute_json, barcode, original_number, unit, default_price, status,identity_status)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,?)
                """,
                categoryId, productSeriesId, req.specPresetId() == null ? 0 : req.specPresetId(),
                systemProductCode, req.name(), blank(grade), blank(temper), blank(standard),
                blank(req.spec()), attributeJson,
                blank(req.barcode()), blank(req.originalNumber()),
                StringUtils.hasText(req.unit()) ? req.unit() : "kg", nvl(req.defaultPrice()), baseStatus(req.status()),
                pendingIdentity(req.identityStatus()) ? "PENDING_REVIEW" : "PUBLISHED");
        mergeProductFieldValues(id, fieldChanges);
        refreshProductDisplaySpec(id);
        refreshProductSpecSignature(id, productSeriesId);
        productMasterDataService.finalizeProductIdentityForProductWrite(
                id, req.identityStatus(), true);
        packagingService.initializeProductProfile(id, "商品建档初始化系列计量包装版本");
        return id;
    }

    @Transactional
    public Long updateProduct(Long id, SimpleCreateRequests.ProductCreate req) {
        dataScopeService.assertMasterAllowed("PRODUCT", id);
        ExistingProductMaster current = existingProductMaster(id);
        Long productSeriesId = req.productSeriesId();
        boolean publishedIdentity = StringUtils.hasText(current.identitySignature())
                || !"PENDING_REVIEW".equals(current.identityStatus());
        String requestedProductCode = StringUtils.hasText(req.sku()) ? req.sku().trim() : current.sku();
        String requestedUnit = StringUtils.hasText(req.unit()) ? req.unit() : "kg";
        boolean seriesChanged = !Objects.equals(current.productSeriesId(), productSeriesId);
        if (!seriesChanged && !Objects.equals(current.unit(), requestedUnit)) {
            throw new BusinessException(
                    "主库存单位由版本化计量包装规则管理，不能在商品资料中直接修改");
        }
        if (publishedIdentity) {
            if (pendingIdentity(req.identityStatus())) {
                throw new BusinessException("已发布商品不能退回待复核后绕过身份约束；规格变化请新建SKU");
            }
            if (seriesChanged) {
                throw new BusinessException("已发布商品不能原地更换产品系列；请停用旧商品并新建SKU");
            }
            if (!Objects.equals(current.sku(), requestedProductCode)) {
                throw new BusinessException("已发布商品不能原地修改商品编码；请停用旧商品并新建SKU");
            }
        }
        Long categoryId = requiredProductSeriesCategory(
                productSeriesId, Objects.equals(current.productSeriesId(), productSeriesId));
        List<ProductFieldChange> fieldChanges = normalizeProductFieldChanges(req, id);
        validateProductRequiredFields(productSeriesId, current, fieldChanges);
        String attributeJson = mergeProductAttributeJson(current.attributeJson(), fieldChanges);
        String grade = normalizeLegacyProductReference("GRADE", req.grade(), true);
        String temper = normalizeLegacyProductReference("TEMPER", req.temper(), true);
        String standard = normalizeLegacyProductReference("STANDARD", req.standard(), true);
        updateRequired("""
                update product
                set category_id = ?, product_series_id = ?, spec_preset_id = ?, sku = ?, name = ?,
                    spec_signature = null, grade = coalesce(?, grade), temper = coalesce(?, temper),
                    standard = coalesce(?, standard), spec = coalesce(?, spec), attribute_json = ?,
                    barcode = ?, original_number = ?, unit = ?, default_price = ?, status = ?
                where id = ?
                """, "商品不存在",
                categoryId, productSeriesId, req.specPresetId() == null ? 0 : req.specPresetId(),
                requestedProductCode, req.name(), grade, temper, standard,
                req.spec(), attributeJson,
                blank(req.barcode()), blank(req.originalNumber()),
                StringUtils.hasText(req.unit()) ? req.unit() : "kg", nvl(req.defaultPrice()), baseStatus(req.status()), id);
        mergeProductFieldValues(id, fieldChanges);
        refreshProductDisplaySpec(id);
        refreshProductSpecSignature(id, productSeriesId);
        String resultingIdentitySignature =
                productMasterDataService.finalizeProductIdentityForProductWrite(
                        id, req.identityStatus(),
                        !Objects.equals(current.productSeriesId(), productSeriesId));
        if (publishedIdentity && !Objects.equals(current.identitySignature(), resultingIdentitySignature)) {
            throw new BusinessException("已发布商品的互换身份不可原地修改；请停用旧商品并新建SKU");
        }
        if (seriesChanged) {
            packagingService.rebaseProductProfileForSeriesChange(
                    id, "待复核商品更换系列，重发计量包装版本");
        }
        return id;
    }

    public ProductPurchaseOptionsDto productPurchaseOptions(Long productId, Long supplierId, String priceUnit,
                                                            BigDecimal quantity, LocalDate businessDate) {
        return productOrderOptions(productId, supplierId, "SUPPLIER", priceUnit, quantity, businessDate);
    }

    public ProductPurchaseOptionsDto productPurchaseOptions(Long productId, Long supplierId, String priceUnit) {
        return productPurchaseOptions(productId, supplierId, priceUnit, BigDecimal.ONE, LocalDate.now());
    }

    public ProductPurchaseOptionsDto productUnitOptions(Long productId, Long partnerId, String partnerType, String priceUnit,
                                                        BigDecimal quantity, LocalDate businessDate) {
        return productOrderOptions(productId, partnerId, partnerType, priceUnit, quantity, businessDate);
    }

    public ProductPurchaseOptionsDto productUnitOptions(Long productId, Long partnerId, String partnerType, String priceUnit) {
        return productUnitOptions(productId, partnerId, partnerType, priceUnit, BigDecimal.ONE, LocalDate.now());
    }

    private ProductPurchaseOptionsDto productOrderOptions(Long productId, Long partnerId, String partnerType, String priceUnit,
                                                          BigDecimal quantity, LocalDate businessDate) {
        String role = StringUtils.hasText(partnerType) ? partnerType.trim().toUpperCase() : "";
        if ("SUPPLIER".equals(role)) {
            requireProductCostViewPermission();
        }
        ProductPurchaseBase product = productPurchaseBase(productId);
        if (partnerId != null && partnerId > 0 && ("SUPPLIER".equals(role) || "CUSTOMER".equals(role))) {
            requirePartnerRole(partnerId, role);
        }

        List<ProductPurchaseOptionsDto.UnitOption> priceUnits = productUnitOptions(true, product.productSeriesId(),
                product.priceUnitCategoryId(), product.unit());
        List<ProductPurchaseOptionsDto.UnitOption> packageUnits = new ArrayList<>(
                packagingService.unitOptions(productId, "SUPPLIER".equals(role) ? "PURCHASE" : "SALES"));
        LocalDate effectiveDate = businessDate == null ? LocalDate.now() : businessDate;
        ProductPurchaseOptionsDto.SupplierOfferContext supplierOffer = null;
        if ("SUPPLIER".equals(role) && partnerId != null && partnerId > 0) {
            mergeSupplierUsedUnits(priceUnits, partnerId, product.id(), true);
            mergeCurrentSupplierOfferUnits(
                    priceUnits, packageUnits, partnerId, product.id(), effectiveDate);
            supplierOffer = currentSupplierOfferContext(
                    product.id(), partnerId,
                    StringUtils.hasText(priceUnit) ? priceUnit.trim() : product.unit(),
                    effectiveDate);
            if (supplierOffer != null) {
                addControlledUnitOption(priceUnits, supplierOffer.priceUnit(), true);
                addControlledUnitOption(packageUnits, supplierOffer.purchaseUnit(), true);
                if (StringUtils.hasText(supplierOffer.packageUnit())) {
                    addControlledUnitOption(packageUnits, supplierOffer.packageUnit(), true);
                }
            }
        }
        String defaultPriceUnit = supplierOffer == null
                ? product.unit() : supplierOffer.priceUnit();
        String resolvedPriceUnit = availableUnitName(priceUnits, priceUnit, defaultPriceUnit);
        if (supplierOffer != null && StringUtils.hasText(priceUnit)
                && !sameText(supplierOffer.priceUnit(), resolvedPriceUnit)) {
            supplierOffer = currentSupplierOfferContext(
                    product.id(), partnerId, resolvedPriceUnit, effectiveDate);
        }

        return new ProductPurchaseOptionsDto(
                product.id(),
                product.unit(),
                priceUnits,
                packageUnits,
                orderPriceSuggestion(
                        product, partnerId, role, resolvedPriceUnit, quantity, effectiveDate,
                        supplierOffer),
                supplierOffer
        );
    }

    public List<AlignedDtos.ProductSeriesRow> productSeries() {
        return jdbcTemplate.query("""
                select s.*, c.name category_name
                from product_series s
                left join product_category c on c.id = s.category_id
                order by s.id desc
                """, productSeriesMapper());
    }

    @Transactional
    public Long createProductSeries(AlignedDtos.ProductSeriesCreate req) {
        requireUniqueProductSeriesCode(req.code(), null);
        Long id = insert("""
                insert into product_series (category_id, code, name, alias_names, package_unit_category_id, price_unit_category_id, status)
                values (?, ?, ?, ?, ?, ?, ?)
                """, req.categoryId() == null ? 0 : req.categoryId(), req.code().trim(), req.name(), blank(req.aliasNames()),
                req.packageUnitCategoryId() == null ? 0 : req.packageUnitCategoryId(),
                req.priceUnitCategoryId() == null ? 0 : req.priceUnitCategoryId(), baseStatus(req.status()));
        productMasterDataService.initializeIdentitySchemaForSeries(id);
        return id;
    }

    public Long updateProductSeries(Long id, AlignedDtos.ProductSeriesCreate req) {
        List<String> currentCodes = jdbcTemplate.query(
                "select code from product_series where id = ?",
                (rs, rowNum) -> rs.getString("code"), id);
        if (currentCodes.isEmpty()) {
            throw new BusinessException("产品系列不存在");
        }
        String currentCode = currentCodes.get(0);
        if (StringUtils.hasText(currentCode)
                && !currentCode.equals(req.code().trim())
                && scalarLong("select count(*) from product where product_series_id = ?", id) > 0) {
            throw new BusinessException("系列编码已有商品使用，不能原地修改；请保留原编码");
        }
        requireUniqueProductSeriesCode(req.code(), id);
        updateRequired("""
                update product_series
                set category_id = ?, code = ?, name = ?, alias_names = ?, package_unit_category_id = ?,
                    price_unit_category_id = ?, status = ?
                where id = ?
                """, "产品系列不存在",
                req.categoryId() == null ? 0 : req.categoryId(), req.code().trim(), req.name(), blank(req.aliasNames()),
                req.packageUnitCategoryId() == null ? 0 : req.packageUnitCategoryId(),
                req.priceUnitCategoryId() == null ? 0 : req.priceUnitCategoryId(), baseStatus(req.status()), id);
        return id;
    }

    private void requireUniqueProductSeriesCode(String code, Long excludedId) {
        Integer count = excludedId == null
                ? jdbcTemplate.queryForObject(
                        "select count(*) from product_series where code = ?",
                        Integer.class, code.trim())
                : jdbcTemplate.queryForObject(
                        "select count(*) from product_series where code = ? and id <> ?",
                        Integer.class, code.trim(), excludedId);
        if (count != null && count > 0) {
            throw new BusinessException("系列编码已存在，请更换后再保存");
        }
    }

    public List<MasterDataDtos.ProductCategoryRow> productCategories() {
        return jdbcTemplate.query("select * from product_category order by sort_no, id", productCategoryMapper());
    }

    public Long createProductCategory(MasterDataDtos.ProductCategoryCreate req) {
        return insert("""
                insert into product_category (parent_id, name, sort_no, status)
                values (?, ?, ?, ?)
                """, req.parentId() == null ? 0 : req.parentId(), req.name(),
                req.sortNo() == null ? 0 : req.sortNo(), baseStatus(req.status()));
    }

    public Long updateProductCategory(Long id, MasterDataDtos.ProductCategoryCreate req) {
        updateRequired("""
                update product_category
                set parent_id = ?, name = ?, sort_no = ?, status = ?
                where id = ?
                """, "产品分类不存在",
                req.parentId() == null ? 0 : req.parentId(), req.name(),
                req.sortNo() == null ? 0 : req.sortNo(), baseStatus(req.status()), id);
        return id;
    }

    public List<MasterDataDtos.ProductStandardRow> productStandards() {
        return productMasterDataService.referenceValuesForProductRead("STANDARD", null, null).stream()
                .map(item -> new MasterDataDtos.ProductStandardRow(
                        item.id(), item.code(), item.name(), item.englishName(),
                        "DISABLED".equals(item.status()) ? "废止" : "现行",
                        "", "", null, item.effectiveFrom(), item.effectiveTo(),
                        "", "", item.issuingBody(), item.edition(), "", item.status()
                )).toList();
    }

    public Long createProductStandard(MasterDataDtos.ProductStandardCreate req) {
        throw new BusinessException(
                "旧执行标准维护入口已退役；请使用统一规范主数据“/product-reference-values”维护STANDARD");
    }

    public Long updateProductStandard(Long id, MasterDataDtos.ProductStandardCreate req) {
        throw new BusinessException(
                "旧执行标准维护入口已退役；请使用统一规范主数据“/product-reference-values”维护STANDARD");
    }

    public List<MasterDataDtos.ProductTemperRow> productTempers() {
        return productMasterDataService.referenceValuesForProductRead("TEMPER", null, null).stream()
                .map(item -> new MasterDataDtos.ProductTemperRow(
                        item.id(), item.name(), item.code(), item.englishName(), item.status()
                )).toList();
    }

    public Long createProductTemper(MasterDataDtos.ProductTemperCreate req) {
        throw new BusinessException(
                "旧材料状态维护入口已退役；请使用统一规范主数据“/product-reference-values”维护TEMPER");
    }

    public Long updateProductTemper(Long id, MasterDataDtos.ProductTemperCreate req) {
        throw new BusinessException(
                "旧材料状态维护入口已退役；请使用统一规范主数据“/product-reference-values”维护TEMPER");
    }

    public List<MasterDataDtos.CustomFieldRow> customFields() {
        return jdbcTemplate.query("""
                select cf.*,
                       (select count(distinct sf.product_series_id)
                        from product_series_field sf
                        where sf.field_id = cf.id) series_count,
                       (select count(distinct pfv.product_id)
                        from product_field_value pfv
                        join product_series_field sf on sf.id = pfv.series_field_id
                        where sf.field_id = cf.id) product_count,
                       (select count(distinct pv.preset_id)
                        from product_spec_preset_value pv
                        join product_series_field sf on sf.id = pv.series_field_id
                        where sf.field_id = cf.id) preset_count
                from product_custom_field cf
                order by cf.id
                """, customFieldMapper());
    }

    public List<MasterDataDtos.CustomFieldUsageRow> customFieldUsages(Long fieldId) {
        customField(fieldId);
        return jdbcTemplate.query("""
                select sf.id series_field_id, sf.product_series_id, ps.name product_series_name,
                       ps.category_id, coalesce(pc.name, '') category_name, sf.display_name,
                       coalesce(sf.unit_id, 0) unit_id, sf.unit_name, sf.required, sf.sku_dimension,
                       sf.include_in_spec, sf.searchable, sf.order_visible, sf.status,
                       (select count(distinct pfv.product_id)
                        from product_field_value pfv where pfv.series_field_id = sf.id) product_count,
                       (select count(distinct pv.preset_id)
                        from product_spec_preset_value pv where pv.series_field_id = sf.id) preset_count
                from product_series_field sf
                join product_series ps on ps.id = sf.product_series_id
                left join product_category pc on pc.id = ps.category_id
                where sf.field_id = ?
                order by pc.sort_no, pc.id, ps.id
                """, (rs, rowNum) -> new MasterDataDtos.CustomFieldUsageRow(
                rs.getLong("series_field_id"), rs.getLong("product_series_id"),
                rs.getString("product_series_name"), rs.getLong("category_id"), rs.getString("category_name"),
                rs.getString("display_name"), rs.getLong("unit_id"), rs.getString("unit_name"),
                rs.getInt("required") == 1, rs.getInt("sku_dimension") == 1,
                rs.getInt("include_in_spec") == 1, rs.getInt("searchable") == 1,
                rs.getInt("order_visible") == 1, rs.getString("status"),
                rs.getLong("product_count"), rs.getLong("preset_count")
        ), fieldId);
    }

    public Long createCustomField(MasterDataDtos.CustomFieldCreate req) {
        ensureCustomFieldNameAvailable(req.name(), null);
        String fieldType = customFieldType(req.fieldType());
        String valueType = customFieldValueType(fieldType, req.valueType());
        String optionJson = customFieldOptions(fieldType, req.optionJson());
        return insert("""
                insert into product_custom_field
                (name, field_type, value_type, unit_category_id, option_json, status)
                values (?, ?, ?, ?, ?, ?)
                """, req.name().trim(), fieldType, valueType, req.unitCategoryId(), optionJson, baseStatus(req.status()));
    }

    @Transactional
    public Long updateCustomField(Long id, MasterDataDtos.CustomFieldCreate req) {
        jdbcTemplate.queryForList("select id from product_custom_field where id=? for update", id);
        MasterDataDtos.CustomFieldRow current = customField(id);
        ensureCustomFieldNameAvailable(req.name(), id);
        String fieldType = customFieldType(req.fieldType());
        String valueType = customFieldValueType(fieldType, req.valueType());
        if (current.seriesCount() > 0 && (!Objects.equals(current.name(), req.name().trim())
                || !Objects.equals(current.fieldType(), fieldType)
                || !Objects.equals(current.valueType(), valueType))) {
            throw new BusinessException("字段已被产品系列使用，不能修改字段名称或类型；可以调整选项、默认单位或停用状态");
        }
        String optionJson = customFieldOptions(fieldType, req.optionJson());
        Integer identityUses = jdbcTemplate.queryForObject("""
                select count(*)
                from product_identity_rule r
                join product_series_field sf on sf.id=r.series_field_id
                where r.source_type='SERIES_FIELD' and sf.field_id=?
                """, Integer.class, id);
        if (identityUses != null && identityUses > 0
                && (!Objects.equals(current.unitCategoryId(), req.unitCategoryId())
                    || !Objects.equals(current.optionJson(), optionJson)
                    || !Objects.equals(current.status(), baseStatus(req.status())))) {
            throw new BusinessException(
                    "规格字段已用于区分已有商品，单位分类、选项和状态不可原地修改；"
                            + "请新增字段，并在对应系列中重新配置“用于区分商品”");
        }
        updateRequired("""
                update product_custom_field
                set name = ?, field_type = ?, value_type = ?, unit_category_id = ?, option_json = ?, status = ?
                where id = ?
                """, "规格字段不存在", req.name().trim(), fieldType, valueType, req.unitCategoryId(), optionJson,
                baseStatus(req.status()), id);
        return id;
    }

    public List<MasterDataDtos.SeriesFieldRow> seriesFields(Long seriesId) {
        return jdbcTemplate.query("""
                select sf.id, sf.product_series_id, sf.field_id, sf.field_name, sf.display_name,
                       sf.unit_category_id, sf.unit_id, sf.unit_name, sf.required,
                       sf.sku_dimension, sf.include_in_spec, sf.searchable, sf.order_visible,
                       sf.min_value, sf.max_value, sf.decimal_scale, sf.sort_no, sf.status,
                       cf.field_type, cf.value_type,
                       coalesce(nullif(sf.option_json, ''), cf.option_json) option_json,
                       (select count(distinct pfv.product_id)
                        from product_field_value pfv where pfv.series_field_id = sf.id) product_count,
                       (select count(distinct pv.preset_id)
                        from product_spec_preset_value pv where pv.series_field_id = sf.id) preset_count
                from product_series_field sf
                join product_custom_field cf on cf.id = sf.field_id
                where sf.product_series_id = ?
                order by sf.sort_no, sf.id
                """, seriesFieldMapper(), seriesId);
    }

    @Transactional
    public Long createSeriesField(MasterDataDtos.SeriesFieldCreate req) {
        ensureSeriesFieldAvailable(req.productSeriesId(), req.fieldId(), null);
        MasterDataDtos.CustomFieldRow field = customField(req.fieldId());
        if (!"ENABLED".equals(field.status())) {
            throw new BusinessException("规格字段已停用，不能新增到产品系列");
        }
        String optionJson = seriesFieldOptions(field, req.optionJson());
        SeriesFieldConfig config = normalizeSeriesFieldConfig(field, req);
        Long id = insert("""
                insert into product_series_field
                (product_series_id, field_id, field_name, display_name, option_json,
                 unit_category_id, unit_id, unit_name, required, sku_dimension, include_in_spec,
                 searchable, order_visible, min_value, max_value, decimal_scale, sort_no, status)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, req.productSeriesId(), req.fieldId(), field.name(),
                StringUtils.hasText(req.displayName()) ? req.displayName() : field.name(),
                optionJson, config.unitCategoryId(), config.unitId(), config.unitName(), bool(req.required()),
                bool(config.skuDimension()), bool(config.includeInSpec()),
                bool(config.searchable()), bool(config.orderVisible()), config.minValue(), config.maxValue(),
                config.decimalScale(),
                req.sortNo() == null ? 0 : req.sortNo(), baseStatus(req.status()));
        if (Boolean.TRUE.equals(config.skuDimension())) {
            productMasterDataService.syncIdentitySchemaFromSeriesFields(
                    req.productSeriesId(), "规格字段配置自动更新商品唯一性规则");
        }
        refreshSeriesProductSpecSignatures(req.productSeriesId());
        return id;
    }

    @Transactional
    public Long updateSeriesField(Long id, MasterDataDtos.SeriesFieldCreate req) {
        jdbcTemplate.queryForList("select id from product_series_field where id=? for update", id);
        MasterDataDtos.SeriesFieldRow current = seriesField(id);
        if (seriesFieldUsedByPreset(id)
                && (!Objects.equals(current.productSeriesId(), req.productSeriesId())
                || !Objects.equals(current.fieldId(), req.fieldId()))) {
            throw new BusinessException("字段已有商品或规格预设历史数据，不能更换产品系列或规格字段；可以停用后保留历史");
        }
        ensureSeriesFieldAvailable(req.productSeriesId(), req.fieldId(), id);
        MasterDataDtos.CustomFieldRow field = customField(req.fieldId());
        String optionJson = seriesFieldOptions(field, req.optionJson());
        SeriesFieldConfig config = normalizeSeriesFieldConfig(field, req);
        if (seriesFieldUsedByIdentity(id) && seriesHasProducts(current.productSeriesId())) {
            boolean structuralChange =
                    !Objects.equals(current.productSeriesId(), req.productSeriesId())
                    || !Objects.equals(current.fieldId(), req.fieldId())
                    || !Objects.equals(current.optionJson(), optionJson)
                    || !Objects.equals(current.unitCategoryId(), config.unitCategoryId())
                    || !Objects.equals(current.unitId(), config.unitId())
                    || !Objects.equals(current.required(), Boolean.TRUE.equals(req.required()))
                    || !Objects.equals(current.skuDimension(),
                        Boolean.TRUE.equals(config.skuDimension()))
                    || !sameDecimal(current.minValue(), config.minValue())
                    || !sameDecimal(current.maxValue(), config.maxValue())
                    || !Objects.equals(current.decimalScale(), config.decimalScale())
                    || !Objects.equals(current.status(), baseStatus(req.status()));
            if (structuralChange) {
                throw new BusinessException(
                        "该规格已用于区分已有商品，字段、选项、单位、必填性、精度和状态"
                                + "不可原地修改；请新增字段，并重新配置“用于区分商品”");
            }
        }
        if (seriesFieldUsedByPreset(id)
                && (!Objects.equals(current.unitCategoryId(), config.unitCategoryId())
                || !Objects.equals(current.unitId(), config.unitId()))) {
            throw new BusinessException("字段已有商品或预设数据，不能更换计量单位；请停用旧字段并新增新的字段定义");
        }
        validateExistingNumericValues(id, field, config);
        updateRequired("""
                update product_series_field
                set product_series_id = ?, field_id = ?, field_name = ?, display_name = ?,
                    option_json = ?, unit_category_id = ?, unit_id = ?, unit_name = ?,
                    required = ?, sku_dimension = ?, include_in_spec = ?, searchable = ?, order_visible = ?,
                    min_value = ?, max_value = ?, decimal_scale = ?, sort_no = ?, status = ?
                where id = ?
                """, "系列字段不存在",
                req.productSeriesId(), req.fieldId(), field.name(),
                StringUtils.hasText(req.displayName()) ? req.displayName() : field.name(),
                optionJson, config.unitCategoryId(), config.unitId(), config.unitName(), bool(req.required()),
                bool(config.skuDimension()), bool(config.includeInSpec()),
                bool(config.searchable()), bool(config.orderVisible()), config.minValue(), config.maxValue(),
                config.decimalScale(), req.sortNo() == null ? 0 : req.sortNo(), baseStatus(req.status()), id);
        refreshSeriesProductSpecSignatures(current.productSeriesId());
        if (!Objects.equals(current.productSeriesId(), req.productSeriesId())) {
            refreshSeriesProductSpecSignatures(req.productSeriesId());
        }
        if (Boolean.TRUE.equals(current.skuDimension())
                || Boolean.TRUE.equals(config.skuDimension())) {
            productMasterDataService.syncIdentitySchemaFromSeriesFields(
                    req.productSeriesId(), "规格字段配置自动更新商品唯一性规则");
            if (!Objects.equals(current.productSeriesId(), req.productSeriesId())) {
                productMasterDataService.syncIdentitySchemaFromSeriesFields(
                        current.productSeriesId(), "规格字段移出后自动更新商品唯一性规则");
            }
        }
        return id;
    }

    private boolean seriesHasProducts(Long productSeriesId) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from product where product_series_id=?",
                Integer.class, productSeriesId);
        return count != null && count > 0;
    }

    private boolean seriesFieldUsedByIdentity(Long seriesFieldId) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*) from product_identity_rule
                where source_type='SERIES_FIELD' and series_field_id=?
                """, Integer.class, seriesFieldId);
        return count != null && count > 0;
    }

    private boolean sameDecimal(BigDecimal left, BigDecimal right) {
        return left == null ? right == null : right != null && left.compareTo(right) == 0;
    }

    public List<MasterDataDtos.SpecPresetRow> specPresets(Long seriesId) {
        List<MasterDataDtos.SpecPresetRow> presets;
        if (seriesId != null && seriesId > 0) {
            presets = jdbcTemplate.query("""
                    select p.*, s.name product_series_name
                    from product_spec_preset p
                    join product_series s on s.id = p.product_series_id
                    where p.product_series_id = ?
                    order by p.sort_no, p.id
                    """, specPresetMapper(), seriesId);
        } else {
            presets = jdbcTemplate.query("""
                    select p.*, s.name product_series_name
                    from product_spec_preset p
                    join product_series s on s.id = p.product_series_id
                    order by s.id, p.sort_no, p.id
                    """, specPresetMapper());
        }
        return attachSpecPresetValues(presets);
    }

    @Transactional
    public Long createSpecPreset(MasterDataDtos.SpecPresetCreate req) {
        List<NormalizedPresetValue> values = normalizeSpecPresetValues(req, null);
        String attributeJson = mergeSpecPresetAttributeJson(req.attributeJson(), values);
        Long id = insert("""
                insert into product_spec_preset
                (product_series_id, name, model, attribute_json, spec_text, sku_suffix, sort_no, status)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                """, req.productSeriesId(), blank(req.name()), blank(req.model()),
                attributeJson,
                blank(req.specText()), blank(req.skuSuffix()), req.sortNo() == null ? 0 : req.sortNo(),
                baseStatus(req.status()));
        mergeSpecPresetValues(id, values);
        return id;
    }

    @Transactional
    public Long updateSpecPreset(Long id, MasterDataDtos.SpecPresetCreate req) {
        List<NormalizedPresetValue> values = normalizeSpecPresetValues(req, id);
        String attributeJson = mergeSpecPresetAttributeJson(existingPresetAttributeJson(id), values);
        updateRequired("""
                update product_spec_preset
                set product_series_id = ?, name = ?, model = ?, attribute_json = ?, spec_text = ?,
                    sku_suffix = ?, sort_no = ?, status = ?
                where id = ?
                """, "规格预设不存在",
                req.productSeriesId(), blank(req.name()), blank(req.model()),
                attributeJson,
                blank(req.specText()), blank(req.skuSuffix()),
                req.sortNo() == null ? 0 : req.sortNo(), baseStatus(req.status()), id);
        mergeSpecPresetValues(id, values);
        return id;
    }

    public List<AlignedDtos.UnitCategoryRow> unitCategories() {
        return jdbcTemplate.query("select * from unit_category order by id", unitCategoryMapper());
    }

    public Long createUnitCategory(AlignedDtos.UnitCategoryCreate req) {
        return insert("insert into unit_category (name, description, status) values (?, ?, ?)",
                req.name(), blank(req.description()), baseStatus(req.status()));
    }

    @Transactional
    public Long updateUnitCategory(Long id, AlignedDtos.UnitCategoryCreate req) {
        List<Map<String, Object>> current = jdbcTemplate.queryForList("""
                select name,status from unit_category where id=? for update
                """, id);
        if (current.isEmpty()) throw new BusinessException("计量分类不存在");
        Integer uses = jdbcTemplate.queryForObject("""
                select
                  (select count(*) from unit where unit_category_id=?)
                  +(select count(*) from product_series
                    where package_unit_category_id=? or price_unit_category_id=?)
                """, Integer.class, id, id, id);
        if (uses != null && uses > 0
                && (!Objects.equals(String.valueOf(current.get(0).get("name")), req.name())
                    || !Objects.equals(String.valueOf(current.get(0).get("status")),
                        baseStatus(req.status())))) {
            throw new BusinessException("计量分类已被单位或产品系列引用，只能修改说明");
        }
        updateRequired("""
                update unit_category
                set name = ?, description = ?, status = ?
                where id = ?
                """, "计量分类不存在", req.name(), blank(req.description()), baseStatus(req.status()), id);
        return id;
    }

    public List<AlignedDtos.UnitRow> units() {
        List<AlignedDtos.UnitRow> units = jdbcTemplate.query("""
                select u.*, c.name unit_category_name
                from unit u
                join unit_category c on c.id = u.unit_category_id
                order by c.id, u.id
                """, unitMapper());
        Map<Long, List<String>> aliases = new LinkedHashMap<>();
        jdbcTemplate.query("""
                select unit_id,alias_name from unit_alias
                where status='ENABLED' order by unit_id,alias_name
                """, rs -> {
            while (rs.next()) {
                aliases.computeIfAbsent(rs.getLong("unit_id"), ignored -> new ArrayList<>())
                        .add(rs.getString("alias_name"));
            }
        });
        return units.stream().map(unit -> new AlignedDtos.UnitRow(
                unit.id(), unit.unitCategoryId(), unit.unitCategoryName(), unit.name(),
                unit.abbreviation(), unit.description(), unit.unitKind(),
                List.copyOf(aliases.getOrDefault(unit.id(), List.of())), unit.status()
        )).toList();
    }

    @Transactional
    public Long createUnit(AlignedDtos.UnitCreate req) {
        validateUnitNames(null, req.name(), req.abbreviation(), req.aliases());
        Long id;
        try {
            id = insert("""
                    insert into unit
                    (unit_category_id,name,abbreviation,description,unit_kind,status)
                    values (?, ?, ?, ?, ?, ?)
                    """, req.unitCategoryId(), req.name().trim(), blank(req.abbreviation()),
                    blank(req.description()), unitKind(req.unitKind()), baseStatus(req.status()));
            replaceUnitAliases(id, req.name(), req.abbreviation(), req.aliases());
        } catch (DuplicateKeyException ex) {
            throw new BusinessException("计量单位名称、缩写或别名已被其他单位占用");
        }
        return id;
    }

    @Transactional
    public Long updateUnit(Long id, AlignedDtos.UnitCreate req) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select unit_category_id,name,abbreviation,unit_kind,status
                from unit where id=? for update
                """, id);
        if (rows.isEmpty()) throw new BusinessException("计量单位不存在");
        Map<String, Object> current = rows.get(0);
        if (unitInUse(id, String.valueOf(current.get("name")),
                String.valueOf(current.get("abbreviation")))) {
            boolean semanticChange =
                    ((Number) current.get("unit_category_id")).longValue() != req.unitCategoryId()
                    || !Objects.equals(String.valueOf(current.get("name")), req.name().trim())
                    || !Objects.equals(String.valueOf(current.get("abbreviation")),
                        blank(req.abbreviation()))
                    || !Objects.equals(String.valueOf(current.get("unit_kind")),
                        unitKind(req.unitKind()))
                    || !Objects.equals(String.valueOf(current.get("status")),
                        baseStatus(req.status()));
            if (semanticChange) {
                throw new BusinessException(
                        "计量单位已被商品、包装或历史业务引用，名称、缩写、分类、类型和状态不可原地修改；"
                                + "请新建单位并走受控迁移");
            }
        }
        validateUnitNames(id, req.name(), req.abbreviation(), req.aliases());
        try {
            updateRequired("""
                    update unit
                    set unit_category_id=?,name=?,abbreviation=?,description=?,unit_kind=?,status=?
                    where id=?
                    """, "计量单位不存在", req.unitCategoryId(), req.name().trim(),
                    blank(req.abbreviation()), blank(req.description()), unitKind(req.unitKind()),
                    baseStatus(req.status()), id);
            replaceUnitAliases(id, req.name(), req.abbreviation(), req.aliases());
        } catch (DuplicateKeyException ex) {
            throw new BusinessException("计量单位名称、缩写或别名已被其他单位占用");
        }
        return id;
    }

    private boolean unitInUse(Long unitId, String name, String abbreviation) {
        Integer count = jdbcTemplate.queryForObject("""
                select
                  (select count(*) from product_series_allowed_unit where unit_id=?)
                  +(select count(*) from product_series_field where unit_id=?)
                  +(select count(*) from product_field_value where unit_id_snapshot=?)
                  +(select count(*) from product_series_package_unit where unit_id=?)
                  +(select count(*) from product_series_price_unit where unit_id=?)
                  +(select count(*) from packaging_template_revision
                    where base_unit_id=? or minimum_sales_unit_id=? or minimum_outbound_unit_id=?
                       or default_purchase_unit_id=? or default_sales_unit_id=?)
                  +(select count(*) from packaging_template_rule
                    where parent_unit_id=? or child_unit_id=?)
                """, Integer.class,
                unitId, unitId, unitId, unitId, unitId,
                unitId, unitId, unitId, unitId, unitId,
                unitId, unitId);
        return count != null && count > 0 || unitTextInUse(name, abbreviation);
    }

    private boolean unitTextInUse(String name, String abbreviation) {
        String primary = StringUtils.hasText(name) ? name.trim() : "";
        String secondary = StringUtils.hasText(abbreviation) ? abbreviation.trim() : "";
        if (!StringUtils.hasText(primary) && !StringUtils.hasText(secondary)) return false;
        Map<String, List<String>> references = new LinkedHashMap<>();
        references.put("product", List.of("unit"));
        references.put("product_series_field", List.of("unit_name"));
        references.put("product_field_value", List.of("unit_name_snapshot"));
        references.put("product_series_package_unit", List.of("name", "unit_name"));
        references.put("product_series_price_unit", List.of("name", "unit_name"));
        references.put("product_measure_policy", List.of(
                "base_unit", "minimum_sales_unit", "minimum_outbound_unit",
                "default_purchase_unit", "default_sales_unit"));
        references.put("product_measure_policy_version", List.of(
                "base_unit", "minimum_sales_unit", "minimum_outbound_unit",
                "default_purchase_unit", "default_sales_unit"));
        references.put("product_package_definition", List.of("package_unit"));
        references.put("product_package_definition_revision_item", List.of("package_unit"));
        references.put("product_package_rule", List.of("parent_unit", "child_unit"));
        references.put("packaging_template_revision", List.of(
                "base_unit", "minimum_sales_unit", "minimum_outbound_unit",
                "default_purchase_unit", "default_sales_unit"));
        references.put("packaging_template_rule", List.of("parent_unit", "child_unit"));
        references.put("packaging_variant_revision", List.of("package_unit"));
        references.put("supplier_product_offer_revision", List.of("purchase_unit", "price_unit"));
        references.put("product_technical_characteristic", List.of("unit_name"));
        references.put("product_bom_revision", List.of("output_unit", "output_base_unit"));
        references.put("product_bom_item", List.of("unit", "base_unit"));
        for (Map.Entry<String, List<String>> reference : references.entrySet()) {
            List<String> predicates = new ArrayList<>();
            List<Object> args = new ArrayList<>();
            for (String column : reference.getValue()) {
                predicates.add("(trim(" + column + ")<>'' and lower(trim(" + column
                        + ")) in (lower(?),lower(?)))");
                args.add(primary);
                args.add(secondary);
            }
            Integer textCount = jdbcTemplate.queryForObject(
                    "select count(*) from " + reference.getKey() + " where "
                            + String.join(" or ", predicates),
                    Integer.class, args.toArray());
            if (textCount != null && textCount > 0) return true;
        }
        return false;
    }

    public List<WarehouseDto> warehouses() {
        return jdbcTemplate.query("select * from warehouse order by id desc", warehouseMapper());
    }

    public Long createWarehouse(SimpleCreateRequests.WarehouseCreate req) {
        return insert("insert into warehouse (name, location, receiving_addresses, status) values (?, ?, ?, ?)",
                req.name(), blank(req.location()), blank(req.receivingAddresses()), baseStatus(req.status()));
    }

    public Long updateWarehouse(Long id, SimpleCreateRequests.WarehouseCreate req) {
        updateRequired("""
                update warehouse
                set name = ?, location = ?, receiving_addresses = ?, status = ?
                where id = ?
                """, "仓库不存在", req.name(), blank(req.location()), blank(req.receivingAddresses()), baseStatus(req.status()), id);
        return id;
    }

    public List<InventoryBatchDto> inventoryBatches(Long productId, Long warehouseId, Boolean availableOnly) {
        StringBuilder sql = new StringBuilder("""
                select b.*, p.sku, p.name product_name, p.grade, p.spec, w.name warehouse_name,
                       io.id source_inbound_id, io.order_no source_inbound_no,
                       coalesce(r.reserved_quantity,0) reserved_quantity,
                       greatest(b.quantity_available-coalesce(r.reserved_quantity,0),0) free_quantity,
                       coalesce(t.in_transit_quantity,0) in_transit_quantity
                from inventory_batch b
                join product p on p.id = b.product_id
                join warehouse w on w.id = b.warehouse_id
                left join inbound_item ii on b.source_type = 'INBOUND' and ii.id = b.source_id
                left join inbound_order io on io.id = ii.inbound_id
                left join (
                  select inventory_batch_id,sum(quantity) reserved_quantity from outbound_allocation
                  where status='DRAFT' group by inventory_batch_id
                ) r on r.inventory_batch_id=b.id
                left join (
                  select i.source_batch_id,sum(i.quantity) in_transit_quantity
                  from inventory_transfer_item i join inventory_transfer_order o on o.id=i.transfer_id
                  where o.status='IN_TRANSIT' group by i.source_batch_id
                ) t on t.source_batch_id=b.id
                where 1 = 1
                """);
        if (productId != null && productId > 0) {
            sql.append(" and b.product_id = ").append(productId);
        }
        if (warehouseId != null && warehouseId > 0) {
            sql.append(" and b.warehouse_id = ").append(warehouseId);
        }
        if (Boolean.TRUE.equals(availableOnly)) {
            sql.append(" and b.quantity_available > 0");
        }
        sql.append(" order by b.received_at asc, b.id asc");
        return jdbcTemplate.query(sql.toString(), inventoryMapper());
    }

    public List<PackagingDtos.PackageInstance> inventoryPackageInstances(Long batchId) {
        lockBatch(batchId);
        return packagingService.inventoryPackageInstances(batchId);
    }

    @Transactional
    public Long createInbound(InboundCreateRequest req) {
        PreparedInboundOrder prepared = prepareInbound(req, 0L);
        SettlementDueSnapshot settlement = settlementDueSnapshot("SUPPLIER", req.supplierId(), req.businessDate());
        String orderNo = nextNo("RK");
        Long inboundId = insert("""
                insert into inbound_order
                (order_no, purchase_order_id, supplier_id, warehouse_id, source_type, source_note,
                 business_date, settlement_days_snapshot, due_date, send_date, send_number,
                 original_amount, discount_rate, adjustment_amount, status, total_quantity, total_amount, memo)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'DRAFT', ?, ?, ?)
                """, orderNo, prepared.purchaseOrderId(), req.supplierId(), req.warehouseId(),
                prepared.purchaseOrderId() > 0 ? "PURCHASE_ORDER" : (StringUtils.hasText(req.sourceType()) ? sourceType(req.sourceType()) : "NO_ORDER"), blank(req.sourceNote()),
                Date.valueOf(req.businessDate()), settlement.days(), Date.valueOf(settlement.dueDate()),
                req.sendDate() == null ? null : Date.valueOf(req.sendDate()), blank(req.sendNumber()),
                prepared.originalAmount(), nvl(req.discountRate()), nvl(req.adjustmentAmount()),
                prepared.totalQuantity(), prepared.totalAmount(), blank(req.memo()));
        insertInboundItems(inboundId, orderNo, prepared.items());
        refreshFlowDocumentTax("INBOUND", inboundId, req.supplierId(), prepared.purchaseOrderId(),
                req.discountRate(), req.adjustmentAmount());
        return inboundId;
    }

    @Transactional
    public Long updateInboundDraft(Long inboundId, InboundCreateRequest req) {
        InboundAuditRow current = lockInbound(inboundId);
        if (!List.of("DRAFT", "REJECTED").contains(current.status())) throw new BusinessException("只有草稿或已拒绝入库单可以修改");
        PreparedInboundOrder prepared = prepareInbound(req, inboundId);
        SettlementDueSnapshot settlement = settlementDueSnapshot("SUPPLIER", req.supplierId(), req.businessDate());
        jdbcTemplate.update("""
                update inbound_order
                set purchase_order_id=?,supplier_id=?,warehouse_id=?,source_type=?,source_note=?,
                    business_date=?,settlement_days_snapshot=?,due_date=?,send_date=?,send_number=?,original_amount=?,discount_rate=?,
                    adjustment_amount=?,total_quantity=?,total_amount=?,memo=?
                where id=?
                """, prepared.purchaseOrderId(), req.supplierId(), req.warehouseId(),
                prepared.purchaseOrderId() > 0 ? "PURCHASE_ORDER" : (StringUtils.hasText(req.sourceType()) ? sourceType(req.sourceType()) : "NO_ORDER"),
                blank(req.sourceNote()), Date.valueOf(req.businessDate()), settlement.days(), Date.valueOf(settlement.dueDate()),
                req.sendDate() == null ? null : Date.valueOf(req.sendDate()), blank(req.sendNumber()),
                prepared.originalAmount(), nvl(req.discountRate()), nvl(req.adjustmentAmount()),
                prepared.totalQuantity(), prepared.totalAmount(), blank(req.memo()), inboundId);
        jdbcTemplate.update("delete p from inbound_package_instance p join inbound_item i on i.id=p.inbound_item_id where i.inbound_id=?", inboundId);
        jdbcTemplate.update("delete from inbound_item where inbound_id=?", inboundId);
        insertInboundItems(inboundId, current.orderNo(), prepared.items());
        refreshFlowDocumentTax("INBOUND", inboundId, req.supplierId(), prepared.purchaseOrderId(),
                req.discountRate(), req.adjustmentAmount());
        return inboundId;
    }

    @Transactional
    public Long submitInbound(Long inboundId, String operatorName) {
        InboundAuditRow inbound = lockInbound(inboundId);
        if ("CANCELLED".equals(inbound.status())) throw new BusinessException("已取消的入库单不能提交审核");
        if (!List.of("DRAFT", "REJECTED").contains(inbound.status())) return inboundId;
        requirePartnerRole(inbound.supplierId(), "SUPPLIER");
        requireWarehouse(inbound.warehouseId());
        Integer itemCount = jdbcTemplate.queryForObject("select count(*) from inbound_item where inbound_id=?", Integer.class, inboundId);
        if (itemCount == null || itemCount == 0) throw new BusinessException("入库单没有商品明细，不能提交审核");
        List<Long> productIds = jdbcTemplate.query("select product_id from inbound_item where inbound_id=?",
                (rs, rowNum) -> rs.getLong("product_id"), inboundId);
        productIds.forEach(this::requireProductAccessible);
        OrderDtos.InboundDetail submissionDetail = inboundDetail(inboundId);
        for (OrderDtos.InboundItemDetail item : submissionDetail.items()) {
            packagingService.validateInboundPackageInstances(
                    item.packagingPlanJson(), item.quantity(),
                    StringUtils.hasText(item.baseUnit()) ? item.baseUnit() : item.unit(),
                    item.packageQuantity(), item.packageUnit(), item.packageInstances());
        }
        Long snapshotId = captureInboundReviewSnapshot(inboundId, operatorName, "SUBMIT");
        jdbcTemplate.update("""
                update inbound_order set status='PENDING',submitted_at=now(),
                  rejection_reason='',rejected_at=null where id=?
                """, inboundId);
        insertInboundAuditLog(inboundId, "SUBMIT", "", operatorName, snapshotId);
        return inboundId;
    }

    @Transactional
    public Long withdrawInbound(Long inboundId, String operatorName) {
        InboundAuditRow inbound = lockInbound(inboundId);
        if (!"PENDING".equals(inbound.status())) throw new BusinessException("只有待审核入库单可以撤回");
        Long snapshotId = ensureInboundReviewSnapshot(inboundId, operatorName);
        jdbcTemplate.update("update inbound_order set status='DRAFT',withdrawn_at=now() where id=?", inboundId);
        insertInboundAuditLog(inboundId, "WITHDRAW", "", operatorName, snapshotId);
        return inboundId;
    }

    private PreparedInboundOrder prepareInbound(InboundCreateRequest req, Long excludedInboundId) {
        requirePartnerRole(req.supplierId(), "SUPPLIER");
        requireWarehouse(req.warehouseId());
        if (req.sendDate() != null && req.sendDate().isAfter(req.businessDate())) {
            throw new BusinessException("发货日期不得晚于入库日期");
        }
        Long purchaseOrderId = req.purchaseOrderId() == null ? 0L : req.purchaseOrderId();
        if (purchaseOrderId > 0) {
            PurchasePayRow purchase = lockPurchaseForPay(purchaseOrderId);
            if (!Objects.equals(purchase.supplierId(), req.supplierId())) {
                throw new BusinessException("入库供应商与采购订单供应商不一致");
            }
            Long expectedWarehouseId = jdbcTemplate.queryForObject(
                    "select expected_warehouse_id from purchase_order where id=?", Long.class, purchaseOrderId);
            if (expectedWarehouseId == null || expectedWarehouseId <= 0) {
                throw new BusinessException("采购订单尚未分配预计入库仓库，不能入库");
            }
            if (!Objects.equals(expectedWarehouseId, req.warehouseId())) {
                throw new BusinessException("入库仓库与采购订单预计入库仓库不一致；请先按订单变更流程调整预计仓库");
            }
        }
        if (StringUtils.hasText(req.sendNumber())) {
            Integer duplicate = jdbcTemplate.queryForObject("""
                    select count(*) from inbound_order
                    where supplier_id = ? and send_number = ? and id <> ?
                    """, Integer.class, req.supplierId(), req.sendNumber(), excludedInboundId);
            if (duplicate != null && duplicate > 0) {
                throw new BusinessException("此发货单已入过库了");
            }
        }

        BigDecimal totalQuantity = BigDecimal.ZERO;
        BigDecimal originalAmount = BigDecimal.ZERO;
        List<PreparedInboundItem> preparedItems = new ArrayList<>();
        for (InboundCreateRequest.Item item : req.items()) {
            long purchaseOrderItemId = resolveInboundPurchaseItem(purchaseOrderId, item);
            if (purchaseOrderItemId > 0) {
                requireProductAccessible(item.productId());
            } else {
                requireProduct(item.productId());
            }
            if (item.productionDate() != null && item.productionDate().isAfter(req.businessDate())) {
                throw new BusinessException("生产日期不得晚于入库日期");
            }
            String priceUnit = StringUtils.hasText(item.priceUnit()) ? item.priceUnit().trim() : productDefaultUnit(item.productId());
            PackagingDtos.Plan plan = inboundPackagingPlan(
                    purchaseOrderId, purchaseOrderItemId, item, priceUnit);
            packagingService.validatePackagingQuantity(plan, item.packageQuantity(), item.packageUnit());
            packagingService.validateInboundPackageInstancesDraft(
                    plan, item.packageQuantity(), item.packageUnit(), item.packageInstances());
            ShelfLifeSnapshot shelfLife = inboundShelfLife(item);
            BigDecimal amount = plan.pricingQuantity().multiply(item.unitCost());
            totalQuantity = totalQuantity.add(plan.baseQuantity());
            originalAmount = originalAmount.add(amount);
            preparedItems.add(new PreparedInboundItem(item, purchaseOrderItemId, priceUnit, plan, amount, shelfLife));
        }
        BigDecimal totalAmount = amountAfterDiscount(originalAmount, req.discountRate(), req.adjustmentAmount());
        if (totalAmount.compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException("入库折扣和调整后总金额不能为负数");
        }

        return new PreparedInboundOrder(purchaseOrderId, totalQuantity, originalAmount, totalAmount, preparedItems);
    }

    private void insertInboundItems(Long inboundId, String orderNo, List<PreparedInboundItem> preparedItems) {
        int index = 1;
        for (PreparedInboundItem prepared : preparedItems) {
            InboundCreateRequest.Item item = prepared.item();
            long purchaseOrderItemId = prepared.purchaseOrderItemId();
            String batchNo = StringUtils.hasText(item.batchNo()) ? item.batchNo() : orderNo + "-" + index;
            String priceUnit = prepared.priceUnit();
            PackagingDtos.Plan plan = prepared.plan();
            BigDecimal amount = prepared.amount();
            ShelfLifeSnapshot shelfLife = prepared.shelfLife();
            ProductDocumentSnapshot product = purchaseItemProductSnapshot(purchaseOrderItemId, item.productId());
            Long inboundItemId = insert("""
                    insert into inbound_item
                    (inbound_id, product_id, product_sku_snapshot, product_name_snapshot, product_spec_snapshot,
                     purchase_order_item_id, requested_quantity, requested_unit, batch_no, serial_number,
                     quantity, base_unit, package_quantity, price_unit, pricing_quantity, package_unit,
                     packaging_plan_json, packaging_effective_revision_id, packaging_snapshot_hash,
                     package_barcode,unit_cost, amount, production_date,
                     shelf_life_mode,shelf_life_value,shelf_life_unit,expiration_date)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, inboundId, item.productId(), product.sku(), product.name(), product.spec(), purchaseOrderItemId,
                    plan.requestedQuantity(), plan.requestedUnit(),
                    batchNo, blank(item.serialNumber()), plan.baseQuantity(), plan.baseUnit(),
                    nvl(item.packageQuantity()), priceUnit, plan.pricingQuantity(), blank(item.packageUnit()),
                    plan.snapshotJson(), plan.effectiveRevisionId() == null ? 0 : plan.effectiveRevisionId(),
                    plan.snapshotHash(), blank(item.packageBarcode()), item.unitCost(), amount,
                    item.productionDate() == null ? null : Date.valueOf(item.productionDate()),
                    shelfLife.mode(), shelfLife.value(), shelfLife.unit(),
                    shelfLife.expirationDate() == null ? null : Date.valueOf(shelfLife.expirationDate()));
            List<PackagingDtos.PackageInstanceInput> instances = item.packageInstances() == null
                    ? List.of() : item.packageInstances();
            int packageIndex = 1;
            for (PackagingDtos.PackageInstanceInput instance : instances) {
                String packageCode = StringUtils.hasText(instance.packageCode())
                        ? instance.packageCode().trim()
                        : batchNo + "-" + String.format("%03d", packageIndex);
                insert("""
                        insert into inbound_package_instance
                        (inbound_item_id, package_code, unit_name, actual_base_quantity, base_unit)
                        values (?, ?, ?, ?, ?)
                        """, inboundItemId, packageCode, blank(item.packageUnit()),
                        nvl(instance.actualBaseQuantity()), plan.baseUnit());
                packageIndex++;
            }
            index++;
        }
    }

    private ShelfLifeSnapshot inboundShelfLife(InboundCreateRequest.Item item) {
        PackagingDtos.PackageDefinition definition = null;
        if (StringUtils.hasText(item.packageBarcode())) {
            PackagingDtos.PackageBarcodeMatch match = packagingService.packageBarcode(item.packageBarcode());
            if (!Objects.equals(match.productId(), item.productId())) {
                throw new BusinessException("包装条码与所选商品不一致");
            }
            if (StringUtils.hasText(item.packageUnit()) && !sameText(match.packageUnit(), item.packageUnit())) {
                throw new BusinessException("包装条码与所选包装单位不一致");
            }
            definition = new PackagingDtos.PackageDefinition(
                    match.productId(), match.packageUnit(), match.barcode(), match.shelfLifeMode(),
                    match.shelfLifeValue(), match.shelfLifeUnit(), 0L, null);
        } else if (StringUtils.hasText(item.packageUnit())) {
            definition = packagingService.packageDefinition(item.productId(), item.packageUnit());
        }
        if (definition == null || !"PERIOD".equalsIgnoreCase(definition.shelfLifeMode())) {
            return new ShelfLifeSnapshot("NONE", null, "", null);
        }
        if (item.productionDate() == null) {
            throw new BusinessException("包装“" + definition.packageUnit() + "”设置了保质期，必须填写生产日期");
        }
        int value = definition.shelfLifeValue() == null ? 0 : definition.shelfLifeValue();
        if (value <= 0) throw new BusinessException("商品包装保质期配置不完整");
        String unit = definition.shelfLifeUnit();
        LocalDate expiration = switch (unit) {
            case "DAY" -> item.productionDate().plusDays(value).minusDays(1);
            case "MONTH" -> item.productionDate().plusMonths(value).minusDays(1);
            case "YEAR" -> item.productionDate().plusYears(value).minusDays(1);
            default -> throw new BusinessException("商品包装保质期单位不正确");
        };
        return new ShelfLifeSnapshot("PERIOD", value, unit, expiration);
    }

    @Transactional
    public Long auditInbound(Long inboundId, boolean approved, String reason, String operatorName) {
        InboundAuditRow inbound = lockInbound(inboundId);
        if ("STOCKED".equals(inbound.status())) {
            throw new BusinessException("已经入库完成，无需再次入库");
        }
        if ("CANCELLED".equals(inbound.status())) {
            throw new BusinessException("入库单已取消");
        }
        if (!"PENDING".equals(inbound.status())) {
            throw new BusinessException("只有待审核入库单可以审核");
        }
        Long snapshotId = ensureInboundReviewSnapshot(inboundId, operatorName);
        if (!approved) {
            if (!StringUtils.hasText(reason)) throw new BusinessException("拒绝入库必须填写原因");
            String rejectionReason = reason.trim();
            if (rejectionReason.length() > 500) throw new BusinessException("拒绝原因不能超过500字");
            jdbcTemplate.update("""
                    update inbound_order set status='REJECTED',rejection_reason=?,rejected_at=now(),audited_at=now()
                    where id=?
                    """, rejectionReason, inboundId);
            insertInboundAuditLog(inboundId, "REJECT", rejectionReason, operatorName, snapshotId);
            return inboundId;
        }

        requirePartnerRole(inbound.supplierId(), "SUPPLIER");
        List<InboundAuditItem> items = jdbcTemplate.query("""
                select id, product_id, purchase_order_item_id, batch_no, serial_number,
                       requested_quantity,quantity,base_unit,package_quantity,package_unit,
                       packaging_plan_json,package_barcode,unit_cost,amount,production_date,
                       shelf_life_mode,shelf_life_value,shelf_life_unit,expiration_date
                from inbound_item
                where inbound_id = ? and inventory_batch_id = 0
                order by id
                """, (rs, rowNum) -> new InboundAuditItem(
                rs.getLong("id"),
                rs.getLong("product_id"),
                rs.getLong("purchase_order_item_id"),
                rs.getString("batch_no"),
                rs.getString("serial_number"),
                rs.getBigDecimal("requested_quantity"),
                rs.getBigDecimal("quantity"),
                rs.getString("base_unit"),
                rs.getBigDecimal("package_quantity"),
                rs.getString("package_unit"),
                rs.getString("packaging_plan_json"),
                rs.getString("package_barcode"),
                rs.getBigDecimal("unit_cost"),
                rs.getBigDecimal("amount"),
                rs.getDate("production_date") == null ? null : rs.getDate("production_date").toLocalDate(),
                rs.getString("shelf_life_mode"),
                rs.getObject("shelf_life_value") == null ? null : rs.getInt("shelf_life_value"),
                rs.getString("shelf_life_unit"),
                rs.getDate("expiration_date") == null ? null : rs.getDate("expiration_date").toLocalDate()
        ), inboundId);

        BigDecimal sourceAmount = items.stream().map(InboundAuditItem::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal allocatedInventoryAmount = BigDecimal.ZERO;
        for (int itemIndex = 0; itemIndex < items.size(); itemIndex++) {
            InboundAuditItem item = items.get(itemIndex);
            BigDecimal inventoryAmount;
            if (itemIndex == items.size() - 1) {
                inventoryAmount = inbound.totalAmount().setScale(2, RoundingMode.HALF_UP).subtract(allocatedInventoryAmount);
            } else if (sourceAmount.compareTo(BigDecimal.ZERO) > 0) {
                inventoryAmount = inbound.totalAmount().multiply(item.amount())
                        .divide(sourceAmount, 2, RoundingMode.HALF_UP);
            } else {
                inventoryAmount = inbound.totalAmount().divide(BigDecimal.valueOf(items.size()), 2, RoundingMode.HALF_UP);
            }
            allocatedInventoryAmount = allocatedInventoryAmount.add(inventoryAmount);
            BigDecimal inventoryUnitCost = inventoryAmount.divide(item.quantity(), 4, RoundingMode.HALF_UP);
            Long batchId = insert("""
                    insert into inventory_batch
                    (product_id, warehouse_id, batch_no, serial_number, source_type, source_id,
                     base_unit,
                     quantity_in, quantity_available, package_quantity_in, package_quantity_available,
                     package_barcode,unit_cost,amount_cost,received_at,production_date,
                     shelf_life_mode,shelf_life_value,shelf_life_unit,expiration_date)
                    values (?, ?, ?, ?, 'INBOUND', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, item.productId(), inbound.warehouseId(), item.batchNo(), item.serialNumber(), item.id(),
                    item.baseUnit(),
                    item.quantity(), item.quantity(), item.packageQuantity(), item.packageQuantity(),
                    item.packageBarcode(), inventoryUnitCost, inventoryAmount, Date.valueOf(inbound.businessDate()),
                    item.productionDate() == null ? null : Date.valueOf(item.productionDate()),
                    item.shelfLifeMode(), item.shelfLifeValue(), item.shelfLifeUnit(),
                    item.expirationDate() == null ? null : Date.valueOf(item.expirationDate()));

            jdbcTemplate.update("""
                    update inbound_item set inventory_batch_id=?,inventory_unit_cost=?,inventory_amount_cost=? where id=?
                    """, batchId, inventoryUnitCost, inventoryAmount, item.id());
            List<InboundPackageInstanceRow> packageInstances = jdbcTemplate.query("""
                    select id, package_code, unit_name, actual_base_quantity, base_unit
                    from inbound_package_instance where inbound_item_id = ? order by id
                    """, (rs, rowNum) -> new InboundPackageInstanceRow(
                    rs.getLong("id"), rs.getString("package_code"), rs.getString("unit_name"),
                    rs.getBigDecimal("actual_base_quantity"), rs.getString("base_unit")
            ), item.id());
            for (InboundPackageInstanceRow packageInstance : packageInstances) {
                insert("""
                        insert into inventory_package_instance
                        (batch_id, product_id, source_instance_id, package_code, unit_name,
                         actual_base_quantity, remaining_base_quantity, base_unit, status)
                        values (?, ?, ?, ?, ?, ?, ?, ?, 'AVAILABLE')
                        """, batchId, item.productId(), packageInstance.id(), packageInstance.packageCode(),
                        packageInstance.unitName(), packageInstance.actualBaseQuantity(),
                        packageInstance.actualBaseQuantity(), packageInstance.baseUnit());
            }
            packagingService.initializeInventoryPackaging(
                    batchId, item.productId(), item.packagingPlanJson(),
                    item.packageQuantity(), item.packageUnit(), !packageInstances.isEmpty(),
                    "INBOUND_ITEM", item.id());
            changeStockBalance(item.productId(), inbound.warehouseId(), item.quantity(), inventoryAmount);
            insertInventoryMovement("IN", "INBOUND", item.productId(), inbound.warehouseId(), batchId,
                    item.quantity(), inventoryUnitCost, inventoryAmount, "INBOUND_ITEM", item.id(), "入库审核 " + inbound.orderNo());
        }

        jdbcTemplate.update("""
                update inbound_order
                set status = 'STOCKED',
                    stocked_at = now(),
                    audited_at = now(),
                    payable_amount = total_amount,
                    payment_status = case
                      when paid_amount >= total_amount and total_amount > 0 then 'PAID'
                      when paid_amount > 0 then 'PARTIAL'
                      else 'UNPAID'
                    end
                where id = ?
                """, inboundId);
        insertPayableMovement("AP", inbound.supplierId(), "INBOUND", inboundId, "INCREASE",
                inbound.totalAmount(), "入库形成应付 " + inbound.orderNo());
        insertInboundAuditLog(inboundId, "APPROVE", "", operatorName, snapshotId);
        if (inbound.purchaseOrderId() > 0) {
            updatePurchaseReceived(inbound.purchaseOrderId(), items);
        }
        postingService.post("INBOUND", inboundId, inbound.orderNo(), "POST", 1,
                inbound.businessDate(), inbound.supplierId(), 0L, "入库审核通过",
                inboundPostingEntries(inboundId));
        return inboundId;
    }

    @Transactional
    public Long reverseInbound(Long inboundId, OutboundActionRequests.Reason req, String operatorName) {
        String reason = requiredReason(req.reason(), "入库冲正");
        InboundAuditRow inbound = lockInbound(inboundId);
        if ("REVERSED".equals(inbound.status())) return inboundId;
        if (!"STOCKED".equals(inbound.status())) throw new BusinessException("只有已入库单可以冲正");
        BigDecimal paidAmount = jdbcTemplate.queryForObject(
                "select paid_amount from inbound_order where id=?", BigDecimal.class, inboundId);
        if (nvl(paidAmount).compareTo(BigDecimal.ZERO) > 0)
            throw new BusinessException("入库单已有付款，必须先冲正付款再冲正入库");
        Integer activeReturns = jdbcTemplate.queryForObject(
                "select count(*) from purchase_return_order where inbound_id=? and status='CONFIRMED'",
                Integer.class, inboundId);
        if (activeReturns != null && activeReturns > 0)
            throw new BusinessException("入库单已有已确认采购退货，必须先冲正采购退货");

        List<InboundReversalItem> items = jdbcTemplate.query("""
                select ii.id,ii.product_id,ii.purchase_order_item_id,ii.inventory_batch_id,
                       ii.requested_quantity,ii.quantity,ii.base_unit,ii.inventory_unit_cost,ii.inventory_amount_cost,
                       b.quantity_in,b.quantity_available,b.unit_cost,b.amount_cost
                from inbound_item ii join inventory_batch b on b.id=ii.inventory_batch_id
                where ii.inbound_id=? order by ii.id for update
                """, (rs, rowNum) -> new InboundReversalItem(
                rs.getLong("id"), rs.getLong("product_id"), rs.getLong("purchase_order_item_id"),
                rs.getLong("inventory_batch_id"), rs.getBigDecimal("requested_quantity"),
                rs.getBigDecimal("quantity"), rs.getString("base_unit"), rs.getBigDecimal("inventory_unit_cost"),
                rs.getBigDecimal("inventory_amount_cost"), rs.getBigDecimal("quantity_in"),
                rs.getBigDecimal("quantity_available"), rs.getBigDecimal("unit_cost"), rs.getBigDecimal("amount_cost")),
                inboundId);
        if (items.isEmpty()) throw new BusinessException("入库单库存批次缺失，不能冲正");
        for (InboundReversalItem item : items) {
            Integer laterMovements = jdbcTemplate.queryForObject("""
                    select count(*) from inventory_movement
                    where batch_id=? and not (ref_type='INBOUND_ITEM' and ref_id=?)
                    """, Integer.class, item.batchId(), item.id());
            if (laterMovements != null && laterMovements > 0)
                throw new BusinessException("批次已有出库、调拨、盘点、退货或成本后续业务，不能直接冲正入库");
            if (item.quantityIn().compareTo(item.quantity()) != 0
                    || item.quantityAvailable().compareTo(item.quantity()) != 0
                    || item.amountCost().compareTo(item.inventoryAmountCost()) != 0
                    || item.unitCost().compareTo(item.inventoryUnitCost()) != 0)
                throw new BusinessException("入库批次数量或成本已变化，不能直接冲正入库");
            if (batchDraftReservedQuantity(item.batchId(), 0L).compareTo(BigDecimal.ZERO) > 0)
                throw new BusinessException("入库批次已被出库草稿占用，请先取消相关出库草稿");
        }
        Long snapshotId = ensureInboundReviewSnapshot(inboundId, operatorName);
        for (InboundReversalItem item : items) {
            packagingService.reverseInboundPackaging(item.batchId(), item.productId(), item.id());
            jdbcTemplate.update("""
                    update inventory_batch set quantity_available=0,amount_cost=0,status='OUT',shipped_at=now()
                    where id=?
                    """, item.batchId());
            changeStockBalance(item.productId(), inbound.warehouseId(), item.quantity().negate(),
                    item.inventoryAmountCost().negate());
            insertInventoryMovement("RC", "INBOUND_REVERSAL", item.productId(), inbound.warehouseId(), item.batchId(),
                    item.quantity().negate(), item.inventoryUnitCost(), item.inventoryAmountCost().negate(),
                    "INBOUND_REVERSAL", inboundId, "入库冲正 " + inbound.orderNo());
            if (inbound.purchaseOrderId() > 0 && item.purchaseOrderItemId() > 0) {
                BigDecimal requestedEquivalent = purchaseOrderRequestedEquivalent(
                        item.purchaseOrderItemId(), inbound.purchaseOrderId(), item.quantity());
                jdbcTemplate.update("""
                        update purchase_order_item set
                          received_quantity=greatest(received_quantity-?,0),
                          received_requested_quantity=greatest(received_requested_quantity-?,0)
                        where id=? and purchase_order_id=?
                        """, item.quantity(), requestedEquivalent, item.purchaseOrderItemId(), inbound.purchaseOrderId());
            }
        }
        if (inbound.purchaseOrderId() > 0) refreshPurchaseOrderReceiptStatus(inbound.purchaseOrderId());
        insertPayableMovement("AR", inbound.supplierId(), "INBOUND_REVERSAL", inboundId, "DECREASE",
                inbound.totalAmount(), "入库冲正撤回应付 " + inbound.orderNo());
        jdbcTemplate.update("""
                update inbound_order set status='REVERSED',payable_amount=0,payment_status='UNPAID',
                  reversal_no=?,reversal_reason=?,reversed_at=now() where id=?
                """, nextNo("RC"), reason, inboundId);
        insertInboundAuditLog(inboundId, "REVERSE", reason, operatorName, snapshotId);
        postingService.reverseSource("INBOUND", inboundId, inbound.orderNo(), "REVERSE", 1,
                req.businessDate() == null ? LocalDate.now() : req.businessDate(), inbound.supplierId(),
                List.of("POST"), reason);
        return inboundId;
    }

    @Transactional
    public Long createPurchaseOrder(PurchaseCreateRequest req) {
        requirePartnerRole(req.supplierId(), "SUPPLIER");
        if (req.expectedWarehouseId() != null && req.expectedWarehouseId() > 0) requireWarehouse(req.expectedWarehouseId());
        PreparedPurchaseOrder prepared = preparePurchaseOrder(req);
        SettlementDueSnapshot settlement = settlementDueSnapshot("SUPPLIER", req.supplierId(), req.businessDate());

        String orderNo = nextNo("CG");
        Long purchaseOrderId = insert("""
                insert into purchase_order
                (order_no, supplier_id, business_date, settlement_days_snapshot, due_date, expected_date, expected_warehouse_id, source_type, source_note,
                 price_includes_tax,tax_rate_summary,tax_rule_snapshot,
                 original_amount, discount_rate, discount_amount, adjustment_amount,amount_excluding_tax,tax_amount,tax_rounding_difference,
                 total_quantity, total_amount, status, memo)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'DRAFT', ?)
                """, orderNo, req.supplierId(), Date.valueOf(req.businessDate()), settlement.days(), Date.valueOf(settlement.dueDate()),
                req.expectedDate() == null ? null : Date.valueOf(req.expectedDate()),
                req.expectedWarehouseId() == null ? 0L : req.expectedWarehouseId(),
                sourceType(req.sourceType()), blank(req.sourceNote()),
                bool(prepared.tax().priceIncludesTax()), prepared.tax().taxRateSummary(), prepared.tax().ruleSnapshot(),
                prepared.originalAmount(), nvl(req.discountRate()), prepared.discountAmount(),
                prepared.adjustmentAmount(), prepared.tax().amountExcludingTax(), prepared.tax().taxAmount(),
                prepared.tax().roundingDifference(), prepared.totalQuantity(), prepared.totalAmount(), blank(req.memo()));

        insertPurchaseOrderItems(purchaseOrderId, prepared.items());
        recordInitialOrderRevision("PURCHASE_ORDER", purchaseOrderId, orderNo);
        return purchaseOrderId;
    }

    @Transactional
    public Long updatePurchaseOrder(Long purchaseOrderId, PurchaseCreateRequest req) {
        requirePartnerRole(req.supplierId(), "SUPPLIER");
        if (req.expectedWarehouseId() != null && req.expectedWarehouseId() > 0) requireWarehouse(req.expectedWarehouseId());
        PurchaseEditRow purchaseOrder = lockPurchaseForEdit(purchaseOrderId);
        Map<Long, String> snapshots = orderItemSnapshots("purchase_order_item", "purchase_order_id", purchaseOrderId);
        Map<Long, String> priceSnapshots = orderItemPriceSnapshots("purchase_order_item", "purchase_order_id", purchaseOrderId);
        Map<Long, Long> historicalProductIds =
                orderItemProductIds("purchase_order_item", "purchase_order_id", purchaseOrderId);
        PreparedPurchaseOrder prepared = preparePurchaseOrder(req, snapshots, priceSnapshots, historicalProductIds,
                storedOrderTaxTerms("PURCHASE_ORDER", purchaseOrderId));
        if (!"DRAFT".equals(purchaseOrder.status())) {
            throw new BusinessException("只有暂存的采购订单可以修改");
        }
        if (purchaseOrder.receivedQuantity().compareTo(BigDecimal.ZERO) != 0
                || purchaseOrder.paidAmount().compareTo(BigDecimal.ZERO) != 0) {
            throw new BusinessException("采购订单已有到货或付款记录，不能修改");
        }
        SettlementDueSnapshot settlement = settlementDueSnapshot("SUPPLIER", req.supplierId(), req.businessDate());

        jdbcTemplate.update("""
                update purchase_order
                set supplier_id = ?, business_date = ?, settlement_days_snapshot = ?, due_date = ?, expected_date = ?, expected_warehouse_id=?, source_type = ?, source_note = ?,
                    price_includes_tax=?,tax_rate_summary=?,tax_rule_snapshot=?,
                    original_amount = ?, discount_rate = ?, discount_amount = ?, adjustment_amount = ?,
                    amount_excluding_tax=?,tax_amount=?,tax_rounding_difference=?,total_quantity = ?,
                    total_amount = ?, memo = ?
                where id = ?
                """, req.supplierId(), Date.valueOf(req.businessDate()), settlement.days(), Date.valueOf(settlement.dueDate()),
                req.expectedDate() == null ? null : Date.valueOf(req.expectedDate()),
                req.expectedWarehouseId() == null ? 0L : req.expectedWarehouseId(),
                sourceType(req.sourceType()), blank(req.sourceNote()), bool(prepared.tax().priceIncludesTax()),
                prepared.tax().taxRateSummary(), prepared.tax().ruleSnapshot(), prepared.originalAmount(),
                nvl(req.discountRate()), prepared.discountAmount(), prepared.adjustmentAmount(),
                prepared.tax().amountExcludingTax(), prepared.tax().taxAmount(), prepared.tax().roundingDifference(),
                prepared.totalQuantity(), prepared.totalAmount(), blank(req.memo()), purchaseOrderId);

        jdbcTemplate.update("delete from purchase_order_item where purchase_order_id = ?", purchaseOrderId);
        insertPurchaseOrderItems(purchaseOrderId, prepared.items());
        refreshInitialOrderRevision("PURCHASE_ORDER", purchaseOrderId);
        return purchaseOrderId;
    }

    @Transactional
    public Long confirmPurchaseOrder(Long purchaseOrderId) {
        PurchaseEditRow purchaseOrder = lockPurchaseForEdit(purchaseOrderId);
        if ("CANCELLED".equals(purchaseOrder.status())) {
            throw new BusinessException("已取消的采购订单不能确认订货");
        }
        if (!"DRAFT".equals(purchaseOrder.status())) {
            return purchaseOrderId;
        }
        requirePartnerRole(purchaseOrder.supplierId(), "SUPPLIER");
        Integer itemCount = jdbcTemplate.queryForObject("""
                select count(*) from purchase_order_item
                where purchase_order_id = ? and status = 'ENABLED'
                """, Integer.class, purchaseOrderId);
        if (itemCount == null || itemCount == 0) {
            throw new BusinessException("采购订单没有有效商品明细，不能确认订货");
        }
        String purchaseSourceType = jdbcTemplate.queryForObject(
                "select source_type from purchase_order where id=?", String.class, purchaseOrderId);
        if (!"DIRECT_TRADE".equals(purchaseSourceType)) {
            validatePurchaseAtpCoordinates(purchaseOrderId);
        }

        jdbcTemplate.update("update purchase_order set status = 'ORDERED' where id = ?", purchaseOrderId);
        List<SupplierProductPriceInput> prices = jdbcTemplate.query("""
                select product_id, price_unit, unit_price
                from purchase_order_item
                where purchase_order_id = ? and status = 'ENABLED' and no_charge=0
                """, (rs, rowNum) -> new SupplierProductPriceInput(
                rs.getLong("product_id"),
                rs.getString("price_unit"),
                rs.getBigDecimal("unit_price")
        ), purchaseOrderId);
        for (SupplierProductPriceInput price : prices) {
            upsertSupplierProductPrice(purchaseOrder.supplierId(), price.productId(), price.priceUnit(), price.unitPrice());
        }
        BusinessPostingDtos.PostingResult posting = postingService.post(
                "PURCHASE_ORDER", purchaseOrderId, purchaseOrder.orderNo(), "COMMITMENT_POST",
                purchaseOrder.currentRevisionNo(), purchaseOrder.businessDate(), purchaseOrder.supplierId(), 0L,
                "确认采购订单", purchaseCommitmentEntries(purchaseOrderId, "INCREASE"));
        linkOrderRevisionPosting("PURCHASE_ORDER", purchaseOrderId, purchaseOrder.currentRevisionNo(), posting.batchId());
        return purchaseOrderId;
    }

    @Transactional
    public Long amendPurchaseOrder(Long purchaseOrderId, BusinessPostingDtos.PurchaseAmendment amendment) {
        PurchaseEditRow purchaseOrder = lockPurchaseForEdit(purchaseOrderId);
        if (!List.of("ORDERED", "PARTIAL_RECEIVED").contains(purchaseOrder.status())) {
            throw new BusinessException("只有已确认且尚未全部入库的采购订单可以变更");
        }
        String reason = requiredReason(amendment.reason(), "采购订单变更");
        requirePartnerRole(purchaseOrder.supplierId(), "SUPPLIER");
        PurchaseCreateRequest req = amendment.order();
        if (req.expectedWarehouseId() != null && req.expectedWarehouseId() > 0) requireWarehouse(req.expectedWarehouseId());
        if (!Objects.equals(req.supplierId(), purchaseOrder.supplierId())) {
            throw new BusinessException("已确认采购订单不能变更供应商；请取消未履约订单后重新下单");
        }
        Integer activeInbounds = jdbcTemplate.queryForObject("""
                select count(*) from inbound_order where purchase_order_id=? and status in ('DRAFT','PENDING')
                """, Integer.class, purchaseOrderId);
        if (activeInbounds != null && activeInbounds > 0) {
            throw new BusinessException("该订单存在草稿或待审核入库单，请先处理后再变更");
        }
        Map<Long, String> snapshots = orderItemSnapshots("purchase_order_item", "purchase_order_id", purchaseOrderId);
        Map<Long, String> priceSnapshots = orderItemPriceSnapshots("purchase_order_item", "purchase_order_id", purchaseOrderId);
        Map<Long, Long> historicalProductIds =
                orderItemProductIds("purchase_order_item", "purchase_order_id", purchaseOrderId);
        PreparedPurchaseOrder prepared = preparePurchaseOrder(req, snapshots, priceSnapshots, historicalProductIds,
                storedOrderTaxTerms("PURCHASE_ORDER", purchaseOrderId));
        if (purchaseOrder.paidAmount().compareTo(prepared.totalAmount()) > 0) {
            throw new BusinessException("变更后订单金额小于已付款金额，请先处理付款或供应商预付余额");
        }
        validatePurchaseAmendmentItems(purchaseOrderId, prepared.items());
        String before = orderSnapshot("PURCHASE_ORDER", purchaseOrderId);
        int revisionNo = purchaseOrder.currentRevisionNo() + 1;
        BusinessPostingDtos.PostingResult reversed = postingService.reverseSource(
                "PURCHASE_ORDER", purchaseOrderId, purchaseOrder.orderNo(), "COMMITMENT_REVERSE",
                revisionNo, purchaseOrder.businessDate(), purchaseOrder.supplierId(),
                List.of("COMMITMENT_POST"), reason);

        jdbcTemplate.update("""
                update purchase_order
                set business_date=?,expected_date=?,expected_warehouse_id=?,source_type=?,source_note=?,original_amount=?,discount_rate=?,
                    discount_amount=?,adjustment_amount=?,price_includes_tax=?,tax_rate_summary=?,tax_rule_snapshot=?,
                    amount_excluding_tax=?,tax_amount=?,tax_rounding_difference=?,total_quantity=?,total_amount=?,memo=?,
                    current_revision_no=?,last_amended_at=now(),
                    payment_status=case when paid_amount>=? and ?>0 then 'PAID'
                                        when paid_amount>0 then 'PARTIAL' else 'UNPAID' end
                where id=?
                """, Date.valueOf(req.businessDate()), req.expectedDate() == null ? null : Date.valueOf(req.expectedDate()),
                req.expectedWarehouseId() == null ? 0L : req.expectedWarehouseId(),
                sourceType(req.sourceType()), blank(req.sourceNote()), prepared.originalAmount(), nvl(req.discountRate()),
                prepared.discountAmount(), prepared.adjustmentAmount(), bool(prepared.tax().priceIncludesTax()),
                prepared.tax().taxRateSummary(), prepared.tax().ruleSnapshot(), prepared.tax().amountExcludingTax(),
                prepared.tax().taxAmount(), prepared.tax().roundingDifference(), prepared.totalQuantity(), prepared.totalAmount(),
                blank(req.memo()), revisionNo, prepared.totalAmount(), prepared.totalAmount(), purchaseOrderId);
        applyPurchaseAmendmentItems(purchaseOrderId, prepared.items());
        refreshPurchaseOrderReceiptStatus(purchaseOrderId);
        validatePurchaseAtpCoordinates(purchaseOrderId);
        BusinessPostingDtos.PostingResult posted = postingService.post(
                "PURCHASE_ORDER", purchaseOrderId, purchaseOrder.orderNo(), "COMMITMENT_POST",
                revisionNo, req.businessDate(), purchaseOrder.supplierId(), 0L, reason,
                purchaseCommitmentEntries(purchaseOrderId, "INCREASE"));
        recordOrderRevision("PURCHASE_ORDER", purchaseOrderId, purchaseOrder.orderNo(), revisionNo,
                "AMENDMENT", reason, before, orderSnapshot("PURCHASE_ORDER", purchaseOrderId),
                reversed.batchId(), posted.batchId());
        return purchaseOrderId;
    }

    public PackagingDtos.Plan purchaseAmendmentPackagingPlan(Long purchaseOrderId, Long itemId,
                                                               PackagingDtos.PlanRequest request) {
        String snapshot = orderItemPackagingSnapshot(
                "purchase_order_item", "purchase_order_id", purchaseOrderId, itemId);
        return packagingService.planFromSnapshot(snapshot, request);
    }

    private void validatePurchaseAtpCoordinates(Long purchaseOrderId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select business_date,expected_date,expected_warehouse_id
                from purchase_order where id=?
                """, purchaseOrderId);
        if (rows.isEmpty()) throw new BusinessException("采购订单不存在");
        Map<String, Object> row = rows.get(0);
        Long warehouseId = ((Number) row.get("expected_warehouse_id")).longValue();
        if (warehouseId <= 0) throw new BusinessException("请先选择预计入库仓库");
        if (row.get("expected_date") == null) throw new BusinessException("请先填写预计到货日期");
        LocalDate businessDate = ((Date) row.get("business_date")).toLocalDate();
        LocalDate expectedDate = ((Date) row.get("expected_date")).toLocalDate();
        if (expectedDate.isBefore(businessDate)) throw new BusinessException("预计到货日期不能早于采购日期");
        requireWarehouse(warehouseId);
    }

    public List<BusinessPostingDtos.OrderRevisionRow> orderRevisions(String orderType, Long orderId) {
        return postingService.orderRevisions(orderType, orderId);
    }

    @Transactional
    public Long cancelPurchaseOrder(Long purchaseOrderId, String reason) {
        PurchaseEditRow purchaseOrder = lockPurchaseForEdit(purchaseOrderId);
        if ("CANCELLED".equals(purchaseOrder.status())) {
            return purchaseOrderId;
        }
        if (!List.of("DRAFT", "ORDERED").contains(purchaseOrder.status())) {
            throw new BusinessException("只有暂存或尚未履约的已确认采购订单可以作废");
        }
        if (purchaseOrder.receivedQuantity().compareTo(BigDecimal.ZERO) != 0
                || purchaseOrder.paidAmount().compareTo(BigDecimal.ZERO) != 0) {
            throw new BusinessException("采购订单已有到货或付款记录，不能作废");
        }
        String cancelReason = requiredReason(reason, "采购订单作废");
        int revisionNo = purchaseOrder.currentRevisionNo();
        Long reversedBatchId = 0L;
        if ("ORDERED".equals(purchaseOrder.status())) {
            revisionNo++;
            BusinessPostingDtos.PostingResult reversed = postingService.reverseSource(
                    "PURCHASE_ORDER", purchaseOrderId, purchaseOrder.orderNo(), "COMMITMENT_REVERSE",
                    revisionNo, purchaseOrder.businessDate(), purchaseOrder.supplierId(),
                    List.of("COMMITMENT_POST"), cancelReason);
            reversedBatchId = reversed.batchId();
        }
        String before = orderSnapshot("PURCHASE_ORDER", purchaseOrderId);
        jdbcTemplate.update("""
                update purchase_order
                set status = 'CANCELLED', cancel_reason = ?, cancelled_at = now(),current_revision_no=?
                where id = ?
                """, cancelReason, revisionNo, purchaseOrderId);
        recordOrderRevision("PURCHASE_ORDER", purchaseOrderId, purchaseOrder.orderNo(), revisionNo,
                "CANCEL", cancelReason, before, orderSnapshot("PURCHASE_ORDER", purchaseOrderId),
                reversedBatchId, 0L);
        return purchaseOrderId;
    }

    private OrderTaxTerms partnerOrderTaxTerms(Long partnerId, String role) {
        boolean customer = "CUSTOMER".equals(role);
        String profileTable = customer ? "partner_customer_profile" : "partner_supplier_profile";
        List<OrderTaxTerms> terms = jdbcTemplate.query("""
                select coalesce(x.tax_included,1) price_includes_tax,
                       coalesce(x.tax_rate,0) tax_rate,coalesce(x.invoice_type,'NONE') invoice_type
                from partner p left join %s x on x.partner_id=p.id where p.id=?
                """.formatted(profileTable), (rs, rowNum) -> new OrderTaxTerms(
                rs.getInt("price_includes_tax") == 1, rs.getBigDecimal("tax_rate"), Map.of(),
                "PARTNER_PROFILE|" + role + "|" + rs.getString("invoice_type")), partnerId);
        if (terms.isEmpty()) throw new BusinessException("往来单位不存在");
        return terms.get(0);
    }

    private OrderTaxTerms storedOrderTaxTerms(String orderType, Long orderId) {
        String headerTable = "PURCHASE_ORDER".equals(orderType) ? "purchase_order" : "sales_order";
        String itemTable = "PURCHASE_ORDER".equals(orderType) ? "purchase_order_item" : "sales_order_item";
        String orderColumn = "PURCHASE_ORDER".equals(orderType) ? "purchase_order_id" : "sales_order_id";
        Map<Long, BigDecimal> rates = new LinkedHashMap<>();
        jdbcTemplate.query("select id,tax_rate from " + itemTable + " where " + orderColumn + "=? and status='ENABLED'",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> rates.put(rs.getLong("id"), rs.getBigDecimal("tax_rate")), orderId);
        List<OrderTaxTerms> terms = jdbcTemplate.query("select price_includes_tax,tax_rule_snapshot from " + headerTable + " where id=?",
                (rs, rowNum) -> new OrderTaxTerms(rs.getInt("price_includes_tax") == 1,
                        rates.values().stream().findFirst().orElse(BigDecimal.ZERO), Map.copyOf(rates),
                        rs.getString("tax_rule_snapshot")), orderId);
        if (terms.isEmpty()) throw new BusinessException("订单不存在");
        return terms.get(0);
    }

    private TaxPriceContract.Document refreshFlowDocumentTax(String flowType, Long documentId, Long partnerId,
                                                               Long sourceOrderId, BigDecimal discountRate,
                                                               BigDecimal adjustmentAmount) {
        boolean inbound = "INBOUND".equals(flowType);
        String headerTable = inbound ? "inbound_order" : "outbound_order";
        String itemTable = inbound ? "inbound_item" : "outbound_item";
        String documentColumn = inbound ? "inbound_id" : "outbound_id";
        String sourceItemColumn = inbound ? "purchase_order_item_id" : "sales_order_item_id";
        String enteredPriceColumn = inbound ? "unit_cost" : "unit_price";
        String role = inbound ? "SUPPLIER" : "CUSTOMER";
        String sourceOrderType = inbound ? "PURCHASE_ORDER" : "SALES_ORDER";
        OrderTaxTerms terms = sourceOrderId != null && sourceOrderId > 0
                ? storedOrderTaxTerms(sourceOrderType, sourceOrderId)
                : partnerOrderTaxTerms(partnerId, role);
        List<FlowTaxItem> items = jdbcTemplate.query("select id," + sourceItemColumn
                        + " source_item_id,pricing_quantity," + enteredPriceColumn + " entered_price from "
                        + itemTable + " where " + documentColumn + "=? order by id",
                (rs, rowNum) -> new FlowTaxItem(rs.getLong("id"), rs.getLong("source_item_id"),
                        rs.getBigDecimal("pricing_quantity"), rs.getBigDecimal("entered_price")), documentId);
        TaxPriceContract.Document tax = TaxPriceContract.calculate(terms.priceIncludesTax(), items.stream()
                        .map(item -> new TaxPriceContract.Input(item.pricingQuantity(), item.enteredPrice(),
                                terms.rateFor(item.sourceItemId()))).toList(),
                discountRate, adjustmentAmount, flowType + "|" + terms.sourceSnapshot());
        for (int index = 0; index < items.size(); index++) {
            FlowTaxItem item = items.get(index);
            TaxPriceContract.Line line = tax.lines().get(index);
            jdbcTemplate.update("update " + itemTable + " set price_includes_tax=?,tax_rate=?,unit_price_excluding_tax=?,"
                            + "amount=?,amount_excluding_tax=?,tax_amount=?,settlement_total_amount=?,tax_rounding_difference=? where id=?",
                    bool(tax.priceIncludesTax()), line.taxRate(), line.unitPriceExcludingTax(), line.originalGrossAmount(),
                    line.amountExcludingTax(), line.taxAmount(), line.settlementTotalAmount(), line.roundingDifference(), item.id());
        }
        jdbcTemplate.update("update " + headerTable + " set price_includes_tax=?,tax_rate_summary=?,tax_rule_snapshot=?,"
                        + "original_amount=?,amount_excluding_tax=?,tax_amount=?,tax_rounding_difference=?,total_amount=? where id=?",
                bool(tax.priceIncludesTax()), tax.taxRateSummary(), tax.ruleSnapshot(), tax.originalGrossAmount(),
                tax.amountExcludingTax(), tax.taxAmount(), tax.roundingDifference(), tax.totalAmount(), documentId);
        return tax;
    }

    private PreparedPurchaseOrder preparePurchaseOrder(PurchaseCreateRequest req) {
        return preparePurchaseOrder(
                req, Map.of(), Map.of(), Map.of(),
                partnerOrderTaxTerms(req.supplierId(), "SUPPLIER"));
    }

    private PreparedPurchaseOrder preparePurchaseOrder(PurchaseCreateRequest req, Map<Long, String> historicalSnapshots,
                                                        Map<Long, String> historicalPriceSnapshots,
                                                        Map<Long, Long> historicalProductIds,
                                                        OrderTaxTerms taxTerms) {
        BigDecimal totalQuantity = BigDecimal.ZERO;
        List<PreparedPurchaseItem> rawItems = new ArrayList<>();
        List<TaxPriceContract.Input> taxInputs = new ArrayList<>();
        for (PurchaseCreateRequest.Item item : req.items()) {
            boolean historicalItem = item.itemId() != null && item.itemId() > 0;
            if (historicalItem) {
                requireHistoricalProduct(
                        item.productId(), item.itemId(), historicalProductIds, "采购订单");
            } else {
                requireProduct(item.productId());
            }
            String priceUnit = StringUtils.hasText(item.priceUnit()) ? item.priceUnit().trim() : productDefaultUnit(item.productId());
            String historicalPriceSnapshot = item.itemId() == null
                    ? null : historicalPriceSnapshots.get(item.itemId());
            ProductPurchaseOptionsDto.SupplierOfferContext supplierOffer =
                    !historicalItem
                            ? validateExpectedSupplierOffer(
                                    req.supplierId(), item, priceUnit, req.businessDate(), taxTerms)
                            : validateFrozenOrCurrentSupplierOffer(
                                    req.supplierId(), item, priceUnit, req.businessDate(),
                                    taxTerms, historicalPriceSnapshot);
            PackagingDtos.PlanRequest planRequest = new PackagingDtos.PlanRequest(
                    item.quantity(), item.quantityUnit(), item.actualBaseQuantity(), priceUnit, "PURCHASE",
                    item.expectedEffectiveRevisionId(), item.expectedPackagingSnapshotHash(),
                    supplierOffer == null ? null : supplierOffer.supplierOfferRevisionId(),
                    supplierOffer == null ? item.packagingVariantRevisionId()
                            : supplierOffer.packagingVariantRevisionId());
            PackagingDtos.Plan plan = historicalItem
                    ? packagingService.planFromSnapshot(requiredSnapshot(historicalSnapshots, item.itemId()), planRequest)
                    : packagingService.plan(item.productId(), planRequest);
            packagingService.validatePackagingQuantity(plan, item.packageQuantity(), item.packageUnit());
            totalQuantity = totalQuantity.add(plan.baseQuantity());
            BigDecimal price = item.noCharge() ? BigDecimal.ZERO : item.unitPrice();
            taxInputs.add(new TaxPriceContract.Input(plan.pricingQuantity(), price, taxTerms.rateFor(item.itemId())));
            PriceCenterDtos.PriceSnapshot priceSnapshot = priceCenterService.freezeEnteredPrice(
                    "PURCHASE", req.supplierId(), item.productId(), plan.pricingQuantity(), priceUnit,
                    req.businessDate(), price, item.noCharge(),
                    historicalPriceSnapshot);
            priceSnapshot = freezeSupplierOfferPriceSnapshot(
                    priceSnapshot, supplierOffer, price, item.noCharge());
            rawItems.add(new PreparedPurchaseItem(item, priceUnit, plan, price, BigDecimal.ZERO, null, priceSnapshot));
        }
        TaxPriceContract.Document tax = TaxPriceContract.calculate(taxTerms.priceIncludesTax(), taxInputs,
                req.discountRate(), req.adjustmentAmount(), taxTerms.sourceSnapshot());
        List<PreparedPurchaseItem> preparedItems = new ArrayList<>();
        for (int index = 0; index < rawItems.size(); index++) {
            PreparedPurchaseItem raw = rawItems.get(index);
            TaxPriceContract.Line line = tax.lines().get(index);
            preparedItems.add(new PreparedPurchaseItem(raw.item(), raw.priceUnit(), raw.plan(), raw.price(),
                    line.originalGrossAmount(), line, raw.priceSnapshot()));
        }
        return new PreparedPurchaseOrder(totalQuantity, tax.originalGrossAmount(), tax.discountAmount(),
                tax.adjustmentAmount(), tax.totalAmount(), tax, preparedItems);
    }

    private void insertPurchaseOrderItems(Long purchaseOrderId, List<PreparedPurchaseItem> preparedItems) {
        for (PreparedPurchaseItem preparedItem : preparedItems) {
            PurchaseCreateRequest.Item item = preparedItem.item();
            PackagingDtos.Plan plan = preparedItem.plan();
            ProductDocumentSnapshot product = currentProductSnapshot(item.productId());
            insert("""
                    insert into purchase_order_item
                    (purchase_order_id, product_id, product_sku_snapshot, product_name_snapshot, product_spec_snapshot,
                     requested_quantity, requested_unit,
                     quantity, base_unit, price_unit, pricing_quantity, package_quantity, package_unit, packaging_plan_json,
                     packaging_effective_revision_id, packaging_snapshot_hash,
                     price_source_code,price_source_name,price_rule_version_id,price_snapshot_json,no_charge,
                     unit_price,tax_rate,unit_price_excluding_tax,amount,amount_excluding_tax,tax_amount,
                     settlement_total_amount,tax_rounding_difference)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, purchaseOrderId, item.productId(), product.sku(), product.name(), product.spec(),
                    plan.requestedQuantity(), plan.requestedUnit(),
                    plan.baseQuantity(), plan.baseUnit(), preparedItem.priceUnit(), plan.pricingQuantity(),
                    nvl(item.packageQuantity()), blank(item.packageUnit()), plan.snapshotJson(),
                    plan.effectiveRevisionId() == null ? 0 : plan.effectiveRevisionId(), plan.snapshotHash(),
                    preparedItem.priceSnapshot().sourceCode(), preparedItem.priceSnapshot().sourceName(),
                    preparedItem.priceSnapshot().ruleVersionId(), preparedItem.priceSnapshot().snapshotJson(),
                    bool(item.noCharge()), preparedItem.price(), preparedItem.taxLine().taxRate(), preparedItem.taxLine().unitPriceExcludingTax(),
                    preparedItem.amount(), preparedItem.taxLine().amountExcludingTax(), preparedItem.taxLine().taxAmount(),
                    preparedItem.taxLine().settlementTotalAmount(), preparedItem.taxLine().roundingDifference());
        }
    }

    public List<OrderDtos.PurchaseOrderRow> purchaseOrders() {
        return jdbcTemplate.query("""
                select o.*, p.name supplier_name,ew.name expected_warehouse_name
                from purchase_order o
                join partner p on p.id = o.supplier_id
                left join warehouse ew on ew.id=o.expected_warehouse_id
                order by o.id desc
                limit 80
                """, purchaseOrderMapper());
    }

    public OrderDtos.PurchaseOrderDetail purchaseOrderDetail(Long id) {
        List<OrderDtos.PurchaseOrderDetail> headers = jdbcTemplate.query("""
                select o.*, p.name supplier_name,ew.name expected_warehouse_name
                from purchase_order o
                join partner p on p.id = o.supplier_id
                left join warehouse ew on ew.id=o.expected_warehouse_id
                where o.id = ?
                """, (rs, rowNum) -> new OrderDtos.PurchaseOrderDetail(
                rs.getLong("id"),
                rs.getString("order_no"),
                rs.getLong("supplier_id"),
                rs.getString("supplier_name"),
                rs.getDate("business_date").toLocalDate(),
                rs.getDate("expected_date") == null ? null : rs.getDate("expected_date").toLocalDate(),
                rs.getLong("expected_warehouse_id"),
                rs.getString("expected_warehouse_name"),
                rs.getString("source_type"),
                rs.getString("source_note"),
                rs.getBigDecimal("original_amount"),
                rs.getBigDecimal("discount_rate"),
                rs.getBigDecimal("discount_amount"),
                rs.getBigDecimal("adjustment_amount"),
                rs.getInt("price_includes_tax") == 1,
                rs.getString("tax_rate_summary"),
                rs.getString("tax_rule_snapshot"),
                rs.getBigDecimal("amount_excluding_tax"),
                rs.getBigDecimal("tax_amount"),
                rs.getBigDecimal("tax_rounding_difference"),
                rs.getBigDecimal("total_quantity"),
                rs.getBigDecimal("received_quantity"),
                rs.getBigDecimal("total_amount"),
                rs.getBigDecimal("paid_amount"),
                rs.getString("payment_status"),
                rs.getString("status"),
                rs.getString("memo"),
                rs.getString("cancel_reason"),
                rs.getTimestamp("cancelled_at") == null ? null : rs.getTimestamp("cancelled_at").toLocalDateTime(),
                List.of()
        ), id);
        if (headers.isEmpty()) {
            throw new BusinessException("采购订单不存在");
        }
        List<OrderDtos.PurchaseOrderItemDetail> items = jdbcTemplate.query("""
                select i.*,coalesce(nullif(i.product_sku_snapshot,''),p.sku) sku,
                       coalesce(nullif(i.product_name_snapshot,''),p.name) product_name,
                       '' grade,'' temper,'' standard,
                       coalesce(nullif(i.product_spec_snapshot,''),p.spec) spec,
                       coalesce(nullif(i.base_unit,''),p.unit) unit
                from purchase_order_item i
                join product p on p.id = i.product_id
                where i.purchase_order_id = ?
                order by i.id
                """, (rs, rowNum) -> new OrderDtos.PurchaseOrderItemDetail(
                rs.getLong("id"),
                rs.getLong("product_id"),
                rs.getString("sku"),
                rs.getString("product_name"),
                rs.getString("grade"),
                rs.getString("temper"),
                rs.getString("standard"),
                rs.getString("spec"),
                rs.getString("unit"),
                rs.getBigDecimal("quantity"),
                rs.getBigDecimal("received_quantity"),
                rs.getString("price_unit"),
                rs.getBigDecimal("package_quantity"),
                rs.getString("package_unit"),
                rs.getString("price_source_code"), rs.getString("price_source_name"),
                rs.getLong("price_rule_version_id"), rs.getString("price_snapshot_json"),
                rs.getBoolean("no_charge"),
                rs.getBigDecimal("unit_price"),
                rs.getBigDecimal("amount"),
                rs.getBigDecimal("tax_rate"), rs.getBigDecimal("unit_price_excluding_tax"),
                rs.getBigDecimal("amount_excluding_tax"), rs.getBigDecimal("tax_amount"),
                rs.getBigDecimal("settlement_total_amount"), rs.getBigDecimal("tax_rounding_difference"),
                rs.getString("status"),
                rs.getBigDecimal("requested_quantity"), rs.getString("requested_unit"),
                rs.getString("base_unit"), rs.getBigDecimal("pricing_quantity"),
                rs.getString("packaging_plan_json"), rs.getBigDecimal("received_requested_quantity")
        ), id);
        OrderDtos.PurchaseOrderDetail header = headers.get(0);
        return new OrderDtos.PurchaseOrderDetail(
                header.id(), header.orderNo(), header.supplierId(), header.supplierName(),
                header.businessDate(), header.expectedDate(), header.expectedWarehouseId(), header.expectedWarehouseName(),
                header.sourceType(), header.sourceNote(),
                header.originalAmount(), header.discountRate(), header.discountAmount(), header.adjustmentAmount(),
                header.priceIncludesTax(), header.taxRateSummary(), header.taxRuleSnapshot(), header.amountExcludingTax(),
                header.taxAmount(), header.taxRoundingDifference(), header.totalQuantity(),
                header.receivedQuantity(), header.totalAmount(), header.paidAmount(), header.paymentStatus(),
                header.status(), header.memo(), header.cancelReason(), header.cancelledAt(), items);
    }

    @Transactional
    public Long createOutbound(OutboundCreateRequest req) {
        requirePartnerRole(req.customerId(), "CUSTOMER");
        requireWarehouse(req.warehouseId());

        String idempotencyKey = blank(req.idempotencyKey()).trim();
        if (StringUtils.hasText(idempotencyKey)) {
            List<Long> existing = jdbcTemplate.query("select id from outbound_order where idempotency_key = ? for update",
                    (rs, rowNum) -> rs.getLong(1), idempotencyKey);
            if (!existing.isEmpty()) return existing.get(0);
        }
        validateOutboundSource(req.salesOrderId(), req.customerId(), req.warehouseId());
        String entryMode = outboundEntryMode(req.entryMode());
        String completenessStatus = outboundCompletenessStatus(req.completenessStatus());
        String lateEntryReason = blank(req.lateEntryReason()).trim();
        if ("BACKFILL".equals(entryMode) && !StringUtils.hasText(lateEntryReason)) {
            throw new BusinessException("事后补录出库必须填写补录原因");
        }
        boolean pricePending = Boolean.TRUE.equals(req.pricePending());
        if (req.salesOrderId() != null && req.salesOrderId() > 0 && pricePending) {
            throw new BusinessException("销售订单已确认价格，关联出库不能改为待定价；请先走销售订单变更");
        }
        if (!pricePending && req.items().stream().anyMatch(item -> nvl(item.unitPrice()).compareTo(BigDecimal.ZERO) <= 0)) {
            throw new BusinessException("非待定价出库必须填写销售单价");
        }

        BigDecimal totalQuantity = BigDecimal.ZERO;
        BigDecimal packageQuantity = BigDecimal.ZERO;
        BigDecimal originalAmount = BigDecimal.ZERO;
        Map<Long, BigDecimal> baseQuantityBySalesItem = new LinkedHashMap<>();
        Map<Long, BigDecimal> requestedByBatch = new HashMap<>();
        for (OutboundCreateRequest.Item item : req.items()) {
            BatchRow batch = lockBatch(item.batchId());
            long salesOrderItemId = resolveOutboundSalesItem(req.salesOrderId(), item.salesOrderItemId(), batch.productId());
            String priceUnit = StringUtils.hasText(item.priceUnit()) ? item.priceUnit().trim() : productDefaultUnit(batch.productId());
            validateOutboundSalesItemPrice(req.salesOrderId(), salesOrderItemId, priceUnit, item.unitPrice());
            PackagingDtos.Plan plan = outboundPackagingPlan(req.salesOrderId(), salesOrderItemId, item, batch.productId(), priceUnit);
            if (!Objects.equals(batch.warehouseId(), req.warehouseId())) {
                throw new BusinessException("出库仓库与库存批次仓库不一致");
            }
            BigDecimal requestedForBatch = requestedByBatch.merge(batch.id(), plan.baseQuantity(), BigDecimal::add);
            if (batch.quantityAvailable().subtract(batchDraftReservedQuantity(batch.id(), 0L)).compareTo(requestedForBatch) < 0) {
                throw new BusinessException("批次 " + batch.batchNo() + " 可用库存不足");
            }
            packagingService.validateBatchPackagingQuantity(item.batchId(), plan.baseQuantity(), item.packageQuantity(), item.packageUnit());
            totalQuantity = totalQuantity.add(plan.baseQuantity());
            packageQuantity = packageQuantity.add(nvl(item.packageQuantity()));
            originalAmount = originalAmount.add(plan.pricingQuantity().multiply(item.unitPrice()));
            if (salesOrderItemId > 0) baseQuantityBySalesItem.merge(salesOrderItemId, plan.baseQuantity(), BigDecimal::add);
        }
        validateOutboundDraftCapacity(req.salesOrderId(), baseQuantityBySalesItem, 0L,
                Boolean.TRUE.equals(req.allowOverShipment()), req.overShipmentReason());
        BigDecimal totalAmount = pricePending ? BigDecimal.ZERO
                : amountAfterDiscount(originalAmount, req.discountRate(), req.adjustmentAmount());
        String shipmentPaymentPolicy = outboundShipmentPaymentPolicy(req.salesOrderId(), req.shipmentPaymentPolicy());
        BigDecimal shipmentControlAmount = outboundShipmentControlAmount(
                shipmentPaymentPolicy, pricePending, req.shipmentControlAmount(), totalAmount);
        Long addressId = req.addressId() == null ? 0 : req.addressId();
        String addressSnapshot = outboundAddressSnapshot(req.salesOrderId(), req.customerId(), addressId);
        LocalDateTime actualShippedAt = req.actualShippedAt() == null
                ? req.businessDate().atStartOfDay() : req.actualShippedAt();
        SettlementDueSnapshot settlement = settlementDueSnapshot("CUSTOMER", req.customerId(), req.businessDate());

        String orderNo = nextNo("CK");
        Long outboundId = insert("""
                insert into outbound_order
                (order_no, sales_order_id, source_type, source_channel, entry_mode, completeness_status,
                 settlement_status, customer_id, address_id, address_snapshot, warehouse_id, business_date,
                 settlement_days_snapshot, due_date, actual_shipped_at, late_entry_reason, allow_over_shipment, over_shipment_reason, idempotency_key, status,
                 total_quantity, package_quantity, original_amount, discount_rate, adjustment_amount, total_amount,
                 receivable_amount, paid_amount, payment_status, shipment_payment_policy, shipment_control_amount, memo)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'DRAFT', ?, ?, ?, ?, ?, ?, 0, 0, 'UNPAID', ?, ?, ?)
                """, orderNo, req.salesOrderId() == null ? 0 : req.salesOrderId(),
                req.salesOrderId() != null && req.salesOrderId() > 0 ? "SALES_ORDER" : "DIRECT",
                outboundSourceChannel(req.sourceChannel()), entryMode, completenessStatus,
                pricePending ? "PENDING_PRICING" : "READY", req.customerId(),
                addressId, addressSnapshot, req.warehouseId(), Date.valueOf(req.businessDate()),
                settlement.days(), Date.valueOf(settlement.dueDate()),
                Timestamp.valueOf(actualShippedAt), lateEntryReason, bool(req.allowOverShipment()),
                blank(req.overShipmentReason()), StringUtils.hasText(idempotencyKey) ? idempotencyKey : null,
                totalQuantity, packageQuantity, originalAmount, nvl(req.discountRate()), nvl(req.adjustmentAmount()),
                totalAmount, shipmentPaymentPolicy, shipmentControlAmount, blank(req.memo()));

        for (OutboundCreateRequest.Item item : req.items()) {
            BatchRow batch = lockBatch(item.batchId());
            long salesOrderItemId = resolveOutboundSalesItem(req.salesOrderId(), item.salesOrderItemId(), batch.productId());
            String priceUnit = StringUtils.hasText(item.priceUnit()) ? item.priceUnit().trim() : productDefaultUnit(batch.productId());
            PackagingDtos.Plan plan = outboundPackagingPlan(req.salesOrderId(), salesOrderItemId, item, batch.productId(), priceUnit);
            BigDecimal amount = pricePending ? BigDecimal.ZERO : plan.pricingQuantity().multiply(item.unitPrice());
            ProductDocumentSnapshot product = salesItemProductSnapshot(salesOrderItemId, batch.productId());
            Long itemId = insert("""
                    insert into outbound_item
                    (outbound_id, sales_order_item_id, product_id, product_sku_snapshot, product_name_snapshot, product_spec_snapshot,
                     requested_quantity, requested_unit,
                     batch_id, quantity, base_unit, package_quantity, package_unit, price_unit, pricing_quantity,
                     packaging_plan_json, packaging_effective_revision_id, packaging_snapshot_hash,
                     selected_package_instance_ids_json, unit_price, amount)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, outboundId, salesOrderItemId, batch.productId(), product.sku(), product.name(), product.spec(),
                    plan.requestedQuantity(), plan.requestedUnit(), item.batchId(), plan.baseQuantity(), plan.baseUnit(),
                    nvl(item.packageQuantity()), blank(item.packageUnit()), priceUnit, plan.pricingQuantity(),
                    plan.snapshotJson(), plan.effectiveRevisionId() == null ? 0 : plan.effectiveRevisionId(),
                    plan.snapshotHash(), jsonLongIds(item.packageInstanceIds()), item.unitPrice(), amount);

            insert("""
                    insert into outbound_allocation
                    (outbound_id, outbound_item_id, sales_order_item_id, product_id, inventory_batch_id, quantity, status)
                    values (?, ?, ?, ?, ?, ?, 'DRAFT')
                    """, outboundId, itemId, salesOrderItemId,
                    batch.productId(), item.batchId(), plan.baseQuantity());
        }
        if (!pricePending) {
            TaxPriceContract.Document tax = refreshFlowDocumentTax("OUTBOUND", outboundId, req.customerId(),
                    req.salesOrderId(), req.discountRate(), req.adjustmentAmount());
            BigDecimal taxAwareControl = outboundShipmentControlAmount(
                    shipmentPaymentPolicy, false, req.shipmentControlAmount(), tax.totalAmount());
            jdbcTemplate.update("update outbound_order set shipment_control_amount=? where id=?", taxAwareControl, outboundId);
        }
        if (!Boolean.FALSE.equals(req.confirmNow())) confirmOutbound(outboundId);
        return outboundId;
    }

    @Transactional
    public Long updateOutboundDraft(Long outboundId, OutboundCreateRequest req) {
        OutboundLifecycleRow existing = lockOutboundLifecycle(outboundId);
        if (!"DRAFT".equals(existing.status())) throw new BusinessException("只有出库草稿可以修改");
        requirePartnerRole(req.customerId(), "CUSTOMER");
        requireWarehouse(req.warehouseId());
        validateOutboundSource(req.salesOrderId(), req.customerId(), req.warehouseId());
        String entryMode = outboundEntryMode(req.entryMode());
        String lateEntryReason = blank(req.lateEntryReason()).trim();
        if ("BACKFILL".equals(entryMode) && !StringUtils.hasText(lateEntryReason)) {
            throw new BusinessException("事后补录出库必须填写补录原因");
        }
        boolean pricePending = Boolean.TRUE.equals(req.pricePending());
        if (req.salesOrderId() != null && req.salesOrderId() > 0 && pricePending) {
            throw new BusinessException("销售订单已确认价格，关联出库不能改为待定价；请先走销售订单变更");
        }
        BigDecimal totalQuantity = BigDecimal.ZERO;
        BigDecimal packageQuantity = BigDecimal.ZERO;
        BigDecimal originalAmount = BigDecimal.ZERO;
        List<DraftOutboundPreparedItem> prepared = new ArrayList<>();
        Map<Long, BigDecimal> baseQuantityBySalesItem = new LinkedHashMap<>();
        Map<Long, BigDecimal> requestedByBatch = new HashMap<>();
        for (OutboundCreateRequest.Item item : req.items()) {
            BatchRow batch = lockBatch(item.batchId());
            long salesOrderItemId = resolveOutboundSalesItem(req.salesOrderId(), item.salesOrderItemId(), batch.productId());
            String priceUnit = StringUtils.hasText(item.priceUnit()) ? item.priceUnit().trim() : productDefaultUnit(batch.productId());
            validateOutboundSalesItemPrice(req.salesOrderId(), salesOrderItemId, priceUnit, item.unitPrice());
            PackagingDtos.Plan plan = outboundPackagingPlan(req.salesOrderId(), salesOrderItemId, item, batch.productId(), priceUnit);
            if (!Objects.equals(batch.warehouseId(), req.warehouseId())) throw new BusinessException("出库仓库与库存批次仓库不一致");
            BigDecimal requestedForBatch = requestedByBatch.merge(batch.id(), plan.baseQuantity(), BigDecimal::add);
            if (batch.quantityAvailable().subtract(batchDraftReservedQuantity(batch.id(), outboundId)).compareTo(requestedForBatch) < 0)
                throw new BusinessException("批次 " + batch.batchNo() + " 可用库存不足；其他出库草稿已占用部分库存");
            if (!pricePending && nvl(item.unitPrice()).compareTo(BigDecimal.ZERO) <= 0) throw new BusinessException("非待定价出库必须填写销售单价");
            totalQuantity = totalQuantity.add(plan.baseQuantity());
            packageQuantity = packageQuantity.add(nvl(item.packageQuantity()));
            BigDecimal amount = pricePending ? BigDecimal.ZERO : plan.pricingQuantity().multiply(item.unitPrice());
            originalAmount = originalAmount.add(amount);
            prepared.add(new DraftOutboundPreparedItem(item, batch, salesOrderItemId, priceUnit, plan, amount));
            if (salesOrderItemId > 0) baseQuantityBySalesItem.merge(salesOrderItemId, plan.baseQuantity(), BigDecimal::add);
        }
        validateOutboundDraftCapacity(req.salesOrderId(), baseQuantityBySalesItem, outboundId,
                Boolean.TRUE.equals(req.allowOverShipment()), req.overShipmentReason());
        BigDecimal totalAmount = pricePending ? BigDecimal.ZERO : amountAfterDiscount(originalAmount, req.discountRate(), req.adjustmentAmount());
        String shipmentPaymentPolicy = outboundShipmentPaymentPolicy(req.salesOrderId(), req.shipmentPaymentPolicy());
        BigDecimal shipmentControlAmount = outboundShipmentControlAmount(
                shipmentPaymentPolicy, pricePending, req.shipmentControlAmount(), totalAmount);
        Long addressId = req.addressId() == null ? 0 : req.addressId();
        LocalDateTime actualShippedAt = req.actualShippedAt() == null ? req.businessDate().atStartOfDay() : req.actualShippedAt();
        SettlementDueSnapshot settlement = settlementDueSnapshot("CUSTOMER", req.customerId(), req.businessDate());
        jdbcTemplate.update("delete from outbound_allocation where outbound_id=?", outboundId);
        jdbcTemplate.update("delete from outbound_item where outbound_id=?", outboundId);
        jdbcTemplate.update("""
                update outbound_order set sales_order_id=?,source_type=?,source_channel=?,entry_mode=?,
                  completeness_status=?,settlement_status=?,customer_id=?,address_id=?,address_snapshot=?,warehouse_id=?,
                  business_date=?,settlement_days_snapshot=?,due_date=?,actual_shipped_at=?,late_entry_reason=?,allow_over_shipment=?,over_shipment_reason=?,
                  total_quantity=?,package_quantity=?,original_amount=?,discount_rate=?,adjustment_amount=?,total_amount=?,
                  shipment_payment_policy=?,shipment_control_amount=?,shipment_required_amount=0,shipment_funded_amount=0,memo=?
                where id=?
                """, req.salesOrderId() == null ? 0 : req.salesOrderId(),
                req.salesOrderId() != null && req.salesOrderId() > 0 ? "SALES_ORDER" : "DIRECT",
                outboundSourceChannel(req.sourceChannel()), entryMode, outboundCompletenessStatus(req.completenessStatus()),
                pricePending ? "PENDING_PRICING" : "READY", req.customerId(), addressId,
                outboundAddressSnapshot(req.salesOrderId(), req.customerId(), addressId),
                req.warehouseId(), Date.valueOf(req.businessDate()), settlement.days(), Date.valueOf(settlement.dueDate()),
                Timestamp.valueOf(actualShippedAt), lateEntryReason, bool(req.allowOverShipment()), blank(req.overShipmentReason()),
                totalQuantity, packageQuantity, originalAmount, nvl(req.discountRate()), nvl(req.adjustmentAmount()),
                totalAmount, shipmentPaymentPolicy, shipmentControlAmount, blank(req.memo()), outboundId);
        for (DraftOutboundPreparedItem row : prepared) {
            OutboundCreateRequest.Item item = row.item();
            PackagingDtos.Plan plan = row.plan();
            ProductDocumentSnapshot product = salesItemProductSnapshot(row.salesOrderItemId(), row.batch().productId());
            Long itemId = insert("""
                    insert into outbound_item
                    (outbound_id,sales_order_item_id,product_id,product_sku_snapshot,product_name_snapshot,product_spec_snapshot,
                     requested_quantity,requested_unit,batch_id,quantity,base_unit,
                     package_quantity,package_unit,price_unit,pricing_quantity,packaging_plan_json,
                     packaging_effective_revision_id,packaging_snapshot_hash,selected_package_instance_ids_json,unit_price,amount)
                    values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, outboundId, row.salesOrderItemId(), row.batch().productId(), product.sku(), product.name(), product.spec(),
                    plan.requestedQuantity(), plan.requestedUnit(),
                    item.batchId(), plan.baseQuantity(), plan.baseUnit(), nvl(item.packageQuantity()), blank(item.packageUnit()),
                    row.priceUnit(), plan.pricingQuantity(), plan.snapshotJson(),
                    plan.effectiveRevisionId() == null ? 0 : plan.effectiveRevisionId(), plan.snapshotHash(),
                    jsonLongIds(item.packageInstanceIds()), item.unitPrice(), row.amount());
            insert("""
                    insert into outbound_allocation
                    (outbound_id,outbound_item_id,sales_order_item_id,product_id,inventory_batch_id,quantity,status)
                    values (?,?,?,?,?,?,'DRAFT')
                    """, outboundId, itemId, row.salesOrderItemId(), row.batch().productId(), item.batchId(), plan.baseQuantity());
        }
        if (!pricePending) {
            TaxPriceContract.Document tax = refreshFlowDocumentTax("OUTBOUND", outboundId, req.customerId(),
                    req.salesOrderId(), req.discountRate(), req.adjustmentAmount());
            BigDecimal taxAwareControl = outboundShipmentControlAmount(
                    shipmentPaymentPolicy, false, req.shipmentControlAmount(), tax.totalAmount());
            jdbcTemplate.update("update outbound_order set shipment_control_amount=? where id=?", taxAwareControl, outboundId);
        }
        if (!Boolean.FALSE.equals(req.confirmNow())) confirmOutbound(outboundId);
        return outboundId;
    }

    @Transactional
    public Long cancelOutboundDraft(Long outboundId, String reason) {
        OutboundLifecycleRow outbound = lockOutboundLifecycle(outboundId);
        if (!"DRAFT".equals(outbound.status())) throw new BusinessException("只有出库草稿可以作废");
        String normalizedReason = blank(reason).trim();
        if (!StringUtils.hasText(normalizedReason)) throw new BusinessException("请填写作废原因");
        if (normalizedReason.length() > 500) throw new BusinessException("作废原因不能超过500字");
        jdbcTemplate.update("update outbound_allocation set status='CANCELLED' where outbound_id=? and status='DRAFT'", outboundId);
        jdbcTemplate.update("update outbound_order set status='CANCELLED',cancel_reason=?,cancelled_at=now() where id=? and status='DRAFT'",
                normalizedReason, outboundId);
        return outboundId;
    }

    @Transactional
    public Long completeOutboundInformation(Long outboundId, String memo) {
        OutboundLifecycleRow outbound = lockOutboundLifecycle(outboundId);
        if (List.of("REVERSED", "CANCELLED").contains(outbound.status())) throw new BusinessException("已反向或已作废出库不能补全资料");
        if (!StringUtils.hasText(memo)) throw new BusinessException("请填写本次补全说明");
        jdbcTemplate.update("""
                update outbound_order set completeness_status='COMPLETE',
                  memo=concat_ws('；',nullif(memo,''),?) where id=?
                """, "资料补全：" + memo.trim(), outboundId);
        return outboundId;
    }

    @Transactional
    public Long confirmOutbound(Long outboundId) {
        return confirmOutbound(outboundId, null);
    }

    @Transactional
    public Long confirmOutbound(Long outboundId, String riskOverrideReason) {
        return confirmOutbound(outboundId, riskOverrideReason, null);
    }

    @Transactional
    public Long confirmOutbound(Long outboundId, String riskOverrideReason, Long approvalId) {
        OutboundLifecycleRow outbound = lockOutboundLifecycle(outboundId);
        if ("SHIPPED".equals(outbound.status())) return outboundId;
        if (!"DRAFT".equals(outbound.status())) throw new BusinessException("只有出库草稿可以确认发货");
        requirePartnerRole(outbound.customerId(), "CUSTOMER");
        List<OutboundLifecycleItemRow> items = outboundLifecycleItems(outboundId);
        if (items.isEmpty()) throw new BusinessException("出库单没有商品明细");
        ShipmentRelease shipmentRelease = validateOutboundShipmentPayment(outbound);
        salesRiskService.enforceOutbound(outboundId, riskOverrideReason, approvalId);

        for (OutboundLifecycleItemRow item : items) {
            BatchRow batch = lockBatch(item.batchId());
            PackagingDtos.Plan plan = storedOutboundPlan(item);
            if (!Objects.equals(batch.warehouseId(), outbound.warehouseId())) {
                throw new BusinessException("出库仓库与库存批次仓库不一致");
            }
            if (batch.quantityAvailable().subtract(batchDraftReservedQuantity(batch.id(), outboundId)).compareTo(plan.baseQuantity()) < 0) {
                throw new BusinessException("批次 " + batch.batchNo() + " 可用库存不足");
            }
            validateOutboundRemaining(outbound, item);
            packagingService.consumeInventoryPackaging(
                    item.batchId(), item.productId(), plan, item.packageQuantity(), item.packageUnit(),
                    parseLongIds(item.selectedPackageInstanceIdsJson()), "OUTBOUND_ITEM", item.id());

            BigDecimal costAmount = item.quantity().compareTo(batch.quantityAvailable()) == 0
                    ? batch.amountCost()
                    : item.quantity().multiply(batch.unitCost()).setScale(2, RoundingMode.HALF_UP);
            jdbcTemplate.update("update outbound_item set inventory_unit_cost=?,inventory_amount_cost=? where id=?",
                    batch.unitCost(), costAmount, item.id());

            jdbcTemplate.update("""
                    update inventory_batch
                    set quantity_available = quantity_available - ?,
                        amount_cost = greatest(amount_cost - ?, 0),
                        package_quantity_available = greatest(package_quantity_available - ?, 0),
                        status = if(quantity_available <= 0, 'OUT', 'AVAILABLE'), shipped_at = now()
                    where id = ?
                    """, item.quantity(), costAmount,
                    item.packageQuantity(), item.batchId());
            changeStockBalance(item.productId(), outbound.warehouseId(), item.quantity().negate(), costAmount.negate());
            insertInventoryMovement("OUT", "OUTBOUND", item.productId(), outbound.warehouseId(), item.batchId(),
                    item.quantity().negate(), batch.unitCost(), costAmount.negate(),
                    "OUTBOUND_ITEM", item.id(), "销售出库 " + outbound.orderNo());
            jdbcTemplate.update("update outbound_allocation set status='ACTIVE' where outbound_item_id=?", item.id());
            if (item.salesOrderItemId() > 0) {
                BigDecimal requestedEquivalent = salesOrderRequestedEquivalent(
                        item.salesOrderItemId(), outbound.salesOrderId(), item.quantity());
                int updated = jdbcTemplate.update("""
                        update sales_order_item set shipped_quantity=shipped_quantity+?,
                          shipped_requested_quantity=shipped_requested_quantity+?
                        where id=? and sales_order_id=? and product_id=?
                        """, item.quantity(), requestedEquivalent, item.salesOrderItemId(),
                        outbound.salesOrderId(), item.productId());
                if (updated != 1) throw new BusinessException("出库商品与销售订单明细不一致");
            }
        }

        jdbcTemplate.update("""
                update outbound_order
                set status='SHIPPED', shipped_at=actual_shipped_at, confirmed_at=now(),
                    shipment_required_amount=?, shipment_funded_amount=?,
                    settled_at=case when settlement_status='READY' then now() else settled_at end,
                    settlement_status=case when settlement_status='READY' then 'SETTLED' else settlement_status end
                where id=? and status='DRAFT'
                """, shipmentRelease.requiredAmount(), shipmentRelease.fundedAmount(), outboundId);
        if (outbound.salesOrderId() > 0) refreshSalesOrderShipmentStatus(outbound.salesOrderId());
        if (!"PENDING_PRICING".equals(outbound.settlementStatus())) {
            postOutboundReceivable(outbound, outbound.totalAmount());
            if (outbound.salesOrderId() <= 0 && !"NONE".equals(outbound.shipmentPaymentPolicy())
                    && outbound.totalAmount().compareTo(BigDecimal.ZERO) > 0) {
                applyReservedOutboundAdvance(outbound, outbound.totalAmount());
            }
        }
        LocalDate businessDate = documentBusinessDate("outbound_order", outboundId, "actual_shipped_at");
        postingService.post("OUTBOUND", outboundId, outbound.orderNo(), "SHIP", 1,
                businessDate, outbound.customerId(), 0L, "销售出库确认",
                outboundShipmentPostingEntries(outboundId));
        if (!"PENDING_PRICING".equals(outbound.settlementStatus())) {
            postingService.post("OUTBOUND", outboundId, outbound.orderNo(), "SETTLE", 1,
                    businessDate, outbound.customerId(), 0L, "销售出库定价结算",
                    receivablePostingEntries(outboundId, outbound.totalAmount(), "INCREASE", "销售出库形成应收"));
        }
        return outboundId;
    }

    @Transactional
    public Long settleOutbound(Long outboundId, OutboundActionRequests.Settlement req) {
        OutboundLifecycleRow outbound = lockOutboundLifecycle(outboundId);
        if (!"SHIPPED".equals(outbound.status())) throw new BusinessException("只有已发货出库单可以结算定价");
        if (!"PENDING_PRICING".equals(outbound.settlementStatus())) throw new BusinessException("该出库单已经完成定价结算");
        requirePartnerRole(outbound.customerId(), "CUSTOMER");
        Map<Long, BigDecimal> prices = new HashMap<>();
        for (OutboundActionRequests.Settlement.ItemPrice item : req.items()) {
            if (prices.put(item.outboundItemId(), item.unitPrice()) != null) {
                throw new BusinessException("出库明细价格不能重复填写");
            }
        }
        List<OutboundLifecycleItemRow> items = outboundLifecycleItems(outboundId);
        if (prices.size() != items.size() || items.stream().anyMatch(item -> !prices.containsKey(item.id()))) {
            throw new BusinessException("请为全部出库明细填写最终销售单价");
        }
        for (OutboundLifecycleItemRow item : items) {
            BigDecimal price = prices.get(item.id());
            BigDecimal amount = item.pricingQuantity().multiply(price);
            jdbcTemplate.update("update outbound_item set unit_price=?, amount=? where id=?",
                    price, amount, item.id());
        }
        TaxPriceContract.Document tax = refreshFlowDocumentTax("OUTBOUND", outboundId, outbound.customerId(),
                outbound.salesOrderId(), req.discountRate(), req.adjustmentAmount());
        BigDecimal originalAmount = tax.originalGrossAmount();
        BigDecimal totalAmount = tax.totalAmount();
        ShipmentRelease shipmentRelease = validateOutboundSettlementPayment(outbound, totalAmount);
        jdbcTemplate.update("""
                update outbound_order set original_amount=?, discount_rate=?, adjustment_amount=?,
                    total_amount=?, shipment_required_amount=?, shipment_funded_amount=?,
                    settlement_status='SETTLED', settled_at=now(),
                    memo=concat_ws('；', nullif(memo,''), ?) where id=?
                """, originalAmount, nvl(req.discountRate()), nvl(req.adjustmentAmount()), totalAmount,
                shipmentRelease.requiredAmount(), shipmentRelease.fundedAmount(),
                "待定价结算：" + blank(req.reason()), outboundId);
        postOutboundReceivable(outbound, totalAmount);
        if (outbound.salesOrderId() <= 0 && !"NONE".equals(outbound.shipmentPaymentPolicy())
                && totalAmount.compareTo(BigDecimal.ZERO) > 0) {
            applyReservedOutboundAdvance(outbound, totalAmount);
        }
        postingService.post("OUTBOUND", outboundId, outbound.orderNo(), "SETTLE", 1,
                documentBusinessDate("outbound_order", outboundId, "actual_shipped_at"), outbound.customerId(),
                0L, requiredReason(req.reason(), "待定价出库结算"),
                receivablePostingEntries(outboundId, totalAmount, "INCREASE", "待定价出库结算形成应收"));
        return outboundId;
    }

    @Transactional
    public Long reverseOutbound(Long outboundId, OutboundActionRequests.Reason req) {
        OutboundLifecycleRow outbound = lockOutboundLifecycle(outboundId);
        if ("REVERSED".equals(outbound.status())) return outboundId;
        if (!"SHIPPED".equals(outbound.status())) throw new BusinessException("只有已发货出库单可以反向出库");
        Integer activeReturns = jdbcTemplate.queryForObject(
                "select count(*) from sales_return_order where outbound_id=? and status='CONFIRMED'", Integer.class, outboundId);
        if (activeReturns != null && activeReturns > 0) throw new BusinessException("该出库单已有销售退货，不能整单反向出库");
        if (outbound.salesOrderId() == 0 && outbound.paidAmount().compareTo(BigDecimal.ZERO) > 0) {
            throw new BusinessException("无订单出库已有回款，请办理销售退货，不能直接反向出库");
        }
        if (outbound.salesOrderId() > 0 && outboundReceivablePosted(outbound)) {
            BigDecimal after = salesOrderReceivableAmount(outbound.salesOrderId()).subtract(outbound.totalAmount());
            BigDecimal settled = salesOrderReceivedAndAdjusted(outbound.salesOrderId());
            if (after.compareTo(settled) < 0) {
                throw new BusinessException("反向出库后应收将小于已核销金额，请先处理回款/票据或改办销售退货");
            }
        }

        for (OutboundLifecycleItemRow item : outboundLifecycleItems(outboundId)) {
            BatchRow batch = lockBatch(item.batchId());
            if (item.inventoryUnitCost() == null || item.inventoryAmountCost() == null)
                throw new BusinessException("出库成本快照缺失，不能直接反向；请先办理数据校正");
            if (batch.unitCost().compareTo(item.inventoryUnitCost()) != 0)
                throw new BusinessException("出库批次成本已发生变化，不能直接反向；请先冲正后续成本调整");
            packagingService.restoreInventoryPackaging(item.batchId(), item.productId(), item.id());
            BigDecimal costAmount = item.inventoryAmountCost();
            jdbcTemplate.update("""
                    update inventory_batch set quantity_available=quantity_available+?,amount_cost=amount_cost+?,
                      package_quantity_available=package_quantity_available+?, status='AVAILABLE' where id=?
                    """, item.quantity(), costAmount, item.packageQuantity(), item.batchId());
            changeStockBalance(item.productId(), outbound.warehouseId(), item.quantity(), costAmount);
            insertInventoryMovement("CX", "CANCEL", item.productId(), outbound.warehouseId(), item.batchId(),
                    item.quantity(), item.inventoryUnitCost(), costAmount, "OUTBOUND_REVERSAL", outboundId,
                    "反向出库 " + outbound.orderNo());
            jdbcTemplate.update("update outbound_allocation set status='REVERSED' where outbound_item_id=?", item.id());
            if (item.salesOrderItemId() > 0) {
                BigDecimal requestedEquivalent = salesOrderRequestedEquivalent(
                        item.salesOrderItemId(), outbound.salesOrderId(), item.quantity());
                jdbcTemplate.update("""
                        update sales_order_item set
                          shipped_quantity=greatest(shipped_quantity-?,0),
                          shipped_requested_quantity=greatest(shipped_requested_quantity-?,0)
                        where id=? and sales_order_id=?
                        """, item.quantity(), requestedEquivalent, item.salesOrderItemId(), outbound.salesOrderId());
            }
        }
        reverseOutboundReceivable(outbound);
        if (outbound.salesOrderId() <= 0) {
            releaseReservedOutboundAdvance(outbound.id(), "反向出库自动释放");
        }
        jdbcTemplate.update("""
                update outbound_order set status='REVERSED', reversal_no=?, reversal_reason=?, reversed_at=now(),
                  receivable_amount=0, payment_status='UNPAID' where id=?
                """, nextNo("CX"), blank(req.reason()), outboundId);
        if (outbound.salesOrderId() > 0) refreshSalesOrderShipmentStatus(outbound.salesOrderId());
        postingService.reverseSource("OUTBOUND", outboundId, outbound.orderNo(), "REVERSE", 1,
                LocalDate.now(), outbound.customerId(), List.of("SHIP", "SETTLE"),
                requiredReason(req.reason(), "反向出库"));
        return outboundId;
    }

    @Transactional
    public Long createSalesReturn(OutboundActionRequests.SalesReturnCreate req) {
        OutboundLifecycleRow outbound = lockOutboundLifecycle(req.outboundId());
        requirePartnerRole(outbound.customerId(), "CUSTOMER");
        if (!"SHIPPED".equals(outbound.status())) throw new BusinessException("只能对已发货出库单办理销售退货");
        if ("PENDING_PRICING".equals(outbound.settlementStatus())) {
            throw new BusinessException("价格待定出库必须先完成最终定价；不继续交易时请办理整单反向出库");
        }
        Map<Long, BigDecimal> quantities = new LinkedHashMap<>();
        for (OutboundActionRequests.SalesReturnCreate.Item item : req.items()) {
            if (quantities.put(item.outboundItemId(), item.quantity()) != null) throw new BusinessException("退货明细不能重复");
        }
        List<OutboundLifecycleItemRow> sourceItems = outboundLifecycleItems(outbound.id()).stream()
                .filter(item -> quantities.containsKey(item.id())).toList();
        if (sourceItems.size() != quantities.size()) throw new BusinessException("退货明细不属于当前出库单");

        BigDecimal totalQuantity = BigDecimal.ZERO;
        BigDecimal totalAmount = BigDecimal.ZERO;
        BigDecimal totalNetAmount = BigDecimal.ZERO;
        BigDecimal totalTaxAmount = BigDecimal.ZERO;
        BigDecimal totalRoundingDifference = BigDecimal.ZERO;
        Map<Long, ReturnTaxSnapshot> returnTax = new LinkedHashMap<>();
        for (OutboundLifecycleItemRow item : sourceItems) {
            BigDecimal quantity = quantities.get(item.id());
            if (item.returnedQuantity().add(quantity).compareTo(item.quantity()) > 0) {
                throw new BusinessException("退货数量超过原出库可退数量");
            }
            totalQuantity = totalQuantity.add(quantity);
            boolean finalReturn = item.returnedQuantity().add(quantity).compareTo(item.quantity()) == 0;
            BigDecimal amount = finalReturn
                    ? item.settlementTotalAmount().subtract(confirmedSalesReturnValue(item.id(), "amount"))
                    : item.settlementTotalAmount().multiply(quantity).divide(item.quantity(), 2, RoundingMode.HALF_UP);
            BigDecimal net = finalReturn
                    ? item.amountExcludingTax().subtract(confirmedSalesReturnValue(item.id(), "amount_excluding_tax"))
                    : item.amountExcludingTax().multiply(quantity).divide(item.quantity(), 2, RoundingMode.HALF_UP);
            BigDecimal tax = amount.subtract(net).setScale(2, RoundingMode.HALF_UP);
            BigDecimal difference = tax.subtract(net.multiply(item.taxRate())
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP));
            returnTax.put(item.id(), new ReturnTaxSnapshot(amount, net, tax, difference));
            totalAmount = totalAmount.add(amount);
            totalNetAmount = totalNetAmount.add(net);
            totalTaxAmount = totalTaxAmount.add(tax);
            totalRoundingDifference = totalRoundingDifference.add(difference);
        }
        BigDecimal outstandingBefore = outbound.salesOrderId() > 0
                ? salesOrderOutstandingAmount(outbound.salesOrderId())
                : outbound.receivableAmount().subtract(outbound.paidAmount()).max(BigDecimal.ZERO);
        BigDecimal advanceCredit = totalAmount.subtract(outstandingBefore).max(BigDecimal.ZERO);
        Long returnId = insert("""
                insert into sales_return_order
                (return_no,outbound_id,sales_order_id,customer_id,warehouse_id,business_date,status,
                 price_includes_tax,tax_rate_summary,tax_rule_snapshot,total_quantity,total_amount,amount_excluding_tax,
                 tax_amount,tax_rounding_difference,receivable_reduction,advance_credit_amount,reason,memo,confirmed_at)
                values (?,?,?,?,?,?,'CONFIRMED',?,?,?,?,?,?,?,?,?,?,?,?,now())
                """, nextNo("XT"), outbound.id(), outbound.salesOrderId(), outbound.customerId(), outbound.warehouseId(),
                Date.valueOf(req.businessDate()), bool(sourceItems.stream().allMatch(OutboundLifecycleItemRow::priceIncludesTax)),
                sourceItems.stream().map(item -> item.taxRate().setScale(4, RoundingMode.HALF_UP).toPlainString() + "%")
                        .distinct().collect(java.util.stream.Collectors.joining(";")),
                "SOURCE_OUTBOUND_PROPORTION_HALF_UP_V1", totalQuantity, totalAmount, totalNetAmount,
                totalTaxAmount, totalRoundingDifference,
                totalAmount.min(outstandingBefore), advanceCredit, blank(req.reason()), blank(req.memo()));

        for (OutboundLifecycleItemRow item : sourceItems) {
            BigDecimal quantity = quantities.get(item.id());
            ReturnTaxSnapshot tax = returnTax.get(item.id());
            BigDecimal amount = tax.amount();
            BatchRow batch = lockBatch(item.batchId());
            BigDecimal costAmount = quantity.multiply(batch.unitCost()).setScale(2, RoundingMode.HALF_UP);
            insert("""
                    insert into sales_return_item
                    (sales_return_id,outbound_item_id,product_id,inventory_batch_id,quantity,base_unit,unit_price,
                     price_includes_tax,tax_rate,amount,amount_excluding_tax,tax_amount,tax_rounding_difference,
                     inventory_unit_cost,inventory_amount_cost)
                    values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, returnId, item.id(), item.productId(), item.batchId(), quantity, item.baseUnit(), item.unitPrice(),
                    bool(item.priceIncludesTax()), item.taxRate(), amount, tax.amountExcludingTax(), tax.taxAmount(),
                    tax.roundingDifference(), batch.unitCost(), costAmount);
            jdbcTemplate.update("update outbound_item set returned_quantity=returned_quantity+? where id=?", quantity, item.id());
            jdbcTemplate.update("update inventory_batch set quantity_available=quantity_available+?,amount_cost=amount_cost+?, status='AVAILABLE' where id=?",
                    quantity, costAmount, item.batchId());
            changeStockBalance(item.productId(), outbound.warehouseId(), quantity, costAmount);
            packagingService.receiveReturnedBaseQuantity(item.batchId(), item.productId(), quantity, item.baseUnit(), returnId);
            insertInventoryMovement("TH", "RETURN", item.productId(), outbound.warehouseId(), item.batchId(),
                    quantity, batch.unitCost(), costAmount, "SALES_RETURN", returnId, "销售退货 " + outbound.orderNo());
        }
        reduceReceivableForSalesReturn(outbound, returnId, totalAmount, advanceCredit);
        postingService.post("SALES_RETURN", returnId, salesReturnNo(returnId), "POST", 1,
                req.businessDate(), outbound.customerId(), 0L, requiredReason(req.reason(), "销售退货"),
                salesReturnPostingEntries(returnId, totalAmount, "INCREASE", "DECREASE"));
        return returnId;
    }

    @Transactional
    public Long reverseSalesReturn(Long returnId, OutboundActionRequests.Reason req) {
        SalesReturnLockRow salesReturn = lockSalesReturn(returnId);
        if ("REVERSED".equals(salesReturn.status())) return returnId;
        if (!"CONFIRMED".equals(salesReturn.status())) throw new BusinessException("只有已确认销售退货可以冲正");
        if (salesReturn.advanceCreditAmount().compareTo(BigDecimal.ZERO) > 0
                && partnerAccountBalance(salesReturn.customerId(), "CUSTOMER_ADVANCE")
                .compareTo(salesReturn.advanceCreditAmount()) < 0) {
            throw new BusinessException("本次退货形成的客户预收余额已经被使用或退款，不能冲正退货");
        }
        OutboundLifecycleRow outbound = lockOutboundLifecycle(salesReturn.outboundId());
        List<SalesReturnItemRow> items = salesReturnItems(returnId);
        for (SalesReturnItemRow item : items) {
            BatchRow batch = lockBatch(item.batchId());
            if (batch.quantityAvailable().compareTo(item.quantity()) < 0) {
                throw new BusinessException("退回库存已经被再次使用，不能冲正退货");
            }
            if (item.inventoryUnitCost() == null || item.inventoryAmountCost() == null)
                throw new BusinessException("销售退货成本快照缺失，不能冲正；请先办理数据校正");
            if (batch.unitCost().compareTo(item.inventoryUnitCost()) != 0)
                throw new BusinessException("退回批次成本已发生变化，不能直接冲正；请先冲正后续成本调整");
            BigDecimal costAmount = item.inventoryAmountCost();
            int inventoryUpdated = jdbcTemplate.update("""
                    update inventory_batch set quantity_available=quantity_available-?,amount_cost=amount_cost-?,
                      status=if(quantity_available<=0,'OUT','AVAILABLE')
                    where id=? and quantity_available>=? and amount_cost>=?
                    """, item.quantity(), costAmount, item.batchId(), item.quantity(), costAmount);
            if (inventoryUpdated != 1) throw new BusinessException("退回库存或成本已被使用，不能冲正退货");
            changeStockBalance(item.productId(), salesReturn.warehouseId(), item.quantity().negate(), costAmount.negate());
            packagingService.reverseReturnedBaseQuantity(item.batchId(), item.productId(), item.quantity(), item.baseUnit(), returnId);
            jdbcTemplate.update("update outbound_item set returned_quantity=greatest(returned_quantity-?,0) where id=?",
                    item.quantity(), item.outboundItemId());
            insertInventoryMovement("TC", "RETURN_REVERSAL", item.productId(), salesReturn.warehouseId(), item.batchId(),
                    item.quantity().negate(), item.inventoryUnitCost(), costAmount.negate(), "SALES_RETURN_REVERSAL", returnId,
                    "销售退货冲正 " + salesReturn.returnNo());
        }
        restoreReceivableForSalesReturn(outbound, salesReturn);
        jdbcTemplate.update("update sales_return_order set status='REVERSED',reversal_no=?,reversal_reason=?,reversed_at=now() where id=?",
                nextNo("TC"), blank(req.reason()), returnId);
        postingService.reverseSource("SALES_RETURN", returnId, salesReturn.returnNo(), "REVERSE", 1,
                LocalDate.now(), salesReturn.customerId(), List.of("POST"),
                requiredReason(req.reason(), "销售退货冲正"));
        return returnId;
    }

    public List<OutboundActionRequests.SalesReturnRow> salesReturns(Long outboundId) {
        String sql = """
                select r.*,o.order_no outbound_no,so.order_no sales_order_no,p.name customer_name
                from sales_return_order r
                join outbound_order o on o.id=r.outbound_id
                left join sales_order so on so.id=r.sales_order_id
                join partner p on p.id=r.customer_id
                where 1=1
                """;
        List<Object> args = new ArrayList<>();
        if (outboundId != null && outboundId > 0) {
            sql += " and r.outbound_id=?";
            args.add(outboundId);
        }
        sql += " order by r.id desc limit 100";
        return jdbcTemplate.query(sql, (rs, rowNum) -> new OutboundActionRequests.SalesReturnRow(
                rs.getLong("id"), rs.getString("return_no"), rs.getLong("outbound_id"),
                rs.getString("outbound_no"), rs.getLong("sales_order_id"), rs.getString("sales_order_no"),
                rs.getLong("customer_id"), rs.getString("customer_name"), rs.getDate("business_date").toLocalDate(),
                rs.getString("status"), rs.getBigDecimal("total_quantity"), rs.getBigDecimal("total_amount"),
                rs.getInt("price_includes_tax") == 1, rs.getString("tax_rate_summary"), rs.getString("tax_rule_snapshot"),
                rs.getBigDecimal("amount_excluding_tax"), rs.getBigDecimal("tax_amount"), rs.getBigDecimal("tax_rounding_difference"),
                rs.getBigDecimal("receivable_reduction"), rs.getBigDecimal("advance_credit_amount"),
                rs.getString("reason"), rs.getString("memo"),
                rs.getTimestamp("confirmed_at") == null ? null : rs.getTimestamp("confirmed_at").toLocalDateTime(),
                rs.getString("reversal_no"), rs.getString("reversal_reason"),
                rs.getTimestamp("reversed_at") == null ? null : rs.getTimestamp("reversed_at").toLocalDateTime()
        ), args.toArray());
    }

    @Transactional
    public Long createPayment(PaymentCreateRequest req) {
        if (req.allocations() != null) {
            return createAllocatedPayment(req);
        }
        return createPayment(req, false);
    }

    private Long createPayment(PaymentCreateRequest req, boolean allowAcceptance) {
        requirePartnerRole(req.customerId(), "CUSTOMER");

        Long salesOrderId = req.salesOrderId() == null ? 0L : req.salesOrderId();
        Long outboundId = req.outboundId() == null ? 0L : req.outboundId();
        if (salesOrderId > 0 && outboundId > 0) {
            throw new BusinessException("销售订单和出库单只能选择一个核销");
        }
        if (outboundId == 0 && StringUtils.hasText(req.outboundNo())) {
            outboundId = findOutboundIdByNo(req.outboundNo());
        }

        String paymentMethod = paymentMethod(req.paymentMethod());
        if ("ACCEPTANCE".equals(paymentMethod) && !allowAcceptance) {
            throw new BusinessException("承兑票据必须通过承兑票据模块登记");
        }
        Long businessBankAccountId = resolveBusinessBankAccount(
                paymentMethod, req.businessBankAccountId(), req.receivedDate());
        String accountType = accountPaymentMethod(paymentMethod) ? customerAccountType(paymentMethod) : "";
        String refNo = "";
        boolean hasTarget = salesOrderId > 0 || outboundId > 0;

        if (!hasTarget && accountPaymentMethod(paymentMethod)) {
            throw new BusinessException("使用预收款或奖励金核销时必须选择销售订单或出库单");
        }
        if (!hasTarget && !"BANK".equals(paymentMethod) && !"CASH".equals(paymentMethod)) {
            throw new BusinessException("无单客户预收款仅支持银行转账或现金收款");
        }

        if (outboundId > 0) {
            OutboundPayRow outbound = lockOutbound(outboundId);
            if (!Objects.equals(outbound.customerId(), req.customerId())) {
                throw new BusinessException("回款客户与出库单客户不一致");
            }
            if (salesOrderId == 0 && outbound.salesOrderId() != null && outbound.salesOrderId() > 0) {
                salesOrderId = outbound.salesOrderId();
            }
            if (salesOrderId > 0 && outbound.salesOrderId() != null
                    && outbound.salesOrderId() > 0 && !Objects.equals(salesOrderId, outbound.salesOrderId())) {
                throw new BusinessException("出库单关联的销售订单与本次回款销售订单不一致");
            }
            BigDecimal unpaid = outbound.receivableAmount().subtract(outbound.paidAmount());
            if (unpaid.compareTo(req.amount()) < 0) {
                throw new BusinessException("回款金额超过该出库单未回款金额");
            }
            refNo = outbound.orderNo();
        }
        if (salesOrderId > 0) {
            SalesOrderPayRow salesOrder = lockSalesOrderForPay(salesOrderId);
            if (!Objects.equals(salesOrder.customerId(), req.customerId())) {
                throw new BusinessException("回款客户与销售订单客户不一致");
            }
            BigDecimal unpaid = salesOrder.totalAmount().subtract(salesOrder.receivedAmount()).subtract(salesOrder.adjustedAmount());
            if (unpaid.compareTo(req.amount()) < 0) {
                throw new BusinessException("回款金额超过该销售订单未回款金额");
            }
            refNo = StringUtils.hasText(refNo) ? refNo + " / " + salesOrder.orderNo() : salesOrder.orderNo();
        }
        if (!hasTarget && !accountPaymentMethod(paymentMethod)) {
            accountType = "CUSTOMER_ADVANCE";
        }

        String paymentNo = nextNo("HK");
        Long paymentId = insert("""
                insert into payment
                (payment_no, customer_id, sales_order_id, outbound_id, amount, allocated_amount,
                 unallocated_amount, payment_method, account_type, fund_nature, allocation_mode,
                 business_bank_account_id, received_date, verification_status, memo)
                values (?, ?, ?, ?, ?, ?, 0, ?, ?, ?, ?, ?, ?, ?, ?)
                """, paymentNo, req.customerId(), salesOrderId, outboundId == 0 ? null : outboundId, req.amount(),
                hasTarget ? req.amount() : BigDecimal.ZERO, paymentMethod, accountType,
                hasTarget ? "SETTLEMENT" : "ADVANCE", hasTarget ? "LEGACY_SINGLE" : "ADVANCE",
                businessBankAccountId, Date.valueOf(req.receivedDate()),
                hasTarget ? "VERIFIED" : "UNAPPLIED", blank(req.memo()));

        if (hasTarget) {
            String targetType = outboundId > 0 ? "OUTBOUND" : "SALES_ORDER";
            Long targetId = outboundId > 0 ? outboundId : salesOrderId;
            Long allocationId = insert("""
                    insert into payment_allocation
                    (allocation_no, payment_id, customer_id, target_type, target_id, allocated_amount,
                     status, capture_source)
                    values (?, ?, ?, ?, ?, ?, 'ACTIVE', 'CREATION')
                    """, paymentNo + "-01", paymentId, req.customerId(), targetType, targetId, req.amount());
            recordCustomerAllocationEvent(allocationId, paymentId, "APPLIED", "", "ACTIVE", req.amount(),
                    req.receivedDate(), "收款时核销 " + refNo);
        }

        if (salesOrderId > 0) {
            jdbcTemplate.update("""
                update sales_order
                    set payment_status = case
                          when received_amount + ? + adjusted_amount >= receivable_amount and receivable_amount > 0 then 'PAID'
                          when received_amount + ? + adjusted_amount > 0 then 'PARTIAL'
                          else 'UNPAID'
                        end,
                        received_amount = received_amount + ?
                    where id = ?
                    """, req.amount(), req.amount(), req.amount(), salesOrderId);
        }
        if (outboundId > 0) {
            jdbcTemplate.update("""
                update outbound_order
                    set payment_status = case
                          when paid_amount + ? >= receivable_amount then 'PAID'
                          when paid_amount + ? > 0 then 'PARTIAL'
                          else 'UNPAID'
                        end,
                        paid_amount = paid_amount + ?
                    where id = ?
                    """, req.amount(), req.amount(), req.amount(), outboundId);
        }

        if (accountPaymentMethod(paymentMethod)) {
            changePartnerAccount(req.customerId(), accountType, req.amount().negate(), "PAYMENT", paymentId,
                    "使用" + accountTypeName(accountType) + "核销 " + refNo);
        } else if (!hasTarget) {
            changePartnerAccount(req.customerId(), accountType, req.amount(), "PAYMENT", paymentId,
                    "客户无单预付款 " + paymentNo);
        }

        if (hasTarget) {
            insertReceivableMovement("PAY", req.customerId(), "PAYMENT", paymentId, "CREDIT", req.amount(),
                    "回款核销 " + refNo);
        }
        postingService.post("PAYMENT", paymentId, paymentNo, "POST", 1, req.receivedDate(),
                req.customerId(), 0L, "客户收款", List.of(new BusinessPostingDtos.EntryDraft(
                        hasTarget && "ACCEPTANCE".equals(paymentMethod) ? "RECEIVABLE_ACCEPTANCE" :
                                (hasTarget && "ADVANCE".equals(paymentMethod) ? "CUSTOMER_ADVANCE_SETTLEMENT" :
                                (hasTarget && "REWARD".equals(paymentMethod) ? "CUSTOMER_REWARD_SETTLEMENT" :
                                (hasTarget ? "RECEIVABLE_SETTLEMENT" : "CUSTOMER_ADVANCE"))),
                        hasTarget ? "DECREASE" : "INCREASE", req.customerId(), 0L, 0L, 0L,
                        "PAYMENT", paymentId, BigDecimal.ZERO, "", req.amount(),
                        hasTarget ? "客户收款核销 " + refNo : "客户无单预收 " + paymentNo)));
        return paymentId;
    }

    private Long createAllocatedPayment(PaymentCreateRequest req) {
        requirePartnerRole(req.customerId(), "CUSTOMER");
        if ((req.salesOrderId() != null && req.salesOrderId() > 0)
                || (req.outboundId() != null && req.outboundId() > 0)
                || StringUtils.hasText(req.outboundNo())) {
            throw new BusinessException("多单核销请仅使用分配明细，不要同时填写旧版单据字段");
        }
        String method = paymentMethod(req.paymentMethod());
        if (!"BANK".equals(method) && !"CASH".equals(method)) {
            throw new BusinessException("普通收款仅支持银行转账或现金；预收款、奖励金和承兑票据请使用对应业务模块");
        }
        BigDecimal total = settlementAmount(req.amount(), "收款金额");
        List<NormalizedAllocation> allocations = normalizeAllocations(req.allocations(),
                Set.of("SALES_ORDER", "OUTBOUND"));
        BigDecimal allocated = allocationTotal(allocations);
        if (allocated.compareTo(total) > 0) throw new BusinessException("核销分配金额不能超过本次收款金额");

        Map<AllocationTarget, BigDecimal> deltas = allocationDeltas(allocations, BigDecimal.ONE);
        Map<AllocationTarget, String> targetNos = applyCustomerAllocationDeltas(req.customerId(), deltas);
        Long bankAccountId = resolveBusinessBankAccount(method, req.businessBankAccountId(), req.receivedDate());
        String paymentNo = nextNo("HK");
        BigDecimal unallocated = total.subtract(allocated);
        Long paymentId = insert("""
                insert into payment
                (payment_no,customer_id,sales_order_id,outbound_id,amount,allocated_amount,unallocated_amount,
                 payment_method,account_type,fund_nature,allocation_mode,business_bank_account_id,received_date,
                 verification_status,memo)
                values (?,?,0,null,?,?,?,?,'','SETTLEMENT','MULTI',?,?,?,?)
                """, paymentNo, req.customerId(), total, allocated, unallocated, method, bankAccountId,
                Date.valueOf(req.receivedDate()), allocationStatus(allocated, total), blank(req.memo()));
        insertCustomerAllocations(paymentId, paymentNo, req.customerId(), allocations, targetNos,
                req.receivedDate(), "CREATION", "收款时分配核销");
        if (allocated.signum() > 0) insertReceivableMovement("PAY", req.customerId(), "PAYMENT", paymentId,
                "CREDIT", allocated, "客户普通收款核销 " + paymentNo);
        postingService.post("PAYMENT", paymentId, paymentNo, "POST", 1, req.receivedDate(),
                req.customerId(), 0L, "客户收款",
                customerPaymentPostingEntries(req.customerId(), paymentId, paymentNo, allocated, unallocated));
        return paymentId;
    }

    @Transactional
    public PaymentAllocationDtos.AllocationSummary changePaymentAllocations(
            Long paymentId, PaymentAllocationDtos.AllocationChange req) {
        String reason = requiredReason(req.reason(), "客户收款重分配");
        ensureAllocationDateOpen(req.businessDate());
        PaymentLock payment = lockPayment(paymentId);
        requireSettlementChangeable(payment.fundNature(), payment.verificationStatus(), "客户收款");
        ensureFundSourceNotTaxMatched("PAYMENT", paymentId);
        List<CustomerAllocationLock> active = lockCustomerAllocations(paymentId);
        Set<Long> releases = normalizedReleaseIds(req.releaseAllocationIds());
        Map<Long, CustomerAllocationLock> activeById = new LinkedHashMap<>();
        active.forEach(row -> activeById.put(row.id(), row));
        for (Long id : releases) {
            if (!activeById.containsKey(id)) throw new BusinessException("待释放的客户收款分配行不存在或已处理");
        }
        List<NormalizedAllocation> additions = normalizeAllocations(req.allocations(),
                Set.of("SALES_ORDER", "OUTBOUND"));
        if (releases.isEmpty() && additions.isEmpty()) throw new BusinessException("本次重分配没有任何变更");
        Set<AllocationTarget> remainingTargets = new HashSet<>();
        for (CustomerAllocationLock row : active) {
            if (!releases.contains(row.id())) remainingTargets.add(new AllocationTarget(row.targetType(), row.targetId()));
        }
        for (NormalizedAllocation row : additions) {
            if (!remainingTargets.add(new AllocationTarget(row.targetType(), row.targetId()))) {
                throw new BusinessException("同一收款的同一目标单据只能保留一条有效分配");
            }
        }
        BigDecimal released = active.stream().filter(row -> releases.contains(row.id()))
                .map(CustomerAllocationLock::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal added = allocationTotal(additions);
        if (added.compareTo(payment.unallocatedAmount().add(released)) > 0) {
            throw new BusinessException("新增分配金额超过本次收款可分配余额");
        }
        BigDecimal nextAllocated = payment.allocatedAmount().subtract(released).add(added);
        requireAccountAllocationFullyApplied(payment.paymentMethod(), payment.amount(), nextAllocated,
                active.size() - releases.size() + additions.size(), "客户预收款或奖励金");
        Map<AllocationTarget, BigDecimal> deltas = new LinkedHashMap<>();
        active.stream().filter(row -> releases.contains(row.id())).forEach(row ->
                deltas.merge(new AllocationTarget(row.targetType(), row.targetId()), row.amount().negate(), BigDecimal::add));
        additions.forEach(row -> deltas.merge(new AllocationTarget(row.targetType(), row.targetId()), row.amount(), BigDecimal::add));
        Map<AllocationTarget, String> targetNos = applyCustomerAllocationDeltas(payment.customerId(), deltas);
        for (CustomerAllocationLock row : active) {
            if (!releases.contains(row.id())) continue;
            jdbcTemplate.update("""
                    update payment_allocation set status='RELEASED',release_reason=?,released_at=now()
                    where id=? and status='ACTIVE'
                    """, reason, row.id());
            recordCustomerAllocationEvent(row.id(), paymentId, "RELEASED", "ACTIVE", "RELEASED",
                    row.amount(), req.businessDate(), reason);
        }
        insertCustomerAllocations(paymentId, payment.paymentNo(), payment.customerId(), additions, targetNos,
                req.businessDate(), "REALLOCATION", reason);
        BigDecimal allocationChange = added.subtract(released);
        if (allocationChange.signum() != 0) {
            insertReceivableMovement("PH", payment.customerId(), "PAYMENT_ALLOCATION", paymentId,
                    allocationChange.signum() > 0 ? "CREDIT" : "DEBIT", allocationChange.abs(), reason);
            postCustomerAllocationChange(payment, req.businessDate(), reason, allocationChange);
        }
        BigDecimal nextUnallocated = payment.amount().subtract(nextAllocated);
        jdbcTemplate.update("""
                update payment set allocated_amount=?,unallocated_amount=?,verification_status=?,allocation_mode='MULTI'
                where id=?
                """, nextAllocated, nextUnallocated, allocationStatus(nextAllocated, payment.amount()), paymentId);
        return new PaymentAllocationDtos.AllocationSummary(paymentId, payment.paymentNo(), payment.amount(),
                nextAllocated, nextUnallocated, allocationStatus(nextAllocated, payment.amount()));
    }

    public PaymentAllocationDtos.Detail paymentAllocationDetail(Long paymentId) {
        return allocationDetail(paymentId, false);
    }

    @Transactional
    public Long reversePayment(Long paymentId, OutboundActionRequests.Reason req) {
        String reason = requiredReason(req.reason(), "客户收款冲正");
        PaymentLock payment = lockPayment(paymentId);
        if ("REVERSED".equals(payment.verificationStatus())) return paymentId;
        riskExceptionService.authorizeOrConsume(
                riskExceptionService.resolveTrigger("PAYMENT", paymentId, "REVERSE"),
                new RiskExceptionDtos.InlineAuthorization(reason, req.approvalId()));
        ensureFundSourceNotReconciled("PAYMENT", paymentId);
        ensureFundSourceNotTaxMatched("PAYMENT", paymentId);
        Integer linkedBills = jdbcTemplate.queryForObject(
                "select count(*) from acceptance_bill where settlement_payment_id=?", Integer.class, paymentId);
        if (linkedBills != null && linkedBills > 0) {
            throw new BusinessException("该收款由承兑票据形成，必须通过承兑票据业务办理后续处理");
        }
        Integer activeAllocations = jdbcTemplate.queryForObject("""
                select count(*) from sales_order_advance_allocation
                where source_payment_id=? and status in ('RESERVED','APPLIED')
                """, Integer.class, paymentId);
        if (activeAllocations != null && activeAllocations > 0) {
            throw new BusinessException("该预收款已被销售订单占用或核销，请先释放或冲正占用");
        }
        boolean settlement = "SETTLEMENT".equals(payment.fundNature());
        List<CustomerAllocationLock> allocations = settlement ? lockCustomerAllocations(paymentId) : List.of();
        BigDecimal activeTotal = allocations.stream().map(CustomerAllocationLock::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (settlement && activeTotal.compareTo(payment.allocatedAmount()) != 0) {
            throw new BusinessException("客户收款有效分配合计与收款头金额不一致，不能无痕冲正");
        }
        if (settlement) {
            Map<AllocationTarget, BigDecimal> deltas = new LinkedHashMap<>();
            allocations.forEach(row -> deltas.merge(new AllocationTarget(row.targetType(), row.targetId()),
                    row.amount().negate(), BigDecimal::add));
            applyCustomerAllocationDeltas(payment.customerId(), deltas);
        }
        if (accountPaymentMethod(payment.paymentMethod())) {
            changePartnerAccount(payment.customerId(), payment.accountType(), payment.amount(),
                    "PAYMENT_REVERSAL", paymentId, "客户收款冲正恢复" + accountTypeName(payment.accountType()));
        } else if (!settlement) {
            BigDecimal available = lockCustomerAdvanceBalance(payment.customerId())
                    .subtract(reservedCustomerAdvance(payment.customerId()));
            if (available.compareTo(payment.amount()) < 0)
                throw new BusinessException("该笔无单预收已被占用或使用，当前可冲正余额不足");
            changePartnerAccount(payment.customerId(), payment.accountType(), payment.amount().negate(),
                    "PAYMENT_REVERSAL", paymentId, "客户无单预收冲正");
        }
        if (settlement && payment.allocatedAmount().signum() > 0) insertReceivableMovement("HC", payment.customerId(),
                "PAYMENT_REVERSAL", paymentId, "DEBIT", payment.allocatedAmount(), "客户收款冲正 " + payment.paymentNo());
        LocalDate businessDate = req.businessDate() == null ? LocalDate.now() : req.businessDate();
        for (CustomerAllocationLock allocation : allocations) {
            jdbcTemplate.update("""
                    update payment_allocation set status='REVERSED',reversal_reason=?,reversed_at=now()
                    where id=? and status='ACTIVE'
                    """, reason, allocation.id());
            recordCustomerAllocationEvent(allocation.id(), paymentId, "HEAD_REVERSED", "ACTIVE", "REVERSED",
                    allocation.amount(), businessDate, reason);
        }
        jdbcTemplate.update("""
                update payment set verification_status='REVERSED',reversal_no=?,reversal_reason=?,reversed_at=now()
                where id=?
                """, nextNo("HC"), reason, paymentId);
        postingService.reverseSource("PAYMENT", paymentId, payment.paymentNo(), "REVERSE", 1,
                businessDate, payment.customerId(),
                List.of("POST", "ALLOCATION_CHANGE"), reason);
        return paymentId;
    }

    @Transactional
    public Long createSupplierPayment(SupplierPaymentCreateRequest req) {
        RiskExceptionService.Authorization authorization = riskExceptionService.authorizeOrConsume(
                riskExceptionService.supplierPaymentTrigger(req),
                new RiskExceptionDtos.InlineAuthorization(req.exceptionReason(), req.riskApprovalId()));
        Long paymentId = createSupplierPaymentInternal(req);
        String paymentNo = jdbcTemplate.queryForObject(
                "select payment_no from supplier_payment where id=?", String.class, paymentId);
        riskExceptionService.bindResult(authorization.requestId(), "SUPPLIER_PAYMENT", paymentId, paymentNo);
        return paymentId;
    }

    private Long createSupplierPaymentInternal(SupplierPaymentCreateRequest req) {
        if (req.allocations() != null) {
            return createAllocatedSupplierPayment(req);
        }
        requirePartnerRole(req.supplierId(), "SUPPLIER");

        Long purchaseOrderId = req.purchaseOrderId() == null ? 0L : req.purchaseOrderId();
        Long inboundId = req.inboundId() == null ? 0L : req.inboundId();
        if (purchaseOrderId > 0 && inboundId > 0) {
            throw new BusinessException("采购订单和入库单只能选择一个核销");
        }
        String paymentMethod = paymentMethod(req.paymentMethod());
        Long businessBankAccountId = resolveBusinessBankAccount(
                paymentMethod, req.businessBankAccountId(), req.paidDate());
        String accountType = accountPaymentMethod(paymentMethod) ? supplierAccountType(paymentMethod) : "";
        String refNo = "";
        boolean hasTarget = purchaseOrderId > 0 || inboundId > 0;

        if (!hasTarget && accountPaymentMethod(paymentMethod)) {
            throw new BusinessException("使用预付款或返利金核销时必须选择采购订单或入库单");
        }

        if (inboundId > 0) {
            InboundPayRow inbound = lockInboundForPay(inboundId);
            if (!Objects.equals(inbound.supplierId(), req.supplierId())) {
                throw new BusinessException("付款供应商与入库单供应商不一致");
            }
            if (!"STOCKED".equals(inbound.status())) {
                throw new BusinessException("入库单未审核入库，不可按入库单付款");
            }
            if (purchaseOrderId == 0 && inbound.purchaseOrderId() > 0) {
                purchaseOrderId = inbound.purchaseOrderId();
                hasTarget = true;
            }
            if (purchaseOrderId > 0 && inbound.purchaseOrderId() > 0 && !Objects.equals(purchaseOrderId, inbound.purchaseOrderId())) {
                throw new BusinessException("入库单关联的采购订单与本次付款采购订单不一致");
            }
            BigDecimal unpaid = inbound.payableAmount().subtract(inbound.paidAmount());
            if (inbound.purchaseOrderId() == 0 && unpaid.compareTo(req.amount()) < 0) {
                throw new BusinessException("付款金额超过该入库单未付款金额");
            }
            refNo = inbound.orderNo();
        }

        if (purchaseOrderId > 0) {
            PurchasePayRow purchase = lockPurchaseForPay(purchaseOrderId);
            if (!Objects.equals(purchase.supplierId(), req.supplierId())) {
                throw new BusinessException("付款供应商与采购订单供应商不一致");
            }
            BigDecimal unpaid = purchase.totalAmount().subtract(purchase.paidAmount());
            if (unpaid.compareTo(req.amount()) < 0) {
                throw new BusinessException("付款金额超过该采购订单未付款金额");
            }
            refNo = StringUtils.hasText(refNo) ? refNo + " / " + purchase.orderNo() : purchase.orderNo();
        }
        if (!hasTarget && !accountPaymentMethod(paymentMethod)) {
            accountType = "SUPPLIER_PREPAY";
        }

        String paymentNo = nextNo("FK");
        Long paymentId = insert("""
                insert into supplier_payment
                (payment_no, supplier_id, purchase_order_id, inbound_id, amount, allocated_amount,
                 unallocated_amount, payment_method, account_type, fund_nature, allocation_mode,
                 business_bank_account_id, paid_date, verification_status, memo)
                values (?, ?, ?, ?, ?, ?, 0, ?, ?, ?, ?, ?, ?, ?, ?)
                """, paymentNo, req.supplierId(), purchaseOrderId, inboundId, req.amount(),
                hasTarget ? req.amount() : BigDecimal.ZERO, paymentMethod, accountType,
                hasTarget ? "SETTLEMENT" : "ADVANCE", hasTarget ? "LEGACY_SINGLE" : "ADVANCE",
                businessBankAccountId, Date.valueOf(req.paidDate()),
                hasTarget ? "VERIFIED" : "UNAPPLIED", blank(req.memo()));

        if (hasTarget) {
            String targetType = inboundId > 0 ? "INBOUND" : "PURCHASE_ORDER";
            Long targetId = inboundId > 0 ? inboundId : purchaseOrderId;
            Long allocationId = insert("""
                    insert into supplier_payment_allocation
                    (allocation_no, supplier_payment_id, supplier_id, target_type, target_id, allocated_amount,
                     status, capture_source)
                    values (?, ?, ?, ?, ?, ?, 'ACTIVE', 'CREATION')
                    """, paymentNo + "-01", paymentId, req.supplierId(), targetType, targetId, req.amount());
            recordSupplierAllocationEvent(allocationId, paymentId, "APPLIED", "", "ACTIVE", req.amount(),
                    req.paidDate(), "付款时核销 " + refNo);
        }

        if (purchaseOrderId > 0) {
            jdbcTemplate.update("update purchase_order set paid_amount=paid_amount+? where id=?",
                    req.amount(), purchaseOrderId);
            refreshPurchasePaymentStatus(purchaseOrderId);
        }
        if (inboundId > 0) {
            jdbcTemplate.update("""
                update inbound_order
                    set payment_status = case
                          when paid_amount + ? >= payable_amount and payable_amount > 0 then 'PAID'
                          when paid_amount + ? > 0 then 'PARTIAL'
                          else 'UNPAID'
                        end,
                        paid_amount = paid_amount + ?
                    where id = ?
                    """, req.amount(), req.amount(), req.amount(), inboundId);
        }

        if (accountPaymentMethod(paymentMethod)) {
            changePartnerAccount(req.supplierId(), accountType, req.amount().negate(), "SUPPLIER_PAYMENT", paymentId,
                    "使用" + accountTypeName(accountType) + "核销 " + refNo);
        } else if (!hasTarget) {
            changePartnerAccount(req.supplierId(), accountType, req.amount(), "SUPPLIER_PAYMENT", paymentId,
                    "向供应商预付款/存款 " + paymentNo);
        }

        if (hasTarget) {
            insertPayableMovement("FP", req.supplierId(), "SUPPLIER_PAYMENT", paymentId, "DECREASE", req.amount(),
                    "供应商付款 " + refNo);
        }
        postingService.post("SUPPLIER_PAYMENT", paymentId, paymentNo, "POST", 1, req.paidDate(),
                req.supplierId(), 0L, "供应商付款", List.of(new BusinessPostingDtos.EntryDraft(
                        hasTarget && "ADVANCE".equals(paymentMethod) ? "SUPPLIER_PREPAY_SETTLEMENT" :
                                (hasTarget && "REWARD".equals(paymentMethod) ? "SUPPLIER_REWARD_SETTLEMENT" :
                                (hasTarget ? "PAYABLE_SETTLEMENT" : "SUPPLIER_PREPAY")),
                        hasTarget ? "DECREASE" : "INCREASE", req.supplierId(), 0L, 0L, 0L,
                        "SUPPLIER_PAYMENT", paymentId, BigDecimal.ZERO, "", req.amount(),
                        hasTarget ? "供应商付款核销 " + refNo : "供应商无单预付 " + paymentNo)));
        return paymentId;
    }

    private Long createAllocatedSupplierPayment(SupplierPaymentCreateRequest req) {
        requirePartnerRole(req.supplierId(), "SUPPLIER");
        if ((req.purchaseOrderId() != null && req.purchaseOrderId() > 0)
                || (req.inboundId() != null && req.inboundId() > 0)) {
            throw new BusinessException("多单核销请仅使用分配明细，不要同时填写旧版单据字段");
        }
        String method = paymentMethod(req.paymentMethod());
        if (!"BANK".equals(method) && !"CASH".equals(method)) {
            throw new BusinessException("普通付款仅支持银行转账或现金；预付款和返利金请使用对应业务模块");
        }
        BigDecimal total = settlementAmount(req.amount(), "付款金额");
        List<NormalizedAllocation> allocations = normalizeAllocations(req.allocations(),
                Set.of("PURCHASE_ORDER", "INBOUND"));
        BigDecimal allocated = allocationTotal(allocations);
        if (allocated.compareTo(total) > 0) throw new BusinessException("核销分配金额不能超过本次付款金额");
        Map<AllocationTarget, BigDecimal> deltas = allocationDeltas(allocations, BigDecimal.ONE);
        Map<AllocationTarget, String> targetNos = applySupplierAllocationDeltas(req.supplierId(), deltas);
        Long bankAccountId = resolveBusinessBankAccount(method, req.businessBankAccountId(), req.paidDate());
        String paymentNo = nextNo("FK");
        BigDecimal unallocated = total.subtract(allocated);
        Long paymentId = insert("""
                insert into supplier_payment
                (payment_no,supplier_id,purchase_order_id,inbound_id,amount,allocated_amount,unallocated_amount,
                 payment_method,account_type,fund_nature,allocation_mode,business_bank_account_id,paid_date,
                 verification_status,memo)
                values (?,?,0,0,?,?,?,?,'','SETTLEMENT','MULTI',?,?,?,?)
                """, paymentNo, req.supplierId(), total, allocated, unallocated, method, bankAccountId,
                Date.valueOf(req.paidDate()), allocationStatus(allocated, total), blank(req.memo()));
        insertSupplierAllocations(paymentId, paymentNo, req.supplierId(), allocations, targetNos,
                req.paidDate(), "CREATION", "付款时分配核销");
        if (allocated.signum() > 0) insertPayableMovement("FP", req.supplierId(), "SUPPLIER_PAYMENT", paymentId,
                "DECREASE", allocated, "供应商普通付款核销 " + paymentNo);
        postingService.post("SUPPLIER_PAYMENT", paymentId, paymentNo, "POST", 1, req.paidDate(),
                req.supplierId(), 0L, "供应商付款",
                supplierPaymentPostingEntries(req.supplierId(), paymentId, paymentNo, allocated, unallocated));
        return paymentId;
    }

    @Transactional
    public PaymentAllocationDtos.AllocationSummary changeSupplierPaymentAllocations(
            Long paymentId, PaymentAllocationDtos.AllocationChange req) {
        String reason = requiredReason(req.reason(), "供应商付款重分配");
        ensureAllocationDateOpen(req.businessDate());
        SupplierPaymentLock payment = lockSupplierPayment(paymentId);
        requireSettlementChangeable(payment.fundNature(), payment.verificationStatus(), "供应商付款");
        ensureFundSourceNotTaxMatched("SUPPLIER_PAYMENT", paymentId);
        List<SupplierAllocationLock> active = lockSupplierAllocations(paymentId);
        Set<Long> releases = normalizedReleaseIds(req.releaseAllocationIds());
        Map<Long, SupplierAllocationLock> activeById = new LinkedHashMap<>();
        active.forEach(row -> activeById.put(row.id(), row));
        for (Long id : releases) {
            if (!activeById.containsKey(id)) throw new BusinessException("待释放的供应商付款分配行不存在或已处理");
        }
        List<NormalizedAllocation> additions = normalizeAllocations(req.allocations(),
                Set.of("PURCHASE_ORDER", "INBOUND"));
        if (releases.isEmpty() && additions.isEmpty()) throw new BusinessException("本次重分配没有任何变更");
        Set<AllocationTarget> remainingTargets = new HashSet<>();
        for (SupplierAllocationLock row : active) {
            if (!releases.contains(row.id())) remainingTargets.add(new AllocationTarget(row.targetType(), row.targetId()));
        }
        for (NormalizedAllocation row : additions) {
            if (!remainingTargets.add(new AllocationTarget(row.targetType(), row.targetId()))) {
                throw new BusinessException("同一付款的同一目标单据只能保留一条有效分配");
            }
        }
        BigDecimal released = active.stream().filter(row -> releases.contains(row.id()))
                .map(SupplierAllocationLock::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal added = allocationTotal(additions);
        if (added.compareTo(payment.unallocatedAmount().add(released)) > 0) {
            throw new BusinessException("新增分配金额超过本次付款可分配余额");
        }
        BigDecimal nextAllocated = payment.allocatedAmount().subtract(released).add(added);
        requireAccountAllocationFullyApplied(payment.paymentMethod(), payment.amount(), nextAllocated,
                active.size() - releases.size() + additions.size(), "供应商预付款或返利金");
        Map<AllocationTarget, BigDecimal> deltas = new LinkedHashMap<>();
        active.stream().filter(row -> releases.contains(row.id())).forEach(row ->
                deltas.merge(new AllocationTarget(row.targetType(), row.targetId()), row.amount().negate(), BigDecimal::add));
        additions.forEach(row -> deltas.merge(new AllocationTarget(row.targetType(), row.targetId()), row.amount(), BigDecimal::add));
        Map<AllocationTarget, String> targetNos = applySupplierAllocationDeltas(payment.supplierId(), deltas);
        for (SupplierAllocationLock row : active) {
            if (!releases.contains(row.id())) continue;
            jdbcTemplate.update("""
                    update supplier_payment_allocation set status='RELEASED',release_reason=?,released_at=now()
                    where id=? and status='ACTIVE'
                    """, reason, row.id());
            recordSupplierAllocationEvent(row.id(), paymentId, "RELEASED", "ACTIVE", "RELEASED",
                    row.amount(), req.businessDate(), reason);
        }
        insertSupplierAllocations(paymentId, payment.paymentNo(), payment.supplierId(), additions, targetNos,
                req.businessDate(), "REALLOCATION", reason);
        BigDecimal allocationChange = added.subtract(released);
        if (allocationChange.signum() != 0) {
            insertPayableMovement("FH", payment.supplierId(), "SUPPLIER_PAYMENT_ALLOCATION", paymentId,
                    allocationChange.signum() > 0 ? "DECREASE" : "INCREASE", allocationChange.abs(), reason);
            postSupplierAllocationChange(payment, req.businessDate(), reason, allocationChange);
        }
        BigDecimal nextUnallocated = payment.amount().subtract(nextAllocated);
        jdbcTemplate.update("""
                update supplier_payment set allocated_amount=?,unallocated_amount=?,verification_status=?,allocation_mode='MULTI'
                where id=?
                """, nextAllocated, nextUnallocated, allocationStatus(nextAllocated, payment.amount()), paymentId);
        return new PaymentAllocationDtos.AllocationSummary(paymentId, payment.paymentNo(), payment.amount(),
                nextAllocated, nextUnallocated, allocationStatus(nextAllocated, payment.amount()));
    }

    public PaymentAllocationDtos.Detail supplierPaymentAllocationDetail(Long paymentId) {
        return allocationDetail(paymentId, true);
    }

    @Transactional
    public Long reverseSupplierPayment(Long paymentId, OutboundActionRequests.Reason req) {
        String reason = requiredReason(req.reason(), "供应商付款冲正");
        SupplierPaymentLock payment = lockSupplierPayment(paymentId);
        if ("REVERSED".equals(payment.verificationStatus())) return paymentId;
        riskExceptionService.authorizeOrConsume(
                riskExceptionService.resolveTrigger("SUPPLIER_PAYMENT", paymentId, "REVERSE"),
                new RiskExceptionDtos.InlineAuthorization(reason, req.approvalId()));
        ensureFundSourceNotReconciled("SUPPLIER_PAYMENT", paymentId);
        ensureFundSourceNotTaxMatched("SUPPLIER_PAYMENT", paymentId);
        boolean settlement = "SETTLEMENT".equals(payment.fundNature());
        List<SupplierAllocationLock> allocations = settlement ? lockSupplierAllocations(paymentId) : List.of();
        BigDecimal activeTotal = allocations.stream().map(SupplierAllocationLock::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (settlement && activeTotal.compareTo(payment.allocatedAmount()) != 0) {
            throw new BusinessException("供应商付款有效分配合计与付款头金额不一致，不能无痕冲正");
        }
        if (settlement) {
            Map<AllocationTarget, BigDecimal> deltas = new LinkedHashMap<>();
            allocations.forEach(row -> deltas.merge(new AllocationTarget(row.targetType(), row.targetId()),
                    row.amount().negate(), BigDecimal::add));
            applySupplierAllocationDeltas(payment.supplierId(), deltas);
        }
        if (accountPaymentMethod(payment.paymentMethod())) {
            changePartnerAccount(payment.supplierId(), payment.accountType(), payment.amount(),
                    "SUPPLIER_PAYMENT_REVERSAL", paymentId, "供应商付款冲正恢复" + accountTypeName(payment.accountType()));
        } else if (!settlement) {
            changePartnerAccount(payment.supplierId(), payment.accountType(), payment.amount().negate(),
                    "SUPPLIER_PAYMENT_REVERSAL", paymentId, "供应商无单预付冲正");
        }
        if (settlement && payment.allocatedAmount().signum() > 0) insertPayableMovement("FC", payment.supplierId(),
                "SUPPLIER_PAYMENT_REVERSAL", paymentId, "INCREASE", payment.allocatedAmount(),
                "供应商付款冲正 " + payment.paymentNo());
        LocalDate businessDate = req.businessDate() == null ? LocalDate.now() : req.businessDate();
        for (SupplierAllocationLock allocation : allocations) {
            jdbcTemplate.update("""
                    update supplier_payment_allocation set status='REVERSED',reversal_reason=?,reversed_at=now()
                    where id=? and status='ACTIVE'
                    """, reason, allocation.id());
            recordSupplierAllocationEvent(allocation.id(), paymentId, "HEAD_REVERSED", "ACTIVE", "REVERSED",
                    allocation.amount(), businessDate, reason);
        }
        jdbcTemplate.update("""
                update supplier_payment set verification_status='REVERSED',reversal_no=?,reversal_reason=?,reversed_at=now()
                where id=?
                """, nextNo("FC"), reason, paymentId);
        postingService.reverseSource("SUPPLIER_PAYMENT", paymentId, payment.paymentNo(), "REVERSE", 1,
                businessDate, payment.supplierId(),
                List.of("POST", "ALLOCATION_CHANGE"), reason);
        return paymentId;
    }

    public List<OrderDtos.InboundRow> inbounds(Long supplierId) {
        String sql = """
                select o.*, p.name supplier_name, w.name warehouse_name, po.order_no purchase_order_no,
                       (select group_concat(concat(
                          trim(trailing '.' from trim(trailing '0' from cast(ii.requested_quantity as char))),
                          coalesce(nullif(ii.requested_unit,''),ii.base_unit)
                        ) order by ii.id separator ' / ')
                        from inbound_item ii where ii.inbound_id=o.id) quantity_summary
                from inbound_order o
                join partner p on p.id = o.supplier_id
                join warehouse w on w.id = o.warehouse_id
                left join purchase_order po on po.id = o.purchase_order_id
                where 1 = 1
                """;
        Object[] args = new Object[0];
        if (supplierId != null && supplierId > 0) {
            sql += " and o.supplier_id = ?";
            args = new Object[]{supplierId};
        }
        sql += " order by o.id desc";
        if (args.length == 0) {
            sql += " limit 50";
        }
        return jdbcTemplate.query(sql, inboundMapper(), args);
    }

    public OrderDtos.InboundDetail inboundDetail(Long id) {
        List<OrderDtos.InboundDetail> headers = jdbcTemplate.query("""
                select o.*, p.name supplier_name, w.name warehouse_name, po.order_no purchase_order_no
                from inbound_order o
                join partner p on p.id = o.supplier_id
                join warehouse w on w.id = o.warehouse_id
                left join purchase_order po on po.id = o.purchase_order_id
                where o.id = ?
                """, (rs, rowNum) -> new OrderDtos.InboundDetail(
                rs.getLong("id"),
                rs.getString("order_no"),
                rs.getLong("purchase_order_id"),
                rs.getString("purchase_order_no"),
                rs.getLong("supplier_id"),
                rs.getString("supplier_name"),
                rs.getLong("warehouse_id"),
                rs.getString("warehouse_name"),
                rs.getString("source_type"),
                rs.getString("source_note"),
                rs.getDate("business_date").toLocalDate(),
                rs.getDate("send_date") == null ? null : rs.getDate("send_date").toLocalDate(),
                rs.getString("send_number"),
                rs.getBigDecimal("original_amount"),
                rs.getBigDecimal("discount_rate"),
                rs.getBigDecimal("adjustment_amount"),
                rs.getBigDecimal("total_quantity"),
                rs.getBigDecimal("total_amount"),
                rs.getBigDecimal("payable_amount"),
                rs.getBigDecimal("paid_amount"),
                rs.getString("payment_status"),
                rs.getString("status"),
                rs.getString("rejection_reason"),
                rs.getTimestamp("submitted_at") == null ? null : rs.getTimestamp("submitted_at").toLocalDateTime(),
                rs.getTimestamp("withdrawn_at") == null ? null : rs.getTimestamp("withdrawn_at").toLocalDateTime(),
                rs.getTimestamp("rejected_at") == null ? null : rs.getTimestamp("rejected_at").toLocalDateTime(),
                rs.getTimestamp("audited_at") == null ? null : rs.getTimestamp("audited_at").toLocalDateTime(),
                rs.getString("memo"),
                List.of(),
                List.of()
        ), id);
        if (headers.isEmpty()) {
            throw new BusinessException("入库单不存在");
        }
        List<OrderDtos.InboundItemDetail> items = jdbcTemplate.query("""
                select i.*,coalesce(nullif(i.product_sku_snapshot,''),p.sku) sku,
                       coalesce(nullif(i.product_name_snapshot,''),p.name) product_name,
                       '' grade,'' temper,'' standard,
                       coalesce(nullif(i.product_spec_snapshot,''),p.spec) spec,
                       coalesce(nullif(i.base_unit,''),p.unit) unit,
                       b.quantity_available current_book_quantity,
                       case when b.id is null then null else coalesce(r.reserved_quantity,0) end reserved_quantity,
                       case when b.id is null then null
                            else greatest(b.quantity_available-coalesce(r.reserved_quantity,0),0)
                       end current_free_quantity,
                       coalesce(nt.non_trade_issued_quantity,0) non_trade_issued_quantity
                from inbound_item i
                join product p on p.id = i.product_id
                left join inventory_batch b on b.id=i.inventory_batch_id
                left join (
                  select inventory_batch_id,sum(quantity) reserved_quantity
                  from outbound_allocation
                  where status='DRAFT'
                  group by inventory_batch_id
                ) r on r.inventory_batch_id=b.id
                left join (
                  select ni.batch_id,sum(ni.quantity) non_trade_issued_quantity
                  from inventory_non_trade_item ni
                  join inventory_non_trade_document nd on nd.id=ni.document_id
                  where nd.direction='OUT'
                    and nd.status in ('ISSUED_PENDING_SIGNATURE','CONFIRMED')
                  group by ni.batch_id
                ) nt on nt.batch_id=b.id
                where i.inbound_id = ?
                order by i.id
                """, (rs, rowNum) -> new OrderDtos.InboundItemDetail(
                rs.getLong("id"),
                rs.getLong("product_id"),
                rs.getString("sku"),
                rs.getString("product_name"),
                rs.getString("grade"),
                rs.getString("temper"),
                rs.getString("standard"),
                rs.getString("spec"),
                rs.getString("unit"),
                rs.getLong("inventory_batch_id"),
                rs.getString("batch_no"),
                rs.getString("serial_number"),
                rs.getBigDecimal("quantity"),
                rs.getBigDecimal("current_book_quantity"),
                rs.getBigDecimal("reserved_quantity"),
                rs.getBigDecimal("current_free_quantity"),
                rs.getBigDecimal("non_trade_issued_quantity"),
                rs.getBigDecimal("package_quantity"),
                rs.getString("price_unit"),
                rs.getString("package_unit"),
                rs.getString("package_barcode"),
                rs.getBigDecimal("unit_cost"),
                rs.getBigDecimal("amount"),
                rs.getDate("production_date") == null ? null : rs.getDate("production_date").toLocalDate(),
                rs.getString("shelf_life_mode"),
                rs.getObject("shelf_life_value") == null ? null : rs.getInt("shelf_life_value"),
                rs.getString("shelf_life_unit"),
                rs.getDate("expiration_date") == null ? null : rs.getDate("expiration_date").toLocalDate(),
                rs.getLong("purchase_order_item_id"), rs.getBigDecimal("requested_quantity"),
                rs.getString("requested_unit"), rs.getString("base_unit"),
                rs.getBigDecimal("pricing_quantity"), rs.getString("packaging_plan_json"), List.of()
        ), id);
        Map<Long, List<PackagingDtos.PackageInstanceInput>> packageInstances = jdbcTemplate.query("""
                select p.inbound_item_id,p.package_code,p.actual_base_quantity
                from inbound_package_instance p
                join inbound_item i on i.id=p.inbound_item_id
                where i.inbound_id=? order by p.id
                """, rs -> {
            Map<Long, List<PackagingDtos.PackageInstanceInput>> grouped = new LinkedHashMap<>();
            while (rs.next()) {
                grouped.computeIfAbsent(rs.getLong("inbound_item_id"), ignored -> new ArrayList<>())
                        .add(new PackagingDtos.PackageInstanceInput(
                                rs.getString("package_code"), rs.getBigDecimal("actual_base_quantity")));
            }
            return grouped;
        }, id);
        items = items.stream().map(item -> item.withPackageInstances(
                packageInstances.getOrDefault(item.id(), List.of()))).toList();
        List<OrderDtos.InboundNonTradeRecord> nonTradeRecords = jdbcTemplate.query("""
                select distinct d.id,d.document_no,d.business_type,d.business_date,
                       ni.product_id,ni.batch_id,b.batch_no,ni.quantity,ni.base_unit,
                       d.recipient,d.handler_name,d.status,d.issued_at,ni.line_no
                from inbound_item ii
                join inventory_non_trade_item ni on ni.batch_id=ii.inventory_batch_id
                join inventory_non_trade_document d on d.id=ni.document_id and d.direction='OUT'
                join inventory_batch b on b.id=ni.batch_id
                where ii.inbound_id=?
                  and d.status in ('ISSUED_PENDING_SIGNATURE','CONFIRMED','REVERSED')
                order by d.business_date desc,d.id desc,ni.line_no
                """, (rs, rowNum) -> new OrderDtos.InboundNonTradeRecord(
                rs.getLong("id"),
                rs.getString("document_no"),
                rs.getString("business_type"),
                rs.getDate("business_date").toLocalDate(),
                rs.getLong("product_id"),
                rs.getLong("batch_id"),
                rs.getString("batch_no"),
                rs.getBigDecimal("quantity"),
                rs.getString("base_unit"),
                rs.getString("recipient"),
                rs.getString("handler_name"),
                rs.getString("status"),
                rs.getTimestamp("issued_at") == null ? null : rs.getTimestamp("issued_at").toLocalDateTime()
        ), id);
        OrderDtos.InboundDetail header = headers.get(0);
        return new OrderDtos.InboundDetail(
                header.id(), header.orderNo(), header.purchaseOrderId(), header.purchaseOrderNo(),
                header.supplierId(), header.supplierName(),
                header.warehouseId(), header.warehouseName(), header.sourceType(), header.sourceNote(),
                header.businessDate(), header.sendDate(),
                header.sendNumber(), header.originalAmount(), header.discountRate(), header.adjustmentAmount(),
                header.totalQuantity(), header.totalAmount(), header.payableAmount(), header.paidAmount(),
                header.paymentStatus(), header.status(), header.rejectionReason(), header.submittedAt(),
                header.withdrawnAt(), header.rejectedAt(), header.auditedAt(), header.memo(),
                items, nonTradeRecords);
    }

    public List<OrderDtos.OutboundRow> outbounds(Long customerId, Long salesOrderId) {
        StringBuilder sql = new StringBuilder("""
                select o.*, p.name customer_name, w.name warehouse_name, so.order_no sales_order_no
                from outbound_order o
                join partner p on p.id = o.customer_id
                join warehouse w on w.id = o.warehouse_id
                left join sales_order so on so.id = o.sales_order_id
                where 1 = 1
                """);
        List<Object> args = new ArrayList<>();
        if (customerId != null && customerId > 0) {
            sql.append(" and o.customer_id = ?");
            args.add(customerId);
        }
        if (salesOrderId != null && salesOrderId > 0) {
            sql.append(" and o.sales_order_id = ?");
            args.add(salesOrderId);
        }
        sql.append(" order by o.id desc");
        if (args.isEmpty()) {
            sql.append(" limit 50");
        }
        return jdbcTemplate.query(sql.toString(), outboundMapper(), args.toArray());
    }

    public OrderDtos.OutboundDetail outboundDetail(Long id) {
        List<OrderDtos.OutboundDetail> headers = jdbcTemplate.query("""
                select o.*, p.name customer_name, w.name warehouse_name, so.order_no sales_order_no,
                       case when o.status = 'DRAFT' then case o.shipment_payment_policy
                         when 'ORDER_PAID' then case when o.sales_order_id > 0 then so.total_amount else o.shipment_control_amount end
                         when 'SHIPMENT_PAID' then coalesce(so.receivable_amount, 0) + o.shipment_control_amount
                         else 0 end else o.shipment_required_amount end shipment_required_amount_value,
                       case when o.status = 'DRAFT' then case when o.sales_order_id > 0
                         then coalesce(op.cash_amount, 0) + coalesce(oa.reserved_amount, 0)
                         else greatest(coalesce(ca.balance, 0) - coalesce(car.reserved_amount, 0), 0) end
                         else o.shipment_funded_amount end shipment_funded_amount_value,
                       greatest((case when o.status = 'DRAFT' then case o.shipment_payment_policy
                         when 'ORDER_PAID' then case when o.sales_order_id > 0 then so.total_amount else o.shipment_control_amount end
                         when 'SHIPMENT_PAID' then coalesce(so.receivable_amount, 0) + o.shipment_control_amount
                         else 0 end else o.shipment_required_amount end)
                         - (case when o.status = 'DRAFT' then case when o.sales_order_id > 0
                         then coalesce(op.cash_amount, 0) + coalesce(oa.reserved_amount, 0)
                         else greatest(coalesce(ca.balance, 0) - coalesce(car.reserved_amount, 0), 0) end
                         else o.shipment_funded_amount end), 0) shipment_payment_shortfall_value,
                       greatest(coalesce(ca.balance, 0) - coalesce(car.reserved_amount, 0), 0) customer_advance_available_value,
                       coalesce(nullif(o.address_snapshot, ''), concat_ws(' ', nullif(a.name, ''), nullif(a.contact_person, ''), nullif(a.phone, ''), nullif(a.detail, ''))) address_snapshot_value
                from outbound_order o
                join partner p on p.id = o.customer_id
                join warehouse w on w.id = o.warehouse_id
                left join sales_order so on so.id = o.sales_order_id
                left join partner_address a on a.id = o.address_id
                left join partner_account ca on ca.partner_id = o.customer_id and ca.account_type = 'CUSTOMER_ADVANCE'
                left join (
                  select customer_id, sum(amount) reserved_amount from (
                    select customer_id, amount from sales_order_advance_allocation where status = 'RESERVED'
                    union all
                    select customer_id, amount from outbound_advance_allocation where status = 'RESERVED'
                  ) all_reserved group by customer_id
                ) car on car.customer_id = o.customer_id
                left join (
                  select sales_order_id, sum(amount) cash_amount from payment
                  where verification_status in ('VERIFIED', 'UNAPPLIED') and payment_method in ('BANK', 'CASH', 'ADVANCE')
                  group by sales_order_id
                ) op on op.sales_order_id = o.sales_order_id
                left join (
                  select sales_order_id, sum(amount) reserved_amount from sales_order_advance_allocation
                  where status = 'RESERVED' group by sales_order_id
                ) oa on oa.sales_order_id = o.sales_order_id
                where o.id = ?
                """, (rs, rowNum) -> new OrderDtos.OutboundDetail(
                rs.getLong("id"),
                rs.getString("order_no"),
                rs.getLong("sales_order_id"),
                rs.getString("sales_order_no"),
                rs.getLong("customer_id"),
                rs.getString("customer_name"),
                rs.getLong("address_id"),
                rs.getString("address_snapshot_value"),
                rs.getLong("warehouse_id"),
                rs.getString("warehouse_name"),
                rs.getDate("business_date").toLocalDate(),
                rs.getTimestamp("actual_shipped_at") == null ? null : rs.getTimestamp("actual_shipped_at").toLocalDateTime(),
                rs.getString("source_type"), rs.getString("source_channel"), rs.getString("entry_mode"),
                rs.getString("completeness_status"), rs.getString("settlement_status"),
                rs.getString("late_entry_reason"), rs.getInt("allow_over_shipment") == 1,
                rs.getString("over_shipment_reason"),
                rs.getBigDecimal("original_amount"),
                rs.getBigDecimal("discount_rate"),
                rs.getBigDecimal("adjustment_amount"),
                rs.getBigDecimal("total_quantity"),
                rs.getBigDecimal("package_quantity"),
                rs.getBigDecimal("total_amount"),
                rs.getBigDecimal("receivable_amount"),
                rs.getBigDecimal("paid_amount"),
                rs.getString("payment_status"),
                rs.getString("shipment_payment_policy"),
                rs.getBigDecimal("shipment_control_amount"),
                rs.getBigDecimal("shipment_required_amount_value"),
                rs.getBigDecimal("shipment_funded_amount_value"),
                rs.getBigDecimal("shipment_payment_shortfall_value"),
                rs.getBigDecimal("customer_advance_available_value"),
                rs.getString("status"),
                rs.getString("memo"),
                rs.getString("cancel_reason"),
                rs.getTimestamp("cancelled_at") == null ? null : rs.getTimestamp("cancelled_at").toLocalDateTime(),
                rs.getTimestamp("confirmed_at") == null ? null : rs.getTimestamp("confirmed_at").toLocalDateTime(),
                rs.getTimestamp("settled_at") == null ? null : rs.getTimestamp("settled_at").toLocalDateTime(),
                rs.getString("reversal_no"), rs.getString("reversal_reason"),
                rs.getTimestamp("reversed_at") == null ? null : rs.getTimestamp("reversed_at").toLocalDateTime(),
                List.of()
        ), id);
        if (headers.isEmpty()) {
            throw new BusinessException("出库单不存在");
        }
        List<OrderDtos.OutboundItemDetail> items = jdbcTemplate.query("""
                select i.*,coalesce(nullif(i.product_sku_snapshot,''),p.sku) sku,
                       coalesce(nullif(i.product_name_snapshot,''),p.name) product_name,
                       '' grade,'' temper,'' standard,
                       coalesce(nullif(i.product_spec_snapshot,''),p.spec) spec,
                       coalesce(nullif(i.base_unit,''),p.unit) unit,b.batch_no
                from outbound_item i
                join product p on p.id = i.product_id
                left join inventory_batch b on b.id = i.batch_id
                where i.outbound_id = ?
                order by i.id
                """, (rs, rowNum) -> new OrderDtos.OutboundItemDetail(
                rs.getLong("id"),
                rs.getLong("product_id"),
                rs.getString("sku"),
                rs.getString("product_name"),
                rs.getString("grade"),
                rs.getString("temper"),
                rs.getString("standard"),
                rs.getString("spec"),
                rs.getString("unit"),
                rs.getLong("batch_id"),
                rs.getString("batch_no"),
                rs.getBigDecimal("quantity"),
                rs.getBigDecimal("package_quantity"),
                rs.getString("price_unit"),
                rs.getBigDecimal("unit_price"),
                rs.getBigDecimal("amount"), rs.getLong("sales_order_item_id"),
                rs.getBigDecimal("requested_quantity"), rs.getString("requested_unit"),
                rs.getString("base_unit"), rs.getBigDecimal("pricing_quantity"),
                rs.getString("package_unit"), rs.getString("packaging_plan_json"),
                rs.getString("selected_package_instance_ids_json"), rs.getBigDecimal("returned_quantity")
        ), id);
        OrderDtos.OutboundDetail header = headers.get(0);
        return new OrderDtos.OutboundDetail(
                header.id(), header.orderNo(), header.salesOrderId(), header.salesOrderNo(), header.customerId(),
                header.customerName(), header.addressId(), header.addressSnapshot(), header.warehouseId(), header.warehouseName(),
                header.businessDate(), header.actualShippedAt(), header.sourceType(), header.sourceChannel(),
                header.entryMode(), header.completenessStatus(), header.settlementStatus(), header.lateEntryReason(),
                header.allowOverShipment(), header.overShipmentReason(),
                header.originalAmount(), header.discountRate(), header.adjustmentAmount(), header.totalQuantity(),
                header.packageQuantity(), header.totalAmount(), header.receivableAmount(), header.paidAmount(),
                header.paymentStatus(), header.shipmentPaymentPolicy(), header.shipmentControlAmount(), header.shipmentRequiredAmount(),
                header.shipmentFundedAmount(), header.shipmentPaymentShortfall(), header.customerAdvanceAvailable(),
                header.status(), header.memo(), header.cancelReason(), header.cancelledAt(), header.confirmedAt(), header.settledAt(),
                header.reversalNo(), header.reversalReason(), header.reversedAt(), items);
    }

    public List<OrderDtos.PaymentRow> payments(Long customerId, Long salesOrderId, Long outboundId) {
        String sql = """
                select p.*, c.name customer_name, so.order_no sales_order_no, o.order_no outbound_no,
                       b.account_name business_bank_account_name,
                       (select count(*) from payment_allocation a
                        where a.payment_id=p.id and a.status='ACTIVE') allocation_count
                from payment p
                join partner c on c.id = p.customer_id
                left join sales_order so on so.id = p.sales_order_id
                left join outbound_order o on o.id = p.outbound_id
                left join business_bank_account b on b.id=p.business_bank_account_id
                where 1 = 1
                """;
        List<Object> args = new ArrayList<>();
        if (customerId != null && customerId > 0) {
            sql += " and p.customer_id = ?";
            args.add(customerId);
        }
        if (salesOrderId != null && salesOrderId > 0) {
            sql += """
                     and (p.sales_order_id=? or exists (
                       select 1 from payment_allocation a
                       left join outbound_order ao on a.target_type='OUTBOUND' and ao.id=a.target_id
                       where a.payment_id=p.id and a.status='ACTIVE'
                         and ((a.target_type='SALES_ORDER' and a.target_id=?) or ao.sales_order_id=?)))
                    """;
            args.add(salesOrderId);
            args.add(salesOrderId);
            args.add(salesOrderId);
        }
        if (outboundId != null && outboundId > 0) {
            sql += """
                     and (p.outbound_id=? or exists (
                       select 1 from payment_allocation a where a.payment_id=p.id and a.status='ACTIVE'
                         and a.target_type='OUTBOUND' and a.target_id=?))
                    """;
            args.add(outboundId);
            args.add(outboundId);
        }
        sql += " order by p.id desc";
        if (args.isEmpty()) {
            sql += " limit 50";
        }
        return jdbcTemplate.query(sql, paymentMapper(), args.toArray());
    }

    public List<OrderDtos.SupplierPaymentRow> supplierPayments(Long supplierId, Long purchaseOrderId, Long inboundId) {
        String sql = """
                select p.*, s.name supplier_name, po.order_no purchase_order_no, i.order_no inbound_no,
                       b.account_name business_bank_account_name,
                       (select count(*) from supplier_payment_allocation a
                        where a.supplier_payment_id=p.id and a.status='ACTIVE') allocation_count
                from supplier_payment p
                join partner s on s.id = p.supplier_id
                left join purchase_order po on po.id = p.purchase_order_id
                left join inbound_order i on i.id = p.inbound_id
                left join business_bank_account b on b.id=p.business_bank_account_id
                where 1 = 1
                """;
        List<Object> args = new ArrayList<>();
        if (supplierId != null && supplierId > 0) {
            sql += " and p.supplier_id = ?";
            args.add(supplierId);
        }
        if (purchaseOrderId != null && purchaseOrderId > 0) {
            sql += """
                     and (p.purchase_order_id=? or exists (
                       select 1 from supplier_payment_allocation a
                       left join inbound_order ai on a.target_type='INBOUND' and ai.id=a.target_id
                       where a.supplier_payment_id=p.id and a.status='ACTIVE'
                         and ((a.target_type='PURCHASE_ORDER' and a.target_id=?) or ai.purchase_order_id=?)))
                    """;
            args.add(purchaseOrderId);
            args.add(purchaseOrderId);
            args.add(purchaseOrderId);
        }
        if (inboundId != null && inboundId > 0) {
            sql += """
                     and (p.inbound_id=? or exists (
                       select 1 from supplier_payment_allocation a where a.supplier_payment_id=p.id
                         and a.status='ACTIVE' and a.target_type='INBOUND' and a.target_id=?))
                    """;
            args.add(inboundId);
            args.add(inboundId);
        }
        sql += " order by p.id desc";
        if (args.isEmpty()) {
            sql += " limit 80";
        }
        return jdbcTemplate.query(sql, supplierPaymentMapper(), args.toArray());
    }

    public List<OrderDtos.PayableRow> payables(Long supplierId) {
        String supplierFilter = supplierId != null && supplierId > 0 ? " and o.supplier_id = ?\n" : "\n";
        String sql = """
                select 'PURCHASE_ORDER' source_type, o.id source_id, o.order_no source_no,
                       o.supplier_id partner_id, p.name supplier_name, o.business_date,
                       greatest(o.total_amount-coalesce(r.returned_amount,0),0) total_amount, o.paid_amount,
                       greatest(o.total_amount-coalesce(r.returned_amount,0)-o.paid_amount,0) unpaid_amount,
                       o.settlement_days_snapshot settlement_days, o.due_date,
                       greatest(datediff(current_date,o.due_date),0) overdue_days,
                       case when o.paid_amount>=greatest(o.total_amount-coalesce(r.returned_amount,0),0) then 'PAID'
                            when o.paid_amount>0 then 'PARTIAL' else 'UNPAID' end payment_status, o.status
                from purchase_order o
                join partner p on p.id = o.supplier_id
                left join (
                  select purchase_order_id,sum(payable_reduction_amount) returned_amount from purchase_return_order
                  where status='CONFIRMED' group by purchase_order_id
                ) r on r.purchase_order_id=o.id
                where o.status not in ('DRAFT', 'CANCELLED')
                """ + supplierFilter + """
                union all
                select 'INBOUND' source_type, o.id source_id, o.order_no source_no,
                       o.supplier_id partner_id, p.name supplier_name, o.business_date,
                       greatest(o.payable_amount-coalesce(r.returned_amount,0),0) total_amount, o.paid_amount,
                       greatest(o.payable_amount-coalesce(r.returned_amount,0)-o.paid_amount,0) unpaid_amount,
                       o.settlement_days_snapshot settlement_days, o.due_date,
                       greatest(datediff(current_date,o.due_date),0) overdue_days,
                       case when o.paid_amount>=greatest(o.payable_amount-coalesce(r.returned_amount,0),0) then 'PAID'
                            when o.paid_amount>0 then 'PARTIAL' else 'UNPAID' end payment_status, o.status
                from inbound_order o
                join partner p on p.id = o.supplier_id
                left join (
                  select inbound_id,sum(payable_reduction_amount) returned_amount from purchase_return_order
                  where status='CONFIRMED' group by inbound_id
                ) r on r.inbound_id=o.id
                where o.purchase_order_id = 0
                  and o.status = 'STOCKED'
                  and o.payable_amount > 0
                """ + supplierFilter + " order by business_date desc, source_id desc";
        List<Object> args = new ArrayList<>();
        if (supplierId != null && supplierId > 0) {
            args.add(supplierId);
            args.add(supplierId);
        } else {
            sql += " limit 120";
        }
        return jdbcTemplate.query(sql, (rs, rowNum) -> new OrderDtos.PayableRow(
                rs.getString("source_type"),
                rs.getLong("source_id"),
                rs.getString("source_no"),
                rs.getLong("partner_id"),
                rs.getString("supplier_name"),
                rs.getDate("business_date").toLocalDate(),
                rs.getBigDecimal("total_amount"),
                rs.getBigDecimal("paid_amount"),
                rs.getBigDecimal("unpaid_amount"),
                rs.getInt("settlement_days"),
                rs.getDate("due_date").toLocalDate(),
                rs.getInt("overdue_days"),
                rs.getString("payment_status"),
                rs.getString("status")
        ), args.toArray());
    }

    public List<OrderDtos.ReceivableRow> receivables(Long customerId) {
        String customerFilter = customerId != null && customerId > 0 ? " and o.customer_id = ?\n" : "\n";
        String sql = """
                select 'SALES_ORDER' source_type, o.id source_id, o.order_no source_no,
                       o.customer_id partner_id, p.name customer_name, o.business_date, o.receivable_amount total_amount, o.received_amount paid_amount,
                       greatest(o.receivable_amount - o.received_amount - o.adjusted_amount, 0) unpaid_amount,
                       o.settlement_days_snapshot settlement_days, o.due_date,
                       greatest(datediff(current_date,o.due_date),0) overdue_days,
                       o.payment_status, o.status
                from sales_order o
                join partner p on p.id = o.customer_id
                left join warehouse fw on fw.id = o.fulfillment_warehouse_id
                where o.status not in ('DRAFT', 'CANCELLED') and o.receivable_amount > 0
                """ + customerFilter + """
                union all
                select 'OUTBOUND' source_type, o.id source_id, o.order_no source_no,
                       o.customer_id partner_id, p.name customer_name, o.business_date, o.receivable_amount total_amount, o.paid_amount,
                       greatest(o.receivable_amount - o.paid_amount, 0) unpaid_amount,
                       o.settlement_days_snapshot settlement_days, o.due_date,
                       greatest(datediff(current_date,o.due_date),0) overdue_days,
                       o.payment_status, o.status
                from outbound_order o
                join partner p on p.id = o.customer_id
                where o.sales_order_id = 0
                  and o.status = 'SHIPPED'
                  and o.receivable_amount > 0
                """ + customerFilter + " order by business_date desc, source_id desc";
        List<Object> args = new ArrayList<>();
        if (customerId != null && customerId > 0) {
            args.add(customerId);
            args.add(customerId);
        } else {
            sql += " limit 120";
        }
        return jdbcTemplate.query(sql, (rs, rowNum) -> new OrderDtos.ReceivableRow(
                rs.getString("source_type"),
                rs.getLong("source_id"),
                rs.getString("source_no"),
                rs.getLong("partner_id"),
                rs.getString("customer_name"),
                rs.getDate("business_date").toLocalDate(),
                rs.getBigDecimal("total_amount"),
                rs.getBigDecimal("paid_amount"),
                rs.getBigDecimal("unpaid_amount"),
                rs.getInt("settlement_days"),
                rs.getDate("due_date").toLocalDate(),
                rs.getInt("overdue_days"),
                rs.getString("payment_status"),
                rs.getString("status")
        ), args.toArray());
    }

    public List<ReceivableSettlementDtos.AdjustmentRow> receivableAdjustments(Long customerId, Long salesOrderId) {
        StringBuilder sql = new StringBuilder("""
                select a.*, p.name customer_name, o.order_no sales_order_no
                from receivable_adjustment a
                join partner p on p.id = a.customer_id
                join sales_order o on o.id = a.sales_order_id
                where 1 = 1
                """);
        List<Object> args = new ArrayList<>();
        if (customerId != null && customerId > 0) {
            sql.append(" and a.customer_id = ?");
            args.add(customerId);
        }
        if (salesOrderId != null && salesOrderId > 0) {
            sql.append(" and a.sales_order_id = ?");
            args.add(salesOrderId);
        }
        sql.append(" order by a.id desc limit 200");
        return jdbcTemplate.query(sql.toString(), receivableAdjustmentMapper(), args.toArray());
    }

    @Transactional
    public Long createReceivableAdjustment(ReceivableSettlementDtos.AdjustmentCreate req) {
        SalesOrderPayRow order = lockSalesOrderForPay(req.salesOrderId());
        String adjustmentType = receivableAdjustmentType(req.adjustmentType());
        requirePartnerRole(order.customerId(), "CUSTOMER");
        BigDecimal outstanding = salesOrderOutstanding(order);
        if (req.amount().compareTo(outstanding) > 0) {
            throw new BusinessException("应收调整金额超过订单当前待收金额 " + moneyText(outstanding));
        }
        return insert("""
                insert into receivable_adjustment
                (adjustment_no, customer_id, sales_order_id, adjustment_type, amount, occurred_date, status,
                 reason, evidence_ref)
                values (?, ?, ?, ?, ?, ?, 'DRAFT', ?, ?)
                """, nextNo("YS"), order.customerId(), order.id(), adjustmentType, req.amount(),
                Date.valueOf(req.occurredDate()), req.reason().trim(), blank(req.evidenceRef()));
    }

    @Transactional
    public Long confirmReceivableAdjustment(Long adjustmentId) {
        ReceivableAdjustmentLockRow adjustment = lockReceivableAdjustment(adjustmentId);
        if (!"DRAFT".equals(adjustment.status())) {
            throw new BusinessException("只有暂存的应收调整单可以确认");
        }
        SalesOrderPayRow order = lockSalesOrderForPay(adjustment.salesOrderId());
        requirePartnerRole(order.customerId(), "CUSTOMER");
        BigDecimal outstanding = salesOrderOutstanding(order);
        if (adjustment.amount().compareTo(outstanding) > 0) {
            throw new BusinessException("订单待收金额已变化，应收调整金额不能超过 " + moneyText(outstanding));
        }
        jdbcTemplate.update("update sales_order set adjusted_amount = adjusted_amount + ? where id = ?",
                adjustment.amount(), order.id());
        refreshSalesOrderPaymentStatus(order.id());
        jdbcTemplate.update("""
                update receivable_adjustment set status = 'CONFIRMED', confirmed_at = now()
                where id = ?
                """, adjustmentId);
        insertReceivableMovement("YJ", order.customerId(), "RECEIVABLE_ADJUSTMENT", adjustmentId,
                "CREDIT", adjustment.amount(), receivableAdjustmentTypeName(adjustment.adjustmentType()) + " " + order.orderNo());
        postingService.post("RECEIVABLE_ADJUSTMENT", adjustmentId, adjustmentNo(adjustmentId), "POST", 1,
                documentBusinessDate("receivable_adjustment", adjustmentId, "occurred_date"), order.customerId(),
                0L, "应收调整确认", List.of(new BusinessPostingDtos.EntryDraft(
                        "RECEIVABLE_ADJUSTMENT", "DECREASE", order.customerId(), 0L, 0L, 0L,
                        "RECEIVABLE_ADJUSTMENT", adjustmentId, BigDecimal.ZERO, "", adjustment.amount(),
                        receivableAdjustmentTypeName(adjustment.adjustmentType()) + " " + order.orderNo())));
        return adjustmentId;
    }

    @Transactional
    public Long cancelReceivableAdjustment(Long adjustmentId, ReceivableSettlementDtos.AdjustmentAction req) {
        ReceivableAdjustmentLockRow adjustment = lockReceivableAdjustment(adjustmentId);
        if (!"DRAFT".equals(adjustment.status())) {
            throw new BusinessException("只有暂存的应收调整单可以作废");
        }
        jdbcTemplate.update("""
                update receivable_adjustment
                set status = 'CANCELLED', reversal_reason = ?, reversed_at = now()
                where id = ?
                """, req.reason().trim(), adjustmentId);
        return adjustmentId;
    }

    @Transactional
    public Long reverseReceivableAdjustment(Long adjustmentId, ReceivableSettlementDtos.AdjustmentAction req) {
        ReceivableAdjustmentLockRow adjustment = lockReceivableAdjustment(adjustmentId);
        if (!"CONFIRMED".equals(adjustment.status())) {
            throw new BusinessException("只有已确认的应收调整单可以冲正");
        }
        SalesOrderPayRow order = lockSalesOrderForPay(adjustment.salesOrderId());
        if (order.adjustedAmount().compareTo(adjustment.amount()) < 0) {
            throw new BusinessException("订单累计应收调整金额异常，不能冲正");
        }
        String reversalNo = nextNo("YC");
        jdbcTemplate.update("update sales_order set adjusted_amount = adjusted_amount - ? where id = ?",
                adjustment.amount(), order.id());
        refreshSalesOrderPaymentStatus(order.id());
        jdbcTemplate.update("""
                update receivable_adjustment
                set status = 'REVERSED', reversal_no = ?, reversal_reason = ?, reversed_at = now()
                where id = ?
                """, reversalNo, req.reason().trim(), adjustmentId);
        insertReceivableMovement("YC", order.customerId(), "RECEIVABLE_ADJUSTMENT_REVERSAL", adjustmentId,
                "DEBIT", adjustment.amount(), "冲正" + receivableAdjustmentTypeName(adjustment.adjustmentType()) + " " + order.orderNo());
        postingService.reverseSource("RECEIVABLE_ADJUSTMENT", adjustmentId, adjustmentNo(adjustmentId),
                "REVERSE", 1, LocalDate.now(), order.customerId(), List.of("POST"),
                requiredReason(req.reason(), "应收调整冲正"));
        return adjustmentId;
    }

    public List<ReceivableSettlementDtos.AcceptanceBillRow> acceptanceBills(Long customerId, Long salesOrderId) {
        StringBuilder sql = new StringBuilder("""
                select b.*, p.name customer_name, o.order_no sales_order_no,
                       case when b.status = 'DISCOUNTED' then b.net_proceeds
                            when b.status = 'MATURED' then b.face_amount else 0 end realized_cash_amount
                from acceptance_bill b
                join partner p on p.id = b.customer_id
                join sales_order o on o.id = b.sales_order_id
                where 1 = 1
                """);
        List<Object> args = new ArrayList<>();
        if (customerId != null && customerId > 0) {
            sql.append(" and b.customer_id = ?");
            args.add(customerId);
        }
        if (salesOrderId != null && salesOrderId > 0) {
            sql.append(" and b.sales_order_id = ?");
            args.add(salesOrderId);
        }
        sql.append(" order by b.id desc limit 200");
        return jdbcTemplate.query(sql.toString(), acceptanceBillMapper(), args.toArray());
    }

    public List<ReceivableSettlementDtos.AcceptanceEventRow> acceptanceBillEvents(Long billId) {
        return jdbcTemplate.query("""
                select e.*,b.account_name business_bank_account_name
                from acceptance_bill_event e
                left join business_bank_account b on b.id=e.business_bank_account_id
                where e.bill_id = ? order by e.id desc
                """, acceptanceEventMapper(), billId);
    }

    @Transactional
    public Long createAcceptanceBill(ReceivableSettlementDtos.AcceptanceCreate req) {
        SalesOrderPayRow order = lockSalesOrderForPay(req.salesOrderId());
        if (req.issueDate().isAfter(req.dueDate())) {
            throw new BusinessException("出票日期不能晚于到期日期");
        }
        if (req.receivedDate().isAfter(req.dueDate())) {
            throw new BusinessException("不能登记已经到期的承兑票据");
        }
        BigDecimal outstanding = salesOrderOutstanding(order);
        if (req.faceAmount().compareTo(outstanding) > 0) {
            throw new BusinessException("承兑票面金额超过订单待收金额 " + moneyText(outstanding));
        }
        String billType = acceptanceBillType(req.billType());
        String billNo = nextNo("CD");
        Long paymentId = createPayment(new PaymentCreateRequest(
                order.customerId(), order.id(), null, null, req.faceAmount(), "ACCEPTANCE", null, req.receivedDate(),
                "承兑收票 " + req.instrumentNo() + " / " + blank(req.memo())
        ), true);
        Long billId = insert("""
                insert into acceptance_bill
                (bill_no, instrument_no, bill_type, customer_id, sales_order_id, face_amount, issue_date, due_date,
                 received_date, drawer, acceptor, settlement_payment_id, status, memo)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'HELD', ?)
                """, billNo, req.instrumentNo().trim(), billType, order.customerId(), order.id(), req.faceAmount(),
                Date.valueOf(req.issueDate()), Date.valueOf(req.dueDate()), Date.valueOf(req.receivedDate()),
                blank(req.drawer()), req.acceptor().trim(), paymentId, blank(req.memo()));
        insertAcceptanceEvent(billId, "RECEIVE", req.receivedDate(), BigDecimal.ZERO, BigDecimal.ZERO,
                null, req.acceptor().trim(), "承兑收票并核销销售订单 " + order.orderNo());
        return billId;
    }

    @Transactional
    public Long discountAcceptanceBill(Long billId, ReceivableSettlementDtos.AcceptanceAction req) {
        AcceptanceBillLockRow bill = lockAcceptanceBill(billId);
        requireHeldAcceptance(bill);
        requirePartnerRole(bill.customerId(), "CUSTOMER");
        BigDecimal net = nvl(req.netProceeds());
        if (net.compareTo(BigDecimal.ZERO) <= 0 || net.compareTo(bill.faceAmount()) > 0) {
            throw new BusinessException("贴现实收金额必须大于0且不能超过票面金额");
        }
        if (!StringUtils.hasText(req.bank())) {
            throw new BusinessException("请填写贴现银行");
        }
        BigDecimal fee = bill.faceAmount().subtract(net);
        Long businessBankAccountId = resolveBusinessBankAccount(
                "BANK", req.businessBankAccountId(), req.occurredDate());
        jdbcTemplate.update("""
                update acceptance_bill
                set status = 'DISCOUNTED', discount_date = ?, discount_bank = ?, discount_fee = ?,
                    net_proceeds = ?, disposed_at = now()
                where id = ?
                """, Date.valueOf(req.occurredDate()), req.bank().trim(), fee, net, billId);
        AcceptanceEventRef event = insertAcceptanceEvent(billId, "DISCOUNT", req.occurredDate(), net, fee,
                businessBankAccountId, req.bank().trim(), blank(req.memo()));
        List<BusinessPostingDtos.EntryDraft> entries = new ArrayList<>();
        entries.add(new BusinessPostingDtos.EntryDraft("ACCEPTANCE_CASH_RECEIPT", "INCREASE",
                bill.customerId(), 0L, 0L, 0L, "ACCEPTANCE_EVENT", event.id(), BigDecimal.ZERO, "", net,
                "承兑贴现实收 " + bill.instrumentNo()));
        if (fee.compareTo(BigDecimal.ZERO) > 0) {
            entries.add(new BusinessPostingDtos.EntryDraft("ACCEPTANCE_DISCOUNT_FEE", "INCREASE",
                    bill.customerId(), 0L, 0L, 0L, "ACCEPTANCE_EVENT", event.id(), BigDecimal.ZERO, "", fee,
                    "承兑贴现费用 " + bill.instrumentNo()));
        }
        postingService.post("ACCEPTANCE_EVENT", event.id(), event.no(), "POST", 1, req.occurredDate(),
                bill.customerId(), 0L, "承兑贴现", entries);
        return billId;
    }

    @Transactional
    public Long matureAcceptanceBill(Long billId, ReceivableSettlementDtos.AcceptanceAction req) {
        AcceptanceBillLockRow bill = lockAcceptanceBill(billId);
        requireHeldAcceptance(bill);
        requirePartnerRole(bill.customerId(), "CUSTOMER");
        Long businessBankAccountId = resolveBusinessBankAccount(
                "BANK", req.businessBankAccountId(), req.occurredDate());
        jdbcTemplate.update("update acceptance_bill set status = 'MATURED', disposed_at = now() where id = ?", billId);
        AcceptanceEventRef event = insertAcceptanceEvent(billId, "MATURITY", req.occurredDate(),
                bill.faceAmount(), BigDecimal.ZERO, businessBankAccountId, bill.acceptor(), blank(req.memo()));
        postingService.post("ACCEPTANCE_EVENT", event.id(), event.no(), "POST", 1, req.occurredDate(),
                bill.customerId(), 0L, "承兑到期收款", List.of(new BusinessPostingDtos.EntryDraft(
                        "ACCEPTANCE_CASH_RECEIPT", "INCREASE", bill.customerId(), 0L, 0L, 0L,
                        "ACCEPTANCE_EVENT", event.id(), BigDecimal.ZERO, "", bill.faceAmount(),
                        "承兑到期收款 " + bill.instrumentNo())));
        return billId;
    }

    @Transactional
    public Long endorseAcceptanceBill(Long billId, ReceivableSettlementDtos.AcceptanceAction req) {
        AcceptanceBillLockRow bill = lockAcceptanceBill(billId);
        requireHeldAcceptance(bill);
        requirePartnerRole(bill.customerId(), "CUSTOMER");
        if (!StringUtils.hasText(req.counterparty())) {
            throw new BusinessException("请填写被背书单位");
        }
        jdbcTemplate.update("update acceptance_bill set status = 'ENDORSED', disposed_at = now() where id = ?", billId);
        insertAcceptanceEvent(billId, "ENDORSE", req.occurredDate(), BigDecimal.ZERO, BigDecimal.ZERO,
                null, req.counterparty().trim(), blank(req.memo()));
        return billId;
    }

    @Transactional
    public Long bounceAcceptanceBill(Long billId, ReceivableSettlementDtos.AcceptanceAction req) {
        AcceptanceBillLockRow bill = lockAcceptanceBill(billId);
        if (!"HELD".equals(bill.status()) && !"DISCOUNTED".equals(bill.status())) {
            throw new BusinessException("只有持有中或已贴现的承兑票据可以登记退票追索");
        }
        if (!StringUtils.hasText(req.memo())) {
            throw new BusinessException("请填写退票或追索原因");
        }
        SalesOrderPayRow order = lockSalesOrderForPay(bill.salesOrderId());
        if (order.receivedAmount().compareTo(bill.faceAmount()) < 0) {
            throw new BusinessException("订单已核销金额异常，不能恢复应收");
        }
        jdbcTemplate.update("update payment set verification_status = 'REVERSED' where id = ?", bill.settlementPaymentId());
        jdbcTemplate.update("update sales_order set received_amount = received_amount - ? where id = ?",
                bill.faceAmount(), bill.salesOrderId());
        refreshSalesOrderPaymentStatus(bill.salesOrderId());
        jdbcTemplate.update("update acceptance_bill set status = 'BOUNCED', disposed_at = now() where id = ?", billId);
        insertReceivableMovement("TP", bill.customerId(), "ACCEPTANCE_BOUNCE", billId, "DEBIT", bill.faceAmount(),
                "承兑退票恢复应收 " + bill.instrumentNo());
        BigDecimal cashOut = "DISCOUNTED".equals(bill.status()) ? bill.faceAmount().negate() : BigDecimal.ZERO;
        Long businessBankAccountId = cashOut.compareTo(BigDecimal.ZERO) == 0 ? null :
                resolveBusinessBankAccount("BANK", req.businessBankAccountId(), req.occurredDate());
        AcceptanceEventRef event = insertAcceptanceEvent(billId, "BOUNCE", req.occurredDate(), cashOut,
                BigDecimal.ZERO, businessBankAccountId, blank(req.counterparty()), req.memo().trim());
        if ("DISCOUNTED".equals(bill.status())) {
            postingService.post("ACCEPTANCE_EVENT", event.id(), event.no(), "POST", 1, req.occurredDate(),
                    bill.customerId(), 0L, "已贴现承兑退票追索", List.of(new BusinessPostingDtos.EntryDraft(
                            "ACCEPTANCE_BOUNCE_CASH", "DECREASE", bill.customerId(), 0L, 0L, 0L,
                            "ACCEPTANCE_EVENT", event.id(), BigDecimal.ZERO, "", bill.faceAmount(),
                            "已贴现承兑退票恢复应收 " + bill.instrumentNo())));
        } else {
            String paymentNo = jdbcTemplate.queryForObject("select payment_no from payment where id=?",
                    String.class, bill.settlementPaymentId());
            postingService.reverseSource("PAYMENT", bill.settlementPaymentId(), paymentNo,
                    "REVERSE", 1, req.occurredDate(), bill.customerId(), List.of("POST"),
                    "承兑退票恢复应收：" + req.memo().trim());
        }
        return billId;
    }

    public List<OrderDtos.PartnerAccountRow> partnerAccounts(Long partnerId) {
        String sql = """
                select a.*, p.name partner_name, p.type partner_type
                from partner_account a
                join partner p on p.id = a.partner_id
                where a.account_type in ('CUSTOMER_ADVANCE', 'CUSTOMER_REWARD', 'SUPPLIER_PREPAY', 'SUPPLIER_REWARD')
                """;
        Object[] args = new Object[0];
        if (partnerId != null && partnerId > 0) {
            sql += " and a.partner_id = ?";
            args = new Object[]{partnerId};
        }
        sql += " order by a.updated_at desc, a.id desc limit 120";
        return jdbcTemplate.query(sql, accountMapper(), args);
    }

    public List<OrderDtos.PartnerAccountLogRow> partnerAccountLogs(Long partnerId) {
        String sql = """
                select l.*, p.name partner_name,
                       case
                         when l.source_type = 'PAYMENT' and coalesce(pay.sales_order_id, 0) > 0 then 'SALES_ORDER'
                         when l.source_type = 'PAYMENT' and coalesce(pay.outbound_id, 0) > 0 then 'OUTBOUND'
                         when l.source_type = 'SUPPLIER_PAYMENT' and coalesce(sp.purchase_order_id, 0) > 0 then 'PURCHASE_ORDER'
                         when l.source_type = 'SUPPLIER_PAYMENT' and coalesce(sp.inbound_id, 0) > 0 then 'INBOUND'
                         when l.source_type = 'ACCOUNT_ACTION' then 'LEGACY_ACCOUNT_ACTION'
                         when l.source_type in ('PARTNER_FUND_ADJUSTMENT','PARTNER_FUND_ADJUSTMENT_REVERSAL') then 'PARTNER_FUND_ADJUSTMENT'
                         when l.source_type = 'CUSTOMER_ADVANCE_REFUND' then 'CUSTOMER_ADVANCE_REFUND'
                         else l.source_type
                       end business_type,
                       case
                         when l.source_type = 'PAYMENT' and coalesce(pay.sales_order_id, 0) > 0 then pay.sales_order_id
                         when l.source_type = 'PAYMENT' and coalesce(pay.outbound_id, 0) > 0 then pay.outbound_id
                         when l.source_type = 'SUPPLIER_PAYMENT' and coalesce(sp.purchase_order_id, 0) > 0 then sp.purchase_order_id
                         when l.source_type = 'SUPPLIER_PAYMENT' and coalesce(sp.inbound_id, 0) > 0 then sp.inbound_id
                         else l.source_id
                       end business_id,
                       coalesce(so.order_no, oo.order_no, po.order_no, io.order_no, pay.payment_no, sp.payment_no,
                                cr.refund_no, pfa.adjustment_no, concat(aa.action_type, ' ', aa.category), '') business_no,
                       coalesce(nullif(pay.memo, ''), nullif(sp.memo, ''), nullif(cr.memo, ''),
                                nullif(pfa.memo, ''), nullif(aa.memo, ''), '') business_summary
                from partner_account_log l
                join partner p on p.id = l.partner_id
                left join payment pay on l.source_type = 'PAYMENT' and pay.id = l.source_id
                left join sales_order so on so.id = pay.sales_order_id
                left join outbound_order oo on oo.id = pay.outbound_id
                left join supplier_payment sp on l.source_type = 'SUPPLIER_PAYMENT' and sp.id = l.source_id
                left join purchase_order po on po.id = sp.purchase_order_id
                left join inbound_order io on io.id = sp.inbound_id
                left join partner_account_action aa on l.source_type = 'ACCOUNT_ACTION' and aa.id = l.source_id
                left join partner_fund_adjustment pfa on l.source_type in ('PARTNER_FUND_ADJUSTMENT','PARTNER_FUND_ADJUSTMENT_REVERSAL') and pfa.id = l.source_id
                left join customer_advance_refund cr on l.source_type = 'CUSTOMER_ADVANCE_REFUND' and cr.id = l.source_id
                where l.account_type in ('CUSTOMER_ADVANCE', 'CUSTOMER_REWARD', 'SUPPLIER_PREPAY', 'SUPPLIER_REWARD')
                """;
        Object[] args = new Object[0];
        if (partnerId != null && partnerId > 0) {
            sql += " and l.partner_id = ?";
            args = new Object[]{partnerId};
        }
        sql += " order by l.id desc limit 160";
        return jdbcTemplate.query(sql, accountLogMapper(), args);
    }

    public List<AlignedDtos.SalesOrderRow> salesOrders() {
        return jdbcTemplate.query("""
                select o.*, p.name customer_name,fw.name fulfillment_warehouse_name,
                       coalesce(ar.secured_amount, 0) reserved_prepayment_amount,
                       greatest(o.required_deposit_amount - coalesce(ar.secured_amount, 0), 0) prepayment_shortfall,
                       coalesce(cp.cash_amount, 0) + coalesce(ab.cash_amount, 0) cash_received_amount,
                       greatest(o.receivable_amount - o.received_amount - o.adjusted_amount, 0) outstanding_amount,
                       greatest(coalesce(cp.cash_amount, 0) + coalesce(ar.reserved_amount, 0) - coalesce(sr.advance_credit_amount, 0), 0) shipment_funded_amount,
                       case o.shipment_payment_policy
                         when 'ORDER_PAID' then o.total_amount
                         when 'SHIPMENT_PAID' then o.receivable_amount
                         else 0 end shipment_required_amount,
                       greatest(case o.shipment_payment_policy
                         when 'ORDER_PAID' then o.total_amount
                         when 'SHIPMENT_PAID' then o.receivable_amount
                         else 0 end - coalesce(cp.cash_amount, 0) - coalesce(ar.reserved_amount, 0)
                         + coalesce(sr.advance_credit_amount, 0), 0) shipment_payment_shortfall
                from sales_order o
                join partner p on p.id = o.customer_id
                left join warehouse fw on fw.id = o.fulfillment_warehouse_id
                left join (
                  select sales_order_id,
                         sum(case when status in ('RESERVED', 'APPLIED') then amount else 0 end) secured_amount,
                         sum(case when status = 'RESERVED' then amount else 0 end) reserved_amount
                  from sales_order_advance_allocation
                  group by sales_order_id
                ) ar on ar.sales_order_id = o.id
                left join (
                  select sales_order_id, sum(amount) cash_amount
                  from payment
                  where verification_status in ('VERIFIED', 'UNAPPLIED') and payment_method in ('BANK', 'CASH', 'ADVANCE')
                  group by sales_order_id
                ) cp on cp.sales_order_id = o.id
                left join (
                  select sales_order_id, sum(advance_credit_amount) advance_credit_amount
                  from sales_return_order where status = 'CONFIRMED' and sales_order_id > 0
                  group by sales_order_id
                ) sr on sr.sales_order_id = o.id
                left join (
                  select sales_order_id, sum(case
                    when status = 'DISCOUNTED' then net_proceeds
                    when status = 'MATURED' then face_amount
                    else 0 end) cash_amount
                  from acceptance_bill group by sales_order_id
                ) ab on ab.sales_order_id = o.id
                order by o.id desc
                limit 50
                """, salesOrderMapper());
    }

    public OrderDtos.SalesOrderDetail salesOrderDetail(Long id) {
        List<OrderDtos.SalesOrderDetail> headers = jdbcTemplate.query("""
                select o.*, p.name customer_name,fw.name fulfillment_warehouse_name,
                       coalesce(nullif(o.address_snapshot, ''), concat_ws(' ', nullif(a.detail, ''), nullif(a.contact_person, ''), nullif(a.phone, ''))) address_snapshot_value,
                       coalesce(ar.secured_amount, 0) reserved_prepayment_amount,
                       greatest(o.required_deposit_amount - coalesce(ar.secured_amount, 0), 0) prepayment_shortfall,
                       coalesce(ca.balance, 0) customer_advance_balance,
                       greatest(coalesce(ca.balance, 0) - coalesce(car.reserved_amount, 0), 0) customer_advance_available,
                       coalesce(cp.cash_amount, 0) + coalesce(ab.cash_amount, 0) cash_received_amount,
                       greatest(o.receivable_amount - o.received_amount - o.adjusted_amount, 0) outstanding_amount,
                       greatest(coalesce(cp.cash_amount, 0) + coalesce(ar.reserved_amount, 0) - coalesce(sr.advance_credit_amount, 0), 0) shipment_funded_amount,
                       case o.shipment_payment_policy
                         when 'ORDER_PAID' then o.total_amount
                         when 'SHIPMENT_PAID' then o.receivable_amount
                         else 0 end shipment_required_amount,
                       greatest(case o.shipment_payment_policy
                         when 'ORDER_PAID' then o.total_amount
                         when 'SHIPMENT_PAID' then o.receivable_amount
                         else 0 end - coalesce(cp.cash_amount, 0) - coalesce(ar.reserved_amount, 0)
                         + coalesce(sr.advance_credit_amount, 0), 0) shipment_payment_shortfall
                from sales_order o
                join partner p on p.id = o.customer_id
                left join warehouse fw on fw.id = o.fulfillment_warehouse_id
                left join partner_address a on a.id = o.address_id
                left join (
                  select sales_order_id,
                         sum(case when status in ('RESERVED', 'APPLIED') then amount else 0 end) secured_amount,
                         sum(case when status = 'RESERVED' then amount else 0 end) reserved_amount
                  from sales_order_advance_allocation
                  group by sales_order_id
                ) ar on ar.sales_order_id = o.id
                left join partner_account ca on ca.partner_id = o.customer_id and ca.account_type = 'CUSTOMER_ADVANCE'
                left join (
                  select customer_id, sum(amount) reserved_amount from (
                    select customer_id, amount from sales_order_advance_allocation where status = 'RESERVED'
                    union all
                    select customer_id, amount from outbound_advance_allocation where status = 'RESERVED'
                  ) all_reserved group by customer_id
                ) car on car.customer_id = o.customer_id
                left join (
                  select sales_order_id, sum(amount) cash_amount
                  from payment
                  where verification_status in ('VERIFIED', 'UNAPPLIED') and payment_method in ('BANK', 'CASH', 'ADVANCE')
                  group by sales_order_id
                ) cp on cp.sales_order_id = o.id
                left join (
                  select sales_order_id, sum(advance_credit_amount) advance_credit_amount
                  from sales_return_order where status = 'CONFIRMED' and sales_order_id > 0
                  group by sales_order_id
                ) sr on sr.sales_order_id = o.id
                left join (
                  select sales_order_id, sum(case
                    when status = 'DISCOUNTED' then net_proceeds
                    when status = 'MATURED' then face_amount
                    else 0 end) cash_amount
                  from acceptance_bill group by sales_order_id
                ) ab on ab.sales_order_id = o.id
                where o.id = ?
                """, (rs, rowNum) -> new OrderDtos.SalesOrderDetail(
                rs.getLong("id"),
                rs.getString("order_no"),
                rs.getString("title"),
                rs.getLong("customer_id"),
                rs.getString("customer_name"),
                rs.getLong("address_id"),
                rs.getString("address_snapshot_value"),
                rs.getDate("business_date").toLocalDate(),
                rs.getDate("delivery_plan_date") == null ? null : rs.getDate("delivery_plan_date").toLocalDate(),
                rs.getLong("fulfillment_warehouse_id"),
                rs.getString("fulfillment_warehouse_name"),
                rs.getBigDecimal("original_amount"),
                rs.getBigDecimal("discount_rate"),
                rs.getBigDecimal("discount_amount"),
                rs.getBigDecimal("adjustment_amount"),
                rs.getInt("price_includes_tax") == 1,
                rs.getString("tax_rate_summary"),
                rs.getString("tax_rule_snapshot"),
                rs.getBigDecimal("amount_excluding_tax"),
                rs.getBigDecimal("tax_amount"),
                rs.getBigDecimal("tax_rounding_difference"),
                rs.getBigDecimal("total_amount"),
                rs.getBigDecimal("receivable_amount"),
                rs.getBigDecimal("received_amount"),
                rs.getBigDecimal("adjusted_amount"),
                rs.getBigDecimal("cash_received_amount"),
                rs.getBigDecimal("outstanding_amount"),
                rs.getBigDecimal("rounded_amount"),
                rs.getString("payment_status"),
                rs.getString("payment_term"),
                rs.getString("shipment_payment_policy"),
                rs.getString("deposit_mode"),
                rs.getBigDecimal("deposit_rate"),
                rs.getBigDecimal("required_deposit_amount"),
                rs.getBigDecimal("reserved_prepayment_amount"),
                rs.getBigDecimal("prepayment_shortfall"),
                rs.getBigDecimal("customer_advance_balance"),
                rs.getBigDecimal("customer_advance_available"),
                rs.getBigDecimal("shipment_funded_amount"),
                rs.getBigDecimal("shipment_required_amount"),
                rs.getBigDecimal("shipment_payment_shortfall"),
                rs.getString("status"),
                rs.getString("memo"),
                rs.getString("cancel_reason"),
                rs.getTimestamp("cancelled_at") == null ? null : rs.getTimestamp("cancelled_at").toLocalDateTime(),
                List.of()
        ), id);
        if (headers.isEmpty()) {
            throw new BusinessException("销售订单不存在");
        }
        List<OrderDtos.SalesOrderItemDetail> items = jdbcTemplate.query("""
                select i.*,coalesce(nullif(i.product_sku_snapshot,''),p.sku) sku,
                       coalesce(nullif(i.product_name_snapshot,''),p.name) product_name,
                       '' grade,'' temper,'' standard,
                       coalesce(nullif(i.product_spec_snapshot,''),p.spec) spec,
                       coalesce(nullif(i.base_unit,''),p.unit) unit
                from sales_order_item i
                join product p on p.id = i.product_id
                where i.sales_order_id = ?
                order by i.id
                """, (rs, rowNum) -> new OrderDtos.SalesOrderItemDetail(
                rs.getLong("id"),
                rs.getLong("product_id"),
                rs.getString("sku"),
                rs.getString("product_name"),
                rs.getString("grade"),
                rs.getString("temper"),
                rs.getString("standard"),
                rs.getString("spec"),
                rs.getString("unit"),
                rs.getBigDecimal("quantity"),
                rs.getString("price_unit"),
                rs.getBigDecimal("package_quantity"),
                rs.getString("package_unit"),
                rs.getString("price_source_code"), rs.getString("price_source_name"),
                rs.getLong("price_rule_version_id"), rs.getString("price_snapshot_json"),
                rs.getInt("no_charge") == 1,
                rs.getBigDecimal("unit_price"),
                rs.getBigDecimal("amount"),
                rs.getBigDecimal("tax_rate"), rs.getBigDecimal("unit_price_excluding_tax"),
                rs.getBigDecimal("amount_excluding_tax"), rs.getBigDecimal("tax_amount"),
                rs.getBigDecimal("settlement_total_amount"), rs.getBigDecimal("tax_rounding_difference"),
                rs.getString("status"), rs.getBigDecimal("requested_quantity"),
                rs.getString("requested_unit"), rs.getString("base_unit"),
                rs.getBigDecimal("pricing_quantity"), rs.getString("packaging_plan_json"),
                rs.getBigDecimal("shipped_quantity"), rs.getBigDecimal("shipped_requested_quantity")
        ), id);
        OrderDtos.SalesOrderDetail header = headers.get(0);
        return new OrderDtos.SalesOrderDetail(
                header.id(), header.orderNo(), header.title(), header.customerId(), header.customerName(),
                header.addressId(), header.addressSnapshot(), header.businessDate(), header.deliveryPlanDate(),
                header.fulfillmentWarehouseId(), header.fulfillmentWarehouseName(), header.originalAmount(),
                header.discountRate(), header.discountAmount(), header.adjustmentAmount(), header.priceIncludesTax(),
                header.taxRateSummary(), header.taxRuleSnapshot(), header.amountExcludingTax(), header.taxAmount(),
                header.taxRoundingDifference(), header.totalAmount(),
                header.receivableAmount(), header.receivedAmount(),
                header.adjustedAmount(), header.cashReceivedAmount(), header.outstandingAmount(),
                header.roundedAmount(), header.paymentStatus(), header.paymentTerm(), header.shipmentPaymentPolicy(),
                header.depositMode(), header.depositRate(),
                header.requiredDepositAmount(), header.reservedPrepaymentAmount(), header.prepaymentShortfall(),
                header.customerAdvanceBalance(), header.customerAdvanceAvailable(), header.shipmentFundedAmount(),
                header.shipmentRequiredAmount(), header.shipmentPaymentShortfall(), header.status(), header.memo(),
                header.cancelReason(), header.cancelledAt(), items);
    }

    @Transactional
    public Long createSalesOrder(AlignedDtos.SalesOrderCreate req) {
        return createSalesOrder(
                req, Map.of(), Map.of(),
                partnerOrderTaxTerms(req.customerId(), "CUSTOMER"));
    }

    /**
     * CRM only supplies an accepted quote snapshot. Sales order validation, numbering, money rules and
     * persistence remain owned by the sales domain, so CRM cannot write sales tables directly.
     */
    @Transactional
    public Long createSalesOrderFromCrmQuote(AlignedDtos.SalesOrderCreate req,
                                             Map<Long, String> quotePackagingSnapshots,
                                             Map<Long, String> quotePriceSnapshots,
                                             Map<Long, Long> quoteProductIds,
                                             boolean priceIncludesTax,
                                             BigDecimal defaultTaxRate,
                                             Map<Long, BigDecimal> quoteItemTaxRates,
                                             String taxRuleSnapshot) {
        return createSalesOrder(req, quotePackagingSnapshots, quotePriceSnapshots, quoteProductIds,
                new OrderTaxTerms(priceIncludesTax, defaultTaxRate, quoteItemTaxRates,
                        "CRM_QUOTE|" + blank(taxRuleSnapshot)));
    }

    private Long createSalesOrder(AlignedDtos.SalesOrderCreate req, Map<Long, String> historicalSnapshots,
                                  Map<Long, Long> historicalProductIds,
                                  OrderTaxTerms taxTerms) {
        return createSalesOrder(req, historicalSnapshots, Map.of(), historicalProductIds, taxTerms);
    }

    private Long createSalesOrder(AlignedDtos.SalesOrderCreate req, Map<Long, String> historicalSnapshots,
                                  Map<Long, String> historicalPriceSnapshots,
                                  Map<Long, Long> historicalProductIds,
                                  OrderTaxTerms taxTerms) {
        requirePartnerRole(req.customerId(), "CUSTOMER");
        if (req.fulfillmentWarehouseId() != null && req.fulfillmentWarehouseId() > 0) {
            requireWarehouse(req.fulfillmentWarehouseId());
        }
        PreparedSalesOrder prepared = prepareSalesOrder(
                req, historicalSnapshots, historicalPriceSnapshots, historicalProductIds, taxTerms);
        SalesOrderPaymentTerms paymentTerms = salesOrderPaymentTerms(req, prepared.totalAmount());
        String shipmentPaymentPolicy = salesOrderShipmentPaymentPolicy(req, paymentTerms.paymentTerm());
        Long addressId = requiredSalesOrderAddressId(req.addressId());
        String addressSnapshot = requiredSalesOrderAddressSnapshot(req.customerId(), addressId);
        SettlementDueSnapshot settlement = settlementDueSnapshot("CUSTOMER", req.customerId(), req.businessDate());
        String orderNo = nextNo("XS");
        Long salesOrderId = insert("""
                insert into sales_order
                (order_no, title, customer_id, address_id, address_snapshot, business_date, settlement_days_snapshot, due_date, delivery_plan_date,fulfillment_warehouse_id,
                 price_includes_tax,tax_rate_summary,tax_rule_snapshot,original_amount, discount_rate, discount_amount, adjustment_amount,
                 amount_excluding_tax,tax_amount,tax_rounding_difference,total_amount,
                 payment_term, shipment_payment_policy, deposit_mode, deposit_rate, required_deposit_amount, status, memo)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        ?, ?, ?, ?, ?, 'DRAFT', ?)
                """, orderNo, blank(req.title()), req.customerId(), addressId, addressSnapshot,
                Date.valueOf(req.businessDate()), settlement.days(), Date.valueOf(settlement.dueDate()),
                req.deliveryPlanDate() == null ? null : Date.valueOf(req.deliveryPlanDate()),
                req.fulfillmentWarehouseId() == null ? 0L : req.fulfillmentWarehouseId(),
                bool(prepared.tax().priceIncludesTax()), prepared.tax().taxRateSummary(), prepared.tax().ruleSnapshot(),
                prepared.originalAmount(), nvl(req.discountRate()), prepared.discountAmount(),
                prepared.adjustmentAmount(), prepared.tax().amountExcludingTax(), prepared.tax().taxAmount(),
                prepared.tax().roundingDifference(), prepared.totalAmount(), paymentTerms.paymentTerm(), shipmentPaymentPolicy, paymentTerms.depositMode(),
                paymentTerms.depositRate(), paymentTerms.requiredAmount(), blank(req.memo()));

        insertSalesOrderItems(salesOrderId, prepared.items());
        recordInitialOrderRevision("SALES_ORDER", salesOrderId, orderNo);
        return salesOrderId;
    }

    @Transactional
    public Long updateSalesOrder(Long salesOrderId, AlignedDtos.SalesOrderCreate req) {
        requirePartnerRole(req.customerId(), "CUSTOMER");
        if (req.fulfillmentWarehouseId() != null && req.fulfillmentWarehouseId() > 0) {
            requireWarehouse(req.fulfillmentWarehouseId());
        }
        SalesOrderEditRow salesOrder = lockSalesOrderForEdit(salesOrderId);
        Map<Long, String> snapshots = orderItemSnapshots("sales_order_item", "sales_order_id", salesOrderId);
        Map<Long, String> priceSnapshots = orderItemPriceSnapshots("sales_order_item", "sales_order_id", salesOrderId);
        Map<Long, Long> historicalProductIds =
                orderItemProductIds("sales_order_item", "sales_order_id", salesOrderId);
        PreparedSalesOrder prepared = prepareSalesOrder(req, snapshots, priceSnapshots, historicalProductIds,
                storedOrderTaxTerms("SALES_ORDER", salesOrderId));
        SalesOrderPaymentTerms paymentTerms = salesOrderPaymentTerms(req, prepared.totalAmount());
        String shipmentPaymentPolicy = salesOrderShipmentPaymentPolicy(req, paymentTerms.paymentTerm());
        if (!"DRAFT".equals(salesOrder.status())) {
            throw new BusinessException("只有暂存的销售订单可以修改");
        }
        if (salesOrder.receivedAmount().compareTo(BigDecimal.ZERO) != 0) {
            throw new BusinessException("销售订单已有回款记录，不能修改");
        }
        BigDecimal reserved = securedSalesOrderAdvance(salesOrderId);
        if (!Objects.equals(salesOrder.customerId(), req.customerId()) && reserved.compareTo(BigDecimal.ZERO) > 0) {
            throw new BusinessException("销售订单已有预收款占用，不能变更客户；请先释放预收款");
        }
        if (reserved.compareTo(prepared.totalAmount()) > 0) {
            throw new BusinessException("已占用预收款超过修改后的订单金额，请先释放部分预收款");
        }
        Long addressId = requiredSalesOrderAddressId(req.addressId());
        String addressSnapshot = requiredSalesOrderAddressSnapshot(req.customerId(), addressId);
        SettlementDueSnapshot settlement = settlementDueSnapshot("CUSTOMER", req.customerId(), req.businessDate());
        jdbcTemplate.update("""
                update sales_order
                set title = ?, customer_id = ?, address_id = ?, address_snapshot = ?, business_date = ?, settlement_days_snapshot = ?, due_date = ?,
                    delivery_plan_date = ?,fulfillment_warehouse_id=?,price_includes_tax=?,tax_rate_summary=?,tax_rule_snapshot=?,
                    original_amount = ?, discount_rate = ?, discount_amount = ?,adjustment_amount = ?,
                    amount_excluding_tax=?,tax_amount=?,tax_rounding_difference=?,total_amount = ?,
                    payment_term = ?, shipment_payment_policy = ?, deposit_mode = ?,
                    deposit_rate = ?, required_deposit_amount = ?, memo = ?
                where id = ?
                """, blank(req.title()), req.customerId(), addressId, addressSnapshot, Date.valueOf(req.businessDate()),
                settlement.days(), Date.valueOf(settlement.dueDate()),
                req.deliveryPlanDate() == null ? null : Date.valueOf(req.deliveryPlanDate()),
                req.fulfillmentWarehouseId() == null ? 0L : req.fulfillmentWarehouseId(),
                bool(prepared.tax().priceIncludesTax()), prepared.tax().taxRateSummary(), prepared.tax().ruleSnapshot(),
                prepared.originalAmount(), nvl(req.discountRate()), prepared.discountAmount(),
                prepared.adjustmentAmount(), prepared.tax().amountExcludingTax(), prepared.tax().taxAmount(),
                prepared.tax().roundingDifference(), prepared.totalAmount(), paymentTerms.paymentTerm(), shipmentPaymentPolicy, paymentTerms.depositMode(),
                paymentTerms.depositRate(), paymentTerms.requiredAmount(), blank(req.memo()), salesOrderId);
        jdbcTemplate.update("delete from sales_order_item where sales_order_id = ?", salesOrderId);
        insertSalesOrderItems(salesOrderId, prepared.items());
        refreshInitialOrderRevision("SALES_ORDER", salesOrderId);
        return salesOrderId;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Long confirmSalesOrder(Long salesOrderId) {
        return confirmSalesOrder(salesOrderId, null);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Long confirmSalesOrder(Long salesOrderId, String riskOverrideReason) {
        return confirmSalesOrder(salesOrderId, riskOverrideReason, null);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Long confirmSalesOrder(Long salesOrderId, String riskOverrideReason, Long approvalId) {
        SalesOrderEditRow salesOrder = lockSalesOrderForEdit(salesOrderId);
        if ("CANCELLED".equals(salesOrder.status())) {
            throw new BusinessException("已作废的销售订单不能确认销售");
        }
        if (!"DRAFT".equals(salesOrder.status())) {
            return salesOrderId;
        }
        requirePartnerRole(salesOrder.customerId(), "CUSTOMER");
        Integer itemCount = jdbcTemplate.queryForObject("""
                select count(*) from sales_order_item
                where sales_order_id = ? and status = 'ENABLED'
                """, Integer.class, salesOrderId);
        if (itemCount == null || itemCount == 0) {
            throw new BusinessException("销售订单没有有效商品明细，不能确认销售");
        }
        BigDecimal reservedAdvance = reservedSalesOrderAdvance(salesOrderId);
        if (!"POSTPAID".equals(salesOrder.paymentTerm())
                && reservedAdvance.compareTo(salesOrder.requiredDepositAmount()) < 0) {
            BigDecimal shortfall = salesOrder.requiredDepositAmount().subtract(reservedAdvance);
            throw new BusinessException("该订单要求预付款，还差" + moneyText(shortfall) + "元，不能确认销售");
        }
        salesRiskService.enforceSalesOrder(salesOrderId, "CONFIRM",
                salesOrder.currentRevisionNo(), riskOverrideReason, approvalId);
        String sourceType = jdbcTemplate.queryForObject(
                "select source_type from sales_order where id=?", String.class, salesOrderId);
        if (!"DIRECT_TRADE".equals(sourceType)) {
            atpService.enforceSalesOrder(salesOrderId, "CONFIRM", salesOrder.currentRevisionNo());
        }
        jdbcTemplate.update("update sales_order set status = 'OPEN' where id = ?", salesOrderId);
        BusinessPostingDtos.PostingResult posting = postingService.post(
                "SALES_ORDER", salesOrderId, salesOrder.orderNo(), "COMMITMENT_POST",
                salesOrder.currentRevisionNo(), salesOrder.businessDate(), salesOrder.customerId(), 0L,
                "确认销售订单", salesCommitmentEntries(salesOrderId, "INCREASE"));
        linkOrderRevisionPosting("SALES_ORDER", salesOrderId, salesOrder.currentRevisionNo(), posting.batchId());
        return salesOrderId;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Long amendSalesOrder(Long salesOrderId, BusinessPostingDtos.SalesAmendment amendment) {
        SalesOrderEditRow salesOrder = lockSalesOrderForEdit(salesOrderId);
        if (!List.of("OPEN", "PARTIAL_SHIPPED").contains(salesOrder.status())) {
            throw new BusinessException("只有已确认且尚未全部出库的销售订单可以变更");
        }
        String reason = requiredReason(amendment.reason(), "销售订单变更");
        requirePartnerRole(salesOrder.customerId(), "CUSTOMER");
        AlignedDtos.SalesOrderCreate req = amendment.order();
        if (req.fulfillmentWarehouseId() == null || req.fulfillmentWarehouseId() <= 0) {
            throw new BusinessException("请先选择履约仓库");
        }
        requireWarehouse(req.fulfillmentWarehouseId());
        if (req.deliveryPlanDate() == null) {
            throw new BusinessException("请先填写承诺交付日期");
        }
        if (!Objects.equals(req.customerId(), salesOrder.customerId())) {
            throw new BusinessException("已确认销售订单不能变更客户；请取消未履约订单后重新下单");
        }
        Integer activeOutbounds = jdbcTemplate.queryForObject(
                "select count(*) from outbound_order where sales_order_id=? and status='DRAFT'", Integer.class, salesOrderId);
        if (activeOutbounds != null && activeOutbounds > 0) {
            throw new BusinessException("该订单存在出库草稿，请先修改或作废出库草稿后再变更");
        }
        Map<Long, String> snapshots = orderItemSnapshots("sales_order_item", "sales_order_id", salesOrderId);
        Map<Long, String> priceSnapshots = orderItemPriceSnapshots("sales_order_item", "sales_order_id", salesOrderId);
        Map<Long, Long> historicalProductIds =
                orderItemProductIds("sales_order_item", "sales_order_id", salesOrderId);
        PreparedSalesOrder prepared = prepareSalesOrder(req, snapshots, priceSnapshots, historicalProductIds,
                storedOrderTaxTerms("SALES_ORDER", salesOrderId));
        SalesOrderPaymentTerms paymentTerms = salesOrderPaymentTerms(req, prepared.totalAmount());
        BigDecimal adjusted = scalarDecimal("select adjusted_amount from sales_order where id=" + salesOrderId);
        if (salesOrder.receivedAmount().add(adjusted).compareTo(prepared.totalAmount()) > 0) {
            throw new BusinessException("变更后订单金额小于已收款和已核销金额，请先处理相关资金单据");
        }
        BigDecimal reservedAdvance = reservedSalesOrderAdvance(salesOrderId);
        if (!"POSTPAID".equals(paymentTerms.paymentTerm())
                && reservedAdvance.compareTo(paymentTerms.requiredAmount()) < 0) {
            throw new BusinessException("变更后的预付款要求高于已占用预收款，请先补足预收款");
        }
        validateSalesAmendmentItems(salesOrderId, prepared.items());
        // Keep the same lock order as confirmation: customer risk scope first, ATP scopes second.
        salesRiskService.lockCustomerScope(salesOrder.customerId());
        atpService.lockSalesOrderAmendment(salesOrderId, req.fulfillmentWarehouseId(),
                prepared.items().stream().map(item -> item.item().productId()).toList());
        String before = orderSnapshot("SALES_ORDER", salesOrderId);
        int revisionNo = salesOrder.currentRevisionNo() + 1;
        BusinessPostingDtos.PostingResult reversed = postingService.reverseSource(
                "SALES_ORDER", salesOrderId, salesOrder.orderNo(), "COMMITMENT_REVERSE",
                revisionNo, salesOrder.businessDate(), salesOrder.customerId(),
                List.of("COMMITMENT_POST"), reason);
        Long addressId = requiredSalesOrderAddressId(req.addressId());
        String addressSnapshot = requiredSalesOrderAddressSnapshot(req.customerId(), addressId);
        String shipmentPaymentPolicy = salesOrderShipmentPaymentPolicy(req, paymentTerms.paymentTerm());
        jdbcTemplate.update("""
                update sales_order
                set title=?,address_id=?,address_snapshot=?,business_date=?,delivery_plan_date=?,fulfillment_warehouse_id=?,
                    price_includes_tax=?,tax_rate_summary=?,tax_rule_snapshot=?,original_amount=?,discount_rate=?,discount_amount=?,adjustment_amount=?,
                    amount_excluding_tax=?,tax_amount=?,tax_rounding_difference=?,total_amount=?,
                    payment_term=?,shipment_payment_policy=?,deposit_mode=?,deposit_rate=?,required_deposit_amount=?,
                    memo=?,current_revision_no=?,last_amended_at=now(),
                    payment_status=case when received_amount+adjusted_amount>=? and ?>0 then 'PAID'
                                        when received_amount+adjusted_amount>0 then 'PARTIAL' else 'UNPAID' end
                where id=?
                """, blank(req.title()), addressId, addressSnapshot, Date.valueOf(req.businessDate()),
                req.deliveryPlanDate() == null ? null : Date.valueOf(req.deliveryPlanDate()),
                req.fulfillmentWarehouseId(),
                bool(prepared.tax().priceIncludesTax()), prepared.tax().taxRateSummary(), prepared.tax().ruleSnapshot(),
                prepared.originalAmount(), nvl(req.discountRate()), prepared.discountAmount(),
                prepared.adjustmentAmount(), prepared.tax().amountExcludingTax(), prepared.tax().taxAmount(),
                prepared.tax().roundingDifference(), prepared.totalAmount(), paymentTerms.paymentTerm(),
                shipmentPaymentPolicy, paymentTerms.depositMode(), paymentTerms.depositRate(),
                paymentTerms.requiredAmount(), blank(req.memo()), revisionNo,
                prepared.totalAmount(), prepared.totalAmount(), salesOrderId);
        applySalesAmendmentItems(salesOrderId, prepared.items());
        refreshSalesOrderShipmentStatus(salesOrderId);
        salesRiskService.enforceSalesOrder(salesOrderId, "AMEND", revisionNo,
                amendment.riskOverrideReason());
        atpService.enforceSalesOrder(salesOrderId, "AMEND", revisionNo);
        BusinessPostingDtos.PostingResult posted = postingService.post(
                "SALES_ORDER", salesOrderId, salesOrder.orderNo(), "COMMITMENT_POST",
                revisionNo, req.businessDate(), salesOrder.customerId(), 0L, reason,
                salesCommitmentEntries(salesOrderId, "INCREASE"));
        recordOrderRevision("SALES_ORDER", salesOrderId, salesOrder.orderNo(), revisionNo,
                "AMENDMENT", reason, before, orderSnapshot("SALES_ORDER", salesOrderId),
                reversed.batchId(), posted.batchId());
        return salesOrderId;
    }

    public PackagingDtos.Plan salesAmendmentPackagingPlan(Long salesOrderId, Long itemId,
                                                            PackagingDtos.PlanRequest request) {
        String snapshot = orderItemPackagingSnapshot(
                "sales_order_item", "sales_order_id", salesOrderId, itemId);
        return packagingService.planFromSnapshot(snapshot, request);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Long cancelSalesOrder(Long salesOrderId, String reason) {
        SalesOrderEditRow salesOrder = lockSalesOrderForEdit(salesOrderId);
        if ("CANCELLED".equals(salesOrder.status())) {
            return salesOrderId;
        }
        if (!List.of("DRAFT", "OPEN").contains(salesOrder.status())) {
            throw new BusinessException("只有暂存或尚未履约的已确认销售订单可以作废");
        }
        if (salesOrder.receivedAmount().compareTo(BigDecimal.ZERO) != 0) {
            throw new BusinessException("销售订单已有回款记录，不能作废");
        }
        BigDecimal shipped = scalarDecimal("select coalesce(sum(shipped_quantity),0) from sales_order_item where sales_order_id=" + salesOrderId);
        if (shipped.compareTo(BigDecimal.ZERO) > 0) throw new BusinessException("销售订单已有出库记录，不能作废");
        Integer outboundDrafts = jdbcTemplate.queryForObject(
                "select count(*) from outbound_order where sales_order_id=? and status='DRAFT'",
                Integer.class, salesOrderId);
        if (outboundDrafts != null && outboundDrafts > 0) {
            throw new BusinessException("销售订单存在出库草稿，请先修改或作废出库草稿后再作废订单");
        }
        String cancelReason = requiredReason(reason, "销售订单作废");
        int revisionNo = salesOrder.currentRevisionNo();
        Long reversedBatchId = 0L;
        if ("OPEN".equals(salesOrder.status())) {
            revisionNo++;
            BusinessPostingDtos.PostingResult reversed = postingService.reverseSource(
                    "SALES_ORDER", salesOrderId, salesOrder.orderNo(), "COMMITMENT_REVERSE",
                    revisionNo, salesOrder.businessDate(), salesOrder.customerId(),
                    List.of("COMMITMENT_POST"), cancelReason);
            reversedBatchId = reversed.batchId();
        }
        String before = orderSnapshot("SALES_ORDER", salesOrderId);
        if ("OPEN".equals(salesOrder.status())) {
            atpService.recordSalesOrderRelease(salesOrderId, revisionNo);
        }
        jdbcTemplate.update("""
                update sales_order_advance_allocation
                set status = 'RELEASED', memo = concat_ws('；', nullif(memo, ''), '订单作废自动释放'),
                    updated_at = current_timestamp
                where sales_order_id = ? and status = 'RESERVED'
                """, salesOrderId);
        jdbcTemplate.update("""
                update sales_order
                set status = 'CANCELLED', cancel_reason = ?, cancelled_at = now(),current_revision_no=?
                where id = ?
                """, cancelReason, revisionNo, salesOrderId);
        recordOrderRevision("SALES_ORDER", salesOrderId, salesOrder.orderNo(), revisionNo,
                "CANCEL", cancelReason, before, orderSnapshot("SALES_ORDER", salesOrderId),
                reversedBatchId, 0L);
        return salesOrderId;
    }

    public OrderDtos.SalesOrderPrepaymentSummary salesOrderPrepayments(Long salesOrderId) {
        List<Map<String, Object>> orders = jdbcTemplate.queryForList("""
                select o.id, o.order_no, o.customer_id, p.name customer_name, o.status, o.payment_term,
                       o.total_amount, o.required_deposit_amount
                from sales_order o join partner p on p.id = o.customer_id where o.id = ?
                """, salesOrderId);
        if (orders.isEmpty()) {
            throw new BusinessException("销售订单不存在");
        }
        Map<String, Object> order = orders.get(0);
        Long customerId = ((Number) order.get("customer_id")).longValue();
        BigDecimal required = decimalValue(order.get("required_deposit_amount"));
        BigDecimal reserved = securedSalesOrderAdvance(salesOrderId);
        BigDecimal balance = customerAdvanceBalance(customerId);
        BigDecimal customerReserved = reservedCustomerAdvance(customerId);
        List<OrderDtos.SalesOrderAdvanceAllocation> allocations = jdbcTemplate.query("""
                select a.*, coalesce(p.payment_no, '') source_payment_no
                from sales_order_advance_allocation a
                left join payment p on p.id = a.source_payment_id
                where a.sales_order_id = ?
                order by a.id desc
                """, (rs, rowNum) -> new OrderDtos.SalesOrderAdvanceAllocation(
                rs.getLong("id"), rs.getLong("sales_order_id"), rs.getLong("source_payment_id"),
                rs.getString("source_payment_no"), rs.getBigDecimal("amount"), rs.getString("status"),
                rs.getLong("applied_payment_id"), rs.getString("memo"), rs.getTimestamp("created_at").toLocalDateTime()
        ), salesOrderId);
        return new OrderDtos.SalesOrderPrepaymentSummary(
                salesOrderId, String.valueOf(order.get("order_no")), customerId,
                String.valueOf(order.get("customer_name")), String.valueOf(order.get("status")),
                String.valueOf(order.get("payment_term")), decimalValue(order.get("total_amount")), required,
                reserved, required.subtract(reserved).max(BigDecimal.ZERO), balance, customerReserved,
                balance.subtract(customerReserved).max(BigDecimal.ZERO), allocations);
    }

    @Transactional
    public Long receiveSalesOrderPrepayment(Long salesOrderId, SalesOrderPrepaymentRequests.Receive req) {
        SalesOrderEditRow order = lockSalesOrderForEdit(salesOrderId);
        requirePrepaymentEligible(order);
        BigDecimal amount = req.amount().setScale(2, RoundingMode.HALF_UP);
        ensurePrepaymentCapacity(order, amount);
        String method = paymentMethod(req.paymentMethod());
        if (!"BANK".equals(method) && !"CASH".equals(method)) {
            throw new BusinessException("订单预付款仅支持银行转账或现金收款");
        }
        Long paymentId = createPayment(new PaymentCreateRequest(
                order.customerId(), null, null, null, amount, method, req.businessBankAccountId(), req.receivedDate(),
                "销售订单" + order.orderNo() + "预付款 " + blank(req.memo())));
        return insertAdvanceAllocation(salesOrderId, order.customerId(), paymentId, amount, blank(req.memo()));
    }

    @Transactional
    public Long reserveSalesOrderPrepayment(Long salesOrderId, SalesOrderPrepaymentRequests.Reserve req) {
        SalesOrderEditRow order = lockSalesOrderForEdit(salesOrderId);
        requirePartnerRole(order.customerId(), "CUSTOMER");
        requirePrepaymentEligible(order);
        BigDecimal amount = req.amount().setScale(2, RoundingMode.HALF_UP);
        ensurePrepaymentCapacity(order, amount);
        BigDecimal available = lockCustomerAdvanceBalance(order.customerId()).subtract(reservedCustomerAdvance(order.customerId()));
        if (available.compareTo(amount) < 0) {
            throw new BusinessException("客户可用预收余额不足，当前可用" + moneyText(available.max(BigDecimal.ZERO)) + "元");
        }
        return insertAdvanceAllocation(salesOrderId, order.customerId(), 0L, amount, blank(req.memo()));
    }

    @Transactional
    public void releaseSalesOrderPrepayment(Long salesOrderId, Long allocationId) {
        SalesOrderEditRow order = lockSalesOrderForEdit(salesOrderId);
        if (!List.of("DRAFT", "OPEN", "PARTIAL_SHIPPED").contains(order.status())) {
            throw new BusinessException("只有未完成发货的订单可以释放预收款占用");
        }
        int updated = jdbcTemplate.update("""
                update sales_order_advance_allocation
                set status = 'RELEASED', memo = concat_ws('；', nullif(memo, ''), '手工释放'),
                    updated_at = current_timestamp
                where id = ? and sales_order_id = ? and status = 'RESERVED'
                """, allocationId, salesOrderId);
        if (updated == 0) {
            throw new BusinessException("预收款占用不存在或已处理");
        }
    }

    public List<OrderDtos.CustomerAdvanceRow> customerAdvances() {
        return jdbcTemplate.query("""
                select p.id customer_id, p.name customer_name, coalesce(a.balance, 0) balance,
                       coalesce(r.reserved_amount, 0) reserved_amount,
                       greatest(coalesce(a.balance, 0) - coalesce(r.reserved_amount, 0), 0) available_amount,
                       coalesce(a.updated_at, p.updated_at) updated_at
                from partner p
                left join partner_account a on a.partner_id = p.id and a.account_type = 'CUSTOMER_ADVANCE'
                left join (
                  select customer_id, sum(amount) reserved_amount from (
                    select customer_id, amount from sales_order_advance_allocation where status = 'RESERVED'
                    union all
                    select customer_id, amount from outbound_advance_allocation where status = 'RESERVED'
                  ) all_reserved group by customer_id
                ) r on r.customer_id = p.id
                where (p.type_mask & 1) = 1 and (coalesce(a.balance, 0) <> 0 or coalesce(r.reserved_amount, 0) <> 0)
                order by available_amount desc, p.id desc
                """, (rs, rowNum) -> new OrderDtos.CustomerAdvanceRow(
                rs.getLong("customer_id"), rs.getString("customer_name"), rs.getBigDecimal("balance"),
                rs.getBigDecimal("reserved_amount"), rs.getBigDecimal("available_amount"),
                rs.getTimestamp("updated_at").toLocalDateTime()));
    }

    public List<OrderDtos.CustomerAdvanceRefundRow> customerAdvanceRefunds(Long customerId) {
        String filter = customerId != null && customerId > 0 ? " where r.customer_id = ?" : "";
        Object[] args = customerId != null && customerId > 0 ? new Object[]{customerId} : new Object[0];
        return jdbcTemplate.query("""
                select r.*, p.name customer_name,b.account_name business_bank_account_name
                from customer_advance_refund r join partner p on p.id = r.customer_id
                left join business_bank_account b on b.id=r.business_bank_account_id
                """ + filter + " order by r.id desc limit 200", (rs, rowNum) -> new OrderDtos.CustomerAdvanceRefundRow(
                rs.getLong("id"), rs.getString("refund_no"), rs.getLong("customer_id"),
                rs.getString("customer_name"), rs.getBigDecimal("amount"), rs.getString("refund_method"),
                rs.getObject("business_bank_account_id", Long.class), rs.getString("business_bank_account_name"),
                rs.getDate("refunded_date").toLocalDate(), rs.getString("status"), rs.getString("memo"),
                rs.getString("reversal_no"), rs.getString("reversal_reason"),
                rs.getTimestamp("reversed_at") == null ? null : rs.getTimestamp("reversed_at").toLocalDateTime(),
                rs.getTimestamp("created_at").toLocalDateTime()), args);
    }

    @Transactional
    public Long refundCustomerAdvance(SalesOrderPrepaymentRequests.Refund req) {
        requirePartnerRole(req.customerId(), "CUSTOMER");
        BigDecimal amount = req.amount().setScale(2, RoundingMode.HALF_UP);
        BigDecimal available = lockCustomerAdvanceBalance(req.customerId()).subtract(reservedCustomerAdvance(req.customerId()));
        if (available.compareTo(amount) < 0) {
            throw new BusinessException("可退款预收余额不足，当前可用" + moneyText(available.max(BigDecimal.ZERO)) + "元");
        }
        String method = paymentMethod(req.refundMethod());
        if (!"BANK".equals(method) && !"CASH".equals(method)) {
            throw new BusinessException("客户退款仅支持银行转账或现金");
        }
        Long businessBankAccountId = resolveBusinessBankAccount(
                method, req.businessBankAccountId(), req.refundedDate());
        String refundNo = nextNo("YT");
        Long refundId = insert("""
                insert into customer_advance_refund
                (refund_no, customer_id, amount, refund_method, business_bank_account_id, refunded_date, memo)
                values (?, ?, ?, ?, ?, ?, ?)
                """, refundNo, req.customerId(), amount, method, businessBankAccountId,
                Date.valueOf(req.refundedDate()), blank(req.memo()));
        changePartnerAccount(req.customerId(), "CUSTOMER_ADVANCE", amount.negate(),
                "CUSTOMER_ADVANCE_REFUND", refundId, "客户预收款退款 " + refundNo);
        postingService.post("CUSTOMER_ADVANCE_REFUND", refundId, refundNo, "POST", 1, req.refundedDate(),
                req.customerId(), 0L, "客户预收款退款", List.of(new BusinessPostingDtos.EntryDraft(
                        "CUSTOMER_ADVANCE_REFUND", "DECREASE", req.customerId(), 0L, 0L, 0L,
                        "CUSTOMER_ADVANCE_REFUND", refundId, BigDecimal.ZERO, "", amount,
                        "客户预收款退款 " + refundNo)));
        return refundId;
    }

    @Transactional
    public Long reverseCustomerAdvanceRefund(Long refundId, OutboundActionRequests.Reason req) {
        List<CustomerAdvanceRefundLock> rows = jdbcTemplate.query("""
                select id,refund_no,customer_id,amount,status
                from customer_advance_refund where id=? for update
                """, (rs, rowNum) -> new CustomerAdvanceRefundLock(
                rs.getLong("id"), rs.getString("refund_no"), rs.getLong("customer_id"),
                rs.getBigDecimal("amount"), rs.getString("status")), refundId);
        if (rows.isEmpty()) throw new BusinessException("客户预收款退款不存在");
        CustomerAdvanceRefundLock refund = rows.get(0);
        if ("REVERSED".equals(refund.status())) return refundId;
        String reason = requiredReason(req.reason(), "客户预收款退款冲正");
        riskExceptionService.authorizeOrConsume(
                riskExceptionService.resolveTrigger("CUSTOMER_ADVANCE_REFUND", refundId, "REVERSE"),
                new RiskExceptionDtos.InlineAuthorization(reason, req.approvalId()));
        ensureFundSourceNotReconciled("CUSTOMER_ADVANCE_REFUND", refundId);
        changePartnerAccount(refund.customerId(), "CUSTOMER_ADVANCE", refund.amount(),
                "CUSTOMER_ADVANCE_REFUND_REVERSAL", refundId, "客户预收款退款冲正 " + refund.refundNo());
        jdbcTemplate.update("""
                update customer_advance_refund
                set status='REVERSED',reversal_no=?,reversal_reason=?,reversed_at=now()
                where id=?
                """, nextNo("YC"), reason, refundId);
        postingService.reverseSource("CUSTOMER_ADVANCE_REFUND", refundId, refund.refundNo(),
                "REVERSE", 1, req.businessDate() == null ? LocalDate.now() : req.businessDate(),
                refund.customerId(), List.of("POST"), reason);
        return refundId;
    }

    private PreparedSalesOrder prepareSalesOrder(AlignedDtos.SalesOrderCreate req) {
        return prepareSalesOrder(
                req, Map.of(), Map.of(),
                partnerOrderTaxTerms(req.customerId(), "CUSTOMER"));
    }

    private PreparedSalesOrder prepareSalesOrder(AlignedDtos.SalesOrderCreate req,
                                                  Map<Long, String> historicalSnapshots,
                                                  Map<Long, Long> historicalProductIds) {
        return prepareSalesOrder(
                req, historicalSnapshots, historicalProductIds,
                partnerOrderTaxTerms(req.customerId(), "CUSTOMER"));
    }

    private PreparedSalesOrder prepareSalesOrder(AlignedDtos.SalesOrderCreate req,
                                                  Map<Long, String> historicalSnapshots,
                                                  Map<Long, Long> historicalProductIds,
                                                  OrderTaxTerms taxTerms) {
        return prepareSalesOrder(
                req, historicalSnapshots, Map.of(), historicalProductIds, taxTerms);
    }

    private PreparedSalesOrder prepareSalesOrder(AlignedDtos.SalesOrderCreate req,
                                                  Map<Long, String> historicalSnapshots,
                                                  Map<Long, String> historicalPriceSnapshots,
                                                  Map<Long, Long> historicalProductIds,
                                                  OrderTaxTerms taxTerms) {
        List<PreparedSalesItem> rawItems = new ArrayList<>();
        List<TaxPriceContract.Input> taxInputs = new ArrayList<>();
        for (AlignedDtos.SalesOrderCreate.Item item : req.items()) {
            boolean historicalItem = item.itemId() != null && item.itemId() > 0;
            if (historicalItem) {
                requireHistoricalProduct(
                        item.productId(), item.itemId(), historicalProductIds, "销售订单");
            } else {
                requireProduct(item.productId());
            }
            String priceUnit = StringUtils.hasText(item.priceUnit()) ? item.priceUnit().trim() : productDefaultUnit(item.productId());
            PackagingDtos.PlanRequest planRequest = new PackagingDtos.PlanRequest(
                    item.quantity(), item.quantityUnit(), item.actualBaseQuantity(), priceUnit, "SALES",
                    item.expectedEffectiveRevisionId(), item.expectedPackagingSnapshotHash());
            PackagingDtos.Plan plan = historicalItem
                    ? packagingService.planFromSnapshot(requiredSnapshot(historicalSnapshots, item.itemId()), planRequest)
                    : packagingService.plan(item.productId(), planRequest);
            packagingService.validatePackagingQuantity(plan, item.packageQuantity(), item.packageUnit());
            BigDecimal price = Boolean.TRUE.equals(item.noCharge()) ? BigDecimal.ZERO : item.unitPrice();
            taxInputs.add(new TaxPriceContract.Input(plan.pricingQuantity(), price, taxTerms.rateFor(item.itemId())));
            PriceCenterDtos.PriceSnapshot priceSnapshot = historicalItem
                    ? priceCenterService.freezeEnteredPrice("SALES", req.customerId(), item.productId(),
                    plan.pricingQuantity(), priceUnit, req.businessDate(), price, Boolean.TRUE.equals(item.noCharge()),
                    historicalPriceSnapshots.get(item.itemId()))
                    : priceCenterService.freezeEnteredPrice("SALES", req.customerId(), item.productId(),
                    plan.pricingQuantity(), priceUnit, req.businessDate(), price, Boolean.TRUE.equals(item.noCharge()));
            rawItems.add(new PreparedSalesItem(item, priceUnit, plan, price, BigDecimal.ZERO, null, priceSnapshot));
        }
        TaxPriceContract.Document tax = TaxPriceContract.calculate(taxTerms.priceIncludesTax(), taxInputs,
                req.discountRate(), req.adjustmentAmount(), taxTerms.sourceSnapshot());
        List<PreparedSalesItem> preparedItems = new ArrayList<>();
        for (int index = 0; index < rawItems.size(); index++) {
            PreparedSalesItem raw = rawItems.get(index);
            TaxPriceContract.Line line = tax.lines().get(index);
            preparedItems.add(new PreparedSalesItem(raw.item(), raw.priceUnit(), raw.plan(), raw.price(),
                    line.originalGrossAmount(), line, raw.priceSnapshot()));
        }
        return new PreparedSalesOrder(tax.originalGrossAmount(), tax.discountAmount(), tax.adjustmentAmount(),
                tax.totalAmount(), tax, preparedItems);
    }

    private SalesOrderPaymentTerms salesOrderPaymentTerms(AlignedDtos.SalesOrderCreate req, BigDecimal totalAmount) {
        String term = switch (blank(req.paymentTerm())) {
            case "PREPAYMENT_REQUIRED" -> "PREPAYMENT_REQUIRED";
            case "FULL_PREPAYMENT" -> "FULL_PREPAYMENT";
            default -> "POSTPAID";
        };
        if ("POSTPAID".equals(term)) {
            return new SalesOrderPaymentTerms(term, "AMOUNT", BigDecimal.ZERO, BigDecimal.ZERO);
        }
        if ("FULL_PREPAYMENT".equals(term)) {
            return new SalesOrderPaymentTerms(term, "RATE", BigDecimal.valueOf(100), totalAmount);
        }
        String mode = "RATE".equals(req.depositMode()) ? "RATE" : "AMOUNT";
        BigDecimal rate = nvl(req.depositRate()).setScale(2, RoundingMode.HALF_UP);
        BigDecimal required;
        if ("RATE".equals(mode)) {
            if (rate.compareTo(BigDecimal.ZERO) <= 0 || rate.compareTo(BigDecimal.valueOf(100)) > 0) {
                throw new BusinessException("预付款比例必须大于0且不超过100%");
            }
            required = totalAmount.multiply(rate).divide(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);
        } else {
            required = nvl(req.requiredDepositAmount()).setScale(2, RoundingMode.HALF_UP);
            if (required.compareTo(BigDecimal.ZERO) <= 0) {
                throw new BusinessException("请填写要求预付金额");
            }
        }
        if (required.compareTo(totalAmount) > 0) {
            throw new BusinessException("要求预付金额不能超过订单实际金额");
        }
        return new SalesOrderPaymentTerms(term, mode, rate, required);
    }

    private String salesOrderShipmentPaymentPolicy(AlignedDtos.SalesOrderCreate req, String paymentTerm) {
        if ("FULL_PREPAYMENT".equals(paymentTerm)) return "ORDER_PAID";
        return shipmentPaymentPolicy(req.shipmentPaymentPolicy());
    }

    private String shipmentPaymentPolicy(String value) {
        return switch (blank(value).toUpperCase()) {
            case "SHIPMENT_PAID" -> "SHIPMENT_PAID";
            case "ORDER_PAID" -> "ORDER_PAID";
            default -> "NONE";
        };
    }

    private String outboundShipmentPaymentPolicy(Long salesOrderId, String requestedPolicy) {
        if (salesOrderId != null && salesOrderId > 0) {
            return jdbcTemplate.queryForObject(
                    "select shipment_payment_policy from sales_order where id = ?",
                    String.class, salesOrderId);
        }
        return shipmentPaymentPolicy(requestedPolicy);
    }

    private BigDecimal outboundShipmentControlAmount(String policy, boolean pricePending,
                                                      BigDecimal requestedAmount, BigDecimal totalAmount) {
        if ("NONE".equals(policy)) return BigDecimal.ZERO;
        if (!pricePending) return nvl(totalAmount).setScale(2, RoundingMode.HALF_UP);
        BigDecimal amount = nvl(requestedAmount).setScale(2, RoundingMode.HALF_UP);
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("款到发货且价格待定时，必须填写暂估最高金额");
        }
        return amount;
    }

    private ShipmentRelease validateOutboundShipmentPayment(OutboundLifecycleRow outbound) {
        if ("NONE".equals(outbound.shipmentPaymentPolicy())) {
            return new ShipmentRelease(BigDecimal.ZERO, BigDecimal.ZERO);
        }
        BigDecimal required;
        BigDecimal funded;
        if (outbound.salesOrderId() > 0) {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                    select total_amount, receivable_amount from sales_order where id = ? for update
                    """, outbound.salesOrderId());
            if (rows.isEmpty()) throw new BusinessException("销售订单不存在");
            BigDecimal orderAmount = decimalValue(rows.get(0).get("total_amount"));
            BigDecimal shippedReceivable = decimalValue(rows.get(0).get("receivable_amount"));
            required = "ORDER_PAID".equals(outbound.shipmentPaymentPolicy())
                    ? orderAmount : shippedReceivable.add(outbound.shipmentControlAmount());
            funded = salesOrderReleaseFunds(outbound.salesOrderId());
        } else {
            required = outbound.shipmentControlAmount();
            funded = lockCustomerAdvanceBalance(outbound.customerId())
                    .subtract(reservedCustomerAdvance(outbound.customerId())).max(BigDecimal.ZERO);
        }
        BigDecimal shortfall = required.subtract(funded).max(BigDecimal.ZERO);
        if (shortfall.compareTo(BigDecimal.ZERO) > 0) {
            throw new BusinessException("该出库单执行款到发货，当前资金还差" + moneyText(shortfall) + "元，不能确认发货");
        }
        if (outbound.salesOrderId() <= 0 && required.compareTo(BigDecimal.ZERO) > 0) {
            insert("""
                    insert into outbound_advance_allocation
                    (outbound_id,customer_id,amount,status,memo) values (?, ?, ?, 'RESERVED', ?)
                    """, outbound.id(), outbound.customerId(), required,
                    "直接出库款到发货占用预收款 " + outbound.orderNo());
        }
        return new ShipmentRelease(required, funded);
    }

    private ShipmentRelease validateOutboundSettlementPayment(OutboundLifecycleRow outbound, BigDecimal finalAmount) {
        if ("NONE".equals(outbound.shipmentPaymentPolicy())) {
            return new ShipmentRelease(BigDecimal.ZERO, BigDecimal.ZERO);
        }
        BigDecimal required;
        BigDecimal funded;
        if (outbound.salesOrderId() > 0) {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                    select total_amount, receivable_amount from sales_order where id = ? for update
                    """, outbound.salesOrderId());
            if (rows.isEmpty()) throw new BusinessException("销售订单不存在");
            required = "ORDER_PAID".equals(outbound.shipmentPaymentPolicy())
                    ? decimalValue(rows.get(0).get("total_amount"))
                    : decimalValue(rows.get(0).get("receivable_amount")).add(finalAmount);
            funded = salesOrderReleaseFunds(outbound.salesOrderId());
        } else {
            BigDecimal reserved = jdbcTemplate.queryForList("""
                    select amount from outbound_advance_allocation
                    where outbound_id=? and status='RESERVED' for update
                    """, BigDecimal.class, outbound.id()).stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal extra = finalAmount.subtract(reserved).max(BigDecimal.ZERO);
            if (extra.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal available = lockCustomerAdvanceBalance(outbound.customerId())
                        .subtract(reservedCustomerAdvance(outbound.customerId())).max(BigDecimal.ZERO);
                if (available.compareTo(extra) < 0) {
                    throw new BusinessException("最终金额超过暂估最高金额，当前资金还差"
                            + moneyText(extra.subtract(available)) + "元，不能完成结算");
                }
                insert("""
                        insert into outbound_advance_allocation
                        (outbound_id,customer_id,amount,status,memo) values (?, ?, ?, 'RESERVED', ?)
                        """, outbound.id(), outbound.customerId(), extra, "最终金额超过暂估上限，补充占用预收款");
                reserved = reserved.add(extra);
            }
            required = finalAmount;
            funded = reserved;
        }
        BigDecimal shortfall = required.subtract(funded).max(BigDecimal.ZERO);
        if (shortfall.compareTo(BigDecimal.ZERO) > 0) {
            throw new BusinessException("最终金额对应的款到发货资金还差" + moneyText(shortfall) + "元，不能完成结算");
        }
        return new ShipmentRelease(required, funded);
    }

    private void requirePrepaymentEligible(SalesOrderEditRow order) {
        if (!List.of("DRAFT", "OPEN", "PARTIAL_SHIPPED").contains(order.status())) {
            throw new BusinessException("只有未完成发货的订单可以登记或占用预付款");
        }
        if ("POSTPAID".equals(order.paymentTerm()) && "NONE".equals(order.shipmentPaymentPolicy())) {
            throw new BusinessException("该订单未设置预付款或款到发货条件");
        }
    }

    private void ensurePrepaymentCapacity(SalesOrderEditRow order, BigDecimal amount) {
        BigDecimal remaining = order.totalAmount().subtract(salesOrderReleaseFunds(order.id()));
        if (amount.compareTo(BigDecimal.ZERO) <= 0 || amount.compareTo(remaining) > 0) {
            throw new BusinessException("本次预付款不能超过订单尚未占用金额" + moneyText(remaining.max(BigDecimal.ZERO)) + "元");
        }
    }

    private Long insertAdvanceAllocation(Long salesOrderId, Long customerId, Long sourcePaymentId,
                                         BigDecimal amount, String memo) {
        return insert("""
                insert into sales_order_advance_allocation
                (sales_order_id, customer_id, source_payment_id, amount, status, memo)
                values (?, ?, ?, ?, 'RESERVED', ?)
                """, salesOrderId, customerId, sourcePaymentId, amount, blank(memo));
    }

    private BigDecimal reservedSalesOrderAdvance(Long salesOrderId) {
        BigDecimal value = jdbcTemplate.queryForObject("""
                select coalesce(sum(amount), 0) from sales_order_advance_allocation
                where sales_order_id = ? and status = 'RESERVED'
                """, BigDecimal.class, salesOrderId);
        return nvl(value);
    }

    private BigDecimal securedSalesOrderAdvance(Long salesOrderId) {
        BigDecimal value = jdbcTemplate.queryForObject("""
                select coalesce(sum(amount), 0) from sales_order_advance_allocation
                where sales_order_id = ? and status in ('RESERVED', 'APPLIED')
                """, BigDecimal.class, salesOrderId);
        return nvl(value);
    }

    private BigDecimal salesOrderReleaseFunds(Long salesOrderId) {
        BigDecimal value = jdbcTemplate.queryForObject("""
                select coalesce((select sum(amount) from payment
                                 where sales_order_id = ? and verification_status in ('VERIFIED', 'UNAPPLIED')
                                   and payment_method in ('BANK', 'CASH', 'ADVANCE')), 0)
                     + coalesce((select sum(amount) from sales_order_advance_allocation
                                 where sales_order_id = ? and status = 'RESERVED'), 0)
                     - coalesce((select sum(advance_credit_amount) from sales_return_order
                                 where sales_order_id = ? and status = 'CONFIRMED'), 0)
                """, BigDecimal.class, salesOrderId, salesOrderId, salesOrderId);
        return nvl(value).max(BigDecimal.ZERO);
    }

    private BigDecimal reservedCustomerAdvance(Long customerId) {
        BigDecimal value = jdbcTemplate.queryForObject("""
                select coalesce((select sum(amount) from sales_order_advance_allocation
                                 where customer_id = ? and status = 'RESERVED'), 0)
                     + coalesce((select sum(amount) from outbound_advance_allocation
                                 where customer_id = ? and status = 'RESERVED'), 0)
                """, BigDecimal.class, customerId, customerId);
        return nvl(value);
    }

    private BigDecimal customerAdvanceBalance(Long customerId) {
        List<BigDecimal> values = jdbcTemplate.query("""
                select balance from partner_account
                where partner_id = ? and account_type = 'CUSTOMER_ADVANCE'
                """, (rs, rowNum) -> rs.getBigDecimal(1), customerId);
        return values.isEmpty() ? BigDecimal.ZERO : nvl(values.get(0));
    }

    private BigDecimal lockCustomerAdvanceBalance(Long customerId) {
        jdbcTemplate.update("""
                insert into partner_account (partner_id, account_type, balance)
                values (?, 'CUSTOMER_ADVANCE', 0)
                on duplicate key update updated_at = updated_at
                """, customerId);
        return scalarDecimal("""
                select balance from partner_account
                where partner_id = %s and account_type = 'CUSTOMER_ADVANCE' for update
                """.formatted(customerId));
    }

    private BigDecimal decimalValue(Object value) {
        if (value instanceof BigDecimal decimal) return decimal;
        if (value instanceof Number number) return BigDecimal.valueOf(number.doubleValue());
        if (value == null) return BigDecimal.ZERO;
        return new BigDecimal(String.valueOf(value));
    }

    private long longValue(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value instanceof Number number ? number.longValue()
                : Long.parseLong(String.valueOf(value));
    }

    private Long nullableLongValue(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value == null) return null;
        long result = value instanceof Number number ? number.longValue()
                : Long.parseLong(String.valueOf(value));
        return result > 0 ? result : null;
    }

    private int intValue(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value == null) return 0;
        return value instanceof Number number ? number.intValue()
                : Integer.parseInt(String.valueOf(value));
    }

    private Integer nullableIntValue(Map<String, Object> row, String key) {
        return row.get(key) == null ? null : intValue(row, key);
    }

    private String stringValue(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private BigDecimal nullableDecimalValue(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? null : decimalValue(value);
    }

    private LocalDate localDateValue(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value == null) return null;
        if (value instanceof LocalDate date) return date;
        if (value instanceof Date date) return date.toLocalDate();
        return LocalDate.parse(String.valueOf(value));
    }

    private String safeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new BusinessException("供货版本快照生成失败");
        }
    }

    private String moneyText(BigDecimal value) {
        return nvl(value).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private void insertSalesOrderItems(Long salesOrderId, List<PreparedSalesItem> preparedItems) {
        for (PreparedSalesItem preparedItem : preparedItems) {
            AlignedDtos.SalesOrderCreate.Item item = preparedItem.item();
            PackagingDtos.Plan plan = preparedItem.plan();
            ProductDocumentSnapshot product = currentProductSnapshot(item.productId());
            insert("""
                    insert into sales_order_item
                    (sales_order_id, product_id, product_sku_snapshot, product_name_snapshot, product_spec_snapshot,
                     requested_quantity, requested_unit,
                     quantity, base_unit, price_unit, pricing_quantity, package_quantity, package_unit, packaging_plan_json,
                     packaging_effective_revision_id, packaging_snapshot_hash,
                     price_source_code,price_source_name,price_rule_version_id,price_snapshot_json,
                     no_charge, unit_price,tax_rate,
                     unit_price_excluding_tax,amount,amount_excluding_tax,tax_amount,settlement_total_amount,tax_rounding_difference)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, salesOrderId, item.productId(), product.sku(), product.name(), product.spec(),
                    plan.requestedQuantity(), plan.requestedUnit(),
                    plan.baseQuantity(), plan.baseUnit(), preparedItem.priceUnit(), plan.pricingQuantity(),
                    nvl(item.packageQuantity()), blank(item.packageUnit()), plan.snapshotJson(),
                    plan.effectiveRevisionId() == null ? 0 : plan.effectiveRevisionId(), plan.snapshotHash(),
                    preparedItem.priceSnapshot().sourceCode(), preparedItem.priceSnapshot().sourceName(),
                    preparedItem.priceSnapshot().ruleVersionId(), preparedItem.priceSnapshot().snapshotJson(),
                    bool(item.noCharge()), preparedItem.price(), preparedItem.taxLine().taxRate(),
                    preparedItem.taxLine().unitPriceExcludingTax(), preparedItem.amount(),
                    preparedItem.taxLine().amountExcludingTax(), preparedItem.taxLine().taxAmount(),
                    preparedItem.taxLine().settlementTotalAmount(), preparedItem.taxLine().roundingDifference());
        }
    }

    private List<BusinessPostingDtos.EntryDraft> inboundPostingEntries(Long inboundId) {
        List<BusinessPostingDtos.EntryDraft> entries = new ArrayList<>();
        jdbcTemplate.query("""
                select ii.id,ii.product_id,ii.inventory_batch_id,ii.quantity,ii.base_unit,
                       ii.inventory_amount_cost,o.warehouse_id,o.supplier_id
                from inbound_item ii join inbound_order o on o.id=ii.inbound_id
                where ii.inbound_id=? order by ii.id
                """, (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
            entries.add(new BusinessPostingDtos.EntryDraft(
                    "INVENTORY_ON_HAND", "INCREASE", rs.getLong("supplier_id"), rs.getLong("product_id"),
                    rs.getLong("warehouse_id"), rs.getLong("inventory_batch_id"), "INBOUND_ITEM", rs.getLong("id"),
                    rs.getBigDecimal("quantity"), rs.getString("base_unit"), BigDecimal.ZERO, "入库增加实际结存"));
            entries.add(new BusinessPostingDtos.EntryDraft(
                    "INVENTORY_COST", "INCREASE", rs.getLong("supplier_id"), rs.getLong("product_id"),
                    rs.getLong("warehouse_id"), rs.getLong("inventory_batch_id"), "INBOUND_ITEM", rs.getLong("id"),
                    BigDecimal.ZERO, "", rs.getBigDecimal("inventory_amount_cost"), "入库增加库存成本"));
        }, inboundId);
        Map<String, Object> header = jdbcTemplate.queryForMap(
                "select supplier_id,total_amount from inbound_order where id=?", inboundId);
        entries.add(new BusinessPostingDtos.EntryDraft(
                "PAYABLE", "INCREASE", ((Number) header.get("supplier_id")).longValue(), 0L, 0L, 0L,
                "INBOUND", inboundId, BigDecimal.ZERO, "", (BigDecimal) header.get("total_amount"), "入库形成应付"));
        return entries;
    }

    private List<BusinessPostingDtos.EntryDraft> outboundShipmentPostingEntries(Long outboundId) {
        List<BusinessPostingDtos.EntryDraft> entries = new ArrayList<>();
        jdbcTemplate.query("""
                select oi.id,oi.product_id,oi.batch_id,oi.quantity,oi.base_unit,oi.amount,
                       oi.inventory_amount_cost,o.warehouse_id,o.customer_id
                from outbound_item oi join outbound_order o on o.id=oi.outbound_id
                where oi.outbound_id=? order by oi.id
                """, (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
            entries.add(new BusinessPostingDtos.EntryDraft(
                    "INVENTORY_ON_HAND", "DECREASE", rs.getLong("customer_id"), rs.getLong("product_id"),
                    rs.getLong("warehouse_id"), rs.getLong("batch_id"), "OUTBOUND_ITEM", rs.getLong("id"),
                    rs.getBigDecimal("quantity"), rs.getString("base_unit"), BigDecimal.ZERO, "出库减少实际结存"));
            entries.add(new BusinessPostingDtos.EntryDraft(
                    "INVENTORY_COST", "DECREASE", rs.getLong("customer_id"), rs.getLong("product_id"),
                    rs.getLong("warehouse_id"), rs.getLong("batch_id"), "OUTBOUND_ITEM", rs.getLong("id"),
                    BigDecimal.ZERO, "", rs.getBigDecimal("inventory_amount_cost"), "出库结转库存成本"));
            entries.add(new BusinessPostingDtos.EntryDraft(
                    "SALES_FULFILLMENT", "INCREASE", rs.getLong("customer_id"), rs.getLong("product_id"),
                    rs.getLong("warehouse_id"), rs.getLong("batch_id"), "OUTBOUND_ITEM", rs.getLong("id"),
                    rs.getBigDecimal("quantity"), rs.getString("base_unit"), rs.getBigDecimal("amount"), "销售履约"));
        }, outboundId);
        return entries;
    }

    private List<BusinessPostingDtos.EntryDraft> receivablePostingEntries(Long outboundId, BigDecimal amount,
                                                                           String direction, String memo) {
        Long customerId = jdbcTemplate.queryForObject(
                "select customer_id from outbound_order where id=?", Long.class, outboundId);
        return List.of(new BusinessPostingDtos.EntryDraft(
                "RECEIVABLE", direction, customerId, 0L, 0L, 0L,
                "OUTBOUND", outboundId, BigDecimal.ZERO, "", amount, memo));
    }

    private List<BusinessPostingDtos.EntryDraft> salesReturnPostingEntries(Long returnId, BigDecimal receivableAmount,
                                                                            String inventoryDirection,
                                                                            String receivableDirection) {
        List<BusinessPostingDtos.EntryDraft> entries = new ArrayList<>();
        jdbcTemplate.query("""
                select ri.id,ri.product_id,ri.inventory_batch_id,ri.quantity,ri.base_unit,
                       ri.inventory_amount_cost,r.warehouse_id,r.customer_id
                from sales_return_item ri join sales_return_order r on r.id=ri.sales_return_id
                where ri.sales_return_id=? order by ri.id
                """, (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
            entries.add(new BusinessPostingDtos.EntryDraft(
                    "INVENTORY_ON_HAND", inventoryDirection, rs.getLong("customer_id"), rs.getLong("product_id"),
                    rs.getLong("warehouse_id"), rs.getLong("inventory_batch_id"), "SALES_RETURN_ITEM", rs.getLong("id"),
                    rs.getBigDecimal("quantity"), rs.getString("base_unit"), BigDecimal.ZERO, "销售退货恢复库存"));
            entries.add(new BusinessPostingDtos.EntryDraft(
                    "INVENTORY_COST", inventoryDirection, rs.getLong("customer_id"), rs.getLong("product_id"),
                    rs.getLong("warehouse_id"), rs.getLong("inventory_batch_id"), "SALES_RETURN_ITEM", rs.getLong("id"),
                    BigDecimal.ZERO, "", rs.getBigDecimal("inventory_amount_cost"), "销售退货恢复库存成本"));
        }, returnId);
        Long customerId = jdbcTemplate.queryForObject(
                "select customer_id from sales_return_order where id=?", Long.class, returnId);
        entries.add(new BusinessPostingDtos.EntryDraft(
                "RECEIVABLE", receivableDirection, customerId, 0L, 0L, 0L,
                "SALES_RETURN", returnId, BigDecimal.ZERO, "", receivableAmount, "销售退货冲减应收"));
        return entries;
    }

    private LocalDate documentBusinessDate(String table, Long id, String dateColumn) {
        Date value = jdbcTemplate.queryForObject(
                "select coalesce(date(" + dateColumn + "),current_date) from " + table + " where id=?",
                Date.class, id);
        return value == null ? LocalDate.now() : value.toLocalDate();
    }

    private String salesReturnNo(Long returnId) {
        return jdbcTemplate.queryForObject("select return_no from sales_return_order where id=?", String.class, returnId);
    }

    private String adjustmentNo(Long adjustmentId) {
        return jdbcTemplate.queryForObject(
                "select adjustment_no from receivable_adjustment where id=?", String.class, adjustmentId);
    }

    private String requiredSnapshot(Map<Long, String> snapshots, Long itemId) {
        String snapshot = snapshots.get(itemId);
        if (!StringUtils.hasText(snapshot)) throw new BusinessException("订单变更明细缺少原包装规则快照");
        return snapshot;
    }

    private Map<Long, String> orderItemSnapshots(String table, String orderColumn, Long orderId) {
        Map<Long, String> result = new HashMap<>();
        jdbcTemplate.query("select id,packaging_plan_json from " + table + " where " + orderColumn + "=?",
                rs -> { result.put(rs.getLong(1), rs.getString(2)); }, orderId);
        return result;
    }

    private Map<Long, String> orderItemPriceSnapshots(String table, String orderColumn, Long orderId) {
        Map<Long, String> result = new HashMap<>();
        jdbcTemplate.query("select id,price_snapshot_json from " + table + " where " + orderColumn + "=?",
                rs -> { result.put(rs.getLong(1), rs.getString(2)); }, orderId);
        return result;
    }

    private Map<Long, Long> orderItemProductIds(String table, String orderColumn, Long orderId) {
        Map<Long, Long> result = new HashMap<>();
        jdbcTemplate.query("select id,product_id from " + table + " where " + orderColumn + "=?",
                rs -> { result.put(rs.getLong(1), rs.getLong(2)); }, orderId);
        return result;
    }

    private String orderItemPackagingSnapshot(String table, String orderColumn, Long orderId, Long itemId) {
        List<String> snapshots = jdbcTemplate.query(
                "select packaging_plan_json from " + table + " where " + orderColumn + "=? and id=?",
                (rs, rowNum) -> rs.getString(1), orderId, itemId);
        if (snapshots.isEmpty() || !StringUtils.hasText(snapshots.get(0)))
            throw new BusinessException("订单明细或历史包装规则快照不存在");
        return snapshots.get(0);
    }

    private List<BusinessPostingDtos.EntryDraft> purchaseCommitmentEntries(Long purchaseOrderId, String direction) {
        Long supplierId = jdbcTemplate.queryForObject(
                "select supplier_id from purchase_order where id=?", Long.class, purchaseOrderId);
        List<BusinessPostingDtos.EntryDraft> entries = new ArrayList<>(jdbcTemplate.query("""
                select id,product_id,quantity,base_unit,amount from purchase_order_item
                where purchase_order_id=? and status='ENABLED' order by id
                """, (rs, rowNum) -> new BusinessPostingDtos.EntryDraft(
                "PURCHASE_COMMITMENT_QTY", direction, supplierId, rs.getLong("product_id"), 0L, 0L,
                "PURCHASE_ORDER_ITEM", rs.getLong("id"), rs.getBigDecimal("quantity"),
                rs.getString("base_unit"), rs.getBigDecimal("amount"), "采购订单承诺明细"), purchaseOrderId));
        BigDecimal total = jdbcTemplate.queryForObject("select total_amount from purchase_order where id=?",
                BigDecimal.class, purchaseOrderId);
        entries.add(new BusinessPostingDtos.EntryDraft("PURCHASE_COMMITMENT_AMOUNT", direction,
                supplierId, 0L, 0L, 0L, "PURCHASE_ORDER", purchaseOrderId,
                BigDecimal.ZERO, "", total, "采购订单承诺金额"));
        return entries;
    }

    private List<BusinessPostingDtos.EntryDraft> salesCommitmentEntries(Long salesOrderId, String direction) {
        Long customerId = jdbcTemplate.queryForObject(
                "select customer_id from sales_order where id=?", Long.class, salesOrderId);
        List<BusinessPostingDtos.EntryDraft> entries = new ArrayList<>(jdbcTemplate.query("""
                select id,product_id,quantity,base_unit,amount from sales_order_item
                where sales_order_id=? and status='ENABLED' order by id
                """, (rs, rowNum) -> new BusinessPostingDtos.EntryDraft(
                "SALES_COMMITMENT_QTY", direction, customerId, rs.getLong("product_id"), 0L, 0L,
                "SALES_ORDER_ITEM", rs.getLong("id"), rs.getBigDecimal("quantity"),
                rs.getString("base_unit"), rs.getBigDecimal("amount"), "销售订单承诺明细"), salesOrderId));
        BigDecimal total = jdbcTemplate.queryForObject("select total_amount from sales_order where id=?",
                BigDecimal.class, salesOrderId);
        entries.add(new BusinessPostingDtos.EntryDraft("SALES_COMMITMENT_AMOUNT", direction,
                customerId, 0L, 0L, 0L, "SALES_ORDER", salesOrderId,
                BigDecimal.ZERO, "", total, "销售订单承诺金额"));
        return entries;
    }

    private void recordInitialOrderRevision(String orderType, Long orderId, String orderNo) {
        recordOrderRevision(orderType, orderId, orderNo, 1, "INITIAL", "订单暂存初始版本",
                null, orderSnapshot(orderType, orderId), 0L, 0L);
    }

    private void refreshInitialOrderRevision(String orderType, Long orderId) {
        jdbcTemplate.update("""
                update business_order_revision set snapshot_after_json=?,created_at=current_timestamp
                where order_type=? and order_id=? and revision_no=1 and change_type='INITIAL'
                """, orderSnapshot(orderType, orderId), orderType, orderId);
    }

    private void linkOrderRevisionPosting(String orderType, Long orderId, int revisionNo, Long postingBatchId) {
        jdbcTemplate.update("""
                update business_order_revision set posted_batch_id=?
                where order_type=? and order_id=? and revision_no=?
                """, postingBatchId, orderType, orderId, revisionNo);
    }

    private void recordOrderRevision(String orderType, Long orderId, String orderNo, int revisionNo,
                                     String changeType, String reason, String before, String after,
                                     Long reversedBatchId, Long postedBatchId) {
        List<Long> existing = jdbcTemplate.queryForList("""
                select id from business_order_revision where order_type=? and order_id=? and revision_no=? for update
                """, Long.class, orderType, orderId, revisionNo);
        SecurityContext.Principal principal = SecurityContext.require();
        if (!existing.isEmpty()) {
            jdbcTemplate.update("""
                    update business_order_revision set change_type=?,change_reason=?,snapshot_before_json=?,
                      snapshot_after_json=?,reversed_posting_batch_id=?,posted_batch_id=?,
                      operator_id=?,operator_name=?,created_at=current_timestamp where id=?
                    """, changeType, reason, before, after, id(reversedBatchId), id(postedBatchId),
                    principal.userId(), principal.employeeName(), existing.get(0));
            return;
        }
        insert("""
                insert into business_order_revision
                (order_type,order_id,order_no,revision_no,change_no,change_type,change_reason,
                 snapshot_before_json,snapshot_after_json,reversed_posting_batch_id,posted_batch_id,
                 operator_id,operator_name)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, orderType, orderId, orderNo, revisionNo, nextNo("BG"), changeType, reason,
                before, after, id(reversedBatchId), id(postedBatchId), principal.userId(), principal.employeeName());
    }

    private String orderSnapshot(String orderType, Long orderId) {
        try {
            Map<String, Object> snapshot = new LinkedHashMap<>();
            if ("PURCHASE_ORDER".equals(orderType)) {
                snapshot.put("header", jdbcTemplate.queryForMap("""
                        select id,order_no,supplier_id,business_date,expected_date,source_type,source_note,
                               original_amount,discount_rate,discount_amount,adjustment_amount,total_quantity,
                               received_quantity,total_amount,paid_amount,payment_status,status,current_revision_no,memo
                        from purchase_order where id=?
                        """, orderId));
                snapshot.put("items", jdbcTemplate.queryForList("""
                        select id,product_id,product_sku_snapshot,product_name_snapshot,product_spec_snapshot,
                               requested_quantity,requested_unit,quantity,base_unit,received_quantity,
                               received_requested_quantity,price_unit,pricing_quantity,package_quantity,package_unit,
                               packaging_plan_json,packaging_effective_revision_id,packaging_snapshot_hash,
                               price_source_code,price_source_name,price_rule_version_id,price_snapshot_json,
                               no_charge,unit_price,amount,status from purchase_order_item where purchase_order_id=? order by id
                        """, orderId));
            } else {
                snapshot.put("header", jdbcTemplate.queryForMap("""
                        select id,order_no,title,customer_id,address_id,address_snapshot,business_date,delivery_plan_date,
                               original_amount,discount_rate,discount_amount,adjustment_amount,total_amount,
                               receivable_amount,received_amount,adjusted_amount,payment_status,payment_term,
                               shipment_payment_policy,deposit_mode,deposit_rate,required_deposit_amount,
                               status,current_revision_no,memo from sales_order where id=?
                        """, orderId));
                snapshot.put("items", jdbcTemplate.queryForList("""
                        select id,product_id,product_sku_snapshot,product_name_snapshot,product_spec_snapshot,
                               requested_quantity,requested_unit,quantity,shipped_quantity,shipped_requested_quantity,
                               base_unit,price_unit,pricing_quantity,package_quantity,package_unit,packaging_plan_json,
                               packaging_effective_revision_id,packaging_snapshot_hash,
                               price_source_code,price_source_name,price_rule_version_id,price_snapshot_json,
                               no_charge,unit_price,amount,status
                        from sales_order_item where sales_order_id=? order by id
                        """, orderId));
            }
            return objectMapper.writeValueAsString(snapshot);
        } catch (Exception ex) {
            throw new BusinessException("订单版本快照生成失败");
        }
    }

    private void validatePurchaseAmendmentItems(Long orderId, List<PreparedPurchaseItem> items) {
        Map<Long, AmendmentItemState> existing = amendmentItemStates(
                "purchase_order_item", "purchase_order_id", "received_quantity", "received_requested_quantity", orderId);
        validateAmendmentItemSet(items.stream().map(item -> item.item().itemId()).toList(), existing);
        for (PreparedPurchaseItem prepared : items) {
            Long itemId = prepared.item().itemId();
            if (itemId == null || itemId <= 0) continue;
            AmendmentItemState state = existing.get(itemId);
            if (!Objects.equals(state.productId(), prepared.item().productId()))
                throw new BusinessException("采购订单变更不能替换既有明细商品；请停用未履约行后新增一行");
            if (prepared.plan().requestedQuantity().compareTo(state.fulfilledRequested()) < 0
                    || prepared.plan().baseQuantity().compareTo(state.fulfilledBase()) < 0)
                throw new BusinessException("采购订单变更数量不能小于该行累计实际入库数量");
        }
        assertFulfilledItemsRetained(items.stream().map(item -> item.item().itemId()).toList(), existing, "采购");
    }

    private void applyPurchaseAmendmentItems(Long orderId, List<PreparedPurchaseItem> items) {
        List<Long> originalIds = jdbcTemplate.queryForList(
                "select id from purchase_order_item where purchase_order_id=? and status='ENABLED' for update",
                Long.class, orderId);
        List<Long> retained = new ArrayList<>();
        for (PreparedPurchaseItem prepared : items) {
            PurchaseCreateRequest.Item item = prepared.item();
            PackagingDtos.Plan plan = prepared.plan();
            if (item.itemId() == null || item.itemId() <= 0) {
                insertPurchaseOrderItems(orderId, List.of(prepared));
                continue;
            }
            retained.add(item.itemId());
            jdbcTemplate.update("""
                    update purchase_order_item set requested_quantity=?,requested_unit=?,quantity=?,base_unit=?,
                      price_unit=?,pricing_quantity=?,package_quantity=?,package_unit=?,packaging_plan_json=?,
                      packaging_effective_revision_id=?,packaging_snapshot_hash=?,
                      price_source_code=?,price_source_name=?,price_rule_version_id=?,price_snapshot_json=?,
                      no_charge=?,unit_price=?,tax_rate=?,
                      unit_price_excluding_tax=?,amount=?,amount_excluding_tax=?,tax_amount=?,
                      settlement_total_amount=?,tax_rounding_difference=?,status='ENABLED'
                    where id=? and purchase_order_id=?
                    """, plan.requestedQuantity(), plan.requestedUnit(), plan.baseQuantity(), plan.baseUnit(),
                    prepared.priceUnit(), plan.pricingQuantity(), nvl(item.packageQuantity()),
                    blank(item.packageUnit()), plan.snapshotJson(),
                    plan.effectiveRevisionId() == null ? 0 : plan.effectiveRevisionId(), plan.snapshotHash(),
                    prepared.priceSnapshot().sourceCode(), prepared.priceSnapshot().sourceName(),
                    prepared.priceSnapshot().ruleVersionId(), prepared.priceSnapshot().snapshotJson(),
                    bool(item.noCharge()), prepared.price(), prepared.taxLine().taxRate(), prepared.taxLine().unitPriceExcludingTax(),
                    prepared.amount(), prepared.taxLine().amountExcludingTax(), prepared.taxLine().taxAmount(),
                    prepared.taxLine().settlementTotalAmount(), prepared.taxLine().roundingDifference(), item.itemId(), orderId);
        }
        List<Long> omitted = originalIds.stream().filter(id -> !retained.contains(id)).toList();
        if (!omitted.isEmpty()) {
            jdbcTemplate.update("""
                    update purchase_order_item set status='DISABLED'
                    where purchase_order_id=? and id in (%s)
                    """.formatted(placeholders(omitted.size())), joinArgs(orderId, omitted));
        }
    }

    private void refreshPurchaseOrderReceiptStatus(Long orderId) {
        jdbcTemplate.update("""
                update purchase_order o set received_quantity=(select coalesce(sum(received_quantity),0)
                    from purchase_order_item where purchase_order_id=o.id and status='ENABLED'),
                  status=case
                    when not exists(select 1 from purchase_order_item i where i.purchase_order_id=o.id
                      and i.status='ENABLED' and i.received_requested_quantity<i.requested_quantity) then 'RECEIVED'
                    when exists(select 1 from purchase_order_item i where i.purchase_order_id=o.id
                      and i.status='ENABLED' and i.received_requested_quantity>0) then 'PARTIAL_RECEIVED'
                    else 'ORDERED' end where o.id=?
                """, orderId);
    }

    private void validateSalesAmendmentItems(Long orderId, List<PreparedSalesItem> items) {
        Map<Long, AmendmentItemState> existing = amendmentItemStates(
                "sales_order_item", "sales_order_id", "shipped_quantity", "shipped_requested_quantity", orderId);
        validateAmendmentItemSet(items.stream().map(item -> item.item().itemId()).toList(), existing);
        for (PreparedSalesItem prepared : items) {
            Long itemId = prepared.item().itemId();
            if (itemId == null || itemId <= 0) continue;
            AmendmentItemState state = existing.get(itemId);
            if (!Objects.equals(state.productId(), prepared.item().productId()))
                throw new BusinessException("销售订单变更不能替换既有明细商品；请停用未履约行后新增一行");
            if (prepared.plan().requestedQuantity().compareTo(state.fulfilledRequested()) < 0
                    || prepared.plan().baseQuantity().compareTo(state.fulfilledBase()) < 0)
                throw new BusinessException("销售订单变更数量不能小于该行累计实际出库数量");
        }
        assertFulfilledItemsRetained(items.stream().map(item -> item.item().itemId()).toList(), existing, "销售");
    }

    private void applySalesAmendmentItems(Long orderId, List<PreparedSalesItem> items) {
        List<Long> originalIds = jdbcTemplate.queryForList(
                "select id from sales_order_item where sales_order_id=? and status='ENABLED' for update",
                Long.class, orderId);
        List<Long> retained = new ArrayList<>();
        for (PreparedSalesItem prepared : items) {
            AlignedDtos.SalesOrderCreate.Item item = prepared.item();
            PackagingDtos.Plan plan = prepared.plan();
            if (item.itemId() == null || item.itemId() <= 0) {
                insertSalesOrderItems(orderId, List.of(prepared));
                continue;
            }
            retained.add(item.itemId());
            jdbcTemplate.update("""
                    update sales_order_item set requested_quantity=?,requested_unit=?,quantity=?,base_unit=?,
                      price_unit=?,pricing_quantity=?,package_quantity=?,package_unit=?,packaging_plan_json=?,
                      packaging_effective_revision_id=?,packaging_snapshot_hash=?,
                      price_source_code=?,price_source_name=?,price_rule_version_id=?,price_snapshot_json=?,
                      no_charge=?,unit_price=?,tax_rate=?,
                      unit_price_excluding_tax=?,amount=?,amount_excluding_tax=?,tax_amount=?,settlement_total_amount=?,
                      tax_rounding_difference=?,status='ENABLED'
                    where id=? and sales_order_id=?
                    """, plan.requestedQuantity(), plan.requestedUnit(), plan.baseQuantity(), plan.baseUnit(),
                    prepared.priceUnit(), plan.pricingQuantity(), nvl(item.packageQuantity()),
                    blank(item.packageUnit()), plan.snapshotJson(),
                    plan.effectiveRevisionId() == null ? 0 : plan.effectiveRevisionId(), plan.snapshotHash(),
                    prepared.priceSnapshot().sourceCode(), prepared.priceSnapshot().sourceName(),
                    prepared.priceSnapshot().ruleVersionId(), prepared.priceSnapshot().snapshotJson(),
                    bool(item.noCharge()), prepared.price(), prepared.taxLine().taxRate(),
                    prepared.taxLine().unitPriceExcludingTax(), prepared.amount(), prepared.taxLine().amountExcludingTax(),
                    prepared.taxLine().taxAmount(), prepared.taxLine().settlementTotalAmount(),
                    prepared.taxLine().roundingDifference(), item.itemId(), orderId);
        }
        List<Long> omitted = originalIds.stream().filter(id -> !retained.contains(id)).toList();
        if (!omitted.isEmpty()) {
            jdbcTemplate.update("""
                    update sales_order_item set status='DISABLED'
                    where sales_order_id=? and id in (%s)
                    """.formatted(placeholders(omitted.size())), joinArgs(orderId, omitted));
        }
    }

    private Map<Long, AmendmentItemState> amendmentItemStates(String table, String orderColumn,
                                                               String fulfilledBaseColumn,
                                                               String fulfilledRequestedColumn, Long orderId) {
        Map<Long, AmendmentItemState> result = new HashMap<>();
        jdbcTemplate.query("select id,product_id," + fulfilledBaseColumn + "," + fulfilledRequestedColumn
                        + " from " + table + " where " + orderColumn + "=? and status='ENABLED' for update",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> result.put(rs.getLong(1), new AmendmentItemState(
                        rs.getLong(1), rs.getLong(2), rs.getBigDecimal(3), rs.getBigDecimal(4))), orderId);
        return result;
    }

    private void validateAmendmentItemSet(List<Long> itemIds, Map<Long, AmendmentItemState> existing) {
        Set<Long> seen = new HashSet<>();
        for (Long itemId : itemIds) {
            if (itemId == null || itemId <= 0) continue;
            if (!seen.add(itemId)) throw new BusinessException("订单变更不能重复提交同一明细行");
            if (!existing.containsKey(itemId)) throw new BusinessException("订单变更包含不属于当前订单的明细行");
        }
    }

    private void assertFulfilledItemsRetained(List<Long> itemIds, Map<Long, AmendmentItemState> existing, String label) {
        Set<Long> retained = new HashSet<>();
        for (Long itemId : itemIds) if (itemId != null && itemId > 0) retained.add(itemId);
        for (AmendmentItemState state : existing.values()) {
            if (!retained.contains(state.id()) && (state.fulfilledBase().compareTo(BigDecimal.ZERO) > 0
                    || state.fulfilledRequested().compareTo(BigDecimal.ZERO) > 0)) {
                throw new BusinessException(label + "订单已有履约的明细不能删除或停用");
            }
        }
    }

    private String requiredReason(String value, String action) {
        String reason = blank(value).trim();
        if (!StringUtils.hasText(reason)) throw new BusinessException(action + "必须填写原因");
        if (reason.length() > 500) throw new BusinessException(action + "原因不能超过500字");
        return reason;
    }

    private long id(Long value) { return value == null ? 0L : value; }

    private String placeholders(int size) {
        return String.join(",", Collections.nCopies(size, "?"));
    }

    private Object[] joinArgs(Object first, List<?> rest) {
        List<Object> args = new ArrayList<>();
        args.add(first);
        args.addAll(rest);
        return args.toArray();
    }

    public DashboardDto dashboard() {
        BigDecimal stockQuantity = scalarDecimal("select coalesce(sum(quantity_available), 0) from stock_balance");
        BigDecimal stockCost = scalarDecimal("select coalesce(sum(amount_cost), 0) from stock_balance");
        BigDecimal receivable = totalReceivableBalance();
        BigDecimal paid = scalarDecimal("""
                select
                  coalesce((select sum(amount) from payment
                            where verification_status <> 'REVERSED' and payment_method in ('BANK', 'CASH')), 0)
                  + coalesce((select sum(case when status = 'DISCOUNTED' then net_proceeds
                                              when status = 'MATURED' then face_amount else 0 end)
                              from acceptance_bill), 0)
                """);
        BigDecimal todayIn = scalarDecimal("select coalesce(sum(total_quantity), 0) from inbound_order where business_date = curdate() and status='STOCKED'");
        BigDecimal todayOut = scalarDecimal("select coalesce(sum(total_quantity), 0) from outbound_order where business_date = curdate() and status='SHIPPED'");

        return new DashboardDto(
                stockQuantity,
                stockCost,
                receivable,
                paid,
                todayIn,
                todayOut,
                ownerAttention(),
                fulfillmentReminders(),
                jdbcTemplate.query("""
                        select b.*, p.sku, p.name product_name, p.grade, p.spec, w.name warehouse_name,
                               io.id source_inbound_id, io.order_no source_inbound_no,
                               coalesce(r.reserved_quantity,0) reserved_quantity,
                               greatest(b.quantity_available-coalesce(r.reserved_quantity,0),0) free_quantity,
                               coalesce(t.in_transit_quantity,0) in_transit_quantity
                        from inventory_batch b
                        join product p on p.id = b.product_id
                        join warehouse w on w.id = b.warehouse_id
                        left join inbound_item ii on b.source_type = 'INBOUND' and ii.id = b.source_id
                        left join inbound_order io on io.id = ii.inbound_id
                        left join (
                          select inventory_batch_id,sum(quantity) reserved_quantity from outbound_allocation
                          where status='DRAFT' group by inventory_batch_id
                        ) r on r.inventory_batch_id=b.id
                        left join (
                          select i.source_batch_id,sum(i.quantity) in_transit_quantity
                          from inventory_transfer_item i join inventory_transfer_order o on o.id=i.transfer_id
                          where o.status='IN_TRANSIT' group by i.source_batch_id
                        ) t on t.source_batch_id=b.id
                        where b.quantity_available > 0
                        order by b.quantity_available asc, b.received_at asc
                        limit 8
                        """, inventoryMapper()),
                jdbcTemplate.query("""
                        select o.*, p.name supplier_name, w.name warehouse_name, po.order_no purchase_order_no,
                               (select group_concat(concat(
                                  trim(trailing '.' from trim(trailing '0' from cast(ii.requested_quantity as char))),
                                  coalesce(nullif(ii.requested_unit,''),ii.base_unit)
                                ) order by ii.id separator ' / ')
                                from inbound_item ii where ii.inbound_id=o.id) quantity_summary
                        from inbound_order o
                        join partner p on p.id = o.supplier_id
                        join warehouse w on w.id = o.warehouse_id
                        left join purchase_order po on po.id = o.purchase_order_id
                        order by o.id desc limit 8
                        """, inboundMapper()),
                jdbcTemplate.query("""
                        select * from (
                          select 'SALES_ORDER' source_type, o.id source_id, o.order_no source_no,
                                 o.customer_id partner_id, p.name customer_name, o.business_date,
                                 o.receivable_amount total_amount, o.received_amount paid_amount,
                                 greatest(o.receivable_amount - o.received_amount - o.adjusted_amount, 0) unpaid_amount,
                                 o.settlement_days_snapshot settlement_days, o.due_date,
                                 greatest(datediff(current_date,o.due_date),0) overdue_days,
                                 o.payment_status, o.status
                          from sales_order o
                          join partner p on p.id = o.customer_id
                          where o.status not in ('DRAFT', 'CANCELLED')
                            and greatest(o.receivable_amount - o.received_amount - o.adjusted_amount, 0) > 0
                          union all
                          select 'OUTBOUND' source_type, o.id source_id, o.order_no source_no,
                                 o.customer_id partner_id, p.name customer_name, o.business_date,
                                 o.receivable_amount total_amount, o.paid_amount,
                                 greatest(o.receivable_amount - o.paid_amount, 0) unpaid_amount,
                                 o.settlement_days_snapshot settlement_days, o.due_date,
                                 greatest(datediff(current_date,o.due_date),0) overdue_days,
                                 o.payment_status, o.status
                          from outbound_order o
                          join partner p on p.id = o.customer_id
                          where o.sales_order_id = 0
                            and o.status = 'SHIPPED'
                            and greatest(o.receivable_amount - o.paid_amount, 0) > 0
                        ) recent_receivable
                        order by business_date desc, source_id desc
                        limit 8
                        """, (rs, rowNum) -> new OrderDtos.ReceivableRow(
                        rs.getString("source_type"), rs.getLong("source_id"), rs.getString("source_no"),
                        rs.getLong("partner_id"), rs.getString("customer_name"), rs.getDate("business_date").toLocalDate(),
                        rs.getBigDecimal("total_amount"), rs.getBigDecimal("paid_amount"),
                        rs.getBigDecimal("unpaid_amount"), rs.getInt("settlement_days"),
                        rs.getDate("due_date").toLocalDate(), rs.getInt("overdue_days"),
                        rs.getString("payment_status"), rs.getString("status"))),
                jdbcTemplate.query("""
                        select p.*, c.name customer_name, so.order_no sales_order_no, o.order_no outbound_no,
                               b.account_name business_bank_account_name,
                               (select count(*) from payment_allocation a
                                where a.payment_id=p.id and a.status='ACTIVE') allocation_count
                        from payment p
                        join partner c on c.id = p.customer_id
                        left join sales_order so on so.id = p.sales_order_id
                        left join outbound_order o on o.id = p.outbound_id
                        left join business_bank_account b on b.id=p.business_bank_account_id
                        order by p.id desc limit 8
                        """, paymentMapper())
        );
    }

    private List<DashboardDto.FulfillmentReminder> fulfillmentReminders() {
        return jdbcTemplate.query("""
                select reminder.*,
                       case datediff(reminder.planned_date,current_date)
                         when 0 then 'TODAY'
                         when 1 then 'TOMORROW'
                         else 'UPCOMING'
                       end timing_bucket
                from (
                  select 'PURCHASE_ORDER' source_type,o.id source_id,o.order_no,
                         p.name partner_name,o.expected_date planned_date,
                         (select count(*) from purchase_order_item i
                          where i.purchase_order_id=o.id and i.status='ENABLED'
                            and i.received_requested_quantity<i.requested_quantity) remaining_line_count,
                         w.name warehouse_name
                  from purchase_order o
                  join partner p on p.id=o.supplier_id
                  left join warehouse w on w.id=o.expected_warehouse_id
                  where o.status in ('ORDERED','PARTIAL_RECEIVED')
                    and coalesce(o.source_type,'STANDARD')<>'DIRECT_TRADE'
                    and o.expected_date between current_date and date_add(current_date,interval 7 day)
                    and exists(select 1 from purchase_order_item i
                               where i.purchase_order_id=o.id and i.status='ENABLED'
                                 and i.received_requested_quantity<i.requested_quantity)
                  union all
                  select 'SALES_ORDER' source_type,o.id source_id,o.order_no,
                         p.name partner_name,o.delivery_plan_date planned_date,
                         (select count(*) from sales_order_item i
                          where i.sales_order_id=o.id and i.status='ENABLED'
                            and i.shipped_requested_quantity<i.requested_quantity) remaining_line_count,
                         w.name warehouse_name
                  from sales_order o
                  join partner p on p.id=o.customer_id
                  left join warehouse w on w.id=o.fulfillment_warehouse_id
                  where o.status in ('OPEN','PARTIAL_SHIPPED')
                    and o.delivery_plan_date between current_date and date_add(current_date,interval 7 day)
                    and exists(select 1 from sales_order_item i
                               where i.sales_order_id=o.id and i.status='ENABLED'
                                 and i.shipped_requested_quantity<i.requested_quantity)
                ) reminder
                order by reminder.planned_date,reminder.source_type,reminder.source_id
                """, (rs, rowNum) -> new DashboardDto.FulfillmentReminder(
                rs.getString("source_type"), rs.getLong("source_id"), rs.getString("order_no"),
                rs.getString("partner_name"), rs.getDate("planned_date").toLocalDate(),
                rs.getString("timing_bucket"), rs.getLong("remaining_line_count"),
                rs.getString("warehouse_name")));
    }

    private DashboardDto.OwnerAttention ownerAttention() {
        DashboardDto.SettlementAttention receivables = settlementAttention(true);
        DashboardDto.SettlementAttention payables = settlementAttention(false);

        List<InventoryDtos.ReconciliationRow> inventoryRows = inventoryService.reconciliation(null, null, false);
        long mismatch = inventoryRows.stream().filter(row -> "MISMATCH".equals(row.status())).count();
        long negative = inventoryRows.stream().filter(row -> row.negativeSourceCount() > 0).count();
        long unavailable = inventoryRows.stream().filter(row -> row.unavailableBatchCount() > 0).count();
        DashboardDto.InventoryAttention inventory = new DashboardDto.InventoryAttention(
                inventoryRows.stream().filter(row -> !"MATCH".equals(row.status())).count(),
                mismatch, negative, unavailable);

        Map<String, Object> bankRow = jdbcTemplate.queryForMap("""
                select count(*) unmatched_count,coalesce(sum(remaining_amount),0) unmatched_amount
                from (
                  select greatest(l.amount-
                    coalesce((select sum(m.matched_amount) from bank_reconciliation_match m
                              where m.statement_line_id=l.id and m.status='ACTIVE'),0)-
                    coalesce((select sum(d.amount) from bank_reconciliation_difference d
                              where d.statement_line_id=l.id and d.status<>'REVERSED'),0),0) remaining_amount
                  from bank_statement_line l
                  join bank_reconciliation_session s on s.id=l.session_id
                  where l.status<>'VOID' and s.status in ('DRAFT','REOPENED')
                ) x where remaining_amount>=0.01
                """);
        DashboardDto.BankAttention bank = new DashboardDto.BankAttention(
                decimalValue(bankRow.get("unmatched_count")).longValue(), decimalValue(bankRow.get("unmatched_amount")),
                scalarLong("select count(*) from bank_reconciliation_session where status in ('DRAFT','REOPENED')"));

        Map<String, Object> invoiceRow = jdbcTemplate.queryForMap("""
                select count(*) unreconciled_count,coalesce(sum(total_amount),0) unreconciled_amount
                from (
                  select i.id,i.total_amount,i.tax_amount,
                    coalesce((select sum(r.total_amount) from tax_invoice_relation r
                              where r.invoice_id=i.id and r.relation_layer='GOODS' and r.status='ACTIVE'),0) goods_amount,
                    coalesce((select sum(r.total_amount) from tax_invoice_relation r
                              where r.invoice_id=i.id and r.relation_layer='SETTLEMENT' and r.status='ACTIVE'),0) settlement_amount,
                    coalesce((select sum(r.tax_amount) from tax_invoice_relation r
                              where r.invoice_id=i.id and r.relation_layer='ACCOUNTING' and r.status='ACTIVE'),0) accounting_tax
                  from tax_invoice i where i.status='CONFIRMED'
                ) x where goods_amount<total_amount or settlement_amount<total_amount
                   or (tax_amount<>0 and accounting_tax<tax_amount)
                """);
        DashboardDto.InvoiceAttention invoices = new DashboardDto.InvoiceAttention(
                decimalValue(invoiceRow.get("unreconciled_count")).longValue(), decimalValue(invoiceRow.get("unreconciled_amount")),
                scalarLong("select count(*) from tax_invoice where status='CONFIRMED' and verification_status<>'VERIFIED'"));

        return new DashboardDto.OwnerAttention(true, receivables, payables, inventory, bank, invoices,
                closeAttention());
    }

    private DashboardDto.SettlementAttention settlementAttention(boolean receivable) {
        String debtSql = receivable ? """
                select o.due_date,greatest(o.receivable_amount-o.received_amount-o.adjusted_amount,0) unpaid_amount
                from sales_order o
                where o.status not in ('DRAFT','CANCELLED') and o.receivable_amount>0
                union all
                select o.due_date,greatest(o.receivable_amount-o.paid_amount,0)
                from outbound_order o
                where o.sales_order_id=0 and o.status='SHIPPED' and o.receivable_amount>0
                """ : """
                select o.due_date,greatest(o.total_amount-coalesce(r.returned_amount,0)-o.paid_amount,0) unpaid_amount
                from purchase_order o
                left join (select purchase_order_id,sum(payable_reduction_amount) returned_amount from purchase_return_order
                           where status='CONFIRMED' group by purchase_order_id) r on r.purchase_order_id=o.id
                where o.status not in ('DRAFT','CANCELLED') and o.total_amount>0
                union all
                select o.due_date,greatest(o.payable_amount-coalesce(r.returned_amount,0)-o.paid_amount,0)
                from inbound_order o
                left join (select inbound_id,sum(payable_reduction_amount) returned_amount from purchase_return_order
                           where status='CONFIRMED' group by inbound_id) r on r.inbound_id=o.id
                where o.purchase_order_id=0 and o.status='STOCKED' and o.payable_amount>0
                """;
        return jdbcTemplate.queryForObject("""
                select
                  sum(case when due_date=current_date and unpaid_amount>0 then 1 else 0 end) due_today_count,
                  coalesce(sum(case when due_date=current_date then unpaid_amount else 0 end),0) due_today_amount,
                  sum(case when due_date<current_date and unpaid_amount>0 then 1 else 0 end) overdue_count,
                  coalesce(sum(case when due_date<current_date then unpaid_amount else 0 end),0) overdue_amount
                from (
                """ + debtSql + """
                ) due_debt
                """, (rs, rowNum) -> new DashboardDto.SettlementAttention(
                rs.getLong("due_today_count"), rs.getBigDecimal("due_today_amount"),
                rs.getLong("overdue_count"), rs.getBigDecimal("overdue_amount")));
    }

    private DashboardDto.CloseAttention closeAttention() {
        List<Long> openPeriodIds = jdbcTemplate.queryForList("""
                select id from accounting_period where status='OPEN'
                order by end_date asc limit 1
                """, Long.class);
        if (openPeriodIds.isEmpty()) {
            return new DashboardDto.CloseAttention(0L, "未初始化开放期间", null, "MISSING",
                    1, 1, "请先建立或开放会计期间");
        }
        FinancialCloseDtos.PeriodClosePreview preview = financialCloseService.closePreview(openPeriodIds.get(0));
        boolean periodStillRunning = preview.endDate().isAfter(LocalDate.now());
        List<FinancialCloseDtos.CloseCheck> blockers = preview.checks().stream()
                .filter(item -> "BLOCKED".equals(item.status()))
                .filter(item -> !periodStillRunning || !"PERIOD_END".equals(item.checkCode()))
                .toList();
        long issues = blockers.stream().mapToLong(item -> item.issueCount() == null ? 0 : item.issueCount()).sum();
        String summary = blockers.isEmpty()
                ? (periodStillRunning ? "期间进行中，当前无业务阻断" : "当前无关账阻断")
                : blockers.stream().limit(3).map(FinancialCloseDtos.CloseCheck::checkName)
                .reduce((left, right) -> left + "、" + right).orElse("");
        if (blockers.size() > 3) summary += "等 " + blockers.size() + " 项";
        return new DashboardDto.CloseAttention(preview.periodId(), preview.periodName(), preview.endDate(),
                preview.periodStatus(), blockers.size(), issues, summary);
    }

    private void requirePartner(Long id) {
        dataScopeService.assertMasterAllowed("PARTNER", id);
        Integer count = jdbcTemplate.queryForObject("select count(*) from partner where id = ?", Integer.class, id);
        if (count == null || count == 0) {
            throw new BusinessException("往来单位不存在");
        }
    }

    private void requirePartnerRole(Long id, String type) {
        dataScopeService.assertMasterAllowed("PARTNER", id);
        PartnerBusinessGuard.requireEnabledRole(jdbcTemplate, id, type);
    }

    private SettlementDueSnapshot settlementDueSnapshot(String partnerType, Long partnerId, LocalDate businessDate) {
        if (businessDate == null) throw new BusinessException("业务日期不能为空");
        String table = "CUSTOMER".equals(partnerType) ? "partner_customer_profile" : "partner_supplier_profile";
        Integer days = jdbcTemplate.queryForObject(
                "select coalesce(settlement_days,0) from " + table + " where partner_id=?", Integer.class, partnerId);
        int normalizedDays = Math.max(days == null ? 0 : days, 0);
        return new SettlementDueSnapshot(normalizedDays, businessDate.plusDays(normalizedDays));
    }

    private void requireProduct(Long id) {
        dataScopeService.assertMasterAllowed("PRODUCT", id);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select p.status product_status,
                       p.identity_status,
                       s.status series_status,
                       c.status category_status,
                       s.name series_name,
                       c.name category_name
                from product p
                left join product_series s on s.id = p.product_series_id
                left join product_category c on c.id = s.category_id
                where p.id = ?
                """, id);
        if (rows.isEmpty()) {
            throw new BusinessException("商品不存在");
        }
        Map<String, Object> row = rows.get(0);
        if (!"ENABLED".equals(String.valueOf(row.get("product_status")))) {
            throw new BusinessException("商品已停用，不能用于新增业务单据");
        }
        if (!"PUBLISHED".equals(String.valueOf(row.get("identity_status")))) {
            throw new BusinessException("商品必填规格尚未补齐，不能用于采购、销售、入库或出库");
        }
        if (!"ENABLED".equals(String.valueOf(row.get("series_status")))) {
            throw new BusinessException("产品系列“" + blank(Objects.toString(row.get("series_name"), "")) + "”已停用，不能用于新增业务单据");
        }
        if (!"ENABLED".equals(String.valueOf(row.get("category_status")))) {
            throw new BusinessException("产品分类“" + blank(Objects.toString(row.get("category_name"), "")) + "”已停用，不能用于新增业务单据");
        }
    }

    private void requireHistoricalProduct(
            Long productId, Long itemId, Map<Long, Long> historicalProductIds, String documentName) {
        requireProductAccessible(productId);
        Long historicalProductId = historicalProductIds.get(itemId);
        if (historicalProductId == null) {
            throw new BusinessException(documentName + "历史明细不存在或不属于当前来源单");
        }
        if (!Objects.equals(historicalProductId, productId)) {
            throw new BusinessException(documentName + "历史明细不能替换商品；请新增明细行");
        }
    }

    private void requireProductAccessible(Long id) {
        dataScopeService.assertMasterAllowed("PRODUCT", id);
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from product where id=?", Integer.class, id);
        if (count == null || count == 0) {
            throw new BusinessException("商品不存在");
        }
    }

    private void requireWarehouse(Long id) {
        dataScopeService.assertMasterAllowed("WAREHOUSE", id);
        Integer count = jdbcTemplate.queryForObject("select count(*) from warehouse where id = ?", Integer.class, id);
        if (count == null || count == 0) {
            throw new BusinessException("仓库不存在");
        }
    }

    private void requireContactForPartner(Long partnerId, Long contactId) {
        Integer count = jdbcTemplate.queryForObject("select count(*) from partner_contact where id = ? and partner_id = ?", Integer.class, contactId, partnerId);
        if (count == null || count == 0) {
            throw new BusinessException("联系人不属于当前往来单位");
        }
    }

    private void clearFirstChoiceContact(Long partnerId) {
        jdbcTemplate.update("update partner_contact set first_choice = 0 where partner_id = ?", partnerId);
    }

    private void clearFirstChoiceAddress(Long partnerId, String addressType) {
        jdbcTemplate.update("update partner_address set first_choice = 0 where partner_id = ? and address_type = ?", partnerId, addressType);
    }

    private void clearFirstChoiceBankAccount(Long partnerId) {
        jdbcTemplate.update("update partner_bank_account set first_choice = 0 where partner_id = ?", partnerId);
    }

    private void syncPartnerPrimaryContact(Long partnerId, String name, String phone) {
        jdbcTemplate.update("update partner set contact_person = ?, phone = ? where id = ?",
                blank(name), blank(phone), partnerId);
    }

    private void syncPartnerPrimaryContactFromContacts(Long partnerId) {
        List<AlignedDtos.ContactRow> rows = jdbcTemplate.query("""
                select * from partner_contact
                where partner_id = ? and first_choice = 1 and status = 'ENABLED'
                order by id asc limit 1
                """, contactMapper(), partnerId);
        if (rows.isEmpty()) {
            syncPartnerPrimaryContact(partnerId, "", "");
            return;
        }
        AlignedDtos.ContactRow primary = rows.get(0);
        syncPartnerPrimaryContact(partnerId, primary.name(), primary.phone());
    }

    private void syncPartnerPrimaryAddress(Long partnerId, String address) {
        jdbcTemplate.update("update partner set address = ? where id = ?", blank(address), partnerId);
    }

    private String addressSnapshot(Long partnerId, Long addressId) {
        if (addressId == null || addressId <= 0) {
            return "";
        }
        List<String> snapshots = jdbcTemplate.query("""
                select concat_ws(' ', nullif(detail, ''), nullif(contact_person, ''), nullif(phone, '')) snapshot
                from partner_address
                where id = ? and partner_id = ? and address_type = 'RECEIVING' and status = 'ENABLED'
                """, (rs, rowNum) -> rs.getString("snapshot"), addressId, partnerId);
        if (snapshots.isEmpty()) {
            throw new BusinessException("收货地址不存在、已停用、类型不正确或不属于当前客户");
        }
        return blank(snapshots.get(0));
    }

    private String outboundAddressSnapshot(Long salesOrderId, Long partnerId, Long addressId) {
        if (salesOrderId != null && salesOrderId > 0 && addressId != null && addressId > 0) {
            List<String> historicalSnapshots = jdbcTemplate.query("""
                    select address_snapshot from sales_order
                    where id=? and customer_id=? and address_id=?
                    """, (rs, rowNum) -> rs.getString(1), salesOrderId, partnerId, addressId);
            if (!historicalSnapshots.isEmpty()) return blank(historicalSnapshots.get(0));
        }
        return addressSnapshot(partnerId, addressId);
    }

    private Long requiredSalesOrderAddressId(Long addressId) {
        if (addressId == null || addressId <= 0) {
            throw new BusinessException("请选择收货地址");
        }
        return addressId;
    }

    private String requiredSalesOrderAddressSnapshot(Long partnerId, Long addressId) {
        List<String> snapshots = jdbcTemplate.query("""
                select concat_ws(' ', nullif(detail, ''), nullif(contact_person, ''), nullif(phone, '')) snapshot
                from partner_address
                where id = ? and partner_id = ? and address_type = 'RECEIVING' and status = 'ENABLED'
                """, (rs, rowNum) -> rs.getString("snapshot"), addressId, partnerId);
        if (snapshots.isEmpty()) {
            throw new BusinessException("收货地址不存在、已停用或不属于当前客户");
        }
        return blank(snapshots.get(0));
    }

    private Long requiredProductSeriesCategory(Long productSeriesId, boolean allowDisabled) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select s.category_id,
                       s.status series_status,
                       c.status category_status,
                       c.name category_name
                from product_series s
                left join product_category c on c.id = s.category_id
                where s.id = ?
                """, productSeriesId);
        if (rows.isEmpty()) {
            throw new BusinessException("产品系列不存在");
        }
        Map<String, Object> row = rows.get(0);
        if (!allowDisabled && !"ENABLED".equals(String.valueOf(row.get("series_status")))) {
            throw new BusinessException("产品系列已停用，不能用于新商品");
        }
        if (!allowDisabled && !"ENABLED".equals(String.valueOf(row.get("category_status")))) {
            throw new BusinessException("产品分类“" + blank(Objects.toString(row.get("category_name"), "")) + "”已停用，不能用于新商品");
        }
        return ((Number) row.get("category_id")).longValue();
    }

    private List<ProductDto> attachProductFieldValues(List<ProductDto> products) {
        if (products.isEmpty()) {
            return products;
        }
        List<Long> productIds = products.stream().map(ProductDto::id).toList();
        String placeholders = String.join(",", Collections.nCopies(productIds.size(), "?"));
        Map<Long, List<ProductDto.ProductFieldValueDto>> valuesByProduct = new HashMap<>();
        jdbcTemplate.query("""
                        select pfv.product_id, pfv.series_field_id,
                               coalesce(sf.field_id, pfv.field_id) field_id,
                               coalesce(sf.product_series_id, 0) product_series_id,
                               coalesce(nullif(sf.field_name, ''), pfv.field_name_snapshot) field_name,
                               coalesce(nullif(sf.display_name, ''), nullif(pfv.display_name_snapshot, ''), pfv.field_name_snapshot) display_name,
                               pfv.value_text, pfv.value_decimal,
                               coalesce(cf.field_type, 'TEXT') field_type,
                               coalesce(sf.unit_id, pfv.unit_id_snapshot, 0) unit_id,
                               coalesce(nullif(sf.unit_name, ''), pfv.unit_name_snapshot, '') unit_name,
                               coalesce(sf.sku_dimension, 0) sku_dimension,
                               coalesce(sf.include_in_spec, 0) include_in_spec,
                               coalesce(sf.searchable, 0) searchable,
                               coalesce(sf.order_visible, 0) order_visible,
                               coalesce(sf.status, 'REMOVED') series_field_status
                        from product_field_value pfv
                        left join product_series_field sf on sf.id = pfv.series_field_id
                        left join product_custom_field cf on cf.id = coalesce(sf.field_id, pfv.field_id)
                        where pfv.product_id in (%s)
                        order by pfv.product_id, coalesce(sf.sort_no, 999999), pfv.id
                        """.formatted(placeholders), rs -> {
                    Long productId = rs.getLong("product_id");
                    String fieldType = rs.getString("field_type");
                    Object value = "NUMBER".equals(fieldType) && rs.getBigDecimal("value_decimal") != null
                            ? rs.getBigDecimal("value_decimal").stripTrailingZeros()
                            : rs.getString("value_text");
                    String unitName = rs.getString("unit_name");
                    valuesByProduct.computeIfAbsent(productId, ignored -> new ArrayList<>()).add(
                            new ProductDto.ProductFieldValueDto(
                                    rs.getLong("series_field_id"),
                                    rs.getLong("field_id"),
                                    rs.getLong("product_series_id"),
                                    rs.getString("field_name"),
                                    rs.getString("display_name"),
                                    value,
                                    displayFieldValue(value, unitName),
                                    fieldType,
                                    rs.getLong("unit_id"),
                                    unitName,
                                    rs.getInt("sku_dimension") == 1,
                                    rs.getInt("include_in_spec") == 1,
                                    rs.getInt("searchable") == 1,
                                    rs.getInt("order_visible") == 1,
                                    rs.getString("series_field_status")
                            ));
                }, productIds.toArray());

        Map<Long, List<String>> missingByProduct = new HashMap<>();
        jdbcTemplate.query("""
                        select p.id product_id,
                               coalesce(nullif(sf.display_name, ''), sf.field_name) missing_field_name
                        from product p
                        join product_series_field sf
                          on sf.product_series_id = p.product_series_id
                         and sf.status = 'ENABLED'
                         and sf.required = 1
                        join product_custom_field cf on cf.id = sf.field_id
                        left join product_field_value pfv
                          on pfv.product_id = p.id
                         and pfv.series_field_id = sf.id
                        where p.id in (%s)
                          and (
                            (cf.field_type = 'NUMBER' and pfv.value_decimal is null)
                            or (cf.field_type <> 'NUMBER' and coalesce(trim(pfv.value_text), '') = '')
                          )
                        order by p.id, sf.sort_no, sf.id
                        """.formatted(placeholders), rs -> {
                    missingByProduct
                            .computeIfAbsent(rs.getLong("product_id"), ignored -> new ArrayList<>())
                            .add(rs.getString("missing_field_name"));
                }, productIds.toArray());

        return products.stream().map(product -> {
            List<String> missingFields = missingByProduct.getOrDefault(product.id(), List.of());
            List<ProductDto.ProductFieldValueDto> fieldValues = valuesByProduct.getOrDefault(product.id(), List.of());
            String orderSpecText = fieldValues.stream()
                    .filter(value -> Objects.equals(value.productSeriesId(), product.productSeriesId()))
                    .filter(value -> "ENABLED".equals(value.seriesFieldStatus()))
                    .filter(value -> Boolean.TRUE.equals(value.orderVisible()))
                    .map(value -> value.displayName() + value.displayValue())
                    .reduce((left, right) -> left + " / " + right)
                    .orElse("");
            return new ProductDto(
                    product.id(), product.categoryId(), product.categoryName(), product.productSeriesId(),
                    product.productSeriesName(), product.specPresetId(), product.specSignature(), product.sku(), product.name(),
                    product.grade(), product.temper(), product.standard(), product.spec(), orderSpecText, product.attributeJson(),
                    product.barcode(), product.originalNumber(), product.unit(), product.defaultPrice(), product.status(),
                    product.identitySchemaVersionSnapshot(), product.identityStatus(), product.identitySignature(),
                    fieldValues, missingFields.isEmpty(), missingFields
            );
        }).toList();
    }

    private String displayFieldValue(Object value, String unitName) {
        if (value == null) {
            return "";
        }
        String text = value instanceof BigDecimal number
                ? number.stripTrailingZeros().toPlainString()
                : String.valueOf(value);
        return text + (StringUtils.hasText(unitName) ? unitName : "");
    }

    private ExistingProductMaster existingProductMaster(Long productId) {
        List<ExistingProductMaster> rows = jdbcTemplate.query("""
                select id, product_series_id, attribute_json,sku,unit,
                       identity_status,identity_signature
                from product where id = ? for update
                """, (rs, rowNum) -> new ExistingProductMaster(
                rs.getLong("id"), rs.getLong("product_series_id"), rs.getString("attribute_json"),
                rs.getString("sku"), rs.getString("unit"), rs.getString("identity_status"),
                rs.getString("identity_signature")
        ), productId);
        if (rows.isEmpty()) {
            throw new BusinessException("商品不存在");
        }
        return rows.get(0);
    }

    private List<ProductFieldChange> normalizeProductFieldChanges(
            SimpleCreateRequests.ProductCreate req, Long existingProductId) {
        Long productSeriesId = req.productSeriesId();
        List<ProductSeriesFieldDefinition> definitions = productSeriesFieldDefinitions(productSeriesId);
        Map<Long, ProductSeriesFieldDefinition> definitionById = new HashMap<>();
        for (ProductSeriesFieldDefinition definition : definitions) {
            definitionById.put(definition.seriesFieldId(), definition);
        }

        List<SimpleCreateRequests.ProductFieldValueCreate> requestedValues = req.fieldValues();
        if (requestedValues == null) {
            requestedValues = legacyProductFieldValues(req.attributeJson(), definitions);
        }

        Set<Long> usedFieldIds = new HashSet<>();
        List<ProductFieldChange> changes = new ArrayList<>();
        for (SimpleCreateRequests.ProductFieldValueCreate requested : requestedValues) {
            ProductSeriesFieldDefinition definition = definitionById.get(requested.seriesFieldId());
            if (definition == null) {
                throw new BusinessException("商品规格值包含不属于当前产品系列的字段");
            }
            if (!"ENABLED".equals(definition.status())) {
                throw new BusinessException("商品规格字段“" + definition.displayName() + "”已停用，不能修改历史值");
            }
            if (!usedFieldIds.add(definition.seriesFieldId())) {
                throw new BusinessException("商品规格字段不能重复");
            }
            String rawValue = requested.value() == null ? "" : String.valueOf(requested.value()).trim();
            String referenceType = StringUtils.hasText(definition.identityReferenceType())
                    ? definition.identityReferenceType()
                    : productReferenceType(definition.fieldName(), definition.displayName());
            if (StringUtils.hasText(referenceType) && StringUtils.hasText(rawValue)) {
                ProductMasterDataDtos.ReferenceResolution resolution =
                        productMasterDataService.resolveReferenceForProductWrite(
                                referenceType, rawValue, true);
                String normalized = Boolean.TRUE.equals(resolution.resolved())
                        ? resolution.normalizedCode() : rawValue;
                changes.add(new ProductFieldChange(
                        definition, normalized, null, rawValue,
                        resolution.referenceValueId() == null ? 0L : resolution.referenceValueId(),
                        Boolean.TRUE.equals(resolution.resolved()) ? resolution.normalizedCode() : ""
                ));
                continue;
            }
            NormalizedFieldValue normalizedValue = StringUtils.hasText(rawValue)
                    ? normalizeProductFieldValue(definition, rawValue, existingProductId)
                    : new NormalizedFieldValue(null, null);
            changes.add(new ProductFieldChange(
                    definition, normalizedValue.valueText(), normalizedValue.valueDecimal(),
                    rawValue, 0L, ""
            ));
        }
        return changes;
    }

    private List<ProductSeriesFieldDefinition> productSeriesFieldDefinitions(Long productSeriesId) {
        return jdbcTemplate.query("""
                select sf.id series_field_id, sf.field_id, sf.product_series_id, sf.field_name,
                       coalesce(nullif(sf.display_name, ''), sf.field_name) display_name,
                       cf.field_type, cf.value_type,
                       coalesce(nullif(sf.option_json, ''), cf.option_json) option_json,
                       coalesce((
                         select ir.reference_type
                         from product_identity_rule ir
                         join product_series identity_series on identity_series.id=ir.product_series_id
                         where ir.product_series_id=sf.product_series_id
                           and ir.schema_version=identity_series.identity_schema_version
                           and ir.series_field_id=sf.id
                           and ir.status='ENABLED'
                         order by ir.id desc limit 1
                       ),'') identity_reference_type,
                       coalesce(sf.unit_id, 0) unit_id, sf.unit_name,
                       sf.required, sf.sku_dimension, sf.include_in_spec, sf.searchable, sf.order_visible,
                       sf.min_value, sf.max_value, sf.decimal_scale, sf.status
                from product_series_field sf
                join product_custom_field cf on cf.id = sf.field_id
                where sf.product_series_id = ?
                order by sf.sort_no, sf.id
                """, (rs, rowNum) -> new ProductSeriesFieldDefinition(
                rs.getLong("series_field_id"), rs.getLong("field_id"), rs.getLong("product_series_id"),
                rs.getString("field_name"), rs.getString("display_name"), rs.getString("field_type"),
                rs.getString("value_type"), rs.getString("option_json"),
                rs.getString("identity_reference_type"), rs.getLong("unit_id"),
                rs.getString("unit_name"), rs.getInt("required") == 1, rs.getInt("sku_dimension") == 1,
                rs.getInt("include_in_spec") == 1, rs.getInt("searchable") == 1,
                rs.getInt("order_visible") == 1, rs.getBigDecimal("min_value"), rs.getBigDecimal("max_value"),
                rs.getInt("decimal_scale"), rs.getString("status")
        ), productSeriesId);
    }

    private List<SimpleCreateRequests.ProductFieldValueCreate> legacyProductFieldValues(
            String attributeJson, List<ProductSeriesFieldDefinition> definitions) {
        Map<String, Object> attributes = parseAttributeMap(attributeJson);
        List<SimpleCreateRequests.ProductFieldValueCreate> values = new ArrayList<>();
        for (ProductSeriesFieldDefinition definition : definitions) {
            if (!"ENABLED".equals(definition.status())) {
                continue;
            }
            Object value = attributes.get(definition.fieldName());
            if (value != null && StringUtils.hasText(String.valueOf(value))) {
                values.add(new SimpleCreateRequests.ProductFieldValueCreate(definition.seriesFieldId(), value));
            }
        }
        return values;
    }

    private NormalizedFieldValue normalizeProductFieldValue(
            ProductSeriesFieldDefinition definition, String rawValue, Long existingProductId) {
        PresetSeriesField compatibleDefinition = new PresetSeriesField(
                definition.seriesFieldId(), definition.fieldId(), definition.fieldName(),
                definition.fieldType(), definition.valueType(), definition.optionJson(), definition.unitName(),
                definition.minValue(), definition.maxValue(), definition.decimalScale(), definition.status()
        );
        if ("SELECT".equals(definition.fieldType())
                && existingProductValue(existingProductId, definition.seriesFieldId(), rawValue)) {
            return new NormalizedFieldValue(rawValue, null);
        }
        return normalizePresetValue(compatibleDefinition, rawValue, null);
    }

    private boolean existingProductValue(Long productId, Long seriesFieldId, String value) {
        if (productId == null) {
            return false;
        }
        Integer count = jdbcTemplate.queryForObject("""
                select count(*) from product_field_value
                where product_id = ? and series_field_id = ? and value_text = ?
                """, Integer.class, productId, seriesFieldId, value);
        return count != null && count > 0;
    }

    private void validateProductRequiredFields(
            Long productSeriesId, ExistingProductMaster existingProduct, List<ProductFieldChange> changes) {
        Map<Long, ProductFieldChange> changeByField = new HashMap<>();
        for (ProductFieldChange change : changes) {
            changeByField.put(change.definition().seriesFieldId(), change);
        }
        boolean requireComplete = existingProduct == null
                || !Objects.equals(existingProduct.productSeriesId(), productSeriesId);
        for (ProductSeriesFieldDefinition definition : productSeriesFieldDefinitions(productSeriesId)) {
            if (!definition.required() || !"ENABLED".equals(definition.status())) {
                continue;
            }
            ProductFieldChange change = changeByField.get(definition.seriesFieldId());
            if (requireComplete) {
                if (change == null || !StringUtils.hasText(change.valueText())) {
                    throw new BusinessException("请填写商品必填字段“" + definition.displayName() + "”");
                }
                continue;
            }
            if (change != null && !StringUtils.hasText(change.valueText())) {
                Integer existingCount = jdbcTemplate.queryForObject("""
                        select count(*) from product_field_value
                        where product_id = ? and series_field_id = ? and trim(value_text) <> ''
                        """, Integer.class, existingProduct == null ? 0L : existingProduct.id(), definition.seriesFieldId());
                if (existingCount != null && existingCount > 0) {
                    throw new BusinessException("商品必填字段“" + definition.displayName() + "”已有值，不能清空");
                }
            }
        }
    }

    private String mergeProductAttributeJson(String baseAttributeJson, List<ProductFieldChange> changes) {
        Map<String, Object> attributes = parseAttributeMap(baseAttributeJson);
        for (ProductFieldChange change : changes) {
            if (!StringUtils.hasText(change.valueText())) {
                attributes.remove(change.definition().fieldName());
            } else {
                attributes.put(change.definition().fieldName(), jsonFieldValue(
                        change.definition().fieldType(), change.valueText()));
            }
        }
        return writeAttributeJson(attributes, "商品规格属性过长，请缩短后保存");
    }

    private void mergeProductFieldValues(Long productId, List<ProductFieldChange> changes) {
        for (ProductFieldChange change : changes) {
            ProductSeriesFieldDefinition definition = change.definition();
            if (!StringUtils.hasText(change.valueText())) {
                jdbcTemplate.update("""
                        delete from product_field_value
                        where product_id = ? and series_field_id = ?
                        """, productId, definition.seriesFieldId());
                continue;
            }
            jdbcTemplate.update("""
                    insert into product_field_value
                      (product_id, series_field_id, field_id, field_name_snapshot, display_name_snapshot,
                       value_text, value_decimal, unit_id_snapshot, unit_name_snapshot,
                       source_value_text,reference_value_id,normalized_code)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?,?,?,?)
                    on duplicate key update
                      field_id = values(field_id),
                      field_name_snapshot = values(field_name_snapshot),
                      display_name_snapshot = values(display_name_snapshot),
                      value_text = values(value_text),
                      value_decimal = values(value_decimal),
                      unit_id_snapshot = values(unit_id_snapshot),
                      unit_name_snapshot = values(unit_name_snapshot),
                      source_value_text = values(source_value_text),
                      reference_value_id = values(reference_value_id),
                      normalized_code = values(normalized_code)
                    """, productId, definition.seriesFieldId(), definition.fieldId(), definition.fieldName(),
                    definition.displayName(), change.valueText(), change.valueDecimal(), definition.unitId(),
                    definition.unitName(), change.sourceValueText(), change.referenceValueId(),
                    change.normalizedCode());
        }
    }

    private String productReferenceType(String fieldName, String displayName) {
        for (String value : List.of(blank(fieldName), blank(displayName))) {
            String normalized = value.replace(" ", "");
            if (Set.of("牌号", "材质牌号", "牌号/材质", "磁材牌号").contains(normalized)) return "GRADE";
            if (Set.of("材料状态", "状态").contains(normalized)) return "TEMPER";
            if (Set.of("执行标准", "标准").contains(normalized)) return "STANDARD";
        }
        return "";
    }

    private String normalizeLegacyProductReference(
            String referenceType, String sourceValue, boolean allowUnresolved) {
        if (sourceValue == null) return null;
        if (!StringUtils.hasText(sourceValue)) return "";
        ProductMasterDataDtos.ReferenceResolution resolution =
                productMasterDataService.resolveReferenceForProductWrite(
                        referenceType, sourceValue, allowUnresolved);
        return Boolean.TRUE.equals(resolution.resolved())
                ? resolution.normalizedCode() : sourceValue.trim();
    }

    private boolean pendingIdentity(String value) {
        return "PENDING_REVIEW".equalsIgnoreCase(value);
    }

    private Map<String, Object> parseAttributeMap(String attributeJson) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        if (!StringUtils.hasText(attributeJson)) {
            return attributes;
        }
        try {
            JsonNode root = objectMapper.readTree(attributeJson);
            if (root == null || !root.isObject()) {
                throw new BusinessException("规格属性格式不正确");
            }
            root.fields().forEachRemaining(entry -> {
                JsonNode value = entry.getValue();
                if (value == null || value.isNull()) {
                    return;
                }
                if (value.isNumber()) {
                    attributes.put(entry.getKey(), value.decimalValue());
                } else if (value.isValueNode()) {
                    attributes.put(entry.getKey(), value.asText());
                } else {
                    attributes.put(entry.getKey(), value.toString());
                }
            });
            return attributes;
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException("规格属性格式不正确");
        }
    }

    private Object jsonFieldValue(String fieldType, String valueText) {
        return "NUMBER".equals(fieldType) ? new BigDecimal(valueText) : valueText;
    }

    private String writeAttributeJson(Map<String, Object> attributes, String tooLongMessage) {
        try {
            String json = objectMapper.writeValueAsString(attributes);
            if (json.length() > 2000) {
                throw new BusinessException(tooLongMessage);
            }
            return json;
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException("规格属性无法保存");
        }
    }

    private record ExistingProductMaster(
            Long id,
            Long productSeriesId,
            String attributeJson,
            String sku,
            String unit,
            String identityStatus,
            String identitySignature
    ) {
    }

    private record ProductSeriesFieldDefinition(
            Long seriesFieldId,
            Long fieldId,
            Long productSeriesId,
            String fieldName,
            String displayName,
            String fieldType,
            String valueType,
            String optionJson,
            String identityReferenceType,
            Long unitId,
            String unitName,
            boolean required,
            boolean skuDimension,
            boolean includeInSpec,
            boolean searchable,
            boolean orderVisible,
            BigDecimal minValue,
            BigDecimal maxValue,
            int decimalScale,
            String status
    ) {
    }

    private record ProductFieldChange(
            ProductSeriesFieldDefinition definition,
            String valueText,
            BigDecimal valueDecimal,
            String sourceValueText,
            Long referenceValueId,
            String normalizedCode
    ) {
    }

    private record ProductDocumentSnapshot(String sku, String name, String spec) {
    }

    private record NormalizedFieldValue(
            String valueText,
            BigDecimal valueDecimal
    ) {
    }

    private MasterDataDtos.CustomFieldRow customField(Long fieldId) {
        List<MasterDataDtos.CustomFieldRow> rows = jdbcTemplate.query("""
                select cf.*,
                       (select count(distinct sf.product_series_id)
                        from product_series_field sf
                        where sf.field_id = cf.id) series_count,
                       (select count(distinct pfv.product_id)
                        from product_field_value pfv
                        join product_series_field sf on sf.id = pfv.series_field_id
                        where sf.field_id = cf.id) product_count,
                       (select count(distinct pv.preset_id)
                        from product_spec_preset_value pv
                        join product_series_field sf on sf.id = pv.series_field_id
                        where sf.field_id = cf.id) preset_count
                from product_custom_field cf
                where cf.id = ?
                """, customFieldMapper(), fieldId);
        if (rows.isEmpty()) {
            throw new BusinessException("规格字段不存在");
        }
        return rows.get(0);
    }

    private MasterDataDtos.SeriesFieldRow seriesField(Long id) {
        List<MasterDataDtos.SeriesFieldRow> rows = jdbcTemplate.query("""
                select sf.id, sf.product_series_id, sf.field_id, sf.field_name, sf.display_name,
                       sf.unit_category_id, sf.unit_id, sf.unit_name, sf.required,
                       sf.sku_dimension, sf.include_in_spec, sf.searchable, sf.order_visible,
                       sf.min_value, sf.max_value, sf.decimal_scale, sf.sort_no, sf.status,
                       cf.field_type, cf.value_type,
                       coalesce(nullif(sf.option_json, ''), cf.option_json) option_json,
                       (select count(distinct pfv.product_id)
                        from product_field_value pfv where pfv.series_field_id = sf.id) product_count,
                       (select count(distinct pv.preset_id)
                        from product_spec_preset_value pv where pv.series_field_id = sf.id) preset_count
                from product_series_field sf
                join product_custom_field cf on cf.id = sf.field_id
                where sf.id = ?
                """, seriesFieldMapper(), id);
        if (rows.isEmpty()) {
            throw new BusinessException("系列字段不存在");
        }
        return rows.get(0);
    }

    private String seriesFieldOptions(MasterDataDtos.CustomFieldRow field, String optionJson) {
        String configuredOptions = StringUtils.hasText(optionJson) ? optionJson : field.optionJson();
        return customFieldOptions(field.fieldType(), configuredOptions);
    }

    private SeriesFieldConfig normalizeSeriesFieldConfig(
            MasterDataDtos.CustomFieldRow field, MasterDataDtos.SeriesFieldCreate req) {
        boolean skuDimension = Boolean.TRUE.equals(req.skuDimension());
        if (skuDimension && !Boolean.TRUE.equals(req.required())) {
            throw new BusinessException("参与SKU识别的字段必须设置为商品建档必填");
        }
        boolean includeInSpec = skuDimension || req.includeInSpec() == null || req.includeInSpec();
        boolean searchable = skuDimension || Boolean.TRUE.equals(req.searchable());
        boolean orderVisible = skuDimension || Boolean.TRUE.equals(req.orderVisible());

        Long unitCategoryId = positiveId(req.unitCategoryId());
        if (unitCategoryId == null) {
            unitCategoryId = positiveId(field.unitCategoryId());
        }
        Long unitId = positiveId(req.unitId());
        String unitName = "";
        if (unitCategoryId != null) {
            if (unitId == null) {
                throw new BusinessException("字段已配置计量分类，请选择具体计量单位");
            }
            List<Map<String, Object>> units = jdbcTemplate.queryForList("""
                    select unit_category_id, name, abbreviation, status
                    from unit where id = ?
                    """, unitId);
            if (units.isEmpty()) {
                throw new BusinessException("计量单位不存在");
            }
            Map<String, Object> unit = units.get(0);
            if (!Objects.equals(((Number) unit.get("unit_category_id")).longValue(), unitCategoryId)) {
                throw new BusinessException("计量单位不属于字段的计量分类");
            }
            if (!"ENABLED".equals(String.valueOf(unit.get("status")))) {
                throw new BusinessException("计量单位已停用");
            }
            unitName = StringUtils.hasText(String.valueOf(unit.get("abbreviation")))
                    ? String.valueOf(unit.get("abbreviation"))
                    : String.valueOf(unit.get("name"));
        } else if (unitId != null) {
            throw new BusinessException("请先选择计量分类，再选择计量单位");
        }

        BigDecimal minValue = null;
        BigDecimal maxValue = null;
        int decimalScale = 0;
        if ("NUMBER".equals(field.fieldType())) {
            decimalScale = "INTEGER".equals(field.valueType())
                    ? 0
                    : req.decimalScale() == null ? 3 : req.decimalScale();
            if (decimalScale < 0 || decimalScale > 10) {
                throw new BusinessException("数字字段小数位数必须在0到10之间");
            }
            minValue = req.minValue();
            maxValue = req.maxValue();
            if (minValue != null && maxValue != null && minValue.compareTo(maxValue) > 0) {
                throw new BusinessException("数字字段最小值不能大于最大值");
            }
        }
        return new SeriesFieldConfig(
                unitCategoryId, unitId, unitName, skuDimension, includeInSpec, searchable, orderVisible,
                minValue, maxValue, decimalScale
        );
    }

    private Long positiveId(Long value) {
        return value != null && value > 0 ? value : null;
    }

    private void validateExistingNumericValues(
            Long seriesFieldId, MasterDataDtos.CustomFieldRow field, SeriesFieldConfig config) {
        if (!"NUMBER".equals(field.fieldType())) {
            return;
        }
        List<BigDecimal> values = jdbcTemplate.query("""
                select value_decimal from product_field_value
                where series_field_id = ? and value_decimal is not null
                union all
                select value_decimal from product_spec_preset_value
                where series_field_id = ? and value_decimal is not null
                """, (rs, rowNum) -> rs.getBigDecimal("value_decimal"), seriesFieldId, seriesFieldId);
        for (BigDecimal value : values) {
            int actualScale = Math.max(value.stripTrailingZeros().scale(), 0);
            if (actualScale > config.decimalScale()) {
                throw new BusinessException("已有商品或预设值的小数位超过新设置，不能缩小小数位数");
            }
            if (config.minValue() != null && value.compareTo(config.minValue()) < 0) {
                throw new BusinessException("已有商品或预设值小于新最小值，不能保存当前限制");
            }
            if (config.maxValue() != null && value.compareTo(config.maxValue()) > 0) {
                throw new BusinessException("已有商品或预设值大于新最大值，不能保存当前限制");
            }
        }
    }

    private void refreshProductSpecSignature(Long productId, Long productSeriesId) {
        String signature = productSpecSignature(productId, productSeriesId);
        if (signature != null) {
            List<String> duplicates = jdbcTemplate.query("""
                    select id,name from product
                    where product_series_id = ? and spec_signature = ? and id <> ?
                    limit 1
                    """, (rs, rowNum) -> rs.getString("name") + "（商品 #" + rs.getLong("id") + "）",
                    productSeriesId, signature, productId);
            if (!duplicates.isEmpty()) {
                throw new BusinessException("相同产品系列和区分规格的商品已存在：" + duplicates.get(0));
            }
        }
        jdbcTemplate.update("update product set spec_signature = ? where id = ?", signature, productId);
    }

    private void refreshProductDisplaySpec(Long productId) {
        List<String> parts = jdbcTemplate.query("""
                select coalesce(nullif(sf.display_name,''),sf.field_name) display_name,
                       cf.field_type,pfv.value_text,pfv.value_decimal,coalesce(sf.unit_name,'') unit_name
                from product_field_value pfv
                join product_series_field sf on sf.id=pfv.series_field_id
                join product_custom_field cf on cf.id=sf.field_id
                where pfv.product_id=? and sf.status='ENABLED' and sf.include_in_spec=1
                  and (coalesce(pfv.value_text,'')<>'' or pfv.value_decimal is not null)
                order by sf.sort_no,sf.id
                """, (rs, rowNum) -> {
            BigDecimal decimal = rs.getBigDecimal("value_decimal");
            String value = "NUMBER".equals(rs.getString("field_type")) && decimal != null
                    ? decimal.stripTrailingZeros().toPlainString()
                    : blank(rs.getString("value_text"));
            return rs.getString("display_name") + value + rs.getString("unit_name");
        }, productId);
        jdbcTemplate.update("update product set spec=? where id=?", String.join(" / ", parts), productId);
    }

    private ProductDocumentSnapshot currentProductSnapshot(Long productId) {
        List<ProductDocumentSnapshot> rows = jdbcTemplate.query("""
                select sku,name,spec from product where id=?
                """, (rs, rowNum) -> new ProductDocumentSnapshot(
                rs.getString("sku"), rs.getString("name"), rs.getString("spec")
        ), productId);
        if (rows.isEmpty()) throw new BusinessException("商品不存在");
        return rows.get(0);
    }

    private ProductDocumentSnapshot purchaseItemProductSnapshot(long purchaseOrderItemId, Long productId) {
        if (purchaseOrderItemId <= 0) return currentProductSnapshot(productId);
        List<ProductDocumentSnapshot> rows = jdbcTemplate.query("""
                select coalesce(nullif(i.product_sku_snapshot,''),p.sku) product_sku_snapshot,
                       coalesce(nullif(i.product_name_snapshot,''),p.name) product_name_snapshot,
                       coalesce(nullif(i.product_spec_snapshot,''),p.spec) product_spec_snapshot
                from purchase_order_item i join product p on p.id=i.product_id
                where i.id=? and i.product_id=?
                """, (rs, rowNum) -> new ProductDocumentSnapshot(
                rs.getString("product_sku_snapshot"), rs.getString("product_name_snapshot"),
                rs.getString("product_spec_snapshot")
        ), purchaseOrderItemId, productId);
        return rows.isEmpty() ? currentProductSnapshot(productId) : rows.get(0);
    }

    private ProductDocumentSnapshot salesItemProductSnapshot(long salesOrderItemId, Long productId) {
        if (salesOrderItemId <= 0) return currentProductSnapshot(productId);
        List<ProductDocumentSnapshot> rows = jdbcTemplate.query("""
                select coalesce(nullif(i.product_sku_snapshot,''),p.sku) product_sku_snapshot,
                       coalesce(nullif(i.product_name_snapshot,''),p.name) product_name_snapshot,
                       coalesce(nullif(i.product_spec_snapshot,''),p.spec) product_spec_snapshot
                from sales_order_item i join product p on p.id=i.product_id
                where i.id=? and i.product_id=?
                """, (rs, rowNum) -> new ProductDocumentSnapshot(
                rs.getString("product_sku_snapshot"), rs.getString("product_name_snapshot"),
                rs.getString("product_spec_snapshot")
        ), salesOrderItemId, productId);
        return rows.isEmpty() ? currentProductSnapshot(productId) : rows.get(0);
    }

    private void refreshSeriesProductSpecSignatures(Long productSeriesId) {
        List<Long> productIds = jdbcTemplate.query("""
                select id from product where product_series_id = ? order by id
                """, (rs, rowNum) -> rs.getLong("id"), productSeriesId);
        if (productIds.isEmpty()) {
            return;
        }
        jdbcTemplate.update("update product set spec_signature = null where product_series_id = ?", productSeriesId);
        Map<String, Long> signatureOwners = new HashMap<>();
        for (Long productId : productIds) {
            String signature = productSpecSignature(productId, productSeriesId);
            if (signature == null) {
                continue;
            }
            Long existingProductId = signatureOwners.putIfAbsent(signature, productId);
            if (existingProductId != null) {
                throw new BusinessException("当前用于区分商品的规格会使已有商品重复，请先补充或调整区分规格");
            }
            jdbcTemplate.update("update product set spec_signature = ? where id = ?", signature, productId);
        }
    }

    private String productSpecSignature(Long productId, Long productSeriesId) {
        List<SpecSignatureValue> values = jdbcTemplate.query("""
                select sf.id series_field_id, cf.field_type, pfv.value_text, pfv.value_decimal
                from product_series_field sf
                join product_custom_field cf on cf.id = sf.field_id
                left join product_field_value pfv
                  on pfv.series_field_id = sf.id and pfv.product_id = ?
                where sf.product_series_id = ?
                  and sf.status = 'ENABLED'
                  and sf.sku_dimension = 1
                order by sf.sort_no, sf.id
                """, (rs, rowNum) -> new SpecSignatureValue(
                rs.getLong("series_field_id"), rs.getString("field_type"),
                rs.getString("value_text"), rs.getBigDecimal("value_decimal")
        ), productId, productSeriesId);
        if (values.isEmpty()) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        for (SpecSignatureValue value : values) {
            String canonicalValue;
            if ("NUMBER".equals(value.fieldType())) {
                if (value.valueDecimal() == null) {
                    return null;
                }
                canonicalValue = value.valueDecimal().setScale(10).toPlainString();
            } else {
                canonicalValue = value.valueText() == null ? "" : value.valueText().trim();
                if (!StringUtils.hasText(canonicalValue)) {
                    return null;
                }
            }
            parts.add(value.seriesFieldId() + "=" + canonicalValue);
        }
        return sha256(String.join("|", parts));
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("无法生成商品规格签名", ex);
        }
    }

    /**
     * MySQL stores JSON semantically and may reorder object keys or normalize
     * numbers. Hash only this recursive canonical form so a snapshot verifies
     * identically before and after a database round trip.
     */
    private String canonicalJson(String value) {
        try {
            return canonicalJson(objectMapper.readTree(value));
        } catch (Exception ex) {
            throw new BusinessException("业务快照JSON无法规范化");
        }
    }

    private String canonicalJson(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(canonicalJsonNode(value));
        } catch (Exception ex) {
            throw new BusinessException("业务快照JSON无法规范化");
        }
    }

    private JsonNode canonicalJsonNode(JsonNode value) {
        if (value == null || value.isNull()) return objectMapper.nullNode();
        if (value.isObject()) {
            ObjectNode result = objectMapper.createObjectNode();
            List<String> names = new ArrayList<>();
            value.fieldNames().forEachRemaining(names::add);
            names.stream().sorted().forEach(
                    name -> result.set(name, canonicalJsonNode(value.get(name))));
            return result;
        }
        if (value.isArray()) {
            ArrayNode result = objectMapper.createArrayNode();
            value.forEach(item -> result.add(canonicalJsonNode(item)));
            return result;
        }
        if (value.isNumber()) {
            return DecimalNode.valueOf(value.decimalValue().stripTrailingZeros());
        }
        return value.deepCopy();
    }

    private record SeriesFieldConfig(
            Long unitCategoryId,
            Long unitId,
            String unitName,
            boolean skuDimension,
            boolean includeInSpec,
            boolean searchable,
            boolean orderVisible,
            BigDecimal minValue,
            BigDecimal maxValue,
            int decimalScale
    ) {
    }

    private record SpecSignatureValue(
            Long seriesFieldId,
            String fieldType,
            String valueText,
            BigDecimal valueDecimal
    ) {
    }

    private void ensureSeriesFieldAvailable(Long seriesId, Long fieldId, Long excludeId) {
        String sql = "select count(*) from product_series_field where product_series_id = ? and field_id = ?";
        List<Object> args = new ArrayList<>();
        args.add(seriesId);
        args.add(fieldId);
        if (excludeId != null) {
            sql += " and id <> ?";
            args.add(excludeId);
        }
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, args.toArray());
        if (count != null && count > 0) {
            throw new BusinessException("当前产品系列已经配置过该规格字段");
        }
    }

    private boolean seriesFieldUsedByPreset(Long seriesFieldId) {
        Integer count = jdbcTemplate.queryForObject("""
                select
                  (select count(*) from product_spec_preset_value where series_field_id = ?)
                  + (select count(*) from product_field_value where series_field_id = ?)
                """, Integer.class, seriesFieldId, seriesFieldId);
        return count != null && count > 0;
    }

    private List<MasterDataDtos.SpecPresetRow> attachSpecPresetValues(List<MasterDataDtos.SpecPresetRow> presets) {
        if (presets.isEmpty()) {
            return presets;
        }
        List<Long> presetIds = presets.stream().map(MasterDataDtos.SpecPresetRow::id).toList();
        String placeholders = String.join(",", Collections.nCopies(presetIds.size(), "?"));
        Map<Long, List<MasterDataDtos.SpecPresetValueRow>> valuesByPreset = new HashMap<>();
        jdbcTemplate.query("""
                        select pv.preset_id, pv.series_field_id, sf.field_id, sf.field_name,
                               cf.field_type, pv.value_text, pv.value_decimal
                        from product_spec_preset_value pv
                        join product_series_field sf on sf.id = pv.series_field_id
                        join product_custom_field cf on cf.id = sf.field_id
                        where pv.preset_id in (%s)
                        order by pv.preset_id, sf.sort_no, sf.id
                        """.formatted(placeholders), rs -> {
                    Long presetId = rs.getLong("preset_id");
                    Object value = "NUMBER".equals(rs.getString("field_type"))
                            && rs.getBigDecimal("value_decimal") != null
                            ? rs.getBigDecimal("value_decimal").stripTrailingZeros()
                            : rs.getString("value_text");
                    valuesByPreset.computeIfAbsent(presetId, ignored -> new ArrayList<>()).add(
                            new MasterDataDtos.SpecPresetValueRow(
                                    rs.getLong("series_field_id"),
                                    rs.getLong("field_id"),
                                    rs.getString("field_name"),
                                    value
                            ));
                }, presetIds.toArray());
        return presets.stream().map(preset -> new MasterDataDtos.SpecPresetRow(
                preset.id(), preset.productSeriesId(), preset.productSeriesName(), preset.name(), preset.model(),
                preset.attributeJson(), valuesByPreset.getOrDefault(preset.id(), List.of()), preset.specText(),
                preset.skuSuffix(), preset.sortNo(), preset.status()
        )).toList();
    }

    private List<NormalizedPresetValue> normalizeSpecPresetValues(
            MasterDataDtos.SpecPresetCreate req, Long existingPresetId) {
        List<PresetSeriesField> seriesFieldRows = jdbcTemplate.query("""
                select sf.id series_field_id, sf.field_id, sf.field_name,
                       cf.field_type, cf.value_type,
                       coalesce(nullif(sf.option_json, ''), cf.option_json) option_json,
                       sf.unit_name, sf.min_value, sf.max_value, sf.decimal_scale,
                       sf.status
                from product_series_field sf
                join product_custom_field cf on cf.id = sf.field_id
                where sf.product_series_id = ?
                order by sf.sort_no, sf.id
                """, (rs, rowNum) -> new PresetSeriesField(
                rs.getLong("series_field_id"), rs.getLong("field_id"), rs.getString("field_name"),
                rs.getString("field_type"), rs.getString("value_type"), rs.getString("option_json"),
                rs.getString("unit_name"), rs.getBigDecimal("min_value"), rs.getBigDecimal("max_value"),
                rs.getInt("decimal_scale"), rs.getString("status")
        ), req.productSeriesId());
        Map<Long, PresetSeriesField> fieldById = new HashMap<>();
        for (PresetSeriesField field : seriesFieldRows) {
            fieldById.put(field.seriesFieldId(), field);
        }

        List<MasterDataDtos.SpecPresetValueCreate> requestedValues = req.fieldValues();
        if (requestedValues == null) {
            requestedValues = legacyPresetValues(req.attributeJson(), seriesFieldRows);
        }

        Set<Long> usedFieldIds = new HashSet<>();
        List<NormalizedPresetValue> normalizedValues = new ArrayList<>();
        for (MasterDataDtos.SpecPresetValueCreate requested : requestedValues) {
            PresetSeriesField field = fieldById.get(requested.seriesFieldId());
            if (field == null) {
                throw new BusinessException("规格预设包含不属于当前产品系列的字段");
            }
            if (!"ENABLED".equals(field.status())) {
                throw new BusinessException("规格预设字段“" + field.fieldName() + "”已停用，不能修改历史值");
            }
            if (!usedFieldIds.add(field.seriesFieldId())) {
                throw new BusinessException("规格预设中的字段不能重复");
            }
            String rawValue = requested.value() == null ? "" : String.valueOf(requested.value()).trim();
            NormalizedFieldValue normalizedValue = StringUtils.hasText(rawValue)
                    ? normalizePresetValue(field, rawValue, existingPresetId)
                    : new NormalizedFieldValue(null, null);
            normalizedValues.add(new NormalizedPresetValue(
                    field.seriesFieldId(), field.fieldId(), field.fieldName(),
                    field.fieldType(), field.valueType(), normalizedValue.valueText(), normalizedValue.valueDecimal()
            ));
        }
        return normalizedValues;
    }

    private List<MasterDataDtos.SpecPresetValueCreate> legacyPresetValues(
            String attributeJson, List<PresetSeriesField> seriesFields) {
        if (!StringUtils.hasText(attributeJson)) {
            return List.of();
        }
        try {
            JsonNode attributes = objectMapper.readTree(attributeJson);
            if (attributes == null || !attributes.isObject()) {
                throw new BusinessException("规格预设属性格式不正确");
            }
            List<MasterDataDtos.SpecPresetValueCreate> values = new ArrayList<>();
            for (PresetSeriesField field : seriesFields) {
                if (!"ENABLED".equals(field.status())) {
                    continue;
                }
                JsonNode value = attributes.get(field.fieldName());
                if (value != null && !value.isNull() && value.isValueNode()) {
                    values.add(new MasterDataDtos.SpecPresetValueCreate(field.seriesFieldId(), value.asText()));
                }
            }
            return values;
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException("规格预设属性格式不正确");
        }
    }

    private NormalizedFieldValue normalizePresetValue(PresetSeriesField field, String rawValue, Long existingPresetId) {
        if (rawValue.length() > 1000) {
            throw new BusinessException("规格预设字段值不能超过1000个字符");
        }
        if ("NUMBER".equals(field.fieldType())) {
            try {
                String numericText = rawValue;
                if (StringUtils.hasText(field.unitName()) && numericText.endsWith(field.unitName())) {
                    numericText = numericText.substring(0, numericText.length() - field.unitName().length()).trim();
                }
                BigDecimal number = new BigDecimal(numericText);
                if ("INTEGER".equals(field.valueType()) && number.stripTrailingZeros().scale() > 0) {
                    throw new BusinessException("字段“" + field.fieldName() + "”只能填写整数");
                }
                int actualScale = Math.max(number.stripTrailingZeros().scale(), 0);
                if (actualScale > field.decimalScale()) {
                    throw new BusinessException("字段“" + field.fieldName() + "”最多保留" + field.decimalScale() + "位小数");
                }
                if (field.minValue() != null && number.compareTo(field.minValue()) < 0) {
                    throw new BusinessException("字段“" + field.fieldName() + "”不能小于" + field.minValue().stripTrailingZeros().toPlainString());
                }
                if (field.maxValue() != null && number.compareTo(field.maxValue()) > 0) {
                    throw new BusinessException("字段“" + field.fieldName() + "”不能大于" + field.maxValue().stripTrailingZeros().toPlainString());
                }
                BigDecimal normalized = number.stripTrailingZeros();
                return new NormalizedFieldValue(normalized.toPlainString(), normalized);
            } catch (NumberFormatException ex) {
                throw new BusinessException("字段“" + field.fieldName() + "”必须填写数字");
            }
        }
        if ("SELECT".equals(field.fieldType())) {
            try {
                JsonNode options = objectMapper.readTree(field.optionJson());
                boolean matched = options != null && options.isArray();
                if (matched) {
                    matched = false;
                    for (JsonNode option : options) {
                        if (Objects.equals(option.asText(), rawValue)) {
                            matched = true;
                            break;
                        }
                    }
                }
                if (!matched && !existingPresetValue(existingPresetId, field.seriesFieldId(), rawValue)) {
                    throw new BusinessException("字段“" + field.fieldName() + "”的默认值不在可选范围内");
                }
            } catch (BusinessException ex) {
                throw ex;
            } catch (Exception ex) {
                throw new BusinessException("字段“" + field.fieldName() + "”的选项配置不正确");
            }
        }
        return new NormalizedFieldValue(rawValue, null);
    }

    private boolean existingPresetValue(Long presetId, Long seriesFieldId, String value) {
        if (presetId == null) {
            return false;
        }
        Integer count = jdbcTemplate.queryForObject("""
                select count(*) from product_spec_preset_value
                where preset_id = ? and series_field_id = ? and value_text = ?
                """, Integer.class, presetId, seriesFieldId, value);
        return count != null && count > 0;
    }

    private String existingPresetAttributeJson(Long presetId) {
        List<String> rows = jdbcTemplate.query("""
                select attribute_json from product_spec_preset where id = ?
                """, (rs, rowNum) -> rs.getString("attribute_json"), presetId);
        if (rows.isEmpty()) {
            throw new BusinessException("规格预设不存在");
        }
        return rows.get(0);
    }

    private String mergeSpecPresetAttributeJson(String baseAttributeJson, List<NormalizedPresetValue> values) {
        Map<String, Object> attributes = parseAttributeMap(baseAttributeJson);
        for (NormalizedPresetValue value : values) {
            if (!StringUtils.hasText(value.valueText())) {
                attributes.remove(value.fieldName());
            } else {
                attributes.put(value.fieldName(), jsonFieldValue(value.fieldType(), value.valueText()));
            }
        }
        return writeAttributeJson(attributes, "规格预设字段默认值过长，请缩短后保存");
    }

    private void mergeSpecPresetValues(Long presetId, List<NormalizedPresetValue> values) {
        for (NormalizedPresetValue value : values) {
            if (!StringUtils.hasText(value.valueText())) {
                jdbcTemplate.update("""
                        delete from product_spec_preset_value
                        where preset_id = ? and series_field_id = ?
                        """, presetId, value.seriesFieldId());
                continue;
            }
            jdbcTemplate.update("""
                    insert into product_spec_preset_value (preset_id, series_field_id, value_text, value_decimal)
                    values (?, ?, ?, ?)
                    on duplicate key update value_text = values(value_text), value_decimal = values(value_decimal)
                    """, presetId, value.seriesFieldId(), value.valueText(), value.valueDecimal());
        }
    }

    private record PresetSeriesField(
            Long seriesFieldId,
            Long fieldId,
            String fieldName,
            String fieldType,
            String valueType,
            String optionJson,
            String unitName,
            BigDecimal minValue,
            BigDecimal maxValue,
            int decimalScale,
            String status
    ) {
    }

    private record NormalizedPresetValue(
            Long seriesFieldId,
            Long fieldId,
            String fieldName,
            String fieldType,
            String valueType,
            String valueText,
            BigDecimal valueDecimal
    ) {
    }

    private void ensureCustomFieldNameAvailable(String name, Long excludeId) {
        String sql = "select count(*) from product_custom_field where lower(trim(name)) = lower(trim(?))";
        List<Object> args = new ArrayList<>();
        args.add(name);
        if (excludeId != null) {
            sql += " and id <> ?";
            args.add(excludeId);
        }
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, args.toArray());
        if (count != null && count > 0) {
            throw new BusinessException("规格字段名称已存在");
        }
    }

    private String customFieldType(String value) {
        String type = StringUtils.hasText(value) ? value.trim().toUpperCase() : "TEXT";
        return switch (type) {
            case "TEXT", "NUMBER", "SELECT" -> type;
            default -> throw new BusinessException("字段输入类型只支持文本、数字或选项");
        };
    }

    private String customFieldValueType(String fieldType, String value) {
        if (!"NUMBER".equals(fieldType)) {
            return "TEXT";
        }
        return "INTEGER".equalsIgnoreCase(value) ? "INTEGER" : "DECIMAL";
    }

    private String customFieldOptions(String fieldType, String optionJson) {
        if (!"SELECT".equals(fieldType)) {
            return "[]";
        }
        try {
            JsonNode options = objectMapper.readTree(StringUtils.hasText(optionJson) ? optionJson : "[]");
            if (!options.isArray()) {
                throw new BusinessException("选项字段必须维护选项列表");
            }
            for (JsonNode option : options) {
                if (!option.isTextual() || !StringUtils.hasText(option.asText())) {
                    throw new BusinessException("选项内容不能为空");
                }
            }
            return options.toString();
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException("字段选项格式不正确");
        }
    }

    private BatchRow lockBatch(Long batchId) {
        List<BatchRow> rows = jdbcTemplate.query("""
                select id, product_id, warehouse_id, batch_no, quantity_available, unit_cost, amount_cost
                from inventory_batch where id = ? for update
                """, (rs, rowNum) -> new BatchRow(
                rs.getLong("id"),
                rs.getLong("product_id"),
                rs.getLong("warehouse_id"),
                rs.getString("batch_no"),
                rs.getBigDecimal("quantity_available"),
                rs.getBigDecimal("unit_cost"),
                rs.getBigDecimal("amount_cost")
        ), batchId);
        if (rows.isEmpty()) {
            throw new BusinessException("库存批次不存在");
        }
        return rows.get(0);
    }

    private OutboundPayRow lockOutbound(Long outboundId) {
        List<OutboundPayRow> rows = jdbcTemplate.query("""
                select id, order_no, customer_id, sales_order_id, receivable_amount, paid_amount
                from outbound_order where id = ? for update
                """, (rs, rowNum) -> new OutboundPayRow(
                rs.getLong("id"),
                rs.getString("order_no"),
                rs.getLong("customer_id"),
                rs.getLong("sales_order_id"),
                rs.getBigDecimal("receivable_amount"),
                rs.getBigDecimal("paid_amount")
        ), outboundId);
        if (rows.isEmpty()) {
            throw new BusinessException("出库单不存在");
        }
        return rows.get(0);
    }

    private SalesOrderPayRow lockSalesOrderForPay(Long salesOrderId) {
        List<SalesOrderPayRow> rows = jdbcTemplate.query("""
                select id, order_no, customer_id, receivable_amount, received_amount, adjusted_amount, status
                from sales_order where id = ? for update
                """, (rs, rowNum) -> new SalesOrderPayRow(
                rs.getLong("id"),
                rs.getString("order_no"),
                rs.getLong("customer_id"),
                rs.getBigDecimal("receivable_amount"),
                rs.getBigDecimal("received_amount"),
                rs.getBigDecimal("adjusted_amount"),
                rs.getString("status")
        ), salesOrderId);
        if (rows.isEmpty()) {
            throw new BusinessException("销售订单不存在");
        }
        if ("CANCELLED".equals(rows.get(0).status())) {
            throw new BusinessException("销售订单已取消");
        }
        if ("DRAFT".equals(rows.get(0).status())) {
            throw new BusinessException("销售订单尚未确认销售，不能出库或收款");
        }
        return rows.get(0);
    }

    private SalesOrderEditRow lockSalesOrderForEdit(Long salesOrderId) {
        List<SalesOrderEditRow> rows = jdbcTemplate.query("""
                select id, order_no, customer_id, total_amount, received_amount, status,
                       payment_term, shipment_payment_policy, required_deposit_amount,
                       business_date,current_revision_no
                from sales_order where id = ? for update
                """, (rs, rowNum) -> new SalesOrderEditRow(
                rs.getLong("id"), rs.getString("order_no"), rs.getLong("customer_id"),
                rs.getBigDecimal("total_amount"), rs.getBigDecimal("received_amount"), rs.getString("status"),
                rs.getString("payment_term"), rs.getString("shipment_payment_policy"),
                rs.getBigDecimal("required_deposit_amount"), rs.getDate("business_date").toLocalDate(),
                rs.getInt("current_revision_no")
        ), salesOrderId);
        if (rows.isEmpty()) {
            throw new BusinessException("销售订单不存在");
        }
        return rows.get(0);
    }

    private InboundAuditRow lockInbound(Long inboundId) {
        List<InboundAuditRow> rows = jdbcTemplate.query("""
                select id, order_no, purchase_order_id, supplier_id, warehouse_id, business_date, total_amount, status
                from inbound_order where id = ? for update
                """, (rs, rowNum) -> new InboundAuditRow(
                rs.getLong("id"),
                rs.getString("order_no"),
                rs.getLong("purchase_order_id"),
                rs.getLong("supplier_id"),
                rs.getLong("warehouse_id"),
                rs.getDate("business_date").toLocalDate(),
                rs.getBigDecimal("total_amount"),
                rs.getString("status")
        ), inboundId);
        if (rows.isEmpty()) {
            throw new BusinessException("入库单不存在");
        }
        return rows.get(0);
    }

    private PurchasePayRow lockPurchaseForPay(Long purchaseOrderId) {
        List<PurchasePayRow> rows = jdbcTemplate.query("""
                select o.id,o.order_no,o.supplier_id,
                       case when o.source_type='DIRECT_TRADE'
                              then coalesce(d.purchase_payable_amount,0)
                            else o.total_amount end total_amount,
                       o.paid_amount,o.status
                from purchase_order o
                left join direct_trade_order d on d.purchase_order_id=o.id
                where o.id = ? for update
                """, (rs, rowNum) -> new PurchasePayRow(
                rs.getLong("id"),
                rs.getString("order_no"),
                rs.getLong("supplier_id"),
                rs.getBigDecimal("total_amount"),
                rs.getBigDecimal("paid_amount"),
                rs.getString("status")
        ), purchaseOrderId);
        if (rows.isEmpty()) {
            throw new BusinessException("采购订单不存在");
        }
        if ("CANCELLED".equals(rows.get(0).status())) {
            throw new BusinessException("采购订单已取消");
        }
        if ("DRAFT".equals(rows.get(0).status())) {
            throw new BusinessException("采购订单尚未确认订货，不能入库或付款");
        }
        return rows.get(0);
    }

    private PurchaseEditRow lockPurchaseForEdit(Long purchaseOrderId) {
        List<PurchaseEditRow> rows = jdbcTemplate.query("""
                select id,order_no,supplier_id,received_quantity,paid_amount,status,business_date,current_revision_no
                from purchase_order where id = ? for update
                """, (rs, rowNum) -> new PurchaseEditRow(
                rs.getLong("id"), rs.getString("order_no"),
                rs.getLong("supplier_id"),
                rs.getBigDecimal("received_quantity"),
                rs.getBigDecimal("paid_amount"),
                rs.getString("status"), rs.getDate("business_date").toLocalDate(),
                rs.getInt("current_revision_no")
        ), purchaseOrderId);
        if (rows.isEmpty()) {
            throw new BusinessException("采购订单不存在");
        }
        return rows.get(0);
    }

    private InboundPayRow lockInboundForPay(Long inboundId) {
        List<InboundPayRow> rows = jdbcTemplate.query("""
                select id, order_no, purchase_order_id, supplier_id, payable_amount, paid_amount, status
                from inbound_order where id = ? for update
                """, (rs, rowNum) -> new InboundPayRow(
                rs.getLong("id"),
                rs.getString("order_no"),
                rs.getLong("purchase_order_id"),
                rs.getLong("supplier_id"),
                rs.getBigDecimal("payable_amount"),
                rs.getBigDecimal("paid_amount"),
                rs.getString("status")
        ), inboundId);
        if (rows.isEmpty()) {
            throw new BusinessException("入库单不存在");
        }
        return rows.get(0);
    }

    private PaymentLock lockPayment(Long paymentId) {
        List<PaymentLock> rows = jdbcTemplate.query("""
                select id,payment_no,customer_id,sales_order_id,coalesce(outbound_id,0) outbound_id,
                       amount,allocated_amount,unallocated_amount,payment_method,account_type,
                       verification_status,fund_nature,allocation_mode
                from payment where id=? for update
                """, (rs, rowNum) -> new PaymentLock(
                rs.getLong("id"), rs.getString("payment_no"), rs.getLong("customer_id"),
                rs.getLong("sales_order_id"), rs.getLong("outbound_id"), rs.getBigDecimal("amount"),
                rs.getBigDecimal("allocated_amount"), rs.getBigDecimal("unallocated_amount"),
                rs.getString("payment_method"), rs.getString("account_type"), rs.getString("verification_status"),
                rs.getString("fund_nature"), rs.getString("allocation_mode")),
                paymentId);
        if (rows.isEmpty()) throw new BusinessException("客户收款不存在");
        return rows.get(0);
    }

    private SupplierPaymentLock lockSupplierPayment(Long paymentId) {
        List<SupplierPaymentLock> rows = jdbcTemplate.query("""
                select id,payment_no,supplier_id,purchase_order_id,inbound_id,amount,allocated_amount,
                       unallocated_amount,payment_method,account_type,verification_status,fund_nature,allocation_mode
                from supplier_payment where id=? for update
                """, (rs, rowNum) -> new SupplierPaymentLock(
                rs.getLong("id"), rs.getString("payment_no"), rs.getLong("supplier_id"),
                rs.getLong("purchase_order_id"), rs.getLong("inbound_id"), rs.getBigDecimal("amount"),
                rs.getBigDecimal("allocated_amount"), rs.getBigDecimal("unallocated_amount"),
                rs.getString("payment_method"), rs.getString("account_type"), rs.getString("verification_status"),
                rs.getString("fund_nature"), rs.getString("allocation_mode")),
                paymentId);
        if (rows.isEmpty()) throw new BusinessException("供应商付款不存在");
        return rows.get(0);
    }

    private Long findOutboundIdByNo(String outboundNo) {
        List<Long> ids = jdbcTemplate.query("select id from outbound_order where order_no = ? limit 1",
                (rs, rowNum) -> rs.getLong("id"), outboundNo);
        if (ids.isEmpty()) {
            throw new BusinessException("出库单号不存在");
        }
        return ids.get(0);
    }

    private String receivableAdjustmentType(String value) {
        return switch (blank(value).toUpperCase()) {
            case "COMMERCIAL_DISCOUNT", "BAD_DEBT", "SMALL_WAIVER" -> value.toUpperCase();
            default -> throw new BusinessException("应收调整类型不正确");
        };
    }

    private String receivableAdjustmentTypeName(String value) {
        return switch (value) {
            case "COMMERCIAL_DISCOUNT" -> "尾款折让";
            case "BAD_DEBT" -> "坏账核销";
            case "SMALL_WAIVER" -> "小额豁免";
            default -> "应收调整";
        };
    }

    private String acceptanceBillType(String value) {
        return switch (blank(value).toUpperCase()) {
            case "BANK_ACCEPTANCE", "COMMERCIAL_ACCEPTANCE" -> value.toUpperCase();
            default -> throw new BusinessException("承兑票据类型不正确");
        };
    }

    private BigDecimal salesOrderOutstanding(SalesOrderPayRow order) {
        return order.totalAmount().subtract(order.receivedAmount()).subtract(order.adjustedAmount()).max(BigDecimal.ZERO);
    }

    private void refreshSalesOrderPaymentStatus(Long salesOrderId) {
        jdbcTemplate.update("""
                update sales_order
                set payment_status = case
                  when received_amount + adjusted_amount >= receivable_amount and receivable_amount > 0 then 'PAID'
                  when received_amount + adjusted_amount > 0 then 'PARTIAL'
                  else 'UNPAID' end
                where id = ?
                """, salesOrderId);
    }

    private void refreshOutboundPaymentStatus(Long outboundId) {
        jdbcTemplate.update("""
                update outbound_order
                set payment_status = case
                  when receivable_amount > 0 and paid_amount >= receivable_amount then 'PAID'
                  when paid_amount > 0 then 'PARTIAL'
                  else 'UNPAID' end
                where id = ?
                """, outboundId);
    }

    private void refreshPurchasePaymentStatus(Long purchaseOrderId) {
        jdbcTemplate.update("""
                update purchase_order o
                left join direct_trade_order d on d.purchase_order_id=o.id
                set o.payment_status=case
                  when (case when o.source_type='DIRECT_TRADE'
                                  then coalesce(d.purchase_payable_amount,0)
                             else o.total_amount end)>0
                       and o.paid_amount>=(case when o.source_type='DIRECT_TRADE'
                                                   then coalesce(d.purchase_payable_amount,0)
                                              else o.total_amount end) then 'PAID'
                  when o.paid_amount>0 then 'PARTIAL' else 'UNPAID' end
                where o.id=?
                """, purchaseOrderId);
    }

    private void refreshInboundPaymentStatus(Long inboundId) {
        jdbcTemplate.update("""
                update inbound_order set payment_status=case
                  when payable_amount>0 and paid_amount>=payable_amount then 'PAID'
                  when paid_amount>0 then 'PARTIAL' else 'UNPAID' end where id=?
                """, inboundId);
    }

    private ReceivableAdjustmentLockRow lockReceivableAdjustment(Long adjustmentId) {
        List<ReceivableAdjustmentLockRow> rows = jdbcTemplate.query("""
                select id, sales_order_id, customer_id, adjustment_type, amount, status
                from receivable_adjustment where id = ? for update
                """, (rs, rowNum) -> new ReceivableAdjustmentLockRow(
                rs.getLong("id"), rs.getLong("sales_order_id"), rs.getLong("customer_id"),
                rs.getString("adjustment_type"), rs.getBigDecimal("amount"), rs.getString("status")
        ), adjustmentId);
        if (rows.isEmpty()) throw new BusinessException("应收调整单不存在");
        return rows.get(0);
    }

    private AcceptanceBillLockRow lockAcceptanceBill(Long billId) {
        List<AcceptanceBillLockRow> rows = jdbcTemplate.query("""
                select id, instrument_no, customer_id, sales_order_id, face_amount, settlement_payment_id,
                       status, acceptor, net_proceeds
                from acceptance_bill where id = ? for update
                """, (rs, rowNum) -> new AcceptanceBillLockRow(
                rs.getLong("id"), rs.getString("instrument_no"), rs.getLong("customer_id"),
                rs.getLong("sales_order_id"), rs.getBigDecimal("face_amount"),
                rs.getLong("settlement_payment_id"), rs.getString("status"), rs.getString("acceptor"),
                rs.getBigDecimal("net_proceeds")
        ), billId);
        if (rows.isEmpty()) throw new BusinessException("承兑票据不存在");
        return rows.get(0);
    }

    private void requireHeldAcceptance(AcceptanceBillLockRow bill) {
        if (!"HELD".equals(bill.status())) {
            throw new BusinessException("只有持有中的承兑票据可以执行该操作");
        }
    }

    private AcceptanceEventRef insertAcceptanceEvent(Long billId, String eventType, LocalDate occurredDate,
                                                     BigDecimal cashAmount, BigDecimal feeAmount,
                                                     Long businessBankAccountId, String counterparty, String memo) {
        String eventNo = nextNo("CP");
        Long eventId = insert("""
                insert into acceptance_bill_event
                (event_no, bill_id, event_type, occurred_date, cash_amount, fee_amount,
                 business_bank_account_id, counterparty, memo)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, eventNo, billId, eventType, Date.valueOf(occurredDate), nvl(cashAmount), nvl(feeAmount),
                businessBankAccountId, blank(counterparty), blank(memo));
        return new AcceptanceEventRef(eventId, eventNo);
    }

    private String paymentMethod(String value) {
        if (!StringUtils.hasText(value)) {
            throw new BusinessException("请选择结算方式");
        }
        return switch (value.trim()) {
            case "BANK", "CASH", "OFFSET", "ADVANCE", "REWARD", "ACCEPTANCE" -> value.trim();
            default -> throw new BusinessException("不支持的结算方式");
        };
    }

    private Long resolveBusinessBankAccount(String paymentMethod, Long requestedId, LocalDate businessDate) {
        if (!"BANK".equals(paymentMethod)) {
            if (requestedId != null && requestedId > 0) {
                throw new BusinessException("只有银行结算才能选择企业银行账户");
            }
            return null;
        }
        LocalDate date = businessDate == null ? LocalDate.now() : businessDate;
        if (requestedId != null && requestedId > 0) {
            List<Long> ids = jdbcTemplate.query("""
                    select id from business_bank_account
                    where id=? and status='ENABLED' and opening_date<=?
                    """, (rs, rowNum) -> rs.getLong("id"), requestedId, Date.valueOf(date));
            if (ids.isEmpty()) throw new BusinessException("所选企业银行账户不存在、已停用或尚未启用");
            return ids.get(0);
        }
        List<Long> enabled = jdbcTemplate.query("""
                select id from business_bank_account
                where status='ENABLED' and opening_date<=? order by id
                """, (rs, rowNum) -> rs.getLong("id"), Date.valueOf(date));
        if (enabled.isEmpty()) throw new BusinessException("请先在银行对账中建立并启用企业银行账户");
        if (enabled.size() > 1) throw new BusinessException("存在多个企业银行账户，请选择本次资金实际收付账户");
        return enabled.get(0);
    }

    private void ensureFundSourceNotReconciled(String sourceType, Long sourceId) {
        Long linked = jdbcTemplate.queryForObject("""
                select
                  (select count(*) from bank_reconciliation_match
                   where source_type=? and source_id=? and status='ACTIVE')+
                  (select count(*) from bank_reconciliation_pending_system
                   where source_type=? and source_id=? and status='OPEN')
                """, Long.class, sourceType, sourceId, sourceType, sourceId);
        if (linked != null && linked > 0) {
            throw new BusinessException("该资金流水已进入银行对账，请先重新开放对应对账单并撤销匹配或未达项后再冲正");
        }
    }

    private void ensureFundSourceNotTaxMatched(String sourceType, Long sourceId) {
        Long linked = jdbcTemplate.queryForObject("""
                select count(*) from tax_invoice_relation
                where source_type=? and source_id=? and relation_layer='FUNDS' and status='ACTIVE'
                """, Long.class, sourceType, sourceId);
        if (linked != null && linked > 0) {
            throw new BusinessException("该资金已参与发票勾稽，不能直接重分配；请先解除对应资金层勾稽关系");
        }
    }

    private void ensureAllocationDateOpen(LocalDate businessDate) {
        if (businessDate == null) throw new BusinessException("核销调整业务日期不能为空");
        Long closed = jdbcTemplate.queryForObject("""
                select count(*) from accounting_period p
                join accounting_entity e on e.id=p.entity_id and e.status='ENABLED'
                where ? between p.start_date and p.end_date and p.status='CLOSED'
                """, Long.class, Date.valueOf(businessDate));
        if (closed != null && closed > 0) {
            throw new BusinessException("核销调整日期所属会计期间已结账，请在当前开放期间处理或先规范反结账");
        }
    }

    private BigDecimal settlementAmount(BigDecimal value, String label) {
        if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(label + "必须大于0");
        }
        try {
            return value.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException ex) {
            throw new BusinessException(label + "最多保留2位小数");
        }
    }

    private List<NormalizedAllocation> normalizeAllocations(
            List<PaymentAllocationDtos.AllocationInput> values, Set<String> allowedTypes) {
        if (values == null || values.isEmpty()) return List.of();
        if (values.size() > 100) throw new BusinessException("单笔收付款最多分配100张业务单据");
        List<NormalizedAllocation> rows = new ArrayList<>();
        Set<AllocationTarget> targets = new HashSet<>();
        for (PaymentAllocationDtos.AllocationInput value : values) {
            if (value == null) throw new BusinessException("核销分配明细不能为空");
            String type = blank(value.targetType()).toUpperCase();
            if (!allowedTypes.contains(type)) throw new BusinessException("核销目标类型不正确");
            if (value.targetId() == null || value.targetId() <= 0) throw new BusinessException("核销目标单据不能为空");
            BigDecimal amount = settlementAmount(value.amount(), "核销分配金额");
            AllocationTarget target = new AllocationTarget(type, value.targetId());
            if (!targets.add(target)) throw new BusinessException("同一目标单据不能重复分配");
            rows.add(new NormalizedAllocation(type, value.targetId(), amount));
        }
        rows.sort((left, right) -> {
            int type = left.targetType().compareTo(right.targetType());
            return type != 0 ? type : left.targetId().compareTo(right.targetId());
        });
        return List.copyOf(rows);
    }

    private BigDecimal allocationTotal(List<NormalizedAllocation> rows) {
        return rows.stream().map(NormalizedAllocation::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private Map<AllocationTarget, BigDecimal> allocationDeltas(
            List<NormalizedAllocation> rows, BigDecimal multiplier) {
        Map<AllocationTarget, BigDecimal> result = new LinkedHashMap<>();
        rows.forEach(row -> result.merge(new AllocationTarget(row.targetType(), row.targetId()),
                row.amount().multiply(multiplier), BigDecimal::add));
        return result;
    }

    private String allocationStatus(BigDecimal allocated, BigDecimal total) {
        if (allocated.compareTo(BigDecimal.ZERO) == 0) return "UNAPPLIED";
        return allocated.compareTo(total) == 0 ? "VERIFIED" : "PARTIAL";
    }

    private String allocationDescription(int count, BigDecimal unallocated) {
        return "（已分配" + count + "张，未分配" + unallocated.setScale(2, RoundingMode.UNNECESSARY).toPlainString() + "元）";
    }

    private Set<Long> normalizedReleaseIds(List<Long> values) {
        Set<Long> result = new HashSet<>();
        if (values == null) return result;
        for (Long value : values) {
            if (value == null || value <= 0) throw new BusinessException("待释放分配行编号不正确");
            if (!result.add(value)) throw new BusinessException("待释放分配行不能重复");
        }
        return result;
    }

    private void requireSettlementChangeable(String fundNature, String status, String label) {
        if (!"SETTLEMENT".equals(fundNature)) throw new BusinessException(label + "属于预收预付，不能按普通核销重分配");
        if ("REVERSED".equals(status)) throw new BusinessException(label + "已冲正，不能再修改分配");
    }

    private void requireAccountAllocationFullyApplied(String paymentMethod, BigDecimal amount,
                                                      BigDecimal allocatedAmount, int activeAllocationCount,
                                                      String label) {
        if (!accountPaymentMethod(paymentMethod)) return;
        if (allocatedAmount.compareTo(amount) != 0 || activeAllocationCount != 1) {
            throw new BusinessException(label + "调整后必须保持一张单据全额核销，不能形成未分配余额或拆分到多张单据");
        }
    }

    private Map<AllocationTarget, String> applyCustomerAllocationDeltas(
            Long customerId, Map<AllocationTarget, BigDecimal> deltas) {
        Map<Long, BigDecimal> salesDeltas = new LinkedHashMap<>();
        Map<Long, BigDecimal> outboundDeltas = new LinkedHashMap<>();
        Map<Long, Long> outboundParents = new HashMap<>();
        Map<AllocationTarget, String> targetNos = new HashMap<>();
        for (Map.Entry<AllocationTarget, BigDecimal> entry : deltas.entrySet()) {
            AllocationTarget target = entry.getKey();
            if ("SALES_ORDER".equals(target.type())) {
                salesDeltas.merge(target.id(), entry.getValue(), BigDecimal::add);
            } else {
                List<OutboundReference> refs = jdbcTemplate.query("""
                        select id,order_no,customer_id,sales_order_id from outbound_order where id=?
                        """, (rs, rowNum) -> new OutboundReference(rs.getLong("id"), rs.getString("order_no"),
                        rs.getLong("customer_id"), rs.getLong("sales_order_id")), target.id());
                if (refs.isEmpty()) throw new BusinessException("出库单不存在");
                OutboundReference ref = refs.get(0);
                if (!Objects.equals(ref.partnerId(), customerId)) throw new BusinessException("回款客户与出库单客户不一致");
                outboundDeltas.merge(target.id(), entry.getValue(), BigDecimal::add);
                outboundParents.put(target.id(), ref.parentOrderId());
                if (ref.parentOrderId() > 0) salesDeltas.merge(ref.parentOrderId(), entry.getValue(), BigDecimal::add);
            }
        }
        List<Long> salesIds = new ArrayList<>(salesDeltas.keySet());
        Collections.sort(salesIds);
        for (Long id : salesIds) {
            SalesOrderPayRow order = lockSalesOrderForPay(id);
            if (!Objects.equals(order.customerId(), customerId)) throw new BusinessException("回款客户与销售订单客户不一致");
            BigDecimal next = order.receivedAmount().add(salesDeltas.get(id));
            BigDecimal ceiling = order.totalAmount().subtract(order.adjustedAmount());
            if (next.compareTo(BigDecimal.ZERO) < 0) throw new BusinessException("销售订单累计收款不足，不能释放该核销");
            if (next.compareTo(ceiling) > 0) throw new BusinessException("核销分配金额超过销售订单未回款金额");
            targetNos.put(new AllocationTarget("SALES_ORDER", id), order.orderNo());
        }
        List<Long> outboundIds = new ArrayList<>(outboundDeltas.keySet());
        Collections.sort(outboundIds);
        for (Long id : outboundIds) {
            OutboundPayRow outbound = lockOutbound(id);
            if (!Objects.equals(outbound.customerId(), customerId)) throw new BusinessException("回款客户与出库单客户不一致");
            if (!Objects.equals(outbound.salesOrderId(), outboundParents.get(id))) throw new BusinessException("出库单来源订单已变化，请刷新后重试");
            BigDecimal next = outbound.paidAmount().add(outboundDeltas.get(id));
            if (next.compareTo(BigDecimal.ZERO) < 0) throw new BusinessException("出库单累计收款不足，不能释放该核销");
            if (next.compareTo(outbound.receivableAmount()) > 0) throw new BusinessException("核销分配金额超过出库单未回款金额");
            targetNos.put(new AllocationTarget("OUTBOUND", id), outbound.orderNo());
        }
        for (Long id : salesIds) {
            jdbcTemplate.update("update sales_order set received_amount=received_amount+? where id=?", salesDeltas.get(id), id);
            refreshSalesOrderPaymentStatus(id);
        }
        for (Long id : outboundIds) {
            jdbcTemplate.update("update outbound_order set paid_amount=paid_amount+? where id=?", outboundDeltas.get(id), id);
            refreshOutboundPaymentStatus(id);
        }
        return targetNos;
    }

    private Map<AllocationTarget, String> applySupplierAllocationDeltas(
            Long supplierId, Map<AllocationTarget, BigDecimal> deltas) {
        Map<Long, BigDecimal> purchaseDeltas = new LinkedHashMap<>();
        Map<Long, BigDecimal> inboundDeltas = new LinkedHashMap<>();
        Map<Long, Long> inboundParents = new HashMap<>();
        Map<AllocationTarget, String> targetNos = new HashMap<>();
        for (Map.Entry<AllocationTarget, BigDecimal> entry : deltas.entrySet()) {
            AllocationTarget target = entry.getKey();
            if ("PURCHASE_ORDER".equals(target.type())) {
                purchaseDeltas.merge(target.id(), entry.getValue(), BigDecimal::add);
            } else {
                List<InboundReference> refs = jdbcTemplate.query("""
                        select id,order_no,supplier_id,purchase_order_id from inbound_order where id=?
                        """, (rs, rowNum) -> new InboundReference(rs.getLong("id"), rs.getString("order_no"),
                        rs.getLong("supplier_id"), rs.getLong("purchase_order_id")), target.id());
                if (refs.isEmpty()) throw new BusinessException("入库单不存在");
                InboundReference ref = refs.get(0);
                if (!Objects.equals(ref.partnerId(), supplierId)) throw new BusinessException("付款供应商与入库单供应商不一致");
                inboundDeltas.merge(target.id(), entry.getValue(), BigDecimal::add);
                inboundParents.put(target.id(), ref.parentOrderId());
                if (ref.parentOrderId() > 0) purchaseDeltas.merge(ref.parentOrderId(), entry.getValue(), BigDecimal::add);
            }
        }
        List<Long> purchaseIds = new ArrayList<>(purchaseDeltas.keySet());
        Collections.sort(purchaseIds);
        for (Long id : purchaseIds) {
            PurchasePayRow order = lockPurchaseForPay(id);
            if (!Objects.equals(order.supplierId(), supplierId)) throw new BusinessException("付款供应商与采购订单供应商不一致");
            BigDecimal next = order.paidAmount().add(purchaseDeltas.get(id));
            if (next.compareTo(BigDecimal.ZERO) < 0) throw new BusinessException("采购订单累计付款不足，不能释放该核销");
            if (next.compareTo(order.totalAmount()) > 0) throw new BusinessException("核销分配金额超过采购订单未付款金额");
            targetNos.put(new AllocationTarget("PURCHASE_ORDER", id), order.orderNo());
        }
        List<Long> inboundIds = new ArrayList<>(inboundDeltas.keySet());
        Collections.sort(inboundIds);
        for (Long id : inboundIds) {
            InboundPayRow inbound = lockInboundForPay(id);
            if (!Objects.equals(inbound.supplierId(), supplierId)) throw new BusinessException("付款供应商与入库单供应商不一致");
            if (!"STOCKED".equals(inbound.status())) throw new BusinessException("入库单未审核入库，不可按入库单付款");
            if (!Objects.equals(inbound.purchaseOrderId(), inboundParents.get(id))) throw new BusinessException("入库单来源订单已变化，请刷新后重试");
            BigDecimal next = inbound.paidAmount().add(inboundDeltas.get(id));
            if (next.compareTo(BigDecimal.ZERO) < 0) throw new BusinessException("入库单累计付款不足，不能释放该核销");
            if (next.compareTo(inbound.payableAmount()) > 0) throw new BusinessException("核销分配金额超过入库单未付款金额");
            targetNos.put(new AllocationTarget("INBOUND", id), inbound.orderNo());
        }
        for (Long id : purchaseIds) {
            jdbcTemplate.update("update purchase_order set paid_amount=paid_amount+? where id=?", purchaseDeltas.get(id), id);
            refreshPurchasePaymentStatus(id);
        }
        for (Long id : inboundIds) {
            jdbcTemplate.update("update inbound_order set paid_amount=paid_amount+? where id=?", inboundDeltas.get(id), id);
            refreshInboundPaymentStatus(id);
        }
        return targetNos;
    }

    private List<BusinessPostingDtos.EntryDraft> customerPaymentPostingEntries(
            Long customerId, Long paymentId, String paymentNo,
            BigDecimal allocated, BigDecimal unallocated) {
        List<BusinessPostingDtos.EntryDraft> entries = new ArrayList<>();
        if (allocated.signum() > 0) entries.add(new BusinessPostingDtos.EntryDraft(
                "RECEIVABLE_SETTLEMENT", "DECREASE", customerId, 0L, 0L, 0L,
                "PAYMENT", paymentId, BigDecimal.ZERO, "", allocated,
                "客户普通收款已核销 " + paymentNo));
        if (unallocated.signum() > 0) entries.add(new BusinessPostingDtos.EntryDraft(
                "UNALLOCATED_CUSTOMER_RECEIPT", "INCREASE", customerId, 0L, 0L, 0L,
                "PAYMENT", paymentId, BigDecimal.ZERO, "", unallocated,
                "客户普通收款暂未分配 " + paymentNo));
        return List.copyOf(entries);
    }

    private List<BusinessPostingDtos.EntryDraft> supplierPaymentPostingEntries(
            Long supplierId, Long paymentId, String paymentNo,
            BigDecimal allocated, BigDecimal unallocated) {
        List<BusinessPostingDtos.EntryDraft> entries = new ArrayList<>();
        if (allocated.signum() > 0) entries.add(new BusinessPostingDtos.EntryDraft(
                "PAYABLE_SETTLEMENT", "DECREASE", supplierId, 0L, 0L, 0L,
                "SUPPLIER_PAYMENT", paymentId, BigDecimal.ZERO, "", allocated,
                "供应商普通付款已核销 " + paymentNo));
        if (unallocated.signum() > 0) entries.add(new BusinessPostingDtos.EntryDraft(
                "UNALLOCATED_SUPPLIER_PAYMENT", "INCREASE", supplierId, 0L, 0L, 0L,
                "SUPPLIER_PAYMENT", paymentId, BigDecimal.ZERO, "", unallocated,
                "供应商普通付款暂未分配 " + paymentNo));
        return List.copyOf(entries);
    }

    private void postCustomerAllocationChange(PaymentLock payment, LocalDate businessDate,
                                              String reason, BigDecimal allocationChange) {
        int revision = nextAllocationPostingRevision("PAYMENT", payment.id());
        postingService.post("PAYMENT", payment.id(), payment.paymentNo(), "ALLOCATION_CHANGE", revision,
                businessDate, payment.customerId(), 0L, reason, List.of(new BusinessPostingDtos.EntryDraft(
                        "CUSTOMER_RECEIPT_ALLOCATION", allocationChange.signum() > 0 ? "INCREASE" : "DECREASE",
                        payment.customerId(), 0L, 0L, 0L, "PAYMENT_ALLOCATION", payment.id(),
                        BigDecimal.ZERO, "", allocationChange.abs(), reason)));
    }

    private void postSupplierAllocationChange(SupplierPaymentLock payment, LocalDate businessDate,
                                              String reason, BigDecimal allocationChange) {
        int revision = nextAllocationPostingRevision("SUPPLIER_PAYMENT", payment.id());
        postingService.post("SUPPLIER_PAYMENT", payment.id(), payment.paymentNo(), "ALLOCATION_CHANGE", revision,
                businessDate, payment.supplierId(), 0L, reason, List.of(new BusinessPostingDtos.EntryDraft(
                        "SUPPLIER_PAYMENT_ALLOCATION", allocationChange.signum() > 0 ? "INCREASE" : "DECREASE",
                        payment.supplierId(), 0L, 0L, 0L, "SUPPLIER_PAYMENT_ALLOCATION", payment.id(),
                        BigDecimal.ZERO, "", allocationChange.abs(), reason)));
    }

    private int nextAllocationPostingRevision(String sourceType, Long sourceId) {
        Integer revision = jdbcTemplate.queryForObject("""
                select coalesce(max(source_revision_no),0)+1 from business_posting_batch
                where source_type=? and source_id=? and posting_action='ALLOCATION_CHANGE'
                """, Integer.class, sourceType, sourceId);
        return revision == null ? 1 : revision;
    }

    private void insertCustomerAllocations(Long paymentId, String paymentNo, Long customerId,
                                           List<NormalizedAllocation> rows,
                                           Map<AllocationTarget, String> targetNos, LocalDate businessDate,
                                           String captureSource, String reason) {
        int sequence = jdbcTemplate.queryForObject(
                "select count(*) from payment_allocation where payment_id=?", Integer.class, paymentId);
        for (NormalizedAllocation row : rows) {
            sequence++;
            Long allocationId = insert("""
                    insert into payment_allocation
                    (allocation_no,payment_id,customer_id,target_type,target_id,allocated_amount,status,capture_source)
                    values (?,?,?,?,?,?,'ACTIVE',?)
                    """, paymentNo + "-" + String.format("%02d", sequence), paymentId, customerId,
                    row.targetType(), row.targetId(), row.amount(), captureSource);
            recordCustomerAllocationEvent(allocationId, paymentId, "APPLIED", "", "ACTIVE", row.amount(),
                    businessDate, reason + " " + targetNos.getOrDefault(new AllocationTarget(row.targetType(), row.targetId()), ""));
        }
    }

    private void insertSupplierAllocations(Long paymentId, String paymentNo, Long supplierId,
                                           List<NormalizedAllocation> rows,
                                           Map<AllocationTarget, String> targetNos, LocalDate businessDate,
                                           String captureSource, String reason) {
        int sequence = jdbcTemplate.queryForObject(
                "select count(*) from supplier_payment_allocation where supplier_payment_id=?", Integer.class, paymentId);
        for (NormalizedAllocation row : rows) {
            sequence++;
            Long allocationId = insert("""
                    insert into supplier_payment_allocation
                    (allocation_no,supplier_payment_id,supplier_id,target_type,target_id,allocated_amount,status,capture_source)
                    values (?,?,?,?,?,?,'ACTIVE',?)
                    """, paymentNo + "-" + String.format("%02d", sequence), paymentId, supplierId,
                    row.targetType(), row.targetId(), row.amount(), captureSource);
            recordSupplierAllocationEvent(allocationId, paymentId, "APPLIED", "", "ACTIVE", row.amount(),
                    businessDate, reason + " " + targetNos.getOrDefault(new AllocationTarget(row.targetType(), row.targetId()), ""));
        }
    }

    private void recordCustomerAllocationEvent(Long allocationId, Long paymentId, String action,
                                               String fromStatus, String toStatus, BigDecimal amount,
                                               LocalDate businessDate, String reason) {
        SecurityContext.Principal principal = SecurityContext.require();
        jdbcTemplate.update("""
                insert into payment_allocation_event
                (allocation_id,payment_id,action,from_status,to_status,amount,business_date,reason,operator_id,operator_name)
                values (?,?,?,?,?,?,?,?,?,?)
                """, allocationId, paymentId, action, blank(fromStatus), toStatus, amount,
                Date.valueOf(businessDate == null ? LocalDate.now() : businessDate), blank(reason),
                principal.userId(), principal.employeeName());
    }

    private void recordSupplierAllocationEvent(Long allocationId, Long paymentId, String action,
                                               String fromStatus, String toStatus, BigDecimal amount,
                                               LocalDate businessDate, String reason) {
        SecurityContext.Principal principal = SecurityContext.require();
        jdbcTemplate.update("""
                insert into supplier_payment_allocation_event
                (allocation_id,supplier_payment_id,action,from_status,to_status,amount,business_date,reason,operator_id,operator_name)
                values (?,?,?,?,?,?,?,?,?,?)
                """, allocationId, paymentId, action, blank(fromStatus), toStatus, amount,
                Date.valueOf(businessDate == null ? LocalDate.now() : businessDate), blank(reason),
                principal.userId(), principal.employeeName());
    }

    private List<CustomerAllocationLock> lockCustomerAllocations(Long paymentId) {
        return jdbcTemplate.query("""
                select id,target_type,target_id,allocated_amount from payment_allocation
                where payment_id=? and status='ACTIVE' order by target_type,target_id,id for update
                """, (rs, rowNum) -> new CustomerAllocationLock(rs.getLong("id"), rs.getString("target_type"),
                rs.getLong("target_id"), rs.getBigDecimal("allocated_amount")), paymentId);
    }

    private List<SupplierAllocationLock> lockSupplierAllocations(Long paymentId) {
        return jdbcTemplate.query("""
                select id,target_type,target_id,allocated_amount from supplier_payment_allocation
                where supplier_payment_id=? and status='ACTIVE' order by target_type,target_id,id for update
                """, (rs, rowNum) -> new SupplierAllocationLock(rs.getLong("id"), rs.getString("target_type"),
                rs.getLong("target_id"), rs.getBigDecimal("allocated_amount")), paymentId);
    }

    private PaymentAllocationDtos.Detail allocationDetail(Long paymentId, boolean supplier) {
        String headerSql = supplier ? """
                select p.id,p.payment_no,p.supplier_id partner_id,x.name partner_name,p.amount,p.allocated_amount,
                       p.unallocated_amount,p.payment_method,p.business_bank_account_id,b.account_name bank_name,
                       p.paid_date business_date,p.verification_status,p.fund_nature,p.allocation_mode,
                       p.reversal_no,p.reversal_reason,p.reversed_at
                from supplier_payment p join partner x on x.id=p.supplier_id
                left join business_bank_account b on b.id=p.business_bank_account_id where p.id=?
                """ : """
                select p.id,p.payment_no,p.customer_id partner_id,x.name partner_name,p.amount,p.allocated_amount,
                       p.unallocated_amount,p.payment_method,p.business_bank_account_id,b.account_name bank_name,
                       p.received_date business_date,p.verification_status,p.fund_nature,p.allocation_mode,
                       p.reversal_no,p.reversal_reason,p.reversed_at
                from payment p join partner x on x.id=p.customer_id
                left join business_bank_account b on b.id=p.business_bank_account_id where p.id=?
                """;
        List<AllocationDetailHeader> headers = jdbcTemplate.query(headerSql, (rs, rowNum) ->
                new AllocationDetailHeader(rs.getLong("id"), rs.getString("payment_no"), rs.getLong("partner_id"),
                        rs.getString("partner_name"), rs.getBigDecimal("amount"), rs.getBigDecimal("allocated_amount"),
                        rs.getBigDecimal("unallocated_amount"), rs.getString("payment_method"),
                        rs.getObject("business_bank_account_id", Long.class), rs.getString("bank_name"),
                        rs.getDate("business_date").toLocalDate(), rs.getString("verification_status"),
                        rs.getString("fund_nature"), rs.getString("allocation_mode"), rs.getString("reversal_no"),
                        rs.getString("reversal_reason"), rs.getTimestamp("reversed_at") == null ? null :
                        rs.getTimestamp("reversed_at").toLocalDateTime()), paymentId);
        if (headers.isEmpty()) throw new BusinessException(supplier ? "供应商付款不存在" : "客户收款不存在");
        String rowSql = supplier ? """
                select a.*,coalesce(po.order_no,i.order_no,'') target_no
                from supplier_payment_allocation a
                left join purchase_order po on a.target_type='PURCHASE_ORDER' and po.id=a.target_id
                left join inbound_order i on a.target_type='INBOUND' and i.id=a.target_id
                where a.supplier_payment_id=? order by a.id
                """ : """
                select a.*,coalesce(so.order_no,o.order_no,'') target_no
                from payment_allocation a
                left join sales_order so on a.target_type='SALES_ORDER' and so.id=a.target_id
                left join outbound_order o on a.target_type='OUTBOUND' and o.id=a.target_id
                where a.payment_id=? order by a.id
                """;
        List<PaymentAllocationDtos.AllocationRow> allocations = jdbcTemplate.query(rowSql,
                (rs, rowNum) -> new PaymentAllocationDtos.AllocationRow(rs.getLong("id"),
                        rs.getString("allocation_no"), rs.getString("target_type"), rs.getLong("target_id"),
                        rs.getString("target_no"), rs.getBigDecimal("allocated_amount"), rs.getString("status"),
                        rs.getString("capture_source"), rs.getString("release_reason"),
                        rs.getTimestamp("released_at") == null ? null : rs.getTimestamp("released_at").toLocalDateTime(),
                        rs.getString("reversal_reason"),
                        rs.getTimestamp("reversed_at") == null ? null : rs.getTimestamp("reversed_at").toLocalDateTime(),
                        rs.getTimestamp("created_at").toLocalDateTime()), paymentId);
        String eventSql = supplier ? """
                select id,allocation_id,action,from_status,to_status,amount,business_date,reason,
                       operator_id,operator_name,created_at from supplier_payment_allocation_event
                where supplier_payment_id=? order by id
                """ : """
                select id,allocation_id,action,from_status,to_status,amount,business_date,reason,
                       operator_id,operator_name,created_at from payment_allocation_event
                where payment_id=? order by id
                """;
        List<PaymentAllocationDtos.EventRow> events = jdbcTemplate.query(eventSql,
                (rs, rowNum) -> new PaymentAllocationDtos.EventRow(rs.getLong("id"), rs.getLong("allocation_id"),
                        rs.getString("action"), rs.getString("from_status"), rs.getString("to_status"),
                        rs.getBigDecimal("amount"), rs.getDate("business_date").toLocalDate(), rs.getString("reason"),
                        rs.getLong("operator_id"), rs.getString("operator_name"),
                        rs.getTimestamp("created_at").toLocalDateTime()), paymentId);
        AllocationDetailHeader h = headers.get(0);
        return new PaymentAllocationDtos.Detail(h.id(), h.paymentNo(), supplier ? "SUPPLIER" : "CUSTOMER",
                h.partnerId(), h.partnerName(), h.amount(), h.allocatedAmount(), h.unallocatedAmount(),
                h.paymentMethod(), h.bankAccountId(), h.bankAccountName(), h.businessDate(), h.status(),
                h.fundNature(), h.allocationMode(), h.reversalNo(), h.reversalReason(), h.reversedAt(),
                allocations, events);
    }

    private boolean accountPaymentMethod(String paymentMethod) {
        return "ADVANCE".equals(paymentMethod) || "REWARD".equals(paymentMethod);
    }

    private String customerAccountType(String paymentMethod) {
        return "REWARD".equals(paymentMethod) ? "CUSTOMER_REWARD" : "CUSTOMER_ADVANCE";
    }

    private String supplierAccountType(String paymentMethod) {
        return "REWARD".equals(paymentMethod) ? "SUPPLIER_REWARD" : "SUPPLIER_PREPAY";
    }

    private String accountTypeName(String accountType) {
        return switch (accountType) {
            case "CUSTOMER_REWARD" -> "客户奖励金";
            case "SUPPLIER_PREPAY" -> "供应商预付款";
            case "SUPPLIER_REWARD" -> "供应商返利金";
            default -> "客户预收款";
        };
    }

    private void changePartnerAccount(Long partnerId, String accountType, BigDecimal amountDiff,
                                      String sourceType, Long sourceId, String memo) {
        jdbcTemplate.update("""
                insert into partner_account (partner_id, account_type, balance)
                values (?, ?, 0)
                on duplicate key update updated_at = updated_at
                """, partnerId, accountType);
        BigDecimal before = scalarDecimal("""
                select balance from partner_account
                where partner_id = %s and account_type = '%s'
                for update
                """.formatted(partnerId, accountType));
        BigDecimal after = before.add(amountDiff);
        if (after.compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException(accountTypeName(accountType) + "余额不足");
        }
        jdbcTemplate.update("""
                update partner_account
                set balance = ?, updated_at = current_timestamp
                where partner_id = ? and account_type = ?
                """, after, partnerId, accountType);
        insert("""
                insert into partner_account_log
                (partner_id, account_type, source_type, source_id, amount, amount_before, amount_after)
                values (?, ?, ?, ?, ?, ?, ?)
                """, partnerId, accountType, sourceType, sourceId == null ? 0L : sourceId, amountDiff, before, after);
    }

    private void changeStockBalance(Long productId, Long warehouseId, BigDecimal quantityDiff, BigDecimal amountDiff) {
        jdbcTemplate.update("""
                insert into stock_balance (product_id, warehouse_id, quantity_available, amount_cost)
                values (?, ?, ?, ?)
                on duplicate key update
                  quantity_available = quantity_available + values(quantity_available),
                  amount_cost = amount_cost + values(amount_cost)
                """, productId, warehouseId, quantityDiff, amountDiff);
    }

    private void insertInventoryMovement(String prefix, String type, Long productId, Long warehouseId, Long batchId,
                                         BigDecimal quantity, BigDecimal unitPrice, BigDecimal amount,
                                         String refType, Long refId, String memo) {
        insert("""
                insert into inventory_movement
                (movement_no, movement_type, product_id, warehouse_id, batch_id, quantity, unit_price, amount, ref_type, ref_id, memo)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, nextNo(prefix), type, productId, warehouseId, batchId, quantity, unitPrice, amount, refType, refId, memo);
    }

    private void insertReceivableMovement(String prefix, Long customerId, String refType, Long refId,
                                          String direction, BigDecimal amount, String memo) {
        BigDecimal current = customerReceivableBalance(customerId);
        insert("""
                insert into receivable_movement
                (movement_no, customer_id, ref_type, ref_id, direction, amount, balance_after, memo)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                """, nextNo(prefix), customerId, refType, refId, direction, amount, current, memo);
    }

    private BigDecimal totalReceivableBalance() {
        return scalarDecimal("""
                select coalesce(sum(unpaid_amount), 0) from (
                  select greatest(receivable_amount - received_amount - adjusted_amount, 0) unpaid_amount
                  from sales_order
                  where status not in ('DRAFT', 'CANCELLED')
                  union all
                  select greatest(receivable_amount - paid_amount, 0) unpaid_amount
                  from outbound_order
                  where sales_order_id = 0 and status <> 'CANCELLED'
                ) t
                """);
    }

    private BigDecimal customerReceivableBalance(Long customerId) {
        return scalarDecimal("""
                select coalesce(sum(unpaid_amount), 0) from (
                  select greatest(receivable_amount - received_amount - adjusted_amount, 0) unpaid_amount
                  from sales_order
                  where customer_id = %s and status not in ('DRAFT', 'CANCELLED')
                  union all
                  select greatest(receivable_amount - paid_amount, 0) unpaid_amount
                  from outbound_order
                  where customer_id = %s and sales_order_id = 0 and status <> 'CANCELLED'
                ) t
                """.formatted(customerId, customerId));
    }

    private void insertPayableMovement(String prefix, Long supplierId, String refType, Long refId,
                                       String direction, BigDecimal amount, String memo) {
        BigDecimal current = scalarDecimal("""
                select coalesce(sum(unpaid_amount), 0) from (
                  select greatest(
                    (case when o.source_type='DIRECT_TRADE'
                            then coalesce(d.purchase_payable_amount,0)
                          else o.total_amount end)-o.paid_amount-coalesce(r.returned_amount,0),0
                  ) unpaid_amount
                  from purchase_order o
                  left join direct_trade_order d on d.purchase_order_id=o.id
                  left join (select purchase_order_id,sum(payable_reduction_amount) returned_amount from purchase_return_order
                    where status='CONFIRMED' group by purchase_order_id) r on r.purchase_order_id=o.id
                  where o.supplier_id = %s and o.status not in ('DRAFT', 'CANCELLED')
                  union all
                  select greatest(o.payable_amount-o.paid_amount-coalesce(r.returned_amount,0),0) unpaid_amount
                  from inbound_order o
                  left join (select inbound_id,sum(payable_reduction_amount) returned_amount from purchase_return_order
                    where status='CONFIRMED' group by inbound_id) r on r.inbound_id=o.id
                  where o.supplier_id = %s and o.purchase_order_id = 0 and o.status = 'STOCKED'
                ) t
                """.formatted(supplierId, supplierId));
        insert("""
                insert into payable_movement
                (movement_no, supplier_id, ref_type, ref_id, direction, amount, balance_after, memo)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                """, nextNo(prefix), supplierId, refType, refId, direction, amount, current, memo);
    }

    public List<OrderDtos.InboundAuditLog> inboundAuditLogs(Long inboundId) {
        inboundDetail(inboundId);
        return jdbcTemplate.query("""
                select l.id,l.inbound_id,l.action,l.reason,l.operator_id,l.operator_name,l.snapshot_id,
                       coalesce(s.round_no,0) review_round,l.created_at
                from inbound_audit_log l
                left join inbound_review_snapshot s on s.id=l.snapshot_id
                where l.inbound_id=? order by l.created_at desc,l.id desc
                """, inboundAuditLogMapper(), inboundId);
    }

    public OrderDtos.InboundAuditLogDetail inboundAuditLogDetail(Long inboundId, Long auditLogId) {
        inboundDetail(inboundId);
        List<OrderDtos.InboundAuditLog> logs = jdbcTemplate.query("""
                select l.id,l.inbound_id,l.action,l.reason,l.operator_id,l.operator_name,l.snapshot_id,
                       coalesce(s.round_no,0) review_round,l.created_at
                from inbound_audit_log l
                left join inbound_review_snapshot s on s.id=l.snapshot_id
                where l.id=? and l.inbound_id=?
                """, inboundAuditLogMapper(), auditLogId, inboundId);
        if (logs.isEmpty()) throw new BusinessException("审核记录不存在");
        OrderDtos.InboundAuditLog log = logs.get(0);
        OrderDtos.InboundReviewSnapshot snapshot = log.snapshotId() > 0
                ? inboundReviewSnapshot(inboundId, log.snapshotId()) : null;
        OrderDtos.InboundReviewSnapshot previousSnapshot = null;
        if (snapshot != null && snapshot.roundNo() > 1) {
            Long previousSnapshotId = jdbcTemplate.queryForObject("""
                    select coalesce(max(id),0) from inbound_review_snapshot
                    where inbound_id=? and round_no<?
                    """, Long.class, inboundId, snapshot.roundNo());
            if (previousSnapshotId != null && previousSnapshotId > 0) {
                previousSnapshot = inboundReviewSnapshot(inboundId, previousSnapshotId);
            }
        }
        return new OrderDtos.InboundAuditLogDetail(log, snapshot, previousSnapshot);
    }

    public InboundAttachmentService.AttachmentContent inboundReviewSnapshotAttachmentContent(
            Long inboundId, Long snapshotId, Long attachmentId) {
        List<SnapshotAttachmentContent> rows = jdbcTemplate.query("""
                select a.file_name,a.content_type,a.storage_path,a.file_content
                from inbound_review_snapshot_attachment a
                join inbound_review_snapshot s on s.id=a.snapshot_id
                where a.id=? and a.snapshot_id=? and s.inbound_id=?
                """, (rs, rowNum) -> new SnapshotAttachmentContent(
                rs.getString("file_name"), rs.getString("content_type"), rs.getString("storage_path"),
                rs.getBytes("file_content")),
                attachmentId, snapshotId, inboundId);
        if (rows.isEmpty()) throw new BusinessException("审核快照影像不存在");
        SnapshotAttachmentContent stored = rows.get(0);
        byte[] bytes = StringUtils.hasText(stored.storagePath())
                ? fileStorageService.read(stored.storagePath()) : stored.legacyContent();
        if (bytes == null) throw new BusinessException("审核快照影像文件不存在");
        return new InboundAttachmentService.AttachmentContent(stored.fileName(), stored.contentType(), bytes);
    }

    private OrderDtos.InboundReviewSnapshot inboundReviewSnapshot(Long inboundId, Long snapshotId) {
        List<OrderDtos.InboundReviewSnapshot> headers = jdbcTemplate.query("""
                select * from inbound_review_snapshot where id=? and inbound_id=?
                """, (rs, rowNum) -> new OrderDtos.InboundReviewSnapshot(
                rs.getLong("id"), rs.getLong("inbound_id"), rs.getInt("round_no"),
                rs.getString("order_no"), rs.getLong("purchase_order_id"), rs.getString("purchase_order_no"),
                rs.getLong("supplier_id"), rs.getString("supplier_name"), rs.getLong("warehouse_id"),
                rs.getString("warehouse_name"), rs.getString("source_type"), rs.getString("source_note"),
                rs.getDate("business_date").toLocalDate(),
                rs.getDate("send_date") == null ? null : rs.getDate("send_date").toLocalDate(),
                rs.getString("send_number"), rs.getBigDecimal("original_amount"),
                rs.getBigDecimal("discount_rate"), rs.getBigDecimal("adjustment_amount"),
                rs.getBigDecimal("total_quantity"), rs.getString("quantity_summary"),
                rs.getBigDecimal("total_amount"), rs.getString("memo"), rs.getString("snapshot_hash"),
                rs.getString("capture_source"), rs.getString("submitted_by_name"),
                rs.getTimestamp("created_at").toLocalDateTime(), List.of(), List.of()), snapshotId, inboundId);
        if (headers.isEmpty()) throw new BusinessException("审核快照不存在");
        List<OrderDtos.InboundReviewSnapshotItem> items = jdbcTemplate.query("""
                select * from inbound_review_snapshot_item where snapshot_id=? order by line_no
                """, (rs, rowNum) -> new OrderDtos.InboundReviewSnapshotItem(
                rs.getLong("id"), rs.getInt("line_no"), rs.getLong("product_id"), rs.getString("sku"),
                rs.getString("product_name"), rs.getString("spec"), rs.getString("batch_no"),
                rs.getString("serial_number"), rs.getBigDecimal("requested_quantity"),
                rs.getString("requested_unit"), rs.getBigDecimal("quantity"), rs.getString("base_unit"),
                rs.getBigDecimal("package_quantity"), rs.getString("package_unit"), rs.getString("price_unit"),
                rs.getBigDecimal("pricing_quantity"), rs.getString("package_barcode"),
                rs.getBigDecimal("unit_cost"), rs.getBigDecimal("amount"),
                rs.getDate("production_date") == null ? null : rs.getDate("production_date").toLocalDate(),
                rs.getString("shelf_life_mode"),
                rs.getObject("shelf_life_value") == null ? null : rs.getInt("shelf_life_value"),
                rs.getString("shelf_life_unit"),
                rs.getDate("expiration_date") == null ? null : rs.getDate("expiration_date").toLocalDate(),
                rs.getString("packaging_plan_json")), snapshotId);
        List<OrderDtos.InboundReviewSnapshotAttachment> attachments = jdbcTemplate.query("""
                select id,original_attachment_id,file_name,content_type,file_size,sha256,original_uploaded_at
                from inbound_review_snapshot_attachment where snapshot_id=? order by id
                """, (rs, rowNum) -> new OrderDtos.InboundReviewSnapshotAttachment(
                rs.getLong("id"), rs.getLong("original_attachment_id"), rs.getString("file_name"),
                rs.getString("content_type"), rs.getLong("file_size"), rs.getString("sha256"),
                rs.getTimestamp("original_uploaded_at") == null ? null
                        : rs.getTimestamp("original_uploaded_at").toLocalDateTime()), snapshotId);
        OrderDtos.InboundReviewSnapshot header = headers.get(0);
        return new OrderDtos.InboundReviewSnapshot(
                header.id(), header.inboundId(), header.roundNo(), header.orderNo(), header.purchaseOrderId(),
                header.purchaseOrderNo(), header.supplierId(), header.supplierName(), header.warehouseId(),
                header.warehouseName(), header.sourceType(), header.sourceNote(), header.businessDate(),
                header.sendDate(), header.sendNumber(), header.originalAmount(), header.discountRate(),
                header.adjustmentAmount(), header.totalQuantity(), header.quantitySummary(), header.totalAmount(),
                header.memo(), header.snapshotHash(), header.captureSource(), header.submittedByName(),
                header.createdAt(), items, attachments);
    }

    private RowMapper<OrderDtos.InboundAuditLog> inboundAuditLogMapper() {
        return (rs, rowNum) -> new OrderDtos.InboundAuditLog(
                rs.getLong("id"), rs.getLong("inbound_id"), rs.getString("action"),
                rs.getString("reason"), rs.getLong("operator_id"), rs.getString("operator_name"),
                rs.getLong("snapshot_id"), rs.getInt("review_round"),
                rs.getTimestamp("created_at").toLocalDateTime());
    }

    private Long ensureInboundReviewSnapshot(Long inboundId, String operatorName) {
        Long snapshotId = jdbcTemplate.queryForObject("""
                select coalesce(max(id),0) from inbound_review_snapshot where inbound_id=?
                """, Long.class, inboundId);
        if (snapshotId != null && snapshotId > 0) return snapshotId;
        return captureInboundReviewSnapshot(inboundId, operatorName, "LEGACY_ACTION");
    }

    private Long captureInboundReviewSnapshot(Long inboundId, String operatorName, String captureSource) {
        OrderDtos.InboundDetail detail = inboundDetail(inboundId);
        int roundNo = Objects.requireNonNullElse(jdbcTemplate.queryForObject("""
                select coalesce(max(round_no),0)+1 from inbound_review_snapshot where inbound_id=?
                """, Integer.class, inboundId), 1);
        List<Map<String, Object>> attachmentSignatures = jdbcTemplate.queryForList("""
                select id,file_name,content_type,file_size,sha256,uploaded_at
                from inbound_attachment where inbound_id=? order by id
                """, inboundId);
        String snapshotHash;
        try {
            Map<String, Object> signature = new LinkedHashMap<>();
            signature.put("roundNo", roundNo);
            signature.put("detail", detail);
            signature.put("attachments", attachmentSignatures);
            snapshotHash = sha256(objectMapper.writeValueAsString(signature));
        } catch (Exception ex) {
            throw new BusinessException("入库审核快照生成失败");
        }
        String name = normalizedOperatorName(operatorName);
        String quantitySummary = detail.items().stream()
                .map(item -> decimalText(item.requestedQuantity() == null ? item.quantity() : item.requestedQuantity())
                        + (StringUtils.hasText(item.requestedUnit()) ? item.requestedUnit() : item.unit()))
                .reduce((left, right) -> left + " / " + right).orElse("");
        Long snapshotId = insert("""
                insert into inbound_review_snapshot
                (inbound_id,round_no,order_no,purchase_order_id,purchase_order_no,supplier_id,supplier_name,
                 warehouse_id,warehouse_name,source_type,source_note,business_date,send_date,send_number,
                 original_amount,discount_rate,adjustment_amount,total_quantity,quantity_summary,total_amount,memo,
                 snapshot_hash,capture_source,submitted_by_name)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, inboundId, roundNo, detail.orderNo(), detail.purchaseOrderId(), blank(detail.purchaseOrderNo()),
                detail.supplierId(), detail.supplierName(), detail.warehouseId(), detail.warehouseName(),
                detail.sourceType(), blank(detail.sourceNote()), Date.valueOf(detail.businessDate()),
                detail.sendDate() == null ? null : Date.valueOf(detail.sendDate()), blank(detail.sendNumber()),
                detail.originalAmount(), detail.discountRate(), detail.adjustmentAmount(), detail.totalQuantity(),
                quantitySummary, detail.totalAmount(), blank(detail.memo()), snapshotHash, captureSource, name);
        int lineNo = 1;
        for (OrderDtos.InboundItemDetail item : detail.items()) {
            insert("""
                    insert into inbound_review_snapshot_item
                    (snapshot_id,line_no,product_id,sku,product_name,spec,batch_no,serial_number,
                     requested_quantity,requested_unit,quantity,base_unit,package_quantity,package_unit,
                     price_unit,pricing_quantity,package_barcode,unit_cost,amount,production_date,
                     shelf_life_mode,shelf_life_value,shelf_life_unit,expiration_date,packaging_plan_json)
                    values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, snapshotId, lineNo++, item.productId(), blank(item.sku()), blank(item.productName()),
                    blank(item.spec()), blank(item.batchNo()), blank(item.serialNumber()),
                    item.requestedQuantity() == null ? item.quantity() : item.requestedQuantity(),
                    StringUtils.hasText(item.requestedUnit()) ? item.requestedUnit() : item.unit(),
                    item.quantity(), StringUtils.hasText(item.baseUnit()) ? item.baseUnit() : item.unit(),
                    nvl(item.packageQuantity()), blank(item.packageUnit()), blank(item.priceUnit()),
                    item.pricingQuantity() == null ? item.quantity() : item.pricingQuantity(),
                    blank(item.packageBarcode()), item.unitCost(), item.amount(),
                    item.productionDate() == null ? null : Date.valueOf(item.productionDate()),
                    blank(item.shelfLifeMode()), item.shelfLifeValue(), blank(item.shelfLifeUnit()),
                    item.expirationDate() == null ? null : Date.valueOf(item.expirationDate()),
                    blank(item.packagingPlanJson()));
        }
        List<SnapshotAttachmentSource> attachmentSources = jdbcTemplate.query("""
                select id,file_name,content_type,file_size,sha256,storage_path,file_content,uploaded_at
                from inbound_attachment where inbound_id=? order by id
                """, (rs, rowNum) -> new SnapshotAttachmentSource(
                rs.getLong("id"), rs.getString("file_name"), rs.getString("content_type"),
                rs.getLong("file_size"), rs.getString("sha256"), rs.getString("storage_path"),
                rs.getBytes("file_content"), rs.getTimestamp("uploaded_at")), inboundId);
        for (SnapshotAttachmentSource source : attachmentSources) {
            String directory = "inbound-review-snapshots/" + snapshotId;
            String extension = fileStorageService.extensionForContentType(source.contentType());
            String storagePath;
            if (StringUtils.hasText(source.storagePath())) {
                storagePath = fileStorageService.copy(source.storagePath(), directory, extension);
            } else if (source.legacyContent() != null) {
                storagePath = fileStorageService.store(source.legacyContent(), directory, extension);
            } else {
                throw new BusinessException("发货单图片文件不存在，无法生成审核快照");
            }
            insert("""
                    insert into inbound_review_snapshot_attachment
                    (snapshot_id,original_attachment_id,file_name,content_type,file_size,sha256,
                     storage_path,file_content,original_uploaded_at)
                    values (?,?,?,?,?,?,?,null,?)
                    """, snapshotId, source.id(), source.fileName(), source.contentType(), source.fileSize(),
                    source.sha256(), storagePath, source.uploadedAt());
        }
        return snapshotId;
    }

    private String decimalText(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).stripTrailingZeros().toPlainString();
    }

    private String normalizedOperatorName(String operatorName) {
        String name = blank(operatorName).trim();
        if (!StringUtils.hasText(name)) name = "系统用户";
        return name.length() > 100 ? name.substring(0, 100) : name;
    }

    private void insertInboundAuditLog(Long inboundId, String action, String reason, String operatorName,
                                       Long snapshotId) {
        String name = normalizedOperatorName(operatorName);
        insert("""
                insert into inbound_audit_log (inbound_id,action,reason,operator_id,operator_name,snapshot_id)
                values (?,?,?,0,?,?)
                """, inboundId, action, blank(reason), name, snapshotId == null ? 0L : snapshotId);
    }

    private void updatePurchaseReceived(Long purchaseOrderId, List<InboundAuditItem> items) {
        for (InboundAuditItem item : items) {
            Long purchaseOrderItemId;
            if (item.purchaseOrderItemId() != null && item.purchaseOrderItemId() > 0) {
                purchaseOrderItemId = item.purchaseOrderItemId();
            } else {
                List<Long> candidates = jdbcTemplate.query("""
                        select id from purchase_order_item
                        where purchase_order_id=? and product_id=? and status='ENABLED' for update
                        """, (rs, rowNum) -> rs.getLong(1), purchaseOrderId, item.productId());
                if (candidates.size() != 1) {
                    throw new BusinessException("采购订单存在相同商品多行，必须指定对应采购明细后再入库");
                }
                purchaseOrderItemId = candidates.get(0);
            }

            List<Map<String, Object>> orderItems = jdbcTemplate.queryForList("""
                    select quantity,received_quantity from purchase_order_item
                    where id=? and purchase_order_id=? and product_id=? and status='ENABLED' for update
                    """, purchaseOrderItemId, purchaseOrderId, item.productId());
            if (orderItems.isEmpty()) throw new BusinessException("采购订单明细不存在或商品不一致");
            BigDecimal orderedBase = decimalValue(orderItems.get(0).get("quantity"));
            BigDecimal receivedBase = decimalValue(orderItems.get(0).get("received_quantity"));
            if (receivedBase.add(item.quantity()).compareTo(orderedBase) > 0) {
                throw new BusinessException("本次入库超过采购订单剩余数量；请先变更采购订单后再审核入库");
            }
            BigDecimal requestedEquivalent = purchaseOrderRequestedEquivalent(
                    purchaseOrderItemId, purchaseOrderId, item.quantity());
            jdbcTemplate.update("""
                    update purchase_order_item set received_quantity=received_quantity+?,
                      received_requested_quantity=received_requested_quantity+?
                    where id=? and purchase_order_id=? and product_id=?
                    """, item.quantity(), requestedEquivalent, purchaseOrderItemId, purchaseOrderId, item.productId());
        }
        jdbcTemplate.update("""
                update purchase_order o
                set received_quantity = (
                      select coalesce(sum(received_quantity), 0)
                      from purchase_order_item
                      where purchase_order_id = o.id
                    ),
                    status = case
                      when not exists(select 1 from purchase_order_item i where i.purchase_order_id=o.id
                        and i.received_requested_quantity<i.requested_quantity) then 'RECEIVED'
                      when exists(select 1 from purchase_order_item i where i.purchase_order_id=o.id
                        and i.received_requested_quantity>0) then 'PARTIAL_RECEIVED'
                      else status
                    end
                where o.id = ?
                """, purchaseOrderId);
    }

    private long resolveInboundPurchaseItem(Long purchaseOrderId, InboundCreateRequest.Item item) {
        if (purchaseOrderId == null || purchaseOrderId <= 0) {
            if (item.purchaseOrderItemId() != null && item.purchaseOrderItemId() > 0) {
                throw new BusinessException("无采购订单入库不能指定采购明细");
            }
            return 0;
        }
        if (item.purchaseOrderItemId() != null && item.purchaseOrderItemId() > 0) {
            Integer matched = jdbcTemplate.queryForObject("""
                    select count(*) from purchase_order_item
                    where id=? and purchase_order_id=? and product_id=? and status='ENABLED'
                    """, Integer.class, item.purchaseOrderItemId(), purchaseOrderId, item.productId());
            if (matched == null || matched != 1) {
                throw new BusinessException("入库商品与所选采购明细不一致");
            }
            return item.purchaseOrderItemId();
        }
        Integer candidates = jdbcTemplate.queryForObject("""
                select count(*) from purchase_order_item
                where purchase_order_id=? and product_id=? and status='ENABLED'
                """, Integer.class, purchaseOrderId, item.productId());
        if (candidates != null && candidates > 1) {
            throw new BusinessException("采购订单存在相同商品多行，请选择具体采购明细");
        }
        if (candidates == null || candidates == 0) throw new BusinessException("采购订单中没有对应商品明细");
        return jdbcTemplate.queryForObject("""
                select id from purchase_order_item where purchase_order_id=? and product_id=? and status='ENABLED' limit 1
                """, Long.class, purchaseOrderId, item.productId());
    }

    private PackagingDtos.Plan inboundPackagingPlan(Long purchaseOrderId, long purchaseOrderItemId,
                                                     InboundCreateRequest.Item item,
                                                     String priceUnit) {
        PackagingDtos.PlanRequest request = new PackagingDtos.PlanRequest(
                item.quantity(), item.quantityUnit(), item.actualBaseQuantity(), priceUnit, "INBOUND",
                item.expectedEffectiveRevisionId(), item.expectedPackagingSnapshotHash());
        if (purchaseOrderItemId > 0) {
            List<String> snapshots = jdbcTemplate.query("""
                    select packaging_plan_json from purchase_order_item
                    where id=? and purchase_order_id=? and product_id=?
                    """, (rs, rowNum) -> rs.getString(1), purchaseOrderItemId, purchaseOrderId, item.productId());
            if (snapshots.isEmpty()) throw new BusinessException("采购明细不存在或商品不一致");
            return packagingService.planFromSnapshot(snapshots.get(0), request);
        }
        return packagingService.plan(item.productId(), request);
    }

    private PackagingDtos.Plan outboundPackagingPlan(Long salesOrderId, long salesOrderItemId,
                                                      OutboundCreateRequest.Item item, Long productId,
                                                      String priceUnit) {
        PackagingDtos.PlanRequest request = new PackagingDtos.PlanRequest(
                item.quantity(), item.quantityUnit(), item.actualBaseQuantity(), priceUnit, "OUTBOUND",
                item.expectedEffectiveRevisionId(), item.expectedPackagingSnapshotHash());
        if (salesOrderItemId > 0) {
            List<String> snapshots = jdbcTemplate.query("""
                    select packaging_plan_json from sales_order_item
                    where id=? and sales_order_id=? and product_id=?
                    """, (rs, rowNum) -> rs.getString(1), salesOrderItemId, salesOrderId, productId);
            if (snapshots.isEmpty()) throw new BusinessException("销售明细不存在或商品不一致");
            return packagingService.planFromSnapshot(snapshots.get(0), request);
        }
        return packagingService.planForBatch(item.batchId(), request);
    }

    private void validateOutboundSalesItemPrice(Long salesOrderId, long salesOrderItemId,
                                                String priceUnit, BigDecimal unitPrice) {
        if (salesOrderId == null || salesOrderId <= 0 || salesOrderItemId <= 0) return;
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select price_unit,unit_price from sales_order_item
                where id=? and sales_order_id=? and status='ENABLED'
                """, salesOrderItemId, salesOrderId);
        if (rows.isEmpty()) throw new BusinessException("销售订单明细不存在或已停用");
        String orderedPriceUnit = String.valueOf(rows.get(0).get("price_unit"));
        BigDecimal orderedUnitPrice = decimalValue(rows.get(0).get("unit_price"));
        if (!Objects.equals(orderedPriceUnit, priceUnit)
                || orderedUnitPrice.compareTo(nvl(unitPrice)) != 0) {
            throw new BusinessException("关联出库必须沿用销售订单已确认的计价单位和销售单价；请先走销售订单变更");
        }
    }

    private void validateOutboundSource(Long salesOrderId, Long customerId, Long warehouseId) {
        if (salesOrderId == null || salesOrderId <= 0) return;
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select customer_id,fulfillment_warehouse_id,status from sales_order where id=?", salesOrderId);
        if (rows.isEmpty()) throw new BusinessException("销售订单不存在");
        if (!Objects.equals(((Number) rows.get(0).get("customer_id")).longValue(), customerId)) {
            throw new BusinessException("出库客户与销售订单客户不一致");
        }
        String status = String.valueOf(rows.get(0).get("status"));
        if ("DRAFT".equals(status)) throw new BusinessException("销售订单尚未确认销售，不能出库");
        if ("CANCELLED".equals(status)) throw new BusinessException("已作废的销售订单不能出库");
        long promisedWarehouseId = ((Number) rows.get(0).get("fulfillment_warehouse_id")).longValue();
        if (promisedWarehouseId <= 0) throw new BusinessException("销售订单尚未分配履约仓库，不能出库");
        if (!Objects.equals(promisedWarehouseId, warehouseId)) {
            throw new BusinessException("出库仓库与销售订单履约仓库不一致；请先按订单变更流程调整履约仓库");
        }
    }

    private OutboundLifecycleRow lockOutboundLifecycle(Long outboundId) {
        List<OutboundLifecycleRow> rows = jdbcTemplate.query("""
                select id,order_no,sales_order_id,customer_id,warehouse_id,status,settlement_status,
                       total_amount,receivable_amount,paid_amount,shipment_payment_policy,shipment_control_amount,
                       allow_over_shipment,over_shipment_reason
                from outbound_order where id=? for update
                """, (rs, rowNum) -> new OutboundLifecycleRow(
                rs.getLong("id"), rs.getString("order_no"), rs.getLong("sales_order_id"),
                rs.getLong("customer_id"), rs.getLong("warehouse_id"), rs.getString("status"),
                rs.getString("settlement_status"), rs.getBigDecimal("total_amount"),
                rs.getBigDecimal("receivable_amount"), rs.getBigDecimal("paid_amount"),
                rs.getString("shipment_payment_policy"), rs.getBigDecimal("shipment_control_amount"),
                rs.getInt("allow_over_shipment") == 1, rs.getString("over_shipment_reason")
        ), outboundId);
        if (rows.isEmpty()) throw new BusinessException("出库单不存在");
        return rows.get(0);
    }

    private List<OutboundLifecycleItemRow> outboundLifecycleItems(Long outboundId) {
        return jdbcTemplate.query("""
                select id,outbound_id,sales_order_item_id,product_id,batch_id,requested_quantity,requested_unit,
                       quantity,base_unit,package_quantity,package_unit,price_unit,pricing_quantity,
                       packaging_plan_json,selected_package_instance_ids_json,unit_price,amount,returned_quantity,
                       price_includes_tax,tax_rate,amount_excluding_tax,tax_amount,settlement_total_amount,tax_rounding_difference,
                       inventory_unit_cost,inventory_amount_cost
                from outbound_item where outbound_id=? order by id
                """, (rs, rowNum) -> new OutboundLifecycleItemRow(
                rs.getLong("id"), rs.getLong("outbound_id"), rs.getLong("sales_order_item_id"),
                rs.getLong("product_id"), rs.getLong("batch_id"), rs.getBigDecimal("requested_quantity"),
                rs.getString("requested_unit"), rs.getBigDecimal("quantity"), rs.getString("base_unit"),
                rs.getBigDecimal("package_quantity"), rs.getString("package_unit"), rs.getString("price_unit"),
                rs.getBigDecimal("pricing_quantity"), rs.getString("packaging_plan_json"),
                rs.getString("selected_package_instance_ids_json"), rs.getBigDecimal("unit_price"),
                rs.getBigDecimal("amount"), rs.getBigDecimal("returned_quantity"),
                rs.getInt("price_includes_tax") == 1, rs.getBigDecimal("tax_rate"),
                rs.getBigDecimal("amount_excluding_tax"), rs.getBigDecimal("tax_amount"),
                rs.getBigDecimal("settlement_total_amount"), rs.getBigDecimal("tax_rounding_difference"),
                rs.getBigDecimal("inventory_unit_cost"), rs.getBigDecimal("inventory_amount_cost")
        ), outboundId);
    }

    private PackagingDtos.Plan storedOutboundPlan(OutboundLifecycleItemRow item) {
        return packagingService.planFromSnapshot(item.packagingPlanJson(), new PackagingDtos.PlanRequest(
                item.requestedQuantity(), item.requestedUnit(), item.quantity(), item.priceUnit(),
                "OUTBOUND", null, null));
    }

    private void validateOutboundRemaining(OutboundLifecycleRow outbound, OutboundLifecycleItemRow item) {
        if (item.salesOrderItemId() <= 0) return;
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select quantity,shipped_quantity from sales_order_item
                where id=? and sales_order_id=? for update
                """, item.salesOrderItemId(), outbound.salesOrderId());
        if (rows.isEmpty()) throw new BusinessException("销售订单明细不存在");
        BigDecimal ordered = decimalValue(rows.get(0).get("quantity"));
        BigDecimal shipped = decimalValue(rows.get(0).get("shipped_quantity"));
        if (shipped.add(item.quantity()).compareTo(ordered) > 0) {
            if (!outbound.allowOverShipment() || !StringUtils.hasText(outbound.overShipmentReason())) {
                throw new BusinessException("本次出库超过销售订单剩余数量；确需超发时必须勾选允许超发并填写原因");
            }
            assertOutboundOverShipmentPermission();
        }
    }

    private void validateOutboundDraftCapacity(Long salesOrderId, Map<Long, BigDecimal> baseQuantityBySalesItem,
                                               Long excludedOutboundId, boolean allowOverShipment,
                                               String overShipmentReason) {
        if (salesOrderId == null || salesOrderId <= 0 || baseQuantityBySalesItem.isEmpty()) return;
        if (allowOverShipment) {
            if (!StringUtils.hasText(overShipmentReason)) throw new BusinessException("允许超发时必须填写超发原因");
            assertOutboundOverShipmentPermission();
        }
        for (Map.Entry<Long, BigDecimal> entry : baseQuantityBySalesItem.entrySet()) {
            List<Map<String, Object>> orderItems = jdbcTemplate.queryForList("""
                    select quantity,shipped_quantity from sales_order_item
                    where id=? and sales_order_id=? for update
                    """, entry.getKey(), salesOrderId);
            if (orderItems.isEmpty()) throw new BusinessException("销售订单明细不存在");
            BigDecimal ordered = decimalValue(orderItems.get(0).get("quantity"));
            BigDecimal shipped = decimalValue(orderItems.get(0).get("shipped_quantity"));
            BigDecimal reserved = jdbcTemplate.queryForObject("""
                    select coalesce(sum(oi.quantity),0)
                    from outbound_item oi
                    join outbound_order o on o.id=oi.outbound_id
                    where oi.sales_order_item_id=? and o.sales_order_id=? and o.status='DRAFT' and o.id<>?
                    """, BigDecimal.class, entry.getKey(), salesOrderId, excludedOutboundId == null ? 0L : excludedOutboundId);
            BigDecimal remaining = ordered.subtract(shipped).subtract(nvl(reserved)).max(BigDecimal.ZERO);
            if (entry.getValue().compareTo(remaining) > 0 && !allowOverShipment) {
                throw new BusinessException("销售订单待发数量不足：其他出库草稿已占用部分数量；请先作废或修改重复草稿");
            }
        }
    }

    private BigDecimal salesOrderRequestedEquivalent(Long salesOrderItemId, Long salesOrderId,
                                                       BigDecimal baseQuantity) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select requested_quantity,quantity from sales_order_item
                where id=? and sales_order_id=? for update
                """, salesOrderItemId, salesOrderId);
        if (rows.isEmpty()) throw new BusinessException("销售订单明细不存在");
        return requestedEquivalent(rows.get(0), baseQuantity, "销售订单");
    }

    private BigDecimal purchaseOrderRequestedEquivalent(Long purchaseOrderItemId, Long purchaseOrderId,
                                                          BigDecimal baseQuantity) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select requested_quantity,quantity from purchase_order_item
                where id=? and purchase_order_id=? for update
                """, purchaseOrderItemId, purchaseOrderId);
        if (rows.isEmpty()) throw new BusinessException("采购订单明细不存在");
        return requestedEquivalent(rows.get(0), baseQuantity, "采购订单");
    }

    private BigDecimal requestedEquivalent(Map<String, Object> orderItem, BigDecimal baseQuantity,
                                           String documentName) {
        BigDecimal orderedBaseQuantity = decimalValue(orderItem.get("quantity"));
        if (orderedBaseQuantity.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(documentName + "基础数量无效，不能换算履约进度");
        }
        return nvl(baseQuantity).multiply(decimalValue(orderItem.get("requested_quantity")))
                .divide(orderedBaseQuantity, 6, RoundingMode.HALF_UP);
    }

    private BigDecimal batchDraftReservedQuantity(Long batchId, Long excludedOutboundId) {
        BigDecimal value = jdbcTemplate.queryForObject("""
                select coalesce(sum(quantity),0) from outbound_allocation
                where inventory_batch_id=? and status='DRAFT' and outbound_id<>?
                """, BigDecimal.class, batchId, excludedOutboundId == null ? 0L : excludedOutboundId);
        return value == null ? BigDecimal.ZERO : value;
    }

    private void assertOutboundOverShipmentPermission() {
        if (!SecurityContext.require().hasPermission("outbound.over-shipment")) {
            throw new BusinessException("没有“允许超订单发货”权限，请联系管理员");
        }
    }

    private void requireProductCostViewPermission() {
        SecurityContext.Principal actor = SecurityContext.require();
        if (!actor.hasPermission("product.cost.view")
                && !actor.hasPermission("product.supplier.offer.manage")) {
            throw new ForbiddenException("当前账号无权查看商品采购成本与供应商供货条款");
        }
    }

    private void postOutboundReceivable(OutboundLifecycleRow outbound, BigDecimal amount) {
        if (amount.compareTo(BigDecimal.ZERO) <= 0) return;
        jdbcTemplate.update("update outbound_order set receivable_amount=?,payment_status='UNPAID' where id=?",
                amount, outbound.id());
        if (outbound.salesOrderId() <= 0) {
            insertReceivableMovement("AR", outbound.customerId(), "OUTBOUND", outbound.id(), "DEBIT", amount,
                    "无订单销售出库产生应收 " + outbound.orderNo());
            return;
        }
        String basis = jdbcTemplate.queryForObject(
                "select receivable_basis from sales_order where id=? for update", String.class, outbound.salesOrderId());
        if ("ORDER_LEGACY".equals(basis)) return;
        jdbcTemplate.update("update sales_order set receivable_amount=receivable_amount+? where id=?",
                amount, outbound.salesOrderId());
        refreshSalesOrderPaymentStatus(outbound.salesOrderId());
        insertReceivableMovement("AR", outbound.customerId(), "OUTBOUND", outbound.id(), "DEBIT", amount,
                "销售出库产生应收 " + outbound.orderNo());
        applyReservedSalesOrderAdvance(outbound.salesOrderId());
    }

    private void applyReservedSalesOrderAdvance(Long salesOrderId) {
        SalesOrderPayRow order = lockSalesOrderForPay(salesOrderId);
        BigDecimal outstanding = salesOrderOutstanding(order);
        if (outstanding.compareTo(BigDecimal.ZERO) <= 0) return;
        List<AdvanceAllocationRow> allocations = jdbcTemplate.query("""
                select id,source_payment_id,amount,memo from sales_order_advance_allocation
                where sales_order_id=? and status='RESERVED' order by id for update
                """, (rs, rowNum) -> new AdvanceAllocationRow(
                rs.getLong("id"), rs.getLong("source_payment_id"), rs.getBigDecimal("amount"), rs.getString("memo")
        ), salesOrderId);
        BigDecimal reserved = allocations.stream().map(AdvanceAllocationRow::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal applyAmount = outstanding.min(reserved);
        if (applyAmount.compareTo(BigDecimal.ZERO) <= 0) return;
        Long paymentId = createPayment(new PaymentCreateRequest(
                order.customerId(), salesOrderId, null, null, applyAmount,
                "ADVANCE", null, LocalDate.now(), "出库形成应收后自动使用订单已占用预收款"));
        BigDecimal remaining = applyAmount;
        for (AdvanceAllocationRow allocation : allocations) {
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) break;
            BigDecimal consumed = allocation.amount().min(remaining);
            BigDecimal left = allocation.amount().subtract(consumed);
            jdbcTemplate.update("""
                    update sales_order_advance_allocation
                    set amount=?,status='APPLIED',applied_payment_id=?,updated_at=current_timestamp where id=?
                    """, consumed, paymentId, allocation.id());
            if (left.compareTo(BigDecimal.ZERO) > 0) {
                insert("""
                        insert into sales_order_advance_allocation
                        (sales_order_id,customer_id,source_payment_id,amount,status,memo)
                        values (?,?,?,?, 'RESERVED', ?)
                        """, salesOrderId, order.customerId(), allocation.sourcePaymentId(), left, allocation.memo());
            }
            remaining = remaining.subtract(consumed);
        }
    }

    private void applyReservedOutboundAdvance(OutboundLifecycleRow outbound, BigDecimal amount) {
        List<Map<String, Object>> allocations = jdbcTemplate.queryForList("""
                select id,amount,memo from outbound_advance_allocation
                where outbound_id=? and status='RESERVED' order by id for update
                """, outbound.id());
        BigDecimal reserved = allocations.stream().map(row -> decimalValue(row.get("amount")))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal applyAmount = nvl(amount).min(reserved);
        if (applyAmount.compareTo(BigDecimal.ZERO) <= 0) return;
        Long paymentId = createPayment(new PaymentCreateRequest(
                outbound.customerId(), null, outbound.id(), null, applyAmount,
                "ADVANCE", null, LocalDate.now(), "款到发货自动使用直接出库预收款 " + outbound.orderNo()));
        BigDecimal remaining = applyAmount;
        for (Map<String, Object> allocation : allocations) {
            Long allocationId = ((Number) allocation.get("id")).longValue();
            BigDecimal allocationAmount = decimalValue(allocation.get("amount"));
            BigDecimal consumed = allocationAmount.min(remaining);
            BigDecimal released = allocationAmount.subtract(consumed);
            if (consumed.compareTo(BigDecimal.ZERO) > 0) {
                jdbcTemplate.update("""
                        update outbound_advance_allocation
                        set amount=?,status='APPLIED',applied_payment_id=?,updated_at=current_timestamp where id=?
                        """, consumed, paymentId, allocationId);
            } else {
                jdbcTemplate.update("""
                        update outbound_advance_allocation
                        set status='RELEASED',memo=concat_ws('；',nullif(memo,''),'最终结算未使用'),updated_at=current_timestamp
                        where id=?
                        """, allocationId);
            }
            if (released.compareTo(BigDecimal.ZERO) > 0 && consumed.compareTo(BigDecimal.ZERO) > 0) {
                insert("""
                        insert into outbound_advance_allocation
                        (outbound_id,customer_id,amount,status,memo) values (?, ?, ?, 'RELEASED', ?)
                        """, outbound.id(), outbound.customerId(), released, "最终金额低于暂估最高金额，自动释放");
            }
            remaining = remaining.subtract(consumed);
        }
    }

    private void releaseReservedOutboundAdvance(Long outboundId, String reason) {
        jdbcTemplate.update("""
                update outbound_advance_allocation
                set status='RELEASED',memo=concat_ws('；',nullif(memo,''),?),updated_at=current_timestamp
                where outbound_id=? and status='RESERVED'
                """, reason, outboundId);
    }

    private boolean outboundReceivablePosted(OutboundLifecycleRow outbound) {
        if (outbound.salesOrderId() <= 0 || !"SETTLED".equals(outbound.settlementStatus())) return false;
        String basis = jdbcTemplate.queryForObject(
                "select receivable_basis from sales_order where id=?", String.class, outbound.salesOrderId());
        return !"ORDER_LEGACY".equals(basis);
    }

    private void reverseOutboundReceivable(OutboundLifecycleRow outbound) {
        if (!"SETTLED".equals(outbound.settlementStatus()) || outbound.totalAmount().compareTo(BigDecimal.ZERO) <= 0) return;
        if (outbound.salesOrderId() <= 0) {
            jdbcTemplate.update("update outbound_order set receivable_amount=0 where id=?", outbound.id());
            insertReceivableMovement("CX", outbound.customerId(), "OUTBOUND_REVERSAL", outbound.id(), "CREDIT",
                    outbound.totalAmount(), "反向出库冲回应收 " + outbound.orderNo());
            return;
        }
        if (!outboundReceivablePosted(outbound)) return;
        jdbcTemplate.update("update sales_order set receivable_amount=greatest(receivable_amount-?,0) where id=?",
                outbound.totalAmount(), outbound.salesOrderId());
        refreshSalesOrderPaymentStatus(outbound.salesOrderId());
        insertReceivableMovement("CX", outbound.customerId(), "OUTBOUND_REVERSAL", outbound.id(), "CREDIT",
                outbound.totalAmount(), "反向出库冲回应收 " + outbound.orderNo());
    }

    private void reduceReceivableForSalesReturn(OutboundLifecycleRow outbound, Long returnId,
                                                 BigDecimal amount, BigDecimal advanceCredit) {
        BigDecimal receivableReduction = amount.subtract(advanceCredit);
        if (outbound.salesOrderId() > 0) {
            jdbcTemplate.update("""
                    update sales_order set receivable_amount=greatest(receivable_amount-?,0),
                      received_amount=greatest(received_amount-?,0) where id=?
                    """, amount, advanceCredit, outbound.salesOrderId());
            refreshSalesOrderPaymentStatus(outbound.salesOrderId());
        } else {
            jdbcTemplate.update("""
                    update outbound_order set receivable_amount=greatest(receivable_amount-?,0),
                      paid_amount=greatest(paid_amount-?,0) where id=?
                    """, amount, advanceCredit, outbound.id());
            refreshOutboundPaymentStatus(outbound.id());
        }
        if (receivableReduction.compareTo(BigDecimal.ZERO) > 0) {
            insertReceivableMovement("TH", outbound.customerId(), "SALES_RETURN", returnId, "CREDIT",
                    receivableReduction, "销售退货减少应收 " + outbound.orderNo());
        }
        if (advanceCredit.compareTo(BigDecimal.ZERO) > 0) {
            changePartnerAccount(outbound.customerId(), "CUSTOMER_ADVANCE", advanceCredit,
                    "SALES_RETURN", returnId, "已收货款退货转客户预收余额");
        }
    }

    private void restoreReceivableForSalesReturn(OutboundLifecycleRow outbound, SalesReturnLockRow salesReturn) {
        if (outbound.salesOrderId() > 0) {
            jdbcTemplate.update("""
                    update sales_order set receivable_amount=receivable_amount+?,
                      received_amount=received_amount+? where id=?
                    """, salesReturn.totalAmount(), salesReturn.advanceCreditAmount(), outbound.salesOrderId());
            refreshSalesOrderPaymentStatus(outbound.salesOrderId());
        } else {
            jdbcTemplate.update("""
                    update outbound_order set receivable_amount=receivable_amount+?,
                      paid_amount=paid_amount+? where id=?
                    """, salesReturn.totalAmount(), salesReturn.advanceCreditAmount(), outbound.id());
            refreshOutboundPaymentStatus(outbound.id());
        }
        if (salesReturn.receivableReduction().compareTo(BigDecimal.ZERO) > 0) {
            insertReceivableMovement("TC", salesReturn.customerId(), "SALES_RETURN_REVERSAL", salesReturn.id(), "DEBIT",
                    salesReturn.receivableReduction(), "销售退货冲正恢复应收 " + salesReturn.returnNo());
        }
        if (salesReturn.advanceCreditAmount().compareTo(BigDecimal.ZERO) > 0) {
            changePartnerAccount(salesReturn.customerId(), "CUSTOMER_ADVANCE",
                    salesReturn.advanceCreditAmount().negate(), "SALES_RETURN_REVERSAL", salesReturn.id(),
                    "销售退货冲正收回客户预收余额");
        }
    }

    private BigDecimal salesOrderReceivableAmount(Long salesOrderId) {
        return scalarDecimal("select receivable_amount from sales_order where id=" + salesOrderId);
    }

    private BigDecimal salesOrderReceivedAndAdjusted(Long salesOrderId) {
        return scalarDecimal("select received_amount+adjusted_amount from sales_order where id=" + salesOrderId);
    }

    private BigDecimal salesOrderOutstandingAmount(Long salesOrderId) {
        return scalarDecimal("select greatest(receivable_amount-received_amount-adjusted_amount,0) from sales_order where id=" + salesOrderId);
    }

    private void refreshSalesOrderShipmentStatus(Long salesOrderId) {
        jdbcTemplate.update("""
                update sales_order o set status=case
                  when not exists(select 1 from sales_order_item i where i.sales_order_id=o.id and i.shipped_requested_quantity<i.requested_quantity) then 'SHIPPED'
                  when exists(select 1 from sales_order_item i where i.sales_order_id=o.id and i.shipped_requested_quantity>0) then 'PARTIAL_SHIPPED'
                  else 'OPEN' end where o.id=? and o.status<>'CANCELLED'
                """, salesOrderId);
    }

    private SalesReturnLockRow lockSalesReturn(Long returnId) {
        List<SalesReturnLockRow> rows = jdbcTemplate.query("""
                select id,return_no,outbound_id,sales_order_id,customer_id,warehouse_id,status,total_amount,
                       receivable_reduction,advance_credit_amount
                from sales_return_order where id=? for update
                """, (rs, rowNum) -> new SalesReturnLockRow(
                rs.getLong("id"), rs.getString("return_no"), rs.getLong("outbound_id"),
                rs.getLong("sales_order_id"), rs.getLong("customer_id"), rs.getLong("warehouse_id"),
                rs.getString("status"), rs.getBigDecimal("total_amount"),
                rs.getBigDecimal("receivable_reduction"), rs.getBigDecimal("advance_credit_amount")
        ), returnId);
        if (rows.isEmpty()) throw new BusinessException("销售退货单不存在");
        return rows.get(0);
    }

    private List<SalesReturnItemRow> salesReturnItems(Long returnId) {
        return jdbcTemplate.query("""
                select outbound_item_id,product_id,inventory_batch_id,quantity,base_unit,unit_price,amount,
                       inventory_unit_cost,inventory_amount_cost
                from sales_return_item where sales_return_id=? order by id
                """, (rs, rowNum) -> new SalesReturnItemRow(
                rs.getLong("outbound_item_id"), rs.getLong("product_id"), rs.getLong("inventory_batch_id"),
                rs.getBigDecimal("quantity"), rs.getString("base_unit"), rs.getBigDecimal("unit_price"),
                rs.getBigDecimal("amount"), rs.getBigDecimal("inventory_unit_cost"), rs.getBigDecimal("inventory_amount_cost")
        ), returnId);
    }

    private BigDecimal confirmedSalesReturnValue(Long outboundItemId, String column) {
        return nvl(jdbcTemplate.queryForObject("""
                select coalesce(sum(i.%s),0) from sales_return_item i
                join sales_return_order r on r.id=i.sales_return_id
                where i.outbound_item_id=? and r.status='CONFIRMED'
                """.formatted(column), BigDecimal.class, outboundItemId));
    }

    private BigDecimal partnerAccountBalance(Long partnerId, String accountType) {
        return scalarDecimal("select coalesce(sum(balance),0) from partner_account where partner_id=" + partnerId
                + " and account_type='" + accountType + "'");
    }

    private String jsonLongIds(List<Long> ids) {
        try {
            return objectMapper.writeValueAsString(ids == null ? List.of() : ids.stream()
                    .filter(id -> id != null && id > 0).distinct().toList());
        } catch (Exception ex) {
            throw new BusinessException("出库包装选择无法保存");
        }
    }

    private List<Long> parseLongIds(String json) {
        if (!StringUtils.hasText(json)) return List.of();
        try {
            List<?> values = objectMapper.readValue(json, List.class);
            return values.stream().filter(Objects::nonNull).map(value -> Long.valueOf(String.valueOf(value))).toList();
        } catch (Exception ex) {
            throw new BusinessException("出库包装选择记录无法解析");
        }
    }

    private String outboundSourceChannel(String value) {
        return switch (blank(value).toUpperCase()) {
            case "PHONE", "WECHAT", "ON_SITE", "SALES_ORDER", "OTHER" -> blank(value).toUpperCase();
            default -> "OTHER";
        };
    }

    private String outboundEntryMode(String value) {
        return "BACKFILL".equalsIgnoreCase(blank(value)) ? "BACKFILL" :
                ("QUICK".equalsIgnoreCase(blank(value)) ? "QUICK" : "NORMAL");
    }

    private String outboundCompletenessStatus(String value) {
        return "TO_COMPLETE".equalsIgnoreCase(blank(value)) ? "TO_COMPLETE" : "COMPLETE";
    }

    private long resolveOutboundSalesItem(Long salesOrderId, Long salesOrderItemId, Long productId) {
        if (salesOrderId == null || salesOrderId <= 0) {
            if (salesOrderItemId != null && salesOrderItemId > 0) {
                throw new BusinessException("无销售订单出库不能指定销售明细");
            }
            return 0;
        }
        List<String> statuses = jdbcTemplate.query("select status from sales_order where id = ?",
                (rs, rowNum) -> rs.getString(1), salesOrderId);
        if (statuses.isEmpty()) {
            throw new BusinessException("销售订单不存在");
        }
        if ("DRAFT".equals(statuses.get(0))) {
            throw new BusinessException("销售订单尚未确认销售，不能出库");
        }
        if ("CANCELLED".equals(statuses.get(0))) {
            throw new BusinessException("已作废的销售订单不能出库");
        }
        if (salesOrderItemId != null && salesOrderItemId > 0) {
            Integer matched = jdbcTemplate.queryForObject("""
                    select count(*) from sales_order_item
                    where id=? and sales_order_id=? and product_id=? and status='ENABLED'
                    """, Integer.class, salesOrderItemId, salesOrderId, productId);
            if (matched == null || matched != 1) throw new BusinessException("出库商品与所选销售明细不一致");
            return salesOrderItemId;
        }
        Integer candidates = jdbcTemplate.queryForObject("""
                select count(*) from sales_order_item
                where sales_order_id=? and product_id=? and status='ENABLED'
                """, Integer.class, salesOrderId, productId);
        if (candidates != null && candidates > 1) {
            throw new BusinessException("销售订单存在相同商品多行，请选择具体销售明细");
        }
        if (candidates == null || candidates == 0) throw new BusinessException("销售订单中没有对应商品明细");
        return jdbcTemplate.queryForObject("""
                select id from sales_order_item where sales_order_id=? and product_id=? and status='ENABLED' limit 1
                """, Long.class, salesOrderId, productId);
    }

    private Long insert(String sql, Object... args) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? 0L : key.longValue();
    }

    private void updateRequired(String sql, String notFoundMessage, Object... args) {
        int affected = jdbcTemplate.update(sql, args);
        if (affected == 0) {
            throw new BusinessException(notFoundMessage);
        }
    }

    private BigDecimal scalarDecimal(String sql) {
        BigDecimal value = jdbcTemplate.queryForObject(sql, BigDecimal.class);
        return value == null ? BigDecimal.ZERO : value;
    }

    private long scalarLong(String sql, Object... args) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
        return value == null ? 0L : value;
    }

    private String nextNo(String prefix) {
        return prefix + LocalDateTime.now().format(NO_TIME) + (NO_COUNTER.incrementAndGet() % 900);
    }

    private String blank(String value) {
        return value == null ? "" : value;
    }

    private String sourceType(String value) {
        if (!StringUtils.hasText(value)) {
            return "FORMAL";
        }
        return switch (value) {
            case "FORMAL", "PHONE", "WECHAT", "EMAIL", "ORAL", "ONSITE", "NO_ORDER",
                    "PURCHASE_ORDER", "DIRECT_TRADE", "OTHER" -> value;
            default -> "OTHER";
        };
    }

    private String followupType(String value) {
        if (!StringUtils.hasText(value)) {
            return "VISIT";
        }
        return switch (value) {
            case "DEVELOP_CUSTOMER", "VISIT", "PHONE", "WECHAT", "EMAIL", "OTHER" -> value;
            default -> "OTHER";
        };
    }

    private String specialDateType(String value) {
        if (!StringUtils.hasText(value)) {
            return "OTHER";
        }
        return switch (value) {
            case "BIRTHDAY", "ESTABLISHMENT", "ANNIVERSARY", "CONTRACT", "OTHER" -> value;
            default -> "OTHER";
        };
    }

    private int safePartnerLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return 0;
        }
        return Math.min(limit, 100);
    }

    private String likeKeyword(String value) {
        if (!StringUtils.hasText(value)) {
            return "%";
        }
        String text = value.trim()
                .replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_");
        return "%" + text + "%";
    }

    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private int bool(Boolean value) {
        return Boolean.TRUE.equals(value) ? 1 : 0;
    }

    private String baseStatus(String status) {
        return "DISABLED".equals(status) ? "DISABLED" : "ENABLED";
    }

    private String unitKind(String value) {
        if (!StringUtils.hasText(value)) return "MEASURE";
        String normalized = value.trim().toUpperCase();
        if (!Set.of("MEASURE", "COUNT", "PACKAGE").contains(normalized)) {
            throw new BusinessException("单位类型只支持MEASURE、COUNT或PACKAGE");
        }
        return normalized;
    }

    private void validateUnitNames(
            Long unitId, String name, String abbreviation, List<String> aliases) {
        if (!StringUtils.hasText(name)) throw new BusinessException("请填写计量单位名称");
        LinkedHashMap<String, String> candidates = normalizedUnitAliases(name, abbreviation, aliases);
        long excludedId = unitId == null ? 0 : unitId;
        for (Map.Entry<String, String> candidate : candidates.entrySet()) {
            Integer tokenConflicts = jdbcTemplate.queryForObject("""
                    select count(*) from unit_lookup_token
                    where unit_id<>? and normalized_token=?
                    """, Integer.class, excludedId, candidate.getKey());
            if (tokenConflicts != null && tokenConflicts > 0) {
                throw new BusinessException("单位名称、缩写或别名“"
                        + candidate.getValue() + "”已被其他单位占用");
            }
        }
    }

    private void replaceUnitAliases(
            Long unitId, String name, String abbreviation, List<String> aliases) {
        LinkedHashMap<String, String> candidates = normalizedUnitAliases(name, abbreviation, aliases);
        candidates.remove(normalizedUnitKey(name));
        if (StringUtils.hasText(abbreviation)) candidates.remove(normalizedUnitKey(abbreviation));
        jdbcTemplate.update("delete from unit_alias where unit_id=?", unitId);
        for (String alias : candidates.values()) {
            jdbcTemplate.update("""
                    insert into unit_alias(unit_id,alias_name,status)
                    values (?,?,'ENABLED')
                    """, unitId, alias);
        }
        replaceUnitLookupTokens(unitId, name, abbreviation, aliases);
    }

    private void replaceUnitLookupTokens(
            Long unitId, String name, String abbreviation, List<String> aliases) {
        LinkedHashMap<String, String> candidates =
                normalizedUnitAliases(name, abbreviation, aliases);
        String normalizedName = normalizedUnitKey(name);
        String normalizedAbbreviation = StringUtils.hasText(abbreviation)
                ? normalizedUnitKey(abbreviation) : "";
        jdbcTemplate.update("delete from unit_lookup_token where unit_id=?", unitId);
        for (Map.Entry<String, String> candidate : candidates.entrySet()) {
            String tokenType = candidate.getKey().equals(normalizedName)
                    ? "NAME"
                    : (candidate.getKey().equals(normalizedAbbreviation)
                        ? "ABBREVIATION" : "ALIAS");
            jdbcTemplate.update("""
                    insert into unit_lookup_token
                    (normalized_token,unit_id,token_type,source_text)
                    values (?,?,?,?)
                    """, candidate.getKey(), unitId, tokenType, candidate.getValue());
        }
    }

    private LinkedHashMap<String, String> normalizedUnitAliases(
            String name, String abbreviation, List<String> aliases) {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        addUnitAlias(values, name);
        addUnitAlias(values, abbreviation);
        if (aliases != null) {
            for (String alias : aliases) addUnitAlias(values, alias);
        }
        return values;
    }

    private void addUnitAlias(Map<String, String> values, String value) {
        if (!StringUtils.hasText(value)) return;
        String display = value.trim().replaceAll("\\s+", " ");
        String key = normalizedUnitKey(display);
        String previous = values.putIfAbsent(key, display);
        if (previous != null && !previous.equals(display)) {
            throw new BusinessException("单位别名规范化后重复：" + display);
        }
    }

    private String normalizedUnitKey(String value) {
        return UnitLookupNormalizer.normalize(value);
    }

    private String followupStatus(String status) {
        return "COMPLETED".equals(status) ? "COMPLETED" : "OPEN";
    }

    private String entityType(String value) {
        if (!StringUtils.hasText(value)) {
            return "COMPANY";
        }
        return switch (value) {
            case "COMPANY", "SOLE_PROPRIETOR", "INDIVIDUAL" -> value;
            default -> "COMPANY";
        };
    }

    private void validatePartnerCreditCode(SimpleCreateRequests.PartnerCreate req) {
        String normalizedEntityType = entityType(req.entityType());
        if (!"INDIVIDUAL".equals(normalizedEntityType) && !StringUtils.hasText(req.creditCode())) {
            throw new BusinessException("企业和个体工商户必须填写统一社会信用代码");
        }
    }

    private String preferredContactMethod(String value) {
        if (!StringUtils.hasText(value)) {
            return "PHONE";
        }
        return switch (value) {
            case "PHONE", "WECHAT", "EMAIL", "VISIT" -> value;
            default -> "PHONE";
        };
    }

    private String addressType(String value) {
        if (!StringUtils.hasText(value)) {
            return "RECEIVING";
        }
        return switch (value) {
            case "REGISTERED", "RECEIVING", "SHIPPING", "PICKUP", "INVOICE", "OTHER" -> value;
            default -> "OTHER";
        };
    }

    private String standardStatus(String status) {
        return StringUtils.hasText(status) ? status : "现行";
    }

    private int partnerRoleMask(String type) {
        if ("SUPPLIER".equals(type)) {
            return 2;
        }
        if ("BOTH".equals(type)) {
            return 3;
        }
        return 1;
    }

    private int validPartnerRoleMask(int typeMask) {
        if (typeMask < 1 || typeMask > 3) {
            throw new BusinessException("往来单位角色必须选择客户、供应商或两者");
        }
        return typeMask;
    }

    private String roleStatus(String value) {
        return "DISABLED".equals(value) ? "DISABLED" : "ENABLED";
    }

    private void requirePartnerDisableReason(String status, String reason) {
        if ("DISABLED".equals(status) && !StringUtils.hasText(reason))
            throw new BusinessException("停用往来单位时必须填写原因");
    }

    private void validateRoleStatuses(int typeMask, SimpleCreateRequests.PartnerCreate req) {
        if ((typeMask & 1) == 1 && "DISABLED".equals(roleStatus(req.customerStatus()))
                && !StringUtils.hasText(req.customerDisabledReason())) {
            throw new BusinessException("停用客户身份时必须填写原因");
        }
        if ((typeMask & 2) == 2 && "DISABLED".equals(roleStatus(req.supplierStatus()))
                && !StringUtils.hasText(req.supplierDisabledReason())) {
            throw new BusinessException("停用供应商身份时必须填写原因");
        }
    }

    private void validateRoleRemoval(Long partnerId, int existingMask, int newMask) {
        if ((existingMask & 1) == 1 && (newMask & 1) == 0 && partnerRoleHasHistory(partnerId, "CUSTOMER")) {
            throw new BusinessException("客户身份已有历史业务，不能删除；请改为停用客户身份");
        }
        if ((existingMask & 2) == 2 && (newMask & 2) == 0 && partnerRoleHasHistory(partnerId, "SUPPLIER")) {
            throw new BusinessException("供应商身份已有历史业务，不能删除；请改为停用供应商身份");
        }
    }

    private boolean partnerRoleHasHistory(Long partnerId, String role) {
        Integer result;
        if ("CUSTOMER".equals(role)) {
            result = jdbcTemplate.queryForObject("""
                    select case when
                      exists(select 1 from sales_order where customer_id = ?)
                      or exists(select 1 from outbound_order where customer_id = ?)
                      or exists(select 1 from payment where customer_id = ?)
                      or exists(select 1 from partner_account where partner_id = ? and account_type like 'CUSTOMER_%')
                    then 1 else 0 end
                    """, Integer.class, partnerId, partnerId, partnerId, partnerId);
        } else {
            result = jdbcTemplate.queryForObject("""
                    select case when
                      exists(select 1 from purchase_order where supplier_id = ?)
                      or exists(select 1 from inbound_order where supplier_id = ?)
                      or exists(select 1 from supplier_payment where supplier_id = ?)
                      or exists(select 1 from supplier_product_price where supplier_id = ?)
                      or exists(select 1 from partner_account where partner_id = ? and account_type like 'SUPPLIER_%')
                    then 1 else 0 end
                    """, Integer.class, partnerId, partnerId, partnerId, partnerId, partnerId);
        }
        return result != null && result == 1;
    }

    private String typeFromMask(int typeMask) {
        if (typeMask == 3) {
            return "BOTH";
        }
        if (typeMask == 2) {
            return "SUPPLIER";
        }
        return "CUSTOMER";
    }

    private void assertPartnerUnique(Long currentId, String name, String creditCode) {
        String excludeCurrent = currentId == null ? "" : " and id <> ?";
        List<Object> nameArgs = new ArrayList<>();
        nameArgs.add(name);
        if (currentId != null) {
            nameArgs.add(currentId);
        }
        Integer nameCount = jdbcTemplate.queryForObject(
                "select count(*) from partner where lower(trim(name)) = lower(trim(?))" + excludeCurrent,
                Integer.class, nameArgs.toArray());
        if (nameCount != null && nameCount > 0) {
            throw new BusinessException("已存在同名往来单位，请在原主档增加客户或供应商角色");
        }
        if (!StringUtils.hasText(creditCode)) {
            return;
        }
        List<Object> codeArgs = new ArrayList<>();
        codeArgs.add(creditCode.trim());
        if (currentId != null) {
            codeArgs.add(currentId);
        }
        Integer codeCount = jdbcTemplate.queryForObject(
                "select count(*) from partner where lower(trim(credit_code)) = lower(trim(?))" + excludeCurrent,
                Integer.class, codeArgs.toArray());
        if (codeCount != null && codeCount > 0) {
            throw new BusinessException("统一信用代码已被其他往来单位使用");
        }
    }

    private String nextPartnerBusinessNo() {
        Long nextValue = jdbcTemplate.queryForObject(
                "select next_value from partner_number_sequence where id = 1 for update", Long.class);
        if (nextValue == null || nextValue <= 0) {
            throw new BusinessException("往来单位编号序列不可用");
        }
        jdbcTemplate.update("update partner_number_sequence set next_value = ? where id = 1", nextValue + 1);
        return String.format("%06d", nextValue);
    }

    private void syncPartnerProfiles(Long partnerId, int typeMask, SimpleCreateRequests.PartnerCreate req) {
        if ((typeMask & 1) == 1) {
            String status = roleStatus(req.customerStatus());
            jdbcTemplate.update("""
                    insert into partner_customer_profile
                    (partner_id, status, customer_level, settlement_method, settlement_days, credit_limit,
                     tax_included, invoice_type, tax_rate, preferred_products, disabled_reason, disabled_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    on duplicate key update
                      status = values(status), customer_level = values(customer_level), settlement_method = values(settlement_method),
                      settlement_days = values(settlement_days), credit_limit = values(credit_limit),
                      tax_included = values(tax_included), invoice_type = values(invoice_type),
                      tax_rate = values(tax_rate), preferred_products = values(preferred_products),
                      disabled_reason = values(disabled_reason),
                      disabled_at = case when values(status) = 'DISABLED'
                                         then coalesce(disabled_at, values(disabled_at)) else null end
                    """, partnerId, status, textOrDefault(req.customerLevel(), "NORMAL"),
                    textOrDefault(req.customerSettlementMethod(), "PER_ORDER"), positiveInt(req.customerSettlementDays()),
                    nvl(req.creditLimit()), bool(req.customerTaxIncluded() == null || req.customerTaxIncluded()),
                    textOrDefault(req.customerInvoiceType(), "NONE"), nonNegative(req.customerTaxRate()),
                    blank(req.preferredProducts()), "DISABLED".equals(status) ? blank(req.customerDisabledReason()) : "",
                    "DISABLED".equals(status) ? Timestamp.valueOf(LocalDateTime.now()) : null);
        } else {
            jdbcTemplate.update("delete from partner_customer_profile where partner_id = ?", partnerId);
        }
        if ((typeMask & 2) == 2) {
            String status = roleStatus(req.supplierStatus());
            jdbcTemplate.update("""
                    insert into partner_supplier_profile
                    (partner_id, status, settlement_method, settlement_days, lead_time_days, min_order_amount,
                     tax_included, invoice_type, tax_rate, qualification_memo, disabled_reason, disabled_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    on duplicate key update
                      status = values(status), settlement_method = values(settlement_method), settlement_days = values(settlement_days),
                      lead_time_days = values(lead_time_days), min_order_amount = values(min_order_amount),
                      tax_included = values(tax_included), invoice_type = values(invoice_type),
                      tax_rate = values(tax_rate), qualification_memo = values(qualification_memo),
                      disabled_reason = values(disabled_reason),
                      disabled_at = case when values(status) = 'DISABLED'
                                         then coalesce(disabled_at, values(disabled_at)) else null end
                    """, partnerId, status, textOrDefault(req.supplierSettlementMethod(), "PER_ORDER"),
                    positiveInt(req.supplierSettlementDays()), positiveInt(req.leadTimeDays()),
                    nonNegative(req.minOrderAmount()), bool(req.supplierTaxIncluded() == null || req.supplierTaxIncluded()),
                    textOrDefault(req.supplierInvoiceType(), "NONE"), nonNegative(req.supplierTaxRate()),
                    blank(req.qualificationMemo()), "DISABLED".equals(status) ? blank(req.supplierDisabledReason()) : "",
                    "DISABLED".equals(status) ? Timestamp.valueOf(LocalDateTime.now()) : null);
        } else {
            jdbcTemplate.update("delete from partner_supplier_profile where partner_id = ?", partnerId);
        }
    }

    private void upsertPrimaryContact(Long partnerId, SimpleCreateRequests.PartnerCreate req) {
        if (!StringUtils.hasText(req.contactPerson())) {
            clearFirstChoiceContact(partnerId);
            syncPartnerPrimaryContact(partnerId, "", "");
            return;
        }
        clearFirstChoiceContact(partnerId);
        List<Long> ids = jdbcTemplate.query("""
                select id from partner_contact where partner_id = ? order by id limit 1
                """, (rs, rowNum) -> rs.getLong("id"), partnerId);
        if (ids.isEmpty()) {
            jdbcTemplate.update("""
                    insert into partner_contact
                    (partner_id, name, job_title, phone, wechat, email, preferred_method, first_choice, status, memo)
                    values (?, ?, ?, ?, ?, ?, ?, 1, 'ENABLED', '由往来单位主档维护')
                    """, partnerId, req.contactPerson(), blank(req.primaryContactRole()), blank(req.phone()),
                    blank(req.wechat()), blank(req.email()), preferredContactMethod(null));
        } else {
            jdbcTemplate.update("""
                    update partner_contact
                    set name = ?, job_title = ?, phone = ?, wechat = ?, email = ?,
                        preferred_method = ?, first_choice = 1, status = 'ENABLED'
                    where id = ?
                    """, req.contactPerson(), blank(req.primaryContactRole()), blank(req.phone()), blank(req.wechat()),
                    blank(req.email()), preferredContactMethod(null), ids.get(0));
        }
        syncPartnerPrimaryContact(partnerId, req.contactPerson(), req.phone());
    }

    private void upsertPrimaryAddress(Long partnerId, SimpleCreateRequests.PartnerCreate req) {
        if (!StringUtils.hasText(req.address())) {
            return;
        }
        List<Long> ids = jdbcTemplate.query("""
                select id from partner_address where partner_id = ? and first_choice = 1
                order by case address_type when 'REGISTERED' then 0 else 1 end, id limit 1
                """, (rs, rowNum) -> rs.getLong("id"), partnerId);
        if (ids.isEmpty()) {
            jdbcTemplate.update("""
                    insert into partner_address
                    (partner_id, name, address_type, detail, contact_person, phone, first_choice, status, memo)
                    values (?, '主要地址', 'REGISTERED', ?, ?, ?, 1, 'ENABLED', '由往来单位主档维护')
                    """, partnerId, req.address(), blank(req.contactPerson()), blank(req.phone()));
        } else {
            jdbcTemplate.update("""
                    update partner_address set detail = ?, contact_person = ?, phone = ?, status = 'ENABLED'
                    where id = ?
                    """, req.address(), blank(req.contactPerson()), blank(req.phone()), ids.get(0));
        }
        syncPartnerPrimaryAddress(partnerId, req.address());
    }

    private int positiveInt(Integer value) {
        return value == null ? 0 : Math.max(value, 0);
    }

    private BigDecimal nonNegative(BigDecimal value) {
        return value == null || value.signum() < 0 ? BigDecimal.ZERO : value;
    }

    private String textOrDefault(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    private String partnerBaseSelect() {
        return """
                select p.id, p.business_no,
                       case p.type_mask when 3 then 'BOTH' when 2 then 'SUPPLIER' else 'CUSTOMER' end type,
                       p.type_mask, p.entity_type, p.name, p.alias_name,
                       p.credit_code, coalesce(pc.name, '') contact_person,
                       coalesce(pc.job_title, '') primary_contact_role,
                       coalesce(pc.phone, '') phone, coalesce(pc.wechat, '') wechat,
                       coalesce(pc.email, '') email, coalesce(nullif(pa.detail, ''), p.address) address,
                       coalesce(cp.credit_limit, p.credit_limit) credit_limit, p.top_fixed,
                       p.cooperation_start_date, p.establishment_date, p.source, p.tags, p.status,
                       p.disabled_reason, p.disabled_at, p.memo, p.created_at,
                       coalesce(cp.status, 'DISABLED') customer_status,
                       coalesce(cp.disabled_reason, '') customer_disabled_reason,
                       cp.disabled_at customer_disabled_at,
                       coalesce(cp.customer_level, 'NORMAL') customer_level,
                       coalesce(cp.settlement_method, 'PER_ORDER') customer_settlement_method,
                       coalesce(cp.settlement_days, 0) customer_settlement_days,
                       coalesce(cp.tax_included, 1) customer_tax_included,
                       coalesce(cp.invoice_type, 'NONE') customer_invoice_type,
                       coalesce(cp.tax_rate, 0) customer_tax_rate,
                       coalesce(cp.preferred_products, '') preferred_products,
                       coalesce(sp.status, 'DISABLED') supplier_status,
                       coalesce(sp.disabled_reason, '') supplier_disabled_reason,
                       sp.disabled_at supplier_disabled_at,
                       coalesce(sp.settlement_method, 'PER_ORDER') supplier_settlement_method,
                       coalesce(sp.settlement_days, 0) supplier_settlement_days,
                       coalesce(sp.lead_time_days, 0) lead_time_days,
                       coalesce(sp.min_order_amount, 0) min_order_amount,
                       coalesce(sp.tax_included, 1) supplier_tax_included,
                       coalesce(sp.invoice_type, 'NONE') supplier_invoice_type,
                       coalesce(sp.tax_rate, 0) supplier_tax_rate,
                       coalesce(sp.qualification_memo, '') qualification_memo
                from partner p
                left join partner_contact pc on pc.id = (
                  select c.id from partner_contact c
                  where c.partner_id = p.id and c.first_choice = 1 and c.status = 'ENABLED'
                  order by c.id asc limit 1
                )
                left join partner_address pa on pa.id = (
                  select a.id from partner_address a where a.partner_id = p.id
                  order by a.first_choice desc, a.status = 'ENABLED' desc,
                           case a.address_type when 'REGISTERED' then 0 when 'RECEIVING' then 1 else 2 end,
                           a.id asc limit 1
                )
                left join partner_customer_profile cp on cp.partner_id = p.id
                left join partner_supplier_profile sp on sp.partner_id = p.id
                """;
    }

    private String partnerSummarySelect() {
        return """
                select p.id, p.business_no,
                       case p.type_mask when 3 then 'BOTH' when 2 then 'SUPPLIER' else 'CUSTOMER' end type,
                       p.type_mask, p.entity_type, p.name, p.alias_name,
                       p.tags, p.top_fixed, p.status,
                       coalesce(cp.status, 'DISABLED') customer_status,
                       coalesce(sp.status, 'DISABLED') supplier_status,
                       coalesce(pc.name, '') primary_contact_name,
                       coalesce(pc.job_title, '') primary_contact_role,
                       coalesce(pc.phone, '') primary_contact_phone,
                       coalesce(pc.wechat, '') primary_contact_wechat,
                       coalesce(nullif(pa.detail, ''), p.address) primary_address,
                       coalesce(ar.receivable_amount, 0) customer_receivable,
                       coalesce(ar.overdue_amount, 0) customer_overdue,
                       coalesce(cp.credit_limit, p.credit_limit) customer_credit_limit,
                       greatest(coalesce(cp.credit_limit, p.credit_limit) - coalesce(ar.receivable_amount, 0), 0) customer_available_credit,
                       coalesce(ac.customer_advance, 0) customer_advance,
                       coalesce(ap.payable_amount, 0) supplier_payable,
                       coalesce(ac.supplier_prepay, 0) supplier_prepay,
                       cb.last_business_date customer_last_business_date,
                       sb.last_business_date supplier_last_business_date,
                       cr.last_payment_date customer_last_payment_date,
                       sr.last_payment_date supplier_last_payment_date,
                       fu.last_followup_at, fu.next_follow_date,
                       coalesce((
                         select group_concat(distinct product.name order by product.name separator '、')
                         from supplier_product_price quote
                         join product product on product.id = quote.product_id
                         where quote.supplier_id = p.id and quote.status = 'ENABLED'
                       ), '') supplied_products
                from partner p
                left join partner_contact pc on pc.id = (
                  select c.id from partner_contact c
                  where c.partner_id = p.id and c.first_choice = 1 and c.status = 'ENABLED'
                  order by c.id asc limit 1
                )
                left join partner_address pa on pa.id = (
                  select a.id from partner_address a where a.partner_id = p.id
                  order by a.first_choice desc, a.status = 'ENABLED' desc, a.id asc limit 1
                )
                left join partner_customer_profile cp on cp.partner_id = p.id
                left join partner_supplier_profile sp on sp.partner_id = p.id
                left join (
                  select debt.partner_id, sum(debt.unpaid_amount) receivable_amount,
                         sum(case when debt.due_date < current_date
                                  then debt.unpaid_amount else 0 end) overdue_amount
                  from (
                    select customer_id partner_id, due_date, greatest(receivable_amount - received_amount - adjusted_amount, 0) unpaid_amount
                    from sales_order where status not in ('DRAFT', 'CANCELLED')
                    union all
                    select customer_id partner_id, due_date, greatest(receivable_amount - paid_amount, 0) unpaid_amount
                    from outbound_order
                    where sales_order_id = 0 and status = 'SHIPPED' and receivable_amount > 0
                  ) debt
                  group by debt.partner_id
                ) ar on ar.partner_id = p.id
                left join (
                  select debt.partner_id, sum(debt.unpaid_amount) payable_amount
                  from (
                    select o.supplier_id partner_id,
                           greatest(o.total_amount - o.paid_amount - coalesce(r.returned_amount,0), 0) unpaid_amount
                    from purchase_order o
                    left join (select purchase_order_id,sum(payable_reduction_amount) returned_amount
                               from purchase_return_order where status='CONFIRMED' group by purchase_order_id) r
                      on r.purchase_order_id=o.id
                    where o.status not in ('DRAFT', 'CANCELLED')
                    union all
                    select o.supplier_id partner_id,
                           greatest(o.payable_amount - o.paid_amount - coalesce(r.returned_amount,0), 0) unpaid_amount
                    from inbound_order o
                    left join (select inbound_id,sum(payable_reduction_amount) returned_amount
                               from purchase_return_order where status='CONFIRMED' group by inbound_id) r
                      on r.inbound_id=o.id
                    where o.purchase_order_id = 0 and o.status = 'STOCKED' and o.payable_amount > 0
                  ) debt group by debt.partner_id
                ) ap on ap.partner_id = p.id
                left join (
                  select partner_id,
                         sum(case when account_type = 'CUSTOMER_ADVANCE' then balance else 0 end) customer_advance,
                         sum(case when account_type = 'SUPPLIER_PREPAY' then balance else 0 end) supplier_prepay
                  from partner_account group by partner_id
                ) ac on ac.partner_id = p.id
                left join (
                  select business.partner_id, max(business.business_date) last_business_date
                  from (
                    select customer_id partner_id, business_date from sales_order where status not in ('DRAFT', 'CANCELLED')
                    union all
                    select customer_id partner_id, business_date from outbound_order where status = 'SHIPPED'
                  ) business group by business.partner_id
                ) cb on cb.partner_id = p.id
                left join (
                  select business.partner_id, max(business.business_date) last_business_date
                  from (
                    select supplier_id partner_id, business_date from purchase_order where status not in ('DRAFT', 'CANCELLED')
                    union all
                    select supplier_id partner_id, business_date from inbound_order where status not in ('DRAFT', 'CANCELLED')
                  ) business group by business.partner_id
                ) sb on sb.partner_id = p.id
                left join (
                  select customer_id partner_id, max(received_date) last_payment_date
                  from payment group by customer_id
                ) cr on cr.partner_id = p.id
                left join (
                  select supplier_id partner_id, max(paid_date) last_payment_date
                  from supplier_payment group by supplier_id
                ) sr on sr.partner_id = p.id
                left join (
                  select partner_id, max(occurred_at) last_followup_at,
                         min(case when status = 'OPEN' then next_follow_date end) next_follow_date
                  from partner_followup group by partner_id
                ) fu on fu.partner_id = p.id
                """;
    }

    private BigDecimal amountAfterDiscount(BigDecimal originalAmount, BigDecimal discountRate, BigDecimal adjustmentAmount) {
        BigDecimal discount = nvl(discountRate);
        BigDecimal adjust = nvl(adjustmentAmount);
        return originalAmount.subtract(originalAmount.multiply(discount).divide(BigDecimal.valueOf(100))).add(adjust);
    }

    private RowMapper<PartnerDto> partnerMapper() {
        return (rs, rowNum) -> new PartnerDto(
                rs.getLong("id"),
                rs.getString("business_no"),
                rs.getString("type"),
                rs.getInt("type_mask"),
                rs.getString("entity_type"),
                rs.getString("name"),
                rs.getString("alias_name"),
                rs.getString("credit_code"),
                rs.getString("contact_person"),
                rs.getString("primary_contact_role"),
                rs.getString("phone"),
                rs.getString("wechat"),
                rs.getString("email"),
                rs.getString("address"),
                rs.getBigDecimal("credit_limit"),
                rs.getInt("top_fixed") == 1,
                rs.getDate("cooperation_start_date") == null ? null : rs.getDate("cooperation_start_date").toLocalDate(),
                rs.getDate("establishment_date") == null ? null : rs.getDate("establishment_date").toLocalDate(),
                rs.getString("source"),
                rs.getString("tags"),
                rs.getString("status"),
                rs.getString("disabled_reason"),
                rs.getTimestamp("disabled_at") == null ? null : rs.getTimestamp("disabled_at").toLocalDateTime(),
                rs.getString("memo"),
                rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getString("customer_status"),
                rs.getString("customer_disabled_reason"),
                rs.getTimestamp("customer_disabled_at") == null ? null : rs.getTimestamp("customer_disabled_at").toLocalDateTime(),
                rs.getString("customer_level"),
                rs.getString("customer_settlement_method"),
                rs.getInt("customer_settlement_days"),
                rs.getInt("customer_tax_included") == 1,
                rs.getString("customer_invoice_type"),
                rs.getBigDecimal("customer_tax_rate"),
                rs.getString("preferred_products"),
                rs.getString("supplier_status"),
                rs.getString("supplier_disabled_reason"),
                rs.getTimestamp("supplier_disabled_at") == null ? null : rs.getTimestamp("supplier_disabled_at").toLocalDateTime(),
                rs.getString("supplier_settlement_method"),
                rs.getInt("supplier_settlement_days"),
                rs.getInt("lead_time_days"),
                rs.getBigDecimal("min_order_amount"),
                rs.getInt("supplier_tax_included") == 1,
                rs.getString("supplier_invoice_type"),
                rs.getBigDecimal("supplier_tax_rate"),
                rs.getString("qualification_memo")
        );
    }

    private RowMapper<PartnerSummaryDto> partnerSummaryMapper() {
        return (rs, rowNum) -> new PartnerSummaryDto(
                rs.getLong("id"),
                rs.getString("business_no"),
                rs.getString("type"),
                rs.getInt("type_mask"),
                rs.getString("entity_type"),
                rs.getString("name"),
                rs.getString("alias_name"),
                rs.getString("tags"),
                rs.getInt("top_fixed") == 1,
                rs.getString("status"),
                rs.getString("customer_status"),
                rs.getString("supplier_status"),
                rs.getString("primary_contact_name"),
                rs.getString("primary_contact_role"),
                rs.getString("primary_contact_phone"),
                rs.getString("primary_contact_wechat"),
                rs.getString("primary_address"),
                rs.getBigDecimal("customer_receivable"),
                rs.getBigDecimal("customer_overdue"),
                rs.getBigDecimal("customer_credit_limit"),
                rs.getBigDecimal("customer_available_credit"),
                rs.getBigDecimal("customer_advance"),
                rs.getBigDecimal("supplier_payable"),
                rs.getBigDecimal("supplier_prepay"),
                rs.getDate("customer_last_business_date") == null ? null : rs.getDate("customer_last_business_date").toLocalDate(),
                rs.getDate("supplier_last_business_date") == null ? null : rs.getDate("supplier_last_business_date").toLocalDate(),
                rs.getDate("customer_last_payment_date") == null ? null : rs.getDate("customer_last_payment_date").toLocalDate(),
                rs.getDate("supplier_last_payment_date") == null ? null : rs.getDate("supplier_last_payment_date").toLocalDate(),
                rs.getTimestamp("last_followup_at") == null ? null : rs.getTimestamp("last_followup_at").toLocalDateTime(),
                rs.getDate("next_follow_date") == null ? null : rs.getDate("next_follow_date").toLocalDate(),
                rs.getString("supplied_products")
        );
    }

    private RowMapper<ProductDto> productMapper() {
        return (rs, rowNum) -> new ProductDto(
                rs.getLong("id"),
                rs.getLong("category_id"),
                rs.getString("category_name"),
                rs.getLong("product_series_id"),
                rs.getString("product_series_name"),
                rs.getLong("spec_preset_id"),
                rs.getString("spec_signature"),
                rs.getString("sku"),
                rs.getString("name"),
                rs.getString("grade"),
                rs.getString("temper"),
                rs.getString("standard"),
                rs.getString("spec"),
                "",
                rs.getString("attribute_json"),
                rs.getString("barcode"),
                rs.getString("original_number"),
                rs.getString("unit"),
                rs.getBigDecimal("default_price"),
                rs.getString("status"),
                rs.getInt("identity_schema_version_snapshot"),
                rs.getString("identity_status"),
                rs.getString("identity_signature"),
                List.of(),
                true,
                List.of()
        );
    }

    private ProductPurchaseBase productPurchaseBase(Long productId) {
        dataScopeService.assertMasterAllowed("PRODUCT", productId);
        List<ProductPurchaseBase> rows = jdbcTemplate.query("""
                select p.id, p.product_series_id, p.unit, p.default_price,
                       p.status, p.identity_status,
                       coalesce(s.package_unit_category_id, 0) package_unit_category_id,
                       coalesce(s.price_unit_category_id, 0) price_unit_category_id
                from product p
                left join product_series s on s.id = p.product_series_id
                where p.id = ?
                """, (rs, rowNum) -> new ProductPurchaseBase(
                rs.getLong("id"),
                rs.getLong("product_series_id"),
                rs.getString("unit"),
                rs.getBigDecimal("default_price"),
                rs.getString("status"),
                rs.getString("identity_status"),
                rs.getLong("package_unit_category_id"),
                rs.getLong("price_unit_category_id")
        ), productId);
        if (rows.isEmpty()) {
            throw new BusinessException("商品不存在");
        }
        ProductPurchaseBase product = rows.get(0);
        if (!"ENABLED".equals(product.status())) {
            throw new BusinessException("商品已停用，不能用于采购、销售或库存业务");
        }
        if (!"PUBLISHED".equals(product.identityStatus())) {
            throw new BusinessException("商品必填规格尚未补齐，不能用于采购、销售或库存业务");
        }
        return product;
    }

    private List<ProductPurchaseOptionsDto.UnitOption> productUnitOptions(boolean priceUnit, Long productSeriesId,
                                                                         Long unitCategoryId, String defaultUnit) {
        String tableName = priceUnit ? "product_series_price_unit" : "product_series_package_unit";
        List<ProductPurchaseOptionsDto.UnitOption> options = new ArrayList<>();
        if (priceUnit && productSeriesId != null && productSeriesId > 0) {
            options.addAll(jdbcTemplate.query("""
                    select coalesce(nullif(u.abbreviation,''),u.name) option_name,
                           u.id,u.name,a.is_default
                    from product_series_allowed_unit a
                    join unit u on u.id=a.unit_id and u.status='ENABLED'
                    where a.product_series_id=? and a.purpose='PRICE' and a.status='ENABLED'
                    order by a.is_default desc,a.sort_no,a.id
                    """, (rs, rowNum) -> new ProductPurchaseOptionsDto.UnitOption(
                    rs.getString("option_name"), rs.getLong("id"), rs.getString("name"),
                    null, rs.getBoolean("is_default")
            ), productSeriesId));
        }
        List<ProductPurchaseOptionsDto.UnitOption> legacy = jdbcTemplate.query("""
                select name, unit_id, unit_name, exchange_rate, primary_unit
                from %s
                where product_series_id = ? and status = 'ENABLED'
                order by primary_unit desc, id
                """.formatted(tableName), (rs, rowNum) -> new ProductPurchaseOptionsDto.UnitOption(
                unitOptionName(rs.getString("name"), rs.getString("unit_name")),
                rs.getObject("unit_id") == null ? null : rs.getLong("unit_id"),
                rs.getString("unit_name"),
                rs.getBigDecimal("exchange_rate"),
                rs.getInt("primary_unit") == 1
        ), productSeriesId == null ? 0 : productSeriesId);
        for (ProductPurchaseOptionsDto.UnitOption option : legacy) {
            if (!hasUnitOption(options, option.name())) options.add(option);
        }

        if (!priceUnit && options.isEmpty() && unitCategoryId != null && unitCategoryId > 0) {
            options = jdbcTemplate.query("""
                    select id, name, abbreviation
                    from unit
                    where unit_category_id = ? and status = 'ENABLED'
                    order by id
                    """, (rs, rowNum) -> {
                String name = unitOptionName(rs.getString("abbreviation"), rs.getString("name"));
                return new ProductPurchaseOptionsDto.UnitOption(
                        name,
                        rs.getLong("id"),
                        rs.getString("name"),
                        null,
                        false
                );
            }, unitCategoryId);
        }

        if (priceUnit && StringUtils.hasText(defaultUnit) && !hasUnitOption(options, defaultUnit)) {
            List<ProductPurchaseOptionsDto.UnitOption> merged = new ArrayList<>();
            merged.add(new ProductPurchaseOptionsDto.UnitOption(defaultUnit.trim(), null, defaultUnit.trim(), null, true));
            merged.addAll(options);
            return merged;
        }
        return options;
    }

    private void mergeSupplierUsedUnits(List<ProductPurchaseOptionsDto.UnitOption> options, Long supplierId, Long productId,
                                        boolean priceUnit) {
        List<String> units;
        if (priceUnit) {
            units = jdbcTemplate.query("""
                    select price_unit
                    from supplier_product_price
                    where supplier_id = ? and product_id = ? and status = 'ENABLED' and price_unit <> ''
                    union
                    select distinct i.price_unit
                    from purchase_order_item i
                    join purchase_order o on o.id = i.purchase_order_id
                    where o.supplier_id = ? and i.product_id = ? and i.price_unit <> ''
                      and o.status not in ('DRAFT', 'CANCELLED')
                    order by 1
                    """, (rs, rowNum) -> rs.getString("price_unit"), supplierId, productId, supplierId, productId);
        } else {
            units = jdbcTemplate.query("""
                    select distinct i.package_unit
                    from purchase_order_item i
                    join purchase_order o on o.id = i.purchase_order_id
                    where o.supplier_id = ? and i.product_id = ? and i.package_unit <> ''
                      and o.status not in ('DRAFT', 'CANCELLED')
                    order by i.package_unit
                    """, (rs, rowNum) -> rs.getString("package_unit"), supplierId, productId);
        }
        for (String unit : units) {
            addLooseUnitOption(options, unit);
        }
    }

    private void mergeCurrentSupplierOfferUnits(
            List<ProductPurchaseOptionsDto.UnitOption> priceUnits,
            List<ProductPurchaseOptionsDto.UnitOption> purchaseUnits,
            Long supplierId, Long productId, LocalDate businessDate) {
        List<Map<String, Object>> offers = jdbcTemplate.queryForList("""
                select distinct r.price_unit,r.purchase_unit,coalesce(vr.package_unit,'') package_unit
                from supplier_product_offer o
                join supplier_product_offer_revision r
                  on r.supplier_product_offer_id=o.id
                left join packaging_variant_revision vr
                  on vr.id=r.packaging_variant_revision_id and vr.status='PUBLISHED'
                left join packaging_variant v
                  on v.id=vr.packaging_variant_id
                where o.supplier_id=? and o.product_id=? and o.status='ENABLED'
                  and r.status='PUBLISHED' and r.effective_from<=?
                  and (r.effective_to is null or r.effective_to>=?)
                  and (r.packaging_variant_revision_id=0
                       or (vr.id is not null and v.status='ENABLED'))
                  and not exists (
                    select 1 from supplier_product_offer_revision r2
                    where r2.supplier_product_offer_id=r.supplier_product_offer_id
                      and r2.status='PUBLISHED' and r2.effective_from<=?
                      and (r2.effective_from>r.effective_from
                           or (r2.effective_from=r.effective_from
                               and r2.revision_no>r.revision_no))
                )
                """, supplierId, productId, Date.valueOf(businessDate),
                Date.valueOf(businessDate),
                Date.valueOf(businessDate));
        for (Map<String, Object> offer : offers) {
            addControlledUnitOption(priceUnits, stringValue(offer, "price_unit"), false);
            addControlledUnitOption(purchaseUnits, stringValue(offer, "purchase_unit"), false);
            addControlledUnitOption(purchaseUnits, stringValue(offer, "package_unit"), false);
        }
    }

    private ProductPurchaseOptionsDto.PriceSuggestion orderPriceSuggestion(ProductPurchaseBase product, Long partnerId,
                                                                          String partnerType, String priceUnit,
                                                                          BigDecimal quantity, LocalDate businessDate,
                                                                          ProductPurchaseOptionsDto.SupplierOfferContext supplierOffer) {
        if ("SUPPLIER".equals(partnerType) && supplierOffer != null
                && supplierOffer.quotedUnitPrice() != null
                && supplierOffer.quotedUnitPrice().compareTo(BigDecimal.ZERO) > 0
                && sameText(priceUnit, supplierOffer.priceUnit())) {
            return new ProductPurchaseOptionsDto.PriceSuggestion(
                    supplierOffer.quotedUnitPrice(), supplierOffer.priceUnit(),
                    "SUPPLIER_OFFER", "供应商供货版本报价",
                    "来自当前有效的供应商供货档案第"
                            + supplierOffer.supplierOfferRevisionNo() + "版",
                    supplierOffer.supplierOfferRevisionId(),
                    supplierOffer.priceIncludesTax(), supplierOffer.taxRate(),
                    List.of(), supplierOfferWarnings(supplierOffer), supplierOffer.snapshotJson());
        }
        if (List.of("SUPPLIER", "CUSTOMER").contains(partnerType)) {
            String businessType = "SUPPLIER".equals(partnerType) ? "PURCHASE" : "SALES";
            Optional<PriceCenterDtos.Preview> preview = priceCenterService.previewOrEmpty(
                    new PriceCenterDtos.PreviewRequest(businessType, partnerId, product.id(),
                            quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0 ? BigDecimal.ONE : quantity,
                            priceUnit, businessDate == null ? LocalDate.now() : businessDate));
            if (preview.isPresent()) return priceSuggestion(preview.get());
        }
        if ("SUPPLIER".equals(partnerType)) {
            return purchasePriceSuggestion(product, partnerId, priceUnit);
        }
        if ("CUSTOMER".equals(partnerType)) {
            return salesPriceSuggestion(product, partnerId, priceUnit);
        }
        return productDefaultPriceSuggestion(product, priceUnit);
    }

    private ProductPurchaseOptionsDto.PriceSuggestion priceSuggestion(PriceCenterDtos.Preview preview) {
        return new ProductPurchaseOptionsDto.PriceSuggestion(preview.unitPrice(), preview.priceUnit(),
                preview.sourceCode(), preview.sourceName(), preview.explanation(), preview.ruleVersionId(),
                preview.priceIncludesTax(), preview.taxRate(), preview.components(), preview.warnings(),
                preview.snapshotJson());
    }

    private ProductPurchaseOptionsDto.SupplierOfferContext currentSupplierOfferContext(
            Long productId, Long supplierId, String priceUnit, LocalDate businessDate) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select o.id supplier_offer_id,r.id supplier_offer_revision_id,r.revision_no,
                       r.supplier_id,r.purchase_unit,r.price_unit,r.currency_code,r.quoted_unit_price,
                       r.price_includes_tax,r.tax_rate,r.minimum_order_quantity,r.order_multiple,
                       r.lead_time_days,r.effective_from,r.effective_to,
                       v.id packaging_variant_id,r.packaging_variant_revision_id,
                       vr.revision_no packaging_variant_revision_no,v.name packaging_variant_name,
                       vr.package_unit,vr.conversion_mode,vr.fixed_base_quantity,
                       vr.reference_base_quantity
                from supplier_product_offer o
                join supplier_product_offer_revision r
                  on r.supplier_product_offer_id=o.id
                left join packaging_variant_revision vr
                  on vr.id=r.packaging_variant_revision_id and vr.status='PUBLISHED'
                left join packaging_variant v
                  on v.id=vr.packaging_variant_id
                where o.product_id=? and o.supplier_id=? and o.status='ENABLED'
                  and r.status='PUBLISHED' and (?='' or r.price_unit=?)
                  and (r.packaging_variant_revision_id=0
                       or (vr.id is not null and v.status='ENABLED'))
                  and r.effective_from<=?
                  and (r.effective_to is null or r.effective_to>=?)
                  and not exists (
                    select 1 from supplier_product_offer_revision r2
                    where r2.supplier_product_offer_id=r.supplier_product_offer_id
                      and r2.status='PUBLISHED'
                      and r2.effective_from<=?
                      and (r2.effective_from>r.effective_from
                           or (r2.effective_from=r.effective_from
                               and r2.revision_no>r.revision_no))
                  )
                order by r.effective_from desc,r.published_at desc,r.id desc
                """, productId, supplierId,
                StringUtils.hasText(priceUnit) ? priceUnit : "",
                StringUtils.hasText(priceUnit) ? priceUnit : "",
                Date.valueOf(businessDate),
                Date.valueOf(businessDate),
                Date.valueOf(businessDate));
        if (rows.isEmpty()) return null;
        if (rows.size() > 1) {
            throw new BusinessException("同一供应商、商品和业务日期存在多份生效供货档案，"
                    + "请先停用重叠档案或补齐明确的供货版本选择");
        }
        Map<String, Object> row = rows.get(0);
        ProductPurchaseOptionsDto.SupplierOfferContext draft =
                new ProductPurchaseOptionsDto.SupplierOfferContext(
                        longValue(row, "supplier_offer_id"),
                        longValue(row, "supplier_offer_revision_id"),
                        intValue(row, "revision_no"),
                        longValue(row, "supplier_id"),
                        stringValue(row, "purchase_unit"),
                        stringValue(row, "price_unit"),
                        stringValue(row, "currency_code"),
                        nullableDecimalValue(row, "quoted_unit_price"),
                        intValue(row, "price_includes_tax") == 1,
                        nullableDecimalValue(row, "tax_rate"),
                        nullableDecimalValue(row, "minimum_order_quantity"),
                        nullableDecimalValue(row, "order_multiple"),
                        intValue(row, "lead_time_days"),
                        localDateValue(row, "effective_from"),
                        localDateValue(row, "effective_to"),
                        nullableLongValue(row, "packaging_variant_id"),
                        nullableLongValue(row, "packaging_variant_revision_id"),
                        nullableIntValue(row, "packaging_variant_revision_no"),
                        stringValue(row, "packaging_variant_name"),
                        stringValue(row, "package_unit"),
                        stringValue(row, "conversion_mode"),
                        nullableDecimalValue(row, "fixed_base_quantity"),
                        nullableDecimalValue(row, "reference_base_quantity"),
                        "",
                        ""
                );
        String snapshot = canonicalJson(safeJson(draft));
        return new ProductPurchaseOptionsDto.SupplierOfferContext(
                draft.supplierOfferId(), draft.supplierOfferRevisionId(),
                draft.supplierOfferRevisionNo(), draft.supplierId(), draft.purchaseUnit(),
                draft.priceUnit(), draft.currencyCode(), draft.quotedUnitPrice(), draft.priceIncludesTax(),
                draft.taxRate(), draft.minimumOrderQuantity(), draft.orderMultiple(),
                draft.leadTimeDays(), draft.effectiveFrom(), draft.effectiveTo(),
                draft.packagingVariantId(), draft.packagingVariantRevisionId(),
                draft.packagingVariantRevisionNo(), draft.packagingVariantName(),
                draft.packageUnit(), draft.conversionMode(), draft.fixedBaseQuantity(),
                draft.referenceBaseQuantity(), snapshot, sha256(snapshot));
    }

    private List<String> supplierOfferWarnings(
            ProductPurchaseOptionsDto.SupplierOfferContext offer) {
        List<String> warnings = new ArrayList<>();
        if (offer.minimumOrderQuantity() != null
                && offer.minimumOrderQuantity().compareTo(BigDecimal.ZERO) > 0) {
            warnings.add("最小起订量：" + offer.minimumOrderQuantity().stripTrailingZeros().toPlainString()
                    + offer.purchaseUnit());
        }
        if (offer.orderMultiple() != null && offer.orderMultiple().compareTo(BigDecimal.ZERO) > 0) {
            warnings.add("下单倍数：" + offer.orderMultiple().stripTrailingZeros().toPlainString()
                    + offer.purchaseUnit());
        }
        if (offer.leadTimeDays() != null && offer.leadTimeDays() > 0) {
            warnings.add("参考交期：" + offer.leadTimeDays() + "天");
        }
        warnings.add("供货版本有效期：" + offer.effectiveFrom()
                + (offer.effectiveTo() == null ? "起" : "至" + offer.effectiveTo()));
        if (offer.packagingVariantRevisionId() != null) {
            String reference = offer.referenceBaseQuantity() == null ? ""
                    : "，参考基础数量" + offer.referenceBaseQuantity().stripTrailingZeros().toPlainString();
            warnings.add("包装方案：" + offer.packagingVariantName() + "第"
                    + offer.packagingVariantRevisionNo() + "版（" + offer.packageUnit()
                    + reference + "）");
        }
        return List.copyOf(warnings);
    }

    private ProductPurchaseOptionsDto.SupplierOfferContext validateExpectedSupplierOffer(
            Long supplierId, PurchaseCreateRequest.Item item, String priceUnit,
            LocalDate businessDate, OrderTaxTerms taxTerms) {
        ProductPurchaseOptionsDto.SupplierOfferContext current = currentSupplierOfferContext(
                item.productId(), supplierId, priceUnit, businessDate);
        if (current == null) {
            Integer relationshipCount = jdbcTemplate.queryForObject("""
                    select count(*) from supplier_product_offer
                    where supplier_id=? and product_id=? and status='ENABLED'
                    """, Integer.class, supplierId, item.productId());
            if ((relationshipCount != null && relationshipCount > 0)
                    || item.expectedSupplierOfferRevisionId() != null
                    || StringUtils.hasText(item.expectedSupplierOfferSnapshotHash())
                    || item.packagingVariantRevisionId() != null) {
                throw new BusinessException("供应商供货版本已失效或不再适用于当前商品、单位和业务日期，请刷新");
            }
            return null;
        }
        validateSupplierOfferTerms(
                current, supplierId, item, priceUnit, businessDate, taxTerms);
        return current;
    }

    private ProductPurchaseOptionsDto.SupplierOfferContext validateFrozenOrCurrentSupplierOffer(
            Long supplierId, PurchaseCreateRequest.Item item, String priceUnit,
            LocalDate businessDate, OrderTaxTerms taxTerms, String historicalPriceSnapshot) {
        ProductPurchaseOptionsDto.SupplierOfferContext frozen =
                frozenSupplierOfferContext(
                        historicalPriceSnapshot, supplierId, item.productId());
        if (frozen == null) {
            return validateExpectedSupplierOffer(
                    supplierId, item, priceUnit, businessDate, taxTerms);
        }
        validateSupplierOfferTerms(
                frozen, supplierId, item, priceUnit, businessDate, taxTerms);
        return frozen;
    }

    private ProductPurchaseOptionsDto.SupplierOfferContext frozenSupplierOfferContext(
            String historicalPriceSnapshot, Long supplierId, Long productId) {
        if (!StringUtils.hasText(historicalPriceSnapshot)) {
            throw new BusinessException("采购明细缺少历史价格快照，不能安全重验供应商供货条款");
        }
        try {
            JsonNode root = objectMapper.readTree(historicalPriceSnapshot);
            JsonNode frozen = root.get("supplierOfferSnapshot");
            if (frozen == null || frozen.isNull() || !frozen.isObject()) return null;
            if (root.path("partnerId").asLong(0) > 0
                    && root.path("partnerId").asLong() != supplierId
                    || root.path("productId").asLong(0) > 0
                    && root.path("productId").asLong() != productId) {
                throw new BusinessException("采购明细的供应商供货快照与当前供应商或商品不一致");
            }
            String snapshot = canonicalJson(frozen);
            String storedHash = root.path("supplierOfferSnapshotHash").asText("");
            if (!StringUtils.hasText(storedHash)
                    || !storedHash.equalsIgnoreCase(sha256(snapshot))) {
                throw new BusinessException("采购明细的供应商供货快照校验失败，禁止继续修改");
            }
            ProductPurchaseOptionsDto.SupplierOfferContext parsed =
                    objectMapper.treeToValue(
                            frozen, ProductPurchaseOptionsDto.SupplierOfferContext.class);
            if (!Objects.equals(parsed.supplierId(), supplierId)) {
                throw new BusinessException("采购明细的供应商供货快照不属于当前供应商");
            }
            Integer ownership = jdbcTemplate.queryForObject("""
                    select count(*)
                    from supplier_product_offer_revision r
                    join supplier_product_offer o on o.id=r.supplier_product_offer_id
                    where r.id=? and o.id=? and o.supplier_id=? and o.product_id=?
                    """, Integer.class, parsed.supplierOfferRevisionId(),
                    parsed.supplierOfferId(), supplierId, productId);
            if (ownership == null || ownership != 1) {
                throw new BusinessException("采购明细的供应商供货版本不属于当前供应商或商品");
            }
            return new ProductPurchaseOptionsDto.SupplierOfferContext(
                    parsed.supplierOfferId(), parsed.supplierOfferRevisionId(),
                    parsed.supplierOfferRevisionNo(), parsed.supplierId(),
                    parsed.purchaseUnit(), parsed.priceUnit(), parsed.currencyCode(),
                    parsed.quotedUnitPrice(), parsed.priceIncludesTax(), parsed.taxRate(),
                    parsed.minimumOrderQuantity(), parsed.orderMultiple(), parsed.leadTimeDays(),
                    parsed.effectiveFrom(), parsed.effectiveTo(), parsed.packagingVariantId(),
                    parsed.packagingVariantRevisionId(), parsed.packagingVariantRevisionNo(),
                    parsed.packagingVariantName(), parsed.packageUnit(), parsed.conversionMode(),
                    parsed.fixedBaseQuantity(), parsed.referenceBaseQuantity(),
                    snapshot, storedHash);
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException("采购明细的供应商供货快照无法解析，禁止继续修改");
        }
    }

    private void validateSupplierOfferTerms(
            ProductPurchaseOptionsDto.SupplierOfferContext current,
            Long supplierId, PurchaseCreateRequest.Item item, String priceUnit,
            LocalDate businessDate, OrderTaxTerms taxTerms) {
        if (!Objects.equals(current.supplierId(), supplierId)) {
            throw new BusinessException("供应商供货版本不属于当前供应商");
        }
        if (item.expectedSupplierOfferRevisionId() == null
                || !Objects.equals(item.expectedSupplierOfferRevisionId(),
                current.supplierOfferRevisionId())) {
            throw new BusinessException("供应商供货档案已更新，请刷新采购商品后重新提交");
        }
        if (!StringUtils.hasText(item.expectedSupplierOfferSnapshotHash())
                || !item.expectedSupplierOfferSnapshotHash().trim()
                .equalsIgnoreCase(current.snapshotHash())) {
            throw new BusinessException("供应商供货条款已变化，请刷新后重新核对起订量、交期和报价");
        }
        if (!sameText(priceUnit, current.priceUnit())) {
            throw new BusinessException("供应商供货版本要求按“" + current.priceUnit()
                    + "”计价，请重新选择计价单位");
        }
        if (!sameText(item.quantityUnit(), current.purchaseUnit())) {
            throw new BusinessException("当前供货版本要求按“" + current.purchaseUnit()
                    + "”采购，请重新选择采购单位");
        }
        if (businessDate == null
                || current.effectiveFrom() == null
                || businessDate.isBefore(current.effectiveFrom())
                || current.effectiveTo() != null
                && businessDate.isAfter(current.effectiveTo())) {
            throw new BusinessException("供应商供货版本在当前采购业务日期无效，请刷新后重新选择");
        }
        if (!"CNY".equalsIgnoreCase(current.currencyCode())) {
            throw new BusinessException("当前采购单仅支持人民币本位币；外币供货报价必须先补齐"
                    + "单据币种、汇率日期与本位币金额快照后才能使用");
        }
        if (current.minimumOrderQuantity() != null
                && current.minimumOrderQuantity().compareTo(BigDecimal.ZERO) > 0
                && item.quantity().compareTo(current.minimumOrderQuantity()) < 0) {
            throw new BusinessException("采购数量不得低于当前供货版本的最小起订量"
                    + current.minimumOrderQuantity().stripTrailingZeros().toPlainString()
                    + current.purchaseUnit());
        }
        if (current.orderMultiple() != null
                && current.orderMultiple().compareTo(BigDecimal.ZERO) > 0
                && item.quantity().remainder(current.orderMultiple()).compareTo(BigDecimal.ZERO) != 0) {
            throw new BusinessException("采购数量必须是当前供货版本下单倍数"
                    + current.orderMultiple().stripTrailingZeros().toPlainString()
                    + current.purchaseUnit() + "的整数倍");
        }
        if (!Objects.equals(item.packagingVariantRevisionId(),
                current.packagingVariantRevisionId())) {
            throw new BusinessException("供应商包装方案版本已变化，请刷新后重新计算预计库存数量");
        }
        BigDecimal orderTaxRate = taxTerms.rateFor(item.itemId());
        if (!Objects.equals(current.priceIncludesTax(), taxTerms.priceIncludesTax())
                || nvl(current.taxRate()).setScale(6, RoundingMode.HALF_UP)
                .compareTo(nvl(orderTaxRate).setScale(6, RoundingMode.HALF_UP)) != 0) {
            throw new BusinessException("供应商供货报价的含税口径或税率与采购单税务主数据不一致，"
                    + "请先修正供应商税务资料或供货报价");
        }
    }

    private PriceCenterDtos.PriceSnapshot freezeSupplierOfferPriceSnapshot(
            PriceCenterDtos.PriceSnapshot base,
            ProductPurchaseOptionsDto.SupplierOfferContext supplierOffer,
            BigDecimal enteredPrice, boolean noCharge) {
        if (supplierOffer == null) return base;
        try {
            JsonNode parsed = objectMapper.readTree(base.snapshotJson());
            if (!(parsed instanceof ObjectNode root)) {
                throw new BusinessException("价格快照格式不正确");
            }
            root.put("supplierOfferId", supplierOffer.supplierOfferId());
            root.put("supplierOfferRevisionId", supplierOffer.supplierOfferRevisionId());
            root.put("supplierOfferRevisionNo", supplierOffer.supplierOfferRevisionNo());
            root.put("supplierOfferSnapshotHash", supplierOffer.snapshotHash());
            root.set("supplierOfferSnapshot",
                    objectMapper.readTree(supplierOffer.snapshotJson()));
            boolean usesQuote = !noCharge && supplierOffer.quotedUnitPrice() != null
                    && supplierOffer.quotedUnitPrice().compareTo(enteredPrice) == 0;
            String sourceCode = usesQuote ? "SUPPLIER_OFFER" : base.sourceCode();
            String sourceName = usesQuote ? "供应商供货版本报价" : base.sourceName();
            long ruleVersionId = usesQuote
                    ? supplierOffer.supplierOfferRevisionId() : base.ruleVersionId();
            if (usesQuote) {
                root.put("sourceCode", sourceCode);
                root.put("sourceName", sourceName);
                root.put("ruleVersionId", ruleVersionId);
            }
            return new PriceCenterDtos.PriceSnapshot(
                    sourceCode, sourceName, ruleVersionId,
                    objectMapper.writeValueAsString(root));
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException("供应商供货报价快照生成失败");
        }
    }

    private ProductPurchaseOptionsDto.PriceSuggestion purchasePriceSuggestion(ProductPurchaseBase product, Long supplierId,
                                                                             String priceUnit) {
        String resolvedPriceUnit = StringUtils.hasText(priceUnit) ? priceUnit.trim() : product.unit();
        if (supplierId != null && supplierId > 0) {
            List<BigDecimal> quotedPrices = jdbcTemplate.query("""
                    select unit_price
                    from supplier_product_price
                    where supplier_id = ? and product_id = ? and price_unit = ? and status = 'ENABLED'
                    order by id desc
                    limit 1
                    """, (rs, rowNum) -> rs.getBigDecimal("unit_price"), supplierId, product.id(), resolvedPriceUnit);
            if (!quotedPrices.isEmpty()) {
                return new ProductPurchaseOptionsDto.PriceSuggestion(quotedPrices.get(0), resolvedPriceUnit,
                        "SUPPLIER_QUOTE", "供应商固定报价", "历史固定报价（尚未版本化）", 0L,
                        null, null, List.of(), List.of("建议在价格中心建立供应商协议价版本"), "");
            }

            List<BigDecimal> recentPrices = jdbcTemplate.query("""
                    select i.unit_price
                    from purchase_order_item i
                    join purchase_order o on o.id = i.purchase_order_id
                    where o.supplier_id = ? and i.product_id = ? and i.price_unit = ?
                      and i.unit_price > 0 and o.status not in ('DRAFT', 'CANCELLED')
                    order by o.business_date desc, i.id desc
                    limit 1
                    """, (rs, rowNum) -> rs.getBigDecimal("unit_price"), supplierId, product.id(), resolvedPriceUnit);
            if (!recentPrices.isEmpty()) {
                return new ProductPurchaseOptionsDto.PriceSuggestion(recentPrices.get(0), resolvedPriceUnit,
                        "RECENT_PURCHASE", "最近采购价", "来自最近已确认采购单", 0L,
                        null, null, List.of(), List.of(), "");
            }
        }

        return productDefaultPriceSuggestion(product, resolvedPriceUnit);
    }

    private ProductPurchaseOptionsDto.PriceSuggestion salesPriceSuggestion(ProductPurchaseBase product, Long customerId,
                                                                          String priceUnit) {
        String resolvedPriceUnit = StringUtils.hasText(priceUnit) ? priceUnit.trim() : product.unit();
        if (customerId != null && customerId > 0) {
            List<BigDecimal> recentPrices = jdbcTemplate.query("""
                    select i.unit_price
                    from sales_order_item i
                    join sales_order o on o.id = i.sales_order_id
                    where o.customer_id = ? and i.product_id = ? and i.price_unit = ?
                      and i.unit_price > 0 and o.status not in ('DRAFT', 'CANCELLED')
                    order by o.business_date desc, i.id desc
                    limit 1
                    """, (rs, rowNum) -> rs.getBigDecimal("unit_price"), customerId, product.id(), resolvedPriceUnit);
            if (!recentPrices.isEmpty()) {
                return new ProductPurchaseOptionsDto.PriceSuggestion(recentPrices.get(0), resolvedPriceUnit,
                        "RECENT_SALES", "最近销售价", "来自该客户最近已确认销售单", 0L,
                        null, null, List.of(), List.of(), "");
            }
        }
        return productDefaultPriceSuggestion(product, resolvedPriceUnit);
    }

    private ProductPurchaseOptionsDto.PriceSuggestion productDefaultPriceSuggestion(ProductPurchaseBase product, String priceUnit) {
        String resolvedPriceUnit = StringUtils.hasText(priceUnit) ? priceUnit.trim() : product.unit();
        if (sameText(resolvedPriceUnit, product.unit())) {
            return new ProductPurchaseOptionsDto.PriceSuggestion(nvl(product.defaultPrice()), resolvedPriceUnit,
                    "PRODUCT_DEFAULT", "商品默认价", "来自商品主数据默认价", 0L,
                    null, null, List.of(), List.of(), "");
        }
        return new ProductPurchaseOptionsDto.PriceSuggestion(BigDecimal.ZERO, resolvedPriceUnit, "NONE", "暂无价格",
                "请在价格中心建立适用规则", 0L, null, null, List.of(), List.of(), "");
    }

    private void upsertSupplierProductPrice(Long supplierId, Long productId, String priceUnit, BigDecimal unitPrice) {
        if (!StringUtils.hasText(priceUnit) || unitPrice == null || unitPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        jdbcTemplate.update("""
                insert into supplier_product_price (supplier_id, product_id, price_unit, unit_price, status)
                values (?, ?, ?, ?, 'ENABLED')
                on duplicate key update unit_price = values(unit_price), status = 'ENABLED', updated_at = current_timestamp
                """, supplierId, productId, priceUnit.trim(), unitPrice);
    }

    private String productDefaultUnit(Long productId) {
        List<String> rows = jdbcTemplate.query("select unit from product where id = ?",
                (rs, rowNum) -> rs.getString("unit"), productId);
        return rows.isEmpty() || !StringUtils.hasText(rows.get(0)) ? "kg" : rows.get(0).trim();
    }

    private String primaryUnitName(List<ProductPurchaseOptionsDto.UnitOption> options, String fallback) {
        for (ProductPurchaseOptionsDto.UnitOption option : options) {
            if (Boolean.TRUE.equals(option.primaryUnit()) && StringUtils.hasText(option.name())) {
                return option.name().trim();
            }
        }
        return options.isEmpty() || !StringUtils.hasText(options.get(0).name()) ? fallback : options.get(0).name().trim();
    }

    private boolean hasUnitOption(List<ProductPurchaseOptionsDto.UnitOption> options, String unitName) {
        for (ProductPurchaseOptionsDto.UnitOption option : options) {
            if (sameText(option.name(), unitName) || sameText(option.unitName(), unitName)) {
                return true;
            }
        }
        return false;
    }

    private void addLooseUnitOption(List<ProductPurchaseOptionsDto.UnitOption> options, String unitName) {
        if (!StringUtils.hasText(unitName) || hasUnitOption(options, unitName)) {
            return;
        }
        String name = unitName.trim();
        options.add(new ProductPurchaseOptionsDto.UnitOption(name, null, name, null, false));
    }

    private void addControlledUnitOption(
            List<ProductPurchaseOptionsDto.UnitOption> options, String unitName, boolean primary) {
        if (!StringUtils.hasText(unitName) || hasUnitOption(options, unitName)) return;
        List<ProductPurchaseOptionsDto.UnitOption> rows = jdbcTemplate.query("""
                select id,name,abbreviation from unit
                where status='ENABLED' and (
                  lower(trim(name))=lower(trim(?))
                  or lower(trim(abbreviation))=lower(trim(?))
                )
                order by id limit 1
                """, (rs, rowNum) -> new ProductPurchaseOptionsDto.UnitOption(
                StringUtils.hasText(rs.getString("abbreviation"))
                        ? rs.getString("abbreviation") : rs.getString("name"),
                rs.getLong("id"), rs.getString("name"), null, primary
        ), unitName, unitName);
        if (rows.isEmpty()) {
            throw new BusinessException("供应商供货版本引用的计量单位已停用，请先修复供货主数据");
        }
        options.add(rows.get(0));
    }

    private String availableUnitName(List<ProductPurchaseOptionsDto.UnitOption> options, String requestedUnit, String fallback) {
        if (StringUtils.hasText(requestedUnit)) {
            for (ProductPurchaseOptionsDto.UnitOption option : options) {
                if (sameText(option.name(), requestedUnit) || sameText(option.unitName(), requestedUnit)) {
                    return option.name();
                }
            }
        }
        return primaryUnitName(options, fallback);
    }

    private String unitOptionName(String preferred, String fallback) {
        if (StringUtils.hasText(preferred)) {
            return preferred.trim();
        }
        return StringUtils.hasText(fallback) ? fallback.trim() : "";
    }

    private boolean sameText(String left, String right) {
        return StringUtils.hasText(left) && StringUtils.hasText(right) && left.trim().equalsIgnoreCase(right.trim());
    }

    private RowMapper<AlignedDtos.ContactRow> contactMapper() {
        return (rs, rowNum) -> new AlignedDtos.ContactRow(
                rs.getLong("id"),
                rs.getLong("partner_id"),
                rs.getString("name"),
                rs.getString("job_title"),
                rs.getString("department"),
                rs.getInt("gender"),
                rs.getString("phone"),
                rs.getString("wechat"),
                rs.getString("email"),
                rs.getString("preferred_method"),
                rs.getInt("first_choice") == 1,
                rs.getDate("birthday") == null ? null : rs.getDate("birthday").toLocalDate(),
                rs.getString("memo"),
                rs.getString("status")
        );
    }

    private RowMapper<AlignedDtos.AddressRow> addressMapper() {
        return (rs, rowNum) -> new AlignedDtos.AddressRow(
                rs.getLong("id"),
                rs.getLong("partner_id"),
                rs.getString("name"),
                rs.getString("address_type"),
                rs.getString("province_code"),
                rs.getString("city_code"),
                rs.getString("district_code"),
                rs.getString("detail"),
                rs.getString("contact_person"),
                rs.getString("phone"),
                rs.getInt("first_choice") == 1,
                rs.getString("memo"),
                rs.getString("status")
        );
    }

    private RowMapper<AlignedDtos.FollowupRow> followupMapper() {
        return (rs, rowNum) -> new AlignedDtos.FollowupRow(
                rs.getLong("id"),
                rs.getLong("partner_id"),
                rs.getObject("contact_id") == null ? null : rs.getLong("contact_id"),
                rs.getString("contact_name"),
                rs.getString("contact_phone"),
                rs.getString("followup_type"),
                rs.getString("method_type"),
                rs.getTimestamp("occurred_at").toLocalDateTime(),
                rs.getDate("next_follow_date") == null ? null : rs.getDate("next_follow_date").toLocalDate(),
                rs.getString("status"),
                rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toLocalDateTime(),
                rs.getString("memo")
        );
    }

    private RowMapper<AlignedDtos.BankAccountRow> bankAccountMapper() {
        return (rs, rowNum) -> new AlignedDtos.BankAccountRow(
                rs.getLong("id"),
                rs.getLong("partner_id"),
                rs.getString("account_name"),
                rs.getString("bank_name"),
                rs.getString("bank_account"),
                rs.getInt("first_choice") == 1,
                rs.getString("status"),
                rs.getString("memo")
        );
    }

    private RowMapper<AlignedDtos.SpecialDateRow> specialDateMapper() {
        return (rs, rowNum) -> new AlignedDtos.SpecialDateRow(
                rs.getLong("id"),
                rs.getLong("partner_id"),
                rs.getObject("contact_id") == null ? null : rs.getLong("contact_id"),
                rs.getString("contact_name"),
                rs.getString("date_type"),
                rs.getString("title"),
                rs.getDate("special_date").toLocalDate(),
                rs.getInt("repeat_yearly") == 1,
                rs.getInt("remind_days_before"),
                rs.getString("memo"),
                rs.getString("status")
        );
    }

    private RowMapper<AlignedDtos.ProductSeriesRow> productSeriesMapper() {
        return (rs, rowNum) -> new AlignedDtos.ProductSeriesRow(
                rs.getLong("id"),
                rs.getLong("category_id"),
                rs.getString("category_name"),
                rs.getString("code"),
                rs.getString("name"),
                rs.getString("alias_names"),
                rs.getLong("package_unit_category_id"),
                rs.getLong("price_unit_category_id"),
                rs.getInt("identity_schema_version"),
                rs.getString("status")
        );
    }

    private RowMapper<MasterDataDtos.ProductCategoryRow> productCategoryMapper() {
        return (rs, rowNum) -> new MasterDataDtos.ProductCategoryRow(
                rs.getLong("id"),
                rs.getLong("parent_id"),
                rs.getString("name"),
                rs.getInt("sort_no"),
                rs.getString("status")
        );
    }

    private RowMapper<MasterDataDtos.ProductStandardRow> productStandardMapper() {
        return (rs, rowNum) -> {
            Date publishDate = rs.getDate("publish_date");
            Date implementationDate = rs.getDate("implementation_date");
            Date abolitionDate = rs.getDate("abolition_date");
            return new MasterDataDtos.ProductStandardRow(
                    rs.getLong("id"),
                    rs.getString("code"),
                    rs.getString("name"),
                    rs.getString("english_name"),
                    rs.getString("standard_status"),
                    rs.getString("ccs"),
                    rs.getString("ics"),
                    publishDate == null ? null : publishDate.toLocalDate(),
                    implementationDate == null ? null : implementationDate.toLocalDate(),
                    abolitionDate == null ? null : abolitionDate.toLocalDate(),
                    rs.getString("competent_department"),
                    rs.getString("responsible_department"),
                    rs.getString("publisher"),
                    rs.getString("memo"),
                    rs.getString("source_url"),
                    rs.getString("status")
            );
        };
    }

    private RowMapper<MasterDataDtos.ProductTemperRow> productTemperMapper() {
        return (rs, rowNum) -> new MasterDataDtos.ProductTemperRow(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("code"),
                rs.getString("english_name"),
                rs.getString("status")
        );
    }

    private RowMapper<MasterDataDtos.CustomFieldRow> customFieldMapper() {
        return (rs, rowNum) -> new MasterDataDtos.CustomFieldRow(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("field_type"),
                rs.getString("value_type"),
                rs.getLong("unit_category_id"),
                rs.getString("option_json"),
                rs.getString("status"),
                rs.getLong("series_count"),
                rs.getLong("product_count"),
                rs.getLong("preset_count")
        );
    }

    private RowMapper<MasterDataDtos.SeriesFieldRow> seriesFieldMapper() {
        return (rs, rowNum) -> new MasterDataDtos.SeriesFieldRow(
                rs.getLong("id"),
                rs.getLong("product_series_id"),
                rs.getLong("field_id"),
                rs.getString("field_name"),
                rs.getString("display_name"),
                rs.getString("field_type"),
                rs.getString("value_type"),
                rs.getString("option_json"),
                rs.getLong("unit_category_id"),
                rs.getLong("unit_id"),
                rs.getString("unit_name"),
                rs.getInt("required") == 1,
                rs.getInt("sku_dimension") == 1,
                rs.getInt("include_in_spec") == 1,
                rs.getInt("searchable") == 1,
                rs.getInt("order_visible") == 1,
                rs.getBigDecimal("min_value"),
                rs.getBigDecimal("max_value"),
                rs.getInt("decimal_scale"),
                rs.getInt("sort_no"),
                rs.getString("status"),
                rs.getLong("product_count"),
                rs.getLong("preset_count")
        );
    }

    private RowMapper<MasterDataDtos.SpecPresetRow> specPresetMapper() {
        return (rs, rowNum) -> new MasterDataDtos.SpecPresetRow(
                rs.getLong("id"),
                rs.getLong("product_series_id"),
                rs.getString("product_series_name"),
                rs.getString("name"),
                rs.getString("model"),
                rs.getString("attribute_json"),
                List.of(),
                rs.getString("spec_text"),
                rs.getString("sku_suffix"),
                rs.getInt("sort_no"),
                rs.getString("status")
        );
    }

    private RowMapper<AlignedDtos.UnitCategoryRow> unitCategoryMapper() {
        return (rs, rowNum) -> new AlignedDtos.UnitCategoryRow(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("status")
        );
    }

    private RowMapper<AlignedDtos.UnitRow> unitMapper() {
        return (rs, rowNum) -> new AlignedDtos.UnitRow(
                rs.getLong("id"),
                rs.getLong("unit_category_id"),
                rs.getString("unit_category_name"),
                rs.getString("name"),
                rs.getString("abbreviation"),
                rs.getString("description"),
                rs.getString("unit_kind"),
                List.of(),
                rs.getString("status")
        );
    }

    private RowMapper<WarehouseDto> warehouseMapper() {
        return (rs, rowNum) -> new WarehouseDto(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("location"),
                rs.getString("receiving_addresses"),
                rs.getString("status")
        );
    }

    private RowMapper<InventoryBatchDto> inventoryMapper() {
        return (rs, rowNum) -> new InventoryBatchDto(
                rs.getLong("id"),
                rs.getLong("product_id"),
                rs.getString("sku"),
                rs.getString("product_name"),
                rs.getString("grade"),
                rs.getString("spec"),
                rs.getLong("warehouse_id"),
                rs.getString("warehouse_name"),
                rs.getString("batch_no"),
                rs.getObject("source_inbound_id") == null ? null : rs.getLong("source_inbound_id"),
                rs.getString("source_inbound_no"),
                rs.getBigDecimal("quantity_in"),
                rs.getBigDecimal("quantity_available"),
                rs.getBigDecimal("reserved_quantity"),
                rs.getBigDecimal("free_quantity"),
                rs.getBigDecimal("in_transit_quantity"),
                rs.getString("base_unit"),
                rs.getLong("packaging_effective_revision_id"),
                rs.getString("packaging_profile_snapshot_json"),
                rs.getString("packaging_snapshot_hash"),
                rs.getString("packaging_tracking_mode"),
                rs.getString("package_barcode"),
                rs.getBigDecimal("unit_cost"),
                rs.getBigDecimal("amount_cost"),
                rs.getString("status"),
                rs.getDate("received_at").toLocalDate(),
                rs.getDate("production_date") == null ? null : rs.getDate("production_date").toLocalDate(),
                rs.getString("shelf_life_mode"),
                rs.getObject("shelf_life_value") == null ? null : rs.getInt("shelf_life_value"),
                rs.getString("shelf_life_unit"),
                rs.getDate("expiration_date") == null ? null : rs.getDate("expiration_date").toLocalDate()
        );
    }

    private RowMapper<OrderDtos.InboundRow> inboundMapper() {
        return (rs, rowNum) -> new OrderDtos.InboundRow(
                rs.getLong("id"),
                rs.getString("order_no"),
                rs.getLong("purchase_order_id"),
                rs.getString("purchase_order_no"),
                rs.getString("supplier_name"),
                rs.getString("warehouse_name"),
                rs.getString("source_type"),
                rs.getDate("business_date").toLocalDate(),
                rs.getString("send_number"),
                rs.getBigDecimal("total_quantity"),
                rs.getString("quantity_summary"),
                rs.getBigDecimal("total_amount"),
                rs.getBigDecimal("paid_amount"),
                rs.getString("payment_status"),
                rs.getString("status")
        );
    }

    private RowMapper<OrderDtos.PurchaseOrderRow> purchaseOrderMapper() {
        return (rs, rowNum) -> new OrderDtos.PurchaseOrderRow(
                rs.getLong("id"),
                rs.getString("order_no"),
                rs.getString("supplier_name"),
                rs.getDate("business_date").toLocalDate(),
                rs.getDate("expected_date") == null ? null : rs.getDate("expected_date").toLocalDate(),
                rs.getLong("expected_warehouse_id"),
                rs.getString("expected_warehouse_name"),
                rs.getString("source_type"),
                rs.getBigDecimal("total_quantity"),
                rs.getBigDecimal("received_quantity"),
                rs.getBigDecimal("original_amount"),
                rs.getBigDecimal("discount_amount"),
                rs.getBigDecimal("adjustment_amount"),
                rs.getBigDecimal("total_amount"),
                rs.getBigDecimal("paid_amount"),
                rs.getString("payment_status"),
                rs.getString("status")
        );
    }

    private RowMapper<OrderDtos.OutboundRow> outboundMapper() {
        return (rs, rowNum) -> new OrderDtos.OutboundRow(
                rs.getLong("id"),
                rs.getString("order_no"),
                rs.getLong("sales_order_id"),
                rs.getString("sales_order_no"),
                rs.getString("customer_name"),
                rs.getString("warehouse_name"),
                rs.getDate("business_date").toLocalDate(),
                rs.getTimestamp("actual_shipped_at") == null ? null : rs.getTimestamp("actual_shipped_at").toLocalDateTime(),
                rs.getString("source_type"),
                rs.getString("source_channel"),
                rs.getString("entry_mode"),
                rs.getString("completeness_status"),
                rs.getString("settlement_status"),
                rs.getBigDecimal("total_quantity"),
                rs.getBigDecimal("package_quantity"),
                rs.getBigDecimal("total_amount"),
                rs.getBigDecimal("paid_amount"),
                rs.getString("payment_status"),
                rs.getString("status")
        );
    }

    private RowMapper<OrderDtos.PaymentRow> paymentMapper() {
        return (rs, rowNum) -> new OrderDtos.PaymentRow(
                rs.getLong("id"),
                rs.getString("payment_no"),
                rs.getLong("customer_id"),
                rs.getString("customer_name"),
                rs.getLong("sales_order_id"),
                rs.getString("sales_order_no"),
                rs.getLong("outbound_id"),
                rs.getString("outbound_no"),
                rs.getBigDecimal("amount"),
                rs.getBigDecimal("allocated_amount"),
                rs.getBigDecimal("unallocated_amount"),
                rs.getInt("allocation_count"),
                rs.getString("fund_nature"),
                rs.getString("allocation_mode"),
                rs.getString("payment_method"),
                rs.getString("account_type"),
                rs.getObject("business_bank_account_id", Long.class),
                rs.getString("business_bank_account_name"),
                rs.getDate("received_date").toLocalDate(),
                rs.getString("verification_status"),
                rs.getString("reversal_no"),
                rs.getString("reversal_reason"),
                rs.getTimestamp("reversed_at") == null ? null : rs.getTimestamp("reversed_at").toLocalDateTime()
        );
    }

    private RowMapper<OrderDtos.SupplierPaymentRow> supplierPaymentMapper() {
        return (rs, rowNum) -> new OrderDtos.SupplierPaymentRow(
                rs.getLong("id"),
                rs.getString("payment_no"),
                rs.getLong("supplier_id"),
                rs.getString("supplier_name"),
                rs.getLong("purchase_order_id"),
                rs.getString("purchase_order_no"),
                rs.getLong("inbound_id"),
                rs.getString("inbound_no"),
                rs.getBigDecimal("amount"),
                rs.getBigDecimal("allocated_amount"),
                rs.getBigDecimal("unallocated_amount"),
                rs.getInt("allocation_count"),
                rs.getString("fund_nature"),
                rs.getString("allocation_mode"),
                rs.getString("payment_method"),
                rs.getString("account_type"),
                rs.getObject("business_bank_account_id", Long.class),
                rs.getString("business_bank_account_name"),
                rs.getDate("paid_date").toLocalDate(),
                rs.getString("verification_status"),
                rs.getString("reversal_no"),
                rs.getString("reversal_reason"),
                rs.getTimestamp("reversed_at") == null ? null : rs.getTimestamp("reversed_at").toLocalDateTime()
        );
    }

    private RowMapper<OrderDtos.PartnerAccountRow> accountMapper() {
        return (rs, rowNum) -> new OrderDtos.PartnerAccountRow(
                rs.getLong("id"),
                rs.getLong("partner_id"),
                rs.getString("partner_name"),
                rs.getString("partner_type"),
                rs.getString("account_type"),
                rs.getBigDecimal("balance"),
                rs.getBigDecimal("frozen_amount"),
                rs.getBigDecimal("credit_limit")
        );
    }

    private RowMapper<OrderDtos.PartnerAccountLogRow> accountLogMapper() {
        return (rs, rowNum) -> new OrderDtos.PartnerAccountLogRow(
                rs.getLong("id"),
                rs.getLong("partner_id"),
                rs.getString("partner_name"),
                rs.getString("account_type"),
                rs.getString("source_type"),
                rs.getLong("source_id"),
                rs.getString("business_type"),
                rs.getLong("business_id"),
                rs.getString("business_no"),
                rs.getString("business_summary"),
                rs.getBigDecimal("amount"),
                rs.getBigDecimal("amount_before"),
                rs.getBigDecimal("amount_after"),
                rs.getTimestamp("created_at").toLocalDateTime()
        );
    }

    private RowMapper<AlignedDtos.SalesOrderRow> salesOrderMapper() {
        return (rs, rowNum) -> new AlignedDtos.SalesOrderRow(
                rs.getLong("id"),
                rs.getString("order_no"),
                rs.getString("title"),
                rs.getString("customer_name"),
                rs.getDate("business_date").toLocalDate(),
                rs.getDate("delivery_plan_date") == null ? null : rs.getDate("delivery_plan_date").toLocalDate(),
                rs.getLong("fulfillment_warehouse_id"),
                rs.getString("fulfillment_warehouse_name"),
                rs.getBigDecimal("original_amount"),
                rs.getBigDecimal("discount_amount"),
                rs.getBigDecimal("adjustment_amount"),
                rs.getBigDecimal("total_amount"),
                rs.getBigDecimal("receivable_amount"),
                rs.getBigDecimal("received_amount"),
                rs.getBigDecimal("adjusted_amount"),
                rs.getBigDecimal("cash_received_amount"),
                rs.getBigDecimal("outstanding_amount"),
                rs.getString("payment_status"),
                rs.getString("payment_term"),
                rs.getString("shipment_payment_policy"),
                rs.getBigDecimal("required_deposit_amount"),
                rs.getBigDecimal("reserved_prepayment_amount"),
                rs.getBigDecimal("prepayment_shortfall"),
                rs.getBigDecimal("shipment_funded_amount"),
                rs.getBigDecimal("shipment_required_amount"),
                rs.getBigDecimal("shipment_payment_shortfall"),
                rs.getString("status")
        );
    }

    private RowMapper<ReceivableSettlementDtos.AdjustmentRow> receivableAdjustmentMapper() {
        return (rs, rowNum) -> new ReceivableSettlementDtos.AdjustmentRow(
                rs.getLong("id"), rs.getString("adjustment_no"), rs.getLong("customer_id"),
                rs.getString("customer_name"), rs.getLong("sales_order_id"), rs.getString("sales_order_no"),
                rs.getString("adjustment_type"), rs.getBigDecimal("amount"), rs.getDate("occurred_date").toLocalDate(),
                rs.getString("status"), rs.getString("reason"), rs.getString("evidence_ref"),
                rs.getTimestamp("confirmed_at") == null ? null : rs.getTimestamp("confirmed_at").toLocalDateTime(),
                rs.getString("reversal_no"), rs.getString("reversal_reason"),
                rs.getTimestamp("reversed_at") == null ? null : rs.getTimestamp("reversed_at").toLocalDateTime(),
                rs.getTimestamp("created_at").toLocalDateTime()
        );
    }

    private RowMapper<ReceivableSettlementDtos.AcceptanceBillRow> acceptanceBillMapper() {
        return (rs, rowNum) -> new ReceivableSettlementDtos.AcceptanceBillRow(
                rs.getLong("id"), rs.getString("bill_no"), rs.getString("instrument_no"),
                rs.getString("bill_type"), rs.getLong("customer_id"), rs.getString("customer_name"),
                rs.getLong("sales_order_id"), rs.getString("sales_order_no"), rs.getBigDecimal("face_amount"),
                rs.getDate("issue_date").toLocalDate(), rs.getDate("due_date").toLocalDate(),
                rs.getDate("received_date").toLocalDate(), rs.getString("drawer"), rs.getString("acceptor"),
                rs.getLong("settlement_payment_id"), rs.getString("status"),
                rs.getDate("discount_date") == null ? null : rs.getDate("discount_date").toLocalDate(),
                rs.getString("discount_bank"), rs.getBigDecimal("discount_fee"), rs.getBigDecimal("net_proceeds"),
                rs.getBigDecimal("realized_cash_amount"), rs.getString("memo"),
                rs.getTimestamp("created_at").toLocalDateTime(), rs.getTimestamp("updated_at").toLocalDateTime()
        );
    }

    private RowMapper<ReceivableSettlementDtos.AcceptanceEventRow> acceptanceEventMapper() {
        return (rs, rowNum) -> new ReceivableSettlementDtos.AcceptanceEventRow(
                rs.getLong("id"), rs.getString("event_no"), rs.getLong("bill_id"), rs.getString("event_type"),
                rs.getDate("occurred_date").toLocalDate(), rs.getBigDecimal("cash_amount"),
                rs.getBigDecimal("fee_amount"), rs.getObject("business_bank_account_id", Long.class),
                rs.getString("business_bank_account_name"), rs.getString("counterparty"), rs.getString("memo"),
                rs.getTimestamp("created_at").toLocalDateTime()
        );
    }

    private record BatchRow(Long id, Long productId, Long warehouseId, String batchNo,
                            BigDecimal quantityAvailable, BigDecimal unitCost, BigDecimal amountCost) {
    }

    private record OutboundLifecycleRow(Long id, String orderNo, Long salesOrderId, Long customerId,
                                        Long warehouseId, String status, String settlementStatus,
                                        BigDecimal totalAmount, BigDecimal receivableAmount, BigDecimal paidAmount,
                                        String shipmentPaymentPolicy, BigDecimal shipmentControlAmount,
                                        boolean allowOverShipment, String overShipmentReason) {
    }

    private record OutboundLifecycleItemRow(Long id, Long outboundId, Long salesOrderItemId, Long productId,
                                            Long batchId, BigDecimal requestedQuantity, String requestedUnit,
                                            BigDecimal quantity, String baseUnit, BigDecimal packageQuantity,
                                            String packageUnit, String priceUnit, BigDecimal pricingQuantity,
                                            String packagingPlanJson, String selectedPackageInstanceIdsJson,
                                            BigDecimal unitPrice, BigDecimal amount, BigDecimal returnedQuantity,
                                            boolean priceIncludesTax, BigDecimal taxRate, BigDecimal amountExcludingTax,
                                            BigDecimal taxAmount, BigDecimal settlementTotalAmount, BigDecimal taxRoundingDifference,
                                            BigDecimal inventoryUnitCost, BigDecimal inventoryAmountCost) {
    }

    private record DraftOutboundPreparedItem(OutboundCreateRequest.Item item, BatchRow batch,
                                             long salesOrderItemId, String priceUnit,
                                             PackagingDtos.Plan plan, BigDecimal amount) {
    }

    private record AdvanceAllocationRow(Long id, Long sourcePaymentId, BigDecimal amount, String memo) {
    }

    private record ShipmentRelease(BigDecimal requiredAmount, BigDecimal fundedAmount) {
    }

    private record SalesReturnLockRow(Long id, String returnNo, Long outboundId, Long salesOrderId,
                                      Long customerId, Long warehouseId, String status, BigDecimal totalAmount,
                                      BigDecimal receivableReduction, BigDecimal advanceCreditAmount) {
    }

    private record SalesReturnItemRow(Long outboundItemId, Long productId, Long batchId, BigDecimal quantity,
                                      String baseUnit, BigDecimal unitPrice, BigDecimal amount,
                                      BigDecimal inventoryUnitCost, BigDecimal inventoryAmountCost) {
    }

    private record OutboundPayRow(Long id, String orderNo, Long customerId, Long salesOrderId,
                                  BigDecimal receivableAmount, BigDecimal paidAmount) {
    }

    private record PaymentLock(Long id, String paymentNo, Long customerId, Long salesOrderId,
                               Long outboundId, BigDecimal amount, BigDecimal allocatedAmount,
                               BigDecimal unallocatedAmount, String paymentMethod,
                               String accountType, String verificationStatus, String fundNature,
                               String allocationMode) {
    }

    private record SupplierPaymentLock(Long id, String paymentNo, Long supplierId, Long purchaseOrderId,
                                       Long inboundId, BigDecimal amount, BigDecimal allocatedAmount,
                                       BigDecimal unallocatedAmount, String paymentMethod,
                                       String accountType, String verificationStatus, String fundNature,
                                       String allocationMode) {
    }

    private record NormalizedAllocation(String targetType, Long targetId, BigDecimal amount) {
    }

    private record AllocationTarget(String type, Long id) {
    }

    private record CustomerAllocationLock(Long id, String targetType, Long targetId, BigDecimal amount) {
    }

    private record SupplierAllocationLock(Long id, String targetType, Long targetId, BigDecimal amount) {
    }

    private record OutboundReference(Long id, String orderNo, Long partnerId, Long parentOrderId) {
    }

    private record InboundReference(Long id, String orderNo, Long partnerId, Long parentOrderId) {
    }

    private record AllocationDetailHeader(Long id, String paymentNo, Long partnerId, String partnerName,
                                          BigDecimal amount, BigDecimal allocatedAmount,
                                          BigDecimal unallocatedAmount, String paymentMethod,
                                          Long bankAccountId, String bankAccountName, LocalDate businessDate,
                                          String status, String fundNature, String allocationMode,
                                          String reversalNo, String reversalReason, LocalDateTime reversedAt) {
    }

    private record CustomerAdvanceRefundLock(Long id, String refundNo, Long customerId,
                                             BigDecimal amount, String status) {
    }

    private record SalesOrderPayRow(Long id, String orderNo, Long customerId,
                                    BigDecimal totalAmount, BigDecimal receivedAmount, BigDecimal adjustedAmount,
                                    String status) {
    }

    private record ReceivableAdjustmentLockRow(Long id, Long salesOrderId, Long customerId,
                                               String adjustmentType, BigDecimal amount, String status) {
    }

    private record AcceptanceBillLockRow(Long id, String instrumentNo, Long customerId, Long salesOrderId,
                                         BigDecimal faceAmount, Long settlementPaymentId, String status,
                                         String acceptor, BigDecimal netProceeds) {
    }

    private record AcceptanceEventRef(Long id, String no) {
    }

    private record SalesOrderEditRow(Long id, String orderNo, Long customerId,
                                     BigDecimal totalAmount, BigDecimal receivedAmount, String status,
                                     String paymentTerm, String shipmentPaymentPolicy,
                                     BigDecimal requiredDepositAmount, LocalDate businessDate,
                                     Integer currentRevisionNo) {
    }

    private record PreparedSalesItem(AlignedDtos.SalesOrderCreate.Item item, String priceUnit,
                                     PackagingDtos.Plan plan, BigDecimal price, BigDecimal amount,
                                     TaxPriceContract.Line taxLine, PriceCenterDtos.PriceSnapshot priceSnapshot) {
    }

    private record PreparedSalesOrder(BigDecimal originalAmount, BigDecimal discountAmount,
                                      BigDecimal adjustmentAmount, BigDecimal totalAmount,
                                      TaxPriceContract.Document tax, List<PreparedSalesItem> items) {
    }

    private record SalesOrderPaymentTerms(String paymentTerm, String depositMode,
                                          BigDecimal depositRate, BigDecimal requiredAmount) {
    }

    private record PurchasePayRow(Long id, String orderNo, Long supplierId,
                                  BigDecimal totalAmount, BigDecimal paidAmount, String status) {
    }

    private record PurchaseEditRow(Long id, String orderNo, Long supplierId, BigDecimal receivedQuantity,
                                   BigDecimal paidAmount, String status, LocalDate businessDate,
                                   Integer currentRevisionNo) {
    }

    private record PreparedPurchaseItem(PurchaseCreateRequest.Item item, String priceUnit,
                                        PackagingDtos.Plan plan, BigDecimal price, BigDecimal amount,
                                        TaxPriceContract.Line taxLine, PriceCenterDtos.PriceSnapshot priceSnapshot) {
    }

    private record PreparedPurchaseOrder(BigDecimal totalQuantity, BigDecimal originalAmount,
                                         BigDecimal discountAmount, BigDecimal adjustmentAmount,
                                         BigDecimal totalAmount, TaxPriceContract.Document tax,
                                         List<PreparedPurchaseItem> items) {
    }

    private record OrderTaxTerms(boolean priceIncludesTax, BigDecimal defaultTaxRate,
                                 Map<Long, BigDecimal> itemTaxRates, String sourceSnapshot) {
        private BigDecimal rateFor(Long itemId) {
            return itemId == null ? defaultTaxRate : itemTaxRates.getOrDefault(itemId, defaultTaxRate);
        }
    }

    private record FlowTaxItem(Long id, Long sourceItemId, BigDecimal pricingQuantity, BigDecimal enteredPrice) {}
    private record ReturnTaxSnapshot(BigDecimal amount, BigDecimal amountExcludingTax,
                                     BigDecimal taxAmount, BigDecimal roundingDifference) {}

    private record AmendmentItemState(Long id, Long productId, BigDecimal fulfilledBase,
                                      BigDecimal fulfilledRequested) {
    }

    private record SupplierProductPriceInput(Long productId, String priceUnit, BigDecimal unitPrice) {
    }

    private record InboundPayRow(Long id, String orderNo, Long purchaseOrderId, Long supplierId,
                                 BigDecimal payableAmount, BigDecimal paidAmount, String status) {
    }

    private record InboundAuditRow(Long id, String orderNo, Long purchaseOrderId, Long supplierId,
                                   Long warehouseId, LocalDate businessDate, BigDecimal totalAmount, String status) {
    }

    private record InboundAuditItem(Long id, Long productId, Long purchaseOrderItemId, String batchNo, String serialNumber,
                                    BigDecimal requestedQuantity, BigDecimal quantity, String baseUnit,
                                    BigDecimal packageQuantity, String packageUnit, String packagingPlanJson,
                                    String packageBarcode, BigDecimal unitCost,
                                    BigDecimal amount, LocalDate productionDate, String shelfLifeMode,
                                    Integer shelfLifeValue, String shelfLifeUnit, LocalDate expirationDate) {
    }

    private record InboundReversalItem(Long id, Long productId, Long purchaseOrderItemId, Long batchId,
                                       BigDecimal requestedQuantity, BigDecimal quantity, String baseUnit,
                                       BigDecimal inventoryUnitCost, BigDecimal inventoryAmountCost,
                                       BigDecimal quantityIn, BigDecimal quantityAvailable,
                                       BigDecimal unitCost, BigDecimal amountCost) {
    }

    private record ShelfLifeSnapshot(String mode, Integer value, String unit, LocalDate expirationDate) {
    }

    private record SettlementDueSnapshot(int days, LocalDate dueDate) {
    }

    private record PreparedInboundItem(InboundCreateRequest.Item item, long purchaseOrderItemId,
                                       String priceUnit, PackagingDtos.Plan plan, BigDecimal amount,
                                       ShelfLifeSnapshot shelfLife) {
    }

    private record PreparedInboundOrder(Long purchaseOrderId, BigDecimal totalQuantity,
                                        BigDecimal originalAmount, BigDecimal totalAmount,
                                        List<PreparedInboundItem> items) {
    }

    private record SnapshotAttachmentSource(Long id, String fileName, String contentType, long fileSize,
                                            String sha256, String storagePath, byte[] legacyContent,
                                            Timestamp uploadedAt) {
    }

    private record SnapshotAttachmentContent(String fileName, String contentType, String storagePath,
                                             byte[] legacyContent) {
    }

    private record InboundPackageInstanceRow(Long id, String packageCode, String unitName,
                                             BigDecimal actualBaseQuantity, String baseUnit) {
    }

    private record ProductPurchaseBase(Long id, Long productSeriesId, String unit, BigDecimal defaultPrice,
                                       String status, String identityStatus,
                                       Long packageUnitCategoryId, Long priceUnitCategoryId) {
    }
}
