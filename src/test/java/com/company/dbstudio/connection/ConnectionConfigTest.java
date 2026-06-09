package com.company.dbstudio.connection;

import com.company.dbstudio.connection.model.ConnectionConfig;
import com.company.dbstudio.connection.model.ConnectionType;
import com.company.dbstudio.connection.model.PoolConfig;
import com.company.dbstudio.connection.model.SshConfig;
import com.company.dbstudio.connection.model.SslConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("连接配置测试")
class ConnectionConfigTest {

    @Test
    @DisplayName("创建默认连接配置")
    void createDefaultConfig_ShouldHaveDefaultValues() {
        ConnectionConfig config = new ConnectionConfig();

        assertThat(config.getId()).isNotNull();
        assertThat(config.getType()).isEqualTo(ConnectionType.MYSQL);
        assertThat(config.getHost()).isEqualTo("127.0.0.1");
        assertThat(config.getPort()).isEqualTo(3306);
        assertThat(config.isFavorite()).isFalse();
    }

    @Test
    @DisplayName("设置连接配置属性")
    void setConfigProperties_ShouldStoreCorrectly() {
        ConnectionConfig config = new ConnectionConfig();
        String id = UUID.randomUUID().toString();

        config.setId(id);
        config.setName("Test MySQL");
        config.setType(ConnectionType.POSTGRESQL);
        config.setHost("192.168.1.100");
        config.setPort(5432);
        config.setDatabase("mydb");
        config.setUsername("admin");
        config.setPassword("secret");
        config.setGroup("Production");
        config.setFavorite(true);

        assertThat(config.getId()).isEqualTo(id);
        assertThat(config.getName()).isEqualTo("Test MySQL");
        assertThat(config.getType()).isEqualTo(ConnectionType.POSTGRESQL);
        assertThat(config.getHost()).isEqualTo("192.168.1.100");
        assertThat(config.getPort()).isEqualTo(5432);
        assertThat(config.getDatabase()).isEqualTo("mydb");
        assertThat(config.getUsername()).isEqualTo("admin");
        assertThat(config.getPassword()).isEqualTo("secret");
        assertThat(config.getGroup()).isEqualTo("Production");
        assertThat(config.isFavorite()).isTrue();
    }

    @Test
    @DisplayName("构建完整JDBC URL")
    void buildJdbcUrl_ShouldConstructCorrectUrl() {
        ConnectionConfig config = new ConnectionConfig();
        config.setType(ConnectionType.MYSQL);
        config.setHost("localhost");
        config.setPort(3306);
        config.setDatabase("testdb");

        String url = config.getType().buildUrl(config.getHost(), config.getPort(), config.getDatabase());

        assertThat(url)
                .startsWith("jdbc:mysql://localhost:3306/testdb")
                .contains("useSSL=false")
                .contains("serverTimezone=UTC");
    }

    @Test
    @DisplayName("连接池配置默认值")
    void poolConfig_ShouldHaveDefaultValues() {
        PoolConfig poolConfig = new PoolConfig();

        assertThat(poolConfig.getMaxPoolSize()).isEqualTo(10);
        assertThat(poolConfig.getMinimumIdle()).isEqualTo(5);
        assertThat(poolConfig.getConnectionTimeout()).isEqualTo(30000);
        assertThat(poolConfig.getIdleTimeout()).isEqualTo(600000);
        assertThat(poolConfig.getMaxLifetime()).isEqualTo(1800000);
    }

    @Test
    @DisplayName("自定义连接池配置")
    void poolConfig_CustomValues_ShouldStoreCorrectly() {
        PoolConfig poolConfig = new PoolConfig();
        poolConfig.setMaxPoolSize(20);
        poolConfig.setMinimumIdle(10);
        poolConfig.setConnectionTimeout(60000);
        poolConfig.setIdleTimeout(300000);
        poolConfig.setMaxLifetime(900000);

        assertThat(poolConfig.getMaxPoolSize()).isEqualTo(20);
        assertThat(poolConfig.getMinimumIdle()).isEqualTo(10);
        assertThat(poolConfig.getConnectionTimeout()).isEqualTo(60000);
        assertThat(poolConfig.getIdleTimeout()).isEqualTo(300000);
        assertThat(poolConfig.getMaxLifetime()).isEqualTo(900000);
    }

