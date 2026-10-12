package spl.lae;

import memory.SharedMatrix;
import memory.SharedVector;
import parser.ComputationNode;
import parser.ComputationNodeType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One operation of an expression tree, prepared for execution: {@code A + B}, {@code A * B},
 * {@code -A} or {@code T(A)}, where every operand is already a concrete matrix.
 *
 * <p>An Operation owns all of its working state. Its matrices are created for it alone, so two
 * Operations never touch each other's data. That is what allows several operations to be in
 * flight at the same time; earlier versions of the engine kept this state in two engine-wide
 * fields, which forced operations to run strictly one after another.
 *
 * <p>Lifecycle: {@link #prepare} validates the operands and copies them into this operation's
 * own matrices; the caller runs every task from {@link #tasks()} (in any order, on any threads)
 * and waits for all of them; then {@link #result()} returns the finished matrix. Only the row
 * tasks may run concurrently with each other — each one touches a different row.
 */
public final class Operation {

    private final ComputationNodeType type;
    /** Holds the left operand, and receives the result in place, row by row. */
    private final SharedMatrix target = new SharedMatrix();
    /** Right operand for addition; empty for every other operation. */
    private final SharedMatrix addend = new SharedMatrix();
    private final List<Runnable> tasks;

    private Operation(ComputationNode node) {
        this.type = node.getNodeType();
        List<ComputationNode> operands = node.getChildren();
        target.loadRowMajor(operands.get(0).getMatrix());
        switch (type) {
            case ADD:
                // Addition writes each right row into the matching left row, so the right
                // operand needs SharedVectors with locks.
                addend.loadRowMajor(operands.get(1).getMatrix());
                tasks = addTasks();
                break;
            case MULTIPLY:
                // Multiplication only reads the right operand, so it uses the array directly:
                // one read-only snapshot shared by every row task, no copy, no locks.
                tasks = multiplyTasks(operands.get(1).getMatrix());
                break;
            case NEGATE:
                tasks = negateTasks();
                break;
            case TRANSPOSE:
                tasks = transposeTasks();
                break;
            default:
                throw new UnsupportedOperationException("Unsupported operation: " + type);
        }
    }

    /**
     * Validates {@code node} and prepares it for execution.
     *
     * @throws IllegalStateException    if {@code node} is a matrix, or if an operand has not been
     *                                  computed yet
     * @throws IllegalArgumentException if the operand count or dimensions are invalid
     */
    public static Operation prepare(ComputationNode node) {
        if (node.getNodeType() == ComputationNodeType.MATRIX) {
            throw new IllegalStateException("A matrix node is not an operation");
        }
        for (ComputationNode child : node.getChildren()) {
            if (child.getNodeType() != ComputationNodeType.MATRIX) {
                throw new IllegalStateException("Cannot compute node: not all children are resolved.");
            }
        }
        validate(node);
        return new Operation(node);
    }

    public ComputationNodeType type() {
        return type;
    }

    /** One task per row. Each task touches only its own row, so they may run in parallel. */
    public List<Runnable> tasks() {
        return tasks;
    }

    /** The finished matrix. Call only after every task has completed. */
    public double[][] result() {
        return target.readRowMajor();
    }

    // ---------------------------------------------------------------------------------------
    // Row tasks
    // ---------------------------------------------------------------------------------------

    private List<Runnable> addTasks() {
        List<Runnable> list = new ArrayList<>();
        for (int i = 0; i < addend.length(); i++) {
            final int r = i;
            list.add(() -> {
                try {
                    target.get(r).add(addend.get(r));
                } catch (Exception ex) {
                    throw new IllegalArgumentException(ex);
                }
            });
        }
        return Collections.unmodifiableList(list);
    }

    private List<Runnable> multiplyTasks(double[][] rightRows) {
        List<Runnable> list = new ArrayList<>();
        for (int i = 0; i < target.length(); i++) {
            final int r = i;
            list.add(() -> {
                try {
                    target.get(r).multiplyByRows(rightRows);
                } catch (Exception ex) {
                    throw new IllegalArgumentException(ex);
                }
            });
        }
        return Collections.unmodifiableList(list);
    }

    private List<Runnable> negateTasks() {
        List<Runnable> list = new ArrayList<>();
        for (int i = 0; i < target.length(); i++) {
            final int r = i;
            list.add(() -> {
                try {
                    target.get(r).negate();
                } catch (Exception ex) {
                    throw new IllegalArgumentException(ex);
                }
            });
        }
        return Collections.unmodifiableList(list);
    }

    private List<Runnable> transposeTasks() {
        List<Runnable> list = new ArrayList<>();
        for (int i = 0; i < target.length(); i++) {
            SharedVector row = target.get(i);
            list.add(row::transpose);
        }
        return Collections.unmodifiableList(list);
    }

    // ---------------------------------------------------------------------------------------
    // Validation
    // ---------------------------------------------------------------------------------------

    private static void validate(ComputationNode node) {
        boolean binary = node.getNodeType() == ComputationNodeType.ADD || node.getNodeType() == ComputationNodeType.MULTIPLY;
        if (node.getChildren() == null || node.getChildren().size() != (binary ? 2 : 1))
            throw new IllegalArgumentException("Invalid operand count");
        for (ComputationNode child : node.getChildren()) validateMatrix(child.getMatrix());
        double[][] a = node.getChildren().get(0).getMatrix();
        if (!binary) return;
        double[][] b = node.getChildren().get(1).getMatrix();
        if (node.getNodeType() == ComputationNodeType.ADD && (a.length != b.length || a[0].length != b[0].length))
            throw new IllegalArgumentException("Addition requires equal matrix dimensions");
        if (node.getNodeType() == ComputationNodeType.MULTIPLY && a[0].length != b.length)
            throw new IllegalArgumentException("Multiplication requires matching inner dimensions");
    }

    /** Non-empty, rectangular, and every value finite. */
    static void validateMatrix(double[][] matrix) {
        if (matrix == null || matrix.length == 0 || matrix[0] == null || matrix[0].length == 0)
            throw new IllegalArgumentException("Matrix must be non-empty");
        int width = matrix[0].length;
        for (double[] row : matrix) {
            if (row == null || row.length != width) throw new IllegalArgumentException("Matrix must be rectangular");
            for (double value : row) if (!Double.isFinite(value)) throw new IllegalArgumentException("Matrix values must be finite");
        }
    }
}
