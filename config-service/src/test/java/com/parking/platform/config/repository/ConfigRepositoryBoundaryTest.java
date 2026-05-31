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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ConfigRepository 边界条件测试")
class ConfigRepositoryBoundaryTest {

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
    @DisplayName("save 边界条件测试")
    class SaveBoundaryTests {

        @Test
        @DisplayName("save - config为null应该抛出NullPointerException")
        void testSave_NullConfig() {
            assertThrows(NullPointerException.class,
                    () -> repository.save(null));
        }

        @Test
        @DisplayName("save - config的id为null时应该正常工作")
        void testSave_ConfigWithNullId() {
            ConfigEntity config = new ConfigEntity();
            config.setNamespace("test.ns");
            
            ConfigEntity saved = repository.save(config);
            assertNotNull(saved);
            assertNotNull(saved.getId());
        }

        @Test
        @DisplayName("save - 多次保存同一个id应该覆盖")
        void testSave_SameIdMultipleTimes() {
            ConfigEntity config1 = new ConfigEntity();
            config1.setNamespace("test.ns");
            config1.setParameter("key", "value1");
            
            ConfigEntity saved1 = repository.save(config1);
            String id = saved1.getId();
            
            ConfigEntity config2 = new ConfigEntity();
            config2.setId(id);
            config2.setNamespace("test.ns");
            config2.setParameter("key", "value2");
            
            ConfigEntity saved2 = repository.save(config2);
            
            assertEquals(id, saved2.getId());
            assertEquals("value2", saved2.getParameter("key"));
            
            ConfigEntity retrieved = repository.findById(id).orElseThrow();
            assertEquals("value2", retrieved.getParameter("key"));
        }

        @Test
        @DisplayName("save - namespace为null应该正常工作")
        void testSave_NullNamespace() {
            ConfigEntity config = new ConfigEntity();
            config.setNamespace(null);
            config.setParameter("key", "value");
            
            ConfigEntity saved = repository.save(config);
            assertNotNull(saved);
            assertNull(saved.getNamespace());
        }

        @Test
        @DisplayName("save - parameters为null的处理")
        void testSave_NullParameters() {
            ConfigEntity config = new ConfigEntity();
            config.setNamespace("test.ns");
            config.setParameters(null);
            
            ConfigEntity saved = repository.save(config);
            assertNotNull(saved);
            assertNull(saved.getParameters());
        }

        @Test
        @DisplayName("save - 大量配置保存")
        void testSave_ManyConfigs() {
            int count = 1000;
            for (int i = 0; i < count; i++) {
                ConfigEntity config = new ConfigEntity();
                config.setNamespace("namespace." + i);
                config.setParameter("index", i);
                repository.save(config);
            }
            
            assertEquals(count, repository.findAll().size());
        }
    }

    @Nested
    @DisplayName("findById 边界条件测试")
    class FindByIdBoundaryTests {

        @Test
        @DisplayName("findById - id为null")
        void testFindById_NullId() {
            assertFalse(repository.findById(null).isPresent());
        }

        @Test
        @DisplayName("findById - id为空字符串")
        void testFindById_EmptyId() {
            assertFalse(repository.findById("").isPresent());
        }

        @Test
        @DisplayName("findById - 不存在的id返回空Optional")
        void testFindById_NotFound() {
            assertFalse(repository.findById("non_existent_id").isPresent());
        }

        @Test
        @DisplayName("findById - 超长id")
        void testFindById_VeryLongId() {
            String longId = "a".repeat(10000);
            assertFalse(repository.findById(longId).isPresent());
        }

        @Test
        @DisplayName("findById - 特殊字符id")
        void testFindById_SpecialCharsId() {
            String specialId = "id@#$%^&*()";
            assertFalse(repository.findById(specialId).isPresent());
        }
    }

    @Nested
    @DisplayName("getById 边界条件测试")
    class GetByIdBoundaryTests {

        @Test
        @DisplayName("getById - 不存在的id抛出ResourceNotFoundException")
        void testGetById_NotFound() {
            ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                    () -> repository.getById("non_existent"));
            assertTrue(ex.getMessage().contains("Config not found"));
        }

        @Test
        @DisplayName("getById - id为null抛出ResourceNotFoundException")
        void testGetById_NullId() {
            assertThrows(ResourceNotFoundException.class,
                    () -> repository.getById(null));
        }

