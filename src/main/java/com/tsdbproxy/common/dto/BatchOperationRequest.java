package com.tsdbproxy.common.dto;

import lombok.Data;

import java.util.List;

@Data
public class BatchOperationRequest {

    private List<Operation> operations;

    @Data
    public static class Operation {
        private String action;
        private String id;
    }
}
