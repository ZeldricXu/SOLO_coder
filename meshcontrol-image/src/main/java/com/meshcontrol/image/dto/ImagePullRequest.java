package com.meshcontrol.image.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ImagePullRequest {

    @NotBlank(message = "image is required")
    private String image;

    private String tag = "latest";
    private String registryId;
    private Boolean p2pEnabled = false;
}
