package com.soyia.copperv2.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "COPPER_PACKAGING_REF_E2E", matches = "true")
class NonTradePackagingReferenceMigrationMySqlAcceptanceTest {
    @ParameterizedTest
    @ValueSource(strings = {"legacy", "item", "usage", "movement", "operation", "counter"})
    void legacyGapsAndEveryHistoricalReferenceReserveAnUnusedIdentity(String source) throws Exception {
        withDatabase(database -> {
            migrate(database, "64");
            try (Connection c = connect(database)) { seedLegacy(c); }
            migrate(database, "67");
            long minimum = 42;
            List<String> before;
            try (Connection c = connect(database)) {
                assertEquals(1L, scalar(c, "select id from inventory_non_trade_item"));
                assertEquals(41L, scalar(c, "select packaging_ref_id from inventory_non_trade_item"));
                switch (source) {
                    case "item" -> execute(c, "update inventory_non_trade_item set id=700");
                    case "usage" -> execute(c, "update inventory_package_instance_usage set ref_id=700");
                    case "movement" -> execute(c, "update inventory_package_movement set ref_id=700");
                    case "operation" -> execute(c, "update inventory_package_instance set operation_ref_type='INVENTORY_NON_TRADE',operation_ref_id=700");
                    case "counter" -> execute(c, "alter table inventory_non_trade_item auto_increment=900");
                    default -> { }
                }
                if (!source.equals("legacy")) minimum = source.equals("counter") ? 900 : 701;
                before = facts(c);
            }
            migrate(database, null);
            try (Connection c = connect(database)) {
                assertEquals(before, facts(c), "Migration must not rewrite existing mappings, usages or movements");
                assertTrue(next(c) >= minimum);
                long item = insertItem(c, 41, 2, 0);
                execute(c, "update inventory_non_trade_item set packaging_ref_id=" + item + " where id=" + item);
                assertTrue(item >= minimum);
                SQLException duplicate = assertThrows(SQLException.class, () -> insertItem(c, 41, 3, 41));
                assertEquals(1062, duplicate.getErrorCode());
            }
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"zero", "duplicate", "overflow", "counter_overflow"})
    void ambiguousOrExhaustedReferencesFailBeforeTheTableDDL(String scenario) throws Exception {
        withDatabase(database -> {
            migrate(database, "64");
            try (Connection c = connect(database)) { seedLegacy(c); }
            migrate(database, "67");
            long counter;
            List<String> before;
            try (Connection c = connect(database)) {
                switch (scenario) {
                    case "zero" -> execute(c, "update inventory_non_trade_item set packaging_ref_id=0");
                    case "duplicate" -> insertItem(c, 41, 2, 41);
                    case "overflow" -> execute(c, "update inventory_package_movement set ref_id=9223372036854775807");
                    case "counter_overflow" -> execute(c, "alter table inventory_non_trade_item auto_increment=9223372036854775808");
                }
                before = facts(c);
                counter = scenario.equals("counter_overflow") ? 0 : next(c);
            }
            FlywayException failure = assertThrows(FlywayException.class, () -> migrate(database, null));
            String code = switch (scenario) {
                case "zero" -> "V68_PACKAGING_REF_ZERO_BLOCK";
                case "duplicate" -> "V68_PACKAGING_REF_DUPLICATE_BLOCK";
                default -> "V68_PACKAGING_REF_JAVA_LONG_EXHAUSTED";
            };
            assertTrue(messages(failure).contains(code), messages(failure));
            try (Connection c = connect(database)) {
                assertEquals(before, facts(c));
                assertEquals(0L, scalar(c, "select count(*) from information_schema.statistics where table_schema=database() and table_name='inventory_non_trade_item' and index_name='uk_inventory_non_trade_packaging_ref'"));
                if (!scenario.equals("counter_overflow")) assertEquals(counter, next(c));
                else assertEquals("9223372036854775808", string(c, "select auto_increment from information_schema.tables where table_schema=database() and table_name='inventory_non_trade_item'"));
            }
        });
    }

    @Test void temporaryZeroSerializesConcurrentCreatorsAndDumpMetadataPreservesTheCounter() throws Exception {
        withDatabase(database -> {
            migrate(database, "64");
            try (Connection c = connect(database)) { seedLegacy(c); }
            migrate(database, null);
            CountDownLatch firstWritten = new CountDownLatch(1), release = new CountDownLatch(1);
            ExecutorService threads = Executors.newFixedThreadPool(2);
            try {
                Future<Long> first = threads.submit(() -> {
                    try (Connection c = connect(database)) {
                        c.setAutoCommit(false);
                        long id = insertItem(c, 41, 2, 0);
                        execute(c, "update inventory_non_trade_item set packaging_ref_id=" + id + " where id=" + id);
                        firstWritten.countDown();
                        if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("commit signal missing");
                        c.commit();
                        return id;
                    }
                });
                assertTrue(firstWritten.await(10, TimeUnit.SECONDS));
                Future<Long> second = threads.submit(() -> {
                    try (Connection c = connect(database)) {
                        c.setAutoCommit(false);
                        long id = insertItem(c, 41, 3, 0);
                        execute(c, "update inventory_non_trade_item set packaging_ref_id=" + id + " where id=" + id);
                        c.commit();
                        return id;
                    }
                });
                release.countDown();
                assertNotEquals(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
            } finally {
                release.countDown();
                threads.shutdownNow();
                assertTrue(threads.awaitTermination(10, TimeUnit.SECONDS));
            }
            // Round-trip the same CREATE TABLE + INSERT data used by a logical SQL dump.
            // A consumed, rolled-back ID makes the saved counter larger than MAX(id).
            try (Connection c = connect(database)) {
                execute(c, "alter table inventory_non_trade_item auto_increment=2000");
                String ddl;
                try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("show create table inventory_non_trade_item")) {
                    assertTrue(r.next()); ddl = r.getString(2);
                }
                assertTrue(ddl.contains("AUTO_INCREMENT=2000"));
                withDatabase(restored -> {
                    try (Connection d = connect(restored)) {
                        execute(d, "set foreign_key_checks=0");
                        execute(d, ddl);
                        execute(d, "create table inventory_batch like `" + database + "`.inventory_batch");
                        execute(d, "insert into inventory_non_trade_item select * from `" + database + "`.inventory_non_trade_item");
                        assertEquals(2000L, next(d));
                        long id = insertItem(d, 41, 4, 0);
                        execute(d, "update inventory_non_trade_item set packaging_ref_id=" + id + " where id=" + id);
                        assertEquals(2000L, id);
                        execute(d, "set foreign_key_checks=1");
                    }
                });
            }
        });
    }

    private void seedLegacy(Connection c) throws SQLException {
        execute(c, "insert into warehouse(id,name,location,status) values (900001,'PKREF-W','TEST','ENABLED')");
        execute(c, "insert into product(id,product_series_id,sku,name,spec,unit,status) values (900001,2001,'PKREF-P','PKREF','TEST','kg','ENABLED')");
        execute(c, "insert into inventory_batch(id,product_id,warehouse_id,batch_no,source_type,source_id,base_unit,quantity_in,quantity_available,unit_cost,amount_cost,received_at) values (900001,900001,900001,'PKREF-B','OPENING',0,'kg',20,19,10,190,'2026-07-22')");
        execute(c, "insert into inventory_non_trade_document(id,document_no,business_type,direction,business_date,product_id,warehouse_id,batch_id,quantity,base_unit,unit_cost,amount_cost,quantity_before,amount_before,reason,status) values (41,'PKREF-OLD','SAMPLE_OUT','OUT','2026-07-22',900001,900001,900001,1,'kg',10,10,20,200,'TEST','CONFIRMED')");
        execute(c, "insert into inventory_package_instance(id,batch_id,product_id,package_code,unit_name,actual_base_quantity,remaining_base_quantity,base_unit,status) values (900001,900001,900001,'PKREF-A','盘',10,9,'kg','AVAILABLE')");
        execute(c, "insert into inventory_package_instance_usage(package_instance_id,batch_id,product_id,ref_type,ref_id,movement_type,base_quantity,base_unit,quantity_before,quantity_after) values (900001,900001,900001,'INVENTORY_NON_TRADE',41,'PARTIAL_ACTUAL_OUT',1,'kg',10,9)");
        execute(c, "insert into inventory_package_movement(batch_id,product_id,movement_type,from_unit,from_quantity,ref_type,ref_id,memo) values (900001,900001,'NONTRADE_BASE','kg',1,'INVENTORY_NON_TRADE',41,'TEST')");
    }
    private long insertItem(Connection c, long document, int line, long ref) throws SQLException {
        // Each row needs a distinct batch under V65's unique(document,batch).
        long batch = 900001L + line;
        execute(c, "insert ignore into inventory_batch(id,product_id,warehouse_id,batch_no,source_type,source_id,base_unit,quantity_in,quantity_available,unit_cost,amount_cost,received_at) values (" + batch + ",900001,900001,'PKREF-B" + line + "','OPENING',0,'kg',20,20,10,200,'2026-07-22')");
        try (PreparedStatement s = c.prepareStatement("insert into inventory_non_trade_item(document_id,line_no,product_id,warehouse_id,batch_id,quantity,base_unit,unit_cost,amount_cost,quantity_before,amount_before,packaging_ref_id) values (?,?,900001,900001,?,1,'kg',10,10,20,200,?)", Statement.RETURN_GENERATED_KEYS)) {
            s.setLong(1, document); s.setInt(2, line); s.setLong(3, batch); s.setLong(4, ref); s.executeUpdate();
            try (ResultSet r = s.getGeneratedKeys()) { assertTrue(r.next()); return r.getLong(1); }
        }
    }
    private List<String> facts(Connection c) throws SQLException {
        List<String> result = new ArrayList<>();
        for (String sql : List.of(
                "select concat_ws('|',id,document_id,line_no,packaging_ref_id,quantity) from inventory_non_trade_item order by id",
                "select concat_ws('|',id,package_instance_id,ref_type,ref_id,base_quantity,status) from inventory_package_instance_usage order by id",
                "select concat_ws('|',id,batch_id,product_id,ref_type,ref_id,from_quantity,to_quantity) from inventory_package_movement order by id",
                "select concat_ws('|',id,remaining_base_quantity,operation_ref_type,operation_ref_id,status) from inventory_package_instance order by id")) {
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) { while (r.next()) result.add(r.getString(1)); }
        }
        return result;
    }
    private long next(Connection c) throws SQLException {
        execute(c, "set session information_schema_stats_expiry=0");
        return scalar(c, "select auto_increment from information_schema.tables where table_schema=database() and table_name='inventory_non_trade_item'");
    }
    private void migrate(String database, String target) {
        var config = Flyway.configure().dataSource(url(database), user(), password()).locations("classpath:db/migration");
        if (target != null) config.target(MigrationVersion.fromVersion(target));
        config.load().migrate();
    }
    private interface DatabaseWork { void run(String database) throws Exception; }
    private void withDatabase(DatabaseWork work) throws Exception {
        String database = "copper_pkref_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection c = connect(""); Statement s = c.createStatement()) { s.execute("create database `" + database + "` character set utf8mb4 collate utf8mb4_0900_ai_ci"); }
        try { work.run(database); }
        finally { try (Connection c = connect(""); Statement s = c.createStatement()) { s.execute("drop database if exists `" + database + "`"); } }
    }
    private Connection connect(String database) throws SQLException { return DriverManager.getConnection(url(database), user(), password()); }
    private String url(String database) {
        String source = required("COPPER_PACKAGING_REF_DB_URL");
        int query = source.indexOf('?');
        String suffix = query < 0 ? "" : source.substring(query);
        String plain = query < 0 ? source : source.substring(0, query);
        return plain.substring(0, plain.lastIndexOf('/') + 1) + database + suffix;
    }
    private String user() { return required("COPPER_PACKAGING_REF_DB_USERNAME"); }
    private String password() { return System.getenv().getOrDefault("COPPER_PACKAGING_REF_DB_PASSWORD", ""); }
    private String required(String name) { String v = System.getenv(name); if (v == null || v.isBlank()) throw new IllegalStateException(name + " is required"); return v; }
    private void execute(Connection c, String sql) throws SQLException { try (Statement s = c.createStatement()) { s.execute(sql); } }
    private long scalar(Connection c, String sql) throws SQLException { return Long.parseLong(string(c, sql)); }
    private String string(Connection c, String sql) throws SQLException { try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) { assertTrue(r.next()); return r.getString(1); } }
    private String messages(Throwable t) { StringBuilder s = new StringBuilder(); for (; t != null; t = t.getCause()) s.append(t.getMessage()).append('\n'); return s.toString(); }
}
