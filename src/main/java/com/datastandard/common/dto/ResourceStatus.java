package com.datastandard.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResourceStatus {

    private String id;
    private String status;
    private BigDecimal progress;
    private String message;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Long durationMs;
}
