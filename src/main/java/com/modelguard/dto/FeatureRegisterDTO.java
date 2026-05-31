package com.modelguard.dto;

import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.io.Serializable;

@Data
public class FeatureRegisterDTO implements Serializable {

    @NotBlank(message = "特征名称不能为空")
    private String name;

    private String description;

    private String featureId;

    @NotBlank(message = "数据类型不能为空")
    private String dataType;

    @NotBlank(message = "特征类型不能为空")
    private String featureType;

    @NotBlank(message = "所属实体不能为空")
    private String entity;

    private String source;

    private Long ttlSeconds;

    private ObjectNode schemaDef;

    private String createdBy;
}
