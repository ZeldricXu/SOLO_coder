package com.iotplatform.gateway.strategy.impl;

import cn.hutool.json.JSONObject;
import com.iotplatform.gateway.dto.ProtocolConvertRequest;
import com.iotplatform.gateway.strategy.ProtocolConverter;
import org.springframework.stereotype.Component;

@Component
public class ModbusToHttpConverter implements ProtocolConverter {

    private static final String SOURCE_PROTOCOL = "Modbus";
    private static final String TARGET_PROTOCOL = "HTTP";

    @Override
    public String convert(ProtocolConvertRequest request) {
        JSONObject result = new JSONObject();
        result.set("protocol", TARGET_PROTOCOL);
        result.set("method", "POST");
        result.set("resource", "/api/modbus/data");
        result.set("body", request.getPayload());
        return result.toString();
    }

    @Override
    public String getSourceProtocol() {
        return SOURCE_PROTOCOL;
    }

    @Override
    public String getTargetProtocol() {
        return TARGET_PROTOCOL;
    }
}
