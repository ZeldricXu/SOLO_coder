package com.parking.platform.deviceshadow.service;

import com.parking.platform.common.entity.DeviceShadowEntity;
import com.parking.platform.common.exception.ValidationException;
import com.parking.platform.deviceshadow.repository.DeviceShadowRepository;
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

@DisplayName("DeviceShadowSyncService 边界条件测试")
class DeviceShadowSyncServiceBoundaryTest {

    private DeviceShadowRepository repository;
    private DeviceShadowSyncService service;

    @BeforeEach
    void setUp() {
        repository = new DeviceShadowRepository();
        service = new DeviceShadowSyncService(repository);
    }

    @AfterEach
    void tearDown() {
        service.clearAll();
    }

    @Nested
    @DisplayName("Device ID 边界条件测试")
    class DeviceIdBoundaryTests {

        @Test
        @DisplayName("创建设备影子 - deviceId为null应该抛出ValidationException")
        void testCreateShadow_NullDeviceId() {
            ValidationException ex = assertThrows(ValidationException.class,
                    () -> service.createShadow(null));
            assertEquals("Device ID cannot be null", ex.getMessage());
        }

        @Test
        @DisplayName("创建设备影子 - deviceId为空字符串应该抛出ValidationException")
        void testCreateShadow_EmptyDeviceId() {
            ValidationException ex = assertThrows(ValidationException.class,
                    () -> service.createShadow(""));
            assertEquals("Device ID cannot be blank", ex.getMessage());
        }

        @Test
        @DisplayName("创建设备影子 - deviceId为空白字符串应该抛出ValidationException")
        void testCreateShadow_BlankDeviceId() {
            ValidationException ex = assertThrows(ValidationException.class,
                    () -> service.createShadow("   "));
            assertEquals("Device ID cannot be blank", ex.getMessage());
        }

        @Test
        @DisplayName("创建设备影子 - deviceId超长应该抛出ValidationException")
        void testCreateShadow_DeviceIdTooLong() {
            String longDeviceId = "d".repeat(DeviceShadowSyncService.MAX_DEVICE_ID_LENGTH + 1);

            ValidationException ex = assertThrows(ValidationException.class,
                    () -> service.createShadow(longDeviceId));
            assertTrue(ex.getMessage().contains("exceeds maximum length"));
        }

        @Test
        @DisplayName("创建设备影子 - deviceId最大长度边界值应该成功")
        void testCreateShadow_DeviceIdMaxLengthBoundary() {
            String maxDeviceId = "d".repeat(DeviceShadowSyncService.MAX_DEVICE_ID_LENGTH);

            DeviceShadowEntity shadow = service.createShadow(maxDeviceId);
            assertNotNull(shadow);
            assertEquals(maxDeviceId, shadow.getDeviceId());
        }

        @Test
        @DisplayName("创建设备影子 - deviceId包含非法字符应该抛出ValidationException")
        void testCreateShadow_InvalidDeviceIdChars() {
            ValidationException ex = assertThrows(ValidationException.class,
                    () -> service.createShadow("device@123"));
            assertTrue(ex.getMessage().contains("alphanumeric characters"));
        }

        @Test
        @DisplayName("创建设备影子 - deviceId合法特殊字符应该成功")
        void testCreateShadow_ValidDeviceIdSpecialChars() {
            DeviceShadowEntity shadow1 = service.createShadow("device-sensor_01.gateway");
            assertNotNull(shadow1);

            DeviceShadowEntity shadow2 = service.createShadow("dev_123-abc.def");
            assertNotNull(shadow2);
        }

        @Test
        @DisplayName("重复创建同一设备影子应该抛出ValidationException")
        void testCreateShadow_Duplicate() {
            String deviceId = "device-001";
            service.createShadow(deviceId);

            ValidationException ex = assertThrows(ValidationException.class,
                    () -> service.createShadow(deviceId));
            assertTrue(ex.getMessage().contains("already exists"));
        }

