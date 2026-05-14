package com.memberscore.dto;

import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.Builder;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class PointOperationResponse {
    
    private String pointId;
    private Integer balance;
    private Integer earnedPoints;
    private Integer consumedPoints;
}
