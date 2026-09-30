#!/usr/bin/env python3
"""
taskflow_e2e.py - one-command end-to-end test runner for taskflow-auth-service.

What it automates (everything you used to copy/paste):
  1. docker compose up -d  + waits for Postgres to be healthy
  2. mvn spring-boot:run   + waits for the app, captures its console output
  3. reads the MOCK EMAILS from that console output (verification / password-reset
     tokens are only ever printed there) so verify/reset flows need no copy-paste
  4. runs the README test sequence (Day 4 .. Day 13) against the real running app
  5. prints a PASS / FAIL / WARN / SKIP summary, stops the app, exits 0/1

Zero dependencies: Python 3.8+ standard library only.

Usage (from anywhere; default project dir = current dir, or pass --project-dir):
  python taskflow_e2e.py                      # full run: docker + app + tests + cleanup
  python taskflow_e2e.py --reset-db           # wipe the Postgres volume first (clean slate)
  python taskflow_e2e.py --only day6,day8     # run selected sections (see --list)
  python taskflow_e2e.py -v                   # trace every HTTP call
  python taskflow_e2e.py --debug              # stream the whole app console live + trace all
  python taskflow_e2e.py --keep-running       # leave app + DB up afterwards for manual poking
  python taskflow_e2e.py --jvm-debug          # app JVM listens for an IDE debugger on :5005
  python taskflow_e2e.py --attach --email-log app.log
        # app already running elsewhere; tail its output file for the mock emails

Debugging tools built in:
  * preflight checks (docker daemon, mvn, java, busy ports, pom.xml)
  * startup-failure analysis: root "Caused by:" + hints for common causes
    (e.g. the 'does not have a registered order' filter error you just hit)
  * on any failed step: full request/response, the app-log lines emitted DURING that
    request, and a ready-to-paste curl replay command
  * every run writes reports/run-<timestamp>/ : app.log, http-trace.jsonl, report.json
  * --fail-fast, --only/--skip, --keep-running, --jvm-debug, --mvn-args
"""
from __future__ import annotations

import argparse
import atexit
import datetime as dt
import json
import os
import re
import shutil
import signal
import socket
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path

IS_WIN = os.name == "nt"
ANSI_RE = re.compile(r"\x1b\[[0-9;]*[A-Za-z]")
TS_LINE_RE = re.compile(r"^\d{4}-\d\d-\d\dT")
EMAIL_HDR_RE = re.compile(
    r"Mock email.{1,10}?to:\s*(?P<to>\S+)\s*\|\s*subject:\s*(?P<subject>.*?)\s*\|\s*body:")
TOKEN_RE = re.compile(r"/(?P<path>auth/verify|reset-password)\?token=(?P<tok>[0-9a-fA-F-]{36})")
STARTED_RE = re.compile(r"Started \w+ in [\d.]+ seconds")


# --------------------------------------------------------------------------- console
class Term:
    color = True

    @staticmethod
    def c(code, s):
        return f"\033[{code}m{s}\033[0m" if Term.color else str(s)


def green(s): return Term.c("32", s)
def red(s): return Term.c("31", s)
def yellow(s): return Term.c("33", s)
def cyan(s): return Term.c("36", s)
def dim(s): return Term.c("2", s)
def bold(s): return Term.c("1", s)


_log_fh = None


def out(msg=""):
    print(msg, flush=True)
    if _log_fh:
        _log_fh.write(ANSI_RE.sub("", msg) + "\n")
        _log_fh.flush()


def banner(title):
    out("\n" + bold(cyan(f"== {title} " + "=" * max(3, 70 - len(title)))))


# --------------------------------------------------------------------------- config
def parse_args():
    p = argparse.ArgumentParser(description="Automated E2E runner for taskflow-auth-service",
                                formatter_class=argparse.RawDescriptionHelpFormatter,
                                epilog=__doc__.split("Usage", 1)[0])
    p.add_argument("--project-dir", default=".", help="folder containing pom.xml + docker-compose.yml")
    p.add_argument("--base-url", default="http://localhost:8080")
    p.add_argument("--reset-db", action="store_true", help="docker compose down -v first (fresh DB)")
    p.add_argument("--skip-docker", action="store_true", help="don't touch docker (DB already up)")
    p.add_argument("--attach", action="store_true", help="don't start the app; it is already running")
    p.add_argument("--email-log", help="with --attach: file the app's console output is tee'd to")
    p.add_argument("--keep-running", action="store_true", help="leave app (and DB) running at the end")
    p.add_argument("--only", help="comma list of section keys to run (see --list)")
    p.add_argument("--skip", help="comma list of section keys to skip")
    p.add_argument("--list", action="store_true", help="list sections and exit")
    p.add_argument("--fail-fast", action="store_true", help="stop at the first failed step")
    p.add_argument("-v", "--verbose", action="store_true", help="print every HTTP request/response")
    p.add_argument("--debug", action="store_true", help="verbose + stream the app console live")
    p.add_argument("--jvm-debug", action="store_true", help="start app with JDWP on --jvm-debug-port")
    p.add_argument("--jvm-debug-port", type=int, default=5005)
    p.add_argument("--mvn-args", default="", help='extra args for mvn, e.g. "-o -DskipTests"')
    p.add_argument("--startup-timeout", type=int, default=240, help="seconds to wait for the app")
    p.add_argument("--pg-container", default="taskflow-postgres")
    p.add_argument("--admin-email", default="seed.admin@taskflow.dev")
    p.add_argument("--admin-password", default="Admin123!")
    p.add_argument("--rate-limit-capacity", type=int, default=20, help="app.security.rate-limit.capacity")
    p.add_argument("--lockout-attempts", type=int, default=5, help="app.security.lockout.max-failed-attempts")
    p.add_argument("--no-color", action="store_true")
    p.add_argument("--reports-dir", default="reports")
    return p.parse_args()


# --------------------------------------------------------------------------- subprocess helpers
def run(cmd, cwd=None, timeout=120):
    try:
        return subprocess.run(cmd, cwd=cwd, capture_output=True, text=True, timeout=timeout,
                              encoding="utf-8", errors="replace")
    except (FileNotFoundError, subprocess.TimeoutExpired) as e:
        return subprocess.CompletedProcess(cmd, 127, "", str(e))


def port_in_use(port, host="127.0.0.1"):
    try:
        with socket.create_connection((host, port), timeout=0.6):
            return True
    except OSError:
        return False


