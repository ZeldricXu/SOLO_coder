package com.modelguard.dto;

import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import java.io.Serializable;

@Data
public class PromptVersionDTO implements Serializable {

    private String promptId;

    @NotBlank(message = "Prompt内容不能为空")
    @Size(max = 50000, message = "Prompt内容不能超过50000字符")
    private String content;

    private ObjectNode variables;

    private String createdBy;

    @Size(max = 512, message = "描述不能超过512字符")
    private String description;
}
