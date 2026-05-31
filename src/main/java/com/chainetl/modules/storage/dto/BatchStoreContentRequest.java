package com.chainetl.modules.storage.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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
public class BatchStoreContentRequest {

    @NotBlank(message = "storageType is required")
    private String storageType;

    @NotNull(message = "items is required")
    @Size(min = 1, max = 100, message = "items size must be between 1 and 100")
    private List<BatchItem> items;

    private Boolean pin;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BatchItem {
        @NotNull(message = "content is required")
        private String content;
        private Map<String, Object> metadata;
    }
}
