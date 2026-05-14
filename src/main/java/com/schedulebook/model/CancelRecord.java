package com.schedulebook.model;

import javax.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "cancel_records")
public class CancelRecord {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "cancel_id", unique = true, nullable = false, length = 50)
    private String cancelId;
    
    @Column(name = "booking_id", nullable = false, length = 50)
    private String bookingId;
    
    @Column(name = "cancel_reason", nullable = false, length = 255)
    private String cancelReason;
    
    @Column(name = "cancel_time", nullable = false)
    private LocalDateTime cancelTime;
    
    @Column(name = "cancel_by", nullable = false, length = 50)
    private String cancelBy;
    
    public CancelRecord() {
    }
    
    // Getters and Setters
    public Long getId() {
        return id;
    }
    
    public void setId(Long id) {
        this.id = id;
    }
    
    public String getCancelId() {
        return cancelId;
    }
    
    public void setCancelId(String cancelId) {
        this.cancelId = cancelId;
    }
    
    public String getBookingId() {
        return bookingId;
    }
    
    public void setBookingId(String bookingId) {
        this.bookingId = bookingId;
    }
    
    public String getCancelReason() {
        return cancelReason;
    }
    
    public void setCancelReason(String cancelReason) {
        this.cancelReason = cancelReason;
    }
    
    public LocalDateTime getCancelTime() {
        return cancelTime;
    }
    
    public void setCancelTime(LocalDateTime cancelTime) {
        this.cancelTime = cancelTime;
    }
    
    public String getCancelBy() {
        return cancelBy;
    }
    
    public void setCancelBy(String cancelBy) {
        this.cancelBy = cancelBy;
    }
}
