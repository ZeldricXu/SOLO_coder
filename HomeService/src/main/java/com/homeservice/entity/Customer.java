package com.homeservice.entity;

import com.homeservice.enums.CustomerLevel;
import com.homeservice.enums.CustomerStatus;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "customers")
public class Customer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", unique = true, nullable = false)
    private String customerId;

    @Column(name = "customer_name", nullable = false)
    private String customerName;

    @Column(name = "customer_phone", nullable = false)
    private String customerPhone;

    @Column(name = "customer_address")
    private String customerAddress;

    @Column(name = "customer_region")
    private String customerRegion;

    @Enumerated(EnumType.STRING)
    @Column(name = "customer_status", nullable = false)
    private CustomerStatus customerStatus = CustomerStatus.ACTIVE;

    @Enumerated(EnumType.STRING)
    @Column(name = "customer_level")
    private CustomerLevel customerLevel = CustomerLevel.NEW;

    @Column(name = "total_bookings")
    private Integer totalBookings = 0;

    @Column(name = "total_spent")
    private Double totalSpent = 0.0;

    @Column(name = "registered_at")
    private Instant registeredAt;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        updatedAt = Instant.now();
        if (registeredAt == null) {
            registeredAt = Instant.now();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    public Customer() {}

    public Customer(String customerId, String customerName, String customerPhone, 
                    String customerAddress, String customerRegion) {
        this.customerId = customerId;
        this.customerName = customerName;
        this.customerPhone = customerPhone;
        this.customerAddress = customerAddress;
        this.customerRegion = customerRegion;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCustomerId() { return customerId; }
    public void setCustomerId(String customerId) { this.customerId = customerId; }
    public String getCustomerName() { return customerName; }
    public void setCustomerName(String customerName) { this.customerName = customerName; }
    public String getCustomerPhone() { return customerPhone; }
    public void setCustomerPhone(String customerPhone) { this.customerPhone = customerPhone; }
    public String getCustomerAddress() { return customerAddress; }
    public void setCustomerAddress(String customerAddress) { this.customerAddress = customerAddress; }
    public String getCustomerRegion() { return customerRegion; }
    public void setCustomerRegion(String customerRegion) { this.customerRegion = customerRegion; }
    public CustomerStatus getCustomerStatus() { return customerStatus; }
    public void setCustomerStatus(CustomerStatus customerStatus) { this.customerStatus = customerStatus; }
    public CustomerLevel getCustomerLevel() { return customerLevel; }
    public void setCustomerLevel(CustomerLevel customerLevel) { this.customerLevel = customerLevel; }
    public Integer getTotalBookings() { return totalBookings; }
    public void setTotalBookings(Integer totalBookings) { this.totalBookings = totalBookings; }
    public Double getTotalSpent() { return totalSpent; }
    public void setTotalSpent(Double totalSpent) { this.totalSpent = totalSpent; }
    public Instant getRegisteredAt() { return registeredAt; }
    public void setRegisteredAt(Instant registeredAt) { this.registeredAt = registeredAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
