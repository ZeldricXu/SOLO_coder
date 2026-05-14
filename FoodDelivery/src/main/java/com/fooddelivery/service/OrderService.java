package com.fooddelivery.service;

import com.fooddelivery.config.LockConfigProperties;
import com.fooddelivery.dto.CreateOrderRequest;
import com.fooddelivery.dto.CreateOrderResponse;
import com.fooddelivery.dto.OrderItemDto;
import com.fooddelivery.entity.*;
import com.fooddelivery.exception.BusinessException;
import com.fooddelivery.repository.OrderItemRepository;
import com.fooddelivery.repository.OrderRepository;
import com.fooddelivery.util.IdGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
public class OrderService {

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderItemRepository orderItemRepository;

    @Autowired
    private RestaurantService restaurantService;

    @Autowired
    private DeliveryService deliveryService;

    @Autowired
    private AnalysisService analysisService;

    @Autowired
    private HistoryService historyService;

    @Autowired
    private RegionService regionService;

    @Autowired
    private LockConfigProperties lockConfig;

    @Transactional
    public CreateOrderResponse createOrder(CreateOrderRequest request) {
        Restaurant restaurant = restaurantService.getRestaurantById(request.getRestaurant_id())
                .orElseThrow(() -> new BusinessException(404, "餐厅不存在"));

        if (!"open".equals(restaurant.getRestaurantStatus())) {
            throw new BusinessException(400, "餐厅已关闭，无法下单");
        }

        List<Dish> dishes = validateAndGetDishes(request.getRestaurant_id(), request.getOrder_items());
        double orderAmount = calculateOrderAmount(request.getOrder_items(), dishes);
        double deliveryFee = calculateDeliveryFee(orderAmount);
        String deliveryRegion = regionService.matchRegionByAddress(request.getDelivery_address());

        String urgency = normalizeUrgency(request.getOrder_urgency());

        Order order = new Order();
        order.setOrderId(IdGenerator.generateOrderId());
        order.setRestaurantId(request.getRestaurant_id());
        order.setUserId(request.getUser_id() != null ? request.getUser_id() : "user_default");
        order.setOrderAmount(orderAmount);
        order.setDeliveryFee(deliveryFee);
        order.setDeliveryAddress(request.getDelivery_address());
        order.setDeliveryRegion(deliveryRegion);
        order.setOrderStatus("pending_confirm");
        order.setPaymentStatus("pending");
        order.setOrderUrgency(urgency);
        order.setHasReview(false);
        order.setOrderTime(LocalDateTime.now());
        Order savedOrder = orderRepository.save(order);

        saveOrderItems(savedOrder.getOrderId(), request.getOrder_items(), dishes);
        restaurantService.incrementOrderCount(request.getRestaurant_id());

        boolean confirmed = confirmOrder(savedOrder.getOrderId());
        if (!confirmed) {
            savedOrder.setOrderStatus("cancelled");
            orderRepository.save(savedOrder);
            throw new BusinessException(400, "订单被餐厅拒绝");
        }

        savedOrder = orderRepository.findByOrderId(savedOrder.getOrderId()).orElse(savedOrder);
        try {
            deliveryService.createDelivery(savedOrder, urgency);
        } catch (Exception e) {
            savedOrder.setOrderStatus("cancelled");
            orderRepository.save(savedOrder);
            throw new BusinessException(400, e.getMessage());
        }

        analysisService.incrementOrderCount(LocalDateTime.now().getMonth().getValue());
        analysisService.addTotalAmount(LocalDateTime.now().getMonth().getValue(), orderAmount + deliveryFee);
        historyService.recordHistory("order", savedOrder.getOrderId(), "create", 
                "创建订单，金额：" + (orderAmount + deliveryFee) + "，紧急程度：" + urgency);

        return new CreateOrderResponse(savedOrder.getOrderId(), savedOrder.getOrderStatus());
    }

