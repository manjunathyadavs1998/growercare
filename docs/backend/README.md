# Database schemas

Each service now owns its schema analysis next to its migrations (ADR-017):
- [identity-access-service/docs/schema.md](https://github.com/manjunathyadavs1998/growercare-identity-access-service/blob/main/docs/schema.md): `gc_iam`, schema `iam`;
- [case-service/docs/schema.md](https://github.com/manjunathyadavs1998/growercare-case-service/blob/main/docs/schema.md): `gc_case`, schemas `ref`, `msg` and 128 shards.
