package com.datastandard.modules.logging.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LogQueryRequest {

    private String keyword;

    private String level;

    private String loggerName;

    private String threadName;

    private Instant startTime;

    private Instant endTime;

    private List<String> mdcKeys;

    private int page;

    private int pageSize;
}
