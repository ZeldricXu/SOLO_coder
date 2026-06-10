package com.company.dbstudio.connection;

import com.company.dbstudio.connection.datasource.DataSourceRegistry;
import com.company.dbstudio.connection.model.ConnectionConfig;
import com.company.dbstudio.connection.model.ConnectionInfo;
import com.company.dbstudio.connection.ssh.SshTunnelHealthChecker;
import com.company.dbstudio.connection.ssh.SshTunnelManager;
import com.company.dbstudio.connection.ssh.SshTunnelManager.SshTunnel;
import com.company.dbstudio.core.ApplicationContext;
import com.company.dbstudio.core.EventBus;
import com.company.dbstudio.core.model.Result;
import com.zaxxer.hikari.HikariDataSource;
import javafx.application.Platform;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public class ConnectionManager implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(ConnectionManager.class);
    private static final int MAX_QUEUED_QUERIES = 100;

    private final ConnectionRepository repository;
    private final DataSourceRegistry dataSourceRegistry;
    private final SshTunnelManager tunnelManager;
    private final EventBus eventBus;
    private final SshTunnelHealthChecker healthChecker;

    private final Map<String, ConnectionInfo> activeConnections = new ConcurrentHashMap<>();
    private final ObservableList<ConnectionInfo> activeConnectionsList =
            FXCollections.observableArrayList();
    private final SimpleObjectProperty<ConnectionInfo> currentConnection =
            new SimpleObjectProperty<>();

    private final Map<String, AtomicBoolean> reconnectingFlags = new ConcurrentHashMap<>();
    private final Map<String, Queue<Runnable>> pendingQueries = new ConcurrentHashMap<>();

    public ConnectionManager() {
        this.repository = new ConnectionRepository();
        this.dataSourceRegistry = DataSourceRegistry.getInstance();
        this.tunnelManager = SshTunnelManager.getInstance();
        this.eventBus = EventBus.getInstance();
        this.healthChecker = SshTunnelHealthChecker.getInstance();
    }

    public ConnectionRepository getRepository() {
        return repository;
    }

    public ObservableList<ConnectionInfo> getActiveConnectionsList() {
        return activeConnectionsList;
    }

    public SimpleObjectProperty<ConnectionInfo> currentConnectionProperty() {
        return currentConnection;
    }

    public ConnectionInfo getCurrentConnection() {
        return currentConnection.get();
    }

    public void setCurrentConnection(ConnectionInfo connection) {
        currentConnection.set(connection);
        if (connection != null) {
            repository.markRecent(connection.getConnectionId());
        }
    }

    public Optional<ConnectionInfo> getActiveConnection(String connectionId) {
        return Optional.ofNullable(activeConnections.get(connectionId));
    }

    public boolean isConnected(String connectionId) {
        ConnectionInfo info = activeConnections.get(connectionId);
        return info != null && info.isConnected();
    }

    public boolean isReconnecting(String connectionId) {
        AtomicBoolean flag = reconnectingFlags.get(connectionId);
        return flag != null && flag.get();
    }

    public void connectAsync(ConnectionConfig config,
                            Consumer<Result<ConnectionInfo>> callback) {
        ApplicationContext.executeAsync(() -> {
            Result<ConnectionInfo> result = connect(config);
            Platform.runLater(() -> callback.accept(result));
        });
    }

    public Result<ConnectionInfo> connect(ConnectionConfig config) {
        try {
            logger.info("Connecting to: {} ({})", config.getName(), config.getType());

            SshTunnel tunnel = null;
            if (config.getSshConfig() != null && config.getSshConfig().isEnabled()) {
                config.getSshConfig().setRemotePort(config.getPort());
                config.getSshConfig().setRemoteHost(config.getHost());
                tunnel = tunnelManager.createTunnel(config.getId(), config.getSshConfig());
                if (tunnel == null) {
                    return Result.failure("Failed to create SSH tunnel");
                }
                config = config.copy();
                config.setHost("localhost");
                config.setPort(tunnel.getLocalPort());
                logger.debug("SSH tunnel established on port: {}", tunnel.getLocalPort());
            }

            String jdbcUrl = config.getJdbcUrl();
            dataSourceRegistry.createDataSource(config.getId(), config, jdbcUrl);

            try (Connection testConn = dataSourceRegistry.getConnection(config.getId())) {
                if (!testConn.isValid(2)) {
                    dataSourceRegistry.removeDataSource(config.getId());
                    return Result.failure("Connection validation failed");
                }
            }

            ConnectionInfo info = new ConnectionInfo(config.getId(), config);
            activeConnections.put(config.getId(), info);
            activeConnectionsList.add(info);
            currentConnection.set(info);

            if (config.getSshConfig() != null && config.getSshConfig().isEnabled()) {
                setupSshReconnectListener(config.getId(), config);
            }

            repository.markRecent(config.getId());

            eventBus.publish(new EventBus.ConnectionCreatedEvent(config.getId()));
            eventBus.publish(new EventBus.StatusMessageEvent(
                    "Connected to " + config.getName(),
                    EventBus.StatusMessageEvent.MessageType.SUCCESS));

            logger.info("Successfully connected to: {}", config.getName());
            return Result.success(info);

        } catch (Exception e) {
            logger.error("Failed to connect to: {}", config.getName(), e);
            dataSourceRegistry.removeDataSource(config.getId());
            tunnelManager.closeTunnel(config.getId());
            return Result.failure(e);
        }
    }

    private void setupSshReconnectListener(String connectionId, ConnectionConfig config) {
        healthChecker.addReconnectListener(connectionId, event -> {
            logger.info("SSH重连事件: {}", event);

            Platform.runLater(() -> {
                if (event.isSuccess()) {
                    reconnectingFlags.remove(connectionId);
                    logger.info("SSH重连成功，重建连接池: {}", connectionId);

                    try {
                        SshTunnel tunnel = tunnelManager.getTunnel(connectionId);
                        if (tunnel != null) {
                            ConnectionConfig newConfig = config.copy();
                            newConfig.setHost("localhost");
                            newConfig.setPort(tunnel.getLocalPort());
                            newConfig.setSshConfig(tunnel.getConfig());

                            dataSourceRegistry.removeDataSource(connectionId);
                            dataSourceRegistry.createDataSource(connectionId, newConfig, newConfig.getJdbcUrl());

                            logger.info("连接池已重建: {}", connectionId);

                            eventBus.publish(new EventBus.StatusMessageEvent(
                                    "SSH连接已恢复",
                                    EventBus.StatusMessageEvent.MessageType.SUCCESS));

                            processPendingQueries(connectionId);
                        }
                    } catch (Exception e) {
                        logger.error("重建连接池失败", e);
                    }
                } else {
                    if (event.getAttempt() == 1) {
                        reconnectingFlags.putIfAbsent(connectionId, new AtomicBoolean(true));
                        reconnectingFlags.get(connectionId).set(true);

                        eventBus.publish(new EventBus.StatusMessageEvent(
                                "SSH连接断开，正在自动重连... (" + event.getAttempt() + "/" + event.getMaxAttempts() + ")",
                                EventBus.StatusMessageEvent.MessageType.WARNING));
                    } else {
                        eventBus.publish(new EventBus.StatusMessageEvent(
                                "SSH重连中... (" + event.getAttempt() + "/" + event.getMaxAttempts() + ")",
                                EventBus.StatusMessageEvent.MessageType.WARNING));
                    }

                    if (event.getAttempt() >= event.getMaxAttempts()) {
                        reconnectingFlags.remove(connectionId);
                        eventBus.publish(new EventBus.StatusMessageEvent(
                                "SSH连接断开，重连失败，请手动重新连接",
                                EventBus.StatusMessageEvent.MessageType.ERROR));
                    }
                }
            });
        });
    }

    public void queueQuery(String connectionId, Runnable query) {
        Queue<Runnable> queue = pendingQueries.computeIfAbsent(
                connectionId, k -> new ConcurrentLinkedQueue<>());

        if (queue.size() >= MAX_QUEUED_QUERIES) {
            logger.warn("查询队列已满，丢弃请求: {}", connectionId);
            return;
        }

        queue.offer(query);
        logger.debug("查询已入队，队列大小: {} - {}", queue.size(), connectionId);
    }

    private void processPendingQueries(String connectionId) {
        Queue<Runnable> queue = pendingQueries.get(connectionId);
        if (queue == null || queue.isEmpty()) {
            return;
        }

        logger.info("处理排队的查询，队列大小: {} - {}", queue.size(), connectionId);

        int processed = 0;
        while (!queue.isEmpty()) {
            Runnable query = queue.poll();
            if (query != null) {
                try {
                    query.run();
                    processed++;
                } catch (Exception e) {
                    logger.error("执行排队查询失败", e);
                }
            }
        }

        logger.info("已处理 {} 个排队查询 - {}", processed, connectionId);
    }

    public void disconnect(String connectionId) {
        logger.info("Disconnecting: {}", connectionId);

        reconnectingFlags.remove(connectionId);
        pendingQueries.remove(connectionId);
        healthChecker.removeReconnectListener(connectionId);

        ConnectionInfo info = activeConnections.remove(connectionId);
        if (info != null) {
            info.setConnected(false);
            activeConnectionsList.remove(info);
            if (currentConnection.get() != null
                    && currentConnection.get().getConnectionId().equals(connectionId)) {
                currentConnection.set(null);
            }
        }

        dataSourceRegistry.removeDataSource(connectionId);
        tunnelManager.closeTunnel(connectionId);

        eventBus.publish(new EventBus.ConnectionClosedEvent(connectionId));
        eventBus.publish(new EventBus.StatusMessageEvent(
                "Disconnected from " + (info != null ? info.getDisplayName() : connectionId),
                EventBus.StatusMessageEvent.MessageType.INFO));

        logger.info("Disconnected: {}", connectionId);
    }

    public void disconnectAll() {
        logger.info("Disconnecting all connections...");
        new ConcurrentHashMap<>(activeConnections).keySet().forEach(this::disconnect);
        healthChecker.close();
    }

    public Connection getConnection(String connectionId) throws SQLException {
        if (isReconnecting(connectionId)) {
            logger.debug("SSH正在重连，查询将排队: {}", connectionId);
            throw new SQLException("SSH连接正在恢复中，请稍后重试");
        }

        if (!isConnected(connectionId)) {
            throw new SQLException("Not connected to: " + connectionId);
        }
        ConnectionInfo info = activeConnections.get(connectionId);
        info.incrementActiveQueries();
        Connection conn = dataSourceRegistry.getConnection(connectionId);
        info.decrementActiveQueries();
        return conn;
    }

    public Connection getCurrentConnectionOrThrow() throws SQLException {
        ConnectionInfo current = currentConnection.get();
        if (current == null || !current.isConnected()) {
            throw new SQLException("No active connection");
        }
        return getConnection(current.getConnectionId());
    }

    public void testConnectionAsync(ConnectionConfig config, Consumer<Result<Void>> callback) {
        ApplicationContext.executeAsync(() -> {
            Result<Void> result = testConnection(config);
            Platform.runLater(() -> callback.accept(result));
        });
    }

    public Result<Void> testConnection(ConnectionConfig config) {
        SshTunnel tunnel = null;
        try {
            ConnectionConfig testConfig = config.copy();

            if (testConfig.getSshConfig() != null && testConfig.getSshConfig().isEnabled()) {
                testConfig.getSshConfig().setRemotePort(testConfig.getPort());
                testConfig.getSshConfig().setRemoteHost(testConfig.getHost());
                tunnel = tunnelManager.createTunnel("test-" + config.getId(), testConfig.getSshConfig());
                if (tunnel == null) {
                    return Result.failure("Failed to create SSH tunnel");
                }
                testConfig.setHost("localhost");
                testConfig.setPort(tunnel.getLocalPort());
            }

            String jdbcUrl = testConfig.getJdbcUrl();
            dataSourceRegistry.testConnection(
                    jdbcUrl,
                    testConfig.getUsername(),
                    testConfig.getPassword(),
                    testConfig.getType().getDriverClass()
            );

            logger.info("Connection test successful for: {}", config.getName());
            return Result.success();

        } catch (Exception e) {
            logger.error("Connection test failed for: {}", config.getName(), e);
            return Result.failure(e);
        } finally {
            if (tunnel != null) {
                tunnelManager.closeTunnel("test-" + config.getId());
            }
        }
    }

    public Optional<String> getCurrentDatabase() {
        ConnectionInfo current = currentConnection.get();
        if (current == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(current.getCurrentDatabase());
    }

    public void setCurrentDatabase(String database) {
        ConnectionInfo current = currentConnection.get();
        if (current != null) {
            current.setCurrentDatabase(database);
            if (current.getConfig().getType() == ConnectionType.MYSQL) {
                try (Connection conn = getConnection(current.getConnectionId())) {
                    conn.createStatement().execute("USE " + database);
                } catch (SQLException e) {
                    logger.error("Failed to switch database: {}", database, e);
                }
            }
        }
    }

    public void addConnection(ConnectionConfig config) {
        repository.addConnection(config);
    }

    public void updateConnection(ConnectionConfig config) {
        repository.updateConnection(config);
    }

    public void deleteConnection(String id) {
        disconnect(id);
        repository.deleteConnection(id);
    }

    public HikariDataSource getDataSource(String connectionId) {
        return dataSourceRegistry.getDataSource(connectionId);
    }

    public SshTunnelHealthChecker getHealthChecker() {
        return healthChecker;
    }

    @Override
    public void close() {
        logger.info("Closing ConnectionManager...");
        disconnectAll();
        dataSourceRegistry.close();
        tunnelManager.close();
    }
}
