package com.solocoder.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("features")
public class FeatureEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String featureName;

    private String entityId;

    private String featureValue;

    private Instant eventTime;

    private String source;

    private Instant createdAt;
}
