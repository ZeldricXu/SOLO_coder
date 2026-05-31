package com.iotplatform.gateway.dto;

import lombok.Data;
import java.util.Map;

@Data
public class ProtocolConvertRequest {

    private String sourceProtocol;
    private String targetProtocol;
    private String payload;
    private Map<String, String> headers;
    private Map<String, Object> options;
}
