package com.device.platform.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
public class BatchOperationRequest {
    @NotEmpty(message = "operations不能为空")
    @Valid
    private List<BatchOperation> operations;

    @Data
    public static class BatchOperation {
        @NotBlank(message = "action不能为空")
        private String action;

        @NotBlank(message = "id不能为空")
        private String id;

        private Map<String, Object> params;
    }
}
