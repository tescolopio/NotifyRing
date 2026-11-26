---
name: dependency-upgrades
description: Upgrades project dependencies safely by reading changelogs for breaking changes, updating one coherent group at a time, fixing breakage, and verifying with tests and lockfiles. Use when bumping a library or framework version, fixing a vulnerable dependency, handling a Dependabot or Renovate pull request, or when asked to "update dependencies" or "upgrade to the latest version".
metadata:
  skillforge-domain: engineering
  skillforge-version: "0.1.0"
---

# Dependency upgrades

Upgrades break things through changed behaviour more often than through
compile errors. Read before you bump, change one thing at a time, and prove
it with the test suite.

## 1. Take stock

1. Identify the package manager(s) and lockfiles (`package-lock.json`,
   `pnpm-lock.yaml`, `poetry.lock`, `uv.lock`, `Cargo.lock`, `go.sum`,
   `Gemfile.lock` and so on). Always use the project's tool, never a
   different one.
2. List what is outdated (`npm outdated`, `pip list --outdated`,
   `cargo outdated`, `go list -m -u all`) and what has known advisories
   (`npm audit`, `pip-audit`, `osv-scanner`, `cargo audit`).
3. Make sure the test suite passes **before** upgrading, so new failures are
   attributable.

## 2. Plan the order

- Security fixes first, then patch, then minor, then major versions.
- Group packages that must move together (a framework and its plugins,
  `react` and `react-dom`, a library and its type definitions); otherwise
  upgrade one package per commit.
- For a major version, read the changelog, release notes and migration
  guide between the current and target versions. List each breaking change
  and whether this project is affected (search for the changed APIs).

## 3. Upgrade

1. Change the version with the package manager so the lockfile updates
   (`npm install pkg@x.y.z`, `uv add pkg==x.y.z`, `cargo add pkg@x.y.z`).
   Make sure the manifest's version requirement changes too, not only the
   lockfile: `cargo update -p pkg`, for example, stays within the existing
   requirement. Do not hand-edit lockfiles.
2. Respect existing pinning style (exact pins vs ranges).
3. Apply the migration steps you listed; prefer the library's codemods when
   provided.
4. Fix deprecation warnings that the upgrade introduces when cheap; list the
   rest.

## 4. Verify

1. Install from a clean state, then build, lint, type-check and run the
   full test suite.
2. Exercise the areas the changelog says changed, even when tests pass.
3. Check the lockfile diff: only expected packages moved; no surprise new
   transitive dependencies from unknown publishers; no install scripts you
   did not expect.
4. Re-run the audit; the targeted advisories should be gone.

## 5. Report

For each upgrade: from → to, breaking changes and how they were handled,
verification done, and anything deferred (with the reason). If an upgrade
cannot be completed, leave the project on the old version and explain the
blocker rather than shipping a half-migrated state.

## Cautions

- Never upgrade everything at once with no plan; a failure then has
  dozens of suspects.
- Don't loosen a pin just to make resolution succeed without explaining why.
- Treat a new major version of a runtime (Node, Python, Java) as its own
  project: CI images, Docker files and deployment config all change too.