        @Test
        @DisplayName("getOrCreateShadow - 不存在时自动创建")
        void testGetOrCreateShadow_CreatesWhenNotExists() {
            String deviceId = "new-device-001";
            assertFalse(repository.existsByDeviceId(deviceId));

            DeviceShadowEntity shadow = service.getOrCreateShadow(deviceId);

            assertNotNull(shadow);
            assertEquals(deviceId, shadow.getDeviceId());
            assertTrue(repository.existsByDeviceId(deviceId));
        }

        @Test
        @DisplayName("getOrCreateShadow - 已存在时返回现有")
        void testGetOrCreateShadow_ReturnsExisting() {
            String deviceId = "existing-device-001";
            DeviceShadowEntity created = service.createShadow(deviceId);

            DeviceShadowEntity retrieved = service.getOrCreateShadow(deviceId);

            assertNotNull(retrieved);
            assertEquals(created.getId(), retrieved.getId());
        }
    }

    @Nested
    @DisplayName("State Map 边界条件测试")
    class StateMapBoundaryTests {

        @Test
        @DisplayName("更新desired状态 - state为null应该抛出ValidationException")
        void testUpdateDesiredState_NullState() {
            String deviceId = "device-001";

            ValidationException ex = assertThrows(ValidationException.class,
                    () -> service.updateDesiredState(deviceId, null));
            assertEquals("desired state cannot be null", ex.getMessage());
        }

        @Test
        @DisplayName("更新desired状态 - state为空map应该抛出ValidationException")
        void testUpdateDesiredState_EmptyState() {
            String deviceId = "device-001";

            ValidationException ex = assertThrows(ValidationException.class,
                    () -> service.updateDesiredState(deviceId, Collections.emptyMap()));
            assertEquals("desired state cannot be empty", ex.getMessage());
        }

        @Test
        @DisplayName("更新reported状态 - state为null应该抛出ValidationException")
        void testUpdateReportedState_NullState() {
            String deviceId = "device-001";

            ValidationException ex = assertThrows(ValidationException.class,
                    () -> service.updateReportedState(deviceId, null));
            assertEquals("reported state cannot be null", ex.getMessage());
        }

        @Test
        @DisplayName("更新状态 - state key为null应该抛出ValidationException")
        void testUpdateState_NullKey() {
            String deviceId = "device-001";
            Map<String, Object> state = new HashMap<>();
            state.put(null, "value");

            ValidationException ex = assertThrows(ValidationException.class,
                    () -> service.updateDesiredState(deviceId, state));
            assertEquals("desired state key cannot be null or blank", ex.getMessage());
        }

        @Test
        @DisplayName("更新状态 - state key为空字符串应该抛出ValidationException")
        void testUpdateState_EmptyKey() {
            String deviceId = "device-001";
            Map<String, Object> state = new HashMap<>();
            state.put("", "value");

            ValidationException ex = assertThrows(ValidationException.class,
                    () -> service.updateDesiredState(deviceId, state));
            assertEquals("desired state key cannot be null or blank", ex.getMessage());
        }

        @Test
        @DisplayName("更新状态 - state key超长应该抛出ValidationException")
        void testUpdateState_KeyTooLong() {
            String deviceId = "device-001";
            String longKey = "k".repeat(DeviceShadowSyncService.MAX_STATE_KEY_LENGTH + 1);
            Map<String, Object> state = new HashMap<>();
            state.put(longKey, "value");

            ValidationException ex = assertThrows(ValidationException.class,
                    () -> service.updateDesiredState(deviceId, state));
            assertTrue(ex.getMessage().contains("exceeds maximum length"));
        }

        @Test
        @DisplayName("更新状态 - state条目数超过限制应该抛出ValidationException")
        void testUpdateState_TooManyEntries() {
            String deviceId = "device-001";
            Map<String, Object> state = new HashMap<>();
            for (int i = 0; i < DeviceShadowSyncService.MAX_STATE_SIZE + 1; i++) {
                state.put("key" + i, "value" + i);
            }

            ValidationException ex = assertThrows(ValidationException.class,
                    () -> service.updateDesiredState(deviceId, state));
            assertTrue(ex.getMessage().contains("exceeds maximum size"));
        }

