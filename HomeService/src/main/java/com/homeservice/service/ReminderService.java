package com.homeservice.service;

import com.homeservice.config.CustomerLevelConfig;
import com.homeservice.config.CustomerLevelConfig.ReminderConfig;
import com.homeservice.entity.Booking;
import com.homeservice.entity.Customer;
import com.homeservice.repository.CustomerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class ReminderService {

    private static final Logger logger = LoggerFactory.getLogger(ReminderService.class);

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private CustomerLevelConfig customerLevelConfig;

    private final ConcurrentHashMap<String, ReminderRecord> reminderRecords = new ConcurrentHashMap<>();
    private final AtomicInteger reminderCounter = new AtomicInteger(0);

    public enum CustomerActivityLevel {
        ACTIVE(24),
        INACTIVE(6);

        private final int reminderIntervalHours;

        CustomerActivityLevel(int reminderIntervalHours) {
            this.reminderIntervalHours = reminderIntervalHours;
        }

        public int getReminderIntervalHours() {
            return reminderIntervalHours;
        }
    }

    public static class ReminderRecord {
        private final String bookingId;
        private final String customerId;
        private final String staffId;
        private final Instant serviceCompletedAt;
        private final List<Instant> reminderSentTimes;
        private final int maxReminders;
        private boolean isReviewed;
        private String customerLevel;
        private Integer intervalHours;

        public ReminderRecord(String bookingId, String customerId, String staffId, int maxReminders) {
            this.bookingId = bookingId;
            this.customerId = customerId;
            this.staffId = staffId;
            this.serviceCompletedAt = Instant.now();
            this.reminderSentTimes = new ArrayList<>();
            this.maxReminders = maxReminders;
            this.isReviewed = false;
        }

        public String getBookingId() { return bookingId; }
        public String getCustomerId() { return customerId; }
        public String getStaffId() { return staffId; }
        public Instant getServiceCompletedAt() { return serviceCompletedAt; }
        public List<Instant> getReminderSentTimes() { return reminderSentTimes; }
        public int getMaxReminders() { return maxReminders; }
        public boolean isReviewed() { return isReviewed; }
        public void setReviewed(boolean reviewed) { isReviewed = reviewed; }
        public int getReminderCount() { return reminderSentTimes.size(); }
        public String getCustomerLevel() { return customerLevel; }
        public void setCustomerLevel(String customerLevel) { this.customerLevel = customerLevel; }
        public Integer getIntervalHours() { return intervalHours; }
        public void setIntervalHours(Integer intervalHours) { this.intervalHours = intervalHours; }
    }

    public ReminderRecord createReminderRecord(Booking booking) {
        String recordKey = "reminder:" + booking.getBookingId();
        ReminderConfig config = getReminderConfigForCustomer(booking.getCustomerId());
        
        ReminderRecord record = new ReminderRecord(
            booking.getBookingId(),
            booking.getCustomerId(),
            booking.getStaffId(),
            config.getMaxReminders()
        );
        record.setCustomerLevel(getCustomerLevelCode(booking.getCustomerId()));
        record.setIntervalHours(config.getIntervalHours());
        
        reminderRecords.put(recordKey, record);
        logger.info("Reminder record created for booking {} (customer: {}, level: {}, interval: {}h, max: {})",
            booking.getBookingId(), booking.getCustomerId(), 
            record.getCustomerLevel(), record.getIntervalHours(), record.getMaxReminders());
        
        return record;
    }

    public CustomerActivityLevel determineCustomerActivityLevel(String customerId) {
        ReminderConfig config = getReminderConfigForCustomer(customerId);
        int threshold = config.getActivityThreshold();
        
        try {
            return customerRepository.findByCustomerId(customerId)
                .map(customer -> {
                    if (customer.getTotalBookings() >= threshold) {
                        return CustomerActivityLevel.ACTIVE;
                    }
                    return CustomerActivityLevel.INACTIVE;
                })
                .orElse(CustomerActivityLevel.INACTIVE);
        } catch (Exception e) {
            logger.warn("Error determining activity level for customer {}: {}", customerId, e.getMessage());
            return CustomerActivityLevel.INACTIVE;
        }
    }

    public int calculateReminderInterval(String customerId) {
        CustomerActivityLevel activityLevel = determineCustomerActivityLevel(customerId);
        ReminderConfig config = getReminderConfigForCustomer(customerId);
        
        if (activityLevel == CustomerActivityLevel.ACTIVE) {
            return config.getIntervalHours();
        } else {
            return config.getIntervalHours() / 4;
        }
    }

    public boolean shouldSendReminder(String bookingId) {
        ReminderRecord record = reminderRecords.get("reminder:" + bookingId);
        if (record == null || record.isReviewed()) {
            return false;
        }

        if (record.getReminderCount() >= record.getMaxReminders()) {
            logger.debug("Max reminders reached for booking {}", bookingId);
            return false;
        }

        if (record.getReminderCount() == 0) {
            return true;
        }

        int intervalHours = calculateReminderInterval(record.getCustomerId());
        Instant lastReminder = record.getReminderSentTimes().get(record.getReminderCount() - 1);
        Instant nextReminderTime = lastReminder.plus(java.time.Duration.ofHours(intervalHours));

        boolean shouldSend = Instant.now().isAfter(nextReminderTime);
        logger.debug("Checking reminder for booking {}: last={}, interval={}h, shouldSend={}",
            bookingId, lastReminder, intervalHours, shouldSend);
        
        return shouldSend;
    }

    public boolean sendReminder(String bookingId) {
        if (!shouldSendReminder(bookingId)) {
            return false;
        }

        ReminderRecord record = reminderRecords.get("reminder:" + bookingId);
        if (record == null) {
            return false;
        }

        record.getReminderSentTimes().add(Instant.now());
        int count = reminderCounter.incrementAndGet();
        
        logger.info("Reminder #{} sent for booking {} (total sent: {})", 
            record.getReminderCount(), bookingId, count);
        
        return true;
    }

    public boolean sendReminderImmediately(String bookingId) {
        ReminderRecord record = reminderRecords.get("reminder:" + bookingId);
        if (record == null || record.isReviewed()) {
            return false;
        }

        if (record.getReminderCount() >= record.getMaxReminders()) {
            return false;
        }

        record.getReminderSentTimes().add(Instant.now());
        reminderCounter.incrementAndGet();
        return true;
    }

    public void markAsReviewed(String bookingId) {
        ReminderRecord record = reminderRecords.get("reminder:" + bookingId);
        if (record != null) {
            record.setReviewed(true);
            logger.info("Booking {} marked as reviewed, stopping reminders", bookingId);
        }
    }

    public ReminderRecord getReminderRecord(String bookingId) {
        return reminderRecords.get("reminder:" + bookingId);
    }

    public int getTotalRemindersSent() {
        return reminderCounter.get();
    }

    public void resetReminderCounter() {
        reminderCounter.set(0);
    }

    public void clearAllReminderRecords() {
        reminderRecords.clear();
        reminderCounter.set(0);
        logger.info("All reminder records cleared");
    }

    private ReminderConfig getReminderConfigForCustomer(String customerId) {
        String levelCode = getCustomerLevelCode(customerId);
        return customerLevelConfig.getReminderByLevel(levelCode);
    }

    private String getCustomerLevelCode(String customerId) {
        try {
            Optional<Customer> customerOpt = customerRepository.findByCustomerId(customerId);
            if (customerOpt.isPresent() && customerOpt.get().getCustomerLevel() != null) {
                return customerOpt.get().getCustomerLevel().getCode();
            }
        } catch (Exception e) {
            logger.warn("Error getting customer level for {}: {}", customerId, e.getMessage());
        }
        return "default";
    }

    public int getMaxRemindersForCustomer(String customerId) {
        ReminderConfig config = getReminderConfigForCustomer(customerId);
        return config.getMaxReminders();
    }

    public int getActivityThresholdForLevel(String levelCode) {
        ReminderConfig config = customerLevelConfig.getReminderByLevel(levelCode);
        return config.getActivityThreshold();
    }
}
