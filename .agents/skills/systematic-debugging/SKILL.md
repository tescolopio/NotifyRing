---
name: systematic-debugging
description: Finds the root cause of a bug, failing test, crash or wrong output by reproducing it, narrowing it down with evidence, and fixing the cause rather than the symptom. Use when something is broken or behaves unexpectedly, a test or CI job fails, an error or stack trace needs explaining, or a previous fix attempt did not work.
metadata:
  skillforge-domain: engineering
  skillforge-version: "0.1.0"
---

# Systematic debugging

Guessing and patching wastes time and hides the real fault. Work from
evidence: reproduce, narrow, explain, fix, prove.

## 1. Reproduce

1. Write down the exact symptom: the error text, wrong value, or behaviour,
   and what was expected instead.
2. Find the shortest reliable way to trigger it: one command, one test, one
   request. Note versions, inputs and environment.
3. If you cannot reproduce it, gather more data (full logs, exact inputs,
   environment differences) before changing any code.

## 2. Read the evidence

- Read the **whole** error and stack trace. The first frame in project code
  is usually more useful than the last line.
- Check what changed recently: `git log`, `git diff`, dependency updates,
  config and environment changes. If it worked before, `git bisect` between
  a good and bad commit is often the fastest route.
- Compare a working case with the failing one and list every difference.

## 3. Narrow it down

Form one hypothesis at a time and test it with the cheapest experiment that
could prove it wrong:

- Add temporary logging or a debugger breakpoint at the boundary between
  "data is still right" and "data is wrong", then move the boundary.
- Shrink the input until removing anything more makes the bug disappear.
- Swap one component at a time (a mock for a service, a known-good input).

Write down each hypothesis and its result. If an experiment surprises you,
your mental model is wrong somewhere; find out where before going on.

## 4. Fix the root cause

1. State the cause in one sentence: *X happens because Y*. If you can't,
   you haven't found it yet.
2. Add a test that reproduces the bug and fails (see the `writing-tests`
   skill).
3. Make the smallest change that removes the cause. Don't catch-and-ignore
   the error, special-case the failing input, or add a retry unless the cause
   really is transient.
4. Search for the same mistake elsewhere in the code and fix those too, or
   list them.

## 5. Prove it

1. The new test passes; the original reproduction no longer fails.
2. The surrounding test suite still passes.
3. Remove temporary logging and debug code.
4. Report the symptom, the root cause, the fix, and how you verified it.

## When stuck

- After three failed hypotheses, stop and re-read the evidence from the
  start; question an assumption you have not tested ("the config is
  loaded", "this function is called").
- If the cause is in a dependency or the environment, say so with the
  evidence, and propose a workaround that is clearly marked as one.
