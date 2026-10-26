package com.soyia.copperv2.service;

import com.soyia.copperv2.dto.AccountingDtos;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;

@Service
public class AccountingCoverageService {
    private final JdbcTemplate jdbc;

    public AccountingCoverageService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public AccountingDtos.RuleCoverageMatrix matrix(LocalDate date) {
        LocalDate asOf = date == null ? LocalDate.now() : date;
        long entityId = jdbc.queryForObject(
                "select id from accounting_entity where status='ENABLED' order by id limit 1", Long.class);
        List<ActiveRule> activeRules = activeRules(entityId, asOf);
        Map<String, List<ActiveRule>> rulesByFact = activeRules.stream().collect(Collectors.groupingBy(
                ActiveRule::key, LinkedHashMap::new, Collectors.toList()));
        List<AccountingDtos.RuleCoverageRow> rows = new ArrayList<>();
        for (AccountingCoverageCatalog.Scenario scenario : AccountingCoverageCatalog.scenarios()) {
            rows.add(toRow(scenario, rulesByFact.getOrDefault(key(scenario.sourceType(), scenario.postingAction(),
                    scenario.factType(), scenario.factDirection()), List.of())));
        }
        appendUnregisteredRules(rows, activeRules);
        appendUndeclaredBusinessFacts(rows, asOf);
        int blocking = (int) rows.stream().filter(row -> isBlocking(row.coverageStatus())).count();
        int warning = (int) rows.stream().filter(row -> "CUSTOMIZED".equals(row.coverageStatus())).count();
        int covered = (int) rows.stream().filter(row -> Set.of("COVERED", "NOT_REQUIRED")
                .contains(row.coverageStatus())).count();
        int accountingRuleCount = (int) rows.stream().filter(row -> "ACCOUNTING_RULE".equals(row.generationMode())).count();
        int noVoucherCount = (int) rows.stream().filter(row -> "NO_VOUCHER".equals(row.generationMode())).count();
        AccountingDtos.RuleCoverageSummary summary = new AccountingDtos.RuleCoverageSummary(
                asOf, rows.size(), covered, warning, blocking, accountingRuleCount, noVoucherCount);
        return new AccountingDtos.RuleCoverageMatrix(summary, List.copyOf(rows));
    }

    /**
     * Runtime gate used before automatic voucher generation. It prevents a partial voucher when
     * only some monetary facts happen to match a rule.
     */
    public int validateBusinessBatch(long entityId, long batchId, String sourceType,
                                     String postingAction, LocalDate businessDate) {
        List<BatchFact> facts = jdbc.query("""
                select fact_type,direction,amount from business_posting_entry
                where posting_batch_id=? order by line_no
                """, (rs, rowNum) -> new BatchFact(rs.getString("fact_type"), rs.getString("direction"),
                rs.getBigDecimal("amount")), batchId);
        return validateFacts(facts, sourceType, postingAction, policy -> jdbc.queryForObject("""
                select count(*) from accounting_rule_version
                where entity_id=? and source_type=? and posting_action=? and fact_type=?
                  and fact_direction=? and status='ENABLED' and effective_from<=?
                  and (effective_to is null or effective_to>=?)
                """, Integer.class, entityId, sourceType, postingAction, policy.factType(),
                policy.factDirection(), Date.valueOf(businessDate), Date.valueOf(businessDate)));
    }

    static int validateFacts(List<BatchFact> facts, String sourceType, String postingAction,
                             ToIntFunction<AccountingCoverageCatalog.FactPolicy> activeRuleCount) {
        int expectedMappedFacts = 0;
        for (BatchFact fact : facts) {
            AccountingCoverageCatalog.FactPolicy policy = AccountingCoverageCatalog.policyFor(
                    sourceType, postingAction, fact.factType(), fact.direction()).orElseThrow(() ->
                    new BusinessException("会计覆盖矩阵未登记业务事实：" + sourceType + "/" + postingAction
                            + "/" + fact.factType() + "/" + fact.direction()));
            if (policy.mode() != AccountingCoverageCatalog.GenerationMode.ACCOUNTING_RULE) continue;
            int count = activeRuleCount.applyAsInt(policy);
            if (count == 0) throw new BusinessException("会计规则缺失，已阻止生成不完整凭证："
                    + sourceType + "/" + postingAction + "/" + fact.factType() + "/" + fact.direction());
            if (count != 1) throw new BusinessException("会计规则不唯一，已阻止生成不确定凭证："
                    + sourceType + "/" + postingAction + "/" + fact.factType() + "/" + fact.direction()
                    + "，有效规则数=" + count);
            if (fact.amount().signum() != 0) expectedMappedFacts++;
        }
        return expectedMappedFacts;
    }

    private AccountingDtos.RuleCoverageRow toRow(AccountingCoverageCatalog.Scenario scenario,
                                                   List<ActiveRule> matches) {
        String status;
        ActiveRule actual = matches.isEmpty() ? null : matches.get(0);
        if (scenario.mode() == AccountingCoverageCatalog.GenerationMode.NO_VOUCHER) {
            status = "NOT_REQUIRED";
        } else if (scenario.mode() != AccountingCoverageCatalog.GenerationMode.ACCOUNTING_RULE) {
            status = "COVERED";
        } else if (matches.isEmpty()) {
            status = "MISSING_RULE";
        } else if (matches.size() > 1) {
            status = "AMBIGUOUS_RULE";
        } else if (!matchesCatalogBaseline(scenario, actual)) {
            status = "CUSTOMIZED";
        } else {
            status = "COVERED";
        }
        String evidence = scenario.evidence();
        if (actual != null) {
            evidence += "；生效规则 " + actual.ruleCode() + "：借 " + actual.debitCode()
                    + "，贷 " + actual.creditCode();
        }
        return new AccountingDtos.RuleCoverageRow(scenario.scenarioCode(), scenario.category(), scenario.name(),
                scenario.sourceType(), scenario.postingAction(), scenario.factType(), scenario.factDirection(),
                scenario.settlementMethod(), scenario.mode().name(), scenario.voucherRequired(),
                scenario.baselineDebitCode(), scenario.baselineCreditCode(), actual == null ? "" : actual.ruleCode(),
                actual == null ? "" : actual.debitCode(), actual == null ? "" : actual.creditCode(), matches.size(),
                status, scenario.resultDescription(), evidence);
    }

