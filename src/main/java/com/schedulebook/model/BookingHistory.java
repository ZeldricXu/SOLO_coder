package com.schedulebook.model;

import javax.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Entity
@Table(name = "booking_history")
public class BookingHistory {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "history_id", unique = true, nullable = false, length = 50)
    private String historyId;
    
    @Column(name = "booking_id", nullable = false, length = 50)
    private String bookingId;
    
    @Column(name = "user_id", nullable = false, length = 50)
    private String userId;
    
    @Column(name = "resource_type", nullable = false, length = 50)
    private String resourceType;
    
    @Column(name = "resource_id", length = 50)
    private String resourceId;
    
    @Column(name = "booking_date", nullable = false)
    private LocalDate bookingDate;
    
    @Column(name = "booking_time", nullable = false)
    private LocalTime bookingTime;
    
    @Column(name = "final_status", nullable = false, length = 50)
    private String finalStatus;
    
    @Column(name = "action_type", nullable = false, length = 50)
    private String actionType;
    
    @Column(name = "action_time", nullable = false)
    private LocalDateTime actionTime;
    
    @Column(name = "action_detail", length = 255)
    private String actionDetail;
    
    public BookingHistory() {
    }
    
    // Getters and Setters
    public Long getId() {
        return id;
    }
    
    public void setId(Long id) {
        this.id = id;
    }
    
    public String getHistoryId() {
        return historyId;
    }
    
    public void setHistoryId(String historyId) {
        this.historyId = historyId;
    }
    
    public String getBookingId() {
        return bookingId;
    }
    
    public void setBookingId(String bookingId) {
        this.bookingId = bookingId;
    }
    
    public String getUserId() {
        return userId;
    }
    
    public void setUserId(String userId) {
        this.userId = userId;
    }
    
    public String getResourceType() {
        return resourceType;
    }
    
    public void setResourceType(String resourceType) {
        this.resourceType = resourceType;
    }
    
    public String getResourceId() {
        return resourceId;
    }
    
    public void setResourceId(String resourceId) {
        this.resourceId = resourceId;
    }
    
    public LocalDate getBookingDate() {
        return bookingDate;
    }
    
    public void setBookingDate(LocalDate bookingDate) {
        this.bookingDate = bookingDate;
    }
    
    public LocalTime getBookingTime() {
        return bookingTime;
    }
    
    public void setBookingTime(LocalTime bookingTime) {
        this.bookingTime = bookingTime;
    }
    
    public String getFinalStatus() {
        return finalStatus;
    }
    
    public void setFinalStatus(String finalStatus) {
        this.finalStatus = finalStatus;
    }
    
    public String getActionType() {
        return actionType;
    }
    
    public void setActionType(String actionType) {
        this.actionType = actionType;
    }
    
    public LocalDateTime getActionTime() {
        return actionTime;
    }
    
    public void setActionTime(LocalDateTime actionTime) {
        this.actionTime = actionTime;
    }
    
    public String getActionDetail() {
        return actionDetail;
    }
    
    public void setActionDetail(String actionDetail) {
        this.actionDetail = actionDetail;
    }
}
