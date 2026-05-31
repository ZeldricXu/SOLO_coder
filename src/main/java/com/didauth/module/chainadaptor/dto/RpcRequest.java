package com.didauth.module.chainadaptor.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class RpcRequest implements Serializable {

    private String jsonrpc = "2.0";
    private String method;
    private Object[] params;
    private Integer id = 1;
}
