package com.library.librarymgmt.service;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class NotificationService {

    @Async
    public CompletableFuture<Boolean> sendReservationNotification(String reserveId, String bookId, String readerId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Thread.sleep(100);
                return true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        });
    }

    public boolean sendReservationNotificationSync(String reserveId, String bookId, String readerId, int maxRetries) {
        AtomicInteger retryCount = new AtomicInteger(0);
        while (retryCount.get() < maxRetries) {
            try {
                Thread.sleep(10);
                return true;
            } catch (Exception e) {
                retryCount.incrementAndGet();
            }
        }
        return false;
    }
}
