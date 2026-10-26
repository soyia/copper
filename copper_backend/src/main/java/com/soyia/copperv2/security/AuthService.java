package com.soyia.copperv2.security;

import com.soyia.copperv2.dto.OrganizationSecurityDtos.DeviceSessionRow;
import com.soyia.copperv2.dto.OrganizationSecurityDtos.SessionView;
import com.soyia.copperv2.service.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class AuthService {
    public static final String SESSION_COOKIE = "COPPER_SESSION";

    private final JdbcTemplate jdbc;
    private final PasswordHasher passwordHasher;
    private final BootstrapCredentialService bootstrapCredentialService;
    private final SecurityAuditService auditService;
    private final int sessionHours;
    private final int maxActiveSessions;
    private final SecureRandom secureRandom = new SecureRandom();

    public AuthService(JdbcTemplate jdbc, PasswordHasher passwordHasher,
                       BootstrapCredentialService bootstrapCredentialService,
                       SecurityAuditService auditService,
                       @Value("${copper.security.session.hours:12}") int sessionHours,
                       @Value("${copper.security.session.max-active:5}") int maxActiveSessions) {
        this.jdbc = jdbc;
        this.passwordHasher = passwordHasher;
        this.bootstrapCredentialService = bootstrapCredentialService;
        this.auditService = auditService;
        this.sessionHours = Math.max(1, Math.min(sessionHours, 168));
        this.maxActiveSessions = Math.max(1, Math.min(maxActiveSessions, 20));
    }

    @Transactional
    public void ensureBootstrapAccount() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM system_user", Integer.class);
        if (count != null && count > 0) return;
        BootstrapCredentialService.Credential credential = bootstrapCredentialService.loadOrCreate();
        validatePassword(credential.password());
        Long employeeId = jdbc.query("SELECT id FROM organization_employee WHERE employee_no='000001' LIMIT 1",
                rs -> rs.next() ? rs.getLong(1) : null);
        if (employeeId == null) {
            employeeId = insertAndId("""
                    INSERT INTO organization_employee (employee_no, name, department_id, job_title, hire_date, department_manager)
                    VALUES ('000001','系统管理员',1,'系统管理员',CURRENT_DATE,1)
                    """);
            jdbc.update("UPDATE organization_employee_number_sequence SET next_value=GREATEST(next_value,2) WHERE id=1");
            jdbc.update("UPDATE organization_department SET manager_employee_id=? WHERE id=1 AND manager_employee_id IS NULL", employeeId);
        }
        Long userId = insertAndId("""
                INSERT INTO system_user (employee_id, username, mobile, password_hash, password_change_required)
                VALUES (?,?,?,?,1)
                """, employeeId, credential.username(), "", passwordHasher.hash(credential.password()));
        jdbc.update("INSERT IGNORE INTO system_user_role (user_id, role_id) SELECT ?, id FROM security_role WHERE code='ADMIN'", userId);
        auditService.recordForActor(0L, "系统", null, "", "BOOTSTRAP_ADMIN", SecurityAuditService.SUCCESS, "",
                "USER", userId, "", "初始化系统管理员账号；一次性凭据已写入受限文件，首次登录必须修改密码",
                null, null, SecurityAuditService.hashIdentifier(credential.username()));
    }

    @Transactional(noRollbackFor = UnauthorizedException.class)
    public LoginResult login(String username, String password, String ipAddress, String userAgent) {
        String loginName = username == null ? "" : username.trim();
        String identifierHash = SecurityAuditService.hashIdentifier(loginName);
        Map<String, Object> row = findLoginAccount(loginName);
        if (row == null) {
            auditLoginFailure(0L, "未知账号", null, ipAddress, "ACCOUNT_OR_PASSWORD", identifierHash);
            throw new UnauthorizedException("账号或密码不正确");
        }
        Long userId = (Long) row.get("id");
        String employeeName = String.valueOf(row.get("employeeName"));
        if (!"ACTIVE".equals(row.get("employmentStatus"))) {
            auditLoginFailure(userId, employeeName, null, ipAddress, "EMPLOYEE_LEFT", identifierHash);
            throw new UnauthorizedException("该人员已离职，不能登录");
        }
        if (!Boolean.TRUE.equals(row.get("loginEnabled")) || !"ENABLED".equals(row.get("status"))) {
            auditLoginFailure(userId, employeeName, null, ipAddress, "ACCOUNT_DISABLED", identifierHash);
            throw new UnauthorizedException("该账号已停用，请联系管理员");
        }
        LocalDateTime lockedUntil = (LocalDateTime) row.get("lockedUntil");
        if (lockedUntil.isAfter(LocalDateTime.now())) {
            auditLoginFailure(userId, employeeName, null, ipAddress, "ACCOUNT_LOCKED", identifierHash);
            throw new UnauthorizedException("密码连续错误次数过多，请稍后再试");
        }
        if (!passwordHasher.matches(password, String.valueOf(row.get("passwordHash")))) {
            int failed = ((Integer) row.get("failedLoginCount")) + 1;
            jdbc.update("""
                    UPDATE system_user
                    SET failed_login_count=?, locked_until=CASE WHEN ?>=5 THEN DATE_ADD(NOW(), INTERVAL 15 MINUTE) ELSE NULL END
                    WHERE id=?
                    """, failed, failed, userId);
            auditLoginFailure(userId, employeeName, null, ipAddress,
                    failed >= 5 ? "PASSWORD_FAILURE_LOCKED" : "ACCOUNT_OR_PASSWORD", identifierHash);
            throw new UnauthorizedException(failed >= 5 ? "密码连续错误次数过多，账号已锁定 15 分钟" : "账号或密码不正确");
        }

        jdbc.update("UPDATE system_user SET failed_login_count=0, locked_until=NULL, last_login_at=NOW() WHERE id=?", userId);
        cleanupSessionHistory();
        revokeSessionsBeyondLimit(userId);
        byte[] random = new byte[32];
        secureRandom.nextBytes(random);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        long sessionId = insertAndId("""
                INSERT INTO system_user_session (user_id, token_hash, expires_at, ip_address, user_agent)
                VALUES (?, ?, DATE_ADD(NOW(), INTERVAL ? HOUR), ?, ?)
                """, userId, sha256(token), sessionHours, safe(ipAddress, 64), safe(userAgent, 255));
        SecurityContext.Principal principal = principalByUserId(userId, sessionId);
        auditService.recordForActor(userId, principal.employeeName(), sessionId, ipAddress,
                "LOGIN_SUCCESS", SecurityAuditService.SUCCESS, "", "USER", userId, "", "登录成功",
                null, null, identifierHash);
        return new LoginResult(token, toView(principal));
    }

    public SecurityContext.Principal authenticateSession(String token) {
        if (token == null || token.isBlank()) throw new UnauthorizedException("请先登录");
        String tokenHash = sha256(token);
        Map<String, Long> session = jdbc.query("""
                SELECT s.id, s.user_id
                FROM system_user_session s
                JOIN system_user u ON u.id=s.user_id
                JOIN organization_employee e ON e.id=u.employee_id
                WHERE s.token_hash=? AND s.expires_at>NOW() AND s.revoked_at IS NULL
                  AND u.status='ENABLED' AND u.login_enabled=1 AND e.employment_status='ACTIVE'
                LIMIT 1
                """, rs -> {
            if (!rs.next()) return null;
            return Map.of("sessionId", rs.getLong("id"), "userId", rs.getLong("user_id"));
        }, tokenHash);
        if (session == null) throw new UnauthorizedException("登录状态已失效，请重新登录");
        jdbc.update("UPDATE system_user_session SET last_seen_at=NOW() WHERE id=? AND revoked_at IS NULL", session.get("sessionId"));
        return principalByUserId(session.get("userId"), session.get("sessionId"));
    }

    @Transactional
    public void logout(String token) {
        if (token == null || token.isBlank()) return;
        Map<String, Object> row = jdbc.query("""
                SELECT s.id, s.user_id, e.name employee_name
                FROM system_user_session s
                JOIN system_user u ON u.id=s.user_id
                JOIN organization_employee e ON e.id=u.employee_id
                WHERE s.token_hash=? LIMIT 1
                """, rs -> {
            if (!rs.next()) return null;
            return Map.of("sessionId", rs.getLong("id"), "userId", rs.getLong("user_id"), "employeeName", rs.getString("employee_name"));
        }, sha256(token));
        if (row == null) return;
        Long sessionId = ((Number) row.get("sessionId")).longValue();
        Long userId = ((Number) row.get("userId")).longValue();
        int changed = jdbc.update("""
                UPDATE system_user_session SET revoked_at=NOW(), revoke_reason='USER_LOGOUT', revoked_by_user_id=?
                WHERE id=? AND revoked_at IS NULL
                """, userId, sessionId);
        if (changed == 1) {
            auditService.recordForActor(userId, String.valueOf(row.get("employeeName")), sessionId, "",
                    "LOGOUT", SecurityAuditService.SUCCESS, "", "SESSION", sessionId, "", "用户退出登录",
                    null, null, "");
        }
    }

    @Transactional
    public void changePassword(Long userId, String currentPassword, String newPassword, String ipAddress) {
        validatePassword(newPassword);
        Map<String, Object> account = jdbc.queryForMap(
                "SELECT username,password_hash,password_change_required FROM system_user WHERE id=?", userId);
        String currentHash = String.valueOf(account.get("password_hash"));
        if (!passwordHasher.matches(currentPassword, currentHash)) throw new BusinessException("当前密码不正确");
        if (passwordHasher.matches(newPassword, currentHash)) throw new BusinessException("新密码不能与当前密码相同");
        jdbc.update("""
                UPDATE system_user
                SET password_hash=?, password_change_required=0, failed_login_count=0, locked_until=NULL, password_changed_at=NOW()
                WHERE id=?
                """, passwordHasher.hash(newPassword), userId);
        SecurityContext.Principal principal = SecurityContext.require();
        invalidateSessions(userId, "PASSWORD_CHANGED");
        boolean bootstrapCredentialDestroyed = false;
        if (((Number) account.get("password_change_required")).intValue() == 1
                && bootstrapCredentialService.isBootstrapUsername(String.valueOf(account.get("username")))) {
            bootstrapCredentialDestroyed = bootstrapCredentialService.destroyIfPresent();
        }
        auditService.recordForActor(userId, principal.employeeName(), principal.sessionId(), ipAddress,
                "CHANGE_PASSWORD", SecurityAuditService.SUCCESS, "", "USER", userId, "", "用户修改密码，全部旧会话已撤销",
                null, null, SecurityAuditService.hashIdentifier(String.valueOf(account.get("username"))));
        if (bootstrapCredentialDestroyed) {
            auditService.recordForActor(userId, principal.employeeName(), principal.sessionId(), ipAddress,
                    "BOOTSTRAP_CREDENTIAL_DESTROYED", SecurityAuditService.SUCCESS, "", "USER", userId, "",
                    "首次管理员一次性凭据已销毁，全部临时会话已失效", null, null, "");
        }
    }

    public SessionView currentSession() { return toView(SecurityContext.require()); }

    public List<DeviceSessionRow> currentUserSessions() {
        SecurityContext.Principal principal = SecurityContext.require();
        return jdbc.query("""
                SELECT id, ip_address, user_agent, created_at, last_seen_at, expires_at
                FROM system_user_session
                WHERE user_id=? AND revoked_at IS NULL AND expires_at>NOW()
                ORDER BY last_seen_at DESC, id DESC
                """, (rs, rowNum) -> new DeviceSessionRow(
                rs.getLong("id"), principal.sessionId() != null && principal.sessionId().equals(rs.getLong("id")),
                clientName(rs.getString("user_agent")), maskIp(rs.getString("ip_address")),
                rs.getObject("created_at", LocalDateTime.class), rs.getObject("last_seen_at", LocalDateTime.class),
                rs.getObject("expires_at", LocalDateTime.class)
        ), principal.userId());
    }

    @Transactional
    public boolean revokeCurrentUserSession(Long sessionId) {
        SecurityContext.Principal principal = SecurityContext.require();
        int changed = jdbc.update("""
                UPDATE system_user_session
                SET revoked_at=NOW(), revoke_reason='USER_REVOKED', revoked_by_user_id=?
                WHERE id=? AND user_id=? AND revoked_at IS NULL AND expires_at>NOW()
                """, principal.userId(), sessionId, principal.userId());
        if (changed != 1) throw new BusinessException("登录设备不存在或已失效");
        auditService.success("SESSION_REVOKED", "SESSION", sessionId, "", "用户撤销登录设备");
        return sessionId.equals(principal.sessionId());
    }

    public void invalidateSessions(Long userId) { invalidateSessions(userId, "SECURITY_CHANGED"); }

    public void invalidateSessions(Long userId, String reason) {
        SecurityContext.Principal principal = SecurityContext.get();
        long actor = principal == null ? 0L : principal.userId();
        jdbc.update("""
                UPDATE system_user_session
                SET revoked_at=NOW(), revoke_reason=?, revoked_by_user_id=?
                WHERE user_id=? AND revoked_at IS NULL
                """, safe(reason, 64), actor, userId);
    }

    public String hashPassword(String password) { validatePassword(password); return passwordHasher.hash(password); }
    public int sessionHours() { return sessionHours; }

    private Map<String, Object> findLoginAccount(String loginName) {
        return jdbc.query("""
                SELECT u.id, u.employee_id, u.username, u.password_hash, u.login_enabled, u.status,
                       u.password_change_required, u.failed_login_count, u.locked_until,
                       e.employee_no, e.name employee_name, e.department_id, e.employment_status,
                       d.name department_name
                FROM system_user u
                JOIN organization_employee e ON e.id=u.employee_id
                JOIN organization_department d ON d.id=e.department_id
                WHERE u.username=? OR (u.mobile<>'' AND u.mobile=?)
                LIMIT 1
                """, rs -> {
            if (!rs.next()) return null;
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", rs.getLong("id"));
            result.put("employeeId", rs.getLong("employee_id"));
            result.put("username", rs.getString("username"));
            result.put("passwordHash", rs.getString("password_hash"));
            result.put("loginEnabled", rs.getBoolean("login_enabled"));
            result.put("status", rs.getString("status"));
            result.put("passwordChangeRequired", rs.getBoolean("password_change_required"));
            result.put("failedLoginCount", rs.getInt("failed_login_count"));
            result.put("lockedUntil", rs.getTimestamp("locked_until") == null
                    ? LocalDateTime.MIN : rs.getTimestamp("locked_until").toLocalDateTime());
            result.put("employeeNo", rs.getString("employee_no"));
            result.put("employeeName", rs.getString("employee_name"));
            result.put("departmentId", rs.getLong("department_id"));
            result.put("employmentStatus", rs.getString("employment_status"));
            result.put("departmentName", rs.getString("department_name"));
            return result;
        }, loginName, loginName);
    }

    private SecurityContext.Principal principalByUserId(Long userId, Long sessionId) {
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT u.id, u.employee_id, u.username, u.password_change_required,
                       e.employee_no, e.name employee_name, e.department_id, d.name department_name
                FROM system_user u JOIN organization_employee e ON e.id=u.employee_id
                JOIN organization_department d ON d.id=e.department_id WHERE u.id=?
                """, userId);
        List<String> roles = jdbc.queryForList("""
                SELECT r.name FROM system_user_role ur JOIN security_role r ON r.id=ur.role_id
                WHERE ur.user_id=? AND r.status='ENABLED' ORDER BY r.system_preset DESC, r.id
                """, String.class, userId);
        Set<String> permissions = new LinkedHashSet<>(jdbc.queryForList("""
                SELECT p.code FROM system_user_role ur
                JOIN security_role r ON r.id=ur.role_id AND r.status='ENABLED'
                JOIN security_role_permission rp ON rp.role_id=r.id
                JOIN security_permission p ON p.id=rp.permission_id AND p.status='ENABLED'
                WHERE ur.user_id=? GROUP BY p.code ORDER BY MIN(p.sort_no), MIN(p.id)
                """, String.class, userId));
        return new SecurityContext.Principal(
                sessionId, userId, ((Number) row.get("employee_id")).longValue(),
                String.valueOf(row.get("employee_name")), String.valueOf(row.get("employee_no")),
                ((Number) row.get("department_id")).longValue(), String.valueOf(row.get("department_name")),
                String.valueOf(row.get("username")), ((Number) row.get("password_change_required")).intValue() == 1,
                roles, permissions
        );
    }

    private SessionView toView(SecurityContext.Principal principal) {
        return new SessionView(principal.sessionId(), principal.userId(), principal.employeeId(), principal.employeeName(),
                principal.employeeNo(), principal.departmentId(), principal.departmentName(), principal.username(),
                principal.passwordChangeRequired(), principal.roleNames(), List.copyOf(principal.permissionCodes()));
    }

    private void revokeSessionsBeyondLimit(Long userId) {
        List<Long> active = jdbc.queryForList("""
                SELECT id FROM system_user_session
                WHERE user_id=? AND revoked_at IS NULL AND expires_at>NOW()
                ORDER BY last_seen_at ASC, id ASC
                """, Long.class, userId);
        int revokeCount = Math.max(0, active.size() - maxActiveSessions + 1);
        for (int i = 0; i < revokeCount; i++) {
            jdbc.update("""
                    UPDATE system_user_session
                    SET revoked_at=NOW(), revoke_reason='CONCURRENT_LIMIT', revoked_by_user_id=?
                    WHERE id=? AND revoked_at IS NULL
                    """, userId, active.get(i));
        }
    }

    private void cleanupSessionHistory() {
        jdbc.update("""
                DELETE FROM system_user_session
                WHERE (revoked_at IS NOT NULL AND revoked_at<DATE_SUB(NOW(), INTERVAL 90 DAY))
                   OR (revoked_at IS NULL AND expires_at<DATE_SUB(NOW(), INTERVAL 90 DAY))
                """);
    }

    private void auditLoginFailure(Long userId, String employeeName, Long sessionId, String ip,
                                   String failureCode, String identifierHash) {
        auditService.recordForActor(userId, employeeName, sessionId, ip, "LOGIN_FAILED",
                SecurityAuditService.DENIED, failureCode, "USER", userId, "", "登录失败",
                null, null, identifierHash);
    }

    private String clientName(String userAgent) {
        String value = userAgent == null ? "" : userAgent.toLowerCase(Locale.ROOT);
        String browser = value.contains("edg/") ? "Edge" : value.contains("chrome/") ? "Chrome"
                : value.contains("safari/") ? "Safari" : value.contains("firefox/") ? "Firefox" : "其他客户端";
        String system = value.contains("windows") ? "Windows" : value.contains("mac os") ? "macOS"
                : value.contains("android") ? "Android" : value.contains("iphone") || value.contains("ipad") ? "iOS"
                : value.contains("linux") ? "Linux" : "未知系统";
        return browser + " · " + system;
    }

    private String maskIp(String ip) {
        if (ip == null || ip.isBlank()) return "";
        if (ip.contains(".")) {
            int last = ip.lastIndexOf('.');
            return last > 0 ? ip.substring(0, last + 1) + "*" : "*";
        }
        int first = ip.indexOf(':');
        int second = first < 0 ? -1 : ip.indexOf(':', first + 1);
        return second > 0 ? ip.substring(0, second) + ":*" : "*";
    }

    private void validatePassword(String password) {
        if (password == null || !password.matches("^(?=.*[A-Za-z])(?=.*\\d).{8,}$"))
            throw new BusinessException("密码至少 8 位，且必须同时包含字母和数字");
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
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

    private String safe(String value, int max) {
        String text = value == null ? "" : value.trim();
        return text.length() <= max ? text : text.substring(0, max);
    }

    public record LoginResult(String token, SessionView session) {}
}
