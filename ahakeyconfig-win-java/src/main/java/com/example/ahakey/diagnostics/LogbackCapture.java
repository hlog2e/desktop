package com.example.ahakey.diagnostics;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.AppenderBase;
import com.example.ahakey.util.LanguageManager;
import org.slf4j.LoggerFactory;
import org.slf4j.helpers.MessageFormatter;

import java.time.Instant;

/** Copies the app's log lines into {@link ConnectionLog}, translating Chinese patterns in Korean. */
final class LogbackCapture extends AppenderBase<ILoggingEvent> {
    private LogbackCapture() {}

    static void install() {
        if (!(LoggerFactory.getILoggerFactory() instanceof LoggerContext context)) return;
        LogbackCapture appender = new LogbackCapture();
        appender.setContext(context);
        appender.setName("CONNECTION_LOG");
        appender.start();
        // com.example.ahakey has additivity=false, so attach to it as well as root.
        for (String name : new String[] {Logger.ROOT_LOGGER_NAME, "com.example.ahakey"}) {
            context.getLogger(name).addAppender(appender);
        }
    }

    @Override
    protected void append(ILoggingEvent event) {
        String pattern = event.getMessage() == null ? "" : event.getMessage();
        String translated = LanguageManager.getInstance().hasLegacyTranslation(pattern)
            ? LanguageManager.localize(pattern) : pattern;
        String message = MessageFormatter.arrayFormat(translated, event.getArgumentArray()).getMessage();
        IThrowableProxy error = event.getThrowableProxy();
        if (error != null) message += " (" + error.getClassName() + ": " + error.getMessage() + ")";
        String logger = event.getLoggerName();
        String shortName = logger.substring(logger.lastIndexOf('.') + 1);
        ConnectionLog.Level level = switch (event.getLevel().toInt()) {
            case ch.qos.logback.classic.Level.ERROR_INT -> ConnectionLog.Level.ERROR;
            case ch.qos.logback.classic.Level.WARN_INT -> ConnectionLog.Level.WARN;
            case ch.qos.logback.classic.Level.INFO_INT -> ConnectionLog.Level.INFO;
            default -> ConnectionLog.Level.DEBUG;
        };
        ConnectionLog.get().add(new ConnectionLog.Entry(Instant.ofEpochMilli(event.getTimeStamp()),
            ConnectionLog.Source.APP, level, shortName, message, null));
    }
}
