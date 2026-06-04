package com.cicd.server.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = "approval_decisions",
       uniqueConstraints = {
           @UniqueConstraint(name = "uk_approval_decision_approver",
                             columnNames = {"approval_id", "approver"})
       })
public class ApprovalDecision extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "approval_id", nullable = false)
    private Approval approval;

    @Column(name = "approver", nullable = false, length = 100)
    private String approver;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "comment", length = 1000)
    private String comment;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;
}
