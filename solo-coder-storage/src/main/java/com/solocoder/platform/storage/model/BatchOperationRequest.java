package com.solocoder.platform.storage.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchOperationRequest {

    @NotEmpty
    @Valid
    private List<BatchOperation> operations;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BatchOperation {
        @NotNull
        private OperationType type;

        @NotNull
        private String key;

        private byte[] data;

        private Map<String, String> metadata;
    }

    public enum OperationType {
        PUT,
        GET,
        DELETE
    }
}
