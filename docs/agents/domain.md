# Domain Docs

This repository uses a single-context domain documentation layout.

## Before exploring

- Read `GLOSSARY.md` at the repository root when it exists.
- Read relevant decisions under `docs/adr/` when they exist.
- If these files do not exist, proceed without treating their absence as a problem. Create them lazily when domain terms or architectural decisions need to be recorded.

## Layout

```text
/
├── GLOSSARY.md
├── docs/adr/
└── src/
```

Use glossary terms consistently in issue titles, refactor proposals, hypotheses, and tests. If a new concept is needed but is not defined, record that gap for domain modeling. If a proposed change conflicts with an existing ADR, surface the conflict explicitly rather than silently overriding it.
