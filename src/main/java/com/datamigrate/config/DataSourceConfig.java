package com.datamigrate.config;

import com.datamigrate.entity.MigrateTask;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class DataSourceConfig {

    private final Map<String, Connection> connectionCache = new ConcurrentHashMap<>();

    public Connection getSourceConnection(MigrateTask task) throws Exception {
        String cacheKey = "source_" + task.getTaskId();
        if (connectionCache.containsKey(cacheKey)) {
            Connection conn = connectionCache.get(cacheKey);
            if (conn != null && !conn.isClosed()) {
                return conn;
            }
        }
        Connection conn = createConnection(
            task.getSourceType(),
            task.getSourceHost(),
            task.getSourcePort(),
            task.getSourceDatabase(),
            task.getSourceUsername(),
            task.getSourcePassword()
        );
        connectionCache.put(cacheKey, conn);
        return conn;
    }

    public Connection getTargetConnection(MigrateTask task) throws Exception {
        String cacheKey = "target_" + task.getTaskId();
        if (connectionCache.containsKey(cacheKey)) {
            Connection conn = connectionCache.get(cacheKey);
            if (conn != null && !conn.isClosed()) {
                return conn;
            }
        }
        Connection conn = createConnection(
            task.getTargetType(),
            task.getTargetHost(),
            task.getTargetPort(),
            task.getTargetDatabase(),
            task.getTargetUsername(),
            task.getTargetPassword()
        );
        connectionCache.put(cacheKey, conn);
        return conn;
    }

    private Connection createConnection(String dbType, String host, Integer port, 
                                       String database, String username, String password) throws Exception {
        String url = buildJdbcUrl(dbType, host, port, database);
        String driverClass = getDriverClass(dbType);
        try {
            Class.forName(driverClass);
            return DriverManager.getConnection(url, username, password);
        } catch (Exception e) {
            log.error("Failed to create connection: dbType={}, host={}, database={}", dbType, host, database, e);
            throw e;
        }
    }

    private String buildJdbcUrl(String dbType, String host, Integer port, String database) {
        if (port == null) {
            port = getDefaultPort(dbType);
        }
        switch (dbType.toLowerCase()) {
            case "mysql":
                return String.format("jdbc:mysql://%s:%d/%s?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true",
                    host, port, database);
            case "postgresql":
                return String.format("jdbc:postgresql://%s:%d/%s", host, port, database);
            case "h2":
                return String.format("jdbc:h2:mem:%s;DB_CLOSE_DELAY=-1", database);
            default:
                return String.format("jdbc:%s://%s:%d/%s", dbType, host, port, database);
        }
    }

    private int getDefaultPort(String dbType) {
        switch (dbType.toLowerCase()) {
            case "mysql":
                return 3306;
            case "postgresql":
                return 5432;
            case "h2":
                return 8082;
            default:
                return 3306;
        }
    }

    private String getDriverClass(String dbType) {
        switch (dbType.toLowerCase()) {
            case "mysql":
                return "com.mysql.cj.jdbc.Driver";
            case "postgresql":
                return "org.postgresql.Driver";
            case "h2":
                return "org.h2.Driver";
            default:
                return "com.mysql.cj.jdbc.Driver";
        }
    }

    public void closeConnections(String taskId) {
        String sourceKey = "source_" + taskId;
        String targetKey = "target_" + taskId;
        
        closeQuietly(connectionCache.remove(sourceKey));
        closeQuietly(connectionCache.remove(targetKey));
    }

    private void closeQuietly(Connection conn) {
        if (conn != null) {
            try {
                conn.close();
            } catch (Exception e) {
                log.warn("Error closing connection", e);
            }
        }
    }

    public void closeAllConnections() {
        connectionCache.values().forEach(this::closeQuietly);
        connectionCache.clear();
    }
}
