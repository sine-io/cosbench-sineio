# Issue tracker: Local Markdown

Issues and specs for this repo live as Markdown files in `.scratch/`.

## Conventions

- One feature per directory: `.scratch/<feature-slug>/`
- The feature spec is `.scratch/<feature-slug>/spec.md`.
- Implementation issues are one file per ticket at `.scratch/<feature-slug>/issues/<NN>-<slug>.md`, numbered from `01`.
- Triage state is recorded as a `Status:` line near the top of each issue file.
- Comments and conversation history append to the bottom of the file under a `## Comments` heading.

## Publishing and fetching

When a skill says to publish to the issue tracker, create a file under `.scratch/<feature-slug>/`, creating the directory when needed. When a skill says to fetch a ticket, read the referenced path supplied by the user.

## Wayfinding

For wayfinding efforts, use `.scratch/<effort>/map.md` as the map and one child ticket per issue under `.scratch/<effort>/issues/`. Child tickets use `Type:` (`research`, `prototype`, `grilling`, or `task`), `Status:` (`claimed` or `resolved`), and an optional `Blocked by:` line. Claim a ticket by setting `Status: claimed`; resolve it by adding an `## Answer` section, setting `Status: resolved`, and recording a context pointer in the map's Decisions-so-far section.
