package com.restaurant.mgmt.dto;

public class CreateOrderResponse {
    private String orderId;
    private String status;
    private double orderAmount;

    public CreateOrderResponse() {
    }

    public CreateOrderResponse(String orderId, String status, double orderAmount) {
        this.orderId = orderId;
        this.status = status;
        this.orderAmount = orderAmount;
    }

    public String getOrderId() {
        return orderId;
    }

    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public double getOrderAmount() {
        return orderAmount;
    }

    public void setOrderAmount(double orderAmount) {
        this.orderAmount = orderAmount;
    }
}
