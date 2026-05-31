package com.nftindexer.modules.event.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.Map;

@Data
public class EventListenerCreateRequest {

    @NotBlank(message = "链ID不能为空")
    private String chainId;

    @NotBlank(message = "合约地址不能为空")
    private String contractAddress;

    @NotBlank(message = "事件名称不能为空")
    private String eventName;

    private String eventSignature;

    private String abi;

    @NotBlank(message = "回调URL不能为空")
    private String callbackUrl;

    private String callbackType;

    private String[] filterTopics;

    private Integer fromBlock;

    private Integer toBlock;

    private String createdBy;

    private Map<String, Object> config;
}
