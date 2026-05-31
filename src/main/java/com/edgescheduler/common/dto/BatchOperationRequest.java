package com.edgescheduler.common.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
public class BatchOperationRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    @Valid
    @NotEmpty(message = "operations cannot be empty")
    private List<BatchOperation> operations;

    @Data
    public static class BatchOperation implements Serializable {
        private static final long serialVersionUID = 1L;

        @NotEmpty(message = "action cannot be empty")
        private String action;

        @NotEmpty(message = "id cannot be empty")
        private String id;

        private Object params;
    }
}