    @Test
    @DisplayName("SSH配置默认禁用")
    void sshConfig_Default_ShouldBeDisabled() {
        SshConfig sshConfig = new SshConfig();
        assertThat(sshConfig.isEnabled()).isFalse();
    }

    @Test
    @DisplayName("SSH配置完整信息")
    void sshConfig_Complete_ShouldStoreAllFields() {
        SshConfig sshConfig = new SshConfig();
        sshConfig.setEnabled(true);
        sshConfig.setHost("ssh.example.com");
        sshConfig.setPort(2222);
        sshConfig.setUsername("sshuser");
        sshConfig.setPassword("sshpass");

        assertThat(sshConfig.isEnabled()).isTrue();
        assertThat(sshConfig.getHost()).isEqualTo("ssh.example.com");
        assertThat(sshConfig.getPort()).isEqualTo(2222);
        assertThat(sshConfig.getUsername()).isEqualTo("sshuser");
        assertThat(sshConfig.getPassword()).isEqualTo("sshpass");
    }

    @Test
    @DisplayName("SSL配置完整信息")
    void sslConfig_Complete_ShouldStoreAllFields() {
        SslConfig sslConfig = new SslConfig();
        sslConfig.setEnabled(true);
        sslConfig.setVerifyServerCertificate(true);
        sslConfig.setCertificatePath("/path/to/cert.pem");
        sslConfig.setClientCertificatePath("/path/to/client-cert.pem");
        sslConfig.setClientKeyPath("/path/to/client-key.pem");

        assertThat(sslConfig.isEnabled()).isTrue();
        assertThat(sslConfig.isVerifyServerCertificate()).isTrue();
        assertThat(sslConfig.getCertificatePath()).isEqualTo("/path/to/cert.pem");
        assertThat(sslConfig.getClientCertificatePath()).isEqualTo("/path/to/client-cert.pem");
        assertThat(sslConfig.getClientKeyPath()).isEqualTo("/path/to/client-key.pem");
    }

    @Test
    @DisplayName("连接配置完整构建")
    void completeConnectionConfig_ShouldAssembleCorrectly() {
        ConnectionConfig config = new ConnectionConfig();
        config.setName("Production DB");
        config.setType(ConnectionType.POSTGRESQL);
        config.setHost("prod-db.example.com");
        config.setPort(5432);
        config.setDatabase("appdb");
        config.setUsername("appuser");
        config.setPassword("apppass");

        PoolConfig poolConfig = new PoolConfig();
        poolConfig.setMaxPoolSize(15);
        poolConfig.setConnectionTimeout(10000);
        config.setPoolConfig(poolConfig);

        SshConfig sshConfig = new SshConfig();
        sshConfig.setEnabled(true);
        sshConfig.setHost("bastion.example.com");
        sshConfig.setPort(22);
        sshConfig.setUsername("bastionuser");
        config.setSshConfig(sshConfig);

        SslConfig sslConfig = new SslConfig();
        sslConfig.setEnabled(true);
        config.setSslConfig(sslConfig);

        assertThat(config.getName()).isEqualTo("Production DB");
        assertThat(config.getPoolConfig().getMaxPoolSize()).isEqualTo(15);
        assertThat(config.getSshConfig().isEnabled()).isTrue();
        assertThat(config.getSshConfig().getHost()).isEqualTo("bastion.example.com");
        assertThat(config.getSslConfig().isEnabled()).isTrue();
    }

    @Test
    @DisplayName("getJdbcUrl方法应构建正确URL")
    void getJdbcUrl_ShouldBuildFromConfig() {
        ConnectionConfig config = new ConnectionConfig();
        config.setType(ConnectionType.POSTGRESQL);
        config.setHost("db.example.com");
        config.setPort(5432);
        config.setDatabase("test");

        String url = config.buildJdbcUrl();

        assertThat(url).isEqualTo("jdbc:postgresql://db.example.com:5432/test");
    }
}
