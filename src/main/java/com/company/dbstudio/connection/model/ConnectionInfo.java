package com.company.dbstudio.connection.model;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;

public class ConnectionInfo {

    private final String connectionId;
    private final ConnectionConfig config;
    private final LocalDateTime connectedAt;
    private final AtomicInteger activeQueries;
    private volatile long lastUsedAt;
    private volatile String currentDatabase;
    private volatile String currentSchema;
    private volatile boolean connected;

    public ConnectionInfo(String connectionId, ConnectionConfig config) {
        this.connectionId = connectionId;
        this.config = config;
        this.connectedAt = LocalDateTime.now();
        this.lastUsedAt = System.currentTimeMillis();
        this.activeQueries = new AtomicInteger(0);
        this.connected = true;
        this.currentDatabase = config.getDatabase();
    }

    public String getConnectionId() {
        return connectionId;
    }

    public ConnectionConfig getConfig() {
        return config;
    }

    public LocalDateTime getConnectedAt() {
        return connectedAt;
    }

    public long getLastUsedAt() {
        return lastUsedAt;
    }

    public void markUsed() {
        this.lastUsedAt = System.currentTimeMillis();
    }

    public int getActiveQueries() {
        return activeQueries.get();
    }

    public int incrementActiveQueries() {
        markUsed();
        return activeQueries.incrementAndGet();
    }

    public int decrementActiveQueries() {
        markUsed();
        return activeQueries.decrementAndGet();
    }

    public String getCurrentDatabase() {
        return currentDatabase;
    }

    public void setCurrentDatabase(String currentDatabase) {
        this.currentDatabase = currentDatabase;
    }

    public String getCurrentSchema() {
        return currentSchema;
    }

    public void setCurrentSchema(String currentSchema) {
        this.currentSchema = currentSchema;
    }

    public boolean isConnected() {
        return connected;
    }

    public void setConnected(boolean connected) {
        this.connected = connected;
    }

    public String getDisplayName() {
        return config.getName();
    }

    public ConnectionType getConnectionType() {
        return config.getType();
    }
}
