package spl.lae;

import demo.SerialEvaluator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import parser.ComputationNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end correctness: the concurrent engine must produce exactly what a simple
 * single-threaded evaluator produces, for many randomly generated expressions.
 *
 * <p>Most tests use small integer entries. Integer arithmetic on doubles is exact at these
 * sizes, so results can be compared with {@code ==} instead of a tolerance: any difference,
 * however small, is a real bug. Separate tests use random real numbers with a tolerance.
 */
@Timeout(120)
class EngineCorrectnessTest {

    // ---------------------------------------------------------------------------------------
    // Random expressions, every operator, many shapes and worker counts
    // ---------------------------------------------------------------------------------------

    @ParameterizedTest(name = "{0} workers")
    @ValueSource(ints = {1, 2, 3, 4, 8})
    void randomIntegerExpressionsMatchSerialExactly(int workers) {
        Random random = new Random(1000 + workers);
        try (LinearAlgebraEngine engine = new LinearAlgebraEngine(workers)) {
            for (int trial = 0; trial < 300; trial++) {
                int rows = 1 + random.nextInt(8), cols = 1 + random.nextInt(8);
                ComputationNode tree = randomTree(random, rows, cols, 4, true);
                double[][] expected = SerialEvaluator.evaluate(tree);  // does not modify the tree
                double[][] actual = engine.run(tree).getMatrix();
                assertExactlyEqual(expected, actual, "trial " + trial + " with " + workers + " workers");
            }
        }
    }

    @Test
    void deepRandomExpressionsMatchSerialExactly() {
        Random random = new Random(77);
        try (LinearAlgebraEngine engine = new LinearAlgebraEngine(4)) {
            for (int trial = 0; trial < 60; trial++) {
                ComputationNode tree = randomTree(random, 1 + random.nextInt(5), 1 + random.nextInt(5), 7, true);
                assertExactlyEqual(SerialEvaluator.evaluate(tree), engine.run(tree).getMatrix(), "trial " + trial);
            }
        }
    }

    @Test
    void randomRealNumberExpressionsMatchSerialClosely() {
        Random random = new Random(42);
        try (LinearAlgebraEngine engine = new LinearAlgebraEngine(4)) {
            for (int trial = 0; trial < 200; trial++) {
                ComputationNode tree = randomTree(random, 1 + random.nextInt(10), 1 + random.nextInt(10), 4, false);
                assertClose(SerialEvaluator.evaluate(tree), engine.run(tree).getMatrix(), "trial " + trial);
            }
        }
    }

    @Test
    void largeMatricesMatchSerial() {
        Random random = new Random(9);
        try (LinearAlgebraEngine engine = new LinearAlgebraEngine(4)) {
            double[][] a = realMatrix(random, 120, 90), b = realMatrix(random, 90, 150), c = realMatrix(random, 120, 150);
            // T(-(A*B) + C)
            ComputationNode tree = op("T", op("+", op("-", op("*", leaf(a), leaf(b))), leaf(c)));
            assertClose(SerialEvaluator.evaluate(tree), engine.run(tree).getMatrix(), "120x90 * 90x150");
        }
    }

    // ---------------------------------------------------------------------------------------
    // Each operator on its own, including the edge shapes
    // ---------------------------------------------------------------------------------------

    @Test
    void eachOperatorOnEdgeShapes() {
        int[][] shapes = {{1, 1}, {1, 7}, {7, 1}, {3, 5}, {5, 3}, {16, 16}};
        Random random = new Random(5);
        try (LinearAlgebraEngine engine = new LinearAlgebraEngine(3)) {
            for (int[] s : shapes) {
                int r = s[0], c = s[1];
                double[][] a = intMatrix(random, r, c), b = intMatrix(random, r, c), bt = intMatrix(random, c, r);
                check(engine, op("+", leaf(a), leaf(b)), "add " + r + "x" + c);
                check(engine, op("-", leaf(a)), "negate " + r + "x" + c);
                check(engine, op("T", leaf(a)), "transpose " + r + "x" + c);
                check(engine, op("*", leaf(a), leaf(bt)), "multiply " + r + "x" + c + " by " + c + "x" + r);
                check(engine, op("*", leaf(bt), leaf(a)), "multiply " + c + "x" + r + " by " + r + "x" + c);
            }
        }
    }

