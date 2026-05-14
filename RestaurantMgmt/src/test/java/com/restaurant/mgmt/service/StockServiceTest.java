package com.restaurant.mgmt.service;

import com.restaurant.mgmt.builder.TestDataBuilder;
import com.restaurant.mgmt.config.DynamicStockDeductionConfig;
import com.restaurant.mgmt.exception.BusinessException;
import com.restaurant.mgmt.model.Stock;
import com.restaurant.mgmt.model.StockWarning;
import com.restaurant.mgmt.repository.StockMovementRepository;
import com.restaurant.mgmt.repository.StockRepository;
import com.restaurant.mgmt.repository.StockWarningRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("库存模块 - 单元测试")
class StockServiceTest {

    @Mock
    private StockRepository stockRepository;

    @Mock
    private StockMovementRepository movementRepository;

    @Mock
    private StockWarningRepository warningRepository;

    @Mock
    private HistoryService historyService;

    @Mock
    private NotificationService notificationService;

    @Spy
    private DynamicStockDeductionConfig deductionConfig = new DynamicStockDeductionConfig();

    @InjectMocks
    private StockService stockService;

    @BeforeEach
    void setUp() {
        doNothing().when(historyService).recordHistory(anyString(), anyString(), anyString(),
            anyString(), anyString(), anyString(), anyString());
        when(warningRepository.save(any(StockWarning.class)))
            .thenAnswer(inv -> inv.getArgument(0));
        when(movementRepository.save(any()))
            .thenAnswer(inv -> inv.getArgument(0));
    }

    @Nested
    @DisplayName("实时扣减测试")
    class RealTimeDeductionTests {

        @Test
        @DisplayName("订单确认时应实时触发库存扣减")
        void testRealTimeDeductionOnConfirmation() {
            Stock criticalStock = TestDataBuilder.buildCriticalIngredientStock();
            double initialQuantity = criticalStock.getStockQuantity();
            double deductAmount = 5.0;

            when(stockRepository.findByIngredientId(TestDataBuilder.INGREDIENT_CRITICAL_1))
                .thenReturn(Optional.of(criticalStock));
            when(stockRepository.save(any(Stock.class))).thenAnswer(inv -> inv.getArgument(0));

            Stock updatedStock = stockService.reduceStock(
                TestDataBuilder.INGREDIENT_CRITICAL_1,
                deductAmount,
                "test_operator",
                "订单消耗测试",
                "ref_order_001"
            );

            assertEquals(initialQuantity - deductAmount, updatedStock.getStockQuantity());
            verify(movementRepository, times(1)).save(any());
        }

        @Test
        @DisplayName("库存扣减应立即生效而非定时批量")
        void testDeductionTakesEffectImmediately() {
            Stock stock = TestDataBuilder.buildCriticalIngredientStock();
            stock.setStockQuantity(100.0);

            when(stockRepository.findByIngredientId(TestDataBuilder.INGREDIENT_CRITICAL_1))
                .thenReturn(Optional.of(stock));
            when(stockRepository.save(any(Stock.class))).thenAnswer(inv -> inv.getArgument(0));

            stockService.reduceStock(
                TestDataBuilder.INGREDIENT_CRITICAL_1,
                10.0,
                "operator",
                "test",
                "ref1"
            );

            assertEquals(90.0, stock.getStockQuantity());

            stockService.reduceStock(
                TestDataBuilder.INGREDIENT_CRITICAL_1,
                5.0,
                "operator",
                "test",
                "ref2"
            );

            assertEquals(85.0, stock.getStockQuantity());

            verify(stockRepository, times(2)).save(any(Stock.class));
        }

        @Test
        @DisplayName("库存不足时应抛出异常")
        void testInsufficientStockShouldThrowException() {
            Stock stock = TestDataBuilder.buildCriticalIngredientStock();
            stock.setStockQuantity(5.0);

            when(stockRepository.findByIngredientId(TestDataBuilder.INGREDIENT_CRITICAL_1))
                .thenReturn(Optional.of(stock));

            assertThrows(BusinessException.class, () ->
                stockService.reduceStock(
                    TestDataBuilder.INGREDIENT_CRITICAL_1,
                    10.0,
                    "operator",
                    "test",
                    "ref1"
                )
            );

            verify(stockRepository, never()).save(any(Stock.class));
        }

        @Test
        @DisplayName("库存增加应实时更新")
        void testStockAdditionRealTime() {
            Stock stock = TestDataBuilder.buildCriticalIngredientStock();
            stock.setStockQuantity(50.0);

            when(stockRepository.findByIngredientId(TestDataBuilder.INGREDIENT_CRITICAL_1))
                .thenReturn(Optional.of(stock));
            when(stockRepository.save(any(Stock.class))).thenAnswer(inv -> inv.getArgument(0));

            Stock updated = stockService.addStock(
                TestDataBuilder.INGREDIENT_CRITICAL_1,
                30.0,
                "operator",
                "入库测试",
                "ref_in_001"
            );

            assertEquals(80.0, updated.getStockQuantity());
        }
    }

