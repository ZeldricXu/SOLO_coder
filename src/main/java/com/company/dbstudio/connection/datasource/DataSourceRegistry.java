package com.company.dbstudio.connection.datasource;

import com.company.dbstudio.connection.model.ConnectionType;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class DataSourceRegistry implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(DataSourceRegistry.class);
    private static final DataSourceRegistry INSTANCE = new DataSourceRegistry();

    private final Map<String, HikariDataSource> dataSources = new ConcurrentHashMap<>();

    private DataSourceRegistry() {
    }

    public static DataSourceRegistry getInstance() {
        return INSTANCE;
    }

    public HikariDataSource createDataSource(String connectionId,
                                             com.company.dbstudio.connection.model.ConnectionConfig config,
                                             String jdbcUrl) {
        HikariConfig hikariConfig = new HikariConfig();

        hikariConfig.setJdbcUrl(jdbcUrl);
        hikariConfig.setUsername(config.getUsername());
        hikariConfig.setPassword(config.getPassword());
        hikariConfig.setDriverClassName(config.getType().getDriverClass());

        var poolConfig = config.getPoolConfig();
        hikariConfig.setMaximumPoolSize(poolConfig.getMaximumPoolSize());
        hikariConfig.setMinimumIdle(poolConfig.getMinimumIdle());
        hikariConfig.setConnectionTimeout(poolConfig.getConnectionTimeout());
        hikariConfig.setIdleTimeout(poolConfig.getIdleTimeout());
        hikariConfig.setMaxLifetime(poolConfig.getMaxLifetime());
        hikariConfig.setLeakDetectionThreshold(poolConfig.getLeakDetectionThreshold());
        hikariConfig.setAutoCommit(poolConfig.isAutoCommit());
        hikariConfig.setReadOnly(poolConfig.isReadOnly());

        if (poolConfig.getConnectionTestQuery() != null && !poolConfig.getConnectionTestQuery().isEmpty()) {
            hikariConfig.setConnectionTestQuery(poolConfig.getConnectionTestQuery());
        }
        if (poolConfig.getSchema() != null && !poolConfig.getSchema().isEmpty()) {
            hikariConfig.setSchema(poolConfig.getSchema());
        }
        if (poolConfig.getCatalog() != null && !poolConfig.getCatalog().isEmpty()) {
            hikariConfig.setCatalog(poolConfig.getCatalog());
        }
        if (poolConfig.getConnectionInitSql() != null && !poolConfig.getConnectionInitSql().isEmpty()) {
            hikariConfig.setConnectionInitSql(poolConfig.getConnectionInitSql());
        }

        config.getProperties().forEach(hikariConfig::addDataSourceProperty);

        if (config.getSslConfig() != null && config.getSslConfig().isEnabled()) {
            configureSsl(hikariConfig, config);
        }

        hikariConfig.setPoolName("DBStudio-" + connectionId.substring(0, 8));

        HikariDataSource dataSource = new HikariDataSource(hikariConfig);
        dataSources.put(connectionId, dataSource);

        logger.info("Created HikariCP data source for connection: {}, pool: {}",
                connectionId, hikariConfig.getPoolName());

        return dataSource;
    }

    private void configureSsl(HikariConfig config, com.company.dbstudio.connection.model.ConnectionConfig connConfig) {
        var sslConfig = connConfig.getSslConfig();
        ConnectionType type = connConfig.getType();

        switch (type) {
            case MYSQL -> {
                config.addDataSourceProperty("useSSL", "true");
                config.addDataSourceProperty("requireSSL", String.valueOf(sslConfig.isRequireSsl()));
                config.addDataSourceProperty("verifyServerCertificate",
                        String.valueOf(sslConfig.isVerifyServerCertificate()));
                if (sslConfig.hasTrustStore()) {
                    config.addDataSourceProperty("trustCertificateKeyStoreUrl",
                            "file:" + sslConfig.getTrustStorePath());
                    if (sslConfig.getTrustStorePassword() != null) {
                        config.addDataSourceProperty("trustCertificateKeyStorePassword",
                                sslConfig.getTrustStorePassword());
                    }
                }
                if (sslConfig.hasKeyStore()) {
                    config.addDataSourceProperty("clientCertificateKeyStoreUrl",
                            "file:" + sslConfig.getKeyStorePath());
                    if (sslConfig.getKeyStorePassword() != null) {
                        config.addDataSourceProperty("clientCertificateKeyStorePassword",
                                sslConfig.getKeyStorePassword());
                    }
                }
                if (sslConfig.getSslProtocol() != null) {
                    config.addDataSourceProperty("enabledTLSProtocols", sslConfig.getSslProtocol());
                }
            }
            case POSTGRESQL -> {
                config.addDataSourceProperty("ssl", "true");
                config.addDataSourceProperty("sslmode",
                        sslConfig.isVerifyServerCertificate() ? "verify-full" : "require");
                if (sslConfig.hasCaCertificate()) {
                    config.addDataSourceProperty("sslrootcert", sslConfig.getCaCertificatePath());
                }
                if (sslConfig.hasClientCertificate()) {
                    config.addDataSourceProperty("sslcert", sslConfig.getClientCertificatePath());
                    config.addDataSourceProperty("sslkey", sslConfig.getClientKeyPath());
                }
            }
            case ORACLE -> {
                config.addDataSourceProperty("oracle.net.ssl_version", sslConfig.getSslProtocol());
                if (sslConfig.hasTrustStore()) {
                    config.addDataSourceProperty("javax.net.ssl.trustStore", sslConfig.getTrustStorePath());
                    if (sslConfig.getTrustStorePassword() != null) {
                        config.addDataSourceProperty("javax.net.ssl.trustStorePassword",
                                sslConfig.getTrustStorePassword());
                    }
                }
                if (sslConfig.hasKeyStore()) {
                    config.addDataSourceProperty("javax.net.ssl.keyStore", sslConfig.getKeyStorePath());
                    if (sslConfig.getKeyStorePassword() != null) {
                        config.addDataSourceProperty("javax.net.ssl.keyStorePassword",
                                sslConfig.getKeyStorePassword());
                    }
                }
            }
            case SQL_SERVER -> {
                config.addDataSourceProperty("encrypt", "true");
                config.addDataSourceProperty("trustServerCertificate",
                        String.valueOf(!sslConfig.isVerifyServerCertificate()));
                if (sslConfig.hasTrustStore()) {
                    config.addDataSourceProperty("trustStore", sslConfig.getTrustStorePath());
                    if (sslConfig.getTrustStorePassword() != null) {
                        config.addDataSourceProperty("trustStorePassword", sslConfig.getTrustStorePassword());
                    }
                }
            }
            default -> {
            }
        }
    }

    public HikariDataSource getDataSource(String connectionId) {
        return dataSources.get(connectionId);
    }

    public boolean hasDataSource(String connectionId) {
        return dataSources.containsKey(connectionId);
    }

    public Connection getConnection(String connectionId) throws SQLException {
        HikariDataSource dataSource = dataSources.get(connectionId);
        if (dataSource == null) {
            throw new SQLException("No data source found for connection: " + connectionId);
        }
        return dataSource.getConnection();
    }

    public void removeDataSource(String connectionId) {
        HikariDataSource dataSource = dataSources.remove(connectionId);
        if (dataSource != null) {
            try {
                dataSource.close();
                logger.info("Closed data source for connection: {}", connectionId);
            } catch (Exception e) {
                logger.error("Error closing data source for connection: {}", connectionId, e);
            }
        }
    }

    public void testConnection(String jdbcUrl, String username, String password, String driverClass) throws SQLException {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);
        config.setDriverClassName(driverClass);
        config.setMaximumPoolSize(1);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(5000);
        config.setPoolName("DBStudio-Test");

        try (HikariDataSource testDataSource = new HikariDataSource(config);
             Connection connection = testDataSource.getConnection()) {
            if (!connection.isValid(2)) {
                throw new SQLException("Connection is not valid");
            }
        }
    }

    @Override
    public void close() {
        logger.info("Closing all data sources...");
        dataSources.keySet().forEach(this::removeDataSource);
        dataSources.clear();
        logger.info("All data sources closed");
    }
}
