package com.stockmgmt.service;

import com.stockmgmt.builder.TestDataBuilder;
import com.stockmgmt.entity.Stock;
import com.stockmgmt.entity.StockWarning;
import com.stockmgmt.enums.WarningLevel;
import com.stockmgmt.enums.WarningStatus;
import com.stockmgmt.enums.WarningType;
import com.stockmgmt.repository.StockWarningRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("预警聚合机制测试")
class WarningServiceTest {

    @Mock
    private StockWarningRepository warningRepository;

    @InjectMocks
    private WarningService warningService;

    @BeforeEach
    void setUp() {
        TestDataBuilder.reset();
    }

    @Test
    @DisplayName("库存不足预警触发 - 低于阈值时创建预警")
    void testTriggerLowStockWarning_Success() {
        Stock stock = TestDataBuilder.buildLowStock();

        when(warningRepository.findFirstByStockIdAndWarningTypeAndStatusOrderByTriggeredAtDesc(
                eq(stock.getStockId()), eq(WarningType.LOW_STOCK), eq(WarningStatus.ACTIVE)))
                .thenReturn(Optional.empty());
        when(warningRepository.save(any(StockWarning.class))).thenAnswer(invocation -> invocation.getArgument(0));

        warningService.triggerLowStockWarning(stock);

        ArgumentCaptor<StockWarning> captor = ArgumentCaptor.forClass(StockWarning.class);
        verify(warningRepository).save(captor.capture());

        StockWarning savedWarning = captor.getValue();
        assertEquals(stock.getStockId(), savedWarning.getStockId());
        assertEquals(stock.getProductId(), savedWarning.getProductId());
        assertEquals(WarningType.LOW_STOCK, savedWarning.getWarningType());
        assertEquals(WarningLevel.HIGH, savedWarning.getWarningLevel());
        assertEquals(stock.getCurrentQuantity(), savedWarning.getCurrentQuantity());
        assertEquals(stock.getWarningThreshold(), savedWarning.getThreshold());
        assertEquals(WarningStatus.ACTIVE, savedWarning.getStatus());
    }

    @Test
    @DisplayName("库存积压预警触发 - 高于积压阈值时创建预警")
    void testTriggerOverstockWarning_Success() {
        Stock stock = TestDataBuilder.buildOverstock();

        when(warningRepository.findFirstByStockIdAndWarningTypeAndStatusOrderByTriggeredAtDesc(
                eq(stock.getStockId()), eq(WarningType.OVERSTOCK), eq(WarningStatus.ACTIVE)))
                .thenReturn(Optional.empty());
        when(warningRepository.save(any(StockWarning.class))).thenAnswer(invocation -> invocation.getArgument(0));

        warningService.triggerOverstockWarning(stock);

        ArgumentCaptor<StockWarning> captor = ArgumentCaptor.forClass(StockWarning.class);
        verify(warningRepository).save(captor.capture());

        StockWarning savedWarning = captor.getValue();
        assertEquals(WarningType.OVERSTOCK, savedWarning.getWarningType());
        assertEquals(WarningLevel.HIGH, savedWarning.getWarningLevel());
    }

    @Test
    @DisplayName("同类预警聚合 - 已有活动预警时不重复创建")
    void testWarningAggregation_NoDuplicateWarning() {
        Stock stock = TestDataBuilder.buildLowStock();
        StockWarning existingWarning = TestDataBuilder.buildActiveLowStockWarning();

        when(warningRepository.findFirstByStockIdAndWarningTypeAndStatusOrderByTriggeredAtDesc(
                eq(stock.getStockId()), eq(WarningType.LOW_STOCK), eq(WarningStatus.ACTIVE)))
                .thenReturn(Optional.of(existingWarning));

        warningService.triggerLowStockWarning(stock);

        verify(warningRepository, never()).save(any(StockWarning.class));
    }

