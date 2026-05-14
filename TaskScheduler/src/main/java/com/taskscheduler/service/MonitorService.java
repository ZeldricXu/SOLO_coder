package com.taskscheduler.service;

import com.taskscheduler.dto.TaskStatistics;
import com.taskscheduler.entity.ExecuteRecord;
import com.taskscheduler.repository.ExecuteRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class MonitorService {

    private final ExecuteRecordRepository executeRecordRepository;

    public TaskStatistics getTaskStatistics(String taskId) {
        TaskStatistics stats = new TaskStatistics();
        stats.setTaskId(taskId);

        long successCount = executeRecordRepository.countByTaskIdAndStatus(taskId, "success");
        long failedCount = executeRecordRepository.countByTaskIdAndStatus(taskId, "failed");
        long runningCount = executeRecordRepository.countByTaskIdAndStatus(taskId, "running");
        long pendingCount = executeRecordRepository.countByTaskIdAndStatus(taskId, "pending");
        long delayedCount = executeRecordRepository.countByTaskIdAndStatus(taskId, "delayed");

        long total = successCount + failedCount + runningCount + pendingCount + delayedCount;

        stats.setTotalExecutions(total);
        stats.setSuccessCount(successCount);
        stats.setFailedCount(failedCount);
        stats.setRunningCount(runningCount);

        Double avgDuration = executeRecordRepository.getAverageExecutionTime(taskId);
        stats.setAverageExecutionTime(avgDuration != null ? avgDuration : 0.0);

        double successRate = (successCount + failedCount) > 0
                ? (double) successCount / (successCount + failedCount) * 100
                : 0.0;
        stats.setSuccessRate(successRate);

        return stats;
    }

    public List<ExecuteRecord> getTaskExecutions(String taskId) {
        return executeRecordRepository.findByTaskIdOrderByExecuteTimeDesc(taskId);
    }

    public List<ExecuteRecord> getTaskExecutionsByTimeRange(String taskId, LocalDateTime startTime, LocalDateTime endTime) {
        return executeRecordRepository.findByTaskIdAndTimeRange(taskId, startTime, endTime);
    }

    public List<ExecuteRecord> getRunningTasks() {
        return executeRecordRepository.findRunningTasks("running");
    }

    public Map<String, Object> getSystemStatus() {
        Map<String, Object> status = new HashMap<>();

        long totalSuccess = executeRecordRepository.countByTaskIdAndStatus("%", "success");
        long totalFailed = executeRecordRepository.countByTaskIdAndStatus("%", "failed");
        long totalRunning = executeRecordRepository.countByTaskIdAndStatus("%", "running");
        long totalPending = executeRecordRepository.countByTaskIdAndStatus("%", "pending");

        status.put("totalSuccess", totalSuccess);
        status.put("totalFailed", totalFailed);
        status.put("totalRunning", totalRunning);
        status.put("totalPending", totalPending);
        status.put("systemTime", LocalDateTime.now().toString());

        return status;
    }
}
