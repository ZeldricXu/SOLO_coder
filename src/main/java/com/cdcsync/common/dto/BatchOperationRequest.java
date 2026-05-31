package com.cdcsync.common.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

@Data
public class BatchOperationRequest {

    @NotEmpty(message = "操作列表不能为空")
    @Valid
    private List<Operation> operations;

    @Data
    public static class Operation {
        private String action;
        private String id;
        private Object params;
    }
}
