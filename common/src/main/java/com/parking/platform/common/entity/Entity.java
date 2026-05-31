package com.parking.platform.common.entity;

import java.util.HashMap;
import java.util.Map;

public class Entity extends BaseEntity {

    private String type;
    private String status;
    private Map<String, Object> attributes;

    public Entity() {
        super();
        this.attributes = new HashMap<>();
    }

    @Override
    protected String getIdPrefix() {
        return "ent";
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Map<String, Object> getAttributes() {
        return attributes;
    }

    public void setAttributes(Map<String, Object> attributes) {
        this.attributes = attributes;
    }

    public Object getAttribute(String key) {
        return attributes.get(key);
    }

    public void setAttribute(String key, Object value) {
        this.attributes.put(key, value);
    }

    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> map = super.toMap();
        map.put("type", type);
        map.put("status", status);
        map.put("attributes", attributes);
        return map;
    }
}
