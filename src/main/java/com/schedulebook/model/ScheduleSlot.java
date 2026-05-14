package com.schedulebook.model;

import javax.persistence.*;
import java.time.LocalTime;

@Entity
@Table(name = "schedule_slots")
public class ScheduleSlot {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "schedule_id")
    private Schedule schedule;
    
    @Column(name = "slot_time", nullable = false)
    private LocalTime slotTime;
    
    @Column(name = "slot_status", nullable = false, length = 50)
    private String slotStatus = "available";
    
    @Column(name = "current_bookings")
    private Integer currentBookings = 0;
    
    @Column(name = "booking_id", length = 50)
    private String bookingId;
    
    public ScheduleSlot() {
    }
    
    // Getters and Setters
    public Long getId() {
        return id;
    }
    
    public void setId(Long id) {
        this.id = id;
    }
    
    public Schedule getSchedule() {
        return schedule;
    }
    
    public void setSchedule(Schedule schedule) {
        this.schedule = schedule;
    }
    
    public LocalTime getSlotTime() {
        return slotTime;
    }
    
    public void setSlotTime(LocalTime slotTime) {
        this.slotTime = slotTime;
    }
    
    public String getSlotStatus() {
        return slotStatus;
    }
    
    public void setSlotStatus(String slotStatus) {
        this.slotStatus = slotStatus;
    }
    
    public Integer getCurrentBookings() {
        return currentBookings;
    }
    
    public void setCurrentBookings(Integer currentBookings) {
        this.currentBookings = currentBookings;
    }
    
    public String getBookingId() {
        return bookingId;
    }
    
    public void setBookingId(String bookingId) {
        this.bookingId = bookingId;
    }
}
