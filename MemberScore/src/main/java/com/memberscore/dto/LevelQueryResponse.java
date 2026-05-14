package com.memberscore.dto;

import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.Builder;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class LevelQueryResponse {
    
    private String level;
    private String levelName;
    private Integer totalPoints;
    private Integer availablePoints;
    private Integer pointsToNextLevel;
}
