package com.restaurant.mgmt.model;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

@Entity
@Table(name = "sales_stats")
public class SalesStat {
    @Id
    @Column(name = "stat_id")
    private String statId;

    @Column(name = "stat_date", nullable = false, unique = true)
    private LocalDate statDate;

    @Column(name = "order_count")
    private int orderCount;

    @Column(name = "total_amount")
    private double totalAmount;

    @Column(name = "cancelled_order_count")
    private int cancelledOrderCount;

    @Column(name = "avg_order_amount")
    private double avgOrderAmount;

    @ElementCollection
    @CollectionTable(name = "dish_sales", joinColumns = @JoinColumn(name = "stat_id"))
    @MapKeyColumn(name = "dish_id")
    @Column(name = "sales_count")
    private Map<String, Integer> dishSales = new HashMap<>();

    @Column(name = "created_at")
    private java.time.LocalDateTime createdAt;

    @Column(name = "updated_at")
    private java.time.LocalDateTime updatedAt;

    public SalesStat() {
        this.createdAt = java.time.LocalDateTime.now();
        this.updatedAt = java.time.LocalDateTime.now();
    }

    public String getStatId() {
        return statId;
    }

    public void setStatId(String statId) {
        this.statId = statId;
    }

    public LocalDate getStatDate() {
        return statDate;
    }

    public void setStatDate(LocalDate statDate) {
        this.statDate = statDate;
    }

    public int getOrderCount() {
        return orderCount;
    }

    public void setOrderCount(int orderCount) {
        this.orderCount = orderCount;
    }

    public double getTotalAmount() {
        return totalAmount;
    }

    public void setTotalAmount(double totalAmount) {
        this.totalAmount = totalAmount;
    }

    public int getCancelledOrderCount() {
        return cancelledOrderCount;
    }

    public void setCancelledOrderCount(int cancelledOrderCount) {
        this.cancelledOrderCount = cancelledOrderCount;
    }

    public double getAvgOrderAmount() {
        return avgOrderAmount;
    }

    public void setAvgOrderAmount(double avgOrderAmount) {
        this.avgOrderAmount = avgOrderAmount;
    }

    public Map<String, Integer> getDishSales() {
        return dishSales;
    }

    public void setDishSales(Map<String, Integer> dishSales) {
        this.dishSales = dishSales;
    }

    public java.time.LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(java.time.LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public java.time.LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(java.time.LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public void addDishSale(String dishId, int quantity) {
        this.dishSales.merge(dishId, quantity, Integer::sum);
    }
}
