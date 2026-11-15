# Syngenta — Company Deep Dive & Fit Analysis
*Prepared 2026-11-14 · design-exercise document, not affiliated with or endorsed by Syngenta. Regulatory references are for requirements framing and must be validated by legal/regulatory counsel per country.*

---

## 1. Who Syngenta is

| Item | Detail |
|---|---|
| Legal / group | **Syngenta Group**, headquartered in Basel, Switzerland; formed June 2020 combining Syngenta AG, ADAMA and Sinochem's agricultural businesses; owned by Chinese state-owned Sinochem (post ChemChina merger) |
| Business units | **Syngenta Crop Protection** (Basel) · **Syngenta Seeds** (Chicago) · **ADAMA** (off-patent crop protection, Israel) · **Syngenta Group China** (Shanghai) |
| Size | ~48–49K employees, presence in 100+ countries |
| FY2025 results | Group sales **US$28.4B** (−1%), EBITDA **US$4.4B** (+13%) |
| | Crop Protection **US$13.7B** (+4%) · Seeds **US$4.8B** (+2%, Brazil +17%) · ADAMA **US$4.1B** · Group China **US$8.3B** |
| Digital | **Cropwise** farm-management platform: ~70–80M hectares, 30+ countries; opened to third-party developers Nov 2025 |

### What they sell
| Portfolio | Examples | Who buys |
|---|---|---|
| **Crop protection** — herbicides, insecticides, fungicides | Amistar (fungicide), Actara (insecticide), Ridomil Gold (fungicide), Callisto (herbicide) | Growers, via distributors & retailers |
| **Seed treatment** | Vibrance, Fortenza | Seed companies, growers |
| **Seeds** — field crops & vegetables | NK (corn, soy, sunflower), Golden Harvest (US corn), vegetable & flower seeds | Growers, dealers, seed retailers |
| **Biologicals** — biostimulants, biocontrol | Valagro portfolio (acquired 2020) | Growers (fast-growing segment) |
| **Professional solutions** | Turf, public health (vector control), lawn & garden | Golf courses, municipalities, governments |
| **Digital & services** | Cropwise (Operations, Grower, Seed Selector, imagery, disease models) | Growers, agronomists |

---

## 2. How Syngenta actually reaches the farmer (critical for the design)

Syngenta is **not a pure B2C company** — it is **B2B2C** with heavy regional variation:

```
           Syngenta (country entity)
                 │  sells / invoices
                 ▼
      Distributors ─────────► Retailers / Dealers / Co-ops (licensed to sell pesticides)
                 │                       │  sells
   (large growers in US/Brazil           ▼
    often buy direct or via        GROWERS (10M+ reachable: smallholders → large farms)
    big ag retailers)                    ▲
                 ▲                       │ advisory, demos, complaints, claims
                 └── Syngenta field force: agronomists, territory managers, customer care
```

| Region | Grower profile | Channel reality | Digital reality |
|---|---|---|---|
| **India / South & SE Asia** | Smallholders (1–2 ha), very price-sensitive, multi-lingual, low literacy in places | Thousands of licensed village retailers; credit given by retailers | Android + WhatsApp dominant, patchy connectivity, IVR/missed-call still common |
| **Brazil / LATAM** | Large commercial farms (1,000s ha) + mid-size | Distributors, co-ops, direct key-accounts; **barter** (inputs paid in grain) | Smartphones + high WhatsApp use; PIX payments; rural connectivity gaps |
| **North America** | Large, highly mechanized, data-savvy | Ag retailers (dealers), seed advisors | Precision ag, machine data, desktop + mobile |
| **Europe** | Mid-size, heavily regulated | Distributors / co-ops | Strong privacy expectations; mandatory electronic spray records coming in |
| **Africa** | Smallholders | Agro-dealers, NGOs, government programs | Feature phones, USSD/SMS, **mobile money** |
| **China** | Mixed; fast consolidation | Group China's own channels | Super-apps; drone spraying very common; strict data-localization |

**Implication:** a single "customer = consumer" model is wrong. The platform must model **growers, channel partners, and Syngenta staff**, and the workflows differ per region.

---

## 3. Real business problems where a service / case-management platform helps

The take-home's "work order" maps to a **Field Case**: anything that needs someone to *investigate, visit, decide, and close* with an audit trail.

