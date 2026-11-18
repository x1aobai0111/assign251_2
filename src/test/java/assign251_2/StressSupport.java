package assign251_2;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Shared measurement and CSV-writing helpers for the stress tests.
 */
final class StressSupport {

    static final Path RESULTS_DIRECTORY = Path.of("target", "stress-results");
    static final Path LOG_DIRECTORY = RESULTS_DIRECTORY.resolve("logs");

    private StressSupport() {
    }

    record MemorySample(long elapsedMillis, long usedBytes) {
    }

    record RunResult(long elapsedMillis, long retainedBytes, List<MemorySample> samples) {
    }

    record Measurement(String appender, int maxSize, long appendMillis, long formatMillis,
                       long retainedBytes, long discarded, List<MemorySample> samples) {
        long totalMillis() {
            return appendMillis + formatMillis;
        }
    }

    /**
     * Runs an action, measuring elapsed time, retained heap and a heap-usage
     * time series.
     */
    static RunResult timed(Runnable action) {
        forceGc();
        long usedBefore = usedHeap();
        try (HeapSampler sampler = HeapSampler.start()) {
            long start = System.nanoTime();
            action.run();
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            List<MemorySample> samples = sampler.stop();
            forceGc();
            long retainedBytes = Math.max(0L, usedHeap() - usedBefore);
            return new RunResult(elapsedMillis, retainedBytes, samples);
        }
    }

    static long timeNanos(Runnable action) {
        long start = System.nanoTime();
        action.run();
        return System.nanoTime() - start;
    }

    static void writeAppender(Measurement measurement) throws IOException {
        Files.createDirectories(RESULTS_DIRECTORY);
        Path csv = RESULTS_DIRECTORY.resolve("appender-results.csv");
        if (Files.notExists(csv)) {
            Files.writeString(csv,
                    "appender,maxSize,append_ms,format_ms,total_ms,retained_bytes,discarded\n");
        }
        String row = String.format(Locale.ROOT, "%s,%d,%d,%d,%d,%d,%d%n",
                measurement.appender(), measurement.maxSize(), measurement.appendMillis(),
                measurement.formatMillis(), measurement.totalMillis(), measurement.retainedBytes(),
                measurement.discarded());
        Files.writeString(csv, row, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        writeSamples(measurement);
    }

    static void writeLayout(String layout, int events, long nanos) throws IOException {
        Files.createDirectories(RESULTS_DIRECTORY);
        Path csv = RESULTS_DIRECTORY.resolve("layout-results.csv");
        if (Files.notExists(csv)) {
            Files.writeString(csv, "layout,events,format_ms,ns_per_event\n");
        }
        double millis = nanos / 1_000_000.0;
        double nanosPerEvent = (double) nanos / events;
        String row = String.format(Locale.ROOT, "%s,%d,%.3f,%.2f%n",
                layout, events, millis, nanosPerEvent);
        Files.writeString(csv, row, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    static void writeBeforeAfter(String list, int maxSize, String phase, int events, double millis)
            throws IOException {
        Files.createDirectories(RESULTS_DIRECTORY);
        Path csv = RESULTS_DIRECTORY.resolve("before-after-results.csv");
        if (Files.notExists(csv)) {
            Files.writeString(csv, "list,maxSize,phase,events,time_ms\n");
        }
        String row = String.format(Locale.ROOT, "%s,%d,%s,%d,%.3f%n",
                list, maxSize, phase, events, millis);
        Files.writeString(csv, row, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    static long usedHeap() {
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    static void forceGc() {
        System.gc();
        sleep(50);
    }

    private static void writeSamples(Measurement measurement) throws IOException {
        String safeName = measurement.appender().replaceAll("[^A-Za-z0-9.-]", "_");
        Path csv = RESULTS_DIRECTORY.resolve(
                "heap-" + safeName + "-max" + measurement.maxSize() + ".csv");
        StringBuilder content = new StringBuilder("elapsed_ms,used_bytes\n");
        for (MemorySample sample : measurement.samples()) {
            content.append(sample.elapsedMillis()).append(',').append(sample.usedBytes()).append('\n');
        }
        Files.writeString(csv, content.toString());
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class HeapSampler implements AutoCloseable {

        private final AtomicBoolean running = new AtomicBoolean(true);
        private final List<MemorySample> samples = Collections.synchronizedList(new ArrayList<>());
        private final long startNanos = System.nanoTime();
        private final Thread samplerThread;

        private HeapSampler() {
            samples.add(new MemorySample(0L, usedHeap()));
            samplerThread = new Thread(this::sampleLoop, "heap-sampler");
            samplerThread.setDaemon(true);
            samplerThread.start();
        }

        static HeapSampler start() {
            return new HeapSampler();
        }

        private void sampleLoop() {
            while (running.get()) {
                sleep(10);
                if (running.get()) {
                    samples.add(new MemorySample(elapsedMillis(), usedHeap()));
                }
            }
        }

        synchronized List<MemorySample> stop() {
            if (running.getAndSet(false)) {
                try {
                    samplerThread.join(500);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
                samples.add(new MemorySample(elapsedMillis(), usedHeap()));
            }
            synchronized (samples) {
                return List.copyOf(samples);
            }
        }

        private long elapsedMillis() {
            return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
        }

        @Override
        public void close() {
            stop();
        }
    }
}
