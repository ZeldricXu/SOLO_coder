package com.fitnesscenter.model;

import javax.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "coaches")
public class Coach {

    @Id
    @Column(name = "coach_id")
    private String coachId;

    @Column(name = "coach_name", nullable = false)
    private String coachName;

    @Column(name = "coach_type")
    private String coachType;

    @Column(name = "coach_rating")
    private Double coachRating;

    @Column(name = "coach_status")
    private String coachStatus;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "gym_id")
    private String gymId;

    @Column(name = "booking_count")
    private Integer bookingCount = 0;

    public Coach() {}

    public String getCoachId() {
        return coachId;
    }

    public void setCoachId(String coachId) {
        this.coachId = coachId;
    }

    public String getCoachName() {
        return coachName;
    }

    public void setCoachName(String coachName) {
        this.coachName = coachName;
    }

    public String getCoachType() {
        return coachType;
    }

    public void setCoachType(String coachType) {
        this.coachType = coachType;
    }

    public Double getCoachRating() {
        return coachRating;
    }

    public void setCoachRating(Double coachRating) {
        this.coachRating = coachRating;
    }

    public String getCoachStatus() {
        return coachStatus;
    }

    public void setCoachStatus(String coachStatus) {
        this.coachStatus = coachStatus;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public String getGymId() {
        return gymId;
    }

    public void setGymId(String gymId) {
        this.gymId = gymId;
    }

    public Integer getBookingCount() {
        return bookingCount;
    }

    public void setBookingCount(Integer bookingCount) {
        this.bookingCount = bookingCount;
    }
}