| # | Problem in the real world | Case type | Who acts | Why it matters to Syngenta |
|---|---|---|---|---|
| 1 | Grower sees pest/disease/weed problem and needs advice | `AGRONOMY_ADVICE` | Call-center agronomist → field agronomist visit | Drives correct product use and loyalty; must recommend **only products registered for that crop/pest in that country** (off-label advice is illegal) |
| 2 | "The product didn't work" / crop damaged after spraying (phytotoxicity) | `PRODUCT_COMPLAINT` | Customer care → agronomist field investigation → quality lab → decision | Compensation cost, reputation, root-cause to manufacturing batch |
| 3 | Seed failed to germinate / off-types | `SEED_PERFORMANCE_CLAIM` / replant | Seed advisor → field inspection → claim approval | Replant guarantees / compensation; seed-law quality obligations |
| 4 | Human/animal/environmental incident (poisoning, bee kill, spill) | `ADVERSE_INCIDENT` | Stewardship + regulatory affairs | **Legally mandated reporting** to regulators within fixed timelines in many countries |
| 5 | Counterfeit / smuggled product in the market | `COUNTERFEIT_REPORT` | Product security team, legal | Counterfeits are a major problem in India, Africa, SE Asia, LATAM; grower safety & revenue |
| 6 | Grower wants a drone/custom spray service | `SPRAY_SERVICE_BOOKING` | Certified drone operator partner | Growing business; heavily regulated per country |
| 7 | Demonstration plots & farmer meetings | `DEMO_PLOT` / `FIELD_EVENT` | Territory manager / agronomist | Main marketing tool in smallholder markets |
| 8 | Empty container collection / take-back | `CONTAINER_RETURN` | Retailer / collection center | **Mandatory** in Brazil; stewardship & EPR elsewhere |
| 9 | Retailer scheme / rebate claim disputes | `CHANNEL_CLAIM` | Sales ops + finance | Large money flows; fraud risk |

---

## 4. Regional regulation that shapes the requirements

### 4.1 Pesticide & seed regulation
| Region | Key rules | What the platform must do |
|---|---|---|
| **India** | Insecticides Act 1968 & Rules 1971 (CIB&RC registration, licensed dealers); draft **Pesticides Management Bill 2025** (published Jan 2026, not yet enacted); Seeds Act 1966 / Seeds (Control) Order 1983; PPV&FR Act 2001; Drone Rules 2021 + interim SOP for pesticide drone spraying | Recommend only CIB&RC-label crop/pest uses; validate retailer license & expiry; capture batch no./mfg date in complaints; record drone operator pilot certificate; be ready for new-bill changes (configurable rules) |
| **Brazil** | **Law 14.785/2023** (new pesticide law, MAPA-led registration); **receituário agronômico** — agronomic prescription signed by a licensed agronomist required to sell pesticides; Law 9.974/2000 — mandatory **empty-container return** (inpEV system); MAPA rules for drone application | Prescription capture/verification, agronomist registration (CREA) validation, container-return tracking, region-specific product registry |
| **United States** | **FIFRA** + EPA label (label is law); state registrations; Restricted-Use Pesticides only for **certified applicators** (40 CFR 171); RUP record-keeping; **FIFRA 6(a)(2)** adverse-effects reporting (40 CFR 159); FAA rules for spray drones | Applicator certification checks for RUP-related services; adverse-effects reporting queue with regulatory clocks; state-by-state product availability |
| **European Union** | Regulation (EC) **1107/2009** (approval of actives, product authorization by member state; Art. 56 notification of harmful effects; withdrawal grace periods); Directive **2009/128/EC** (Sustainable Use — training certificates, aerial spraying banned except derogation); Implementing Reg. **(EU) 2023/564** — **electronic records of PPP use** by professional users | Member-state-level product authorization; handle product withdrawals with sell-off / use-up deadlines; block drone-spray booking unless derogation; export spray records in required format |
| **China** | ICAMA registration; high drone spraying adoption | Operated as a separate cell (see data rules) |
| **Africa** | National boards (e.g., Kenya PCPB) | Lightweight channels (USSD/SMS), agro-dealer verification |

