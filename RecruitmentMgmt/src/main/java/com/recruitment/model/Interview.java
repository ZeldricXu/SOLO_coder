package com.recruitment.model;

import com.recruitment.common.enums.InterviewStatus;
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
@Table(name = "interviews")
public class Interview {
    @Id
    @Column(name = "interview_id", nullable = false, unique = true)
    private String interviewId;

    @Column(name = "resume_id", nullable = false)
    private String resumeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "interview_type", nullable = false)
    private InterviewType interviewType;

    @Column(name = "interviewer_id", nullable = false)
    private String interviewerId;

    @Column(name = "interview_time", nullable = false)
    private Instant interviewTime;

    @Enumerated(EnumType.STRING)
    @Column(name = "interview_status", nullable = false)
    @Builder.Default
    private InterviewStatus interviewStatus = InterviewStatus.SCHEDULED;

    @Column(name = "interview_result")
    private String interviewResult;

    @Column(name = "interview_score")
    private Integer interviewScore;

    @Column(name = "tech_evaluation")
    private String techEvaluation;

    @Column(name = "overall_evaluation")
    private String overallEvaluation;

    @Column(name = "reject_reason")
    private String rejectReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        if (interviewStatus == null) {
            interviewStatus = InterviewStatus.SCHEDULED;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
