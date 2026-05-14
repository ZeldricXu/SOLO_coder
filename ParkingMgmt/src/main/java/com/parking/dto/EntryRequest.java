package com.parking.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class EntryRequest {
    private String vehicleNumber;
    private String parkingId;
    private String vehicleType;
    private String vehicleOwner;
    private String vehiclePhone;
}
