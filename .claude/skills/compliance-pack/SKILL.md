---
name: compliance-pack
description: Add or change country-specific behaviour in GrowerCare through country profiles (control-plane config) and compliance packs (Strategy/Plugin modules such as pack-in, pack-br, pack-us, pack-eu) instead of country if-statements. Use when a requirement differs by country or region: regulations, workflows, required fields, regulatory clocks, channels, tax/payment rails, privacy rules, dashboards.
---

# Compliance packs & country profiles

Background:
- `docs/syngenta/01_COMPANY_ANALYSIS.md §4` (regulations per region)
- `docs/syngenta/02_BUSINESS_REQUIREMENTS.md §5` (profiles & packs)
- `docs/architecture/01_SERVICES/control-plane-service.md`

## Decide: config or code?
| Variation | Where it goes |
|---|---|
| Languages, channels, enabled case types, SLA durations, seasons, retention periods, widget enablement, approval limits | **Country profile** (config, versioned, maker-checker). No code change. |
| Extra workflow steps/fields for a case type | **Workflow definition** per case type × country (config), validated against HQ guardrails |
| Rules needing logic or external integration (prescription validation, license checks, regulator report format, e-invoice hooks) | **Compliance pack** code module |

**Never write `if ("IN".equals(country))` in services.** CI greps for country-literal conditionals outside `libs/packs/**`.

## Pack SPI (`libs/compliance-pack-api`)
Typical extension points (each a Strategy interface, selected by the country profile's enabled pack version):
- `CaseIntakeRules`: extra validation/fields on case creation (e.g., BR requires a prescription number).
- `RegulatoryClockPolicy`: deadlines and escalation for `ADVERSE_INCIDENT`.
- `RegulatorReportFormatter`: produces the regulator's report payload or file.
- `PartnerKycValidator`: tax ID and pesticide-licence validation (GSTIN, CNPJ, …).
- `LabelCompliancePolicy`: country nuances on top of catalog-registry checks (e.g., EU withdrawal grace periods).
- `ConsentNoticeProvider`: privacy-law-specific notices (DPDP, LGPD, GDPR).

## Steps
1. Write the requirement as a pack/profile change with its legal reference (and a "validate with counsel" note).
2. Implement in `libs/packs/pack-<cc>/` with unit tests per rule. Packs are pure domain code (no Spring in pack logic; wiring only in `config`).
3. Bump the pack version; add it to the country profile draft in control-plane; get two approvals; set an effective date.
4. Tests:
   - pack unit tests;
   - case-service integration test running the workflow with the pack enabled for that country **and disabled for another country** (isolation);
   - E2E scenario for the country in Playwright.
5. Document in the relevant service doc's "Country variations" note and in the BRD requirement row.
