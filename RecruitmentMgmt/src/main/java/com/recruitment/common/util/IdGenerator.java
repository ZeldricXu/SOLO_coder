package com.recruitment.common.util;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

public class IdGenerator {
    private static final AtomicLong positionCounter = new AtomicLong(1);
    private static final AtomicLong resumeCounter = new AtomicLong(1);
    private static final AtomicLong candidateCounter = new AtomicLong(1);
    private static final AtomicLong interviewCounter = new AtomicLong(1);
    private static final AtomicLong interviewerCounter = new AtomicLong(1);
    private static final AtomicLong hireCounter = new AtomicLong(1);
    private static final AtomicLong statCounter = new AtomicLong(1);
    private static final AtomicLong workflowCounter = new AtomicLong(1);
    private static final AtomicLong historyCounter = new AtomicLong(1);

    public static String generatePositionId() {
        return String.format("position_%03d", positionCounter.getAndIncrement());
    }

    public static String generateResumeId() {
        return String.format("resume_%03d", resumeCounter.getAndIncrement());
    }

    public static String generateCandidateId() {
        return String.format("candidate_%03d", candidateCounter.getAndIncrement());
    }

    public static String generateInterviewId() {
        return String.format("interview_%03d", interviewCounter.getAndIncrement());
    }

    public static String generateInterviewerId() {
        return String.format("interviewer_%03d", interviewerCounter.getAndIncrement());
    }

    public static String generateHireId() {
        return String.format("hire_%03d", hireCounter.getAndIncrement());
    }

    public static String generateStatId() {
        return String.format("stat_%03d", statCounter.getAndIncrement());
    }

    public static String generateWorkflowId() {
        return String.format("workflow_%03d", workflowCounter.getAndIncrement());
    }

    public static String generateHistoryId() {
        return String.format("history_%03d", historyCounter.getAndIncrement());
    }

    public static String getCurrentTimestamp() {
        return Instant.now().toString();
    }
}
