package com.edgeplatform.common.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

@Data
public class BatchOperationRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private List<Operation> operations;

    @Data
    public static class Operation implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private String action;
        private String id;
    }
}
