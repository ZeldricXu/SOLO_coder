package com.observability.common.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
public class BatchOperationRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    private List<Operation> operations;

    @Data
    public static class Operation implements Serializable {
        private String action;
        private String id;
        private Map<String, Object> params;
    }
}
