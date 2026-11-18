package assign251_2;

/**
 * JMX management view of a {@link MemAppender}.
 *
 * <p>The interface follows the standard MBean naming convention, so a
 * {@code MemAppender} instance is exposed automatically when it is
 * registered with the platform MBean server.</p>
 */
public interface MemAppenderMBean {

    /**
     * Returns the cached log messages, formatted with the appender layout when
     * one is available and using the raw message text otherwise.
     *
     * @return a snapshot of the cached messages; never {@code null}
     */
    String[] getLogMessages();

    /**
     * Returns the estimated number of characters currently held in memory.
     *
     * <p>This is an estimate because a Java {@code String} also stores object
     * headers and uses UTF-16 code units internally.</p>
     *
     * @return total character count of the cached log messages
     */
    long getEstimatedLogSize();

    /**
     * Returns how many log events have been discarded because {@code maxSize}
     * was reached.
     *
     * @return number of discarded events, never negative
     */
    long getDiscardedLogCount();
}