def docker_compose_cmd():
    if shutil.which("docker") and run(["docker", "compose", "version"], timeout=20).returncode == 0:
        return ["docker", "compose"]
    if shutil.which("docker-compose"):
        return ["docker-compose"]
    return None


def kill_tree(proc):
    if proc is None or proc.poll() is not None:
        return
    try:
        if IS_WIN:
            subprocess.run(["taskkill", "/F", "/T", "/PID", str(proc.pid)], capture_output=True)
        else:
            os.killpg(os.getpgid(proc.pid), signal.SIGTERM)
            try:
                proc.wait(timeout=15)
            except subprocess.TimeoutExpired:
                os.killpg(os.getpgid(proc.pid), signal.SIGKILL)
    except Exception:
        pass


# --------------------------------------------------------------------------- failure diagnosis
HINTS = [
    (r"does not have a registered order",
     "SecurityConfig: addFilterBefore/After(x, SomeCustomFilter.class) anchors to a custom filter that is not\n"
     "registered yet. Register the anchor filter FIRST (addFilterBefore(jwt, UsernamePasswordAuthenticationFilter.class))\n"
     "and only then anchor the other one to it - or anchor both to UsernamePasswordAuthenticationFilter."),
    (r"Port \d+ was already in use|Address already in use|BindException",
     "Port 8080 is taken. Stop the other process (`netstat -ano | findstr :8080` on Windows) or use --attach."),
    (r"Connection to .*:5433 refused|Connection refused.*5433|PSQLException: Connection",
     "Postgres isn't reachable on localhost:5433. Is the container healthy? `docker compose ps` / `docker logs taskflow-postgres`."),
    (r"password authentication failed",
     "DB credentials mismatch. A stale volume may hold different creds: rerun with --reset-db."),
    (r'relation "\w+" does not exist',
     "Schema missing (data.sql ran before Hibernate?). Check spring.jpa.defer-datasource-initialization=true and ddl-auto."),
    (r"COMPILATION ERROR|cannot find symbol|package .* does not exist",
     "Java compilation failed - scroll up in app.log for the first [ERROR] line with a file:line."),
    (r"Could not resolve dependencies|Could not transfer artifact|Connect to repo\.maven",
     "Maven can't download dependencies (offline / proxy). Try --mvn-args \"-o\" if everything is already cached."),
    (r"invalid target release|release version \d+ not supported|Unsupported class file major version",
     "JDK/`release` mismatch. pom targets Java 17; you run a newer JDK - fine - but an OLDER one will fail. Check `java -version`."),
    (r"Unable to find a suitable main class|Could not find or load main class",
     "Run from the folder that contains pom.xml (or pass --project-dir)."),
    (r"UnsatisfiedDependencyException|NoSuchBeanDefinitionException",
     "Bean wiring problem - read the LAST 'Caused by:' line below, that's the real reason."),
    (r"Validation failed for .*DDL|SchemaManagementException|Schema-validation",
     "Hibernate schema mismatch with the DB. --reset-db usually fixes it in dev."),
]


def diagnose(lines, title="Startup failed"):
    out("\n" + red(bold(f"!! {title}")))
    text = "\n".join(lines)
    causes = [l.strip() for l in lines if l.lstrip().startswith("Caused by:")]
    if causes:
        out(red("Root cause (last 'Caused by'):"))
        out("  " + causes[-1][:400])
    else:
        errs = [l for l in lines if " ERROR " in l or l.startswith("[ERROR]")]
        if errs:
            out(red("First error lines:"))
            for l in errs[:6]:
                out("  " + l[:300])
    matched = False
    for pat, hint in HINTS:
        if re.search(pat, text):
            out(yellow("\nLikely fix: ") + hint)
            matched = True
    if not matched:
        out(dim("(no built-in hint matched - see the tail below and app.log)"))
    out(dim("\n--- last 25 lines of app output ---"))
    for l in lines[-25:]:
        out(dim("  " + l[:220]))


