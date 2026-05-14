package com.restaurant.mgmt.service;

import com.restaurant.mgmt.builder.TestDataBuilder;
import com.restaurant.mgmt.exception.BusinessException;
import com.restaurant.mgmt.model.Dish;
import com.restaurant.mgmt.repository.DishRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("菜品管理模块 - 单元测试")
class DishServiceTest {

    @Mock
    private DishRepository dishRepository;

    @Mock
    private HistoryService historyService;

    @InjectMocks
    private DishService dishService;

    @BeforeEach
    void setUp() {
        doNothing().when(historyService).recordHistory(anyString(), anyString(), anyString(),
            anyString(), anyString(), anyString(), anyString());
    }

    @Nested
    @DisplayName("菜品状态变更测试")
    class DishStatusChangeTests {

        @Test
        @DisplayName("菜品应能上架")
        void testDishCanBePutOnline() {
            Dish dish = TestDataBuilder.buildOfflineDish();

            when(dishRepository.findById(TestDataBuilder.DISH_SIGNATURE_ID))
                .thenReturn(Optional.of(dish));
            when(dishRepository.save(any(Dish.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            Dish updated = dishService.updateStatus(
                TestDataBuilder.DISH_SIGNATURE_ID, "available");

            assertEquals("available", updated.getDishStatus());
        }

        @Test
        @DisplayName("菜品应能标记为售罄")
        void testDishCanBeMarkedSoldOut() {
            Dish dish = TestDataBuilder.buildSignatureDish();

            when(dishRepository.findById(TestDataBuilder.DISH_SIGNATURE_ID))
                .thenReturn(Optional.of(dish));
            when(dishRepository.save(any(Dish.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            Dish updated = dishService.updateStatus(
                TestDataBuilder.DISH_SIGNATURE_ID, "sold_out");

            assertEquals("sold_out", updated.getDishStatus());
        }

        @Test
        @DisplayName("菜品应能下架")
        void testDishCanBeTakenOffline() {
            Dish dish = TestDataBuilder.buildSignatureDish();

            when(dishRepository.findById(TestDataBuilder.DISH_SIGNATURE_ID))
                .thenReturn(Optional.of(dish));
            when(dishRepository.save(any(Dish.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            Dish updated = dishService.updateStatus(
                TestDataBuilder.DISH_SIGNATURE_ID, "offline");

            assertEquals("offline", updated.getDishStatus());
        }

        @Test
        @DisplayName("不存在的菜品状态变更应失败")
        void testStatusChangeForNonExistentDishFails() {
            when(dishRepository.findById("non_existent"))
                .thenReturn(Optional.empty());

            assertThrows(BusinessException.class, () ->
                dishService.updateStatus("non_existent", "available")
            );
        }

        @Test
        @DisplayName("状态变更应更新时间戳")
        void testStatusChangeUpdatesTimestamp() {
            Dish dish = TestDataBuilder.buildSignatureDish();
            java.time.LocalDateTime oldUpdatedAt = dish.getUpdatedAt();

            when(dishRepository.findById(TestDataBuilder.DISH_SIGNATURE_ID))
                .thenReturn(Optional.of(dish));
            when(dishRepository.save(any(Dish.class)))
                .thenAnswer(inv -> {
                    Dish d = inv.getArgument(0);
                    d.setUpdatedAt(java.time.LocalDateTime.now());
                    return d;
                });

            Dish updated = dishService.updateStatus(
                TestDataBuilder.DISH_SIGNATURE_ID, "sold_out");

            assertNotNull(updated.getUpdatedAt());
        }

        @Test
        @DisplayName("多次状态变更应正确记录")
        void testMultipleStatusChanges() {
            Dish dish = TestDataBuilder.buildSignatureDish();

            when(dishRepository.findById(TestDataBuilder.DISH_SIGNATURE_ID))
                .thenReturn(Optional.of(dish));
            when(dishRepository.save(any(Dish.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            dishService.updateStatus(TestDataBuilder.DISH_SIGNATURE_ID, "sold_out");
            assertEquals("sold_out", dish.getDishStatus());

            dishService.updateStatus(TestDataBuilder.DISH_SIGNATURE_ID, "available");
            assertEquals("available", dish.getDishStatus());

            dishService.updateStatus(TestDataBuilder.DISH_SIGNATURE_ID, "offline");
            assertEquals("offline", dish.getDishStatus());

            verify(dishRepository, times(3)).save(any(Dish.class));
        }
    }

    @Nested
    @DisplayName("菜品分类管理测试")
    class DishCategoryManagementTests {

        @Test
        @DisplayName("应能按分类查询菜品")
        void testGetDishesByCategory() {
            Dish mainDish = TestDataBuilder.buildSignatureDish();
            Dish appetizerDish = TestDataBuilder.buildAppetizerDish();

            when(dishRepository.findByDishCategory("main"))
                .thenReturn(List.of(mainDish));
            when(dishRepository.findByDishCategory("appetizer"))
                .thenReturn(List.of(appetizerDish));

            List<Dish> mainDishes = dishService.getDishesByCategory("main");
            List<Dish> appetizerDishes = dishService.getDishesByCategory("appetizer");

            assertEquals(1, mainDishes.size());
            assertEquals("main", mainDishes.get(0).getDishCategory());
            assertEquals(1, appetizerDishes.size());
            assertEquals("appetizer", appetizerDishes.get(0).getDishCategory());
        }

        @Test
        @DisplayName("应能更新菜品分类")
        void testUpdateDishCategory() {
            Dish dish = TestDataBuilder.buildSignatureDish();
            dish.setDishCategory("main");

            when(dishRepository.findById(TestDataBuilder.DISH_SIGNATURE_ID))
                .thenReturn(Optional.of(dish));
            when(dishRepository.save(any(Dish.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            Dish updateDto = new Dish();
            updateDto.setDishCategory("special");

            Dish updated = dishService.updateDish(
                TestDataBuilder.DISH_SIGNATURE_ID, updateDto);

            assertEquals("special", updated.getDishCategory());
        }

        @Test
        @DisplayName("同一分类应能包含多个菜品")
        void testCategoryContainsMultipleDishes() {
            Dish dish1 = TestDataBuilder.buildSignatureDish();
            dish1.setDishCategory("main");
            Dish dish2 = TestDataBuilder.buildAppetizerDish();
            dish2.setDishCategory("main");

            when(dishRepository.findByDishCategory("main"))
                .thenReturn(Arrays.asList(dish1, dish2));

            List<Dish> mainDishes = dishService.getDishesByCategory("main");

            assertEquals(2, mainDishes.size());
            assertTrue(mainDishes.stream()
                .allMatch(d -> "main".equals(d.getDishCategory())));
        }

        @Test
        @DisplayName("招牌菜应使用特殊类型")
        void testSignatureDishType() {
            Dish signature = TestDataBuilder.buildSignatureDish();
            assertEquals("signature", signature.getDishType());
        }

        @Test
        @DisplayName("普通菜品应使用regular类型")
        void testRegularDishType() {
            Dish regular = TestDataBuilder.buildAppetizerDish();
            assertEquals("regular", regular.getDishType());
        }
    }

    @Nested
    @DisplayName("菜品上下架测试")
    class DishOnlineOfflineTests {

        @Test
        @DisplayName("上架菜品应设置available状态")
        void testOnlineDishSetsAvailableStatus() {
            Dish dish = TestDataBuilder.buildOfflineDish();

            when(dishRepository.findById(TestDataBuilder.DISH_SIGNATURE_ID))
                .thenReturn(Optional.of(dish));
            when(dishRepository.save(any(Dish.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            Dish online = dishService.putOnline(TestDataBuilder.DISH_SIGNATURE_ID);

            assertEquals("available", online.getDishStatus());
        }

        @Test
        @DisplayName("下架菜品应设置offline状态")
        void testOfflineDishSetsOfflineStatus() {
            Dish dish = TestDataBuilder.buildSignatureDish();

            when(dishRepository.findById(TestDataBuilder.DISH_SIGNATURE_ID))
                .thenReturn(Optional.of(dish));
            when(dishRepository.save(any(Dish.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            Dish offline = dishService.takeOffline(TestDataBuilder.DISH_SIGNATURE_ID);

            assertEquals("offline", offline.getDishStatus());
        }

        @Test
        @DisplayName("已上架菜品再次上架不应报错")
        void testOnlineAlreadyOnlineDish() {
            Dish dish = TestDataBuilder.buildSignatureDish();

            when(dishRepository.findById(TestDataBuilder.DISH_SIGNATURE_ID))
                .thenReturn(Optional.of(dish));
            when(dishRepository.save(any(Dish.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            Dish result = dishService.putOnline(TestDataBuilder.DISH_SIGNATURE_ID);

            assertEquals("available", result.getDishStatus());
        }

        @Test
        @DisplayName("已下架菜品再次下架不应报错")
        void testOfflineAlreadyOfflineDish() {
            Dish dish = TestDataBuilder.buildOfflineDish();

            when(dishRepository.findById(TestDataBuilder.DISH_SIGNATURE_ID))
                .thenReturn(Optional.of(dish));
            when(dishRepository.save(any(Dish.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            Dish result = dishService.takeOffline(TestDataBuilder.DISH_SIGNATURE_ID);

            assertEquals("offline", result.getDishStatus());
        }

        @Test
        @DisplayName("上架售罄菜品应变为available")
        void testOnlineSoldOutDish() {
            Dish dish = TestDataBuilder.buildSoldOutDish();

            when(dishRepository.findById(TestDataBuilder.DISH_SIGNATURE_ID))
                .thenReturn(Optional.of(dish));
            when(dishRepository.save(any(Dish.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            Dish result = dishService.putOnline(TestDataBuilder.DISH_SIGNATURE_ID);

            assertEquals("available", result.getDishStatus());
        }
    }

    @Nested
    @DisplayName("菜品CRUD测试")
    class DishCrudTests {

        @Test
        @DisplayName("应能创建新菜品")
        void testCreateDish() {
            Dish dish = TestDataBuilder.buildSignatureDish();

            when(dishRepository.existsByDishId(anyString())).thenReturn(false);
            when(dishRepository.save(any(Dish.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            Dish created = dishService.createDish(dish);

            assertNotNull(created);
            assertNotNull(created.getDishId());
            verify(dishRepository, times(1)).save(any(Dish.class));
        }

        @Test
        @DisplayName("创建菜品时ID已存在应失败")
        void testCreateDishWithExistingIdFails() {
            Dish dish = TestDataBuilder.buildSignatureDish();

            when(dishRepository.existsByDishId(dish.getDishId())).thenReturn(true);

            assertThrows(BusinessException.class, () ->
                dishService.createDish(dish)
            );
        }

        @Test
        @DisplayName("创建菜品时名称为空应失败")
        void testCreateDishWithEmptyNameFails() {
            Dish dish = TestDataBuilder.buildSignatureDish();
            dish.setDishName("");

            assertThrows(BusinessException.class, () ->
                dishService.createDish(dish)
            );
        }

        @Test
        @DisplayName("应能更新菜品信息")
        void testUpdateDish() {
            Dish dish = TestDataBuilder.buildSignatureDish();

            when(dishRepository.findById(TestDataBuilder.DISH_SIGNATURE_ID))
                .thenReturn(Optional.of(dish));
            when(dishRepository.save(any(Dish.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            Dish updateDto = new Dish();
            updateDto.setDishName("更新后的招牌菜");
            updateDto.setDishPrice(68.0);
            updateDto.setDescription("更新后的描述");

            Dish updated = dishService.updateDish(
                TestDataBuilder.DISH_SIGNATURE_ID, updateDto);

            assertEquals("更新后的招牌菜", updated.getDishName());
            assertEquals(68.0, updated.getDishPrice());
            assertEquals("更新后的描述", updated.getDescription());
        }

        @Test
        @DisplayName("更新不存在的菜品应失败")
        void testUpdateNonExistentDishFails() {
            when(dishRepository.findById("non_existent"))
                .thenReturn(Optional.empty());

            Dish updateDto = new Dish();
            updateDto.setDishName("测试");

            assertThrows(BusinessException.class, () ->
                dishService.updateDish("non_existent", updateDto)
            );
        }

        @Test
        @DisplayName("应能删除菜品")
        void testDeleteDish() {
            when(dishRepository.existsById(TestDataBuilder.DISH_SIGNATURE_ID))
                .thenReturn(true);
            doNothing().when(dishRepository).deleteById(anyString());

            dishService.deleteDish(TestDataBuilder.DISH_SIGNATURE_ID);

            verify(dishRepository, times(1))
                .deleteById(TestDataBuilder.DISH_SIGNATURE_ID);
        }

        @Test
        @DisplayName("删除不存在的菜品应失败")
        void testDeleteNonExistentDishFails() {
            when(dishRepository.existsById("non_existent"))
                .thenReturn(false);

            assertThrows(BusinessException.class, () ->
                dishService.deleteDish("non_existent")
            );
        }

        @Test
        @DisplayName("应能查询单个菜品")
        void testGetDishById() {
            Dish dish = TestDataBuilder.buildSignatureDish();

            when(dishRepository.findById(TestDataBuilder.DISH_SIGNATURE_ID))
                .thenReturn(Optional.of(dish));

            Dish found = dishService.getDishById(TestDataBuilder.DISH_SIGNATURE_ID);

            assertNotNull(found);
            assertEquals(TestDataBuilder.DISH_SIGNATURE_ID, found.getDishId());
            assertEquals("招牌菜品", found.getDishName());
        }

        @Test
        @DisplayName("应能查询所有菜品")
        void testGetAllDishes() {
            List<Dish> dishes = TestDataBuilder.buildMultipleDishes();

            when(dishRepository.findAll()).thenReturn(dishes);

            List<Dish> found = dishService.getAllDishes();

            assertEquals(3, found.size());
        }
    }

    @Nested
    @DisplayName("菜品价格与配料测试")
    class DishPriceAndIngredientTests {

        @Test
        @DisplayName("菜品价格应能更新")
        void testDishPriceUpdate() {
            Dish dish = TestDataBuilder.buildSignatureDish();
            double oldPrice = dish.getDishPrice();

            when(dishRepository.findById(TestDataBuilder.DISH_SIGNATURE_ID))
                .thenReturn(Optional.of(dish));
            when(dishRepository.save(any(Dish.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            Dish updateDto = new Dish();
            updateDto.setDishPrice(88.0);

            Dish updated = dishService.updateDish(
                TestDataBuilder.DISH_SIGNATURE_ID, updateDto);

            assertEquals(88.0, updated.getDishPrice());
            assertNotEquals(oldPrice, updated.getDishPrice());
        }

        @Test
        @DisplayName("招牌菜应包含配料信息")
        void testSignatureDishHasIngredients() {
            Dish dish = TestDataBuilder.buildSignatureDish();

            assertNotNull(dish.getIngredients());
            assertFalse(dish.getIngredients().isEmpty());
        }

        @Test
        @DisplayName("配料应包含正确的食材信息")
        void testIngredientsHaveCorrectInfo() {
            Dish dish = TestDataBuilder.buildSignatureDish();

            assertTrue(dish.getIngredients().stream()
                .anyMatch(ing -> 
                    TestDataBuilder.INGREDIENT_CRITICAL_1.equals(ing.getIngredientId())
                )
            );
        }

        @Test
        @DisplayName("菜品应有制作时间")
        void testDishHasPreparationTime() {
            Dish dish = TestDataBuilder.buildSignatureDish();

            assertNotNull(dish.getPreparationTime());
            assertTrue(dish.getPreparationTime() > 0);
        }

        @Test
        @DisplayName("菜品应有辣度等级")
        void testDishHasSpicyLevel() {
            Dish dish = TestDataBuilder.buildSignatureDish();

            assertNotNull(dish.getSpicyLevel());
            assertTrue(dish.getSpicyLevel() >= 0);
        }
    }
}
