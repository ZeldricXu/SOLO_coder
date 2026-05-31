package com.solocoder.infrastructure.adapter.logging.plugin;

import com.solocoder.infrastructure.adapter.logging.StructuredLogEvent;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Component
public class TraceIdLogPlugin implements LogPlugin {

    private static final String TRACE_ID_KEY = "traceId";

    @Override
    public StructuredLogEvent transform(StructuredLogEvent event) {
        String traceId = MDC.get(TRACE_ID_KEY);
        if (traceId == null || traceId.isEmpty()) {
            traceId = UUID.randomUUID().toString();
            MDC.put(TRACE_ID_KEY, traceId);
        }

        Map<String, Object> context = new HashMap<>(event.getContext());
        context.put(TRACE_ID_KEY, traceId);

        return StructuredLogEvent.builder()
                .timestamp(event.getTimestamp())
                .level(event.getLevel())
                .message(event.getMessage())
                .loggerName(event.getLoggerName())
                .threadName(event.getThreadName())
                .context(context)
                .stackTrace(event.getStackTrace())
                .build();
    }

    @Override
    public int getOrder() {
        return -100;
    }
}
