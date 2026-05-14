package com.library.librarymgmt.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationTask {
    private String taskId;
    private String reserveId;
    private String bookId;
    private String readerId;
    private int retryCount;
    private String status;
    private Instant createdAt;
    private Instant lastRetryAt;

    public NotificationTask(String reserveId, String bookId, String readerId) {
        this.taskId = java.util.UUID.randomUUID().toString();
        this.reserveId = reserveId;
        this.bookId = bookId;
        this.readerId = readerId;
        this.retryCount = 0;
        this.status = "pending";
        this.createdAt = Instant.now();
    }
}
