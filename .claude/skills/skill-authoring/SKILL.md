---
name: skill-authoring
description: Writes or reviews an Agent Skill (a folder with a SKILL.md file) so that agents load it at the right time and follow it reliably. Use when creating a new skill, turning a repeated workflow or prompt into a skill, fixing a skill that triggers too often or never, or checking a SKILL.md against the Agent Skills specification.
metadata:
  skillforge-domain: meta
  skillforge-version: "0.1.0"
---

# Skill authoring

An Agent Skill is a folder whose `SKILL.md` has YAML frontmatter and Markdown
instructions. Agents read only `name` and `description` until a task matches,
then load the body, then open other files in the folder only when the body
points to them. Write for that order.

## 1. Pin down the job before writing

Ask, or infer from the conversation, and write down:

- **The task**: one recurring job, stated as a verb phrase ("review a
  database migration", not "databases").
- **Triggers**: three or four things a user would actually say when they need
  it, and one or two near misses that should *not* load it.
- **The output**: what done looks like (a file, a diff, a report, a decision).
- **What the agent gets wrong without it**: the skill's whole value lies here.
  If a capable agent already does the task well, do not write a skill.

## 2. Write the frontmatter

```yaml
---
name: review-migrations        # a-z, 0-9, single hyphens; same as the folder
description: >-
  Reviews database migration files for locking, data loss and rollback
  problems. Use when a pull request adds or changes a migration, or the user
  asks whether a schema change is safe to deploy.
---
```

- `name`: at most 64 characters, lowercase letters, digits and single
  hyphens, no leading or trailing hyphen, identical to the folder name.
- `description`: at most 1024 characters. Say **what it does** and **when to
  use it**, using the words from your trigger list. Write in the third person.
  This one field decides whether the skill ever loads, so spend the most care
  here.
- Optional: `license`, `compatibility` (only for real environment needs, at
  most 500 characters), `metadata` (string keys to *quoted* string values),
  and `allowed-tools` (experimental; never pre-approve an unrestricted shell).
- Add no other keys. Tools that follow the specification may reject them.

## 3. Write the body

- Lead with the procedure as numbered steps, in the order the agent should
  act. Use imperatives.
- State defaults instead of listing options. Give one recommended way and
  name the exception that changes it.
- Include one short, concrete example of input and expected output when the
  output format matters.
- Spell out checks the agent should run before it declares the task done.
- Cover the two or three edge cases that actually cause failures. Skip
  general advice the agent already knows.
- Keep `SKILL.md` under 500 lines (well under 5000 tokens is better). Move
  long reference material into `references/`, templates into `assets/`, and
  deterministic work into `scripts/`, and link each file directly from
  `SKILL.md` with a relative path. Keep references one level deep.

## 4. Scripts and safety

- Prefer a script when the same code would otherwise be rewritten each time,
  or when exact output matters. Document its inputs, outputs and
  dependencies at the top of the file and make errors explain the fix.
- Never fetch and execute remote code, read credentials, or send data to a
  network service unless that is the skill's stated purpose, and say so in
  the description.
- Commit real files, not symbolic links.

## 5. Check it

1. Run the repository's validator if it has one (in SkillForge:
   `python tools/validate_skills.py skills/<name>`), or `skills-ref validate`
   from the Agent Skills project.
2. Walk through [the review checklist](references/checklist.md) and fix every
   item that fails.
3. Test triggering: for each phrase on your trigger list, confirm that the
   description alone would make you pick this skill, and that the near
   misses would not. Reword the description until both hold.
4. Test the body: follow the instructions literally on one realistic task.
   Anything you had to guess is a gap to fill.

When reviewing someone else's skill, report the checklist items that fail,
quote the line, and propose the replacement text.
