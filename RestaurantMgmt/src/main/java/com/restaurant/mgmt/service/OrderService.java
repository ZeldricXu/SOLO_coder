package com.restaurant.mgmt.service;

import com.restaurant.mgmt.dto.CreateOrderRequest;
import com.restaurant.mgmt.dto.CreateOrderResponse;
import com.restaurant.mgmt.dto.OrderItemRequest;
import com.restaurant.mgmt.exception.BusinessException;
import com.restaurant.mgmt.model.*;
import com.restaurant.mgmt.repository.OrderRepository;
import com.restaurant.mgmt.util.IdGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

@Service
public class OrderService {

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private DishService dishService;

    @Autowired
    private TableService tableService;

    @Autowired
    private StockService stockService;

    @Autowired
    private AnalysisService analysisService;

    @Autowired
    private EmployeeService employeeService;

    @Autowired
    private HistoryService historyService;

    @Transactional
    public CreateOrderResponse createOrder(CreateOrderRequest request) {
        if (request.getOrderItems() == null || request.getOrderItems().isEmpty()) {
            throw new BusinessException("订单菜品不能为空");
        }

        Map<String, Dish> dishMap = new HashMap<>();
        for (OrderItemRequest itemRequest : request.getOrderItems()) {
            Dish dish = dishService.getDishById(itemRequest.getDishId());
            if (!"available".equals(dish.getDishStatus())) {
                throw new BusinessException("菜品不可用: " + dish.getDishName());
            }
            if (itemRequest.getQuantity() <= 0) {
                throw new BusinessException("菜品数量必须大于0");
            }
            dishMap.put(itemRequest.getDishId(), dish);
        }

        RestaurantTable table = null;
        if (request.getTableId() != null) {
            table = tableService.getTableById(request.getTableId());
            if (!tableService.isTableReservedOrAvailable(request.getTableId())) {
                throw new BusinessException("桌位不可用");
            }
            tableService.occupyTable(request.getTableId());
        }

        Order order = new Order();
        order.setOrderId(IdGenerator.generateOrderId());
        order.setTableId(table != null ? table.getTableId() : null);
        order.setTableNumber(table != null ? table.getTableNumber() : null);
        order.setCustomerName(request.getCustomerName());
        order.setCustomerPhone(request.getCustomerPhone());
        order.setPaymentMethod(request.getPaymentMethod());
        order.setRemark(request.getRemark());
        order.setCreatedAt(LocalDateTime.now());
        order.setOrderStatus("pending_payment");

        List<OrderItem> orderItems = new ArrayList<>();
        double totalAmount = 0;

        for (OrderItemRequest itemRequest : request.getOrderItems()) {
            Dish dish = dishMap.get(itemRequest.getDishId());
            OrderItem item = new OrderItem(
                dish.getDishId(),
                dish.getDishName(),
                itemRequest.getQuantity(),
                dish.getDishPrice()
            );
            item.setRemark(itemRequest.getRemark());
            orderItems.add(item);
            totalAmount += item.getSubtotal();
        }

        order.setOrderItems(orderItems);
        order.setOrderAmount(totalAmount);

        Order savedOrder = orderRepository.save(order);

        historyService.recordHistory("order", savedOrder.getOrderId(), "创建订单", 
            "创建订单, 金额: " + totalAmount, "system", "create", "success");

        return new CreateOrderResponse(savedOrder.getOrderId(), savedOrder.getOrderStatus(), totalAmount);
    }

    @Transactional
    public Order processPayment(String orderId, String paymentMethod) {
        Order order = getOrderById(orderId);
        
        if (!"pending_payment".equals(order.getOrderStatus())) {
            throw new BusinessException("订单状态不允许支付");
        }

        boolean paymentSuccess = simulatePayment(order.getOrderAmount(), paymentMethod);
        
        if (paymentSuccess) {
            order.setPaymentMethod(paymentMethod);
            order.setOrderStatus("confirmed");
            order.setConfirmedAt(LocalDateTime.now());
            order.setPaidAt(LocalDateTime.now());

            Map<String, Double> ingredientQuantities = calculateIngredientRequirements(order);
            if (!ingredientQuantities.isEmpty()) {
                try {
                    stockService.checkAndReduceStocks(ingredientQuantities, "system", orderId);
                } catch (BusinessException e) {
                    order.setOrderStatus("pending_payment");
                    orderRepository.save(order);
                    throw e;
                }
            }

            analysisService.updateSalesStats(order);
            employeeService.notifyWaiters(order);

            historyService.recordHistory("order", orderId, "支付成功", 
                "订单支付成功, 金额: " + order.getOrderAmount(), "system", "pay", "success");
        } else {
            order.setOrderStatus("cancelled");
            order.setCancelledAt(LocalDateTime.now());
            order.setCancelReason("支付失败");
            
            if (order.getTableId() != null) {
                tableService.releaseTable(order.getTableId());
            }
            
            historyService.recordHistory("order", orderId, "支付失败", 
                "订单支付失败", "system", "pay", "failed");
        }

        return orderRepository.save(order);
    }

    private Map<String, Double> calculateIngredientRequirements(Order order) {
        Map<String, Double> ingredientQuantities = new HashMap<>();
        
        for (OrderItem orderItem : order.getOrderItems()) {
            try {
                Dish dish = dishService.getDishById(orderItem.getDishId());
                if (dish.getIngredients() != null) {
                    for (DishIngredient ingredient : dish.getIngredients()) {
                        double totalQuantity = ingredient.getQuantity() * orderItem.getQuantity();
                        ingredientQuantities.merge(ingredient.getIngredientId(), totalQuantity, Double::sum);
                    }
                }
            } catch (BusinessException ignored) {
            }
        }
        
        return ingredientQuantities;
    }

    private boolean simulatePayment(double amount, String paymentMethod) {
        return true;
    }

    public Order getOrderById(String orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException("订单不存在"));
    }

    public List<Order> getAllOrders() {
        return orderRepository.findAll();
    }

    public List<Order> getOrdersByStatus(String status) {
        return orderRepository.findByOrderStatus(status);
    }

    public List<Order> getOrdersByTimeRange(LocalDateTime startTime, LocalDateTime endTime) {
        return orderRepository.findByCreatedAtBetween(startTime, endTime);
    }

    @Transactional
    public Order cancelOrder(String orderId, String reason) {
        Order order = getOrderById(orderId);
        
        if ("completed".equals(order.getOrderStatus()) || "cancelled".equals(order.getOrderStatus())) {
            throw new BusinessException("订单状态不允许取消");
        }

        order.setOrderStatus("cancelled");
        order.setCancelledAt(LocalDateTime.now());
        order.setCancelReason(reason != null ? reason : "用户取消");

        if (order.getTableId() != null) {
            tableService.releaseTable(order.getTableId());
        }

        if ("confirmed".equals(order.getOrderStatus())) {
            analysisService.recordCancelledOrder(order);
        }

        historyService.recordHistory("order", orderId, "取消订单", 
            "取消订单, 原因: " + reason, "system", "cancel", "success");

        return orderRepository.save(order);
    }

    @Transactional
    public Order completeOrder(String orderId) {
        Order order = getOrderById(orderId);
        
        if (!"confirmed".equals(order.getOrderStatus())) {
            throw new BusinessException("订单状态不允许完成");
        }

        order.setOrderStatus("completed");
        order.setCompletedAt(LocalDateTime.now());

        if (order.getTableId() != null) {
            tableService.releaseTable(order.getTableId());
        }

        historyService.recordHistory("order", orderId, "完成订单", 
            "订单完成", "system", "complete", "success");

        return orderRepository.save(order);
    }
}
