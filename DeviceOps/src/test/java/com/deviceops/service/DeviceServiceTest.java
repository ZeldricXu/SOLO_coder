package com.deviceops.service;

import com.deviceops.builder.TestDataBuilder;
import com.deviceops.dto.DeviceCreateRequest;
import com.deviceops.entity.Device;
import com.deviceops.exception.DeviceOpsException;
import com.deviceops.repository.DeviceRepository;
import com.deviceops.service.device.DeviceService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("设备管理模块测试")
class DeviceServiceTest {

    @Mock
    private DeviceRepository deviceRepository;

    @InjectMocks
    private DeviceService deviceService;

    @Nested
    @DisplayName("设备录入测试")
    class DeviceCreationTests {

        @Test
        @DisplayName("创建设备成功 - 验证设备基本信息录入正确性")
        void createDevice_Success() {
            DeviceCreateRequest request = TestDataBuilder.buildDeviceCreateRequest(
                    "测试服务器", "server", "机房A-机架1"
            );
            Device mockDevice = TestDataBuilder.buildDevice(
                    "device_001", "测试服务器", "server", "机房A-机架1"
            );
            when(deviceRepository.save(any(Device.class))).thenReturn(mockDevice);

            Device result = deviceService.createDevice(request);

            assertNotNull(result);
            assertEquals("device_001", result.getDeviceId());
            assertEquals("测试服务器", result.getDeviceName());
            assertEquals("server", result.getDeviceType());
            assertEquals("机房A-机架1", result.getDeviceLocation());
            verify(deviceRepository, times(1)).save(any(Device.class));
        }

        @Test
        @DisplayName("创建设备时状态默认为正常")
        void createDevice_DefaultStatusIsNormal() {
            DeviceCreateRequest request = TestDataBuilder.buildDeviceCreateRequest();
            Device mockDevice = TestDataBuilder.buildNormalDevice();
            when(deviceRepository.save(any(Device.class))).thenReturn(mockDevice);

            Device result = deviceService.createDevice(request);

            assertEquals("normal", result.getDeviceStatus());
        }

        @Test
        @DisplayName("创建设备时自动生成设备ID")
        void createDevice_GeneratesDeviceId() {
            DeviceCreateRequest request = TestDataBuilder.buildDeviceCreateRequest();
            Device mockDevice = TestDataBuilder.buildDevice();
            when(deviceRepository.save(any(Device.class))).thenReturn(mockDevice);

            Device result = deviceService.createDevice(request);

            assertNotNull(result.getDeviceId());
            assertTrue(result.getDeviceId().startsWith("device_"));
        }
    }

    @Nested
    @DisplayName("设备配置管理测试")
    class DeviceConfigurationTests {

        @Test
        @DisplayName("查询设备成功")
        void getDevice_Success() {
            Device mockDevice = TestDataBuilder.buildDevice();
            when(deviceRepository.findById("device_001")).thenReturn(Optional.of(mockDevice));

            Device result = deviceService.getDevice("device_001");

            assertNotNull(result);
            assertEquals("device_001", result.getDeviceId());
        }

        @Test
        @DisplayName("查询设备不存在时抛出异常")
        void getDevice_NotFound_ThrowsException() {
            when(deviceRepository.findById("device_999")).thenReturn(Optional.empty());

            DeviceOpsException exception = assertThrows(DeviceOpsException.class, () -> {
                deviceService.getDevice("device_999");
            });

            assertEquals(404, exception.getCode());
            assertTrue(exception.getMessage().contains("device_999"));
        }

        @Test
        @DisplayName("更新设备配置成功")
        void updateDevice_Success() {
            Device existingDevice = TestDataBuilder.buildDevice();
            DeviceCreateRequest updateRequest = TestDataBuilder.buildDeviceCreateRequest(
                    "更新后的设备名", "network", "机房B"
            );
            when(deviceRepository.findById("device_001")).thenReturn(Optional.of(existingDevice));
            when(deviceRepository.save(any(Device.class))).thenReturn(existingDevice);

            Device result = deviceService.updateDevice("device_001", updateRequest);

            assertEquals("更新后的设备名", result.getDeviceName());
            assertEquals("network", result.getDeviceType());
            assertEquals("机房B", result.getDeviceLocation());
        }

