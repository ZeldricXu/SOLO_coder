package com.company.dbstudio.connection;

import com.company.dbstudio.connection.datasource.DataSourceRegistry;
import com.company.dbstudio.connection.model.ConnectionConfig;
import com.company.dbstudio.connection.model.ConnectionInfo;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public class ConnectionManager implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(ConnectionManager.class);

    private final ConnectionRepository repository;
    private final DataSourceRegistry dataSourceRegistry;
    private final SshTunnelManager tunnelManager;
    private final EventBus eventBus;

    private final Map<String, ConnectionInfo> activeConnections = new ConcurrentHashMap<>();
    private final ObservableList<ConnectionInfo> activeConnectionsList =
            FXCollections.observableArrayList();
    private final SimpleObjectProperty<ConnectionInfo> currentConnection =
            new SimpleObjectProperty<>();

    public ConnectionManager() {
        this.repository = new ConnectionRepository();
        this.dataSourceRegistry = DataSourceRegistry.getInstance();
        this.tunnelManager = SshTunnelManager.getInstance();
        this.eventBus = EventBus.getInstance();
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

    public void disconnect(String connectionId) {
        logger.info("Disconnecting: {}", connectionId);

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
    }

    public Connection getConnection(String connectionId) throws SQLException {
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

    @Override
    public void close() {
        logger.info("Closing ConnectionManager...");
        disconnectAll();
        dataSourceRegistry.close();
        tunnelManager.close();
    }
}