        @Test
        @DisplayName("getById - 存在的id正常返回")
        void testGetById_Found() {
            ConfigEntity config = new ConfigEntity();
            config.setNamespace("test.ns");
            ConfigEntity saved = repository.save(config);
            
            ConfigEntity retrieved = repository.getById(saved.getId());
            assertNotNull(retrieved);
            assertEquals(saved.getId(), retrieved.getId());
        }
    }

    @Nested
    @DisplayName("findByNamespace 边界条件测试")
    class FindByNamespaceBoundaryTests {

        @Test
        @DisplayName("findByNamespace - namespace为null返回空列表")
        void testFindByNamespace_NullNamespace() {
            List<ConfigEntity> result = repository.findByNamespace(null);
            assertNotNull(result);
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("findByNamespace - namespace为空字符串")
        void testFindByNamespace_EmptyNamespace() {
            List<ConfigEntity> result = repository.findByNamespace("");
            assertNotNull(result);
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("findByNamespace - 超长namespace")
        void testFindByNamespace_VeryLongNamespace() {
            String longNamespace = "n".repeat(10000);
            List<ConfigEntity> result = repository.findByNamespace(longNamespace);
            assertNotNull(result);
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("findByNamespace - 同一namespace多个配置")
        void testFindByNamespace_MultipleConfigsSameNamespace() {
            String namespace = "same.namespace";
            int count = 50;
            
            for (int i = 0; i < count; i++) {
                ConfigEntity config = new ConfigEntity();
                config.setNamespace(namespace);
                config.setParameter("index", i);
                repository.save(config);
            }
            
            List<ConfigEntity> result = repository.findByNamespace(namespace);
            assertEquals(count, result.size());
        }

        @Test
        @DisplayName("findByNamespace - 不同namespace互不干扰")
        void testFindByNamespace_DifferentNamespaces() {
            for (int i = 0; i < 10; i++) {
                ConfigEntity config = new ConfigEntity();
                config.setNamespace("ns." + i);
                repository.save(config);
            }
            
            for (int i = 0; i < 10; i++) {
                List<ConfigEntity> result = repository.findByNamespace("ns." + i);
                assertEquals(1, result.size());
            }
        }
    }

    @Nested
    @DisplayName("deleteById 边界条件测试")
    class DeleteByIdBoundaryTests {

        @Test
        @DisplayName("deleteById - 不存在的id抛出ResourceNotFoundException")
        void testDeleteById_NotFound() {
            assertThrows(ResourceNotFoundException.class,
                    () -> repository.deleteById("non_existent"));
        }

        @Test
        @DisplayName("deleteById - id为null抛出ResourceNotFoundException")
        void testDeleteById_NullId() {
            assertThrows(ResourceNotFoundException.class,
                    () -> repository.deleteById(null));
        }

        @Test
        @DisplayName("deleteById - 删除后findById返回空")
        void testDeleteById_DeletesConfig() {
            ConfigEntity config = new ConfigEntity();
            config.setNamespace("test.ns");
            ConfigEntity saved = repository.save(config);
            String id = saved.getId();
            
            assertTrue(repository.findById(id).isPresent());
            
            repository.deleteById(id);
            
            assertFalse(repository.findById(id).isPresent());
        }

        @Test
        @DisplayName("deleteById - 删除后版本历史也被删除")
        void testDeleteById_DeletesVersionHistory() {
            ConfigEntity config = new ConfigEntity();
            config.setNamespace("test.ns");
            ConfigEntity saved = repository.save(config);
            String id = saved.getId();
            
            ConfigVersionHistoryEntity history = new ConfigVersionHistoryEntity(id, 1);
            history.setChangeReason("create");
            history.setChangedBy("user");
            repository.saveVersionHistory(history);
            
            assertEquals(1, repository.findHistoryByConfigId(id).size());
            
            repository.deleteById(id);
            
            assertTrue(repository.findHistoryByConfigId(id).isEmpty());
        }

        @Test
        @DisplayName("deleteById - 多次删除同一个id")
        void testDeleteById_DeletingAlreadyDeleted() {
            ConfigEntity config = new ConfigEntity();
            config.setNamespace("test.ns");
            ConfigEntity saved = repository.save(config);
            String id = saved.getId();
            
            repository.deleteById(id);
            
            assertThrows(ResourceNotFoundException.class,
                    () -> repository.deleteById(id));
        }
    }

    @Nested
    @DisplayName("findAll 边界条件测试")
    class FindAllBoundaryTests {

        @Test
        @DisplayName("findAll - 空仓库返回空列表")
        void testFindAll_EmptyRepository() {
            List<ConfigEntity> result = repository.findAll();
            assertNotNull(result);
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("findAll - 返回的是新创建的列表副本")
        void testFindAll_ReturnsCopy() {
            ConfigEntity config = new ConfigEntity();
            config.setNamespace("test.ns");
            repository.save(config);
            
            List<ConfigEntity> result1 = repository.findAll();
            int size1 = result1.size();
            
            result1.add(new ConfigEntity());
            
            List<ConfigEntity> result2 = repository.findAll();
            assertEquals(size1, result2.size());
        }

        @Test
        @DisplayName("findAll - 大量配置")
        void testFindAll_ManyConfigs() {
            int count = 1000;
            for (int i = 0; i < count; i++) {
                ConfigEntity config = new ConfigEntity();
                config.setNamespace("ns." + i);
                repository.save(config);
            }
            
            assertEquals(count, repository.findAll().size());
        }
    }

    @Nested
    @DisplayName("版本历史边界条件测试")
    class VersionHistoryBoundaryTests {

        @Test
        @DisplayName("saveVersionHistory - history为null抛出NullPointerException")
        void testSaveVersionHistory_NullHistory() {
            assertThrows(NullPointerException.class,
                    () -> repository.saveVersionHistory(null));
        }

        @Test
        @DisplayName("saveVersionHistory - 同一配置多版本")
        void testSaveVersionHistory_MultipleVersions() {
            String configId = "test-config-1";
            int versionCount = 100;
            
            for (int v = 1; v <= versionCount; v++) {
                ConfigVersionHistoryEntity history = new ConfigVersionHistoryEntity(configId, v);
                history.setChangeReason("update " + v);
                repository.saveVersionHistory(history);
            }
            
            List<ConfigVersionHistoryEntity> history = repository.findHistoryByConfigId(configId);
            assertEquals(versionCount, history.size());
        }

        @Test
        @DisplayName("saveVersionHistory - 相同configId和version覆盖")
        void testSaveVersionHistory_SameVersionOverwrites() {
            String configId = "test-config-1";
            
            ConfigVersionHistoryEntity v1 = new ConfigVersionHistoryEntity(configId, 1);
            v1.setChangeReason("first");
            repository.saveVersionHistory(v1);
            
            ConfigVersionHistoryEntity v2 = new ConfigVersionHistoryEntity(configId, 1);
            v2.setChangeReason("second");
            repository.saveVersionHistory(v2);
            
            ConfigVersionHistoryEntity retrieved = repository.findHistoryVersion(configId, 1).orElseThrow();
            assertEquals("second", retrieved.getChangeReason());
        }

        @Test
        @DisplayName("findHistoryVersion - 不存在的配置返回空Optional")
        void testFindHistoryVersion_ConfigNotFound() {
            assertFalse(repository.findHistoryVersion("non_existent", 1).isPresent());
        }

        @Test
        @DisplayName("findHistoryVersion - 存在配置但不存在版本返回空Optional")
        void testFindHistoryVersion_VersionNotFound() {
            String configId = "test-config-1";
            
            ConfigVersionHistoryEntity v1 = new ConfigVersionHistoryEntity(configId, 1);
            repository.saveVersionHistory(v1);
            
            assertFalse(repository.findHistoryVersion(configId, 999).isPresent());
        }

        @Test
        @DisplayName("findHistoryVersion - version为null")
        void testFindHistoryVersion_NullVersion() {
            String configId = "test-config-1";
            
            ConfigVersionHistoryEntity v1 = new ConfigVersionHistoryEntity(configId, 1);
            repository.saveVersionHistory(v1);
            
            assertFalse(repository.findHistoryVersion(configId, null).isPresent());
        }

        @Test
        @DisplayName("findHistoryByConfigId - 不存在的配置返回空列表")
        void testFindHistoryByConfigId_NotFound() {
            List<ConfigVersionHistoryEntity> result = repository.findHistoryByConfigId("non_existent");
            assertNotNull(result);
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("findHistoryByConfigId - configId为null返回空列表")
        void testFindHistoryByConfigId_NullConfigId() {
            List<ConfigVersionHistoryEntity> result = repository.findHistoryByConfigId(null);
            assertNotNull(result);
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("findHistoryByConfigId - 版本按降序排列")
        void testFindHistoryByConfigId_VersionsInDescendingOrder() {
            String configId = "test-config-1";
            int versionCount = 10;
            
            for (int v = 1; v <= versionCount; v++) {
                ConfigVersionHistoryEntity history = new ConfigVersionHistoryEntity(configId, v);
                repository.saveVersionHistory(history);
            }
            
            List<ConfigVersionHistoryEntity> history = repository.findHistoryByConfigId(configId);
            
            for (int i = 0; i < history.size() - 1; i++) {
                assertTrue(history.get(i).getVersion() > history.get(i + 1).getVersion());
            }
        }
    }

    @Nested
    @DisplayName("回滚点边界条件测试")
    class RollbackPointBoundaryTests {

        @Test
        @DisplayName("findRollbackPoints - 不存在的配置返回空列表")
        void testFindRollbackPoints_ConfigNotFound() {
            List<ConfigVersionHistoryEntity> result = repository.findRollbackPoints("non_existent");
            assertNotNull(result);
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("findRollbackPoints - 过滤出只有rollbackPoint=true的版本")
        void testFindRollbackPoints_FiltersCorrectly() {
            String configId = "test-config-1";
            
            ConfigVersionHistoryEntity v1 = new ConfigVersionHistoryEntity(configId, 1);
            v1.setRollbackPoint(false);
            repository.saveVersionHistory(v1);
            
            ConfigVersionHistoryEntity v2 = new ConfigVersionHistoryEntity(configId, 2);
            v2.setRollbackPoint(true);
            repository.saveVersionHistory(v2);
            
            ConfigVersionHistoryEntity v3 = new ConfigVersionHistoryEntity(configId, 3);
            v3.setRollbackPoint(false);
            repository.saveVersionHistory(v3);
            
            ConfigVersionHistoryEntity v4 = new ConfigVersionHistoryEntity(configId, 4);
            v4.setRollbackPoint(true);
            repository.saveVersionHistory(v4);
            
            List<ConfigVersionHistoryEntity> rollbackPoints = repository.findRollbackPoints(configId);
            assertEquals(2, rollbackPoints.size());
            assertTrue(rollbackPoints.stream().allMatch(ConfigVersionHistoryEntity::isRollbackPoint));
        }

        @Test
        @DisplayName("findRollbackPoints - 没有回滚点返回空列表")
        void testFindRollbackPoints_NoRollbackPoints() {
            String configId = "test-config-1";
            
            for (int v = 1; v <= 5; v++) {
                ConfigVersionHistoryEntity history = new ConfigVersionHistoryEntity(configId, v);
                history.setRollbackPoint(false);
                repository.saveVersionHistory(history);
            }
            
            List<ConfigVersionHistoryEntity> result = repository.findRollbackPoints(configId);
            assertNotNull(result);
            assertTrue(result.isEmpty());
        }
    }

    @Nested
    @DisplayName("clearAll 边界条件测试")
    class ClearAllBoundaryTests {

        @Test
        @DisplayName("clearAll - 清空配置和版本历史")
        void testClearAll_EmptiesRepository() {
            ConfigEntity config = new ConfigEntity();
            config.setNamespace("test.ns");
            repository.save(config);
            
            ConfigVersionHistoryEntity history = new ConfigVersionHistoryEntity(config.getId(), 1);
            repository.saveVersionHistory(history);
            
            assertFalse(repository.findAll().isEmpty());
            
            repository.clearAll();
            
            assertTrue(repository.findAll().isEmpty());
        }

        @Test
        @DisplayName("clearAll - 重置simulateStorageFailure为false")
        void testClearAll_ResetsStorageFailureFlag() {
            repository.setSimulateStorageFailure(true);
            
            repository.clearAll();
            
            ConfigEntity config = new ConfigEntity();
            config.setNamespace("test.ns");
            assertDoesNotThrow(() -> repository.save(config));
        }

        @Test
        @DisplayName("clearAll - 多次调用安全")
        void testClearAll_MultipleCalls() {
            assertDoesNotThrow(() -> {
                repository.clearAll();
                repository.clearAll();
                repository.clearAll();
            });
        }
    }
}
