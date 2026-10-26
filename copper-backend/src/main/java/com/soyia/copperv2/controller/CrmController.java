package com.soyia.copperv2.controller;

import com.soyia.copperv2.dto.ApiResponse;
import com.soyia.copperv2.dto.CrmDtos;
import com.soyia.copperv2.service.CrmService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/crm")
public class CrmController {
    private final CrmService service;

    public CrmController(CrmService service) {
        this.service = service;
    }

    @GetMapping("/dashboard")
    public ApiResponse<CrmDtos.Dashboard> dashboard() {
        return ApiResponse.ok(service.dashboard());
    }

    @GetMapping("/options")
    public ApiResponse<List<CrmDtos.OptionRow>> options(
            @RequestParam(required = false) String optionType,
            @RequestParam(defaultValue = "false") boolean includeDisabled) {
        return ApiResponse.ok(service.options(optionType, includeDisabled));
    }

    @PostMapping("/options")
    public ApiResponse<Long> createOption(@Valid @RequestBody CrmDtos.OptionSave request) {
        return ApiResponse.ok(service.saveOption(null, request));
    }

    @PutMapping("/options/{id}")
    public ApiResponse<Long> updateOption(@PathVariable Long id,
                                          @Valid @RequestBody CrmDtos.OptionSave request) {
        return ApiResponse.ok(service.saveOption(id, request));
    }

    @GetMapping("/duplicates")
    public ApiResponse<List<CrmDtos.DuplicateCandidate>> duplicates(
            @RequestParam(required = false) String companyName,
            @RequestParam(required = false) String phone,
            @RequestParam(required = false) String email) {
        return ApiResponse.ok(service.duplicateCandidates(companyName, phone, email));
    }

    @GetMapping("/leads")
    public ApiResponse<List<CrmDtos.LeadRow>> leads(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(service.leads(status, keyword));
    }

    @GetMapping("/leads/{id}")
    public ApiResponse<CrmDtos.LeadDetail> lead(@PathVariable Long id) {
        return ApiResponse.ok(service.lead(id));
    }

    @PostMapping("/leads")
    public ApiResponse<Long> createLead(@Valid @RequestBody CrmDtos.LeadSave request) {
        return ApiResponse.ok(service.saveLead(null, request));
    }

    @PutMapping("/leads/{id}")
    public ApiResponse<Long> updateLead(@PathVariable Long id,
                                        @Valid @RequestBody CrmDtos.LeadSave request) {
        return ApiResponse.ok(service.saveLead(id, request));
    }

    @PostMapping("/leads/{id}/qualify")
    public ApiResponse<Long> qualifyLead(@PathVariable Long id,
                                         @Valid @RequestBody CrmDtos.LeadQualify request) {
        return ApiResponse.ok(service.qualifyLead(id, request));
    }

    @PostMapping("/leads/{id}/disqualify")
    public ApiResponse<Long> disqualifyLead(@PathVariable Long id,
                                            @Valid @RequestBody CrmDtos.ReasonAction request) {
        return ApiResponse.ok(service.disqualifyLead(id, request));
    }

    @PostMapping("/leads/{id}/reactivate")
    public ApiResponse<Long> reactivateLead(@PathVariable Long id,
                                            @Valid @RequestBody CrmDtos.ReasonAction request) {
        return ApiResponse.ok(service.reactivateLead(id, request));
    }

    @GetMapping("/customers")
    public ApiResponse<List<CrmDtos.CustomerRow>> customers(
            @RequestParam(required = false) String lifecycleStage,
            @RequestParam(required = false) String tierCode,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(service.customers(lifecycleStage, tierCode, keyword));
    }

    @GetMapping("/customers/{id}")
    public ApiResponse<CrmDtos.CustomerDetail> customer(@PathVariable Long id) {
        return ApiResponse.ok(service.customer(id));
    }

    @GetMapping("/customers/by-partner/{partnerId}/analysis")
    public ApiResponse<CrmDtos.CustomerAnalysis> customerAnalysis(@PathVariable Long partnerId) {
        return ApiResponse.ok(service.customerAnalysis(partnerId));
    }

    @PostMapping("/customers")
    public ApiResponse<Long> createCustomer(@Valid @RequestBody CrmDtos.CustomerProfileSave request) {
        return ApiResponse.ok(service.saveCustomerProfile(null, request));
    }

    @PutMapping("/customers/{id}")
    public ApiResponse<Long> updateCustomer(@PathVariable Long id,
                                            @Valid @RequestBody CrmDtos.CustomerProfileSave request) {
        return ApiResponse.ok(service.saveCustomerProfile(id, request));
    }

    @GetMapping("/opportunities")
    public ApiResponse<List<CrmDtos.OpportunityRow>> opportunities(
            @RequestParam(required = false) String stage,
            @RequestParam(required = false) Long partnerId,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(service.opportunities(stage, partnerId, keyword));
    }

    @GetMapping("/opportunities/{id}")
    public ApiResponse<CrmDtos.OpportunityDetail> opportunity(@PathVariable Long id) {
        return ApiResponse.ok(service.opportunity(id));
    }

