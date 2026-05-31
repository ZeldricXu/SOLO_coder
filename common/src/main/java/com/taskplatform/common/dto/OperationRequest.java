package com.taskplatform.common.dto;

import lombok.Data;
import java.util.List;

@Data
public class OperationRequest {

    private List<Operation> operations;

    @Data
    public static class Operation {
        private String action;
        private String id;
        private Object params;
    }
}