# --------------------------------------------------------------------------- app process
class App:
    """Owns `mvn spring-boot:run` (or tails a log file in --attach mode) and parses mock emails."""

    def __init__(self, cfg, log_path):
        self.cfg, self.log_path = cfg, Path(log_path)
        self.cv = threading.Condition()
        self.lines = []
        self.emails = []          # dicts: to, kind ('verify'|'reset'), token
        self._pending = None
        self.started = False
        self.failed_marker = False
        self.exited = False
        self.proc = None
        self.has_log_access = True

    # ----- line ingestion (shared by process pump and file tail)
    def _ingest(self, line):
        line = ANSI_RE.sub("", line.rstrip("\r\n"))
        with self.cv:
            self.lines.append(line)
            m = EMAIL_HDR_RE.search(line)
            if m:
                self._pending = {"to": m.group("to"), "subject": m.group("subject")}
                line_rest = line[m.end():]
            else:
                line_rest = line
                if self._pending and TS_LINE_RE.match(line):
                    self._pending = None
            if self._pending:
                t = TOKEN_RE.search(line_rest)
                if t:
                    kind = "verify" if t.group("path") == "auth/verify" else "reset"
                    self.emails.append({"to": self._pending["to"].lower(), "kind": kind, "token": t.group("tok")})
                    self._pending = None
            if STARTED_RE.search(line):
                self.started = True
            if "Application run failed" in line or "APPLICATION FAILED TO START" in line:
                self.failed_marker = True
            self.cv.notify_all()
        if self.cfg.debug:
            print(dim("  app| " + line[:240]), flush=True)

    # ----- start / stop
    def start(self):
        cfg = self.cfg
        extra = cfg.mvn_args.strip()
        jvm = ""
        if cfg.jvm_debug:
            jvm = (f'"-Dspring-boot.run.jvmArguments=-agentlib:jdwp=transport=dt_socket,'
                   f'server=y,suspend=n,address={cfg.jvm_debug_port}"')
        if IS_WIN:
            cmd = f"mvn spring-boot:run {jvm} {extra}".strip()
            kw = {"shell": True}
        else:
            cmd = ["mvn", "spring-boot:run"] + ([jvm.strip('"')] if jvm else []) + (extra.split() if extra else [])
            kw = {"start_new_session": True}
        out(dim(f"$ {cmd if isinstance(cmd, str) else ' '.join(cmd)}   (cwd={cfg.project_dir})"))
        self.proc = subprocess.Popen(cmd, cwd=cfg.project_dir, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                     stdin=subprocess.DEVNULL, **kw)
        threading.Thread(target=self._pump, daemon=True).start()
        if cfg.jvm_debug:
            out(yellow(f"JVM debug: attach your IDE (Remote JVM Debug) to localhost:{cfg.jvm_debug_port}"))

    def _pump(self):
        with open(self.log_path, "w", encoding="utf-8") as f:
            for raw in iter(self.proc.stdout.readline, b""):
                line = raw.decode("utf-8", "replace").rstrip("\r\n")
                f.write(line + "\n")
                f.flush()
                self._ingest(line)
        with self.cv:
            self.exited = True
            self.cv.notify_all()

    def attach_tail(self, path):
        p = Path(path)
        if not p.exists():
            raise SystemExit(f"--email-log file not found: {p}")

        def tail():
            with open(p, "r", encoding="utf-8", errors="replace") as f:
                f.seek(0, os.SEEK_END)
                while True:
                    l = f.readline()
                    if l:
                        self._ingest(l)
                    else:
                        time.sleep(0.15)
        threading.Thread(target=tail, daemon=True).start()

    def stop(self):
        kill_tree(self.proc)

    # ----- helpers for tests
    def line_count(self):
        with self.cv:
            return len(self.lines)

    def slice(self, a, b):
        with self.cv:
            return self.lines[a:b]

    def email_count(self, to, kind):
        with self.cv:
            return sum(1 for e in self.emails if e["to"] == to.lower() and e["kind"] == kind)

    def wait_for_email(self, to, kind, have=0, timeout=20):
        """Block until more than `have` emails of `kind` to `to` exist; return the newest token."""
        if not self.has_log_access:
            raise Skip("no access to app console output (use default mode, or --attach --email-log FILE)")
        end = time.time() + timeout
        with self.cv:
            while True:
                mine = [e for e in self.emails if e["to"] == to.lower() and e["kind"] == kind]
                if len(mine) > have:
                    return mine[-1]["token"]
                left = end - time.time()
                if left <= 0:
                    raise Fail(f"no '{kind}' mock email for {to} appeared in app output within {timeout}s")
                self.cv.wait(timeout=min(left, 0.5))

    def wait_ready(self, http, timeout):
        start, last_hb = time.time(), time.time()
        while time.time() - start < timeout:
            if self.proc and self.proc.poll() is not None:
                time.sleep(0.5)
                return False
            if self.failed_marker:
                for _ in range(40):          # let mvn print BUILD FAILURE and exit
                    if self.proc.poll() is not None:
                        break
                    time.sleep(0.25)
                return False
            if self.started:
                return True
            if http.probe("/v3/api-docs"):
                return True
            if time.time() - last_hb > 10:
                last_hb = time.time()
                tail = (self.lines[-1] if self.lines else "(no output yet)")[:110]
                out(dim(f"   ...waiting ({int(time.time() - start)}s) last: {tail}"))
            time.sleep(0.4)
        return False


# --------------------------------------------------------------------------- HTTP client
class Resp:
    def __init__(self, method, url, req_headers, req_body, status, headers, text, ms, log_range):
        self.method, self.url, self.req_headers, self.req_body = method, url, req_headers, req_body
        self.status, self.headers, self.text, self.ms, self.log_range = status, headers, text, ms, log_range
        self._json, self._parsed = None, False

    def json(self):
        if not self._parsed:
            self._parsed = True
            try:
                self._json = json.loads(self.text) if self.text else None
            except ValueError:
                self._json = None
        return self._json

    def msg(self):
        j = self.json()
        return (j or {}).get("message", "") if isinstance(j, dict) else ""


def mask_headers(h):
    o = {}
    for k, v in h.items():
        if k.lower() == "authorization" and len(v) > 24:
            v = v[:18] + f"...({len(v)} chars)"
        o[k] = v
    return o


def curl_for(r):
    parts = ["curl", "-i", "-X", r.method, f"'{r.url}'"]
    for k, v in r.req_headers.items():
        parts += ["-H", f"'{k}: {v}'"]
    if r.req_body:
        parts += ["-d", "'" + r.req_body.replace("'", "'\\''") + "'"]
    return " ".join(parts)


class Http:
    def __init__(self, cfg, app, trace_path):
        self.cfg, self.app = cfg, app
        self.base = cfg.base_url.rstrip("/")
        self.trace = open(trace_path, "w", encoding="utf-8")
        self.last = None

    def probe(self, path):
        try:
            with urllib.request.urlopen(self.base + path, timeout=2) as r:
                return r.status == 200
        except urllib.error.HTTPError:
            return True          # any HTTP answer means Tomcat is up
        except Exception:
            return False

    def request(self, method, path, body=None, token=None, headers=None, raw=None, retry_429=True):
        url = self.base + path
        h = {"Accept": "application/json"}
        data = None
        if body is not None or raw is not None:
            h["Content-Type"] = "application/json"
            data = (raw if raw is not None else json.dumps(body)).encode("utf-8")
        if token:
            h["Authorization"] = f"Bearer {token}"
        h.update(headers or {})
        for attempt in range(4):
            a = self.app.line_count() if self.app else 0
            t0 = time.time()
            req = urllib.request.Request(url, data=data, method=method, headers=h)
            try:
                with urllib.request.urlopen(req, timeout=20) as resp:
                    status, rh, text = resp.status, dict(resp.headers.items()), resp.read().decode("utf-8", "replace")
            except urllib.error.HTTPError as e:
                status, rh, text = e.code, dict(e.headers.items()), e.read().decode("utf-8", "replace")
            except (urllib.error.URLError, ConnectionError, socket.timeout) as e:
                raise Fail(f"cannot reach {url}: {e}")
            ms = int((time.time() - t0) * 1000)
            if self.app:
                time.sleep(0.08)      # let the app flush its log lines for this request
            b = self.app.line_count() if self.app else 0
            r = Resp(method, url, h, data.decode() if data else "", status,
                     {k.lower(): v for k, v in rh.items()}, text, ms, (a, b))
            self.last = r
            self.trace.write(json.dumps({"t": dt.datetime.now().isoformat(), "method": method, "url": url,
                                         "req_headers": mask_headers(h), "req_body": r.req_body,
                                         "status": status, "resp_headers": r.headers, "resp_body": text[:4000],
                                         "ms": ms}) + "\n")
            self.trace.flush()
            if self.cfg.verbose or self.cfg.debug:
                out(dim(f"   {method} {path} -> {status} ({ms}ms)"))
            if status == 429 and retry_429 and attempt < 3:
                wait = min(int(r.headers.get("retry-after", "5")) + 1, 65)
                out(yellow(f"   429 from rate limiter on {path}; sleeping {wait}s then retrying"))
                time.sleep(wait)
                continue
            return r

    def get(self, path, **kw): return self.request("GET", path, **kw)
    def post(self, path, body=None, **kw): return self.request("POST", path, body=body, **kw)
    def put(self, path, body=None, **kw): return self.request("PUT", path, body=body, **kw)
    def delete(self, path, **kw): return self.request("DELETE", path, **kw)


