package com.modelguard.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClusterStatusResponse {

    private Integer totalNodes;
    private Integer onlineNodes;
    private Integer offlineNodes;
    private Integer totalGpuCount;
    private Integer totalGpuMemoryGb;
    private Integer availableGpuMemoryGb;
    private Double memoryUtilization;
    private Integer pendingTasks;
    private Integer runningTasks;
    private Integer completedTasksToday;
    private Map<String, Object> utilizationByNode;
    private Map<String, Object> queueStats;
}
