package spl.lae;

import parser.*;
import memory.*;
import scheduling.*;

import java.util.List;

public class LinearAlgebraEngine implements AutoCloseable {

    private SharedMatrix leftMatrix = new SharedMatrix();
    private SharedMatrix rightMatrix = new SharedMatrix();
    private TiredExecutor executor;

    public LinearAlgebraEngine(int numThreads) {
        if (numThreads <= 0) {
            throw new IllegalArgumentException("numThreads must be positive");
        }
        this.executor = new TiredExecutor(numThreads);
    }

    public synchronized ComputationNode run(ComputationNode computationRoot) {
        if (computationRoot == null) {
            throw new IllegalArgumentException("computationRoot cannot be null");
        }

// Normalize all n-ary operations before resolving the expression tree.
        computationRoot.associativeNesting();

// keep resolving until the root becomes a matrix node
        while (computationRoot.getNodeType() != ComputationNodeType.MATRIX) {
            ComputationNode nextNode = computationRoot.findResolvable();
            if (nextNode == null) {
                throw new IllegalStateException("No resolvable node found");
            }

            loadAndCompute(nextNode);

        }

        validateMatrix(computationRoot.getMatrix());
        return computationRoot;
    }

    public void loadAndCompute(ComputationNode node) {
        if (node.getNodeType() == ComputationNodeType.MATRIX) {
            return;
        }
        for (ComputationNode child : node.getChildren()) {
            if (child.getNodeType() != ComputationNodeType.MATRIX) {
                throw new IllegalStateException("Cannot compute node: not all children are resolved.");
            }
        }

        validateOperation(node);
        leftMatrix.loadRowMajor(node.getChildren().get(0).getMatrix());

        // Only addition needs the right operand as shared vectors (each row is added in place
        // into the left row). Multiplication only reads it, so it uses the operand array directly.
        if (node.getNodeType() == ComputationNodeType.ADD) {
            rightMatrix.loadRowMajor(node.getChildren().get(1).getMatrix());
        }

        List<Runnable> tasks;

        switch (node.getNodeType()) {
            case ADD:
                tasks = createAddTasks();
                break;
            case MULTIPLY:
                tasks = createMultiplyTasks(node.getChildren().get(1).getMatrix());
                break;
            case NEGATE:
                tasks = createNegateTasks();
                break;
            case TRANSPOSE:
                tasks = createTransposeTasks();
                break;
            default:
                throw new UnsupportedOperationException("Unsupported operation: " + node.getNodeType());
        }

        if (tasks != null && !tasks.isEmpty()) {
            executor.submitAll(tasks);
            node.resolve(leftMatrix.readRowMajor());
        }

    }

    private static void validateOperation(ComputationNode node) {
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
    private static void validateMatrix(double[][] matrix) {
        if (matrix == null || matrix.length == 0 || matrix[0] == null || matrix[0].length == 0)
            throw new IllegalArgumentException("Matrix must be non-empty");
        int width = matrix[0].length;
        for (double[] row : matrix) {
            if (row == null || row.length != width) throw new IllegalArgumentException("Matrix must be rectangular");
            for (double value : row) if (!Double.isFinite(value)) throw new IllegalArgumentException("Matrix values must be finite");
        }
    }
    @Override public void close() { executor.close(); }

    public List<Runnable> createAddTasks() {
        List<Runnable> tasks = new java.util.ArrayList<>();
        int rows = rightMatrix.length();

        for (int i = 0; i < rows; i++) {
            final int r = i;

            tasks.add(() -> {
                try {
                    leftMatrix.get(r).add(rightMatrix.get(r));
                } catch (Exception ex) {
                    throw new IllegalArgumentException(ex);
                }
            });
        }

        return tasks;
    }

    /**
     * One task per row of the left matrix: row · right.
     *
     * <p>{@code rightRows} is shared by every task and is only ever read, never written, so no
     * lock or private copy is needed. It was already validated as a rectangular matrix. Loading
     * it into a {@link SharedMatrix} and reading it straight back out, as earlier versions did,
     * copied the whole matrix twice per multiplication for no benefit.
     */
    public List<Runnable> createMultiplyTasks(double[][] rightRows) {
        List<Runnable> tasks = new java.util.ArrayList<>();
        int rows = leftMatrix.length();
        final double[][] rightSnapshot = rightRows;

        for (int i = 0; i < rows; i++) {
            final int r = i;

            tasks.add(() -> {
                try {
                    leftMatrix.get(r).multiplyByRows(rightSnapshot);
                } catch (Exception ex) {
                    throw new IllegalArgumentException(ex);
                }
            });
        }

        return tasks;
    }

    public List<Runnable> createNegateTasks() {
        List<Runnable> tasks = new java.util.ArrayList<>();
        int rows = leftMatrix.length();

        for (int i = 0; i < rows; i++) {
            final int r = i;

            tasks.add(() -> {
                try {
                    leftMatrix.get(r).negate();
                } catch (Exception ex) {
                    throw new IllegalArgumentException(ex);
                }
            });
        }

        return tasks;
    }

    public List<Runnable> createTransposeTasks() {
        List<Runnable> tasks = new java.util.LinkedList<>();
        for (int i = 0; i < leftMatrix.length(); i++) {
            SharedVector vLeft = leftMatrix.get(i);
            tasks.add(() -> vLeft.transpose());
        }
        return tasks;
    }

    public String getWorkerReport() {
        return executor.getWorkerReport();
    }
    public void shutdown() throws InterruptedException {
        if (executor != null) {
            executor.shutdown();
        }
    }


}
