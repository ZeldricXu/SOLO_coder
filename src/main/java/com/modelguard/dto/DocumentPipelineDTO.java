package com.modelguard.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.io.Serializable;

@Data
public class DocumentPipelineDTO implements Serializable {

    @NotBlank(message = "管道名称不能为空")
    private String name;

    private String description;

    @NotBlank(message = "源文件类型不能为空")
    private String sourceType;

    @Min(value = 100, message = "分块大小最小为100")
    @Max(value = 4000, message = "分块大小最大为4000")
    private Integer chunkSize = 512;

    @Min(value = 0, message = "分块重叠最小为0")
    @Max(value = 500, message = "分块重叠最大为500")
    private Integer chunkOverlap = 50;

    @NotBlank(message = "向量模型不能为空")
    private String embeddingModel;

    @NotNull(message = "向量维度不能为空")
    @Min(value = 128, message = "向量维度最小为128")
    @Max(value = 4096, message = "向量维度最大为4096")
    private Integer vectorDimension;

    private String createdBy;
}
