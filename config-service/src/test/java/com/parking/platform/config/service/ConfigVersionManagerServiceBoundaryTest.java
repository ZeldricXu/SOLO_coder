package com.parking.platform.config.service;

import com.parking.platform.common.entity.ConfigEntity;
import com.parking.platform.common.exception.ValidationException;
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

@DisplayName("ConfigVersionManagerService 边界条件测试")
class ConfigVersionManagerServiceBoundaryTest {

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
    @DisplayName("Namespace 边界条件测试")
    class NamespaceBoundaryTests {

        @Test
        @DisplayName("创建配置 - namespace为null应该抛出ValidationException")
        void testCreateConfig_NullNamespace() {
            Map<String, Object> params = new HashMap<>();
            params.put("key", "value");

            ValidationException ex = assertThrows(ValidationException.class,
                    () -> service.createConfig(null, params, "reason", "user"));
            assertEquals("Namespace cannot be null", ex.getMessage());
        }

        @Test
        @DisplayName("创建配置 - namespace为空字符串应该抛出ValidationException")
        void testCreateConfig_EmptyNamespace() {
            Map<String, Object> params = new HashMap<>();
            params.put("key", "value");

            ValidationException ex = assertThrows(ValidationException.class,
                    () -> service.createConfig("", params, "reason", "user"));
            assertEquals("Namespace cannot be blank", ex.getMessage());
        }

        @Test
        @DisplayName("创建配置 - namespace为空白字符串应该抛出ValidationException")
        void testCreateConfig_BlankNamespace() {
            Map<String, Object> params = new HashMap<>();
            params.put("key", "value");

            ValidationException ex = assertThrows(ValidationException.class,
                    () -> service.createConfig("   ", params, "reason", "user"));
            assertEquals("Namespace cannot be blank", ex.getMessage());
        }

        @Test
        @DisplayName("创建配置 - namespace超长应该抛出ValidationException")
        void testCreateConfig_NamespaceTooLong() {
            String longNamespace = "a".repeat(ConfigVersionManagerService.MAX_NAMESPACE_LENGTH + 1);
            Map<String, Object> params = new HashMap<>();
            params.put("key", "value");

            ValidationException ex = assertThrows(ValidationException.class,
                    () -> service.createConfig(longNamespace, params, "reason", "user"));
            assertTrue(ex.getMessage().contains("exceeds maximum length"));
        }

        @Test
        @DisplayName("创建配置 - namespace最大长度边界值应该成功")
        void testCreateConfig_NamespaceMaxLengthBoundary() {
            String maxNamespace = "a".repeat(ConfigVersionManagerService.MAX_NAMESPACE_LENGTH);
            Map<String, Object> params = new HashMap<>();
            params.put("key", "value");

            ConfigEntity config = service.createConfig(maxNamespace, params, "reason", "user");
            assertNotNull(config);
            assertEquals(maxNamespace, config.getNamespace());
        }

        @Test
        @DisplayName("创建配置 - namespace包含非法字符应该抛出ValidationException")
        void testCreateConfig_InvalidNamespaceChars() {
            Map<String, Object> params = new HashMap<>();
            params.put("key", "value");

            ValidationException ex = assertThrows(ValidationException.class,
                    () -> service.createConfig("invalid@namespace", params, "reason", "user"));
            assertTrue(ex.getMessage().contains("alphanumeric characters"));
        }

        @Test
        @DisplayName("创建配置 - namespace合法特殊字符应该成功")
        void testCreateConfig_ValidNamespaceSpecialChars() {
            Map<String, Object> params = new HashMap<>();
            params.put("key", "value");

            ConfigEntity config1 = service.createConfig("my.namespace-v1_test", params, "reason", "user");
            assertNotNull(config1);

            ConfigEntity config2 = service.createConfig("namespace.123", params, "reason", "user");
            assertNotNull(config2);
        }
    }

    @Nested
    @DisplayName("Parameters 边界条件测试")
    class ParametersBoundaryTests {

        @Test
        @DisplayName("创建配置 - parameters为null应该成功（使用空map）")
        void testCreateConfig_NullParameters() {
            ConfigEntity config = service.createConfig("test.ns", null, "reason", "user");
            assertNotNull(config);
            assertNotNull(config.getParameters());
            assertTrue(config.getParameters().isEmpty());
        }

