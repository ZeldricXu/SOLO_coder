package com.modelguard.dto;

import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import java.io.Serializable;

@Data
public class ChunkCreateDTO implements Serializable {

    @NotBlank(message = "任务ID不能为空")
    private String taskId;

    @NotBlank(message = "块内容不能为空")
    private String content;

    private ObjectNode metadata;

    private String embedding;

    private Integer pageNumber;

    private Integer startIndex;

    private Integer endIndex;
}
