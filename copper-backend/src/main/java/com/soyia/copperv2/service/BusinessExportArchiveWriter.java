package com.soyia.copperv2.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

@Service
public class BusinessExportArchiveWriter {
    private static final long MIN_FREE_BYTES = 128L * 1024 * 1024;
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssXX");
    private static final Set<String> SENSITIVE_EXCLUSION_REASONS = Set.of("password_hash", "file_content");

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final LocalFileStorageService fileStorage;
    private final BusinessExportProgressReporter progressReporter;
    private final TransactionTemplate snapshotTransaction;
    private final Path rootPath;
    private final Path workRoot;
    private final ZoneId zoneId;

    public BusinessExportArchiveWriter(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            LocalFileStorageService fileStorage,
            BusinessExportProgressReporter progressReporter,
            PlatformTransactionManager transactionManager,
            @Value("${copper.export.root-path}") String configuredRootPath,
            @Value("${spring.jackson.time-zone}") String configuredTimeZone
    ) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.fileStorage = fileStorage;
        this.progressReporter = progressReporter;
        if (!(transactionManager instanceof DataSourceTransactionManager)) {
            throw new IllegalStateException("完整业务导出要求 JDBC 事务管理器");
        }
        this.snapshotTransaction = new TransactionTemplate(transactionManager);
        snapshotTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        snapshotTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        snapshotTransaction.setReadOnly(true);
        snapshotTransaction.setTimeout(60 * 60);
        if (!StringUtils.hasText(configuredRootPath)) throw new IllegalStateException("copper.export.root-path 不能为空");
        this.rootPath = Path.of(configuredRootPath).toAbsolutePath().normalize();
        this.workRoot = rootPath.resolve(".work");
        this.zoneId = ZoneId.of(configuredTimeZone);
        initializeDirectories();
    }

    public ArchiveResult writeFullExport(String exportId, Long requesterId, String requesterName) {
        UUID.fromString(exportId);
        ensureFreeSpace();
        Path work = workRoot.resolve(exportId).normalize();
        if (!work.startsWith(workRoot)) throw new IllegalArgumentException("非法导出任务 ID");
        Path partial = rootPath.resolve("." + exportId + ".partial");
        Path archive = null;
        try {
            if (Files.exists(work)) throw new IllegalStateException("导出暂存目录已存在");
            Files.createDirectories(work);
            OffsetDateTime snapshotStartedAt = OffsetDateTime.now(zoneId);
            Snapshot snapshot = snapshotTransaction.execute(status -> generateSnapshot(work, exportId));
            if (snapshot == null) throw new IllegalStateException("导出事务未生成结果");
            OffsetDateTime completedAt = OffsetDateTime.now(zoneId);
            progressReporter.report(exportId, 90, "BUILDING_MANIFEST");
            writeReadme(work, exportId, snapshotStartedAt, completedAt);
            writeManifest(work, exportId, requesterId, requesterName, snapshotStartedAt, completedAt, snapshot);
            writeChecksums(work);
            String fileName = "copper_full_" + completedAt.format(FILE_TIME) + "_" + exportId + ".zip";
            archive = rootPath.resolve(fileName).normalize();
            progressReporter.report(exportId, 92, "PACKAGING_ARCHIVE");
            zipDirectory(work, partial);
            moveAtomically(partial, archive);
            verifyArchive(archive);
            long archiveBytes = Files.size(archive);
            String archiveSha256 = sha256(archive);
            deleteTree(work);
            return new ArchiveResult(fileName, fileName, archiveBytes, archiveSha256,
                    snapshot.rowCounts().size(), snapshot.recordCount(), snapshot.attachmentCount());
        } catch (RuntimeException | IOException ex) {
            deleteQuietly(partial);
            if (archive != null) deleteQuietly(archive);
            deleteTreeQuietly(work);
            if (ex instanceof BusinessException business) throw business;
            throw new IllegalStateException("完整业务导出生成失败", ex);
        }
    }

    public Path resolveCompletedArchive(String relativePath) {
        if (!StringUtils.hasText(relativePath)) throw new BusinessException("导出文件尚未生成");
        Path candidate = Path.of(relativePath);
        if (candidate.isAbsolute()) throw new BusinessException("导出文件路径非法");
        Path resolved = rootPath.resolve(candidate).normalize();
        if (!resolved.startsWith(rootPath) || !Files.isRegularFile(resolved)) {
            throw new BusinessException("导出文件不存在，请重新生成");
        }
        return resolved;
    }

    public void deleteArchive(String relativePath) {
        if (!StringUtils.hasText(relativePath)) return;
        Path candidate = Path.of(relativePath);
        if (candidate.isAbsolute()) return;
        Path resolved = rootPath.resolve(candidate).normalize();
        if (resolved.startsWith(rootPath)) deleteQuietly(resolved);
    }

    public void cleanupInterruptedWork() {
        if (!Files.isDirectory(workRoot)) return;
        try (Stream<Path> paths = Files.list(workRoot)) {
            paths.filter(Files::isDirectory).forEach(this::deleteTreeQuietly);
        } catch (IOException ex) {
            throw new IllegalStateException("无法清理中断的导出暂存目录", ex);
        }
    }

    public void cleanupInterruptedArtifacts(List<String> exportIds) {
        for (String exportId : exportIds) {
            UUID.fromString(exportId);
            deleteQuietly(rootPath.resolve("." + exportId + ".partial"));
            try (Stream<Path> paths = Files.list(rootPath)) {
                paths.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith("_" + exportId + ".zip"))
                        .forEach(this::deleteQuietly);
            } catch (IOException ex) {
                throw new IllegalStateException("无法清理中断的导出文件", ex);
            }
        }
    }

    private Snapshot generateSnapshot(Path work, String exportId) {
        List<TableDefinition> allTables = loadTableDefinitions();
        validateTableCoverage(allTables);
        List<TableDefinition> exportedTables = allTables.stream()
                .filter(table -> !BusinessExportCatalog.EXCLUDED_TABLES.contains(table.name())).toList();
        Map<EnumKey, Set<String>> enums = new TreeMap<>(Comparator.comparing(EnumKey::table).thenComparing(EnumKey::column));
        Map<String, Long> rowCounts = new TreeMap<>();
        progressReporter.report(exportId, 8, "SCHEMA_DICTIONARY");
        writeSchemaDictionary(work, allTables);
        int processedTables = 0;
        int lastProgress = 8;
        for (TableDefinition table : exportedTables) {
            long rows = exportTable(work, table, enums);
            rowCounts.put(table.name(), rows);
            processedTables++;
            int progress = 10 + (processedTables * 68 / exportedTables.size());
            if (progress > lastProgress) {
                progressReporter.report(exportId, progress, BusinessExportCatalog.phaseFor(table.name()));
                lastProgress = progress;
            }
        }
        progressReporter.report(exportId, 80, "ENUM_DICTIONARY");
        writeEnums(work, enums);
        progressReporter.report(exportId, 82, "BUSINESS_TOTALS");
        writeBusinessTotals(work);
        progressReporter.report(exportId, 86, "ATTACHMENTS");
        long attachmentCount = exportAttachments(work);
        long records = rowCounts.values().stream().mapToLong(Long::longValue).sum();
        return new Snapshot(rowCounts, records, attachmentCount);
    }

    private List<TableDefinition> loadTableDefinitions() {
        String database = jdbc.queryForObject("SELECT DATABASE()", String.class);
        if (!StringUtils.hasText(database)) throw new IllegalStateException("未连接 Copper 业务数据库");
        Map<String, String> comments = new LinkedHashMap<>();
        jdbc.query("""
                SELECT table_name, table_comment
                FROM information_schema.tables
                WHERE table_schema=? AND table_type='BASE TABLE'
                ORDER BY table_name
                """, (RowCallbackHandler) rs -> comments.put(rs.getString(1), rs.getString(2)), database);

        Map<ColumnKey, Reference> references = new LinkedHashMap<>();
        jdbc.query("""
                SELECT table_name, column_name, referenced_table_name, referenced_column_name
                FROM information_schema.key_column_usage
                WHERE table_schema=? AND referenced_table_name IS NOT NULL
                """, (RowCallbackHandler) rs -> references.put(new ColumnKey(rs.getString(1), rs.getString(2)),
                new Reference(rs.getString(3), rs.getString(4))), database);

        Map<String, List<ColumnDefinition>> columns = new LinkedHashMap<>();
        jdbc.query("""
                SELECT table_name, column_name, data_type, column_type, numeric_precision, numeric_scale,
                       is_nullable, column_key, column_comment, ordinal_position
                FROM information_schema.columns
                WHERE table_schema=?
                ORDER BY table_name, ordinal_position
                """, rs -> {
            String table = rs.getString("table_name");
            String column = rs.getString("column_name");
            Reference reference = references.get(new ColumnKey(table, column));
            columns.computeIfAbsent(table, ignored -> new ArrayList<>()).add(new ColumnDefinition(
                    column, rs.getString("data_type"), rs.getString("column_type"),
                    nullableLong(rs.getObject("numeric_precision")), nullableLong(rs.getObject("numeric_scale")),
                    "YES".equals(rs.getString("is_nullable")), "PRI".equals(rs.getString("column_key")),
                    rs.getString("column_comment"), rs.getInt("ordinal_position"), reference
            ));
        }, database);

        return comments.entrySet().stream().map(entry -> new TableDefinition(
                entry.getKey(), entry.getValue(), List.copyOf(columns.getOrDefault(entry.getKey(), List.of()))
        )).toList();
    }

    private void validateTableCoverage(List<TableDefinition> tables) {
        for (TableDefinition table : tables) {
            if (BusinessExportCatalog.EXCLUDED_TABLES.contains(table.name())) continue;
            BusinessExportCatalog.directoryFor(table.name());
            List<ColumnDefinition> exported = table.columns().stream()
                    .filter(column -> !BusinessExportCatalog.excludedColumn(table.name(), column.name())).toList();
            if (exported.isEmpty()) throw new IllegalStateException("业务表没有可导出字段：" + table.name());
            for (ColumnDefinition column : exported) {
                if (Set.of("blob", "longblob", "mediumblob", "tinyblob", "binary", "varbinary").contains(column.dataType())) {
                    throw new IllegalStateException("二进制字段必须显式排除或转为附件：" + table.name() + "." + column.name());
                }
            }
        }
    }

    private long exportTable(Path work, TableDefinition table, Map<EnumKey, Set<String>> enums) {
        List<ColumnDefinition> columns = table.columns().stream()
                .filter(column -> !BusinessExportCatalog.excludedColumn(table.name(), column.name())).toList();
        String directory = BusinessExportCatalog.directoryFor(table.name());
        Path target = work.resolve(directory).resolve(table.name() + ".csv");
        createParent(target);
        String columnSql = columns.stream().map(column -> quoteIdentifier(column.name())).collect(Collectors.joining(","));
        List<String> primary = columns.stream().filter(ColumnDefinition::primaryKey).map(ColumnDefinition::name).toList();
        String orderSql = primary.isEmpty() ? "" : " ORDER BY " + primary.stream().map(this::quoteIdentifier).collect(Collectors.joining(","));
        String sql = "SELECT " + columnSql + " FROM " + quoteIdentifier(table.name()) + orderSql;
        try (Writer writer = csvWriter(target)) {
            writeCsvLine(writer, columns.stream().map(ColumnDefinition::name).toList());
            Long count = jdbc.query(connection -> {
                PreparedStatement statement = connection.prepareStatement(sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
                statement.setFetchSize(Integer.MIN_VALUE);
                return statement;
            }, rs -> {
                long rows = 0;
                while (rs.next()) {
                    List<String> values = new ArrayList<>(columns.size());
                    for (int index = 0; index < columns.size(); index++) {
                        ColumnDefinition column = columns.get(index);
                        String value = BusinessExportFormat.value(rs.getObject(index + 1), zoneId);
                        values.add(value);
                        if (BusinessExportCatalog.enumColumn(column.name()) && !value.isBlank() && value.length() <= 128) {
                            enums.computeIfAbsent(new EnumKey(table.name(), column.name()), ignored -> new LinkedHashSet<>()).add(value);
                        }
                    }
                    try {
                        writeCsvLine(writer, values);
                    } catch (IOException ex) {
                        throw new UncheckedIOException(ex);
                    }
                    rows++;
                }
                return rows;
            });
            return count == null ? 0 : count;
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private void writeSchemaDictionary(Path work, List<TableDefinition> tables) {
        Path tableFile = work.resolve("schema/tables.csv");
        Path fieldFile = work.resolve("schema/fields.csv");
        createParent(tableFile);
        try (Writer tableWriter = csvWriter(tableFile); Writer fieldWriter = csvWriter(fieldFile)) {
            writeCsvLine(tableWriter, List.of("table_name", "chinese_name", "directory", "exported", "exclusion_reason"));
            writeCsvLine(fieldWriter, List.of("table_name", "table_chinese_name", "column_name", "chinese_name",
                    "data_type", "column_type", "precision", "scale", "nullable", "primary_key",
                    "referenced_table", "referenced_column", "exported", "exclusion_reason"));
            for (TableDefinition table : tables) {
                boolean tableExported = !BusinessExportCatalog.EXCLUDED_TABLES.contains(table.name());
                String directory = tableExported ? BusinessExportCatalog.directoryFor(table.name()) : "";
                String tableReason = tableExported ? "" : tableExclusionReason(table.name());
                writeCsvLine(tableWriter, List.of(table.name(), fallback(table.comment(), table.name()), directory,
                        String.valueOf(tableExported), tableReason));
                for (ColumnDefinition column : table.columns()) {
                    boolean columnExported = tableExported && !BusinessExportCatalog.excludedColumn(table.name(), column.name());
                    String reason = columnExported ? "" : columnExclusionReason(table.name(), column.name(), tableReason);
                    writeCsvLine(fieldWriter, List.of(
                            table.name(), fallback(table.comment(), table.name()), column.name(), fallback(column.comment(), column.name()),
                            column.dataType(), column.columnType(), number(column.precision()), number(column.scale()),
                            String.valueOf(column.nullable()), String.valueOf(column.primaryKey()),
                            column.reference() == null ? "" : column.reference().table(),
                            column.reference() == null ? "" : column.reference().column(),
                            String.valueOf(columnExported), reason
                    ));
                }
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private void writeEnums(Path work, Map<EnumKey, Set<String>> enums) {
        Path target = work.resolve("schema/enums.csv");
        createParent(target);
        try (Writer writer = csvWriter(target)) {
            writeCsvLine(writer, List.of("table_name", "column_name", "machine_value", "chinese_name"));
            for (Map.Entry<EnumKey, Set<String>> entry : enums.entrySet()) {
                entry.getValue().stream().sorted().forEach(value -> {
                    try {
                        writeCsvLine(writer, List.of(entry.getKey().table(), entry.getKey().column(), value,
                                BusinessExportCatalog.enumChineseName(value)));
                    } catch (IOException ex) {
                        throw new UncheckedIOException(ex);
                    }
                });
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private void writeBusinessTotals(Path work) {
        String sql = """
                SELECT 'stock.quantity_available' metric, CAST(COALESCE(SUM(quantity_available), 0) AS CHAR) value FROM stock_balance
                UNION ALL SELECT 'stock.amount_cost', CAST(COALESCE(SUM(amount_cost), 0) AS CHAR) FROM stock_balance
                UNION ALL SELECT 'inventory_batch.quantity_available', CAST(COALESCE(SUM(quantity_available), 0) AS CHAR) FROM inventory_batch
                UNION ALL SELECT 'inventory_batch.amount_cost', CAST(COALESCE(SUM(amount_cost), 0) AS CHAR) FROM inventory_batch
                UNION ALL SELECT CONCAT('partner_account.balance.', LOWER(account_type)), CAST(COALESCE(SUM(balance), 0) AS CHAR) FROM partner_account GROUP BY account_type
                UNION ALL SELECT CONCAT('partner_account.frozen.', LOWER(account_type)), CAST(COALESCE(SUM(frozen_amount), 0) AS CHAR) FROM partner_account GROUP BY account_type
                UNION ALL SELECT CONCAT('partner_account.opposite_balance.', LOWER(account_type)), CAST(COALESCE(SUM(opposite_balance), 0) AS CHAR) FROM partner_account GROUP BY account_type
                UNION ALL SELECT CONCAT('receivable_movement.amount.', LOWER(direction)), CAST(COALESCE(SUM(amount), 0) AS CHAR) FROM receivable_movement GROUP BY direction
                UNION ALL SELECT CONCAT('payable_movement.amount.', LOWER(direction)), CAST(COALESCE(SUM(amount), 0) AS CHAR) FROM payable_movement GROUP BY direction
                UNION ALL SELECT CONCAT('customer_payment.amount.', LOWER(verification_status)), CAST(COALESCE(SUM(amount), 0) AS CHAR) FROM payment GROUP BY verification_status
                UNION ALL SELECT CONCAT('customer_payment.allocated_amount.', LOWER(verification_status)), CAST(COALESCE(SUM(allocated_amount), 0) AS CHAR) FROM payment GROUP BY verification_status
                UNION ALL SELECT CONCAT('customer_payment.unallocated_amount.', LOWER(verification_status)), CAST(COALESCE(SUM(unallocated_amount), 0) AS CHAR) FROM payment GROUP BY verification_status
                UNION ALL SELECT CONCAT('supplier_payment.amount.', LOWER(verification_status)), CAST(COALESCE(SUM(amount), 0) AS CHAR) FROM supplier_payment GROUP BY verification_status
                UNION ALL SELECT CONCAT('supplier_payment.allocated_amount.', LOWER(verification_status)), CAST(COALESCE(SUM(allocated_amount), 0) AS CHAR) FROM supplier_payment GROUP BY verification_status
                UNION ALL SELECT CONCAT('supplier_payment.unallocated_amount.', LOWER(verification_status)), CAST(COALESCE(SUM(unallocated_amount), 0) AS CHAR) FROM supplier_payment GROUP BY verification_status
                UNION ALL SELECT CONCAT('payment_allocation.amount.', LOWER(status)), CAST(COALESCE(SUM(allocated_amount), 0) AS CHAR) FROM payment_allocation GROUP BY status
                UNION ALL SELECT CONCAT('supplier_payment_allocation.amount.', LOWER(status)), CAST(COALESCE(SUM(allocated_amount), 0) AS CHAR) FROM supplier_payment_allocation GROUP BY status
                UNION ALL SELECT CONCAT('payment_allocation_event.amount.', LOWER(action)), CAST(COALESCE(SUM(amount), 0) AS CHAR) FROM payment_allocation_event GROUP BY action
                UNION ALL SELECT CONCAT('supplier_payment_allocation_event.amount.', LOWER(action)), CAST(COALESCE(SUM(amount), 0) AS CHAR) FROM supplier_payment_allocation_event GROUP BY action
                UNION ALL SELECT CONCAT('acceptance_bill.face_amount.', LOWER(status)), CAST(COALESCE(SUM(face_amount), 0) AS CHAR) FROM acceptance_bill GROUP BY status
                UNION ALL SELECT 'acceptance_bill.discount_fee.total', CAST(COALESCE(SUM(discount_fee), 0) AS CHAR) FROM acceptance_bill
                UNION ALL SELECT 'acceptance_bill.net_proceeds.total', CAST(COALESCE(SUM(net_proceeds), 0) AS CHAR) FROM acceptance_bill
                UNION ALL SELECT CONCAT('acceptance_bill_event.cash_amount.', LOWER(event_type)), CAST(COALESCE(SUM(cash_amount), 0) AS CHAR) FROM acceptance_bill_event GROUP BY event_type
                UNION ALL SELECT CONCAT('acceptance_bill_event.fee_amount.', LOWER(event_type)), CAST(COALESCE(SUM(fee_amount), 0) AS CHAR) FROM acceptance_bill_event GROUP BY event_type
                UNION ALL SELECT 'customer_advance_refund.amount.total', CAST(COALESCE(SUM(amount), 0) AS CHAR) FROM customer_advance_refund
                UNION ALL SELECT CONCAT('sales_order_advance_allocation.amount.', LOWER(status)), CAST(COALESCE(SUM(amount), 0) AS CHAR) FROM sales_order_advance_allocation GROUP BY status
                UNION ALL SELECT CONCAT('outbound_advance_allocation.amount.', LOWER(status)), CAST(COALESCE(SUM(amount), 0) AS CHAR) FROM outbound_advance_allocation GROUP BY status
                UNION ALL SELECT 'partner_account_action.amount.total', CAST(COALESCE(SUM(amount), 0) AS CHAR) FROM partner_account_action
                UNION ALL SELECT CONCAT('partner_fund_adjustment.amount.', LOWER(adjustment_type), '.', LOWER(direction), '.', LOWER(status)), CAST(COALESCE(SUM(amount), 0) AS CHAR) FROM partner_fund_adjustment GROUP BY adjustment_type,direction,status
                UNION ALL SELECT 'partner_account_log.amount.total', CAST(COALESCE(SUM(amount), 0) AS CHAR) FROM partner_account_log
                UNION ALL SELECT CONCAT('receivable_adjustment.amount.', LOWER(adjustment_type), '.', LOWER(status)), CAST(COALESCE(SUM(amount), 0) AS CHAR) FROM receivable_adjustment GROUP BY adjustment_type, status
                UNION ALL SELECT CONCAT('sales_order.receivable_amount.', LOWER(payment_status)), CAST(COALESCE(SUM(receivable_amount), 0) AS CHAR) FROM sales_order GROUP BY payment_status
                UNION ALL SELECT CONCAT('sales_order.received_amount.', LOWER(payment_status)), CAST(COALESCE(SUM(received_amount), 0) AS CHAR) FROM sales_order GROUP BY payment_status
                UNION ALL SELECT CONCAT('purchase_order.total_amount.', LOWER(payment_status)), CAST(COALESCE(SUM(total_amount), 0) AS CHAR) FROM purchase_order GROUP BY payment_status
                UNION ALL SELECT CONCAT('purchase_order.paid_amount.', LOWER(payment_status)), CAST(COALESCE(SUM(paid_amount), 0) AS CHAR) FROM purchase_order GROUP BY payment_status
                UNION ALL SELECT CONCAT('outbound_order.receivable_amount.', LOWER(settlement_status)), CAST(COALESCE(SUM(receivable_amount), 0) AS CHAR) FROM outbound_order GROUP BY settlement_status
                UNION ALL SELECT CONCAT('outbound_order.paid_amount.', LOWER(settlement_status)), CAST(COALESCE(SUM(paid_amount), 0) AS CHAR) FROM outbound_order GROUP BY settlement_status
                UNION ALL SELECT CONCAT('inventory_transfer.total_quantity.', LOWER(status)), CAST(COALESCE(SUM(total_quantity), 0) AS CHAR) FROM inventory_transfer_order GROUP BY status
                UNION ALL SELECT CONCAT('inventory_transfer.total_cost.', LOWER(status)), CAST(COALESCE(SUM(total_cost), 0) AS CHAR) FROM inventory_transfer_order GROUP BY status
                UNION ALL SELECT CONCAT('inventory_stocktake.gain_quantity.', LOWER(status)), CAST(COALESCE(SUM(total_gain_quantity), 0) AS CHAR) FROM inventory_stocktake_order GROUP BY status
                UNION ALL SELECT CONCAT('inventory_stocktake.loss_quantity.', LOWER(status)), CAST(COALESCE(SUM(total_loss_quantity), 0) AS CHAR) FROM inventory_stocktake_order GROUP BY status
                UNION ALL SELECT CONCAT('inventory_stocktake.cost_difference.', LOWER(status)), CAST(COALESCE(SUM(total_cost_difference), 0) AS CHAR) FROM inventory_stocktake_order GROUP BY status
                UNION ALL SELECT CONCAT('purchase_return.total_quantity.', LOWER(status)), CAST(COALESCE(SUM(total_quantity), 0) AS CHAR) FROM purchase_return_order GROUP BY status
                UNION ALL SELECT CONCAT('purchase_return.total_amount.', LOWER(status)), CAST(COALESCE(SUM(total_amount), 0) AS CHAR) FROM purchase_return_order GROUP BY status
                UNION ALL SELECT CONCAT('purchase_return.payable_reduction.', LOWER(status)), CAST(COALESCE(SUM(payable_reduction_amount), 0) AS CHAR) FROM purchase_return_order GROUP BY status
                UNION ALL SELECT CONCAT('purchase_return.supplier_credit.', LOWER(status)), CAST(COALESCE(SUM(supplier_credit_amount), 0) AS CHAR) FROM purchase_return_order GROUP BY status
                UNION ALL SELECT CONCAT('inventory_cost_adjustment.difference_amount.', LOWER(status)), CAST(COALESCE(SUM(total_difference_amount), 0) AS CHAR) FROM inventory_cost_adjustment GROUP BY status
                UNION ALL SELECT CONCAT('landed_cost.fee_amount.', LOWER(status)), CAST(COALESCE(SUM(fee_amount), 0) AS CHAR) FROM landed_cost_allocation GROUP BY status
                UNION ALL SELECT CONCAT('landed_cost.capitalized_amount.', LOWER(status)), CAST(COALESCE(SUM(capitalized_amount), 0) AS CHAR) FROM landed_cost_allocation GROUP BY status
                UNION ALL SELECT CONCAT('landed_cost.expensed_amount.', LOWER(status)), CAST(COALESCE(SUM(expensed_amount), 0) AS CHAR) FROM landed_cost_allocation GROUP BY status
                UNION ALL SELECT CONCAT('business_posting_batch.count.', LOWER(status)), CAST(COUNT(*) AS CHAR) FROM business_posting_batch GROUP BY status
                UNION ALL SELECT CONCAT('business_posting_entry.quantity.', LOWER(fact_type), '.', LOWER(direction)), CAST(COALESCE(SUM(quantity), 0) AS CHAR) FROM business_posting_entry GROUP BY fact_type,direction
                UNION ALL SELECT CONCAT('business_posting_entry.amount.', LOWER(fact_type), '.', LOWER(direction)), CAST(COALESCE(SUM(amount), 0) AS CHAR) FROM business_posting_entry GROUP BY fact_type,direction
                UNION ALL SELECT CONCAT('accounting_voucher.debit.', LOWER(status)), CAST(COALESCE(SUM(debit_total), 0) AS CHAR) FROM accounting_voucher GROUP BY status
                UNION ALL SELECT CONCAT('accounting_voucher.credit.', LOWER(status)), CAST(COALESCE(SUM(credit_total), 0) AS CHAR) FROM accounting_voucher GROUP BY status
                UNION ALL SELECT 'accounting_ledger.debit.total', CAST(COALESCE(SUM(debit_amount), 0) AS CHAR) FROM accounting_ledger_entry
                UNION ALL SELECT 'accounting_ledger.credit.total', CAST(COALESCE(SUM(credit_amount), 0) AS CHAR) FROM accounting_ledger_entry
                UNION ALL SELECT CONCAT('accounting_generation_issue.count.', LOWER(status)), CAST(COUNT(*) AS CHAR) FROM accounting_generation_issue GROUP BY status
                UNION ALL SELECT CONCAT('tax_invoice.amount_excluding_tax.', LOWER(direction), '.', LOWER(invoice_color), '.', LOWER(status)), CAST(COALESCE(SUM(amount_excluding_tax), 0) AS CHAR) FROM tax_invoice GROUP BY direction,invoice_color,status
                UNION ALL SELECT CONCAT('tax_invoice.tax_amount.', LOWER(direction), '.', LOWER(invoice_color), '.', LOWER(status)), CAST(COALESCE(SUM(tax_amount), 0) AS CHAR) FROM tax_invoice GROUP BY direction,invoice_color,status
                UNION ALL SELECT CONCAT('tax_invoice.total_amount.', LOWER(direction), '.', LOWER(invoice_color), '.', LOWER(status)), CAST(COALESCE(SUM(total_amount), 0) AS CHAR) FROM tax_invoice GROUP BY direction,invoice_color,status
                UNION ALL SELECT CONCAT('tax_invoice_relation.total_amount.', LOWER(relation_layer), '.', LOWER(status)), CAST(COALESCE(SUM(total_amount), 0) AS CHAR) FROM tax_invoice_relation GROUP BY relation_layer,status
                UNION ALL SELECT CONCAT('bank_account.opening_balance.', LOWER(status)), CAST(COALESCE(SUM(opening_balance), 0) AS CHAR) FROM business_bank_account GROUP BY status
                UNION ALL SELECT CONCAT('bank_reconciliation.difference.', LOWER(status)), CAST(COALESCE(SUM(difference), 0) AS CHAR) FROM bank_reconciliation_session GROUP BY status
                UNION ALL SELECT CONCAT('bank_statement.amount.', LOWER(direction), '.', LOWER(status)), CAST(COALESCE(SUM(amount), 0) AS CHAR) FROM bank_statement_line GROUP BY direction,status
                UNION ALL SELECT CONCAT('bank_match.amount.', LOWER(status)), CAST(COALESCE(SUM(matched_amount), 0) AS CHAR) FROM bank_reconciliation_match GROUP BY status
                UNION ALL SELECT CONCAT('bank_difference.amount.', LOWER(status)), CAST(COALESCE(SUM(amount), 0) AS CHAR) FROM bank_reconciliation_difference GROUP BY status
                UNION ALL SELECT CONCAT('bank_pending.amount.', LOWER(status)), CAST(COALESCE(SUM(amount), 0) AS CHAR) FROM bank_reconciliation_pending_system GROUP BY status
                UNION ALL SELECT CONCAT('period_close.assets.', LOWER(status)), CAST(COALESCE(SUM(balance_sheet_assets), 0) AS CHAR) FROM accounting_period_close_run GROUP BY status
                UNION ALL SELECT CONCAT('period_close.liabilities_equity.', LOWER(status)), CAST(COALESCE(SUM(balance_sheet_liabilities_equity), 0) AS CHAR) FROM accounting_period_close_run GROUP BY status
                UNION ALL SELECT CONCAT('period_close.net_profit.', LOWER(status)), CAST(COALESCE(SUM(income_net_profit), 0) AS CHAR) FROM accounting_period_close_run GROUP BY status
                UNION ALL SELECT CONCAT('period_close.cash_net_increase.', LOWER(status)), CAST(COALESCE(SUM(cash_net_increase), 0) AS CHAR) FROM accounting_period_close_run GROUP BY status
                UNION ALL SELECT CONCAT('period_close.trial_difference.', LOWER(status)), CAST(COALESCE(SUM(trial_difference), 0) AS CHAR) FROM accounting_period_close_run GROUP BY status
                UNION ALL SELECT CONCAT('period_close_check.issue_count.', LOWER(status)), CAST(COALESCE(SUM(issue_count), 0) AS CHAR) FROM accounting_period_close_check GROUP BY status
                UNION ALL SELECT CONCAT('period_close_check.amount_difference.', LOWER(status)), CAST(COALESCE(SUM(amount_difference), 0) AS CHAR) FROM accounting_period_close_check GROUP BY status
                UNION ALL SELECT CONCAT('crm_lead.estimated_amount.', LOWER(status)), CAST(COALESCE(SUM(estimated_amount), 0) AS CHAR) FROM crm_lead GROUP BY status
                UNION ALL SELECT CONCAT('crm_customer_profile.annual_potential.', LOWER(lifecycle_stage), '.', LOWER(status)), CAST(COALESCE(SUM(annual_potential), 0) AS CHAR) FROM crm_customer_profile GROUP BY lifecycle_stage,status
                UNION ALL SELECT CONCAT('crm_opportunity.estimated_amount.', LOWER(stage)), CAST(COALESCE(SUM(estimated_amount), 0) AS CHAR) FROM crm_opportunity GROUP BY stage
                UNION ALL SELECT CONCAT('crm_quote.total_amount.', LOWER(status)), CAST(COALESCE(SUM(total_amount), 0) AS CHAR) FROM crm_quote GROUP BY status
                UNION ALL SELECT CONCAT('crm_quote.tax_amount.', LOWER(status)), CAST(COALESCE(SUM(tax_amount), 0) AS CHAR) FROM crm_quote GROUP BY status
                UNION ALL SELECT 'crm_quote_item.total_amount.total', CAST(COALESCE(SUM(total_amount), 0) AS CHAR) FROM crm_quote_item
                UNION ALL SELECT CONCAT('crm_followup_plan.count.', LOWER(status)), CAST(COUNT(*) AS CHAR) FROM crm_followup_plan GROUP BY status
                UNION ALL SELECT CONCAT('crm_activity.count.', LOWER(activity_type)), CAST(COUNT(*) AS CHAR) FROM crm_activity GROUP BY activity_type
                UNION ALL SELECT CONCAT('electronic_credential.count.', LOWER(credential_type), '.', LOWER(status)), CAST(COUNT(*) AS CHAR) FROM electronic_credential GROUP BY credential_type,status
                UNION ALL SELECT CONCAT('tax_workpaper.estimated_vat_payable.', LOWER(status)), CAST(COALESCE(SUM(estimated_vat_payable), 0) AS CHAR) FROM tax_workpaper GROUP BY status
                UNION ALL SELECT CONCAT('data_migration.total_rows.', LOWER(status)), CAST(COALESCE(SUM(total_rows), 0) AS CHAR) FROM data_migration_batch GROUP BY status
                UNION ALL SELECT CONCAT('data_migration.valid_rows.', LOWER(status)), CAST(COALESCE(SUM(valid_rows), 0) AS CHAR) FROM data_migration_batch GROUP BY status
                UNION ALL SELECT CONCAT('data_migration.error_rows.', LOWER(status)), CAST(COALESCE(SUM(error_rows), 0) AS CHAR) FROM data_migration_batch GROUP BY status
                UNION ALL SELECT CONCAT('data_migration.applied_rows.', LOWER(status)), CAST(COALESCE(SUM(applied_rows), 0) AS CHAR) FROM data_migration_batch GROUP BY status
                UNION ALL SELECT CONCAT('data_migration_fund.opening_balance.', LOWER(fund_type), '.', LOWER(status)), CAST(COALESCE(SUM(opening_balance), 0) AS CHAR) FROM data_migration_fund_opening GROUP BY fund_type,status
                UNION ALL SELECT CONCAT('data_migration_check.issue_count.', LOWER(status)), CAST(COALESCE(SUM(issue_count), 0) AS CHAR) FROM data_migration_check_run GROUP BY status
                ORDER BY metric
                """;
        Path target = work.resolve("schema/business-totals.csv");
        createParent(target);
        try (Writer writer = csvWriter(target)) {
            writeCsvLine(writer, List.of("metric", "decimal_value"));
            jdbc.query(sql, (RowCallbackHandler) rs -> {
                try {
                    writeCsvLine(writer, List.of(rs.getString(1), rs.getString(2)));
                } catch (IOException ex) {
                    throw new UncheckedIOException(ex);
                }
            });
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private long exportAttachments(Path work) {
        String sql = """
                SELECT 'inbound_attachment' source_table, 'INBOUND' business_type, 'DELIVERY_PROOF' evidence_type,
                       'ACTIVE' evidence_status, a.id attachment_id, a.inbound_id parent_id, a.inbound_id business_id,
                       a.file_name, a.content_type, a.file_size, a.sha256, a.storage_path, a.file_content,
                       a.uploaded_at source_time
                FROM inbound_attachment a
                UNION ALL
                SELECT 'inbound_review_snapshot_attachment', 'INBOUND_REVIEW_SNAPSHOT', 'REVIEW_SNAPSHOT',
                       'SNAPSHOT', a.id, a.snapshot_id, s.inbound_id,
                       a.file_name, a.content_type, a.file_size, a.sha256, a.storage_path, a.file_content,
                       a.original_uploaded_at
                FROM inbound_review_snapshot_attachment a
                JOIN inbound_review_snapshot s ON s.id=a.snapshot_id
                UNION ALL
                SELECT 'business_evidence_attachment', a.document_type, a.evidence_type, a.status,
                       a.id, a.document_id, a.document_id, a.original_file_name, a.content_type,
                       a.file_size, a.sha256, a.storage_path, null, a.uploaded_at
                FROM business_evidence_attachment a
                UNION ALL
                SELECT 'electronic_credential', a.credential_type, 'ORIGINAL_DOCUMENT', a.status,
                       a.id, a.id, a.id, a.original_file_name, a.content_type,
                       a.file_size, a.source_file_sha256, a.storage_path, null, a.created_at
                FROM electronic_credential a
                UNION ALL
                SELECT 'electronic_credential_verification', c.credential_type, 'VERIFICATION_PROOF', a.verification_result,
                       a.id, a.credential_id, a.credential_id, a.proof_file_name, a.proof_content_type,
                       a.proof_file_size, a.proof_sha256, a.proof_storage_path, null, a.created_at
                FROM electronic_credential_verification a
                JOIN electronic_credential c ON c.id=a.credential_id
                UNION ALL
                SELECT 'tax_invoice_import_batch', 'TAX_INVOICE_IMPORT', 'IMPORT_SOURCE', a.status,
                       a.id, a.id, a.id, a.source_file_name, 'text/csv', a.source_file_size,
                       a.source_file_sha256, a.source_storage_path, null, a.imported_at
                FROM tax_invoice_import_batch a
                UNION ALL
                SELECT 'bank_statement_import_batch', 'BANK_STATEMENT_IMPORT', 'IMPORT_SOURCE', a.status,
                       a.id, a.id, a.id, a.source_file_name,
                       CASE WHEN UPPER(a.source_file_format)='XLSX'
                            THEN 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'
                            ELSE 'text/csv' END,
                       a.source_file_size, a.source_file_sha256, a.source_storage_path, null, a.created_at
                FROM bank_statement_import_batch a
                UNION ALL
                SELECT 'product_import_job', 'PRODUCT_IMPORT', 'IMPORT_SOURCE', a.status,
                       a.id, a.id, a.id, a.source_file_name,
                       CASE WHEN UPPER(a.source_file_format)='XLSX'
                            THEN 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'
                            ELSE 'text/csv' END,
                       a.source_file_size, a.source_file_sha256, a.source_storage_path, null, a.created_at
                FROM product_import_job a
                UNION ALL
                SELECT 'accounting_archive_batch', 'ACCOUNTING_ARCHIVE', 'ACCOUNTING_ARCHIVE', a.status,
                       a.id, a.id, a.id, a.file_name, 'application/zip', a.archive_bytes,
                       a.archive_sha256, a.storage_path, null, a.generated_at
                FROM accounting_archive_batch a
                UNION ALL
                SELECT 'document_intake_batch', a.document_type, 'INTAKE_SOURCE', a.status,
                       a.id, a.id, a.id, a.original_file_name, a.content_type, a.file_size,
                       a.sha256, a.storage_path, null, a.created_at
                FROM document_intake_batch a
                ORDER BY source_table, attachment_id
                """;
        Path manifest = work.resolve("attachments/manifest.csv");
        createParent(manifest);
        try (Writer writer = csvWriter(manifest)) {
            writeCsvLine(writer, List.of("source_table", "business_type", "business_id", "parent_id", "attachment_id",
                    "evidence_type", "evidence_status", "original_file_name", "content_type", "file_size",
                    "sha256", "source_time", "export_path"));
            Long count = jdbc.query(sql, rs -> {
                long rows = 0;
                while (rs.next()) {
                    String sourceTable = rs.getString("source_table");
                    long attachmentId = rs.getLong("attachment_id");
                    long parentId = rs.getLong("parent_id");
                    long businessId = rs.getLong("business_id");
                    String originalName = BusinessExportFormat.safeFileName(rs.getString("file_name"));
                    String businessType = rs.getString("business_type");
                    String directory = switch (sourceTable) {
                        case "inbound_attachment" -> "inbounds";
                        case "inbound_review_snapshot_attachment" -> "inbound-review-snapshots";
                        case "electronic_credential" -> "electronic-credentials/originals";
                        case "electronic_credential_verification" -> "electronic-credentials/verifications";
                        default -> "evidence/" + businessType.toLowerCase(Locale.ROOT).replace('_', '-');
                    };
                    String exportPath = "attachments/" + directory + "/" + businessId + "/"
                            + attachmentId + "--" + originalName;
                    byte[] content = readAttachment(rs.getString("storage_path"), rs.getBytes("file_content"));
                    long expectedSize = rs.getLong("file_size");
                    String expectedSha = rs.getString("sha256");
                    if (content.length != expectedSize || !sha256(content).equalsIgnoreCase(expectedSha)) {
                        throw new IllegalStateException("附件大小或 SHA-256 不一致：" + sourceTable + "/" + attachmentId);
                    }
                    Path target = work.resolve(exportPath).normalize();
                    if (!target.startsWith(work.resolve("attachments"))) throw new IllegalStateException("附件导出路径非法");
                    createParent(target);
                    try {
                        Files.write(target, content);
                        writeCsvLine(writer, List.of(sourceTable, businessType,
                                String.valueOf(businessId), String.valueOf(parentId), String.valueOf(attachmentId),
                                rs.getString("evidence_type"), rs.getString("evidence_status"), rs.getString("file_name"),
                                rs.getString("content_type"), String.valueOf(expectedSize), expectedSha,
                                BusinessExportFormat.value(rs.getObject("source_time"), zoneId), exportPath));
                    } catch (IOException ex) {
                        throw new UncheckedIOException(ex);
                    }
                    rows++;
                }
                return rows;
            });
            return count == null ? 0 : count;
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private byte[] readAttachment(String storagePath, byte[] legacyContent) {
        if (StringUtils.hasText(storagePath)) return fileStorage.read(storagePath);
        if (legacyContent != null) return legacyContent;
        throw new IllegalStateException("附件既没有存储路径也没有旧 BLOB 内容");
    }

    private void writeManifest(Path work, String exportId, Long requesterId, String requesterName,
                               OffsetDateTime startedAt, OffsetDateTime completedAt, Snapshot snapshot) throws IOException {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("formatVersion", BusinessExportCatalog.FORMAT_VERSION);
        manifest.put("exportId", exportId);
        manifest.put("scopeType", "FULL");
        manifest.put("filters", Map.of(
                "modules", "ALL", "businessDateFrom", "", "businessDateTo", "", "dataScope", "ALL"));
        manifest.put("timeZone", zoneId.getId());
        manifest.put("snapshotStartedAt", startedAt.toString());
        manifest.put("completedAt", completedAt.toString());
        manifest.put("requestedBy", Map.of("userId", requesterId, "name", requesterName));
        manifest.put("schemaVersion", schemaVersion());
        manifest.put("tableCount", snapshot.rowCounts().size());
        manifest.put("recordCount", snapshot.recordCount());
        manifest.put("attachmentCount", snapshot.attachmentCount());
        manifest.put("rowCounts", snapshot.rowCounts());
        manifest.put("decimalEncoding", "plain-string-no-scientific-notation");
        manifest.put("timestampEncoding", "ISO-8601-with-offset");
        manifest.put("checksumAlgorithm", "SHA-256");
        manifest.put("excludedSecrets", List.of(
                "system_user.password_hash", "system_user_session.*",
                "database passwords", "session secrets", "TLS private keys", "third-party credentials"
        ));
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(work.resolve("manifest.json").toFile(), manifest);
    }

    private void writeReadme(Path work, String exportId, OffsetDateTime startedAt, OffsetDateTime completedAt) throws IOException {
        String html = """
                <!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><title>Copper 完整业务导出</title>
                <style>body{font:16px/1.65 system-ui,sans-serif;max-width:900px;margin:40px auto;padding:0 24px;color:#222}code{background:#f5f5f5;padding:2px 5px}h1,h2{line-height:1.3}</style></head><body>
                <h1>Copper 完整业务导出</h1>
                <p>格式：<code>%s</code>；任务：<code>%s</code></p>
                <p>一致性读取开始：%s；完成：%s；时区：%s。</p>
                <h2>如何读取</h2><ul>
                <li>业务数据位于各目录的 UTF-8 CSV；<code>schema/fields.csv</code> 是字段字典。</li>
                <li>金额、数量、单价、税额和汇率使用普通十进制字符串，不使用科学计数法。</li>
                <li><code>attachments/manifest.csv</code> 将原始附件关联到业务记录；<code>checksums.sha256</code> 可校验全部文件。</li>
                <li><code>manifest.json</code> 明确本包是全部数据、表记录数、时区和安全排除项。</li></ul>
                <h2>安全说明</h2><p>CSV 保留原始文本。使用电子表格打开不可信文本前，应通过“导入数据”方式并关闭公式执行。
                密码哈希、登录会话、数据库密码和第三方密钥不属于业务数据，已明确排除。</p>
                <p>本包用于长期读取和迁出，不是 Copper 灾难恢复备份；灾难恢复请使用 <code>copper-backup/v1</code>。</p>
                </body></html>
                """.formatted(BusinessExportCatalog.FORMAT_VERSION, exportId, startedAt, completedAt, zoneId.getId());
        Files.writeString(work.resolve("README.html"), html, StandardCharsets.UTF_8);
    }

    private void writeChecksums(Path work) throws IOException {
        List<Path> files = regularFiles(work).stream()
                .filter(path -> !path.getFileName().toString().equals("checksums.sha256"))
                .sorted(Comparator.comparing(path -> relative(work, path))).toList();
        Path checksums = work.resolve("checksums.sha256");
        try (BufferedWriter writer = Files.newBufferedWriter(checksums, StandardCharsets.UTF_8)) {
            for (Path file : files) writer.write(sha256(file) + "  " + relative(work, file) + "\n");
        }
    }

    private void zipDirectory(Path source, Path target) throws IOException {
        deleteQuietly(target);
        try (OutputStream output = new BufferedOutputStream(Files.newOutputStream(target));
             ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            for (Path file : regularFiles(source).stream().sorted(Comparator.comparing(path -> relative(source, path))).toList()) {
                ZipEntry entry = new ZipEntry(relative(source, file));
                zip.putNextEntry(entry);
                Files.copy(file, zip);
                zip.closeEntry();
            }
        }
    }

    private void verifyArchive(Path archive) throws IOException {
        try (ZipFile zip = new ZipFile(archive.toFile(), StandardCharsets.UTF_8)) {
            ZipEntry checksumEntry = zip.getEntry("checksums.sha256");
            if (checksumEntry == null) throw new IllegalStateException("导出包缺少 checksums.sha256");
            try (BufferedReader reader = new BufferedReader(new java.io.InputStreamReader(zip.getInputStream(checksumEntry), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) continue;
                    int separator = line.indexOf("  ");
                    if (separator != 64) throw new IllegalStateException("导出包校验清单格式错误");
                    String expected = line.substring(0, separator);
                    String name = line.substring(separator + 2);
                    ZipEntry entry = zip.getEntry(name);
                    if (entry == null || entry.isDirectory()) throw new IllegalStateException("导出包文件缺失：" + name);
                    try (InputStream input = new BufferedInputStream(zip.getInputStream(entry))) {
                        if (!expected.equals(sha256(input))) throw new IllegalStateException("导出包文件校验失败：" + name);
                    }
                }
            }
        }
    }

    private String schemaVersion() {
        String version = jdbc.queryForObject(
                "SELECT version FROM flyway_schema_history WHERE success=1 ORDER BY installed_rank DESC LIMIT 1",
                String.class);
        if (version == null || version.isBlank()) {
            throw new IllegalStateException("无法确定当前数据库迁移版本，禁止生成无版本标识的导出包");
        }
        return version;
    }

    private Writer csvWriter(Path target) throws IOException {
        BufferedWriter writer = Files.newBufferedWriter(target, StandardCharsets.UTF_8);
        writer.write('\ufeff');
        return writer;
    }

    private void writeCsvLine(Writer writer, List<String> values) throws IOException {
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) writer.write(',');
            writer.write(BusinessExportFormat.csv(values.get(index)));
        }
        writer.write("\r\n");
    }

    private String quoteIdentifier(String value) {
        if (value == null || !value.matches("[a-z0-9_]+")) throw new IllegalArgumentException("非法数据库标识符");
        return "`" + value + "`";
    }

    private String tableExclusionReason(String table) {
        return switch (table) {
            case "system_user_session" -> "登录会话令牌属于运行秘密，不进入业务导出";
            case "flyway_schema_history" -> "数据库迁移内部状态不属于业务数据，版本单独写入 manifest";
            default -> "明确排除";
        };
    }

    private String columnExclusionReason(String table, String column, String tableReason) {
        if (!tableReason.isBlank()) return tableReason;
        if ("password_hash".equals(column)) return "密码哈希属于运行秘密，不进入业务导出";
        if ("file_content".equals(column)) return "附件原始字节已按稳定路径单独导出，CSV 不重复写入二进制";
        return SENSITIVE_EXCLUSION_REASONS.contains(column) ? "安全排除" : "明确排除";
    }

    private void initializeDirectories() {
        try {
            Files.createDirectories(workRoot);
            if (!Files.isDirectory(rootPath) || !Files.isWritable(rootPath)) {
                throw new IllegalStateException("业务导出目录不可写：" + rootPath);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("业务导出目录初始化失败：" + rootPath, ex);
        }
    }

    private void ensureFreeSpace() {
        try {
            FileStore store = Files.getFileStore(rootPath);
            if (store.getUsableSpace() < MIN_FREE_BYTES) {
                throw new BusinessException("可用磁盘空间低于 128 MiB，已阻止生成完整业务导出");
            }
        } catch (IOException ex) {
            throw new IllegalStateException("无法检查业务导出磁盘空间", ex);
        }
    }

    private void createParent(Path target) {
        try {
            Files.createDirectories(target.getParent());
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private List<Path> regularFiles(Path root) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(path -> Files.isRegularFile(path) && !Files.isSymbolicLink(path)).toList();
        }
    }

    private String relative(Path root, Path file) {
        return root.relativize(file).toString().replace(file.getFileSystem().getSeparator(), "/");
    }

    private void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(source, target);
        }
    }

    private String sha256(Path path) throws IOException {
        try (InputStream input = new BufferedInputStream(Files.newInputStream(path))) {
            return sha256(input);
        }
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    private String sha256(InputStream input) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (DigestInputStream digestInput = new DigestInputStream(input, digest)) {
                digestInput.transferTo(OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    private void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    private void deleteTreeQuietly(Path root) {
        try {
            deleteTree(root);
        } catch (IOException ignored) {
        }
    }

    private void deleteQuietly(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }

    private Long nullableLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private String number(Long value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String fallback(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }

    public record ArchiveResult(String fileName, String relativePath, long archiveBytes, String archiveSha256,
                                long tableCount, long recordCount, long attachmentCount) {}

    private record Snapshot(Map<String, Long> rowCounts, long recordCount, long attachmentCount) {}
    private record TableDefinition(String name, String comment, List<ColumnDefinition> columns) {}
    private record ColumnDefinition(String name, String dataType, String columnType, Long precision, Long scale,
                                    boolean nullable, boolean primaryKey, String comment, int ordinal, Reference reference) {}
    private record Reference(String table, String column) {}
    private record ColumnKey(String table, String column) {}
    private record EnumKey(String table, String column) {}
}
