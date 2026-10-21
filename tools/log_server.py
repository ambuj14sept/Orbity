#!/usr/bin/env python3
"""Orbity developer server, reachable from the phone over Tailscale.

  POST /log?device=phone|watch|crash   append a batch of log lines (sent by the phone app)
  GET  /                               download page for the APKs in dist/
  GET  /dist/<file>.apk                APK download
  GET  /logs/<file>.log                read a log

Logs are written to logs/<device>.log and, interleaved, logs/all.log.
Usage: python3 tools/log_server.py [--host IP ...] [--port 8077]
(defaults to this machine's Tailscale addresses plus 127.0.0.1, so it is not reachable from other networks)
"""
import argparse
import html
import re
import socket
import subprocess
import threading
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

ROOT = Path(__file__).resolve().parent.parent
LOGS = ROOT / "logs"
DIST = ROOT / "dist"
MAX_BODY = 1_000_000
write_lock = threading.Lock()


def tailscale_ips() -> list[str]:
    # IPv4 and IPv6: the MagicDNS name resolves to both. Fail rather than fall back, since the
    # phone can only reach Tailscale addresses (the systemd service restarts us until it is up).
    return subprocess.check_output(["tailscale", "ip"], text=True, timeout=5).split()


class Server6(ThreadingHTTPServer):
    address_family = socket.AF_INET6


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        url = urlparse(self.path)
        if url.path != "/log":
            return self.send_error(404)
        device = parse_qs(url.query).get("device", ["unknown"])[0]
        if not re.fullmatch(r"[a-z]{1,16}", device):
            return self.send_error(400, "bad device")
        length = int(self.headers.get("Content-Length", 0))
        if length <= 0 or length > MAX_BODY:
            return self.send_error(413)
        text = self.rfile.read(length).decode("utf-8", "replace")
        received = datetime.now().strftime("%H:%M:%S")
        with write_lock:
            LOGS.mkdir(exist_ok=True)
            with open(LOGS / f"{device}.log", "a") as f:
                f.write(text if text.endswith("\n") else text + "\n")
            with open(LOGS / "all.log", "a") as f:
                for line in text.splitlines():
                    f.write(f"[{received} {device:5}] {line}\n")
        self.send_response(204)
        self.end_headers()

    def do_GET(self):
        path = urlparse(self.path).path
        if path == "/":
            return self.index()
        for prefix, folder, mime in (("/dist/", DIST, "application/vnd.android.package-archive"),
                                     ("/logs/", LOGS, "text/plain; charset=utf-8")):
            if path.startswith(prefix):
                name = path[len(prefix):]
                file = folder / name
                if "/" in name or not file.is_file():
                    return self.send_error(404)
                data = file.read_bytes()
                self.send_response(200)
                self.send_header("Content-Type", mime)
                self.send_header("Content-Length", str(len(data)))
                if mime.startswith("application/"):
                    self.send_header("Content-Disposition", f'attachment; filename="{name}"')
                self.end_headers()
                self.wfile.write(data)
                return
        self.send_error(404)

    def index(self):
        def rows(folder, prefix, pattern):
            items = sorted(folder.glob(pattern)) if folder.exists() else []
            if not items:
                return "<li>none yet</li>"
            return "".join(
                f'<li><a href="{prefix}{html.escape(p.name)}">{html.escape(p.name)}</a> '
                f'<small>{p.stat().st_size // 1024} KB · '
                f'{datetime.fromtimestamp(p.stat().st_mtime):%d %b %H:%M}</small></li>'
                for p in items)
        page = f"""<!doctype html><meta name=viewport content="width=device-width,initial-scale=1">
<title>Orbity</title>
<style>body{{font:17px system-ui;margin:24px;max-width:600px}}li{{margin:14px 0}}
a{{font-size:19px}}small{{color:#777;display:block}}</style>
<h2>Orbity builds</h2><ul>{rows(DIST, "/dist/", "*.apk")}</ul>
<p><b>Orbity-phone / Orbity-watch</b>: normal builds, never send anything off the phone.<br>
<b>…-dev</b>: also send live logs and crash reports to this server (can be switched off on the phone).<br>
Install the phone APK by tapping it; install the watch APK with Wear Installer 2.
Phone and watch builds of either kind work together.</p>
<h3>Logs</h3><ul>{rows(LOGS, "/logs/", "*.log")}</ul>"""
        data = page.encode()
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, fmt, *args):
        if not self.path.startswith("/log?"):  # uploads arrive every few seconds; keep the console readable
            super().log_message(fmt, *args)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", action="append", help="address to listen on (repeatable)")
    parser.add_argument("--port", type=int, default=8077)
    args = parser.parse_args()
    # Tailscale for the phone, localhost for checks on this machine.
    hosts = args.host or [*tailscale_ips(), "127.0.0.1"]
    servers = [(Server6 if ":" in host else ThreadingHTTPServer)((host, args.port), Handler) for host in hosts]
    for host in hosts:
        shown = f"[{host}]" if ":" in host else host
        print(f"Orbity dev server on http://{shown}:{args.port}/", flush=True)
    print(f"Logs in {LOGS}", flush=True)
    for server in servers[1:]:
        threading.Thread(target=server.serve_forever, daemon=True).start()
    servers[0].serve_forever()


if __name__ == "__main__":
    main()
