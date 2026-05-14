package com.recruitment.model;

import com.recruitment.common.enums.PositionStatus;
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
@Table(name = "positions")
public class Position {
    @Id
    @Column(name = "position_id", nullable = false, unique = true)
    private String positionId;

    @Column(name = "position_name", nullable = false)
    private String positionName;

    @Column(name = "position_type", nullable = false)
    private String positionType;

    @Column(name = "position_department", nullable = false)
    private String positionDepartment;

    @Enumerated(EnumType.STRING)
    @Column(name = "position_status", nullable = false)
    private PositionStatus positionStatus;

    @Column(name = "position_count", nullable = false)
    private Integer positionCount;

    @Column(name = "resume_count")
    @Builder.Default
    private Integer resumeCount = 0;

    @Column(name = "position_salary")
    private String positionSalary;

    @Column(name = "position_requirement")
    private String positionRequirement;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        if (resumeCount == null) {
            resumeCount = 0;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
