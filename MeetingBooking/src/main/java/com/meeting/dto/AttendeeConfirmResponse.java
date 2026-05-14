package com.meeting.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AttendeeConfirmResponse {
    private String attendeeId;
    private String meetingId;
    private String userId;
    private String userName;
    private String attendeeStatus;
    private LocalDateTime attendeeTime;
}
