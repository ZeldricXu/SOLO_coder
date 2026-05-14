package com.datasync.service.datasource.impl;

import com.datasync.common.Constants;
import com.datasync.model.DataSourceConfig;
import com.datasync.service.datasource.DataSourceAdapter;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.*;

public class JdbcDataSourceAdapter implements DataSourceAdapter {

    private static final Logger logger = LoggerFactory.getLogger(JdbcDataSourceAdapter.class);

    private HikariDataSource dataSource;
    private String type;
    private boolean connected = false;

    @Override
    public void connect(DataSourceConfig config) throws Exception {
        String host = config.getHost();
        Integer port = config.getPort();
        String database = config.getDatabase();
        String user = config.getUser();
        String password = config.getPassword();
        this.type = config.getSourceType();

        String jdbcUrl;
        String driverClass;

        if (Constants.DATA_SOURCE_TYPE_MYSQL.equals(type)) {
            jdbcUrl = String.format("jdbc:mysql://%s:%d/%s?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true",
                    host, port != null ? port : 3306, database);
            driverClass = "com.mysql.cj.jdbc.Driver";
        } else if (Constants.DATA_SOURCE_TYPE_POSTGRESQL.equals(type)) {
            jdbcUrl = String.format("jdbc:postgresql://%s:%d/%s",
                    host, port != null ? port : 5432, database);
            driverClass = "org.postgresql.Driver";
        } else if (Constants.DATA_SOURCE_TYPE_ORACLE.equals(type)) {
            jdbcUrl = String.format("jdbc:oracle:thin:@%s:%d:%s",
                    host, port != null ? port : 1521, database);
            driverClass = "oracle.jdbc.OracleDriver";
        } else {
            throw new IllegalArgumentException("Unsupported JDBC data source type: " + type);
        }

        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(jdbcUrl);
        hikariConfig.setUsername(user);
        hikariConfig.setPassword(password);
        hikariConfig.setDriverClassName(driverClass);
        hikariConfig.setMaximumPoolSize(10);
        hikariConfig.setMinimumIdle(2);
        hikariConfig.setConnectionTimeout(30000);
        hikariConfig.setIdleTimeout(600000);

        this.dataSource = new HikariDataSource(hikariConfig);
        this.connected = true;
        logger.info("Connected to {} data source: {}", type, config.getSourceId());
    }

    @Override
    public void disconnect() {
        if (dataSource != null) {
            dataSource.close();
            connected = false;
            logger.info("Disconnected from data source");
        }
    }

    @Override
    public boolean isConnected() {
        return connected && dataSource != null && !dataSource.isClosed();
    }

    @Override
    public List<Map<String, Object>> readData(String tableName, String filterRule, String dataKeyField) throws Exception {
        List<Map<String, Object>> results = new ArrayList<>();
        String sql = "SELECT * FROM " + tableName;
        if (filterRule != null && !filterRule.isEmpty()) {
            sql += " WHERE " + filterRule;
        }
        if (dataKeyField != null && !dataKeyField.isEmpty()) {
            sql += " ORDER BY " + dataKeyField;
        }

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            ResultSetMetaData metaData = rs.getMetaData();
            int columnCount = metaData.getColumnCount();

            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= columnCount; i++) {
                    String columnName = metaData.getColumnName(i);
                    Object value = rs.getObject(i);
                    row.put(columnName, value);
                }
                results.add(row);
            }
        }
        logger.debug("Read {} records from {}", results.size(), tableName);
        return results;
    }

    @Override
    public Map<String, Object> readSingle(String tableName, String dataKeyField, String dataKey) throws Exception {
        String sql = "SELECT * FROM " + tableName + " WHERE " + dataKeyField + " = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setObject(1, dataKey);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    ResultSetMetaData metaData = rs.getMetaData();
                    int columnCount = metaData.getColumnCount();
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= columnCount; i++) {
                        String columnName = metaData.getColumnName(i);
                        Object value = rs.getObject(i);
                        row.put(columnName, value);
                    }
                    return row;
                }
            }
        }
        return null;
    }

    @Override
    public void writeData(String tableName, String dataKeyField, Map<String, Object> data) throws Exception {
        if (exists(tableName, dataKeyField, String.valueOf(data.get(dataKeyField)))) {
            updateData(tableName, dataKeyField, String.valueOf(data.get(dataKeyField)), data);
            return;
        }

        List<String> columns = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        List<String> placeholders = new ArrayList<>();

        for (Map.Entry<String, Object> entry : data.entrySet()) {
            columns.add(entry.getKey());
            values.add(entry.getValue());
            placeholders.add("?");
        }

        String sql = "INSERT INTO " + tableName + " (" + String.join(", ", columns) +
                ") VALUES (" + String.join(", ", placeholders) + ")";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            for (int i = 0; i < values.size(); i++) {
                stmt.setObject(i + 1, values.get(i));
            }
            stmt.executeUpdate();
        }
    }

    @Override
    public void batchWrite(String tableName, String dataKeyField, List<Map<String, Object>> dataList) throws Exception {
        for (Map<String, Object> data : dataList) {
            writeData(tableName, dataKeyField, data);
        }
    }

    @Override
    public void updateData(String tableName, String dataKeyField, String dataKey, Map<String, Object> data) throws Exception {
        List<String> setClauses = new ArrayList<>();
        List<Object> values = new ArrayList<>();

        for (Map.Entry<String, Object> entry : data.entrySet()) {
            if (!entry.getKey().equals(dataKeyField)) {
                setClauses.add(entry.getKey() + " = ?");
                values.add(entry.getValue());
            }
        }

        if (setClauses.isEmpty()) {
            return;
        }

        values.add(dataKey);
        String sql = "UPDATE " + tableName + " SET " + String.join(", ", setClauses) +
                " WHERE " + dataKeyField + " = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            for (int i = 0; i < values.size(); i++) {
                stmt.setObject(i + 1, values.get(i));
            }
            stmt.executeUpdate();
        }
    }

    @Override
    public void deleteData(String tableName, String dataKeyField, String dataKey) throws Exception {
        String sql = "DELETE FROM " + tableName + " WHERE " + dataKeyField + " = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setObject(1, dataKey);
            stmt.executeUpdate();
        }
    }

    @Override
    public boolean exists(String tableName, String dataKeyField, String dataKey) throws Exception {
        String sql = "SELECT 1 FROM " + tableName + " WHERE " + dataKeyField + " = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setObject(1, dataKey);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        }
    }

    @Override
    public String getType() {
        return type;
    }
}
