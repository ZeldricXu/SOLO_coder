package com.movie.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "seats", indexes = {
    @Index(name = "idx_schedule_seat", columnList = "schedule_id, seat_row, seat_column", unique = true)
})
public class Seat {

    @Id
    @Column(name = "seat_id", length = 50)
    private String seatId;

    @Column(name = "schedule_id", nullable = false, length = 50)
    private String scheduleId;

    @Column(name = "seat_row")
    private Integer seatRow;

    @Column(name = "seat_column")
    private Integer seatColumn;

    @Column(name = "seat_number", length = 20)
    private String seatNumber;

    @Column(name = "seat_type", length = 20)
    private String seatType;

    @Column(name = "seat_status", length = 20)
    private String seatStatus;

    @Column(name = "seat_price", precision = 10, scale = 2)
    private BigDecimal seatPrice;

    @Column(name = "lock_user_id", length = 50)
    private String lockUserId;

    @Column(name = "lock_time")
    private LocalDateTime lockTime;

    @Column(name = "ticket_id", length = 50)
    private String ticketId;

    public Seat() {
    }

    public Seat(String seatId, String scheduleId, Integer seatRow, Integer seatColumn,
                String seatNumber, String seatType, String seatStatus, BigDecimal seatPrice) {
        this.seatId = seatId;
        this.scheduleId = scheduleId;
        this.seatRow = seatRow;
        this.seatColumn = seatColumn;
        this.seatNumber = seatNumber;
        this.seatType = seatType;
        this.seatStatus = seatStatus;
        this.seatPrice = seatPrice;
    }

    public String getSeatId() {
        return seatId;
    }

    public void setSeatId(String seatId) {
        this.seatId = seatId;
    }

    public String getScheduleId() {
        return scheduleId;
    }

    public void setScheduleId(String scheduleId) {
        this.scheduleId = scheduleId;
    }

    public Integer getSeatRow() {
        return seatRow;
    }

    public void setSeatRow(Integer seatRow) {
        this.seatRow = seatRow;
    }

    public Integer getSeatColumn() {
        return seatColumn;
    }

    public void setSeatColumn(Integer seatColumn) {
        this.seatColumn = seatColumn;
    }

    public String getSeatNumber() {
        return seatNumber;
    }

    public void setSeatNumber(String seatNumber) {
        this.seatNumber = seatNumber;
    }

    public String getSeatType() {
        return seatType;
    }

    public void setSeatType(String seatType) {
        this.seatType = seatType;
    }

    public String getSeatStatus() {
        return seatStatus;
    }

    public void setSeatStatus(String seatStatus) {
        this.seatStatus = seatStatus;
    }

    public BigDecimal getSeatPrice() {
        return seatPrice;
    }

    public void setSeatPrice(BigDecimal seatPrice) {
        this.seatPrice = seatPrice;
    }

    public String getLockUserId() {
        return lockUserId;
    }

    public void setLockUserId(String lockUserId) {
        this.lockUserId = lockUserId;
    }

    public LocalDateTime getLockTime() {
        return lockTime;
    }

    public void setLockTime(LocalDateTime lockTime) {
        this.lockTime = lockTime;
    }

    public String getTicketId() {
        return ticketId;
    }

    public void setTicketId(String ticketId) {
        this.ticketId = ticketId;
    }
}
