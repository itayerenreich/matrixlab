package demo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import parser.ComputationNode;
import spl.lae.LinearAlgebraEngine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
class ScenarioBenchTest {
    @TempDir Path out;

    @Test
    void everyScenarioMatchesTheSerialEvaluator() {
        try (LinearAlgebraEngine engine = new LinearAlgebraEngine(3)) {
            for (Scenarios.Scenario s : Scenarios.all(7L)) {
                double[][] expected = SerialEvaluator.evaluate(s.tree().get());
                double[][] actual = engine.run(s.tree().get()).getMatrix();
                assertDoesNotThrow(() -> ScenarioBench.verify(s.name(), expected, actual), s.name());
            }
        }
    }

    @Test
    void wideAndChainDoTheSameMultiplications() {
        // The comparison is only fair if both trees contain the same number of products.
        assertEquals(Scenarios.WIDE_TERMS, countMultiplies(scenario("wide").tree().get()));
        assertEquals(Scenarios.WIDE_TERMS, countMultiplies(scenario("chain").tree().get()));
    }

    @Test
    void serialEvaluatorDoesNotMutateTheTree() {
        ComputationNode tree = scenario("wide").tree().get();
        SerialEvaluator.evaluate(tree);
        assertNotEquals(parser.ComputationNodeType.MATRIX, tree.getNodeType());
    }

    @Test
    void writesCsvWithOneRowPerScenarioAndWorkerCount() throws Exception {
        ScenarioBench.main(new String[]{"2", "1", out.toString(), "42"});
        List<String> lines = Files.readAllLines(out.resolve("scenarios.csv"));
        // header + 3 scenarios × (serial, 1 worker, 2 workers)
        assertEquals(1 + 3 * 3, lines.size());
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("wide,2,")));
    }

    @Test
    void verificationCatchesWrongResults() {
        assertThrows(IllegalStateException.class,
            () -> ScenarioBench.verify("x", new double[][]{{1}}, new double[][]{{2}}));
    }

    private static Scenarios.Scenario scenario(String name) {
        return Scenarios.all(1L).stream().filter(s -> s.name().equals(name)).findFirst().orElseThrow();
    }

    private static int countMultiplies(ComputationNode node) {
        if (node.getNodeType() == parser.ComputationNodeType.MATRIX) return 0;
        int own = node.getNodeType() == parser.ComputationNodeType.MULTIPLY ? node.getChildren().size() - 1 : 0;
        int sum = own;
        for (ComputationNode child : node.getChildren()) sum += countMultiplies(child);
        return sum;
    }
}
