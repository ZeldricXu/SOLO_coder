package com.recruitment.model;

import com.recruitment.common.enums.HistoryType;
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
@Table(name = "histories")
public class History {
    @Id
    @Column(name = "history_id", nullable = false, unique = true)
    private String historyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "history_type", nullable = false)
    private HistoryType historyType;

    @Column(name = "related_id", nullable = false)
    private String relatedId;

    @Column(name = "position_id")
    private String positionId;

    @Column(name = "resume_id")
    private String resumeId;

    @Column(name = "candidate_id")
    private String candidateId;

    @Column(name = "interview_id")
    private String interviewId;

    @Column(name = "hire_id")
    private String hireId;

    @Column(name = "action")
    private String action;

    @Column(name = "old_status")
    private String oldStatus;

    @Column(name = "new_status")
    private String newStatus;

    @Column(name = "description")
    @Lob
    private String description;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
    }
}
