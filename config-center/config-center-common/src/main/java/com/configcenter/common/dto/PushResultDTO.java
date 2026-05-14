package com.configcenter.common.dto;

import com.configcenter.common.enums.PushStatus;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class PushResultDTO {
    
    private String pushId;
    private String configId;
    private String version;
    private String targetGroup;
    private PushStatus pushStatus;
    private LocalDateTime pushTime;
    private Integer successCount;
    private Integer failCount;
    private Integer totalCount;
}
