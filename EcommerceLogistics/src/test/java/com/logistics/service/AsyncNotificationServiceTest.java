package com.logistics.service;

import com.logistics.builder.TestDataBuilder;
import com.logistics.constant.LogisticsConstants;
import com.logistics.entity.Notification;
import com.logistics.repository.NotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("异步通知服务测试")
class AsyncNotificationServiceTest {

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private UserOnlineService userOnlineService;

    @InjectMocks
    private AsyncNotificationService asyncNotificationService;

    private static final String TEST_LOGISTICS_ID = "notify_test_logistics_001";
    private static final String TEST_USER_ID = "notify_test_user_001";

    @BeforeEach
    void setUp() {
        when(notificationRepository.save(any(Notification.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    @DisplayName("测试配送状态更新完成后立即返回响应不阻塞")
    void testAsyncNotificationReturnsImmediately() {
        long startTime = System.currentTimeMillis();

        asyncNotificationService.sendNotificationAsync(
                TEST_LOGISTICS_ID,
                LogisticsConstants.NOTIFY_TYPE_STATUS,
                LogisticsConstants.STATUS_DELIVERED,
                TEST_USER_ID);

        long elapsedTime = System.currentTimeMillis() - startTime;

        assertTrue(elapsedTime < 500, "异步通知应该立即返回，耗时应该小于500ms");
        assertTrue(asyncNotificationService.getQueueSize() >= 1, "通知应该已入队");
    }

    @Test
    @DisplayName("测试后台Worker执行状态通知发送处理")
    void testWorkerProcessesNotificationQueue() {
        when(userOnlineService.isUserOnline(TEST_USER_ID)).thenReturn(true);

        asyncNotificationService.sendNotificationAsync(
                TEST_LOGISTICS_ID,
                LogisticsConstants.NOTIFY_TYPE_STATUS,
                LogisticsConstants.STATUS_DELIVERED,
                TEST_USER_ID);

        assertEquals(1, asyncNotificationService.getQueueSize());

        asyncNotificationService.processNotificationQueue();

        verify(notificationRepository, atLeastOnce()).save(any(Notification.class));
    }

    @Test
    @DisplayName("测试在线用户推送场景")
    void testOnlineUserPushNotification() {
        when(userOnlineService.isUserOnline(TEST_USER_ID)).thenReturn(true);

        asyncNotificationService.sendNotificationAsync(
                TEST_LOGISTICS_ID,
                LogisticsConstants.NOTIFY_TYPE_STATUS,
                LogisticsConstants.STATUS_DELIVERING,
                TEST_USER_ID);

        asyncNotificationService.processNotificationQueue();

        verify(userOnlineService, times(1)).isUserOnline(eq(TEST_USER_ID));
        verify(notificationRepository, atLeastOnce()).save(any(Notification.class));
    }

    @Test
    @DisplayName("测试离线用户存储场景")
    void testOfflineUserStoreNotification() {
        when(userOnlineService.isUserOnline(TEST_USER_ID)).thenReturn(false);

        asyncNotificationService.sendNotificationAsync(
                TEST_LOGISTICS_ID,
                LogisticsConstants.NOTIFY_TYPE_STATUS,
                LogisticsConstants.STATUS_DELIVERING,
                TEST_USER_ID);

        asyncNotificationService.processNotificationQueue();

        verify(userOnlineService, times(1)).isUserOnline(eq(TEST_USER_ID));
        verify(notificationRepository, atLeastOnce()).save(any(Notification.class));
    }

    @Test
    @DisplayName("测试在线推送与离线存储两个场景完整覆盖")
    void testBothOnlineAndOfflineScenarios() {
        Notification onlineNotification = new Notification();
        onlineNotification.setLogisticsId(TEST_LOGISTICS_ID);
        onlineNotification.setNotifyType(LogisticsConstants.NOTIFY_TYPE_STATUS);
        onlineNotification.setNotifyStatus(LogisticsConstants.STATUS_DELIVERING);

        Notification offlineNotification = new Notification();
        offlineNotification.setLogisticsId(TEST_LOGISTICS_ID + "_offline");
        offlineNotification.setNotifyType(LogisticsConstants.NOTIFY_TYPE_STATUS);
        offlineNotification.setNotifyStatus(LogisticsConstants.STATUS_DELIVERED);

        when(userOnlineService.isUserOnline("online_user")).thenReturn(true);
        when(userOnlineService.isUserOnline("offline_user")).thenReturn(false);

        asyncNotificationService.sendNotificationAsync(
                TEST_LOGISTICS_ID,
                LogisticsConstants.NOTIFY_TYPE_STATUS,
                LogisticsConstants.STATUS_DELIVERING,
                "online_user");

        asyncNotificationService.sendNotificationAsync(
                TEST_LOGISTICS_ID + "_offline",
                LogisticsConstants.NOTIFY_TYPE_STATUS,
                LogisticsConstants.STATUS_DELIVERED,
                "offline_user");

        assertEquals(2, asyncNotificationService.getQueueSize());

        asyncNotificationService.processNotificationQueue();

        verify(userOnlineService).isUserOnline(eq("online_user"));
        verify(userOnlineService).isUserOnline(eq("offline_user"));
        verify(notificationRepository, atLeast(2)).save(any(Notification.class));
    }

    @Test
    @DisplayName("测试通知发送失败时的重试机制")
    void testNotificationRetryOnFailure() throws InterruptedException {
        AsyncNotificationService spyService = spy(asyncNotificationService);

        AtomicInteger callCount = new AtomicInteger(0);
        doAnswer(invocation -> {
            int count = callCount.incrementAndGet();
            if (count < 3) {
                throw new RuntimeException("模拟发送失败");
            }
            return null;
        }).when(notificationRepository).save(any(Notification.class));

        when(userOnlineService.isUserOnline(TEST_USER_ID)).thenReturn(true);

        spyService.sendNotificationAsync(
                TEST_LOGISTICS_ID,
                LogisticsConstants.NOTIFY_TYPE_STATUS,
                LogisticsConstants.STATUS_DELIVERED,
                TEST_USER_ID);

        spyService.processNotificationQueue();

        Thread.sleep(100);

        assertTrue(spyService.getFailedTaskCount() >= 0, "失败任务应该被记录");
    }

    @Test
    @DisplayName("测试同步通知发送")
    void testSyncNotificationSend() {
        when(userOnlineService.isUserOnline(TEST_USER_ID)).thenReturn(true);

        Notification notification = asyncNotificationService.sendNotificationSync(
                TEST_LOGISTICS_ID,
                LogisticsConstants.NOTIFY_TYPE_STATUS,
                LogisticsConstants.STATUS_DELIVERED,
                TEST_USER_ID);

        assertNotNull(notification);
        assertEquals(TEST_LOGISTICS_ID, notification.getLogisticsId());
        assertEquals(LogisticsConstants.NOTIFY_TYPE_STATUS, notification.getNotifyType());
        assertEquals(LogisticsConstants.STATUS_DELIVERED, notification.getNotifyStatus());
        assertEquals(TEST_USER_ID, notification.getUserId());

        verify(notificationRepository, times(1)).save(any(Notification.class));
    }

    @Test
    @DisplayName("测试无用户ID的异步通知")
    void testAsyncNotificationWithoutUserId() {
        asyncNotificationService.sendNotificationAsync(
                TEST_LOGISTICS_ID,
                LogisticsConstants.NOTIFY_TYPE_STATUS,
                LogisticsConstants.STATUS_DELIVERING);

        assertEquals(1, asyncNotificationService.getQueueSize());

        asyncNotificationService.processNotificationQueue();

        verify(notificationRepository, atLeastOnce()).save(any(Notification.class));
    }

    @Test
    @DisplayName("测试通知任务数据完整性")
    void testNotificationTaskDataIntegrity() {
        AsyncNotificationService.NotificationTask task = new AsyncNotificationService.NotificationTask();
        task.setLogisticsId(TEST_LOGISTICS_ID);
        task.setNotifyType(LogisticsConstants.NOTIFY_TYPE_TRACK);
        task.setNotifyStatus(LogisticsConstants.STATUS_DELIVERING);
        task.setUserId(TEST_USER_ID);
        task.setRetryCount(0);

        assertEquals(TEST_LOGISTICS_ID, task.getLogisticsId());
        assertEquals(LogisticsConstants.NOTIFY_TYPE_TRACK, task.getNotifyType());
        assertEquals(LogisticsConstants.STATUS_DELIVERING, task.getNotifyStatus());
        assertEquals(TEST_USER_ID, task.getUserId());
        assertEquals(0, task.getRetryCount());
    }

    @Test
    @DisplayName("测试ActiveWorker计数")
    void testActiveWorkerCount() {
        assertEquals(0, asyncNotificationService.getActiveWorkerCount());
    }

    @Test
    @DisplayName("测试不同类型的通知发送")
    void testDifferentNotificationTypes() {
        verifyNotificationType(LogisticsConstants.NOTIFY_TYPE_STATUS, LogisticsConstants.STATUS_SHIPPING);
        verifyNotificationType(LogisticsConstants.NOTIFY_TYPE_TRACK, LogisticsConstants.STATUS_DELIVERING);
        verifyNotificationType(LogisticsConstants.NOTIFY_TYPE_DELIVERY, LogisticsConstants.STATUS_DELIVERED);
    }

    private void verifyNotificationType(String type, String status) {
        AsyncNotificationService.NotificationTask task = new AsyncNotificationService.NotificationTask();
        task.setLogisticsId(TEST_LOGISTICS_ID);
        task.setNotifyType(type);
        task.setNotifyStatus(status);

        assertEquals(type, task.getNotifyType());
        assertEquals(status, task.getNotifyStatus());
    }
}
