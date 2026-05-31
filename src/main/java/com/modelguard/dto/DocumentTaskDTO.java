package com.modelguard.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import java.io.Serializable;

@Data
public class DocumentTaskDTO implements Serializable {

    @NotBlank(message = "管道ID不能为空")
    private String pipelineId;

    @NotBlank(message = "文件名不能为空")
    private String fileName;

    @NotBlank(message = "文件路径不能为空")
    private String filePath;

    private Long fileSize;

    private String vectorStore;
}
