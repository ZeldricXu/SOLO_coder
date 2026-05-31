package com.meshcontrol.image.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

@Data
public class ImageSyncRequest {

    @NotBlank(message = "sourceRegistryId is required")
    private String sourceRegistryId;

    @NotBlank(message = "targetRegistryId is required")
    private String targetRegistryId;

    @NotBlank(message = "sourceRepo is required")
    private String sourceRepo;

    @NotBlank(message = "targetRepo is required")
    private String targetRepo;

    private List<String> tagFilter;
}
