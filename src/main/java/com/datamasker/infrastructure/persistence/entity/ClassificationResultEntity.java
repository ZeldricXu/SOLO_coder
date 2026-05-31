package com.datamasker.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("dm_classification_result")
public class ClassificationResultEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    private String dataSource;

    private String fieldName;

    private String category;

    private String level;

    private Double confidence;

    private LocalDateTime createdAt;
}
