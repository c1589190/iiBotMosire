package io.mosire.iibot.app.complaint;

import io.mosire.iibot.taskflow.complaint.Complaint;
import io.mosire.iibot.taskflow.complaint.ComplaintAuditAction;
import io.mosire.iibot.taskflow.complaint.ComplaintAuditEntry;
import io.mosire.iibot.taskflow.complaint.ComplaintCommandResult;
import io.mosire.iibot.taskflow.complaint.ComplaintDeliveryState;
import io.mosire.iibot.taskflow.complaint.ComplaintSnapshot;
import io.mosire.iibot.taskflow.complaint.ComplaintStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonComplaintStoreTest {

    private static final Instant NOW = Instant.parse("2026-10-24T00:00:00Z");
    private static final String BODY_SECRET = "投诉绝密正文-不得进入异常日志-7f6d";
    private static final String RESPONSE_SECRET = "GM 绝密回应-不得进入异常日志-91ab";

    @TempDir
    Path tempDir;

    @Test
    void missingFileLoadsEmptyWithoutCreatingAnything() {
        JsonComplaintStore store = new JsonComplaintStore(tempDir);
        assertThat(store.path()).isEqualTo(tempDir.toAbsolutePath().normalize()
                .resolve(JsonComplaintStore.FILE_NAME));
        assertThat(store.load()).isEqualTo(ComplaintSnapshot.empty());
        assertThat(Files.exists(store.path())).isFalse();
    }

    @Test
    void roundTripPreservesComplaintsIdempotencyAndAudit() throws Exception {
        JsonComplaintStore store = new JsonComplaintStore(tempDir);
        Complaint submitted = new Complaint("complaint-1", "player-1", "投诉标题",
                BODY_SECRET, ComplaintStatus.SUBMITTED, 1, NOW, NOW,
                null, null, null, null);
        Complaint responded = new Complaint("complaint-2", "player-1", "已回应标题",
                "第二条投诉正文", ComplaintStatus.RESPONDED, 2, NOW, NOW.plusSeconds(5),
                "gm-1", RESPONSE_SECRET, NOW.plusSeconds(5), ComplaintDeliveryState.PENDING);
        ComplaintCommandResult receipt = ComplaintCommandResult.success("complaint-2", 2);
        ComplaintSnapshot snapshot = ComplaintSnapshot.empty()
                .withComplaint(submitted)
                .withComplaint(responded)
                .withIdempotentResult("respond-key", receipt)
                .withAuditEntry(new ComplaintAuditEntry(ComplaintAuditAction.RESPOND,
                        "complaint-2", "player-1", "gm-1", 2,
                        "hash", NOW.plusSeconds(4)));

        store.save(snapshot);
        ComplaintSnapshot loaded = store.load();

        assertThat(loaded).isEqualTo(snapshot);
        assertThat(loaded.findComplaint("complaint-1").orElseThrow().body())
                .isEqualTo(BODY_SECRET);
        assertThat(loaded.findComplaint("complaint-2").orElseThrow().responseBody())
                .isEqualTo(RESPONSE_SECRET);
        assertThat(loaded.idempotentResults()).containsEntry("respond-key", receipt);
        assertThat(loaded.auditLog()).hasSize(1);
        assertThat(Files.readString(store.path())).contains("\"schemaVersion\":1");
    }

    @Test
    void malformedUnknownFieldTrailingTokenAndSchemaMismatchFailClosedWithoutLeakingBody()
            throws Exception {
        JsonComplaintStore store = new JsonComplaintStore(tempDir);
        store.save(snapshot("complaint-1"));
        String valid = Files.readString(store.path());

        assertCorrupt(store, valid.replaceFirst("\\{", "{\"unexpected\":true,"));
        assertCorrupt(store, valid + "\n{}");
        assertCorrupt(store, "this is not json " + BODY_SECRET);
        assertThatThrownBy(() -> {
            Files.writeString(store.path(),
                    valid.replace("\"schemaVersion\":1", "\"schemaVersion\":999"));
            store.load();
        }).isInstanceOf(ComplaintStoreException.class)
                .satisfies(failure -> {
                    ComplaintStoreException exception = (ComplaintStoreException) failure;
                    assertThat(exception.code()).isEqualTo(ComplaintStoreException.Code.SCHEMA);
                    assertThat(exception.getMessage()).doesNotContain(BODY_SECRET)
                            .doesNotContain(RESPONSE_SECRET);
                });
    }

    @Test
    void saveAtomicallyReplacesWholeSnapshotAndLeavesNoTemporaryFile() throws Exception {
        JsonComplaintStore store = new JsonComplaintStore(tempDir);
        store.save(snapshot("complaint-1"));
        Path firstPath = store.path();
        String firstContent = Files.readString(firstPath);

        ComplaintSnapshot second = snapshot("complaint-2");
        store.save(second);

        assertThat(store.load()).isEqualTo(second);
        assertThat(store.load()).isNotEqualTo(snapshot("complaint-1"));
        assertThat(Files.readString(store.path())).isNotEqualTo(firstContent);
        try (Stream<Path> files = Files.list(tempDir)) {
            assertThat(files.map(path -> path.getFileName().toString()).toList())
                    .containsExactly(JsonComplaintStore.FILE_NAME);
        }
        if (Files.getFileStore(tempDir).supportsFileAttributeView("posix")) {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(store.path());
            assertThat(permissions).containsExactlyInAnyOrder(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
        }
    }

    @Test
    void saveFailureLeavesExistingCompleteSnapshotUntouched() throws Exception {
        JsonComplaintStore store = new JsonComplaintStore(tempDir);
        store.save(snapshot("complaint-1"));
        Path parent = store.path().getParent();
        Set<PosixFilePermission> original = Files.getPosixFilePermissions(parent);
        try {
            Files.setPosixFilePermissions(parent, Set.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE));
            assertThatThrownBy(() -> store.save(snapshot("complaint-2")))
                    .isInstanceOf(ComplaintStoreException.class)
                    .satisfies(failure -> assertThat(
                            ((ComplaintStoreException) failure).code())
                            .isIn(ComplaintStoreException.Code.SAVE,
                                    ComplaintStoreException.Code.IO));
        } finally {
            Files.setPosixFilePermissions(parent, original);
        }
        assertThat(store.load()).isEqualTo(snapshot("complaint-1"));
    }

    private static ComplaintSnapshot snapshot(String complaintId) {
        Complaint complaint = new Complaint(complaintId, "player-1", "标题-" + complaintId,
                BODY_SECRET + "-" + complaintId, ComplaintStatus.RESPONDED, 2,
                NOW, NOW, "gm-1", RESPONSE_SECRET, NOW, ComplaintDeliveryState.PENDING);
        return ComplaintSnapshot.empty().withComplaint(complaint);
    }

    private static void assertCorrupt(JsonComplaintStore store, String content) throws Exception {
        Files.writeString(store.path(), content);
        assertThatThrownBy(store::load)
                .isInstanceOf(ComplaintStoreException.class)
                .satisfies(failure -> {
                    ComplaintStoreException exception = (ComplaintStoreException) failure;
                    assertThat(exception.code()).isEqualTo(ComplaintStoreException.Code.CORRUPT);
                    assertThat(exception.getMessage()).doesNotContain(BODY_SECRET)
                            .doesNotContain(RESPONSE_SECRET);
                });
    }
}
