package com.datamasker.domain.shamir.cache;

import lombok.Data;

@Data
public class CacheStats {

    private long hitCount;
    private long missCount;
    private double hitRate;
    private int l1Size;
    private int l2Size;
    private long warmupTimeMs;
}
