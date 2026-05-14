package com.movie.util;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public class IdGenerator {

    private static final AtomicInteger MOVIE_COUNTER = new AtomicInteger(1);
    private static final AtomicInteger CINEMA_COUNTER = new AtomicInteger(1);
    private static final AtomicInteger SCHEDULE_COUNTER = new AtomicInteger(1);
    private static final AtomicInteger SEAT_COUNTER = new AtomicInteger(1);
    private static final AtomicInteger TICKET_COUNTER = new AtomicInteger(1);
    private static final AtomicInteger USER_COUNTER = new AtomicInteger(1);
    private static final AtomicInteger STAT_COUNTER = new AtomicInteger(1);

    public static String generateMovieId() {
        return String.format("movie_%03d", MOVIE_COUNTER.getAndIncrement());
    }

    public static String generateCinemaId() {
        return String.format("cinema_%03d", CINEMA_COUNTER.getAndIncrement());
    }

    public static String generateScheduleId() {
        return String.format("schedule_%03d", SCHEDULE_COUNTER.getAndIncrement());
    }

    public static String generateSeatId() {
        return String.format("seat_%03d", SEAT_COUNTER.getAndIncrement());
    }

    public static String generateTicketId() {
        return String.format("ticket_%03d", TICKET_COUNTER.getAndIncrement());
    }

    public static String generateUserId() {
        return String.format("user_%03d", USER_COUNTER.getAndIncrement());
    }

    public static String generateStatId() {
        return String.format("stat_%03d", STAT_COUNTER.getAndIncrement());
    }

    public static String generateUUID() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    public static String getTimestamp() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
    }
}
