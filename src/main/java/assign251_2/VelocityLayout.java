package assign251_2;

import com.google.common.base.Preconditions;
import org.apache.logging.log4j.core.Core;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.config.plugins.PluginAttribute;
import org.apache.logging.log4j.core.config.plugins.PluginFactory;
import org.apache.logging.log4j.core.layout.AbstractStringLayout;
import org.apache.velocity.Template;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.exception.VelocityException;
import org.apache.velocity.runtime.RuntimeConstants;
import org.apache.velocity.runtime.resource.loader.StringResourceLoader;
import org.apache.velocity.runtime.resource.util.StringResourceRepository;

import java.io.StringWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A Log4j 2 layout that formats {@link LogEvent}s with a Velocity template.
 *
 * <p>The layout behaves like Log4j's pattern layout, but Velocity syntax is
 * used instead of percent placeholders. The supported variables are:</p>
 * <table border="1">
 *   <caption>Supported template variables</caption>
 *   <tr><th>Variable</th><th>Meaning</th></tr>
 *   <tr><td>{@code $c}</td><td>logger name</td></tr>
 *   <tr><td>{@code $d}</td><td>event date/time using {@link Date#toString()}</td></tr>
 *   <tr><td>{@code $m}</td><td>formatted message</td></tr>
 *   <tr><td>{@code $p}</td><td>log level</td></tr>
 *   <tr><td>{@code $t}</td><td>thread name</td></tr>
 *   <tr><td>{@code $n}</td><td>platform line separator</td></tr>
 * </table>
 *
 * <p>The pattern can be supplied through the constructor or changed with
 * {@link #setPattern(String)}. It is compiled once when set, so repeated
 * formatting does not re-parse the template.</p>
 */
@Plugin(name = "VelocityLayout", category = Core.CATEGORY_NAME,
        elementType = Layout.ELEMENT_TYPE, printObject = true)
public final class VelocityLayout extends AbstractStringLayout {

    /** Default pattern, equivalent to Log4j's {@code %m%n}. */
    public static final String DEFAULT_PATTERN = "$m$n";

    private static final AtomicInteger LAYOUT_SEQUENCE = new AtomicInteger();

    private final int layoutId;
    private final VelocityEngine velocityEngine;
    private final StringResourceRepository templateRepository;

    private String pattern;
    private int templateVersion;
    private volatile Template template;

    /**
     * Creates a layout with the default pattern and UTF-8 encoding.
     */
    public VelocityLayout() {
        this(DEFAULT_PATTERN, StandardCharsets.UTF_8);
    }

    /**
     * Creates a layout with the supplied pattern and UTF-8 encoding.
     *
     * @param pattern Velocity template pattern; must not be {@code null}
     */
    public VelocityLayout(String pattern) {
        this(pattern, StandardCharsets.UTF_8);
    }

    /**
     * Creates a layout with the supplied pattern and character encoding.
     *
     * @param pattern Velocity template pattern; must not be {@code null}
     * @param charset character encoding used by the layout
     */
    public VelocityLayout(String pattern, Charset charset) {
        super(Objects.requireNonNull(charset, "charset must not be null"));
        this.layoutId = LAYOUT_SEQUENCE.incrementAndGet();
        this.velocityEngine = createVelocityEngine();
        this.templateRepository = StringResourceLoader.getRepository();
        setPattern(pattern);
    }

    /**
     * Log4j plugin factory for configuration-file use.
     *
     * @param pattern template pattern
     * @param charset character encoding name
     * @return a configured layout
     */
    @PluginFactory
    public static VelocityLayout createLayout(
            @PluginAttribute(value = "pattern", defaultString = DEFAULT_PATTERN) String pattern,
            @PluginAttribute(value = "charset", defaultString = "UTF-8") String charset) {
        return new VelocityLayout(pattern, Charset.forName(charset));
    }

    /**
     * Changes the template used by this layout.
     *
     * <p>The template is compiled immediately. If compilation fails, the
     * previous valid template remains active.</p>
     *
     * @param pattern new Velocity template; must not be {@code null}
     * @throws IllegalArgumentException if Velocity cannot parse the template
     */
    public synchronized void setPattern(String pattern) {
        Preconditions.checkNotNull(pattern, "pattern must not be null");
        int version = ++templateVersion;
        String resourceName = "velocity-layout-" + layoutId + "-" + version;
        templateRepository.putStringResource(resourceName, pattern);
        Template compiledTemplate;
        try {
            compiledTemplate = velocityEngine.getTemplate(resourceName);
        } catch (VelocityException exception) {
            throw new IllegalArgumentException("Invalid Velocity pattern: " + pattern, exception);
        }
        this.pattern = pattern;
        this.template = compiledTemplate;
    }

    /**
     * Returns the current Velocity template.
     *
     * @return pattern supplied to the constructor or setter
     */
    public String getPattern() {
        return pattern;
    }

    @Override
    public String toSerializable(LogEvent event) {
        Preconditions.checkNotNull(event, "event must not be null");

        VelocityContext context = new VelocityContext();
        context.put("c", event.getLoggerName());
        context.put("d", new Date(event.getTimeMillis()));
        context.put("m", event.getMessage() == null ? "" : event.getMessage().getFormattedMessage());
        context.put("p", event.getLevel());
        context.put("t", event.getThreadName());
        context.put("n", System.lineSeparator());

        StringWriter writer = new StringWriter();
        try {
            template.merge(context, writer);
        } catch (VelocityException exception) {
            return "VelocityLayout error: " + exception.getMessage();
        }
        return writer.toString();
    }

    private static VelocityEngine createVelocityEngine() {
        Properties properties = new Properties();
        properties.setProperty(RuntimeConstants.RESOURCE_LOADERS, "string");
        properties.setProperty("resource.loader.string.class", StringResourceLoader.class.getName());
        VelocityEngine engine = new VelocityEngine(properties);
        engine.init();
        return engine;
    }
}
