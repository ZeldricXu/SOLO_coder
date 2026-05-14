package com.mobilestore.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "approval_logs")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ApprovalLog {

    @Id
    @Column(name = "log_id", length = 50)
    private String logId;

    @Column(name = "version_id", length = 50, nullable = false)
    private String versionId;

    @Column(name = "action", length = 20)
    private String action;

    @Column(name = "operator", length = 50)
    private String operator;

    @Column(name = "comment", columnDefinition = "TEXT")
    private String comment;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
