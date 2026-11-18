package assign251_2;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.ConsoleAppender;
import org.apache.logging.log4j.core.appender.FileAppender;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.message.ParameterizedMessage;
import org.apache.logging.log4j.message.SimpleMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VelocityLayoutTest {

    private static final String LINE_SEPARATOR = System.lineSeparator();

    @BeforeEach
    @AfterEach
    void resetSingleton() {
        MemAppender.resetInstance();
    }

    @Test
    void formatsEverySupportedVariable() {
        VelocityLayout layout = new VelocityLayout("$c|$d|$m|$p|$t|$n");
        long timeMillis = 1_234_567_890_123L;

        String formatted = layout.toSerializable(event(Level.WARN, "assign251_2.Logger",
                "message", timeMillis));

        assertEquals("assign251_2.Logger|" + new Date(timeMillis) + "|message|WARN|test-thread|"
                + LINE_SEPARATOR, formatted);
    }

    @Test
    void constructorSuppliesThePattern() {
        VelocityLayout layout = new VelocityLayout("[$p] $m");

        assertEquals("[$p] $m", layout.getPattern());
    }

    @Test
    void setterChangesThePattern() {
        VelocityLayout layout = new VelocityLayout("$m");
        LogEvent event = event(Level.INFO, "logger", "hello", 0L);

        assertEquals("hello", layout.toSerializable(event));

        layout.setPattern("[$p] $m");

        assertEquals("[$p] $m", layout.getPattern());
        assertEquals("[INFO] hello", layout.toSerializable(event));
    }

    @Test
    void defaultPatternFormatsMessageAndLineSeparator() {
        VelocityLayout layout = new VelocityLayout();

        assertEquals("hello" + LINE_SEPARATOR,
                layout.toSerializable(event(Level.INFO, "logger", "hello", 0L)));
    }

    @Test
    void pluginFactoryCreatesAConfiguredLayout() {
        VelocityLayout layout = VelocityLayout.createLayout("[$p] $m", "UTF-8");

        assertEquals("[INFO] hello",
                layout.toSerializable(event(Level.INFO, "logger", "hello", 0L)));
    }

    @Test
    void formatsParameterizedMessages() {
        LogEvent event = Log4jLogEvent.newBuilder()
                .setLoggerName("logger")
                .setLevel(Level.INFO)
                .setMessage(new ParameterizedMessage("Hello {}", "World"))
                .build();

        assertEquals("Hello World", new VelocityLayout("$m").toSerializable(event));
    }

    @Test
    void formatsANullMessageAsAnEmptyString() {
        LogEvent event = Log4jLogEvent.newBuilder()
                .setLoggerName("logger")
                .setLevel(Level.INFO)
                .build();

        assertEquals("", new VelocityLayout("$m").toSerializable(event));
    }

    @ParameterizedTest
    @ValueSource(strings = {"TRACE", "DEBUG", "INFO", "WARN", "ERROR", "FATAL"})
    void rendersEveryStandardLevel(String levelName) {
        VelocityLayout layout = new VelocityLayout("$p");
        Level level = Level.valueOf(levelName);

        assertEquals(level.toString(),
                layout.toSerializable(event(level, "logger", "message", 0L)));
    }

    @Test
    void rejectsNullPatterns() {
        assertThrows(NullPointerException.class, () -> new VelocityLayout((String) null));

        VelocityLayout layout = new VelocityLayout("$m");
        assertThrows(NullPointerException.class, () -> layout.setPattern(null));
    }

    @Test
    void rejectsInvalidPatternAndKeepsThePreviousTemplate() {
        VelocityLayout layout = new VelocityLayout("$m");

        assertThrows(IllegalArgumentException.class, () -> layout.setPattern("#if($m)"));

        assertEquals("$m", layout.getPattern());
        assertEquals("hello", layout.toSerializable(event(Level.INFO, "logger", "hello", 0L)));
    }

    @Test
    void worksWithTheBuiltInConsoleAppender() {
        VelocityLayout layout = new VelocityLayout("[$p] $m$n");
        PrintStream originalOut = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        ConsoleAppender appender = null;
        try {
            appender = ConsoleAppender.newBuilder()
                    .setName("velocity-console")
                    .setLayout(layout)
                    .setTarget(ConsoleAppender.Target.SYSTEM_OUT)
                    .setFollow(true)
                    .build();
            appender.start();
            appender.append(event(Level.INFO, "logger", "console", 0L));
        } finally {
            if (appender != null) {
                appender.stop();
            }
            System.setOut(originalOut);
        }

        assertEquals("[INFO] console" + LINE_SEPARATOR, buffer.toString(StandardCharsets.UTF_8));
    }

    @Test
    void worksWithTheBuiltInFileAppender(@TempDir Path tempDirectory) throws Exception {
        VelocityLayout layout = new VelocityLayout("[$p] $m$n");
        Path logFile = tempDirectory.resolve("velocity-layout.log");
        FileAppender appender = FileAppender.newBuilder()
                .setName("velocity-file")
                .setLayout(layout)
                .setFileName(logFile.toString())
                .setAppend(false)
                .build();

        appender.start();
        try {
            appender.append(event(Level.WARN, "logger", "file", 0L));
        } finally {
            appender.stop();
        }

        assertEquals("[WARN] file" + LINE_SEPARATOR, Files.readString(logFile));
    }

    @Test
    void worksWithMemAppender() {
        VelocityLayout layout = new VelocityLayout("[$p] $m$n");
        MemAppender appender = MemAppender.getInstance(layout, new ArrayList<>());
        appender.start();

        appender.append(event(Level.ERROR, "logger", "memory", 0L));

        assertEquals("[ERROR] memory" + LINE_SEPARATOR, appender.getEventStrings().get(0));
        assertTrue(appender.getEventStrings().get(0).contains("memory"));
    }

    @Test
    void charsetConstructorUsesUtf8() {
        VelocityLayout layout = new VelocityLayout("$m", StandardCharsets.UTF_8);

        assertEquals("Grüße", layout.toSerializable(event(Level.INFO, "logger", "Grüße", 0L)));
    }

    private static LogEvent event(Level level, String loggerName, String message, long timeMillis) {
        return Log4jLogEvent.newBuilder()
                .setLoggerName(loggerName)
                .setLevel(level)
                .setThreadName("test-thread")
                .setTimeMillis(timeMillis)
                .setMessage(new SimpleMessage(message))
                .build();
    }
}
