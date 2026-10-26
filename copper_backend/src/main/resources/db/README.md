# Copper database V1 baseline

The historical development SQL files were removed before the first formal Copper database release.

The authoritative source for V1 is the accepted `copper_v2` database, not the former collection of manually ordered scripts. The V1 target follows the prefix-free table names used by the current backend code.

Before the prefix-free cutover on 2026-07-13, the development database used MySQL 8.0.42 and contained 87 `c_`-prefixed base tables. Its normalized schema-only dump had SHA-256:

`f4cec8d6f0c6cf8b3364c9e25c52dc8e937ce38a8acb7b6f731d334b390f7d94`

The complete pre-cutover backup is `copper_v2-before-remove-c-prefix-20260713-111900.sql`, SHA-256 `6a512ba611c979e30ca70a3119dd0d70c7b69a4b8076ded63b221578645eccc1`.

After the user stopped port 8191, all 87 tables were renamed atomically. The prefix-free database still contained 87 base tables, had zero broken foreign-key targets, and passed `mysqlcheck` for every table. The immediate post-cutover schema-only evidence had SHA-256:

`ccd5250750325f5b5c78b9a9f9a79281a3de13a450e44754f792e66e13639490`

`migration/V1__baseline.sql` was generated from an isolated prefix-free candidate database. It contains 87 tables and data from only 20 reviewed system seed tables: default organization and number sequences, units, product category/series/specification configuration, packaging templates, roles, permissions, and data scopes.

The candidate contains zero customer, supplier, product instance, order, inventory, receivable, payable, payment, user, session, or attachment rows. The V1 file SHA-256 is:

`0f32a11c8d0393602b382581501dbbbc3823ef8287945aecb3103872967fcf7c`

The live prefix-free database and the isolated V1 candidate have identical normalized DDL after removing only runtime `AUTO_INCREMENT` counters. Both normalized schema-only dumps have SHA-256:

`8c6b1700e50ebeb8c6c272dec1abca9a585a9a8bc5a12b66862195fdeeeb0f3e`

Flyway 11.20.3 was verified on the official MySQL 8.4 container by installing V1 into an empty isolated database. The result contained 87 application tables, one successful `flyway_schema_history` row, the reviewed reference seeds, and no business data. A second startup reported schema version 1 with no migration necessary and did not duplicate initialization rows.

Migration failure, recovery, readiness, and one-time administrator credential acceptance evidence is recorded in `docs/M1_DATABASE_V1_ACCEPTANCE.md`.

After the normalized DDL comparison passed, the existing prefix-free `copper_v2` database was explicitly enrolled as Flyway baseline version 1 without starting the business service. A subsequent verification start on agent port 8193 validated the baseline, reported the schema up to date with no migration necessary, and shut down cleanly. The formal 8191 service remained stopped throughout this cutover verification.

Flyway applies V1 only to an empty database by default. `COPPER_FLYWAY_BASELINE_ON_MIGRATE` remains false unless a separately verified existing prefix-free database is being enrolled; arbitrary non-empty databases must never be silently baselined.

After Copper 1.0.0 is released:

- V1 becomes immutable.
- New schema changes start at V2.
- Released migrations are append-only and must never be edited in place.
- A migration failure must keep the application out of readiness.

Tracking issue: <https://github.com/soyia/copper_backend/issues/2>
