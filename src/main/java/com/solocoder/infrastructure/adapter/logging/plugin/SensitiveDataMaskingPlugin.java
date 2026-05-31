package com.solocoder.infrastructure.adapter.logging.plugin;

import com.solocoder.infrastructure.adapter.logging.StructuredLogEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class SensitiveDataMaskingPlugin implements LogPlugin {

    private static final String MASK = "******";
    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "password", "secret", "token", "apikey", "api_key",
            "creditcard", "credit_card", "ssn", "phone", "email"
    );

    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+$");
    private static final Pattern PHONE_PATTERN = Pattern.compile(
            "^1[3-9]\\d{9}$");

    @Value("${logging.masking.enabled:true}")
    private boolean maskingEnabled;

    @Override
    public StructuredLogEvent transform(StructuredLogEvent event) {
        Map<String, Object> maskedContext = new HashMap<>();
        for (Map.Entry<String, Object> entry : event.getContext().entrySet()) {
            String key = entry.getKey().toLowerCase();
            Object value = entry.getValue();

            if (SENSITIVE_KEYS.contains(key)) {
                maskedContext.put(entry.getKey(), MASK);
            } else if (value instanceof String strValue) {
                maskedContext.put(entry.getKey(), maskStringValue(strValue));
            } else {
                maskedContext.put(entry.getKey(), value);
            }
        }

        String maskedMessage = maskStringValue(event.getMessage());

        return StructuredLogEvent.builder()
                .timestamp(event.getTimestamp())
                .level(event.getLevel())
                .message(maskedMessage)
                .loggerName(event.getLoggerName())
                .threadName(event.getThreadName())
                .context(maskedContext)
                .stackTrace(event.getStackTrace())
                .build();
    }

    private String maskStringValue(String value) {
        if (EMAIL_PATTERN.matcher(value).matches()) {
            int atIndex = value.indexOf('@');
            return MASK + value.substring(atIndex);
        }
        if (PHONE_PATTERN.matcher(value).matches()) {
            return value.substring(0, 3) + MASK + value.substring(8);
        }
        return value;
    }

    @Override
    public boolean isEnabled() {
        return maskingEnabled;
    }

    @Override
    public int getOrder() {
        return -50;
    }
}
