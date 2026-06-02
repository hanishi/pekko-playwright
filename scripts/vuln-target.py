#!/usr/bin/env python3
"""A deliberately-vulnerable local target for exercising the DAST scanner's
reflected-XSS confirmation path.

RUN ON LOCALHOST ONLY. It reflects the `q` query parameter into the response
body WITHOUT escaping -- that unsanitized reflection IS the vulnerability, on
purpose. Do not expose this to a network.

    python3 scripts/vuln-target.py            # serves http://localhost:8123/?q=hello

Then point the scanner at it (localhost is the authorized active scope):

    DAST_AUTHORIZED_HOSTS=localhost \\
      sbt 'runMain dast.scan.ScannerMain http://localhost:8123/?q=hello'
"""
import re
import time
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import urlparse, parse_qs

PORT = 8123


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        parsed = urlparse(self.path)
        params = parse_qs(parsed.query)

        # Open redirect, on purpose: `next` is used as the Location verbatim,
        # with no allow-list, so an off-origin value redirects off-site.
        if parsed.path == "/redirect":
            target = params.get("next", ["/"])[0]
            self.send_response(302)
            self.send_header("Location", target)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return

        # SQL injection, on purpose: `id` is concatenated into a fake query.
        # An unbalanced quote yields a (faked) MySQL error; a SLEEP(n) payload
        # actually sleeps n seconds, so both error- and time-based probes confirm.
        if parsed.path == "/item":
            ident = params.get("id", [""])[0]
            m = re.search(r"SLEEP\((\d+)\)", ident, re.IGNORECASE)
            if m:
                time.sleep(min(int(m.group(1)), 10))
            if ident.count("'") % 2 == 1:
                body = (
                    b"<html><body>Database error: You have an error in your SQL "
                    b"syntax near \"'\"</body></html>"
                )
                self.send_response(500)
                self.send_header("Content-Type", "text/html; charset=utf-8")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
                return
            body = f"<html><body>Item: {ident}</body></html>".encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return

        q = params.get("q", [""])[0]
        # Vulnerable on purpose: `q` is interpolated into HTML with no escaping.
        html = (
            "<!doctype html><html><head><title>vuln target</title></head>"
            "<body><h1>Search</h1>"
            f"<div id=result>You searched for: {q}</div>"
            "</body></html>"
        )
        body = html.encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):  # quiet
        pass


if __name__ == "__main__":
    print(f"Vulnerable test target: http://localhost:{PORT}/?q=hello  (Ctrl-C to stop)")
    HTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
