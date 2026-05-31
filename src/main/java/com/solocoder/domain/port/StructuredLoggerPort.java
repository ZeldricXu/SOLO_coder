package com.solocoder.domain.port;

import java.util.Map;

public interface StructuredLoggerPort {

    void info(String message);

    void info(String message, Map<String, Object> context);

    void warn(String message);

    void warn(String message, Map<String, Object> context);

    void error(String message);

    void error(String message, Throwable throwable);

    void error(String message, Map<String, Object> context);

    void error(String message, Throwable throwable, Map<String, Object> context);

    void debug(String message);

    void debug(String message, Map<String, Object> context);
}
