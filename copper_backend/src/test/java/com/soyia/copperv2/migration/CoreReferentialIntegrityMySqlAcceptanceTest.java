package com.soyia.copperv2.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "COPPER_FK_E2E", matches = "true")
class CoreReferentialIntegrityMySqlAcceptanceTest {
    private static final int PRE_V67_FOREIGN_KEY_COUNT = 38;
    private static final int V67_FOREIGN_KEY_COUNT = 54;

    @Test
    void freshSchemaInstallsRestrictConstraintsAndRejectsInvalidWrites() throws Exception {
        String database = databaseName("fresh");
        createDatabase(database);
        try {
            String databaseUrl = databaseUrl(database);
            Flyway.configure()
                    .dataSource(databaseUrl, username(), password())
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();

            try (Connection connection = connect(databaseUrl)) {
                assertEquals(PRE_V67_FOREIGN_KEY_COUNT + V67_FOREIGN_KEY_COUNT,
                        scalar(connection, """
                                select count(*)
                                from information_schema.referential_constraints
                                where constraint_schema = database()
                                """));
                assertEquals(V67_FOREIGN_KEY_COUNT,
                        scalar(connection, """
                                select count(*)
                                from information_schema.referential_constraints
                                where constraint_schema = database()
                                  and constraint_name in (
                                    'fk_purchase_order_item_order',
                                    'fk_purchase_order_item_product',
                                    'fk_sales_order_item_order',
                                    'fk_sales_order_item_product',
                                    'fk_inbound_item_order',
                                    'fk_inbound_item_product',
                                    'fk_outbound_item_order',
                                    'fk_outbound_item_product',
                                    'fk_inventory_batch_product',
                                    'fk_inventory_batch_warehouse',
                                    'fk_stock_balance_product',
                                    'fk_stock_balance_warehouse',
                                    'fk_inventory_movement_product',
                                    'fk_inventory_movement_warehouse',
                                    'fk_inventory_movement_batch',
                                    'fk_inventory_transfer_item_order',
                                    'fk_inventory_transfer_item_batch',
                                    'fk_inventory_stocktake_item_order',
                                    'fk_inventory_stocktake_item_batch',
                                    'fk_purchase_return_item_order',
                                    'fk_purchase_return_item_inbound',
                                    'fk_purchase_return_item_batch',
                                    'fk_sales_return_item_order',
                                    'fk_sales_return_item_outbound',
                                    'fk_sales_return_item_batch',
                                    'fk_payment_allocation_payment',
                                    'fk_supplier_allocation_payment',
                                    'fk_accounting_voucher_line_voucher',
                                    'fk_accounting_voucher_line_account',
                                    'fk_accounting_ledger_voucher',
                                    'fk_accounting_ledger_voucher_line',
                                    'fk_accounting_ledger_account',
                                    'fk_tax_invoice_line_invoice',
                                    'fk_tax_invoice_line_rate',
                                    'fk_bank_reconciliation_match_session',
                                    'fk_bank_reconciliation_match_statement',
                                    'fk_inventory_non_trade_product',
                                    'fk_inventory_non_trade_warehouse',
                                    'fk_inventory_non_trade_batch',
                                    'fk_purchase_quote_order',
                                    'fk_purchase_quote_supplier',
                                    'fk_purchase_quote_item_option',
                                    'fk_purchase_quote_item_product',
                                    'fk_sales_cost_treasury_document',
                                    'fk_outbound_delivery_outbound',
                                    'fk_outbound_delivery_event_delivery',
                                    'fk_external_share_partner',
                                    'fk_inventory_package_usage_instance',
                                    'fk_inventory_package_usage_batch',
                                    'fk_inventory_package_usage_product',
                                    'fk_inventory_non_trade_item_document',
                                    'fk_inventory_non_trade_item_product',
                                    'fk_inventory_non_trade_item_warehouse',
                                    'fk_inventory_non_trade_item_batch'
                                  )
                                  and delete_rule = 'RESTRICT'
                                  and update_rule = 'RESTRICT'
                                """));

                long paymentId = insertPayment(connection);
                long allocationId = insertAllocation(connection, paymentId, "VALID");

                SQLException deleteFailure = assertThrows(SQLException.class, () -> {
                    try (PreparedStatement statement = connection.prepareStatement("delete from payment where id=?")) {
                        statement.setLong(1, paymentId);
                        statement.executeUpdate();
                    }
                });
                assertEquals(1451, deleteFailure.getErrorCode());

                SQLException orphanFailure = assertThrows(SQLException.class,
                        () -> insertAllocation(connection, Long.MAX_VALUE, "ORPHAN"));
                assertEquals(1452, orphanFailure.getErrorCode());

                SQLException v66OrphanFailure = assertThrows(SQLException.class,
                        () -> insertDeliveryEventOrphan(connection));
                assertEquals(1452, v66OrphanFailure.getErrorCode());

                try (PreparedStatement statement = connection.prepareStatement(
                        "delete from payment_allocation where id=?")) {
                    statement.setLong(1, allocationId);
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement("delete from payment where id=?")) {
                    statement.setLong(1, paymentId);
                    assertEquals(1, statement.executeUpdate());
                }
            }
        } finally {
            dropDatabase(database);
        }
    }

