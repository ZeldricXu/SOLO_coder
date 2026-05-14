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
public class RoomSearchRequest {
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Integer minCapacity;
    private List<String> requiredFeatures;
    private String location;
    private String roomStatus;
}
