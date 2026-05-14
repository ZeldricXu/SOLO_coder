package com.parking.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ExitResponse {
    private String exitId;
    private double fee;
    private int parkingDuration;
    private String settlementId;
    private String entryTime;
    private String exitTime;
}
