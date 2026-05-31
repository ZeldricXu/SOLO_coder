package com.parking.platform.config.service;

import com.parking.platform.common.entity.ConfigEntity;
import com.parking.platform.common.exception.ConfigRollbackException;
import com.parking.platform.common.exception.ConfigVersionNotFoundException;
import com.parking.platform.common.exception.ResourceNotFoundException;
import com.parking.platform.config.repository.ConfigRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ConfigVersionManagerService 异常路径测试")
class ConfigVersionManagerServiceExceptionTest {

    private ConfigRepository repository;
    private ConfigVersionManagerService service;

    @BeforeEach
    void setUp() {
        repository = new ConfigRepository();
        service = new ConfigVersionManagerService(repository);
    }

    @AfterEach
    void tearDown() {
        service.clearAll();
    }

    @Nested
    @DisplayName("资源不存在异常测试")
    class ResourceNotFoundTests {

        @Test
        @DisplayName("获取不存在的配置应该抛出ResourceNotFoundException")
        void testGetConfig_NotFound() {
            ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                    () -> service.getConfig("non_existent_id"));
            assertEquals(404, ex.getCode());
            assertTrue(ex.getMessage().contains("Config not found"));
        }

        @Test
        @DisplayName("更新不存在的配置应该抛出ResourceNotFoundException")
        void testUpdateConfig_NotFound() {
            Map<String, Object> updates = new HashMap<>();
            updates.put("key", "value");

            ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                    () -> service.updateConfig("non_existent_id", updates, "reason", "user"));
            assertEquals(404, ex.getCode());
        }

        @Test
        @DisplayName("删除不存在的配置应该抛出ResourceNotFoundException")
        void testDeleteConfig_NotFound() {
            ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                    () -> service.deleteConfig("non_existent_id"));
            assertEquals(404, ex.getCode());
        }

        @Test
        @DisplayName("切换不存在配置的开关状态应该抛出ResourceNotFoundException")
        void testToggleConfig_NotFound() {
            ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                    () -> service.toggleConfig("non_existent_id", true));
            assertEquals(404, ex.getCode());
        }

        @Test
        @DisplayName("查询不存在配置的历史应该返回空列表")
        void testGetConfigHistory_NotFound_ReturnsEmpty() {
            var history = service.getConfigHistory("non_existent_id");
            assertNotNull(history);
            assertTrue(history.isEmpty());
        }

        @Test
        @DisplayName("查询不存在的历史版本应该抛出ConfigVersionNotFoundException")
        void testGetConfigHistoryVersion_NotFound() {
            ConfigVersionNotFoundException ex = assertThrows(ConfigVersionNotFoundException.class,
                    () -> service.getConfigHistoryVersion("non_existent_id", 1));
            assertEquals(404, ex.getCode());
        }
    }

    @Nested
    @DisplayName("回滚异常测试")
    class RollbackExceptionTests {

        @Test
        @DisplayName("回滚到当前版本或更高版本应该抛出ConfigRollbackException")
        void testRollbackToVersion_TargetTooHigh() {
            Map<String, Object> params = new HashMap<>();
            params.put("key", "value");
            ConfigEntity config = service.createConfig("test.ns", params, "create", "user");
            String configId = config.getId();

            Map<String, Object> updates = new HashMap<>();
            updates.put("key2", "value2");
            service.updateConfig(configId, updates, "update", "user");

            ConfigEntity v3 = service.updateConfig(configId, Collections.emptyMap(), "another update", "user");
            int currentVersion = v3.getVersion();

            ConfigRollbackException ex1 = assertThrows(ConfigRollbackException.class,
                    () -> service.rollbackToVersion(configId, currentVersion, "comment", "user"));
            assertTrue(ex1.getMessage().contains("Cannot rollback to version"));

            ConfigRollbackException ex2 = assertThrows(ConfigRollbackException.class,
                    () -> service.rollbackToVersion(configId, currentVersion + 100, "comment", "user"));
            assertTrue(ex2.getMessage().contains("Cannot rollback to version"));
        }

        @Test
        @DisplayName("回滚到不存在的历史版本应该抛出ConfigVersionNotFoundException")
        void testRollbackToVersion_VersionNotFound() {
            Map<String, Object> params = new HashMap<>();
            params.put("key", "value");
            ConfigEntity config = service.createConfig("test.ns", params, "create", "user");
            String configId = config.getId();

            Map<String, Object> updates = new HashMap<>();
            updates.put("key2", "value2");
            service.updateConfig(configId, updates, "update", "user");

            ConfigVersionNotFoundException ex = assertThrows(ConfigVersionNotFoundException.class,
                    () -> service.rollbackToVersion(configId, 999, "comment", "user"));
            assertEquals(404, ex.getCode());
        }

        @Test
        @DisplayName("回滚不存在的配置应该抛出ResourceNotFoundException")
        void testRollbackToVersion_ConfigNotFound() {
            ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                    () -> service.rollbackToVersion("non_existent", 1, "comment", "user"));
            assertEquals(404, ex.getCode());
        }
    }

    @Nested
    @DisplayName("存储层故障模拟测试")
    class StorageFailureTests {

        @Test
        @DisplayName("存储层故障 - 创建配置应该失败")
        void testCreateConfig_StorageFailure() {
            repository.setSimulateStorageFailure(true);

            Map<String, Object> params = new HashMap<>();
            params.put("key", "value");

            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> service.createConfig("test.ns", params, "reason", "user"));
            assertTrue(ex.getMessage().contains("Storage layer failure"));
        }

        @Test
        @DisplayName("存储层故障 - 获取配置应该失败")
        void testGetConfig_StorageFailure() {
            Map<String, Object> params = new HashMap<>();
            params.put("key", "value");
            ConfigEntity config = service.createConfig("test.ns", params, "reason", "user");
            String configId = config.getId();

            repository.setSimulateStorageFailure(true);

            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> service.getConfig(configId));
            assertTrue(ex.getMessage().contains("Storage layer failure"));
        }

        @Test
        @DisplayName("存储层故障 - 更新配置应该失败")
        void testUpdateConfig_StorageFailure() {
            Map<String, Object> params = new HashMap<>();
            params.put("key", "value");
            ConfigEntity config = service.createConfig("test.ns", params, "reason", "user");
            String configId = config.getId();

            repository.setSimulateStorageFailure(true);

            Map<String, Object> updates = new HashMap<>();
            updates.put("key2", "value2");

            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> service.updateConfig(configId, updates, "reason", "user"));
            assertTrue(ex.getMessage().contains("Storage layer failure"));
        }

        @Test
        @DisplayName("存储层故障 - 回滚应该失败")
        void testRollback_StorageFailure() {
            Map<String, Object> params = new HashMap<>();
            params.put("key", "value");
            ConfigEntity config = service.createConfig("test.ns", params, "create", "user");
            String configId = config.getId();

            Map<String, Object> updates = new HashMap<>();
            updates.put("key2", "value2");
            service.updateConfig(configId, updates, "update", "user");

            repository.setSimulateStorageFailure(true);

            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> service.rollbackToVersion(configId, 1, "comment", "user"));
            assertTrue(ex.getMessage().contains("Storage layer failure"));
        }

        @Test
        @DisplayName("存储层故障恢复后应该正常工作")
        void testStorageFailureRecovery() {
            Map<String, Object> params = new HashMap<>();
            params.put("key", "value");
            ConfigEntity config = service.createConfig("test.ns", params, "reason", "user");
            String configId = config.getId();

            repository.setSimulateStorageFailure(true);
            assertThrows(RuntimeException.class,
                    () -> service.getConfig(configId));

            repository.setSimulateStorageFailure(false);
            ConfigEntity recovered = service.getConfig(configId);
            assertNotNull(recovered);
            assertEquals(configId, recovered.getId());
        }
    }

    @Nested
    @DisplayName("重复创建测试")
    class DuplicateTests {

        @Test
        @DisplayName("同一namespace可以创建多个配置")
        void testCreateConfig_SameNamespace_MultipleConfigs() {
            Map<String, Object> params1 = new HashMap<>();
            params1.put("key", "value1");
            ConfigEntity config1 = service.createConfig("same.ns", params1, "create 1", "user");

            Map<String, Object> params2 = new HashMap<>();
            params2.put("key", "value2");
            ConfigEntity config2 = service.createConfig("same.ns", params2, "create 2", "user");

            assertNotNull(config1);
            assertNotNull(config2);
            assertFalse(config1.getId().equals(config2.getId()));

            var configs = service.getConfigsByNamespace("same.ns");
            assertEquals(2, configs.size());
        }
    }

    @Nested
    @DisplayName("部分更新测试")
    class PartialUpdateTests {

        @Test
        @DisplayName("null更新应该只递增版本号，不改变参数")
        void testUpdateConfig_NullUpdates() {
            Map<String, Object> params = new HashMap<>();
            params.put("key1", "value1");
            params.put("key2", "value2");
            ConfigEntity config = service.createConfig("test.ns", params, "create", "user");
            String configId = config.getId();

            ConfigEntity updated = service.updateConfig(configId, null, "null update", "user");

            assertEquals(2, updated.getVersion());
            assertEquals("value1", updated.getParameter("key1"));
            assertEquals("value2", updated.getParameter("key2"));
        }

        @Test
        @DisplayName("空map更新应该只递增版本号")
        void testUpdateConfig_EmptyMapUpdates() {
            Map<String, Object> params = new HashMap<>();
            params.put("key1", "value1");
            ConfigEntity config = service.createConfig("test.ns", params, "create", "user");
            String configId = config.getId();

            ConfigEntity updated = service.updateConfig(configId, Collections.emptyMap(), "empty update", "user");

            assertEquals(2, updated.getVersion());
            assertEquals(1, updated.getParameters().size());
        }
    }

    @Nested
    @DisplayName("边界回滚测试")
    class EdgeRollbackTests {

        @Test
        @DisplayName("回滚后参数应该恢复为目标版本的内容")
        void testRollback_ParametersRestored() {
            Map<String, Object> v1Params = new HashMap<>();
            v1Params.put("stage", "v1");
            v1Params.put("value", 100);
            ConfigEntity v1 = service.createConfig("test.ns", v1Params, "create v1", "user");
            String configId = v1.getId();

            Map<String, Object> v2Updates = new HashMap<>();
            v2Updates.put("stage", "v2");
            v2Updates.put("value", 200);
            v2Updates.put("new_key", "new_value");
            ConfigEntity v2 = service.updateConfig(configId, v2Updates, "update to v2", "user");

            Map<String, Object> v3Updates = new HashMap<>();
            v3Updates.put("stage", "v3");
            v3Updates.put("another", "data");
            service.updateConfig(configId, v3Updates, "update to v3", "user");

            ConfigEntity rolledBack = service.rollbackToVersion(configId, 2, "rollback to v2", "admin");

            assertEquals("v2", rolledBack.getParameter("stage"));
            assertEquals(200, rolledBack.getParameter("value"));
            assertEquals("new_value", rolledBack.getParameter("new_key"));
            assertEquals(5, rolledBack.getVersion());
        }

        @Test
        @DisplayName("回滚历史记录应该正确保存")
        void testRollback_HistorySaved() {
            Map<String, Object> v1Params = new HashMap<>();
            v1Params.put("key", "v1");
            ConfigEntity v1 = service.createConfig("test.ns", v1Params, "create v1", "user");
            String configId = v1.getId();

            Map<String, Object> v2Updates = new HashMap<>();
            v2Updates.put("key", "v2");
            service.updateConfig(configId, v2Updates, "update to v2", "user");

            ConfigEntity rolledBack = service.rollbackToVersion(configId, 1, "rollback", "admin");

            var history = service.getConfigHistory(configId);
            assertTrue(history.size() >= 4);

            var rollbackPoints = service.getRollbackPoints(configId);
            assertTrue(rollbackPoints.size() >= 1);
        }
    }
}
