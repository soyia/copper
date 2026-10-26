package com.soyia.copperv2.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public final class CrmDtos {
    private CrmDtos() {}

    public record OptionRow(Long id, String optionType, String optionCode, String optionName,
                            Integer sortNo, boolean builtIn, String status) {}
    public record OptionSave(@NotBlank String optionType, @NotBlank String optionCode,
                             @NotBlank String optionName, Integer sortNo, String status) {}

    public record Dashboard(BigDecimal openPipelineAmount, Integer openOpportunityCount,
                            Integer openLeadCount, Integer overduePlanCount, Integer dueTodayPlanCount,
                            Integer activeQuoteCount, BigDecimal acceptedQuoteAmount,
                            Integer expiringQuoteCount, Integer repurchaseReminderCount,
                            Integer receivableReminderCount, BigDecimal receivableReminderAmount,
                            List<StageSummary> stages, List<PlanRow> urgentPlans,
                            List<QuoteRow> expiringQuotes,
                            List<CustomerAttentionRow> repurchaseReminders,
                            List<CustomerAttentionRow> receivableReminders,
                            List<CustomerAnalysisRow> customerRanking) {}
    public record StageSummary(String stage, Integer count, BigDecimal amount) {}

    public record DuplicateCandidate(String matchType, Long sourceId, String sourceNo,
                                     String name, String phone, String email, String status) {}

    public record LeadRow(Long id, String leadNo, String subject, String companyName, String contactName,
                          String phone, String email, String wechat, String sourceCode, String demand,
                          BigDecimal estimatedAmount, Long ownerUserId, String ownerName, String status,
                          Long partnerId, String partnerName, Long contactId, Long qualifiedOpportunityId,
                          String disqualifiedReasonCode, String disqualifiedReason,
                          LocalDateTime qualifiedAt, LocalDateTime disqualifiedAt,
                          LocalDateTime createdAt, LocalDateTime updatedAt) {}
    public record LeadDetail(LeadRow lead, List<ActivityRow> activities, List<PlanRow> plans,
                             List<EventRow> events) {}
    public record LeadSave(@NotBlank String subject, String companyName, String contactName,
                           String phone, String email, String wechat, String sourceCode, String demand,
                           @DecimalMin("0.00") BigDecimal estimatedAmount, Long partnerId, Long contactId) {}
    public record LeadQualify(@NotNull Long partnerId, Long contactId, boolean createOpportunity,
                              String opportunityTitle, LocalDate expectedCloseDate) {}

    public record CustomerRow(Long id, Long partnerId, String businessNo, String partnerName,
                              String contactName, String contactPhone, String lifecycleStage,
                              String tierCode, String sourceCode, Long ownerUserId, String ownerName,
                              String tags, BigDecimal annualPotential, String preferredCategory,
                              String competitor, String nextAction, LocalDate nextActionDate,
                              boolean repurchaseReminderEnabled, Integer repurchaseCycleDays,
                              String wonReasonCode, String lostReasonCode, String lostReason,
                              String status, LocalDateTime updatedAt,
                              BigDecimal openPipelineAmount, LocalDateTime lastActivityAt,
                              LocalDateTime nextPlanAt) {}
    public record CustomerProfileSave(@NotNull Long partnerId, String lifecycleStage, String tierCode,
                                      String sourceCode, String tags, @DecimalMin("0.00") BigDecimal annualPotential,
                                      String preferredCategory, String competitor, String nextAction,
                                      LocalDate nextActionDate, Boolean repurchaseReminderEnabled,
                                      @DecimalMin("1") @DecimalMax("3650") Integer repurchaseCycleDays,
                                      String wonReasonCode, String lostReasonCode,
                                      String lostReason) {}
    public record CustomerDetail(CustomerRow customer, CustomerAnalysis analysis,
                                 List<ContactRow> contacts, List<OpportunityRow> opportunities,
                                 List<QuoteRow> quotes, List<ActivityRow> activities,
                                 List<PlanRow> plans, List<EventRow> events) {}
    public record ContactRow(Long id, String name, String jobTitle, String department,
                             String phone, String wechat, String email, String preferredMethod,
                             boolean firstChoice, String status) {}
    public record CustomerAnalysis(Long partnerId, BigDecimal shippedRevenue, BigDecimal returnedRevenue,
                                   BigDecimal netRevenue, BigDecimal inventoryCost, BigDecimal returnedCost,
                                   BigDecimal grossProfit, BigDecimal grossMargin,
                                   BigDecimal receivableBalance, BigDecimal overdueAmount,
                                   BigDecimal receivedAmount, LocalDate lastBusinessDate,
                                   String recentProducts, BigDecimal openPipelineAmount) {}
    public record CustomerAnalysisRow(Long customerProfileId, Long partnerId, String partnerName,
                                      String contactName, String contactPhone, String lifecycleStage, String tierCode,
                                      boolean repurchaseReminderEnabled, Integer repurchaseCycleDays,
                                      BigDecimal netRevenue, BigDecimal grossProfit,
                                      BigDecimal receivableBalance, BigDecimal overdueAmount,
                                      LocalDate lastBusinessDate, LocalDate latestOrderDate,
                                      String recentProducts,
                                      BigDecimal openPipelineAmount) {}
    public record CustomerAttentionRow(Long customerProfileId, Long partnerId, String partnerName,
                                       String contactName, String contactPhone,
                                       BigDecimal receivableBalance, BigDecimal overdueAmount,
                                       LocalDate lastBusinessDate, LocalDate suggestedDate,
                                       Integer overdueDays, Integer repurchaseCycleDays,
                                       String recentProducts) {}

    public record OpportunityRow(Long id, String opportunityNo, Long partnerId, String partnerName,
                                 Long contactId, String contactName, Long sourceLeadId, String title,
                                 String stage, BigDecimal probability, BigDecimal estimatedAmount,
                                 LocalDate expectedCloseDate, Long ownerUserId, String ownerName,
                                 String demand, String competitor, String winReasonCode, String winReason,
                                 String lossReasonCode, String lossReason, LocalDateTime wonAt,
                                 LocalDateTime lostAt, LocalDateTime updatedAt) {}
    public record OpportunityDetail(OpportunityRow opportunity, List<QuoteRow> quotes,
                                    List<ActivityRow> activities, List<PlanRow> plans,
                                    List<EventRow> events) {}
    public record OpportunitySave(@NotNull Long partnerId, Long contactId, Long sourceLeadId,
                                  @NotBlank String title,
                                  @DecimalMin("0.00") @DecimalMax("100.00") BigDecimal probability,
                                  @DecimalMin("0.00") BigDecimal estimatedAmount,
                                  LocalDate expectedCloseDate, String demand, String competitor) {}
    public record StageChange(@NotBlank String stage, String reasonCode, String reason) {}
    public record ReasonAction(String reasonCode, @NotBlank String reason) {}

    public record QuoteRow(Long id, String quoteNo, Integer revisionNo, Long previousQuoteId,
                           Long opportunityId, String opportunityNo, Long partnerId, String partnerName,
                           Long contactId, String contactName, Long addressId, String title,
                           LocalDate quoteDate, LocalDate validUntil, boolean priceIncludesTax,
                           BigDecimal taxRate, String taxRuleSnapshot, BigDecimal originalAmount, BigDecimal discountRate,
                           BigDecimal discountAmount, BigDecimal adjustmentAmount,
                           BigDecimal amountExcludingTax, BigDecimal taxAmount, BigDecimal totalAmount,
                           String paymentTerm, String shipmentPaymentPolicy, String depositMode,
                           BigDecimal depositRate, BigDecimal requiredDepositAmount,
                           LocalDate deliveryPlanDate, String memo,
                           String status, String decisionReasonCode, String decisionReason,
                           Long salesOrderId, String salesOrderNo, LocalDateTime updatedAt) {}
    public record QuoteDetail(QuoteRow quote, List<QuoteItemRow> items,
                              List<ActivityRow> activities, List<PlanRow> plans,
                              List<EventRow> events) {}
    public record QuoteItemRow(Long id, Long productId, String productSkuSnapshot,
                               String productNameSnapshot, String productSpecSnapshot,
                               BigDecimal requestedQuantity, String requestedUnit,
                               BigDecimal actualBaseQuantity, BigDecimal baseQuantity, String baseUnit,
                               BigDecimal pricingQuantity, String priceUnit, String packageUnit,
                               String packagingPlanJson, Long packagingEffectiveRevisionId,
                               String packagingSnapshotHash, String priceSourceCode, String priceSourceName,
                               Long priceRuleVersionId, String priceSnapshotJson,
                               boolean noCharge, BigDecimal unitPrice,
                               BigDecimal taxRate, BigDecimal amountExcludingTax,
                               BigDecimal taxAmount, BigDecimal totalAmount, Integer sortNo) {}
    public record QuoteSave(Long opportunityId, @NotNull Long partnerId, Long contactId, Long addressId,
                            String title, @NotNull LocalDate quoteDate, @NotNull LocalDate validUntil,
                            boolean priceIncludesTax,
                            @DecimalMin("0.00") @DecimalMax("100.00") BigDecimal taxRate,
                            String taxRuleSnapshot,
                            @DecimalMin("0.00") @DecimalMax("100.00") BigDecimal discountRate,
                            BigDecimal adjustmentAmount, String paymentTerm,
                            String shipmentPaymentPolicy, String depositMode,
                            @DecimalMin("0.00") @DecimalMax("100.00") BigDecimal depositRate,
                            @DecimalMin("0.00") BigDecimal requiredDepositAmount,
                            LocalDate deliveryPlanDate, String memo,
                            @Valid @NotEmpty List<QuoteItemSave> items) {}
    public record QuoteItemSave(Long itemId, @NotNull Long productId,
                                @NotNull @DecimalMin("0.000001") @Digits(integer = 18, fraction = 6) BigDecimal quantity,
                                String quantityUnit,
                                @DecimalMin("0.000001") @Digits(integer = 18, fraction = 6) BigDecimal actualBaseQuantity,
                                String priceUnit, String packageUnit, boolean noCharge,
                                @NotNull @DecimalMin("0.0000") BigDecimal unitPrice) {}

    public record ActivityRow(Long id, String targetType, Long targetId, Long partnerId,
                              String partnerName, Long contactId, String contactName,
                              String activityType, String subject, LocalDateTime occurredAt,
                              Integer durationMinutes, String outcome, String nextAction,
                              Long ownerUserId, String ownerName, String sourceType,
                              Long sourceId, LocalDateTime createdAt) {}
    public record ActivitySave(@NotBlank String targetType, @NotNull Long targetId,
                               Long partnerId, Long contactId, @NotBlank String activityType,
                               @NotBlank String subject, @NotNull LocalDateTime occurredAt,
                               @DecimalMin("0") Integer durationMinutes, String outcome, String nextAction) {}

    public record PlanRow(Long id, String targetType, Long targetId, Long partnerId,
                          String partnerName, Long contactId, String contactName,
                          String subject, String planType, LocalDateTime dueAt,
                          String priority, Integer reminderMinutes, Long ownerUserId,
                          String ownerName, String status, Long completionActivityId,
                          String cancelReason, LocalDateTime completedAt,
                          LocalDateTime cancelledAt, LocalDateTime createdAt) {}
    public record PlanSave(@NotBlank String targetType, @NotNull Long targetId,
                           Long partnerId, Long contactId, @NotBlank String subject,
                           @NotBlank String planType, @NotNull LocalDateTime dueAt,
                           String priority, @DecimalMin("0") Integer reminderMinutes) {}
    public record PlanComplete(@NotBlank String outcome, String nextAction,
                               Integer durationMinutes, LocalDateTime occurredAt) {}

    public record EventRow(Long id, String aggregateType, Long aggregateId, String eventType,
                           Integer eventVersion, Long partnerId, Long actorUserId,
                           String actorName, String payloadJson, LocalDateTime occurredAt) {}

    public record QuoteConversion(Long quoteId, Long salesOrderId, String salesOrderNo) {}
}
