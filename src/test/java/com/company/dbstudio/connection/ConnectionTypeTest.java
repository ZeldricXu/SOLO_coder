package com.company.dbstudio.connection;

import com.company.dbstudio.connection.model.ConnectionConfig;
import com.company.dbstudio.connection.model.ConnectionType;
import com.company.dbstudio.test.TestConstants;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("连接类型测试")
class ConnectionTypeTest {

    @ParameterizedTest
    @CsvSource({
            "mysql,      MYSQL",
            "postgresql, POSTGRESQL",
            "oracle,     ORACLE",
            "sqlserver,  SQL_SERVER",
            "thrift,     THRIFT"
    })
    @DisplayName("从协议解析连接类型")
    void fromProtocol_ShouldReturnCorrectType(String protocol, ConnectionType expected) {
        ConnectionType result = ConnectionType.fromProtocol(protocol);
        assertThat(result).isEqualTo(expected);
    }

    @Test
    @DisplayName("无效协议应抛出异常")
    void fromProtocol_InvalidProtocol_ShouldThrowException() {
        assertThatThrownBy(() -> ConnectionType.fromProtocol("invalid"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown protocol");
    }

    @ParameterizedTest
    @CsvSource({
            "MYSQL,      localhost, 3306, testdb, jdbc:mysql://localhost:3306/testdb",
            "POSTGRESQL, localhost, 5432, mydb,   jdbc:postgresql://localhost:5432/mydb",
            "THRIFT,     10.0.0.1,  9090, data,   jdbc:thrift://10.0.0.1:9090/data"
    })
    @DisplayName("构建JDBC URL")
    void buildUrl_ShouldConstructCorrectUrl(ConnectionType type, String host, int port, String database, String expected) {
        String url = type.buildUrl(host, port, database);
        assertThat(url).isEqualTo(expected);
    }

    @Test
    @DisplayName("MySQL URL应包含SSL和时区参数")
    void buildUrl_MySql_ShouldIncludeExtraParams() {
        String url = ConnectionType.MYSQL.buildUrl("localhost", 3306, "testdb");
        assertThat(url)
                .contains("useSSL=false")
                .contains("serverTimezone=UTC")
                .contains("allowPublicKeyRetrieval=true");
    }

    @Test
    @DisplayName("系统数据库判断")
    void isSystemDatabase_ShouldReturnCorrectResult() {
        assertThat(ConnectionType.MYSQL.isSystemDatabase("mysql")).isTrue();
        assertThat(ConnectionType.MYSQL.isSystemDatabase("information_schema")).isTrue();
        assertThat(ConnectionType.MYSQL.isSystemDatabase("userdb")).isFalse();
        assertThat(ConnectionType.POSTGRESQL.isSystemDatabase("postgres")).isTrue();
        assertThat(ConnectionType.POSTGRESQL.isSystemDatabase("userdb")).isFalse();
    }

    @Test
    @DisplayName("获取驱动类名")
    void getDriverClass_ShouldReturnCorrectDriver() {
        assertThat(ConnectionType.MYSQL.getDriverClass()).isEqualTo("com.mysql.cj.jdbc.Driver");
        assertThat(ConnectionType.POSTGRESQL.getDriverClass()).isEqualTo("org.postgresql.Driver");
        assertThat(ConnectionType.ORACLE.getDriverClass()).isEqualTo("oracle.jdbc.OracleDriver");
        assertThat(ConnectionType.SQL_SERVER.getDriverClass()).isEqualTo("com.microsoft.sqlserver.jdbc.SQLServerDriver");
        assertThat(ConnectionType.THRIFT.getDriverClass()).isEqualTo("com.company.dbstudio.connection.datasource.ThriftDriver");
    }

    @Test
    @DisplayName("默认端口")
    void getDefaultPort_ShouldReturnCorrectPort() {
        assertThat(ConnectionType.MYSQL.getDefaultPort()).isEqualTo(3306);
        assertThat(ConnectionType.POSTGRESQL.getDefaultPort()).isEqualTo(5432);
        assertThat(ConnectionType.ORACLE.getDefaultPort()).isEqualTo(1521);
        assertThat(ConnectionType.SQL_SERVER.getDefaultPort()).isEqualTo(1433);
        assertThat(ConnectionType.THRIFT.getDefaultPort()).isEqualTo(9090);
    }

    @Test
    @DisplayName("显示名称")
    void getDisplayName_ShouldReturnUserFriendlyName() {
        assertThat(ConnectionType.MYSQL.getDisplayName()).isEqualTo("MySQL");
        assertThat(ConnectionType.SQL_SERVER.getDisplayName()).isEqualTo("SQL Server");
        assertThat(ConnectionType.THRIFT.getDisplayName()).isEqualTo("Thrift DataSource");
    }
}
