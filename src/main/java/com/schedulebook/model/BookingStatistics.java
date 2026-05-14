package com.schedulebook.model;

import javax.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "booking_statistics")
public class BookingStatistics {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "stat_id", unique = true, nullable = false, length = 50)
    private String statId;
    
    @Column(name = "stat_date", nullable = false, unique = true)
    private LocalDate statDate;
    
    @Column(name = "total_bookings")
    private Integer totalBookings = 0;
    
    @Column(name = "confirmed_bookings")
    private Integer confirmedBookings = 0;
    
    @Column(name = "cancelled_bookings")
    private Integer cancelledBookings = 0;
    
    @Column(name = "resource_utilization")
    private Integer resourceUtilization = 0;
    
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
    
    public BookingStatistics() {
    }
    
    // Getters and Setters
    public Long getId() {
        return id;
    }
    
    public void setId(Long id) {
        this.id = id;
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
    
    public Integer getTotalBookings() {
        return totalBookings;
    }
    
    public void setTotalBookings(Integer totalBookings) {
        this.totalBookings = totalBookings;
    }
    
    public Integer getConfirmedBookings() {
        return confirmedBookings;
    }
    
    public void setConfirmedBookings(Integer confirmedBookings) {
        this.confirmedBookings = confirmedBookings;
    }
    
    public Integer getCancelledBookings() {
        return cancelledBookings;
    }
    
    public void setCancelledBookings(Integer cancelledBookings) {
        this.cancelledBookings = cancelledBookings;
    }
    
    public Integer getResourceUtilization() {
        return resourceUtilization;
    }
    
    public void setResourceUtilization(Integer resourceUtilization) {
        this.resourceUtilization = resourceUtilization;
    }
    
    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
    
    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
