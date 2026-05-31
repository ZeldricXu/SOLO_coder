package com.parking.platform.config.repository;

import com.parking.platform.common.entity.ConfigEntity;
import com.parking.platform.common.entity.ConfigVersionHistoryEntity;
import com.parking.platform.common.exception.ResourceNotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ConfigRepository 异常路径测试")
class ConfigRepositoryExceptionTest {

    private ConfigRepository repository;

    @BeforeEach
    void setUp() {
        repository = new ConfigRepository();
    }

    @AfterEach
    void tearDown() {
        repository.clearAll();
    }

    @Nested
    @DisplayName("存储层故障模拟测试")
    class StorageFailureTests {

        @Test
        @DisplayName("save - 存储故障时抛出RuntimeException")
        void testSave_StorageFailure() {
            repository.setSimulateStorageFailure(true);
            
            ConfigEntity config = new ConfigEntity();
            config.setNamespace("test.ns");
            
            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> repository.save(config));
            assertTrue(ex.getMessage().contains("Storage layer failure"));
        }

        @Test
        @DisplayName("findById - 存储故障时抛出RuntimeException")
        void testFindById_StorageFailure() {
            ConfigEntity config = new ConfigEntity();
            config.setNamespace("test.ns");
            ConfigEntity saved = repository.save(config);
            String id = saved.getId();
            
            repository.setSimulateStorageFailure(true);
            
            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> repository.findById(id));
            assertTrue(ex.getMessage().contains("Storage layer failure"));
        }

        @Test
        @DisplayName("getById - 存储故障时抛出RuntimeException")
        void testGetById_StorageFailure() {
            ConfigEntity config = new ConfigEntity();
            config.setNamespace("test.ns");
            ConfigEntity saved = repository.save(config);
            String id = saved.getId();
            
            repository.setSimulateStorageFailure(true);
            
            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> repository.getById(id));
            assertTrue(ex.getMessage().contains("Storage layer failure"));
        }

        @Test
        @DisplayName("findByNamespace - 存储故障时抛出RuntimeException")
        void testFindByNamespace_StorageFailure() {
            repository.setSimulateStorageFailure(true);
            
            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> repository.findByNamespace("test.ns"));
            assertTrue(ex.getMessage().contains("Storage layer failure"));
        }

        @Test
        @DisplayName("deleteById - 存储故障时抛出RuntimeException")
        void testDeleteById_StorageFailure() {
            ConfigEntity config = new ConfigEntity();
            config.setNamespace("test.ns");
            ConfigEntity saved = repository.save(config);
            String id = saved.getId();
            
            repository.setSimulateStorageFailure(true);
            
            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> repository.deleteById(id));
            assertTrue(ex.getMessage().contains("Storage layer failure"));
        }

        @Test
        @DisplayName("findAll - 存储故障时抛出RuntimeException")
        void testFindAll_StorageFailure() {
            repository.setSimulateStorageFailure(true);
            
            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> repository.findAll());
            assertTrue(ex.getMessage().contains("Storage layer failure"));
        }

        @Test
        @DisplayName("saveVersionHistory - 存储故障时抛出RuntimeException")
        void testSaveVersionHistory_StorageFailure() {
            repository.setSimulateStorageFailure(true);
            
            ConfigVersionHistoryEntity history = new ConfigVersionHistoryEntity("config-1", 1);
            
            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> repository.saveVersionHistory(history));
            assertTrue(ex.getMessage().contains("Storage layer failure"));
        }

        @Test
        @DisplayName("findHistoryVersion - 存储故障时抛出RuntimeException")
        void testFindHistoryVersion_StorageFailure() {
            repository.setSimulateStorageFailure(true);
            
            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> repository.findHistoryVersion("config-1", 1));
            assertTrue(ex.getMessage().contains("Storage layer failure"));
        }

        @Test
        @DisplayName("findHistoryByConfigId - 存储故障时抛出RuntimeException")
        void testFindHistoryByConfigId_StorageFailure() {
            repository.setSimulateStorageFailure(true);
            
            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> repository.findHistoryByConfigId("config-1"));
            assertTrue(ex.getMessage().contains("Storage layer failure"));
        }

        @Test
        @DisplayName("findRollbackPoints - 存储故障时抛出RuntimeException")
        void testFindRollbackPoints_StorageFailure() {
            repository.setSimulateStorageFailure(true);
            
            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> repository.findRollbackPoints("config-1"));
            assertTrue(ex.getMessage().contains("Storage layer failure"));
        }

        @Test
        @DisplayName("存储故障恢复后操作正常")
        void testStorageFailure_Recovery() {
            ConfigEntity config = new ConfigEntity();
            config.setNamespace("test.ns");
            ConfigEntity saved = repository.save(config);
            String id = saved.getId();
            
            repository.setSimulateStorageFailure(true);
            assertThrows(RuntimeException.class, () -> repository.findById(id));
            
            repository.setSimulateStorageFailure(false);
            
            ConfigEntity recovered = repository.findById(id).orElseThrow();
            assertNotNull(recovered);
            assertEquals(id, recovered.getId());
        }
    }

    @Nested
    @DisplayName("空指针异常测试")
    class NullPointerExceptionTests {

        @Test
        @DisplayName("save - null config抛出NullPointerException")
        void testSave_NullConfig() {
            assertThrows(NullPointerException.class,
                    () -> repository.save(null));
        }

        @Test
        @DisplayName("saveVersionHistory - null history抛出NullPointerException")
        void testSaveVersionHistory_NullHistory() {
            assertThrows(NullPointerException.class,
                    () -> repository.saveVersionHistory(null));
        }

        @Test
        @DisplayName("deleteById - null id抛出ResourceNotFoundException")
        void testDeleteById_NullId() {
            assertThrows(ResourceNotFoundException.class,
                    () -> repository.deleteById(null));
        }

        @Test
        @DisplayName("getById - null id抛出ResourceNotFoundException")
        void testGetById_NullId() {
            assertThrows(ResourceNotFoundException.class,
                    () -> repository.getById(null));
        }
    }

    @Nested
    @DisplayName("资源不存在异常测试")
    class ResourceNotFoundTests {

        @Test
        @DisplayName("getById - 不存在的id抛出ResourceNotFoundException")
        void testGetById_NotFound() {
            ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                    () -> repository.getById("non_existent_id"));
            assertTrue(ex.getMessage().contains("Config not found"));
        }

        @Test
        @DisplayName("deleteById - 不存在的id抛出ResourceNotFoundException")
        void testDeleteById_NotFound() {
            ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                    () -> repository.deleteById("non_existent_id"));
            assertTrue(ex.getMessage().contains("Config not found"));
        }

        @Test
        @DisplayName("findById - 不存在的id返回空Optional")
        void testFindById_NotFound_ReturnsEmpty() {
            assertFalse(repository.findById("non_existent_id").isPresent());
        }

        @Test
        @DisplayName("findHistoryVersion - 不存在的配置返回空Optional")
        void testFindHistoryVersion_ConfigNotFound_ReturnsEmpty() {
            assertFalse(repository.findHistoryVersion("non_existent", 1).isPresent());
        }

        @Test
        @DisplayName("findHistoryVersion - 存在配置但不存在版本返回空Optional")
        void testFindHistoryVersion_VersionNotFound_ReturnsEmpty() {
            String configId = "test-config";
            ConfigVersionHistoryEntity v1 = new ConfigVersionHistoryEntity(configId, 1);
            repository.saveVersionHistory(v1);
            
            assertFalse(repository.findHistoryVersion(configId, 999).isPresent());
        }

        @Test
        @DisplayName("findByNamespace - 不存在的namespace返回空列表")
        void testFindByNamespace_NotFound_ReturnsEmpty() {
            List<ConfigEntity> result = repository.findByNamespace("non_existent_namespace");
            assertNotNull(result);
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("findHistoryByConfigId - 不存在的配置返回空列表")
        void testFindHistoryByConfigId_NotFound_ReturnsEmpty() {
            List<ConfigVersionHistoryEntity> result = repository.findHistoryByConfigId("non_existent");
            assertNotNull(result);
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("findRollbackPoints - 不存在的配置返回空列表")
        void testFindRollbackPoints_NotFound_ReturnsEmpty() {
            List<ConfigVersionHistoryEntity> result = repository.findRollbackPoints("non_existent");
            assertNotNull(result);
            assertTrue(result.isEmpty());
        }
    }

    @Nested
    @DisplayName("边界场景异常测试")
    class EdgeCaseExceptionTests {

        @Test
        @DisplayName("删除后再次删除 - 抛出ResourceNotFoundException")
        void testDelete_Twice() {
            ConfigEntity config = new ConfigEntity();
            config.setNamespace("test.ns");
            ConfigEntity saved = repository.save(config);
            String id = saved.getId();
            
            repository.deleteById(id);
            
            assertThrows(ResourceNotFoundException.class,
                    () -> repository.deleteById(id));
        }

        @Test
        @DisplayName("删除后版本历史也被清除")
        void testDelete_ClearsVersionHistory() {
            ConfigEntity config = new ConfigEntity();
            config.setNamespace("test.ns");
            ConfigEntity saved = repository.save(config);
            String id = saved.getId();
            
            ConfigVersionHistoryEntity v1 = new ConfigVersionHistoryEntity(id, 1);
            v1.setRollbackPoint(true);
            repository.saveVersionHistory(v1);
            
            ConfigVersionHistoryEntity v2 = new ConfigVersionHistoryEntity(id, 2);
            repository.saveVersionHistory(v2);
            
            assertEquals(2, repository.findHistoryByConfigId(id).size());
            assertEquals(1, repository.findRollbackPoints(id).size());
            
            repository.deleteById(id);
            
            assertTrue(repository.findHistoryByConfigId(id).isEmpty());
            assertTrue(repository.findRollbackPoints(id).isEmpty());
        }

        @Test
        @DisplayName("clearAll后操作 - 正常工作")
        void testClearAll_RepositoryEmpty() {
            for (int i = 0; i < 10; i++) {
                ConfigEntity config = new ConfigEntity();
                config.setNamespace("ns." + i);
                repository.save(config);
            }
            
            repository.clearAll();
            
            assertTrue(repository.findAll().isEmpty());
            
            ConfigEntity newConfig = new ConfigEntity();
            newConfig.setNamespace("new.ns");
            ConfigEntity saved = repository.save(newConfig);
            
            assertNotNull(saved);
            assertEquals(1, repository.findAll().size());
        }

        @Test
        @DisplayName("大量操作后clearAll - 完全清空")
        void testClearAll_AfterManyOperations() {
            int configCount = 100;
            List<String> configIds = new ArrayList<>();
            
            for (int i = 0; i < configCount; i++) {
                ConfigEntity config = new ConfigEntity();
                config.setNamespace("ns." + i);
                String id = repository.save(config).getId();
                configIds.add(id);
                
                ConfigVersionHistoryEntity v1 = new ConfigVersionHistoryEntity(id, 1);
                v1.setRollbackPoint(true);
                repository.saveVersionHistory(v1);
            }
            
            repository.clearAll();
            
            assertTrue(repository.findAll().isEmpty());
            
            for (String id : configIds) {
                assertFalse(repository.findById(id).isPresent());
                assertTrue(repository.findHistoryByConfigId(id).isEmpty());
            }
        }
    }

    @Nested
    @DisplayName("空参数安全测试")
    class NullParameterSafetyTests {

        @Test
        @DisplayName("findById - null id安全返回")
        void testFindById_NullId_Safe() {
            assertDoesNotThrow(() -> repository.findById(null));
            assertFalse(repository.findById(null).isPresent());
        }

        @Test
        @DisplayName("findByNamespace - null namespace安全返回")
        void testFindByNamespace_NullNamespace_Safe() {
            assertDoesNotThrow(() -> repository.findByNamespace(null));
            assertTrue(repository.findByNamespace(null).isEmpty());
        }

        @Test
        @DisplayName("findHistoryVersion - null configId安全返回")
        void testFindHistoryVersion_NullConfigId_Safe() {
            assertDoesNotThrow(() -> repository.findHistoryVersion(null, 1));
            assertFalse(repository.findHistoryVersion(null, 1).isPresent());
        }

        @Test
        @DisplayName("findHistoryVersion - null version安全返回")
        void testFindHistoryVersion_NullVersion_Safe() {
            assertDoesNotThrow(() -> repository.findHistoryVersion("config-1", null));
            assertFalse(repository.findHistoryVersion("config-1", null).isPresent());
        }

        @Test
        @DisplayName("findHistoryByConfigId - null configId安全返回")
        void testFindHistoryByConfigId_NullConfigId_Safe() {
            assertDoesNotThrow(() -> repository.findHistoryByConfigId(null));
            assertTrue(repository.findHistoryByConfigId(null).isEmpty());
        }

        @Test
        @DisplayName("findRollbackPoints - null configId安全返回")
        void testFindRollbackPoints_NullConfigId_Safe() {
            assertDoesNotThrow(() -> repository.findRollbackPoints(null));
            assertTrue(repository.findRollbackPoints(null).isEmpty());
        }
    }

    @Nested
    @DisplayName("版本覆盖测试")
    class VersionOverwriteTests {

        @Test
        @DisplayName("saveVersionHistory - 相同configId和version覆盖旧数据")
        void testSaveVersionHistory_OverwritesSameVersion() {
            String configId = "test-config-overwrite";
            
            ConfigVersionHistoryEntity v1Original = new ConfigVersionHistoryEntity(configId, 1);
            v1Original.setChangeReason("original reason");
            v1Original.setChangedBy("original user");
            repository.saveVersionHistory(v1Original);
            
            ConfigVersionHistoryEntity retrieved1 = repository.findHistoryVersion(configId, 1).orElseThrow();
            assertEquals("original reason", retrieved1.getChangeReason());
            assertEquals("original user", retrieved1.getChangedBy());
            
            ConfigVersionHistoryEntity v1New = new ConfigVersionHistoryEntity(configId, 1);
            v1New.setChangeReason("new reason");
            v1New.setChangedBy("new user");
            repository.saveVersionHistory(v1New);
            
            ConfigVersionHistoryEntity retrieved2 = repository.findHistoryVersion(configId, 1).orElseThrow();
            assertEquals("new reason", retrieved2.getChangeReason());
            assertEquals("new user", retrieved2.getChangedBy());
            
            assertEquals(1, repository.findHistoryByConfigId(configId).size());
        }
    }
}
