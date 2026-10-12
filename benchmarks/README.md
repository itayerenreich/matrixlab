# Benchmark history

Each file is the output of the scenario benchmark at a specific point in the project's history,
so improvements can be compared on the same machine with the same inputs.

```bash
java -jar target/matrixlab-1.0.0.jar scenarios
```

Defaults: `maxWorkers=8`, `repetitions=15`, `warmups=3`, `seed=42`.
Machine: Windows, 12 logical processors, Java 23.0.1.

| File | State of the engine | Key observation |
|---|---|---|
| `baseline-before-memory-fix.csv` | Original memory layer: every element read takes and releases a lock | `wide` and `chain` run 15-18x slower than serial and do not speed up with more workers; their times are almost identical, so sequential overhead, not missing parallelism, is the bottleneck |

## How to read the columns

- `median_ms`: median of the measured runs, after warmups.
- `scaling_vs_1_worker`: the scenario's 1-worker time divided by its N-worker time. Perfect scaling would equal N.
- `speedup_vs_serial`: the serial reference time divided by the engine time. Below 1 means the engine is slower than a plain loop.
