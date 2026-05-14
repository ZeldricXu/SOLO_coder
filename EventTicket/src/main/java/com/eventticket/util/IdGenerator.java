package com.eventticket.util;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

public class IdGenerator {
    
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    
    public static String generateEventId() {
        return "event_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
    
    public static String generateTicketId() {
        return "ticket_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
    
    public static String generateSeatId() {
        return "seat_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
    
    public static String generateParticipantId() {
        return "participant_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
    
    public static String generateVerifyId() {
        return "verify_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
    
    public static String generateChangeId() {
        return "change_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
    
    public static String generateStatId() {
        return "stat_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
    
    public static String generateScheduleId() {
        return "schedule_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
    
    public static String generateHistoryId() {
        return "history_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
    
    public static String generateTicketNumber() {
        String timestamp = LocalDateTime.now().format(DATE_FORMATTER);
        String random = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        return "TK" + timestamp + random;
    }
}
