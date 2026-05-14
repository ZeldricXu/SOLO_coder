package com.movie.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

public class TicketCreateRequest {

    @NotBlank(message = "schedule_id不能为空")
    private String scheduleId;

    private String userId;

    private List<String> seatIds;

    public TicketCreateRequest() {
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
}