    @Test
    void knownSmallResults() {
        try (LinearAlgebraEngine engine = new LinearAlgebraEngine(2)) {
            double[][] a = {{1, 2}, {3, 4}}, b = {{5, 6}, {7, 8}};
            assertExactlyEqual(new double[][]{{19, 22}, {43, 50}}, engine.run(op("*", leaf(a), leaf(b))).getMatrix(), "A*B");
            assertExactlyEqual(new double[][]{{6, 8}, {10, 12}}, engine.run(op("+", leaf(a), leaf(b))).getMatrix(), "A+B");
            assertExactlyEqual(new double[][]{{1, 3}, {2, 4}}, engine.run(op("T", leaf(a))).getMatrix(), "T(A)");
            assertExactlyEqual(new double[][]{{-1, -2}, {-3, -4}}, engine.run(op("-", leaf(a))).getMatrix(), "-A");
            // Multiplication is not commutative: B*A differs from A*B.
            assertExactlyEqual(new double[][]{{23, 34}, {31, 46}}, engine.run(op("*", leaf(b), leaf(a))).getMatrix(), "B*A");
        }
    }

    @Test
    void algebraicIdentitiesHold() {
        Random random = new Random(11);
        try (LinearAlgebraEngine engine = new LinearAlgebraEngine(4)) {
            for (int trial = 0; trial < 50; trial++) {
                int r = 1 + random.nextInt(9), k = 1 + random.nextInt(9), c = 1 + random.nextInt(9);
                double[][] a = intMatrix(random, r, k), b = intMatrix(random, k, c);
                // T(T(A)) = A
                assertExactlyEqual(a, engine.run(op("T", op("T", leaf(a)))).getMatrix(), "T(T(A))");
                // -(-A) = A
                assertExactlyEqual(a, engine.run(op("-", op("-", leaf(a)))).getMatrix(), "-(-A)");
                // A + (-A) = 0
                assertExactlyEqual(new double[r][k], engine.run(op("+", leaf(a), op("-", leaf(a)))).getMatrix(), "A + (-A)");
                // T(A*B) = T(B) * T(A)
                double[][] left = engine.run(op("T", op("*", leaf(a), leaf(b)))).getMatrix();
                double[][] right = engine.run(op("*", op("T", leaf(b)), op("T", leaf(a)))).getMatrix();
                assertExactlyEqual(left, right, "T(A*B) = T(B)*T(A)");
            }
        }
    }

