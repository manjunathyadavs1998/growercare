---
name: architecture-adr
description: Write or update GrowerCare Architecture Decision Records and Mermaid architecture diagrams (context, container, component, sequence, deployment) in the project's format. Use when making or changing an architectural decision, adding a service, or documenting a design.
---

# Architecture ADRs & diagrams (GrowerCare)

## Before writing
1. Read `docs/architecture/00_OVERVIEW.md` and the ADR index in `docs/architecture/adr/`.
2. Check whether an existing ADR already covers the decision. If so, **supersede it**: new ADR + set the old one's status to `Superseded by ADR-0xx`. Never silently edit an accepted decision.
3. Check impact on the requirement IDs in `docs/syngenta/02_BUSINESS_REQUIREMENTS.md` and on the data/ID docs (`docs/syngenta/03–05`).

## ADR format (exactly this)
File: `docs/architecture/adr/ADR-<NNN>-<kebab-title>.md` (next free number)
```markdown
# ADR-NNN: <decision as a sentence>
- **Status:** Proposed | Accepted | Superseded by ADR-xxx · YYYY-MM-DD
- **Context:** <forces: load numbers, constraints, requirement IDs>
- **Decision:** <what we do, concrete enough to implement>
- **Consequences:** <plus> / <minus>, with mitigations
- **Alternatives:** <option (why rejected)>, …
```
Rules:
- Quote numbers (req/s, rows/day, cost), not adjectives.
- Name the trade-off explicitly: what we give up.
- One decision per ADR.

## Diagram rules (Mermaid)
- Use `flowchart` (LR/TB) for context/container/component views and `sequenceDiagram` for flows. Don't use the experimental C4 syntax.
- Node labels: no parentheses or semicolons inside `[ ]`. Use `<br/>` for line breaks. Avoid `<` and `>` in text.
- Show data stores as `[( )]`, queues as `[[ ]]`, external systems in their own nodes.
- Every service doc in `docs/architecture/01_SERVICES/` must contain: Responsibilities, component diagram, ≥ 1 key-flow sequence diagram, API, Data, Events, Scaling profile, Limitations, Trade-offs (table).

## After writing
- Add/adjust the row in `00_OVERVIEW.md §6` (service catalogue / requirement ownership) if service boundaries changed.
- Update `CLAUDE.md` only if conventions changed.
