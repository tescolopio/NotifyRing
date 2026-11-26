---
name: writing-tests
description: Writes focused automated tests that fail for the right reason and pass for the right reason, using the project's existing test framework and conventions. Use when adding tests for new or changed code, reproducing a bug with a failing test, raising coverage of a risky module, or when asked to "add tests" or "make sure this is tested".
metadata:
  skillforge-domain: engineering
  skillforge-version: "0.1.0"
---

# Writing tests

A good test pins down one behaviour, fails clearly when that behaviour
breaks, and survives refactoring that keeps the behaviour. Match the project;
don't import a new style.

## 1. Learn the project's testing setup

Before writing anything, find out:

- The framework and runner (look at existing tests, `package.json`,
  `pyproject.toml`, `Makefile`, CI config) and the exact command to run one
  test file.
- Where tests live and how they are named.
- Existing helpers, fixtures, factories and fakes. Reuse them.
- Whether tests may touch the network, a database or the file system, and
  how the project isolates them.

Run the existing suite (or the relevant file) once so you know its baseline.

## 2. Decide what to test

List the behaviours the code promises, not its lines:

1. The main success path.
2. Each documented error or rejection.
3. Boundaries: empty, one, many; zero and negative; maximum sizes; unusual
   characters; time zones and dates when relevant.
4. Any bug being fixed: reproduce it first (see below).

Prefer the lowest level that exercises the behaviour for real: a unit test
for pure logic, an integration test when the value is in how parts connect.
Mock only what is slow, non-deterministic or external (network, clock,
randomness, third-party APIs), never the code under test.

## 3. Write each test

- One behaviour per test, named for it: `rejects_expired_token`, not
  `test_auth_3`.
- Arrange, act, assert, with only the setup this test needs.
- Assert on outcomes the caller can observe (return values, state changes,
  emitted events, error types and messages), not on private internals.
- Make failures readable: assert the specific value, not `assert result`.
- Keep tests deterministic: fix seeds, freeze time, avoid sleeps, don't
  depend on test order or on other tests' data.

## 4. Prove the test can fail

For every new test, confirm it fails when the behaviour is broken:

- For a bug fix, write the test first and watch it fail on the unfixed code.
- For new code, temporarily break the line under test (flip a condition,
  return early) and confirm the test fails, then restore it.

A test that has never failed has not been shown to test anything.

## 5. Finish

1. Run the new tests and the surrounding suite; all must pass.
2. Run the project's linter or type checker on the test files if it has one.
3. Report what is now covered, how you showed each test can fail, and any
   behaviour you chose not to test and why.

## Don'ts

- Don't change production code just to make it easier to test without
  saying so; propose the refactor instead.
- Don't loosen, skip or delete an existing failing test to get green.
- Don't snapshot large outputs when a few precise assertions would do.
