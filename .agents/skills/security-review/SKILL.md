---
name: security-review
description: Reviews code or a change for exploitable security vulnerabilities such as injection, broken access control, unsafe deserialization, secret leaks and risky dependencies, and reports only findings with a concrete path to impact (attacker-controlled input reaching a sink, or a sensitive asset such as a live secret exposed to people who should not have it). Use when asked for a security review or audit, before merging changes to authentication, authorization, input handling, file or network access, or when the user asks "is this secure?".
metadata:
  skillforge-domain: security
  skillforge-version: "0.1.0"
---

# Security review

Find vulnerabilities an attacker could actually exploit, prove the path, and
give a fix. A false alarm costs trust; a missed injection costs much more, so
be thorough in looking and strict in reporting.

## 1. Map the attack surface

For the code in scope, list:

- **Entry points** that accept outside data: HTTP handlers, CLI arguments,
  message consumers, file and upload parsers, webhooks, environment and
  config read at runtime.
- **Trust boundaries**: where data crosses from user, network or another
  service into this code, and where this code calls out.
- **Sensitive sinks**: database queries, shell and process execution, file
  paths, outbound URLs, HTML/template rendering, deserializers, `eval`,
  crypto, logging, and anything that grants access.
- **Assets**: credentials, personal data, money, admin actions.

## 2. Trace sources to sinks

For each entry point, follow the data to every sensitive sink it reaches
and check each step against [the vulnerability classes](references/vulnerability-classes.md).
At each step ask: is it validated, encoded, parameterized or authorized
*here*, for *this* sink?

Pay most attention to code that was changed, code that handles
authentication or authorization, and code that builds strings for another
interpreter (SQL, shell, HTML, paths, URLs, regex, YAML).

## 3. Confirm before reporting

A finding needs all three:

1. **Source**: attacker-controlled input, and who controls it; or, for an
   exposure, the sensitive asset (a live credential, private key, personal
   data) and who can reach it.
2. **Path**: the concrete call chain with file and line numbers, with no
   effective check along the way; or, for an exposure, where the asset ends
   up (a public client bundle, a log, a committed file, an API response).
3. **Impact**: what the attacker gains (read other users' data, run
   commands, escalate privilege, deny service, use the leaked credential).

If one is missing, either find it or move the item to "hardening
suggestions". Do not report a theoretical sink without a reachable source.

## 4. Report

Most severe first:

```
[critical|high|medium|low] Title (class, e.g. SQL injection)
Location: path/to/file.py:120 (entry: POST /api/search)
Path: request.args["q"] -> build_query() -> cursor.execute(f"...{q}...")
Impact: any visitor can read every table
Fix: use a parameterized query: cursor.execute("... WHERE name = %s", (q,))
```

Then list hardening suggestions separately and briefly, and state what was
in scope and what was not reviewed.

## Rules

- Never run exploits against systems you do not own or are not authorized
  to test; demonstrate with a unit test or a local reproduction.
- Never paste real secrets you find into the report; give the file and line
  and recommend rotating them.
- Prefer the framework's built-in protection (ORM parameters, template
  auto-escaping, CSRF middleware) over hand-written sanitizing.
- Check new or updated dependencies: pinned versions, expected publisher,
  known advisories (`npm audit`, `pip-audit`, `osv-scanner` when available).