    @Test
    void legacyOrphanBlocksBeforeAnyV67ConstraintIsInstalled() throws Exception {
        String database = databaseName("legacy");
        createDatabase(database);
        try {
            String databaseUrl = databaseUrl(database);
            Flyway.configure()
                    .dataSource(databaseUrl, username(), password())
                    .locations("classpath:db/migration")
                    .target(MigrationVersion.fromVersion("66"))
                    .load()
                    .migrate();

            try (Connection connection = connect(databaseUrl)) {
                insertDeliveryEventOrphan(connection);
            }

            FlywayException failure = assertThrows(FlywayException.class, () -> Flyway.configure()
                    .dataSource(databaseUrl, username(), password())
                    .locations("classpath:db/migration")
                    .load()
                    .migrate());
            assertTrue(causeMessages(failure).contains(
                    "V67_ORPHAN_BLOCK:outbound_delivery_event.delivery_id:1"));

            try (Connection connection = connect(databaseUrl)) {
                assertEquals(PRE_V67_FOREIGN_KEY_COUNT,
                        scalar(connection, """
                                select count(*)
                                from information_schema.referential_constraints
                                where constraint_schema = database()
                                """));
                assertEquals(0L, scalar(connection, """
                        select count(*)
                        from information_schema.referential_constraints
                        where constraint_schema = database()
                          and constraint_name = 'fk_purchase_order_item_order'
                        """));
            }
        } finally {
            dropDatabase(database);
        }
    }

    @Test
    void populatedV66SchemaUpgradesWithoutReplacingBusinessFacts() throws Exception {
        String database = databaseName("upgrade");
        createDatabase(database);
        try {
            String url = databaseUrl(database);
            Flyway.configure().dataSource(url, username(), password())
                    .locations("classpath:db/migration").target(MigrationVersion.fromVersion("66"))
                    .load().migrate();
            long paymentId;
            try (Connection connection = connect(url)) {
                paymentId = insertPayment(connection);
                insertAllocation(connection, paymentId, "UPGRADE");
            }
            Flyway.configure().dataSource(url, username(), password())
                    .locations("classpath:db/migration").load().migrate();
            try (Connection connection = connect(url)) {
                assertEquals(1L, scalar(connection,
                        "select count(*) from payment_allocation where payment_id=" + paymentId));
                SQLException blocked = assertThrows(SQLException.class, () -> {
                    try (Statement statement = connection.createStatement()) {
                        statement.executeUpdate("delete from payment where id=" + paymentId);
                    }
                });
                assertEquals(1451, blocked.getErrorCode());
            }
        } finally {
            dropDatabase(database);
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"package_usage", "nontrade_item"})
    void recentPackageAndNonTradeOrphansBlockBeforeAnyConstraint(String fixture) throws Exception {
        String database = databaseName(fixture);
        createDatabase(database);
        try {
            String url = databaseUrl(database);
            Flyway.configure().dataSource(url, username(), password())
                    .locations("classpath:db/migration").target(MigrationVersion.fromVersion("66"))
                    .load().migrate();
            try (Connection connection = connect(url); Statement statement = connection.createStatement()) {
                if (fixture.equals("package_usage")) {
                    statement.executeUpdate("""
                            insert into inventory_package_instance_usage
                            (package_instance_id,batch_id,product_id,ref_type,ref_id,movement_type,
                             base_quantity,base_unit,quantity_before,quantity_after)
                            values (9223372036854775807,9223372036854775807,9223372036854775807,
                                    'NON_TRADE',1,'CONSUME',1,'kg',1,0)
                            """);
                } else {
                    statement.executeUpdate("""
                            insert into inventory_non_trade_item
                            (document_id,line_no,product_id,warehouse_id,batch_id,quantity,base_unit,
                             unit_cost,amount_cost,quantity_before,amount_before)
                            values (9223372036854775807,1,9223372036854775807,9223372036854775807,
                                    9223372036854775807,1,'kg',1,1,1,1)
                            """);
                }
            }
            FlywayException failure = assertThrows(FlywayException.class, () -> Flyway.configure()
                    .dataSource(url, username(), password()).locations("classpath:db/migration").load().migrate());
            String relation = fixture.equals("package_usage")
                    ? "inventory_package_instance_usage.package_instance_id"
                    : "inventory_non_trade_item.document_id";
            assertTrue(causeMessages(failure).contains("V67_ORPHAN_BLOCK:" + relation + ":1"));
            try (Connection connection = connect(url)) {
                assertEquals(PRE_V67_FOREIGN_KEY_COUNT, scalar(connection, """
                        select count(*) from information_schema.referential_constraints
                        where constraint_schema=database()
                        """));
            }
        } finally {
            dropDatabase(database);
        }
    }

