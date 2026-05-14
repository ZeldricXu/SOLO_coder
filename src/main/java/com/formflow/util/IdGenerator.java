package com.formflow.util;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicLong;

public class IdGenerator {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final AtomicLong COUNTER = new AtomicLong(0);

    private IdGenerator() {
    }

    public static String generateFormId() {
        return "form_" + generateTimestamp() + generateSequence();
    }

    public static String generateInstanceId() {
        return "instance_" + generateTimestamp() + generateSequence();
    }

    public static String generateTaskId() {
        return "task_" + generateTimestamp() + generateSequence();
    }

    public static String generateApprovalId() {
        return "approval_" + generateTimestamp() + generateSequence();
    }

    public static String generateTemplateId(String prefix) {
        return "template_" + (prefix != null ? prefix + "_" : "") + generateTimestamp() + generateSequence();
    }

    public static String generateProcessId(String prefix) {
        return "process_" + (prefix != null ? prefix + "_" : "") + generateTimestamp() + generateSequence();
    }

    private static String generateTimestamp() {
        return LocalDateTime.now().format(DATE_FORMATTER);
    }

    private static String generateSequence() {
        long seq = COUNTER.incrementAndGet() % 10000;
        return String.format("%04d", seq);
    }
}
