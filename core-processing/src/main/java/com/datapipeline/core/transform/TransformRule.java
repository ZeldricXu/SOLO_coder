package com.datapipeline.core.transform;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransformRule {

    public enum RuleType {
        RENAME,
        REMOVE,
        ADD,
        NORMALIZE,
        MAP,
        FILTER
    }

    private RuleType type;
    @Builder.Default
    private Map<String, Object> params = Collections.emptyMap();

    @SuppressWarnings("unchecked")
    public <T> T getParam(String key, Class<T> type, T defaultValue) {
        Object value = params.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (type.isInstance(value)) {
            return type.cast(value);
        }
        return defaultValue;
    }

    @SuppressWarnings("unchecked")
    public <T> List<T> getParamList(String key, Class<T> elementType) {
        Object value = params.get(key);
        if (value instanceof List<?> list) {
            List<T> result = new ArrayList<>();
            for (Object item : list) {
                if (elementType.isInstance(item)) {
                    result.add(elementType.cast(item));
                }
            }
            return result;
        }
        return Collections.emptyList();
    }

}
