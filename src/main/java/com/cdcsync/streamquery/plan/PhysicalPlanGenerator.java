package com.cdcsync.streamquery.plan;

import com.alibaba.fastjson2.JSON;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class PhysicalPlanGenerator {

    public static PhysicalPlan generate(LogicalPlan logicalPlan) {
        return translatePlan(logicalPlan);
    }

    private static PhysicalPlan translatePlan(LogicalPlan logicalPlan) {
        return switch (logicalPlan.getPlanType()) {
            case "SCAN" -> translateScan(logicalPlan);
            case "FILTER" -> translateFilter(logicalPlan);
            case "PROJECT" -> translateProject(logicalPlan);
            case "SORT" -> translateSort(logicalPlan);
            default -> translateGeneric(logicalPlan);
        };
    }

    private static PhysicalPlan translateScan(LogicalPlan logicalPlan) {
        PhysicalPlan physicalPlan = new PhysicalPlan("TABLE_SCAN") {};
        physicalPlan.setCost(100.0);
        physicalPlan.setPartitionInfo("table", logicalPlan.getProperty("table"));
        if (logicalPlan.getProperty("filter") != null) {
            physicalPlan.setPartitionInfo("pushdownFilter", logicalPlan.getProperty("filter"));
        }
        if (logicalPlan.getProperty("requiredColumns") != null) {
            physicalPlan.setPartitionInfo("columns", logicalPlan.getProperty("requiredColumns"));
        }
        return physicalPlan;
    }

    private static PhysicalPlan translateFilter(LogicalPlan logicalPlan) {
        PhysicalPlan physicalPlan = new PhysicalPlan("FILTER") {};
        physicalPlan.setCost(50.0);
        physicalPlan.setPartitionInfo("condition", logicalPlan.getProperty("condition"));
        return physicalPlan;
    }

    private static PhysicalPlan translateProject(LogicalPlan logicalPlan) {
        PhysicalPlan physicalPlan = new PhysicalPlan("PROJECT") {};
        physicalPlan.setCost(30.0);
        physicalPlan.setPartitionInfo("projections", logicalPlan.getProperty("selectItems"));
        return physicalPlan;
    }

    private static PhysicalPlan translateSort(LogicalPlan logicalPlan) {
        PhysicalPlan physicalPlan = new PhysicalPlan("SORT") {};
        physicalPlan.setCost(200.0);
        physicalPlan.setPartitionInfo("orderSpec", logicalPlan.getProperty("orderElements"));
        physicalPlan.setPartitionInfo("algorithm", "externalSort");
        return physicalPlan;
    }

    private static PhysicalPlan translateGeneric(LogicalPlan logicalPlan) {
        PhysicalPlan physicalPlan = new PhysicalPlan(logicalPlan.getPlanType()) {};
        physicalPlan.setCost(10.0);
        physicalPlan.setPartitionInfo("properties", logicalPlan.getProperties());
        return physicalPlan;
    }

    public static String toJson(PhysicalPlan plan) {
        return JSON.toJSONString(plan);
    }

    public static PhysicalPlan fromJson(String json) {
        return JSON.parseObject(json, PhysicalPlan.class);
    }

    public static Map<String, Object> explain(PhysicalPlan plan) {
        return Map.of(
                "operatorType", plan.getOperatorType(),
                "cost", plan.getCost(),
                "partitionInfo", plan.getPartitionInfo()
        );
    }

    public static double calculateTotalCost(List<PhysicalPlan> plans) {
        return plans.stream().mapToDouble(PhysicalPlan::getCost).sum();
    }
}
