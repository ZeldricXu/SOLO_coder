package com.movie.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "tickets")
public class Ticket {

    @Id
    @Column(name = "ticket_id", length = 50)
    private String ticketId;

    @Column(name = "schedule_id", nullable = false, length = 50)
    private String scheduleId;

    @Column(name = "user_id", length = 50)
    private String userId;

    @Transient
    private List<String> seatIds = new ArrayList<>();

    @Column(name = "seat_ids_json", length = 2000)
    private String seatIdsJson;

    @Column(name = "ticket_amount", precision = 10, scale = 2)
    private BigDecimal ticketAmount;

    @Column(name = "ticket_status", length = 20)
    private String ticketStatus;

    @Column(name = "ticket_time")
    private LocalDateTime ticketTime;

    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    @Column(name = "movie_name", length = 200)
    private String movieName;

    @Column(name = "cinema_name", length = 200)
    private String cinemaName;

    @Column(name = "schedule_date")
    private java.time.LocalDate scheduleDate;

    @Column(name = "schedule_time")
    private java.time.LocalTime scheduleTime;

    public Ticket() {
    }

    public Ticket(String ticketId, String scheduleId, String userId, List<String> seatIds,
                  BigDecimal ticketAmount, String ticketStatus, LocalDateTime ticketTime) {
        this.ticketId = ticketId;
        this.scheduleId = scheduleId;
        this.userId = userId;
        this.seatIds = seatIds;
        this.ticketAmount = ticketAmount;
        this.ticketStatus = ticketStatus;
        this.ticketTime = ticketTime;
    }

    public String getTicketId() {
        return ticketId;
    }

    public void setTicketId(String ticketId) {
        this.ticketId = ticketId;
    }

    public String getScheduleId() {
        return scheduleId;
    }

    public void setScheduleId(String scheduleId) {
        this.scheduleId = scheduleId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public List<String> getSeatIds() {
        return seatIds;
    }

    public void setSeatIds(List<String> seatIds) {
        this.seatIds = seatIds;
    }

    public String getSeatIdsJson() {
        return seatIdsJson;
    }

    public void setSeatIdsJson(String seatIdsJson) {
        this.seatIdsJson = seatIdsJson;
    }

    public BigDecimal getTicketAmount() {
        return ticketAmount;
    }

    public void setTicketAmount(BigDecimal ticketAmount) {
        this.ticketAmount = ticketAmount;
    }

    public String getTicketStatus() {
        return ticketStatus;
    }

    public void setTicketStatus(String ticketStatus) {
        this.ticketStatus = ticketStatus;
    }

    public LocalDateTime getTicketTime() {
        return ticketTime;
    }

    public void setTicketTime(LocalDateTime ticketTime) {
        this.ticketTime = ticketTime;
    }

    public LocalDateTime getConfirmedAt() {
        return confirmedAt;
    }

    public void setConfirmedAt(LocalDateTime confirmedAt) {
        this.confirmedAt = confirmedAt;
    }

    public String getMovieName() {
        return movieName;
    }

    public void setMovieName(String movieName) {
        this.movieName = movieName;
    }

    public String getCinemaName() {
        return cinemaName;
    }

    public void setCinemaName(String cinemaName) {
        this.cinemaName = cinemaName;
    }

    public java.time.LocalDate getScheduleDate() {
        return scheduleDate;
    }

    public void setScheduleDate(java.time.LocalDate scheduleDate) {
        this.scheduleDate = scheduleDate;
    }

    public java.time.LocalTime getScheduleTime() {
        return scheduleTime;
    }

    public void setScheduleTime(java.time.LocalTime scheduleTime) {
        this.scheduleTime = scheduleTime;
    }
}
