package com.didauth.module.event.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;
import java.util.Map;

@Data
public class RegisterEventRequest implements Serializable {

    @NotBlank(message = "chainType不能为空")
    private String chainType;

    @NotBlank(message = "contractAddress不能为空")
    private String contractAddress;

    @NotBlank(message = "eventName不能为空")
    private String eventName;

    private String topic0;
    private String topic1;
    private String topic2;
    private String topic3;

    private Map<String, Object> filterParams;

    @NotBlank(message = "callbackUrl不能为空")
    private String callbackUrl;

    private String callbackType = "HTTP_POST";

    private String userId;
}
