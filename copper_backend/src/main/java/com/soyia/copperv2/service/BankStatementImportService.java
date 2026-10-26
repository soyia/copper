package com.soyia.copperv2.service;

import com.soyia.copperv2.dto.FinancialCloseDtos;
import com.soyia.copperv2.security.SecurityContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class BankStatementImportService {
    private static final DateTimeFormatter BATCH_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");
    private static final AtomicInteger COUNTER = new AtomicInteger();
    private static final int AUTO_MATCH_SCORE = 80;
    private static final int REVIEW_SCORE = 65;
    private static final int UNIQUE_LEAD = 15;

    private final JdbcTemplate jdbc;
    private final BankStatementFileParser parser;
    private final FinancialCloseService financialCloseService;
    private final LocalFileStorageService storage;
    private final StorageCapacityService capacity;

    public BankStatementImportService(JdbcTemplate jdbc, BankStatementFileParser parser,
                                      FinancialCloseService financialCloseService,
                                      LocalFileStorageService storage,
                                      StorageCapacityService capacity) {
        this.jdbc = jdbc;
        this.parser = parser;
        this.financialCloseService = financialCloseService;
        this.storage = storage;
        this.capacity = capacity;
    }

    public BankStatementFileParser.TemplateFile template(String format) {
        return parser.template(format);
    }

    @Transactional
    public FinancialCloseDtos.StatementImportPreview preview(Long sessionId, MultipartFile file) {
        capacity.assertLargeWriteAllowed("银行流水导入");
        financialCloseService.assertStatementImportEditable(sessionId);
        BankStatementFileParser.ParsedFile parsed = parser.parse(file);
        String sha = sha256(parsed.bytes());
        String extension = "CSV".equals(parsed.format()) ? ".csv" : ".xlsx";
        String storagePath = storage.store(parsed.bytes(), "bank-statement-imports", extension);
        SecurityContext.Principal principal = SecurityContext.require();
        String actorName = StringUtils.hasText(principal.employeeName()) ? principal.employeeName() : principal.username();
        String batchNo = "YH-IMP-" + LocalDateTime.now().format(BATCH_TIME)
                + String.format("%03d", Math.floorMod(COUNTER.incrementAndGet(), 1000));
        Long batchId = insert("""
                insert into bank_statement_import_batch
                (session_id,batch_no,template_version,source_file_name,source_file_format,
                 source_file_sha256,source_file_size,source_storage_path,status,imported_by,imported_by_name)
                values (?,?,?,?,?,?,?,?,'PREVIEWED',?,?)
                """, sessionId, batchNo, BankStatementFileParser.TEMPLATE_VERSION, parsed.fileName(),
                parsed.format(), sha, parsed.bytes().length, storagePath,
                principal.userId() == null ? 0L : principal.userId(), actorName);

        ImportContext context = context(sessionId);
        Map<String, Long> existingHashes = existingHashLines(sessionId);
        Map<String, Long> existingSerials = existingSerialLines(sessionId);
        Set<String> fileHashes = new HashSet<>();
        Set<String> fileSerials = new HashSet<>();
        Set<String> reservedAutoCandidates = new HashSet<>();
        List<FinancialCloseDtos.FundCandidate> candidates = financialCloseService.statementImportCandidates(sessionId);
        Counters counters = new Counters();
        for (BankStatementFileParser.ParsedRow parsedRow : parsed.rows()) {
            ImportDecision decision = decide(parsedRow, context, existingHashes, existingSerials,
                    fileHashes, fileSerials, candidates, reservedAutoCandidates);
            insertRow(batchId, parsedRow.sourceRowNo(), parsedRow.input(), decision);
            counters.add(decision);
        }
        jdbc.update("""
                update bank_statement_import_batch set total_rows=?,valid_rows=?,duplicate_rows=?,error_rows=?,
                  auto_match_rows=?,review_rows=?,unmatched_rows=? where id=?
                """, parsed.rows().size(), counters.valid, counters.duplicates, counters.errors,
                counters.autoMatches, counters.reviews, counters.unmatched, batchId);
        return preview(sessionId, batchId);
    }

    public List<FinancialCloseDtos.StatementImportBatchView> batches(Long sessionId) {
        financialCloseService.assertStatementImportVisible(sessionId);
        return jdbc.query(batchSelect() + " where session_id=? order by id desc", batchMapper(), sessionId);
    }

    public FinancialCloseDtos.StatementImportPreview preview(Long sessionId, Long batchId) {
        financialCloseService.assertStatementImportVisible(sessionId);
        List<FinancialCloseDtos.StatementImportBatchView> batches = jdbc.query(
                batchSelect() + " where id=? and session_id=?", batchMapper(), batchId, sessionId);
        if (batches.isEmpty()) throw new BusinessException("银行流水导入批次不存在");
        List<FinancialCloseDtos.StatementImportRowView> rows = jdbc.query("""
                select * from bank_statement_import_row where batch_id=? order by source_row_no
                """, (rs, rowNum) -> new FinancialCloseDtos.StatementImportRowView(
                rs.getLong("id"), rs.getLong("batch_id"), rs.getInt("source_row_no"),
                rs.getDate("transaction_date") == null ? null : rs.getDate("transaction_date").toLocalDate(),
                rs.getDate("value_date") == null ? null : rs.getDate("value_date").toLocalDate(),
                rs.getString("bank_serial_no"), rs.getString("direction"), rs.getBigDecimal("amount"),
                rs.getBigDecimal("balance"), rs.getString("counterparty_name"),
                rs.getString("counterparty_account"), rs.getString("purpose"),
                rs.getString("validation_status"), rs.getString("validation_message"),
                rs.getString("duplicate_type"), rs.getLong("duplicate_statement_line_id"),
                rs.getString("match_action"), rs.getString("suggested_source_type"),
                rs.getLong("suggested_source_id"), rs.getString("suggested_source_no"),
                rs.getInt("suggested_score"), rs.getString("suggested_reason"),
                rs.getString("unmatched_reason_code"), rs.getString("unmatched_reason"),
                rs.getLong("imported_statement_line_id")), batchId);
        return new FinancialCloseDtos.StatementImportPreview(batches.get(0), rows);
    }

    @Transactional
    public FinancialCloseDtos.StatementImportResult commit(Long sessionId, Long batchId,
                                                            FinancialCloseDtos.StatementImportCommit request) {
        return financialCloseService.commitStatementImport(sessionId, batchId,
                request == null || !Boolean.FALSE.equals(request.applyAutoMatches()));
    }

    private ImportDecision decide(BankStatementFileParser.ParsedRow parsedRow, ImportContext context,
                                  Map<String, Long> existingHashes, Map<String, Long> existingSerials,
                                  Set<String> fileHashes, Set<String> fileSerials,
                                  List<FinancialCloseDtos.FundCandidate> candidates,
                                  Set<String> reservedAutoCandidates) {
        if (!parsedRow.valid()) return ImportDecision.error(parsedRow.error());
        FinancialCloseDtos.StatementLineInput input = parsedRow.input();
        if (input.transactionDate().isBefore(context.from()) || input.transactionDate().isAfter(context.to())) {
            return ImportDecision.error("交易日期不在当前对账期间 " + context.from() + " 至 " + context.to() + " 内");
        }
        String hash = financialCloseService.statementSourceHash(input);
        if (!fileHashes.add(hash)) return ImportDecision.duplicate(hash, "FILE_EXACT", 0L, "文件内存在内容完全相同的重复行");
        Long exactId = existingHashes.get(hash);
        if (exactId != null) return ImportDecision.duplicate(hash, "EXISTING_EXACT", exactId, "本对账单已存在内容完全相同的银行流水");
        String serial = normalizeSerial(input.bankSerialNo());
        if (StringUtils.hasText(serial) && !fileSerials.add(serial)) {
            return ImportDecision.duplicate(hash, "FILE_SERIAL", 0L, "文件内存在相同银行流水号");
        }
        Long serialId = StringUtils.hasText(serial) ? existingSerials.get(serial) : null;
        if (serialId != null) return ImportDecision.duplicate(hash, "EXISTING_SERIAL", serialId, "本对账单已存在相同银行流水号");

        MatchSuggestion suggestion = suggest(input, candidates);
        String candidateKey = suggestion.sourceType() + ":" + suggestion.sourceId();
        if ("AUTO_MATCH".equals(suggestion.action()) && !reservedAutoCandidates.add(candidateKey)) {
            suggestion = new MatchSuggestion("REVIEW", suggestion.sourceType(), suggestion.sourceId(),
                    suggestion.sourceNo(), suggestion.score(), suggestion.reason(),
                    "CANDIDATE_ALREADY_SUGGESTED", "同一系统资金已被本批次另一行优先建议，需人工确认");
        }
        return ImportDecision.valid(hash, suggestion);
    }

    private MatchSuggestion suggest(FinancialCloseDtos.StatementLineInput line,
                                    List<FinancialCloseDtos.FundCandidate> candidates) {
        List<FinancialCloseDtos.FundCandidate> sameDirection = candidates.stream()
                .filter(candidate -> candidate.direction().equals(line.direction())).toList();
        List<ScoredCandidate> exactAmount = sameDirection.stream()
                .filter(candidate -> candidate.remainingAmount().compareTo(line.amount()) == 0)
                .map(candidate -> score(line, candidate))
                .sorted(Comparator.comparingInt(ScoredCandidate::score).reversed()
                        .thenComparing(candidate -> candidate.candidate().sourceDate())
                        .thenComparing(candidate -> candidate.candidate().sourceNo()))
                .toList();
        if (exactAmount.isEmpty()) {
            return MatchSuggestion.unmatched("NO_AMOUNT_CANDIDATE", "没有收支方向和待匹配金额同时一致的系统资金");
        }
        ScoredCandidate best = exactAmount.get(0);
        int secondScore = exactAmount.size() > 1 ? exactAmount.get(1).score() : -1;
        boolean unique = best.score() - secondScore >= UNIQUE_LEAD;
        FinancialCloseDtos.FundCandidate candidate = best.candidate();
        if (best.score() >= AUTO_MATCH_SCORE && unique && best.identityEvidence()) {
            return new MatchSuggestion("AUTO_MATCH", candidate.sourceType(), candidate.sourceId(),
                    candidate.sourceNo(), best.score(), best.reason(), "", "");
        }
        if (best.score() >= REVIEW_SCORE) {
            String code = unique ? "LOW_CONFIDENCE" : "MULTIPLE_CANDIDATES";
            String reason = unique ? "金额和日期相符，但对方户名或用途证据不足，需人工确认" :
                    "存在多个接近的系统资金候选，不能自动决定";
            return new MatchSuggestion("REVIEW", candidate.sourceType(), candidate.sourceId(),
                    candidate.sourceNo(), best.score(), best.reason(), code, reason);
        }
        boolean dateNear = exactAmount.stream().anyMatch(item -> item.dayGap() <= 7);
        if (!dateNear) return MatchSuggestion.unmatched("DATE_OUT_OF_RANGE", "存在相同金额，但业务日期与银行交易日期相差超过7天");
        return MatchSuggestion.unmatched("COUNTERPARTY_OR_PURPOSE_MISMATCH", "金额和日期接近，但对方户名、用途或系统单号无法相互印证");
    }

    private ScoredCandidate score(FinancialCloseDtos.StatementLineInput line,
                                  FinancialCloseDtos.FundCandidate candidate) {
        int score = 40;
        List<String> reasons = new ArrayList<>(List.of("金额一致 +40"));
        long dayGap = Math.abs(java.time.temporal.ChronoUnit.DAYS.between(line.transactionDate(), candidate.sourceDate()));
        int dateScore = dayGap == 0 ? 25 : dayGap <= 1 ? 20 : dayGap <= 3 ? 12 : dayGap <= 7 ? 5 : 0;
        if (dateScore > 0) { score += dateScore; reasons.add("日期相差" + dayGap + "天 +" + dateScore); }
        String lineName = normalizeName(line.counterpartyName());
        String partnerName = normalizeName(candidate.partnerName());
        boolean nameMatch = StringUtils.hasText(lineName) && StringUtils.hasText(partnerName) &&
                (lineName.equals(partnerName) || lineName.contains(partnerName) || partnerName.contains(lineName));
        if (nameMatch) { score += 25; reasons.add("对方户名相符 +25"); }
        String purpose = normalizeText(line.purpose());
        String sourceNo = normalizeText(candidate.sourceNo());
        boolean sourceNoMatch = StringUtils.hasText(sourceNo) && purpose.contains(sourceNo);
        if (sourceNoMatch) { score += 30; reasons.add("用途包含系统单号 +30"); }
        boolean memoMatch = tokenOverlap(line.purpose(), candidate.memo());
        if (memoMatch) { score += 10; reasons.add("用途与业务备注相符 +10"); }
        return new ScoredCandidate(candidate, Math.min(score, 100), dayGap,
                nameMatch || sourceNoMatch, String.join("；", reasons));
    }

    private boolean tokenOverlap(String left, String right) {
        if (!StringUtils.hasText(left) || !StringUtils.hasText(right)) return false;
        String normalizedLeft = normalizeText(left);
        for (String token : right.split("[\\s,，;；:/\\-]+")) {
            String normalized = normalizeText(token);
            if (normalized.length() >= 4 && normalizedLeft.contains(normalized)) return true;
        }
        return false;
    }

    private void insertRow(Long batchId, int rowNo, FinancialCloseDtos.StatementLineInput line,
                           ImportDecision decision) {
        jdbc.update("""
                insert into bank_statement_import_row
                (batch_id,source_row_no,transaction_date,value_date,bank_serial_no,direction,amount,balance,
                 counterparty_name,counterparty_account,purpose,source_hash,validation_status,validation_message,
                 duplicate_type,duplicate_statement_line_id,match_action,suggested_source_type,suggested_source_id,
                 suggested_source_no,suggested_score,suggested_reason,unmatched_reason_code,unmatched_reason)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, batchId, rowNo, line == null ? null : Date.valueOf(line.transactionDate()),
                line == null ? null : Date.valueOf(line.valueDate()), line == null ? "" : text(line.bankSerialNo()),
                line == null ? "" : line.direction(), line == null ? null : line.amount(),
                line == null ? null : line.balance(), line == null ? "" : text(line.counterpartyName()),
                line == null ? "" : text(line.counterpartyAccount()), line == null ? "" : text(line.purpose()),
                decision.sourceHash(), decision.validationStatus(), decision.validationMessage(),
                decision.duplicateType(), decision.duplicateLineId(), decision.matchAction(),
                decision.suggestion().sourceType(), decision.suggestion().sourceId(), decision.suggestion().sourceNo(),
                decision.suggestion().score(), decision.suggestion().reason(),
                decision.suggestion().unmatchedCode(), decision.suggestion().unmatchedReason());
    }

    private ImportContext context(Long sessionId) {
        List<ImportContext> rows = jdbc.query("""
                select id,statement_from,statement_to from bank_reconciliation_session where id=?
                """, (rs, rowNum) -> new ImportContext(rs.getLong("id"),
                rs.getDate("statement_from").toLocalDate(), rs.getDate("statement_to").toLocalDate()), sessionId);
        if (rows.isEmpty()) throw new BusinessException("银行对账单不存在");
        return rows.get(0);
    }

    private Map<String, Long> existingHashLines(Long sessionId) {
        Map<String, Long> result = new HashMap<>();
        for (Map<String, Object> row : jdbc.queryForList(
                "select id,source_hash from bank_statement_line where session_id=?", sessionId)) {
            result.put(String.valueOf(row.get("source_hash")), ((Number) row.get("id")).longValue());
        }
        return result;
    }

    private Map<String, Long> existingSerialLines(Long sessionId) {
        Map<String, Long> result = new HashMap<>();
        for (Map<String, Object> row : jdbc.queryForList(
                "select id,bank_serial_no from bank_statement_line where session_id=? and bank_serial_no<>''", sessionId)) {
            result.putIfAbsent(normalizeSerial(String.valueOf(row.get("bank_serial_no"))),
                    ((Number) row.get("id")).longValue());
        }
        return result;
    }

    private String batchSelect() {
        return """
                select id,session_id,batch_no,template_version,source_file_name,source_file_format,
                  source_file_sha256,source_file_size,status,total_rows,valid_rows,duplicate_rows,error_rows,
                  auto_match_rows,review_rows,unmatched_rows,imported_rows,applied_auto_match_rows,
                  imported_by_name,created_at,completed_at from bank_statement_import_batch
                """;
    }

    private org.springframework.jdbc.core.RowMapper<FinancialCloseDtos.StatementImportBatchView> batchMapper() {
        return (rs, rowNum) -> new FinancialCloseDtos.StatementImportBatchView(
                rs.getLong("id"), rs.getLong("session_id"), rs.getString("batch_no"),
                rs.getString("template_version"), rs.getString("source_file_name"),
                rs.getString("source_file_format"), rs.getString("source_file_sha256"),
                rs.getLong("source_file_size"), rs.getString("status"), rs.getInt("total_rows"),
                rs.getInt("valid_rows"), rs.getInt("duplicate_rows"), rs.getInt("error_rows"),
                rs.getInt("auto_match_rows"), rs.getInt("review_rows"), rs.getInt("unmatched_rows"),
                rs.getInt("imported_rows"), rs.getInt("applied_auto_match_rows"),
                rs.getString("imported_by_name"), rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toLocalDateTime());
    }

    private Long insert(String sql, Object... args) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
            return statement;
        }, key);
        Number value = key.getKey();
        if (value == null) throw new IllegalStateException("未取得银行流水导入批次编号");
        return value.longValue();
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception ex) {
            throw new IllegalStateException("无法生成银行流水文件摘要", ex);
        }
    }

    private String normalizeSerial(String value) {
        return normalizeText(value).replace("-", "");
    }

    private String normalizeName(String value) {
        String normalized = normalizeText(value);
        for (String suffix : List.of("有限责任公司", "股份有限公司", "有限公司", "个体工商户", "公司")) {
            if (normalized.endsWith(suffix)) normalized = normalized.substring(0, normalized.length() - suffix.length());
        }
        return normalized;
    }

    private String normalizeText(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    private String text(String value) { return value == null ? "" : value.trim(); }

    private record ImportContext(long id, LocalDate from, LocalDate to) {}
    private record ScoredCandidate(FinancialCloseDtos.FundCandidate candidate, int score,
                                   long dayGap, boolean identityEvidence, String reason) {}
    private record MatchSuggestion(String action, String sourceType, long sourceId, String sourceNo,
                                   int score, String reason, String unmatchedCode, String unmatchedReason) {
        private static MatchSuggestion unmatched(String code, String reason) {
            return new MatchSuggestion("UNMATCHED", "", 0L, "", 0, "", code, reason);
        }
        private static MatchSuggestion empty() { return unmatched("", ""); }
    }
    private record ImportDecision(String sourceHash, String validationStatus, String validationMessage,
                                  String duplicateType, long duplicateLineId, String matchAction,
                                  MatchSuggestion suggestion) {
        private static ImportDecision error(String message) {
            return new ImportDecision("", "ERROR", message, "NONE", 0L, "INVALID", MatchSuggestion.empty());
        }
        private static ImportDecision duplicate(String hash, String type, long lineId, String message) {
            return new ImportDecision(hash, "DUPLICATE", message, type, lineId, "DUPLICATE", MatchSuggestion.empty());
        }
        private static ImportDecision valid(String hash, MatchSuggestion suggestion) {
            return new ImportDecision(hash, "VALID", "", "NONE", 0L, suggestion.action(), suggestion);
        }
    }
    private static final class Counters {
        private int valid;
        private int duplicates;
        private int errors;
        private int autoMatches;
        private int reviews;
        private int unmatched;
        private void add(ImportDecision decision) {
            switch (decision.validationStatus()) {
                case "ERROR" -> errors++;
                case "DUPLICATE" -> duplicates++;
                default -> {
                    valid++;
                    switch (decision.matchAction()) {
                        case "AUTO_MATCH" -> autoMatches++;
                        case "REVIEW" -> reviews++;
                        default -> unmatched++;
                    }
                }
            }
        }
    }
}