# --------------------------------------------------------------------------- test framework
class Fail(Exception):
    pass


class Skip(Exception):
    pass


class StopRun(Exception):
    pass


class Ctx(dict):
    """Shared state between steps; missing keys skip the step instead of crashing it."""
    def __getattr__(self, k):
        if k in self:
            return self[k]
        raise Skip(f"needs '{k}' from an earlier step that did not succeed")

    def __setattr__(self, k, v):
        self[k] = v


class User:
    def __init__(self, name, run_id, password="Sup3rSecret1"):
        self.name, self.email, self.password = name, f"{name}.{run_id}@taskflow.dev", password
        self.id = self.access = self.refresh = None


def check(cond, msg):
    if not cond:
        raise Fail(msg)


def expect_status(r, *codes):
    if r.status not in codes:
        raise Fail(f"expected HTTP {'/'.join(map(str, codes))}, got {r.status}  ({r.msg() or r.text[:120]!r})")


def expect_error_shape(r):
    j = r.json()
    check(isinstance(j, dict), "error body is not a JSON object")
    for k in ("status", "error", "message", "path"):
        check(k in j, f"ApiErrorResponse missing field '{k}'")
    check(j["status"] == r.status, f"body.status {j['status']} != HTTP {r.status}")


class Step:
    def __init__(self, t, name, soft):
        self.t, self.name, self.soft = t, name, soft

    def __enter__(self):
        return self

    def __exit__(self, et, ev, tb):
        t = self.t
        if et is None:
            t.record("PASS", self.name)
            return False
        if issubclass(et, Skip):
            t.record("SKIP", self.name, str(ev))
            return True
        if issubclass(et, StopRun):
            return False
        if issubclass(et, Fail):
            status = "WARN" if self.soft else "FAIL"
            t.record(status, self.name, str(ev), debug=True)
        else:   # unexpected bug in the test script itself
            import traceback
            t.record("FAIL", self.name, f"script error: {et.__name__}: {ev}", debug=True,
                     extra="".join(traceback.format_exception(et, ev, tb))[-1200:])
        if t.cfg.fail_fast and not self.soft:
            raise StopRun()
        return True


class Runner:
    def __init__(self, cfg, http, app):
        self.cfg, self.http, self.app = cfg, http, app
        self.results, self.c = [], Ctx()
        self.section_key = ""
        self.run_id = uuid.uuid4().hex[:6]

    def step(self, name, soft=False):
        return Step(self, name, soft)

    def user(self, name):
        users = self.c.setdefault("_users", {})
        if name not in users:
            users[name] = User(name, self.run_id)
        return users[name]

    def record(self, status, name, detail="", debug=False, extra=""):
        self.results.append({"section": self.section_key, "status": status, "name": name, "detail": detail})
        mark = {"PASS": green("PASS"), "FAIL": red("FAIL"), "WARN": yellow("WARN"), "SKIP": dim("SKIP")}[status]
        out(f"  [{mark}] {name}" + (f"\n         {dim(detail)}" if detail and status != "PASS" else ""))
        if debug:
            self.debug_bundle(extra)

    def debug_bundle(self, extra=""):
        r = self.http.last
        if extra:
            out(dim("         --- script traceback ---\n" + extra))
        if r is None:
            return
        pad = "         "
        out(pad + dim(f"-- request  {r.method} {r.url}  ({r.ms}ms)"))
        for k, v in mask_headers(r.req_headers).items():
            out(pad + dim(f"   {k}: {v}"))
        if r.req_body:
            out(pad + dim("   body: " + r.req_body[:600]))
        out(pad + dim(f"-- response HTTP {r.status}"))
        for k in ("content-type", "retry-after", "x-ratelimit-remaining", "location"):
            if k in r.headers:
                out(pad + dim(f"   {k}: {r.headers[k]}"))
        out(pad + dim("   body: " + r.text[:900].replace("\n", " ")))
        if self.app and self.app.has_log_access:
            a, b = r.log_range
            lines = self.app.slice(a, b + 40)
            keep = [l for l in lines if re.search(
                r"ERROR|WARN|Exception|Caused by|Mock email|^\s+at com\.ahdyahmed", l)
                and not re.search(r"~\[(spring|hibernate|jackson|tomcat|jakarta|jdk)", l)]
            if self.cfg.debug:
                keep = lines
            if keep:
                out(pad + dim("-- app log during this request (filtered; --debug for all)"))
                for l in keep[:25]:
                    out(pad + dim("   | " + l[:200]))
        out(pad + dim("-- replay (git-bash / WSL / mac / linux):"))
        out(pad + cyan("   " + curl_for(r)))


# --------------------------------------------------------------------------- test sections
def sec_smoke(t):
    h = t.http
    with t.step("GET /v3/api-docs is public -> 200 (OpenAPI served)"):
        r = h.get("/v3/api-docs")
        expect_status(r, 200)
        check("openapi" in r.text, "response doesn't look like an OpenAPI document")
    with t.step("protected endpoint without token -> 401 + ApiErrorResponse shape"):
        r = h.get("/api/projects")
        expect_status(r, 401)
        expect_error_shape(r)


def sec_day4(t):
    h, c = t.http, t.c
    for n in ("alice", "bob", "carol"):
        with t.step(f"register {n} -> 201, role USER, enabled=false"):
            u = t.user(n)
            r = h.post("/auth/register", {"email": u.email, "password": u.password})
            expect_status(r, 201)
            j = r.json()
            check(j["email"] == u.email, "email not echoed back")
            check(j["role"] == "USER", f"self-registration must force USER, got {j['role']}")
            check(j["enabled"] is False, "new account must start disabled (Day 12)")
            u.id = j["id"]
    with t.step("weak password -> 400 with fieldErrors.password"):
        r = h.post("/auth/register", {"email": f"weak.{t.run_id}@taskflow.dev", "password": "weak"})
        expect_status(r, 400)
        check("password" in (r.json().get("fieldErrors") or {}), "fieldErrors.password missing")
    with t.step("malformed email -> 400 with fieldErrors.email"):
        r = h.post("/auth/register", {"email": "not-an-email", "password": "Sup3rSecret1"})
        expect_status(r, 400)
        check("email" in (r.json().get("fieldErrors") or {}), "fieldErrors.email missing")
    with t.step("duplicate email -> 409"):
        u = t.user("alice")
        expect_status(h.post("/auth/register", {"email": u.email, "password": u.password}), 409)


