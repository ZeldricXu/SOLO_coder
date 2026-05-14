package com.houserental.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class HouseSearchDTO {
    private String keyword;
    private String houseType;
    private String houseStatus;
    private Double minRent;
    private Double maxRent;
    private Double minArea;
    private Double maxArea;
    private String landlordId;
}
