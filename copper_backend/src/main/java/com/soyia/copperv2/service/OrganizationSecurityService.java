package com.soyia.copperv2.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.soyia.copperv2.dto.OrganizationSecurityDtos.DataScopeRule;
import com.soyia.copperv2.dto.OrganizationSecurityDtos.DepartmentRow;
import com.soyia.copperv2.dto.OrganizationSecurityDtos.DepartmentSave;
import com.soyia.copperv2.dto.OrganizationSecurityDtos.DefaultRoleAcceptance;
import com.soyia.copperv2.dto.OrganizationSecurityDtos.EmployeeRow;
import com.soyia.copperv2.dto.OrganizationSecurityDtos.EmployeeSave;
import com.soyia.copperv2.dto.OrganizationSecurityDtos.PermissionRow;
import com.soyia.copperv2.dto.OrganizationSecurityDtos.RoleRow;
import com.soyia.copperv2.dto.OrganizationSecurityDtos.RoleAcceptanceCheck;
import com.soyia.copperv2.dto.OrganizationSecurityDtos.RoleAcceptanceRow;
import com.soyia.copperv2.dto.OrganizationSecurityDtos.RoleSave;
import com.soyia.copperv2.dto.OrganizationSecurityDtos.ScopeOption;
import com.soyia.copperv2.dto.OrganizationSecurityDtos.UserCreate;
import com.soyia.copperv2.dto.OrganizationSecurityDtos.UserRow;
import com.soyia.copperv2.dto.OrganizationSecurityDtos.UserUpdate;
import com.soyia.copperv2.security.AuthService;
import com.soyia.copperv2.security.SecurityContext;
import com.soyia.copperv2.security.SecurityAuditService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class OrganizationSecurityService {
    private static final List<String> SCOPE_DIMENSIONS = List.of("DOCUMENT", "WAREHOUSE", "PARTNER", "PRODUCT", "ACCOUNT");
    private static final Set<String> SCOPE_MODES = Set.of("ALL", "DEPARTMENT_AND_CHILDREN", "DEPARTMENT", "SELF", "CUSTOM");
    private static final List<RoleAcceptancePolicy> DEFAULT_ROLE_ACCEPTANCE_POLICIES = List.of(
            policy("OWNER", "老板", "ALL",
                    allowed("dashboard.view", "查看经营工作台"),
                    allowed("purchase.manage", "处理采购"),
                    allowed("sales.manage", "处理销售"),
                    allowed("sales.risk.policy.manage", "维护客户销售风控策略"),
                    allowed("sales.risk.override", "授权销售风险例外"),
                    allowed("finance.manage", "处理收付款"),
                    allowed("partner.fund.adjust.approve", "确认往来余额调整"),
                    allowed("partner.fund.adjust.reverse", "冲正往来余额调整"),
                    allowed("inventory.cost.view", "查看库存成本"),
                    allowed("accounting.manage", "修改暂存会计凭证"),
                    allowed("invoice.void", "作废符合条件的发票登记"),
                    allowed("financial.bank.account.manage", "维护企业银行账户"),
                    allowed("financial.close.reopen", "规范反结账")),
            policy("FINANCE", "财务", "ALL",
                    allowed("finance.manage", "处理收付款与核销"),
                    allowed("partner.fund.adjust.approve", "确认往来余额调整"),
                    allowed("partner.fund.adjust.reverse", "冲正往来余额调整"),
                    allowed("accounting.manage", "修改暂存会计凭证"),
                    allowed("accounting.review", "审核会计凭证"),
                    allowed("accounting.post", "记账及冲销"),
                    allowed("invoice.manage", "登记和勾稽发票"),
                    allowed("invoice.void", "作废符合条件的发票登记"),
                    allowed("financial.bank.manage", "完成银行对账"),
                    allowed("financial.bank.account.manage", "维护企业银行账户"),
                    allowed("financial.close.manage", "执行期末结账"),
                    allowed("inventory.cost.view", "查看库存成本"),
                    denied("financial.close.reopen", "反结账须由老板或管理员执行")),
            policy("WAREHOUSE", "仓库", "ALL",
                    allowed("inbound.manage", "登记入库"),
                    allowed("inbound.submit", "提交入库审核"),
                    allowed("outbound.manage", "登记出库"),
                    allowed("inventory.transfer", "处理库存调拨"),
                    allowed("inventory.stocktake", "处理库存盘点"),
                    denied("inventory.cost.view", "不得查看库存成本"),
                    denied("product.cost.view", "不得查看采购价和成本价"),
                    denied("sales.gross-profit.view", "不得查看销售毛利"),
                    denied("sales.risk.override", "不得授权销售风险例外"),
                    denied("accounting.manage", "不得修改会计凭证"),
                    denied("invoice.void", "不得作废发票"),
                    denied("financial.bank.account.manage", "不得维护企业银行账户"),
                    denied("financial.close.reopen", "不得反结账")),
            policy("SALES", "销售", "SELF",
                    allowed("sales.manage", "登记销售订单"),
                    allowed("sales.confirm", "确认销售订单"),
                    allowed("crm.record.manage", "跟进客户"),
                    allowed("outbound.manage", "登记销售出库"),
                    allowed("finance.view", "查看本人业务的应收回款"),
                    denied("inventory.cost.view", "不得查看库存成本"),
                    denied("product.cost.view", "不得查看采购价和成本价"),
                    denied("sales.gross-profit.view", "默认不得查看毛利"),
                    denied("sales.risk.policy.manage", "不得维护销售风控策略"),
                    denied("sales.risk.override", "不得自行授权销售风险例外"),
                    denied("accounting.manage", "不得修改会计凭证"),
                    denied("invoice.void", "不得作废发票"),
                    denied("financial.bank.account.manage", "不得维护企业银行账户"),
                    denied("financial.close.reopen", "不得反结账")),
            policy("PURCHASE", "采购", "SELF",
                    allowed("purchase.manage", "登记采购订单"),
                    allowed("purchase.confirm", "确认采购订单"),
                    allowed("purchase.return", "处理采购退货"),
                    allowed("inbound.manage", "登记采购入库"),
                    allowed("product.cost.view", "查看采购价以询价比价"),
                    denied("inventory.cost.view", "不得查看库存计价金额"),
                    denied("sales.gross-profit.view", "不得查看销售毛利"),
                    denied("accounting.manage", "不得修改会计凭证"),
                    denied("invoice.void", "不得作废发票"),
                    denied("financial.bank.account.manage", "不得维护企业银行账户"),
                    denied("financial.close.reopen", "不得反结账"))
    );

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final AuthService authService;
    private final SecurityAuditService auditService;

    public OrganizationSecurityService(JdbcTemplate jdbc, ObjectMapper objectMapper, AuthService authService,
                                       SecurityAuditService auditService) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.authService = authService;
        this.auditService = auditService;
    }

    public List<DepartmentRow> departments() {
        List<DepartmentBase> rows = jdbc.query("""
                SELECT d.id, d.parent_id, d.code, d.name, d.manager_employee_id,
                       COALESCE(m.name,'') manager_name, d.sort_no, d.status, d.disabled_reason,
                       COUNT(e.id) employee_count
                FROM organization_department d
                LEFT JOIN organization_employee m ON m.id=d.manager_employee_id
                LEFT JOIN organization_employee e ON e.department_id=d.id AND e.employment_status='ACTIVE'
                GROUP BY d.id, d.parent_id, d.code, d.name, d.manager_employee_id, m.name,
                         d.sort_no, d.status, d.disabled_reason
                ORDER BY d.sort_no, d.id
                """, (rs, rowNum) -> new DepartmentBase(
                rs.getLong("id"), rs.getLong("parent_id"), rs.getString("code"), rs.getString("name"),
                nullableLong(rs.getObject("manager_employee_id")), rs.getString("manager_name"),
                rs.getInt("sort_no"), rs.getString("status"), rs.getString("disabled_reason"),
                rs.getLong("employee_count")
        ));
        Map<Long, DepartmentBase> byId = new LinkedHashMap<>();
        rows.forEach(item -> byId.put(item.id(), item));
        Map<Long, PathInfo> paths = new HashMap<>();
        List<DepartmentRow> result = new ArrayList<>();
        for (DepartmentBase item : rows) {
            PathInfo path = departmentPath(item.id(), byId, paths, new HashSet<>());
            result.add(new DepartmentRow(item.id(), item.parentId(), item.code(), item.name(), item.managerEmployeeId(),
                    item.managerName(), path.level(), path.fullPath(), item.sortNo(), item.status(), item.disabledReason(), item.employeeCount()));
        }
        return result;
    }

    @Transactional
    public Long createDepartment(DepartmentSave request) {
        long parentId = request.parentId() == null ? 0 : request.parentId();
        validateDepartmentParent(parentId, null);
        try {
            long id = insertAndId("""
                    INSERT INTO organization_department (parent_id, code, name, manager_employee_id, sort_no)
                    VALUES (?,?,?,?,?)
                    """, parentId, text(request.code()), text(request.name()), request.managerEmployeeId(), number(request.sortNo()));
            validateManager(id, request.managerEmployeeId());
            audit("CREATE_DEPARTMENT", "DEPARTMENT", id, "新增部门：" + text(request.name()));
            return id;
        } catch (DataIntegrityViolationException ex) {
            throw new BusinessException("部门编码已存在，或同一上级下已有同名部门");
        }
    }

    @Transactional
    public Long updateDepartment(Long id, DepartmentSave request) {
        DepartmentBase existing = requireDepartment(id);
        if (existing.parentId() == 0 && request.parentId() != null && request.parentId() != 0)
            throw new BusinessException("根部门不能移动");
        long parentId = request.parentId() == null ? existing.parentId() : request.parentId();
        validateDepartmentParent(parentId, id);
        validateManager(id, request.managerEmployeeId());
        try {
            jdbc.update("""
                    UPDATE organization_department SET parent_id=?, code=?, name=?, manager_employee_id=?, sort_no=? WHERE id=?
                    """, parentId, text(request.code()), text(request.name()), request.managerEmployeeId(), number(request.sortNo()), id);
            audit("UPDATE_DEPARTMENT", "DEPARTMENT", id, "修改部门：" + text(request.name()));
            return id;
        } catch (DataIntegrityViolationException ex) {
            throw new BusinessException("部门编码已存在，或同一上级下已有同名部门");
        }
    }

    @Transactional
    public Long disableDepartment(Long id, String reason) {
        DepartmentBase existing = requireDepartment(id);
        if (existing.parentId() == 0) throw new BusinessException("根部门不能停用");
        requireReason(reason);
        if (count("SELECT COUNT(*) FROM organization_department WHERE parent_id=? AND status='ENABLED'", id) > 0)
            throw new BusinessException("请先停用或迁移下级部门");
        if (count("SELECT COUNT(*) FROM organization_employee WHERE department_id=? AND employment_status='ACTIVE'", id) > 0)
            throw new BusinessException("部门仍有在职人员，请先调动或办理离职");
        jdbc.update("UPDATE organization_department SET status='DISABLED', disabled_reason=?, disabled_at=NOW() WHERE id=?", text(reason), id);
        audit("DISABLE_DEPARTMENT", "DEPARTMENT", id, "停用部门：" + existing.name() + "；原因：" + text(reason));
        return id;
    }

    public List<EmployeeRow> employees(Long departmentId, String keyword, String employmentStatus) {
        List<EmployeeRow> rows = jdbc.query("""
                SELECT e.id, e.employee_no, e.name, e.department_id, d.name department_name,
                       e.job_title, e.mobile, e.email, e.hire_date, e.leave_date, e.department_manager,
                       e.employment_status, e.leave_reason, u.id user_id, u.login_enabled, u.username,
                       GROUP_CONCAT(DISTINCT r.name ORDER BY r.system_preset DESC, r.id SEPARATOR ',') role_names
                FROM organization_employee e
                JOIN organization_department d ON d.id=e.department_id
                LEFT JOIN system_user u ON u.employee_id=e.id
                LEFT JOIN system_user_role ur ON ur.user_id=u.id
                LEFT JOIN security_role r ON r.id=ur.role_id AND r.status='ENABLED'
                WHERE (? IS NULL OR e.department_id=?)
                  AND (?='' OR e.employment_status=?)
                  AND (?='' OR CONCAT(e.employee_no,' ',e.name,' ',e.mobile,' ',e.job_title) LIKE CONCAT('%',?,'%'))
                GROUP BY e.id, e.employee_no, e.name, e.department_id, d.name, e.job_title, e.mobile, e.email,
                         e.hire_date, e.leave_date, e.department_manager, e.employment_status, e.leave_reason,
                         u.id, u.login_enabled, u.username
                ORDER BY CASE e.employment_status WHEN 'ACTIVE' THEN 0 ELSE 1 END, d.name, e.employee_no
                """, (rs, rowNum) -> new EmployeeRow(
                rs.getLong("id"), rs.getString("employee_no"), rs.getString("name"), rs.getLong("department_id"),
                rs.getString("department_name"), rs.getString("job_title"), rs.getString("mobile"), rs.getString("email"),
                rs.getObject("hire_date", LocalDate.class), rs.getObject("leave_date", LocalDate.class),
                rs.getBoolean("department_manager"), rs.getString("employment_status"), rs.getString("leave_reason"),
                nullableLong(rs.getObject("user_id")), rs.getBoolean("login_enabled"), empty(rs.getString("username")),
                csv(rs.getString("role_names"))
        ), departmentId, departmentId, text(employmentStatus), text(employmentStatus), text(keyword), text(keyword));
        return filterEmployeeDataScope(rows);
    }

    @Transactional
    public Long createEmployee(EmployeeSave request) {
        requireEnabledDepartment(request.departmentId());
        String employeeNo = StringUtils.hasText(request.employeeNo()) ? request.employeeNo().trim() : nextEmployeeNo();
        try {
            long id = insertAndId("""
                    INSERT INTO organization_employee (employee_no, name, department_id, job_title, mobile, email, hire_date, department_manager)
                    VALUES (?,?,?,?,?,?,?,?)
                    """, employeeNo, text(request.name()), request.departmentId(), text(request.jobTitle()), text(request.mobile()),
                    text(request.email()), request.hireDate(), Boolean.TRUE.equals(request.departmentManager()));
            synchronizeDepartmentManager(id, request.departmentId(), Boolean.TRUE.equals(request.departmentManager()));
            audit("CREATE_EMPLOYEE", "EMPLOYEE", id, "新增人员：" + text(request.name()));
            return id;
        } catch (DataIntegrityViolationException ex) {
            throw new BusinessException("人员编码已存在");
        }
    }

    @Transactional
    public Long updateEmployee(Long id, EmployeeSave request) {
        EmployeeBase existing = requireEmployee(id);
        if (!"ACTIVE".equals(existing.employmentStatus())) throw new BusinessException("离职人员不能直接修改，请先恢复在职");
        requireEnabledDepartment(request.departmentId());
        String employeeNo = StringUtils.hasText(request.employeeNo()) ? request.employeeNo().trim() : existing.employeeNo();
        try {
            jdbc.update("""
                    UPDATE organization_employee SET employee_no=?, name=?, department_id=?, job_title=?, mobile=?, email=?,
                                          hire_date=?, department_manager=? WHERE id=?
                    """, employeeNo, text(request.name()), request.departmentId(), text(request.jobTitle()), text(request.mobile()),
                    text(request.email()), request.hireDate(), Boolean.TRUE.equals(request.departmentManager()), id);
            if (!existing.departmentId().equals(request.departmentId()))
                jdbc.update("UPDATE organization_department SET manager_employee_id=NULL WHERE id=? AND manager_employee_id=?", existing.departmentId(), id);
            synchronizeDepartmentManager(id, request.departmentId(), Boolean.TRUE.equals(request.departmentManager()));
            audit("UPDATE_EMPLOYEE", "EMPLOYEE", id, "修改人员：" + text(request.name()));
            return id;
        } catch (DataIntegrityViolationException ex) {
            throw new BusinessException("人员编码已存在");
        }
    }

    @Transactional
    public Long leaveEmployee(Long id, LocalDate leaveDate, String reason) {
        EmployeeBase existing = requireEmployee(id);
        if (!"ACTIVE".equals(existing.employmentStatus())) throw new BusinessException("该人员已经离职");
        requireReason(reason);
        jdbc.update("""
                UPDATE organization_employee SET employment_status='LEFT', leave_date=?, leave_reason=?, department_manager=0 WHERE id=?
                """, leaveDate, text(reason), id);
        jdbc.update("UPDATE organization_department SET manager_employee_id=NULL WHERE manager_employee_id=?", id);
        List<Long> userIds = jdbc.queryForList("SELECT id FROM system_user WHERE employee_id=?", Long.class, id);
        for (Long userId : userIds) {
            jdbc.update("UPDATE system_user SET login_enabled=0, status='DISABLED', disabled_reason=?, disabled_at=NOW() WHERE id=?", "人员离职：" + text(reason), userId);
            authService.invalidateSessions(userId, "EMPLOYEE_LEFT");
        }
        audit("LEAVE_EMPLOYEE", "EMPLOYEE", id, "人员离职：" + existing.name() + "；原因：" + text(reason));
        return id;
    }

    @Transactional
    public Long reinstateEmployee(Long id) {
        EmployeeBase existing = requireEmployee(id);
        if ("ACTIVE".equals(existing.employmentStatus())) throw new BusinessException("该人员当前为在职状态");
        jdbc.update("UPDATE organization_employee SET employment_status='ACTIVE', leave_date=NULL, leave_reason='' WHERE id=?", id);
        audit("REINSTATE_EMPLOYEE", "EMPLOYEE", id, "恢复人员在职：" + existing.name());
        return id;
    }

    public List<UserRow> users() {
        return jdbc.query("""
                SELECT u.id, u.employee_id, e.employee_no, e.name employee_name, e.department_id,
                       d.name department_name, u.username, u.mobile, u.status, u.login_enabled,
                       u.password_change_required, u.last_login_at,
                       GROUP_CONCAT(DISTINCT r.id ORDER BY r.id SEPARATOR ',') role_ids,
                       GROUP_CONCAT(DISTINCT r.name ORDER BY r.system_preset DESC, r.id SEPARATOR ',') role_names
                FROM system_user u JOIN organization_employee e ON e.id=u.employee_id
                JOIN organization_department d ON d.id=e.department_id
                LEFT JOIN system_user_role ur ON ur.user_id=u.id
                LEFT JOIN security_role r ON r.id=ur.role_id
                GROUP BY u.id, u.employee_id, e.employee_no, e.name, e.department_id, d.name, u.username,
                         u.mobile, u.status, u.login_enabled, u.password_change_required, u.last_login_at
                ORDER BY CASE u.status WHEN 'ENABLED' THEN 0 ELSE 1 END, e.employee_no
                """, (rs, rowNum) -> new UserRow(
                rs.getLong("id"), rs.getLong("employee_id"), rs.getString("employee_no"), rs.getString("employee_name"),
                rs.getLong("department_id"), rs.getString("department_name"), rs.getString("username"), rs.getString("mobile"),
                rs.getString("status"), rs.getBoolean("login_enabled"), rs.getBoolean("password_change_required"),
                rs.getObject("last_login_at", LocalDateTime.class), csvLong(rs.getString("role_ids")), csv(rs.getString("role_names"))
        ));
    }

    @Transactional
    public Long createUser(UserCreate request) {
        EmployeeBase employee = requireEmployee(request.employeeId());
        if (!"ACTIVE".equals(employee.employmentStatus())) throw new BusinessException("离职人员不能授权登录");
        if (count("SELECT COUNT(*) FROM system_user WHERE employee_id=?", request.employeeId()) > 0)
            throw new BusinessException("该人员已经有登录账号");
        validateRoleIds(request.roleIds());
        try {
            long id = insertAndId("""
                    INSERT INTO system_user (employee_id, username, mobile, password_hash, password_change_required)
                    VALUES (?,?,?,?,1)
                    """, request.employeeId(), text(request.username()), text(request.mobile()), authService.hashPassword(request.initialPassword()));
            replaceUserRoles(id, request.roleIds());
            audit("CREATE_USER", "USER", id, "授权人员登录：" + employee.name());
            return id;
        } catch (DataIntegrityViolationException ex) {
            throw new BusinessException("登录账号已存在");
        }
    }

    @Transactional
    public Long updateUser(Long id, UserUpdate request) {
        requireUser(id);
        validateRoleIds(request.roleIds());
        protectLastAdministrator(id, request.roleIds(), true);
        Map<String, Object> before = userSecuritySnapshot(id);
        try {
            jdbc.update("UPDATE system_user SET username=?, mobile=? WHERE id=?", text(request.username()), text(request.mobile()), id);
            replaceUserRoles(id, request.roleIds());
            authService.invalidateSessions(id, "USER_SECURITY_CHANGED");
            auditService.successChange("UPDATE_USER", "USER", id, "",
                    "修改用户账号、角色或权限范围，旧会话已撤销", before, userSecuritySnapshot(id));
            return id;
        } catch (DataIntegrityViolationException ex) {
            throw new BusinessException("登录账号已存在");
        }
    }

    @Transactional
    public Long resetPassword(Long id, String password) {
        requireUser(id);
        jdbc.update("UPDATE system_user SET password_hash=?, password_change_required=1, failed_login_count=0, locked_until=NULL WHERE id=?", authService.hashPassword(password), id);
        authService.invalidateSessions(id, "PASSWORD_RESET");
        audit("RESET_PASSWORD", "USER", id, "管理员重置用户密码");
        return id;
    }

    @Transactional
    public Long setUserEnabled(Long id, boolean enabled, String reason) {
        UserBase user = requireUser(id);
        if (!enabled) {
            requireReason(reason);
            if (SecurityContext.require().userId().equals(id)) throw new BusinessException("不能停用当前登录账号");
            protectLastAdministrator(id, List.of(), false);
            jdbc.update("UPDATE system_user SET login_enabled=0, status='DISABLED', disabled_reason=?, disabled_at=NOW() WHERE id=?", text(reason), id);
            authService.invalidateSessions(id, "USER_DISABLED");
            audit("DISABLE_USER", "USER", id, "停用用户：" + user.username() + "；原因：" + text(reason));
        } else {
            EmployeeBase employee = requireEmployee(user.employeeId());
            if (!"ACTIVE".equals(employee.employmentStatus())) throw new BusinessException("离职人员不能启用登录");
            jdbc.update("UPDATE system_user SET login_enabled=1, status='ENABLED', disabled_reason='', disabled_at=NULL WHERE id=?", id);
            audit("ENABLE_USER", "USER", id, "启用用户：" + user.username());
        }
        return id;
    }

    @Transactional
    public Long revokeUserSessions(Long id, String reason) {
        UserBase user = requireUser(id);
        requireReason(reason);
        if (SecurityContext.require().userId().equals(id)) {
            throw new BusinessException("当前账号请在“登录设备”中撤销会话，避免管理操作中途失效");
        }
        authService.invalidateSessions(id, "ADMIN_REVOKED");
        audit("REVOKE_USER_SESSIONS", "USER", id,
                "管理员撤销用户全部会话：" + user.username() + "；原因：" + text(reason));
        return id;
    }

    public List<PermissionRow> permissions() {
        return jdbc.query("""
                SELECT id, code, module_code, module_name, permission_type, name, description, sort_no
                FROM security_permission WHERE status='ENABLED' ORDER BY sort_no, id
                """, (rs, rowNum) -> new PermissionRow(rs.getLong("id"), rs.getString("code"), rs.getString("module_code"),
                rs.getString("module_name"), rs.getString("permission_type"), rs.getString("name"),
                rs.getString("description"), rs.getInt("sort_no")));
    }

    public List<RoleRow> roles() {
        List<RoleBase> bases = jdbc.query("""
                SELECT r.id, r.code, r.name, r.description, r.system_preset, r.status, COUNT(ur.user_id) user_count
                FROM security_role r LEFT JOIN system_user_role ur ON ur.role_id=r.id
                GROUP BY r.id, r.code, r.name, r.description, r.system_preset, r.status
                ORDER BY r.system_preset DESC, r.id
                """, (rs, rowNum) -> new RoleBase(rs.getLong("id"), rs.getString("code"), rs.getString("name"),
                rs.getString("description"), rs.getBoolean("system_preset"), rs.getString("status"), rs.getLong("user_count")));
        List<RoleRow> result = new ArrayList<>();
        for (RoleBase base : bases) result.add(roleRow(base));
        return result;
    }

    public DefaultRoleAcceptance defaultRoleAcceptance() {
        Map<String, RoleRow> actualRoles = new LinkedHashMap<>();
        for (RoleRow role : roles()) actualRoles.put(role.code(), role);
        List<RoleAcceptanceRow> rows = new ArrayList<>();
        long failed = 0;
        for (RoleAcceptancePolicy policy : DEFAULT_ROLE_ACCEPTANCE_POLICIES) {
            RoleRow actual = actualRoles.get(policy.roleCode());
            List<RoleAcceptanceCheck> checks = new ArrayList<>();
            if (actual == null) {
                checks.add(new RoleAcceptanceCheck("ROLE", "role.exists", "预置岗位存在", "存在", "缺失", false));
            } else {
                Set<String> permissions = new HashSet<>(actual.permissionCodes());
                for (PermissionExpectation expectation : policy.permissions()) {
                    boolean granted = permissions.contains(expectation.code());
                    checks.add(new RoleAcceptanceCheck("PERMISSION", expectation.code(), expectation.name(),
                            expectation.expected() ? "允许" : "禁止", granted ? "允许" : "禁止",
                            granted == expectation.expected()));
                }
                String actualDocumentScope = actual.dataScopes().stream()
                        .filter(scope -> "DOCUMENT".equals(scope.dimension()))
                        .map(DataScopeRule::mode).findFirst().orElse("缺失");
                checks.add(new RoleAcceptanceCheck("DATA_SCOPE", "DOCUMENT", "业务单据范围",
                        policy.documentScope(), actualDocumentScope,
                        policy.documentScope().equals(actualDocumentScope)));
            }
            long roleFailed = checks.stream().filter(check -> !check.passed()).count();
            failed += roleFailed;
            rows.add(new RoleAcceptanceRow(policy.roleCode(), actual == null ? policy.roleName() : actual.name(),
                    actual == null ? 0 : actual.userCount(), roleFailed == 0, checks));
        }
        return new DefaultRoleAcceptance(LocalDateTime.now(), failed == 0, failed, rows);
    }

    private static RoleAcceptancePolicy policy(String roleCode, String roleName, String documentScope,
                                               PermissionExpectation... permissions) {
        return new RoleAcceptancePolicy(roleCode, roleName, documentScope, List.of(permissions));
    }

    private static PermissionExpectation allowed(String code, String name) {
        return new PermissionExpectation(code, name, true);
    }

    private static PermissionExpectation denied(String code, String name) {
        return new PermissionExpectation(code, name, false);
    }

    @Transactional
    public Long createRole(RoleSave request) {
        validateRoleSave(request);
        try {
            long id = insertAndId("""
                    INSERT INTO security_role (code, name, description, system_preset)
                    VALUES (?,?,?,0)
                    """, "CUSTOM_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase(), text(request.name()), text(request.description()));
            replaceRoleSecurity(id, request);
            audit("CREATE_ROLE", "ROLE", id, "新增角色：" + text(request.name()));
            return id;
        } catch (DataIntegrityViolationException ex) {
            throw new BusinessException("角色名称已存在");
        }
    }

    @Transactional
    public Long updateRole(Long id, RoleSave request) {
        RoleBase existing = requireRole(id);
        if (existing.systemPreset()) throw new BusinessException("系统预置角色不能直接修改，请复制后调整");
        validateRoleSave(request);
        Map<String, Object> before = roleSecuritySnapshot(id);
        try {
            jdbc.update("UPDATE security_role SET name=?, description=? WHERE id=?", text(request.name()), text(request.description()), id);
            replaceRoleSecurity(id, request);
            invalidateRoleSessions(id);
            auditService.successChange("UPDATE_ROLE", "ROLE", id, "",
                    "修改角色权限与数据范围：" + text(request.name()), before, roleSecuritySnapshot(id));
            return id;
        } catch (DataIntegrityViolationException ex) {
            throw new BusinessException("角色名称已存在");
        }
    }

    @Transactional
    public Long copyRole(Long id, String name) {
        RoleBase source = requireRole(id);
        requireReason(name);
        RoleSave save = new RoleSave(name.trim(), "复制自“" + source.name() + "”", rolePermissionCodes(id), roleDataScopes(id));
        return createRole(save);
    }

    @Transactional
    public Long disableRole(Long id, String reason) {
        RoleBase existing = requireRole(id);
        if (existing.systemPreset()) throw new BusinessException("系统预置角色不能停用");
        requireReason(reason);
        if (existing.userCount() > 0) throw new BusinessException("该角色仍分配给用户，请先调整用户角色");
        jdbc.update("UPDATE security_role SET status='DISABLED', disabled_reason=?, disabled_at=NOW() WHERE id=?", text(reason), id);
        audit("DISABLE_ROLE", "ROLE", id, "停用角色：" + existing.name() + "；原因：" + text(reason));
        return id;
    }

    public List<ScopeOption> dataScopeOptions(String dimension) {
        String normalized = dimension == null ? "" : dimension.trim().toUpperCase();
        return switch (normalized) {
            case "DOCUMENT" -> jdbc.query("SELECT id, name, code FROM organization_department WHERE status='ENABLED' ORDER BY sort_no,id",
                    (rs, rowNum) -> new ScopeOption(rs.getLong(1), rs.getString(2), rs.getString(3)));
            case "WAREHOUSE" -> jdbc.query("SELECT id, name, location FROM warehouse WHERE status='ENABLED' ORDER BY id",
                    (rs, rowNum) -> new ScopeOption(rs.getLong(1), rs.getString(2), rs.getString(3)));
            case "PARTNER" -> jdbc.query("SELECT id, name, business_no FROM partner WHERE status='ENABLED' ORDER BY top_fixed DESC,id",
                    (rs, rowNum) -> new ScopeOption(rs.getLong(1), rs.getString(2), rs.getString(3)));
            case "PRODUCT" -> jdbc.query("SELECT id, name, sku FROM product WHERE status='ENABLED' ORDER BY id",
                    (rs, rowNum) -> new ScopeOption(rs.getLong(1), rs.getString(2), rs.getString(3)));
            case "ACCOUNT" -> jdbc.query("""
                    SELECT a.id, CONCAT(p.name, ' · ', CASE a.account_type WHEN 'CUSTOMER_ADVANCE' THEN '预收账户' WHEN 'SUPPLIER_PREPAY' THEN '预付账户' ELSE '往来账户' END),
                           CONCAT('余额 ', a.balance)
                    FROM partner_account a JOIN partner p ON p.id=a.partner_id ORDER BY p.name,a.id
                    """, (rs, rowNum) -> new ScopeOption(rs.getLong(1), rs.getString(2), rs.getString(3)));
            default -> throw new BusinessException("不支持的数据范围维度");
        };
    }

    private void validateDepartmentParent(long parentId, Long currentId) {
        if (parentId == 0) return;
        requireEnabledDepartment(parentId);
        if (currentId != null && parentId == currentId) throw new BusinessException("上级部门不能选择自身");
        int depth = 1;
        long cursor = parentId;
        Set<Long> visited = new HashSet<>();
        while (cursor != 0) {
            if (!visited.add(cursor) || currentId != null && cursor == currentId) throw new BusinessException("不能把部门移动到自己的下级");
            DepartmentBase parent = requireDepartment(cursor);
            cursor = parent.parentId();
            depth++;
            if (depth > 10) throw new BusinessException("部门最多支持 10 级");
        }
    }

    private void validateManager(Long departmentId, Long managerEmployeeId) {
        if (managerEmployeeId == null) return;
        EmployeeBase employee = requireEmployee(managerEmployeeId);
        if (!employee.departmentId().equals(departmentId)) throw new BusinessException("部门负责人必须属于当前部门");
        if (!"ACTIVE".equals(employee.employmentStatus())) throw new BusinessException("离职人员不能设为部门负责人");
    }

    private void synchronizeDepartmentManager(Long employeeId, Long departmentId, boolean manager) {
        if (manager) {
            jdbc.update("UPDATE organization_employee SET department_manager=0 WHERE department_id=? AND id<>?", departmentId, employeeId);
            jdbc.update("UPDATE organization_department SET manager_employee_id=? WHERE id=?", employeeId, departmentId);
        } else {
            jdbc.update("UPDATE organization_department SET manager_employee_id=NULL WHERE id=? AND manager_employee_id=?", departmentId, employeeId);
        }
    }

    private String nextEmployeeNo() {
        Long next = jdbc.queryForObject("SELECT next_value FROM organization_employee_number_sequence WHERE id=1 FOR UPDATE", Long.class);
        if (next == null) throw new IllegalStateException("人员编码序列缺失");
        jdbc.update("UPDATE organization_employee_number_sequence SET next_value=? WHERE id=1", next + 1);
        return String.format("%06d", next);
    }

    private void validateRoleIds(List<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) throw new BusinessException("至少选择一个角色");
        long count = count("SELECT COUNT(*) FROM security_role WHERE status='ENABLED' AND id IN (" + placeholders(roleIds.size()) + ")", roleIds.toArray());
        if (count != new HashSet<>(roleIds).size()) throw new BusinessException("包含不存在或已停用的角色");
    }

    private void replaceUserRoles(Long userId, List<Long> roleIds) {
        jdbc.update("DELETE FROM system_user_role WHERE user_id=?", userId);
        Long operatorId = SecurityContext.require().userId();
        for (Long roleId : new LinkedHashSet<>(roleIds)) jdbc.update("INSERT INTO system_user_role (user_id, role_id, assigned_by) VALUES (?,?,?)", userId, roleId, operatorId);
    }

    private void protectLastAdministrator(Long userId, List<Long> prospectiveRoleIds, boolean roleChange) {
        Long adminRoleId = jdbc.queryForObject("SELECT id FROM security_role WHERE code='ADMIN'", Long.class);
        boolean currentlyAdmin = count("SELECT COUNT(*) FROM system_user_role WHERE user_id=? AND role_id=?", userId, adminRoleId) > 0;
        boolean remainsAdmin = roleChange && prospectiveRoleIds.contains(adminRoleId);
        if (currentlyAdmin && !remainsAdmin) {
            long others = count("""
                    SELECT COUNT(DISTINCT u.id) FROM system_user u
                    JOIN system_user_role ur ON ur.user_id=u.id
                    WHERE ur.role_id=? AND u.id<>? AND u.status='ENABLED' AND u.login_enabled=1
                    """, adminRoleId, userId);
            if (others == 0) throw new BusinessException("系统必须至少保留一个可登录的系统管理员");
        }
    }

    private void validateRoleSave(RoleSave request) {
        if (!StringUtils.hasText(request.name())) throw new BusinessException("请填写角色名称");
        Set<String> codes = new LinkedHashSet<>(request.permissionCodes() == null ? List.of() : request.permissionCodes());
        if (!codes.isEmpty()) {
            long count = count("SELECT COUNT(*) FROM security_permission WHERE status='ENABLED' AND code IN (" + placeholders(codes.size()) + ")", codes.toArray());
            if (count != codes.size()) throw new BusinessException("包含不存在或已停用的权限点");
        }
        Map<String, DataScopeRule> scopeMap = new HashMap<>();
        for (DataScopeRule scope : request.dataScopes()) {
            String dimension = text(scope.dimension()).toUpperCase();
            String mode = text(scope.mode()).toUpperCase();
            if (!SCOPE_DIMENSIONS.contains(dimension) || !SCOPE_MODES.contains(mode)) throw new BusinessException("数据范围设置不正确");
            if ("CUSTOM".equals(mode) && (scope.selectedIds() == null || scope.selectedIds().isEmpty())) throw new BusinessException("指定数据范围至少选择一项");
            scopeMap.put(dimension, new DataScopeRule(dimension, mode, scope.selectedIds() == null ? List.of() : scope.selectedIds()));
        }
        for (String dimension : SCOPE_DIMENSIONS) if (!scopeMap.containsKey(dimension)) throw new BusinessException("缺少“" + dimension + "”数据范围");
    }

    private void replaceRoleSecurity(Long roleId, RoleSave request) {
        jdbc.update("DELETE FROM security_role_permission WHERE role_id=?", roleId);
        for (String code : new LinkedHashSet<>(request.permissionCodes()))
            jdbc.update("INSERT INTO security_role_permission (role_id, permission_id) SELECT ?, id FROM security_permission WHERE code=? AND status='ENABLED'", roleId, code);
        jdbc.update("DELETE FROM security_role_data_scope WHERE role_id=?", roleId);
        for (DataScopeRule scope : request.dataScopes())
            jdbc.update("INSERT INTO security_role_data_scope (role_id, dimension, scope_mode, selected_ids_json) VALUES (?,?,?,CAST(? AS JSON))",
                    roleId, scope.dimension().toUpperCase(), scope.mode().toUpperCase(), toJson(scope.selectedIds()));
    }

    private RoleRow roleRow(RoleBase base) {
        return new RoleRow(base.id(), base.code(), base.name(), base.description(), base.systemPreset(), base.status(),
                base.userCount(), rolePermissionCodes(base.id()), roleDataScopes(base.id()));
    }

    private List<String> rolePermissionCodes(Long roleId) {
        return jdbc.queryForList("""
                SELECT p.code FROM security_role_permission rp JOIN security_permission p ON p.id=rp.permission_id
                WHERE rp.role_id=? ORDER BY p.sort_no,p.id
                """, String.class, roleId);
    }

    private List<DataScopeRule> roleDataScopes(Long roleId) {
        Map<String, DataScopeRule> rows = new LinkedHashMap<>();
        jdbc.query("SELECT dimension, scope_mode, selected_ids_json FROM security_role_data_scope WHERE role_id=?",
                (RowCallbackHandler) rs -> rows.put(rs.getString(1), new DataScopeRule(rs.getString(1), rs.getString(2), fromJson(rs.getString(3)))), roleId);
        List<DataScopeRule> result = new ArrayList<>();
        for (String dimension : SCOPE_DIMENSIONS) result.add(rows.getOrDefault(dimension, new DataScopeRule(dimension, "ALL", List.of())));
        return result;
    }

    private List<EmployeeRow> filterEmployeeDataScope(List<EmployeeRow> rows) {
        SecurityContext.Principal principal = SecurityContext.get();
        if (principal == null || principal.hasPermission("role.manage") || principal.hasPermission("user.manage")) return rows;
        List<DataScopeRule> scopes = jdbc.query("""
                SELECT DISTINCT s.dimension, s.scope_mode, s.selected_ids_json
                FROM system_user_role ur JOIN security_role r ON r.id=ur.role_id AND r.status='ENABLED'
                JOIN security_role_data_scope s ON s.role_id=r.id AND s.dimension='DOCUMENT'
                WHERE ur.user_id=?
                """, (rs, rowNum) -> new DataScopeRule(rs.getString(1), rs.getString(2), fromJson(rs.getString(3))), principal.userId());
        if (scopes.stream().anyMatch(scope -> "ALL".equals(scope.mode()))) return rows;
        Set<Long> departmentIds = new HashSet<>();
        boolean self = false;
        for (DataScopeRule scope : scopes) {
            switch (scope.mode()) {
                case "DEPARTMENT" -> departmentIds.add(principal.departmentId());
                case "DEPARTMENT_AND_CHILDREN" -> departmentIds.addAll(departmentAndDescendants(principal.departmentId()));
                case "CUSTOM" -> departmentIds.addAll(scope.selectedIds());
                case "SELF" -> self = true;
                default -> { }
            }
        }
        boolean includeSelf = self;
        return rows.stream().filter(row -> departmentIds.contains(row.departmentId()) || includeSelf && row.id().equals(principal.employeeId())).toList();
    }

    private Set<Long> departmentAndDescendants(Long departmentId) {
        Set<Long> result = new HashSet<>();
        result.add(departmentId);
        boolean changed = true;
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id,parent_id FROM organization_department");
        while (changed) {
            changed = false;
            for (Map<String, Object> row : rows) {
                long id = ((Number) row.get("id")).longValue();
                long parent = ((Number) row.get("parent_id")).longValue();
                if (result.contains(parent) && result.add(id)) changed = true;
            }
        }
        return result;
    }

    private void invalidateRoleSessions(Long roleId) {
        List<Long> userIds = jdbc.queryForList("SELECT user_id FROM system_user_role WHERE role_id=?", Long.class, roleId);
        userIds.forEach(userId -> authService.invalidateSessions(userId, "ROLE_SECURITY_CHANGED"));
    }

    private Map<String, Object> userSecuritySnapshot(Long userId) {
        UserBase user = requireUser(userId);
        return Map.of(
                "identifierHash", SecurityAuditService.hashIdentifier(user.username()),
                "status", user.status(),
                "loginEnabled", user.loginEnabled(),
                "roleIds", jdbc.queryForList("SELECT role_id FROM system_user_role WHERE user_id=? ORDER BY role_id", Long.class, userId)
        );
    }

    private Map<String, Object> roleSecuritySnapshot(Long roleId) {
        RoleBase role = requireRole(roleId);
        return Map.of(
                "name", role.name(),
                "status", role.status(),
                "permissionCodes", rolePermissionCodes(roleId),
                "dataScopes", roleDataScopes(roleId)
        );
    }

    private DepartmentBase requireDepartment(Long id) {
        DepartmentBase row = jdbc.query("""
                SELECT d.id,d.parent_id,d.code,d.name,d.manager_employee_id,COALESCE(e.name,''),d.sort_no,d.status,d.disabled_reason,0
                FROM organization_department d LEFT JOIN organization_employee e ON e.id=d.manager_employee_id WHERE d.id=?
                """, rs -> rs.next() ? new DepartmentBase(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                nullableLong(rs.getObject(5)), rs.getString(6), rs.getInt(7), rs.getString(8), rs.getString(9), 0) : null, id);
        if (row == null) throw new BusinessException("部门不存在");
        return row;
    }

    private void requireEnabledDepartment(Long id) {
        DepartmentBase row = requireDepartment(id);
        if (!"ENABLED".equals(row.status())) throw new BusinessException("部门已停用，不能继续使用");
    }

    private EmployeeBase requireEmployee(Long id) {
        EmployeeBase row = jdbc.query("SELECT id,employee_no,name,department_id,employment_status FROM organization_employee WHERE id=?",
                rs -> rs.next() ? new EmployeeBase(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getString(5)) : null, id);
        if (row == null) throw new BusinessException("人员不存在");
        return row;
    }

    private UserBase requireUser(Long id) {
        UserBase row = jdbc.query("SELECT id,employee_id,username,status,login_enabled FROM system_user WHERE id=?",
                rs -> rs.next() ? new UserBase(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getBoolean(5)) : null, id);
        if (row == null) throw new BusinessException("用户不存在");
        return row;
    }

    private RoleBase requireRole(Long id) {
        RoleBase row = jdbc.query("""
                SELECT r.id,r.code,r.name,r.description,r.system_preset,r.status,COUNT(ur.user_id)
                FROM security_role r LEFT JOIN system_user_role ur ON ur.role_id=r.id WHERE r.id=?
                GROUP BY r.id,r.code,r.name,r.description,r.system_preset,r.status
                """, rs -> rs.next() ? new RoleBase(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getBoolean(5), rs.getString(6), rs.getLong(7)) : null, id);
        if (row == null) throw new BusinessException("角色不存在");
        return row;
    }

    private PathInfo departmentPath(Long id, Map<Long, DepartmentBase> byId, Map<Long, PathInfo> cache, Set<Long> visiting) {
        if (cache.containsKey(id)) return cache.get(id);
        DepartmentBase item = byId.get(id);
        if (item == null || !visiting.add(id)) return new PathInfo(1, item == null ? "" : item.name());
        PathInfo result;
        if (item.parentId() == 0 || !byId.containsKey(item.parentId())) result = new PathInfo(1, item.name());
        else {
            PathInfo parent = departmentPath(item.parentId(), byId, cache, visiting);
            result = new PathInfo(parent.level() + 1, parent.fullPath() + " / " + item.name());
        }
        visiting.remove(id);
        cache.put(id, result);
        return result;
    }

    private long insertAndId(String sql, Object... args) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) throw new IllegalStateException("未返回新增主键");
        return key.longValue();
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    private void audit(String action, String targetType, Long targetId, String summary) {
        auditService.success(action, targetType, targetId, "", summary);
    }

    private String toJson(List<Long> ids) {
        try { return objectMapper.writeValueAsString(ids == null ? List.of() : ids); }
        catch (Exception ex) { throw new BusinessException("数据范围保存失败"); }
    }

    private List<Long> fromJson(String json) {
        if (!StringUtils.hasText(json)) return List.of();
        try { return objectMapper.readValue(json, new TypeReference<>() {}); }
        catch (Exception ex) { return List.of(); }
    }

    private void requireReason(String value) { if (!StringUtils.hasText(value)) throw new BusinessException("必须填写原因"); }
    private String text(String value) { return value == null ? "" : value.trim(); }
    private String empty(String value) { return value == null ? "" : value; }
    private int number(Integer value) { return value == null ? 0 : value; }
    private Long nullableLong(Object value) { return value == null ? null : ((Number) value).longValue(); }
    private String placeholders(int size) { return String.join(",", java.util.Collections.nCopies(size, "?")); }
    private List<String> csv(String value) { return !StringUtils.hasText(value) ? List.of() : Arrays.stream(value.split(",")).filter(StringUtils::hasText).toList(); }
    private List<Long> csvLong(String value) { return !StringUtils.hasText(value) ? List.of() : Arrays.stream(value.split(",")).filter(StringUtils::hasText).map(Long::valueOf).toList(); }

    private record DepartmentBase(Long id, Long parentId, String code, String name, Long managerEmployeeId, String managerName, int sortNo, String status, String disabledReason, long employeeCount) {}
    private record EmployeeBase(Long id, String employeeNo, String name, Long departmentId, String employmentStatus) {}
    private record UserBase(Long id, Long employeeId, String username, String status, boolean loginEnabled) {}
    private record RoleBase(Long id, String code, String name, String description, boolean systemPreset, String status, long userCount) {}
    private record PermissionExpectation(String code, String name, boolean expected) {}
    private record RoleAcceptancePolicy(String roleCode, String roleName, String documentScope,
                                        List<PermissionExpectation> permissions) {}
    private record PathInfo(int level, String fullPath) {}
}
