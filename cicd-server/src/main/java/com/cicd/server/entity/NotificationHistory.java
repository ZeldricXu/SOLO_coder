package com.cicd.server.entity;

import com.cicd.common.enums.NotificationChannel;
import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = "notification_history")
public class NotificationHistory extends BaseEntity {

    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private NotificationChannel channel;

    @Column(name = "recipient", length = 500)
    private String recipient;

    @Column(length = 500)
    private String subject;

    @Column(columnDefinition = "TEXT")
    private String content;

    @Column(name = "pipeline_execution_id")
    private Long pipelineExecutionId;

    @Column(name = "deployment_id")
    private Long deploymentId;

    @Column(name = "project_id")
    private Long projectId;

    @Column(name = "status", length = 30)
    private String status;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(name = "retry_count", nullable = false)
    private Integer retryCount = 0;
}
