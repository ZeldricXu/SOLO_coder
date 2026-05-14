package com.library.librarymgmt.util;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public class IdGenerator {
    private static final AtomicInteger counter = new AtomicInteger(0);

    public static String generateId(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().substring(0, 8);
    }

    public static String generateBookId() {
        return generateId("book");
    }

    public static String generateReaderId() {
        return generateId("reader");
    }

    public static String generateBorrowId() {
        return generateId("borrow");
    }

    public static String generateReturnId() {
        return generateId("return");
    }

    public static String generateReserveId() {
        return generateId("reserve");
    }

    public static String generateReviewId() {
        return generateId("review");
    }

    public static String generateStatId() {
        return generateId("stat");
    }
}