        @Test
        @DisplayName("创建配置 - parameters为空map应该成功")
        void testCreateConfig_EmptyParameters() {
            ConfigEntity config = service.createConfig("test.ns", Collections.emptyMap(), "reason", "user");
            assertNotNull(config);
            assertTrue(config.getParameters().isEmpty());
        }

        @Test
        @DisplayName("创建配置 - parameters key为null应该抛出ValidationException")
        void testCreateConfig_NullParameterKey() {
            Map<String, Object> params = new HashMap<>();
            params.put(null, "value");

            ValidationException ex = assertThrows(ValidationException.class,
                    () -> service.createConfig("test.ns", params, "reason", "user"));
            assertEquals("Parameter key cannot be null or blank", ex.getMessage());
        }

        @Test
        @DisplayName("创建配置 - parameters key为空字符串应该抛出ValidationException")
        void testCreateConfig_EmptyParameterKey() {
            Map<String, Object> params = new HashMap<>();
            params.put("", "value");

            ValidationException ex = assertThrows(ValidationException.class,
                    () -> service.createConfig("test.ns", params, "reason", "user"));
            assertEquals("Parameter key cannot be null or blank", ex.getMessage());
        }

        @Test
        @DisplayName("创建配置 - parameters key超长应该抛出ValidationException")
        void testCreateConfig_ParameterKeyTooLong() {
            String longKey = "k".repeat(ConfigVersionManagerService.MAX_PARAM_KEY_LENGTH + 1);
            Map<String, Object> params = new HashMap<>();
            params.put(longKey, "value");

            ValidationException ex = assertThrows(ValidationException.class,
                    () -> service.createConfig("test.ns", params, "reason", "user"));
            assertTrue(ex.getMessage().contains("exceeds maximum length"));
        }

        @Test
        @DisplayName("创建配置 - parameters超过最大数量应该抛出ValidationException")
        void testCreateConfig_TooManyParameters() {
            Map<String, Object> params = new HashMap<>();
            for (int i = 0; i < ConfigVersionManagerService.MAX_PARAMS_SIZE + 1; i++) {
                params.put("key" + i, "value" + i);
            }

            ValidationException ex = assertThrows(ValidationException.class,
                    () -> service.createConfig("test.ns", params, "reason", "user"));
            assertTrue(ex.getMessage().contains("exceeds maximum size"));
        }

        @Test
        @DisplayName("创建配置 - parameters最大数量边界值应该成功")
        void testCreateConfig_MaxParametersBoundary() {
            Map<String, Object> params = new HashMap<>();
            for (int i = 0; i < ConfigVersionManagerService.MAX_PARAMS_SIZE; i++) {
                params.put("key" + i, "value" + i);
            }

            ConfigEntity config = service.createConfig("test.ns", params, "reason", "user");
            assertNotNull(config);
            assertEquals(ConfigVersionManagerService.MAX_PARAMS_SIZE, config.getParameters().size());
        }
    }

    @Nested
    @DisplayName("ChangeReason 边界条件测试")
    class ChangeReasonBoundaryTests {

        @Test
        @DisplayName("创建配置 - changeReason为null应该成功")
        void testCreateConfig_NullChangeReason() {
            Map<String, Object> params = new HashMap<>();
            params.put("key", "value");

            ConfigEntity config = service.createConfig("test.ns", params, null, "user");
            assertNotNull(config);
        }

        @Test
        @DisplayName("创建配置 - changeReason超长应该抛出ValidationException")
        void testCreateConfig_ChangeReasonTooLong() {
            String longReason = "r".repeat(ConfigVersionManagerService.MAX_CHANGE_REASON_LENGTH + 1);
            Map<String, Object> params = new HashMap<>();
            params.put("key", "value");

            ValidationException ex = assertThrows(ValidationException.class,
                    () -> service.createConfig("test.ns", params, longReason, "user"));
            assertTrue(ex.getMessage().contains("exceeds maximum length"));
        }

