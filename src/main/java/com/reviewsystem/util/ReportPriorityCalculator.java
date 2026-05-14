package com.reviewsystem.util;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
public class ReportPriorityCalculator {

    private static final Map<String, Integer> TYPE_PRIORITY_MAP = new HashMap<>();

    static {
        TYPE_PRIORITY_MAP.put("politics", 100);
        TYPE_PRIORITY_MAP.put("violence", 90);
        TYPE_PRIORITY_MAP.put("porn", 85);
        TYPE_PRIORITY_MAP.put("drug", 85);
        TYPE_PRIORITY_MAP.put("fraud", 80);
        TYPE_PRIORITY_MAP.put("spam", 60);
        TYPE_PRIORITY_MAP.put("advertisement", 50);
        TYPE_PRIORITY_MAP.put("insult", 40);
        TYPE_PRIORITY_MAP.put("other", 20);
    }

    public int calculatePriority(String reportType, int reportCount) {
        int basePriority = TYPE_PRIORITY_MAP.getOrDefault(reportType, 20);
        int countBonus = Math.min(reportCount * 5, 30);
        return Math.min(100, basePriority + countBonus);
    }

    public int getPriority(String reportType) {
        return TYPE_PRIORITY_MAP.getOrDefault(reportType, 20);
    }

    public Map<String, Integer> getPriorityMap() {
        return new HashMap<>(TYPE_PRIORITY_MAP);
    }
}
