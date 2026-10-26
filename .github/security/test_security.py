"""Regression tests for security gates, exception scope and report redaction."""

import contextlib
import datetime as dt
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import security


class SecurityTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)

    def exception(self, **overrides):
        entry = dict(id="CVE-2099-12345", expired_at=(dt.date.today() + dt.timedelta(days=7)).isoformat(),
                     statement="Not reachable; evidence in tracking issue", owner="maintainer",
                     issue="https://github.com/example/project/issues/1",
                     purls=["pkg:maven/example/library@1.0"])
        entry.update(overrides)
        return entry

    def prepare(self, entries):
        source = self.root / "exceptions.json"
        destination = self.root / "ignore.yaml"
        source.write_text(json.dumps({"vulnerabilities": entries, "secrets": []}))
        security.prepare(source, destination)
        return json.loads(destination.read_text())

    def test_exception_preserves_scope_and_expiration(self):
        prepared = self.prepare([self.exception()])["vulnerabilities"][0]
        self.assertEqual(prepared["purls"], ["pkg:maven/example/library@1.0"])
        self.assertEqual(prepared["expired_at"], self.exception()["expired_at"] + "T00:00:00Z")
        self.assertNotIn("owner", prepared)

    def test_invalid_exceptions_fail_closed(self):
        invalid = [dict(expired_at="2000-01-01"), dict(purls=[]), dict(purls=["pkg:maven/example/library"]),
                   dict(purls=["pkg:maven/example/library@*"]), dict(owner=""), dict(expire_at="2099-01-01")]
        for overrides in invalid:
            with self.subTest(overrides=overrides), self.assertRaises(ValueError):
                self.prepare([self.exception(**overrides)])

    def scan(self, kind, data, exit_code=0):
        def trivy(command, **kwargs):
            Path(command[command.index("--output") + 1]).write_text(json.dumps(data))
            self.assertEqual(command[1], {"jar": "rootfs", "image": "image", "secrets": "fs"}[kind])
            self.assertEqual(kwargs["stdout"], security.subprocess.DEVNULL)
            self.assertEqual(kwargs["stderr"], security.subprocess.DEVNULL if kind == "secrets" else None)
            return security.subprocess.CompletedProcess(command, exit_code)
        with patch.object(security.subprocess, "run", side_effect=trivy), contextlib.redirect_stdout(io.StringIO()):
            return security.scan(kind, ".", self.root, "ignore.yaml")

    def test_secret_report_cannot_retain_matched_values(self):
        sensitive = "synthetic-credential-value"
        data = {"SchemaVersion": 2, "Results": [{"Target": "config.txt", "Secrets": [
            {"RuleID": "test-rule", "Severity": "LOW", "StartLine": 2, "Match": sensitive,
             "Code": {"Lines": [sensitive]}}]}]}
        self.assertTrue(self.scan("secrets", data))
        report = (self.root / "secrets.json").read_text()
        self.assertNotIn(sensitive, report)
        self.assertIn("config.txt", report)

    def test_clean_secret_report_can_omit_results(self):
        self.assertFalse(self.scan("secrets", {"SchemaVersion": 2}))

    def test_scanner_error_and_missing_java_inventory_fail_closed(self):
        with self.assertRaises(ValueError):
            self.scan("jar", {"SchemaVersion": 2})
        with self.assertRaises(ValueError):
            self.scan("secrets", {"SchemaVersion": 2}, exit_code=2)

    def test_vulnerable_packaged_dependency_blocks_even_without_fix(self):
        data = {"SchemaVersion": 2, "Results": [{"Type": "jar", "Packages": [{"Name": "library"}],
                "Vulnerabilities": [{"Severity": "HIGH", "FixedVersion": ""}]}]}
        self.assertTrue(self.scan("jar", data))
        data["Results"][0]["Vulnerabilities"][0]["Severity"] = "MEDIUM"
        self.assertFalse(self.scan("jar", data))
        with self.assertRaises(ValueError):
            self.scan("image", data)
        data["Results"].append({"Type": "alpine", "Packages": [{"Name": "openjdk25-jre-headless"}]})
        self.assertFalse(self.scan("image", data))

    def test_codeql_security_score_controls_gate(self):
        path = self.root / "java.sarif"
        data = {"version": "2.1.0", "runs": [{"tool": {"driver": {"rules": [
            {"id": "test/rule", "properties": {"security-severity": "7.5"}}]}},
            "properties": {"metricResults": [{"ruleId": "java/summary/lines-of-code-kotlin", "value": 100}]},
            "results": [{"ruleId": "test/rule", "level": "warning"}]}]}
        path.write_text(json.dumps(data))
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertTrue(security.codeql(self.root))
            data["runs"][0]["tool"]["driver"]["rules"][0]["properties"]["security-severity"] = "6.9"
            path.write_text(json.dumps(data))
            self.assertFalse(security.codeql(self.root))
            data["runs"][0]["properties"]["metricResults"] = []
            path.write_text(json.dumps(data))
            with self.assertRaises(ValueError):
                security.codeql(self.root)

    def test_missing_sast_report_is_not_a_clean_scan(self):
        with self.assertRaises(ValueError):
            security.codeql(self.root)


if __name__ == "__main__":
    unittest.main()
