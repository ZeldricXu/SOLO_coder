package com.cicd.server.entity;

import com.cicd.common.enums.ApprovalMode;
import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;
import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = "approvals")
public class Approval extends BaseEntity {

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pipeline_execution_id", nullable = false)
    private PipelineExecution pipelineExecution;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "environment_id")
    private Environment environment;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "description", length = 1000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "approval_mode", nullable = false, length = 20)
    private ApprovalMode approvalMode;

    @Column(name = "approvers_json", columnDefinition = "TEXT", nullable = false)
    private String approversJson;

    @Column(name = "decisions_json", columnDefinition = "TEXT")
    private String decisionsJson;

    @Column(name = "status", nullable = false, length = 30)
    private String status;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "rejected_at")
    private LocalDateTime rejectedAt;

    @Column(name = "decided_by", length = 100)
    private String decidedBy;

    @Column(name = "decision_comment", length = 1000)
    private String decisionComment;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "notification_sent", nullable = false)
    private Boolean notificationSent = false;

    @OneToMany(mappedBy = "approval", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<ApprovalDecision> decisions;
}
