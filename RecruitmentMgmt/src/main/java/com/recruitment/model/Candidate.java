package com.recruitment.model;

import com.recruitment.common.enums.CandidateStatus;
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
@Table(name = "candidates")
public class Candidate {
    @Id
    @Column(name = "candidate_id", nullable = false, unique = true)
    private String candidateId;

    @Column(name = "candidate_name", nullable = false)
    private String candidateName;

    @Column(name = "candidate_phone", nullable = false)
    private String candidatePhone;

    @Column(name = "candidate_email")
    private String candidateEmail;

    @Column(name = "candidate_education")
    private String candidateEducation;

    @Column(name = "candidate_experience")
    private String candidateExperience;

    @Enumerated(EnumType.STRING)
    @Column(name = "candidate_status", nullable = false)
    @Builder.Default
    private CandidateStatus candidateStatus = CandidateStatus.REGISTERED;

    @Column(name = "registered_at", nullable = false)
    private Instant registeredAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        registeredAt = Instant.now();
        if (candidateStatus == null) {
            candidateStatus = CandidateStatus.REGISTERED;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
