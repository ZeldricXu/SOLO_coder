package com.metricplatform.common;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
public class BatchOperationRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    @Valid
    @NotEmpty(message = "操作列表不能为空")
    private List<BatchOperation> operations;

    @Data
    public static class BatchOperation implements Serializable {
        private String action;
        private String id;
        private Object params;
    }
}
