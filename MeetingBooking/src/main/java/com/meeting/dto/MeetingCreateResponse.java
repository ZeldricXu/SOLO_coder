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
public class MeetingCreateResponse {
    private String meetingId;
    private String roomId;
    private String roomName;
    private String meetingTopic;
    private String meetingType;
    private LocalDateTime meetingStart;
    private LocalDateTime meetingEnd;
    private String meetingStatus;
    private String organizerId;
    private String scheduleId;
}
