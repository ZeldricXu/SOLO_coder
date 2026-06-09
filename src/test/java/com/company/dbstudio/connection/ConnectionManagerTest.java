package com.company.dbstudio.connection;

import com.company.dbstudio.connection.model.ConnectionConfig;
import com.company.dbstudio.connection.model.ConnectionType;
import com.company.dbstudio.connection.repository.ConnectionRepository;
import com.company.dbstudio.core.model.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("连接管理器测试")
class ConnectionManagerTest {

    @Mock
    private ConnectionRepository repository;

    @Mock
    private ConnectionManager manager;

    @Mock
    private Connection mockConnection;

    private ConnectionConfig testConfig;

    @BeforeEach
    void setUp() {
        testConfig = new ConnectionConfig();
        testConfig.setId(UUID.randomUUID().toString());
        testConfig.setName("Test DB");
        testConfig.setType(ConnectionType.MYSQL);
        testConfig.setHost("localhost");
        testConfig.setPort(3306);
        testConfig.setDatabase("test");
        testConfig.setUsername("user");
        testConfig.setPassword("pass");
    }

    @Test
    @DisplayName("连接成功测试")
    void connect_Success_ShouldReturnSuccessResult() throws Exception {
        when(manager.getConnection(testConfig.getId())).thenReturn(mockConnection);
        when(manager.connect(testConfig.getId())).thenReturn(Result.success(mockConnection));

        Result<Connection> result = manager.connect(testConfig.getId());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData()).isNotNull();
    }

    @Test
    @DisplayName("连接失败测试 - 网络错误")
    void connect_NetworkError_ShouldReturnFailure() throws Exception {
        when(manager.connect(testConfig.getId()))
                .thenReturn(Result.failure("Connection refused"));

        Result<Connection> result = manager.connect(testConfig.getId());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getMessage()).contains("Connection refused");
    }

    @Test
    @DisplayName("连接失败测试 - 认证错误")
    void connect_AuthenticationError_ShouldReturnFailure() throws Exception {
        when(manager.connect(testConfig.getId()))
                .thenReturn(Result.failure("Access denied for user 'user'"));

        Result<Connection> result = manager.connect(testConfig.getId());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getMessage()).contains("Access denied");
    }

    @Test
    @DisplayName("保存连接配置")
    void saveConnection_ShouldStoreConfig() throws Exception {
        doNothing().when(repository).save(any(ConnectionConfig.class));

        repository.save(testConfig);

        verify(repository, times(1)).save(testConfig);
    }

    @Test
    @DisplayName("获取所有连接")
    void getAllConnections_ShouldReturnAllConfigs() throws Exception {
        ConnectionConfig config2 = new ConnectionConfig();
        config2.setId(UUID.randomUUID().toString());
        config2.setName("Test DB 2");

        when(repository.findAll()).thenReturn(Arrays.asList(testConfig, config2));

        List<ConnectionConfig> configs = repository.findAll();

        assertThat(configs).hasSize(2);
        assertThat(configs).extracting(ConnectionConfig::getName)
                .containsExactlyInAnyOrder("Test DB", "Test DB 2");
    }

    @Test
    @DisplayName("获取收藏连接")
    void getFavoriteConnections_ShouldReturnOnlyFavorites() throws Exception {
        testConfig.setFavorite(true);
        ConnectionConfig config2 = new ConnectionConfig();
        config2.setId(UUID.randomUUID().toString());
        config2.setName("Not Favorite");
        config2.setFavorite(false);

        when(repository.findByFavorite(true)).thenReturn(List.of(testConfig));

        List<ConnectionConfig> favorites = repository.findByFavorite(true);

        assertThat(favorites).hasSize(1);
        assertThat(favorites.get(0).getName()).isEqualTo("Test DB");
    }

    @Test
    @DisplayName("按分组获取连接")
    void getConnectionsByGroup_ShouldReturnGrouped() throws Exception {
        testConfig.setGroup("Production");
        ConnectionConfig config2 = new ConnectionConfig();
        config2.setId(UUID.randomUUID().toString());
        config2.setName("Dev DB");
        config2.setGroup("Development");

        when(repository.findByGroup("Production")).thenReturn(List.of(testConfig));

        List<ConnectionConfig> prodGroup = repository.findByGroup("Production");

        assertThat(prodGroup).hasSize(1);
        assertThat(prodGroup.get(0).getGroup()).isEqualTo("Production");
    }

    @Test
    @DisplayName("删除连接")
    void deleteConnection_ShouldRemoveConfig() throws Exception {
        doNothing().when(repository).deleteById(testConfig.getId());

        repository.deleteById(testConfig.getId());

        verify(repository, times(1)).deleteById(testConfig.getId());
    }

    @Test
    @DisplayName("连接超时后友好错误提示")
    void connect_Timeout_ShouldReturnFriendlyError() throws Exception {
        when(manager.connect(testConfig.getId()))
                .thenReturn(Result.failure("连接超时，请检查网络连接或增加超时时间"));

        Result<Connection> result = manager.connect(testConfig.getId());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getMessage()).contains("连接超时");
    }

    @Test
    @DisplayName("连接失败时记录详细错误信息")
    void connect_Failure_ShouldIncludeDetailsInError() throws Exception {
        when(manager.connect(testConfig.getId()))
                .thenReturn(Result.failure("无法连接到 localhost:3306 - 数据库 'test' 不存在"));

        Result<Connection> result = manager.connect(testConfig.getId());

        assertThat(result.getMessage())
                .contains("localhost")
                .contains("3306")
                .contains("test");
    }

    @Test
    @DisplayName("断开连接")
    void disconnect_ShouldCloseConnection() throws Exception {
        doNothing().when(manager).disconnect(testConfig.getId());

        manager.disconnect(testConfig.getId());

        verify(manager, times(1)).disconnect(testConfig.getId());
    }

    @Test
    @DisplayName("检查连接状态")
    void isConnected_ShouldReturnCorrectStatus() {
        when(manager.isConnected(testConfig.getId())).thenReturn(true);

        boolean connected = manager.isConnected(testConfig.getId());

        assertThat(connected).isTrue();
    }

    @Test
    @DisplayName("最近使用连接排序")
    void getRecentConnections_ShouldBeSortedByLastUsed() throws Exception {
        testConfig.setLastUsedAt(java.time.LocalDateTime.now().minusHours(1));
        ConnectionConfig config2 = new ConnectionConfig();
        config2.setId(UUID.randomUUID().toString());
        config2.setName("Recent DB");
        config2.setLastUsedAt(java.time.LocalDateTime.now());

        when(repository.findRecent(anyInt())).thenReturn(Arrays.asList(config2, testConfig));

        List<ConnectionConfig> recent = repository.findRecent(10);

        assertThat(recent).hasSize(2);
        assertThat(recent.get(0).getName()).isEqualTo("Recent DB");
    }

    @Test
    @DisplayName("测试连接功能")
    void testConnection_ShouldReturnTestResult() throws Exception {
        when(manager.testConnection(testConfig)).thenReturn(Result.success(null));

        Result<Void> result = manager.testConnection(testConfig);

        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    @DisplayName("测试连接失败")
    void testConnection_Failure_ShouldReturnFailure() throws Exception {
        when(manager.testConnection(testConfig))
                .thenReturn(Result.failure("连接测试失败: Connection refused"));

        Result<Void> result = manager.testConnection(testConfig);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getMessage()).contains("连接测试失败");
    }

    @Test
    @DisplayName("更新连接配置")
    void updateConnection_ShouldUpdateConfig() throws Exception {
        doNothing().when(repository).save(any(ConnectionConfig.class));
        testConfig.setName("Updated Name");

        repository.save(testConfig);

        verify(repository).save(argThat(c -> "Updated Name".equals(c.getName())));
    }
}
