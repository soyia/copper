package com.soyia.copperv2.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public final class OrganizationSecurityDtos {
    private OrganizationSecurityDtos() {}

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}
    public record ChangePasswordRequest(@NotBlank String currentPassword, @NotBlank String newPassword) {}
    public record ActionReason(@NotBlank String reason) {}
    public record ResetPasswordRequest(@NotBlank String initialPassword) {}

    public record SessionView(
            Long sessionId, Long userId, Long employeeId, String employeeName, String employeeNo,
            Long departmentId, String departmentName, String username,
            boolean passwordChangeRequired, List<String> roleNames, List<String> permissionCodes
    ) {}

    public record DeviceSessionRow(
            Long id, boolean current, String clientName, String ipAddress,
            LocalDateTime createdAt, LocalDateTime lastSeenAt, LocalDateTime expiresAt
    ) {}

    public record SecurityAuditRow(
            String eventId, Long operatorUserId, String operatorName, String actionCode,
            String resultCode, String failureCode, String targetType, Long targetId,
            String targetBusinessKey, String summary, String ipAddress, String traceId,
            String requestMethod, String requestPath, LocalDateTime occurredAt
    ) {}

    public record DepartmentRow(
            Long id, Long parentId, String code, String name, Long managerEmployeeId,
            String managerName, int level, String fullPath, int sortNo, String status,
            String disabledReason, long employeeCount
    ) {}

    public record DepartmentSave(
            Long parentId, @NotBlank String code, @NotBlank String name,
            Long managerEmployeeId, Integer sortNo
    ) {}

    public record EmployeeRow(
            Long id, String employeeNo, String name, Long departmentId, String departmentName,
            String jobTitle, String mobile, String email, LocalDate hireDate, LocalDate leaveDate,
            boolean departmentManager, String employmentStatus, String leaveReason,
            Long userId, boolean loginEnabled, String username, List<String> roleNames
    ) {}

    public record EmployeeSave(
            String employeeNo, @NotBlank String name, @NotNull Long departmentId,
            String jobTitle, String mobile, String email, LocalDate hireDate,
            Boolean departmentManager
    ) {}

    public record LeaveRequest(@NotNull LocalDate leaveDate, @NotBlank String reason) {}

    public record PermissionRow(
            Long id, String code, String moduleCode, String moduleName, String permissionType,
            String name, String description, int sortNo
    ) {}

    public record DataScopeRule(String dimension, String mode, List<Long> selectedIds) {}

    public record RoleRow(
            Long id, String code, String name, String description, boolean systemPreset,
            String status, long userCount, List<String> permissionCodes, List<DataScopeRule> dataScopes
    ) {}

    public record RoleAcceptanceCheck(
            String groupCode, String checkCode, String checkName,
            String expectedValue, String actualValue, boolean passed
    ) {}

    public record RoleAcceptanceRow(
            String roleCode, String roleName, long userCount, boolean passed,
            List<RoleAcceptanceCheck> checks
    ) {}

    public record DefaultRoleAcceptance(
            LocalDateTime checkedAt, boolean passed, long failedCheckCount,
            List<RoleAcceptanceRow> roles
    ) {}

    public record RoleSave(
            @NotBlank String name, String description,
            @NotNull List<String> permissionCodes, @NotNull List<DataScopeRule> dataScopes
    ) {}

    public record CopyRoleRequest(@NotBlank String name) {}

    public record UserRow(
            Long id, Long employeeId, String employeeNo, String employeeName,
            Long departmentId, String departmentName, String username, String mobile,
            String status, boolean loginEnabled, boolean passwordChangeRequired,
            LocalDateTime lastLoginAt, List<Long> roleIds, List<String> roleNames
    ) {}

    public record UserCreate(
            @NotNull Long employeeId, @NotBlank String username, String mobile,
            @NotBlank String initialPassword, @NotEmpty List<Long> roleIds
    ) {}

    public record UserUpdate(
            @NotBlank String username, String mobile, @NotEmpty List<Long> roleIds
    ) {}

    public record ScopeOption(Long id, String name, String secondaryText) {}
}
