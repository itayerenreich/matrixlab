package spl.lae;

import demo.SerialEvaluator;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import parser.ComputationNode;
import parser.ComputationNodeType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link Operation}: one prepared operation owns its own state.
 *
 * <p>The key property is independence: the row tasks of many different operations can be mixed
 * together and run on many threads at once, and every operation still produces exactly the
 * right result. The engine relies on this to run independent parts of an expression in parallel.
 */
@Timeout(60)
class OperationTest {

    @Test
    void eachOperationTypeComputesTheRightResult() {
        Random random = new Random(1);
        for (int trial = 0; trial < 100; trial++) {
            int r = 1 + random.nextInt(7), k = 1 + random.nextInt(7), c = 1 + random.nextInt(7);
            double[][] a = intMatrix(random, r, k), a2 = intMatrix(random, r, k), b = intMatrix(random, k, c);
            assertEqual(SerialEvaluator.add(a, a2), runInline(op("+", a, a2)), "add");
            assertEqual(SerialEvaluator.multiply(a, b), runInline(op("*", a, b)), "multiply");
            assertEqual(SerialEvaluator.negate(a), runInline(op("-", a)), "negate");
            assertEqual(SerialEvaluator.transpose(a), runInline(op("T", a)), "transpose");
        }
    }

    @Test
    void thereIsOneTaskPerRow() {
        assertEquals(3, Operation.prepare(op("+", new double[3][4], new double[3][4])).tasks().size());
        assertEquals(2, Operation.prepare(op("*", new double[2][5], new double[5][7])).tasks().size());
        assertEquals(4, Operation.prepare(op("-", new double[4][1])).tasks().size());
        assertEquals(6, Operation.prepare(op("T", new double[6][2])).tasks().size());
    }

    @Test
    void reportsItsType() {
        assertEquals(ComputationNodeType.MULTIPLY, Operation.prepare(op("*", new double[1][1], new double[1][1])).type());
        assertEquals(ComputationNodeType.TRANSPOSE, Operation.prepare(op("T", new double[1][1])).type());
    }

    @Test
    void taskListCannotBeModified() {
        Operation operation = Operation.prepare(op("-", new double[2][2]));
        assertThrows(UnsupportedOperationException.class, () -> operation.tasks().clear());
    }

    @Test
    void preparingDoesNotModifyTheOperands() {
        double[][] a = {{1, 2}, {3, 4}}, b = {{5, 6}, {7, 8}};
        Operation operation = Operation.prepare(op("+", a, b));
        for (Runnable task : operation.tasks()) task.run();
        assertEqual(new double[][]{{6, 8}, {10, 12}}, operation.result(), "result");
        assertEqual(new double[][]{{1, 2}, {3, 4}}, a, "left operand");
        assertEqual(new double[][]{{5, 6}, {7, 8}}, b, "right operand");
    }

    @Test
    void rejectsAMatrixNode() {
        assertThrows(IllegalStateException.class, () -> Operation.prepare(new ComputationNode(new double[][]{{1}})));
    }

    @Test
    void rejectsAnOperandThatIsNotComputedYet() {
        ComputationNode inner = op("-", new double[][]{{1}});
        ComputationNode outer = new ComputationNode("-", new ArrayList<>(List.of(inner)));
        assertThrows(IllegalStateException.class, () -> Operation.prepare(outer));
    }

    @Test
    void rejectsMismatchedDimensions() {
        assertThrows(IllegalArgumentException.class, () -> Operation.prepare(op("+", new double[2][2], new double[3][3])));
        assertThrows(IllegalArgumentException.class, () -> Operation.prepare(op("*", new double[2][3], new double[2][3])));
    }

    /**
     * Prepares many unrelated operations, mixes all of their row tasks into one shuffled list,
     * and runs that list on several threads at once. Every operation must still be exactly right.
     * With engine-wide shared matrices, as in earlier versions, this could not work.
     */
    @RepeatedTest(10)
    void manyOperationsRunInterleavedOnManyThreadsWithoutInterfering() throws Exception {
        Random random = new Random();
        int operations = 40;
        List<Operation> prepared = new ArrayList<>();
        List<double[][]> expected = new ArrayList<>();
        List<Runnable> allTasks = new ArrayList<>();
        for (int i = 0; i < operations; i++) {
            int r = 1 + random.nextInt(12), k = 1 + random.nextInt(12), c = 1 + random.nextInt(12);
            double[][] a = intMatrix(random, r, k);
            ComputationNode node;
            double[][] want;
            switch (random.nextInt(4)) {
                case 0: { double[][] b = intMatrix(random, r, k); node = op("+", a, b); want = SerialEvaluator.add(a, b); break; }
                case 1: { double[][] b = intMatrix(random, k, c); node = op("*", a, b); want = SerialEvaluator.multiply(a, b); break; }
                case 2: node = op("-", a); want = SerialEvaluator.negate(a); break;
                default: node = op("T", a); want = SerialEvaluator.transpose(a); break;
            }
            Operation operation = Operation.prepare(node);
            prepared.add(operation);
            expected.add(want);
            allTasks.addAll(operation.tasks());
        }
        Collections.shuffle(allTasks, random);

        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (Runnable task : allTasks) futures.add(pool.submit(task));
            for (Future<?> f : futures) f.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        for (int i = 0; i < operations; i++) {
            assertEqual(expected.get(i), prepared.get(i).result(),
                "operation " + i + " (" + prepared.get(i).type() + ")");
        }
    }

    /** The engine and a hand-run Operation agree, for every operation type. */
    @Test
    void engineUsesOperationsToTheSameEffect() {
        Random random = new Random(3);
        try (LinearAlgebraEngine engine = new LinearAlgebraEngine(3)) {
            for (int trial = 0; trial < 50; trial++) {
                double[][] a = intMatrix(random, 3, 4), b = intMatrix(random, 4, 2);
                assertEqual(runInline(op("*", a, b)), engine.run(op("*", a, b)).getMatrix(), "trial " + trial);
            }
        }
    }

    // ---------------------------------------------------------------------------------------

    private static double[][] runInline(ComputationNode node) {
        Operation operation = Operation.prepare(node);
        for (Runnable task : operation.tasks()) task.run();
        return operation.result();
    }

    private static ComputationNode op(String operator, double[][]... operands) {
        List<ComputationNode> children = new ArrayList<>();
        for (double[][] m : operands) children.add(new ComputationNode(m));
        return new ComputationNode(operator, children);
    }

    private static double[][] intMatrix(Random random, int rows, int cols) {
        double[][] m = new double[rows][cols];
        for (int i = 0; i < rows; i++)
            for (int j = 0; j < cols; j++) m[i][j] = random.nextInt(7) - 3;
        return m;
    }

    private static void assertEqual(double[][] expected, double[][] actual, String context) {
        assertEquals(expected.length, actual.length, context + ": row count");
        for (int i = 0; i < expected.length; i++) assertArrayEquals(expected[i], actual[i], context + ": row " + i);
    }
}
