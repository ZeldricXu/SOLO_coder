package com.iotconnect.enums;

public enum AlertOperator {
    GREATER_THAN("greater_than"),
    LESS_THAN("less_than"),
    GREATER_THAN_OR_EQUAL("greater_than_or_equal"),
    LESS_THAN_OR_EQUAL("less_than_or_equal"),
    EQUAL("equal"),
    NOT_EQUAL("not_equal");

    private final String value;

    AlertOperator(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static AlertOperator fromValue(String value) {
        for (AlertOperator operator : values()) {
            if (operator.value.equalsIgnoreCase(value)) {
                return operator;
            }
        }
        return GREATER_THAN;
    }

    public boolean evaluate(Double actualValue, Double threshold) {
        if (actualValue == null || threshold == null) {
            return false;
        }
        switch (this) {
            case GREATER_THAN:
                return actualValue > threshold;
            case LESS_THAN:
                return actualValue < threshold;
            case GREATER_THAN_OR_EQUAL:
                return actualValue >= threshold;
            case LESS_THAN_OR_EQUAL:
                return actualValue <= threshold;
            case EQUAL:
                return actualValue.equals(threshold);
            case NOT_EQUAL:
                return !actualValue.equals(threshold);
            default:
                return false;
        }
    }
}
