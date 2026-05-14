package com.movie.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "cinemas")
public class Cinema {

    @Id
    @Column(name = "cinema_id", length = 50)
    private String cinemaId;

    @Column(name = "cinema_name", nullable = false, length = 200)
    private String cinemaName;

    @Column(name = "cinema_address", length = 500)
    private String cinemaAddress;

    @Column(name = "cinema_region", length = 100)
    private String cinemaRegion;

    @Column(name = "cinema_status", length = 20)
    private String cinemaStatus;

    @Column(name = "cinema_rating")
    private Double cinemaRating;

    @Column(name = "seat_total")
    private Integer seatTotal;

    @Column(name = "schedule_count")
    private Integer scheduleCount = 0;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public Cinema() {
    }

    public Cinema(String cinemaId, String cinemaName, String cinemaAddress, String cinemaRegion,
                  String cinemaStatus, Double cinemaRating, Integer seatTotal, LocalDateTime createdAt) {
        this.cinemaId = cinemaId;
        this.cinemaName = cinemaName;
        this.cinemaAddress = cinemaAddress;
        this.cinemaRegion = cinemaRegion;
        this.cinemaStatus = cinemaStatus;
        this.cinemaRating = cinemaRating;
        this.seatTotal = seatTotal;
        this.createdAt = createdAt;
    }

    public String getCinemaId() {
        return cinemaId;
    }

    public void setCinemaId(String cinemaId) {
        this.cinemaId = cinemaId;
    }

    public String getCinemaName() {
        return cinemaName;
    }

    public void setCinemaName(String cinemaName) {
        this.cinemaName = cinemaName;
    }

    public String getCinemaAddress() {
        return cinemaAddress;
    }

    public void setCinemaAddress(String cinemaAddress) {
        this.cinemaAddress = cinemaAddress;
    }

    public String getCinemaRegion() {
        return cinemaRegion;
    }

    public void setCinemaRegion(String cinemaRegion) {
        this.cinemaRegion = cinemaRegion;
    }

    public String getCinemaStatus() {
        return cinemaStatus;
    }

    public void setCinemaStatus(String cinemaStatus) {
        this.cinemaStatus = cinemaStatus;
    }

    public Double getCinemaRating() {
        return cinemaRating;
    }

    public void setCinemaRating(Double cinemaRating) {
        this.cinemaRating = cinemaRating;
    }

    public Integer getSeatTotal() {
        return seatTotal;
    }

    public void setSeatTotal(Integer seatTotal) {
        this.seatTotal = seatTotal;
    }

    public Integer getScheduleCount() {
        return scheduleCount;
    }

    public void setScheduleCount(Integer scheduleCount) {
        this.scheduleCount = scheduleCount;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
