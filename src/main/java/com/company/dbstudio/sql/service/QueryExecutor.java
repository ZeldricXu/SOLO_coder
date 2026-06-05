package com.company.dbstudio.sql.service;

import com.company.dbstudio.connection.ConnectionManager;
import com.company.dbstudio.connection.QueryHistoryManager;
import com.company.dbstudio.connection.datasource.DataSourceRegistry;
import com.company.dbstudio.connection.model.ConnectionType;
import com.company.dbstudio.connection.model.QueryHistory;
import com.company.dbstudio.core.ApplicationContext;
import com.company.dbstudio.core.model.Result;
import com.company.dbstudio.core.util.DateUtils;
import com.company.dbstudio.core.util.StringUtils;
import com.company.dbstudio.sql.model.ExecutionPlan;
import com.company.dbstudio.sql.model.QueryResult;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public class QueryExecutor {
    private final DataSourceRegistry dataSourceRegistry;
    private final ConnectionManager connectionManager;
    private final QueryHistoryManager historyManager;
    private final SqlParserService parserService;
    private final ExecutionPlanParser planParser;
    private final AtomicBoolean cancelled;

    public QueryExecutor() {
        this.dataSourceRegistry = ApplicationContext.getBean(DataSourceRegistry.class);
        this.connectionManager = ApplicationContext.getBean(ConnectionManager.class);
        this.historyManager = ApplicationContext.getBean(QueryHistoryManager.class);
        this.parserService = SqlParserService.getInstance();
        this.planParser = new ExecutionPlanParser();
        this.cancelled = new AtomicBoolean(false);
    }

    public Result<QueryResult> executeQuery(String connectionId, String sql) {
        return executeQuery(connectionId, sql, false, -1);
    }

    public Result<QueryResult> executeQuery(String connectionId, String sql, boolean withPlan, int fetchSize) {
        if (StringUtils.isEmpty(connectionId) || StringUtils.isEmpty(sql)) {
            return Result.failure("连接ID或SQL语句不能为空");
        }

        cancelled.set(false);
        long startTime = System.currentTimeMillis();
        QueryResult queryResult = new QueryResult(sql, connectionId);
        queryResult.setQueryType(parserService.determineQueryType(sql));

        List<String> statements = parserService.splitStatements(sql);
        if (statements.isEmpty()) {
            return Result.failure("没有可执行的SQL语句");
        }

        try (Connection conn = dataSourceRegistry.getConnection(connectionId)) {
            for (int i = 0; i < statements.size(); i++) {
                if (cancelled.get()) {
                    queryResult.setErrorMessage("查询已取消");
                    return Result.failure("查询已取消");
                }

                String stmtSql = statements.get(i);
                executeSingleStatement(conn, stmtSql, queryResult, withPlan, fetchSize);

                if (queryResult.getHasError()) {
                    break;
                }

                if (statements.size() > 1 && !queryResult.getHasError() && i < statements.size() - 1) {
                    queryResult.addRow(FXCollections.observableArrayList(
                            "--- 语句 " + (i + 1) + " 执行完成 ---",
                            "影响行数: " + queryResult.getAffectedRows()
                    ));
                }
            }
        } catch (SQLException e) {
            queryResult.setErrorMessage("SQL执行错误: " + e.getMessage());
            queryResult.setHasError(true);
        }

        long executionTime = System.currentTimeMillis() - startTime;
        queryResult.setExecutionTime(executionTime);

        saveQueryHistory(connectionId, sql, queryResult);

        if (queryResult.getHasError()) {
            return Result.failure(queryResult.getErrorMessage());
        }

        return Result.success(queryResult);
    }

    private void executeSingleStatement(Connection conn, String sql, QueryResult result, 
                                       boolean withPlan, int fetchSize) throws SQLException {
        String queryType = parserService.determineQueryType(sql);
        result.setQueryType(queryType);

        if (withPlan || "EXPLAIN".equalsIgnoreCase(queryType)) {
            executeExplain(conn, sql, result);
            return;
        }

        try (Statement stmt = conn.createStatement()) {
            if (fetchSize > 0) {
                stmt.setFetchSize(fetchSize);
            }
            stmt.setQueryTimeout(300);

            boolean hasResultSet = stmt.execute(sql);
            long affectedRows = stmt.getLargeUpdateCount();

            if (hasResultSet) {
                try (ResultSet rs = stmt.getResultSet()) {
                    result.setColumnsFromMetaData(rs.getMetaData());
                    
                    ObservableList<ObservableList<Object>> data = FXCollections.observableArrayList();
                    int rowCount = 0;
                    while (rs.next() && !cancelled.get()) {
                        ObservableList<Object> row = FXCollections.observableArrayList();
                        int colCount = rs.getMetaData().getColumnCount();
                        for (int i = 1; i <= colCount; i++) {
                            Object value = rs.getObject(i);
                            if (value instanceof byte[] bytes) {
                                row.add(StringUtils.formatBytes(bytes.length));
                            } else if (value instanceof java.sql.Clob clob) {
                                row.add(clob.getSubString(1, 1000) + "...");
                            } else if (value != null && value.getClass().getName().contains("Blob")) {
                                row.add("BLOB(" + ((Blob) value).length() + " bytes)");
                            } else {
                                row.add(value);
                            }
                        }
                        data.add(row);
                        rowCount++;
                        
                        if (rowCount % 1000 == 0 && cancelled.get()) {
                            break;
                        }
                    }
                    result.setData(data);
                }
            } else {
                result.setAffectedRows(affectedRows);
            }
        }
    }

    private void executeExplain(Connection conn, String sql, QueryResult result) throws SQLException {
        String explainSql = sql;
        if (!sql.toUpperCase().startsWith("EXPLAIN")) {
            ConnectionType type = connectionManager.getCurrentConnection()
                    .map(c -> c.getConfig().getType())
                    .orElse(ConnectionType.MYSQL);
            
            explainSql = switch (type) {
                case MYSQL, POSTGRESQL -> "EXPLAIN ANALYZE " + sql;
                case ORACLE -> "EXPLAIN PLAN FOR " + sql;
                case SQL_SERVER -> "SET SHOWPLAN_XML ON; " + sql + "; SET SHOWPLAN_XML OFF;";
                default -> "EXPLAIN " + sql;
            };
        }

        List<String[]> planRows = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(explainSql)) {
            
            ResultSetMetaData metaData = rs.getMetaData();
            int colCount = metaData.getColumnCount();
            
            while (rs.next()) {
                String[] row = new String[colCount];
                for (int i = 1; i <= colCount; i++) {
                    Object value = rs.getObject(i);
                    row[i - 1] = value != null ? value.toString() : "";
                }
                planRows.add(row);
            }

            List<QueryResult.ColumnInfo> columns = new ArrayList<>();
            for (int i = 1; i <= colCount; i++) {
                columns.add(new QueryResult.ColumnInfo(
                        metaData.getColumnName(i),
                        metaData.getColumnLabel(i),
                        metaData.getColumnTypeName(i),
                        metaData.getColumnType(i),
                        metaData.getPrecision(i),
                        metaData.getScale(i),
                        true
                ));
            }
            result.setColumns(columns);

            ObservableList<ObservableList<Object>> data = FXCollections.observableArrayList();
            for (String[] row : planRows) {
                ObservableList<Object> observableRow = FXCollections.observableArrayList();
                for (String cell : row) {
                    observableRow.add(cell);
                }
                data.add(observableRow);
            }
            result.setData(data);

            ConnectionType type = connectionManager.getCurrentConnection()
                    .map(c -> c.getConfig().getType())
                    .orElse(ConnectionType.MYSQL);
            ExecutionPlan plan = planParser.parse(planRows, type);
            result.setExecutionPlan(plan);
        }
    }

    public void executeQueryAsync(String connectionId, String sql, Consumer<Result<QueryResult>> callback) {
        executeQueryAsync(connectionId, sql, false, -1, callback);
    }

    public void executeQueryAsync(String connectionId, String sql, boolean withPlan, 
                                 int fetchSize, Consumer<Result<QueryResult>> callback) {
        ApplicationContext.executeAsync(() -> {
            Result<QueryResult> result = executeQuery(connectionId, sql, withPlan, fetchSize);
            Platform.runLater(() -> callback.accept(result));
        });
    }

    public void cancel() {
        cancelled.set(true);
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    private void saveQueryHistory(String connectionId, String sql, QueryResult result) {
        try {
            QueryHistory history = new QueryHistory();
            history.setConnectionId(connectionId);
            history.setSql(sql);
            history.setExecutionTime(result.getExecutionTime());
            history.setRowCount(result.getRowCount());
            history.setAffectedRows(result.getAffectedRows());
            history.setSuccess(!result.getHasError());
            history.setErrorMessage(result.getErrorMessage());
            history.setQueryType(result.getQueryType());
            history.setExecutionPlan(result.getExecutionPlan() != null ? 
                    result.getExecutionPlan().toTreeString() : null);
            history.setCreatedAt(LocalDateTime.now());
            history.setUpdatedAt(LocalDateTime.now());
            
            historyManager.addHistory(history);
        } catch (Exception e) {
            // 历史记录保存失败不影响主流程
        }
    }

    public Result<ExecutionPlan> explainPlan(String connectionId, String sql) {
        QueryResult result = new QueryResult(sql, connectionId);
        try (Connection conn = dataSourceRegistry.getConnection(connectionId)) {
            executeExplain(conn, sql, result);
            if (result.getExecutionPlan() != null) {
                return Result.success(result.getExecutionPlan());
            }
            return Result.failure("无法解析执行计划");
        } catch (SQLException e) {
            return Result.failure("执行计划获取失败: " + e.getMessage());
        }
    }

    public Result<Long> countRows(String connectionId, String tableName) {
        String sql = "SELECT COUNT(*) FROM " + tableName;
        try (Connection conn = dataSourceRegistry.getConnection(connectionId);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) {
                return Result.success(rs.getLong(1));
            }
            return Result.failure("无法获取行数");
        } catch (SQLException e) {
            return Result.failure("计数查询失败: " + e.getMessage());
        }
    }

    public Result<List<String>> getTableColumns(String connectionId, String tableName) {
        try (Connection conn = dataSourceRegistry.getConnection(connectionId);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM " + tableName + " WHERE 1=0")) {
            
            ResultSetMetaData metaData = rs.getMetaData();
            List<String> columns = new ArrayList<>();
            for (int i = 1; i <= metaData.getColumnCount(); i++) {
                columns.add(metaData.getColumnName(i));
            }
            return Result.success(columns);
        } catch (SQLException e) {
            return Result.failure("获取列信息失败: " + e.getMessage());
        }
    }
}
