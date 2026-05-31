package com.didauth.module.zkp.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
public class ZkpVerifyRequest implements Serializable {

    @NotBlank(message = "circuitId不能为空")
    private String circuitId;

    @NotBlank(message = "proofData不能为空")
    private String proofData;

    private List<String> publicInputs;

    private String userId;

    private String traceId;
}
