package assign251_2;

import com.google.common.base.Preconditions;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.Core;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.config.plugins.Plugin;

import javax.management.JMException;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.io.Serializable;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A Log4j 2 appender that keeps log events in memory instead of writing them
 * to an external destination.
 *
 * <p>The appender is a singleton. The first call to one of the
 * {@code getInstance} methods creates the instance and injects its two
 * dependencies:</p>
 * <ul>
 *   <li>the {@link Layout} used to format events, which may be {@code null}
 *       until an operation that needs formatting is requested;</li>
 *   <li>the {@link List} used as the event store, which may be supplied by the
 *       caller (dependency injection) or defaulted to an {@link ArrayList}.</li>
 * </ul>
 *
 * <p>The appender keeps at most {@link #getMaxSize()} events. When the store is
 * full, the oldest event is removed before the new event is added. Removing an
 * event this way increments {@link #getDiscardedLogCount()}; clearing the store
 * with {@link #printLogs()} does not.</p>
 */
@Plugin(name = "MemAppender", category = Core.CATEGORY_NAME,
        elementType = Appender.ELEMENT_TYPE, printObject = true)
public final class MemAppender extends AbstractAppender implements MemAppenderMBean {

    /** Default upper bound for the in-memory event store. */
    public static final int DEFAULT_MAX_SIZE = 100;

    private static final String MBEAN_DOMAIN = "assign251_2";

    private static MemAppender instance;

    private final List<LogEvent> logEvents;
    private volatile int maxSize = DEFAULT_MAX_SIZE;
    private long discardedLogCount;
    private ObjectName objectName;

    private MemAppender(String name, Filter filter, Layout<? extends Serializable> layout,
                        boolean ignoreExceptions, List<LogEvent> logEvents) {
        super(name, filter, layout, ignoreExceptions, Property.EMPTY_ARRAY);
        this.logEvents = logEvents;
    }

    /**
     * Returns the singleton, creating it on first use.
     *
     * <p>The supplied layout and list are used only when the singleton is
     * created. Later calls return the existing instance and ignore new
     * dependency arguments, which preserves the singleton contract.</p>
     *
     * @param layout    layout used to format events; may be {@code null}
     * @param logEvents list used as the event store; {@code null} selects a
     *                  default {@link ArrayList}
     * @return the singleton appender
     */
    public static synchronized MemAppender getInstance(Layout<? extends Serializable> layout,
                                                       List<LogEvent> logEvents) {
        if (instance == null) {
            List<LogEvent> store = logEvents == null ? new ArrayList<>() : logEvents;
            instance = new MemAppender("MemAppender", null, layout, true, store);
        }
        return instance;
    }

    /**
     * Returns the singleton, using a default {@link ArrayList} as the store.
     *
     * @param layout layout used to format events; may be {@code null}
     * @return the singleton appender
     */
    public static synchronized MemAppender getInstance(Layout<? extends Serializable> layout) {
        return getInstance(layout, null);
    }

    /**
     * Returns the singleton without an injected layout or list.
     *
     * @return the singleton appender
     */
    public static synchronized MemAppender getInstance() {
        return getInstance(null, null);
    }

    /**
     * Stops and forgets the singleton.
     *
     * <p>This method exists so that tests can isolate singleton state. It also
     * unregisters the JMX MBean registered by the appender.</p>
     */
    public static synchronized void resetInstance() {
        if (instance != null) {
            instance.stop();
            instance = null;
        }
    }

    /**
     * Stores an event and evicts the oldest events if the store is full.
     *
     * @param event event to store; must not be {@code null}
     */
    @Override
    public void append(LogEvent event) {
        Preconditions.checkNotNull(event, "event must not be null");
        LogEvent storedEvent = event.toImmutable();
        synchronized (logEvents) {
            while (logEvents.size() >= maxSize) {
                logEvents.remove(0);
                discardedLogCount++;
            }
            logEvents.add(storedEvent);
        }
    }

    /**
     * Returns an unmodifiable snapshot of the stored events.
     *
     * @return unmodifiable list of cached events
     */
    public List<LogEvent> getCurrentLogs() {
        synchronized (logEvents) {
            return Collections.unmodifiableList(new ArrayList<>(logEvents));
        }
    }

    /**
     * Returns the stored events formatted with the injected layout.
     *
     * @return unmodifiable list of formatted events
     * @throws IllegalStateException if no layout was supplied
     */
    public List<String> getEventStrings() {
        Layout<? extends Serializable> layout = requireLayout();
        synchronized (logEvents) {
            List<String> formattedEvents = new ArrayList<>(logEvents.size());
            for (LogEvent event : logEvents) {
                formattedEvents.add(format(layout, event));
            }
            return Collections.unmodifiableList(formattedEvents);
        }
    }

    /**
     * Prints the stored events using the layout and then clears the store.
     *
     * <p>Cleared events are not counted as discarded events.</p>
     *
     * @throws IllegalStateException if no layout was supplied
     */
    public void printLogs() {
        Layout<? extends Serializable> layout = requireLayout();
        synchronized (logEvents) {
            for (LogEvent event : logEvents) {
                System.out.print(format(layout, event));
            }
            System.out.flush();
            logEvents.clear();
        }
    }

    /**
     * Returns the maximum number of events retained in memory.
     *
     * @return positive maximum size
     */
    public int getMaxSize() {
        return maxSize;
    }

    /**
     * Sets the maximum number of events retained in memory.
     *
     * @param maxSize new maximum; must be greater than zero
     * @throws IllegalArgumentException if {@code maxSize} is not positive
     */
    public void setMaxSize(int maxSize) {
        Preconditions.checkArgument(maxSize > 0, "maxSize must be greater than zero: %s", maxSize);
        this.maxSize = maxSize;
    }

    /**
     * Returns the number of events evicted because the store was full.
     *
     * @return discarded event count
     */
    @Override
    public long getDiscardedLogCount() {
        synchronized (logEvents) {
            return discardedLogCount;
        }
    }

    @Override
    public String[] getLogMessages() {
        synchronized (logEvents) {
            String[] messages = new String[logEvents.size()];
            for (int i = 0; i < logEvents.size(); i++) {
                messages[i] = textOf(logEvents.get(i));
            }
            return messages;
        }
    }

    @Override
    public long getEstimatedLogSize() {
        synchronized (logEvents) {
            long characterCount = 0;
            for (LogEvent event : logEvents) {
                characterCount += textOf(event).length();
            }
            return characterCount;
        }
    }

    /**
     * Starts the appender and registers its JMX MBean.
     */
    @Override
    public void start() {
        super.start();
        registerMBean();
    }

    /**
     * Unregisters the JMX MBean and stops the appender.
     */
    @Override
    public void stop() {
        unregisterMBean();
        super.stop();
    }

    private Layout<? extends Serializable> requireLayout() {
        Layout<? extends Serializable> layout = getLayout();
        Preconditions.checkState(layout != null,
                "A layout must be supplied before formatted log operations are used");
        return layout;
    }

    private static String format(Layout<? extends Serializable> layout, LogEvent event) {
        return Objects.toString(layout.toSerializable(event), "");
    }

    /**
     * Returns the text used by the JMX view for one cached event: the formatted
     * event when a layout is available, otherwise the raw message.
     */
    private String textOf(LogEvent event) {
        Layout<? extends Serializable> layout = getLayout();
        return layout == null
                ? Objects.toString(event.getMessage(), "")
                : format(layout, event);
    }

    private void registerMBean() {
        if (objectName != null) {
            return;
        }
        try {
            ObjectName name = new ObjectName(MBEAN_DOMAIN + ":type="
                    + MemAppender.class.getSimpleName() + ",name=" + ObjectName.quote(getName()));
            MBeanServer server = ManagementFactory.getPlatformMBeanServer();
            if (server.isRegistered(name)) {
                server.unregisterMBean(name);
            }
            server.registerMBean(this, name);
            objectName = name;
        } catch (JMException exception) {
            LOGGER.error("Unable to register MBean for appender " + getName(), exception);
        }
    }

    private void unregisterMBean() {
        if (objectName == null) {
            return;
        }
        try {
            MBeanServer server = ManagementFactory.getPlatformMBeanServer();
            if (server.isRegistered(objectName)) {
                server.unregisterMBean(objectName);
            }
        } catch (JMException exception) {
            LOGGER.error("Unable to unregister MBean for appender " + getName(), exception);
        } finally {
            objectName = null;
        }
    }
}
