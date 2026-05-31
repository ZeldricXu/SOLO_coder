package com.nftindexer.modules.zkp.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.Map;

@Data
public class ZkpVerifyRequest {

    @NotBlank(message = "证明数据不能为空")
    private String proofData;

    @NotBlank(message = "公开输入不能为空")
    private String publicInputs;

    private String circuitId;

    private String circuitName;

    private String verificationKey;

    private String submittedBy;

    private Map<String, Object> metadata;
}
