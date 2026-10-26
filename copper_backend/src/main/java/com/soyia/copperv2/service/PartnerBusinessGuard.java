package com.soyia.copperv2.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

/** Serializes new business with changes to the partner master and its role profiles. */
final class PartnerBusinessGuard {
    private PartnerBusinessGuard() {}

    static void lockMaster(JdbcTemplate jdbc, Long partnerId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Partner mutation requires a transaction");
        master(jdbc, partnerId, true);
    }

    static void requireEnabledRole(JdbcTemplate jdbc, Long partnerId, String role) {
        PartnerState partner = master(jdbc, partnerId, false);
        boolean customer = "CUSTOMER".equals(role);
        if (!customer && !"SUPPLIER".equals(role)) throw new IllegalArgumentException("Invalid partner role");
        String label = customer ? "客户" : "供应商";
        int mask = customer ? 1 : 2;
        if (!"ENABLED".equals(partner.status()))
            throw new BusinessException("往来单位已停用，不能新增" + label + "业务单据");
        if ((partner.typeMask() & mask) != mask)
            throw new BusinessException("往来单位未配置" + label + "身份");
        String table = customer ? "partner_customer_profile" : "partner_supplier_profile";
        List<String> statuses = jdbc.queryForList(
                "select status from " + table + " where partner_id=?" + lockingClause(), String.class, partnerId);
        if (statuses.isEmpty() || !"ENABLED".equals(statuses.get(0)))
            throw new BusinessException(label + "身份已停用，不能新增" + label + "业务单据");
    }

    private static PartnerState master(JdbcTemplate jdbc, Long partnerId, boolean exclusive) {
        List<PartnerState> rows = jdbc.query(
                "select status,type_mask from partner where id=?"
                        + (exclusive ? " for update" : lockingClause()),
                (rs, rowNum) -> new PartnerState(rs.getString("status"), rs.getInt("type_mask")), partnerId);
        if (rows.isEmpty()) throw new BusinessException("往来单位不存在");
        return rows.get(0);
    }

    private static String lockingClause() {
        return TransactionSynchronizationManager.isActualTransactionActive() ? " for share" : "";
    }

    private record PartnerState(String status, int typeMask) {}
}
