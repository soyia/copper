# M1 production image and security baseline

This document is the operating contract for the Copper Backend production image.

## Image baseline

- Builder: Maven 3.9.9 with Eclipse Temurin Java 17, pinned by multi-platform image digest.
- Runtime: Eclipse Temurin Java 17 JRE, pinned by multi-platform image digest.
- Runtime identity: non-login user `copper`, UID/GID `10001` by default.
- Persistent container path: `/app/data`.
- Writable subdirectories: `/app/data/files`, `/app/data/logs`, `/app/data/secrets`, and `/app/data/tmp`.
- The application JAR and runtime filesystem remain read-only to the application user except for the mounted data path.

The image declares `/app/data` as a volume, but production orchestration must still mount a named volume or customer-controlled host directory there. Recreating a container without reattaching the same persistent data path is an operator error, not an application backup strategy.

## Required production configuration

Start from `.env.example`. Every environment-specific value must be supplied by the deployment package; no production database, port, log, attachment, or cookie value has a weak fallback.

The `prod` profile rejects startup unless all of the following are true:

- MySQL uses a non-root application account.
- The database password is at least 16 characters and comes from `COPPER_DB_PASSWORD_FILE`.
- The password is not embedded in the JDBC URL or passed directly as `COPPER_DB_PASSWORD`.
- Port and IANA time zone are valid.
- Log level is `INFO`, `WARN`, or `ERROR`; production `DEBUG` and `TRACE` are rejected.
- The attachment, upload temporary, log, and bootstrap credential paths are absolute, writable, and below `COPPER_DATA_ROOT`.
- Allowed browser origins are explicit HTTPS origins without wildcards.
- The session cookie is `HttpOnly`, `Secure`, and `SameSite=Strict`.
- Flyway is enabled and automatic baseline of a non-empty database is disabled.

Startup errors use Chinese instructions and must be corrected before the container is considered ready.

## Secret delivery

Create the database password outside Git and outside the image. Mount it read-only into the backend container, for example at `/run/secrets/copper_db_password`, then set:

```text
COPPER_DB_PASSWORD_FILE=/run/secrets/copper_db_password
```

Do not also set `COPPER_DB_PASSWORD`. The application reads one line from the mounted file during environment preparation. The Actuator configuration exposes only health endpoints, so environment and configuration properties are not available through HTTP.

The mounted secret must be readable by runtime UID `10001` and must not be writable by other users. The accepted test used mode `0600` owned by UID `10001`.

The first administrator credential is different: Copper generates it after a successful database migration and writes it to the persistent `COPPER_BOOTSTRAP_CREDENTIAL_FILE` with owner-only permissions. Follow `docs/M1_DATABASE_V1_ACCEPTANCE.md` for delivery and destruction.

## Cookie and reverse proxy contract

Production cookies require HTTPS. The reverse proxy must terminate TLS and forward `/api` to the private backend network. `COPPER_ALLOWED_ORIGINS` must contain the exact public HTTPS origin used by the Web client. `COPPER_ALLOW_PRIVATE_NETWORK_ORIGINS` defaults to `false`; only a customer-controlled LAN deployment may enable it to accept RFC1918 IPv4 Web origins (`10/8`, `172.16/12`, `192.168/16`). Backend port 8191 must not be exposed directly to the public internet.

Changing from the old insecure cookie configuration invalidates the practical use of HTTP-only local login. Developers who run the JAR outside the production image must provide an explicit non-production profile and configuration; production settings must never be weakened to make local HTTP convenient.

## Logging contract

- JDBC statement binding logs are disabled.
- The default production level cannot be `DEBUG` or `TRACE`.
- Passwords and session tokens are never intentionally logged.
- Unhandled errors return a short tracking number instead of the exception message.
- Production error logs record the tracking number and exception type; stack traces are debug-only and therefore unavailable under the accepted production levels.
- `COPPER_LOG_FILE` must be under the persistent data root so container recreation does not discard operational logs.

## Health and recovery

- Liveness: `/api/actuator/health/liveness`
- Readiness: `/api/actuator/health/readiness`
- Health responses expose status only.
- The Docker health check targets liveness. Traffic routing must use readiness because readiness also includes database availability.
- A Flyway migration failure stops application startup and prevents readiness, as accepted in M1-05.

## Acceptance checklist

1. Build the Dockerfile twice from a clean context and confirm the pinned builder and runtime identities.
2. Inspect the image user and confirm it is not root.
3. Start with one required setting missing and confirm a Chinese startup failure.
4. Start with `root`, a weak/direct database password, insecure cookie, wildcard origin, or debug logging and confirm rejection.
5. Start with a mounted database secret and persistent `/app/data`, then confirm liveness, readiness, login, and the secure `Set-Cookie` attributes.
6. Write a probe file under the attachment directory, recreate the backend container with the same data mount, and confirm the file and application data remain.
7. Confirm the backend log contains no database password, session token, JDBC bind parameters, or test personal data.

## Accepted evidence on 2026-07-13

The image was built and tested only on `ec2-user@local-database`; no local development container runtime was used.

- Host architecture: Linux arm64; Docker Engine 29.5.3.
- Database: existing isolated `mysql:8.4` runtime, MySQL 8.4.10.
- Final image build runtime manifest: `sha256:ccc4e43742d48dd1ab1d626a1849f11bdc69b2a81750bbf225fe7dd6d5cda36b`.
- Final build and cached rebuild used the same runtime config: `sha256:e63441099deb545eb83eb5d897ccd3148a50a62e5cbd853b47d265b308c5e7bc`.
- The top-level BuildKit manifest-list digest differed between builds because the provenance attestation manifest was regenerated; application runtime layers, runtime manifest, and configuration remained identical.
- Image inspection confirmed user `copper:copper`, UID `10001`, persistent volume `/app/data`, production profile, Java 17.0.19, and the liveness health check.
- Missing configuration, a direct database password, MySQL root, `Secure=false`, and `DEBUG` logging each caused a non-zero startup exit with a Chinese recovery message.
- A valid mounted password file was absent from Docker inspect; readiness returned `UP`.
- Login returned `Secure; HttpOnly; SameSite=Strict`, and the authenticated current-user API succeeded.
- Recreating the backend container with the same data mount preserved an attachment probe and the one-time administrator delivery file.
- Docker health became `healthy`; Flyway V1 remained successful with 87 application tables.
- The persistent application log contained none of the generated database password, bootstrap password, session token, `StatementCreatorUtils`, or JDBC parameter-binding output.
- After acceptance, the temporary backend containers, image tags, test database, test database user, source directory, secret, and persistent test data were deleted. Existing `vm-mysql`, `vm-valkey`, and formal 8191 remained untouched.
