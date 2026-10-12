package demo;

import spl.lae.LinearAlgebraEngine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Measures how the engine scales with worker count on differently shaped expression trees.
 *
 * <p>The key number is {@code scaling}: the scenario's 1-worker median divided by its N-worker
 * median. Perfect scaling would be N. Comparing scaling across scenarios shows where the
 * engine leaves workers idle — that is the baseline the dataflow scheduler has to beat.
 *
 * <p>Method: every mode is warmed up, measurement order is shuffled each round with a seeded
 * random, the median of the measured rounds is reported, and every result is verified
 * against {@link SerialEvaluator}. Like Matrix Race, this is an application-level experiment,
 * not a JMH microbenchmark.
 */
public final class ScenarioBench {
    private ScenarioBench() {}

    public record Row(String scenario, int workers, double medianMs, double scaling, double speedupVsSerial) {}

    public static final int WARMUPS = 3;

    public static List<Row> run(int maxWorkers, int repetitions, long seed) {
        if (maxWorkers < 1 || maxWorkers > 32 || repetitions < 1 || repetitions > 200)
            throw new IllegalArgumentException("maxWorkers: 1..32; repetitions: 1..200");

        List<Integer> counts = new ArrayList<>();
        for (int n = 1; n <= maxWorkers; n *= 2) counts.add(n);
        if (!counts.contains(maxWorkers)) counts.add(maxWorkers);

        List<Row> rows = new ArrayList<>();
        List<LinearAlgebraEngine> engines = new ArrayList<>();
        try {
            for (int count : counts) engines.add(new LinearAlgebraEngine(count));

            for (Scenarios.Scenario scenario : Scenarios.all(seed)) {
                double[][] expected = SerialEvaluator.evaluate(scenario.tree().get());

                // Mode 0 is the serial reference; mode i (i ≥ 1) is engines.get(i - 1).
                List<List<Double>> times = new ArrayList<>();
                for (int i = 0; i <= counts.size(); i++) times.add(new ArrayList<>());
                Random orderRandom = new Random(seed ^ scenario.name().hashCode());

                for (int round = -WARMUPS; round < repetitions; round++) {
                    List<Integer> order = new ArrayList<>();
                    for (int i = 0; i <= counts.size(); i++) order.add(i);
                    Collections.shuffle(order, orderRandom);

                    for (int mode : order) {
                        var tree = scenario.tree().get();
                        long start = System.nanoTime();
                        double[][] result = mode == 0
                            ? SerialEvaluator.evaluate(tree)
                            : engines.get(mode - 1).run(tree).getMatrix();
                        double elapsedMs = (System.nanoTime() - start) / 1_000_000.0;
                        verify(scenario.name(), expected, result);
                        if (round >= 0) times.get(mode).add(elapsedMs);
                    }
                }

                double serialMs = median(times.get(0));
                double oneWorkerMs = median(times.get(1));
                rows.add(new Row(scenario.name(), 0, serialMs, Double.NaN, 1.0));
                for (int i = 0; i < counts.size(); i++) {
                    double ms = median(times.get(i + 1));
                    rows.add(new Row(scenario.name(), counts.get(i), ms, oneWorkerMs / ms, serialMs / ms));
                }
            }
        } finally {
            for (LinearAlgebraEngine engine : engines) engine.close();
        }
        return rows;
    }

    static void verify(String scenario, double[][] expected, double[][] actual) {
        if (expected.length != actual.length || expected[0].length != actual[0].length)
            throw new IllegalStateException(scenario + ": result has the wrong shape");
        for (int i = 0; i < expected.length; i++)
            for (int j = 0; j < expected[0].length; j++) {
                double delta = Math.abs(expected[i][j] - actual[i][j]);
                if (!Double.isFinite(actual[i][j]) || delta > 1e-9 * Math.max(1, Math.abs(expected[i][j])))
                    throw new IllegalStateException(scenario + ": verification failed at " + i + "," + j);
            }
    }

    static double median(List<Double> values) {
        List<Double> copy = new ArrayList<>(values);
        Collections.sort(copy);
        int mid = copy.size() / 2;
        return copy.size() % 2 == 0 ? (copy.get(mid - 1) + copy.get(mid)) / 2 : copy.get(mid);
    }

    public static String toCsv(List<Row> rows) {
        StringBuilder csv = new StringBuilder("scenario,workers,median_ms,scaling_vs_1_worker,speedup_vs_serial\n");
        for (Row r : rows) {
            csv.append(String.format(Locale.ROOT, "%s,%s,%.4f,%s,%.3f%n",
                r.scenario(), r.workers() == 0 ? "serial" : String.valueOf(r.workers()), r.medianMs(),
                Double.isNaN(r.scaling()) ? "" : String.format(Locale.ROOT, "%.3f", r.scaling()),
                r.speedupVsSerial()));
        }
        return csv.toString();
    }

    public static void main(String[] args) throws IOException {
        if (args.length > 4) throw new IllegalArgumentException("scenarios [maxWorkers] [repetitions] [outputDirectory] [seed]");
        int maxWorkers = args.length > 0 ? Integer.parseInt(args[0]) : Math.min(8, Runtime.getRuntime().availableProcessors());
        int repetitions = args.length > 1 ? Integer.parseInt(args[1]) : 15;
        Path out = Path.of(args.length > 2 ? args[2] : "results");
        long seed = args.length > 3 ? Long.parseLong(args[3]) : 42L;

        System.out.printf("Scenario benchmark | maxWorkers=%d | repetitions=%d | warmups=%d | seed=%d%n",
            maxWorkers, repetitions, WARMUPS, seed);
        System.out.printf("Java %s | %d logical processors%n%n",
            System.getProperty("java.version"), Runtime.getRuntime().availableProcessors());
        for (Scenarios.Scenario s : Scenarios.all(seed)) System.out.printf("  %-6s %s%n", s.name(), s.description());
        System.out.println();

        List<Row> rows = run(maxWorkers, repetitions, seed);

        System.out.println("scenario  workers   median ms   scaling   vs serial");
        String last = "";
        for (Row r : rows) {
            if (!r.scenario().equals(last) && !last.isEmpty()) System.out.println();
            last = r.scenario();
            System.out.printf(Locale.ROOT, "%-8s  %7s  %10.3f   %7s   %8.2fx%n",
                r.scenario(), r.workers() == 0 ? "serial" : String.valueOf(r.workers()), r.medianMs(),
                Double.isNaN(r.scaling()) ? "-" : String.format(Locale.ROOT, "%.2fx", r.scaling()),
                r.speedupVsSerial());
        }

        Files.createDirectories(out);
        Path csv = out.resolve("scenarios.csv");
        Files.writeString(csv, toCsv(rows));
        System.out.println("\nAll results verified against the serial evaluator.");
        System.out.println("CSV: " + csv.toAbsolutePath());
    }
}
