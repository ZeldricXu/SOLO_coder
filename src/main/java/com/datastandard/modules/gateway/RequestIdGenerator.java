package com.datastandard.modules.gateway;

import cn.hutool.core.lang.Snowflake;
import cn.hutool.core.util.IdUtil;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class RequestIdGenerator {

    private static final int TRACE_ID_LENGTH = 16;
    private static final int SPAN_ID_LENGTH = 8;
    private static final String HEX_CHARS = "0123456789abcdef";

    private final Snowflake snowflake = IdUtil.getSnowflake(1, 1);
    private final AtomicLong spanCounter = new AtomicLong(0);
    private final SecureRandom secureRandom = new SecureRandom();

    public String generateRequestId() {
        return "req-" + snowflake.nextIdStr();
    }

    public String generateTraceId() {
        return generateRandomHex(TRACE_ID_LENGTH);
    }

    public String generateSpanId() {
        return generateRandomHex(SPAN_ID_LENGTH);
    }

    public String generateNextSpanId(String parentSpanId) {
        if (parentSpanId == null) {
            return generateSpanId();
        }
        long nextVal = spanCounter.incrementAndGet() & 0xFFFFFFFFL;
        return parentSpanId + "." + Long.toHexString(nextVal);
    }

    public String generateCompactId() {
        byte[] bytes = new byte[12];
        secureRandom.nextBytes(bytes);
        long timestamp = Instant.now().toEpochMilli();
        for (int i = 0; i < 8; i++) {
            bytes[i] = (byte) ((timestamp >> (8 * (7 - i))) & 0xFF);
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String generateRandomHex(int length) {
        StringBuilder sb = new StringBuilder(length);
        byte[] randomBytes = new byte[length];
        secureRandom.nextBytes(randomBytes);
        for (int i = 0; i < length; i++) {
            sb.append(HEX_CHARS.charAt((randomBytes[i] & 0xFF) % 16));
        }
        return sb.toString();
    }
}
