package com.loganalytics.common.util;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public final class IdUtils {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final AtomicInteger COUNTER = new AtomicInteger(RANDOM.nextInt(0xFFFF));

    private IdUtils() {}

    public static String newId() {
        return UUID.randomUUID().toString();
    }

    public static String newShortId() {
        UUID uuid = UUID.randomUUID();
        ByteBuffer bb = ByteBuffer.wrap(new byte[16]);
        bb.putLong(uuid.getMostSignificantBits());
        bb.putLong(uuid.getLeastSignificantBits());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bb.array());
    }

    public static String newPatternId() {
        long timestamp = System.currentTimeMillis();
        int counter = COUNTER.getAndIncrement() & 0xFFFF;
        int random = RANDOM.nextInt(0xFFFFFF);
        return String.format("P-%013d-%04x-%06x", timestamp, counter, random);
    }

    public static String newAnomalyId() {
        return "A-" + newShortId();
    }

    public static String newAlertId() {
        return "ALT-" + newShortId();
    }

    public static String newMetricId() {
        return "M-" + newShortId();
    }

    public static String generateHash(String... inputs) {
        StringBuilder sb = new StringBuilder();
        for (String input : inputs) {
            sb.append(input == null ? "" : input);
            sb.append('|');
        }
        return Integer.toHexString(sb.toString().hashCode());
    }
}
