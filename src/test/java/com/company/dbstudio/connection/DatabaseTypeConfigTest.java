package com.company.dbstudio.connection;

import com.company.dbstudio.connection.config.DatabaseTypeConfig;
import com.company.dbstudio.connection.model.ConnectionType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseTypeConfigTest {

    @BeforeAll
    static void setup() {
        DatabaseTypeConfig.getInstance();
    }

    @Test
    void testSingletonInstance() {
        DatabaseTypeConfig config1 = DatabaseTypeConfig.getInstance();
        DatabaseTypeConfig config2 = DatabaseTypeConfig.getInstance();
        assertSame(config1, config2, "Should be singleton instance");
    }

    @Test
    void testLoadAllDatabaseTypes() {
        Set<ConnectionType> supportedTypes = DatabaseTypeConfig.getInstance().getSupportedTypes();
        assertTrue(supportedTypes.contains(ConnectionType.MYSQL), "MySQL should be supported");
        assertTrue(supportedTypes.contains(ConnectionType.POSTGRESQL), "PostgreSQL should be supported");
        assertTrue(supportedTypes.contains(ConnectionType.ORACLE), "Oracle should be supported");
        assertTrue(supportedTypes.contains(ConnectionType.SQL_SERVER), "SQL Server should be supported");
        assertTrue(supportedTypes.contains(ConnectionType.THRIFT), "Thrift should be supported");
    }

    @Test
    void testMySqlConfig() {
        DatabaseTypeConfig.DbTypeInfo info = DatabaseTypeConfig.getInstance()
                .getTypeInfo(ConnectionType.MYSQL);

        assertNotNull(info, "MySQL config should not be null");
        assertEquals("MySQL", info.getDisplayName());
        assertEquals("com.mysql.cj.jdbc.Driver", info.getDriverClass());
        assertEquals(3306, info.getDefaultPort());
        assertTrue(info.isRequired("host"), "host should be required");
        assertTrue(info.isRequired("port"), "port should be required");
        assertTrue(info.isRequired("database"), "database should be required");
        assertTrue(info.isRequired("username"), "username should be required");
        assertTrue(info.isOptional("password"), "password should be optional");
        assertEquals("false", info.getDefaultValue("useSSL"));
        assertEquals("Asia/Shanghai", info.getDefaultValue("serverTimezone"));
    }

    @Test
    void testPostgreSqlConfig() {
        DatabaseTypeConfig.DbTypeInfo info = DatabaseTypeConfig.getInstance()
                .getTypeInfo(ConnectionType.POSTGRESQL);

        assertNotNull(info, "PostgreSQL config should not be null");
        assertEquals("PostgreSQL", info.getDisplayName());
        assertEquals("org.postgresql.Driver", info.getDriverClass());
        assertEquals(5432, info.getDefaultPort());
    }

    @Test
    void testOracleConfig() {
        DatabaseTypeConfig.DbTypeInfo info = DatabaseTypeConfig.getInstance()
                .getTypeInfo(ConnectionType.ORACLE);

        assertNotNull(info, "Oracle config should not be null");
        assertEquals("Oracle", info.getDisplayName());
        assertEquals("oracle.jdbc.OracleDriver", info.getDriverClass());
        assertEquals(1521, info.getDefaultPort());
    }

    @Test
    void testSqlServerConfig() {
        DatabaseTypeConfig.DbTypeInfo info = DatabaseTypeConfig.getInstance()
                .getTypeInfo(ConnectionType.SQL_SERVER);

        assertNotNull(info, "SQL Server config should not be null");
        assertEquals("SQL Server", info.getDisplayName());
        assertEquals("com.microsoft.sqlserver.jdbc.SQLServerDriver", info.getDriverClass());
        assertEquals(1433, info.getDefaultPort());
    }

    @Test
    void testThriftConfig() {
        DatabaseTypeConfig.DbTypeInfo info = DatabaseTypeConfig.getInstance()
                .getTypeInfo(ConnectionType.THRIFT);

        assertNotNull(info, "Thrift config should not be null");
        assertEquals("Thrift", info.getDisplayName());
        assertEquals(9090, info.getDefaultPort());
        assertTrue(info.isRequired("service"), "service should be required for Thrift");
    }

    @Test
    void testBuildJdbcUrl() {
        DatabaseTypeConfig config = DatabaseTypeConfig.getInstance();

        String mysqlUrl = config.buildJdbcUrl(ConnectionType.MYSQL, "localhost", 3306, "testdb");
        assertEquals("jdbc:mysql://localhost:3306/testdb", mysqlUrl);

        String pgUrl = config.buildJdbcUrl(ConnectionType.POSTGRESQL, "192.168.1.100", 5432, "mydb");
        assertEquals("jdbc:postgresql://192.168.1.100:5432/mydb", pgUrl);

        String oracleUrl = config.buildJdbcUrl(ConnectionType.ORACLE, "db.example.com", 1521, "ORCL");
        assertEquals("jdbc:oracle:thin:@db.example.com:1521:ORCL", oracleUrl);
    }

    @Test
    void testBuildJdbcUrlWithDefaults() {
        DatabaseTypeConfig config = DatabaseTypeConfig.getInstance();

        String mysqlUrl = config.buildJdbcUrl(ConnectionType.MYSQL, null, 0, "testdb");
        assertEquals("jdbc:mysql://localhost:3306/testdb", mysqlUrl);
    }

    @Test
    void testJdbcProperties() {
        DatabaseTypeConfig.DbTypeInfo info = DatabaseTypeConfig.getInstance()
                .getTypeInfo(ConnectionType.MYSQL);

        List<String> jdbcProps = info.getJdbcProperties();
        assertNotNull(jdbcProps);
        assertTrue(jdbcProps.contains("useSSL"));
        assertTrue(jdbcProps.contains("serverTimezone"));
        assertTrue(jdbcProps.contains("allowPublicKeyRetrieval"));
    }

    @Test
    void testIsKnownField() {
        DatabaseTypeConfig.DbTypeInfo info = DatabaseTypeConfig.getInstance()
                .getTypeInfo(ConnectionType.MYSQL);

        assertTrue(info.isKnownField("host"));
        assertTrue(info.isKnownField("password"));
        assertTrue(info.isKnownField("useSSL"));
        assertFalse(info.isKnownField("unknownField"));
    }
}
