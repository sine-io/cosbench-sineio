# Repository Instructions

1. 使用 Docker 命令时，请添加 `sudo`。
2. 使用 Go 语言开发项目时，请遵循 DDD Lite + CQRS + Clean Architecture + DIP。
3. 开发调试使用 Docker 容器，调试完毕后清理容器。

## Agent skills

### Issue tracker

Issues and specs live as Markdown files under `.scratch/<feature-slug>/`. See `docs/agents/issue-tracker.md`.

### Triage labels

Use the default canonical labels: `needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, and `wontfix`. See `docs/agents/triage-labels.md`.

### Domain docs

This is a single-context repository; use the root `GLOSSARY.md` and `docs/adr/` when present. See `docs/agents/domain.md`.
