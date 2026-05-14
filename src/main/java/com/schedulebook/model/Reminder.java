package com.schedulebook.model;

import javax.persistence.*;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Entity
@Table(name = "reminders")
public class Reminder {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "reminder_id", unique = true, nullable = false, length = 50)
    private String reminderId;
    
    @Column(name = "booking_id", nullable = false, length = 50)
    private String bookingId;
    
    @Column(name = "reminder_type", nullable = false, length = 50)
    private String reminderType = "before_time";
    
    @Column(name = "reminder_time", nullable = false)
    private LocalTime reminderTime;
    
    @Column(name = "reminder_channel", nullable = false, length = 50)
    private String reminderChannel = "sms";
    
    @Column(name = "reminder_status", nullable = false, length = 50)
    private String reminderStatus = "pending";
    
    @Column(name = "created_at")
    private LocalDateTime createdAt;
    
    @Column(name = "sent_at")
    private LocalDateTime sentAt;
    
    public Reminder() {
    }
    
    // Getters and Setters
    public Long getId() {
        return id;
    }
    
    public void setId(Long id) {
        this.id = id;
    }
    
    public String getReminderId() {
        return reminderId;
    }
    
    public void setReminderId(String reminderId) {
        this.reminderId = reminderId;
    }
    
    public String getBookingId() {
        return bookingId;
    }
    
    public void setBookingId(String bookingId) {
        this.bookingId = bookingId;
    }
    
    public String getReminderType() {
        return reminderType;
    }
    
    public void setReminderType(String reminderType) {
        this.reminderType = reminderType;
    }
    
    public LocalTime getReminderTime() {
        return reminderTime;
    }
    
    public void setReminderTime(LocalTime reminderTime) {
        this.reminderTime = reminderTime;
    }
    
    public String getReminderChannel() {
        return reminderChannel;
    }
    
    public void setReminderChannel(String reminderChannel) {
        this.reminderChannel = reminderChannel;
    }
    
    public String getReminderStatus() {
        return reminderStatus;
    }
    
    public void setReminderStatus(String reminderStatus) {
        this.reminderStatus = reminderStatus;
    }
    
    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
    
    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
    
    public LocalDateTime getSentAt() {
        return sentAt;
    }
    
    public void setSentAt(LocalDateTime sentAt) {
        this.sentAt = sentAt;
    }
}
