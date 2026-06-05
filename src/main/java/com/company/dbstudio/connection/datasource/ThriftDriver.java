package com.company.dbstudio.connection.datasource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.Properties;
import java.util.logging.Logger;

public class ThriftDriver implements Driver {

    private static final Logger logger = LoggerFactory.getLogger(ThriftDriver.class);
    private static final String URL_PREFIX = "jdbc:thrift://";

    static {
        try {
            DriverManager.registerDriver(new ThriftDriver());
        } catch (SQLException e) {
            logger.error("Failed to register ThriftDriver", e);
        }
    }

    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        if (!acceptsURL(url)) {
            return null;
        }

        logger.info("Connecting to Thrift data source: {}", url);

        return new ThriftConnection(url, info);
    }

    @Override
    public boolean acceptsURL(String url) throws SQLException {
        return url != null && url.startsWith(URL_PREFIX);
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException {
        return new DriverPropertyInfo[]{
                new DriverPropertyInfo("host", info.getProperty("host")),
                new DriverPropertyInfo("port", info.getProperty("port")),
                new DriverPropertyInfo("database", info.getProperty("database")),
                new DriverPropertyInfo("user", info.getProperty("user")),
                new DriverPropertyInfo("password", info.getProperty("password")),
                new DriverPropertyInfo("timeout", info.getProperty("timeout", "30000")),
                new DriverPropertyInfo("service", info.getProperty("service", "DatabaseService"))
        };
    }

    @Override
    public int getMajorVersion() {
        return 1;
    }

    @Override
    public int getMinorVersion() {
        return 0;
    }

    @Override
    public boolean jdbcCompliant() {
        return false;
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException("Logging not supported");
    }
}
