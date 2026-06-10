package com.company.dbstudio.connection.ssh;

import com.company.dbstudio.connection.model.SshConfig;
import com.company.dbstudio.connection.ssh.SshTunnelHealthChecker.TunnelHealthStatus;
import com.company.dbstudio.connection.ssh.SshTunnelHealthChecker.TunnelReconnectEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SSH心跳检测和自动重连 - 回归测试")
class SshTunnelHealthCheckerTest {

    private SshTunnelHealthChecker healthChecker;

    @BeforeEach
    void setup() {
        healthChecker = SshTunnelHealthChecker.getInstance();
    }

    @AfterEach
    void cleanup() {
        healthChecker.stopMonitoring("test-conn-1");
        healthChecker.stopMonitoring("test-conn-2");
    }

    @Test
    @DisplayName("单例模式验证")
    void testSingleton() {
        SshTunnelHealthChecker instance1 = SshTunnelHealthChecker.getInstance();
        SshTunnelHealthChecker instance2 = SshTunnelHealthChecker.getInstance();

        assertSame(instance1, instance2, "Should return the same instance");
    }

    @Test
    @DisplayName("SshConfig keepAliveEnabled 正确判断")
    void testSshConfigKeepAliveEnabled() {
        SshConfig config = new SshConfig();

        config.setKeepAliveInterval(0);
        assertThat(config.isKeepAliveEnabled()).isFalse();

        config.setKeepAliveInterval(30000);
        assertThat(config.isKeepAliveEnabled()).isTrue();

        config.setKeepAliveInterval(60000);
        assertThat(config.isKeepAliveEnabled()).isTrue();
    }

    @Test
    @DisplayName("TunnelHealthStatus 初始状态")
    void testTunnelHealthStatusInitialState() {
        TunnelHealthStatus status = new TunnelHealthStatus("test-conn");

        assertThat(status.getConnectionId()).isEqualTo("test-conn");
        assertThat(status.isHealthy()).isTrue();
        assertThat(status.isReconnecting()).isFalse();
        assertThat(status.getReconnectAttempts()).isEqualTo(0);
        assertThat(status.getLastHealthCheck()).isNotNull();
        assertThat(status.getLastSuccessfulCheck()).isNotNull();
        assertThat(status.getLastErrorMessage()).isNull();
    }

    @Test
    @DisplayName("TunnelHealthStatus 重连计数")
    void testTunnelHealthStatusReconnectAttempts() {
        TunnelHealthStatus status = new TunnelHealthStatus("test-conn");

        status.incrementReconnectAttempts();
        assertThat(status.getReconnectAttempts()).isEqualTo(1);

        status.incrementReconnectAttempts();
        assertThat(status.getReconnectAttempts()).isEqualTo(2);

        status.resetReconnectAttempts();
        assertThat(status.getReconnectAttempts()).isEqualTo(0);
    }

    @Test
    @DisplayName("TunnelHealthStatus 健康状态更新")
    void testTunnelHealthStatusHealthUpdate() {
        TunnelHealthStatus status = new TunnelHealthStatus("test-conn");

        status.setHealthy(false);
        status.setLastErrorMessage("Connection timeout");

        assertThat(status.isHealthy()).isFalse();
        assertThat(status.getLastErrorMessage()).isEqualTo("Connection timeout");

        status.setHealthy(true);
        assertThat(status.isHealthy()).isTrue();
        assertThat(status.getLastErrorMessage()).isNull();
    }

    @Test
    @DisplayName("TunnelHealthStatus 重连中状态")
    void testTunnelHealthStatusReconnectingFlag() {
        TunnelHealthStatus status = new TunnelHealthStatus("test-conn");

        assertThat(status.isReconnecting().compareAndSet(false, true)).isTrue();
        assertThat(status.isReconnecting().get()).isTrue();

        assertThat(status.isReconnecting().compareAndSet(true, false)).isTrue();
        assertThat(status.isReconnecting().get()).isFalse();
    }

    @Test
    @DisplayName("TunnelReconnectEvent 成功事件")
    void testTunnelReconnectEventSuccess() {
        TunnelReconnectEvent event = new TunnelReconnectEvent(
                "conn-1", "ssh.example.com", 2, 5, true, "SSH隧道重连成功"
        );

        assertThat(event.getConnectionId()).isEqualTo("conn-1");
        assertThat(event.getSshHost()).isEqualTo("ssh.example.com");
        assertThat(event.getAttempt()).isEqualTo(2);
        assertThat(event.getMaxAttempts()).isEqualTo(5);
        assertThat(event.isSuccess()).isTrue();
        assertThat(event.getMessage()).isEqualTo("SSH隧道重连成功");
        assertThat(event.getTimestamp()).isNotNull();
        assertThat(event.toString()).contains("conn-1");
        assertThat(event.toString()).contains("重连成功");
    }

