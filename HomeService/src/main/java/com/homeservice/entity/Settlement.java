package com.homeservice.entity;

import com.homeservice.enums.SettlementStatus;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "settlements")
public class Settlement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "settlement_id", unique = true, nullable = false)
    private String settlementId;

    @Column(name = "booking_id", nullable = false)
    private String bookingId;

    @Column(name = "staff_id", nullable = false)
    private String staffId;

    @Column(name = "service_amount", nullable = false)
    private Double serviceAmount;

    @Column(name = "platform_fee", nullable = false)
    private Double platformFee;

    @Column(name = "staff_amount", nullable = false)
    private Double staffAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "settlement_status", nullable = false)
    private SettlementStatus settlementStatus = SettlementStatus.PENDING;

    @Column(name = "settlement_time")
    private Instant settlementTime;

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

    public Settlement() {}

    public Settlement(String settlementId, String bookingId, String staffId, Double serviceAmount,
                      Double platformFee, Double staffAmount) {
        this.settlementId = settlementId;
        this.bookingId = bookingId;
        this.staffId = staffId;
        this.serviceAmount = serviceAmount;
        this.platformFee = platformFee;
        this.staffAmount = staffAmount;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getSettlementId() { return settlementId; }
    public void setSettlementId(String settlementId) { this.settlementId = settlementId; }
    public String getBookingId() { return bookingId; }
    public void setBookingId(String bookingId) { this.bookingId = bookingId; }
    public String getStaffId() { return staffId; }
    public void setStaffId(String staffId) { this.staffId = staffId; }
    public Double getServiceAmount() { return serviceAmount; }
    public void setServiceAmount(Double serviceAmount) { this.serviceAmount = serviceAmount; }
    public Double getPlatformFee() { return platformFee; }
    public void setPlatformFee(Double platformFee) { this.platformFee = platformFee; }
    public Double getStaffAmount() { return staffAmount; }
    public void setStaffAmount(Double staffAmount) { this.staffAmount = staffAmount; }
    public SettlementStatus getSettlementStatus() { return settlementStatus; }
    public void setSettlementStatus(SettlementStatus settlementStatus) { this.settlementStatus = settlementStatus; }
    public Instant getSettlementTime() { return settlementTime; }
    public void setSettlementTime(Instant settlementTime) { this.settlementTime = settlementTime; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
