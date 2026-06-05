package com.company.dbstudio.performance.model;

import java.time.LocalDateTime;

public class SlowQueryInfo {

    private String sql;
    private LocalDateTime executeTime;
    private long executionTimeMs;
    private long rowsExamined;
    private long rowsSent;
    private String database;
    private String user;
    private String queryType;

    public SlowQueryInfo() {}

    public SlowQueryInfo(String sql, LocalDateTime executeTime, long executionTimeMs,
                         long rowsExamined, long rowsSent, String database, String user) {
        this.sql = sql;
        this.executeTime = executeTime;
        this.executionTimeMs = executionTimeMs;
        this.rowsExamined = rowsExamined;
        this.rowsSent = rowsSent;
        this.database = database;
        this.user = user;
    }

    public String getSql() { return sql; }
    public void setSql(String sql) { this.sql = sql; }

    public LocalDateTime getExecuteTime() { return executeTime; }
    public void setExecuteTime(LocalDateTime executeTime) { this.executeTime = executeTime; }

    public long getExecutionTimeMs() { return executionTimeMs; }
    public void setExecutionTimeMs(long executionTimeMs) { this.executionTimeMs = executionTimeMs; }

    public long getRowsExamined() { return rowsExamined; }
    public void setRowsExamined(long rowsExamined) { this.rowsExamined = rowsExamined; }

    public long getRowsSent() { return rowsSent; }
    public void setRowsSent(long rowsSent) { this.rowsSent = rowsSent; }

    public String getDatabase() { return database; }
    public void setDatabase(String database) { this.database = database; }

    public String getUser() { return user; }
    public void setUser(String user) { this.user = user; }

    public String getQueryType() { return queryType; }
    public void setQueryType(String queryType) { this.queryType = queryType; }

    public String getExecutionTimeDisplay() {
        if (executionTimeMs < 1000) {
            return executionTimeMs + "ms";
        } else if (executionTimeMs < 60000) {
            return String.format("%.2fs", executionTimeMs / 1000.0);
        } else {
            return String.format("%dm%ds", executionTimeMs / 60000, (executionTimeMs % 60000) / 1000);
        }
    }
}