    @Nested
    @DisplayName("扣减策略差异测试")
    class DeductionStrategyTests {

        @Test
        @DisplayName("关键食材应使用预扣减策略")
        void testCriticalIngredientPreDeduct() {
            assertTrue(deductionConfig.shouldPreDeduct(
                TestDataBuilder.INGREDIENT_CRITICAL_1, "肉类"));
            assertEquals("pre_deduct", 
                deductionConfig.getDeductionStrategy(TestDataBuilder.INGREDIENT_CRITICAL_1, "肉类"));
        }

        @Test
        @DisplayName("海鲜类食材应使用预扣减策略")
        void testSeafoodCategoryPreDeduct() {
            assertTrue(deductionConfig.shouldPreDeduct("ingredient_seafood_001", "海鲜"));
            assertEquals("pre_deduct",
                deductionConfig.getDeductionStrategy("ingredient_seafood_001", "海鲜"));
        }

        @Test
        @DisplayName("普通食材应使用确认扣减策略")
        void testNormalIngredientConfirmDeduct() {
            assertTrue(deductionConfig.shouldConfirmDeduct(
                TestDataBuilder.INGREDIENT_NORMAL_1, "调料"));
            assertEquals("confirm_deduct",
                deductionConfig.getDeductionStrategy(TestDataBuilder.INGREDIENT_NORMAL_1, "调料"));
        }

        @Test
        @DisplayName("蔬菜类食材应使用确认扣减策略")
        void testVegetableCategoryConfirmDeduct() {
            assertTrue(deductionConfig.shouldConfirmDeduct("ingredient_veg_001", "蔬菜"));
            assertEquals("confirm_deduct",
                deductionConfig.getDeductionStrategy("ingredient_veg_001", "蔬菜"));
        }

        @Test
        @DisplayName("预扣减时关键食材库存应减少")
        void testPreDeductReducesCriticalStock() {
            Stock criticalStock = TestDataBuilder.buildCriticalIngredientStock();
            double initialQuantity = criticalStock.getStockQuantity();

            when(stockRepository.findByIngredientId(TestDataBuilder.INGREDIENT_CRITICAL_1))
                .thenReturn(Optional.of(criticalStock));
            when(stockRepository.save(any(Stock.class))).thenAnswer(inv -> inv.getArgument(0));

            Map<String, Double> ingredients = new HashMap<>();
            ingredients.put(TestDataBuilder.INGREDIENT_CRITICAL_1, 10.0);

            Map<String, Object> result = stockService.preDeductCriticalIngredients(
                ingredients, "operator", "ref_001");

            assertTrue((Boolean) result.get("success"));
            assertEquals(initialQuantity - 10.0, criticalStock.getStockQuantity());
        }

        @Test
        @DisplayName("确认扣减时普通食材库存应减少")
        void testConfirmDeductReducesNormalStock() {
            Stock normalStock = TestDataBuilder.buildNormalIngredientStock();
            double initialQuantity = normalStock.getStockQuantity();

            when(stockRepository.findByIngredientId(TestDataBuilder.INGREDIENT_NORMAL_1))
                .thenReturn(Optional.of(normalStock));
            when(stockRepository.save(any(Stock.class))).thenAnswer(inv -> inv.getArgument(0));

            Map<String, Double> ingredients = new HashMap<>();
            ingredients.put(TestDataBuilder.INGREDIENT_NORMAL_1, 20.0);

            Map<String, Object> result = stockService.deductOnConfirmation(
                ingredients, "operator", "ref_001");

            assertTrue((Boolean) result.get("success"));
            assertEquals(initialQuantity - 20.0, normalStock.getStockQuantity());
        }
    }

    @Nested
    @DisplayName("库存预警阈值测试")
    class WarningThresholdTests {

        @Test
        @DisplayName("库存低于阈值应触发预警")
        void testLowStockTriggersWarning() {
            Stock lowStock = TestDataBuilder.buildLowStockIngredient();

            when(stockRepository.save(any(Stock.class))).thenReturn(lowStock);

            lowStock.setStockQuantity(5.0);
            stockService.checkAndCreateWarning(lowStock);

            verify(warningRepository, times(1)).save(any(StockWarning.class));
            verify(notificationService, times(1)).sendStockWarning(any(StockWarning.class));
        }

        @Test
        @DisplayName("库存高于阈值不应触发预警")
        void testHighStockNoWarning() {
            Stock highStock = TestDataBuilder.buildCriticalIngredientStock();

            stockService.checkAndCreateWarning(highStock);

            verify(warningRepository, never()).save(any(StockWarning.class));
            verify(notificationService, never()).sendStockWarning(any(StockWarning.class));
        }

        @Test
        @DisplayName("库存极度不足应触发高级别预警")
        void testCriticalLowStockHighLevelWarning() {
            String level = deductionConfig.getWarningLevel(2.0, 10.0);
            assertEquals("high", level);
        }

        @Test
        @DisplayName("库存中等不足应触发中级别预警")
        void testMediumLowStockMediumLevelWarning() {
            String level = deductionConfig.getWarningLevel(5.0, 10.0);
            assertEquals("medium", level);
        }

