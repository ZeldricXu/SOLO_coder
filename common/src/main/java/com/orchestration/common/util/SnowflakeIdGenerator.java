package com.orchestration.common.util;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class SnowflakeIdGenerator {

    @Value("${snowflake.worker-id:1}")
    private long workerId;

    @Value("${snowflake.datacenter-id:1}")
    private long datacenterId;

    private volatile cn.hutool.core.lang.Snowflake snowflake;

    public long nextId() {
        if (snowflake == null) {
            synchronized (this) {
                if (snowflake == null) {
                    snowflake = cn.hutool.core.util.IdUtil.getSnowflake(workerId, datacenterId);
                }
            }
        }
        return snowflake.nextId();
    }

    public String nextIdStr() {
        return String.valueOf(nextId());
    }
}