        @Test
        @DisplayName("更新状态 - state最大数量边界值应该成功")
        void testUpdateState_MaxSizeBoundary() {
            String deviceId = "device-001";
            Map<String, Object> state = new HashMap<>();
            for (int i = 0; i < DeviceShadowSyncService.MAX_STATE_SIZE; i++) {
                state.put("key" + i, "value" + i);
            }

            DeviceShadowEntity shadow = service.updateDesiredState(deviceId, state);
            assertNotNull(shadow);
            assertEquals(DeviceShadowSyncService.MAX_STATE_SIZE, shadow.getDesired().size());
        }

        @Test
        @DisplayName("更新状态 - key最大长度边界值应该成功")
        void testUpdateState_KeyMaxLengthBoundary() {
            String deviceId = "device-001";
            String maxKey = "k".repeat(DeviceShadowSyncService.MAX_STATE_KEY_LENGTH);
            Map<String, Object> state = new HashMap<>();
            state.put(maxKey, "value");

            DeviceShadowEntity shadow = service.updateDesiredState(deviceId, state);
            assertNotNull(shadow);
            assertTrue(shadow.getDesired().containsKey(maxKey));
        }
    }

    @Nested
    @DisplayName("Version 边界条件测试")
    class VersionBoundaryTests {

        @Test
        @DisplayName("新建影子版本号初始值应为1")
        void testCreateShadow_InitialVersions() {
            String deviceId = "device-001";
            DeviceShadowEntity shadow = service.createShadow(deviceId);

            assertEquals(1, shadow.getDesiredVersion());
            assertEquals(1, shadow.getReportedVersion());
        }

        @Test
        @DisplayName("更新desired状态 - 版本号应该递增")
        void testUpdateDesiredState_VersionIncrements() {
            String deviceId = "device-001";
            service.createShadow(deviceId);

            Map<String, Object> state1 = new HashMap<>();
            state1.put("key1", "value1");
            DeviceShadowEntity s1 = service.updateDesiredState(deviceId, state1);
            assertEquals(2, s1.getDesiredVersion());
            assertEquals(1, s1.getReportedVersion());

            Map<String, Object> state2 = new HashMap<>();
            state2.put("key2", "value2");
            DeviceShadowEntity s2 = service.updateDesiredState(deviceId, state2);
            assertEquals(3, s2.getDesiredVersion());
            assertEquals(1, s2.getReportedVersion());
        }

        @Test
        @DisplayName("更新reported状态 - 版本号应该递增")
        void testUpdateReportedState_VersionIncrements() {
            String deviceId = "device-001";
            service.createShadow(deviceId);

            Map<String, Object> state1 = new HashMap<>();
            state1.put("sensor1", 100);
            DeviceShadowEntity s1 = service.updateReportedState(deviceId, state1);
            assertEquals(1, s1.getDesiredVersion());
            assertEquals(2, s1.getReportedVersion());

            Map<String, Object> state2 = new HashMap<>();
            state2.put("sensor2", 200);
            DeviceShadowEntity s2 = service.updateReportedState(deviceId, state2);
            assertEquals(1, s2.getDesiredVersion());
            assertEquals(3, s2.getReportedVersion());
        }

        @Test
        @DisplayName("sync后 - reported版本号应该递增")
        void testSync_ReportedVersionIncrements() throws Exception {
            String deviceId = "device-001";

            Map<String, Object> desired = new HashMap<>();
            desired.put("target_temp", 25);
            service.updateDesiredState(deviceId, desired);

            DeviceShadowEntity synced = service.syncWithDevice(deviceId);

            assertTrue(synced.getDesiredVersion() < synced.getReportedVersion());
        }
    }

    @Nested
    @DisplayName("Status 边界条件测试")
    class StatusBoundaryTests {

