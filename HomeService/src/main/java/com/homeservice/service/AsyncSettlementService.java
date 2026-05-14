package com.homeservice.service;

import com.homeservice.config.RedisConfig;
import com.homeservice.entity.Booking;
import com.homeservice.entity.Settlement;
import com.homeservice.enums.SettlementStatus;
import com.homeservice.repository.BookingRepository;
import com.homeservice.repository.SettlementRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class AsyncSettlementService {

    @Autowired
    private SettlementRepository settlementRepository;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private StaffService staffService;

    @Autowired
    private AnalyticsService analyticsService;

    @Autowired
    private ServiceHistoryService serviceHistoryService;

    @Autowired
    private RedisSettlementQueueService redisSettlementQueueService;

    @Autowired
    private RedisConfig redisConfig;

    private final Map<String, SettlementTask> settlementTasks = new ConcurrentHashMap<>();
    private final AtomicInteger totalTasks = new AtomicInteger(0);
    private final AtomicInteger successTasks = new AtomicInteger(0);
    private final AtomicInteger failedTasks = new AtomicInteger(0);

    private static final double PLATFORM_FEE_RATE = 0.10;
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 1000;

    public static class SettlementTask {
        private final String settlementId;
        private final String bookingId;
        private final String staffId;
        private final double serviceAmount;
        private final double platformFee;
        private final double staffAmount;
        private int retryCount;
        private SettlementStatus status;
        private Instant createdAt;
        private Instant completedAt;
        private String errorMessage;

        public SettlementTask(String settlementId, String bookingId, String staffId, 
                              double serviceAmount, double platformFee, double staffAmount) {
            this.settlementId = settlementId;
            this.bookingId = bookingId;
            this.staffId = staffId;
            this.serviceAmount = serviceAmount;
            this.platformFee = platformFee;
            this.staffAmount = staffAmount;
            this.retryCount = 0;
            this.status = SettlementStatus.PENDING;
            this.createdAt = Instant.now();
        }

        public String getSettlementId() { return settlementId; }
        public String getBookingId() { return bookingId; }
        public String getStaffId() { return staffId; }
        public double getServiceAmount() { return serviceAmount; }
        public double getPlatformFee() { return platformFee; }
        public double getStaffAmount() { return staffAmount; }
        public int getRetryCount() { return retryCount; }
        public void incrementRetryCount() { this.retryCount++; }
        public SettlementStatus getStatus() { return status; }
        public void setStatus(SettlementStatus status) { this.status = status; }
        public Instant getCreatedAt() { return createdAt; }
        public Instant getCompletedAt() { return completedAt; }
        public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
        public String getErrorMessage() { return errorMessage; }
        public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
        public boolean canRetry() { return retryCount < MAX_RETRIES; }
    }

    @Async
    public CompletableFuture<Settlement> processSettlementAsync(String bookingId, String settlementId, Booking booking) {
        if (redisConfig.isEnabled()) {
            return processSettlementWithRedisQueue(bookingId, settlementId, booking);
        }
        return processSettlementInMemory(bookingId, settlementId, booking);
    }

    private CompletableFuture<Settlement> processSettlementWithRedisQueue(String bookingId, String settlementId, Booking booking) {
        boolean submitted = redisSettlementQueueService.submitSettlementTask(settlementId, booking);
        
        if (!submitted) {
            return CompletableFuture.completedFuture(createPendingSettlement(settlementId, booking));
        }

        SettlementTask task = createSettlementTask(settlementId, booking);
        settlementTasks.put(settlementId, task);
        totalTasks.incrementAndGet();

        return CompletableFuture.completedFuture(createPendingSettlement(settlementId, booking));
    }

    private CompletableFuture<Settlement> processSettlementInMemory(String bookingId, String settlementId, Booking booking) {
        SettlementTask task = createSettlementTask(settlementId, booking);
        settlementTasks.put(settlementId, task);
        totalTasks.incrementAndGet();

        return CompletableFuture.supplyAsync(() -> {
            return executeSettlementWithRetry(task, booking);
        });
    }

    private SettlementTask createSettlementTask(String settlementId, Booking booking) {
        double serviceAmount = booking.getBookingAmount();
        double platformFee = calculatePlatformFee(serviceAmount);
        double staffAmount = serviceAmount - platformFee;
        return new SettlementTask(settlementId, booking.getBookingId(), booking.getStaffId(),
                                   serviceAmount, platformFee, staffAmount);
    }

    private Settlement createPendingSettlement(String settlementId, Booking booking) {
        double serviceAmount = booking.getBookingAmount();
        double platformFee = calculatePlatformFee(serviceAmount);
        double staffAmount = serviceAmount - platformFee;
        
        Settlement settlement = new Settlement(
            settlementId,
            booking.getBookingId(),
            booking.getStaffId(),
            serviceAmount,
            platformFee,
            staffAmount
        );
        settlement.setSettlementStatus(SettlementStatus.PENDING);
        settlement.setSettlementTime(Instant.now());
        return settlementRepository.save(settlement);
    }

    private Settlement executeSettlementWithRetry(SettlementTask task, Booking booking) {
        while (task.canRetry()) {
            try {
                return executeSettlement(task, booking);
            } catch (Exception e) {
                task.incrementRetryCount();
                task.setErrorMessage(e.getMessage());

                if (!task.canRetry()) {
                    task.setStatus(SettlementStatus.FAILED);
                    task.setCompletedAt(Instant.now());
                    failedTasks.incrementAndGet();
                    return createFailedSettlement(task);
                }

                try {
                    Thread.sleep(RETRY_DELAY_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Settlement task interrupted", ie);
                }
            }
        }
        throw new RuntimeException("Max retries exceeded");
    }

    private Settlement executeSettlement(SettlementTask task, Booking booking) {
        boolean paymentSuccess = processPayment(task.getServiceAmount());

        if (!paymentSuccess) {
            throw new RuntimeException("Payment processing failed");
        }

        Settlement settlement = new Settlement(
            task.getSettlementId(),
            task.getBookingId(),
            task.getStaffId(),
            task.getServiceAmount(),
            task.getPlatformFee(),
            task.getStaffAmount()
        );
        settlement.setSettlementStatus(SettlementStatus.PAID);
        settlement.setSettlementTime(Instant.now());
        settlementRepository.save(settlement);

        booking.setIsSettled(true);
        bookingRepository.save(booking);

        staffService.addStaffIncome(task.getStaffId(), task.getStaffAmount());
        analyticsService.addToTotalRevenue(task.getServiceAmount());
        serviceHistoryService.recordSettlementHistory(
            "PROCESS",
            "Settlement processed successfully. Amount: " + task.getServiceAmount(),
            task.getBookingId(),
            task.getStaffId(),
            booking.getCustomerId()
        );

        task.setStatus(SettlementStatus.PAID);
        task.setCompletedAt(Instant.now());
        successTasks.incrementAndGet();

        return settlement;
    }

    private Settlement createFailedSettlement(SettlementTask task) {
        Settlement settlement = new Settlement(
            task.getSettlementId(),
            task.getBookingId(),
            task.getStaffId(),
            task.getServiceAmount(),
            task.getPlatformFee(),
            task.getStaffAmount()
        );
        settlement.setSettlementStatus(SettlementStatus.FAILED);
        return settlementRepository.save(settlement);
    }

    private double calculatePlatformFee(double serviceAmount) {
        return serviceAmount * PLATFORM_FEE_RATE;
    }

    private boolean processPayment(double amount) {
        return true;
    }

    public SettlementTask getSettlementTask(String settlementId) {
        return settlementTasks.get(settlementId);
    }

    public int getTotalTasks() {
        return totalTasks.get();
    }

    public int getSuccessTasks() {
        return successTasks.get();
    }

    public int getFailedTasks() {
        return failedTasks.get();
    }

    public int getPendingQueueTasks() {
        return redisSettlementQueueService.getPendingTaskCount();
    }

    public int getFailedQueueTasks() {
        return redisSettlementQueueService.getFailedTaskCount();
    }

    public void resetCounters() {
        totalTasks.set(0);
        successTasks.set(0);
        failedTasks.set(0);
        settlementTasks.clear();
    }

    public double getPlatformFeeRate() {
        return PLATFORM_FEE_RATE;
    }

    public int getMaxRetries() {
        return MAX_RETRIES;
    }

    public boolean isRedisEnabled() {
        return redisConfig.isEnabled();
    }
}
