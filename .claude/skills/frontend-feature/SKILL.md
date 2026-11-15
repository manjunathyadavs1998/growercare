---
name: frontend-feature
description: Build a GrowerCare React feature (grower-web, staff-console or field-pwa) with the project conventions - feature-slice folders, orval-generated TanStack Query hooks, Zustand for UI state only, FormatJS i18n, permission gates, realtime and offline patterns, and Vitest/RTL/MSW/Playwright tests. Use for any new screen, component or frontend data flow.
---

# Frontend feature (gc-frontend)

Read: `docs/architecture/06_FRONTEND.md`, ADR-009.

## Structure
`apps/<app>/src/features/<feature>/{api,components,routes,store.ts,i18n,__tests__}`. Routes are lazy-loaded.

## Data rules
1. **Server data = TanStack Query only**, via hooks generated in `packages/api-client` (orval). Wrap them in `features/<f>/api` to define query keys and optimistic updates. Don't call `fetch` directly.
2. **UI state = Zustand** (filters, view prefs, panel state). Never copy server data into Zustand.
3. Lists use **infinite queries with cursor** (`nextCursor`); tables use TanStack Table + Virtual for > 200 rows.
4. Date ranges older than 90 days call the **archive** hooks (data-lifecycle API). Show the `X-Data-Source` freshness badge.
5. Mutations that create things send an `Idempotency-Key` / `client_msg_id` (UUIDv7 from `packages/utils`) and use optimistic updates with rollback.
6. Realtime: `useCaseChannel(caseId)` merges pushed events into the query cache; on reconnect, refetch `?after=<lastId>`.
7. Offline (field-pwa): writes go to the Dexie command queue via `useOfflineCommand`; never call APIs directly from offline-capable screens.

## UX rules
- Every user-visible string goes through FormatJS `<FormattedMessage id=…>` with ICU plurals. No string concatenation. RTL-safe CSS (logical properties).
- Gate actions with `<Can permission="…" scope={…}>`. Still handle 403/404 from the server gracefully (Problem Details → toast/inline error).
- Mask PII by default; "Show" calls the unmask endpoint (audited).
- Grower-facing screens: test at 360 px width and on slow 3G (Lighthouse CI budget).
- IDs are strings. Never `Number(id)`.

## Tests (required per feature)
| Test | Tool | Minimum |
|---|---|---|
| Component/functional | Vitest + RTL + MSW (handlers from OpenAPI examples) | Happy path, empty state, error (Problem Details), permission-hidden action |
| Store | Vitest | Zustand actions |
| E2E | Playwright (`e2e/`) | Critical journeys per role; cross-country access must fail |
| A11y | axe in RTL tests | No serious violations |

## Done checklist
- [ ] Generated hooks used; no hand-written API types
- [ ] i18n keys added for all supported languages (fallback English flagged)
- [ ] Tests above pass; bundle budget OK
