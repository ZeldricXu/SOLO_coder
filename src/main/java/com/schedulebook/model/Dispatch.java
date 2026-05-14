package com.schedulebook.model;

import javax.persistence.*;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Entity
@Table(name = "dispatches")
public class Dispatch {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "dispatch_id", unique = true, nullable = false, length = 50)
    private String dispatchId;
    
    @Column(name = "booking_id", nullable = false, length = 50)
    private String bookingId;
    
    @Column(name = "resource_id", nullable = false, length = 50)
    private String resourceId;
    
    @Column(name = "dispatch_time", nullable = false)
    private LocalTime dispatchTime;
    
    @Column(name = "dispatch_status", nullable = false, length = 50)
    private String dispatchStatus;
    
    @Column(name = "dispatched_at")
    private LocalDateTime dispatchedAt;
    
    @Column(name = "released_at")
    private LocalDateTime releasedAt;
    
    public Dispatch() {
    }
    
    // Getters and Setters
    public Long getId() {
        return id;
    }
    
    public void setId(Long id) {
        this.id = id;
    }
    
    public String getDispatchId() {
        return dispatchId;
    }
    
    public void setDispatchId(String dispatchId) {
        this.dispatchId = dispatchId;
    }
    
    public String getBookingId() {
        return bookingId;
    }
    
    public void setBookingId(String bookingId) {
        this.bookingId = bookingId;
    }
    
    public String getResourceId() {
        return resourceId;
    }
    
    public void setResourceId(String resourceId) {
        this.resourceId = resourceId;
    }
    
    public LocalTime getDispatchTime() {
        return dispatchTime;
    }
    
    public void setDispatchTime(LocalTime dispatchTime) {
        this.dispatchTime = dispatchTime;
    }
    
    public String getDispatchStatus() {
        return dispatchStatus;
    }
    
    public void setDispatchStatus(String dispatchStatus) {
        this.dispatchStatus = dispatchStatus;
    }
    
    public LocalDateTime getDispatchedAt() {
        return dispatchedAt;
    }
    
    public void setDispatchedAt(LocalDateTime dispatchedAt) {
        this.dispatchedAt = dispatchedAt;
    }
    
    public LocalDateTime getReleasedAt() {
        return releasedAt;
    }
    
    public void setReleasedAt(LocalDateTime releasedAt) {
        this.releasedAt = releasedAt;
    }
}
