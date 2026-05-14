package com.homeservice.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "service_stats")
public class ServiceStat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "stat_id", unique = true, nullable = false)
    private String statId;

    @Column(name = "stat_month", nullable = false)
    private String statMonth;

    @Column(name = "staff_count")
    private Integer staffCount = 0;

    @Column(name = "booking_count")
    private Integer bookingCount = 0;

    @Column(name = "review_count")
    private Integer reviewCount = 0;

    @Column(name = "total_amount")
    private Double totalAmount = 0.0;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        updatedAt = Instant.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    public ServiceStat() {}

    public ServiceStat(String statId, String statMonth) {
        this.statId = statId;
        this.statMonth = statMonth;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getStatId() { return statId; }
    public void setStatId(String statId) { this.statId = statId; }
    public String getStatMonth() { return statMonth; }
    public void setStatMonth(String statMonth) { this.statMonth = statMonth; }
    public Integer getStaffCount() { return staffCount; }
    public void setStaffCount(Integer staffCount) { this.staffCount = staffCount; }
    public Integer getBookingCount() { return bookingCount; }
    public void setBookingCount(Integer bookingCount) { this.bookingCount = bookingCount; }
    public Integer getReviewCount() { return reviewCount; }
    public void setReviewCount(Integer reviewCount) { this.reviewCount = reviewCount; }
    public Double getTotalAmount() { return totalAmount; }
    public void setTotalAmount(Double totalAmount) { this.totalAmount = totalAmount; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
