package com.chaoslab.modules.traffic.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

@Data
public class BlueGreenDeployRequest {

    @NotBlank(message = "策略ID不能为空")
    private String strategyId;

    @NotBlank(message = "蓝环境版本不能为空")
    private String blueVersion;

    @NotBlank(message = "绿环境版本不能为空")
    private String greenVersion;

    private List<String> testHeaders;

    private Boolean autoSwitch = false;
}
