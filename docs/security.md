# Backend security scanning

## Coverage and gates

[Security workflow](../.github/workflows/security.yml) runs independently of `Build & Test`.
No security job needs PostgreSQL, Redis, production credentials, or integration tests.

| Check | Pull requests to main/develop | Push to main, daily, manual | Coverage |
| --- | --- | --- | --- |
| `Secret security` | Yes | Yes | Trivy secret rules on the tracked checkout, including Markdown |
| `Application security` | Yes | Yes | CodeQL Java/Kotlin and Trivy on the compiled Spring Boot JAR |
| `Container security` | Skipped | Yes | Production Dockerfile, final image OS/JRE packages and bundled Java dependencies |
| `Dependency submission` | Skipped | Yes | Resolved Gradle dependency graph for Dependabot Alerts |

Dependency submission is restricted to main, including manual runs.
The daily run is at 05:23 UTC against the default branch. Trivy downloads current
vulnerability and Java identification databases; there is no frozen database or
`skip-db-update` option. Thus new disclosures can fail without application changes.
GitHub may delay schedules or disable them after prolonged repository inactivity;
monitor scheduled runs and use workflow dispatch when necessary.

PR checks avoid integration tests and image builds. One uncached production compilation
serves both CodeQL extraction and JAR scanning. CodeQL uses `java-kotlin`, **manual** build
mode, initialization before compilation, disabled incremental/build/configuration caches,
and in-process Kotlin compilation with sufficient Gradle heap. `build-mode: none` does
not analyze Kotlin. Only production sources are compiled; test-source SAST is not included.
The SARIF gate also requires a positive Kotlin lines-of-code metric; Java-only or empty
extraction cannot silently pass. The extended security suite is used once; do not also enable CodeQL default setup.

Trivy uses `rootfs` mode on `build/libs` (filesystem mode disables JAR analysis), including `BOOT-INF/lib` nested JARs: both direct and transitive
runtime dependencies actually shipped in the artifact are included. Source-only Gradle
manifest scanning is insufficient and is not used. Reports include the package inventory;
a missing Java inventory fails the gate rather than appearing clean. The submitted Gradle
graph also resolves development/test/build dependencies; it improves Dependabot Alerts
coverage beyond what the version catalog alone exposes. Those dependencies are not all
bundled in the production artifact, and are not all gated by the JAR scan.

All non-excepted HIGH/CRITICAL Trivy vulnerabilities block, **including unfixed findings**.
MEDIUM/LOW/UNKNOWN remain visible in reports. There is no blanket `ignore-unfixed` or
reachability assumption. CodeQL's local SARIF gate blocks security scores >= 7.0 (HIGH
and CRITICAL), regardless of SARIF's display level. Missing/invalid reports and scanner
errors also fail. Every detected secret blocks regardless of severity.

## Reports and repository settings

Each scanner writes a job summary. Actions artifacts retain JSON package inventories,
vulnerability reports, CodeQL SARIF, sanitized secret locations, and the scanned image
identity for 14 days, including on finding-related failures. CodeQL also uploads to
**Security → Code scanning**. Trivy JSON is kept in Actions rather than uploading duplicate
JAR and container alerts to code scanning. Dependency submission retains its graph artifact.

On 2026-10-26 the GitHub API reported this public repository had Dependabot Alerts and
security updates enabled, CodeQL default setup not configured, and **secret scanning and
push protection disabled**. No existing repository secret scanner was present. The new
Trivy job provides a repository-controlled baseline. Administrators should enable GitHub
secret scanning and push protection (and non-provider patterns if available) in repository
security settings. These settings cannot be established by a committed workflow.

Set branch rules to require `Build & Test`, `Secret security`, and `Application security`.
`Container security` and `Dependency submission` run on main/daily, so do not require them
on PRs. Treat a failed main container scan as a release blocker. Enable notification/watch
preferences for security workflow failures and Dependabot/code scanning alerts. Committing
a workflow does not configure branch protection or a deployment gate.

Actions use immutable commit SHAs, read-only contents by default, no persisted checkout
credentials, and bounded job durations. Only CodeQL gets `security-events: write`; only
the main-branch-only dependency submission job gets `contents: write`. Fork PRs use `pull_request`,
never `pull_request_target`, and receive no repository secrets. No write-permission job
executes PR code via a privileged follow-up workflow.

Secret scanning inspects the current tracked tree, not Git history or deleted intermediate
commits. GitHub's native scanning provides complementary historical/push-time coverage.
Do not upload raw secret scanner output, matched values, `.env`, build contexts, or container
exports as report artifacts. The wrapper discards scanner stdout/stderr, parses secret JSON
in a temporary directory, emits only file/rule/severity/line, and deletes the raw report.
If scanning fails, reproduce locally; raw diagnostics are intentionally withheld. Never
paste a suspected credential into an issue or exception statement. Revoke/rotate exposed
credentials first; deleting the file is insufficient.

## Container identity and reproducibility

Both Dockerfile base images use readable tags plus immutable multi-platform digest pins,
maintained by the existing Docker Dependabot configuration. The build now uses the
repository's checksum-verified Gradle wrapper instead of a separately versioned Gradle
installation, and no longer suppresses dependency-resolution failures.

