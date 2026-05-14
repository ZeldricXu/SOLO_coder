package com.invoice.mgmt.common.util;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

public class IdGenerator {
    private static final AtomicLong COUNTER = new AtomicLong(0);

    public static String generateInvoiceId() {
        return "invoice_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    public static String generateVerifyId() {
        return "verify_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    public static String generateReimburseId() {
        return "reimburse_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    public static String generateArchiveId() {
        return "archive_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    public static String generateStatId() {
        return "stat_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    public static String generateTypeId() {
        return "type_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    public static String generate(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    public static long generateLongId() {
        return Instant.now().toEpochMilli() * 1000 + COUNTER.getAndIncrement() % 1000;
    }
}