def sec_day12(t):
    h, app = t.http, t.app
    with t.step("login before verification -> 403 'verify your email'"):
        u = t.user("alice")
        r = h.post("/auth/login", {"email": u.email, "password": u.password})
        expect_status(r, 403)
        check("verify" in r.msg().lower(), f"message should explain why: {r.msg()!r}")
    for n in ("alice", "bob", "carol"):
        with t.step(f"verify {n} using token read from app console -> 200 enabled=true"):
            u = t.user(n)
            tok = app.wait_for_email(u.email, "verify")
            t.c[f"vtoken_{n}"] = tok
            r = h.get(f"/auth/verify?token={tok}")
            expect_status(r, 200)
            check(r.json()["enabled"] is True, "account still disabled after verify")
    with t.step("re-using a verification token -> 401"):
        expect_status(h.get(f"/auth/verify?token={t.c.vtoken_alice}"), 401)
    with t.step("garbage verification token -> 401"):
        expect_status(h.get("/auth/verify?token=" + str(uuid.uuid4())), 401)
    with t.step("resend-verification flow (new token works; verified/unknown emails -> same 204)"):
        u = t.user("erin")
        expect_status(h.post("/auth/register", {"email": u.email, "password": u.password}), 201)
        app.wait_for_email(u.email, "verify")
        have = app.email_count(u.email, "verify")
        r1 = h.post("/auth/resend-verification", {"email": u.email})
        expect_status(r1, 204)
        tok = app.wait_for_email(u.email, "verify", have=have)
        expect_status(h.get(f"/auth/verify?token={tok}"), 200)
        have = app.email_count(u.email, "verify")
        r2 = h.post("/auth/resend-verification", {"email": u.email})             # already verified
        r3 = h.post("/auth/resend-verification", {"email": f"ghost.{t.run_id}@taskflow.dev"})  # unknown
        check(r2.status == r3.status == 204 and r2.text == r3.text, "verified vs unknown responses differ")
        time.sleep(1.0)
        check(app.email_count(u.email, "verify") == have, "a verification email was sent to an already-verified account")


def sec_day6(t):
    h = t.http
    for n in ("alice", "bob", "carol"):
        with t.step(f"login {n} -> 200 with access+refresh tokens"):
            u = t.user(n)
            r = h.post("/auth/login", {"email": u.email, "password": u.password})
            expect_status(r, 200)
            j = r.json()
            check(j.get("accessToken") and j.get("refreshToken"), "tokens missing")
            check(j.get("tokenType") == "Bearer", "tokenType != Bearer")
            u.access, u.refresh = j["accessToken"], j["refreshToken"]
    a = lambda: t.user("alice")
    with t.step("wrong password -> 401 generic message"):
        r = h.post("/auth/login", {"email": a().email, "password": "WrongPassw0rd"})
        expect_status(r, 401)
        t.c.generic_msg = r.msg()
    with t.step("unknown email -> 401 with the SAME message (no account enumeration)"):
        r = h.post("/auth/login", {"email": f"nobody.{t.run_id}@taskflow.dev", "password": "WrongPassw0rd"})
        expect_status(r, 401)
        check(r.msg() == t.c.generic_msg, f"message differs: {r.msg()!r} vs {t.c.generic_msg!r}")
    with t.step("access token works on a protected endpoint (GET /api/projects -> 200)"):
        expect_status(h.get("/api/projects", token=a().access), 200)
    with t.step("tampered access token -> 401"):
        bad = a().access[:-4] + ("AAAA" if not a().access.endswith("AAAA") else "BBBB")
        expect_status(h.get("/api/projects", token=bad), 401)
    with t.step("refresh token used as Bearer -> 401 (only access tokens authenticate)"):
        expect_status(h.get("/api/projects", token=a().refresh), 401)
    with t.step("access token used as refresh token -> 401"):
        expect_status(h.post("/auth/refresh", {"refreshToken": a().access}), 401)
    with t.step("refresh rotates: new pair 200, old refresh token then 401"):
        time.sleep(1.2)       # JWTs carry second-resolution iat and no jti; avoid same-second duplicates here
        old = a().refresh
        r = h.post("/auth/refresh", {"refreshToken": old})
        expect_status(r, 200)
        j = r.json()
        check(j["refreshToken"] != old, "rotation returned the same refresh token")
        a().access, a().refresh = j["accessToken"], j["refreshToken"]
        expect_status(h.post("/auth/refresh", {"refreshToken": old}), 401)
    with t.step("logout -> 204, repeat logout -> 204 (idempotent), refresh afterwards -> 401"):
        rt = a().refresh
        expect_status(h.post("/auth/logout", {"refreshToken": rt}), 204)
        expect_status(h.post("/auth/logout", {"refreshToken": rt}), 204)
        expect_status(h.post("/auth/refresh", {"refreshToken": rt}), 401)
    with t.step("re-login alice for later sections"):
        r = h.post("/auth/login", {"email": a().email, "password": a().password})
        expect_status(r, 200)
        a().access, a().refresh = r.json()["accessToken"], r.json()["refreshToken"]
    with t.step("PROBE: two logins back-to-back (same second) must both succeed (JWT needs a jti)", soft=True):
        u = t.user("bob")
        for i in range(3):          # no sleep on purpose: identical JWTs collide on refresh_tokens.token_hash
            r = h.post("/auth/login", {"email": u.email, "password": u.password})
            expect_status(r, 200)
        u.access, u.refresh = r.json()["accessToken"], r.json()["refreshToken"]


