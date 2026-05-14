package com.hotelbooking.service;

import com.hotelbooking.dto.CheckInRequest;
import com.hotelbooking.model.CheckIn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@Service
@EnableAsync
public class AsyncCheckInService {
    private static final Logger logger = LoggerFactory.getLogger(AsyncCheckInService.class);

    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final long RETRY_DELAY_MS = 1000;

    private final CheckInService checkInService;

    public AsyncCheckInService(CheckInService checkInService) {
        this.checkInService = checkInService;
    }

    public CompletableFuture<CheckIn> asyncCheckIn(CheckInRequest request) {
        logger.info("提交异步入住登记请求: 预订ID={}", request.getBookingId());
        return CompletableFuture.supplyAsync(() -> processCheckInWithRetry(request));
    }

    private CheckIn processCheckInWithRetry(CheckInRequest request) {
        int attempt = 0;
        while (attempt < MAX_RETRY_ATTEMPTS) {
            try {
                logger.info("入住登记处理, 尝试次数: {}/{}", attempt + 1, MAX_RETRY_ATTEMPTS);
                return checkInService.checkIn(request);
            } catch (RuntimeException e) {
                attempt++;
                if (attempt >= MAX_RETRY_ATTEMPTS) {
                    logger.error("入住登记失败，已达到最大重试次数: 预订ID={}, 错误={}", 
                            request.getBookingId(), e.getMessage());
                    throw e;
                }
                logger.warn("入住登记失败，准备重试: 预订ID={}, 尝试次数={}, 错误={}", 
                        request.getBookingId(), attempt, e.getMessage());
                try {
                    Thread.sleep(RETRY_DELAY_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("入住登记被中断", ie);
                }
            }
        }
        throw new RuntimeException("入住登记处理异常");
    }

    @Async
    public void processCheckInAsync(CheckInRequest request, CheckInCallback callback) {
        logger.info("异步处理入住登记: 预订ID={}", request.getBookingId());
        try {
            CheckIn checkIn = processCheckInWithRetry(request);
            callback.onSuccess(checkIn);
        } catch (Exception e) {
            logger.error("异步入住登记处理失败: 预订ID={}, 错误={}", request.getBookingId(), e.getMessage());
            callback.onFailure(e);
        }
    }

    public Optional<CheckIn> getCheckInStatus(String checkinId) {
        return checkInService.getCheckInById(checkinId);
    }

    public interface CheckInCallback {
        void onSuccess(CheckIn checkIn);
        void onFailure(Exception e);
    }

    public static int getMaxRetryAttempts() {
        return MAX_RETRY_ATTEMPTS;
    }
}
