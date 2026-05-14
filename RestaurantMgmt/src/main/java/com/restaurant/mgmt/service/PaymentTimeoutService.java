package com.restaurant.mgmt.service;

import com.restaurant.mgmt.config.DynamicPaymentTimeoutConfig;
import com.restaurant.mgmt.model.Order;
import com.restaurant.mgmt.repository.OrderRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class PaymentTimeoutService {

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private DynamicPaymentTimeoutConfig timeoutConfig;

    @Autowired
    private TableService tableService;

    private final Map<String, LocalDateTime> reminderSentTime = new HashMap<>();

    public void checkPaymentTimeout() {
        List<Order> pendingOrders = orderRepository.findByOrderStatus("pending_payment");
        LocalDateTime now = LocalDateTime.now();

        for (Order order : pendingOrders) {
            processOrderTimeout(order, now);
        }
    }

    private void processOrderTimeout(Order order, LocalDateTime now) {
        if (order.getCreatedAt() == null) {
            return;
        }

        int timeoutMinutes = timeoutConfig.getTimeoutMinutes(order.getOrderAmount());
        int reminderMinutes = timeoutConfig.getReminderMinutes(order.getOrderAmount());

        long minutesSinceCreated = ChronoUnit.MINUTES.between(order.getCreatedAt(), now);

        if (minutesSinceCreated >= reminderMinutes && minutesSinceCreated < timeoutMinutes) {
            if (!hasReminderSentRecently(order.getOrderId(), now)) {
                sendTimeoutReminder(order);
                reminderSentTime.put(order.getOrderId(), now);
            }
        }

        if (minutesSinceCreated >= timeoutMinutes) {
            handleTimeoutOrder(order);
        }
    }

    private boolean hasReminderSentRecently(String orderId, LocalDateTime now) {
        LocalDateTime lastSent = reminderSentTime.get(orderId);
        if (lastSent == null) {
            return false;
        }
        return ChronoUnit.MINUTES.between(lastSent, now) < 2;
    }

    public void sendTimeoutReminder(Order order) {
        int timeoutMinutes = timeoutConfig.getTimeoutMinutes(order.getOrderAmount());
        System.out.printf("[超时服务] 发送支付超时提醒: 订单 %s, 金额 %.2f, 剩余时间 %d 分钟%n",
            order.getOrderId(), order.getOrderAmount(), 
            timeoutMinutes - getMinutesSinceCreation(order));
        notificationService.sendPaymentTimeoutReminder(order);
    }

    public void handleTimeoutOrder(Order order) {
        System.out.printf("[超时服务] 订单 %s 支付超时，自动取消%n", order.getOrderId());
        
        order.setOrderStatus("cancelled");
        order.setCancelledAt(LocalDateTime.now());
        order.setCancelReason("支付超时自动取消");

        if (order.getTableId() != null) {
            try {
                tableService.releaseTable(order.getTableId());
            } catch (Exception e) {
                System.out.printf("[超时服务] 释放桌位失败: %s%n", e.getMessage());
            }
        }

        orderRepository.save(order);
        notificationService.sendOrderNotification(order, "订单因支付超时已自动取消");
    }

    private int getMinutesSinceCreation(Order order) {
        if (order.getCreatedAt() == null) {
            return 0;
        }
        return (int) ChronoUnit.MINUTES.between(order.getCreatedAt(), LocalDateTime.now());
    }

    public List<Order> getPendingPaymentOrders() {
        return orderRepository.findByOrderStatus("pending_payment");
    }

    public List<Order> getOrdersNearTimeout() {
        List<Order> pendingOrders = getPendingPaymentOrders();
        List<Order> nearTimeout = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();

        for (Order order : pendingOrders) {
            if (order.getCreatedAt() == null) continue;
            
            int timeoutMinutes = timeoutConfig.getTimeoutMinutes(order.getOrderAmount());
            int reminderMinutes = timeoutConfig.getReminderMinutes(order.getOrderAmount());
            long minutesSinceCreated = ChronoUnit.MINUTES.between(order.getCreatedAt(), now);

            if (minutesSinceCreated >= reminderMinutes && minutesSinceCreated < timeoutMinutes) {
                nearTimeout.add(order);
            }
        }

        return nearTimeout;
    }

    public Map<String, Object> getTimeoutInfo(Order order) {
        Map<String, Object> info = new HashMap<>();
        info.put("orderId", order.getOrderId());
        info.put("orderAmount", order.getOrderAmount());
        info.put("orderSize", timeoutConfig.getOrderSizeCategory(order.getOrderAmount()));
        info.put("timeoutMinutes", timeoutConfig.getTimeoutMinutes(order.getOrderAmount()));
        info.put("reminderMinutes", timeoutConfig.getReminderMinutes(order.getOrderAmount()));
        
        if (order.getCreatedAt() != null) {
            info.put("minutesSinceCreated", getMinutesSinceCreation(order));
        }
        
        return info;
    }

    public void clearReminderHistory() {
        reminderSentTime.clear();
    }
}
