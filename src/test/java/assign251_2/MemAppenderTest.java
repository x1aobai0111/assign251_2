package assign251_2;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.message.SimpleMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemAppenderTest {

    private static final String LINE_SEPARATOR = System.lineSeparator();

    @BeforeEach
    @AfterEach
    void resetSingleton() {
        MemAppender.resetInstance();
    }

    @Test
    void returnsTheSameSingletonInstance() {
        MemAppender first = MemAppender.getInstance(new VelocityLayout("$m"), new ArrayList<>());
        MemAppender second = MemAppender.getInstance(new VelocityLayout("$m"), new LinkedList<>());

        assertSame(first, second);
    }

    @Test
    void injectsTheSuppliedList() {
        List<LogEvent> injectedList = new LinkedList<>();
        MemAppender appender = MemAppender.getInstance(new VelocityLayout("$m"), injectedList);

        appender.append(event("injected"));

        assertEquals(1, injectedList.size());
        assertEquals("injected", injectedList.get(0).getMessage().getFormattedMessage());
    }

    @Test
    void createsADefaultListWhenNoneIsInjected() {
        MemAppender appender = MemAppender.getInstance(null, null);

        appender.append(event("default-list"));

        assertEquals(1, appender.getCurrentLogs().size());
    }

    @Test
    void returnsAnUnmodifiableSnapshotOfCurrentLogs() {
        MemAppender appender = MemAppender.getInstance();
        appender.append(event("first"));

        List<LogEvent> snapshot = appender.getCurrentLogs();
        appender.append(event("second"));

        assertThrows(UnsupportedOperationException.class, snapshot::clear);
        assertEquals(1, snapshot.size());
        assertEquals(2, appender.getCurrentLogs().size());
    }

    @Test
    void formatsEventStringsWithTheInjectedLayout() {
        MemAppender appender = MemAppender.getInstance(new VelocityLayout("[$p] $m$n"));
        appender.append(event("hello"));

        List<String> strings = appender.getEventStrings();

        assertEquals(List.of("[INFO] hello" + LINE_SEPARATOR), strings);
        assertThrows(UnsupportedOperationException.class, strings::clear);
    }

    @Test
    void eventStringsRequireALayout() {
        MemAppender appender = MemAppender.getInstance();
        appender.append(event("without-layout"));

        IllegalStateException exception = assertThrows(IllegalStateException.class, appender::getEventStrings);

        assertTrue(exception.getMessage().contains("layout"));
    }

    @Test
    void printLogsPrintsThenClearsWithoutCountingClearedEventsAsDiscarded() {
        MemAppender appender = MemAppender.getInstance(new VelocityLayout("$m$n"));
        appender.setMaxSize(10);
        appender.append(event("first"));
        appender.append(event("second"));

        String output = captureStandardOut(appender::printLogs);

        assertEquals("first" + LINE_SEPARATOR + "second" + LINE_SEPARATOR, output);
        assertTrue(appender.getCurrentLogs().isEmpty());
        assertEquals(0L, appender.getDiscardedLogCount());
    }

    @Test
    void printLogsRequiresALayout() {
        MemAppender appender = MemAppender.getInstance();
        appender.append(event("without-layout"));

        assertThrows(IllegalStateException.class, appender::printLogs);
    }

    @Test
    void evictsTheOldestEventsWhenMaxSizeIsReached() {
        MemAppender appender = MemAppender.getInstance(new VelocityLayout("$m"));
        appender.setMaxSize(3);

        appendMessages(appender, "1", "2", "3", "4", "5");

        assertEquals(List.of("3", "4", "5"), messagesOf(appender));
        assertEquals(2L, appender.getDiscardedLogCount());
    }

    @Test
    void keepsOnlyTheLatestEventWhenMaxSizeIsOne() {
        MemAppender appender = MemAppender.getInstance(new VelocityLayout("$m"));
        appender.setMaxSize(1);

        appendMessages(appender, "old", "new");

        assertEquals(List.of("new"), messagesOf(appender));
        assertEquals(1L, appender.getDiscardedLogCount());
    }

    @Test
    void reducingMaxSizeDiscardsEnoughEventsForTheNextAppend() {
        MemAppender appender = MemAppender.getInstance(new VelocityLayout("$m"));
        appender.setMaxSize(5);
        appendMessages(appender, "1", "2", "3", "4", "5");

        appender.setMaxSize(2);
        appender.append(event("6"));

        assertEquals(List.of("5", "6"), messagesOf(appender));
        assertEquals(4L, appender.getDiscardedLogCount());
    }

    @Test
    void reportsTheConfiguredMaxSize() {
        MemAppender appender = MemAppender.getInstance();

        appender.setMaxSize(42);

        assertEquals(42, appender.getMaxSize());
    }

    @Test
    void rejectsNonPositiveMaxSize() {
        MemAppender appender = MemAppender.getInstance();

        assertThrows(IllegalArgumentException.class, () -> appender.setMaxSize(0));
        assertThrows(IllegalArgumentException.class, () -> appender.setMaxSize(-1));
        assertEquals(MemAppender.DEFAULT_MAX_SIZE, appender.getMaxSize());
    }

    @Test
    void rejectsNullEvents() {
        MemAppender appender = MemAppender.getInstance();

        assertThrows(NullPointerException.class, () -> appender.append(null));
    }

    @Test
    void resetInstanceCreatesAFreshSingleton() {
        MemAppender first = MemAppender.getInstance();
        first.append(event("before-reset"));

        MemAppender.resetInstance();
        MemAppender second = MemAppender.getInstance();

        assertNotSame(first, second);
        assertTrue(second.getCurrentLogs().isEmpty());
    }

    @Test
    void keepsDiscardedCountWhenLogsArePrintedAndCleared() {
        MemAppender appender = MemAppender.getInstance(new VelocityLayout("$m"));
        appender.setMaxSize(2);
        appendMessages(appender, "1", "2", "3");

        captureStandardOut(appender::printLogs);

        assertTrue(appender.getCurrentLogs().isEmpty());
        assertEquals(1L, appender.getDiscardedLogCount());
    }

    private static LogEvent event(String message) {
        return Log4jLogEvent.newBuilder()
                .setLoggerName("assign251_2.MemAppenderTest")
                .setLevel(Level.INFO)
                .setThreadName(Thread.currentThread().getName())
                .setMessage(new SimpleMessage(message))
                .build();
    }

    private static void appendMessages(MemAppender appender, String... messages) {
        for (String message : messages) {
            appender.append(event(message));
        }
    }

    private static List<String> messagesOf(MemAppender appender) {
        return appender.getCurrentLogs().stream()
                .map(event -> event.getMessage().getFormattedMessage())
                .toList();
    }

    private static String captureStandardOut(Runnable action) {
        PrintStream originalOut = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        try {
            action.run();
        } finally {
            System.setOut(originalOut);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }
}
