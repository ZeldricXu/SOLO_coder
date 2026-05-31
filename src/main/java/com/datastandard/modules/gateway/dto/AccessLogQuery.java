package com.datastandard.modules.gateway.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AccessLogQuery {

    private String requestId;

    private String clientIp;

    private String userId;

    private String path;

    private String method;

    private Integer statusCode;

    private Instant startTime;

    private Instant endTime;

    @Builder.Default
    private int page = 1;

    @Builder.Default
    private int size = 20;
}
