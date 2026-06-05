package com.company.dbstudio.connection.model;

import com.company.dbstudio.core.model.BaseModel;

import java.time.LocalDateTime;

public class QueryHistory extends BaseModel {

    private String connectionId;
    private String connectionName;
    private ConnectionType connectionType;
    private String sql;
    private String database;
    private String schema;
    private long executionTime;
    private int rowCount;
    private boolean success;
    private String errorMessage;
    private LocalDateTime executedAt;
    private String executionPlan;

    public QueryHistory() {
        super();
    }

    public QueryHistory(String connectionId, String sql) {
        this();
        this.connectionId = connectionId;
        this.sql = sql;
        this.executedAt = LocalDateTime.now();
    }

    public static QueryHistory success(String connectionId, String sql,
                                       long executionTime, int rowCount) {
        QueryHistory history = new QueryHistory(connectionId, sql);
        history.executionTime = executionTime;
        history.rowCount = rowCount;
        history.success = true;
        return history;
    }

    public static QueryHistory failure(String connectionId, String sql,
                                       long executionTime, String errorMessage) {
        QueryHistory history = new QueryHistory(connectionId, sql);
        history.executionTime = executionTime;
        history.success = false;
        history.errorMessage = errorMessage;
        return history;
    }

    public String getConnectionId() {
        return connectionId;
    }

    public void setConnectionId(String connectionId) {
        this.connectionId = connectionId;
    }

    public String getConnectionName() {
        return connectionName;
    }

    public void setConnectionName(String connectionName) {
        this.connectionName = connectionName;
    }

    public ConnectionType getConnectionType() {
        return connectionType;
    }

    public void setConnectionType(ConnectionType connectionType) {
        this.connectionType = connectionType;
    }

    public String getSql() {
        return sql;
    }

    public void setSql(String sql) {
        this.sql = sql;
    }

    public String getDatabase() {
        return database;
    }

    public void setDatabase(String database) {
        this.database = database;
    }

    public String getSchema() {
        return schema;
    }

    public void setSchema(String schema) {
        this.schema = schema;
    }

    public long getExecutionTime() {
        return executionTime;
    }

    public void setExecutionTime(long executionTime) {
        this.executionTime = executionTime;
    }

    public int getRowCount() {
        return rowCount;
    }

    public void setRowCount(int rowCount) {
        this.rowCount = rowCount;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public LocalDateTime getExecutedAt() {
        return executedAt;
    }

    public void setExecutedAt(LocalDateTime executedAt) {
        this.executedAt = executedAt;
    }

    public String getExecutionPlan() {
        return executionPlan;
    }

    public void setExecutionPlan(String executionPlan) {
        this.executionPlan = executionPlan;
    }
}
