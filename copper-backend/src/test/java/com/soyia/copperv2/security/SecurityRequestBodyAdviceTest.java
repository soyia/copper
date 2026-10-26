package com.soyia.copperv2.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SecurityRequestBodyAdviceTest {
    @AfterEach
    void clearContext() {
        SecurityContext.clear();
    }

    @Test
    void rejectsOutOfScopeMasterAndDocumentReferencesBeforeControllerRuns() {
        BusinessDataScopeService scope = mock(BusinessDataScopeService.class);
        SecurityContext.set(new SecurityContext.Principal(
                9L, 1L, 2L, "业务员", "000002", 3L, "销售部", "seller",
                false, List.of("销售"), Set.of("sales.manage")
        ));
        SecurityRequestBodyAdvice advice = new SecurityRequestBodyAdvice(scope);
        Request request = new Request(11L, 12L, List.of(new Line(13L)));

        advice.afterBodyRead(request, null, null, Request.class, null);

        verify(scope).assertMasterAllowed("PARTNER", 11L);
        verify(scope).assertDocumentAllowed("SALES_ORDER", 12L);
        verify(scope).assertMasterAllowed("PRODUCT", 13L);
    }

    private record Request(Long customerId, Long salesOrderId, List<Line> items) {}
    private record Line(Long productId) {}
}
