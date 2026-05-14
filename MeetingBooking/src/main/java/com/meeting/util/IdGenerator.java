package com.meeting.util;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicLong;

public class IdGenerator {
    private static final AtomicLong counter = new AtomicLong(0);
    private static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private IdGenerator() {
    }

    public static String generateId(String prefix) {
        String timestamp = LocalDateTime.now().format(formatter);
        long sequence = counter.incrementAndGet() % 10000;
        return String.format("%s_%s%04d", prefix, timestamp, sequence);
    }

    public static String generateMeetingId() {
        return generateId("meeting");
    }

    public static String generateRoomId() {
        return generateId("room");
    }

    public static String generateScheduleId() {
        return generateId("schedule");
    }

    public static String generateAttendeeId() {
        return generateId("attendee");
    }

    public static String generateDeviceId() {
        return generateId("device");
    }

    public static String generateReminderId() {
        return generateId("reminder");
    }

    public static String generateStatId() {
        return generateId("stat");
    }

    public static String generateHistoryId() {
        return generateId("history");
    }

    public static String generateTypeId() {
        return generateId("type");
    }
}
