package com.didauth.module.chainadaptor.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class RpcResponse<T> implements Serializable {

    private String jsonrpc;
    private Integer id;
    private T result;
    private RpcError error;

    @Data
    public static class RpcError implements Serializable {
        private Integer code;
        private String message;
        private String data;
    }
}
