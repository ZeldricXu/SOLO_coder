package com.hotelbooking.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "checkins")
public class CheckIn {
    @Id
    @Column(name = "checkin_id", length = 50)
    private String checkinId;

    @Column(name = "booking_id", length = 50)
    private String bookingId;

    @Column(name = "checkin_time")
    private LocalDateTime checkinTime;

    @Column(name = "checkin_status", length = 20)
    private String checkinStatus;

    @Column(name = "customer_id_type", length = 20)
    private String customerIdType;

    @Column(name = "customer_id_number", length = 50)
    private String customerIdNumber;

    public CheckIn() {}

    public String getCheckinId() { return checkinId; }
    public void setCheckinId(String checkinId) { this.checkinId = checkinId; }
    public String getBookingId() { return bookingId; }
    public void setBookingId(String bookingId) { this.bookingId = bookingId; }
    public LocalDateTime getCheckinTime() { return checkinTime; }
    public void setCheckinTime(LocalDateTime checkinTime) { this.checkinTime = checkinTime; }
    public String getCheckinStatus() { return checkinStatus; }
    public void setCheckinStatus(String checkinStatus) { this.checkinStatus = checkinStatus; }
    public String getCustomerIdType() { return customerIdType; }
    public void setCustomerIdType(String customerIdType) { this.customerIdType = customerIdType; }
    public String getCustomerIdNumber() { return customerIdNumber; }
    public void setCustomerIdNumber(String customerIdNumber) { this.customerIdNumber = customerIdNumber; }
}
