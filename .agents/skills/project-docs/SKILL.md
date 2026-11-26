---
name: project-docs
description: Writes and updates a project's core developer documentation (README, contributing guide, architecture decision records and changelog entries) so a newcomer can understand, run and change the project. Use when creating or improving a README, documenting how to set up or run a project, recording an architecture or design decision, writing a changelog entry, or when docs are out of date after a change.
metadata:
  skillforge-domain: writing
  skillforge-version: "0.1.0"
---

# Project docs

Documentation earns its keep when a newcomer can go from clone to a working
change without asking anyone. Write for that reader, verify every command,
and keep docs next to the code they describe.

## 1. Find out what exists

1. Read the current README, `docs/`, CONTRIBUTING, existing decision records
   and CHANGELOG. Note what is missing, wrong or duplicated.
2. Learn how the project really works from the code and config: how it is
   installed, built, tested, configured and deployed (package scripts,
   Makefile, CI workflows, Dockerfiles, `.env.example`).

## 2. README

Use [the README template](assets/README-template.md) as the outline and drop
sections that don't apply. In order:

1. **Name and one-paragraph summary**: what it is, who it is for, what
   problem it solves.
2. **Quick start**: prerequisites with versions, then the shortest
   copy-pasteable path from clone to running.
3. **Usage**: the two or three most common tasks with real examples.
4. **Development**: running tests, linting, project layout in a few lines.
5. **Configuration**: environment variables and settings, with defaults.
6. **Links**: deeper docs, contributing, license.

Keep it scannable: short paragraphs, code blocks for every command, no
marketing language.

## 3. Architecture decision records (ADRs)

Record a decision when it is hard to reverse or will puzzle someone later
(a database choice, an auth approach, a module boundary). Save as
`docs/decisions/NNNN-short-title.md` (or the project's existing location):

```markdown
# NNNN. Title in the form of the decision

Date: YYYY-MM-DD
Status: proposed | accepted | superseded by NNNN

## Context
The forces at play: requirements, constraints, options considered.

## Decision
What we chose, stated plainly.

## Consequences
What becomes easier, what becomes harder, and follow-up work.
```

Never edit an accepted ADR's decision; write a new one that supersedes it.

## 4. Changelog

If the project keeps a CHANGELOG, add entries under an "Unreleased" heading
grouped as Added, Changed, Deprecated, Removed, Fixed, Security. Write for
users of the project, not for its developers: describe the effect, not the
implementation.

## 5. Verify

1. Run every command you documented, in a clean checkout where possible.
2. Check every link and file path.
3. Make sure examples match the current API and output.
4. Remove or update anything the code no longer supports.

## Style

- Second person and imperative ("Run `make test`").
- Explain *why* for anything surprising; don't restate obvious code.
- Prefer one accurate page over several stale ones; delete outdated docs
  rather than leaving them to mislead.
