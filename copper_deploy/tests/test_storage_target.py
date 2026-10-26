#!/usr/bin/env python3
from __future__ import annotations

import hashlib
import http.server
import json
import os
import pathlib
import subprocess
import sys
import tempfile
import threading
import unittest
import urllib.parse
import xml.etree.ElementTree as ET


ROOT = pathlib.Path(__file__).resolve().parents[1]
TOOL = ROOT / "scripts" / "storage-target.py"
ARCHIVE_NAME = "copper-test_20260714T000000Z_0123456789abcdef0123456789abcdef_manual.copperbak"


class ObjectStoreHandler(http.server.BaseHTTPRequestHandler):
    objects: dict[str, tuple[bytes, dict[str, str]]] = {}
    fail_deletes = False

    def log_message(self, *_args) -> None:
        return

    def object_path(self) -> str:
        return urllib.parse.unquote(urllib.parse.urlsplit(self.path).path)

    def do_PUT(self) -> None:
        path = self.object_path()
        copy_source = self.headers.get("x-amz-copy-source")
        if copy_source:
            source = urllib.parse.unquote(copy_source)
            if source not in self.objects:
                self.send_error(404)
                return
            content, metadata = self.objects[source]
        else:
            length = int(self.headers.get("content-length", "0"))
            content = self.rfile.read(length)
            metadata = {
                key.lower(): value
                for key, value in self.headers.items()
                if key.lower().startswith("x-amz-meta-")
            }
        self.objects[path] = (content, metadata)
        self.send_response(200)
        self.send_header("content-length", "0")
        self.end_headers()

    def do_HEAD(self) -> None:
        path = self.object_path()
        if path not in self.objects:
            self.send_error(404)
            return
        content, metadata = self.objects[path]
        self.send_response(200)
        self.send_header("content-length", str(len(content)))
        for key, value in metadata.items():
            self.send_header(key, value)
        self.end_headers()

    def do_GET(self) -> None:
        parsed = urllib.parse.urlsplit(self.path)
        query = urllib.parse.parse_qs(parsed.query)
        if query.get("list-type") == ["2"]:
            prefix = query.get("prefix", [""])[0]
            root = ET.Element("ListBucketResult")
            ET.SubElement(root, "IsTruncated").text = "false"
            for path, (content, _metadata) in sorted(self.objects.items()):
                key = path.removeprefix("/test-bucket/")
                if not key.startswith(prefix):
                    continue
                node = ET.SubElement(root, "Contents")
                ET.SubElement(node, "Key").text = key
                ET.SubElement(node, "Size").text = str(len(content))
            body = ET.tostring(root, encoding="utf-8", xml_declaration=True)
        else:
            path = self.object_path()
            if path not in self.objects:
                self.send_error(404)
                return
            body = self.objects[path][0]
        self.send_response(200)
        self.send_header("content-length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_DELETE(self) -> None:
        if self.fail_deletes:
            self.send_error(503)
            return
        self.objects.pop(self.object_path(), None)
        self.send_response(204)
        self.send_header("content-length", "0")
        self.end_headers()


class StorageTargetTest(unittest.TestCase):
    def setUp(self) -> None:
        ObjectStoreHandler.objects = {}
        ObjectStoreHandler.fail_deletes = False
        self.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), ObjectStoreHandler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.temporary = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.temporary.name)
        self.data = self.root / "data"
        self.local = self.root / "local"
        self.external = self.root / "external"
        self.nas = self.root / "nas"
        self.credentials = self.root / "credentials"
        self.credentials.mkdir()
        self.access = self.credentials / "access"
        self.secret = self.credentials / "secret"
        self.access.write_text("test-access", encoding="utf-8")
        self.secret.write_text("test-secret", encoding="utf-8")
        os.chmod(self.access, 0o600)
        os.chmod(self.secret, 0o600)
        self.env_file = self.root / "copper.env"
        self.env_file.write_text(
            "\n".join(
                [
                    "COPPER_SITE_ID=copper-storage-unit-test",
                    f"COPPER_VAR_DIR={self.data}",
                    "COPPER_BACKUP_TARGETS=local,external,nas,s3",
                    f"COPPER_BACKUP_DIR={self.local}",
                    f"COPPER_BACKUP_EXTERNAL_DIR={self.external}",
                    f"COPPER_BACKUP_NAS_DIR={self.nas}",
                    "COPPER_STORAGE_TEST_ALLOW_UNMOUNTED_EXTERNAL=1",
                    "COPPER_STORAGE_TEST_ALLOW_UNMOUNTED_NAS=1",
                    f"COPPER_BACKUP_S3_ENDPOINT=http://127.0.0.1:{self.server.server_port}",
                    "COPPER_BACKUP_S3_REGION=us-east-1",
                    "COPPER_BACKUP_S3_BUCKET=test-bucket",
                    "COPPER_BACKUP_S3_PREFIX=copper-test",
                    f"COPPER_BACKUP_S3_ACCESS_KEY_FILE={self.access}",
                    f"COPPER_BACKUP_S3_SECRET_KEY_FILE={self.secret}",
                ]
            )
            + "\n",
            encoding="utf-8",
        )
        self.data.mkdir()
        self.local.mkdir()
        self.external.mkdir()
        self.nas.mkdir()
        self.run_tool("enroll", "external", "--confirm", str(self.external))
        self.run_tool("enroll", "nas", "--confirm", str(self.nas))
        self.archive = self.local / ARCHIVE_NAME
        self.archive.write_bytes((b"copper-backup-v1\n" * 8192) + b"end")
        digest = hashlib.sha256(self.archive.read_bytes()).hexdigest()
        self.sidecar = self.local / f"{ARCHIVE_NAME}.sha256"
        self.sidecar.write_text(f"{digest}  {ARCHIVE_NAME}\n", encoding="ascii")
        self.digest = digest

    def tearDown(self) -> None:
        self.server.shutdown()
        self.thread.join(timeout=5)
        self.server.server_close()
        self.temporary.cleanup()

    def run_tool(
        self,
        *arguments: str,
        check: bool = True,
        extra_env: dict[str, str] | None = None,
    ) -> subprocess.CompletedProcess[str]:
        environment = os.environ.copy()
        environment["COPPER_TEST_MODE"] = "1"
        environment.update(extra_env or {})
        return subprocess.run(
            [sys.executable, str(TOOL), "--env-file", str(self.env_file), *map(str, arguments)],
            text=True,
            capture_output=True,
            check=check,
            env=environment,
        )

    def test_publish_verify_fetch_and_delete_all_targets(self) -> None:
        published = self.run_tool("publish", self.archive, self.sidecar)
        result = json.loads(published.stdout)
        self.assertEqual(["local", "external", "nas", "s3"], result["targets"])
        self.assertEqual(self.digest, result["sha256"])

        status = json.loads(self.run_tool("status").stdout)
        self.assertEqual(4, len(status["targets"]))
        self.assertEqual([1, 1, 1, 1], [item["backupCount"] for item in status["targets"]])
        s3 = next(item for item in status["targets"] if item["target"] == "s3")
        self.assertFalse(s3["quotaKnown"])
        self.assertIsNone(s3["availableBytes"])

        destination = self.root / "download"
        fetched = self.run_tool("--target", "s3", "fetch", ARCHIVE_NAME, destination)
        self.assertEqual(destination / ARCHIVE_NAME, pathlib.Path(fetched.stdout.strip()))
        self.assertEqual(self.archive.read_bytes(), (destination / ARCHIVE_NAME).read_bytes())

        self.run_tool("--target", "nas", "delete", ARCHIVE_NAME)
        listing = json.loads(self.run_tool("list", "--json").stdout)
        self.assertEqual([], listing["nas"])
        self.assertEqual(1, len(listing["local"]))
        self.assertEqual(1, len(listing["s3"]))

    def test_identity_marker_loss_or_target_swap_fails_closed(self) -> None:
        (self.external / ".copper-storage-identity.json").unlink()
        result = self.run_tool("--target", "external", "preflight", "0", check=False)
        self.assertNotEqual(0, result.returncode)
        self.assertRegex(result.stderr, r"身份标记缺失|换盘")
        enroll = self.run_tool(
            "enroll", "external", "--confirm", str(self.external), check=False
        )
        self.assertNotEqual(0, enroll.returncode)
        self.assertIn("禁止自动把替换介质登记为原目标", enroll.stderr)

    def test_publish_interruption_never_leaves_success_marker(self) -> None:
        result = self.run_tool(
            "publish",
            self.archive,
            self.sidecar,
            check=False,
            extra_env={"COPPER_STORAGE_TEST_FAIL_AT": "external-after-archive-copy"},
        )
        self.assertNotEqual(0, result.returncode)
        listing = json.loads(self.run_tool("list", "--json").stdout)
        self.assertEqual({"external": [], "local": [], "nas": [], "s3": []}, listing)
        self.assertFalse((self.external / f"{ARCHIVE_NAME}.success.json").exists())

    def test_local_target_reports_single_disk_risk(self) -> None:
        status = json.loads(self.run_tool("--target", "local", "status").stdout)["targets"][0]
        self.assertEqual("SAME_AS_PRIMARY_DATA", status["failureDomain"])
        self.assertEqual("WARNING", status["failureDomainRisk"])

    def test_preflight_rejects_insufficient_filesystem_space(self) -> None:
        result = self.run_tool("--target", "local", "preflight", str(2**63 - 1), check=False)
        self.assertNotEqual(0, result.returncode)
        self.assertRegex(result.stderr, r"空间不足|DANGER|CRITICAL")

    def test_rejects_world_readable_s3_secret(self) -> None:
        os.chmod(self.secret, 0o644)
        result = self.run_tool("--target", "s3", "preflight", "0", check=False)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("权限必须为 0600", result.stderr)

    def test_status_reports_unavailable_target_without_blocking_core_status(self) -> None:
        os.chmod(self.secret, 0o644)
        status = json.loads(self.run_tool("status").stdout)
        s3 = next(item for item in status["targets"] if item["target"] == "s3")
        self.assertFalse(s3["available"])
        self.assertEqual("UNKNOWN", s3["riskLevel"])
        local = next(item for item in status["targets"] if item["target"] == "local")
        self.assertTrue(local["available"])

    def test_s3_delete_reports_remote_failure(self) -> None:
        self.run_tool("publish", self.archive, self.sidecar)
        ObjectStoreHandler.fail_deletes = True
        result = self.run_tool("--target", "s3", "delete", ARCHIVE_NAME, check=False)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("S3 请求失败", result.stderr)


if __name__ == "__main__":
    unittest.main()
