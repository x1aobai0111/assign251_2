package assign251_2;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.ConsoleAppender;
import org.apache.logging.log4j.core.appender.FileAppender;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.apache.logging.log4j.message.SimpleMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stress tests comparing the in-memory appender with built-in appenders and
 * comparing the Velocity layout with the built-in pattern layout.
 *
 * <p>The tests report time in milliseconds and retained heap in bytes. Raw
 * measurements are written to {@code target/stress-results/} so that the
 * performance report can quote exactly the values produced by the build.</p>
 */
class StressPerformanceTest {

    private static final int LOG_COUNT = 10_000;
    private static final String PATTERN = "[%p] %c %d: %m%n";

    private static final PatternLayout PATTERN_LAYOUT = PatternLayout.newBuilder()
            .withPattern(PATTERN)
            .build();

    private static List<LogEvent> events;

    @BeforeAll
    static void createEvents() {
        long baseTime = 1_700_000_000_000L;
        List<LogEvent> createdEvents = new ArrayList<>(LOG_COUNT);
        for (int i = 0; i < LOG_COUNT; i++) {
            createdEvents.add(Log4jLogEvent.newBuilder()
                    .setLoggerName("assign251_2.stress")
                    .setLevel(Level.INFO)
                    .setThreadName("stress-main")
                    .setTimeMillis(baseTime + i)
                    .setMessage(new SimpleMessage("stress-message-" + i))
                    .build());
        }
        events = List.copyOf(createdEvents);
    }

    @BeforeEach
    @AfterEach
    void resetSingleton() {
        MemAppender.resetInstance();
    }

    @ParameterizedTest(name = "maxSize={0}")
    @ValueSource(ints = {1, 10, 100, 1000, 10000, 1_000_000})
    void comparesAppendersAcrossMaxSizes(int maxSize) throws IOException {
        runMemAppender("MemAppender-ArrayList", maxSize, new ArrayList<>());
        runMemAppender("MemAppender-LinkedList", maxSize, new LinkedList<>());

        StressSupport.RunResult consoleRun = measureConsoleAppender();
        record("ConsoleAppender", maxSize, consoleRun, 0L, 0L);

        StressSupport.RunResult fileRun = measureFileAppender(maxSize);
        assertTrue(Files.size(fileLog(maxSize)) > 0);
        record("FileAppender", maxSize, fileRun, 0L, 0L);
    }

    @Test
    void comparesVelocityLayoutWithPatternLayout() throws IOException {
        PatternLayout patternLayout = PatternLayout.newBuilder()
                .withPattern("[%p] %c %d: %m%n")
                .build();
        VelocityLayout velocityLayout = new VelocityLayout("[$p] $c $d: $m$n");
        int repetitions = 5;
        int formattedEvents = LOG_COUNT * repetitions;

        formatAll(patternLayout, 1);
        formatAll(velocityLayout, 1);

        long patternNanos = formatAll(patternLayout, repetitions);
        long velocityNanos = formatAll(velocityLayout, repetitions);

        StressSupport.writeLayout("PatternLayout", formattedEvents, patternNanos);
        StressSupport.writeLayout("VelocityLayout", formattedEvents, velocityNanos);
        System.out.printf(Locale.ROOT,
                "Layout comparison (%d events): PatternLayout=%.3f ms (%.2f ns/event), "
                        + "VelocityLayout=%.3f ms (%.2f ns/event)%n",
                formattedEvents, patternNanos / 1_000_000.0, (double) patternNanos / formattedEvents,
                velocityNanos / 1_000_000.0, (double) velocityNanos / formattedEvents);

        assertTrue(patternNanos > 0);
        assertTrue(velocityNanos > 0);

        String velocitySample = velocityLayout.toSerializable(events.get(0));
        assertTrue(velocitySample.contains("INFO"));
        assertTrue(velocitySample.contains("assign251_2.stress"));
        assertFalse(velocitySample.isBlank());
    }