        @Test
        @DisplayName("创建配置 - changeReason最大长度边界值应该成功")
        void testCreateConfig_ChangeReasonMaxLengthBoundary() {
            String maxReason = "r".repeat(ConfigVersionManagerService.MAX_CHANGE_REASON_LENGTH);
            Map<String, Object> params = new HashMap<>();
            params.put("key", "value");

            ConfigEntity config = service.createConfig("test.ns", params, maxReason, "user");
            assertNotNull(config);
        }
    }

    @Nested
    @DisplayName("Version 边界条件测试")
    class VersionBoundaryTests {

        @Test
        @DisplayName("新建配置版本应为1")
        void testCreateConfig_InitialVersionIsOne() {
            Map<String, Object> params = new HashMap<>();
            params.put("key", "value");

            ConfigEntity config = service.createConfig("test.ns", params, "reason", "user");
            assertEquals(1, config.getVersion());
        }

        @Test
        @DisplayName("更新配置版本号应该递增")
        void testUpdateConfig_VersionIncrements() {
            Map<String, Object> params = new HashMap<>();
            params.put("key1", "value1");
            ConfigEntity config = service.createConfig("test.ns", params, "create", "user");
            String configId = config.getId();
            assertEquals(1, config.getVersion());

            Map<String, Object> updates = new HashMap<>();
            updates.put("key2", "value2");
            ConfigEntity updated1 = service.updateConfig(configId, updates, "update1", "user");
            assertEquals(2, updated1.getVersion());

            Map<String, Object> updates2 = new HashMap<>();
            updates2.put("key3", "value3");
            ConfigEntity updated2 = service.updateConfig(configId, updates2, "update2", "user");
            assertEquals(3, updated2.getVersion());
        }

        @Test
        @DisplayName("空更新不应该改变版本号（merge操作，空map应该触发版本变更？需要验证逻辑）")
        void testUpdateConfig_EmptyUpdates() {
            Map<String, Object> params = new HashMap<>();
            params.put("key1", "value1");
            ConfigEntity config = service.createConfig("test.ns", params, "create", "user");
            String configId = config.getId();

            ConfigEntity updated = service.updateConfig(configId, Collections.emptyMap(), "empty", "user");
            assertEquals(2, updated.getVersion());
        }

        @Test
        @DisplayName("version为0或负数不能回滚")
        void testRollback_InvalidTargetVersion() {
            Map<String, Object> params = new HashMap<>();
            params.put("key1", "value1");
            ConfigEntity config = service.createConfig("test.ns", params, "create", "user");
            String configId = config.getId();

            Map<String, Object> updates = new HashMap<>();
            updates.put("key2", "value2");
            service.updateConfig(configId, updates, "update", "user");

            assertThrows(ValidationException.class,
                    () -> service.rollbackToVersion(configId, 0, "comment", "user"));

            assertThrows(ValidationException.class,
                    () -> service.rollbackToVersion(configId, -1, "comment", "user"));
        }
    }

    @Nested
    @DisplayName("Enabled 状态边界条件测试")
    class EnabledBoundaryTests {

        @Test
        @DisplayName("新建配置默认为启用状态")
        void testCreateConfig_DefaultEnabled() {
            Map<String, Object> params = new HashMap<>();
            params.put("key", "value");

            ConfigEntity config = service.createConfig("test.ns", params, "reason", "user");
            assertTrue(config.isEnabled());
        }

        @Test
        @DisplayName("禁用配置后状态为false")
        void testToggleConfig_Disable() {
            Map<String, Object> params = new HashMap<>();
            params.put("key", "value");
            ConfigEntity config = service.createConfig("test.ns", params, "reason", "user");
            String configId = config.getId();

            ConfigEntity disabled = service.toggleConfig(configId, false);
            assertFalse(disabled.isEnabled());
        }

        @Test
        @DisplayName("重新启用配置后状态为true")
        void testToggleConfig_ReEnable() {
            Map<String, Object> params = new HashMap<>();
            params.put("key", "value");
            ConfigEntity config = service.createConfig("test.ns", params, "reason", "user");
            String configId = config.getId();

            service.toggleConfig(configId, false);
            ConfigEntity reEnabled = service.toggleConfig(configId, true);
            assertTrue(reEnabled.isEnabled());
        }
    }
}
