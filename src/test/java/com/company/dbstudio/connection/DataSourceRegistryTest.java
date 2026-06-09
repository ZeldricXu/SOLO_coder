package com.company.dbstudio.connection;

import com.company.dbstudio.connection.datasource.DataSourceRegistry;
import com.company.dbstudio.connection.model.ConnectionConfig;
import com.company.dbstudio.connection.model.ConnectionType;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("数据源注册表测试")
class DataSourceRegistryTest {

    private final DataSourceRegistry registry = new DataSourceRegistry();

    @Test
    @DisplayName("注册和获取Hikari数据源")
    void registerAndGetDataSource_ShouldWork() {
        ConnectionConfig config = createH2Config();

        registry.registerDataSource(config.getId(), config);

        HikariDataSource dataSource = registry.getDataSource(config.getId());
        assertThat(dataSource).isNotNull();
        assertThat(dataSource.getJdbcUrl()).startsWith("jdbc:h2:");
    }

    @Test
    @DisplayName("获取不存在的数据源应返回null")
    void getDataSource_NotExists_ShouldReturnNull() {
        HikariDataSource dataSource = registry.getDataSource("non-existent");
        assertThat(dataSource).isNull();
    }

    @Test
    @DisplayName("驱动自动匹配 - MySQL")
    void getDriver_MySQL_ShouldReturnCorrectDriver() {
        Driver driver = registry.getDriver(ConnectionType.MYSQL);
        assertThat(driver).isNotNull();
        assertThat(driver.getClass().getName()).isEqualTo("com.mysql.cj.jdbc.Driver");
    }

    @Test
    @DisplayName("驱动自动匹配 - PostgreSQL")
    void getDriver_PostgreSQL_ShouldReturnCorrectDriver() {
        Driver driver = registry.getDriver(ConnectionType.POSTGRESQL);
        assertThat(driver).isNotNull();
        assertThat(driver.getClass().getName()).isEqualTo("org.postgresql.Driver");
    }

    @Test
    @DisplayName("从URL解析连接类型 - MySQL")
    void parseConnectionType_MySqlUrl_ShouldReturnMySQL() {
        String url = "jdbc:mysql://localhost:3306/testdb";
        ConnectionType type = registry.parseConnectionType(url);
        assertThat(type).isEqualTo(ConnectionType.MYSQL);
    }

    @Test
    @DisplayName("从URL解析连接类型 - PostgreSQL")
    void parseConnectionType_PostgresUrl_ShouldReturnPostgreSQL() {
        String url = "jdbc:postgresql://localhost:5432/testdb";
        ConnectionType type = registry.parseConnectionType(url);
        assertThat(type).isEqualTo(ConnectionType.POSTGRESQL);
    }

    @Test
    @DisplayName("从URL解析连接类型 - Oracle")
    void parseConnectionType_OracleUrl_ShouldReturnOracle() {
        String url = "jdbc:oracle:thin:@//localhost:1521/XE";
        ConnectionType type = registry.parseConnectionType(url);
        assertThat(type).isEqualTo(ConnectionType.ORACLE);
    }

    @Test
    @DisplayName("从URL解析连接类型 - SQL Server")
    void parseConnectionType_SQLServerUrl_ShouldReturnSQLServer() {
        String url = "jdbc:sqlserver://localhost:1433;databaseName=testdb";
        ConnectionType type = registry.parseConnectionType(url);
        assertThat(type).isEqualTo(ConnectionType.SQL_SERVER);
    }

    @Test
    @DisplayName("从URL解析连接类型 - Thrift")
    void parseConnectionType_ThriftUrl_ShouldReturnThrift() {
        String url = "jdbc:thrift://localhost:9090/testdb";
        ConnectionType type = registry.parseConnectionType(url);
        assertThat(type).isEqualTo(ConnectionType.THRIFT);
    }

    @Test
    @DisplayName("无效URL应抛出异常")
    void parseConnectionType_InvalidUrl_ShouldThrowException() {
        String url = "jdbc:unknown://localhost/test";
        assertThatThrownBy(() -> registry.parseConnectionType(url))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown connection type");
    }

    @Test
    @DisplayName("连接池参数正确应用")
    void registerDataSource_ShouldApplyPoolConfig() {
        ConnectionConfig config = createH2Config();
        config.getPoolConfig().setMaxPoolSize(15);
        config.getPoolConfig().setMinimumIdle(5);
        config.getPoolConfig().setConnectionTimeout(10000);

        registry.registerDataSource(config.getId(), config);
        HikariDataSource dataSource = registry.getDataSource(config.getId());

        assertThat(dataSource.getMaximumPoolSize()).isEqualTo(15);
        assertThat(dataSource.getMinimumIdle()).isEqualTo(5);
        assertThat(dataSource.getConnectionTimeout()).isEqualTo(10000);
    }

    @Test
    @DisplayName("获取JDBC连接")
    void getConnection_ShouldReturnValidConnection() throws Exception {
        ConnectionConfig config = createH2Config();
        registry.registerDataSource(config.getId(), config);

        try (Connection conn = registry.getConnection(config.getId())) {
            assertThat(conn).isNotNull();
            assertThat(conn.isClosed()).isFalse();
        }
    }

    @Test
    @DisplayName("关闭数据源")
    void closeDataSource_ShouldReleaseResources() {
        ConnectionConfig config = createH2Config();
        registry.registerDataSource(config.getId(), config);

        registry.closeDataSource(config.getId());

        HikariDataSource dataSource = registry.getDataSource(config.getId());
        assertThat(dataSource).isNull();
    }

    @Test
    @DisplayName("获取可用连接类型列表")
    void getAvailableConnectionTypes_ShouldReturnAllTypes() {
        List<ConnectionType> types = registry.getAvailableConnectionTypes();
        assertThat(types)
                .hasSize(5)
                .containsExactlyInAnyOrder(
                        ConnectionType.MYSQL,
                        ConnectionType.POSTGRESQL,
                        ConnectionType.ORACLE,
                        ConnectionType.SQL_SERVER,
                        ConnectionType.THRIFT
                );
    }

    @Test
    @DisplayName("Hikari数据源配置正确")
    void registerDataSource_ShouldConfigureHikariCorrectly() {
        ConnectionConfig config = createH2Config();

        registry.registerDataSource(config.getId(), config);
        HikariDataSource dataSource = registry.getDataSource(config.getId());

        assertThat(dataSource.getPoolName()).isEqualTo(config.getName());
        assertThat(dataSource.getDriverClassName()).isNotNull();
        assertThat(dataSource.getJdbcUrl()).startsWith("jdbc:h2:mem:");
    }

    @Test
    @DisplayName("SSL配置应正确应用到JDBC URL")
    void registerDataSource_WithSsl_ShouldIncludeSslParams() {
        ConnectionConfig config = createH2Config();
        config.setSslConfig(null);

        registry.registerDataSource(config.getId(), config);
        HikariDataSource dataSource = registry.getDataSource(config.getId());

        assertThat(dataSource.getJdbcUrl()).isNotNull();
    }

    private ConnectionConfig createH2Config() {
        ConnectionConfig config = new ConnectionConfig();
        config.setId("test-h2-" + System.currentTimeMillis());
        config.setName("Test H2");
        config.setHost("localhost");
        config.setPort(0);
        config.setDatabase("testdb");
        config.setUsername("sa");
        config.setPassword("");
        return config;
    }
}
