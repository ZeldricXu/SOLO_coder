package com.monitoring.common.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResourceStatusResponse {

    private String id;

    private String status;

    private Double progress;
}
