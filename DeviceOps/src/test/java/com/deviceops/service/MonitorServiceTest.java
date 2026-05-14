package com.deviceops.service;

import com.deviceops.builder.TestDataBuilder;
import com.deviceops.entity.StatusRecord;
import com.deviceops.exception.DeviceOpsException;
import com.deviceops.repository.StatusRecordRepository;
import com.deviceops.service.device.DeviceService;
import com.deviceops.service.monitor.MonitorService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("监控模块测试")
class MonitorServiceTest {

    @Mock
    private StatusRecordRepository statusRecordRepository;

    @Mock
    private DeviceService deviceService;

    @InjectMocks
    private MonitorService monitorService;

    @Nested
    @DisplayName("状态采集测试")
    class StatusCollectionTests {

        @Test
        @DisplayName("状态采集 - 设备存在时采集成功")
        void collectStatus_DeviceExists_Success() {
            when(deviceService.exists("device_001")).thenReturn(true);
            when(statusRecordRepository.save(any(StatusRecord.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            StatusRecord result = monitorService.collectStatus("device_001", "cpu");

            assertNotNull(result);
            assertEquals("device_001", result.getDeviceId());
            assertEquals("cpu", result.getStatusType());
            assertNotNull(result.getStatusValue());
            assertNotNull(result.getStatusLevel());
            verify(statusRecordRepository, times(1)).save(any(StatusRecord.class));
        }

        @Test
        @DisplayName("状态采集 - 设备不存在时抛出异常")
        void collectStatus_DeviceNotExists_ThrowsException() {
            when(deviceService.exists("device_999")).thenReturn(false);

            DeviceOpsException exception = assertThrows(DeviceOpsException.class, () -> {
                monitorService.collectStatus("device_999", "cpu");
            });

            assertEquals(404, exception.getCode());
            verify(statusRecordRepository, never()).save(any(StatusRecord.class));
        }

        @Test
        @DisplayName("采集全部状态 - CPU、内存、网络三种类型")
        void collectAllStatus_CollectsAllTypes() {
            when(deviceService.exists("device_001")).thenReturn(true);
            when(statusRecordRepository.save(any(StatusRecord.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));
            when(deviceService.updateDeviceStatus(anyString(), anyString()))
                    .thenReturn(TestDataBuilder.buildDevice());

            Map<String, StatusRecord> results = monitorService.collectAllStatus("device_001");

            assertEquals(3, results.size());
            assertTrue(results.containsKey("cpu"));
            assertTrue(results.containsKey("memory"));
            assertTrue(results.containsKey("network"));
            verify(statusRecordRepository, times(3)).save(any(StatusRecord.class));
        }

        @Test
        @DisplayName("正常状态值 - 状态级别为normal")
        void collectStatus_NormalValue_LevelIsNormal() {
            when(deviceService.exists("device_001")).thenReturn(true);
            when(statusRecordRepository.save(any(StatusRecord.class)))
                    .thenAnswer(invocation -> {
                        StatusRecord record = invocation.getArgument(0);
                        record.setStatusValue(50);
                        record.setStatusLevel("normal");
                        return record;
                    });

            StatusRecord result = monitorService.collectStatus("device_001", "cpu");

            assertEquals("normal", result.getStatusLevel());
        }

        @Test
        @DisplayName("获取最新状态 - 从历史记录中提取")
        void getLatestStatus_FromHistoryRecords() {
            when(deviceService.exists("device_001")).thenReturn(true);
            List<StatusRecord> records = TestDataBuilder.buildStatusRecordList("device_001");
            when(statusRecordRepository.findTop10ByDeviceIdOrderByStatusTimeDesc("device_001"))
                    .thenReturn(records);

            Map<String, Integer> status = monitorService.getLatestStatus("device_001");

            assertEquals(3, status.size());
            assertTrue(status.containsKey("cpu"));
            assertTrue(status.containsKey("memory"));
            assertTrue(status.containsKey("network"));
        }

        @Test
        @DisplayName("获取最新状态 - 无历史记录时生成默认值")
        void getLatestStatus_NoHistory_GeneratesDefaults() {
            when(deviceService.exists("device_001")).thenReturn(true);
            when(statusRecordRepository.findTop10ByDeviceIdOrderByStatusTimeDesc("device_001"))
                    .thenReturn(Collections.emptyList());

            Map<String, Integer> status = monitorService.getLatestStatus("device_001");

            assertFalse(status.isEmpty());
            assertTrue(status.containsKey("cpu"));
            assertTrue(status.containsKey("memory"));
        }
    }

    @Nested
    @DisplayName("状态级别判断测试")
    class StatusLevelTests {

        @Test
        @DisplayName("存在异常状态记录时 - hasAbnormalStatus返回true")
        void hasAbnormalStatus_WithAbnormalRecord_ReturnsTrue() {
            List<StatusRecord> records = new ArrayList<>();
            records.add(TestDataBuilder.buildAbnormalStatusRecord());
            records.add(TestDataBuilder.buildNormalStatusRecord());
            when(statusRecordRepository.findTop10ByDeviceIdOrderByStatusTimeDesc("device_001"))
                    .thenReturn(records);

            boolean result = monitorService.hasAbnormalStatus("device_001");

            assertTrue(result);
        }

        @Test
        @DisplayName("无异常状态记录时 - hasAbnormalStatus返回false")
        void hasAbnormalStatus_NoAbnormalRecord_ReturnsFalse() {
            List<StatusRecord> records = new ArrayList<>();
            records.add(TestDataBuilder.buildNormalStatusRecord());
            records.add(TestDataBuilder.buildWarningStatusRecord());
            when(statusRecordRepository.findTop10ByDeviceIdOrderByStatusTimeDesc("device_001"))
                    .thenReturn(records);

            boolean result = monitorService.hasAbnormalStatus("device_001");

            assertFalse(result);
        }

        @Test
        @DisplayName("存在异常记录时 - 设备状态判断为abnormal")
        void determineDeviceStatus_WithAbnormal_ReturnsAbnormal() {
            List<StatusRecord> records = new ArrayList<>();
            records.add(TestDataBuilder.buildAbnormalStatusRecord());
            when(statusRecordRepository.findTop10ByDeviceIdOrderByStatusTimeDesc("device_001"))
                    .thenReturn(records);

            String status = monitorService.determineDeviceStatus("device_001");

            assertEquals("abnormal", status);
        }

        @Test
        @DisplayName("仅存在预警记录时 - 设备状态判断为warning")
        void determineDeviceStatus_WithWarningOnly_ReturnsWarning() {
            List<StatusRecord> records = new ArrayList<>();
            records.add(TestDataBuilder.buildWarningStatusRecord());
            records.add(TestDataBuilder.buildNormalStatusRecord());
            when(statusRecordRepository.findTop10ByDeviceIdOrderByStatusTimeDesc("device_001"))
                    .thenReturn(records);

            String status = monitorService.determineDeviceStatus("device_001");

            assertEquals("warning", status);
        }

        @Test
        @DisplayName("全部正常记录时 - 设备状态判断为normal")
        void determineDeviceStatus_AllNormal_ReturnsNormal() {
            List<StatusRecord> records = new ArrayList<>();
            records.add(TestDataBuilder.buildNormalStatusRecord());
            when(statusRecordRepository.findTop10ByDeviceIdOrderByStatusTimeDesc("device_001"))
                    .thenReturn(records);

            String status = monitorService.determineDeviceStatus("device_001");

            assertEquals("normal", status);
        }

        @Test
        @DisplayName("无记录时 - 设备状态默认为normal")
        void determineDeviceStatus_NoRecords_ReturnsNormal() {
            when(statusRecordRepository.findTop10ByDeviceIdOrderByStatusTimeDesc("device_001"))
                    .thenReturn(Collections.emptyList());

            String status = monitorService.determineDeviceStatus("device_001");

            assertEquals("normal", status);
        }
    }

    @Nested
    @DisplayName("状态历史查询测试")
    class StatusHistoryTests {

        @Test
        @DisplayName("查询状态历史 - 设备存在时返回记录列表")
        void getStatusHistory_DeviceExists_ReturnsRecords() {
            when(deviceService.exists("device_001")).thenReturn(true);
            List<StatusRecord> records = TestDataBuilder.buildStatusRecordList("device_001");
            when(statusRecordRepository.findByDeviceIdOrderByStatusTimeDesc("device_001"))
                    .thenReturn(records);

            List<StatusRecord> result = monitorService.getStatusHistory("device_001");

            assertEquals(3, result.size());
            verify(statusRecordRepository, times(1))
                    .findByDeviceIdOrderByStatusTimeDesc("device_001");
        }

        @Test
        @DisplayName("查询状态历史 - 设备不存在时抛出异常")
        void getStatusHistory_DeviceNotExists_ThrowsException() {
            when(deviceService.exists("device_999")).thenReturn(false);

            DeviceOpsException exception = assertThrows(DeviceOpsException.class, () -> {
                monitorService.getStatusHistory("device_999");
            });

            assertEquals(404, exception.getCode());
        }
    }
}
