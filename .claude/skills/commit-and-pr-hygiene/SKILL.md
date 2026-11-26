---
name: commit-and-pr-hygiene
description: Prepares clean commits and pull requests, with focused changes, clear commit messages, and a PR description that lets a reviewer understand and verify the change quickly. Use when committing work, writing or fixing a commit message, splitting a messy change into commits, opening or describing a pull request, or responding to "write the PR description".
metadata:
  skillforge-domain: delivery
  skillforge-version: "0.1.0"
---

# Commit and pull request hygiene

Reviewers and future maintainers read history to answer "what changed and
why?". Make each commit and PR answer that on its own.

## 1. Before committing

1. Review your own diff (`git diff --staged`) line by line. Remove debug
   output, commented-out code, stray formatting changes and unrelated edits.
2. Check for secrets, credentials, large binaries and generated files. Never
   commit `.env` files or keys; if one was committed, rotate it, since
   history keeps it.
3. Run the project's fast checks (formatter, linter, type checker, the
   affected tests).

## 2. Shape the commits

- One logical change per commit: a refactor, then the feature that uses it;
  not both mixed together. Each commit should build and pass tests.
- Stage selectively (`git add -p`) to separate concerns that ended up in
  the same files.
- Follow the repository's convention. Look at `git log` first: if it uses
  Conventional Commits (`feat:`, `fix:`), keep using them; if not, don't
  introduce them.

## 3. Write the commit message

```
Short imperative summary, about 50 characters

Why the change was needed and what it does, wrapped at 72 columns.
Mention the approach when it is not obvious, and anything a reader
might otherwise trip over (behaviour changes, migrations, follow-ups).

Refs #123
```

- Summary line: imperative mood ("Fix", "Add", not "Fixed", "Adds"), no
  trailing period, specific ("Fix crash when cart is empty", not "Fix bug").
- Body: the *why*. The diff already shows the *what*.

## 4. Open the pull request

1. Use the repository's PR template if it has one; fill every section.
2. Title: like a commit summary, describing the whole PR.
3. Description, in this order:
   - **What and why**: the problem and the outcome, in two or three
     sentences. Link the issue.
   - **Changes**: the notable parts, grouped, not every file.
   - **How to verify**: commands run and their results; manual steps if
     needed; screenshots for UI changes.
   - **Risks and follow-ups**: migrations, flags, breaking changes, things
     deliberately left out.
4. Keep PRs small enough to review in one sitting (a few hundred changed
   lines as a guide). Split large work into a stack of PRs that each stand
   alone.
5. Mark it as a draft while it is not ready, and say what is missing.

## 5. During review

- Answer every comment: fix it, or explain why not.
- Push fixes as new commits while review is in progress so reviewers can
  see what changed; squash only if the project asks for it.
- Never rewrite history on a branch someone else is working on.