    @Test
    @DisplayName("TunnelReconnectEvent 失败事件")
    void testTunnelReconnectEventFailure() {
        TunnelReconnectEvent event = new TunnelReconnectEvent(
                "conn-1", "ssh.example.com", 3, 5, false, "Connection refused"
        );

        assertThat(event.isSuccess()).isFalse();
        assertThat(event.getAttempt()).isEqualTo(3);
        assertThat(event.toString()).contains("重连失败");
        assertThat(event.toString()).contains("3/5");
    }

    @Test
    @DisplayName("重连事件监听器 - 事件通知")
    void testReconnectListenerNotification() {
        AtomicInteger eventCount = new AtomicInteger(0);
        AtomicReference<TunnelReconnectEvent> lastEvent = new AtomicReference<>();

        healthChecker.addReconnectListener("test-conn-1", event -> {
            eventCount.incrementAndGet();
            lastEvent.set(event);
        });

        TunnelReconnectEvent testEvent = new TunnelReconnectEvent(
                "test-conn-1", "ssh.test.com", 1, 5, false, "Test event"
        );

        for (int i = 0; i < 3; i++) {
            healthChecker.addReconnectListener("test-conn-1", event -> {
                eventCount.incrementAndGet();
                lastEvent.set(event);
            });
        }

        healthChecker.stopMonitoring("test-conn-1");

        healthChecker.addReconnectListener("test-conn-1", event -> {
            eventCount.incrementAndGet();
            lastEvent.set(event);
        });

        healthChecker.removeReconnectListener("test-conn-1");

        assertThat(eventCount.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("停止监控后清理资源")
    void testStopMonitoringCleanup() {
        String connId = "test-cleanup";

        healthChecker.addReconnectListener(connId, e -> {});
        healthChecker.stopMonitoring(connId);

        assertThat(healthChecker.getHealthStatus(connId)).isNull();
    }

    @Test
    @DisplayName("获取健康状态 - 未监控的连接")
    void testGetHealthStatusForUnmonitoredConnection() {
        assertThat(healthChecker.getHealthStatus("non-existent")).isNull();
        assertThat(healthChecker.isTunnelHealthy("non-existent")).isFalse();
    }

    @Test
    @DisplayName("SshConfig copy方法保留所有字段")
    void testSshConfigCopyPreservesAllFields() {
        SshConfig original = new SshConfig();
        original.setEnabled(true);
        original.setHost("ssh.example.com");
        original.setPort(2222);
        original.setUsername("testuser");
        original.setPassword("testpass");
        original.setKeepAliveInterval(30000);
        original.setConnectionTimeout(15000);
        original.setUseCompression(true);
        original.setPrivateKeyPath("/path/to/key");
        original.setSshJumpHost("jump.example.com");
        original.setSshJumpPort(2223);
        original.setSshJumpUser("jumpuser");

        SshConfig copy = original.copy();

        assertThat(copy.isEnabled()).isEqualTo(original.isEnabled());
        assertThat(copy.getHost()).isEqualTo(original.getHost());
        assertThat(copy.getPort()).isEqualTo(original.getPort());
        assertThat(copy.getUsername()).isEqualTo(original.getUsername());
        assertThat(copy.getPassword()).isEqualTo(original.getPassword());
        assertThat(copy.getKeepAliveInterval()).isEqualTo(original.getKeepAliveInterval());
        assertThat(copy.getConnectionTimeout()).isEqualTo(original.getConnectionTimeout());
        assertThat(copy.isUseCompression()).isEqualTo(original.isUseCompression());
        assertThat(copy.getPrivateKeyPath()).isEqualTo(original.getPrivateKeyPath());
        assertThat(copy.getSshJumpHost()).isEqualTo(original.getSshJumpHost());
        assertThat(copy.getSshJumpPort()).isEqualTo(original.getSshJumpPort());
        assertThat(copy.getSshJumpUser()).isEqualTo(original.getSshJumpUser());
        assertThat(copy.isKeepAliveEnabled()).isEqualTo(original.isKeepAliveEnabled());
    }

    @Test
    @DisplayName("SshConfig isKeyAuth 判断")
    void testSshConfigIsKeyAuth() {
        SshConfig config = new SshConfig();

        assertThat(config.isKeyAuth()).isFalse();

        config.setPrivateKeyPath("");
        assertThat(config.isKeyAuth()).isFalse();

        config.setPrivateKeyPath("/path/to/key");
        assertThat(config.isKeyAuth()).isTrue();
    }

    @Test
    @DisplayName("多次停止监控不会抛出异常")
    void testMultipleStopMonitoringSafe() {
        String connId = "test-multiple-stop";

        assertDoesNotThrow(() -> {
            healthChecker.stopMonitoring(connId);
            healthChecker.stopMonitoring(connId);
            healthChecker.stopMonitoring(connId);
        });
    }

    @Test
    @DisplayName("健康检查器实例状态")
    void testHealthCheckerInstanceState() {
        assertThat(healthChecker).isNotNull();
        assertThat(healthChecker).isInstanceOf(SshTunnelHealthChecker.class);
    }
}
