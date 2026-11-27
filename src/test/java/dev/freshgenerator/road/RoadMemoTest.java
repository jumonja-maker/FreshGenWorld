package dev.freshgenerator.road;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.*;

/** Contracts of the road planner's memo (code audits 26.11 OpenAI FG94-02/FG094-02/FG094-03 and 27.11 K-10/K-11). */
class RoadMemoTest {
    @Test void aFailureIsComputedAgainAndAKnownValueReplacesAnAbsentOne() {
        var memo = new RoadNetwork.Memo<Long, String>(4, "test");
        int[] calls = {0};
        assertNull(memo.get(1L, () -> { calls[0]++; throw new IllegalStateException("transient"); }));
        assertEquals("ok", memo.get(1L, () -> { calls[0]++; return "ok"; }), "a transient failure is no fact about the world");
        assertEquals(2, calls[0]);
        assertNull(memo.get(2L, () -> null));
        memo.offer(2L, "built");
        assertEquals("built", memo.get(2L, () -> "never"), "the village the world built replaces the absent plan");
    }

    @Test void aValueOfferedWhileTheKeyIsComputedWins() throws Exception {
        var memo = new RoadNetwork.Memo<Long, String>(4, "test");
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        Thread t = new Thread(() -> memo.get(7L, () -> {
            started.countDown();
            try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return null;
        }));
        t.start();
        started.await();
        memo.offer(7L, "known");
        release.countDown();
        t.join();
        assertEquals("known", memo.get(7L, () -> "recomputed"));
    }

    @Test void failuresAndOffersKeepTheMemoBounded() {
        var memo = new RoadNetwork.Memo<Long, String>(4, "test");
        for (long k = 0; k < 1000; k++) memo.get(k, () -> { throw new IllegalStateException("x"); });
        assertTrue(memo.order.size() <= 8, "failed keys pile up in the eviction order: " + memo.order.size());
        for (long k = 0; k < 1000; k++) memo.offer(10_000 + k, "v");
        assertTrue(memo.map.size() <= 5, "offers pass the limit: " + memo.map.size());
    }
}
