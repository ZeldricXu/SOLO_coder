package com.iotplatform.protocol.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import java.util.Map;

@Data
public class ProtocolDataDTO {

    @NotBlank(message = "设备ID不能为空")
    private String deviceId;

    @NotBlank(message = "协议类型不能为空")
    private String protocolType;

    private String payload;

    private byte[] binaryPayload;

    private Map<String, Object> headers;

    private Map<String, Object> config;

    private Long timestamp;
}
