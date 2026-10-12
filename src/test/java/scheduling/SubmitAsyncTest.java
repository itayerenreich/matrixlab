package scheduling;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link TiredExecutor#submitAsync}: returns without waiting for the task, and its future reports
 * exactly when and how the task finished. The dataflow scheduler is built on this.
 */
@Timeout(20)
class SubmitAsyncTest {

    @Test
    void futureCompletesAfterTheTaskRanAndSeesItsWrites() throws Exception {
        try (TiredExecutor executor = new TiredExecutor(2)) {
            int[] box = new int[1];   // plain, non-volatile: visibility must come from the future
            CompletableFuture<Void> done = executor.submitAsync(() -> box[0] = 42);
            done.get(5, TimeUnit.SECONDS);
            assertEquals(42, box[0]);
            assertFalse(done.isCompletedExceptionally());
        }
    }

    @Test
    void returnsBeforeTheTaskFinishes() throws Exception {
        try (TiredExecutor executor = new TiredExecutor(2)) {
            CountDownLatch release = new CountDownLatch(1);
            try {
                CompletableFuture<Void> done = executor.submitAsync(() -> await(release));
                // submitAsync already returned while the task is still blocked.
                Thread.sleep(50);
                assertFalse(done.isDone());
                release.countDown();
                done.get(5, TimeUnit.SECONDS);
                assertTrue(done.isDone());
            } finally {
                // Always release, even when an assertion fails, or shutdown would wait forever.
                release.countDown();
            }
        }
    }

    @Test
    void failedTaskCompletesTheFutureExceptionallyWithItsOwnException() throws Exception {
        try (TiredExecutor executor = new TiredExecutor(1)) {
            IllegalStateException boom = new IllegalStateException("boom");
            CompletableFuture<Void> done = executor.submitAsync(() -> { throw boom; });
            ExecutionException error = assertThrows(ExecutionException.class, () -> done.get(5, TimeUnit.SECONDS));
            assertSame(boom, error.getCause());
        }
    }

    @Test
    void workerStaysUsableAfterAFailure() throws Exception {
        try (TiredExecutor executor = new TiredExecutor(1)) {
            CompletableFuture<Void> failed = executor.submitAsync(() -> { throw new RuntimeException("first"); });
            assertThrows(ExecutionException.class, () -> failed.get(5, TimeUnit.SECONDS));
            AtomicInteger ran = new AtomicInteger();
            executor.submitAsync(ran::incrementAndGet).get(5, TimeUnit.SECONDS);
            assertEquals(1, ran.get());
            assertTrue(executor.getWorkerReport().contains("failed=1"));
        }
    }

    /** Far more tasks than workers: each call waits for a free worker, none is lost, none hangs. */
    @RepeatedTest(5)
    void manyMoreTasksThanWorkersAllComplete() throws Exception {
        try (TiredExecutor executor = new TiredExecutor(3)) {
            AtomicInteger ran = new AtomicInteger();
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (int i = 0; i < 2_000; i++) futures.add(executor.submitAsync(ran::incrementAndGet));
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(10, TimeUnit.SECONDS);
            assertEquals(2_000, ran.get());
        }
    }

    @Test
    void tasksRunInParallelOnDifferentWorkers() throws Exception {
        int workers = 4;
        try (TiredExecutor executor = new TiredExecutor(workers)) {
            // Each task waits until all of them are running at once; only possible if they overlap.
            CyclicBarrier allRunning = new CyclicBarrier(workers);
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (int i = 0; i < workers; i++) {
                futures.add(executor.submitAsync(() -> {
                    try { allRunning.await(5, TimeUnit.SECONDS); }
                    catch (Exception e) { throw new IllegalStateException("tasks did not overlap", e); }
                }));
            }
            for (CompletableFuture<Void> f : futures) f.get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void callbackRunsAfterTheTaskAndCanHandOffToAnotherThread() throws Exception {
        try (TiredExecutor executor = new TiredExecutor(2)) {
            BlockingQueue<String> messages = new LinkedBlockingQueue<>();
            AtomicReference<String> order = new AtomicReference<>("");
            executor.submitAsync(() -> order.updateAndGet(s -> s + "task;"))
                .whenComplete((ok, error) -> {
                    order.updateAndGet(s -> s + "callback;");
                    messages.add("done");
                });
            assertEquals("done", messages.poll(5, TimeUnit.SECONDS));
            assertEquals("task;callback;", order.get());
        }
    }

    @Test
    void rejectsNullTask() {
        try (TiredExecutor executor = new TiredExecutor(1)) {
            assertThrows(IllegalArgumentException.class, () -> executor.submitAsync(null));
        }
    }

    @Test
    void rejectsAfterShutdown() throws Exception {
        TiredExecutor executor = new TiredExecutor(1);
        executor.shutdown();
        assertThrows(RejectedExecutionException.class, () -> executor.submitAsync(() -> {}));
    }

    @Test
    void aWorkerCannotSubmitWorkItself() throws Exception {
        // A worker that blocks waiting for a free worker could deadlock the pool; it is refused.
        try (TiredExecutor executor = new TiredExecutor(1)) {
            CompletableFuture<Void> outer = executor.submitAsync(() -> executor.submitAsync(() -> {}));
            ExecutionException error = assertThrows(ExecutionException.class, () -> outer.get(5, TimeUnit.SECONDS));
            assertInstanceOf(RejectedExecutionException.class, error.getCause());
        }
    }

    @Test
    void shutdownWaitsForAsyncTasksAlreadyAccepted() throws Exception {
        TiredExecutor executor = new TiredExecutor(2);
        AtomicInteger finished = new AtomicInteger();
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            futures.add(executor.submitAsync(() -> {
                try { Thread.sleep(20); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                finished.incrementAndGet();
            }));
        }
        executor.shutdown();
        assertEquals(6, finished.get());
        for (CompletableFuture<Void> f : futures) assertTrue(f.isDone());
    }

    private static void await(CountDownLatch latch) {
        try { latch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
