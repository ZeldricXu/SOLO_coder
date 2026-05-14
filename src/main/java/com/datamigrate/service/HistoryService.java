package com.datamigrate.service;

import com.datamigrate.common.TaskStatus;
import com.datamigrate.entity.MigrateStat;
import com.datamigrate.entity.MigrateTask;
import com.datamigrate.repository.MigrateStatRepository;
import com.datamigrate.repository.MigrateTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class HistoryService {

    private final MigrateTaskRepository taskRepository;
    private final MigrateStatRepository statRepository;
    private final LogService logService;

    public List<Map<String, Object>> getTaskHistory(String taskId) {
        List<Map<String, Object>> history = new ArrayList<>();
        
        Optional<MigrateTask> taskOpt = taskRepository.findByTaskId(taskId);
        if (taskOpt.isPresent()) {
            MigrateTask task = taskOpt.get();
            
            Map<String, Object> baseInfo = new LinkedHashMap<>();
            baseInfo.put("taskId", task.getTaskId());
            baseInfo.put("taskName", task.getTaskName());
            baseInfo.put("status", task.getStatus());
            baseInfo.put("createdAt", task.getCreatedAt());
            baseInfo.put("startedAt", task.getStartedAt());
            baseInfo.put("completedAt", task.getCompletedAt());
            
            Optional<MigrateStat> statOpt = statRepository.findByTaskId(taskId);
            if (statOpt.isPresent()) {
                MigrateStat stat = statOpt.get();
                baseInfo.put("totalDurationSeconds", stat.getTotalDurationSeconds());
                baseInfo.put("totalRecords", stat.getTotalRecords());
                baseInfo.put("successRecords", stat.getSuccessRecords());
                baseInfo.put("failRecords", stat.getFailRecords());
                baseInfo.put("avgSpeedPerSecond", stat.getAvgSpeedPerSecond());
                baseInfo.put("batchCount", stat.getBatchCount());
                baseInfo.put("retryCount", stat.getRetryCount());
                baseInfo.put("verifyMatchRate", stat.getVerifyMatchRate());
            }
            
            history.add(baseInfo);
        }
        
        return history;
    }

    public List<Map<String, Object>> listCompletedTasks() {
        List<TaskStatus> completedStatuses = Arrays.asList(
            TaskStatus.COMPLETED, TaskStatus.FAILED, TaskStatus.CANCELLED, TaskStatus.VERIFIED
        );
        
        List<MigrateTask> tasks = taskRepository.findByStatusIn(completedStatuses);
        return tasks.stream()
            .map(this::convertToHistoryItem)
            .sorted(Comparator.comparing(m -> (LocalDateTime) m.get("completedAt"), 
                Comparator.nullsLast(Comparator.reverseOrder())))
            .collect(Collectors.toList());
    }

    private Map<String, Object> convertToHistoryItem(MigrateTask task) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("taskId", task.getTaskId());
        item.put("taskName", task.getTaskName());
        item.put("status", task.getStatus());
        item.put("createdAt", task.getCreatedAt());
        item.put("startedAt", task.getStartedAt());
        item.put("completedAt", task.getCompletedAt());
        
        Optional<MigrateStat> statOpt = statRepository.findByTaskId(task.getTaskId());
        if (statOpt.isPresent()) {
            MigrateStat stat = statOpt.get();
            item.put("totalDurationSeconds", stat.getTotalDurationSeconds());
            item.put("totalRecords", stat.getTotalRecords());
            item.put("successRecords", stat.getSuccessRecords());
            item.put("failRecords", stat.getFailRecords());
            item.put("verifyMatchRate", stat.getVerifyMatchRate());
        }
        
        return item;
    }

    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new LinkedHashMap<>();
        
        stats.put("totalTasks", taskRepository.count());
        stats.put("pendingTasks", taskRepository.countByStatus(TaskStatus.PENDING));
        stats.put("runningTasks", taskRepository.countByStatus(TaskStatus.RUNNING));
        stats.put("completedTasks", taskRepository.countByStatus(TaskStatus.COMPLETED) 
            + taskRepository.countByStatus(TaskStatus.VERIFIED));
        stats.put("failedTasks", taskRepository.countByStatus(TaskStatus.FAILED));
        
        List<MigrateStat> allStats = statRepository.findAll();
        long totalRecords = 0;
        long successRecords = 0;
        long totalDuration = 0;
        
        for (MigrateStat stat : allStats) {
            if (stat.getTotalRecords() != null) {
                totalRecords += stat.getTotalRecords();
            }
            if (stat.getSuccessRecords() != null) {
                successRecords += stat.getSuccessRecords();
            }
            if (stat.getTotalDurationSeconds() != null) {
                totalDuration += stat.getTotalDurationSeconds();
            }
        }
        
        stats.put("totalMigratedRecords", totalRecords);
        stats.put("totalSuccessRecords", successRecords);
        stats.put("totalDurationSeconds", totalDuration);
        
        return stats;
    }
}
