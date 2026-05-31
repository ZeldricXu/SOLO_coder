package com.datamasker.interfaces.dto.shamir;

import lombok.Data;

@Data
public class CacheStatsResponse {

    private double hitRate;
    private long hitCount;
    private long missCount;
    private int l1EntryCount;
    private int l2EntryCount;
    private long warmupDuration;
}
