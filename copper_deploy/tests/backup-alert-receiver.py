#!/usr/bin/env python3
"""Loopback-only webhook receiver used by the M2-06 isolated runtime test."""

from __future__ import annotations

import argparse
import json
import os
import pathlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--ready-file", required=True)
    args = parser.parse_args()
    if not 1024 <= args.port <= 65535:
        raise SystemExit("test webhook port must be 1024-65535")
    output = pathlib.Path(args.output)
    ready = pathlib.Path(args.ready_file)
    if output.exists() or ready.exists():
        raise SystemExit("test webhook output paths must not already exist")

    class Handler(BaseHTTPRequestHandler):
        def do_POST(self) -> None:  # noqa: N802
            if self.path != "/copper-backup-alert":
                self.send_error(404)
                return
            length = int(self.headers.get("Content-Length", "0"))
            if length <= 0 or length > 64 * 1024:
                self.send_error(400)
                return
            raw = self.rfile.read(length)
            try:
                body = json.loads(raw)
            except json.JSONDecodeError:
                self.send_error(400)
                return
            record = {
                "body": body,
                "raw": raw.decode("utf-8"),
                "signature": self.headers.get("X-Copper-Signature", ""),
                "event": self.headers.get("X-Copper-Event", ""),
            }
            descriptor = os.open(output, os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o600)
            try:
                os.write(descriptor, (json.dumps(record, sort_keys=True, separators=(",", ":")) + "\n").encode("utf-8"))
                os.fsync(descriptor)
            finally:
                os.close(descriptor)
            self.send_response(204)
            self.end_headers()

        def log_message(self, _format: str, *_args: object) -> None:
            return

    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    ready.write_text("ready\n", encoding="ascii")
    ready.chmod(0o600)
    try:
        server.serve_forever()
    finally:
        ready.unlink(missing_ok=True)
        server.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
