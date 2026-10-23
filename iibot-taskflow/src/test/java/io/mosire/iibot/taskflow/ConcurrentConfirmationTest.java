package io.mosire.iibot.taskflow;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** 并发重复确认同一 key 时只能产生一条 Decision 和一个事件。 */
class ConcurrentConfirmationTest {

    @Test
    void concurrentDuplicateConfirmUsesOneDecisionAndOneEvent() throws Exception {
        EngineFixture f = EngineFixture.create();
        f.loadFacts(new WorldFact("f1", 1, "港口", Visibility.PUBLIC));
        f.openTurn();
        DecisionDraft draft = f.createDraft(f.p1, "观察", "draft-1");
        long revision = f.turnRevision();
        int eventsBefore = f.store.size();

        int workers = 16;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<CommandResult>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < workers; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await(5, TimeUnit.SECONDS);
                    return f.engine.confirmDecision(f.p1, draft.draftId(), draft.previewHash(),
                            revision, "concurrent-same-key");
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<CommandResult> results = new ArrayList<>();
            for (Future<CommandResult> future : futures) {
                results.add(future.get(5, TimeUnit.SECONDS));
            }
            assertThat(results).allMatch(CommandResult::ok);
            assertThat(results).allMatch(r -> r.eventSeq() == results.getFirst().eventSeq());
        } finally {
            pool.shutdownNow();
        }

        assertThat(f.turnView().decisions()).hasSize(1);
        assertThat(f.store.size()).isEqualTo(eventsBefore + 1);
    }
}
