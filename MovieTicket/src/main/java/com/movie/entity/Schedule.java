package com.movie.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

@Entity
@Table(name = "schedules")
public class Schedule {

    @Id
    @Column(name = "schedule_id", length = 50)
    private String scheduleId;

    @Column(name = "movie_id", nullable = false, length = 50)
    private String movieId;

    @Column(name = "cinema_id", nullable = false, length = 50)
    private String cinemaId;

    @Column(name = "schedule_date")
    private LocalDate scheduleDate;

    @Column(name = "schedule_time")
    private LocalTime scheduleTime;

    @Column(name = "schedule_price", precision = 10, scale = 2)
    private BigDecimal schedulePrice;

    @Column(name = "schedule_seats")
    private Integer scheduleSeats;

    @Column(name = "schedule_available")
    private Integer scheduleAvailable;

    @Column(name = "schedule_status", length = 20)
    private String scheduleStatus;

    @Column(name = "created_at")
    private java.time.LocalDateTime createdAt;

    public Schedule() {
    }

    public Schedule(String scheduleId, String movieId, String cinemaId, LocalDate scheduleDate,
                    LocalTime scheduleTime, BigDecimal schedulePrice, Integer scheduleSeats,
                    Integer scheduleAvailable, String scheduleStatus, java.time.LocalDateTime createdAt) {
        this.scheduleId = scheduleId;
        this.movieId = movieId;
        this.cinemaId = cinemaId;
        this.scheduleDate = scheduleDate;
        this.scheduleTime = scheduleTime;
        this.schedulePrice = schedulePrice;
        this.scheduleSeats = scheduleSeats;
        this.scheduleAvailable = scheduleAvailable;
        this.scheduleStatus = scheduleStatus;
        this.createdAt = createdAt;
    }

    public String getScheduleId() {
        return scheduleId;
    }

    public void setScheduleId(String scheduleId) {
        this.scheduleId = scheduleId;
    }

    public String getMovieId() {
        return movieId;
    }

    public void setMovieId(String movieId) {
        this.movieId = movieId;
    }

    public String getCinemaId() {
        return cinemaId;
    }

    public void setCinemaId(String cinemaId) {
        this.cinemaId = cinemaId;
    }

    public LocalDate getScheduleDate() {
        return scheduleDate;
    }

    public void setScheduleDate(LocalDate scheduleDate) {
        this.scheduleDate = scheduleDate;
    }

    public LocalTime getScheduleTime() {
        return scheduleTime;
    }

    public void setScheduleTime(LocalTime scheduleTime) {
        this.scheduleTime = scheduleTime;
    }

    public BigDecimal getSchedulePrice() {
        return schedulePrice;
    }

    public void setSchedulePrice(BigDecimal schedulePrice) {
        this.schedulePrice = schedulePrice;
    }

    public Integer getScheduleSeats() {
        return scheduleSeats;
    }

    public void setScheduleSeats(Integer scheduleSeats) {
        this.scheduleSeats = scheduleSeats;
    }

    public Integer getScheduleAvailable() {
        return scheduleAvailable;
    }

    public void setScheduleAvailable(Integer scheduleAvailable) {
        this.scheduleAvailable = scheduleAvailable;
    }

    public String getScheduleStatus() {
        return scheduleStatus;
    }

    public void setScheduleStatus(String scheduleStatus) {
        this.scheduleStatus = scheduleStatus;
    }

    public java.time.LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(java.time.LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
