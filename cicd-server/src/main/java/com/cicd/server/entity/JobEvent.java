package com.cicd.server.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = "job_events")
public class JobEvent extends BaseEntity {

    @Column(name = "job_id", nullable = false)
    private Long jobId;

    @Column(name = "job_token", nullable = false, length = 64)
    private String jobToken;

    @Column(name = "event_type", nullable = false, length = 30)
    private String eventType;

    @Column(name = "step_index")
    private Integer stepIndex;

    @Column(name = "step_name", length = 200)
    private String stepName;

    @Column(name = "step_status", length = 20)
    private String stepStatus;

    @Column(name = "runner_id")
    private Long runnerId;

    @Column(name = "log_increment", columnDefinition = "TEXT")
    private String logIncrement;

    @Column(name = "log_offset")
    private Integer logOffset;

    @Column(name = "exit_code")
    private Integer exitCode;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "event_timestamp", nullable = false)
    private LocalDateTime eventTimestamp;
}
