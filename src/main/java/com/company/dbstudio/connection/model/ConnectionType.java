package com.company.dbstudio.connection.model;

import java.util.Arrays;
import java.util.List;

public enum ConnectionType {
    MYSQL("MySQL", "mysql", 3306, "com.mysql.cj.jdbc.Driver",
            "jdbc:mysql://{host}:{port}/{database}?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true",
            Arrays.asList("information_schema", "mysql", "performance_schema", "sys")),

    POSTGRESQL("PostgreSQL", "postgresql", 5432, "org.postgresql.Driver",
            "jdbc:postgresql://{host}:{port}/{database}",
            List.of("postgres")),

    ORACLE("Oracle", "oracle", 1521, "oracle.jdbc.OracleDriver",
            "jdbc:oracle:thin:@{host}:{port}:{database}",
            List.of("SYS", "SYSTEM", "SYSDBA")),

    SQL_SERVER("SQL Server", "sqlserver", 1433, "com.microsoft.sqlserver.jdbc.SQLServerDriver",
            "jdbc:sqlserver://{host}:{port};databaseName={database};encrypt=false",
            List.of("master", "tempdb", "model", "msdb")),

    THRIFT("Thrift DataSource", "thrift", 9090, "com.company.dbstudio.connection.datasource.ThriftDriver",
            "jdbc:thrift://{host}:{port}/{database}",
            List.of("default"));

    private final String displayName;
    private final String protocol;
    private final int defaultPort;
    private final String driverClass;
    private final String urlPattern;
    private final List<String> systemDatabases;

    ConnectionType(String displayName, String protocol, int defaultPort,
                   String driverClass, String urlPattern, List<String> systemDatabases) {
        this.displayName = displayName;
        this.protocol = protocol;
        this.defaultPort = defaultPort;
        this.driverClass = driverClass;
        this.urlPattern = urlPattern;
        this.systemDatabases = systemDatabases;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getProtocol() {
        return protocol;
    }

    public int getDefaultPort() {
        return defaultPort;
    }

    public String getDriverClass() {
        return driverClass;
    }

    public String getUrlPattern() {
        return urlPattern;
    }

    public List<String> getSystemDatabases() {
        return systemDatabases;
    }

    public boolean isSystemDatabase(String databaseName) {
        return systemDatabases.contains(databaseName);
    }

    public String buildUrl(String host, int port, String database) {
        return urlPattern
                .replace("{host}", host)
                .replace("{port}", String.valueOf(port))
                .replace("{database}", database);
    }

    public static ConnectionType fromProtocol(String protocol) {
        for (ConnectionType type : values()) {
            if (type.protocol.equalsIgnoreCase(protocol)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown protocol: " + protocol);
    }

    @Override
    public String toString() {
        return displayName;
    }
}
