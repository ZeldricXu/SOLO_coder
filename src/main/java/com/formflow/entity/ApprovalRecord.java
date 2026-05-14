package com.formflow.entity;

import com.formflow.enums.ApprovalResult;
import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "approval_records", indexes = {
    @Index(name = "idx_record_instance", columnList = "instance_id"),
    @Index(name = "idx_record_form", columnList = "form_id")
})
public class ApprovalRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "approval_id", unique = true, nullable = false)
    private String approvalId;

    @Column(name = "instance_id", nullable = false)
    private String instanceId;

    @Column(name = "node_id", nullable = false)
    private String nodeId;

    @Column(name = "node_name")
    private String nodeName;

    @Column(name = "form_id", nullable = false)
    private String formId;

    @Column(name = "task_id")
    private String taskId;

    @Column(name = "approver_id", nullable = false)
    private String approverId;

    @Column(name = "approver_name")
    private String approverName;

    @Enumerated(EnumType.STRING)
    @Column(name = "approval_result", nullable = false)
    private ApprovalResult approvalResult;

    @Column(name = "approval_comment", length = 1000)
    private String approvalComment;

    @Column(name = "submitter_id")
    private String submitterId;

    @Column(name = "submitter_name")
    private String submitterName;

    @Column(name = "action_type")
    private String actionType;

    @Column(name = "sort_order")
    private Integer sortOrder;

    @CreationTimestamp
    @Column(name = "approval_time", nullable = false, updatable = false)
    private LocalDateTime approvalTime;
}
