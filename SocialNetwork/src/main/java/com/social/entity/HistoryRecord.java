package com.social.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "history_records")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class HistoryRecord {
    @Id
    @Column(name = "history_id", nullable = false, length = 50)
    private String historyId;

    @Column(name = "user_id", nullable = false, length = 50)
    private String userId;

    @Column(name = "record_type", nullable = false, length = 20)
    private String recordType;

    @Column(name = "target_id", length = 50)
    private String targetId;

    @Column(name = "record_content", length = 2000)
    private String recordContent;

    @Column(name = "record_time")
    private LocalDateTime recordTime;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (recordTime == null) {
            recordTime = LocalDateTime.now();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    @Version
    private Long version;
}
