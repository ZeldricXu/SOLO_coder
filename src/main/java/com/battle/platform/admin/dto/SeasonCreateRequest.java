package com.battle.platform.admin.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class SeasonCreateRequest {
    private String seasonCode;
    private String seasonName;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Integer maxPlayersPerServer;
    private Integer bracketSize;
    private String rewardConfigJson;
}
