package com.homeservice.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "service_history")
public class ServiceHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "history_id", unique = true, nullable = false)
    private String historyId;

    @Column(name = "history_type", nullable = false)
    private String historyType;

    @Column(name = "booking_id")
    private String bookingId;

    @Column(name = "staff_id")
    private String staffId;

    @Column(name = "customer_id")
    private String customerId;

    @Column(name = "action", nullable = false)
    private String action;

    @Column(name = "description", length = 1000)
    private String description;

    @Column(name = "occurred_at")
    private Instant occurredAt;

    @Column(name = "created_at")
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        if (occurredAt == null) {
            occurredAt = Instant.now();
        }
    }

    public ServiceHistory() {}

    public ServiceHistory(String historyId, String historyType, String action) {
        this.historyId = historyId;
        this.historyType = historyType;
        this.action = action;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getHistoryId() { return historyId; }
    public void setHistoryId(String historyId) { this.historyId = historyId; }
    public String getHistoryType() { return historyType; }
    public void setHistoryType(String historyType) { this.historyType = historyType; }
    public String getBookingId() { return bookingId; }
    public void setBookingId(String bookingId) { this.bookingId = bookingId; }
    public String getStaffId() { return staffId; }
    public void setStaffId(String staffId) { this.staffId = staffId; }
    public String getCustomerId() { return customerId; }
    public void setCustomerId(String customerId) { this.customerId = customerId; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Instant getOccurredAt() { return occurredAt; }
    public void setOccurredAt(Instant occurredAt) { this.occurredAt = occurredAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
