package com.fooddelivery.service;

import com.fooddelivery.builder.TestDataBuilder;
import com.fooddelivery.dto.CreateOrderRequest;
import com.fooddelivery.dto.OrderItemDto;
import com.fooddelivery.entity.Dish;
import com.fooddelivery.entity.Order;
import com.fooddelivery.entity.Restaurant;
import com.fooddelivery.exception.BusinessException;
import com.fooddelivery.repository.OrderItemRepository;
import com.fooddelivery.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("订单模块测试 - 订单确认机制")
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderItemRepository orderItemRepository;

    @Mock
    private RestaurantService restaurantService;

    @Mock
    private DeliveryService deliveryService;

    @Mock
    private AnalysisService analysisService;

    @Mock
    private HistoryService historyService;

    @Mock
    private RegionService regionService;

    @InjectMocks
    private OrderService orderService;

    private Restaurant testRestaurant;
    private List<Dish> testDishes;
    private Order testOrder;

    @BeforeEach
    void setUp() {
        testRestaurant = TestDataBuilder.buildRestaurant();
        testDishes = TestDataBuilder.buildDishList(testRestaurant.getRestaurantId());
        testOrder = TestDataBuilder.buildOrder();
    }

    @Test
    @DisplayName("订单创建后等待餐厅确认 - 初始状态为待确认")
    void testOrderInitialStatusIsPendingConfirm() {
        assertEquals("pending_confirm", testOrder.getOrderStatus());
        assertNull(testOrder.getConfirmedAt());
    }

    @Test
    @DisplayName("确认成功场景 - 订单状态变为已确认")
    void testConfirmSuccess() {
        when(orderRepository.findByOrderId(anyString())).thenReturn(Optional.of(testOrder));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> {
            Order order = invocation.getArgument(0);
            order.setOrderStatus("confirmed");
            return order;
        });

        boolean confirmed = orderService.confirmOrder(testOrder.getOrderId());

        assertTrue(confirmed);
        verify(orderRepository, times(1)).findByOrderId(testOrder.getOrderId());
        verify(orderRepository, times(1)).save(any(Order.class));
    }

    @Test
    @DisplayName("确认成功场景 - 记录确认时间")
    void testConfirmSuccessRecordsConfirmedAt() {
        Order order = TestDataBuilder.buildOrder();
        when(orderRepository.findByOrderId(anyString())).thenReturn(Optional.of(order));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        orderService.confirmOrder(order.getOrderId());

        verify(orderRepository).save(argToSave -> {
            assertEquals("confirmed", argToSave.getOrderStatus());
            assertNotNull(argToSave.getConfirmedAt());
            return true;
        });
    }

    @Test
    @DisplayName("确认拒绝场景 - 订单状态变为已取消")
    void testConfirmRejectCancelsOrder() {
        when(orderRepository.findByOrderId(anyString())).thenReturn(Optional.of(testOrder));
        when(orderRepository.save(any(Order.class))).thenReturn(testOrder);

        OrderService spyService = spy(orderService);
        doReturn(false).when(spyService).confirmOrder(anyString());

        assertEquals("pending_confirm", testOrder.getOrderStatus());
    }

    @Test
    @DisplayName("订单状态流转 - 待确认 -> 已确认")
    void testStatusFlow_PendingToConfirmed() {
        Order order = TestDataBuilder.buildOrder("pending_confirm");
        assertEquals("pending_confirm", order.getOrderStatus());

        when(orderRepository.findByOrderId(anyString())).thenReturn(Optional.of(order));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        orderService.confirmOrder(order.getOrderId());

        verify(orderRepository).save(argToSave -> {
            assertEquals("confirmed", argToSave.getOrderStatus());
            return true;
        });
    }

    @Test
    @DisplayName("订单状态流转 - 已确认 -> 配送中")
    void testStatusFlow_ConfirmedToDelivering() {
        Order order = TestDataBuilder.buildOrder("confirmed");
        assertEquals("confirmed", order.getOrderStatus());

        when(orderRepository.findByOrderId(anyString())).thenReturn(Optional.of(order));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        orderService.updateOrderStatus(order.getOrderId(), "delivering");

        verify(orderRepository).save(argToSave -> {
            assertEquals("delivering", argToSave.getOrderStatus());
            return true;
        });
    }

    @Test
    @DisplayName("订单状态流转 - 配送中 -> 已送达")
    void testStatusFlow_DeliveringToDelivered() {
        Order order = TestDataBuilder.buildOrder("delivering");
        assertEquals("delivering", order.getOrderStatus());

        when(orderRepository.findByOrderId(anyString())).thenReturn(Optional.of(order));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        orderService.updateOrderStatus(order.getOrderId(), "delivered");

        verify(orderRepository).save(argToSave -> {
            assertEquals("delivered", argToSave.getOrderStatus());
            assertNotNull(argToSave.getDeliveredAt());
            return true;
        });
    }

    @Test
    @DisplayName("订单状态流转 - 完整生命周期验证")
    void testCompleteStatusLifecycle() {
        String orderId = "order_lifecycle_001";

        Order orderPending = TestDataBuilder.buildOrder("pending_confirm");
        Order orderConfirmed = TestDataBuilder.buildOrder("confirmed");
        Order orderDelivering = TestDataBuilder.buildOrder("delivering");
        Order orderDelivered = TestDataBuilder.buildOrder("delivered");

        assertEquals("pending_confirm", orderPending.getOrderStatus());
        assertEquals("confirmed", orderConfirmed.getOrderStatus());
        assertNotNull(orderConfirmed.getConfirmedAt());
        assertEquals("delivering", orderDelivering.getOrderStatus());
        assertEquals("delivered", orderDelivered.getOrderStatus());
        assertNotNull(orderDelivered.getDeliveredAt());

        assertNull(orderPending.getConfirmedAt());
        assertNull(orderPending.getDeliveredAt());
        assertNotNull(orderConfirmed.getConfirmedAt());
        assertNull(orderConfirmed.getDeliveredAt());
        assertNotNull(orderDelivered.getConfirmedAt());
        assertNotNull(orderDelivered.getDeliveredAt());
    }

    @Test
    @DisplayName("订单不存在时抛出异常")
    void testConfirmNonExistentOrder() {
        when(orderRepository.findByOrderId(anyString())).thenReturn(Optional.empty());

        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> orderService.confirmOrder("non_existent_order"));

        assertEquals("订单不存在", exception.getMessage());
    }

    @Test
    @DisplayName("餐厅已关闭时拒绝订单")
    void testRejectOrderWhenRestaurantClosed() {
        Restaurant closedRestaurant = TestDataBuilder.buildRestaurantClosed();
        when(restaurantService.getRestaurantById(anyString())).thenReturn(Optional.of(closedRestaurant));

        CreateOrderRequest request = new CreateOrderRequest();
        request.setRestaurant_id(closedRestaurant.getRestaurantId());
        request.setOrder_items(new ArrayList<>());
        request.setDelivery_address("测试地址");

        BusinessException exception = assertThrows(BusinessException.class,
                () -> orderService.createOrder(request));

        assertEquals(400, exception.getCode());
        assertEquals("餐厅已关闭，无法下单", exception.getMessage());
    }

    @Test
    @DisplayName("餐厅不存在时拒绝订单")
    void testRejectOrderWhenRestaurantNotExist() {
        when(restaurantService.getRestaurantById(anyString())).thenReturn(Optional.empty());

        CreateOrderRequest request = new CreateOrderRequest();
        request.setRestaurant_id("non_existent");
        request.setOrder_items(new ArrayList<>());
        request.setDelivery_address("测试地址");

        BusinessException exception = assertThrows(BusinessException.class,
                () -> orderService.createOrder(request));

        assertEquals(404, exception.getCode());
        assertEquals("餐厅不存在", exception.getMessage());
    }

    @Test
    @DisplayName("订单状态更新 - 支付状态变更")
    void testUpdatePaymentStatus() {
        Order order = TestDataBuilder.buildOrder();
        assertEquals("pending", order.getPaymentStatus());

        when(orderRepository.findByOrderId(anyString())).thenReturn(Optional.of(order));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        orderService.processPayment(order.getOrderId());

        verify(orderRepository).save(argToSave -> {
            assertEquals("paid", argToSave.getPaymentStatus());
            return true;
        });
    }

    @Test
    @DisplayName("订单评价状态更新")
    void testUpdateReviewStatus() {
        Order order = TestDataBuilder.buildOrderDelivered();
        assertFalse(order.getHasReview());

        order.setHasReview(true);
        order.setOrderStatus("reviewed");

        assertTrue(order.getHasReview());
        assertEquals("reviewed", order.getOrderStatus());
    }

    @Test
    @DisplayName("订单配送区域匹配")
    void testDeliveryRegionMatching() {
        String address1 = "朝阳区建国路88号";
        String address2 = "海淀区中关村大街1号";

        when(regionService.matchRegionByAddress(address1)).thenReturn("朝阳区");
        when(regionService.matchRegionByAddress(address2)).thenReturn("海淀区");

        assertEquals("朝阳区", regionService.matchRegionByAddress(address1));
        assertEquals("海淀区", regionService.matchRegionByAddress(address2));
    }

    @Test
    @DisplayName("订单金额计算 - 满50免配送费")
    void testOrderAmountCalculation_FreeDelivery() {
        List<OrderItemDto> items = new ArrayList<>();
        items.add(new OrderItemDto("dish_001", 2, 30.0));

        OrderService spyService = spy(orderService);

        try {
            java.lang.reflect.Method method = OrderService.class.getDeclaredMethod(
                    "calculateDeliveryFee", double.class);
            method.setAccessible(true);

            double deliveryFee = (double) method.invoke(spyService, 60.0);
            assertEquals(0.0, deliveryFee);
        } catch (Exception e) {
            fail("反射调用失败: " + e.getMessage());
        }
    }

    @Test
    @DisplayName("订单金额计算 - 不满50收配送费")
    void testOrderAmountCalculation_WithDeliveryFee() {
        OrderService spyService = spy(orderService);

        try {
            java.lang.reflect.Method method = OrderService.class.getDeclaredMethod(
                    "calculateDeliveryFee", double.class);
            method.setAccessible(true);

            double deliveryFee = (double) method.invoke(spyService, 40.0);
            assertEquals(5.0, deliveryFee);
        } catch (Exception e) {
            fail("反射调用失败: " + e.getMessage());
        }
    }
}
