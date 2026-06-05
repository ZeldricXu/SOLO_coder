package com.company.dbstudio.performance.service;

import com.company.dbstudio.connection.ConnectionType;
import com.company.dbstudio.connection.datasource.DataSourceRegistry;
import com.company.dbstudio.core.model.Result;
import com.company.dbstudio.performance.model.SlowQueryInfo;
import com.company.dbstudio.sql.model.ExecutionPlan;
import com.company.dbstudio.sql.model.IndexSuggestion;
import com.company.dbstudio.sql.service.ExecutionPlanParser;
import com.company.dbstudio.sql.service.SqlParserService;
import net.sf.jsqlparser.JSQLParserException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class PerformanceAnalyzerService {

    private final DataSourceRegistry dataSourceRegistry;
    private final ExecutionPlanParser executionPlanParser;
    private final SqlParserService sqlParserService;

    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    public PerformanceAnalyzerService(DataSourceRegistry dataSourceRegistry,
                                      ExecutionPlanParser executionPlanParser,
                                      SqlParserService sqlParserService) {
        this.dataSourceRegistry = dataSourceRegistry;
        this.executionPlanParser = executionPlanParser;
        this.sqlParserService = sqlParserService;
    }

    public Result<List<SlowQueryInfo>> getSlowQueries(String connectionId, long minDurationMs, int limit) {
        try (Connection conn = dataSourceRegistry.getConnection(connectionId)) {
            ConnectionType type = ConnectionType.fromName(conn.getMetaData().getDatabaseProductName());
            List<SlowQueryInfo> queries = switch (type) {
                case MYSQL -> getMySQLSlowQueries(conn, minDurationMs, limit);
                case POSTGRESQL -> getPostgreSQLSlowQueries(conn, minDurationMs, limit);
                case ORACLE -> getOracleSlowQueries(conn, minDurationMs, limit);
                case SQL_SERVER -> getSQLServerSlowQueries(conn, minDurationMs, limit);
                default -> new ArrayList<>();
            };
            return Result.success(queries);
        } catch (Exception e) {
            return Result.failure("获取慢查询失败: " + e.getMessage());
        }
    }

    public void getSlowQueriesAsync(String connectionId, long minDurationMs, int limit,
                                    Consumer<Result<List<SlowQueryInfo>>> callback) {
        CompletableFuture.supplyAsync(() -> getSlowQueries(connectionId, minDurationMs, limit),
                Runnable::start).thenAccept(callback);
    }

    private List<SlowQueryInfo> getMySQLSlowQueries(Connection conn, long minDurationMs, int limit) throws SQLException {
        List<SlowQueryInfo> queries = new ArrayList<>();
        String sql = "SELECT query, start_time, TIMESTAMPDIFF(MICROSECOND, start_time, end_time) / 1000 AS duration, " +
                "rows_examined, rows_sent, db, user " +
                "FROM mysql.slow_log " +
                "WHERE TIMESTAMPDIFF(MICROSECOND, start_time, end_time) / 1000 >= ? " +
                "ORDER BY start_time DESC LIMIT ?";

        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, minDurationMs);
            pstmt.setInt(2, limit);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next() && !cancelled.get()) {
                    SlowQueryInfo info = new SlowQueryInfo();
                    info.setSql(rs.getString("query"));
                    info.setExecuteTime(rs.getTimestamp("start_time") != null ?
                            rs.getTimestamp("start_time").toLocalDateTime() : LocalDateTime.now());
                    info.setExecutionTimeMs(rs.getLong("duration"));
                    info.setRowsExamined(rs.getLong("rows_examined"));
                    info.setRowsSent(rs.getLong("rows_sent"));
                    info.setDatabase(rs.getString("db"));
                    info.setUser(rs.getString("user"));
                    info.setQueryType(detectQueryType(info.getSql()));
                    queries.add(info);
                }
            }
        }
        return queries;
    }

    private List<SlowQueryInfo> getPostgreSQLSlowQueries(Connection conn, long minDurationMs, int limit) throws SQLException {
        List<SlowQueryInfo> queries = new ArrayList<>();
        String sql = "SELECT query, query_start, (total_time / calls) AS avg_time, " +
                "rows, calls, datname, usename " +
                "FROM pg_stat_statements s " +
                "JOIN pg_database d ON s.dbid = d.oid " +
                "JOIN pg_user u ON s.userid = u.usesysid " +
                "WHERE (total_time / calls) >= ? " +
                "ORDER BY avg_time DESC LIMIT ?";

        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setDouble(1, minDurationMs);
            pstmt.setInt(2, limit);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next() && !cancelled.get()) {
                    SlowQueryInfo info = new SlowQueryInfo();
                    info.setSql(rs.getString("query"));
                    info.setExecuteTime(LocalDateTime.now());
                    info.setExecutionTimeMs((long) rs.getDouble("avg_time"));
                    info.setRowsExamined(rs.getLong("rows"));
                    info.setRowsSent(rs.getLong("rows"));
                    info.setDatabase(rs.getString("datname"));
                    info.setUser(rs.getString("usename"));
                    info.setQueryType(detectQueryType(info.getSql()));
                    queries.add(info);
                }
            }
        }
        return queries;
    }

    private List<SlowQueryInfo> getOracleSlowQueries(Connection conn, long minDurationMs, int limit) throws SQLException {
        List<SlowQueryInfo> queries = new ArrayList<>();
        String sql = "SELECT sql_fulltext, last_active_time, elapsed_time / 1000 AS avg_time, " +
                "rows_processed, executions, parsing_schema_name " +
                "FROM v$sql " +
                "WHERE elapsed_time / 1000 >= ? " +
                "ORDER BY avg_time DESC FETCH FIRST ? ROWS ONLY";

        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, minDurationMs);
            pstmt.setInt(2, limit);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next() && !cancelled.get()) {
                    SlowQueryInfo info = new SlowQueryInfo();
                    info.setSql(rs.getString("sql_fulltext"));
                    info.setExecuteTime(rs.getTimestamp("last_active_time") != null ?
                            rs.getTimestamp("last_active_time").toLocalDateTime() : LocalDateTime.now());
                    info.setExecutionTimeMs(rs.getLong("avg_time"));
                    info.setRowsExamined(rs.getLong("rows_processed"));
                    info.setRowsSent(rs.getLong("rows_processed"));
                    info.setDatabase("");
                    info.setUser(rs.getString("parsing_schema_name"));
                    info.setQueryType(detectQueryType(info.getSql()));
                    queries.add(info);
                }
            }
        }
        return queries;
    }

    private List<SlowQueryInfo> getSQLServerSlowQueries(Connection conn, long minDurationMs, int limit) throws SQLException {
        List<SlowQueryInfo> queries = new ArrayList<>();
        String sql = "SELECT SUBSTRING(st.text, (qs.statement_start_offset/2)+1, " +
                "((CASE qs.statement_end_offset WHEN -1 THEN DATALENGTH(st.text) " +
                "ELSE qs.statement_end_offset END - qs.statement_start_offset)/2) + 1) AS query_text, " +
                "qs.last_execution_time, qs.total_elapsed_time / 1000 / qs.execution_count AS avg_time, " +
                "qs.total_rows, qs.execution_count, DB_NAME(st.dbid) AS dbname " +
                "FROM sys.dm_exec_query_stats qs " +
                "CROSS APPLY sys.dm_exec_sql_text(qs.sql_handle) st " +
                "WHERE qs.total_elapsed_time / 1000 / qs.execution_count >= ? " +
                "ORDER BY avg_time DESC OFFSET 0 ROWS FETCH NEXT ? ROWS ONLY";

        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, minDurationMs);
            pstmt.setInt(2, limit);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next() && !cancelled.get()) {
                    SlowQueryInfo info = new SlowQueryInfo();
                    info.setSql(rs.getString("query_text"));
                    info.setExecuteTime(rs.getTimestamp("last_execution_time") != null ?
                            rs.getTimestamp("last_execution_time").toLocalDateTime() : LocalDateTime.now());
                    info.setExecutionTimeMs(rs.getLong("avg_time"));
                    info.setRowsExamined(rs.getLong("total_rows"));
                    info.setRowsSent(rs.getLong("total_rows"));
                    info.setDatabase(rs.getString("dbname"));
                    info.setUser("");
                    info.setQueryType(detectQueryType(info.getSql()));
                    queries.add(info);
                }
            }
        }
        return queries;
    }

    private String detectQueryType(String sql) {
        if (sql == null || sql.isEmpty()) return "UNKNOWN";
        String upper = sql.trim().toUpperCase();
        if (upper.startsWith("SELECT")) return "SELECT";
        if (upper.startsWith("INSERT")) return "INSERT";
        if (upper.startsWith("UPDATE")) return "UPDATE";
        if (upper.startsWith("DELETE")) return "DELETE";
        if (upper.startsWith("CREATE")) return "CREATE";
        if (upper.startsWith("ALTER")) return "ALTER";
        return "OTHER";
    }

    public Result<ExecutionPlan> analyzeExecutionPlan(String connectionId, String sql) {
        try (Connection conn = dataSourceRegistry.getConnection(connectionId)) {
            ConnectionType type = ConnectionType.fromName(conn.getMetaData().getDatabaseProductName());
            String explainSql = buildExplainSql(type, sql);

            List<String[]> planRows = new ArrayList<>();
            try (PreparedStatement pstmt = conn.prepareStatement(explainSql);
                 ResultSet rs = pstmt.executeQuery()) {

                int colCount = rs.getMetaData().getColumnCount();
                while (rs.next() && !cancelled.get()) {
                    String[] row = new String[colCount];
                    for (int i = 0; i < colCount; i++) {
                        row[i] = rs.getString(i + 1);
                    }
                    planRows.add(row);
                }
            }

            ExecutionPlan plan = executionPlanParser.parse(planRows, type);
            return Result.success(plan);
        } catch (Exception e) {
            return Result.failure("分析执行计划失败: " + e.getMessage());
        }
    }

    public void analyzeExecutionPlan(String connectionId, String sql, Consumer<Result<ExecutionPlan>> callback) {
        CompletableFuture.supplyAsync(() -> analyzeExecutionPlan(connectionId, sql),
                Runnable::start).thenAccept(callback);
    }

    private String buildExplainSql(ConnectionType type, String sql) {
        return switch (type) {
            case MYSQL -> "EXPLAIN " + sql;
            case POSTGRESQL -> "EXPLAIN (FORMAT TEXT, ANALYZE, BUFFERS, COSTS, TIMING) " + sql;
            case ORACLE -> "EXPLAIN PLAN FOR " + sql;
            case SQL_SERVER -> "SET SHOWPLAN_ALL ON; " + sql + "; SET SHOWPLAN_ALL OFF;";
            default -> "EXPLAIN " + sql;
        };
    }

    public Result<List<IndexSuggestion>> generateIndexSuggestions(String connectionId, String sql) {
        try {
            Result<Set<String>> tablesResult = sqlParserService.extractTables(sql);
            Result<Set<String>> columnsResult = sqlParserService.extractColumns(sql);

            if (!tablesResult.isSuccess()) {
                return Result.failure(tablesResult.getMessage());
            }

            List<IndexSuggestion> suggestions = new ArrayList<>();
            Set<String> tables = tablesResult.getData();
            Set<String> columns = columnsResult.getData();

            for (String tableName : tables) {
                List<String> whereColumns = columns.stream()
                        .filter(c -> c.toLowerCase().contains(tableName.toLowerCase()) ||
                                !c.contains("."))
                        .collect(Collectors.toList());

                if (!whereColumns.isEmpty()) {
                    IndexSuggestion suggestion = new IndexSuggestion(
                            tableName,
                            whereColumns,
                            "WHERE条件列可能缺少索引",
                            "range_scan"
                    );
                    suggestions.add(suggestion);
                }
            }

            try (Connection conn = dataSourceRegistry.getConnection(connectionId)) {
                for (IndexSuggestion suggestion : suggestions) {
                    checkExistingIndex(conn, suggestion);
                }
            }

            suggestions.sort(Comparator.comparing(IndexSuggestion::getPriority).reversed());
            return Result.success(suggestions);

        } catch (JSQLParserException e) {
            return Result.failure("SQL解析失败: " + e.getMessage());
        } catch (Exception e) {
            return Result.failure("生成索引建议失败: " + e.getMessage());
        }
    }

    public void generateIndexSuggestionsAsync(String connectionId, String sql,
                                              Consumer<Result<List<IndexSuggestion>>> callback) {
        CompletableFuture.supplyAsync(() -> generateIndexSuggestions(connectionId, sql),
                Runnable::start).thenAccept(callback);
    }

    private void checkExistingIndex(Connection conn, IndexSuggestion suggestion) throws SQLException {
        String sql = "SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS " +
                "WHERE TABLE_NAME = ? AND COLUMN_NAME = ?";

        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, suggestion.getTableName());
            pstmt.setString(2, suggestion.getColumns().get(0));
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next() && rs.getInt(1) > 0) {
                    suggestion.setDescription("已存在索引，建议检查索引选择性");
                    suggestion.setPriority(suggestion.getPriority() - 2);
                }
            }
        } catch (SQLException e) {
            // 某些数据库不支持 INFORMATION_SCHEMA.STATISTICS
        }
    }

    public Result<List<String>> generateOptimizationTips(ExecutionPlan plan, String sql) {
        List<String> tips = new ArrayList<>();

        if (plan != null) {
            if (plan.hasFullTableScan()) {
                tips.add("⚠️ 存在全表扫描，考虑为过滤条件列添加索引");
            }

            if (plan.hasFilesort()) {
                tips.add("⚠️ 存在文件排序，考虑为ORDER BY列添加索引或优化查询");
            }

            if (plan.hasTemporaryTable()) {
                tips.add("⚠️ 使用了临时表，考虑优化GROUP BY或DISTINCT操作");
            }

            if (plan.getCost() > 10000) {
                tips.add(String.format("⚠️ 查询成本较高(%.0f)，考虑优化查询结构", plan.getCost()));
            }

            double scanRatio = plan.getRowsScanned() > 0 ?
                    (double) plan.getRowsScanned() / Math.max(plan.getRowsReturned(), 1) : 0;
            if (scanRatio > 10) {
                tips.add(String.format("⚠️ 扫描行数/返回行数比率较高(%.1f)，考虑添加更精确的过滤条件", scanRatio));
            }

            if (plan.hasNestedLoopJoin() && plan.getRowsScanned() > 10000) {
                tips.add("💡 嵌套循环连接在大数据集上可能性能不佳，考虑调整JOIN顺序或使用哈希连接");
            }
        }

        String upperSql = sql.trim().toUpperCase();
        if (upperSql.contains("SELECT *")) {
            tips.add("💡 避免使用SELECT *，只查询需要的列可以减少数据传输和内存使用");
        }

        if (upperSql.contains("LIKE '%") && upperSql.contains("%'")) {
            tips.add("💡 前导通配符LIKE查询无法使用索引，考虑使用全文搜索");
        }

        if (upperSql.contains("OR ") && !upperSql.contains("UNION")) {
            tips.add("💡 OR条件可能导致索引失效，考虑使用UNION替代");
        }

        if (upperSql.contains("GROUP BY") && upperSql.contains("ORDER BY")) {
            tips.add("💡 GROUP BY会自动排序，如果不需要排序可以使用ORDER BY NULL");
        }

        if (tips.isEmpty()) {
            tips.add("✅ 查询结构良好，未发现明显性能问题");
        }

        return Result.success(tips);
    }

    public void cancel() {
        cancelled.set(true);
    }
}
