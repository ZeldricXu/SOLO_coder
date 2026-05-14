package com.survey.util;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

public class IdGenerator {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    public static String generateSurveyId() {
        return "survey_" + generateShortId();
    }

    public static String generateQuestionId() {
        return "q_" + generateShortId();
    }

    public static String generatePublishId() {
        return "publish_" + generateShortId();
    }

    public static String generateAnswerId() {
        return "answer_" + generateShortId();
    }

    public static String generateStatId() {
        return "stat_" + generateShortId();
    }

    public static String generateReviewId() {
        return "review_" + generateShortId();
    }

    public static String generateTemplateId() {
        return "template_" + generateShortId();
    }

    public static String generateReportId() {
        return "report_" + generateShortId();
    }

    public static String generateReminderId() {
        return "reminder_" + generateShortId();
    }

    private static String generateShortId() {
        String uuid = UUID.randomUUID().toString().replace("-", "");
        String timestamp = LocalDateTime.now().format(DATE_FORMATTER);
        return timestamp + uuid.substring(0, 6);
    }
}
