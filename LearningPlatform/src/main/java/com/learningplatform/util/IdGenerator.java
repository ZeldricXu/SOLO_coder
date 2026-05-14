
package com.learningplatform.util;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

public class IdGenerator {

    private static final AtomicLong sequence = new AtomicLong(0);

    public static String generateId(String prefix) {
        String uuid = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        return prefix + "_" + uuid;
    }

    public static String generateCourseId() {
        return generateId("course");
    }

    public static String generateChapterId() {
        return generateId("chapter");
    }

    public static String generateProgressId() {
        return generateId("progress");
    }

    public static String generateStudentId() {
        return generateId("student");
    }

    public static String generateCertificateId() {
        return generateId("cert");
    }

    public static String generateReviewId() {
        return generateId("review");
    }

    public static String generateResourceId() {
        return generateId("resource");
    }

    public static String generateStatId() {
        return generateId("stat");
    }

    public static String generateEnrollmentId() {
        return generateId("enroll");
    }

    public static String generateChapterProgressId() {
        return generateId("chprog");
    }

    public static String generateHistoryId() {
        return generateId("history");
    }

    public static String generateCertificateNumber() {
        String date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        long seq = sequence.incrementAndGet();
        return String.format("CERT%s%03d", date, seq);
    }
}
