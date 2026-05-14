package com.homeservice.entity;

import com.homeservice.enums.BookingStatus;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "bookings")
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "booking_id", unique = true, nullable = false)
    private String bookingId;

    @Column(name = "staff_id", nullable = false)
    private String staffId;

    @Column(name = "customer_id", nullable = false)
    private String customerId;

    @Column(name = "service_type", nullable = false)
    private String serviceType;

    @Column(name = "service_time", nullable = false)
    private Instant serviceTime;

    @Column(name = "service_duration", nullable = false)
    private Integer serviceDuration = 2;

    @Enumerated(EnumType.STRING)
    @Column(name = "booking_status", nullable = false)
    private BookingStatus bookingStatus = BookingStatus.CONFIRMED;

    @Column(name = "booking_amount", nullable = false)
    private Double bookingAmount;

    @Column(name = "is_reviewed", nullable = false)
    private Boolean isReviewed = false;

    @Column(name = "is_settled", nullable = false)
    private Boolean isSettled = false;

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

    public Booking() {}

    public Booking(String bookingId, String staffId, String customerId, String serviceType,
                   Instant serviceTime, Integer serviceDuration, Double bookingAmount) {
        this.bookingId = bookingId;
        this.staffId = staffId;
        this.customerId = customerId;
        this.serviceType = serviceType;
        this.serviceTime = serviceTime;
        this.serviceDuration = serviceDuration;
        this.bookingAmount = bookingAmount;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getBookingId() { return bookingId; }
    public void setBookingId(String bookingId) { this.bookingId = bookingId; }
    public String getStaffId() { return staffId; }
    public void setStaffId(String staffId) { this.staffId = staffId; }
    public String getCustomerId() { return customerId; }
    public void setCustomerId(String customerId) { this.customerId = customerId; }
    public String getServiceType() { return serviceType; }
    public void setServiceType(String serviceType) { this.serviceType = serviceType; }
    public Instant getServiceTime() { return serviceTime; }
    public void setServiceTime(Instant serviceTime) { this.serviceTime = serviceTime; }
    public Integer getServiceDuration() { return serviceDuration; }
    public void setServiceDuration(Integer serviceDuration) { this.serviceDuration = serviceDuration; }
    public BookingStatus getBookingStatus() { return bookingStatus; }
    public void setBookingStatus(BookingStatus bookingStatus) { this.bookingStatus = bookingStatus; }
    public Double getBookingAmount() { return bookingAmount; }
    public void setBookingAmount(Double bookingAmount) { this.bookingAmount = bookingAmount; }
    public Boolean getIsReviewed() { return isReviewed; }
    public void setIsReviewed(Boolean isReviewed) { this.isReviewed = isReviewed; }
    public Boolean getIsSettled() { return isSettled; }
    public void setIsSettled(Boolean isSettled) { this.isSettled = isSettled; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
