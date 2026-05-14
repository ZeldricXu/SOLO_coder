package com.meeting.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.LocalDateTime;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MeetingListRequest {
    private String roomId;
    private String organizerId;
    private String userId;
    private String meetingStatus;
    private String meetingType;
    private LocalDateTime startTimeFrom;
    private LocalDateTime startTimeTo;
    private List<String> statusList;
    private Integer page;
    private Integer size;
}
