package com.meeting.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StatsResponse {
    private String statMonth;
    private Integer meetingCount;
    private Long totalDurationMinutes;
    private Integer attendeeCount;
    private Integer confirmedAttendeeCount;
    private Integer reminderSentCount;
    private Integer cancelledCount;
    private Double averageAttendeesPerMeeting;
    private Map<String, Long> roomUsage;
    private Map<String, Integer> meetingTypeDistribution;
}
