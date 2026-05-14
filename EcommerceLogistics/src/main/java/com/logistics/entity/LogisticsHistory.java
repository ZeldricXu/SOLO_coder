package com.logistics.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "logistics_history")
public class LogisticsHistory {

    @Id
    @Column(name = "history_id", nullable = false, unique = true)
    private String historyId;

    @Column(name = "logistics_id", nullable = false)
    private String logisticsId;

    @Column(name = "history_type", nullable = false)
    private String historyType;

    @Column(name = "history_status", nullable = false)
    private String historyStatus;

    @Column(name = "history_detail")
    private String historyDetail;

    @Column(name = "history_time", nullable = false)
    private LocalDateTime historyTime;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (historyTime == null) {
            historyTime = LocalDateTime.now();
        }
    }
}
