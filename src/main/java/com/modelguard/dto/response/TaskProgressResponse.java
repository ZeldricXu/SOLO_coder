package com.modelguard.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskProgressResponse {

    private String taskId;
    private String status;
    private String phase;
    private BigDecimal progress;
    private Integer totalChunks;
    private Integer processedChunks;
    private Long elapsedMs;
    private Long estimatedRemainingMs;
}
