package com.soyia.copperv2.security;

import com.soyia.copperv2.dto.ApiResponse;
import com.soyia.copperv2.dto.DashboardDto;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ControllerAdvice
public class BusinessDataScopeAdvice implements ResponseBodyAdvice<Object> {
    private static final Map<String, String> DOCUMENT_PATHS = new LinkedHashMap<>();
    static {
        DOCUMENT_PATHS.put("/purchase-orders", "PURCHASE_ORDER");
        DOCUMENT_PATHS.put("/inbounds", "INBOUND");
        DOCUMENT_PATHS.put("/sales-orders", "SALES_ORDER");
        DOCUMENT_PATHS.put("/outbounds", "OUTBOUND");
        DOCUMENT_PATHS.put("/direct-trades", "DIRECT_TRADE");
        DOCUMENT_PATHS.put("/sales-returns", "SALES_RETURN");
        DOCUMENT_PATHS.put("/payments", "PAYMENT");
        DOCUMENT_PATHS.put("/supplier-payments", "SUPPLIER_PAYMENT");
        DOCUMENT_PATHS.put("/receivable-adjustments", "RECEIVABLE_ADJUSTMENT");
        DOCUMENT_PATHS.put("/acceptance-bills", "ACCEPTANCE_BILL");
        DOCUMENT_PATHS.put("/partner-fund-adjustments", "PARTNER_FUND_ADJUSTMENT");
        DOCUMENT_PATHS.put("/treasury/documents", "TREASURY_DOCUMENT");
        DOCUMENT_PATHS.put("/inventory/non-trade", "INVENTORY_NON_TRADE");
        DOCUMENT_PATHS.put("/inventory/landed-cost-allocations", "LANDED_COST_ALLOCATION");
        DOCUMENT_PATHS.put("/customer-advance-refunds", "CUSTOMER_ADVANCE_REFUND");
    }

    private final BusinessDataScopeService dataScopeService;

    public BusinessDataScopeAdvice(BusinessDataScopeService dataScopeService) {
        this.dataScopeService = dataScopeService;
    }

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) { return true; }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (!(body instanceof ApiResponse<?> apiResponse) || apiResponse.code() != 0 || SecurityContext.get() == null) return body;
        String path = UriComponentsBuilder.fromUri(request.getURI()).build().getPath();
        if (path == null) return body;
        if (path.startsWith("/api")) path = path.substring(4);
        String method = request.getMethod().name();
        Object data = apiResponse.data();

        if ("POST".equals(method) && data instanceof Map<?, ?> map) {
            Long id = number(map.get("id"));
            String resourceType = documentResource(path);
            if (resourceType != null && id != null) dataScopeService.stampDocumentOwner(resourceType, id);
        }
        if (!"GET".equals(method)) return body;
        if ("/dashboard".equals(path) && data instanceof DashboardDto dashboard)
            return new ApiResponse<>(apiResponse.code(), apiResponse.message(), dataScopeService.scopedDashboard(dashboard));

        for (Map.Entry<String, String> entry : DOCUMENT_PATHS.entrySet()) {
            if (path.equals(entry.getKey()) && data instanceof List<?> rows)
                return new ApiResponse<>(apiResponse.code(), apiResponse.message(), dataScopeService.filterDocumentList(entry.getValue(), rows));
            Long recordId = pathRecordId(path, entry.getKey());
            if (recordId != null) dataScopeService.assertDocumentAllowed(entry.getValue(), recordId);
        }

        if ((path.equals("/partners") || path.equals("/partners/summaries")) && data instanceof List<?> rows)
            return new ApiResponse<>(apiResponse.code(), apiResponse.message(), dataScopeService.filterMasterList("PARTNER", rows, "id", "partnerId"));
        if ((path.equals("/products") || path.equals("/products/packaging-summaries")) && data instanceof List<?> rows)
            return new ApiResponse<>(apiResponse.code(), apiResponse.message(), dataScopeService.filterMasterList("PRODUCT", rows, "id", "productId"));
        if (path.equals("/warehouses") && data instanceof List<?> rows)
            return new ApiResponse<>(apiResponse.code(), apiResponse.message(), dataScopeService.filterMasterList("WAREHOUSE", rows, "id", "warehouseId"));
        if (path.equals("/inventory/batches") && data instanceof List<?> rows) {
            List<?> filtered = dataScopeService.filterMasterList("WAREHOUSE", rows, "warehouseId");
            filtered = dataScopeService.filterMasterList("PRODUCT", filtered, "productId");
            @SuppressWarnings("unchecked")
            List<com.soyia.copperv2.dto.InventoryBatchDto> inventoryRows = (List<com.soyia.copperv2.dto.InventoryBatchDto>) filtered;
            return new ApiResponse<>(apiResponse.code(), apiResponse.message(), dataScopeService.sanitizeInventory(inventoryRows));
        }
        if (path.equals("/partner-accounts") && data instanceof List<?> rows) {
            List<?> filtered = dataScopeService.filterMasterList("ACCOUNT", rows, "id");
            filtered = dataScopeService.filterMasterList("PARTNER", filtered, "partnerId");
            return new ApiResponse<>(apiResponse.code(), apiResponse.message(), filtered);
        }
        if ((path.equals("/payables") || path.equals("/receivables")) && data instanceof List<?> rows)
            return new ApiResponse<>(apiResponse.code(), apiResponse.message(), dataScopeService.filterDerivedDocumentList(rows));
        if (path.equals("/partner-account-logs") && data instanceof List<?> rows)
            return new ApiResponse<>(apiResponse.code(), apiResponse.message(), dataScopeService.filterPartnerAccountRecords(rows, "partnerId", "accountType", "TRADE"));
        if (path.equals("/customer-advances") && data instanceof List<?> rows)
            return new ApiResponse<>(apiResponse.code(), apiResponse.message(), dataScopeService.filterPartnerAccountRecords(rows, "customerId", "accountType", "CUSTOMER_ADVANCE"));
        Long partnerId = pathRecordId(path, "/partners");
        if (partnerId != null) dataScopeService.assertMasterAllowed("PARTNER", partnerId);
        Long productId = pathRecordId(path, "/products");
        if (productId != null) dataScopeService.assertMasterAllowed("PRODUCT", productId);
        return body;
    }

    private String documentResource(String path) {
        for (Map.Entry<String, String> entry : DOCUMENT_PATHS.entrySet()) if (path.equals(entry.getKey()) || path.startsWith(entry.getKey() + "/")) return entry.getValue();
        return null;
    }

    private Long pathRecordId(String path, String prefix) {
        Matcher matcher = Pattern.compile("^" + Pattern.quote(prefix) + "/(\\d+)(?:/.*)?$").matcher(path);
        return matcher.matches() ? Long.parseLong(matcher.group(1)) : null;
    }

    private Long number(Object value) { return value instanceof Number number ? number.longValue() : null; }
}
