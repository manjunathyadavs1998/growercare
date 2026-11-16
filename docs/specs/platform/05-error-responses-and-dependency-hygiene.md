# SPEC-PLAT-05: Problem+json for every error, and dependency hygiene
- **Requirements:** NFR API consistency (RFC 9457, `java-exception-handling` skill), SPEC-PLAT-02 (Dependabot governance)
- **Status:** Implemented (2026-11-16; Dependabot PRs #3–#6 closed and superseded by this change)
- **Owner:** gc-common / gc-security (both services), repository settings

## 0. Decisions
| # | Decision | Alternative (not chosen) |
|---|---|---|
| D1 | Requests that Spring Security's firewall rejects (e.g. `//` or `;` in the path, encoded traversal) are answered by a **`RequestRejectedHandler`** that writes problem+json: 400 `VALIDATION_FAILED`, field `path` | Leaving Spring Boot's default `/error` body (`timestamp/status/error/path`), which differs from every other error |
| D2 | **Anything that still reaches `/error`** (errors raised in filters, container errors) is rendered by `GcErrorController` as problem+json, keeping the original status | Boot's `BasicErrorController` |
| D3 | Spring MVC's protocol errors get their **real status** instead of falling into the 500 catch-all: 405 `METHOD_NOT_ALLOWED` (with `Allow`), 415 `UNSUPPORTED_MEDIA_TYPE`, 406 `NOT_ACCEPTABLE` | Mapping them all to 400 |
| D4 | **Dependabot:** <br>• **JaCoCo 0.8.16** and **Keycloak 26.8** are applied here, verified by the full build, coverage gate and smoke test; <br>• **major** bumps of the stateful images **postgres** and **redis** are ignored by Dependabot: they must match the AWS engines (Aurora/RDS PostgreSQL; ElastiCache Redis 7 or Valkey) and need a deliberate, spec'd upgrade test (partitions, replication slots). Minor and patch updates still flow | Auto-merging majors |

## 1. Behaviour (rules)
| Rule | Given | When | Then |
|---|---|---|---|
| PLAT-R66 | either service | any error response | `Content-Type: application/problem+json` with `type`, `title`, `status`, `code`, `traceId`, and no stack trace. This includes requests rejected before MVC (D1) and anything rendered at `/error` (D2) |
| PLAT-R67 | a request the firewall rejects | — | 400 `VALIDATION_FAILED`, `errors[0].field = "path"` |
| PLAT-R68 | a known path with an unsupported method · an unsupported request content type · an unsatisfiable `Accept` | — | 405 `METHOD_NOT_ALLOWED` + `Allow` header · 415 `UNSUPPORTED_MEDIA_TYPE` · 406 `NOT_ACCEPTABLE` (never 500) |
| PLAT-R69 | a request body that is not valid JSON | — | 400 `VALIDATION_FAILED`, field `body` (regression guard; already implemented) |
| PLAT-R70 | `.github/dependabot.yml` | Dependabot runs | `postgres` and `redis` images: `semver-major` updates ignored; everything else unchanged |
| PLAT-R71 | JaCoCo 0.8.16, Keycloak 26.8 | `mvn -B verify` + coverage gate + `smoke.sh` | all green |

## 2. Examples (each row = one parameterized test row)
| Example | Rule | Request | Expected |
|---|---|---|---|
| PLAT-E78 | R67 | `GET /cases/1/%2e%2e/attachments` (encoded traversal) | 400 problem+json `VALIDATION_FAILED`, field `path` |
| PLAT-E79 | R67 | `GET /cases/1;jsessionid=x` | 400 problem+json, field `path` |
| PLAT-E80 | R68 | `DELETE /cases` | 405 problem+json `METHOD_NOT_ALLOWED`, `Allow` lists GET, POST |
| PLAT-E81 | R68 | `POST /cases` with `Content-Type: text/plain` | 415 problem+json `UNSUPPORTED_MEDIA_TYPE` |
| PLAT-E82 | R68 | `GET /cases` with `Accept: image/png` | 406 problem+json `NOT_ACCEPTABLE` |
| PLAT-E83 | R69 | `POST /cases` with body `{"growerId":` | 400 problem+json, field `body` |
| PLAT-E84 | R66 | the same rejections against identity-access-service (`GET /grants/%2e%2e/1`, `GET /me;jsessionid=x`) | 400 problem+json |
| PLAT-E86 | R67 | **smoke (real Tomcat):** `GET /cases/<id>/attachments//download`, i.e. an empty id (MockMvc does not reproduce Tomcat's `//` handling) | 400 problem+json |
| PLAT-E85 | R71 | the local stack on Keycloak 26.8 | `smoke.sh`: 0 failed checks |

## 3. Traceability
| Rule / Example | Test |
|---|---|
| R66–R69 / E78–E83 | `CaseSecurityIT$ErrorResponses#everyErrorIsProblemJson` (parameterized) |
| R66 / E84 | `IamSecurityIT$Authentication#rejectedPathsAreProblemJson` |
| R70 | `.github/dependabot.yml` (reviewed in this PR) |
| R71 / E85 · R67 / E86 | CI `backend-ci` + coverage gate; `local-dev/smoke.sh` (PLAT-E86) |
