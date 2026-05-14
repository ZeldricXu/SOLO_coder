package com.fooddelivery.service;

import com.fooddelivery.builder.TestDataBuilder;
import com.fooddelivery.entity.Notify;
import com.fooddelivery.repository.NotifyRepository;
import com.fooddelivery.repository.TrackRepository;
import com.fooddelivery.util.NotificationPushService;
import com.fooddelivery.util.NotificationPushService.PushMessage;
import com.fooddelivery.util.NotificationPushService.PushStrategy;
import com.fooddelivery.util.NotificationPushService.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("状态模块测试 - 状态实时推送")
class StatusServiceTest {

    @Mock
    private NotifyRepository notifyRepository;

    @Mock
    private TrackRepository trackRepository;

    @Mock
    private HistoryService historyService;

    @InjectMocks
    private StatusService statusService;

    private NotificationPushService pushService;

    private static final Set<String> IMPORTANT_STATUSES = new HashSet<>(
            Arrays.asList("pending_pickup", "picked_up", "delivered", "cancelled"));

    @BeforeEach
    void setUp() {
        pushService = new NotificationPushService();
        pushService.clearAll();
    }

    @Test
    @DisplayName("状态更新实时触发推送 - 重要状态立即推送")
    void testImportantStatusTriggersRealTimePush() {
        String userId = "user_001";
        pushService.setUserOnline(userId);

        String importantStatus = "delivered";
        assertTrue(IMPORTANT_STATUSES.contains(importantStatus));

        PushMessage message = new PushMessage(
                "order_001",
                importantStatus,
                "订单已送达",
                PushStrategy.REAL_TIME,
                true
        );

        boolean pushed = pushService.pushNotification(userId, message);

        assertTrue(pushed, "在线用户重要状态应该推送成功");
        assertEquals(1, pushService.getRealTimePushedCount());
        assertEquals(0, pushService.getBatchQueueCount());

        List<PushMessage> pushedMessages = pushService.getRealTimePushedMessages();
        assertEquals(1, pushedMessages.size());
        assertEquals(importantStatus, pushedMessages.get(0).getStatus());
        assertTrue(pushedMessages.get(0).isImportant());
    }

    @Test
    @DisplayName("状态更新实时触发推送 - 普通状态批量推送")
    void testNormalStatusTriggersBatchPush() {
        String userId = "user_001";
        pushService.setUserOnline(userId);

        String normalStatus = "delivering";
        assertFalse(IMPORTANT_STATUSES.contains(normalStatus));

        PushMessage message = new PushMessage(
                "order_001",
                normalStatus,
                "配送中",
                PushStrategy.BATCH,
                false
        );

        boolean pushed = pushService.pushNotification(userId, message);

        assertTrue(pushed, "在线用户普通状态应该进入批量队列");
        assertEquals(0, pushService.getRealTimePushedCount());
        assertEquals(1, pushService.getBatchQueueCount());

        List<PushMessage> batchMessages = pushService.getBatchQueue();
        assertEquals(1, batchMessages.size());
        assertEquals(normalStatus, batchMessages.get(0).getStatus());
        assertFalse(batchMessages.get(0).isImportant());
    }

    @Test
    @DisplayName("不同状态类型推送策略差异 - 重要状态列表")
    void testImportantStatusList() {
        Set<String> expectedImportant = new HashSet<>(
                Arrays.asList("pending_pickup", "picked_up", "delivered", "cancelled"));
        assertEquals(expectedImportant, IMPORTANT_STATUSES);
    }

    @Test
    @DisplayName("推送发送机制 - 批量阈值触发推送")
    void testBatchThresholdTrigger() {
        String userId = "user_001";
        pushService.setUserOnline(userId);

        int batchThreshold = 10;

        for (int i = 0; i < batchThreshold - 1; i++) {
            PushMessage msg = new PushMessage(
                    "order_" + i,
                    "delivering",
                    "配送中 " + i,
                    PushStrategy.BATCH,
                    false
            );
            pushService.pushNotification(userId, msg);
        }

        assertEquals(batchThreshold - 1, pushService.getBatchQueueCount());
        assertEquals(0, pushService.getRealTimePushedCount());

        PushMessage thresholdMsg = new PushMessage(
                "order_999",
                "delivering",
                "触发批量推送",
                PushStrategy.BATCH,
                false
        );
        pushService.pushNotification(userId, thresholdMsg);

        assertEquals(0, pushService.getBatchQueueCount());
        assertEquals(batchThreshold, pushService.getRealTimePushedCount());
    }

    @Test
    @DisplayName("推送发送机制 - 手动刷新批量队列")
    void testManualBatchFlush() {
        String userId = "user_001";
        pushService.setUserOnline(userId);

        for (int i = 0; i < 5; i++) {
            PushMessage msg = new PushMessage(
                    "order_" + i,
                    "delivering",
                    "配送中",
                    PushStrategy.BATCH,
                    false
            );
            pushService.pushNotification(userId, msg);
        }

        assertEquals(5, pushService.getBatchQueueCount());
        assertEquals(0, pushService.getRealTimePushedCount());

        int flushed = pushService.flushBatch();

        assertEquals(5, flushed);
        assertEquals(0, pushService.getBatchQueueCount());
        assertEquals(5, pushService.getRealTimePushedCount());
    }

