package com.datasync.service.log;

import com.datasync.model.SyncLog;

import java.util.List;

public interface SyncLogger {

    void log(SyncLog log);

    void info(String taskId, String syncId, String message);

    void info(String taskId, String syncId, String message, String dataKey);

    void warn(String taskId, String syncId, String message);

    void warn(String taskId, String syncId, String message, String dataKey);

    void error(String taskId, String syncId, String message);

    void error(String taskId, String syncId, String message, String dataKey, Throwable e);

    List<SyncLog> getLogsBySyncId(String syncId);

    List<SyncLog> getLogsByTaskId(String taskId);

    List<SyncLog> getLogsByTaskId(String taskId, String level);

    List<SyncLog> getErrorLogs(String syncId);

    void clearLogs(String syncId);
}
