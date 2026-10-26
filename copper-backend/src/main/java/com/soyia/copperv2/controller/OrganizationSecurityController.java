package com.soyia.copperv2.controller;

import com.soyia.copperv2.dto.ApiResponse;
import com.soyia.copperv2.dto.OrganizationSecurityDtos.ActionReason;
import com.soyia.copperv2.dto.OrganizationSecurityDtos.ChangePasswordRequest;
import com.soyia.copperv2.dto.OrganizationSecurityDtos.CopyRoleRequest;
import com.soyia.copperv2.dto.OrganizationSecurityDtos.DepartmentRow;
import com.soyia.copperv2.dto.OrganizationSecurityDtos.DepartmentSave;
import com.soyia.copperv2.dto.OrganizationSecurityDtos.DefaultRoleAcceptance;
import com.soyia.copperv2.dto.OrganizationSecurityDtos.DeviceSessionRow;
import com.soyia.copperv2.dto.OrganizationSecurityDtos.EmployeeRow;
import com.soyia.copperv2.dto.OrganizationSecurityDtos.EmployeeSave;
import com.soyia.copperv2.dto.OrganizationSecurityDtos.LeaveRequest;
import com.soyia.copperv2.dto.OrganizationSecurityDtos.LoginRequest;
import com.soyia.copperv2.dto.OrganizationSecurityDtos.PermissionRow;
import com.soyia.copperv2.dto.OrganizationSecurityDtos.ResetPasswordRequest;
import com.soyia.copperv2.dto.OrganizationSecurityDtos.RoleRow;
import com.soyia.copperv2.dto.OrganizationSecurityDtos.RoleSave;
import com.soyia.copperv2.dto.OrganizationSecurityDtos.ScopeOption;
import com.soyia.copperv2.dto.OrganizationSecurityDtos.SecurityAuditRow;
import com.soyia.copperv2.dto.OrganizationSecurityDtos.SessionView;
import com.soyia.copperv2.dto.OrganizationSecurityDtos.UserCreate;
import com.soyia.copperv2.dto.OrganizationSecurityDtos.UserRow;
import com.soyia.copperv2.dto.OrganizationSecurityDtos.UserUpdate;
import com.soyia.copperv2.security.AuthService;
import com.soyia.copperv2.security.SecurityContext;
import com.soyia.copperv2.security.SecurityAuditService;
import com.soyia.copperv2.security.SessionCookieFactory;
import com.soyia.copperv2.service.OrganizationSecurityService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
public class OrganizationSecurityController {
    private final AuthService authService;
    private final OrganizationSecurityService organizationService;
    private final SessionCookieFactory sessionCookieFactory;
    private final SecurityAuditService auditService;

    public OrganizationSecurityController(AuthService authService, OrganizationSecurityService organizationService,
                                          SessionCookieFactory sessionCookieFactory, SecurityAuditService auditService) {
        this.authService = authService;
        this.organizationService = organizationService;
        this.sessionCookieFactory = sessionCookieFactory;
        this.auditService = auditService;
    }

    @PostMapping("/auth/login")
    public ApiResponse<SessionView> login(@Valid @RequestBody LoginRequest body, HttpServletRequest request, HttpServletResponse response) {
        AuthService.LoginResult result = authService.login(body.username(), body.password(), clientIp(request), request.getHeader("User-Agent"));
        response.addHeader(HttpHeaders.SET_COOKIE, sessionCookieFactory.create(result.token(), Duration.ofHours(authService.sessionHours())).toString());
        return ApiResponse.ok(result.session());
    }

    @GetMapping("/auth/me")
    public ApiResponse<SessionView> currentSession() { return ApiResponse.ok(authService.currentSession()); }

    @GetMapping("/auth/sessions")
    public ApiResponse<List<DeviceSessionRow>> sessions() {
        return ApiResponse.ok(authService.currentUserSessions());
    }