    @Test
    @DisplayName("库存不足预警级别判定 - 根据库存比例确定级别")
    void testLowStockWarningLevel_Determination() {
        Stock stock1 = TestDataBuilder.buildStockWithThresholds(2, 10, 500);
        Stock stock2 = TestDataBuilder.buildStockWithThresholds(5, 10, 500);
        Stock stock3 = TestDataBuilder.buildStockWithThresholds(7, 10, 500);

        when(warningRepository.findFirstByStockIdAndWarningTypeAndStatusOrderByTriggeredAtDesc(
                anyString(), eq(WarningType.LOW_STOCK), eq(WarningStatus.ACTIVE)))
                .thenReturn(Optional.empty());
        when(warningRepository.save(any(StockWarning.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ArgumentCaptor<StockWarning> captor = ArgumentCaptor.forClass(StockWarning.class);

        warningService.triggerLowStockWarning(stock1);
        verify(warningRepository).save(captor.capture());
        assertEquals(WarningLevel.HIGH, captor.getValue().getWarningLevel());

        warningService.triggerLowStockWarning(stock2);
        verify(warningRepository, times(2)).save(captor.capture());
        assertEquals(WarningLevel.HIGH, captor.getValue().getWarningLevel());

        warningService.triggerLowStockWarning(stock3);
        verify(warningRepository, times(3)).save(captor.capture());
        assertEquals(WarningLevel.MEDIUM, captor.getValue().getWarningLevel());
    }

    @Test
    @DisplayName("库存积压预警级别判定 - 根据库存比例确定级别")
    void testOverstockWarningLevel_Determination() {
        Stock stock1 = TestDataBuilder.buildStockWithThresholds(1200, 10, 500);
        Stock stock2 = TestDataBuilder.buildStockWithThresholds(800, 10, 500);
        Stock stock3 = TestDataBuilder.buildStockWithThresholds(600, 10, 500);

        when(warningRepository.findFirstByStockIdAndWarningTypeAndStatusOrderByTriggeredAtDesc(
                anyString(), eq(WarningType.OVERSTOCK), eq(WarningStatus.ACTIVE)))
                .thenReturn(Optional.empty());
        when(warningRepository.save(any(StockWarning.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ArgumentCaptor<StockWarning> captor = ArgumentCaptor.forClass(StockWarning.class);

        warningService.triggerOverstockWarning(stock1);
        verify(warningRepository).save(captor.capture());
        assertEquals(WarningLevel.HIGH, captor.getValue().getWarningLevel());

        warningService.triggerOverstockWarning(stock2);
        verify(warningRepository, times(2)).save(captor.capture());
        assertEquals(WarningLevel.MEDIUM, captor.getValue().getWarningLevel());

        warningService.triggerOverstockWarning(stock3);
        verify(warningRepository, times(3)).save(captor.capture());
        assertEquals(WarningLevel.LOW, captor.getValue().getWarningLevel());
    }

    @Test
    @DisplayName("预警自动解除 - 库存恢复正常时解除预警")
    void testResolveLowStockWarning_Success() {
        Stock stock = TestDataBuilder.buildNormalStock();
        StockWarning activeWarning = TestDataBuilder.buildActiveLowStockWarning();
        activeWarning.setStockId(stock.getStockId());

        when(warningRepository.findByStockIdAndStatus(eq(stock.getStockId()), eq(WarningStatus.ACTIVE)))
                .thenReturn(Arrays.asList(activeWarning));
        when(warningRepository.save(any(StockWarning.class))).thenAnswer(invocation -> invocation.getArgument(0));

        warningService.resolveLowStockWarning(stock);

        ArgumentCaptor<StockWarning> captor = ArgumentCaptor.forClass(StockWarning.class);
        verify(warningRepository).save(captor.capture());

        StockWarning resolvedWarning = captor.getValue();
        assertEquals(WarningStatus.HANDLED, resolvedWarning.getStatus());
        assertNotNull(resolvedWarning.getHandledAt());
        assertEquals("system", resolvedWarning.getHandledBy());
        assertTrue(resolvedWarning.getRemark().contains("自动解除"));
    }

    @Test
    @DisplayName("库存积压预警自动解除 - 库存恢复正常时解除预警")
    void testResolveOverstockWarning_Success() {
        Stock stock = TestDataBuilder.buildNormalStock();
        StockWarning activeWarning = TestDataBuilder.buildActiveOverstockWarning();
        activeWarning.setStockId(stock.getStockId());

        when(warningRepository.findByStockIdAndStatus(eq(stock.getStockId()), eq(WarningStatus.ACTIVE)))
                .thenReturn(Arrays.asList(activeWarning));
        when(warningRepository.save(any(StockWarning.class))).thenAnswer(invocation -> invocation.getArgument(0));

        warningService.resolveOverstockWarning(stock);

        ArgumentCaptor<StockWarning> captor = ArgumentCaptor.forClass(StockWarning.class);
        verify(warningRepository).save(captor.capture());

        StockWarning resolvedWarning = captor.getValue();
        assertEquals(WarningStatus.HANDLED, resolvedWarning.getStatus());
        assertTrue(resolvedWarning.getRemark().contains("自动解除"));
    }

    @Test
    @DisplayName("预警检查 - 综合检查库存状态并触发或解除预警")
    void testCheckAndTriggerWarning_NormalStock() {
        Stock stock = TestDataBuilder.buildNormalStock();

        when(warningRepository.findByStockIdAndStatus(eq(stock.getStockId()), eq(WarningStatus.ACTIVE)))
                .thenReturn(new ArrayList<>());

        warningService.checkAndTriggerWarning(stock);

        verify(warningRepository, never()).save(any(StockWarning.class));
    }

    @Test
    @DisplayName("预警检查 - 库存不足时触发预警")
    void testCheckAndTriggerWarning_LowStock() {
        Stock stock = TestDataBuilder.buildLowStock();

        when(warningRepository.findFirstByStockIdAndWarningTypeAndStatusOrderByTriggeredAtDesc(
                eq(stock.getStockId()), eq(WarningType.LOW_STOCK), eq(WarningStatus.ACTIVE)))
                .thenReturn(Optional.empty());
        when(warningRepository.findByStockIdAndStatus(eq(stock.getStockId()), eq(WarningStatus.ACTIVE)))
                .thenReturn(new ArrayList<>());
        when(warningRepository.save(any(StockWarning.class))).thenAnswer(invocation -> invocation.getArgument(0));

        warningService.checkAndTriggerWarning(stock);

        ArgumentCaptor<StockWarning> captor = ArgumentCaptor.forClass(StockWarning.class);
        verify(warningRepository).save(captor.capture());
        assertEquals(WarningType.LOW_STOCK, captor.getValue().getWarningType());
    }

    @Test
    @DisplayName("预警检查 - 库存积压时触发预警")
    void testCheckAndTriggerWarning_Overstock() {
        Stock stock = TestDataBuilder.buildOverstock();

        when(warningRepository.findFirstByStockIdAndWarningTypeAndStatusOrderByTriggeredAtDesc(
                eq(stock.getStockId()), eq(WarningType.OVERSTOCK), eq(WarningStatus.ACTIVE)))
                .thenReturn(Optional.empty());
        when(warningRepository.findByStockIdAndStatus(eq(stock.getStockId()), eq(WarningStatus.ACTIVE)))
                .thenReturn(new ArrayList<>());
        when(warningRepository.save(any(StockWarning.class))).thenAnswer(invocation -> invocation.getArgument(0));

        warningService.checkAndTriggerWarning(stock);

        ArgumentCaptor<StockWarning> captor = ArgumentCaptor.forClass(StockWarning.class);
        verify(warningRepository).save(captor.capture());
        assertEquals(WarningType.OVERSTOCK, captor.getValue().getWarningType());
    }

    @Test
    @DisplayName("获取活动预警 - 正确返回活动预警列表")
    void testGetActiveWarnings_Success() {
        StockWarning warning1 = TestDataBuilder.buildActiveLowStockWarning();
        StockWarning warning2 = TestDataBuilder.buildActiveOverstockWarning();

        when(warningRepository.findByStatus(WarningStatus.ACTIVE))
                .thenReturn(Arrays.asList(warning1, warning2));

        List<StockWarning> warnings = warningService.getActiveWarnings();

        assertEquals(2, warnings.size());
        assertTrue(warnings.stream().anyMatch(w -> w.getWarningType() == WarningType.LOW_STOCK));
        assertTrue(warnings.stream().anyMatch(w -> w.getWarningType() == WarningType.OVERSTOCK));
    }

    @Test
    @DisplayName("按类型获取活动预警 - 正确过滤预警类型")
    void testGetActiveWarningsByType_Success() {
        StockWarning warning1 = TestDataBuilder.buildActiveLowStockWarning();

        when(warningRepository.findByWarningTypeAndStatus(WarningType.LOW_STOCK, WarningStatus.ACTIVE))
                .thenReturn(Arrays.asList(warning1));

        List<StockWarning> warnings = warningService.getActiveWarningsByType(WarningType.LOW_STOCK);

        assertEquals(1, warnings.size());
        assertEquals(WarningType.LOW_STOCK, warnings.get(0).getWarningType());
    }

    @Test
    @DisplayName("活动预警统计 - 正确统计活动预警数量")
    void testGetActiveWarningCount_Success() {
        when(warningRepository.countByStatus(WarningStatus.ACTIVE)).thenReturn(5L);

        long count = warningService.getActiveWarningCount();

        assertEquals(5L, count);
    }

    @Test
    @DisplayName("预警内容完整性 - 确保预警通知包含所有必要信息")
    void testWarningContent_Completeness() {
        Stock stock = TestDataBuilder.buildLowStock();
        stock.setProductName("iPhone 15 Pro Max");
        stock.setStockId("STOCK_001");
        stock.setProductId("PROD_001");

        when(warningRepository.findFirstByStockIdAndWarningTypeAndStatusOrderByTriggeredAtDesc(
                anyString(), any(), any()))
                .thenReturn(Optional.empty());
        when(warningRepository.save(any(StockWarning.class))).thenAnswer(invocation -> {
            StockWarning warning = invocation.getArgument(0);
            assertNotNull(warning.getStockId());
            assertNotNull(warning.getProductId());
            assertNotNull(warning.getProductName());
            assertNotNull(warning.getWarningType());
            assertNotNull(warning.getWarningLevel());
            assertNotNull(warning.getCurrentQuantity());
            assertNotNull(warning.getThreshold());
            assertNotNull(warning.getStatus());
            assertNotNull(warning.getTriggeredAt());
            return warning;
        });

        warningService.triggerLowStockWarning(stock);

        verify(warningRepository).save(any(StockWarning.class));
    }

    @Test
    @DisplayName("不同预警类型独立处理 - 库存不足和积压预警互不干扰")
    void testDifferentWarningTypes_Independent() {
        Stock stock = TestDataBuilder.buildStock(100, 100, 0);
        stock.setWarningThreshold(10);
        stock.setOverstockThreshold(500);

        StockWarning lowStockWarning = TestDataBuilder.buildActiveLowStockWarning();
        lowStockWarning.setStockId(stock.getStockId());

        when(warningRepository.findByStockIdAndStatus(stock.getStockId(), WarningStatus.ACTIVE))
                .thenReturn(Arrays.asList(lowStockWarning));
        when(warningRepository.save(any(StockWarning.class))).thenAnswer(invocation -> invocation.getArgument(0));

        warningService.resolveLowStockWarning(stock);

        ArgumentCaptor<StockWarning> captor = ArgumentCaptor.forClass(StockWarning.class);
        verify(warningRepository).save(captor.capture());

        assertEquals(WarningType.LOW_STOCK, captor.getValue().getWarningType());
        assertEquals(WarningStatus.HANDLED, captor.getValue().getStatus());
    }
}
