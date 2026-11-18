package assign251_2;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.ConsoleAppender;
import org.apache.logging.log4j.core.appender.FileAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemAppenderIntegrationTest {

    private static final String LINE_SEPARATOR = System.lineSeparator();

    private LoggerContext context;

    @BeforeEach
    void createLoggerContext() {
        MemAppender.resetInstance();
        context = new LoggerContext("integration-" + UUID.randomUUID());
    }

    @AfterEach
    void closeLoggerContext() {
        context.stop();
        MemAppender.resetInstance();
    }

    @Test
    void receivesEveryEventThatPassesTheLoggerLevel() {
        MemAppender memAppender = MemAppender.getInstance(new VelocityLayout("[$p] $c: $m$n"));
        memAppender.setMaxSize(100);
        memAppender.start();
        Logger logger = loggerWith("assign251_2.integration.levels", Level.TRACE, memAppender);

        logger.trace("trace");
        logger.debug("debug");
        logger.info("info");
        logger.warn("warn");
        logger.error("error");

        List<String> levels = memAppender.getCurrentLogs().stream()
                .map(event -> event.getLevel().name())
                .toList();
        assertEquals(List.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR"), levels);
        assertEquals(5, memAppender.getEventStrings().size());
    }

    @Test
    void respectsTheLoggerLevelFilter() {
        MemAppender memAppender = MemAppender.getInstance(new VelocityLayout("$m"));
        memAppender.start();
        Logger logger = loggerWith("assign251_2.integration.filter", Level.WARN, memAppender);

        logger.info("ignored");
        logger.warn("kept");
        logger.error("also-kept");

        assertEquals(List.of("kept", "also-kept"), messagesOf(memAppender));
    }

    @Test
    void separatesEventsFromDifferentLoggers() {
        MemAppender memAppender = MemAppender.getInstance(new VelocityLayout("$c: $m"));
        memAppender.start();
        Logger firstLogger = loggerWith("assign251_2.integration.first", Level.INFO, memAppender);
        Logger secondLogger = loggerWith("assign251_2.integration.second", Level.INFO, memAppender);

        firstLogger.info("one");
        secondLogger.info("two");

        Set<String> loggerNames = memAppender.getCurrentLogs().stream()
                .map(event -> event.getLoggerName())
                .collect(Collectors.toSet());
        assertEquals(Set.of("assign251_2.integration.first", "assign251_2.integration.second"),
                loggerNames);
    }

    @Test
    void worksWithABuiltInPatternLayout() {
        PatternLayout patternLayout = PatternLayout.newBuilder()
                .withPattern("[%p] %c: %m")
                .build();
        MemAppender memAppender = MemAppender.getInstance(patternLayout);
        memAppender.start();
        Logger logger = loggerWith("assign251_2.integration.pattern", Level.INFO, memAppender);

        logger.info("pattern-layout");

        assertEquals("[INFO] assign251_2.integration.pattern: pattern-layout",
                memAppender.getEventStrings().get(0));
    }

    @Test
    void worksAlongsideABuiltInConsoleAppender() {
        MemAppender memAppender = MemAppender.getInstance(new VelocityLayout("$m"));
        memAppender.start();
        PatternLayout consoleLayout = PatternLayout.newBuilder().withPattern("CONSOLE %m%n").build();
        PrintStream originalOut = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        ConsoleAppender consoleAppender = null;

        System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        try {
            consoleAppender = ConsoleAppender.newBuilder()
                    .setName("integration-console")
                    .setLayout(consoleLayout)
                    .setTarget(ConsoleAppender.Target.SYSTEM_OUT)
                    .setFollow(true)
                    .build();
            consoleAppender.start();
            Logger logger = loggerWith("assign251_2.integration.both", Level.INFO,
                    memAppender, consoleAppender);

            logger.info("shared-event");
        } finally {
            if (consoleAppender != null) {
                consoleAppender.stop();
            }
            System.setOut(originalOut);
        }

        assertEquals("CONSOLE shared-event" + LINE_SEPARATOR,
                buffer.toString(StandardCharsets.UTF_8));
        assertEquals(List.of("shared-event"), messagesOf(memAppender));
    }

    @Test
    void worksWithAFileAppenderAndVelocityLayout(@TempDir Path tempDirectory) throws Exception {
        VelocityLayout velocityLayout = new VelocityLayout("$p $c $m$n");
        Path logFile = tempDirectory.resolve("integration.log");
        FileAppender fileAppender = FileAppender.newBuilder()
                .setName("integration-file")
                .setLayout(velocityLayout)
                .setFileName(logFile.toString())
                .setAppend(false)
                .build();
        fileAppender.start();
        try {
            Logger logger = loggerWith("assign251_2.integration.file", Level.INFO, fileAppender);

            logger.info("file-event");
        } finally {
            fileAppender.stop();
        }

        assertEquals("INFO assign251_2.integration.file file-event" + LINE_SEPARATOR,
                Files.readString(logFile));
    }

    @Test
    void stopsDiscardingOnlyWhenTheConfiguredSizeIsExceeded() {
        MemAppender memAppender = MemAppender.getInstance(new VelocityLayout("$m"));
        memAppender.setMaxSize(3);
        memAppender.start();
        Logger logger = loggerWith("assign251_2.integration.maxsize", Level.INFO, memAppender);

        for (int i = 1; i <= 5; i++) {
            logger.info("event-" + i);
        }

        assertEquals(List.of("event-3", "event-4", "event-5"), messagesOf(memAppender));
        assertEquals(2L, memAppender.getDiscardedLogCount());
        assertTrue(memAppender.getEstimatedLogSize() > 0);
    }

    private Logger loggerWith(String loggerName, Level level, Appender... appenders) {
        Configuration configuration = context.getConfiguration();
        LoggerConfig loggerConfig = new LoggerConfig(loggerName, level, false);
        for (Appender appender : appenders) {
            loggerConfig.addAppender(appender, null, null);
        }
        configuration.addLogger(loggerName, loggerConfig);
        context.updateLoggers();
        return context.getLogger(loggerName);
    }

    private static List<String> messagesOf(MemAppender appender) {
        List<String> messages = new ArrayList<>();
        appender.getCurrentLogs().forEach(event ->
                messages.add(event.getMessage().getFormattedMessage()));
        return messages;
    }
}
