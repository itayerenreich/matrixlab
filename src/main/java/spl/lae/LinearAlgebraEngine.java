package spl.lae;

import parser.ComputationNode;
import parser.ComputationNodeType;
import scheduling.TiredExecutor;

/**
 * Evaluates matrix expression trees on a pool of worker threads.
 *
 * <p>The engine itself holds no matrix data: its only state is the worker pool. Each operation
 * in the tree is turned into an {@link Operation} that owns its own matrices, its rows are run
 * in parallel on the pool, and its result replaces the operation in the tree.
 *
 * <p>Operations are currently resolved one at a time, deepest first. Because no operation shares
 * state with another, the scheduling order is free to change without touching how an individual
 * operation is computed.
 */
public class LinearAlgebraEngine implements AutoCloseable {

    private final TiredExecutor executor;

    public LinearAlgebraEngine(int numThreads) {
        if (numThreads <= 0) {
            throw new IllegalArgumentException("numThreads must be positive");
        }
        this.executor = new TiredExecutor(numThreads);
    }

    /**
     * Evaluates {@code computationRoot} in place and returns it, now a matrix node.
     * One run at a time per engine; the tree must not be shared or modified during the run.
     */
    public synchronized ComputationNode run(ComputationNode computationRoot) {
        if (computationRoot == null) {
            throw new IllegalArgumentException("computationRoot cannot be null");
        }

        // Normalize all n-ary operations into binary ones before resolving the tree.
        computationRoot.associativeNesting();

        // Keep resolving operations whose operands are all matrices until the root is a matrix.
        while (computationRoot.getNodeType() != ComputationNodeType.MATRIX) {
            ComputationNode nextNode = computationRoot.findResolvable();
            if (nextNode == null) {
                throw new IllegalStateException("No resolvable node found");
            }
            loadAndCompute(nextNode);
        }

        Operation.validateMatrix(computationRoot.getMatrix());
        return computationRoot;
    }

    /**
     * Computes one operation whose operands are all matrices, and replaces it in the tree with
     * its result. A node that is already a matrix is left unchanged.
     */
    public void loadAndCompute(ComputationNode node) {
        if (node.getNodeType() == ComputationNodeType.MATRIX) {
            return;
        }
        Operation operation = Operation.prepare(node);
        executor.submitAll(operation.tasks());
        node.resolve(operation.result());
    }

    public String getWorkerReport() {
        return executor.getWorkerReport();
    }

    public void shutdown() throws InterruptedException {
        executor.shutdown();
    }

    @Override
    public void close() {
        executor.close();
    }
}
