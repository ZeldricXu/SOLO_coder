package com.chain.infrastructure.chainadapter.dto;

import lombok.Data;

@Data
public class RpcResponse<T> {

    private String jsonrpc;

    private Long id;

    private T result;

    private RpcError error;

    @Data
    public static class RpcError {
        private Integer code;
        private String message;
        private Object data;
    }
}
