package com.recruitment.model;

import com.recruitment.common.enums.ResumeSource;
import com.recruitment.common.enums.ResumeStatus;
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
@Table(name = "resumes")
public class Resume {
    @Id
    @Column(name = "resume_id", nullable = false, unique = true)
    private String resumeId;

    @Column(name = "position_id", nullable = false)
    private String positionId;

    @Column(name = "candidate_id", nullable = false)
    private String candidateId;

    @Enumerated(EnumType.STRING)
    @Column(name = "resume_status", nullable = false)
    @Builder.Default
    private ResumeStatus resumeStatus = ResumeStatus.PENDING_SCREEN;

    @Enumerated(EnumType.STRING)
    @Column(name = "resume_source")
    @Builder.Default
    private ResumeSource resumeSource = ResumeSource.PLATFORM;

    @Column(name = "resume_time", nullable = false)
    private Instant resumeTime;

    @Column(name = "screened_at")
    private Instant screenedAt;

    @Column(name = "screen_result")
    private String screenResult;

    @Column(name = "reject_reason")
    private String rejectReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        resumeTime = Instant.now();
        createdAt = Instant.now();
        if (resumeStatus == null) {
            resumeStatus = ResumeStatus.PENDING_SCREEN;
        }
        if (resumeSource == null) {
            resumeSource = ResumeSource.PLATFORM;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
