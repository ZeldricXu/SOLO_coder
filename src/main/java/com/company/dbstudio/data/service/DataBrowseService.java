package com.company.dbstudio.data.service;

import com.company.dbstudio.connection.datasource.DataSourceRegistry;
import com.company.dbstudio.connection.model.ConnectionType;
import com.company.dbstudio.core.ApplicationContext;
import com.company.dbstudio.core.model.Result;
import com.company.dbstudio.core.util.StringUtils;
import com.company.dbstudio.data.model.RowChange;
import com.company.dbstudio.data.model.TableData;
import com.company.dbstudio.data.model.TableData.ColumnMetadata;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.sql.*;
import java.util.*;
import java.util.function.Consumer;

public class DataBrowseService {
    private final DataSourceRegistry dataSourceRegistry;

    public DataBrowseService() {
        this.dataSourceRegistry = ApplicationContext.getBean(DataSourceRegistry.class);
    }

    public Result<TableData> loadTableData(String connectionId, String tableName, String schemaName) {
        return loadTableData(connectionId, tableName, schemaName, 1, 100, null, null);
    }

    public Result<TableData> loadTableData(String connectionId, String tableName, String schemaName,
                                          int page, int pageSize, String whereClause, String orderByClause) {
        TableData tableData = new TableData(tableName, schemaName);
        tableData.setCurrentPage(page);
        tableData.setPageSize(pageSize);
        tableData.setWhereClause(whereClause);
        tableData.setOrderByClause(orderByClause);

        try (Connection conn = dataSourceRegistry.getConnection(connectionId)) {
            DatabaseMetaData metaData = conn.getMetaData();
            List<ColumnMetadata> columns = loadColumnMetadata(metaData, tableName, schemaName);
            tableData.setColumns(columns);

            Set<String> primaryKeys = loadPrimaryKeys(metaData, tableName, schemaName);
            for (ColumnMetadata col : columns) {
                if (primaryKeys.contains(col.getName())) {
                    columns.set(columns.indexOf(col), new ColumnMetadata(
                            col.getName(), col.getType(), col.getSqlType(),
                            col.getPrecision(), col.getScale(), col.isNullable(),
                            true, col.isAutoIncrement(), col.getDefaultValue(),
                            col.getComment(), col.isEditable()
                    ));
                }
            }

            Result<Long> countResult = countRows(conn, tableData);
            if (countResult.isSuccess()) {
                tableData.setTotalRows(countResult.getData());
            }

            Result<ObservableList<ObservableList<Object>>> rowsResult = 
                    loadRows(conn, tableData, primaryKeys);
            if (rowsResult.isSuccess()) {
                tableData.setRows(rowsResult.getData());
            }

            return Result.success(tableData);
        } catch (SQLException e) {
            return Result.failure("加载表数据失败: " + e.getMessage());
        }
    }

    private List<ColumnMetadata> loadColumnMetadata(DatabaseMetaData metaData, 
                                                   String tableName, String schemaName) throws SQLException {
        List<ColumnMetadata> columns = new ArrayList<>();
        try (ResultSet rs = metaData.getColumns(null, schemaName, tableName, null)) {
            while (rs.next()) {
                ColumnMetadata col = new ColumnMetadata(
                        rs.getString("COLUMN_NAME"),
                        rs.getString("TYPE_NAME"),
                        rs.getInt("DATA_TYPE"),
                        rs.getInt("COLUMN_SIZE"),
                        rs.getInt("DECIMAL_DIGITS"),
                        rs.getInt("NULLABLE") == DatabaseMetaData.columnNullable,
                        false,
                        "YES".equalsIgnoreCase(rs.getString("IS_AUTOINCREMENT")),
                        rs.getString("COLUMN_DEF"),
                        rs.getString("REMARKS"),
                        true
                );
                columns.add(col);
            }
        }
        return columns;
    }

    private Set<String> loadPrimaryKeys(DatabaseMetaData metaData, 
                                       String tableName, String schemaName) throws SQLException {
        Set<String> primaryKeys = new LinkedHashSet<>();
        try (ResultSet rs = metaData.getPrimaryKeys(null, schemaName, tableName)) {
            while (rs.next()) {
                primaryKeys.add(rs.getString("COLUMN_NAME"));
            }
        }
        return primaryKeys;
    }

    private Result<Long> countRows(Connection conn, TableData tableData) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM ");
        sql.append(tableData.getFullTableName());
        
