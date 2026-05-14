package com.example.mailservice.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IndexTask implements Serializable {
    private static final long serialVersionUID = 1L;

    private String taskId;
    private String mailId;
    private String subject;
    private String content;
    private String category;
    private String mailType;
    private int retryCount;
    private int maxRetries;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime lastAttemptAt;
    private String errorMessage;

    public void incrementRetry() {
        this.retryCount++;
        this.lastAttemptAt = LocalDateTime.now();
    }

    public boolean canRetry() {
        return retryCount < maxRetries;
    }

    public static IndexTask fromMailRecord(MailRecord record, int maxRetries) {
        return IndexTask.builder()
                .taskId("idx_" + record.getMailId())
                .mailId(record.getMailId())
                .subject(record.getSubject())
                .content(record.getContent())
                .category(record.getCategory())
                .mailType(record.getMailType())
                .maxRetries(maxRetries)
                .retryCount(0)
                .status("pending")
                .createdAt(LocalDateTime.now())
                .build();
    }
}
