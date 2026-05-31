package com.nftindexer.modules.bridge.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.Map;

@Data
public class MessageVerifyRequest {

    @NotBlank(message = "消息ID不能为空")
    private String messageId;

    @NotBlank(message = "证明数据不能为空")
    private String proof;

    private Map<String, Object> proofData;
}
