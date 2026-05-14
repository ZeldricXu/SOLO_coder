package com.parking.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class EntryResponse {
    private String entryId;
    private String spaceNumber;
    private String spaceId;
    private String vehicleNumber;
    private String entryTime;
    private String vehicleType;
    private Integer lockTimeoutSeconds;
    private String spaceType;
}
