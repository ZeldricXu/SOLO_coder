package com.observability.anomaly.algorithm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class RateOfChangeDetector implements AnomalyDetector {

    @Override
    public String getName() {
        return "rate_of_change";
    }

    @Override
    public AnomalyResult detect(List<Double> historicalData, double currentValue, Map<String, Object> params) {
        if (historicalData == null || historicalData.size() < 2) {
            return AnomalyResult.normal(getName(), currentValue, currentValue);
        }

        double maxChangePercent = params != null && params.containsKey("maxChangePercent") ?
                ((Number) params.get("maxChangePercent")).doubleValue() : 50.0;

        double previousValue = historicalData.get(historicalData.size() - 1);
        double changePercent = previousValue != 0 ?
                Math.abs((currentValue - previousValue) / previousValue * 100) : 0;

        double baseline = previousValue;
        double threshold = maxChangePercent;
        double deviation = changePercent;

        if (changePercent > maxChangePercent) {
            String severity = changePercent > maxChangePercent * 3 ? "critical" :
                    changePercent > maxChangePercent * 2 ? "warning" : "info";
            return AnomalyResult.anomaly(getName(), currentValue, baseline, threshold, deviation, severity);
        }

        return AnomalyResult.normal(getName(), currentValue, baseline);
    }
}