    @Test
    void comparesPerformanceBeforeAndAfterMaxSizeIsReached() throws IOException {
        int maxSize = 1000;
        for (List<LogEvent> store : List.<List<LogEvent>>of(new ArrayList<>(), new LinkedList<>())) {
            MemAppender appender = MemAppender.getInstance(PATTERN_LAYOUT, store);
            appender.setMaxSize(maxSize);
            appender.start();
            String listName = store.getClass().getSimpleName();

            long beforeNanos = StressSupport.timeNanos(
                    () -> events.subList(0, maxSize).forEach(appender::append));
            assertEquals(0L, appender.getDiscardedLogCount());

            long afterNanos = StressSupport.timeNanos(
                    () -> events.subList(maxSize, LOG_COUNT).forEach(appender::append));
            assertEquals(LOG_COUNT - maxSize, appender.getDiscardedLogCount());
            assertEquals(maxSize, appender.getCurrentLogs().size());

            StressSupport.writeBeforeAfter(listName, maxSize, "before", maxSize,
                    beforeNanos / 1_000_000.0);
            StressSupport.writeBeforeAfter(listName, maxSize, "after", LOG_COUNT - maxSize,
                    afterNanos / 1_000_000.0);
            System.out.printf(Locale.ROOT,
                    "%s maxSize=%d: before=%.3f ms (%d events), after=%.3f ms (%d events)%n",
                    listName, maxSize, beforeNanos / 1_000_000.0, maxSize,
                    afterNanos / 1_000_000.0, LOG_COUNT - maxSize);

            MemAppender.resetInstance();
        }
    }

    private void runMemAppender(String label, int maxSize, List<LogEvent> store) throws IOException {
        MemAppender appender = MemAppender.getInstance(PATTERN_LAYOUT, store);
        appender.setMaxSize(maxSize);
        appender.start();
        try {
            StressSupport.RunResult appendRun = StressSupport.timed(
                    () -> events.forEach(appender::append));

            long formatStart = System.nanoTime();
            List<String> formattedEvents = appender.getEventStrings();
            long formatMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - formatStart);

            long discarded = appender.getDiscardedLogCount();
            assertEquals(Math.min(LOG_COUNT, maxSize), appender.getCurrentLogs().size());
            assertEquals(Math.max(0, LOG_COUNT - maxSize), discarded);
            assertFalse(formattedEvents.isEmpty());

            record(label, maxSize, appendRun, formatMillis, discarded);
        } finally {
            MemAppender.resetInstance();
        }
    }

    private static StressSupport.RunResult measureConsoleAppender() {
        PrintStream originalOut = System.out;
        PrintStream sink = new PrintStream(OutputStream.nullOutputStream(), false,
                StandardCharsets.UTF_8);
        System.setOut(sink);
        try {
            ConsoleAppender consoleAppender = ConsoleAppender.newBuilder()
                    .setName("stress-console-" + System.nanoTime())
                    .setLayout(PATTERN_LAYOUT)
                    .setTarget(ConsoleAppender.Target.SYSTEM_OUT)
                    .setFollow(true)
                    .build();
            consoleAppender.start();
            try {
                return StressSupport.timed(() -> events.forEach(consoleAppender::append));
            } finally {
                consoleAppender.stop();
            }
        } finally {
            System.setOut(originalOut);
            sink.close();
        }
    }

    private static StressSupport.RunResult measureFileAppender(int maxSize) throws IOException {
        Path logFile = fileLog(maxSize);
        Files.createDirectories(logFile.getParent());
        FileAppender fileAppender = FileAppender.newBuilder()
                .setName("stress-file-" + maxSize)
                .setLayout(PATTERN_LAYOUT)
                .setFileName(logFile.toString())
                .setAppend(false)
                .build();
        fileAppender.start();
        try {
            return StressSupport.timed(() -> events.forEach(fileAppender::append));
        } finally {
            fileAppender.stop();
        }
    }

    private static void record(String label, int maxSize, StressSupport.RunResult run,
                               long formatMillis, long discarded) throws IOException {
        long totalMillis = run.elapsedMillis() + formatMillis;
        System.out.printf(Locale.ROOT,
                "%-24s maxSize=%-8d append=%4d ms format=%4d ms total=%4d ms "
                        + "retained=%8.2f MB discarded=%d%n",
                label, maxSize, run.elapsedMillis(), formatMillis, totalMillis,
                run.retainedBytes() / (1024.0 * 1024.0), discarded);
        StressSupport.writeAppender(new StressSupport.Measurement(label, maxSize,
                run.elapsedMillis(), formatMillis, run.retainedBytes(), discarded, run.samples()));
    }

    private static long formatAll(Layout<String> layout, int repetitions) {
        long characters = 0;
        long start = System.nanoTime();
        for (int round = 0; round < repetitions; round++) {
            for (LogEvent event : events) {
                characters += layout.toSerializable(event).length();
            }
        }
        long elapsedNanos = System.nanoTime() - start;
        assertTrue(characters > 0);
        return elapsedNanos;
    }

    private static Path fileLog(int maxSize) {
        return StressSupport.LOG_DIRECTORY.resolve("file-max-" + maxSize + ".log");
    }
}
