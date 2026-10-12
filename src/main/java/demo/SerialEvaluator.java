package demo;

import parser.ComputationNode;
import parser.ComputationNodeType;

import java.util.List;

/**
 * Single-threaded reference evaluator for expression trees.
 *
 * <p>It never mutates the tree, so it can be used to compute the expected result of a tree
 * before the engine resolves that same tree in place. It is deliberately simple: correctness
 * here is what every benchmark and test compares the concurrent engine against.
 */
public final class SerialEvaluator {
    private SerialEvaluator() {}

    public static double[][] evaluate(ComputationNode node) {
        if (node.getNodeType() == ComputationNodeType.MATRIX) {
            return node.getMatrix();
        }
        List<ComputationNode> children = node.getChildren();
        switch (node.getNodeType()) {
            case NEGATE:
                return negate(evaluate(children.get(0)));
            case TRANSPOSE:
                return transpose(evaluate(children.get(0)));
            case ADD: {
                double[][] acc = evaluate(children.get(0));
                for (int i = 1; i < children.size(); i++) acc = add(acc, evaluate(children.get(i)));
                return acc;
            }
            case MULTIPLY: {
                double[][] acc = evaluate(children.get(0));
                for (int i = 1; i < children.size(); i++) acc = multiply(acc, evaluate(children.get(i)));
                return acc;
            }
            default:
                throw new IllegalArgumentException("Unsupported node type: " + node.getNodeType());
        }
    }

    public static double[][] add(double[][] a, double[][] b) {
        if (a.length != b.length || a[0].length != b[0].length)
            throw new IllegalArgumentException("Addition requires equal matrix dimensions");
        double[][] c = new double[a.length][a[0].length];
        for (int i = 0; i < a.length; i++)
            for (int j = 0; j < a[0].length; j++) c[i][j] = a[i][j] + b[i][j];
        return c;
    }

    public static double[][] multiply(double[][] a, double[][] b) {
        if (a[0].length != b.length)
            throw new IllegalArgumentException("Multiplication requires matching inner dimensions");
        double[][] c = new double[a.length][b[0].length];
        for (int i = 0; i < a.length; i++)
            for (int j = 0; j < b[0].length; j++) {
                double sum = 0;
                for (int k = 0; k < b.length; k++) sum += a[i][k] * b[k][j];
                c[i][j] = sum;
            }
        return c;
    }

    public static double[][] negate(double[][] a) {
        double[][] c = new double[a.length][a[0].length];
        for (int i = 0; i < a.length; i++)
            for (int j = 0; j < a[0].length; j++) c[i][j] = -a[i][j];
        return c;
    }

    public static double[][] transpose(double[][] a) {
        double[][] c = new double[a[0].length][a.length];
        for (int i = 0; i < a.length; i++)
            for (int j = 0; j < a[0].length; j++) c[j][i] = a[i][j];
        return c;
    }
}
