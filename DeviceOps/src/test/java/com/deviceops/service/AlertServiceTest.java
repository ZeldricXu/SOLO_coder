package com.deviceops.service;

import com.deviceops.builder.TestDataBuilder;
import com.deviceops.entity.AlertRecord;
import com.deviceops.repository.AlertRecordRepository;
import com.deviceops.service.alert.AlertService;
import com.deviceops.service.device.DeviceService;
import com.deviceops.service.history.HistoryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("预警模块测试")
class AlertServiceTest {

    @Mock
    private AlertRecordRepository alertRecordRepository;

    @Mock
    private DeviceService deviceService;

    @Mock
    private HistoryService historyService;

    @InjectMocks
    private AlertService alertService;

    @Nested
    @DisplayName("预警发送测试")
    class AlertSendingTests {

        @Test
        @DisplayName("发送预警成功 - 状态异常时发送高级别预警")
        void sendStatusAbnormalAlert_Success() {
            when(deviceService.exists("device_001")).thenReturn(true);
            AlertRecord mockAlert = TestDataBuilder.buildHighLevelAlert();
            when(alertRecordRepository.save(any(AlertRecord.class))).thenReturn(mockAlert);

            AlertRecord result = alertService.sendStatusAbnormalAlert("device_001");

            assertNotNull(result);
            assertEquals("high", result.getAlertLevel());
            assertEquals("status_abnormal", result.getAlertType());
            verify(alertRecordRepository, times(1)).save(any(AlertRecord.class));
        }

        @Test
        @DisplayName("发送预警成功 - 状态预警时发送中级别预警")
        void sendWarningAlert_Success() {
            when(deviceService.exists("device_001")).thenReturn(true);
            AlertRecord mockAlert = TestDataBuilder.buildMediumLevelAlert();
            when(alertRecordRepository.save(any(AlertRecord.class))).thenReturn(mockAlert);

            AlertRecord result = alertService.sendWarningAlert("device_001");

            assertNotNull(result);
            assertEquals("medium", result.getAlertLevel());
            assertEquals("status_warning", result.getAlertType());
        }

        @Test
        @DisplayName("预警发送时记录历史")
        void sendAlert_RecordsHistory() {
            when(deviceService.exists("device_001")).thenReturn(true);
            AlertRecord mockAlert = TestDataBuilder.buildHighLevelAlert();
            when(alertRecordRepository.save(any(AlertRecord.class))).thenReturn(mockAlert);

            alertService.sendAlert("device_001", "test_alert", "high");

            verify(historyService, times(1)).recordAlert("device_001", "test_alert", "high");
        }

        @Test
        @DisplayName("状态为abnormal时检查并发送高级别预警")
        void checkAndSendAlert_AbnormalStatus_SendsHighAlert() {
            when(deviceService.exists("device_001")).thenReturn(true);
            AlertRecord mockAlert = TestDataBuilder.buildHighLevelAlert();
            when(alertRecordRepository.save(any(AlertRecord.class))).thenReturn(mockAlert);

            alertService.checkAndSendAlert("device_001", "abnormal");

            verify(alertRecordRepository, times(1)).save(any(AlertRecord.class));
        }

        @Test
        @DisplayName("状态为warning时检查并发送中级别预警")
        void checkAndSendAlert_WarningStatus_SendsMediumAlert() {
            when(deviceService.exists("device_001")).thenReturn(true);
            AlertRecord mockAlert = TestDataBuilder.buildMediumLevelAlert();
            when(alertRecordRepository.save(any(AlertRecord.class))).thenReturn(mockAlert);

            alertService.checkAndSendAlert("device_001", "warning");

            verify(alertRecordRepository, times(1)).save(any(AlertRecord.class));
        }

        @Test
        @DisplayName("状态为normal时不发送预警")
        void checkAndSendAlert_NormalStatus_NoAlert() {
            alertService.checkAndSendAlert("device_001", "normal");

            verify(alertRecordRepository, never()).save(any(AlertRecord.class));
        }
    }

