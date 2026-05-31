package com.chain.infrastructure.chainadapter.dto;

import lombok.Data;

@Data
public class RpcRequest {

    private String jsonrpc = "2.0";

    private String method;

    private Object[] params;

    private Long id;

    public RpcRequest() {
        this.id = System.currentTimeMillis();
    }

    public RpcRequest(String method, Object... params) {
        this();
        this.method = method;
        this.params = params;
    }
}