### 4.2 Data protection & residency
| Jurisdiction | Law | Design impact |
|---|---|---|
| EU / UK | GDPR / UK GDPR | Lawful basis & consent, DSAR in 30 days, DPIA, restricted transfers (SCCs) |
| Brazil | LGPD | Similar to GDPR; ANPD; data-subject rights |
| India | **DPDP Act 2023** + DPDP Rules 2025 (phased) | Consent notices in local languages, consent manager support, breach notification, children's data rules |
| China | **PIPL**, Data Security Law | **Data localization** + security assessment for cross-border transfer → separate deployment |
| US | CCPA/CPRA + other state laws | Opt-out of sale/share, sensitive data limits |
| Vietnam / Indonesia / Kenya / Nigeria | PDPD (Decree 13/2023) / PDP Law 2022 / DPA 2019 / NDPA 2023 | Local registration/impact assessments, some localization tendencies |

Also relevant: the **EU Code of Conduct on agricultural data sharing** — farm data belongs to the farmer; consent needed for reuse (consistent with Syngenta's stated Cropwise position).

### 4.3 Tax, invoicing & payments
| Region | Specifics |
|---|---|
| India | GST with **e-invoicing (IRN)**; seeds GST-exempt vs pesticides taxed; UPI payments |
| Brazil | **NF-e** electronic invoices; PIX; **barter** and CPR (rural product note) financing |
| US | State sales tax with **farm-exemption certificates**; ACH |
| EU | VAT; country e-invoicing mandates (e.g., Italy SDI) |
| Africa | Mobile money (e.g., M-Pesa) |

**Principle:** the ERP (e.g., SAP) stays the financial system of record. This platform is the **system of engagement** — it raises claims, approvals and payment requests and integrates with ERP; it does not build its own general ledger.

---

## 5. Gap: previous BRD (appliance after-sales) vs Syngenta reality
| Previous assumption | Syngenta reality | Change |
|---|---|---|
| Single company, single country | 100+ countries, 4 business units | Hierarchy Global → Region → Country → Territory; BU dimension |
| Customer buys directly | B2B2C via distributors/retailers | Channel partner org model (multi-tenancy returns, for partners) |
| Technician repairs appliance | Agronomist investigates field, quality lab tests batch | Case types with configurable workflows |
| Warranty by serial number | Product identified by **batch/lot**; seeds by lot + variety | Batch traceability, recall support |
| Invoices + payments to customer | Compensation via **credit note to retailer**, replant seed, rebates; ERP is SoR | Claims & approvals, ERP integration |
| One privacy regime | 10+ regimes, some with localization | Regional data cells |
| Same UI everywhere | Region-specific dashboards, languages, channels | Country profiles + feature flags |

**Next document:** `02_BUSINESS_REQUIREMENTS.md`.

---

### Sources
- [Syngenta Group Reports 2025 Full Year Results (Business Wire)](https://www.businesswire.com/news/home/20260330697554/en/Syngenta-Group-Reports-2025-Full-Year-Results)
- [CropLife — Syngenta Group 2025 results](https://www.croplife.com/crop-inputs/syngenta-group-delivers-resilient-2025-results-with-double-digit-ebitda-growth/)
- [Syngenta Group launch, 2020](https://www.syngentagroup.com/newsroom/2020/launch-syngenta-group-creating-global-agtech-market-leader)
- [Syngenta opens Cropwise to developers (Nov 2025)](https://www.syngenta.com/media/media-releases/2025/syngenta-opens-cropwise-digital-platform-developers-co-innovate-and)
- [AgTechNavigator — Inside Syngenta's Cropwise](https://www.agtechnavigator.com/Article/2025/10/08/tech-insight-exploring-syngentas-cropwise-platform)
- [Syngenta Vietnam bar code anti-counterfeit (2012)](https://www.efeedlink.com/contents/03-22-2012/9dfd850e-8c58-4d55-ad97-91edcff4274b-a181.html)
- [Global Agriculture — protecting against counterfeit seeds](https://www.global-agriculture.com/crop-protection/how-to-protect-your-operation-from-fake-counterfeit-seeds/)
- [Policy Edge — India's fight against counterfeit farm inputs](https://www.policyedge.in/p/making-indias-fight-against-counterfeit-farm-inputs-more-effective)
- [Down To Earth — Pesticides Management Bill 2025](https://www.downtoearth.org.in/environment/pesticides-management-bill-2025-revised-draft-but-old-gaps-remain)
- [The Week — Pesticides Management Bill explained (Jan 2026)](https://www.theweek.in/news/india/2026/01/07/pesticides-management-bill-explained-how-new-law-could-help-farmers-and-industry.html)
