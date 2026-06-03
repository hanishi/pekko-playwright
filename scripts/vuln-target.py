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
import urllib.request
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

        # Login form: GET renders it; POST (below) sets the session cookie.
        if parsed.path == "/login":
            body = (
                b"<html><body><form method=POST action=/login>"
                b"<input type=text name=username placeholder=user>"
                b"<input type=password name=password placeholder=pass>"
                b"<button type=submit>Log in</button></form></body></html>"
            )
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return

        # SPA shell: session-gated HTML whose JS fetches the JSON API on load.
        # A link crawl sees no account here; only observing the XHR reveals it.
        if parsed.path == "/app":
            if "session=" not in self.headers.get("Cookie", ""):
                self.send_response(401)
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            # The account id comes from the SPA's own ?id (default 1001), and
            # the page links to another id. So a link crawl sees only the link;
            # only running the JS reveals the /api/account XHR, and following
            # the link reveals a second one (multi-hop in-browser navigation).
            body = (
                b"<html><head><title>app</title></head><body><div id=app></div>"
                b'<a href="/app?id=1002">view 1002</a>'
                b"<script>var id=new URLSearchParams(location.search).get('id')||'1001';"
                b"fetch('/api/account?id='+id).then(r=>r.json()).then(d=>{"
                b"document.getElementById('app').textContent=d.email;});"
                b"</script></body></html>"
            )
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return

        # JSON API behind the SPA: same IDOR as /account (session required,
        # ownership NOT checked), but reached only via the SPA's fetch.
        if parsed.path == "/api/account":
            if "session=" not in self.headers.get("Cookie", ""):
                self.send_response(401)
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            ident = params.get("id", ["0"])[0]
            body = (
                f'{{"id":"{ident}","email":"user{ident}@example.com"}}'
            ).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return

        # Authenticated dashboard: session-gated, links to the user's account so
        # an authenticated crawl can navigate from here to /account.
        if parsed.path == "/dashboard":
            if "session=" not in self.headers.get("Cookie", ""):
                self.send_response(401)
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            # Only a search form here, no direct account link: an account
            # listing is reachable only by submitting the search (so a link
            # crawl alone cannot get there; LLM-driven navigation must).
            body = (
                b"<html><body><h1>Dashboard</h1>"
                b'<form method="POST" action="/search">'
                b'<input type="text" name="q" placeholder="search accounts">'
                b"<button type=submit>Search</button></form></body></html>"
            )
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return

        # IDOR, on purpose: /account requires a session cookie but does NOT
        # check that the session owns `id`, so any logged-in user reads any id.
        if parsed.path == "/account":
            cookies = self.headers.get("Cookie", "")
            if "session=" not in cookies:
                self.send_response(401)
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            ident = params.get("id", ["0"])[0]
            body = (
                f'{{"id":"{ident}","email":"user{ident}@example.com",'
                f'"balance":{1000 + int(ident) if ident.isdigit() else 0}}}'
            ).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return

        # Missing function-level auth, on purpose: /admin serves admin content
        # to anyone, with no authentication check at all.
        if parsed.path == "/admin":
            body = b"<html><body><h1>admin panel</h1>all users listed here</body></html>"
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return

        # SSRF, on purpose: the server fetches whatever `url` points at.
        if parsed.path == "/fetch":
            fetch_url = params.get("url", [""])[0]
            note = "no url"
            if fetch_url:
                try:
                    with urllib.request.urlopen(fetch_url, timeout=5) as r:
                        note = f"fetched {r.status}"
                except Exception as e:  # noqa: BLE001
                    note = f"fetch failed: {e}"
            body = f"<html><body>{note}</body></html>".encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
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

    def do_POST(self):
        parsed = urlparse(self.path)
        if parsed.path == "/login":
            length = int(self.headers.get("Content-Length", "0") or 0)
            form = parse_qs(self.rfile.read(length).decode("utf-8"))
            user = form.get("username", [""])[0]
            pw = form.get("password", [""])[0]
            # Accept any username with the shared demo password.
            if user and pw == "secret":
                self.send_response(302)
                self.send_header("Set-Cookie", f"session={user}; Path=/")
                self.send_header("Location", "/")
                self.send_header("Content-Length", "0")
                self.end_headers()
            else:
                self.send_response(401)
                self.send_header("Content-Length", "0")
                self.end_headers()
            return
        # Search results: a listing of accounts as links, reached only by
        # submitting the dashboard search form (a POST the navigator must make).
        if parsed.path == "/search":
            body = (
                b"<html><body><h1>Results</h1>"
                b'<a href="/account?id=1001">Account 1001</a>'
                b'<a href="/account?id=1002">Account 1002</a></body></html>"'
            )
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        self.send_response(404)
        self.send_header("Content-Length", "0")
        self.end_headers()

    def log_message(self, *args):  # quiet
        pass


if __name__ == "__main__":
    print(f"Vulnerable test target: http://localhost:{PORT}/?q=hello  (Ctrl-C to stop)")
    HTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
