package com.travelbooking.service;

import com.travelbooking.config.SettlementConfig;
import com.travelbooking.dto.SettlementTaskDTO;
import com.travelbooking.exception.BusinessException;
import com.travelbooking.model.Booking;
import com.travelbooking.model.Settlement;
import com.travelbooking.repository.SettlementRepository;
import com.travelbooking.util.IdGenerator;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@RequiredArgsConstructor
@Slf4j
public class AsyncSettlementService {

    private final SettlementRepository settlementRepository;
    private final BookingService bookingService;
    private final AnalyticsService analyticsService;
    private final HistoryService historyService;
    private final SettlementService settlementService;
    private final SettlementConfig settlementConfig;
    private final RedisSettlementQueueService redisQueueService;

    public static final int MAX_RETRY_ATTEMPTS = 3;
    public static final long RETRY_DELAY_MS = 1000;

    private final ExecutorService executorService = Executors.newFixedThreadPool(4);

    @Getter
    private final Map<String, SettlementTaskResult> taskResults = new ConcurrentHashMap<>();

    public static class SettlementTaskResult {
        private final AtomicInteger attemptCount = new AtomicInteger(0);
        private volatile boolean completed = false;
        private volatile boolean success = false;
        private volatile String errorMessage;
        private volatile Settlement settlement;

        public int getAttemptCount() {
            return attemptCount.get();
        }

        public boolean isCompleted() {
            return completed;
        }

        public boolean isSuccess() {
            return success;
        }

        public String getErrorMessage() {
            return errorMessage;
        }

        public Settlement getSettlement() {
            return settlement;
        }
    }

    public static class SettlementResponse {
        private final String taskId;
        private final String bookingId;
        private final String status;
        private final boolean persisted;

        public SettlementResponse(String taskId, String bookingId, String status, boolean persisted) {
            this.taskId = taskId;
            this.bookingId = bookingId;
            this.status = status;
            this.persisted = persisted;
        }

        public String getTaskId() { return taskId; }
        public String getBookingId() { return bookingId; }
        public String getStatus() { return status; }
        public boolean isPersisted() { return persisted; }
    }

    public SettlementResponse initiateSettlement(String bookingId, String paymentMethod) {
        Booking booking = bookingService.getBookingById(bookingId)
                .orElseThrow(() -> new BusinessException(404, "预订不存在"));

        if (!"completed".equals(booking.getBookingStatus()) && !"confirmed".equals(booking.getBookingStatus())) {
            throw new BusinessException(400, "行程未完成");
        }

        Optional<Settlement> existing = settlementRepository.findByBookingIdAndPaymentStatus(bookingId, "paid");
        if (existing.isPresent()) {
            throw new BusinessException(400, "重复结算");
        }

        String taskId = "settle_" + IdGenerator.generateSettlementId();

        boolean persisted = false;
        if (settlementConfig.isPersistenceEnabled() && redisQueueService != null) {
            SettlementTaskDTO task = SettlementTaskDTO.create(
                    null, bookingId, booking.getTouristId(), booking.getBookingAmount());
            task.setPaymentMethod(paymentMethod);
            task.setMaxRetries(settlementConfig.getMaxRetryAttempts());
            persisted = redisQueueService.enqueueTask(task);
            if (persisted) {
                log.info("结算任务已持久化到Redis - 任务ID: {}, 预订ID: {}", taskId, bookingId);
            }
        }

        if (!persisted) {
            SettlementTaskResult result = new SettlementTaskResult();
            taskResults.put(taskId, result);
            executorService.submit(() -> executeSettlementWithRetry(taskId, bookingId, paymentMethod));
            log.info("结算任务已提交到内存队列 - 任务ID: {}, 预订ID: {}", taskId, bookingId);
        }

        return new SettlementResponse(taskId, bookingId, "processing", persisted);
    }

