"""Fail-closed report gates and expiring, scoped Trivy exceptions (stdlib only)."""

import datetime as dt
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile


ROOT = Path(__file__).resolve().parents[2]


def prepare(source, destination):
    policy = json.loads(Path(source).read_text())
    if set(policy) != {"vulnerabilities", "secrets"}:
        raise ValueError("Expected vulnerabilities and secrets exception lists")
    output = {}
    for kind, entries in policy.items():
        if not isinstance(entries, list):
            raise ValueError("Exceptions must be lists")
        output[kind] = []
        for entry in entries:
            if not isinstance(entry, dict):
                raise ValueError("Exception must be an object")
            required = {"id", "expired_at", "statement", "owner", "issue"}
            if not required <= entry.keys() or entry.keys() - required - {"paths", "purls"}:
                raise ValueError("Invalid exception fields")
            if any(not isinstance(entry[k], str) or not entry[k].strip() for k in required):
                raise ValueError("Exception metadata must be nonempty strings")
            expiry = dt.date.fromisoformat(entry["expired_at"])
            if expiry.isoformat() != entry["expired_at"]:
                raise ValueError("Expiration must use YYYY-MM-DD")
            if expiry <= dt.datetime.now(dt.timezone.utc).date():
                raise ValueError("Expired exception: remove or review it")
            if not entry["issue"].startswith("https://github.com/"):
                raise ValueError("Exception needs a GitHub tracking issue")
            scopes = [entry.get("paths", []), entry.get("purls", [])]
            if not any(scopes) or any(not isinstance(scope, list) for scope in scopes):
                raise ValueError("Exception needs explicit paths or versioned purls")
            for values in scopes:
                for value in values:
                    if not isinstance(value, str) or not value or any(c in value for c in "*?[]"):
                        raise ValueError("Wildcard/empty exception scopes are forbidden")
            if kind == "secrets" and entry.get("purls"):
                raise ValueError("Secret exceptions must use paths")
            if any(not p.startswith("pkg:") or "@" not in p for p in entry.get("purls", [])):
                raise ValueError("PURLs must include an exact package version")
            generated = {k: v for k, v in entry.items() if k not in {"owner", "issue"}}
            # JSON quotes dates; Trivy needs RFC3339 for a quoted timestamp.
            generated["expired_at"] = entry["expired_at"] + "T00:00:00Z"
            output[kind].append(generated)
    # JSON is valid YAML; the .yaml extension selects Trivy's structured ignore parser.
    Path(destination).write_text(json.dumps(output, indent=2) + "\n")


def summary(title, lines):
    text = f"### {title}\n\n" + "\n".join(lines) + "\n"
    print(text)
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as stream:
            stream.write(text + "\n")


def scan(kind, target, report_dir, ignorefile):
    os.environ.setdefault("TRIVY_CACHE_DIR", str(Path(os.environ.get("RUNNER_TEMP", tempfile.gettempdir())) / "trivy-cache"))
    reports = Path(report_dir)
    reports.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="scheduler-scan-") as temporary:
        raw = Path(temporary) / "result.json"
        target_type = {"image": "image", "jar": "rootfs", "secrets": "fs"}[kind]
        command = ["trivy", target_type, "--quiet",
                   "--timeout", "15m", "--scanners", "secret" if kind == "secrets" else "vuln",
                   "--ignorefile", ignorefile, "--format", "json", "--output", str(raw),
                   "--exit-code", "0"]
        if kind == "secrets":
            command += ["--secret-config", str(ROOT / ".github/security/trivy-secret.yaml")]
        else:
            command += ["--list-all-pkgs"]
        if kind == "image":
            command += ["--image-src", "docker"]
        # Never forward secret diagnostics: they can contain matched values.
        result = subprocess.run(command + [target], stdout=subprocess.DEVNULL,
                                stderr=subprocess.DEVNULL if kind == "secrets" else None)
        if result.returncode:
            raise ValueError(f"{kind} scan failed (exit {result.returncode}); no successful report")
        data = json.loads(raw.read_text())
        if data.get("SchemaVersion") != 2:
            raise ValueError("Unexpected Trivy report schema")
        results = data.get("Results") or []
        if kind == "secrets":
            # Explicit allowlist: no Match, Code, source lines or raw scanner output is retained.
            findings = [{"file": r["Target"], "rule": s["RuleID"], "severity": s["Severity"],
                         "line": s["StartLine"]}
                        for r in results for s in r.get("Secrets", []) or []]
            safe = {"findings": findings}
            blocked = len(findings)
        else:
            safe = data
            findings = [v for r in results for v in r.get("Vulnerabilities", []) or []]
            blocked = sum(v["Severity"] in {"HIGH", "CRITICAL"} for v in findings)
            # A clean report with no dependency extraction must not pass silently.
            packages = [p for r in results for p in r.get("Packages", []) or []]
            if not any(r.get("Type") == "jar" and r.get("Packages") for r in results):
                raise ValueError("No packaged Java dependencies detected")
            if kind == "image" and not any(p["Name"] == "openjdk25-jre-headless" for p in packages):
                raise ValueError("Java runtime missing from container package inventory")
        (reports / f"{kind}.json").write_text(json.dumps(safe, indent=2) + "\n")
        summary(f"Trivy {kind}", [f"Findings: {len(findings)}; blocking: {blocked}.",
                                  f"Report: `{kind}.json` in the security report artifact."])
        return bool(blocked)


def codeql(report_dir):
    paths = list(Path(report_dir).glob("*.sarif"))
    if not paths:
        raise ValueError("CodeQL SARIF report missing")
    blocked = 0
    total = 0
    kotlin_lines = 0
    for path in paths:
        data = json.loads(path.read_text())
        if data.get("version") != "2.1.0" or not data.get("runs"):
            raise ValueError("Unexpected CodeQL SARIF schema")
        for run in data["runs"]:
            if any(i.get("executionSuccessful") is False for i in run.get("invocations", [])):
                raise ValueError("CodeQL reported unsuccessful analysis")
            kotlin_lines += sum(m["value"] for m in run.get("properties", {}).get("metricResults", [])
                                if m.get("ruleId") == "java/summary/lines-of-code-kotlin")
            # CodeQL can also reference rule descriptors from tool extensions.
            drivers = [run["tool"]["driver"]] + run["tool"].get("extensions", [])
            rules = {r["id"]: r for driver in drivers for r in driver.get("rules", [])}
            for result in run.get("results", []):
                total += 1
                rule = rules[result["ruleId"]]
                score = rule.get("properties", {}).get("security-severity", "0")
                blocked += float(score) >= 7.0
    if kotlin_lines <= 0:
        raise ValueError("CodeQL reported no extracted Kotlin lines")
    summary("CodeQL Java/Kotlin", [f"Kotlin lines analyzed: {kotlin_lines}.", f"Findings: {total}; HIGH/CRITICAL: {blocked}.",
                                   "SARIF is retained in the application security artifact and GitHub code scanning."])
    return bool(blocked)


def main():
    command, *args = sys.argv[1:]
    if command == "prepare":
        prepare(*args)
        return 0
    if command == "scan":
        return int(scan(*args))
    if command == "codeql":
        return int(codeql(*args))
    raise ValueError("Unknown command")


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (ValueError, KeyError, TypeError, OSError) as error:
        # Do not print input data or scanner output: it may contain secrets.
        summary("Security check failed", ["Scanner execution or policy validation failed; this is not a clean scan."])
        print(f"Security check failed: {type(error).__name__}: {error}", file=sys.stderr)
        sys.exit(1)
