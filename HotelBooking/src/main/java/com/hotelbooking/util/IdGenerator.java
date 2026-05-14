package com.hotelbooking.util;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

public class IdGenerator {
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final AtomicLong COUNTER = new AtomicLong(0);

    public static String generateHotelId() {
        return "hotel_" + UUID.randomUUID().toString().substring(0, 8);
    }

    public static String generateRoomId() {
        return "room_" + UUID.randomUUID().toString().substring(0, 8);
    }

    public static String generateBookingId() {
        String timestamp = LocalDateTime.now().format(DATE_FORMATTER);
        long seq = COUNTER.incrementAndGet() % 10000;
        return "booking_" + timestamp + String.format("%04d", seq);
    }

    public static String generateCheckInId() {
        String timestamp = LocalDateTime.now().format(DATE_FORMATTER);
        long seq = COUNTER.incrementAndGet() % 10000;
        return "checkin_" + timestamp + String.format("%04d", seq);
    }

    public static String generateServiceId() {
        String timestamp = LocalDateTime.now().format(DATE_FORMATTER);
        long seq = COUNTER.incrementAndGet() % 10000;
        return "service_" + timestamp + String.format("%04d", seq);
    }

    public static String generateSettlementId() {
        String timestamp = LocalDateTime.now().format(DATE_FORMATTER);
        long seq = COUNTER.incrementAndGet() % 10000;
        return "settlement_" + timestamp + String.format("%04d", seq);
    }

    public static String generateStatId() {
        return "stat_" + UUID.randomUUID().toString().substring(0, 8);
    }

    public static String generateReviewId() {
        return "review_" + UUID.randomUUID().toString().substring(0, 8);
    }
}