    @Nested
    @DisplayName("预警确认机制测试")
    class AlertAcknowledgementTests {

        @Test
        @DisplayName("确认预警 - ack状态更新为true")
        void acknowledgeAlert_SetsAcknowledgedTrue() {
            AlertRecord unackedAlert = TestDataBuilder.buildUnacknowledgedAlert();
            when(alertRecordRepository.findById("alert_001")).thenReturn(Optional.of(unackedAlert));
            when(alertRecordRepository.save(any(AlertRecord.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            AlertRecord result = alertService.acknowledgeAlert("alert_001");

            assertTrue(result.getAcknowledged());
            verify(alertRecordRepository, times(1)).save(any(AlertRecord.class));
        }

        @Test
        @DisplayName("确认并清除预警 - 状态更新为acknowledged")
        void acknowledgeAndClear_UpdatesStatus() {
            AlertRecord unackedAlert = TestDataBuilder.buildUnacknowledgedAlert();
            when(alertRecordRepository.findById("alert_001")).thenReturn(Optional.of(unackedAlert));
            when(alertRecordRepository.save(any(AlertRecord.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            AlertRecord result = alertService.acknowledgeAndClear("alert_001");

            assertTrue(result.getAcknowledged());
            assertEquals("acknowledged", result.getAlertStatus());
        }

        @Test
        @DisplayName("获取未确认预警列表")
        void getUnacknowledgedAlerts_ReturnsList() {
            List<AlertRecord> unackedList = new ArrayList<>();
            unackedList.add(TestDataBuilder.buildUnacknowledgedAlert());
            when(alertRecordRepository.findByAcknowledged(false)).thenReturn(unackedList);

            List<AlertRecord> result = alertService.getUnacknowledgedAlerts();

            assertEquals(1, result.size());
            verify(alertRecordRepository, times(1)).findByAcknowledged(false);
        }
    }

    @Nested
    @DisplayName("预警重试机制测试")
    class AlertRetryTests {

        @Test
        @DisplayName("高级别预警 - 最大重试次数为5次")
        void getMaxRetries_HighLevel_Is5() {
            int maxRetries = alertService.getMaxRetriesForLevel("high");
            assertEquals(5, maxRetries);
        }

        @Test
        @DisplayName("中级别预警 - 最大重试次数为3次")
        void getMaxRetries_MediumLevel_Is3() {
            int maxRetries = alertService.getMaxRetriesForLevel("medium");
            assertEquals(3, maxRetries);
        }

        @Test
        @DisplayName("低级别预警 - 最大重试次数为1次")
        void getMaxRetries_LowLevel_Is1() {
            int maxRetries = alertService.getMaxRetriesForLevel("low");
            assertEquals(1, maxRetries);
        }

        @Test
        @DisplayName("不同故障级别重试次数差异验证 - 高级别可重试更多")
        void retryCountDifference_HighVsMedium() {
            int highLevelRetries = alertService.getMaxRetriesForLevel("high");
            int mediumLevelRetries = alertService.getMaxRetriesForLevel("medium");

            assertTrue(highLevelRetries > mediumLevelRetries);
            assertEquals(5, highLevelRetries);
            assertEquals(3, mediumLevelRetries);
        }

        @Test
        @DisplayName("预警重试 - 重试次数增加")
        void retryAlert_IncrementsRetryCount() {
            AlertRecord alert = TestDataBuilder.buildHighLevelAlertWithMaxRetries();
            when(alertRecordRepository.findById("alert_006")).thenReturn(Optional.of(alert));
            when(alertRecordRepository.save(any(AlertRecord.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            AlertRecord result = alertService.retryAlert("alert_006");

            assertEquals(1, result.getRetryCount());
            assertEquals("retried", result.getAlertStatus());
        }

        @Test
        @DisplayName("高级别预警 - 可连续重试5次")
        void retryAlert_HighLevel_CanRetry5Times() {
            AlertRecord alert = TestDataBuilder.buildHighLevelAlertWithMaxRetries();
            when(alertRecordRepository.findById("alert_006")).thenReturn(Optional.of(alert));
            when(alertRecordRepository.save(any(AlertRecord.class)))
                    .thenAnswer(invocation -> {
                        AlertRecord saved = invocation.getArgument(0);
                        alert.setRetryCount(saved.getRetryCount());
                        return saved;
                    });

            for (int i = 0; i < 5; i++) {
                AlertRecord result = alertService.retryAlert("alert_006");
                assertEquals(i + 1, result.getRetryCount());
            }

            AlertRecord finalResult = alertService.retryAlert("alert_006");
            assertEquals("failed", finalResult.getAlertStatus());
        }

        @Test
        @DisplayName("中级别预警 - 可连续重试3次")
        void retryAlert_MediumLevel_CanRetry3Times() {
            AlertRecord alert = TestDataBuilder.buildMediumLevelAlertWithMaxRetries();
            when(alertRecordRepository.findById("alert_007")).thenReturn(Optional.of(alert));
            when(alertRecordRepository.save(any(AlertRecord.class)))
                    .thenAnswer(invocation -> {
                        AlertRecord saved = invocation.getArgument(0);
                        alert.setRetryCount(saved.getRetryCount());
                        return saved;
                    });

            for (int i = 0; i < 3; i++) {
                AlertRecord result = alertService.retryAlert("alert_007");
                assertEquals(i + 1, result.getRetryCount());
            }

            AlertRecord finalResult = alertService.retryAlert("alert_007");
            assertEquals("failed", finalResult.getAlertStatus());
        }

        @Test
        @DisplayName("已确认预警 - 不能重试")
        void retryAlert_Acknowledged_ThrowsException() {
            AlertRecord ackedAlert = TestDataBuilder.buildAcknowledgedAlert();
            when(alertRecordRepository.findById("alert_002")).thenReturn(Optional.of(ackedAlert));

            RuntimeException exception = assertThrows(RuntimeException.class, () -> {
                alertService.retryAlert("alert_002");
            });

            assertTrue(exception.getMessage().contains("预警已确认"));
        }

        @Test
        @DisplayName("判断预警是否可重试 - 未超过最大次数时可重试")
        void canRetry_BelowMaxRetries_ReturnsTrue() {
            AlertRecord alert = TestDataBuilder.buildHighLevelAlertWithMaxRetries();
            when(alertRecordRepository.findById("alert_006")).thenReturn(Optional.of(alert));

            boolean result = alertService.canRetry("alert_006");

            assertTrue(result);
        }

        @Test
        @DisplayName("判断预警是否可重试 - 已达最大次数时不可重试")
        void canRetry_AtMaxRetries_ReturnsFalse() {
            AlertRecord alert = TestDataBuilder.buildAlertExceededRetries();
            when(alertRecordRepository.findById("alert_008")).thenReturn(Optional.of(alert));

            boolean result = alertService.canRetry("alert_008");

            assertFalse(result);
        }
    }

    @Nested
    @DisplayName("预警查询测试")
    class AlertQueryTests {

        @Test
        @DisplayName("按设备查询预警记录")
        void getAlertsByDevice_ReturnsRecords() {
            when(deviceService.exists("device_001")).thenReturn(true);
            List<AlertRecord> alerts = new ArrayList<>();
            alerts.add(TestDataBuilder.buildHighLevelAlert());
            when(alertRecordRepository.findByDeviceIdOrderByAlertTimeDesc("device_001"))
                    .thenReturn(alerts);

            List<AlertRecord> result = alertService.getAlertsByDevice("device_001");

            assertEquals(1, result.size());
        }

        @Test
        @DisplayName("按级别查询预警记录")
        void getAlertsByLevel_ReturnsRecords() {
            List<AlertRecord> alerts = new ArrayList<>();
            alerts.add(TestDataBuilder.buildHighLevelAlert());
            when(alertRecordRepository.findByAlertLevel("high")).thenReturn(alerts);

            List<AlertRecord> result = alertService.getAlertsByLevel("high");

            assertEquals(1, result.size());
        }
    }
}
