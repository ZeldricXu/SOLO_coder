package com.datastandard.modules.profiling.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("profiling_sessions")
public class ProfilingSession {

    @TableId(type = IdType.ASSIGN_UUID)
    private String sessionId;

    private String sessionName;

    private String description;

    private String status;

    private Instant startTime;

    private Instant endTime;

    private Duration requestedDuration;

    private Duration actualDuration;

    private int samplingIntervalMs;

    private boolean cpuProfiling;

    private boolean memoryProfiling;

    private boolean lockProfiling;

    private boolean allocationProfiling;

    private String includedPackages;

    private String excludedPackages;

    private String targetJvmPid;

    private String cpuReportPath;

    private String memoryReportPath;

    private String flameGraphPath;

    private String diffReportPath;

    private String compareWithSessionId;

    private String jvmVersion;

    private String errorMessage;

    private String createdBy;

    private Instant createdAt;

    private Instant updatedAt;

    private Integer deleted;
}
