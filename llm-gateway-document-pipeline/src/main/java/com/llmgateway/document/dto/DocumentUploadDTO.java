package com.llmgateway.document.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import java.io.Serializable;
import java.util.Map;

@Data
public class DocumentUploadDTO implements Serializable {

    private String title;

    @NotBlank(message = "文件名不能为空")
    private String fileName;

    @NotBlank(message = "文件类型不能为空")
    private String fileType;

    private Long fileSize;

    private String charset;

    private String language;

    private Map<String, Object> metadata;

    private String createdBy;

    private String content;
}
