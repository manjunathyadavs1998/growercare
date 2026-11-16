# ADR-016: Attachments start as a module of case-service; media-service is extracted later
- **Status:** Accepted · 2026-11-16
- **Supersedes:** for phase 1 only, the ownership of uploads and scanning in `01_SERVICES/media-service.md`. That service doc stays the target design.
- **Context:**
  - CASE-3 and CMT-10 need evidence and comment attachments now (SPEC-CASE-05).
  - The media-service design has its own database, S3 events → SQS, scan workers and `media.scanned` events. Locally that means a third JVM and database on a laptop that is already RAM-bound (Postgres, Redis, Keycloak, Kafka, Connect, two services).
  - Attachments belong to a case: the scope and visibility rules (SPEC-CASE-01/03) live in case-service.
- **Decision:**
  - case-service gets an `attachments` module built on ports (`ObjectStore`, `MalwareScanner`, `AttachmentRepository`) and a Spring-free `ScanPipeline`:
    - checksum → malware scan → magic bytes → metadata strip.
  - Uploads and downloads are presigned (bytes never pass through pods).
  - A per-shard poller (`FOR UPDATE SKIP LOCKED`) runs the scan.
  - Rows live in the existing shard table `case_attachment`. The result is published as `case.attachment.scanned` through the outbox.
  - S3 API everywhere: AWS S3 in the cloud, SeaweedFS locally and in Testcontainers. MinIO stopped publishing community images in October 2025.
  - Local scanner: the EICAR test scanner. Production plugs ClamAV workers or GuardDuty Malware Protection for S3 into `MalwareScanner` (chosen at the AWS stage).
- **Extraction triggers (any one):**
  - media volume needs scan workers to scale apart from case-service;
  - a second domain (field visits, claims) needs files;
  - S3 event-driven scanning (SQS or Lambda) is adopted.

  Extraction moves the module behind media-service's API and replaces the poller with S3 events. The shard rows become a projection of `media.scanned`.
- **Consequences:**
  - Plus: no extra service or database now; reuses case scope checks; the pipeline is portable (it could run in a Lambda via the AWS base image + Runtime Interface Emulator, tested locally without an AWS account).
  - Minus: case-service pods do the scanning I/O (bounded: batch of 10 per shard, 25 MB images and 100 MB video); file bytes pass through case-service memory and temporary disk during scans.
- **Alternatives:** media-service now (more processes and work, same user value); scanning inside the upload request (slow and blocks request threads).
