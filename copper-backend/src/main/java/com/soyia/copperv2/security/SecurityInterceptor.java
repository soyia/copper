package com.soyia.copperv2.security;

import com.soyia.copperv2.service.StorageCapacityService;
import com.soyia.copperv2.service.TrustedTimeSafetyService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class SecurityInterceptor implements HandlerInterceptor {
    private final AuthService authService;
    private final BusinessDataScopeService dataScopeService;
    private final StorageCapacityService storageCapacityService;
    private final TrustedTimeSafetyService trustedTimeSafetyService;
    private final SecurityAuditService auditService;

    public SecurityInterceptor(AuthService authService, BusinessDataScopeService dataScopeService,
                               StorageCapacityService storageCapacityService,
                               TrustedTimeSafetyService trustedTimeSafetyService,
                               SecurityAuditService auditService) {
        this.authService = authService;
        this.dataScopeService = dataScopeService;
        this.storageCapacityService = storageCapacityService;
        this.trustedTimeSafetyService = trustedTimeSafetyService;
        this.auditService = auditService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (HttpMethod.OPTIONS.matches(request.getMethod())) return true;
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (HttpMethod.GET.matches(request.getMethod()) && path.startsWith("/actuator/health")) return true;
        if ("/auth/login".equals(path)) return true;
        if ((HttpMethod.GET.matches(request.getMethod())
                && (path.startsWith("/public/order-shares/") || path.startsWith("/open-api/v1/orders/")))
                || path.startsWith("/public/confirmations/"))
            return true;
        SecurityContext.Principal principal;
        try {
            principal = authService.authenticateSession(sessionToken(request));
        } catch (UnauthorizedException ex) {
            auditService.recordForActor(0L, "未知会话", null, "", "SESSION_REJECTED",
                    SecurityAuditService.DENIED, "SESSION_INVALID_OR_EXPIRED", "SESSION", 0L, "",
                    "无效或已过期会话访问被拒绝", null, null, "");
            throw ex;
        }
        SecurityContext.set(principal);
        try {
            if (path.startsWith("/auth/")) return true;
            if (principal.passwordChangeRequired()) throw new ForbiddenException("首次登录必须先修改临时密码");
            if (!isReadOperation(path, request.getMethod())) {
                storageCapacityService.assertBusinessWriteAllowed();
                trustedTimeSafetyService.assertHighRiskWriteAllowed(request.getMethod(), path);
            }
            String permission = requiredPermission(path, request.getMethod());
            request.setAttribute("copper.requiredPermission", permission);
            if (!hasRequiredPermission(principal, permission)) {
                auditService.denied("ACCESS_DENIED", "MISSING_PERMISSION", "API", 0L,
                        "接口访问被拒绝；缺少权限：" + permission);
                request.setAttribute("copper.denialAudited", Boolean.TRUE);
                throw new ForbiddenException("没有“" + permission + "”权限，请联系管理员");
            }
            assertDocumentPath(path);
            assertMasterPath(path);
            return true;
        } catch (RuntimeException ex) {
            if (ex instanceof ForbiddenException && !Boolean.TRUE.equals(request.getAttribute("copper.denialAudited"))) {
                auditService.denied("ACCESS_DENIED", "DATA_SCOPE_OR_ACCOUNT_POLICY", targetType(path),
                        targetId(path), "接口访问被数据范围或账号安全策略拒绝");
            } else if (!(ex instanceof ForbiddenException)) {
                auditService.failed("API_REQUEST_REJECTED", ex.getClass().getSimpleName(), targetType(path),
                        targetId(path), "写入前置校验未通过");
            }
            SecurityContext.clear();
            throw ex;
        }
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        try {
            String path = request.getRequestURI().substring(request.getContextPath().length());
            boolean write = !HttpMethod.OPTIONS.matches(request.getMethod())
                    && !isReadOperation(path, request.getMethod());
            if (write && !path.startsWith("/auth/") && SecurityContext.get() != null) {
                String permission = String.valueOf(request.getAttribute("copper.requiredPermission"));
                String summary = request.getMethod() + " " + path + "；权限=" + permission;
                if (ex == null && response.getStatus() < 400) {
                    auditService.success("API_WRITE", targetType(path), targetId(path), "", summary);
                } else {
                    String failure = ex == null ? "HTTP_" + response.getStatus() : ex.getClass().getSimpleName();
                    auditService.failed("API_WRITE", failure, targetType(path), targetId(path), summary);
                }
            }
        } finally {
            SecurityContext.clear();
        }
    }

    private String requiredPermission(String path, String method) {
        boolean read = isReadOperation(path, method);
        if (path.equals("/dashboard")) return "dashboard.view";
        if (path.startsWith("/operations")) {
            if (path.startsWith("/operations/sales-costs") && !read) return "sales.cost.manage";
            return "operations.insight.view";
        }
        if (path.startsWith("/direct-trades")) {
            if (read) return "direct.trade.view";
            if (path.endsWith("/confirm") || path.endsWith("/cancel")) return "direct.trade.confirm";
            return "direct.trade.manage";
        }
        if (path.startsWith("/external-shares"))
            return read ? "external.share.manage" : "external.share.manage";
        if (path.startsWith("/external-confirmation-packets"))
            return "external.confirm.manage";
        if (path.startsWith("/document-intakes"))
            return read ? "document.intake.view" : "document.intake.manage";
        if (path.startsWith("/integration-keys"))
            return "integration.key.manage";
        if (path.startsWith("/organization/")) return read ? "organization.view" : "organization.manage";
        if (path.startsWith("/system/users")) return read ? "user.view" : "user.manage";
        if (path.startsWith("/system/roles")) return read ? "role.view" : "role.manage";
        if (path.startsWith("/system/security-audit")) return "security.audit.view";
        if (path.startsWith("/system/permissions") || path.startsWith("/system/data-scope-options")) return "role.view";
        if (path.startsWith("/system/storage-capacity")) return "storage.capacity.view";
        if (path.startsWith("/business-exports")) return "data.export";
        if (path.startsWith("/data-migrations")) {
            if (read) return "data.migration.view";
            if (path.endsWith("/apply")) return "data.migration.apply";
            if (path.endsWith("/rollback")) return "data.migration.rollback";
            if (path.endsWith("/cutover")) return "data.migration.cutover";
            if (path.endsWith("/consistency-check")) return "data.migration.view";
            return "data.migration.manage";
        }
        if (path.startsWith("/risk-exceptions/policy") && !read) return "risk.exception.policy.manage";
        if (path.startsWith("/risk-exceptions") && !read &&
                (path.endsWith("/approve") || path.endsWith("/reject") || path.endsWith("/transfer")))
            return "risk.exception.approve";
        if (path.startsWith("/risk-exceptions") && !read) return "risk.exception.request";
        if (path.startsWith("/risk-exceptions")) return "risk.exception.view";
        if (path.startsWith("/price-center")) return read ? "pricing.view" : "pricing.manage";
        if (path.startsWith("/tax-compliance/workpapers") && !read) return "tax.compliance.review";
        if (path.startsWith("/tax-compliance")) return read ? "tax.compliance.view" : "tax.compliance.manage";
        if (path.startsWith("/evidence")) return read ? "evidence.view" : "evidence.manage";
        if (path.startsWith("/business-postings")) return read ? "posting.view" : "posting.manage";
        if (path.startsWith("/tax-invoices") && !read && path.endsWith("/red-confirmation")) return "invoice.red.manage";
        if (path.startsWith("/tax-invoices") && !read &&
                (path.endsWith("/verify") || path.endsWith("/booking-confirm") ||
                        path.endsWith("/usage-confirm") || path.endsWith("/usage-transfer-out")))
            return "invoice.verify";
        if (path.startsWith("/tax-invoices") && !read && path.endsWith("/void")) return "invoice.void";
        if (path.startsWith("/tax-invoices") && !read && path.endsWith("/confirm")) return "invoice.confirm";
        if (path.startsWith("/tax-invoices")) return read ? "invoice.view" : "invoice.manage";
        if (path.startsWith("/financial/reports")) return "financial.report.view";
        if (path.startsWith("/financial/bank-accounts")) {
            return read ? "financial.bank.view" : "financial.bank.account.manage";
        }
        if (path.startsWith("/financial/bank-reconciliations")) {
            return read ? "financial.bank.view" : "financial.bank.manage";
        }
        if (path.startsWith("/financial/period-close")) {
            if (!read && path.endsWith("/reopen")) return "financial.close.reopen";
            return read ? "financial.report.view" : "financial.close.manage";
        }
        if (path.startsWith("/crm/options") && !read) return "crm.option.manage";
        if (path.startsWith("/crm/quotes") && path.endsWith("/convert-to-sales-order")) return "crm.quote.convert";
        if (path.startsWith("/crm/dashboard") || path.contains("/analysis")) return "crm.analysis.view";
        if (path.startsWith("/crm/")) return read ? "crm.record.view" : "crm.record.manage";
        if (path.startsWith("/accounting/periods") && !read && path.endsWith("/reopen"))
            return "financial.close.reopen";
        if (path.startsWith("/accounting/periods") && !read && path.endsWith("/close"))
            return "financial.close.manage";
        if (path.startsWith("/accounting/periods")) return read ? "accounting.view" : "accounting.period.manage";
        if (path.startsWith("/accounting/rules")) return read ? "accounting.view" : "accounting.rule.manage";
        if (path.startsWith("/accounting/archives")) return "accounting.archive";
        if (path.startsWith("/accounting") && !read &&
                (path.endsWith("/review") || path.endsWith("/reject"))) return "accounting.review";
        if (path.startsWith("/accounting") && !read &&
                (path.endsWith("/post") || path.endsWith("/reverse"))) return "accounting.post";
        if (path.startsWith("/accounting")) return read ? "accounting.view" : "accounting.manage";
        if (path.startsWith("/bank-accounts")) return read ? "finance.sensitive.view" : "finance.manage";
        if (path.startsWith("/settlement-reports")) return "finance.view";
        if (startsWithAny(path, "/partners", "/contacts", "/addresses", "/followups", "/special-dates"))
            return read ? "partner.view" : "partner.manage";
        if (path.equals("/product-reference-values") && read) return "product.view";
        if (path.startsWith("/product-series/")
                && path.endsWith("/allowed-units") && read) return "product.view";
        if (path.startsWith("/product-reference-")
                || path.startsWith("/product-source-")
                || path.startsWith("/product-imports")
                || (path.startsWith("/product-series/")
                    && (path.contains("/identity-schema") || path.contains("/allowed-units")))
                || (path.startsWith("/products/")
                    && (path.contains("/packaging-variants")
                        || path.contains("/supplier-offers")
                        || path.endsWith("/ports")
                        || path.endsWith("/conductor-construction")
                        || path.contains("/technical-specs")
                        || path.contains("/bom")
                        || path.endsWith("/identity/publish")))
                || path.equals("/products/duplicate-check")) {
            if (path.contains("/supplier-offers")) {
                return read ? "product.cost.view" : "product.supplier.offer.manage";
            }
            if (path.startsWith("/product-imports")) {
                return read ? "product.master.complex.view" : "product.master.complex.manage";
            }
            return read ? "product.master.complex.view" : "product.master.complex.manage";
        }
        if (startsWithAny(path, "/products", "/product-", "/custom-fields", "/unit-categories", "/units", "/packaging-templates")) {
            if (path.endsWith("/purchase-options")) return "product.cost.view";
            if (read || path.endsWith("/packaging-plan")) return "product.view";
            return "product.manage";
        }
        if (path.startsWith("/warehouses")) return read ? "warehouse.view" : "warehouse.manage";
        if (path.startsWith("/inventory")) {
            if (path.startsWith("/inventory/non-trade")) {
                return read ? "inventory.nontrade.view" : "inventory.nontrade.manage";
            }
            if (path.contains("/quality-certificates")) {
                return read ? "inventory.quality.view" : "inventory.quality.manage";
            }
            if (path.startsWith("/inventory/landed-cost-allocations")) {
                if (read) return "landed.cost.view";
                if (path.endsWith("/confirm")) return "landed.cost.confirm";
                if (path.endsWith("/reverse")) return "landed.cost.reverse";
                return "landed.cost.manage";
            }
            if (read) {
                if (path.startsWith("/inventory/cost-")) return "inventory.cost.view";
                if (path.startsWith("/inventory/purchase-returns")) return "purchase.view";
                return "inventory.view";
            }
            if (path.startsWith("/inventory/transfers")) return "inventory.transfer";
            if (path.startsWith("/inventory/stocktakes")) return "inventory.stocktake";
            if (path.startsWith("/inventory/purchase-returns")) return "purchase.return";
            if (path.startsWith("/inventory/cost-adjustments")) return "inventory.cost.adjust";
            return "inventory.adjust";
        }
        if (path.startsWith("/purchase-orders")) {
            if (path.contains("/followup")) return read ? "purchase.followup.view" : "purchase.followup.manage";
            if (read) return "purchase.view";
            if (path.contains("/quotes")) return "purchase.quote.manage";
            if (path.contains("/amendments")) return "purchase.amend";
            if (path.endsWith("/confirm") || path.endsWith("/cancel")) return "purchase.confirm";
            return "purchase.manage";
        }
        if (path.startsWith("/inbounds")) {
            if (read) return "inbound.view";
            if (path.endsWith("/audit") || path.endsWith("/reverse")) return "inbound.audit";
            if (path.endsWith("/submit") || path.endsWith("/withdraw")) return "inbound.submit";
            return "inbound.manage";
        }
        if (path.startsWith("/sales-orders")) {
            if (read) return "sales.view";
            if (path.contains("/amendments")) return "sales.amend";
            if (path.endsWith("/confirm") || path.endsWith("/cancel")) return "sales.confirm";
            return "sales.manage";
        }
        if (path.startsWith("/sales-risk/partners")) return "sales.risk.policy.manage";
        if (path.startsWith("/outbounds") || path.startsWith("/sales-returns")) {
            if (read) return "outbound.view";
            if (path.contains("/delivery")) return "outbound.delivery.manage";
            if (path.endsWith("/reverse")) return "outbound.reverse";
            return "outbound.manage";
        }
        if (path.startsWith("/partner-fund-adjustments")) {
            if (read) return "partner.fund.adjust.view";
            if (path.endsWith("/confirm")) return "partner.fund.adjust.approve";
            if (path.endsWith("/reverse")) return "partner.fund.adjust.reverse";
            return "partner.fund.adjust.manage";
        }
        if (path.startsWith("/treasury")) {
            if (read) return "treasury.view";
            if (path.startsWith("/treasury/payment-requests")) return "treasury.payment.request";
            if (path.contains("/funds/") && path.endsWith("/reverse")) return "treasury.reverse";
            if (path.startsWith("/treasury/transfers") || path.endsWith("/settlements"))
                return "treasury.fund.manage";
            if (path.endsWith("/reverse")) return "treasury.reverse";
            if (path.endsWith("/confirm")) return "treasury.document.confirm";
            return "treasury.document.manage";
        }
        if (startsWithAny(path, "/payments", "/receivables", "/receivable-adjustments", "/acceptance-bills",
                "/supplier-payments", "/payables", "/partner-accounts", "/partner-account-logs",
                "/customer-advances", "/customer-advance-refunds")) {
            if (read) return "finance.view";
            if (path.contains("/confirm") || path.contains("/cancel") || path.contains("/reverse") ||
                    path.contains("/discount") || path.contains("/mature") || path.contains("/endorse") || path.contains("/bounce"))
                return "finance.approve";
            return "finance.manage";
        }
        if (path.startsWith("/platform")) return read ? "platform.view" : "platform.manage";
        throw new ForbiddenException("该接口尚未纳入权限目录，已按安全策略拒绝访问");
    }

    private boolean startsWithAny(String path, String... prefixes) {
        return List.of(prefixes).stream().anyMatch(path::startsWith);
    }

    private boolean isReadOperation(String path, String method) {
        return HttpMethod.GET.matches(method) || HttpMethod.HEAD.matches(method)
                || (HttpMethod.POST.matches(method) && (
                    "/price-center/preview".equals(path)
                    || "/product-reference-values/resolve".equals(path)
                    || "/products/duplicate-check".equals(path)
                    || (path.startsWith("/product-series/")
                        && path.endsWith("/identity-schema/preflight"))
                ));
    }

    /**
     * Fine-grained manage permissions include read access to the same governed
     * resource. Keep the implication list explicit so unrelated *.manage
     * permissions never become a generic bypass.
     */
    private boolean hasRequiredPermission(SecurityContext.Principal principal, String permission) {
        if (principal.hasPermission(permission)) return true;
        return switch (permission) {
            case "product.view" ->
                    principal.hasPermission("product.manage");
            case "product.master.complex.view" ->
                    principal.hasPermission("product.master.complex.manage");
            case "inventory.quality.view" ->
                    principal.hasPermission("inventory.quality.manage");
            case "product.cost.view" ->
                    principal.hasPermission("product.supplier.offer.manage");
            default -> false;
        };
    }

    private void assertDocumentPath(String path) {
        Map<String, String> resources = Map.ofEntries(
                Map.entry("/purchase-orders", "PURCHASE_ORDER"), Map.entry("/inbounds", "INBOUND"),
                Map.entry("/sales-orders", "SALES_ORDER"), Map.entry("/outbounds", "OUTBOUND"),
                Map.entry("/direct-trades", "DIRECT_TRADE"),
                Map.entry("/sales-returns", "SALES_RETURN"), Map.entry("/payments", "PAYMENT"),
                Map.entry("/supplier-payments", "SUPPLIER_PAYMENT"), Map.entry("/receivable-adjustments", "RECEIVABLE_ADJUSTMENT"),
                Map.entry("/acceptance-bills", "ACCEPTANCE_BILL"), Map.entry("/customer-advance-refunds", "CUSTOMER_ADVANCE_REFUND"),
                Map.entry("/partner-fund-adjustments", "PARTNER_FUND_ADJUSTMENT"),
                Map.entry("/treasury/documents", "TREASURY_DOCUMENT"),
                Map.entry("/inventory/landed-cost-allocations", "LANDED_COST_ALLOCATION"),
                Map.entry("/tax-invoices", "TAX_INVOICE")
        );
        for (Map.Entry<String, String> entry : resources.entrySet()) {
            Matcher matcher = Pattern.compile("^" + Pattern.quote(entry.getKey()) + "/(\\d+)(?:/.*)?$").matcher(path);
            if (matcher.matches()) {
                dataScopeService.assertDocumentAllowed(entry.getValue(), Long.parseLong(matcher.group(1)));
                return;
            }
        }
        Map<String, String> inventoryResources = Map.of(
                "/inventory/transfers", "INVENTORY_TRANSFER",
                "/inventory/stocktakes", "INVENTORY_STOCKTAKE",
                "/inventory/purchase-returns", "PURCHASE_RETURN",
                "/inventory/cost-adjustments", "INVENTORY_COST_ADJUSTMENT",
                "/inventory/non-trade", "INVENTORY_NON_TRADE",
                "/inventory/landed-cost-allocations", "LANDED_COST_ALLOCATION"
        );
        for (Map.Entry<String, String> entry : inventoryResources.entrySet()) {
            Matcher matcher = Pattern.compile("^" + Pattern.quote(entry.getKey()) + "/(\\d+)(?:/.*)?$").matcher(path);
            if (matcher.matches()) {
                dataScopeService.assertDocumentAllowed(entry.getValue(), Long.parseLong(matcher.group(1)));
                return;
            }
        }
    }

    private void assertMasterPath(String path) {
        assertMasterPath(path, "/partners", "PARTNER");
        assertMasterPath(path, "/products", "PRODUCT");
        assertMasterPath(path, "/warehouses", "WAREHOUSE");
    }

    private void assertMasterPath(String path, String prefix, String dimension) {
        Matcher matcher = Pattern.compile("^" + Pattern.quote(prefix) + "/(\\d+)(?:/.*)?$").matcher(path);
        if (matcher.matches()) dataScopeService.assertMasterAllowed(dimension, Long.parseLong(matcher.group(1)));
    }

    private Long targetId(String path) {
        Matcher matcher = Pattern.compile("^/[^/]+/(\\d+)(?:/.*)?$").matcher(path);
        if (matcher.matches()) return Long.parseLong(matcher.group(1));
        matcher = Pattern.compile("^/inventory/[^/]+/(\\d+)(?:/.*)?$").matcher(path);
        return matcher.matches() ? Long.parseLong(matcher.group(1)) : 0L;
    }

    private String targetType(String path) {
        String value = path == null ? "" : path.replaceFirst("^/", "");
        int slash = value.indexOf('/');
        if (slash >= 0) value = value.substring(0, slash);
        value = value.replace('-', '_').toUpperCase();
        return value.isBlank() ? "API" : value.substring(0, Math.min(value.length(), 32));
    }

    private String sessionToken(HttpServletRequest request) {
        if (request.getCookies() == null) return "";
        for (Cookie cookie : request.getCookies()) if (AuthService.SESSION_COOKIE.equals(cookie.getName())) return cookie.getValue();
        return "";
    }
}