    @Test
    void naryOperationsKeepEveryOperandAndTheirOrder() {
        Random random = new Random(13);
        try (LinearAlgebraEngine engine = new LinearAlgebraEngine(3)) {
            for (int trial = 0; trial < 40; trial++) {
                int n = 3 + random.nextInt(4);
                List<ComputationNode> addends = new ArrayList<>(), factors = new ArrayList<>();
                int dim = 1 + random.nextInt(4);
                for (int i = 0; i < n; i++) {
                    addends.add(leaf(intMatrix(random, 2, 3)));
                    factors.add(leaf(intMatrix(random, dim, dim)));
                }
                check(engine, new ComputationNode("+", addends), "sum of " + n);
                check(engine, new ComputationNode("*", factors), "product of " + n);
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Reuse and concurrent callers
    // ---------------------------------------------------------------------------------------

    @Test
    void oneEngineStaysCorrectAcrossManyRunsAndAfterErrors() {
        Random random = new Random(21);
        try (LinearAlgebraEngine engine = new LinearAlgebraEngine(3)) {
            for (int trial = 0; trial < 150; trial++) {
                if (trial % 10 == 5) {
                    // A dimension mismatch must fail without breaking the next run.
                    ComputationNode bad = op("+", leaf(intMatrix(random, 2, 2)), leaf(intMatrix(random, 3, 3)));
                    assertThrows(IllegalArgumentException.class, () -> engine.run(bad));
                    continue;
                }
                ComputationNode tree = randomTree(random, 1 + random.nextInt(6), 1 + random.nextInt(6), 3, true);
                assertExactlyEqual(SerialEvaluator.evaluate(tree), engine.run(tree).getMatrix(), "trial " + trial);
            }
        }
    }

    @Test
    void severalEnginesRunningAtTheSameTimeAreAllCorrect() throws Exception {
        int callers = 4;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        try {
            List<Future<?>> results = new ArrayList<>();
            for (int c = 0; c < callers; c++) {
                final int seed = 300 + c;
                results.add(pool.submit(() -> {
                    Random random = new Random(seed);
                    try (LinearAlgebraEngine engine = new LinearAlgebraEngine(2 + seed % 3)) {
                        for (int trial = 0; trial < 100; trial++) {
                            ComputationNode tree = randomTree(random, 1 + random.nextInt(6), 1 + random.nextInt(6), 4, true);
                            assertExactlyEqual(SerialEvaluator.evaluate(tree), engine.run(tree).getMatrix(),
                                "caller " + seed + " trial " + trial);
                        }
                    }
                    return null;
                }));
            }
            for (Future<?> f : results) f.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void oneEngineSharedByManyCallersIsCorrect() throws Exception {
        int callers = 4;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        try (LinearAlgebraEngine engine = new LinearAlgebraEngine(4)) {
            List<Future<?>> results = new ArrayList<>();
            for (int c = 0; c < callers; c++) {
                final int seed = 500 + c;
                results.add(pool.submit(() -> {
                    Random random = new Random(seed);
                    for (int trial = 0; trial < 80; trial++) {
                        ComputationNode tree = randomTree(random, 1 + random.nextInt(6), 1 + random.nextInt(6), 4, true);
                        double[][] expected = SerialEvaluator.evaluate(tree);
                        assertExactlyEqual(expected, engine.run(tree).getMatrix(), "caller " + seed + " trial " + trial);
                    }
                    return null;
                }));
            }
            for (Future<?> f : results) f.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    // ---------------------------------------------------------------------------------------
    // Random expression generator
    // ---------------------------------------------------------------------------------------

    /**
     * Builds a random expression whose result has shape {@code rows x cols}.
     * Shapes are chosen top-down so every generated expression is valid.
     */
    static ComputationNode randomTree(Random random, int rows, int cols, int depth, boolean integers) {
        if (depth == 0 || random.nextInt(4) == 0) {
            return leaf(integers ? intMatrix(random, rows, cols) : realMatrix(random, rows, cols));
        }
        switch (random.nextInt(4)) {
            case 0: { // n-ary addition, all operands share the result shape
                int n = 2 + random.nextInt(3);
                List<ComputationNode> operands = new ArrayList<>();
                for (int i = 0; i < n; i++) operands.add(randomTree(random, rows, cols, depth - 1, integers));
                return new ComputationNode("+", operands);
            }
            case 1: { // n-ary multiplication with random inner dimensions
                int n = 2 + random.nextInt(2);
                List<ComputationNode> operands = new ArrayList<>();
                int left = rows;
                for (int i = 0; i < n; i++) {
                    int right = i == n - 1 ? cols : 1 + random.nextInt(6);
                    operands.add(randomTree(random, left, right, depth - 1, integers));
                    left = right;
                }
                return new ComputationNode("*", operands);
            }
            case 2:
                return op("-", randomTree(random, rows, cols, depth - 1, integers));
            default:
                return op("T", randomTree(random, cols, rows, depth - 1, integers));
        }
    }

    private static ComputationNode leaf(double[][] m) {
        return new ComputationNode(m);
    }

    private static ComputationNode op(String operator, ComputationNode... operands) {
        return new ComputationNode(operator, new ArrayList<>(List.of(operands)));
    }

    private static double[][] intMatrix(Random random, int rows, int cols) {
        double[][] m = new double[rows][cols];
        for (int i = 0; i < rows; i++)
            for (int j = 0; j < cols; j++) m[i][j] = random.nextInt(7) - 3;   // -3..3
        return m;
    }

    private static double[][] realMatrix(Random random, int rows, int cols) {
        double[][] m = new double[rows][cols];
        for (int i = 0; i < rows; i++)
            for (int j = 0; j < cols; j++) m[i][j] = random.nextGaussian();
        return m;
    }

    private static void check(LinearAlgebraEngine engine, ComputationNode tree, String context) {
        assertExactlyEqual(SerialEvaluator.evaluate(tree), engine.run(tree).getMatrix(), context);
    }

    private static void assertExactlyEqual(double[][] expected, double[][] actual, String context) {
        assertEquals(expected.length, actual.length, context + ": row count");
        for (int i = 0; i < expected.length; i++)
            assertArrayEquals(expected[i], actual[i], context + ": row " + i);
    }

    private static void assertClose(double[][] expected, double[][] actual, String context) {
        assertEquals(expected.length, actual.length, context + ": row count");
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i].length, actual[i].length, context + ": column count in row " + i);
            for (int j = 0; j < expected[i].length; j++) {
                double tolerance = 1e-9 * Math.max(1, Math.abs(expected[i][j]));
                assertEquals(expected[i][j], actual[i][j], tolerance, context + ": at " + i + "," + j);
            }
        }
    }
}
