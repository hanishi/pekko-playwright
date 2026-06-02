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
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import urlparse, parse_qs

PORT = 8123


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        q = parse_qs(urlparse(self.path).query).get("q", [""])[0]
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
