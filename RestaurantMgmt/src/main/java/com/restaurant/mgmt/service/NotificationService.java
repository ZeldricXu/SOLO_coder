package com.restaurant.mgmt.service;

import com.restaurant.mgmt.model.Order;
import com.restaurant.mgmt.model.StockWarning;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class NotificationService {

    private final List<String> sentNotifications = new ArrayList<>();
    private int paymentTimeoutReminderCount = 0;
    private int stockWarningCount = 0;

    public void sendPaymentTimeoutReminder(Order order) {
        String message = String.format("支付超时提醒: 订单 %s, 金额 %.2f, 桌位 %s",
            order.getOrderId(), order.getOrderAmount(), order.getTableNumber());
        sentNotifications.add(message);
        paymentTimeoutReminderCount++;
        System.out.println("[通知] " + message);
    }

    public void sendStockWarning(StockWarning warning) {
        String message = String.format("库存预警: %s(%s), 当前库存: %.2f, 阈值: %.2f, 级别: %s",
            warning.getIngredientName(), warning.getIngredientId(),
            warning.getCurrentQuantity(), warning.getWarningThreshold(),
            warning.getWarningLevel());
        sentNotifications.add(message);
        stockWarningCount++;
        System.out.println("[通知] " + message);
    }

    public void sendOrderNotification(Order order, String message) {
        String notification = String.format("订单通知: 订单 %s - %s", order.getOrderId(), message);
        sentNotifications.add(notification);
        System.out.println("[通知] " + notification);
    }

    public int getPaymentTimeoutReminderCount() {
        return paymentTimeoutReminderCount;
    }

    public int getStockWarningCount() {
        return stockWarningCount;
    }

    public List<String> getSentNotifications() {
        return new ArrayList<>(sentNotifications);
    }

    public void clearNotifications() {
        sentNotifications.clear();
        paymentTimeoutReminderCount = 0;
        stockWarningCount = 0;
    }

    public boolean hasPaymentTimeoutReminder(String orderId) {
        return sentNotifications.stream()
            .anyMatch(n -> n.contains("支付超时提醒") && n.contains(orderId));
    }

    public boolean hasStockWarning(String ingredientId) {
        return sentNotifications.stream()
            .anyMatch(n -> n.contains("库存预警") && n.contains(ingredientId));
    }
}
