---
name: code-review
description: Reviews a code change (a diff, branch, commit or pull request) for bugs, security problems, missing tests and maintainability issues, and reports verified findings ranked by severity. Use when asked to review code, check a pull request or diff before merging, look over someone's changes, or answer "is this safe to merge?".
metadata:
  skillforge-domain: engineering
  skillforge-version: "0.1.0"
---

# Code review

Find the problems that matter in a change, prove each one, and report them so
the author can act without a follow-up question. A short list of real bugs is
worth more than a long list of opinions.

## 1. Establish scope

1. Identify exactly what changed: the diff against the merge base, not the
   whole file. For a pull request, read its description and linked issue to
   learn the intent.
2. Read enough surrounding code to understand each changed function: its
   callers, the data it receives, and what it returns or mutates.
3. Note the project's own rules (CONTRIBUTING, AGENTS.md, CLAUDE.md, linters,
   existing patterns). Breaking a written project rule is a finding.

## 2. Look for defects, most severe first

Work through [the review checklist](references/checklist.md). In order:

1. **Correctness**: wrong logic, off-by-one, inverted conditions, unhandled
   `null`/empty/error cases, broken invariants, race conditions, resource
   leaks, behaviour that contradicts the stated intent.
2. **Security**: untrusted input reaching queries, shells, file paths,
   templates or deserializers; missing authorization; secrets in code or
   logs. For a deep pass, use the `security-review` skill.
3. **Compatibility**: changed public APIs, schemas, config or CLI flags
   without migration; data written in a format old code cannot read.
4. **Tests**: new behaviour without a test, tests that cannot fail, or tests
   changed to match a bug.
5. **Maintainability**: duplication of an existing helper, misleading names,
   dead code, complexity the change did not need. Report these only when
   they will cost the next reader real time.

Skip style points a formatter or linter would catch.

## 3. Verify every finding

Before reporting, confirm each one:

- Trace a concrete input from entry point to the faulty line. If you cannot
  name an input that triggers it, it is not a bug yet.
- Check whether the code elsewhere already guards the case (a caller
  validates, a type forbids it, a test covers it).
- Run the relevant test, or write a minimal failing one, when you can.

Drop anything you could not confirm, or report it separately as a question.

## 4. Report

One entry per finding, most severe first:

```
[severity] path/to/file.py:42 — one-line statement of the defect
Trigger: the concrete input or sequence that causes it
Impact: what goes wrong for the user or system
Fix: the specific change (a code suggestion when it is short)
```

Severity: **blocker** (data loss, security hole, crash on a common path,
wrong results), **major** (bug on a plausible path, missing test for new
logic), **minor** (edge case, maintainability), **nit** (optional; omit
unless asked).

End with a one-line verdict: *ready to merge*, *merge after fixing the
blockers*, or *needs rework*, and why. If you found nothing, say what you
checked rather than inventing findings.

## Edge cases

- **Huge diffs**: review the riskiest files first (auth, data writes,
  migrations, concurrency) and say which files you did not review.
- **Generated or vendored files**: skip them and say so.
- **Your own change**: review it with the same steps; do not assume intent
  matches implementation.
