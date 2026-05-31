package com.datastandard.modules.profiling.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.util.Set;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProfilingRequest {

    @NotBlank(message = "会话名称不能为空")
    private String sessionName;

    private String description;

    @Builder.Default
    private Duration duration = Duration.ofMinutes(5);

    @Min(1)
    @Builder.Default
    private int samplingIntervalMs = 10;

    @Builder.Default
    private boolean cpuProfiling = true;

    @Builder.Default
    private boolean memoryProfiling = true;

    @Builder.Default
    private boolean lockProfiling = false;

    @Builder.Default
    private boolean allocationProfiling = false;

    private Set<String> includedPackages;

    private Set<String> excludedPackages;

    private String targetJvmPid;

    private String triggerCondition;

    @Builder.Default
    private boolean autoExport = true;

    @Builder.Default
    private boolean generateFlameGraph = true;

    private String createdBy;

    private String compareWithSessionId;
}
