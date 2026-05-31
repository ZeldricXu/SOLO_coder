package com.nftindexer.modules.zkp.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.Map;

@Data
public class ZkpCircuitCreateRequest {

    @NotBlank(message = "电路名称不能为空")
    private String circuitName;

    @NotBlank(message = "电路类型不能为空")
    private String circuitType;

    private String provingKey;

    @NotBlank(message = "验证密钥不能为空")
    private String verificationKey;

    private String compiledCircuit;

    private String sourceCode;

    private Integer version;

    private String createdBy;

    private Map<String, Object> metadata;
}