        @Test
        @DisplayName("检查设备存在")
        void exists_WhenDeviceExists_ReturnsTrue() {
            when(deviceRepository.existsById("device_001")).thenReturn(true);

            boolean result = deviceService.exists("device_001");

            assertTrue(result);
        }

        @Test
        @DisplayName("检查设备不存在")
        void exists_WhenDeviceNotExists_ReturnsFalse() {
            when(deviceRepository.existsById("device_999")).thenReturn(false);

            boolean result = deviceService.exists("device_999");

            assertFalse(result);
        }
    }

    @Nested
    @DisplayName("设备状态生命周期测试")
    class DeviceStatusLifecycleTests {

        @Test
        @DisplayName("设备状态从正常转为异常")
        void updateDeviceStatus_NormalToAbnormal() {
            Device normalDevice = TestDataBuilder.buildNormalDevice();
            when(deviceRepository.findById("device_001")).thenReturn(Optional.of(normalDevice));
            when(deviceRepository.save(any(Device.class))).thenAnswer(invocation -> invocation.getArgument(0));

            Device result = deviceService.updateDeviceStatus("device_001", "abnormal");

            assertEquals("abnormal", result.getDeviceStatus());
            verify(deviceRepository, times(1)).save(any(Device.class));
        }

        @Test
        @DisplayName("设备状态从异常转为修复中")
        void updateDeviceStatus_AbnormalToProcessing() {
            Device abnormalDevice = TestDataBuilder.buildAbnormalDevice();
            when(deviceRepository.findById("device_002")).thenReturn(Optional.of(abnormalDevice));
            when(deviceRepository.save(any(Device.class))).thenAnswer(invocation -> invocation.getArgument(0));

            Device result = deviceService.updateDeviceStatus("device_002", "warning");

            assertEquals("warning", result.getDeviceStatus());
        }

        @Test
        @DisplayName("设备状态从修复中转回正常 - 完整生命周期验证")
        void updateDeviceStatus_FullLifecycle() {
            Device device = TestDataBuilder.buildNormalDevice();
            when(deviceRepository.findById("device_001")).thenReturn(Optional.of(device));
            when(deviceRepository.save(any(Device.class))).thenAnswer(invocation -> invocation.getArgument(0));

            Device step1 = deviceService.updateDeviceStatus("device_001", "abnormal");
            assertEquals("abnormal", step1.getDeviceStatus());

            Device step2 = deviceService.updateDeviceStatus("device_001", "warning");
            assertEquals("warning", step2.getDeviceStatus());

            Device step3 = deviceService.updateDeviceStatus("device_001", "normal");
            assertEquals("normal", step3.getDeviceStatus());

            verify(deviceRepository, times(3)).save(any(Device.class));
        }

        @Test
        @DisplayName("统计正常设备数量")
        void countByStatus_NormalDevices() {
            when(deviceRepository.countByDeviceStatus("normal")).thenReturn(50L);

            long count = deviceService.countByStatus("normal");

            assertEquals(50L, count);
        }

        @Test
        @DisplayName("统计异常设备数量")
        void countByStatus_AbnormalDevices() {
            when(deviceRepository.countByDeviceStatus("abnormal")).thenReturn(5L);

            long count = deviceService.countByStatus("abnormal");

            assertEquals(5L, count);
        }

        @Test
        @DisplayName("统计总设备数量")
        void count_TotalDevices() {
            when(deviceRepository.count()).thenReturn(100L);

            long count = deviceService.count();

            assertEquals(100L, count);
        }
    }

    @Nested
    @DisplayName("设备删除测试")
    class DeviceDeletionTests {

        @Test
        @DisplayName("删除设备成功")
        void deleteDevice_Success() {
            when(deviceRepository.existsById("device_001")).thenReturn(true);
            doNothing().when(deviceRepository).deleteById("device_001");

            deviceService.deleteDevice("device_001");

            verify(deviceRepository, times(1)).deleteById("device_001");
        }

        @Test
        @DisplayName("删除不存在的设备时抛出异常")
        void deleteDevice_NotFound_ThrowsException() {
            when(deviceRepository.existsById("device_999")).thenReturn(false);

            DeviceOpsException exception = assertThrows(DeviceOpsException.class, () -> {
                deviceService.deleteDevice("device_999");
            });

            assertEquals(404, exception.getCode());
        }
    }
}
