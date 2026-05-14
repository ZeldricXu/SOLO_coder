package com.datamigrate.service;

import com.datamigrate.common.LogLevel;
import com.datamigrate.common.LogType;
import com.datamigrate.entity.MigrateLog;
import com.datamigrate.repository.MigrateLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class LogService {

    private final MigrateLogRepository logRepository;

    public void log(String taskId, LogType logType, LogLevel logLevel, String content) {
        log(taskId, logType, logLevel, content, null);
    }

    public void log(String taskId, LogType logType, LogLevel logLevel, String content, String extraInfo) {
        MigrateLog migrateLog = new MigrateLog();
        migrateLog.setLogId("log_" + UUID.randomUUID().toString().substring(0, 12));
        migrateLog.setTaskId(taskId);
        migrateLog.setLogType(logType);
        migrateLog.setLogLevel(logLevel);
        migrateLog.setLogContent(content);
        migrateLog.setExtraInfo(extraInfo);
        migrateLog.setLogTime(LocalDateTime.now());

        logRepository.save(migrateLog);

        String logMessage = String.format("[%s] [%s] %s: %s", logLevel, logType, taskId, content);
        switch (logLevel) {
            case DEBUG:
                log.debug(logMessage);
                break;
            case INFO:
                log.info(logMessage);
                break;
            case WARN:
                log.warn(logMessage);
                break;
            case ERROR:
                log.error(logMessage);
                break;
        }
    }

    public void logMigrate(String taskId, String content) {
        log(taskId, LogType.MIGRATE, LogLevel.INFO, content);
    }

    public void logMigrateError(String taskId, String content, Exception e) {
        log(taskId, LogType.MIGRATE, LogLevel.ERROR, content, e != null ? e.getMessage() : null);
    }

    public void logVerify(String taskId, String content) {
        log(taskId, LogType.VERIFY, LogLevel.INFO, content);
    }

    public void logRetry(String taskId, String content) {
        log(taskId, LogType.RETRY, LogLevel.WARN, content);
    }

    public void logSchedule(String taskId, String content) {
        log(taskId, LogType.SCHEDULE, LogLevel.INFO, content);
    }

    public void logSystem(String taskId, String content) {
        log(taskId, LogType.SYSTEM, LogLevel.INFO, content);
    }

    public List<MigrateLog> getLogsByTaskId(String taskId) {
        return logRepository.findByTaskIdOrderByLogTimeDesc(taskId);
    }

    public List<MigrateLog> getLogsByTaskIdAndType(String taskId, LogType logType) {
        return logRepository.findByTaskIdAndLogTypeOrderByLogTimeDesc(taskId, logType);
    }

    public List<MigrateLog> getLogsByTimeRange(LocalDateTime start, LocalDateTime end) {
        return logRepository.findByLogTimeBetweenOrderByLogTimeDesc(start, end);
    }
}
