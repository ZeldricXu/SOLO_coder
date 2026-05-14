package com.example.mailservice.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SendTask implements Serializable {
    private static final long serialVersionUID = 1L;

    private String taskId;
    private String mailId;
    private List<String> recipients;
    private List<String> cc;
    private List<String> bcc;
    private String subject;
    private String content;
    private String contentType;
    private String category;
    private String templateId;
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

    public static SendTask fromMailRecord(MailRecord record, int maxRetries) {
        return SendTask.builder()
                .taskId("task_" + record.getMailId())
                .mailId(record.getMailId())
                .subject(record.getSubject())
                .content(record.getContent())
                .category(record.getCategory())
                .maxRetries(maxRetries)
                .retryCount(0)
                .status("pending")
                .createdAt(LocalDateTime.now())
                .build();
    }
}
