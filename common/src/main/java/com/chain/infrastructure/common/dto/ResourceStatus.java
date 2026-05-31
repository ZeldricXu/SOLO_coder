package com.chain.infrastructure.common.dto;

import lombok.Data;

@Data
public class ResourceStatus {

    private String id;

    private String status;

    private Double progress;

    private String phase;

    private String errorDetail;
}
