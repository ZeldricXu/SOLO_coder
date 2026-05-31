package com.nftindexer.modules.common.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class ResourceBatchRequest {

    @Valid
    @NotEmpty(message = "操作列表不能为空")
    private List<BatchOperation> operations;

    @Data
    public static class BatchOperation {
        @NotBlank(message = "操作类型不能为空")
        private String action;

        @NotBlank(message = "资源ID不能为空")
        private String id;

        private Map<String, Object> parameters;
    }
}
