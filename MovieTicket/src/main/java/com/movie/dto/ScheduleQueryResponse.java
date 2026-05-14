package com.movie.dto;

import java.io.Serializable;
import java.math.BigDecimal;

public class ScheduleQueryResponse implements Serializable {

    private String scheduleId;
    private String movieId;
    private String cinemaId;
    private String cinema;
    private String date;
    private String time;
    private BigDecimal price;
    private Integer seats;
    private Integer available;
    private String status;

    public ScheduleQueryResponse() {
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

    public String getCinema() {
        return cinema;
    }

    public void setCinema(String cinema) {
        this.cinema = cinema;
    }

    public String getDate() {
        return date;
    }

    public void setDate(String date) {
        this.date = date;
    }

    public String getTime() {
        return time;
    }

    public void setTime(String time) {
        this.time = time;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public Integer getSeats() {
        return seats;
    }

    public void setSeats(Integer seats) {
        this.seats = seats;
    }

    public Integer getAvailable() {
        return available;
    }

    public void setAvailable(Integer available) {
        this.available = available;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