    @PostMapping("/opportunities")
    public ApiResponse<Long> createOpportunity(@Valid @RequestBody CrmDtos.OpportunitySave request) {
        return ApiResponse.ok(service.saveOpportunity(null, request));
    }

    @PutMapping("/opportunities/{id}")
    public ApiResponse<Long> updateOpportunity(@PathVariable Long id,
                                               @Valid @RequestBody CrmDtos.OpportunitySave request) {
        return ApiResponse.ok(service.saveOpportunity(id, request));
    }

    @PostMapping("/opportunities/{id}/stage")
    public ApiResponse<Long> changeStage(@PathVariable Long id,
                                         @Valid @RequestBody CrmDtos.StageChange request) {
        return ApiResponse.ok(service.changeOpportunityStage(id, request));
    }

    @GetMapping("/quotes")
    public ApiResponse<List<CrmDtos.QuoteRow>> quotes(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long partnerId,
            @RequestParam(required = false) Long opportunityId) {
        return ApiResponse.ok(service.quotes(status, partnerId, opportunityId));
    }

    @GetMapping("/quotes/{id}")
    public ApiResponse<CrmDtos.QuoteDetail> quote(@PathVariable Long id) {
        return ApiResponse.ok(service.quote(id));
    }

    @PostMapping("/quotes")
    public ApiResponse<Long> createQuote(@Valid @RequestBody CrmDtos.QuoteSave request) {
        return ApiResponse.ok(service.saveQuote(null, request));
    }

    @PutMapping("/quotes/{id}")
    public ApiResponse<Long> updateQuote(@PathVariable Long id,
                                         @Valid @RequestBody CrmDtos.QuoteSave request) {
        return ApiResponse.ok(service.saveQuote(id, request));
    }

    @PostMapping("/quotes/{id}/activate")
    public ApiResponse<Long> activateQuote(@PathVariable Long id) {
        return ApiResponse.ok(service.activateQuote(id));
    }

    @PostMapping("/quotes/{id}/revise")
    public ApiResponse<Long> reviseQuote(@PathVariable Long id,
                                         @Valid @RequestBody CrmDtos.ReasonAction request) {
        return ApiResponse.ok(service.reviseQuote(id, request));
    }

    @PostMapping("/quotes/{id}/accept")
    public ApiResponse<Long> acceptQuote(@PathVariable Long id,
                                         @Valid @RequestBody CrmDtos.ReasonAction request) {
        return ApiResponse.ok(service.decideQuote(id, "ACCEPTED", request));
    }

    @PostMapping("/quotes/{id}/reject")
    public ApiResponse<Long> rejectQuote(@PathVariable Long id,
                                         @Valid @RequestBody CrmDtos.ReasonAction request) {
        return ApiResponse.ok(service.decideQuote(id, "REJECTED", request));
    }

    @PostMapping("/quotes/{id}/cancel")
    public ApiResponse<Long> cancelQuote(@PathVariable Long id,
                                         @Valid @RequestBody CrmDtos.ReasonAction request) {
        return ApiResponse.ok(service.decideQuote(id, "CANCELLED", request));
    }

    @PostMapping("/quotes/{id}/convert-to-sales-order")
    public ApiResponse<CrmDtos.QuoteConversion> convertQuote(@PathVariable Long id) {
        return ApiResponse.ok(service.convertQuote(id));
    }

    @GetMapping("/activities")
    public ApiResponse<List<CrmDtos.ActivityRow>> activities(
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) Long targetId) {
        return ApiResponse.ok(service.activities(targetType, targetId));
    }

    @PostMapping("/activities")
    public ApiResponse<Long> createActivity(@Valid @RequestBody CrmDtos.ActivitySave request) {
        return ApiResponse.ok(service.createActivity(request));
    }

    @GetMapping("/plans")
    public ApiResponse<List<CrmDtos.PlanRow>> plans(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String dueScope,
            @RequestParam(defaultValue = "true") boolean overdueFirst) {
        return ApiResponse.ok(service.plans(status, dueScope, overdueFirst));
    }

    @PostMapping("/plans")
    public ApiResponse<Long> createPlan(@Valid @RequestBody CrmDtos.PlanSave request) {
        return ApiResponse.ok(service.savePlan(null, request));
    }

    @PutMapping("/plans/{id}")
    public ApiResponse<Long> updatePlan(@PathVariable Long id,
                                        @Valid @RequestBody CrmDtos.PlanSave request) {
        return ApiResponse.ok(service.savePlan(id, request));
    }

    @PostMapping("/plans/{id}/complete")
    public ApiResponse<Long> completePlan(@PathVariable Long id,
                                          @Valid @RequestBody CrmDtos.PlanComplete request) {
        return ApiResponse.ok(service.completePlan(id, request));
    }

    @PostMapping("/plans/{id}/cancel")
    public ApiResponse<Long> cancelPlan(@PathVariable Long id,
                                        @Valid @RequestBody CrmDtos.ReasonAction request) {
        return ApiResponse.ok(service.cancelPlan(id, request));
    }
}
