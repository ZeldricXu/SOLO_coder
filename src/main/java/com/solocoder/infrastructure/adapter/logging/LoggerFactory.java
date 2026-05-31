package com.solocoder.infrastructure.adapter.logging;

import com.solocoder.domain.port.StructuredLoggerPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LoggerFactory {

    private final StructuredLoggerPort structuredLoggerPort;

    public StructuredLoggerPort getLogger() {
        return structuredLoggerPort;
    }

    public StructuredLoggerPort getLogger(Class<?> clazz) {
        return structuredLoggerPort;
    }

    public StructuredLoggerPort getLogger(String name) {
        return structuredLoggerPort;
    }
}
