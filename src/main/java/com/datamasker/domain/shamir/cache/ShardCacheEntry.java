package com.datamasker.domain.shamir.cache;

import lombok.Data;

import java.math.BigInteger;

@Data
public class ShardCacheEntry {

    private BigInteger shardData;
    private int threshold;
    private String owner;
    private long createdAt;
    private int accessCount;
    private long lastAccessed;
}
