package com.reviewsystem.util;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public class IdGenerator {

    private static final AtomicInteger COUNTER = new AtomicInteger(0);
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private IdGenerator() {}

    public static String generateCommentId() {
        return "comment_" + generateUniqueSuffix();
    }

    public static String generateAuditId() {
        return "audit_" + generateUniqueSuffix();
    }

    public static String generateSentimentId() {
        return "sentiment_" + generateUniqueSuffix();
    }

    public static String generateRecommendId() {
        return "recommend_" + generateUniqueSuffix();
    }

    public static String generateReportId() {
        return "report_" + generateUniqueSuffix();
    }

    public static String generateStatId() {
        return "stat_" + generateUniqueSuffix();
    }

    public static String generateReplyId() {
        return "reply_" + generateUniqueSuffix();
    }

    public static String generateHistoryId() {
        return "history_" + generateUniqueSuffix();
    }

    public static String generateEvaluationId() {
        return "evaluation_" + generateUniqueSuffix();
    }

    private static String generateUniqueSuffix() {
        String timestamp = LocalDateTime.now().format(FORMATTER);
        int count = COUNTER.incrementAndGet() % 10000;
        return timestamp + String.format("%04d", count);
    }

    public static String generateUUID() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
