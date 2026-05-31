package com.cdcsync.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResourceStatusResponse {

    private String id;
    private String status;
    private Double progress;
    private String message;
}
