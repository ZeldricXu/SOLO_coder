package com.datamigrate.dto;

import com.datamigrate.common.TaskStatus;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TaskListResponse {
    private List<TaskInfo> tasks;
    private long total;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TaskInfo {
        private String taskId;
        private String taskName;
        private TaskStatus status;
        private LocalDateTime createdAt;
        private LocalDateTime startedAt;
        private LocalDateTime completedAt;
    }
}