    private long insertPayment(Connection connection) throws SQLException {
        String paymentNo = "FK-" + UUID.randomUUID().toString().replace("-", "");
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into payment(payment_no,customer_id,amount,received_date)
                values (?,1,10.00,?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, paymentNo);
            statement.setObject(2, LocalDate.of(2026, 7, 24));
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                assertTrue(keys.next());
                return keys.getLong(1);
            }
        }
    }

    private long insertAllocation(Connection connection, long paymentId, String suffix) throws SQLException {
        String allocationNo = "FK-" + suffix + "-" + UUID.randomUUID().toString().replace("-", "");
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into payment_allocation(
                  allocation_no,payment_id,customer_id,target_type,target_id,allocated_amount
                ) values (?, ?, 1, 'SALES_ORDER', 1, 1.00)
                """, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, allocationNo);
            statement.setLong(2, paymentId);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                assertTrue(keys.next());
                return keys.getLong(1);
            }
        }
    }

    private void insertDeliveryEventOrphan(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into outbound_delivery_event(delivery_id,action)
                values (?, 'INVALID_REFERENCE_TEST')
                """)) {
            statement.setLong(1, Long.MAX_VALUE);
            statement.executeUpdate();
        }
    }

    private long scalar(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getLong(1);
        }
    }

    private void createDatabase(String database) throws SQLException {
        try (Connection connection = connect(serverUrl());
             Statement statement = connection.createStatement()) {
            statement.execute("create database `" + database
                    + "` character set utf8mb4 collate utf8mb4_0900_ai_ci");
        }
    }

    private void dropDatabase(String database) throws SQLException {
        try (Connection connection = connect(serverUrl());
             Statement statement = connection.createStatement()) {
            statement.execute("drop database if exists `" + database + "`");
        }
    }

    private Connection connect(String url) throws SQLException {
        return DriverManager.getConnection(url, username(), password());
    }

    private String databaseUrl(String database) {
        String url = sourceUrl();
        int query = url.indexOf('?');
        String suffix = query >= 0 ? url.substring(query) : "";
        String withoutQuery = query >= 0 ? url.substring(0, query) : url;
        return withoutQuery.substring(0, withoutQuery.lastIndexOf('/') + 1) + database + suffix;
    }

    private String serverUrl() {
        String url = sourceUrl();
        int query = url.indexOf('?');
        String suffix = query >= 0 ? url.substring(query) : "";
        String withoutQuery = query >= 0 ? url.substring(0, query) : url;
        return withoutQuery.substring(0, withoutQuery.lastIndexOf('/') + 1) + suffix;
    }

    private String databaseName(String role) {
        return ("copper_fk_" + role + "_" + UUID.randomUUID().toString().replace("-", ""))
                .toLowerCase(Locale.ROOT);
    }

    private String sourceUrl() {
        return required("COPPER_FK_DB_URL");
    }

    private String username() {
        return required("COPPER_FK_DB_USERNAME");
    }

    private String password() {
        return System.getenv().getOrDefault("COPPER_FK_DB_PASSWORD", "");
    }

    private String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required");
        }
        return value;
    }

    private String causeMessages(Throwable failure) {
        StringBuilder messages = new StringBuilder();
        Throwable current = failure;
        while (current != null) {
            if (current.getMessage() != null) {
                messages.append(current.getMessage()).append('\n');
            }
            current = current.getCause();
        }
        return messages.toString();
    }
}
