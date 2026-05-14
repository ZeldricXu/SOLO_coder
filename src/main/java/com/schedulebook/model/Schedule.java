package com.schedulebook.model;

import javax.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "schedules")
public class Schedule {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "schedule_id", unique = true, nullable = false, length = 50)
    private String scheduleId;
    
    @Column(name = "resource_id", nullable = false, length = 50)
    private String resourceId;
    
    @Column(name = "schedule_date", nullable = false)
    private LocalDate scheduleDate;
    
    @Column(name = "max_booking_per_slot", nullable = false)
    private Integer maxBookingPerSlot = 1;
    
    @Column(name = "created_at")
    private LocalDateTime createdAt;
    
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
    
    @OneToMany(mappedBy = "schedule", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ScheduleSlot> slots = new ArrayList<>();
    
    public Schedule() {
    }
    
    // Getters and Setters
    public Long getId() {
        return id;
    }
    
    public void setId(Long id) {
        this.id = id;
    }
    
    public String getScheduleId() {
        return scheduleId;
    }
    
    public void setScheduleId(String scheduleId) {
        this.scheduleId = scheduleId;
    }
    
    public String getResourceId() {
        return resourceId;
    }
    
    public void setResourceId(String resourceId) {
        this.resourceId = resourceId;
    }
    
    public LocalDate getScheduleDate() {
        return scheduleDate;
    }
    
    public void setScheduleDate(LocalDate scheduleDate) {
        this.scheduleDate = scheduleDate;
    }
    
    public Integer getMaxBookingPerSlot() {
        return maxBookingPerSlot;
    }
    
    public void setMaxBookingPerSlot(Integer maxBookingPerSlot) {
        this.maxBookingPerSlot = maxBookingPerSlot;
    }
    
    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
    
    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
    
    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
    
    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
    
    public List<ScheduleSlot> getSlots() {
        return slots;
    }
    
    public void setSlots(List<ScheduleSlot> slots) {
        this.slots = slots;
    }
    
    public void addSlot(ScheduleSlot slot) {
        slots.add(slot);
        slot.setSchedule(this);
    }
    
    public void removeSlot(ScheduleSlot slot) {
        slots.remove(slot);
        slot.setSchedule(null);
    }
}
