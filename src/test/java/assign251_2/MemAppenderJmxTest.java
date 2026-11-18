package assign251_2;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.message.SimpleMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemAppenderJmxTest {

    private static final String MBEAN_QUERY = "assign251_2:type=MemAppender,*";

    @BeforeEach
    @AfterEach
    void resetSingleton() {
        MemAppender.resetInstance();
    }

    @Test
    void registersAnMBeanWithTheExpectedAttributes() throws Exception {
        MemAppender appender = MemAppender.getInstance(new VelocityLayout("$m"));
        appender.setMaxSize(2);
        appender.start();
        appender.append(event("alpha"));
        appender.append(event("beta"));
        appender.append(event("gamma"));

        MBeanServer server = ManagementFactory.getPlatformMBeanServer();
        ObjectName objectName = onlyRegisteredMBean(server);

        assertArrayEquals(new String[]{"beta", "gamma"},
                (String[]) server.getAttribute(objectName, "LogMessages"));
        assertEquals(9L, server.getAttribute(objectName, "EstimatedLogSize"));
        assertEquals(1L, server.getAttribute(objectName, "DiscardedLogCount"));
    }

    @Test
    void exposesRawMessagesWhenNoLayoutWasInjected() throws Exception {
        MemAppender appender = MemAppender.getInstance();
        appender.start();
        appender.append(event("raw-message"));

        MBeanServer server = ManagementFactory.getPlatformMBeanServer();
        ObjectName objectName = onlyRegisteredMBean(server);

        assertArrayEquals(new String[]{"raw-message"},
                (String[]) server.getAttribute(objectName, "LogMessages"));
        assertEquals(11L, server.getAttribute(objectName, "EstimatedLogSize"));
    }

    @Test
    void unregistersTheMBeanWhenTheAppenderIsStopped() throws Exception {
        MemAppender appender = MemAppender.getInstance(new VelocityLayout("$m"));
        appender.start();
        MBeanServer server = ManagementFactory.getPlatformMBeanServer();

        assertFalse(server.queryNames(new ObjectName(MBEAN_QUERY), null).isEmpty());

        appender.stop();

        assertTrue(server.queryNames(new ObjectName(MBEAN_QUERY), null).isEmpty());
    }

    @Test
    void resetInstanceUnregistersTheMBean() throws Exception {
        MemAppender appender = MemAppender.getInstance(new VelocityLayout("$m"));
        appender.start();
        MBeanServer server = ManagementFactory.getPlatformMBeanServer();

        MemAppender.resetInstance();

        assertTrue(server.queryNames(new ObjectName(MBEAN_QUERY), null).isEmpty());
    }

    @Test
    void startingTwiceRegistersOnlyOneMBean() throws Exception {
        MemAppender appender = MemAppender.getInstance(new VelocityLayout("$m"));
        appender.start();
        appender.start();
        MBeanServer server = ManagementFactory.getPlatformMBeanServer();

        assertEquals(1, server.queryNames(new ObjectName(MBEAN_QUERY), null).size());

        appender.stop();
    }

    @Test
    void stoppingTwiceIsSafe() throws Exception {
        MemAppender appender = MemAppender.getInstance(new VelocityLayout("$m"));
        appender.start();
        MBeanServer server = ManagementFactory.getPlatformMBeanServer();

        appender.stop();
        appender.stop();

        assertTrue(server.queryNames(new ObjectName(MBEAN_QUERY), null).isEmpty());
    }

    @Test
    void reflectedClearedLogsAreNotCountedAsDiscarded() throws Exception {
        MemAppender appender = MemAppender.getInstance(new VelocityLayout("$m"));
        appender.setMaxSize(5);
        appender.start();
        appender.append(event("first"));
        appender.append(event("second"));

        captureStandardOut(appender::printLogs);

        MBeanServer server = ManagementFactory.getPlatformMBeanServer();
        ObjectName objectName = onlyRegisteredMBean(server);
        assertArrayEquals(new String[0],
                (String[]) server.getAttribute(objectName, "LogMessages"));
        assertEquals(0L, server.getAttribute(objectName, "EstimatedLogSize"));
        assertEquals(0L, server.getAttribute(objectName, "DiscardedLogCount"));
    }

    private static ObjectName onlyRegisteredMBean(MBeanServer server) throws Exception {
        Set<ObjectName> names = server.queryNames(new ObjectName(MBEAN_QUERY), null);
        assertEquals(1, names.size());
        return names.iterator().next();
    }

    private static LogEvent event(String message) {
        return Log4jLogEvent.newBuilder()
                .setLoggerName("assign251_2.MemAppenderJmxTest")
                .setLevel(Level.INFO)
                .setThreadName(Thread.currentThread().getName())
                .setMessage(new SimpleMessage(message))
                .build();
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
