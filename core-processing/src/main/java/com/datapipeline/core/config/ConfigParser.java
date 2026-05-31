package com.datapipeline.core.config;

import com.datapipeline.common.model.ConfigDefinition;
import com.datapipeline.core.transform.TransformRule;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

@Slf4j
public class ConfigParser {

    private static final String RULES_KEY = "rules";
    private static final String POOL_SIZE_KEY = "poolSize";
    private static final String ACQUIRE_TIMEOUT_KEY = "acquireTimeoutMs";
    private static final String TIMEOUT_KEY = "timeout";
    private static final String RETRIES_KEY = "retries";

    public ProcessConfig parse(ConfigDefinition config) {
        if (config == null) {
            return ProcessConfig.defaults();
        }
        Map<String, Object> params = config.getParameters();
        if (params == null || params.isEmpty()) {
            return ProcessConfig.defaults();
        }

        return ProcessConfig.builder()
                .poolSize(getInt(params, POOL_SIZE_KEY, ProcessConfig.DEFAULT_POOL_SIZE))
                .acquireTimeoutMs(getLong(params, ACQUIRE_TIMEOUT_KEY, ProcessConfig.DEFAULT_ACQUIRE_TIMEOUT_MS))
                .timeoutSeconds(getInt(params, TIMEOUT_KEY, ProcessConfig.DEFAULT_TIMEOUT_SECONDS))
                .maxRetries(getInt(params, RETRIES_KEY, ProcessConfig.DEFAULT_MAX_RETRIES))
                .transformRules(parseRules(params))
                .build();
    }

    private int getInt(Map<String, Object> params, String key, int defaultValue) {
        Object value = params.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return defaultValue;
    }

    private long getLong(Map<String, Object> params, String key, long defaultValue) {
        Object value = params.get(key);
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return defaultValue;
    }

    @SuppressWarnings("unchecked")
    private List<TransformRule> parseRules(Map<String, Object> params) {
        Object rulesObj = params.get(RULES_KEY);
        if (!(rulesObj instanceof List<?>)) {
            return List.of();
        }

        List<?> list = (List<?>) rulesObj;
        if (list.isEmpty()) {
            return List.of();
        }

        List<TransformRule> rules = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof Map<?, ?>)) {
                continue;
            }
            Map<?, ?> map = (Map<?, ?>) item;
            Object typeObj = map.get("type");
            if (!(typeObj instanceof String)) {
                continue;
            }
            try {
                TransformRule.RuleType ruleType = TransformRule.RuleType.valueOf((String) typeObj);
                Map<String, Object> ruleParams = (Map<String, Object>) map.getOrDefault("params", Collections.emptyMap());
                rules.add(TransformRule.builder()
                        .type(ruleType)
                        .params(ruleParams != null ? new HashMap<>(ruleParams) : new HashMap<>())
                        .build());
            } catch (Exception e) {
                log.warn("Invalid transform rule: {}", item, e);
            }
        }
        return rules.isEmpty() ? List.of() : rules;
    }

}
