package com.recruitment.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;
import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "statistics")
public class Statistics {
    @Id
    @Column(name = "stat_id", nullable = false, unique = true)
    private String statId;

    @Column(name = "stat_month", nullable = false, unique = true)
    private String statMonth;

    @Column(name = "position_count")
    @Builder.Default
    private Integer positionCount = 0;

    @Column(name = "resume_count")
    @Builder.Default
    private Integer resumeCount = 0;

    @Column(name = "screened_count")
    @Builder.Default
    private Integer screenedCount = 0;

    @Column(name = "interview_count")
    @Builder.Default
    private Integer interviewCount = 0;

    @Column(name = "hire_count")
    @Builder.Default
    private Integer hireCount = 0;

    @Column(name = "reject_count")
    @Builder.Default
    private Integer rejectCount = 0;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        if (positionCount == null) positionCount = 0;
        if (resumeCount == null) resumeCount = 0;
        if (screenedCount == null) screenedCount = 0;
        if (interviewCount == null) interviewCount = 0;
        if (hireCount == null) hireCount = 0;
        if (rejectCount == null) rejectCount = 0;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