    private boolean matchesCatalogBaseline(AccountingCoverageCatalog.Scenario scenario, ActiveRule actual) {
        return AccountingCoverageCatalog.policyFor(scenario.sourceType(), scenario.postingAction(),
                        scenario.factType(), scenario.factDirection())
                .map(policy -> policy.baselineDebitCode().equals(actual.debitCode())
                        && policy.baselineCreditCode().equals(actual.creditCode()))
                .orElse(false);
    }

    private void appendUnregisteredRules(List<AccountingDtos.RuleCoverageRow> rows,
                                         List<ActiveRule> activeRules) {
        Set<String> registered = AccountingCoverageCatalog.factPolicies().stream()
                .filter(policy -> policy.mode() == AccountingCoverageCatalog.GenerationMode.ACCOUNTING_RULE)
                .map(AccountingCoverageCatalog.FactPolicy::key).collect(Collectors.toSet());
        for (ActiveRule rule : activeRules) {
            if (registered.contains(rule.key())) continue;
            rows.add(new AccountingDtos.RuleCoverageRow("UNREGISTERED_RULE_" + rule.id(), "配置漂移",
                    "会计规则未登记到覆盖矩阵", rule.sourceType(), rule.postingAction(), rule.factType(),
                    rule.factDirection(), "", "ACCOUNTING_RULE", true, "", "", rule.ruleCode(),
                    rule.debitCode(), rule.creditCode(), 1, "UNREGISTERED_RULE",
                    "规则存在，但系统无法证明对应业务事实及稳定凭证语义",
                    "先登记覆盖策略与自动化断言，再启用该规则"));
        }
    }

    private void appendUndeclaredBusinessFacts(List<AccountingDtos.RuleCoverageRow> rows, LocalDate asOf) {
        List<DeclaredFact> declared = jdbc.query("""
                select distinct r.source_type,r.posting_action,j.fact_type
                from business_posting_rule_version r
                join json_table(r.rule_json,'$.facts[*]' columns(fact_type varchar(96) path '$')) j
                where r.status='ENABLED' and r.effective_from<=?
                  and (r.effective_to is null or r.effective_to>=?)
                """, (rs, rowNum) -> new DeclaredFact(rs.getString("source_type"),
                rs.getString("posting_action"), rs.getString("fact_type")),
                Date.valueOf(asOf), Date.valueOf(asOf));
        for (DeclaredFact fact : declared) {
            if (AccountingCoverageCatalog.knowsDeclaredFact(fact.sourceType(), fact.postingAction(), fact.factType())) continue;
            rows.add(new AccountingDtos.RuleCoverageRow("UNREGISTERED_FACT_" + fact.sourceType() + "_"
                    + fact.postingAction() + "_" + fact.factType(), "配置漂移", "业务事实未登记到覆盖矩阵",
                    fact.sourceType(), fact.postingAction(), fact.factType(), "*", "", "UNKNOWN", false,
                    "", "", "", "", "", 0, "UNREGISTERED_FACT", "不能确定是否应生成总账",
                    "业务过账规则已经声明该事实，但覆盖目录没有会计处置策略"));
        }
    }

    private List<ActiveRule> activeRules(long entityId, LocalDate asOf) {
        return jdbc.query("""
                select r.id,r.rule_code,r.source_type,r.posting_action,r.fact_type,r.fact_direction,
                       da.account_code debit_code,ca.account_code credit_code
                from accounting_rule_version r
                join accounting_account da on da.id=r.debit_account_id
                join accounting_account ca on ca.id=r.credit_account_id
                where r.entity_id=? and r.status='ENABLED' and r.effective_from<=?
                  and (r.effective_to is null or r.effective_to>=?)
                order by r.source_type,r.posting_action,r.fact_type,r.fact_direction,r.id
                """, (rs, rowNum) -> new ActiveRule(rs.getLong("id"), rs.getString("rule_code"),
                rs.getString("source_type"), rs.getString("posting_action"), rs.getString("fact_type"),
                rs.getString("fact_direction"), rs.getString("debit_code"), rs.getString("credit_code")),
                entityId, Date.valueOf(asOf), Date.valueOf(asOf));
    }

    private static boolean isBlocking(String status) {
        return Set.of("MISSING_RULE", "AMBIGUOUS_RULE", "UNREGISTERED_RULE", "UNREGISTERED_FACT")
                .contains(status);
    }

    private static String key(String sourceType, String action, String factType, String direction) {
        return String.join("|", sourceType, action, factType, direction);
    }

    private record ActiveRule(long id, String ruleCode, String sourceType, String postingAction,
                              String factType, String factDirection, String debitCode, String creditCode) {
        String key() { return AccountingCoverageService.key(sourceType, postingAction, factType, factDirection); }
    }

    record BatchFact(String factType, String direction, java.math.BigDecimal amount) {}
    private record DeclaredFact(String sourceType, String postingAction, String factType) {}
}
