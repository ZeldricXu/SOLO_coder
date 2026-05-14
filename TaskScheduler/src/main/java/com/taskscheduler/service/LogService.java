package com.taskscheduler.service;

import com.taskscheduler.entity.TaskLog;
import com.taskscheduler.repository.TaskLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class LogService {

    private final TaskLogRepository taskLogRepository;

    @Transactional
    public void logInfo(String executeId, String taskId, String content) {
        log(executeId, taskId, "info", content);
    }

    @Transactional
    public void logWarn(String executeId, String taskId, String content) {
        log(executeId, taskId, "warn", content);
    }

    @Transactional
    public void logError(String executeId, String taskId, String content) {
        log(executeId, taskId, "error", content);
    }

    @Transactional
    public void logDebug(String executeId, String taskId, String content) {
        log(executeId, taskId, "debug", content);
    }

    @Transactional
    public void log(String executeId, String taskId, String level, String content) {
        TaskLog taskLog = new TaskLog();
        taskLog.setExecuteId(executeId);
        taskLog.setTaskId(taskId);
        taskLog.setLogLevel(level);
        taskLog.setLogContent(content);
        taskLog.setLogTime(LocalDateTime.now());
        
        taskLogRepository.save(taskLog);
        
        log.info("[{}][{}][{}] {}", executeId, taskId, level, content);
    }

    public List<TaskLog> getLogsByExecuteId(String executeId) {
        return taskLogRepository.findLogsByExecuteId(executeId);
    }

    public List<TaskLog> getLogsByTaskId(String taskId) {
        return taskLogRepository.findByTaskIdOrderByLogTimeDesc(taskId);
    }

    public List<TaskLog> getLogsByTaskIdAndTimeRange(String taskId, LocalDateTime startTime, LocalDateTime endTime) {
        return taskLogRepository.findByTaskIdAndTimeRange(taskId, startTime, endTime);
    }

    public List<TaskLog> getLogsByLevel(String level) {
        return taskLogRepository.findByLogLevel(level);
    }
}
