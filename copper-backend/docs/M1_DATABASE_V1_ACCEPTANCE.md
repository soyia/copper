# M1 database V1 acceptance

Accepted on 2026-07-13 against the container runtime on `local-database`.

## Frozen runtime

- Database image: `mysql:8.4`
- Image digest: `sha256:c831a0f11348d402b43d77453e17d770be2eef356615a2823fe0f5a0d6c8b9af`
- Database runtime: MySQL Community Server 8.4.10, Linux arm64
- Server character set: `utf8mb4`
- Server collation: `utf8mb4_0900_ai_ci`
- Java runtime used for acceptance: Eclipse Temurin 17.0.19, Linux arm64
- Flyway: 11.20.3 (`flyway-core` and `flyway-mysql`)

Flyway's MySQL module remains an explicit dependency, as required by the [official MySQL integration documentation](https://documentation.red-gate.com/fd/mysql-277579322.html). The accepted Flyway artifacts are available from [Maven Central](https://repo1.maven.org/maven2/org/flywaydb/flyway-core/11.20.3/).

## Empty database and restart acceptance

The final application artifact was started against a newly created empty MySQL 8.4 database.

- Flyway applied exactly one migration, `V1__baseline.sql`.
- The result contained 87 application tables, zero `c_`-prefixed tables, and one successful V1 history row.
- Customer, product instance, order, inventory, payment, receivable, and payable row counts remained zero.
- The bootstrap process created one administrator and did not copy credentials into V1.
- `/api/actuator/health/readiness` returned `{"status":"UP"}`.
- A second start reported schema version 1 and `No migration necessary`.
- The V1 history, administrator, seven roles, twelve units, and delivered credential file were not duplicated or replaced.

## Migration failure and recovery acceptance

A synthetic, unreleased `V2__m1_recovery_probe.sql` containing invalid SQL was mounted alongside the immutable V1.

- V1 completed successfully and V2 was recorded as failed.
- The backend container exited with code 1.
- Port 8193 had no listener and the readiness endpoint was unreachable.
- No administrator or recovery-probe table was created after the failed migration.
- The startup failure report named V2 and printed a Chinese recovery action: keep the service stopped, do not edit released migrations, inspect `flyway_schema_history`, back up the database, then fix the unreleased migration or restore a backup.

The failed test database was then fully recreated, which is the safest recovery path for this empty acceptance database. With a corrected, still-unreleased V2, Flyway applied V1 and V2, the probe table existed, the backend started, and readiness returned `UP`.

## One-time administrator credential lifecycle

When `system_user` is empty, Copper now generates a random 24-character temporary password and writes it to `COPPER_BOOTSTRAP_CREDENTIAL_FILE` rather than accepting a public or persistent default password.

- The delivery file is created with owner read/write permissions only (`0600` on the accepted Linux runtime).
- A restart before first login reuses the same file and does not silently replace the operator's delivered password.
- The temporary account is limited by `password_change_required`; non-auth business APIs remain blocked until the password is changed.
- A successful first password change deletes all temporary sessions, deletes the delivery file, clears the browser session cookie, and writes `CHANGE_PASSWORD` plus `BOOTSTRAP_CREDENTIAL_DESTROYED` audit events.
- The old temporary password is rejected after the change and the new password is accepted.
- If the delivery file cannot be deleted, password change fails so the operator can correct filesystem permissions and retry.
- Copper never writes the generated password to application logs or the database migration.

Deleting a file is the application-level destruction boundary. Physical secure erasure on SSD, snapshots, or backup media remains the responsibility of the customer's storage and backup platform.

## Operations contract

1. Mount a private, persistent secrets directory that is writable by the backend runtime user.
2. Set `COPPER_BOOTSTRAP_CREDENTIAL_FILE` to a file inside that directory.
3. After first startup, read the file through a trusted installation channel and store the temporary value in the operator's password manager.
4. Log in and change the password immediately. Expect the current session to be cleared and log in again with the new password.
5. Confirm the delivery file no longer exists and the destruction audit event is present.

No part of this flow uses `COPPER_BOOTSTRAP_PASSWORD`; that environment variable is no longer an initialization input.
