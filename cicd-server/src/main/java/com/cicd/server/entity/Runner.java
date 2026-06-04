package com.cicd.server.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = "runners")
public class Runner extends BaseEntity {

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "runner_token", nullable = false, length = 200)
    private String runnerToken;

    @Column(name = "tags", length = 500)
    private String tags;

    @Column(name = "hostname", length = 200)
    private String hostname;

    @Column(name = "ip_address", length = 50)
    private String ipAddress;

    @Column(name = "os", length = 50)
    private String os;

    @Column(name = "architecture", length = 50)
    private String architecture;

    @Column(name = "cpu_cores")
    private Integer cpuCores;

    @Column(name = "memory_mb")
    private Integer memoryMb;

    @Column(name = "last_heartbeat_at")
    private LocalDateTime lastHeartbeatAt;

    @Column(name = "connected_at")
    private LocalDateTime connectedAt;

    @Column(name = "status", nullable = false, length = 30)
    private String status;

    @Column(name = "current_job_id")
    private Long currentJobId;

    @Column(name = "executed_jobs_count", nullable = false)
    private Integer executedJobsCount = 0;

    @Column(name = "concurrent_jobs", nullable = false)
    private Integer concurrentJobs = 1;

    @Column(name = "workspace_path", length = 500)
    private String workspacePath;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Column(name = "is_locked", nullable = false)
    private Boolean isLocked = false;

    @Column(name = "lock_reason", length = 500)
    private String lockReason;

    @Column(name = "version", length = 50)
    private String version;
}
