package com.soyia.copperv2.security;

import java.util.List;
import java.util.Set;

public final class SecurityContext {
    private static final ThreadLocal<Principal> CURRENT = new ThreadLocal<>();

    private SecurityContext() {}

    public static void set(Principal principal) { CURRENT.set(principal); }
    public static Principal get() { return CURRENT.get(); }
    public static Principal require() {
        Principal principal = CURRENT.get();
        if (principal == null) throw new UnauthorizedException("登录状态已失效，请重新登录");
        return principal;
    }
    public static void clear() { CURRENT.remove(); }

    public record Principal(
            Long sessionId, Long userId, Long employeeId, String employeeName, String employeeNo,
            Long departmentId, String departmentName, String username,
            boolean passwordChangeRequired, List<String> roleNames, Set<String> permissionCodes
    ) {
        public Principal(Long userId, Long employeeId, String employeeName, String employeeNo,
                         Long departmentId, String departmentName, String username,
                         boolean passwordChangeRequired, List<String> roleNames, Set<String> permissionCodes) {
            this(null, userId, employeeId, employeeName, employeeNo, departmentId, departmentName,
                    username, passwordChangeRequired, roleNames, permissionCodes);
        }

        public boolean hasPermission(String code) { return permissionCodes.contains(code); }
    }
}
