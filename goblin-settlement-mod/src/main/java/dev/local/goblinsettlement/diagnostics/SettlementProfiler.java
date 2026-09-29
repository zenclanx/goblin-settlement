package dev.local.goblinsettlement.diagnostics;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Session-only timing accumulator for the world-tick coordinators. Deliberately free of Minecraft
 * types so a plain unit check can drive it; the state lives only in memory and is never persisted.
 * Nothing is recorded while profiling is off, so the samples map stays empty until {@link #on()}.
 */
public final class SettlementProfiler {
    private static boolean enabled;
    private static final Map<String, Sample> samples = new LinkedHashMap<>();

    private SettlementProfiler() {
    }

    /** Times {@code task} under {@code name} when enabled; runs it untouched otherwise. */
    public static void run(String name, Runnable task) {
        if (!enabled) {
            task.run();
            return;
        }
        long start = System.nanoTime();
        task.run();
        record(name, System.nanoTime() - start);
    }

    /**
     * Accumulation core, a no-op while profiling is off. Deterministic given the durations, so the
     * check injects them directly.
     */
    public static void record(String name, long nanos) {
        if (!enabled) {
            return;
        }
        samples.computeIfAbsent(name, key -> new Sample()).add(nanos);
    }

    /** Enables profiling and clears every sample, so the next report covers a clean window. */
    public static void on() {
        samples.clear();
        enabled = true;
    }

    public static void off() {
        enabled = false;
    }

    public static boolean enabled() {
        return enabled;
    }

    /**
     * One line per recorded name, ordered by total time descending and then by name ascending, so the
     * output is stable between runs. All durations are printed in milliseconds.
     */
    public static String report() {
        if (samples.isEmpty()) {
            return "Profiler: no samples recorded";
        }
        List<Map.Entry<String, Sample>> ordered = new ArrayList<>(samples.entrySet());
        ordered.sort((left, right) -> {
            int byTotal = Long.compare(right.getValue().totalNanos, left.getValue().totalNanos);
            return byTotal != 0 ? byTotal : left.getKey().compareTo(right.getKey());
        });
        StringBuilder builder = new StringBuilder("Profiler: name n=count total avg max (ms)");
        for (Map.Entry<String, Sample> entry : ordered) {
            Sample sample = entry.getValue();
            builder.append('\n')
                    .append(entry.getKey())
                    .append(": n=").append(sample.count)
                    .append(" total=").append(millis(sample.totalNanos))
                    .append(" avg=").append(millis(sample.totalNanos / sample.count))
                    .append(" max=").append(millis(sample.maxNanos));
        }
        return builder.toString();
    }

    private static String millis(long nanos) {
        return String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0);
    }

    private static final class Sample {
        private long count;
        private long totalNanos;
        private long maxNanos;

        private void add(long nanos) {
            count++;
            totalNanos += nanos;
            if (nanos > maxNanos) {
                maxNanos = nanos;
            }
        }
    }
}
