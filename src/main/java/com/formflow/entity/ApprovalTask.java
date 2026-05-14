package com.formflow.entity;

import com.formflow.enums.TaskStatus;
import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "approval_tasks", indexes = {
    @Index(name = "idx_task_approver", columnList = "approver_id"),
    @Index(name = "idx_task_instance", columnList = "instance_id"),
    @Index(name = "idx_task_status", columnList = "task_status")
})
public class ApprovalTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", unique = true, nullable = false)
    private String taskId;

    @Column(name = "instance_id", nullable = false)
    private String instanceId;

    @Column(name = "node_id", nullable = false)
    private String nodeId;

    @Column(name = "node_name")
    private String nodeName;

    @Column(name = "form_id", nullable = false)
    private String formId;

    @Column(name = "template_id")
    private String templateId;

    @Column(name = "approver_id", nullable = false)
    private String approverId;

    @Column(name = "approver_name")
    private String approverName;

    @Column(name = "submitter_id")
    private String submitterId;

    @Column(name = "submitter_name")
    private String submitterName;

    @Enumerated(EnumType.STRING)
    @Column(name = "task_status", nullable = false)
    private TaskStatus taskStatus = TaskStatus.PENDING;

    @Column(name = "approval_result")
    private String approvalResult;

    @Column(name = "approval_comment", length = 1000)
    private String approvalComment;

    @Column(name = "form_title")
    private String formTitle;

    @Column(name = "priority")
    private Integer priority = 0;

    @CreationTimestamp
    @Column(name = "assigned_time", nullable = false, updatable = false)
    private LocalDateTime assignedTime;

    @Column(name = "due_time")
    private LocalDateTime dueTime;

    @Column(name = "completed_time")
    private LocalDateTime completedTime;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
