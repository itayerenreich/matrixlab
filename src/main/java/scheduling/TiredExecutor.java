package scheduling;

import java.util.*;
import java.util.concurrent.*;

/** Bounded, blocking task handoff with least accumulated weighted-runtime scheduling. */
public class TiredExecutor implements AutoCloseable {
    private final TiredThread[] workers;
    private final PriorityQueue<TiredThread> idle = new PriorityQueue<>(
        Comparator.comparingDouble(TiredThread::getFatigue).thenComparingInt(TiredThread::getWorkerId));
    private final Object monitor = new Object();
    private int inFlight;
    private boolean closed;

    public TiredExecutor(int numThreads) { this(numThreads, 42L); }
    public TiredExecutor(int numThreads, long seed) {
        if (numThreads <= 0) throw new IllegalArgumentException("numThreads must be positive");
        workers = new TiredThread[numThreads];
        Random random = new Random(seed);
        for (int i = 0; i < numThreads; i++) {
            workers[i] = new TiredThread(i, 0.5 + random.nextDouble());
            idle.add(workers[i]);
        }
        for (TiredThread worker : workers) worker.start();
    }
    private void rejectWorkerCall() {
        for (TiredThread worker : workers)
            if (Thread.currentThread() == worker)
                throw new RejectedExecutionException("Nested blocking executor calls are unsupported");
    }
    /** Fire-and-forget submission; failures are counted in the worker report. */
    public void submit(Runnable task) { submitTracked(task); }

    /**
     * Hands {@code task} to the least fatigued idle worker and returns at once, without waiting
     * for the task to run. The returned future completes when the task finishes: normally if it
     * returned, exceptionally with the task's own exception if it threw. A failed task never
     * kills its worker.
     *
     * <p>Unlike {@link #submitAll}, this does not wait for anything except a free worker: when
     * every worker is busy the call blocks until one becomes idle (backpressure). Everything the
     * task wrote is visible to whoever observes the future's completion.
     *
     * <p>Completion callbacks attached to the future run on the worker thread that ran the task,
     * after the worker is already back in the idle pool. They must not block or submit new work.
     *
     * @throws IllegalArgumentException   if {@code task} is null
     * @throws RejectedExecutionException if the executor is shut down, the caller is interrupted
     *                                    while waiting for a worker, or the caller is itself one
     *                                    of this executor's workers (that could deadlock)
     */
    public CompletableFuture<Void> submitAsync(Runnable task) { return submitTracked(task); }

    private CompletableFuture<Void> submitTracked(Runnable task) {
        if (task == null) throw new IllegalArgumentException("task cannot be null");
        rejectWorkerCall();
        CompletableFuture<Void> result = new CompletableFuture<>();
        synchronized (monitor) {
            while (!closed && idle.isEmpty()) {
                try { monitor.wait(); }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RejectedExecutionException("Interrupted while waiting for a worker", e);
                }
            }
            if (closed) throw new RejectedExecutionException("Executor is shut down");
            if (Thread.currentThread().isInterrupted())
                throw new RejectedExecutionException("Submission interrupted");
            TiredThread worker = idle.remove();
            inFlight++;
            worker.newTask(task, failure -> {
                synchronized (monitor) {
                    // Worker metrics are stable before returning it to the priority queue.
                    idle.add(worker);
                    inFlight--;
                    monitor.notifyAll();
                }
                if (failure == null) result.complete(null);
                else result.completeExceptionally(failure);
            });
        }
        return result;
    }
    /** Waits for this batch only. Drains accepted tasks before reporting any failure. */
    public void submitAll(Iterable<Runnable> tasks) {
        if (tasks == null) throw new IllegalArgumentException("tasks cannot be null");
        rejectWorkerCall();
        List<Runnable> copy = new ArrayList<>();
        for (Runnable task : tasks) {
            if (task == null) throw new IllegalArgumentException("task cannot be null");
            copy.add(task);
        }
        synchronized (monitor) {
            if (closed) throw new RejectedExecutionException("Executor is shut down");
        }
        List<CompletableFuture<Void>> accepted = new ArrayList<>();
        RuntimeException submissionFailure = null;
        try { for (Runnable task : copy) accepted.add(submitTracked(task)); }
        catch (RuntimeException e) { submissionFailure = e; }
        Throwable taskFailure = null;
        boolean interrupted = Thread.interrupted();
        for (CompletableFuture<Void> result : accepted) {
            boolean done = false;
            while (!done) {
                try { result.get(); done = true; }
                catch (InterruptedException e) { interrupted = true; }
                catch (ExecutionException e) {
                    if (taskFailure == null) taskFailure = e.getCause();
                    done = true;
                }
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
        if (submissionFailure != null) throw submissionFailure;
        if (taskFailure != null) throw new CompletionException("Task batch failed", taskFailure);
        if (interrupted) throw new RejectedExecutionException("Batch wait interrupted; accepted tasks drained");
    }
    public void shutdown() throws InterruptedException {
        rejectWorkerCall();
        boolean interrupted = false;
        synchronized (monitor) {
            closed = true;
            monitor.notifyAll();
            while (inFlight != 0) {
                try { monitor.wait(); }
                catch (InterruptedException e) { interrupted = true; }
            }
        }
        for (TiredThread worker : workers) worker.shutdown();
        for (TiredThread worker : workers) {
            while (worker.isAlive()) {
                try { worker.join(); }
                catch (InterruptedException e) { interrupted = true; }
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
            throw new InterruptedException("Shutdown completed after interruption");
        }
    }
    @Override public void close() {
        try { shutdown(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
    public String getWorkerReport() {
        StringBuilder result = new StringBuilder();
        for (TiredThread w : workers) result.append("Worker ").append(w.getWorkerId())
            .append(" | busy=").append(w.isBusy()).append(" | tasks=").append(w.getCompletedTasks())
            .append(" | failed=").append(w.getFailedTasks()).append(" | timeUsed(ns)=").append(w.getTimeUsed())
            .append(" | timeIdle(ns)=").append(w.getTimeIdle()).append(" | fatigue=").append(w.getFatigue()).append('\n');
        return result.toString();
    }
}