    private String normalizeUrgency(String urgency) {
        if (urgency == null || urgency.isEmpty()) {
            return lockConfig.getDefaultUrgency();
        }
        if (lockConfig.isValidUrgency(urgency)) {
            return urgency;
        }
        log.warn("无效的订单紧急程度: {}, 使用默认值: {}", urgency, lockConfig.getDefaultUrgency());
        return lockConfig.getDefaultUrgency();
    }

    private List<Dish> validateAndGetDishes(String restaurantId, List<OrderItemDto> items) {
        List<Dish> dishes = new ArrayList<>();
        for (OrderItemDto item : items) {
            Dish dish = restaurantService.getDishByRestaurantAndId(restaurantId, item.getDish_id())
                    .orElseThrow(() -> new BusinessException(404, "菜品不存在：" + item.getDish_id()));
            if (!"active".equals(dish.getDishStatus())) {
                throw new BusinessException(400, "菜品已下架：" + dish.getDishName());
            }
            dishes.add(dish);
        }
        return dishes;
    }

    private double calculateOrderAmount(List<OrderItemDto> items, List<Dish> dishes) {
        double total = 0;
        for (int i = 0; i < items.size(); i++) {
            Dish dish = dishes.get(i);
            double price = items.get(i).getPrice() != null ? items.get(i).getPrice() : dish.getDishPrice();
            total += price * items.get(i).getQuantity();
        }
        return total;
    }

    private double calculateDeliveryFee(double orderAmount) {
        if (orderAmount >= 50) {
            return 0;
        }
        return 5.0;
    }

    private void saveOrderItems(String orderId, List<OrderItemDto> items, List<Dish> dishes) {
        for (int i = 0; i < items.size(); i++) {
            OrderItemDto item = items.get(i);
            Dish dish = dishes.get(i);
            double price = item.getPrice() != null ? item.getPrice() : dish.getDishPrice();
            OrderItem orderItem = new OrderItem();
            orderItem.setOrderId(orderId);
            orderItem.setDishId(dish.getDishId());
            orderItem.setDishName(dish.getDishName());
            orderItem.setQuantity(item.getQuantity());
            orderItem.setPrice(price);
            orderItem.setSubtotal(price * item.getQuantity());
            orderItemRepository.save(orderItem);
        }
    }

    @Transactional
    public boolean confirmOrder(String orderId) {
        Order order = orderRepository.findByOrderId(orderId)
                .orElseThrow(() -> new RuntimeException("订单不存在"));
        order.setOrderStatus("confirmed");
        order.setConfirmedAt(LocalDateTime.now());
        orderRepository.save(order);
        historyService.recordHistory("order", orderId, "confirm", "订单已确认");
        return true;
    }

    public Optional<Order> getOrderById(String orderId) {
        return orderRepository.findByOrderId(orderId);
    }

    public List<Order> getOrdersByUserId(String userId) {
        return orderRepository.findByUserIdOrderByOrderTimeDesc(userId);
    }

    public List<Order> getOrdersByRestaurantId(String restaurantId) {
        return orderRepository.findByRestaurantIdOrderByOrderTimeDesc(restaurantId);
    }

    public List<Order> getOrdersByUrgency(String urgency) {
        return orderRepository.findByOrderUrgency(urgency);
    }

    public List<OrderItem> getOrderItems(String orderId) {
        return orderItemRepository.findByOrderId(orderId);
    }

    @Transactional
    public Order updateOrderStatus(String orderId, String status) {
        Order order = orderRepository.findByOrderId(orderId)
                .orElseThrow(() -> new RuntimeException("订单不存在"));
        order.setOrderStatus(status);
        if ("delivered".equals(status)) {
            order.setDeliveredAt(LocalDateTime.now());
        }
        Order saved = orderRepository.save(order);
        historyService.recordHistory("order", orderId, "status_change", "订单状态更新为：" + status);
        return saved;
    }

    @Transactional
    public Order processPayment(String orderId) {
        Order order = orderRepository.findByOrderId(orderId)
                .orElseThrow(() -> new RuntimeException("订单不存在"));
        order.setPaymentStatus("paid");
        Order saved = orderRepository.save(order);
        historyService.recordHistory("order", orderId, "payment", "订单支付完成");
        return saved;
    }
}
