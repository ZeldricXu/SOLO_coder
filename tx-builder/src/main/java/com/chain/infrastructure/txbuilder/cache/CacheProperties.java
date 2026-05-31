package com.chain.infrastructure.txbuilder.cache;

import lombok.Value;
import java.time.Duration;

@Value
public class CacheProperties {
    String name;
    Duration ttl;
    long maxSize;
    boolean enableRedis;
    Duration redisTtl;
}
