package com.meeting.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RoomSearchResponse {
    private String roomId;
    private String roomName;
    private Integer roomCapacity;
    private String roomLocation;
    private String roomStatus;
    private List<String> roomFeatures;
    private Boolean available;
}
