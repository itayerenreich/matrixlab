package memory;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Random;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for whole-row copying: {@link SharedVector#snapshot()} and the
 * {@link SharedMatrix#readRowMajor()} implementation built on it.
 *
 * <p>The single-threaded tests check that the faster implementation returns exactly what the
 * old element-by-element implementation returned. The concurrent tests check the property the
 * locks exist for: a reader never observes a half-written row or a half-written matrix.
 */
@Timeout(30)
class SnapshotTest {

    // ---------------------------------------------------------------------------------------
    // SharedVector.snapshot()
    // ---------------------------------------------------------------------------------------

    @Test
    void snapshotReturnsAllValuesInOrder() {
        SharedVector v = new SharedVector(new double[]{3, -1.5, 0, 7}, VectorOrientation.ROW_MAJOR);
        assertArrayEquals(new double[]{3, -1.5, 0, 7}, v.snapshot());
    }

    @Test
    void snapshotOfSingleElementVector() {
        SharedVector v = new SharedVector(new double[]{42}, VectorOrientation.COLUMN_MAJOR);
        assertArrayEquals(new double[]{42}, v.snapshot());
    }

    @Test
    void changingTheSnapshotDoesNotChangeTheVector() {
        SharedVector v = new SharedVector(new double[]{1, 2, 3}, VectorOrientation.ROW_MAJOR);
        double[] copy = v.snapshot();
        copy[0] = 999;
        assertEquals(1, v.get(0));
    }

    @Test
    void changingTheVectorDoesNotChangeAnEarlierSnapshot() {
        SharedVector v = new SharedVector(new double[]{1, 2, 3}, VectorOrientation.ROW_MAJOR);
        double[] before = v.snapshot();
        v.negate();
        assertArrayEquals(new double[]{1, 2, 3}, before);
        assertArrayEquals(new double[]{-1, -2, -3}, v.snapshot());
    }

    @Test
    void eachSnapshotIsANewArray() {
        SharedVector v = new SharedVector(new double[]{1, 2}, VectorOrientation.ROW_MAJOR);
        assertNotSame(v.snapshot(), v.snapshot());
    }

    @Test
    void snapshotReflectsEveryKindOfWrite() {
        SharedVector v = new SharedVector(new double[]{1, 2}, VectorOrientation.ROW_MAJOR);
        v.add(new SharedVector(new double[]{10, 20}, VectorOrientation.ROW_MAJOR));
        assertArrayEquals(new double[]{11, 22}, v.snapshot());
        v.negate();
        assertArrayEquals(new double[]{-11, -22}, v.snapshot());
        // [-11, -22] · [[1, 0, 2], [0, 1, 3]] = [-11, -22, -88]
        v.multiplyByRows(new double[][]{{1, 0, 2}, {0, 1, 3}});
        assertArrayEquals(new double[]{-11, -22, -88}, v.snapshot());
    }

    @Test
    void snapshotMatchesElementByElementReads() {
        Random random = new Random(1);
        for (int trial = 0; trial < 200; trial++) {
            double[] data = randomArray(random, 1 + random.nextInt(300));
            SharedVector v = new SharedVector(data, VectorOrientation.ROW_MAJOR);
            double[] viaSnapshot = v.snapshot();
            for (int i = 0; i < data.length; i++) assertEquals(v.get(i), viaSnapshot[i]);
        }
    }

    @Test
    void snapshotReleasesItsReadLock() throws Exception {
        SharedVector v = new SharedVector(new double[]{1, 2}, VectorOrientation.ROW_MAJOR);
        v.snapshot();
        // A writer on another thread must not be blocked by a lock the snapshot forgot to release.
        ExecutorService other = Executors.newSingleThreadExecutor();
        try {
            other.submit(v::negate).get(2, TimeUnit.SECONDS);
        } finally {
            other.shutdownNow();
        }
        assertArrayEquals(new double[]{-1, -2}, v.snapshot());
    }

    @Test
    void snapshotWorksWhileTheSameThreadAlreadyHoldsTheReadLock() {
        // readRowMajor holds every read lock and then calls snapshot(); the lock is reentrant.
        SharedVector v = new SharedVector(new double[]{5, 6}, VectorOrientation.ROW_MAJOR);
        v.readLock();
        try {
            assertArrayEquals(new double[]{5, 6}, v.snapshot());
        } finally {
            v.readUnlock();
        }
    }

    // ---------------------------------------------------------------------------------------
    // SharedMatrix.readRowMajor() — same answers as before the optimization
    // ---------------------------------------------------------------------------------------

    @Test
    void readRowMajorMatchesTheOldElementByElementImplementation() {
        Random random = new Random(2);
        for (int trial = 0; trial < 300; trial++) {
            double[][] data = randomMatrix(random, 1 + random.nextInt(40), 1 + random.nextInt(40));
            SharedMatrix m = new SharedMatrix();
            if (random.nextBoolean()) m.loadRowMajor(data); else m.loadColumnMajor(data);
            assertDeepEquals(readElementByElement(m), m.readRowMajor(), "trial " + trial);
        }
    }

    @Test
    void rowMajorLoadReadsBackUnchanged() {
        Random random = new Random(3);
        for (int trial = 0; trial < 200; trial++) {
            double[][] data = randomMatrix(random, 1 + random.nextInt(30), 1 + random.nextInt(30));
            SharedMatrix m = new SharedMatrix();
            m.loadRowMajor(data);
            assertDeepEquals(data, m.readRowMajor(), "trial " + trial);
        }
    }

    @Test
    void columnMajorLoadReadsBackUnchanged() {
        // loadColumnMajor stores columns, readRowMajor must reassemble the original rows.
        Random random = new Random(4);
        for (int trial = 0; trial < 200; trial++) {
            double[][] data = randomMatrix(random, 1 + random.nextInt(30), 1 + random.nextInt(30));
            SharedMatrix m = new SharedMatrix();
            m.loadColumnMajor(data);
            assertDeepEquals(data, m.readRowMajor(), "trial " + trial);
        }
    }

    @Test
    void transposingEveryVectorReadsBackTheTranspose() {
        Random random = new Random(5);
        for (int trial = 0; trial < 200; trial++) {
            int rows = 1 + random.nextInt(25), cols = 1 + random.nextInt(25);
            double[][] data = randomMatrix(random, rows, cols);
            SharedMatrix m = new SharedMatrix(data);
            for (int i = 0; i < m.length(); i++) m.get(i).transpose();
            assertDeepEquals(transpose(data), m.readRowMajor(), "trial " + trial);
        }
    }

    @Test
    void edgeShapesReadBackUnchanged() {
        double[][][] shapes = {
            {{7}},                       // 1x1
            {{1, 2, 3, 4, 5}},           // one row
            {{1}, {2}, {3}, {4}, {5}},   // one column
        };
        for (double[][] data : shapes) {
            SharedMatrix rowMajor = new SharedMatrix(data);
            assertDeepEquals(data, rowMajor.readRowMajor(), "row-major");
            SharedMatrix colMajor = new SharedMatrix();
            colMajor.loadColumnMajor(data);
            assertDeepEquals(data, colMajor.readRowMajor(), "column-major");
        }
    }

    @Test
    void largeMatrixReadsBackUnchanged() {
        double[][] data = randomMatrix(new Random(6), 500, 400);
        SharedMatrix m = new SharedMatrix(data);
        assertDeepEquals(data, m.readRowMajor(), "500x400");
        SharedMatrix c = new SharedMatrix();
        c.loadColumnMajor(data);
        assertDeepEquals(data, c.readRowMajor(), "500x400 column-major");
    }

    @Test
    void resultIsIndependentOfTheMatrix() {
        SharedMatrix m = new SharedMatrix(new double[][]{{1, 2}, {3, 4}});
        double[][] out = m.readRowMajor();
        out[0][0] = 999;
        m.get(1).negate();
        assertDeepEquals(new double[][]{{1, 2}, {-3, -4}}, m.readRowMajor(), "matrix");
        assertDeepEquals(new double[][]{{999, 2}, {3, 4}}, out, "earlier result");
    }

    @Test
    void resultRowsAreNotSharedWithEachOther() {
        SharedMatrix m = new SharedMatrix(new double[][]{{1, 2}, {3, 4}, {5, 6}});
        double[][] out = m.readRowMajor();
        for (int i = 0; i < out.length; i++)
            for (int j = i + 1; j < out.length; j++) assertNotSame(out[i], out[j]);
    }

    @Test
    void readRowMajorReleasesEveryLock() throws Exception {
        SharedMatrix m = new SharedMatrix(new double[][]{{1, 2}, {3, 4}, {5, 6}});
        m.readRowMajor();
        ExecutorService other = Executors.newSingleThreadExecutor();
        try {
            other.submit(() -> { for (int i = 0; i < m.length(); i++) m.get(i).negate(); })
                .get(2, TimeUnit.SECONDS);
        } finally {
            other.shutdownNow();
        }
        assertDeepEquals(new double[][]{{-1, -2}, {-3, -4}, {-5, -6}}, m.readRowMajor(), "after negate");
    }

    // ---------------------------------------------------------------------------------------
    // Concurrency: readers never see half-written data
    // ---------------------------------------------------------------------------------------

    /**
     * A writer keeps negating a long row. Every value starts positive, and a negate flips all
     * of them under one write lock, so a correct snapshot is either all-positive or
     * all-negative. A mixed snapshot would mean the copy raced with a write.
     */
    @RepeatedTest(5)
    void snapshotNeverSeesAHalfNegatedRow() throws Exception {
        double[] data = new double[2_000];
        for (int i = 0; i < data.length; i++) data[i] = i + 1;
        SharedVector v = new SharedVector(data, VectorOrientation.ROW_MAJOR);

        runConcurrently(v::negate, () -> {
            double[] s = v.snapshot();
            boolean positive = s[0] > 0;
            for (int i = 0; i < s.length; i++) {
                if ((s[i] > 0) != positive) return "row mixed signs at index " + i;
                if (Math.abs(s[i]) != i + 1) return "wrong magnitude at index " + i;
            }
            return null;
        });
    }

    /**
     * A writer locks every row of the matrix (in index order, the same order readers use) and
     * negates all of them before unlocking. readRowMajor holds all read locks for the whole
     * copy, so it must see either the entire matrix before a negate or the entire matrix after
     * it — never some rows flipped and others not.
     */
    @RepeatedTest(5)
    void readRowMajorNeverSeesAHalfNegatedMatrix() throws Exception {
        int rows = 40, cols = 60;
        double[][] data = new double[rows][cols];
        for (int i = 0; i < rows; i++)
            for (int j = 0; j < cols; j++) data[i][j] = i * cols + j + 1;
        SharedMatrix m = new SharedMatrix(data);

        Runnable negateWholeMatrix = () -> {
            for (int i = 0; i < rows; i++) m.get(i).writeLock();
            try {
                // Each row's negate re-enters its own write lock, which this thread already holds.
                for (int i = 0; i < rows; i++) m.get(i).negate();
            } finally {
                for (int i = rows - 1; i >= 0; i--) m.get(i).writeUnlock();
            }
        };

        runConcurrently(negateWholeMatrix, () -> {
            double[][] s = m.readRowMajor();
            boolean positive = s[0][0] > 0;
            for (int i = 0; i < rows; i++)
                for (int j = 0; j < cols; j++) {
                    if ((s[i][j] > 0) != positive) return "matrix mixed signs at " + i + "," + j;
                    if (Math.abs(s[i][j]) != i * cols + j + 1) return "wrong magnitude at " + i + "," + j;
                }
            return null;
        });
    }

    /** Same as above, for a matrix stored by columns, which takes the transposing read path. */
    @RepeatedTest(5)
    void columnMajorReadNeverSeesAHalfNegatedMatrix() throws Exception {
        int rows = 30, cols = 50;
        double[][] data = new double[rows][cols];
        for (int i = 0; i < rows; i++)
            for (int j = 0; j < cols; j++) data[i][j] = i * cols + j + 1;
        SharedMatrix m = new SharedMatrix();
        m.loadColumnMajor(data);
        int vectors = m.length();

        Runnable negateWholeMatrix = () -> {
            for (int i = 0; i < vectors; i++) m.get(i).writeLock();
            try {
                for (int i = 0; i < vectors; i++) m.get(i).negate();
            } finally {
                for (int i = vectors - 1; i >= 0; i--) m.get(i).writeUnlock();
            }
        };

        runConcurrently(negateWholeMatrix, () -> {
            double[][] s = m.readRowMajor();
            boolean positive = s[0][0] > 0;
            for (int i = 0; i < rows; i++)
                for (int j = 0; j < cols; j++) {
                    if ((s[i][j] > 0) != positive) return "matrix mixed signs at " + i + "," + j;
                    if (Math.abs(s[i][j]) != i * cols + j + 1) return "wrong magnitude at " + i + "," + j;
                }
            return null;
        });
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    /** What readRowMajor computed before the optimization: one locked get() per element. */
    private static double[][] readElementByElement(SharedMatrix m) {
        if (m.getOrientation() == VectorOrientation.ROW_MAJOR) {
            double[][] out = new double[m.length()][];
            for (int i = 0; i < m.length(); i++) {
                out[i] = new double[m.get(i).length()];
                for (int j = 0; j < out[i].length; j++) out[i][j] = m.get(i).get(j);
            }
            return out;
        }
        int rows = m.get(0).length();
        double[][] out = new double[rows][m.length()];
        for (int j = 0; j < m.length(); j++)
            for (int i = 0; i < rows; i++) out[i][j] = m.get(j).get(i);
        return out;
    }

    /**
     * Runs one writer and three readers at the same time for a fixed number of reads.
     * Each read returns null when the snapshot is consistent, or a description of the problem.
     */
    private static void runConcurrently(Runnable write, Callable<String> read) throws Exception {
        int readers = 3, readsPerReader = 3_000;
        ExecutorService pool = Executors.newFixedThreadPool(readers + 1);
        AtomicBoolean done = new AtomicBoolean(false);
        AtomicReference<String> failure = new AtomicReference<>();
        AtomicLong writes = new AtomicLong();
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> writer = pool.submit(() -> {
                await(start);
                while (!done.get()) { write.run(); writes.incrementAndGet(); }
            });
            Future<?>[] readerFutures = new Future<?>[readers];
            for (int r = 0; r < readers; r++) {
                readerFutures[r] = pool.submit(() -> {
                    await(start);
                    for (int k = 0; k < readsPerReader && failure.get() == null; k++) {
                        String problem = read.call();
                        if (problem != null) failure.compareAndSet(null, problem);
                    }
                    return null;
                });
            }
            start.countDown();
            for (Future<?> f : readerFutures) f.get(20, TimeUnit.SECONDS);
            done.set(true);
            writer.get(5, TimeUnit.SECONDS);
        } finally {
            done.set(true);
            pool.shutdownNow();
        }
        assertNull(failure.get(), failure.get());
        // Guard against a vacuous pass: the reads must really have overlapped with writes.
        assertTrue(writes.get() > 10, "writer only ran " + writes.get() + " times");
    }

    private static void await(CountDownLatch latch) {
        try { latch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static void assertDeepEquals(double[][] expected, double[][] actual, String context) {
        assertEquals(expected.length, actual.length, context + ": row count");
        for (int i = 0; i < expected.length; i++)
            assertArrayEquals(expected[i], actual[i], context + ": row " + i);
    }

    private static double[][] transpose(double[][] a) {
        double[][] t = new double[a[0].length][a.length];
        for (int i = 0; i < a.length; i++)
            for (int j = 0; j < a[0].length; j++) t[j][i] = a[i][j];
        return t;
    }

    private static double[] randomArray(Random random, int n) {
        double[] a = new double[n];
        for (int i = 0; i < n; i++) a[i] = random.nextGaussian() * 100;
        return a;
    }

    private static double[][] randomMatrix(Random random, int rows, int cols) {
        double[][] m = new double[rows][];
        for (int i = 0; i < rows; i++) m[i] = randomArray(random, cols);
        return m;
    }
}
