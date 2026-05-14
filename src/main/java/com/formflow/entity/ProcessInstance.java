package com.formflow.entity;

import com.formflow.enums.ProcessInstanceStatus;
import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "process_instances")
public class ProcessInstance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "instance_id", unique = true, nullable = false)
    private String instanceId;

    @Column(name = "process_id", nullable = false)
    private String processId;

    @Column(name = "form_id", nullable = false)
    private String formId;

    @Column(name = "current_node_id")
    private String currentNodeId;

    @Column(name = "previous_node_id")
    private String previousNodeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "instance_status", nullable = false)
    private ProcessInstanceStatus instanceStatus = ProcessInstanceStatus.RUNNING;

    @Column(name = "submitter_id", nullable = false)
    private String submitterId;

    @Column(name = "submitter_name")
    private String submitterName;

    @Column(name = "variables", length = 2000)
    private String variables;

    @CreationTimestamp
    @Column(name = "start_time", nullable = false, updatable = false)
    private LocalDateTime startTime;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "end_time")
    private LocalDateTime endTime;

    @Column(name = "remark")
    private String remark;
}