def sec_day7(t):
    h, c = t.http, t.c
    with t.step("USER (alice) creating a project -> 403"):
        r = h.post("/api/projects", {"name": "nope"}, token=t.user("alice").access)
        expect_status(r, 403)
        expect_error_shape(r)
    with t.step("login seeded ADMIN"):
        r = h.post("/auth/login", {"email": t.cfg.admin_email, "password": t.cfg.admin_password})
        expect_status(r, 200)
        c.admin = r.json()["accessToken"]
    with t.step("non-admin hitting GET /api/admin/users -> 403"):
        expect_status(h.get("/api/admin/users", token=t.user("bob").access), 403)
    with t.step("ADMIN lists users -> 200 (paged)"):
        r = h.get("/api/admin/users?size=5", token=c.admin)
        expect_status(r, 200)
        check("content" in r.json(), "no 'content' in page response")
    for n in ("alice", "carol"):
        with t.step(f"ADMIN promotes {n} to MANAGER -> 200"):
            r = h.put(f"/api/admin/users/{t.user(n).id}/role", {"role": "MANAGER"}, token=c.admin)
            expect_status(r, 200)
            check(r.json()["role"] == "MANAGER", "role not updated")
    with t.step("alice's EXISTING token now creates a project -> 201 (role checked live, not from JWT)"):
        r = h.post("/api/projects", {"name": f"TaskFlow {t.run_id}", "description": "e2e"}, token=t.user("alice").access)
        expect_status(r, 201)
        j = r.json()
        check(j["ownerId"] == t.user("alice").id, "owner must be the caller")
        check(r.headers.get("location", "").endswith(f"/api/projects/{j['id']}"), "Location header wrong")
        c.project_id = j["id"]
    with t.step("PROBE: malformed JSON body should be 400, not 500", soft=True):
        expect_status(h.post("/auth/login", raw="{not json"), 400)
    with t.step("PROBE: unknown role value should be 400, not 500", soft=True):
        expect_status(h.put(f"/api/admin/users/{t.user('carol').id}/role", {"role": "SUPERMAN"}, token=c.admin), 400)
    with t.step("PROBE: unknown route (authenticated) should be 404, not 500", soft=True):
        expect_status(h.get("/api/does-not-exist", token=c.admin), 404)


def sec_day8(t):
    h, c = t.http, t.c
    al, bo, ca = (t.user(n) for n in ("alice", "bob", "carol"))
    pid = lambda: c.project_id
    with t.step("non-member bob GET project -> 403"):
        expect_status(h.get(f"/api/projects/{pid()}", token=bo.access), 403)
    with t.step("MANAGER-but-not-owner carol PUT project -> 403"):
        expect_status(h.put(f"/api/projects/{pid()}", {"name": "hijack"}, token=ca.access), 403)
    with t.step("owner alice adds bob -> 200, memberCount 1"):
        r = h.post(f"/api/projects/{pid()}/members/{bo.id}", token=al.access)
        expect_status(r, 200)
        check(r.json()["memberCount"] == 1, f"memberCount={r.json()['memberCount']}")
    with t.step("member bob GET project -> 200"):
        expect_status(h.get(f"/api/projects/{pid()}", token=bo.access), 200)
    with t.step("alice creates a task assigned to bob -> 201"):
        r = h.post("/api/tasks", {"title": "Wire JWT filter", "priority": "HIGH", "projectId": pid(),
                                  "assigneeId": bo.id}, token=al.access)
        expect_status(r, 201)
        j = r.json()
        check(j["status"] == "TODO" and j["createdById"] == al.id and j["assigneeId"] == bo.id, "task fields wrong")
        c.task_id = j["id"]
    with t.step("carol (MANAGER, not member) creating a task in that project -> 403"):
        expect_status(h.post("/api/tasks", {"title": "x", "projectId": pid()}, token=ca.access), 403)
    with t.step("assignee bob PUT task -> 200 (assignee may update)"):
        r = h.put(f"/api/tasks/{c.task_id}", {"title": "Wire JWT filter (wip)", "status": "IN_PROGRESS",
                                               "assigneeId": bo.id}, token=bo.access)
        expect_status(r, 200)
        check(r.json()["status"] == "IN_PROGRESS", "status not updated")
    with t.step("carol PUT task -> 403"):
        expect_status(h.put(f"/api/tasks/{c.task_id}", {"title": "x"}, token=ca.access), 403)
    with t.step("assignee bob DELETE task -> 403 (delete is owner/ADMIN only)"):
        expect_status(h.delete(f"/api/tasks/{c.task_id}", token=bo.access), 403)
    with t.step("owner alice DELETE task -> 204"):
        expect_status(h.delete(f"/api/tasks/{c.task_id}", token=al.access), 204)
    with t.step("alice removes bob -> 200; bob GET project -> 403 again"):
        expect_status(h.delete(f"/api/projects/{pid()}/members/{bo.id}", token=al.access), 200)
        expect_status(h.get(f"/api/projects/{pid()}", token=bo.access), 403)
    with t.step("carol's project list does NOT contain alice's project"):
        r = h.get("/api/projects?size=100", token=ca.access)
        expect_status(r, 200)
        check(pid() not in [p["id"] for p in r.json()["content"]], "carol can see a project she has no access to")
    with t.step("ADMIN's project list DOES contain it"):
        r = h.get("/api/projects?size=100&sort=id,desc", token=c.admin)
        expect_status(r, 200)
        check(pid() in [p["id"] for p in r.json()["content"]], "admin can't see the project")


def sec_day9(t):
    h, c = t.http, t.c
    al, bo = t.user("alice"), t.user("bob")
    pid = lambda: c.project_id
    with t.step("re-add bob, create open task assigned to him"):
        expect_status(h.post(f"/api/projects/{pid()}/members/{bo.id}", token=al.access), 200)
        r = h.post("/api/tasks", {"title": "Open task", "projectId": pid(), "assigneeId": bo.id}, token=al.access)
        expect_status(r, 201)
        c.task2 = r.json()["id"]
    with t.step("remove bob while he has an open task -> 409 mentioning the task id"):
        r = h.delete(f"/api/projects/{pid()}/members/{bo.id}", token=al.access)
        expect_status(r, 409)
        check(str(c.task2) in r.msg(), f"409 message should list task id {c.task2}: {r.msg()!r}")
    with t.step("mark task DONE (keep assignee) -> 200, then removal -> 200"):
        expect_status(h.put(f"/api/tasks/{c.task2}", {"title": "Open task", "status": "DONE",
                                                      "assigneeId": bo.id}, token=al.access), 200)
        expect_status(h.delete(f"/api/projects/{pid()}/members/{bo.id}", token=al.access), 200)
    with t.step("sort=name,asc -> 200"):
        expect_status(h.get("/api/projects?sort=name,asc", token=al.access), 200)
    with t.step("sort=owner.email,asc (not allow-listed) -> 400"):
        expect_status(h.get("/api/projects?sort=owner.email,asc", token=al.access), 400)
    with t.step("tasks sort=nonsense,asc -> 400"):
        expect_status(h.get("/api/tasks?sort=nonsense,asc", token=al.access), 400)


