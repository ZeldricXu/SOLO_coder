package com.solocoder.platform.featurestore.model;

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
public class FeatureValue implements Serializable {

    private static final long serialVersionUID = 1L;

    private String featureId;
    private String entityId;
    private Object value;
    private long timestamp;
    private Map<String, String> metadata;

    public static FeatureValue of(String featureId, String entityId, Object value) {
        return FeatureValue.builder()
                .featureId(featureId)
                .entityId(entityId)
                .value(value)
                .timestamp(System.currentTimeMillis())
                .build();
    }
}
