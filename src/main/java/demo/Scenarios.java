package demo;

import parser.ComputationNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Supplier;

/**
 * Benchmark workloads that differ in the <em>shape</em> of the expression tree.
 *
 * <p>The engine parallelizes rows within one operation. How much that helps depends on the
 * tree: a single large operation already keeps every worker busy, while many small independent
 * operations leave workers idle between them. These scenarios isolate that difference:
 *
 * <ul>
 *   <li><b>single</b> – one {@code n×n · n×n} product. Plenty of rows, no independent branches.</li>
 *   <li><b>wide</b> – a sum of {@code k} independent products, each with only {@code rows} rows.
 *       Few rows per operation, many operations that <em>could</em> run at the same time.</li>
 *   <li><b>chain</b> – {@code k} products where each one consumes the previous result.
 *       Same number of multiplications and the same arithmetic as <b>wide</b>, but every
 *       operation depends on the one before it, so nothing can overlap. This is the control.</li>
 * </ul>
 *
 * Every tree is built fresh on each call because the engine resolves nodes in place.
 */
public final class Scenarios {
    private Scenarios() {}

    public record Scenario(String name, String description, Supplier<ComputationNode> tree) {}

    public static final int WIDE_TERMS = 8;
    public static final int THIN_ROWS = 2;
    public static final int INNER = 128;
    public static final int SINGLE_SIZE = 128;

    public static List<Scenario> all(long seed) {
        Random random = new Random(seed);
        return List.of(single(random), wide(random), chain(random));
    }

    static Scenario single(Random random) {
        double[][] a = randomMatrix(random, SINGLE_SIZE, SINGLE_SIZE, 1);
        double[][] b = randomMatrix(random, SINGLE_SIZE, SINGLE_SIZE, 1);
        return new Scenario("single",
            "A*B, " + SINGLE_SIZE + "x" + SINGLE_SIZE + " - one large operation",
            () -> op("*", leaf(a), leaf(b)));
    }

    static Scenario wide(Random random) {
        List<double[][]> lefts = new ArrayList<>();
        List<double[][]> rights = new ArrayList<>();
        for (int i = 0; i < WIDE_TERMS; i++) {
            lefts.add(randomMatrix(random, THIN_ROWS, INNER, 1));
            rights.add(randomMatrix(random, INNER, INNER, 1 / Math.sqrt(INNER)));
        }
        return new Scenario("wide",
            "sum of A_i*B_i, " + WIDE_TERMS + " independent products of " + THIN_ROWS + "x" + INNER + " * " + INNER + "x" + INNER,
            () -> {
                List<ComputationNode> terms = new ArrayList<>();
                for (int i = 0; i < WIDE_TERMS; i++) terms.add(op("*", leaf(lefts.get(i)), leaf(rights.get(i))));
                return new ComputationNode("+", terms);
            });
    }

    static Scenario chain(Random random) {
        double[][] start = randomMatrix(random, THIN_ROWS, INNER, 1);
        List<double[][]> factors = new ArrayList<>();
        // Scaling by 1/sqrt(n) keeps values from growing with every multiplication.
        for (int i = 0; i < WIDE_TERMS; i++) factors.add(randomMatrix(random, INNER, INNER, 1 / Math.sqrt(INNER)));
        return new Scenario("chain",
            "A*B_1*...*B_" + WIDE_TERMS + ", " + WIDE_TERMS + " dependent products of " + THIN_ROWS + "x" + INNER + " * " + INNER + "x" + INNER,
            () -> {
                List<ComputationNode> operands = new ArrayList<>();
                operands.add(leaf(start));
                for (double[][] f : factors) operands.add(leaf(f));
                // n-ary multiplication is normalized left-associatively: ((A·B₁)·B₂)·…
                return new ComputationNode("*", operands);
            });
    }

    private static ComputationNode leaf(double[][] m) {
        return new ComputationNode(m);
    }

    private static ComputationNode op(String operator, ComputationNode... operands) {
        return new ComputationNode(operator, new ArrayList<>(List.of(operands)));
    }

    static double[][] randomMatrix(Random random, int rows, int cols, double scale) {
        double[][] m = new double[rows][cols];
        for (int i = 0; i < rows; i++)
            for (int j = 0; j < cols; j++) m[i][j] = (random.nextDouble() * 2 - 1) * scale;
        return m;
    }
}
