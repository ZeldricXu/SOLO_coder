package com.schedulebook.service;

import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class IdGeneratorService {
    
    private static final AtomicLong bookingCounter = new AtomicLong(0);
    private static final AtomicLong scheduleCounter = new AtomicLong(0);
    private static final AtomicLong dispatchCounter = new AtomicLong(0);
    private static final AtomicLong reminderCounter = new AtomicLong(0);
    private static final AtomicLong cancelCounter = new AtomicLong(0);
    private static final AtomicLong statCounter = new AtomicLong(0);
    private static final AtomicLong historyCounter = new AtomicLong(0);
    private static final AtomicLong resourceCounter = new AtomicLong(0);
    
    private String getTimestampPrefix() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyMMddHHmmss"));
    }
    
    public String generateBookingId() {
        return "booking_" + getTimestampPrefix() + "_" + bookingCounter.incrementAndGet();
    }
    
    public String generateScheduleId() {
        return "schedule_" + getTimestampPrefix() + "_" + scheduleCounter.incrementAndGet();
    }
    
    public String generateDispatchId() {
        return "dispatch_" + getTimestampPrefix() + "_" + dispatchCounter.incrementAndGet();
    }
    
    public String generateReminderId() {
        return "reminder_" + getTimestampPrefix() + "_" + reminderCounter.incrementAndGet();
    }
    
    public String generateCancelId() {
        return "cancel_" + getTimestampPrefix() + "_" + cancelCounter.incrementAndGet();
    }
    
    public String generateStatId() {
        return "stat_" + getTimestampPrefix() + "_" + statCounter.incrementAndGet();
    }
    
    public String generateHistoryId() {
        return "history_" + getTimestampPrefix() + "_" + historyCounter.incrementAndGet();
    }
    
    public String generateResourceId(String resourceType) {
        return resourceType + "_" + getTimestampPrefix() + "_" + resourceCounter.incrementAndGet();
    }
}
