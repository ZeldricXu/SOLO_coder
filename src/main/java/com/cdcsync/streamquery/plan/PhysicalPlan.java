package com.cdcsync.streamquery.plan;

import lombok.Data;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

@Data
public abstract class PhysicalPlan implements Serializable {

    private static final long serialVersionUID = 1L;

    protected String operatorType;

    protected double cost;

    protected Map<String, Object> partitionInfo = new HashMap<>();

    public PhysicalPlan(String operatorType) {
        this.operatorType = operatorType;
    }

    public void setPartitionInfo(String key, Object value) {
        this.partitionInfo.put(key, value);
    }

    public Object getPartitionInfo(String key) {
        return this.partitionInfo.get(key);
    }
}
