package com.recruitment.model;

import com.recruitment.common.enums.InterviewerStatus;
import com.recruitment.common.enums.InterviewType;
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
@Table(name = "interviewers")
public class Interviewer {
    @Id
    @Column(name = "interviewer_id", nullable = false, unique = true)
    private String interviewerId;

    @Column(name = "interviewer_name", nullable = false)
    private String interviewerName;

    @Column(name = "interviewer_department", nullable = false)
    private String interviewerDepartment;

    @Enumerated(EnumType.STRING)
    @Column(name = "interviewer_type", nullable = false)
    private InterviewType interviewerType;

    @Enumerated(EnumType.STRING)
    @Column(name = "interviewer_status", nullable = false)
    @Builder.Default
    private InterviewerStatus interviewerStatus = InterviewerStatus.AVAILABLE;

    @Column(name = "interviewer_count")
    @Builder.Default
    private Integer interviewerCount = 0;

    @Column(name = "completed_count")
    @Builder.Default
    private Integer completedCount = 0;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        if (interviewerStatus == null) {
            interviewerStatus = InterviewerStatus.AVAILABLE;
        }
        if (interviewerCount == null) {
            interviewerCount = 0;
        }
        if (completedCount == null) {
            completedCount = 0;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
