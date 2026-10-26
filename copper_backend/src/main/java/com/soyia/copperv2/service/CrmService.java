package com.soyia.copperv2.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.soyia.copperv2.dto.AlignedDtos;
import com.soyia.copperv2.dto.CrmDtos;
import com.soyia.copperv2.dto.PackagingDtos;
import com.soyia.copperv2.dto.PriceCenterDtos;
import com.soyia.copperv2.security.ForbiddenException;
import com.soyia.copperv2.security.SecurityContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
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
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class CrmService {
    private static final Set<String> LEAD_STATUSES = Set.of("OPEN", "QUALIFIED", "DISQUALIFIED");
    private static final List<String> OPPORTUNITY_STAGES = List.of("QUALIFY", "NEEDS", "PROPOSAL", "NEGOTIATION", "WON", "LOST");
    private static final Set<String> TARGET_TYPES = Set.of("LEAD", "CUSTOMER", "OPPORTUNITY", "QUOTE");
    private static final Set<String> QUOTE_ACTIONABLE = Set.of("DRAFT", "ACTIVE", "ACCEPTED", "REJECTED", "CANCELLED", "REVISED");

    private final JdbcTemplate jdbcTemplate;
    private final PackagingService packagingService;
    private final CopperService copperService;
    private final ObjectMapper objectMapper;
    private final PriceCenterService priceCenterService;

    public CrmService(JdbcTemplate jdbcTemplate, PackagingService packagingService,
                      CopperService copperService, ObjectMapper objectMapper,
                      PriceCenterService priceCenterService) {
        this.jdbcTemplate = jdbcTemplate;
        this.packagingService = packagingService;
        this.copperService = copperService;
        this.objectMapper = objectMapper;
        this.priceCenterService = priceCenterService;
    }

    public List<CrmDtos.OptionRow> options(String optionType, boolean includeDisabled) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                select id,option_type,option_code,option_name,sort_no,built_in,status
                from crm_option where 1=1
                """);
        if (StringUtils.hasText(optionType)) {
            sql.append(" and option_type=?");
            args.add(optionType.trim().toUpperCase());
        }
        if (!includeDisabled) sql.append(" and status='ENABLED'");
        sql.append(" order by option_type,sort_no,id");
        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> new CrmDtos.OptionRow(
                rs.getLong("id"), rs.getString("option_type"), rs.getString("option_code"),
                rs.getString("option_name"), rs.getInt("sort_no"), rs.getBoolean("built_in"),
                rs.getString("status")), args.toArray());
    }

    @Transactional
    public Long saveOption(Long id, CrmDtos.OptionSave request) {
        String type = requiredCode(request.optionType(), "选项类型");
        String code = requiredCode(request.optionCode(), "选项编码");
        String status = "DISABLED".equalsIgnoreCase(request.status()) ? "DISABLED" : "ENABLED";
        if (id == null) {
            return insert("""
                    insert into crm_option(option_type,option_code,option_name,sort_no,built_in,status)
                    values(?,?,?,?,0,?)
                    """, type, code, request.optionName().trim(), nvl(request.sortNo()), status);
        }
        Integer builtIn = jdbcTemplate.queryForObject("select built_in from crm_option where id=? for update", Integer.class, id);
        if (builtIn == null) throw new BusinessException("CRM 选项不存在");
        jdbcTemplate.update("""
                update crm_option set option_name=?,sort_no=?,status=? where id=?
                """, request.optionName().trim(), nvl(request.sortNo()), status, id);
        return id;
    }

    public CrmDtos.Dashboard dashboard() {
        String ownerClause = ownerClause("o.owner_user_id");
        Object[] ownerArgs = ownerArgs();
        BigDecimal pipeline = scalarDecimal("select coalesce(sum(o.estimated_amount),0) from crm_opportunity o where o.stage not in ('WON','LOST')" + ownerClause, ownerArgs);
        int openOpportunities = scalarInt("select count(*) from crm_opportunity o where o.stage not in ('WON','LOST')" + ownerClause, ownerArgs);
        String leadOwnerClause = ownerClause("l.owner_user_id");
        int openLeads = scalarInt("select count(*) from crm_lead l where l.status='OPEN'" + leadOwnerClause, ownerArgs());
        String planOwnerClause = ownerClause("p.owner_user_id");
        int overdue = scalarInt("select count(*) from crm_followup_plan p where p.status='OPEN' and p.due_at<now()" + planOwnerClause, ownerArgs());
        int today = scalarInt("select count(*) from crm_followup_plan p where p.status='OPEN' and date(p.due_at)=current_date" + planOwnerClause, ownerArgs());
        String quoteOwnerJoin = canViewAll() ? "" : " and coalesce(o.owner_user_id,q.created_by)=?";
        Object[] quoteArgs = canViewAll() ? new Object[0] : new Object[]{principal().userId()};
        int activeQuotes = scalarInt("""
                select count(*) from crm_quote q
                left join crm_opportunity o on o.id=q.opportunity_id
                where q.status='ACTIVE'
                """ + quoteOwnerJoin, quoteArgs);
        BigDecimal acceptedAmount = scalarDecimal("""
                select coalesce(sum(q.total_amount),0) from crm_quote q
                left join crm_opportunity o on o.id=q.opportunity_id
                where q.status='ACCEPTED'
                """ + quoteOwnerJoin, quoteArgs);
        int expiringQuoteCount = scalarInt("""
                select count(*) from crm_quote q
                left join crm_opportunity o on o.id=q.opportunity_id
                where q.status='ACTIVE' and q.valid_until<=date_add(current_date,interval 7 day)
                """ + quoteOwnerJoin, quoteArgs);
        List<CrmDtos.QuoteRow> expiringQuoteRows = jdbcTemplate.query(quoteSelect() + """
                where q.status='ACTIVE' and q.valid_until<=date_add(current_date,interval 7 day)
                """ + quoteOwnerJoin + " order by q.valid_until,q.updated_at desc limit 8", quoteMapper(), quoteArgs);
        List<CrmDtos.StageSummary> stages = jdbcTemplate.query("""
                select o.stage,count(*) item_count,coalesce(sum(o.estimated_amount),0) total_amount
                from crm_opportunity o where 1=1
                """ + ownerClause + " group by o.stage", (rs, rowNum) -> new CrmDtos.StageSummary(
                rs.getString("stage"), rs.getInt("item_count"), rs.getBigDecimal("total_amount")), ownerArgs);
        List<CrmDtos.PlanRow> urgentPlans = plans("OPEN", null, true).stream().limit(10).toList();
        LocalDate currentDate = LocalDate.now();
        List<CrmDtos.CustomerAnalysisRow> analyses = customerAnalysisRows();
        List<CrmDtos.CustomerAttentionRow> repurchaseRows = analyses.stream()
                .filter(row -> row.repurchaseReminderEnabled() && !"LOST".equals(row.lifecycleStage()))
                .filter(row -> row.lastBusinessDate() != null)
                .filter(row -> row.latestOrderDate() == null || !row.latestOrderDate().isAfter(row.lastBusinessDate()))
                .filter(row -> !row.lastBusinessDate().plusDays(row.repurchaseCycleDays()).isAfter(currentDate))
                .sorted((left, right) -> left.lastBusinessDate().plusDays(left.repurchaseCycleDays())
                        .compareTo(right.lastBusinessDate().plusDays(right.repurchaseCycleDays())))
                .map(row -> customerAttention(row, row.lastBusinessDate().plusDays(row.repurchaseCycleDays()), currentDate))
                .toList();
        List<CrmDtos.CustomerAttentionRow> receivableRows = analyses.stream()
                .filter(row -> row.receivableBalance().signum() > 0)
                .sorted((left, right) -> {
                    int overdueCompare = right.overdueAmount().compareTo(left.overdueAmount());
                    return overdueCompare != 0 ? overdueCompare : right.receivableBalance().compareTo(left.receivableBalance());
                })
                .map(row -> customerAttention(row, null, currentDate))
                .toList();
        BigDecimal receivableReminderAmount = receivableRows.stream().map(CrmDtos.CustomerAttentionRow::receivableBalance)
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
        List<CrmDtos.CustomerAnalysisRow> ranking = analyses.stream().limit(8).toList();
        return new CrmDtos.Dashboard(pipeline, openOpportunities, openLeads, overdue, today,
                activeQuotes, acceptedAmount, expiringQuoteCount, repurchaseRows.size(),
                receivableRows.size(), receivableReminderAmount, stages, urgentPlans,
                expiringQuoteRows, repurchaseRows.stream().limit(8).toList(),
                receivableRows.stream().limit(8).toList(), ranking);
    }

    private CrmDtos.CustomerAttentionRow customerAttention(CrmDtos.CustomerAnalysisRow row,
                                                            LocalDate suggestedDate, LocalDate currentDate) {
        int overdueDays = suggestedDate == null ? 0
                : (int) Math.max(ChronoUnit.DAYS.between(suggestedDate, currentDate), 0);
        return new CrmDtos.CustomerAttentionRow(row.customerProfileId(), row.partnerId(), row.partnerName(),
                row.contactName(), row.contactPhone(), row.receivableBalance(), row.overdueAmount(),
                row.lastBusinessDate(), suggestedDate, overdueDays, row.repurchaseCycleDays(), row.recentProducts());
    }

    public List<CrmDtos.DuplicateCandidate> duplicateCandidates(String companyName, String phone, String email) {
        String company = normalized(companyName);
        String normalizedPhone = normalizePhone(phone);
        String normalizedEmail = normalized(email);
        if (!StringUtils.hasText(company) && !StringUtils.hasText(normalizedPhone) && !StringUtils.hasText(normalizedEmail)) return List.of();
        List<CrmDtos.DuplicateCandidate> rows = new ArrayList<>();
        if (StringUtils.hasText(company)) {
            rows.addAll(jdbcTemplate.query("""
                    select 'PARTNER_NAME' match_type,p.id source_id,p.business_no source_no,p.name,
                           p.phone,'' email,p.status from partner p
                    where lower(trim(p.name))=lower(trim(?)) limit 10
                    """, duplicateMapper(), companyName.trim()));
            rows.addAll(jdbcTemplate.query("""
                    select 'LEAD_COMPANY' match_type,l.id source_id,l.lead_no source_no,l.company_name name,
                           l.phone,l.email,l.status from crm_lead l
                    where lower(trim(l.company_name))=lower(trim(?)) limit 10
                    """, duplicateMapper(), companyName.trim()));
        }
        if (StringUtils.hasText(normalizedPhone)) {
            rows.addAll(jdbcTemplate.query("""
                    select 'PARTNER_PHONE' match_type,p.id source_id,p.business_no source_no,p.name,
                           p.phone,'' email,p.status from partner p
                    where replace(replace(replace(replace(p.phone,' ',''),'-',''),'(',''),')','')=? limit 10
                    """, duplicateMapper(), normalizedPhone));
            rows.addAll(jdbcTemplate.query("""
                    select 'LEAD_PHONE' match_type,l.id source_id,l.lead_no source_no,l.company_name name,
                           l.phone,l.email,l.status from crm_lead l
                    where replace(replace(replace(replace(l.phone,' ',''),'-',''),'(',''),')','')=? limit 10
                    """, duplicateMapper(), normalizedPhone));
        }
        if (StringUtils.hasText(normalizedEmail)) {
            rows.addAll(jdbcTemplate.query("""
                    select 'CONTACT_EMAIL' match_type,c.id source_id,cast(c.partner_id as char) source_no,
                           concat(p.name,' / ',c.name) name,c.phone,c.email,c.status
                    from partner_contact c join partner p on p.id=c.partner_id
                    where lower(trim(c.email))=? limit 10
                    """, duplicateMapper(), normalizedEmail));
            rows.addAll(jdbcTemplate.query("""
                    select 'LEAD_EMAIL' match_type,l.id source_id,l.lead_no source_no,l.company_name name,
                           l.phone,l.email,l.status from crm_lead l where lower(trim(l.email))=? limit 10
                    """, duplicateMapper(), normalizedEmail));
        }
        return rows.stream().collect(LinkedHashMap<String, CrmDtos.DuplicateCandidate>::new,
                (map, row) -> map.putIfAbsent(row.matchType() + ':' + row.sourceId(), row), Map::putAll)
                .values().stream().limit(20).toList();
    }

    public List<CrmDtos.LeadRow> leads(String status, String keyword) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder(leadSelect()).append(" where 1=1");
        if (StringUtils.hasText(status)) { sql.append(" and l.status=?"); args.add(status.trim().toUpperCase()); }
        if (StringUtils.hasText(keyword)) {
            sql.append(" and (l.lead_no like ? or l.subject like ? or l.company_name like ? or l.contact_name like ? or l.phone like ?)");
            String value = '%' + keyword.trim() + '%';
            for (int i = 0; i < 5; i++) args.add(value);
        }
        appendOwnerFilter(sql, args, "l.owner_user_id");
        sql.append(" order by case l.status when 'OPEN' then 0 else 1 end,l.updated_at desc limit 500");
        return jdbcTemplate.query(sql.toString(), leadMapper(), args.toArray());
    }

    public CrmDtos.LeadDetail lead(Long id) {
        CrmDtos.LeadRow row = one(leadSelect() + " where l.id=?", leadMapper(), id, "线索不存在");
        assertOwnerAllowed(row.ownerUserId());
        return new CrmDtos.LeadDetail(row, activities("LEAD", id), plansForTarget("LEAD", id), events("LEAD", id));
    }

    @Transactional
    public Long saveLead(Long id, CrmDtos.LeadSave request) {
        SecurityContext.Principal actor = principal();
        validateLeadStatus("OPEN");
        if (id == null && request.partnerId() == null
                && !duplicateCandidates(request.companyName(), request.phone(), request.email()).isEmpty()) {
            throw new BusinessException("发现同名、同电话或同邮箱记录，请先核对重复候选；确认不是同一主体后再调整主档信息");
        }
        if (request.partnerId() != null) requireCustomerPartner(request.partnerId());
        if (request.contactId() != null) requireContact(request.contactId(), request.partnerId());
        if (id == null) {
            long leadId = insert("""
                    insert into crm_lead
                    (lead_no,subject,company_name,contact_name,phone,email,wechat,source_code,demand,
                     estimated_amount,owner_user_id,owner_name,status,partner_id,contact_id,created_by,updated_by)
                    values(?,?,?,?,?,?,?,?,?,?,?,?, 'OPEN',?,?,?,?)
                    """, nextNo("LEAD", "LX"), request.subject().trim(), blank(request.companyName()),
                    blank(request.contactName()), blank(request.phone()), blank(request.email()), blank(request.wechat()),
                    optionCode("LEAD_SOURCE", request.sourceCode(), "OTHER"), blank(request.demand()), money(request.estimatedAmount()),
                    actor.userId(), actor.employeeName(), request.partnerId(), request.contactId(), actor.userId(), actor.userId());
            event("LEAD", leadId, "LEAD_CREATED", request.partnerId(), Map.of("subject", request.subject().trim()));
            return leadId;
        }
        CrmDtos.LeadRow current = lockLead(id);
        assertOwnerAllowed(current.ownerUserId());
        if (!"OPEN".equals(current.status())) throw new BusinessException("只有待跟进线索可以修改；已完成状态请通过重新激活保留历史");
        jdbcTemplate.update("""
                update crm_lead set subject=?,company_name=?,contact_name=?,phone=?,email=?,wechat=?,source_code=?,
                    demand=?,estimated_amount=?,partner_id=?,contact_id=?,updated_by=? where id=?
                """, request.subject().trim(), blank(request.companyName()), blank(request.contactName()), blank(request.phone()),
                blank(request.email()), blank(request.wechat()), optionCode("LEAD_SOURCE", request.sourceCode(), "OTHER"),
                blank(request.demand()), money(request.estimatedAmount()), request.partnerId(), request.contactId(), actor.userId(), id);
        event("LEAD", id, "LEAD_UPDATED", request.partnerId(), Map.of("subject", request.subject().trim()));
        return id;
    }

    @Transactional
    public Long qualifyLead(Long id, CrmDtos.LeadQualify request) {
        CrmDtos.LeadRow lead = lockLead(id);
        assertOwnerAllowed(lead.ownerUserId());
        if ("QUALIFIED".equals(lead.status())) return id;
        if (!"OPEN".equals(lead.status())) throw new BusinessException("只有待跟进线索可以转为潜在客户");
        requireCustomerPartner(request.partnerId());
        if (request.contactId() != null) requireContact(request.contactId(), request.partnerId());
        Long profileId = ensureCustomerProfile(request.partnerId(), lead.sourceCode());
        Long opportunityId = null;
        if (request.createOpportunity()) {
            String title = StringUtils.hasText(request.opportunityTitle()) ? request.opportunityTitle().trim() : lead.subject();
            opportunityId = createOpportunity(new CrmDtos.OpportunitySave(request.partnerId(), request.contactId(), id,
                    title, BigDecimal.TEN, lead.estimatedAmount(), request.expectedCloseDate(), lead.demand(), ""));
        }
        jdbcTemplate.update("""
                update crm_lead set status='QUALIFIED',partner_id=?,contact_id=?,qualified_profile_id=?,
                    qualified_opportunity_id=?,qualified_at=now(),disqualified_reason_code='',disqualified_reason='',
                    disqualified_at=null,updated_by=? where id=?
                """, request.partnerId(), request.contactId(), profileId, opportunityId, principal().userId(), id);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("partnerId", request.partnerId());
        payload.put("profileId", profileId);
        payload.put("opportunityId", opportunityId == null ? 0 : opportunityId);
        event("LEAD", id, "LEAD_QUALIFIED", request.partnerId(), payload);
        return id;
    }

    @Transactional
    public Long disqualifyLead(Long id, CrmDtos.ReasonAction request) {
        CrmDtos.LeadRow lead = lockLead(id);
        assertOwnerAllowed(lead.ownerUserId());
        if ("DISQUALIFIED".equals(lead.status())) return id;
        if (!"OPEN".equals(lead.status())) throw new BusinessException("只有待跟进线索可以判定无效");
        jdbcTemplate.update("""
                update crm_lead set status='DISQUALIFIED',disqualified_reason_code=?,disqualified_reason=?,
                    disqualified_at=now(),updated_by=? where id=?
                """, optionCode("LOSS_REASON", request.reasonCode(), "OTHER"), request.reason().trim(), principal().userId(), id);
        event("LEAD", id, "LEAD_DISQUALIFIED", lead.partnerId(), Map.of("reason", request.reason().trim()));
        return id;
    }

    @Transactional
    public Long reactivateLead(Long id, CrmDtos.ReasonAction request) {
        CrmDtos.LeadRow lead = lockLead(id);
        assertOwnerAllowed(lead.ownerUserId());
        if ("OPEN".equals(lead.status())) return id;
        jdbcTemplate.update("""
                update crm_lead set status='OPEN',reactivated_reason=?,reactivated_at=now(),
                    qualified_profile_id=null,qualified_opportunity_id=null,qualified_at=null,updated_by=? where id=?
                """, request.reason().trim(), principal().userId(), id);
        event("LEAD", id, "LEAD_REACTIVATED", lead.partnerId(), Map.of("reason", request.reason().trim()));
        return id;
    }

    public List<CrmDtos.CustomerRow> customers(String lifecycleStage, String tierCode, String keyword) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder(customerSelect()).append(" where cp.status='ENABLED'");
        if (StringUtils.hasText(lifecycleStage)) { sql.append(" and cp.lifecycle_stage=?"); args.add(lifecycleStage.trim().toUpperCase()); }
        if (StringUtils.hasText(tierCode)) { sql.append(" and cp.tier_code=?"); args.add(tierCode.trim().toUpperCase()); }
        if (StringUtils.hasText(keyword)) {
            sql.append(" and (p.business_no like ? or p.name like ? or p.contact_person like ? or p.phone like ?)");
            String value = '%' + keyword.trim() + '%'; for (int i = 0; i < 4; i++) args.add(value);
        }
        appendOwnerFilter(sql, args, "cp.owner_user_id");
        sql.append(" order by cp.next_action_date is null,cp.next_action_date,cp.updated_at desc limit 500");
        return jdbcTemplate.query(sql.toString(), customerMapper(), args.toArray());
    }

    public CrmDtos.CustomerDetail customer(Long id) {
        CrmDtos.CustomerRow row = one(customerSelect() + " where cp.id=?", customerMapper(), id, "CRM 客户档案不存在");
        assertOwnerAllowed(row.ownerUserId());
        Long partnerId = row.partnerId();
        List<CrmDtos.ContactRow> contacts = jdbcTemplate.query("""
                select id,name,job_title,department,phone,wechat,email,preferred_method,first_choice,status
                from partner_contact where partner_id=? order by first_choice desc,status,id
                """, (rs, rowNum) -> new CrmDtos.ContactRow(rs.getLong("id"), rs.getString("name"),
                rs.getString("job_title"), rs.getString("department"), rs.getString("phone"), rs.getString("wechat"),
                rs.getString("email"), rs.getString("preferred_method"), rs.getBoolean("first_choice"), rs.getString("status")), partnerId);
        return new CrmDtos.CustomerDetail(row, customerAnalysis(partnerId), contacts,
                opportunities(null, partnerId, null), quotes(null, partnerId, null), activities("CUSTOMER", partnerId),
                plansForTarget("CUSTOMER", partnerId), eventsByPartner(partnerId));
    }

    @Transactional
    public Long saveCustomerProfile(Long id, CrmDtos.CustomerProfileSave request) {
        requireCustomerPartner(request.partnerId());
        SecurityContext.Principal actor = principal();
        String stage = lifecycleStage(request.lifecycleStage());
        String tier = optionCode("CUSTOMER_TIER", request.tierCode(), "NORMAL");
        String source = optionCode("LEAD_SOURCE", request.sourceCode(), "OTHER");
        boolean repurchaseReminderEnabled = !Boolean.FALSE.equals(request.repurchaseReminderEnabled());
        int repurchaseCycleDays = request.repurchaseCycleDays() == null ? 30 : request.repurchaseCycleDays();
        if (id == null) {
            List<Long> existing = jdbcTemplate.query("select id from crm_customer_profile where partner_id=?", (rs, rowNum) -> rs.getLong(1), request.partnerId());
            if (!existing.isEmpty()) id = existing.get(0);
        }
        if (id == null) {
            long profileId = insert("""
                    insert into crm_customer_profile
                    (partner_id,lifecycle_stage,tier_code,source_code,owner_user_id,owner_name,tags,annual_potential,
                     preferred_category,competitor,next_action,next_action_date,repurchase_reminder_enabled,
                     repurchase_cycle_days,won_reason_code,lost_reason_code,lost_reason,status,created_by,updated_by)
                    values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'ENABLED',?,?)
                    """, request.partnerId(), stage, tier, source, actor.userId(), actor.employeeName(), blank(request.tags()),
                    money(request.annualPotential()), blank(request.preferredCategory()), blank(request.competitor()),
                    blank(request.nextAction()), date(request.nextActionDate()), repurchaseReminderEnabled, repurchaseCycleDays,
                    blank(request.wonReasonCode()),
                    blank(request.lostReasonCode()), blank(request.lostReason()), actor.userId(), actor.userId());
            event("CUSTOMER", request.partnerId(), "CUSTOMER_PROFILE_CREATED", request.partnerId(), Map.of("profileId", profileId));
            return profileId;
        }
        CrmDtos.CustomerRow current = one(customerSelect() + " where cp.id=? for update", customerMapper(), id, "CRM 客户档案不存在");
        assertOwnerAllowed(current.ownerUserId());
        if (!Objects.equals(current.partnerId(), request.partnerId())) throw new BusinessException("CRM 客户档案不能更换往来单位主档");
        jdbcTemplate.update("""
                update crm_customer_profile set lifecycle_stage=?,tier_code=?,source_code=?,
                    owner_user_id=coalesce(owner_user_id,?),owner_name=case when owner_user_id is null then ? else owner_name end,
                    tags=?,annual_potential=?,preferred_category=?,competitor=?,next_action=?,next_action_date=?,
                    repurchase_reminder_enabled=?,repurchase_cycle_days=?,won_reason_code=?,lost_reason_code=?,lost_reason=?,
                    updated_by=? where id=?
                """, stage, tier, source, actor.userId(), actor.employeeName(), blank(request.tags()), money(request.annualPotential()),
                blank(request.preferredCategory()), blank(request.competitor()), blank(request.nextAction()), date(request.nextActionDate()),
                repurchaseReminderEnabled, repurchaseCycleDays, blank(request.wonReasonCode()),
                blank(request.lostReasonCode()), blank(request.lostReason()), actor.userId(), id);
        event("CUSTOMER", request.partnerId(), "CUSTOMER_PROFILE_UPDATED", request.partnerId(), Map.of("profileId", id, "stage", stage));
        return id;
    }

    public List<CrmDtos.OpportunityRow> opportunities(String stage, Long partnerId, String keyword) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder(opportunitySelect()).append(" where 1=1");
        if (StringUtils.hasText(stage)) { sql.append(" and o.stage=?"); args.add(stage.trim().toUpperCase()); }
        if (partnerId != null) { sql.append(" and o.partner_id=?"); args.add(partnerId); }
        if (StringUtils.hasText(keyword)) {
            sql.append(" and (o.opportunity_no like ? or o.title like ? or p.name like ?)");
            String value = '%' + keyword.trim() + '%'; args.add(value); args.add(value); args.add(value);
        }
        appendOwnerFilter(sql, args, "o.owner_user_id");
        sql.append(" order by field(o.stage,'NEGOTIATION','PROPOSAL','NEEDS','QUALIFY','WON','LOST'),o.expected_close_date is null,o.expected_close_date,o.updated_at desc limit 500");
        return jdbcTemplate.query(sql.toString(), opportunityMapper(), args.toArray());
    }

    public CrmDtos.OpportunityDetail opportunity(Long id) {
        CrmDtos.OpportunityRow row = one(opportunitySelect() + " where o.id=?", opportunityMapper(), id, "商机不存在");
        assertOwnerAllowed(row.ownerUserId());
        return new CrmDtos.OpportunityDetail(row, quotes(null, null, id), activities("OPPORTUNITY", id),
                plansForTarget("OPPORTUNITY", id), events("OPPORTUNITY", id));
    }

    @Transactional
    public Long saveOpportunity(Long id, CrmDtos.OpportunitySave request) {
        if (id == null) return createOpportunity(request);
        CrmDtos.OpportunityRow current = lockOpportunity(id);
        assertOwnerAllowed(current.ownerUserId());
        if (Set.of("WON", "LOST").contains(current.stage())) throw new BusinessException("已成交或已流失商机不能直接修改，请先按原因重新激活");
        requireCustomerPartner(request.partnerId());
        if (!Objects.equals(current.partnerId(), request.partnerId())) throw new BusinessException("商机不能更换客户主档；请关闭原商机后重新创建");
        if (request.contactId() != null) requireContact(request.contactId(), request.partnerId());
        jdbcTemplate.update("""
                update crm_opportunity set contact_id=?,title=?,probability=?,estimated_amount=?,expected_close_date=?,
                    demand=?,competitor=?,updated_by=? where id=?
                """, request.contactId(), request.title().trim(), percent(request.probability()), money(request.estimatedAmount()),
                date(request.expectedCloseDate()), blank(request.demand()), blank(request.competitor()), principal().userId(), id);
        event("OPPORTUNITY", id, "OPPORTUNITY_UPDATED", current.partnerId(), Map.of("title", request.title().trim()));
        return id;
    }

    @Transactional
    public Long createOpportunity(CrmDtos.OpportunitySave request) {
        requireCustomerPartner(request.partnerId());
        if (request.contactId() != null) requireContact(request.contactId(), request.partnerId());
        if (request.sourceLeadId() != null) {
            CrmDtos.LeadRow lead = lockLead(request.sourceLeadId());
            assertOwnerAllowed(lead.ownerUserId());
        }
        SecurityContext.Principal actor = principal();
        ensureCustomerProfile(request.partnerId(), "OTHER");
        long id = insert("""
                insert into crm_opportunity
                (opportunity_no,partner_id,contact_id,source_lead_id,title,stage,probability,estimated_amount,
                 expected_close_date,owner_user_id,owner_name,demand,competitor,created_by,updated_by)
                values(?,?,?,?,?,'QUALIFY',?,?,?,?,?,?,?,?,?)
                """, nextNo("OPPORTUNITY", "SJ"), request.partnerId(), request.contactId(), request.sourceLeadId(),
                request.title().trim(), percent(request.probability()), money(request.estimatedAmount()), date(request.expectedCloseDate()),
                actor.userId(), actor.employeeName(), blank(request.demand()), blank(request.competitor()), actor.userId(), actor.userId());
        event("OPPORTUNITY", id, "OPPORTUNITY_CREATED", request.partnerId(), Map.of("title", request.title().trim()));
        return id;
    }

    @Transactional
    public Long changeOpportunityStage(Long id, CrmDtos.StageChange request) {
        CrmDtos.OpportunityRow current = lockOpportunity(id);
        assertOwnerAllowed(current.ownerUserId());
        String target = request.stage().trim().toUpperCase();
        if (!OPPORTUNITY_STAGES.contains(target)) throw new BusinessException("不支持的商机阶段");
        if (target.equals(current.stage())) return id;
        if (Set.of("WON", "LOST").contains(current.stage())) {
            if (!"QUALIFY".equals(target) || !StringUtils.hasText(request.reason())) {
                throw new BusinessException("已关闭商机只能填写原因后重新激活到资格确认阶段");
            }
            jdbcTemplate.update("""
                    update crm_opportunity set stage='QUALIFY',probability=10.00,reactivated_reason=?,reactivated_at=now(),
                        won_at=null,lost_at=null,updated_by=? where id=?
                    """, request.reason().trim(), principal().userId(), id);
            event("OPPORTUNITY", id, "OPPORTUNITY_REACTIVATED", current.partnerId(), Map.of("reason", request.reason().trim()));
            return id;
        }
        int currentIndex = OPPORTUNITY_STAGES.indexOf(current.stage());
        int targetIndex = OPPORTUNITY_STAGES.indexOf(target);
        if (targetIndex < currentIndex) throw new BusinessException("商机阶段不能无痕倒退；请保留原商机并新建后续商机，或关闭后按原因重新激活");
        BigDecimal probability = switch (target) {
            case "QUALIFY" -> BigDecimal.TEN;
            case "NEEDS" -> BigDecimal.valueOf(30);
            case "PROPOSAL" -> BigDecimal.valueOf(50);
            case "NEGOTIATION" -> BigDecimal.valueOf(75);
            case "WON" -> BigDecimal.valueOf(100);
            default -> BigDecimal.ZERO;
        };
        if (Set.of("WON", "LOST").contains(target) && !StringUtils.hasText(request.reason())) {
            throw new BusinessException("成交或流失必须填写原因，便于复盘和审计");
        }
        String reasonCode = Set.of("WON", "LOST").contains(target)
                ? optionCode("WON".equals(target) ? "WIN_REASON" : "LOSS_REASON", request.reasonCode(), "OTHER") : "";
        jdbcTemplate.update("""
                update crm_opportunity set stage=?,probability=?,
                    win_reason_code=case when ?='WON' then ? else win_reason_code end,
                    win_reason=case when ?='WON' then ? else win_reason end,
                    loss_reason_code=case when ?='LOST' then ? else loss_reason_code end,
                    loss_reason=case when ?='LOST' then ? else loss_reason end,
                    won_at=case when ?='WON' then now() else won_at end,
                    lost_at=case when ?='LOST' then now() else lost_at end,updated_by=? where id=?
                """, target, probability, target, reasonCode, target, blank(request.reason()), target, reasonCode,
                target, blank(request.reason()), target, target, principal().userId(), id);
        if ("WON".equals(target)) jdbcTemplate.update("update crm_customer_profile set lifecycle_stage='ACTIVE',won_reason_code=? where partner_id=?", reasonCode, current.partnerId());
        event("OPPORTUNITY", id, "OPPORTUNITY_STAGE_CHANGED", current.partnerId(), Map.of(
                "from", current.stage(), "to", target, "reason", blank(request.reason())));
        return id;
    }

    public List<CrmDtos.QuoteRow> quotes(String status, Long partnerId, Long opportunityId) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder(quoteSelect()).append(" where 1=1");
        if (StringUtils.hasText(status)) { sql.append(" and q.status=?"); args.add(status.trim().toUpperCase()); }
        if (partnerId != null) { sql.append(" and q.partner_id=?"); args.add(partnerId); }
        if (opportunityId != null) { sql.append(" and q.opportunity_id=?"); args.add(opportunityId); }
        if (!canViewAll()) {
            sql.append(" and coalesce(o.owner_user_id,q.created_by)=?");
            args.add(principal().userId());
        }
        sql.append(" order by q.quote_date desc,q.id desc limit 500");
        return jdbcTemplate.query(sql.toString(), quoteMapper(), args.toArray());
    }

    public CrmDtos.QuoteDetail quote(Long id) {
        CrmDtos.QuoteRow row = lockOrReadQuote(id, false);
        assertQuoteOwner(row);
        return new CrmDtos.QuoteDetail(row, quoteItems(id), activities("QUOTE", id),
                plansForTarget("QUOTE", id), events("QUOTE", id));
    }

    @Transactional
    public Long saveQuote(Long id, CrmDtos.QuoteSave request) {
        validateQuoteRequest(request);
        if (request.opportunityId() != null) {
            CrmDtos.OpportunityRow opportunity = lockOpportunity(request.opportunityId());
            assertOwnerAllowed(opportunity.ownerUserId());
            if (!Objects.equals(opportunity.partnerId(), request.partnerId())) throw new BusinessException("报价客户必须与商机客户一致");
        }
        requireCustomerPartner(request.partnerId());
        if (request.contactId() != null) requireContact(request.contactId(), request.partnerId());
        if (request.addressId() != null) requireAddress(request.addressId(), request.partnerId());
        CrmDtos.QuoteRow current = null;
        Map<Long, String> historicalPriceSnapshots = Map.of();
        if (id != null) {
            current = lockOrReadQuote(id, true);
            assertQuoteOwner(current);
            if (!"DRAFT".equals(current.status())) throw new BusinessException("只有报价草稿可以修改；已生效报价请创建修订版本");
            if (!Objects.equals(current.partnerId(), request.partnerId())) throw new BusinessException("报价不能更换客户；请作废后重新创建");
            historicalPriceSnapshots = quoteItemPriceSnapshots(id);
        }
        PreparedQuote prepared = prepareQuote(request, historicalPriceSnapshots);
        SecurityContext.Principal actor = principal();
        if (id == null) {
            String quoteNo = nextNo("QUOTE", "BJ");
            long quoteId = insertQuote(quoteNo, 1, null, request, prepared, actor);
            insertQuoteItems(quoteId, prepared.items());
            event("QUOTE", quoteId, "QUOTE_CREATED", request.partnerId(), Map.of("quoteNo", quoteNo));
            return quoteId;
        }
        updateQuote(id, request, prepared, actor);
        jdbcTemplate.update("delete from crm_quote_item where quote_id=?", id);
        insertQuoteItems(id, prepared.items());
        event("QUOTE", id, "QUOTE_UPDATED", request.partnerId(), Map.of("revisionNo", current.revisionNo()));
        return id;
    }

    @Transactional
    public Long activateQuote(Long id) {
        CrmDtos.QuoteRow row = lockOrReadQuote(id, true);
        assertQuoteOwner(row);
        if ("ACTIVE".equals(row.status())) return id;
        if (!"DRAFT".equals(row.status())) throw new BusinessException("只有报价草稿可以生效");
        if (row.validUntil().isBefore(LocalDate.now())) throw new BusinessException("报价有效期已过，请先修改有效期");
        if (quoteItems(id).isEmpty()) throw new BusinessException("报价没有商品明细");
        jdbcTemplate.update("update crm_quote set status='ACTIVE',activated_at=now(),updated_by=? where id=?", principal().userId(), id);
        event("QUOTE", id, "QUOTE_ACTIVATED", row.partnerId(), Map.of("quoteNo", row.quoteNo(), "revisionNo", row.revisionNo()));
        return id;
    }

    @Transactional
    public Long reviseQuote(Long id, CrmDtos.ReasonAction request) {
        CrmDtos.QuoteRow row = lockOrReadQuote(id, true);
        assertQuoteOwner(row);
        if (!"ACTIVE".equals(row.status())) throw new BusinessException("只有已生效且尚未决策的报价可以修订");
        SecurityContext.Principal actor = principal();
        jdbcTemplate.update("update crm_quote set status='REVISED',decision_reason=?,updated_by=? where id=?",
                request.reason().trim(), actor.userId(), id);
        long newId = insert("""
                insert into crm_quote
                (quote_no,revision_no,previous_quote_id,opportunity_id,partner_id,contact_id,address_id,title,
                 quote_date,valid_until,price_includes_tax,tax_rate,tax_rule_snapshot,original_amount,discount_rate,
                 discount_amount,adjustment_amount,amount_excluding_tax,tax_amount,total_amount,payment_term,
                 shipment_payment_policy,deposit_mode,deposit_rate,required_deposit_amount,delivery_plan_date,memo,
                 status,created_by,updated_by)
                select quote_no,revision_no+1,id,opportunity_id,partner_id,contact_id,address_id,title,current_date,valid_until,
                       price_includes_tax,tax_rate,tax_rule_snapshot,original_amount,discount_rate,discount_amount,
                       adjustment_amount,amount_excluding_tax,tax_amount,total_amount,payment_term,shipment_payment_policy,
                       deposit_mode,deposit_rate,required_deposit_amount,delivery_plan_date,memo,'DRAFT',?,?
                from crm_quote where id=?
                """, actor.userId(), actor.userId(), id);
        jdbcTemplate.update("""
                insert into crm_quote_item
                (quote_id,product_id,product_sku_snapshot,product_name_snapshot,product_spec_snapshot,requested_quantity,
                 requested_unit,actual_base_quantity,base_quantity,base_unit,pricing_quantity,price_unit,package_unit,
                 packaging_plan_json,packaging_effective_revision_id,packaging_snapshot_hash,
                 price_source_code,price_source_name,price_rule_version_id,price_snapshot_json,no_charge,unit_price,
                 tax_rate,amount_excluding_tax,tax_amount,total_amount,sort_no)
                select ?,product_id,product_sku_snapshot,product_name_snapshot,product_spec_snapshot,requested_quantity,
                       requested_unit,actual_base_quantity,base_quantity,base_unit,pricing_quantity,price_unit,package_unit,
                       packaging_plan_json,packaging_effective_revision_id,packaging_snapshot_hash,
                       price_source_code,price_source_name,price_rule_version_id,price_snapshot_json,no_charge,unit_price,
                       tax_rate,amount_excluding_tax,tax_amount,total_amount,sort_no
                from crm_quote_item where quote_id=? order by sort_no,id
                """, newId, id);
        event("QUOTE", id, "QUOTE_REVISED", row.partnerId(), Map.of("newQuoteId", newId, "reason", request.reason().trim()));
        event("QUOTE", newId, "QUOTE_REVISION_CREATED", row.partnerId(), Map.of("previousQuoteId", id));
        return newId;
    }

    @Transactional
    public Long decideQuote(Long id, String targetStatus, CrmDtos.ReasonAction request) {
        if (!Set.of("ACCEPTED", "REJECTED", "CANCELLED").contains(targetStatus)) throw new BusinessException("不支持的报价动作");
        CrmDtos.QuoteRow row = lockOrReadQuote(id, true);
        assertQuoteOwner(row);
        if (targetStatus.equals(row.status())) return id;
        if ("CANCELLED".equals(targetStatus) && Set.of("DRAFT", "ACTIVE").contains(row.status())) {
            // Drafts or active quotes may be cancelled before customer acceptance.
        } else if ("CANCELLED".equals(targetStatus) && "ACCEPTED".equals(row.status()) && row.salesOrderId() == null) {
            // An accepted quote can be cancelled only before it creates a sales order, with an audited reason.
        } else if (!"ACTIVE".equals(row.status())) {
            throw new BusinessException("只有已生效报价可以接受或拒绝；草稿只能作废");
        }
        String reason = request.reason().trim();
        String reasonType = "ACCEPTED".equals(targetStatus) ? "WIN_REASON" : "LOSS_REASON";
        String reasonCode = optionCode(reasonType, request.reasonCode(), "OTHER");
        jdbcTemplate.update("""
                update crm_quote set status=?,decision_reason_code=?,decision_reason=?,
                    accepted_at=case when ?='ACCEPTED' then now() else accepted_at end,
                    rejected_at=case when ?='REJECTED' then now() else rejected_at end,
                    cancelled_at=case when ?='CANCELLED' then now() else cancelled_at end,updated_by=? where id=?
                """, targetStatus, reasonCode, reason, targetStatus, targetStatus, targetStatus, principal().userId(), id);
        event("QUOTE", id, "QUOTE_" + targetStatus, row.partnerId(), Map.of("reasonCode", reasonCode, "reason", reason));
        if (row.opportunityId() != null && "ACCEPTED".equals(targetStatus)) {
            CrmDtos.OpportunityRow opportunity = lockOpportunity(row.opportunityId());
            if (!"WON".equals(opportunity.stage())) changeOpportunityStage(row.opportunityId(), new CrmDtos.StageChange("WON", reasonCode, reason));
        }
        return id;
    }

    @Transactional
    public CrmDtos.QuoteConversion convertQuote(Long id) {
        if (!principal().hasPermission("sales.manage")) {
            throw new ForbiddenException("报价转销售订单还需要销售订单维护权限");
        }
        CrmDtos.QuoteRow quote = lockOrReadQuote(id, true);
        assertQuoteOwner(quote);
        if (quote.salesOrderId() != null && quote.salesOrderId() > 0) return conversionView(quote);
        if (!"ACCEPTED".equals(quote.status())) throw new BusinessException("只有客户已接受的报价可以转销售订单草稿");
        if (quote.addressId() == null) throw new BusinessException("报价尚未选择客户收货地址，不能转换销售订单");
        List<CrmDtos.QuoteItemRow> quoteItems = quoteItems(id);
        Map<Long, String> snapshots = new LinkedHashMap<>();
        Map<Long, String> priceSnapshots = new LinkedHashMap<>();
        Map<Long, Long> productIds = new LinkedHashMap<>();
        Map<Long, BigDecimal> taxRates = new LinkedHashMap<>();
        List<AlignedDtos.SalesOrderCreate.Item> items = new ArrayList<>();
        for (CrmDtos.QuoteItemRow item : quoteItems) {
            snapshots.put(item.id(), item.packagingPlanJson());
            priceSnapshots.put(item.id(), item.priceSnapshotJson());
            productIds.put(item.id(), item.productId());
            taxRates.put(item.id(), item.taxRate());
            items.add(new AlignedDtos.SalesOrderCreate.Item(item.id(), item.productId(), item.requestedQuantity(),
                    item.requestedUnit(), item.actualBaseQuantity(), item.packagingEffectiveRevisionId(),
                    null, item.priceUnit(), item.packageUnit(), item.noCharge(), item.unitPrice()));
        }
        AlignedDtos.SalesOrderCreate request = new AlignedDtos.SalesOrderCreate(
                quote.title(), quote.partnerId(), quote.addressId(), LocalDate.now(), quote.validUntil(),
                quote.discountRate(), quote.adjustmentAmount(), quotePaymentTerm(id), quoteShipmentPolicy(id),
                quoteDepositMode(id), quoteDepositRate(id), quoteRequiredDeposit(id),
                "来源 CRM 报价 " + quote.quoteNo() + " 第" + quote.revisionNo() + "版", items);
        Long salesOrderId = copperService.createSalesOrderFromCrmQuote(
                request, snapshots, priceSnapshots, productIds,
                quote.priceIncludesTax(), quote.taxRate(), taxRates,
                quote.quoteNo() + "|REV=" + quote.revisionNo() + "|" + quote.taxRuleSnapshot());
        jdbcTemplate.update("update crm_quote set sales_order_id=?,updated_by=? where id=?", salesOrderId, principal().userId(), id);
        jdbcTemplate.update("""
                insert into crm_quote_conversion(quote_id,opportunity_id,sales_order_id,converted_by,converted_by_name)
                values(?,?,?,?,?)
                """, id, quote.opportunityId(), salesOrderId, principal().userId(), principal().employeeName());
        event("QUOTE", id, "QUOTE_CONVERTED_TO_SALES_ORDER", quote.partnerId(), Map.of("salesOrderId", salesOrderId));
        return conversionView(lockOrReadQuote(id, false));
    }

    public List<CrmDtos.ActivityRow> activities(String targetType, Long targetId) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder(activitySelect()).append(" where 1=1");
        if (StringUtils.hasText(targetType)) { sql.append(" and a.target_type=?"); args.add(targetType.trim().toUpperCase()); }
        if (targetId != null) { sql.append(" and a.target_id=?"); args.add(targetId); }
        appendOwnerFilter(sql, args, "a.owner_user_id");
        sql.append(" order by a.occurred_at desc,a.id desc limit 500");
        return jdbcTemplate.query(sql.toString(), activityMapper(), args.toArray());
    }

    @Transactional
    public Long createActivity(CrmDtos.ActivitySave request) {
        TargetRef target = requireTarget(request.targetType(), request.targetId(), request.partnerId());
        if (request.contactId() != null) requireContact(request.contactId(), target.partnerId());
        SecurityContext.Principal actor = principal();
        long id = insert("""
                insert into crm_activity
                (target_type,target_id,partner_id,contact_id,activity_type,subject,occurred_at,duration_minutes,
                 outcome,next_action,owner_user_id,owner_name,source_type,created_by)
                values(?,?,?,?,?,?,?,?,?,?,?,?, 'CRM',?)
                """, target.type(), target.id(), target.partnerId(), request.contactId(),
                optionCode("ACTIVITY_TYPE", request.activityType(), "OTHER"), request.subject().trim(),
                timestamp(request.occurredAt()), nonNegative(request.durationMinutes()), blank(request.outcome()),
                blank(request.nextAction()), actor.userId(), actor.employeeName(), actor.userId());
        event(target.type(), target.id(), "ACTIVITY_RECORDED", target.partnerId(), Map.of("activityId", id, "subject", request.subject().trim()));
        return id;
    }

    public List<CrmDtos.PlanRow> plans(String status, String dueScope, boolean overdueFirst) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder(planSelect()).append(" where 1=1");
        if (StringUtils.hasText(status)) { sql.append(" and fp.status=?"); args.add(status.trim().toUpperCase()); }
        if ("TODAY".equalsIgnoreCase(dueScope)) sql.append(" and date(fp.due_at)=current_date");
        if ("OVERDUE".equalsIgnoreCase(dueScope)) sql.append(" and fp.due_at<now() and fp.status='OPEN'");
        appendOwnerFilter(sql, args, "fp.owner_user_id");
        sql.append(overdueFirst ? " order by (fp.status='OPEN' and fp.due_at<now()) desc,fp.due_at,fp.id" : " order by fp.due_at,fp.id");
        sql.append(" limit 500");
        return jdbcTemplate.query(sql.toString(), planMapper(), args.toArray());
    }

    @Transactional
    public Long savePlan(Long id, CrmDtos.PlanSave request) {
        TargetRef target = requireTarget(request.targetType(), request.targetId(), request.partnerId());
        if (request.contactId() != null) requireContact(request.contactId(), target.partnerId());
        SecurityContext.Principal actor = principal();
        String priority = Set.of("LOW", "NORMAL", "HIGH", "URGENT").contains(blank(request.priority()).toUpperCase())
                ? request.priority().toUpperCase() : "NORMAL";
        if (id == null) {
            long planId = insert("""
                    insert into crm_followup_plan
                    (target_type,target_id,partner_id,contact_id,subject,plan_type,due_at,priority,reminder_minutes,
                     owner_user_id,owner_name,status,created_by,updated_by)
                    values(?,?,?,?,?,?,?,?,?,?,?,'OPEN',?,?)
                    """, target.type(), target.id(), target.partnerId(), request.contactId(), request.subject().trim(),
                    optionCode("ACTIVITY_TYPE", request.planType(), "OTHER"), timestamp(request.dueAt()), priority,
                    nonNegative(request.reminderMinutes()), actor.userId(), actor.employeeName(), actor.userId(), actor.userId());
            event(target.type(), target.id(), "FOLLOWUP_PLAN_CREATED", target.partnerId(), Map.of("planId", planId));
            return planId;
        }
        CrmDtos.PlanRow current = lockPlan(id);
        assertOwnerAllowed(current.ownerUserId());
        if (!"OPEN".equals(current.status())) throw new BusinessException("只有待办计划可以修改");
        if (!current.targetType().equals(target.type()) || !current.targetId().equals(target.id())) throw new BusinessException("跟进计划不能更换关联对象");
        jdbcTemplate.update("""
                update crm_followup_plan set contact_id=?,subject=?,plan_type=?,due_at=?,priority=?,reminder_minutes=?,updated_by=? where id=?
                """, request.contactId(), request.subject().trim(), optionCode("ACTIVITY_TYPE", request.planType(), "OTHER"),
                timestamp(request.dueAt()), priority, nonNegative(request.reminderMinutes()), actor.userId(), id);
        event(target.type(), target.id(), "FOLLOWUP_PLAN_UPDATED", target.partnerId(), Map.of("planId", id));
        return id;
    }

    @Transactional
    public Long completePlan(Long id, CrmDtos.PlanComplete request) {
        CrmDtos.PlanRow plan = lockPlan(id);
        assertOwnerAllowed(plan.ownerUserId());
        if ("COMPLETED".equals(plan.status())) return id;
        if (!"OPEN".equals(plan.status())) throw new BusinessException("已取消计划不能完成");
        LocalDateTime occurredAt = request.occurredAt() == null ? LocalDateTime.now() : request.occurredAt();
        long activityId = createActivity(new CrmDtos.ActivitySave(plan.targetType(), plan.targetId(), plan.partnerId(),
                plan.contactId(), plan.planType(), plan.subject(), occurredAt, nonNegative(request.durationMinutes()),
                request.outcome().trim(), blank(request.nextAction())));
        jdbcTemplate.update("""
                update crm_activity set source_type='FOLLOWUP_PLAN',source_id=? where id=?
                """, id, activityId);
        jdbcTemplate.update("""
                update crm_followup_plan set status='COMPLETED',completion_activity_id=?,completed_at=now(),updated_by=? where id=?
                """, activityId, principal().userId(), id);
        event(plan.targetType(), plan.targetId(), "FOLLOWUP_PLAN_COMPLETED", plan.partnerId(), Map.of("planId", id, "activityId", activityId));
        return id;
    }

    @Transactional
    public Long cancelPlan(Long id, CrmDtos.ReasonAction request) {
        CrmDtos.PlanRow plan = lockPlan(id);
        assertOwnerAllowed(plan.ownerUserId());
        if ("CANCELLED".equals(plan.status())) return id;
        if (!"OPEN".equals(plan.status())) throw new BusinessException("已完成计划不能取消");
        jdbcTemplate.update("""
                update crm_followup_plan set status='CANCELLED',cancel_reason=?,cancelled_at=now(),updated_by=? where id=?
                """, request.reason().trim(), principal().userId(), id);
        event(plan.targetType(), plan.targetId(), "FOLLOWUP_PLAN_CANCELLED", plan.partnerId(), Map.of("planId", id, "reason", request.reason().trim()));
        return id;
    }

    public CrmDtos.CustomerAnalysis customerAnalysis(Long partnerId) {
        requireCustomerPartner(partnerId);
        BigDecimal shipped = scalarDecimal("select coalesce(sum(total_amount),0) from outbound_order where customer_id=? and status not in ('DRAFT','CANCELLED','REVERSED')", partnerId);
        BigDecimal returned = scalarDecimal("select coalesce(sum(total_amount),0) from sales_return_order where customer_id=? and status='CONFIRMED'", partnerId);
        BigDecimal cost = scalarDecimal("""
                select coalesce(sum(i.inventory_amount_cost),0) from outbound_item i
                join outbound_order o on o.id=i.outbound_id
                where o.customer_id=? and o.status not in ('DRAFT','CANCELLED','REVERSED')
                """, partnerId);
        BigDecimal returnedCost = scalarDecimal("""
                select coalesce(sum(i.inventory_amount_cost),0) from sales_return_item i
                join sales_return_order r on r.id=i.sales_return_id
                where r.customer_id=? and r.status='CONFIRMED'
                """, partnerId);
        BigDecimal netRevenue = shipped.subtract(returned).setScale(2, RoundingMode.HALF_UP);
        BigDecimal netCost = cost.subtract(returnedCost).setScale(2, RoundingMode.HALF_UP);
        BigDecimal grossProfit = netRevenue.subtract(netCost).setScale(2, RoundingMode.HALF_UP);
        BigDecimal margin = netRevenue.signum() == 0 ? BigDecimal.ZERO : grossProfit.multiply(BigDecimal.valueOf(100)).divide(netRevenue, 2, RoundingMode.HALF_UP);
        BigDecimal receivable = scalarDecimal("""
                select coalesce(sum(case when direction='DEBIT' then amount else -amount end),0)
                from receivable_movement where customer_id=?
                """, partnerId);
        BigDecimal overdue = scalarDecimal("""
                select coalesce(sum(greatest(receivable_amount-received_amount-adjusted_amount,0)),0)
                from sales_order where customer_id=? and business_date<date_sub(current_date,interval 30 day)
                  and status not in ('DRAFT','CANCELLED')
                """, partnerId);
        BigDecimal received = scalarDecimal("select coalesce(sum(amount),0) from payment where customer_id=? and verification_status<>'REVERSED'", partnerId);
        LocalDate lastBusinessDate = jdbcTemplate.query("""
                select max(business_date) last_date from outbound_order
                where customer_id=? and status not in ('DRAFT','CANCELLED','REVERSED')
                """, rs -> rs.next() && rs.getDate(1) != null ? rs.getDate(1).toLocalDate() : null, partnerId);
        String recentProducts = jdbcTemplate.query("""
                select group_concat(distinct x.product_name_snapshot order by x.max_date desc separator '、')
                from (select i.product_name_snapshot,max(o.business_date) max_date
                      from outbound_item i join outbound_order o on o.id=i.outbound_id
                      where o.customer_id=? and o.status not in ('DRAFT','CANCELLED','REVERSED')
                      group by i.product_name_snapshot order by max_date desc limit 8) x
                """, rs -> rs.next() ? blank(rs.getString(1)) : "", partnerId);
        BigDecimal pipeline = scalarDecimal("select coalesce(sum(estimated_amount),0) from crm_opportunity where partner_id=? and stage not in ('WON','LOST')", partnerId);
        return new CrmDtos.CustomerAnalysis(partnerId, shipped, returned, netRevenue, cost, returnedCost,
                grossProfit, margin, receivable, overdue, received, lastBusinessDate, recentProducts, pipeline);
    }

    private List<CrmDtos.CustomerAnalysisRow> customerAnalysisRows() {
        String ownerFilter = ownerClause("cp.owner_user_id");
        return jdbcTemplate.query("""
                select cp.id customer_profile_id,cp.partner_id,p.name partner_name,
                       coalesce(c.name,p.contact_person) contact_name,coalesce(c.phone,p.phone) contact_phone,
                       cp.lifecycle_stage,cp.tier_code,cp.repurchase_reminder_enabled,cp.repurchase_cycle_days,
                       (coalesce(ob.shipped_revenue,0)-coalesce(sr.returned_revenue,0)) net_revenue,
                       ((coalesce(ob.shipped_revenue,0)-coalesce(sr.returned_revenue,0))
                         -(coalesce(oc.inventory_cost,0)-coalesce(src.returned_cost,0))) gross_profit,
                       coalesce(ar.receivable_balance,0) receivable_balance,
                       coalesce(od.overdue_amount,0) overdue_amount,ob.last_business_date,so.latest_order_date,
                       coalesce(rp.recent_products,'') recent_products,coalesce(pl.open_pipeline_amount,0) open_pipeline_amount
                from crm_customer_profile cp
                join partner p on p.id=cp.partner_id
                left join partner_contact c on c.partner_id=p.id and c.first_choice=1 and c.status='ENABLED'
                left join (
                  select customer_id,sum(total_amount) shipped_revenue,max(business_date) last_business_date
                  from outbound_order where status not in ('DRAFT','CANCELLED','REVERSED') group by customer_id
                ) ob on ob.customer_id=cp.partner_id
                left join (
                  select customer_id,sum(total_amount) returned_revenue from sales_return_order
                  where status='CONFIRMED' group by customer_id
                ) sr on sr.customer_id=cp.partner_id
                left join (
                  select o.customer_id,sum(i.inventory_amount_cost) inventory_cost
                  from outbound_item i join outbound_order o on o.id=i.outbound_id
                  where o.status not in ('DRAFT','CANCELLED','REVERSED') group by o.customer_id
                ) oc on oc.customer_id=cp.partner_id
                left join (
                  select r.customer_id,sum(i.inventory_amount_cost) returned_cost
                  from sales_return_item i join sales_return_order r on r.id=i.sales_return_id
                  where r.status='CONFIRMED' group by r.customer_id
                ) src on src.customer_id=cp.partner_id
                left join (
                  select customer_id,sum(case when direction='DEBIT' then amount else -amount end) receivable_balance
                  from receivable_movement group by customer_id
                ) ar on ar.customer_id=cp.partner_id
                left join (
                  select debt.customer_id,sum(debt.unpaid_amount) overdue_amount from (
                    select customer_id,greatest(receivable_amount-received_amount-adjusted_amount,0) unpaid_amount
                    from sales_order where business_date<date_sub(current_date,interval 30 day)
                      and status not in ('DRAFT','CANCELLED')
                    union all
                    select customer_id,greatest(receivable_amount-paid_amount,0) unpaid_amount
                    from outbound_order where sales_order_id=0 and business_date<date_sub(current_date,interval 30 day)
                      and status='SHIPPED'
                  ) debt group by debt.customer_id
                ) od on od.customer_id=cp.partner_id
                left join (
                  select customer_id,max(business_date) latest_order_date from sales_order
                  where status<>'CANCELLED' group by customer_id
                ) so on so.customer_id=cp.partner_id
                left join (
                  select x.customer_id,substring_index(group_concat(x.product_name_snapshot order by x.max_date desc separator '、'),'、',8) recent_products
                  from (
                    select o.customer_id,i.product_name_snapshot,max(o.business_date) max_date
                    from outbound_item i join outbound_order o on o.id=i.outbound_id
                    where o.status not in ('DRAFT','CANCELLED','REVERSED')
                    group by o.customer_id,i.product_name_snapshot
                  ) x group by x.customer_id
                ) rp on rp.customer_id=cp.partner_id
                left join (
                  select partner_id,sum(estimated_amount) open_pipeline_amount from crm_opportunity
                  where stage not in ('WON','LOST') group by partner_id
                ) pl on pl.partner_id=cp.partner_id
                where cp.status='ENABLED'
                """ + ownerFilter + " order by net_revenue desc,cp.updated_at desc", (rs, rowNum) ->
                new CrmDtos.CustomerAnalysisRow(rs.getLong("customer_profile_id"), rs.getLong("partner_id"),
                        rs.getString("partner_name"), rs.getString("contact_name"), rs.getString("contact_phone"),
                        rs.getString("lifecycle_stage"), rs.getString("tier_code"),
                        rs.getBoolean("repurchase_reminder_enabled"), rs.getInt("repurchase_cycle_days"),
                        rs.getBigDecimal("net_revenue"), rs.getBigDecimal("gross_profit"),
                        rs.getBigDecimal("receivable_balance"), rs.getBigDecimal("overdue_amount"),
                        localDate(rs.getDate("last_business_date")), localDate(rs.getDate("latest_order_date")),
                        rs.getString("recent_products"), rs.getBigDecimal("open_pipeline_amount")), ownerArgs());
    }

    private List<CrmDtos.PlanRow> plansForTarget(String targetType, Long targetId) {
        List<Object> args = new ArrayList<>(List.of(targetType, targetId));
        StringBuilder sql = new StringBuilder(planSelect()).append(" where fp.target_type=? and fp.target_id=?");
        appendOwnerFilter(sql, args, "fp.owner_user_id");
        sql.append(" order by case fp.status when 'OPEN' then 0 else 1 end,fp.due_at desc");
        return jdbcTemplate.query(sql.toString(), planMapper(), args.toArray());
    }

    private List<CrmDtos.EventRow> events(String aggregateType, Long aggregateId) {
        return jdbcTemplate.query("""
                select id,aggregate_type,aggregate_id,event_type,event_version,partner_id,actor_user_id,
                       actor_name,cast(payload_json as char) payload_json,occurred_at
                from crm_domain_event where aggregate_type=? and aggregate_id=? order by id desc
                """, eventMapper(), aggregateType, aggregateId);
    }

    private List<CrmDtos.EventRow> eventsByPartner(Long partnerId) {
        return jdbcTemplate.query("""
                select id,aggregate_type,aggregate_id,event_type,event_version,partner_id,actor_user_id,
                       actor_name,cast(payload_json as char) payload_json,occurred_at
                from crm_domain_event where partner_id=? order by id desc limit 300
                """, eventMapper(), partnerId);
    }

    private Long ensureCustomerProfile(Long partnerId, String sourceCode) {
        List<Long> ids = jdbcTemplate.query("select id from crm_customer_profile where partner_id=? for update", (rs, rowNum) -> rs.getLong(1), partnerId);
        if (!ids.isEmpty()) {
            jdbcTemplate.update("update crm_customer_profile set status='ENABLED',lifecycle_stage=case when lifecycle_stage='LOST' then 'PROSPECT' else lifecycle_stage end where id=?", ids.get(0));
            return ids.get(0);
        }
        SecurityContext.Principal actor = principal();
        long id = insert("""
                insert into crm_customer_profile
                (partner_id,lifecycle_stage,tier_code,source_code,owner_user_id,owner_name,status,created_by,updated_by)
                values(?,'PROSPECT','NORMAL',?,?,?,'ENABLED',?,?)
                """, partnerId, optionCode("LEAD_SOURCE", sourceCode, "OTHER"), actor.userId(), actor.employeeName(), actor.userId(), actor.userId());
        event("CUSTOMER", partnerId, "CUSTOMER_PROFILE_CREATED", partnerId, Map.of("profileId", id));
        return id;
    }

    private PreparedQuote prepareQuote(CrmDtos.QuoteSave request, Map<Long, String> historicalPriceSnapshots) {
        BigDecimal headerRate = percent(request.taxRate());
        List<PreparedQuoteItem> rawItems = new ArrayList<>();
        List<TaxPriceContract.Input> taxInputs = new ArrayList<>();
        int sort = 10;
        for (CrmDtos.QuoteItemSave item : request.items()) {
            ProductSnapshot product = product(item.productId());
            String priceUnit = StringUtils.hasText(item.priceUnit()) ? item.priceUnit().trim() : product.unit();
            PackagingDtos.Plan plan = packagingService.plan(item.productId(), new PackagingDtos.PlanRequest(
                    item.quantity(), item.quantityUnit(), item.actualBaseQuantity(), priceUnit, "SALES"));
            BigDecimal price = item.noCharge() ? BigDecimal.ZERO : nvl(item.unitPrice()).setScale(4, RoundingMode.HALF_UP);
            PriceCenterDtos.PriceSnapshot priceSnapshot = priceCenterService.freezeEnteredPrice(
                    "SALES", request.partnerId(), item.productId(), plan.pricingQuantity(), priceUnit,
                    request.quoteDate(), price, item.noCharge(),
                    item.itemId() == null ? null : historicalPriceSnapshots.get(item.itemId()));
            taxInputs.add(new TaxPriceContract.Input(plan.pricingQuantity(), price, headerRate));
            rawItems.add(new PreparedQuoteItem(item, product, plan, price, headerRate,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, sort, priceSnapshot));
            sort += 10;
        }
        TaxPriceContract.Document tax = TaxPriceContract.calculate(request.priceIncludesTax(), taxInputs,
                request.discountRate(), request.adjustmentAmount(), request.taxRuleSnapshot());
        List<PreparedQuoteItem> items = new ArrayList<>();
        for (int index = 0; index < rawItems.size(); index++) {
            PreparedQuoteItem raw = rawItems.get(index);
            TaxPriceContract.Line line = tax.lines().get(index);
            items.add(new PreparedQuoteItem(raw.request(), raw.product(), raw.plan(), raw.unitPrice(),
                    line.taxRate(), line.amountExcludingTax(), line.taxAmount(),
                    line.settlementTotalAmount(), raw.sortNo(), raw.priceSnapshot()));
        }
        return new PreparedQuote(tax.originalGrossAmount(), tax.discountAmount(), tax.adjustmentAmount(),
                tax.amountExcludingTax(), tax.taxAmount(), tax.totalAmount(), tax.ruleSnapshot(), items);
    }

    private long insertQuote(String quoteNo, int revisionNo, Long previousQuoteId, CrmDtos.QuoteSave request,
                             PreparedQuote prepared, SecurityContext.Principal actor) {
        return insert("""
                insert into crm_quote
                (quote_no,revision_no,previous_quote_id,opportunity_id,partner_id,contact_id,address_id,title,quote_date,
                 valid_until,price_includes_tax,tax_rate,tax_rule_snapshot,original_amount,discount_rate,discount_amount,
                 adjustment_amount,amount_excluding_tax,tax_amount,total_amount,payment_term,shipment_payment_policy,
                 deposit_mode,deposit_rate,required_deposit_amount,delivery_plan_date,memo,status,created_by,updated_by)
                values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'DRAFT',?,?)
                """, quoteNo, revisionNo, previousQuoteId, request.opportunityId(), request.partnerId(), request.contactId(),
                request.addressId(), blank(request.title()), date(request.quoteDate()), date(request.validUntil()), request.priceIncludesTax(),
                percent(request.taxRate()), prepared.ruleSnapshot(), prepared.originalAmount(), percent(request.discountRate()),
                prepared.discountAmount(), prepared.adjustmentAmount(), prepared.netAmount(), prepared.taxAmount(), prepared.totalAmount(),
                paymentTerm(request.paymentTerm()), shipmentPolicy(request.shipmentPaymentPolicy()), depositMode(request.depositMode()),
                percent(request.depositRate()), money(request.requiredDepositAmount()), date(request.deliveryPlanDate()), blank(request.memo()),
                actor.userId(), actor.userId());
    }

    private void updateQuote(Long id, CrmDtos.QuoteSave request, PreparedQuote prepared, SecurityContext.Principal actor) {
        jdbcTemplate.update("""
                update crm_quote set opportunity_id=?,contact_id=?,address_id=?,title=?,quote_date=?,valid_until=?,
                    price_includes_tax=?,tax_rate=?,tax_rule_snapshot=?,original_amount=?,discount_rate=?,discount_amount=?,
                    adjustment_amount=?,amount_excluding_tax=?,tax_amount=?,total_amount=?,payment_term=?,shipment_payment_policy=?,
                    deposit_mode=?,deposit_rate=?,required_deposit_amount=?,delivery_plan_date=?,memo=?,updated_by=? where id=?
                """, request.opportunityId(), request.contactId(), request.addressId(), blank(request.title()), date(request.quoteDate()),
                date(request.validUntil()), request.priceIncludesTax(), percent(request.taxRate()), prepared.ruleSnapshot(),
                prepared.originalAmount(), percent(request.discountRate()), prepared.discountAmount(), prepared.adjustmentAmount(),
                prepared.netAmount(), prepared.taxAmount(), prepared.totalAmount(), paymentTerm(request.paymentTerm()),
                shipmentPolicy(request.shipmentPaymentPolicy()), depositMode(request.depositMode()), percent(request.depositRate()),
                money(request.requiredDepositAmount()), date(request.deliveryPlanDate()), blank(request.memo()), actor.userId(), id);
    }

    private void insertQuoteItems(Long quoteId, List<PreparedQuoteItem> items) {
        for (PreparedQuoteItem item : items) {
            jdbcTemplate.update("""
                    insert into crm_quote_item
                    (quote_id,product_id,product_sku_snapshot,product_name_snapshot,product_spec_snapshot,
                     requested_quantity,requested_unit,actual_base_quantity,base_quantity,base_unit,pricing_quantity,
                     price_unit,package_unit,packaging_plan_json,packaging_effective_revision_id,packaging_snapshot_hash,
                     price_source_code,price_source_name,price_rule_version_id,price_snapshot_json,
                     no_charge,unit_price,tax_rate,amount_excluding_tax,tax_amount,total_amount,sort_no)
                    values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, quoteId, item.request().productId(), item.product().sku(), item.product().name(), item.product().spec(),
                    item.plan().requestedQuantity(), item.plan().requestedUnit(), item.request().actualBaseQuantity(),
                    item.plan().baseQuantity(), item.plan().baseUnit(), item.plan().pricingQuantity(), item.plan().priceUnit(),
                    blank(item.request().packageUnit()), item.plan().snapshotJson(), nvl(item.plan().effectiveRevisionId()),
                    blank(item.plan().snapshotHash()), item.priceSnapshot().sourceCode(), item.priceSnapshot().sourceName(),
                    item.priceSnapshot().ruleVersionId(), item.priceSnapshot().snapshotJson(),
                    item.request().noCharge(), item.unitPrice(), item.taxRate(),
                    item.netAmount(), item.taxAmount(), item.totalAmount(), item.sortNo());
        }
    }

    private Map<Long, String> quoteItemPriceSnapshots(Long quoteId) {
        Map<Long, String> result = new LinkedHashMap<>();
        jdbcTemplate.query("select id,price_snapshot_json from crm_quote_item where quote_id=?",
                (org.springframework.jdbc.core.RowCallbackHandler) rs ->
                        result.put(rs.getLong("id"), rs.getString("price_snapshot_json")), quoteId);
        return result;
    }

    private List<CrmDtos.QuoteItemRow> quoteItems(Long quoteId) {
        return jdbcTemplate.query("""
                select id,product_id,product_sku_snapshot,product_name_snapshot,product_spec_snapshot,
                       requested_quantity,requested_unit,actual_base_quantity,base_quantity,base_unit,pricing_quantity,
                       price_unit,package_unit,packaging_plan_json,packaging_effective_revision_id,packaging_snapshot_hash,
                       price_source_code,price_source_name,price_rule_version_id,price_snapshot_json,
                       no_charge,unit_price,tax_rate,amount_excluding_tax,tax_amount,total_amount,sort_no
                from crm_quote_item where quote_id=? order by sort_no,id
                """, (rs, rowNum) -> new CrmDtos.QuoteItemRow(rs.getLong("id"), rs.getLong("product_id"),
                rs.getString("product_sku_snapshot"), rs.getString("product_name_snapshot"), rs.getString("product_spec_snapshot"),
                rs.getBigDecimal("requested_quantity"), rs.getString("requested_unit"), rs.getBigDecimal("actual_base_quantity"),
                rs.getBigDecimal("base_quantity"), rs.getString("base_unit"), rs.getBigDecimal("pricing_quantity"),
                rs.getString("price_unit"), rs.getString("package_unit"), rs.getString("packaging_plan_json"),
                rs.getLong("packaging_effective_revision_id"), rs.getString("packaging_snapshot_hash"),
                rs.getString("price_source_code"), rs.getString("price_source_name"),
                rs.getLong("price_rule_version_id"), rs.getString("price_snapshot_json"), rs.getBoolean("no_charge"),
                rs.getBigDecimal("unit_price"), rs.getBigDecimal("tax_rate"), rs.getBigDecimal("amount_excluding_tax"),
                rs.getBigDecimal("tax_amount"), rs.getBigDecimal("total_amount"), rs.getInt("sort_no")), quoteId);
    }

    private CrmDtos.QuoteConversion conversionView(CrmDtos.QuoteRow quote) {
        String orderNo = quote.salesOrderId() == null ? "" : jdbcTemplate.queryForObject("select order_no from sales_order where id=?", String.class, quote.salesOrderId());
        return new CrmDtos.QuoteConversion(quote.id(), quote.salesOrderId(), blank(orderNo));
    }

    private TargetRef requireTarget(String targetType, Long targetId, Long requestedPartnerId) {
        String type = requiredCode(targetType, "关联对象类型");
        if (!TARGET_TYPES.contains(type)) throw new BusinessException("不支持的 CRM 关联对象");
        Long partnerId;
        Long ownerId;
        switch (type) {
            case "LEAD" -> { CrmDtos.LeadRow row = lockLead(targetId); partnerId = row.partnerId(); ownerId = row.ownerUserId(); }
            case "CUSTOMER" -> {
                CrmDtos.CustomerRow row = one(customerSelect() + " where cp.partner_id=?", customerMapper(), targetId, "CRM 客户档案不存在");
                partnerId = row.partnerId(); ownerId = row.ownerUserId();
            }
            case "OPPORTUNITY" -> { CrmDtos.OpportunityRow row = lockOpportunity(targetId); partnerId = row.partnerId(); ownerId = row.ownerUserId(); }
            case "QUOTE" -> { CrmDtos.QuoteRow row = lockOrReadQuote(targetId, true); partnerId = row.partnerId(); ownerId = quoteOwnerId(row); }
            default -> throw new BusinessException("不支持的 CRM 关联对象");
        }
        assertOwnerAllowed(ownerId);
        if (requestedPartnerId != null && partnerId != null && !Objects.equals(requestedPartnerId, partnerId)) throw new BusinessException("关联客户与 CRM 对象不一致");
        return new TargetRef(type, targetId, partnerId);
    }

    private void requireCustomerPartner(Long partnerId) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*) from partner p left join partner_customer_profile cp on cp.partner_id=p.id
                where p.id=? and p.status='ENABLED' and (p.type_mask & 1)=1 and coalesce(cp.status,'ENABLED')='ENABLED'
                """, Integer.class, partnerId);
        if (count == null || count == 0) throw new BusinessException("请选择启用且具有客户身份的往来单位");
    }

    private void requireContact(Long contactId, Long partnerId) {
        if (partnerId == null) throw new BusinessException("选择联系人前必须先选择客户主档");
        Integer count = jdbcTemplate.queryForObject("select count(*) from partner_contact where id=? and partner_id=? and status='ENABLED'", Integer.class, contactId, partnerId);
        if (count == null || count == 0) throw new BusinessException("联系人不属于当前客户或已停用");
    }

    private void requireAddress(Long addressId, Long partnerId) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*) from partner_address
                where id=? and partner_id=? and address_type='RECEIVING' and status='ENABLED'
                """, Integer.class, addressId, partnerId);
        if (count == null || count == 0) throw new BusinessException("请选择当前客户启用的收货地址；注册地址不能用于销售交付");
    }

    private ProductSnapshot product(Long productId) {
        return one("select id,sku,name,spec,unit from product where id=? and status='ENABLED'",
                (rs, rowNum) -> new ProductSnapshot(rs.getLong("id"), rs.getString("sku"), rs.getString("name"), rs.getString("spec"), rs.getString("unit")),
                productId, "商品不存在或已停用");
    }

    private void validateQuoteRequest(CrmDtos.QuoteSave request) {
        if (request.validUntil().isBefore(request.quoteDate())) throw new BusinessException("报价有效期不能早于报价日期");
        if (!QUOTE_ACTIONABLE.contains("DRAFT")) throw new IllegalStateException("报价状态配置异常");
        if (request.items().size() > 200) throw new BusinessException("一张报价最多200行商品");
    }

    private void assertQuoteOwner(CrmDtos.QuoteRow row) {
        assertOwnerAllowed(quoteOwnerId(row));
    }

    private Long quoteOwnerId(CrmDtos.QuoteRow row) {
        if (row.opportunityId() == null) {
            return jdbcTemplate.queryForObject("select created_by from crm_quote where id=?", Long.class, row.id());
        }
        return jdbcTemplate.queryForObject("select owner_user_id from crm_opportunity where id=?", Long.class, row.opportunityId());
    }

    private boolean canViewAll() { return principal().hasPermission("crm.record.view.all"); }
    private SecurityContext.Principal principal() { return SecurityContext.require(); }

    private void assertOwnerAllowed(Long ownerUserId) {
        if (!canViewAll() && ownerUserId != null && !Objects.equals(ownerUserId, principal().userId())) {
            throw new ForbiddenException("只能查看和维护本人负责的 CRM 记录");
        }
    }

    private void appendOwnerFilter(StringBuilder sql, List<Object> args, String column) {
        if (!canViewAll()) { sql.append(" and (").append(column).append(" is null or ").append(column).append("=?)"); args.add(principal().userId()); }
    }
    private String ownerClause(String column) { return canViewAll() ? "" : " and (" + column + " is null or " + column + "=?)"; }
    private Object[] ownerArgs() { return canViewAll() ? new Object[0] : new Object[]{principal().userId()}; }

    private String nextNo(String documentType, String prefix) {
        Long value = jdbcTemplate.queryForObject("select next_value from crm_number_sequence where document_type=? for update", Long.class, documentType);
        if (value == null) throw new BusinessException("CRM 单号序列不存在：" + documentType);
        jdbcTemplate.update("update crm_number_sequence set next_value=next_value+1 where document_type=?", documentType);
        return prefix + LocalDate.now().toString().replace("-", "") + String.format("%05d", value);
    }

    private void event(String aggregateType, Long aggregateId, String eventType, Long partnerId, Map<String, ?> payload) {
        SecurityContext.Principal actor = principal();
        jdbcTemplate.update("""
                insert into crm_domain_event
                (aggregate_type,aggregate_id,event_type,event_version,partner_id,actor_user_id,actor_name,payload_json)
                values(?,?,?,1,?,?,?,cast(? as json))
                """, aggregateType, aggregateId, eventType, partnerId, actor.userId(), actor.employeeName(), json(payload));
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException ex) { throw new BusinessException("CRM 审计事件序列化失败"); }
    }

    private String optionCode(String optionType, String value, String fallback) {
        String code = StringUtils.hasText(value) ? value.trim().toUpperCase() : fallback;
        Integer count = jdbcTemplate.queryForObject("select count(*) from crm_option where option_type=? and option_code=? and status='ENABLED'", Integer.class, optionType, code);
        if (count == null || count == 0) throw new BusinessException("CRM 选项已停用或不存在：" + optionType + "/" + code);
        return code;
    }

    private String lifecycleStage(String value) {
        String stage = StringUtils.hasText(value) ? value.trim().toUpperCase() : "PROSPECT";
        if (!Set.of("PROSPECT", "ACTIVE", "DORMANT", "LOST").contains(stage)) throw new BusinessException("不支持的客户生命周期阶段");
        return stage;
    }

    private String paymentTerm(String value) {
        return Set.of("POSTPAID", "PREPAYMENT_REQUIRED", "FULL_PREPAYMENT").contains(blank(value).toUpperCase()) ? value.toUpperCase() : "POSTPAID";
    }
    private String shipmentPolicy(String value) {
        return Set.of("NONE", "SHIPMENT_PAID", "ORDER_PAID").contains(blank(value).toUpperCase()) ? value.toUpperCase() : "NONE";
    }
    private String depositMode(String value) { return "RATE".equalsIgnoreCase(value) ? "RATE" : "AMOUNT"; }

    private String quotePaymentTerm(Long quoteId) { return jdbcTemplate.queryForObject("select payment_term from crm_quote where id=?", String.class, quoteId); }
    private String quoteShipmentPolicy(Long quoteId) { return jdbcTemplate.queryForObject("select shipment_payment_policy from crm_quote where id=?", String.class, quoteId); }
    private String quoteDepositMode(Long quoteId) { return jdbcTemplate.queryForObject("select deposit_mode from crm_quote where id=?", String.class, quoteId); }
    private BigDecimal quoteDepositRate(Long quoteId) { return jdbcTemplate.queryForObject("select deposit_rate from crm_quote where id=?", BigDecimal.class, quoteId); }
    private BigDecimal quoteRequiredDeposit(Long quoteId) { return jdbcTemplate.queryForObject("select required_deposit_amount from crm_quote where id=?", BigDecimal.class, quoteId); }

    private CrmDtos.LeadRow lockLead(Long id) { return one(leadSelect() + " where l.id=? for update", leadMapper(), id, "线索不存在"); }
    private CrmDtos.OpportunityRow lockOpportunity(Long id) { return one(opportunitySelect() + " where o.id=? for update", opportunityMapper(), id, "商机不存在"); }
    private CrmDtos.QuoteRow lockOrReadQuote(Long id, boolean lock) { return one(quoteSelect() + " where q.id=?" + (lock ? " for update" : ""), quoteMapper(), id, "报价不存在"); }
    private CrmDtos.PlanRow lockPlan(Long id) { return one(planSelect() + " where fp.id=? for update", planMapper(), id, "跟进计划不存在"); }

    private String leadSelect() {
        return """
                select l.id,l.lead_no,l.subject,l.company_name,l.contact_name,l.phone,l.email,l.wechat,l.source_code,
                       l.demand,l.estimated_amount,l.owner_user_id,l.owner_name,l.status,l.partner_id,p.name partner_name,
                       l.contact_id,l.qualified_opportunity_id,l.disqualified_reason_code,l.disqualified_reason,
                       l.qualified_at,l.disqualified_at,l.created_at,l.updated_at
                from crm_lead l left join partner p on p.id=l.partner_id
                """;
    }

    private RowMapper<CrmDtos.LeadRow> leadMapper() {
        return (rs, rowNum) -> new CrmDtos.LeadRow(rs.getLong("id"), rs.getString("lead_no"), rs.getString("subject"),
                rs.getString("company_name"), rs.getString("contact_name"), rs.getString("phone"), rs.getString("email"),
                rs.getString("wechat"), rs.getString("source_code"), rs.getString("demand"), rs.getBigDecimal("estimated_amount"),
                rs.getLong("owner_user_id"), rs.getString("owner_name"), rs.getString("status"), longOrNull(rs.getObject("partner_id")),
                rs.getString("partner_name"), longOrNull(rs.getObject("contact_id")), longOrNull(rs.getObject("qualified_opportunity_id")),
                rs.getString("disqualified_reason_code"), rs.getString("disqualified_reason"), localDateTime(rs.getTimestamp("qualified_at")),
                localDateTime(rs.getTimestamp("disqualified_at")), localDateTime(rs.getTimestamp("created_at")), localDateTime(rs.getTimestamp("updated_at")));
    }

    private String customerSelect() {
        return """
                select cp.id,cp.partner_id,p.business_no,p.name partner_name,
                       coalesce(c.name,p.contact_person) contact_name,coalesce(c.phone,p.phone) contact_phone,
                       cp.lifecycle_stage,cp.tier_code,cp.source_code,cp.owner_user_id,cp.owner_name,cp.tags,
                       cp.annual_potential,cp.preferred_category,cp.competitor,cp.next_action,cp.next_action_date,
                       cp.repurchase_reminder_enabled,cp.repurchase_cycle_days,
                       cp.won_reason_code,cp.lost_reason_code,cp.lost_reason,cp.status,cp.updated_at,
                       coalesce((select sum(o.estimated_amount) from crm_opportunity o where o.partner_id=cp.partner_id and o.stage not in ('WON','LOST')),0) open_pipeline_amount,
                       (select max(a.occurred_at) from crm_activity a where a.partner_id=cp.partner_id) last_activity_at,
                       (select min(fp.due_at) from crm_followup_plan fp where fp.partner_id=cp.partner_id and fp.status='OPEN') next_plan_at
                from crm_customer_profile cp join partner p on p.id=cp.partner_id
                left join partner_contact c on c.partner_id=p.id and c.first_choice=1 and c.status='ENABLED'
                """;
    }

    private RowMapper<CrmDtos.CustomerRow> customerMapper() {
        return (rs, rowNum) -> new CrmDtos.CustomerRow(rs.getLong("id"), rs.getLong("partner_id"), rs.getString("business_no"),
                rs.getString("partner_name"), rs.getString("contact_name"), rs.getString("contact_phone"), rs.getString("lifecycle_stage"),
                rs.getString("tier_code"), rs.getString("source_code"), longOrNull(rs.getObject("owner_user_id")), rs.getString("owner_name"),
                rs.getString("tags"), rs.getBigDecimal("annual_potential"), rs.getString("preferred_category"), rs.getString("competitor"),
                rs.getString("next_action"), localDate(rs.getDate("next_action_date")),
                rs.getBoolean("repurchase_reminder_enabled"), rs.getInt("repurchase_cycle_days"), rs.getString("won_reason_code"),
                rs.getString("lost_reason_code"), rs.getString("lost_reason"),
                rs.getString("status"), localDateTime(rs.getTimestamp("updated_at")), rs.getBigDecimal("open_pipeline_amount"),
                localDateTime(rs.getTimestamp("last_activity_at")), localDateTime(rs.getTimestamp("next_plan_at")));
    }

    private String opportunitySelect() {
        return """
                select o.id,o.opportunity_no,o.partner_id,p.name partner_name,o.contact_id,c.name contact_name,
                       o.source_lead_id,o.title,o.stage,o.probability,o.estimated_amount,o.expected_close_date,
                       o.owner_user_id,o.owner_name,o.demand,o.competitor,o.win_reason_code,o.win_reason,
                       o.loss_reason_code,o.loss_reason,o.won_at,o.lost_at,o.updated_at
                from crm_opportunity o join partner p on p.id=o.partner_id
                left join partner_contact c on c.id=o.contact_id
                """;
    }

    private RowMapper<CrmDtos.OpportunityRow> opportunityMapper() {
        return (rs, rowNum) -> new CrmDtos.OpportunityRow(rs.getLong("id"), rs.getString("opportunity_no"), rs.getLong("partner_id"),
                rs.getString("partner_name"), longOrNull(rs.getObject("contact_id")), rs.getString("contact_name"), longOrNull(rs.getObject("source_lead_id")),
                rs.getString("title"), rs.getString("stage"), rs.getBigDecimal("probability"), rs.getBigDecimal("estimated_amount"),
                localDate(rs.getDate("expected_close_date")), rs.getLong("owner_user_id"), rs.getString("owner_name"), rs.getString("demand"),
                rs.getString("competitor"), rs.getString("win_reason_code"), rs.getString("win_reason"), rs.getString("loss_reason_code"),
                rs.getString("loss_reason"), localDateTime(rs.getTimestamp("won_at")), localDateTime(rs.getTimestamp("lost_at")),
                localDateTime(rs.getTimestamp("updated_at")));
    }

    private String quoteSelect() {
        return """
                select q.id,q.quote_no,q.revision_no,q.previous_quote_id,q.opportunity_id,o.opportunity_no,
                       q.partner_id,p.name partner_name,q.contact_id,c.name contact_name,q.address_id,q.title,
                       q.quote_date,q.valid_until,q.price_includes_tax,q.tax_rate,q.tax_rule_snapshot,q.original_amount,q.discount_rate,
                       q.discount_amount,q.adjustment_amount,q.amount_excluding_tax,q.tax_amount,q.total_amount,
                       q.payment_term,q.shipment_payment_policy,q.deposit_mode,q.deposit_rate,q.required_deposit_amount,
                       q.delivery_plan_date,q.memo,q.status,
                       q.decision_reason_code,q.decision_reason,q.sales_order_id,so.order_no sales_order_no,q.updated_at
                from crm_quote q join partner p on p.id=q.partner_id
                left join crm_opportunity o on o.id=q.opportunity_id
                left join partner_contact c on c.id=q.contact_id
                left join sales_order so on so.id=q.sales_order_id
                """;
    }

    private RowMapper<CrmDtos.QuoteRow> quoteMapper() {
        return (rs, rowNum) -> new CrmDtos.QuoteRow(rs.getLong("id"), rs.getString("quote_no"), rs.getInt("revision_no"),
                longOrNull(rs.getObject("previous_quote_id")), longOrNull(rs.getObject("opportunity_id")), rs.getString("opportunity_no"),
                rs.getLong("partner_id"), rs.getString("partner_name"), longOrNull(rs.getObject("contact_id")), rs.getString("contact_name"),
                longOrNull(rs.getObject("address_id")), rs.getString("title"), localDate(rs.getDate("quote_date")), localDate(rs.getDate("valid_until")),
                rs.getBoolean("price_includes_tax"), rs.getBigDecimal("tax_rate"), rs.getString("tax_rule_snapshot"),
                rs.getBigDecimal("original_amount"), rs.getBigDecimal("discount_rate"),
                rs.getBigDecimal("discount_amount"), rs.getBigDecimal("adjustment_amount"), rs.getBigDecimal("amount_excluding_tax"),
                rs.getBigDecimal("tax_amount"), rs.getBigDecimal("total_amount"), rs.getString("payment_term"),
                rs.getString("shipment_payment_policy"), rs.getString("deposit_mode"), rs.getBigDecimal("deposit_rate"),
                rs.getBigDecimal("required_deposit_amount"), localDate(rs.getDate("delivery_plan_date")), rs.getString("memo"),
                rs.getString("status"), rs.getString("decision_reason_code"),
                rs.getString("decision_reason"), longOrNull(rs.getObject("sales_order_id")), rs.getString("sales_order_no"),
                localDateTime(rs.getTimestamp("updated_at")));
    }

    private String activitySelect() {
        return """
                select a.id,a.target_type,a.target_id,a.partner_id,p.name partner_name,a.contact_id,c.name contact_name,
                       a.activity_type,a.subject,a.occurred_at,a.duration_minutes,a.outcome,a.next_action,
                       a.owner_user_id,a.owner_name,a.source_type,a.source_id,a.created_at
                from crm_activity a left join partner p on p.id=a.partner_id left join partner_contact c on c.id=a.contact_id
                """;
    }

    private RowMapper<CrmDtos.ActivityRow> activityMapper() {
        return (rs, rowNum) -> new CrmDtos.ActivityRow(rs.getLong("id"), rs.getString("target_type"), rs.getLong("target_id"),
                longOrNull(rs.getObject("partner_id")), rs.getString("partner_name"), longOrNull(rs.getObject("contact_id")), rs.getString("contact_name"),
                rs.getString("activity_type"), rs.getString("subject"), localDateTime(rs.getTimestamp("occurred_at")), rs.getInt("duration_minutes"),
                rs.getString("outcome"), rs.getString("next_action"), rs.getLong("owner_user_id"), rs.getString("owner_name"), rs.getString("source_type"),
                longOrNull(rs.getObject("source_id")), localDateTime(rs.getTimestamp("created_at")));
    }

    private String planSelect() {
        return """
                select fp.id,fp.target_type,fp.target_id,fp.partner_id,p.name partner_name,fp.contact_id,c.name contact_name,
                       fp.subject,fp.plan_type,fp.due_at,fp.priority,fp.reminder_minutes,fp.owner_user_id,fp.owner_name,
                       fp.status,fp.completion_activity_id,fp.cancel_reason,fp.completed_at,fp.cancelled_at,fp.created_at
                from crm_followup_plan fp left join partner p on p.id=fp.partner_id left join partner_contact c on c.id=fp.contact_id
                """;
    }

    private RowMapper<CrmDtos.PlanRow> planMapper() {
        return (rs, rowNum) -> new CrmDtos.PlanRow(rs.getLong("id"), rs.getString("target_type"), rs.getLong("target_id"),
                longOrNull(rs.getObject("partner_id")), rs.getString("partner_name"), longOrNull(rs.getObject("contact_id")), rs.getString("contact_name"),
                rs.getString("subject"), rs.getString("plan_type"), localDateTime(rs.getTimestamp("due_at")), rs.getString("priority"),
                rs.getInt("reminder_minutes"), rs.getLong("owner_user_id"), rs.getString("owner_name"), rs.getString("status"),
                longOrNull(rs.getObject("completion_activity_id")), rs.getString("cancel_reason"), localDateTime(rs.getTimestamp("completed_at")),
                localDateTime(rs.getTimestamp("cancelled_at")), localDateTime(rs.getTimestamp("created_at")));
    }

    private RowMapper<CrmDtos.EventRow> eventMapper() {
        return (rs, rowNum) -> new CrmDtos.EventRow(rs.getLong("id"), rs.getString("aggregate_type"), rs.getLong("aggregate_id"),
                rs.getString("event_type"), rs.getInt("event_version"), longOrNull(rs.getObject("partner_id")), rs.getLong("actor_user_id"),
                rs.getString("actor_name"), rs.getString("payload_json"), localDateTime(rs.getTimestamp("occurred_at")));
    }

    private RowMapper<CrmDtos.DuplicateCandidate> duplicateMapper() {
        return (rs, rowNum) -> new CrmDtos.DuplicateCandidate(rs.getString("match_type"), rs.getLong("source_id"),
                rs.getString("source_no"), rs.getString("name"), rs.getString("phone"), rs.getString("email"), rs.getString("status"));
    }

    private <T> T one(String sql, RowMapper<T> mapper, Object id, String notFound) {
        List<T> rows = jdbcTemplate.query(sql, mapper, id);
        if (rows.isEmpty()) throw new BusinessException(notFound);
        return rows.get(0);
    }

    private long insert(String sql, Object... args) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
            return statement;
        }, keyHolder);
        if (keyHolder.getKey() == null) throw new BusinessException("CRM 数据写入失败");
        return keyHolder.getKey().longValue();
    }

    private int scalarInt(String sql, Object... args) { Integer value = jdbcTemplate.queryForObject(sql, Integer.class, args); return value == null ? 0 : value; }
    private BigDecimal scalarDecimal(String sql, Object... args) { BigDecimal value = jdbcTemplate.queryForObject(sql, BigDecimal.class, args); return value == null ? BigDecimal.ZERO : value; }
    private BigDecimal taxExcluded(BigDecimal gross, BigDecimal rate) {
        if (rate.signum() == 0) return gross.setScale(2, RoundingMode.HALF_UP);
        return gross.multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(100).add(rate), 2, RoundingMode.HALF_UP);
    }
    private BigDecimal money(BigDecimal value) { return nvl(value).setScale(2, RoundingMode.HALF_UP); }
    private BigDecimal percent(BigDecimal value) { return nvl(value).setScale(4, RoundingMode.HALF_UP); }
    private BigDecimal nvl(BigDecimal value) { return value == null ? BigDecimal.ZERO : value; }
    private long nvl(Long value) { return value == null ? 0L : value; }
    private int nvl(Integer value) { return value == null ? 0 : value; }
    private int nonNegative(Integer value) { return value == null ? 0 : Math.max(0, value); }
    private String blank(String value) { return value == null ? "" : value.trim(); }
    private String normalized(String value) { return blank(value).toLowerCase(); }
    private String normalizePhone(String value) { return blank(value).replaceAll("[\\s\\-()]", ""); }
    private String requiredCode(String value, String label) { if (!StringUtils.hasText(value)) throw new BusinessException("请填写" + label); return value.trim().toUpperCase(); }
    private void validateLeadStatus(String status) { if (!LEAD_STATUSES.contains(status)) throw new BusinessException("不支持的线索状态"); }
    private Date date(LocalDate value) { return value == null ? null : Date.valueOf(value); }
    private Timestamp timestamp(LocalDateTime value) { return value == null ? null : Timestamp.valueOf(value); }
    private LocalDate localDate(Date value) { return value == null ? null : value.toLocalDate(); }
    private LocalDateTime localDateTime(Timestamp value) { return value == null ? null : value.toLocalDateTime(); }
    private Long longOrNull(Object value) { return value == null ? null : ((Number) value).longValue(); }

    private record TargetRef(String type, Long id, Long partnerId) {}
    private record ProductSnapshot(Long id, String sku, String name, String spec, String unit) {}
    private record PreparedQuote(BigDecimal originalAmount, BigDecimal discountAmount, BigDecimal adjustmentAmount,
                                 BigDecimal netAmount, BigDecimal taxAmount, BigDecimal totalAmount, String ruleSnapshot,
                                 List<PreparedQuoteItem> items) {}
    private record PreparedQuoteItem(CrmDtos.QuoteItemSave request, ProductSnapshot product, PackagingDtos.Plan plan,
                                     BigDecimal unitPrice, BigDecimal taxRate, BigDecimal netAmount,
                                     BigDecimal taxAmount, BigDecimal totalAmount, int sortNo,
                                     PriceCenterDtos.PriceSnapshot priceSnapshot) {}
}
