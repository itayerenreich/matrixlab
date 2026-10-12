package spl.lae;

import demo.MatrixRace;
import demo.ScenarioBench;
import parser.*;
import java.util.Arrays;

public class Main {
    public static void main(String[] args) {
        int status = execute(args);
        if (status != 0) System.exit(status);
    }
    static int execute(String[] args) {
        if (args.length == 0 || args[0].equals("--help")) {
            System.out.println("MatrixLab\n  java -jar matrixlab-1.0.0.jar solve <workers> <input.json> <output.json>\n  java -jar matrixlab-1.0.0.jar race [size=128] [maxWorkers=4] [repetitions=5] [outputDirectory=results] [seed=42]\n  java -jar matrixlab-1.0.0.jar scenarios [maxWorkers=8] [repetitions=15] [outputDirectory=results] [seed=42]\nLegacy form: <workers> <input.json> <output.json>");
            return args.length == 0 ? 2 : 0;
        }
        if (args[0].equals("race")) {
            try { MatrixRace.main(Arrays.copyOfRange(args, 1, args.length)); return 0; }
            catch (Exception e) { System.err.println("Matrix Race failed: " + e.getMessage()); return 1; }
        }
        if (args[0].equals("scenarios")) {
            try { ScenarioBench.main(Arrays.copyOfRange(args, 1, args.length)); return 0; }
            catch (Exception e) { System.err.println("Scenario benchmark failed: " + e.getMessage()); return 1; }
        }
        String[] actual = args[0].equals("solve") ? Arrays.copyOfRange(args, 1, args.length) : args;
        if (actual.length != 3) { System.err.println("Expected: solve <workers> <input.json> <output.json>"); return 2; }
        try {
            int threads = Integer.parseInt(actual[0]);
            ComputationNode input = new InputParser().parse(actual[1]);
            try (LinearAlgebraEngine engine = new LinearAlgebraEngine(threads)) {
                long start = System.nanoTime();
                ComputationNode result = engine.run(input);
                double ms = (System.nanoTime() - start) / 1_000_000.0;
                OutputWriter.write(result.getMatrix(), actual[2]);
                System.out.printf(java.util.Locale.ROOT, "Solved in %.3f ms%n", ms);
                System.out.print(engine.getWorkerReport());
            }
            return 0;
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            System.err.println("Solve failed: " + message);
            try { OutputWriter.write(message, actual[2]); } catch (Exception ignored) {}
            return 1;
        }
    }
}
