package com.parking.platform.feature.entity;

import lombok.Data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
public class UserGroup {
    private String id;
    private String name;
    private String description;
    private List<GroupRule> rules = new ArrayList<>();

    public boolean matches(Map<String, String> userAttributes) {
        if (rules.isEmpty()) {
            return false;
        }
        for (GroupRule rule : rules) {
            if (!rule.evaluate(userAttributes)) {
                return false;
            }
        }
        return true;
    }

    @Data
    public static class GroupRule {
        private String attribute;
        private Operator operator;
        private String value;

        public enum Operator {
            EQUALS,
            NOT_EQUALS,
            CONTAINS,
            IN,
            GREATER_THAN,
            LESS_THAN
        }

        public boolean evaluate(Map<String, String> userAttributes) {
            String actual = userAttributes.get(attribute);
            if (actual == null && operator != Operator.NOT_EQUALS) {
                return false;
            }
            switch (operator) {
                case EQUALS:
                    return value.equals(actual);
                case NOT_EQUALS:
                    return !value.equals(actual);
                case CONTAINS:
                    return actual != null && actual.contains(value);
                case IN:
                    String[] values = value.split(",");
                    for (String v : values) {
                        if (v.trim().equals(actual)) {
                            return true;
                        }
                    }
                    return false;
                case GREATER_THAN:
                    try {
                        return actual != null && Double.parseDouble(actual) > Double.parseDouble(value);
                    } catch (NumberFormatException e) {
                        return false;
                    }
                case LESS_THAN:
                    try {
                        return actual != null && Double.parseDouble(actual) < Double.parseDouble(value);
                    } catch (NumberFormatException e) {
                        return false;
                    }
                default:
                    return false;
            }
        }
    }
}
