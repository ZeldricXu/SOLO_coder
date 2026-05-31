package com.edgescheduler.inference.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.edgescheduler.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "ai_model", autoResultMap = true)
public class AiModel extends BaseEntity {

    private static final long serialVersionUID = 1L;

    private String modelId;
    private String modelName;
    private String modelVersion;
    private String framework;
    private String modelType;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> modelSpec;

    private Long modelSize;
    private String downloadUrl;
    private String md5Checksum;
    private String status;
    private String description;

    public interface Status {
        String UPLOADED = "uploaded";
        String DEPLOYING = "deploying";
        String DEPLOYED = "deployed";
        String FAILED = "failed";
        String DISABLED = "disabled";
    }

    public interface Framework {
        String TENSORFLOW = "tensorflow";
        String PYTORCH = "pytorch";
        String ONNX = "onnx";
        String TFLITE = "tflite";
        String OPENVINO = "openvino";
    }

    public interface ModelType {
        String CLASSIFICATION = "classification";
        String DETECTION = "detection";
        String SEGMENTATION = "segmentation";
        String ANOMALY_DETECTION = "anomaly_detection";
        String PREDICTION = "prediction";
        String CUSTOM = "custom";
    }
}
