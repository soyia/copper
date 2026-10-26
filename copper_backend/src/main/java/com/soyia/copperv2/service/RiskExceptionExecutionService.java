package com.soyia.copperv2.service;

import com.soyia.copperv2.dto.SupplierPaymentCreateRequest;
import com.soyia.copperv2.security.ForbiddenException;
import com.soyia.copperv2.security.SecurityContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RiskExceptionExecutionService {
    private final RiskExceptionService riskExceptionService;
    private final CopperService copperService;

    public RiskExceptionExecutionService(RiskExceptionService riskExceptionService, CopperService copperService) {
        this.riskExceptionService = riskExceptionService;
        this.copperService = copperService;
    }

    @Transactional
    public Long execute(Long requestId) {
        RiskExceptionService.RequestLock request = riskExceptionService.executableRequest(requestId);
        if (!"SUPPLIER_PAYMENT_INTENT".equals(request.sourceType()) || !"CREATE".equals(request.actionCode())) {
            throw new BusinessException("该审批必须回到原业务单据执行，审批中心不会自动改写库存、资金或单据状态");
        }
        SecurityContext.Principal actor = SecurityContext.require();
        if (!actor.hasPermission("finance.manage")) {
            throw new ForbiddenException("当前账号没有供应商付款执行权限；审批通过不替代原业务权限");
        }
        SupplierPaymentCreateRequest payment = riskExceptionService.supplierPaymentFromSnapshot(request);
        return copperService.createSupplierPayment(payment);
    }
}
