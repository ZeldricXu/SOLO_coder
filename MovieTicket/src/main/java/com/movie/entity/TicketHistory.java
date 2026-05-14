package com.movie.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "ticket_history", indexes = {
    @Index(name = "idx_history_user", columnList = "user_id"),
    @Index(name = "idx_history_time", columnList = "created_at")
})
public class TicketHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ticket_id", length = 50)
    private String ticketId;

    @Column(name = "user_id", length = 50)
    private String userId;

    @Column(name = "movie_id", length = 50)
    private String movieId;

    @Column(name = "movie_name", length = 200)
    private String movieName;

    @Column(name = "cinema_id", length = 50)
    private String cinemaId;

    @Column(name = "cinema_name", length = 200)
    private String cinemaName;

    @Column(name = "schedule_id", length = 50)
    private String scheduleId;

    @Column(name = "action", length = 50)
    private String action;

    @Column(name = "old_status", length = 20)
    private String oldStatus;

    @Column(name = "new_status", length = 20)
    private String newStatus;

    @Column(name = "ticket_amount", precision = 10, scale = 2)
    private BigDecimal ticketAmount;

    @Column(name = "seat_ids_json", length = 2000)
    private String seatIdsJson;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "remark", length = 500)
    private String remark;

    public TicketHistory() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTicketId() {
        return ticketId;
    }

    public void setTicketId(String ticketId) {
        this.ticketId = ticketId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getMovieId() {
        return movieId;
    }

    public void setMovieId(String movieId) {
        this.movieId = movieId;
    }

    public String getMovieName() {
        return movieName;
    }

    public void setMovieName(String movieName) {
        this.movieName = movieName;
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

    public String getScheduleId() {
        return scheduleId;
    }

    public void setScheduleId(String scheduleId) {
        this.scheduleId = scheduleId;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getOldStatus() {
        return oldStatus;
    }

    public void setOldStatus(String oldStatus) {
        this.oldStatus = oldStatus;
    }

    public String getNewStatus() {
        return newStatus;
    }

    public void setNewStatus(String newStatus) {
        this.newStatus = newStatus;
    }

    public BigDecimal getTicketAmount() {
        return ticketAmount;
    }

    public void setTicketAmount(BigDecimal ticketAmount) {
        this.ticketAmount = ticketAmount;
    }

    public String getSeatIdsJson() {
        return seatIdsJson;
    }

    public void setSeatIdsJson(String seatIdsJson) {
        this.seatIdsJson = seatIdsJson;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}
