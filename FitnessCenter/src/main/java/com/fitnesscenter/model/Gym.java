package com.fitnesscenter.model;

import javax.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "gyms")
public class Gym {

    @Id
    @Column(name = "gym_id")
    private String gymId;

    @Column(name = "gym_name", nullable = false)
    private String gymName;

    @Column(name = "gym_address")
    private String gymAddress;

    @Column(name = "gym_phone")
    private String gymPhone;

    @Column(name = "gym_status")
    private String gymStatus;

    @Column(name = "opening_hours")
    private String openingHours;

    @Column(name = "created_at")
    private Instant createdAt;

    public Gym() {}

    public String getGymId() {
        return gymId;
    }

    public void setGymId(String gymId) {
        this.gymId = gymId;
    }

    public String getGymName() {
        return gymName;
    }

    public void setGymName(String gymName) {
        this.gymName = gymName;
    }

    public String getGymAddress() {
        return gymAddress;
    }

    public void setGymAddress(String gymAddress) {
        this.gymAddress = gymAddress;
    }

    public String getGymPhone() {
        return gymPhone;
    }

    public void setGymPhone(String gymPhone) {
        this.gymPhone = gymPhone;
    }

    public String getGymStatus() {
        return gymStatus;
    }

    public void setGymStatus(String gymStatus) {
        this.gymStatus = gymStatus;
    }

    public String getOpeningHours() {
        return openingHours;
    }

    public void setOpeningHours(String openingHours) {
        this.openingHours = openingHours;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
