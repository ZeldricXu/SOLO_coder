package com.device.platform.dto;

import jakarta.validation.constraints.*;
import lombok.Data;
import java.util.Map;

@Data
public class DeviceActivateRequest {
    @NotBlank(message = "deviceId不能为空")
    @Size(min = 1, max = 128, message = "deviceId长度必须在1-128字符之间")
    @Pattern(regexp = "^[a-zA-Z0-9_-]+$", message = "deviceId只能包含字母、数字、下划线和中划线")
    private String deviceId;

    @NotBlank(message = "productKey不能为空")
    @Size(min = 1, max = 64, message = "productKey长度必须在1-64字符之间")
    @Pattern(regexp = "^[a-zA-Z0-9_-]+$", message = "productKey只能包含字母、数字、下划线和中划线")
    private String productKey;

    @NotBlank(message = "deviceSecret不能为空")
    @Size(min = 8, max = 256, message = "deviceSecret长度必须在8-256字符之间")
    private String deviceSecret;

    @Size(max = 128, message = "deviceName长度不能超过128字符")
    private String deviceName;

    @Size(max = 64, message = "deviceType长度不能超过64字符")
    @Pattern(regexp = "^[a-zA-Z0-9_-]*$", message = "deviceType只能包含字母、数字、下划线和中划线")
    private String deviceType;

    @Size(max = 32, message = "firmwareVersion长度不能超过32字符")
    @Pattern(regexp = "^[a-zA-Z0-9.+-]*$", message = "firmwareVersion格式不正确")
    private String firmwareVersion;

    @Size(max = 32, message = "hardwareVersion长度不能超过32字符")
    @Pattern(regexp = "^[a-zA-Z0-9.+-]*$", message = "hardwareVersion格式不正确")
    private String hardwareVersion;

    @Size(max = 45, message = "ipAddress长度不能超过45字符")
    private String ipAddress;

    @Size(max = 64, message = "region长度不能超过64字符")
    private String region;

    @Size(max = 100, message = "attributes最多包含100个键值对")
    private Map<String, Object> attributes;

    @Size(max = 50, message = "tags最多包含50个键值对")
    private Map<String, String> tags;
}
