package com.soyia.copperv2.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.soyia.copperv2.dto.OrganizationSecurityDtos.SecurityAuditRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class SecurityAuditService {
    public static final String SUCCESS = "SUCCESS";
    public static final String DENIED = "DENIED";
    public static final String FAILED = "FAILED";
    private static final int MAX_JSON_CHARS = 32_000;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public SecurityAuditService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public void success(String action, String targetType, Long targetId, String targetBusinessKey, String summary) {
        recordCurrent(action, SUCCESS, "", targetType, targetId, targetBusinessKey, summary, null, null);
    }

    public void successChange(String action, String targetType, Long targetId, String targetBusinessKey,
                              String summary, Object before, Object after) {
        recordCurrent(action, SUCCESS, "", targetType, targetId, targetBusinessKey, summary, before, after);
    }

    public void denied(String action, String failureCode, String targetType, Long targetId, String summary) {
        recordCurrent(action, DENIED, failureCode, targetType, targetId, "", summary, null, null);
    }

    public void failed(String action, String failureCode, String targetType, Long targetId, String summary) {
        recordCurrent(action, FAILED, failureCode, targetType, targetId, "", summary, null, null);
    }

    public void recordForActor(Long operatorId, String operatorName, Long sessionId, String ipAddress,
                               String action, String resultCode, String failureCode, String targetType,
                               Long targetId, String targetBusinessKey, String summary,
                               Object before, Object after, String subjectIdentifierHash) {
        SecurityRequestContext.Metadata request = SecurityRequestContext.get();
        String traceId = request == null ? randomId() : request.traceId();
        String method = request == null ? "" : request.requestMethod();
        String path = request == null ? "" : request.requestPath();
        String userAgentHash = request == null ? "" : request.userAgentHash();
        String requestIp = StringUtils.hasText(ipAddress) ? ipAddress : request == null ? "" : request.ipAddress();
        jdbc.update("""
                INSERT INTO security_audit_log
                (event_id, operator_user_id, operator_name, session_id, action_code, result_code, failure_code,
                 target_type, target_id, target_business_key, summary, before_json, after_json, ip_address,
                 trace_id, request_method, request_path, user_agent_hash, subject_identifier_hash, occurred_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP(6))
                """, randomId(), number(operatorId), safe(operatorName, 64), sessionId,
                safe(action, 64).toUpperCase(Locale.ROOT), normalizeResult(resultCode), safe(failureCode, 64),
                safe(targetType, 32).toUpperCase(Locale.ROOT), number(targetId), safe(targetBusinessKey, 128),
                safe(summary, 500), safeJson(before), safeJson(after), safe(requestIp, 64), traceId,
                safe(method, 10), safe(path, 255), safe(userAgentHash, 64), safe(subjectIdentifierHash, 64));
    }

    public List<SecurityAuditRow> query(String action, String result, Long operatorUserId,
                                        LocalDateTime from, LocalDateTime to, Integer requestedLimit) {
        StringBuilder sql = new StringBuilder("""
                SELECT event_id, operator_user_id, operator_name, action_code, result_code, failure_code,
                       target_type, target_id, target_business_key, summary, ip_address, trace_id,
                       request_method, request_path, occurred_at
                FROM security_audit_log WHERE 1=1
                """);
        List<Object> args = new ArrayList<>();
        if (StringUtils.hasText(action)) {
            sql.append(" AND action_code=?");
            args.add(action.trim().toUpperCase(Locale.ROOT));
        }
        if (StringUtils.hasText(result)) {
            sql.append(" AND result_code=?");
            args.add(normalizeResult(result));
        }
        if (operatorUserId != null) {
            sql.append(" AND operator_user_id=?");
            args.add(operatorUserId);
        }
        if (from != null) {
            sql.append(" AND occurred_at>=?");
            args.add(Timestamp.valueOf(from));
        }
        if (to != null) {
            sql.append(" AND occurred_at<?");
            args.add(Timestamp.valueOf(to));
        }
        int limit = Math.max(1, Math.min(requestedLimit == null ? 100 : requestedLimit, 200));
        sql.append(" ORDER BY occurred_at DESC, id DESC LIMIT ?");
        args.add(limit);
        return jdbc.query(sql.toString(), (rs, rowNum) -> new SecurityAuditRow(
                rs.getString("event_id"), rs.getLong("operator_user_id"), rs.getString("operator_name"),
                rs.getString("action_code"), rs.getString("result_code"), rs.getString("failure_code"),
                rs.getString("target_type"), rs.getLong("target_id"), rs.getString("target_business_key"),
                rs.getString("summary"), maskIp(rs.getString("ip_address")), rs.getString("trace_id"),
                rs.getString("request_method"), rs.getString("request_path"),
                rs.getObject("occurred_at", LocalDateTime.class)
        ), args.toArray());
    }

    public static String hashIdentifier(String value) {
        return SecurityRequestContext.sha256(value == null ? "" : value.trim().toLowerCase(Locale.ROOT));
    }

    private void recordCurrent(String action, String resultCode, String failureCode, String targetType,
                               Long targetId, String targetBusinessKey, String summary, Object before, Object after) {
        SecurityContext.Principal principal = SecurityContext.get();
        recordForActor(principal == null ? 0L : principal.userId(), principal == null ? "系统" : principal.employeeName(),
                principal == null ? null : principal.sessionId(), "", action, resultCode, failureCode,
                targetType, targetId, targetBusinessKey, summary, before, after, "");
    }

    private String safeJson(Object value) {
        if (value == null) return null;
        try {
            JsonNode node = objectMapper.valueToTree(value);
            redact(node);
            String json = objectMapper.writeValueAsString(node);
            if (json.length() <= MAX_JSON_CHARS) return json;
            return objectMapper.writeValueAsString(Map.of("truncated", true, "originalCharacters", json.length()));
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException ex) {
            return objectMapper.createObjectNode().put("serializationFailed", true).toString();
        }
    }

    private void redact(JsonNode node) {
        if (node instanceof ObjectNode object) {
            Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String key = field.getKey().toLowerCase(Locale.ROOT);
                if (key.contains("password") || key.contains("token") || key.contains("secret")
                        || key.contains("cookie") || key.contains("credential")) {
                    object.put(field.getKey(), "[REDACTED]");
                } else {
                    redact(field.getValue());
                }
            }
        } else if (node.isArray()) {
            node.forEach(this::redact);
        }
    }

    private String normalizeResult(String result) {
        String value = safe(result, 16).toUpperCase(Locale.ROOT);
        return switch (value) {
            case DENIED -> DENIED;
            case FAILED -> FAILED;
            default -> SUCCESS;
        };
    }

    private String maskIp(String ip) {
        if (!StringUtils.hasText(ip)) return "";
        if (ip.contains(".")) {
            int last = ip.lastIndexOf('.');
            return last > 0 ? ip.substring(0, last + 1) + "*" : "*";
        }
        int cut = ip.indexOf(':', Math.max(0, ip.indexOf(':') + 1));
        return cut > 0 ? ip.substring(0, cut) + ":*" : "*";
    }

    private long number(Long value) { return value == null ? 0L : value; }
    private String randomId() { return UUID.randomUUID().toString().replace("-", ""); }
    private String safe(String value, int max) {
        String text = value == null ? "" : value.trim();
        return text.length() <= max ? text : text.substring(0, max);
    }
}
