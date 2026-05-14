package com.datasync.service.log.impl;

import com.datasync.common.Constants;
import com.datasync.model.SyncLog;
import com.datasync.service.log.SyncLogger;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

@Service
public class SyncLoggerImpl implements SyncLogger {

    private static final Logger logger = LoggerFactory.getLogger(SyncLoggerImpl.class);

    private final List<SyncLog> logCache = new CopyOnWriteArrayList<>();

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Override
    public void log(SyncLog syncLog) {
        if (syncLog.getLogId() == null) {
            syncLog.setLogId("log_" + UUID.randomUUID().toString().substring(0, 12));
        }
        logCache.add(syncLog);
        saveToRedis(syncLog);

        switch (syncLog.getLogLevel()) {
            case Constants.SYNC_LOG_LEVEL_INFO:
                logger.info("[{}] {}", syncLog.getSyncId(), syncLog.getMessage());
                break;
            case Constants.SYNC_LOG_LEVEL_WARN:
                logger.warn("[{}] {}", syncLog.getSyncId(), syncLog.getMessage());
                break;
            case Constants.SYNC_LOG_LEVEL_ERROR:
                logger.error("[{}] {}", syncLog.getSyncId(), syncLog.getMessage());
                break;
            default:
                logger.debug("[{}] {}", syncLog.getSyncId(), syncLog.getMessage());
        }
    }

    @Override
    public void info(String taskId, String syncId, String message) {
        log(SyncLog.info(taskId, syncId, message));
    }

    @Override
    public void info(String taskId, String syncId, String message, String dataKey) {
        SyncLog log = SyncLog.info(taskId, syncId, message);
        log.setDataKey(dataKey);
        log(log);
    }

    @Override
    public void warn(String taskId, String syncId, String message) {
        log(SyncLog.warn(taskId, syncId, message));
    }

    @Override
    public void warn(String taskId, String syncId, String message, String dataKey) {
        SyncLog log = SyncLog.warn(taskId, syncId, message);
        log.setDataKey(dataKey);
        log(log);
    }

    @Override
    public void error(String taskId, String syncId, String message) {
        log(SyncLog.error(taskId, syncId, message));
    }

    @Override
    public void error(String taskId, String syncId, String message, String dataKey, Throwable e) {
        SyncLog log = SyncLog.error(taskId, syncId, message);
        log.setDataKey(dataKey);
        if (e != null) {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            e.printStackTrace(pw);
            log.setDetails(sw.toString());
        }
        log(log);
    }

    @Override
    public List<SyncLog> getLogsBySyncId(String syncId) {
        return logCache.stream()
                .filter(l -> syncId.equals(l.getSyncId()))
                .collect(Collectors.toList());
    }

    @Override
    public List<SyncLog> getLogsByTaskId(String taskId) {
        return logCache.stream()
                .filter(l -> taskId.equals(l.getTaskId()))
                .collect(Collectors.toList());
    }

    @Override
    public List<SyncLog> getLogsByTaskId(String taskId, String level) {
        return logCache.stream()
                .filter(l -> taskId.equals(l.getTaskId()) && level.equals(l.getLogLevel()))
                .collect(Collectors.toList());
    }

    @Override
    public List<SyncLog> getErrorLogs(String syncId) {
        return logCache.stream()
                .filter(l -> syncId.equals(l.getSyncId()) && Constants.SYNC_LOG_LEVEL_ERROR.equals(l.getLogLevel()))
                .collect(Collectors.toList());
    }

    @Override
    public void clearLogs(String syncId) {
        logCache.removeIf(l -> syncId.equals(l.getSyncId()));
    }

    private void saveToRedis(SyncLog log) {
        try {
            String json = objectMapper.writeValueAsString(log);
            String key = Constants.REDIS_KEY_PREFIX_LOG + log.getLogId();
            redisTemplate.opsForValue().set(key, json);
        } catch (Exception e) {
            logger.debug("Failed to save log to Redis", e);
        }
    }
}
