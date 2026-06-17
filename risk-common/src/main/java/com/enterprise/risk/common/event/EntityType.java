package com.enterprise.risk.common.event;

public enum EntityType {
    USER("user"),
    ACCOUNT("account"),
    IP("ip"),
    DEVICE("device"),
    ORDER("order"),
    SESSION("session");

    private final String code;

    EntityType(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public static EntityType fromCode(String code) {
        for (EntityType type : values()) {
            if (type.code.equalsIgnoreCase(code)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown entity type: " + code);
    }
}
