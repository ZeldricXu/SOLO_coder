package com.datapipeline.dp.injector;

import com.datapipeline.dp.budget.PrivacyBudgetManager;
import com.datapipeline.dp.noise.NoiseGenerator;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

@Slf4j
public class PrivacyInjector {

    private final NoiseGenerator noiseGenerator;
    private final PrivacyBudgetManager budgetManager;
    private final Map<String, FieldConfig> fieldConfigs = new HashMap<>();

    public PrivacyInjector(NoiseGenerator noiseGenerator, PrivacyBudgetManager budgetManager) {
        this.noiseGenerator = noiseGenerator;
        this.budgetManager = budgetManager;
    }

    public void registerFieldConfig(String fieldPath, FieldConfig config) {
        fieldConfigs.put(fieldPath, config);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> injectNoise(Map<String, Object> data, String budgetAccountId) {
        Map<String, Object> result = new LinkedHashMap<>(data);

        for (Map.Entry<String, FieldConfig> entry : fieldConfigs.entrySet()) {
            String fieldPath = entry.getKey();
            FieldConfig config = entry.getValue();

            if (!budgetManager.consumeBudget(budgetAccountId, config.getEpsilon(), config.getDelta())) {
                log.warn("Skipping noise injection for field {}: insufficient budget", fieldPath);
                continue;
            }

            Object value = resolveValue(result, fieldPath);
            if (value == null) {
                continue;
            }

            Object noisyValue = applyNoise(value, config);
            setValue(result, fieldPath, noisyValue);
        }

        return result;
    }

    public List<Map<String, Object>> injectNoiseToList(List<Map<String, Object>> dataList, String budgetAccountId) {
        if (dataList.isEmpty()) {
            return dataList;
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> data : dataList) {
            result.add(injectNoise(data, budgetAccountId));
        }
        return result;
    }

    public Object injectSingleValue(Object value, double epsilon, double delta,
                                    double sensitivity, NoiseGenerator.Type noiseType) {
        if (value instanceof Number num) {
            double doubleValue = num.doubleValue();
            if (noiseType == NoiseGenerator.Type.LAPLACE) {
                return noiseGenerator.addLaplaceNoise(doubleValue, epsilon, sensitivity);
            } else {
                return noiseGenerator.addGaussianNoise(doubleValue, epsilon, delta, sensitivity);
            }
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private Object resolveValue(Map<String, Object> data, String path) {
        String[] parts = path.split("\\.");
        Object current = data;
        for (String part : parts) {
            if (current instanceof Map<?, ?> map) {
                current = map.get(part);
            } else {
                return null;
            }
        }
        return current;
    }

    @SuppressWarnings("unchecked")
    private void setValue(Map<String, Object> data, String path, Object value) {
        String[] parts = path.split("\\.");
        Map<String, Object> current = data;
        for (int i = 0; i < parts.length - 1; i++) {
            Object next = current.get(parts[i]);
            if (!(next instanceof Map)) {
                Map<String, Object> newMap = new LinkedHashMap<>();
                current.put(parts[i], newMap);
                current = newMap;
            } else {
                current = (Map<String, Object>) next;
            }
        }
        current.put(parts[parts.length - 1], value);
    }

    private Object applyNoise(Object value, FieldConfig config) {
        if (value instanceof Integer i) {
            if (config.getNoiseType() == NoiseGenerator.Type.LAPLACE) {
                return noiseGenerator.addLaplaceNoise(i, config.getEpsilon(), config.getSensitivity());
            } else {
                return noiseGenerator.addGaussianNoise(i, config.getEpsilon(), config.getDelta(), config.getSensitivity());
            }
        } else if (value instanceof Long l) {
            if (config.getNoiseType() == NoiseGenerator.Type.LAPLACE) {
                return noiseGenerator.addLaplaceNoise(l, config.getEpsilon(), config.getSensitivity());
            } else {
                return noiseGenerator.addGaussianNoise(l, config.getEpsilon(), config.getDelta(), config.getSensitivity());
            }
        } else if (value instanceof Number num) {
            double d = num.doubleValue();
            if (config.getNoiseType() == NoiseGenerator.Type.LAPLACE) {
                return noiseGenerator.addLaplaceNoise(d, config.getEpsilon(), config.getSensitivity());
            } else {
                return noiseGenerator.addGaussianNoise(d, config.getEpsilon(), config.getDelta(), config.getSensitivity());
            }
        }
        return value;
    }

}
