package com.datamasker.interfaces.dto.privacy;

import lombok.Data;

@Data
public class AccumulatorStatsResponse {

    private int pendingItems;

    private long totalProcessed;

    private long lastFlushTime;

    private int flushCount;
}
