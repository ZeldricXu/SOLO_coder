package com.solocoder.infrastructure.adapter.logging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.solocoder.domain.port.StructuredLoggerPort;
import com.solocoder.infrastructure.adapter.logging.plugin.LogPluginManager;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class Slf4jStructuredLogger implements StructuredLoggerPort {

    private final Logger logger = LoggerFactory.getLogger(Slf4jStructuredLogger.class);
    private final ObjectMapper objectMapper;
    private final LogPluginManager pluginManager;

    @Override
    public void info(String message) {
        logInternal("INFO", message, null, null);
    }

    @Override
    public void info(String message, Map<String, Object> context) {
        logInternal("INFO", message, context, null);
    }

    @Override
    public void warn(String message) {
        logInternal("WARN", message, null, null);
    }

    @Override
    public void warn(String message, Map<String, Object> context) {
        logInternal("WARN", message, context, null);
    }

    @Override
    public void error(String message) {
        logInternal("ERROR", message, null, null);
    }

    @Override
    public void error(String message, Throwable throwable) {
        logInternal("ERROR", message, null, throwable);
    }

    @Override
    public void error(String message, Map<String, Object> context) {
        logInternal("ERROR", message, context, null);
    }

    @Override
    public void error(String message, Throwable throwable, Map<String, Object> context) {
        logInternal("ERROR", message, context, throwable);
    }

    @Override
    public void debug(String message) {
        logInternal("DEBUG", message, null, null);
    }

    @Override
    public void debug(String message, Map<String, Object> context) {
        logInternal("DEBUG", message, context, null);
    }

    private void logInternal(String level, String message, Map<String, Object> context, Throwable throwable) {
        StructuredLogEvent event = buildEvent(level, message, context, throwable);

        event = pluginManager.applyBeforeLog(event);

        String structuredMessage = formatAsJson(event);
        populateMdc(event.getContext());

        try {
            switch (event.getLevel()) {
                case "INFO" -> logger.info(structuredMessage, throwable);
                case "WARN" -> logger.warn(structuredMessage, throwable);
                case "ERROR" -> logger.error(structuredMessage, throwable);
                case "DEBUG" -> logger.debug(structuredMessage, throwable);
                default -> logger.info(structuredMessage, throwable);
            }
        } finally {
            clearMdc();
            pluginManager.applyAfterLog(event);
        }
    }

    private StructuredLogEvent buildEvent(String level, String message, Map<String, Object> context,
                                           Throwable throwable) {
        return StructuredLogEvent.builder()
                .timestamp(Instant.now())
                .level(level)
                .message(message)
                .loggerName(logger.getName())
                .threadName(Thread.currentThread().getName())
                .context(context != null ? new HashMap<>(context) : new HashMap<>())
                .stackTrace(throwable != null ? getStackTrace(throwable) : null)
                .build();
    }

    private String formatAsJson(StructuredLogEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            return buildFallbackMessage(event.getLevel(), event.getMessage(), event.getContext(), null);
        }
    }

    private String formatAsJson(String level, String message, Map<String, Object> context, Throwable throwable) {
        try {
            StructuredLogEvent event = StructuredLogEvent.builder()
                    .level(level)
                    .message(message)
                    .loggerName(logger.getName())
                    .threadName(Thread.currentThread().getName())
                    .context(context != null ? context : new HashMap<>())
                    .stackTrace(throwable != null ? getStackTrace(throwable) : null)
                    .build();
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            return buildFallbackMessage(level, message, context, throwable);
        }
    }

    private String getStackTrace(Throwable throwable) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        throwable.printStackTrace(pw);
        return sw.toString();
    }

    private String buildFallbackMessage(String level, String message, Map<String, Object> context, Throwable throwable) {
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(level).append("] ").append(message);
        if (context != null && !context.isEmpty()) {
            sb.append(" context=").append(context);
        }
        if (throwable != null) {
            sb.append(" error=").append(throwable.getMessage());
        }
        return sb.toString();
    }

    private void populateMdc(Map<String, Object> context) {
        if (context != null) {
            context.forEach((key, value) -> {
                if (value != null) {
                    MDC.put(key, value.toString());
                }
            });
        }
    }

    private void clearMdc() {
        MDC.clear();
    }
}
