package com.solocoder.platform.gas.estimator.adapter.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class GasEstimationRequestDto {

    @NotBlank(message = "chainId不能为空")
    private String chainId;

    private String network;

    private String txType;

    private Long gasLimit;

    private String contractAddress;

    private String methodId;

    @NotNull(message = "时间戳不能为空")
    private Long timestamp;

    @NotBlank(message = "签名不能为空")
    private String signature;
}
