# Code review checklist

Use the items that apply to the change. Each answer that points to a defect
(whether that answer is "yes" or "no") is a candidate finding to verify, not
a finding yet.

## Logic and data

- Can any input be `null`, empty, zero, negative, very large, or of an
  unexpected type here, and is that handled?
- Are loop bounds, slices and ranges correct at both ends?
- Are conditions inverted, or is `and`/`or` precedence wrong?
- Is an error swallowed, logged and ignored, or turned into a wrong default?
- Does the change update every place that must stay in sync (callers,
  serializers, docs, config, migrations)?
- Are floating-point, time zones, encodings or locale assumptions safe?

## State and concurrency

- Is shared state mutated without a lock or transaction?
- Can two requests interleave and both pass a check-then-act sequence?
- Are files, connections, locks and subprocesses always released, including
  on error paths?
- Are retries idempotent?

## Interfaces

- Did a public function, endpoint, CLI flag, event or schema change shape?
  Who calls it, and do they still work?
- Is new configuration documented and defaulted safely?
- Is data written in a format that older versions still running can read?

## Security

- Does untrusted input reach SQL, a shell, a file path, a URL fetch, a
  template, `eval`, or a deserializer?
- Is every new endpoint or action checked for authorization, not only
  authentication?
- Are secrets, tokens or personal data logged, returned or committed?
- Are new dependencies pinned and from the expected source?

## Tests

- Is there a test that fails without this change?
- Do tests assert outcomes, not just that code ran?
- Were existing tests weakened, skipped or rewritten to match new behaviour
  without a reason?

## Performance

- Is there a query or network call inside a loop?
- Does anything load an unbounded collection into memory?
- Is work repeated that could be computed once?