    private void executeSettlementWithRetry(String taskId, String bookingId, String paymentMethod) {
        SettlementTaskResult result = taskResults.get(taskId);
        if (result == null) return;

        int attempts = 0;
        boolean success = false;
        String lastError = null;

        while (attempts < settlementConfig.getMaxRetryAttempts() && !success) {
            attempts++;
            result.attemptCount.set(attempts);

            try {
                log.info("执行结算 - 任务ID: {}, 预订ID: {}, 尝试次数: {}", taskId, bookingId, attempts);

                Booking booking = bookingService.getBookingById(bookingId)
                        .orElseThrow(() -> new BusinessException(404, "预订不存在"));

                Settlement settlement = new Settlement();
                settlement.setSettlementId(IdGenerator.generateSettlementId());
                settlement.setBookingId(bookingId);
                settlement.setTouristId(booking.getTouristId());
                settlement.setSettlementAmount(booking.getBookingAmount());
                settlement.setPaymentMethod(paymentMethod);
                settlement.setPaymentStatus("pending");
                settlement.setSettlementTime(Instant.now());

                boolean paymentSuccess = simulatePaymentProcessing(bookingId, attempts);

                if (paymentSuccess) {
                    settlement.setPaymentStatus("paid");
                    Settlement saved = settlementRepository.save(settlement);

                    bookingService.updateBookingStatus(bookingId, "settled");
                    analyticsService.updateSettlementStatistics(booking.getBookingAmount());
                    historyService.recordHistory("settlement", saved.getSettlementId(),
                            "create", "异步结算成功，金额: " + booking.getBookingAmount());

                    result.completed = true;
                    result.success = true;
                    result.settlement = saved;
                    success = true;
                    log.info("结算成功 - 任务ID: {}, 预订ID: {}", taskId, bookingId);
                } else {
                    throw new BusinessException("支付处理失败");
                }
            } catch (Exception e) {
                lastError = e.getMessage();
                result.errorMessage = lastError;
                log.warn("结算失败 - 任务ID: {}, 尝试次数: {}, 错误: {}", taskId, attempts, lastError);

                if (attempts < settlementConfig.getMaxRetryAttempts()) {
                    try {
                        Thread.sleep(settlementConfig.getRetryDelayMs() * attempts);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        if (!success) {
            result.completed = true;
            result.success = false;
            result.errorMessage = lastError;
            log.error("结算最终失败 - 任务ID: {}, 预订ID: {}, 最终错误: {}", taskId, bookingId, lastError);
        }
    }

    protected boolean simulatePaymentProcessing(String bookingId, int attempt) {
        return true;
    }

    public Optional<SettlementTaskResult> getTaskResult(String taskId) {
        return Optional.ofNullable(taskResults.get(taskId));
    }

    public void shutdown() {
        executorService.shutdown();
    }

    public boolean triggerSettlement(String itineraryId, String bookingId) {
        Optional<Booking> bookingOpt = bookingService.getBookingById(bookingId);
        if (bookingOpt.isEmpty()) {
            log.warn("预订不存在，跳过结算 - 预订ID: {}", bookingId);
            return false;
        }

        Booking booking = bookingOpt.get();
        String taskId = "settle_" + itineraryId + "_" + System.currentTimeMillis();

        if (settlementConfig.isPersistenceEnabled() && redisQueueService != null) {
            SettlementTaskDTO task = SettlementTaskDTO.create(
                    itineraryId, bookingId, booking.getTouristId(), booking.getBookingAmount());
            task.setMaxRetries(settlementConfig.getMaxRetryAttempts());
            return redisQueueService.enqueueTask(task);
        } else {
            SettlementTask task = createSettlementTask(itineraryId, bookingId);
            executorService.submit(() -> {
                try {
                    task.call();
                } catch (Exception e) {
                    log.error("异步结算任务执行失败", e);
                }
            });
            return true;
        }
    }

    public SettlementTask createSettlementTask(String itineraryId, String bookingId) {
        return new SettlementTask(itineraryId, bookingId, bookingService,
                settlementService, analyticsService, historyService, settlementConfig);
    }

    @RequiredArgsConstructor
    public static class SettlementTask implements Callable<Boolean> {
        private final String itineraryId;
        private final String bookingId;
        private final BookingService bookingService;
        private final SettlementService settlementService;
        private final AnalyticsService analyticsService;
        private final HistoryService historyService;
        private final SettlementConfig settlementConfig;

        @Override
        public Boolean call() throws Exception {
            int attempt = 0;
            boolean success = false;
            Exception lastError = null;

            while (attempt < settlementConfig.getMaxRetryAttempts() && !success) {
                attempt++;
                try {
                    Optional<Booking> bookingOpt = bookingService.getBookingById(bookingId);
                    if (bookingOpt.isEmpty()) {
                        return false;
                    }

                    Booking booking = bookingOpt.get();

                    Settlement settlement = new Settlement();
                    settlement.setSettlementId("settle_" + System.currentTimeMillis());
                    settlement.setBookingId(bookingId);
                    settlement.setItineraryId(itineraryId);
                    settlement.setTouristId(booking.getTouristId());
                    settlement.setSettlementAmount(booking.getBookingAmount());
                    settlement.setSettlementStatus("pending");
                    settlement.setSettlementTime(Instant.now());

                    Settlement saved = settlementService.createSettlement(settlement);

                    if ("success".equals(saved.getPaymentStatus()) || "paid".equals(saved.getPaymentStatus())) {
                        bookingService.completeSettlement(bookingId, booking.getBookingAmount());
                        analyticsService.updateSettlementStatistics(booking.getBookingAmount());
                        historyService.recordHistory("settlement", saved.getSettlementId(),
                                "success", "异步结算成功，金额: " + booking.getBookingAmount());
                        success = true;
                        return true;
                    } else {
                        throw new RuntimeException("支付处理失败");
                    }
                } catch (Exception e) {
                    lastError = e;
                    if (attempt < settlementConfig.getMaxRetryAttempts()) {
                        try {
                            Thread.sleep(settlementConfig.getRetryDelayMs() * attempt);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            }

            historyService.recordHistory("settlement", "fail_" + bookingId,
                    "failed", "异步结算失败，最后错误: " + (lastError != null ? lastError.getMessage() : "未知"));
            return false;
        }
    }
}
