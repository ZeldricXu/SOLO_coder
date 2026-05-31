package com.parking.platform.common.util;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

public class IdGenerator {

    private static final AtomicLong SEQUENCE = new AtomicLong(0);
    private static final long EPOCH = 1704067200000L;

    public static String generate(String prefix) {
        long timestamp = Instant.now().toEpochMilli() - EPOCH;
        long sequence = SEQUENCE.incrementAndGet() % 10000;
        String random = UUID.randomUUID().toString().replace("-", "").substring(0, 6);
        return String.format("%s_%d%d%s", prefix, timestamp, sequence, random);
    }

    public static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    public static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    public static String requestId() {
        return "req_" + shortId();
    }

    public static String traceId() {
        return "trace_" + uuid().substring(0, 16);
    }
}