        if (tableData.getWhereClause() != null && !tableData.getWhereClause().isEmpty()) {
            sql.append(" WHERE ").append(tableData.getWhereClause());
        }

        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql.toString())) {
            if (rs.next()) {
                return Result.success(rs.getLong(1));
            }
            return Result.failure("无法获取行数");
        } catch (SQLException e) {
            return Result.failure("计数查询失败: " + e.getMessage());
        }
    }

    private Result<ObservableList<ObservableList<Object>>> loadRows(Connection conn, TableData tableData,
                                                                   Set<String> primaryKeys) {
        StringBuilder sql = new StringBuilder("SELECT ");
        
        List<ColumnMetadata> columns = tableData.getColumns();
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) sql.append(", ");
            sql.append(columns.get(i).getName());
        }
        
        sql.append(" FROM ").append(tableData.getFullTableName());
        
        if (tableData.getWhereClause() != null && !tableData.getWhereClause().isEmpty()) {
            sql.append(" WHERE ").append(tableData.getWhereClause());
        }
        
        if (tableData.getOrderByClause() != null && !tableData.getOrderByClause().isEmpty()) {
            sql.append(" ORDER BY ").append(tableData.getOrderByClause());
        }

        ConnectionType type = ConnectionType.MYSQL;
        try {
            type = ConnectionType.valueOf(conn.getMetaData().getDatabaseProductName().toUpperCase());
        } catch (Exception ignored) {
        }

        int offset = (tableData.getCurrentPage() - 1) * tableData.getPageSize();
        int limit = tableData.getPageSize();

        sql = switch (type) {
            case MYSQL, POSTGRESQL, THRIFT -> 
                sql.append(" LIMIT ").append(limit).append(" OFFSET ").append(offset);
            case ORACLE -> 
                new StringBuilder("SELECT * FROM (SELECT a.*, ROWNUM rnum FROM (")
                        .append(sql).append(") a WHERE ROWNUM <= ").append(offset + limit)
                        .append(") WHERE rnum > ").append(offset);
            case SQL_SERVER -> 
                sql.append(" OFFSET ").append(offset).append(" ROWS FETCH NEXT ")
                        .append(limit).append(" ROWS ONLY");
        };

        ObservableList<ObservableList<Object>> rows = FXCollections.observableArrayList();
        
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql.toString())) {
            
            while (rs.next()) {
                ObservableList<Object> row = FXCollections.observableArrayList();
                for (int i = 0; i < columns.size(); i++) {
                    ColumnMetadata col = columns.get(i);
                    Object value = rs.getObject(i + 1);
                    
                    if (col.isBlobType() && value instanceof byte[] bytes) {
                        value = StringUtils.formatBytes(bytes.length);
                    } else if (col.isClobType() && value instanceof java.sql.Clob clob) {
                        value = clob.getSubString(1, 100) + "...";
                    }
                    
                    row.add(value);
                }
                rows.add(row);
            }
            
            return Result.success(rows);
        } catch (SQLException e) {
            return Result.failure("加载数据失败: " + e.getMessage());
        }
    }

    public void loadTableDataAsync(String connectionId, String tableName, String schemaName,
                                   Consumer<Result<TableData>> callback) {
        ApplicationContext.executeAsync(() -> {
            Result<TableData> result = loadTableData(connectionId, tableName, schemaName);
            Platform.runLater(() -> callback.accept(result));
        });
    }

    public void loadTableDataAsync(String connectionId, TableData tableData,
                                   Consumer<Result<TableData>> callback) {
        ApplicationContext.executeAsync(() -> {
            Result<TableData> result = loadTableData(
                    connectionId,
                    tableData.getTableName(),
                    tableData.getSchemaName(),
                    tableData.getCurrentPage(),
                    tableData.getPageSize(),
                    tableData.getWhereClause(),
                    tableData.getOrderByClause()
            );
            if (result.isSuccess()) {
                TableData newData = result.getData();
                tableData.setRows(newData.getRows());
                tableData.setTotalRows(newData.getTotalRows());
                tableData.setColumns(newData.getColumns());
                result = Result.success(tableData);
            }
            Platform.runLater(() -> callback.accept(result));
        });
    }

    public Result<Integer> applyChanges(String connectionId, List<RowChange> changes) {
        if (changes == null || changes.isEmpty()) {
            return Result.success(0);
        }

        try (Connection conn = dataSourceRegistry.getConnection(connectionId)) {
            conn.setAutoCommit(false);
            int totalAffected = 0;

            try {
                for (RowChange change : changes) {
                    if (!change.hasChanges()) {
                        continue;
                    }

                    String sql = change.generateSql();
                    try (Statement stmt = conn.createStatement()) {
                        int affected = stmt.executeUpdate(sql);
                        totalAffected += affected;
                    }
                }

                conn.commit();
                return Result.success(totalAffected);
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            return Result.failure("应用更改失败: " + e.getMessage());
        }
    }

    public void applyChangesAsync(String connectionId, List<RowChange> changes,
                                  Consumer<Result<Integer>> callback) {
        ApplicationContext.executeAsync(() -> {
            Result<Integer> result = applyChanges(connectionId, changes);
            Platform.runLater(() -> callback.accept(result));
        });
    }

    public Result<byte[]> loadBlobData(String connectionId, String tableName, String columnName,
                                       String whereClause) {
        String sql = "SELECT " + columnName + " FROM " + tableName + 
                     (whereClause != null ? " WHERE " + whereClause : "");
        
        try (Connection conn = dataSourceRegistry.getConnection(connectionId);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            
            if (rs.next()) {
                byte[] data = rs.getBytes(1);
                if (data != null) {
                    return Result.success(data);
                }
            }
            return Result.failure("未找到BLOB数据");
        } catch (SQLException e) {
            return Result.failure("加载BLOB数据失败: " + e.getMessage());
        }
    }

    public Result<String> loadClobData(String connectionId, String tableName, String columnName,
                                       String whereClause) {
        String sql = "SELECT " + columnName + " FROM " + tableName +
                     (whereClause != null ? " WHERE " + whereClause : "");
        
        try (Connection conn = dataSourceRegistry.getConnection(connectionId);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            
            if (rs.next()) {
                java.sql.Clob clob = rs.getClob(1);
                if (clob != null) {
                    return Result.success(clob.getSubString(1, (int) clob.length()));
                }
            }
            return Result.failure("未找到CLOB数据");
        } catch (SQLException e) {
            return Result.failure("加载CLOB数据失败: " + e.getMessage());
        }
    }

    public Result<String> loadJsonData(String connectionId, String tableName, String columnName,
                                       String whereClause) {
        return loadClobData(connectionId, tableName, columnName, whereClause);
    }

    public Result<List<String>> getTableNames(String connectionId, String schemaName) {
        List<String> tables = new ArrayList<>();
        
        try (Connection conn = dataSourceRegistry.getConnection(connectionId)) {
            DatabaseMetaData metaData = conn.getMetaData();
            try (ResultSet rs = metaData.getTables(null, schemaName, "%", 
                    new String[]{"TABLE", "VIEW"})) {
                while (rs.next()) {
                    tables.add(rs.getString("TABLE_NAME"));
                }
            }
            Collections.sort(tables);
            return Result.success(tables);
        } catch (SQLException e) {
            return Result.failure("获取表列表失败: " + e.getMessage());
        }
    }

    public Result<List<String>> getSchemaNames(String connectionId) {
        List<String> schemas = new ArrayList<>();
        
        try (Connection conn = dataSourceRegistry.getConnection(connectionId)) {
            DatabaseMetaData metaData = conn.getMetaData();
            try (ResultSet rs = metaData.getSchemas()) {
                while (rs.next()) {
                    schemas.add(rs.getString("TABLE_SCHEM"));
                }
            }
            Collections.sort(schemas);
            return Result.success(schemas);
        } catch (SQLException e) {
            return Result.failure("获取模式列表失败: " + e.getMessage());
        }
    }

    public void getSchemaNamesAsync(String connectionId, Consumer<Result<List<String>>> callback) {
        ApplicationContext.executeAsync(() -> {
            Result<List<String>> result = getSchemaNames(connectionId);
            Platform.runLater(() -> callback.accept(result));
        });
    }

    public void getTableNamesAsync(String connectionId, String schemaName,
                                   Consumer<Result<List<String>>> callback) {
        ApplicationContext.executeAsync(() -> {
            Result<List<String>> result = getTableNames(connectionId, schemaName);
            Platform.runLater(() -> callback.accept(result));
        });
    }

    public void loadBlobDataAsync(String connectionId, String tableName, String columnName,
                                  String whereClause, Consumer<Result<byte[]>> callback) {
        ApplicationContext.executeAsync(() -> {
            Result<byte[]> result = loadBlobData(connectionId, tableName, columnName, whereClause);
            Platform.runLater(() -> callback.accept(result));
        });
    }

    public void loadJsonDataAsync(String connectionId, String tableName, String columnName,
                                  String whereClause, Consumer<Result<String>> callback) {
        ApplicationContext.executeAsync(() -> {
            Result<String> result = loadJsonData(connectionId, tableName, columnName, whereClause);
            Platform.runLater(() -> callback.accept(result));
        });
    }
}
