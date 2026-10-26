import argparse
import importlib.util
import json
import pathlib
import stat
import subprocess
import tempfile
import unittest
from unittest import mock


ROOT = pathlib.Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location(
    "copper_cloudflare_remote_access",
    ROOT / "scripts" / "remote-access-cloudflare.py",
)
assert SPEC and SPEC.loader
CLOUDFLARE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CLOUDFLARE)
TOKEN = "eyJ" + ("A" * 120)


class CloudflareRemoteAccessTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.base = pathlib.Path(self.temporary.name)
        self.etc = self.base / "etc"
        self.var = self.base / "var"
        self.env_file = self.etc / "copper.env"
        self.token_path = self.etc / "remote-access" / "cloudflare" / "tunnel-token"
        self.source_token = self.base / "source-token"
        self.source_token.write_text(TOKEN + "\n", encoding="utf-8")
        self.write_env(
            {
                "COPPER_SITE_ID": "site-001",
                "COPPER_DOMAIN": "erp.customer.test",
                "COPPER_DEPLOY_MODE": "lan",
                "COPPER_TLS_MODE": "lan",
                "COPPER_ETC_DIR": str(self.etc),
                "COPPER_VAR_DIR": str(self.var),
                "COPPER_RESTORE_PROXY_PORT": "18080",
                "COPPER_REMOTE_ACCESS_PROVIDER": "none",
                "COPPER_REMOTE_ACCESS_ENABLED": "no",
                "COPPER_CLOUDFLARED_IMAGE": CLOUDFLARE.DEFAULT_IMAGE,
                "COPPER_CLOUDFLARE_HOSTNAME": "",
                "COPPER_CLOUDFLARE_TOKEN_FILE": str(self.token_path),
                "COPPER_CLOUDFLARE_METRICS_PORT": "20241",
            }
        )

    def write_env(self, values: dict[str, str]) -> None:
        self.env_file.parent.mkdir(parents=True, exist_ok=True)
        self.env_file.write_text(
            "\n".join(f"{key}={value}" for key, value in values.items()) + "\n",
            encoding="utf-8",
        )
        self.env_file.chmod(0o600)

    def configure_args(self, **changes: object) -> argparse.Namespace:
        values: dict[str, object] = {
            "hostname": None,
            "token_file": self.source_token,
            "metrics_port": 20241,
            "test_mode": True,
        }
        values.update(changes)
        return argparse.Namespace(**values)

    def configured_values(self, enabled: bool = False) -> dict[str, str]:
        values = CLOUDFLARE.parse_env(self.env_file)
        values.update(
            {
                "COPPER_REMOTE_ACCESS_PROVIDER": "cloudflare",
                "COPPER_REMOTE_ACCESS_ENABLED": "yes" if enabled else "no",
                "COPPER_CLOUDFLARE_HOSTNAME": values["COPPER_DOMAIN"],
            }
        )
        CLOUDFLARE.update_env(
            self.env_file,
            {
                "COPPER_REMOTE_ACCESS_PROVIDER": values["COPPER_REMOTE_ACCESS_PROVIDER"],
                "COPPER_REMOTE_ACCESS_ENABLED": values["COPPER_REMOTE_ACCESS_ENABLED"],
                "COPPER_CLOUDFLARE_HOSTNAME": values["COPPER_CLOUDFLARE_HOSTNAME"],
            },
        )
        self.token_path.parent.mkdir(parents=True, exist_ok=True)
        CLOUDFLARE.atomic_write(self.token_path, (TOKEN + "\n").encode("utf-8"))
        return CLOUDFLARE.parse_env(self.env_file)

    def test_configure_keeps_token_out_of_env_and_state(self) -> None:
        result = CLOUDFLARE.configure(
            self.configure_args(), ROOT, self.env_file
        )
        self.assertEqual(result, 0)
        values = CLOUDFLARE.parse_env(self.env_file)
        self.assertEqual(values["COPPER_REMOTE_ACCESS_PROVIDER"], "cloudflare")
        self.assertEqual(values["COPPER_REMOTE_ACCESS_ENABLED"], "no")
        self.assertEqual(values["COPPER_CLOUDFLARE_HOSTNAME"], values["COPPER_DOMAIN"])
        self.assertNotIn(TOKEN, self.env_file.read_text(encoding="utf-8"))
        self.assertEqual(stat.S_IMODE(self.token_path.stat().st_mode), 0o600)
        state = self.var / "remote-access" / "cloudflare-state.json"
        self.assertTrue(state.is_file())
        self.assertNotIn(TOKEN, state.read_text(encoding="utf-8"))
        self.assertEqual(json.loads(state.read_text())["status"], "CONFIGURED_DISABLED")

    def test_configure_requires_same_hostname_for_split_horizon_identity(self) -> None:
        with self.assertRaisesRegex(CLOUDFLARE.RemoteAccessError, "完全相同"):
            CLOUDFLARE.configure(
                self.configure_args(hostname="other.customer.test"),
                ROOT,
                self.env_file,
            )
        self.assertFalse(self.token_path.exists())

    def test_validate_rejects_mutable_image_and_reserved_metrics_port(self) -> None:
        values = self.configured_values()
        values["COPPER_CLOUDFLARED_IMAGE"] = "cloudflare/cloudflared:latest"
        with self.assertRaisesRegex(CLOUDFLARE.RemoteAccessError, "不可变摘要"):
            CLOUDFLARE.validate_configuration(values, True)
        values["COPPER_CLOUDFLARED_IMAGE"] = CLOUDFLARE.DEFAULT_IMAGE
        values["COPPER_CLOUDFLARE_METRICS_PORT"] = "8191"
        with self.assertRaisesRegex(CLOUDFLARE.RemoteAccessError, "保留端口"):
            CLOUDFLARE.validate_configuration(values, True)

    def test_token_path_cannot_escape_private_directory(self) -> None:
        values = self.configured_values()
        values["COPPER_CLOUDFLARE_TOKEN_FILE"] = str(
            self.etc / "remote-access" / "cloudflare" / ".." / ".." / "escaped-token"
        )
        with self.assertRaisesRegex(CLOUDFLARE.RemoteAccessError, "必须位于"):
            CLOUDFLARE.validate_configuration(values, True)

    def test_connector_health_alone_cannot_mark_remote_access_healthy(self) -> None:
        values = self.configured_values(enabled=True)
        with (
            mock.patch.object(CLOUDFLARE, "origin_check", return_value=(True, "origin ok")),
            mock.patch.object(
                CLOUDFLARE,
                "connector_check",
                return_value=(True, "connector ok", 4),
            ),
            mock.patch.object(
                CLOUDFLARE,
                "access_gate_check",
                return_value=(False, "anonymous request reached Copper"),
            ),
        ):
            report, exit_code = CLOUDFLARE.build_status(
                ROOT, self.env_file, values, True
            )
        self.assertEqual(exit_code, 1)
        self.assertEqual(report["overallLevel"], "DEGRADED")
        self.assertTrue(report["checks"]["connector"]["passed"])
        self.assertFalse(report["checks"]["accessGate"]["passed"])

    def test_access_gate_requires_cloudflare_access_redirect(self) -> None:
        class FakeConnection:
            def __init__(self, response: bytes) -> None:
                self.response = response

            def __enter__(self) -> "FakeConnection":
                return self

            def __exit__(self, *_: object) -> None:
                return None

            def settimeout(self, _: int) -> None:
                return None

            def sendall(self, _: bytes) -> None:
                return None

            def recv(self, _: int) -> bytes:
                response, self.response = self.response, b""
                return response

        class FakeContext:
            def __init__(self, response: bytes) -> None:
                self.response = response

            def wrap_socket(self, _: object, server_hostname: str) -> FakeConnection:
                self.server_hostname = server_hostname
                return FakeConnection(self.response)

        redirect = (
            b"HTTP/1.1 302 Found\r\n"
            b"Location: https://customer.cloudflareaccess.com/cdn-cgi/access/login\r\n\r\n"
        )
        raw = FakeConnection(b"")
        with (
            mock.patch.object(CLOUDFLARE, "cloudflare_addresses", return_value=["203.0.113.10"]),
            mock.patch.object(CLOUDFLARE.socket, "create_connection", return_value=raw),
            mock.patch.object(CLOUDFLARE.ssl, "create_default_context", return_value=FakeContext(redirect)),
        ):
            passed, detail = CLOUDFLARE.access_gate_check("erp.customer.test")
        self.assertTrue(passed)
        self.assertIn("Cloudflare Access", detail)

        direct = b"HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n\r\nCopper"
        with (
            mock.patch.object(CLOUDFLARE, "cloudflare_addresses", return_value=["203.0.113.10"]),
            mock.patch.object(CLOUDFLARE.socket, "create_connection", return_value=raw),
            mock.patch.object(CLOUDFLARE.ssl, "create_default_context", return_value=FakeContext(direct)),
        ):
            passed, detail = CLOUDFLARE.access_gate_check("erp.customer.test")
        self.assertFalse(passed)
        self.assertIn("没有进入", detail)

    def test_enable_failure_is_fail_closed(self) -> None:
        values = self.configured_values()
        success = subprocess.CompletedProcess([], 0, "", "")
        args = argparse.Namespace(test_mode=True, wait_timeout=1)
        with (
            mock.patch.object(CLOUDFLARE, "run", return_value=success),
            mock.patch.object(
                CLOUDFLARE,
                "wait_until",
                side_effect=[
                    "origin ok",
                    "connector ok",
                    CLOUDFLARE.RemoteAccessError("Access gate missing"),
                ],
            ),
            mock.patch.object(CLOUDFLARE, "stop_connector") as stopped,
        ):
            with self.assertRaisesRegex(CLOUDFLARE.RemoteAccessError, "Access gate missing"):
                CLOUDFLARE.enable(args, ROOT, self.env_file)
        stopped.assert_called_once()
        updated = CLOUDFLARE.parse_env(self.env_file)
        self.assertEqual(updated["COPPER_REMOTE_ACCESS_ENABLED"], "no")
        state = json.loads(
            (self.var / "remote-access" / "cloudflare-state.json").read_text()
        )
        self.assertEqual(state["status"], "ENABLE_FAILED_CLOSED")
        self.assertNotIn(TOKEN, json.dumps(state))

    def test_disabled_token_rotation_is_atomic_and_secret_free(self) -> None:
        self.configured_values()
        new_token = "eyJ" + ("B" * 120)
        source = self.base / "new-token"
        source.write_text(new_token, encoding="utf-8")
        args = argparse.Namespace(test_mode=True, token_file=source, wait_timeout=1)
        self.assertEqual(
            CLOUDFLARE.rotate_token(args, ROOT, self.env_file),
            0,
        )
        self.assertEqual(self.token_path.read_text().strip(), new_token)
        state_text = (
            self.var / "remote-access" / "cloudflare-state.json"
        ).read_text(encoding="utf-8")
        self.assertNotIn(TOKEN, state_text)
        self.assertNotIn(new_token, state_text)

    def test_enabled_token_rotation_restores_old_token_on_validation_failure(self) -> None:
        self.configured_values(enabled=True)
        new_token = "eyJ" + ("C" * 120)
        source = self.base / "new-token-enabled"
        source.write_text(new_token, encoding="utf-8")
        args = argparse.Namespace(test_mode=True, token_file=source, wait_timeout=1)
        success = subprocess.CompletedProcess([], 0, "", "")
        with (
            mock.patch.object(CLOUDFLARE, "run", return_value=success),
            mock.patch.object(
                CLOUDFLARE,
                "wait_until",
                side_effect=[
                    CLOUDFLARE.RemoteAccessError("new token rejected"),
                    "old connector restored",
                    "old access restored",
                ],
            ),
        ):
            with self.assertRaisesRegex(CLOUDFLARE.RemoteAccessError, "new token rejected"):
                CLOUDFLARE.rotate_token(args, ROOT, self.env_file)
        self.assertEqual(self.token_path.read_text().strip(), TOKEN)
        state = json.loads(
            (self.var / "remote-access" / "cloudflare-state.json").read_text()
        )
        self.assertEqual(state["status"], "ENABLED")
        self.assertIn("恢复旧 token", state["detail"])

    def test_disable_still_stops_connector_when_token_file_is_missing(self) -> None:
        self.configured_values(enabled=True)
        self.token_path.unlink()
        args = argparse.Namespace(test_mode=True)
        with mock.patch.object(CLOUDFLARE, "stop_connector") as stopped:
            self.assertEqual(CLOUDFLARE.disable(args, ROOT, self.env_file), 0)
        stopped.assert_called_once()
        self.assertEqual(
            CLOUDFLARE.parse_env(self.env_file)["COPPER_REMOTE_ACCESS_ENABLED"],
            "no",
        )

    def test_remove_destroys_only_cloudflare_local_secret_and_preserves_business_data(self) -> None:
        self.configured_values()
        business_data = self.var / "mysql" / "customer-data"
        business_data.parent.mkdir(parents=True, exist_ok=True)
        business_data.write_text("preserve", encoding="utf-8")
        args = argparse.Namespace(
            test_mode=True,
            confirm="site-001:REMOVE-CLOUDFLARE",
        )
        with mock.patch.object(CLOUDFLARE, "stop_connector"):
            self.assertEqual(CLOUDFLARE.remove(args, ROOT, self.env_file), 0)
        self.assertFalse(self.token_path.exists())
        self.assertEqual(business_data.read_text(), "preserve")
        values = CLOUDFLARE.parse_env(self.env_file)
        self.assertEqual(values["COPPER_REMOTE_ACCESS_PROVIDER"], "none")
        receipt = json.loads(
            (self.var / "remote-access" / "cloudflare-removal.json").read_text()
        )
        self.assertTrue(receipt["copperBusinessDataPreserved"])
        self.assertEqual(receipt["cloudCleanup"], "PENDING_CUSTOMER_ACTION")

    def test_remove_requires_site_scoped_confirmation(self) -> None:
        values = self.configured_values()
        args = argparse.Namespace(test_mode=True, confirm="REMOVE-CLOUDFLARE")
        with self.assertRaisesRegex(CLOUDFLARE.RemoteAccessError, "site-001"):
            CLOUDFLARE.remove(args, ROOT, self.env_file)
        self.assertTrue(self.token_path.exists())
        self.assertEqual(values["COPPER_REMOTE_ACCESS_PROVIDER"], "cloudflare")

    def test_backup_and_restore_allowlists_do_not_capture_cloudflare_credentials(self) -> None:
        for relative in (
            "scripts/backup-metadata.py",
            "scripts/backup-chain.py",
            "scripts/restore-metadata.py",
        ):
            content = (ROOT / relative).read_text(encoding="utf-8")
            self.assertNotIn("COPPER_CLOUDFLARE", content, relative)
            self.assertNotIn("cloudflare_tunnel_token", content, relative)


if __name__ == "__main__":
    unittest.main()
