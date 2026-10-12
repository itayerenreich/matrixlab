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
| `after-row-snapshot.csv` | `readRowMajor` copies each row under one lock (`SharedVector.snapshot()`) | `wide` 2.7-4x faster and `chain` 5-7x faster; adding workers now helps `wide` (1.43x at 4-8 workers instead of none). `wide` and `chain` now differ, which shows a fixed per-operation cost is the next bottleneck |

## Before vs after the row-snapshot fix

Median ms, same machine, same seed.

| Scenario | Workers | Before | After | Speedup |
|---|---|---|---|---|
| single | 1 | 6.88 | 6.59 | 1.04x |
| single | 8 | 2.88 | 2.26 | 1.27x |
| wide | 1 | 5.32 | 1.99 | 2.67x |
| wide | 4 | 5.63 | 1.36 | 4.14x |
| wide | 8 | 5.61 | 1.39 | 4.04x |
| chain | 1 | 5.42 | 1.04 | 5.21x |
| chain | 4 | 5.37 | 0.79 | 6.82x |
| chain | 8 | 5.35 | 0.83 | 6.41x |

`single` barely changes because its time is mostly the multiplication itself; the lock overhead mattered most when the work per operation was small. The engine is still slower than the plain serial loop in every scenario, so the remaining overhead is the next target.

## How to read the columns

- `median_ms`: median of the measured runs, after warmups.
- `scaling_vs_1_worker`: the scenario's 1-worker time divided by its N-worker time. Perfect scaling would equal N.
- `speedup_vs_serial`: the serial reference time divided by the engine time. Below 1 means the engine is slower than a plain loop.
