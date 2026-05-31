package com.chaoslab.modules.image.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ImageSyncRequest {

    @NotBlank(message = "源仓库ID不能为空")
    private String sourceRepoId;

    @NotBlank(message = "目标仓库ID不能为空")
    private String targetRepoId;

    @NotBlank(message = "镜像引用不能为空")
    private String imageReference;

    private String strategy = "layered";

    private Boolean p2pEnabled = false;
}