    @PostMapping("/auth/sessions/{id}/revoke")
    public ApiResponse<Map<String, Boolean>> revokeSession(@PathVariable Long id, HttpServletResponse response) {
        boolean current = authService.revokeCurrentUserSession(id);
        if (current) response.addHeader(HttpHeaders.SET_COOKIE, sessionCookieFactory.create("", Duration.ZERO).toString());
        return ApiResponse.ok(Map.of("current", current));
    }

    @PostMapping("/auth/logout")
    public ApiResponse<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        authService.logout(sessionToken(request));
        response.addHeader(HttpHeaders.SET_COOKIE, sessionCookieFactory.create("", Duration.ZERO).toString());
        return ApiResponse.ok(null);
    }

    @PostMapping("/auth/change-password")
    public ApiResponse<Void> changePassword(@Valid @RequestBody ChangePasswordRequest body, HttpServletRequest request, HttpServletResponse response) {
        authService.changePassword(SecurityContext.require().userId(), body.currentPassword(), body.newPassword(), clientIp(request));
        response.addHeader(HttpHeaders.SET_COOKIE, sessionCookieFactory.create("", Duration.ZERO).toString());
        return ApiResponse.ok(null);
    }

    @GetMapping("/organization/departments")
    public ApiResponse<List<DepartmentRow>> departments() { return ApiResponse.ok(organizationService.departments()); }

    @PostMapping("/organization/departments")
    public ApiResponse<Map<String, Long>> createDepartment(@Valid @RequestBody DepartmentSave body) {
        return ApiResponse.ok(Map.of("id", organizationService.createDepartment(body)));
    }

    @PutMapping("/organization/departments/{id}")
    public ApiResponse<Map<String, Long>> updateDepartment(@PathVariable Long id, @Valid @RequestBody DepartmentSave body) {
        return ApiResponse.ok(Map.of("id", organizationService.updateDepartment(id, body)));
    }

    @PostMapping("/organization/departments/{id}/disable")
    public ApiResponse<Map<String, Long>> disableDepartment(@PathVariable Long id, @Valid @RequestBody ActionReason body) {
        return ApiResponse.ok(Map.of("id", organizationService.disableDepartment(id, body.reason())));
    }

    @GetMapping("/organization/employees")
    public ApiResponse<List<EmployeeRow>> employees(
            @RequestParam(required = false) Long departmentId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String employmentStatus) {
        return ApiResponse.ok(organizationService.employees(departmentId, keyword, employmentStatus));
    }

    @PostMapping("/organization/employees")
    public ApiResponse<Map<String, Long>> createEmployee(@Valid @RequestBody EmployeeSave body) {
        return ApiResponse.ok(Map.of("id", organizationService.createEmployee(body)));
    }

    @PutMapping("/organization/employees/{id}")
    public ApiResponse<Map<String, Long>> updateEmployee(@PathVariable Long id, @Valid @RequestBody EmployeeSave body) {
        return ApiResponse.ok(Map.of("id", organizationService.updateEmployee(id, body)));
    }

    @PostMapping("/organization/employees/{id}/leave")
    public ApiResponse<Map<String, Long>> leaveEmployee(@PathVariable Long id, @Valid @RequestBody LeaveRequest body) {
        return ApiResponse.ok(Map.of("id", organizationService.leaveEmployee(id, body.leaveDate(), body.reason())));
    }

    @PostMapping("/organization/employees/{id}/reinstate")
    public ApiResponse<Map<String, Long>> reinstateEmployee(@PathVariable Long id) {
        return ApiResponse.ok(Map.of("id", organizationService.reinstateEmployee(id)));
    }

    @GetMapping("/system/users")
    public ApiResponse<List<UserRow>> users() { return ApiResponse.ok(organizationService.users()); }

    @PostMapping("/system/users")
    public ApiResponse<Map<String, Long>> createUser(@Valid @RequestBody UserCreate body) {
        return ApiResponse.ok(Map.of("id", organizationService.createUser(body)));
    }

    @PutMapping("/system/users/{id}")
    public ApiResponse<Map<String, Long>> updateUser(@PathVariable Long id, @Valid @RequestBody UserUpdate body) {
        return ApiResponse.ok(Map.of("id", organizationService.updateUser(id, body)));
    }

    @PostMapping("/system/users/{id}/reset-password")
    public ApiResponse<Map<String, Long>> resetPassword(@PathVariable Long id, @Valid @RequestBody ResetPasswordRequest body) {
        return ApiResponse.ok(Map.of("id", organizationService.resetPassword(id, body.initialPassword())));
    }

    @PostMapping("/system/users/{id}/disable")
    public ApiResponse<Map<String, Long>> disableUser(@PathVariable Long id, @Valid @RequestBody ActionReason body) {
        return ApiResponse.ok(Map.of("id", organizationService.setUserEnabled(id, false, body.reason())));
    }

    @PostMapping("/system/users/{id}/enable")
    public ApiResponse<Map<String, Long>> enableUser(@PathVariable Long id) {
        return ApiResponse.ok(Map.of("id", organizationService.setUserEnabled(id, true, "")));
    }

    @PostMapping("/system/users/{id}/sessions/revoke")
    public ApiResponse<Map<String, Long>> revokeUserSessions(@PathVariable Long id, @Valid @RequestBody ActionReason body) {
        return ApiResponse.ok(Map.of("id", organizationService.revokeUserSessions(id, body.reason())));
    }

    @GetMapping("/system/security-audit")
    public ApiResponse<List<SecurityAuditRow>> securityAudit(
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String result,
            @RequestParam(required = false) Long operatorUserId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(required = false) Integer limit) {
        return ApiResponse.ok(auditService.query(action, result, operatorUserId, from, to, limit));
    }

    @GetMapping("/system/permissions")
    public ApiResponse<List<PermissionRow>> permissions() { return ApiResponse.ok(organizationService.permissions()); }

    @GetMapping("/system/data-scope-options")
    public ApiResponse<List<ScopeOption>> dataScopeOptions(@RequestParam String dimension) {
        return ApiResponse.ok(organizationService.dataScopeOptions(dimension));
    }

    @GetMapping("/system/roles")
    public ApiResponse<List<RoleRow>> roles() { return ApiResponse.ok(organizationService.roles()); }

    @GetMapping("/system/roles/default-acceptance")
    public ApiResponse<DefaultRoleAcceptance> defaultRoleAcceptance() {
        return ApiResponse.ok(organizationService.defaultRoleAcceptance());
    }

    @PostMapping("/system/roles")
    public ApiResponse<Map<String, Long>> createRole(@Valid @RequestBody RoleSave body) {
        return ApiResponse.ok(Map.of("id", organizationService.createRole(body)));
    }

    @PutMapping("/system/roles/{id}")
    public ApiResponse<Map<String, Long>> updateRole(@PathVariable Long id, @Valid @RequestBody RoleSave body) {
        return ApiResponse.ok(Map.of("id", organizationService.updateRole(id, body)));
    }

    @PostMapping("/system/roles/{id}/copy")
    public ApiResponse<Map<String, Long>> copyRole(@PathVariable Long id, @Valid @RequestBody CopyRoleRequest body) {
        return ApiResponse.ok(Map.of("id", organizationService.copyRole(id, body.name())));
    }

    @PostMapping("/system/roles/{id}/disable")
    public ApiResponse<Map<String, Long>> disableRole(@PathVariable Long id, @Valid @RequestBody ActionReason body) {
        return ApiResponse.ok(Map.of("id", organizationService.disableRole(id, body.reason())));
    }

    private String sessionToken(HttpServletRequest request) {
        if (request.getCookies() == null) return "";
        for (Cookie cookie : request.getCookies()) if (AuthService.SESSION_COOKIE.equals(cookie.getName())) return cookie.getValue();
        return "";
    }

    private String clientIp(HttpServletRequest request) {
        return request.getRemoteAddr();
    }
}
