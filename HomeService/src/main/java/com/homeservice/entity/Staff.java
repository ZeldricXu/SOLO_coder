package com.homeservice.entity;

import com.homeservice.enums.StaffStatus;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "staffs")
public class Staff {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "staff_id", unique = true, nullable = false)
    private String staffId;

    @Column(name = "staff_name", nullable = false)
    private String staffName;

    @Column(name = "staff_type", nullable = false)
    private String staffType;

    @Column(name = "staff_phone", nullable = false)
    private String staffPhone;

    @Column(name = "staff_rating")
    private Double staffRating = 0.0;

    @Column(name = "staff_region", nullable = false)
    private String staffRegion;

    @Enumerated(EnumType.STRING)
    @Column(name = "staff_status", nullable = false)
    private StaffStatus staffStatus = StaffStatus.AVAILABLE;

    @Column(name = "staff_price", nullable = false)
    private Double staffPrice;

    @Column(name = "total_bookings")
    private Integer totalBookings = 0;

    @Column(name = "total_reviews")
    private Integer totalReviews = 0;

    @Column(name = "total_income")
    private Double totalIncome = 0.0;

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

    public Staff() {}

    public Staff(String staffId, String staffName, String staffType, String staffPhone, 
                 String staffRegion, Double staffPrice) {
        this.staffId = staffId;
        this.staffName = staffName;
        this.staffType = staffType;
        this.staffPhone = staffPhone;
        this.staffRegion = staffRegion;
        this.staffPrice = staffPrice;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getStaffId() { return staffId; }
    public void setStaffId(String staffId) { this.staffId = staffId; }
    public String getStaffName() { return staffName; }
    public void setStaffName(String staffName) { this.staffName = staffName; }
    public String getStaffType() { return staffType; }
    public void setStaffType(String staffType) { this.staffType = staffType; }
    public String getStaffPhone() { return staffPhone; }
    public void setStaffPhone(String staffPhone) { this.staffPhone = staffPhone; }
    public Double getStaffRating() { return staffRating; }
    public void setStaffRating(Double staffRating) { this.staffRating = staffRating; }
    public String getStaffRegion() { return staffRegion; }
    public void setStaffRegion(String staffRegion) { this.staffRegion = staffRegion; }
    public StaffStatus getStaffStatus() { return staffStatus; }
    public void setStaffStatus(StaffStatus staffStatus) { this.staffStatus = staffStatus; }
    public Double getStaffPrice() { return staffPrice; }
    public void setStaffPrice(Double staffPrice) { this.staffPrice = staffPrice; }
    public Integer getTotalBookings() { return totalBookings; }
    public void setTotalBookings(Integer totalBookings) { this.totalBookings = totalBookings; }
    public Integer getTotalReviews() { return totalReviews; }
    public void setTotalReviews(Integer totalReviews) { this.totalReviews = totalReviews; }
    public Double getTotalIncome() { return totalIncome; }
    public void setTotalIncome(Double totalIncome) { this.totalIncome = totalIncome; }
    public Instant getRegisteredAt() { return registeredAt; }
    public void setRegisteredAt(Instant registeredAt) { this.registeredAt = registeredAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
