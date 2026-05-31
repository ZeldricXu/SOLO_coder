package com.iotplatform.gateway.strategy.impl;

import cn.hutool.json.JSONObject;
import com.iotplatform.gateway.dto.ProtocolConvertRequest;
import com.iotplatform.gateway.strategy.ProtocolConverter;
import org.springframework.stereotype.Component;

@Component
public class HttpToCoapConverter implements ProtocolConverter {

    private static final String SOURCE_PROTOCOL = "HTTP";
    private static final String TARGET_PROTOCOL = "CoAP";

    @Override
    public String convert(ProtocolConvertRequest request) {
        JSONObject result = new JSONObject();
        result.set("protocol", TARGET_PROTOCOL);
        result.set("code", "2.05");
        result.set("payload", request.getPayload());
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
