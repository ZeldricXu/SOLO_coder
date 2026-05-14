package com.movie.dto;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public class TicketCreateResponse implements Serializable {

    private String ticketId;
    private String scheduleId;
    private String userId;
    private List<String> seatIds;
    private BigDecimal ticketAmount;
    private String ticketStatus;
    private LocalDateTime ticketTime;

    public TicketCreateResponse() {
    }

    public TicketCreateResponse(String ticketId, String status) {
        this.ticketId = ticketId;
        this.ticketStatus = status;
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
}
