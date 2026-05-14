package com.recruitment.model;

import com.recruitment.common.enums.HireStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;
import java.time.Instant;
import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "hires")
public class Hire {
    @Id
    @Column(name = "hire_id", nullable = false, unique = true)
    private String hireId;

    @Column(name = "resume_id", nullable = false)
    private String resumeId;

    @Column(name = "candidate_id", nullable = false)
    private String candidateId;

    @Enumerated(EnumType.STRING)
    @Column(name = "hire_status", nullable = false)
    @Builder.Default
    private HireStatus hireStatus = HireStatus.PENDING_APPROVAL;

    @Column(name = "hire_salary")
    private String hireSalary;

    @Column(name = "hire_date")
    private LocalDate hireDate;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "reject_reason")
    private String rejectReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        if (hireStatus == null) {
            hireStatus = HireStatus.PENDING_APPROVAL;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
