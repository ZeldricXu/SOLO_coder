package com.cdcsync.metadata.crawler;

import com.alibaba.fastjson2.JSON;
import com.cdcsync.common.exception.BusinessException;
import com.cdcsync.common.util.ValidationUtils;
import com.cdcsync.metadata.domain.DataSource;
import lombok.extern.slf4j.Slf4j;

import java.sql.*;
import java.util.*;
import java.util.regex.Pattern;

@Slf4j
class JdbcMetadataProvider {

    private static final Pattern SAFE_IDENTIFIER_PATTERN = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_$]*$");
    private static final int MAX_IDENTIFIER_LENGTH = 128;
    private static final int DEFAULT_QUERY_TIMEOUT_SECONDS = 30;
    private static final int DEFAULT_CONNECTION_TIMEOUT_SECONDS = 10;

    private final DataSource dataSource;

    JdbcMetadataProvider(DataSource dataSource) {
        ValidationUtils.notNull(dataSource, "dataSource");
        ValidationUtils.notBlank(dataSource.getHost(), "dataSource.host");
        ValidationUtils.notNull(dataSource.getPort(), "dataSource.port");
        ValidationUtils.validPort(dataSource.getPort());
        ValidationUtils.notBlank(dataSource.getDatabaseName(), "dataSource.databaseName");
        this.dataSource = dataSource;
    }

    Connection getConnection() throws SQLException {
        String url = buildJdbcUrl();
        Properties props = new Properties();
        if (dataSource.getUsername() != null) {
            props.setProperty("user", dataSource.getUsername());
        }
        if (dataSource.getPassword() != null) {
            props.setProperty("password", dataSource.getPassword());
        }
        props.setProperty("connectTimeout", String.valueOf(DEFAULT_CONNECTION_TIMEOUT_SECONDS * 1000));
        props.setProperty("socketTimeout", String.valueOf(DEFAULT_CONNECTION_TIMEOUT_SECONDS * 1000));

        Connection conn = DriverManager.getConnection(url, props);
        try {
            conn.setNetworkTimeout(java.util.concurrent.Executors.newSingleThreadExecutor(),
                    DEFAULT_QUERY_TIMEOUT_SECONDS * 1000);
            return conn;
        } catch (SQLException e) {
            closeQuietly(conn);
            throw e;
        }
    }

    private String buildJdbcUrl() {
        String host = ValidationUtils.safeTrim(dataSource.getHost());
        String dbName = ValidationUtils.safeTrim(dataSource.getDatabaseName());
        return String.format("jdbc:mysql://%s:%d/%s?useSSL=false&serverTimezone=UTC",
                host, dataSource.getPort(), dbName);
    }

    private void validateTableName(String tableName) {
        ValidationUtils.notBlank(tableName, "tableName");
        ValidationUtils.maxLength(tableName, MAX_IDENTIFIER_LENGTH, "tableName");
        if (!SAFE_IDENTIFIER_PATTERN.matcher(tableName).matches()) {
            throw new BusinessException(400, "Invalid table name: contains unsafe characters");
        }
    }

    List<String> listTableNames(Connection conn) throws SQLException {
        ValidationUtils.notNull(conn, "connection");

        List<String> tables = new ArrayList<>();
        DatabaseMetaData metaData = conn.getMetaData();
        try (ResultSet rs = metaData.getTables(dataSource.getDatabaseName(), null, "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                String tableName = rs.getString("TABLE_NAME");
                if (tableName != null && !tableName.trim().isEmpty()) {
                    tables.add(tableName);
                }
            }
        }
        return tables;
    }

    List<Map<String, Object>> getColumns(Connection conn, String tableName) throws SQLException {
        ValidationUtils.notNull(conn, "connection");
        validateTableName(tableName);

        List<Map<String, Object>> columns = new ArrayList<>();
        DatabaseMetaData metaData = conn.getMetaData();
        try (ResultSet rs = metaData.getColumns(dataSource.getDatabaseName(), null, tableName, null)) {
            while (rs.next()) {
                Map<String, Object> column = new LinkedHashMap<>();
                String columnName = rs.getString("COLUMN_NAME");
                column.put("name", columnName != null ? ValidationUtils.safeTruncate(columnName, MAX_IDENTIFIER_LENGTH) : null);
                column.put("type", rs.getString("TYPE_NAME"));
                column.put("size", rs.getInt("COLUMN_SIZE"));
                column.put("nullable", rs.getInt("NULLABLE") == DatabaseMetaData.columnNullable);
                column.put("primaryKey", false);
                column.put("autoIncrement", "YES".equals(rs.getString("IS_AUTOINCREMENT")));
                String remarks = rs.getString("REMARKS");
                column.put("comment", remarks != null ? ValidationUtils.safeTruncate(remarks, 1024) : null);
                columns.add(column);
            }
        }
        markPrimaryKeys(conn, tableName, columns);
        return columns;
    }

    private void markPrimaryKeys(Connection conn, String tableName, List<Map<String, Object>> columns) throws SQLException {
        ValidationUtils.notNull(columns, "columns");

        DatabaseMetaData metaData = conn.getMetaData();
        Set<String> pkColumns = new HashSet<>();
        try (ResultSet rs = metaData.getPrimaryKeys(dataSource.getDatabaseName(), null, tableName)) {
            while (rs.next()) {
                String colName = rs.getString("COLUMN_NAME");
                if (colName != null) {
                    pkColumns.add(colName);
                }
            }
        }
        for (Map<String, Object> column : columns) {
            if (pkColumns.contains(column.get("name"))) {
                column.put("primaryKey", true);
            }
        }
    }

    List<Map<String, Object>> getIndexes(Connection conn, String tableName) throws SQLException {
        ValidationUtils.notNull(conn, "connection");
        validateTableName(tableName);

        List<Map<String, Object>> indexes = new ArrayList<>();
        DatabaseMetaData metaData = conn.getMetaData();
        Map<String, Map<String, Object>> indexMap = new LinkedHashMap<>();

        try (ResultSet rs = metaData.getIndexInfo(dataSource.getDatabaseName(), null, tableName, false, false)) {
            while (rs.next()) {
                String indexName = rs.getString("INDEX_NAME");
                if (indexName == null) continue;

                Map<String, Object> index = indexMap.computeIfAbsent(indexName, k -> {
                    Map<String, Object> idx = new LinkedHashMap<>();
                    idx.put("name", k);
                    idx.put("unique", !rs.getBoolean("NON_UNIQUE"));
                    idx.put("type", rs.getString("INDEX_TYPE"));
                    idx.put("columns", new ArrayList<String>());
                    return idx;
                });
                String columnName = rs.getString("COLUMN_NAME");
                if (columnName != null) {
                    ((List<String>) index.get("columns")).add(columnName);
                }
            }
        }
        indexes.addAll(indexMap.values());
        return indexes;
    }

    String getColumnsJson(Connection conn, String tableName) throws SQLException {
        return JSON.toJSONString(getColumns(conn, tableName));
    }

    String getIndexesJson(Connection conn, String tableName) throws SQLException {
        return JSON.toJSONString(getIndexes(conn, tableName));
    }

    long getRowCount(Connection conn, String tableName) throws SQLException {
        ValidationUtils.notNull(conn, "connection");
        validateTableName(tableName);

        String sql = "SELECT COUNT(*) FROM `" + tableName + "`";
        try (Statement stmt = conn.createStatement()) {
            stmt.setQueryTimeout(DEFAULT_QUERY_TIMEOUT_SECONDS);
            try (ResultSet rs = stmt.executeQuery(sql)) {
                if (rs.next()) {
                    return Math.max(0, rs.getLong(1));
                }
            }
        } catch (SQLException e) {
            log.warn("Failed to get row count for table {}: {}", tableName, e.getMessage());
            return 0;
        }
        return 0;
    }

    long getTableSize(Connection conn, String tableName) throws SQLException {
        ValidationUtils.notNull(conn, "connection");
        validateTableName(tableName);

        String sql = "SELECT (data_length + index_length) AS size FROM information_schema.tables " +
                "WHERE table_schema = ? AND table_name = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setQueryTimeout(DEFAULT_QUERY_TIMEOUT_SECONDS);
            stmt.setString(1, dataSource.getDatabaseName());
            stmt.setString(2, tableName);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Math.max(0, rs.getLong("size"));
                }
            }
        } catch (SQLException e) {
            log.warn("Failed to get table size for {}: {}", tableName, e.getMessage());
            return 0;
        }
        return 0;
    }

    List<Map<String, Object>> getSampleData(Connection conn, String tableName, int limit) throws SQLException {
        ValidationUtils.notNull(conn, "connection");
        validateTableName(tableName);

        int safeLimit = ValidationUtils.validLimit(limit);
        List<Map<String, Object>> data = new ArrayList<>();

        String sql = "SELECT * FROM `" + tableName + "` LIMIT " + safeLimit;
        try (Statement stmt = conn.createStatement()) {
            stmt.setQueryTimeout(DEFAULT_QUERY_TIMEOUT_SECONDS);
            try (ResultSet rs = stmt.executeQuery(sql)) {
                ResultSetMetaData metaData = rs.getMetaData();
                int columnCount = metaData.getColumnCount();

                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= columnCount; i++) {
                        String columnName = metaData.getColumnName(i);
                        Object value = rs.getObject(i);

                        if (value instanceof String strValue) {
                            value = ValidationUtils.safeTruncate(strValue, 8192);
                        } else if (value instanceof byte[] bytesValue) {
                            value = "[binary data, length=" + bytesValue.length + "]";
                        }

                        row.put(columnName, value);
                    }
                    data.add(row);
                }
            }
        }
        return data;
    }

    String getSampleDataJson(Connection conn, String tableName, int limit) throws SQLException {
        return JSON.toJSONString(getSampleData(conn, tableName, limit));
    }

    static void closeQuietly(AutoCloseable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Exception e) {
                log.debug("Failed to close resource: {}", e.getMessage());
            }
        }
    }
}
