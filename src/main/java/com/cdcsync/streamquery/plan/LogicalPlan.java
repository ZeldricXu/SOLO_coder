package com.cdcsync.streamquery.plan;

import lombok.Data;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Data
public abstract class LogicalPlan implements Serializable {

    private static final long serialVersionUID = 1L;

    protected volatile String planType;

    protected List<LogicalPlan> children = new CopyOnWriteArrayList<>();

    protected Map<String, Object> properties = new ConcurrentHashMap<>();

    public LogicalPlan(String planType) {
        this.planType = planType;
    }

    public void addChild(LogicalPlan child) {
        this.children.add(child);
    }

    public void setProperty(String key, Object value) {
        if (key != null && value != null) {
            this.properties.put(key, value);
        }
    }

    public Object getProperty(String key) {
        return this.properties.get(key);
    }

    public List<LogicalPlan> getChildren() {
        return Collections.unmodifiableList(children);
    }

    public Map<String, Object> getProperties() {
        return Collections.unmodifiableMap(properties);
    }
}
