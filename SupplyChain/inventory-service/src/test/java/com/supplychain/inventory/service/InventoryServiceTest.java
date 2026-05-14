package com.supplychain.inventory.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.supplychain.common.dto.InventorySyncRequest;
import com.supplychain.common.entity.Inventory;
import com.supplychain.common.entity.InventorySync;
import com.supplychain.common.entity.InventoryWarning;
import com.supplychain.common.enums.WarningLevel;
import com.supplychain.common.enums.WarningType;
import com.supplychain.common.testdata.TestDataBuilder;
import com.supplychain.inventory.mapper.InventoryMapper;
import com.supplychain.inventory.mapper.InventorySyncMapper;
import com.supplychain.inventory.mapper.InventoryWarningMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("库存协同服务单元测试")
class InventoryServiceTest {

    @Mock
    private InventoryMapper inventoryMapper;

    @Mock
    private InventorySyncMapper syncMapper;

    @Mock
    private InventoryWarningMapper warningMapper;

    @InjectMocks
    private InventoryService inventoryService;

    @BeforeEach
    void setUp() {
        inventoryService.clearWarningNotifications();
        inventoryService.clearSyncMetrics();
    }

    @Nested
    @DisplayName("库存实时同步测试")
    class RealTimeSyncTests {

        @Test
        @DisplayName("测试库存实时同步 - 新增库存")
        void testRealTimeSyncNewInventory() {
            InventorySyncRequest request = TestDataBuilder.buildInventorySyncRequest("supplier_qual_001");
            
            when(inventoryMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
            when(inventoryMapper.insert(any(Inventory.class))).thenReturn(1);
            when(syncMapper.insert(any(InventorySync.class))).thenReturn(1);

            InventorySync sync = inventoryService.syncInventoryRealTime(request);

            assertNotNull(sync);
            assertEquals("supplier_qual_001", sync.getSupplierId());
            assertEquals("real_time_inventory", sync.getSyncType());
            verify(inventoryMapper, times(2)).insert(any(Inventory.class));
            assertTrue(inventoryService.isRealTimeSync("supplier_qual_001"));
        }

        @Test
        @DisplayName("测试库存实时同步 - 更新现有库存")
        void testRealTimeSyncUpdateExistingInventory() {
            InventorySyncRequest request = TestDataBuilder.buildInventorySyncRequest("supplier_qual_001");
            Inventory existingInventory = TestDataBuilder.buildNormalInventory();
            
            when(inventoryMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existingInventory);
            when(inventoryMapper.updateById(any(Inventory.class))).thenReturn(1);
            when(syncMapper.insert(any(InventorySync.class))).thenReturn(1);

            InventorySync sync = inventoryService.syncInventoryRealTime(request);

            assertNotNull(sync);
            verify(inventoryMapper, times(2)).updateById(any(Inventory.class));
            verify(inventoryMapper, never()).insert(any(Inventory.class));
        }

        @Test
        @DisplayName("测试库存变动实时触发同步更新")
        void testInventoryChangeTriggersSync() {
            InventorySyncRequest request = TestDataBuilder.buildInventorySyncRequest("supplier_test_001");
            
            when(inventoryMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
            when(inventoryMapper.insert(any(Inventory.class))).thenReturn(1);
            when(syncMapper.insert(any(InventorySync.class))).thenReturn(1);

            assertFalse(inventoryService.isRealTimeSync("supplier_test_001"));
            
            InventorySync sync = inventoryService.syncInventoryRealTime(request);

            assertTrue(inventoryService.isRealTimeSync("supplier_test_001"));
            assertNotNull(sync.getSyncTime());
        }

        @Test
        @DisplayName("测试实时同步而非定时批量同步")
        void testRealTimeNotBatchSync() {
            InventorySyncRequest request = TestDataBuilder.buildInventorySyncRequest("supplier_real_001");
            
            when(inventoryMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
            when(inventoryMapper.insert(any(Inventory.class))).thenReturn(1);
            when(syncMapper.insert(any(InventorySync.class))).thenReturn(1);

            InventorySync firstSync = inventoryService.syncInventoryRealTime(request);
            Map<String, Object> metrics1 = inventoryService.getSyncMetrics("supplier_real_001");
            int freq1 = (int) metrics1.get("syncFrequency");

            InventorySync secondSync = inventoryService.syncInventoryRealTime(request);
            Map<String, Object> metrics2 = inventoryService.getSyncMetrics("supplier_real_001");
            int freq2 = (int) metrics2.get("syncFrequency");

            assertTrue(freq2 > freq1, "实时同步应立即更新频率指标，而非等待批量处理");
            assertEquals("real_time_inventory", firstSync.getSyncType());
            assertEquals("real_time_inventory", secondSync.getSyncType());
        }

        @Test
        @DisplayName("测试同步时间戳更新")
        void testSyncTimestampUpdate() {
            InventorySyncRequest request = TestDataBuilder.buildInventorySyncRequest("supplier_time_001");
            
            when(inventoryMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
            when(inventoryMapper.insert(any(Inventory.class))).thenReturn(1);
            when(syncMapper.insert(any(InventorySync.class))).thenReturn(1);

            inventoryService.syncInventoryRealTime(request);
            Map<String, Object> metrics = inventoryService.getSyncMetrics("supplier_time_001");
            
            assertNotNull(metrics.get("lastSyncTime"));
            assertTrue((Boolean) metrics.get("isRealTime"));
        }

        @Test
        @DisplayName("测试同步频率统计")
        void testSyncFrequencyTracking() {
            InventorySyncRequest request = TestDataBuilder.buildInventorySyncRequest("supplier_freq_001");
            
            when(inventoryMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
            when(inventoryMapper.insert(any(Inventory.class))).thenReturn(1);
            when(syncMapper.insert(any(InventorySync.class))).thenReturn(1);

            for (int i = 0; i < 3; i++) {
                inventoryService.syncInventoryRealTime(request);
            }

            Map<String, Object> metrics = inventoryService.getSyncMetrics("supplier_freq_001");
            int frequency = (int) metrics.get("syncFrequency");
            
            assertEquals(6, frequency);
        }
    }

    @Nested
    @DisplayName("库存预警阈值测试")
    class WarningThresholdTests {

        @Test
        @DisplayName("测试库存预警阈值检测 - 库存低于阈值")
        void testLowStockWarningThreshold() {
            boolean isWarning = inventoryService.checkWarningThreshold(30, 100);
            assertTrue(isWarning, "库存30低于阈值100应触发预警");
        }

        @Test
        @DisplayName("测试库存预警阈值检测 - 库存等于阈值")
        void testStockEqualToThreshold() {
            boolean isWarning = inventoryService.checkWarningThreshold(100, 100);
            assertFalse(isWarning, "库存等于阈值不应触发预警");
        }

        @Test
        @DisplayName("测试库存预警阈值检测 - 库存高于阈值")
        void testStockAboveThreshold() {
            boolean isWarning = inventoryService.checkWarningThreshold(150, 100);
            assertFalse(isWarning, "库存高于阈值不应触发预警");
        }

        @Test
        @DisplayName("测试预警级别判定 - 严重级别")
        void testCriticalWarningLevel() {
            String level = inventoryService.getWarningLevel(20, 100);
            assertEquals(WarningLevel.CRITICAL.getCode(), level, "20%库存应判定为严重级别");
        }

        @Test
        @DisplayName("测试预警级别判定 - 高级别")
        void testHighWarningLevel() {
            String level = inventoryService.getWarningLevel(40, 100);
            assertEquals(WarningLevel.HIGH.getCode(), level, "40%库存应判定为高级别");
        }

        @Test
        @DisplayName("测试预警级别判定 - 中级别")
        void testMediumWarningLevel() {
            String level = inventoryService.getWarningLevel(70, 100);
            assertEquals(WarningLevel.MEDIUM.getCode(), level, "70%库存应判定为中级别");
        }

        @Test
        @DisplayName("测试预警级别判定 - 低级别")
        void testLowWarningLevel() {
            String level = inventoryService.getWarningLevel(85, 100);
            assertEquals(WarningLevel.LOW.getCode(), level, "85%库存应判定为低级别");
        }

        @Test
        @DisplayName("测试低库存同步时创建预警")
        void testLowStockSyncCreatesWarning() {
            InventorySyncRequest request = TestDataBuilder.buildLowStockSyncRequest("supplier_warn_001");
            
            when(inventoryMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
            when(inventoryMapper.insert(any(Inventory.class))).thenReturn(1);
            when(syncMapper.insert(any(InventorySync.class))).thenReturn(1);
            when(warningMapper.insert(any(InventoryWarning.class))).thenReturn(1);

            inventoryService.syncInventoryRealTime(request);

            List<String> notifications = inventoryService.getWarningNotifications();
            assertEquals(2, notifications.size());
            verify(warningMapper, times(2)).insert(any(InventoryWarning.class));
        }

        @Test
        @DisplayName("测试正常库存同步时不创建预警")
        void testNormalStockSyncNoWarning() {
            InventorySyncRequest request = TestDataBuilder.buildInventorySyncRequest("supplier_norm_001");
            
            when(inventoryMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
            when(inventoryMapper.insert(any(Inventory.class))).thenReturn(1);
            when(syncMapper.insert(any(InventorySync.class))).thenReturn(1);

            inventoryService.syncInventoryRealTime(request);

            List<String> notifications = inventoryService.getWarningNotifications();
            assertEquals(0, notifications.size());
            verify(warningMapper, never()).insert(any(InventoryWarning.class));
        }

        @Test
        @DisplayName("测试预警通知发送")
        void testWarningNotificationSent() {
            InventorySyncRequest request = TestDataBuilder.buildLowStockSyncRequest("supplier_notif_001");
            
            when(inventoryMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
            when(inventoryMapper.insert(any(Inventory.class))).thenReturn(1);
            when(syncMapper.insert(any(InventorySync.class))).thenReturn(1);
            when(warningMapper.insert(any(InventoryWarning.class))).thenReturn(1);

            inventoryService.syncInventoryRealTime(request);

            List<String> notifications = inventoryService.getWarningNotifications();
            assertTrue(notifications.stream().allMatch(n -> n.contains("[库存预警]")));
            assertTrue(notifications.stream().allMatch(n -> n.contains("supplier_notif_001")));
        }

        @Test
        @DisplayName("测试预警通知包含正确信息")
        void testWarningNotificationContent() {
            InventorySyncRequest request = TestDataBuilder.buildLowStockSyncRequest("supplier_info_001");
            
            when(inventoryMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
            when(inventoryMapper.insert(any(Inventory.class))).thenReturn(1);
            when(syncMapper.insert(any(InventorySync.class))).thenReturn(1);
            when(warningMapper.insert(any(InventoryWarning.class))).thenReturn(1);

            inventoryService.syncInventoryRealTime(request);

            List<String> notifications = inventoryService.getWarningNotifications();
            String notification = notifications.get(0);
            
            assertTrue(notification.contains("当前库存=25") || notification.contains("当前库存=10"));
            assertTrue(notification.contains("阈值=100"));
            assertTrue(notification.contains("类型=" + WarningType.LOW_STOCK.getCode()));
        }
    }

    @Nested
    @DisplayName("同步策略差异测试")
    class SyncStrategyTests {

        @Test
        @DisplayName("测试低频同步策略")
        void testLowFrequencyStrategy() {
            String strategy = inventoryService.determineSyncStrategy(5);
            assertEquals("low_frequency_strategy", strategy);
        }

        @Test
        @DisplayName("测试正常频率同步策略")
        void testNormalFrequencyStrategy() {
            String strategy = inventoryService.determineSyncStrategy(25);
            assertEquals("normal_frequency_strategy", strategy);
        }

        @Test
        @DisplayName("测试中高频同步策略")
        void testMediumHighFrequencyStrategy() {
            String strategy = inventoryService.determineSyncStrategy(75);
            assertEquals("medium_frequency_strategy", strategy);
        }

        @Test
        @DisplayName("测试高频同步策略")
        void testHighFrequencyStrategy() {
            String strategy = inventoryService.determineSyncStrategy(150);
            assertEquals("high_frequency_strategy", strategy);
        }

        @Test
        @DisplayName("测试不同变动频率下的同步策略")
        void testDifferentFrequencyStrategies() {
            InventorySyncRequest request = TestDataBuilder.buildInventorySyncRequest("supplier_strat_001");
            
            when(inventoryMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
            when(inventoryMapper.insert(any(Inventory.class))).thenReturn(1);
            when(syncMapper.insert(any(InventorySync.class))).thenReturn(1);

            for (int i = 0; i < 10; i++) {
                inventoryService.syncInventoryRealTime(request);
            }

            Map<String, Object> metrics = inventoryService.getSyncMetrics("supplier_strat_001");
            String strategy = (String) metrics.get("syncStrategy");
            
            assertEquals("normal_frequency_strategy", strategy);
            assertEquals(20, metrics.get("syncFrequency"));
        }

        @Test
        @DisplayName("测试同步策略边界值 - 低频边界")
        void testStrategyBoundaryLow() {
            assertEquals("low_frequency_strategy", inventoryService.determineSyncStrategy(9));
            assertEquals("normal_frequency_strategy", inventoryService.determineSyncStrategy(10));
        }

        @Test
        @DisplayName("测试同步策略边界值 - 中高频边界")
        void testStrategyBoundaryMediumHigh() {
            assertEquals("normal_frequency_strategy", inventoryService.determineSyncStrategy(49));
            assertEquals("medium_frequency_strategy", inventoryService.determineSyncStrategy(50));
        }

        @Test
        @DisplayName("测试同步策略边界值 - 高频边界")
        void testStrategyBoundaryHigh() {
            assertEquals("medium_frequency_strategy", inventoryService.determineSyncStrategy(99));
            assertEquals("high_frequency_strategy", inventoryService.determineSyncStrategy(100));
        }
    }

    @Nested
    @DisplayName("库存查询与管理测试")
    class InventoryManagementTests {

        @Test
        @DisplayName("测试获取库存列表")
        void testListInventories() {
            List<Inventory> inventories = Arrays.asList(
                TestDataBuilder.buildNormalInventory(),
                TestDataBuilder.buildLowStockInventory()
            );
            
            when(inventoryMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(inventories);

            List<Inventory> result = inventoryService.listInventories("supplier_qual_001", null);

            assertEquals(2, result.size());
            verify(inventoryMapper).selectList(any(LambdaQueryWrapper.class));
        }

        @Test
        @DisplayName("测试获取库存列表 - 按商品过滤")
        void testListInventoriesByItem() {
            List<Inventory> inventories = Arrays.asList(
                TestDataBuilder.buildNormalInventory()
            );
            
            when(inventoryMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(inventories);

            List<Inventory> result = inventoryService.listInventories(null, "item_001");

            assertEquals(1, result.size());
        }

        @Test
        @DisplayName("测试获取预警列表")
        void testListWarnings() {
            List<InventoryWarning> warnings = Arrays.asList(
                TestDataBuilder.buildLowStockWarning(),
                TestDataBuilder.buildCriticalWarning()
            );
            
            when(warningMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(warnings);

            List<InventoryWarning> result = inventoryService.listWarnings("active", null);

            assertEquals(2, result.size());
        }

        @Test
        @DisplayName("测试获取预警列表 - 按类型过滤")
        void testListWarningsByType() {
            List<InventoryWarning> warnings = Arrays.asList(
                TestDataBuilder.buildLowStockWarning()
            );
            
            when(warningMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(warnings);

            List<InventoryWarning> result = inventoryService.listWarnings(
                null, WarningType.LOW_STOCK.getCode());

            assertEquals(1, result.size());
        }

        @Test
        @DisplayName("测试处理预警")
        void testHandleWarning() {
            InventoryWarning warning = TestDataBuilder.buildLowStockWarning();
            
            when(warningMapper.selectById("warn_low_001")).thenReturn(warning);
            when(warningMapper.updateById(any(InventoryWarning.class))).thenReturn(1);

            InventoryWarning result = inventoryService.handleWarning("warn_low_001", "管理员");

            assertEquals("handled", result.getStatus());
            assertEquals("管理员", result.getHandler());
            assertNotNull(result.getHandledAt());
        }

        @Test
        @DisplayName("测试获取同步记录")
        void testListSyncRecords() {
            List<InventorySync> syncs = Arrays.asList(
                TestDataBuilder.buildInventorySyncRecord()
            );
            
            when(syncMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(syncs);

            List<InventorySync> result = inventoryService.listSyncRecords("supplier_qual_001");

            assertEquals(1, result.size());
        }

        @Test
        @DisplayName("测试获取单个库存")
        void testGetInventory() {
            Inventory inventory = TestDataBuilder.buildNormalInventory();
            
            when(inventoryMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(inventory);

            Inventory result = inventoryService.getInventory("supplier_qual_001", "item_001");

            assertNotNull(result);
            assertEquals("item_001", result.getItemId());
        }
    }

    @Nested
    @DisplayName("同步指标测试")
    class SyncMetricsTests {

        @Test
        @DisplayName("测试清除同步指标")
        void testClearSyncMetrics() {
            InventorySyncRequest request = TestDataBuilder.buildInventorySyncRequest("supplier_metric_001");
            
            when(inventoryMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
            when(inventoryMapper.insert(any(Inventory.class))).thenReturn(1);
            when(syncMapper.insert(any(InventorySync.class))).thenReturn(1);

            inventoryService.syncInventoryRealTime(request);
            assertTrue(inventoryService.isRealTimeSync("supplier_metric_001"));

            inventoryService.clearSyncMetrics();
            assertFalse(inventoryService.isRealTimeSync("supplier_metric_001"));
        }

        @Test
        @DisplayName("测试获取同步频率Map")
        void testGetSyncFrequencyMap() {
            InventorySyncRequest request = TestDataBuilder.buildInventorySyncRequest("supplier_map_001");
            
            when(inventoryMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
            when(inventoryMapper.insert(any(Inventory.class))).thenReturn(1);
            when(syncMapper.insert(any(InventorySync.class))).thenReturn(1);

            inventoryService.syncInventoryRealTime(request);
            Map<String, Integer> frequencyMap = inventoryService.getSyncFrequencyMap();

            assertFalse(frequencyMap.isEmpty());
        }

        @Test
        @DisplayName("测试获取同步指标")
        void testGetSyncMetrics() {
            InventorySyncRequest request = TestDataBuilder.buildInventorySyncRequest("supplier_metrics_001");
            
            when(inventoryMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
            when(inventoryMapper.insert(any(Inventory.class))).thenReturn(1);
            when(syncMapper.insert(any(InventorySync.class))).thenReturn(1);

            inventoryService.syncInventoryRealTime(request);
            Map<String, Object> metrics = inventoryService.getSyncMetrics("supplier_metrics_001");

            assertTrue(metrics.containsKey("supplierId"));
            assertTrue(metrics.containsKey("lastSyncTime"));
            assertTrue(metrics.containsKey("isRealTime"));
            assertTrue(metrics.containsKey("syncFrequency"));
            assertTrue(metrics.containsKey("syncStrategy"));
        }
    }
}
