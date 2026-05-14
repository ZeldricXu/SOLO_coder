package com.restaurant.mgmt.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "promotions")
public class Promotion {
    @Id
    @Column(name = "promotion_id")
    private String promotionId;

    @Column(name = "promotion_name", nullable = false)
    private String promotionName;

    @Column(name = "promotion_type")
    private String promotionType;

    @Column(name = "description")
    private String description;

    @Column(name = "discount_type")
    private String discountType;

    @Column(name = "discount_value")
    private double discountValue;

    @Column(name = "min_order_amount")
    private double minOrderAmount;

    @Column(name = "max_discount_amount")
    private double maxDiscountAmount;

    @ElementCollection
    @CollectionTable(name = "promotion_dishes", joinColumns = @JoinColumn(name = "promotion_id"))
    @Column(name = "dish_id")
    private List<String> applicableDishes = new ArrayList<>();

    @Column(name = "start_time")
    private LocalDateTime startTime;

    @Column(name = "end_time")
    private LocalDateTime endTime;

    @Column(name = "status")
    private String status;

    @Column(name = "usage_limit")
    private int usageLimit;

    @Column(name = "usage_count")
    private int usageCount;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public Promotion() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
        this.status = "draft";
        this.usageCount = 0;
    }

    public String getPromotionId() {
        return promotionId;
    }

    public void setPromotionId(String promotionId) {
        this.promotionId = promotionId;
    }

    public String getPromotionName() {
        return promotionName;
    }

    public void setPromotionName(String promotionName) {
        this.promotionName = promotionName;
    }

    public String getPromotionType() {
        return promotionType;
    }

    public void setPromotionType(String promotionType) {
        this.promotionType = promotionType;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getDiscountType() {
        return discountType;
    }

    public void setDiscountType(String discountType) {
        this.discountType = discountType;
    }

    public double getDiscountValue() {
        return discountValue;
    }

    public void setDiscountValue(double discountValue) {
        this.discountValue = discountValue;
    }

    public double getMinOrderAmount() {
        return minOrderAmount;
    }

    public void setMinOrderAmount(double minOrderAmount) {
        this.minOrderAmount = minOrderAmount;
    }

    public double getMaxDiscountAmount() {
        return maxDiscountAmount;
    }

    public void setMaxDiscountAmount(double maxDiscountAmount) {
        this.maxDiscountAmount = maxDiscountAmount;
    }

    public List<String> getApplicableDishes() {
        return applicableDishes;
    }

    public void setApplicableDishes(List<String> applicableDishes) {
        this.applicableDishes = applicableDishes;
    }

    public LocalDateTime getStartTime() {
        return startTime;
    }

    public void setStartTime(LocalDateTime startTime) {
        this.startTime = startTime;
    }

    public LocalDateTime getEndTime() {
        return endTime;
    }

    public void setEndTime(LocalDateTime endTime) {
        this.endTime = endTime;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public int getUsageLimit() {
        return usageLimit;
    }

    public void setUsageLimit(int usageLimit) {
        this.usageLimit = usageLimit;
    }

    public int getUsageCount() {
        return usageCount;
    }

    public void setUsageCount(int usageCount) {
        this.usageCount = usageCount;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public boolean isActive() {
        if (!"active".equals(this.status)) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        boolean withinTime = (this.startTime == null || !now.isBefore(this.startTime)) &&
                            (this.endTime == null || !now.isAfter(this.endTime));
        boolean withinLimit = this.usageLimit == 0 || this.usageCount < this.usageLimit;
        return withinTime && withinLimit;
    }
}
