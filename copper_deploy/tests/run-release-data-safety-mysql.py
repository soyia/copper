#!/usr/bin/env python3
"""Exercise production attachment references and newest totals on isolated real MySQL.

The caller owns the server. This creates/drops only a randomly named acceptance
database, never starts a service, and never reads a production database.
"""
import argparse
import hashlib
import json
import pathlib
import secrets
import subprocess
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
ADDED = {
    "electronic_credential": ("storage_path", "file_size", "source_file_sha256"),
    "electronic_credential_verification": ("proof_storage_path", "proof_file_size", "proof_sha256"),
    "product_import_job": ("source_storage_path", "source_file_size", "source_file_sha256"),
    "document_intake_batch": ("storage_path", "file_size", "sha256"),
}
OLD = {
    "inbound_attachment": ("storage_path", "file_size", "sha256"),
    "inbound_review_snapshot_attachment": ("storage_path", "file_size", "sha256"),
    "business_evidence_attachment": ("storage_path", "file_size", "sha256"),
    "tax_invoice_import_batch": ("source_storage_path", "source_file_size", "source_file_sha256"),
    "bank_statement_import_batch": ("source_storage_path", "source_file_size", "source_file_sha256"),
    "accounting_archive_batch": ("storage_path", "archive_bytes", "archive_sha256"),
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mysql-command-json", required=True, help='JSON argv, e.g. ["/path/mysql","--socket=/tmp/owned.sock","-uroot"]')
    parser.add_argument("--evidence-output", type=pathlib.Path)
    args = parser.parse_args()
    prefix = json.loads(args.mysql_command_json)
    if not isinstance(prefix, list) or not prefix or any(not isinstance(v, str) or not v for v in prefix):
        parser.error("mysql command must be a nonempty argv array; put secrets in a defaults file")
    if any(v.split("=", 1)[0] == "--password" or v.startswith("-p") for v in prefix[1:]):
        parser.error("passwords in argv are forbidden; use an owned defaults file")
    database = "copper_release_accept_" + secrets.token_hex(6)
    checks = []

    def mysql(sql, db=None):
        result = subprocess.run([*prefix, "--batch", "--raw", "--skip-column-names", *([db] if db else [])], input=sql, text=True, capture_output=True)
        if result.returncode:
            raise RuntimeError("MySQL acceptance query failed: " + result.stderr)
        return result.stdout

    with tempfile.TemporaryDirectory(prefix="copper-release-data-safety-") as temporary:
        work = pathlib.Path(temporary)
        try:
            mysql(f"CREATE DATABASE `{database}` CHARACTER SET utf8mb4;")
            version = mysql("SELECT VERSION();", database).strip()
            if not version.startswith("8.4."):
                raise RuntimeError("real MySQL 8.4 is required")
            references = work / "refs.sql"
            totals = work / "totals.sql"
            subprocess.run(["bash", "-c", 'source "$1"; write_attachment_references_sql "$2"; write_business_totals_sql "$3"', "acceptance", str(ROOT / "scripts/verification-sql.sh"), str(references), str(totals)], check=True)
            originals = {}
            fixture_root = work / "files"
            for index, (table, columns) in enumerate({**OLD, **ADDED}.items(), 1):
                path_col, size_col, hash_col = columns
                content = ("synthetic-" + table).encode()
                digest = hashlib.sha256(content).hexdigest()
                relative = f"acceptance/{table}.txt"
                original = fixture_root / relative
                original.parent.mkdir(parents=True, exist_ok=True)
                original.write_bytes(content)
                originals[table] = (original, content)
                mysql(f"CREATE TABLE `{table}` (id BIGINT PRIMARY KEY, `{path_col}` VARCHAR(512), `{size_col}` BIGINT, `{hash_col}` CHAR(64)); INSERT INTO `{table}` VALUES ({index},'{relative}',{len(content)},'{digest}');", database)
            rows = mysql(references.read_text(), database)
            actual = {row.split("\t")[0] for row in rows.splitlines()}
            if actual != set(OLD) | set(ADDED):
                raise RuntimeError("production SQL omitted an attachment source")
            refs_tsv = work / "references.tsv"
            refs_tsv.write_text(rows)
            files_tsv = work / "files.tsv"
            output = work / "attachments.json"

            def capture_files():
                files_tsv.write_text("".join(
                    f"{path.relative_to(fixture_root)}\t{path.stat().st_size}\t{hashlib.sha256(path.read_bytes()).hexdigest()}\n"
                    for path in sorted(fixture_root.rglob("*")) if path.is_file()
                ))

            def metadata(expect_ok):
                output.unlink(missing_ok=True)
                result = subprocess.run([sys.executable, str(ROOT / "scripts/backup-metadata.py"), "attachments", str(files_tsv), str(refs_tsv), str(output)], text=True, capture_output=True)
                if (result.returncode == 0) != expect_ok:
                    raise RuntimeError("attachment validation returned an incorrect result")
                if not expect_ok and output.exists():
                    raise RuntimeError("failed attachment validation left successful metadata")
                return result

            capture_files()
            metadata(True)
            checks.append("ten_attachment_sources_and_metadata_pass")
            for table in ADDED:
                original, content = originals[table]
                original.unlink()
                capture_files()
                failure = metadata(False)
                if table not in failure.stderr:
                    raise RuntimeError("missing attachment failure lost source identity")
                checks.append(table + "_missing_rejected")
                original.write_bytes(b"x" * len(content))
                capture_files()
                metadata(False)
                checks.append(table + "_tampering_rejected")
                original.write_bytes(content)
            # Older schemas retain their original six-source contract.
            for table in ADDED:
                mysql(f"DROP TABLE `{table}`;", database)
            legacy = mysql(references.read_text(), database)
            if {row.split("\t")[0] for row in legacy.splitlines()} != set(OLD):
                raise RuntimeError("older schema attachment verification regressed")
            checks.append("older_schema_optional_sources_pass")
            mysql("""
CREATE TABLE inventory_package_instance (product_id BIGINT,status VARCHAR(32), remaining_base_quantity DECIMAL(24,6));
INSERT INTO inventory_package_instance VALUES (10,'AVAILABLE',7.125000),(10,'AVAILABLE',2.375000),(20,'AVAILABLE',1.000000);
CREATE TABLE inventory_package_instance_usage (product_id BIGINT,status VARCHAR(16), movement_type VARCHAR(32), base_quantity DECIMAL(24,6));
INSERT INTO inventory_package_instance_usage VALUES (10,'CONFIRMED','NON_TRADE_OUT',3.250000);
CREATE TABLE inventory_non_trade_item (product_id BIGINT,quantity DECIMAL(24,6), amount_cost DECIMAL(18,2));
INSERT INTO inventory_non_trade_item VALUES (10,1.250000,7.50),(20,2.000000,11.25);
CREATE TABLE inventory_non_trade_document (status VARCHAR(32),total_amount_cost DECIMAL(18,2),item_count INT);
INSERT INTO inventory_non_trade_document VALUES ('ISSUED_PENDING_SIGNATURE',18.75,2);
""", database)
            optional_sql = totals.read_text().split("SET @copper_optional_sql", 1)[1]
            values = dict(row.split("\t") for row in mysql("SET @copper_optional_sql" + optional_sql, database).splitlines())
            expected = {
                "inventory_package.remaining_base_quantity.10.available": "9.500000",
                "inventory_package.remaining_base_quantity.20.available": "1.000000",
                "inventory_package_usage.base_quantity.10.confirmed.non_trade_out": "3.250000",
                "inventory_non_trade_item.quantity.10": "1.250000",
                "inventory_non_trade_item.quantity.20": "2.000000",
                "inventory_non_trade_item.amount_cost.total": "18.75",
                "inventory_non_trade.total_amount_cost.issued_pending_signature": "18.75",
                "inventory_non_trade.item_count.issued_pending_signature": "2",
            }
            if values != expected:
                raise RuntimeError("latest package/nontrade quantity/cost totals do not match exact decimals")
            checks.append("package_remaining_multiitem_cost_pending_signature_totals_pass")
            report = {"format": "copper-release-data-safety/v1", "status": "PASS", "databaseVersion": version, "checks": checks, "customerDataUsed": False, "servicesStarted": False}
        finally:
            mysql(f"DROP DATABASE IF EXISTS `{database}`;")
    report["ownedDatabaseRemoved"] = True
    if args.evidence_output:
        args.evidence_output.write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report))


if __name__ == "__main__":
    main()
