---
name: using-code-scalpel
description: Uses the Code Scalpel MCP server's tools to read, analyze, security-scan and safely refactor code by symbol instead of reading whole files. Use when Code Scalpel tools (extract_code, get_symbol_references, security_scan, rename_symbol and others) are available and the task involves finding or understanding code, tracing callers or dependencies, checking for vulnerabilities, or renaming and replacing functions or classes.
compatibility: Requires the Code Scalpel MCP server (codescalpel), which SkillForge's installer configures.
metadata:
  skillforge-domain: engineering
  skillforge-version: "0.1.0"
---

# Using Code Scalpel

Code Scalpel parses code into syntax trees and data-flow graphs, so it can
return one function, every caller of a symbol, or a traced vulnerability
without loading whole files. Reach for it before reading large files or
grepping across a project.

Tool names may carry a client prefix (for example
`mcp__codescalpel__extract_code` in Claude Code). Read each tool's input
schema before the first call rather than guessing parameter names.

## Pick the tool for the job

| Task | Tool |
| --- | --- |
| Get oriented in an unfamiliar project | `get_project_map`, then `crawl_project` for metrics |
| Read one function or class (with its imports) | `extract_code` |
| Understand a file's structure, imports, complexity | `analyze_code` |
| Context around a specific location | `get_file_context` |
| Who calls this / where is it used? | `get_symbol_references`, `get_call_graph` |
| What does this module depend on? | `get_cross_file_dependencies` |
| Trace untrusted input to dangerous sinks | `security_scan`, `cross_file_security_scan` |
| List dangerous functions in any language | `unified_sink_detect` |
| Security context around one function | `get_graph_neighborhood` |
| TypeScript types trusted at I/O boundaries | `type_evaporation_scan` |
| Vulnerable dependencies | `scan_dependencies` |
| Check a change before applying it | `simulate_refactor` |
| Replace a function or class body | `update_symbol` |
| Rename across the project | `rename_symbol` |
| Check paths before file operations | `validate_paths` |
| Explore execution paths / draft tests | `symbolic_execute`, `generate_unit_tests` |
| Organization policy checks | `code_policy_check`, `verify_policy_integrity` |
| Current tier and limits | `get_capabilities` |

## Workflows

**Understand before changing.** `get_project_map` → `extract_code` for the
target → `get_symbol_references` for its callers → read only the callers
that matter. Summarize what the code does and who depends on it before
editing.

**Safe refactor.** Find every reference with `get_symbol_references`; run
`simulate_refactor` on the planned change; apply with `update_symbol` or
`rename_symbol`; then run the project's own tests. Code Scalpel's checks
complement the test suite; they do not replace it.

**Security pass.** Run `security_scan` on changed files and
`cross_file_security_scan` when input crosses modules; add
`scan_dependencies` when manifests changed. Treat each result as a lead:
confirm the source, path and impact in the code before reporting it (see the
`security-review` skill).

**Tests for tricky logic.** Use `symbolic_execute` to list the paths through
a function, then `generate_unit_tests` for a starting set; review and adapt
them to the project's test style (see the `writing-tests` skill).

## When not to use it

- For a small file you need in full, reading it directly is simpler.
- If a tool reports an unsupported language or a tier limit, fall back to
  normal file reading and search, and say so.
- If the tools are not available at all, tell the user that the Code
  Scalpel MCP server is not connected; in a SkillForge project,
  `python tools/mcp_check.py` from the SkillForge clone shows whether it
  starts.
