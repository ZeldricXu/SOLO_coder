package com.solocoder.platform.core.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DataRecord implements Serializable {

    private static final long serialVersionUID = 1L;

    private String id;
    private Map<String, Object> fields;
    private String source;
    private String schema;
    private long timestamp;

    public Object getField(String name) {
        return fields != null ? fields.get(name) : null;
    }

    public DataRecord withField(String name, Object value) {
        this.fields.put(name, value);
        return this;
    }
}
