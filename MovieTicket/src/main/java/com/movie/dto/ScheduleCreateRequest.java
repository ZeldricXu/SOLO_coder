package com.movie.dto;

import java.time.LocalDate;
import java.time.LocalTime;
import java.math.BigDecimal;

public class ScheduleCreateRequest {

    private String movieId;
    private String cinemaId;
    private LocalDate scheduleDate;
    private LocalTime scheduleTime;
    private BigDecimal schedulePrice;
    private Integer scheduleSeats;

    public ScheduleCreateRequest() {
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
}
