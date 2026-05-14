package com.parking.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "settlement_tasks")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SettlementTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String taskId;

    @Column(nullable = false)
    private String entryId;

    @Column(nullable = false)
    private String exitId;

    @Column(nullable = false)
    private String vehicleId;

    private String spaceId;

    @Column(nullable = false)
    private Integer parkingDurationMinutes;

    @Column(nullable = false)
    private String taskStatus;

    private Integer retryAttempts;

    @Column(nullable = false)
    private Integer maxRetryAttempts;

    private String errorMessage;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private LocalDateTime startedAt;

    private LocalDateTime completedAt;

    private LocalDateTime nextRetryAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (taskStatus == null) {
            taskStatus = "pending";
        }
        if (retryAttempts == null) {
            retryAttempts = 0;
        }
        if (maxRetryAttempts == null) {
            maxRetryAttempts = 3;
        }
    }

    public boolean canRetry() {
        return retryAttempts < maxRetryAttempts;
    }

    public void incrementRetryCount() {
        if (retryAttempts == null) {
            retryAttempts = 0;
        }
        retryAttempts++;
    }
}
