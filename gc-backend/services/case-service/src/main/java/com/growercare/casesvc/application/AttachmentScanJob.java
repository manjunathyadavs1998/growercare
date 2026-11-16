package com.growercare.casesvc.application;

import com.growercare.casesvc.application.port.AttachmentRepository;
import com.growercare.casesvc.application.port.CaseRepository;
import com.growercare.casesvc.application.port.ObjectStore;
import com.growercare.casesvc.application.port.ReferenceData;
import com.growercare.casesvc.domain.attachment.Attachment;
import com.growercare.outbox.OutboxWriter;
import com.growercare.shard.ShardId;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * SPEC-CASE-05 D4, R103–R107, R113. Per shard, one transaction claims a batch of PENDING attachments
 * ({@code FOR UPDATE SKIP LOCKED}, so replicas share the work and each file is decided once). It publishes each
 * result (clean or quarantine object, status, activity, outbox) and deletes the staging objects after commit.
 * A failing shard is logged and skipped (SPEC-CASE-04 R95).
 */
@Service
public class AttachmentScanJob {

    private static final Logger log = LoggerFactory.getLogger(AttachmentScanJob.class);
    static final int BATCH = 10;
    static final long SYSTEM_ACTOR = 0L;

    private final ReferenceData ref;
    private final AttachmentRepository repo;
    private final CaseRepository cases;
    private final ObjectStore store;
    private final ScanPipeline pipeline;
    private final OutboxWriter outbox;
    private final TransactionTemplate tx;
    private final Clock clock;

    public AttachmentScanJob(ReferenceData ref, AttachmentRepository repo, CaseRepository cases, ObjectStore store,
                             ScanPipeline pipeline, OutboxWriter outbox, TransactionTemplate tx, Clock clock) {
        this.ref = ref;
        this.repo = repo;
        this.cases = cases;
        this.store = store;
        this.pipeline = pipeline;
        this.outbox = outbox;
        this.tx = tx;
        this.clock = clock;
    }

    /** @return attachments decided (CLEAN, INFECTED or REJECTED) across all shards */
    public int runOnce() {
        return (int) PerShard.sum("attachment-scan", ref.allShards(), this::scanShard);
    }

    /** R113: expires uploads never completed within {@code maxAge}; @return rows expired */
    public int expireStale(Duration maxAge) {
        var cutoff = clock.instant().minus(maxAge);
        return (int) PerShard.sum("attachment-expiry", ref.allShards(), shard -> {
            List<Attachment> expired = tx.execute(status -> repo.expireStale(shard, cutoff));
            expired = expired == null ? List.of() : expired;
            expired.forEach(a -> store.delete(ObjectStore.Area.STAGING, a.objectKey()));      // after commit
            return expired.size();
        });
    }

    long scanShard(ShardId shard) {
        var temporary = new ArrayList<Path>();
        var stagingToDelete = new ArrayList<String>();
        try {
            Integer decided = tx.execute(status -> {
                int count = 0;
                for (var attachment : repo.claimPending(shard, BATCH)) {
                    var outcome = decide(attachment, temporary);
                    var result = Attachment.Status.valueOf(outcome.result().name());
                    if (!repo.markScanned(shard, attachment.caseId(), attachment.attachmentId(), result, clock.instant())) {
                        continue;                                                              // decided elsewhere (R107)
                    }
                    record(shard, attachment, outcome);
                    stagingToDelete.add(attachment.objectKey());
                    count++;
                }
                return count;
            });
            stagingToDelete.forEach(key -> store.delete(ObjectStore.Area.STAGING, key));        // only after commit
            return decided == null ? 0 : decided;
        } finally {
            temporary.forEach(AttachmentScanJob::deleteQuietly);
        }
    }

    private ScanPipeline.Outcome decide(Attachment attachment, List<Path> temporary) {
        var file = store.download(ObjectStore.Area.STAGING, attachment.objectKey());
        temporary.add(file);
        var outcome = pipeline.inspect(file, attachment.contentType(), attachment.sha256Hex());
        if (outcome.result() == ScanPipeline.Result.CLEAN) {
            temporary.add(outcome.cleanFile());
            store.upload(ObjectStore.Area.CLEAN, attachment.objectKey(), outcome.cleanFile(), attachment.contentType());
        } else {
            store.upload(ObjectStore.Area.QUARANTINE, attachment.objectKey(), file, "application/octet-stream");
            log.error("attachment {} of case {} {}: {}", attachment.attachmentId(), attachment.caseId(),
                    outcome.result(), outcome.reason());                                         // ids only, no content
        }
        return outcome;
    }

    private void record(ShardId shard, Attachment attachment, ScanPipeline.Outcome outcome) {
        if (outcome.result() == ScanPipeline.Result.CLEAN) {
            long activity = cases.insertActivity(shard, attachment.caseId(), "ATTACHMENT_ADDED",
                    attachment.uploadedByUserId(), null, null, null);
            cases.insertActivityDetail(shard, attachment.caseId(), activity, "attachment_id", null,
                    Long.toString(attachment.attachmentId()));
        }
        outbox.append(CaseCommands.outboxOf(shard), new OutboxWriter.Event("case", Long.toString(attachment.caseId()),
                "case.attachment.scanned", Map.of(
                        "caseId", Long.toString(attachment.caseId()),
                        "attachmentId", Long.toString(attachment.attachmentId()),
                        "kind", attachment.kind(),
                        "result", outcome.result().name())), null);
    }

    private static void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("could not delete temporary file {}", file.getFileName());
        }
    }
}
