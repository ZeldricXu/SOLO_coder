package com.monitoring.common.utils;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

public class IdGenerator {

    private static final SecureRandom RANDOM = new SecureRandom();

    private IdGenerator() {
    }

    public static String generateId(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    public static String generateTraceId() {
        byte[] randomBytes = new byte[12];
        RANDOM.nextBytes(randomBytes);
        long timestamp = Instant.now().toEpochMilli();
        return Long.toHexString(timestamp) + bytesToHex(randomBytes);
    }

    public static String generateShortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    public static String generateBase64Id() {
        byte[] randomBytes = new byte[16];
        RANDOM.nextBytes(randomBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }
}