        @Test
        @DisplayName("库存轻微不足应触发低级别预警")
        void testSlightlyLowStockLowLevelWarning() {
            String level = deductionConfig.getWarningLevel(8.0, 10.0);
            assertEquals("low", level);
        }

        @Test
        @DisplayName("checkWarningThreshold应正确检测阈值")
        void testCheckWarningThreshold() {
            Stock lowStock = TestDataBuilder.buildLowStockIngredient();
            Stock highStock = TestDataBuilder.buildCriticalIngredientStock();

            when(stockRepository.findByIngredientId("ing_low"))
                .thenReturn(Optional.of(lowStock));
            when(stockRepository.findByIngredientId("ing_high"))
                .thenReturn(Optional.of(highStock));

            assertTrue(stockService.checkWarningThreshold("ing_low"));
            assertFalse(stockService.checkWarningThreshold("ing_high"));
        }
    }

    @Nested
    @DisplayName("预警通知测试")
    class WarningNotificationTests {

        @Test
        @DisplayName("库存扣减后低于阈值应发送预警通知")
        void testDeductionTriggersWarningNotification() {
            Stock stock = TestDataBuilder.buildCriticalIngredientStock();
            stock.setStockQuantity(15.0);

            when(stockRepository.findByIngredientId(TestDataBuilder.INGREDIENT_CRITICAL_1))
                .thenReturn(Optional.of(stock));
            when(stockRepository.save(any(Stock.class))).thenReturn(stock);

            stockService.reduceStock(
                TestDataBuilder.INGREDIENT_CRITICAL_1,
                10.0,
                "operator",
                "test",
                "ref_001"
            );

            verify(notificationService, times(1))
                .sendStockWarning(any(StockWarning.class));
        }

        @Test
        @DisplayName("多次预警应发送多次通知")
        void testMultipleWarningsSendMultipleNotifications() {
            Stock stock1 = TestDataBuilder.buildLowStockIngredient();
            stock1.setIngredientId("ing_1");
            Stock stock2 = TestDataBuilder.buildMediumLowStockIngredient();
            stock2.setIngredientId("ing_2");

            when(stockRepository.save(any(Stock.class))).thenReturn(stock1, stock2);

            stockService.checkAndCreateWarning(stock1);
            stockService.checkAndCreateWarning(stock2);

            verify(notificationService, times(2))
                .sendStockWarning(any(StockWarning.class));
        }

        @Test
        @DisplayName("预警通知应包含正确的食材信息")
        void testWarningNotificationContainsCorrectInfo() {
            Stock stock = TestDataBuilder.buildLowStockIngredient();

            when(stockRepository.save(any(Stock.class))).thenReturn(stock);

            stockService.checkAndCreateWarning(stock);

            verify(notificationService).sendStockWarning(argThat(warning ->
                warning.getIngredientId().equals(stock.getIngredientId()) &&
                warning.getIngredientName().equals(stock.getIngredientName()) &&
                warning.getCurrentQuantity() == stock.getStockQuantity()
            ));
        }
    }

    @Nested
    @DisplayName("批量库存检查测试")
    class BatchStockCheckTests {

        @Test
        @DisplayName("批量检查扣减全部充足应成功")
        void testBatchCheckAllSufficient() {
            Stock stock1 = TestDataBuilder.buildCriticalIngredientStock();
            stock1.setIngredientId("ing_1");
            Stock stock2 = TestDataBuilder.buildNormalIngredientStock();
            stock2.setIngredientId("ing_2");

            when(stockRepository.findByIngredientId("ing_1")).thenReturn(Optional.of(stock1));
            when(stockRepository.findByIngredientId("ing_2")).thenReturn(Optional.of(stock2));
            when(stockRepository.save(any(Stock.class))).thenAnswer(inv -> inv.getArgument(0));

            Map<String, Double> ingredients = new HashMap<>();
            ingredients.put("ing_1", 10.0);
            ingredients.put("ing_2", 20.0);

            Map<String, Boolean> result = stockService.checkAndReduceStocks(
                ingredients, "operator", "ref_batch_001");

            assertTrue(result.get("ing_1"));
            assertTrue(result.get("ing_2"));
        }

        @Test
        @DisplayName("批量检查部分不足应抛出异常")
        void testBatchCheckPartialInsufficient() {
            Stock stock1 = TestDataBuilder.buildCriticalIngredientStock();
            stock1.setIngredientId("ing_1");
            stock1.setStockQuantity(5.0);
            Stock stock2 = TestDataBuilder.buildNormalIngredientStock();
            stock2.setIngredientId("ing_2");

            when(stockRepository.findByIngredientId("ing_1")).thenReturn(Optional.of(stock1));
            when(stockRepository.findByIngredientId("ing_2")).thenReturn(Optional.of(stock2));

            Map<String, Double> ingredients = new HashMap<>();
            ingredients.put("ing_1", 10.0);
            ingredients.put("ing_2", 20.0);

            assertThrows(BusinessException.class, () ->
                stockService.checkAndReduceStocks(ingredients, "operator", "ref_batch_002")
            );
        }
    }
}
