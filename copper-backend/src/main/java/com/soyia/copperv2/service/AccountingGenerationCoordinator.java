package com.soyia.copperv2.service;

import com.soyia.copperv2.dto.AccountingDtos;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class AccountingGenerationCoordinator {
    private final AccountingService accountingService;

    public AccountingGenerationCoordinator(AccountingService accountingService) {
        this.accountingService = accountingService;
    }

    public void generateSafely(Long businessPostingBatchId) {
        try {
            accountingService.generateForBusinessPosting(businessPostingBatchId);
        } catch (RuntimeException exception) {
            accountingService.recordGenerationFailure(businessPostingBatchId, exception);
        }
    }

    public AccountingDtos.GenerateResult generateMissingSafely() {
        List<Long> voucherIds = new ArrayList<>();
        int cancelled = 0;
        int issues = 0;
        for (Long batchId : accountingService.missingBusinessPostingBatchIds()) {
            try {
                AccountingService.GenerationOutcome outcome = accountingService.generateForBusinessPosting(batchId);
                voucherIds.addAll(outcome.voucherIds());
                cancelled += outcome.cancelledCount();
            } catch (RuntimeException exception) {
                accountingService.recordGenerationFailure(batchId, exception);
                issues++;
            }
        }
        return new AccountingDtos.GenerateResult(voucherIds.size(), cancelled, issues, voucherIds);
    }
}
