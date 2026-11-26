# Skill review checklist

Mark each item pass or fail. A skill is ready when every item passes.

## Discovery

- [ ] The description says what the skill does *and* when to use it.
- [ ] The description uses words a user would actually type for this task.
- [ ] The description would not match the near-miss requests.
- [ ] The name is a short verb or noun phrase that says what the skill does,
      not a project codename.

## Format

- [ ] The folder name and `name` are identical.
- [ ] Frontmatter has only `name`, `description`, `license`, `compatibility`,
      `metadata` and `allowed-tools`.
- [ ] Every `metadata` value is a quoted string.
- [ ] `SKILL.md` is under 500 lines.
- [ ] Every linked file exists, sits inside the skill folder, and is linked
      directly from `SKILL.md`.

## Instructions

- [ ] The body opens with steps the agent can follow in order.
- [ ] Each choice has a stated default.
- [ ] The body says how to verify the result before finishing.
- [ ] Nothing in the body restates general knowledge the agent already has.
- [ ] Output formats that matter are shown with an example.

## Safety

- [ ] No step downloads and runs remote code.
- [ ] No step reads secrets or sends data to a network service, unless the
      description says that is the purpose.
- [ ] `allowed-tools`, if present, names specific commands rather than an
      unrestricted shell.
- [ ] Scripts document their dependencies and fail with a clear message.
- [ ] The folder contains no symbolic links, binaries or generated files that
      are not needed at run time.
