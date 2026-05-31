package com.iotplatform.gateway.enums;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

public enum ProtocolType {

    HTTP("HTTP", true),
    HTTPS("HTTPS", true),
    MQTT("MQTT", true),
    COAP("CoAP", true),
    MODBUS("Modbus", true),
    OPC_UA("OPC-UA", true),
    WEBSOCKET("WebSocket", true),
    GRPC("gRPC", true);

    private final String displayName;
    private final boolean supported;

    ProtocolType(String displayName, boolean supported) {
        this.displayName = displayName;
        this.supported = supported;
    }

    public String getDisplayName() {
        return displayName;
    }

    public boolean isSupported() {
        return supported;
    }

    public static ProtocolType fromString(String value) {
        if (value == null) {
            return null;
        }
        String upperValue = value.toUpperCase().replace("-", "_");
        for (ProtocolType type : values()) {
            if (type.name().equals(upperValue) || type.displayName.equalsIgnoreCase(value)) {
                return type;
            }
        }
        return null;
    }

    public static boolean isSupported(String protocol) {
        ProtocolType type = fromString(protocol);
        return type != null && type.isSupported();
    }

    public static Set<String> getSupportedProtocols() {
        return Arrays.stream(values())
                .filter(ProtocolType::isSupported)
                .map(ProtocolType::getDisplayName)
                .collect(Collectors.toSet());
    }
}
