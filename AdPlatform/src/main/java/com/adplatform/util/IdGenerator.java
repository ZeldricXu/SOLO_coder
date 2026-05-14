package com.adplatform.util;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

public class IdGenerator {
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final AtomicLong COUNTER = new AtomicLong(0);

    public static String generateId(String prefix) {
        String timestamp = LocalDateTime.now().format(DATE_FORMATTER);
        long sequence = COUNTER.incrementAndGet() % 10000;
        return String.format("%s_%s%04d", prefix, timestamp, sequence);
    }

    public static String generateUUID() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
