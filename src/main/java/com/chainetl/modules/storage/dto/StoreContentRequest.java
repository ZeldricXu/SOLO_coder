package com.chainetl.modules.storage.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StoreContentRequest {

    @NotBlank(message = "storageType is required")
    private String storageType;

    @NotNull(message = "content is required")
    private String content;

    private Boolean pin;

    private Map<String, Object> metadata;
}