    @Test
    @DisplayName("在线推送场景 - 用户在线时重要状态立即推送")
    void testOnlineUserImportantStatusPush() {
        String userId = "user_online";
        pushService.setUserOnline(userId);
        assertEquals(UserStatus.ONLINE, pushService.getUserStatus(userId));

        PushMessage msg = new PushMessage(
                "order_001",
                "delivered",
                "订单已送达",
                PushStrategy.REAL_TIME,
                true
        );

        boolean pushed = pushService.pushNotification(userId, msg);

        assertTrue(pushed);
        assertEquals(1, pushService.getRealTimePushedCount());
    }

    @Test
    @DisplayName("在线推送场景 - 用户在线时普通状态进入批量")
    void testOnlineUserNormalStatusBatch() {
        String userId = "user_online";
        pushService.setUserOnline(userId);

        PushMessage msg = new PushMessage(
                "order_001",
                "delivering",
                "配送中",
                PushStrategy.BATCH,
                false
        );

        boolean pushed = pushService.pushNotification(userId, msg);

        assertTrue(pushed);
        assertEquals(1, pushService.getBatchQueueCount());
    }

    @Test
    @DisplayName("离线存储场景 - 用户离线时重要状态存储")
    void testOfflineUserImportantStatusStorage() {
        String userId = "user_offline";
        pushService.setUserOffline(userId);
        assertEquals(UserStatus.OFFLINE, pushService.getUserStatus(userId));

        PushMessage msg = new PushMessage(
                "order_001",
                "delivered",
                "订单已送达",
                PushStrategy.REAL_TIME,
                true
        );

        boolean pushed = pushService.pushNotification(userId, msg);

        assertFalse(pushed, "离线用户应该推送失败");
        assertEquals(0, pushService.getRealTimePushedCount());

        List<PushMessage> offlineMessages = pushService.getOfflineMessages(userId);
        assertEquals(1, offlineMessages.size());
        assertEquals("order_001", offlineMessages.get(0).getOrderId());
    }

    @Test
    @DisplayName("离线存储场景 - 用户离线时普通状态存储")
    void testOfflineUserNormalStatusStorage() {
        String userId = "user_offline";
        pushService.setUserOffline(userId);

        PushMessage msg = new PushMessage(
                "order_001",
                "delivering",
                "配送中",
                PushStrategy.BATCH,
                false
        );

        boolean pushed = pushService.pushNotification(userId, msg);

        assertFalse(pushed);
        assertEquals(0, pushService.getRealTimePushedCount());
        assertEquals(0, pushService.getBatchQueueCount());

        List<PushMessage> offlineMessages = pushService.getOfflineMessages(userId);
        assertEquals(1, offlineMessages.size());
    }

    @Test
    @DisplayName("推送策略正确应用 - 重要状态REAL_TIME")
    void testImportantStatusUsesRealTimeStrategy() {
        for (String status : IMPORTANT_STATUSES) {
            boolean isImportant = IMPORTANT_STATUSES.contains(status);
            assertTrue(isImportant, status + " 应该是重要状态");

            PushStrategy strategy = isImportant ? PushStrategy.REAL_TIME : PushStrategy.BATCH;
            assertEquals(PushStrategy.REAL_TIME, strategy);
        }
    }

    @Test
    @DisplayName("推送策略正确应用 - 普通状态BATCH")
    void testNormalStatusUsesBatchStrategy() {
        List<String> normalStatuses = Arrays.asList("delivering", "cooking", "preparing");
        for (String status : normalStatuses) {
            boolean isImportant = IMPORTANT_STATUSES.contains(status);
            assertFalse(isImportant, status + " 应该是普通状态");

            PushStrategy strategy = isImportant ? PushStrategy.REAL_TIME : PushStrategy.BATCH;
            assertEquals(PushStrategy.BATCH, strategy);
        }
    }

    @Test
    @DisplayName("多种状态类型推送验证")
    void testMultipleStatusTypesPush() {
        String userId = "user_001";
        pushService.setUserOnline(userId);

        String[][] testCases = {
                {"pending_pickup", "骑手已接单", "true"},
                {"picked_up", "骑手已取餐", "true"},
                {"delivering", "配送中", "false"},
                {"delivered", "订单已送达", "true"},
                {"cancelled", "订单已取消", "true"}
        };

        for (String[] testCase : testCases) {
            String status = testCase[0];
            String message = testCase[1];
            boolean isImportant = Boolean.parseBoolean(testCase[2]);

            PushMessage msg = new PushMessage(
                    "order_" + status,
                    status,
                    message,
                    isImportant ? PushStrategy.REAL_TIME : PushStrategy.BATCH,
                    isImportant
            );

            int beforeRealTime = pushService.getRealTimePushedCount();
            int beforeBatch = pushService.getBatchQueueCount();

            pushService.pushNotification(userId, msg);

            if (isImportant) {
                assertEquals(beforeRealTime + 1, pushService.getRealTimePushedCount(),
                        status + " 应该实时推送");
            } else {
                assertEquals(beforeBatch + 1, pushService.getBatchQueueCount(),
                        status + " 应该进入批量队列");
            }
        }
    }
}
