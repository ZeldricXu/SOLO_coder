package com.datastandard.modules.slo.calculator;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class SliCalculatorRegistry {

    private final Map<String, SliTypeCalculator> calculatorMap = new HashMap<>();

    public SliCalculatorRegistry(List<SliTypeCalculator> calculators) {
        for (SliTypeCalculator calculator : calculators) {
            calculatorMap.put(calculator.getType(), calculator);
        }
    }

    public SliTypeCalculator getCalculator(String sliType) {
        String normalizedType = normalizeType(sliType);
        SliTypeCalculator calculator = calculatorMap.get(normalizedType);
        if (calculator == null) {
            throw new IllegalArgumentException("不支持的SLI类型: " + sliType);
        }
        return calculator;
    }

    private String normalizeType(String sliType) {
        String upper = sliType.toUpperCase();
        if (upper.equals("DELAY")) {
            return "LATENCY";
        }
        if (upper.equals("ERRORRATE")) {
            return "ERROR_RATE";
        }
        return upper;
    }
}
