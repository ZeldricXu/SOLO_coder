package com.nftindexer.modules.common.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ResourceStatusResponse {

    private String id;
    private String type;
    private String status;
    private BigDecimal progress;
    private String phase;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private String errorDetail;
    private Map<String, Object> metadata;
}