        @Test
        @DisplayName("新建影子状态应为idle")
        void testCreateShadow_DefaultStatus() {
            String deviceId = "device-001";
            DeviceShadowEntity shadow = service.createShadow(deviceId);

            assertEquals("idle", shadow.getStatus());
        }

        @Test
        @DisplayName("更新desired状态后状态应为pending_sync")
        void testUpdateDesiredState_StatusChange() {
            String deviceId = "device-001";
            service.createShadow(deviceId);

            Map<String, Object> state = new HashMap<>();
            state.put("key", "value");
            DeviceShadowEntity shadow = service.updateDesiredState(deviceId, state);

            assertEquals("pending_sync", shadow.getStatus());
        }

        @Test
        @DisplayName("空desired时应该视为已同步")
        void testIsSynced_EmptyDesired() {
            String deviceId = "device-001";
            DeviceShadowEntity shadow = service.createShadow(deviceId);

            assertTrue(shadow.isSynced());
        }

        @Test
        @DisplayName("desired和reported相同时应该视为已同步")
        void testIsSynced_SameContent() {
            String deviceId = "device-001";

            Map<String, Object> state = new HashMap<>();
            state.put("temp", 25);

            service.updateDesiredState(deviceId, state);
            service.updateReportedState(deviceId, state);

            DeviceShadowEntity shadow = service.getShadowByDeviceId(deviceId);
            assertTrue(shadow.isSynced());
        }

        @Test
        @DisplayName("desired和reported不同时应该视为未同步")
        void testIsSynced_DifferentContent() {
            String deviceId = "device-001";

            Map<String, Object> desired = new HashMap<>();
            desired.put("temp", 25);
            service.updateDesiredState(deviceId, desired);

            Map<String, Object> reported = new HashMap<>();
            reported.put("temp", 20);
            service.updateReportedState(deviceId, reported);

            DeviceShadowEntity shadow = service.getShadowByDeviceId(deviceId);
            assertFalse(shadow.isSynced());
        }
    }

    @Nested
    @DisplayName("Diff 边界条件测试")
    class DiffBoundaryTests {

        @Test
        @DisplayName("空desired时diff应该为空")
        void testGetDiff_EmptyDesired() {
            String deviceId = "device-001";
            service.createShadow(deviceId);

            Map<String, Object> diff = service.getDiff(deviceId);
            assertTrue(diff.isEmpty());
        }

        @Test
        @DisplayName("desired和reported完全相同时diff应该为空")
        void testGetDiff_FullySynced() {
            String deviceId = "device-001";

            Map<String, Object> state = new HashMap<>();
            state.put("temp", 25);
            state.put("humidity", 60);

            service.updateDesiredState(deviceId, state);
            service.updateReportedState(deviceId, state);

            Map<String, Object> diff = service.getDiff(deviceId);
            assertTrue(diff.isEmpty());
        }

        @Test
        @DisplayName("desired和reported不同时diff应该包含差异")
        void testGetDiff_WithDifferences() {
            String deviceId = "device-001";

            Map<String, Object> desired = new HashMap<>();
            desired.put("temp", 25);
            desired.put("mode", "auto");
            service.updateDesiredState(deviceId, desired);

            Map<String, Object> reported = new HashMap<>();
            reported.put("temp", 20);
            service.updateReportedState(deviceId, reported);

            Map<String, Object> diff = service.getDiff(deviceId);
            assertEquals(2, diff.size());
            assertTrue(diff.containsKey("temp"));
            assertTrue(diff.containsKey("mode"));
        }

        @Test
        @DisplayName("reported有额外字段但desired没有时diff应该只包含desired的差异")
        void testGetDiff_ReportedHasExtra() {
            String deviceId = "device-001";

            Map<String, Object> desired = new HashMap<>();
            desired.put("temp", 25);
            service.updateDesiredState(deviceId, desired);

            Map<String, Object> reported = new HashMap<>();
            reported.put("temp", 25);
            reported.put("extra_field", "value");
            service.updateReportedState(deviceId, reported);

            Map<String, Object> diff = service.getDiff(deviceId);
            assertTrue(diff.isEmpty());
        }
    }
}
