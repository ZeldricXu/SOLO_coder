package com.company.dbstudio.connection.model;

public class PoolConfig {

    private int maximumPoolSize = 10;
    private int minimumIdle = 2;
    private long connectionTimeout = 30000;
    private long idleTimeout = 600000;
    private long maxLifetime = 1800000;
    private long leakDetectionThreshold = 20000;
    private String connectionTestQuery;
    private boolean autoCommit = true;
    private boolean readOnly = false;
    private String schema;
    private String catalog;
    private String connectionInitSql;

    public PoolConfig() {
    }

    public PoolConfig copy() {
        PoolConfig copy = new PoolConfig();
        copy.maximumPoolSize = this.maximumPoolSize;
        copy.minimumIdle = this.minimumIdle;
        copy.connectionTimeout = this.connectionTimeout;
        copy.idleTimeout = this.idleTimeout;
        copy.maxLifetime = this.maxLifetime;
        copy.leakDetectionThreshold = this.leakDetectionThreshold;
        copy.connectionTestQuery = this.connectionTestQuery;
        copy.autoCommit = this.autoCommit;
        copy.readOnly = this.readOnly;
        copy.schema = this.schema;
        copy.catalog = this.catalog;
        copy.connectionInitSql = this.connectionInitSql;
        return copy;
    }

    public int getMaximumPoolSize() {
        return maximumPoolSize;
    }

    public void setMaximumPoolSize(int maximumPoolSize) {
        this.maximumPoolSize = maximumPoolSize;
    }

    public int getMinimumIdle() {
        return minimumIdle;
    }

    public void setMinimumIdle(int minimumIdle) {
        this.minimumIdle = minimumIdle;
    }

    public long getConnectionTimeout() {
        return connectionTimeout;
    }

    public void setConnectionTimeout(long connectionTimeout) {
        this.connectionTimeout = connectionTimeout;
    }

    public long getIdleTimeout() {
        return idleTimeout;
    }

    public void setIdleTimeout(long idleTimeout) {
        this.idleTimeout = idleTimeout;
    }

    public long getMaxLifetime() {
        return maxLifetime;
    }

    public void setMaxLifetime(long maxLifetime) {
        this.maxLifetime = maxLifetime;
    }

    public long getLeakDetectionThreshold() {
        return leakDetectionThreshold;
    }

    public void setLeakDetectionThreshold(long leakDetectionThreshold) {
        this.leakDetectionThreshold = leakDetectionThreshold;
    }

    public String getConnectionTestQuery() {
        return connectionTestQuery;
    }

    public void setConnectionTestQuery(String connectionTestQuery) {
        this.connectionTestQuery = connectionTestQuery;
    }

    public boolean isAutoCommit() {
        return autoCommit;
    }

    public void setAutoCommit(boolean autoCommit) {
        this.autoCommit = autoCommit;
    }

    public boolean isReadOnly() {
        return readOnly;
    }

    public void setReadOnly(boolean readOnly) {
        this.readOnly = readOnly;
    }

    public String getSchema() {
        return schema;
    }

    public void setSchema(String schema) {
        this.schema = schema;
    }

    public String getCatalog() {
        return catalog;
    }

    public void setCatalog(String catalog) {
        this.catalog = catalog;
    }

    public String getConnectionInitSql() {
        return connectionInitSql;
    }

    public void setConnectionInitSql(String connectionInitSql) {
        this.connectionInitSql = connectionInitSql;
    }
}