def sec_day10(t):
    h, app = t.http, t.app
    u = t.user("dave")
    with t.step("register + verify dave"):
        expect_status(h.post("/auth/register", {"email": u.email, "password": u.password}), 201)
        expect_status(h.get("/auth/verify?token=" + app.wait_for_email(u.email, "verify")), 200)
    n = t.cfg.lockout_attempts
    msgs = []
    with t.step(f"{n} wrong passwords -> {n}x 401, identical message each time"):
        for i in range(n):
            r = h.post("/auth/login", {"email": u.email, "password": "WrongPassw0rd"})
            expect_status(r, 401)
            msgs.append(r.msg())
        check(len(set(msgs)) == 1, f"messages differ across attempts: {set(msgs)}")
    with t.step("CORRECT password while locked -> still 401, same generic message"):
        r = h.post("/auth/login", {"email": u.email, "password": u.password})
        expect_status(r, 401)
        check(r.msg() == msgs[0], "locked-account message differs (leaks lockout state)")
    t.record("SKIP", "lockout auto-expiry", "needs waiting lockout-duration-minutes (15) or a config override")


def sec_day13(t):
    h, app = t.http, t.app
    al = t.user("alice")
    with t.step("fresh login for alice (need a live refresh token)"):
        r = h.post("/auth/login", {"email": al.email, "password": al.password})
        expect_status(r, 200)
        t.c.pre_reset_refresh = r.json()["refreshToken"]
    with t.step("forgot-password: existing vs unknown email -> identical 204"):
        have = app.email_count(al.email, "reset")
        r1 = h.post("/auth/forgot-password", {"email": al.email})
        r2 = h.post("/auth/forgot-password", {"email": f"ghost.{t.run_id}@taskflow.dev"})
        check(r1.status == r2.status == 204 and r1.text == r2.text, f"responses differ: {r1.status} vs {r2.status}")
        t.c.reset_token = app.wait_for_email(al.email, "reset", have=have)
    with t.step("reset with weak new password -> 400"):
        expect_status(h.post("/auth/reset-password", {"token": t.c.reset_token, "newPassword": "weak"}), 400)
    with t.step("reset with garbage token -> 401"):
        expect_status(h.post("/auth/reset-password", {"token": str(uuid.uuid4()), "newPassword": "BrandNewSecret1"}), 401)
    with t.step("reset with valid token -> 204"):
        expect_status(h.post("/auth/reset-password", {"token": t.c.reset_token, "newPassword": "BrandNewSecret1"}), 204)
    with t.step("pre-reset refresh token is revoked -> 401"):
        expect_status(h.post("/auth/refresh", {"refreshToken": t.c.pre_reset_refresh}), 401)
    with t.step("old password rejected -> 401; new password accepted -> 200"):
        expect_status(h.post("/auth/login", {"email": al.email, "password": al.password}), 401)
        time.sleep(1.2)       # avoid a same-second login producing an identical refresh token (see day6 PROBE)
        expect_status(h.post("/auth/login", {"email": al.email, "password": "BrandNewSecret1"}), 200)
    with t.step("re-using the reset token -> 401"):
        expect_status(h.post("/auth/reset-password", {"token": t.c.reset_token, "newPassword": "Another1Secret"}), 401)


def sec_day11(t):
    """Runs LAST: it deliberately drains the forgot-password bucket."""
    h, cap = t.http, t.cfg.rate_limit_capacity
    path, body = "/auth/forgot-password", {"email": f"rl.{t.run_id}@taskflow.dev"}
    with t.step(f"hammer {path} -> 429 within {cap + 10} calls, with Retry-After + error shape"):
        hit = None
        for i in range(cap + 10):
            r = h.post(path, body, retry_429=False)
            if r.status == 429:
                hit = i + 1
                break
        check(hit is not None, f"no 429 after {cap + 10} requests (rate limiter off or capacity > {cap}?)")
        check("retry-after" in r.headers, "429 missing Retry-After header")
        expect_error_shape(r)
        t.c.rl_hit = hit
        out(dim(f"         first 429 on request #{hit} (capacity={cap}; earlier calls this minute count too)"))
    with t.step("buckets are per-path: /auth/login unaffected -> 401 (not 429)"):
        r = h.post("/auth/login", {"email": body["email"], "password": "x"}, retry_429=False)
        expect_status(r, 401)
    with t.step("PROBE: rotating X-Forwarded-For must NOT reset the limit (spoofable client IP)", soft=True):
        r = h.post(path, body, headers={"X-Forwarded-For": f"203.0.113.{uuid.uuid4().int % 250 + 1}"}, retry_429=False)
        check(r.status == 429, f"got {r.status}: limiter keys on a client-supplied X-Forwarded-For header, "
                               "so attackers can bypass it by varying that header")


SECTIONS = [
    ("smoke", "Smoke: app reachable, docs public, auth required", sec_smoke),
    ("day4", "Day 4  - registration & validation", sec_day4),
    ("day12", "Day 12 - email verification (tokens read from app console)", sec_day12),
    ("day6", "Day 6  - login / refresh rotation / logout", sec_day6),
    ("day7", "Day 7  - role-based access control", sec_day7),
    ("day8", "Day 8  - ownership & membership", sec_day8),
    ("day9", "Day 9  - member-removal guard & sort validation", sec_day9),
    ("day10", "Day 10 - account lockout", sec_day10),
    ("day13", "Day 13 - password reset", sec_day13),
    ("day11", "Day 11 - rate limiting (runs last)", sec_day11),
]
DEPENDS = {"day12": ["day4"], "day6": ["day4", "day12"], "day7": ["day4", "day12", "day6"],
           "day8": ["day4", "day12", "day6", "day7"], "day9": ["day4", "day12", "day6", "day7", "day8"],
           "day10": ["day4", "day12"], "day13": ["day4", "day12", "day6"]}


# --------------------------------------------------------------------------- orchestration
def preflight(cfg):
    banner("Preflight")
    problems = []
    if not (cfg.project_dir / "pom.xml").exists():
        problems.append(f"pom.xml not found in {cfg.project_dir} (use --project-dir)")
    jv = run(["java", "-version"])
    out(f"  java : {(jv.stderr or jv.stdout).splitlines()[0] if jv.returncode == 0 else red('NOT FOUND')}")
    if jv.returncode != 0:
        problems.append("java not on PATH")
    if not cfg.attach:
        mv = shutil.which("mvn")
        out(f"  mvn  : {mv or red('NOT FOUND')}")
        if not mv:
            problems.append("mvn not on PATH")
        if port_in_use(8080):
            problems.append("port 8080 already in use - stop that process, or use --attach")
    if not cfg.skip_docker:
        dc = docker_compose_cmd()
        out(f"  docker compose : {' '.join(dc) if dc else red('NOT FOUND')}")
        if not dc:
            problems.append("docker compose not available")
        elif run(["docker", "info"], timeout=30).returncode != 0:
            problems.append("Docker daemon not running - start Docker Desktop")
    if problems:
        for p in problems:
            out(red("  x " + p))
        raise SystemExit(2)
    out(green("  all good"))


