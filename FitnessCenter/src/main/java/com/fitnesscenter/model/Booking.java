package com.fitnesscenter.model;

import javax.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "bookings")
public class Booking {

    @Id
    @Column(name = "booking_id")
    private String bookingId;

    @Column(name = "member_id", nullable = false)
    private String memberId;

    @Column(name = "course_id", nullable = false)
    private String courseId;

    @Column(name = "coach_id")
    private String coachId;

    @Column(name = "booking_status")
    private String bookingStatus;

    @Column(name = "booking_time")
    private Instant bookingTime;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    public Booking() {}

    public String getBookingId() {
        return bookingId;
    }

    public void setBookingId(String bookingId) {
        this.bookingId = bookingId;
    }

    public String getMemberId() {
        return memberId;
    }

    public void setMemberId(String memberId) {
        this.memberId = memberId;
    }

    public String getCourseId() {
        return courseId;
    }

    public void setCourseId(String courseId) {
        this.courseId = courseId;
    }

    public String getCoachId() {
        return coachId;
    }

    public void setCoachId(String coachId) {
        this.coachId = coachId;
    }

    public String getBookingStatus() {
        return bookingStatus;
    }

    public void setBookingStatus(String bookingStatus) {
        this.bookingStatus = bookingStatus;
    }

    public Instant getBookingTime() {
        return bookingTime;
    }

    public void setBookingTime(Instant bookingTime) {
        this.bookingTime = bookingTime;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
    }

    public void setConfirmedAt(Instant confirmedAt) {
        this.confirmedAt = confirmedAt;
    }
}
