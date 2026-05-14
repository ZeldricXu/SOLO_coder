package com.formflow.entity;

import com.formflow.enums.FormStatus;
import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "form_data")
public class FormData {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "form_id", unique = true, nullable = false)
    private String formId;

    @Column(name = "template_id", nullable = false)
    private String templateId;

    @Column(name = "submitter_id", nullable = false)
    private String submitterId;

    @Column(name = "submitter_name")
    private String submitterName;

    @Column(name = "form_data", length = 4000)
    private String formData;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private FormStatus status = FormStatus.DRAFT;

    @Column(name = "process_instance_id")
    private String processInstanceId;

    @Column(name = "current_approver_ids", length = 1000)
    private String currentApproverIds;

    @Column(name = "remark")
    private String remark;

    @CreationTimestamp
    @Column(name = "submit_time", nullable = false, updatable = false)
    private LocalDateTime submitTime;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "completed_time")
    private LocalDateTime completedTime;
}