def start_database(cfg):
    banner("Database (docker compose)")
    dc = docker_compose_cmd()
    if cfg.reset_db:
        out(yellow("  --reset-db: docker compose down -v"))
        run(dc + ["down", "-v"], cwd=cfg.project_dir, timeout=120)
    r = run(dc + ["up", "-d"], cwd=cfg.project_dir, timeout=300)
    out(dim((r.stdout + r.stderr).strip()[-600:]))
    if r.returncode != 0:
        out(red("docker compose up failed"))
        raise SystemExit(2)
    end = time.time() + 90
    while time.time() < end:
        s = run(["docker", "inspect", "-f", "{{.State.Health.Status}}", cfg.pg_container]).stdout.strip()
        if s == "healthy":
            out(green(f"  {cfg.pg_container} is healthy"))
            return
        time.sleep(2)
    lg = run(["docker", "logs", "--tail", "30", cfg.pg_container])
    out(red("Postgres never became healthy. Last container logs:"))
    out(dim((lg.stdout + lg.stderr)[-1500:]))
    raise SystemExit(2)


def main():
    cfg = parse_args()
    if cfg.list:
        for k, title, _ in SECTIONS:
            print(f"{k:8} {title}")
        return 0
    if cfg.no_color or not sys.stdout.isatty():
        Term.color = False
    if IS_WIN:
        os.system("")                     # enable ANSI escapes on Windows 10+ consoles
    cfg.project_dir = Path(cfg.project_dir).resolve()
    cfg.verbose = cfg.verbose or cfg.debug

    stamp = dt.datetime.now().strftime("%Y%m%d-%H%M%S")
    rep = (Path(cfg.reports_dir) if Path(cfg.reports_dir).is_absolute() else cfg.project_dir / cfg.reports_dir) / f"run-{stamp}"
    rep.mkdir(parents=True, exist_ok=True)
    global _log_fh
    _log_fh = open(rep / "console.log", "w", encoding="utf-8")
    out(bold(f"TaskFlow E2E  |  {cfg.project_dir}  |  reports -> {rep}"))

    preflight(cfg)
    if not cfg.skip_docker:
        start_database(cfg)

    app = App(cfg, rep / "app.log")
    http = Http(cfg, app, rep / "http-trace.jsonl")
    started_by_us = False

    def cleanup():
        if started_by_us and not cfg.keep_running:
            out(dim("\nstopping app..."))
            app.stop()
    atexit.register(cleanup)
    signal.signal(signal.SIGINT, lambda *_: (cleanup(), os._exit(130)))

    banner("Application")
    if cfg.attach:
        if not http.probe("/v3/api-docs"):
            out(red(f"--attach given but nothing answers on {cfg.base_url}"))
            return 2
        if cfg.email_log:
            app.attach_tail(cfg.email_log)
        else:
            app.has_log_access = False
            out(yellow("  attached without --email-log: verify / reset steps will be SKIPPED"))
    else:
        app.start()
        started_by_us = True
        out("  compiling + starting (first run can take a minute)...")
        if not app.wait_ready(http, cfg.startup_timeout):
            diagnose(app.lines, "Application failed to start")
            out(dim(f"\nFull output: {rep / 'app.log'}"))
            app.stop()
            return 2
        out(green("  application is up"))

    t = Runner(cfg, http, app)
    only = {s.strip() for s in cfg.only.split(",")} if cfg.only else None
    skip = {s.strip() for s in cfg.skip.split(",")} if cfg.skip else set()
    if only:
        bad = only - {k for k, _, _ in SECTIONS}
        if bad:
            out(red(f"unknown section(s): {bad}  (see --list)"))
            return 2
    selected = [(k, ti, fn) for k, ti, fn in SECTIONS if (not only or k in only) and k not in skip]
    if only:
        need = {d for k, _, _ in selected for d in DEPENDS.get(k, [])}
        if need - {k for k, _, _ in selected}:
            out(yellow(f"  note: {sorted(need - {k for k, _, _ in selected})} are prerequisites of your selection and "
                       "will be run too (they create the users/tokens later sections need)"))
            selected = [(k, ti, fn) for k, ti, fn in SECTIONS if k in {s for s, _, _ in selected} | need]

    t0 = time.time()
    try:
        for key, title, fn in selected:
            banner(title)
            t.section_key = key
            try:
                fn(t)
            except (Fail, Skip) as e:     # a failure outside a step (e.g. setup)
                t.record("SKIP" if isinstance(e, Skip) else "FAIL", f"{key}: section aborted", str(e), debug=True)
    except StopRun:
        out(yellow("\n--fail-fast: stopping at first failure"))

    # ---- summary
    banner("Summary")
    counts = {k: sum(1 for r in t.results if r["status"] == k) for k in ("PASS", "FAIL", "WARN", "SKIP")}
    for r in t.results:
        if r["status"] in ("FAIL", "WARN"):
            col = red if r["status"] == "FAIL" else yellow
            out(f"  {col(r['status'])} [{r['section']}] {r['name']}\n         {dim(r['detail'])}")
    out(f"\n  {green(str(counts['PASS']) + ' passed')}   {red(str(counts['FAIL']) + ' failed')}   "
        f"{yellow(str(counts['WARN']) + ' warnings (probes)')}   {dim(str(counts['SKIP']) + ' skipped')}   "
        f"in {time.time() - t0:.1f}s")
    (rep / "report.json").write_text(json.dumps({"counts": counts, "results": t.results}, indent=2), encoding="utf-8")
    out(dim(f"  artifacts: {rep}  (console.log, app.log, http-trace.jsonl, report.json)"))
    if cfg.keep_running:
        out(cyan(f"\n  --keep-running: app still up -> {cfg.base_url}/swagger-ui.html   (Ctrl+C to stop it)"))
        signal.signal(signal.SIGINT, signal.default_int_handler)
        try:
            while True:
                time.sleep(3600)
        except KeyboardInterrupt:
            pass
        app.stop()
    return 1 if counts["FAIL"] else 0


if __name__ == "__main__":
    sys.exit(main())