The final image uses Alpine's **APK-managed `openjdk25-jre-headless`**. This deliberately
replaces the tarball-installed Temurin JRE: Trivy can inventory the runtime and match it
to Alpine security advisories. The gate requires this JRE package to be present. OS and
Java library vulnerabilities are scanned together. Keep the JRE headless and Java 25;
validate runtime/provider behavior when updating Alpine/OpenJDK. The application still
runs as an unprivileged user, with the same port, Java entrypoint and Java tool options.

APK repositories provide current Java/OS dependency packages at build time, so digest
pins alone do **not** promise byte-identical rebuilds. Gradle versions/BOMs are fixed, but
there are no dependency lockfiles or complete dependency verification metadata. Scans use
fresh advisory data by design. Dependabot updates base/action/Gradle references; review the
explicit Trivy binary version and CodeQL/compiler compatibility when updating tooling.
CodeQL tools are linked to the pinned Action release rather than nightly tools.

CI builds the production Dockerfile and scans its immutable **local image ID**, recording
that ID, source commit and linux/amd64 platform in the container artifact. It does not
publish or deploy. Dokploy currently rebuilds from source; its resulting image can differ
because of platform, repository updates and build metadata. The scanned image is therefore
a production candidate, not proof about the running deployment. For exact-image promotion,
build once, scan, push that same image, and deploy the registry digest; configure Dokploy
to consume it instead of rebuilding. Until then, scan the actual deployed image ID with
the same policy before release. Other CPU architectures and Compose's PostgreSQL/Redis
images are outside this backend-image scan. Database/provider coverage is imperfect;
review upstream Java/Alpine advisories even when there are no scanner findings.

## Triage and remediation

1. Open the failed Actions summary and download the matching report. Distinguish a scanner
   failure from findings; neither is a successful check. For CodeQL, follow the source/sink
   path in code scanning. Never dismiss a finding solely to make CI green.
2. For Java findings, locate the introducing dependency with
   `./gradlew dependencyInsight --dependency <module> --configuration runtimeClasspath`.
   Prefer updating the Spring Boot BOM or the introducing direct library; use a narrowly
   justified version constraint only if the compatible upstream update is unavailable.
   Rebuild the artifact, rescan, and run the relevant tests/full build and coverage checks.
3. For OS/JRE findings, refresh the pinned base digest and rebuild against updated Alpine
   packages. If no fix is published, assess exposure/mitigations and track upstream progress.
   For SAST, fix the unsafe data flow and verify with CodeQL plus regression coverage.
4. Review the new report and inventory before release. A clean scan is not a penetration
   test, proof of exploitability/non-exploitability, or a substitute for application tests.

## Scoped exceptions

[trivy-exceptions.json](../.github/security/trivy-exceptions.json) starts empty. Each entry
requires `id`, `expired_at` (YYYY-MM-DD), `statement`, `owner`, `issue` (GitHub issue URL),
and exact `paths` and/or versioned package `purls`. Wildcards, missing fields, unknown keys,
and expired entries fail CI. Expiry is at the start of that UTC date. Use the narrowest
package/version or file path from the report; a CVE ID alone is forbidden. Secret exceptions
use exact file paths and are only appropriate for confirmed synthetic fixtures.

Example structure (do not add without reviewing the actual finding):

```json
{
  "vulnerabilities": [{
    "id": "CVE-2099-12345",
    "purls": ["pkg:maven/example/library@1.0"],
    "expired_at": "2099-01-15",
    "statement": "Document exposure evidence, mitigation and removal condition here.",
    "owner": "responsible-maintainer",
    "issue": "https://github.com/s24407-pj/scheduler/issues/123"
  }],
  "secrets": []
}
```

Use short review windows (normally <= 30 days). A maintainer must review the evidence,
owner, scope and removal condition in the PR. CI validates and translates this policy to
Trivy's structured ignore format; developers must not add a global ignore file or disable
scanners/rules. Keep accepted findings in their tracking issues, remove exceptions when
fixed, and review before expiry. The local CodeQL gate has no exception mechanism; fix
HIGH/CRITICAL SAST findings rather than relying on GitHub alert dismissal to bypass it.

## Local validation

With JDK 25, Python 3 and the workflow's Trivy version on PATH:

```bash
python3 -m unittest discover -s .github/security -v
python3 .github/security/security.py prepare .github/security/trivy-exceptions.json /tmp/scheduler-trivy-ignore.yaml
./gradlew bootJar --no-daemon
python3 .github/security/security.py scan jar build/libs security-reports /tmp/scheduler-trivy-ignore.yaml
docker build -t scheduler-security:local .
python3 .github/security/security.py scan image scheduler-security:local security-reports /tmp/scheduler-trivy-ignore.yaml
python3 .github/security/security.py scan secrets . security-reports /tmp/scheduler-trivy-ignore.yaml
```

CI secrets use `git archive HEAD` so local untracked credentials/build output are excluded.
The local `.` example scans your working tree; raw findings are still discarded. Validate
workflow edits with `actionlint .github/workflows/*.yml`. Application build/tests/coverage
remain in the existing CI workflow unchanged.

Tooling references: [CodeQL build modes](https://docs.github.com/en/code-security/reference/code-scanning/codeql/build-options-for-compiled-languages),
[CodeQL compiler support](https://codeql.github.com/docs/codeql-overview/supported-languages-and-frameworks/),
[Trivy Java archives](https://trivy.dev/docs/latest/guide/coverage/language/java/),
[Trivy structured ignores](https://trivy.dev/docs/latest/configuration/filtering/),
and [Gradle dependency submission](https://github.com/gradle/actions/blob/main/docs/dependency-submission.md).
